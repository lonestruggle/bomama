package com.combatbot;

import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.World;
import net.runelite.api.WorldType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.util.Text;
import net.storm.api.domain.widgets.IWidget;
import net.storm.sdk.game.Game;
import net.storm.sdk.game.Worlds;
import net.storm.sdk.widgets.Widgets;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
/**
 * Wereld-hop: ingame via Storm {@link Worlds} (world hopper UI), login-scherm via RuneLite {@link Client#hopToWorld}.
 */
public final class AccountSwitchWorldHop {

    private static final int[] F2P_FALLBACK_IDS = {
            301, 308, 316, 335, 379, 380, 381, 382, 383, 384, 385, 386, 387, 388, 389, 390
    };

    /** OSRS messagebox / world-hop bevestiging (iface 229 e.d.). */
    private static final int[] HOP_CONFIRM_INTERFACE_GROUPS = {229, 219, 217, 231, 193, 162};

    /** Werelden waarvan de client "choose a different world" gaf — tijdelijk vermijden. */
    private static final Set<Integer> RECENTLY_REJECTED_WORLD_IDS = ConcurrentHashMap.newKeySet();
    private static volatile int lastAttemptedHopWorldId = -1;

    private AccountSwitchWorldHop() {
    }

    private static void debug(String msg) {
        DebugLog.log("WorldHop", msg);
    }

    /**
     * Willekeurige F2P-wereld (niet PVP/skill total te hoog), bij voorkeur anders dan huidige wereld.
     */
    public static int pickRandomF2pWorldId(Client rl) {
        int avoid = rl != null ? rl.getWorld() : Worlds.getCurrentId();
        if (rl != null && isLoggedInGame(rl)) {
            try {
                Worlds.loadWorlds();
                World picked = Worlds.getRandom(w ->
                        isSafeF2pWorld(w)
                                && w.getId() != avoid
                                && !RECENTLY_REJECTED_WORLD_IDS.contains(w.getId()));
                if (picked != null) {
                    return picked.getId();
                }
            } catch (Throwable t) {
                debug("pickRandomF2pWorldId Storm: " + t.getMessage());
            }
        }
        if (rl != null) {
            World[] list = rl.getWorldList();
            if (list != null && list.length > 0) {
                List<World> candidates = new ArrayList<>();
                for (World w : list) {
                    if (w == null) {
                        continue;
                    }
                    int id = w.getId();
                    if (id <= 0 || id == avoid || RECENTLY_REJECTED_WORLD_IDS.contains(id)) {
                        continue;
                    }
                    if (isSafeF2pWorld(w)) {
                        candidates.add(w);
                    }
                }
                if (!candidates.isEmpty()) {
                    return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size())).getId();
                }
            }
        }
        for (int tries = 0; tries < 12; tries++) {
            int id = F2P_FALLBACK_IDS[ThreadLocalRandom.current().nextInt(F2P_FALLBACK_IDS.length)];
            if (id != avoid && !RECENTLY_REJECTED_WORLD_IDS.contains(id)) {
                return id;
            }
        }
        RECENTLY_REJECTED_WORLD_IDS.clear();
        return F2P_FALLBACK_IDS[0];
    }

    /**
     * Chat: {@code Please choose a different world.} — wereld vol, zelfde wereld, of type beperking.
     */
    public static void onHopGameMessage(String message) {
        if (message == null) {
            return;
        }
        String low = Text.removeTags(message).trim().toLowerCase(Locale.ROOT);
        if (!low.contains("choose a different world")
                && !low.contains("please choose a different")
                && !low.contains("unable to connect to")) {
            return;
        }
        int rejected = lastAttemptedHopWorldId;
        if (rejected > 0) {
            RECENTLY_REJECTED_WORLD_IDS.add(rejected);
            debug("wereld afgewezen door client: w" + rejected + " (" + low + ")");
        }
    }

    public static boolean isWorldRecentlyRejected(int worldId) {
        return worldId > 0 && RECENTLY_REJECTED_WORLD_IDS.contains(worldId);
    }

    /**
     * Klik {@code Switch world} op de OSRS-bevestiging (Esc → World Switcher → Configure kan dit permanent uitzetten).
     *
     * @return true als er geklikt is
     */
    public static boolean tryAcceptWorldHopConfirmation() {
        for (int group : HOP_CONFIRM_INTERFACE_GROUPS) {
            if (!hasVisibleTextInGroup(group, "sure you wish to switch")
                    && !hasVisibleTextInGroup(group, "switch to world")) {
                continue;
            }
            IWidget btn = findClickableWidgetInGroup(group, "switch world");
            if (btn != null && clickHopWidget(btn)) {
                debug("bevestiging: Switch world geklikt (iface " + group + ")");
                return true;
            }
        }
        for (int group : HOP_CONFIRM_INTERFACE_GROUPS) {
            IWidget btn = findClickableWidgetInGroup(group, "switch world");
            if (btn != null && clickHopWidget(btn)) {
                debug("bevestiging: Switch world geklikt (iface " + group + ", geen parent-tekst)");
                return true;
            }
        }
        return false;
    }

    public static boolean isSafeF2pWorld(World w) {
        if (w == null) {
            return false;
        }
        Collection<WorldType> types = w.getTypes();
        if (types == null || types.isEmpty()) {
            int id = w.getId();
            for (int f2p : F2P_FALLBACK_IDS) {
                if (f2p == id) {
                    return true;
                }
            }
            return false;
        }
        if (types.contains(WorldType.MEMBERS)) {
            return false;
        }
        if (WorldType.isPvpWorld(types)) {
            return false;
        }
        if (types.contains(WorldType.SKILL_TOTAL)) {
            return false;
        }
        if (types.contains(WorldType.HIGH_RISK)) {
            return false;
        }
        return !types.contains(WorldType.DEADMAN)
                && !types.contains(WorldType.TOURNAMENT_WORLD)
                && !types.contains(WorldType.BETA_WORLD)
                && !types.contains(WorldType.SEASONAL)
                && !types.contains(WorldType.LAST_MAN_STANDING);
    }

    public static World findWorldById(Client rl, int worldId) {
        if (worldId <= 0) {
            return null;
        }
        if (rl != null && isLoggedInGame(rl)) {
            try {
                Worlds.loadWorlds();
                World storm = Worlds.getFirst(w -> w != null && w.getId() == worldId);
                if (storm != null) {
                    return storm;
                }
            } catch (Throwable ignored) {
            }
        }
        if (rl == null) {
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

    /**
     * Plant wereld-hop (client-thread). Ingame: Storm world hopper; login-scherm: RuneLite API.
     */
    public static boolean scheduleHopToWorld(Client rl, ClientThread clientThread, int worldId) {
        if (rl == null || worldId <= 0) {
            return false;
        }
        if (Game.isLoggedIn() || WelcomeScreenPlayHelper.isWelcomeLobbyPendingPlay()) {
            debug("login hop: uitgesteld — nog ingelogd of welkomst-lobby open");
            return false;
        }
        if (!Game.isOnLoginScreen()) {
            debug("login hop: uitgesteld — isOnLoginScreen=false");
            return false;
        }
        if (isWorldRecentlyRejected(worldId)) {
            worldId = pickRandomF2pWorldId(rl);
        }
        if (isLoggedInGame(rl) && rl.getWorld() == worldId) {
            worldId = pickRandomF2pWorldId(rl);
        }
        if (worldId <= 0) {
            return false;
        }
        lastAttemptedHopWorldId = worldId;
        if (isLoggedInGame(rl)) {
            return scheduleInGameHop(clientThread, worldId);
        }
        return scheduleLoginScreenHop(rl, clientThread, worldId);
    }

    private static boolean isLoggedInGame(Client rl) {
        if (rl == null) {
            return false;
        }
        GameState gs = rl.getGameState();
        return gs == GameState.LOGGED_IN || gs == GameState.HOPPING || gs == GameState.LOADING;
    }

    private static boolean scheduleInGameHop(ClientThread clientThread, int worldId) {
        Runnable doHop = () -> {
            try {
                Worlds.loadWorlds();
                World dest = Worlds.getFirst(w -> w != null && w.getId() == worldId);
                if (dest == null) {
                    debug("ingame hop: w" + worldId + " niet in Storm-lijst");
                    return;
                }
                if (!Worlds.isHopperOpen()) {
                    Worlds.openHopper();
                }
                debug("ingame hop: hopTo w" + worldId + " (hopperOpen=" + Worlds.isHopperOpen() + ")");
                Worlds.hopTo(dest);
                tryAcceptWorldHopConfirmation();
                if (clientThread != null) {
                    clientThread.invokeLater(AccountSwitchWorldHop::tryAcceptWorldHopConfirmation);
                }
            } catch (Throwable t) {
                debug("ingame hop failed w" + worldId + ": " + t.getMessage());
            }
        };
        runOnClientThread(clientThread, doHop);
        return true;
    }

    private static boolean scheduleLoginScreenHop(Client rl, ClientThread clientThread, int worldId) {
        World dest = findWorldById(rl, worldId);
        if (dest == null) {
            debug("login hop: w" + worldId + " niet in clientlijst");
            return false;
        }
        final Client rlFinal = rl;
        final World destFinal = dest;
        final int idFinal = worldId;
        Runnable doHop = () -> {
            try {
                WelcomeScreenPlayHelper.notifyLoginScreenWorldHop();
                debug("login hop: hopToWorld w" + idFinal);
                rlFinal.hopToWorld(destFinal);
            } catch (Throwable hopEx) {
                try {
                    rlFinal.changeWorld(destFinal);
                } catch (Throwable chEx) {
                    try {
                        java.lang.reflect.Method m = rlFinal.getClass().getMethod("hopToWorld", int.class);
                        m.invoke(rlFinal, idFinal);
                    } catch (Throwable ignored) {
                        debug("login hop: alle methodes mislukt w" + idFinal);
                    }
                }
            }
        };
        runOnClientThread(clientThread, doHop);
        return true;
    }

    private static void runOnClientThread(ClientThread clientThread, Runnable task) {
        if (clientThread != null) {
            clientThread.invokeLater(task);
        } else {
            task.run();
        }
    }

    private static boolean hasVisibleTextInGroup(int group, String needle) {
        if (needle == null || needle.isEmpty() || group < 0) {
            return false;
        }
        String lowNeedle = needle.toLowerCase(Locale.ROOT);
        for (int child = 0; child <= 40; child++) {
            IWidget root = safeGetWidget(group, child);
            if (root == null) {
                continue;
            }
            for (IWidget w : flattenWidgets(root)) {
                if (w == null || isWidgetHidden(w)) {
                    continue;
                }
                if (widgetTextContains(w, lowNeedle)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static IWidget findClickableWidgetInGroup(int group, String needle) {
        if (needle == null || needle.isEmpty() || group < 0) {
            return null;
        }
        String lowNeedle = needle.toLowerCase(Locale.ROOT);
        IWidget best = null;
        for (int child = 0; child <= 40; child++) {
            IWidget root = safeGetWidget(group, child);
            if (root == null) {
                continue;
            }
            for (IWidget w : flattenWidgets(root)) {
                if (w == null || isWidgetHidden(w)) {
                    continue;
                }
                if (!widgetTextContains(w, lowNeedle)) {
                    continue;
                }
                if (widgetTextContains(w, "cancel")) {
                    continue;
                }
                best = w;
            }
        }
        return best;
    }

    private static boolean clickHopWidget(IWidget w) {
        if (w == null) {
            return false;
        }
        try {
            if (HumanMouseClickHelper.smoothMoveAndLeftClickWidget(w)) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (w.hasAction("Switch world")) {
                w.interact("Switch world");
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            net.storm.sdk.game.Client.interact(1, MenuAction.CC_OP.getId(), -1, w.getId());
            return true;
        } catch (Throwable ignored) {
        }
        try {
            w.interact(0);
            return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static IWidget safeGetWidget(int group, int child) {
        try {
            return Widgets.get(group, child);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isWidgetHidden(IWidget w) {
        try {
            return w.isHidden();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static boolean widgetTextContains(IWidget w, String lowNeedle) {
        String text = cleanWidgetText(w.getText());
        String name = cleanWidgetText(w.getName());
        String combined = (text + " " + name).toLowerCase(Locale.ROOT);
        return combined.contains(lowNeedle);
    }

    private static String cleanWidgetText(String s) {
        if (s == null) {
            return "";
        }
        return Text.removeTags(s).replace('\n', ' ').trim();
    }

    private static List<IWidget> flattenWidgets(IWidget root) {
        List<IWidget> out = new ArrayList<>();
        Deque<IWidget> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            IWidget w = q.poll();
            if (w == null) {
                continue;
            }
            out.add(w);
            enqueueChildren(q, w.getChildren());
            enqueueChildren(q, w.getDynamicChildren());
            enqueueChildren(q, w.getNestedChildren());
        }
        return out;
    }

    private static void enqueueChildren(Deque<IWidget> q, IWidget[] arr) {
        if (arr == null) {
            return;
        }
        for (IWidget ch : arr) {
            if (ch != null) {
                q.add(ch);
            }
        }
    }
}
