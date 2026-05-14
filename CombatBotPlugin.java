package com.combatbot;

import com.google.inject.Inject;
import com.google.inject.Provides;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Quest;
import net.runelite.api.Skill;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.widgets.Widget;
import net.runelite.api.ChatMessageType;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.Text;
import net.storm.api.domain.actors.INPC;
import net.storm.api.domain.actors.IPlayer;
import net.storm.api.domain.items.IInventoryItem;
import net.storm.api.domain.widgets.IWidget;
import net.storm.api.Static;
import net.storm.api.game.AttackStyle;
import net.storm.api.game.ICombat;
import net.storm.api.plugins.LoopedPlugin;
import net.storm.api.plugins.PluginDescriptor;
import net.storm.sdk.entities.NPCs;
import net.storm.sdk.entities.Players;
import net.storm.sdk.input.Mouse;
import net.storm.sdk.items.Bank;
import net.storm.sdk.items.Equipment;
import net.storm.sdk.items.GrandExchange;
import net.storm.sdk.items.Inventory;
import net.storm.api.account.GameAccount;
import net.storm.sdk.game.Game;
import net.storm.sdk.game.Skills;
import net.storm.sdk.quests.Quests;
import net.storm.sdk.widgets.Dialog;
import net.storm.sdk.widgets.Widgets;
import javax.imageio.ImageIO;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.awt.Canvas;
import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.event.MouseAdapter;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@PluginDescriptor(
        name = "Combat Bot",
        description = "Multi-skill bot: vecht, hak hout, mijn erts, vis, loot, eet en bankt automatisch"
)
public class CombatBotPlugin extends LoopedPlugin {

    public static final String VERSION = "0.3.1";

    @Inject
    private CombatBotConfig config;

    @Inject
    private net.storm.api.plugins.config.ConfigManager stormConfigManager;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private ClientToolbar clientToolbar;

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private DrawManager drawManager;

    @Inject
    private CombatBotOverlay overlay;

    @Inject
    private AreaOverlay areaOverlay;

    @Inject
    private WalkClickHighlightOverlay walkClickHighlightOverlay;

    @Inject
    private WidgetHoverOverlay widgetHoverOverlay;

    @Inject
    private MouseDebugOverlay mouseDebugOverlay;

    private CombatBotPaint paint;
    private AntiBan antiBan;
    private AccountSwitcher accountSwitcher;
    private SameAccountRelogger sameAccountRelogger;
    private TileMarkerManager tileMarkerManager;
    private AreaMenuListener areaMenuListener;

    // Panel + HTTP server
    private CombatBotPanel panel;
    private NavigationButton navButton;
    private ConfigHttpServer httpServer;
    /**
     * Panel "Uitloggen": wacht op einde combat, loot waar nodig, geen nieuwe aanvallen, daarna
     * {@link Game#logout()} op de {@link ClientThread} (niet op EDT — anders faalt uitloggen / bevestigingsdialoog).
     */
    private volatile boolean panelLogoutRequested;
    private volatile long panelLogoutRequestedAtMs;
    private static final long PANEL_LOGOUT_FORCE_TIMEOUT_MS = 45_000L;

    // Handlers
    private CombatHandler combatHandler;
    private WoodcutterHandler woodcutterHandler;
    private MiningHandler miningHandler;
    private FishingHandler fishingHandler;
    private ImpsHandler impsHandler;
    private GiantsHandler giantsHandler;
    private BarbarianHandler barbarianHandler;
    private LootHandler lootHandler;
    private VampireSlayerQuestHandler vampireSlayerQuestHandler;
    private StarterSkillHandler starterSkillHandler;
    private TutorialModeHandler tutorialModeHandler;

    // Skill rotation
    public enum ActiveSkill { TUT, STARTER, COMBAT, BARBARIAN, WOODCUTTING, MINING, FISHING, IMPS, GIANTS, LOOT, VAMPIRE_SLAYER }
    private static final int VAMPIRE_SLAYER_MIN_HP_LEVEL = 15;
    private ActiveSkill activeSkill = ActiveSkill.COMBAT;
    private Instant skillSwitchTime;
    private int nextSwitchSeconds;
    private final Random random = new Random();

    // Guard: voorkomt loop-bug direct na switch
    private boolean justSwitched = false;
    private long switchCooldownUntil = 0;
    private long lastMeleeStyleSwitchMs = 0;
    private boolean wcImpsCashFarmActive = false;
    private int wcImpsCashFarmTripsTarget = 0;
    private int wcImpsCashFarmTripsStart = 0;
    private boolean moneyImpsCashFarmActive = false;
    private ActiveSkill moneyImpsCashFarmReturnSkill = ActiveSkill.COMBAT;
    private int moneyImpsCashFarmTripsTarget = 0;
    private int moneyImpsCashFarmTripsStart = 0;
    private long lastBankSnapshotSyncMs = 0L;
    /** Vorige tick: bank open geweest voor snapshot (rising edge = direct sync). */
    private boolean bankSnapshotPrevOpen = false;
    private long lastQuestCalibrationSyncMs = 0L;
    /** Panel “reset”: volgende fresh start laadt starter-fase niet uit account-JSON. */
    private boolean ignoreStarterJsonOnNextFreshStart;

    // Discord screenshot sending
    private volatile boolean discordSendInProgress = false;
    private long lastDiscordSendMs = 0;
    /** Tijdens re-log: tekst-pings met ETA ( los van screenshot-interval; loop stopt vroeg tijdens pauze). */
    private long lastDiscordRelogPingMs = 0L;
    private long lastWidgetInspectorDumpMs = 0L;
    /** Path-traversal tracker voor de Walk-klik overlay: laatste tile waarop we de speler zagen. */
    private WorldPoint lastPathTraversalWp = null;
    /** Click-destination tracker: laatste RuneLite local destination die als click-tile is gemarkeerd. */
    private WorldPoint lastWalkDestinationWp = null;
    /**
     * Aantal aankomende game-ticks waarin we de actuele RL destination als click-tile registreren,
     * ook als die nog niet gewijzigd is. Wordt bv. gezet na een minimap-click of een mislukte
     * scene-decode zodat we de échte target-tile zeker te pakken krijgen.
     */
    private int pendingDestCaptureTicks = 0;
    private static final long DISCORD_RELOG_PING_INTERVAL_MS = 5L * 60L * 1000L;
    private long lastRandomEventActionMs = 0;
    private long lastLampSkillSelectMs = 0;
    private long lastLampActionMs = 0;
    private long lampFlowStartedMs = 0;
    private long lastLampDebugMs = 0;
    private int lampInterfaceGroupHint = -1;
    private static final long RANDOM_EVENT_ACTION_COOLDOWN_MS = 1500;
    private static final int GENIE_LAMP_ITEM_ID = 2528;
    private static final long LAMP_ACTION_COOLDOWN_MS = 250;
    private static final long LAMP_DEBUG_INTERVAL_MS = 1500;
    private static final long LAMP_FAILSAFE_AFTER_MS = 12000;
    /** Min. tijd tussen "Rub/Use" op lamp in inventaris als interface nog niet herkend wordt (voorkomt spam-loop). */
    private static final long LAMP_INVENTORY_OPEN_MIN_GAP_MS = 2200;
    /**
     * Veel voorkomende interface-groepen voor lamp/XL-lamp schermen (eerst hier scannen, daarna 0–800).
     * Zie o.a. deadzone-config; IDs kunnen per revisie verschuiven — fallback blijft volledige scan.
     */
    private static final int[] LAMP_INTERFACE_GROUPS_PRIORITY = {
            240, 219, 229, 233, 134, 260, 261, 311, 312, 162, 163
    };
    /** Fragmenten in widget-tekst die samen met "confirm" op een lamp-skill scherm wijzen. */
    private static final String[] LAMP_SKILL_LABEL_FRAGMENTS = {
            "hitpoints", "hit points", "attack", "strength", "defence", "defense", "magic", "ranged", "prayer",
            "runecraft", "construction", "woodcutting", "fishing", "cooking", "mining", "smithing", "crafting",
            "herblore", "agility", "thieving", "slayer", "farming", "hunter", "fletching", "firemaking",
            "sailing", "choose"
    };
    private static final Gson COMPACT_GSON = new Gson();
    private static final DateTimeFormatter CALIBRATION_TIME_FMT = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");
    private long lastLampInventoryInteractMs = 0;
    private MouseAdapter gameplayMouseTraceListener;
    private long gameplayMouseTraceLastMoveMs = 0L;
    private long gameplayMouseTraceLastEventMs = 0L;
    private boolean gameplayMouseTraceDragging = false;

    /**
     * Alternatieve widget-teksten per skill, voor als de interface niet exact {@link CombatBotConfig.GenieLampSkill#displayName()} gebruikt.
     */
    private static String[] getLampKeywordsForSkill(CombatBotConfig.GenieLampSkill skill) {
        if (skill == null) {
            return new String[0];
        }
        String name = skill.displayName().toLowerCase();
        switch (skill) {
            case HITPOINTS:
                return new String[]{"hitpoints", "hit points"};
            case DEFENCE:
                return new String[]{"defence", "defense"};
            case RUNECRAFT:
                return new String[]{"runecraft", "runecrafting"};
            default:
                return new String[]{name};
        }
    }

    // ID-based herkenning (naast naam) voor stabielere random-event handling.
    private static final Set<Integer> GENIE_NPC_IDS = new HashSet<>(List.of(326, 327));
    // Bekende random event namen (fallback naast ID-checks).
    private static final Set<String> RANDOM_EVENT_NAMES = new HashSet<>(Arrays.asList(
            "genie",
            "drill demon",
            "freaky forester",
            "frog",
            "mysterious old man",
            "evil bob",
            "capt' arnav",
            "phileas rimor",
            "leo",
            "postie pete",
            "quiz master",
            "sandwich lady",
            "strange plant",
            "dunce",
            "pillory guard",
            "beekeeper",
            "security guard",
            "certer",
            "giles",
            "niles",
            "miles"
    ));
    private static final Set<String> LAMP_COURIER_NAMES = new HashSet<>(Arrays.asList(
            "giles", "niles", "miles"
    ));
    // Event-specifieke IDs kunnen hier uitgebreid worden zodra je ze in logs ziet.
    private static final Set<Integer> DISMISS_RANDOM_EVENT_IDS = new HashSet<>(List.of(
            // Placeholder voor toekomstige vaste IDs; naam-check blijft actief.
    ));
    /** Minimale cooldown (1 tick) — handler start direct met lopen/banken na wissel. */
    private static final long SWITCH_COOLDOWN_MS = 100;

    // Track bot enabled state voor fresh start
    private boolean wasBotEnabled = false;
    private boolean startupInventoryChecked = false;

    // Directe knoppen: panel zet deze via callback, plugin verwerkt in loop (config kan vertraagd zijn)
    private volatile boolean switchNowRequested = false;
    private volatile boolean nextAccountRequested = false;
    private volatile boolean sellNowRequested = false;
    private volatile boolean loginNowRequested = false;

    // Cleanup state voor skill switch
    private boolean cleaningUpBeforeSwitch = false;
    private ActiveSkill pendingSkill = null;

    private long lastAccountListReloadTime = 0;
    private static final long ACCOUNT_LIST_RELOAD_INTERVAL_MS = 10_000;
    /** Eén keer accounts herladen zodra we uitgelogd + bot aan zijn — geen 10s wachten vóór eerste login. */
    private boolean coldLoginAccountListPrimed;
    private long lastAccountStatSnapshotMs = 0;
    private static final long ACCOUNT_STAT_SNAPSHOT_INTERVAL_MS = 45_000L;
    private long lastHiscoreBanCheckMs = 0L;
    private static final long HISCORE_BAN_CHECK_INTERVAL_MS = 30L * 60L * 1000L;
    private volatile boolean hiscoreBanCheckInFlight = false;

    /** Laatst gesynchroniseerde center-strings (panel / HTTP wijkt af → handlers herladen). */
    private String snapCombatCenters = "";
    private String snapWcCenters = "";
    private String snapMiningCenters = "";
    private String snapFishingCenters = "";
    private String snapImpsCenters = "";
    /** Globale center-teksten (Centers-tab); wordt niet leeggemaakt door account zonder globale centers. */
    private String masterCombatCenters = "";
    private String masterWcCenters = "";
    private String masterMiningCenters = "";
    private String masterFishingCenters = "";
    private String masterImpsCenters = "";
    private boolean applyingManagedCenters = false;

    /** Multi-account (👤-tabel): na logout volgende account voorbereiden + skill-voortgang per RSN. */
    private boolean prevGameLoggedIn;
    /** Vorige tick: bankinterface open — voor failureNudge-decay bij sluiten. */
    private boolean prevTickBankOpen;
    private String lastTrackedPlayerName = "";
    private boolean managedLogoutPrepareDone;
    private ManagedJagexAccountsStore.ManagedJagexAccountRow pendingManagedLoginRow;
    private boolean restoredProgressThisLogin;
    /** Voorkomt herhaald wissen van per-account timers terwijl bot uit staat. */
    private boolean clearedLocalProgressOnStop;
    /** Anti-loop guard: herhaald laden van exact dezelfde skill op hetzelfde account. */
    private final Map<String, String> lastRestoredSkillByAccount = new HashMap<>();
    private final Map<String, Integer> sameRestoreCountByAccount = new HashMap<>();
    private long lastLocalProgressSaveMs;
    private static final long LOCAL_PROGRESS_SAVE_INTERVAL_MS = 30_000L;
    private static final int DARK_WIZARD_LEVEL = 7;
    private static final int DARK_WIZARD_SAFE_COMBAT_LEVEL = (DARK_WIZARD_LEVEL * 2) + 2; // lvl 16+
    private long draynorAvoidCentersUntilMs = 0L;
    private final Map<String, Long> draynorBotMentionSpeakerMs = new HashMap<>();
    private long lastDraynorBotReactionMs = 0L;
    private long lastAutoDeactivateMs = 0L;
    private boolean deathFailoverTriggered;
    /** Global anti-stuck: te lang stil op dezelfde tile zonder animatie/beweging. */
    private WorldPoint idleStuckLastTile;
    private long idleStuckSinceMs;
    private long idleStuckLastWarnMs;
    private long idleStuckLastRecoveryMs;
    private int idleStuckRecoveryCount;
    private static final long IDLE_STUCK_WARN_MS = 45_000L;
    private static final long IDLE_STUCK_TRIGGER_MS = 120_000L;
    private static final long IDLE_STUCK_RECOVERY_COOLDOWN_MS = 25_000L;
    /** Starter-specifieke status-stall monitor (zelflerend per account/status). */
    private String starterLastStatusBucket = "";
    private long starterStatusSinceMs = 0L;
    private long starterStatusLastWarnMs = 0L;
    private long starterStatusLastRecoveryMs = 0L;
    /** Debug loop-watch: detecteer herhalende "niks-doen" signature voor snellere diagnose. */
    private String loopWatchLastSignature = "";
    private long loopWatchSignatureSinceMs = 0L;
    private long loopWatchLastLogMs = 0L;
    private static final long LOOP_WATCH_LOG_EVERY_MS = 30_000L;

    /** Barb loot actief zonder {@code barbLootEnabled} (alleen bij start skill BARB_LOOT + Magic &lt; 5). */
    private boolean barbLootSessionActive;
    /** Eén keer Magic-check na login voor {@link CombatBotConfig.StartSkill#BARB_LOOT}. */
    private boolean barbLootStartMagicCheckDone;

    @Provides
    CombatBotConfig provideConfig(net.storm.api.plugins.config.ConfigManager configManager) {
        return configManager.getConfig(CombatBotConfig.class);
    }

    @Override
    public void startUp() throws Exception {
        CombatBotRuntime.setActivePlugin(this);
        paint = new CombatBotPaint();
        antiBan = new AntiBan(config, paint);
        antiBan.startFidgetWorker();
        accountSwitcher = new AccountSwitcher(config, paint);
        accountSwitcher.setOnRotationAccountReady(this::applyManagedCentersForDisplayName);
        accountSwitcher.setManagedJagexLoginPreparer(this::tryApplyManagedRowLoginForSwitcher);
        if (clientThread != null) {
            accountSwitcher.setClientSyncExecutor(clientThread::invoke);
            accountSwitcher.setClientLogoutInvoker(clientThread::invoke);
            accountSwitcher.setAccountRotationLogoutGate(this::canCompletePanelLogoutNow);
        }
        // Geen GameState/cutscene-filter: na logout is de client vaak kort LOADING — dan bleef de wissel op "wacht op login-scherm" hangen.
        accountSwitcher.setWorldHopSupport(
                () -> client != null ? client.getWorld() : -1,
                (worldId) -> AccountSwitchWorldHop.scheduleHopToWorld(client, clientThread, worldId),
                () -> client != null && client.getGameState() == GameState.HOPPING);
        accountSwitcher.setSwitchWorldResolver(this::resolveAccountSwitchWorldForDisplayName);
        accountSwitcher.setRandomF2pWorldSupplier(() -> AccountSwitchWorldHop.pickRandomF2pWorldId(client));
        sameAccountRelogger = new SameAccountRelogger(config, paint);

        // TileMarkerManager
        tileMarkerManager = new TileMarkerManager();
        loadTileMarkersFromConfig();

        overlay.setPaint(paint);
        overlayManager.add(overlay);
        overlayManager.add(areaOverlay);
        overlayManager.add(walkClickHighlightOverlay);
        overlayManager.add(widgetHoverOverlay);
        overlayManager.add(mouseDebugOverlay);
        areaOverlay.setConfig(config);
        walkClickHighlightOverlay.setConfig(config);
        areaOverlay.setTileMarkerManager(tileMarkerManager);

        MovementHelper.setWalkHighlightPersistence(
                csv -> stormConfigManager.setConfiguration("combatbot", "debugWalkClickPersistedQueue",
                        csv == null ? "" : csv),
                () -> config.debugWalkClickPersistedQueue());

        // Auto-log walk-tile events naar JSONL — alleen als toggle aan staat (config-gated bij elke event).
        MovementHelper.setWalkTileEventSink(line -> {
            if (config.debugWalkAutoLogToDisk() && config.debugWalkClickOverlay()) {
                DebugLog.appendWalkTilesJsonLine(line);
            }
        });

        // Anti-ban: vraag de fidget-worker om ~1.2 sec rust voor elke walk-actie zodat
        // synthetische muis-bewegingen niet bovenop een echte walk-/destinatie-click landen.
        MovementHelper.setPreWalkActionHook(() -> {
            if (antiBan != null) antiBan.notifyHandlerAction("walk", 1200);
        });

        // Per-account GE shop policy: GeShopPolicy bevraagt de huidige row van de ingelogde RSN.
        GeShopPolicy.setCurrentRowSupplier(() ->
                ManagedJagexAccountsStore.findRowForDisplayName(config, tryGetLocalRsn()));

        // Scene-check predicate voor de walk-tile diagnose-knop: een WorldPoint zit in scene
        // als LocalPoint.fromWorld een waarde teruggeeft (= overlay zou hem kunnen tekenen).
        MovementHelper.setSceneInBoundsCheck(wp -> {
            try {
                if (wp == null || client == null) return false;
                if (client.getGameState() != GameState.LOGGED_IN) return false;
                return net.runelite.api.coords.LocalPoint.fromWorld(client, wp) != null;
            } catch (Throwable ignored) {
                return false;
            }
        });

        // Polygon-check: kan de overlay-renderer hier echt een vlak tekenen?
        // (camera/hoogte/occlusie kan in-scene tiles alsnog blokkeren)
        MovementHelper.setPolygonRenderableCheck(wp -> {
            try {
                if (wp == null || client == null) return false;
                if (client.getGameState() != GameState.LOGGED_IN) return false;
                net.runelite.api.coords.LocalPoint lp = net.runelite.api.coords.LocalPoint.fromWorld(client, wp);
                if (lp == null) return false;
                return net.runelite.api.Perspective.getCanvasTilePoly(client, lp) != null;
            } catch (Throwable ignored) {
                return false;
            }
        });

        // Hotspot-stuck waarschuwing — initieel uit configuratie laden.
        applyWalkTileStuckConfig();

        // AreaMenuListener met center callbacks
        areaMenuListener = new AreaMenuListener();
        areaMenuListener.setClient(client);
        areaMenuListener.init(tileMarkerManager, this::saveTileMarkersToConfig,
                new AreaMenuListener.CenterCallback() {
                    @Override
                    public void onAddCenter(ActiveSkill skill, WorldPoint point) {
                        handleAddCenter(skill, point);
                    }

                    @Override
                    public void onRemoveCenter(ActiveSkill skill, WorldPoint point) {
                        handleRemoveCenter(skill, point);
                    }

                    @Override
                    public void onAdjustRadius(ActiveSkill skill, WorldPoint point, int delta) {
                        handleAdjustRadius(skill, point, delta);
                    }
                });

        // Handlers aanmaken
        combatHandler = new CombatHandler(config, antiBan, paint, null);
        combatHandler.setTileMarkerManager(tileMarkerManager);
        woodcutterHandler = new WoodcutterHandler(config, antiBan, paint);
        woodcutterHandler.setTileMarkerManager(tileMarkerManager);
        miningHandler = new MiningHandler(config, antiBan, paint);
        miningHandler.setTileMarkerManager(tileMarkerManager);
        fishingHandler = new FishingHandler(config, antiBan, paint, this::stopBotAndLogoutForFishing);
        fishingHandler.setTileMarkerManager(tileMarkerManager);
        impsHandler = new ImpsHandler(config, antiBan, paint);
        impsHandler.setTileMarkerManager(tileMarkerManager);
        impsHandler.setQuestProgressConfigManager(stormConfigManager);
        vampireSlayerQuestHandler = new VampireSlayerQuestHandler(config, antiBan, paint, stormConfigManager);
        giantsHandler = new GiantsHandler(config, antiBan, paint);
        giantsHandler.setTileMarkerManager(tileMarkerManager);
        barbarianHandler = new BarbarianHandler(config, antiBan, paint);
        lootHandler = new LootHandler(config, antiBan, paint);
        lootHandler.setClientThread(clientThread);
        lootHandler.setConfigManager(stormConfigManager);
        lootHandler.setTileMarkerManager(tileMarkerManager);
        starterSkillHandler = new StarterSkillHandler(config, antiBan, paint);
        tutorialModeHandler = new TutorialModeHandler(paint, config);
        wireStarterHandoffCallback();

        // Centers laden en op handlers zetten
        loadCentersAndSetHandlers();
        captureCentersSnapshotFromConfig();
        copySnapCentersToMaster();
        updateMenuListenerCenters();

        activeSkill = resolveStartSkill();
        resetSwitchTimer();

        // === Panel registratie ===
        String webGuiUrl = config.webGuiUrl();
        panel = new CombatBotPanel(paint, config, stormConfigManager, webGuiUrl,
                this::requestSwitchNow, this::requestNextAccountNow, this::requestSellNow, this::requestLoginNow, this::requestPanelLogout,
                this::requestTestAutoLogin,
                () -> accountSwitcher.reload(), this::prepareLoginFromManagedAccount,
                this::resetAllBotStateFromPanel, this::emergencyStopAllFromPanel,
                this::requestTestPlayerLookupAntiban,
                this::requestTestLampHoverWidgets,
                this::requestTestHumanMicroMouse,
                this::requestTestFidgetBurst);

        // Maak een eenvoudig icoon (16x16 gouden zwaard)
        BufferedImage icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = icon.createGraphics();
        g.setColor(new java.awt.Color(255, 215, 0));
        g.fillRect(6, 0, 4, 12);
        g.fillRect(2, 10, 12, 3);
        g.dispose();

        navButton = NavigationButton.builder()
                .tooltip("Combat Bot")
                .icon(icon)
                .panel(panel)
                .priority(5)
                .build();
        clientToolbar.addNavigation(navButton);
        installGameplayMouseTraceListener();

        // === HTTP Server starten ===
        httpServer = new ConfigHttpServer(config, stormConfigManager);
        httpServer.start();
    }

    @Override
    public void shutDown() throws Exception {
        CombatBotRuntime.setActivePlugin(null);
        if (antiBan != null) antiBan.stopFidgetWorker();
        overlayManager.remove(overlay);
        overlayManager.remove(areaOverlay);
        overlayManager.remove(walkClickHighlightOverlay);
        overlayManager.remove(widgetHoverOverlay);
        overlayManager.remove(mouseDebugOverlay);
        saveTileMarkersToConfig();
        uninstallGameplayMouseTraceListener();

        if (panel != null) panel.stopTimer();
        if (navButton != null) clientToolbar.removeNavigation(navButton);
        if (httpServer != null) httpServer.stop();
    }

    // ===================== Rechtermuisklik Menu =====================

    @Subscribe
    public void onMenuOpened(MenuOpened event) {
        if (areaMenuListener != null) {
            areaMenuListener.setActiveSkill(activeSkill);
            areaMenuListener.onMenuOpened(event);
        }
    }

    private void installGameplayMouseTraceListener() {
        uninstallGameplayMouseTraceListener();
        if (client == null) {
            return;
        }
        Canvas canvas = client.getCanvas();
        if (canvas == null) {
            return;
        }
        gameplayMouseTraceLastMoveMs = 0L;
        gameplayMouseTraceLastEventMs = 0L;
        gameplayMouseTraceDragging = false;
        gameplayMouseTraceListener = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                logGameplayMouseTraceEvent("move", e, false);
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                logGameplayMouseTraceEvent("drag_move", e, true);
            }

            @Override
            public void mousePressed(MouseEvent e) {
                gameplayMouseTraceDragging = true;
                logGameplayMouseTraceEvent("press", e, false);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                logGameplayMouseTraceEvent("release", e, false);
                gameplayMouseTraceDragging = false;
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                logGameplayMouseTraceEvent("click", e, false);
            }
        };
        canvas.addMouseListener(gameplayMouseTraceListener);
        canvas.addMouseMotionListener(gameplayMouseTraceListener);
    }

    private void uninstallGameplayMouseTraceListener() {
        if (client == null || gameplayMouseTraceListener == null) {
            gameplayMouseTraceListener = null;
            return;
        }
        Canvas canvas = client.getCanvas();
        if (canvas != null) {
            canvas.removeMouseListener(gameplayMouseTraceListener);
            canvas.removeMouseMotionListener(gameplayMouseTraceListener);
        }
        gameplayMouseTraceListener = null;
    }

    private void logGameplayMouseTraceEvent(String type, MouseEvent e, boolean sampledMotion) {
        if (!config.gameplayMouseTraceLog() || e == null) {
            return;
        }
        if (config.gameplayMouseTraceOnlyWhenBotOff() && config.botEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (sampledMotion) {
            int sampleMs = Math.max(10, config.gameplayMouseTraceMoveSampleMs());
            if (gameplayMouseTraceLastMoveMs > 0 && now - gameplayMouseTraceLastMoveMs < sampleMs) {
                return;
            }
            gameplayMouseTraceLastMoveMs = now;
        }

        JsonObject j = new JsonObject();
        j.addProperty("t", now);
        j.addProperty("type", type);
        j.addProperty("x", e.getX());
        j.addProperty("y", e.getY());
        j.addProperty("button", e.getButton());
        j.addProperty("modsEx", e.getModifiersEx());
        j.addProperty("clickCount", e.getClickCount());
        j.addProperty("dragging", gameplayMouseTraceDragging || "drag_move".equals(type));
        j.addProperty("botEnabled", config.botEnabled());
        j.addProperty("dt", gameplayMouseTraceLastEventMs > 0 ? now - gameplayMouseTraceLastEventMs : 0L);
        gameplayMouseTraceLastEventMs = now;

        DebugLog.appendMouseTraceJsonLine(COMPACT_GSON.toJson(j));
    }

    /**
     * Logt gekozen menu-acties voor ML / replay-analyse: canvas-positie, optie, target, params.
     * NDJSON: {@code ~/.runelite/prive-logs/combat-bot-ml-clicks-YYYY-MM-DD.jsonl}; leesbare regels onder bron ML_CLICK.
     */
    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event) {
        // Walk-click overlay: registreer ELKE walk-actie (van bot of van mens) zodat de
        // overlay alle daadwerkelijk geklikte tiles toont met een count erop.
        // Dit is onafhankelijk van de ML-click logger hieronder.
        // Alleen actief als master + sub-toggle voor click-tiles aan staan.
        if (config.debugWalkClickOverlay() && config.debugWalkOverlayShowClickTiles()) {
            try {
                MenuAction ma = event.getMenuAction();
                boolean isWalk = ma == MenuAction.WALK
                        || (event.getMenuOption() != null
                                && event.getMenuOption().equalsIgnoreCase("Walk here"));
                if (isWalk) {
                    int sceneX = event.getParam0();
                    int sceneY = event.getParam1();
                    boolean wasMinimap = isMouseInMinimapArea();
                    WorldPoint clickWp = sceneToWorldClickTile(sceneX, sceneY);

                    // Sanity-check: als de gedecodeerde tile onrealistisch ver weg ligt
                    // (typisch bij minimap-clicks waar param0/param1 geen scene-coords zijn)
                    // dan plannen we een fallback via de actuele RL destination.
                    boolean decodeLooksOff = false;
                    try {
                        IPlayer lpz = Players.getLocal();
                        if (clickWp != null && lpz != null && lpz.getWorldLocation() != null) {
                            int dist = clickWp.distanceTo(lpz.getWorldLocation());
                            // Game-view scene = 104x104; alles > ~80 vanaf speler is verdacht.
                            if (dist > 80) {
                                decodeLooksOff = true;
                            }
                        }
                    } catch (Throwable ignored2) {
                    }

                    if (clickWp != null && !decodeLooksOff) {
                        MovementHelper.recordExternalWalkClick(clickWp);
                    }
                    if (wasMinimap || clickWp == null || decodeLooksOff) {
                        // Schedule een paar ticks fallback-capture op de echte RL destination.
                        pendingDestCaptureTicks = Math.max(pendingDestCaptureTicks, 4);
                    }

                    // Live debug-info zodat je in de Debug-tab ziet wat er gebeurt bij élke walk-klik
                    // (vooral handig om te zien wanneer er via de minimap geklikt wordt).
                    DebugLog.log("WalkClickDbg", String.format(
                            "WALK click: minimap=%s p0=%d p1=%d → wp=%s%s",
                            wasMinimap ? "JA" : "nee",
                            sceneX, sceneY,
                            clickWp == null ? "(decode FAIL)" : (clickWp.getX() + "," + clickWp.getY() + ",p" + clickWp.getPlane()),
                            decodeLooksOff ? " [decode_lijkt_fout]" : ""));
                }
            } catch (Throwable ignored) {
            }
        }

        if (!config.gameplayMlClickLog()) {
            return;
        }
        GameState gs = client.getGameState();
        if (gs != GameState.LOGGED_IN) {
            return;
        }
        Boolean authentic = tryMenuOptionClickedAuthentic(event);
        if (config.gameplayMlClickLogOnlyAuthentic() && Boolean.FALSE.equals(authentic)) {
            return;
        }
        net.runelite.api.Point mp = client.getMouseCanvasPosition();
        int cx = -1;
        int cy = -1;
        if (mp != null) {
            cx = (int) Math.round(mp.getX());
            cy = (int) Math.round(mp.getY());
        }

        JsonObject j = new JsonObject();
        j.addProperty("t", System.currentTimeMillis());
        if (authentic != null) {
            j.addProperty("auth", authentic);
        }
        j.addProperty("cx", cx);
        j.addProperty("cy", cy);
        j.addProperty("gameState", gs.name());

        String op = event.getMenuOption();
        String target = event.getMenuTarget();
        j.addProperty("option", op == null ? "" : op);
        j.addProperty("target", target == null ? "" : Text.removeTags(target));

        try {
            if (event.getMenuAction() != null) {
                j.addProperty("action", event.getMenuAction().name());
            }
        } catch (Throwable ignored) {
        }
        j.addProperty("id", event.getId());
        j.addProperty("p0", event.getParam0());
        j.addProperty("p1", event.getParam1());

        try {
            MenuEntry me = event.getMenuEntry();
            if (me != null) {
                j.addProperty("itemId", me.getItemId());
            }
        } catch (Throwable ignored) {
        }

        String json = COMPACT_GSON.toJson(j);
        DebugLog.appendMlClicksJsonLine(json);

        String human = String.format(Locale.ROOT,
                "cx=%d cy=%d | %s | %s | action=%s id=%d p0=%d p1=%d",
                cx, cy,
                op == null ? "" : op,
                target == null ? "" : Text.removeTags(target),
                safeMenuActionName(event),
                event.getId(), event.getParam0(), event.getParam1());
        DebugLog.log("ML_CLICK", human);
    }

    private static String safeMenuActionName(MenuOptionClicked event) {
        try {
            return event.getMenuAction() == null ? "" : event.getMenuAction().name();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /**
     * Converteer params (uit MenuOptionClicked.getParam0/getParam1 voor WALK acties) naar
     * een wereld-tile. Detecteert automatisch of de waarden tile-coords (0..103) zijn of
     * LocalPoint-pixel-coords (~0..13312, 128 px per tile) — dat laatste komt voor bij
     * walks die via {@code client.invokeMenuAction(...)} door de SDK gedispatcht worden.
     * Geeft {@code null} als niet ingelogd of buiten bereik.
     */
    private WorldPoint sceneToWorldClickTile(int sceneX, int sceneY) {
        if (client == null || client.getGameState() != GameState.LOGGED_IN) {
            return null;
        }
        if (sceneX < 0 || sceneY < 0) {
            return null;
        }
        try {
            // Heuristiek: als params buiten scene-tile bereik vallen → behandel als
            // LocalPoint pixel-coords (1 tile = Perspective.LOCAL_TILE_SIZE = 128 px).
            int sx = sceneX;
            int sy = sceneY;
            if (sx > 103 || sy > 103) {
                int ts = net.runelite.api.Perspective.LOCAL_TILE_SIZE; // 128
                sx = sceneX / ts;
                sy = sceneY / ts;
            }
            if (sx > 103 || sy > 103) {
                // Nog te groot na conversie → onbruikbaar
                return null;
            }
            WorldView wv = client.getTopLevelWorldView();
            int plane = wv != null ? wv.getPlane() : client.getPlane();
            return WorldPoint.fromScene(client, sx, sy, plane);
        } catch (Throwable ignored) {
            try {
                int baseX = client.getBaseX();
                int baseY = client.getBaseY();
                int plane = client.getPlane();
                return new WorldPoint(baseX + sceneX, baseY + sceneY, plane);
            } catch (Throwable ignored2) {
                return null;
            }
        }
    }

    /**
     * Detecteert of de muis op het moment van een click in het minimap-vlak van het scherm zat.
     * Heuristiek dekt zowel <i>fixed</i> als <i>resizable</i> layout: minimap zit altijd
     * rechts-bovenin en is ~210 px breed.
     */
    private boolean isMouseInMinimapArea() {
        try {
            net.runelite.api.Point mp = client.getMouseCanvasPosition();
            if (mp == null) return false;
            int cw = client.getCanvasWidth();
            int x = (int) mp.getX();
            int y = (int) mp.getY();
            // Minimap area: rechtsbovenin. Met wat marge zodat ook compass/orbs meetellen.
            return x >= (cw - 250) && y >= 0 && y <= 200;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Voor walk-click overlay: bepaal of de speler momenteel een NPC volgt/aanvalt.
     * Tijdens NPC-follow hercomputeert de client elke tick het pad naar de bewegende NPC,
     * waardoor {@link #localDestinationWorldPoint()} steeds verandert. Die updates zijn
     * GEEN echte walk-clicks en moeten dus niet als click-tiles geregistreerd worden
     * (anders krijg je een blauw spoor achter elke wegrennende vijand).
     */
    private boolean isInteractingWithNpcForOverlay() {
        try {
            IPlayer lp = Players.getLocal();
            if (lp == null) return false;
            Object target = lp.getInteracting();
            return target instanceof INPC;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * RuneLite houdt tijdens lopen de actieve destination bij. Storm movement triggert niet
     * altijd een normale {@link MenuOptionClicked}, dus dit vangt ook SDK-interne walk-clicks.
     *
     * BELANGRIJK: {@code client.getLocalDestinationLocation()} geeft een {@code LocalPoint}
     * waarvan {@code getX()}/{@code getY()} de positie in <i>pixels</i> teruggeven (0–13312),
     * niet tile-coords. We gebruiken {@code WorldPoint.fromLocal} dat de pixel→tile→world
     * conversie correct uitvoert.
     */
    private WorldPoint localDestinationWorldPoint() {
        if (client == null || client.getGameState() != GameState.LOGGED_IN) {
            return null;
        }
        try {
            java.lang.reflect.Method m = client.getClass().getMethod("getLocalDestinationLocation");
            Object dst = m.invoke(client);
            if (dst == null) {
                return null;
            }
            if (dst instanceof net.runelite.api.coords.LocalPoint) {
                return WorldPoint.fromLocal(client, (net.runelite.api.coords.LocalPoint) dst);
            }
            // Fallback voor SDK's die een ander type teruggeven: probeer getSceneX/getSceneY
            // (tile-coords); geef NIET getX/getY door — dat zijn pixel-coords en geeft fantoom-WP's.
            try {
                java.lang.reflect.Method getSx = dst.getClass().getMethod("getSceneX");
                java.lang.reflect.Method getSy = dst.getClass().getMethod("getSceneY");
                Object osx = getSx.invoke(dst);
                Object osy = getSy.invoke(dst);
                if (osx instanceof Number && osy instanceof Number) {
                    return sceneToWorldClickTile(((Number) osx).intValue(), ((Number) osy).intValue());
                }
            } catch (Throwable ignored2) {
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Pakt de huidige hotspot-stuck instellingen uit de config en geeft ze door aan
     * {@link MovementHelper}. Wordt aangeroepen bij startUp en elke loop-tick zodat
     * config-changes direct doorwerken zonder restart.
     */
    private void applyWalkTileStuckConfig() {
        if (config == null) {
            return;
        }
        if (!config.debugWalkClickOverlay()
                || !config.debugWalkOverlayShowClickTiles()
                || !config.debugWalkStuckHotspotEnabled()) {
            MovementHelper.setStuckHotspotConfig(null, 0, 60);
            return;
        }
        int thr = Math.max(2, config.debugWalkStuckHotspotThreshold());
        int win = Math.max(10, config.debugWalkStuckHotspotWindowSec());
        MovementHelper.setStuckHotspotConfig((info, threshold, windowSec) -> {
            if (info == null || info.point == null) return;
            DebugLog.log("WalkHotspot", "⚠ Hotspot-stuck: tile " + info.point.getX() + ","
                    + info.point.getY() + " (plane " + info.point.getPlane() + ") kreeg "
                    + threshold + "+ clicks in " + windowSec + "s "
                    + "(totaal clicks=" + info.count + ", traversals=" + info.traversals + ")");
        }, thr, win);
    }

    /** RuneLite: {@code isAuthentic()} op nieuwere clients; op oudere API {@code null} (= geen filter). */
    private static Boolean tryMenuOptionClickedAuthentic(MenuOptionClicked event) {
        if (event == null) {
            return null;
        }
        try {
            java.lang.reflect.Method m = event.getClass().getMethod("isAuthentic");
            Object o = m.invoke(event);
            return o instanceof Boolean ? (Boolean) o : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ===================== Chat Message Handler =====================

    @Subscribe
    public void onChatMessage(ChatMessage event) {
        if (event.getType() != ChatMessageType.GAMEMESSAGE
                && event.getType() != ChatMessageType.SPAM
                && event.getType() != ChatMessageType.PUBLICCHAT
                && event.getType() != ChatMessageType.MODCHAT
                && event.getType() != ChatMessageType.FRIENDSCHAT) return;
        String msg = event.getMessage();
        if (msg == null) return;
        String lower = Text.removeTags(msg).trim().toLowerCase();
        maybeHandleDraynorBotMention(event, lower);
        if (lower.contains("you just advanced a") || lower.contains("you've just advanced a")) {
            try {
                Dialog.continueSpace();
            } catch (Throwable ignored) {
            }
        }

        // Stuur door naar combat handler voor rune/ammo detectie
        if (combatHandler != null) {
            combatHandler.onChatMessage(msg);
        }
        if (impsHandler != null) {
            impsHandler.onChatMessage(msg);
        }
        if (woodcutterHandler != null) {
            woodcutterHandler.onGameMessage(msg);
        }
        if (starterSkillHandler != null) {
            starterSkillHandler.onGameMessage(msg);
        }
        if (fishingHandler != null) {
            fishingHandler.onGameMessage(msg);
        }
        if (vampireSlayerQuestHandler != null) {
            vampireSlayerQuestHandler.onChatMessage(msg);
        }
    }

    /**
     * Periodieke widget-dump naar stdout (IDE/console) — zie {@link CombatBotConfig#widgetInspectorIntervalSeconds()}.
     */
    @Subscribe
    public void onGameTick(GameTick event) {
        boolean trackClicks = config.debugWalkClickOverlay()
                && config.debugWalkOverlayShowClickTiles()
                && Game.isLoggedIn();
        if (trackClicks) {
            try {
                WorldPoint dst = localDestinationWorldPoint();
                boolean changed = dst != null && !dst.equals(lastWalkDestinationWp);
                // Tijdens een pending-capture window registeren we de destination ook als
                // hij niet veranderd is — handig na een minimap-klik waar de scene-decode faalde.
                boolean forceCapture = pendingDestCaptureTicks > 0 && dst != null;
                // NPC-follow / combat-volgen genereert élke tick een nieuwe destination omdat
                // de client het pad naar de bewegende NPC hercomputeert. Dat is GEEN echte
                // walk-click — als we dit zouden recorden krijg je een spoor van blauwe tiles
                // achter elke wegrennende vijand. We skippen daarom alle destination-updates
                // terwijl de speler een NPC als interact-target heeft.
                boolean followingNpc = isInteractingWithNpcForOverlay();
                if (changed) {
                    if (followingNpc) {
                        // Stilzwijgend bijwerken zonder te recorden — anders triggert de volgende
                        // tick weer "changed" en zou de eerstvolgende ondanks de filter alsnog
                        // worden gemarkeerd.
                        lastWalkDestinationWp = dst;
                    } else {
                        MovementHelper.recordExternalWalkClick(dst);
                        lastWalkDestinationWp = dst;
                        DebugLog.log("WalkClickDbg", "destination → " + dst.getX() + "," + dst.getY()
                                + ",p" + dst.getPlane() + (forceCapture ? " [pendingFallback]" : ""));
                    }
                } else if (forceCapture && !followingNpc) {
                    // Re-record alleen als het niet al de laatste was om dubbele entries te voorkomen.
                    if (lastWalkDestinationWp == null || !dst.equals(lastWalkDestinationWp)) {
                        MovementHelper.recordExternalWalkClick(dst);
                        lastWalkDestinationWp = dst;
                        DebugLog.log("WalkClickDbg", "destination [forced] → " + dst.getX() + ","
                                + dst.getY() + ",p" + dst.getPlane());
                    }
                } else if (dst == null) {
                    lastWalkDestinationWp = null;
                }
                if (pendingDestCaptureTicks > 0) {
                    pendingDestCaptureTicks--;
                }
            } catch (Throwable ignored) {
            }
        } else if (lastWalkDestinationWp != null) {
            lastWalkDestinationWp = null;
            pendingDestCaptureTicks = 0;
        }

        // Walk-klik overlay: log iedere tile-overgang van de speler als path-traversal
        // (naast de "Walk here" klikken die we al via onMenuOptionClicked vangen).
        // Hierdoor zie je het hele pad oplopen, niet alleen de eind-klik.
        // Alleen actief als master + sub-toggle voor pad-tiles aan staan.
        boolean trackPath = config.debugWalkClickOverlay()
                && config.debugWalkOverlayShowPathTiles()
                && Game.isLoggedIn();
        if (trackPath) {
            try {
                IPlayer lp = Players.getLocal();
                if (lp != null) {
                    WorldPoint pos = lp.getWorldLocation();
                    if (pos != null && !pos.equals(lastPathTraversalWp)) {
                        MovementHelper.recordPathTileTraversal(pos);
                        lastPathTraversalWp = pos;
                    }
                }
            } catch (Throwable ignored) {
            }
        } else if (lastPathTraversalWp != null) {
            // Overlay/sub-toggle uit → reset tracker zodat hij straks bij heractivatie fris start
            lastPathTraversalWp = null;
        }

        int interval = config.widgetInspectorIntervalSeconds();
        if (interval <= 0 || !Game.isLoggedIn()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastWidgetInspectorDumpMs < interval * 1000L) {
            return;
        }
        lastWidgetInspectorDumpMs = now;
        int max = config.widgetInspectorMaxLines();
        max = Math.max(50, Math.min(max, 2000));
        try {
            WidgetDebugHelper.dumpVisibleWidgetsToDebug(
                    config.widgetInspectorFilter(), max, config.widgetInspectorSkipAlreadyPrinted());
        } catch (Throwable t) {
            System.err.println("[CombatBot] Widget inspector: " + t.getMessage());
        }
    }

    // ===================== Center Beheer =====================

    private void handleAddCenter(ActiveSkill skill, WorldPoint point) {
        String configKey = getCenterConfigKey(skill);
        String current = getCenterString(skill);
        String updated = CenterManager.addCenter(current, point, CenterManager.DEFAULT_RADIUS);

        stormConfigManager.setConfiguration("combatbot", configKey, updated);
        refreshCentersForSkill(skill, updated);
        rememberCenterSnapshot(skill, updated);
        syncCenterChangeAcrossManagedRow(skill, updated);

        if (skill == activeSkill) {
            setHandlerCenter(skill, point, CenterManager.DEFAULT_RADIUS);
            areaOverlay.setActiveCenterForSkill(skill, point);
        }

        paint.setLastAntiBanAction("✅ Center: " + getSkillName(skill) + " @ " + point.getX() + "," + point.getY());
    }

    private void handleRemoveCenter(ActiveSkill skill, WorldPoint point) {
        String configKey = getCenterConfigKey(skill);
        String current = getCenterString(skill);
        String updated = CenterManager.removeNearest(current, point);

        stormConfigManager.setConfiguration("combatbot", configKey, updated);
        refreshCentersForSkill(skill, updated);
        rememberCenterSnapshot(skill, updated);
        syncCenterChangeAcrossManagedRow(skill, updated);

        if (skill == activeSkill) {
            CenterManager.Center newCenter = CenterManager.pickRandom(updated);
            if (newCenter != null) {
                setHandlerCenter(skill, newCenter.point, newCenter.radius);
                areaOverlay.setActiveCenterForSkill(skill, newCenter.point);
            } else {
                setHandlerCenter(skill, null, 0);
                areaOverlay.setActiveCenterForSkill(skill, null);
            }
        }

        paint.setLastAntiBanAction("🗑 Center verwijderd: " + getSkillName(skill));
    }

    private void handleAdjustRadius(ActiveSkill skill, WorldPoint point, int delta) {
        String configKey = getCenterConfigKey(skill);
        String current = getCenterString(skill);
        String updated = CenterManager.adjustRadius(current, point, delta);

        stormConfigManager.setConfiguration("combatbot", configKey, updated);
        refreshCentersForSkill(skill, updated);
        rememberCenterSnapshot(skill, updated);
        syncCenterChangeAcrossManagedRow(skill, updated);

        if (skill == activeSkill) {
            CenterManager.Center nearest = CenterManager.findNearest(updated, point);
            if (nearest != null) {
                setHandlerCenter(skill, nearest.point, nearest.radius);
            }
        }

        CenterManager.Center adj = CenterManager.findNearest(updated, point);
        paint.setLastAntiBanAction("📐 Radius: " + getSkillName(skill) + " r=" + (adj != null ? adj.radius : "?"));
    }

    /**
     * Wordt aangeroepen na ELKE handmatige center-wijziging (in-game right-click én RuneLite-panel),
     * zodat de in-memory master en — indien van toepassing — de huidige managed-account row
     * niet stale raken. Anders wordt de nieuwe center bij de volgende {@link #applyManagedCentersForDisplayName}
     * weer overschreven door de oude master/subset en lijkt het of het toevoegen "niet is opgeslagen".
     */
    private void syncCenterChangeAcrossManagedRow(ActiveSkill skill, String updated) {
        if (applyingManagedCenters) {
            return;
        }
        String value = nullToEmptyCenters(updated);

        // 1) Master in-memory bijwerken. Dit is de bron-van-waarheid voor accounts die de globale
        //    lijst gebruiken (useGlobal* = true + lege subset).
        switch (skill) {
            case COMBAT: masterCombatCenters = value; break;
            case WOODCUTTING: masterWcCenters = value; break;
            case MINING: masterMiningCenters = value; break;
            case FISHING: masterFishingCenters = value; break;
            case IMPS: masterImpsCenters = value; break;
            default: return;
        }

        // 2) Als de huidige account een eigen subset gebruikt voor deze skill, die subset ook
        //    bijwerken en de blob persist'en — anders gaat de nieuwe center verloren bij de
        //    volgende account-switch.
        try {
            String label = tryGetLocalRsn();
            if ((label == null || label.trim().isEmpty())
                    && accountSwitcher != null && accountSwitcher.getAccountCount() > 0) {
                label = accountSwitcher.getCurrentAccountName();
            }
            if (label == null || label.trim().isEmpty()) {
                return;
            }
            String blob = config.managedJagexAccountsBlob();
            if (blob == null || blob.trim().isEmpty()) {
                return;
            }
            java.util.List<ManagedJagexAccountsStore.ManagedJagexAccountRow> rows =
                    new java.util.ArrayList<>(ManagedJagexAccountsStore.parseRows(blob));
            boolean changed = false;
            for (ManagedJagexAccountsStore.ManagedJagexAccountRow r : rows) {
                if (r == null || r.displayName == null) continue;
                if (!r.displayName.equalsIgnoreCase(label.trim())) continue;
                boolean rowHasSubset = false;
                switch (skill) {
                    case COMBAT:
                        rowHasSubset = r.useGlobalCombatCenters && r.combatCenters != null && !r.combatCenters.trim().isEmpty();
                        if (rowHasSubset) { r.combatCenters = value; changed = true; }
                        break;
                    case WOODCUTTING:
                        rowHasSubset = r.useGlobalWcCenters && r.wcCenters != null && !r.wcCenters.trim().isEmpty();
                        if (rowHasSubset) { r.wcCenters = value; changed = true; }
                        break;
                    case MINING:
                        rowHasSubset = r.useGlobalMiningCenters && r.miningCenters != null && !r.miningCenters.trim().isEmpty();
                        if (rowHasSubset) { r.miningCenters = value; changed = true; }
                        break;
                    case FISHING:
                        rowHasSubset = r.useGlobalFishingCenters && r.fishingCenters != null && !r.fishingCenters.trim().isEmpty();
                        if (rowHasSubset) { r.fishingCenters = value; changed = true; }
                        break;
                    case IMPS:
                        rowHasSubset = r.useGlobalImpsCenters && r.impsCenters != null && !r.impsCenters.trim().isEmpty();
                        if (rowHasSubset) { r.impsCenters = value; changed = true; }
                        break;
                    default: break;
                }
                break;
            }
            if (changed) {
                ManagedJagexAccountsStore.persist(stormConfigManager, rows);
                DebugLog.log("Centers", "Persistente per-account subset bijgewerkt voor "
                        + label + " (" + getSkillName(skill) + ")");
            }
        } catch (Throwable t) {
            DebugLog.log("Centers", "syncCenterChangeAcrossManagedRow fout: " + t.getMessage());
        }
    }

    private void refreshCentersForSkill(ActiveSkill skill, String centersData) {
        areaOverlay.setCentersForSkill(skill, CenterManager.parse(centersData));
        updateMenuListenerCenters();
    }

    private void updateMenuListenerCenters() {
        if (areaMenuListener != null) {
            areaMenuListener.updateCenterStrings(
                    config.combatCenters(),
                    config.wcCenters(),
                    config.miningCenters(),
                    config.fishingCenters(),
                    config.impsCenters()
            );
        }
    }

    private void loadCentersAndSetHandlers() {
        loadAndSetForSkill(ActiveSkill.COMBAT, config.combatCenters());
        loadAndSetForSkill(ActiveSkill.WOODCUTTING, config.wcCenters());
        loadAndSetForSkill(ActiveSkill.MINING, config.miningCenters());
        loadAndSetForSkill(ActiveSkill.FISHING, config.fishingCenters());
        loadAndSetForSkill(ActiveSkill.IMPS, config.impsCenters());
        areaOverlay.setActiveCenterForSkill(ActiveSkill.LOOT, LootHandler.getActivityCenter());
    }

    private static String nullToEmptyCenters(String s) {
        return s != null ? s : "";
    }

    private void captureCentersSnapshotFromConfig() {
        snapCombatCenters = nullToEmptyCenters(config.combatCenters());
        snapWcCenters = nullToEmptyCenters(config.wcCenters());
        snapMiningCenters = nullToEmptyCenters(config.miningCenters());
        snapFishingCenters = nullToEmptyCenters(config.fishingCenters());
        snapImpsCenters = nullToEmptyCenters(config.impsCenters());
    }

    private void copySnapCentersToMaster() {
        masterCombatCenters = snapCombatCenters;
        masterWcCenters = snapWcCenters;
        masterMiningCenters = snapMiningCenters;
        masterFishingCenters = snapFishingCenters;
        masterImpsCenters = snapImpsCenters;
    }

    private void rememberCenterSnapshot(ActiveSkill skill, String serialized) {
        String v = nullToEmptyCenters(serialized);
        switch (skill) {
            case COMBAT: snapCombatCenters = v; break;
            case WOODCUTTING: snapWcCenters = v; break;
            case MINING: snapMiningCenters = v; break;
            case FISHING: snapFishingCenters = v; break;
            case IMPS: snapImpsCenters = v; break;
            default: break;
        }
    }

    /** Wijzigingen via RuneLite-panel of web HTTP → overlay + handlers (willekeurig actief center opnieuw). */
    private void refreshCentersFromExternalConfigChanges() {
        boolean any = false;
        String c;
        if (!Objects.equals((c = nullToEmptyCenters(config.combatCenters())), snapCombatCenters)) {
            snapCombatCenters = c;
            if (!applyingManagedCenters) {
                masterCombatCenters = c;
                syncCenterChangeAcrossManagedRow(ActiveSkill.COMBAT, c);
            }
            loadAndSetForSkill(ActiveSkill.COMBAT, c);
            any = true;
        }
        if (!Objects.equals((c = nullToEmptyCenters(config.wcCenters())), snapWcCenters)) {
            snapWcCenters = c;
            if (!applyingManagedCenters) {
                masterWcCenters = c;
                syncCenterChangeAcrossManagedRow(ActiveSkill.WOODCUTTING, c);
            }
            loadAndSetForSkill(ActiveSkill.WOODCUTTING, c);
            any = true;
        }
        if (!Objects.equals((c = nullToEmptyCenters(config.miningCenters())), snapMiningCenters)) {
            snapMiningCenters = c;
            if (!applyingManagedCenters) {
                masterMiningCenters = c;
                syncCenterChangeAcrossManagedRow(ActiveSkill.MINING, c);
            }
            loadAndSetForSkill(ActiveSkill.MINING, c);
            any = true;
        }
        if (!Objects.equals((c = nullToEmptyCenters(config.fishingCenters())), snapFishingCenters)) {
            snapFishingCenters = c;
            if (!applyingManagedCenters) {
                masterFishingCenters = c;
                syncCenterChangeAcrossManagedRow(ActiveSkill.FISHING, c);
            }
            loadAndSetForSkill(ActiveSkill.FISHING, c);
            any = true;
        }
        if (!Objects.equals((c = nullToEmptyCenters(config.impsCenters())), snapImpsCenters)) {
            snapImpsCenters = c;
            if (!applyingManagedCenters) {
                masterImpsCenters = c;
                syncCenterChangeAcrossManagedRow(ActiveSkill.IMPS, c);
            }
            loadAndSetForSkill(ActiveSkill.IMPS, c);
            any = true;
        }
        if (any) {
            updateMenuListenerCenters();
        }
    }

    /**
     * Twee fishing-centers (Barbarian + Draynor): bij level-up vanaf 20 overschakelen naar Barbarian-centers.
     */
    private void syncFishingCenterForTrainingLevel() {
        if (fishingHandler == null) return;
        if (countSafeActiveCentersForSkill(ActiveSkill.FISHING, config.fishingCenters()) < 2) return;
        CenterManager.Center ideal = pickCenterForSkillWithSafety(ActiveSkill.FISHING, config.fishingCenters());
        if (ideal == null) return;
        WorldPoint cur = fishingHandler.getFishingSpot();
        int fishLvl = Skills.getLevel(Skill.FISHING);
        String filtered = filterCentersBySafety(ActiveSkill.FISHING, config.fishingCenters());
        if (cur != null && CenterManager.fishingTrainingCenterStillValid(filtered, cur, fishLvl)) {
            return;
        }
        fishingHandler.setActiveCenter(ideal.point, ideal.radius);
        areaOverlay.setActiveCenterForSkill(ActiveSkill.FISHING, ideal.point);
        String label = ideal.name != null && !ideal.name.isEmpty() ? ideal.name : (ideal.point.getX() + "," + ideal.point.getY());
        paint.setLastAntiBanAction("🎣 Center: " + label + " (fish " + fishLvl + ")");
    }

    private void loadAndSetForSkill(ActiveSkill skill, String centersData) {
        List<CenterManager.Center> centers = CenterManager.parse(centersData);
        areaOverlay.setCentersForSkill(skill, centers);
        CenterManager.Center chosen = pickCenterForSkillWithSafety(skill, centersData);
        if (chosen != null) {
            setHandlerCenter(skill, chosen.point, chosen.radius);
            areaOverlay.setActiveCenterForSkill(skill, chosen.point);
        } else {
            setHandlerCenter(skill, null, 0);
            areaOverlay.setActiveCenterForSkill(skill, null);
        }
    }

    private void setHandlerCenter(ActiveSkill skill, WorldPoint center, int radius) {
        switch (skill) {
            case COMBAT:
                if (combatHandler != null) combatHandler.setActiveCenter(center, radius);
                break;
            case WOODCUTTING:
                if (woodcutterHandler != null) woodcutterHandler.setActiveCenter(center, radius);
                break;
            case MINING:
                if (miningHandler != null) miningHandler.setActiveCenter(center, radius);
                break;
            case FISHING:
                if (fishingHandler != null) fishingHandler.setActiveCenter(center, radius);
                break;
            case IMPS:
                if (impsHandler != null) impsHandler.setActiveCenter(center, radius);
                break;
        }
    }

    /** Na nieuwe ImpsHandler: actief center uit impsCenters (of leeg = config Hunting X/Y). */
    private void syncImpsHandlerActiveCenter() {
        if (impsHandler == null) return;
        impsHandler.setTileMarkerManager(tileMarkerManager);
        CenterManager.Center chosen = CenterManager.pickRandom(config.impsCenters());
        if (chosen != null) {
            impsHandler.setActiveCenter(chosen.point, chosen.radius);
            areaOverlay.setActiveCenterForSkill(ActiveSkill.IMPS, chosen.point);
        } else {
            impsHandler.setActiveCenter(null, 0);
            areaOverlay.setActiveCenterForSkill(ActiveSkill.IMPS, null);
        }
    }

    private String getCenterConfigKey(ActiveSkill skill) {
        switch (skill) {
            case COMBAT:      return "combatCenters";
            case WOODCUTTING: return "wcCenters";
            case MINING:      return "miningCenters";
            case FISHING:     return "fishingCenters";
            case IMPS:        return "impsCenters";
            default:          return "combatCenters";
        }
    }

    private String getCenterString(ActiveSkill skill) {
        switch (skill) {
            case COMBAT:      return config.combatCenters();
            case WOODCUTTING: return config.wcCenters();
            case MINING:      return config.miningCenters();
            case FISHING:     return config.fishingCenters();
            case IMPS:        return config.impsCenters();
            default:          return "";
        }
    }

    private void wireStarterHandoffCallback() {
        if (starterSkillHandler == null) {
            return;
        }
        starterSkillHandler.setOnHandoffToImps(() -> {
            stormConfigManager.setConfiguration("combatbot", "startSkill", CombatBotConfig.StartSkill.COMBAT.name());
            stormConfigManager.setConfiguration("combatbot", "impsMode", "true");
            impsHandler = new ImpsHandler(config, antiBan, paint);
            impsHandler.setTileMarkerManager(tileMarkerManager);
            impsHandler.setQuestProgressConfigManager(stormConfigManager);
            impsHandler.resetState();
            impsHandler.beginStarterSkillBridge(() -> {
                stormConfigManager.setConfiguration("combatbot", "botEnabled", "false");
                wasBotEnabled = false;
                invokeGameLogoutOnClientThread();
            });
            syncImpsHandlerActiveCenter();
            impsHandler.startGearPreparation();
            activeSkill = ActiveSkill.IMPS;
            paint.setActiveSkill(getSkillName(activeSkill));
            resetSwitchTimer();
        });
    }

    // ===================== MAIN LOOP =====================

    @Override
    public int loop() {
        MovementHelper.setForceLargeStepMode(config.impsForceLargeSteps());
        MovementHelper.setDebugWalkHighlightEnabled(config.debugWalkClickOverlay());
        applyWalkTileStuckConfig();
        if (Game.isLoggedIn()) {
            coldLoginAccountListPrimed = false;
        }
        if (!config.botEnabled()) {
            coldLoginAccountListPrimed = false;
        }
        if (!config.botEnabled() && wasBotEnabled && !clearedLocalProgressOnStop) {
            resetAllAccountLocalProgressOnStop();
        }
        if (config.botEnabled()) {
            clearedLocalProgressOnStop = false;
        }
        // "Log in" knop: altijd verwerken (ook als bot uitstaat), zodat je uitgelogd kunt starten
        if (loginNowRequested || config.loginNow()) {
            loginNowRequested = false;
            stormConfigManager.setConfiguration("combatbot", "loginNow", "false");
            if (sameAccountRelogger != null && sameAccountRelogger.tryLoginNow()) {
                return 120;
            }
        }

        // Pauze/Stop moet echt stil zijn: geen account-switch/relog/skill-cleanup meer door laten lopen.
        if (!config.botEnabled()) {
            coldLoginAccountListPrimed = false;
            startupInventoryChecked = false;
            wasBotEnabled = false;
            resetIdleStuckMonitor();
            prevTickBankOpen = false;
            bankSnapshotPrevOpen = false;
            paint.setCurrentStatus("⏸ Bot gepauzeerd/gestopt — Start om verder te gaan");
            if (Game.isLoggedIn() && panelLogoutRequested) {
                int panelOff = tickPanelLogoutWhileBotStopped();
                if (panelOff > 0) {
                    return panelOff;
                }
            }
            return 1000;
        }

        // Actieve account-wissel: altijd eerst (anders mist check() op sommige paden — 2e wissel bleef hangen).
        if (config.accountSwitchEnabled() && accountSwitcher != null && accountSwitcher.isSwitching()) {
            // Vóór vroege return: anders bereikt loop() syncPanelLogoutSuppressToHandlers() niet → combat stopt niet → gate blokkeert logout.
            if (Game.isLoggedIn()) {
                syncPanelLogoutSuppressToHandlers();
            }
            long nowSw = System.currentTimeMillis();
            if (nowSw - lastAccountListReloadTime >= ACCOUNT_LIST_RELOAD_INTERVAL_MS) {
                lastAccountListReloadTime = nowSw;
                accountSwitcher.reload();
            }
            int switchDelay = accountSwitcher.check();
            if (switchDelay > 0) {
                return switchDelay;
            }
        }

        // Bot aan maar nog niet ingelogd: eerst inlog-flow (geen hiscore, geen fresh start, geen random events).
        if (config.botEnabled() && !Game.isLoggedIn()) {
            resetIdleStuckMonitor();
            if (handleClientUpdatedPromptStop()) {
                return 1500;
            }
            handleManagedAccountSessionTracking();
            long nowLogin = System.currentTimeMillis();
            if (!coldLoginAccountListPrimed) {
                accountSwitcher.reload();
                lastAccountListReloadTime = nowLogin;
                coldLoginAccountListPrimed = true;
            } else if (nowLogin - lastAccountListReloadTime >= ACCOUNT_LIST_RELOAD_INTERVAL_MS) {
                lastAccountListReloadTime = nowLogin;
                accountSwitcher.reload();
            }
            // Geen dubbele check() tijdens wissel — dat loopt in het blok hierboven
            if (!accountSwitcher.isSwitching()) {
                int accountDelayEarly = accountSwitcher.check();
                if (accountDelayEarly > 0) {
                    return accountDelayEarly;
                }
            }
            if (sameAccountRelogger != null && (config.botEnabled() || sameAccountRelogger.isRelogFlowActive())) {
                int relogDelay = sameAccountRelogger.check();
                maybeSendDiscordRelogPing();
                if (relogDelay > 0) {
                    return relogDelay;
                }
            }
            if (sameAccountRelogger == null || !sameAccountRelogger.isRelogFlowActive()) {
                lastDiscordRelogPingMs = 0L;
            }
            if (config.accountSwitchEnabled() && accountSwitcher.getAccountCount() == 0
                    && sameAccountRelogger != null && sameAccountRelogger.isIdle() && !sameAccountRelogger.isRelogFlowActive()
                    && Game.isOnLoginScreen() && !Game.isLoggedIn()) {
                sameAccountRelogger.startTestAutoLoginFromLoginScreen();
                return 80 + random.nextInt(70);
            }
            if (Game.isOnLoginScreen()) {
                paint.setCurrentStatus("🔐 Inloggen...");
                return 60 + random.nextInt(60);
            }
            paint.setCurrentStatus("⏳ Wacht op client...");
            return 120 + random.nextInt(100);
        }

        handleManagedAccountSessionTracking();
        observeFailureNudgeBankWindowEdge();

        // Re-log: tijdens een lopende cyclus ook door laten lopen als de bot uit staat, zodat we weer kunnen inloggen.
        // Met bot uit + IDLE geen check (voorkomt onverwachte logout door alleen de timer).
        if (sameAccountRelogger != null && (config.botEnabled() || sameAccountRelogger.isRelogFlowActive())) {
            int relogDelay = sameAccountRelogger.check();
            maybeSendDiscordRelogPing();
            if (relogDelay > 0) {
                return relogDelay;
            }
        }
        if (sameAccountRelogger == null || !sameAccountRelogger.isRelogFlowActive()) {
            lastDiscordRelogPingMs = 0L;
        }

        if (Game.isLoggedIn()) {
            syncPanelLogoutSuppressToHandlers();
            monitorRepeatedLoopDebugSignature();
        }

        int deathFailoverDelay = maybeHandleDeathFailover();
        if (deathFailoverDelay > 0) {
            return deathFailoverDelay;
        }

        // Fresh start: als bot net weer is aangezet, reset alles (alleen als de client al ingelogd is).
        if (!wasBotEnabled && Game.isLoggedIn()) {
            wasBotEnabled = true;
            // One-shot triggers kunnen persisted zijn; reset ze bij start om onverwachte skill jumps te voorkomen.
            switchNowRequested = false;
            nextAccountRequested = false;
            sellNowRequested = false;
            stormConfigManager.setConfiguration("combatbot", "switchNow", "false");
            stormConfigManager.setConfiguration("combatbot", "impsSellNow", "false");
            performFreshStart();
            runStartupInventoryCheck();
            startupInventoryChecked = true;
            paint.setCurrentStatus("▶ Bot gestart — fresh start!");
            return 1000;
        }

        if (!startupInventoryChecked) {
            runStartupInventoryCheck();
            startupInventoryChecked = true;
        }

        if (handleNoUsableSkillForCurrentAccount()) {
            return 900;
        }

        int bankCalibDelay = handlePerAccountBankCalibrationAndSnapshot();
        if (bankCalibDelay > 0) {
            return bankCalibDelay;
        }

        int idleStuckDelay = handleIdleStuckRecovery();
        if (idleStuckDelay > 0) {
            return idleStuckDelay;
        }

        // Random events: Genie aanspreken, andere random events dismissen.
        int randomEventDelay = handleRandomEventInteraction();
        if (randomEventDelay > 0) return randomEventDelay;

        // Cleanup voor skill switch actief
        if (cleaningUpBeforeSwitch) {
            int result = handleCleanupBeforeSwitch();
            if (result > 0) return result;
            // Cleanup klaar → voltooi de switch
            finishSkillSwitch();
            return (int) SWITCH_COOLDOWN_MS;
        }

        // Account switch: alleen IDLE-timer (rotatie) — actieve wissel/uitloggen zie blok vóór managed-session.
        if (!accountSwitcher.isSwitching()) {
            long now = System.currentTimeMillis();
            if (now - lastAccountListReloadTime >= ACCOUNT_LIST_RELOAD_INTERVAL_MS) {
                lastAccountListReloadTime = now;
                accountSwitcher.reload();
            }
            int accountDelay = accountSwitcher.check();
            if (accountDelay > 0) {
                return accountDelay;
            }
        }

        // Rotatie aan maar geen Storm-accountregels (lege lijst) → inloggen via re-log / test-auto-login-pad.
        if (config.accountSwitchEnabled() && accountSwitcher.getAccountCount() == 0
                && sameAccountRelogger != null && sameAccountRelogger.isIdle() && !sameAccountRelogger.isRelogFlowActive()
                && Game.isOnLoginScreen() && !Game.isLoggedIn()) {
            sameAccountRelogger.startTestAutoLoginFromLoginScreen();
            return 80 + random.nextInt(70);
        }

        // Geen skill-handlers tot de client echt ingelogd is (fallback; normaal vangt de vroege login-route dit af).
        if (!Game.isLoggedIn()) {
            if (Game.isOnLoginScreen()) {
                paint.setCurrentStatus("🔐 Inloggen...");
                return 60 + random.nextInt(60);
            }
            paint.setCurrentStatus("⏳ Wacht op client...");
            return 120 + random.nextInt(100);
        }

        maybeApplyBarbLootStartMagicGate();

        enforceStarterStartSkillIfConfigured();

        maybeUpdateAccountStatSnapshot();
        maybeRunPeriodicHiscoreBanCheck();

        // Anti-ban check
        int antiBanDelay = antiBan.check();
        if (antiBanDelay > 0) return antiBanDelay;

        // Tile marker preset knoppen
        handleTileMarkerButtons();

        refreshCentersFromExternalConfigChanges();

        // Combat: geen food meer (bank + GE) → Combat uit rotatie halen en/of bot pauzeren
        if (activeSkill == ActiveSkill.COMBAT && combatHandler != null && combatHandler.isOutOfFoodNoGeRestock()) {
            combatHandler.resetOutOfFoodNoGeRestock();

            // Zijn er andere skills actief? Dan Combat uit rotatie en switchen.
            if (combatHandler.hasOtherActiveSkills()) {
                paint.setLastAntiBanAction("⛔ Combat gestopt: geen food (bank+GE)");
                // Combat uit de rotatie halen totdat gebruiker het weer aanzet
                stormConfigManager.setConfiguration("combatbot", "combatInRotation", "false");
                // Kies direct een andere skill
                initiateSkillSwitch();
                return 1000;
            } else {
                // Geen andere skills → start re-log flow i.p.v. lang wachten op dezelfde wereld
                paint.setLastAntiBanAction("⛔ Combat: geen food (bank+GE) — re-log gestart");
                if (sameAccountRelogger != null) {
                    sameAccountRelogger.forceLogoutNow();
                }
                // Korte delay; de echte logout+pauze wordt door SameAccountRelogger.check() afgehandeld
                return 2000;
            }
        }

        // "Next account" knop: alleen account-rotatie, geen skill-switch fallback.
        if (nextAccountRequested) {
            nextAccountRequested = false;
            if (config.accountSwitchEnabled()
                    && accountSwitcher != null
                    && accountSwitcher.getAccountCount() >= 2) {
                boolean switching = accountSwitcher.requestImmediateSwitch("⚡ Handmatige switch / next account");
                if (switching) {
                    paint.setLastAntiBanAction("⚡ Volgende account gestart");
                    return 250;
                }
            }
            paint.setLastAntiBanAction("⚠ Next account niet beschikbaar");
            return 500;
        }

        // "Switch Now" knop (config + directe request): altijd skill-switch.
        if (switchNowRequested || config.switchNow()) {
            switchNowRequested = false;
            stormConfigManager.setConfiguration("combatbot", "switchNow", "false");
            initiateSkillSwitch();
            resetIdleStuckMonitor();
            return 600;
        }

        // "Sell now" knop: zelfde gedrag als Switch Now — direct verwerken, eventueel naar Imps voor GE-verkoop
        if (sellNowRequested || config.impsSellNow()) {
            sellNowRequested = false;
            stormConfigManager.setConfiguration("combatbot", "impsSellNow", "false");
            if (activeSkill != ActiveSkill.IMPS) {
                activeSkill = ActiveSkill.IMPS;
                impsHandler = new ImpsHandler(config, antiBan, paint);
                impsHandler.setQuestProgressConfigManager(stormConfigManager);
                syncImpsHandlerActiveCenter();
            }
            impsHandler.startGeSellNow();
            paint.setLastAntiBanAction("🛒 GE verkoop gestart");
            return 500;
        }

        // Minimale switch-cooldown (100ms) — daarna start handler direct met lopen/banken
        if (System.currentTimeMillis() < switchCooldownUntil) {
            paint.setCurrentStatus("🔄 Gewisseld naar " + getSkillName(activeSkill) + "...");
            return 150;
        }

        String rsnVampFix = tryGetLocalRsn();
        if (rsnVampFix != null && activeSkill == ActiveSkill.VAMPIRE_SLAYER
                && AccountQuestProgressStore.isVampireSlayerComplete(config, rsnVampFix)) {
            activeSkill = resolveStartSkill();
            paint.setActiveSkill(getSkillName(activeSkill));
        }

        if (isVampireSlayerLoopPriorityActive()) {
            String rsnQuest = tryGetLocalRsn();
            if (rsnQuest != null && !AccountQuestProgressStore.isVampireSlayerComplete(config, rsnQuest)) {
                if (hasMinimumHpForVampireSlayer()) {
                    if (vampireSlayerQuestHandler == null) {
                        vampireSlayerQuestHandler = new VampireSlayerQuestHandler(config, antiBan, paint, stormConfigManager);
                    }
                    activeSkill = ActiveSkill.VAMPIRE_SLAYER;
                    int qDelay = vampireSlayerQuestHandler.loop();
                    if (handleVampireSlayerOutOfFood()) {
                        return 1000;
                    }
                    if (handleVampireSlayerStakeBlocked()) {
                        return 800;
                    }
                    paint.setActiveSkill(getSkillName(activeSkill));
                    return finalizePanelLogoutIfReady(qDelay);
                } else {
                    paint.setLastAntiBanAction("Vampyre Slayer overgeslagen: HP level < " + VAMPIRE_SLAYER_MIN_HP_LEVEL);
                }
            }
        }

        if (activeSkill == ActiveSkill.BARBARIAN && !config.barbarianMode()) {
            ActiveSkill was = activeSkill;
            activeSkill = resolveStartSkill();
            resetHandlerForSkill(activeSkill);
            resetSwitchTimer();
            paint.setActiveSkill(getSkillName(activeSkill));
            paint.setLastAntiBanAction("Barbarian uit → " + getSkillName(activeSkill) + " (was " + getSkillName(was) + ")");
            DebugLog.log("StartSkill", "barbarianMode off: " + was + " -> " + activeSkill);
        }

        maybeEnterTutorialPreSkill();
        checkSkillRotation();

        if (activeSkill == ActiveSkill.TUT) {
            int tutDelay = tutorialModeHandler != null ? tutorialModeHandler.loop() : 700;
            if (handleTutorialPostLoop()) {
                maybeSendDiscordScreenshot();
                return finalizePanelLogoutIfReady(1000);
            }
            paint.setRotationEnabled(false);
            paint.setActiveSkill(getSkillName(activeSkill));
            maybeSendDiscordScreenshot();
            return finalizePanelLogoutIfReady(tutDelay);
        }

        if (activeSkill == ActiveSkill.STARTER) {
            boolean rotationEnabled = getEnabledSkills().size() >= 2
                    || config.barbarianMode() || config.wcEnabled() || config.miningEnabled() || config.fishingEnabled()
                    || config.barbLootEnabled() || barbLootSessionActive;
            paint.setRotationEnabled(rotationEnabled);
            paint.setActiveSkill(getSkillName(activeSkill));
            if (skillSwitchTime != null && rotationEnabled) {
                long elapsedSec = Duration.between(skillSwitchTime, Instant.now()).getSeconds();
                long remainingSec = Math.max(0, nextSwitchSeconds - elapsedSec);
                paint.setSecondsUntilSwitch(remainingSec);
                paint.setTotalSwitchSeconds(nextSwitchSeconds);
            }
            areaOverlay.setActiveSkill(activeSkill);
            int starterRecoveryDelay = handleStarterStatusLearningRecovery();
            if (starterRecoveryDelay > 0) {
                maybeSendDiscordScreenshot();
                return finalizePanelLogoutIfReady(starterRecoveryDelay);
            }
            int starterDelay = starterSkillHandler != null ? starterSkillHandler.loop() : 600;
            maybeSendDiscordScreenshot();
            return finalizePanelLogoutIfReady(starterDelay);
        }

        List<ActiveSkill> enabledSkillsNow = getEnabledSkills();
        if (enabledSkillsNow.isEmpty()) {
            paint.setCurrentStatus("⏸ Geen actieve skills in rotatie");
            paint.setActiveSkill("Geen");
            return 1500;
        }

        boolean rotationEnabled = getEnabledSkills().size() >= 2
                || config.barbarianMode() || config.wcEnabled() || config.miningEnabled() || config.fishingEnabled()
                || config.barbLootEnabled() || barbLootSessionActive;
        paint.setRotationEnabled(rotationEnabled);
        paint.setActiveSkill(getSkillName(activeSkill));

        if (skillSwitchTime != null && rotationEnabled) {
            long elapsedSec = Duration.between(skillSwitchTime, Instant.now()).getSeconds();
            long remainingSec = Math.max(0, nextSwitchSeconds - elapsedSec);
            paint.setSecondsUntilSwitch(remainingSec);
            paint.setTotalSwitchSeconds(nextSwitchSeconds);
        }

        areaOverlay.setActiveSkill(activeSkill);
        enforceConfiguredMeleeAttackStyle();

        int handlerDelay;
        if (activeSkill != ActiveSkill.LOOT) {
            paint.setBarbLootGeBankTripProgress(0, 0);
        }
        switch (activeSkill) {
            case WOODCUTTING: {
                int wcDelay = woodcutterHandler.loop();
                WoodcutterHandler.ImpsCashFarmRequest req = woodcutterHandler.pollImpsCashFarmRequest();
                boolean impsAvailable = effectiveImpsInRotation();
                if (req != null && config.wcAxeCashViaImpsEnabled() && impsAvailable) {
                    wcImpsCashFarmActive = true;
                    wcImpsCashFarmTripsTarget = Math.max(1, req.estimatedImpsTrips);
                    wcImpsCashFarmTripsStart = impsHandler.getBankTripCount();
                    activeSkill = ActiveSkill.IMPS;
                    paint.setActiveSkill(getSkillName(activeSkill));
                    paint.setCurrentStatus("WC→Imps cash-farm: " + req.missingAxeName + " (x" + wcImpsCashFarmTripsTarget + " trips)");
                    paint.setLastAntiBanAction("WC: " + req.currentCoinsGp + "gp < " + req.estimatedAxeCostGp
                            + "gp voor " + req.missingAxeName + " → Imps x" + wcImpsCashFarmTripsTarget);
                    DebugLog.log("Woodcutting", "Switch naar Imps cash-farm: axe=" + req.missingAxeName
                            + ", coins=" + req.currentCoinsGp + ", shortfall=" + req.estimatedShortfallGp
                            + ", trips=" + wcImpsCashFarmTripsTarget);
                    handlerDelay = 800;
                } else {
                    handlerDelay = wcDelay;
                }
                break;
            }
            case MINING:      handlerDelay = miningHandler.loop(); break;
            case FISHING:
                syncFishingCenterForTrainingLevel();
                handlerDelay = fishingHandler.loop();
                FishingHandler.ImpsCashFarmRequest fishReq = fishingHandler.pollImpsCashFarmRequest();
                boolean impsAvailableForCash = effectiveImpsInRotation();
                if (fishReq != null && impsAvailableForCash) {
                    moneyImpsCashFarmActive = true;
                    moneyImpsCashFarmReturnSkill = ActiveSkill.FISHING;
                    moneyImpsCashFarmTripsTarget = Math.max(1, fishReq.estimatedImpsTrips);
                    moneyImpsCashFarmTripsStart = impsHandler.getBankTripCount();
                    activeSkill = ActiveSkill.IMPS;
                    paint.setActiveSkill(getSkillName(activeSkill));
                    paint.setCurrentStatus("Fishing→Imps cash-farm: " + fishReq.missingItemName + " (x" + moneyImpsCashFarmTripsTarget + " trips)");
                    paint.setLastAntiBanAction("Fishing: " + fishReq.availableCoinsGp + "gp < " + fishReq.neededCoinsGp
                            + "gp voor " + fishReq.missingItemName + " → Imps x" + moneyImpsCashFarmTripsTarget);
                    DebugLog.log("Fishing", "Switch naar Imps cash-farm: item=" + fishReq.missingItemName
                            + ", coins=" + fishReq.availableCoinsGp + ", needed=" + fishReq.neededCoinsGp
                            + ", trips=" + moneyImpsCashFarmTripsTarget);
                    handlerDelay = 800;
                }
                break;
            case IMPS: {
                int impsDelay = impsHandler.loop();
                if (wcImpsCashFarmActive) {
                    int tripsDone = Math.max(0, impsHandler.getBankTripCount() - wcImpsCashFarmTripsStart);
                    int tripsLeft = Math.max(0, wcImpsCashFarmTripsTarget - tripsDone);
                    paint.setCurrentStatus("Imps cash-farm voor WC axe: nog " + tripsLeft + " trip(s)");
                    if (tripsDone >= wcImpsCashFarmTripsTarget) {
                        wcImpsCashFarmActive = false;
                        wcImpsCashFarmTripsTarget = 0;
                        wcImpsCashFarmTripsStart = 0;
                        activeSkill = ActiveSkill.WOODCUTTING;
                        resetSwitchTimer();
                        paint.setActiveSkill(getSkillName(activeSkill));
                        paint.setLastAntiBanAction("Imps cash-farm klaar → terug naar WC");
                        handlerDelay = 800;
                        break;
                    }
                }
                if (moneyImpsCashFarmActive) {
                    int tripsDone = Math.max(0, impsHandler.getBankTripCount() - moneyImpsCashFarmTripsStart);
                    int tripsLeft = Math.max(0, moneyImpsCashFarmTripsTarget - tripsDone);
                    paint.setCurrentStatus("Imps cash-farm voor coins: nog " + tripsLeft + " trip(s)");
                    if (tripsDone >= moneyImpsCashFarmTripsTarget) {
                        ActiveSkill returnSkill = moneyImpsCashFarmReturnSkill;
                        moneyImpsCashFarmActive = false;
                        moneyImpsCashFarmTripsTarget = 0;
                        moneyImpsCashFarmTripsStart = 0;
                        activeSkill = returnSkill;
                        resetSwitchTimer();
                        paint.setActiveSkill(getSkillName(activeSkill));
                        paint.setLastAntiBanAction("Imps cash-farm klaar → terug naar " + getSkillName(activeSkill));
                        handlerDelay = 800;
                        break;
                    }
                }
                if (impsHandler.shouldSwitchToNormalCombat()) {
                    wcImpsCashFarmActive = false;
                    wcImpsCashFarmTripsTarget = 0;
                    wcImpsCashFarmTripsStart = 0;
                    moneyImpsCashFarmActive = false;
                    moneyImpsCashFarmTripsTarget = 0;
                    moneyImpsCashFarmTripsStart = 0;
                    String deactivated = CenterManager.deactivateAll(config.impsCenters());
                    stormConfigManager.setConfiguration("combatbot", "impsCenters", deactivated);
                    rememberCenterSnapshot(ActiveSkill.IMPS, deactivated);
                    refreshCentersForSkill(ActiveSkill.IMPS, deactivated);
                    activeSkill = ActiveSkill.COMBAT;
                    impsHandler = new ImpsHandler(config, antiBan, paint);
                    impsHandler.setQuestProgressConfigManager(stormConfigManager);
                    syncImpsHandlerActiveCenter();
                    paint.setLastAntiBanAction("⚠ Imps: geen coins → normaal combat");
                    handlerDelay = 2000;
                } else {
                    handlerDelay = impsDelay;
                }
                break;
            }
            case BARBARIAN:
                handlerDelay = barbarianHandler.loop();
                break;
            case GIANTS: handlerDelay = giantsHandler.loop(); break;
            case LOOT:   handlerDelay = lootHandler.loop(); break;
            case VAMPIRE_SLAYER:
                if (vampireSlayerQuestHandler == null) {
                    vampireSlayerQuestHandler = new VampireSlayerQuestHandler(config, antiBan, paint, stormConfigManager);
                }
                handlerDelay = vampireSlayerQuestHandler.loop();
                if (handleVampireSlayerOutOfFood()) {
                    return 1000;
                }
                if (handleVampireSlayerStakeBlocked()) {
                    return 800;
                }
                break;
            case COMBAT:
            default: handlerDelay = combatHandler.loop(); break;
        }

        maybeSendDiscordScreenshot();
        return finalizePanelLogoutIfReady(handlerDelay);
    }

    private boolean handleVampireSlayerOutOfFood() {
        if (vampireSlayerQuestHandler == null || !vampireSlayerQuestHandler.isOutOfFoodForQuest()) {
            return false;
        }
        vampireSlayerQuestHandler.resetOutOfFoodForQuest();
        paint.setLastAntiBanAction("⛔ Vampire Slayer: geen food — uitloggen/account wisselen");
        DebugLog.log("QUEST", "Vampire Slayer: geen food beschikbaar, niet ingelogd blijven");

        boolean switching = accountSwitcher != null
                && accountSwitcher.requestImmediateSwitch("🔄 Vampire Slayer: geen food — volgende account");
        if (!switching) {
            stormConfigManager.setConfiguration("combatbot", "botEnabled", "false");
            wasBotEnabled = false;
            invokeGameLogoutOnClientThread();
        }
        return true;
    }

    /**
     * Stake-block recovery: als Dr Harlow de stake niet meer geeft en die ook niet in de bank
     * ligt, kan de quest niet verder. We gaan dan NIET eindeloos wachten. Stappen:
     *   1. Vampire Slayer quest-mode UIT zetten (anders pakt de priority-loop hem opnieuw).
     *   2. Probeer een andere skill in de huidige rotation. Als er minstens één non-VS skill
     *      enabled is, switch daar naartoe.
     *   3. Geen alternatieve skill → account-switch.
     *   4. Geen accounts beschikbaar → logout + botEnabled=false.
     */
    private boolean handleVampireSlayerStakeBlocked() {
        if (vampireSlayerQuestHandler == null || !vampireSlayerQuestHandler.isQuestBlockedStakeMissing()) {
            return false;
        }
        vampireSlayerQuestHandler.resetQuestBlockedStakeMissing();

        // 1) Quest-mode uit zodat de priority-loop niet meteen weer VS pakt.
        try {
            stormConfigManager.setConfiguration("combatbot", "vampireSlayerQuestMode", "false");
        } catch (Throwable ignored) {
        }

        // 2) Andere skill in rotation? Filter VS er uit; als er iets anders is, switch direct.
        ActiveSkill fallback = null;
        try {
            List<ActiveSkill> enabled = getEnabledSkills();
            for (ActiveSkill s : enabled) {
                if (s != ActiveSkill.VAMPIRE_SLAYER) {
                    fallback = s;
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
        if (fallback != null) {
            paint.setLastAntiBanAction("⛔ Stake ontbreekt — VS uit, door naar " + getSkillName(fallback));
            DebugLog.log("QUEST", "Stake-blocked: VS uitgeschakeld, switch naar " + fallback);
            activeSkill = fallback;
            resetHandlerForSkill(activeSkill);
            resetSwitchTimer();
            paint.setActiveSkill(getSkillName(activeSkill));
            return true;
        }

        // 3) Geen alternatief → account-switch.
        boolean switching = accountSwitcher != null
                && accountSwitcher.requestImmediateSwitch("🔄 Vampire Slayer geblokkeerd (stake) — volgende account");
        if (switching) {
            paint.setLastAntiBanAction("⛔ Stake ontbreekt — geen andere skill, account-switch");
            DebugLog.log("QUEST", "Stake-blocked: geen alt skill, account-switch gestart");
            return true;
        }

        // 4) Geen accounts → logout.
        paint.setLastAntiBanAction("⛔ Stake ontbreekt — geen alt skill/account, logout");
        DebugLog.log("QUEST", "Stake-blocked: geen alt skill/account beschikbaar, bot uit + logout");
        stormConfigManager.setConfiguration("combatbot", "botEnabled", "false");
        wasBotEnabled = false;
        invokeGameLogoutOnClientThread();
        return true;
    }

    private static AttackStyle fourthOrThirdAttackStyleEnum() {
        try {
            return AttackStyle.valueOf("FOURTH");
        } catch (IllegalArgumentException e) {
            return AttackStyle.THIRD;
        }
    }

    private AttackStyle toAttackStyle(CombatBotConfig.MeleeTrainingStyle style) {
        if (style == null) {
            return AttackStyle.UNKNOWN;
        }
        switch (style) {
            case ATTACK:
                return AttackStyle.FIRST;
            case STRENGTH:
                return AttackStyle.SECOND;
            case DEFENCE:
                return fourthOrThirdAttackStyleEnum();
            default:
                return AttackStyle.UNKNOWN;
        }
    }

    private CombatBotConfig.MeleeTrainingStyle resolveConfiguredMeleeStyleForActiveSkill() {
        CombatBotConfig.MeleeTrainingStyle accountTargetStyle = resolveAccountTargetMeleeStyleForActiveSkill();
        if (accountTargetStyle != CombatBotConfig.MeleeTrainingStyle.BALANCED) {
            return accountTargetStyle;
        }
        switch (activeSkill) {
            case COMBAT:
                return config.combatStyle() == CombatBotConfig.ImpsCombatStyle.MELEE
                        ? config.combatMeleeTrainingStyle() : CombatBotConfig.MeleeTrainingStyle.BALANCED;
            case IMPS: {
                String rsn = tryGetLocalRsn();
                CombatBotConfig.ImpsCombatStyle st = ManagedJagexAccountsStore.resolveImpsCombatStyleForDisplayName(
                        config, rsn != null ? rsn : "");
                return st == CombatBotConfig.ImpsCombatStyle.MELEE
                        ? config.impsMeleeTrainingStyle() : CombatBotConfig.MeleeTrainingStyle.BALANCED;
            }
            case GIANTS: {
                String rsnG = tryGetLocalRsn();
                CombatBotConfig.ImpsCombatStyle gst = ManagedJagexAccountsStore.resolveGiantsCombatStyleForDisplayName(
                        config, rsnG != null ? rsnG : "");
                return gst == CombatBotConfig.ImpsCombatStyle.MELEE
                        ? config.giantsMeleeTrainingStyle() : CombatBotConfig.MeleeTrainingStyle.BALANCED;
            }
            default:
                return CombatBotConfig.MeleeTrainingStyle.BALANCED;
        }
    }

    private CombatBotConfig.MeleeTrainingStyle resolveAccountTargetMeleeStyleForActiveSkill() {
        boolean meleeMode;
        switch (activeSkill) {
            case COMBAT:
                meleeMode = config.combatStyle() == CombatBotConfig.ImpsCombatStyle.MELEE;
                break;
            case IMPS: {
                String rsnM = tryGetLocalRsn();
                meleeMode = ManagedJagexAccountsStore.resolveImpsCombatStyleForDisplayName(
                        config, rsnM != null ? rsnM : "") == CombatBotConfig.ImpsCombatStyle.MELEE;
                break;
            }
            case GIANTS: {
                String rsnGm = tryGetLocalRsn();
                meleeMode = ManagedJagexAccountsStore.resolveGiantsCombatStyleForDisplayName(
                        config, rsnGm != null ? rsnGm : "") == CombatBotConfig.ImpsCombatStyle.MELEE;
                break;
            }
            default:
                meleeMode = false;
        }
        if (!meleeMode) {
            return CombatBotConfig.MeleeTrainingStyle.BALANCED;
        }
        String rsn = tryGetLocalRsn();
        ManagedJagexAccountsStore.ManagedJagexAccountRow row = findManagedRowByDisplayName(rsn);
        if (row == null) {
            return CombatBotConfig.MeleeTrainingStyle.BALANCED;
        }
        int atk = Skills.getLevel(Skill.ATTACK);
        int str = Skills.getLevel(Skill.STRENGTH);
        int def = Skills.getLevel(Skill.DEFENCE);
        return pickMeleeStyleByPriority(row, atk, str, def);
    }

    /**
     * Bepaalt op basis van {@code row.targetMeleePriority} welke skill als eerste getraind wordt
     * zolang die nog onder zijn target zit. "Lowest" varianten kiezen dynamisch op basis van
     * absolute lvl of percentage onder target. Bij gelijkstand: vaste tiebreaker Att → Str → Def.
     */
    private CombatBotConfig.MeleeTrainingStyle pickMeleeStyleByPriority(
            ManagedJagexAccountsStore.ManagedJagexAccountRow row, int atk, int str, int def) {
        boolean atkBelow = row.targetAttackLevel > 0 && atk < row.targetAttackLevel;
        boolean strBelow = row.targetStrengthLevel > 0 && str < row.targetStrengthLevel;
        boolean defBelow = row.targetDefenceLevel > 0 && def < row.targetDefenceLevel;
        if (!atkBelow && !strBelow && !defBelow) {
            return CombatBotConfig.MeleeTrainingStyle.BALANCED;
        }
        String mode = row.targetMeleePriority == null ? "" : row.targetMeleePriority.trim().toUpperCase();
        switch (mode) {
            case "LOWEST_FIRST":
                return pickByLowestAbsolute(atk, str, def, atkBelow, strBelow, defBelow);
            case "LOWEST_PCT_FIRST":
                return pickByLowestPercent(row, atk, str, def, atkBelow, strBelow, defBelow);
            case "STR_ATT_DEF":
                if (strBelow) return CombatBotConfig.MeleeTrainingStyle.STRENGTH;
                if (atkBelow) return CombatBotConfig.MeleeTrainingStyle.ATTACK;
                return CombatBotConfig.MeleeTrainingStyle.DEFENCE;
            case "DEF_ATT_STR":
                if (defBelow) return CombatBotConfig.MeleeTrainingStyle.DEFENCE;
                if (atkBelow) return CombatBotConfig.MeleeTrainingStyle.ATTACK;
                return CombatBotConfig.MeleeTrainingStyle.STRENGTH;
            case "":
            case "ATT_STR_DEF":
            default:
                if (atkBelow) return CombatBotConfig.MeleeTrainingStyle.ATTACK;
                if (strBelow) return CombatBotConfig.MeleeTrainingStyle.STRENGTH;
                return CombatBotConfig.MeleeTrainingStyle.DEFENCE;
        }
    }

    private CombatBotConfig.MeleeTrainingStyle pickByLowestAbsolute(
            int atk, int str, int def, boolean atkBelow, boolean strBelow, boolean defBelow) {
        int bestLvl = Integer.MAX_VALUE;
        CombatBotConfig.MeleeTrainingStyle best = CombatBotConfig.MeleeTrainingStyle.BALANCED;
        if (atkBelow && atk < bestLvl) { bestLvl = atk; best = CombatBotConfig.MeleeTrainingStyle.ATTACK; }
        if (strBelow && str < bestLvl) { bestLvl = str; best = CombatBotConfig.MeleeTrainingStyle.STRENGTH; }
        if (defBelow && def < bestLvl) { best = CombatBotConfig.MeleeTrainingStyle.DEFENCE; }
        return best;
    }

    private CombatBotConfig.MeleeTrainingStyle pickByLowestPercent(
            ManagedJagexAccountsStore.ManagedJagexAccountRow row, int atk, int str, int def,
            boolean atkBelow, boolean strBelow, boolean defBelow) {
        double bestPct = Double.MAX_VALUE;
        CombatBotConfig.MeleeTrainingStyle best = CombatBotConfig.MeleeTrainingStyle.BALANCED;
        if (atkBelow) {
            double pct = (double) atk / Math.max(1, row.targetAttackLevel);
            if (pct < bestPct) { bestPct = pct; best = CombatBotConfig.MeleeTrainingStyle.ATTACK; }
        }
        if (strBelow) {
            double pct = (double) str / Math.max(1, row.targetStrengthLevel);
            if (pct < bestPct) { bestPct = pct; best = CombatBotConfig.MeleeTrainingStyle.STRENGTH; }
        }
        if (defBelow) {
            double pct = (double) def / Math.max(1, row.targetDefenceLevel);
            if (pct < bestPct) { best = CombatBotConfig.MeleeTrainingStyle.DEFENCE; }
        }
        return best;
    }

    private boolean activeContextWantsRangedCombat() {
        switch (activeSkill) {
            case COMBAT:
                return config.combatStyle() == CombatBotConfig.ImpsCombatStyle.RANGED;
            case IMPS: {
                String rsn = tryGetLocalRsn();
                return ManagedJagexAccountsStore.resolveImpsCombatStyleForDisplayName(
                        config, rsn != null ? rsn : "") == CombatBotConfig.ImpsCombatStyle.RANGED;
            }
            case GIANTS: {
                String rsnGr = tryGetLocalRsn();
                return ManagedJagexAccountsStore.resolveGiantsCombatStyleForDisplayName(
                        config, rsnGr != null ? rsnGr : "") == CombatBotConfig.ImpsCombatStyle.RANGED;
            }
            default:
                return false;
        }
    }

    private boolean hasRangedWeaponEquippedForStyleSwitch() {
        try {
            var list = Equipment.getAll(item -> {
                if (item == null || item.getName() == null) {
                    return false;
                }
                String name = item.getName().toLowerCase(Locale.ROOT);
                return name.contains("shortbow") || name.contains("longbow") || name.contains("crossbow")
                        || name.contains("ballista") || name.contains("blowpipe") || name.contains("bow");
            });
            return list != null && !list.isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    private void enforceConfiguredMeleeAttackStyle() {
        if (activeSkill == ActiveSkill.STARTER) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastMeleeStyleSwitchMs < 900) {
            return;
        }
        boolean useRangedRapid = activeContextWantsRangedCombat() && hasRangedWeaponEquippedForStyleSwitch();
        AttackStyle wanted;
        if (useRangedRapid) {
            wanted = AttackStyle.SECOND;
        } else {
            CombatBotConfig.MeleeTrainingStyle wantedCfg = resolveConfiguredMeleeStyleForActiveSkill();
            wanted = toAttackStyle(wantedCfg);
            if (wanted == AttackStyle.UNKNOWN) {
                return;
            }
        }
        try {
            ICombat combat = Static.getCombat();
            if (combat == null) {
                return;
            }
            AttackStyle cur = combat.getAttackStyle();
            if (cur == wanted) {
                return;
            }
            combat.setAttackStyle(wanted);
            lastMeleeStyleSwitchMs = now;
            DebugLog.log("MeleeStyle", "Style set to " + wanted + " for " + activeSkill
                    + (useRangedRapid ? " (Ranged Rapid)" : ""));
        } catch (Throwable ignored) {
        }
    }

    // ===================== CLEANUP VOOR SKILL SWITCH =====================

    /**
     * Start het cleanup-proces: drop of bank items vóór de skill switch.
     * Items worden menselijk gedropt (met kleine vertragingen).
     */
    private void initiateSkillSwitch() {
        List<ActiveSkill> enabledSkills = getEnabledSkills();
        if (enabledSkills.isEmpty()) {
            paint.setCurrentStatus("⏸ Geen actieve skills in rotatie");
            return;
        }
        if (enabledSkills.size() == 1) {
            ActiveSkill only = enabledSkills.get(0);
            if (activeSkill != only) {
                activeSkill = only;
                performSkillSwitchInternal();
            }
            return;
        }

        pendingSkill = getNextSkill(enabledSkills);

        // Als we van IMPS weggaan, eerst beads/talisman banken
        if (activeSkill == ActiveSkill.IMPS && impsHandler.hasDepositItems()) {
            impsHandler.startBankingBeforeRotation();
            cleaningUpBeforeSwitch = true;
            paint.setCurrentStatus("🧹 Imps: Beads/talisman banken voor wissel...");
            return;
        }

        // Check of er items zijn om op te ruimen
        if (hasSkillItems(activeSkill) && !Inventory.isEmpty()) {
            cleaningUpBeforeSwitch = true;
            paint.setCurrentStatus("🧹 Inventory opruimen voor wissel...");
        } else {
            // Geen cleanup nodig, direct switchen
            activeSkill = pendingSkill;
            performSkillSwitchInternal();
        }
    }

    /**
     * Afhandeling van cleanup: drop items menselijk (1 voor 1 met random delays).
     * Als banken aanstaat, loop naar bank en bank alles.
     */
    private int handleCleanupBeforeSwitch() {
        // Geen items meer → klaar
        if (!hasSkillItems(activeSkill) && !(activeSkill == ActiveSkill.IMPS && impsHandler.isBankingBeforeRotation())) {
            return 0; // 0 = klaar
        }

        // IMPS: gebruik ImpsHandler's eigen banking flow (deposit box bij Port Sarim)
        if (activeSkill == ActiveSkill.IMPS && impsHandler.isBankingBeforeRotation()) {
            int result = impsHandler.loop();
            if (!impsHandler.isBankingBeforeRotation()) {
                // Banking klaar
                return 0;
            }
            return result;
        }

        // Als banken aanstaat voor de huidige skill, ga banken
        boolean shouldBank = shouldBankForSkill(activeSkill);

        if (shouldBank) {
            if (Bank.isOpen()) {
                // Deposit items 1-voor-1 (menselijker, voorkomt bank loop bij lege inv)
                java.util.List<net.storm.api.domain.items.IInventoryItem> skillItems = getSkillItemsToClean(activeSkill);
                if (skillItems != null && !skillItems.isEmpty()) {
                    java.util.Collections.shuffle(skillItems, random);
                    for (net.storm.api.domain.items.IInventoryItem item : skillItems) {
                        if (item != null && item.getName() != null) {
                            if (item.getId() == GENIE_LAMP_ITEM_ID) {
                                continue;
                            }
                            Bank.depositAll(item.getName());
                            HumanBanking.pauseBetweenActions();
                        }
                    }
                }
                HumanBanking.pauseBeforeClose();
                Bank.close();
                HumanBanking.pauseAfterClose();
                paint.setLastAntiBanAction("✓ Inventory gebankt voor wissel");
                return 0; // klaar
            }
            paint.setCurrentStatus("🏦 Lopen naar bank voor skill wissel...");
            // Eerst dichtbij komen: loop naar dichtstbijzijnde F2P-veilige bank
            if (BankHelper.interactIfNearby()) {
                return 1500;
            }
            if (BankHelper.walkToNearestFullBank()) {
                return 800;
            }
            // Geen bank gevonden — probeer SDK open als laatste (wacht + nudge bij timeout)
            BankHelper.openSdkBankAndWait();
            return 2000;
        }

        // Drop modus: drop items 1 voor 1 (menselijk)
        paint.setCurrentStatus("🗑 Items droppen voor skill wissel...");
        var items = getSkillItemsToClean(activeSkill);
        if (items != null && !items.isEmpty()) {
            // Drop 1-3 items per tick (menselijk patroon)
            int dropCount = 1 + random.nextInt(3);
            for (int i = 0; i < Math.min(dropCount, items.size()); i++) {
                items.get(i).interact("Drop");
                try { Thread.sleep(80 + random.nextInt(200)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            return 300 + random.nextInt(400); // Wacht even tussen batches
        }

        return 0; // Geen items meer
    }

    private void finishSkillSwitch() {
        cleaningUpBeforeSwitch = false;
        activeSkill = pendingSkill;
        pendingSkill = null;
        performSkillSwitchInternal();
    }

    /** Check of de huidige skill items in de inventory heeft. */
    private boolean hasSkillItems(ActiveSkill skill) {
        switch (skill) {
            case WOODCUTTING:
                return Inventory.getFirst(item ->
                        item.getName() != null && (
                                item.getName().toLowerCase().contains("log")
                                        && !item.getName().toLowerCase().contains("axe")
                        )) != null;
            case MINING:
                return Inventory.getFirst(item ->
                        item.getName() != null && (
                                item.getName().toLowerCase().contains("ore")
                                        || item.getName().equalsIgnoreCase("Coal")
                                        || item.getName().equalsIgnoreCase("Clay")
                        )) != null;
            case FISHING:
                return Inventory.getFirst(item ->
                        item.getName() != null && (
                                item.getName().startsWith("Raw ")
                                        || item.getName().equals("Shrimps")
                                        || item.getName().equals("Anchovies")
                        )) != null;
            case LOOT:
                return Inventory.getFirst(item ->
                        item.getName() != null && (
                                item.getName().toLowerCase().contains("trout")
                                        || item.getName().toLowerCase().contains("salmon")
                        )) != null;
            default:
                return false; // Combat items niet auto-droppen
        }
    }

    /** Haal droppable skill items op. */
    private List<net.storm.api.domain.items.IInventoryItem> getSkillItemsToClean(ActiveSkill skill) {
        switch (skill) {
            case WOODCUTTING:
                return Inventory.getAll(item ->
                        item.getName() != null
                                && item.getName().toLowerCase().contains("log")
                                && !item.getName().toLowerCase().contains("axe"));
            case MINING:
                return Inventory.getAll(item ->
                        item.getName() != null && (
                                item.getName().toLowerCase().contains("ore")
                                        || item.getName().equalsIgnoreCase("Coal")
                                        || item.getName().equalsIgnoreCase("Clay")
                        ));
            case FISHING:
                return Inventory.getAll(item ->
                        item.getName() != null && (
                                item.getName().startsWith("Raw ")
                                        || item.getName().equals("Shrimps")
                                || item.getName().equals("Anchovies")
                        ));
            case LOOT:
                return Inventory.getAll(item ->
                        item.getName() != null && (
                                item.getName().toLowerCase().contains("trout")
                                        || item.getName().toLowerCase().contains("salmon")
                        ));
            default:
                return new ArrayList<>();
        }
    }

    /** Bepaal of banken aanstaat voor de huidige skill. */
    private boolean shouldBankForSkill(ActiveSkill skill) {
        switch (skill) {
            case WOODCUTTING: return !config.wcDropLogs() && !config.wcFiremaking();
            case MINING: return !config.miningDropOre();
            case FISHING: return !config.fishingDropFish();
            case LOOT: return true;
            default: return false;
        }
    }

    // ===================== Tile Marker Beheer =====================

    private void loadTileMarkersFromConfig() {
        tileMarkerManager.loadForSkill(ActiveSkill.COMBAT,      config.combatTiles());
        tileMarkerManager.loadForSkill(ActiveSkill.WOODCUTTING, config.wcTiles());
        tileMarkerManager.loadForSkill(ActiveSkill.MINING,      config.miningTiles());
        tileMarkerManager.loadForSkill(ActiveSkill.FISHING,     config.fishingTiles());
    }

    public void saveTileMarkersToConfig() {
        stormConfigManager.setConfiguration("combatbot", "combatTiles",
                tileMarkerManager.serializeForSkill(ActiveSkill.COMBAT));
        stormConfigManager.setConfiguration("combatbot", "wcTiles",
                tileMarkerManager.serializeForSkill(ActiveSkill.WOODCUTTING));
        stormConfigManager.setConfiguration("combatbot", "miningTiles",
                tileMarkerManager.serializeForSkill(ActiveSkill.MINING));
        stormConfigManager.setConfiguration("combatbot", "fishingTiles",
                tileMarkerManager.serializeForSkill(ActiveSkill.FISHING));

        if (combatHandler != null) {
            combatHandler.updateSafespotFromMarkers();
        }
    }

    private void handleTileMarkerButtons() {
        if (config.saveTilePreset()) {
            stormConfigManager.setConfiguration("combatbot", "saveTilePreset", "false");
            String presetData = tileMarkerManager.serializeAllAsPreset();
            String name = config.tilePresetName().trim();
            if (name.isEmpty()) name = "Preset";
            String updated = TileMarkerManager.addPreset(config.tilePresets(), name, presetData);
            stormConfigManager.setConfiguration("combatbot", "tilePresets", updated);
            paint.setLastAntiBanAction("✅ Tile preset opgeslagen: " + name);
        }

        if (config.doLoadTilePreset()) {
            stormConfigManager.setConfiguration("combatbot", "doLoadTilePreset", "false");
            String name = config.loadTilePreset().trim();
            String presetData = TileMarkerManager.getPreset(config.tilePresets(), name);
            if (presetData != null) {
                tileMarkerManager.loadFromPreset(presetData);
                saveTileMarkersToConfig();
                paint.setLastAntiBanAction("📂 Tile preset geladen: " + name);
            } else {
                paint.setLastAntiBanAction("⚠ Preset niet gevonden: " + name);
            }
        }

        if (config.clearTileMarkers()) {
            stormConfigManager.setConfiguration("combatbot", "clearTileMarkers", "false");
            tileMarkerManager.loadForSkill(activeSkill, "");
            saveTileMarkersToConfig();
            paint.setLastAntiBanAction("🗑 Tile markers gewist voor " + getSkillName(activeSkill));
        }
    }

    /**
     * Tut mode + nog op Tutorial Island + tut niet als voltooid opgeslagen: geen bank-calibratie.
     * Calibratie draait anders vóór {@link #maybeEnterTutorialPreSkill()} en blokkeert de tutorial-loop
     * (bijv. eindeloos “naar bank lopen” zonder bank op het eiland).
     */
    private boolean shouldDeferBankCalibrationUntilAfterTutorial() {
        if (!config.tutModeEnabled() || !Game.isLoggedIn()) {
            return false;
        }
        IPlayer lp = Players.getLocal();
        if (lp == null || !TutorialModeHandler.isOnTutorialIsland(lp)) {
            return false;
        }
        String rsn = tryGetLocalRsn();
        if (rsn != null && !rsn.isBlank() && TutProgressStore.isCompleted(config, rsn)) {
            return false;
        }
        return true;
    }

    private void maybeEnterTutorialPreSkill() {
        if (!config.tutModeEnabled()) {
            return;
        }
        String rsn = tryGetLocalRsn();
        if (rsn == null || rsn.isBlank()) {
            return;
        }
        if (TutProgressStore.isCompleted(config, rsn)) {
            return;
        }
        IPlayer lp = Players.getLocal();
        if (lp == null || !TutorialModeHandler.isOnTutorialIsland(lp)) {
            return;
        }
        if (activeSkill == ActiveSkill.TUT) {
            return;
        }
        activeSkill = ActiveSkill.TUT;
        resetHandlerForSkill(activeSkill);
        paint.setLastAntiBanAction("Tut pre-skill actief voor nieuwe account");
        DebugLog.log("Tut", "Pre-skill actief voor account: " + rsn);
    }

    private boolean handleTutorialPostLoop() {
        if (tutorialModeHandler == null) {
            return false;
        }
        String rsn = tryGetLocalRsn();
        if (tutorialModeHandler.isTutorialDoneNow()) {
            if (rsn != null && !rsn.isBlank()) {
                TutProgressStore.markCompleted(stormConfigManager, config, rsn);
            }
            if (config.tutDirectToStarter()) {
                activeSkill = ActiveSkill.STARTER;
            } else {
                activeSkill = resolveStartSkill();
                if (activeSkill == ActiveSkill.TUT) {
                    activeSkill = ActiveSkill.COMBAT;
                }
            }
            resetHandlerForSkill(activeSkill);
            resetSwitchTimer();
            paint.setLastAntiBanAction("Tut voltooid → " + getSkillName(activeSkill));
            return false;
        }
        if (!tutorialModeHandler.isStepStuck()) {
            return false;
        }
        String reason = tutorialModeHandler.getStuckReason();
        DebugLog.log("Tut", reason);
        paint.setCurrentStatus(reason);
        if (config.tutSwitchAccountOnStuck() && config.accountSwitchEnabled()) {
            boolean switched = accountSwitcher != null
                    && accountSwitcher.requestImmediateSwitch("⚠ Tut stuck: " + reason);
            if (switched) {
                invokeGameLogoutOnClientThread();
                return true;
            }
        }
        stormConfigManager.setConfiguration("combatbot", "botEnabled", false);
        return true;
    }

    // ===================== Start Skill Resolutie =====================

    /** Globale {@link CombatBotConfig#startSkill()}, tenzij dit account eigen center-lijsten heeft en een override zet. */
    private CombatBotConfig.StartSkill effectiveStartSkillSetting() {
        return effectiveStartSkillSettingForDisplayName(tryGetLocalRsn());
    }

    private CombatBotConfig.StartSkill effectiveStartSkillSettingForDisplayName(String displayName) {
        ManagedJagexAccountsStore.ManagedJagexAccountRow row =
                ManagedJagexAccountsStore.findRowForDisplayName(config, displayName);
        if (row != null && !row.useGlobalCenterListsOnly) {
            String ov = row.startSkillOverride != null ? row.startSkillOverride.trim() : "";
            if (!ov.isEmpty()) {
                try {
                    return CombatBotConfig.StartSkill.valueOf(ov.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                    // onbekende waarde → globaal
                }
            }
        }
        return config.startSkill();
    }

    private ActiveSkill resolveStartSkill() {
        CombatBotConfig.StartSkill setting = effectiveStartSkillSetting();
        List<ActiveSkill> enabled = getEnabledSkills();
        ActiveSkill fallback = enabled.isEmpty() ? ActiveSkill.COMBAT : enabled.get(0);
        ActiveSkill candidate = fallback;

        CombatBotConfig.StartSkill effectiveSetting = setting;
        if (setting == CombatBotConfig.StartSkill.VAMPIRE_SLAYER) {
            String rsnV = tryGetLocalRsn();
            if (rsnV != null
                    && !AccountQuestProgressStore.isVampireSlayerComplete(config, rsnV)
                    && hasMinimumHpForVampireSlayer()) {
                return ActiveSkill.VAMPIRE_SLAYER;
            }
            effectiveSetting = CombatBotConfig.StartSkill.COMBAT;
        }

        if (isVampireSlayerLoopPriorityActive()) {
            String rsn = tryGetLocalRsn();
            if (rsn != null
                    && !AccountQuestProgressStore.isVampireSlayerComplete(config, rsn)
                    && hasMinimumHpForVampireSlayer()) {
                return ActiveSkill.VAMPIRE_SLAYER;
            }
        }

        switch (effectiveSetting) {
            case TUT:
                candidate = ActiveSkill.TUT;
                break;
            case STARTER:
                candidate = ActiveSkill.STARTER;
                break;
            case WOODCUTTING: candidate = CenterManager.countActive(config.wcCenters()) > 0 ? ActiveSkill.WOODCUTTING : fallback; break;
            case MINING:      candidate = CenterManager.countActive(config.miningCenters()) > 0 ? ActiveSkill.MINING : fallback; break;
            case FISHING:     candidate = CenterManager.countActive(config.fishingCenters()) > 0 ? ActiveSkill.FISHING : fallback; break;
            case BARBARIAN:   candidate = config.barbarianMode() ? ActiveSkill.BARBARIAN : fallback; break;
            case LOOT:        candidate = config.barbLootEnabled() ? ActiveSkill.LOOT : fallback; break;
            case BARB_LOOT: {
                String rsn = tryGetLocalRsn();
                if (rsn != null && BarbLootSkipStore.isSkipped(config, rsn)) {
                    barbLootStartMagicCheckDone = true;
                    barbLootSessionActive = false;
                    candidate = pickFirstNonLootSkill();
                } else {
                    candidate = ActiveSkill.LOOT;
                }
                break;
            }
            case IMPS:        candidate = effectiveImpsInRotation() ? ActiveSkill.IMPS : fallback; break;
            case GIANTS:      candidate = effectiveGiantsMode() ? ActiveSkill.GIANTS : fallback; break;
            case RANDOM: {
                if (enabled.isEmpty()) return fallback;
                return enabled.get(random.nextInt(enabled.size()));
            }
            case COMBAT:
            default: candidate = CenterManager.countActive(config.combatCenters()) > 0 ? ActiveSkill.COMBAT : fallback; break;
        }

        // Skilling skills MOETEN actieve centers hebben, anders fallback
        if (candidate == ActiveSkill.WOODCUTTING || candidate == ActiveSkill.MINING || candidate == ActiveSkill.FISHING) {
            String centersData = getCenterString(candidate);
            if (CenterManager.countActive(centersData) == 0) {
                paint.setLastAntiBanAction("⚠ Geen centers voor " + getSkillName(candidate) + " → fallback");
                // Zoek een andere skill die WEL centers heeft
                List<ActiveSkill> altEnabled = getEnabledSkills();
                for (ActiveSkill alt : altEnabled) {
                    if (alt != candidate) {
                        paint.setLastAntiBanAction("↪ Fallback naar " + getSkillName(alt));
                        return alt;
                    }
                }
                return fallback;
            }
        }
        if (candidate == ActiveSkill.BARBARIAN && !config.barbarianMode()) {
            candidate = fallback;
        }
        if (candidate == ActiveSkill.TUT && !config.tutModeEnabled()) {
            candidate = fallback;
        }
        DebugLog.log("StartSkill", "resolveStartSkill=" + getSkillName(candidate)
                + " setting=" + setting
                + " enabled=" + enabled);
        return candidate;
    }

    // ===================== Skill Rotation =====================

    /**
     * Fresh start: reset ALLE handlers en state zodat de bot helemaal opnieuw begint.
     * Wordt aangeroepen wanneer de bot opnieuw wordt ingeschakeld na een stop.
     */
    private void performFreshStart() {
        BankHelper.resetOpenCooldown();
        // Reset alle handlers — volledig opnieuw aanmaken
        combatHandler = new CombatHandler(config, antiBan, paint, null);
        combatHandler.setTileMarkerManager(tileMarkerManager);
        combatHandler.resetState();
        woodcutterHandler = new WoodcutterHandler(config, antiBan, paint);
        woodcutterHandler.setTileMarkerManager(tileMarkerManager);
        woodcutterHandler.resetState();
        miningHandler = new MiningHandler(config, antiBan, paint);
        miningHandler.setTileMarkerManager(tileMarkerManager);
        miningHandler.resetState();
        barbarianHandler = new BarbarianHandler(config, antiBan, paint);
        barbarianHandler.resetState();
        fishingHandler = new FishingHandler(config, antiBan, paint, this::stopBotAndLogoutForFishing);
        fishingHandler.setTileMarkerManager(tileMarkerManager);
        fishingHandler.resetState();
        impsHandler = new ImpsHandler(config, antiBan, paint);
        impsHandler.setTileMarkerManager(tileMarkerManager);
        impsHandler.setQuestProgressConfigManager(stormConfigManager);
        impsHandler.resetState();
        vampireSlayerQuestHandler = new VampireSlayerQuestHandler(config, antiBan, paint, stormConfigManager);
        giantsHandler = new GiantsHandler(config, antiBan, paint);
        giantsHandler.setTileMarkerManager(tileMarkerManager);
        giantsHandler.resetState();
        lootHandler = new LootHandler(config, antiBan, paint);
        lootHandler.setClientThread(clientThread);
        lootHandler.setConfigManager(stormConfigManager);
        lootHandler.setTileMarkerManager(tileMarkerManager);
        lootHandler.resetState();
        starterSkillHandler = new StarterSkillHandler(config, antiBan, paint);
        tutorialModeHandler = new TutorialModeHandler(paint, config);
        wireStarterHandoffCallback();
        if (ignoreStarterJsonOnNextFreshStart) {
            starterSkillHandler.resetStateForPanel();
            ignoreStarterJsonOnNextFreshStart = false;
        } else {
            starterSkillHandler.resetState();
        }

        // Centers opnieuw laden. Belangrijk: bij managed accounts eerst de per-account centers
        // toepassen en daarna pas resolveStartSkill() doen. Anders kan Start skill = Imps nog
        // naar de vorige/globale impsCenters kijken en naar een andere skill fallbacken.
        loadCentersAndSetHandlers();
        captureCentersSnapshotFromConfig();
        String managedCenterLabel = tryGetLocalRsn();
        if ((managedCenterLabel == null || managedCenterLabel.trim().isEmpty())
                && config.accountSwitchEnabled() && accountSwitcher.getAccountCount() > 0) {
            managedCenterLabel = accountSwitcher.getCurrentAccountName();
        }
        if (managedCenterLabel != null && !managedCenterLabel.trim().isEmpty()) {
            applyManagedCentersForDisplayName(managedCenterLabel);
        }

        barbLootSessionActive = false;
        barbLootStartMagicCheckDone = false;
        wcImpsCashFarmActive = false;
        wcImpsCashFarmTripsTarget = 0;
        wcImpsCashFarmTripsStart = 0;
        moneyImpsCashFarmActive = false;
        moneyImpsCashFarmTripsTarget = 0;
        moneyImpsCashFarmTripsStart = 0;

        // Skill rotation reset
        activeSkill = resolveStartSkill();
        resetSwitchTimer();
        cleaningUpBeforeSwitch = false;
        pendingSkill = null;
        switchCooldownUntil = 0;

        lampFlowStartedMs = 0;
        lampInterfaceGroupHint = -1;
        lastLampSkillSelectMs = 0;
        lastLampInventoryInteractMs = 0;

        paint.setActiveSkill(getSkillName(activeSkill));
        paint.setLastAntiBanAction("▶ Fresh start — alles gereset");
        paint.setCurrentStatus("▶ Start skill: " + getSkillName(activeSkill));
        DebugLog.log("StartSkill", "FreshStart activeSkill=" + getSkillName(activeSkill)
                + " enabledNow=" + getEnabledSkills());
    }

    /** Laadt optioneel per-account center-strings uit de Account Manager na account-wissel. */
    private void applyManagedCentersForDisplayName(String label) {
        if (label == null || label.isEmpty() || "Geen".equalsIgnoreCase(label)) {
            return;
        }
        String blob = config.managedJagexAccountsBlob();
        if (blob == null || blob.trim().isEmpty()) {
            return;
        }
        for (ManagedJagexAccountsStore.ManagedJagexAccountRow r : ManagedJagexAccountsStore.parseRows(blob)) {
            if (r.displayName != null && r.displayName.equalsIgnoreCase(label)) {
                applyingManagedCenters = true;
                try {
                    boolean any = false;
                    boolean forceGlobal = r.useGlobalCenterListsOnly;
                    any |= setManagedCenterConfig("combatCenters", r.useGlobalCombatCenters,
                            forceGlobal ? "" : r.combatCenters, masterCombatCenters);
                    any |= setManagedCenterConfig("wcCenters", r.useGlobalWcCenters,
                            forceGlobal ? "" : r.wcCenters, masterWcCenters);
                    any |= setManagedCenterConfig("miningCenters", r.useGlobalMiningCenters,
                            forceGlobal ? "" : r.miningCenters, masterMiningCenters);
                    any |= setManagedCenterConfig("fishingCenters", r.useGlobalFishingCenters,
                            forceGlobal ? "" : r.fishingCenters, masterFishingCenters);
                    any |= setManagedCenterConfig("impsCenters", r.useGlobalImpsCenters,
                            forceGlobal ? "" : r.impsCenters, masterImpsCenters);
                    if (any) {
                        captureCentersSnapshotFromConfig();
                        loadCentersAndSetHandlers();
                        updateMenuListenerCenters();
                        paint.setLastAntiBanAction("📍 Centers voor account " + label);
                    }
                } finally {
                    applyingManagedCenters = false;
                }
                return;
            }
        }
    }

    /**
     * Zet config voor één skill: leeg (uit), volledige master, of account-subset (deels globale lijst).
     * {@code accountSubset} leeg bij {@code useGlobal} = hele master; anders alleen geselecteerde locaties.
     */
    private boolean setManagedCenterConfig(String key, boolean useGlobal, String accountSubset, String masterValue) {
        String target;
        if (!useGlobal) {
            target = "";
        } else if (accountSubset == null || accountSubset.trim().isEmpty()) {
            target = nullToEmptyCenters(masterValue);
        } else {
            target = nullToEmptyCenters(accountSubset);
        }
        String cur;
        switch (key) {
            case "combatCenters": cur = nullToEmptyCenters(config.combatCenters()); break;
            case "wcCenters": cur = nullToEmptyCenters(config.wcCenters()); break;
            case "miningCenters": cur = nullToEmptyCenters(config.miningCenters()); break;
            case "fishingCenters": cur = nullToEmptyCenters(config.fishingCenters()); break;
            case "impsCenters": cur = nullToEmptyCenters(config.impsCenters()); break;
            default: return false;
        }
        if (Objects.equals(cur, target)) {
            return false;
        }
        stormConfigManager.setConfiguration("combatbot", key, target);
        return true;
    }

    private void maybeUpdateAccountStatSnapshot() {
        long now = System.currentTimeMillis();
        if (now - lastAccountStatSnapshotMs < ACCOUNT_STAT_SNAPSHOT_INTERVAL_MS) {
            return;
        }
        if (!Game.isLoggedIn()) {
            return;
        }
        try {
            IPlayer lp = Players.getLocal();
            if (lp == null || lp.getName() == null) {
                return;
            }
            String rsn = Text.removeTags(lp.getName());
            if (rsn == null || rsn.isEmpty()) {
                return;
            }
            lastAccountStatSnapshotMs = now;
            long invCoins = Inventory.getCount(true, "Coins");
            long bankCoins = 0;
            if (Bank.isOpen()) {
                var bankCoinStack = Bank.getFirst("Coins");
                if (bankCoinStack != null) {
                    bankCoins = bankCoinStack.getQuantity();
                }
            }
            ManagedJagexAccountsStore.AccountStatSnapshot snap = new ManagedJagexAccountsStore.AccountStatSnapshot();
            snap.totalGpApprox = invCoins + bankCoins;
            snap.combatLevel = lp.getCombatLevel();
            snap.attack = Skills.getLevel(Skill.ATTACK);
            snap.strength = Skills.getLevel(Skill.STRENGTH);
            snap.defence = Skills.getLevel(Skill.DEFENCE);
            snap.magic = Skills.getLevel(Skill.MAGIC);
            snap.woodcutting = Skills.getLevel(Skill.WOODCUTTING);
            snap.mining = Skills.getLevel(Skill.MINING);
            snap.fishing = Skills.getLevel(Skill.FISHING);
            snap.prayer = Skills.getLevel(Skill.PRAYER);
            // Quests-kolom: reserve voor quest progress; voorlopig 0 als geen directe bron beschikbaar is.
            snap.quests = 0;
            snap.updatedEpochMs = now;
            ManagedJagexAccountsStore.mergeSnapshot(stormConfigManager, config, rsn, snap);
        } catch (Throwable t) {
            DebugLog.log("CombatBot", "account snapshot: " + t.getMessage());
        }
    }

    private void maybeRunPeriodicHiscoreBanCheck() {
        if (!config.botEnabled()) {
            return;
        }
        if (hiscoreBanCheckInFlight) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastHiscoreBanCheckMs < 10_000L) {
            return;
        }

        List<ManagedJagexAccountsStore.ManagedJagexAccountRow> rows =
                ManagedJagexAccountsStore.parseRows(config.managedJagexAccountsBlob());
        if (rows.isEmpty()) {
            return;
        }
        Map<String, ManagedJagexAccountsStore.AccountStatSnapshot> snaps =
                ManagedJagexAccountsStore.parseSnapshots(config.accountStatSnapshotsBlob());
        List<String> dueAccounts = new ArrayList<>();
        for (ManagedJagexAccountsStore.ManagedJagexAccountRow r : rows) {
            if (r == null || r.isEmpty() || r.displayName == null || r.displayName.trim().isEmpty()) {
                continue;
            }
            String rsn = r.displayName.trim();
            ManagedJagexAccountsStore.AccountStatSnapshot s = ManagedJagexAccountsStore.snapshotForRow(snaps, rsn);
            boolean neverChecked = s == null || s.updatedEpochMs <= 0;
            boolean stale = !neverChecked && (now - s.updatedEpochMs >= HISCORE_BAN_CHECK_INTERVAL_MS);
            if (neverChecked || stale) {
                dueAccounts.add(rsn);
            }
        }
        if (dueAccounts.isEmpty()) {
            return;
        }

        lastHiscoreBanCheckMs = now;
        hiscoreBanCheckInFlight = true;
        Thread t = new Thread(() -> {
            try {
                for (String rsn : dueAccounts) {
                    Map<String, String> live = OsrsHiscoreApi.fetchFullSkillLevels(rsn);
                    boolean suspectBanned = (live == null || live.isEmpty());
                    Map<String, ManagedJagexAccountsStore.AccountStatSnapshot> snapMap =
                            ManagedJagexAccountsStore.parseSnapshots(config.accountStatSnapshotsBlob());
                    ManagedJagexAccountsStore.AccountStatSnapshot prev =
                            ManagedJagexAccountsStore.snapshotForRow(snapMap, rsn);
                    ManagedJagexAccountsStore.AccountStatSnapshot snap =
                            prev != null ? prev : new ManagedJagexAccountsStore.AccountStatSnapshot();
                    snap.hiscoreSuspectBanned = suspectBanned;
                    snap.updatedEpochMs = System.currentTimeMillis();
                    ManagedJagexAccountsStore.mergeSnapshot(stormConfigManager, config, rsn, snap);
                    DebugLog.log("Accounts", "hiscore 30m-check " + rsn + " -> suspectBanned=" + suspectBanned);
                }
            } catch (Throwable t1) {
                DebugLog.log("Accounts", "hiscore 30m-check fout: " + t1.getMessage());
            } finally {
                hiscoreBanCheckInFlight = false;
            }
        }, "hiscore-ban-check");
        t.setDaemon(true);
        t.start();
    }

    private void maybeHandleDraynorBotMention(ChatMessage event, String lowerMessage) {
        if (event == null || lowerMessage == null) return;
        if (!Game.isLoggedIn()) return;
        if (!(lowerMessage.contains("bot") || lowerMessage.contains("bots"))) return;
        if (!(activeSkill == ActiveSkill.WOODCUTTING || activeSkill == ActiveSkill.FISHING)) return;
        IPlayer lp = Players.getLocal();
        if (lp == null || lp.getWorldLocation() == null) return;
        WorldPoint pos = lp.getWorldLocation();
        if (!(pos.getX() >= 3070 && pos.getX() <= 3115 && pos.getY() >= 3200 && pos.getY() <= 3280)) return;

        String speaker = event.getName() != null ? Text.removeTags(event.getName()).trim() : "unknown";
        long now = System.currentTimeMillis();
        draynorBotMentionSpeakerMs.put(speaker.toLowerCase(Locale.ROOT), now);
        if (now - lastDraynorBotReactionMs < 20_000L) {
            return;
        }
        lastDraynorBotReactionMs = now;
        draynorAvoidCentersUntilMs = now + (20L * 60L * 1000L); // 20 min Draynor skippen
        DebugLog.log("Accounts", "Draynor bot-melding door " + speaker + ": \"" + lowerMessage + "\"");

        String centersData = activeSkill == ActiveSkill.WOODCUTTING ? config.wcCenters() : config.fishingCenters();
        CenterManager.Center safe = pickCenterForSkillWithSafety(activeSkill, centersData);
        if (safe != null) {
            setHandlerCenter(activeSkill, safe.point, safe.radius);
            areaOverlay.setActiveCenterForSkill(activeSkill, safe.point);
            paint.setLastAntiBanAction("🚶 Draynor avoid -> ander center");
            return;
        }
        int worldId = AccountSwitchWorldHop.pickRandomF2pWorldId(client);
        AccountSwitchWorldHop.scheduleHopToWorld(client, clientThread, worldId);
        paint.setLastAntiBanAction("🌍 Draynor bots genoemd -> hop w" + worldId);
    }

    private void checkSkillRotation() {
        if (activeSkill == ActiveSkill.STARTER) {
            return;
        }
        if (activeSkill == ActiveSkill.VAMPIRE_SLAYER && isVampireSlayerLoopPriorityActive()) {
            String n = tryGetLocalRsn();
            if (n != null && !AccountQuestProgressStore.isVampireSlayerComplete(config, n)) {
                return;
            }
        }
        List<ActiveSkill> enabledSkills = getEnabledSkills();
        if (enabledSkills.isEmpty()) {
            return;
        }
        if (enabledSkills.size() <= 1) {
            if (activeSkill != ActiveSkill.STARTER && !enabledSkills.contains(activeSkill)) {
                activeSkill = enabledSkills.get(0);
            }
            return;
        }
        if (skillSwitchTime == null) { resetSwitchTimer(); return; }
        long elapsedSec = Duration.between(skillSwitchTime, Instant.now()).getSeconds();
        if (elapsedSec >= nextSwitchSeconds) initiateSkillSwitch();
    }

    /** Genie: Talk-to. Andere random events die met ons interacteren: Dismiss. */
    private int handleRandomEventInteraction() {
        long now = System.currentTimeMillis();

        // Lamp eerst behandelen (mag sneller reageren dan random dismiss cooldown).
        int lampDelay = handleGenieLampIfPresent(now);
        if (lampDelay > 0) return lampDelay;

        if (now - lastRandomEventActionMs < RANDOM_EVENT_ACTION_COOLDOWN_MS) return 0;

        IPlayer local = Players.getLocal();
        if (local == null || local.getWorldLocation() == null) return 0;

        Object localWrapped = local.getWrapped();
        INPC randomNpc = NPCs.getNearest(npc -> {
            if (npc == null || npc.getName() == null) {
                return false;
            }
            if (npc.getWorldLocation() == null
                    || npc.getWorldLocation().distanceTo(local.getWorldLocation()) > 6) {
                return false;
            }
            String n = npc.getName().toLowerCase().trim();
            // Random events van andere spelers kunnen óók dichtbij staan en óók "Dismiss" hebben.
            // Daarom is Dismiss/naam/ID alleen een herkenning-signaal; we handelen pas als de NPC
            // daadwerkelijk met onze local player interacteert.
            boolean randomEventLike = npc.hasAction("Dismiss")
                    || RANDOM_EVENT_NAMES.contains(n)
                    || DISMISS_RANDOM_EVENT_IDS.contains(npc.getId());
            if (!randomEventLike) {
                return false;
            }
            Object interacting = npc.getInteracting();
            if (interacting == null) return false;
            return interacting == local
                    || interacting.equals(local)
                    || (localWrapped != null
                    && (interacting == localWrapped || interacting.equals(localWrapped)));
        });
        if (randomNpc == null || randomNpc.getName() == null) return 0;

        String name = randomNpc.getName().toLowerCase().trim();
        int npcId = randomNpc.getId();
        boolean knownRandomByName = RANDOM_EVENT_NAMES.contains(name);
        boolean knownRandomById = DISMISS_RANDOM_EVENT_IDS.contains(npcId);
        boolean hasDismiss = randomNpc.hasAction("Dismiss");

        // Alleen handelen op bekende random events of NPC's met een Dismiss-actie.
        if (!knownRandomByName && !knownRandomById && !hasDismiss) return 0;

        boolean isGenie = GENIE_NPC_IDS.contains(npcId) || name.contains("genie");
        boolean shouldKeepGenieLamp = config.genieLampSkill() != null
                && config.genieLampSkill() != CombatBotConfig.GenieLampSkill.NONE;
        if (isGenie && shouldKeepGenieLamp) {
            if (randomNpc.hasAction("Talk-to")) {
                randomNpc.interact("Talk-to");
                lastRandomEventActionMs = now;
                paint.setLastAntiBanAction("🧞 Genie aanspreken");
                return 900 + random.nextInt(600);
            }
            return 0;
        }

        if (hasDismiss) {
            randomNpc.interact("Dismiss");
            lastRandomEventActionMs = now;
            paint.setLastAntiBanAction("👋 Random event dismissed: " + name);
            return 800 + random.nextInt(500);
        }

        // Lamp-koeriers (Giles/Niles/Miles): als Dismiss ontbreekt, Talk-to → XP-lamp.
        boolean isLampCourier = LAMP_COURIER_NAMES.contains(name);
        if (isLampCourier) {
            if (Dialog.isOpen()) {
                Dialog.continueSpace();
                lastRandomEventActionMs = now;
                paint.setLastAntiBanAction("📜 " + name + ": dialog doorklikken");
                return 600 + random.nextInt(400);
            }
            if (randomNpc.hasAction("Talk-to")) {
                randomNpc.interact("Talk-to");
                lastRandomEventActionMs = now;
                paint.setLastAntiBanAction("📜 " + name + " aanspreken");
                return 900 + random.nextInt(600);
            }
            return 0;
        }

        // Fallback: bekende random event zonder Dismiss → toch praten zodat het scherm zich
        // afsluit (bv. nieuwe varianten waar dismiss is verdwenen).
        if (randomNpc.hasAction("Talk-to")) {
            if (Dialog.isOpen()) {
                Dialog.continueSpace();
                lastRandomEventActionMs = now;
                paint.setLastAntiBanAction("📜 " + name + ": dialog doorklikken");
                return 600 + random.nextInt(400);
            }
            randomNpc.interact("Talk-to");
            lastRandomEventActionMs = now;
            paint.setLastAntiBanAction("📜 " + name + " aanspreken (geen Dismiss)");
            return 900 + random.nextInt(600);
        }
        return 0;
    }

    /**
     * Genie lamp flow (widget-only):
     * 1) Rub/Use lamp (ID 2528) om skill-interface te openen
     * 2) Klik gewenste skill via widget-tekst
     * 3) Klik Confirm via widget-tekst
     */
    private int handleGenieLampIfPresent(long now) {
        CombatBotConfig.GenieLampSkill preferred = config.genieLampSkill();
        if (preferred == null || preferred == CombatBotConfig.GenieLampSkill.NONE) {
            return 0;
        }
        if (now - lastLampActionMs < LAMP_ACTION_COOLDOWN_MS) {
            return 0;
        }

        IInventoryItem lamp = Inventory.getFirst(item ->
                item != null && item.getId() == GENIE_LAMP_ITEM_ID
        );

        if (lamp == null && lampFlowStartedMs <= 0) {
            return 0;
        }

        int lampGroupDetected = findLampInterfaceGroup();
        boolean lampInterfaceOpen = lampGroupDetected >= 0;

        if (lampInterfaceOpen) {
            lampInterfaceGroupHint = lampGroupDetected;
            paint.setCurrentStatus("\uD83E\uDE94 Genie lamp actief: " + preferred.displayName());
            if (lampFlowStartedMs <= 0) {
                lampFlowStartedMs = now;
            }
            if (now - lastLampDebugMs >= LAMP_DEBUG_INTERVAL_MS) {
                lastLampDebugMs = now;
                DebugLog.log("Lamp", "Interface open, group=" + lampGroupDetected
                        + " preferred=" + preferred.displayName());
            }
        }

        if (lamp != null && !lampInterfaceOpen) {
            if (now - lastLampInventoryInteractMs < LAMP_INVENTORY_OPEN_MIN_GAP_MS) {
                return 400 + random.nextInt(250);
            }
            if (lamp.hasAction("Rub")) {
                lamp.interact("Rub");
            } else if (lamp.hasAction("Use")) {
                lamp.interact("Use");
            } else {
                lamp.interact(0);
            }
            lastLampActionMs = now;
            lastRandomEventActionMs = now;
            lastLampInventoryInteractMs = now;
            if (lampFlowStartedMs <= 0) {
                lampFlowStartedMs = now;
            }
            lastLampDebugMs = now;
            paint.setLastAntiBanAction("\uD83E\uDE94 Genie lamp openen");
            DebugLog.log("Lamp", "Lamp clicked to open interface");
            return 250 + random.nextInt(250);
        }

        if (!lampInterfaceOpen) {
            if (lamp == null) {
                lampFlowStartedMs = 0;
                lastLampSkillSelectMs = 0;
            }
            return 0;
        }

        if (now - lastLampSkillSelectMs < 5000) {
            if (clickLampWidgetByKeywordInLampGroup("confirm")) {
                lastLampActionMs = now;
                lastRandomEventActionMs = now;
                paint.setLastAntiBanAction("✅ Genie lamp bevestigd");
                DebugLog.log("Lamp", "Confirm via widget (after skill select)");
                lampFlowStartedMs = 0;
                lastLampSkillSelectMs = 0;
                lampInterfaceGroupHint = -1;
                return 650 + random.nextInt(500);
            }
        }

        String[] keywords = getLampKeywordsForSkill(preferred);
        for (String keyword : keywords) {
            if (clickLampWidgetByKeywordInLampGroup(keyword)) {
                lastLampSkillSelectMs = now;
                lastLampActionMs = now;
                lastRandomEventActionMs = now;
                paint.setLastAntiBanAction("\uD83E\uDE94 Lamp skill: " + preferred.displayName());
                DebugLog.log("Lamp", "Skill select via widget: " + keyword);
                return 500 + random.nextInt(450);
            }
        }

        if (clickLampWidgetByKeywordInLampGroup("confirm")) {
            lastLampActionMs = now;
            lastRandomEventActionMs = now;
            paint.setLastAntiBanAction("✅ Genie lamp bevestigd");
            DebugLog.log("Lamp", "Confirm via widget (direct)");
            lampFlowStartedMs = 0;
            lastLampSkillSelectMs = 0;
            lampInterfaceGroupHint = -1;
            return 650 + random.nextInt(500);
        }

        if (lampFlowStartedMs > 0 && (now - lampFlowStartedMs) >= LAMP_FAILSAFE_AFTER_MS) {
            DebugLog.log("Lamp", "FAILSAFE: interface open >" + LAMP_FAILSAFE_AFTER_MS
                    + "ms, brute-force skill labels in group " + lampGroupDetected);
            for (String frag : LAMP_SKILL_LABEL_FRAGMENTS) {
                if ("confirm".equals(frag) || "choose".equals(frag)) {
                    continue;
                }
                if (clickLampWidgetByKeywordInLampGroup(frag)) {
                    lastLampSkillSelectMs = now;
                    lastLampActionMs = now;
                    DebugLog.log("Lamp", "FAILSAFE skill click: " + frag);
                    return 500 + random.nextInt(450);
                }
            }
            DebugLog.log("Lamp", "FAILSAFE: geen skill/confirm widget - reset flow");
            lampFlowStartedMs = 0;
            lampInterfaceGroupHint = -1;
            return 400;
        }

        DebugLog.log("Lamp", "Interface open maar geen target dit tick");
        return 350 + random.nextInt(250);
    }

    /** Lamp-scherm: "confirm" plus minstens een skill-tekst. */
    private boolean isLampLikeWidgetGroup(int group) {
        if (group < 0) {
            return false;
        }
        if (!hasVisibleWidgetContainingInGroup(group, "confirm")) {
            return false;
        }
        for (String frag : LAMP_SKILL_LABEL_FRAGMENTS) {
            if (hasVisibleWidgetContainingInGroup(group, frag)) {
                return true;
            }
        }
        return false;
    }

    private int findLampInterfaceGroup() {
        long t = System.currentTimeMillis();
        if (lampInterfaceGroupHint >= 0) {
            if (isLampLikeWidgetGroup(lampInterfaceGroupHint)) {
                return lampInterfaceGroupHint;
            }
            if (lampFlowStartedMs > 0 && (t - lampFlowStartedMs) < 15_000L
                    && hasVisibleWidgetContainingInGroup(lampInterfaceGroupHint, "confirm")) {
                return lampInterfaceGroupHint;
            }
            lampInterfaceGroupHint = -1;
        }

        for (int group : LAMP_INTERFACE_GROUPS_PRIORITY) {
            if (isLampLikeWidgetGroup(group)) {
                return group;
            }
        }
        if (lampFlowStartedMs > 0 && (t - lampFlowStartedMs) < 15_000L) {
            for (int group : LAMP_INTERFACE_GROUPS_PRIORITY) {
                if (hasVisibleWidgetContainingInGroup(group, "confirm")) {
                    return group;
                }
            }
        }

        if (lampFlowStartedMs > 0) {
            for (int group = 0; group <= 800; group++) {
                if (isLampLikeWidgetGroup(group)) {
                    return group;
                }
            }
        }
        return -1;
    }

    private boolean hasVisibleWidgetContainingInGroup(int group, String needle) {
        if (needle == null || needle.isEmpty() || group < 0) return false;
        String lowNeedle = needle.toLowerCase();
        for (int child = 0; child <= 80; child++) {
            IWidget root = Widgets.get(group, child);
            if (root == null || root.isHidden()) continue;
            if (widgetNameContains(root, lowNeedle)) return true;
            IWidget[] descendants = getWidgetChildren(root);
            if (descendants == null) continue;
            for (IWidget w : descendants) {
                if (w == null || w.isHidden()) continue;
                if (widgetNameContains(w, lowNeedle)) return true;
            }
        }
        return false;
    }

    /**
     * Normale lamp: zelfde widget-bounds als {@link #runLampHoverWidgetTest} (vaste ids + keyword op 240),
     * daarna linksklik i.p.v. alleen hover.
     */
    private boolean clickLampWidgetByKeywordInLampGroup(String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return false;
        }
        String lowKeyword = keyword.toLowerCase(Locale.ROOT);
        int group = lampInterfaceGroupHint >= 0 ? lampInterfaceGroupHint : findLampInterfaceGroup();
        if (group < 0) {
            group = 240;
        }

        Rectangle b = resolveLampWidgetBoundsLikeHoverTest(group, lowKeyword);
        if (b != null && b.width > 0 && b.height > 0) {
            return smoothMoveAndLeftClickLampWidget(b, "keyword '" + keyword + "'");
        }

        List<IWidget> candidates = new ArrayList<>();
        for (int child = 0; child <= 80; child++) {
            IWidget root = Widgets.get(group, child);
            if (root == null || root.isHidden()) {
                continue;
            }
            collectStormLampWidgetsMatching(root, lowKeyword, candidates);
        }
        if (group != 240) {
            for (int child = 0; child <= 80; child++) {
                IWidget root = Widgets.get(240, child);
                if (root == null || root.isHidden()) {
                    continue;
                }
                collectStormLampWidgetsMatching(root, lowKeyword, candidates);
            }
        }
        if (candidates.isEmpty()) {
            return false;
        }
        Collections.shuffle(candidates, random);
        candidates.get(0).interact(0);
        DebugLog.log("Lamp", "Storm interact(0) fallback keyword='" + keyword + "'");
        return true;
    }

    /**
     * Exact hetzelfde afwegingspatroon als de werkende lamp-hovertest: eerst keyword in RL-boom,
     * daarna vaste children (confirm 27, attack 2, magic 5) en 240/24 fallbacks zoals in de test.
     */
    private Rectangle resolveLampWidgetBoundsLikeHoverTest(int group, String lowKeyword) {
        Rectangle b = readWidgetBoundsByKeywordOnClientThread(group, lowKeyword);
        if (b != null && b.width > 0 && b.height > 0) {
            return b;
        }
        if ("confirm".equals(lowKeyword)) {
            b = readWidgetBoundsOnClientThread(group, 27);
            if (b != null && b.width > 0 && b.height > 0) {
                return b;
            }
            b = readWidgetBoundsOnClientThread(240, 27);
            if (b != null && b.width > 0 && b.height > 0) {
                return b;
            }
            b = readWidgetBoundsOnClientThread(24, 27);
            if (b != null && b.width > 0 && b.height > 0) {
                return b;
            }
            return readWidgetBoundsByKeywordOnClientThread(240, lowKeyword);
        }
        if ("attack".equals(lowKeyword)) {
            b = readWidgetBoundsOnClientThread(group, 2);
            if (b != null && b.width > 0 && b.height > 0) {
                return b;
            }
            return readWidgetBoundsOnClientThread(240, 2);
        }
        if ("magic".equals(lowKeyword)) {
            b = readWidgetBoundsOnClientThread(group, 5);
            if (b != null && b.width > 0 && b.height > 0) {
                return b;
            }
            return readWidgetBoundsOnClientThread(240, 5);
        }
        if (group != 240) {
            b = readWidgetBoundsByKeywordOnClientThread(240, lowKeyword);
            if (b != null && b.width > 0 && b.height > 0) {
                return b;
            }
        }
        return null;
    }

    private void collectStormLampWidgetsMatching(IWidget node, String lowKeyword, List<IWidget> out) {
        if (node == null || node.isHidden()) {
            return;
        }
        if (widgetNameContains(node, lowKeyword)) {
            out.add(node);
        }
        IWidget[] ch = getWidgetChildren(node);
        if (ch == null) {
            return;
        }
        for (IWidget c : ch) {
            collectStormLampWidgetsMatching(c, lowKeyword, out);
        }
    }

    /**
     * Lamp- en UI-widgets tonen labels vaak in {@link IWidget#getText()} of actions, niet alleen in {@link IWidget#getName()}.
     * Zonder text/actions-match faalt o.a. {@link #isLampLikeWidgetGroup} / {@link #findLampInterfaceGroup} terwijl vaste-id tests wél werken.
     */
    private boolean widgetNameContains(IWidget widget, String keyword) {
        if (widget == null || keyword == null || keyword.isEmpty()) {
            return false;
        }
        String k = keyword.toLowerCase(Locale.ROOT);
        String[] sources = new String[]{
                safeWidgetText(widget),
                widget.getName()
        };
        for (String src : sources) {
            if (src == null || src.isEmpty()) {
                continue;
            }
            String cleaned = src.replaceAll("<[^>]*>", "").trim().toLowerCase(Locale.ROOT);
            if (cleaned.contains(k)) {
                return true;
            }
        }
        try {
            String[] actions = widget.getActions();
            if (actions != null) {
                for (String a : actions) {
                    if (a == null || a.isEmpty()) {
                        continue;
                    }
                    String cleaned = a.replaceAll("<[^>]*>", "").trim().toLowerCase(Locale.ROOT);
                    if (cleaned.contains(k)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private String safeWidgetText(IWidget widget) {
        try {
            return widget.getText();
        } catch (Throwable t) {
            return null;
        }
    }

    private IWidget[] getWidgetChildren(IWidget parent) {
        if (parent == null) return null;
        IWidget[] children = parent.getDynamicChildren();
        if (children != null && children.length > 0) return children;
        children = parent.getChildren();
        if (children != null && children.length > 0) return children;
        children = parent.getNestedChildren();
        if (children != null && children.length > 0) return children;
        return null;
    }

    private static String formatDiscordCountdown(long totalSeconds) {
        if (totalSeconds < 0) {
            totalSeconds = 0;
        }
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        if (h > 0) {
            return String.format("%d:%02d:%02d", h, m, s);
        }
        return String.format("%02d:%02d", m, s);
    }

    private void appendDiscordRelogBlock(StringBuilder sb) {
        if (paint == null || !paint.isRelogEnabled()) {
            return;
        }
        if (paint.isRelogInPause() && paint.getRelogPauseSecondsRemaining() > 0) {
            sb.append("Re-log pauze: nog ").append(formatDiscordCountdown(paint.getRelogPauseSecondsRemaining()))
                    .append(" tot auto-login\n");
        } else if (client != null && client.getGameState() == GameState.LOGIN_SCREEN) {
            sb.append("Re-log: op login-scherm (auto-login)\n");
        } else if (!paint.isRelogInPause() && paint.getRelogSecondsUntilLogout() > 0) {
            sb.append("Volgende re-log over: ").append(formatDiscordCountdown(paint.getRelogSecondsUntilLogout())).append("\n");
        }
    }

    private String discordGameStateName() {
        try {
            if (client != null && client.getGameState() != null) {
                return client.getGameState().name();
            }
        } catch (Exception ignored) {
        }
        return "onbekend";
    }

    private String discordWorldLine() {
        if (client == null || client.getGameState() != GameState.LOGGED_IN) {
            return null;
        }
        try {
            WorldView wv = client.getTopLevelWorldView();
            if (wv != null) {
                int id = wv.getId();
                if (id > 0) {
                    return "Wereld: " + id;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Markdown-link naar officiële OSRS personal hiscore (Discord ondersteunt [label](url)). */
    private static String discordHiscoreMarkdown(String displayName) {
        if (displayName == null || displayName.isEmpty()) {
            return "";
        }
        String name = Text.removeTags(displayName).trim();
        if (name.isEmpty()) {
            return "";
        }
        String url = "https://secure.runescape.com/m=hiscore_oldschool/hiscorepersonal?user1="
                + URLEncoder.encode(name, StandardCharsets.UTF_8);
        String esc = name.replace("]", "\\]");
        return "[" + esc + "](" + url + ")";
    }

    private String discordRsnLine() {
        if (client == null || client.getGameState() != GameState.LOGGED_IN) {
            return null;
        }
        try {
            Player p = client.getLocalPlayer();
            if (p != null && p.getName() != null && !p.getName().isEmpty()) {
                String md = discordHiscoreMarkdown(p.getName());
                if (!md.isEmpty()) {
                    return "Speler: " + md;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String discordCashInvLine() {
        try {
            IInventoryItem coins = Inventory.getFirst("Coins");
            int n = coins != null ? coins.getQuantity() : 0;
            String fmt = paint != null ? paint.formatGp(n) : String.valueOf(n);
            return "Cash (inv): " + fmt + " gp";
        } catch (Exception e) {
            return null;
        }
    }

    private int countDiscordFoodInInventory() {
        try {
            CombatBotConfig.FoodChoice fc = config.foodChoice();
            if (fc == CombatBotConfig.FoodChoice.ANY) {
                int sum = 0;
                for (CombatBotConfig.FoodChoice c : CombatBotConfig.FoodChoice.values()) {
                    if (c == CombatBotConfig.FoodChoice.ANY) {
                        continue;
                    }
                    IInventoryItem it = Inventory.getFirst(c.toItemName());
                    if (it != null) {
                        sum += it.getQuantity();
                    }
                }
                return sum;
            }
            IInventoryItem it = Inventory.getFirst(fc.toItemName());
            return it != null ? it.getQuantity() : 0;
        } catch (Exception e) {
            return -1;
        }
    }

    private String discordVitalsLine() {
        if (client == null || client.getGameState() != GameState.LOGGED_IN) {
            return null;
        }
        try {
            int hp = Skills.getBoostedLevel(Skill.HITPOINTS);
            int hpBase = Skills.getLevel(Skill.HITPOINTS);
            int pray = Skills.getBoostedLevel(Skill.PRAYER);
            int prayBase = Skills.getLevel(Skill.PRAYER);
            int runPct = Math.min(100, Math.max(0, client.getEnergy() / 100));
            return String.format("HP: %d/%d · Gebed: %d/%d · Run: %d%%", hp, hpBase, pray, prayBase, runPct);
        } catch (Exception e) {
            return null;
        }
    }

    private String discordLocationLine(ActiveSkill skillSnapshot) {
        try {
            if (areaOverlay == null) {
                return null;
            }
            return areaOverlay.getActiveCenterDescription(skillSnapshot);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Compacte webhook: weinig regels. Uitgebreid: wereld, RSN, vitals, locatie, food, enz.
     */
    private String buildDiscordUpdateText(ActiveSkill skillSnapshot, String modeSnapshot, String statusSnapshot,
            int killsSnapshot, int lootItemsSnapshot, long lootValueSnapshot, String runtimeSnapshot, String gpStr,
            boolean detailed) {
        StringBuilder sb = new StringBuilder(detailed ? 700 : 320);
        if (detailed) {
            sb.append("CombatBot Discord Update\n");
        } else {
            sb.append("CombatBot Discord Update (compact)\n");
        }

        sb.append("Client: ").append(discordGameStateName()).append("\n");
        sb.append("Mode: ").append(modeSnapshot).append("\n");
        sb.append("Status: ").append(statusSnapshot).append("\n");

        appendDiscordRelogBlock(sb);

        if (!detailed) {
            sb.append("Sessie: ").append(killsSnapshot).append(" kills · ")
                    .append(lootItemsSnapshot).append(" loot · ")
                    .append(gpStr).append(" gp · runtime ").append(runtimeSnapshot).append("\n");
            sb.append("Bot v").append(VERSION);
            return sb.toString();
        }

        String w = discordWorldLine();
        if (w != null) {
            sb.append(w).append("\n");
        }
        String rsn = discordRsnLine();
        if (rsn != null) {
            sb.append(rsn).append("\n");
        }

        String cashLine = discordCashInvLine();
        if (cashLine != null) {
            sb.append(cashLine).append("\n");
        }

        String vitals = discordVitalsLine();
        if (vitals != null) {
            sb.append(vitals).append("\n");
        }

        String loc = discordLocationLine(skillSnapshot);
        if (loc != null) {
            sb.append("Locatie: ").append(loc).append("\n");
        }

        int foodN = countDiscordFoodInInventory();
        if (foodN >= 0) {
            sb.append("Food (inv, schatting): ").append(foodN).append("\n");
        }

        if (paint != null && paint.isRotationEnabled() && paint.getSecondsUntilSwitch() > 0) {
            sb.append("Skill-wissel over: ").append(formatDiscordCountdown(paint.getSecondsUntilSwitch())).append("\n");
        }

        if (paint != null && paint.isAccountSwitchEnabled()) {
            String nm = paint.getCurrentAccountName();
            if (nm != null && !Text.removeTags(nm).trim().isEmpty()) {
                String md = discordHiscoreMarkdown(nm);
                if (!md.isEmpty()) {
                    sb.append("Account: ").append(md)
                            .append(" (").append(paint.getCurrentAccountNumber()).append("/").append(paint.getTotalAccounts())
                            .append("), wissel over ").append(CombatBotPaint.formatAccountSwitchHms(paint.getAccountSecondsLeft()))
                            .append("\n");
                }
            }
        }

        String xpLine = paint != null ? paint.getDiscordXpLine(modeSnapshot) : null;
        if (xpLine != null) {
            sb.append(xpLine).append("\n");
        }

        sb.append("Sessie — Kills: ").append(killsSnapshot)
                .append(" · Loot: ").append(lootItemsSnapshot).append(" items")
                .append(" · Loot GP: ").append(gpStr)
                .append(" · Runtime: ").append(runtimeSnapshot).append("\n");

        sb.append("Bot v").append(VERSION);
        return sb.toString();
    }

    private void sendDiscordWebhookInBackground(String webhookUrl, String content, BufferedImage img) {
        new Thread(() -> {
            try {
                DebugLog.log("Discord", "Screenshot sturen naar webhook…");
                boolean ok = DiscordWebhookSender.sendWebhook(webhookUrl, content, img);
                DebugLog.log("Discord", ok ? "Discord webhook OK" : "Discord webhook FAILED");
            } catch (Exception e) {
                DebugLog.log("Discord", "Discord send error: " + e.getMessage());
            } finally {
                discordSendInProgress = false;
            }
        }, "CombatBot-DiscordSend").start();
    }

    /**
     * Tijdens re-log: korte tekst naar Discord (geen screenshot), omdat {@link #maybeSendDiscordScreenshot()}
     * dan niet wordt bereikt. Eerste bruikbare ETA direct, daarna elke 5 minuten.
     */
    private void maybeSendDiscordRelogPing() {
        try {
            if (config == null || sameAccountRelogger == null || !sameAccountRelogger.isRelogFlowActive()) {
                return;
            }
            if (!config.discordRelogPausePingsEnabled()) {
                return;
            }
            String webhookUrl = config.discordWebhookUrl();
            if (webhookUrl == null || webhookUrl.trim().isEmpty()) {
                return;
            }
            if (discordSendInProgress) {
                return;
            }

            long pauseSec = paint != null ? paint.getRelogPauseSecondsRemaining() : 0L;
            boolean pauseEta = paint != null && paint.isRelogInPause() && pauseSec > 0;
            GameState gs = null;
            try {
                if (client != null) {
                    gs = client.getGameState();
                }
            } catch (Exception ignored) {
            }
            boolean loginScreen = gs == GameState.LOGIN_SCREEN;
            boolean hasCountdownContext = pauseEta || loginScreen;

            long now = System.currentTimeMillis();
            boolean firstPing = lastDiscordRelogPingMs == 0L && hasCountdownContext;
            boolean periodic = lastDiscordRelogPingMs > 0L && (now - lastDiscordRelogPingMs >= DISCORD_RELOG_PING_INTERVAL_MS);
            if (!firstPing && !periodic) {
                return;
            }

            final String content = buildDiscordRelogPingText(gs, pauseSec, pauseEta);
            final String webhookFinal = webhookUrl.trim();
            discordSendInProgress = true;
            lastDiscordRelogPingMs = now;
            new Thread(() -> {
                try {
                    DebugLog.log("Discord", "Re-log status (tekst) naar webhook…");
                    boolean ok = DiscordWebhookSender.sendWebhook(webhookFinal, content, null);
                    DebugLog.log("Discord", ok ? "Discord re-log ping OK" : "Discord re-log ping FAILED");
                } catch (Exception e) {
                    DebugLog.log("Discord", "Discord re-log ping error: " + e.getMessage());
                } finally {
                    discordSendInProgress = false;
                }
            }, "CombatBot-DiscordRelogPing").start();
        } catch (Exception ignored) {
        }
    }

    private String buildDiscordRelogPingText(GameState gs, long pauseSecondsRemaining, boolean pauseEta) {
        StringBuilder sb = new StringBuilder(280);
        sb.append("CombatBot — Re-log status\n");
        sb.append("Client: ").append(gs != null ? gs.name() : "onbekend").append("\n");
        if (pauseEta) {
            sb.append("Auto-login over: ").append(formatDiscordCountdown(pauseSecondsRemaining)).append("\n");
        } else if (gs == GameState.LOGIN_SCREEN) {
            sb.append("Status: login-scherm — auto-login wordt geprobeerd.\n");
        } else {
            sb.append("Status: re-log bezig (uitloggen of verbinden…)\n");
        }
        if (paint != null && paint.isRelogEnabled() && !pauseEta && paint.getRelogSecondsUntilLogout() > 0) {
            sb.append("Volgende geplande re-log over: ")
                    .append(formatDiscordCountdown(paint.getRelogSecondsUntilLogout())).append("\n");
        }
        sb.append("Bot v").append(VERSION);
        return sb.toString();
    }

    /** Periodiek Discord screenshot + status tekst sturen. */
    private void maybeSendDiscordScreenshot() {
        try {
            if (config == null) return;
            if (!config.discordScreenshotsEnabled()) return;
            String webhookUrl = config.discordWebhookUrl();
            if (webhookUrl == null || webhookUrl.trim().isEmpty()) return;

            int intervalSec = config.discordScreenshotIntervalSeconds();
            if (intervalSec <= 0) intervalSec = 120;

            long now = System.currentTimeMillis();
            if (lastDiscordSendMs > 0 && (now - lastDiscordSendMs) < intervalSec * 1000L) return;
            if (discordSendInProgress) return;

            // Snapshot van waarden voor de thread
            final ActiveSkill skillSnapshot = activeSkill;
            final String modeSnapshot = getSkillName(skillSnapshot);
            final String statusSnapshot = paint != null ? paint.getCurrentStatus() : "n/a";
            final int killsSnapshot = paint != null ? paint.getKills() : 0;
            final int lootItemsSnapshot = paint != null ? paint.getLootedItems() : 0;
            final long lootValueSnapshot = paint != null ? paint.getTotalLootValue() : 0;
            final String runtimeSnapshot = paint != null ? paint.formatRuntime() : "n/a";

            discordSendInProgress = true;
            lastDiscordSendMs = now;

            String gpStr = paint != null ? paint.formatGp(lootValueSnapshot) : String.valueOf(lootValueSnapshot);
            final String content = buildDiscordUpdateText(skillSnapshot, modeSnapshot, statusSnapshot,
                    killsSnapshot, lootItemsSnapshot, lootValueSnapshot, runtimeSnapshot, gpStr,
                    config.discordDetailedWebhookText());
            final String webhookFinal = webhookUrl.trim();

            // Zelfde aanpak als RuneLite Screenshot / Dink: volgende game-frame (werkt ook als venster niet op focus).
            // Robot-screengrab geeft vaak zwart als de client niet zichtbaar is.
            if (drawManager != null && clientThread != null) {
                clientThread.invokeLater(() -> drawManager.requestNextFrameListener((Image frame) -> {
                    BufferedImage img = null;
                    try {
                        if (frame != null) {
                            img = ImageUtil.bufferedImageFromImage(frame);
                        }
                    } catch (Exception e) {
                        DebugLog.log("Discord", "Game-frame → image mislukt: " + e.getMessage());
                    }
                    if (img == null) {
                        img = DiscordWebhookSender.captureCanvas();
                    }
                    sendDiscordWebhookInBackground(webhookFinal, content, img);
                }));
            } else {
                new Thread(() -> {
                    try {
                        DebugLog.log("Discord", "Screenshot sturen (Robot, geen DrawManager)…");
                        BufferedImage img = DiscordWebhookSender.captureCanvas();
                        sendDiscordWebhookInBackground(webhookFinal, content, img);
                    } catch (Exception e) {
                        DebugLog.log("Discord", "Discord capture error: " + e.getMessage());
                        discordSendInProgress = false;
                    }
                }, "CombatBot-DiscordCapture").start();
            }
        } catch (Exception ignored) {
        }
    }

    /** Oude directe switch — nu alleen aangeroepen na cleanup. */
    private void performSkillSwitchInternal() {
        if (activeSkill != ActiveSkill.LOOT) {
            barbLootSessionActive = false;
        }
        resetHandlerForSkill(activeSkill);

        paint.setActiveSkill(getSkillName(activeSkill));
        paint.setCurrentStatus("🔄 Gewisseld naar " + getSkillName(activeSkill));
        paint.setLastAntiBanAction("✓ Skill wissel: " + getSkillName(activeSkill));

        switchCooldownUntil = System.currentTimeMillis() + SWITCH_COOLDOWN_MS;
        resetSwitchTimer();
    }

    /** Wordt aangeroepen door het panel wanneer de gebruiker op "Switch Now" klikt. */
    public void requestSwitchNow() { switchNowRequested = true; }
    public void requestNextAccountNow() { nextAccountRequested = true; }

    private void resetIdleStuckMonitor() {
        idleStuckLastTile = null;
        idleStuckSinceMs = 0L;
        idleStuckLastWarnMs = 0L;
        idleStuckLastRecoveryMs = 0L;
        idleStuckRecoveryCount = 0;
        starterLastStatusBucket = "";
        starterStatusSinceMs = 0L;
        starterStatusLastWarnMs = 0L;
        starterStatusLastRecoveryMs = 0L;
    }

    private String resolveCurrentRsnForLearning(IPlayer local) {
        if (local != null && local.getName() != null && !local.getName().trim().isEmpty()) {
            return Text.removeTags(local.getName()).trim();
        }
        if (lastTrackedPlayerName != null && !lastTrackedPlayerName.trim().isEmpty()) {
            return lastTrackedPlayerName.trim();
        }
        return "unknown";
    }

    private String normalizeStuckStatusBucket(String status) {
        if (status == null || status.trim().isEmpty()) {
            return "unknown";
        }
        String s = status.toLowerCase(Locale.ROOT);
        if (s.contains("karamja dock")) return "imps_karamja_dock";
        // Banking-trip boot (status zegt niet "karamja dock") — zelfde dock-vastloop-profiel als Musa Point.
        if (s.contains("boot naar port sarim")
                || (s.contains("[boat]") && s.contains("port sarim"))) {
            return "imps_karamja_dock";
        }
        if (s.contains("gear aantrekken")) return "imps_gear_aantrekken";
        if (s.contains("-> bank")) return "to_bank";
        if (s.contains("-> ge")) return "to_ge";
        s = s.replaceAll("[^a-z0-9_\\- ]", " ").replaceAll("\\s+", "_");
        return s.length() > 42 ? s.substring(0, 42) : s;
    }

    private String normalizeStarterStatusBucket(String status) {
        if (status == null || status.trim().isEmpty()) {
            return "starter_unknown";
        }
        String s = status.toLowerCase(Locale.ROOT);
        if (s.contains("naar train-gebied")) return "starter_to_train";
        if (s.contains("naar draynor bank") || s.contains("naar lumbridge bank")) return "starter_to_bank";
        if (s.contains("koken")) return "starter_cook";
        if (s.contains("vuur")) return "starter_fire";
        if (s.contains("eten")) return "starter_eat";
        if (s.contains("npc-gevecht")) return "starter_wait_npc_combat";
        s = s.replaceAll("[^a-z0-9_\\- ]", " ").replaceAll("\\s+", "_");
        return "starter_" + (s.length() > 34 ? s.substring(0, 34) : s);
    }

    private int handleStarterStatusLearningRecovery() {
        if (activeSkill != ActiveSkill.STARTER || starterSkillHandler == null) {
            return 0;
        }
        IPlayer local = Players.getLocal();
        if (local == null) {
            return 0;
        }
        String status = paint != null ? paint.getCurrentStatus() : "";
        String bucket = normalizeStarterStatusBucket(status);
        long now = System.currentTimeMillis();
        if (!bucket.equals(starterLastStatusBucket)) {
            starterLastStatusBucket = bucket;
            starterStatusSinceMs = now;
            starterStatusLastWarnMs = 0L;
            return 0;
        }
        if (starterStatusSinceMs <= 0L) {
            starterStatusSinceMs = now;
            return 0;
        }
        long durMs = now - starterStatusSinceMs;

        String rsn = resolveCurrentRsnForLearning(local);
        String learnKey = rsn.toLowerCase(Locale.ROOT) + "|STARTER|" + bucket;
        StarterStatusLearningStore.Entry learned = StarterStatusLearningStore.get(config, learnKey);
        int seen = learned != null ? learned.count : 0;
        long dynamicTriggerMs = seen >= 1 ? 45_000L : 75_000L;

        if (durMs >= 30_000L && now - starterStatusLastWarnMs >= 15_000L) {
            starterStatusLastWarnMs = now;
            DebugLog.log("StarterSkill", "[StarterWarn] " + (durMs / 1000) + "s status=" + bucket + " rsn=" + rsn);
        }
        if (durMs < dynamicTriggerMs || (now - starterStatusLastRecoveryMs) < 20_000L) {
            return 0;
        }

        starterStatusLastRecoveryMs = now;
        StarterStatusLearningStore.increment(stormConfigManager, config, learnKey);
        StarterStatusLearningStore.Entry after = StarterStatusLearningStore.get(config, learnKey);
        int learnedCount = after != null ? after.count : (seen + 1);
        DebugLog.log("StarterSkill", "[StarterRecovery] status=" + bucket + " learned=" + learnedCount + " rsn=" + rsn);

        // Eerste stap: zachte recovery binnen starter mode.
        if (learnedCount <= 1) {
            starterSkillHandler.resetState();
            if (paint != null) {
                paint.setCurrentStatus("⚠ Starter recovery: reset state");
                paint.setLastAntiBanAction("Starter recovery: soft reset");
            }
            starterStatusSinceMs = now;
            return 700;
        }

        // Herhaald patroon: agressiever (account switch indien mogelijk).
        if (config.accountSwitchEnabled() && accountSwitcher != null && accountSwitcher.getAccountCount() >= 2) {
            boolean switching = accountSwitcher.requestImmediateSwitch("⚠ Starter learned-stall: " + bucket);
            if (switching) {
                if (paint != null) {
                    paint.setCurrentStatus("⚠ Starter recovery: volgende account");
                    paint.setLastAntiBanAction("Starter recovery: next account");
                }
                resetIdleStuckMonitor();
                return 250;
            }
        }

        initiateSkillSwitch();
        if (paint != null) {
            paint.setCurrentStatus("⚠ Starter recovery: skill switch");
            paint.setLastAntiBanAction("Starter recovery: skill switch");
        }
        resetIdleStuckMonitor();
        return 700;
    }

    private void monitorRepeatedLoopDebugSignature() {
        IPlayer lp = Players.getLocal();
        if (lp == null || lp.getWorldLocation() == null) {
            loopWatchLastSignature = "";
            loopWatchSignatureSinceMs = 0L;
            loopWatchLastLogMs = 0L;
            return;
        }
        String status = paint != null ? paint.getCurrentStatus() : "";
        WorldPoint p = lp.getWorldLocation();
        String signature = activeSkill + "|" + status + "|" + p.getX() + "," + p.getY()
                + "|m=" + lp.isMoving() + "|a=" + lp.getAnimation();
        long now = System.currentTimeMillis();
        if (!signature.equals(loopWatchLastSignature)) {
            loopWatchLastSignature = signature;
            loopWatchSignatureSinceMs = now;
            loopWatchLastLogMs = 0L;
            return;
        }
        long durMs = (loopWatchSignatureSinceMs > 0L) ? (now - loopWatchSignatureSinceMs) : 0L;
        if (durMs < 45_000L) {
            return;
        }
        if (now - loopWatchLastLogMs < LOOP_WATCH_LOG_EVERY_MS) {
            return;
        }
        loopWatchLastLogMs = now;
        String rsn = resolveCurrentRsnForLearning(lp);
        DebugLog.log("CombatBot", "[LoopWatch] " + rsn
                + " blijft in dezelfde signature voor " + (durMs / 1000) + "s"
                + " | skill=" + activeSkill
                + " | status=" + status
                + " | tile=" + p.getX() + "," + p.getY()
                + " | moving=" + lp.isMoving()
                + " | anim=" + lp.getAnimation());
    }

    /**
     * Vastloopdetectie: speler blijft te lang stil op exact dezelfde tile zonder animatie.
     * Uitzonderingen (geen stuck-teller): GE/bank open; NPC-combat ({@link AccountSwitchCombatGate});
     * open dialoog — stilstaan is dan normaal (o.a. farmen/kopen/goblins tussen kills met korte idle).
     * Herstel:
     * 1) Bij Imps eerst soft-reset van handler + gear prep.
     * 2) Daarna account-switch (indien mogelijk), anders skill-switch.
     */
    private int handleIdleStuckRecovery() {
        IPlayer local = Players.getLocal();
        if (local == null || local.getWorldLocation() == null) {
            resetIdleStuckMonitor();
            return 0;
        }

        long now = System.currentTimeMillis();
        WorldPoint tile = local.getWorldLocation();
        boolean sameTile = idleStuckLastTile != null && idleStuckLastTile.equals(tile);
        boolean idleNoAnim = !local.isMoving() && local.getAnimation() == -1;

        if (!sameTile || !idleNoAnim) {
            idleStuckLastTile = tile;
            idleStuckSinceMs = now;
            idleStuckLastWarnMs = 0L;
            return 0;
        }

        try {
            if (GrandExchange.isOpen() || Bank.isOpen()) {
                resetIdleStuckMonitor();
                return 0;
            }
        } catch (Throwable ignored) {
        }

        try {
            if (AccountSwitchCombatGate.isInNpcCombat()) {
                resetIdleStuckMonitor();
                return 0;
            }
        } catch (Throwable ignored) {
        }

        try {
            if (Dialog.isOpen()) {
                resetIdleStuckMonitor();
                return 0;
            }
        } catch (Throwable ignored) {
        }

        if (idleStuckSinceMs <= 0L) {
            idleStuckSinceMs = now;
        }
        long stuckMs = now - idleStuckSinceMs;
        String statusNow = paint != null ? paint.getCurrentStatus() : "";
        String statusBucketNow = normalizeStuckStatusBucket(statusNow);
        String rsnNow = resolveCurrentRsnForLearning(local);
        String preKey = rsnNow.toLowerCase(Locale.ROOT) + "|" + activeSkill + "|" + statusBucketNow;
        StuckLearningStore.Entry preLearned = StuckLearningStore.get(config, preKey);
        int preCount = preLearned != null ? preLearned.count : 0;
        long dynamicTriggerMs = IDLE_STUCK_TRIGGER_MS;
        if ("imps_karamja_dock".equals(statusBucketNow)) {
            dynamicTriggerMs = preCount >= 1 ? 60_000L : 90_000L;
        }

        if (stuckMs >= IDLE_STUCK_WARN_MS && now - idleStuckLastWarnMs >= 15_000L) {
            idleStuckLastWarnMs = now;
            String status = paint != null ? paint.getCurrentStatus() : "";
            DebugLog.log("CombatBot", "[StuckWarn] " + (stuckMs / 1000) + "s stil op "
                    + tile.getX() + "," + tile.getY() + " | skill=" + activeSkill + " | status=" + status);
        }

        if (stuckMs < dynamicTriggerMs || (now - idleStuckLastRecoveryMs) < IDLE_STUCK_RECOVERY_COOLDOWN_MS) {
            return 0;
        }

        idleStuckLastRecoveryMs = now;
        idleStuckRecoveryCount++;
        String status = paint != null ? paint.getCurrentStatus() : "";
        String rsn = resolveCurrentRsnForLearning(local);
        String statusBucket = normalizeStuckStatusBucket(status);
        String contextKey = rsn.toLowerCase(Locale.ROOT) + "|" + activeSkill + "|" + statusBucket;
        StuckLearningStore.increment(stormConfigManager, config, contextKey);
        StuckLearningStore.Entry learned = StuckLearningStore.get(config, contextKey);
        int learnedCount = learned != null ? learned.count : 1;
        DebugLog.log("CombatBot", "[StuckRecovery] trigger#" + idleStuckRecoveryCount + " na "
                + (stuckMs / 1000) + "s | tile=" + tile.getX() + "," + tile.getY()
                + " | skill=" + activeSkill + " | status=" + status + " | learned=" + learnedCount);

        AccountBehaviorProfileStore.recordEnvironmentStruggle(stormConfigManager, config, rsn);

        boolean dockContext = "imps_karamja_dock".equals(statusBucket);
        boolean aggressiveRecovery = learnedCount >= 2;
        if (activeSkill == ActiveSkill.IMPS && impsHandler != null && dockContext && aggressiveRecovery) {
            impsHandler.setPreferAlternateKaramjaDockRoute(true);
        }
        if (activeSkill == ActiveSkill.IMPS && idleStuckRecoveryCount <= 1 && !aggressiveRecovery && impsHandler != null) {
            impsHandler.resetState();
            impsHandler.startGearPreparation();
            if (paint != null) {
                paint.setCurrentStatus("⚠ Stuck detectie: Imps herstel");
                paint.setLastAntiBanAction("Stuck recovery: Imps soft reset");
            }
            return 900;
        }

        if (config.accountSwitchEnabled() && accountSwitcher != null && accountSwitcher.getAccountCount() >= 2) {
            boolean switching = accountSwitcher.requestImmediateSwitch("⚠ Stuck detectie: te lang stil op 1 tile");
            if (switching) {
                if (paint != null) {
                    paint.setCurrentStatus("⚠ Stuck detectie: volgende account" + (aggressiveRecovery ? " (geleerd)" : ""));
                    paint.setLastAntiBanAction("Stuck recovery: next account (" + learnedCount + "x)");
                }
                resetIdleStuckMonitor();
                return 250;
            }
        }

        initiateSkillSwitch();
        if (paint != null) {
            paint.setCurrentStatus("⚠ Stuck detectie: skill switch");
            paint.setLastAntiBanAction("Stuck recovery: skill switch");
        }
        resetIdleStuckMonitor();
        return 700;
    }

    /** Wordt aangeroepen door het panel wanneer de gebruiker op "Sell now" klikt. */
    public void requestSellNow() { sellNowRequested = true; }

    /** Wordt aangeroepen door het panel wanneer de gebruiker op "Log in" klikt (Centers-tab). */
    public void requestLoginNow() {
        loginNowRequested = true;
        // Loop wordt op login-scherm vaak niet aangeroepen; voer login direct uit op game-thread
        if (clientThread != null && sameAccountRelogger != null) {
            clientThread.invokeLater(() -> {
                if (sameAccountRelogger.tryLoginNow()) {
                    loginNowRequested = false;
                    stormConfigManager.setConfiguration("combatbot", "loginNow", "false");
                }
            });
        }
    }

    /**
     * Panel-uitloggen (knop in het Combat Bot-venster).
     * <p>
     * <b>Versus account-wissel ({@link AccountSwitcher}):</b> hier geen automatisch volgend account — alleen uitloggen.
     * Rotatie gebruikt dezelfde combat/loot-check ({@link #canCompletePanelLogoutNow()}) vóór {@code Game.logout()} en
     * daarna credentials/hop/login voor het volgende account.
     */
    private void requestPanelLogout() {
        if (!panelLogoutRequested) {
            panelLogoutRequestedAtMs = System.currentTimeMillis();
        }
        panelLogoutRequested = true;
        DebugLog.log("CombatBot", "Panel: uitloggen gepland (combat/loot → Game.logout op client thread)");
        if (paint != null) {
            paint.setLastAntiBanAction("🚪 Uitloggen gepland…");
        }
    }

    private void syncPanelLogoutSuppressToHandlers() {
        boolean accountSwitchWantsSuppress = config.accountSwitchEnabled()
                && accountSwitcher != null
                && accountSwitcher.wantsCombatSuppressForLogoutFlow();
        boolean on = panelLogoutRequested || accountSwitchWantsSuppress;
        if (combatHandler != null) {
            combatHandler.setSuppressNewAttacksForLogout(on);
        }
        if (giantsHandler != null) {
            giantsHandler.setSuppressNewAttacksForLogout(on);
        }
    }

    private boolean mustWaitCombatForPanelLogout() {
        if (AccountSwitchCombatGate.isInNpcCombat()) {
            return true;
        }
        if (activeSkill == ActiveSkill.COMBAT && combatHandler != null && combatHandler.isInPlayerCombatInteraction()) {
            return true;
        }
        if (activeSkill == ActiveSkill.GIANTS && giantsHandler != null && giantsHandler.isCurrentlyInCombat()) {
            return true;
        }
        return false;
    }

    private boolean mustWaitLootForPanelLogout() {
        if (activeSkill == ActiveSkill.COMBAT && combatHandler != null) {
            return combatHandler.hasPendingLootBeforePanelLogout();
        }
        if (activeSkill == ActiveSkill.GIANTS && giantsHandler != null) {
            return giantsHandler.hasPendingLootBeforePanelLogout();
        }
        return false;
    }

    private boolean canCompletePanelLogoutNow() {
        return !mustWaitCombatForPanelLogout() && !mustWaitLootForPanelLogout();
    }

    /**
     * Storm/RuneLite: {@link Game#logout()} moet op de client/game thread — niet op de Swing-EDT.
     * Geen wachtrij van honderden {@code invokeLater} als de loop blijft aanroepen vóór de vorige run.
     */
    private volatile boolean panelLogoutInvokePending;

    private void invokeGameLogoutOnClientThread() {
        Runnable r = () -> {
            panelLogoutInvokePending = false;
            try {
                DebugLog.log("CombatBot", "Game.logout() via ClientThread.invokeLater");
                Game.logout();
            } catch (Throwable t) {
                DebugLog.log("CombatBot", "Game.logout() fout: " + t.getMessage());
            }
        };
        if (clientThread != null) {
            if (panelLogoutInvokePending) {
                return;
            }
            panelLogoutInvokePending = true;
            clientThread.invokeLater(r);
        } else {
            r.run();
        }
    }

    private void completePanelLogoutNow() {
        panelLogoutRequested = false;
        panelLogoutRequestedAtMs = 0L;
        syncPanelLogoutSuppressToHandlers();
        invokeGameLogoutOnClientThread();
    }

    private int finalizePanelLogoutIfReady(int handlerDelay) {
        if (!panelLogoutRequested || !Game.isLoggedIn()) {
            return handlerDelay;
        }
        if (!canCompletePanelLogoutNow()) {
            long now = System.currentTimeMillis();
            if (panelLogoutRequestedAtMs > 0L && now - panelLogoutRequestedAtMs >= PANEL_LOGOUT_FORCE_TIMEOUT_MS) {
                DebugLog.log("CombatBot", "Panel logout timeout (" + PANEL_LOGOUT_FORCE_TIMEOUT_MS
                        + "ms) -> force Game.logout()");
                if (paint != null) {
                    paint.setLastAntiBanAction("🚪 Uitloggen timeout -> force logout");
                }
                completePanelLogoutNow();
                return Math.max(handlerDelay, 2200);
            }
            if (mustWaitCombatForPanelLogout()) {
                paint.setLastAntiBanAction("🚪 Uitloggen: wacht tot combat voorbij (geen nieuwe aanvallen)…");
            } else if (mustWaitLootForPanelLogout()) {
                paint.setLastAntiBanAction("🚪 Uitloggen: eerst loot…");
            }
            return handlerDelay;
        }
        completePanelLogoutNow();
        // Iets langere tick na logout zodat de client dezelfde “adem” krijgt als bij account-wissel
        return Math.max(handlerDelay, 2200);
    }

    /**
     * Bot staat uit maar panel wil uitloggen: draai één handler-tick voor combat/loot, of log direct uit.
     */
    private int tickPanelLogoutWhileBotStopped() {
        if (!canCompletePanelLogoutNow()) {
            long now = System.currentTimeMillis();
            if (panelLogoutRequestedAtMs > 0L && now - panelLogoutRequestedAtMs >= PANEL_LOGOUT_FORCE_TIMEOUT_MS) {
                DebugLog.log("CombatBot", "Panel logout timeout while bot stopped ("
                        + PANEL_LOGOUT_FORCE_TIMEOUT_MS + "ms) -> force Game.logout()");
                if (paint != null) {
                    paint.setLastAntiBanAction("🚪 Uitloggen timeout (stop) -> force logout");
                }
                completePanelLogoutNow();
                return 2200;
            }
            int h = runActiveSkillHandlerLoopOnly();
            if (h > 0) {
                return h;
            }
            return 600;
        }
        completePanelLogoutNow();
        return 2200;
    }

    private int runActiveSkillHandlerLoopOnly() {
        if (activeSkill == ActiveSkill.LOOT) {
            return lootHandler != null ? lootHandler.loop() : 600;
        }
        switch (activeSkill) {
            case TUT:
                return tutorialModeHandler != null ? tutorialModeHandler.loop() : 700;
            case STARTER:
                return starterSkillHandler != null ? starterSkillHandler.loop() : 600;
            case WOODCUTTING:
                return woodcutterHandler != null ? woodcutterHandler.loop() : 600;
            case MINING:
                return miningHandler != null ? miningHandler.loop() : 600;
            case FISHING:
                return fishingHandler != null ? fishingHandler.loop() : 600;
            case IMPS:
                return impsHandler != null ? impsHandler.loop() : 600;
            case GIANTS:
                return giantsHandler != null ? giantsHandler.loop() : 600;
            case BARBARIAN:
                return barbarianHandler != null ? barbarianHandler.loop() : 600;
            case COMBAT:
            default:
                return combatHandler != null ? combatHandler.loop() : 600;
        }
    }

    /** Skills > General: test de automatische re-log inlog-flow (meerdere pogingen) vanaf het login-scherm. */
    public void requestTestAutoLogin() {
        if (clientThread != null && sameAccountRelogger != null) {
            clientThread.invokeLater(() -> sameAccountRelogger.startTestAutoLoginFromLoginScreen());
        }
    }

    public void requestTestPlayerLookupAntiban() {
        if (antiBan == null) {
            return;
        }
        int delay = antiBan.triggerPlayerLookupTest();
        if (paint != null) {
            paint.setLastAntiBanAction("🧪 Lookup test gestart");
        }
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public String requestTestHumanMicroMouse() {
        if (antiBan == null) {
            return "Anti-ban nog niet geïnitialiseerd";
        }
        try {
            return antiBan.triggerHumanMicroMouseTest();
        } catch (Throwable t) {
            return "Fout: " + t.getMessage();
        }
    }

    public String requestTestFidgetBurst() {
        if (antiBan == null) {
            return "Anti-ban nog niet geïnitialiseerd";
        }
        try {
            return antiBan.triggerFidgetBurstTest();
        } catch (Throwable t) {
            return "Fout: " + t.getMessage();
        }
    }

    public void requestTestLampHoverWidgets() {
        Thread t = new Thread(this::runLampHoverWidgetTest, "combatbot-lamp-hover-test");
        t.setDaemon(true);
        t.start();
    }

    /** Oude werkende flow: vaste interface 240 (Attack 2, Magic 5, Confirm 27 + fallbacks), alleen hover. */
    private void runLampHoverWidgetTest() {
        try {
            DebugLog.log("Lamp", "[TEST] Start lamp hover test");
            clickLampInventoryOnceForTest();
            sleepSafe(650, 1200);

            hoverWidgetForTest(240, 2, "Attack");
            sleepSafe(260, 480);
            hoverWidgetForTest(240, 5, "Magic");
            sleepSafe(260, 480);
            if (!hoverWidgetForTest(240, 27, "Confirm")) {
                if (!hoverWidgetForTest(24, 27, "Confirm fallback 24,27")) {
                    hoverWidgetByKeywordForTest(240, "confirm", "Confirm keyword");
                }
            }

            DebugLog.log("Lamp", "[TEST] Klaar lamp hover test");
        } catch (Throwable t) {
            DebugLog.log("Lamp", "[TEST] Fout: " + t.getMessage());
        }
    }

    private void clickLampInventoryOnceForTest() {
        try {
            IInventoryItem lamp = Inventory.getFirst(i -> i != null && i.getId() == GENIE_LAMP_ITEM_ID);
            if (lamp == null) {
                DebugLog.log("Lamp", "[TEST] Geen lamp in inventory (id 2528)");
                return;
            }
            if (lamp.hasAction("Rub")) {
                lamp.interact("Rub");
            } else if (lamp.hasAction("Use")) {
                lamp.interact("Use");
            } else {
                lamp.interact(0);
            }
            DebugLog.log("Lamp", "[TEST] Lamp interact gedaan (Rub/Use)");
        } catch (Throwable t) {
            DebugLog.log("Lamp", "[TEST] Lamp interact fout: " + t.getMessage());
        }
    }

    private boolean hoverWidgetForTest(int group, int child, String label) {
        Rectangle b = readWidgetBoundsOnClientThread(group, child);
        if (b == null || b.width <= 0 || b.height <= 0) {
            DebugLog.log("Lamp", "[TEST] " + label + " widget niet gevonden: " + group + "," + child);
            return false;
        }
        int x = b.x + Math.max(2, b.width / 2);
        int y = b.y + Math.max(2, b.height / 2);
        try {
            Canvas canvas = net.storm.sdk.game.Client.getCanvas();
            if (canvas != null) {
                int maxX = Math.max(20, canvas.getWidth() - 3);
                int maxY = Math.max(20, canvas.getHeight() - 3);
                x = Math.max(3, Math.min(maxX, x));
                y = Math.max(3, Math.min(maxY, y));
                smoothHoverMoveTo(x, y, canvas);
                sleepSafe(180, 320); // korte menselijke hover hold
                if (paint != null) {
                    paint.setLastAntiBanAction("🧞 Lamp test hover: " + label);
                }
                DebugLog.log("Lamp", "[TEST] Hover " + label + " -> (" + x + "," + y + ") bounds="
                        + b.x + "," + b.y + " " + b.width + "x" + b.height);
                return true;
            }
        } catch (Throwable t) {
            DebugLog.log("Lamp", "[TEST] Hover " + label + " fout: " + t.getMessage());
        }
        return false;
    }

    private boolean hoverWidgetByKeywordForTest(int group, String keyword, String label) {
        Rectangle b = readWidgetBoundsByKeywordOnClientThread(group, keyword);
        if (b == null || b.width <= 0 || b.height <= 0) {
            DebugLog.log("Lamp", "[TEST] " + label + " niet gevonden via keyword '" + keyword + "'");
            return false;
        }
        int x = b.x + Math.max(2, b.width / 2);
        int y = b.y + Math.max(2, b.height / 2);
        try {
            Canvas canvas = net.storm.sdk.game.Client.getCanvas();
            if (canvas != null) {
                int maxX = Math.max(20, canvas.getWidth() - 3);
                int maxY = Math.max(20, canvas.getHeight() - 3);
                x = Math.max(3, Math.min(maxX, x));
                y = Math.max(3, Math.min(maxY, y));
                smoothHoverMoveTo(x, y, canvas);
                sleepSafe(180, 320);
                if (paint != null) {
                    paint.setLastAntiBanAction("🧞 Lamp test hover: " + label);
                }
                DebugLog.log("Lamp", "[TEST] Hover " + label + " -> (" + x + "," + y + ") bounds="
                        + b.x + "," + b.y + " " + b.width + "x" + b.height);
                return true;
            }
        } catch (Throwable t) {
            DebugLog.log("Lamp", "[TEST] Hover " + label + " fout: " + t.getMessage());
        }
        return false;
    }

    private Rectangle readWidgetBoundsOnClientThread(int group, int child) {
        if (client == null) {
            return null;
        }
        if (clientThread == null) {
            var w = client.getWidget(group, child);
            return w != null && !w.isHidden() ? w.getBounds() : null;
        }
        AtomicReference<Rectangle> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        clientThread.invokeLater(() -> {
            try {
                var w = client.getWidget(group, child);
                if (w != null && !w.isHidden()) {
                    ref.set(w.getBounds());
                }
            } catch (Throwable ignored) {
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(350, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return ref.get();
    }

    private Rectangle readWidgetBoundsByKeywordOnClientThread(int group, String keyword) {
        if (client == null || keyword == null || keyword.isEmpty()) {
            return null;
        }
        String k = keyword.toLowerCase(Locale.ROOT);
        if (clientThread == null) {
            for (int child = 0; child <= 80; child++) {
                Widget root = client.getWidget(group, child);
                Widget found = findRlWidgetByKeyword(root, k);
                if (found != null) {
                    Rectangle b = found.getBounds();
                    if (b != null && b.width > 0 && b.height > 0) {
                        return b;
                    }
                }
            }
            return null;
        }
        AtomicReference<Rectangle> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        clientThread.invokeLater(() -> {
            try {
                for (int child = 0; child <= 80; child++) {
                    Widget root = client.getWidget(group, child);
                    Widget found = findRlWidgetByKeyword(root, k);
                    if (found != null) {
                        Rectangle b = found.getBounds();
                        if (b != null && b.width > 0 && b.height > 0) {
                            ref.set(b);
                            break;
                        }
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(450, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return ref.get();
    }

    /**
     * Zelfde idee als {@link #clickLampWidgetByKeywordInLampGroup}: lamp-teksten zitten vaak in geneste widgets,
     * niet alleen op (group, child) root-niveau.
     */
    private static Widget findRlWidgetByKeyword(Widget w, String keywordLower) {
        if (w == null || w.isHidden() || keywordLower == null || keywordLower.isEmpty()) {
            return null;
        }
        String name = w.getName();
        if (name != null && !name.isEmpty()) {
            String n = Text.removeTags(name).toLowerCase(Locale.ROOT);
            if (n.contains(keywordLower)) {
                Rectangle b = w.getBounds();
                if (b != null && b.width > 0 && b.height > 0) {
                    return w;
                }
            }
        }
        Widget[] children = w.getChildren();
        if (children != null) {
            for (Widget c : children) {
                Widget found = findRlWidgetByKeyword(c, keywordLower);
                if (found != null) {
                    return found;
                }
            }
        }
        children = w.getDynamicChildren();
        if (children != null) {
            for (Widget c : children) {
                Widget found = findRlWidgetByKeyword(c, keywordLower);
                if (found != null) {
                    return found;
                }
            }
        }
        try {
            Widget[] nested = w.getNestedChildren();
            if (nested != null) {
                for (Widget c : nested) {
                    Widget found = findRlWidgetByKeyword(c, keywordLower);
                    if (found != null) {
                        return found;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Zelfde geometrie/tween/hover-hold als {@link #hoverWidgetForTest}; daarna echte muisklik op de canvas.
     * Geen {@link Mouse#click(int, int, boolean)} hier: vanaf de client thread voert Storm die asynchroon uit,
     * waardoor de klik los kan laten lopen van de net gesimuleerde {@link Mouse#moved}-reeks. Low-level
     * pressed/released/clicked gebruikt dezelfde {@link Canvas} en coördinaten als de hover-test.
     */
    private boolean smoothMoveAndLeftClickLampWidget(Rectangle b, String logCtx) {
        if (b == null || b.width <= 0 || b.height <= 0) {
            return false;
        }
        Canvas canvas = net.storm.sdk.game.Client.getCanvas();
        if (canvas == null) {
            return false;
        }
        int x = b.x + Math.max(2, b.width / 2);
        int y = b.y + Math.max(2, b.height / 2);
        int maxX = Math.max(20, canvas.getWidth() - 3);
        int maxY = Math.max(20, canvas.getHeight() - 3);
        x = Math.max(3, Math.min(maxX, x));
        y = Math.max(3, Math.min(maxY, y));
        try {
            smoothHoverMoveTo(x, y, canvas);
            sleepSafe(180, 320);
            long tMove = System.currentTimeMillis();
            Mouse.moved(x, y, canvas, tMove);
            sleepSafe(28, 55);
            long tPress = System.currentTimeMillis();
            Mouse.pressed(x, y, canvas, tPress, MouseEvent.BUTTON1);
            sleepSafe(45, 95);
            long tRel = System.currentTimeMillis();
            Mouse.released(x, y, canvas, tRel, MouseEvent.BUTTON1);
            Mouse.clicked(x, y, canvas, tRel, MouseEvent.BUTTON1);
            DebugLog.log("Lamp", "Click " + logCtx + " -> (" + x + "," + y + ") bounds="
                    + b.x + "," + b.y + " " + b.width + "x" + b.height);
            return true;
        } catch (Throwable t) {
            DebugLog.log("Lamp", "Click " + logCtx + " fout: " + t.getMessage());
            return false;
        }
    }

    private void smoothHoverMoveTo(int toX, int toY, Canvas canvas) {
        net.runelite.api.Point p = client != null ? client.getMouseCanvasPosition() : null;
        int startX = (p != null && p.getX() >= 0) ? p.getX() : toX;
        int startY = (p != null && p.getY() >= 0) ? p.getY() : toY;
        int maxX = canvas != null ? Math.max(20, canvas.getWidth() - 3) : 760;
        int maxY = canvas != null ? Math.max(20, canvas.getHeight() - 3) : 500;
        int steps = 8 + random.nextInt(8);
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            double eased = (1.0 - Math.cos(t * Math.PI)) / 2.0;
            int x = (int) Math.round(startX + ((toX - startX) * eased)) + random.nextInt(3) - 1;
            int y = (int) Math.round(startY + ((toY - startY) * eased)) + random.nextInt(3) - 1;
            x = Math.max(3, Math.min(maxX, x));
            y = Math.max(3, Math.min(maxY, y));
            Mouse.moved(x, y, canvas, System.currentTimeMillis());
            sleepSafe(14, 32);
        }
    }

    private void sleepSafe(int minMs, int maxMs) {
        int high = Math.max(minMs, maxMs);
        int ms = minMs + random.nextInt(Math.max(1, high - minMs + 1));
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Jagex OAuth + {@link Game#setGameAccount} op de client. Volgorde gelijk aan {@link AccountSwitcher} (OAuth-modus,
     * dan account), daarna Storm-session/display. Wordt gepland op {@link ClientThread#invokeAtTickEnd} zodat de engine
     * na het wegschrijven van {@code credentials.properties} kan bijwerken — anders blijft het welkomstscherm soms visueel achter.
     */
    private void applyPreparedJagexLoginToClient(GameAccount acc, String dn, String cid, String sid) {
        try {
            net.storm.sdk.game.Client.setOAuthLoginMode();
            if (client != null && dn != null && !dn.isEmpty()) {
                client.setUsername(dn);
            }
            Game.setGameAccount(acc);
            if (cid != null && !cid.isEmpty()) {
                net.storm.sdk.game.Client.setCharacterId(cid);
            }
            if (sid != null && !sid.isEmpty()) {
                net.storm.sdk.game.Client.setSessionId(sid);
            }
            if (dn != null && !dn.isEmpty()) {
                net.storm.sdk.game.Client.setDisplayName(dn);
                net.storm.sdk.game.Client.setUsername(dn);
            }
            net.storm.sdk.game.Client.promptCredentials(false);
            if (client != null && dn != null && !dn.isEmpty()) {
                client.setUsername(dn);
            }
        } catch (Throwable t) {
            DebugLog.log("Accounts", "Storm Client.set* loginvelden: " + t.getMessage());
        }
        if (client != null && client.getCanvas() != null) {
            client.getCanvas().repaint();
        }
    }

    /**
     * Accounts-tab: dubbelklik op een rij zet het geselecteerde Jagex OAuth-account klaar op het login-scherm
     * (zelfde idee als account switcher: {@code Client.setOAuthLoginMode} + {@code Game.setGameAccount}).
     */
    private void prepareLoginFromManagedAccount(ManagedJagexAccountsStore.ManagedJagexAccountRow row) {
        if (clientThread == null || row == null) {
            return;
        }
        // Zelfde login-detectie als AccountSwitcher / SameAccountRelogger (RL GameState.LOGIN_SCREEN ≠ Jagex welkomst)
        clientThread.invokeLater(() -> {
            GameState gs = client != null ? client.getGameState() : null;
            boolean onLoginUi = Game.isOnLoginScreen()
                    || gs == GameState.LOGIN_SCREEN
                    || gs == GameState.LOGIN_SCREEN_AUTHENTICATOR;
            if (client == null || !onLoginUi) {
                if (paint != null) {
                    String hint = gs != null ? gs.name() : "?";
                    paint.setLastAntiBanAction("⚠ Dubbelklik: welkom/login-scherm (nu: " + hint + ")");
                }
                DebugLog.log("Accounts", "prepareLogin afgebroken: onLoginUi=false gs=" + (gs != null ? gs.name() : "?"));
                return;
            }
            File profielCreds = JagexCredentialsHelper.resolveLoginScreenCredentialsFile(
                    config.loginScreenCredentialsPath(), config.accountList());
            if (profielCreds != null) {
                if (!JagexCredentialsHelper.writeCredentialsPropertiesFile(profielCreds, row) && paint != null) {
                    paint.setLastAntiBanAction("⚠ Schrijven mislukt: " + profielCreds.getAbsolutePath());
                }
            }
            accountSwitcher.reload();
            JagexCredentialsHelper.saveManagedRowCredentials(row);
            // Eerst rij zelf (vers uit Bewerken); anders oude cache — verkeerde volgorde gaf stale tokens bij login.
            GameAccount acc = JagexCredentialsHelper.gameAccountFromManagedRow(row);
            if (acc == null) {
                acc = JagexCredentialsHelper.loadSavedCredentials(row.displayName.trim());
            }
            if (acc == null) {
                if (paint != null) {
                    paint.setLastAntiBanAction("⚠ " + row.displayName + ": geen session — vul in bij Bewerken…");
                }
                return;
            }
            String dn = row.displayName != null ? row.displayName.trim() : "";
            String cid = row.characterId != null ? row.characterId.trim() : "";
            String sid = row.sessionId != null ? row.sessionId.trim() : "";
            final GameAccount accFinal = acc;
            final File profielCredsFinal = profielCreds;
            final GameState gsFinal = gs;
            // Eén tick uitstellen: het grote welkomstlabel wordt vaak pas na een volledige tick ververst.
            clientThread.invokeAtTickEnd(() -> {
                applyPreparedJagexLoginToClient(accFinal, dn, cid, sid);
                String stormDisp = "";
                String stormChar = "";
                String stormUser = "";
                try {
                    String d = net.storm.sdk.game.Client.getDisplayName();
                    String c = net.storm.sdk.game.Client.getCharacterId();
                    String u = net.storm.sdk.game.Client.getUsername();
                    stormDisp = d != null ? d : "";
                    stormChar = c != null ? c : "";
                    stormUser = u != null ? u : "";
                } catch (Throwable ignored) {
                }
                String rlUser = "";
                String launcherName = "";
                try {
                    if (client != null) {
                        String ru = client.getUsername();
                        rlUser = ru != null ? ru : "";
                        String ln = client.getLauncherDisplayName();
                        launcherName = ln != null ? ln : "";
                    }
                } catch (Throwable ignored) {
                }
                DebugLog.log("Accounts", "prepareLogin OK display=" + row.displayName
                        + " gs=" + (gsFinal != null ? gsFinal.name() : "?")
                        + " stormAfter=[disp=" + stormDisp + " charId=" + stormChar + " user=" + stormUser + "]"
                        + " rlUser=[" + rlUser + "] launcherDisp=[" + launcherName + "]");
                if (paint != null) {
                    String sync = profielCredsFinal != null ? (" • " + profielCredsFinal.getName() + " bijgewerkt") : "";
                    if (profielCredsFinal == null) {
                        sync = " • zet Profiel credentials-pad of jagex: in lijst";
                    }
                    boolean launcherMismatch = !launcherName.isEmpty() && !dn.isEmpty()
                            && !launcherName.equalsIgnoreCase(dn);
                    if (launcherMismatch) {
                        paint.setLastAntiBanAction("→ " + dn + " klaar (Play) — welkomsttekst kan launcher-profiel tonen ("
                                + launcherName + "); herstart client om label te laten matchen" + sync);
                    } else {
                        paint.setLastAntiBanAction("→ Login: " + row.displayName + " — druk Play" + sync);
                    }
                }
            });
        });
    }

    /**
     * Zet credentials + GameAccount op het login-scherm (zelfde als dubbelklik), aangeroepen vanuit de game-loop
     * (o.a. {@link #pendingManagedLoginRow} na uitloggen naar het volgende rotation-account).
     */
    boolean syncPrepareManagedLoginOnLoginScreen(ManagedJagexAccountsStore.ManagedJagexAccountRow row) {
        if (row == null || row.isEmpty()) {
            return false;
        }
        GameState gs = client != null ? client.getGameState() : null;
        boolean onLoginUi = Game.isOnLoginScreen()
                || gs == GameState.LOGIN_SCREEN
                || gs == GameState.LOGIN_SCREEN_AUTHENTICATOR;
        if (!onLoginUi) {
            return false;
        }
        return applyManagedRowJagexLoginCore(row);
    }

    /**
     * Kern van dubbelklik / {@link AccountSwitcher}: profiel + {@link #applyPreparedJagexLoginToClient}.
     */
    private boolean applyManagedRowJagexLoginCore(ManagedJagexAccountsStore.ManagedJagexAccountRow row) {
        return applyManagedRowJagexLoginCore(row, null, true);
    }

    /**
     * @param deferClientApplyToTickEnd {@code true} voor handmatige voorbereiding op login-UI (welkomstscherm kan anders
     * visueel achterblijven). {@code false} voor {@link AccountSwitcher}, die direct daarna Play klikt: dan moet
     * {@link #applyPreparedJagexLoginToClient} al klaar zijn (synchroon {@code ClientThread#invoke}), anders raakt de client inconsistent.
     */
    private boolean applyManagedRowJagexLoginCore(ManagedJagexAccountsStore.ManagedJagexAccountRow row,
            String jagexCredentialsPathFromSwitcherEntry,
            boolean deferClientApplyToTickEnd) {
        if (row == null || row.isEmpty()) {
            return false;
        }
        // Schijf-I/O op de game/client-thread: anders faalt schrijven vaak (bestand door client vastgehouden) en zie je
        // credentials.properties niet veranderen terwijl het pad wél klopt.
        Runnable disk = () -> {
            File profielCreds = JagexCredentialsHelper.resolveLoginScreenCredentialsFile(
                    config.loginScreenCredentialsPath(), config.accountList(), jagexCredentialsPathFromSwitcherEntry);
            if (profielCreds != null) {
                boolean wrote = JagexCredentialsHelper.writeCredentialsPropertiesFile(profielCreds, row);
                if (!wrote) {
                    GameAccount syncGa = JagexCredentialsHelper.gameAccountFromManagedRow(row);
                    if (syncGa == null) {
                        syncGa = JagexCredentialsHelper.loadSavedCredentials(row.displayName.trim());
                    }
                    JagexCredentialsHelper.syncAccountToProfileForSwitcher(
                            profielCreds, row.displayName.trim(), config.pastedCredentials(), syncGa);
                }
            } else {
                DebugLog.log("Accounts", "Geen profiel-credentialsbestand (zet Profiel credentials.properties of jagex:-pad in lijst)");
                if (paint != null) {
                    paint.setLastAntiBanAction("⚠ Geen pad naar credentials.properties — zet \"Profiel credentials.properties\" op de Accounts-tab (zelfde bestand dat je open houdt in de editor)");
                }
            }
            // Geen accountSwitcher.reload(): die herbouwt accounts[] terwijl currentAccountIndex vaststaat
            JagexCredentialsHelper.saveManagedRowCredentials(row);
        };
        if (clientThread != null) {
            clientThread.invoke(disk);
        } else {
            disk.run();
        }
        GameAccount acc = JagexCredentialsHelper.gameAccountFromManagedRow(row);
        if (acc == null) {
            acc = JagexCredentialsHelper.loadSavedCredentials(row.displayName.trim());
        }
        if (acc == null) {
            return false;
        }
        String dn = row.displayName != null ? row.displayName.trim() : "";
        String cid = row.characterId != null ? row.characterId.trim() : "";
        String sid = row.sessionId != null ? row.sessionId.trim() : "";
        final GameAccount accFinal = acc;
        if (clientThread != null) {
            if (deferClientApplyToTickEnd) {
                clientThread.invokeAtTickEnd(() -> applyPreparedJagexLoginToClient(accFinal, dn, cid, sid));
            } else {
                clientThread.invoke(() -> applyPreparedJagexLoginToClient(accFinal, dn, cid, sid));
            }
        } else {
            applyPreparedJagexLoginToClient(accFinal, dn, cid, sid);
        }
        return true;
    }

    /**
     * Voor {@link AccountSwitcher}: zoek de rij op de Accounts-tab en gebruik hetzelfde login-pad als dubbelklik.
     *
     * @param jagexCredentialsPathFromSwitcherEntry optioneel pad van deze rotatie-entry (zelfde als jagex:-regel).
     */
    private boolean tryApplyManagedRowLoginForSwitcher(String displayName, String jagexCredentialsPathFromSwitcherEntry) {
        if (displayName == null || displayName.trim().isEmpty()) {
            return false;
        }
        for (ManagedJagexAccountsStore.ManagedJagexAccountRow r
                : ManagedJagexAccountsStore.parseRows(config.managedJagexAccountsBlob())) {
            if (r.isEmpty() || r.displayName == null) {
                continue;
            }
            if (JagexCredentialsHelper.normalizeDisplayNameForMatch(r.displayName)
                    .equalsIgnoreCase(JagexCredentialsHelper.normalizeDisplayNameForMatch(displayName))) {
                return applyManagedRowJagexLoginCore(r, jagexCredentialsPathFromSwitcherEntry, false);
            }
        }
        return false;
    }

    /**
     * Per-account wereld vóór inloggen na rotatie: uit {@link ManagedJagexAccountsStore.ManagedJagexAccountRow#accountSwitchWorld}
     * en {@link ManagedJagexAccountsStore.ManagedJagexAccountRow#accountSwitchWorldHopEnabled}.
     * {@code 0} = willekeurige F2P via {@link AccountSwitchWorldHop#pickRandomF2pWorldId};
     * {@code -1} = geen wereld-hop (resolver + {@link AccountSwitcher}).
     */
    private int resolveAccountSwitchWorldForDisplayName(String displayName) {
        if (displayName == null || displayName.trim().isEmpty()) {
            return 0;
        }
        for (ManagedJagexAccountsStore.ManagedJagexAccountRow r
                : ManagedJagexAccountsStore.parseRows(config.managedJagexAccountsBlob())) {
            if (r.isEmpty() || r.displayName == null) {
                continue;
            }
            if (JagexCredentialsHelper.normalizeDisplayNameForMatch(r.displayName)
                    .equalsIgnoreCase(JagexCredentialsHelper.normalizeDisplayNameForMatch(displayName))) {
                if (!r.accountSwitchWorldHopEnabled) {
                    return -1;
                }
                return parseWorldIdDigits(r.accountSwitchWorld);
            }
        }
        return 0;
    }

    private static int parseWorldIdDigits(String raw) {
        if (raw == null) {
            return 0;
        }
        String t = raw.trim();
        if (t.isEmpty()) {
            return 0;
        }
        String digits = t.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return 0;
        }
        try {
            int v = Integer.parseInt(digits);
            return v > 0 ? v : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * FishingHandler: geen bruikbaar loop-pad naar Edgeville én Varrock West bank. Reden staat in debug-log.
     */
    private void stopBotAndLogoutForFishing(String reason) {
        String msg = reason != null ? reason : "Fishing bank-route faalde (onbekend)";
        DebugLog.log("CombatBot", "STOP + LOGOUT: " + msg);
        if (paint != null) {
            paint.setCurrentStatus("⛔ " + msg);
            paint.setLastAntiBanAction("⛔ " + msg);
        }
        if (stormConfigManager != null) {
            stormConfigManager.setConfiguration("combatbot", "botEnabled", "false");
        }
        wasBotEnabled = false;
        if (fishingHandler != null) {
            fishingHandler.resetState();
        }
        DebugLog.log("CombatBot", "Logout: Game.logout() (client thread)");
        invokeGameLogoutOnClientThread();
    }

    /** Herinitialiseer handler en kies een nieuw random center. */
    private void resetHandlerForSkill(ActiveSkill skill) {
        String centersData = getCenterString(skill);
        CenterManager.Center chosen = skill == ActiveSkill.LOOT ? null : pickCenterForSkillWithSafety(skill, centersData);

        switch (skill) {
            case TUT:
                tutorialModeHandler = new TutorialModeHandler(paint, config);
                break;
            case STARTER:
                starterSkillHandler = new StarterSkillHandler(config, antiBan, paint);
                wireStarterHandoffCallback();
                starterSkillHandler.resetState();
                break;
            case WOODCUTTING:
                woodcutterHandler = new WoodcutterHandler(config, antiBan, paint);
                woodcutterHandler.setTileMarkerManager(tileMarkerManager);
                woodcutterHandler.resetState();
                if (chosen != null) {
                    woodcutterHandler.setActiveCenter(chosen.point, chosen.radius);
                    areaOverlay.setActiveCenterForSkill(skill, chosen.point);
                }
                break;
            case MINING:
                miningHandler = new MiningHandler(config, antiBan, paint);
                miningHandler.setTileMarkerManager(tileMarkerManager);
                miningHandler.resetState();
                if (chosen != null) {
                    miningHandler.setActiveCenter(chosen.point, chosen.radius);
                    areaOverlay.setActiveCenterForSkill(skill, chosen.point);
                }
                break;
            case BARBARIAN:
                barbarianHandler = new BarbarianHandler(config, antiBan, paint);
                barbarianHandler.resetState();
                areaOverlay.setActiveCenterForSkill(skill, new WorldPoint(config.barbarianHallX(), config.barbarianHallY(), 0));
                break;
            case FISHING:
                fishingHandler = new FishingHandler(config, antiBan, paint, this::stopBotAndLogoutForFishing);
                fishingHandler.setTileMarkerManager(tileMarkerManager);
                fishingHandler.resetState();
                if (chosen != null) {
                    fishingHandler.setActiveCenter(chosen.point, chosen.radius);
                    areaOverlay.setActiveCenterForSkill(skill, chosen.point);
                }
                break;
            case IMPS:
                impsHandler = new ImpsHandler(config, antiBan, paint);
                impsHandler.setTileMarkerManager(tileMarkerManager);
                impsHandler.setQuestProgressConfigManager(stormConfigManager);
                impsHandler.resetState();
                impsHandler.startGearPreparation();
                if (chosen != null) {
                    impsHandler.setActiveCenter(chosen.point, chosen.radius);
                    areaOverlay.setActiveCenterForSkill(skill, chosen.point);
                } else {
                    impsHandler.setActiveCenter(null, 0);
                    areaOverlay.setActiveCenterForSkill(skill, null);
                }
                break;
            case GIANTS:
                giantsHandler = new GiantsHandler(config, antiBan, paint);
                giantsHandler.setTileMarkerManager(tileMarkerManager);
                giantsHandler.resetState();
                giantsHandler.startGearPreparation();
                break;
            case COMBAT:
                combatHandler = new CombatHandler(config, antiBan, paint, null);
                combatHandler.setTileMarkerManager(tileMarkerManager);
                combatHandler.resetState();
                if (chosen != null) {
                    combatHandler.setActiveCenter(chosen.point, chosen.radius);
                    areaOverlay.setActiveCenterForSkill(skill, chosen.point);
                }
                break;
            case LOOT:
                lootHandler = new LootHandler(config, antiBan, paint);
                lootHandler.setClientThread(clientThread);
                lootHandler.setConfigManager(stormConfigManager);
                lootHandler.setTileMarkerManager(tileMarkerManager);
                lootHandler.resetState();
                areaOverlay.setActiveCenterForSkill(ActiveSkill.LOOT, LootHandler.getActivityCenter());
                break;
            case VAMPIRE_SLAYER:
                vampireSlayerQuestHandler = new VampireSlayerQuestHandler(config, antiBan, paint, stormConfigManager);
                break;
        }

        if (skill == ActiveSkill.LOOT) {
            paint.setLastAntiBanAction("🗺 Loot: Barbarian spot " + LootHandler.getActivityCenter().getX()
                    + "," + LootHandler.getActivityCenter().getY());
        } else if (skill == ActiveSkill.VAMPIRE_SLAYER) {
            paint.setLastAntiBanAction("Quest: Vampyre Slayer");
        } else {
            paint.setLastAntiBanAction("🗺 " + getSkillName(skill) + " center: " +
                    (chosen != null ? chosen.point.getX() + "," + chosen.point.getY() + " r=" + chosen.radius : "vrij"));
        }
    }

    private List<ActiveSkill> getEnabledSkills() {
        ManagedJagexAccountsStore.ManagedJagexAccountRow rotRow =
                ManagedJagexAccountsStore.findRowForDisplayName(config, tryGetLocalRsn());
        if (rotRow != null && rotRow.rotationUseCustomProfile) {
            List<ActiveSkill> skills = new ArrayList<>();
            if (rotRow.rotationPickCombat && CenterManager.countActive(config.combatCenters()) > 0) {
                skills.add(ActiveSkill.COMBAT);
            }
            if (effectiveImpsInRotation()) {
                skills.add(ActiveSkill.IMPS);
            }
            if (effectiveGiantsMode()) {
                skills.add(ActiveSkill.GIANTS);
            }
            if (config.barbarianMode()) {
                skills.add(ActiveSkill.BARBARIAN);
            }
            if (rotRow.rotationPickWc && CenterManager.countActive(config.wcCenters()) > 0) {
                skills.add(ActiveSkill.WOODCUTTING);
            }
            if (rotRow.rotationPickMining && CenterManager.countActive(config.miningCenters()) > 0) {
                skills.add(ActiveSkill.MINING);
            }
            if (rotRow.rotationPickFishing && CenterManager.countActive(config.fishingCenters()) > 0) {
                skills.add(ActiveSkill.FISHING);
            }
            boolean barbLootListed = config.barbLootEnabled() || barbLootSessionActive
                    || (effectiveStartSkillSetting() == CombatBotConfig.StartSkill.BARB_LOOT && !barbLootStartMagicCheckDone);
            if (barbLootListed) {
                skills.add(ActiveSkill.LOOT);
            }
            return skills;
        }

        List<ActiveSkill> skills = new ArrayList<>();
        // Center-based skills: actieve centers zijn leidend.
        if (CenterManager.countActive(config.combatCenters()) > 0) {
            skills.add(ActiveSkill.COMBAT);
        }
        if (effectiveImpsInRotation()) {
            skills.add(ActiveSkill.IMPS);
        }
        if (effectiveGiantsMode()) {
            skills.add(ActiveSkill.GIANTS);
        }
        if (config.barbarianMode()) {
            skills.add(ActiveSkill.BARBARIAN);
        }
        if (CenterManager.countActive(config.wcCenters()) > 0) {
            skills.add(ActiveSkill.WOODCUTTING);
        }
        if (CenterManager.countActive(config.miningCenters()) > 0) {
            skills.add(ActiveSkill.MINING);
        }
        if (CenterManager.countActive(config.fishingCenters()) > 0) {
            skills.add(ActiveSkill.FISHING);
        }
        boolean barbLootListed = config.barbLootEnabled() || barbLootSessionActive
                || (effectiveStartSkillSetting() == CombatBotConfig.StartSkill.BARB_LOOT && !barbLootStartMagicCheckDone);
        if (barbLootListed) {
            skills.add(ActiveSkill.LOOT);
        }

        // Geen automatische Combat-fallback meer als niets actief is.
        // Als deze lijst leeg is, laat de re-log / pause logica het script veilig stoppen.
        return skills;
    }

    private int getLocalCombatLevelSafe() {
        try {
            IPlayer lp = Players.getLocal();
            return lp != null ? lp.getCombatLevel() : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private boolean shouldAvoidDraynorCentersNow() {
        if (System.currentTimeMillis() < draynorAvoidCentersUntilMs) {
            return true;
        }
        int cmb = getLocalCombatLevelSafe();
        return cmb > 0 && cmb < DARK_WIZARD_SAFE_COMBAT_LEVEL;
    }

    private boolean isDraynorLikeCenter(CenterManager.Center c) {
        if (c == null || c.point == null) return false;
        String n = c.name != null ? c.name.toLowerCase(Locale.ROOT) : "";
        if (n.contains("draynor")) return true;
        int x = c.point.getX();
        int y = c.point.getY();
        return x >= 3070 && x <= 3115 && y >= 3200 && y <= 3280;
    }

    private String filterCentersBySafety(ActiveSkill skill, String centersData) {
        if (centersData == null || centersData.trim().isEmpty()) {
            return "";
        }
        if (!(skill == ActiveSkill.WOODCUTTING || skill == ActiveSkill.FISHING)) {
            return centersData;
        }
        if (!shouldAvoidDraynorCentersNow()) {
            return centersData;
        }
        List<CenterManager.Center> all = CenterManager.parse(centersData);
        List<CenterManager.Center> safe = new ArrayList<>();
        for (CenterManager.Center c : all) {
            if (c == null || !c.active) continue;
            if (!isDraynorLikeCenter(c)) {
                safe.add(c);
            }
        }
        if (safe.isEmpty()) {
            return "";
        }
        return CenterManager.serialize(safe);
    }

    private int countSafeActiveCentersForSkill(ActiveSkill skill, String centersData) {
        return CenterManager.countActive(filterCentersBySafety(skill, centersData));
    }

    private CenterManager.Center pickCenterForSkillWithSafety(ActiveSkill skill, String centersData) {
        String filtered = filterCentersBySafety(skill, centersData);
        if (filtered == null || filtered.trim().isEmpty()) {
            return null;
        }
        if (skill == ActiveSkill.FISHING) {
            return CenterManager.pickFishingCenterForTraining(filtered, Skills.getLevel(Skill.FISHING));
        }
        return CenterManager.pickRandom(filtered);
    }

    private boolean handleNoUsableSkillForCurrentAccount() {
        long now = System.currentTimeMillis();
        if (now - lastAutoDeactivateMs < 10_000L) {
            return false;
        }
        List<ActiveSkill> enabled = getEnabledSkills();
        if (enabled.isEmpty()) {
            return false;
        }
        boolean hasUsable = false;
        for (ActiveSkill s : enabled) {
            if (s == ActiveSkill.WOODCUTTING) {
                if (countSafeActiveCentersForSkill(s, config.wcCenters()) > 0) {
                    hasUsable = true;
                    break;
                }
                continue;
            }
            if (s == ActiveSkill.FISHING) {
                if (countSafeActiveCentersForSkill(s, config.fishingCenters()) > 0) {
                    hasUsable = true;
                    break;
                }
                continue;
            }
            hasUsable = true;
            break;
        }
        if (hasUsable) {
            return false;
        }
        String rsn = tryGetLocalRsn();
        if (rsn == null || rsn.trim().isEmpty()) {
            return false;
        }
        String reason = "Auto non-actief: geen bruikbare skill (laag combat + unsafe centers)";
        deactivateManagedAccountRotation(rsn, reason);
        lastAutoDeactivateMs = now;
        if (paint != null) {
            paint.setCurrentStatus("⛔ " + reason);
            paint.setLastAntiBanAction("⛔ " + rsn + " non-actief gezet");
        }
        boolean switching = accountSwitcher != null
                && accountSwitcher.requestImmediateSwitch("🔄 " + rsn + " non-actief — volgende account");
        if (!switching) {
            stormConfigManager.setConfiguration("combatbot", "botEnabled", "false");
            wasBotEnabled = false;
            invokeGameLogoutOnClientThread();
        }
        return true;
    }

    private void deactivateManagedAccountRotation(String displayName, String reason) {
        if (displayName == null || displayName.trim().isEmpty()) return;
        List<ManagedJagexAccountsStore.ManagedJagexAccountRow> rows =
                new ArrayList<>(ManagedJagexAccountsStore.parseRows(config.managedJagexAccountsBlob()));
        String norm = JagexCredentialsHelper.normalizeDisplayNameForMatch(displayName);
        boolean changed = false;
        for (ManagedJagexAccountsStore.ManagedJagexAccountRow r : rows) {
            if (r == null || r.displayName == null) continue;
            if (!JagexCredentialsHelper.normalizeDisplayNameForMatch(r.displayName).equalsIgnoreCase(norm)) continue;
            if (r.rotationEnabled) {
                r.rotationEnabled = false;
                changed = true;
            }
            String msg = reason != null ? reason : "Auto non-actief";
            String stamp = java.time.LocalTime.now().withNano(0).toString();
            String tag = "[" + stamp + "] " + msg;
            r.notes = (r.notes == null || r.notes.trim().isEmpty()) ? tag : (r.notes + " | " + tag);
            changed = true;
        }
        if (changed) {
            ManagedJagexAccountsStore.persist(stormConfigManager, rows);
        }
    }

    private String tryGetLocalRsn() {
        try {
            if (!Game.isLoggedIn()) {
                return null;
            }
            IPlayer lp = Players.getLocal();
            if (lp == null || lp.getName() == null) {
                return null;
            }
            return Text.removeTags(lp.getName());
        } catch (Throwable t) {
            return null;
        }
    }

    /** Giants in rotatie: {@link CombatBotConfig#giantsMode()} met per-account override (ingelogde RSN). */
    private boolean effectiveGiantsMode() {
        return ManagedJagexAccountsStore.resolveGiantsModeForDisplayName(config, tryGetLocalRsn());
    }

    /** Imps in rotatie: {@code impsMode} + imps-centers, met per-account “alleen Imps / alleen Giants”. */
    private boolean effectiveImpsInRotation() {
        return ManagedJagexAccountsStore.resolveImpsInRotationForDisplayName(config, tryGetLocalRsn());
    }

    private ManagedJagexAccountsStore.ManagedJagexAccountRow findManagedRowByDisplayName(String displayName) {
        return ManagedJagexAccountsStore.findRowForDisplayName(config, displayName);
    }

    private void clearManagedCalibrateFlag(String displayName) {
        if (displayName == null || displayName.trim().isEmpty()) {
            return;
        }
        List<ManagedJagexAccountsStore.ManagedJagexAccountRow> rows =
                new ArrayList<>(ManagedJagexAccountsStore.parseRows(config.managedJagexAccountsBlob()));
        boolean changed = false;
        String norm = JagexCredentialsHelper.normalizeDisplayNameForMatch(displayName);
        for (ManagedJagexAccountsStore.ManagedJagexAccountRow r : rows) {
            if (r == null || r.displayName == null) {
                continue;
            }
            if (JagexCredentialsHelper.normalizeDisplayNameForMatch(r.displayName).equalsIgnoreCase(norm)
                    && r.calibrateBankOnNextLogin) {
                r.calibrateBankOnNextLogin = false;
                changed = true;
            }
        }
        if (changed) {
            ManagedJagexAccountsStore.persist(stormConfigManager, rows);
        }
    }

    private int handlePerAccountBankCalibrationAndSnapshot() {
        if (!Game.isLoggedIn()) {
            return 0;
        }
        if (shouldDeferBankCalibrationUntilAfterTutorial()) {
            return 0;
        }
        String rsn = tryGetLocalRsn();
        if (rsn == null || rsn.trim().isEmpty()) {
            return 0;
        }
        syncQuestCompletionOnCalibration(rsn);
        ManagedJagexAccountsStore.ManagedJagexAccountRow row = findManagedRowByDisplayName(rsn);
        boolean forceCalibrate = row != null && row.calibrateBankOnNextLogin;
        boolean notCalibratedYet = !AccountStateJsonStore.isBankCalibrated(rsn);
        boolean needsCalibrate = forceCalibrate || notCalibratedYet;

        if (needsCalibrate && !Bank.isOpen()) {
            if (BankHelper.interactIfNearby()) {
                paint.setCurrentStatus("Account calibratie: bank openen…");
                return 500;
            }
            if (BankHelper.walkToNearestFullBank()) {
                paint.setCurrentStatus("Account calibratie: naar bank lopen…");
                return 900;
            }
            return 900;
        }

        if (!Bank.isOpen()) {
            bankSnapshotPrevOpen = false;
            return 0;
        }

        long now = System.currentTimeMillis();
        boolean bankJustOpened = !bankSnapshotPrevOpen;
        bankSnapshotPrevOpen = true;
        // Direct bij openen + daarna minstens elke seconde tijdens dezelfde sessie (lang banken = actuele JSON).
        if (!bankJustOpened && now - lastBankSnapshotSyncMs < 1000L) {
            return needsCalibrate ? 250 : 0;
        }
        lastBankSnapshotSyncMs = now;

        boolean markCalibrated = forceCalibrate || !AccountStateJsonStore.isBankCalibrated(rsn);
        BankSnapshotHelper.writeSnapshotIfBankOpen(rsn, markCalibrated);
        if (forceCalibrate) {
            clearManagedCalibrateFlag(rsn);
            String stamp = Instant.ofEpochMilli(System.currentTimeMillis())
                    .atZone(ZoneId.systemDefault())
                    .format(CALIBRATION_TIME_FMT);
            paint.setLastAntiBanAction("Bank calibratie voltooid voor " + rsn + " (" + stamp + ")");
        }
        return needsCalibrate ? 250 : 0;
    }

    private void syncQuestCompletionOnCalibration(String rsn) {
        if (rsn == null || rsn.trim().isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastQuestCalibrationSyncMs < 8000L) {
            return;
        }
        lastQuestCalibrationSyncMs = now;

        boolean vampDone = false;
        try {
            vampDone = Quests.isFinished(Quest.VAMPYRE_SLAYER);
        } catch (Throwable ignored) {
        }
        if (!vampDone) {
            return;
        }

        AccountQuestProgressStore.QuestEntry cur = AccountQuestProgressStore.get(config, rsn);
        if (cur != null && cur.vampireSlayerStep >= AccountQuestProgressStore.VAMPIRE_SLAYER_STEP_DONE) {
            return;
        }
        AccountQuestProgressStore.QuestEntry q = cur != null ? cur : new AccountQuestProgressStore.QuestEntry();
        q.vampireSlayerStep = AccountQuestProgressStore.VAMPIRE_SLAYER_STEP_DONE;
        AccountQuestProgressStore.put(stormConfigManager, config, rsn, q);
        DebugLog.log("QUEST", "Calibratie-sync: Vampire Slayer voltooid gedetecteerd voor " + rsn);
    }

    /** Alleen expliciete quest-mode toggle mag Vampire Slayer prioriteren. */
    private boolean isVampireSlayerLoopPriorityActive() {
        return config.vampireSlayerQuestMode();
    }

    private boolean hasMinimumHpForVampireSlayer() {
        try {
            return Skills.getLevel(Skill.HITPOINTS) >= VAMPIRE_SLAYER_MIN_HP_LEVEL;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isVampireSlayerIncompleteForRsn(String displayName) {
        return displayName != null && !displayName.trim().isEmpty()
                && !AccountQuestProgressStore.isVampireSlayerComplete(config, displayName.trim());
    }

    /** Eerste skill in rotatie die geen Barb loot is (na Magic ≥5 of overslaan). */
    private ActiveSkill pickFirstNonLootSkill() {
        barbLootSessionActive = false;
        List<ActiveSkill> enabled = getEnabledSkills();
        for (ActiveSkill s : enabled) {
            if (s != ActiveSkill.LOOT) {
                return s;
            }
        }
        if (effectiveImpsInRotation()) {
            return ActiveSkill.IMPS;
        }
        if (CenterManager.countActive(config.combatCenters()) > 0) {
            return ActiveSkill.COMBAT;
        }
        if (CenterManager.countActive(config.wcCenters()) > 0) {
            return ActiveSkill.WOODCUTTING;
        }
        if (CenterManager.countActive(config.miningCenters()) > 0) {
            return ActiveSkill.MINING;
        }
        if (CenterManager.countActive(config.fishingCenters()) > 0) {
            return ActiveSkill.FISHING;
        }
        if (effectiveGiantsMode()) {
            return ActiveSkill.GIANTS;
        }
        if (config.barbarianMode()) {
            return ActiveSkill.BARBARIAN;
        }
        return ActiveSkill.COMBAT;
    }

    /**
     * Start skill {@link CombatBotConfig.StartSkill#BARB_LOOT}: bij Magic ≥ 5 geen Barb loot meer voor dit account;
     * anders {@link #barbLootSessionActive} aan zodat loot-handler draait zonder config-vink.
     */
    private void maybeApplyBarbLootStartMagicGate() {
        if (effectiveStartSkillSetting() != CombatBotConfig.StartSkill.BARB_LOOT) {
            return;
        }
        if (barbLootStartMagicCheckDone) {
            return;
        }
        barbLootStartMagicCheckDone = true;
        try {
            int magic = Skills.getLevel(Skill.MAGIC);
            String rsn = tryGetLocalRsn();
            if (magic >= 5) {
                if (rsn != null) {
                    BarbLootSkipStore.setSkipped(stormConfigManager, config, rsn);
                }
                barbLootSessionActive = false;
                if (activeSkill == ActiveSkill.LOOT) {
                    activeSkill = pickFirstNonLootSkill();
                    resetHandlerForSkill(activeSkill);
                    paint.setActiveSkill(getSkillName(activeSkill));
                    paint.setLastAntiBanAction("✓ Magic ≥5 — Barb loot overgeslagen voor dit account");
                    resetSwitchTimer();
                }
            } else {
                barbLootSessionActive = true;
            }
        } catch (Throwable t) {
            barbLootSessionActive = true;
            DebugLog.log("CombatBot", "Barb loot magic gate: " + t.getMessage());
        }
    }

    // ===================== STARTUP INVENTORY CHECK =====================

    /** Eenmalige startup-scan: welke inventory items passen bij welke skill(s). */
    private void runStartupInventoryCheck() {
        try {
            var all = Inventory.getAll();
            if (all == null || all.isEmpty()) return;

            List<ActiveSkill> enabled = getEnabledSkills();
            if (enabled.isEmpty()) {
                // Als geen skills aanstaan, check tegen alle bekende skills zodat user toch nuttige feedback krijgt.
                enabled = List.of(ActiveSkill.COMBAT, ActiveSkill.WOODCUTTING, ActiveSkill.MINING, ActiveSkill.FISHING,
                        ActiveSkill.IMPS, ActiveSkill.GIANTS, ActiveSkill.LOOT);
            }

            List<String> unknown = new ArrayList<>();
            for (var item : all) {
                if (item == null || item.getName() == null || item.getName().isBlank()) continue;
                String name = item.getName();
                List<String> tags = new ArrayList<>();
                for (ActiveSkill s : enabled) {
                    if (itemBelongsToSkill(name, s)) tags.add(getSkillName(s));
                }
                if (tags.isEmpty()) {
                    unknown.add(name);
                } else {
                    DebugLog.log("InvCheck", name + " -> " + String.join("/", tags));
                }
            }

            if (!unknown.isEmpty()) {
                DebugLog.log("InvCheck", "Niet-herkende items bij start: " + String.join(", ", unknown));
                paint.setLastAntiBanAction("⚠ Inv-check: " + unknown.size() + " onbekende items");
            } else {
                DebugLog.log("InvCheck", "Startup inventory check: geen afwijkende items");
                paint.setLastAntiBanAction("✓ Inv-check OK");
            }
        } catch (Exception e) {
            DebugLog.log("InvCheck", "Startup check fout: " + e.getMessage());
        }
    }

    private boolean itemBelongsToSkill(String itemName, ActiveSkill skill) {
        if (itemName == null) return false;
        String n = itemName.toLowerCase();
        switch (skill) {
            case WOODCUTTING:
                return n.contains(" axe")
                        || n.endsWith("axe")
                        || n.contains("logs")
                        || n.equals("tinderbox")
                        || n.equals("knife");
            case MINING:
                return n.contains("pickaxe")
                        || n.contains(" ore")
                        || n.contains("ore")
                        || n.contains("gem")
                        || n.equals("hammer")
                        || n.equals("chisel");
            case FISHING:
                return n.contains("net")
                        || n.contains("rod")
                        || n.contains("harpoon")
                        || n.contains("bait")
                        || n.contains("feather")
                        || n.contains("shrimp")
                        || n.contains("anchovies")
                        || n.contains("herring")
                        || n.contains("sardine")
                        || n.contains("trout")
                        || n.contains("salmon")
                        || n.contains("tuna")
                        || n.contains("lobster")
                        || n.contains("swordfish")
                        || n.contains("shark")
                        || n.contains("monkfish");
            case IMPS:
                return n.contains("bead")
                        || n.contains("fiendish ashes")
                        || n.contains("talisman")
                        || n.contains("rune")
                        || n.contains("arrow")
                        || n.contains("staff")
                        || n.contains("bow")
                        || n.contains("scimitar")
                        || n.contains("sword")
                        || n.contains("dagger")
                        || n.contains("mace")
                        || n.contains("battleaxe")
                        || n.contains("warhammer")
                        || n.contains("coins")
                        || n.contains("amulet");
            case GIANTS:
                return n.contains("brass key")
                        || n.contains("lamp")
                        || n.contains("bone")
                        || n.contains("big bones")
                        || n.contains("rune")
                        || n.contains("arrow")
                        || n.contains("staff")
                        || n.contains("bow")
                        || n.contains("scimitar")
                        || n.contains("sword")
                        || n.contains("dagger")
                        || n.contains("mace")
                        || n.contains("battleaxe")
                        || n.contains("warhammer")
                        || n.contains("coins")
                        || n.contains("food")
                        || n.contains("trout")
                        || n.contains("salmon")
                        || n.contains("tuna")
                        || n.contains("lobster")
                        || n.contains("swordfish")
                        || n.contains("shark")
                        || n.contains("monkfish")
                        || n.contains("pizza")
                        || n.contains("amulet");
            case LOOT:
                if (n.contains("pickaxe")) return false;
                return n.contains("trout")
                        || n.contains("salmon")
                        || n.contains(" axe")
                        || (n.endsWith("axe") && !n.contains("battleaxe") && !n.contains("battle axe"))
                        || n.contains("logs")
                        || n.equals("tinderbox");
            case VAMPIRE_SLAYER:
                return n.contains("stake")
                        || n.contains("garlic")
                        || n.equals("hammer")
                        || n.contains("beer")
                        || n.contains("rune")
                        || n.contains("staff")
                        || n.contains("air rune")
                        || n.contains("mind rune");
            case TUT:
                return n.contains("net")
                        || n.contains("bread")
                        || n.contains("bronze")
                        || n.contains("pickaxe")
                        || n.contains("rune")
                        || n.contains("air rune")
                        || n.contains("mind rune");
            case STARTER:
                return itemBelongsToSkill(itemName, ActiveSkill.WOODCUTTING)
                        || itemBelongsToSkill(itemName, ActiveSkill.COMBAT)
                        || n.contains("bone")
                        || n.contains("rat meat")
                        || n.contains("raw meat")
                        || n.contains("burnt meat")
                        || n.contains("cooked meat")
                        || n.contains("leather")
                        || n.contains("platebody")
                        || n.contains("platelegs")
                        || n.contains("full helm")
                        || n.contains("kiteshield");
            case COMBAT:
            default:
                return n.contains("rune")
                        || n.contains("arrow")
                        || n.contains("staff")
                        || n.contains("bow")
                        || n.contains("scimitar")
                        || n.contains("sword")
                        || n.contains("dagger")
                        || n.contains("mace")
                        || n.contains("battleaxe")
                        || n.contains("warhammer")
                        || n.contains("coins")
                        || n.contains("food")
                        || n.contains("trout")
                        || n.contains("salmon")
                        || n.contains("tuna")
                        || n.contains("lobster")
                        || n.contains("swordfish")
                        || n.contains("shark")
                        || n.contains("monkfish")
                        || n.contains("pizza")
                        || n.contains("lamp")
                        || n.contains("amulet");
        }
    }

    private ActiveSkill getNextSkill(List<ActiveSkill> enabledSkills) {
        int currentIndex = enabledSkills.indexOf(activeSkill);
        return enabledSkills.get((currentIndex + 1) % enabledSkills.size());
    }

    private String getSkillName(ActiveSkill skill) {
        switch (skill) {
            case TUT:         return "Tutorial";
            case STARTER:     return "Starter";
            case WOODCUTTING: return "Woodcutting";
            case MINING:      return "Mining";
            case FISHING:     return "Fishing";
            case BARBARIAN:   return "Barbarians (Longhall)";
            case IMPS:        return "Imps (Karamja)";
            case GIANTS:      return "Giants (Edgeville)";
            case LOOT:        return "Loot (Barb fish)";
            case VAMPIRE_SLAYER: return "Vampyre Slayer";
            case COMBAT:
            default:          return "Combat";
        }
    }

    private void resetSwitchTimer() {
        skillSwitchTime = Instant.now();
        int minSec = config.rotationMinMinutes() * 60;
        int maxSec = config.rotationMaxMinutes() * 60;
        if (maxSec <= minSec) maxSec = minSec + 60;
        int span = maxSec - minSec;
        nextSwitchSeconds = span <= 0 ? minSec : (minSec + random.nextInt(span));
    }

    private void resetAllAccountLocalProgressOnStop() {
        if (!config.resetAccountTimersOnStop()) {
            clearedLocalProgressOnStop = true;
            return;
        }
        AccountLocalProgressStore.clear(stormConfigManager);
        lastRestoredSkillByAccount.clear();
        sameRestoreCountByAccount.clear();
        restoredProgressThisLogin = false;
        lastLocalProgressSaveMs = 0L;
        clearedLocalProgressOnStop = true;
        if (paint != null) {
            paint.setLastAntiBanAction("⏹ Stop: account skill-timers gereset");
        }
        DebugLog.log("Accounts", "Stop detected: accountLocalProgressBlob gewist (alle account-timers reset).");
    }

    void resetAllBotStateFromPanel() {
        loginNowRequested = false;
        switchNowRequested = false;
        nextAccountRequested = false;
        sellNowRequested = false;
        panelLogoutRequested = false;
        panelLogoutRequestedAtMs = 0L;
        cleaningUpBeforeSwitch = false;
        pendingSkill = null;
        pendingManagedLoginRow = null;
        managedLogoutPrepareDone = false;
        lastDiscordRelogPingMs = 0L;
        justSwitched = false;
        stormConfigManager.setConfiguration("combatbot", "switchNow", "false");
        stormConfigManager.setConfiguration("combatbot", "impsSellNow", "false");
        stormConfigManager.setConfiguration("combatbot", "loginNow", "false");
        if (accountSwitcher != null) {
            accountSwitcher.resetToIdleFromPanel();
        }
        if (sameAccountRelogger != null) {
            sameAccountRelogger.resetToIdleFromPanel();
        }
        if (combatHandler != null) combatHandler.resetState();
        if (woodcutterHandler != null) woodcutterHandler.resetState();
        if (miningHandler != null) miningHandler.resetState();
        if (fishingHandler != null) fishingHandler.resetState();
        if (impsHandler != null) {
            impsHandler.resetState();
            if (activeSkill == ActiveSkill.IMPS) {
                impsHandler.startGearPreparation();
            }
        }
        if (giantsHandler != null) giantsHandler.resetState();
        if (lootHandler != null) lootHandler.resetState();
        if (starterSkillHandler != null) starterSkillHandler.resetState();
        startupInventoryChecked = true;
        switchCooldownUntil = 0;
        resetAllAccountLocalProgressOnStop();
        resetSwitchTimer();
        if (paint != null) {
            paint.setActiveSkill(getSkillName(activeSkill));
            paint.setLastAntiBanAction("↺ Reset — runtime state opgeschoond");
            paint.setCurrentStatus("↺ Reset klaar — hervat " + getSkillName(activeSkill));
        }
    }

    void emergencyStopAllFromPanel() {
        stormConfigManager.setConfiguration("combatbot", "botEnabled", "false");
        loginNowRequested = false;
        switchNowRequested = false;
        nextAccountRequested = false;
        sellNowRequested = false;
        panelLogoutRequested = false;
        panelLogoutRequestedAtMs = 0L;
        cleaningUpBeforeSwitch = false;
        pendingSkill = null;
        pendingManagedLoginRow = null;
        managedLogoutPrepareDone = false;
        restoredProgressThisLogin = false;
        lastDiscordRelogPingMs = 0L;
        justSwitched = false;
        startupInventoryChecked = false;
        coldLoginAccountListPrimed = false;
        switchCooldownUntil = 0L;
        stormConfigManager.setConfiguration("combatbot", "switchNow", "false");
        stormConfigManager.setConfiguration("combatbot", "impsSellNow", "false");
        stormConfigManager.setConfiguration("combatbot", "loginNow", "false");
        if (accountSwitcher != null) {
            accountSwitcher.resetToIdleFromPanel();
        }
        if (sameAccountRelogger != null) {
            sameAccountRelogger.resetToIdleFromPanel();
        }
        if (combatHandler != null) combatHandler.resetState();
        if (woodcutterHandler != null) woodcutterHandler.resetState();
        if (miningHandler != null) miningHandler.resetState();
        if (fishingHandler != null) fishingHandler.resetState();
        if (impsHandler != null) impsHandler.resetState();
        if (giantsHandler != null) giantsHandler.resetState();
        if (lootHandler != null) lootHandler.resetState();
        if (starterSkillHandler != null) starterSkillHandler.resetState();
        resetAllAccountLocalProgressOnStop();
        if (paint != null) {
            paint.setLastAntiBanAction("⛔ Noodstop — alle bot-acties gestopt");
            paint.setCurrentStatus("⛔ Noodstop actief — Start om opnieuw te hervatten");
        }
    }

    /**
     * Decay van {@code failureNudge}: bank ging van open → dicht (succesvolle flow / speler sloot).
     * Profiel direct verversen zodat {@link AntiBan} de nieuwe multiplier gebruikt.
     */
    private void observeFailureNudgeBankWindowEdge() {
        if (!Game.isLoggedIn() || !config.botEnabled()) {
            return;
        }
        boolean bankOpen = Bank.isOpen();
        if (config.accountBehaviorProfileEnabled()
                && prevTickBankOpen
                && !bankOpen) {
            String rsn = resolveCurrentRsnForLearning(Players.getLocal());
            if (rsn != null && !rsn.isEmpty() && !"unknown".equalsIgnoreCase(rsn)) {
                AccountBehaviorProfileStore.decrementFailureNudge(stormConfigManager, config, rsn);
                if (antiBan != null) {
                    AccountBehaviorProfileStore.Profile prof =
                            AccountBehaviorProfileStore.getOrCreate(stormConfigManager, config, rsn);
                    antiBan.setBehaviorProfile(prof);
                }
            }
        }
        prevTickBankOpen = bankOpen;
    }

    /**
     * Verhoogt {@code failureNudge} na een mislukte interactie (timeout, false return, …).
     * Handlers zonder plugin-ref: {@link CombatBotRuntime#applyFailureNudgeAfterBadInteraction()}.
     */
    public void applyFailureNudgeAfterBadInteraction() {
        if (!config.accountBehaviorProfileEnabled()) {
            return;
        }
        String rsn = resolveCurrentRsnForLearning(Players.getLocal());
        if (rsn == null || rsn.isEmpty() || "unknown".equalsIgnoreCase(rsn)) {
            return;
        }
        AccountBehaviorProfileStore.incrementFailureNudge(stormConfigManager, config, rsn);
        if (antiBan != null) {
            AccountBehaviorProfileStore.Profile prof =
                    AccountBehaviorProfileStore.getOrCreate(stormConfigManager, config, rsn);
            antiBan.setBehaviorProfile(prof);
        }
    }

    /**
     * Voert {@code action} uit; bij {@code false} wordt failure-nudge verhoogd (profiel + AntiBan direct bijgewerkt).
     * Let op: synchrone checks alleen — veel SDK-acties openen interfaces pas na een tick.
     */
    public boolean executeWithFailCheck(BooleanSupplier action, String logContext) {
        boolean success = action.getAsBoolean();
        if (!success) {
            DebugLog.log("CombatBot", "[FailNudge] " + logContext);
            applyFailureNudgeAfterBadInteraction();
        }
        return success;
    }

    private void handleManagedAccountSessionTracking() {
        long now = System.currentTimeMillis();
        boolean loggedIn = Game.isLoggedIn();
        try {
            if (loggedIn && Players.getLocal() != null && Players.getLocal().getName() != null) {
                String name = Text.removeTags(Players.getLocal().getName());
                if (name != null && !name.isEmpty()) {
                    String prevName = lastTrackedPlayerName;
                    if (prevName != null
                            && !prevName.isEmpty()
                            && !prevName.equalsIgnoreCase(name)) {
                        resetRuntimeStateForAccountChange(prevName, name);
                    }
                    lastTrackedPlayerName = name;
                    if (accountSwitcher != null) {
                        accountSwitcher.syncCurrentAccountByDisplayName(name);
                    }
                    if (paint != null) {
                        paint.setCurrentAccountNameOnly(name);
                    }
                    if (!restoredProgressThisLogin) {
                        tryRestoreLocalProgress(name);
                        restoredProgressThisLogin = true;
                    }
                    if (antiBan != null) {
                        if (config.accountBehaviorProfileEnabled()) {
                            AccountBehaviorProfileStore.Profile prof =
                                    AccountBehaviorProfileStore.getOrCreate(stormConfigManager, config, name);
                            antiBan.setBehaviorProfile(prof);
                            BankWalkPersonality.setActive(prof);
                        } else {
                            antiBan.setBehaviorProfile(null);
                            BankWalkPersonality.setActive(null);
                        }
                    }
                    if (config.botEnabled() && now - lastLocalProgressSaveMs >= LOCAL_PROGRESS_SAVE_INTERVAL_MS) {
                        saveLocalProgressForPlayer(name);
                        lastLocalProgressSaveMs = now;
                    }
                }
                if (pendingManagedLoginRow != null) {
                    pendingManagedLoginRow = null;
                    managedLogoutPrepareDone = false;
                }
            } else if (!loggedIn) {
                restoredProgressThisLogin = false;
                prevTickBankOpen = false;
                if (antiBan != null) {
                    antiBan.setBehaviorProfile(null);
                }
                BankWalkPersonality.setActive(null);
            }
        } catch (Throwable ignored) {
        }

        if (pendingManagedLoginRow != null && Game.isOnLoginScreen() && !loggedIn
                && (!config.accountSwitchEnabled() || accountSwitcher.getAccountCount() < 2)) {
            if (!managedLogoutPrepareDone) {
                ManagedJagexAccountsStore.ManagedJagexAccountRow row = pendingManagedLoginRow;
                if (syncPrepareManagedLoginOnLoginScreen(row)) {
                    managedLogoutPrepareDone = true;
                    JagexLauncherPlayButton.clickPlayButton();
                    if (paint != null) {
                        paint.setLastAntiBanAction("→ Volgende account: " + row.displayName + " (Play)");
                    }
                }
            }
        }

        if (prevGameLoggedIn && !loggedIn) {
            onManagedClientLoggedOut();
        }
        prevGameLoggedIn = loggedIn;
    }

    private void resetRuntimeStateForAccountChange(String previousName, String newName) {
        // Nieuwe account: nooit door met oude handler-state/status.
        BankHelper.resetOpenCooldown();
        cleaningUpBeforeSwitch = false;
        pendingSkill = null;
        switchNowRequested = false;
        nextAccountRequested = false;
        sellNowRequested = false;
        startupInventoryChecked = false;
        switchCooldownUntil = 0L;
        restoredProgressThisLogin = false;
        bankSnapshotPrevOpen = false;
        lastBankSnapshotSyncMs = 0L;
        if (combatHandler != null) combatHandler.resetState();
        if (woodcutterHandler != null) woodcutterHandler.resetState();
        if (miningHandler != null) miningHandler.resetState();
        if (fishingHandler != null) fishingHandler.resetState();
        if (impsHandler != null) impsHandler.resetState();
        if (giantsHandler != null) giantsHandler.resetState();
        if (lootHandler != null) lootHandler.resetState();
        if (starterSkillHandler != null) starterSkillHandler.resetState();
        if (paint != null) {
            paint.setCurrentStatus("🔄 Account gewisseld: " + newName + " — state gereset");
            paint.setLastAntiBanAction("↺ Account switch: " + previousName + " -> " + newName);
        }
    }

    private void onManagedClientLoggedOut() {
        if (lastTrackedPlayerName.isEmpty()) {
            return;
        }
        if (sameAccountRelogger != null && sameAccountRelogger.isRelogFlowActive()) {
            return;
        }
        if (accountSwitcher != null && accountSwitcher.isSwitching()) {
            return;
        }
        saveLocalProgressForPlayer(lastTrackedPlayerName);
        if (config.accountSwitchEnabled() && accountSwitcher.getAccountCount() >= 2) {
            return;
        }
        List<ManagedJagexAccountsStore.ManagedJagexAccountRow> en = listEnabledManagedRotationAccounts();
        if (en.size() < 2) {
            return;
        }
        int idx = -1;
        for (int i = 0; i < en.size(); i++) {
            if (en.get(i).displayName != null
                    && en.get(i).displayName.equalsIgnoreCase(lastTrackedPlayerName)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            idx = 0;
        }
        int next = (idx + 1) % en.size();
        pendingManagedLoginRow = en.get(next).copy();
        managedLogoutPrepareDone = false;
        if (paint != null) {
            paint.setLastAntiBanAction("Uitgelogd — volgende account na login: " + pendingManagedLoginRow.displayName);
        }
    }

    private List<ManagedJagexAccountsStore.ManagedJagexAccountRow> listEnabledManagedRotationAccounts() {
        List<ManagedJagexAccountsStore.ManagedJagexAccountRow> out = new ArrayList<>();
        for (ManagedJagexAccountsStore.ManagedJagexAccountRow r
                : ManagedJagexAccountsStore.parseRows(config.managedJagexAccountsBlob())) {
            if (!r.isEmpty() && r.rotationEnabled) {
                out.add(r);
            }
        }
        return out;
    }

    private void saveLocalProgressForPlayer(String displayName) {
        if (displayName == null || displayName.trim().isEmpty()) {
            return;
        }
        if (skillSwitchTime == null) {
            resetSwitchTimer();
        }
        long elapsed = Duration.between(skillSwitchTime, Instant.now()).getSeconds();
        AccountLocalProgressStore.Entry e = new AccountLocalProgressStore.Entry();
        ActiveSkill toSave = activeSkill;
        if (isVampireSlayerLoopPriorityActive() && isVampireSlayerIncompleteForRsn(displayName)) {
            toSave = ActiveSkill.VAMPIRE_SLAYER;
        }
        e.activeSkillName = toSave.name();
        e.elapsedSec = Math.max(0, elapsed);
        e.totalSwitchSec = Math.max(60, nextSwitchSeconds);
        AccountLocalProgressStore.put(stormConfigManager, config, displayName.trim(), e);
        DebugLog.log("Accounts", "Progress save " + displayName.trim()
                + " -> " + e.activeSkillName + " " + e.elapsedSec + "s/" + e.totalSwitchSec + "s");
    }

    /**
     * Zorgt dat {@code activeSkill} overeenkomt met {@code startSkill = Starter}.
     * <p>Oorzaak van veel "waarom Imps?": opgeslagen {@link AccountLocalProgressStore} had nog IMPS actief terwijl
     * je Start skill opnieuw op Starter zette, of de bot bleef aan over een relog heen zonder fresh start
     * ({@code wasBotEnabled} bleef true) waardoor oude {@code activeSkill} bleef hangen.
     * <p>Geen {@link #resetHandlerForSkill(ActiveSkill)}: dat zou Starter-fase uit JSON wissen. Imps-state wel resetten.
     */
    private void enforceStarterStartSkillIfConfigured() {
        if (effectiveStartSkillSetting() != CombatBotConfig.StartSkill.STARTER) {
            return;
        }
        if (activeSkill == ActiveSkill.STARTER) {
            return;
        }
        if (activeSkill == ActiveSkill.VAMPIRE_SLAYER) {
            return;
        }
        if (wcImpsCashFarmActive || moneyImpsCashFarmActive) {
            return;
        }
        if (impsHandler != null && impsHandler.isGeSellNowFlowActive()) {
            return;
        }
        if (cleaningUpBeforeSwitch || pendingSkill != null) {
            return;
        }
        ActiveSkill was = activeSkill;
        activeSkill = ActiveSkill.STARTER;
        if (impsHandler != null) {
            impsHandler.resetState();
        }
        resetSwitchTimer();
        paint.setActiveSkill(getSkillName(activeSkill));
        paint.setLastAntiBanAction("Start skill = Starter — actieve skill gecorrigeerd (was " + getSkillName(was) + ")");
        DebugLog.log("StartSkill", "enforceStarterStartSkillIfConfigured: " + was + " -> STARTER");
    }

    private void tryRestoreLocalProgress(String displayName) {
        if (isVampireSlayerLoopPriorityActive() && isVampireSlayerIncompleteForRsn(displayName)) {
            if (hasMinimumHpForVampireSlayer()) {
                activeSkill = ActiveSkill.VAMPIRE_SLAYER;
                resetSwitchTimer();
                paint.setActiveSkill(getSkillName(activeSkill));
                if (paint != null) {
                    paint.setLastAntiBanAction("Voortgang: Vampyre Slayer (quest-mode actief)");
                }
            } else if (paint != null) {
                paint.setLastAntiBanAction("Vampyre Slayer overgeslagen: HP level < " + VAMPIRE_SLAYER_MIN_HP_LEVEL);
            }
            return;
        }
        if (effectiveStartSkillSettingForDisplayName(displayName) == CombatBotConfig.StartSkill.STARTER) {
            activeSkill = ActiveSkill.STARTER;
            resetSwitchTimer();
            paint.setActiveSkill(getSkillName(activeSkill));
            if (paint != null) {
                paint.setLastAntiBanAction("Voortgang: Starter (start skill = Starter — geen rotatie uit save)");
            }
            DebugLog.log("StartSkill", "tryRestoreLocalProgress: forced STARTER for " + displayName);
            return;
        }
        AccountLocalProgressStore.Entry e = AccountLocalProgressStore.get(config, displayName);
        if (e == null) {
            // Nieuw account / geen save: NOOIT doorgaan met skill van vorig account.
            activeSkill = resolveStartSkill();
            resetSwitchTimer();
            paint.setActiveSkill(getSkillName(activeSkill));
            if (paint != null) {
                paint.setLastAntiBanAction("Nieuw account zonder save: start op " + getSkillName(activeSkill));
            }
            return;
        }
        ActiveSkill s;
        try {
            s = ActiveSkill.valueOf(e.activeSkillName);
        } catch (IllegalArgumentException ex) {
            return;
        }
        if (s == ActiveSkill.VAMPIRE_SLAYER
                && (!isVampireSlayerLoopPriorityActive() || !hasMinimumHpForVampireSlayer())) {
            s = resolveStartSkill();
        }
        if (s == ActiveSkill.BARBARIAN && !config.barbarianMode()) {
            s = resolveStartSkill();
        }
        long total = Math.max(60, e.totalSwitchSec);
        long elapsed = Math.min(Math.max(0, e.elapsedSec), total);
        activeSkill = s;
        skillSwitchTime = Instant.now().minusSeconds(elapsed);
        nextSwitchSeconds = (int) Math.min(Integer.MAX_VALUE, total);
        maybeApplyRestoreLoopGuard(displayName);
        paint.setActiveSkill(getSkillName(activeSkill));
        if (paint != null) {
            paint.setLastAntiBanAction("Voortgang hersteld voor " + displayName + ": " + getSkillName(activeSkill));
        }
        DebugLog.log("Accounts", "Progress restore " + displayName.trim()
                + " -> " + activeSkill + " " + elapsed + "s/" + total + "s");
    }

    private boolean handleClientUpdatedPromptStop() {
        if (!isClientUpdatedPromptVisible()) {
            return false;
        }
        if (stormConfigManager != null) {
            stormConfigManager.setConfiguration("combatbot", "botEnabled", "false");
        }
        wasBotEnabled = false;
        panelLogoutRequested = false;
        panelLogoutRequestedAtMs = 0L;
        if (paint != null) {
            paint.setCurrentStatus("⏹ Client update gedetecteerd — bot gestopt");
            paint.setLastAntiBanAction("RuneScape update popup: bot uitgezet");
        }
        DebugLog.log("CombatBot", "Client update prompt gedetecteerd (RuneScape has been updated) -> botEnabled=false");
        return true;
    }

    private boolean isClientUpdatedPromptVisible() {
        return hasVisibleWidgetContaining("runescape has been updated")
                && hasVisibleWidgetContaining("please restart your client");
    }

    private boolean hasVisibleWidgetContaining(String needle) {
        if (needle == null || needle.isEmpty()) {
            return false;
        }
        String lowNeedle = needle.toLowerCase(Locale.ROOT);
        for (int group = 0; group <= 800; group++) {
            if (hasVisibleWidgetContainingInGroup(group, lowNeedle)) {
                return true;
            }
        }
        return false;
    }

    private int maybeHandleDeathFailover() {
        if (!Game.isLoggedIn()) {
            deathFailoverTriggered = false;
            return 0;
        }
        int hpNow;
        try {
            hpNow = Skills.getBoostedLevel(Skill.HITPOINTS);
        } catch (Exception e) {
            return 0;
        }
        if (hpNow > 0) {
            deathFailoverTriggered = false;
            return 0;
        }
        if (deathFailoverTriggered) {
            return 1200;
        }
        deathFailoverTriggered = true;

        String reason = "☠ Dood gegaan — uitloggen en naar volgende account";
        boolean switching = accountSwitcher != null
                && accountSwitcher.getAccountCount() >= 2
                && accountSwitcher.requestImmediateSwitch(reason);
        if (switching) {
            if (paint != null) {
                paint.setLastAntiBanAction(reason);
                paint.setCurrentStatus("☠ Death failover: volgende account...");
            }
            DebugLog.log("Accounts", "Death failover -> next account switch requested");
            return 1200;
        }

        stormConfigManager.setConfiguration("combatbot", "botEnabled", "false");
        wasBotEnabled = false;
        panelLogoutRequested = false;
        panelLogoutRequestedAtMs = 0L;
        invokeGameLogoutOnClientThread();
        if (paint != null) {
            paint.setLastAntiBanAction("☠ Dood + geen volgende account — bot gestopt");
            paint.setCurrentStatus("⏹ Geen volgende account na death");
        }
        DebugLog.log("Accounts", "Death failover -> no next account, bot stopped");
        return 1200;
    }

    private void maybeApplyRestoreLoopGuard(String displayName) {
        if (displayName == null || displayName.trim().isEmpty()) {
            return;
        }
        String key = displayName.trim().toLowerCase(Locale.ROOT);
        String curSkill = activeSkill.name();
        String prevSkill = lastRestoredSkillByAccount.get(key);
        int nextCount = curSkill.equals(prevSkill)
                ? sameRestoreCountByAccount.getOrDefault(key, 0) + 1
                : 1;
        sameRestoreCountByAccount.put(key, nextCount);
        lastRestoredSkillByAccount.put(key, curSkill);

        if (nextCount < 3) {
            return;
        }
        List<ActiveSkill> enabled = getEnabledSkills();
        if (enabled.size() <= 1 || !enabled.contains(activeSkill)) {
            return;
        }
        ActiveSkill forced = getNextSkill(enabled);
        if (forced == activeSkill) {
            return;
        }
        activeSkill = forced;
        resetSwitchTimer();
        sameRestoreCountByAccount.put(key, 1);
        if (paint != null) {
            paint.setLastAntiBanAction("Anti-loop: " + displayName + " -> " + getSkillName(activeSkill));
        }
        DebugLog.log("Accounts", "Anti-loop guard actief voor " + displayName
                + ": forced skill switch naar " + activeSkill);
    }
}
