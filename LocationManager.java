package com.combatbot;

import net.runelite.api.coords.WorldPoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * Beheert named locaties per skill.
 *
 * Formaat in config: "naam:X:Y:Z:E|naam2:X2:Y2:Z2:E2"
 * E = 1 (enabled, standaard) of 0 (disabled)
 * Oud formaat zonder E wordt als enabled behandeld.
 *
 * Voorbeeld: "Goblins:3243:3247:0:1|Barbarians:3232:3404:0:0"
 */
public class LocationManager {

    private static final Random random = new Random();

    public static class NamedLocation {
        public final String name;
        public final WorldPoint point;
        public final boolean enabled;

        public NamedLocation(String name, WorldPoint point, boolean enabled) {
            this.name = name;
            this.point = point;
            this.enabled = enabled;
        }

        @Override
        public String toString() {
            return name + " (" + point.getX() + "," + point.getY() + "," + point.getPlane() + ")" + (enabled ? "" : " [UIT]");
        }

        /** Serialiseer terug naar config formaat. */
        public String serialize() {
            return name + ":" + point.getX() + ":" + point.getY() + ":" + point.getPlane() + ":" + (enabled ? "1" : "0");
        }
    }

    /**
     * Parses een locatie string naar een lijst van ALLE NamedLocations (enabled + disabled).
     * Formaat: "naam:X:Y:Z:E|naam2:X2:Y2:Z2:E2"
     */
    public static List<NamedLocation> parse(String locationString) {
        List<NamedLocation> result = new ArrayList<>();
        if (locationString == null || locationString.trim().isEmpty()) {
            return result;
        }

        String[] entries = locationString.split("\\|");
        for (String entry : entries) {
            entry = entry.trim();
            if (entry.isEmpty()) continue;

            String[] parts = entry.split(":");
            if (parts.length >= 4) {
                try {
                    String name = parts[0].trim();
                    int x = Integer.parseInt(parts[1].trim());
                    int y = Integer.parseInt(parts[2].trim());
                    int z = Integer.parseInt(parts[3].trim());
                    boolean enabled = true;
                    if (parts.length >= 5) {
                        enabled = !"0".equals(parts[4].trim());
                    }
                    result.add(new NamedLocation(name, new WorldPoint(x, y, z), enabled));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return result;
    }

    /**
     * Parses en retourneert ALLEEN enabled locaties.
     */
    public static List<NamedLocation> parseEnabled(String locationString) {
        return parse(locationString).stream()
                .filter(loc -> loc.enabled)
                .collect(Collectors.toList());
    }

    /**
     * Kiest een willekeurige enabled locatie, of null als er geen zijn.
     */
    public static NamedLocation getRandomEnabled(String locationString) {
        List<NamedLocation> enabled = parseEnabled(locationString);
        if (enabled.isEmpty()) return null;
        return enabled.get(random.nextInt(enabled.size()));
    }

    /**
     * Geeft de eerste geldige enabled locatie terug, of null.
     */
    public static WorldPoint getFirstOrNull(String locationString) {
        List<NamedLocation> locations = parseEnabled(locationString);
        if (locations.isEmpty()) return null;
        return locations.get(0).point;
    }

    /**
     * Geeft de eerste geldige enabled locatie terug, of de fallback.
     */
    public static WorldPoint getFirst(String locationString, WorldPoint fallback) {
        List<NamedLocation> locations = parseEnabled(locationString);
        if (locations.isEmpty()) return fallback;
        return locations.get(0).point;
    }

    /**
     * Geeft de naam van de eerste enabled locatie terug, of "Onbekend".
     */
    public static String getFirstName(String locationString) {
        List<NamedLocation> locations = parseEnabled(locationString);
        if (locations.isEmpty()) return "Onbekend";
        return locations.get(0).name;
    }

    /**
     * Geeft een leesbaar overzicht van alle locaties.
     */
    public static String getSummary(String locationString) {
        List<NamedLocation> locations = parseEnabled(locationString);
        if (locations.isEmpty()) return "Huidige positie";
        if (locations.size() == 1) return locations.get(0).name;
        return locations.get(0).name + " +" + (locations.size() - 1) + " meer";
    }

    /**
     * Toggle de enabled status van een locatie op index en geeft de nieuwe string terug.
     */
    public static String toggleEnabled(String locationString, int index) {
        List<NamedLocation> all = parse(locationString);
        if (index < 0 || index >= all.size()) return locationString;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < all.size(); i++) {
            if (i > 0) sb.append("|");
            NamedLocation loc = all.get(i);
            boolean newEnabled = (i == index) ? !loc.enabled : loc.enabled;
            sb.append(loc.name).append(":").append(loc.point.getX()).append(":")
                    .append(loc.point.getY()).append(":").append(loc.point.getPlane())
                    .append(":").append(newEnabled ? "1" : "0");
        }
        return sb.toString();
    }

    /**
     * Verwijdert een locatie op de gegeven index.
     */
    public static String removeLocation(String locationString, int index) {
        List<NamedLocation> all = parse(locationString);
        if (index < 0 || index >= all.size()) return locationString;

        all.remove(index);
        return all.stream().map(NamedLocation::serialize).collect(Collectors.joining("|"));
    }

    /**
     * Geeft een leesbare lijst van alle locaties met nummers en ✅/❌ indicators.
     */
    public static String getReadableList(String locationString) {
        List<NamedLocation> all = parse(locationString);
        if (all.isEmpty()) return "(geen locaties)";

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < all.size(); i++) {
            NamedLocation loc = all.get(i);
            sb.append(i + 1).append(". ")
                    .append(loc.enabled ? "✅" : "❌").append(" ")
                    .append(loc.name)
                    .append(" (").append(loc.point.getX()).append(",").append(loc.point.getY()).append(")");
            if (i < all.size() - 1) sb.append(" | ");
        }
        return sb.toString();
    }
}
