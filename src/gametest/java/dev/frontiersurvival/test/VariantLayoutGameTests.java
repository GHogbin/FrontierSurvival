package dev.frontiersurvival.test;

import dev.frontiersurvival.FrontierSurvival;
import dev.frontiersurvival.entity.BanditEntity;
import dev.frontiersurvival.entity.GuardEntity;
import dev.frontiersurvival.entity.QuartermasterEntity;
import dev.frontiersurvival.settlement.SettlementBoardBlockEntity;
import dev.frontiersurvival.worldgen.TerrainGroundPiece;
import dev.frontiersurvival.worldgen.TerrainPlanner;
import dev.frontiersurvival.worldgen.TerrainSettlementStructure;
import dev.frontiersurvival.worldgen.TerrainTemplatePiece;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FrontierSurvival.ID)
@PrefixGameTestTemplate(false)
public final class VariantLayoutGameTests {
    private VariantLayoutGameTests() {}

    @GameTest(template = "test/arena_large", batch = "variants", timeoutTicks = 300)
    public static void hamletSmallPlansPlacesAndSpawns(GameTestHelper helper) {
        verify(helper, new Expect("terrain_hamlet_small", 2, 1, 0, 0, 1, 2, 3, 2, 1));
    }

    @GameTest(template = "test/arena_large", batch = "variants", timeoutTicks = 400)
    public static void hamletLargePlansPlacesAndSpawns(GameTestHelper helper) {
        verify(helper, new Expect("terrain_hamlet_large", 5, 2, 0, 0, 2, 6, 17, 8, 1));
    }

    @GameTest(template = "test/arena_large", batch = "variants", timeoutTicks = 300)
    public static void banditCampSmallPlansPlacesAndSpawns(GameTestHelper helper) {
        verify(helper, new Expect("terrain_bandit_camp_small", 0, 0, 2, 1, 0, 0, 2, 2, 0));
    }

    @GameTest(template = "test/arena_large", batch = "variants", timeoutTicks = 300)
    public static void banditCampLargePlansPlacesAndSpawns(GameTestHelper helper) {
        verify(helper, new Expect("terrain_bandit_camp_large", 0, 0, 6, 2, 0, 0, 4, 4, 0, 1));
    }

    @GameTest(template = "test/arena_large", batch = "variants", timeoutTicks = 300)
    public static void watchtowerSmallPlansPlacesAndSpawns(GameTestHelper helper) {
        verify(helper, new Expect("terrain_watchtower_small", 2, 1, 0, 0, 0, 0, 1, 1, 1));
    }

    @GameTest(template = "test/arena_large", batch = "variants", timeoutTicks = 300)
    public static void watchtowerLargePlansPlacesAndSpawns(GameTestHelper helper) {
        verify(helper, new Expect("terrain_watchtower_large", 4, 2, 0, 0, 1, 1, 5, 2, 1));
    }

    private static void verify(GameTestHelper helper, Expect expect) {
        ServerLevel level = helper.getLevel();
        TerrainSettlementStructure structure = structure(helper, expect.id);
        for (TerrainPlanner.Plot plot : structure.plots()) {
            helper.assertTrue(level.getStructureManager().get(plot.template()).isPresent(),
                    "Variant component template exists: " + plot.template());
        }
        BlockPos relativeOrigin = new BlockPos(3, 4, 3);
        BlockPos origin = helper.absolutePos(relativeOrigin);
        TerrainPlanner.Plan plan = TerrainPlanner.plan(structure.width(), structure.plots(), structure.paths(),
                structure.walls(), (x, z) -> new TerrainPlanner.Sample(origin.getY() + (x + z) / 20, false),
                level.getMinBuildHeight(), level.getMaxBuildHeight()).orElseThrow();
        helper.assertTrue(structure.geometryFits(plan, level.getStructureManager()), "Variant geometry fits terrain plan");
        List<StructurePiece> pieces = pieces(level, origin, plan);
        int top = pieces.stream().mapToInt(piece -> piece.getBoundingBox().maxY()).max().orElseThrow() - origin.getY() + 1;
        buildScene(helper, relativeOrigin, plan, top);
        place(level, pieces, chunks(origin, plan.width()), origin);
        assertFootprintsSupported(helper, level, origin, plan);
        assertBlocks(helper, level, origin, plan.width(), top, expect);
        AABB bounds = new AABB(origin.offset(0, -4, 0), origin.offset(plan.width(), top + 4, plan.width()));
        helper.runAfterDelay(10, () -> {
            var guards = level.getEntitiesOfClass(GuardEntity.class, bounds);
            var bandits = level.getEntitiesOfClass(BanditEntity.class, bounds);
            helper.assertTrue(guards.size() == expect.guards && guards.stream().filter(GuardEntity::isArcher).count() == expect.guardArchers,
                    "Guard population matches " + expect.id + ": " + guards);
            helper.assertTrue(bandits.size() == expect.bandits && bandits.stream().filter(BanditEntity::isArcher).count() == expect.banditArchers
                    && bandits.stream().filter(BanditEntity::isLeader).count() == expect.leaders,
                    "Bandit population matches " + expect.id + ": " + bandits);
            helper.assertTrue(level.getEntitiesOfClass(QuartermasterEntity.class, bounds).size() == expect.quartermasters,
                    "Quartermaster population matches " + expect.id);
            helper.assertTrue(level.getEntitiesOfClass(Villager.class, bounds).size() == expect.villagers,
                    "Villager population matches " + expect.id);
            helper.succeed();
        });
    }

    private static TerrainSettlementStructure structure(GameTestHelper helper, String id) {
        var found = helper.getLevel().registryAccess().registryOrThrow(Registries.STRUCTURE)
                .get(ResourceLocation.fromNamespaceAndPath(FrontierSurvival.ID, id));
        helper.assertTrue(found instanceof TerrainSettlementStructure, id + " uses terrain settlement structure type");
        if (found instanceof TerrainSettlementStructure terrain) return terrain;
        throw new IllegalStateException(id + " not registered");
    }

    private static List<StructurePiece> pieces(ServerLevel level, BlockPos origin, TerrainPlanner.Plan plan) {
        List<StructurePiece> pieces = new ArrayList<>();
        pieces.add(new TerrainGroundPiece(origin, plan));
        for (TerrainPlanner.Building building : plan.buildings()) {
            pieces.add(new TerrainTemplatePiece(level.getStructureManager(), origin, plan, building));
        }
        return pieces;
    }

    private static void buildScene(GameTestHelper helper, BlockPos relativeOrigin, TerrainPlanner.Plan plan, int top) {
        int baseY = helper.absolutePos(relativeOrigin).getY();
        for (int z = 0; z < plan.width(); z++) {
            for (int x = 0; x < plan.width(); x++) {
                int ground = plan.ground(x, z) - baseY;
                for (int y = -4; y <= top + 2; y++) {
                    helper.setBlock(relativeOrigin.offset(x, y, z),
                            y < ground ? Blocks.DIRT : y == ground ? Blocks.GRASS_BLOCK : Blocks.AIR);
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
                            RandomSource.create(chunk.toLong() ^ 67890), box, chunk, origin);
                }
            }
        }
    }

    private static void assertFootprintsSupported(GameTestHelper helper, ServerLevel level, BlockPos origin, TerrainPlanner.Plan plan) {
        for (TerrainPlanner.Building building : plan.buildings()) {
            TerrainPlanner.Plot plot = building.plot();
            for (int z = plot.minZ(); z < plot.minZ() + plot.groundDepth(); z++) {
                for (int x = plot.minX(); x < plot.minX() + plot.groundWidth(); x++) {
                    BlockPos floor = new BlockPos(origin.getX() + x, building.floorY(), origin.getZ() + z);
                    helper.assertTrue(!level.getBlockState(floor).isAir(), "Every variant footprint cell has a floor");
                    for (int y = plan.ground(x, z); y < building.floorY(); y++) {
                        helper.assertTrue(level.getBlockState(new BlockPos(floor.getX(), y, floor.getZ())).is(Blocks.COBBLESTONE),
                                "Variant footprint is supported by foundation");
                    }
                }
            }
        }
    }

    private static void assertBlocks(GameTestHelper helper, ServerLevel level, BlockPos origin, int width, int top, Expect expect) {
        int beds = 0;
        int chests = 0;
        int boards = 0;
        for (int x = 0; x < width; x++) {
            for (int y = -4; y <= top + 2; y++) {
                for (int z = 0; z < width; z++) {
                    BlockPos pos = origin.offset(x, y, z);
                    if (level.getBlockState(pos).getBlock() instanceof BedBlock
                            && level.getBlockState(pos).getValue(BedBlock.PART) == BedPart.HEAD) beds++;
                    if (level.getBlockEntity(pos) instanceof ChestBlockEntity) chests++;
                    if (level.getBlockEntity(pos) instanceof SettlementBoardBlockEntity) boards++;
                }
            }
        }
        helper.assertTrue(beds == expect.beds, "Bed count matches " + expect.id + ": " + beds);
        helper.assertTrue(chests == expect.chests, "Chest count matches " + expect.id + ": " + chests);
        helper.assertTrue(boards == expect.boards, "Charter count matches " + expect.id + ": " + boards);
    }

    private record Expect(String id, int guards, int guardArchers, int bandits, int banditArchers,
                          int quartermasters, int villagers, int beds, int chests, int boards, int leaders) {
        Expect(String id, int guards, int guardArchers, int bandits, int banditArchers,
               int quartermasters, int villagers, int beds, int chests, int boards) {
            this(id, guards, guardArchers, bandits, banditArchers, quartermasters, villagers, beds, chests, boards, 0);
        }
    }
}
