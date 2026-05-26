package com.combatbot;

import net.runelite.api.coords.WorldPoint;

import java.awt.Rectangle;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Hot/cold solver voor beginner clues — port van RuneLite
 * {@code HotColdSolver} + {@code HotColdLocation} (BEGINNER) + {@code HotColdTemperature}.
 * <p>
 * Bij elke Feel: filter mogelijke dig-zones op afstand (Chebyshev) en warmer/colder/same.
 * Bij "visibly shaking" is de huidige tile de graveerplek.
 */
public final class BeginnerHotColdSolver {

    /** 9×9 rond centrum — zie RuneLite HotColdLocation.getRect(). */
    private static final int BEGINNER_DIG_RADIUS = 3;

    public enum Temperature {
        ICE_COLD("ice cold", 500, 5000),
        VERY_COLD("very cold", 200, 499),
        COLD("cold", 150, 199),
        WARM("warm", 100, 149),
        HOT("hot", 70, 99),
        VERY_HOT("very hot", 30, 69),
        INCREDIBLY_HOT("incredibly hot", 4, 29),
        VISIBLY_SHAKING("visibly shaking", 0, 3);

        final String text;
        final int minDistance;
        final int maxDistance;

        Temperature(String text, int minDistance, int maxDistance) {
            this.text = text;
            this.minDistance = minDistance;
            this.maxDistance = maxDistance;
        }
    }

    public enum TemperatureChange {
        WARMER("and warmer than"),
        SAME("and the same temperature as"),
        COLDER("but colder than");

        final String text;

        TemperatureChange(String text) {
            this.text = text;
        }

        static TemperatureChange fromMessage(String message) {
            if (message == null || !message.endsWith(" last time.")) {
                return null;
            }
            String low = message.toLowerCase(Locale.ROOT);
            for (TemperatureChange c : values()) {
                if (low.contains(c.text)) {
                    return c;
                }
            }
            return null;
        }
    }

    /** RuneLite HotColdLocation BEGINNER entries. */
    public enum BeginnerZone {
        DRAYNOR_WHEAT(new WorldPoint(3120, 3282, 0), "Draynor wheat field"),
        ICE_MOUNTAIN(new WorldPoint(3007, 3475, 0), "Ice Mountain"),
        LUMBRIDGE_COW(new WorldPoint(3174, 3336, 0), "Cow field N of Lumbridge"),
        DRAYNOR_MUSHROOMS(new WorldPoint(3096, 3379, 0), "Mushrooms NW of Draynor Manor"),
        AL_KHARID_MINE(new WorldPoint(3332, 3313, 0), "NE of Al Kharid mine");

        final WorldPoint center;
        final String label;

        BeginnerZone(WorldPoint center, String label) {
            this.center = center;
            this.label = label;
        }

        Rectangle digRect() {
            return new Rectangle(
                    center.getX() - BEGINNER_DIG_RADIUS,
                    center.getY() - BEGINNER_DIG_RADIUS,
                    BEGINNER_DIG_RADIUS * 2 + 1,
                    BEGINNER_DIG_RADIUS * 2 + 1);
        }
    }

    private final Set<BeginnerZone> possible = EnumSet.allOf(BeginnerZone.class);
    private WorldPoint lastFeelPoint;
    private WorldPoint finalDigSpot;

    public void reset() {
        possible.clear();
        possible.addAll(EnumSet.allOf(BeginnerZone.class));
        lastFeelPoint = null;
        finalDigSpot = null;
    }

    public boolean hasFinalDigSpot() {
        return finalDigSpot != null;
    }

    public WorldPoint getFinalDigSpot() {
        return finalDigSpot;
    }

    public Set<BeginnerZone> getPossibleZones() {
        return Collections.unmodifiableSet(possible);
    }

  /** Eén zone over na filtering — null als 0 of >1. */
    public BeginnerZone soleZone() {
        return possible.size() == 1 ? possible.iterator().next() : null;
    }

    public WorldPoint centroidOfPossibilities() {
        if (possible.isEmpty()) {
            return null;
        }
        long sx = 0;
        long sy = 0;
        for (BeginnerZone z : possible) {
            sx += z.center.getX();
            sy += z.center.getY();
        }
        int n = possible.size();
        return new WorldPoint((int) (sx / n), (int) (sy / n), 0);
    }

    /**
     * Verwerk device-chat. Retourneert true als bericht herkend is.
     */
    public boolean signal(WorldPoint feelPoint, String rawMessage) {
        if (feelPoint == null || rawMessage == null || rawMessage.isEmpty()) {
            return false;
        }
        String low = rawMessage.toLowerCase(Locale.ROOT);
        if (!low.contains("device")) {
            return false;
        }
        Temperature temp = parseTemperature(low);
        if (temp == null) {
            return false;
        }
        if (temp == Temperature.VISIBLY_SHAKING) {
            finalDigSpot = feelPoint;
            return true;
        }
        finalDigSpot = null;
        TemperatureChange change = TemperatureChange.fromMessage(low);
        filterByTemperature(feelPoint, temp, change);
        lastFeelPoint = feelPoint;
        return true;
    }

    static Temperature parseTemperature(String low) {
        if (!low.startsWith("the device is ") && !low.contains("the device is ")) {
            return null;
        }
        Temperature best = null;
        int bestLen = 0;
        for (Temperature t : Temperature.values()) {
            if (low.contains(t.text) && t.text.length() > bestLen) {
                best = t;
                bestLen = t.text.length();
            }
        }
        return best;
    }

    private void filterByTemperature(WorldPoint feelPoint, Temperature temperature, TemperatureChange change) {
        int maxSq = temperature.maxDistance;
        int minSq = temperature.minDistance;

        Rectangle maxArea = new Rectangle(
                feelPoint.getX() - maxSq, feelPoint.getY() - maxSq,
                2 * maxSq + 1, 2 * maxSq + 1);
        Rectangle minArea = new Rectangle(
                feelPoint.getX() - minSq, feelPoint.getY() - minSq,
                2 * minSq + 1, 2 * minSq + 1);

        possible.removeIf(zone -> {
            Rectangle rect = zone.digRect();
            return minArea.contains(rect) || !maxArea.intersects(rect);
        });

        if (lastFeelPoint != null && change != null) {
            switch (change) {
                case COLDER:
                    possible.removeIf(zone ->
                            zone.center.distanceTo2D(feelPoint) <= zone.center.distanceTo2D(lastFeelPoint));
                    break;
                case WARMER:
                    possible.removeIf(zone ->
                            zone.center.distanceTo2D(feelPoint) >= zone.center.distanceTo2D(lastFeelPoint));
                    break;
                case SAME:
                    possible.removeIf(zone ->
                            zone.center.distanceTo2D(feelPoint) != zone.center.distanceTo2D(lastFeelPoint));
                    break;
                default:
                    break;
            }
        }
    }

    public boolean hasAnyFeel() {
        return lastFeelPoint != null;
    }

    public BeginnerHotColdSolver() {
        reset();
    }
}

