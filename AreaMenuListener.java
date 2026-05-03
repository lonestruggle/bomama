package com.combatbot;

import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Tile;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.MenuOpened;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * AreaMenuListener - Rechtermuisklik-menu voor center- en tile-beheer.
 *
 * Menu-entries worden ingevoegd BOVEN "Cancel" maar ONDER de standaard game opties.
 * Dit zorgt ervoor dat onze opties altijd goed bereikbaar zijn zonder
 * de standaard game menu items te verplaatsen.
 */
public class AreaMenuListener {

    private Client client;
    private TileMarkerManager tileMarkerManager;
    private CombatBotPlugin.ActiveSkill activeSkill = CombatBotPlugin.ActiveSkill.COMBAT;
    private Runnable onChanged;
    private CenterCallback centerCallback;

    // Config strings per skill (bijgewerkt door plugin)
    private String combatCenters = "";
    private String wcCenters = "";
    private String miningCenters = "";
    private String fishingCenters = "";
    private String impsCenters = "";

    /** Callback voor center-bewerkingen. */
    public interface CenterCallback {
        void onAddCenter(CombatBotPlugin.ActiveSkill skill, WorldPoint point);
        void onRemoveCenter(CombatBotPlugin.ActiveSkill skill, WorldPoint point);
        void onAdjustRadius(CombatBotPlugin.ActiveSkill skill, WorldPoint point, int delta);
    }

    public void setClient(Client client) {
        this.client = client;
    }

    public void init(TileMarkerManager manager, Runnable onChanged, CenterCallback centerCallback) {
        this.tileMarkerManager = manager;
        this.onChanged = onChanged;
        this.centerCallback = centerCallback;
    }

    public void setActiveSkill(CombatBotPlugin.ActiveSkill skill) {
        this.activeSkill = skill;
    }

    public void updateCenterStrings(String combat, String wc, String mining, String fishing, String imps) {
        this.combatCenters = combat != null ? combat : "";
        this.wcCenters = wc != null ? wc : "";
        this.miningCenters = mining != null ? mining : "";
        this.fishingCenters = fishing != null ? fishing : "";
        this.impsCenters = imps != null ? imps : "";
    }

    private String getCentersForSkill(CombatBotPlugin.ActiveSkill skill) {
        switch (skill) {
            case COMBAT:      return combatCenters;
            case WOODCUTTING: return wcCenters;
            case MINING:      return miningCenters;
            case FISHING:     return fishingCenters;
            case IMPS:        return impsCenters;
            default:          return "";
        }
    }

    /**
     * Aangeroepen bij MenuOpened event.
     * Voegt center- en tile-marker opties toe.
     * Onze opties komen BOVEN Cancel maar ONDER standaard game opties.
     */
    public void onMenuOpened(MenuOpened event) {
        if (client == null) return;

        Tile hoveredTile = client.getSelectedSceneTile();
        if (hoveredTile == null) return;

        WorldPoint tilePoint = hoveredTile.getWorldLocation();
        if (tilePoint == null) return;

        MenuEntry[] existing = client.getMenuEntries();
        List<MenuEntry> gameEntries = new ArrayList<>();
        MenuEntry cancelEntry = null;

        // Splits bestaande entries: game entries + cancel
        for (MenuEntry entry : existing) {
            if (entry.getOption() != null && entry.getOption().equalsIgnoreCase("Cancel")) {
                cancelEntry = entry;
            } else {
                gameEntries.add(entry);
            }
        }

        // Onze custom entries
        List<MenuEntry> customEntries = new ArrayList<>();

        // === Center opties per skill ===
        addCenterEntries(customEntries, CombatBotPlugin.ActiveSkill.COMBAT, "Combat", tilePoint, "ff3232");
        addCenterEntries(customEntries, CombatBotPlugin.ActiveSkill.WOODCUTTING, "WC", tilePoint, "32c832");
        addCenterEntries(customEntries, CombatBotPlugin.ActiveSkill.MINING, "Mining", tilePoint, "c88c32");
        addCenterEntries(customEntries, CombatBotPlugin.ActiveSkill.FISHING, "Fishing", tilePoint, "3278dc");
        addCenterEntries(customEntries, CombatBotPlugin.ActiveSkill.IMPS, "Imps", tilePoint, "dc32dc");

        // === Tile marking opties (alleen voor actieve skill) ===
        if (tileMarkerManager != null) {
            boolean isExcluded = tileMarkerManager.isTileExcluded(activeSkill, tilePoint);
            boolean hasSafespot = activeSkill == CombatBotPlugin.ActiveSkill.COMBAT
                    && tileMarkerManager.getTilesForSkill(activeSkill).stream()
                    .anyMatch(t -> t.point.equals(tilePoint) && t.type == TileMarkerManager.MarkerType.SAFESPOT);
            boolean hasAnyMarker = isExcluded || hasSafespot;

            if (hasAnyMarker) {
                MenuEntry remove = client.createMenuEntry(-1)
                        .setOption("Verwijder markering")
                        .setTarget("<col=ff0000>Tile marker</col>")
                        .setType(MenuAction.RUNELITE)
                        .onClick(e -> {
                            Tile t = client.getSelectedSceneTile();
                            if (t == null) return;
                            WorldPoint wp = t.getWorldLocation();
                            TileMarkerManager.MarkerType type = isExcluded
                                    ? TileMarkerManager.MarkerType.EXCLUDED
                                    : TileMarkerManager.MarkerType.SAFESPOT;
                            tileMarkerManager.toggleTile(activeSkill, wp, type);
                            if (onChanged != null) onChanged.run();
                        });
                customEntries.add(remove);
            }

            if (activeSkill == CombatBotPlugin.ActiveSkill.COMBAT && !hasSafespot) {
                MenuEntry safespot = client.createMenuEntry(-1)
                        .setOption("Voeg safespot toe")
                        .setTarget("<col=ffff00>Tile marker</col>")
                        .setType(MenuAction.RUNELITE)
                        .onClick(e -> {
                            Tile t = client.getSelectedSceneTile();
                            if (t == null) return;
                            WorldPoint wp = t.getWorldLocation();
                            if (tileMarkerManager.isTileExcluded(activeSkill, wp)) {
                                tileMarkerManager.toggleTile(activeSkill, wp, TileMarkerManager.MarkerType.EXCLUDED);
                            }
                            tileMarkerManager.toggleTile(activeSkill, wp, TileMarkerManager.MarkerType.SAFESPOT);
                            if (onChanged != null) onChanged.run();
                        });
                customEntries.add(safespot);
            }

            if (!isExcluded) {
                MenuEntry excluded = client.createMenuEntry(-1)
                        .setOption("Markeer tile (Excluded)")
                        .setTarget("<col=ff4444>Tile marker</col>")
                        .setType(MenuAction.RUNELITE)
                        .onClick(e -> {
                            Tile t = client.getSelectedSceneTile();
                            if (t == null) return;
                            WorldPoint wp = t.getWorldLocation();
                            if (hasSafespot) {
                                tileMarkerManager.toggleTile(activeSkill, wp, TileMarkerManager.MarkerType.SAFESPOT);
                            }
                            tileMarkerManager.toggleTile(activeSkill, wp, TileMarkerManager.MarkerType.EXCLUDED);
                            if (onChanged != null) onChanged.run();
                        });
                customEntries.add(excluded);
            }
        }

        // Samenvoegen: RuneLite menu = LAATSTE entry bovenaan.
        // Volgorde in array: cancel (onderaan menu) → custom → game entries (bovenaan menu)
        List<MenuEntry> finalEntries = new ArrayList<>();
        if (cancelEntry != null) {
            finalEntries.add(cancelEntry);
        }
        finalEntries.addAll(customEntries);
        finalEntries.addAll(gameEntries);

        client.setMenuEntries(finalEntries.toArray(new MenuEntry[0]));
    }

    /**
     * Voegt center-bewerkingsopties toe per skill.
     */
    private void addCenterEntries(List<MenuEntry> entries, CombatBotPlugin.ActiveSkill skill, String skillName, WorldPoint tilePoint, String hexColor) {
        if (centerCallback == null) return;

        String centersData = getCentersForSkill(skill);
        CenterManager.Center nearest = CenterManager.findNearest(centersData, tilePoint);
        int radiusAdjustBuffer = (skill == CombatBotPlugin.ActiveSkill.IMPS) ? 30 : 3;
        boolean hasNearbyCenter = nearest != null && nearest.point.distanceTo(tilePoint) <= nearest.radius + radiusAdjustBuffer;

        // Altijd: "Voeg center toe"
        MenuEntry addEntry = client.createMenuEntry(-1)
                .setOption("Voeg center toe (" + skillName + ")")
                .setTarget("<col=" + hexColor + ">Area center</col>")
                .setType(MenuAction.RUNELITE)
                .onClick(e -> {
                    Tile t = client.getSelectedSceneTile();
                    if (t == null) return;
                    centerCallback.onAddCenter(skill, t.getWorldLocation());
                });
        entries.add(addEntry);

        // Als er een center in de buurt is: radius +/- en verwijder
        if (hasNearbyCenter) {
            MenuEntry radiusUp = client.createMenuEntry(-1)
                    .setOption("Radius + (" + skillName + ") [r=" + nearest.radius + "]")
                    .setTarget("<col=" + hexColor + ">Area center</col>")
                    .setType(MenuAction.RUNELITE)
                    .onClick(e -> {
                        Tile t = client.getSelectedSceneTile();
                        if (t == null) return;
                        centerCallback.onAdjustRadius(skill, t.getWorldLocation(), 1);
                    });
            entries.add(radiusUp);

            MenuEntry radiusUp5 = client.createMenuEntry(-1)
                    .setOption("Radius +5 (" + skillName + ") [r=" + nearest.radius + "]")
                    .setTarget("<col=" + hexColor + ">Area center</col>")
                    .setType(MenuAction.RUNELITE)
                    .onClick(e -> {
                        Tile t = client.getSelectedSceneTile();
                        if (t == null) return;
                        centerCallback.onAdjustRadius(skill, t.getWorldLocation(), 5);
                    });
            entries.add(radiusUp5);

            MenuEntry radiusDown = client.createMenuEntry(-1)
                    .setOption("Radius - (" + skillName + ") [r=" + nearest.radius + "]")
                    .setTarget("<col=" + hexColor + ">Area center</col>")
                    .setType(MenuAction.RUNELITE)
                    .onClick(e -> {
                        Tile t = client.getSelectedSceneTile();
                        if (t == null) return;
                        centerCallback.onAdjustRadius(skill, t.getWorldLocation(), -1);
                    });
            entries.add(radiusDown);

            MenuEntry radiusDown5 = client.createMenuEntry(-1)
                    .setOption("Radius -5 (" + skillName + ") [r=" + nearest.radius + "]")
                    .setTarget("<col=" + hexColor + ">Area center</col>")
                    .setType(MenuAction.RUNELITE)
                    .onClick(e -> {
                        Tile t = client.getSelectedSceneTile();
                        if (t == null) return;
                        centerCallback.onAdjustRadius(skill, t.getWorldLocation(), -5);
                    });
            entries.add(radiusDown5);

            MenuEntry removeEntry = client.createMenuEntry(-1)
                    .setOption("Verwijder center (" + skillName + ")")
                    .setTarget("<col=" + hexColor + ">Area center</col>")
                    .setType(MenuAction.RUNELITE)
                    .onClick(e -> {
                        Tile t = client.getSelectedSceneTile();
                        if (t == null) return;
                        centerCallback.onRemoveCenter(skill, t.getWorldLocation());
                    });
            entries.add(removeEntry);
        }
    }
}
