package dev.frontiersurvival.test;

import dev.frontiersurvival.worldgen.TerrainGroundPiece;
import dev.frontiersurvival.worldgen.TerrainPlanner;
import dev.frontiersurvival.worldgen.TerrainSettlementStructure;
import dev.frontiersurvival.worldgen.TerrainTemplatePiece;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("frontiersurvival")
@PrefixGameTestTemplate(false)
public final class TerrainGameTests {
    private static final ResourceLocation SYNTHETIC = ResourceLocation.fromNamespaceAndPath("frontiersurvival", "synthetic");

    private TerrainGameTests() {}

    @GameTest(template = "test/arena", batch = "terrain")
    public static void blendingRampsOpenGroundTowardEachFloor(GameTestHelper helper) {
        List<TerrainPlanner.Plot> plots = List.of(plot(10, 10, 5, 5));
        List<TerrainPlanner.Cell> path = List.of(new TerrainPlanner.Cell(12, 15), new TerrainPlanner.Cell(12, 16));
        // A steady east-west slope with five blocks of relief across the footprint itself.
        TerrainPlanner.Plan plan = TerrainPlanner.plan(39, plots, path, Optional.empty(),
                (x, z) -> new TerrainPlanner.Sample(60 + x, false), -64, 320).orElseThrow();
        int floor = plan.buildings().get(0).floorY();
        helper.assertTrue(floor == 72, "Median floor sits mid-slope");
        for (int z = 0; z < 39; z++) {
            for (int x = 0; x < 39; x++) {
                int distance = Math.max(Math.max(10 - x, x - 14), Math.max(10 - z, z - 14));
                int target = plan.blend(x, z);
                if (distance <= 0 || plan.path(x, z) != TerrainPlanner.ABSENT) {
                    helper.assertTrue(target == TerrainPlanner.ABSENT, "Footprints and paths are never blended");
                } else if (distance > TerrainPlanner.BLEND_RADIUS) {
                    helper.assertTrue(target == TerrainPlanner.ABSENT, "Blending stays local to the building");
                } else {
                    int ground = target == TerrainPlanner.ABSENT ? plan.ground(x, z) : target;
                    helper.assertTrue(Math.abs(ground - floor) <= distance,
                            "Ground within " + distance + " blocks rises or falls toward the floor at one block per block");
                }
            }
        }
        helper.assertTrue(plan.blend(9, 12) == 71 && plan.blend(7, 12) == 69 && plan.blend(15, 12) == 73,
                "Low side is filled and high side is cut into a one-block-per-block ramp");
        helper.succeed();
    }

    @GameTest(template = "test/arena", batch = "terrain")
    public static void steepDropsBesideBuildingsStayNaturalAndReload(GameTestHelper helper) {
        // A plateau edge west of the footprint and a dry cave mouth to its south.
        TerrainPlanner.Plan plan = TerrainPlanner.plan(19, List.of(plot(5, 5, 5, 5)), List.of(), Optional.empty(),
                (x, z) -> new TerrainPlanner.Sample(x < 5 ? 66 : x == 7 && z == 12 ? 60 : 80, false), -64, 320).orElseThrow();
        for (int z = 0; z < 19; z++) {
            for (int x = 0; x < 19; x++) {
                int target = plan.blend(x, z);
                helper.assertTrue(target == TerrainPlanner.ABSENT
                                || Math.abs(target - plan.ground(x, z)) <= TerrainPlanner.MAX_BLEND_CHANGE,
                        "Blending never plans an extreme fill or cut");
            }
        }
        helper.assertTrue(plan.blend(4, 7) == TerrainPlanner.ABSENT && plan.blend(7, 12) == TerrainPlanner.ABSENT,
                "Cliffs and cave mouths beside a building are left natural");
        StructurePieceSerializationContext context = StructurePieceSerializationContext.fromLevel(helper.getLevel());
        ListTag tags = new ListTag();
        tags.add(new TerrainGroundPiece(new BlockPos(100, 0, 100), plan).createTag(context));
        helper.assertTrue(PiecesContainer.load(tags, context).pieces().size() == 1,
                "The saved ground piece always reloads after a restart");
        helper.succeed();
    }

    @GameTest(template = "test/arena", batch = "terrain")
    public static void backWallsDoNotCreateFalseDoorstepAnchors(GameTestHelper helper) {
        List<TerrainPlanner.Plot> plots = List.of(
                new TerrainPlanner.Plot(SYNTHETIC, 2, 2, 0, 0, 3, 3, List.of(new TerrainPlanner.Cell(1, 2))),
                new TerrainPlanner.Plot(SYNTHETIC, 6, 2, 0, 0, 3, 3, List.of(new TerrainPlanner.Cell(1, 2))));
        helper.assertTrue(TerrainPlanner.plan(13, plots, List.of(new TerrainPlanner.Cell(5, 3)), Optional.empty(),
                (x, z) -> new TerrainPlanner.Sample(x >= 6 ? 66 : 64, false), -64, 320).isPresent(),
                "An alley beside two solid walls is not mistaken for two conflicting entrances");
        helper.succeed();
    }

    @GameTest(template = "test/arena", batch = "terrain")
    public static void rollingSlopeUsesIndependentMediansAndLevelEntrances(GameTestHelper helper) {
        List<TerrainPlanner.Plot> plots = List.of(plot(2, 2, 5, 5), plot(24, 2, 5, 5));
        List<TerrainPlanner.Cell> paths = new ArrayList<>();
        for (int x = 2; x <= 28; x++) paths.add(new TerrainPlanner.Cell(x, 7));
        // A separate approach must be graded independently rather than lost by a single-source traversal.
        for (int x = 2; x <= 9; x++) paths.add(new TerrainPlanner.Cell(x, 22));
        AtomicInteger calls = new AtomicInteger();
        TerrainPlanner.Plan plan = TerrainPlanner.plan(39, plots, paths, Optional.empty(), (x, z) -> {
            calls.incrementAndGet();
            return new TerrainPlanner.Sample(64 + x / 8, false);
        }, -64, 320).orElseThrow();
        helper.assertTrue(calls.get() == 39 * 39, "Samples each natural terrain column exactly once");
        helper.assertTrue(plan.buildings().get(0).floorY() == 64 && plan.buildings().get(1).floorY() == 67,
                "Each rigid building uses its own solid-ground median, not a settlement-wide slab");
        assertGrading(helper, plan);
        for (int x = 2; x <= 6; x++) helper.assertTrue(plan.path(x, 7) == 64, "First entrance is level");
        for (int x = 24; x <= 28; x++) helper.assertTrue(plan.path(x, 7) == 67, "Higher entrance is level");
        helper.assertTrue(plan.path(9, 22) != TerrainPlanner.ABSENT, "Disconnected approach is retained");
        int[] leaked = plan.groundHeights();
        leaked[0] = 999;
        helper.assertTrue(plan.ground(0, 0) == 64, "Baked profiles are immutable");
        helper.succeed();
    }

    @GameTest(template = "test/arena", batch = "terrain")
    public static void unsafeCliffsWaterVoidAndConflictingEntrancesAreRejected(GameTestHelper helper) {
        List<TerrainPlanner.Plot> plots = List.of(plot(2, 2, 5, 5));
        helper.assertTrue(TerrainPlanner.plan(39, plots, List.of(), Optional.empty(),
                (x, z) -> new TerrainPlanner.Sample(x >= 4 ? 70 : 64, false), -64, 320).isEmpty(),
                "Six-block building cliff is rejected");
        helper.assertTrue(TerrainPlanner.plan(39, List.of(plot(2, 2, 5, 5), plot(30, 2, 5, 5)), List.of(), Optional.empty(),
                (x, z) -> new TerrainPlanner.Sample(x >= 20 ? 81 : 64, false), -64, 320).isEmpty(),
                "Building elevations more than sixteen blocks apart are rejected");
        TerrainPlanner.Plan pond = TerrainPlanner.plan(39, plots, List.of(), Optional.empty(),
                (x, z) -> x == 15 && z == 15 ? new TerrainPlanner.Sample(55, true)
                        : x == 30 && z == 30 ? new TerrainPlanner.Sample(-64, false, false)
                        : new TerrainPlanner.Sample(64, false), -64, 320).orElseThrow();
        helper.assertTrue(pond.ground(15, 15) == 64 && pond.ground(30, 30) == 64,
                "Untouched water or holes are allowed but never become foundation heights");
        helper.assertTrue(TerrainPlanner.plan(39, plots, List.of(new TerrainPlanner.Cell(15, 15)), Optional.empty(),
                (x, z) -> new TerrainPlanner.Sample(64, x == 15 && z == 15), -64, 320).isEmpty(),
                "A path through water is rejected, not buried");
        helper.assertTrue(TerrainPlanner.plan(39, plots, List.of(), Optional.of(new TerrainPlanner.Walls(1, 37, 18, 20, false)),
                (x, z) -> new TerrainPlanner.Sample(64, x == 37 && z == 10), -64, 320).isEmpty(),
                "A palisade through water is rejected");
        helper.assertTrue(TerrainPlanner.plan(39, plots, List.of(), Optional.empty(),
                (x, z) -> new TerrainPlanner.Sample(64, x == 3 && z == 3), -64, 320).isEmpty(),
                "Water-covered footprint is rejected");
        helper.assertTrue(TerrainPlanner.plan(39, plots, List.of(), Optional.empty(),
                (x, z) -> new TerrainPlanner.Sample(-64, false, false), -64, 320).isEmpty(), "Void has no flat fallback");
        helper.assertTrue(TerrainPlanner.plan(39, plots, List.of(), Optional.empty(),
                (x, z) -> new TerrainPlanner.Sample(319, false), -64, 320).isEmpty(), "Build-height violation rejected");
        List<TerrainPlanner.Plot> conflicting = List.of(plot(2, 2, 3, 3), plot(6, 2, 3, 3));
        helper.assertTrue(TerrainPlanner.plan(13, conflicting, List.of(new TerrainPlanner.Cell(5, 3)), Optional.empty(),
                (x, z) -> new TerrainPlanner.Sample(x >= 6 ? 66 : 64, false), -64, 320).isEmpty(),
                "A shared doorstep cannot satisfy two conflicting building floors");
        List<TerrainPlanner.Cell> shortPath = List.of(new TerrainPlanner.Cell(4, 5), new TerrainPlanner.Cell(5, 5),
                new TerrainPlanner.Cell(6, 5));
        helper.assertTrue(TerrainPlanner.plan(13, conflicting, shortPath, Optional.empty(),
                (x, z) -> new TerrainPlanner.Sample(x >= 6 ? 68 : 64, false), -64, 320).isEmpty(),
                "Anchor distance envelopes reject an impossible four-block climb over two edges");
        helper.succeed();
    }

    @GameTest(template = "test/arena", batch = "terrain")
    public static void irregularGraphsRespectEveryAnchorAndGate(GameTestHelper helper) {
        List<TerrainPlanner.Plot> plots = List.of(plot(3, 3, 3, 3), plot(29, 29, 3, 3));
        List<TerrainPlanner.Cell> paths = new ArrayList<>();
        for (int x = 4; x <= 30; x++) paths.add(new TerrainPlanner.Cell(x, 6));
        for (int z = 6; z <= 32; z++) paths.add(new TerrainPlanner.Cell(30, z));
        for (int z = 1; z <= 37; z++) {
            for (int x = 18; x <= 20; x++) paths.add(new TerrainPlanner.Cell(x, z));
        }
        TerrainPlanner.Walls walls = new TerrainPlanner.Walls(1, 37, 18, 20, true);
        TerrainPlanner.Plan plan = TerrainPlanner.plan(39, plots, paths, Optional.of(walls),
                (x, z) -> new TerrainPlanner.Sample(64 + (x + z) / 12, false), -64, 320).orElseThrow();
        assertGrading(helper, plan);
        for (int x = 18; x <= 20; x++) {
            helper.assertTrue(plan.path(x, 1) == plan.wall(x, 1) && plan.path(x, 37) == plan.wall(x, 37),
                    "Both gate surfaces match their graded approaches");
        }
        helper.succeed();
    }

    @GameTest(template = "test/arena", batch = "terrain")
    public static void pathEnvelopesMatchExhaustiveFeasibility(GameTestHelper helper) {
        Random random = new Random(20261005);
        List<TerrainPlanner.Plot> plots = List.of(plot(2, 2, 1, 1), plot(6, 2, 1, 1));
        List<TerrainPlanner.Cell> cells = new ArrayList<>();
        for (int x = 2; x <= 6; x++) cells.add(new TerrainPlanner.Cell(x, 3));
        for (int test = 0; test < 1000; test++) {
            int[] terrain = new int[5];
            for (int i = 0; i < terrain.length; i++) terrain[i] = 60 + random.nextInt(11);
            int left = 60 + random.nextInt(11);
            int right = 60 + random.nextInt(11);
            boolean feasible = exhaustivePath(terrain, left, right, 0, 0);
            Optional<TerrainPlanner.Plan> result = TerrainPlanner.plan(13, plots, cells, Optional.empty(), (x, z) -> {
                int height = z == 2 && x == 2 ? left : z == 2 && x == 6 ? right
                        : z == 3 && x >= 2 && x <= 6 ? terrain[x - 2] : 64;
                return new TerrainPlanner.Sample(height, false);
            }, -64, 320);
            helper.assertTrue(result.isPresent() == feasible, "Envelope solution agrees with exhaustive feasible-height search");
            result.ifPresent(plan -> assertGrading(helper, plan));
        }
        helper.succeed();
    }

    @GameTest(template = "test/arena", batch = "terrain_scene", timeoutTicks = 200)
    public static void realTemplatesFollowSlopeAndSurviveNbtAndReverseChunkOrder(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        TerrainSettlementStructure structure = hamlet(helper);
        BlockPos relativeOrigin = new BlockPos(3,
                level.getMaxBuildHeight() - 40 - helper.absolutePos(BlockPos.ZERO).getY(), 3);
        BlockPos origin = helper.absolutePos(relativeOrigin);
        TerrainPlanner.Plan plan = TerrainPlanner.plan(structure.width(), structure.plots(), structure.paths(),
                structure.walls(), (x, z) -> new TerrainPlanner.Sample(origin.getY() + (x + z) / 14, false),
                level.getMinBuildHeight(), level.getMaxBuildHeight()).orElseThrow();
        helper.assertTrue(plan.buildings().stream().mapToInt(TerrainPlanner.Building::floorY).distinct().count() > 1,
                "A rolling scene genuinely gives different building elevations");
        helper.assertTrue(structure.geometryFits(plan, level.getStructureManager()),
                "Independent building elevations do not clip a neighboring room or roof");
        List<StructurePiece> pieces = pieces(level, origin, plan);
        int top = pieces.stream().mapToInt(piece -> piece.getBoundingBox().maxY()).max().orElseThrow() - origin.getY() + 1;
        buildScene(helper, relativeOrigin, plan, top);
        BlockPos untouched = untouchedCell(level, origin, structure, plan);
        BlockPos landmark = untouched.above(7);
        level.setBlock(landmark, Blocks.GOLD_BLOCK.defaultBlockState(), 2);
        List<ChunkPos> chunks = chunks(origin, plan.width());
        place(level, pieces, chunks, origin);
        assertScene(helper, origin, plan, pieces);
        helper.assertTrue(level.getBlockState(landmark).is(Blocks.GOLD_BLOCK), "Unworked landscape is not bulldozed");
        helper.assertTrue(level.getBlockState(untouched).is(Blocks.GRASS_BLOCK), "No giant stone courtyard foundation");
        List<BlockState> expected = snapshot(level, origin, plan.width(), top);

        StructurePieceSerializationContext context = StructurePieceSerializationContext.fromLevel(level);
        ListTag tags = new ListTag();
        pieces.forEach(piece -> tags.add(piece.createTag(context)));
        List<StructurePiece> restored = PiecesContainer.load(tags, context).pieces();
        helper.assertTrue(restored.size() == pieces.size(), "Native registered piece IDs restore every piece");
        for (int i = 0; i < pieces.size(); i++) {
            helper.assertTrue(pieces.get(i).createTag(context).equals(restored.get(i).createTag(context)),
                    "NBT preserves exact positions, footprint, support bounds and baked terrain profiles");
        }
        level.getEntitiesOfClass(Mob.class, new AABB(origin.offset(0, -4, 0), origin.offset(plan.width(), top + 1, plan.width())))
                .forEach(Mob::discard);
        buildScene(helper, relativeOrigin, plan, top);
        level.setBlock(landmark, Blocks.GOLD_BLOCK.defaultBlockState(), 2);
        Collections.reverse(chunks);
        place(level, restored, chunks, origin);
        helper.assertTrue(expected.equals(snapshot(level, origin, plan.width(), top)),
                "Reverse chunk generation after NBT reload gives exactly the same blocks, without terrain resampling");
        assertScene(helper, origin, plan, restored);
        helper.succeed();
    }

    @GameTest(template = "test/arena", batch = "terrain_footings", timeoutTicks = 100)
    public static void foundationsAreChunkClippedAndContinueAfterReload(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        TerrainPlanner.Plot original = hamlet(helper).plots().stream()
                .filter(plot -> plot.template().getPath().contains("cottage"))
                .findFirst().orElseThrow();
        BlockPos relativeOrigin = new BlockPos(3,
                level.getMaxBuildHeight() - 40 - helper.absolutePos(BlockPos.ZERO).getY(), 3);
        BlockPos origin = helper.absolutePos(relativeOrigin);
        int boundary = Math.floorMod(-origin.getX(), 16);
        if (boundary < original.groundX() + original.groundWidth() / 2 + 1) boundary += 16;
        int plotX = boundary - original.groundX() - original.groundWidth() / 2;
        TerrainPlanner.Plot plot = new TerrainPlanner.Plot(original.template(), plotX, 2, original.groundX(),
                original.groundZ(), original.groundWidth(), original.groundDepth());
        int edge = boundary;
        TerrainPlanner.Plan plan = TerrainPlanner.plan(39, List.of(plot), List.of(), Optional.empty(),
                (x, z) -> new TerrainPlanner.Sample(origin.getY() + (x < edge ? 0 : 2), false),
                level.getMinBuildHeight(), level.getMaxBuildHeight()).orElseThrow();
        List<StructurePiece> pieces = pieces(level, origin, plan);
        TerrainPlanner.Building building = plan.buildings().get(0);
        helper.assertTrue(building.floorY() == origin.getY() + 2, "Median keeps the whole room level above lower ground");
        int top = pieces.stream().mapToInt(piece -> piece.getBoundingBox().maxY()).max().orElseThrow() - origin.getY() + 1;
        buildScene(helper, relativeOrigin, plan, top);
        BlockPos low = new BlockPos(origin.getX() + boundary - 1, building.floorY() - 1, origin.getZ() + plot.minZ() + 1);
        BlockPos high = low.east().above(2);
        helper.assertTrue(Math.floorMod(high.getX(), 16) == 0, "Fixture crosses an actual chunk edge");
        level.setBlock(high, Blocks.GOLD_BLOCK.defaultBlockState(), 2);
        ChunkPos first = new ChunkPos(low);
        place(level, pieces, List.of(first), origin);
        helper.assertTrue(level.getBlockState(low).is(Blocks.COBBLESTONE)
                        && level.getBlockState(low.below()).is(Blocks.COBBLESTONE),
                "Low-side foundation reaches sampled solid ground, not just the visible floor");
        helper.assertTrue(level.getBlockState(high).is(Blocks.GOLD_BLOCK), "First chunk cannot write the neighboring room");
        StructurePieceSerializationContext context = StructurePieceSerializationContext.fromLevel(level);
        ListTag tags = new ListTag();
        pieces.forEach(piece -> tags.add(piece.createTag(context)));
        List<StructurePiece> restored = PiecesContainer.load(tags, context).pieces();
        List<ChunkPos> remaining = chunks(origin, plan.width());
        remaining.remove(first);
        Collections.reverse(remaining);
        place(level, restored, remaining, origin);
        helper.assertTrue(!level.getBlockState(high).is(Blocks.GOLD_BLOCK), "Reloaded piece completes the neighboring room");
        helper.assertTrue(level.getBlockState(low).is(Blocks.COBBLESTONE), "Previously generated support remains intact");
        assertScene(helper, origin, plan, restored);
        helper.succeed();
    }

    private static TerrainPlanner.Plot plot(int x, int z, int width, int depth) {
        List<TerrainPlanner.Cell> entrances = new ArrayList<>();
        for (int zz = 0; zz < depth; zz++) {
            for (int xx = 0; xx < width; xx++) {
                if (xx == 0 || xx == width - 1 || zz == 0 || zz == depth - 1) entrances.add(new TerrainPlanner.Cell(xx, zz));
            }
        }
        return new TerrainPlanner.Plot(SYNTHETIC, x, z, 0, 0, width, depth, entrances);
    }

    private static boolean exhaustivePath(int[] terrain, int left, int right, int index, int previous) {
        if (index == terrain.length) return true;
        int lower = index == 0 ? left : index == terrain.length - 1 ? right : terrain[index] - 3;
        int upper = index == 0 ? left : index == terrain.length - 1 ? right : terrain[index] + 3;
        for (int height = lower; height <= upper; height++) {
            if (Math.abs(height - terrain[index]) > 3 || index > 0 && Math.abs(height - previous) > 1) continue;
            if (exhaustivePath(terrain, left, right, index + 1, height)) return true;
        }
        return false;
    }

    private static TerrainSettlementStructure hamlet(GameTestHelper helper) {
        var found = helper.getLevel().registryAccess().registryOrThrow(Registries.STRUCTURE)
                .get(ResourceLocation.fromNamespaceAndPath("frontiersurvival", "terrain_fortified_hamlet"));
        helper.assertTrue(found instanceof TerrainSettlementStructure, "Hamlet uses the native terrain settlement type");
        if (found instanceof TerrainSettlementStructure terrain) return terrain;
        throw new IllegalStateException("Terrain hamlet not registered");
    }

    private static List<StructurePiece> pieces(ServerLevel level, BlockPos origin, TerrainPlanner.Plan plan) {
        List<StructurePiece> pieces = new ArrayList<>();
        pieces.add(new TerrainGroundPiece(origin, plan));
        for (TerrainPlanner.Building building : plan.buildings()) {
            helperTemplateExists(level, building.plot());
            pieces.add(new TerrainTemplatePiece(level.getStructureManager(), origin, plan, building));
        }
        return pieces;
    }

    private static void helperTemplateExists(ServerLevel level, TerrainPlanner.Plot plot) {
        if (level.getStructureManager().get(plot.template()).isEmpty()) {
            throw new IllegalStateException("Missing original component " + plot.template());
        }
    }

    private static void buildScene(GameTestHelper helper, BlockPos relativeOrigin, TerrainPlanner.Plan plan, int top) {
        int baseY = helper.absolutePos(relativeOrigin).getY();
        for (int z = 0; z < plan.width(); z++) {
            for (int x = 0; x < plan.width(); x++) {
                int ground = plan.ground(x, z) - baseY;
                // GameTest helper y=0 is ABOVE its fixture floor: build our own ground explicitly.
                for (int y = -4; y <= top; y++) {
                    Block block = y < ground ? Blocks.DIRT : y == ground ? Blocks.GRASS_BLOCK : Blocks.AIR;
                    helper.setBlock(relativeOrigin.offset(x, y, z), block);
                }
            }
        }
    }

    private static List<ChunkPos> chunks(BlockPos origin, int width) {
        List<ChunkPos> result = new ArrayList<>();
        for (int z = origin.getZ() >> 4; z <= (origin.getZ() + width - 1) >> 4; z++) {
            for (int x = origin.getX() >> 4; x <= (origin.getX() + width - 1) >> 4; x++) {
                result.add(new ChunkPos(x, z));
            }
        }
        return result;
    }

    private static void place(ServerLevel level, List<StructurePiece> pieces, List<ChunkPos> chunks, BlockPos origin) {
        for (ChunkPos chunk : chunks) {
            BoundingBox box = new BoundingBox(chunk.getMinBlockX(), level.getMinBuildHeight(), chunk.getMinBlockZ(),
                    chunk.getMaxBlockX(), level.getMaxBuildHeight() - 1, chunk.getMaxBlockZ());
            for (StructurePiece piece : pieces) {
                if (piece.getBoundingBox().intersects(box)) {
                    piece.postProcess(level, level.structureManager(), level.getChunkSource().getGenerator(),
                            RandomSource.create(chunk.toLong() ^ 12345), box, chunk, origin);
                }
            }
        }
    }

    private static void assertGrading(GameTestHelper helper, TerrainPlanner.Plan plan) {
        for (int z = 0; z < plan.width(); z++) {
            for (int x = 0; x < plan.width(); x++) {
                int height = plan.path(x, z);
                if (height == TerrainPlanner.ABSENT) continue;
                if (x + 1 < plan.width() && plan.path(x + 1, z) != TerrainPlanner.ABSENT) {
                    helper.assertTrue(Math.abs(height - plan.path(x + 1, z)) <= 1, "East/west path step is at most one");
                }
                if (z + 1 < plan.width() && plan.path(x, z + 1) != TerrainPlanner.ABSENT) {
                    helper.assertTrue(Math.abs(height - plan.path(x, z + 1)) <= 1, "North/south path step is at most one");
                }
                helper.assertTrue(Math.abs(height - plan.ground(x, z)) <= TerrainPlanner.MAX_PATH_ADJUSTMENT,
                        "Path earthworks remain bounded");
                int owner = plan.occupant(x, z);
                if (owner != 0) helper.assertTrue(height == plan.buildings().get(owner - 1).floorY(), "Footprint path anchor");
                for (int[] delta : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    int nx = x + delta[0];
                    int nz = z + delta[1];
                    if (nx < 0 || nz < 0 || nx >= plan.width() || nz >= plan.width()) continue;
                    owner = plan.occupant(nx, nz);
                    if (owner == 0) continue;
                    TerrainPlanner.Plot plot = plan.buildings().get(owner - 1).plot();
                    if (plot.entrances().contains(new TerrainPlanner.Cell(nx - plot.x(), nz - plot.z()))) {
                        helper.assertTrue(height == plan.buildings().get(owner - 1).floorY(),
                            "Every path adjacent to a building entrance is anchored to its floor");
                    }
                }
            }
        }
    }

    private static void assertScene(GameTestHelper helper, BlockPos origin, TerrainPlanner.Plan plan,
                                    List<StructurePiece> pieces) {
        ServerLevel level = helper.getLevel();
        assertGrading(helper, plan);
        int bedPairs = 0;
        int supported = 0;
        for (int b = 0; b < plan.buildings().size(); b++) {
            TerrainPlanner.Building building = plan.buildings().get(b);
            TerrainPlanner.Plot plot = building.plot();
            StructurePiece placed = pieces.get(b + 1);
            helper.assertTrue(placed instanceof TerrainTemplatePiece, "Rigid native template piece retained");
            if (!(placed instanceof TerrainTemplatePiece piece)) throw new IllegalStateException("Not a terrain template");
            helper.assertTrue(piece.floorY() == building.floorY(), "Piece never projects or resamples its building floor");
            helper.assertTrue(Arrays.equals(piece.groundHeights(), foundationProfile(plan, plot)), "Cached foundation terrain");
            for (int z = plot.minZ(); z < plot.minZ() + plot.groundDepth(); z++) {
                for (int x = plot.minX(); x < plot.minX() + plot.groundWidth(); x++) {
                    BlockPos floor = new BlockPos(origin.getX() + x, building.floorY(), origin.getZ() + z);
                    helper.assertTrue(!level.getBlockState(floor).isAir(), "Every room footprint has its level floor");
                    for (int y = plan.ground(x, z); y < building.floorY(); y++) {
                        helper.assertTrue(level.getBlockState(new BlockPos(floor.getX(), y, floor.getZ())).is(Blocks.COBBLESTONE),
                                "Individual foundations extend through the last gap into solid ground");
                        supported++;
                    }
                }
            }
            for (Block block : BuiltInRegistries.BLOCK) {
                if (block != Blocks.AIR && block != Blocks.STRUCTURE_VOID) {
                    for (var detail : piece.template().filterBlocks(BlockPos.ZERO, new StructurePlaceSettings(), block)) {
                        if (detail.pos().getY() == 0) continue;
                        helper.assertTrue(level.getBlockState(piece.templatePosition().offset(detail.pos())).getBlock() == block,
                                "Original roof, walls and details remain intact after terrain placement: "
                                        + detail.pos() + " in " + plot.template());
                    }
                }
                if (!(block instanceof BedBlock)) continue;
                var beds = piece.template().filterBlocks(BlockPos.ZERO, new StructurePlaceSettings(), block);
                for (var bed : beds) {
                    BlockPos pos = piece.templatePosition().offset(bed.pos());
                    BlockState actual = level.getBlockState(pos);
                    helper.assertTrue(actual.getBlock() == bed.state().getBlock() && actual.getValue(BedBlock.PART) == bed.state().getValue(BedBlock.PART),
                            "Both bed halves preserve the original rigid template geometry");
                    BlockPos partner = pos.relative(BedBlock.getConnectedDirection(actual));
                    helper.assertTrue(level.getBlockState(partner).getBlock() == actual.getBlock()
                                    && level.getBlockState(partner).getValue(BedBlock.PART) != actual.getValue(BedBlock.PART)
                                    && partner.getY() == pos.getY(), "A usable bed pair remains level");
                    if (actual.getValue(BedBlock.PART) == BedPart.HEAD) bedPairs++;
                }
            }
            for (var ladder : piece.template().filterBlocks(BlockPos.ZERO, new StructurePlaceSettings(), Blocks.LADDER)) {
                helper.assertTrue(level.getBlockState(piece.templatePosition().offset(ladder.pos())).equals(ladder.state()),
                        "Ladders stay at their original rigid tower offsets");
            }
        }
        helper.assertTrue(supported > 0, "The synthetic slope actually exercises foundation columns");
        helper.assertTrue(bedPairs > 0, "Original cottages contribute real complete bed pairs");
        for (int z = 0; z < plan.width(); z++) {
            for (int x = 0; x < plan.width(); x++) {
                int y = plan.path(x, z);
                if (y == TerrainPlanner.ABSENT || plan.occupant(x, z) != 0) continue;
                if (plan.walls().isPresent() && plan.walls().get().perimeter(x, z) && !plan.walls().get().gate(x, z)) continue;
                BlockPos pos = new BlockPos(origin.getX() + x, y, origin.getZ() + z);
                helper.assertTrue(level.getBlockState(pos).is(Blocks.GRAVEL), "Real graded path surface exists");
                int headroom = plan.walls().isPresent() && plan.walls().get().gate(x, z) ? 3 : 2;
                for (int h = 1; h <= headroom; h++) helper.assertTrue(level.getBlockState(pos.above(h)).isAir(),
                        "Blocked path at local " + x + "," + z + ", ground=" + y + ", height=" + h
                                + ": " + level.getBlockState(pos.above(h)));
                helper.assertTrue(level.noCollision(new AABB(pos.getX() + 0.2, y + 1.01, pos.getZ() + 0.2,
                        pos.getX() + 0.8, y + 2.96, pos.getZ() + 0.8)), "A full-height villager can stand on the graded path");
            }
        }
    }

    private static int[] foundationProfile(TerrainPlanner.Plan plan, TerrainPlanner.Plot plot) {
        int[] heights = new int[plot.groundWidth() * plot.groundDepth()];
        for (int z = 0; z < plot.groundDepth(); z++) {
            for (int x = 0; x < plot.groundWidth(); x++) {
                heights[z * plot.groundWidth() + x] = plan.ground(plot.minX() + x, plot.minZ() + z);
            }
        }
        return heights;
    }

    private static BlockPos untouchedCell(ServerLevel level, BlockPos origin, TerrainSettlementStructure structure,
                                          TerrainPlanner.Plan plan) {
        for (int z = 0; z < plan.width(); z++) {
            for (int x = 0; x < plan.width(); x++) {
                if (plan.occupant(x, z) != 0 || plan.path(x, z) != TerrainPlanner.ABSENT
                        || plan.wall(x, z) != TerrainPlanner.ABSENT || plan.blend(x, z) != TerrainPlanner.ABSENT) continue;
                boolean templateCell = false;
                for (TerrainPlanner.Plot plot : structure.plots()) {
                    var size = level.getStructureManager().get(plot.template()).orElseThrow().getSize();
                    templateCell |= x >= plot.x() && x < plot.x() + size.getX() && z >= plot.z() && z < plot.z() + size.getZ();
                }
                if (!templateCell) return new BlockPos(origin.getX() + x, plan.ground(x, z), origin.getZ() + z);
            }
        }
        throw new IllegalStateException("Expected untouched original landscape outside paths, walls and buildings");
    }

    private static List<BlockState> snapshot(ServerLevel level, BlockPos origin, int width, int top) {
        List<BlockState> result = new ArrayList<>();
        for (int z = 0; z < width; z++) {
            for (int x = 0; x < width; x++) {
                for (int y = -4; y <= top; y++) result.add(level.getBlockState(origin.offset(x, y, z)));
            }
        }
        return result;
    }
}
