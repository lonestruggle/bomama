package com.combatbot;

import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Point;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;
import net.runelite.api.widgets.Widget;
import net.runelite.client.util.Text;

import javax.inject.Inject;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Toont compacte widget-metadata voor het kleinste zichtbare widget onder de muiscursor
 * (zelfde id/iface-notatie als de widget-dump).
 */
public class WidgetHoverOverlay extends Overlay {

    private static final int MAX_GROUP = 900;
    private static final int MAX_CHILD = 120;

    @Inject
    private Client client;

    @Inject
    private CombatBotConfig config;

    private int lastMouseX = Integer.MIN_VALUE;
    private int lastMouseY = Integer.MIN_VALUE;
    private int lastScanTick = -1;
    private Widget cachedWidget;

    @Inject
    WidgetHoverOverlay() {
        setPosition(OverlayPosition.TOOLTIP);
        setLayer(OverlayLayer.ALWAYS_ON_TOP);
        setPriority(OverlayPriority.LOW);
    }

    @Override
    public Dimension render(Graphics2D g) {
        if (!config.widgetHoverInspectorEnabled()
                || client == null
                || client.getGameState() != GameState.LOGGED_IN) {
            return null;
        }

        Point mouse = client.getMouseCanvasPosition();
        int tick = client.getTickCount();
        boolean moved = mouse.getX() != lastMouseX || mouse.getY() != lastMouseY;
        if (moved || tick != lastScanTick) {
            lastMouseX = mouse.getX();
            lastMouseY = mouse.getY();
            lastScanTick = tick;
            cachedWidget = findSmallestWidgetAt(mouse.getX(), mouse.getY());
        }

        Widget w = cachedWidget;
        if (w == null) {
            return null;
        }

        String name = clean(w.getName());
        String text = clean(w.getText());
        String actions = formatActions(w);
        String human = WidgetDebugHelper.deriveHumanLabel(name, text, actions);
        int packed = w.getId();
        int ig = packed > 0 ? (packed >>> 16) : -1;
        int ic = packed > 0 ? (packed & 0xFFFF) : -1;

        List<String> lines = new ArrayList<>(8);
        lines.add("humanLabel: " + (human.isEmpty() ? "—" : human));
        lines.add("id=" + packed + "  iface=" + ig + "," + ic);
        if (!name.isEmpty()) {
            lines.add("name: " + trunc(name, 56));
        }
        if (!text.isEmpty()) {
            lines.add("text: " + trunc(text, 56));
        }
        if (!actions.isEmpty()) {
            lines.add("actions: " + trunc(actions, 56));
        }
        Rectangle b = w.getBounds();
        if (b.width > 0 && b.height > 0) {
            lines.add(String.format(Locale.ROOT, "bounds: %d,%d %dx%d", b.x, b.y, b.width, b.height));
        }

        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Font font = new Font(Font.MONOSPACED, Font.PLAIN, 11);
        g.setFont(font);
        int pad = 6;
        int lineH = g.getFontMetrics().getHeight();
        int maxW = 0;
        for (String line : lines) {
            maxW = Math.max(maxW, g.getFontMetrics().stringWidth(line));
        }
        int boxW = maxW + pad * 2;
        int boxH = lines.size() * lineH + pad * 2 - 2;

        g.setColor(new Color(0, 0, 0, 200));
        g.fill(new RoundRectangle2D.Float(0, 0, boxW, boxH, 8, 8));
        g.setColor(new Color(120, 220, 140));
        g.draw(new RoundRectangle2D.Float(0, 0, boxW, boxH, 8, 8));

        g.setColor(new Color(240, 248, 255));
        int y = pad + g.getFontMetrics().getAscent();
        for (String line : lines) {
            g.drawString(line, pad, y);
            y += lineH;
        }
        return new Dimension(boxW, boxH);
    }

    private static String trunc(String s, int max) {
        if (s == null || s.length() <= max) {
            return s == null ? "" : s;
        }
        return s.substring(0, max - 1) + "…";
    }

    private static String clean(String s) {
        if (s == null) {
            return "";
        }
        return Text.removeTags(s).replace('\n', ' ').trim();
    }

    private static String formatActions(Widget w) {
        try {
            String[] a = w.getActions();
            if (a == null || a.length == 0) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (String x : a) {
                if (x != null && !x.isBlank()) {
                    if (sb.length() > 0) {
                        sb.append(" | ");
                    }
                    sb.append(x);
                }
            }
            return sb.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private Widget findSmallestWidgetAt(int mx, int my) {
        Best best = new Best();
        for (int g = 0; g <= MAX_GROUP; g++) {
            for (int c = 0; c <= MAX_CHILD; c++) {
                Widget root = client.getWidget(g, c);
                if (root == null) {
                    continue;
                }
                visitWidgetForHit(root, mx, my, best);
            }
        }
        return best.w;
    }

    private void visitWidgetForHit(Widget w, int mx, int my, Best best) {
        if (w == null || w.isHidden()) {
            return;
        }
        Rectangle b = w.getBounds();
        if (b.width > 0 && b.height > 0
                && mx >= b.x && mx < b.x + b.width
                && my >= b.y && my < b.y + b.height) {
            int a = b.width * b.height;
            if (a < best.area) {
                best.area = a;
                best.w = w;
            }
        }
        Widget[] ch = w.getChildren();
        if (ch != null) {
            for (Widget c : ch) {
                if (c != null) {
                    visitWidgetForHit(c, mx, my, best);
                }
            }
        }
        ch = w.getDynamicChildren();
        if (ch != null) {
            for (Widget c : ch) {
                if (c != null) {
                    visitWidgetForHit(c, mx, my, best);
                }
            }
        }
        try {
            Widget[] nested = w.getNestedChildren();
            if (nested != null) {
                for (Widget c : nested) {
                    if (c != null) {
                        visitWidgetForHit(c, mx, my, best);
                    }
                }
            }
        } catch (Throwable ignored) {
            // optional API
        }
    }

    private static final class Best {
        Widget w;
        int area = Integer.MAX_VALUE;
    }
}
