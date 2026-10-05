package dev.frontiersurvival.worldgen;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * Natural terrain before carvers and features. Noise-based worlds are filled one chunk at a time using vanilla's
 * own cell interpolation, which is far cheaper than building a separate noise column for every sampled block.
 */
public final class TerrainSampler {
    private static final int SCAN_ABOVE_PRELIMINARY = 24;
    private static final int MAX_SCAN_DEPTH = 160;

    private final Structure.GenerationContext context;
    private final NoiseGeneratorSettings noise;
    private final NoiseSettings vertical;
    private final Long2ObjectOpenHashMap<TerrainPlanner.Sample[]> chunks = new Long2ObjectOpenHashMap<>();

    public TerrainSampler(Structure.GenerationContext context) {
        this.context = context;
        if (context.chunkGenerator() instanceof NoiseBasedChunkGenerator generator) {
            noise = generator.generatorSettings().value();
            vertical = noise.noiseSettings().clampToHeightAccessor(context.heightAccessor());
        } else {
            noise = null;
            vertical = null;
        }
    }

    public TerrainPlanner.Sample sample(int x, int z) {
        if (noise == null) return column(x, z);
        long key = ChunkPos.asLong(x >> 4, z >> 4);
        TerrainPlanner.Sample[] chunk = chunks.get(key);
        if (chunk == null) {
            chunk = fill(x >> 4, z >> 4);
            chunks.put(key, chunk);
        }
        return chunk[(z & 15) * 16 + (x & 15)];
    }

    public int filledChunks() {
        return chunks.size();
    }

    private TerrainPlanner.Sample[] fill(int chunkX, int chunkZ) {
        int minX = chunkX << 4;
        int minZ = chunkZ << 4;
        ChunkNoise chunk = new ChunkNoise(context.randomState(), minX, minZ, vertical, noise);
        int width = chunk.width();
        int height = chunk.height();
        int cells = 16 / width;
        int minY = vertical.minY();
        int maxY = minY + vertical.height() - 1;
        int minCellY = Math.floorDiv(minY, height);
        TerrainPlanner.Sample[] samples = new TerrainPlanner.Sample[256];
        boolean[] wet = new boolean[256];
        chunk.initializeForFirstCellX();
        for (int cx = 0; cx < cells; cx++) {
            chunk.advanceCellX(cx);
            for (int cz = 0; cz < cells; cz++) {
                int cellX = minX + cx * width;
                int cellZ = minZ + cz * width;
                int preliminary = chunk.preliminarySurfaceLevel(cellX, cellZ);
                int start = preliminary == Integer.MAX_VALUE ? maxY
                        : Math.max(minY, Math.min(maxY, preliminary + SCAN_ABOVE_PRELIMINARY));
                int floor = Math.max(minY, start - MAX_SCAN_DEPTH);
                int remaining = width * width;
                for (int cy = Math.floorDiv(start, height) - minCellY; cy >= 0 && remaining > 0; cy--) {
                    chunk.selectCellYZ(cy, cz);
                    for (int inY = height - 1; inY >= 0 && remaining > 0; inY--) {
                        int y = (minCellY + cy) * height + inY;
                        chunk.updateForY(y, (double) inY / height);
                        for (int dx = 0; dx < width; dx++) {
                            int x = cellX + dx;
                            chunk.updateForX(x, (double) dx / width);
                            for (int dz = 0; dz < width; dz++) {
                                int z = cellZ + dz;
                                chunk.updateForZ(z, (double) dz / width);
                                int index = (z - minZ) * 16 + (x - minX);
                                if (samples[index] != null || y > start) continue;
                                BlockState state = chunk.state();
                                if (state == null) state = noise.defaultBlock();
                                if (!state.getFluidState().isEmpty()) {
                                    wet[index] = true;
                                } else if (!state.isAir()) {
                                    // Solid at the first scanned block means the true surface is above the scan.
                                    samples[index] = new TerrainPlanner.Sample(y, wet[index], y < start);
                                    remaining--;
                                    continue;
                                }
                                if (y <= floor) {
                                    samples[index] = new TerrainPlanner.Sample(y, wet[index], false);
                                    remaining--;
                                }
                            }
                        }
                    }
                }
            }
            chunk.swapSlices();
        }
        chunk.stopInterpolation();
        for (int i = 0; i < samples.length; i++) {
            if (samples[i] == null) samples[i] = new TerrainPlanner.Sample(minY, wet[i], false);
        }
        return samples;
    }

    private TerrainPlanner.Sample column(int x, int z) {
        int minY = context.heightAccessor().getMinBuildHeight();
        NoiseColumn column = context.chunkGenerator().getBaseColumn(x, z, context.heightAccessor(), context.randomState());
        boolean wet = false;
        for (int y = context.heightAccessor().getMaxBuildHeight() - 1; y >= minY; y--) {
            BlockState state = column.getBlock(y);
            if (state.is(BlockTags.LEAVES)) continue;
            wet |= !state.getFluidState().isEmpty();
            if (state.isSolid() && state.getFluidState().isEmpty()) return new TerrainPlanner.Sample(y, wet);
        }
        return new TerrainPlanner.Sample(minY, wet, false);
    }

    private static Aquifer.FluidPicker fluids(NoiseGeneratorSettings settings) {
        // Mirrors NoiseBasedChunkGenerator's global fluid levels.
        Aquifer.FluidStatus lava = new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState());
        int sea = settings.seaLevel();
        Aquifer.FluidStatus water = new Aquifer.FluidStatus(sea, settings.defaultFluid());
        return (x, y, z) -> y < Math.min(-54, sea) ? lava : water;
    }

    private static final class ChunkNoise extends NoiseChunk {
        private ChunkNoise(RandomState random, int minX, int minZ, NoiseSettings vertical, NoiseGeneratorSettings settings) {
            super(16 / vertical.getCellWidth(), random, minX, minZ, vertical, NoBeard.INSTANCE, settings,
                    fluids(settings), Blender.empty());
        }

        private BlockState state() { return getInterpolatedState(); }
        private int width() { return cellWidth(); }
        private int height() { return cellHeight(); }
    }

    /** Structures sample untouched natural terrain, so no other structure's beard is included. */
    private enum NoBeard implements DensityFunctions.BeardifierOrMarker {
        INSTANCE;

        @Override
        public double compute(DensityFunction.FunctionContext context) { return 0; }

        @Override
        public double minValue() { return 0; }

        @Override
        public double maxValue() { return 0; }
    }
}
