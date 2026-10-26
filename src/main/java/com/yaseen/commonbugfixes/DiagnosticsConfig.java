package com.yaseen.commonbugfixes;

import net.minecraftforge.common.ForgeConfigSpec;

/** Common config: log segment size, segment age and the in-memory queue limit. */
final class DiagnosticsConfig {
    static final ForgeConfigSpec SPEC;

    private static final int DEFAULT_SEGMENT_MIB = 50;
    private static final int DEFAULT_SEGMENT_MINUTES = 5;
    private static final int DEFAULT_MAX_PENDING = 200_000;

    private static final ForgeConfigSpec.IntValue SEGMENT_MIB;
    private static final ForgeConfigSpec.IntValue SEGMENT_MINUTES;
    private static final ForgeConfigSpec.IntValue MAX_PENDING;
    private static final ForgeConfigSpec.IntValue SLOW_TICK_MS;
    private static final ForgeConfigSpec.IntValue SLOW_TICK_FRAMES;
    private static final ForgeConfigSpec.IntValue MEMORY_WARN_PERCENT;
    private static final ForgeConfigSpec.IntValue HOTSPOT_THRESHOLD;
    private static final ForgeConfigSpec.BooleanValue ANALYZE_CRASHES;
    private static final ForgeConfigSpec.IntValue CRASH_LOG_LINES;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("logging");
        SEGMENT_MIB = builder
                .comment("Start a new log file when the current one reaches this size (MiB).")
                .defineInRange("segmentSizeMiB", DEFAULT_SEGMENT_MIB, 1, 1024);
        SEGMENT_MINUTES = builder
                .comment("Start a new log file when the current one is this old (minutes).")
                .defineInRange("segmentMinutes", DEFAULT_SEGMENT_MINUTES, 1, 120);
        MAX_PENDING = builder
                .comment("Maximum log records waiting in memory for the writer thread.",
                        "When the limit is reached new records are dropped and a LOG_RECORDS_DROPPED line is written.")
                .defineInRange("maxPendingRecords", DEFAULT_MAX_PENDING, 1_000, 5_000_000);
        builder.pop();

        builder.push("detection");
        SLOW_TICK_MS = builder
                .comment("When one server tick takes longer than this (ms), write the server thread's stack trace (SLOW_TICK).",
                        "It shows which code made the tick slow.")
                .defineInRange("slowTickMs", 500, 100, 10_000);
        SLOW_TICK_FRAMES = builder
                .comment("How many stack frames to write for each SLOW_TICK report.")
                .defineInRange("slowTickStackFrames", 25, 5, 200);
        MEMORY_WARN_PERCENT = builder
                .comment("Write a MEMORY_WARNING when heap use stays at or above this percent for 10 seconds,",
                        "or when the garbage collector uses at least 25% of the time.")
                .defineInRange("memoryWarnPercent", 95, 50, 99);
        HOTSPOT_THRESHOLD = builder
                .comment("Write an ENTITY_HOTSPOT line when one chunk holds at least this many entities.")
                .defineInRange("entityHotspotThreshold", 100, 10, 5000);
        builder.pop();

        builder.push("crashAnalysis");
        ANALYZE_CRASHES = builder
                .comment("At startup, analyse the newest crash report / JVM crash file and write the result to",
                        "'Common Bugfixes/crash-analysis'.")
                .define("analyzeCrashes", true);
        CRASH_LOG_LINES = builder
                .comment("How many lines from the end of the previous session log to add to a crash analysis (0 = none).")
                .defineInRange("logLinesInAnalysis", 150, 0, 2000);
        builder.pop();
        SPEC = builder.build();
    }

    private DiagnosticsConfig() {}

    static long segmentBytes() {
        return read(SEGMENT_MIB, DEFAULT_SEGMENT_MIB) * 1_048_576L;
    }

    static long segmentAgeNanos() {
        return read(SEGMENT_MINUTES, DEFAULT_SEGMENT_MINUTES) * 60_000_000_000L;
    }

    static int maxPendingRecords() {
        return read(MAX_PENDING, DEFAULT_MAX_PENDING);
    }

    static long slowTickMs() {
        return read(SLOW_TICK_MS, 500);
    }

    static int slowTickFrames() {
        return read(SLOW_TICK_FRAMES, 25);
    }

    static int memoryWarnPercent() {
        return read(MEMORY_WARN_PERCENT, 95);
    }

    static int hotspotThreshold() {
        return read(HOTSPOT_THRESHOLD, 100);
    }

    static boolean analyzeCrashes() {
        try {
            return ANALYZE_CRASHES.get();
        } catch (RuntimeException notLoadedYet) {
            return true;
        }
    }

    static int crashLogLines() {
        return read(CRASH_LOG_LINES, 150);
    }

    /** The value is not readable until Forge has loaded the config file; fall back to the default until then. */
    private static int read(ForgeConfigSpec.IntValue value, int fallback) {
        try {
            return value.get();
        } catch (RuntimeException notLoadedYet) {
            return fallback;
        }
    }
}
