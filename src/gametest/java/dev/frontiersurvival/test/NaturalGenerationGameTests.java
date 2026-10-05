package dev.frontiersurvival.test;

import dev.frontiersurvival.FrontierSurvival;
import dev.frontiersurvival.entity.BanditEntity;
import dev.frontiersurvival.entity.GuardEntity;
import dev.frontiersurvival.worldgen.Palette;
import dev.frontiersurvival.worldgen.TerrainGroundPiece;
import dev.frontiersurvival.worldgen.TerrainSettlementStructure;
import dev.frontiersurvival.worldgen.TerrainTemplatePiece;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Lets Minecraft generate each frontier site through its normal chunk pipeline, then inspects the result. */
@GameTestHolder(FrontierSurvival.ID)
@PrefixGameTestTemplate(false)
public final class NaturalGenerationGameTests {
    /** Canopies of trees rooted just outside a site can overhang this far; the rest of the site must be clear. */
    private static final int CANOPY_INSET = 7;

    private NaturalGenerationGameTests() {}

    private record Site(String name, BoundingBox box, Class<? extends Mob> defender, int defenders) {}

    @GameTest(template = "test/arena", batch = "natural", timeoutTicks = 1200)
    public static void locatedSitesGenerateGroundedPopulatedRegionalAndTreeFree(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var structures = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        List<ResourceKey<Structure>> keys = structures.entrySet().stream()
                .filter(entry -> entry.getValue() instanceof TerrainSettlementStructure)
                .map(Map.Entry::getKey)
                .sorted(Comparator.comparing(key -> key.location().toString()))
                .toList();
        helper.assertTrue(keys.size() >= 3, "Every terrain site kind is registered: " + keys);
        Map<String, Site> sites = new LinkedHashMap<>();
        // Far from the GameTest arenas, so every chunk here is generated fresh by vanilla worldgen.
        BlockPos searchFrom = new BlockPos(0, 64, -3000);
        for (ResourceKey<Structure> key : keys) {
            String name = key.location().getPath();
            var holder = structures.getHolderOrThrow(key);
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
            TerrainGroundPiece ground = null;
            int columns = 0;
            int defenders = 0;
            boolean camp = name.contains("camp");
            for (StructurePiece piece : start.getPieces()) {
                if (piece instanceof TerrainGroundPiece found) ground = found;
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
                defenders += residents(building, camp);
            }
            helper.assertTrue(columns > 0 && ground != null, name + " contains a ground plan and grounded buildings");
            assertClearOfVegetation(helper, level, name, ground, start.getPieces());
            assertRegionalMaterials(helper, level, name, ground, start);
            assertBuildingsIntact(helper, level, name, start);
            sites.put(name, new Site(name, box, camp ? BanditEntity.class : GuardEntity.class, defenders));
            FrontierSurvival.LOGGER.info("Natural {} at {}: {} grounded columns, facing {}, palette {}, {} defenders",
                    name, box.getCenter(), columns, rotationOf(start), ground.palette().getSerializedName(), defenders);
        }
        helper.succeedWhen(() -> {
            for (Site site : sites.values()) {
                int found = level.getEntitiesOfClass(site.defender(), AABB.of(site.box()).inflate(8)).size();
                helper.assertTrue(site.defenders() > 0 && found >= site.defenders(),
                        site.name() + " has its generated defenders: " + found + "/" + site.defenders());
            }
        });
    }

    /** Defenders authored into a component template (guards, or bandits and their leader in camps). */
    private static int residents(TerrainTemplatePiece piece, boolean camp) {
        int count = 0;
        for (Tag entry : piece.template().save(new CompoundTag()).getList("entities", Tag.TAG_COMPOUND)) {
            String id = ((CompoundTag) entry).getCompound("nbt").getString("id");
            if (camp ? id.startsWith(FrontierSurvival.ID + ":bandit") : id.equals(FrontierSurvival.ID + ":guard")) count++;
        }
        return count;
    }

    private static void assertClearOfVegetation(GameTestHelper helper, ServerLevel level, String name, TerrainGroundPiece ground,
                                                List<StructurePiece> pieces) {
        BlockPos origin = ground.origin();
        int width = ground.width();
        int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
        for (int height : ground.groundHeights()) {
            low = Math.min(low, height);
            high = Math.max(high, height);
        }
        int leaves = 0, trunks = 0, plants = 0, turf = 0;
        List<String> rooted = new java.util.ArrayList<>();
        for (int z = 0; z < width; z++) {
            for (int x = 0; x < width; x++) {
                boolean inner = x >= CANOPY_INSET && z >= CANOPY_INSET && x < width - CANOPY_INSET && z < width - CANOPY_INSET;
                for (int y = low - 2; y <= high + 32; y++) {
                    BlockPos pos = new BlockPos(origin.getX() + x, y, origin.getZ() + z);
                    BlockState state = level.getBlockState(pos);
                    if (state.is(FrontierSurvival.SETTLEMENT_GRASS.get())) turf++;
                    // Generated trees always leave dirt under their trunk; settlement posts stand on stone or timber.
                    // A 2x2 tree rooted just outside may straddle the outermost ring, so only inner cells count.
                    boolean edge = x == 0 || z == 0 || x == width - 1 || z == width - 1;
                    boolean trunk = !edge && state.is(BlockTags.LOGS) && level.getBlockState(pos.below()).is(BlockTags.DIRT);
                    boolean plant = state.is(BlockTags.FLOWERS) || state.is(BlockTags.SAPLINGS) || state.is(Blocks.GRASS)
                            || state.is(Blocks.TALL_GRASS) || state.is(Blocks.FERN) || state.is(Blocks.LARGE_FERN)
                            || state.is(Blocks.SWEET_BERRY_BUSH);
                    // Grass in a carver tunnel under the courtyard is cave flora, not something rooted in the site.
                    for (int h = 1; h <= 4 && plant; h++) {
                        BlockState above = level.getBlockState(pos.above(h));
                        plant = above.canBeReplaced() || above.is(BlockTags.LEAVES) || above.isAir();
                    }
                    if (trunk) trunks++;
                    // Farmland inside farm plots is real soil; only open settlement ground must stay bare.
                    boolean open = ground.occupancy()[z * width + x] == 0;
                    if (plant && open) plants++;
                    if ((trunk || plant && open) && rooted.size() < 6) {
                        int i = z * width + x;
                        StringBuilder column = new StringBuilder();
                        for (int dy = -3; dy <= 2; dy++) {
                            column.append(' ').append(dy).append('=').append(level.getBlockState(pos.offset(0, dy, 0))
                                    .getBlock().getDescriptionId().replace("block.minecraft.", ""));
                        }
                        List<String> covering = new java.util.ArrayList<>();
                        for (StructurePiece piece : pieces) {
                            if (piece instanceof TerrainTemplatePiece building && piece.getBoundingBox().isInside(
                                    new BlockPos(pos.getX(), piece.getBoundingBox().minY(), pos.getZ()))) {
                                covering.add(building.template().getSize().toShortString() + "@" + building.rotation()
                                        + building.getBoundingBox().minX() + "," + building.getBoundingBox().minZ()
                                        + " foot" + building.footprint());
                            }
                        }
                        rooted.add(x + "," + (y - ground.groundHeights()[i]) + "," + z + " "
                                + state.getBlock().getDescriptionId() + " occupied=" + (ground.occupancy()[i] != 0)
                                + " path=" + (ground.pathHeights()[i] != Integer.MIN_VALUE)
                                + " blend=" + (ground.blendHeights()[i] == Integer.MIN_VALUE ? "-" : ground.blendHeights()[i] - ground.groundHeights()[i])
                                + " wall=" + (ground.wallHeights()[i] != Integer.MIN_VALUE)
                                + " chunk=" + new ChunkPos(pos) + " world=" + pos.toShortString() + " column:" + column
                                + " under " + covering + " refs=" + level.getChunk(pos).getAllReferences().keySet().stream()
                                .map(structure -> level.registryAccess().registryOrThrow(Registries.STRUCTURE).getKey(structure))
                                .toList());
                    }
                    if (inner && state.is(BlockTags.LEAVES)) leaves++;
                }
            }
        }
        helper.assertTrue(turf > 0, name + " ground is settled turf");
        helper.assertTrue(trunks == 0 && plants == 0, name + " has no trees, bushes or flowers rooted inside: trunks="
                + trunks + " plants=" + plants + " at " + rooted);
        if (leaves > 0) FrontierSurvival.LOGGER.info("{}: {} leaves from neighbouring canopies overhang the centre", name, leaves);
    }

    /**
     * Every turned, re-materialled component block (crops included) really stands in the generated world; only a
     * handful may differ, e.g. a farm pond frozen over in a snowy biome.
     */
    private static void assertBuildingsIntact(GameTestHelper helper, ServerLevel level, String name, StructureStart start) {
        int checked = 0, wrong = 0;
        List<String> samples = new java.util.ArrayList<>();
        for (StructurePiece piece : start.getPieces()) {
            if (!(piece instanceof TerrainTemplatePiece building)) continue;
            StructurePlaceSettings turned = new StructurePlaceSettings().setRotation(building.rotation());
            for (Block block : BuiltInRegistries.BLOCK) {
                if (block == Blocks.AIR || block == Blocks.STRUCTURE_VOID) continue;
                for (var info : building.template().filterBlocks(building.templatePosition(), turned, block)) {
                    BlockState mapped = building.palette().apply(info.state());
                    BlockState turf = TerrainGroundPiece.settledGround(mapped);
                    Block expected = turf != null ? turf.getBlock() : mapped.getBlock();
                    Block actual = level.getBlockState(info.pos()).getBlock();
                    checked++;
                    if (actual != expected) {
                        wrong++;
                        if (samples.size() < 5) samples.add(info.pos().subtract(building.templatePosition()).toShortString()
                                + " " + expected.getDescriptionId() + "->" + actual.getDescriptionId());
                    }
                }
            }
        }
        helper.assertTrue(checked > 0 && wrong <= Math.max(2, checked / 100), name + " components generate intact: "
                + wrong + "/" + checked + " differ " + samples);
    }

    private static void assertRegionalMaterials(GameTestHelper helper, ServerLevel level, String name,
                                                TerrainGroundPiece ground, StructureStart start) {
        BlockPos center = start.getBoundingBox().getCenter();
        int y = ground.groundHeights()[ground.width() / 2 * ground.width() + ground.width() / 2];
        Palette expected = Palette.forBiome(level.getNoiseBiome(QuartPos.fromBlock(center.getX()), QuartPos.fromBlock(y),
                QuartPos.fromBlock(center.getZ())));
        for (StructurePiece piece : start.getPieces()) {
            if (piece instanceof TerrainTemplatePiece building) {
                helper.assertTrue(building.palette() == ground.palette() && building.rotation() == rotationOf(start),
                        name + " builds every component in one palette and one facing");
            }
        }
        helper.assertTrue(ground.palette() == expected, name + " uses its biome's materials: " + ground.palette() + " vs " + expected);
        if (expected == Palette.OAK) return;
        int canonical = 0;
        for (StructurePiece piece : start.getPieces()) {
            if (!(piece instanceof TerrainTemplatePiece building)) continue;
            BoundingBox box = piece.getBoundingBox();
            // Only the building body: foundations reach down where unrelated mineshaft timbers may lie.
            for (BlockPos pos : BlockPos.betweenClosed(box.minX(), building.floorY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
                BlockState state = level.getBlockState(pos);
                if (state.is(Blocks.OAK_PLANKS) || state.is(Blocks.OAK_DOOR) || state.is(Blocks.STRIPPED_SPRUCE_LOG)) canonical++;
            }
        }
        helper.assertTrue(canonical == 0, name + " is fully re-materialled for " + expected + ": " + canonical + " oak blocks left");
    }

    private static net.minecraft.world.level.block.Rotation rotationOf(StructureStart start) {
        for (StructurePiece piece : start.getPieces()) {
            if (piece instanceof TerrainTemplatePiece building) return building.rotation();
        }
        return net.minecraft.world.level.block.Rotation.NONE;
    }
}
