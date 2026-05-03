package com.combatbot;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * OSRS hiscores {@code index_lite.ws} — officiële Old School-endpoints (normaal, ironman, enz.).
 * Spaties in RSN: {@code URLEncoder} + {@code +} → {@code %20} voor sommige servers.
 */
public final class OsrsHiscoreApi {

    private static final String[] LITE_URL_PREFIXES = {
            "https://oldschool.runescape.com/hiscores_oldschool/index_lite.ws?player=",
            "https://oldschool.runescape.com/hiscores_oldschool_ironman/index_lite.ws?player=",
            "https://oldschool.runescape.com/hiscores_oldschool_hardcore_ironman/index_lite.ws?player=",
            "https://oldschool.runescape.com/hiscores_oldschool_ultimate/index_lite.ws?player=",
            "https://secure.runescape.com/m=hiscore_oldschool/index_lite.ws?player="
    };
    private static final int CONNECT_MS = 15000;
    private static final int READ_MS = 25000;
    private static final Pattern CSV_LINE = Pattern.compile("^-?\\d+,\\d+,\\d+$");

    /**
     * Regels 0–25 van {@code index_lite.ws}: Overall, dan skills t/m Construction, daarna Sailing indien aanwezig.
     */
    private static final String[] LITE_LINE_NAMES = {
            "Overall",
            "Attack", "Defence", "Strength", "Hitpoints",
            "Ranged", "Prayer", "Magic", "Cooking", "Woodcutting",
            "Fletching", "Fishing", "Firemaking", "Crafting", "Smithing",
            "Mining", "Herblore", "Agility", "Thieving", "Slayer",
            "Farming", "Runecraft", "Hunter", "Construction",
            "Sailing"
    };

    private OsrsHiscoreApi() {
    }

    /**
     * Haalt skilllevels op en vult {@link ManagedJagexAccountsStore.AccountStatSnapshot}.
     * {@code totalGpApprox} blijft 0 — hiscores bevatten geen GP (alleen in-game meten).
     *
     * @return null bij netwerk-/parse-fout of speler niet gevonden
     */
    public static ManagedJagexAccountsStore.AccountStatSnapshot fetchSnapshot(String displayName) {
        if (displayName == null || displayName.trim().isEmpty()) {
            return null;
        }
        String name = displayName.trim();
        List<String> lines = fetchLinesForPlayer(name);
        if (lines == null || lines.size() < 16 || !looksLikeLiteCsv(lines)) {
            return null;
        }
        int attack = parseLevel(lines.get(1));
        int defence = parseLevel(lines.get(2));
        int strength = parseLevel(lines.get(3));
        int hitpoints = parseLevel(lines.get(4));
        int ranged = parseLevel(lines.get(5));
        int prayer = parseLevel(lines.get(6));
        int magic = parseLevel(lines.get(7));
        int woodcutting = lines.size() > 9 ? parseLevel(lines.get(9)) : 0;
        int fishing = lines.size() > 11 ? parseLevel(lines.get(11)) : 0;
        int mining = lines.size() > 15 ? parseLevel(lines.get(15)) : 0;

        int combatLevel = computeCombatLevel(attack, defence, strength, hitpoints, ranged, prayer, magic);

        ManagedJagexAccountsStore.AccountStatSnapshot snap = new ManagedJagexAccountsStore.AccountStatSnapshot();
        snap.totalGpApprox = 0;
        snap.hiscoreSuspectBanned = false;
        snap.combatLevel = combatLevel;
        snap.attack = attack;
        snap.strength = strength;
        snap.defence = defence;
        snap.magic = magic;
        snap.woodcutting = woodcutting;
        snap.mining = mining;
        snap.fishing = fishing;
        snap.prayer = prayer;
        snap.updatedEpochMs = System.currentTimeMillis();
        return snap;
    }

    /**
     * Alle skilllevels uit {@code index_lite.ws} (Overall + skills; Sailing alleen als de API die regel levert).
     *
     * @return null bij fout; anders map met displaywaarden (ontbrekend → "—")
     */
    public static Map<String, String> fetchFullSkillLevels(String displayName) {
        if (displayName == null || displayName.trim().isEmpty()) {
            return null;
        }
        List<String> lines = fetchLinesForPlayer(displayName.trim());
        if (lines == null || lines.size() < 16 || !looksLikeLiteCsv(lines)) {
            return null;
        }
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < LITE_LINE_NAMES.length; i++) {
            String skillName = LITE_LINE_NAMES[i];
            if (i >= lines.size()) {
                out.put(skillName, "—");
            } else {
                out.put(skillName, parseLevelString(lines.get(i), skillName));
            }
        }
        return out;
    }

    /**
     * Combat level zoals veel web-overlays (zelfde als de meegeleverde React-snippet).
     */
    public static String computeCombatLevelReactDisplay(Map<String, String> stats) {
        if (stats == null || stats.isEmpty()) {
            return "—";
        }
        int def = parseIntSkill(stats, "Defence", 1);
        int hp = parseIntSkill(stats, "Hitpoints", 10);
        int pray = parseIntSkill(stats, "Prayer", 1);
        int att = parseIntSkill(stats, "Attack", 1);
        int str = parseIntSkill(stats, "Strength", 1);
        int rng = parseIntSkill(stats, "Ranged", 1);
        int mag = parseIntSkill(stats, "Magic", 1);
        double base = 0.25 * (def + hp + Math.floor(pray / 2.0));
        double melee = 0.325 * (att + str);
        double range = 0.325 * Math.floor((3.0 * rng) / 2.0);
        double mage = 0.325 * Math.floor((3.0 * mag) / 2.0);
        return String.valueOf((int) Math.floor(base + Math.max(melee, Math.max(range, mage))));
    }

    private static int parseIntSkill(Map<String, String> stats, String key, int defaultVal) {
        String v = stats.get(key);
        if (v == null || v.equals("—")) {
            return defaultVal;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    private static String parseLevelString(String line, String skillName) {
        if (line == null || line.isEmpty()) {
            return "—";
        }
        String[] p = line.split(",");
        if (p.length < 2) {
            return "—";
        }
        String raw = p[1].trim();
        if ("-1".equals(raw)) {
            return "Hitpoints".equals(skillName) ? "10" : "1";
        }
        return raw;
    }

    private static List<String> fetchLinesForPlayer(String name) {
        List<String> lines = null;
        for (String prefix : LITE_URL_PREFIXES) {
            try {
                lines = fetchLines(name, prefix);
                if (lines != null && lines.size() >= 16 && looksLikeLiteCsv(lines)) {
                    return lines;
                }
            } catch (IOException ignored) {
                lines = null;
            }
        }
        return null;
    }

    private static boolean looksLikeLiteCsv(List<String> lines) {
        for (int i = 0; i < Math.min(5, lines.size()); i++) {
            String s = lines.get(i);
            if (s != null && !s.isEmpty() && CSV_LINE.matcher(s.trim()).matches()) {
                return true;
            }
        }
        return false;
    }

    private static List<String> fetchLines(String playerName, String urlPrefix) throws IOException {
        // + uit URLEncoder werkt niet overal; %20 is veiliger voor query player=
        String enc = URLEncoder.encode(playerName, StandardCharsets.UTF_8).replace("+", "%20");
        URL url = new URL(urlPrefix + enc);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; CombatBot/OSRS hiscore)");
        c.setConnectTimeout(CONNECT_MS);
        c.setReadTimeout(READ_MS);
        c.setInstanceFollowRedirects(true);
        int code = c.getResponseCode();
        InputStream in = code == HttpURLConnection.HTTP_OK
                ? c.getInputStream()
                : c.getErrorStream();
        if (in == null) {
            c.disconnect();
            throw new IOException("HTTP " + code + " geen body");
        }
        List<String> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                lines.add(line.trim());
            }
        } finally {
            c.disconnect();
        }
        if (code != HttpURLConnection.HTTP_OK) {
            throw new IOException("HTTP " + code);
        }
        return lines;
    }

    private static int parseLevel(String line) {
        if (line == null || line.isEmpty()) {
            return 0;
        }
        String[] p = line.split(",");
        if (p.length < 2) {
            return 0;
        }
        try {
            return Integer.parseInt(p[1].trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Formule zoals op de OSRS wiki (Base + max melee, ranged, magic style).
     */
    static int computeCombatLevel(int attack, int defence, int strength, int hitpoints,
            int ranged, int prayer, int magic) {
        double base = 0.25 * (defence + hitpoints + Math.floor(prayer / 2.0));
        double melee = 0.325 * (attack + strength);
        double range = 0.325 * (ranged * 2.0);
        double mage = 0.325 * (magic * 2.0);
        double combat = base + Math.max(melee, Math.max(range, mage));
        int cl = (int) Math.floor(combat);
        return Math.min(126, Math.max(3, cl));
    }
}
