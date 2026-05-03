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
import net.runelite.api.MenuEntry;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.Locale;
import java.util.function.BooleanSupplier;

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
    private long lastQuestCalibrationSyncMs = 0L;
    /** Panel “reset”: volgende fresh start laadt starter-fase niet uit account-JSON. */
    private boolean ignoreStarterJsonOnNextFreshStart;

    // Discord screenshot sending
    private volatile boolean discordSendInProgress = false;
    private long lastDiscordSendMs = 0;
    /** Tijdens re-log: tekst-pings met ETA ( los van screenshot-interval; loop stopt vroeg tijdens pauze). */
    private long lastDiscordRelogPingMs = 0L;
    private long lastWidgetInspectorDumpMs = 0L;
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
            219, 229, 233, 134, 260, 261, 311, 312, 162, 163
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
            "certer"
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
        areaOverlay.setConfig(config);
        walkClickHighlightOverlay.setConfig(config);
        areaOverlay.setTileMarkerManager(tileMarkerManager);

        MovementHelper.setWalkHighlightPersistence(
                csv -> stormConfigManager.setConfiguration("combatbot", "debugWalkClickPersistedQueue",
                        csv == null ? "" : csv),
                () -> config.debugWalkClickPersistedQueue());

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
                this::resetAllBotStateFromPanel, this::emergencyStopAllFromPanel);

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

        // === HTTP Server starten ===
        httpServer = new ConfigHttpServer(config, stormConfigManager);
        httpServer.start();
    }

    @Override
    public void shutDown() throws Exception {
        CombatBotRuntime.setActivePlugin(null);
        overlayManager.remove(overlay);
        overlayManager.remove(areaOverlay);
        overlayManager.remove(walkClickHighlightOverlay);
        overlayManager.remove(widgetHoverOverlay);
        saveTileMarkersToConfig();

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

    /**
     * Logt gekozen menu-acties voor ML / replay-analyse: canvas-positie, optie, target, params.
     * NDJSON: {@code ~/.runelite/prive-logs/combat-bot-ml-clicks-YYYY-MM-DD.jsonl}; leesbare regels onder bron ML_CLICK.
     */
    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event) {
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
    }

    /**
     * Periodieke widget-dump naar stdout (IDE/console) — zie {@link CombatBotConfig#widgetInspectorIntervalSeconds()}.
     */
    @Subscribe
    public void onGameTick(GameTick event) {
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

        if (skill == activeSkill) {
            CenterManager.Center nearest = CenterManager.findNearest(updated, point);
            if (nearest != null) {
                setHandlerCenter(skill, nearest.point, nearest.radius);
            }
        }

        CenterManager.Center adj = CenterManager.findNearest(updated, point);
        paint.setLastAntiBanAction("📐 Radius: " + getSkillName(skill) + " r=" + (adj != null ? adj.radius : "?"));
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
            }
            loadAndSetForSkill(ActiveSkill.COMBAT, c);
            any = true;
        }
        if (!Objects.equals((c = nullToEmptyCenters(config.wcCenters())), snapWcCenters)) {
            snapWcCenters = c;
            if (!applyingManagedCenters) {
                masterWcCenters = c;
            }
            loadAndSetForSkill(ActiveSkill.WOODCUTTING, c);
            any = true;
        }
        if (!Objects.equals((c = nullToEmptyCenters(config.miningCenters())), snapMiningCenters)) {
            snapMiningCenters = c;
            if (!applyingManagedCenters) {
                masterMiningCenters = c;
            }
            loadAndSetForSkill(ActiveSkill.MINING, c);
            any = true;
        }
        if (!Objects.equals((c = nullToEmptyCenters(config.fishingCenters())), snapFishingCenters)) {
            snapFishingCenters = c;
            if (!applyingManagedCenters) {
                masterFishingCenters = c;
            }
            loadAndSetForSkill(ActiveSkill.FISHING, c);
            any = true;
        }
        if (!Objects.equals((c = nullToEmptyCenters(config.impsCenters())), snapImpsCenters)) {
            snapImpsCenters = c;
            if (!applyingManagedCenters) {
                masterImpsCenters = c;
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
            boolean rotationEnabled = config.barbarianMode() || config.wcEnabled() || config.miningEnabled() || config.fishingEnabled()
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

        boolean rotationEnabled = config.barbarianMode() || config.wcEnabled() || config.miningEnabled() || config.fishingEnabled()
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
                boolean impsAvailable = (CenterManager.countActive(config.impsCenters()) > 0) || config.impsMode();
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
                boolean impsAvailableForCash = (CenterManager.countActive(config.impsCenters()) > 0) || config.impsMode();
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
            case GIANTS:
                return config.giantsCombatStyle() == CombatBotConfig.ImpsCombatStyle.MELEE
                        ? config.giantsMeleeTrainingStyle() : CombatBotConfig.MeleeTrainingStyle.BALANCED;
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
            case GIANTS:
                meleeMode = config.giantsCombatStyle() == CombatBotConfig.ImpsCombatStyle.MELEE;
                break;
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
        if (row.targetAttackLevel > 0 && atk < row.targetAttackLevel) {
            return CombatBotConfig.MeleeTrainingStyle.ATTACK;
        }
        if (row.targetStrengthLevel > 0 && str < row.targetStrengthLevel) {
            return CombatBotConfig.MeleeTrainingStyle.STRENGTH;
        }
        if (row.targetDefenceLevel > 0 && def < row.targetDefenceLevel) {
            return CombatBotConfig.MeleeTrainingStyle.DEFENCE;
        }
        return CombatBotConfig.MeleeTrainingStyle.BALANCED;
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
            case GIANTS:
                return config.giantsCombatStyle() == CombatBotConfig.ImpsCombatStyle.RANGED;
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

    private ActiveSkill resolveStartSkill() {
        CombatBotConfig.StartSkill setting = config.startSkill();
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
            case IMPS:        candidate = (CenterManager.countActive(config.impsCenters()) > 0 || config.impsMode()) ? ActiveSkill.IMPS : fallback; break;
            case GIANTS:      candidate = config.giantsMode() ? ActiveSkill.GIANTS : fallback; break;
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

        // Centers opnieuw laden
        loadCentersAndSetHandlers();
        captureCentersSnapshotFromConfig();

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
        if (config.accountSwitchEnabled() && accountSwitcher.getAccountCount() > 0) {
            applyManagedCentersForDisplayName(accountSwitcher.getCurrentAccountName());
        }
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
                    boolean forceGlobal =
                            config.accountsUseGlobalCenterListsOnly();
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
            if (npc == null || npc.getName() == null || npc.getInteracting() == null) {
                return false;
            }
            Object interacting = npc.getInteracting();
            boolean targetsLocal =
                    interacting == local
                            || interacting.equals(local)
                            || (localWrapped != null
                            && (interacting == localWrapped || interacting.equals(localWrapped)));
            return targetsLocal
                    && npc.getWorldLocation() != null
                    && npc.getWorldLocation().distanceTo(local.getWorldLocation()) <= 6;
        });
        if (randomNpc == null || randomNpc.getName() == null) return 0;

        String name = randomNpc.getName().toLowerCase().trim();
        int npcId = randomNpc.getId();
        boolean knownRandomByName = RANDOM_EVENT_NAMES.contains(name);
        boolean knownRandomById = DISMISS_RANDOM_EVENT_IDS.contains(npcId);

        // Alleen handelen op bekende random events (naam/ID).
        if (!knownRandomByName && !knownRandomById) return 0;

        boolean isGenie = GENIE_NPC_IDS.contains(npcId) || name.contains("genie");
        if (isGenie) {
            if (randomNpc.hasAction("Talk-to")) {
                randomNpc.interact("Talk-to");
                lastRandomEventActionMs = now;
                paint.setLastAntiBanAction("🧞 Genie aanspreken");
                return 900 + random.nextInt(600);
            }
            return 0;
        }

        if (randomNpc.hasAction("Dismiss")) {
            randomNpc.interact("Dismiss");
            lastRandomEventActionMs = now;
            paint.setLastAntiBanAction("👋 Random event dismissed");
            return 800 + random.nextInt(500);
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

    private boolean clickLampWidgetByKeywordInLampGroup(String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return false;
        }
        String lowKeyword = keyword.toLowerCase();
        int group = lampInterfaceGroupHint;
        if (group < 0) {
            group = findLampInterfaceGroup();
        }
        if (group < 0) {
            return false;
        }

        List<IWidget> candidates = new ArrayList<>();
        for (int child = 0; child <= 80; child++) {
            IWidget root = Widgets.get(group, child);
            if (root == null || root.isHidden()) continue;
            if (widgetNameContains(root, lowKeyword)) {
                candidates.add(root);
            }
            IWidget[] descendants = getWidgetChildren(root);
            if (descendants == null) continue;
            for (IWidget w : descendants) {
                if (w == null || w.isHidden()) continue;
                if (widgetNameContains(w, lowKeyword)) {
                    candidates.add(w);
                }
            }
        }
        if (candidates.isEmpty()) {
            return false;
        }
        Collections.shuffle(candidates, random);
        candidates.get(0).interact(0);
        return true;
    }

    private boolean widgetNameContains(IWidget widget, String keyword) {
        if (widget == null || keyword == null || keyword.isEmpty()) return false;
        String name = widget.getName();
        if (name == null || name.isEmpty()) return false;
        String cleaned = name.replaceAll("<[^>]*>", "").trim().toLowerCase();
        return cleaned.contains(keyword);
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
        List<ActiveSkill> skills = new ArrayList<>();
        // Center-based skills: actieve centers zijn leidend.
        if (CenterManager.countActive(config.combatCenters()) > 0) {
            skills.add(ActiveSkill.COMBAT);
        }
        if (CenterManager.countActive(config.impsCenters()) > 0 || config.impsMode()) {
            skills.add(ActiveSkill.IMPS);
        }
        if (config.giantsMode()) {
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
                || (config.startSkill() == CombatBotConfig.StartSkill.BARB_LOOT && !barbLootStartMagicCheckDone);
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

    private ManagedJagexAccountsStore.ManagedJagexAccountRow findManagedRowByDisplayName(String displayName) {
        if (displayName == null || displayName.trim().isEmpty()) {
            return null;
        }
        String norm = JagexCredentialsHelper.normalizeDisplayNameForMatch(displayName);
        for (ManagedJagexAccountsStore.ManagedJagexAccountRow r :
                ManagedJagexAccountsStore.parseRows(config.managedJagexAccountsBlob())) {
            if (r == null || r.displayName == null || r.displayName.trim().isEmpty()) {
                continue;
            }
            String rn = JagexCredentialsHelper.normalizeDisplayNameForMatch(r.displayName);
            if (rn.equalsIgnoreCase(norm)) {
                return r;
            }
        }
        return null;
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

    private String[] bankSnapshotKeyItems() {
        return new String[] {
                "Coins", "Tinderbox",
                "Bronze axe", "Iron axe", "Steel axe", "Black axe", "Mithril axe", "Adamant axe", "Rune axe",
                "Bronze pickaxe", "Iron pickaxe", "Steel pickaxe", "Black pickaxe", "Mithril pickaxe", "Adamant pickaxe", "Rune pickaxe",
                "Bronze sword", "Iron sword", "Steel sword", "Black sword", "Mithril sword", "Adamant sword", "Rune sword",
                "Bronze longsword", "Iron longsword", "Steel longsword", "Black longsword", "Mithril longsword", "Adamant longsword", "Rune longsword",
                "Bronze scimitar", "Iron scimitar", "Steel scimitar", "Black scimitar", "Mithril scimitar", "Adamant scimitar", "Rune scimitar",
                "Bronze dagger", "Iron dagger", "Steel dagger", "Black dagger", "Mithril dagger", "Adamant dagger", "Rune dagger",
                "Bronze battleaxe", "Iron battleaxe", "Steel battleaxe", "Black battleaxe", "Mithril battleaxe", "Adamant battleaxe", "Rune battleaxe",
                "Bronze med helm", "Iron med helm", "Steel med helm", "Black med helm", "Mithril med helm", "Adamant med helm", "Rune med helm",
                "Bronze full helm", "Iron full helm", "Steel full helm", "Black full helm", "Mithril full helm", "Adamant full helm", "Rune full helm",
                "Bronze chainbody", "Iron chainbody", "Steel chainbody", "Black chainbody", "Mithril chainbody", "Adamant chainbody", "Rune chainbody",
                "Bronze platebody", "Iron platebody", "Steel platebody", "Black platebody", "Mithril platebody", "Adamant platebody", "Rune platebody",
                "Bronze platelegs", "Iron platelegs", "Steel platelegs", "Black platelegs", "Mithril platelegs", "Adamant platelegs", "Rune platelegs",
                "Bronze plateskirt", "Iron plateskirt", "Steel plateskirt", "Black plateskirt", "Mithril plateskirt", "Adamant plateskirt", "Rune plateskirt",
                "Wooden shield", "Bronze kiteshield", "Iron kiteshield", "Steel kiteshield", "Black kiteshield", "Mithril kiteshield", "Adamant kiteshield", "Rune kiteshield",
                "Fly fishing rod", "Feather", "Fishing bait",
                "Fishing rod", "Small fishing net",
                "Raw shrimps", "Shrimps", "Raw sardine", "Sardine", "Raw herring", "Herring",
                "Raw trout", "Trout", "Raw salmon", "Salmon",
                "Shortbow", "Longbow", "Bronze arrow", "Iron arrow", "Steel arrow", "Mithril arrow", "Adamant arrow", "Rune arrow",
                "Staff of air", "Staff of fire", "Staff of water", "Staff of earth",
                "Air rune", "Mind rune", "Chaos rune", "Law rune",
                "Amulet of power", "Hammer", "Garlic", "Stake",
                "Black bead", "Red bead", "Yellow bead", "White bead", "Mind talisman", "Fiendish ashes"
        };
    }

    private String buildKnownBankItemsCsv() {
        String[] keyItems = bankSnapshotKeyItems();
        Set<String> present = new LinkedHashSet<>();
        for (String item : keyItems) {
            try {
                if (Bank.contains(item)) {
                    present.add(item);
                }
            } catch (Throwable ignored) {
            }
        }
        return String.join(",", present);
    }

    private String buildKnownBankItemQtyJson() {
        String[] keyItems = bankSnapshotKeyItems();
        java.util.Map<String, Integer> qty = new java.util.LinkedHashMap<>();
        for (String item : keyItems) {
            int n = 0;
            try {
                if (Bank.contains(item)) {
                    var bi = Bank.getFirst(item);
                    n = bi != null ? Math.max(1, bi.getQuantity()) : 1;
                }
            } catch (Throwable ignored) {
            }
            if (n > 0) {
                qty.put(item, n);
            }
        }
        return COMPACT_GSON.toJson(qty);
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
            return 0;
        }

        long now = System.currentTimeMillis();
        if (now - lastBankSnapshotSyncMs < 2500L) {
            return needsCalibrate ? 250 : 0;
        }
        lastBankSnapshotSyncMs = now;

        int invCoins = 0;
        int bankCoins = 0;
        try {
            IInventoryItem coins = Inventory.getFirst("Coins");
            invCoins = coins != null ? Math.max(0, coins.getQuantity()) : 0;
        } catch (Throwable ignored) {
        }
        try {
            if (Bank.contains("Coins")) {
                var bc = Bank.getFirst("Coins");
                bankCoins = bc != null ? Math.max(0, bc.getQuantity()) : 0;
            }
        } catch (Throwable ignored) {
        }
        String knownItemsCsv = buildKnownBankItemsCsv();
        String knownItemQtyJson = buildKnownBankItemQtyJson();
        // Elke bank-open is een echte live snapshot. De handmatige checkbox forceert alleen het openen + UI-melding.
        AccountStateJsonStore.putBankSnapshot(rsn, knownItemsCsv, knownItemQtyJson, bankCoins, invCoins, true);
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
        if (config.impsMode()) {
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
        if (config.giantsMode()) {
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
        if (config.startSkill() != CombatBotConfig.StartSkill.BARB_LOOT) {
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
        if (config.startSkill() != CombatBotConfig.StartSkill.STARTER) {
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
        if (config.startSkill() == CombatBotConfig.StartSkill.STARTER) {
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
