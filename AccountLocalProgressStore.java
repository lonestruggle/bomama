package com.combatbot;

import net.storm.api.plugins.config.ConfigManager;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Per-account bot-state (welke skill actief was + voortgang rotatie-timer) voor wisselen na logout.
 * Formaat: {@code rsnLower=SKILLNAME|elapsedSec|totalSwitchSec} per regel.
 */
public final class AccountLocalProgressStore {

    private static final String D = "\u001F";
    private static final String GROUP = "combatbot";
    private static final String KEY = "accountLocalProgressBlob";

    private AccountLocalProgressStore() {
    }

    public static final class Entry {
        public String activeSkillName = "COMBAT";
        /** Seconden sinds start van huidige skill-blok. */
        public long elapsedSec;
        public long totalSwitchSec;
    }

    public static Map<String, Entry> parse(String blob) {
        Map<String, Entry> m = new LinkedHashMap<>();
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
            String rest = line.substring(eq + 1);
            String[] p = rest.split("\\|", -1);
            if (p.length < 3) {
                continue;
            }
            try {
                Entry e = new Entry();
                e.activeSkillName = p[0].trim();
                e.elapsedSec = Long.parseLong(p[1].trim());
                e.totalSwitchSec = Long.parseLong(p[2].trim());
                m.put(key, e);
            } catch (NumberFormatException ignored) {
            }
        }
        return m;
    }

    public static String serialize(Map<String, Entry> map) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Entry> e : map.entrySet()) {
            Entry v = e.getValue();
            if (v == null) {
                continue;
            }
            sb.append(e.getKey()).append('=')
                    .append(v.activeSkillName).append('|')
                    .append(v.elapsedSec).append('|')
                    .append(v.totalSwitchSec).append('\n');
        }
        return sb.toString();
    }

    public static void put(ConfigManager cm, CombatBotConfig cfg, String displayName, Entry entry) {
        if (cm == null || cfg == null || displayName == null || displayName.trim().isEmpty() || entry == null) {
            return;
        }
        String k = displayName.trim().toLowerCase(Locale.ROOT);
        Map<String, Entry> m = parse(cfg.accountLocalProgressBlob());
        m.put(k, entry);
        cm.setConfiguration(GROUP, KEY, serialize(m));
    }

    public static Entry get(CombatBotConfig cfg, String displayName) {
        if (cfg == null || displayName == null || displayName.trim().isEmpty()) {
            return null;
        }
        return parse(cfg.accountLocalProgressBlob()).get(displayName.trim().toLowerCase(Locale.ROOT));
    }

    public static void clear(ConfigManager cm) {
        if (cm != null) {
            cm.setConfiguration(GROUP, KEY, "");
        }
    }
}
