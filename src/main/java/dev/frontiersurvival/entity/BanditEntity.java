package dev.frontiersurvival.entity;

import dev.frontiersurvival.FrontierSurvival;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.MoveTowardsRestrictionGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.RangedBowAttackGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;

public final class BanditEntity extends Monster implements RangedAttackMob {
    private static final EntityDataAccessor<Boolean> ARCHER = SynchedEntityData.defineId(BanditEntity.class, EntityDataSerializers.BOOLEAN);
    private final MeleeAttackGoal melee = new MeleeAttackGoal(this, 1.05, false);
    private final RangedBowAttackGoal<BanditEntity> ranged = new RangedBowAttackGoal<>(this, 1, 45, 18);
    private @Nullable BlockPos camp;
    private boolean roleAssigned;

    public BanditEntity(EntityType<? extends BanditEntity> type, Level level) {
        super(type, level);
        xpReward = isLeader() ? 15 : 5;
    }

    public boolean isLeader() { return getType() == FrontierSurvival.BANDIT_LEADER.get(); }
    public boolean isArcher() { return entityData.get(ARCHER); }

    public static AttributeSupplier.Builder attributes(boolean leader) {
        return createMonsterAttributes().add(Attributes.MAX_HEALTH, leader ? 48 : 24)
                .add(Attributes.MOVEMENT_SPEED, leader ? 0.27 : 0.25)
                .add(Attributes.ATTACK_DAMAGE, leader ? 6 : 3).add(Attributes.FOLLOW_RANGE, 28)
                .add(Attributes.ARMOR, leader ? 5 : 1);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        entityData.define(ARCHER, false);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(3, new MoveTowardsRestrictionGoal(this, 1));
        goalSelector.addGoal(4, new WaterAvoidingRandomStrollGoal(this, 0.8));
        goalSelector.addGoal(5, new LookAtPlayerGoal(this, Player.class, 10));
        goalSelector.addGoal(6, new RandomLookAroundGoal(this));
        targetSelector.addGoal(1, new HurtByTargetGoal(this).setAlertOthers());
        targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
        targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, GuardEntity.class, true));
        targetSelector.addGoal(4, new NearestAttackableTargetGoal<>(this, AbstractVillager.class, true));
    }

    public void setArcher(boolean archer) {
        archer = archer && !isLeader();
        entityData.set(ARCHER, archer);
        roleAssigned = true;
        setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(archer ? Items.BOW : isLeader() ? Items.IRON_AXE : Items.STONE_SWORD));
        setItemSlot(EquipmentSlot.OFFHAND, archer ? ItemStack.EMPTY : new ItemStack(Items.SHIELD));
        setDropChance(EquipmentSlot.MAINHAND, 0.05F);
        setDropChance(EquipmentSlot.OFFHAND, 0.02F);
        if (!level().isClientSide) {
            goalSelector.removeGoal(melee);
            goalSelector.removeGoal(ranged);
            goalSelector.addGoal(2, archer ? ranged : melee);
        }
    }

    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, MobSpawnType reason,
            @Nullable SpawnGroupData group, @Nullable CompoundTag tag) {
        SpawnGroupData result = super.finalizeSpawn(level, difficulty, reason, group, tag);
        if (!roleAssigned) setArcher(random.nextInt(3) == 0);
        if (reason == MobSpawnType.STRUCTURE) {
            camp = blockPosition();
            restrictTo(camp, 28);
            setPersistenceRequired();
        }
        return result;
    }

    @Override
    public void performRangedAttack(LivingEntity target, float strength) { FrontierArrow.fire(this, target, strength, false); }

    public static boolean canSpawn(EntityType<BanditEntity> type, ServerLevelAccessor level, MobSpawnType reason,
            BlockPos position, RandomSource random) {
        return level.getDifficulty() != Difficulty.PEACEFUL && level.canSeeSky(position)
                && level.getBlockState(position.below()).is(BlockTags.ANIMALS_SPAWNABLE_ON)
                && Monster.checkMobSpawnRules(type, level, reason, position, random);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean("Archer", isArcher());
        if (camp != null) tag.putLong("CampHome", camp.asLong());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        setArcher(tag.getBoolean("Archer"));
        if (tag.contains("CampHome")) {
            camp = BlockPos.of(tag.getLong("CampHome"));
            restrictTo(camp, 28);
        } else if (isPersistenceRequired()) {
            camp = blockPosition();
            restrictTo(camp, 28);
        }
    }

    @Override
    protected SoundEvent getAmbientSound() { return SoundEvents.PILLAGER_AMBIENT; }
    @Override
    protected SoundEvent getHurtSound(DamageSource source) { return SoundEvents.PLAYER_HURT; }
    @Override
    protected SoundEvent getDeathSound() { return SoundEvents.PLAYER_DEATH; }
}
