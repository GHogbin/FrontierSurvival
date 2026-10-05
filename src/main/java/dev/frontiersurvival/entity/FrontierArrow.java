package dev.frontiersurvival.entity;

import dev.frontiersurvival.FrontierSurvival;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.level.Level;

public final class FrontierArrow extends Arrow {
    private boolean guardShot;
    private @Nullable UUID playerTarget;

    public FrontierArrow(EntityType<? extends FrontierArrow> type, Level level) { super(type, level); }

    public static void fire(Mob shooter, LivingEntity target, float strength, boolean guard) {
        FrontierArrow arrow = new FrontierArrow(FrontierSurvival.ARROW.get(), shooter.level());
        arrow.moveTo(shooter.getX(), shooter.getEyeY() - 0.1, shooter.getZ());
        arrow.setOwner(shooter);
        arrow.guardShot = guard;
        arrow.playerTarget = target instanceof Player ? target.getUUID() : null;
        arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
        arrow.setBaseDamage(guard ? 2 : 2.5);
        double x = target.getX() - shooter.getX();
        double z = target.getZ() - shooter.getZ();
        double y = target.getY(0.4) - arrow.getY();
        arrow.shoot(x, y + Math.sqrt(x * x + z * z) * 0.2, z, 1.6F, 8);
        shooter.playSound(SoundEvents.SKELETON_SHOOT, 1, 1);
        shooter.level().addFreshEntity(arrow);
    }

    @Override
    protected boolean canHitEntity(Entity target) {
        if (guardShot && target instanceof LivingEntity
                && !(target instanceof Monster && !(target instanceof Creeper))
                && !(target instanceof Player && target.getUUID().equals(playerTarget))) return false;
        if (!guardShot && target instanceof BanditEntity) return false;
        return super.canHitEntity(target);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean("GuardShot", guardShot);
        if (playerTarget != null) tag.putUUID("PlayerTarget", playerTarget);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        guardShot = tag.getBoolean("GuardShot");
        playerTarget = tag.hasUUID("PlayerTarget") ? tag.getUUID("PlayerTarget") : null;
    }
}
