package com.combatbot;

import net.runelite.api.coords.WorldPoint;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Beheert gemarkeerde tiles per skill.
 *
 * Twee soorten markering:
 *   - EXCLUDED  → bot doet niets in deze tile (bijv. obstakels, gevaarlijke plekken)
 *   - SAFESPOT  → alleen voor Combat: bot keert hier terug na aanvallen op afstand
 *
 * Opslag als config-string:
 *   formaat: "X:Y:Z:TYPE|X2:Y2:Z2:TYPE2"
 *   TYPE = "E" (excluded) of "S" (safespot)
 *
 * Presets worden opgeslagen als benoemde sets, bijv.:
 *   "PresetNaam=X:Y:Z:E|X2:Y2:Z2:S;PresetNaam2=..."
 */
public class TileMarkerManager {

    public enum MarkerType {
        EXCLUDED,   // Tile uitsluiten (bot zoekt/werkt hier niet)
        SAFESPOT    // Safespot voor ranged/magic combat
    }

    public static class MarkedTile {
        public final WorldPoint point;
        public final MarkerType type;

        public MarkedTile(WorldPoint point, MarkerType type) {
            this.point = point;
            this.type = type;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof MarkedTile)) return false;
            MarkedTile that = (MarkedTile) o;
            return point.equals(that.point);
        }

        @Override
        public int hashCode() { return point.hashCode(); }

        public String serialize() {
            return point.getX() + ":" + point.getY() + ":" + point.getPlane() + ":" +
                    (type == MarkerType.SAFESPOT ? "S" : "E");
        }

        public static MarkedTile deserialize(String s) {
            String[] parts = s.split(":");
            if (parts.length < 4) return null;
            try {
                int x = Integer.parseInt(parts[0].trim());
                int y = Integer.parseInt(parts[1].trim());
                int z = Integer.parseInt(parts[2].trim());
                MarkerType t = parts[3].trim().equalsIgnoreCase("S") ? MarkerType.SAFESPOT : MarkerType.EXCLUDED;
                return new MarkedTile(new WorldPoint(x, y, z), t);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    // Tiles per skill
    private final List<MarkedTile> combatTiles   = new ArrayList<>();
    private final List<MarkedTile> wcTiles        = new ArrayList<>();
    private final List<MarkedTile> miningTiles    = new ArrayList<>();
    private final List<MarkedTile> fishingTiles   = new ArrayList<>();

    // ===================== TOGGLE =====================

    /**
     * Voeg toe of verwijder een tile voor de gegeven skill.
     * Als de tile al bestaat: verwijder hem.
     * Als de tile nieuw is: voeg toe met het opgegeven type.
     */
    public boolean toggleTile(CombatBotPlugin.ActiveSkill skill, WorldPoint point, MarkerType type) {
        List<MarkedTile> list = getListForSkill(skill);
        MarkedTile existing = list.stream().filter(t -> t.point.equals(point)).findFirst().orElse(null);
        if (existing != null) {
            list.remove(existing);
            return false; // verwijderd
        } else {
            list.add(new MarkedTile(point, type));
            return true; // toegevoegd
        }
    }

    public boolean isTileExcluded(CombatBotPlugin.ActiveSkill skill, WorldPoint point) {
        return getListForSkill(skill).stream()
                .anyMatch(t -> t.point.equals(point) && t.type == MarkerType.EXCLUDED);
    }

    public WorldPoint getSafespot() {
        return combatTiles.stream()
                .filter(t -> t.type == MarkerType.SAFESPOT)
                .map(t -> t.point)
                .findFirst().orElse(null);
    }

    /** Alle safespots voor combat (meerdere tiles met type SAFESPOT). */
    public List<WorldPoint> getAllSafespots() {
        return combatTiles.stream()
                .filter(t -> t.type == MarkerType.SAFESPOT)
                .map(t -> t.point)
                .collect(Collectors.toList());
    }

    public List<MarkedTile> getTilesForSkill(CombatBotPlugin.ActiveSkill skill) {
        return Collections.unmodifiableList(getListForSkill(skill));
    }

    // ===================== SERIALISATIE =====================

    public String serializeForSkill(CombatBotPlugin.ActiveSkill skill) {
        List<MarkedTile> list = getListForSkill(skill);
        return list.stream().map(MarkedTile::serialize).collect(Collectors.joining("|"));
    }

    public void loadForSkill(CombatBotPlugin.ActiveSkill skill, String data) {
        List<MarkedTile> list = getListForSkill(skill);
        list.clear();
        if (data == null || data.trim().isEmpty()) return;
        for (String part : data.split("\\|")) {
            MarkedTile t = MarkedTile.deserialize(part.trim());
            if (t != null) list.add(t);
        }
    }

    // ===================== PRESET SERIALISATIE =====================

    /**
     * Serialiseer alle skills naar één preset-string.
     * Formaat: "COMBAT=...|WC=...|MINING=...|FISHING=..."
     */
    public String serializeAllAsPreset() {
        return "COMBAT=" + serializeForSkill(CombatBotPlugin.ActiveSkill.COMBAT) + "§" +
                "WC="     + serializeForSkill(CombatBotPlugin.ActiveSkill.WOODCUTTING) + "§" +
                "MINING=" + serializeForSkill(CombatBotPlugin.ActiveSkill.MINING) + "§" +
                "FISHING=" + serializeForSkill(CombatBotPlugin.ActiveSkill.FISHING);
    }

    /**
     * Laad een preset-string terug in alle skills.
     */
    public void loadFromPreset(String presetData) {
        if (presetData == null || presetData.trim().isEmpty()) return;
        for (String section : presetData.split("§")) {
            if (section.startsWith("COMBAT=")) {
                loadForSkill(CombatBotPlugin.ActiveSkill.COMBAT, section.substring(7));
            } else if (section.startsWith("WC=")) {
                loadForSkill(CombatBotPlugin.ActiveSkill.WOODCUTTING, section.substring(3));
            } else if (section.startsWith("MINING=")) {
                loadForSkill(CombatBotPlugin.ActiveSkill.MINING, section.substring(7));
            } else if (section.startsWith("FISHING=")) {
                loadForSkill(CombatBotPlugin.ActiveSkill.FISHING, section.substring(8));
            }
        }
    }

    // ===================== PRESET OPSLAAN/LADEN (benoemd) =====================

    /**
     * Voeg een preset toe aan de presetlijst.
     * Presets string formaat: "Naam1=DATA1;;Naam2=DATA2"
     */
    public static String addPreset(String existing, String name, String presetData) {
        String newEntry = name.replace("=", "_").replace(";;", "_") + "=" + presetData;
        if (existing == null || existing.trim().isEmpty()) return newEntry;
        return existing.trim() + ";;" + newEntry;
    }

    /**
     * Geeft de data van een preset op naam terug.
     */
    public static String getPreset(String presetList, String name) {
        if (presetList == null || presetList.trim().isEmpty()) return null;
        for (String entry : presetList.split(";;")) {
            int eq = entry.indexOf('=');
            if (eq < 0) continue;
            String n = entry.substring(0, eq).trim();
            if (n.equalsIgnoreCase(name)) return entry.substring(eq + 1);
        }
        return null;
    }

    /**
     * Geeft de lijst van presetnamen terug.
     */
    public static List<String> getPresetNames(String presetList) {
        List<String> names = new ArrayList<>();
        if (presetList == null || presetList.trim().isEmpty()) return names;
        for (String entry : presetList.split(";;")) {
            int eq = entry.indexOf('=');
            if (eq > 0) names.add(entry.substring(0, eq).trim());
        }
        return names;
    }

    /**
     * Verwijder een preset op naam.
     */
    public static String removePreset(String presetList, String name) {
        if (presetList == null || presetList.trim().isEmpty()) return "";
        return Arrays.stream(presetList.split(";;"))
                .filter(entry -> {
                    int eq = entry.indexOf('=');
                    return eq > 0 && !entry.substring(0, eq).trim().equalsIgnoreCase(name);
                })
                .collect(Collectors.joining(";;"));
    }

    // ===================== PRIVATE HELPERS =====================

    private List<MarkedTile> getListForSkill(CombatBotPlugin.ActiveSkill skill) {
        switch (skill) {
            case WOODCUTTING: return wcTiles;
            case MINING:      return miningTiles;
            case FISHING:     return fishingTiles;
            case COMBAT:
            default:          return combatTiles;
        }
    }
}
