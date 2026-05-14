package com.combatbot;

import net.storm.api.domain.actors.IPlayer;

/**
 * Shared guard against repeated NPC/object clicks while the previous interaction is still resolving.
 */
public final class InteractionThrottle {
    private static final long DEFAULT_INTERACTION_WAIT_MS = 4_500L;
    private static long lastGlobalInteractionMs;

    private InteractionThrottle() {
    }

    public static boolean shouldWaitAfterInteraction(IPlayer local, long lastInteractionMs) {
        return shouldWaitAfterInteraction(local, lastInteractionMs, DEFAULT_INTERACTION_WAIT_MS);
    }

    public static boolean shouldWaitAfterInteraction(IPlayer local, long lastInteractionMs, long waitMs) {
        if (local == null || lastInteractionMs <= 0) {
            return false;
        }
        long age = System.currentTimeMillis() - lastInteractionMs;
        return age < Math.max(0L, waitMs) && (local.isMoving() || local.isInteracting());
    }

    public static void markGlobalInteraction() {
        lastGlobalInteractionMs = System.currentTimeMillis();
    }

    public static boolean shouldWaitAfterGlobalInteraction(IPlayer local) {
        return shouldWaitAfterInteraction(local, lastGlobalInteractionMs, DEFAULT_INTERACTION_WAIT_MS);
    }
}
