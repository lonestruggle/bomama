package com.combatbot;

import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * AreaOverlay - Tekent center-gebieden en gemarkeerde tiles.
 *
 * Toont ALLE centers voor de actieve skill, elk met hun eigen radius.
 * Het actieve center (dat de handler gebruikt) wordt extra gemarkeerd.
 */
public class AreaOverlay extends Overlay {

    @Inject
    private Client client;

    private CombatBotConfig config;
    private CombatBotPlugin.ActiveSkill activeSkill = CombatBotPlugin.ActiveSkill.COMBAT;
    private TileMarkerManager tileMarkerManager;

    // Centers per skill
    private List<CenterManager.Center> combatCenters = new ArrayList<>();
    private List<CenterManager.Center> wcCenters = new ArrayList<>();
    private List<CenterManager.Center> miningCenters = new ArrayList<>();
    private List<CenterManager.Center> fishingCenters = new ArrayList<>();
    private List<CenterManager.Center> impsCenters = new ArrayList<>();

    // Actief center per skill (waar de handler op zoekt)
    private WorldPoint activeCombatCenter;
    private WorldPoint activeWcCenter;
    private WorldPoint activeMiningCenter;
    /** Tile van de rots die MiningHandler target (highlight). */
    private WorldPoint miningTargetRockTile;
    private WorldPoint activeFishingCenter;
    private WorldPoint activeImpsCenter;
    private WorldPoint activeLootCenter;

    /**
     * Alleen visuele cap voor performance; handlers gebruiken nog steeds de echte center.radius.
     * Verhoogd zodat grotere centers beter overeenkomen met wat je in-game instelt.
     */
    private static final int MAX_TILE_RENDER_RADIUS = 45;

    // ===================== GIANTS HUNT AREA =====================
    // Center/radius moeten matchen met GiantsHandler zodat je exact ziet waarom de bot kiest.
    private static final WorldPoint HILL_GIANTS_CENTER = new WorldPoint(3115, 9837, 0);
    private static final int HILL_GIANTS_RADIUS = 24;

    // Scorpion danger zones (zelfde als in ImpsHandler)
    private static final WorldPoint SCORPION_ZONE_1 = new WorldPoint(2852, 3185, 0);
    private static final WorldPoint SCORPION_ZONE_2 = new WorldPoint(2853, 3161, 0);
    // Hardcoded extra imp areas (zelfde centers als ImpsHandler)
    private static final WorldPoint EXTRA_IMP_AREA_CENTER_1 = new WorldPoint(2826, 3149, 0);
    private static final WorldPoint EXTRA_IMP_AREA_CENTER_2 = new WorldPoint(2832, 3200, 0);
    private static final Color GOBLIN_COIN_COLOR = new Color(255, 170, 40, 55);
    /** Vaste kleuren: extra imp-gebieden (niet dezelfde als geconfigureerde centers). */
    private static final Color IMPS_EXTRA_AREA_1 = new Color(39, 174, 96, 48);
    private static final Color IMPS_EXTRA_AREA_2 = new Color(230, 126, 34, 48);
    /**
     * Per index een eigen kleur voor Imps-centers uit CenterManager (makkelijk te onderscheiden in overlay).
     * Alpha wordt door {@link #renderAreaTiles} / {@link #renderCenterTile} nog aangepast.
     */
    private static final Color[] IMPS_CENTER_PALETTE = {
            new Color(231, 76, 60, 58),
            new Color(46, 204, 113, 58),
            new Color(155, 89, 182, 58),
            new Color(241, 196, 15, 58),
            new Color(52, 152, 219, 58),
            new Color(230, 126, 34, 58),
            new Color(26, 188, 156, 58),
            new Color(233, 30, 99, 58),
            new Color(142, 68, 173, 58),
            new Color(22, 160, 133, 58),
    };

    private static Color impsPaletteForCenterIndex(int index) {
        if (index < 0) {
            index = 0;
        }
        return IMPS_CENTER_PALETTE[index % IMPS_CENTER_PALETTE.length];
    }

    @Inject
    public AreaOverlay() {
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
        setPriority(0.9f);
    }

    public void setConfig(CombatBotConfig config) {
        this.config = config;
    }

    public void setActiveSkill(CombatBotPlugin.ActiveSkill skill) {
        this.activeSkill = skill;
    }

    public void setTileMarkerManager(TileMarkerManager manager) {
        this.tileMarkerManager = manager;
    }

    /** Update de centers voor een skill vanuit config data. */
    public void setCentersForSkill(CombatBotPlugin.ActiveSkill skill, List<CenterManager.Center> centers) {
        switch (skill) {
            case COMBAT:      combatCenters = centers != null ? centers : new ArrayList<>(); break;
            case WOODCUTTING: wcCenters = centers != null ? centers : new ArrayList<>(); break;
            case MINING:      miningCenters = centers != null ? centers : new ArrayList<>(); break;
            case FISHING:     fishingCenters = centers != null ? centers : new ArrayList<>(); break;
            case IMPS:        impsCenters = centers != null ? centers : new ArrayList<>(); break;
            case BARBARIAN:   break;
        }
    }

    /** Stel het actieve center in (het center dat de handler momenteel gebruikt). */
    public void setMiningTargetRockTile(WorldPoint tile) {
        this.miningTargetRockTile = tile;
    }

    public void setActiveCenterForSkill(CombatBotPlugin.ActiveSkill skill, WorldPoint center) {
        switch (skill) {
            case COMBAT:      activeCombatCenter = center; break;
            case WOODCUTTING: activeWcCenter = center; break;
            case MINING:      activeMiningCenter = center; break;
            case FISHING:     activeFishingCenter = center; break;
            case IMPS:        activeImpsCenter = center; break;
            case LOOT:        activeLootCenter = center; break;
            case BARBARIAN:   break;
        }
    }

    /**
     * Korte locatie-omschrijving voor Discord (center-naam of coördinaten).
     */
    public String getActiveCenterDescription(CombatBotPlugin.ActiveSkill skill) {
        if (skill == null) {
            return null;
        }
        if (skill == CombatBotPlugin.ActiveSkill.GIANTS) {
            String monster = config != null && config.giantsMonsterName() != null
                    ? config.giantsMonsterName().trim()
                    : "";
            return monster.isEmpty() ? "Hill Giants (Edgeville)" : "Giants: " + monster;
        }
        WorldPoint active = null;
        List<CenterManager.Center> list;
        switch (skill) {
            case COMBAT:
                active = activeCombatCenter;
                list = combatCenters;
                break;
            case WOODCUTTING:
                active = activeWcCenter;
                list = wcCenters;
                break;
            case MINING:
                active = activeMiningCenter;
                list = miningCenters;
                break;
            case FISHING:
                active = activeFishingCenter;
                list = fishingCenters;
                break;
            case IMPS:
                active = activeImpsCenter;
                list = impsCenters;
                break;
            case LOOT:
                return activeLootCenter != null ? "Barb loot / fish" : null;
            case BARBARIAN:
                return "Barbarian longhall";
            default:
                return null;
        }
        if (active == null) {
            return null;
        }
        for (CenterManager.Center c : list) {
            if (c != null && active.equals(c.point)) {
                if (c.name != null && !c.name.isEmpty()) {
                    return c.name;
                }
                break;
            }
        }
        return active.getX() + "," + active.getY() + " p" + active.getPlane();
    }

    private List<CenterManager.Center> getCentersForSkill(CombatBotPlugin.ActiveSkill skill) {
        switch (skill) {
            case COMBAT:      return combatCenters;
            case WOODCUTTING: return wcCenters;
            case MINING:      return miningCenters;
            case FISHING:     return fishingCenters;
            case IMPS:        return impsCenters;
            case BARBARIAN:   return new ArrayList<>();
            default:          return new ArrayList<>();
        }
    }

    private WorldPoint getActiveCenterForSkill(CombatBotPlugin.ActiveSkill skill) {
        switch (skill) {
            case COMBAT:      return activeCombatCenter;
            case WOODCUTTING: return activeWcCenter;
            case MINING:      return activeMiningCenter;
            case FISHING:     return activeFishingCenter;
            case IMPS:        return activeImpsCenter;
            case BARBARIAN:   return null;
            default:          return null;
        }
    }

    private boolean isOverlayVisible() {
        if (config == null) return false;
        switch (activeSkill) {
            case COMBAT:      return config.showCombatOverlay();
            case STARTER:     return config.showStarterOverlay();
            case WOODCUTTING: return config.showWcOverlay();
            case MINING:      return config.showMiningOverlay();
            case FISHING:     return config.showFishingOverlay();
            case IMPS:        return config.showImpsOverlay();
            case BARBARIAN:   return config.barbarianShowOverlay();
            case LOOT:        return true;
            default:          return true;
        }
    }

    private Color getColorForSkill(CombatBotPlugin.ActiveSkill skill) {
        switch (skill) {
            case COMBAT:      return new Color(220, 50, 50, 60);
            case STARTER:     return new Color(255, 140, 30, 58);
            case WOODCUTTING: return new Color(50, 200, 50, 60);
            case MINING:      return new Color(200, 140, 50, 60);
            case FISHING:     return new Color(50, 120, 220, 60);
            case IMPS:        return new Color(0, 200, 220, 55);
            case BARBARIAN:   return new Color(220, 140, 60, 60);
            case LOOT:        return new Color(255, 200, 80, 58);
            default:          return new Color(150, 150, 150, 60);
        }
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (config == null || !config.botEnabled()) return null;

        // Render centers voor actieve skill
        if (isOverlayVisible()) {
            List<CenterManager.Center> centers = getCentersForSkill(activeSkill);
            WorldPoint activeCenter = getActiveCenterForSkill(activeSkill);
            Color areaColor = getColorForSkill(activeSkill);

            for (int ci = 0; ci < centers.size(); ci++) {
                CenterManager.Center center = centers.get(ci);
                boolean isActive = activeCenter != null && activeCenter.equals(center.point);
                Color fill = (activeSkill == CombatBotPlugin.ActiveSkill.IMPS)
                        ? impsPaletteForCenterIndex(ci)
                        : areaColor;
                renderAreaTiles(graphics, center, fill, isActive);
                renderCenterTile(graphics, center, isActive,
                        activeSkill == CombatBotPlugin.ActiveSkill.IMPS ? fill : null);
            }
        }

        // Render Giants hunting/battle area
        if (activeSkill == CombatBotPlugin.ActiveSkill.GIANTS && config.showGiantsOverlay()) {
            renderGiantsHuntArea(graphics);
        }

        // Render imps hunting radius overlay
        if (activeSkill == CombatBotPlugin.ActiveSkill.IMPS && config.showImpsOverlay()) {
            renderImpsHuntingArea(graphics);
        }
        if (activeSkill == CombatBotPlugin.ActiveSkill.IMPS && config.impsShowGoblinCoinOverlay()) {
            renderImpsGoblinCoinArea(graphics);
        }
        if (activeSkill == CombatBotPlugin.ActiveSkill.BARBARIAN && config.barbarianShowOverlay()) {
            renderBarbarianLonghallArea(graphics);
        }

        if (activeSkill == CombatBotPlugin.ActiveSkill.LOOT && activeLootCenter != null) {
            CenterManager.Center lootArea = new CenterManager.Center(activeLootCenter, 10, "Barb loot", true);
            Color lootColor = getColorForSkill(activeSkill);
            renderAreaTiles(graphics, lootArea, lootColor, true);
            renderCenterTile(graphics, lootArea, true, null);
        }

        // Starter: vast train-anker (rats / WC / bonfire) — zelfde center als StarterSkillHandler
        if (activeSkill == CombatBotPlugin.ActiveSkill.STARTER && config.showStarterOverlay()) {
            CenterManager.Center starterTrain = new CenterManager.Center(
                    StarterSkillHandler.starterTrainOverlayCenter(config),
                    StarterSkillHandler.STARTER_TRAIN_AREA_RADIUS,
                    "Starter train",
                    true);
            Color st = getColorForSkill(activeSkill);
            renderAreaTiles(graphics, starterTrain, st, true);
            renderCenterTile(graphics, starterTrain, true, null);
        }

        // Doelrots mining (actieve tile)
        if (activeSkill == CombatBotPlugin.ActiveSkill.MINING
                && config.showMiningRockTarget()
                && miningTargetRockTile != null) {
            renderMiningTargetRock(graphics, miningTargetRockTile);
        }

        // Render gemarkeerde tiles (altijd)
        if (tileMarkerManager != null) {
            renderMarkedTiles(graphics);
        }

        return null;
    }

    private void renderMiningTargetRock(Graphics2D graphics, WorldPoint tile) {
        if (client == null || tile == null) {
            return;
        }
        LocalPoint lp = LocalPoint.fromWorld(client, tile);
        if (lp == null) {
            return;
        }
        Polygon poly = Perspective.getCanvasTilePoly(client, lp);
        if (poly == null) {
            return;
        }
        graphics.setColor(new Color(255, 220, 60, 95));
        graphics.fill(poly);
        graphics.setColor(new Color(255, 200, 40, 220));
        graphics.setStroke(new BasicStroke(2f));
        graphics.draw(poly);
    }

    private void renderGiantsHuntArea(Graphics2D graphics) {
        // Giants heeft geen center-lijst in CenterManager: het is één vaste battle area.
        Color giantsColor = new Color(255, 120, 0, 60);
        CenterManager.Center center = new CenterManager.Center(HILL_GIANTS_CENTER, HILL_GIANTS_RADIUS, "Hill Giants", true);
        renderAreaTiles(graphics, center, giantsColor, true);
        renderCenterTile(graphics, center, true, null);
    }

    private void renderBarbarianLonghallArea(Graphics2D graphics) {
        int x1 = config.barbarianCorner1X();
        int y1 = config.barbarianCorner1Y();
        int x2 = config.barbarianCorner2X();
        int y2 = config.barbarianCorner2Y();
        int x3 = config.barbarianCorner3X();
        int y3 = config.barbarianCorner3Y();
        int x4 = config.barbarianCorner4X();
        int y4 = config.barbarianCorner4Y();
        int minX = Math.min(Math.min(x1, x2), Math.min(x3, x4));
        int maxX = Math.max(Math.max(x1, x2), Math.max(x3, x4));
        int minY = Math.min(Math.min(y1, y2), Math.min(y3, y4));
        int maxY = Math.max(Math.max(y1, y2), Math.max(y3, y4));

        Color fill = new Color(220, 140, 60, 60);
        Color border = new Color(220, 140, 60, 180);
        int plane = 0;

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                WorldPoint tilePoint = new WorldPoint(x, y, plane);
                LocalPoint lp = LocalPoint.fromWorld(client, tilePoint);
                if (lp == null) continue;
                Polygon poly = Perspective.getCanvasTilePoly(client, lp);
                if (poly == null) continue;

                boolean edge = (x == minX || x == maxX || y == minY || y == maxY);
                if (edge) {
                    graphics.setColor(border);
                    graphics.setStroke(new BasicStroke(2f));
                    graphics.draw(poly);
                } else {
                    graphics.setColor(fill);
                    graphics.fill(poly);
                }
            }
        }

        int cx = (minX + maxX) / 2;
        int cy = (minY + maxY) / 2;
        renderCenterTile(graphics, new CenterManager.Center(new WorldPoint(cx, cy, plane), 1, "Barbarian hall", true), true, null);
    }

    // ===================== CENTER TILE MARKERING =====================

    private void renderCenterTile(Graphics2D graphics, CenterManager.Center center, boolean isActive) {
        renderCenterTile(graphics, center, isActive, null);
    }

    /**
     * @param accent Kleur voor midden-tegel + label (bijv. Imps per-center palet); {@code null} = klassiek blauw.
     */
    private void renderCenterTile(Graphics2D graphics, CenterManager.Center center, boolean isActive, Color accent) {
        LocalPoint lp = LocalPoint.fromWorld(client, center.point);
        if (lp == null) return;

        Polygon poly = Perspective.getCanvasTilePoly(client, lp);
        if (poly == null) return;

        Color base = accent != null ? accent : new Color(0, 150, 255);
        int alpha = isActive ? 120 : 50;
        graphics.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha));
        graphics.fill(poly);
        if (isActive) {
            graphics.setColor(new Color(255, 255, 255, 220));
        } else if (accent != null) {
            graphics.setColor(new Color(
                    Math.min(255, base.getRed() + 70),
                    Math.min(255, base.getGreen() + 70),
                    Math.min(255, base.getBlue() + 70),
                    160));
        } else {
            graphics.setColor(new Color(180, 180, 180, 150));
        }
        graphics.setStroke(new BasicStroke(isActive ? 2.5f : 1.5f));
        graphics.draw(poly);

        // Label with name
        Rectangle bounds = poly.getBounds();
        int cx = bounds.x + bounds.width / 2;
        int cy = bounds.y + bounds.height / 2;
        graphics.setFont(new Font("Arial", Font.BOLD, 9));
        FontMetrics fm = graphics.getFontMetrics();

        String nameLabel = (center.name != null && !center.name.isEmpty()) ? center.name : "";
        String radiusLabel = "r=" + center.radius;
        String label = (isActive ? "★ " : "") + (nameLabel.isEmpty() ? radiusLabel : nameLabel + " " + radiusLabel);

        int lw = fm.stringWidth(label);
        graphics.setColor(new Color(0, 0, 0, 180));
        graphics.fillRoundRect(cx - lw / 2 - 3, cy - 12, lw + 6, 14, 3, 3);
        Color textColor;
        if (isActive) {
            textColor = new Color(120, 255, 140);
        } else if (accent != null) {
            textColor = new Color(
                    Math.min(255, base.getRed() + 90),
                    Math.min(255, base.getGreen() + 90),
                    Math.min(255, base.getBlue() + 90),
                    255);
        } else {
            textColor = new Color(100, 200, 255);
        }
        graphics.setColor(textColor);
        graphics.drawString(label, cx - lw / 2, cy);
    }

    // ===================== AREA RENDERING =====================

    private void renderAreaTiles(Graphics2D graphics, CenterManager.Center center, Color fillColor, boolean isActive) {
        int plane = center.point.getPlane();
        int safeRadius = Math.min(center.radius, MAX_TILE_RENDER_RADIUS);

        Color fill = isActive ? fillColor : new Color(fillColor.getRed(), fillColor.getGreen(), fillColor.getBlue(), 25);
        Color borderColor = new Color(
                fillColor.getRed(), fillColor.getGreen(), fillColor.getBlue(),
                isActive ? Math.min(255, fillColor.getAlpha() + 120) : 60
        );

        for (int dx = -safeRadius; dx <= safeRadius; dx++) {
            for (int dy = -safeRadius; dy <= safeRadius; dy++) {
                WorldPoint tilePoint = new WorldPoint(center.point.getX() + dx, center.point.getY() + dy, plane);

                LocalPoint lp = LocalPoint.fromWorld(client, tilePoint);
                if (lp == null) continue;

                Polygon poly = Perspective.getCanvasTilePoly(client, lp);
                if (poly == null) continue;

                boolean isEdge = (Math.abs(dx) == safeRadius || Math.abs(dy) == safeRadius);

                if (isEdge) {
                    graphics.setColor(borderColor);
                    graphics.setStroke(new BasicStroke(isActive ? 2f : 1f));
                    graphics.draw(poly);
                } else {
                    graphics.setColor(fill);
                    graphics.fill(poly);
                }
            }
        }

        // Geen apart label hier: {@link #renderCenterTile} tekent al naam + radius op het center-tile
        // (dubbele tekst voorkomen).
    }

    // ===================== IMPS HUNTING AREA OVERLAY =====================

    private void renderImpsHuntingArea(Graphics2D graphics) {
        // Twee vaste extra imp-zones: elk een eigen kleur (niet hetzelfde als elkaar of als center-palet).
        int extraRadius = config.impsHuntingRadius();
        renderAreaTiles(graphics, new CenterManager.Center(EXTRA_IMP_AREA_CENTER_1, extraRadius, "Imp extra 1", true), IMPS_EXTRA_AREA_1, false);
        renderCenterTile(graphics, new CenterManager.Center(EXTRA_IMP_AREA_CENTER_1, extraRadius, "Imp extra 1", true), false, IMPS_EXTRA_AREA_1);
        renderAreaTiles(graphics, new CenterManager.Center(EXTRA_IMP_AREA_CENTER_2, extraRadius, "Imp extra 2", true), IMPS_EXTRA_AREA_2, false);
        renderCenterTile(graphics, new CenterManager.Center(EXTRA_IMP_AREA_CENTER_2, extraRadius, "Imp extra 2", true), false, IMPS_EXTRA_AREA_2);

        boolean hasActiveImpCenter = false;
        for (CenterManager.Center c : impsCenters) {
            if (c != null && c.active) {
                hasActiveImpCenter = true;
                break;
            }
        }

        // Legacy cirkel alleen als er geen actieve imp-centers zijn (die worden al als areas getekend)
        if (!hasActiveImpCenter) {
            WorldPoint huntCenter = new WorldPoint(config.impsHuntingX(), config.impsHuntingY(), 0);
            int huntRadius = config.impsHuntingRadius();

            int safeRadius = Math.min(huntRadius, MAX_TILE_RENDER_RADIUS);
            Color huntBorder = new Color(52, 152, 219, 175);

            for (int dx = -safeRadius; dx <= safeRadius; dx++) {
                for (int dy = -safeRadius; dy <= safeRadius; dy++) {
                    boolean isEdge = (Math.abs(dx) == safeRadius || Math.abs(dy) == safeRadius);
                    if (!isEdge) continue;

                    WorldPoint tilePoint = new WorldPoint(huntCenter.getX() + dx, huntCenter.getY() + dy, 0);
                    LocalPoint lp = LocalPoint.fromWorld(client, tilePoint);
                    if (lp == null) continue;

                    Polygon poly = Perspective.getCanvasTilePoly(client, lp);
                    if (poly == null) continue;

                    graphics.setColor(huntBorder);
                    graphics.setStroke(new BasicStroke(1.5f));
                    graphics.draw(poly);
                }
            }

            LocalPoint centerLp = LocalPoint.fromWorld(client, huntCenter);
            if (centerLp != null) {
                net.runelite.api.Point centerScreen = Perspective.localToCanvas(client, centerLp, 0);
                if (centerScreen != null) {
                    String label = "🎯 Imps r=" + huntRadius;
                    graphics.setFont(new Font("Arial", Font.BOLD, 11));
                    FontMetrics fm = graphics.getFontMetrics();
                    int lw = fm.stringWidth(label);
                    int csx = centerScreen.getX();
                    int csy = centerScreen.getY();
                    graphics.setColor(new Color(0, 0, 0, 180));
                    graphics.fillRoundRect(csx - lw / 2 - 4, csy - 14, lw + 8, 16, 4, 4);
                    graphics.setColor(new Color(93, 173, 226));
                    graphics.drawString(label, csx - lw / 2, csy);
                }
            }
        }

        // Render rally point radius (paars) — optioneel via config
        if (config.impsShowRallyRadius()) {
            WorldPoint rallyCenter = new WorldPoint(2830, 3182, 0);
            int rallyRadius = config.impsRallyPointRadius();
            int safeR = Math.min(rallyRadius, MAX_TILE_RENDER_RADIUS);
            Color rallyColor = new Color(180, 80, 220, 50);
            Color rallyBorder = new Color(180, 80, 220, 180);
            for (int dx = -safeR; dx <= safeR; dx++) {
                for (int dy = -safeR; dy <= safeR; dy++) {
                    boolean isEdge = (Math.abs(dx) == safeR || Math.abs(dy) == safeR);
                    if (!isEdge) continue;
                    WorldPoint tilePoint = new WorldPoint(rallyCenter.getX() + dx, rallyCenter.getY() + dy, 0);
                    LocalPoint lp = LocalPoint.fromWorld(client, tilePoint);
                    if (lp == null) continue;
                    Polygon poly = Perspective.getCanvasTilePoly(client, lp);
                    if (poly == null) continue;
                    graphics.setColor(rallyBorder);
                    graphics.setStroke(new BasicStroke(1.5f));
                    graphics.draw(poly);
                }
            }
            LocalPoint rallyLp = LocalPoint.fromWorld(client, rallyCenter);
            if (rallyLp != null) {
                net.runelite.api.Point rallyScreen = Perspective.localToCanvas(client, rallyLp, 0);
                if (rallyScreen != null) {
                    String label = "Rally r=" + rallyRadius;
                    graphics.setFont(new Font("Arial", Font.BOLD, 10));
                    FontMetrics fm = graphics.getFontMetrics();
                    int lw = fm.stringWidth(label);
                    graphics.setColor(new Color(0, 0, 0, 180));
                    graphics.fillRoundRect(rallyScreen.getX() - lw / 2 - 4, rallyScreen.getY() - 14, lw + 8, 16, 4, 4);
                    graphics.setColor(new Color(200, 120, 255));
                    graphics.drawString(label, rallyScreen.getX() - lw / 2, rallyScreen.getY());
                }
            }
        }

        // Render scorpion danger zones — twee verschillende tinten (rood vs. magenta)
        if (config.impsAvoidScorpions()) {
            renderScorpionZone(graphics, SCORPION_ZONE_1, "⚠ Schorpioen A", 0);
            renderScorpionZone(graphics, SCORPION_ZONE_2, "⚠ Schorpioen B", 1);
        }
    }

    private void renderImpsGoblinCoinArea(Graphics2D graphics) {
        WorldPoint center = new WorldPoint(config.impsGoblinCoinCenterX(), config.impsGoblinCoinCenterY(), 0);
        int radius = Math.max(3, config.impsGoblinCoinRadius());
        CenterManager.Center goblinArea = new CenterManager.Center(center, radius, "Goblin coin", true);
        renderAreaTiles(graphics, goblinArea, GOBLIN_COIN_COLOR, false);
        renderCenterTile(graphics, goblinArea, true, GOBLIN_COIN_COLOR);
    }

    private void renderScorpionZone(Graphics2D graphics, WorldPoint zoneCenter, String label, int zoneIndex) {
        // Check of speler hoog genoeg level is — zo ja, toon zone als groen (veilig)
        boolean isSafe = false;
        try {
            net.runelite.api.Player localPlayer = client.getLocalPlayer();
            if (localPlayer != null) {
                int myLevel = localPlayer.getCombatLevel();
                int scorpionLevel = config.impsScorpionLevel();
                isSafe = myLevel > (scorpionLevel * 2) + 1;
            }
        } catch (Exception e) {}

        Color zoneBorder;
        if (isSafe) {
            zoneBorder = new Color(50, 200, 50, 140);
        } else if (zoneIndex == 0) {
            zoneBorder = new Color(192, 57, 43, 195);
        } else {
            zoneBorder = new Color(142, 68, 173, 195);
        }

        int zoneRadius = (config != null) ? config.impsScorpionZoneRadius() : 8;
        int safeR = Math.min(Math.max(1, zoneRadius), MAX_TILE_RENDER_RADIUS);

        for (int dx = -safeR; dx <= safeR; dx++) {
            for (int dy = -safeR; dy <= safeR; dy++) {
                boolean isEdge = (Math.abs(dx) == safeR || Math.abs(dy) == safeR);
                if (!isEdge) continue;

                WorldPoint tilePoint = new WorldPoint(zoneCenter.getX() + dx, zoneCenter.getY() + dy, 0);
                LocalPoint lp = LocalPoint.fromWorld(client, tilePoint);
                if (lp == null) continue;

                Polygon poly = Perspective.getCanvasTilePoly(client, lp);
                if (poly == null) continue;

                graphics.setColor(zoneBorder);
                graphics.setStroke(new BasicStroke(2f));
                graphics.draw(poly);
            }
        }

        // Zone label
        LocalPoint centerLp = LocalPoint.fromWorld(client, zoneCenter);
        if (centerLp != null) {
            net.runelite.api.Point centerScreen = Perspective.localToCanvas(client, centerLp, 0);
            if (centerScreen != null) {
                String displayLabel = isSafe ? "✅ Veilig" : label;
                graphics.setFont(new Font("Arial", Font.BOLD, 10));
                FontMetrics fm = graphics.getFontMetrics();
                int lw = fm.stringWidth(displayLabel);
                int csx = centerScreen.getX();
                int csy = centerScreen.getY();
                graphics.setColor(new Color(0, 0, 0, 180));
                graphics.fillRoundRect(csx - lw / 2 - 3, csy - 12, lw + 6, 14, 3, 3);
                Color lbl = isSafe
                        ? new Color(100, 255, 100)
                        : (zoneIndex == 0 ? new Color(255, 120, 100) : new Color(220, 160, 255));
                graphics.setColor(lbl);
                graphics.drawString(displayLabel, csx - lw / 2, csy);
            }
        }
    }

    // ===================== GEMARKEERDE TILES =====================

    private void renderMarkedTiles(Graphics2D graphics) {
        if (tileMarkerManager == null) return;
        List<TileMarkerManager.MarkedTile> tiles = tileMarkerManager.getTilesForSkill(activeSkill);

        for (TileMarkerManager.MarkedTile tile : tiles) {
            LocalPoint lp = LocalPoint.fromWorld(client, tile.point);
            if (lp == null) continue;

            Polygon poly = Perspective.getCanvasTilePoly(client, lp);
            if (poly == null) continue;

            if (tile.type == TileMarkerManager.MarkerType.EXCLUDED) {
                graphics.setColor(new Color(220, 40, 40, 90));
                graphics.fill(poly);
                graphics.setColor(new Color(255, 80, 80, 220));
                graphics.setStroke(new BasicStroke(2f));
                graphics.draw(poly);
                drawCross(graphics, poly, new Color(255, 60, 60, 240));
            } else {
                graphics.setColor(new Color(255, 220, 0, 100));
                graphics.fill(poly);
                graphics.setColor(new Color(255, 200, 0, 240));
                graphics.setStroke(new BasicStroke(2f));
                graphics.draw(poly);
                drawStar(graphics, poly, new Color(255, 220, 0, 255));
            }

            Rectangle bounds = poly.getBounds();
            int cx = bounds.x + bounds.width / 2;
            int cy = bounds.y + bounds.height / 2;
            String label = tile.type == TileMarkerManager.MarkerType.SAFESPOT ? "SAFE" : "X";
            graphics.setFont(new Font("Arial", Font.BOLD, 9));
            FontMetrics fm = graphics.getFontMetrics();
            int lw = fm.stringWidth(label);
            graphics.setColor(Color.BLACK);
            graphics.drawString(label, cx - lw / 2 + 1, cy + 4 + 1);
            graphics.setColor(tile.type == TileMarkerManager.MarkerType.SAFESPOT
                    ? new Color(255, 240, 0) : new Color(255, 100, 100));
            graphics.drawString(label, cx - lw / 2, cy + 4);
        }
    }

    private void drawCross(Graphics2D graphics, Polygon poly, Color color) {
        Rectangle b = poly.getBounds();
        int cx = b.x + b.width / 2;
        int cy = b.y + b.height / 2;
        int arm = Math.min(b.width, b.height) / 4;
        graphics.setColor(color);
        graphics.setStroke(new BasicStroke(2.5f));
        graphics.drawLine(cx - arm, cy - arm, cx + arm, cy + arm);
        graphics.drawLine(cx + arm, cy - arm, cx - arm, cy + arm);
    }

    private void drawStar(Graphics2D graphics, Polygon poly, Color color) {
        Rectangle b = poly.getBounds();
        int cx = b.x + b.width / 2;
        int cy = b.y + b.height / 2;
        int r = Math.min(b.width, b.height) / 5;
        graphics.setColor(color);
        graphics.setStroke(new BasicStroke(1.5f));
        for (int i = 0; i < 5; i++) {
            double a = Math.toRadians(-90 + i * 72);
            int x1 = cx + (int)(Math.cos(a) * r);
            int y1 = cy + (int)(Math.sin(a) * r);
            double a2 = Math.toRadians(-90 + (i + 2) * 72);
            int x2 = cx + (int)(Math.cos(a2) * r);
            int y2 = cy + (int)(Math.sin(a2) * r);
            graphics.drawLine(x1, y1, x2, y2);
        }
    }
}
