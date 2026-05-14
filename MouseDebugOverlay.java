package com.combatbot;

import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Point;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

import javax.inject.Inject;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;

/**
 * Eenvoudige debug-overlay voor cursor zichtbaarheid:
 * tekent een kleine marker + XY-label op de huidige canvaspositie van de muis.
 */
public class MouseDebugOverlay extends Overlay {
    private int lastX = -1;
    private int lastY = -1;
    private long lastSeenMs = 0L;
    private static final long STICKY_MS = 1500L;

    @Inject
    private Client client;

    @Inject
    private CombatBotConfig config;

    @Inject
    MouseDebugOverlay() {
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ALWAYS_ON_TOP);
        setPriority(OverlayPriority.HIGH);
    }

    @Override
    public Dimension render(Graphics2D g) {
        if (client == null || !config.debugMouseOverlay()) {
            return null;
        }
        GameState gs = client.getGameState();
        if (gs != GameState.LOGGED_IN && gs != GameState.LOGIN_SCREEN) {
            return null;
        }

        Point p = client.getMouseCanvasPosition();
        int x = -1;
        int y = -1;
        if (p != null) {
            x = p.getX();
            y = p.getY();
            if (x >= 0 && y >= 0) {
                lastX = x;
                lastY = y;
                lastSeenMs = System.currentTimeMillis();
            }
        }

        if (x < 0 || y < 0) {
            if (lastX < 0 || lastY < 0 || (System.currentTimeMillis() - lastSeenMs) > STICKY_MS) {
                return null;
            }
            x = lastX;
            y = lastY;
        }

        g.setColor(new Color(0, 255, 140, 210));
        g.drawOval(x - 7, y - 7, 14, 14);
        g.drawLine(x - 11, y, x + 11, y);
        g.drawLine(x, y - 11, x, y + 11);

        String txt = "Mouse: " + x + "," + y;
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 11));
        int tx = x + 14;
        int ty = y - 10;

        g.setColor(new Color(0, 0, 0, 180));
        g.fillRoundRect(tx - 4, ty - 11, g.getFontMetrics().stringWidth(txt) + 8, 16, 6, 6);
        g.setColor(new Color(220, 255, 235));
        g.drawString(txt, tx, ty);
        return null;
    }
}
