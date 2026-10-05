package dev.frontiersurvival.test;

import dev.frontiersurvival.FrontierSurvival;
import dev.frontiersurvival.entity.BanditEntity;
import dev.frontiersurvival.entity.GuardEntity;
import dev.frontiersurvival.worldgen.TerrainTemplatePiece;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Lets Minecraft generate each frontier site through its normal chunk pipeline, then inspects the result. */
@GameTestHolder(FrontierSurvival.ID)
@PrefixGameTestTemplate(false)
public final class NaturalGenerationGameTests {
    private NaturalGenerationGameTests() {}

    private record Site(String name, BoundingBox box, Class<? extends Mob> defender, int defenders) {}

    @GameTest(template = "test/arena", batch = "natural", timeoutTicks = 600)
    public static void locatedSitesGenerateGroundedAndPopulated(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var structures = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        Map<String, Site> sites = new LinkedHashMap<>();
        // Far from the GameTest arenas, so every chunk here is generated fresh by vanilla worldgen.
        BlockPos searchFrom = new BlockPos(0, 64, -3000);
        for (String name : new String[]{"terrain_fortified_hamlet", "terrain_bandit_camp", "terrain_watchtower"}) {
            var holder = structures.getHolderOrThrow(ResourceKey.create(Registries.STRUCTURE,
                    ResourceLocation.fromNamespaceAndPath(FrontierSurvival.ID, name)));
            var located = level.getChunkSource().getGenerator().findNearestMapStructure(level, HolderSet.direct(holder),
                    searchFrom, 64, false);
            helper.assertTrue(located != null, "/locate finds a naturally generated " + name);
            ChunkPos startChunk = new ChunkPos(located.getFirst());
            StructureStart start = level.getChunk(startChunk.x, startChunk.z).getStartForStructure(holder.value());
            helper.assertTrue(start != null && start.isValid(), "Located " + name + " has a real structure start");
            BoundingBox box = start.getBoundingBox();
            for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
                for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                    level.setChunkForced(cx, cz, true);
                    level.getChunk(cx, cz);
                }
            }
            int columns = 0;
            for (StructurePiece piece : start.getPieces()) {
                if (!(piece instanceof TerrainTemplatePiece building)) continue;
                BoundingBox footprint = building.footprint();
                for (int x = footprint.minX(); x <= footprint.maxX(); x++) {
                    for (int z = footprint.minZ(); z <= footprint.maxZ(); z++) {
                        BlockPos floor = new BlockPos(x, building.floorY(), z);
                        helper.assertTrue(!level.getBlockState(floor).isAir(), name + " floor exists at " + floor);
                        helper.assertTrue(!level.getBlockState(floor.below()).canBeReplaced(),
                                name + " floor is supported, not floating, at " + floor);
                        columns++;
                    }
                }
            }
            helper.assertTrue(columns > 0, name + " contains grounded building pieces");
            boolean camp = name.contains("camp");
            sites.put(name, new Site(name, box, camp ? BanditEntity.class : GuardEntity.class,
                    name.contains("hamlet") ? 3 : camp ? 4 : 2));
            FrontierSurvival.LOGGER.info("Natural {} at {} checked {} grounded footprint columns", name, box.getCenter(), columns);
        }
        helper.succeedWhen(() -> {
            for (Site site : sites.values()) {
                int found = level.getEntitiesOfClass(site.defender(), AABB.of(site.box()).inflate(8)).size();
                helper.assertTrue(found >= site.defenders(), site.name() + " has its generated defenders: " + found);
            }
        });
    }
}
