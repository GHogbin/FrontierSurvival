package dev.frontiersurvival.entity;

import dev.frontiersurvival.settlement.Reputation;
import dev.frontiersurvival.settlement.SettlementState;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.MoveTowardsRestrictionGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.RangedBowAttackGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;

public final class GuardEntity extends PathfinderMob implements RangedAttackMob {
    private static final EntityDataAccessor<Boolean> ARCHER = SynchedEntityData.defineId(GuardEntity.class, EntityDataSerializers.BOOLEAN);
    private final MeleeAttackGoal melee = new MeleeAttackGoal(this, 1.1, false);
    private final RangedBowAttackGoal<GuardEntity> ranged = new RangedBowAttackGoal<>(this, 1, 35, 18);
    private @Nullable BlockPos home;
    private boolean homeBound;
    private boolean roleAssigned;

    public GuardEntity(EntityType<? extends GuardEntity> type, Level level) {
        super(type, level);
        if (getNavigation() instanceof GroundPathNavigation ground) ground.setCanOpenDoors(true);
    }

    public static AttributeSupplier.Builder attributes() {
        return createMobAttributes().add(Attributes.MAX_HEALTH, 30).add(Attributes.ATTACK_DAMAGE, 4)
                .add(Attributes.MOVEMENT_SPEED, 0.27).add(Attributes.FOLLOW_RANGE, 28).add(Attributes.ARMOR, 2);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        entityData.define(ARCHER, false);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new OpenDoorGoal(this, true));
        goalSelector.addGoal(3, new MoveTowardsRestrictionGoal(this, 1));
        goalSelector.addGoal(4, new WaterAvoidingRandomStrollGoal(this, 0.7));
        goalSelector.addGoal(5, new LookAtPlayerGoal(this, Player.class, 8));
        goalSelector.addGoal(6, new RandomLookAroundGoal(this));
        targetSelector.addGoal(1, new HurtByTargetGoal(this).setAlertOthers());
        targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, 10, true, false,
                entity -> entity instanceof Player player && Reputation.outlaw(this, player)));
        targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, Monster.class, 10, true, false,
                entity -> !(entity instanceof Creeper)));
    }

    public boolean isArcher() { return entityData.get(ARCHER); }

    public void setArcher(boolean archer) {
        entityData.set(ARCHER, archer);
        roleAssigned = true;
        setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(archer ? Items.BOW : Items.IRON_SWORD));
        setItemSlot(EquipmentSlot.OFFHAND, archer ? ItemStack.EMPTY : new ItemStack(Items.SHIELD));
        setDropChance(EquipmentSlot.MAINHAND, 0);
        setDropChance(EquipmentSlot.OFFHAND, 0);
        if (!level().isClientSide) {
            goalSelector.removeGoal(melee);
            goalSelector.removeGoal(ranged);
            goalSelector.addGoal(2, archer ? ranged : melee);
        }
    }

    public void bindHome(BlockPos position) {
        home = position.immutable();
        homeBound = true;
        restrictTo(home, 36);
        setPersistenceRequired();
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (homeBound && home == null && tickCount % 20 == 0 && level() instanceof ServerLevel server) {
            SettlementState.get(server).nearest(server, blockPosition(), 64).ifPresent(this::bindHome);
        }
    }

    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, MobSpawnType reason,
            @Nullable SpawnGroupData group, @Nullable CompoundTag tag) {
        SpawnGroupData result = super.finalizeSpawn(level, difficulty, reason, group, tag);
        if (!roleAssigned) setArcher(random.nextInt(3) == 0);
        homeBound = reason == MobSpawnType.STRUCTURE;
        setPersistenceRequired();
        return result;
    }

    @Override
    public void performRangedAttack(LivingEntity target, float strength) { FrontierArrow.fire(this, target, strength, true); }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (hand == InteractionHand.MAIN_HAND) {
            if (!level().isClientSide) {
                player.displayClientMessage(Component.translatable("message.frontiersurvival.guard."
                        + (isArcher() ? "archer" : "melee")), false);
                Reputation.show(this, player);
            }
            return InteractionResult.sidedSuccess(level().isClientSide);
        }
        return super.mobInteract(player, hand);
    }

    @Override
    public boolean removeWhenFarAway(double distance) { return false; }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean("Archer", isArcher());
        tag.putBoolean("HomeBound", homeBound);
        if (home != null) tag.putLong("SettlementHome", home.asLong());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        setArcher(tag.getBoolean("Archer"));
        homeBound = tag.getBoolean("HomeBound");
        if (tag.contains("SettlementHome")) bindHome(BlockPos.of(tag.getLong("SettlementHome")));
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) { return SoundEvents.PLAYER_HURT; }
    @Override
    protected SoundEvent getDeathSound() { return SoundEvents.PLAYER_DEATH; }
}
