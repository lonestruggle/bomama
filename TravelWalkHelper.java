package com.combatbot;

import java.util.Random;

/**
 * Gedeelde travel-logica: vloeiend doorlopen met configureerbare reclick-interval
 * (lang pad opnieuw klikken terwijl de speler al beweegt).
 */
public final class TravelWalkHelper {

    private TravelWalkHelper() {}
    private static final int RECLICK_MIN_MS = 800;
    private static final int RECLICK_MAX_MS = 1600;

    public static boolean reclickReady(long lastClickMs, CombatBotConfig config) {
        // Vloeiend doorlopen: reclick ook tijdens movement, met menselijk random ritme.
        // Drempel is per click pseudo-random op basis van lastClickMs: 800-1600ms.
        long seed = (lastClickMs * 1103515245L + 12345L) & 0x7fffffffL;
        int ms = RECLICK_MIN_MS + (int) (seed % (RECLICK_MAX_MS - RECLICK_MIN_MS + 1));
        return System.currentTimeMillis() - lastClickMs >= ms;
    }

    /** Korte wacht als nog geen reclick mag. */
    public static int shortWaitMs(Random rng) {
        int r = rng != null ? rng.nextInt(200) : 100;
        return 280 + r;
    }

    public static int postClickDelayMs(CombatBotConfig config, Random rng) {
        int min = config != null ? config.travelPostClickDelayMin() : 400;
        int max = config != null ? config.travelPostClickDelayMax() : 900;
        min = Math.max(80, min);
        max = Math.max(min, max);
        if (rng == null) return min;
        return min + rng.nextInt(max - min + 1);
    }
}
