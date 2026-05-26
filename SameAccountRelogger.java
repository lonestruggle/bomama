package com.combatbot;

import net.storm.api.account.GameAccount;
import net.storm.sdk.game.Client;
import net.storm.sdk.game.Game;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Periodiek uit- en weer inloggen met hetzelfde account (re-log).
 * <p><b>Uitloggen</b>: bij timer of {@link #forceLogoutNow()} → {@link State#LOGGING_OUT}, daarna
 * {@link Game#logout()} direct vanuit de loop (zelfde als werkende backup), daarna
 * {@link State#WAITING_LOGOUT} tot login-scherm, optioneel pauze, dan {@link State#LOGGING_IN}.
 * Alleen actief als {@code reLogoutEnabled} en <em>geen</em> multi-account-rotatie tegelijk.
 */
public class SameAccountRelogger {

    private enum State {
        IDLE,
        LOGGING_OUT,
        WAITING_LOGOUT,
        PAUSED_ON_LOGIN,
        LOGGING_IN
    }

    private final CombatBotConfig config;
    private final CombatBotPaint paint;
    private final Random random = new Random();

    private State state = State.IDLE;
    private Instant sessionStart;
    private int nextRelogMinutes;

    private GameAccount gameAccount;
    private boolean isJagexAccount = false;
    private String lastAccountConfig = null;

    private int loginRetries = 0;
    // Jagex login kan soms traag zijn; geef ruim de tijd.
    private static final int MAX_LOGIN_RETRIES = 8;
    private Instant pauseUntil;
    private long lastLogoutAttemptMs;
    private long waitLogoutSinceMs;
    private int logoutRetryWhileLoggedIn;
    private static final int MAX_LOGOUT_RETRY_WHILE_LOGGED_IN = 10;
    private static final long ABORT_LOGOUT_STUCK_AFTER_MS = 90_000L;
    private static final long MIN_MS_BETWEEN_RELOG_LOGOUT_RETRIES = 4500L;
    private static final int MS_LOOP_PAUSE_AFTER_RELOG_LOGOUT = 2200;

    public SameAccountRelogger(CombatBotConfig config, CombatBotPaint paint) {
        this.config = config;
        this.paint = paint;
        resetTimer();
    }

    /** True zolang uit-/inloggen of pauze op login-scherm nog bezig is (niet gewone IDLE-wachttijd). */
    public boolean isRelogFlowActive() {
        return state != State.IDLE;
    }

    /** Alleen IDLE (geen actieve re-log cyclus). */
    public boolean isIdle() {
        return state == State.IDLE;
    }

    /** Panel "Reset": lopende re-log cyclus afbreken en timer opnieuw (als re-log aan staat). */
    public void resetToIdleFromPanel() {
        state = State.IDLE;
        loginRetries = 0;
        pauseUntil = null;
        lastLogoutAttemptMs = 0;
        waitLogoutSinceMs = 0;
        logoutRetryWhileLoggedIn = 0;
        resetTimer();
        updatePaintStatus(config.reLogoutEnabled() && !config.accountSwitchEnabled());
    }

    private void invokeGameLogout() {
        DebugLog.log("ReLog", "Logout: Game.logout()");
        Game.logout();
    }

    private void abortRelogDueToLogoutLoop() {
        debug("abortRelogDueToLogoutLoop: te veel logout-pogingen of timeout");
        state = State.IDLE;
        logoutRetryWhileLoggedIn = 0;
        resetTimer();
        paint.setLastAntiBanAction("⛔ Re-log gestopt: uitloggen lukt niet — log uit via de client of herstart");
    }

    /** Forceer direct een re-log flow: log nu uit en volg de normale timer/pauze+login stappen. */
    public void forceLogoutNow() {
        state = State.LOGGING_OUT;
        loginRetries = 0;
        sessionStart = Instant.now();
        paint.setLastAntiBanAction("🔁 Re-log (force) — uitloggen vanwege geen food");
        debug("forceLogoutNow: state=LOGGING_OUT (trigger via code, bijv. geen food)");
    }

    private void debug(String msg) {
        DebugLog.log("ReLog", msg);
        System.out.println("[ReLog] " + msg);
    }

    /**
     * Hoofdmethode – aanroepen vanuit de plugin loop.
     * Geeft > 0 terug zolang de re-log flow bezig is (plugin moet dan even wachten).
     * Geeft 0 terug als alles idle is en de normale loop door kan gaan.
     */
    public int check() {
        boolean relogAllowed = config.reLogoutEnabled() && !config.accountSwitchEnabled();
        // Bot aan + uitgelogd op login-scherm: start inlog-flow (tenzij multi-account switcher 2+ accounts afhandelt)
        if (state == State.IDLE && config.botEnabled() && Game.isOnLoginScreen() && !Game.isLoggedIn()) {
            if (!config.accountSwitchEnabled()) {
                state = State.LOGGING_IN;
                loginRetries = 0;
                pauseUntil = null;
                paint.setLastAntiBanAction("🔐 Inloggen...");
                return 35 + random.nextInt(40);
            }
        }
        // Alleen volledig overslaan als er geen lopende uit-/inlog-cyclus is. Anders zou de client op het
        // login-scherm blijven hangen (o.a. als de bot tussendoor uit wordt gezet of re-log in config uit).
        if (!relogAllowed && state == State.IDLE) {
            updatePaintStatus(false);
            return 0;
        }
        if (!relogAllowed && state != State.IDLE) {
            debug("check: re-log staat uit in config maar state=" + state + " — flow wordt nog afgemaakt");
        }

        switch (state) {
            case IDLE:
                int idleDelay = checkTimer();
                updatePaintStatus(true);
                return idleDelay;
            case LOGGING_OUT:
                int d1 = handleLogout();
                updatePaintStatus(true);
                return d1;
            case WAITING_LOGOUT:
                int d2 = handleWaitLogout();
                updatePaintStatus(true);
                return d2;
            case PAUSED_ON_LOGIN:
                int d3 = handlePausedOnLogin();
                updatePaintStatus(true);
                return d3;
            case LOGGING_IN:
                int d4 = handleLogin();
                updatePaintStatus(true);
                return d4;
            default:
                updatePaintStatus(true);
                return 0;
        }
    }

    private int checkTimer() {
        if (sessionStart == null) {
            sessionStart = Instant.now();
            resetTimer();
            return 0;
        }

        long elapsedMinutes = Duration.between(sessionStart, Instant.now()).toMinutes();
        if (elapsedMinutes >= nextRelogMinutes) {
            state = State.LOGGING_OUT;
            loginRetries = 0;
            paint.setLastAntiBanAction("🔁 Re-log: uitloggen...");
            debug("Timer klaar → start LOGGING_OUT (nextRelogMinutes=" + nextRelogMinutes + ")");
            return MS_LOOP_PAUSE_AFTER_RELOG_LOGOUT;
        }

        return 0;
    }

    private int handleLogout() {
        debug("handleLogout: Game.logout()");
        logoutRetryWhileLoggedIn = 0;
        invokeGameLogout();
        lastLogoutAttemptMs = System.currentTimeMillis();
        waitLogoutSinceMs = System.currentTimeMillis();
        state = State.WAITING_LOGOUT;
        return MS_LOOP_PAUSE_AFTER_RELOG_LOGOUT;
    }

    private int handleWaitLogout() {
        if (Game.isLoggedIn()) {
            long now = System.currentTimeMillis();
            if (now - waitLogoutSinceMs >= ABORT_LOGOUT_STUCK_AFTER_MS) {
                abortRelogDueToLogoutLoop();
                return 3000;
            }
            if (now - lastLogoutAttemptMs >= MIN_MS_BETWEEN_RELOG_LOGOUT_RETRIES) {
                if (logoutRetryWhileLoggedIn >= MAX_LOGOUT_RETRY_WHILE_LOGGED_IN) {
                    abortRelogDueToLogoutLoop();
                    return 3000;
                }
                logoutRetryWhileLoggedIn++;
                debug("handleWaitLogout: nog ingelogd — opnieuw Game.logout() (" + logoutRetryWhileLoggedIn + "/" + MAX_LOGOUT_RETRY_WHILE_LOGGED_IN + ")");
                invokeGameLogout();
                lastLogoutAttemptMs = now;
                paint.setLastAntiBanAction("🔄 Re-log: opnieuw uitloggen… (" + logoutRetryWhileLoggedIn + "/" + MAX_LOGOUT_RETRY_WHILE_LOGGED_IN + ")");
                return MS_LOOP_PAUSE_AFTER_RELOG_LOGOUT + random.nextInt(400);
            }
            return 850 + random.nextInt(300);
        }
        if (Game.isOnLoginScreen()) {
            // Bepaal hoelang we op het login scherm blijven staan (pauze)
            int minPause = Math.max(0, config.reLogoutPauseMinMinutes());
            int maxPause = Math.max(minPause, config.reLogoutPauseMaxMinutes());
            if (maxPause <= 0) {
                // Geen pauze geconfigureerd → direct inlog-flow starten
                debug("handleWaitLogout: geen pauze ingesteld → direct LOGGING_IN");
                state = State.LOGGING_IN;
                return 150 + random.nextInt(120);
            }
            if (maxPause < minPause) {
                maxPause = minPause;
            }
            int pauseMinutes = (maxPause == minPause)
                    ? minPause
                    : (minPause + random.nextInt(maxPause - minPause + 1));
            long pauseSeconds = Math.max(10, pauseMinutes * 60L);
            pauseUntil = Instant.now().plusSeconds(pauseSeconds);
            state = State.PAUSED_ON_LOGIN;
            paint.setLastAntiBanAction("⏸ Pauze (re-log) ~" + pauseMinutes + " min");
            debug("handleWaitLogout: login screen → PAUSED_ON_LOGIN voor ~" + pauseMinutes + " min");
            return 1200;
        }
        return 650 + random.nextInt(200);
    }

    private int handlePausedOnLogin() {
        if (!Game.isOnLoginScreen()) {
            // Spelstatus veranderd, ga verder met inloggen
            debug("handlePausedOnLogin: niet meer op login screen → LOGGING_IN");
            state = State.LOGGING_IN;
            return 1000;
        }
        if (pauseUntil == null) {
            debug("handlePausedOnLogin: pauseUntil null → LOGGING_IN");
            state = State.LOGGING_IN;
            return 1000;
        }
        if (Instant.now().isAfter(pauseUntil)) {
            state = State.LOGGING_IN;
            paint.setLastAntiBanAction("🔁 Pauze voorbij — opnieuw inloggen");
            debug("handlePausedOnLogin: pauze voorbij → LOGGING_IN");
            return 1000;
        }
        // Nog in pauze
        return 3000;
    }

    /**
     * Eénmalige loginpoging: op het login-scherm account zetten + Play-knop(en) klikken.
     * Te gebruiken vanuit het panel ("Log in"-knop) om uitgelogd te starten en te testen.
     *
     * @return true als er een poging is gedaan (op login scherm + account geladen)
     */
    public boolean tryLoginNow() {
        if (!Game.isOnLoginScreen()) {
            return false;
        }
        if (!ensureAccountLoaded()) {
            return false;
        }
        setLoginMode();
        Game.setGameAccount(gameAccount);
        debug("tryLoginNow: Game.setGameAccount + Play-knop");
        JagexLauncherPlayButton.clickPlayButton();
        paint.setLastAntiBanAction("🔐 Log in geprobeerd");
        return true;
    }

    /** Panel / test: zelfde pad als na Re-log pauze (meerdere Play-pogingen), niet de one-shot tryLoginNow. */
    public void startTestAutoLoginFromLoginScreen() {
        if (!Game.isOnLoginScreen()) {
            paint.setLastAntiBanAction("⚠ Test auto-login: open eerst het login-scherm");
            debug("startTestAutoLoginFromLoginScreen: geen login screen");
            return;
        }
        loginRetries = 0;
        pauseUntil = null;
        state = State.LOGGING_IN;
        paint.setLastAntiBanAction("🔁 Test auto-login gestart (re-log pad)");
        debug("startTestAutoLoginFromLoginScreen: state=LOGGING_IN");
    }

    private int handleLogin() {
        if (!ensureAccountLoaded()) {
            // Geen geldig account – schakel re-log uit tot de user het fixt
            debug("handleLogin: ensureAccountLoaded() faalde, re-log uit tot volgende timer");
            state = State.IDLE;
            resetTimer();
            return 0;
        }

        if (WelcomeScreenPlayHelper.isInGameWorld()) {
            debug("handleLogin: in game world → re-log voltooid, timer reset");
            state = State.IDLE;
            sessionStart = Instant.now();
            resetTimer();
            paint.setLastAntiBanAction("✓ Re-log voltooid");
            return 1000;
        }

        if (WelcomeScreenPlayHelper.isWelcomeLobbyPendingPlay()) {
            int playDelay = WelcomeScreenPlayHelper.advanceWelcomeLobbyClick();
            loginRetries++;
            return playDelay > 0 ? playDelay : 260 + random.nextInt(140);
        }

        if (Game.isOnLoginScreen()) {
            // Eerste poging: account instellen
            if (loginRetries == 0) {
                setLoginMode();
                Game.setGameAccount(gameAccount);
                debug("handleLogin: eerste poging → Game.setGameAccount(...) aangeroepen");
            }

            // Klik op de Play-knop (vaste schermpositie, geen deprecated widget-API)
            if (JagexLauncherPlayButton.clickPlayButton()) {
                debug("handleLogin: Jagex Launcher Play-knop aangeklikt");
            }

            loginRetries++;
            if (loginRetries > MAX_LOGIN_RETRIES) {
                paint.setLastAntiBanAction("⚠ Re-log mislukt, probeer later opnieuw");
                debug("handleLogin: MAX_LOGIN_RETRIES bereikt → stop en reset timer");
                state = State.IDLE;
                resetTimer();
                return 4000;
            }
            return 260 + random.nextInt(140);
        }

        return 160 + random.nextInt(120);
    }

    private void resetTimer() {
        int min = Math.max(1, config.reLogoutMinMinutes());
        int max = Math.max(min + 1, config.reLogoutMaxMinutes());
        if (max <= min) {
            max = min + 1;
        }
        nextRelogMinutes = min + random.nextInt(max - min);
        sessionStart = Instant.now();
    }

    private void updatePaintStatus(boolean enabled) {
        if (!enabled) {
            paint.setRelogInfo(false, 0, 0, false);
            return;
        }
        boolean actuallyEnabled = config.reLogoutEnabled() && !config.accountSwitchEnabled();
        if (!actuallyEnabled) {
            paint.setRelogInfo(false, 0, 0, false);
            return;
        }
        Instant now = Instant.now();
        long secondsUntilLogout = 0;
        long pauseSeconds = 0;
        boolean inPause = false;

        if (state == State.PAUSED_ON_LOGIN && pauseUntil != null) {
            inPause = true;
            pauseSeconds = Math.max(0, Duration.between(now, pauseUntil).getSeconds());
        } else if (sessionStart != null && nextRelogMinutes > 0) {
            long elapsed = Duration.between(sessionStart, now).getSeconds();
            long total = nextRelogMinutes * 60L;
            secondsUntilLogout = Math.max(0, total - elapsed);
        }
        paint.setRelogInfo(true, secondsUntilLogout, pauseSeconds, inPause);
    }

    /**
     * Zorgt dat er een GameAccount klaarstaat op basis van de reLogoutAccount config.
     * Ondersteunt:
     *  - Legacy: email:wachtwoord
     *  - Jagex:  jagex:/pad/naar/credentials.properties
     */
    private boolean ensureAccountLoaded() {
        String cfg = config.reLogoutAccount();
        if (cfg == null || cfg.trim().isEmpty()) {
            JagexCredentialsHelper.ParsedJagexAccount chosen =
                    JagexCredentialsHelper.resolveEnabledJagexAccountForAutoLogin(config);
            if (chosen == null) {
                int enabledCount = JagexCredentialsHelper.listEnabledPastedJagexAccounts(config).size();
                if (enabledCount > 1) {
                    if (config.accountSwitchEnabled()) {
                        paint.setLastAntiBanAction("⚠ Re-log: meerdere accounts — account-switcher gebruiken");
                        debug("ensureAccountLoaded: meerdere enabled Jagex-accounts — account-switcher, geen ReLog");
                    } else {
                        paint.setLastAntiBanAction("⚠ Re-log: meerdere accounts — vul reLogoutAccount in (pasted:Naam)");
                        debug("ensureAccountLoaded: meerdere enabled Jagex-accounts, user moet kiezen");
                    }
                } else {
                    paint.setLastAntiBanAction("⚠ Re-log: geen actieve Jagex account (vink er 1 aan)");
                    debug("ensureAccountLoaded: geen enabled Jagex-account gevonden");
                }
                return false;
            }
            gameAccount = chosen.toGameAccount();
            isJagexAccount = true;
            setLoginMode();
            debug("ensureAccountLoaded: automatisch gekozen Jagex-account '" + chosen.getDisplayName() + "'");
            return true;
        }

        cfg = cfg.trim();
        debug("ensureAccountLoaded: config='" + cfg + "'");
        if (cfg.equals(lastAccountConfig) && gameAccount != null) {
            // Al geladen – alleen loginmodus opnieuw instellen voor de zekerheid
            setLoginMode();
            return true;
        }

        lastAccountConfig = cfg;
        if (cfg.toLowerCase().startsWith("pasted:")) {
            String displayName = cfg.substring(7).trim();
            if (displayName.isEmpty()) {
                paint.setLastAntiBanAction("⚠ Re-log: pasted: zonder display naam");
                debug("ensureAccountLoaded: pasted: maar displayName leeg");
                return false;
            }
            Set<String> enabled = JagexCredentialsHelper.parseEnabledDisplayNames(config.enabledDisplayNames());
            if (!enabled.contains(displayName)) {
                paint.setLastAntiBanAction("⚠ Re-log: " + displayName + " niet aangevinkt in Jagex-lijst");
                debug("ensureAccountLoaded: displayName '" + displayName + "' niet aangevinkt in enabledDisplayNames");
                return false;
            }
            List<JagexCredentialsHelper.ParsedJagexAccount> pasted = JagexCredentialsHelper.parsePastedCredentials(config.pastedCredentials());
            for (JagexCredentialsHelper.ParsedJagexAccount acc : pasted) {
                if (displayName.equals(acc.getDisplayName())) {
                    gameAccount = acc.toGameAccount();
                    isJagexAccount = true;
                    setLoginMode();
                    debug("ensureAccountLoaded: pasted-account gevonden voor '" + displayName + "'");
                    return true;
                }
            }
            paint.setLastAntiBanAction("⚠ Re-log: " + displayName + " niet in geplakte lijst");
            debug("ensureAccountLoaded: displayName '" + displayName + "' niet gevonden in geplakte credentials");
            return false;
        }
        if (cfg.toLowerCase().startsWith("jagex:")) {
            String path = cfg.substring(6).trim();
            GameAccount acc = loadJagexCredentials(path);
            if (acc == null) {
                return false;
            }
            gameAccount = acc;
            isJagexAccount = true;
        } else {
            String[] parts = cfg.split(":", 2);
            if (parts.length != 2) {
                paint.setLastAntiBanAction("⚠ Re-log: account formaat ongeldig");
                return false;
            }
            String username = parts[0].trim();
            String password = parts[1].trim();
            if (username.isEmpty() || password.isEmpty()) {
                paint.setLastAntiBanAction("⚠ Re-log: lege username of password");
                return false;
            }
            gameAccount = new GameAccount(username, password);
            isJagexAccount = false;
        }

        setLoginMode();
        return true;
    }

    private void setLoginMode() {
        if (isJagexAccount) {
            Client.setOAuthLoginMode();
        } else {
            Client.setNormalLoginMode();
        }
    }

    private GameAccount loadJagexCredentials(String path) {
        File file = new File(path);
        if (!file.exists() || !file.canRead()) {
            paint.setLastAntiBanAction("⚠ Re-log: credentials niet gevonden (pad: " + file.getName() + ")");
            return null;
        }
        GameAccount acc = JagexCredentialsHelper.loadFromFile(path);
        if (acc == null) {
            paint.setLastAntiBanAction("⚠ Re-log: ongeldige credentials (mist username/session in bestand?)");
            return null;
        }
        return acc;
    }
}

