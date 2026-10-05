package dev.frontiersurvival.worldgen;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public final class TerrainTypes {
    private static final DeferredRegister<StructureType<?>> STRUCTURES =
            DeferredRegister.create(Registries.STRUCTURE_TYPE, "frontiersurvival");
    private static final DeferredRegister<StructurePieceType> PIECES =
            DeferredRegister.create(Registries.STRUCTURE_PIECE, "frontiersurvival");
    private static final DeferredRegister<StructureProcessorType<?>> PROCESSORS =
            DeferredRegister.create(Registries.STRUCTURE_PROCESSOR, "frontiersurvival");

    public static final RegistryObject<StructureType<TerrainSettlementStructure>> TERRAIN_SETTLEMENT =
            STRUCTURES.register("terrain_settlement", () -> () -> TerrainSettlementStructure.CODEC);
    public static final RegistryObject<StructurePieceType> TERRAIN_BUILDING =
            PIECES.register("terrain_building", () -> TerrainTemplatePiece::new);
    public static final RegistryObject<StructurePieceType> TERRAIN_GROUND =
            PIECES.register("terrain_ground", () -> TerrainGroundPiece::new);
    public static final RegistryObject<StructureProcessorType<PaletteProcessor>> PALETTE =
            PROCESSORS.register("palette", TerrainTypes::paletteType);

    private TerrainTypes() {}

    private static StructureProcessorType<PaletteProcessor> paletteType() {
        return () -> PaletteProcessor.CODEC;
    }

    public static void register(IEventBus bus) {
        STRUCTURES.register(bus);
        PIECES.register(bus);
        PROCESSORS.register(bus);
    }
}
