package dev.frontiersurvival.village;

import com.mojang.serialization.Codec;
import dev.frontiersurvival.FrontierSurvival;
import dev.frontiersurvival.entity.GuardEntity;
import dev.frontiersurvival.entity.QuartermasterEntity;
import dev.frontiersurvival.settlement.SettlementBoardBlockEntity;
import dev.frontiersurvival.worldgen.Palette;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.StructureTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Fortifies and garrisons vanilla villages with chunk-clipped procedural additions. */
public final class VillageDefences {
    public static final DeferredRegister<Feature<?>> FEATURES = DeferredRegister.create(ForgeRegistries.FEATURES, FrontierSurvival.ID);
    public static final RegistryObject<Feature<NoneFeatureConfiguration>> VILLAGE_DEFENCES = FEATURES.register("village_defences",
            () -> new VillageDefenceFeature(NoneFeatureConfiguration.CODEC));
    /** Plans are pure functions of a village's pieces; weak keys let unloaded villages be forgotten. */
    private static final Map<StructureStart, DefencePlan> CACHE = Collections.synchronizedMap(new WeakHashMap<>());
    /**
     * Chunks within this radius of the world origin are left undefended. Only the GameTest run sets it, so village
     * garrisons never wander into the unrelated test arenas built there.
     */
    private static final int CLEAR_RADIUS_CHUNKS = Integer.getInteger("frontiersurvival.villageDefences.clearRadiusChunks", 0);
    private static final ResourceLocation STALL_SUPPLIES =
            ResourceLocation.fromNamespaceAndPath(FrontierSurvival.ID, "chests/hamlet_supplies");

    private VillageDefences() {}

    public static void register(IEventBus bus) { FEATURES.register(bus); }

    public static DefencePlan planForTest(ServerLevel level, StructureStart start) { return createPlan(level, start); }

    private static DefencePlan cachedPlan(WorldGenLevel level, StructureStart start) {
        synchronized (CACHE) {
            DefencePlan plan = CACHE.get(start);
            if (plan == null) {
                plan = createPlan(level, start);
                CACHE.put(start, plan);
            }
            return plan;
        }
    }

    private static DefencePlan createPlan(WorldGenLevel level, StructureStart start) {
        List<Box> buildings = new ArrayList<>();
        List<Box> occupied = new ArrayList<>();
        List<Box> streets = new ArrayList<>();
        List<StructurePiece> pieces = new ArrayList<>(start.getPieces());
        pieces.sort(Comparator.comparingInt((StructurePiece p) -> p.getBoundingBox().minX())
                .thenComparingInt(p -> p.getBoundingBox().minZ()).thenComparingInt(p -> p.getBoundingBox().minY()));
        for (StructurePiece piece : pieces) {
            Box box = Box.of(piece.getBoundingBox());
            occupied.add(box);
            boolean street = piece instanceof PoolElementStructurePiece pool
                    && pool.getElement().getProjection() == StructureTemplatePool.Projection.TERRAIN_MATCHING;
            if (street) streets.add(box); else buildings.add(box);
        }
        if (buildings.isEmpty()) buildings.addAll(occupied);
        if (buildings.isEmpty()) return DefencePlan.empty(Palette.OAK);

        BoundingBox full = start.getBoundingBox();
        BlockPos center = full.getCenter();
        ResourceLocation id = level.registryAccess().registryOrThrow(Registries.STRUCTURE).getKey(start.getStructure());
        if (id == null) id = ResourceLocation.fromNamespaceAndPath("minecraft", "village_plains");
        Palette palette = Palette.forVillage(id, level.getBiome(center));

        List<Cell> points = new ArrayList<>();
        for (Box box : buildings) {
            Box e = box.expand(5);
            points.add(new Cell(e.minX, e.minZ)); points.add(new Cell(e.minX, e.maxZ));
            points.add(new Cell(e.maxX, e.minZ)); points.add(new Cell(e.maxX, e.maxZ));
        }
        List<Cell> hull = hull(points);
        if (hull.size() < 3) return DefencePlan.empty(palette);
        LinkedHashSet<Cell> ringSet = rasterHull(hull);
        // Only buildings push the ring away; where a street crosses it, the crossing becomes a gate.
        ringSet.removeIf(cell -> intersectsAny(buildings, cell.x, cell.z, 1));
        List<Cell> ring = sortRing(ringSet, center.getX(), center.getZ());
        if (ring.isEmpty()) return DefencePlan.empty(palette);

        Set<Cell> gates = new HashSet<>();
        for (Cell cell : ring) if (containsAny(streets, cell.x, cell.z, 1)) gates.add(cell);
        if (gates.isEmpty()) addFallbackGates(ring, gates, center);
        widenGates(ring, gates, 1);
        List<Gate> gateGroups = gates(ring, gates);
        Set<Cell> gatePosts = new HashSet<>();
        int n = ring.size();
        for (Gate gate : gateGroups) {
            gatePosts.add(ring.get(Math.floorMod(gate.start - 1, n)));
            gatePosts.add(ring.get(Math.floorMod(gate.end + 1, n)));
        }

        List<Tower> towers = new ArrayList<>();
        for (int i = 0; i < ring.size() && towers.size() < 6; i += 45) {
            for (int slide = 0; slide <= 8; slide++) {
                int idx = (i + (slide % 2 == 0 ? slide / 2 : -(slide + 1) / 2) + ring.size()) % ring.size();
                Cell wall = ring.get(idx);
                if (gates.contains(wall) || gatePosts.contains(wall)) continue;
                Cell inside = inside(wall, center, 4);
                Box footprint = new Box(inside.x - 2, inside.z - 2, inside.x + 2, inside.z + 2);
                if (oneChunk(footprint) && !intersectsAnyBox(occupied, footprint.expand(1))
                        && !intersectsCells(ringSet, footprint) && !nearTower(towers, inside)) {
                    towers.add(new Tower(inside, wall, towerSpots(inside, occupied, ringSet)));
                    break;
                }
            }
        }

        List<Post> guards = new ArrayList<>();
        for (Gate gate : gateGroups) {
            if (guards.size() >= 4) break;
            Cell mid = gate.mid(ring);
            // Stand just inside the gateway, never on a rooftop or inside a house.
            for (int distance : new int[]{3, 2, 4, 1}) {
                Cell spot = inside(mid, center, distance);
                if (!intersectsAny(buildings, spot.x, spot.z, 0) && !ringSet.contains(spot)) {
                    guards.add(new Post(spot, 10));
                    break;
                }
            }
        }

        @Nullable Stall stall = findStall(occupied, ringSet, towers, center, palette);
        return new DefencePlan(palette, List.copyOf(ring), Set.copyOf(gates), Set.copyOf(gatePosts),
                List.copyOf(gateGroups), List.copyOf(towers), List.copyOf(guards), stall,
                List.copyOf(buildings), List.copyOf(occupied));
    }

    /**
     * Where a tower may stand, best first: its planned spot, then nearby spots in the same chunk that keep clear
     * of the ring and every village piece. The chunk picks the first spot that is dry and buildable.
     */
    private static List<Cell> towerSpots(Cell planned, List<Box> occupied, Set<Cell> ring) {
        int[][] offsets = {{0, 0}, {2, 0}, {-2, 0}, {0, 2}, {0, -2}, {2, 2}, {-2, 2}, {2, -2}, {-2, -2},
                {4, 0}, {-4, 0}, {0, 4}, {0, -4}};
        int chunkX = planned.x >> 4, chunkZ = planned.z >> 4;
        List<Cell> spots = new ArrayList<>();
        for (int[] offset : offsets) {
            Cell spot = new Cell(planned.x + offset[0], planned.z + offset[1]);
            Box footprint = new Box(spot.x - 2, spot.z - 2, spot.x + 2, spot.z + 2);
            if (footprint.minX >> 4 != chunkX || footprint.maxX >> 4 != chunkX
                    || footprint.minZ >> 4 != chunkZ || footprint.maxZ >> 4 != chunkZ) continue;
            if (intersectsAnyBox(occupied, footprint.expand(1)) || intersectsCells(ring, footprint)) continue;
            spots.add(spot);
        }
        return List.copyOf(spots);
    }

    private static @Nullable Stall findStall(List<Box> occupied, Set<Cell> ring, List<Tower> towers, BlockPos center,
                                             Palette palette) {
        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};
        for (int r = 7; r <= 28; r += 3) for (int[] d : dirs) {
            Cell c = new Cell(center.getX() + d[0] * r, center.getZ() + d[1] * r);
            Box area = new Box(c.x - 2, c.z - 2, c.x + 2, c.z + 2);
            boolean clearOfTowers = true;
            for (Tower tower : towers) for (Cell spot : tower.spots) {
                clearOfTowers &= !area.expand(1).intersects(new Box(spot.x - 2, spot.z - 2, spot.x + 2, spot.z + 2));
            }
            if (oneChunk(area) && clearOfTowers && !intersectsAnyBox(occupied, area.expand(1))
                    && !intersectsCells(ring, area.expand(1))) {
                return new Stall(c, palette);
            }
        }
        return null;
    }

    private static boolean nearTower(List<Tower> towers, Cell c) {
        for (Tower t : towers) if (Math.abs(t.center.x - c.x) < 10 && Math.abs(t.center.z - c.z) < 10) return true;
        return false;
    }

    private static boolean oneChunk(Box b) { return (b.minX >> 4) == (b.maxX >> 4) && (b.minZ >> 4) == (b.maxZ >> 4); }
    private static boolean intersectsCells(Set<Cell> cells, Box b) { for (Cell c : cells) if (b.contains(c.x, c.z)) return true; return false; }
    private static boolean intersectsAnyBox(List<Box> boxes, Box b) { for (Box o : boxes) if (b.intersects(o)) return true; return false; }
    private static boolean intersectsAny(List<Box> boxes, int x, int z, int expand) { for (Box b : boxes) if (b.expand(expand).contains(x, z)) return true; return false; }
    private static boolean containsAny(List<Box> boxes, int x, int z, int expand) { for (Box b : boxes) if (b.expand(expand).contains(x, z)) return true; return false; }

    private static void addFallbackGates(List<Cell> ring, Set<Cell> gates, BlockPos center) {
        addGateAt(ring, gates, closest(ring, c -> Math.abs(c.x - center.getX())));
        addGateAt(ring, gates, closest(ring, c -> Math.abs(c.z - center.getZ())));
    }
    private interface Score { int score(Cell c); }
    private static int closest(List<Cell> ring, Score score) {
        int best = 0, bestScore = Integer.MAX_VALUE;
        for (int i = 0; i < ring.size(); i++) { int s = score.score(ring.get(i)); if (s < bestScore) { best = i; bestScore = s; } }
        return best;
    }
    private static void addGateAt(List<Cell> ring, Set<Cell> gates, int idx) { for (int d = -1; d <= 1; d++) gates.add(ring.get(Math.floorMod(idx + d, ring.size()))); }
    private static void widenGates(List<Cell> ring, Set<Cell> gates, int radius) {
        List<Integer> hits = new ArrayList<>();
        for (int i = 0; i < ring.size(); i++) if (gates.contains(ring.get(i))) hits.add(i);
        for (int i : hits) for (int d = -radius; d <= radius; d++) gates.add(ring.get(Math.floorMod(i + d, ring.size())));
    }

    private static List<Gate> gates(List<Cell> ring, Set<Cell> gates) {
        List<Gate> groups = new ArrayList<>();
        int n = ring.size();
        boolean[] g = new boolean[n];
        for (int i = 0; i < n; i++) g[i] = gates.contains(ring.get(i));
        for (int i = 0; i < n; i++) {
            if (!g[i] || g[Math.floorMod(i - 1, n)]) continue;
            int end = i;
            while (g[(end + 1) % n] && (end + 1) % n != i) end = (end + 1) % n;
            groups.add(new Gate(i, end));
        }
        if (groups.isEmpty() && n > 0 && g[0]) groups.add(new Gate(0, n - 1));
        return groups;
    }

    private static Cell inside(Cell c, BlockPos center, int distance) {
        int dx = Integer.compare(center.getX(), c.x), dz = Integer.compare(center.getZ(), c.z);
        return new Cell(c.x + dx * distance, c.z + dz * distance);
    }

    private static List<Cell> hull(List<Cell> points) {
        List<Cell> p = new ArrayList<>(new LinkedHashSet<>(points));
        p.sort(Comparator.comparingInt((Cell c) -> c.x).thenComparingInt(c -> c.z));
        if (p.size() <= 1) return p;
        List<Cell> lower = new ArrayList<>(), upper = new ArrayList<>();
        for (Cell c : p) { while (lower.size() >= 2 && cross(lower.get(lower.size()-2), lower.get(lower.size()-1), c) <= 0) lower.remove(lower.size()-1); lower.add(c); }
        for (int i = p.size() - 1; i >= 0; i--) { Cell c = p.get(i); while (upper.size() >= 2 && cross(upper.get(upper.size()-2), upper.get(upper.size()-1), c) <= 0) upper.remove(upper.size()-1); upper.add(c); }
        lower.remove(lower.size()-1); upper.remove(upper.size()-1); lower.addAll(upper); return lower;
    }
    private static long cross(Cell a, Cell b, Cell c) { return (long)(b.x-a.x)*(c.z-a.z) - (long)(b.z-a.z)*(c.x-a.x); }

    private static LinkedHashSet<Cell> rasterHull(List<Cell> hull) {
        LinkedHashSet<Cell> cells = new LinkedHashSet<>();
        for (int i = 0; i < hull.size(); i++) line(hull.get(i), hull.get((i + 1) % hull.size()), cells);
        return cells;
    }
    private static void line(Cell a, Cell b, Set<Cell> out) {
        int x = a.x, z = a.z, dx = Math.abs(b.x - a.x), dz = Math.abs(b.z - a.z);
        int sx = a.x < b.x ? 1 : -1, sz = a.z < b.z ? 1 : -1, err = dx - dz;
        while (true) {
            out.add(new Cell(x, z));
            if (x == b.x && z == b.z) break;
            int e2 = err * 2;
            if (e2 > -dz) { err -= dz; x += sx; }
            if (e2 < dx) { err += dx; z += sz; }
        }
    }
    private static List<Cell> sortRing(Set<Cell> ring, int cx, int cz) {
        List<Cell> cells = new ArrayList<>(ring);
        cells.sort(Comparator.comparingDouble(c -> Math.atan2(c.z - cz, c.x - cx)));
        return cells;
    }

    public record Cell(int x, int z) {}
    public record Box(int minX, int minZ, int maxX, int maxZ) {
        static Box of(BoundingBox box) { return new Box(box.minX(), box.minZ(), box.maxX(), box.maxZ()); }
        Box expand(int n) { return new Box(minX - n, minZ - n, maxX + n, maxZ + n); }
        boolean contains(int x, int z) { return x >= minX && x <= maxX && z >= minZ && z <= maxZ; }
        boolean intersects(Box o) { return maxX >= o.minX && minX <= o.maxX && maxZ >= o.minZ && minZ <= o.maxZ; }
    }
    public record Gate(int start, int end) {
        public Cell mid(List<Cell> ring) { int n = ring.size(); int len = end >= start ? end - start + 1 : end + n - start + 1; return ring.get((start + len / 2) % n); }
    }
    public record Tower(Cell center, Cell wall, List<Cell> spots) {}
    public record Post(Cell center, int radius) {}
    public record Stall(Cell center, Palette palette) {}
    public record DefencePlan(Palette palette, List<Cell> ring, Set<Cell> gates, Set<Cell> gatePosts, List<Gate> gateGroups,
                              List<Tower> towers, List<Post> gateGuards, @Nullable Stall stall, List<Box> buildings,
                              List<Box> occupied) {
        static DefencePlan empty(Palette palette) { return new DefencePlan(palette, List.of(), Set.of(), Set.of(), List.of(), List.of(), List.of(), null, List.of(), List.of()); }
        public boolean nonGateWallInsideBuilding() { for (Cell c : ring) if (!gates.contains(c) && intersectsAny(buildings, c.x, c.z, 1)) return true; return false; }
    }

    private static final class VillageDefenceFeature extends Feature<NoneFeatureConfiguration> {
        VillageDefenceFeature(Codec<NoneFeatureConfiguration> codec) { super(codec); }

        @Override public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
            WorldGenLevel level = context.level();
            try {
                ChunkPos current = new ChunkPos(context.origin());
                if (!level.getLevel().dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) return false;
                if (Math.abs(current.x) < CLEAR_RADIUS_CHUNKS && Math.abs(current.z) < CLEAR_RADIUS_CHUNKS) return false;
                var structures = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
                List<Holder<Structure>> villages = structures.getTag(StructureTags.VILLAGE)
                        .map(named -> named.stream().toList()).orElse(List.of());
                if (villages.isEmpty()) return false;
                boolean placed = false;
                Set<StructureStart> starts = Collections.newSetFromMap(new IdentityHashMap<>());
                // Every chunk within 8 already has its structure starts while features generate, and a village's
                // ring stays within about 6 chunks of its start, so this scan sees every village it could touch.
                for (int cx = current.x - 8; cx <= current.x + 8; cx++) for (int cz = current.z - 8; cz <= current.z + 8; cz++) {
                    ChunkAccess chunk = level.getChunk(cx, cz, ChunkStatus.STRUCTURE_STARTS, false);
                    if (chunk == null) continue;
                    for (Holder<Structure> holder : villages) {
                        StructureStart start = chunk.getStartForStructure(holder.value());
                        if (start != null && start.isValid()) starts.add(start);
                    }
                }
                // Villages referencing this chunk, resolved through this generation region only (never off-thread).
                if (level instanceof WorldGenRegion region) {
                    starts.addAll(region.getLevel().structureManager().forWorldGenRegion(region).startsForStructure(current,
                            structure -> structures.wrapAsHolder(structure).is(StructureTags.VILLAGE)));
                }
                for (StructureStart start : starts) {
                    DefencePlan plan = cachedPlan(level, start);
                    if (!plan.ring.isEmpty()) placed |= placePlan(level, current, plan, context.random());
                }
                if (placed) FrontierSurvival.LOGGER.debug("Placed village defences in chunk {}", current);
                return placed;
            } catch (Throwable t) {
                FrontierSurvival.LOGGER.warn("Skipping village defences at {}", context.origin(), t);
                return false;
            }
        }
    }

    private static boolean placePlan(WorldGenLevel level, ChunkPos chunk, DefencePlan plan, RandomSource random) {
        boolean placed = false;
        for (int i = 0; i < plan.ring.size(); i++) {
            Cell cell = plan.ring.get(i);
            if (!inChunk(chunk, cell)) continue;
            if (plan.gates.contains(cell)) { placed |= placeGate(level, cell, plan.palette); continue; }
            boolean post = plan.gatePosts.contains(cell);
            placed |= placeWall(level, cell, plan.palette, post ? 5 : (i % 8 == 0 ? 4 : 3), post || i % 8 == 0);
        }
        for (Tower tower : plan.towers) if (inChunk(chunk, tower.center)) placed |= placeTower(level, tower, plan);
        for (Post guard : plan.gateGuards) if (inChunk(chunk, guard.center)) placed |= spawnGuard(level, guard.center, false, guard.radius);
        if (plan.stall != null && inChunk(chunk, plan.stall.center)) placed |= placeStall(level, plan.stall, random);
        return placed;
    }

    private static boolean inChunk(ChunkPos chunk, Cell cell) { return (cell.x >> 4) == chunk.x && (cell.z >> 4) == chunk.z; }

    /** The ground under a column; canopies that neighbouring chunks spread over it are looked through. */
    private static int ground(WorldGenLevel level, int x, int z) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z) - 1, z);
        while (pos.getY() > level.getMinBuildHeight() && clearable(level.getBlockState(pos))) pos.move(Direction.DOWN);
        return pos.getY();
    }

    /** Air, plants and overhanging leaves may give way to defences; village blocks and other structures never do. */
    private static boolean clearable(BlockState state) {
        return state.is(BlockTags.LEAVES) || state.getBlock() instanceof BushBlock
                || state.canBeReplaced() && state.getFluidState().isEmpty();
    }

    private static boolean wet(WorldGenLevel level, BlockPos ground) {
        return !level.getFluidState(ground).isEmpty() || !level.getFluidState(ground.above()).isEmpty();
    }

    private static boolean canReplaceColumn(WorldGenLevel level, BlockPos ground, int height) {
        if (wet(level, ground)) return false;
        for (int y = 1; y <= height; y++) if (!clearable(level.getBlockState(ground.above(y)))) return false;
        return true;
    }

    private static boolean placeWall(WorldGenLevel level, Cell c, Palette palette, int height, boolean lantern) {
        BlockPos g = new BlockPos(c.x, ground(level, c.x, c.z), c.z);
        // A neighbouring tree's trunk standing on the line keeps its place; the palisade simply gaps around it.
        if (level.getBlockState(g).is(BlockTags.LOGS) || !canReplaceColumn(level, g, height + (lantern ? 1 : 0))) return false;
        set(level, g, palette.foundation());
        for (int y = 1; y <= height; y++) set(level, g.above(y), palette.palisade());
        if (lantern) set(level, g.above(height + 1), Blocks.LANTERN.defaultBlockState());
        return true;
    }

    private static boolean placeGate(WorldGenLevel level, Cell c, Palette palette) {
        BlockPos g = new BlockPos(c.x, ground(level, c.x, c.z), c.z);
        if (wet(level, g)) return false;
        // Village roads keep their own surface; open ground in a gateway becomes trodden path.
        if (level.getBlockState(g).is(BlockTags.DIRT)) set(level, g, palette.path());
        for (int y = 1; y <= 4; y++) {
            if (clearable(level.getBlockState(g.above(y)))) set(level, g.above(y), Blocks.AIR.defaultBlockState());
        }
        return true;
    }

    /**
     * A roofed 5x5 lookout: walled ground floor with a doorway toward the village and arrow slits, a ladder against
     * the back wall up through a full deck, a fenced parapet and a slab roof on corner posts. An archer holds the deck.
     * It stands on the first of its planned spots that is dry and not too steep.
     */
    private static boolean placeTower(WorldGenLevel level, Tower tower, DefencePlan plan) {
        for (Cell spot : tower.spots) {
            if (placeTowerAt(level, spot, tower.wall, plan)) return true;
        }
        return false;
    }

    private static boolean placeTowerAt(WorldGenLevel level, Cell center, Cell wall, DefencePlan plan) {
        int[] ys = new int[25];
        int i = 0, min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            int y = ground(level, center.x + dx, center.z + dz);
            if (wet(level, new BlockPos(center.x + dx, y, center.z + dz))) return false;
            ys[i++] = y;
            min = Math.min(min, y);
            max = Math.max(max, y);
        }
        // Up to six blocks of slope: the plinth fills the low side and the high side is cut back.
        if (max - min > 6) return false;
        java.util.Arrays.sort(ys);
        int base = ys[12];
        Tower tower = new Tower(center, wall, List.of(center));
        Palette palette = plan.palette;
        Direction door = facingToward(tower.center, tower.wall).getOpposite();
        Direction back = door.getOpposite();
        BlockState planks = palette.planks(), frame = palette.frame(), slab = palette.slab(), air = Blocks.AIR.defaultBlockState();
        BlockState railX = palette.fence().setValue(BlockStateProperties.EAST, true).setValue(BlockStateProperties.WEST, true);
        BlockState railZ = palette.fence().setValue(BlockStateProperties.NORTH, true).setValue(BlockStateProperties.SOUTH, true);
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            int x = tower.center.x + dx, z = tower.center.z + dz;
            for (int y = ground(level, x, z); y < base; y++) set(level, new BlockPos(x, y, z), palette.foundation());
            set(level, new BlockPos(x, base, z), planks);
            boolean corner = Math.abs(dx) == 2 && Math.abs(dz) == 2;
            boolean edge = Math.abs(dx) == 2 || Math.abs(dz) == 2;
            for (int h = 1; h <= 10; h++) {
                BlockState state;
                if (h <= 5) state = corner ? frame : edge ? planks : air;
                else if (h == 6) state = planks;
                else if (h <= 9) state = corner ? frame : edge && h == 7 ? (Math.abs(dz) == 2 ? railX : railZ) : air;
                else state = slab;
                set(level, new BlockPos(x, base + h, z), state);
            }
        }
        BlockPos doorway = new BlockPos(tower.center.x + door.getStepX() * 2, base + 1, tower.center.z + door.getStepZ() * 2);
        set(level, doorway, air);
        set(level, doorway.above(), air);
        for (Direction side : Direction.Plane.HORIZONTAL) {
            if (side.getAxis() == door.getAxis()) continue;
            set(level, new BlockPos(tower.center.x + side.getStepX() * 2, base + 3, tower.center.z + side.getStepZ() * 2), air);
        }
        // Rungs hang on the solid back wall and, at deck height, on the deck's back edge.
        BlockPos ladder = new BlockPos(tower.center.x + back.getStepX(), base + 1, tower.center.z + back.getStepZ());
        BlockState rung = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, door);
        for (int h = 0; h <= 5; h++) set(level, ladder.above(h), rung);
        set(level, new BlockPos(tower.center.x, base + 9, tower.center.z),
                Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        spawnGuard(level, new Cell(tower.center.x, tower.center.z), true, 2, base + 7);
        return true;
    }

    private static Direction facingToward(Cell from, Cell to) {
        int dx = to.x - from.x, dz = to.z - from.z;
        return Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
    }

    /** A canopied market stall on settlement turf with the village charter, stores and a quartermaster. */
    private static boolean placeStall(WorldGenLevel level, Stall stall, RandomSource random) {
        int[] ys = new int[25];
        int i = 0, min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            int y = ground(level, stall.center.x + dx, stall.center.z + dz);
            if (wet(level, new BlockPos(stall.center.x + dx, y, stall.center.z + dz))) return false;
            ys[i++] = y;
            min = Math.min(min, y);
            max = Math.max(max, y);
        }
        if (max - min > 8) return false;
        java.util.Arrays.sort(ys);
        // Terraced into the slope at the median height: low corners are filled, high ground is cut back.
        int base = ys[12];
        BlockState turf = FrontierSurvival.SETTLEMENT_GRASS.get().defaultBlockState();
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            int x = stall.center.x + dx, z = stall.center.z + dz;
            int y0 = ground(level, x, z);
            for (int y = y0; y < base; y++) set(level, new BlockPos(x, y, z), stall.palette.foundation());
            set(level, new BlockPos(x, base, z), turf);
            for (int y = base + 1; y <= Math.max(base + 4, y0); y++) set(level, new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
        }
        for (int dx : new int[]{-2, 2}) for (int dz : new int[]{-2, 2}) for (int h = 1; h <= 3; h++) {
            set(level, new BlockPos(stall.center.x + dx, base + h, stall.center.z + dz), stall.palette.fence());
        }
        BlockState wool = BuiltInRegistries.BLOCK.get(ResourceLocation.withDefaultNamespace(stall.palette.accent().getName() + "_wool")).defaultBlockState();
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            if (Math.abs(dx) == 2 || Math.abs(dz) == 2 || (dx + dz & 1) == 0) {
                set(level, new BlockPos(stall.center.x + dx, base + 4, stall.center.z + dz), wool);
            }
        }
        BlockPos barrel = new BlockPos(stall.center.x - 1, base + 1, stall.center.z);
        set(level, barrel, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.SOUTH));
        RandomizableContainerBlockEntity.setLootTable(level, random, barrel, STALL_SUPPLIES);
        BlockPos charter = new BlockPos(stall.center.x, base + 1, stall.center.z);
        set(level, charter, FrontierSurvival.BOARD.get().defaultBlockState());
        // The charter registers itself with the settlement record when its chunk loads on the server thread.
        if (level.getBlockEntity(charter) instanceof SettlementBoardBlockEntity board) board.setVillage();
        BlockPos chest = new BlockPos(stall.center.x + 1, base + 1, stall.center.z);
        set(level, chest, Blocks.CHEST.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.SOUTH));
        RandomizableContainerBlockEntity.setLootTable(level, random, chest, STALL_SUPPLIES);
        set(level, new BlockPos(stall.center.x, base + 3, stall.center.z), Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        QuartermasterEntity qm = FrontierSurvival.QUARTERMASTER.get().create(level.getLevel());
        if (qm != null) {
            qm.moveTo(stall.center.x + 0.5, base + 1, stall.center.z + 1.5, 180, 0);
            qm.setPersistenceRequired();
            qm.finalizeSpawn(level, level.getCurrentDifficultyAt(qm.blockPosition()), MobSpawnType.STRUCTURE, null, null);
            level.addFreshEntity(qm);
        }
        return true;
    }
    private static boolean spawnGuard(WorldGenLevel level, Cell cell, boolean archer, int radius) { return spawnGuard(level, cell, archer, radius, ground(level, cell.x, cell.z) + 1); }
    private static boolean spawnGuard(WorldGenLevel level, Cell cell, boolean archer, int radius, int y) {
        GuardEntity guard = FrontierSurvival.GUARD.get().create(level.getLevel());
        if (guard == null) return false;
        DifficultyInstance difficulty = level.getCurrentDifficultyAt(new BlockPos(cell.x, y, cell.z));
        guard.moveTo(cell.x + 0.5, y, cell.z + 0.5, 0, 0);
        guard.finalizeSpawn(level, difficulty, MobSpawnType.STRUCTURE, null, null);
        guard.setArcher(archer);
        guard.holdPost(new BlockPos(cell.x, y, cell.z), radius);
        guard.setPersistenceRequired();
        return level.addFreshEntity(guard);
    }
    private static void set(WorldGenLevel level, BlockPos pos, BlockState state) { if (level.ensureCanWrite(pos)) level.setBlock(pos, state, 2, 0); }
}
