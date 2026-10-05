package dev.frontiersurvival.worldgen;

import dev.frontiersurvival.FrontierSurvival;
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
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.SnowyDirtBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;

public final class TerrainGroundPiece extends StructurePiece {
    /** Open ground is looked for this far above and below its planned height. */
    private static final int SETTLE_WINDOW = 3;
    /** Below its planned height, the exposed-top search stops here (deeper is cave, not courtyard). */
    private static final int SETTLE_FALLBACK_DEPTH = 8;
    /** How far a path or wall footing is propped up through ground hollowed out after sampling. */
    private static final int PATH_SUPPORT = 6;
    private final int originX;
    private final int originZ;
    private final int width;
    private final int[] natural;
    private final int[] occupancy;
    private final int[] paths;
    private final int[] wallHeights;
    private final int[] blend;
    private final Optional<TerrainPlanner.Walls> walls;
    private final Palette palette;

    public TerrainGroundPiece(BlockPos origin, TerrainPlanner.Plan plan) {
        this(origin, plan, Palette.OAK);
    }

    public TerrainGroundPiece(BlockPos origin, TerrainPlanner.Plan plan, Palette palette) {
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
        this.palette = palette;
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
        // Pieces saved before 0.2.0 were always oak-palette settlements.
        palette = tag.contains("Palette", Tag.TAG_STRING) ? Palette.byName(tag.getString("Palette")) : Palette.OAK;
        if (width < TerrainPlanner.MIN_WIDTH || width > TerrainPlanner.MAX_WIDTH || natural.length != width * width
                || occupancy.length != natural.length || paths.length != natural.length
                || wallHeights.length != natural.length || blend.length != natural.length) {
            throw new IllegalArgumentException("Invalid saved terrain ground profile");
        }
        if (tag.contains("Walls", Tag.TAG_COMPOUND)) {
            CompoundTag wall = tag.getCompound("Walls");
            // Before 0.2.0 the south side always had a gate and the north side optionally did.
            int sides = wall.contains("Sides", Tag.TAG_INT) ? wall.getInt("Sides")
                    : TerrainPlanner.Walls.SOUTH | (wall.getBoolean("NorthGate") ? TerrainPlanner.Walls.NORTH : 0);
            if (sides < 0 || sides > 15) throw new IllegalArgumentException("Invalid saved palisade gates");
            walls = Optional.of(new TerrainPlanner.Walls(wall.getInt("Min"), wall.getInt("Max"),
                    wall.getInt("GateStart"), wall.getInt("GateEnd"), sides));
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
    public Optional<TerrainPlanner.Walls> walls() { return walls; }
    public Palette palette() { return palette; }
    public int width() { return width; }
    public BlockPos origin() { return new BlockPos(originX, 0, originZ); }

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
        tag.putString("Palette", palette.getSerializedName());
        walls.ifPresent(value -> {
            CompoundTag wall = new CompoundTag();
            wall.putInt("Min", value.min());
            wall.putInt("Max", value.max());
            wall.putInt("GateStart", value.gateStart());
            wall.putInt("GateEnd", value.gateEnd());
            wall.putBoolean("NorthGate", value.northGate());
            wall.putInt("Sides", value.sides());
            tag.put("Walls", wall);
        });
    }

    @Override
    public void postProcess(WorldGenLevel level, StructureManager structures, ChunkGenerator generator,
                            RandomSource random, BoundingBox chunkBox, ChunkPos chunk, BlockPos pivot) {
        BlockState pathSurface = palette.path();
        BlockState wallBase = palette.foundation();
        BlockState post = palette.palisade();
        for (int z = 0; z < width; z++) {
            for (int x = 0; x < width; x++) {
                int i = z * width + x;
                if (occupancy[i] != 0) continue;
                if (paths[i] != TerrainPlanner.ABSENT) {
                    column(level, chunkBox, x, z, natural[i], paths[i], pathSurface);
                    for (int y = paths[i] + 1; y <= Math.max(paths[i] + 3, natural[i] + 3); y++) {
                        write(level, chunkBox, x, y, z, Blocks.AIR.defaultBlockState());
                    }
                }
                if (walls.isPresent() && wallHeights[i] != TerrainPlanner.ABSENT && !walls.get().gate(x, z)) {
                    column(level, chunkBox, x, z, natural[i], wallHeights[i], wallBase);
                    for (int y = wallHeights[i] + 1; y <= Math.max(wallHeights[i], natural[i]) + 3; y++) {
                        write(level, chunkBox, x, y, z, post);
                    }
                }
                if (blend[i] != TerrainPlanner.ABSENT) blendColumn(level, chunkBox, x, z, natural[i], blend[i]);
            }
        }
        settleOpenGround(level, chunkBox);
    }

    /**
     * Structures are placed before vegetation, so turning every open dirt-family surface in the settlement into
     * non-dirt turf now stops trees, bushes, flowers and mushrooms from later rooting inside the site. The surface
     * is found near the planned ground height, so overhangs or blocks hanging above the courtyard do not hide it.
     */
    private void settleOpenGround(WorldGenLevel level, BoundingBox chunkBox) {
        int minX = Math.max(chunkBox.minX(), originX);
        int maxX = Math.min(chunkBox.maxX(), originX + width - 1);
        int minZ = Math.max(chunkBox.minZ(), originZ);
        int maxZ = Math.min(chunkBox.maxZ(), originZ + width - 1);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int worldZ = minZ; worldZ <= maxZ; worldZ++) {
            for (int worldX = minX; worldX <= maxX; worldX++) {
                int i = (worldZ - originZ) * width + (worldX - originX);
                // Paths are settled too: if terrain carved after sampling swallowed a path block, its ground shows.
                if (occupancy[i] != 0) continue;
                int surface = paths[i] != TerrainPlanner.ABSENT ? paths[i]
                        : blend[i] != TerrainPlanner.ABSENT ? blend[i] : natural[i];
                boolean settled = false;
                for (int y = surface + SETTLE_WINDOW; y >= surface - SETTLE_WINDOW && !settled; y--) {
                    pos.set(worldX, y, worldZ);
                    settled = settle(level, chunkBox, pos);
                }
                // Untouched margins were never sampled exactly, and neighbouring canopies can overhang any cell:
                // look down from the exposed top instead.
                int top = level.getHeight(Heightmap.Types.OCEAN_FLOOR, worldX, worldZ) - 1;
                for (int y = top; y >= surface - SETTLE_FALLBACK_DEPTH && !settled; y--) {
                    pos.set(worldX, y, worldZ);
                    settled = settle(level, chunkBox, pos);
                }
            }
        }
    }

    private static boolean settle(WorldGenLevel level, BoundingBox chunkBox, BlockPos pos) {
        if (!chunkBox.isInside(pos)) return false;
        BlockState turf = settledGround(level.getBlockState(pos));
        // The exposed ground: a dirt-family block under air or a plant (flowers are not "replaceable" blocks).
        if (turf == null || !open(level.getBlockState(pos.above()))) return false;
        level.setBlock(pos, turf, 2);
        // Vegetation spilled from an already decorated neighbouring chunk cannot stay rooted on turf.
        for (int y = 1; y <= 2; y++) {
            BlockPos plant = pos.above(y);
            if (chunkBox.isInside(plant) && level.getBlockState(plant).getBlock() instanceof BushBlock) {
                level.setBlock(plant, Blocks.AIR.defaultBlockState(), 2);
            }
        }
        return true;
    }

    /** Space a block could be placed into: air, replaceable cover such as snow or grass, or any small plant. */
    private static boolean open(BlockState state) {
        return state.canBeReplaced() || state.getBlock() instanceof BushBlock;
    }

    /** The non-dirt turf replacing a dirt-family surface, or null when the surface is already safe. */
    public static BlockState settledGround(BlockState surface) {
        if (!surface.is(BlockTags.DIRT)) return null;
        BlockState turf = FrontierSurvival.SETTLEMENT_GRASS.get().defaultBlockState();
        return surface.hasProperty(SnowyDirtBlock.SNOWY)
                ? turf.setValue(SnowyDirtBlock.SNOWY, surface.getValue(SnowyDirtBlock.SNOWY)) : turf;
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
        // Carvers and caves can hollow ground after it was sampled; keep paths and wall footings (gravel falls) supported.
        BlockPos.MutableBlockPos below = new BlockPos.MutableBlockPos(originX + x, floorY - 1, originZ + z);
        for (int depth = 0; depth < PATH_SUPPORT && chunkBox.isInside(below)
                && open(level.getBlockState(below)); depth++, below.move(0, -1, 0)) {
            level.setBlock(below, Blocks.DIRT.defaultBlockState(), 2);
        }
    }

    private void write(WorldGenLevel level, BoundingBox chunkBox, int x, int y, int z, BlockState state) {
        BlockPos pos = new BlockPos(originX + x, y, originZ + z);
        if (chunkBox.isInside(pos)) level.setBlock(pos, state, 2);
    }
}
