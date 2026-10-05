package dev.frontiersurvival.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

public final class TerrainSettlementStructure extends Structure {
    public static final Codec<TerrainSettlementStructure> CODEC =
            RecordCodecBuilder.<TerrainSettlementStructure>mapCodec(instance -> instance.group(
                    settingsCodec(instance),
                    Codec.intRange(TerrainPlanner.MIN_WIDTH, TerrainPlanner.MAX_WIDTH).fieldOf("width").forGetter(TerrainSettlementStructure::width),
                    TerrainPlanner.Plot.CODEC.listOf().fieldOf("plots").forGetter(TerrainSettlementStructure::plots),
                    TerrainPlanner.Cell.CODEC.listOf().fieldOf("paths").forGetter(TerrainSettlementStructure::paths),
                    TerrainPlanner.Walls.CODEC.optionalFieldOf("walls").forGetter(TerrainSettlementStructure::walls)
            ).apply(instance, TerrainSettlementStructure::new)).flatXmap(
                    TerrainSettlementStructure::validate, TerrainSettlementStructure::validate).codec();

    /** Candidate sites around the start chunk, nearest first; the lowest-earthworks valid site wins. */
    private static final int[][] SITE_OFFSETS = {
            {0, 0}, {12, 0}, {-12, 0}, {0, 12}, {0, -12}, {12, 12}, {-12, 12}, {12, -12}, {-12, -12}};
    /** Per template, its solid/air shape for each rotation ordinal, normalised to the turned box's min corner. */
    private static final Map<StructureTemplate, Shape[]> SHAPES = Collections.synchronizedMap(new WeakHashMap<>());

    private final int width;
    private final List<TerrainPlanner.Plot> plots;
    private final List<TerrainPlanner.Cell> paths;
    private final Optional<TerrainPlanner.Walls> walls;

    public TerrainSettlementStructure(StructureSettings settings, int width, List<TerrainPlanner.Plot> plots,
                                      List<TerrainPlanner.Cell> paths, Optional<TerrainPlanner.Walls> walls) {
        super(settings);
        this.width = width;
        this.plots = List.copyOf(plots);
        this.paths = List.copyOf(paths);
        this.walls = walls;
    }

    public int width() { return width; }
    public List<TerrainPlanner.Plot> plots() { return plots; }
    public List<TerrainPlanner.Cell> paths() { return paths; }
    public Optional<TerrainPlanner.Walls> walls() { return walls; }

    private static DataResult<TerrainSettlementStructure> validate(TerrainSettlementStructure structure) {
        return TerrainPlanner.validLayout(structure.width, structure.plots, structure.paths, structure.walls)
                ? DataResult.success(structure)
                : DataResult.error(() -> "Invalid or overlapping terrain settlement footprint");
    }

    /** The chosen site: grid origin, plan of the turned layout, its earthworks, facing and regional materials. */
    public record Site(int originX, int originZ, TerrainPlanner.Plan plan, int earthworks, Rotation rotation,
                       Palette palette) {}

    @Override
    protected Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
        Optional<Site> chosen = chooseSite(context, null);
        if (chosen.isEmpty()) return Optional.empty();
        Site best = chosen.get();
        TerrainPlanner.Plan plan = best.plan();
        BlockPos origin = new BlockPos(best.originX(), 0, best.originZ());
        BlockPos center = new BlockPos(best.originX() + width / 2, plan.ground(width / 2, width / 2),
                best.originZ() + width / 2);
        return Optional.of(new GenerationStub(center, builder -> {
            builder.addPiece(new TerrainGroundPiece(origin, plan, best.palette()));
            for (TerrainPlanner.Building building : plan.buildings()) {
                builder.addPiece(new TerrainTemplatePiece(context.structureTemplateManager(), origin, plan, building,
                        best.rotation(), best.palette()));
            }
        }));
    }

    /** Counts why each nearby candidate site and facing was accepted or rejected; used to tune frequency. */
    public Map<String, Integer> diagnose(GenerationContext context) {
        Map<String, Integer> reasons = new TreeMap<>();
        chooseSite(context, reasons);
        return reasons;
    }

    /** The site, facing and palette this structure would choose for the context's chunk, if any. */
    public Optional<Site> site(GenerationContext context) {
        return chooseSite(context, null);
    }

    private Optional<Site> chooseSite(GenerationContext context, Map<String, Integer> reasons) {
        TerrainSampler terrain = new TerrainSampler(context);
        int centerX = context.chunkPos().getMiddleBlockX();
        int centerZ = context.chunkPos().getMiddleBlockZ();
        // Vanilla checks the biome only after placement is found; rejecting first avoids sampling unusable regions.
        if (!biomeAllows(context, terrain, centerX, centerZ)) {
            count(reasons, "biome");
            return Optional.empty();
        }
        // Every quarter turn is tried; on equal earthworks the per-chunk shuffled order picks the facing.
        List<Rotation> rotations = Rotation.getShuffled(context.random());
        Map<ResourceLocation, Vec3i> sizes = templateSizes(context.structureTemplateManager());
        List<TerrainLayout> layouts = new ArrayList<>(rotations.size());
        for (Rotation rotation : rotations) {
            layouts.add(TerrainLayout.rotate(width, plots, paths, walls, rotation, sizes::get));
        }
        int minY = context.heightAccessor().getMinBuildHeight();
        int maxY = context.heightAccessor().getMaxBuildHeight();
        Site best = null;
        for (int[] offset : SITE_OFFSETS) {
            int originX = centerX + offset[0] - width / 2;
            int originZ = centerZ + offset[1] - width / 2;
            if ((offset[0] != 0 || offset[1] != 0) && !biomeAllows(context, terrain, originX + width / 2, originZ + width / 2)) {
                count(reasons, "offset-biome");
                continue;
            }
            TerrainPlanner.Sampler sampler = (x, z) -> terrain.sample(originX + x, originZ + z);
            for (TerrainLayout layout : layouts) {
                String early = preflight(layout.plots(), sampler, minY, maxY);
                if (early != null) {
                    count(reasons, early);
                    continue;
                }
                TerrainPlanner.Evaluation evaluation = TerrainPlanner.evaluate(width, layout.plots(), layout.paths(),
                        layout.walls(), sampler, minY, maxY);
                if (evaluation.plan().isEmpty()) {
                    count(reasons, evaluation.rejection());
                    continue;
                }
                TerrainPlanner.Plan plan = evaluation.plan().get();
                if (!templatesFit(plan, context, layout.rotation())) {
                    count(reasons, "template-fit");
                    continue;
                }
                if (!geometryFits(plan, context.structureTemplateManager(), layout.rotation())) {
                    count(reasons, "geometry");
                    continue;
                }
                count(reasons, "valid");
                int earthworks = plan.earthworks();
                if (best == null || earthworks < best.earthworks()) {
                    best = new Site(originX, originZ, plan, earthworks, layout.rotation(), Palette.OAK);
                }
            }
        }
        if (best == null) return Optional.empty();
        int x = best.originX() + width / 2;
        int z = best.originZ() + width / 2;
        Palette palette = Palette.forBiome(context.biomeSource().getNoiseBiome(QuartPos.fromBlock(x),
                QuartPos.fromBlock(best.plan().ground(width / 2, width / 2)), QuartPos.fromBlock(z),
                context.randomState().sampler()));
        return Optional.of(new Site(best.originX(), best.originZ(), best.plan(), best.earthworks(), best.rotation(), palette));
    }

    private Map<ResourceLocation, Vec3i> templateSizes(StructureTemplateManager manager) {
        Map<ResourceLocation, Vec3i> sizes = new HashMap<>();
        for (TerrainPlanner.Plot plot : plots) {
            sizes.computeIfAbsent(plot.template(), location -> template(manager, location).getSize());
        }
        return sizes;
    }

    private static StructureTemplate template(StructureTemplateManager manager, ResourceLocation location) {
        return manager.get(location).orElseThrow(() -> new IllegalStateException("Missing terrain component " + location));
    }

    private static void count(Map<String, Integer> reasons, String reason) {
        if (reasons != null) reasons.merge(reason, 1, Integer::sum);
    }

    private static boolean biomeAllows(GenerationContext context, TerrainSampler terrain, int x, int z) {
        TerrainPlanner.Sample sample = terrain.sample(x, z);
        if (!sample.solid()) return false;
        return context.validBiome().test(context.biomeSource().getNoiseBiome(QuartPos.fromBlock(x),
                QuartPos.fromBlock(sample.groundY()), QuartPos.fromBlock(z), context.randomState().sampler()));
    }

    private boolean templatesFit(TerrainPlanner.Plan plan, GenerationContext context, Rotation rotation) {
        for (TerrainPlanner.Building building : plan.buildings()) {
            TerrainPlanner.Plot plot = building.plot();
            Vec3i size = template(context.structureTemplateManager(), plot.template()).getSize(rotation);
            if (size.getY() < 2 || plot.x() + size.getX() > width || plot.z() + size.getZ() > width
                    || plot.groundX() + plot.groundWidth() > size.getX()
                    || plot.groundZ() + plot.groundDepth() > size.getZ()
                    || building.floorY() + size.getY() > context.heightAccessor().getMaxBuildHeight()) {
                return false;
            }
        }
        return true;
    }

    /** Rejects independent elevations that would make one component's roof or wall clip another's room. */
    public boolean geometryFits(TerrainPlanner.Plan plan, StructureTemplateManager manager) {
        return geometryFits(plan, manager, Rotation.NONE);
    }

    /** As {@link #geometryFits(TerrainPlanner.Plan, StructureTemplateManager)} for a plan of a turned layout. */
    public boolean geometryFits(TerrainPlanner.Plan plan, StructureTemplateManager manager, Rotation rotation) {
        Long2ObjectOpenHashMap<Block> solid = new Long2ObjectOpenHashMap<>();
        LongOpenHashSet clearing = new LongOpenHashSet();
        for (TerrainPlanner.Building building : plan.buildings()) {
            TerrainPlanner.Plot plot = building.plot();
            Shape shape = shape(template(manager, plot.template()), rotation);
            for (int i = 0; i < shape.solid().length; i++) {
                long position = BlockPos.offset(shape.solid()[i], plot.x(), building.floorY(), plot.z());
                if (clearing.contains(position)) return false;
                Block previous = solid.putIfAbsent(position, shape.blocks()[i]);
                if (previous != null && previous != shape.blocks()[i]) return false;
            }
            for (long relative : shape.air()) {
                long position = BlockPos.offset(relative, plot.x(), building.floorY(), plot.z());
                if (solid.containsKey(position)) return false;
                clearing.add(position);
            }
        }
        return true;
    }

    private record Shape(long[] solid, Block[] blocks, long[] air) {}

    private static Shape shape(StructureTemplate template, Rotation rotation) {
        Shape[] shapes = SHAPES.computeIfAbsent(template, key -> new Shape[Rotation.values().length]);
        synchronized (shapes) {
            Shape shape = shapes[rotation.ordinal()];
            if (shape == null) {
                shape = computeShape(template, rotation);
                shapes[rotation.ordinal()] = shape;
            }
            return shape;
        }
    }

    private static Shape computeShape(StructureTemplate template, Rotation rotation) {
        LongArrayList solid = new LongArrayList();
        List<Block> blocks = new ArrayList<>();
        LongArrayList air = new LongArrayList();
        StructurePlaceSettings settings = new StructurePlaceSettings().setRotation(rotation).setRandom(RandomSource.create(0));
        BoundingBox box = template.getBoundingBox(settings, BlockPos.ZERO);
        for (Block block : BuiltInRegistries.BLOCK) {
            if (block == Blocks.STRUCTURE_VOID) continue;
            for (var info : template.filterBlocks(BlockPos.ZERO, settings, block)) {
                long position = BlockPos.asLong(info.pos().getX() - box.minX(), info.pos().getY(), info.pos().getZ() - box.minZ());
                if (block == Blocks.AIR) {
                    air.add(position);
                } else {
                    solid.add(position);
                    blocks.add(block);
                }
            }
        }
        return new Shape(solid.toLongArray(), blocks.toArray(Block[]::new), air.toLongArray());
    }

    private static String preflight(List<TerrainPlanner.Plot> plots, TerrainPlanner.Sampler sampler, int minY, int maxY) {
        for (TerrainPlanner.Plot plot : plots) {
            int x = plot.minX(), z = plot.minZ();
            int maxX = x + plot.groundWidth() - 1, maxZ = z + plot.groundDepth() - 1;
            int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
            for (int[] point : new int[][]{{x, z}, {maxX, z}, {x, maxZ}, {maxX, maxZ}, {(x + maxX) / 2, (z + maxZ) / 2}}) {
                TerrainPlanner.Sample sample = sampler.sample(point[0], point[1]);
                if (!TerrainPlanner.suitable(sample, minY, maxY)) return sample.wet() ? "water" : "ground";
                low = Math.min(low, sample.groundY());
                high = Math.max(high, sample.groundY());
            }
            if (high - low > TerrainPlanner.MAX_FOOTPRINT_RELIEF) {
                return "footprint-relief:" + plot.template().getPath().replaceAll(".*/", "");
            }
        }
        return null;
    }

    @Override
    public StructureType<?> type() {
        return TerrainTypes.TERRAIN_SETTLEMENT.get();
    }
}
