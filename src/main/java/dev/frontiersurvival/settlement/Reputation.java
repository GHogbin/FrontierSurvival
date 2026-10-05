package dev.frontiersurvival.settlement;

import dev.frontiersurvival.FrontierConfig;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

public final class Reputation {
    public static Optional<BlockPos> local(Entity entity) {
        if (!(entity.level() instanceof ServerLevel level) || !level.dimension().equals(Level.OVERWORLD)) {
            return Optional.empty();
        }
        return SettlementState.get(level).nearest(level, entity.blockPosition(), 64);
    }

    public static int localScore(Entity resident, Player player) {
        if (!FrontierConfig.REPUTATION.get() || !(resident.level() instanceof ServerLevel level)) return 0;
        return local(resident).map(center -> SettlementState.get(level).score(center, player.getUUID())).orElse(0);
    }

    public static boolean outlaw(Entity resident, Player player) {
        return FrontierConfig.OUTLAW_GUARDS.get() && !player.isCreative() && !player.isSpectator()
                && localScore(resident, player) <= -30;
    }

    public static String tier(int score) {
        if (score <= -30) return "outlaw";
        if (score <= -15) return "unwelcome";
        if (score < 0) return "suspicious";
        if (score >= 60) return "champion";
        if (score >= 20) return "trusted";
        return "neutral";
    }

    public static int emeraldPrice(int base, int score) {
        if (score >= 60) return Math.max(1, base - Math.max(1, base * 35 / 100));
        if (score >= 20) return Math.max(1, base - Math.max(1, base / 4));
        if (score < 0) return Math.min(64, base + Math.max(1, (base + 3) / 4));
        return base;
    }

    public static void show(Entity resident, Player player) {
        Optional<BlockPos> center = local(resident);
        if (center.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.frontiersurvival.no_settlement"), false);
        } else {
            show((ServerLevel) resident.level(), center.get(), player);
        }
    }

    public static void show(ServerLevel level, BlockPos center, Player player) {
        int score = FrontierConfig.REPUTATION.get() ? SettlementState.get(level).score(center, player.getUUID()) : 0;
        player.displayClientMessage(Component.translatable("message.frontiersurvival.standing",
                Component.translatable("settlement.frontiersurvival."
                        + (SettlementState.get(level).isOutpost(center) ? "outpost" : "hamlet")),
                center.getX(), center.getZ(), score,
                Component.translatable("reputation.frontiersurvival." + tier(score))), false);
    }

    public static void changed(Player player, int change, int score) {
        if (change != 0) player.displayClientMessage(Component.translatable("message.frontiersurvival.changed",
                change > 0 ? "+" + change : Integer.toString(change), score,
                Component.translatable("reputation.frontiersurvival." + tier(score))), true);
    }

    private Reputation() {}
}
