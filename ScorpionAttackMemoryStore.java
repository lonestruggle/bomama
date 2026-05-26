package com.combatbot;

import net.runelite.api.coords.WorldPoint;
import net.storm.api.plugins.config.ConfigManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Per account onthouden op welke tiles scorpion-aggro is gedetecteerd.
 * Formaat per regel:
 * rsnLower|x|y|plane=count|lastEpochMs
 */
public final class ScorpionAttackMemoryStore {

    private static final String GROUP = "combatbot";
    private static final String KEY = "impsScorpionAttackMemoryBlob";

    private ScorpionAttackMemoryStore() {
    }

    public static final class Entry {
        public String rsnLower;
        public int x;
        public int y;
        public int plane;
        public int count;
        public long lastEpochMs;
    }

    public static void recordAttack(ConfigManager cm, CombatBotConfig cfg, String rsn, WorldPoint p) {
        if (cm == null || cfg == null || rsn == null || rsn.trim().isEmpty() || p == null) {
            return;
        }
        String key = buildKey(rsn, p);
        long now = System.currentTimeMillis();
        Map<String, Entry> all = parse(cfg.impsScorpionAttackMemoryBlob());
        Entry e = all.get(key);
        if (e == null) {
            e = new Entry();
            e.rsnLower = rsn.trim().toLowerCase(Locale.ROOT);
            e.x = p.getX();
            e.y = p.getY();
            e.plane = p.getPlane();
        }
        if (e.lastEpochMs > 0L && now - e.lastEpochMs > 48L * 60L * 60L * 1000L) {
            e.count = Math.max(0, e.count - 1); // lichte decay over tijd
        }
        e.count++;
        e.lastEpochMs = now;
        all.put(key, e);
        cm.setConfiguration(GROUP, KEY, serialize(all));
    }

    public static List<Entry> getRecentForRsn(CombatBotConfig cfg, String rsn, int minCount, long maxAgeMs) {
        List<Entry> out = new ArrayList<>();
        if (cfg == null || rsn == null || rsn.trim().isEmpty()) {
            return out;
        }
        String norm = rsn.trim().toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        for (Entry e : parse(cfg.impsScorpionAttackMemoryBlob()).values()) {
            if (e == null || e.rsnLower == null) continue;
            if (!e.rsnLower.equals(norm)) continue;
            if (e.count < Math.max(1, minCount)) continue;
            if (maxAgeMs > 0 && e.lastEpochMs > 0L && now - e.lastEpochMs > maxAgeMs) continue;
            out.add(e);
        }
        return out;
    }

    private static String buildKey(String rsn, WorldPoint p) {
        return rsn.trim().toLowerCase(Locale.ROOT) + "|" + p.getX() + "|" + p.getY() + "|" + p.getPlane();
    }

    private static Map<String, Entry> parse(String blob) {
        Map<String, Entry> out = new LinkedHashMap<>();
        if (blob == null || blob.trim().isEmpty()) {
            return out;
        }
        for (String line : blob.split("\\n")) {
            if (line == null || line.trim().isEmpty()) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String k = line.substring(0, eq).trim();
            String[] id = k.split("\\|", -1);
            if (id.length < 4) continue;
            String[] val = line.substring(eq + 1).split("\\|", -1);
            if (val.length < 2) continue;
            try {
                Entry e = new Entry();
                e.rsnLower = id[0].trim().toLowerCase(Locale.ROOT);
                e.x = Integer.parseInt(id[1].trim());
                e.y = Integer.parseInt(id[2].trim());
                e.plane = Integer.parseInt(id[3].trim());
                e.count = Integer.parseInt(val[0].trim());
                e.lastEpochMs = Long.parseLong(val[1].trim());
                out.put(k, e);
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    private static String serialize(Map<String, Entry> map) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Entry> en : map.entrySet()) {
            if (en.getValue() == null) continue;
            Entry e = en.getValue();
            sb.append(en.getKey()).append('=')
                    .append(Math.max(0, e.count)).append('|')
                    .append(Math.max(0L, e.lastEpochMs))
                    .append('\n');
        }
        return sb.toString();
    }
}
