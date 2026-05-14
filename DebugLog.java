package com.combatbot;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Shared debug log — verzamelt berichten van alle handlers.
 * <ul>
 *   <li>Combat Bot-zijpaneel → tab <b>🔍 Debug</b> (live tekst)</li>
 *   <li>Bestand: {@code ~/.runelite/prive-logs/combat-bot-debug-YYYY-MM-DD.log}</li>
 *   <li>Daarnaast: elke regel naar <b>stdout</b> (alleen zichtbaar als je RuneLite vanaf een terminal start)</li>
 * </ul>
 */
public class DebugLog {

    private static final int MAX_ENTRIES = 1500;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final List<String> entries = Collections.synchronizedList(new ArrayList<>());
    private static final Set<String> knownSources = Collections.synchronizedSet(new LinkedHashSet<>());
    private static final Set<String> disabledSources = Collections.synchronizedSet(new LinkedHashSet<>());
    /** Standaard aan zodat debug in de combatpanel-console zichtbaar is. */
    private static volatile boolean enabled = true;

    public static void setEnabled(boolean on) {
        enabled = on;
        if (on) {
            log("DEBUG", "Debug logging ingeschakeld");
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setSourceEnabled(String source, boolean enabledSource) {
        String s = normalizeSource(source);
        if (s.isEmpty()) {
            return;
        }
        knownSources.add(s);
        if (enabledSource) {
            disabledSources.remove(s);
        } else {
            disabledSources.add(s);
        }
    }

    public static boolean isSourceEnabled(String source) {
        String s = normalizeSource(source);
        if (s.isEmpty()) {
            return true;
        }
        return !disabledSources.contains(s);
    }

    public static List<String> getKnownSources() {
        synchronized (knownSources) {
            return new ArrayList<>(knownSources);
        }
    }

    /** Zorgt dat een bron in het Debug-paneel verschijnt (checkbox), ook vóór de eerste logregel. */
    public static void registerSourceForPanel(String source) {
        if (source == null || source.isBlank()) {
            return;
        }
        knownSources.add(normalizeSource(source));
    }

    public static String getDisabledSourcesCsv() {
        synchronized (disabledSources) {
            return String.join(",", disabledSources);
        }
    }

    public static void setDisabledSourcesCsv(String csv) {
        disabledSources.clear();
        if (csv == null || csv.trim().isEmpty()) {
            return;
        }
        for (String part : csv.split(",")) {
            String s = normalizeSource(part);
            if (!s.isEmpty()) {
                disabledSources.add(s);
                knownSources.add(s);
            }
        }
    }

    public static void log(String source, String message) {
        if (!enabled) return;
        String srcRaw = source == null ? "" : source.trim();
        String srcKey = normalizeSource(srcRaw);
        if (srcKey.isEmpty()) {
            srcRaw = "DEBUG";
            srcKey = "DEBUG";
        }
        knownSources.add(srcKey);
        if (disabledSources.contains(srcKey)) {
            return;
        }
        String time = LocalTime.now().format(TIME_FMT);
        String entry = "[" + time + "] [" + srcRaw + "] " + message;
        entries.add(entry);
        // Ook stdout (terminal als je RuneLite vanaf console start) — het plugin-Debug-tabblad blijft de hoofdplek.
        System.out.println(entry);
        // Trim als te groot
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(0);
        }
        appendToFile(entry);
    }

    /**
     * Meerdere regels in één blok (één file-append), zelfde bron-filter als {@link #log}.
     * Gebruikt door o.a. widget-inspector zodat grote dumps in het Debug-tabblad verschijnen.
     */
    public static void logBlock(String source, Iterable<String> messages) {
        if (!enabled || messages == null) {
            return;
        }
        String srcRaw = source == null ? "" : source.trim();
        String srcKey = normalizeSource(srcRaw);
        if (srcKey.isEmpty()) {
            srcRaw = "DEBUG";
            srcKey = "DEBUG";
        }
        knownSources.add(srcKey);
        if (disabledSources.contains(srcKey)) {
            return;
        }
        List<String> newEntries = new ArrayList<>();
        for (String msg : messages) {
            if (msg == null) {
                continue;
            }
            String time = LocalTime.now().format(TIME_FMT);
            String entry = "[" + time + "] [" + srcRaw + "] " + msg;
            newEntries.add(entry);
            System.out.println(entry);
        }
        if (newEntries.isEmpty()) {
            return;
        }
        synchronized (entries) {
            entries.addAll(newEntries);
            while (entries.size() > MAX_ENTRIES) {
                entries.remove(0);
            }
        }
        try (FileWriter fw = new FileWriter(getLogFile(), true)) {
            for (String e : newEntries) {
                fw.write(e);
                fw.write(System.lineSeparator());
            }
        } catch (IOException ignored) {
        }
    }

    public static List<String> getEntries() {
        return new ArrayList<>(entries);
    }

    public static void clear() {
        entries.clear();
    }

    public static String getText() {
        StringBuilder sb = new StringBuilder();
        for (String e : getEntries()) {
            sb.append(e).append("\n");
        }
        return sb.toString();
    }

    /** Eén bestand per kalenderdag: {@code combat-bot-debug-YYYY-MM-DD.log}. */
    private static File getLogFile() {
        String userHome = System.getProperty("user.home");
        File rlDir = new File(userHome, ".runelite");
        File logsDir = new File(rlDir, "prive-logs");
        if (!logsDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            logsDir.mkdirs();
        }
        String day = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        return new File(logsDir, "combat-bot-debug-" + day + ".log");
    }

    private static void appendToFile(String line) {
        try (FileWriter fw = new FileWriter(getLogFile(), true)) {
            fw.write(line);
            fw.write(System.lineSeparator());
        } catch (IOException ignored) {
        }
    }

    /**
     * Huidige dag-log ({@code combat-bot-debug-YYYY-MM-DD.log}); na middernacht wijst dit naar het nieuwe bestand.
     */
    public static File getLogFilePath() {
        return getLogFile();
    }

    /**
     * Één JSON-object per regel (NDJSON) voor ML — zelfde map als de debug-log:
     * {@code ~/.runelite/prive-logs/combat-bot-ml-clicks-YYYY-MM-DD.jsonl}.
     * Wordt altijd weggeschreven (ongeacht {@link #enabled}); fouten worden genegeerd.
     */
    public static void appendMlClicksJsonLine(String jsonLine) {
        if (jsonLine == null || jsonLine.isEmpty()) {
            return;
        }
        synchronized (DebugLog.class) {
            try (FileWriter fw = new FileWriter(getMlClicksFile(), true)) {
                fw.write(jsonLine);
                fw.write(System.lineSeparator());
            } catch (IOException ignored) {
            }
        }
    }

    public static File getMlClicksFilePath() {
        return getMlClicksFile();
    }

    /**
     * Één JSON-object per regel (NDJSON) voor ruwe muis-traces:
     * {@code ~/.runelite/prive-logs/combat-bot-mouse-trace-YYYY-MM-DD.jsonl}.
     * Wordt gebruikt om menselijke muis-snelheid/beweging/click-drag patronen te analyseren.
     */
    public static void appendMouseTraceJsonLine(String jsonLine) {
        if (jsonLine == null || jsonLine.isEmpty()) {
            return;
        }
        synchronized (DebugLog.class) {
            try (FileWriter fw = new FileWriter(getMouseTraceFile(), true)) {
                fw.write(jsonLine);
                fw.write(System.lineSeparator());
            } catch (IOException ignored) {
            }
        }
    }

    public static File getMouseTraceFilePath() {
        return getMouseTraceFile();
    }

    /**
     * Eén JSON-object per regel (NDJSON) voor walk-tile events:
     * {@code ~/.runelite/prive-logs/combat-bot-walk-tiles-YYYY-MM-DD.jsonl}.
     * Bevat zowel "click"-events ("Walk here" door bot/jezelf) als "traverse"-events
     * (speler liep over een tile). Wordt gebruikt voor offline hotspot-/route-analyse.
     */
    public static void appendWalkTilesJsonLine(String jsonLine) {
        if (jsonLine == null || jsonLine.isEmpty()) {
            return;
        }
        synchronized (DebugLog.class) {
            try (FileWriter fw = new FileWriter(getWalkTilesFile(), true)) {
                fw.write(jsonLine);
                fw.write(System.lineSeparator());
            } catch (IOException ignored) {
            }
        }
    }

    public static File getWalkTilesFilePath() {
        return getWalkTilesFile();
    }

    /**
     * Schrijft een one-shot snapshot-export van de huidige walk-tile-aggregaten naar
     * {@code ~/.runelite/prive-logs/combat-bot-walk-tiles-export-YYYY-MM-DD-HHmmss.jsonl}.
     * Eén regel per tile met alle counters; geschikt voor analyse zonder dat auto-log
     * aan hoeft te staan. Retourneert het pad of {@code null} bij fout.
     */
    public static File exportWalkTilesSnapshot(Iterable<String> jsonLines) {
        if (jsonLines == null) return null;
        String userHome = System.getProperty("user.home");
        File rlDir = new File(userHome, ".runelite");
        File logsDir = new File(rlDir, "prive-logs");
        if (!logsDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            logsDir.mkdirs();
        }
        String stamp = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
                + "-" + LocalTime.now().format(DateTimeFormatter.ofPattern("HHmmss"));
        File out = new File(logsDir, "combat-bot-walk-tiles-export-" + stamp + ".jsonl");
        try (FileWriter fw = new FileWriter(out, false)) {
            for (String line : jsonLines) {
                if (line == null || line.isEmpty()) continue;
                fw.write(line);
                fw.write(System.lineSeparator());
            }
            return out;
        } catch (IOException e) {
            return null;
        }
    }

    private static File getMlClicksFile() {
        String userHome = System.getProperty("user.home");
        File rlDir = new File(userHome, ".runelite");
        File logsDir = new File(rlDir, "prive-logs");
        if (!logsDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            logsDir.mkdirs();
        }
        String day = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        return new File(logsDir, "combat-bot-ml-clicks-" + day + ".jsonl");
    }

    private static File getMouseTraceFile() {
        String userHome = System.getProperty("user.home");
        File rlDir = new File(userHome, ".runelite");
        File logsDir = new File(rlDir, "prive-logs");
        if (!logsDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            logsDir.mkdirs();
        }
        String day = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        return new File(logsDir, "combat-bot-mouse-trace-" + day + ".jsonl");
    }

    private static File getWalkTilesFile() {
        String userHome = System.getProperty("user.home");
        File rlDir = new File(userHome, ".runelite");
        File logsDir = new File(rlDir, "prive-logs");
        if (!logsDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            logsDir.mkdirs();
        }
        String day = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        return new File(logsDir, "combat-bot-walk-tiles-" + day + ".jsonl");
    }

    private static String normalizeSource(String source) {
        return source == null ? "" : source.trim().toUpperCase(Locale.ROOT);
    }
}
