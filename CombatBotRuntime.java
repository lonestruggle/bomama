package com.combatbot;

import java.util.function.BooleanSupplier;

/**
 * Dunne koppeling naar de actieve {@link CombatBotPlugin} voor handlers zonder plugin-referentie
 * (o.a. failure-nudge na mislukte interacties / timeouts).
 */
public final class CombatBotRuntime {

    private static volatile CombatBotPlugin activePlugin;

    private CombatBotRuntime() {
    }

    public static void setActivePlugin(CombatBotPlugin plugin) {
        activePlugin = plugin;
    }

    public static CombatBotPlugin getActivePlugin() {
        return activePlugin;
    }

    /** Zie {@link CombatBotPlugin#applyFailureNudgeAfterBadInteraction()}. */
    public static void applyFailureNudgeAfterBadInteraction() {
        CombatBotPlugin p = activePlugin;
        if (p != null) {
            p.applyFailureNudgeAfterBadInteraction();
        }
    }

    /** Zie {@link CombatBotPlugin#executeWithFailCheck(BooleanSupplier, String)}. */
    public static boolean executeWithFailCheck(BooleanSupplier action, String logContext) {
        CombatBotPlugin p = activePlugin;
        if (p == null) {
            return action.getAsBoolean();
        }
        return p.executeWithFailCheck(action, logContext);
    }

    /**
     * Pollt {@code condition} tot true of {@code timeoutMs} verstreken. Bij timeout: failure-nudge + log.
     * Gebruik na booth/banker-klik of {@code Bank#open()} — niet na een enkele “klik geregistreerd”-boolean.
     */
    public static boolean waitWithFailCheck(BooleanSupplier condition, int timeoutMs, String logContext) {
        if (condition == null) {
            return false;
        }
        int cap = Math.max(1, timeoutMs);
        long deadline = System.currentTimeMillis() + cap;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (condition.getAsBoolean()) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
            try {
                Thread.sleep(80);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        try {
            if (condition.getAsBoolean()) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        DebugLog.log("CombatBot", "[FailNudge timeout] " + logContext + " (" + cap + "ms)");
        applyFailureNudgeAfterBadInteraction();
        return false;
    }
}
