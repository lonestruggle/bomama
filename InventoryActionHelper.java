package com.combatbot;

import net.storm.api.domain.items.IInventoryItem;

/**
 * Alle interacties op inventory-items lopen hier doorheen zodat — met
 * {@link CombatBotConfig#openInventoryViaMouseClick()} aan — eerst de inventory-tab
 * geopend wordt via het tab-widget (iface 161,62).
 */
public final class InventoryActionHelper {

    private InventoryActionHelper() {
    }

    public static void interact(CombatBotConfig config, IInventoryItem item, String action) {
        if (item == null || action == null || action.isEmpty()) {
            return;
        }
        InventoryTabHelper.ensureInventoryTabForAction(config);
        try {
            item.interact(action);
        } catch (Throwable ignored) {
        }
    }

    public static void interact(CombatBotConfig config, IInventoryItem item, int menuIndex) {
        if (item == null) {
            return;
        }
        InventoryTabHelper.ensureInventoryTabForAction(config);
        try {
            item.interact(menuIndex);
        } catch (Throwable ignored) {
        }
    }
}
