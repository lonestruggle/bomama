package com.combatbot;

import net.runelite.api.Quest;
import net.storm.api.plugins.config.ConfigManager;
import net.storm.sdk.quests.Quests;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Per-account quest state: Vampyre Slayer alleen <b>voltooid</b> (stap ≥ {@link #VAMPIRE_SLAYER_STEP_DONE}) + hammer-van-imp.
 * Tussenstappen staan niet meer in JSON (voorkomt vastlopers door verouderde stap).
 * <p>Primair: {@link AccountStateJsonStore} ({@code ~/.runelite/combatbot-account-state.json}).
 * Het config-blob veld blijft geschreven voor compatibiliteit / migratie.</p>
 */
public final class AccountQuestProgressStore {

    private static final String GROUP = "combatbot";
    private static final String KEY = "accountQuestProgressBlob";
    /** Stap &gt;= dit = quest voltooid, quest-modus mag uit. */
    public static final int VAMPIRE_SLAYER_STEP_DONE = 1000;

    private AccountQuestProgressStore() {
    }

    public static final class QuestEntry {
        public int vampireSlayerStep;
        public boolean hammerFromImp;
    }

    public static Map<String, QuestEntry> parse(String blob) {
        Map<String, QuestEntry> m = new LinkedHashMap<>();
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
            if (p.length < 2) {
                continue;
            }
            try {
                QuestEntry e = new QuestEntry();
                e.vampireSlayerStep = Integer.parseInt(p[0].trim());
                e.hammerFromImp = "1".equals(p[1].trim()) || Boolean.parseBoolean(p[1].trim());
                m.put(key, e);
            } catch (NumberFormatException ignored) {
            }
        }
        return m;
    }

    public static String serialize(Map<String, QuestEntry> map) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, QuestEntry> e : map.entrySet()) {
            QuestEntry v = e.getValue();
            if (v == null) {
                continue;
            }
            sb.append(e.getKey()).append('=')
                    .append(v.vampireSlayerStep).append('|')
                    .append(v.hammerFromImp ? '1' : '0')
                    .append('\n');
        }
        return sb.toString();
    }

    public static void put(ConfigManager cm, CombatBotConfig cfg, String displayName, QuestEntry entry) {
        if (displayName == null || displayName.trim().isEmpty() || entry == null) {
            return;
        }
        QuestEntry persisted = persistableCopy(entry);
        AccountStateJsonStore.putQuestEntry(displayName.trim(), persisted);
        if (cm == null || cfg == null) {
            return;
        }
        String k = displayName.trim().toLowerCase(Locale.ROOT);
        Map<String, QuestEntry> m = parse(cfg.accountQuestProgressBlob());
        m.put(k, persisted);
        cm.setConfiguration(GROUP, KEY, serialize(m));
    }

    /** Alleen voltooide quest en hammer naar bestand; geen tussenstappen. */
    private static QuestEntry persistableCopy(QuestEntry entry) {
        QuestEntry p = new QuestEntry();
        p.hammerFromImp = entry.hammerFromImp;
        p.vampireSlayerStep = entry.vampireSlayerStep >= VAMPIRE_SLAYER_STEP_DONE ? entry.vampireSlayerStep : 0;
        return p;
    }

    public static QuestEntry get(CombatBotConfig cfg, String displayName) {
        if (displayName == null || displayName.trim().isEmpty()) {
            return null;
        }
        String trim = displayName.trim();
        String k = trim.toLowerCase(Locale.ROOT);
        QuestEntry fromJson = AccountStateJsonStore.getQuestEntry(trim);
        QuestEntry fromBlob = cfg != null ? parse(cfg.accountQuestProgressBlob()).get(k) : null;

        if (fromJson == null && fromBlob != null) {
            QuestEntry p = persistableCopy(fromBlob);
            AccountStateJsonStore.putQuestEntry(trim, p);
            return stripIntermediateVampireStep(fromBlob);
        }
        if (fromJson != null && fromBlob != null) {
            if (fromBlob.vampireSlayerStep > fromJson.vampireSlayerStep
                    || (fromBlob.hammerFromImp && !fromJson.hammerFromImp)) {
                QuestEntry merged = new QuestEntry();
                merged.vampireSlayerStep = Math.max(fromJson.vampireSlayerStep, fromBlob.vampireSlayerStep);
                merged.hammerFromImp = fromJson.hammerFromImp || fromBlob.hammerFromImp;
                AccountStateJsonStore.putQuestEntry(trim, persistableCopy(merged));
                return stripIntermediateVampireStep(merged);
            }
        }
        if (fromJson != null) {
            return stripIntermediateVampireStep(fromJson);
        }
        return stripIntermediateVampireStep(fromBlob);
    }

    private static QuestEntry stripIntermediateVampireStep(QuestEntry e) {
        if (e == null) {
            return null;
        }
        if (e.vampireSlayerStep >= VAMPIRE_SLAYER_STEP_DONE) {
            return e;
        }
        QuestEntry o = new QuestEntry();
        o.hammerFromImp = e.hammerFromImp;
        o.vampireSlayerStep = 0;
        return o;
    }

    public static boolean isVampireSlayerComplete(CombatBotConfig cfg, String displayName) {
        try {
            if (Quests.isFinished(Quest.VAMPYRE_SLAYER)) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        QuestEntry e = get(cfg, displayName);
        return e != null && e.vampireSlayerStep >= VAMPIRE_SLAYER_STEP_DONE;
    }

    public static void setHammerFromImp(ConfigManager cm, CombatBotConfig cfg, String displayName) {
        if (cm == null || cfg == null || displayName == null || displayName.trim().isEmpty()) {
            return;
        }
        QuestEntry e = get(cfg, displayName.trim());
        if (e == null) {
            e = new QuestEntry();
        }
        e.hammerFromImp = true;
        put(cm, cfg, displayName.trim(), e);
    }

    public static void clear(ConfigManager cm) {
        if (cm != null) {
            cm.setConfiguration(GROUP, KEY, "");
        }
    }
}
