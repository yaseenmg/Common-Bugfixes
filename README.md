# Common Bugfixes

[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
![Minecraft 1.20.1](https://img.shields.io/badge/Minecraft-1.20.1-blue)
![Forge 47](https://img.shields.io/badge/Forge-47.x-orange)

**Finds out why Minecraft crashed, and why it lags.**

After a crash, Common Bugfixes reads the crash report and tells you which mod is most likely responsible and what to try. While you play it keeps a detailed plain-text log, so lag, freezes and memory problems can be traced. It changes no gameplay.

## Requirements
- Minecraft **1.20.1**
- Forge **47.x**

It can be installed on a client, on a server, or on both. Neither side needs the other to have it.

## Crash analysis
At startup the mod looks at the newest crash report (`crash-reports/`) and the newest JVM crash file (`hs_err_pid*.log`) left by the previous run, and writes an analysis to `Common Bugfixes/crash-analysis/`:

- **Most likely mod**, with a confidence level (HIGH when the stack trace and Forge's own "suspected mods" agree).
- **Reason and advice** for common problems: out of memory, mod version mismatch (`NoSuchMethodError`, `ClassNotFoundException`), Mixin conflicts, bad entities and block entities, rendering and graphics-driver crashes, server freezes, wrong Java version, registry problems.
- **Context:** the entity type and position or the block that crashed, Minecraft/Java/memory details.
- **What happened just before:** warnings and the last 150 lines of the previous session log (the log of the previous session is read *before* it is cleaned up).
- **Hard kills:** if the last session has no end marker and no crash report matches it, an `unclean-exit` note is written.

Run `/commonbugfixes lastcrash` in game to read the newest analysis.

## Other tools
- `/commonbugfixes status` shows the log file, average and worst tick time of the last 100 ticks, an estimated TPS, the last entity scan and memory figures. It needs permission level 2.
- **Slow tick reports:** when one server tick takes longer than `slowTickMs` (default 500), a `SLOW_TICK` entry shows what the server thread was doing.
- **Entity hotspots:** `ENTITY_HOTSPOT` points to a chunk holding 100 or more entities.
- **Memory warnings:** `MEMORY_WARNING` when the heap stays nearly full or the garbage collector uses a quarter of the time.
- **Uncaught exceptions** from any thread are written to the log, and the end of the log is flushed when the game closes.

## What it records
Files are written to `<game folder>/Common Bugfixes/logs/` as `session-YYYYMMDD-HHmmss-SSS-partNNN.txt`.

- **Heartbeat every second:** server tick time, FPS, memory, garbage collection, thread count and log queue size.
- **World and player snapshots:** dimension, position, health, food, experience and the full inventory.
- **Entity tracking:** a full snapshot every 30 seconds and only the changes in between (`ENTITY_STATE`, `ENTITY_TICK`, `ENTITY_NOT_IN_LOADED_SET`, `ENTITY_POPULATION`).
- **Stall watchdog:** if the server tick or the client tick makes no progress for 2 seconds, or rendering for 3 seconds, a `THREAD_STALL` entry is written with the thread state and its full stack trace (at most once every 5 seconds).
- **Hitches:** `SERVER_HITCH` for ticks of 100 ms or more, `INCIDENT_SNAPSHOT` for ticks of 1000 ms or more, `CLIENT_HITCH` for frames of 100 ms or more.
- **Events:** entities joining and leaving, projectile impacts, damage and death, item use, block break/place/click, chunk loads, player login and logout, the loaded mod list, and client sounds with their distance to the player.

Writing happens on its own thread, so logging does not slow the game down.

## Configuration
`config/common_bugfixes-common.toml` is created on first launch.

| Option | Default | Range | Meaning |
|---|---|---|---|
| `segmentSizeMiB` | `50` | 1–1024 | Start a new log file at this size |
| `segmentMinutes` | `5` | 1–120 | Start a new log file at this age |
| `maxPendingRecords` | `200000` | 1000–5000000 | Records kept in memory for the writer; extra records are dropped and counted in a `LOG_RECORDS_DROPPED` line |
| `slowTickMs` | `500` | 100–10000 | Server tick time that triggers a `SLOW_TICK` stack trace |
| `slowTickStackFrames` | `25` | 5–200 | Stack frames written per `SLOW_TICK` |
| `memoryWarnPercent` | `95` | 50–99 | Heap percentage that triggers `MEMORY_WARNING` |
| `entityHotspotThreshold` | `100` | 10–5000 | Entities in one chunk that trigger `ENTITY_HOTSPOT` |
| `analyzeCrashes` | `true` | | Analyse crash files at startup |
| `logLinesInAnalysis` | `150` | 0–2000 | Log lines added to each crash analysis |

## Things to know
- Logs of previous sessions are deleted once, when the game starts (after the crash analysis has used them). Copy a log you want to keep before launching again.
- The analysis is a best guess from the stack trace. Treat `LOW` and `MEDIUM` results as a lead, not a verdict.
- The log contains player names, coordinates and full inventories (including item NBT). Do not post it publicly without checking it first.
- A busy modpack produces a lot of data. Lower `segmentSizeMiB` or clear the folder if disk space matters.
- In single player, the paused game does not trigger false stall reports.

## Installation
1. Install Minecraft 1.20.1 with Forge 47.x.
2. Put the mod jar in your `mods` folder.

## Building from source
```
./gradlew build
```
Requires JDK 17. The jar is written to `build/libs/`. The `Build` workflow in `.github/workflows` does the same on GitHub.

## Contributing
See [CONTRIBUTING.md](CONTRIBUTING.md).

## License
[MIT](LICENSE), by Yaseen.
