package com.combatbot;

import com.google.gson.Gson;
import net.runelite.client.util.Text;
import net.storm.api.domain.items.IItem;
import net.storm.api.widgets.EquipmentSlot;
import net.storm.sdk.game.Game;
import net.storm.sdk.items.Equipment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Snapshot van alle equipped items per slot → {@link AccountStateJsonStore}.
 */
public final class EquipmentSnapshotHelper {

    private static final Gson GSON = new Gson();

    private EquipmentSnapshotHelper() {
    }

    public static void writeSnapshotIfLoggedIn(String displayName) {
        if (displayName == null || displayName.trim().isEmpty()) {
            return;
        }
        if (!Game.isLoggedIn()) {
            return;
        }
        List<EquippedSlotSnapshot> slots = buildEquippedSlotList();
        String slotsJson = GSON.toJson(slots);
        Map<String, Integer> qty = new LinkedHashMap<>();
        Map<String, Integer> ids = new LinkedHashMap<>();
        for (EquippedSlotSnapshot row : slots) {
            if (row.name == null || row.name.isEmpty()) {
                continue;
            }
            int q = Math.max(1, row.qty);
            qty.merge(row.name, q, Integer::sum);
            ids.put(row.name, row.id);
        }
        AccountStateJsonStore.putEquippedSnapshot(
                displayName.trim(),
                GSON.toJson(qty),
                GSON.toJson(ids),
                slotsJson);
    }

    /**
     * Alle bezetten slots in vaste volgorde (HEAD … AMMO) via {@link Equipment#fromSlot}.
     */
    public static List<EquippedSlotSnapshot> buildEquippedSlotList() {
        List<EquippedSlotSnapshot> rows = new ArrayList<>();
        for (EquipmentSlot equipSlot : EquipmentSlot.values()) {
            try {
                IItem item = Equipment.fromSlot(equipSlot);
                if (item == null || item.getName() == null) {
                    continue;
                }
                String name = Text.removeTags(item.getName()).trim();
                if (name.isEmpty()) {
                    continue;
                }
                rows.add(new EquippedSlotSnapshot(
                        equipSlot.name(),
                        name,
                        item.getId(),
                        Math.max(1, item.getQuantity())));
            } catch (Throwable ignored) {
            }
        }
        return rows;
    }

    /** @deprecated Gebruik {@link #buildEquippedSlotList()}; blijft voor backwards-compat. */
    public static String buildEquippedItemQtyJson() {
        Map<String, Integer> qty = new LinkedHashMap<>();
        for (EquippedSlotSnapshot row : buildEquippedSlotList()) {
            qty.merge(row.name, Math.max(1, row.qty), Integer::sum);
        }
        return GSON.toJson(qty);
    }

    public static String buildEquippedItemIdJson() {
        Map<String, Integer> ids = new LinkedHashMap<>();
        for (EquippedSlotSnapshot row : buildEquippedSlotList()) {
            ids.put(row.name, row.id);
        }
        return GSON.toJson(ids);
    }

    public static String buildEquippedSlotsJson() {
        return GSON.toJson(buildEquippedSlotList());
    }
}
