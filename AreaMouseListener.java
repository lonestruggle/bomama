package com.combatbot;

import net.runelite.api.Client;
import net.runelite.api.Tile;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.input.MouseAdapter;

import javax.inject.Inject;
import java.awt.event.MouseEvent;

/**
 * AreaMouseListener - Verwerkt Shift+Rechtermuisknop op tiles in het gamecanvas.
 *
 * Gedrag:
 *   - Shift+Rechtsklik op een tile zonder markering → toon keuze menu (Excluded of Safespot)
 *   - Shift+Rechtsklik op een al gemarkeerde tile → verwijder markering
 *
 * Voor Safespot: alleen beschikbaar als de actieve skill COMBAT is.
 *
 * In RuneLite is er geen in-game popup menu via overlay — we wisselen daarom
 * tussen twee modi via een extra Shift-variant:
 *   - Normaal Shift+Rechts: toggle EXCLUDED markering
 *   - Ctrl+Shift+Rechts: toggle SAFESPOT markering (alleen Combat)
 *
 * De plugin-klasse geeft de juiste callback mee.
 */
public class AreaMouseListener extends MouseAdapter {

    @Inject
    private Client client;

    private TileMarkerManager tileMarkerManager;
    private CombatBotPlugin plugin;
    private CombatBotPlugin.ActiveSkill activeSkill = CombatBotPlugin.ActiveSkill.COMBAT;
    private Runnable onChanged;

    public void init(TileMarkerManager manager, CombatBotPlugin plugin, Runnable onChanged) {
        this.tileMarkerManager = manager;
        this.plugin = plugin;
        this.onChanged = onChanged;
    }

    public void setActiveSkill(CombatBotPlugin.ActiveSkill skill) {
        this.activeSkill = skill;
    }

    @Override
    public MouseEvent mousePressed(MouseEvent e) {
        if (!e.isShiftDown() || e.getButton() != MouseEvent.BUTTON3) {
            return e; // Alleen Shift+Rechtsklik
        }

        if (tileMarkerManager == null || client == null) return e;

        // Haal de tile op via de client scene
        WorldPoint clickedTile = getClickedWorldPoint(e);
        if (clickedTile == null) return e;

        // Ctrl+Shift+Rechts = Safespot (alleen Combat)
        TileMarkerManager.MarkerType type;
        if (e.isControlDown() && activeSkill == CombatBotPlugin.ActiveSkill.COMBAT) {
            type = TileMarkerManager.MarkerType.SAFESPOT;
        } else {
            type = TileMarkerManager.MarkerType.EXCLUDED;
        }

        boolean added = tileMarkerManager.toggleTile(activeSkill, clickedTile, type);

        if (onChanged != null) onChanged.run();

        e.consume(); // Voorkom RuneLite context menu
        return e;
    }

    /**
     * Zet muispositie om naar WorldPoint via de RuneLite scene (Tile-hover).
     * Gebruikt client.getScene().getTiles() op basis van muis XY.
     */
    private WorldPoint getClickedWorldPoint(MouseEvent e) {
        if (client.getScene() == null) return null;

        Tile[][][] tiles = client.getScene().getTiles();
        int plane = client.getPlane();
        if (plane < 0 || plane >= tiles.length) return null;

        // RuneLite biedt geen directe canvas→tile lookup; we gebruiken getSelectedSceneTile()
        Tile hovered = client.getSelectedSceneTile();
        if (hovered == null) return null;

        return hovered.getWorldLocation();
    }
}
