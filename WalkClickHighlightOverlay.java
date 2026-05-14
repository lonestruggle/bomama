package com.combatbot;

import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.util.List;

/**
 * Tekent twee soorten tiles op de wereld:
 *  1) PAD-tiles (groene dunne outline + "·{n}" mini-label) — elke tile waar de speler
 *     overheen gewandeld is. Heatmap-gradient op aantal traversals.
 *  2) CLICK-tiles (heatmap fill + "x{n}" label) — elke tile waar een "Walk here"
 *     klik op is uitgegeven. Vaker geklikt = warmere kleur.
 *
 * Bron: {@link MovementHelper#snapshotDebugWalkClickInfos()} — gevuld door MovementHelper-
 * fallbacks, {@link CombatBotPlugin#onMenuOptionClicked} (clicks) en
 * {@link CombatBotPlugin#onGameTick} (path-traversals).
 */
public class WalkClickHighlightOverlay extends Overlay {

    @Inject
    private Client client;

    private CombatBotConfig config;

    private static final Font CLICK_FONT = new Font("Arial", Font.BOLD, 11);
    private static final Font PATH_FONT = new Font("Arial", Font.PLAIN, 9);
    private static final long FRESH_WINDOW_MS = 60_000L;

    @Inject
    public WalkClickHighlightOverlay() {
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
        setPriority(0.95f);
    }

    public void setConfig(CombatBotConfig config) {
        this.config = config;
    }

    @Override
    public Dimension render(Graphics2D g) {
        if (config == null || !config.debugWalkClickOverlay()) {
            return null;
        }
        boolean showClicks = config.debugWalkOverlayShowClickTiles();
        boolean showPaths = config.debugWalkOverlayShowPathTiles();
        if (!showClicks && !showPaths) {
            return null;
        }
        List<MovementHelper.WalkClickInfo> infos = MovementHelper.snapshotDebugWalkClickInfos();
        if (infos.isEmpty()) {
            return null;
        }

        // Bereken normalisatie-bases voor heatmaps (apart voor click-count en traversals).
        int maxClick = 1;
        int maxTraverse = 1;
        for (MovementHelper.WalkClickInfo info : infos) {
            if (info.count > maxClick) {
                maxClick = info.count;
            }
            if (info.traversals > maxTraverse) {
                maxTraverse = info.traversals;
            }
        }

        long now = System.currentTimeMillis();

        // PASS 1: PAD-tiles (alleen tiles met traversals > 0 én geen click) — onderlaag.
        g.setFont(PATH_FONT);
        FontMetrics pfm = g.getFontMetrics();
        for (MovementHelper.WalkClickInfo info : infos) {
            if (!showPaths || info.traversals <= 0 || info.count > 0) {
                continue;
            }
            LocalPoint lp = LocalPoint.fromWorld(client, info.point);
            if (lp == null) {
                continue;
            }
            Polygon poly = Perspective.getCanvasTilePoly(client, lp);
            if (poly == null) {
                continue;
            }
            float ratio = Math.min(1f, info.traversals / (float) maxTraverse);
            // Pad-tiles in groen-tinten, transparanter dan click-tiles.
            Color fill = pathColor(ratio, /*alpha=*/ 55);
            g.setColor(fill);
            g.fill(poly);
            long ageMs = Math.max(0L, now - info.lastTraverseMs);
            float freshness = ageMs >= FRESH_WINDOW_MS ? 0f : 1f - (ageMs / (float) FRESH_WINDOW_MS);
            int borderAlpha = (int) (90 + 120 * freshness);
            g.setColor(new Color(120, 220, 120, Math.min(255, borderAlpha)));
            g.setStroke(new BasicStroke(0.9f + 0.6f * freshness));
            g.draw(poly);
            // Klein label voor traversals > 1 (anders te druk).
            if (info.traversals > 1) {
                String label = "·" + info.traversals;
                Rectangle b = poly.getBounds();
                int cx = b.x + b.width / 2;
                int cy = b.y + b.height / 2 + pfm.getAscent() / 2 - 1;
                int lw = pfm.stringWidth(label);
                g.setColor(new Color(0, 0, 0, 170));
                g.fillRoundRect(cx - lw / 2 - 2, cy - pfm.getAscent() + 1, lw + 4, pfm.getAscent() + 2, 3, 3);
                g.setColor(new Color(180, 255, 180));
                g.drawString(label, cx - lw / 2, cy);
            }
        }

        // PASS 2: CLICK-tiles (tiles waar een "Walk here" actie op zat) — bovenlaag.
        g.setFont(CLICK_FONT);
        FontMetrics cfm = g.getFontMetrics();
        for (MovementHelper.WalkClickInfo info : infos) {
            if (!showClicks || info.count <= 0) {
                continue;
            }
            LocalPoint lp = LocalPoint.fromWorld(client, info.point);
            if (lp == null) {
                continue;
            }
            Polygon poly = Perspective.getCanvasTilePoly(client, lp);
            if (poly == null) {
                continue;
            }
            float ratio = Math.min(1f, info.count / (float) maxClick);
            Color fill = heatmapColor(ratio, /*alpha=*/ 130);
            g.setColor(fill);
            g.fill(poly);

            long ageMs = Math.max(0L, now - info.lastClickMs);
            float freshness = ageMs >= FRESH_WINDOW_MS ? 0f : 1f - (ageMs / (float) FRESH_WINDOW_MS);
            int borderAlpha = (int) (140 + 115 * freshness);
            g.setColor(new Color(255, 255, 255, Math.min(255, borderAlpha)));
            g.setStroke(new BasicStroke(1.8f + 0.8f * freshness));
            g.draw(poly);

            String label = "x" + info.count;
            Rectangle b = poly.getBounds();
            int cx = b.x + b.width / 2;
            int cy = b.y + b.height / 2 + cfm.getAscent() / 2 - 2;
            int lw = cfm.stringWidth(label);
            g.setColor(new Color(0, 0, 0, 210));
            g.fillRoundRect(cx - lw / 2 - 3, cy - cfm.getAscent() + 1, lw + 6, cfm.getAscent() + 3, 4, 4);
            g.setColor(info.count >= 3 ? new Color(255, 230, 80) : new Color(255, 255, 255));
            g.drawString(label, cx - lw / 2, cy);
        }
        return null;
    }

    /**
     * Click-heatmap: blauw → cyaan → groen → geel → rood, op basis van count-ratio (0..1).
     */
    private static Color heatmapColor(float t, int alpha) {
        t = Math.max(0f, Math.min(1f, t));
        int r;
        int g;
        int b;
        if (t < 0.25f) {
            float k = t / 0.25f;
            r = 30;
            g = (int) (60 + 195 * k);
            b = 220;
        } else if (t < 0.5f) {
            float k = (t - 0.25f) / 0.25f;
            r = 30;
            g = 255;
            b = (int) (220 - 200 * k);
        } else if (t < 0.75f) {
            float k = (t - 0.5f) / 0.25f;
            r = (int) (30 + 225 * k);
            g = 255;
            b = 20;
        } else {
            float k = (t - 0.75f) / 0.25f;
            r = 255;
            g = (int) (255 - 220 * k);
            b = 20;
        }
        return new Color(clamp(r), clamp(g), clamp(b), Math.max(0, Math.min(255, alpha)));
    }

    /**
     * Path-heatmap (groen-tinten): donker groen → fel limoen, op basis van traversal-ratio.
     */
    private static Color pathColor(float t, int alpha) {
        t = Math.max(0f, Math.min(1f, t));
        int r = (int) (40 + 80 * t);
        int g = (int) (140 + 110 * t);
        int b = (int) (40 + 60 * t);
        return new Color(clamp(r), clamp(g), clamp(b), Math.max(0, Math.min(255, alpha)));
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
