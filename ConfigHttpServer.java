package com.combatbot;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Eenvoudige HTTP server op poort 43120.
 * - GET /config.json → huidige config als JSON (voor React GUI sync)
 * - GET / → redirect naar de web GUI URL
 * - CORS headers voor cross-origin requests vanuit de React app
 */
public class ConfigHttpServer {

    private HttpServer server;
    private final CombatBotConfig config;
    private final net.storm.api.plugins.config.ConfigManager configManager;
    private volatile boolean running = false;

    public ConfigHttpServer(CombatBotConfig config, net.storm.api.plugins.config.ConfigManager configManager) {
        this.config = config;
        this.configManager = configManager;
    }

    public void start() {
        try {
            server = HttpServer.create(new InetSocketAddress(43120), 0);
            server.createContext("/config.json", new ConfigHandler());
            server.createContext("/", new RootHandler());
            server.setExecutor(null);
            server.start();
            running = true;
            System.out.println("[CombatBot] HTTP server gestart op poort 43120");
        } catch (IOException e) {
            System.err.println("[CombatBot] Kan HTTP server niet starten: " + e.getMessage());
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            running = false;
            System.out.println("[CombatBot] HTTP server gestopt");
        }
    }

    public boolean isRunning() {
        return running;
    }

    private void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
    }

    /**
     * Serveert de huidige config als JSON.
     */
    private class ConfigHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);

            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            // POST = update config vanuit web GUI
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                byte[] body = exchange.getRequestBody().readAllBytes();
                String json = new String(body, StandardCharsets.UTF_8);
                applyJsonConfig(json);
                String response = "{\"status\":\"ok\"}";
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes(StandardCharsets.UTF_8));
                }
                return;
            }

            String json = buildConfigJson();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, json.getBytes(StandardCharsets.UTF_8).length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    /**
     * Root handler: serveert een simpele HTML pagina met redirect naar de web GUI.
     */
    private class RootHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);

            String guiUrl = config.webGuiUrl();
            if (guiUrl == null || guiUrl.isEmpty()) {
                guiUrl = "https://id-preview--189c9144-0c91-40c6-81e5-f3542a0829c4.lovable.app";
            }

            String html = "<!DOCTYPE html><html><head><meta charset='utf-8'>"
                    + "<title>Combat Bot</title>"
                    + "<meta http-equiv='refresh' content='0;url=" + guiUrl + "'>"
                    + "<style>body{background:#1a1a2e;color:#e0e0e0;font-family:Arial,sans-serif;display:flex;"
                    + "justify-content:center;align-items:center;height:100vh;margin:0;}"
                    + "a{color:#4ade80;font-size:18px;}</style></head><body>"
                    + "<div><h2>⚔ Combat Bot</h2>"
                    + "<p>Je wordt doorgestuurd naar de <a href='" + guiUrl + "'>Web GUI</a>...</p>"
                    + "<p style='font-size:12px;color:#888;'>Als het niet automatisch opent, klik op de link hierboven.</p>"
                    + "</div></body></html>";

            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    /** Bouw een JSON string van alle config waarden. */
    private String buildConfigJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        appendBool(sb, "botEnabled", config.botEnabled()); sb.append(",");
        appendStr(sb, "monsterName", config.monsterName()); sb.append(",");
        appendStr(sb, "foodChoice", config.foodChoice().name()); sb.append(",");
        appendInt(sb, "eatPercent", config.eatPercent()); sb.append(",");
        appendInt(sb, "attackRange", config.attackRange()); sb.append(",");
        appendBool(sb, "disableCombatNoFood", config.disableCombatNoFood()); sb.append(",");
        appendBool(sb, "buryBones", config.buryBones()); sb.append(",");
        appendInt(sb, "buryBonesMinBatch", config.buryBonesMinBatch()); sb.append(",");
        appendBool(sb, "safespotEnabled", config.safespotEnabled()); sb.append(",");
        appendBool(sb, "showCombatOverlay", config.showCombatOverlay()); sb.append(",");
        appendStr(sb, "lootItems", config.lootItems()); sb.append(",");
        appendInt(sb, "lootMinValue", config.lootMinValue()); sb.append(",");
        appendBool(sb, "lootByMinValue", config.lootByMinValue()); sb.append(",");
        appendBool(sb, "lootOnlyOwn", config.lootOnlyOwn()); sb.append(",");
        appendStr(sb, "specialLootItems", config.specialLootItems()); sb.append(",");
        appendBool(sb, "lootBonesAndAshes", config.lootBonesAndAshes()); sb.append(",");
        appendBool(sb, "bankWhenNoFood", config.bankWhenNoFood()); sb.append(",");
        appendBool(sb, "bankLootedItems", config.bankLootedItems()); sb.append(",");
        appendInt(sb, "foodAmount", config.foodAmount()); sb.append(",");
        appendBool(sb, "wcEnabled", config.wcEnabled()); sb.append(",");
        appendBool(sb, "wcUseSpecificTree", config.wcUseSpecificTree()); sb.append(",");
        appendStr(sb, "wcTreeName", config.wcTreeName()); sb.append(",");
        appendBool(sb, "wcDropLogs", config.wcDropLogs()); sb.append(",");
        appendBool(sb, "wcFiremaking", config.wcFiremaking()); sb.append(",");
        appendBool(sb, "showWcOverlay", config.showWcOverlay()); sb.append(",");
        appendBool(sb, "miningEnabled", config.miningEnabled()); sb.append(",");
        appendBool(sb, "miningUseSpecificOre", config.miningUseSpecificOre()); sb.append(",");
        appendStr(sb, "miningOreName", config.miningOreName()); sb.append(",");
        appendBool(sb, "miningDropOre", config.miningDropOre()); sb.append(",");
        appendBool(sb, "showMiningOverlay", config.showMiningOverlay()); sb.append(",");
        appendBool(sb, "fishingEnabled", config.fishingEnabled()); sb.append(",");
        appendBool(sb, "fishingUseSpecificMethod", config.fishingUseSpecificMethod()); sb.append(",");
        appendStr(sb, "fishingSpotName", config.fishingSpotName()); sb.append(",");
        appendStr(sb, "fishingAction", config.fishingAction()); sb.append(",");
        appendBool(sb, "fishingDropFish", config.fishingDropFish()); sb.append(",");
        appendBool(sb, "showFishingOverlay", config.showFishingOverlay()); sb.append(",");
        appendStr(sb, "startSkill", config.startSkill().name()); sb.append(",");
        appendStr(sb, "starterTrainRegion", config.starterTrainRegion().name()); sb.append(",");
        appendInt(sb, "rotationMinMinutes", config.rotationMinMinutes()); sb.append(",");
        appendInt(sb, "rotationMaxMinutes", config.rotationMaxMinutes()); sb.append(",");
        appendBool(sb, "barbLootEnabled", config.barbLootEnabled()); sb.append(",");
        appendBool(sb, "barbLootBonfireWait", config.barbLootBonfireWait()); sb.append(",");
        appendInt(sb, "barbLootGeAfterBanks", config.barbLootGeAfterBanks()); sb.append(",");
        appendInt(sb, "barbLootGePercentBelow", config.barbLootGePercentBelow()); sb.append(",");
        appendInt(sb, "barbLootGeMinCash", config.barbLootGeMinCash()); sb.append(",");
        appendInt(sb, "barbLootGeMindRunes", config.barbLootGeMindRunes()); sb.append(",");
        appendBool(sb, "barbLootGeBuyAirStaff", config.barbLootGeBuyAirStaff()); sb.append(",");
        appendBool(sb, "switchNow", config.switchNow()); sb.append(",");
        appendBool(sb, "lootDelayEnabled", config.lootDelayEnabled()); sb.append(",");
        appendInt(sb, "lootDelayKills", config.lootDelayKills()); sb.append(",");
        appendInt(sb, "lootDelayMinSeconds", config.lootDelayMinSeconds()); sb.append(",");
        appendInt(sb, "lootDelayMaxSeconds", config.lootDelayMaxSeconds()); sb.append(",");
        appendBool(sb, "accountSwitchEnabled", config.accountSwitchEnabled()); sb.append(",");
        appendInt(sb, "accountMinMinutes", config.accountMinMinutes()); sb.append(",");
        appendInt(sb, "accountMaxMinutes", config.accountMaxMinutes()); sb.append(",");
        appendBool(sb, "antiBanEnabled", config.antiBanEnabled()); sb.append(",");
        appendInt(sb, "antiBanFrequency", config.antiBanFrequency()); sb.append(",");
        appendBool(sb, "cameraMovement", config.cameraMovement()); sb.append(",");
        appendInt(sb, "cameraDurationMin", config.cameraDurationMin()); sb.append(",");
        appendInt(sb, "cameraDurationMax", config.cameraDurationMax()); sb.append(",");
        appendBool(sb, "idleChecks", config.idleChecks()); sb.append(",");
        appendBool(sb, "randomMouseMovement", config.randomMouseMovement()); sb.append(",");
        appendBool(sb, "tabGlanceEnabled", config.tabGlanceEnabled()); sb.append(",");
        appendBool(sb, "misClickEnabled", config.misClickEnabled()); sb.append(",");
        appendInt(sb, "misClickPercent", config.misClickPercent()); sb.append(",");
        appendBool(sb, "impsMode", config.impsMode()); sb.append(",");
        // Imps-specifieke settings naar web GUI
        appendStr(sb, "impsCombatStyle", config.impsCombatStyle().name()); sb.append(",");
        appendStr(sb, "impsMageSpell", config.impsMageSpell().name()); sb.append(",");
        appendStr(sb, "impsLootItems", config.impsLootItems()); sb.append(",");
        appendBool(sb, "impsScatterAshes", config.impsScatterAshes()); sb.append(",");
        appendInt(sb, "impsBankThreshold", config.impsBankThreshold()); sb.append(",");
        appendInt(sb, "impsMinCoins", config.impsMinCoins()); sb.append(",");
        appendInt(sb, "impsHuntingX", config.impsHuntingX()); sb.append(",");
        appendInt(sb, "impsHuntingY", config.impsHuntingY()); sb.append(",");
        appendInt(sb, "impsHuntingRadius", config.impsHuntingRadius()); sb.append(",");
        appendBool(sb, "impsAvoidScorpions", config.impsAvoidScorpions()); sb.append(",");
        appendInt(sb, "impsScorpionLevel", config.impsScorpionLevel()); sb.append(",");
        appendBool(sb, "showImpsOverlay", config.showImpsOverlay()); sb.append(",");
        appendInt(sb, "impsIdleRoamSeconds", config.impsIdleRoamSeconds()); sb.append(",");
        appendBool(sb, "impsAttackScorpions", config.impsAttackScorpions()); sb.append(",");
        appendInt(sb, "impsScorpionZoneRadius", config.impsScorpionZoneRadius()); sb.append(",");
        appendBool(sb, "impsGeSellEnabled", config.impsGeSellEnabled()); sb.append(",");
        appendInt(sb, "impsGeSellAfterBanks", config.impsGeSellAfterBanks()); sb.append(",");
        appendInt(sb, "impsGeSellPrice", config.impsGeSellPrice()); sb.append(",");
        appendInt(sb, "impsLawRuneBuyPrice", config.impsLawRuneBuyPrice()); sb.append(",");
        appendBool(sb, "impsTeleportBuyRunes", config.impsTeleportBuyRunes()); sb.append(",");
        appendBool(sb, "impsUseVarrockTeleport", config.impsUseVarrockTeleport()); sb.append(",");
        appendBool(sb, "impsUseFaladorTeleport", config.impsUseFaladorTeleport()); sb.append(",");
        appendBool(sb, "impsUseLumbridgeTeleport", config.impsUseLumbridgeTeleport()); sb.append(",");
        appendBool(sb, "impsMeleeOpeningAirStrike", config.impsMeleeOpeningAirStrike()); sb.append(",");
        appendInt(sb, "impsMeleeOpeningAirStrikeMinDistance", config.impsMeleeOpeningAirStrikeMinDistance()); sb.append(",");
        appendBool(sb, "impsMeleeOpeningAirStrikeDebug", config.impsMeleeOpeningAirStrikeDebug()); sb.append(",");
        appendInt(sb, "impsStepMinDistance", config.impsStepMinDistance()); sb.append(",");
        appendInt(sb, "impsStepMaxDistance", config.impsStepMaxDistance()); sb.append(",");
        appendInt(sb, "impsRallyPointRadius", config.impsRallyPointRadius()); sb.append(",");
        appendBool(sb, "impsShowRallyRadius", config.impsShowRallyRadius()); sb.append(",");
        appendBool(sb, "impsSellNow", config.impsSellNow()); sb.append(",");
        appendStr(sb, "combatCenters", config.combatCenters()); sb.append(",");
        appendStr(sb, "wcCenters", config.wcCenters()); sb.append(",");
        appendStr(sb, "miningCenters", config.miningCenters()); sb.append(",");
        appendStr(sb, "fishingCenters", config.fishingCenters()); sb.append(",");
        appendStr(sb, "impsCenters", config.impsCenters()); sb.append(",");
        appendBool(sb, "accountsUseGlobalCenterListsOnly", config.accountsUseGlobalCenterListsOnly()); sb.append(",");
        // Giants Mode
        appendBool(sb, "giantsMode", config.giantsMode()); sb.append(",");
        appendStr(sb, "giantsMonsterName", config.giantsMonsterName()); sb.append(",");
        appendStr(sb, "giantsCombatStyle", config.giantsCombatStyle().name()); sb.append(",");
        appendStr(sb, "giantsMageSpell", config.giantsMageSpell().name()); sb.append(",");
        appendInt(sb, "giantsBrassKeyPriceMin", config.giantsBrassKeyPriceMin()); sb.append(",");
        appendInt(sb, "giantsBrassKeyPriceMax", config.giantsBrassKeyPriceMax()); sb.append(",");
        appendStr(sb, "giantsLootItems", config.giantsLootItems()); sb.append(",");
        appendInt(sb, "giantsBankWhenLoot", config.giantsBankWhenLoot()); sb.append(",");
        appendBool(sb, "showGiantsOverlay", config.showGiantsOverlay()); sb.append(",");
        appendBool(sb, "giantsPreferVarrock", config.giantsPreferVarrock()); sb.append(",");
        appendInt(sb, "travelReclickIntervalMs", config.travelReclickIntervalMs()); sb.append(",");
        appendInt(sb, "travelPostClickDelayMin", config.travelPostClickDelayMin()); sb.append(",");
        appendInt(sb, "travelPostClickDelayMax", config.travelPostClickDelayMax()); sb.append(",");
        appendBool(sb, "travelUsePathfinderWalk", config.travelUsePathfinderWalk());
        sb.append("}");
        return sb.toString();
    }

    /** Pas inkomende JSON config toe via stormConfigManager. */
    private void applyJsonConfig(String json) {
        // Simpele key-value parser (geen externe JSON lib nodig)
        try {
            json = json.trim();
            if (json.startsWith("{")) json = json.substring(1);
            if (json.endsWith("}")) json = json.substring(0, json.length() - 1);

            String[] pairs = json.split(",(?=\\s*\")");
            for (String pair : pairs) {
                int colonIdx = pair.indexOf(':');
                if (colonIdx < 0) continue;
                String key = pair.substring(0, colonIdx).trim().replace("\"", "");
                String value = pair.substring(colonIdx + 1).trim().replace("\"", "");
                configManager.setConfiguration("combatbot", key, value);
            }
        } catch (Exception e) {
            System.err.println("[CombatBot] Config apply fout: " + e.getMessage());
        }
    }

    private void appendStr(StringBuilder sb, String key, String val) {
        sb.append("\"").append(key).append("\":\"").append(escapeJson(val != null ? val : "")).append("\"");
    }

    private void appendInt(StringBuilder sb, String key, int val) {
        sb.append("\"").append(key).append("\":").append(val);
    }

    private void appendBool(StringBuilder sb, String key, boolean val) {
        sb.append("\"").append(key).append("\":").append(val);
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }
}
