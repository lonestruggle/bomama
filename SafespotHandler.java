package com.combatbot;

import net.runelite.api.coords.WorldPoint;
import net.storm.api.domain.actors.INPC;
import net.storm.api.domain.actors.IPlayer;
import net.storm.sdk.entities.NPCs;
import net.storm.sdk.entities.Players;

import java.util.ArrayList;
import java.util.List;

/**
 * SafespotHandler - Beheert multi-safespot logica voor ranged/magic combat.
 *
 * Meerdere safespots worden ondersteund. Als de bot op een safespot toch
 * melee-schade ontvangt (NPC staat adjacent), wisselt hij automatisch
 * naar de volgende safespot in de lijst.
 *
 * States:
 *   WAITING   → op safespot, wacht op NPC in range
 *   ATTACKING → speler is in combat (isInteracting() = true)
 *   RETURNING → lopen terug naar safespot
 *   ENGAGING  → eenmalig verlaten van safespot om NPC aan te vallen
 */
public class SafespotHandler {

    public enum SafeState {
        WAITING,    // Op safespot, wachten
        ATTACKING,  // In combat
        RETURNING,  // Terug lopen naar safespot
        ENGAGING    // Eenmalig NPC aanvallen buiten safespot
    }

    private final List<WorldPoint> safespots = new ArrayList<>();
    private int currentSafespotIndex = 0;
    private SafeState state = SafeState.WAITING;

    // Eenmaal aangevallen = we moeten NIET opnieuw uitlopen
    private boolean hasEngagedTarget = false;
    private int engagedNpcId = -1;

    // Range waarbinnen we vanuit safespot kunnen aanvallen (tiles)
    private int attackRange;

    private static final int SAFESPOT_THRESHOLD = 1; // max tiles van safespot

    // Tracking: hoeveel ticks we op de safespot staan terwijl een NPC adjacent is
    private int hitWhileOnSpotCount = 0;
    private static final int HIT_THRESHOLD = 3; // Na 3 ticks adjacent → rotate

    private static void debug(String msg) {
        DebugLog.log("Safespot", msg);
    }

    public SafespotHandler(WorldPoint safespotPoint, int attackRange) {
        this.attackRange = attackRange;
        if (safespotPoint != null) {
            safespots.add(safespotPoint);
        }
    }

    /** Stel meerdere safespots in (vervangt de huidige lijst). */
    public void setSafespots(List<WorldPoint> points) {
        safespots.clear();
        if (points != null) safespots.addAll(points);
        currentSafespotIndex = 0;
        resetState();
        debug("Safespots geladen: " + safespots.size() + " spots");
    }

    /** Stel een enkele safespot in (backwards compatible). */
    public void setSafespot(WorldPoint point) {
        safespots.clear();
        if (point != null) safespots.add(point);
        currentSafespotIndex = 0;
        resetState();
    }

    /** Huidige actieve safespot. */
    public WorldPoint getCurrentSafespot() {
        if (safespots.isEmpty()) return null;
        if (currentSafespotIndex >= safespots.size()) currentSafespotIndex = 0;
        return safespots.get(currentSafespotIndex);
    }

    public int getSafespotCount() { return safespots.size(); }

    public SafeState getState() { return state; }

    public boolean isOnSafespot() {
        IPlayer local = Players.getLocal();
        WorldPoint sp = getCurrentSafespot();
        if (local == null || sp == null) return false;
        return local.getWorldLocation().distanceTo(sp) <= SAFESPOT_THRESHOLD;
    }

    /**
     * Wissel naar de volgende safespot in de lijst.
     * Als we al op de laatste zitten, ga terug naar de eerste.
     */
    /**
     * Wissel naar de dichtstbijzijnde andere safespot (niet de huidige).
     * Kiest altijd de safespot die het dichtst bij de speler is,
     * zodat de bot niet naar een verre spot loopt.
     */
    private void rotateToNextSafespot() {
        if (safespots.size() <= 1) {
            debug("Kan niet roteren — slechts " + safespots.size() + " safespot(s)");
            return;
        }

        IPlayer local = Players.getLocal();
        WorldPoint playerPos = (local != null) ? local.getWorldLocation() : null;

        int bestIndex = -1;
        int bestDist = Integer.MAX_VALUE;

        for (int i = 0; i < safespots.size(); i++) {
            if (i == currentSafespotIndex) continue; // skip huidige
            WorldPoint sp = safespots.get(i);
            int dist = (playerPos != null) ? playerPos.distanceTo(sp) : i; // fallback: volgende in lijst
            if (dist < bestDist) {
                bestDist = dist;
                bestIndex = i;
            }
        }

        if (bestIndex < 0) {
            // Fallback: gewoon volgende in lijst
            bestIndex = (currentSafespotIndex + 1) % safespots.size();
        }

        int oldIndex = currentSafespotIndex;
        currentSafespotIndex = bestIndex;
        WorldPoint newSpot = getCurrentSafespot();
        debug("Safespot rotate: #" + oldIndex + " → #" + currentSafespotIndex +
                " (" + newSpot.getX() + "," + newSpot.getY() + ") dist=" + bestDist);
        hitWhileOnSpotCount = 0;
        state = SafeState.RETURNING;
        hasEngagedTarget = false;
        engagedNpcId = -1;
    }

    /**
     * Check of een NPC adjacent is (melee range, 1 tile) terwijl we op de safespot staan.
     * Dit betekent dat de safespot niet werkt voor die NPC.
     */
    private boolean isBeingMeleedOnSpot() {
        IPlayer local = Players.getLocal();
        if (local == null) return false;
        WorldPoint playerPos = local.getWorldLocation();

        List<INPC> attackers = NPCs.getAll(npc ->
                npc != null && npc.isInteracting()
                        && npc.getInteracting() == local.getWrapped()
                        && !npc.isDead()
                        && npc.getWorldLocation().distanceTo(playerPos) <= 1);

        return attackers != null && !attackers.isEmpty();
    }

    /**
     * Hoofd-logica. Geeft de gewenste actie terug als delay.
     *
     * @param target     De gevonden NPC om aan te vallen (kan null zijn)
     * @param isInCombat Of de speler momenteel interacteert (isInteracting())
     * @return delay in ms
     */
    public int tick(INPC target, boolean isInCombat) {
        WorldPoint safespotPoint = getCurrentSafespot();
        if (safespotPoint == null) return -1;

        IPlayer local = Players.getLocal();
        if (local == null) return 1000;

        boolean onSafespot = isOnSafespot();

        // === MULTI-SAFESPOT CHECK ===
        // Als we op de safespot staan maar een NPC staat adjacent (melee), rotate
        if (onSafespot && isBeingMeleedOnSpot()) {
            hitWhileOnSpotCount++;
            debug("NPC adjacent op safespot! count=" + hitWhileOnSpotCount + "/" + HIT_THRESHOLD);
            if (hitWhileOnSpotCount >= HIT_THRESHOLD && safespots.size() > 1) {
                rotateToNextSafespot();
                MovementHelper.walkToExact(getCurrentSafespot());
                return 800;
            }
        } else if (onSafespot) {
            // Reset counter als we veilig zijn
            hitWhileOnSpotCount = 0;
        }

        // Als we in combat zijn: we aanvallen al — gewoon wachten
        if (isInCombat) {
            state = SafeState.ATTACKING;
            hasEngagedTarget = true;
            if (target != null) engagedNpcId = System.identityHashCode(target);

            // Als speler van safespot is afgezakt tijdens combat: terug
            if (!onSafespot) {
                MovementHelper.walkToExact(safespotPoint);
                return 800;
            }
            return 600; // Wacht in combat
        }

        // Niet in combat → check of we terug moeten naar safespot
        if (!onSafespot) {
            state = SafeState.RETURNING;
            MovementHelper.walkToExact(safespotPoint);
            return 1000;
        }

        // Op safespot — check NPC
        if (target == null) {
            hasEngagedTarget = false;
            engagedNpcId = -1;
            state = SafeState.WAITING;
            return 1000;
        }

        // NPC gevonden
        int distToNpc = local.getWorldLocation().distanceTo(target.getWorldLocation());
        int npcId = System.identityHashCode(target);

        if (distToNpc <= attackRange) {
            // NPC is in range: aanvallen vanuit safespot
            state = SafeState.ATTACKING;
            target.interact("Attack");
            hasEngagedTarget = true;
            engagedNpcId = npcId;
            return 800;
        }

        // NPC buiten range
        if (hasEngagedTarget && engagedNpcId == npcId) {
            // Al aangevallen: NIET opnieuw uitlopen, wacht tot hij terugkomt
            state = SafeState.WAITING;
            return 600;
        }

        if (!hasEngagedTarget) {
            // Nog NIET aangevallen: loop eenmalig naar NPC, attack, kom terug
            state = SafeState.ENGAGING;
            target.interact("Attack");
            hasEngagedTarget = true;
            engagedNpcId = npcId;
            return 600;
        }

        state = SafeState.WAITING;
        return 800;
    }

    public void reset() {
        resetState();
    }

    private void resetState() {
        state = SafeState.WAITING;
        hasEngagedTarget = false;
        engagedNpcId = -1;
        hitWhileOnSpotCount = 0;
    }
}
