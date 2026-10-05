package dev.frontiersurvival;

import com.mojang.logging.LogUtils;
import dev.frontiersurvival.entity.BanditEntity;
import dev.frontiersurvival.entity.FrontierArrow;
import dev.frontiersurvival.entity.GuardEntity;
import dev.frontiersurvival.entity.QuartermasterEntity;
import dev.frontiersurvival.settlement.SettlementBoardBlock;
import dev.frontiersurvival.settlement.SettlementBoardBlockEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.common.ForgeSpawnEggItem;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.event.entity.SpawnPlacementRegisterEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.Logger;

@Mod(FrontierSurvival.ID)
public final class FrontierSurvival {
    public static final String ID = "frontiersurvival";
    public static final Logger LOGGER = LogUtils.getLogger();
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, ID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, ID);
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, ID);

    public static final RegistryObject<EntityType<GuardEntity>> GUARD = ENTITIES.register("guard",
            () -> EntityType.Builder.of(GuardEntity::new, MobCategory.CREATURE).sized(0.6F, 1.95F)
                    .clientTrackingRange(8).build(ID + ":guard"));
    public static final RegistryObject<EntityType<BanditEntity>> BANDIT = ENTITIES.register("bandit",
            () -> EntityType.Builder.of(BanditEntity::new, MobCategory.MONSTER).sized(0.6F, 1.95F)
                    .clientTrackingRange(8).build(ID + ":bandit"));
    public static final RegistryObject<EntityType<BanditEntity>> BANDIT_LEADER = ENTITIES.register("bandit_leader",
            () -> EntityType.Builder.of(BanditEntity::new, MobCategory.MONSTER).sized(0.6F, 1.95F)
                    .clientTrackingRange(8).build(ID + ":bandit_leader"));
    public static final RegistryObject<EntityType<QuartermasterEntity>> QUARTERMASTER = ENTITIES.register("quartermaster",
            () -> EntityType.Builder.of(QuartermasterEntity::new, MobCategory.CREATURE).sized(0.6F, 1.95F)
                    .clientTrackingRange(8).build(ID + ":quartermaster"));
    public static final RegistryObject<EntityType<FrontierArrow>> ARROW = ENTITIES.register("frontier_arrow",
            () -> EntityType.Builder.<FrontierArrow>of(FrontierArrow::new, MobCategory.MISC).sized(0.5F, 0.5F)
                    .clientTrackingRange(4).updateInterval(20).build(ID + ":frontier_arrow"));
    public static final RegistryObject<Block> BOARD = BLOCKS.register("settlement_board", SettlementBoardBlock::new);
    public static final RegistryObject<BlockEntityType<SettlementBoardBlockEntity>> BOARD_ENTITY =
            BLOCK_ENTITIES.register("settlement_board",
                    () -> BlockEntityType.Builder.of(SettlementBoardBlockEntity::new, BOARD.get()).build(null));
    public static final RegistryObject<Item> BOARD_ITEM = ITEMS.register("settlement_board",
            () -> new BlockItem(BOARD.get(), new Item.Properties()));
    public static final RegistryObject<Item> GUARD_EGG = ITEMS.register("guard_spawn_egg",
            () -> new ForgeSpawnEggItem(GUARD, 0x355c86, 0xbdc4c9, new Item.Properties()));
    public static final RegistryObject<Item> BANDIT_EGG = ITEMS.register("bandit_spawn_egg",
            () -> new ForgeSpawnEggItem(BANDIT, 0x59402b, 0x9a3232, new Item.Properties()));
    public static final RegistryObject<Item> LEADER_EGG = ITEMS.register("bandit_leader_spawn_egg",
            () -> new ForgeSpawnEggItem(BANDIT_LEADER, 0x382932, 0xd3a847, new Item.Properties()));
    public static final RegistryObject<Item> MERCHANT_EGG = ITEMS.register("quartermaster_spawn_egg",
            () -> new ForgeSpawnEggItem(QUARTERMASTER, 0x386549, 0xd3a847, new Item.Properties()));

    public FrontierSurvival(FMLJavaModLoadingContext context) {
        IEventBus bus = context.getModEventBus();
        ENTITIES.register(bus);
        ITEMS.register(bus);
        BLOCKS.register(bus);
        BLOCK_ENTITIES.register(bus);
        bus.addListener(FrontierSurvival::attributes);
        bus.addListener(FrontierSurvival::spawns);
        bus.addListener(FrontierSurvival::creativeTab);
        context.registerConfig(ModConfig.Type.COMMON, FrontierConfig.SPEC);
        LOGGER.info("Frontier Survival 0.1.0: independent Forge survival frontier");
    }

    private static void attributes(EntityAttributeCreationEvent event) {
        event.put(GUARD.get(), GuardEntity.attributes().build());
        event.put(BANDIT.get(), BanditEntity.attributes(false).build());
        event.put(BANDIT_LEADER.get(), BanditEntity.attributes(true).build());
        event.put(QUARTERMASTER.get(), QuartermasterEntity.attributes().build());
    }

    private static void spawns(SpawnPlacementRegisterEvent event) {
        event.register(BANDIT.get(), SpawnPlacements.Type.ON_GROUND, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                BanditEntity::canSpawn, SpawnPlacementRegisterEvent.Operation.REPLACE);
    }

    private static void creativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {
            event.accept(GUARD_EGG);
            event.accept(BANDIT_EGG);
            event.accept(LEADER_EGG);
            event.accept(MERCHANT_EGG);
        }
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) event.accept(BOARD_ITEM);
    }
}
