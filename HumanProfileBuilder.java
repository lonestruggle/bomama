package com.combatbot;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Bouwt een {@link HumanProfile} uit een (of meerdere) muis-trace NDJSON-bestand(en)
 * — zie {@link DebugLog#getMouseTraceFilePath()}.
 *
 * <p>Strategie:</p>
 * <ul>
 *   <li>Per gebeurtenistype (move, drag_move, press, release, click) verzamelen we tijds- en
 *       afstands-deltas.</li>
 *   <li>We berekenen <b>p25 / p50 / p75 / p95</b> percentielen, na het filteren van duidelijke
 *       outliers (idle gaps {@literal >} 5s tellen we niet mee in de cadence-statistiek).</li>
 *   <li>De resultaten worden naar {@link HumanProfile#save(HumanProfile)} weggeschreven.</li>
 * </ul>
 */
public final class HumanProfileBuilder {

    /** Beperk geheugen — bij hele grote traces sample we onder de motorkap. */
    private static final int MAX_SAMPLES_PER_METRIC = 200_000;

    private HumanProfileBuilder() {
    }

    /**
     * Resultaat van een build-run: het opgeslagen profiel + een korte status-string.
     */
    public static final class BuildResult {
        public final HumanProfile profile;
        public final String summary;
        public final boolean success;

        public BuildResult(HumanProfile profile, String summary, boolean success) {
            this.profile = profile;
            this.summary = summary;
            this.success = success;
        }
    }

    /**
     * Bouwt het profiel uit het muis-trace bestand van vandaag (zelfde naamconventie als
     * {@link DebugLog#getMouseTraceFilePath()}). Schrijft direct naar
     * {@link HumanProfile#getProfileFile()}.
     */
    public static BuildResult buildAndSaveFromTodayTrace() {
        File f = DebugLog.getMouseTraceFilePath();
        if (f == null || !f.exists() || f.length() == 0) {
            // Probeer de meest recente .jsonl in dezelfde map als fallback
            File parent = (f != null) ? f.getParentFile() : null;
            File alt = findMostRecentMouseTraceFile(parent);
            if (alt != null) {
                return buildAndSaveFromFile(alt);
            }
            return new BuildResult(null,
                    "Geen muis-trace bestand gevonden. Zet 'Record muis trace' aan, beweeg/klik even, en probeer opnieuw.",
                    false);
        }
        return buildAndSaveFromFile(f);
    }

    /**
     * Build-pipeline voor een specifiek bestand. Scant regel-voor-regel zodat ook bestanden van
     * tientallen MB werken zonder OOM.
     */
    public static BuildResult buildAndSaveFromFile(File traceFile) {
        if (traceFile == null || !traceFile.exists()) {
            return new BuildResult(null, "Bestand niet gevonden: " + traceFile, false);
        }
        Gson gson = new Gson();

        List<Double> moveDtMs = new ArrayList<>();
        List<Double> moveStepPx = new ArrayList<>();
        List<Double> moveSpeedPxPerMs = new ArrayList<>();
        List<Double> dragDurationMs = new ArrayList<>();
        List<Double> pressReleaseDwellMs = new ArrayList<>();
        List<Double> interClickGapMs = new ArrayList<>();

        long totalEvents = 0;

        long lastMoveT = -1;
        int lastMoveX = Integer.MIN_VALUE;
        int lastMoveY = Integer.MIN_VALUE;
        long pressT = -1;
        long lastPressT = -1;

        try (BufferedReader br = new BufferedReader(new FileReader(traceFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                JsonObject ev;
                try {
                    ev = gson.fromJson(line, JsonObject.class);
                } catch (Throwable ignored) {
                    continue;
                }
                if (ev == null) {
                    continue;
                }
                totalEvents++;

                String type = ev.has("type") ? ev.get("type").getAsString() : "";
                long t = ev.has("t") ? ev.get("t").getAsLong() : -1;
                int x = ev.has("x") ? ev.get("x").getAsInt() : Integer.MIN_VALUE;
                int y = ev.has("y") ? ev.get("y").getAsInt() : Integer.MIN_VALUE;

                if ("move".equals(type) || "drag_move".equals(type)) {
                    if (lastMoveT > 0 && t > lastMoveT && lastMoveX != Integer.MIN_VALUE) {
                        long dt = t - lastMoveT;
                        if (dt > 0 && dt < 5_000) {
                            int dx = Math.abs(x - lastMoveX);
                            int dy = Math.abs(y - lastMoveY);
                            double dist = Math.hypot(dx, dy);
                            if (dist <= 800) {
                                addSample(moveDtMs, (double) dt);
                                addSample(moveStepPx, Math.max(dx, dy));
                                if (dt > 0) {
                                    addSample(moveSpeedPxPerMs, dist / (double) dt);
                                }
                            }
                        }
                    }
                    lastMoveT = t;
                    lastMoveX = x;
                    lastMoveY = y;
                } else if ("press".equals(type)) {
                    if (lastPressT > 0 && t > lastPressT) {
                        long gap = t - lastPressT;
                        if (gap > 0 && gap < 60_000) {
                            addSample(interClickGapMs, (double) gap);
                        }
                    }
                    pressT = t;
                    lastPressT = t;
                } else if ("release".equals(type)) {
                    if (pressT > 0 && t >= pressT) {
                        long dwell = t - pressT;
                        if (dwell >= 0 && dwell < 5_000) {
                            addSample(pressReleaseDwellMs, (double) dwell);
                            addSample(dragDurationMs, (double) dwell);
                        }
                        pressT = -1;
                    }
                }
            }
        } catch (IOException ex) {
            return new BuildResult(null, "Lezen mislukt: " + ex.getMessage(), false);
        }

        if (totalEvents == 0) {
            return new BuildResult(null, "Bestand bevat geen events.", false);
        }

        HumanProfile profile = new HumanProfile();
        profile.version = HumanProfile.VERSION;
        profile.generatedAtMs = System.currentTimeMillis();
        profile.sourceFile = traceFile.getName();
        profile.totalEvents = totalEvents;
        long usedSamples = (long) (moveDtMs.size() + moveStepPx.size() + moveSpeedPxPerMs.size()
                + dragDurationMs.size() + pressReleaseDwellMs.size() + interClickGapMs.size());
        profile.usedSamples = usedSamples;

        profile.moveDtMs = percentilesOf(moveDtMs);
        profile.moveStepPx = percentilesOf(moveStepPx);
        profile.moveSpeedPxPerMs = percentilesOf(moveSpeedPxPerMs);
        profile.dragDurationMs = percentilesOf(dragDurationMs);
        profile.pressReleaseDwellMs = percentilesOf(pressReleaseDwellMs);
        profile.interClickGapMs = percentilesOf(interClickGapMs);

        try {
            HumanProfile.save(profile);
        } catch (IOException ex) {
            return new BuildResult(null, "Opslaan mislukt: " + ex.getMessage(), false);
        }
        HumanProfile.invalidateCache();

        String summary = "Profiel opgeslagen: " + HumanProfile.getProfileFile().getName()
                + "\nBron: " + traceFile.getName() + " (" + totalEvents + " events, " + usedSamples + " samples)\n\n"
                + profile.summaryText();
        return new BuildResult(profile, summary, true);
    }

    /** Reservoir-sampling om geheugen te begrenzen op echt grote bestanden. */
    private static void addSample(List<Double> list, double v) {
        if (list.size() < MAX_SAMPLES_PER_METRIC) {
            list.add(v);
        } else {
            // simpele drop-policy: als we vol zitten, vervang willekeurige slot in 1/N kans
            int idx = (int) (Math.random() * MAX_SAMPLES_PER_METRIC);
            if (idx >= 0 && idx < MAX_SAMPLES_PER_METRIC) {
                list.set(idx, v);
            }
        }
    }

    private static HumanProfile.Percentiles percentilesOf(List<Double> raw) {
        if (raw == null || raw.size() < 8) {
            return null;
        }
        List<Double> values = new ArrayList<>(raw);
        Collections.sort(values);
        return new HumanProfile.Percentiles(
                percentile(values, 25),
                percentile(values, 50),
                percentile(values, 75),
                percentile(values, 95)
        );
    }

    private static double percentile(List<Double> sorted, double pct) {
        if (sorted == null || sorted.isEmpty()) {
            return 0.0;
        }
        double k = (sorted.size() - 1) * (pct / 100.0);
        int lo = (int) Math.floor(k);
        int hi = (int) Math.ceil(k);
        if (lo == hi) {
            return sorted.get(lo);
        }
        double frac = k - lo;
        return sorted.get(lo) * (1.0 - frac) + sorted.get(hi) * frac;
    }

    /**
     * Zoekt — wanneer het bestand van vandaag ontbreekt — het meest recente
     * {@code combat-bot-mouse-trace-*.jsonl} in de prive-logs map.
     */
    private static File findMostRecentMouseTraceFile(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return null;
        }
        File[] files = dir.listFiles((d, name) ->
                name != null && name.startsWith("combat-bot-mouse-trace-") && name.endsWith(".jsonl"));
        if (files == null || files.length == 0) {
            return null;
        }
        File best = files[0];
        for (File f : files) {
            if (f.lastModified() > best.lastModified()) {
                best = f;
            }
        }
        return (best != null && best.length() > 0) ? best : null;
    }
}
