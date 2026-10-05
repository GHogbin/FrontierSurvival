package dev.frontiersurvival.worldgen;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/** Re-materials oak-palette templates for the site's region, including cloth colours and villager clothing. */
public final class PaletteProcessor extends StructureProcessor {
    public static final Codec<PaletteProcessor> CODEC =
            Palette.CODEC.fieldOf("palette").xmap(PaletteProcessor::new, PaletteProcessor::palette).codec();

    private final Palette palette;

    public PaletteProcessor(Palette palette) {
        this.palette = palette;
    }

    public Palette palette() { return palette; }

    @Override
    public StructureTemplate.StructureBlockInfo processBlock(LevelReader level, BlockPos offset, BlockPos pivot,
                                                             StructureTemplate.StructureBlockInfo raw,
                                                             StructureTemplate.StructureBlockInfo info,
                                                             StructurePlaceSettings settings) {
        BlockState mapped = palette.apply(info.state());
        // Any grass authored into a template is laid as settlement turf, so nothing can take root on it.
        if (mapped.is(Blocks.GRASS_BLOCK)) mapped = TerrainGroundPiece.settledGround(mapped);
        return mapped == info.state() ? info : new StructureTemplate.StructureBlockInfo(info.pos(), mapped, info.nbt());
    }

    @Override
    public StructureTemplate.StructureEntityInfo processEntity(LevelReader level, BlockPos seedPos,
                                                               StructureTemplate.StructureEntityInfo raw,
                                                               StructureTemplate.StructureEntityInfo info,
                                                               StructurePlaceSettings settings,
                                                               StructureTemplate template) {
        if (!"minecraft:villager".equals(info.nbt.getString("id"))) return info;
        CompoundTag nbt = info.nbt.copy();
        CompoundTag data = nbt.getCompound("VillagerData");
        data.putString("type", BuiltInRegistries.VILLAGER_TYPE.getKey(palette.villagerType()).toString());
        nbt.put("VillagerData", data);
        return new StructureTemplate.StructureEntityInfo(info.pos, info.blockPos, nbt);
    }

    @Override
    protected StructureProcessorType<?> getType() {
        return TerrainTypes.PALETTE.get();
    }
}
