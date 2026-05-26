package com.combatbot;

import net.storm.api.domain.items.IInventoryItem;
import net.storm.sdk.items.Equipment;
import net.storm.sdk.items.Inventory;

import java.util.Locale;

/**
 * Inventarisruimte vóór bank-withdraw: stackables vs. niet-stackable (1 slot per stuk).
 */
public final class BankInventoryHelper {

    private BankInventoryHelper() {
    }

    public static int freeSlots() {
        try {
            return Math.max(0, Inventory.getFreeSlots());
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * Ruwe heuristiek: runes, pijlen, bait, coins, … stacken in één slot.
     */
    public static boolean isLikelyStackable(String itemName) {
        if (itemName == null || itemName.trim().isEmpty()) {
            return true;
        }
        String n = itemName.toLowerCase(Locale.ROOT);
        if (n.equals("coins")) {
            return true;
        }
        return n.contains("rune")
                || n.contains("arrow")
                || n.contains("bolt")
                || n.contains("bait")
                || n.contains("feather")
                || n.contains("ashes")
                || n.contains("bones")
                || n.contains("charm")
                || n.contains("essence")
                || n.contains("nail")
                || n.contains("ore")
                || n.contains("log");
    }

    /**
     * Extra inv-slots nodig om {@code quantity} van dit item te kunnen opnemen (na deposit-fase).
     */
    public static int slotsNeededToHold(String itemName, int quantity, int alreadyInInv) {
        if (quantity <= 0) {
            return 0;
        }
        if (isLikelyStackable(itemName)) {
            return alreadyInInv > 0 ? 0 : 1;
        }
        return quantity;
    }

    /**
     * Slots nodig om van {@code have} naar {@code target} te gaan voor één requirement-regel.
     */
    public static int slotsNeededForWithdrawDelta(String itemName, int have, int target) {
        if (target <= have) {
            return 0;
        }
        return slotsNeededToHold(itemName, target - have, have);
    }

    public static boolean hasEquippedNamed(String itemName) {
        if (itemName == null || itemName.isEmpty()) {
            return false;
        }
        try {
            return Equipment.contains(item -> item != null && item.getName() != null
                    && item.getName().equalsIgnoreCase(itemName));
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static int countInInventory(String itemName) {
        if (itemName == null || itemName.isEmpty()) {
            return 0;
        }
        try {
            return Math.max(0, Inventory.getCount(true, itemName));
        } catch (Throwable ignored) {
            try {
                IInventoryItem it = Inventory.getFirst(itemName);
                return it != null ? Math.max(1, it.getQuantity()) : 0;
            } catch (Throwable ignored2) {
                return 0;
            }
        }
    }

    public static boolean hasSpaceForWithdraw(String itemName, int withdrawQty, int haveNow) {
        if (withdrawQty <= 0) {
            return true;
        }
        int need = slotsNeededToHold(itemName, withdrawQty, haveNow);
        return freeSlots() >= need;
    }
}
