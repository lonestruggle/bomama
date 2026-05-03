package com.combatbot;

import net.storm.api.plugins.config.ConfigManager;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Exporteert en importeert Combat Bot-instellingen als JSON-bestand
 * (zelfde {@code combatbot}-groep als Storm-config), voor gebruik op een andere PC.
 * Accountlijsten, geplakte Jagex-credentials en re-log accounttekst worden bewust niet meegenomen.
 */
public final class CombatBotSettingsTransfer {

    public static final String EXPORT_VERSION_KEY = "_combatbotSettingsExport";
    public static final int EXPORT_VERSION = 1;

    private static final String GROUP = "combatbot";

    /** Geen export/import: gevoelige of pc-lokale accountdata. */
    private static final Set<String> NEVER_TRANSFER_KEYS;

    static {
        Set<String> nt = new HashSet<>();
        nt.add("accountList");
        nt.add("loginScreenCredentialsPath");
        nt.add("pastedCredentials");
        nt.add("enabledDisplayNames");
        nt.add("reLogoutAccount");
        nt.add("loginNow");
        nt.add("managedJagexAccountsBlob");
        nt.add("accountStatSnapshotsBlob");
        NEVER_TRANSFER_KEYS = Collections.unmodifiableSet(nt);
    }

    /** Eenmalige actie-keys: na import altijd veilig op false. */
    private static final String[] RESET_AFTER_IMPORT = {
            "loginNow",
            "switchNow",
            "impsSellNow",
            "saveTilePreset",
            "doLoadTilePreset",
            "clearTileMarkers"
    };

    private CombatBotSettingsTransfer() {
    }

    public static String exportToJson(CombatBotConfig config) {
        StringBuilder sb = new StringBuilder(16384);
        sb.append('{');
        appendInt(sb, EXPORT_VERSION_KEY, EXPORT_VERSION);
        sb.append(',');

        appendBool(sb, "botEnabled", config.botEnabled());
        sb.append(',');
        appendStr(sb, "monsterName", config.monsterName());
        sb.append(',');
        appendStr(sb, "foodChoice", config.foodChoice().name());
        sb.append(',');
        appendInt(sb, "eatPercent", config.eatPercent());
        sb.append(',');
        appendBool(sb, "disableCombatNoFood", config.disableCombatNoFood());
        sb.append(',');
        appendInt(sb, "attackRange", config.attackRange());
        sb.append(',');
        appendInt(sb, "attackDelayMin", config.attackDelayMin());
        sb.append(',');
        appendInt(sb, "attackDelayMax", config.attackDelayMax());
        sb.append(',');
        appendBool(sb, "buryBones", config.buryBones());
        sb.append(',');
        appendInt(sb, "buryBonesMinBatch", config.buryBonesMinBatch());
        sb.append(',');
        appendStr(sb, "combatCenters", config.combatCenters());
        sb.append(',');
        appendBool(sb, "accountsUseGlobalCenterListsOnly", config.accountsUseGlobalCenterListsOnly());
        sb.append(',');
        appendStr(sb, "combatStyle", config.combatStyle().name());
        sb.append(',');
        appendBool(sb, "showCombatOverlay", config.showCombatOverlay());
        sb.append(',');
        appendBool(sb, "safespotEnabled", config.safespotEnabled());
        sb.append(',');
        appendBool(sb, "pickupArrows", config.pickupArrows());
        sb.append(',');
        appendInt(sb, "pickupArrowsMinKills", config.pickupArrowsMinKills());
        sb.append(',');
        appendInt(sb, "pickupArrowsMaxKills", config.pickupArrowsMaxKills());
        sb.append(',');
        appendInt(sb, "combatArrowMin", config.combatArrowMin());
        sb.append(',');
        appendInt(sb, "combatArrowTarget", config.combatArrowTarget());
        sb.append(',');
        appendInt(sb, "combatRuneMin", config.combatRuneMin());
        sb.append(',');
        appendInt(sb, "combatRuneTarget", config.combatRuneTarget());
        sb.append(',');
        appendStr(sb, "genieLampSkill", config.genieLampSkill().name());
        sb.append(',');

        appendStr(sb, "impsCenters", config.impsCenters());
        sb.append(',');
        appendBool(sb, "impsMode", config.impsMode());
        sb.append(',');
        appendStr(sb, "impsCombatStyle", config.impsCombatStyle().name());
        sb.append(',');
        appendStr(sb, "impsMageSpell", config.impsMageSpell().name());
        sb.append(',');
        appendStr(sb, "impsLootItems", config.impsLootItems());
        sb.append(',');
        appendBool(sb, "impsScatterAshes", config.impsScatterAshes());
        sb.append(',');
        appendInt(sb, "impsBankThreshold", config.impsBankThreshold());
        sb.append(',');
        appendInt(sb, "impsMinCoins", config.impsMinCoins());
        sb.append(',');
        appendInt(sb, "impsHuntingX", config.impsHuntingX());
        sb.append(',');
        appendInt(sb, "impsHuntingY", config.impsHuntingY());
        sb.append(',');
        appendInt(sb, "impsHuntingRadius", config.impsHuntingRadius());
        sb.append(',');
        appendBool(sb, "impsAvoidScorpions", config.impsAvoidScorpions());
        sb.append(',');
        appendInt(sb, "impsScorpionLevel", config.impsScorpionLevel());
        sb.append(',');
        appendBool(sb, "showImpsOverlay", config.showImpsOverlay());
        sb.append(',');
        appendInt(sb, "impsIdleRoamSeconds", config.impsIdleRoamSeconds());
        sb.append(',');
        appendBool(sb, "impsAttackScorpions", config.impsAttackScorpions());
        sb.append(',');
        appendInt(sb, "impsScorpionZoneRadius", config.impsScorpionZoneRadius());
        sb.append(',');
        appendBool(sb, "impsGeSellEnabled", config.impsGeSellEnabled());
        sb.append(',');
        appendInt(sb, "impsGeSellAfterBanks", config.impsGeSellAfterBanks());
        sb.append(',');
        appendInt(sb, "impsGeSellPrice", config.impsGeSellPrice());
        sb.append(',');
        appendInt(sb, "impsLawRuneBuyPrice", config.impsLawRuneBuyPrice());
        sb.append(',');
        appendBool(sb, "impsTeleportBuyRunes", config.impsTeleportBuyRunes());
        sb.append(',');
        appendBool(sb, "impsUseVarrockTeleport", config.impsUseVarrockTeleport());
        sb.append(',');
        appendBool(sb, "impsUseFaladorTeleport", config.impsUseFaladorTeleport());
        sb.append(',');
        appendBool(sb, "impsUseLumbridgeTeleport", config.impsUseLumbridgeTeleport());
        sb.append(',');
        appendInt(sb, "impsStepMinDistance", config.impsStepMinDistance());
        sb.append(',');
        appendInt(sb, "impsStepMaxDistance", config.impsStepMaxDistance());
        sb.append(',');
        appendInt(sb, "impsRallyPointRadius", config.impsRallyPointRadius());
        sb.append(',');
        appendBool(sb, "impsShowRallyRadius", config.impsShowRallyRadius());
        sb.append(',');
        appendBool(sb, "impsSellNow", config.impsSellNow());
        sb.append(',');
        appendStr(sb, "impsFallbackStyle", config.impsFallbackStyle().name());
        sb.append(',');
        appendInt(sb, "impsAmmoRestockPrice", config.impsAmmoRestockPrice());
        sb.append(',');
        appendBool(sb, "impsMeleeOpeningAirStrike", config.impsMeleeOpeningAirStrike());
        sb.append(',');
        appendInt(sb, "impsMeleeOpeningAirStrikeMinDistance", config.impsMeleeOpeningAirStrikeMinDistance());
        sb.append(',');
        appendBool(sb, "impsMeleeOpeningAirStrikeDebug", config.impsMeleeOpeningAirStrikeDebug());
        sb.append(',');
        appendBool(sb, "impsStayInsideRadius", config.impsStayInsideRadius());
        sb.append(',');

        appendStr(sb, "combatTiles", config.combatTiles());
        sb.append(',');
        appendStr(sb, "wcTiles", config.wcTiles());
        sb.append(',');
        appendStr(sb, "miningTiles", config.miningTiles());
        sb.append(',');
        appendStr(sb, "fishingTiles", config.fishingTiles());
        sb.append(',');
        appendStr(sb, "tilePresetName", config.tilePresetName());
        sb.append(',');
        appendBool(sb, "saveTilePreset", config.saveTilePreset());
        sb.append(',');
        appendStr(sb, "tilePresets", config.tilePresets());
        sb.append(',');
        appendStr(sb, "loadTilePreset", config.loadTilePreset());
        sb.append(',');
        appendBool(sb, "doLoadTilePreset", config.doLoadTilePreset());
        sb.append(',');
        appendBool(sb, "clearTileMarkers", config.clearTileMarkers());
        sb.append(',');

        appendStr(sb, "lootItems", config.lootItems());
        sb.append(',');
        appendInt(sb, "lootMinValue", config.lootMinValue());
        sb.append(',');
        appendBool(sb, "lootByMinValue", config.lootByMinValue());
        sb.append(',');
        appendBool(sb, "lootOnlyOwn", config.lootOnlyOwn());
        sb.append(',');
        appendStr(sb, "specialLootItems", config.specialLootItems());
        sb.append(',');
        appendBool(sb, "lootBonesAndAshes", config.lootBonesAndAshes());
        sb.append(',');
        appendBool(sb, "lootDelayEnabled", config.lootDelayEnabled());
        sb.append(',');
        appendInt(sb, "lootDelayKills", config.lootDelayKills());
        sb.append(',');
        appendInt(sb, "lootDelayMinSeconds", config.lootDelayMinSeconds());
        sb.append(',');
        appendInt(sb, "lootDelayMaxSeconds", config.lootDelayMaxSeconds());
        sb.append(',');

        appendBool(sb, "bankWhenNoFood", config.bankWhenNoFood());
        sb.append(',');
        appendBool(sb, "bankLootedItems", config.bankLootedItems());
        sb.append(',');
        appendInt(sb, "foodAmount", config.foodAmount());
        sb.append(',');
        appendBool(sb, "combatGeFoodEnabled", config.combatGeFoodEnabled());
        sb.append(',');
        appendStr(sb, "combatGeFoodType", config.combatGeFoodType().name());
        sb.append(',');
        appendInt(sb, "combatGeFoodBasePrice", config.combatGeFoodBasePrice());
        sb.append(',');

        appendBool(sb, "wcEnabled", config.wcEnabled());
        sb.append(',');
        appendBool(sb, "wcUseSpecificTree", config.wcUseSpecificTree());
        sb.append(',');
        appendStr(sb, "wcTreeName", config.wcTreeName());
        sb.append(',');
        appendBool(sb, "wcDropLogs", config.wcDropLogs());
        sb.append(',');
        appendBool(sb, "wcFiremaking", config.wcFiremaking());
        sb.append(',');
        appendStr(sb, "wcCenters", config.wcCenters());
        sb.append(',');
        appendBool(sb, "showWcOverlay", config.showWcOverlay());
        sb.append(',');
        appendInt(sb, "wcInteractDelayMin", config.wcInteractDelayMin());
        sb.append(',');
        appendInt(sb, "wcInteractDelayMax", config.wcInteractDelayMax());
        sb.append(',');

        appendBool(sb, "miningEnabled", config.miningEnabled());
        sb.append(',');
        appendBool(sb, "miningUseSpecificOre", config.miningUseSpecificOre());
        sb.append(',');
        appendStr(sb, "miningOreName", config.miningOreName());
        sb.append(',');
        appendBool(sb, "miningDropOre", config.miningDropOre());
        sb.append(',');
        appendStr(sb, "miningCenters", config.miningCenters());
        sb.append(',');
        appendBool(sb, "showMiningOverlay", config.showMiningOverlay());
        sb.append(',');
        appendInt(sb, "miningInteractDelayMin", config.miningInteractDelayMin());
        sb.append(',');
        appendInt(sb, "miningInteractDelayMax", config.miningInteractDelayMax());
        sb.append(',');

        appendBool(sb, "fishingEnabled", config.fishingEnabled());
        sb.append(',');
        appendBool(sb, "fishingUseSpecificMethod", config.fishingUseSpecificMethod());
        sb.append(',');
        appendStr(sb, "fishingSpotName", config.fishingSpotName());
        sb.append(',');
        appendStr(sb, "fishingAction", config.fishingAction());
        sb.append(',');
        appendBool(sb, "fishingDropFish", config.fishingDropFish());
        sb.append(',');
        appendBool(sb, "fishingCookEnabled", config.fishingCookEnabled());
        sb.append(',');
        appendBool(sb, "fishingRestockEnabled", config.fishingRestockEnabled());
        sb.append(',');
        appendInt(sb, "fishingRestockAmount", config.fishingRestockAmount());
        sb.append(',');
        appendStr(sb, "fishingBaitName", config.fishingBaitName());
        sb.append(',');
        appendInt(sb, "fishingBaitMin", config.fishingBaitMin());
        sb.append(',');
        appendInt(sb, "fishingBaitTarget", config.fishingBaitTarget());
        sb.append(',');
        appendInt(sb, "fishingBaitPrice", config.fishingBaitPrice());
        sb.append(',');
        appendInt(sb, "fishingFeatherPrice", config.fishingFeatherPrice());
        sb.append(',');
        appendStr(sb, "fishingCenters", config.fishingCenters());
        sb.append(',');
        appendBool(sb, "fishingUseVarrockTeleport", config.fishingUseVarrockTeleport());
        sb.append(',');
        appendBool(sb, "showFishingOverlay", config.showFishingOverlay());
        sb.append(',');
        appendInt(sb, "fishingInteractDelayMin", config.fishingInteractDelayMin());
        sb.append(',');
        appendInt(sb, "fishingInteractDelayMax", config.fishingInteractDelayMax());
        sb.append(',');

        appendBool(sb, "geSellEnabled", config.geSellEnabled());
        sb.append(',');
        appendStr(sb, "geSellPriceMode", config.geSellPriceMode().name());
        sb.append(',');
        appendInt(sb, "geSellFixedPrice", config.geSellFixedPrice());
        sb.append(',');
        appendInt(sb, "geSellPercentBelow", config.geSellPercentBelow());
        sb.append(',');
        appendStr(sb, "geSellLootItems", config.geSellLootItems());
        sb.append(',');
        appendInt(sb, "geSellAfterBanks", config.geSellAfterBanks());
        sb.append(',');

        appendStr(sb, "startSkill", config.startSkill().name());
        sb.append(',');
        appendStr(sb, "starterTrainRegion", config.starterTrainRegion().name());
        sb.append(',');
        appendBool(sb, "combatInRotation", config.combatInRotation());
        sb.append(',');
        appendInt(sb, "rotationMinMinutes", config.rotationMinMinutes());
        sb.append(',');
        appendInt(sb, "rotationMaxMinutes", config.rotationMaxMinutes());
        sb.append(',');
        appendBool(sb, "switchNow", config.switchNow());
        sb.append(',');
        appendBool(sb, "barbLootEnabled", config.barbLootEnabled());
        sb.append(',');
        appendBool(sb, "barbLootBonfireWait", config.barbLootBonfireWait());
        sb.append(',');
        appendInt(sb, "barbLootGeAfterBanks", config.barbLootGeAfterBanks());
        sb.append(',');
        appendInt(sb, "barbLootGePercentBelow", config.barbLootGePercentBelow());
        sb.append(',');
        appendInt(sb, "barbLootGeMinCash", config.barbLootGeMinCash());
        sb.append(',');
        appendInt(sb, "barbLootGeMindRunes", config.barbLootGeMindRunes());
        sb.append(',');
        appendBool(sb, "barbLootGeBuyAirStaff", config.barbLootGeBuyAirStaff());
        sb.append(',');

        appendBool(sb, "accountSwitchEnabled", config.accountSwitchEnabled());
        sb.append(',');
        appendInt(sb, "accountMinMinutes", config.accountMinMinutes());
        sb.append(',');
        appendInt(sb, "accountMaxMinutes", config.accountMaxMinutes());
        sb.append(',');

        appendBool(sb, "reLogoutEnabled", config.reLogoutEnabled());
        sb.append(',');
        appendInt(sb, "reLogoutMinMinutes", config.reLogoutMinMinutes());
        sb.append(',');
        appendInt(sb, "reLogoutMaxMinutes", config.reLogoutMaxMinutes());
        sb.append(',');
        appendInt(sb, "reLogoutPauseMinMinutes", config.reLogoutPauseMinMinutes());
        sb.append(',');
        appendInt(sb, "reLogoutPauseMaxMinutes", config.reLogoutPauseMaxMinutes());
        sb.append(',');

        appendInt(sb, "travelReclickIntervalMs", config.travelReclickIntervalMs());
        sb.append(',');
        appendInt(sb, "travelPostClickDelayMin", config.travelPostClickDelayMin());
        sb.append(',');
        appendInt(sb, "travelPostClickDelayMax", config.travelPostClickDelayMax());
        sb.append(',');
        appendBool(sb, "travelUsePathfinderWalk", config.travelUsePathfinderWalk());
        sb.append(',');

        appendBool(sb, "antiBanEnabled", config.antiBanEnabled());
        sb.append(',');
        appendInt(sb, "antiBanFrequency", config.antiBanFrequency());
        sb.append(',');
        appendBool(sb, "cameraMovement", config.cameraMovement());
        sb.append(',');
        appendInt(sb, "cameraDurationMin", config.cameraDurationMin());
        sb.append(',');
        appendInt(sb, "cameraDurationMax", config.cameraDurationMax());
        sb.append(',');
        appendInt(sb, "mmbDragSpeedMin", config.mmbDragSpeedMin());
        sb.append(',');
        appendInt(sb, "mmbDragSpeedMax", config.mmbDragSpeedMax());
        sb.append(',');
        appendInt(sb, "mmbDragDistanceMin", config.mmbDragDistanceMin());
        sb.append(',');
        appendInt(sb, "mmbDragDistanceMax", config.mmbDragDistanceMax());
        sb.append(',');
        appendBool(sb, "idleChecks", config.idleChecks());
        sb.append(',');
        appendBool(sb, "randomMouseMovement", config.randomMouseMovement());
        sb.append(',');
        appendBool(sb, "tabGlanceEnabled", config.tabGlanceEnabled());
        sb.append(',');
        appendBool(sb, "misClickEnabled", config.misClickEnabled());
        sb.append(',');
        appendInt(sb, "misClickPercent", config.misClickPercent());
        sb.append(',');

        appendStr(sb, "discordWebhookUrl", config.discordWebhookUrl());
        sb.append(',');
        appendBool(sb, "discordScreenshotsEnabled", config.discordScreenshotsEnabled());
        sb.append(',');
        appendInt(sb, "discordScreenshotIntervalSeconds", config.discordScreenshotIntervalSeconds());
        sb.append(',');
        appendBool(sb, "discordDetailedWebhookText", config.discordDetailedWebhookText());
        sb.append(',');
        appendBool(sb, "discordRelogPausePingsEnabled", config.discordRelogPausePingsEnabled());
        sb.append(',');

        appendBool(sb, "giantsMode", config.giantsMode());
        sb.append(',');
        appendStr(sb, "giantsMonsterName", config.giantsMonsterName());
        sb.append(',');
        appendStr(sb, "giantsCombatStyle", config.giantsCombatStyle().name());
        sb.append(',');
        appendStr(sb, "giantsMageSpell", config.giantsMageSpell().name());
        sb.append(',');
        appendInt(sb, "giantsBrassKeyPriceMin", config.giantsBrassKeyPriceMin());
        sb.append(',');
        appendInt(sb, "giantsBrassKeyPriceMax", config.giantsBrassKeyPriceMax());
        sb.append(',');
        appendStr(sb, "giantsLootItems", config.giantsLootItems());
        sb.append(',');
        appendInt(sb, "giantsBankWhenLoot", config.giantsBankWhenLoot());
        sb.append(',');
        appendBool(sb, "showGiantsOverlay", config.showGiantsOverlay());
        sb.append(',');
        appendBool(sb, "giantsPreferVarrock", config.giantsPreferVarrock());
        sb.append(',');
        appendStr(sb, "webGuiUrl", config.webGuiUrl());

        sb.append('}');
        return sb.toString();
    }

    /**
     * Past alle keys uit het JSON-bestand toe. Strings met komma's/quotes worden ondersteund.
     * Na afloop worden eenmalige trigger-keys op {@code false} gezet.
     *
     * @return aantal toegepaste keys (excl. metadata)
     */
    public static int importFromJson(String json, ConfigManager configManager) {
        Map<String, String> map = parseJsonObject(json);
        int applied = 0;
        for (Map.Entry<String, String> e : map.entrySet()) {
            String key = e.getKey();
            if (EXPORT_VERSION_KEY.equals(key) || NEVER_TRANSFER_KEYS.contains(key)) {
                continue;
            }
            configManager.setConfiguration(GROUP, key, e.getValue());
            applied++;
        }
        for (String k : RESET_AFTER_IMPORT) {
            configManager.setConfiguration(GROUP, k, "false");
        }
        return applied;
    }

    private static void appendStr(StringBuilder sb, String key, String val) {
        sb.append('"').append(key).append("\":\"").append(escapeJson(val != null ? val : "")).append('"');
    }

    private static void appendInt(StringBuilder sb, String key, int val) {
        sb.append('"').append(key).append("\":").append(val);
    }

    private static void appendBool(StringBuilder sb, String key, boolean val) {
        sb.append('"').append(key).append("\":").append(val);
    }

    private static String escapeJson(String s) {
        StringBuilder o = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\':
                    o.append("\\\\");
                    break;
                case '"':
                    o.append("\\\"");
                    break;
                case '\n':
                    o.append("\\n");
                    break;
                case '\r':
                    break;
                case '\t':
                    o.append("\\t");
                    break;
                default:
                    o.append(c);
            }
        }
        return o.toString();
    }

    /** Minimale JSON object → map (alle waarden als string, nummers/booleans ongewijzigd als tekst). */
    static Map<String, String> parseJsonObject(String raw) {
        Parser p = new Parser(raw.trim());
        if (!p.consumeIf('{')) {
            throw new IllegalArgumentException("Geen geldig JSON object (verwacht '{').");
        }
        Map<String, String> map = new LinkedHashMap<>();
        p.skipWs();
        if (p.consumeIf('}')) {
            return map;
        }
        while (true) {
            p.skipWs();
            String key = p.readString();
            p.skipWs();
            if (!p.consumeIf(':')) {
                throw new IllegalArgumentException("Verwacht ':' na key");
            }
            p.skipWs();
            String value = p.readValue();
            map.put(key, value);
            p.skipWs();
            if (p.consumeIf('}')) {
                break;
            }
            if (!p.consumeIf(',')) {
                throw new IllegalArgumentException("Verwacht ',' of '}'");
            }
        }
        return map;
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        void skipWs() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                    i++;
                } else {
                    break;
                }
            }
        }

        boolean consumeIf(char c) {
            if (i < s.length() && s.charAt(i) == c) {
                i++;
                return true;
            }
            return false;
        }

        String readString() {
            if (i >= s.length() || s.charAt(i) != '"') {
                throw new IllegalArgumentException("Verwacht string (\")");
            }
            i++;
            StringBuilder b = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return b.toString();
                }
                if (c == '\\' && i < s.length()) {
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"':
                        case '\\':
                        case '/':
                            b.append(e);
                            break;
                        case 'n':
                            b.append('\n');
                            break;
                        case 'r':
                            b.append('\r');
                            break;
                        case 't':
                            b.append('\t');
                            break;
                        case 'u':
                            if (i + 4 > s.length()) {
                                throw new IllegalArgumentException("Incomplete \\u escape");
                            }
                            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default:
                            b.append(e);
                    }
                } else {
                    b.append(c);
                }
            }
            throw new IllegalArgumentException("Niet-afgesloten string");
        }

        String readValue() {
            if (i >= s.length()) {
                throw new IllegalArgumentException("Onverwacht einde");
            }
            char c = s.charAt(i);
            if (c == '"') {
                return readString();
            }
            if (c == '-' || (c >= '0' && c <= '9')) {
                return readNumber();
            }
            int start = i;
            if (s.regionMatches(i, "true", 0, 4)) {
                i += 4;
                return "true";
            }
            if (s.regionMatches(i, "false", 0, 5)) {
                i += 5;
                return "false";
            }
            if (s.regionMatches(i, "null", 0, 4)) {
                i += 4;
                return "";
            }
            throw new IllegalArgumentException("Onbekende waarde op positie " + start);
        }

        String readNumber() {
            int start = i;
            if (s.charAt(i) == '-') {
                i++;
            }
            while (i < s.length()) {
                char ch = s.charAt(i);
                if ((ch >= '0' && ch <= '9') || ch == '.' || ch == 'e' || ch == 'E' || ch == '+' || ch == '-') {
                    i++;
                } else {
                    break;
                }
            }
            return s.substring(start, i);
        }
    }
}
