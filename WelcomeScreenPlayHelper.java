package com.combatbot;

import net.runelite.api.MenuAction;
import net.runelite.client.util.Text;
import net.storm.api.domain.widgets.IWidget;
import net.storm.sdk.game.Client;
import net.storm.sdk.game.Game;
import net.storm.sdk.script.blocking_events.WelcomeScreenEvent;
import net.storm.sdk.widgets.Widgets;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Klik op de Play-knop op het OSRS-welkomstscherm (iface 378, lobby na Jagex-login).
 * <p>
 * Child {@code 77} = {@code CLICK HERE TO PLAY} (packed id {@code 24772685}) — primaire klik.
 * Child {@code 72} = oudere {@code PLAY}-laag — alleen fallback.
 * Widgets zijn vaak {@code hidden} of {@code null} als de client op de achtergrond staat — dan
 * blind {@code CC_OP} + canvas-fallback ({@link JagexLauncherPlayButton}).
 */
public final class WelcomeScreenPlayHelper {

    public static final String ALIAS_WELCOME_PLAY = "welcomePlayButton";
    public static final String ALIAS_WELCOME_PLAY_TEXT = "welcomePlayButtonText";

    private static final int WELCOME_GROUP = 378;
    /** Storm overlay: iface 378,77 — id 24772685, tekst CLICK HERE TO PLAY. */
    private static final int PLAY_CHILD = 77;
    private static final int PLAY_FALLBACK_CHILD = 72;
    private static final int PLAY_PACKED_ID = (WELCOME_GROUP << 16) | PLAY_CHILD;
    private static final int PLAY_FALLBACK_PACKED_ID = (WELCOME_GROUP << 16) | PLAY_FALLBACK_CHILD;

    private static final long PLAY_CLICK_COOLDOWN_MS = 1_200L;
    /** Welkomst-lobby: tijd tussen CLICK HERE TO PLAY-pogingen (CC_OP kan traag/laden). */
    private static final long WELCOME_LOBBY_PLAY_RETRY_MS = 6_000L;
    private static final long BLIND_PLAY_COOLDOWN_MS = 1_200L;
    private static final long PLAY_NOT_FOUND_LOG_COOLDOWN_MS = 30_000L;
    /** Kort: iface 378 is meestal direct klikbaar zodra CLICK HERE TO PLAY zichtbaar is. */
    private static final long WELCOME_LOBBY_STABLE_MS = 350L;
    private static final long PLAY_WAIT_LOG_THROTTLE_MS = 8_000L;
    /** Geen canvas-fallback vlak na widget-klik zolang lobby nog open is. */
    private static final long CANVAS_FALLBACK_AFTER_WIDGET_MS = 5_000L;
    /** Na Jagex Play Now: kort wachten op lobby-load (niet 10s — blokkeerde CLICK HERE TO PLAY). */
    private static final long POST_JAGEX_PLAY_SETTLE_MS = 2_000L;
    private static final long WELCOME_LOBBY_STABLE_AFTER_JAGEX_PLAY_MS = 500L;
    private static long lastJagexLoginScreenPlayMs;
    private static long lastPlayClickAttemptMs;
    private static long lastBlindPlayAttemptMs;
    private static long lastPlayNotFoundLogMs;
    private static long lastPlayWaitLogMs;
    private static long welcomeLobbyVisibleSinceMs;
    private static long lastWidgetPlayAttemptMs;
    private static int welcomeWidgetClickAttempts;

    static {
        WidgetRegistry.registerIface(ALIAS_WELCOME_PLAY, WELCOME_GROUP, PLAY_CHILD, "CLICK HERE TO PLAY");
        WidgetRegistry.registerPacked(ALIAS_WELCOME_PLAY, PLAY_PACKED_ID, "CLICK HERE TO PLAY");
        WidgetRegistry.registerIface(ALIAS_WELCOME_PLAY_TEXT, WELCOME_GROUP, PLAY_CHILD,
                "CLICK HERE TO PLAY");
        WidgetRegistry.registerIface("welcomePlayButtonFallback", WELCOME_GROUP, PLAY_FALLBACK_CHILD, "Play");
        WidgetRegistry.registerPacked("welcomePlayButtonFallback", PLAY_FALLBACK_PACKED_ID, "Play");
    }

    private WelcomeScreenPlayHelper() {
    }

    /**
     * Fase voor login/welkomst — combineert {@link Game#getState()} met widget-checks.
     * {@code LOGGED_IN} alleen is onbetrouwbaar: welkomst-lobby gebruikt ook {@code LOGGED_IN}.
     */
    public enum LoginPhase {
        /** In game world — geen Play-klik. */
        IN_GAME,
        /** LOGGED_IN + iface 378 CLICK HERE TO PLAY — Play-klik nodig. */
        WELCOME_LOBBY,
        /** Jagex/login-scherm vóór welkomst. */
        LOGIN_SCREEN,
        /** LOADING / HOPPING — even wachten. */
        LOADING,
        UNKNOWN
    }

    /** Storm {@code Game.getState()} als string (bijv. {@code LOGGED_IN}, {@code LOGIN_SCREEN}). */
    public static String stormGameStateName() {
        try {
            Object state = Game.class.getMethod("getState").invoke(null);
            return state != null ? state.toString() : "null";
        } catch (NoSuchMethodException ignored) {
            return "n/a";
        } catch (Throwable t) {
            return "err:" + t.getClass().getSimpleName();
        }
    }

    /** Na login-scherm {@link AccountSwitchWorldHop} — reset stabilisatie-timer. */
    public static void notifyLoginScreenWorldHop() {
        welcomeLobbyVisibleSinceMs = 0L;
        welcomeWidgetClickAttempts = 0;
        lastWidgetPlayAttemptMs = 0L;
    }

    /** Na canvas-klik op grijze Play Now (Jagex account-picker). */
    public static void notifyJagexLoginScreenPlayClicked() {
        lastJagexLoginScreenPlayMs = System.currentTimeMillis();
        welcomeLobbyVisibleSinceMs = 0L;
        welcomeWidgetClickAttempts = 0;
        lastWidgetPlayAttemptMs = 0L;
        lastPlayClickAttemptMs = System.currentTimeMillis();
    }

    /** Client laadt welkomst-lobby na Play Now — geen extra login-scherm-klikken. */
    public static boolean isPostJagexLoginScreenPlaySettling() {
        return lastJagexLoginScreenPlayMs > 0L
                && System.currentTimeMillis() - lastJagexLoginScreenPlayMs < POST_JAGEX_PLAY_SETTLE_MS;
    }

    private static long welcomeLobbyStableRequiredMs() {
        return isPostJagexLoginScreenPlaySettling()
                ? WELCOME_LOBBY_STABLE_AFTER_JAGEX_PLAY_MS
                : WELCOME_LOBBY_STABLE_MS;
    }

    /** Houd lobby-stabiliteit bij (aanroepen vóór Play-klik). */
    public static void tickWelcomeLobbyTracking() {
        if (isWelcomeLobbyPendingPlay()) {
            if (welcomeLobbyVisibleSinceMs == 0L) {
                welcomeLobbyVisibleSinceMs = System.currentTimeMillis();
            }
            return;
        }
        LoginPhase phase = resolveLoginPhase();
        if (phase == LoginPhase.LOADING) {
            return;
        }
        if (phase != LoginPhase.WELCOME_LOBBY) {
            welcomeLobbyVisibleSinceMs = 0L;
            welcomeWidgetClickAttempts = 0;
        }
    }

    /** Welkomst-lobby zichtbaar lang genoeg + CLICK HERE TO PLAY-widget bruikbaar. */
    public static boolean isWelcomeLobbyReadyToClick() {
        tickWelcomeLobbyTracking();
        if (!isWelcomeLobbyPendingPlay()) {
            return false;
        }
        IWidget play = resolvePlayButtonWidget();
        if (play == null || !isUsablePlayWidget(play) || !isWidgetVisibleForLogin(play)) {
            return false;
        }
        if (welcomeLobbyVisibleSinceMs <= 0L) {
            welcomeLobbyVisibleSinceMs = System.currentTimeMillis();
            return false;
        }
        return System.currentTimeMillis() - welcomeLobbyVisibleSinceMs >= welcomeLobbyStableRequiredMs();
    }

    /**
     * Welkomst-lobby: muisklik op 378,77, wacht op overgang. Geen canvas-fallback (valt vaak mis).
     *
     * @return delay ms (&gt;0 wachten), 0 = klaar (in game world of geen lobby meer)
     */
    public static int advanceWelcomeLobbyClick() {
        tickWelcomeLobbyTracking();
        if (!isWelcomeLobbyPendingPlay()) {
            welcomeWidgetClickAttempts = 0;
            return 0;
        }
        if (!isWelcomeLobbyReadyToClick()) {
            long now = System.currentTimeMillis();
            if (now - lastPlayWaitLogMs >= PLAY_WAIT_LOG_THROTTLE_MS) {
                lastPlayWaitLogMs = now;
                DebugLog.log("Login", "Play wacht: welkomst-lobby stabiliseert ("
                        + (welcomeLobbyVisibleSinceMs > 0
                        ? (now - welcomeLobbyVisibleSinceMs) + "ms" : "nog geen iface") + ")");
            }
            return 250 + (int) (Math.random() * 200);
        }
        long now = System.currentTimeMillis();
        if (now - lastPlayClickAttemptMs < WELCOME_LOBBY_PLAY_RETRY_MS) {
            int remain = (int) (WELCOME_LOBBY_PLAY_RETRY_MS - (now - lastPlayClickAttemptMs));
            return Math.max(400, remain);
        }
        lastPlayClickAttemptMs = now;
        tryFocusGameWindow();
        if (clickWelcomePlayWidget()) {
            lastWidgetPlayAttemptMs = now;
            welcomeWidgetClickAttempts++;
            if (!isWelcomeLobbyPendingPlay()) {
                DebugLog.log("Login", "[OK] Welkomst-lobby: CLICK HERE TO PLAY — in game world");
                welcomeWidgetClickAttempts = 0;
                return 0;
            }
            DebugLog.log("Login", "Welkomst-lobby: Play-klik (" + welcomeWidgetClickAttempts
                    + "), wacht " + (WELCOME_LOBBY_PLAY_RETRY_MS / 1000) + "s op overgang…");
            return (int) WELCOME_LOBBY_PLAY_RETRY_MS + (int) (Math.random() * 300);
        }
        logPlayNotFoundThrottled();
        return 450;
    }

    /** Canvas-fallback alleen als widget-pad faalde en lobby al lang zichtbaar is. */
    public static boolean mayUseCanvasPlayFallback() {
        if (!isWelcomeLobbyPendingPlay() || !isWelcomeLobbyReadyToClick()) {
            return false;
        }
        if (welcomeWidgetClickAttempts < 2) {
            return false;
        }
        long sinceWidget = System.currentTimeMillis() - lastWidgetPlayAttemptMs;
        return lastWidgetPlayAttemptMs > 0 && sinceWidget >= CANVAS_FALLBACK_AFTER_WIDGET_MS;
    }

    public static LoginPhase resolveLoginPhase() {
        try {
            if (WelcomeScreenEvent.isWelcomeScreenOpen()) {
                return LoginPhase.WELCOME_LOBBY;
            }
        } catch (Throwable ignored) {
        }
        if (isWelcomeLobbyPendingPlay()) {
            return LoginPhase.WELCOME_LOBBY;
        }
        String gs = stormGameStateName();
        if ("LOADING".equals(gs) || "HOPPING".equals(gs)) {
            return LoginPhase.LOADING;
        }
        if ("LOGIN_SCREEN".equals(gs) || "LOGIN_SCREEN_AUTHENTICATOR".equals(gs)) {
            return LoginPhase.LOGIN_SCREEN;
        }
        if (Game.isOnLoginScreen()) {
            return LoginPhase.LOGIN_SCREEN;
        }
        if ("LOGGED_IN".equals(gs) || Game.isLoggedIn()) {
            return LoginPhase.IN_GAME;
        }
        return LoginPhase.UNKNOWN;
    }

    /** Echt in de game world — niet welkomst-lobby, login-scherm of laden. */
    public static boolean isInGameWorld() {
        if (isWelcomeLobbyPendingPlay()) {
            return false;
        }
        try {
            if (WelcomeScreenEvent.isWelcomeScreenOpen()) {
                return false;
            }
        } catch (Throwable ignored) {
        }
        return resolveLoginPhase() == LoginPhase.IN_GAME;
    }

    /**
     * Lobby na Jagex-login: {@code Game.isLoggedIn()} / {@code LOGGED_IN} maar iface 378
     * {@code CLICK HERE TO PLAY} nog zichtbaar — moet nog één keer Play geklikt worden.
     */
    public static boolean isWelcomeLobbyPendingPlay() {
        try {
            if (WelcomeScreenEvent.isWelcomeScreenOpen()) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        if (!Game.isLoggedIn()) {
            return false;
        }
        if (isPlayButtonVisible()) {
            return true;
        }
        IWidget w77 = safeGetWidget(WELCOME_GROUP, PLAY_CHILD);
        if (w77 == null || !isWidgetVisibleForLogin(w77)) {
            return false;
        }
        String t = widgetCombinedText(w77);
        return t.contains("click here to play") || t.contains("play now");
    }

    /**
     * Widget-pad: SDK → 378,77 → 378,72 fallback → blind CC_OP.
     *
     * @return true als een interactie is geprobeerd (niet per se dat het scherm al weg is)
     */
    public static boolean tryClickPlay() {
        LoginPhase phase = resolveLoginPhase();
        if (phase == LoginPhase.WELCOME_LOBBY) {
            return advanceWelcomeLobbyClick() > 0;
        }
        if (phase != LoginPhase.LOGIN_SCREEN) {
            return false;
        }
        if (!shouldAttemptPlayClick()) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - lastPlayClickAttemptMs < PLAY_CLICK_COOLDOWN_MS) {
            return false;
        }
        lastPlayClickAttemptMs = now;
        tryFocusGameWindow();
        IWidget play = findPlayButtonWidget();
        if (play != null && clickWelcomePlayWidget()) {
            return true;
        }
        // Jagex login-scherm heeft geen 378,77 — blind CC_OP veroorzaakt alleen ruis in de log.
        if (Game.isOnLoginScreen() && !Game.isLoggedIn()) {
            return false;
        }
        return tryBlindPlayButtonInteract();
    }

    /**
     * Debug-tab test: log game-state + probeer één Play-klik (378,77).
     */
    public static void debugTestClickPlay() {
        debugLogLoginState();
        LoginPhase phase = resolveLoginPhase();
        if (phase == LoginPhase.IN_GAME || phase == LoginPhase.LOADING) {
            DebugLog.log("Login", "Test Play: fase=" + phase + " (Game.getState()="
                    + stormGameStateName() + ") — geen klik");
            return;
        }
        lastPlayClickAttemptMs = 0L;
        lastBlindPlayAttemptMs = 0L;
        welcomeLobbyVisibleSinceMs = 0L;
        welcomeWidgetClickAttempts = 0;
        lastWidgetPlayAttemptMs = 0L;
        boolean clicked = tryClickPlay();
        DebugLog.log("Login", "Test Play: tryClickPlay=" + clicked);
        debugLogLoginState();
    }

    /** Logt Storm/RL login-state naar Debug-console (bron Login). */
    public static void debugLogLoginState() {
        LoginPhase phase = resolveLoginPhase();
        StringBuilder sb = new StringBuilder();
        sb.append("loginPhase=").append(phase);
        sb.append(" | Game.getState()=").append(stormGameStateName());
        sb.append(" | isLoggedIn=").append(safeCallBool(WelcomeScreenPlayHelper::safeIsLoggedIn));
        sb.append(" | isOnLoginScreen=").append(safeCallBool(() -> Game.isOnLoginScreen()));
        try {
            sb.append(" | welcomeOpen=").append(WelcomeScreenEvent.isWelcomeScreenOpen());
        } catch (Throwable t) {
            sb.append(" | welcomeOpen=err:").append(t.getClass().getSimpleName());
        }
        sb.append(" | playVisible=").append(isPlayButtonVisible());
        sb.append(" | welcomeLobby=").append(isWelcomeLobbyPendingPlay());
        sb.append(" | shouldClick=").append(shouldAttemptPlayClick());

        String loginHint = JagexCredentialsHelper.loginScreenDisplayNameHint();
        if (loginHint != null && !loginHint.isEmpty()) {
            sb.append(" | loginHint=").append(loginHint);
        }

        CombatBotPlugin plugin = CombatBotRuntime.getActivePlugin();
        if (plugin != null) {
            sb.append(" | RL.getGameState()=").append(plugin.getClientGameStateLabel());
        }

        IWidget w77 = safeGetWidget(WELCOME_GROUP, PLAY_CHILD);
        IWidget w72 = safeGetWidget(WELCOME_GROUP, PLAY_FALLBACK_CHILD);
        sb.append(" | w378,77=").append(describeWidget(w77));
        sb.append(" | w378,72=").append(describeWidget(w72));

        DebugLog.log("Login", sb.toString());
    }

    /** Welkomst-lobby of login-scherm met CLICK HERE TO PLAY — gestuurd door {@link #resolveLoginPhase()}. */
    public static boolean shouldAttemptPlayClick() {
        LoginPhase phase = resolveLoginPhase();
        if (phase == LoginPhase.WELCOME_LOBBY) {
            return true;
        }
        if (phase == LoginPhase.LOGIN_SCREEN) {
            return Game.isOnLoginScreen() && !Game.isLoggedIn() || isPlayButtonVisible();
        }
        return false;
    }

    /** Nog niet ingelogd vóór welcome-iface geladen (Jagex launcher / account picker). */
    public static boolean isPreWelcomeLoginPhase() {
        try {
            if (Game.isLoggedIn()) {
                return false;
            }
            return !WelcomeScreenEvent.isWelcomeScreenOpen();
        } catch (Throwable ignored) {
            return !Game.isLoggedIn();
        }
    }

    public static boolean isWelcomeOrLoginUi() {
        try {
            if (WelcomeScreenEvent.isWelcomeScreenOpen()) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (Game.isOnLoginScreen()) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return isPlayButtonVisible();
    }

    public static boolean isPlayButtonVisible() {
        IWidget play = resolvePlayButtonWidget();
        if (play == null) {
            return false;
        }
        return isWidgetVisibleForLogin(play);
    }

    /**
     * CC_OP op 378,77 (CLICK HERE TO PLAY) zonder dat de widget zichtbaar hoeft te zijn.
     */
    public static boolean tryBlindPlayButtonInteract() {
        LoginPhase phase = resolveLoginPhase();
        if (phase != LoginPhase.WELCOME_LOBBY && phase != LoginPhase.LOGIN_SCREEN) {
            return false;
        }
        if (!shouldAttemptPlayClick()) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - lastBlindPlayAttemptMs < BLIND_PLAY_COOLDOWN_MS) {
            return false;
        }
        lastBlindPlayAttemptMs = now;
        try {
            Client.interact(1, MenuAction.CC_OP.getId(), -1, PLAY_PACKED_ID);
            DebugLog.log("Login", "Play blind CC_OP (378,77 id=" + PLAY_PACKED_ID + ")");
            return true;
        } catch (Throwable ignored) {
        }
        try {
            Client.interact(1, MenuAction.CC_OP.getId(), -1, PLAY_FALLBACK_PACKED_ID);
            DebugLog.log("Login", "Play blind CC_OP fallback (378,72 id=" + PLAY_FALLBACK_PACKED_ID + ")");
            return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** Breng RuneLite/game-venster naar voren vóór pixel-klik (achtergrond-fix). */
    public static void tryFocusGameWindow() {
        try {
            java.awt.Canvas canvas = Client.getCanvas();
            if (canvas == null) {
                return;
            }
            java.awt.Window win = javax.swing.SwingUtilities.getWindowAncestor(canvas);
            if (win != null) {
                win.toFront();
                win.requestFocus();
            }
        } catch (Throwable ignored) {
        }
    }

    private static IWidget findPlayButtonWidget() {
        try {
            if (WelcomeScreenEvent.isWelcomeScreenOpen()) {
                IWidget sdk = WelcomeScreenEvent.getPlayButton();
                if (isUsablePlayWidget(sdk)) {
                    return sdk;
                }
            }
        } catch (Throwable ignored) {
        }

        IWidget play = resolvePlayButtonWidget();
        if (play != null) {
            return play;
        }

        try {
            IWidget sdk = WelcomeScreenEvent.getPlayButton();
            if (isUsablePlayWidget(sdk)) {
                return sdk;
            }
        } catch (Throwable ignored) {
        }

        return scanWelcomeInterfaceForPlayButton();
    }

    private static IWidget resolvePlayButtonWidget() {
        IWidget play = WidgetRegistry.get(ALIAS_WELCOME_PLAY);
        if (isUsablePlayWidget(play)) {
            return play;
        }
        play = safeGetWidget(WELCOME_GROUP, PLAY_CHILD);
        if (isUsablePlayWidget(play)) {
            return play;
        }
        play = safeGetWidget(PLAY_PACKED_ID);
        if (isUsablePlayWidget(play)) {
            return play;
        }
        play = safeGetWidget(WELCOME_GROUP, PLAY_FALLBACK_CHILD);
        if (isUsablePlayWidget(play)) {
            return play;
        }
        play = safeGetWidget(PLAY_FALLBACK_PACKED_ID);
        return isUsablePlayWidget(play) ? play : null;
    }

    private static IWidget scanWelcomeInterfaceForPlayButton() {
        for (int rootChild = 0; rootChild <= 4; rootChild++) {
            IWidget root = safeGetWidget(WELCOME_GROUP, rootChild);
            if (root == null) {
                continue;
            }
            IWidget best = null;
            int bestScore = -1;
            for (IWidget w : flattenWidgets(root)) {
                int score = playWidgetScore(w);
                if (score > bestScore) {
                    bestScore = score;
                    best = w;
                }
            }
            if (bestScore > 0) {
                return best;
            }
        }
        return null;
    }

    private static int playWidgetScore(IWidget w) {
        if (!isUsablePlayWidget(w)) {
            return -1;
        }
        int score = 0;
        try {
            if (w.getId() == PLAY_PACKED_ID || childIndex(w) == PLAY_CHILD) {
                score += 100;
            }
            if (w.getId() == PLAY_FALLBACK_PACKED_ID || childIndex(w) == PLAY_FALLBACK_CHILD) {
                score += 60;
            }
        } catch (Throwable ignored) {
        }
        if (widgetHasPlayAction(w)) {
            score += 80;
        }
        String combined = widgetCombinedText(w);
        if (combined.contains("click here to play") || combined.contains("play now")) {
            score += 40;
        }
        if (combined.contains("play") && !combined.contains("player")) {
            score += 10;
        }
        try {
            java.awt.Rectangle b = w.getBounds();
            if (b != null && b.width >= 40 && b.height >= 20) {
                score += 5;
            }
        } catch (Throwable ignored) {
        }
        return score;
    }

    private static int childIndex(IWidget w) {
        try {
            return w.getId() & 0xFFFF;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static boolean isWidgetVisibleForLogin(IWidget w) {
        if (w == null) {
            return false;
        }
        if (!Game.isLoggedIn()) {
            return true;
        }
        try {
            return !w.isHidden();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static boolean isUsablePlayWidget(IWidget w) {
        if (w == null) {
            return false;
        }
        if (childIndex(w) == PLAY_FALLBACK_CHILD && !widgetHasPlayAction(w)
                && !widgetCombinedText(w).contains("click here to play")) {
            return false;
        }
        boolean loginPath = !Game.isLoggedIn();
        if (!loginPath) {
            try {
                if (w.isHidden()) {
                    return false;
                }
            } catch (Throwable ignored) {
                return false;
            }
        }
        if (widgetHasPlayAction(w)) {
            return true;
        }
        try {
            if (w.getId() == PLAY_PACKED_ID || childIndex(w) == PLAY_CHILD
                    || w.getId() == PLAY_FALLBACK_PACKED_ID || childIndex(w) == PLAY_FALLBACK_CHILD) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        String combined = widgetCombinedText(w);
        return combined.contains("click here to play") || combined.contains("play now");
    }

    private static boolean widgetHasPlayAction(IWidget w) {
        try {
            if (w.hasAction("Play")) {
                return true;
            }
            String[] actions = w.getActions();
            if (actions != null) {
                for (String a : actions) {
                    if (a != null && a.equalsIgnoreCase("Play")) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** Welkomst-lobby: echte muisklik eerst (CC_OP alleen logt vaak zonder overgang). */
    private static boolean clickWelcomePlayWidget() {
        IWidget w77 = safeGetWidget(WELCOME_GROUP, PLAY_CHILD);
        if (isUsablePlayWidget(w77) && clickWelcomeLobbyPlayWidget(w77)) {
            return true;
        }
        IWidget play = findPlayButtonWidget();
        if (play == null) {
            return tryBlindPlayButtonInteract();
        }
        if (childIndex(play) == PLAY_FALLBACK_CHILD) {
            return false;
        }
        return clickWelcomeLobbyPlayWidget(play);
    }

    private static boolean clickWelcomeLobbyPlayWidget(IWidget play) {
        if (play == null) {
            return false;
        }
        int id = safeWidgetId(play);
        int child = childIndex(play);

        try {
            if (HumanMouseClickHelper.smoothMoveAndLeftClickWidget(play)) {
                DebugLog.log("Login", "Play geklikt (widget-muis id=" + id + " child=" + child + ")");
                return true;
            }
        } catch (Throwable ignored) {
        }

        try {
            if (play.hasAction("Play")) {
                play.interact("Play");
                DebugLog.log("Login", "Play geklikt (interact Play id=" + id + " child=" + child + ")");
                return true;
            }
        } catch (Throwable ignored) {
        }

        try {
            play.interact(0);
            DebugLog.log("Login", "Play geklikt (interact 0 id=" + id + " child=" + child + ")");
            return true;
        } catch (Throwable ignored) {
        }

        try {
            Client.interact(1, MenuAction.CC_OP.getId(), -1, play.getId());
            DebugLog.log("Login", "Play geklikt (CC_OP id=" + id + " child=" + child + ")");
            return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean clickPlayWidget(IWidget play) {
        if (play == null) {
            return false;
        }
        int id = safeWidgetId(play);
        int child = childIndex(play);

        try {
            if (play.hasAction("Play")) {
                play.interact("Play");
                DebugLog.log("Login", "Play geklikt (interact Play id=" + id + " child=" + child + ")");
                return true;
            }
        } catch (Throwable ignored) {
        }

        try {
            Client.interact(1, MenuAction.CC_OP.getId(), -1, play.getId());
            DebugLog.log("Login", "Play geklikt (CC_OP id=" + id + " child=" + child + ")");
            return true;
        } catch (Throwable ignored) {
        }

        try {
            play.interact(0);
            DebugLog.log("Login", "Play geklikt (interact 0 id=" + id + " child=" + child + ")");
            return true;
        } catch (Throwable ignored) {
        }

        try {
            if (HumanMouseClickHelper.smoothMoveAndLeftClickWidget(play)) {
                DebugLog.log("Login", "Play geklikt (widget-muis id=" + id + " child=" + child + ")");
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static void logPlayNotFoundThrottled() {
        long now = System.currentTimeMillis();
        if (now - lastPlayNotFoundLogMs < PLAY_NOT_FOUND_LOG_COOLDOWN_MS) {
            return;
        }
        lastPlayNotFoundLogMs = now;
        DebugLog.log("Login", "Play-knop niet zichtbaar — blind CC_OP/canvas; focus client indien vast");
    }

    private static IWidget safeGetWidget(int group, int child) {
        try {
            return Widgets.get(group, child);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static IWidget safeGetWidget(int packedId) {
        try {
            return Widgets.get(packedId);
        } catch (Throwable ignored) {
            return null;
        }
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

    private static String widgetCombinedText(IWidget w) {
        return (cleanWidgetString(w.getText()) + " " + cleanWidgetString(w.getName()))
                .toLowerCase(Locale.ROOT);
    }

    private static String cleanWidgetString(String s) {
        if (s == null) {
            return "";
        }
        return Text.removeTags(s).replace('\n', ' ').trim();
    }

    private static int safeWidgetId(IWidget w) {
        try {
            return w.getId();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static boolean safeIsLoggedIn() {
        return Game.isLoggedIn();
    }

    private static boolean safeCallBool(java.util.function.BooleanSupplier supplier) {
        try {
            return supplier.getAsBoolean();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String describeWidget(IWidget w) {
        if (w == null) {
            return "null";
        }
        String text = cleanWidgetString(w.getText());
        if (text.isEmpty()) {
            text = cleanWidgetString(w.getName());
        }
        return "id=" + safeWidgetId(w) + " child=" + childIndex(w)
                + " text=\"" + text + "\" hidden=" + safeHidden(w);
    }

    private static boolean safeHidden(IWidget w) {
        try {
            return w.isHidden();
        } catch (Throwable ignored) {
            return false;
        }
    }
}
