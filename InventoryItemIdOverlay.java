package com.combatbot;

import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

import javax.inject.Inject;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;

/**
 * Tekent item-ID's op inventory-slots (iface 149) — niet aan de muis, vast op elk item-vakje.
 */
public class InventoryItemIdOverlay extends Overlay {

    /** Lege inventory-slot in widget-API — zie {@link BeginnerClueReference#EMPTY_INVENTORY_WIDGET_ITEM_ID}. */

    @Inject
    private Client client;

    @Inject
    private CombatBotConfig config;

    @Inject
    InventoryItemIdOverlay() {
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setPriority(OverlayPriority.HIGH);
    }

    @Override
    public Dimension render(Graphics2D g) {
        if (!config.debugInventoryItemIdOverlay()
                || client == null
                || client.getGameState() != GameState.LOGGED_IN) {
            return null;
        }

        if (!InventoryTabHelper.isInventoryTabOpen()) {
            return null;
        }

        Widget inventory = client.getWidget(WidgetInfo.INVENTORY);
        if (inventory == null || inventory.isHidden()) {
            return null;
        }

        Widget[] children = inventory.getChildren();
        if (children == null || children.length == 0) {
            return null;
        }

        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 9));

        for (Widget slot : children) {
            if (slot == null || slot.isHidden()) {
                continue;
            }
            int itemId = slot.getItemId();
            if (!isRealInventoryItemId(itemId)) {
                continue;
            }

            Rectangle bounds = slot.getBounds();
            if (bounds == null || bounds.width <= 0 || bounds.height <= 0) {
                continue;
            }

            String clueHint = ClueScrollHelper.tierLabelForItemId(itemId);
            String label = clueHint != null ? itemId + " " + clueHint : String.valueOf(itemId);
            int qty = slot.getItemQuantity();
            if (qty > 1) {
                label += " x" + qty;
            }

            int textW = g.getFontMetrics().stringWidth(label);
            int textH = g.getFontMetrics().getHeight();
            int x = bounds.x + 1;
            int y = bounds.y + textH - 2;

            g.setColor(new Color(0, 0, 0, 170));
            g.fillRect(x - 1, bounds.y, Math.min(textW + 3, bounds.width), textH);
            g.setColor(clueHint != null ? new Color(255, 220, 120) : new Color(140, 255, 160));
            g.drawString(label, x, y);
        }

        return null;
    }

    static boolean isRealInventoryItemId(int itemId) {
        return itemId > 0 && itemId != BeginnerClueReference.EMPTY_INVENTORY_WIDGET_ITEM_ID;
    }
}
