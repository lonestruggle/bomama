package com.combatbot;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Per-account policy voor wat de bot wel/niet bij de Grand Exchange mag kopen, plus per-categorie
 * caps. Wordt ingelezen vanuit {@link ManagedJagexAccountsStore.ManagedJagexAccountRow#geBuyTogglesBlob}
 * via {@link #setCurrentRowSupplier(Supplier)} (plugin koppelt deze hook bij {@code startUp()}).
 *
 * <p>Blob-formaat (compact, single-line, één regel per row):
 * <pre>cat1=on:cap|cat2=on:cap|...</pre>
 * <ul>
 *   <li>{@code on}: {@code 1} = mag kopen, {@code 0} = blokkeren.</li>
 *   <li>{@code cap}: max-totaal per restock-call (0 = geen extra cap, gebruik wat de handler vraagt).</li>
 * </ul>
 *
 * <p>Defaults: alle categorieën aan, geen extra caps (= huidig gedrag), zodat bestaande accounts
 * die nog géén blob hebben gewoon doorlopen zoals voorheen.
 */
public final class GeShopPolicy {

    /** Logische groepen waarop de UI checkboxen + caps biedt. */
    public enum Category {
        FOOD("food", "🍞 Voedsel (vis/brood/cake)"),
        RANGE_AMMO("arrows", "🏹 Range ammo (arrows/bolts/darts/knives)"),
        RUNES_BASIC("runes_basic", "🪄 Basic runes (Mind/Air/Water/Earth/Fire/Body/Chaos)"),
        RUNES_HIGH("runes_high", "🪄 Dure runes (Law/Death/Nature/Cosmic/Soul/Blood/Astral/Wrath)"),
        STAVES("staves", "🪄 Staffen (Staff of air/fire/water/earth)"),
        AXES("axes", "🪓 Bijlen (gathering: bronze/iron/steel/black)"),
        FISHING_TOOLS("fishing", "🎣 Vis-tools (rod/harpoon/lobster pot)"),
        GEAR("gear", "🛡️ Gear-upgrades (zwaarden/armor/amulets/...)"),
        QUEST_ITEMS("quest", "🔑 Quest/dungeon items (brass key etc)"),
        OTHER("other", "❓ Overig / niet-gecategoriseerd");

        public final String key;
        public final String label;
        Category(String key, String label) { this.key = key; this.label = label; }
        public static Category fromKey(String key) {
            if (key == null) return OTHER;
            for (Category c : values()) if (c.key.equalsIgnoreCase(key.trim())) return c;
            return OTHER;
        }
    }

    /** Eén regel in de blob. */
    public static final class CategoryRule {
        public boolean allowed = true;
        public int cap = 0; // 0 = geen extra cap

        public CategoryRule() {}
        public CategoryRule(boolean allowed, int cap) { this.allowed = allowed; this.cap = Math.max(0, cap); }
    }

    private static volatile Supplier<ManagedJagexAccountsStore.ManagedJagexAccountRow> currentRowSupplier;

    /** Plugin koppelt hier de "huidige ingelogde account-row" provider. */
    public static void setCurrentRowSupplier(Supplier<ManagedJagexAccountsStore.ManagedJagexAccountRow> supplier) {
        currentRowSupplier = supplier;
    }

    private GeShopPolicy() {}

    // ---------------------------------------------------------------------
    // Item → categorie
    // ---------------------------------------------------------------------

    /** Bepaal categorie op basis van item-naam (case-insensitive substring matching). */
    public static Category categoryFor(String itemName) {
        if (itemName == null) return Category.OTHER;
        String n = itemName.trim().toLowerCase(Locale.ROOT);
        if (n.isEmpty()) return Category.OTHER;

        // Quest / dungeon
        if (n.equals("brass key") || n.equals("dusty key") || n.equals("muddy key")
                || n.equals("garlic") || n.equals("stake") || n.equals("hammer")) {
            return Category.QUEST_ITEMS;
        }

        // Range ammo
        if (n.contains("arrow") || n.endsWith(" bolts") || n.equals("bolts")
                || n.contains("dart") || n.contains("javelin")
                || n.endsWith(" knife") || n.endsWith(" knives") || n.contains("throwing knife")
                || n.contains("throwing axe") || n.contains("thrownaxe")) {
            return Category.RANGE_AMMO;
        }

        // Staffen — staff of <element> (geen battlestaff/mystic)
        if (n.startsWith("staff of ") || n.equals("staff")) {
            return Category.STAVES;
        }

        // Runes — splits op high vs basic
        if (n.endsWith(" rune") || n.equals("rune") || n.contains(" rune")) {
            if (n.contains("law") || n.contains("death") || n.contains("nature")
                    || n.contains("cosmic") || n.contains("soul") || n.contains("blood")
                    || n.contains("wrath") || n.contains("astral")) {
                return Category.RUNES_HIGH;
            }
            return Category.RUNES_BASIC;
        }

        // Fishing tools (NIET fishing bait — dat valt onder OTHER/AMMO niet bedoeld als consumable hier)
        if (n.equals("fly fishing rod") || n.equals("fishing rod") || n.equals("big fishing net")
                || n.equals("small fishing net") || n.equals("fishing net") || n.equals("harpoon")
                || n.equals("barb-tail harpoon") || n.equals("lobster pot") || n.equals("crayfish cage")
                || n.equals("fishing bait") || n.equals("feather") || n.equals("feathers")) {
            return Category.FISHING_TOOLS;
        }

        // Bijlen (gathering — geen battleaxe). Heuristiek: eindigt op "axe" en is geen battle/war/dragon.
        // We pakken expliciet de bekende WC-axes om geen weapons te raken.
        if (n.equals("bronze axe") || n.equals("iron axe") || n.equals("steel axe")
                || n.equals("black axe") || n.equals("mithril axe") || n.equals("adamant axe")
                || n.equals("rune axe") || n.equals("dragon axe") || n.equals("crystal axe")
                || n.equals("infernal axe") || n.equals("3rd age axe") || n.equals("gilded axe")) {
            return Category.AXES;
        }

        // Voedsel (cooked vis + brood + cake)
        if (isFood(n)) return Category.FOOD;

        // Gear — wapens, armor, schilden. Heuristiek: bevat metaal-prefix EN een gear-suffix.
        if (looksLikeGear(n)) return Category.GEAR;

        return Category.OTHER;
    }

    private static boolean isFood(String n) {
        // Common food items - non-exhaustive maar dekt de bot's restocks.
        final List<String> foods = Arrays.asList(
                "shrimps", "shrimp", "anchovies", "sardine", "herring", "trout", "salmon",
                "tuna", "lobster", "swordfish", "monkfish", "shark", "anglerfish",
                "bass", "pike", "cod", "mackerel", "karambwan", "manta ray", "sea turtle",
                "bread", "bread roll", "cake", "chocolate cake", "slice of cake",
                "stew", "meat pie", "apple pie", "summer pie", "wild pie", "admiral pie",
                "potato with butter", "baked potato", "potato with cheese",
                "chocolate bar", "banana", "peach", "jug of wine"
        );
        for (String f : foods) {
            if (n.equals(f) || n.equals("cooked " + f) || n.endsWith(" " + f)) return true;
        }
        return false;
    }

    private static boolean looksLikeGear(String n) {
        final String[] tiers = {"bronze ", "iron ", "steel ", "black ", "white ",
                "mithril ", "adamant ", "rune ", "dragon "};
        final String[] suffixes = {
                " sword", " scimitar", " longsword", " dagger", " mace", " warhammer",
                " battleaxe", " halberd", " spear", " hasta", " 2h sword", " hatchet",
                " kiteshield", " sq shield", " defender",
                " full helm", " med helm", " helm",
                " platebody", " chainbody", " platelegs", " plateskirt",
                " gauntlets", " gloves", " boots",
                " amulet of strength", " amulet of magic", " amulet of accuracy",
                " amulet of power", " amulet of glory"
        };
        for (String t : tiers) {
            if (!n.startsWith(t)) continue;
            for (String s : suffixes) {
                if (n.endsWith(s)) return true;
            }
        }
        // Naked items zonder tier-prefix:
        if (n.equals("amulet of power") || n.equals("amulet of glory") || n.equals("amulet of strength")
                || n.equals("amulet of magic")) {
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------------
    // Lookup
    // ---------------------------------------------------------------------

    /** {@code true} als deze bot dit item nu mag kopen volgens de huidige account-row. */
    public static boolean allowsBuy(String itemName) {
        Category cat = categoryFor(itemName);
        CategoryRule rule = ruleFor(cat);
        return rule.allowed;
    }

    /**
     * Pas een categorie-cap toe op een door de handler gevraagde {@code requestedQuantity}.
     * Returns het uiteindelijke maximum: {@code min(requestedQuantity, cap)} als cap > 0,
     * anders gewoon {@code requestedQuantity}.
     */
    public static int effectiveQuantity(String itemName, int requestedQuantity) {
        if (requestedQuantity <= 0) return requestedQuantity;
        CategoryRule rule = ruleFor(categoryFor(itemName));
        if (rule.cap <= 0) return requestedQuantity;
        return Math.min(requestedQuantity, rule.cap);
    }

    private static CategoryRule ruleFor(Category cat) {
        Map<Category, CategoryRule> rules = currentRules();
        CategoryRule r = rules.get(cat);
        return r != null ? r : new CategoryRule(true, 0);
    }

    /**
     * Huidige rules (uit account-row blob) of {@link #defaultRules()} (alles aan, geen caps).
     */
    public static Map<Category, CategoryRule> currentRules() {
        Supplier<ManagedJagexAccountsStore.ManagedJagexAccountRow> sup = currentRowSupplier;
        ManagedJagexAccountsStore.ManagedJagexAccountRow row = (sup == null) ? null : safeGet(sup);
        if (row == null) return defaultRules();
        return parseBlob(row.geBuyTogglesBlob);
    }

    private static ManagedJagexAccountsStore.ManagedJagexAccountRow safeGet(
            Supplier<ManagedJagexAccountsStore.ManagedJagexAccountRow> sup) {
        try { return sup.get(); } catch (Throwable ignored) { return null; }
    }

    /** Default = alle categorieën aan, geen extra caps. */
    public static Map<Category, CategoryRule> defaultRules() {
        LinkedHashMap<Category, CategoryRule> out = new LinkedHashMap<>();
        for (Category c : Category.values()) {
            // Suggested default cap voor RUNES_HIGH = 30 (om 160-buy weg te houden ook als de
            // GeRestockHelper bug ergens terugkomt). User kan dat zelf aanpassen in UI.
            int defaultCap = (c == Category.RUNES_HIGH) ? 30 : 0;
            out.put(c, new CategoryRule(true, defaultCap));
        }
        return out;
    }

    // ---------------------------------------------------------------------
    // Parse / serialize
    // ---------------------------------------------------------------------

    public static Map<Category, CategoryRule> parseBlob(String blob) {
        Map<Category, CategoryRule> rules = defaultRules();
        if (blob == null || blob.trim().isEmpty()) return rules;
        for (String chunk : blob.split("\\|")) {
            if (chunk == null) continue;
            String c = chunk.trim();
            if (c.isEmpty()) continue;
            int eq = c.indexOf('=');
            if (eq <= 0) continue;
            String key = c.substring(0, eq).trim();
            String val = c.substring(eq + 1).trim();
            Category cat = Category.fromKey(key);
            int colon = val.indexOf(':');
            String onPart = colon < 0 ? val : val.substring(0, colon);
            String capPart = colon < 0 ? "0" : val.substring(colon + 1);
            CategoryRule r = new CategoryRule();
            r.allowed = !"0".equals(onPart.trim());
            try { r.cap = Math.max(0, Integer.parseInt(capPart.trim())); }
            catch (NumberFormatException ignored) { r.cap = 0; }
            rules.put(cat, r);
        }
        return rules;
    }

    public static String serializeBlob(Map<Category, CategoryRule> rules) {
        if (rules == null || rules.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<Category, CategoryRule> e : rules.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            if (!first) sb.append('|');
            first = false;
            CategoryRule r = e.getValue();
            sb.append(e.getKey().key).append('=')
                    .append(r.allowed ? '1' : '0').append(':').append(Math.max(0, r.cap));
        }
        return sb.toString();
    }
}
