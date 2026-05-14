package com.combatbot;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.Random;

/**
 * "Human profile" — gestructureerde percentielen die de bot gebruikt om timings/bewegingen
 * dichter bij de echte speler te leggen. Wordt opgebouwd door {@link HumanProfileBuilder}
 * uit het muis-trace logbestand ({@link DebugLog#getMouseTraceFilePath()}) en opgeslagen in
 * {@code ~/.runelite/prive-logs/combat-bot-human-profile.json}.
 *
 * <p>Alle waarden zijn optioneel: als er geen profiel beschikbaar is, vallen de callers
 * terug op hun standaard random-ranges. De bot blijft werken zonder profiel.</p>
 */
public final class HumanProfile {

    /** Versie van het profielformaat — nieuwe velden mogen worden toegevoegd zonder bump. */
    public static final int VERSION = 1;

    /** Vier vaste percentielen — eenvoudig genoeg voor JSON, expressief genoeg voor sampling. */
    public static final class Percentiles {
        public double p25;
        public double p50;
        public double p75;
        public double p95;

        public Percentiles() {
        }

        public Percentiles(double p25, double p50, double p75, double p95) {
            this.p25 = p25;
            this.p50 = p50;
            this.p75 = p75;
            this.p95 = p95;
        }

        boolean isUsable() {
            return p25 >= 0 && p50 >= p25 && p75 >= p50 && p95 >= p75 && p95 > 0;
        }
    }

    public int version = VERSION;
    public long generatedAtMs;
    public String sourceFile;
    public long totalEvents;
    public long usedSamples;

    public Percentiles moveStepPx;
    public Percentiles moveDtMs;
    public Percentiles moveSpeedPxPerMs;
    public Percentiles dragDurationMs;
    public Percentiles pressReleaseDwellMs;
    public Percentiles interClickGapMs;

    private static volatile HumanProfile cached;
    private static volatile long cachedAtMs;
    private static final long CACHE_TTL_MS = 30_000L;

    /** Laadt (of hergebruikt cache) het opgeslagen profiel; geeft {@code null} als er geen profiel is. */
    public static HumanProfile getOrLoad() {
        long now = System.currentTimeMillis();
        HumanProfile snap = cached;
        if (snap != null && (now - cachedAtMs) < CACHE_TTL_MS) {
            return snap;
        }
        synchronized (HumanProfile.class) {
            now = System.currentTimeMillis();
            if (cached != null && (now - cachedAtMs) < CACHE_TTL_MS) {
                return cached;
            }
            HumanProfile loaded = loadFromDisk();
            cached = loaded;
            cachedAtMs = now;
            return loaded;
        }
    }

    /** Forceert opnieuw laden bij volgende {@link #getOrLoad()} (bijv. na nieuwe build). */
    public static void invalidateCache() {
        synchronized (HumanProfile.class) {
            cached = null;
            cachedAtMs = 0L;
        }
    }

    /** Schrijf een net opgebouwd profiel naar disk en cache het direct in. */
    public static synchronized void save(HumanProfile profile) throws IOException {
        if (profile == null) {
            return;
        }
        File f = getProfileFile();
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (java.io.FileWriter fw = new java.io.FileWriter(f)) {
            new Gson().toJson(profile, fw);
        }
        cached = profile;
        cachedAtMs = System.currentTimeMillis();
    }

    public static File getProfileFile() {
        String userHome = System.getProperty("user.home");
        File rlDir = new File(userHome, ".runelite");
        File logsDir = new File(rlDir, "prive-logs");
        return new File(logsDir, "combat-bot-human-profile.json");
    }

    private static HumanProfile loadFromDisk() {
        File f = getProfileFile();
        if (!f.exists() || f.length() == 0) {
            return null;
        }
        try (Reader r = new FileReader(f)) {
            HumanProfile p = new Gson().fromJson(r, HumanProfile.class);
            if (p == null) {
                return null;
            }
            return p;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Snapshot voor weergave in de UI (compact, één regel per metriek). */
    public String summaryText() {
        StringBuilder sb = new StringBuilder();
        sb.append("Profiel v").append(version).append(" — ");
        sb.append(totalEvents).append(" events, ").append(usedSamples).append(" samples\n");
        appendPercLine(sb, "moveStepPx",         moveStepPx,         "px");
        appendPercLine(sb, "moveDtMs",           moveDtMs,           "ms");
        appendPercLine(sb, "moveSpeedPxPerMs",   moveSpeedPxPerMs,   "px/ms");
        appendPercLine(sb, "dragDurationMs",     dragDurationMs,     "ms");
        appendPercLine(sb, "pressReleaseDwellMs", pressReleaseDwellMs, "ms");
        appendPercLine(sb, "interClickGapMs",    interClickGapMs,    "ms");
        return sb.toString();
    }

    private static void appendPercLine(StringBuilder sb, String name, Percentiles p, String unit) {
        if (p == null) {
            return;
        }
        sb.append(String.format("  %-22s p25=%.1f%s p50=%.1f%s p75=%.1f%s p95=%.1f%s%n",
                name, p.p25, unit, p.p50, unit, p.p75, unit, p.p95, unit));
    }

    // ===== Sampling helpers =====================================================
    // Alle samplers retourneren een waarde uit de geleerde verdeling met lichte jitter,
    // of vallen terug op een sane fallback als het profiel die metriek mist.

    public int sampleMoveStepPx(Random rng, int fallback) {
        return Math.max(0, sampleFromPercentiles(rng, moveStepPx, fallback, 1));
    }

    public int sampleMoveDtMs(Random rng, int fallback) {
        return Math.max(1, sampleFromPercentiles(rng, moveDtMs, fallback, 1));
    }

    public int sampleDragDurationMs(Random rng, int fallback) {
        return Math.max(40, sampleFromPercentiles(rng, dragDurationMs, fallback, 5));
    }

    public int samplePressReleaseDwellMs(Random rng, int fallback) {
        return Math.max(40, sampleFromPercentiles(rng, pressReleaseDwellMs, fallback, 5));
    }

    public int sampleInterClickGapMs(Random rng, int fallback) {
        return Math.max(50, sampleFromPercentiles(rng, interClickGapMs, fallback, 10));
    }

    /**
     * Trekt een waarde uit de p25/p50/p75/p95-verdeling met lichte jitter.
     * Strategie: kies bucket op basis van uniform getal,
     * interpoleer lineair binnen de bucket en jitter een paar procent.
     */
    private static int sampleFromPercentiles(Random rng, Percentiles p, int fallback, int jitter) {
        if (p == null || !p.isUsable() || rng == null) {
            return fallback;
        }
        double u = rng.nextDouble();
        double v;
        if (u < 0.25) {
            v = p.p25 * (u / 0.25 + 0.5);
        } else if (u < 0.5) {
            v = p.p25 + (p.p50 - p.p25) * ((u - 0.25) / 0.25);
        } else if (u < 0.75) {
            v = p.p50 + (p.p75 - p.p50) * ((u - 0.5) / 0.25);
        } else if (u < 0.95) {
            v = p.p75 + (p.p95 - p.p75) * ((u - 0.75) / 0.20);
        } else {
            v = p.p95 + (p.p95 - p.p75) * ((u - 0.95) / 0.05);
        }
        if (jitter > 0) {
            v += (rng.nextDouble() - 0.5) * 2.0 * jitter;
        }
        return (int) Math.round(v);
    }

    /** True als er bruikbare percentielen geladen zijn voor minstens beweging + dwell. */
    public boolean isUsable() {
        return moveDtMs != null && moveDtMs.isUsable()
                && pressReleaseDwellMs != null && pressReleaseDwellMs.isUsable();
    }

    /**
     * Compacte JSON-string voor diagnostische uitvoer (toont datum + medianen).
     */
    public JsonObject toShortJson() {
        JsonObject j = new JsonObject();
        j.addProperty("v", version);
        j.addProperty("at", generatedAtMs);
        j.addProperty("events", totalEvents);
        j.addProperty("samples", usedSamples);
        if (moveDtMs != null) {
            j.addProperty("moveDtP50", moveDtMs.p50);
        }
        if (pressReleaseDwellMs != null) {
            j.addProperty("dwellP50", pressReleaseDwellMs.p50);
        }
        if (interClickGapMs != null) {
            j.addProperty("clickGapP50", interClickGapMs.p50);
        }
        return j;
    }
}
