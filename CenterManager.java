package com.combatbot;

import net.runelite.api.coords.WorldPoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * CenterManager - Beheert center-locaties per skill.
 *
 * Elke center heeft een WorldPoint, radius, naam, en active-status.
 * Opslag als config-string: "X:Y:Z:R:Naam:A|X2:Y2:Z2:R2:Naam2:A2"
 * A = 1 (actief, standaard) of 0 (inactief)
 *
 * Bij skill-rotatie wordt een willekeurige ACTIEVE center uit de lijst gekozen.
 */
public class CenterManager {

    public static class Center {
        public final WorldPoint point;
        public int radius;
        public String name;
        public boolean active;

        public Center(WorldPoint point, int radius) {
            this(point, radius, "", true);
        }

        public Center(WorldPoint point, int radius, String name) {
            this(point, radius, name, true);
        }

        public Center(WorldPoint point, int radius, String name, boolean active) {
            this.point = point;
            this.radius = radius;
            this.name = name != null ? name : "";
            this.active = active;
        }

        /** Format: X:Y:Z:R:Name:A (name en active zijn optioneel, backwards compatible) */
        public String serialize() {
            String base = point.getX() + ":" + point.getY() + ":" + point.getPlane() + ":" + radius;
            String safeName = (name != null && !name.isEmpty()) ? name.replace("|", "").replace(":", "") : "";
            return base + ":" + safeName + ":" + (active ? "1" : "0");
        }

        public static Center deserialize(String s) {
            if (s == null || s.trim().isEmpty()) return null;
            String[] parts = s.trim().split(":");
            if (parts.length < 4) return null;
            try {
                int x = Integer.parseInt(parts[0]);
                int y = Integer.parseInt(parts[1]);
                int z = Integer.parseInt(parts[2]);
                int r = Integer.parseInt(parts[3]);
                String name = parts.length >= 5 ? parts[4] : "";
                boolean active = true; // Default actief (backwards compatible)
                if (parts.length >= 6) {
                    active = !"0".equals(parts[5]);
                }
                return new Center(new WorldPoint(x, y, z), Math.max(1, r), name, active);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        @Override
        public String toString() {
            String label = (name != null && !name.isEmpty()) ? name : "(" + point.getX() + "," + point.getY() + ")";
            return label + " r=" + radius + (active ? "" : " [UIT]");
        }
    }

    private static final Random random = new Random();
    public static final int DEFAULT_RADIUS = 10;

    /** Parse een center-string naar een lijst van Centers. */
    public static List<Center> parse(String data) {
        List<Center> result = new ArrayList<>();
        if (data == null || data.trim().isEmpty()) return result;
        for (String part : data.split("\\|")) {
            Center c = Center.deserialize(part.trim());
            if (c != null) result.add(c);
        }
        return result;
    }

    /** Serialiseer een lijst van Centers naar een config-string. */
    public static String serialize(List<Center> centers) {
        if (centers == null || centers.isEmpty()) return "";
        return centers.stream().map(Center::serialize).collect(Collectors.joining("|"));
    }

    /** Kies een willekeurige ACTIEVE center uit de lijst. */
    public static Center pickRandom(String data) {
        List<Center> centers = parse(data);
        List<Center> activeCenters = centers.stream().filter(c -> c.active).collect(Collectors.toList());
        if (activeCenters.isEmpty()) return null;
        return activeCenters.get(random.nextInt(activeCenters.size()));
    }

    /**
     * Fishing: bij meerdere actieve centers (Barbarian + Draynor) kies op basis van Fishing-level.
     * Onder {@link FishingConfig#MIN_FISHING_LEVEL_PREFER_BARBARIAN_OVER_DRAYNOR} gaat de voorkeur naar Draynor
     * (Net/Bait); daarna Barbarian (Lure/Feather e.d.). Anders gewoon willekeurig actief of enige pool.
     */
    public static Center pickFishingCenterForTraining(String data, int fishingLevel) {
        List<Center> pool = fishingTrainingPool(data, fishingLevel);
        if (pool == null || pool.isEmpty()) return null;
        return pool.get(random.nextInt(pool.size()));
    }

    /**
     * True als het huidige center nog bij de pool hoort die bij dit fishing level hoort.
     * Zo voorkom je dat elke loop-tick een andere willekeurige center uit dezelfde pool wordt gekozen.
     */
    public static boolean fishingTrainingCenterStillValid(String data, WorldPoint activeCenter, int fishingLevel) {
        if (activeCenter == null) return false;
        List<Center> pool = fishingTrainingPool(data, fishingLevel);
        if (pool == null || pool.isEmpty()) return false;
        return pool.stream().anyMatch(c -> c.point.equals(activeCenter));
    }

    private static List<Center> fishingTrainingPool(String data, int fishingLevel) {
        List<Center> activeCenters = parse(data).stream().filter(c -> c.active).collect(Collectors.toList());
        if (activeCenters.isEmpty()) return null;
        if (activeCenters.size() == 1) return new ArrayList<>(activeCenters);

        List<Center> barbarian = new ArrayList<>();
        List<Center> draynor = new ArrayList<>();
        List<Center> other = new ArrayList<>();
        for (Center c : activeCenters) {
            String n = c.name == null ? "" : c.name.toLowerCase();
            if (n.contains("barbarian")) {
                barbarian.add(c);
                continue;
            }
            if (n.contains("draynor")) {
                draynor.add(c);
                continue;
            }
            int x = c.point.getX(), y = c.point.getY();
            if (FishingConfig.isBarbarianFishingLocation(x, y)) {
                barbarian.add(c);
            } else if (FishingConfig.isDraynorFishingLocation(x, y, c.name)) {
                draynor.add(c);
            } else {
                other.add(c);
            }
        }

        List<Center> pool;
        if (!barbarian.isEmpty() && !draynor.isEmpty()) {
            if (fishingLevel < FishingConfig.MIN_FISHING_LEVEL_PREFER_BARBARIAN_OVER_DRAYNOR) {
                pool = new ArrayList<>(draynor);
            } else {
                pool = new ArrayList<>(barbarian);
            }
        } else if (!barbarian.isEmpty()) {
            pool = new ArrayList<>(barbarian);
        } else if (!draynor.isEmpty()) {
            pool = new ArrayList<>(draynor);
        } else {
            pool = new ArrayList<>(activeCenters);
        }

        if (pool.isEmpty()) {
            pool = new ArrayList<>(activeCenters);
        }
        return pool;
    }

    /** Voeg een center toe (of vervang als dezelfde positie al bestaat). */
    public static String addCenter(String data, WorldPoint point, int radius) {
        return addCenter(data, point, radius, "");
    }

    /** Voeg een center toe met naam. */
    public static String addCenter(String data, WorldPoint point, int radius, String name) {
        List<Center> centers = parse(data);
        centers.removeIf(c -> c.point.equals(point));
        centers.add(new Center(point, radius, name, true));
        return serialize(centers);
    }

    /** Verwijder de dichtstbijzijnde center. */
    public static String removeNearest(String data, WorldPoint point) {
        List<Center> centers = parse(data);
        if (centers.isEmpty()) return "";
        Center nearest = findNearestCenter(centers, point);
        if (nearest != null) centers.remove(nearest);
        return serialize(centers);
    }

    /** Zoek de dichtstbijzijnde center. */
    public static Center findNearest(String data, WorldPoint point) {
        return findNearestCenter(parse(data), point);
    }

    /** Pas de radius aan van de dichtstbijzijnde center. */
    public static String adjustRadius(String data, WorldPoint nearPoint, int delta) {
        List<Center> centers = parse(data);
        Center nearest = findNearestCenter(centers, nearPoint);
        if (nearest != null) {
            nearest.radius = Math.max(1, Math.min(120, nearest.radius + delta));
        }
        return serialize(centers);
    }

    /** Geeft het aantal centers terug. */
    public static int count(String data) {
        return parse(data).size();
    }

    /** Geeft het aantal ACTIEVE centers terug. */
    public static int countActive(String data) {
        return (int) parse(data).stream().filter(c -> c.active).count();
    }

    /**
     * Bouwt een config-string met alleen de gekozen globale locaties (allemaal actief).
     * {@code selected[i]} hoort bij {@code parse(masterData).get(i)}.
     */
    public static String buildSubsetFromSelection(String masterData, boolean[] selected) {
        List<Center> all = parse(masterData);
        if (all.isEmpty() || selected == null) {
            return "";
        }
        List<Center> out = new ArrayList<>();
        int n = Math.min(all.size(), selected.length);
        for (int i = 0; i < n; i++) {
            if (selected[i]) {
                Center c = all.get(i);
                out.add(new Center(c.point, c.radius, c.name, true));
            }
        }
        return serialize(out);
    }

    /**
     * True als subset dezelfde locaties bevat als master (zelfde WorldPoints, volgorde mag verschillen).
     */
    public static boolean subsetCoversAllGlobal(String masterData, String subsetData) {
        List<Center> m = parse(masterData);
        List<Center> s = parse(subsetData);
        if (m.isEmpty()) {
            return true;
        }
        if (s.size() != m.size()) {
            return false;
        }
        for (Center cm : m) {
            boolean found = false;
            for (Center cs : s) {
                if (cm.point.equals(cs.point)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    /** Welke indices uit master zitten in accountSubset (zelfde WorldPoint). */
    public static boolean[] selectionMaskFromSubset(String masterData, String accountSubset) {
        List<Center> m = parse(masterData);
        boolean[] mask = new boolean[m.size()];
        if (accountSubset == null || accountSubset.trim().isEmpty()) {
            java.util.Arrays.fill(mask, true);
            return mask;
        }
        List<Center> sub = parse(accountSubset);
        for (int i = 0; i < m.size(); i++) {
            WorldPoint p = m.get(i).point;
            for (Center c : sub) {
                if (p.equals(c.point)) {
                    mask[i] = true;
                    break;
                }
            }
        }
        return mask;
    }

    /** Zet alle centers op inactief. Gebruikt wanneer een skill (bijv. Imps) geforceerd wordt uitgeschakeld. */
    public static String deactivateAll(String data) {
        List<Center> centers = parse(data);
        for (Center c : centers) {
            c.active = false;
        }
        return serialize(centers);
    }

    /** Geeft een leesbaar overzicht van alle centers. */
    public static String getSummary(String data) {
        List<Center> centers = parse(data);
        if (centers.isEmpty()) return "(geen centers)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < centers.size(); i++) {
            if (i > 0) sb.append(" | ");
            Center c = centers.get(i);
            String label = (c.name != null && !c.name.isEmpty()) ? c.name : "(" + c.point.getX() + "," + c.point.getY() + ")";
            sb.append(i + 1).append(". ").append(label).append(" r=").append(c.radius);
            if (!c.active) sb.append(" [UIT]");
        }
        return sb.toString();
    }

    // ===================== PRIVATE HELPERS =====================

    private static Center findNearestCenter(List<Center> centers, WorldPoint point) {
        if (centers.isEmpty() || point == null) return null;
        Center nearest = null;
        int minDist = Integer.MAX_VALUE;
        for (Center c : centers) {
            int dist = c.point.distanceTo(point);
            if (dist < minDist) {
                minDist = dist;
                nearest = c;
            }
        }
        return nearest;
    }
}
