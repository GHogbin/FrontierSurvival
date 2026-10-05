package dev.frontiersurvival.entity;

import dev.frontiersurvival.FrontierConfig;
import dev.frontiersurvival.settlement.Reputation;
import dev.frontiersurvival.settlement.SettlementState;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.LookAtTradingPlayerGoal;
import net.minecraft.world.entity.ai.goal.MoveTowardsRestrictionGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.goal.TradeWithPlayerGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.Level;

public final class QuartermasterEntity extends AbstractVillager {
    private long restockDay = -1;
    private @Nullable BlockPos home;

    public QuartermasterEntity(EntityType<? extends QuartermasterEntity> type, Level level) {
        super(type, level);
        setPersistenceRequired();
        if (getNavigation() instanceof GroundPathNavigation ground) ground.setCanOpenDoors(true);
    }

    public static AttributeSupplier.Builder attributes() {
        return createMobAttributes().add(Attributes.MAX_HEALTH, 24).add(Attributes.MOVEMENT_SPEED, 0.25);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new TradeWithPlayerGoal(this));
        goalSelector.addGoal(1, new LookAtTradingPlayerGoal(this));
        goalSelector.addGoal(2, new AvoidEntityGoal<>(this, Monster.class, 12, 1, 1.2));
        goalSelector.addGoal(3, new OpenDoorGoal(this, true));
        goalSelector.addGoal(4, new MoveTowardsRestrictionGoal(this, 1));
        goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.6));
        goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8));
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (home == null && tickCount % 20 == 0) Reputation.local(this).ifPresent(center -> {
            home = center;
            restrictTo(home, 24);
        });
        Player player = getTradingPlayer();
        if (!level().isClientSide && player != null && Reputation.localScore(this, player) <= -15) {
            player.closeContainer();
            setTradingPlayer(null);
        }
    }

    public void prepareOffers(Player player) {
        long day = level().getGameTime() / 24000L;
        MerchantOffers offers = getOffers();
        if (restockDay != day) {
            offers.forEach(MerchantOffer::resetUses);
            restockDay = day;
        }
        int standing = Reputation.localScore(this, player);
        for (MerchantOffer offer : offers) {
            offer.resetSpecialPriceDiff();
            if (offer.getBaseCostA().is(Items.EMERALD)) {
                int base = offer.getBaseCostA().getCount();
                offer.addToSpecialPriceDiff(Reputation.emeraldPrice(base, standing) - base);
            }
        }
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND || !isAlive() || isBaby() || isTrading()) {
            return super.mobInteract(player, hand);
        }
        if (!level().isClientSide) {
            if (player.isShiftKeyDown()) {
                donate(player);
            } else if (Reputation.localScore(this, player) <= -15) {
                player.displayClientMessage(Component.translatable("message.frontiersurvival.refused"), false);
                Reputation.show(this, player);
            } else {
                prepareOffers(player);
                setTradingPlayer(player);
                openTradingScreen(player, getDisplayName(), 1);
            }
        }
        return InteractionResult.sidedSuccess(level().isClientSide);
    }

    public boolean donate(Player player) {
        if (!(level() instanceof ServerLevel level) || !FrontierConfig.REPUTATION.get()
                || player.isSpectator() || player instanceof net.minecraftforge.common.util.FakePlayer
                || !player.isAlive() || player.level() != level || player.distanceToSqr(this) > 36) return false;
        Optional<BlockPos> center = Reputation.local(this);
        if (center.isEmpty()) {
            Reputation.show(this, player);
            return false;
        }
        if (player.isCreative()) {
            player.displayClientMessage(Component.translatable("message.frontiersurvival.donation_hint"), false);
            return false;
        }
        SettlementState state = SettlementState.get(level);
        long day = level.getGameTime() / 24000L;
        if (!state.canDonate(center.get(), player.getUUID(), day)) {
            player.displayClientMessage(Component.translatable("message.frontiersurvival.donation_used"), false);
            return false;
        }
        ItemStack held = player.getMainHandItem();
        int count = held.is(Items.WHEAT) ? 16 : held.is(Items.BREAD) ? 8 : held.is(Items.IRON_INGOT) ? 3 : 0;
        if (count == 0 || held.getCount() < count) {
            player.displayClientMessage(Component.translatable("message.frontiersurvival.donation_hint"), false);
            Reputation.show(this, player);
            return false;
        }
        int previous = state.score(center.get(), player.getUUID());
        if (!state.donate(center.get(), player.getUUID(), day)) {
            player.displayClientMessage(Component.translatable("message.frontiersurvival.donation_used"), false);
            return false;
        }
        held.shrink(count);
        ItemStack reward = new ItemStack(Items.EMERALD);
        if (!player.getInventory().add(reward)) player.drop(reward, false);
        int standing = state.score(center.get(), player.getUUID());
        Reputation.changed(player, standing - previous, standing);
        player.displayClientMessage(Component.translatable("message.frontiersurvival.donation_done"), false);
        playSound(SoundEvents.VILLAGER_YES, 0.8F, 1);
        return true;
    }

    @Override
    protected void updateTrades() {
        MerchantOffers trades = getOffers();
        trades.add(new MerchantOffer(new ItemStack(Items.EMERALD, 3), new ItemStack(Items.BREAD, 6), 8, 0, 0));
        trades.add(new MerchantOffer(new ItemStack(Items.EMERALD, 6), new ItemStack(Items.IRON_SWORD), 4, 0, 0));
        trades.add(new MerchantOffer(new ItemStack(Items.EMERALD, 8), new ItemStack(Items.IRON_CHESTPLATE), 3, 0, 0));
        trades.add(new MerchantOffer(new ItemStack(Items.EMERALD, 4), new ItemStack(Items.SHIELD), 4, 0, 0));
        trades.add(new MerchantOffer(new ItemStack(Items.EMERALD, 5), new ItemStack(Items.ARROW, 16), 8, 0, 0));
        trades.add(new MerchantOffer(new ItemStack(Items.WHEAT, 20), new ItemStack(Items.EMERALD), 6, 0, 0));
    }

    @Override
    public void setTradingPlayer(@Nullable Player player) {
        super.setTradingPlayer(player);
        if (player == null && offers != null) offers.forEach(MerchantOffer::resetSpecialPriceDiff);
    }

    @Override
    protected void rewardTradeXp(MerchantOffer offer) {}
    @Override
    public boolean showProgressBar() { return false; }
    @Override
    public boolean removeWhenFarAway(double distance) { return false; }
    @Override
    public AgeableMob getBreedOffspring(ServerLevel level, AgeableMob partner) { return null; }
    @Override
    public SoundEvent getNotifyTradeSound() { return SoundEvents.VILLAGER_YES; }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putLong("RestockDay", restockDay);
        if (home != null) tag.putLong("SettlementHome", home.asLong());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (offers != null) offers.forEach(MerchantOffer::resetSpecialPriceDiff);
        restockDay = tag.contains("RestockDay") ? tag.getLong("RestockDay") : -1;
        if (tag.contains("SettlementHome")) {
            home = BlockPos.of(tag.getLong("SettlementHome"));
            restrictTo(home, 24);
        }
    }
}
