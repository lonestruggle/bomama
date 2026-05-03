package com.combatbot;

import net.storm.api.account.GameAccount;
import net.storm.api.domain.widgets.IWidget;
import net.storm.sdk.game.Client;
import net.storm.sdk.game.Game;
import net.storm.sdk.widgets.Widgets;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.ToIntFunction;

/**
 * Beheert tijdgebonden account rotatie.
 * <p><b>Uitloggen (kort)</b>: timer of handmatige wissel zet {@link SwitchState#LOGGING_OUT}.
 * {@link #invokeGameLogout()}: eerst {@link Game#logout()}, optioneel widget-fallback (tab/knop), daarna nog een {@code Game.logout()}.
 * Daarna {@link SwitchState#WAITING_LOGOUT}
 * tot {@link Game#isOnLoginScreen()} en credentials voor het volgende account in
 * {@link SwitchState#SETTING_ACCOUNT} / {@link SwitchState#HOP_WORLD} / {@link SwitchState#LOGGING_IN}.
 * <p>
 * Ondersteunt zowel legacy accounts (email:password) als Jagex accounts
 * via credentials.properties bestanden.
 *
 * Config formaat accountList:
 *   - Legacy:  email:password
 *   - Jagex:   jagex:/pad/naar/credentials.properties
 *   Gescheiden door | of newline.
 *
 * Voorbeeld: jagex:C:/Users/user/.runelite/profiles/acc1/credentials.properties|jagex:C:/Users/user/.runelite/profiles/acc2/credentials.properties
 */
public class AccountSwitcher {

    public enum SwitchState {
        IDLE,           // Normaal actief op huidig account
        LOGGING_OUT,    // Bezig met uitloggen
        WAITING_LOGOUT, // Wacht tot login screen verschijnt
        HOP_WORLD,      // Optioneel: andere wereld vóór credentials (login-scherm)
        SETTING_ACCOUNT,// Account credentials instellen
        LOGGING_IN,     // Wacht tot ingelogd
        COOLDOWN        // Korte pauze na inloggen
    }

    /**
     * Wrapper die zowel legacy als Jagex accounts ondersteunt.
     */
    private static class AccountEntry {
        final GameAccount gameAccount;
        final boolean isJagex;
        final String label;         // Weergavenaam
        final String credentialsPath; // Pad naar credentials.properties (alleen Jagex)

        // Legacy account
        AccountEntry(String username, String password) {
            this.gameAccount = new GameAccount(username, password);
            this.isJagex = false;
            this.label = maskEmail(username);
            this.credentialsPath = null;
        }

        // Jagex account uit credentials file
        AccountEntry(GameAccount account, String path, String displayName) {
            this.gameAccount = account;
            this.isJagex = true;
            this.label = displayName != null && !displayName.isEmpty()
                    ? displayName
                    : "Jagex-" + new File(path).getParentFile().getName();
            this.credentialsPath = path;
        }

        // Jagex account uit geplakte credentials (geen path)
        AccountEntry(GameAccount account, String displayName) {
            this.gameAccount = account;
            this.isJagex = true;
            this.label = displayName != null && !displayName.isEmpty() ? displayName : "Jagex";
            this.credentialsPath = null;
        }

        private static String maskEmail(String email) {
            if (email.contains("@")) {
                int at = email.indexOf("@");
                return email.substring(0, Math.min(3, at)) + "***" + email.substring(at);
            }
            return email.substring(0, Math.min(3, email.length())) + "***";
        }
    }

    private final CombatBotConfig config;
    private final CombatBotPaint paint;
    private final Random random = new Random();

    private List<AccountEntry> accounts = new ArrayList<>();
    private int currentAccountIndex = 0;
    private SwitchState state = SwitchState.IDLE;

    // Timer
    private Instant accountStartTime;
    private int nextSwitchMinutes;

    // Cooldown na login
    private Instant cooldownStart;
    /** DEBUG tijdelijk verkort (was 10): minder wachten tussen wissels om te testen wat vastloopt. */
    private static final int COOLDOWN_SECONDS = 2;

    /**
     * DEBUG: geen {@link Client#setNormalLoginMode()} / {@link Game#setGameAccount} bij legacy (email:wachtwoord) tijdens rotatie.
     * Aan = alleen nuttig als je uitsluitend Jagex-entries draait; legacy-wissel faalt dan bewust. Zet op {@code false} voor productie.
     */
    private static final boolean DEBUG_SKIP_LEGACY_SWITCH_CREDENTIALS = true;

    // Retry tracking
    private int loginRetries = 0;
    private static final int MAX_LOGIN_RETRIES = 3;

    /** Cold start op login-scherm: SETTING_ACCOUNT zonder naar het volgende account te springen. */
    private boolean settingAccountWithoutAdvance;

    /**
     * Alleen voor fallback {@link #stormAccountListSingleWithoutManagedVink()}: na eerste geslaagde login volgende keren
     * geen credentials. Bij precies 1x Rotatie-vink op Accounts-tab gebeurt nooit credential-sync (geen prime nodig).
     */
    private boolean singleAccountCredentialsPrimed;

    /** Na succesvol wisselen: cooldown voorbij → IDLE (label = huidige account display name). */
    private java.util.function.Consumer<String> onRotationAccountReady;

    /**
     * Als dit true teruggeeft: volledige Storm-login zoals Accounts-tab dubbelklik
     * (profiel + Client.setCharacterId/setSessionId/…); anders alleen GameAccount + bestandssync.
     * Tweede argument: optioneel {@code jagex:}-pad van deze entry (anders null bij alleen geplakte credentials).
     */
    private BiPredicate<String, String> managedJagexLoginPreparer;

    /** Optioneel: {@link net.runelite.client.callback.ClientThread#invoke} zodat profiel-sync niet op een worker-thread draait. */
    private Consumer<Runnable> clientSyncExecutor;

    /**
     * Uitloggen op de client thread — bij voorkeur {@link net.runelite.client.callback.ClientThread#invoke}
     * (blocking vanaf de script-loop) zodat {@link Game#logout()} op dezelfde manier draait als bij re-log;
     * {@code invokeLater} gaf bij sommige clients geen effect meer bij de 2e rotatie.
     */
    private Consumer<Runnable> clientLogoutInvoker;

    /**
     * Zelfde veiligheid als panel-uitloggen: geen {@link Game#logout()} tijdens NPC-combat / speler-combat-interactie
     * of hangende combat-loot (anders blijft de client ingelogd en tellen we geen retry).
     */
    private BooleanSupplier accountRotationLogoutGate;

    private long lastLogoutWaitMessageMs;
    private long waitLogoutSinceMs;
    /** Tijdstip laatste {@link Game#logout()} — voor herhaalde logout in {@link #handleWaitLogout()}. */
    private long lastLogoutAttemptMs;

    /** Telt herhaalde logout-pogingen in {@link SwitchState#WAITING_LOGOUT} zolang {@link Game#isLoggedIn()} true blijft. */
    private int logoutRetryWhileLoggedIn;
    private static final int MAX_LOGOUT_RETRY_WHILE_LOGGED_IN = 10;
    /** Na deze tijd nog ingelogd tijdens wachten → geen eindeloze loop, wissel afbreken. */
    private static final long ABORT_LOGOUT_STUCK_AFTER_MS = 90_000L;
    /**
     * Uitgelogd maar {@link Game#isOnLoginScreen()} nog false (LOADING/tussenscherm): hoelang wachten voordat we toch
     * naar credentials/wereld gaan. Was 45s (voelde als “altijd 45s”); lager = sneller door, iets hoger risico te vroeg.
     */
    private static final long FORCE_CREDENTIALS_WHEN_NO_LOGIN_UI_MS = 15_000L;
    /**
     * Bij precies 1x Rotatie-vink schrijven we geen credentials: sneller naar Play — korter wachten op
     * {@link Game#isOnLoginScreen()} tijdens LOADING (lager risico dan bij profiel-sync).
     */
    private static final long FORCE_NO_LOGIN_UI_SINGLE_VINK_MS = 6_000L;
    private static final int POLL_NO_LOGIN_UI_SINGLE_VINK_MS = 280;
    private static final int POLL_NO_LOGIN_UI_SINGLE_VINK_JITTER_MS = 140;
    /** Tweede waarschuwing over geen login-UI (reset interne teller voor bericht-spam). */
    private static final long LOGIN_UI_STUCK_WARN_RESET_MS = 60_000L;
    /**
     * Min. tijd tussen {@link Game#logout()}-pogingen bij account-wissel (langer dan panel-uitloggen: client moet dialoog/engine verwerken).
     * Te kort → tweede wissel lijkt “niet uit te loggen” omdat de volgende loop-tick te vroeg komt.
     */
    private static final long MIN_MS_BETWEEN_SWITCH_LOGOUT_RETRIES = 4500L;
    /** Eerste tick na {@link #handleLogout()}: korte pauze vóór opnieuw meten (logout draait synchroon via client {@code invoke}). */
    private static final int MS_LOOP_PAUSE_AFTER_SWITCH_LOGOUT = 2200;

    /** RuneLite: huidige wereld-ID en hop naar volgende account-wereld. */
    private IntSupplier currentWorldSupplier;
    private IntConsumer worldHopInvoker;
    private BooleanSupplier gameStateHoppingCheck;
    private boolean hopScheduledForThisSwitch;
    private long hopWorldStartMs;

    /** Per account: display name → wereld-ID; 0 = willekeurige F2P ({@link #randomF2pWorldSupplier}). */
    private ToIntFunction<String> switchWorldForDisplayName;
    private IntSupplier randomF2pWorldSupplier;

    public AccountSwitcher(CombatBotConfig config, CombatBotPaint paint) {
        this.config = config;
        this.paint = paint;
        parseAccounts();
        resetSwitchTimer();
    }

    public void setOnRotationAccountReady(java.util.function.Consumer<String> callback) {
        this.onRotationAccountReady = callback;
    }

    public void setManagedJagexLoginPreparer(BiPredicate<String, String> preparer) {
        this.managedJagexLoginPreparer = preparer;
    }

    public void setClientSyncExecutor(Consumer<Runnable> executor) {
        this.clientSyncExecutor = executor;
    }

    public void setClientLogoutInvoker(Consumer<Runnable> invoker) {
        this.clientLogoutInvoker = invoker;
    }

    public void setAccountRotationLogoutGate(BooleanSupplier gate) {
        this.accountRotationLogoutGate = gate;
    }

    /**
     * Wereld-hop vóór inloggen: {@code currentWorldId}, {@code hopToWorldId} (client-thread), {@code isHopping}.
     */
    public void setWorldHopSupport(IntSupplier currentWorldId, IntConsumer hopToWorldId, BooleanSupplier isHopping) {
        this.currentWorldSupplier = currentWorldId;
        this.worldHopInvoker = hopToWorldId;
        this.gameStateHoppingCheck = isHopping;
    }

    public void setSwitchWorldResolver(ToIntFunction<String> resolver) {
        this.switchWorldForDisplayName = resolver;
    }

    public void setRandomF2pWorldSupplier(IntSupplier supplier) {
        this.randomF2pWorldSupplier = supplier;
    }

    private boolean isLoginScreenReadyForAccountSwitch() {
        return Game.isOnLoginScreen() && !Game.isLoggedIn();
    }

    /** Aantal niet-lege Accounts-tab rijen met {@link ManagedJagexAccountsStore.ManagedJagexAccountRow#rotationEnabled}. */
    private int countManagedAccountsRotationChecked() {
        int n = 0;
        for (ManagedJagexAccountsStore.ManagedJagexAccountRow r
                : ManagedJagexAccountsStore.parseRows(config.managedJagexAccountsBlob())) {
            if (!r.isEmpty() && r.rotationEnabled) {
                n++;
            }
        }
        return n;
    }

    /** Precies één niet-lege rij met Rotatie-vink op de Accounts-tab. */
    private boolean exactlyOneManagedRotationVink() {
        return countManagedAccountsRotationChecked() == 1;
    }

    /**
     * Geen enkele managed Rotatie-vink, wel precies één Storm rotatie-entry — oude fallback; eerste login volledige credentials,
     * daarna overslaan zodra {@link #singleAccountCredentialsPrimed}.
     */
    private boolean stormAccountListSingleWithoutManagedVink() {
        return countManagedAccountsRotationChecked() == 0 && accounts.size() == 1;
    }

    private long forceNoLoginUiThresholdMs() {
        return exactlyOneManagedRotationVink()
                ? FORCE_NO_LOGIN_UI_SINGLE_VINK_MS
                : FORCE_CREDENTIALS_WHEN_NO_LOGIN_UI_MS;
    }

    private int msPollWhileWaitingForLoginUiAfterLogout() {
        if (exactlyOneManagedRotationVink()) {
            return POLL_NO_LOGIN_UI_SINGLE_VINK_MS + random.nextInt(POLL_NO_LOGIN_UI_SINGLE_VINK_JITTER_MS);
        }
        return 700 + random.nextInt(250);
    }

    private int msBetweenPlayRetriesOnLoginScreen() {
        return exactlyOneManagedRotationVink()
                ? (100 + random.nextInt(100))
                : (200 + random.nextInt(120));
    }

    /** Na {@link #transitionToSettingAccountOrHop()} uit {@link #handleWaitLogout()}: 1-vink = kortere loop-tick. */
    private int msAfterWaitLogoutTransition() {
        if (exactlyOneManagedRotationVink()) {
            if (state == SwitchState.HOP_WORLD) {
                return 150;
            }
            return 180 + random.nextInt(100);
        }
        if (state == SwitchState.HOP_WORLD) {
            return 250;
        }
        return 350 + random.nextInt(200);
    }

    private void maybeShowLogoutWaitMessage() {
        long now = System.currentTimeMillis();
        if (now - lastLogoutWaitMessageMs < 3500) {
            return;
        }
        lastLogoutWaitMessageMs = now;
        paint.setLastAntiBanAction("⏳ Account-wissel: wacht op login-scherm na uitloggen…");
    }

    private int resolveTargetWorldForNextAccount() {
        if (accounts.isEmpty()) {
            return -1;
        }
        // Eerste login vanaf login-scherm: nog geen index-advance → wereld hoort bij huidig account.
        // Na uitloggen voor wissel: hop voor het volgende account (current+1).
        int idx = settingAccountWithoutAdvance
                ? currentAccountIndex
                : (currentAccountIndex + 1) % accounts.size();
        String label = accounts.get(idx).label;
        int explicit = 0;
        if (switchWorldForDisplayName != null) {
            try {
                explicit = switchWorldForDisplayName.applyAsInt(label);
            } catch (Throwable ignored) {
            }
        }
        // Negatief = wereld-hop uit (per account); geen vaste wereld en geen willekeurige hop.
        if (explicit < 0) {
            return -1;
        }
        if (explicit > 0) {
            return explicit;
        }
        if (randomF2pWorldSupplier != null) {
            try {
                int w = randomF2pWorldSupplier.getAsInt();
                if (w > 0) {
                    return w;
                }
            } catch (Throwable ignored) {
            }
        }
        return -1;
    }

    private boolean shouldHopWorldBeforeNextAccount() {
        if (worldHopInvoker == null || currentWorldSupplier == null) {
            return false;
        }
        int target = resolveTargetWorldForNextAccount();
        if (target <= 0) {
            return false;
        }
        try {
            return currentWorldSupplier.getAsInt() != target;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void transitionToSettingAccountOrHop() {
        if (shouldHopWorldBeforeNextAccount()) {
            state = SwitchState.HOP_WORLD;
            hopScheduledForThisSwitch = false;
            hopWorldStartMs = System.currentTimeMillis();
            return;
        }
        state = SwitchState.SETTING_ACCOUNT;
    }

    private void runSyncOnClientThread(Runnable r) {
        if (clientSyncExecutor != null) {
            clientSyncExecutor.accept(r);
        } else {
            r.run();
        }
    }

    /**
     * Logout voor <strong>account-rotatie</strong> (timer-wissel).
     * <p>
     * Zelfde veiligheid als de panel-uitlogknop: optioneel {@code accountRotationLogoutGate} (combat/loot) vóór
     * aanroep. Stappen: {@code Game.logout()}, bij geen effect widget-interactie (typische OSRS-IDs), laatste {@code Game.logout()}.
     * <p>
     * {@code Thread.sleep} draait op de client thread (kan kort bevriezen); alleen in deze sequentie.
     */
    private void invokeGameLogout() {
        Runnable r = () -> {
            try {
                DebugLog.log("Accounts", "Logout poging #" + (logoutRetryWhileLoggedIn + 1)
                        + " | isLoggedIn=" + Game.isLoggedIn()
                        + " | isOnLoginScreen=" + Game.isOnLoginScreen());

                Game.logout();
                sleepLogoutStepMs(600);

                if (Game.isLoggedIn() && !Game.isOnLoginScreen()) {
                    DebugLog.log("Accounts", "Game.logout() had geen effect — probeer widget-logout");
                    IWidget logoutTab = Widgets.get(182, 8);
                    if (logoutTab != null) {
                        logoutTab.interact("Logout");
                        sleepLogoutStepMs(400);
                    }
                    IWidget logoutBtn = Widgets.get(182, 12);
                    if (logoutBtn != null) {
                        logoutBtn.interact(0);
                    } else {
                        logoutBtn = Widgets.get(182, 6);
                        if (logoutBtn != null) {
                            logoutBtn.interact("Click here to logout");
                        }
                    }
                }

                if (Game.isLoggedIn() && !Game.isOnLoginScreen()) {
                    sleepLogoutStepMs(600);
                    Game.logout();
                }

                DebugLog.log("Accounts", "Na logout-sequentie: isLoggedIn=" + Game.isLoggedIn()
                        + " | isOnLoginScreen=" + Game.isOnLoginScreen());
            } catch (Throwable t) {
                DebugLog.log("Accounts", "invokeGameLogout exception: " + t.getMessage());
            }
        };
        if (clientLogoutInvoker != null) {
            clientLogoutInvoker.accept(r);
        } else if (clientSyncExecutor != null) {
            clientSyncExecutor.accept(r);
        } else {
            r.run();
        }
    }

    private static void sleepLogoutStepMs(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Stop account-rotatie als logout blijft falen — voorkomt eindeloze client-herstarts. */
    private void abortAccountSwitchDueToLogoutLoop() {
        DebugLog.log("Accounts", "Account-wissel gestopt: logout lukt niet (max pogingen of timeout)");
        logoutRetryWhileLoggedIn = 0;
        singleAccountCredentialsPrimed = false;
        state = SwitchState.IDLE;
        hopScheduledForThisSwitch = false;
        hopWorldStartMs = 0;
        resetSwitchTimer();
        paint.setLastAntiBanAction("⛔ Account-wissel gestopt: uitloggen lukt niet — log uit via de client of herstart het spel");
    }

    /**
     * Parsed accounts vanuit de config string.
     * Formaat per entry:
     *   - "jagex:/pad/naar/credentials.properties" voor Jagex accounts
     *   - "email:wachtwoord" voor legacy accounts
     * Gescheiden door | of newline.
     */
    private void parseAccounts() {
        accounts.clear();

        // 1) Accountlijst (jagex:path | email:password)
        String accountsStr = config.accountList();
        if (accountsStr != null && !accountsStr.trim().isEmpty()) {
            String[] entries = accountsStr.split("[|\\n]+");
            for (String entry : entries) {
                entry = entry.trim();
                if (entry.isEmpty()) continue;

                if (entry.toLowerCase().startsWith("jagex:")) {
                    String path = entry.substring(6).trim();
                    AccountEntry jagexAccount = loadJagexCredentials(path);
                    if (jagexAccount != null) accounts.add(jagexAccount);
                } else {
                    String[] parts = entry.split(":", 2);
                    if (parts.length == 2) {
                        String username = parts[0].trim();
                        String password = parts[1].trim();
                        if (!username.isEmpty() && !password.isEmpty()) {
                            accounts.add(new AccountEntry(username, password));
                        }
                    }
                }
            }
        }

        // 2) Geplakte Jagex credentials (filter op aangevinkte display namen)
        java.util.Set<String> enabledNames = JagexCredentialsHelper.parseEnabledDisplayNames(config.enabledDisplayNames());
        List<JagexCredentialsHelper.ParsedJagexAccount> pastedAccounts = JagexCredentialsHelper.parsePastedCredentials(config.pastedCredentials());
        for (JagexCredentialsHelper.ParsedJagexAccount parsed : pastedAccounts) {
            if (enabledNames.contains(parsed.getDisplayName())) {
                accounts.add(new AccountEntry(parsed.toGameAccount(), parsed.getDisplayName()));
            }
        }
    }

    /**
     * Laadt Jagex account credentials uit een credentials-bestand (map/profiel).
     * Ondersteunt JX_* keys en Storm-profiel keys (characterId, sessionId, displayName).
     */
    private AccountEntry loadJagexCredentials(String path) {
        File file = new File(path);
        if (!file.exists() || !file.canRead()) {
            paint.setLastAntiBanAction("⚠ Credentials niet gevonden: " + file.getName());
            return null;
        }
        GameAccount account = JagexCredentialsHelper.loadFromFile(path);
        if (account == null) {
            paint.setLastAntiBanAction("⚠ Ongeldige credentials in: " + file.getName() + " (mist username/session?)");
            return null;
        }
        String displayName = account.getDisplayName();
        if (displayName == null || displayName.isEmpty()) {
            displayName = account.getUsername();
        }
        return new AccountEntry(account, path, displayName);
    }

    /**
     * Hoofdmethode - roep dit aan in de plugin loop.
     * Geeft > 0 terug als er gewacht moet worden (account switch bezig).
     * Geeft 0 terug als de normale loop door kan gaan.
     */
    public int check() {
        // Lege lijst = niets om in te loggen; 1 account mag wel (eerste login + timer-rotatie naar zelf).
        if (!config.accountSwitchEnabled()) {
            return 0;
        }
        if (accounts.isEmpty()) {
            if (state == SwitchState.IDLE) {
                return 0;
            }
            // Alleen uitloggen afmaken zonder accountregels; andere states met lege lijst resetten.
            if (state != SwitchState.WAITING_LOGOUT && state != SwitchState.LOGGING_OUT) {
                state = SwitchState.IDLE;
                return 0;
            }
        }

        switch (state) {
            case IDLE:
                return checkTimer();
            case LOGGING_OUT:
                return handleLogout();
            case WAITING_LOGOUT:
                return handleWaitLogout();
            case HOP_WORLD:
                return handleHopWorld();
            case SETTING_ACCOUNT:
                return handleSetAccount();
            case LOGGING_IN:
                return handleLogin();
            case COOLDOWN:
                return handleCooldown();
            default:
                return 0;
        }
    }

    private int checkTimer() {
        if (accountStartTime == null) {
            resetSwitchTimer();
        }

        // Eerst inloggen: bot/client op login-scherm — optioneel wereld-hop, daarna credentials (zelfde als na uitloggen).
        if (Game.isOnLoginScreen() && !Game.isLoggedIn()) {
            settingAccountWithoutAdvance = true;
            transitionToSettingAccountOrHop();
            paint.setLastAntiBanAction(state == SwitchState.HOP_WORLD
                    ? "🌍 Account-wissel: wereld kiezen vóór inloggen…"
                    : "🔐 Account-wissel: inloggen (start)…");
            return state == SwitchState.HOP_WORLD ? 50 : (25 + random.nextInt(35));
        }

        long elapsedSec = Duration.between(accountStartTime, Instant.now()).getSeconds();
        long periodSec = nextSwitchMinutes * 60L;
        long remainingSec = Math.max(0, periodSec - elapsedSec);
        paint.setAccountInfo(getCurrentAccountName(), remainingSec, accounts.size(), currentAccountIndex + 1);

        if (elapsedSec >= periodSec) {
            state = SwitchState.LOGGING_OUT;
            loginRetries = 0;
            paint.setLastAntiBanAction("🔄 Account wissel...");
            return MS_LOOP_PAUSE_AFTER_SWITCH_LOGOUT;
        }
        return 0;
    }

    private int handleLogout() {
        if (accountRotationLogoutGate != null && !accountRotationLogoutGate.getAsBoolean()) {
            paint.setLastAntiBanAction("Account-wissel: wacht tot veilig om uit te loggen (combat/loot)...");
            return 450 + random.nextInt(200);
        }
        logoutRetryWhileLoggedIn = 0;
        invokeGameLogout();
        lastLogoutAttemptMs = System.currentTimeMillis();
        state = SwitchState.WAITING_LOGOUT;
        waitLogoutSinceMs = System.currentTimeMillis();
        return MS_LOOP_PAUSE_AFTER_SWITCH_LOGOUT;
    }

    private int handleWaitLogout() {
        if (Game.isLoggedIn()) {
            if (accountRotationLogoutGate != null && !accountRotationLogoutGate.getAsBoolean()) {
                paint.setLastAntiBanAction("Account-wissel: wacht (combat/loot) voor opnieuw uitloggen...");
                return 550 + random.nextInt(200);
            }
            long now = System.currentTimeMillis();
            if (now - waitLogoutSinceMs >= ABORT_LOGOUT_STUCK_AFTER_MS) {
                abortAccountSwitchDueToLogoutLoop();
                return 3000;
            }
            if (now - lastLogoutAttemptMs >= MIN_MS_BETWEEN_SWITCH_LOGOUT_RETRIES) {
                if (logoutRetryWhileLoggedIn >= MAX_LOGOUT_RETRY_WHILE_LOGGED_IN) {
                    abortAccountSwitchDueToLogoutLoop();
                    return 3000;
                }
                logoutRetryWhileLoggedIn++;
                invokeGameLogout();
                lastLogoutAttemptMs = now;
                paint.setLastAntiBanAction("🔄 Account-wissel: opnieuw uitloggen… (" + logoutRetryWhileLoggedIn + "/" + MAX_LOGOUT_RETRY_WHILE_LOGGED_IN + ")");
                return MS_LOOP_PAUSE_AFTER_SWITCH_LOGOUT + random.nextInt(500);
            }
            maybeShowLogoutWaitMessage();
            return exactlyOneManagedRotationVink()
                    ? (500 + random.nextInt(200))
                    : (900 + random.nextInt(350));
        }
        if (!isLoginScreenReadyForAccountSwitch()) {
            maybeShowLogoutWaitMessage();
            long waitMs = System.currentTimeMillis() - waitLogoutSinceMs;
            if (waitMs > LOGIN_UI_STUCK_WARN_RESET_MS) {
                paint.setLastAntiBanAction("⚠ Nog geen login-scherm — sta je in een cutscene? Wacht of log handmatig uit.");
                waitLogoutSinceMs = System.currentTimeMillis();
            }
            // Lang LOADING / tussenscherm: blijf anders eeuwig in WAITING_LOGOUT (vooral 2e wissel)
            if (waitMs > forceNoLoginUiThresholdMs()) {
                paint.setLastAntiBanAction("⏳ Account-wissel: geen login-UI — ga door naar account/wereld-stap…");
                transitionToSettingAccountOrHop();
                return msAfterWaitLogoutTransition();
            }
            return msPollWhileWaitingForLoginUiAfterLogout();
        }
        transitionToSettingAccountOrHop();
        return msAfterWaitLogoutTransition();
    }

    private int handleHopWorld() {
        int target = resolveTargetWorldForNextAccount();
        if (target <= 0 || worldHopInvoker == null || currentWorldSupplier == null) {
            state = SwitchState.SETTING_ACCOUNT;
            return 200;
        }
        int cur;
        try {
            cur = currentWorldSupplier.getAsInt();
        } catch (Throwable t) {
            state = SwitchState.SETTING_ACCOUNT;
            return 500;
        }
        if (cur == target) {
            hopScheduledForThisSwitch = false;
            state = SwitchState.SETTING_ACCOUNT;
            return 300;
        }
        if (!hopScheduledForThisSwitch) {
            hopScheduledForThisSwitch = true;
            hopWorldStartMs = System.currentTimeMillis();
            try {
                worldHopInvoker.accept(target);
            } catch (Throwable t) {
                DebugLog.log("Accounts", "Wereld-hop aanroep: " + t.getMessage());
            }
            paint.setLastAntiBanAction("🌍 Account-wissel: hop naar w" + target + "…");
            return 12000 + random.nextInt(2000);
        }
        boolean hopping = false;
        try {
            hopping = gameStateHoppingCheck != null && gameStateHoppingCheck.getAsBoolean();
        } catch (Throwable ignored) {
        }
        if (hopping) {
            if (System.currentTimeMillis() - hopWorldStartMs > 90_000) {
                hopScheduledForThisSwitch = false;
                state = SwitchState.SETTING_ACCOUNT;
                paint.setLastAntiBanAction("⚠ Wereld-hop duurt lang — ga door met inloggen");
                return 2000;
            }
            return 3500;
        }
        try {
            if (currentWorldSupplier.getAsInt() == target) {
                hopScheduledForThisSwitch = false;
                state = SwitchState.SETTING_ACCOUNT;
                paint.setLastAntiBanAction("✓ Wereld w" + target + " — account voorbereiden…");
                return 600;
            }
        } catch (Throwable ignored) {
        }
        if (System.currentTimeMillis() - hopWorldStartMs > 50_000) {
            hopScheduledForThisSwitch = false;
            state = SwitchState.SETTING_ACCOUNT;
            paint.setLastAntiBanAction("⚠ Wereld-hop: timeout — volgende stap");
            return 2000;
        }
        return 3000;
    }

    private int handleSetAccount() {
        if (settingAccountWithoutAdvance) {
            settingAccountWithoutAdvance = false;
        } else {
            currentAccountIndex = (currentAccountIndex + 1) % accounts.size();
        }
        AccountEntry entry = accounts.get(currentAccountIndex);

        if (exactlyOneManagedRotationVink()) {
            DebugLog.log("Accounts", "1x Rotatie-vink: geen credentials — alleen Play");
            paint.setLastAntiBanAction("→ Alleen Play (geen credential-sync; 1 account aangevinkt)");
            state = SwitchState.LOGGING_IN;
            loginRetries = 0;
            if (Game.isOnLoginScreen()) {
                JagexLauncherPlayButton.clickPlayButton();
                loginRetries = 1;
            }
            return 70 + random.nextInt(60);
        }

        if (singleAccountCredentialsPrimed && stormAccountListSingleWithoutManagedVink()) {
            DebugLog.log("Accounts", "1 Storm-entry zonder managed-vink: credentials overslaan (geprime)");
            paint.setLastAntiBanAction("→ Zelfde account — alleen Play (credentials niet opnieuw gezet)");
            state = SwitchState.LOGGING_IN;
            loginRetries = 0;
            if (Game.isOnLoginScreen()) {
                JagexLauncherPlayButton.clickPlayButton();
                loginRetries = 1;
            }
            return 70 + random.nextInt(60);
        }

        // Eerst managed-tab pad (zelfde als dubbelklik): voorkomt dat loadSavedCredentials
        // een verkeerde stash zet vóór we de rij uit managedJagexAccountsBlob toepassen.
        boolean managedLikeDoubleClick = entry.isJagex && managedJagexLoginPreparer != null
                && managedJagexLoginPreparer.test(entry.label, entry.credentialsPath);

        // Jagex: gebruik opgeslagen credentials alleen als we niet via managed-pad zijn gegaan
        if (entry.isJagex && !managedLikeDoubleClick) {
            GameAccount saved = JagexCredentialsHelper.loadSavedCredentials(entry.label);
            if (saved != null) {
                entry = new AccountEntry(saved, entry.credentialsPath, entry.label);
                accounts.set(currentAccountIndex, entry);
            } else if (entry.credentialsPath != null) {
                // Geen opgeslagen kopie: laad van pad (slaat meteen op voor volgende keer)
                AccountEntry refreshed = loadJagexCredentials(entry.credentialsPath);
                if (refreshed != null) {
                    accounts.set(currentAccountIndex, refreshed);
                    entry = refreshed;
                } else {
                    paint.setLastAntiBanAction("⚠ Credentials verlopen: " + entry.label);
                    if (accounts.size() > 2) {
                        return 2000;
                    }
                    state = SwitchState.IDLE;
                    return 10000;
                }
            }
        }

        if (!entry.isJagex) {
            if (DEBUG_SKIP_LEGACY_SWITCH_CREDENTIALS) {
                DebugLog.log("Accounts", "DEBUG_SKIP_LEGACY_SWITCH_CREDENTIALS: geen setNormalLoginMode/setGameAccount");
                paint.setLastAntiBanAction("DEBUG: legacy switch overgeslagen (flag) — alleen Jagex testen");
            } else {
                Client.setNormalLoginMode();
                Game.setGameAccount(entry.gameAccount);
            }
        } else if (managedLikeDoubleClick) {
            // Plugin: zelfde pad als dubbelklik — applyPreparedJagexLoginToClient (geen tweede setGameAccount)
            paint.setLastAntiBanAction("→ " + entry.label + " (zoals dubbelklik)");
        } else {
            final AccountEntry ent = entry;
            runSyncOnClientThread(() -> {
                Client.setOAuthLoginMode();
                File prof = JagexCredentialsHelper.resolveLoginScreenCredentialsFile(
                        config.loginScreenCredentialsPath(), config.accountList(), ent.credentialsPath);
                if (prof != null) {
                    JagexCredentialsHelper.syncAccountToProfileForSwitcher(
                            prof, ent.label, config.pastedCredentials(), ent.gameAccount);
                }
                Game.setGameAccount(ent.gameAccount);
            });
            paint.setLastAntiBanAction("→ " + entry.label + " (Jagex)");
        }

        state = SwitchState.LOGGING_IN;
        // Direct eerste Play na credentials (was vóór aparte tick → sneller cold start).
        loginRetries = 0;
        if (Game.isOnLoginScreen()) {
            JagexLauncherPlayButton.clickPlayButton();
            loginRetries = 1;
        }
        return 70 + random.nextInt(60);
    }

    private int handleLogin() {
        if (Game.isLoggedIn()) {
            if (stormAccountListSingleWithoutManagedVink()) {
                singleAccountCredentialsPrimed = true;
            }
            state = SwitchState.COOLDOWN;
            cooldownStart = Instant.now();
            resetSwitchTimer();
            paint.setLastAntiBanAction("✓ Ingelogd: " + getCurrentAccountName());
            paint.incrementAccountSwitches();
            return 600;
        }

        if (Game.isOnLoginScreen()) {
            JagexLauncherPlayButton.clickPlayButton();
            loginRetries++;
            if (loginRetries > MAX_LOGIN_RETRIES) {
                paint.setLastAntiBanAction("⚠ Login mislukt, skip " + getCurrentAccountName());
                state = SwitchState.SETTING_ACCOUNT;
                loginRetries = 0;
                return 2000;
            }
            return msBetweenPlayRetriesOnLoginScreen();
        }

        return exactlyOneManagedRotationVink() ? (80 + random.nextInt(70)) : (120 + random.nextInt(100));
    }

    private int handleCooldown() {
        if (cooldownStart != null) {
            long elapsed = Duration.between(cooldownStart, Instant.now()).getSeconds();
            if (elapsed >= COOLDOWN_SECONDS) {
                state = SwitchState.IDLE;
                // Schone start voor volgende timer-wissel (voorkomt vastlopen in HOP/WAIT_LOGOUT bij 2e keer)
                hopScheduledForThisSwitch = false;
                hopWorldStartMs = 0;
                waitLogoutSinceMs = 0;
                lastLogoutWaitMessageMs = 0;
                lastLogoutAttemptMs = 0;
                logoutRetryWhileLoggedIn = 0;
                settingAccountWithoutAdvance = false;
                if (onRotationAccountReady != null && !accounts.isEmpty()) {
                    try {
                        String nm = getCurrentAccountName();
                        if (nm != null && !nm.isEmpty() && !"Geen".equals(nm)) {
                            onRotationAccountReady.accept(nm);
                        }
                    } catch (Throwable ignored) {
                    }
                }
                return 0;
            }
        }
        return 2000;
    }

    private void resetSwitchTimer() {
        accountStartTime = Instant.now();
        int min = config.accountMinMinutes();
        int max = config.accountMaxMinutes();
        if (max <= min) max = min + 1;
        nextSwitchMinutes = min + random.nextInt(max - min);
    }

    public String getCurrentAccountName() {
        if (accounts.isEmpty()) return "Geen";
        return accounts.get(currentAccountIndex).label;
    }

    public boolean isSwitching() {
        return state != SwitchState.IDLE;
    }

    public boolean requestImmediateSwitch(String reason) {
        if (!config.accountSwitchEnabled() || accounts.isEmpty()) {
            return false;
        }
        state = SwitchState.LOGGING_OUT;
        loginRetries = 0;
        logoutRetryWhileLoggedIn = 0;
        waitLogoutSinceMs = 0L;
        paint.setLastAntiBanAction(reason != null && !reason.trim().isEmpty()
                ? reason
                : "🔄 Account wissel aangevraagd");
        return true;
    }

    /**
     * Alleen tijdens actief uitloggen / wachten op login-scherm terwijl nog ingelogd — niet tijdens {@link SwitchState#COOLDOWN}
     * (anders zouden combat handlers na elke wissel te lang geen nieuwe targets pakken).
     */
    public boolean wantsCombatSuppressForLogoutFlow() {
        if (state == SwitchState.LOGGING_OUT) {
            return true;
        }
        return state == SwitchState.WAITING_LOGOUT && Game.isLoggedIn();
    }

    public int getAccountCount() {
        return accounts.size();
    }

    /**
     * Synchroniseer de interne index op basis van de daadwerkelijk ingelogde spelernaam.
     * Alleen in IDLE zodat een actieve switch-flow niet wordt verstoord.
     */
    public void syncCurrentAccountByDisplayName(String displayName) {
        if (state != SwitchState.IDLE || displayName == null || displayName.trim().isEmpty() || accounts.isEmpty()) {
            return;
        }
        String wanted = displayName.trim();
        for (int i = 0; i < accounts.size(); i++) {
            AccountEntry e = accounts.get(i);
            if (e != null && e.label != null && e.label.equalsIgnoreCase(wanted)) {
                currentAccountIndex = i;
                return;
            }
        }
    }

    /**
     * Herlaad de accounts lijst (bijv. na config wijziging).
     * Houdt {@link #currentAccountIndex} geldig — anders {@code accounts.get(index)} crash of verkeerde wissel bij 2e rotatie.
     */
    public void reload() {
        parseAccounts();
        if (!stormAccountListSingleWithoutManagedVink()) {
            singleAccountCredentialsPrimed = false;
        }
        if (accounts.isEmpty()) {
            currentAccountIndex = 0;
        } else if (currentAccountIndex >= accounts.size()) {
            currentAccountIndex = accounts.size() - 1;
        }
    }

    /**
     * Panel "Reset": afbreken van account-wissel / uitloggen / inloggen en rotatie-timer opnieuw vanaf nu.
     * Zelfde idee als script uit en weer aan — geen hangende switch-state.
     */
    public void resetToIdleFromPanel() {
        state = SwitchState.IDLE;
        loginRetries = 0;
        settingAccountWithoutAdvance = false;
        singleAccountCredentialsPrimed = false;
        hopScheduledForThisSwitch = false;
        hopWorldStartMs = 0;
        cooldownStart = null;
        waitLogoutSinceMs = 0;
        lastLogoutAttemptMs = 0;
        logoutRetryWhileLoggedIn = 0;
        resetSwitchTimer();
    }
}
