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

    public static final String RANGED_AMMO_DEFAULT = "Bronze arrow";
    /** Per account: beste pijl in bank (Rune → … → Bronze). */
    public static final String RANGED_AMMO_BEST = "BEST";
    public static final String RANGED_AMMO_BEST_LABEL = "Beste in bank";
    public static final String[] RANGED_AMMO_TYPE_CHOICES = {
            "Bronze arrow",
            "Iron arrow",
            "Steel arrow",
            "Mithril arrow",
            "Adamant arrow",
            "Rune arrow"
    };
    private static final String[] RANGED_ARROWS_BEST_FIRST = {
            "Rune arrow", "Adamant arrow", "Mithril arrow", "Steel arrow", "Iron arrow", "Bronze arrow"
    };

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
        /**
         * Volgorde waarin Att/Str/Def naar hun target getraind worden zolang ze nog niet bereikt zijn.
         * Leeg of {@code "ATT_STR_DEF"} = klassiek (Att eerst, dan Str, dan Def).
         * Andere waardes: {@code "STR_ATT_DEF"}, {@code "DEF_ATT_STR"},
         * {@code "LOWEST_FIRST"} (laagste absolute lvl eerst),
         * {@code "LOWEST_PCT_FIRST"} (laagste % onder target eerst).
         */
        public String targetMeleePriority = "";
        /** Forceer bij volgende login een bank-open + snapshot update voor dit account. */
        public boolean calibrateBankOnNextLogin = false;
        /** Auto-upgrade MAGE naar Fire Strike (met GE-koop/sell loot indien nodig). */
        public boolean magicAutoUpdate = false;
        /**
         * Imps combat style: leeg = volg globale {@link CombatBotConfig#impsCombatStyle()};
         * anders {@code MELEE}, {@code RANGED} of {@code MAGE}.
         */
        public String impsCombatStyleOverride = "";
        /**
         * Giants combat style: leeg = volg globale {@link CombatBotConfig#giantsCombatStyle()};
         * anders {@code MELEE}, {@code RANGED} of {@code MAGE}.
         */
        public String giantsCombatStyleOverride = "";
        /**
         * Aan (standaard): bij dit account altijd de volledige center-lijsten van de Centers-tab;
         * subsets uit <b>Locaties…</b> worden genegeerd. Uit: per skill gelden de gekozen subset-locaties.
         */
        public boolean useGlobalCenterListsOnly = true;
        /**
         * Giants in rotatie: leeg = volg globale {@link CombatBotConfig#giantsMode()};
         * {@code "1"} = altijd aan voor dit account; {@code "0"} = altijd uit.
         */
        public String giantsModeOverride = "";
        /**
         * Imps Mode (zonder radius — gebruikt {@link CombatBotConfig#impsHuntingX}/Y/radius):
         * leeg = volg globale {@link CombatBotConfig#impsMode()};
         * {@code "1"} = altijd aan voor dit account; {@code "0"} = altijd uit voor dit account.
         * <p>Werkt los van het Imps-centers blok: je kunt centers uit hebben en hier "1" zetten,
         * dan gebruikt de bot voor dit account de Hunting X/Y zone.
         */
        public String impsModeOverride = "";
        /**
         * Imps vs Giants in rotatie: leeg = beide mogen (volgens globale vinken + centers);
         * {@code IMPS} = geen Giants voor dit account; {@code GIANTS} = geen Imps voor dit account.
         */
        public String impsGiantsFocus = "";
        /**
         * Als {@code true}: rotatie gebruikt alleen de aangevinkte skills hieronder (Combat t/m Giants),
         * onafhankelijk van globale skill-vinken op andere tabs. Barbarian + Barb-loot blijven globaal.
         */
        public boolean rotationUseCustomProfile = false;
        public boolean rotationPickCombat = true;
        public boolean rotationPickWc = true;
        public boolean rotationPickMining = true;
        public boolean rotationPickFishing = true;
        public boolean rotationPickImps = true;
        public boolean rotationPickGiants = true;
        /**
         * Start skill voor dit account als {@link #useGlobalCenterListsOnly} uit staat (eigen center-subsets).
         * Leeg = volg globale {@link CombatBotConfig#startSkill()}; anders {@link CombatBotConfig.StartSkill} name().
         */
        public String startSkillOverride = "";

        /**
         * Per-account GE-shop policy (welke categorieën mag de bot kopen + per-categorie cap).
         * Compact format: zie {@link GeShopPolicy#serializeBlob}. Leeg = defaults (alles aan).
         */
        public String geBuyTogglesBlob = "";

        /**
         * Aan: gebruik Chronicle (Diango book) om naar Varrock te teleporteren in plaats van Magic 25
         * teleport. Geldt vooral voor low-magic accounts. Aan/uit per account; default uit zodat huidig
         * gedrag (alleen Magic-tp/walk) niet stiekem verandert.
         */
        public boolean useChronicleForVarrock = false;

        /**
         * Per-center gedrag (drop/bank, FM, cook) — zie {@link AccountCenterBehaviorStore}.
         * Lege regel in blob = volg globale skill-instelling voor die tile.
         */
        public String wcCenterBehaviorsBlob = "";
        public String miningCenterBehaviorsBlob = "";
        public String fishingCenterBehaviorsBlob = "";

        /**
         * Ranged ammo voor dit account (Combat, Imps, Giants, …): vaste pijl (bv. Bronze arrow)
         * of {@link #RANGED_AMMO_BEST} voor beste in bank eerst.
         */
        public String rangedAmmoType = RANGED_AMMO_DEFAULT;
        /**
         * Imps concurrentie-hop op Karamja: alleen actief als globaal
         * {@link CombatBotConfig#impsCompetitorWorldHop()} aan staat.
         */
        public boolean impsCompetitorWorldHopEnabled = true;

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
            r.targetMeleePriority = targetMeleePriority != null ? targetMeleePriority : "";
            r.calibrateBankOnNextLogin = calibrateBankOnNextLogin;
            r.magicAutoUpdate = magicAutoUpdate;
            r.impsCombatStyleOverride = impsCombatStyleOverride != null ? impsCombatStyleOverride : "";
            r.giantsCombatStyleOverride = giantsCombatStyleOverride != null ? giantsCombatStyleOverride : "";
            r.useGlobalCenterListsOnly = useGlobalCenterListsOnly;
            r.giantsModeOverride = giantsModeOverride != null ? giantsModeOverride : "";
            r.impsModeOverride = impsModeOverride != null ? impsModeOverride : "";
            r.impsGiantsFocus = impsGiantsFocus != null ? impsGiantsFocus : "";
            r.rotationUseCustomProfile = rotationUseCustomProfile;
            r.rotationPickCombat = rotationPickCombat;
            r.rotationPickWc = rotationPickWc;
            r.rotationPickMining = rotationPickMining;
            r.rotationPickFishing = rotationPickFishing;
            r.rotationPickImps = rotationPickImps;
            r.rotationPickGiants = rotationPickGiants;
            r.startSkillOverride = startSkillOverride != null ? startSkillOverride : "";
            r.geBuyTogglesBlob = geBuyTogglesBlob != null ? geBuyTogglesBlob : "";
            r.useChronicleForVarrock = useChronicleForVarrock;
            r.wcCenterBehaviorsBlob = wcCenterBehaviorsBlob != null ? wcCenterBehaviorsBlob : "";
            r.miningCenterBehaviorsBlob = miningCenterBehaviorsBlob != null ? miningCenterBehaviorsBlob : "";
            r.fishingCenterBehaviorsBlob = fishingCenterBehaviorsBlob != null ? fishingCenterBehaviorsBlob : "";
            r.rangedAmmoType = normalizeRangedAmmoType(rangedAmmoType);
            r.impsCompetitorWorldHopEnabled = impsCompetitorWorldHopEnabled;
            return r;
        }
    }

    public static String normalizeRangedAmmoType(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return RANGED_AMMO_DEFAULT;
        }
        String t = raw.trim();
        if (RANGED_AMMO_BEST.equalsIgnoreCase(t) || "best".equalsIgnoreCase(t)
                || t.equalsIgnoreCase(RANGED_AMMO_BEST_LABEL)) {
            return RANGED_AMMO_BEST;
        }
        for (String choice : RANGED_AMMO_TYPE_CHOICES) {
            if (choice.equalsIgnoreCase(t)) {
                return choice;
            }
        }
        return RANGED_AMMO_DEFAULT;
    }

    public static String rangedAmmoUiLabel(String normalized) {
        String n = normalizeRangedAmmoType(normalized);
        return RANGED_AMMO_BEST.equals(n) ? RANGED_AMMO_BEST_LABEL : n;
    }

    public static String rangedAmmoFromUiLabel(String uiLabel) {
        if (uiLabel == null || uiLabel.trim().isEmpty()) {
            return RANGED_AMMO_DEFAULT;
        }
        if (RANGED_AMMO_BEST_LABEL.equalsIgnoreCase(uiLabel.trim())
                || uiLabel.toLowerCase(Locale.ROOT).contains("beste in bank")) {
            return RANGED_AMMO_BEST;
        }
        return normalizeRangedAmmoType(uiLabel);
    }

    public static String resolveRangedAmmoTypeForDisplayName(CombatBotConfig cfg, String displayName) {
        if (cfg == null || displayName == null || displayName.trim().isEmpty()) {
            return RANGED_AMMO_DEFAULT;
        }
        ManagedJagexAccountRow row = findRowForDisplayName(cfg, displayName);
        if (row != null) {
            return normalizeRangedAmmoType(row.rangedAmmoType);
        }
        return RANGED_AMMO_DEFAULT;
    }

    public static boolean useBestRangedAmmoForDisplayName(CombatBotConfig cfg, String displayName) {
        return RANGED_AMMO_BEST.equals(resolveRangedAmmoTypeForDisplayName(cfg, displayName));
    }

    /** Volgorde voor bank-withdraw / GE (één type of beste-eerst). */
    public static String[] rangedAmmoWithdrawOrder(CombatBotConfig cfg, String displayName) {
        String n = resolveRangedAmmoTypeForDisplayName(cfg, displayName);
        if (RANGED_AMMO_BEST.equals(n)) {
            return RANGED_ARROWS_BEST_FIRST.clone();
        }
        return new String[] {n};
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
                if (p.length >= 24) {
                    r.useGlobalCenterListsOnly = "1".equals(get(p, 23));
                } else {
                    r.useGlobalCenterListsOnly = true;
                }
                if (p.length >= 25) {
                    r.giantsModeOverride = get(p, 24);
                } else {
                    r.giantsModeOverride = "";
                }
                if (p.length >= 26) {
                    r.impsGiantsFocus = get(p, 25);
                } else {
                    r.impsGiantsFocus = "";
                }
                if (p.length >= 27) {
                    r.rotationUseCustomProfile = "1".equals(get(p, 26));
                }
                if (p.length >= 33) {
                    r.rotationPickCombat = "1".equals(get(p, 27));
                    r.rotationPickWc = "1".equals(get(p, 28));
                    r.rotationPickMining = "1".equals(get(p, 29));
                    r.rotationPickFishing = "1".equals(get(p, 30));
                    r.rotationPickImps = "1".equals(get(p, 31));
                    r.rotationPickGiants = "1".equals(get(p, 32));
                }
                if (p.length >= 34) {
                    r.startSkillOverride = get(p, 33);
                }
                if (p.length >= 35) {
                    r.giantsCombatStyleOverride = get(p, 34);
                } else {
                    r.giantsCombatStyleOverride = "";
                }
                if (p.length >= 36) {
                    r.targetMeleePriority = get(p, 35);
                }
                if (p.length >= 37) {
                    r.impsModeOverride = get(p, 36);
                } else {
                    r.impsModeOverride = "";
                }
                if (p.length >= 38) {
                    r.geBuyTogglesBlob = get(p, 37);
                } else {
                    r.geBuyTogglesBlob = "";
                }
                if (p.length >= 39) {
                    r.useChronicleForVarrock = "1".equals(get(p, 38));
                } else {
                    r.useChronicleForVarrock = false;
                }
                if (p.length >= 40) {
                    r.wcCenterBehaviorsBlob = get(p, 39);
                }
                if (p.length >= 41) {
                    r.miningCenterBehaviorsBlob = get(p, 40);
                }
                if (p.length >= 42) {
                    r.fishingCenterBehaviorsBlob = get(p, 41);
                }
                if (p.length >= 43) {
                    r.rangedAmmoType = normalizeRangedAmmoType(get(p, 42));
                }
                if (p.length >= 44) {
                    r.impsCompetitorWorldHopEnabled = "1".equals(get(p, 43));
                }
            } else {
                r.useGlobalCombatCenters = !nonBlankCenter(r.combatCenters);
                r.useGlobalWcCenters = !nonBlankCenter(r.wcCenters);
                r.useGlobalMiningCenters = !nonBlankCenter(r.miningCenters);
                r.useGlobalFishingCenters = !nonBlankCenter(r.fishingCenters);
                r.useGlobalImpsCenters = !nonBlankCenter(r.impsCenters);
                r.useGlobalCenterListsOnly = true;
                r.giantsModeOverride = "";
                r.impsModeOverride = "";
                r.impsGiantsFocus = "";
                r.rotationUseCustomProfile = false;
                r.startSkillOverride = "";
                r.giantsCombatStyleOverride = "";
                r.geBuyTogglesBlob = "";
                r.useChronicleForVarrock = false;
                r.wcCenterBehaviorsBlob = "";
                r.miningCenterBehaviorsBlob = "";
                r.fishingCenterBehaviorsBlob = "";
                r.rangedAmmoType = RANGED_AMMO_DEFAULT;
                r.impsCompetitorWorldHopEnabled = true;
            }
            if (!r.isEmpty()) {
                out.add(r);
            }
        }
        return out;
    }

    /** Zoekt accountrij op display name (zelfde normalisatie als account-switch). */
    public static ManagedJagexAccountRow findRowForDisplayName(CombatBotConfig cfg, String displayName) {
        if (cfg == null || displayName == null || displayName.trim().isEmpty()) {
            return null;
        }
        String norm = JagexCredentialsHelper.normalizeDisplayNameForMatch(displayName.trim());
        for (ManagedJagexAccountRow r : parseRows(cfg.managedJagexAccountsBlob())) {
            if (r == null || r.displayName == null || r.displayName.trim().isEmpty()) {
                continue;
            }
            String rn = JagexCredentialsHelper.normalizeDisplayNameForMatch(r.displayName);
            if (rn.equalsIgnoreCase(norm)) {
                return r;
            }
        }
        return null;
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
                    .append(nz(r.impsCombatStyleOverride)).append(D)
                    .append(offOn(r.useGlobalCenterListsOnly)).append(D)
                    .append(nz(r.giantsModeOverride)).append(D)
                    .append(nz(r.impsGiantsFocus)).append(D)
                    .append(offOn(r.rotationUseCustomProfile)).append(D)
                    .append(offOn(r.rotationPickCombat)).append(D)
                    .append(offOn(r.rotationPickWc)).append(D)
                    .append(offOn(r.rotationPickMining)).append(D)
                    .append(offOn(r.rotationPickFishing)).append(D)
                    .append(offOn(r.rotationPickImps)).append(D)
                    .append(offOn(r.rotationPickGiants)).append(D)
                    .append(nz(r.startSkillOverride)).append(D)
                    .append(nz(r.giantsCombatStyleOverride)).append(D)
                    .append(nz(r.targetMeleePriority)).append(D)
                    .append(nz(r.impsModeOverride)).append(D)
                    .append(nz(r.geBuyTogglesBlob)).append(D)
                    .append(r.useChronicleForVarrock ? "1" : "0").append(D)
                    .append(nz(r.wcCenterBehaviorsBlob)).append(D)
                    .append(nz(r.miningCenterBehaviorsBlob)).append(D)
                    .append(nz(r.fishingCenterBehaviorsBlob)).append(D)
                    .append(nz(normalizeRangedAmmoType(r.rangedAmmoType))).append(D)
                    .append(r.impsCompetitorWorldHopEnabled ? "1" : "0");
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
    public static CombatBotConfig.ImpsCombatStyle resolveGiantsCombatStyleForDisplayName(
            CombatBotConfig cfg, String displayName) {
        if (cfg == null) {
            return CombatBotConfig.ImpsCombatStyle.MELEE;
        }
        if (displayName == null || displayName.trim().isEmpty()) {
            return cfg.giantsCombatStyle();
        }
        String key = displayName.trim();
        for (ManagedJagexAccountRow r : parseRows(cfg.managedJagexAccountsBlob())) {
            if (r == null || r.displayName == null) {
                continue;
            }
            if (!r.displayName.trim().equalsIgnoreCase(key)) {
                continue;
            }
            String o = r.giantsCombatStyleOverride;
            if (o == null || o.trim().isEmpty()) {
                return cfg.giantsCombatStyle();
            }
            try {
                return CombatBotConfig.ImpsCombatStyle.valueOf(o.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return cfg.giantsCombatStyle();
            }
        }
        return cfg.giantsCombatStyle();
    }

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

    /**
     * Imps in rotatie (normaal: {@code impsMode} + actieve imps-centers), tenzij dit account alleen Giants wil.
     * Bij {@link ManagedJagexAccountRow#rotationUseCustomProfile} en aangevinkt Imps: standaard <b>aan</b> zonder
     * globale Imps-tab; alleen het per-account rotatie-vinkje beslist dan.
     */
    public static boolean resolveImpsInRotationForDisplayName(CombatBotConfig cfg, String displayName) {
        if (cfg == null) {
            return false;
        }
        boolean base = CenterManager.countActive(cfg.impsCenters()) > 0 || cfg.impsMode();
        if (displayName == null || displayName.trim().isEmpty()) {
            return base;
        }
        String key = displayName.trim();
        for (ManagedJagexAccountRow r : parseRows(cfg.managedJagexAccountsBlob())) {
            if (r == null || r.displayName == null) {
                continue;
            }
            if (!r.displayName.trim().equalsIgnoreCase(key)) {
                continue;
            }
            // Per-account "Imps Mode (zonder radius)" override: leeg = volg verder de normale logica;
            // "1" = forceer Imps aan voor dit account; "0" = forceer uit. Werkt zowel voor custom
            // profile als voor accounts die globale lijsten volgen.
            String impsOv = r.impsModeOverride != null ? r.impsModeOverride.trim() : "";
            if ("1".equals(impsOv)) {
                return true;
            }
            if ("0".equals(impsOv)) {
                return false;
            }
            if (r.rotationUseCustomProfile) {
                return r.rotationPickImps;
            }
            String focus = r.impsGiantsFocus != null ? r.impsGiantsFocus.trim() : "";
            if ("GIANTS".equalsIgnoreCase(focus)) {
                return false;
            }
            return base;
        }
        return base;
    }

    /**
     * Giants-modus: per-account override op {@link CombatBotConfig#giantsMode()} (RSN = display name in tabel).
     * Bij {@link ManagedJagexAccountRow#rotationUseCustomProfile}: {@code rotationPickGiants} = in rotatie (zoals Imps);
     * alleen {@code giantsModeOverride} "0"/"1" forceert uit/aan. Zonder custom profiel: Giants-tab + override.
     */
    public static boolean resolveGiantsModeForDisplayName(CombatBotConfig cfg, String displayName) {
        if (cfg == null) {
            return false;
        }
        if (displayName == null || displayName.trim().isEmpty()) {
            return cfg.giantsMode();
        }
        String key = displayName.trim();
        for (ManagedJagexAccountRow r : parseRows(cfg.managedJagexAccountsBlob())) {
            if (r == null || r.displayName == null) {
                continue;
            }
            if (!r.displayName.trim().equalsIgnoreCase(key)) {
                continue;
            }
            if (r.rotationUseCustomProfile) {
                if (!r.rotationPickGiants) {
                    return false;
                }
                String oCustom = r.giantsModeOverride;
                if (oCustom != null && !oCustom.trim().isEmpty()) {
                    String tc = oCustom.trim();
                    if ("1".equals(tc) || "true".equalsIgnoreCase(tc) || "on".equalsIgnoreCase(tc)) {
                        return true;
                    }
                    if ("0".equals(tc) || "false".equalsIgnoreCase(tc) || "off".equalsIgnoreCase(tc)) {
                        return false;
                    }
                }
                // "Giants — in rotatie" aan = Giants in enabledSkills (niet afhankelijk van globale Giants-tab)
                return true;
            }
            String focus = r.impsGiantsFocus != null ? r.impsGiantsFocus.trim() : "";
            if ("IMPS".equalsIgnoreCase(focus)) {
                return false;
            }
            String o = r.giantsModeOverride;
            if (o == null || o.trim().isEmpty()) {
                return cfg.giantsMode();
            }
            String t = o.trim();
            if ("1".equals(t) || "true".equalsIgnoreCase(t) || "on".equalsIgnoreCase(t)) {
                return true;
            }
            if ("0".equals(t) || "false".equalsIgnoreCase(t) || "off".equalsIgnoreCase(t)) {
                return false;
            }
            return cfg.giantsMode();
        }
        return cfg.giantsMode();
    }

    private ManagedJagexAccountsStore() {}
}
