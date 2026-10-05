package dev.frontiersurvival.worldgen;

import java.util.Arrays;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;

public final class TerrainGroundPiece extends StructurePiece {
    private final int originX;
    private final int originZ;
    private final int width;
    private final int[] natural;
    private final int[] occupancy;
    private final int[] paths;
    private final int[] wallHeights;
    private final int[] blend;
    private final Optional<TerrainPlanner.Walls> walls;

    public TerrainGroundPiece(BlockPos origin, TerrainPlanner.Plan plan) {
        super(TerrainTypes.TERRAIN_GROUND.get(), 0, bounds(origin, plan));
        originX = origin.getX();
        originZ = origin.getZ();
        width = plan.width();
        natural = plan.groundHeights();
        occupancy = plan.occupancy();
        paths = plan.pathHeights();
        wallHeights = plan.wallHeights();
        blend = plan.blendHeights();
        walls = plan.walls();
    }

    public TerrainGroundPiece(StructurePieceSerializationContext context, CompoundTag tag) {
        super(TerrainTypes.TERRAIN_GROUND.get(), tag);
        originX = tag.getInt("OriginX");
        originZ = tag.getInt("OriginZ");
        width = tag.getInt("Width");
        natural = tag.getIntArray("Natural").clone();
        occupancy = tag.getIntArray("Occupancy").clone();
        paths = tag.getIntArray("Paths").clone();
        wallHeights = tag.getIntArray("WallHeights").clone();
        // 0.1.1 pieces predate blended ground and simply keep their saved earthworks.
        if (tag.contains("Blend", Tag.TAG_INT_ARRAY)) {
            blend = tag.getIntArray("Blend").clone();
        } else {
            blend = new int[natural.length];
            Arrays.fill(blend, TerrainPlanner.ABSENT);
        }
        if (width < 13 || width > 39 || natural.length != width * width || occupancy.length != natural.length
                || paths.length != natural.length || wallHeights.length != natural.length || blend.length != natural.length) {
            throw new IllegalArgumentException("Invalid saved terrain ground profile");
        }
        if (tag.contains("Walls", Tag.TAG_COMPOUND)) {
            CompoundTag wall = tag.getCompound("Walls");
            walls = Optional.of(new TerrainPlanner.Walls(wall.getInt("Min"), wall.getInt("Max"),
                    wall.getInt("GateStart"), wall.getInt("GateEnd"), wall.getBoolean("NorthGate")));
        } else {
            walls = Optional.empty();
        }
        for (int i = 0; i < natural.length; i++) {
            if (paths[i] != TerrainPlanner.ABSENT && Math.abs((long) paths[i] - natural[i]) > TerrainPlanner.MAX_PATH_ADJUSTMENT
                    || wallHeights[i] != TerrainPlanner.ABSENT
                    && Math.abs((long) wallHeights[i] - natural[i]) > TerrainPlanner.MAX_PATH_ADJUSTMENT
                    || blend[i] != TerrainPlanner.ABSENT
                    && Math.abs((long) blend[i] - natural[i]) > TerrainPlanner.MAX_BLEND_CHANGE) {
                throw new IllegalArgumentException("Invalid saved terrain earthworks");
            }
        }
    }

    private static BoundingBox bounds(BlockPos origin, TerrainPlanner.Plan plan) {
        int min = Arrays.stream(plan.groundHeights()).min().orElseThrow();
        int max = Arrays.stream(plan.groundHeights()).max().orElseThrow();
        return new BoundingBox(origin.getX(), min - TerrainPlanner.MAX_SUPPORT, origin.getZ(),
                origin.getX() + plan.width() - 1, max + TerrainPlanner.MAX_PATH_ADJUSTMENT + 4,
                origin.getZ() + plan.width() - 1);
    }

    public int[] groundHeights() { return natural.clone(); }
    public int[] pathHeights() { return paths.clone(); }
    public int[] wallHeights() { return wallHeights.clone(); }
    public int[] occupancy() { return occupancy.clone(); }
    public int[] blendHeights() { return blend.clone(); }

    @Override
    protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
        tag.putInt("OriginX", originX);
        tag.putInt("OriginZ", originZ);
        tag.putInt("Width", width);
        tag.putIntArray("Natural", natural.clone());
        tag.putIntArray("Occupancy", occupancy.clone());
        tag.putIntArray("Paths", paths.clone());
        tag.putIntArray("WallHeights", wallHeights.clone());
        tag.putIntArray("Blend", blend.clone());
        walls.ifPresent(value -> {
            CompoundTag wall = new CompoundTag();
            wall.putInt("Min", value.min());
            wall.putInt("Max", value.max());
            wall.putInt("GateStart", value.gateStart());
            wall.putInt("GateEnd", value.gateEnd());
            wall.putBoolean("NorthGate", value.northGate());
            tag.put("Walls", wall);
        });
    }

    @Override
    public void postProcess(WorldGenLevel level, StructureManager structures, ChunkGenerator generator,
                            RandomSource random, BoundingBox chunkBox, ChunkPos chunk, BlockPos pivot) {
        for (int z = 0; z < width; z++) {
            for (int x = 0; x < width; x++) {
                int i = z * width + x;
                if (occupancy[i] != 0) continue;
                if (paths[i] != TerrainPlanner.ABSENT) {
                    column(level, chunkBox, x, z, natural[i], paths[i], Blocks.GRAVEL.defaultBlockState());
                    for (int y = paths[i] + 1; y <= Math.max(paths[i] + 3, natural[i] + 3); y++) {
                        write(level, chunkBox, x, y, z, Blocks.AIR.defaultBlockState());
                    }
                }
                if (walls.isPresent() && wallHeights[i] != TerrainPlanner.ABSENT && !walls.get().gate(x, z)) {
                    column(level, chunkBox, x, z, natural[i], wallHeights[i], Blocks.COBBLESTONE.defaultBlockState());
                    for (int y = wallHeights[i] + 1; y <= Math.max(wallHeights[i], natural[i]) + 3; y++) {
                        write(level, chunkBox, x, y, z, Blocks.OAK_LOG.defaultBlockState());
                    }
                }
                if (blend[i] != TerrainPlanner.ABSENT) blendColumn(level, chunkBox, x, z, natural[i], blend[i]);
            }
        }
    }

    private void blendColumn(WorldGenLevel level, BoundingBox chunkBox, int x, int z, int naturalY, int targetY) {
        BlockPos top = new BlockPos(originX + x, naturalY, originZ + z);
        if (!chunkBox.isInside(top)) return;
        BlockState current = level.getBlockState(top);
        // Keep the biome's own top layer (grass, podzol, sand, snow-covered grass) on the reshaped surface.
        BlockState surface = current.isAir() || !current.getFluidState().isEmpty()
                ? Blocks.GRASS_BLOCK.defaultBlockState() : current;
        BlockState fill = surface.is(BlockTags.DIRT) ? Blocks.DIRT.defaultBlockState() : surface;
        if (targetY > naturalY) {
            for (int y = naturalY; y < targetY; y++) write(level, chunkBox, x, y, z, fill);
        } else {
            for (int y = targetY + 1; y <= naturalY; y++) write(level, chunkBox, x, y, z, Blocks.AIR.defaultBlockState());
        }
        write(level, chunkBox, x, targetY, z, surface);
    }

    private void column(WorldGenLevel level, BoundingBox chunkBox, int x, int z, int naturalY,
                        int floorY, BlockState surface) {
        for (int y = naturalY; y < floorY; y++) {
            write(level, chunkBox, x, y, z, Blocks.DIRT.defaultBlockState());
        }
        write(level, chunkBox, x, floorY, z, surface);
    }

    private void write(WorldGenLevel level, BoundingBox chunkBox, int x, int y, int z, BlockState state) {
        BlockPos pos = new BlockPos(originX + x, y, originZ + z);
        if (chunkBox.isInside(pos)) level.setBlock(pos, state, 2);
    }
}
