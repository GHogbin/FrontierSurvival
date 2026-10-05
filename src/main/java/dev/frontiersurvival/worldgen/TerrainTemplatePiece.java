package dev.frontiersurvival.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.TemplateStructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

public final class TerrainTemplatePiece extends TemplateStructurePiece {
    private final int groundX;
    private final int groundZ;
    private final int groundWidth;
    private final int groundDepth;
    private final int[] natural;

    public TerrainTemplatePiece(StructureTemplateManager manager, BlockPos origin, TerrainPlanner.Plan plan,
                                TerrainPlanner.Building building) {
        super(TerrainTypes.TERRAIN_BUILDING.get(), 0, manager, building.plot().template(),
                building.plot().template().toString(), settings(),
                new BlockPos(origin.getX() + building.plot().x(), building.floorY(), origin.getZ() + building.plot().z()));
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
        super(TerrainTypes.TERRAIN_BUILDING.get(), tag, context.structureTemplateManager(), location -> settings());
        groundX = tag.getInt("GroundX");
        groundZ = tag.getInt("GroundZ");
        groundWidth = tag.getInt("GroundWidth");
        groundDepth = tag.getInt("GroundDepth");
        natural = tag.getIntArray("Natural").clone();
        if (groundX < 0 || groundZ < 0 || groundWidth < 1 || groundDepth < 1
                || groundX + groundWidth > template.getSize().getX()
                || groundZ + groundDepth > template.getSize().getZ()
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

    private static StructurePlaceSettings settings() {
        return new StructurePlaceSettings().setIgnoreEntities(false).setFinalizeEntities(true).setKeepLiquids(false);
    }

    private void includeSupports() {
        BoundingBox box = template.getBoundingBox(placeSettings, templatePosition);
        boundingBox = new BoundingBox(box.minX(), templatePosition.getY() - TerrainPlanner.MAX_SUPPORT,
                box.minZ(), box.maxX(), box.maxY(), box.maxZ());
    }

    public int floorY() { return templatePosition.getY(); }
    public int[] groundHeights() { return natural.clone(); }

    @Override
    protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
        super.addAdditionalSaveData(context, tag);
        tag.putInt("GroundX", groundX);
        tag.putInt("GroundZ", groundZ);
        tag.putInt("GroundWidth", groundWidth);
        tag.putInt("GroundDepth", groundDepth);
        tag.putIntArray("Natural", natural.clone());
    }

    @Override
    public void postProcess(WorldGenLevel level, StructureManager structures, ChunkGenerator generator,
                            RandomSource random, BoundingBox chunkBox, ChunkPos chunk, BlockPos pivot) {
        for (int z = 0; z < groundDepth; z++) {
            for (int x = 0; x < groundWidth; x++) {
                int bottom = natural[z * groundWidth + x];
                for (int y = floorY() - 1; y >= bottom; y--) {
                    BlockPos pos = new BlockPos(templatePosition.getX() + groundX + x, y,
                            templatePosition.getZ() + groundZ + z);
                    if (chunkBox.isInside(pos)) level.setBlock(pos, Blocks.COBBLESTONE.defaultBlockState(), 2);
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
