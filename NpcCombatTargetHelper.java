package com.combatbot;

import net.storm.api.domain.actors.INPC;
import net.storm.api.domain.actors.IPlayer;
import net.storm.sdk.entities.Players;

import java.util.List;

/**
 * Gedeelde checks: NPC al door een andere speler bezet / multi-combat.
 * Logica gelijk aan wat eerder alleen in {@link CombatHandler} zat.
 */
public final class NpcCombatTargetHelper {

    private NpcCombatTargetHelper() {
    }

    /**
     * {@code true} als deze NPC voor ons niet veilig als nieuw doel is (andere speler bezig),
     * behalve in multi-combat — daar mag de caller zelf {@link #isInMultiCombatZone()} gebruiken en dan niet filteren.
     */
    public static boolean isNpcInCombatWithOther(INPC npc, IPlayer local) {
        if (npc == null || local == null) {
            return false;
        }
        if (npc.isInteracting()) {
            try {
                net.runelite.api.Actor interacting = npc.getInteracting();
                if (interacting != null
                        && interacting != local.getWrapped()
                        && interacting instanceof net.runelite.api.Player) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }

        try {
            List<IPlayer> nearbyPlayers = Players.getAll(p ->
                    p != null
                            && !p.equals(local)
                            && p.isInteracting()
            );
            if (nearbyPlayers != null) {
                net.runelite.api.Actor npcActor = npc.getWrapped();
                for (IPlayer other : nearbyPlayers) {
                    try {
                        if (other.getInteracting() == npcActor) {
                            return true;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        }

        try {
            if (npc.getHealthRatio() > 0 && npc.getHealthRatio() < npc.getHealthScale()
                    && !npc.isInteracting()) {
                return true;
            }
            if (npc.getHealthRatio() > 0 && npc.getHealthRatio() < npc.getHealthScale()
                    && npc.isInteracting()) {
                net.runelite.api.Actor inter = npc.getInteracting();
                if (inter != null && inter != local.getWrapped() && inter instanceof net.runelite.api.Player) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }

        return false;
    }

    /** Varbit 4605: multi-combat gebied (1 = multi). */
    public static boolean isInMultiCombatZone() {
        try {
            net.runelite.api.Client rlClient = net.storm.sdk.game.Client.getWrapped();
            if (rlClient != null) {
                return rlClient.getVarbitValue(4605) == 1;
            }
        } catch (Exception ignored) {
        }
        return false;
    }
}
