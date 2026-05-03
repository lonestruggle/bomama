package com.combatbot;

import net.storm.sdk.items.Bank;

/**
 * Gedeelde volgorde: beste heal eerst, <b>alleen F2P</b> (OSRS itemnamen).
 * Geen member-vis, geen pineapple pizza / karambwan, enz. — past bij F2P accounts.
 */
public final class CombatFoodPriority {

    /**
     * F2P-only, hoog → laag effect per item. Anchovy pizza eerst (2× 9 HP = 18 totaal per pizza).
     */
    public static final String[] BEST_FIRST = {
            "Anchovy pizza", "Swordfish", "Lobster", "Meat pizza", "Plain pizza",
            "Tuna", "Salmon", "Trout", "Pike",
            "Herring", "Sardine", "Anchovies", "Shrimps",
            "Cooked chicken", "Cooked meat", "Bread"
    };

    private CombatFoodPriority() {
    }

    /** Eerste hit waarvan {@link Bank#contains(String)} true is. Bank moet open zijn. */
    public static String findBestInBank() {
        if (!Bank.isOpen()) return null;
        for (String f : BEST_FIRST) {
            if (Bank.contains(f)) return f;
        }
        return null;
    }

    /**
     * Concreet item voor UBM/GE wanneer {@code foodChoice == ANY} en er (nog) geen food in de bank zit:
     * het eerste uit {@link #BEST_FIRST} (duidelijk hoog-tier; GE kan daarna falen als te duur).
     */
    public static String firstTargetWhenAnyAndBankUnknown() {
        return BEST_FIRST[0];
    }

    /** Index in {@link #BEST_FIRST}, of 0 als onbekend. */
    public static int indexInBestFirst(String itemName) {
        if (itemName == null || itemName.isEmpty()) {
            return 0;
        }
        for (int i = 0; i < BEST_FIRST.length; i++) {
            if (BEST_FIRST[i].equalsIgnoreCase(itemName.trim())) {
                return i;
            }
        }
        return 0;
    }
}
