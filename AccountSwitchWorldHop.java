package com.combatbot;

import net.runelite.api.Client;
import net.runelite.api.World;
import net.runelite.api.WorldType;
import net.runelite.client.callback.ClientThread;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Wereld-hop op het login-scherm vóór inloggen na account-wissel (zelfde strategie als {@link LootHandler}).
 */
public final class AccountSwitchWorldHop {

    private static final int[] F2P_FALLBACK_IDS = {
            301, 308, 316, 335, 379, 380, 381, 382, 383, 384, 385, 386, 387, 388, 389, 390
    };

    private AccountSwitchWorldHop() {
    }

    /**
     * Willekeurige F2P-wereld (niet PVP/skill total te hoog), bij voorkeur anders dan {@code rl.getWorld()}.
     */
    public static int pickRandomF2pWorldId(Client rl) {
        int avoid = rl != null ? rl.getWorld() : -1;
        if (rl != null) {
            World[] list = rl.getWorldList();
            if (list != null && list.length > 0) {
                List<World> candidates = new ArrayList<>();
                for (World w : list) {
                    if (w == null) continue;
                    int id = w.getId();
                    if (id <= 0 || id == avoid) continue;
                    Collection<WorldType> types = w.getTypes();
                    if (types != null && !types.isEmpty()) {
                        if (types.contains(WorldType.MEMBERS)) continue;
                        if (WorldType.isPvpWorld(types)) continue;
                        if (types.contains(WorldType.SKILL_TOTAL)) continue; // vermijd 500+/750+/1k worlds
                        if (types.contains(WorldType.DEADMAN)
                                || types.contains(WorldType.TOURNAMENT_WORLD)
                                || types.contains(WorldType.BETA_WORLD)
                                || types.contains(WorldType.SEASONAL)
                                || types.contains(WorldType.LAST_MAN_STANDING)) {
                            continue;
                        }
                    }
                    candidates.add(w);
                }
                if (!candidates.isEmpty()) {
                    return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size())).getId();
                }
            }
        }
        for (int tries = 0; tries < 12; tries++) {
            int id = F2P_FALLBACK_IDS[ThreadLocalRandom.current().nextInt(F2P_FALLBACK_IDS.length)];
            if (id != avoid) {
                return id;
            }
        }
        return F2P_FALLBACK_IDS[0];
    }

    public static World findWorldById(Client rl, int worldId) {
        if (rl == null || worldId <= 0) {
            return null;
        }
        World[] list = rl.getWorldList();
        if (list == null) {
            return null;
        }
        for (World w : list) {
            if (w != null && w.getId() == worldId) {
                return w;
            }
        }
        return null;
    }

    public static void scheduleHopToWorld(Client rl, ClientThread clientThread, int worldId) {
        if (rl == null) {
            return;
        }
        World dest = findWorldById(rl, worldId);
        if (dest == null) {
            DebugLog.log("Accounts", "Wereld w" + worldId + " niet in clientlijst — overslaan");
            return;
        }
        final Client rlFinal = rl;
        final World destFinal = dest;
        final int idFinal = worldId;
        Runnable doHop = () -> {
            try {
                rlFinal.hopToWorld(destFinal);
            } catch (Throwable hopEx) {
                try {
                    rlFinal.changeWorld(destFinal);
                } catch (Throwable chEx) {
                    try {
                        java.lang.reflect.Method m = rlFinal.getClass().getMethod("hopToWorld", int.class);
                        m.invoke(rlFinal, idFinal);
                    } catch (Throwable ignored) {
                    }
                }
            }
        };
        if (clientThread != null) {
            clientThread.invokeLater(doHop);
        } else {
            doHop.run();
        }
    }
}
