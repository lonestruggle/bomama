package com.combatbot;

import net.runelite.api.coords.WorldPoint;
import net.storm.api.plugins.config.ConfigManager;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Per-RSN gedrag: anti-ban-gewichten, loop-doel-tegels, fatigue na stuck-recoveries,
 * en {@code failureNudge} (reactieve vertraging na fouten / stalls — met decay bij bank-sluiting).
 * <p>
 * Blob: {@code rsn=seed|dMul|offCam|offIdle|offMouse|offMis|struggle|fatigue|failureNudge}
 */
public final class AccountBehaviorProfileStore {

    private static final String GROUP = "combatbot";
    private static final String KEY = "accountBehaviorProfileBlob";
    private static final int MAX_FAILURE_NUDGE = 10;
    /** Per +1 nudge: +5% op anti-ban-delay (max {@value #MAX_FAILURE_NUDGE} → +50%). */
    private static final double FAILURE_NUDGE_STEP = 0.05;

    private AccountBehaviorProfileStore() {
    }

    public static final class Profile {
        public final long seed;
        public final int delayMultiplierPercent;
        public final int weightOffsetCamera;
        public final int weightOffsetIdle;
        public final int weightOffsetMouse;
        public final int weightOffsetMisclick;
        public final int environmentStruggleEvents;
        public final int delayFatigueBonusPercent;
        /**
         * 0–10: interactie-/stall-straflagen; elke stap +5% op {@link #failureDelayMultiplier()}.
         */
        public final int failureNudge;

        Profile(long seed, int delayMultiplierPercent,
                int weightOffsetCamera, int weightOffsetIdle,
                int weightOffsetMouse, int weightOffsetMisclick,
                int environmentStruggleEvents, int delayFatigueBonusPercent,
                int failureNudge) {
            this.seed = seed;
            this.delayMultiplierPercent = delayMultiplierPercent;
            this.weightOffsetCamera = weightOffsetCamera;
            this.weightOffsetIdle = weightOffsetIdle;
            this.weightOffsetMouse = weightOffsetMouse;
            this.weightOffsetMisclick = weightOffsetMisclick;
            this.environmentStruggleEvents = environmentStruggleEvents;
            this.delayFatigueBonusPercent = delayFatigueBonusPercent;
            this.failureNudge = Math.min(MAX_FAILURE_NUDGE, Math.max(0, failureNudge));
        }

        /** Basis + fatigue (85–130%), vóór {@link #failureDelayMultiplier()}. */
        public int effectiveDelayMultiplierPercent() {
            int b = Math.min(125, Math.max(85, delayMultiplierPercent));
            int f = Math.min(5, Math.max(0, delayFatigueBonusPercent));
            return Math.min(130, Math.max(85, b + f));
        }

        /** Vermenigvuldiger op delay (1.0 … 1.5). */
        public double failureDelayMultiplier() {
            return 1.0 + FAILURE_NUDGE_STEP * Math.min(MAX_FAILURE_NUDGE, Math.max(0, failureNudge));
        }
    }

    public static Profile getOrCreate(ConfigManager cm, CombatBotConfig cfg, String displayName) {
        if (cfg == null || displayName == null || displayName.trim().isEmpty()) {
            return null;
        }
        String k = displayName.trim().toLowerCase(Locale.ROOT);
        Map<String, Profile> m = parse(cfg.accountBehaviorProfileBlob());
        Profile existing = m.get(k);
        if (existing != null) {
            return existing;
        }
        Profile gen = generateForKey(k);
        if (cm != null) {
            m.put(k, gen);
            cm.setConfiguration(GROUP, KEY, serialize(m));
        }
        return gen;
    }

    /**
     * Stuck / movement-stall recovery: verhoog struggle + fatigue (elke 5) en verhoog {@code failureNudge} met 1 (cap 10).
     */
    public static void recordEnvironmentStruggle(ConfigManager cm, CombatBotConfig cfg, String displayName) {
        if (cm == null || cfg == null || displayName == null || displayName.trim().isEmpty()) {
            return;
        }
        if (!cfg.accountBehaviorProfileEnabled()) {
            return;
        }
        String k = displayName.trim().toLowerCase(Locale.ROOT);
        Map<String, Profile> m = parse(cfg.accountBehaviorProfileBlob());
        Profile old = m.get(k);
        if (old == null) {
            old = generateForKey(k);
        }
        int newStruggle = old.environmentStruggleEvents + 1;
        int newFatigue = old.delayFatigueBonusPercent;
        if (newStruggle % 5 == 0) {
            newFatigue = Math.min(5, newFatigue + 1);
        }
        int newFail = Math.min(MAX_FAILURE_NUDGE, old.failureNudge + 1);
        Profile neu = copyProfile(old, newStruggle, newFatigue, newFail);
        m.put(k, neu);
        cm.setConfiguration(GROUP, KEY, serialize(m));
    }

    /** Expliciete interactie-fout (timeouts, mislukte opens, …) — +1 nudge, cap 10. */
    public static void incrementFailureNudge(ConfigManager cm, CombatBotConfig cfg, String displayName) {
        mutateFailureNudge(cm, cfg, displayName, +1);
    }

    /** Decay: typisch na bank-sluiting (succesvolle flow); −1, vloer 0. */
    public static void decrementFailureNudge(ConfigManager cm, CombatBotConfig cfg, String displayName) {
        mutateFailureNudge(cm, cfg, displayName, -1);
    }

    private static void mutateFailureNudge(ConfigManager cm, CombatBotConfig cfg, String displayName, int delta) {
        if (cm == null || cfg == null || displayName == null || displayName.trim().isEmpty()) {
            return;
        }
        if (!cfg.accountBehaviorProfileEnabled()) {
            return;
        }
        String k = displayName.trim().toLowerCase(Locale.ROOT);
        Map<String, Profile> m = parse(cfg.accountBehaviorProfileBlob());
        Profile old = m.get(k);
        if (old == null) {
            old = generateForKey(k);
        }
        int nf = Math.min(MAX_FAILURE_NUDGE, Math.max(0, old.failureNudge + delta));
        if (nf == old.failureNudge) {
            return;
        }
        Profile neu = copyProfile(old, old.environmentStruggleEvents, old.delayFatigueBonusPercent, nf);
        m.put(k, neu);
        cm.setConfiguration(GROUP, KEY, serialize(m));
    }

    private static Profile copyProfile(Profile old, int struggle, int fatigue, int failureNudge) {
        return new Profile(
                old.seed,
                old.delayMultiplierPercent,
                old.weightOffsetCamera,
                old.weightOffsetIdle,
                old.weightOffsetMouse,
                old.weightOffsetMisclick,
                struggle,
                fatigue,
                failureNudge);
    }

    static long seedFromRsn(String rsnLower) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < rsnLower.length(); i++) {
            h ^= (rsnLower.charAt(i) & 0xff);
            h *= 0x100000001b3L;
        }
        return h;
    }

    static Profile generateForKey(String rsnLower) {
        long seed = seedFromRsn(rsnLower);
        Random rnd = new Random(seed);
        int dMul = 88 + rnd.nextInt(25);
        int wCam = -10 + rnd.nextInt(21);
        int wIdle = -10 + rnd.nextInt(21);
        int wMouse = -10 + rnd.nextInt(21);
        int wMis = -10 + rnd.nextInt(21);
        return new Profile(seed, dMul, wCam, wIdle, wMouse, wMis, 0, 0, 0);
    }

    public static boolean shouldSkipApproachPersonalization(WorldPoint goal) {
        if (goal == null) {
            return true;
        }
        int x = goal.getX();
        int y = goal.getY();
        int p = goal.getPlane();
        return x == 3206 && y == 3208 && (p == 0 || p == 1);
    }

    public static WorldPoint personalizeApproachTile(Profile profile, WorldPoint canonical) {
        if (canonical == null || profile == null) {
            return canonical;
        }
        if (shouldSkipApproachPersonalization(canonical)) {
            return canonical;
        }
        long mix = profile.seed
                ^ 0x9E3779B97F4A7C15L
                ^ (((long) canonical.getX()) << 20)
                ^ (((long) canonical.getY()) << 5)
                ^ ((long) canonical.getPlane() << 40);
        Random r = new Random(mix);
        int dx = r.nextInt(3) - 1;
        int dy = r.nextInt(3) - 1;
        if (dx == 0 && dy == 0) {
            if (r.nextBoolean()) {
                dx = r.nextBoolean() ? 1 : -1;
            } else {
                dy = r.nextBoolean() ? 1 : -1;
            }
        }
        return new WorldPoint(canonical.getX() + dx, canonical.getY() + dy, canonical.getPlane());
    }

    @Deprecated
    public static WorldPoint personalizeBankWalkGoal(Profile profile, WorldPoint canonical) {
        return personalizeApproachTile(profile, canonical);
    }

    private static Map<String, Profile> parse(String blob) {
        Map<String, Profile> out = new LinkedHashMap<>();
        if (blob == null || blob.trim().isEmpty()) {
            return out;
        }
        for (String line : blob.split("\\n")) {
            if (line == null || line.trim().isEmpty()) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String rest = line.substring(eq + 1);
            String[] p = rest.split("\\|", -1);
            if (p.length < 6) {
                continue;
            }
            try {
                long seed = Long.parseLong(p[0].trim());
                int dMul = Integer.parseInt(p[1].trim());
                int oCam = Integer.parseInt(p[2].trim());
                int oIdle = Integer.parseInt(p[3].trim());
                int oMouse = Integer.parseInt(p[4].trim());
                int oMis = Integer.parseInt(p[5].trim());
                int struggle = p.length > 6 ? Math.max(0, Integer.parseInt(p[6].trim())) : 0;
                int fatigue = p.length > 7 ? Math.min(5, Math.max(0, Integer.parseInt(p[7].trim()))) : 0;
                int fail = p.length > 8 ? Math.min(MAX_FAILURE_NUDGE, Math.max(0, Integer.parseInt(p[8].trim()))) : 0;
                out.put(key, new Profile(seed, dMul, oCam, oIdle, oMouse, oMis, struggle, fatigue, fail));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    private static String serialize(Map<String, Profile> map) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Profile> en : map.entrySet()) {
            Profile v = en.getValue();
            if (v == null || en.getKey() == null || en.getKey().isEmpty()) {
                continue;
            }
            sb.append(en.getKey()).append('=')
                    .append(v.seed).append('|')
                    .append(v.delayMultiplierPercent).append('|')
                    .append(v.weightOffsetCamera).append('|')
                    .append(v.weightOffsetIdle).append('|')
                    .append(v.weightOffsetMouse).append('|')
                    .append(v.weightOffsetMisclick).append('|')
                    .append(v.environmentStruggleEvents).append('|')
                    .append(v.delayFatigueBonusPercent).append('|')
                    .append(v.failureNudge)
                    .append('\n');
        }
        return sb.toString();
    }
}
