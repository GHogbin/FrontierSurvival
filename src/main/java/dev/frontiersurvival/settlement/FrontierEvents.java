package dev.frontiersurvival.settlement;

import com.mojang.brigadier.Command;
import dev.frontiersurvival.FrontierConfig;
import dev.frontiersurvival.FrontierSurvival;
import dev.frontiersurvival.entity.BanditEntity;
import dev.frontiersurvival.entity.GuardEntity;
import dev.frontiersurvival.entity.QuartermasterEntity;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = FrontierSurvival.ID)
public final class FrontierEvents {
    public static boolean resident(LivingEntity entity) {
        return entity instanceof GuardEntity || entity instanceof QuartermasterEntity || entity instanceof Villager;
    }

    private static boolean eligible(ServerPlayer player) {
        return FrontierConfig.REPUTATION.get() && !player.isCreative() && !player.isSpectator()
                && !(player instanceof FakePlayer) && player.level().dimension().equals(Level.OVERWORLD);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void hurt(LivingDamageEvent event) {
        if (event.getAmount() <= 0 || !resident(event.getEntity())
                || !(event.getSource().getEntity() instanceof ServerPlayer player) || !eligible(player)) return;
        Reputation.local(event.getEntity()).ifPresent(center -> {
            SettlementState state = SettlementState.get(player.serverLevel());
            int change = state.penalizeHarm(center, player.getUUID(), player.serverLevel().getGameTime());
            Reputation.changed(player, change, state.score(center, player.getUUID()));
        });
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void death(LivingDeathEvent event) {
        if (!(event.getSource().getEntity() instanceof ServerPlayer player) || !eligible(player)) return;
        LivingEntity victim = event.getEntity();
        Reputation.local(victim).ifPresent(center -> {
            SettlementState state = SettlementState.get(player.serverLevel());
            int change;
            if (resident(victim)) {
                change = state.change(center, player.getUUID(), -25);
            } else if (victim instanceof Monster monster && (victim instanceof BanditEntity
                    || monster.getTarget() != null && (resident(monster.getTarget()) || monster.getTarget() == player))) {
                int amount = victim instanceof BanditEntity bandit && bandit.isLeader() ? 5 : 2;
                change = state.rewardDefense(center, player.getUUID(), player.serverLevel().getGameTime() / 24000L,
                        amount, FrontierConfig.DEFENSE_CAP.get());
            } else {
                return;
            }
            Reputation.changed(player, change, state.score(center, player.getUUID()));
        });
    }

    @SubscribeEvent
    public static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("frontier")
                .then(Commands.literal("reputation").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    Reputation.show(player, player);
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("help").executes(context -> {
                    context.getSource().sendSuccess(() -> Component.translatable("message.frontiersurvival.help"), false);
                    return Command.SINGLE_SUCCESS;
                })));
    }

    private FrontierEvents() {}
}
