package com.combatbot;

import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.util.List;

/**
 * Tekent tiles waar {@link MovementHelper} recent een walk naar heeft uitgegeven (debug).
 */
public class WalkClickHighlightOverlay extends Overlay {

    @Inject
    private Client client;

    private CombatBotConfig config;

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
        List<WorldPoint> pts = MovementHelper.snapshotDebugWalkHighlightPoints();
        if (pts.isEmpty()) {
            return null;
        }
        for (int i = 0; i < pts.size(); i++) {
            WorldPoint wp = pts.get(i);
            LocalPoint lp = LocalPoint.fromWorld(client, wp);
            if (lp == null) {
                continue;
            }
            Polygon poly = Perspective.getCanvasTilePoly(client, lp);
            if (poly == null) {
                continue;
            }
            float t = pts.size() <= 1 ? 1f : 1f - (i / (float) (pts.size() - 1));
            int r = (int) (80 + 175 * t);
            int gr = (int) (40 + 180 * (1 - t));
            g.setColor(new Color(r, gr, 255, 110));
            g.fill(poly);
            g.setColor(new Color(255, 255, 255, 200));
            g.setStroke(new BasicStroke(2f));
            g.draw(poly);
        }
        return null;
    }
}
