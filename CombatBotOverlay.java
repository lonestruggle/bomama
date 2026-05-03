package com.combatbot;

import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import java.awt.*;

/**
 * Registreerbare RuneLite/Storm overlay.
 * Positie: DYNAMIC zodat de speler hem kan verslepen naar elke positie op het scherm.
 * Layer: ABOVE_SCENE zodat de overlay altijd zichtbaar is boven het speelveld.
 */
public class CombatBotOverlay extends Overlay {

    private CombatBotPaint paint;

    @Inject
    public CombatBotOverlay() {
        // DYNAMIC maakt de overlay versleepbaar via de RuneLite overlay-manager UI
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
        // Resizable = true zodat RuneLite weet dat dit een versleepbaar paneel is
        setMovable(true);
    }

    public void setPaint(CombatBotPaint paint) {
        this.paint = paint;
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (paint == null) return null;
        return paint.render(graphics);
    }
}
