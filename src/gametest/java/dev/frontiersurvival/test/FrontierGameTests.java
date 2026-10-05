package dev.frontiersurvival.test;

import com.mojang.authlib.GameProfile;
import dev.frontiersurvival.FrontierConfig;
import dev.frontiersurvival.FrontierSurvival;
import dev.frontiersurvival.entity.BanditEntity;
import dev.frontiersurvival.entity.FrontierArrow;
import dev.frontiersurvival.entity.GuardEntity;
import dev.frontiersurvival.entity.QuartermasterEntity;
import dev.frontiersurvival.settlement.Reputation;
import dev.frontiersurvival.settlement.SettlementBoardBlockEntity;
import dev.frontiersurvival.settlement.SettlementState;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FrontierSurvival.ID)
@PrefixGameTestTemplate(false)
public final class FrontierGameTests {
    @GameTest(template = "test/arena")
    public static void standingAndDailyLimitsSurviveReload(GameTestHelper helper) {
        SettlementState state = new SettlementState();
        BlockPos hamlet = new BlockPos(150, 64, 200);
        BlockPos tower = new BlockPos(400, 70, 200);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        state.register(hamlet, false);
        state.register(tower, true);
        helper.assertTrue(state.rewardDefense(hamlet, first, 2, 6, 10) == 6, "Defense earns trust");
        helper.assertTrue(state.rewardDefense(hamlet, first, 2, 6, 10) == 4, "Daily cap limits defense farming");
        helper.assertTrue(state.donate(hamlet, first, 2), "First donation succeeds");
        helper.assertTrue(state.penalizeHarm(hamlet, first, 100) == -3, "Resident harm loses trust");
        helper.assertTrue(state.penalizeHarm(hamlet, first, 101) == 0, "Rapid hits do not multiply penalties");
        SettlementState restored = SettlementState.load(state.save(new CompoundTag()));
        helper.assertTrue(restored.score(hamlet, first) == 12, "Standing survives NBT");
        helper.assertTrue(restored.score(tower, first) == 0 && restored.score(hamlet, second) == 0,
                "Settlements and players have independent standing");
        helper.assertTrue(restored.rewardDefense(hamlet, first, 2, 2, 10) == 0
                && !restored.donate(hamlet, first, 2), "Reload cannot reset daily limits");
        helper.assertTrue(restored.rewardDefense(hamlet, first, 3, 2, 10) == 2
                && restored.donate(hamlet, first, 3), "Next day resets both limits");
        restored.change(hamlet, first, Integer.MAX_VALUE);
        helper.assertTrue(restored.score(hamlet, first) == 100, "Score saturates safely");
        restored.change(hamlet, first, Integer.MIN_VALUE);
        helper.assertTrue(restored.score(hamlet, first) == -100 && restored.isOutpost(tower), "Bounds and settlement kind survive");
        helper.succeed();
    }

    @GameTest(template = "test/arena")
    public static void donationsAndPricesAreLocalAndCannotRepeat(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos center = board(helper, new BlockPos(10, 1, 10));
        QuartermasterEntity merchant = helper.spawn(FrontierSurvival.QUARTERMASTER.get(), 12, 1, 10);
        merchant.setNoAi(true);
        TestPlayer player = new TestPlayer(level);
        player.moveTo(merchant.blockPosition().east(), 0, 0);
        SettlementState state = SettlementState.get(level);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WHEAT, 64));
        helper.assertTrue(merchant.donate(player), "Correct donation accepted");
        helper.assertTrue(player.getMainHandItem().getCount() == 48 && player.getInventory().countItem(Items.EMERALD) == 1,
                "Consumes exact supplies and pays exactly one emerald");
        helper.assertTrue(state.score(center, player.getUUID()) == 5 && !merchant.donate(player),
                "Daily donation cannot be duplicated");
        helper.assertTrue(player.getMainHandItem().getCount() == 48, "Refusal does not consume supplies");
        state.change(center, player.getUUID(), 15);
        merchant.prepareOffers(player);
        var armor = merchant.getOffers().stream().filter(offer -> offer.getResult().is(Items.IRON_CHESTPLATE)).findFirst().orElseThrow();
        helper.assertTrue(armor.getCostA().getCount() == 6, "Trusted standing gives 25 percent armor discount");
        CompoundTag saved = new CompoundTag();
        merchant.save(saved);
        helper.assertTrue(armor.getCostA().getCount() == 6, "Saving never changes an open customer's prices");
        TestPlayer stranger = new TestPlayer(level);
        merchant.prepareOffers(stranger);
        helper.assertTrue(armor.getCostA().getCount() == 8, "Discounts never leak to another customer");
        state.change(center, stranger.getUUID(), -1);
        merchant.prepareOffers(stranger);
        helper.assertTrue(armor.getCostA().getCount() == 10, "Suspicious reputation increases prices");
        boolean enabled = FrontierConfig.REPUTATION.get();
        FrontierConfig.REPUTATION.set(false);
        try {
            merchant.prepareOffers(stranger);
            helper.assertTrue(armor.getCostA().getCount() == 8, "Disabling reputation restores base prices");
        } finally {
            FrontierConfig.REPUTATION.set(enabled);
        }
        helper.succeed();
    }

    @GameTest(template = "test/arena")
    public static void combatEventsRewardDefenseAndPunishMurder(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos center = board(helper, new BlockPos(12, 1, 12));
        TestPlayer player = new TestPlayer(level);
        player.moveTo(helper.absolutePos(new BlockPos(14, 1, 12)), 0, 0);
        BanditEntity bandit = helper.spawn(FrontierSurvival.BANDIT.get(), 16, 1, 12);
        bandit.setArcher(false);
        bandit.setNoAi(true);
        bandit.hurt(player.damageSources().playerAttack(player), 100);
        SettlementState state = SettlementState.get(level);
        helper.assertTrue(state.score(center, player.getUUID()) == 2, "Real player bandit kill triggers defense event");
        Villager villager = helper.spawn(EntityType.VILLAGER, 18, 1, 12);
        villager.setNoAi(true);
        villager.hurt(player.damageSources().playerAttack(player), 100);
        helper.assertTrue(state.score(center, player.getUUID()) == -26, "Real resident kill applies hurt and murder penalties");
        GuardEntity guard = helper.spawn(FrontierSurvival.GUARD.get(), 20, 1, 12);
        state.change(center, player.getUUID(), -4);
        helper.assertTrue(Reputation.outlaw(guard, player), "Outlaws are valid guard threats");
        player.creative = true;
        helper.assertTrue(!Reputation.outlaw(guard, player), "Creative players are never hunted");
        helper.succeed();
    }

    @GameTest(template = "test/arena", timeoutTicks = 120, batch = "combat")
    public static void guardsReallyFightBandits(GameTestHelper helper) {
        combatFloor(helper);
        GuardEntity guard = helper.spawn(FrontierSurvival.GUARD.get(), 8, 1, 8);
        guard.setArcher(false);
        BanditEntity bandit = helper.spawn(FrontierSurvival.BANDIT.get(), 11, 1, 8);
        bandit.setArcher(false);
        bandit.setNoAi(true);
        float health = bandit.getHealth();
        guard.setTarget(bandit);
        helper.succeedWhen(() -> helper.assertTrue(bandit.getHealth() < health, "Guard melee attack inflicts damage"));
    }

    @GameTest(template = "test/arena", timeoutTicks = 120, batch = "arrows")
    public static void guardArrowsPassThroughResidents(GameTestHelper helper) {
        combatFloor(helper);
        GuardEntity guard = helper.spawn(FrontierSurvival.GUARD.get(), 8, 1, 8);
        guard.setArcher(true);
        guard.setNoAi(true);
        Villager friend = helper.spawn(EntityType.VILLAGER, 11, 1, 8);
        friend.setNoAi(true);
        BanditEntity enemy = helper.spawn(FrontierSurvival.BANDIT.get(), 16, 1, 8);
        enemy.setNoAi(true);
        float friendlyHealth = friend.getHealth();
        float enemyHealth = enemy.getHealth();
        guard.performRangedAttack(enemy, 1);
        helper.succeedWhen(() -> {
            helper.assertTrue(enemy.getHealth() < enemyHealth, "Arrow strikes hostile beyond villager");
            helper.assertTrue(friend.getHealth() == friendlyHealth, "Villager is not hurt by friendly arrow");
        });
    }

    @GameTest(template = "test/arena")
    public static void equipmentRolesAndHomesSurviveSave(GameTestHelper helper) {
        GuardEntity guard = FrontierSurvival.GUARD.get().create(helper.getLevel());
        helper.assertTrue(guard != null, "Guard is registered");
        guard.setArcher(true);
        BlockPos center = helper.absolutePos(new BlockPos(10, 1, 10));
        guard.bindHome(center);
        CompoundTag saved = new CompoundTag();
        guard.save(saved);
        var loaded = EntityType.create(saved, helper.getLevel()).orElseThrow();
        helper.assertTrue(loaded instanceof GuardEntity restored && restored.isArcher()
                && restored.getMainHandItem().is(Items.BOW) && restored.getRestrictCenter().equals(center)
                && restored.isPersistenceRequired(), "Role, bow and absolute home persist");
        BanditEntity leader = FrontierSurvival.BANDIT_LEADER.get().create(helper.getLevel());
        helper.assertTrue(leader != null, "Leader is registered");
        leader.setArcher(true);
        helper.assertTrue(!leader.isArcher() && leader.getMaxHealth() == 48 && leader.getMainHandItem().is(Items.IRON_AXE),
                "Leader is stronger and always melee");
        helper.succeed();
    }

    @GameTest(template = "test/arena", batch = "hamlet", timeoutTicks = 100)
    public static void originalHamletLoadsWithUsableResidents(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(3, 1, 3));
        var template = level.getStructureManager().get(id("fortified_hamlet")).orElseThrow();
        validateEntityNbt(helper, template);
        helper.assertTrue(template.placeInWorld(level, origin, origin, new StructurePlaceSettings().setFinalizeEntities(true),
                RandomSource.create(42), 2), "Original hamlet template places");
        AABB bounds = new AABB(origin, origin.offset(39, 19, 39));
        BlockPos center = origin.offset(19, 6, 18);
        helper.assertTrue(level.getBlockEntity(center) instanceof SettlementBoardBlockEntity, "Settlement charter has registered block entity");
        // Entity section visibility is applied on the next chunk tick after template placement.
        helper.runAfterDelay(5, () -> {
            var guards = level.getEntitiesOfClass(GuardEntity.class, bounds);
            var merchants = level.getEntitiesOfClass(QuartermasterEntity.class, bounds);
            var villagers = level.getEntitiesOfClass(Villager.class, bounds);
            helper.assertTrue(guards.size() == 3 && guards.stream().filter(GuardEntity::isArcher).count() == 1
                    && merchants.size() == 1 && villagers.size() == 4, "Complete original hamlet population");
            villagers.forEach(villager -> villager.setNoAi(true));
            merchants.forEach(merchant -> merchant.setNoAi(true));
            for (var entity : guards) helper.assertTrue(level.noCollision(entity), "Guard spawn has headroom");
            for (var entity : merchants) helper.assertTrue(level.noCollision(entity), "Merchant spawn has headroom");
        });
        helper.runAfterDelay(30, () -> {
            helper.assertTrue(SettlementState.get(level).nearest(level, center, 1).orElseThrow().equals(center),
                    "Board registers absolute settlement location");
            helper.assertTrue(level.getEntitiesOfClass(GuardEntity.class, bounds).stream()
                    .allMatch(guard -> guard.getRestrictCenter().equals(center)),
                    "Generated guards bind to real settlement, not template-relative coords");
            helper.succeed();
        });
    }

    @GameTest(template = "test/arena", batch = "tower", timeoutTicks = 60)
    public static void originalTowerLoadsWithMerchantsAndArchers(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(4, 1, 4));
        var template = level.getStructureManager().get(id("watchtower")).orElseThrow();
        helper.assertTrue(template.placeInWorld(level, origin, origin, new StructurePlaceSettings().setFinalizeEntities(true),
                RandomSource.create(43), 2), "Watchtower places");
        AABB bounds = new AABB(origin, origin.offset(13, 19, 13));
        BlockPos center = origin.offset(6, 6, 6);
        ((SettlementBoardBlockEntity) level.getBlockEntity(center)).register(level);
        helper.assertTrue(SettlementState.get(level).isOutpost(center), "Outpost NBT is preserved");
        var chest = (ChestBlockEntity) level.getBlockEntity(origin.offset(8, 6, 4));
        chest.unpackLootTable(null);
        helper.assertTrue(!chest.isEmpty(), "Supply chest loot table resolves");
        helper.runAfterDelay(5, () -> {
            var guards = level.getEntitiesOfClass(GuardEntity.class, bounds);
            helper.assertTrue(guards.size() == 2 && guards.stream().filter(GuardEntity::isArcher).count() == 1
                    && level.getEntitiesOfClass(QuartermasterEntity.class, bounds).size() == 1, "Outpost population spawns");
            helper.succeed();
        });
    }

    @GameTest(template = "test/arena", batch = "camp", timeoutTicks = 60)
    public static void originalCampLoadsWithLeaderAndLoot(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(4, 1, 4));
        var template = level.getStructureManager().get(id("bandit_camp")).orElseThrow();
        validateEntityNbt(helper, template);
        helper.assertTrue(template.placeInWorld(level, origin, origin, new StructurePlaceSettings().setFinalizeEntities(true),
                RandomSource.create(44), 2), "Camp places");
        helper.runAfterDelay(5, () -> {
            var bandits = level.getEntitiesOfClass(BanditEntity.class, new AABB(origin, origin.offset(23, 14, 23)));
            helper.assertTrue(bandits.size() == 4 && bandits.stream().filter(BanditEntity::isLeader).count() == 1
                    && bandits.stream().filter(BanditEntity::isArcher).count() == 1, "Bandit camp spawns three bandits and leader");
            helper.assertTrue(bandits.stream().allMatch(bandit -> bandit.isPersistenceRequired()
                    && !bandit.getMainHandItem().isEmpty() && level.noCollision(bandit)), "Camp bandits persist, are armed and unobstructed");
            helper.succeed();
        });
    }

    @GameTest(template = "test/arena", batch = "worldgen", timeoutTicks = 200)
    public static void originalStructuresHaveValidWorldgenStarts(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        for (String name : new String[]{"fortified_hamlet", "watchtower", "bandit_camp"}) {
            var structure = registry.get(id(name));
            helper.assertTrue(structure != null, "Structure codec loads: " + name);
            var start = structure.generate(level.registryAccess(), generator, generator.getBiomeSource(),
                    level.getChunkSource().randomState(), level.getStructureManager(), level.getSeed(),
                    new net.minecraft.world.level.ChunkPos(helper.absolutePos(new BlockPos(10, 1, 10))),
                    0, level, biome -> true);
            helper.assertTrue(start.isValid() && !start.getPieces().isEmpty(), "Jigsaw pool generates a valid start: " + name);
        }
        helper.succeed();
    }

    private static BlockPos board(GameTestHelper helper, BlockPos relative) {
        helper.setBlock(relative, FrontierSurvival.BOARD.get());
        BlockPos center = helper.absolutePos(relative);
        ((SettlementBoardBlockEntity) helper.getLevel().getBlockEntity(center)).register(helper.getLevel());
        return center;
    }

    private static void validateEntityNbt(GameTestHelper helper,
            net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate template) {
        var entities = template.save(new CompoundTag()).getList("entities", net.minecraft.nbt.Tag.TAG_COMPOUND);
        for (int index = 0; index < entities.size(); index++) {
            CompoundTag nbt = entities.getCompound(index).getCompound("nbt");
            var entity = EntityType.create(nbt, helper.getLevel());
            helper.assertTrue(entity.isPresent(), "Template entity NBT loads: " + nbt);
        }
    }

    private static void combatFloor(GameTestHelper helper) {
        for (int x = 5; x <= 20; x++) {
            for (int z = 5; z <= 12; z++) helper.setBlock(x, 0, z, Blocks.GRASS_BLOCK);
        }
    }

    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath(FrontierSurvival.ID, path); }

    private static final class TestPlayer extends ServerPlayer {
        private boolean creative;

        private TestPlayer(ServerLevel level) {
            super(level.getServer(), level, new GameProfile(UUID.randomUUID(), "FrontierTester"));
            connection = new ServerGamePacketListenerImpl(level.getServer(), new Connection(PacketFlow.SERVERBOUND), this);
        }
        @Override
        public boolean isCreative() { return creative; }
        @Override
        public void displayClientMessage(Component message, boolean actionBar) {}
    }
}
