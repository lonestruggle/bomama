package com.combatbot;

import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.WidgetID;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Verzamelde data voor {@link ClueScrollHelper.Tier#BEGINNER} — basis voor een toekomstige solver.
 * Bronnen: OSRS Wiki (Treasure Trails), RuneLite {@code cluescrolls}-plugin (Cryptic/Emote/Anagram/Map).
 * <p>
 * Belangrijk: alle beginner-stappen gebruiken hetzelfde item-ID {@link #ITEM_CLUE_SCROLL} (23182).
 * De actieve stap wordt bepaald door de <b>tekst</b> in het clue-venster, niet door een ander item.
 */
public final class BeginnerClueReference {

    // —— Items ——
    public static final int ITEM_CLUE_SCROLL = 23182;
    public static final int ITEM_REWARD_CASKET = 23245;
    public static final int ITEM_SCROLL_BOX = 24361;

    /** Charlie the Tramp — zuid-Varrock (RuneLite NPC overlay). */
    public static final int NPC_ID_CHARLIE_TRAMP = 5209;
    public static final WorldPoint CHARLIE_TRAMP_TILE = new WorldPoint(3208, 3391, 0);

    /** Lege inventory-widget (geen echt item). */
    public static final int EMPTY_INVENTORY_WIDGET_ITEM_ID = 6512;

    public enum StepType {
        /** Scrambled letters → talk NPC. */
        ANAGRAM,
        /** Riddle → talk NPC. */
        CRYPTIC_TALK,
        /** Riddle → search object / hot-cold + Reldo. */
        CRYPTIC_SPECIAL,
        /** Equip items + emote op locatie; Uri geeft volgende stap. */
        EMOTE,
        /** Map image (iface TRAIL_MAP01–11) → dig met spade. */
        MAP,
        /** Strange device van Reldo → hot/cold → dig. */
        HOT_COLD,
        /** Talk Charlie → breng 1 van 8 items. */
        CHARLIE_TRAMP
    }

    public static final class StashUnit {
        public final String clueHint;
        public final WorldPoint near;
        public final String[] requiredItems;

        public StashUnit(String clueHint, WorldPoint near, String... requiredItems) {
            this.clueHint = clueHint;
            this.near = near;
            this.requiredItems = requiredItems;
        }
    }

    public static final class ClueEntry {
        public final StepType type;
        /** Unieke substring in clue-tekst (lowercase vergelijking). */
        public final String textMatch;
        public final String solution;
        public final WorldPoint destination;
        public final String npcName;
        public final String emote;
        public final String[] equipItems;

        public ClueEntry(StepType type, String textMatch, String solution, WorldPoint destination,
                String npcName, String emote, String[] equipItems) {
            this.type = type;
            this.textMatch = textMatch;
            this.solution = solution;
            this.destination = destination;
            this.npcName = npcName;
            this.emote = emote;
            this.equipItems = equipItems;
        }
    }

    /** 10% 1 stap, 45% 2 stap, 45% 3 stap (Mod Ash). */
    public static final String STEP_COUNT_NOTE =
            "Beginner: 10% 1 stap, 45% 2 stappen, 45% 3 stappen — zelfde item-ID elke stap.";

    public static final String DETECTION_NOTE =
            "Lees clue-tekst (RuneLite: widget TRAIL_CLUETEXT) of map-iface (TRAIL_MAP01–11). "
                    + "Master gebruikt ook 23182; onderscheid alleen via tekst.";

    private static final ClueEntry[] ENTRIES = buildEntries();

    private static final StashUnit[] STASH_UNITS = {
            new StashUnit("Cheer in the centre of Lumbridge Swamp",
                    new WorldPoint(3204, 3173, 0), "Leather boots", "Leather gloves"),
            new StashUnit("Cheer for the knitters of Varrock",
                    new WorldPoint(3205, 3416, 0), "Cape", "Chef's hat"),
            new StashUnit("Panic at the Al Kharid mine",
                    new WorldPoint(3303, 3271, 0), "Bronze med helm", "Iron chainbody"),
    };

    /** Charlie vraagt altijd één van deze (pre-made mag sinds 2022). */
    public static final String[] CHARLIE_TRAMP_REQUEST_ITEMS = {
            "Trout", "Pike", "Leather body", "Leather chaps",
            "Raw trout", "Raw herring", "Iron ore", "Iron dagger"
    };

    /**
     * Parse Charlie dialoog: "I really need a cooked trout" enz. (wiki Transcript:Charlie the Tramp).
     *
     * @return exact inventory item name, of null
     */
    public static String parseCharlieItemRequestFromDialog(String rawText) {
        if (rawText == null || rawText.isEmpty()) {
            return null;
        }
        String n = normalizeClueText(rawText);
        if (n.contains("cooked trout")) {
            return "Trout";
        }
        if (n.contains("cooked pike")) {
            return "Pike";
        }
        if (n.contains("raw herring")) {
            return "Raw herring";
        }
        if (n.contains("raw trout")) {
            return "Raw trout";
        }
        if (n.contains("leather chaps")) {
            return "Leather chaps";
        }
        if (n.contains("leather body")) {
            return "Leather body";
        }
        if (n.contains("iron dagger")) {
            return "Iron dagger";
        }
        if (n.contains("iron ore")) {
            return "Iron ore";
        }
        for (String item : CHARLIE_TRAMP_REQUEST_ITEMS) {
            if (n.contains(item.toLowerCase(Locale.ROOT))) {
                return item;
            }
        }
        return null;
    }

    /** Hot/cold — alleen via Reldo, niet bank/GE. */
    public static final String ITEM_STRANGE_DEVICE = "Strange device";
    public static final int ITEM_STRANGE_DEVICE_ID = 23183;

    /** Vaak nodig voor beginner emotes (GE/koop). */
    public static final String[] COMMON_EMOTE_ITEMS = {
            "Bronze axe", "Chef's hat", "Gold necklace", "Gold ring",
            "Leather boots", "Red cape"
    };

    private BeginnerClueReference() {
    }

    public static ClueEntry[] allEntries() {
        return ENTRIES;
    }

    public static StashUnit[] stashUnits() {
        return STASH_UNITS;
    }

    /**
     * Volledige clue-kit voor bankvoorbereiding: spade, emote-outfits, Charlie-items, STASH, map/hot-cold.
     * Geen clue scroll — die blijft in inventory tijdens deposit.
     */
    public static String[] allKitItemNames() {
        Set<String> names = new LinkedHashSet<>();
        names.add("Spade");
        names.add(ITEM_STRANGE_DEVICE);
        for (String s : COMMON_EMOTE_ITEMS) {
            names.add(s);
        }
        for (String s : CHARLIE_TRAMP_REQUEST_ITEMS) {
            names.add(s);
        }
        for (StashUnit su : STASH_UNITS) {
            if (su.requiredItems != null) {
                for (String item : su.requiredItems) {
                    names.add(item);
                }
            }
        }
        for (ClueEntry e : ENTRIES) {
            if (e.equipItems != null) {
                for (String item : e.equipItems) {
                    if (!isReldoOnlyItem(item)) {
                        names.add(item);
                    }
                }
            }
        }
        return names.toArray(new String[0]);
    }

    /** Alternatieve itemnamen (wiki vs client) en emote-capes. */
    private static final String[][] KIT_NAME_ALIASES = {
            {"Cape", "Red cape", "Black cape", "Blue cape", "Yellow cape", "Green cape", "Orange cape", "Purple cape"},
            {"Bronze med helm", "Bronze medium helmet", "Bronze medium helm"},
    };

    /** Alleen Reldo (cryptic “buried beneath…” / hot-cold device) — geen bank-withdraw of GE. */
    public static boolean isReldoOnlyItem(String itemName) {
        return itemName != null && itemName.equalsIgnoreCase(ITEM_STRANGE_DEVICE);
    }

    public static boolean isStrangeDeviceId(int itemId) {
        return itemId == ITEM_STRANGE_DEVICE_ID;
    }

    public static boolean isStrangeDeviceItem(String itemName, int itemId) {
        if (isStrangeDeviceId(itemId)) {
            return true;
        }
        return itemName != null && itemName.equalsIgnoreCase(ITEM_STRANGE_DEVICE);
    }

    /** Of {@code actualItemName} in inv/equip voldoet aan kit-regel {@code requiredKitName}. */
    public static boolean kitRequirementMetByItemName(String requiredKitName, String actualItemName) {
        if (requiredKitName == null || actualItemName == null) {
            return false;
        }
        if (requiredKitName.equalsIgnoreCase(actualItemName)) {
            return true;
        }
        for (String[] group : KIT_NAME_ALIASES) {
            if (group.length == 0 || !group[0].equalsIgnoreCase(requiredKitName)) {
                continue;
            }
            for (String alias : group) {
                if (alias.equalsIgnoreCase(actualItemName)) {
                    return true;
                }
            }
        }
        String req = normalizeKitToken(requiredKitName);
        String act = normalizeKitToken(actualItemName);
        if (req.equals(act)) {
            return true;
        }
        if ("cape".equals(req) && act.endsWith("cape")) {
            return true;
        }
        if (req.contains("bronze") && req.contains("helm") && act.contains("bronze") && act.contains("helm")) {
            return true;
        }
        return false;
    }

    private static String normalizeKitToken(String name) {
        return name.trim().toLowerCase(Locale.ROOT)
                .replace("medium", "med")
                .replaceAll("\\s+", " ");
    }

    /** Exacte OSRS-clientnaam voor bank/GE (niet "Bronze medium helmet", niet Gilded spade). */
    public static String canonicalKitItemName(String kitName) {
        if (kitName == null || kitName.isEmpty()) {
            return kitName;
        }
        if (kitName.equalsIgnoreCase("Bronze medium helmet")
                || kitName.equalsIgnoreCase("Bronze medium helm")) {
            return "Bronze med helm";
        }
        if (kitName.equalsIgnoreCase("spade")) {
            return "Spade";
        }
        return kitName;
    }

    /** Kit-items die op de Grand Exchange gekocht mogen worden. */
    public static boolean isGePurchasableKitItem(String itemName) {
        return itemName != null && !itemName.isEmpty() && !isReldoOnlyItem(itemName);
    }

    /** Startprijs voor GE-inkoop (GeRestockHelper escaleert indien nodig). */
    public static int geStartPrice(String itemName) {
        if (itemName == null || itemName.isEmpty()) {
            return 1000;
        }
        String n = itemName.toLowerCase(Locale.ROOT);
        if (n.equals("spade")) {
            return 500;
        }
        if (n.contains("gold ring") || n.contains("gold necklace")) {
            return 350;
        }
        if (n.contains("bronze axe") || n.contains("leather boots") || n.contains("leather gloves")) {
            return 200;
        }
        if (n.contains("chef's hat") || n.contains("red cape") || n.equals("cape")) {
            return 250;
        }
        if (n.contains("bronze med helm") || n.contains("iron chainbody")) {
            return 400;
        }
        if (n.contains("trout") || n.contains("pike") || n.contains("herring")) {
            return 80;
        }
        if (n.contains("leather body") || n.contains("leather chaps")) {
            return 350;
        }
        if (n.contains("iron ore") || n.contains("iron dagger")) {
            return 250;
        }
        return 1500;
    }

    /** Herken open map-clue interface (na Read op scroll) → bekende dig-stap. */
    public static ClueEntry matchByMapInterfaceGroup(int groupId) {
        if (groupId == WidgetID.BEGINNER_CLUE_MAP_CHAMPIONS_GUILD) {
            return entryByTextMatch("champions guild");
        }
        if (groupId == WidgetID.BEGINNER_CLUE_MAP_VARROCK_EAST_MINE) {
            return entryByTextMatch("varrock east mine");
        }
        if (groupId == WidgetID.BEGINNER_CLUE_MAP_DRAYNOR) {
            return entryByTextMatch("south of draynor bank");
        }
        if (groupId == WidgetID.BEGINNER_CLUE_MAP_NORTH_OF_FALADOR) {
            return entryByTextMatch("standing stones");
        }
        if (groupId == WidgetID.BEGINNER_CLUE_MAP_WIZARDS_TOWER) {
            return entryByTextMatch("wizards tower");
        }
        return null;
    }

    public static ClueEntry entryByTextMatch(String textMatch) {
        if (textMatch == null) {
            return null;
        }
        for (ClueEntry e : ENTRIES) {
            if (textMatch.equals(e.textMatch)) {
                return e;
            }
        }
        return null;
    }

    /** Zoek bekende stap op (genormaliseerde) clue-tekst — voor debug/solver-start. */
    public static ClueEntry matchByClueText(String rawText) {
        if (rawText == null || rawText.isEmpty()) {
            return null;
        }
        String norm = normalizeClueText(rawText);
        ClueEntry best = null;
        int bestLen = 0;
        for (ClueEntry e : ENTRIES) {
            if (e.textMatch == null || e.textMatch.isEmpty()) {
                continue;
            }
            if (!norm.contains(e.textMatch)) {
                continue;
            }
            int score = e.textMatch.length();
            if (e.type == StepType.CRYPTIC_SPECIAL) {
                score += 50;
            }
            if (score > bestLen) {
                best = e;
                bestLen = score;
            }
        }
        return best;
    }

    public static String normalizeClueText(String text) {
        return text.toLowerCase(Locale.ROOT)
                .replace('\n', ' ')
                .replaceAll("<[^>]*>", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /** Dump volledige beginner-database naar Debug-tab (bron Clue). */
    public static void logFullReferenceToDebug() {
        DebugLog.log("Clue", "=== Beginner Treasure Trail referentie ===");
        DebugLog.log("Clue", "Items: scroll=" + ITEM_CLUE_SCROLL + " casket=" + ITEM_REWARD_CASKET
                + " scrollBox=" + ITEM_SCROLL_BOX);
        DebugLog.log("Clue", STEP_COUNT_NOTE);
        DebugLog.log("Clue", DETECTION_NOTE);
        DebugLog.log("Clue", "Stap-types: ANAGRAM, CRYPTIC_TALK, CRYPTIC_SPECIAL (Reldo), EMOTE, MAP, HOT_COLD, CHARLIE_TRAMP");

        for (StepType t : StepType.values()) {
            int n = 0;
            for (ClueEntry e : ENTRIES) {
                if (e.type == t) {
                    n++;
                }
            }
            DebugLog.log("Clue", "— " + t + " (" + n + " bekende stappen in DB) —");
            for (ClueEntry e : ENTRIES) {
                if (e.type != t) {
                    continue;
                }
                logEntry(e);
            }
        }

        DebugLog.log("Clue", "— STASH (3) —");
        for (StashUnit s : STASH_UNITS) {
            DebugLog.log("Clue", "STASH @ " + s.near.getX() + "," + s.near.getY() + " | "
                    + s.clueHint + " | items: " + String.join(", ", s.requiredItems));
        }

        DebugLog.log("Clue", "— Charlie the Tramp (8 mogelijke items) —");
        for (String item : CHARLIE_TRAMP_REQUEST_ITEMS) {
            DebugLog.log("Clue", "  " + item);
        }

        DebugLog.log("Clue", "— Hot/Cold dig zones (5) — Chronicle-trick op Wiki —");
        for (ClueEntry e : ENTRIES) {
            if (e.type == StepType.HOT_COLD) {
                logEntry(e);
            }
        }

        DebugLog.log("Clue", "Solver-volgende stap: lees clue-tekst in-game → matchByClueText → walk/talk/emote/dig.");
    }

    private static void logEntry(ClueEntry e) {
        StringBuilder sb = new StringBuilder();
        sb.append(e.type);
        if (e.npcName != null) {
            sb.append(" NPC=").append(e.npcName);
        }
        if (e.emote != null) {
            sb.append(" emote=").append(e.emote);
        }
        if (e.equipItems != null && e.equipItems.length > 0) {
            sb.append(" equip=[").append(String.join(", ", e.equipItems)).append("]");
        }
        if (e.destination != null) {
            sb.append(" @ ").append(e.destination.getX()).append(",").append(e.destination.getY());
        }
        sb.append(" | ").append(e.solution);
        DebugLog.log("Clue", sb.toString());
    }

    private static ClueEntry[] buildEntries() {
        List<ClueEntry> list = new ArrayList<>();

        // —— Anagram (8) — RuneLite AnagramClue TRAIL_CLUE_BEGINNER ——
        addAnagram(list, "an earl", "Ranael", 3315, 3163, "Al Kharid skirt shop");
        addAnagram(list, "carpet ahoy", "Apothecary", 3195, 3404, "SW Varrock");
        addAnagram(list, "char game disorder", "Archmage Sedridor", 3102, 9570, "Wizards' Tower basement");
        addAnagram(list, "i cord", "Doric", 2951, 3450, "N of Falador");
        addAnagram(list, "in bar", "Brian", 3026, 3246, "Port Sarim battleaxe shop");
        addAnagram(list, "rain cove", "Veronica", 3110, 3330, "Outside Draynor Manor");
        addAnagram(list, "rug deter", "Gertrude", 3151, 3412, "W of Varrock");
        addAnagram(list, "sir share red", "Hairdresser", 2944, 3381, "W Falador");
        addAnagram(list, "taunt roof", "Fortunato", 3080, 3250, "Draynor market");

        // —— Cryptic talk (5) — RuneLite CrypticClue ——
        addCrypticTalk(list, "always walking around the castle grounds",
                "Hans", 3221, 3218, "Talk Hans at Lumbridge Castle");
        addCrypticTalk(list, "duke horacio calls home",
                "Cook", 3208, 3213, "Talk Cook in Lumbridge Castle");
        addCrypticTalk(list, "village of barbarians",
                "Hunding", 3097, 3432, "Talk Hunding, Barbarian Village tower (plane 2)");
        addCrypticTalk(list, "charlie the tramp",
                "Charlie the Tramp", CHARLIE_TRAMP_TILE.getX(), CHARLIE_TRAMP_TILE.getY(),
                "Talk Charlie S of Varrock — geeft item-task");
        addCrypticTalk(list, "near the open desert",
                "Shantay", 3303, 3123, "Talk Shantay at Shantay Pass");

        // —— Cryptic special ——
        list.add(new ClueEntry(StepType.CRYPTIC_SPECIAL,
                "buried beneath the ground",
                "Talk Reldo (Varrock Palace Library, deur 3210,3495) → strange device → Hot/Cold → dig",
                new WorldPoint(3210, 3495, 0), "Reldo", null, null));

        // —— Emote (6) — RuneLite EmoteClue TRAIL_CLUE_BEGINNER ——
        list.add(emote("blow a raspberry at aris", "Raspberry", new String[]{"Gold ring", "Gold necklace"},
                3203, 3424, "Aris tent Varrock Square"));
        list.add(emote("bow to brugsen bursen", "Bow", null,
                3164, 3477, "Inside Grand Exchange"));
        list.add(emote("cheer at iffie nitter", "Cheer", new String[]{"Chef's hat", "Red cape"},
                3205, 3416, "Thessalia's Fine Clothes Varrock"));
        list.add(emote("clap at bob's brilliant axes", "Clap", new String[]{"Bronze axe", "Leather boots"},
                3231, 3203, "Bob's Brilliant Axes Lumbridge"));
        list.add(emote("panic at al kharid mine", "Panic", null,
                3303, 3271, "Al Kharid mine (groot gebied)"));
        list.add(emote("spin at flynn's mace shop", "Spin", null,
                2950, 3387, "Flynn's Mace Shop N Falador"));

        // Wiki/STASH emotes (extra beginner emotes met STASH — zelfde tier, andere teksten):
        list.add(emote("cheer in the centre of lumbridge swamp", "Cheer",
                new String[]{"Leather boots", "Leather gloves"}, 3204, 3173, "Lumbridge Swamp centre"));
        list.add(emote("cheer for the knitters of varrock", "Cheer",
                new String[]{"Cape", "Chef's hat"}, 3205, 3416, "Outside Iffie's shop"));
        list.add(emote("panic at the al kharid mine", "Panic",
                new String[]{"Bronze med helm", "Iron chainbody"}, 3303, 3271, "NE Al Kharid mine (STASH)"));

        // —— Map (5) — RuneLite BeginnerMapClue ——
        list.add(map("champions guild", 3167, 3360, "Dig with spade — map iface TRAIL_MAP01"));
        list.add(map("varrock east mine", 3290, 3373, "Dig — TRAIL_MAP02"));
        list.add(map("south of draynor bank", 3092, 3226, "Dig — TRAIL_MAP03"));
        list.add(map("standing stones", 3043, 3399, "Dig — TRAIL_MAP06"));
        list.add(map("wizards tower", 3109, 3153, "Dig — TRAIL_MAP11"));

        // —— Hot/Cold dig areas (5) — RuneLite HotColdLocation BEGINNER ——
        list.add(new ClueEntry(StepType.HOT_COLD, "draynor wheat",
                "Hot/Cold dig: wheat field next to Draynor", new WorldPoint(3120, 3282, 0),
                null, null, new String[]{"Spade", "Strange device"}));
        list.add(new ClueEntry(StepType.HOT_COLD, "ice mountain",
                "Hot/Cold dig: atop Ice Mountain", new WorldPoint(3007, 3475, 0),
                null, null, new String[]{"Spade", "Strange device"}));
        list.add(new ClueEntry(StepType.HOT_COLD, "cow field north of lumbridge",
                "Hot/Cold dig: cow field N of Lumbridge", new WorldPoint(3174, 3336, 0),
                null, null, new String[]{"Spade", "Strange device"}));
        list.add(new ClueEntry(StepType.HOT_COLD, "mushrooms north-west of draynor manor",
                "Hot/Cold dig: mushrooms NW of Draynor Manor", new WorldPoint(3096, 3379, 0),
                null, null, new String[]{"Spade", "Strange device"}));
        list.add(new ClueEntry(StepType.HOT_COLD, "north-east of al kharid mine",
                "Hot/Cold dig: NE of Al Kharid mine", new WorldPoint(3332, 3313, 0),
                null, null, new String[]{"Spade", "Strange device"}));

        return list.toArray(new ClueEntry[0]);
    }

    private static void addAnagram(List<ClueEntry> list, String match, String npc, int x, int y, String area) {
        list.add(new ClueEntry(StepType.ANAGRAM, match,
                "Talk to " + npc + " (" + area + ")",
                new WorldPoint(x, y, 0), npc, null, null));
    }

    private static void addCrypticTalk(List<ClueEntry> list, String match, String npc, int x, int y, String sol) {
        list.add(new ClueEntry(StepType.CRYPTIC_TALK, match, sol,
                new WorldPoint(x, y, 0), npc, null, null));
    }

    private static ClueEntry emote(String match, String emote, String[] equip, int x, int y, String note) {
        return new ClueEntry(StepType.EMOTE, match, note + " — emote " + emote + ", then talk Uri",
                new WorldPoint(x, y, 0), null, emote, equip);
    }

    private static void addMap(List<ClueEntry> list, String match, int x, int y, String sol) {
        list.add(map(match, x, y, sol));
    }

    private static ClueEntry map(String match, int x, int y, String sol) {
        return new ClueEntry(StepType.MAP, match, sol, new WorldPoint(x, y, 0), null, null,
                new String[]{"Spade"});
    }
}
