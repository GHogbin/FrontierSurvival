package dev.frontiersurvival.test;

import dev.frontiersurvival.FrontierSurvival;
import dev.frontiersurvival.worldgen.TerrainPlanner;
import dev.frontiersurvival.worldgen.TerrainSampler;
import dev.frontiersurvival.worldgen.TerrainSettlementStructure;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Generates structure starts exactly as a default Overworld would, independent of the GameTest level preset. */
@GameTestHolder(FrontierSurvival.ID)
@PrefixGameTestTemplate(false)
public final class WorldgenSurveyGameTests {
    private static final long[] SEEDS = {20261005L, 4242L};
    private static final int HALF_SIZE = 1536;
    private static final ResourceLocation FRONTIER_SITES = ResourceLocation.fromNamespaceAndPath(FrontierSurvival.ID, "frontier_sites");

    private WorldgenSurveyGameTests() {}

    private record Found(int x, int z, int radius) {}

    @GameTest(template = "test/arena", batch = "survey", timeoutTicks = 400)
    public static void frontierSitesAppearAtPlayableDensity(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        RegistryAccess registries = level.registryAccess();
        var settings = registries.registryOrThrow(Registries.NOISE_SETTINGS).getHolderOrThrow(NoiseGeneratorSettings.OVERWORLD);
        var preset = registries.registryOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getHolderOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD);
        var sets = registries.registryOrThrow(Registries.STRUCTURE_SET);
        StructureSet set = sets.get(FRONTIER_SITES);
        helper.assertTrue(set != null && set.placement() instanceof RandomSpreadStructurePlacement, "Shared frontier site grid");
        RandomSpreadStructurePlacement placement = (RandomSpreadStructurePlacement) set.placement();
        Map<String, Integer> totals = new TreeMap<>();
        for (long seed : SEEDS) {
            MultiNoiseBiomeSource biomes = MultiNoiseBiomeSource.createFromPreset(preset);
            NoiseBasedChunkGenerator generator = new NoiseBasedChunkGenerator(biomes, settings);
            RandomState random = RandomState.create(settings.value(), registries.registryOrThrow(Registries.NOISE).asLookup(), seed);
            ChunkGeneratorStructureState placementState = ChunkGeneratorStructureState.createForNormal(random, seed, biomes, sets.asLookup());
            List<Found> found = new ArrayList<>();
            int minRegion = Math.floorDiv(Math.floorDiv(-HALF_SIZE, 16), placement.spacing());
            int maxRegion = Math.floorDiv(Math.floorDiv(HALF_SIZE - 1, 16), placement.spacing());
            int candidates = 0, excluded = 0, anyBiome = 0, attempts = 0;
            long nanos = 0;
            Map<String, Integer> generated = new TreeMap<>();
            Map<String, Integer> siteReasons = new TreeMap<>();
            for (int rx = minRegion; rx <= maxRegion; rx++) {
                for (int rz = minRegion; rz <= maxRegion; rz++) {
                    ChunkPos chunk = placement.getPotentialStructureChunk(seed, rx * placement.spacing(), rz * placement.spacing());
                    if (chunk.getMinBlockX() < -HALF_SIZE || chunk.getMinBlockX() >= HALF_SIZE
                            || chunk.getMinBlockZ() < -HALF_SIZE || chunk.getMinBlockZ() >= HALF_SIZE) continue;
                    candidates++;
                    if (!placement.isStructureChunk(placementState, chunk.x, chunk.z)) {
                        excluded++;
                        continue;
                    }
                    int x = chunk.getMiddleBlockX(), z = chunk.getMiddleBlockZ();
                    int y = generator.getFirstOccupiedHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, random);
                    Holder<Biome> biome = biomes.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y),
                            QuartPos.fromBlock(z), random.sampler());
                    if (set.structures().stream().anyMatch(option -> option.structure().value().biomes().contains(biome))) anyBiome++;
                    // Mirrors ChunkGenerator.createStructures: weighted choice, then the remaining kinds in turn.
                    List<StructureSet.StructureSelectionEntry> options = new ArrayList<>(set.structures());
                    WorldgenRandom selector = new WorldgenRandom(new LegacyRandomSource(0L));
                    selector.setLargeFeatureSeed(seed, chunk.x, chunk.z);
                    int total = options.stream().mapToInt(StructureSet.StructureSelectionEntry::weight).sum();
                    while (!options.isEmpty()) {
                        int roll = selector.nextInt(total);
                        int index = 0;
                        for (StructureSet.StructureSelectionEntry option : options) {
                            roll -= option.weight();
                            if (roll < 0) break;
                            index++;
                        }
                        StructureSet.StructureSelectionEntry option = options.get(index);
                        Structure structure = option.structure().value();
                        String name = option.structure().unwrapKey().orElseThrow().location().getPath();
                        attempts++;
                        long started = System.nanoTime();
                        StructureStart start = structure.generate(registries, generator, biomes, random,
                                level.getStructureManager(), seed, chunk, 0, level, structure.biomes()::contains);
                        nanos += System.nanoTime() - started;
                        if (start.isValid()) {
                            generated.merge(name, 1, Integer::sum);
                            var box = start.getBoundingBox();
                            found.add(new Found(box.getCenter().getX(), box.getCenter().getZ(),
                                    Math.max(box.getXSpan(), box.getZSpan()) / 2));
                            break;
                        }
                        if (structure.biomes().contains(biome) && structure instanceof TerrainSettlementStructure terrain) {
                            var context = new Structure.GenerationContext(registries, generator, biomes, random,
                                    level.getStructureManager(), seed, chunk, level, structure.biomes()::contains);
                            terrain.diagnose(context).forEach((reason, count) -> siteReasons.merge(name + "/" + reason, count, Integer::sum));
                        }
                        options.remove(index);
                        total -= option.weight();
                    }
                }
            }
            int overlaps = 0;
            for (int i = 0; i < found.size(); i++) {
                for (int j = i + 1; j < found.size(); j++) {
                    Found a = found.get(i), b = found.get(j);
                    if (Math.abs(a.x() - b.x()) < a.radius() + b.radius() && Math.abs(a.z() - b.z()) < a.radius() + b.radius()) overlaps++;
                }
            }
            generated.forEach((name, count) -> totals.merge(name, count, Integer::sum));
            FrontierSurvival.LOGGER.info("Survey seed={} spacing={} area={}km2 candidates={} villageExcluded={} suitableBiome={} "
                            + "generated={} overlappingPairs={} avgMsPerAttempt={} reasonsWhenRejected={}", seed,
                    placement.spacing(), String.format("%.1f", Math.pow(2.0 * HALF_SIZE / 1000.0, 2)), candidates, excluded,
                    anyBiome, generated, overlaps, String.format("%.1f", nanos / 1e6 / Math.max(1, attempts)), siteReasons);
            helper.assertTrue(overlaps == 0, "Frontier sites never overlap one another");
            // Regression floor: roughly half the measured density, per 3 km x 3 km default world.
            helper.assertTrue(generated.getOrDefault("terrain_fortified_hamlet", 0) >= 10, "Hamlets are common enough to find");
            helper.assertTrue(generated.getOrDefault("terrain_bandit_camp", 0) >= 15, "Bandit camps are common enough to find");
            helper.assertTrue(generated.getOrDefault("terrain_watchtower", 0) >= 20, "Watchtowers are common enough to find");
        }
        FrontierSurvival.LOGGER.info("Survey totals over {} seeds: {}", SEEDS.length, totals);
        helper.succeed();
    }

    @GameTest(template = "test/arena", batch = "sampler", timeoutTicks = 200)
    public static void chunkBatchSamplerMatchesVanillaColumns(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        RegistryAccess registries = level.registryAccess();
        var settings = registries.registryOrThrow(Registries.NOISE_SETTINGS).getHolderOrThrow(NoiseGeneratorSettings.OVERWORLD);
        var preset = registries.registryOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getHolderOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD);
        long seed = SEEDS[0];
        MultiNoiseBiomeSource biomes = MultiNoiseBiomeSource.createFromPreset(preset);
        NoiseBasedChunkGenerator generator = new NoiseBasedChunkGenerator(biomes, settings);
        RandomState random = RandomState.create(settings.value(), registries.registryOrThrow(Registries.NOISE).asLookup(), seed);
        int compared = 0, unknown = 0, wetColumns = 0;
        for (int[] chunk : new int[][]{{0, 0}, {37, -12}, {-50, 21}, {80, 80}, {-23, -91}, {5, 140}}) {
            ChunkPos pos = new ChunkPos(chunk[0], chunk[1]);
            var context = new Structure.GenerationContext(registries, generator, biomes, random,
                    level.getStructureManager(), seed, pos, level, biome -> true);
            TerrainSampler sampler = new TerrainSampler(context);
            for (int dz = 0; dz < 16; dz++) {
                for (int dx = 0; dx < 16; dx++) {
                    int x = pos.getMinBlockX() + dx, z = pos.getMinBlockZ() + dz;
                    TerrainPlanner.Sample fast = sampler.sample(x, z);
                    NoiseColumn column = generator.getBaseColumn(x, z, level, random);
                    int vanillaGround = Integer.MIN_VALUE;
                    boolean vanillaWet = false;
                    for (int y = level.getMaxBuildHeight() - 1; y >= level.getMinBuildHeight(); y--) {
                        BlockState state = column.getBlock(y);
                        if (!state.getFluidState().isEmpty()) vanillaWet = true;
                        else if (!state.isAir()) {
                            vanillaGround = y;
                            break;
                        }
                    }
                    if (!fast.solid()) {
                        unknown++;
                        continue;
                    }
                    compared++;
                    if (vanillaWet) wetColumns++;
                    helper.assertTrue(fast.groundY() == vanillaGround && fast.wet() == vanillaWet,
                            "Chunk sampler differs from vanilla at " + x + "," + z + ": " + fast + " vs ground="
                                    + vanillaGround + " wet=" + vanillaWet);
                }
            }
            helper.assertTrue(sampler.filledChunks() == 1, "One noise chunk serves all 256 columns");
        }
        FrontierSurvival.LOGGER.info("Sampler parity: compared={} wet={} unknownAboveOrBelowScan={}", compared, wetColumns, unknown);
        helper.assertTrue(compared >= 6 * 256 * 3 / 4, "Nearly every sampled land/water column is resolved exactly");
        helper.succeed();
    }
}
