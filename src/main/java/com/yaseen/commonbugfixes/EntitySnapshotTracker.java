package com.yaseen.commonbugfixes;

import net.minecraft.world.entity.Entity;

import java.util.HashMap;
import java.util.Map;

/**
 * Turns the once-per-second entity scan into compact "delta" records.
 *
 * Every 30 seconds (and on the first scan) a FULL_KEYFRAME is written with the complete state of
 * every entity. In between, an entity whose state is unchanged only gets a short ENTITY_TICK line,
 * and entities that were present in the previous scan but are missing now are reported with
 * ENTITY_NOT_IN_LOADED_SET.
 */
final class EntitySnapshotTracker {
    private static final long KEYFRAME_INTERVAL_NS = 30_000_000_000L;

    private Map<String, EntitySample> previous = new HashMap<>();
    private Map<String, EntitySample> current = new HashMap<>();
    private final Map<String, Integer> population = new HashMap<>();
    private final Map<String, Integer> chunkCounts = new HashMap<>();
    private long lastHotspotNs;
    private volatile String lastSummary = "no_scan_yet";
    private boolean keyframe;
    private long lastKeyframeNs;
    private long sequence;
    private long changed;
    private long disappeared;
    private long tickSamples;

    EntitySnapshotTracker() {}

    void begin(String side, long nowNanos, String worldSummary) {
        sequence++;
        current.clear();
        population.clear();
        chunkCounts.clear();
        changed = 0L;
        disappeared = 0L;
        tickSamples = 0L;
        keyframe = previous.isEmpty() || lastKeyframeNs == 0L || nowNanos - lastKeyframeNs >= KEYFRAME_INTERVAL_NS;
        if (keyframe) lastKeyframeNs = nowNanos;
        DiagnosticsLog.log("ENTITY_SNAPSHOT_BEGIN side=" + side + " seq=" + sequence
                + " interval_s=1 mode=" + (keyframe ? "FULL_KEYFRAME" : "DELTA") + " " + worldSummary);
    }

    void record(String side, Entity entity) {
        String dimension = entity.level().dimension().location().toString();
        String type = CommonBugfixes.entityTypeId(entity);
        String key = dimension + "|" + entity.getUUID();
        long signature = fingerprint(CommonBugfixes.entityChangeSignature(entity));
        int entityTick = entity.tickCount;
        EntitySample oldSample = previous.get(key);
        current.put(key, new EntitySample(signature, entityTick));
        population.merge(dimension + "|" + type, 1, Integer::sum);
        var chunk = entity.chunkPosition();
        chunkCounts.merge(dimension + "|" + chunk.x + "|" + chunk.z, 1, Integer::sum);

        if (keyframe || oldSample == null || oldSample.signature != signature) {
            String reason = keyframe ? "keyframe" : oldSample == null ? "new_or_reloaded" : "state_changed";
            DiagnosticsLog.log("ENTITY_STATE side=" + side + " seq=" + sequence + " change=" + reason
                    + " dimension=" + dimension + " " + CommonBugfixes.entityDescription(entity));
            changed++;
        } else {
            DiagnosticsLog.log("ENTITY_TICK side=" + side + " seq=" + sequence + " dimension=" + dimension
                    + " uuid=" + entity.getUUID() + " tick_count=" + entityTick + " tick_delta=" + (entityTick - oldSample.tickCount));
            tickSamples++;
        }
    }

    /** Short description of the last scan, for the log line written when a tick is slow. */
    String summary() {
        return lastSummary;
    }

    private String topTypes(int count) {
        Map<String, Integer> byType = new HashMap<>();
        for (Map.Entry<String, Integer> entry : population.entrySet()) {
            int split = entry.getKey().indexOf('|');
            byType.merge(split < 0 ? entry.getKey() : entry.getKey().substring(split + 1), entry.getValue(), Integer::sum);
        }
        StringBuilder text = new StringBuilder();
        byType.entrySet().stream()
                .sorted((a, b) -> b.getValue() != a.getValue().intValue() ? Integer.compare(b.getValue(), a.getValue()) : a.getKey().compareTo(b.getKey()))
                .limit(count)
                .forEach(entry -> {
                    if (text.length() > 0) text.append(',');
                    text.append(entry.getKey()).append('=').append(entry.getValue());
                });
        return text.length() == 0 ? "none" : text.toString();
    }

    /** Writes ENTITY_HOTSPOT when one chunk holds a very large number of entities (at most every 30 seconds). */
    private void reportHotspot(String side) {
        String bestKey = null;
        int best = 0;
        for (Map.Entry<String, Integer> entry : chunkCounts.entrySet()) {
            if (entry.getValue() > best) {
                best = entry.getValue();
                bestKey = entry.getKey();
            }
        }
        long now = System.nanoTime();
        if (bestKey == null || best < DiagnosticsConfig.hotspotThreshold()) return;
        if (lastHotspotNs != 0L && now - lastHotspotNs < 30_000_000_000L) return;
        lastHotspotNs = now;
        String[] parts = bestKey.split("\\|");
        if (parts.length != 3) return;
        int chunkX = Integer.parseInt(parts[1]);
        int chunkZ = Integer.parseInt(parts[2]);
        DiagnosticsLog.log("ENTITY_HOTSPOT side=" + side + " seq=" + sequence + " dimension=" + parts[0]
                + " chunk_x=" + chunkX + " chunk_z=" + chunkZ + " block_x=" + (chunkX * 16) + " block_z=" + (chunkZ * 16)
                + " entities_in_chunk=" + best + " top_types_in_world=" + topTypes(3)
                + " note=many_entities_in_one_chunk_can_cause_lag");
    }

    /** 64-bit FNV-1a fingerprint, so the previous scan keeps 8 bytes per entity instead of the whole state string. */
    private static long fingerprint(String text) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < text.length(); i++) {
            hash ^= text.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    void end(String side) {
        for (String key : previous.keySet()) {
            if (!current.containsKey(key)) {
                int split = key.indexOf('|');
                String dimension = split < 0 ? "unknown" : key.substring(0, split);
                String uuid = split < 0 ? key : key.substring(split + 1);
                DiagnosticsLog.log("ENTITY_NOT_IN_LOADED_SET side=" + side + " seq=" + sequence
                        + " dimension=" + dimension + " uuid=" + uuid
                        + " note=entity_left_loaded_snapshot; inspect_ENTITY_LEAVE_for_reason");
                disappeared++;
            }
        }
        population.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    int split = entry.getKey().indexOf('|');
                    String dimension = split < 0 ? "unknown" : entry.getKey().substring(0, split);
                    String type = split < 0 ? entry.getKey() : entry.getKey().substring(split + 1);
                    DiagnosticsLog.log("ENTITY_POPULATION side=" + side + " seq=" + sequence
                            + " dimension=" + dimension + " type=" + type + " count=" + entry.getValue());
                });
        reportHotspot(side);
        lastSummary = "entities=" + current.size() + " top_types=" + topTypes(3);
        DiagnosticsLog.log("ENTITY_SNAPSHOT_END side=" + side + " seq=" + sequence
                + " entities_seen=" + current.size() + " states_written=" + changed
                + " tick_samples=" + tickSamples + " no_longer_loaded=" + disappeared
                + " keyframe=" + keyframe);
        Map<String, EntitySample> old = previous;
        previous = current;
        current = old;
        current.clear();
    }

    private static final class EntitySample {
        private final long signature;
        private final int tickCount;

        private EntitySample(long signature, int tickCount) {
            this.signature = signature;
            this.tickCount = tickCount;
        }
    }
}
