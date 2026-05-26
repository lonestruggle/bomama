package com.combatbot;

import net.runelite.client.util.Text;
import net.storm.api.domain.items.IItem;
import net.storm.api.widgets.EquipmentSlot;
import net.storm.sdk.items.Equipment;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Leest equipped-snapshot uit {@link AccountStateJsonStore} en vergelijkt met live {@link Equipment}.
 */
public final class EquipmentSnapshotPlanner {

    private EquipmentSnapshotPlanner() {
    }

    public static boolean hasPersistedSnapshot(String displayName) {
        if (displayName == null || displayName.isEmpty()) {
            return false;
        }
        AccountStateJsonStore.AccountEntry e = AccountStateJsonStore.getEntry(displayName);
        if (e == null) {
            return false;
        }
        if (e.knownEquippedSlotsJson != null && !e.knownEquippedSlotsJson.trim().isEmpty()) {
            return true;
        }
        return e.knownEquippedItemQtyJson != null && !e.knownEquippedItemQtyJson.trim().isEmpty();
    }

    public static List<EquippedSlotSnapshot> knownEquippedSlots(String displayName) {
        if (displayName == null || displayName.isEmpty()) {
            return Collections.emptyList();
        }
        return AccountStateJsonStore.knownEquippedSlots(AccountStateJsonStore.getEntry(displayName));
    }

    /** Live slot eerst, anders snapshot. {@code slotName} = {@link EquipmentSlot#name()} (bijv. {@code WEAPON}). */
    public static EquippedSlotSnapshot itemInSlot(String displayName, String slotName) {
        EquippedSlotSnapshot live = itemInSlotLive(slotName);
        if (live != null) {
            return live;
        }
        if (slotName == null || slotName.isEmpty()) {
            return null;
        }
        for (EquippedSlotSnapshot row : knownEquippedSlots(displayName)) {
            if (row.slot != null && row.slot.equalsIgnoreCase(slotName.trim())) {
                return row;
            }
        }
        return null;
    }

    public static EquippedSlotSnapshot itemInSlotLive(String slotName) {
        if (slotName == null || slotName.isEmpty()) {
            return null;
        }
        EquipmentSlot equipSlot;
        try {
            equipSlot = EquipmentSlot.valueOf(slotName.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
        try {
            IItem item = Equipment.fromSlot(equipSlot);
            if (item == null || item.getName() == null) {
                return null;
            }
            String name = Text.removeTags(item.getName()).trim();
            if (name.isEmpty()) {
                return null;
            }
            return new EquippedSlotSnapshot(
                    equipSlot.name(),
                    name,
                    item.getId(),
                    Math.max(1, item.getQuantity()));
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean hasItemInSlot(String displayName, String slotName, String itemName) {
        if (itemName == null || itemName.isEmpty()) {
            return false;
        }
        EquippedSlotSnapshot row = itemInSlot(displayName, slotName);
        return row != null && row.name != null && row.name.equalsIgnoreCase(itemName.trim());
    }

    public static boolean slotContainsNamePart(String displayName, String slotName, String namePart) {
        if (namePart == null || namePart.isEmpty()) {
            return false;
        }
        EquippedSlotSnapshot row = itemInSlot(displayName, slotName);
        if (row == null || row.name == null) {
            return false;
        }
        return row.name.toLowerCase(Locale.ROOT).contains(namePart.toLowerCase(Locale.ROOT));
    }

    public static int knownEquippedQty(String displayName, String itemName) {
        return AccountStateJsonStore.knownEquippedQty(AccountStateJsonStore.getEntry(displayName), itemName);
    }

    /**
     * Live equipment eerst; anders snapshot (kan 1–2s oud zijn tussen ticks).
     */
    public static boolean hasEquippedItem(String displayName, String itemName) {
        if (itemName == null || itemName.isEmpty()) {
            return false;
        }
        if (isEquippedLive(itemName)) {
            return true;
        }
        return knownEquippedQty(displayName, itemName) > 0;
    }

    public static boolean isEquippedLive(String itemName) {
        if (itemName == null || itemName.isEmpty()) {
            return false;
        }
        try {
            return Equipment.contains(item -> item != null && item.getName() != null
                    && Text.removeTags(item.getName()).trim().equalsIgnoreCase(itemName.trim()));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Snapshot zegt: dit item zit niet (meer) aan — bank-withdraw voor equip kan nodig zijn. */
    public static boolean snapshotConfirmsNotEquipped(String displayName, String itemName) {
        if (!hasPersistedSnapshot(displayName)) {
            return false;
        }
        return knownEquippedQty(displayName, itemName) <= 0 && !isEquippedLive(itemName);
    }

    public static boolean skipBankWithdrawBecauseEquipped(String displayName, String itemName) {
        return hasEquippedItem(displayName, itemName);
    }

    public static boolean nameContainsEquipped(String displayName, String namePart) {
        if (namePart == null || namePart.isEmpty()) {
            return false;
        }
        String needle = namePart.toLowerCase(Locale.ROOT);
        try {
            var live = Equipment.getAll(item -> item != null && item.getName() != null
                    && item.getName().toLowerCase(Locale.ROOT).contains(needle));
            if (live != null && !live.isEmpty()) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        for (EquippedSlotSnapshot row : knownEquippedSlots(displayName)) {
            if (row.name != null && row.name.toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    public static Map<String, Integer> knownEquippedQtyMap(String displayName) {
        if (displayName == null || displayName.isEmpty()) {
            return new LinkedHashMap<>();
        }
        return AccountStateJsonStore.knownEquippedQtyMap(AccountStateJsonStore.getEntry(displayName));
    }

    public static int equippedSlotCount(String displayName) {
        List<EquippedSlotSnapshot> slots = knownEquippedSlots(displayName);
        if (!slots.isEmpty()) {
            return slots.size();
        }
        return knownEquippedQtyMap(displayName).size();
    }

    /** Korte samenvatting voor UI/debug: {@code WEAPON=Shortbow, AMULET=Amulet of power}. */
    public static String formatEquippedSlotsBrief(String displayName) {
        List<EquippedSlotSnapshot> slots = knownEquippedSlots(displayName);
        if (slots.isEmpty()) {
            return "-";
        }
        StringBuilder sb = new StringBuilder();
        for (EquippedSlotSnapshot row : slots) {
            if (row.slot == null || row.name == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(row.slot).append('=').append(row.name);
        }
        return sb.length() > 0 ? sb.toString() : "-";
    }
}
