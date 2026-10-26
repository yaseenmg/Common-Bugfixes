# Changelog

## 1.1.0

- **Crash analysis:** at startup the newest crash report and `hs_err_pid*.log` are analysed. The result (likely mod, confidence, advice, context, and the end of the previous session log) is written to `Common Bugfixes/crash-analysis/`.
- Detects a previous session that ended without a clean end marker (hard kill).
- `/commonbugfixes status` and `/commonbugfixes lastcrash` commands.
- `SLOW_TICK` stack traces for server ticks above `slowTickMs`, `HITCH_CONTEXT` for ticks of 500 ms or more.
- `ENTITY_HOTSPOT` for chunks with a very large number of entities.
- `MEMORY_WARNING` for a nearly full heap or heavy garbage collection.
- Uncaught exceptions are logged; the end of the log is flushed on JVM shutdown.
- A new watchdog flag per server session, so monitors of an earlier single-player session cannot pile up.
- New config options (see README).
- The slow-tick monitor polls every quarter of its threshold, so a tick only slightly over the limit is still caught.
- Crash analysis understands ModLauncher frame prefixes such as `TRANSFORMER/minecraft@1.20.1/`.
- Per-session tick counters are reset when a new single-player world is opened, so world loading is not reported as a slow tick.
- Config values fall back to defaults on any error while the config file is not loaded yet.

## 1.0.0

First public release of the project formerly known as "Everything in this world" / Warium Diagnostics.

- Renamed to **Common Bugfixes** (mod id `common_bugfixes`). The log folder is now `Common Bugfixes/logs`; leftover logs in the old `Everything in this world/logs` and `logs/warium-diagnostics` folders are removed once at startup.
- Added `config/common_bugfixes-common.toml` with `segmentSizeMiB`, `segmentMinutes` and `maxPendingRecords`.
- The in-memory log queue is now limited. Dropped records are counted and reported.
- The entity tracker keeps a 64-bit fingerprint per entity instead of the whole state string.
- The stall watchdog no longer reports the integrated server as stalled while the single-player game is paused.
- `displayTest="IGNORE_ALL_VERSION"`: the mod no longer has to be installed on both client and server.
- Open source under the MIT license.
