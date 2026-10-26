package com.yaseen.commonbugfixes;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Daemon monitor that reports when a watched thread has not made progress for too long.
 * When a stall is detected it logs the thread state and its full stack trace.
 */
final class StallWatchdog {
    /** Set by the client each tick: the single-player game is paused, so the integrated server is not ticking. */
    static volatile boolean gamePaused;

    private StallWatchdog() {}

    static Thread start(String monitorName, AtomicBoolean running, LongSupplier lastProgressNanos,
                        Supplier<String> context, Supplier<Thread> watchedThread, long thresholdMs) {
        return start(monitorName, "THREAD_STALL", running, lastProgressNanos, context, watchedThread,
                thresholdMs, Integer.MAX_VALUE);
    }

    /** Same as above with a custom record label and a limit on the number of stack frames written. */
    static Thread start(String monitorName, String label, AtomicBoolean running, LongSupplier lastProgressNanos,
                        Supplier<String> context, Supplier<Thread> watchedThread, long thresholdMs, int maxFrames) {
        final boolean serverMonitor = monitorName.startsWith("server");
        // poll often enough to catch a tick that is only a little longer than the threshold
        final long pollMs = Math.max(50L, Math.min(1000L, thresholdMs / 4L));
        Thread monitor = new Thread(() -> {
            long lastReportNanos = 0L;
            boolean resumeGrace = false;
            while (running.get()) {
                try {
                    Thread.sleep(pollMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                // a paused single-player server does not tick on purpose; skip it, plus a short grace period after resuming
                if (serverMonitor && gamePaused) {
                    resumeGrace = true;
                    continue;
                }
                if (serverMonitor && resumeGrace) {
                    resumeGrace = false;
                    try {
                        Thread.sleep(Math.max(pollMs, 1000L));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }
                long now = System.nanoTime();
                long last = lastProgressNanos.getAsLong();
                long stalledMs = (now - last) / 1_000_000L;
                if (last <= 0L || stalledMs < thresholdMs) continue;
                // do not repeat the same stall report more often than every 5 seconds
                if (lastReportNanos != 0L && now - lastReportNanos < 5_000_000_000L) continue;
                lastReportNanos = now;

                Thread target = watchedThread.get();
                DiagnosticsLog.log(label + " monitor=" + DiagnosticsLog.clean(monitorName)
                        + " stalled_ms=" + stalledMs
                        + " watched_thread=" + (target == null ? "unknown" : DiagnosticsLog.clean(target.getName()))
                        + " thread_state=" + (target == null ? "unknown" : target.getState())
                        + " context=" + DiagnosticsLog.clean(context.get())
                        + " " + DiagnosticsLog.runtimeStats());
                if (target != null) {
                    StackTraceElement[] frames = target.getStackTrace();
                    DiagnosticsLog.log(label + "_STACK_BEGIN monitor=" + DiagnosticsLog.clean(monitorName)
                            + " frame_count=" + frames.length + " frames_written=" + Math.min(frames.length, maxFrames));
                    for (int i = 0; i < frames.length && i < maxFrames; i++) {
                        DiagnosticsLog.log(label + "_STACK monitor=" + DiagnosticsLog.clean(monitorName)
                                + " frame=" + i + " at=" + frames[i]);
                    }
                    DiagnosticsLog.log(label + "_STACK_END monitor=" + DiagnosticsLog.clean(monitorName));
                }
            }
        }, "CommonBugfixes-Watch-" + monitorName);
        monitor.setDaemon(true);
        monitor.setPriority(Thread.MIN_PRIORITY + 3);
        monitor.start();
        return monitor;
    }
}
