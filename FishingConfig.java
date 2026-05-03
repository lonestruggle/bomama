package com.combatbot;

import net.runelite.api.coords.WorldPoint;

/**
 * Fishing configuratie - vis spots, levels, benodigde tools en bait.
 * Cooking levels per vis.
 */
public class FishingConfig {

    // {Spot naam, actie, level, tool, bait (of "" als geen bait nodig)}
    public static final String[][] FISHING_METHODS = {
            {"Fishing spot", "Net", "1", "Small fishing net", ""},
            {"Fishing spot", "Bait", "5", "Fishing rod", "Fishing bait"},
            {"Fishing spot", "Lure", "20", "Fly fishing rod", "Feather"},
            {"Cage/Harpoon fishing spot", "Cage", "40", "Lobster pot", ""},
            {"Cage/Harpoon fishing spot", "Harpoon", "35", "Harpoon", ""},
            {"Net/Harpoon fishing spot", "Net", "62", "Small fishing net", ""},
            {"Net/Harpoon fishing spot", "Harpoon", "76", "Harpoon", ""},
            {"Rod Fishing spot", "Use-rod", "48", "Barbarian rod", "Fishing bait"},
            {"Fishing spot", "Use-rod", "65", "Fishing rod", "Fishing bait"}
    };

    // {Raw vis naam, cooked naam, cooking level, fishing level}
    public static final String[][] FISH_DATA = {
            // OSRS inventory-namen (rauw = "Raw …") — moet matchen voor canCook / useOn.
            {"Raw shrimps", "Shrimps", "1", "1"},
            {"Raw sardine", "Sardine", "1", "5"},
            {"Raw herring", "Herring", "5", "10"},
            {"Raw anchovies", "Anchovies", "1", "15"},
            {"Raw trout", "Trout", "15", "20"},
            {"Raw pike", "Pike", "20", "25"},
            {"Raw salmon", "Salmon", "25", "30"},
            {"Raw tuna", "Tuna", "30", "35"},
            {"Raw lobster", "Lobster", "40", "40"},
            {"Raw swordfish", "Swordfish", "45", "50"},
            {"Raw monkfish", "Monkfish", "62", "62"},
            {"Raw shark", "Shark", "80", "76"},
            {"Raw anglerfish", "Anglerfish", "84", "82"},
            {"Raw dark crab", "Dark crab", "90", "85"}
    };

    /** Barbarian Village fishing (fly/lure) — ongeveer 3109,3434. */
    private static final int BARBARIAN_X = 3109, BARBARIAN_Y = 3434, BARBARIAN_R = 18;

    /** Draynor vis-gebied (default center ~3090,3230). */
    private static final int DRAYNOR_X = 3090, DRAYNOR_Y = 3230, DRAYNOR_R = 28;

    /**
     * Onder dit Fishing-level liever Draynor (Net/Bait) i.p.v. Barbarian:
     * op Barbarian is Lure/Feather pas vanaf 20 optimaal; beide centers aangevinkt → tot lvl 20 naar Draynor.
     */
    public static final int MIN_FISHING_LEVEL_PREFER_BARBARIAN_OVER_DRAYNOR = 20;

    public static boolean isBarbarianFishingLocation(int x, int y) {
        int dx = Math.abs(x - BARBARIAN_X), dy = Math.abs(y - BARBARIAN_Y);
        return dx <= BARBARIAN_R && dy <= BARBARIAN_R;
    }

    /** Vaste tegel bij de rivier (NPC-cluster) — als walk-doel als je wel “in het gebied” bent maar geen spot ziet. */
    public static WorldPoint getBarbarianRiverAnchor() {
        return new WorldPoint(BARBARIAN_X, BARBARIAN_Y, 0);
    }

    /** Ruimtelijke check + optionele centernaam (panel: "Draynor village"). */
    public static boolean isDraynorFishingLocation(int x, int y, String centerName) {
        if (centerName != null) {
            String n = centerName.toLowerCase();
            if (n.contains("draynor")) return true;
            if (n.contains("barbarian")) return false;
        }
        int dx = Math.abs(x - DRAYNOR_X), dy = Math.abs(y - DRAYNOR_Y);
        return dx <= DRAYNOR_R && dy <= DRAYNOR_R;
    }

    /**
     * Bepaal de beste vismethode op basis van Fishing level.
     * Geeft een array terug: {spotName, action, toolName, bait}
     */
    public static String[] getBestMethodForLevel(int fishingLevel) {
        String[] best = {FISHING_METHODS[0][0], FISHING_METHODS[0][1], FISHING_METHODS[0][3], FISHING_METHODS[0][4]};
        int bestLevel = 1;

        for (String[] method : FISHING_METHODS) {
            int reqLevel = Integer.parseInt(method[2]);
            if (fishingLevel >= reqLevel && reqLevel >= bestLevel) {
                best = new String[]{method[0], method[1], method[3], method[4]};
                bestLevel = reqLevel;
            }
        }
        return best;
    }

    /**
     * Fly fishing (Lure) op "Fishing spot" — zelfde rivier als Barbarian, geen Barbarian rod nodig.
     * Gebruikt als fallback als alleen Fly rod + Feather beschikbaar zijn.
     */
    public static String[] getFlyLureMethod() {
        return new String[]{
                FISHING_METHODS[2][0], FISHING_METHODS[2][1],
                FISHING_METHODS[2][3], FISHING_METHODS[2][4]
        };
    }

    /**
     * Beste vismethode voor Barbarian Village: alleen spots die daar bestaan.
     * Geen Cage/Harpoon of Net/Harpoon spots (die bestaan daar niet).
     * Toegestaan: "Fishing spot" (Net, Bait, Lure) en "Rod Fishing spot" (Barbarian rod).
     * <p>
     * Let op: bij level ≥48 wint deze tabel de {@code Use-rod}/{@code Barbarian rod}-rij (hoogste vereiste level).
     * {@link com.combatbot.FishingHandler} kan naar {@link #getFlyLureMethod()} vallen als die kit ontbreekt.
     */
    public static String[] getBestMethodForLevelBarbarian(int fishingLevel) {
        String[] best = {FISHING_METHODS[0][0], FISHING_METHODS[0][1], FISHING_METHODS[0][3], FISHING_METHODS[0][4]};
        int bestLevel = 1;
        for (String[] method : FISHING_METHODS) {
            String spotName = method[0];
            if ("Cage/Harpoon fishing spot".equals(spotName) || "Net/Harpoon fishing spot".equals(spotName)) {
                continue;
            }
            int reqLevel = Integer.parseInt(method[2]);
            if (fishingLevel >= reqLevel && reqLevel >= bestLevel) {
                best = new String[]{method[0], method[1], method[3], method[4]};
                bestLevel = reqLevel;
            }
        }
        return best;
    }

    /**
     * Zelfde als getBestMethodForLevel maar sluit Barbarian/fly fishing uit (Net of Rod+Bait).
     * Voor wanneer de spot niet bij Barbarian Village is.
     */
    public static String[] getBestMethodForLevelNonBarbarian(int fishingLevel) {
        String[] best = {FISHING_METHODS[0][0], FISHING_METHODS[0][1], FISHING_METHODS[0][3], FISHING_METHODS[0][4]};
        int bestLevel = 1;
        for (String[] method : FISHING_METHODS) {
            String action = method[1];
            String tool = method[3];
            if ("Lure".equals(action) || "Barbarian rod".equals(tool)) continue;
            int reqLevel = Integer.parseInt(method[2]);
            if (fishingLevel >= reqLevel && reqLevel >= bestLevel) {
                best = new String[]{method[0], method[1], method[3], method[4]};
                bestLevel = reqLevel;
            }
        }
        return best;
    }

    /**
     * Draynor spots hebben in de praktijk vooral Net/Bait acties.
     * Voorkomt dat auto-select op hoge level een niet-bestaande actie kiest (zoals Use-rod).
     */
    public static String[] getBestMethodForLevelDraynor(int fishingLevel) {
        String[] best = {FISHING_METHODS[0][0], FISHING_METHODS[0][1], FISHING_METHODS[0][3], FISHING_METHODS[0][4]};
        int bestLevel = 1;
        for (String[] method : FISHING_METHODS) {
            String action = method[1];
            String spotName = method[0];
            if (!"Fishing spot".equals(spotName)) continue;
            if (!"Net".equals(action) && !"Bait".equals(action)) continue;
            int reqLevel = Integer.parseInt(method[2]);
            if (fishingLevel >= reqLevel && reqLevel >= bestLevel) {
                best = new String[]{method[0], method[1], method[3], method[4]};
                bestLevel = reqLevel;
            }
        }
        return best;
    }

    /**
     * Edgeville/Barbarian river profiel: gebruik Feather (Lure/Fly fishing) waar mogelijk.
     * Dit voorkomt dat hier per ongeluk op Bait/Use-rod wordt overgeschakeld.
     */
    public static String[] getBestMethodForLevelEdgeFeatherOnly(int fishingLevel) {
        String[] best = null;
        int bestLevel = -1;
        for (String[] method : FISHING_METHODS) {
            String action = method[1];
            String bait = method[4];
            if (!"Lure".equals(action)) continue;
            if (!"Feather".equalsIgnoreCase(bait)) continue;
            int reqLevel = Integer.parseInt(method[2]);
            if (fishingLevel >= reqLevel && reqLevel >= bestLevel) {
                best = new String[]{method[0], method[1], method[3], method[4]};
                bestLevel = reqLevel;
            }
        }
        // Fallback als level te laag is voor Lure
        if (best == null) {
            return getBestMethodForLevelNonBarbarian(fishingLevel);
        }
        return best;
    }

    /**
     * Geeft het benodigde tool terug voor een specifieke vismethode.
     */
    public static String getToolForMethod(String spotName, String action) {
        for (String[] method : FISHING_METHODS) {
            if (method[0].equals(spotName) && method[1].equals(action)) {
                return method[3];
            }
        }
        return "Small fishing net";
    }

    /**
     * Geeft het benodigde bait terug voor een specifieke vismethode.
     * Leeg = geen bait nodig.
     */
    public static String getBaitForMethod(String spotName, String action) {
        for (String[] method : FISHING_METHODS) {
            if (method[0].equals(spotName) && method[1].equals(action)) {
                return method[4];
            }
        }
        return "";
    }

    /**
     * Bepaal het cooking level dat nodig is voor een rauwe vis.
     * -1 = niet gevonden / niet kookbaar.
     */
    public static int getCookingLevelForFish(String rawFishName) {
        for (String[] data : FISH_DATA) {
            if (data[0].equalsIgnoreCase(rawFishName)) {
                return Integer.parseInt(data[2]);
            }
        }
        return -1;
    }

    /**
     * Geeft de cooked naam terug voor een rauwe vis.
     */
    public static String getCookedName(String rawFishName) {
        for (String[] data : FISH_DATA) {
            if (data[0].equalsIgnoreCase(rawFishName)) {
                return data[1];
            }
        }
        return null;
    }

    /**
     * Check of de speler een vis kan koken op basis van Cooking level.
     */
    public static boolean canCook(String rawFishName, int cookingLevel) {
        int reqLevel = getCookingLevelForFish(rawFishName);
        return reqLevel >= 0 && cookingLevel >= reqLevel;
    }
}
