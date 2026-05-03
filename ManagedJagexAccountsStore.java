package com.combatbot;

import net.storm.api.plugins.config.ConfigManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Opslag en synchronisatie van Jagex-accounts (display/session/character + rotatie + centers)
 * met {@link CombatBotConfig#pastedCredentials()} en {@link CombatBotConfig#enabledDisplayNames()}.
 */
public final class ManagedJagexAccountsStore {

    private static final String D = "\u001F";
    private static final String GROUP = "combatbot";

    public static final class ManagedJagexAccountRow {
        public String displayName = "";
        public String characterId = "";
        public String sessionId = "";
        public boolean rotationEnabled = true;
        public String notes = "";
        /**
         * Optioneel: deelverzameling van de globale Centers-tab (zelfde formaat als config).
         * Leeg + {@code useGlobal*Centers true} = hele globale lijst. Niet-leeg = alleen deze locaties.
         */
        public String combatCenters = "";
        public String wcCenters = "";
        public String miningCenters = "";
        public String fishingCenters = "";
        public String impsCenters = "";
        /** Aan = skill gebruikt globale centers (eventueel gefilterd via {@code combatCenters}… velden). Uit = geen centers. */
        public boolean useGlobalCombatCenters = true;
        public boolean useGlobalWcCenters = true;
        public boolean useGlobalMiningCenters = true;
        public boolean useGlobalFishingCenters = true;
        public boolean useGlobalImpsCenters = true;
        /** Optioneel: RuneLite wereld-ID voor account-wissel. Leeg = willekeurige F2P-wereld. */
        public String accountSwitchWorld = "";
        /** {@code false} = geen wereld-hop bij rotatie naar dit account (blijf op huidige wereld). */
        public boolean accountSwitchWorldHopEnabled = true;
        /** Optioneel: per-account melee train-doelen voor Starter (0 = standaard/random). */
        public int targetAttackLevel = 0;
        public int targetStrengthLevel = 0;
        public int targetDefenceLevel = 0;
        /** Forceer bij volgende login een bank-open + snapshot update voor dit account. */
        public boolean calibrateBankOnNextLogin = false;
        /** Auto-upgrade MAGE naar Fire Strike (met GE-koop/sell loot indien nodig). */
        public boolean magicAutoUpdate = false;
        /**
         * Imps combat style: leeg = volg globale {@link CombatBotConfig#impsCombatStyle()};
         * anders {@code MELEE}, {@code RANGED} of {@code MAGE}.
         */
        public String impsCombatStyleOverride = "";

        public boolean isEmpty() {
            return displayName == null || displayName.trim().isEmpty();
        }

        public ManagedJagexAccountRow copy() {
            ManagedJagexAccountRow r = new ManagedJagexAccountRow();
            r.displayName = displayName;
            r.characterId = characterId;
            r.sessionId = sessionId;
            r.rotationEnabled = rotationEnabled;
            r.notes = notes;
            r.combatCenters = combatCenters;
            r.wcCenters = wcCenters;
            r.miningCenters = miningCenters;
            r.fishingCenters = fishingCenters;
            r.impsCenters = impsCenters;
            r.useGlobalCombatCenters = useGlobalCombatCenters;
            r.useGlobalWcCenters = useGlobalWcCenters;
            r.useGlobalMiningCenters = useGlobalMiningCenters;
            r.useGlobalFishingCenters = useGlobalFishingCenters;
            r.useGlobalImpsCenters = useGlobalImpsCenters;
            r.accountSwitchWorld = accountSwitchWorld;
            r.accountSwitchWorldHopEnabled = accountSwitchWorldHopEnabled;
            r.targetAttackLevel = targetAttackLevel;
            r.targetStrengthLevel = targetStrengthLevel;
            r.targetDefenceLevel = targetDefenceLevel;
            r.calibrateBankOnNextLogin = calibrateBankOnNextLogin;
            r.magicAutoUpdate = magicAutoUpdate;
            r.impsCombatStyleOverride = impsCombatStyleOverride != null ? impsCombatStyleOverride : "";
            return r;
        }
    }

    private static boolean nonBlankCenter(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static String offOn(boolean useGlobal) {
        return useGlobal ? "1" : "0";
    }

    public static final class AccountStatSnapshot {
        public long totalGpApprox;
        public int combatLevel;
        public int woodcutting;
        public int mining;
        public int fishing;
        public int prayer;
        public int magic;
        /** Quests: voorlopig quest points (of 0 als onbekend). */
        public int quests;
        public int attack;
        public int strength;
        public int defence;
        public long updatedEpochMs;
        /** Geen hiscore (API) — vaak geband; handmatig controleren. */
        public boolean hiscoreSuspectBanned;
    }

    public static List<ManagedJagexAccountRow> parseRows(String blob) {
        List<ManagedJagexAccountRow> out = new ArrayList<>();
        if (blob == null || blob.trim().isEmpty()) {
            return out;
        }
        for (String line : blob.split("\\n")) {
            if (line.isEmpty()) {
                continue;
            }
            String[] p = line.split(Pattern.quote(D), -1);
            ManagedJagexAccountRow r = new ManagedJagexAccountRow();
            r.displayName = get(p, 0);
            r.characterId = get(p, 1);
            r.sessionId = get(p, 2);
            r.rotationEnabled = !"0".equals(get(p, 3));
            r.notes = get(p, 4);
            r.combatCenters = get(p, 5);
            r.wcCenters = get(p, 6);
            r.miningCenters = get(p, 7);
            r.fishingCenters = get(p, 8);
            r.impsCenters = get(p, 9);
            if (p.length >= 15) {
                r.useGlobalCombatCenters = "1".equals(get(p, 10));
                r.useGlobalWcCenters = "1".equals(get(p, 11));
                r.useGlobalMiningCenters = "1".equals(get(p, 12));
                r.useGlobalFishingCenters = "1".equals(get(p, 13));
                r.useGlobalImpsCenters = "1".equals(get(p, 14));
                if (p.length >= 16) {
                    r.accountSwitchWorld = get(p, 15);
                }
                if (p.length >= 17) {
                    r.accountSwitchWorldHopEnabled = "1".equals(get(p, 16));
                }
                if (p.length >= 18) {
                    r.targetAttackLevel = parseIntOrZero(get(p, 17));
                }
                if (p.length >= 19) {
                    r.targetStrengthLevel = parseIntOrZero(get(p, 18));
                }
                if (p.length >= 20) {
                    r.targetDefenceLevel = parseIntOrZero(get(p, 19));
                }
                if (p.length >= 21) {
                    r.calibrateBankOnNextLogin = "1".equals(get(p, 20));
                }
                if (p.length >= 22) {
                    r.magicAutoUpdate = "1".equals(get(p, 21));
                }
                if (p.length >= 23) {
                    r.impsCombatStyleOverride = get(p, 22);
                }
            } else {
                r.useGlobalCombatCenters = !nonBlankCenter(r.combatCenters);
                r.useGlobalWcCenters = !nonBlankCenter(r.wcCenters);
                r.useGlobalMiningCenters = !nonBlankCenter(r.miningCenters);
                r.useGlobalFishingCenters = !nonBlankCenter(r.fishingCenters);
                r.useGlobalImpsCenters = !nonBlankCenter(r.impsCenters);
            }
            if (!r.isEmpty()) {
                out.add(r);
            }
        }
        return out;
    }

    private static String get(String[] p, int i) {
        return i < p.length ? p[i] : "";
    }

    public static String serializeRows(List<ManagedJagexAccountRow> rows) {
        StringBuilder sb = new StringBuilder();
        for (ManagedJagexAccountRow r : rows) {
            if (r.isEmpty()) {
                continue;
            }
            sb.append(nz(r.displayName)).append(D)
                    .append(nz(r.characterId)).append(D)
                    .append(nz(r.sessionId)).append(D)
                    .append(r.rotationEnabled ? "1" : "0").append(D)
                    .append(nz(r.notes)).append(D)
                    .append(nz(r.combatCenters)).append(D)
                    .append(nz(r.wcCenters)).append(D)
                    .append(nz(r.miningCenters)).append(D)
                    .append(nz(r.fishingCenters)).append(D)
                    .append(nz(r.impsCenters)).append(D)
                    .append(offOn(r.useGlobalCombatCenters)).append(D)
                    .append(offOn(r.useGlobalWcCenters)).append(D)
                    .append(offOn(r.useGlobalMiningCenters)).append(D)
                    .append(offOn(r.useGlobalFishingCenters)).append(D)
                    .append(offOn(r.useGlobalImpsCenters)).append(D)
                    .append(nz(r.accountSwitchWorld)).append(D)
                    .append(r.accountSwitchWorldHopEnabled ? "1" : "0").append(D)
                    .append(Math.max(0, r.targetAttackLevel)).append(D)
                    .append(Math.max(0, r.targetStrengthLevel)).append(D)
                    .append(Math.max(0, r.targetDefenceLevel)).append(D)
                    .append(r.calibrateBankOnNextLogin ? "1" : "0").append(D)
                    .append(r.magicAutoUpdate ? "1" : "0").append(D)
                    .append(nz(r.impsCombatStyleOverride));
            sb.append('\n');
        }
        return sb.toString();
    }

    private static int parseIntOrZero(String value) {
        try {
            return Integer.parseInt(value == null ? "0" : value.trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    public static String buildPastedCredentials(List<ManagedJagexAccountRow> rows) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (ManagedJagexAccountRow r : rows) {
            if (r.isEmpty()) {
                continue;
            }
            if (!first) {
                sb.append("\n---\n");
            }
            first = false;
            sb.append("JX_DISPLAY_NAME=").append(r.displayName.trim()).append('\n');
            if (r.characterId != null && !r.characterId.trim().isEmpty()) {
                sb.append("JX_CHARACTER_ID=").append(r.characterId.trim()).append('\n');
            }
            if (r.sessionId != null && !r.sessionId.trim().isEmpty()) {
                sb.append("JX_SESSION_ID=").append(r.sessionId.trim()).append('\n');
            }
        }
        return sb.toString();
    }

    public static String buildEnabledCsv(List<ManagedJagexAccountRow> rows) {
        List<String> sel = new ArrayList<>();
        for (ManagedJagexAccountRow r : rows) {
            if (!r.isEmpty() && r.rotationEnabled) {
                sel.add(r.displayName.trim());
            }
        }
        return String.join(",", sel);
    }

    /** Schrijft blob + synchroniseert geplakte credentials en rotatie-lijst voor {@link AccountSwitcher}. */
    public static void persist(ConfigManager cm, List<ManagedJagexAccountRow> rows) {
        String blob = serializeRows(rows);
        String pasted = buildPastedCredentials(rows);
        String enabled = buildEnabledCsv(rows);
        cm.setConfiguration(GROUP, "managedJagexAccountsBlob", blob);
        cm.setConfiguration(GROUP, "pastedCredentials", pasted);
        cm.setConfiguration(GROUP, "enabledDisplayNames", enabled);
    }

    // --- stat snapshots (RSN / display name lowercase key) ---

    public static Map<String, AccountStatSnapshot> parseSnapshots(String blob) {
        Map<String, AccountStatSnapshot> m = new LinkedHashMap<>();
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
            if (p.length < 7) {
                continue;
            }
            try {
                AccountStatSnapshot s = new AccountStatSnapshot();
                s.totalGpApprox = Long.parseLong(p[0]);
                s.combatLevel = Integer.parseInt(p[1]);
                s.woodcutting = Integer.parseInt(p[2]);
                s.mining = Integer.parseInt(p[3]);
                s.fishing = Integer.parseInt(p[4]);
                s.prayer = Integer.parseInt(p[5]);
                s.updatedEpochMs = Long.parseLong(p[6]);
                s.hiscoreSuspectBanned = p.length >= 8 && "1".equals(p[7].trim());
                if (p.length >= 11) {
                    s.attack = parseIntOrZero(p[8]);
                    s.strength = parseIntOrZero(p[9]);
                    s.defence = parseIntOrZero(p[10]);
                }
                if (p.length >= 12) {
                    s.magic = parseIntOrZero(p[11]);
                }
                if (p.length >= 13) {
                    s.quests = parseIntOrZero(p[12]);
                }
                m.put(key, s);
            } catch (NumberFormatException ignored) {
            }
        }
        return m;
    }

    public static String serializeSnapshots(Map<String, AccountStatSnapshot> map) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, AccountStatSnapshot> e : map.entrySet()) {
            AccountStatSnapshot s = e.getValue();
            if (s == null) {
                continue;
            }
            sb.append(e.getKey()).append('=')
                    .append(s.totalGpApprox).append('|')
                    .append(s.combatLevel).append('|')
                    .append(s.woodcutting).append('|')
                    .append(s.mining).append('|')
                    .append(s.fishing).append('|')
                    .append(s.prayer).append('|')
                    .append(s.updatedEpochMs).append('|')
                    .append(s.hiscoreSuspectBanned ? "1" : "0").append('|')
                    .append(Math.max(0, s.attack)).append('|')
                    .append(Math.max(0, s.strength)).append('|')
                    .append(Math.max(0, s.defence)).append('|')
                    .append(Math.max(0, s.magic)).append('|')
                    .append(Math.max(0, s.quests)).append('\n');
        }
        return sb.toString();
    }

    public static void mergeSnapshot(ConfigManager cm, CombatBotConfig cfg, String accountKey, AccountStatSnapshot snap) {
        if (accountKey == null || accountKey.trim().isEmpty() || snap == null) {
            return;
        }
        Map<String, AccountStatSnapshot> m = parseSnapshots(cfg.accountStatSnapshotsBlob());
        m.put(accountKey.trim().toLowerCase(Locale.ROOT), snap);
        cm.setConfiguration(GROUP, "accountStatSnapshotsBlob", serializeSnapshots(m));
    }

    public static AccountStatSnapshot snapshotForRow(Map<String, AccountStatSnapshot> map, String displayName) {
        if (map == null || displayName == null) {
            return null;
        }
        return map.get(displayName.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Imps-modus: per-account override op {@link CombatBotConfig#impsCombatStyle()} (RSN = display name in tabel).
     */
    public static CombatBotConfig.ImpsCombatStyle resolveImpsCombatStyleForDisplayName(
            CombatBotConfig cfg, String displayName) {
        if (cfg == null) {
            return CombatBotConfig.ImpsCombatStyle.MELEE;
        }
        if (displayName == null || displayName.trim().isEmpty()) {
            return cfg.impsCombatStyle();
        }
        String key = displayName.trim();
        for (ManagedJagexAccountRow r : parseRows(cfg.managedJagexAccountsBlob())) {
            if (r == null || r.displayName == null) {
                continue;
            }
            if (!r.displayName.trim().equalsIgnoreCase(key)) {
                continue;
            }
            String o = r.impsCombatStyleOverride;
            if (o == null || o.trim().isEmpty()) {
                return cfg.impsCombatStyle();
            }
            try {
                return CombatBotConfig.ImpsCombatStyle.valueOf(o.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return cfg.impsCombatStyle();
            }
        }
        return cfg.impsCombatStyle();
    }

    private ManagedJagexAccountsStore() {}
}
