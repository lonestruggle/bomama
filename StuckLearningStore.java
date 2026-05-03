package com.combatbot;

import net.storm.api.plugins.config.ConfigManager;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Klein, persistent geheugen voor stuck-patronen.
 * Formaat per regel: keyLower=count|lastEpochMs
 */
public final class StuckLearningStore {

    private static final String GROUP = "combatbot";
    private static final String KEY = "stuckLearningBlob";

    private StuckLearningStore() {
    }

    public static final class Entry {
        public int count;
        public long lastEpochMs;
    }

    public static Entry get(CombatBotConfig cfg, String key) {
        if (cfg == null || key == null || key.trim().isEmpty()) {
            return null;
        }
        return parse(cfg.stuckLearningBlob()).get(key.trim().toLowerCase(Locale.ROOT));
    }

    public static void increment(ConfigManager cm, CombatBotConfig cfg, String key) {
        if (cm == null || cfg == null || key == null || key.trim().isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        String norm = key.trim().toLowerCase(Locale.ROOT);
        Map<String, Entry> all = parse(cfg.stuckLearningBlob());
        Entry e = all.get(norm);
        if (e == null) {
            e = new Entry();
        }
        // Simpele decay: als het lang geleden is, begin rustiger opnieuw.
        if (e.lastEpochMs > 0L && now - e.lastEpochMs > 24L * 60L * 60L * 1000L) {
            e.count = Math.max(0, e.count - 1);
        }
        e.count++;
        e.lastEpochMs = now;
        all.put(norm, e);
        cm.setConfiguration(GROUP, KEY, serialize(all));
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
            String k = line.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String[] p = line.substring(eq + 1).split("\\|", -1);
            if (p.length < 2) continue;
            try {
                Entry e = new Entry();
                e.count = Integer.parseInt(p[0].trim());
                e.lastEpochMs = Long.parseLong(p[1].trim());
                out.put(k, e);
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    private static String serialize(Map<String, Entry> map) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Entry> en : map.entrySet()) {
            if (en.getKey() == null || en.getKey().isEmpty() || en.getValue() == null) continue;
            Entry e = en.getValue();
            sb.append(en.getKey()).append('=')
                    .append(Math.max(0, e.count)).append('|')
                    .append(Math.max(0L, e.lastEpochMs))
                    .append('\n');
        }
        return sb.toString();
    }
}
