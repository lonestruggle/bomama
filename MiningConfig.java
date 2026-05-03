package com.combatbot;

/**
 * Mining configuratie - ertsen, levels en bijbehorende pickaxes.
 */
public class MiningConfig {

    public static final String[][] ORE_LEVELS = {
            {"Copper rocks", "1"},
            {"Tin rocks", "1"},
            {"Iron rocks", "15"},
            {"Silver rocks", "20"},
            {"Coal rocks", "30"},
            {"Gold rocks", "40"},
            {"Mithril rocks", "55"},
            {"Adamantite rocks", "70"},
            {"Runite rocks", "85"}
    };

    public static final String[][] PICKAXE_LEVELS = {
            {"Bronze pickaxe", "1"},
            {"Iron pickaxe", "1"},
            {"Steel pickaxe", "6"},
            {"Black pickaxe", "11"},
            {"Mithril pickaxe", "21"},
            {"Adamant pickaxe", "31"},
            {"Rune pickaxe", "41"},
            {"Dragon pickaxe", "61"},
            {"Crystal pickaxe", "71"}
    };

    /**
     * Geeft het beste erts terug dat gemijnd kan worden op basis van Mining level.
     */
    public static String getBestOreForLevel(int miningLevel) {
        String bestOre = "Copper rocks";
        for (String[] entry : ORE_LEVELS) {
            int requiredLevel = Integer.parseInt(entry[1]);
            if (miningLevel >= requiredLevel) {
                bestOre = entry[0];
            }
        }
        return bestOre;
    }

    /**
     * Check of de speler een pickaxe heeft (inventory of equipped).
     */
    public static boolean hasPickaxe(String[] inventoryItems, String[] equippedItems) {
        for (String[] pick : PICKAXE_LEVELS) {
            for (String item : inventoryItems) {
                if (item.equals(pick[0])) return true;
            }
            for (String item : equippedItems) {
                if (item.equals(pick[0])) return true;
            }
        }
        return false;
    }

    /**
     * Geeft de naam van het erts-item dat je krijgt bij het mijnen.
     */
    public static String getOreItemName(String rockName) {
        if (rockName.contains("Copper")) return "Copper ore";
        if (rockName.contains("Tin")) return "Tin ore";
        if (rockName.contains("Iron")) return "Iron ore";
        if (rockName.contains("Silver")) return "Silver ore";
        if (rockName.contains("Coal")) return "Coal";
        if (rockName.contains("Gold")) return "Gold ore";
        if (rockName.contains("Mithril")) return "Mithril ore";
        if (rockName.contains("Adamantite")) return "Adamantite ore";
        if (rockName.contains("Runite")) return "Runite ore";
        return "Ore";
    }
}
