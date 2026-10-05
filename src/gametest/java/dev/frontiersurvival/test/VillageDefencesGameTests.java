package dev.frontiersurvival.test;

import dev.frontiersurvival.FrontierSurvival;
import dev.frontiersurvival.entity.GuardEntity;
import dev.frontiersurvival.entity.QuartermasterEntity;
import dev.frontiersurvival.settlement.SettlementState;
import dev.frontiersurvival.village.VillageDefences;
import dev.frontiersurvival.village.VillageDefences.Cell;
import dev.frontiersurvival.village.VillageDefences.DefencePlan;
import dev.frontiersurvival.village.VillageDefences.Stall;
import dev.frontiersurvival.village.VillageDefences.Tower;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FrontierSurvival.ID)
@PrefixGameTestTemplate(false)
public final class VillageDefencesGameTests {
    private VillageDefencesGameTests() {}

    @GameTest(template = "test/arena", batch = "villages", timeoutTicks = 80)
    public static void vanillaVillageSetIsDenser(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        StructureSet set = level.registryAccess().registryOrThrow(Registries.STRUCTURE_SET)
                .get(ResourceLocation.withDefaultNamespace("villages"));
        helper.assertTrue(set != null, "minecraft:villages structure set exists");
        helper.assertTrue(set.placement() instanceof RandomSpreadStructurePlacement, "villages use random_spread placement");
        RandomSpreadStructurePlacement placement = (RandomSpreadStructurePlacement) set.placement();
        helper.assertTrue(placement.spacing() == 28, "village spacing is 28, was " + placement.spacing());
        helper.assertTrue(placement.separation() == 7, "village separation is 7, was " + placement.separation());
        helper.assertTrue(set.structures().size() == 5, "the five vanilla village structures remain present");
        helper.succeed();
    }

    @GameTest(template = "test/arena", batch = "villages", timeoutTicks = 1200)
    public static void realVillagesGenerateDefencesAndServices(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var structures = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        HolderSet.Named<Structure> villageTag = structures.getTag(StructureTags.VILLAGE)
                .orElseThrow(() -> new IllegalStateException("Village structure tag missing"));
        List<BlockPos> origins = List.of(new BlockPos(20000, 64, 20000), new BlockPos(-22000, 64, 20500),
                new BlockPos(21000, 64, -22500), new BlockPos(-23000, 64, -21500), new BlockPos(26000, 64, 18000));
        Candidate chosen = null;
        for (BlockPos origin : origins) {
            var located = level.getChunkSource().getGenerator().findNearestMapStructure(level, villageTag, origin, 140, false);
            if (located == null) continue;
            StructureStart start = startAt(level, villageTag, new ChunkPos(located.getFirst()));
            if (start == null || !start.isValid()) continue;
            DefencePlan plan = VillageDefences.planForTest(level, start);
            if (!plan.ring().isEmpty() && !plan.gateGroups().isEmpty() && !plan.towers().isEmpty() && plan.stall() != null) {
                chosen = new Candidate(located.getFirst(), start, plan);
                break;
            }
        }
        helper.assertTrue(chosen != null, "located a village with gates, tower and stall space");
        Candidate village = chosen;
        forcePlanChunks(level, village.plan);
        DefencePlan recomputed = VillageDefences.planForTest(level, village.start);
        helper.assertTrue(village.plan.equals(recomputed), "village defence plan is deterministic");
        helper.assertFalse(village.plan.nonGateWallInsideBuilding(), "non-gate wall cells avoid expanded building boxes");
        assertWalls(helper, level, village.plan);
        helper.succeedWhen(() -> {
            assertTowerArcher(helper, level, village.plan);
            assertGateGuard(helper, level, village.plan);
            assertStall(helper, level, village.plan);
            FrontierSurvival.LOGGER.info("Village defence GameTest verified vanilla village near {} with {} wall cells, {} gates, {} towers and stall {}",
                    village.located, village.plan.ring().size(), village.plan.gateGroups().size(), village.plan.towers().size(), village.plan.stall().center());
        });
    }

    private static StructureStart startAt(ServerLevel level, HolderSet<Structure> villages, ChunkPos chunk) {
        level.setChunkForced(chunk.x, chunk.z, true);
        var access = level.getChunk(chunk.x, chunk.z);
        for (Holder<Structure> holder : villages) {
            StructureStart start = access.getStartForStructure(holder.value());
            if (start != null && start.isValid()) return start;
        }
        return null;
    }

    private static void forcePlanChunks(ServerLevel level, DefencePlan plan) {
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Cell c : plan.ring()) { minX = Math.min(minX, c.x()); minZ = Math.min(minZ, c.z()); maxX = Math.max(maxX, c.x()); maxZ = Math.max(maxZ, c.z()); }
        for (Tower t : plan.towers()) { minX = Math.min(minX, t.center().x() - 2); minZ = Math.min(minZ, t.center().z() - 2); maxX = Math.max(maxX, t.center().x() + 2); maxZ = Math.max(maxZ, t.center().z() + 2); }
        if (plan.stall() != null) { Stall s = plan.stall(); minX = Math.min(minX, s.center().x() - 2); minZ = Math.min(minZ, s.center().z() - 2); maxX = Math.max(maxX, s.center().x() + 2); maxZ = Math.max(maxZ, s.center().z() + 2); }
        for (int cx = (minX >> 4) - 1; cx <= (maxX >> 4) + 1; cx++) for (int cz = (minZ >> 4) - 1; cz <= (maxZ >> 4) + 1; cz++) {
            level.setChunkForced(cx, cz, true);
            level.getChunk(cx, cz);
        }
    }

    private static void assertWalls(GameTestHelper helper, ServerLevel level, DefencePlan plan) {
        int dry = 0, placed = 0, obstructed = 0;
        List<String> unexplained = new java.util.ArrayList<>();
        for (int i = 0; i < plan.ring().size(); i++) {
            Cell c = plan.ring().get(i);
            if (plan.gates().contains(c)) continue;
            int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE, c.x(), c.z()) - 1;
            int floor = level.getHeight(Heightmap.Types.OCEAN_FLOOR, c.x(), c.z()) - 1;
            boolean wet = false;
            for (int y = floor; y <= surface + 1; y++) wet |= !level.getFluidState(new BlockPos(c.x(), y, c.z())).isEmpty();
            if (wet) continue;
            dry++;
            BlockPos top = new BlockPos(c.x(), floor, c.z());
            // Posts stand on the ground, often beneath the canopy of a tree rooted outside the ring.
            boolean post = false;
            for (int dy = 0; dy <= 24 && !post; dy++) post = level.getBlockState(top.below(dy)).is(plan.palette().palisade().getBlock());
            if (post) { placed++; continue; }
            // A missing post must be explained by something the palisade may not replace: a trunk or a village block.
            BlockPos ground = top;
            while (ground.getY() > level.getMinBuildHeight() && (level.getBlockState(ground).is(BlockTags.LEAVES)
                    || level.getBlockState(ground).canBeReplaced())) ground = ground.below();
            boolean blocked = level.getBlockState(ground).is(BlockTags.LOGS);
            for (int h = 1; h <= 5 && !blocked; h++) {
                BlockState above = level.getBlockState(ground.above(h));
                blocked = !above.isAir() && !above.canBeReplaced() && !above.is(BlockTags.LEAVES);
            }
            if (blocked) obstructed++;
            else if (unexplained.size() < 8) unexplained.add(c + " ground=" + level.getBlockState(ground).getBlock());
            else unexplained.add("...");
        }
        FrontierSurvival.LOGGER.info("Village palisade: {} of {} dry ring cells posted, {} obstructed by trees or village blocks",
                placed, dry, obstructed);
        helper.assertTrue(dry >= 20, "village plan has enough dry wall cells to assess: " + dry);
        helper.assertTrue(placed + obstructed == dry && placed * 2 >= dry,
                "every dry ring cell carries a post unless a trunk or village block stands there: " + placed + " posted, "
                        + obstructed + " obstructed of " + dry + "; unexplained " + unexplained);
    }

    private static void assertTowerArcher(GameTestHelper helper, ServerLevel level, DefencePlan plan) {
        for (Tower tower : plan.towers()) {
            AABB box = new AABB(tower.center().x() - 3, level.getMinBuildHeight(), tower.center().z() - 3,
                    tower.center().x() + 4, level.getMaxBuildHeight(), tower.center().z() + 4);
            for (GuardEntity guard : level.getEntitiesOfClass(GuardEntity.class, box)) {
                if (guard.isArcher() && guard.hasPost() && guard.postRadius() <= 4) return;
            }
        }
        helper.fail("no post-bound archer found on a generated watchtower");
    }

    private static void assertGateGuard(GameTestHelper helper, ServerLevel level, DefencePlan plan) {
        for (var post : plan.gateGuards()) {
            AABB box = new AABB(post.center().x() - 3, level.getMinBuildHeight(), post.center().z() - 3,
                    post.center().x() + 4, level.getMaxBuildHeight(), post.center().z() + 4);
            for (GuardEntity guard : level.getEntitiesOfClass(GuardEntity.class, box)) {
                if (!guard.isArcher() && guard.hasPost() && guard.postRadius() >= 8) return;
            }
        }
        helper.fail("no post-bound melee gate guard found");
    }

    private static void assertStall(GameTestHelper helper, ServerLevel level, DefencePlan plan) {
        Stall stall = plan.stall();
        helper.assertTrue(stall != null, "plan includes a market stall");
        BlockPos board = null;
        for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y++) {
            BlockPos pos = new BlockPos(stall.center().x(), y, stall.center().z());
            if (level.getBlockState(pos).is(FrontierSurvival.BOARD.get())) { board = pos; break; }
        }
        helper.assertTrue(board != null, "village charter block exists at the stall");
        BlockPos boardPos = board;
        helper.assertTrue(SettlementState.get(level).nearest(level, boardPos, 4).filter(boardPos::equals).isPresent(), "village charter registered in settlement state");
        helper.assertTrue(SettlementState.get(level).kind(boardPos) == SettlementState.Kind.VILLAGE, "registered settlement kind is Village");
        AABB box = new AABB(stall.center().x() - 4, level.getMinBuildHeight(), stall.center().z() - 4,
                stall.center().x() + 5, level.getMaxBuildHeight(), stall.center().z() + 5);
        helper.assertTrue(!level.getEntitiesOfClass(QuartermasterEntity.class, box).isEmpty(), "quartermaster stands at the market stall");
    }

    private record Candidate(BlockPos located, StructureStart start, DefencePlan plan) {}
}
