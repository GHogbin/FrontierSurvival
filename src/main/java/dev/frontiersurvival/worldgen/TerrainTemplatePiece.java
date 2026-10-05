package dev.frontiersurvival.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.TemplateStructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

public final class TerrainTemplatePiece extends TemplateStructurePiece {
    /** Ground footprint along world axes, relative to the min corner of the (possibly turned) template box. */
    private final int groundX;
    private final int groundZ;
    private final int groundWidth;
    private final int groundDepth;
    private final int[] natural;
    private final Rotation rotation;
    private final Palette palette;

    public TerrainTemplatePiece(StructureTemplateManager manager, BlockPos origin, TerrainPlanner.Plan plan,
                                TerrainPlanner.Building building) {
        this(manager, origin, plan, building, Rotation.NONE, Palette.OAK);
    }

    /** {@code building} must come from a plan of the layout already turned by {@code rotation}. */
    public TerrainTemplatePiece(StructureTemplateManager manager, BlockPos origin, TerrainPlanner.Plan plan,
                                TerrainPlanner.Building building, Rotation rotation, Palette palette) {
        super(TerrainTypes.TERRAIN_BUILDING.get(), 0, manager, building.plot().template(),
                building.plot().template().toString(), settings(rotation, palette),
                position(manager, origin, building, rotation));
        this.rotation = rotation;
        this.palette = palette;
        TerrainPlanner.Plot plot = building.plot();
        groundX = plot.groundX();
        groundZ = plot.groundZ();
        groundWidth = plot.groundWidth();
        groundDepth = plot.groundDepth();
        natural = new int[groundWidth * groundDepth];
        for (int z = 0; z < groundDepth; z++) {
            for (int x = 0; x < groundWidth; x++) {
                natural[z * groundWidth + x] = plan.ground(plot.minX() + x, plot.minZ() + z);
            }
        }
        includeSupports();
    }

    public TerrainTemplatePiece(StructurePieceSerializationContext context, CompoundTag tag) {
        super(TerrainTypes.TERRAIN_BUILDING.get(), tag, context.structureTemplateManager(),
                location -> settings(rotation(tag), palette(tag)));
        rotation = rotation(tag);
        palette = palette(tag);
        groundX = tag.getInt("GroundX");
        groundZ = tag.getInt("GroundZ");
        groundWidth = tag.getInt("GroundWidth");
        groundDepth = tag.getInt("GroundDepth");
        natural = tag.getIntArray("Natural").clone();
        Vec3i size = template.getSize(rotation);
        if (groundX < 0 || groundZ < 0 || groundWidth < 1 || groundDepth < 1
                || groundX + groundWidth > size.getX() || groundZ + groundDepth > size.getZ()
                || natural.length != groundWidth * groundDepth) {
            throw new IllegalArgumentException("Invalid saved terrain building footprint");
        }
        for (int height : natural) {
            if (templatePosition.getY() - height > TerrainPlanner.MAX_SUPPORT
                    || height - templatePosition.getY() > TerrainPlanner.MAX_FOOTPRINT_RELIEF) {
                throw new IllegalArgumentException("Invalid saved terrain building foundation");
            }
        }
        includeSupports();
    }

    private static Rotation rotation(CompoundTag tag) {
        // Pieces saved before 0.2.0 were never turned.
        if (!tag.contains("Rot", Tag.TAG_STRING)) return Rotation.NONE;
        try {
            return Rotation.valueOf(tag.getString("Rot"));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid saved terrain building rotation", invalid);
        }
    }

    private static Palette palette(CompoundTag tag) {
        return tag.contains("Palette", Tag.TAG_STRING) ? Palette.byName(tag.getString("Palette")) : Palette.OAK;
    }

    private static StructurePlaceSettings settings(Rotation rotation, Palette palette) {
        // Known shape, like vanilla jigsaw pieces: re-checking survival before chunks are lit would uproot every crop.
        return new StructurePlaceSettings().setRotation(rotation).setIgnoreEntities(false).setKnownShape(true)
                .setFinalizeEntities(true).setKeepLiquids(false).addProcessor(new PaletteProcessor(palette));
    }

    /** Seats the turned template so its box starts exactly at the plot's grid corner. */
    private static BlockPos position(StructureTemplateManager manager, BlockPos origin, TerrainPlanner.Building building,
                                     Rotation rotation) {
        StructureTemplate template = manager.getOrCreate(building.plot().template());
        BoundingBox local = template.getBoundingBox(new StructurePlaceSettings().setRotation(rotation), BlockPos.ZERO);
        return new BlockPos(origin.getX() + building.plot().x() - local.minX(), building.floorY(),
                origin.getZ() + building.plot().z() - local.minZ());
    }

    private BoundingBox templateBox() {
        return template.getBoundingBox(placeSettings, templatePosition);
    }

    private void includeSupports() {
        BoundingBox box = templateBox();
        int highestGround = templatePosition.getY();
        for (int height : natural) highestGround = Math.max(highestGround, height);
        boundingBox = new BoundingBox(box.minX(), templatePosition.getY() - TerrainPlanner.MAX_SUPPORT,
                box.minZ(), box.maxX(), Math.max(box.maxY(), highestGround), box.maxZ());
    }

    public int floorY() { return templatePosition.getY(); }
    public int[] groundHeights() { return natural.clone(); }
    public Rotation rotation() { return rotation; }
    public Palette palette() { return palette; }
    public StructurePlaceSettings placeSettings() { return placeSettings; }

    /** The building's ground-contact columns, at floor level. */
    public BoundingBox footprint() {
        BoundingBox box = templateBox();
        int x = box.minX() + groundX;
        int z = box.minZ() + groundZ;
        return new BoundingBox(x, floorY(), z, x + groundWidth - 1, floorY(), z + groundDepth - 1);
    }

    @Override
    protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
        super.addAdditionalSaveData(context, tag);
        tag.putInt("GroundX", groundX);
        tag.putInt("GroundZ", groundZ);
        tag.putInt("GroundWidth", groundWidth);
        tag.putInt("GroundDepth", groundDepth);
        tag.putIntArray("Natural", natural.clone());
        tag.putString("Rot", rotation.name());
        tag.putString("Palette", palette.getSerializedName());
    }

    @Override
    public void postProcess(WorldGenLevel level, StructureManager structures, ChunkGenerator generator,
                            RandomSource random, BoundingBox chunkBox, ChunkPos chunk, BlockPos pivot) {
        int templateTop = floorY() + template.getSize().getY();
        BoundingBox box = templateBox();
        BlockState support = palette.apply(Blocks.COBBLESTONE.defaultBlockState());
        for (int z = 0; z < groundDepth; z++) {
            for (int x = 0; x < groundWidth; x++) {
                int ground = natural[z * groundWidth + x];
                int columnX = box.minX() + groundX + x;
                int columnZ = box.minZ() + groundZ + z;
                int bottom = floorY() - TerrainPlanner.MAX_SUPPORT;
                for (int y = floorY() - 1; y >= bottom; y--) {
                    BlockPos pos = new BlockPos(columnX, y, columnZ);
                    if (!chunkBox.isInside(pos)) continue;
                    // Fill to sampled ground, then on through any cave or ravine carved after sampling.
                    if (y < ground && !level.getBlockState(pos).canBeReplaced()) break;
                    level.setBlock(pos, support, 2);
                }
                // Low components (farms, lamps, posts) leave natural ground above their template; excavate it.
                for (int y = templateTop; y <= ground; y++) {
                    BlockPos pos = new BlockPos(columnX, y, columnZ);
                    if (chunkBox.isInside(pos)) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        super.postProcess(level, structures, generator, random, chunkBox, chunk, pivot);
        // Native template processing recomputes its box; retain the saved foundation coverage afterward.
        includeSupports();
    }

    @Override
    protected void handleDataMarker(String marker, BlockPos pos, ServerLevelAccessor level,
                                    RandomSource random, BoundingBox box) {
        // Component templates use real board block entities and native template entities, not data markers.
    }
}
