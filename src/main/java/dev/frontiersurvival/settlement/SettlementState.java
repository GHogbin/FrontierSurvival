package dev.frontiersurvival.settlement;

import dev.frontiersurvival.FrontierSurvival;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.saveddata.SavedData;

public final class SettlementState extends SavedData {
    public enum Kind {
        HAMLET("hamlet"), OUTPOST("outpost"), VILLAGE("village");
        private final String key;
        Kind(String key) { this.key = key; }
        public String key() { return key; }
        public static Kind fromName(String name, boolean outpost) {
            for (Kind kind : values()) if (kind.key.equals(name)) return kind;
            return outpost ? OUTPOST : HAMLET;
        }
    }

    private final Map<BlockPos, Settlement> settlements = new HashMap<>();

    public static SettlementState get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                SettlementState::load, SettlementState::new, "frontiersurvival_settlements");
    }

    public void register(BlockPos center, boolean outpost) {
        register(center, outpost ? Kind.OUTPOST : Kind.HAMLET);
    }

    public void register(BlockPos center, Kind kind) {
        Settlement existing = settlements.get(center);
        if (existing == null) {
            settlements.put(center.immutable(), new Settlement(kind));
            setDirty();
        } else if (existing.kind != kind) {
            existing.kind = kind;
            setDirty();
        }
    }

    public Optional<BlockPos> nearest(ServerLevel level, BlockPos position, int radius) {
        return settlements.keySet().stream()
                .filter(center -> Math.abs(center.getY() - position.getY()) <= 32
                        && horizontalDistance(center, position) <= (long) radius * radius)
                .filter(center -> level.hasChunkAt(center) && level.getBlockState(center).is(FrontierSurvival.BOARD.get()))
                .min(Comparator.comparingLong(center -> horizontalDistance(center, position)));
    }

    private static long horizontalDistance(BlockPos first, BlockPos second) {
        long x = (long) first.getX() - second.getX();
        long z = (long) first.getZ() - second.getZ();
        return x * x + z * z;
    }

    public boolean isOutpost(BlockPos center) {
        return kind(center) == Kind.OUTPOST;
    }

    public Kind kind(BlockPos center) {
        return requireSettlement(center).kind;
    }

    public int score(BlockPos center, UUID player) {
        Standing value = requireSettlement(center).players.get(player);
        return value == null ? 0 : value.score;
    }

    public int change(BlockPos center, UUID player, int amount) {
        Standing value = standing(center, player);
        int previous = value.score;
        value.score = (int) Math.max(-100L, Math.min(100L, (long) previous + amount));
        if (value.score != previous) setDirty();
        return value.score - previous;
    }

    public int rewardDefense(BlockPos center, UUID player, long day, int amount, int cap) {
        Standing value = standing(center, player);
        if (value.defenseDay != day) {
            value.defenseDay = day;
            value.defenseEarned = 0;
            setDirty();
        }
        int reward = Math.min(amount, Math.max(0, cap - value.defenseEarned));
        if (reward == 0) return 0;
        value.defenseEarned += reward;
        setDirty();
        return change(center, player, reward);
    }

    public boolean donate(BlockPos center, UUID player, long day) {
        Standing value = standing(center, player);
        if (value.donationDay == day) return false;
        value.donationDay = day;
        change(center, player, 5);
        setDirty();
        return true;
    }

    public boolean canDonate(BlockPos center, UUID player, long day) {
        Standing value = requireSettlement(center).players.get(player);
        return value == null || value.donationDay != day;
    }

    public int penalizeHarm(BlockPos center, UUID player, long tick) {
        Standing value = standing(center, player);
        if (tick - value.lastHarm < 40) return 0;
        value.lastHarm = tick;
        setDirty();
        return change(center, player, -3);
    }

    private Standing standing(BlockPos center, UUID player) {
        return requireSettlement(center).players.computeIfAbsent(player, ignored -> new Standing());
    }

    private Settlement requireSettlement(BlockPos center) {
        Settlement settlement = settlements.get(center);
        if (settlement == null) throw new IllegalArgumentException("Unknown frontier settlement " + center);
        return settlement;
    }

    public static SettlementState load(CompoundTag tag) {
        SettlementState state = new SettlementState();
        ListTag settlements = tag.getList("Settlements", Tag.TAG_COMPOUND);
        for (int i = 0; i < settlements.size(); i++) {
            CompoundTag data = settlements.getCompound(i);
            Settlement settlement = new Settlement(Kind.fromName(data.getString("Kind"), data.getBoolean("Outpost")));
            ListTag players = data.getList("Players", Tag.TAG_COMPOUND);
            for (int j = 0; j < players.size(); j++) {
                CompoundTag person = players.getCompound(j);
                Standing standing = new Standing();
                standing.score = Mth.clamp(person.getInt("Score"), -100, 100);
                standing.defenseDay = person.getLong("DefenseDay");
                standing.defenseEarned = Math.max(0, person.getInt("DefenseEarned"));
                standing.donationDay = person.getLong("DonationDay");
                standing.lastHarm = person.getLong("LastHarm");
                settlement.players.put(person.getUUID("Player"), standing);
            }
            state.settlements.put(BlockPos.of(data.getLong("Center")), settlement);
        }
        return state;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag values = new ListTag();
        settlements.forEach((center, settlement) -> {
            CompoundTag data = new CompoundTag();
            data.putLong("Center", center.asLong());
            data.putBoolean("Outpost", settlement.kind == Kind.OUTPOST);
            data.putString("Kind", settlement.kind.key);
            ListTag players = new ListTag();
            settlement.players.forEach((id, standing) -> {
                CompoundTag person = new CompoundTag();
                person.putUUID("Player", id);
                person.putInt("Score", standing.score);
                person.putLong("DefenseDay", standing.defenseDay);
                person.putInt("DefenseEarned", standing.defenseEarned);
                person.putLong("DonationDay", standing.donationDay);
                person.putLong("LastHarm", standing.lastHarm);
                players.add(person);
            });
            data.put("Players", players);
            values.add(data);
        });
        tag.put("Settlements", values);
        return tag;
    }

    private static final class Settlement {
        private Kind kind;
        private final Map<UUID, Standing> players = new HashMap<>();
        private Settlement(Kind kind) { this.kind = kind; }
    }

    private static final class Standing {
        private int score;
        private long defenseDay = -1;
        private int defenseEarned;
        private long donationDay = -1;
        private long lastHarm = -1000;
    }
}
