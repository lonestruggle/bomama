package com.combatbot;

import net.storm.api.domain.widgets.IWidget;
import net.storm.api.widgets.Tab;
import net.storm.sdk.widgets.Tabs;
import net.storm.sdk.widgets.Widgets;

/**
 * Opent de inventory-tab: optioneel via muisklik op het tab-widget (iface 161,62 / id 10551358),
 * anders via {@link Tabs#open(Tab)} zoals voorheen.
 */
public final class InventoryTabHelper {

    /** Alias in {@link WidgetRegistry} — dump: humanLabel Inventory, iface=161,62. */
    public static final String ALIAS_INVENTORY_TAB = "inventoryTab";

    private static final int INVENTORY_TAB_GROUP = 161;
    private static final int INVENTORY_TAB_CHILD = 62;

    static {
        WidgetRegistry.registerIface(ALIAS_INVENTORY_TAB, INVENTORY_TAB_GROUP, INVENTORY_TAB_CHILD, "Inventory");
    }

    private InventoryTabHelper() {
    }

    /** True als de speler de Inventory-tab open heeft (niet Equipment/Prayer/…). */
    public static boolean isInventoryTabOpen() {
        try {
            return Tabs.isOpen(Tab.INVENTORY);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Vóór een inventory-item actie (eten, wield, drop, …): als {@code openInventoryViaMouseClick}
     * aan staat en de tab nog niet open is, klik op het Inventory-tab-widget.
     */
    public static void ensureInventoryTabForAction(CombatBotConfig config) {
        if (config == null || !config.openInventoryViaMouseClick()) {
            return;
        }
        openInventoryTab(config);
    }

    /**
     * Zorg dat de inventory-tab actief is.
     *
     * @return true als de tab al open was of een open-poging is gedaan
     */
    public static boolean openInventoryTab(CombatBotConfig config) {
        try {
            if (Tabs.isOpen(Tab.INVENTORY)) {
                return true;
            }
        } catch (Throwable ignored) {
        }

        if (config != null && config.openInventoryViaMouseClick()) {
            if (clickInventoryTabWidget()) {
                return true;
            }
            return openViaTabsSdk();
        }
        return openViaTabsSdk();
    }

    private static boolean openViaTabsSdk() {
        try {
            Tabs.open(Tab.INVENTORY);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean clickInventoryTabWidget() {
        IWidget w = WidgetRegistry.getVerified(ALIAS_INVENTORY_TAB);
        if (w == null) {
            try {
                w = Widgets.get(INVENTORY_TAB_GROUP, INVENTORY_TAB_CHILD);
            } catch (Throwable ignored) {
                w = null;
            }
        }
        if (w == null) {
            return false;
        }
        try {
            if (w.isHidden()) {
                return false;
            }
            if (HumanMouseClickHelper.smoothMoveAndLeftClickWidget(w)) {
                return true;
            }
            // Fallback: directe widget-actie (zonder zichtbare muisbeweging)
            if (w.hasAction("Inventory")) {
                w.interact("Inventory");
            } else {
                w.interact(0);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
