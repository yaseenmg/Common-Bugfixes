package com.yaseen.commonbugfixes;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Asynchronous plain-text logger. Records are queued by the game threads and written by a
 * dedicated daemon thread, so logging never blocks the game. Output is split into numbered
 * segments ("-part001.txt", "-part002.txt", ...) rotated by size or age (both set in the config file).
 */
final class DiagnosticsLog {
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    static final String LOG_FOLDER = "Common Bugfixes";
    private static final String LEGACY_FOLDER = "Everything in this world";
    private static final LogItem STOP = new LogItem(null, true);

    private static volatile Session activeSession;
    private static volatile Path currentFile;
    private static boolean startupCleanupDone;

    private DiagnosticsLog() {}

    static synchronized void start(Path gameDirectory, String source) {
        Session existing = activeSession;
        if (existing != null) {
            log("SESSION_ATTACH source=" + clean(source) + " file=" + currentFile);
            return;
        }
        try {
            Path dir = gameDirectory.resolve(LOG_FOLDER).resolve("logs");
            Files.createDirectories(dir);

            String cleanupSummary;
            if (!startupCleanupDone) {
                startupCleanupDone = true;
                Path legacyDir = gameDirectory.resolve(LEGACY_FOLDER).resolve("logs");
                Path legacyOldDir = gameDirectory.resolve("logs").resolve("warium-diagnostics");
                cleanupSummary = "OLD_LOG_CLEANUP startup_once=true "
                        + deletePreviousSessionLogs(dir, "current") + " "
                        + deletePreviousSessionLogs(legacyDir, "legacy") + " "
                        + deletePreviousSessionLogs(legacyOldDir, "legacy_old")
                        + " current_dir=Common Bugfixes/logs";
            } else {
                cleanupSummary = "OLD_LOG_CLEANUP skipped=already_run_this_process=true";
            }

            String prefix = "session-" + LocalDateTime.now().format(FILE_TIME);
            Session session = new Session(dir, prefix);
            currentFile = session.segmentPath(1);
            activeSession = session;
            session.writerThread = new Thread(() -> writerLoop(session), "CommonBugfixes-LogWriter");
            session.writerThread.setDaemon(true);
            session.writerThread.start();

            log("SESSION_START version=" + clean(CommonBugfixes.version()) + " source=" + clean(source));
            log("LOG_STORAGE format=plain_txt segment_limit_mib=" + DiagnosticsConfig.segmentBytes() / 1_048_576L
                    + " max_segment_age_min=" + DiagnosticsConfig.segmentAgeNanos() / 60_000_000_000L
                    + " max_pending_records=" + DiagnosticsConfig.maxPendingRecords()
                    + " startup_cleanup=previous_session_logs_once_per_process=true async_writer=true");
            log("LOG_DIRECTORY path=Common Bugfixes/logs");
            log(cleanupSummary);
            String note = CrashAnalyzer.startupNote;
            if (note != null) {
                log(note);
                CrashAnalyzer.startupNote = null;
            }
            log("FORMAT key=value; UTC ISO-8601 timestamps; entity deltas plus a full keyframe every 30 seconds");
        } catch (Exception e) {
            activeSession = null;
            System.err.println("[Common Bugfixes] Could not create session log: " + e);
        }
    }

    static synchronized void log(String message) {
        Session session = activeSession;
        if (session == null) return;
        if (session.failed) {
            session.failedRecords++;
            if (!session.warnedFailed) {
                session.warnedFailed = true;
                System.err.println("[Common Bugfixes] Logging is unavailable; records are not being saved. Check disk space/permissions. session=" + session.prefix);
            }
            return;
        }
        if (session.queue.size() >= DiagnosticsConfig.maxPendingRecords()) {
            session.dropped.incrementAndGet();
            return;
        }
        String safe = message == null ? "null" : message.replace('\n', ' ').replace('\r', ' ');
        session.queue.offer(new LogItem(Instant.now().toString() + " | " + safe, false));
    }

    static synchronized void stop(String reason) {
        Session session = activeSession;
        if (session == null) return;
        log("SESSION_END reason=" + clean(reason));
        activeSession = null;
        session.queue.offer(STOP);
    }

    /** Stops the log and waits briefly for the writer thread, so the end of the log is on disk. Used by the JVM shutdown hook. */
    static void shutdown(String reason, long waitMs) {
        Session session = activeSession;
        stop(reason);
        if (session != null && session.writerThread != null) {
            try {
                session.writerThread.join(waitMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Writes an exception and its causes as separate lines (message plus up to 40 frames each). */
    static void logThrowable(String label, Throwable error) {
        Throwable t = error;
        for (int depth = 0; t != null && depth < 6; depth++, t = t.getCause()) {
            log(label + " exception=" + clean(t.getClass().getName()) + " message=" + clean(String.valueOf(t.getMessage()))
                    + (depth == 0 ? "" : " caused_by_level=" + depth));
            StackTraceElement[] frames = t.getStackTrace();
            for (int i = 0; i < frames.length && i < 40; i++) {
                log(label + " level=" + depth + " frame=" + i + " at=" + frames[i]);
            }
        }
    }

    private static long lastMemoryCheckNs;
    private static long lastMemoryWarnNs;
    private static int highHeapSeconds;
    private static final long[] GC_HISTORY = new long[10];
    private static int gcHistoryIndex;
    private static int gcHistoryFilled;

    /** Called about once per second: warns when memory is nearly full or the garbage collector is working too much. */
    static synchronized void checkMemory() {
        long now = System.nanoTime();
        if (lastMemoryCheckNs != 0L && now - lastMemoryCheckNs < 900_000_000L) return;
        lastMemoryCheckNs = now;

        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        long gcTime = 0L;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (gc.getCollectionTime() > 0) gcTime += gc.getCollectionTime();
        }
        long gcTenSecondsAgo = gcHistoryFilled >= GC_HISTORY.length ? GC_HISTORY[gcHistoryIndex] : -1L;
        GC_HISTORY[gcHistoryIndex] = gcTime;
        gcHistoryIndex = (gcHistoryIndex + 1) % GC_HISTORY.length;
        if (gcHistoryFilled < GC_HISTORY.length) gcHistoryFilled++;

        long max = heap.getMax();
        double percent = max > 0 ? heap.getUsed() * 100.0 / max : 0.0;
        highHeapSeconds = percent >= DiagnosticsConfig.memoryWarnPercent() ? highHeapSeconds + 1 : 0;
        long gcDelta = gcTenSecondsAgo < 0 ? 0L : gcTime - gcTenSecondsAgo;
        boolean heapProblem = highHeapSeconds >= 10;
        boolean gcProblem = gcDelta >= 2500L;
        if ((heapProblem || gcProblem) && (lastMemoryWarnNs == 0L || now - lastMemoryWarnNs >= 60_000_000_000L)) {
            lastMemoryWarnNs = now;
            log("MEMORY_WARNING reason=" + (heapProblem ? "heap_nearly_full" : "gc_overhead")
                    + " heap_used_mb=" + heap.getUsed() / 1048576L
                    + " heap_max_mb=" + (max < 0 ? -1L : max / 1048576L)
                    + " heap_percent=" + String.format(java.util.Locale.ROOT, "%.1f", percent)
                    + " gc_time_last_10s_ms=" + gcDelta
                    + " advice=increase_xmx_or_reduce_load");
        }
    }

    static boolean isOpen() {
        return activeSession != null;
    }

    static Path currentFile() {
        return currentFile;
    }

    static int pendingCount() {
        Session session = activeSession;
        return session == null ? 0 : session.queue.size();
    }

    /** Deletes old "session-*" log files left by previous runs; returns a summary for the log. */
    private static String deletePreviousSessionLogs(Path directory, String label) {
        int deleted = 0;
        int failures = 0;
        if (!Files.isDirectory(directory)) {
            return label + "_deleted=0 " + label + "_failed=0";
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                boolean sessionLog = name.startsWith("session-")
                        && (name.endsWith(".log") || name.endsWith(".log.gz") || name.endsWith(".txt"));
                if (!sessionLog || !Files.isRegularFile(file)) continue;
                try {
                    if (Files.deleteIfExists(file)) deleted++;
                } catch (IOException e) {
                    failures++;
                    System.err.println("[Common Bugfixes] Could not delete previous log " + file + ": " + e);
                }
            }
        } catch (IOException e) {
            failures++;
            System.err.println("[Common Bugfixes] Could not inspect previous logs in " + directory + ": " + e);
        }
        return label + "_deleted=" + deleted + " " + label + "_failed=" + failures;
    }

    private static void writerLoop(Session session) {
        try (PlainTextSegmentWriter writer = new PlainTextSegmentWriter(session)) {
            boolean stopping = false;
            while (!stopping) {
                LogItem item = session.queue.poll(1, TimeUnit.SECONDS);
                if (item != null) {
                    if (item.stop) stopping = true;
                    else writer.writeLine(item.line);
                }
                for (int drained = 0; !stopping && drained < 8192; drained++) {
                    item = session.queue.poll();
                    if (item == null) break;
                    if (item.stop) stopping = true;
                    else writer.writeLine(item.line);
                }
                long droppedNow = session.dropped.getAndSet(0L);
                if (droppedNow > 0L) {
                    writer.writeLine(Instant.now() + " | LOG_RECORDS_DROPPED count=" + droppedNow + " reason=queue_full");
                }
                writer.flush();
            }
            LogItem item;
            while ((item = session.queue.poll()) != null) {
                if (!item.stop) writer.writeLine(item.line);
            }
            writer.flush();
        } catch (InterruptedException e) {
            session.failed = true;
            Thread.currentThread().interrupt();
            System.err.println("[Common Bugfixes] Log writer interrupted; some queued data may remain for " + session.prefix);
        } catch (IOException e) {
            session.failed = true;
            System.err.println("[Common Bugfixes] Log writer failed for " + session.prefix
                    + "; queued_records=" + session.queue.size()
                    + "; error=" + e
                    + "; subsequent_events_will_not_be_saved=true");
        }
    }

    /** Heap / GC / thread / pending-queue figures appended to periodic heartbeat lines. */
    static String runtimeStats() {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        long gcCount = 0L;
        long gcTime = 0L;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (gc.getCollectionCount() > 0) gcCount += gc.getCollectionCount();
            if (gc.getCollectionTime() > 0) gcTime += gc.getCollectionTime();
        }
        return "heap_used_mb=" + heap.getUsed() / 1048576L
                + " heap_committed_mb=" + heap.getCommitted() / 1048576L
                + " heap_max_mb=" + (heap.getMax() < 0 ? -1L : heap.getMax() / 1048576L)
                + " gc_count=" + gcCount
                + " gc_time_ms=" + gcTime
                + " jvm_threads=" + ManagementFactory.getThreadMXBean().getThreadCount()
                + " pending_log_records=" + pendingCount();
    }

    static String clean(String s) {
        if (s == null) return "unknown";
        return s.replace(' ', '_').replace('\n', '_').replace('\r', '_');
    }

    private static final class Session {
        private final Path directory;
        private final String prefix;
        private final BlockingQueue<LogItem> queue = new LinkedBlockingQueue<>();
        private Thread writerThread;
        private volatile boolean failed;
        private boolean warnedFailed;
        private long failedRecords;
        private final AtomicLong dropped = new AtomicLong();

        private Session(Path directory, String prefix) {
            this.directory = directory;
            this.prefix = prefix;
        }

        private Path segmentPath(int part) {
            return directory.resolve(prefix + "-part" + String.format("%03d", part) + ".txt");
        }
    }

    private static final class LogItem {
        private final String line;
        private final boolean stop;

        private LogItem(String line, boolean stop) {
            this.line = line;
            this.stop = stop;
        }
    }

    private static final class PlainTextSegmentWriter implements AutoCloseable {
        private final Session session;
        private int part = 1;
        private long uncompressedBytes;
        private long partStartedNs;
        private BufferedWriter writer;

        private PlainTextSegmentWriter(Session session) throws IOException {
            this.session = session;
            openPart();
            writeRaw("LOG_SEGMENT_START part=" + part + " path=" + session.segmentPath(part).getFileName());
        }

        private void openPart() throws IOException {
            Path path = session.segmentPath(part);
            writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            currentFile = path;
            uncompressedBytes = 0L;
            partStartedNs = System.nanoTime();
        }

        private void writeLine(String line) throws IOException {
            writeRaw(line);
            uncompressedBytes += line.getBytes(StandardCharsets.UTF_8).length + 1L;
            if (uncompressedBytes >= DiagnosticsConfig.segmentBytes()) {
                rotate("plain_size_limit");
            } else if (System.nanoTime() - partStartedNs >= DiagnosticsConfig.segmentAgeNanos()) {
                rotate("time_limit");
            }
        }

        private void writeRaw(String line) throws IOException {
            writer.write(line);
            writer.newLine();
        }

        private void rotate(String reason) throws IOException {
            int nextPart = part + 1;
            writeRaw(Instant.now() + " | LOG_SEGMENT_ROTATE next_part=" + nextPart + " reason=" + reason);
            writer.flush();
            writer.close();
            part = nextPart;
            openPart();
            writeRaw(Instant.now() + " | LOG_SEGMENT_START part=" + part + " previous_part=" + (part - 1) + " reason=" + reason);
        }

        private void flush() throws IOException {
            writer.flush();
        }

        @Override
        public void close() throws IOException {
            if (writer != null) {
                writer.flush();
                writer.close();
                writer = null;
            }
        }
    }
}
