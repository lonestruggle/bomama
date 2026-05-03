package com.combatbot;

import net.storm.api.plugins.config.ConfigManager;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Per RSN: Barb loot voor Magic &lt; 5 is niet meer nodig (Magic ≥ 5 gedetecteerd of handmatig overgeslagen).
 */
public final class BarbLootSkipStore {

    private static final String GROUP = "combatbot";
    private static final String KEY = "barbLootMagicSkipBlob";

    private BarbLootSkipStore() {
    }

    public static boolean isSkipped(CombatBotConfig cfg, String rsn) {
        if (cfg == null || rsn == null || rsn.trim().isEmpty()) {
            return false;
        }
        return "1".equals(parse(cfg.barbLootMagicSkipBlob()).get(rsn.trim().toLowerCase(Locale.ROOT)));
    }

    public static void setSkipped(ConfigManager cm, CombatBotConfig cfg, String rsn) {
        if (cm == null || cfg == null || rsn == null || rsn.trim().isEmpty()) {
            return;
        }
        String k = rsn.trim().toLowerCase(Locale.ROOT);
        Map<String, String> m = parse(cfg.barbLootMagicSkipBlob());
        m.put(k, "1");
        cm.setConfiguration(GROUP, KEY, serialize(m));
    }

    private static Map<String, String> parse(String blob) {
        Map<String, String> m = new LinkedHashMap<>();
        if (blob == null || blob.trim().isEmpty()) {
            return m;
        }
        for (String line : blob.split("\\n")) {
            if (line.isEmpty()) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String val = line.substring(eq + 1).trim();
            m.put(key, val);
        }
        return m;
    }

    private static String serialize(Map<String, String> map) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (e.getKey() == null || e.getKey().isEmpty()) {
                continue;
            }
            sb.append(e.getKey()).append('=').append(e.getValue() != null ? e.getValue() : "").append('\n');
        }
        return sb.toString();
    }
}
