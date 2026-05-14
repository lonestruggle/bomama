package com.combatbot;

import com.google.gson.Gson;
import net.storm.api.domain.items.IInventoryItem;
import net.storm.sdk.items.Bank;
import net.storm.sdk.items.Inventory;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Bouwt bank-snapshots voor {@link AccountStateJsonStore} (o.a. {@code knownBankCoins}) terwijl de bank open is.
 */
public final class BankSnapshotHelper {

    private static final Gson GSON = new Gson();

    private BankSnapshotHelper() {
    }

    /**
     * Schrijft JSON-snapshot als de bank-interface open is. {@code markCalibrated} alleen {@code true} bij
     * eerste kalibratie of handmatige “calibreer bij login” — routine sessies zetten alleen de bekende velden.
     */
    public static void writeSnapshotIfBankOpen(String displayName, boolean markCalibrated) {
        if (displayName == null || displayName.trim().isEmpty() || !Bank.isOpen()) {
            return;
        }
        String csv = buildKnownBankItemsCsv();
        String json = buildKnownBankItemQtyJson();
        long invCoins = 0;
        long bankCoins = 0;
        try {
            IInventoryItem coins = Inventory.getFirst("Coins");
            invCoins = coins != null ? Math.max(0L, (long) coins.getQuantity()) : 0L;
        } catch (Throwable ignored) {
        }
        try {
            if (Bank.contains("Coins")) {
                var bc = Bank.getFirst("Coins");
                bankCoins = bc != null ? Math.max(0L, (long) bc.getQuantity()) : 0L;
            }
        } catch (Throwable ignored) {
        }
        AccountStateJsonStore.putBankSnapshot(displayName.trim(), csv, json, bankCoins, invCoins, markCalibrated);

        // Spiegel chronicle-deel naar de eigen velden zodat handlers/helpers het zonder JSON-parse kunnen lezen.
        try {
            int cardsBank = 0;
            try {
                if (Bank.contains(ChronicleHelper.TELEPORT_CARD)) {
                    var tc = Bank.getFirst(ChronicleHelper.TELEPORT_CARD);
                    cardsBank = tc != null ? Math.max(0, tc.getQuantity()) : 0;
                }
            } catch (Throwable ignored) {
            }
            ChronicleHelper.syncFromBankSnapshot(displayName.trim(), cardsBank);
        } catch (Throwable ignored) {
        }
    }

    static String[] bankSnapshotKeyItems() {
        return new String[] {
                "Coins", "Tinderbox",
                "Bronze axe", "Iron axe", "Steel axe", "Black axe", "Mithril axe", "Adamant axe", "Rune axe",
                "Bronze pickaxe", "Iron pickaxe", "Steel pickaxe", "Black pickaxe", "Mithril pickaxe", "Adamant pickaxe", "Rune pickaxe",
                "Bronze sword", "Iron sword", "Steel sword", "Black sword", "Mithril sword", "Adamant sword", "Rune sword",
                "Bronze longsword", "Iron longsword", "Steel longsword", "Black longsword", "Mithril longsword", "Adamant longsword", "Rune longsword",
                "Bronze scimitar", "Iron scimitar", "Steel scimitar", "Black scimitar", "Mithril scimitar", "Adamant scimitar", "Rune scimitar",
                "Bronze dagger", "Iron dagger", "Steel dagger", "Black dagger", "Mithril dagger", "Adamant dagger", "Rune dagger",
                "Bronze battleaxe", "Iron battleaxe", "Steel battleaxe", "Black battleaxe", "Mithril battleaxe", "Adamant battleaxe", "Rune battleaxe",
                "Bronze med helm", "Iron med helm", "Steel med helm", "Black med helm", "Mithril med helm", "Adamant med helm", "Rune med helm",
                "Bronze full helm", "Iron full helm", "Steel full helm", "Black full helm", "Mithril full helm", "Adamant full helm", "Rune full helm",
                "Bronze chainbody", "Iron chainbody", "Steel chainbody", "Black chainbody", "Mithril chainbody", "Adamant chainbody", "Rune chainbody",
                "Bronze platebody", "Iron platebody", "Steel platebody", "Black platebody", "Mithril platebody", "Adamant platebody", "Rune platebody",
                "Bronze platelegs", "Iron platelegs", "Steel platelegs", "Black platelegs", "Mithril platelegs", "Adamant platelegs", "Rune platelegs",
                "Bronze plateskirt", "Iron plateskirt", "Steel plateskirt", "Black plateskirt", "Mithril plateskirt", "Adamant plateskirt", "Rune plateskirt",
                "Wooden shield", "Bronze sq shield", "Iron sq shield", "Steel sq shield", "Black sq shield", "Mithril sq shield", "Adamant sq shield", "Rune sq shield",
                "Bronze kiteshield", "Iron kiteshield", "Steel kiteshield", "Black kiteshield", "Mithril kiteshield", "Adamant kiteshield", "Rune kiteshield",
                "Leather gloves", "Leather boots", "Fighting boots", "Fancy boots",
                "Fly fishing rod", "Feather", "Fishing bait",
                "Fishing rod", "Small fishing net",
                "Raw shrimps", "Shrimps", "Raw sardine", "Sardine", "Raw herring", "Herring",
                "Raw trout", "Trout", "Raw salmon", "Salmon",
                "Shortbow", "Longbow", "Bronze arrow", "Iron arrow", "Steel arrow", "Mithril arrow", "Adamant arrow", "Rune arrow",
                "Leather cowl", "Coif", "Leather body", "Hardleather body", "Studded body", "Green d'hide body",
                "Leather chaps", "Studded chaps", "Green d'hide chaps", "Leather vambraces", "Green d'hide vambraces",
                "Staff of air", "Staff of fire", "Staff of water", "Staff of earth",
                "Wizard hat", "Blue wizard hat", "Black wizard hat", "Wizard robe", "Blue wizard robe", "Black wizard robe",
                "Monk's robe", "Monk's robe top", "Zamorak monk bottom", "Mystic hat", "Mystic robe top", "Mystic robe bottom", "Mystic gloves", "Mystic boots",
                "Air rune", "Mind rune", "Chaos rune", "Law rune", "Fire rune", "Water rune", "Earth rune", "Body rune",
                "Amulet of power", "Amulet of strength", "Amulet of accuracy", "Amulet of magic", "Amulet of defence",
                "Hammer", "Garlic", "Stake",
                "Black bead", "Red bead", "Yellow bead", "White bead", "Mind talisman", "Fiendish ashes",
                "Brass key",
                // Chronicle teleport (Diango)
                ChronicleHelper.CHRONICLE, ChronicleHelper.TELEPORT_CARD
        };
    }

    static String buildKnownBankItemsCsv() {
        String[] keyItems = bankSnapshotKeyItems();
        Set<String> present = new LinkedHashSet<>();
        for (String item : keyItems) {
            try {
                if (Bank.contains(item)) {
                    present.add(item);
                }
            } catch (Throwable ignored) {
            }
        }
        return String.join(",", present);
    }

    static String buildKnownBankItemQtyJson() {
        String[] keyItems = bankSnapshotKeyItems();
        Map<String, Integer> qty = new LinkedHashMap<>();
        for (String item : keyItems) {
            int n = 0;
            try {
                if (Bank.contains(item)) {
                    var bi = Bank.getFirst(item);
                    n = bi != null ? Math.max(1, bi.getQuantity()) : 1;
                }
            } catch (Throwable ignored) {
            }
            if (n > 0) {
                qty.put(item, n);
            }
        }
        return GSON.toJson(qty);
    }
}
