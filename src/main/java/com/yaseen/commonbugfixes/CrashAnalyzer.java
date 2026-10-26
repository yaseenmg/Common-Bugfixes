package com.yaseen.commonbugfixes;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads Minecraft crash reports and JVM crash files (hs_err_pid*.log) left by the previous run and
 * writes a short, human-readable analysis: the most likely mod, the reason, and what happened in the
 * last seconds before the end according to this mod's own session log.
 *
 * Only plain Java is used here, so it can be tested without Minecraft.
 */
final class CrashAnalyzer {
    /** Analysis output: a one-line headline plus the report lines. */
    static final class Result {
        final String headline;
        final List<String> lines;

        Result(String headline, List<String> lines) {
            this.headline = headline;
            this.lines = lines;
        }
    }

    /** Lines of the newest analysis, for the /commonbugfixes lastcrash command. */
    static volatile List<String> lastAnalysisLines = Collections.emptyList();
    /** One line for the new session log, set when a crash was analysed at startup. */
    static volatile String startupNote;

    private static final Pattern FRAME = Pattern.compile(
            "^\\s*at\\s+([^\\s(]+)\\(([^)]*)\\)(?:\\s+[~|?]?\\[([^\\]]*)\\])?.*$");
    private static final Pattern MEMORY = Pattern.compile(
            "Memory:\\s*\\d+ bytes \\((\\d+) MiB\\) / \\d+ bytes \\((\\d+) MiB\\) up to \\d+ bytes \\((\\d+) MiB\\)");
    private static final long MAX_REPORT_BYTES = 5L * 1024 * 1024;
    private static final long MAX_AGE_MS = 30L * 24 * 3600 * 1000;
    private static final long LOG_MATCH_WINDOW_MS = 180_000L;

    private static final String[] LIBRARY_PREFIXES = {
            "client-", "server-", "forge-", "fmlcore", "javafmllanguage", "lowcodelanguage", "mclanguage",
            "modlauncher", "securejarhandler", "bootstraplauncher", "mixin-", "asm-", "guava", "gson", "netty",
            "log4j", "authlib", "brigadier", "datafixerupper", "joml", "lwjgl", "jopt", "jna", "commons-",
            "oshi", "icu4j", "fastutil", "slf4j", "jackson", "eventbus", "coremods", "unsafe", "jarjar",
            "accesstransformers", "mergetool", "terminalconsole", "jline", "nashorn"};

    private static final class Hint {
        final Pattern pattern;
        final boolean environment;
        final String text;

        Hint(String regex, boolean environment, String text) {
            this.pattern = Pattern.compile(regex);
            this.environment = environment;
            this.text = text;
        }
    }

    private static final List<Hint> HINTS = List.of(
            new Hint("outofmemoryerror|java heap space|gc overhead limit", true,
                    "The game ran out of memory. Give Minecraft more RAM (the -Xmx launcher setting), remove heavy mods, or reduce render/simulation distance. Check MEMORY_WARNING lines in the session log."),
            new Hint("stackoverflowerror", false,
                    "Endless recursion inside a mod. The mod on top of the stack is the one to report."),
            new Hint("unsupportedclassversionerror", true,
                    "A mod was built for a newer Java than the one running the game. Minecraft 1.20.1 needs Java 17."),
            new Hint("nosuchmethoderror|nosuchfielderror|noclassdeffounderror|classnotfoundexception|abstractmethoderror|incompatibleclasschangeerror", false,
                    "A mod expects another mod or library version than the installed one. Check that every mod is built for Minecraft 1.20.1 and that its required libraries/dependencies have the right version."),
            new Hint("invalidinjectionexception|mixinapplyerror|mixintransformererror|critical injection failure|mixin apply failed|mixin transformation", false,
                    "A Mixin failed to apply. Two mods usually patch the same code, or one mod does not match this game version. Update both, or remove one of them."),
            new Hint("concurrentmodificationexception", false,
                    "A mod changed a list while another part of the game was reading it (thread-safety bug in that mod)."),
            new Hint("ticking entity", false,
                    "An entity crashed while ticking. The entity type and position are listed under CONTEXT. Remove that entity (for example with /kill) or teleport away and delete it from the world before loading the chunk."),
            new Hint("ticking block entity", false,
                    "A block entity (machine, chest, tile) crashed while ticking. Its position is listed under CONTEXT. Remove that block."),
            new Hint("rendering entity|rendering block entity|rendering screen|tesselating block model", false,
                    "The crash happened while drawing. A resource pack, shader, or a mod's renderer is the usual cause."),
            new Hint("exception generating new chunk|feature placement|worldgen|exception ticking world", false,
                    "World generation or world ticking failed. A worldgen mod or biome mod conflicts with another. Try a new world without the suspected mod."),
            new Hint("watching server|server watchdog|single server tick took", false,
                    "The server froze for too long and the watchdog stopped it. Look for THREAD_STALL / SLOW_TICK stack traces in the session log; they show what the server thread was doing."),
            new Hint("opengl|glfw error|pixel format not accepted|failed to create window|exception_access_violation|failed to initialize", true,
                    "Graphics problem. Update the graphics driver, disable shaders/overlays and try again."),
            new Hint("duplicate|registry object not present|registry.*missing|id map|missing registry", false,
                    "Registry problem: two mods register the same thing, or a mod was removed from an existing world. Check the mod list."));

    private CrashAnalyzer() {}

    // ------------------------------------------------------------------ startup entry point

    /** Scans for crash files left by the previous run, writes analyses, and fills the static summary fields. */
    static void runStartup(Path gameDir, int logLines) {
        try {
            Path logDir = gameDir.resolve(DiagnosticsLog.LOG_FOLDER).resolve("logs");
            Path outDir = gameDir.resolve(DiagnosticsLog.LOG_FOLDER).resolve("crash-analysis");
            Path previousLog = newest(logDir, "session-", ".txt");
            List<String> tail = previousLog == null ? List.of() : tail(previousLog, Math.max(0, logLines));
            long previousLogTime = previousLog == null ? 0L : mtime(previousLog);
            boolean cleanEnd = false;
            for (String line : tail) {
                if (line.contains("SESSION_END")) cleanEnd = true;
            }

            boolean explained = false; // a crash file matches the end of the previous session log
            long now = System.currentTimeMillis();
            Path crash = newest(gameDir.resolve("crash-reports"), "crash-", ".txt");
            Path hs = newest(gameDir, "hs_err_pid", ".log");
            for (Path source : new Path[] {crash, hs}) {
                if (source == null || now - mtime(source) > MAX_AGE_MS) continue;
                Path out = outDir.resolve("analysis-" + stripExtension(source.getFileName().toString()) + ".txt");
                boolean relatedLog = previousLog != null && Math.abs(mtime(source) - previousLogTime) <= LOG_MATCH_WINDOW_MS;
                if (relatedLog) explained = true;
                if (Files.exists(out)) continue;
                String text = readText(source);
                Result result = source == crash ? analyzeCrashReport(text) : analyzeHsErr(text);
                List<String> lines = new ArrayList<>(result.lines);
                if (relatedLog) lines.addAll(logSections(tail, previousLog.getFileName().toString()));
                lines.add(0, "Source: " + source.getFileName());
                write(out, lines);
                startupNote = "PREVIOUS_CRASH_ANALYSIS file=" + DiagnosticsLog.clean(out.getFileName().toString())
                        + " headline=" + DiagnosticsLog.clean(result.headline);
            }

            if (!explained && previousLog != null && !cleanEnd && !tail.isEmpty()) {
                Path out = outDir.resolve("unclean-exit-" + stripExtension(previousLog.getFileName().toString()) + ".txt");
                if (!Files.exists(out)) {
                    List<String> lines = new ArrayList<>();
                    lines.add("PREVIOUS SESSION ENDED WITHOUT A CLEAN END MARKER");
                    lines.add("The last session log has no SESSION_END line and no crash report matches it.");
                    lines.add("This usually means the game was killed (Task Manager, power loss, out of memory killer) or the JVM died.");
                    lines.add("");
                    lines.addAll(logSections(tail, previousLog.getFileName().toString()));
                    write(out, lines);
                    startupNote = "PREVIOUS_CRASH_ANALYSIS file=" + DiagnosticsLog.clean(out.getFileName().toString())
                            + " headline=unclean_exit_no_end_marker";
                }
            }

            Path latest = newest(outDir, "", ".txt");
            if (latest != null) lastAnalysisLines = List.of(readText(latest).split("\\R"));
        } catch (Throwable t) {
            System.err.println("[Common Bugfixes] Crash analysis failed: " + t);
        }
    }

    // ------------------------------------------------------------------ crash report

    static Result analyzeCrashReport(String report) {
        List<String> all = List.of(report.split("\\R", -1));
        String time = valueOf(all, "Time");
        String description = valueOf(all, "Description");

        int descIndex = -1;
        int endIndex = all.size();
        for (int i = 0; i < all.size(); i++) {
            String line = all.get(i);
            if (descIndex < 0 && line.startsWith("Description:")) descIndex = i;
            if (line.startsWith("A detailed walkthrough")) {
                endIndex = i;
                break;
            }
        }
        List<String> exception = descIndex < 0 ? List.of() : all.subList(descIndex + 1, endIndex);

        // split into the main exception and its "Caused by:" chain
        List<List<String>> segments = new ArrayList<>();
        List<String> heads = new ArrayList<>();
        List<String> current = null;
        for (String raw : exception) {
            String line = raw.stripTrailing();
            if (line.isBlank()) continue;
            boolean frame = line.stripLeading().startsWith("at ");
            boolean nested = line.stripLeading().startsWith("... ") || line.startsWith("\tSuppressed:");
            if (!frame && !nested && (current == null || line.startsWith("Caused by:"))) {
                current = new ArrayList<>();
                segments.add(current);
                heads.add(line.startsWith("Caused by:") ? line.substring("Caused by:".length()).trim() : line.trim());
            } else if (current != null && frame) {
                current.add(line);
            }
        }
        String topHead = heads.isEmpty() ? "unknown" : heads.get(0);
        String rootHead = heads.isEmpty() ? "unknown" : heads.get(heads.size() - 1);
        List<String> rootFrames = segments.isEmpty() ? List.of() : segments.get(segments.size() - 1);
        if (rootFrames.isEmpty() && !segments.isEmpty()) rootFrames = segments.get(0);

        Map<String, String[]> mods = parseModTable(all);

        // walk the top frames of the root cause
        Map<String, Integer> scores = new LinkedHashMap<>();
        List<String> orderOfAppearance = new ArrayList<>();
        String firstModJar = null;
        String firstModFrame = null;
        for (int i = 0; i < rootFrames.size() && i < 40; i++) {
            Matcher m = FRAME.matcher(rootFrames.get(i));
            if (!m.matches()) continue;
            String cls = m.group(1).replaceFirst("^[A-Za-z_-]+/[^/]+@[^/]*/", "");
            String jar = jarOf(m.group(3));
            if (jar == null || isJdk(cls)) continue;
            String[] info = mods.get(jar.toLowerCase(Locale.ROOT));
            String id = info == null ? null : info[1];
            if (isLibrary(jar, id)) continue;
            if (firstModJar == null) {
                firstModJar = jar;
                firstModFrame = cls + " (" + m.group(2) + ")";
            }
            scores.merge(jar, 40 - i, Integer::sum);
            if (!orderOfAppearance.contains(jar)) orderOfAppearance.add(jar);
        }
        String bestJar = null;
        int bestScore = -1;
        for (Map.Entry<String, Integer> e : scores.entrySet()) {
            if (e.getValue() > bestScore) {
                bestScore = e.getValue();
                bestJar = e.getKey();
            }
        }

        String suspected = valueOf(all, "Suspected Mods");
        String headText = (description + " " + String.join(" ", heads)).toLowerCase(Locale.ROOT);
        List<Hint> matched = new ArrayList<>();
        for (Hint hint : HINTS) {
            if (hint.pattern.matcher(headText).find()) matched.add(hint);
        }
        boolean environment = false;
        for (Hint hint : matched) environment |= hint.environment;

        // verdict
        String likely = null;
        String likelyId = null;
        String confidence = "LOW";
        String primaryJar = firstModJar != null ? firstModJar : bestJar;
        if (primaryJar != null) {
            String[] info = mods.get(primaryJar.toLowerCase(Locale.ROOT));
            likely = info == null ? primaryJar : info[0] + " (" + info[1] + ")";
            likelyId = info == null ? null : info[1];
            boolean suspectedHasIt = likelyId != null && suspected.toLowerCase(Locale.ROOT).contains("(" + likelyId.toLowerCase(Locale.ROOT) + ")");
            confidence = suspectedHasIt ? "HIGH" : "MEDIUM";
        } else if (!suspected.isBlank() && !suspected.equalsIgnoreCase("NONE")) {
            likely = suspected;
            confidence = "MEDIUM";
        }
        if (environment) confidence = "ENVIRONMENT";

        String memoryNote = null;
        Matcher mem = MEMORY.matcher(report);
        if (mem.find()) {
            long used = Long.parseLong(mem.group(1));
            long max = Long.parseLong(mem.group(3));
            if (max > 0 && used * 100 / max >= 90) {
                memoryNote = "Memory was almost full when the game crashed: " + used + " MiB of " + max + " MiB.";
            }
        }

        List<String> out = new ArrayList<>();
        String shortRoot = shortException(rootHead);
        String headline = shortRoot + " | " + (environment ? "environment problem" : likely == null ? "no mod identified" : likely)
                + " | confidence " + confidence;
        out.add("CRASH ANALYSIS");
        out.add("Headline: " + headline);
        out.add("");
        out.add("SUMMARY");
        out.add("  Time:        " + (time.isBlank() ? "unknown" : time));
        out.add("  Description: " + (description.isBlank() ? "unknown" : description));
        out.add("  Exception:   " + topHead);
        if (heads.size() > 1) out.add("  Root cause:  " + rootHead);
        String thread = valueOf(all, "Thread");
        if (!thread.isBlank()) out.add("  Thread:      " + thread);
        out.add("");
        out.add("MOST LIKELY CAUSE");
        if (environment) {
            out.add("  Not a mod bug: see the advice below.");
        } else if (likely != null) {
            out.add("  " + likely + "   [confidence: " + confidence + "]");
            if (firstModFrame != null) out.add("  First mod code on the stack: " + firstModFrame);
        } else {
            out.add("  No mod could be identified from the stack trace.");
        }
        if (!suspected.isBlank()) out.add("  Forge suspected mods: " + suspected);
        if (orderOfAppearance.size() > 1) {
            List<String> names = new ArrayList<>();
            for (String jar : orderOfAppearance) {
                String[] info = mods.get(jar.toLowerCase(Locale.ROOT));
                names.add(info == null ? jar : info[0] + " (" + info[1] + ")");
            }
            out.add("  Mods on the stack, top first: " + String.join(", ", names));
        }
        if (!matched.isEmpty() || memoryNote != null) {
            out.add("");
            out.add("ADVICE");
            for (Hint hint : matched) out.add("  - " + hint.text);
            if (memoryNote != null) out.add("  - " + memoryNote + " Give the game more RAM or reduce load.");
        }

        List<String> context = new ArrayList<>();
        addSection(context, all, "Entity being ticked", 8);
        addSection(context, all, "Block entity being ticked", 8);
        addSection(context, all, "Entity being rendered", 8);
        addSection(context, all, "Affected level", 6);
        if (!context.isEmpty()) {
            out.add("");
            out.add("CONTEXT");
            out.addAll(context);
        }

        out.add("");
        out.add("SYSTEM");
        for (String key : new String[] {"Minecraft Version", "Operating System", "Java Version", "Memory", "JVM Flags"}) {
            String v = valueOf(all, key);
            if (!v.isBlank()) out.add("  " + key + ": " + v);
        }
        out.add("");
        out.add("TOP OF THE STACK");
        for (int i = 0; i < rootFrames.size() && i < 12; i++) out.add("  " + rootFrames.get(i).strip());
        return new Result(headline, out);
    }

    // ------------------------------------------------------------------ JVM fatal error file

    static Result analyzeHsErr(String text) {
        List<String> all = List.of(text.split("\\R", -1));
        List<String> header = new ArrayList<>();
        for (int i = 0; i < all.size() && i < 60; i++) {
            String line = all.get(i);
            if (line.startsWith("#")) header.add(line);
        }
        String problem = "";
        for (int i = 0; i < header.size(); i++) {
            if (header.get(i).contains("Problematic frame")) {
                if (i + 1 < header.size()) problem = header.get(i + 1).replaceFirst("^#\\s*", "").trim();
                break;
            }
        }
        String lower = String.join(" ", header).toLowerCase(Locale.ROOT);
        String advice;
        if (problem.toLowerCase(Locale.ROOT).matches(".*(nvoglv|nvopencl|nvwgf|atio|aticfx|atioglxx|amdxx|ig\\d+icd|igd|igdumd|opengl32|libgl|nvidia|amdgpu|mesa).*")) {
            advice = "The crash happened inside the graphics driver. Update (or cleanly reinstall) the graphics driver, and try without shaders.";
        } else if (lower.contains("insufficient memory") || lower.contains("native memory") || lower.contains("out of memory")) {
            advice = "The system ran out of memory. Close other programs, lower the game's RAM setting (-Xmx), or increase the page file / swap.";
        } else if (problem.toLowerCase(Locale.ROOT).contains("jvm") || problem.toLowerCase(Locale.ROOT).contains("libjvm")) {
            advice = "The Java Virtual Machine itself crashed. Try a different Java 17 build (for example Temurin) and remove Java agents or overlay software.";
        } else {
            advice = "The Java process died natively. Look at the problematic frame below; a native library name tells which program caused it. Overlays, antivirus and graphics drivers are common causes.";
        }
        List<String> out = new ArrayList<>();
        String headline = "JVM fatal error | " + (problem.isBlank() ? "unknown frame" : problem);
        out.add("CRASH ANALYSIS (JVM fatal error)");
        out.add("Headline: " + headline);
        out.add("");
        out.add("ADVICE");
        out.add("  - " + advice);
        out.add("");
        out.add("JVM REPORT HEADER");
        for (String line : header) out.add("  " + line);
        return new Result(headline, out);
    }

    // ------------------------------------------------------------------ session log extracts

    private static List<String> logSections(List<String> tail, String logName) {
        List<String> out = new ArrayList<>();
        List<String> notable = new ArrayList<>();
        String lastHeartbeat = null;
        for (String line : tail) {
            if (line.contains("SERVER_HEARTBEAT") || line.contains("CLIENT_HEARTBEAT")) lastHeartbeat = line;
            if (line.contains("THREAD_STALL ") || line.contains("SLOW_TICK ") || line.contains("MEMORY_WARNING")
                    || line.contains("UNCAUGHT_EXCEPTION ") || line.contains("LOG_RECORDS_DROPPED")
                    || line.contains("SERVER_HITCH severity=CRITICAL") || line.contains("SERVER_HITCH severity=HIGH")) {
                notable.add(line);
            }
        }
        out.add("");
        out.add("WARNINGS IN THE LAST PART OF THE PREVIOUS SESSION (" + logName + ")");
        if (notable.isEmpty()) out.add("  none");
        for (int i = Math.max(0, notable.size() - 12); i < notable.size(); i++) out.add("  " + notable.get(i));
        if (lastHeartbeat != null) {
            out.add("");
            out.add("LAST HEARTBEAT");
            out.add("  " + lastHeartbeat);
        }
        out.add("");
        out.add("LAST " + tail.size() + " LOG LINES");
        for (String line : tail) out.add(line);
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private static void addSection(List<String> out, List<String> all, String name, int maxLines) {
        String header = "-- " + name + " --";
        for (int i = 0; i < all.size(); i++) {
            if (!all.get(i).trim().equals(header)) continue;
            out.add("  " + name + ":");
            int added = 0;
            for (int j = i + 1; j < all.size() && added < maxLines; j++) {
                String line = all.get(j).trim();
                if (line.startsWith("-- ") || line.startsWith("Stacktrace:")) break;
                if (line.isEmpty() || line.equals("Details:")) continue;
                out.add("    " + line);
                added++;
            }
            return;
        }
    }

    private static Map<String, String[]> parseModTable(List<String> all) {
        Map<String, String[]> mods = new HashMap<>();
        int start = -1;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).contains("Mod List:")) {
                start = i;
                break;
            }
        }
        if (start < 0) return mods;
        boolean sawRow = false;
        for (int i = start + 1; i < all.size(); i++) {
            String line = all.get(i);
            if (!line.contains("|")) {
                if (sawRow || (!line.isBlank() && !Character.isWhitespace(line.charAt(0)))) break;
                continue;
            }
            String[] cols = line.split("\\|");
            if (cols.length < 3) continue;
            String file = cols[0].trim();
            if (!file.toLowerCase(Locale.ROOT).endsWith(".jar")) continue;
            sawRow = true;
            mods.put(file.toLowerCase(Locale.ROOT), new String[] {cols[1].trim(), cols[2].trim()});
        }
        return mods;
    }

    private static String jarOf(String bracket) {
        if (bracket == null) return null;
        String jar = bracket.split("[%!:]")[0].trim();
        return jar.toLowerCase(Locale.ROOT).endsWith(".jar") ? jar : null;
    }

    private static boolean isJdk(String cls) {
        return cls.startsWith("java.") || cls.startsWith("jdk.") || cls.startsWith("sun.") || cls.startsWith("javax.")
                || cls.contains("/java.") || cls.contains("/jdk.") || cls.contains("/sun.");
    }

    private static boolean isLibrary(String jar, String id) {
        if (id != null && (id.equals("minecraft") || id.equals("forge"))) return true;
        String lower = jar.toLowerCase(Locale.ROOT);
        for (String prefix : LIBRARY_PREFIXES) {
            if (lower.startsWith(prefix)) return true;
        }
        return false;
    }

    private static String shortException(String head) {
        String name = head.split("[:\\s]", 2)[0];
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }

    /** Value of the first "Key: value" line, ignoring indentation; empty when missing. */
    private static String valueOf(List<String> lines, String key) {
        String prefix = key + ":";
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith(prefix)) return t.substring(prefix.length()).trim();
        }
        return "";
    }

    private static Path newest(Path dir, String prefix, String suffix) {
        if (!Files.isDirectory(dir)) return null;
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith(prefix) && n.endsWith(suffix) && Files.isRegularFile(p);
                    })
                    .max(Comparator.comparingLong(CrashAnalyzer::mtime))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static long mtime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String readText(Path p) throws IOException {
        long size = Files.size(p);
        if (size > MAX_REPORT_BYTES) {
            try (RandomAccessFile file = new RandomAccessFile(p.toFile(), "r")) {
                byte[] buf = new byte[(int) MAX_REPORT_BYTES];
                file.readFully(buf);
                return new String(buf, StandardCharsets.UTF_8);
            }
        }
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    private static void write(Path out, List<String> lines) throws IOException {
        Files.createDirectories(out.getParent());
        Files.write(out, (String.join(System.lineSeparator(), lines) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
    }

    /** Last {@code lines} lines of a text file, reading only its last 512 KiB. */
    static List<String> tail(Path file, int lines) throws IOException {
        if (lines <= 0) return List.of();
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long length = raf.length();
            int window = (int) Math.min(length, 512L * 1024);
            byte[] buf = new byte[window];
            raf.seek(length - window);
            raf.readFully(buf);
            String text = new String(buf, StandardCharsets.UTF_8);
            List<String> all = new ArrayList<>(List.of(text.split("\\R")));
            if (length > window && !all.isEmpty()) all.remove(0); // first line is probably cut in half
            return new ArrayList<>(all.subList(Math.max(0, all.size() - lines), all.size()));
        }
    }
}
