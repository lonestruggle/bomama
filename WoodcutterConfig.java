package com.combatbot;


/**
 * Woodcutting configuratie - wordt opgenomen in de hoofd CombatBotConfig.
 * Dit bestand definieert alleen de constanten en defaults als referentie.
 * De daadwerkelijke config items staan in CombatBotConfig.
 */
public class WoodcutterConfig {

    /**
     * Boom types: { zoeksleutelwoord, minimaal WC level, log item naam }
     *
     * Het "zoeksleutelwoord" wordt gebruikt als contains()-check op de object naam.
     * De in-game namen zijn bijv. "Tree", "Oak", "Oak Tree" — contains() vangt beide.
     *
     * Geordend van laagste naar hoogste level zodat getBestTreeForLevel()
     * de beste optie kan bepalen.
     */
    public static final String[][] TREE_LEVELS = {
            {"Tree",         "1",  "Logs"},
            {"Oak",          "15", "Oak logs"},
            {"Willow",       "30", "Willow logs"},
            {"Teak",         "35", "Teak logs"},
            {"Maple",        "45", "Maple logs"},
            {"Mahogany",     "50", "Mahogany logs"},
            {"Yew",          "60", "Yew logs"},
            {"Magic",        "75", "Magic logs"},
            {"Redwood",      "90", "Redwood logs"}
    };

    /**
     * Geeft het zoekwoord van de beste boom terug die gekapt kan worden op basis van WC level.
     * Bijv. level 15 → "Oak", level 29 → "Oak", level 30 → "Willow".
     */
    public static String getBestTreeForLevel(int wcLevel) {
        String bestTree = "Tree";
        for (String[] entry : TREE_LEVELS) {
            int requiredLevel = Integer.parseInt(entry[1]);
            if (wcLevel >= requiredLevel) {
                bestTree = entry[0];
            }
        }
        return bestTree;
    }

    /**
     * Zelfde logica als {@code WoodcutterHandler#matchesTreeKeyword}: bij keyword "tree" alleen exacte "Tree",
     * anders {@code contains} (Oak/Willow e.d.) — voorkomt dat "tree" in "Willow tree" een normale Tree matcht.
     */
    public static boolean matchesTreeObjectName(String objectName, String keyword) {
        if (objectName == null || keyword == null) {
            return false;
        }
        String nameLower = objectName.toLowerCase().trim();
        String keyLower = keyword.toLowerCase().trim();
        if (keyLower.equals("tree")) {
            return nameLower.equals("tree");
        }
        return nameLower.contains(keyLower);
    }

    /**
     * Geeft de log-itemnaam terug voor een gegeven zoekwoord.
     * Gebruikt contains() zodat bijv. "Oak Tree" ook "Oak logs" teruggeeft.
     * Bijv. "Oak" of "Oak Tree" → "Oak logs", "Tree" → "Logs".
     */
    public static String getLogName(String treeKeyword) {
        if (treeKeyword == null) return "Logs";
        String lower = treeKeyword.toLowerCase();
        for (String[] entry : TREE_LEVELS) {
            // Sla "Tree" over als standalone keyword om valse matches te voorkomen
            if (entry[0].equalsIgnoreCase("Tree")) continue;
            if (lower.contains(entry[0].toLowerCase())) {
                return entry[2];
            }
        }
        // Fallback voor gewone "Tree" of onbekend
        return "Logs";
    }

    /**
     * Geeft het vereiste Firemaking level terug voor het verbranden van logs
     * van het opgegeven boomtype. FM levels komen overeen met WC levels.
     */
    public static int getRequiredFmLevel(String treeKeyword) {
        if (treeKeyword == null) return 1;
        String lower = treeKeyword.toLowerCase();
        for (String[] entry : TREE_LEVELS) {
            if (entry[0].equalsIgnoreCase("Tree")) continue;
            if (lower.contains(entry[0].toLowerCase())) {
                return Integer.parseInt(entry[1]);
            }
        }
        return 1; // gewone logs
    }
}
