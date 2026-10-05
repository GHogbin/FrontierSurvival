package dev.frontiersurvival;

import net.minecraftforge.common.ForgeConfigSpec;

public final class FrontierConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue REPUTATION;
    public static final ForgeConfigSpec.IntValue DEFENSE_CAP;
    public static final ForgeConfigSpec.BooleanValue OUTLAW_GUARDS;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("reputation");
        REPUTATION = builder.comment("Persist local trust, defense rewards, donations and merchant price changes.")
                .define("enabled", true);
        DEFENSE_CAP = builder.comment("Maximum defense reputation earned per player per settlement per 24000 world ticks.")
                .defineInRange("dailyDefenseCap", 10, 0, 50);
        OUTLAW_GUARDS = builder.comment("Guards pursue survival players with local standing of -30 or below.")
                .define("guardsPursueOutlaws", true);
        builder.pop();
        SPEC = builder.build();
    }

    private FrontierConfig() {}
}
