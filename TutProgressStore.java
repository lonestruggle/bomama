package com.combatbot;

import net.storm.api.plugins.config.ConfigManager;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Houdt per account bij of Tutorial Island al eenmalig is afgerond.
 */
public final class TutProgressStore {
    private static final String GROUP = "combatbot";
    private static final String KEY = "tutCompletedAccountsBlob";

    private TutProgressStore() {
    }

    public static Set<String> parse(String blob) {
        Set<String> done = new LinkedHashSet<>();
        if (blob == null || blob.trim().isEmpty()) {
            return done;
        }
        for (String line : blob.split("\\n")) {
            String rsn = line == null ? "" : line.trim().toLowerCase(Locale.ROOT);
            if (!rsn.isEmpty()) {
                done.add(rsn);
            }
        }
        return done;
    }

    public static String serialize(Set<String> done) {
        StringBuilder sb = new StringBuilder();
        if (done == null) {
            return "";
        }
        for (String rsn : done) {
            if (rsn == null) {
                continue;
            }
            String cleaned = rsn.trim().toLowerCase(Locale.ROOT);
            if (cleaned.isEmpty()) {
                continue;
            }
            sb.append(cleaned).append('\n');
        }
        return sb.toString();
    }

    public static boolean isCompleted(CombatBotConfig cfg, String rsn) {
        if (cfg == null || rsn == null || rsn.trim().isEmpty()) {
            return false;
        }
        return parse(cfg.tutCompletedAccountsBlob()).contains(rsn.trim().toLowerCase(Locale.ROOT));
    }

    public static void markCompleted(ConfigManager cm, CombatBotConfig cfg, String rsn) {
        if (cm == null || cfg == null || rsn == null || rsn.trim().isEmpty()) {
            return;
        }
        Set<String> done = parse(cfg.tutCompletedAccountsBlob());
        done.add(rsn.trim().toLowerCase(Locale.ROOT));
        cm.setConfiguration(GROUP, KEY, serialize(done));
    }
}
