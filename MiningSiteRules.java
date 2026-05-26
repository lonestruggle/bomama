package com.combatbot;

import net.runelite.api.coords.WorldPoint;

import java.util.Locale;
import java.util.Random;

/**
 * Herkenning van mining-centers (naam uit Centers-tab) en regels: erts per level, power-mining vs banken,
 * vaste bank-ankers (Draynor / Al Kharid).
 * <p><b>Al Kharid 2 vs 3:</b> bij 2 altijd banken op ijzer (geen power mine, ook niet bij globale “Erts droppen”).
 * Bij 3 altijd power mine op ijzer. Beide blijven bruikbaar vanaf mining 15+ zolang het center aan staat, ook op 30/55+.</p>
 */
public final class MiningSiteRules {

    public enum MiningSiteKind {
        UNKNOWN,
        /** Varrock east mine — alleen tin/copper; ijzer op andere centers (≥15). */
        VARROCK_EAST,
        /** Lumbridge zuid — zelfde profiel als Varrock east. */
        LUMB_SOUTH,
        /** Draynor zuid — ≥30 coal (30–54), ≥55 coal of mithril; bank Draynor. */
        DRAYNOR_SOUTH,
        /** Al Kharid "2" — iron vanaf 15; altijd bank Al Kharid (nooit power mine op ijzer). */
        ALKHARID_IRON_PAIR,
        /** Al Kharid "3" — iron vanaf 15; altijd power mine op ijzer (drop). */
        ALKHARID_IRON_TRIPLE
    }

    public enum MiningBankAnchor {
        NEAREST,
        DRAYNOR,
        AL_KHARID
    }

    private static final WorldPoint DRAYNOR_BANK_ANCHOR = new WorldPoint(3092, 3243, 0);
    private static final WorldPoint ALKHARID_BANK_ANCHOR = new WorldPoint(3269, 3167, 0);
    /** Standaard center-tiles (Centers-tab); fallback als de naam geen "2"/"3" bevat. */
    private static final WorldPoint ALKHARID_2_CENTER = new WorldPoint(3297, 3291, 0);
    private static final WorldPoint ALKHARID_3_CENTER = new WorldPoint(3295, 3310, 0);
    /**
     * Al Kharid 3: center-area ligt deels op onbegaanbare heuvel — loop/mine vanaf deze tegel (±1).
     */
    private static final WorldPoint ALKHARID_3_STAND_TILE = new WorldPoint(3297, 3311, 0);
    private static final int ALKHARID_3_STAND_RADIUS = 1;
    private static final int ALKHARID_SITE_MATCH_RADIUS = 22;
    /** Al Kharid mijn: scorpions lvl 14 — agro als jouw combat ≤ NPC×2+1 (zelfde als imps). */
    public static final int ALKHARID_SCORPION_NPC_LEVEL = 14;

    /**
     * {@code true} als lvl-14 scorpions in de Al Kharid-mijn agressief kunnen zijn (combat onbekend = voorzichtig).
     */
    public static boolean scorpionsAggressiveToCombatLevel(int combatLevel) {
        if (combatLevel <= 0) {
            return true;
        }
        return combatLevel <= (ALKHARID_SCORPION_NPC_LEVEL * 2) + 1;
    }

    /** Minimaal combat om Al Kharid 2/3 veilig te minen zonder scorpion-agro. */
    public static int alkharidScorpionSafeCombatLevel() {
        return (ALKHARID_SCORPION_NPC_LEVEL * 2) + 2;
    }

    public static boolean siteHasAlkharidScorpions(MiningSiteKind kind) {
        return kind == MiningSiteKind.ALKHARID_IRON_PAIR || kind == MiningSiteKind.ALKHARID_IRON_TRIPLE;
    }
    /**
     * West Lumbridge Swamp coal mine (OSRS wiki: West Lumbridge Swamp mine; redirect van "Draynor mine").
     * Veel NL-spelers noemen dit "lumb zuid"; coördinaat heeft voorrang vóór die naam voor erts-keuze.
     */
    private static final WorldPoint WEST_LUMBRIDGE_SWAMP_COAL_ANCHOR = new WorldPoint(3226, 3146, 0);
    private static final int WEST_LUMBRIDGE_SWAMP_COAL_MATCH_RADIUS = 30;
    /**
     * Oostelijke Lumbridge swamp training mine (tin/copper voor &lt;15), los van de westelijke coal-mijn.
     */
    private static final WorldPoint EAST_LUMBRIDGE_SWAMP_TIN_ANCHOR = new WorldPoint(3175, 3298, 0);
    private static final int EAST_LUMBRIDGE_SWAMP_TIN_MATCH_RADIUS = 24;
    /** Zuidoost Varrock tin/copper mijn (standaard center ca. 3285,3368 in plugindefaults). */
    private static final WorldPoint VARROCK_EAST_MINE_ANCHOR = new WorldPoint(3285, 3368, 0);
    private static final int VARROCK_EAST_MINE_MATCH_RADIUS = 22;

    private MiningSiteRules() {
    }

    public static WorldPoint draynorBankAnchor() {
        return DRAYNOR_BANK_ANCHOR;
    }

    public static WorldPoint alKharidBankAnchor() {
        return ALKHARID_BANK_ANCHOR;
    }

    /**
     * Vaste loop-/stand-tegel (niet het center) als het mining-center deels onbegaanbaar is.
     * {@code null} = gewoon naar center/rots lopen.
     */
    public static WorldPoint miningApproachTile(MiningSiteKind kind) {
        if (kind == MiningSiteKind.ALKHARID_IRON_TRIPLE) {
            return ALKHARID_3_STAND_TILE;
        }
        return null;
    }

    /** Tolerantie rond {@link #miningApproachTile} (Chebyshev / grid). */
    public static int miningApproachRadius(MiningSiteKind kind) {
        return kind == MiningSiteKind.ALKHARID_IRON_TRIPLE ? ALKHARID_3_STAND_RADIUS : 0;
    }

    /**
     * Referentie-tile per herkende mijn (dichtstbijzijnde center wint bij dedupe van dezelfde soort).
     */
    public static WorldPoint trainingAnchorForSiteKind(MiningSiteKind kind) {
        switch (kind) {
            case VARROCK_EAST:
                return VARROCK_EAST_MINE_ANCHOR;
            case LUMB_SOUTH:
                return EAST_LUMBRIDGE_SWAMP_TIN_ANCHOR;
            case DRAYNOR_SOUTH:
                return WEST_LUMBRIDGE_SWAMP_COAL_ANCHOR;
            case ALKHARID_IRON_PAIR:
                return ALKHARID_2_CENTER;
            case ALKHARID_IRON_TRIPLE:
                return ALKHARID_3_CENTER;
            default:
                return null;
        }
    }

    public static MiningSiteKind classify(String centerName) {
        return classify(centerName, null);
    }

    /** Naam uit Centers-tab; optioneel center-tile als naam geen Al Kharid 2/3 herkent. */
    public static MiningSiteKind classify(String centerName, WorldPoint centerPoint) {
        if (centerPoint != null && nearCenter(centerPoint, WEST_LUMBRIDGE_SWAMP_COAL_ANCHOR, WEST_LUMBRIDGE_SWAMP_COAL_MATCH_RADIUS)) {
            return MiningSiteKind.DRAYNOR_SOUTH;
        }
        if (centerPoint != null && nearCenter(centerPoint, EAST_LUMBRIDGE_SWAMP_TIN_ANCHOR, EAST_LUMBRIDGE_SWAMP_TIN_MATCH_RADIUS)) {
            return MiningSiteKind.LUMB_SOUTH;
        }
        if (centerPoint != null && nearCenter(centerPoint, VARROCK_EAST_MINE_ANCHOR, VARROCK_EAST_MINE_MATCH_RADIUS)) {
            return MiningSiteKind.VARROCK_EAST;
        }
        String n = centerName == null ? "" : centerName.toLowerCase(Locale.ROOT).trim();
        if (!n.isEmpty()) {
            if ((n.contains("varrock") && n.contains("east")) || n.contains("varrock east")) {
                return MiningSiteKind.VARROCK_EAST;
            }
            if ((n.contains("lumb") || n.contains("lumbridge"))
                    && (n.contains("zuid") || n.contains("south"))) {
                return MiningSiteKind.LUMB_SOUTH;
            }
            if (n.contains("draynor") && (n.contains("zuid") || n.contains("south"))) {
                return MiningSiteKind.DRAYNOR_SOUTH;
            }
            if (containsAlkharidToken(n)) {
                if (isAlkharid3Name(n)) {
                    return MiningSiteKind.ALKHARID_IRON_TRIPLE;
                }
                if (isAlkharid2Name(n)) {
                    return MiningSiteKind.ALKHARID_IRON_PAIR;
                }
            }
        }
        if (centerPoint != null) {
            boolean near3 = nearCenter(centerPoint, ALKHARID_3_CENTER, ALKHARID_SITE_MATCH_RADIUS);
            boolean near2 = nearCenter(centerPoint, ALKHARID_2_CENTER, ALKHARID_SITE_MATCH_RADIUS);
            if (near3 && near2) {
                int d2 = centerPoint.distanceTo(ALKHARID_2_CENTER);
                int d3 = centerPoint.distanceTo(ALKHARID_3_CENTER);
                return d2 <= d3 ? MiningSiteKind.ALKHARID_IRON_PAIR : MiningSiteKind.ALKHARID_IRON_TRIPLE;
            }
            if (near3) {
                return MiningSiteKind.ALKHARID_IRON_TRIPLE;
            }
            if (near2) {
                return MiningSiteKind.ALKHARID_IRON_PAIR;
            }
        }
        return MiningSiteKind.UNKNOWN;
    }

    private static boolean nearCenter(WorldPoint p, WorldPoint anchor, int radius) {
        return p.getPlane() == anchor.getPlane()
                && Math.abs(p.getX() - anchor.getX()) <= radius
                && Math.abs(p.getY() - anchor.getY()) <= radius;
    }

    private static boolean isAlkharid3Name(String n) {
        return n.contains("triple") || matchesKharidSpaceNumber(n, 3) || matchesAkAbbrev(n, 3);
    }

    private static boolean isAlkharid2Name(String n) {
        return n.contains("pair") || matchesKharidSpaceNumber(n, 2) || matchesAkAbbrev(n, 2);
    }

    /**
     * Herkent "alkarid 3" / "al kharid 3" zonder "alkarid 33" of "alkarid 32" (substring-val op "…3").
     */
    private static boolean matchesKharidSpaceNumber(String n, int num) {
        String digit = Integer.toString(num);
        String[] prefixes = {"alkarid ", "al kharid ", "kharid "};
        for (String p : prefixes) {
            int i = 0;
            while ((i = n.indexOf(p, i)) >= 0) {
                int j = i + p.length();
                if (j < n.length() && n.startsWith(digit, j)) {
                    int after = j + digit.length();
                    if (after >= n.length() || !Character.isDigit(n.charAt(after))) {
                        return true;
                    }
                }
                i = i + 1;
            }
        }
        return false;
    }

    /** {@code ak2} / {@code ak 2} maar niet {@code ak22} als tweede site. */
    private static boolean matchesAkAbbrev(String n, int num) {
        char d = (char) ('0' + num);
        int i = 0;
        while ((i = n.indexOf("ak", i)) >= 0) {
            int j = i + 2;
            while (j < n.length() && n.charAt(j) == ' ') {
                j++;
            }
            if (j < n.length() && n.charAt(j) == d) {
                int k = j + 1;
                if (k < n.length() && Character.isDigit(n.charAt(k))) {
                    i = j + 1;
                    continue;
                }
                boolean leftOk = i == 0 || !Character.isLetterOrDigit(n.charAt(i - 1));
                boolean rightOk = k >= n.length() || !Character.isDigit(n.charAt(k));
                if (leftOk && rightOk) {
                    return true;
                }
            }
            i = i + 1;
        }
        return false;
    }

    private static boolean containsAlkharidToken(String n) {
        return n.contains("alkarid") || n.contains("al kharid") || n.contains("kharid");
    }

    /**
     * Volle inventaris: droppen (powermine) of banken?
     * <ul>
     *   <li>Al Kharid 2 + ijzer: altijd bank (overschrijft globale "Erts droppen")</li>
     *   <li>Al Kharid 3 + ijzer: altijd droppen (overschrijft globale instelling)</li>
     *   <li>Overige locaties: globale instelling + standaard tin/copper-drop regels</li>
     * </ul>
     */
    public static boolean shouldDropOre(MiningSiteKind kind, boolean globalPreferDrop,
            int miningLevel, String rockNameFragment) {
        if (rockNameFragment == null) {
            return globalPreferDrop;
        }
        String r = rockNameFragment.toLowerCase(Locale.ROOT);
        if (kind == MiningSiteKind.ALKHARID_IRON_PAIR && r.contains("iron") && miningLevel >= 15) {
            return false;
        }
        if (kind == MiningSiteKind.ALKHARID_IRON_TRIPLE && r.contains("iron")) {
            return true;
        }
        if (globalPreferDrop) {
            return true;
        }
        return usePowerMining(kind, miningLevel, rockNameFragment);
    }

    /** Korte tekst voor paint / debug. */
    public static String oreDisposalLabel(MiningSiteKind kind, boolean globalPreferDrop,
            int miningLevel, String rockNameFragment) {
        boolean drop = shouldDropOre(kind, globalPreferDrop, miningLevel, rockNameFragment);
        if (kind == MiningSiteKind.ALKHARID_IRON_PAIR && rockNameFragment != null
                && rockNameFragment.toLowerCase(Locale.ROOT).contains("iron")) {
            return drop ? "drop (ongeldig AK2)" : "bank (Al Kharid 2)";
        }
        if (kind == MiningSiteKind.ALKHARID_IRON_TRIPLE && rockNameFragment != null
                && rockNameFragment.toLowerCase(Locale.ROOT).contains("iron")) {
            return drop ? "drop (Al Kharid 3)" : "bank (ongeldig AK3)";
        }
        return drop ? "drop" : "bank";
    }

    /** Center waar ijzer getraind wordt (Al Kharid 2/3). */
    public static boolean siteTrainsIronOre(MiningSiteKind kind) {
        switch (kind) {
            case ALKHARID_IRON_PAIR:
            case ALKHARID_IRON_TRIPLE:
                return true;
            default:
                return false;
        }
    }

    /** Alleen tin/copper — nooit ijzer op deze plek (ook niet op 15+). */
    public static boolean siteOnlyCopperTin(MiningSiteKind kind) {
        return kind == MiningSiteKind.VARROCK_EAST || kind == MiningSiteKind.LUMB_SOUTH;
    }

    /** Center mag in de rotatie-pool voor dit mining level (AK 2/3 blijven ≥15 beschikbaar, ook bij 30/55+). */
    public static boolean centerAllowedForMiningLevel(MiningSiteKind kind, int miningLevel) {
        switch (kind) {
            case ALKHARID_IRON_PAIR:
            case ALKHARID_IRON_TRIPLE:
                return miningLevel >= 15;
            case DRAYNOR_SOUTH:
                return miningLevel >= 30;
            default:
                return true;
        }
    }

    /**
     * Rock-naamfragment voor {@link net.storm.sdk.entities.TileObjects} (substring match op objectnaam).
     */
    public static String pickRockName(MiningSiteKind kind, int miningLevel, Random rng) {
        switch (kind) {
            case VARROCK_EAST:
            case LUMB_SOUTH:
                return rng.nextBoolean() ? "Copper rocks" : "Tin rocks";
            case ALKHARID_IRON_PAIR:
            case ALKHARID_IRON_TRIPLE:
                return miningLevel >= 15 ? "Iron rocks" : "Copper rocks";
            case DRAYNOR_SOUTH:
                if (miningLevel < 30) {
                    return "Copper rocks";
                }
                if (miningLevel < 55) {
                    return "Coal rocks";
                }
                return rng.nextBoolean() ? "Coal rocks" : "Mithril rocks";
            default:
                if (miningLevel >= 15 && miningLevel < 30) {
                    return "Iron rocks";
                }
                return MiningConfig.getBestOreForLevel(miningLevel);
        }
    }

    /** Power mining i.p.v. bank (tin/copper; Al Kharid 3 + ijzer). Al Kharid 2 + ijzer: nooit — zie handler. */
    public static boolean usePowerMining(MiningSiteKind kind, int miningLevel, String rockNameFragment) {
        if (rockNameFragment == null) {
            return false;
        }
        String r = rockNameFragment.toLowerCase(Locale.ROOT);
        if (r.contains("copper") || r.contains("tin")) {
            return true;
        }
        if (kind == MiningSiteKind.ALKHARID_IRON_TRIPLE && r.contains("iron")) {
            return true;
        }
        return false;
    }

    public static MiningBankAnchor bankAnchor(MiningSiteKind kind, int miningLevel, String rockNameFragment) {
        if (rockNameFragment != null && usePowerMining(kind, miningLevel, rockNameFragment)) {
            return MiningBankAnchor.NEAREST;
        }
        if (kind == MiningSiteKind.DRAYNOR_SOUTH && miningLevel >= 30) {
            return MiningBankAnchor.DRAYNOR;
        }
        if (kind == MiningSiteKind.ALKHARID_IRON_PAIR && miningLevel >= 15) {
            String r = rockNameFragment == null ? "" : rockNameFragment.toLowerCase(Locale.ROOT);
            if (r.contains("iron")) {
                return MiningBankAnchor.AL_KHARID;
            }
        }
        return MiningBankAnchor.NEAREST;
    }

    /** IJzerrotsen: extra concurrentie-checks (speler op tile). */
    public static boolean shouldApplyIronCompetitionRules(MiningSiteKind kind, String rockNameFragment) {
        if (rockNameFragment == null || !rockNameFragment.toLowerCase(Locale.ROOT).contains("iron")) {
            return false;
        }
        return shouldApplyMiningCompetitionWorldHop(kind);
    }

    /**
     * Drukke F2P-mijnen: wereld-hop pas na meerdere lege swings mét andere speler op dezelfde rots
     * (tin/copper én iron; niet alleen ijzer).
     */
    public static boolean shouldApplyMiningCompetitionWorldHop(MiningSiteKind kind) {
        switch (kind) {
            case VARROCK_EAST:
            case LUMB_SOUTH:
            case ALKHARID_IRON_PAIR:
            case ALKHARID_IRON_TRIPLE:
            case DRAYNOR_SOUTH:
                return true;
            default:
                return false;
        }
    }
}
