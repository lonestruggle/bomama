package com.combatbot;

import net.storm.api.domain.actors.INPC;
import net.storm.api.domain.actors.IPlayer;
import net.storm.sdk.entities.NPCs;
import net.storm.sdk.entities.Players;

import java.util.Locale;

/**
 * Bepaalt of account-rotatie moet wachten op <strong>echte NPC-combat</strong>.
 * <p>
 * Niet blokkeren tijdens skills: een <strong>fishing spot</strong> is ook een {@link INPC} en
 * {@link IPlayer#isInteracting()} is dan true — dat is géén combat. We gebruiken daarom
 * vis-animaties + NPC-naam + andere skilling-animaties om valse "in combat"-detectie te voorkomen.
 */
public final class AccountSwitchCombatGate {

    private AccountSwitchCombatGate() {
    }

    /**
     * {@code true} alleen bij aanval/aangevallen worden door een <strong>echte vijand-NPC</strong>,
     * niet tijdens vissen/hout kappen e.d.
     */
    public static boolean isInNpcCombat() {
        IPlayer local = Players.getLocal();
        if (local == null) {
            return false;
        }
        try {
            if (isSkillingAnimation(local.getAnimation())) {
                return false;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (local.isInteracting() && local.getInteracting() instanceof INPC) {
                INPC target = (INPC) local.getInteracting();
                if (target == null || target.isDead()) {
                    return false;
                }
                if (isNonCombatInteractNpc(target)) {
                    return false;
                }
                return true;
            }
        } catch (Throwable ignored) {
            return false;
        }
        try {
            INPC attacker = NPCs.getNearest(npc -> npc != null
                    && !npc.isDead()
                    && !isNonCombatInteractNpc(npc)
                    && npc.isInteracting()
                    && npc.getInteracting() != null
                    && npc.getInteracting() == local.getWrapped());
            return attacker != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Vis-animaties (zelfde cluster als LootHandler). */
    private static boolean isOsrsFishingAnimation(int animId) {
        if (animId < 0) {
            return false;
        }
        if (animId >= 618 && animId <= 628) {
            return true;
        }
        return animId == 2891 || animId == 2892;
    }

    /** WC-animaties (zelfde idee als {@link WoodcutterHandler#isWoodcuttingPlayerAnimation}). */
    private static boolean isWoodcuttingAnimation(int animId) {
        if (animId < 0) {
            return false;
        }
        if (animId >= 867 && animId <= 876) {
            return true;
        }
        if (animId >= 3282 && animId <= 3305) {
            return true;
        }
        return animId == 8324 || animId == 10073;
    }

    /** Veelvoorkomende mining-zwaai (pickaxe); smal genoeg om niet met melee te overlappen. */
    private static boolean isMiningAnimation(int animId) {
        if (animId < 0) {
            return false;
        }
        return animId >= 675 && animId <= 682;
    }

    private static boolean isSkillingAnimation(int animId) {
        return isOsrsFishingAnimation(animId) || isWoodcuttingAnimation(animId) || isMiningAnimation(animId);
    }

    private static boolean looksLikeFishingSpotName(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("fishing spot") || lower.contains("rod fishing");
    }

    /**
     * NPCs waarmee je interact hebt zonder combat (spot/object met NPC-wrapper).
     */
    private static boolean isNonCombatInteractNpc(INPC npc) {
        if (npc == null) {
            return true;
        }
        return looksLikeFishingSpotName(npc.getName());
    }
}
