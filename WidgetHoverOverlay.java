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
 * Widget-info onder de muis: Storm {@link WidgetDebugHelper} + fallback RuneLite-widgetboom.
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
    private WidgetDebugHelper.WidgetHoverInfo cachedStorm;
    private Widget cachedRlWidget;

    @Inject
    WidgetHoverOverlay() {
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ALWAYS_ON_TOP);
        setPriority(OverlayPriority.HIGH);
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
            cachedStorm = WidgetDebugHelper.findSmallestStormWidgetAt(mouse.getX(), mouse.getY());
            cachedRlWidget = findSmallestRlWidgetAt(mouse.getX(), mouse.getY());
        }

        List<String> lines = buildLines(cachedStorm, cachedRlWidget);
        if (lines.isEmpty()) {
            lines.add("geen widget op pixel");
            lines.add("canvas " + mouse.getX() + "," + mouse.getY());
        }

        setPreferredLocation(new java.awt.Point(mouse.getX() + 14, mouse.getY() + 14));

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

    private static List<String> buildLines(WidgetDebugHelper.WidgetHoverInfo storm, Widget rl) {
        List<String> lines = new ArrayList<>(10);
        if (storm != null) {
            lines.add("bron: Storm");
            lines.add("humanLabel: " + (storm.humanLabel.isEmpty() ? "—" : storm.humanLabel));
            lines.add("id=" + storm.packedId + "  iface=" + storm.ifaceGroup + "," + storm.ifaceChild);
            if (!storm.name.isEmpty()) {
                lines.add("name: " + trunc(storm.name, 56));
            }
            if (!storm.text.isEmpty()) {
                lines.add("text: " + trunc(storm.text, 56));
            }
            if (!storm.actions.isEmpty()) {
                lines.add("actions: " + trunc(storm.actions, 56));
            }
            if (storm.boundsW > 0 && storm.boundsH > 0) {
                lines.add(String.format(Locale.ROOT, "canvas: %d,%d %dx%d",
                        storm.canvasX, storm.canvasY, storm.boundsW, storm.boundsH));
            }
            return lines;
        }
        if (rl != null) {
            lines.add("bron: RuneLite");
            String name = clean(rl.getName());
            String text = clean(rl.getText());
            String actions = formatActions(rl);
            String human = WidgetDebugHelper.deriveHumanLabel(name, text, actions);
            int packed = rl.getId();
            int ig = packed > 0 ? (packed >>> 16) : -1;
            int ic = packed > 0 ? (packed & 0xFFFF) : -1;
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
            Point loc = rl.getCanvasLocation();
            if (loc != null && rl.getWidth() > 0 && rl.getHeight() > 0) {
                lines.add(String.format(Locale.ROOT, "canvas: %d,%d %dx%d",
                        loc.getX(), loc.getY(), rl.getWidth(), rl.getHeight()));
            }
        }
        return lines;
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

    private Widget findSmallestRlWidgetAt(int mx, int my) {
        Best best = new Best();
        for (int g = 0; g <= MAX_GROUP; g++) {
            for (int c = 0; c <= MAX_CHILD; c++) {
                Widget root = client.getWidget(g, c);
                if (root != null) {
                    visitRlWidget(root, mx, my, best);
                }
            }
        }
        return best.w;
    }

    private void visitRlWidget(Widget w, int mx, int my, Best best) {
        if (w == null) {
            return;
        }
        try {
            if (w.isHidden()) {
                return;
            }
        } catch (Throwable ignored) {
            return;
        }
        try {
            if (w.isSelfHidden()) {
                return;
            }
        } catch (Throwable ignored) {
        }
        Point loc = w.getCanvasLocation();
        int ww = w.getWidth();
        int wh = w.getHeight();
        if (loc != null && ww > 0 && wh > 0
                && mx >= loc.getX() && mx < loc.getX() + ww
                && my >= loc.getY() && my < loc.getY() + wh) {
            int area = ww * wh;
            if (area < best.area) {
                best.area = area;
                best.w = w;
            }
        }
        Widget[] ch = w.getChildren();
        if (ch != null) {
            for (Widget c : ch) {
                if (c != null) {
                    visitRlWidget(c, mx, my, best);
                }
            }
        }
        ch = w.getDynamicChildren();
        if (ch != null) {
            for (Widget c : ch) {
                if (c != null) {
                    visitRlWidget(c, mx, my, best);
                }
            }
        }
        try {
            Widget[] nested = w.getNestedChildren();
            if (nested != null) {
                for (Widget c : nested) {
                    if (c != null) {
                        visitRlWidget(c, mx, my, best);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static final class Best {
        Widget w;
        int area = Integer.MAX_VALUE;
    }
}
