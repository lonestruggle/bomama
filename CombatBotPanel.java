package com.combatbot;

import net.runelite.client.ui.PluginPanel;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.event.TableModelEvent;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.BorderFactory;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.event.ChangeListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URI;
import java.net.URL;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.Objects;
import java.lang.ref.WeakReference;

/**
 * In-game zijpaneel met VOLLEDIGE settings GUI + live stats.
 * Hoofdtabs o.a. "⚙ Settings", "🎯 Skills" (per-skill), "📊 Stats"
 * Alle wijzigingen worden direct opgeslagen via stormConfigManager.
 */
public class CombatBotPanel extends PluginPanel {

    private final CombatBotPaint paint;
    private final CombatBotConfig config;
    private final net.storm.api.plugins.config.ConfigManager configManager;
    private final Timer refreshTimer;

    // Stats labels
    private final JLabel lblRuntime = new JLabel("00:00:00");
    private final JLabel lblActiveSkill = new JLabel("Combat");
    private final JLabel lblStatus = new JLabel("Opstarten...");
    private final JLabel lblRelog = new JLabel("-");
    private final JLabel lblCombatXp = new JLabel("0");
    private final JLabel lblWcXp = new JLabel("0");
    private final JLabel lblMiningXp = new JLabel("0");
    private final JLabel lblFishingXp = new JLabel("0");
    private final JLabel lblFmXp = new JLabel("0");
    private final JLabel lblKills = new JLabel("0");
    private final JLabel lblLoot = new JLabel("0 gp");
    private final JLabel lblLogs = new JLabel("0");
    private final JLabel lblOres = new JLabel("0");
    private final JLabel lblFish = new JLabel("0");
    /** Live beginner-clue kit checklist (Debug-tab). */
    private JTextPane beginnerClueKitPane;

    // Colors
    private static final Color GOLD = new Color(255, 215, 0);
    private static final Color BG_DARK = new Color(30, 30, 40);
    private static final Color BG_SECTION = new Color(40, 42, 54);
    private static final Color BG_HEADER = new Color(50, 55, 70);
    private static final Color GREEN = new Color(74, 222, 128);
    private static final Color TEXT = new Color(220, 220, 220);
    private static final Color TEXT_DIM = new Color(160, 160, 170);

    // Base fonts (originele maat)
    private static final int BASE_FONT_TITLE = 14;
    private static final int BASE_FONT_SECTION = 12;
    private static final int BASE_FONT_LABEL = 11;
    private static final int BASE_FONT_VALUE = 11;

    // Font objects
    private static final Font FONT_TITLE = new Font("SansSerif", Font.BOLD, BASE_FONT_TITLE);
    private static final Font FONT_SECTION = new Font("SansSerif", Font.BOLD, BASE_FONT_SECTION);
    private static final Font FONT_LABEL = new Font("SansSerif", Font.PLAIN, BASE_FONT_LABEL);
    private static final Font FONT_VALUE = new Font("SansSerif", Font.BOLD, BASE_FONT_VALUE);
    private static final DateTimeFormatter DATE_TIME_FMT = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");
    private static final String DEFAULT_COMBAT_CENTERS = "3212:3424:0:18:Varrock guards:1";
    private static final String DEFAULT_WC_CENTERS = "3086:3232:0:14:Draynor willows:1";
    /** Standaard mining-centers (pipe); namen moeten {@link MiningSiteRules#classify} matchen voor site-regels. */
    private static final String DEFAULT_MINING_CENTERS =
            "3285:3368:0:14:Varrock east mine:1|"
                    + "3226:3146:0:10:lumb zuid:1|"
                    + "3232:3148:0:10:draynor zuid:1|"
                    + "3297:3291:0:15:alkarid 2:1|"
                    + "3295:3310:0:4:alkarid 3:1";

    /** Zelfde als standaard mining-lijst in dit panel; plugin gebruikt dit voor eenmalige merge zonder restore van andere skills. */
    public static String builtinDefaultMiningCenters() {
        return DEFAULT_MINING_CENTERS;
    }
    private static final String DEFAULT_FISHING_CENTERS = "3104:3433:0:14:Barbarian village:1|3090:3230:0:14:Draynor village:1";
    private static final String DEFAULT_IMPS_CENTERS = "2826:3181:0:12:Karamja imps:1";

    // Schaalfactor
    private float scaleFactor = 1.0f;

    // Detached frame
    private JFrame detachedFrame = null;
    private JTabbedPane tabs;
    private JPanel mainContent;

    /** Panels onder 📍 Centers → skill-rijen; verversen bij tab-open (config kan in-game wijzigen). */
    private static final class CentersSectionRefs {
        JPanel combat;
        JPanel wc;
        JPanel mining;
        JPanel fishing;
        JPanel imps;
    }

    private CentersSectionRefs embeddedCentersRefs;
    private CentersSectionRefs detachedCentersRefs;

    private final Runnable onSwitchNowRequested;
    private final Runnable onNextAccountRequested;
    private final Runnable onSellNowRequested;
    private final Runnable onLoginRequested;
    /** Uitloggen via plugin (combat/loot → {@code Game.logout} op client thread). */
    private final Runnable onPanelLogoutRequested;
    private final Runnable onTestAutoLoginRequested;
    private final Runnable onManagedAccountsSaved;
    /** Dubbelklik account op login-scherm: zet Jagex-logingegevens voor dit account. */
    private final Consumer<ManagedJagexAccountsStore.ManagedJagexAccountRow> onAccountPrepareLogin;
    /** Pending login + opgeslagen skill-timers wissen. */
    private final Runnable onResetAllBotState;
    /** Noodstop: bot uit + alle runtime/pending flows afbreken. */
    private final Runnable onEmergencyStopAll;
    /** Debug: forceer anti-ban speler-lookup testactie. */
    private final Runnable onTestPlayerLookupRequested;
    /** Debug: test lamp-widget hover flow (zonder klikken op skill/confirm). */
    private final Runnable onTestLampHoverRequested;
    /** Debug: forceer human-profile micro-mouse beweging nu, geeft status terug voor popup. */
    private final java.util.function.Supplier<String> onTestHumanMicroMouseRequested;
    /** Debug: forceer een fidget-burst (achtergrond mouse-fidget worker), geeft status terug. */
    private final java.util.function.Supplier<String> onTestFidgetBurstRequested;
    /** Besturingsbalk Start/Pauze/Stop/Reset op Accounts-tab — verversen op config + opruimen als tab weg is. */
    private final List<BotBarHandle> accountsBotBarHandles = new ArrayList<>();
    /** Hoofd-Debug-tab + eventueel los venster: allemaal dezelfde DebugLog-inhoud. */
    private final List<JTextArea> debugLogTextAreas = new CopyOnWriteArrayList<>();
    /**
     * Eén accountlijst voor ingebed paneel én los venster — anders importeer je in het ene venster
     * terwijl de tabel in het andere nog een oude {@code ArrayList} gebruikt (dan lijkt er "niets" te gebeuren).
     */
    private List<ManagedJagexAccountsStore.ManagedJagexAccountRow> sharedManagedAccountRows;
    /**
     * Elke geopende Accounts-tab registreert een refill-{@link Runnable}. Die runnable moet <b>niet</b> alleen
     * in een {@link WeakReference} zitten (dan kan GC 'm weggooien terwijl de tabel nog bestaat → geen refresh
     * na bewerken/opslaan). We houden de runnable sterk vast en koppelen een zwakke ref aan de {@link JTable}
     * om handles op te ruimen als het venster/tab weg is.
     */
    private final CopyOnWriteArrayList<AccountTableRefillHandle> accountTableRefillHandles = new CopyOnWriteArrayList<>();

    private static final class AccountTableRefillHandle {
        final WeakReference<JTable> tableRef;
        final Runnable refill;

        AccountTableRefillHandle(JTable table, Runnable refill) {
            this.tableRef = new WeakReference<>(table);
            this.refill = refill;
        }
    }
    /** Houd toggles met dezelfde config-key live synchroon (o.a. overlays in meerdere tabs). */
    private final Map<String, List<JCheckBox>> mirroredTogglesByConfigKey = new HashMap<>();
    private boolean syncingMirroredToggles = false;
    private static final String[] DEFAULT_DEBUG_SOURCES = {
            "COMBATBOT", "STARTSKILL", "STARTERSKILL", "MELEESTYLE", "COMBAT", "IMPS", "GIANTS",
            "WOODCUTTING", "MINING", "FISHING", "BARBLOOT", "QUEST", "LAMP", "DISCORD",
            "ACCOUNTS", "RELOG", "LOGIN", "MOVEMENTHELPER", "BANKHELPER", "UBM", "GERESTOCK",
            "ACCOUNTSTATEJSON", "INVCHECK", "SAFESPOT", "VARROCKTP", "DEBUG", "WIDGET", "STORMIMPORT"
    };

    private static final class BotBarHandle {
        final JButton anchor;
        final Runnable refresh;

        BotBarHandle(JButton anchor, Runnable refresh) {
            this.anchor = anchor;
            this.refresh = refresh;
        }
    }

    public CombatBotPanel(CombatBotPaint paint, CombatBotConfig config,
                          net.storm.api.plugins.config.ConfigManager configManager, String webGuiUrl,
                          Runnable onSwitchNowRequested, Runnable onNextAccountRequested, Runnable onSellNowRequested, Runnable onLoginRequested,
                          Runnable onPanelLogoutRequested,
                          Runnable onTestAutoLoginRequested, Runnable onManagedAccountsSaved,
                          Consumer<ManagedJagexAccountsStore.ManagedJagexAccountRow> onAccountPrepareLogin,
                          Runnable onResetAllBotState, Runnable onEmergencyStopAll,
                          Runnable onTestPlayerLookupRequested,
                          Runnable onTestLampHoverRequested,
                          java.util.function.Supplier<String> onTestHumanMicroMouseRequested,
                          java.util.function.Supplier<String> onTestFidgetBurstRequested) {
        this.paint = paint;
        this.config = config;
        this.configManager = configManager;
        this.onSwitchNowRequested = onSwitchNowRequested;
        this.onNextAccountRequested = onNextAccountRequested != null ? onNextAccountRequested : () -> {};
        this.onSellNowRequested = onSellNowRequested;
        this.onLoginRequested = onLoginRequested;
        this.onPanelLogoutRequested = onPanelLogoutRequested != null ? onPanelLogoutRequested : () -> {};
        this.onTestAutoLoginRequested = onTestAutoLoginRequested;
        this.onManagedAccountsSaved = onManagedAccountsSaved != null ? onManagedAccountsSaved : () -> {};
        this.onAccountPrepareLogin = onAccountPrepareLogin != null ? onAccountPrepareLogin : (r) -> {};
        this.onResetAllBotState = onResetAllBotState != null ? onResetAllBotState : () -> {};
        this.onEmergencyStopAll = onEmergencyStopAll != null ? onEmergencyStopAll : () -> {};
        this.onTestPlayerLookupRequested = onTestPlayerLookupRequested != null ? onTestPlayerLookupRequested : () -> {};
        this.onTestLampHoverRequested = onTestLampHoverRequested != null ? onTestLampHoverRequested : () -> {};
        this.onTestHumanMicroMouseRequested = onTestHumanMicroMouseRequested != null ? onTestHumanMicroMouseRequested
                : () -> "Geen handler bekend";
        this.onTestFidgetBurstRequested = onTestFidgetBurstRequested != null ? onTestFidgetBurstRequested
                : () -> "Fidget-handler niet bekend";
        DebugLog.setDisabledSourcesCsv(config.debugDisabledSourcesCsv());
        for (String src : DEFAULT_DEBUG_SOURCES) {
            DebugLog.setSourceEnabled(src, DebugLog.isSourceEnabled(src));
        }

        setLayout(new BorderLayout());
        setBackground(BG_DARK);

        // === Header ===
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(BG_DARK);
        header.setBorder(new EmptyBorder(8, 10, 4, 10));

        JLabel title = new JLabel("⚔ Combat Bot  v" + CombatBotPlugin.VERSION);
        title.setFont(new Font("Arial", Font.BOLD, 16));
        title.setForeground(GOLD);
        header.add(title, BorderLayout.WEST);

        // Knoppen panel (rechts)
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        btnPanel.setOpaque(false);

        // Pop-out knop
        JButton btnPopout = new JButton("📐");
        btnPopout.setToolTipText("Open in apart venster (resizable)");
        btnPopout.setFont(new Font("Arial", Font.PLAIN, 12));
        btnPopout.setPreferredSize(new Dimension(36, 24));
        btnPopout.setBackground(new Color(60, 70, 100));
        btnPopout.setForeground(Color.WHITE);
        btnPopout.setFocusPainted(false);
        btnPopout.addActionListener(e -> toggleDetachedWindow());
        btnPanel.add(btnPopout);

        // Web GUI knop
        String guiUrl = (webGuiUrl != null && !webGuiUrl.isEmpty()) ? webGuiUrl
                : "https://id-preview--189c9144-0c91-40c6-81e5-f3542a0829c4.lovable.app";
        JButton btnWeb = new JButton("🌐 Web");
        btnWeb.setFont(new Font("Arial", Font.PLAIN, 10));
        btnWeb.setPreferredSize(new Dimension(60, 24));
        btnWeb.setBackground(new Color(40, 120, 60));
        btnWeb.setForeground(Color.WHITE);
        btnWeb.setFocusPainted(false);
        btnWeb.addActionListener(e -> openBrowser(guiUrl));
        btnPanel.add(btnWeb);

        header.add(btnPanel, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        // === Tabbed pane ===
        tabs = new JTabbedPane();
        tabs.setFont(new Font("Arial", Font.BOLD, 11));
        tabs.setBackground(BG_DARK);

        tabs.addTab("👤 Accounts", createAccountsManagerTab());
        tabs.addTab("⚙ Settings", createSettingsTab());
        tabs.addTab("🎯 Skills", createSkillTabsTab());
        tabs.addTab("📊 Stats", createStatsTab());
        tabs.addTab("📍 Centers", createCentersTab(r -> embeddedCentersRefs = r));
        tabs.addTab("🔍 Debug", createDebugTab());
        tabs.setSelectedIndex(0);

        tabs.addChangeListener(e -> {
            if (!(e.getSource() instanceof JTabbedPane)) {
                return;
            }
            JTabbedPane tp = (JTabbedPane) e.getSource();
            int i = tp.getSelectedIndex();
            if (i < 0) {
                return;
            }
            String tabTitle = tp.getTitleAt(i);
            if (tabTitle != null && tabTitle.contains("Centers")) {
                refreshCentersTabRowsFromConfig();
            }
        });

        add(tabs, BorderLayout.CENTER);

        // Auto-open los venster zodra het zijpaneel zichtbaar wordt (Combat Bot-icoon)
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
                SwingUtilities.invokeLater(() -> {
                    if (detachedFrame == null || !detachedFrame.isVisible()) {
                        toggleDetachedWindow();
                    }
                });
            }
        });

        // Refresh timer
        refreshTimer = new Timer(1000, e -> updateLabels());
        refreshTimer.start();

        DebugLog.log("CombatBot", "Panel geladen — onder tab 🔍 Debug zie je deze log (vink 'Debug Log Aan' aan).");
        SwingUtilities.invokeLater(this::refreshDebugLogViews);
    }

    // ===================== SETTINGS TAB =====================

    private JScrollPane createSettingsTab() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(BG_DARK);
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));

        // ▶ Bot Control
        JPanel control = createSection("▶ Bot Control", true);
        addToggle(control, "Bot inschakelen", config.botEnabled(), v -> {
            setConfig("botEnabled", v);
            if (v) {
                notifyBotStartRequested();
            }
        });
        addToggle(control, "Grote loopstappen 15-20 (globaal)", config.impsForceLargeSteps(), v -> setConfig("impsForceLargeSteps", v));
        addToggle(control, "Reset per-account timers bij stop", config.resetAccountTimersOnStop(), v -> setConfig("resetAccountTimersOnStop", v));
        addToggle(control, "LoopWatch: herstel bij loop", config.loopWatchRecoveryEnabled(), v -> setConfig("loopWatchRecoveryEnabled", v));
        addSlider(control, "LoopWatch: herstel na (sec)", config.loopWatchTriggerSec(), 45, 600, v -> setConfig("loopWatchTriggerSec", v));
        addSlider(control, "LoopWatch: logout na (sec)", config.loopWatchLogoutSec(), 60, 900, v -> setConfig("loopWatchLogoutSec", v));
        addTriggerToggle(control, "🚪 Uitloggen (wacht op combat, loot, client thread)", "panelLogoutTrigger", onPanelLogoutRequested);
        addTextField(control, "Web GUI URL", config.webGuiUrl(), v -> setConfig("webGuiUrl", v));
        panel.add(control);
        panel.add(Box.createVerticalStrut(6));

        JPanel tiles = createSection("📍 Tile presets (snel)", false);
        addTextField(tiles, "Preset naam", config.tilePresetName(), v -> setConfig("tilePresetName", v));
        addActionButton(tiles, "💾 Sla huidige tile-set op als preset",
                () -> setConfig("saveTilePreset", true));
        addTextField(tiles, "Preset laden (naam)", config.loadTilePreset(), v -> setConfig("loadTilePreset", v));
        addActionButton(tiles, "📂 Laad preset (naam hierboven)",
                () -> setConfig("doLoadTilePreset", true));
        addActionButton(tiles, "🗑 Wis tile markers (huidige skill)",
                () -> setConfig("clearTileMarkers", true));
        panel.add(tiles);
        panel.add(Box.createVerticalStrut(6));

        JPanel portable = createSection("💾 Zelfde instellingen op andere PC", false);
        JLabel portableHelp = new JLabel("<html><div style='color:#a0a0b0;font-size:10px;width:280px'>"
                + "Export: sla je bot-instellingen op. Op de andere PC: Import. "
                + "Accounts-tab (blob, credentials) en re-log accounttekst worden <b>niet</b> meegenomen — die vul je lokaal zelf in."
                + "</div></html>");
        portableHelp.setAlignmentX(Component.LEFT_ALIGNMENT);
        portable.add(portableHelp);
        portable.add(Box.createVerticalStrut(6));
        JPanel portableBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        portableBtns.setOpaque(false);
        portableBtns.setAlignmentX(Component.LEFT_ALIGNMENT);
        JButton btnExportSettings = new JButton("Export instellingen…");
        styleSmallButton(btnExportSettings, new Color(50, 90, 70));
        btnExportSettings.setToolTipText("Sla alle Combat Bot-opties op in een JSON-bestand");
        btnExportSettings.addActionListener(e -> exportSettingsToFile(btnExportSettings));
        JButton btnImportSettings = new JButton("Import instellingen…");
        styleSmallButton(btnImportSettings, new Color(70, 65, 120));
        btnImportSettings.setToolTipText("Laad eerder geëxporteerde instellingen (overschrijft huidige config)");
        btnImportSettings.addActionListener(e -> importSettingsFromFile(btnImportSettings));
        portableBtns.add(btnExportSettings);
        portableBtns.add(btnImportSettings);
        portable.add(portableBtns);
        panel.add(portable);
        panel.add(Box.createVerticalStrut(6));

        // 🖼 Alle overlays centraal
        JPanel overlays = createSection("🖼 Overlay Settings", false);
        addOverlayToggle(overlays, "Combat overlay", config.showCombatOverlay(), "showCombatOverlay");
        addOverlayToggle(overlays, "WC overlay", config.showWcOverlay(), "showWcOverlay");
        addOverlayToggle(overlays, "Mining overlay", config.showMiningOverlay(), "showMiningOverlay");
        addOverlayToggle(overlays, "Fishing overlay", config.showFishingOverlay(), "showFishingOverlay");
        addOverlayToggle(overlays, "Imps overlay", config.showImpsOverlay(), "showImpsOverlay");
        addOverlayToggle(overlays, "Imps goblin coin overlay", config.impsShowGoblinCoinOverlay(), "impsShowGoblinCoinOverlay");
        addOverlayToggle(overlays, "Imps rally radius overlay", config.impsShowRallyRadius(), "impsShowRallyRadius");
        addOverlayToggle(overlays, "Giants overlay", config.showGiantsOverlay(), "showGiantsOverlay");
        addOverlayToggle(overlays, "Barbarian overlay", config.barbarianShowOverlay(), "barbarianShowOverlay");
        panel.add(overlays);
        panel.add(Box.createVerticalStrut(6));

        // ⚔ Combat
        JPanel combat = createSection("⚔ Combat Settings", false);
        addComboBox(combat, "Combat style", CombatBotConfig.ImpsCombatStyle.values(), config.combatStyle(),
                v -> setConfig("combatStyle", v.name()));
        addComboBox(combat, "Melee attack style", CombatBotConfig.MeleeTrainingStyle.values(), config.combatMeleeTrainingStyle(),
                v -> setConfig("combatMeleeTrainingStyle", v.name()));
        addTextField(combat, "Monster namen", config.monsterName(), v -> setConfig("monsterName", v));
        addComboBox(combat, "Food", CombatBotConfig.FoodChoice.values(), config.foodChoice(),
                v -> setConfig("foodChoice", v.name()));
        addSlider(combat, "Eat HP %", config.eatPercent(), 5, 90, v -> setConfig("eatPercent", v));
        addSlider(combat, "Attack range", config.attackRange(), 1, 20, v -> setConfig("attackRange", v));
        addSlider(combat, "Attack delay min (ms)", config.attackDelayMin(), 0, 5000, v -> setConfig("attackDelayMin", v));
        addSlider(combat, "Attack delay max (ms)", config.attackDelayMax(), 0, 10000, v -> setConfig("attackDelayMax", v));
        addToggle(combat, "Stop bij geen food", config.disableCombatNoFood(), v -> setConfig("disableCombatNoFood", v));
        addToggle(combat, "Bury Bones/Ashes", config.buryBones(), v -> setConfig("buryBones", v));
        addSlider(combat, "Bones/ashes bij min. X stuks", config.buryBonesMinBatch(), 1, 28, v -> setConfig("buryBonesMinBatch", v));
        addToggle(combat, "Safespot", config.safespotEnabled(), v -> setConfig("safespotEnabled", v));
        addToggle(combat, "🎯 Imps Mode", config.impsMode(), v -> setConfig("impsMode", v));
        addOverlayToggle(combat, "Combat overlay", config.showCombatOverlay(), "showCombatOverlay");
        addToggle(combat, "Arrows/bolts oppakken", config.pickupArrows(), v -> setConfig("pickupArrows", v));
        addSlider(combat, "Arrow pickup min kills", config.pickupArrowsMinKills(), 0, 30, v -> setConfig("pickupArrowsMinKills", v));
        addSlider(combat, "Arrow pickup max kills", config.pickupArrowsMaxKills(), 1, 50, v -> setConfig("pickupArrowsMaxKills", v));
        addSlider(combat, "Arrow min (bank onder)", config.combatArrowMin(), 0, 500, v -> setConfig("combatArrowMin", v));
        addSlider(combat, "Arrow target (ophalen)", config.combatArrowTarget(), 1, 1000, v -> setConfig("combatArrowTarget", v));
        addSlider(combat, "Rune min (bank onder)", config.combatRuneMin(), 0, 500, v -> setConfig("combatRuneMin", v));
        addSlider(combat, "Rune target (ophalen)", config.combatRuneTarget(), 1, 1000, v -> setConfig("combatRuneTarget", v));
        addComboBox(combat, "Genie lamp skill", CombatBotConfig.GenieLampSkill.values(), config.genieLampSkill(),
                v -> setConfig("genieLampSkill", v.name()));
        panel.add(combat);
        panel.add(Box.createVerticalStrut(6));

        // 💰 Loot settings (voor alle combat: normaal + imps)
        JPanel loot = createSection("💰 Loot Settings", false);
        addTextField(loot, "Loot items", config.lootItems(), v -> setConfig("lootItems", v));
        addSlider(loot, "Min HA waarde", config.lootMinValue(), 0, 50000, v -> setConfig("lootMinValue", v));
        addToggle(loot, "Loot op min waarde", config.lootByMinValue(), v -> setConfig("lootByMinValue", v));
        addToggle(loot, "Alleen eigen drops", config.lootOnlyOwn(), v -> setConfig("lootOnlyOwn", v));
        addTextField(loot, "Speciale loots", config.specialLootItems(), v -> setConfig("specialLootItems", v));
        addToggle(loot, "Bones & Fiendish ashes", config.lootBonesAndAshes(), v -> setConfig("lootBonesAndAshes", v));
        addToggle(loot, "Loot delay (per kills)", config.lootDelayEnabled(), v -> setConfig("lootDelayEnabled", v));
        addSlider(loot, "Kills vóór looten", config.lootDelayKills(), 0, 15, v -> setConfig("lootDelayKills", v));
        addSlider(loot, "Min wacht (sec)", config.lootDelayMinSeconds(), 0, 30, v -> setConfig("lootDelayMinSeconds", v));
        addSlider(loot, "Max wacht (sec)", config.lootDelayMaxSeconds(), 0, 60, v -> setConfig("lootDelayMaxSeconds", v));
        addToggle(loot, "Eet om ruimte te maken voor loot", config.eatToMakeSpaceForLoot(), v -> setConfig("eatToMakeSpaceForLoot", v));
        panel.add(loot);
        panel.add(Box.createVerticalStrut(6));

        // 🏦 Banking
        JPanel bank = createSection("🏦 Banking", false);
        addToggle(bank, "Bank als food op (Combat/Giants)", config.bankWhenNoFood(), v -> setConfig("bankWhenNoFood", v));
        addToggle(bank, "Bank gelootte items", config.bankLootedItems(), v -> setConfig("bankLootedItems", v));
        addSlider(bank, "Aantal food", config.foodAmount(), 1, 28, v -> setConfig("foodAmount", v));
        addToggle(bank, "GE food (Combat/Giants)", config.combatGeFoodEnabled(), v -> setConfig("combatGeFoodEnabled", v));
        addComboBox(bank, "GE food type (F2P)", CombatBotConfig.CombatGeFoodType.values(), config.combatGeFoodType(),
                v -> setConfig("combatGeFoodType", v.name()));
        addSlider(bank, "GE food basisprijs (gp)", config.combatGeFoodBasePrice(), 1, 50000, v -> setConfig("combatGeFoodBasePrice", v));
        addToggle(bank, "GE ranged ammo (Combat)", config.combatGeRangedAmmoEnabled(), v -> setConfig("combatGeRangedAmmoEnabled", v));
        addSlider(bank, "GE ranged ammo basisprijs (gp/stuk)", config.combatGeRangedAmmoBasePrice(), 1, 1000, v -> setConfig("combatGeRangedAmmoBasePrice", v));
        panel.add(bank);
        panel.add(Box.createVerticalStrut(6));

        // 🪓 Woodcutting
        JPanel wc = createSection("🪓 Woodcutting", false);
        addToggle(wc, "WC inschakelen", config.wcEnabled(), v -> setConfig("wcEnabled", v));
        addToggle(wc, "Specifieke boom", config.wcUseSpecificTree(), v -> setConfig("wcUseSpecificTree", v));
        addTextField(wc, "Boom naam", config.wcTreeName(), v -> setConfig("wcTreeName", v));
        addToggle(wc, "Logs droppen", config.wcDropLogs(), v -> setConfig("wcDropLogs", v));
        addToggle(wc, "🔥 Firemaking", config.wcFiremaking(), v -> setConfig("wcFiremaking", v));
        addToggle(wc, "Farm cash via Imps voor axe", config.wcAxeCashViaImpsEnabled(), v -> setConfig("wcAxeCashViaImpsEnabled", v));
        addSlider(wc, "Max Imps trips voor axe", config.wcAxeImpsTripCap(), 1, 30, v -> setConfig("wcAxeImpsTripCap", v));
        addSlider(wc, "WC delay min (ms)", config.wcInteractDelayMin(), 0, 5000, v -> setConfig("wcInteractDelayMin", v));
        addSlider(wc, "WC delay max (ms)", config.wcInteractDelayMax(), 0, 10000, v -> setConfig("wcInteractDelayMax", v));
        addOverlayToggle(wc, "WC overlay", config.showWcOverlay(), "showWcOverlay");
        panel.add(wc);
        panel.add(Box.createVerticalStrut(6));

        // ⛏ Mining
        JPanel mining = createSection("⛏ Mining", false);
        addToggle(mining, "Mining inschakelen", config.miningEnabled(), v -> setConfig("miningEnabled", v));
        addToggle(mining, "Doric's Quest automatisch", config.miningDoricsQuestAuto(), v -> setConfig("miningDoricsQuestAuto", v));
        addToggle(mining, "Specifiek erts", config.miningUseSpecificOre(), v -> setConfig("miningUseSpecificOre", v));
        addTextField(mining, "Erts naam", config.miningOreName(), v -> setConfig("miningOreName", v));
        addToggle(mining, "Erts droppen (standaard)", config.miningDropOre(), v -> setConfig("miningDropOre", v));
        addSlider(mining, "Mining delay min (ms)", config.miningInteractDelayMin(), 0, 5000, v -> setConfig("miningInteractDelayMin", v));
        addSlider(mining, "Mining delay max (ms)", config.miningInteractDelayMax(), 0, 10000, v -> setConfig("miningInteractDelayMax", v));
        addOverlayToggle(mining, "Mining overlay", config.showMiningOverlay(), "showMiningOverlay");
        addOverlayToggle(mining, "Markeer doelrots", config.showMiningRockTarget(), "showMiningRockTarget");
        panel.add(mining);
        panel.add(Box.createVerticalStrut(6));

        // 🐟 Fishing
        JPanel fishing = createSection("🐟 Fishing", false);
        addToggle(fishing, "Fishing inschakelen", config.fishingEnabled(), v -> setConfig("fishingEnabled", v));
        addToggle(fishing, "Specifieke methode", config.fishingUseSpecificMethod(), v -> setConfig("fishingUseSpecificMethod", v));
        addTextField(fishing, "Spot naam", config.fishingSpotName(), v -> setConfig("fishingSpotName", v));
        addTextField(fishing, "Vis actie", config.fishingAction(), v -> setConfig("fishingAction", v));
        addToggle(fishing, "Vis droppen", config.fishingDropFish(), v -> setConfig("fishingDropFish", v));
        addToggle(fishing, "🔥 Cooking", config.fishingCookEnabled(), v -> setConfig("fishingCookEnabled", v));
        addToggle(fishing, "🛒 Bait restock (GE)", config.fishingRestockEnabled(), v -> setConfig("fishingRestockEnabled", v));
        addSlider(fishing, "Restock hoeveelheid", config.fishingRestockAmount(), 100, 5000, v -> setConfig("fishingRestockAmount", v));
        addTextField(fishing, "Bait naam (Universal Banking)", config.fishingBaitName(), v -> setConfig("fishingBaitName", v));
        addSlider(fishing, "Bait min (bank onder)", config.fishingBaitMin(), 0, 500, v -> setConfig("fishingBaitMin", v));
        addSlider(fishing, "Bait target (ophalen)", config.fishingBaitTarget(), 1, 1000, v -> setConfig("fishingBaitTarget", v));
        addSlider(fishing, "Max prijs Bait (gp)", config.fishingBaitPrice(), 1, 50, v -> setConfig("fishingBaitPrice", v));
        addSlider(fishing, "Max prijs Feather (gp)", config.fishingFeatherPrice(), 1, 50, v -> setConfig("fishingFeatherPrice", v));
        addToggle(fishing, "Varrock teleport naar GE", config.fishingUseVarrockTeleport(), v -> setConfig("fishingUseVarrockTeleport", v));
        addSlider(fishing, "Fishing delay min (ms)", config.fishingInteractDelayMin(), 0, 5000, v -> setConfig("fishingInteractDelayMin", v));
        addSlider(fishing, "Fishing delay max (ms)", config.fishingInteractDelayMax(), 0, 10000, v -> setConfig("fishingInteractDelayMax", v));
        addOverlayToggle(fishing, "Fishing overlay", config.showFishingOverlay(), "showFishingOverlay");
        panel.add(fishing);
        panel.add(Box.createVerticalStrut(6));

        // 🚶 Movement (travel reclick — Fishing, WC, Mining, Giants)
        JPanel movement = createSection("🚶 Lopen / Travel", false);
        addSlider(movement, "Reclick interval (ms)", config.travelReclickIntervalMs(), 150, 5000, v -> setConfig("travelReclickIntervalMs", v));
        addSlider(movement, "Delay na click min (ms)", config.travelPostClickDelayMin(), 80, 2000, v -> setConfig("travelPostClickDelayMin", v));
        addSlider(movement, "Delay na click max (ms)", config.travelPostClickDelayMax(), 80, 3000, v -> setConfig("travelPostClickDelayMax", v));
        addToggle(movement, "Pathfinder walk (Storm)", config.travelUsePathfinderWalk(), v -> setConfig("travelUsePathfinderWalk", v));
        panel.add(movement);
        panel.add(Box.createVerticalStrut(6));

        // 🔄 Skill Rotation
        JPanel rotation = createSection("🔄 Skill Rotation", false);
        addOverlayToggle(rotation, "Toon Starter train-gebied", config.showStarterOverlay(), "showStarterOverlay");
        addComboBox(rotation, "Starter train-gebied", CombatBotConfig.StarterTrainRegion.values(), config.starterTrainRegion(),
                v -> setConfig("starterTrainRegion", v.name()));
        addComboBox(rotation, "Starter melee style", CombatBotConfig.MeleeTrainingStyle.values(), config.starterMeleeTrainingStyle(),
                v -> setConfig("starterMeleeTrainingStyle", v.name()));
        addComboBox(rotation, "Start skill", CombatBotConfig.StartSkill.values(), config.startSkill(),
                v -> setConfig("startSkill", v.name()));
        addToggle(rotation, "Combat in rotatie", config.combatInRotation(), v -> setConfig("combatInRotation", v));
        addToggle(rotation, "🎯 Imps in rotatie", config.impsMode(), v -> setConfig("impsMode", v));
        addToggle(rotation, "🐟 Barb fishing loot skill", config.barbLootEnabled(), v -> setConfig("barbLootEnabled", v));
        addToggle(rotation, "Loot-wacht: bonfire", config.barbLootBonfireWait(), v -> setConfig("barbLootBonfireWait", v));
        addSlider(rotation, "Barb loot: bank na X vis", config.barbLootBankFishCount(), 1, 28, v -> setConfig("barbLootBankFishCount", v));
        addSlider(rotation, "Barb loot: GE na X bank trips (0=uit)", config.barbLootGeAfterBanks(), 0, 30, v -> setConfig("barbLootGeAfterBanks", v));
        addSlider(rotation, "Barb GE: % onder markt", config.barbLootGePercentBelow(), 1, 50, v -> setConfig("barbLootGePercentBelow", v));
        addSlider(rotation, "Barb GE: min. cash reserve (gp)", config.barbLootGeMinCash(), 0, 50000, v -> setConfig("barbLootGeMinCash", v));
        addSlider(rotation, "Barb GE: mind runes (doel)", config.barbLootGeMindRunes(), 0, 10000, v -> setConfig("barbLootGeMindRunes", v));
        addToggle(rotation, "Barb GE: Staff of air kopen", config.barbLootGeBuyAirStaff(), v -> setConfig("barbLootGeBuyAirStaff", v));
        addSlider(rotation, "Min. minuten", config.rotationMinMinutes(), 1, 240, v -> setConfig("rotationMinMinutes", v));
        addSlider(rotation, "Max. minuten", config.rotationMaxMinutes(), 1, 240, v -> setConfig("rotationMaxMinutes", v));
        addTriggerToggle(rotation, "⚡ Switch Now", "switchNow", onSwitchNowRequested);
        panel.add(rotation);
        panel.add(Box.createVerticalStrut(6));

        // 🔁 Re-log (zelfde account)
        JPanel relog = createSection("🔁 Re-log (zelfde account)", false);
        addToggle(relog, "Re-log inschakelen", config.reLogoutEnabled(), v -> setConfig("reLogoutEnabled", v));
        addSlider(relog, "Min. minuten tot re-log", config.reLogoutMinMinutes(), 1, 240, v -> setConfig("reLogoutMinMinutes", v));
        addSlider(relog, "Max. minuten tot re-log", config.reLogoutMaxMinutes(), 1, 240, v -> setConfig("reLogoutMaxMinutes", v));
        addSlider(relog, "Pauze min (minuten)", config.reLogoutPauseMinMinutes(), 0, 60, v -> setConfig("reLogoutPauseMinMinutes", v));
        addSlider(relog, "Pauze max (minuten)", config.reLogoutPauseMaxMinutes(), 0, 90, v -> setConfig("reLogoutPauseMaxMinutes", v));
        addTextField(relog, "Account (email of jagex:path)", config.reLogoutAccount(), v -> setConfig("reLogoutAccount", v));
        addTriggerToggle(relog, "🔐 Log in (nu)", "loginNow", onLoginRequested);
        addTextField(relog, "Account lijst (geavanceerd)", config.accountList(), v -> setConfig("accountList", v));
        panel.add(relog);
        panel.add(Box.createVerticalStrut(6));

        // 🔔 Discord screenshots
        JPanel discord = createSection("🔔 Discord", false);
        addTextField(discord, "Webhook URL", config.discordWebhookUrl(), v -> setConfig("discordWebhookUrl", v));
        addToggle(discord, "Screenshots inschakelen", config.discordScreenshotsEnabled(), v -> setConfig("discordScreenshotsEnabled", v));
        addSlider(discord, "Interval (sec)", config.discordScreenshotIntervalSeconds(), 30, 300, v -> setConfig("discordScreenshotIntervalSeconds", v));
        addToggle(discord, "Uitgebreide webhook-tekst", config.discordDetailedWebhookText(), v -> setConfig("discordDetailedWebhookText", v));
        addToggle(discord, "Re-log: elke 5 min update (tekst)", config.discordRelogPausePingsEnabled(), v -> setConfig("discordRelogPausePingsEnabled", v));
        panel.add(discord);
        panel.add(Box.createVerticalStrut(6));

        // 🛒 GE Verkoop (Globaal)
        JPanel geSell = createSection("🛒 GE Verkoop", false);
        addToggle(geSell, "GE verkoop inschakelen", config.geSellEnabled(), v -> setConfig("geSellEnabled", v));
        addComboBox(geSell, "Prijs modus", CombatBotConfig.GeSellPriceMode.values(), config.geSellPriceMode(),
                v -> setConfig("geSellPriceMode", v.name()));
        addSlider(geSell, "Vaste prijs (gp)", config.geSellFixedPrice(), 1, 50000, v -> setConfig("geSellFixedPrice", v));
        addSlider(geSell, "% onder GE prijs", config.geSellPercentBelow(), 1, 50, v -> setConfig("geSellPercentBelow", v));
        addToggle(geSell, "Beads: eerst markt-5% (fallback prijs)", config.geSellBeadMarketFirst(), v -> setConfig("geSellBeadMarketFirst", v));
        addTextField(geSell, "Verkoop items", config.geSellLootItems(), v -> setConfig("geSellLootItems", v));
        addSlider(geSell, "GE na X bank trips", config.geSellAfterBanks(), 1, 20, v -> setConfig("geSellAfterBanks", v));
        panel.add(geSell);
        panel.add(Box.createVerticalStrut(6));

        // 🛡 Anti-Ban
        JPanel antiban = createSection("🛡 Anti-Ban", false);
        addToggle(antiban, "Anti-ban aan", config.antiBanEnabled(), v -> setConfig("antiBanEnabled", v));
        addToggle(antiban, "Per-RSN gedragsprofiel", config.accountBehaviorProfileEnabled(),
                v -> setConfig("accountBehaviorProfileEnabled", v));
        addSlider(antiban, "Frequentie (sec)", config.antiBanFrequency(), 10, 120, v -> setConfig("antiBanFrequency", v));
        addComboBox(antiban, "Heftigheid", CombatBotConfig.AntiBanIntensity.values(), config.antiBanIntensity(),
                v -> setConfig("antiBanIntensity", v.name()));
        addToggle(antiban, "Camera bewegingen", config.cameraMovement(), v -> setConfig("cameraMovement", v));
        addSlider(antiban, "Camera min duur (ms)", config.cameraDurationMin(), 100, 5000, v -> setConfig("cameraDurationMin", v));
        addSlider(antiban, "Camera max duur (ms)", config.cameraDurationMax(), 100, 8000, v -> setConfig("cameraDurationMax", v));
        addToggle(antiban, "Idle pauzes", config.idleChecks(), v -> setConfig("idleChecks", v));
        addToggle(antiban, "Muis bewegingen", config.randomMouseMovement(), v -> setConfig("randomMouseMovement", v));
        addSlider(antiban, "Micro-move amplitude min (px)", config.mouseMicroMoveAmpMinPx(), 1, 300,
                v -> setConfig("mouseMicroMoveAmpMinPx", v));
        addSlider(antiban, "Micro-move amplitude max (px)", config.mouseMicroMoveAmpMaxPx(), 1, 300,
                v -> setConfig("mouseMicroMoveAmpMaxPx", v));
        addSlider(antiban, "Micro-move duur min (ms)", config.mouseMicroMoveDurMinMs(), 50, 2000,
                v -> setConfig("mouseMicroMoveDurMinMs", v));
        addSlider(antiban, "Micro-move duur max (ms)", config.mouseMicroMoveDurMaxMs(), 50, 2000,
                v -> setConfig("mouseMicroMoveDurMaxMs", v));
        addToggle(antiban, "Continuous muis-fidget", config.mouseFidgetEnabled(), v -> setConfig("mouseFidgetEnabled", v));
        addSlider(antiban, "Fidget amplitude min (px)", config.mouseFidgetAmpMinPx(), 1, 200,
                v -> setConfig("mouseFidgetAmpMinPx", v));
        addSlider(antiban, "Fidget amplitude max (px)", config.mouseFidgetAmpMaxPx(), 1, 200,
                v -> setConfig("mouseFidgetAmpMaxPx", v));
        addSlider(antiban, "Fidget duur min (ms)", config.mouseFidgetDurMinMs(), 50, 2000,
                v -> setConfig("mouseFidgetDurMinMs", v));
        addSlider(antiban, "Fidget duur max (ms)", config.mouseFidgetDurMaxMs(), 50, 2000,
                v -> setConfig("mouseFidgetDurMaxMs", v));
        addToggle(antiban, "Tab-wissel (inventory)", config.tabGlanceEnabled(), v -> setConfig("tabGlanceEnabled", v));
        addToggle(antiban, "Use mouse to inv", config.openInventoryViaMouseClick(),
                v -> setConfig("openInventoryViaMouseClick", v));
        addToggle(antiban, "Misclicks", config.misClickEnabled(), v -> setConfig("misClickEnabled", v));
        addSlider(antiban, "Misclick kans %", config.misClickPercent(), 1, 30, v -> setConfig("misClickPercent", v));
        addToggle(antiban, "Speler lookup (rechtsklik)", config.playerLookupAntibanEnabled(),
                v -> setConfig("playerLookupAntibanEnabled", v));
        addToggle(antiban, "Skill hover (anti-ban)", config.skillHoverEnabled(),
                v -> setConfig("skillHoverEnabled", v));
        addSlider(antiban, "MMB stap snelheid min (ms)", config.mmbDragSpeedMin(), 5, 200, v -> setConfig("mmbDragSpeedMin", v));
        addSlider(antiban, "MMB stap snelheid max (ms)", config.mmbDragSpeedMax(), 5, 300, v -> setConfig("mmbDragSpeedMax", v));
        addSlider(antiban, "MMB drag afstand min (px)", config.mmbDragDistanceMin(), 10, 500, v -> setConfig("mmbDragDistanceMin", v));
        addSlider(antiban, "MMB drag afstand max (px)", config.mmbDragDistanceMax(), 10, 800, v -> setConfig("mmbDragDistanceMax", v));
        panel.add(antiban);
        panel.add(Box.createVerticalStrut(6));

        // 🎯 Imps Mode
        JPanel imps = createSection("🎯 Imps Mode (Karamja)", false);
        addToggle(imps, "Imps Mode", config.impsMode(), v -> setConfig("impsMode", v));
        addComboBox(imps, "Combat Style", CombatBotConfig.ImpsCombatStyle.values(), config.impsCombatStyle(),
                v -> setConfig("impsCombatStyle", v.name()));
        addComboBox(imps, "Melee attack style", CombatBotConfig.MeleeTrainingStyle.values(), config.impsMeleeTrainingStyle(),
                v -> setConfig("impsMeleeTrainingStyle", v.name()));
        addComboBox(imps, "Mage Spell", CombatBotConfig.ImpsMageSpell.values(), config.impsMageSpell(),
                v -> setConfig("impsMageSpell", v.name()));
        addTextField(imps, "Loot items", config.impsLootItems(), v -> setConfig("impsLootItems", v));
        addToggle(imps, "Scatter ashes", config.impsScatterAshes(), v -> setConfig("impsScatterAshes", v));
        addSlider(imps, "Bank threshold", config.impsBankThreshold(), 1, 28, v -> setConfig("impsBankThreshold", v));
        addSlider(imps, "Min coins", config.impsMinCoins(), 30, 500, v -> setConfig("impsMinCoins", v));
        JLabel impsHuntHint = new JLabel("<html><i>Centers-tab: met actieve Imps centers gelden die i.p.v. X/Y/radius hieronder.</i></html>");
        impsHuntHint.setFont(FONT_LABEL);
        impsHuntHint.setForeground(TEXT_DIM);
        impsHuntHint.setAlignmentX(Component.LEFT_ALIGNMENT);
        impsHuntHint.setBorder(new EmptyBorder(0, 10, 4, 10));
        imps.add(impsHuntHint);
        addSlider(imps, "Hunting X", config.impsHuntingX(), 2700, 3000, v -> setConfig("impsHuntingX", v));
        addSlider(imps, "Hunting Y", config.impsHuntingY(), 3100, 3300, v -> setConfig("impsHuntingY", v));
        addSlider(imps, "Hunting radius", config.impsHuntingRadius(), 5, 50, v -> setConfig("impsHuntingRadius", v));
        addToggle(imps, "Vermijd scorpion zones", config.impsAvoidScorpions(), v -> setConfig("impsAvoidScorpions", v));
        addSlider(imps, "Scorpion NPC level", config.impsScorpionLevel(), 1, 100, v -> setConfig("impsScorpionLevel", v));
        addOverlayToggle(imps, "Toon Imps overlay", config.showImpsOverlay(), "showImpsOverlay");
        addSlider(imps, "Idle roam (sec)", config.impsIdleRoamSeconds(), 0, 60, v -> setConfig("impsIdleRoamSeconds", v));
        addToggle(imps, "Val scorpions aan", config.impsAttackScorpions(), v -> setConfig("impsAttackScorpions", v));
        addSlider(imps, "Scorpion zone radius", config.impsScorpionZoneRadius(), 1, 20, v -> setConfig("impsScorpionZoneRadius", v));
        JCheckBox cbImpsHop = new JCheckBox("Wereld-hop bij andere imp-jager", config.impsCompetitorWorldHop());
        cbImpsHop.setToolTipText("Bevestigingspopup in-game uitzetten: World Switcher → Configure (tandwiel). "
                + "Anders klikt de bot automatisch op Switch world.");
        cbImpsHop.addActionListener(e -> setConfig("impsCompetitorWorldHop", cbImpsHop.isSelected()));
        cbImpsHop.setAlignmentX(Component.LEFT_ALIGNMENT);
        imps.add(cbImpsHop);
        addToggle(imps, "🛒 GE verkoop", config.impsGeSellEnabled(), v -> setConfig("impsGeSellEnabled", v));
        addSlider(imps, "GE na X bank trips", config.impsGeSellAfterBanks(), 1, 20, v -> setConfig("impsGeSellAfterBanks", v));
        addSlider(imps, "GE verkoopprijs (gp)", config.impsGeSellPrice(), 1, 10000, v -> setConfig("impsGeSellPrice", v));
        addSlider(imps, "Law rune koopprijs (gp)", config.impsLawRuneBuyPrice(), 1, 2000, v -> setConfig("impsLawRuneBuyPrice", v));
        addToggle(imps, "Teleports: runes bijkopen", config.impsTeleportBuyRunes(), v -> setConfig("impsTeleportBuyRunes", v));
        addToggle(imps, "Varrock teleport gebruiken", config.impsUseVarrockTeleport(), v -> setConfig("impsUseVarrockTeleport", v));
        addToggle(imps, "Falador teleport gebruiken", config.impsUseFaladorTeleport(), v -> setConfig("impsUseFaladorTeleport", v));
        addToggle(imps, "Lumbridge teleport gebruiken", config.impsUseLumbridgeTeleport(), v -> setConfig("impsUseLumbridgeTeleport", v));
        addToggle(imps, "Melee: open met 1x Air Strike", config.impsMeleeOpeningAirStrike(), v -> setConfig("impsMeleeOpeningAirStrike", v));
        addSlider(imps, "Melee opener min afstand", config.impsMeleeOpeningAirStrikeMinDistance(), 1, 12, v -> setConfig("impsMeleeOpeningAirStrikeMinDistance", v));
        addToggle(imps, "Melee opener debug", config.impsMeleeOpeningAirStrikeDebug(), v -> setConfig("impsMeleeOpeningAirStrikeDebug", v));
        addSlider(imps, "Loop stap min (tiles)", config.impsStepMinDistance(), 3, 25, v -> setConfig("impsStepMinDistance", v));
        addSlider(imps, "Loop stap max (tiles)", config.impsStepMaxDistance(), 5, 35, v -> setConfig("impsStepMaxDistance", v));
        addSlider(imps, "Rally point radius (tiles)", config.impsRallyPointRadius(), 1, 25, v -> setConfig("impsRallyPointRadius", v));
        addSlider(imps, "Arrival radius (tiles)", config.impsArrivalRadius(), 8, 20, v -> setConfig("impsArrivalRadius", v));
        addOverlayToggle(imps, "Toon rally radius overlay", config.impsShowRallyRadius(), "impsShowRallyRadius");
        addToggle(imps, "Stay inside hunt radius", config.impsStayInsideRadius(), v -> setConfig("impsStayInsideRadius", v));
        addSlider(imps, "Mage trip cast budget", config.impsMageTripCastBudget(), 1, 200, v -> setConfig("impsMageTripCastBudget", v));
        addSlider(imps, "Imp NPC ID (Storm, 0=naam-only)", config.impsNpcId(), 0, 20000, v -> setConfig("impsNpcId", v));
        addComboBox(imps, "Fallback combat style", CombatBotConfig.ImpsCombatStyle.values(), config.impsFallbackStyle(),
                v -> setConfig("impsFallbackStyle", v.name()));
        addSlider(imps, "Ammo/rune koopprijs (gp)", config.impsAmmoRestockPrice(), 1, 5000, v -> setConfig("impsAmmoRestockPrice", v));
        addTriggerToggle(imps, "🛒 Sell now (start GE verkoop)", "impsSellNow", onSellNowRequested);
        panel.add(imps);
        panel.add(Box.createVerticalStrut(6));

        // 🗡 Giants Mode (instellingen zoals Imps: combat style, gear prep, prijs-range, loot)
        JPanel giants = createSection("🗡 Giants Mode (Edgeville Dungeon)", false);
        addToggle(giants, "Giants Mode", config.giantsMode(), v -> setConfig("giantsMode", v));
        addTextField(giants, "Monster naam", config.giantsMonsterName(), v -> setConfig("giantsMonsterName", v));
        addComboBox(giants, "Combat style", CombatBotConfig.ImpsCombatStyle.values(), config.giantsCombatStyle(), v -> setConfig("giantsCombatStyle", v.name()));
        addComboBox(giants, "Melee attack style", CombatBotConfig.MeleeTrainingStyle.values(), config.giantsMeleeTrainingStyle(), v -> setConfig("giantsMeleeTrainingStyle", v.name()));
        addComboBox(giants, "Mage spell", CombatBotConfig.ImpsMageSpell.values(), config.giantsMageSpell(), v -> setConfig("giantsMageSpell", v.name()));
        addSlider(giants, "Brass key prijs min (gp)", config.giantsBrassKeyPriceMin(), 100, 2000, v -> setConfig("giantsBrassKeyPriceMin", v));
        addSlider(giants, "Brass key prijs max (gp)", config.giantsBrassKeyPriceMax(), 100, 2000, v -> setConfig("giantsBrassKeyPriceMax", v));
        addTextField(giants, "Loot items", config.giantsLootItems(), v -> setConfig("giantsLootItems", v));
        addSlider(giants, "Bank bij X loot items", config.giantsBankWhenLoot(), 1, 28, v -> setConfig("giantsBankWhenLoot", v));
        addToggle(giants, "Voorkeur Varrock ingang", config.giantsPreferVarrock(), v -> setConfig("giantsPreferVarrock", v));
        addOverlayToggle(giants, "Toon Giants overlay", config.showGiantsOverlay(), "showGiantsOverlay");
        panel.add(giants);

        panel.add(Box.createVerticalGlue());

        JScrollPane scroll = new JScrollPane(panel);
        scroll.setBackground(BG_DARK);
        scroll.getViewport().setBackground(BG_DARK);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    /** Extra overzicht: skill-eigen tabs i.p.v. alles in één lange settings lijst. */
    private JComponent createSkillTabsTab() {
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBackground(BG_DARK);

        JLabel hint = new JLabel("<html><div style='color:#a8a8b8;font-size:10px;width:280px;padding:4px 8px 6px 8px'>"
                + "Instellingen per skill hieronder. Overige opties (export, relog, random events, …) onder "
                + "<b>⚙ Settings</b>; <b>accounts en rotatie</b> onder <b>👤 Accounts</b>.</div></html>");
        hint.setBackground(BG_DARK);
        wrap.add(hint, BorderLayout.NORTH);

        JTabbedPane skillTabs = new JTabbedPane();
        skillTabs.setFont(new Font("Arial", Font.BOLD, 11));
        skillTabs.setBackground(BG_DARK);
        skillTabs.addTab("General", createGeneralSkillSettingsTab());
        skillTabs.addTab("Combat", createCombatSkillSettingsTab());
        skillTabs.addTab("Woodcut", createWoodcutSkillSettingsTab());
        skillTabs.addTab("Mining", createMiningSkillSettingsTab());
        skillTabs.addTab("Fishing", createFishingSkillSettingsTab());
        skillTabs.addTab("Imps", createImpsSkillSettingsTab());
        skillTabs.addTab("Giants", createGiantsSkillSettingsTab());
        skillTabs.addTab("Barbarian", createBarbarianSkillSettingsTab());
        wrap.add(skillTabs, BorderLayout.CENTER);
        return wrap;
    }

    private JScrollPane createGeneralSkillSettingsTab() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(BG_DARK);
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));

        panel.add(createCollapsibleSection("▶ Bot Control", true, true, c -> {
            JPanel testAutoLoginRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
            testAutoLoginRow.setBackground(BG_SECTION);
            testAutoLoginRow.setAlignmentX(Component.LEFT_ALIGNMENT);
            testAutoLoginRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
            JButton btnTestAutoLogin = new JButton("🔁 Test auto-login (re-log)");
            styleSmallButton(btnTestAutoLogin, new Color(55, 75, 120));
            btnTestAutoLogin.setToolTipText("<html>Alleen op het <b>login-scherm</b>: start dezelfde flow als bij Re-log (setGameAccount + Play, meerdere pogingen).<br>Vul <i>Account (re-log)</i> onder Settings of beheer accounts op de <b>Accounts-tab</b>.</html>");
            btnTestAutoLogin.addActionListener(e -> {
                if (onTestAutoLoginRequested != null) {
                    onTestAutoLoginRequested.run();
                }
            });
            testAutoLoginRow.add(btnTestAutoLogin);
            c.add(testAutoLoginRow);
            addToggle(c, "🐟 Barb fishing loot skill", config.barbLootEnabled(), v -> setConfig("barbLootEnabled", v));
            addToggle(c, "Loot-wacht: bonfire", config.barbLootBonfireWait(), v -> setConfig("barbLootBonfireWait", v));
            addSlider(c, "Barb: GE na X bank trips (0=uit)", config.barbLootGeAfterBanks(), 0, 30, v -> setConfig("barbLootGeAfterBanks", v));
            addSlider(c, "Barb GE: % onder markt", config.barbLootGePercentBelow(), 1, 50, v -> setConfig("barbLootGePercentBelow", v));
            addSlider(c, "Barb GE: min. cash (gp)", config.barbLootGeMinCash(), 0, 50000, v -> setConfig("barbLootGeMinCash", v));
            addSlider(c, "Barb GE: mind runes doel", config.barbLootGeMindRunes(), 0, 10000, v -> setConfig("barbLootGeMindRunes", v));
            addToggle(c, "Barb GE: Staff of air", config.barbLootGeBuyAirStaff(), v -> setConfig("barbLootGeBuyAirStaff", v));
            addTriggerToggle(c, "⚡ Switch Now", "switchNow", onSwitchNowRequested);
        }));
        panel.add(Box.createVerticalStrut(6));

        panel.add(createCollapsibleSection("🚶 Lopen / Travel", false, false, c -> {
            addSlider(c, "Reclick interval (ms)", config.travelReclickIntervalMs(), 150, 5000, v -> setConfig("travelReclickIntervalMs", v));
            addSlider(c, "Delay na click min (ms)", config.travelPostClickDelayMin(), 80, 2000, v -> setConfig("travelPostClickDelayMin", v));
            addSlider(c, "Delay na click max (ms)", config.travelPostClickDelayMax(), 80, 3000, v -> setConfig("travelPostClickDelayMax", v));
            addToggle(c, "Pathfinder walk (Storm)", config.travelUsePathfinderWalk(), v -> setConfig("travelUsePathfinderWalk", v));
        }));
        panel.add(Box.createVerticalStrut(6));

        panel.add(createCollapsibleSection("🏦 Banking & food (Combat/Giants)", false, false, c -> {
            addToggle(c, "Bank als food op (Combat/Giants)", config.bankWhenNoFood(), v -> setConfig("bankWhenNoFood", v));
            addToggle(c, "Bank gelootte items", config.bankLootedItems(), v -> setConfig("bankLootedItems", v));
            addSlider(c, "Aantal food", config.foodAmount(), 1, 28, v -> setConfig("foodAmount", v));
            addToggle(c, "GE food (Combat/Giants)", config.combatGeFoodEnabled(), v -> setConfig("combatGeFoodEnabled", v));
            addComboBox(c, "GE food type (F2P)", CombatBotConfig.CombatGeFoodType.values(), config.combatGeFoodType(),
                    v -> setConfig("combatGeFoodType", v.name()));
            addSlider(c, "GE food basisprijs (gp)", config.combatGeFoodBasePrice(), 1, 50000, v -> setConfig("combatGeFoodBasePrice", v));
        }));

        panel.add(Box.createVerticalGlue());
        return wrapScroll(panel);
    }

    private JScrollPane createCombatSkillSettingsTab() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(BG_DARK);
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));

        panel.add(createCollapsibleSection("⚔ Combat (basis)", false, true, c -> {
            addComboBox(c, "Combat style", CombatBotConfig.ImpsCombatStyle.values(), config.combatStyle(), v -> setConfig("combatStyle", v.name()));
            addComboBox(c, "Melee attack style", CombatBotConfig.MeleeTrainingStyle.values(), config.combatMeleeTrainingStyle(), v -> setConfig("combatMeleeTrainingStyle", v.name()));
            addTextField(c, "Monster namen", config.monsterName(), v -> setConfig("monsterName", v));
            addComboBox(c, "Food", CombatBotConfig.FoodChoice.values(), config.foodChoice(), v -> setConfig("foodChoice", v.name()));
            addSlider(c, "Eat HP %", config.eatPercent(), 5, 90, v -> setConfig("eatPercent", v));
            addSlider(c, "Attack range", config.attackRange(), 1, 20, v -> setConfig("attackRange", v));
            addSlider(c, "Attack delay min (ms)", config.attackDelayMin(), 0, 5000, v -> setConfig("attackDelayMin", v));
            addSlider(c, "Attack delay max (ms)", config.attackDelayMax(), 0, 10000, v -> setConfig("attackDelayMax", v));
            addToggle(c, "Stop bij geen food", config.disableCombatNoFood(), v -> setConfig("disableCombatNoFood", v));
            addToggle(c, "Bury Bones/Ashes", config.buryBones(), v -> setConfig("buryBones", v));
            addSlider(c, "Bones/ashes bij min. X stuks", config.buryBonesMinBatch(), 1, 28, v -> setConfig("buryBonesMinBatch", v));
            addToggle(c, "Safespot", config.safespotEnabled(), v -> setConfig("safespotEnabled", v));
            addOverlayToggle(c, "Combat overlay", config.showCombatOverlay(), "showCombatOverlay");
        }));
        panel.add(Box.createVerticalStrut(6));

        panel.add(createCollapsibleSection("🏹 Pijlen & runes", false, false, c -> {
            addSlider(c, "Arrow min (bank onder)", config.combatArrowMin(), 0, 500, v -> setConfig("combatArrowMin", v));
            addSlider(c, "Arrow target (ophalen)", config.combatArrowTarget(), 1, 1000, v -> setConfig("combatArrowTarget", v));
            addSlider(c, "Rune min (bank onder)", config.combatRuneMin(), 0, 500, v -> setConfig("combatRuneMin", v));
            addSlider(c, "Rune target (ophalen)", config.combatRuneTarget(), 1, 1000, v -> setConfig("combatRuneTarget", v));
        }));
        panel.add(Box.createVerticalStrut(6));

        panel.add(createCollapsibleSection("🧞 Genie lamp (random event)", false, false, c -> {
            JLabel lampInfo = new JLabel("<html><i>Kies welke skill de lamp gebruikt. "
                    + "Met de testknop hieronder wordt alleen gehoverd op Attack → Magic → Confirm (geen klik).</i></html>");
            lampInfo.setFont(FONT_LABEL);
            lampInfo.setForeground(TEXT_DIM);
            lampInfo.setAlignmentX(Component.LEFT_ALIGNMENT);
            lampInfo.setBorder(new EmptyBorder(0, 10, 6, 10));
            c.add(lampInfo);

            addComboBox(c, "Genie lamp skill", CombatBotConfig.GenieLampSkill.values(), config.genieLampSkill(),
                    v -> setConfig("genieLampSkill", v.name()));

            JPanel lampTestRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
            lampTestRow.setOpaque(false);
            lampTestRow.setAlignmentX(Component.LEFT_ALIGNMENT);
            JButton lampTestBtn = new JButton("🧪 Test lamp hover");
            styleSmallButton(lampTestBtn, new Color(65, 80, 120));
            lampTestBtn.setToolTipText("Opent lamp en hovert op Attack, Magic en Confirm widgets (zonder klik).");
            lampTestBtn.addActionListener(e -> onTestLampHoverRequested.run());
            lampTestRow.add(lampTestBtn);
            c.add(lampTestRow);
        }));
        panel.add(Box.createVerticalStrut(6));

        panel.add(createCollapsibleSection("💰 Loot", false, false, c -> {
            addTextField(c, "Loot items", config.lootItems(), v -> setConfig("lootItems", v));
            addSlider(c, "Min HA waarde", config.lootMinValue(), 0, 50000, v -> setConfig("lootMinValue", v));
            addToggle(c, "Loot op min waarde", config.lootByMinValue(), v -> setConfig("lootByMinValue", v));
            addToggle(c, "Alleen eigen drops", config.lootOnlyOwn(), v -> setConfig("lootOnlyOwn", v));
            addTextField(c, "Speciale loots", config.specialLootItems(), v -> setConfig("specialLootItems", v));
            addToggle(c, "Bones & Fiendish ashes", config.lootBonesAndAshes(), v -> setConfig("lootBonesAndAshes", v));
            addToggle(c, "Loot delay (per kills)", config.lootDelayEnabled(), v -> setConfig("lootDelayEnabled", v));
            addSlider(c, "Kills vóór looten", config.lootDelayKills(), 0, 15, v -> setConfig("lootDelayKills", v));
            addSlider(c, "Min wacht (sec)", config.lootDelayMinSeconds(), 0, 30, v -> setConfig("lootDelayMinSeconds", v));
            addSlider(c, "Max wacht (sec)", config.lootDelayMaxSeconds(), 0, 60, v -> setConfig("lootDelayMaxSeconds", v));
            addToggle(c, "Eet om ruimte te maken voor loot", config.eatToMakeSpaceForLoot(), v -> setConfig("eatToMakeSpaceForLoot", v));
        }));

        panel.add(Box.createVerticalGlue());
        return wrapScroll(panel);
    }

    private JScrollPane createWoodcutSkillSettingsTab() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(BG_DARK);
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));
        panel.add(createCollapsibleSection("🪓 Woodcutting (basis)", false, true, c -> {
            addToggle(c, "WC inschakelen", config.wcEnabled(), v -> setConfig("wcEnabled", v));
            addToggle(c, "Specifieke boom", config.wcUseSpecificTree(), v -> setConfig("wcUseSpecificTree", v));
            addTextField(c, "Boom naam", config.wcTreeName(), v -> setConfig("wcTreeName", v));
            addToggle(c, "Logs droppen", config.wcDropLogs(), v -> setConfig("wcDropLogs", v));
            addToggle(c, "🔥 Firemaking", config.wcFiremaking(), v -> setConfig("wcFiremaking", v));
            addToggle(c, "Farm cash via Imps voor axe", config.wcAxeCashViaImpsEnabled(), v -> setConfig("wcAxeCashViaImpsEnabled", v));
            addSlider(c, "Max Imps trips voor axe", config.wcAxeImpsTripCap(), 1, 30, v -> setConfig("wcAxeImpsTripCap", v));
        }));
        panel.add(Box.createVerticalStrut(6));
        panel.add(createCollapsibleSection("⏱ WC delays & overlay", false, false, c -> {
            addSlider(c, "WC delay min (ms)", config.wcInteractDelayMin(), 0, 5000, v -> setConfig("wcInteractDelayMin", v));
            addSlider(c, "WC delay max (ms)", config.wcInteractDelayMax(), 0, 10000, v -> setConfig("wcInteractDelayMax", v));
            addOverlayToggle(c, "WC overlay", config.showWcOverlay(), "showWcOverlay");
        }));
        panel.add(Box.createVerticalGlue());
        return wrapScroll(panel);
    }

    private JScrollPane createMiningSkillSettingsTab() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(BG_DARK);
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));
        panel.add(createCollapsibleSection("⛏ Mining (basis)", false, true, c -> {
            addToggle(c, "Mining inschakelen", config.miningEnabled(), v -> setConfig("miningEnabled", v));
            addToggle(c, "Doric's Quest automatisch", config.miningDoricsQuestAuto(), v -> setConfig("miningDoricsQuestAuto", v));
            addToggle(c, "Specifiek erts", config.miningUseSpecificOre(), v -> setConfig("miningUseSpecificOre", v));
            addTextField(c, "Erts naam", config.miningOreName(), v -> setConfig("miningOreName", v));
            addToggle(c, "Erts droppen (standaard)", config.miningDropOre(), v -> setConfig("miningDropOre", v));
            JLabel dropHint = new JLabel("<html><div style='color:#a0a0b0;font-size:10px;width:280px'>"
                    + "Geldt voor onbekende locaties. <b>Al Kharid 2</b> bankt ijzer altijd; "
                    + "<b>Al Kharid 3</b> dropt ijzer altijd — ook als deze toggle aan staat. "
                    + "Center-naam moet \"alkarid 2\" of \"alkarid 3\" bevatten (of standaard-coördinaten).</div></html>");
            dropHint.setFont(FONT_LABEL);
            dropHint.setForeground(TEXT_DIM);
            dropHint.setAlignmentX(Component.LEFT_ALIGNMENT);
            dropHint.setBorder(new EmptyBorder(0, 18, 4, 4));
            c.add(dropHint);
        }));
        panel.add(Box.createVerticalStrut(6));
        panel.add(createCollapsibleSection("⏱ Mining delays & overlay", false, false, c -> {
            addSlider(c, "Mining delay min (ms)", config.miningInteractDelayMin(), 0, 5000, v -> setConfig("miningInteractDelayMin", v));
            addSlider(c, "Mining delay max (ms)", config.miningInteractDelayMax(), 0, 10000, v -> setConfig("miningInteractDelayMax", v));
            addOverlayToggle(c, "Mining overlay", config.showMiningOverlay(), "showMiningOverlay");
            addOverlayToggle(c, "Markeer doelrots", config.showMiningRockTarget(), "showMiningRockTarget");
        }));
        panel.add(Box.createVerticalGlue());
        return wrapScroll(panel);
    }

    private JScrollPane createFishingSkillSettingsTab() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(BG_DARK);
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));
        panel.add(createCollapsibleSection("🐟 Fishing (basis)", false, true, c -> {
            addToggle(c, "Fishing inschakelen", config.fishingEnabled(), v -> setConfig("fishingEnabled", v));
            addToggle(c, "Specifieke methode", config.fishingUseSpecificMethod(), v -> setConfig("fishingUseSpecificMethod", v));
            addTextField(c, "Spot naam", config.fishingSpotName(), v -> setConfig("fishingSpotName", v));
            addTextField(c, "Vis actie", config.fishingAction(), v -> setConfig("fishingAction", v));
            addToggle(c, "Vis droppen", config.fishingDropFish(), v -> setConfig("fishingDropFish", v));
            addToggle(c, "🔥 Cooking", config.fishingCookEnabled(), v -> setConfig("fishingCookEnabled", v));
        }));
        panel.add(Box.createVerticalStrut(6));
        panel.add(createCollapsibleSection("🎣 Bait, bank & GE", false, false, c -> {
            addSlider(c, "Bait min (bank onder)", config.fishingBaitMin(), 0, 500, v -> setConfig("fishingBaitMin", v));
            addSlider(c, "Bait target (ophalen)", config.fishingBaitTarget(), 1, 2000, v -> setConfig("fishingBaitTarget", v));
            addSlider(c, "Restock hoeveelheid", config.fishingRestockAmount(), 100, 5000, v -> setConfig("fishingRestockAmount", v));
            addTextField(c, "Bait naam (Universal Banking)", config.fishingBaitName(), v -> setConfig("fishingBaitName", v));
            addToggle(c, "🛒 Bait restock (GE)", config.fishingRestockEnabled(), v -> setConfig("fishingRestockEnabled", v));
            addSlider(c, "Max prijs Bait (gp)", config.fishingBaitPrice(), 1, 50, v -> setConfig("fishingBaitPrice", v));
            addSlider(c, "Max prijs Feather (gp)", config.fishingFeatherPrice(), 1, 50, v -> setConfig("fishingFeatherPrice", v));
            addToggle(c, "Varrock teleport naar GE", config.fishingUseVarrockTeleport(), v -> setConfig("fishingUseVarrockTeleport", v));
        }));
        panel.add(Box.createVerticalStrut(6));
        panel.add(createCollapsibleSection("⏱ Fishing delays & overlay", false, false, c -> {
            addSlider(c, "Fishing delay min (ms)", config.fishingInteractDelayMin(), 0, 5000, v -> setConfig("fishingInteractDelayMin", v));
            addSlider(c, "Fishing delay max (ms)", config.fishingInteractDelayMax(), 0, 10000, v -> setConfig("fishingInteractDelayMax", v));
            addOverlayToggle(c, "Fishing overlay", config.showFishingOverlay(), "showFishingOverlay");
        }));
        panel.add(Box.createVerticalGlue());
        return wrapScroll(panel);
    }

    private JScrollPane createImpsSkillSettingsTab() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(BG_DARK);
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));

        panel.add(createCollapsibleSection("🎯 Imps — basis", false, true, c -> {
            addToggle(c, "Imps Mode", config.impsMode(), v -> setConfig("impsMode", v));
            addComboBox(c, "Combat Style", CombatBotConfig.ImpsCombatStyle.values(), config.impsCombatStyle(), v -> setConfig("impsCombatStyle", v.name()));
            addComboBox(c, "Melee attack style", CombatBotConfig.MeleeTrainingStyle.values(), config.impsMeleeTrainingStyle(), v -> setConfig("impsMeleeTrainingStyle", v.name()));
            addComboBox(c, "Mage Spell", CombatBotConfig.ImpsMageSpell.values(), config.impsMageSpell(), v -> setConfig("impsMageSpell", v.name()));
            addTextField(c, "Loot items", config.impsLootItems(), v -> setConfig("impsLootItems", v));
            addToggle(c, "Scatter ashes", config.impsScatterAshes(), v -> setConfig("impsScatterAshes", v));
            addSlider(c, "Bank threshold", config.impsBankThreshold(), 1, 28, v -> setConfig("impsBankThreshold", v));
            addSlider(c, "Min coins", config.impsMinCoins(), 30, 500, v -> setConfig("impsMinCoins", v));
            addOverlayToggle(c, "Toon Imps overlay", config.showImpsOverlay(), "showImpsOverlay");
            addSlider(c, "Idle roam (sec)", config.impsIdleRoamSeconds(), 0, 60, v -> setConfig("impsIdleRoamSeconds", v));
        }));
        panel.add(Box.createVerticalStrut(6));

        panel.add(createCollapsibleSection("📍 Jachtgebied (fallback X/Y)", false, false, c -> {
            JLabel impsHuntHint = new JLabel("<html><div style='color:#a0a0b0;font-size:10px;width:260px'><i>Centers-tab: actieve Imps centers gaan vóór X/Y/radius hier.</i></div></html>");
            impsHuntHint.setAlignmentX(Component.LEFT_ALIGNMENT);
            impsHuntHint.setBorder(new EmptyBorder(0, 10, 6, 10));
            c.add(impsHuntHint);
            addSlider(c, "Hunting X", config.impsHuntingX(), 2700, 3000, v -> setConfig("impsHuntingX", v));
            addSlider(c, "Hunting Y", config.impsHuntingY(), 3100, 3300, v -> setConfig("impsHuntingY", v));
            addSlider(c, "Hunting radius", config.impsHuntingRadius(), 5, 80, v -> setConfig("impsHuntingRadius", v));
        }));
        panel.add(Box.createVerticalStrut(6));

        panel.add(createCollapsibleSection("🪙 Goblin coin-recovery gebied", false, false, c -> {
            addSlider(c, "Goblin center X", config.impsGoblinCoinCenterX(), 2950, 3050, v -> setConfig("impsGoblinCoinCenterX", v));
            addSlider(c, "Goblin center Y", config.impsGoblinCoinCenterY(), 3160, 3260, v -> setConfig("impsGoblinCoinCenterY", v));
            addSlider(c, "Goblin radius", config.impsGoblinCoinRadius(), 3, 30, v -> setConfig("impsGoblinCoinRadius", v));
            addOverlayToggle(c, "Toon goblin coin overlay", config.impsShowGoblinCoinOverlay(), "impsShowGoblinCoinOverlay");
        }));
        panel.add(Box.createVerticalStrut(6));

        panel.add(createCollapsibleSection("🦂 Scorpions", false, false, c -> {
            addToggle(c, "Vermijd scorpion zones", config.impsAvoidScorpions(), v -> setConfig("impsAvoidScorpions", v));
            addSlider(c, "Scorpion NPC level", config.impsScorpionLevel(), 1, 100, v -> setConfig("impsScorpionLevel", v));
            addToggle(c, "Val scorpions aan", config.impsAttackScorpions(), v -> setConfig("impsAttackScorpions", v));
            addSlider(c, "Scorpion zone radius", config.impsScorpionZoneRadius(), 1, 20, v -> setConfig("impsScorpionZoneRadius", v));
        }));
        panel.add(Box.createVerticalStrut(6));

        panel.add(createCollapsibleSection("🛒 GE verkoop & teleports", false, false, c -> {
            addToggle(c, "🛒 GE verkoop", config.impsGeSellEnabled(), v -> setConfig("impsGeSellEnabled", v));
            addSlider(c, "GE na X bank trips", config.impsGeSellAfterBanks(), 1, 20, v -> setConfig("impsGeSellAfterBanks", v));
            addSlider(c, "GE verkoopprijs (gp)", config.impsGeSellPrice(), 1, 10000, v -> setConfig("impsGeSellPrice", v));
            addSlider(c, "Law rune koopprijs (gp)", config.impsLawRuneBuyPrice(), 1, 2000, v -> setConfig("impsLawRuneBuyPrice", v));
            addToggle(c, "Teleports: runes bijkopen", config.impsTeleportBuyRunes(), v -> setConfig("impsTeleportBuyRunes", v));
            addToggle(c, "Varrock teleport gebruiken", config.impsUseVarrockTeleport(), v -> setConfig("impsUseVarrockTeleport", v));
            addToggle(c, "Falador teleport gebruiken", config.impsUseFaladorTeleport(), v -> setConfig("impsUseFaladorTeleport", v));
            addToggle(c, "Lumbridge teleport gebruiken", config.impsUseLumbridgeTeleport(), v -> setConfig("impsUseLumbridgeTeleport", v));
            addTriggerToggle(c, "🛒 Sell now (start GE verkoop)", "impsSellNow", onSellNowRequested);
        }));
        panel.add(Box.createVerticalStrut(6));

        panel.add(createCollapsibleSection("⚔ Melee opener (Air Strike)", false, false, c -> {
            addToggle(c, "Melee: open met 1x Air Strike", config.impsMeleeOpeningAirStrike(), v -> setConfig("impsMeleeOpeningAirStrike", v));
            addSlider(c, "Melee opener min afstand", config.impsMeleeOpeningAirStrikeMinDistance(), 1, 12, v -> setConfig("impsMeleeOpeningAirStrikeMinDistance", v));
            addToggle(c, "Melee opener debug", config.impsMeleeOpeningAirStrikeDebug(), v -> setConfig("impsMeleeOpeningAirStrikeDebug", v));
        }));
        panel.add(Box.createVerticalStrut(6));

        panel.add(createCollapsibleSection("🚶 Loop-stappen & rally", false, false, c -> {
            addSlider(c, "Loop stap min (tiles)", config.impsStepMinDistance(), 3, 25, v -> setConfig("impsStepMinDistance", v));
            addSlider(c, "Loop stap max (tiles)", config.impsStepMaxDistance(), 5, 35, v -> setConfig("impsStepMaxDistance", v));
            addSlider(c, "Rally point radius (tiles)", config.impsRallyPointRadius(), 1, 50, v -> setConfig("impsRallyPointRadius", v));
            addSlider(c, "Arrival radius (tiles)", config.impsArrivalRadius(), 8, 20, v -> setConfig("impsArrivalRadius", v));
            addOverlayToggle(c, "Toon rally radius overlay", config.impsShowRallyRadius(), "impsShowRallyRadius");
        }));

        panel.add(Box.createVerticalGlue());
        return wrapScroll(panel);
    }

    private JScrollPane createGiantsSkillSettingsTab() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(BG_DARK);
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));

        panel.add(createCollapsibleSection("🗡 Giants — basis", false, true, c -> {
            addToggle(c, "Giants Mode", config.giantsMode(), v -> setConfig("giantsMode", v));
            addTextField(c, "Monster naam", config.giantsMonsterName(), v -> setConfig("giantsMonsterName", v));
            addComboBox(c, "Combat style", CombatBotConfig.ImpsCombatStyle.values(), config.giantsCombatStyle(), v -> setConfig("giantsCombatStyle", v.name()));
            addComboBox(c, "Melee attack style", CombatBotConfig.MeleeTrainingStyle.values(), config.giantsMeleeTrainingStyle(), v -> setConfig("giantsMeleeTrainingStyle", v.name()));
            addComboBox(c, "Mage spell", CombatBotConfig.ImpsMageSpell.values(), config.giantsMageSpell(), v -> setConfig("giantsMageSpell", v.name()));
            addSlider(c, "Brass key prijs min (gp)", config.giantsBrassKeyPriceMin(), 100, 2000, v -> setConfig("giantsBrassKeyPriceMin", v));
            addSlider(c, "Brass key prijs max (gp)", config.giantsBrassKeyPriceMax(), 100, 2000, v -> setConfig("giantsBrassKeyPriceMax", v));
        }));
        panel.add(Box.createVerticalStrut(6));
        panel.add(createCollapsibleSection("💰 Loot & overlay", false, false, c -> {
            addTextField(c, "Loot items", config.giantsLootItems(), v -> setConfig("giantsLootItems", v));
            addSlider(c, "Bank bij X loot items", config.giantsBankWhenLoot(), 1, 28, v -> setConfig("giantsBankWhenLoot", v));
            addToggle(c, "Voorkeur Varrock ingang", config.giantsPreferVarrock(), v -> setConfig("giantsPreferVarrock", v));
            addOverlayToggle(c, "Toon Giants overlay", config.showGiantsOverlay(), "showGiantsOverlay");
        }));
        panel.add(Box.createVerticalStrut(6));
        panel.add(createCollapsibleSection("🍗 Food banking (onafhankelijk)", false, true, c -> {
            JLabel info = new JLabel("<html><i>Werkt los van de globale 'Bank als food op'-switch.<br>"
                    + "Zodra food in inventory onder de drempel komt → terug naar bank, "
                    + "ongeacht HP. Voorkomt paniek-eat-loops.</i></html>");
            info.setForeground(new Color(180, 180, 180));
            info.setFont(info.getFont().deriveFont(11f));
            info.setBorder(new EmptyBorder(2, 4, 6, 4));
            c.add(info);
            addToggle(c, "Giants: bank voor food", config.giantsBankForFood(),
                    v -> setConfig("giantsBankForFood", v));
            addSlider(c, "Giants: food drempel", config.giantsLowFoodBankThreshold(), 1, 10,
                    v -> setConfig("giantsLowFoodBankThreshold", v));
        }));

        panel.add(Box.createVerticalGlue());
        return wrapScroll(panel);
    }

    private JScrollPane createBarbarianSkillSettingsTab() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(BG_DARK);
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));

        panel.add(createCollapsibleSection("🛡 Barbarian (Longhall) — basis", false, true, c -> {
            addToggle(c, "Barbarian mode", config.barbarianMode(), v -> setConfig("barbarianMode", v));
            addToggle(c, "Pak cooked meat", config.barbarianPickupCookedMeat(), v -> setConfig("barbarianPickupCookedMeat", v));
            addSlider(c, "Food min", config.barbarianFoodMin(), 0, 28, v -> setConfig("barbarianFoodMin", v));
            addSlider(c, "Food target", config.barbarianFoodTarget(), 1, 28, v -> setConfig("barbarianFoodTarget", v));
            addToggle(c, "Loot bones", config.barbarianLootBones(), v -> setConfig("barbarianLootBones", v));
            addToggle(c, "Loot coins", config.barbarianLootCoins(), v -> setConfig("barbarianLootCoins", v));
            addOverlayToggle(c, "Toon Barbarian overlay", config.barbarianShowOverlay(), "barbarianShowOverlay");
        }));
        panel.add(Box.createVerticalStrut(6));

        panel.add(createCollapsibleSection("📍 Longhall gebied", false, false, c -> {
            addSlider(c, "Hall X", config.barbarianHallX(), 3000, 3200, v -> setConfig("barbarianHallX", v));
            addSlider(c, "Hall Y", config.barbarianHallY(), 3400, 3520, v -> setConfig("barbarianHallY", v));
            addSlider(c, "Hall radius", config.barbarianHallRadius(), 3, 20, v -> setConfig("barbarianHallRadius", v));
            addSlider(c, "Hoek 1 X", config.barbarianCorner1X(), 3000, 3200, v -> setConfig("barbarianCorner1X", v));
            addSlider(c, "Hoek 1 Y", config.barbarianCorner1Y(), 3400, 3520, v -> setConfig("barbarianCorner1Y", v));
            addSlider(c, "Hoek 2 X", config.barbarianCorner2X(), 3000, 3200, v -> setConfig("barbarianCorner2X", v));
            addSlider(c, "Hoek 2 Y", config.barbarianCorner2Y(), 3400, 3520, v -> setConfig("barbarianCorner2Y", v));
            addSlider(c, "Hoek 3 X", config.barbarianCorner3X(), 3000, 3200, v -> setConfig("barbarianCorner3X", v));
            addSlider(c, "Hoek 3 Y", config.barbarianCorner3Y(), 3400, 3520, v -> setConfig("barbarianCorner3Y", v));
            addSlider(c, "Hoek 4 X", config.barbarianCorner4X(), 3000, 3200, v -> setConfig("barbarianCorner4X", v));
            addSlider(c, "Hoek 4 Y", config.barbarianCorner4Y(), 3400, 3520, v -> setConfig("barbarianCorner4Y", v));
        }));

        panel.add(Box.createVerticalGlue());
        return wrapScroll(panel);
    }

    private JScrollPane wrapScroll(JPanel panel) {
        JScrollPane scroll = new JScrollPane(panel);
        scroll.setBackground(BG_DARK);
        scroll.getViewport().setBackground(BG_DARK);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    // ===================== STATS TAB =====================

    private JScrollPane createStatsTab() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(BG_DARK);
        panel.setBorder(new EmptyBorder(8, 10, 8, 10));

        addSectionTitle(panel, "Status");
        addStatRow(panel, "Runtime:", lblRuntime);
        addStatRow(panel, "Actieve Skill:", lblActiveSkill);
        addStatRow(panel, "Status:", lblStatus);
        addStatRow(panel, "Re-log:", lblRelog);
        addSep(panel);

        addSectionTitle(panel, "XP Verdiend");
        addStatRow(panel, "Combat:", lblCombatXp);
        addStatRow(panel, "Woodcutting:", lblWcXp);
        addStatRow(panel, "Mining:", lblMiningXp);
        addStatRow(panel, "Fishing:", lblFishingXp);
        addStatRow(panel, "Firemaking:", lblFmXp);
        addSep(panel);

        addSectionTitle(panel, "Statistieken");
        addStatRow(panel, "Kills:", lblKills);
        addStatRow(panel, "Loot waarde:", lblLoot);
        addStatRow(panel, "Logs gekapt:", lblLogs);
        addStatRow(panel, "Erts gemijnd:", lblOres);
        addStatRow(panel, "Vis gevangen:", lblFish);

        panel.add(Box.createVerticalGlue());

        JScrollPane scroll = new JScrollPane(panel);
        scroll.setBackground(BG_DARK);
        scroll.getViewport().setBackground(BG_DARK);
        scroll.setBorder(null);
        return scroll;
    }

    // ===================== CENTERS TAB =====================

    private JScrollPane createCentersTab(java.util.function.Consumer<CentersSectionRefs> refSink) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(BG_DARK);
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));

        JLabel info = new JLabel("<html><b>Center locaties per skill</b><br>Rechtermuisklik in-game om toe te voegen.<br>"
                + "<span style='color:#a0a0b0;font-size:10px'>Of elk account alleen de volledige lijst hier volgt (zonder subset "
                + "uit <b>Bewerken</b>), stel dat in per account op de tab <b>Accounts</b> → <b>Bewerken</b>.</span></html>");
        info.setFont(FONT_LABEL);
        info.setForeground(TEXT_DIM);
        info.setAlignmentX(Component.LEFT_ALIGNMENT);
        info.setBorder(new EmptyBorder(0, 0, 8, 0));
        panel.add(info);

        JPanel defaultCentersRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        defaultCentersRow.setBackground(BG_DARK);
        defaultCentersRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        JButton btnRestoreDefaults = new JButton("↺ Restore default centers");
        btnRestoreDefaults.setFont(FONT_LABEL);
        btnRestoreDefaults.setBackground(new Color(50, 80, 120));
        btnRestoreDefaults.setForeground(Color.WHITE);
        btnRestoreDefaults.setFocusPainted(false);
        btnRestoreDefaults.addActionListener(e -> {
            setConfig("combatCenters", DEFAULT_COMBAT_CENTERS);
            setConfig("wcCenters", DEFAULT_WC_CENTERS);
            setConfig("miningCenters", DEFAULT_MINING_CENTERS);
            setConfig("fishingCenters", DEFAULT_FISHING_CENTERS);
            setConfig("impsCenters", DEFAULT_IMPS_CENTERS);
            JOptionPane.showMessageDialog(
                    CombatBotPanel.this,
                    "Default centers zijn teruggezet.\nSchakel naar de tab 📍 Centers om de lijst te zien (of open die tab opnieuw).",
                    "Centers hersteld",
                    JOptionPane.INFORMATION_MESSAGE
            );
        });
        defaultCentersRow.add(btnRestoreDefaults);
        panel.add(defaultCentersRow);
        panel.add(Box.createVerticalStrut(6));

        JPanel combatSec = addCentersSection(panel, "⚔ Combat", config.combatCenters(), "combatCenters");
        panel.add(Box.createVerticalStrut(6));
        JPanel wcSec = addCentersSection(panel, "🪓 Woodcutting", config.wcCenters(), "wcCenters");
        panel.add(Box.createVerticalStrut(6));
        JPanel mineSec = addCentersSection(panel, "⛏ Mining", config.miningCenters(), "miningCenters");
        panel.add(Box.createVerticalStrut(6));
        JPanel fishSec = addCentersSection(panel, "🐟 Fishing", config.fishingCenters(), "fishingCenters");
        panel.add(Box.createVerticalStrut(6));
        JPanel impsSec = addImpsCentersBlock(panel);
        panel.add(Box.createVerticalStrut(6));
        addGiantsSection(panel);
        panel.add(Box.createVerticalStrut(6));
        addBarbarianSection(panel);

        panel.add(Box.createVerticalGlue());

        if (refSink != null) {
            CentersSectionRefs r = new CentersSectionRefs();
            r.combat = combatSec;
            r.wc = wcSec;
            r.mining = mineSec;
            r.fishing = fishSec;
            r.imps = impsSec;
            refSink.accept(r);
        }

        JScrollPane scroll = new JScrollPane(panel);
        scroll.setBackground(BG_DARK);
        scroll.getViewport().setBackground(BG_DARK);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    /** Imps: rotatie-toggle + lijst hunting centers (zelfde als andere skills). @return panel met Imps center-rijen */
    private JPanel addImpsCentersBlock(JPanel parent) {
        JPanel head = createSection("🎯 Imps (Karamja)", false);
        addToggle(head, "Imps als skill kiezen (in rotatie / start skill)", config.impsMode(), v -> setConfig("impsMode", v));
        JLabel info = new JLabel("<html><i>Rechtermuisklik in-game → Imps center. ≥1 actief: jachtzone = center+radius. Anders: Hunting X/Y in Settings.</i></html>");
        info.setFont(FONT_LABEL);
        info.setForeground(TEXT_DIM);
        info.setAlignmentX(Component.LEFT_ALIGNMENT);
        info.setBorder(new EmptyBorder(4, 10, 8, 10));
        head.add(info);
        parent.add(head);
        parent.add(Box.createVerticalStrut(4));
        JPanel impsRows = addCentersSection(parent, "Imps hunting centers", config.impsCenters(), "impsCenters");
        return impsRows;
    }

    /** Sectie voor Giants mode: geen centers (vaste locatie Edgeville Dungeon), wel toggle. */
    private void addGiantsSection(JPanel parent) {
        JPanel section = createSection("🗡 Giants (Edgeville Dungeon)", false);
        addToggle(section, "Giants als skill kiezen (in rotatie / start skill)", config.giantsMode(), v -> setConfig("giantsMode", v));
        JLabel info = new JLabel("<html><i>Hill Giants — Edgeville Dungeon (brass key). Per account: Accounts → Bewerken (globale lijsten uit) → Giants combat + onderaan \"Giants — in rotatie\".</i></html>");
        info.setFont(FONT_LABEL);
        info.setForeground(TEXT_DIM);
        info.setAlignmentX(Component.LEFT_ALIGNMENT);
        info.setBorder(new EmptyBorder(4, 10, 8, 10));
        section.add(info);
        parent.add(section);
    }

    /** Barbarian heeft geen losse center-lijst; gebied wordt via longhall-hoeken geconfigureerd. */
    private void addBarbarianSection(JPanel parent) {
        JPanel section = createSection("🛡 Barbarian (Longhall)", false);
        addToggle(section, "Barbarian als skill kiezen (in rotatie / start skill)", config.barbarianMode(), v -> setConfig("barbarianMode", v));
        JLabel info = new JLabel("<html><i>Longhall gebied stel je in onder Skills → Barbarian (hoeken + overlay).</i></html>");
        info.setFont(FONT_LABEL);
        info.setForeground(TEXT_DIM);
        info.setAlignmentX(Component.LEFT_ALIGNMENT);
        info.setBorder(new EmptyBorder(4, 10, 8, 10));
        section.add(info);
        parent.add(section);
    }

    private String getCurrentCentersData(String configKey) {
        switch (configKey) {
            case "combatCenters": return config.combatCenters();
            case "wcCenters": return config.wcCenters();
            case "miningCenters": return config.miningCenters();
            case "fishingCenters": return config.fishingCenters();
            case "impsCenters": return config.impsCenters();
            default: return "";
        }
    }

    /** Bij mining altijd ontdubbelen vóór opslaan (zelfde regels als {@link CenterManager#dedupeMiningCentersBlob}). */
    private static String serializeCentersForPersistence(String configKey, List<CenterManager.Center> updated) {
        if ("miningCenters".equals(configKey)) {
            return CenterManager.serializeMiningCentersDeduped(updated);
        }
        return CenterManager.serialize(updated);
    }

    private JPanel addCentersSection(JPanel parent, String title, String centersData, String configKey) {
        JPanel section = createSection(title, false);
        populateCentersSectionRows(section, centersData, configKey);
        parent.add(section);
        return section;
    }

    /**
     * Vult de rijen onder de sectie-header opnieuw (header = eerste component).
     * Zo kan "✕" alleen deze sectie verversen i.p.v. het hele Centers-tab leeg te maken.
     */
    private void populateCentersSectionRows(JPanel section, String centersData, String configKey) {
        while (section.getComponentCount() > 1) {
            section.remove(section.getComponentCount() - 1);
        }

        List<CenterManager.Center> centers = CenterManager.parse(centersData);

        if (centers.isEmpty()) {
            JPanel row = new JPanel(new BorderLayout());
            row.setBackground(BG_SECTION);
            row.setBorder(new EmptyBorder(5, 10, 5, 10));
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            JLabel lbl = new JLabel("Geen centers — rechtermuisklik in-game");
            lbl.setFont(FONT_LABEL);
            lbl.setForeground(TEXT_DIM);
            row.add(lbl);
            section.add(row);
            return;
        }

        for (int i = 0; i < centers.size(); i++) {
            CenterManager.Center c = centers.get(i);
            final int idx = i;

            JPanel row = new JPanel(new BorderLayout(4, 0));
            row.setBackground(BG_SECTION);
            row.setBorder(new EmptyBorder(3, 10, 3, 10));
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
            row.setAlignmentX(Component.LEFT_ALIGNMENT);

            JCheckBox cb = new JCheckBox();
            cb.setSelected(c.active);
            cb.setBackground(BG_SECTION);
            cb.setForeground(GREEN);

            String label = (c.name != null && !c.name.isEmpty()) ? c.name :
                    "(" + c.point.getX() + "," + c.point.getY() + ")";
            JLabel nameLbl = new JLabel(label + "  r=" + c.radius);
            nameLbl.setFont(FONT_LABEL);
            nameLbl.setForeground(c.active ? TEXT : TEXT_DIM);

            JButton editBtn = new JButton("✏");
            editBtn.setFont(new Font("Arial", Font.PLAIN, 10));
            editBtn.setPreferredSize(new Dimension(28, 22));
            editBtn.setBackground(new Color(50, 55, 70));
            editBtn.setForeground(GOLD);
            editBtn.setFocusPainted(false);
            editBtn.setBorder(new LineBorder(new Color(70, 75, 90), 1));
            editBtn.setToolTipText("Naam bewerken");
            editBtn.addActionListener(e -> {
                String currentName = (c.name != null && !c.name.isEmpty()) ? c.name : "";
                String newName = (String) JOptionPane.showInputDialog(
                        SwingUtilities.getWindowAncestor(editBtn),
                        "Nieuwe naam voor deze locatie:",
                        "Naam bewerken",
                        JOptionPane.PLAIN_MESSAGE,
                        null, null, currentName
                );
                if (newName != null) {
                    String current = getCurrentCentersData(configKey);
                    List<CenterManager.Center> updated = CenterManager.parse(current);
                    if (idx < updated.size()) {
                        updated.get(idx).name = newName.trim();
                        String serialized = serializeCentersForPersistence(configKey, updated);
                        configManager.setConfiguration("combatbot", configKey, serialized);
                        String dispName = newName.trim().isEmpty()
                                ? "(" + updated.get(idx).point.getX() + "," + updated.get(idx).point.getY() + ")"
                                : newName.trim();
                        nameLbl.setText(dispName + "  r=" + updated.get(idx).radius);
                    }
                }
            });

            JButton removeBtn = new JButton("✕");
            removeBtn.setFont(new Font("Arial", Font.BOLD, 10));
            removeBtn.setPreferredSize(new Dimension(28, 22));
            removeBtn.setBackground(new Color(70, 40, 40));
            removeBtn.setForeground(new Color(255, 100, 100));
            removeBtn.setFocusPainted(false);
            removeBtn.setBorder(new LineBorder(new Color(90, 50, 50), 1));
            removeBtn.setToolTipText("Locatie verwijderen");
            removeBtn.addActionListener(e -> {
                String current = getCurrentCentersData(configKey);
                List<CenterManager.Center> updated = CenterManager.parse(current);
                if (idx < updated.size()) {
                    updated.remove(idx);
                    String serialized = serializeCentersForPersistence(configKey, updated);
                    configManager.setConfiguration("combatbot", configKey, serialized);
                    SwingUtilities.invokeLater(() -> {
                        populateCentersSectionRows(section, serialized, configKey);
                        section.revalidate();
                        section.repaint();
                    });
                }
            });

            cb.addActionListener(e -> {
                nameLbl.setForeground(cb.isSelected() ? TEXT : TEXT_DIM);
                String current = getCurrentCentersData(configKey);
                List<CenterManager.Center> updated = CenterManager.parse(current);
                if (idx < updated.size()) {
                    updated.get(idx).active = cb.isSelected();
                    configManager.setConfiguration("combatbot", configKey,
                            serializeCentersForPersistence(configKey, updated));
                }
            });

            JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
            btnPanel.setOpaque(false);
            btnPanel.add(editBtn);
            btnPanel.add(removeBtn);

            row.add(cb, BorderLayout.WEST);
            row.add(nameLbl, BorderLayout.CENTER);
            row.add(btnPanel, BorderLayout.EAST);
            section.add(row);
        }
    }

    /** Herlaadt skill-secties vanuit actuele config (in-game centers, account-wissel zonder tab te sluiten). */
    private void refreshCentersTabRowsFromConfig() {
        refreshCentersSections(embeddedCentersRefs);
        refreshCentersSections(detachedCentersRefs);
    }

    private void refreshCentersSections(CentersSectionRefs r) {
        if (r == null) {
            return;
        }
        try {
            if (r.combat != null && r.combat.isDisplayable()) {
                populateCentersSectionRows(r.combat, config.combatCenters(), "combatCenters");
                r.combat.revalidate();
                r.combat.repaint();
            }
            if (r.wc != null && r.wc.isDisplayable()) {
                populateCentersSectionRows(r.wc, config.wcCenters(), "wcCenters");
                r.wc.revalidate();
                r.wc.repaint();
            }
            if (r.mining != null && r.mining.isDisplayable()) {
                populateCentersSectionRows(r.mining, config.miningCenters(), "miningCenters");
                r.mining.revalidate();
                r.mining.repaint();
            }
            if (r.fishing != null && r.fishing.isDisplayable()) {
                populateCentersSectionRows(r.fishing, config.fishingCenters(), "fishingCenters");
                r.fishing.revalidate();
                r.fishing.repaint();
            }
            if (r.imps != null && r.imps.isDisplayable()) {
                populateCentersSectionRows(r.imps, config.impsCenters(), "impsCenters");
                r.imps.revalidate();
                r.imps.repaint();
            }
        } catch (Throwable ignored) {
            // verdwenen panel (detach gesloten) of config race
        }
    }

    // ===================== CONFIG HELPERS =====================

    private void setConfig(String key, Object value) {
        configManager.setConfiguration("combatbot", key, String.valueOf(value));
        syncMirroredToggles(key, value);
    }

    private static void styleSmallButton(JButton b, Color bg) {
        b.setFont(new Font("Arial", Font.PLAIN, 11));
        b.setBackground(bg);
        b.setForeground(Color.WHITE);
        b.setFocusPainted(false);
    }

    /** RuneLite / embedded Swing: parent voor modale dialoog (getWindowAncestor kan null zijn). */
    private static Window resolveSwingOwner(Component c) {
        Window w = SwingUtilities.getWindowAncestor(c);
        if (w != null && w.isDisplayable()) {
            return w;
        }
        Window fw = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow();
        if (fw != null && fw.isDisplayable()) {
            return fw;
        }
        for (Frame fr : Frame.getFrames()) {
            if (fr != null && fr.isDisplayable() && fr.isVisible()) {
                return fr;
            }
        }
        return w;
    }

    private static String escapeForHtmlLabel(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
    }

    private void exportSettingsToFile(Component parent) {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Combat Bot instellingen exporteren");
        fc.setSelectedFile(new File("combatbot-settings.json"));
        fc.setFileFilter(new FileNameExtensionFilter("JSON (*.json)", "json"));
        Window w = parent != null ? SwingUtilities.getWindowAncestor(parent) : null;
        if (fc.showSaveDialog(w) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File f = fc.getSelectedFile();
        if (f != null && !f.getName().toLowerCase().endsWith(".json")) {
            f = new File(f.getParentFile(), f.getName() + ".json");
        }
        try {
            String json = CombatBotSettingsTransfer.exportToJson(config);
            Files.write(f.toPath(), json.getBytes(StandardCharsets.UTF_8));
            JOptionPane.showMessageDialog(w,
                    "Opgeslagen: " + f.getAbsolutePath(),
                    "Export gelukt",
                    JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(w,
                    "Kon niet opslaan: " + ex.getMessage(),
                    "Export mislukt",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void importSettingsFromFile(Component parent) {
        Window w = parent != null ? SwingUtilities.getWindowAncestor(parent) : null;
        int ok = JOptionPane.showConfirmDialog(w,
                "Alle Combat Bot-instellingen uit het bestand worden toegepast.\n"
                        + "Account/credentials op deze PC worden niet uit het bestand gehaald.\n"
                        + "Eenmalige acties (switch now, …) blijven uit na import.\n\n"
                        + "Doorgaan?",
                "Import bevestigen",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) {
            return;
        }
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Combat Bot instellingen importeren");
        fc.setFileFilter(new FileNameExtensionFilter("JSON (*.json)", "json"));
        if (fc.showOpenDialog(w) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File f = fc.getSelectedFile();
        try {
            String json = Files.readString(f.toPath(), StandardCharsets.UTF_8);
            int n = CombatBotSettingsTransfer.importFromJson(json, configManager);
            JOptionPane.showMessageDialog(w,
                    n + " instellingen toegepast uit:\n" + f.getAbsolutePath()
                            + "\n\nHerstart de plugin of client als waarden in het paneel niet direct verversen.",
                    "Import gelukt",
                    JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(w,
                    "Kon niet importeren: " + ex.getMessage(),
                    "Import mislukt",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private JPanel createSection(String title, boolean accent) {
        JPanel section = new JPanel();
        section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
        section.setBackground(BG_SECTION);
        section.setBorder(new LineBorder(new Color(60, 65, 80), 1, true));
        section.setAlignmentX(Component.LEFT_ALIGNMENT);

        JButton header = new JButton("▶ " + title);
        header.setFont(FONT_SECTION);
        header.setForeground(accent ? GREEN : GOLD);
        header.setBackground(accent ? new Color(74, 222, 128, 30) : BG_HEADER);
        header.setBorder(new EmptyBorder(6, 10, 6, 10));
        header.setFocusPainted(false);
        header.setBorderPainted(false);
        header.setOpaque(true);
        header.setContentAreaFilled(true);
        header.setHorizontalAlignment(SwingConstants.LEFT);
        header.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        header.addActionListener(e -> {
            boolean anyVisible = false;
            for (int i = 1; i < section.getComponentCount(); i++) {
                if (section.getComponent(i).isVisible()) {
                    anyVisible = true;
                    break;
                }
            }
            boolean show = !anyVisible;
            for (int i = 1; i < section.getComponentCount(); i++) {
                section.getComponent(i).setVisible(show);
            }
            header.setText((show ? "▼ " : "▶ ") + title);
            section.revalidate();
            section.repaint();
        });

        section.add(header);
        section.putClientProperty("collapsedInitialized", Boolean.FALSE);
        section.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) == 0 || !section.isShowing()) return;
            if (Boolean.TRUE.equals(section.getClientProperty("collapsedInitialized"))) return;
            for (int i = 1; i < section.getComponentCount(); i++) {
                section.getComponent(i).setVisible(false);
            }
            header.setText("▶ " + title);
            section.putClientProperty("collapsedInitialized", Boolean.TRUE);
            section.revalidate();
            section.repaint();
        });

        return section;
    }

    /**
     * Inklapbare sectie (accordion): klik op de kop om inhoud te tonen/verbergen.
     *
     * @param fillContent voeg rijen toe aan het meegegeven panel (zelfde helpers als {@link #createSection}).
     */
    private JPanel createCollapsibleSection(String title, boolean accent, boolean expandedInitially,
                                            Consumer<JPanel> fillContent) {
        JPanel outer = new JPanel();
        outer.setLayout(new BoxLayout(outer, BoxLayout.Y_AXIS));
        outer.setBackground(BG_DARK);
        outer.setAlignmentX(Component.LEFT_ALIGNMENT);
        outer.setBorder(new LineBorder(new Color(60, 65, 80), 1, true));

        boolean initialExpanded = false;
        JButton toggle = new JButton((initialExpanded ? "▼ " : "▶ ") + title);
        styleCollapsibleHeader(toggle, accent);
        toggle.setAlignmentX(Component.LEFT_ALIGNMENT);
        toggle.setHorizontalAlignment(SwingConstants.LEFT);
        toggle.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(BG_SECTION);
        content.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.setVisible(initialExpanded);

        fillContent.accept(content);

        toggle.addActionListener(e -> {
            boolean open = !content.isVisible();
            content.setVisible(open);
            toggle.setText((open ? "▼ " : "▶ ") + title);
            outer.revalidate();
            outer.repaint();
            Container up = outer.getParent();
            while (up != null) {
                up.revalidate();
                up.repaint();
                up = up.getParent();
            }
        });

        outer.add(toggle);
        outer.add(content);
        return outer;
    }

    private void styleCollapsibleHeader(JButton btn, boolean accent) {
        btn.setFont(FONT_SECTION);
        btn.setForeground(accent ? GREEN : GOLD);
        btn.setBackground(accent ? new Color(74, 222, 128, 45) : BG_HEADER);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setOpaque(true);
        btn.setContentAreaFilled(true);
        btn.setBorder(new EmptyBorder(8, 10, 8, 10));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    private JCheckBox addToggle(JPanel section, String label, boolean initial, Consumer<Boolean> onChange) {
        JPanel row = new JPanel(new BorderLayout());
        row.setBackground(BG_SECTION);
        row.setBorder(new EmptyBorder(5, 10, 5, 10));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel lbl = new JLabel(label);
        lbl.setFont(FONT_LABEL);
        lbl.setForeground(TEXT);
        row.add(lbl, BorderLayout.WEST);

        JCheckBox cb = new JCheckBox();
        cb.setSelected(initial);
        cb.setBackground(BG_SECTION);
        cb.setForeground(GREEN);
        cb.addActionListener(e -> onChange.accept(cb.isSelected()));
        row.add(cb, BorderLayout.EAST);

        section.add(row);
        return cb;
    }

    private void addOverlayToggle(JPanel section, String label, boolean initial, String configKey) {
        JCheckBox cb = addToggle(section, label, initial, v -> setConfig(configKey, v));
        mirroredTogglesByConfigKey.computeIfAbsent(configKey, k -> new ArrayList<>()).add(cb);
    }

    private void syncMirroredToggles(String key, Object value) {
        List<JCheckBox> mirrors = mirroredTogglesByConfigKey.get(key);
        if (mirrors == null || mirrors.isEmpty() || syncingMirroredToggles) {
            return;
        }

        final String raw = String.valueOf(value);
        if (!"true".equalsIgnoreCase(raw) && !"false".equalsIgnoreCase(raw)) {
            return;
        }
        final boolean selected = Boolean.parseBoolean(raw);

        syncingMirroredToggles = true;
        try {
            for (JCheckBox cb : mirrors) {
                if (cb != null && cb.isSelected() != selected) {
                    cb.setSelected(selected);
                }
            }
        } finally {
            syncingMirroredToggles = false;
        }
    }

    /**
     * Plain action knop. Voor "fire-and-forget" config-triggers (bv. tile preset save/load) waar de
     * plugin zelf de config-flag terug op false zet. We zetten hier alleen true/start de Runnable.
     */
    private void addActionButton(JPanel section, String label, Runnable onClick) {
        JPanel row = new JPanel(new BorderLayout());
        row.setBackground(BG_SECTION);
        row.setBorder(new EmptyBorder(5, 10, 5, 10));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        JButton btn = new JButton(label);
        btn.setFont(FONT_LABEL);
        btn.setForeground(Color.WHITE);
        btn.setBackground(new Color(60, 65, 80));
        btn.setFocusPainted(false);
        btn.addActionListener(e -> { if (onClick != null) onClick.run(); });
        row.add(btn, BorderLayout.CENTER);
        section.add(row);
    }

    /** Toggle die na activatie direct weer uitvinkt (voor Switch Now / Sell now). */
    private void addTriggerToggle(JPanel section, String label, String configKey, Runnable onTrigger) {
        JPanel row = new JPanel(new BorderLayout());
        row.setBackground(BG_SECTION);
        row.setBorder(new EmptyBorder(5, 10, 5, 10));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel lbl = new JLabel(label);
        lbl.setFont(FONT_LABEL);
        lbl.setForeground(TEXT);
        row.add(lbl, BorderLayout.WEST);
        JCheckBox cb = new JCheckBox();
        cb.setSelected(false);
        cb.setBackground(BG_SECTION);
        cb.setForeground(GREEN);
        cb.addActionListener(e -> {
            if (cb.isSelected()) {
                if (onTrigger != null) onTrigger.run();
                setConfig(configKey, false);
                cb.setSelected(false);
            }
        });
        row.add(cb, BorderLayout.EAST);
        section.add(row);
    }

    private void addTextField(JPanel section, String label, String initial, Consumer<String> onChange) {
        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.setBackground(BG_SECTION);
        row.setBorder(new EmptyBorder(5, 10, 5, 10));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel lbl = new JLabel(label);
        lbl.setFont(FONT_LABEL);
        lbl.setForeground(TEXT);
        lbl.setPreferredSize(new Dimension(120, 24));
        row.add(lbl, BorderLayout.WEST);

        JTextField field = new JTextField(initial != null ? initial : "");
        field.setFont(FONT_LABEL);
        field.setBackground(new Color(35, 37, 48));
        field.setForeground(Color.WHITE);
        field.setCaretColor(Color.WHITE);
        field.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(new Color(60, 65, 80), 1),
                new EmptyBorder(2, 4, 2, 4)));
        // Debounced save: sla op na 500ms inactiviteit (zodat elke wijziging snel wordt opgeslagen)
        final Timer[] debounceTimer = {null};
        field.getDocument().addDocumentListener(new DocumentListener() {
            private void scheduleUpdate() {
                if (debounceTimer[0] != null) debounceTimer[0].stop();
                debounceTimer[0] = new Timer(500, ev -> onChange.accept(field.getText()));
                debounceTimer[0].setRepeats(false);
                debounceTimer[0].start();
            }
            @Override public void insertUpdate(DocumentEvent e) { scheduleUpdate(); }
            @Override public void removeUpdate(DocumentEvent e) { scheduleUpdate(); }
            @Override public void changedUpdate(DocumentEvent e) { scheduleUpdate(); }
        });
        // Behoud focusLost + Enter als fallback
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                if (debounceTimer[0] != null) debounceTimer[0].stop();
                onChange.accept(field.getText());
            }
        });
        field.addActionListener(e -> onChange.accept(field.getText()));
        row.add(field, BorderLayout.CENTER);

        section.add(row);
    }

    private void addSlider(JPanel section, String label, int initial, int min, int max, Consumer<Integer> onChange) {
        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.setBackground(BG_SECTION);
        row.setBorder(new EmptyBorder(5, 10, 5, 10));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel lbl = new JLabel(label);
        lbl.setFont(FONT_LABEL);
        lbl.setForeground(TEXT);
        lbl.setPreferredSize(new Dimension(120, 24));
        row.add(lbl, BorderLayout.WEST);

        JLabel valueLabel = new JLabel(String.valueOf(initial));
        valueLabel.setFont(FONT_VALUE);
        valueLabel.setForeground(GREEN);
        valueLabel.setPreferredSize(new Dimension(50, 24));
        valueLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        row.add(valueLabel, BorderLayout.EAST);

        JSlider slider = new JSlider(min, max, Math.min(Math.max(initial, min), max));
        slider.setBackground(BG_SECTION);
        slider.setForeground(GREEN);
        slider.setFocusable(false);
        slider.addChangeListener(e -> {
            int val = slider.getValue();
            valueLabel.setText(String.valueOf(val));
            if (!slider.getValueIsAdjusting()) {
                onChange.accept(val);
            }
        });
        row.add(slider, BorderLayout.CENTER);

        section.add(row);
    }

    private <E extends Enum<E>> void addComboBox(JPanel section, String label, E[] values, E initial, Consumer<E> onChange) {
        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.setBackground(BG_SECTION);
        row.setBorder(new EmptyBorder(5, 10, 5, 10));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel lbl = new JLabel(label);
        lbl.setFont(FONT_LABEL);
        lbl.setForeground(TEXT);
        lbl.setPreferredSize(new Dimension(120, 24));
        row.add(lbl, BorderLayout.WEST);

        JComboBox<E> combo = new JComboBox<>(values);
        combo.setSelectedItem(initial);
        combo.setFont(FONT_LABEL);
        combo.setBackground(new Color(35, 37, 48));
        combo.setForeground(Color.WHITE);
        combo.addActionListener(e -> {
            @SuppressWarnings("unchecked")
            E selected = (E) combo.getSelectedItem();
            if (selected != null) onChange.accept(selected);
        });
        row.add(combo, BorderLayout.CENTER);

        section.add(row);
    }

    // ===================== ACCOUNTS MANAGER TAB =====================

    /** Kolom met ℹ — opent live hiscore-grid (niet meer enkele klik op de rij). */
    private static final int ACCOUNTS_COL_NEXT = 1;
    private static final int ACCOUNTS_COL_INFO = 2;
    private static final int ACCOUNTS_COL_USER = 3;
    private static final int ACCOUNTS_COL_GP = 4;
    private static final int ACCOUNTS_COL_CMB = 5;
    private static final int ACCOUNTS_COL_ASD = 6;
    private static final int ACCOUNTS_COL_WC = 7;
    private static final int ACCOUNTS_COL_MIN = 8;
    private static final int ACCOUNTS_COL_FISH = 9;
    private static final int ACCOUNTS_COL_PRAY = 10;
    private static final int ACCOUNTS_COL_MAGIC = 11;
    private static final int ACCOUNTS_COL_QUEST = 12;
    private static final int ACCOUNTS_COL_SKILL = 13;
    private static final int ACCOUNTS_COL_TIMER = 14;

    private static final Map<String, ImageIcon> ACCOUNT_HEADER_ICON_CACHE = new HashMap<>();

    private static String formatHiscoreSyncAgo(long updatedEpochMs) {
        long sec = Math.max(0, (System.currentTimeMillis() - updatedEpochMs) / 1000L);
        if (sec < 60) {
            return sec + " sec geleden";
        }
        if (sec < 3600) {
            return (sec / 60) + " min geleden";
        }
        if (sec < 86400) {
            return (sec / 3600) + " u geleden";
        }
        return (sec / 86400) + " d geleden";
    }

    private static String formatAccountProgressTimer(AccountLocalProgressStore.Entry e) {
        if (e == null) {
            return "—";
        }
        long elapsed = Math.max(0L, e.elapsedSec);
        long total = Math.max(0L, e.totalSwitchSec);
        return formatMmSs(elapsed) + "/" + formatMmSs(total);
    }

    private static String formatMmSs(long secTotal) {
        long s = Math.max(0L, secTotal);
        long mm = s / 60L;
        long ss = s % 60L;
        return String.format("%02d:%02d", mm, ss);
    }

    private String resolveNextRotationAccountDisplayName(List<ManagedJagexAccountsStore.ManagedJagexAccountRow> rows) {
        if (rows == null || rows.isEmpty() || paint == null || !paint.isAccountSwitchEnabled()) {
            return null;
        }
        String current = paint.getCurrentAccountName();
        if (current == null || current.trim().isEmpty() || "Geen".equalsIgnoreCase(current.trim())) {
            return null;
        }
        List<String> enabled = new ArrayList<>();
        for (ManagedJagexAccountsStore.ManagedJagexAccountRow r : rows) {
            if (r == null || r.isEmpty() || !r.rotationEnabled || r.displayName == null || r.displayName.trim().isEmpty()) {
                continue;
            }
            enabled.add(r.displayName.trim());
        }
        if (enabled.size() < 2) {
            return null;
        }
        int idx = -1;
        for (int i = 0; i < enabled.size(); i++) {
            if (enabled.get(i).equalsIgnoreCase(current.trim())) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            return enabled.get(0);
        }
        return enabled.get((idx + 1) % enabled.size());
    }

    private static String accountHeaderIconUrl(String key) {
        switch (key) {
            case "CMB":
                return "https://oldschool.runescape.wiki/images/Combat_icon.png";
            case "ASD":
                return null;
            case "WC":
                return "https://oldschool.runescape.wiki/images/Woodcutting_icon.png";
            case "MIN":
                return "https://oldschool.runescape.wiki/images/Mining_icon.png";
            case "FISH":
                return "https://oldschool.runescape.wiki/images/Fishing_icon.png";
            case "PRAY":
                return "https://oldschool.runescape.wiki/images/Prayer_icon.png";
            case "MAGIC":
                return "https://oldschool.runescape.wiki/images/Magic_icon.png";
            case "QUEST":
                return "https://oldschool.runescape.wiki/images/Quest_point_icon.png";
            default:
                return null;
        }
    }

    private static ImageIcon loadAccountHeaderIcon(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        if ("ASD".equals(key)) {
            return loadCombinedAsdHeaderIcon();
        }
        ImageIcon cached = ACCOUNT_HEADER_ICON_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        String url = accountHeaderIconUrl(key);
        if (url == null) {
            return null;
        }
        try {
            ImageIcon raw = new ImageIcon(new URL(url));
            Image scaled = raw.getImage().getScaledInstance(14, 14, Image.SCALE_SMOOTH);
            ImageIcon icon = new ImageIcon(scaled);
            ACCOUNT_HEADER_ICON_CACHE.put(key, icon);
            return icon;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static ImageIcon loadCombinedAsdHeaderIcon() {
        final String key = "ASD_COMBINED";
        ImageIcon cached = ACCOUNT_HEADER_ICON_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            ImageIcon attack = new ImageIcon(new URL("https://oldschool.runescape.wiki/images/Attack_icon.png"));
            ImageIcon strength = new ImageIcon(new URL("https://oldschool.runescape.wiki/images/Strength_icon.png"));
            ImageIcon defence = new ImageIcon(new URL("https://oldschool.runescape.wiki/images/Defence_icon.png"));

            int iconW = 12;
            int iconH = 12;
            int gap = 1;
            int totalW = iconW * 3 + gap * 2;
            java.awt.image.BufferedImage combined = new java.awt.image.BufferedImage(
                    totalW, iconH, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = combined.createGraphics();
            try {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2.drawImage(attack.getImage(), 0, 0, iconW, iconH, null);
                g2.drawImage(strength.getImage(), iconW + gap, 0, iconW, iconH, null);
                g2.drawImage(defence.getImage(), (iconW + gap) * 2, 0, iconW, iconH, null);
            } finally {
                g2.dispose();
            }
            ImageIcon icon = new ImageIcon(combined);
            ACCOUNT_HEADER_ICON_CACHE.put(key, icon);
            return icon;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void applySuspectAccountRowBackground(JTable t, JLabel lab, int row, boolean isSelected) {
        if (row < 0 || lab == null) {
            return;
        }
        Object u = t.getValueAt(row, ACCOUNTS_COL_USER);
        String name = u != null ? u.toString() : "";
        ManagedJagexAccountsStore.AccountStatSnapshot s = ManagedJagexAccountsStore.snapshotForRow(
                ManagedJagexAccountsStore.parseSnapshots(config.accountStatSnapshotsBlob()), name);
        boolean suspect = s != null && s.hiscoreSuspectBanned;
        if (suspect) {
            lab.setBackground(isSelected ? new Color(110, 45, 45) : new Color(85, 30, 30));
            lab.setForeground(isSelected ? Color.WHITE : new Color(255, 200, 200));
        } else {
            lab.setBackground(isSelected ? t.getSelectionBackground() : t.getBackground());
            lab.setForeground(isSelected ? t.getSelectionForeground() : t.getForeground());
        }
    }

    /** Map visuele tabelrij → datarij (alleen niet-lege accounts; idem filter als {@link #createAccountsManagerTab} refill). */
    private ManagedJagexAccountsStore.ManagedJagexAccountRow resolveAccountRowAtTableIndex(
            List<ManagedJagexAccountsStore.ManagedJagexAccountRow> accountRows, int tableRow) {
        if (tableRow < 0) {
            return null;
        }
        Map<String, ManagedJagexAccountsStore.AccountStatSnapshot> snaps =
                ManagedJagexAccountsStore.parseSnapshots(config.accountStatSnapshotsBlob());
        boolean hideBanned = config.accountsTableHideSuspectBanned();
        int idx = 0;
        for (ManagedJagexAccountsStore.ManagedJagexAccountRow r : accountRows) {
            if (r.isEmpty()) {
                continue;
            }
            ManagedJagexAccountsStore.AccountStatSnapshot s =
                    ManagedJagexAccountsStore.snapshotForRow(snaps, r.displayName);
            if (hideBanned && s != null && s.hiscoreSuspectBanned) {
                continue;
            }
            if (idx == tableRow) {
                return r;
            }
            idx++;
        }
        return null;
    }

    private int resolveTableIndexForAccount(
            List<ManagedJagexAccountsStore.ManagedJagexAccountRow> accountRows, String displayName) {
        if (displayName == null) {
            return -1;
        }
        Map<String, ManagedJagexAccountsStore.AccountStatSnapshot> snaps =
                ManagedJagexAccountsStore.parseSnapshots(config.accountStatSnapshotsBlob());
        boolean hideBanned = config.accountsTableHideSuspectBanned();
        int idx = 0;
        for (ManagedJagexAccountsStore.ManagedJagexAccountRow r : accountRows) {
            if (r.isEmpty()) {
                continue;
            }
            ManagedJagexAccountsStore.AccountStatSnapshot s =
                    ManagedJagexAccountsStore.snapshotForRow(snaps, r.displayName);
            if (hideBanned && s != null && s.hiscoreSuspectBanned) {
                continue;
            }
            if (displayName.equalsIgnoreCase(r.displayName)) {
                return idx;
            }
            idx++;
        }
        return -1;
    }

    /**
     * Compacte besturing voor de bot (Accounts-tab). Dubbelklik op een account in de tabel = login op welkomstscherm (ongewijzigd).
     */
    private JPanel createAccountsBotControlBar() {
        JPanel wrap = new JPanel(new BorderLayout(12, 0));
        wrap.setBackground(new Color(34, 36, 48));
        wrap.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(new Color(55, 60, 78), 1, true),
                new EmptyBorder(10, 12, 10, 12)));
        wrap.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel headline = new JLabel("Besturing");
        headline.setFont(FONT_SECTION);
        headline.setForeground(GOLD);

        JButton btnStart = new JButton("▶ Start");
        JButton btnPause = new JButton("⏸ Pauze");
        JButton btnStop = new JButton("■ Stop");
        JButton btnNextSkill = new JButton("⏭ Next skill");
        JButton btnNextAccount = new JButton("⚡ Next account");
        JButton btnReset = new JButton("↺ Reset");
        JButton btnEmergency = new JButton("⛔ Noodstop");

        Font btnFont = new Font("SansSerif", Font.BOLD, 11);
        Dimension btnSize = new Dimension(84, 30);
        for (JButton b : new JButton[] { btnStart, btnPause, btnStop, btnNextSkill, btnNextAccount, btnReset, btnEmergency }) {
            b.setFont(btnFont);
            b.setForeground(Color.WHITE);
            b.setFocusPainted(false);
            b.setPreferredSize(btnSize);
        }
        btnNextSkill.setPreferredSize(new Dimension(102, 30));
        btnNextAccount.setPreferredSize(new Dimension(116, 30));
        btnEmergency.setPreferredSize(new Dimension(104, 30));
        btnStart.setBackground(new Color(32, 120, 62));
        btnStart.setToolTipText("Bot inschakelen / hervatten vanaf huidige state");
        btnPause.setBackground(new Color(130, 95, 28));
        btnPause.setToolTipText("Bot tijdelijk uitzetten — gebruik Start om verder te gaan");
        btnStop.setBackground(new Color(130, 42, 48));
        btnStop.setToolTipText("Bot uit + geplande Switch now / Sell now annuleren");
        btnNextSkill.setBackground(new Color(45, 90, 145));
        btnNextSkill.setToolTipText("Direct naar volgende skill in de actieve rotatie");
        btnNextAccount.setBackground(new Color(85, 60, 120));
        btnNextAccount.setToolTipText("Direct switchen naar volgende account in rotatie");
        btnReset.setBackground(new Color(68, 62, 110));
        btnReset.setToolTipText("<html>Runtime state opschonen zonder centers/startskill opnieuw te laden:<br>"
                + "account-wissel/re-log afbreken, handler-stuck-state resetten, huidige skill behouden.</html>");
        btnEmergency.setBackground(new Color(160, 20, 28));
        btnEmergency.setToolTipText("<html>Dodemansknop: bot direct uit, alle pending acties/switch/relog/handlers afbreken.<br>"
                + "Gebruik als Stop/Pauze niet snel genoeg stilvalt.</html>");

        JLabel status = new JLabel("●");
        status.setFont(FONT_LABEL);
        status.setHorizontalAlignment(SwingConstants.RIGHT);

        Runnable refresh = () -> {
            if (!btnStart.isDisplayable()) {
                return;
            }
            boolean on = config.botEnabled();
            btnStart.setEnabled(!on);
            btnPause.setEnabled(on);
            btnStop.setEnabled(on);
            btnNextSkill.setEnabled(on);
            btnNextAccount.setEnabled(on);
            status.setText(on ? "●  Aan" : "●  Uit");
            status.setForeground(on ? GREEN : new Color(130, 130, 145));
        };

        btnStart.addActionListener(e -> {
            setConfig("botEnabled", true);
            notifyBotStartRequested();
            refresh.run();
        });
        btnPause.addActionListener(e -> {
            setConfig("botEnabled", false);
            refresh.run();
        });
        btnStop.addActionListener(e -> {
            setConfig("botEnabled", false);
            setConfig("switchNow", false);
            setConfig("impsSellNow", false);
            refresh.run();
        });
        btnNextSkill.addActionListener(e -> setConfig("switchNow", true));
        btnNextAccount.addActionListener(e -> {
            onNextAccountRequested.run();
        });
        btnReset.addActionListener(e -> {
            onResetAllBotState.run();
            refresh.run();
        });
        btnEmergency.addActionListener(e -> {
            onEmergencyStopAll.run();
            refresh.run();
        });

        accountsBotBarHandles.add(new BotBarHandle(btnStart, refresh));
        refresh.run();

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        left.setOpaque(false);
        left.add(headline);
        left.add(Box.createHorizontalStrut(4));
        left.add(btnStart);
        left.add(btnPause);
        left.add(btnStop);
        left.add(btnNextSkill);
        left.add(btnNextAccount);
        left.add(btnReset);
        left.add(btnEmergency);

        wrap.add(left, BorderLayout.CENTER);
        wrap.add(status, BorderLayout.EAST);
        return wrap;
    }

    /** Storm account-wissel + credentials-pad; voorheen onder Settings — nu alleen hier. */
    private JPanel createAccountsTabRotationAndCredentialsSection() {
        JPanel section = createSection("⚙ Account rotatie & login-bestand", false);
        JLabel hint = new JLabel("<html><div style='color:#a0a0b0;font-size:10px;width:420px'>"
                + "<b>Accountlijst</b> komt uit de tabel hieronder (kolom <b>Rotatie</b> + rijen in <b>Bewerken</b>). "
                + "Hier stel je in of Storm automatisch wisselt en waar <code>credentials.properties</code> staat voor dubbelklik-login. "
                + "Bij account-wissel wordt standaard een <b>willekeurige F2P-wereld</b> gekozen; een vaste wereld zet je per account in <b>Bewerken</b>."
                + "</div></html>");
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        hint.setBorder(new EmptyBorder(0, 0, 8, 0));
        section.add(hint);

        addToggle(section, "Rotatie tussen accounts inschakelen", config.accountSwitchEnabled(),
                v -> setConfig("accountSwitchEnabled", v));
        addSlider(section, "Min. minuten per account", config.accountMinMinutes(), 1, 240,
                v -> setConfig("accountMinMinutes", v));
        addSlider(section, "Max. minuten per account", config.accountMaxMinutes(), 1, 360,
                v -> setConfig("accountMaxMinutes", v));
        addTextField(section, "Profiel credentials.properties", config.loginScreenCredentialsPath(),
                v -> setConfig("loginScreenCredentialsPath", v));

        JPanel accBtnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        accBtnRow.setBackground(BG_SECTION);
        accBtnRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        JButton btnPickCreds = new JButton("📁 Kies credentials.properties");
        styleSmallButton(btnPickCreds, new Color(50, 80, 120));
        btnPickCreds.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Kies credentials.properties");
            chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
            int res = chooser.showOpenDialog(CombatBotPanel.this);
            if (res == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null) {
                String path = chooser.getSelectedFile().getAbsolutePath().replace("\\", "/");
                setConfig("loginScreenCredentialsPath", path);
            }
        });
        JButton btnOpenRuneLite = new JButton("📂 Open RuneLite map");
        styleSmallButton(btnOpenRuneLite, new Color(60, 60, 80));
        btnOpenRuneLite.addActionListener(e -> {
            try {
                String home = System.getProperty("user.home");
                File dir;
                String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
                if (os.contains("win")) {
                    dir = new File(home, "AppData/Roaming/RuneLite");
                } else if (os.contains("mac")) {
                    dir = new File(home, "Library/Application Support/RuneLite");
                } else {
                    dir = new File(home, ".runelite");
                }
                if (!dir.exists()) {
                    dir = new File(home);
                }
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    Desktop.getDesktop().open(dir);
                }
            } catch (Exception ignored) {
            }
        });
        JButton btnSwitchNow = new JButton("⚡ Switch / next account");
        styleSmallButton(btnSwitchNow, new Color(85, 60, 120));
        btnSwitchNow.addActionListener(e -> {
            onNextAccountRequested.run();
        });
        accBtnRow.add(btnPickCreds);
        accBtnRow.add(btnOpenRuneLite);
        accBtnRow.add(btnSwitchNow);
        section.add(accBtnRow);
        return section;
    }

    private JPanel createAccountsStartSkillSection() {
        JPanel section = createSection("🎯 Start skill & rotatie", false);
        addToggle(section, "Grote loopstappen 15-20 (globaal)", config.impsForceLargeSteps(), v -> setConfig("impsForceLargeSteps", v));
        addToggle(section, "Reset per-account timers bij stop", config.resetAccountTimersOnStop(), v -> setConfig("resetAccountTimersOnStop", v));
        addComboBox(section, "Starter train-gebied", CombatBotConfig.StarterTrainRegion.values(), config.starterTrainRegion(),
                v -> setConfig("starterTrainRegion", v.name()));
        addComboBox(section, "Starter melee style", CombatBotConfig.MeleeTrainingStyle.values(), config.starterMeleeTrainingStyle(),
                v -> setConfig("starterMeleeTrainingStyle", v.name()));
        addComboBox(section, "Start skill", CombatBotConfig.StartSkill.values(), config.startSkill(),
                v -> setConfig("startSkill", v.name()));
        addToggle(section, "Tut mode (eenmalig pre-skill)", config.tutModeEnabled(), v -> setConfig("tutModeEnabled", v));
        addToggle(section, "Tut: direct naar Starter", config.tutDirectToStarter(), v -> setConfig("tutDirectToStarter", v));
        addToggle(section, "Tut: switch account bij stuck", config.tutSwitchAccountOnStuck(),
                v -> setConfig("tutSwitchAccountOnStuck", v));
        addTextField(section, "Tut: display-name lijst", config.tutDisplayNamePool(), v -> setConfig("tutDisplayNamePool", v));
        addToggle(section, "🛡 Barbarian mode (skill)", config.barbarianMode(), v -> setConfig("barbarianMode", v));
        addToggle(section, "Vampyre Slayer quest (prioriteit in loop)", config.vampireSlayerQuestMode(),
                v -> setConfig("vampireSlayerQuestMode", v));
        return section;
    }

    private synchronized List<ManagedJagexAccountsStore.ManagedJagexAccountRow> managedAccountRowsShared() {
        if (sharedManagedAccountRows == null) {
            sharedManagedAccountRows = new ArrayList<>(ManagedJagexAccountsStore.parseRows(config.managedJagexAccountsBlob()));
            if (sharedManagedAccountRows.isEmpty()) {
                sharedManagedAccountRows.addAll(buildRowsFromPastedFallback());
            }
        }
        return sharedManagedAccountRows;
    }

    private void registerAccountTableRefill(JTable table, Runnable refillTable) {
        if (table == null || refillTable == null) {
            return;
        }
        pruneDeadAccountTableRefillHandles();
        accountTableRefillHandles.add(new AccountTableRefillHandle(table, refillTable));
    }

    private void pruneDeadAccountTableRefillHandles() {
        accountTableRefillHandles.removeIf(h -> h.tableRef.get() == null);
    }

    /** Alle open Accounts-tabellen (zijpaneel + los venster) opnieuw vullen vanuit de gedeelde accountlijst. */
    private void refreshAllAccountTables() {
        pruneDeadAccountTableRefillHandles();
        for (AccountTableRefillHandle h : accountTableRefillHandles) {
            JTable t = h.tableRef.get();
            if (t != null) {
                try {
                    h.refill.run();
                } catch (Throwable ex) {
                    DebugLog.log("ACCOUNTS", "Account-tabel verversen: " + ex.getMessage());
                }
            }
        }
    }

    /** Herken OK uit handmatige {@link JOptionPane#createDialog} (Integer/Long/String afhankelijk van LAF). */
    private static boolean isJOptionPaneOkValue(Object selVal) {
        if (selVal instanceof Integer) {
            return ((Integer) selVal).intValue() == JOptionPane.OK_OPTION;
        }
        if (selVal instanceof Long) {
            return ((Long) selVal).longValue() == JOptionPane.OK_OPTION;
        }
        if (selVal instanceof String) {
            String s = ((String) selVal).trim();
            return "OK".equalsIgnoreCase(s);
        }
        return false;
    }

    private JPanel createAccountsManagerTab() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BG_DARK);
        root.setBorder(new EmptyBorder(8, 8, 8, 8));

        JPanel controlBar = createAccountsBotControlBar();
        JPanel northStack = new JPanel();
        northStack.setLayout(new BoxLayout(northStack, BoxLayout.Y_AXIS));
        northStack.setOpaque(false);
        northStack.add(controlBar);
        northStack.add(Box.createVerticalStrut(8));
        northStack.add(createAccountsStartSkillSection());
        northStack.add(Box.createVerticalStrut(8));
        northStack.add(createAccountsTabRotationAndCredentialsSection());
        northStack.add(Box.createVerticalStrut(8));
        root.add(northStack, BorderLayout.NORTH);

        final List<ManagedJagexAccountsStore.ManagedJagexAccountRow> accountRows = managedAccountRowsShared();

        String[] colNames = {
                "Rotatie", "Volgende", "ℹ", "Gebruikersnaam", "GP ~", "Cmb", "A/S/D", "WC", "Min", "Fish", "Pray", "Magic", "Quests", "Skill", "Timer", "Centers", "Laatst"
        };
        DefaultTableModel tableModel = new DefaultTableModel(colNames, 0) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return columnIndex == 0 ? Boolean.class : String.class;
            }

            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 0;
            }
        };

        final JTable[] tableRef = new JTable[1];
        /** Dubbelklik op Accounts (niet kolom Rotatie/ℹ): tijd tussen twee klikken i.p.v. getClickCount() op JavaFX/Swing. */
        final long[] lastAccountDblMs = new long[1];
        final int[] lastAccountDblRow = new int[] { -1 };
        final int[] lastAccountDblCol = new int[] { -1 };

        Runnable refillTable = () -> {
            JTable table = tableRef[0];
            String preserveUser = null;
            if (table != null && table.getRowCount() > 0) {
                int sr = table.getSelectedRow();
                if (sr < 0) {
                    sr = table.getSelectionModel().getLeadSelectionIndex();
                }
                if (sr >= 0 && sr < table.getRowCount()) {
                    Object u = table.getValueAt(sr, ACCOUNTS_COL_USER);
                    preserveUser = u != null ? u.toString() : null;
                }
            }

            tableModel.setRowCount(0);
            Map<String, ManagedJagexAccountsStore.AccountStatSnapshot> snaps =
                    ManagedJagexAccountsStore.parseSnapshots(config.accountStatSnapshotsBlob());
            Map<String, AccountLocalProgressStore.Entry> progressByAccount =
                    AccountLocalProgressStore.parse(config.accountLocalProgressBlob());
            Map<String, AccountQuestProgressStore.QuestEntry> questByAccount =
                    AccountQuestProgressStore.parse(config.accountQuestProgressBlob());
            String nextRotationAccount = resolveNextRotationAccountDisplayName(accountRows);
            boolean hideBannedRows = config.accountsTableHideSuspectBanned();
            for (ManagedJagexAccountsStore.ManagedJagexAccountRow r : accountRows) {
                if (r.isEmpty()) {
                    continue;
                }
                ManagedJagexAccountsStore.AccountStatSnapshot s =
                        ManagedJagexAccountsStore.snapshotForRow(snaps, r.displayName);
                if (hideBannedRows && s != null && s.hiscoreSuspectBanned) {
                    continue;
                }
                int glob = 0;
                if (r.useGlobalCombatCenters) {
                    glob++;
                }
                if (r.useGlobalWcCenters) {
                    glob++;
                }
                if (r.useGlobalMiningCenters) {
                    glob++;
                }
                if (r.useGlobalFishingCenters) {
                    glob++;
                }
                if (r.useGlobalImpsCenters) {
                    glob++;
                }
                String centersLbl = glob + "/5 glob.";
                String gp = "—";
                String cmb = "—";
                String melee = "—";
                String wc = "—";
                String mi = "—";
                String fi = "—";
                String pr = "—";
                String mg = "—";
                String qs = "—";
                String accountKey = r.displayName != null ? r.displayName.trim().toLowerCase(Locale.ROOT) : "";
                AccountLocalProgressStore.Entry progress = progressByAccount.get(
                        accountKey);
                String skillLbl = (progress != null && progress.activeSkillName != null && !progress.activeSkillName.trim().isEmpty())
                        ? progress.activeSkillName
                        : "—";
                String timerLbl = formatAccountProgressTimer(progress);
                String last = "—";
                if (s != null) {
                    gp = paint != null ? paint.formatGp(s.totalGpApprox) : String.valueOf(s.totalGpApprox);
                    cmb = String.valueOf(s.combatLevel);
                    melee = s.attack + "/" + s.strength + "/" + s.defence;
                    wc = String.valueOf(s.woodcutting);
                    mi = String.valueOf(s.mining);
                    fi = String.valueOf(s.fishing);
                    pr = String.valueOf(s.prayer);
                    mg = String.valueOf(s.magic);
                    if (s.quests > 0) {
                        qs = String.valueOf(s.quests);
                    }
                    if (s.updatedEpochMs > 0) {
                        last = formatHiscoreSyncAgo(s.updatedEpochMs);
                    }
                }
                AccountQuestProgressStore.QuestEntry q = questByAccount.get(accountKey);
                if (q != null && q.vampireSlayerStep >= AccountQuestProgressStore.VAMPIRE_SLAYER_STEP_DONE) {
                    qs = "Vampyre Slayer";
                }
                String nextMark = (nextRotationAccount != null && r.displayName != null
                        && r.displayName.trim().equalsIgnoreCase(nextRotationAccount))
                        ? "▶"
                        : "";
                tableModel.addRow(new Object[]{
                        r.rotationEnabled, nextMark, "ℹ", r.displayName, gp, cmb, melee, wc, mi, fi, pr, mg, qs, skillLbl, timerLbl, centersLbl, last
                });
            }

            if (table != null && preserveUser != null && !preserveUser.isEmpty()) {
                int newIdx = resolveTableIndexForAccount(accountRows, preserveUser);
                if (newIdx >= 0) {
                    table.setRowSelectionInterval(newIdx, newIdx);
                }
            }
        };

        final Timer[] accountPersistDebounce = new Timer[1];
        Runnable runPersistManagedAccounts = () -> {
            List<ManagedJagexAccountsStore.ManagedJagexAccountRow> save = new ArrayList<>();
            for (ManagedJagexAccountsStore.ManagedJagexAccountRow r : accountRows) {
                if (!r.isEmpty()) {
                    save.add(r);
                }
            }
            ManagedJagexAccountsStore.persist(configManager, save);
            onManagedAccountsSaved.run();
            refreshAllAccountTables();
        };
        Runnable schedulePersistManagedAccounts = () -> {
            if (accountPersistDebounce[0] != null) {
                accountPersistDebounce[0].stop();
            }
            accountPersistDebounce[0] = new Timer(400, e -> runPersistManagedAccounts.run());
            accountPersistDebounce[0].setRepeats(false);
            accountPersistDebounce[0].start();
        };

        tableModel.addTableModelListener(e -> {
            if (e.getType() != TableModelEvent.UPDATE || e.getColumn() != 0) {
                return;
            }
            ManagedJagexAccountsStore.ManagedJagexAccountRow row =
                    resolveAccountRowAtTableIndex(accountRows, e.getFirstRow());
            if (row != null) {
                Object v = tableModel.getValueAt(e.getFirstRow(), 0);
                row.rotationEnabled = Boolean.TRUE.equals(v);
                schedulePersistManagedAccounts.run();
            }
        });

        JTable table = new JTable(tableModel);
        tableRef[0] = table;
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFont(FONT_LABEL);
        table.setBackground(new Color(35, 37, 48));
        table.setForeground(TEXT);
        table.setRowHeight(24);
        table.setFillsViewportHeight(true);

        table.getColumnModel().getColumn(ACCOUNTS_COL_NEXT).setPreferredWidth(52);
        table.getColumnModel().getColumn(ACCOUNTS_COL_NEXT).setMaxWidth(64);
        table.getColumnModel().getColumn(ACCOUNTS_COL_INFO).setPreferredWidth(34);
        table.getColumnModel().getColumn(ACCOUNTS_COL_INFO).setMaxWidth(44);
        table.getColumnModel().getColumn(ACCOUNTS_COL_NEXT).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean isSelected, boolean hasFocus,
                    int row, int column) {
                JLabel c = (JLabel) super.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, column);
                c.setHorizontalAlignment(SwingConstants.CENTER);
                c.setToolTipText("Volgende account in rotatie");
                return c;
            }
        });
        TableCellRenderer defaultHeaderRenderer = table.getTableHeader().getDefaultRenderer();
        int[] iconHeaderCols = {
                ACCOUNTS_COL_CMB, ACCOUNTS_COL_ASD, ACCOUNTS_COL_WC, ACCOUNTS_COL_MIN,
                ACCOUNTS_COL_FISH, ACCOUNTS_COL_PRAY, ACCOUNTS_COL_MAGIC, ACCOUNTS_COL_QUEST
        };
        Map<Integer, String> iconHeaderKeys = new HashMap<>();
        iconHeaderKeys.put(ACCOUNTS_COL_CMB, "CMB");
        iconHeaderKeys.put(ACCOUNTS_COL_ASD, "ASD");
        iconHeaderKeys.put(ACCOUNTS_COL_WC, "WC");
        iconHeaderKeys.put(ACCOUNTS_COL_MIN, "MIN");
        iconHeaderKeys.put(ACCOUNTS_COL_FISH, "FISH");
        iconHeaderKeys.put(ACCOUNTS_COL_PRAY, "PRAY");
        iconHeaderKeys.put(ACCOUNTS_COL_MAGIC, "MAGIC");
        iconHeaderKeys.put(ACCOUNTS_COL_QUEST, "QUEST");
        for (int colIdx : iconHeaderCols) {
            if (colIdx < 0 || colIdx >= table.getColumnCount()) {
                continue;
            }
            table.getColumnModel().getColumn(colIdx).setHeaderRenderer((t, value, isSelected, hasFocus, row, column) -> {
                JLabel lab = (JLabel) defaultHeaderRenderer.getTableCellRendererComponent(
                        t, value, isSelected, hasFocus, row, column);
                lab.setHorizontalAlignment(SwingConstants.CENTER);
                String key = iconHeaderKeys.get(colIdx);
                ImageIcon icon = loadAccountHeaderIcon(key);
                if (icon != null) {
                    lab.setIcon(icon);
                    lab.setText("");
                    lab.setToolTipText(value != null ? value.toString() : null);
                } else {
                    lab.setIcon(null);
                    lab.setText(value != null ? value.toString() : "");
                    lab.setToolTipText(null);
                }
                return lab;
            });
        }
        table.getColumnModel().getColumn(ACCOUNTS_COL_INFO).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean isSelected, boolean hasFocus,
                    int row, int column) {
                JLabel c = (JLabel) super.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, column);
                c.setHorizontalAlignment(SwingConstants.CENTER);
                c.setText("ℹ");
                c.setToolTipText("Live hiscore-stats (OSRS API)");
                return c;
            }
        });

        TableCellRenderer baseStr = table.getDefaultRenderer(String.class);
        TableCellRenderer baseBool = table.getDefaultRenderer(Boolean.class);
        table.setDefaultRenderer(String.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean isSelected, boolean hasFocus,
                    int row, int column) {
                JLabel lab = (JLabel) baseStr.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, column);
                if (column == ACCOUNTS_COL_USER) {
                    Object u = t.getValueAt(row, ACCOUNTS_COL_USER);
                    String name = u != null ? u.toString() : "";
                    ManagedJagexAccountsStore.AccountStatSnapshot s = ManagedJagexAccountsStore.snapshotForRow(
                            ManagedJagexAccountsStore.parseSnapshots(config.accountStatSnapshotsBlob()), name);
                    if (s != null && s.hiscoreSuspectBanned) {
                        lab.setText(name + " [BANNED?]");
                    }
                }
                applySuspectAccountRowBackground(t, lab, row, isSelected);
                return lab;
            }
        });
        table.setDefaultRenderer(Boolean.class, new TableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean isSelected, boolean hasFocus,
                    int row, int column) {
                Component comp = baseBool.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, column);
                Object u = t.getValueAt(row, ACCOUNTS_COL_USER);
                String name = u != null ? u.toString() : "";
                ManagedJagexAccountsStore.AccountStatSnapshot s = ManagedJagexAccountsStore.snapshotForRow(
                        ManagedJagexAccountsStore.parseSnapshots(config.accountStatSnapshotsBlob()), name);
                if (s != null && s.hiscoreSuspectBanned) {
                    comp.setBackground(isSelected ? new Color(110, 45, 45) : new Color(85, 30, 30));
                    comp.setForeground(isSelected ? Color.WHITE : new Color(255, 200, 200));
                } else {
                    comp.setBackground(isSelected ? t.getSelectionBackground() : t.getBackground());
                    comp.setForeground(isSelected ? t.getSelectionForeground() : t.getForeground());
                }
                return comp;
            }
        });

        JPopupMenu accountRowPopup = new JPopupMenu();
        JMenuItem accountPopupEdit = new JMenuItem("Bewerken…");
        JMenuItem accountPopupDelete = new JMenuItem("Verwijderen");
        accountRowPopup.add(accountPopupEdit);
        accountRowPopup.add(accountPopupDelete);

        Runnable runEditSelectedAccount = () -> {
            ManagedJagexAccountsStore.ManagedJagexAccountRow sel =
                    resolveAccountRowAtTableIndex(accountRows, table.getSelectedRow());
            if (sel == null) {
                sel = resolveAccountRowAtTableIndex(accountRows, table.getSelectionModel().getLeadSelectionIndex());
            }
            if (sel == null) {
                JOptionPane.showMessageDialog(root,
                        "Klik eerst een rij aan in de tabel (gebruikersnaam).",
                        "Bewerken",
                        JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (showAccountEditDialog(SwingUtilities.getWindowAncestor(root), sel)) {
                refreshAllAccountTables();
                schedulePersistManagedAccounts.run();
            }
        };
        Runnable runDeleteSelectedAccount = () -> {
            ManagedJagexAccountsStore.ManagedJagexAccountRow sel =
                    resolveAccountRowAtTableIndex(accountRows, table.getSelectedRow());
            if (sel == null) {
                sel = resolveAccountRowAtTableIndex(accountRows, table.getSelectionModel().getLeadSelectionIndex());
            }
            if (sel != null) {
                accountRows.remove(sel);
                refreshAllAccountTables();
                schedulePersistManagedAccounts.run();
            }
        };
        accountPopupEdit.addActionListener(e -> runEditSelectedAccount.run());
        accountPopupDelete.addActionListener(e -> runDeleteSelectedAccount.run());

        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                handleAccountTableMouse(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                handleAccountTableMouse(e);
            }

            private void handleAccountTableMouse(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    int row = table.rowAtPoint(e.getPoint());
                    if (row >= 0) {
                        table.setRowSelectionInterval(row, row);
                        accountRowPopup.show(e.getComponent(), e.getX(), e.getY());
                    }
                    return;
                }
                if (!SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }
                int row = table.rowAtPoint(e.getPoint());
                if (row < 0) {
                    return;
                }
                int col = table.columnAtPoint(e.getPoint());
                table.setRowSelectionInterval(row, row);
                Window win = SwingUtilities.getWindowAncestor(root);
                if (col == ACCOUNTS_COL_INFO) {
                    ManagedJagexAccountsStore.ManagedJagexAccountRow acc =
                            resolveAccountRowAtTableIndex(accountRows, row);
                    if (acc != null) {
                        AccountStatsGridDialog.show(win, acc.displayName);
                    }
                    lastAccountDblRow[0] = -1;
                    return;
                }
                if (col == 0) {
                    lastAccountDblRow[0] = -1;
                    return;
                }
                long now = System.currentTimeMillis();
                boolean isDbl = row == lastAccountDblRow[0]
                        && col == lastAccountDblCol[0]
                        && now - lastAccountDblMs[0] > 30L
                        && now - lastAccountDblMs[0] < 650L;
                if (isDbl) {
                    lastAccountDblRow[0] = -1;
                    table.setRowSelectionInterval(row, row);
                    ManagedJagexAccountsStore.ManagedJagexAccountRow r = resolveAccountRowAtTableIndex(accountRows, row);
                    if (r != null) {
                        onAccountPrepareLogin.accept(r.copy());
                    }
                } else {
                    lastAccountDblMs[0] = now;
                    lastAccountDblRow[0] = row;
                    lastAccountDblCol[0] = col;
                }
            }
        });

        registerAccountTableRefill(table, refillTable);
        refreshAllAccountTables();
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(null);
        JPanel tableToolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        tableToolbar.setOpaque(false);
        JCheckBox cbHideSuspectBanned = new JCheckBox("Verberg accounts met hiscore-ban vermoeden");
        cbHideSuspectBanned.setSelected(config.accountsTableHideSuspectBanned());
        cbHideSuspectBanned.setForeground(TEXT);
        cbHideSuspectBanned.setBackground(BG_DARK);
        cbHideSuspectBanned.addActionListener(e -> {
            setConfig("accountsTableHideSuspectBanned", cbHideSuspectBanned.isSelected());
            refreshAllAccountTables();
        });
        tableToolbar.add(cbHideSuspectBanned);
        JPanel centerWrap = new JPanel(new BorderLayout());
        centerWrap.setOpaque(false);
        centerWrap.setBackground(BG_DARK);
        centerWrap.add(tableToolbar, BorderLayout.NORTH);
        centerWrap.add(scroll, BorderLayout.CENTER);
        root.add(centerWrap, BorderLayout.CENTER);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        btns.setOpaque(false);
        JButton addBtn = new JButton("+ Account");
        styleSmallButton(addBtn, new Color(50, 100, 60));
        JButton importBtn = new JButton("Importeer plak-tekst");
        styleSmallButton(importBtn, new Color(55, 75, 120));
        JButton importStormJsonBtn = new JButton("Importeer Storm JSON…");
        styleSmallButton(importStormJsonBtn, new Color(55, 95, 120));
        importStormJsonBtn.setToolTipText("<html>Bulk: kies een <code>storm-accounts.json</code> (velden <code>character_id</code>, "
                + "<code>session_id</code>, <code>display_name</code>, optioneel <code>world</code>). "
                + "Accounts die al bestaan (zelfde character id, session id of displaynaam) worden overgeslagen.</html>");
        JButton editBtn = new JButton("Bewerken…");
        styleSmallButton(editBtn, new Color(70, 70, 95));
        JButton delBtn = new JButton("Verwijder");
        styleSmallButton(delBtn, new Color(100, 50, 50));
        JButton resetStateBtn = new JButton("↺ Status reset");
        styleSmallButton(resetStateBtn, new Color(90, 55, 55));
        resetStateBtn.setToolTipText("Zelfde als ↺ Reset op de balk: runtime state opschonen zonder centers/startskill te wijzigen. "
                + "Breekt hangende account-wissel/re-log en handler-stuck-state af, huidige skill blijft behouden.");
        btns.add(addBtn);
        btns.add(importBtn);
        btns.add(importStormJsonBtn);
        btns.add(editBtn);
        btns.add(delBtn);
        btns.add(resetStateBtn);

        JLabel lblStormImportStatus = new JLabel(" ");
        lblStormImportStatus.setFont(FONT_LABEL);
        lblStormImportStatus.setForeground(TEXT_DIM);
        lblStormImportStatus.setAlignmentX(Component.LEFT_ALIGNMENT);
        lblStormImportStatus.setBorder(new EmptyBorder(2, 4, 6, 4));

        JPanel south = new JPanel();
        south.setLayout(new BoxLayout(south, BoxLayout.Y_AXIS));
        south.setOpaque(false);
        south.add(btns);
        south.add(lblStormImportStatus);
        root.add(south, BorderLayout.SOUTH);

        addBtn.addActionListener(e -> {
            ManagedJagexAccountsStore.ManagedJagexAccountRow r = new ManagedJagexAccountsStore.ManagedJagexAccountRow();
            r.displayName = "account" + (accountRows.size() + 1);
            r.rotationEnabled = true;
            accountRows.add(r);
            refreshAllAccountTables();
            schedulePersistManagedAccounts.run();
        });

        importBtn.addActionListener(e -> {
            List<JagexCredentialsHelper.ParsedJagexAccount> parsed =
                    JagexCredentialsHelper.parsePastedCredentials(config.pastedCredentials());
            java.util.Set<String> enabled =
                    JagexCredentialsHelper.parseEnabledDisplayNames(config.enabledDisplayNames());
            for (JagexCredentialsHelper.ParsedJagexAccount p : parsed) {
                String dn = p.getDisplayName();
                ManagedJagexAccountsStore.ManagedJagexAccountRow row = null;
                for (ManagedJagexAccountsStore.ManagedJagexAccountRow x : accountRows) {
                    if (dn != null && dn.equalsIgnoreCase(x.displayName)) {
                        row = x;
                        break;
                    }
                }
                if (row == null) {
                    row = new ManagedJagexAccountsStore.ManagedJagexAccountRow();
                    accountRows.add(row);
                }
                row.displayName = dn != null ? dn : row.displayName;
                String cid = p.get("JX_CHARACTER_ID");
                if (cid != null && !cid.isEmpty()) {
                    row.characterId = cid;
                }
                String sid = p.get("JX_ACCESS_TOKEN");
                if (sid == null || sid.isEmpty()) {
                    sid = p.get("JX_SESSION_ID");
                }
                if (sid != null && !sid.isEmpty()) {
                    row.sessionId = sid;
                }
                row.rotationEnabled = enabled.contains(dn);
            }
            refreshAllAccountTables();
            schedulePersistManagedAccounts.run();
            JOptionPane.showMessageDialog(root, "Geïmporteerd uit huidige geplakte credentials.", "Accounts", JOptionPane.INFORMATION_MESSAGE);
        });

        importStormJsonBtn.addActionListener(e -> {
            Window w = resolveSwingOwner(root);
            JFileChooser fc = new JFileChooser();
            fc.setDialogTitle("Storm accounts JSON (storm-accounts.json)");
            fc.setFileFilter(new FileNameExtensionFilter("JSON (*.json)", "json"));
            int rc = fc.showOpenDialog(w);
            if (rc != JFileChooser.APPROVE_OPTION) {
                lblStormImportStatus.setText("<html><body width='440'><font color='#bbbbbb'>Import geannuleerd.</font></body></html>");
                DebugLog.log("StormImport", "geannuleerd door gebruiker");
                return;
            }
            File f = fc.getSelectedFile();
            if (f == null || !f.isFile()) {
                String msg = "Geen geldig bestand gekozen: " + (f == null ? "(null)" : f.getAbsolutePath());
                lblStormImportStatus.setText("<html><body width='440'><font color='#ff8888'>" + escapeForHtmlLabel(msg) + "</font></body></html>");
                JOptionPane.showMessageDialog(resolveSwingOwner(root), msg, "Storm JSON import", JOptionPane.ERROR_MESSAGE);
                DebugLog.log("StormImport", msg);
                return;
            }

            String json;
            try {
                json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            } catch (Exception readEx) {
                String msg = "Kon bestand niet lezen: " + readEx.getMessage() + "\n" + f.getAbsolutePath();
                lblStormImportStatus.setText("<html><body width='440'><font color='#ff8888'>" + escapeForHtmlLabel(msg) + "</font></body></html>");
                JOptionPane.showMessageDialog(resolveSwingOwner(root), msg, "Storm JSON import", JOptionPane.ERROR_MESSAGE);
                DebugLog.log("StormImport", "read-fail: " + readEx.getMessage());
                return;
            }

            int rowsBefore = accountRows.size();
            StormAccountsBulkImport.Result res;
            try {
                res = StormAccountsBulkImport.importInto(accountRows, json);
            } catch (Throwable importEx) {
                String msg = "Import-exception: " + importEx.getClass().getSimpleName() + ": " + importEx.getMessage();
                lblStormImportStatus.setText("<html><body width='440'><font color='#ff8888'>" + escapeForHtmlLabel(msg) + "</font></body></html>");
                JOptionPane.showMessageDialog(resolveSwingOwner(root), msg, "Storm JSON import", JOptionPane.ERROR_MESSAGE);
                DebugLog.log("StormImport", msg);
                return;
            }
            int rowsAfter = accountRows.size();

            // Schrijf diagnose-bestand naast ~/.runelite/ zodat de gebruiker exact ziet wat er gebeurde.
            try {
                java.nio.file.Path diag = java.nio.file.Paths.get(System.getProperty("user.home"),
                        ".runelite", "combatbot-storm-import.log");
                java.nio.file.Files.createDirectories(diag.getParent());
                String line = "[" + java.time.Instant.now() + "] file=" + f.getAbsolutePath()
                        + " bytes=" + json.length()
                        + " rowsBefore=" + rowsBefore + " rowsAfter=" + rowsAfter
                        + " result=" + res.summary()
                        + (res.fatalError != null ? " fatal=" + res.fatalError : "")
                        + System.lineSeparator();
                java.nio.file.Files.write(diag, line.getBytes(StandardCharsets.UTF_8),
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (Throwable ignore) { }

            refreshAllAccountTables();
            if (res.added > 0) {
                schedulePersistManagedAccounts.run();
            }

            String summaryLine = (res.fatalError != null ? res.fatalError : res.summary())
                    + "\n\nRijen voor: " + rowsBefore + " → na: " + rowsAfter
                    + "\nBestand: " + f.getAbsolutePath()
                    + "\nDiagnose-log: ~/.runelite/combatbot-storm-import.log";
            lblStormImportStatus.setText("<html><body width='440'>"
                    + (res.fatalError != null ? "<font color='#ff8888'>" : "")
                    + escapeForHtmlLabel(summaryLine)
                    + (res.fatalError != null ? "</font>" : "")
                    + "</body></html>");
            DebugLog.log("StormImport", summaryLine.replace('\n', ' '));

            // Toon ALTIJD een modale popup zodat de gebruiker de uitkomst ziet.
            JOptionPane.showMessageDialog(resolveSwingOwner(root),
                    summaryLine,
                    "Storm JSON import",
                    res.fatalError != null ? JOptionPane.ERROR_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
        });

        editBtn.addActionListener(e -> runEditSelectedAccount.run());

        delBtn.addActionListener(e -> runDeleteSelectedAccount.run());

        root.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) == 0 || root.isShowing()) {
                return;
            }
            if (accountPersistDebounce[0] != null && accountPersistDebounce[0].isRunning()) {
                accountPersistDebounce[0].stop();
                runPersistManagedAccounts.run();
            }
        });

        resetStateBtn.addActionListener(e -> {
            onResetAllBotState.run();
            refreshAllAccountTables();
        });

        Timer refreshTimer = new Timer(8000, e2 -> refreshAllAccountTables());
        refreshTimer.start();

        return root;
    }

    private List<ManagedJagexAccountsStore.ManagedJagexAccountRow> buildRowsFromPastedFallback() {
        List<ManagedJagexAccountsStore.ManagedJagexAccountRow> out = new ArrayList<>();
        List<JagexCredentialsHelper.ParsedJagexAccount> parsed =
                JagexCredentialsHelper.parsePastedCredentials(config.pastedCredentials());
        java.util.Set<String> enabled =
                JagexCredentialsHelper.parseEnabledDisplayNames(config.enabledDisplayNames());
        for (JagexCredentialsHelper.ParsedJagexAccount p : parsed) {
            ManagedJagexAccountsStore.ManagedJagexAccountRow r = new ManagedJagexAccountsStore.ManagedJagexAccountRow();
            r.displayName = p.getDisplayName();
            if (r.displayName == null || r.displayName.isEmpty()) {
                continue;
            }
            String cid = p.get("JX_CHARACTER_ID");
            if (cid != null) {
                r.characterId = cid;
            }
            String sid = p.get("JX_ACCESS_TOKEN");
            if (sid == null || sid.isEmpty()) {
                sid = p.get("JX_SESSION_ID");
            }
            if (sid != null) {
                r.sessionId = sid;
            }
            r.rotationEnabled = enabled.contains(r.displayName);
            out.add(r);
        }
        return out;
    }

    /** Compacte ⓘ-knop: volledige uitleg in een dialoog (account-bewerken). */
    private static JButton createAccountInfoButton(Window owner, String title, String htmlBody) {
        JButton b = new JButton("ⓘ");
        b.setFont(new Font("SansSerif", Font.BOLD, 13));
        b.setForeground(new Color(130, 170, 230));
        b.setBackground(BG_DARK);
        b.setBorderPainted(false);
        b.setContentAreaFilled(false);
        b.setFocusPainted(false);
        b.setMargin(new Insets(0, 2, 0, 2));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setToolTipText("Uitleg");
        b.addActionListener(e -> JOptionPane.showMessageDialog(owner,
                "<html><body style='width:400px;font-family:sans-serif;font-size:12px'>" + htmlBody + "</body></html>",
                title,
                JOptionPane.INFORMATION_MESSAGE));
        return b;
    }

    private static JPanel accountSectionHeader(String title, JButton infoButton) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel lab = new JLabel(title);
        lab.setFont(FONT_SECTION);
        lab.setForeground(TEXT);
        row.add(lab);
        row.add(infoButton);
        return row;
    }

    /**
     * Per-account "GE inkoop" blok: per categorie een vinkje (mag bot kopen?) + spinner (max-cap).
     * State wordt geserializeerd als compact blob via {@link GeShopPolicy#serializeBlob}.
     */
    private static final class GeShopBlock {
        final JPanel root;
        final java.util.LinkedHashMap<GeShopPolicy.Category, JCheckBox> checkboxes = new java.util.LinkedHashMap<>();
        final java.util.LinkedHashMap<GeShopPolicy.Category, JSpinner> caps = new java.util.LinkedHashMap<>();

        GeShopBlock(JPanel root) { this.root = root; }

        void applyToRow(ManagedJagexAccountsStore.ManagedJagexAccountRow r) {
            java.util.LinkedHashMap<GeShopPolicy.Category, GeShopPolicy.CategoryRule> rules =
                    new java.util.LinkedHashMap<>();
            for (GeShopPolicy.Category c : GeShopPolicy.Category.values()) {
                JCheckBox cb = checkboxes.get(c);
                JSpinner sp = caps.get(c);
                boolean on = cb == null || cb.isSelected();
                int cap = sp == null ? 0 : ((Number) sp.getValue()).intValue();
                rules.put(c, new GeShopPolicy.CategoryRule(on, cap));
            }
            r.geBuyTogglesBlob = GeShopPolicy.serializeBlob(rules);
        }
    }

    private GeShopBlock buildGeShopBlock(Window parent,
                                         ManagedJagexAccountsStore.ManagedJagexAccountRow r) {
        java.util.Map<GeShopPolicy.Category, GeShopPolicy.CategoryRule> rules =
                GeShopPolicy.parseBlob(r.geBuyTogglesBlob);

        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBackground(BG_DARK);
        root.setAlignmentX(Component.LEFT_ALIGNMENT);
        root.setBorder(new EmptyBorder(8, 0, 4, 0));

        String helpHtml = "<p>Vink uit wat dit account <b>niet</b> mag kopen in de Grand Exchange. "
                + "Per categorie kan je optioneel een <b>cap</b> zetten (0 = geen extra cap, gebruik wat de handler vraagt).</p>"
                + "<p>Voorbeeld: <b>Dure runes</b> cap = 30 → de bot koopt nooit meer dan 30 Law/Death/Nature/...&nbsp;per restock-call. "
                + "Goed tegen het 'koopt 160 Law runes'-probleem.</p>"
                + "<p>Bestaande accounts zonder instellingen starten met <b>alle vinkjes aan</b> en <b>Dure runes cap = 30</b> als veilige default.</p>"
                + "<p>Een geblokkeerde aankoop verschijnt in de log als <code>[GeRestock] BLOKKADE per-account policy: '...'</code>.</p>";
        JPanel head = accountSectionHeader(
                "📦 GE inkoop (per account)",
                createAccountInfoButton(parent, "GE inkoop", helpHtml));
        head.setBorder(new EmptyBorder(0, 0, 6, 0));
        root.add(head);

        GeShopBlock block = new GeShopBlock(root);

        JPanel grid = new JPanel(new GridBagLayout());
        grid.setBackground(BG_DARK);
        grid.setAlignmentX(Component.LEFT_ALIGNMENT);
        GridBagConstraints g = new GridBagConstraints();
        g.anchor = GridBagConstraints.WEST;
        g.insets = new Insets(2, 4, 2, 8);

        int row = 0;
        for (GeShopPolicy.Category cat : GeShopPolicy.Category.values()) {
            GeShopPolicy.CategoryRule rule = rules.getOrDefault(cat,
                    new GeShopPolicy.CategoryRule(true, 0));

            JCheckBox cb = new JCheckBox(cat.label);
            cb.setFont(FONT_LABEL);
            cb.setBackground(BG_DARK);
            cb.setForeground(TEXT);
            cb.setSelected(rule.allowed);
            cb.setToolTipText(
                    "Aan: bot mag deze categorie in de GE kopen. "
                            + "Uit: élke buy van een item in deze categorie wordt geweigerd (FAILED → handler valt terug).");
            block.checkboxes.put(cat, cb);

            JLabel capLab = new JLabel("max:");
            capLab.setFont(FONT_LABEL);
            capLab.setForeground(TEXT_DIM);

            JSpinner cap = new JSpinner(new javax.swing.SpinnerNumberModel(
                    Math.max(0, rule.cap), 0, 100_000, 1));
            cap.setPreferredSize(new Dimension(72, 22));
            cap.setToolTipText(
                    "Cap op totale hoeveelheid per restock-call. 0 = geen extra cap (handler-default). "
                            + "Voor dure runes raden we 20–40 aan.");
            block.caps.put(cat, cap);

            g.gridx = 0; g.gridy = row; g.weightx = 1.0; g.fill = GridBagConstraints.HORIZONTAL;
            grid.add(cb, g);
            g.gridx = 1; g.weightx = 0; g.fill = GridBagConstraints.NONE;
            grid.add(capLab, g);
            g.gridx = 2;
            grid.add(cap, g);
            row++;
        }
        root.add(grid);
        return block;
    }

    private boolean showAccountEditDialog(Window parent, ManagedJagexAccountsStore.ManagedJagexAccountRow r) {
        JPanel form = new JPanel(new BorderLayout(0, 10));
        JPanel top = new JPanel(new GridLayout(0, 2, 6, 4));
        JTextField fName = new JTextField(r.displayName, 18);
        JTextField fChar = new JTextField(r.characterId, 18);
        JTextField fSess = new JTextField(r.sessionId, 18);
        JTextField fNotes = new JTextField(r.notes, 18);
        JTextField fWorld = new JTextField(r.accountSwitchWorld != null ? r.accountSwitchWorld : "", 18);
        JTextField fAttTarget = new JTextField(String.valueOf(Math.max(0, r.targetAttackLevel)), 18);
        JTextField fStrTarget = new JTextField(String.valueOf(Math.max(0, r.targetStrengthLevel)), 18);
        JTextField fDefTarget = new JTextField(String.valueOf(Math.max(0, r.targetDefenceLevel)), 18);
        fAttTarget.setToolTipText("0 = geen per-account doel. Gebruikt door Starter én melee Combat/Imps/Giants.");
        fStrTarget.setToolTipText("0 = geen per-account doel. Gebruikt door Starter én melee Combat/Imps/Giants.");
        fDefTarget.setToolTipText("0 = geen per-account doel. Gebruikt door Starter én melee Combat/Imps/Giants.");
        fWorld.setToolTipText(
                "Als wereld-hop aan staat: leeg = willekeurige F2P, of vul een wereld-ID (bijv. 301) voor een vaste wereld. "
                        + "Geldt bij start op het login-scherm én bij account-wissel.");
        JCheckBox cbWorldHop = new JCheckBox();
        cbWorldHop.setSelected(r.accountSwitchWorldHopEnabled);
        JCheckBox cbCalibrateBank = new JCheckBox();
        cbCalibrateBank.setSelected(r.calibrateBankOnNextLogin);
        cbCalibrateBank.setToolTipText("Bij volgende login eerst bank openen en account-bankstate verversen.");
        JCheckBox cbMagicAutoUpdate = new JCheckBox();
        cbMagicAutoUpdate.setSelected(r.magicAutoUpdate);
        cbMagicAutoUpdate.setToolTipText("Auto: bij Magic 13+ in Imps MAGE naar Fire Strike upgraden; bij coin-tekort eerst loot verkopen.");
        JCheckBox cbGlobalCenterListsOnly = new JCheckBox();
        cbGlobalCenterListsOnly.setSelected(r.useGlobalCenterListsOnly);
        cbGlobalCenterListsOnly.setToolTipText(
                "Aan: dit account gebruikt altijd de center-lijsten van de tab Centers + globale skill-vinken. "
                        + "Uit: welke skills meetellen en Imps-combat/Giants zie je hieronder (zelfde idee als de dropdowns bovenin).");
        ManagedJagexAccountsStore.AccountStatSnapshot statSnap =
                ManagedJagexAccountsStore.snapshotForRow(
                        ManagedJagexAccountsStore.parseSnapshots(config.accountStatSnapshotsBlob()),
                        r.displayName);
        String currentMeleeLevels = statSnap != null
                ? statSnap.attack + " / " + statSnap.strength + " / " + statSnap.defence
                : "nog onbekend";
        AccountStateJsonStore.AccountEntry accountState = AccountStateJsonStore.getEntry(r.displayName);
        long lastCalibMs = accountState != null ? accountState.lastBankCalibrationMs : 0L;
        String antiBanSummary = formatAntiBanSummary(accountState);
        String antiBanLast = formatAntiBanLastAction(accountState);
        String bankSnapshotSummary = formatBankSnapshotSummary(accountState);
        cbWorldHop.setToolTipText("Aan: bij rotatie naar dit account eerst hoppen (willekeurig F2P of vak hiernaast). Uit: geen hop.");
        Runnable syncWorldField = () -> fWorld.setEnabled(cbWorldHop.isSelected());
        cbWorldHop.addActionListener(e -> syncWorldField.run());
        syncWorldField.run();
        addFormRow(top, "Display name", fName);
        addFormRow(top, "Character ID", fChar);
        addFormRow(top, "Session ID", fSess);
        addFormRow(top, "Notes", fNotes);
        addFormRow(top, "Target Attack lvl (account)", fAttTarget);
        addFormRow(top, "Target Strength lvl (account)", fStrTarget);
        addFormRow(top, "Target Defence lvl (account)", fDefTarget);

        // Melee prioriteit: welke stat eerst getraind wordt zolang die onder z'n target zit.
        JComboBox<MeleePriorityOption> meleePriorityCombo = new JComboBox<>(MeleePriorityOption.values());
        meleePriorityCombo.setSelectedItem(MeleePriorityOption.fromKey(r.targetMeleePriority));
        meleePriorityCombo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        meleePriorityCombo.setToolTipText(
                "Volgorde waarin de bot Att/Str/Def naar hun target traint zolang ze nog niet bereikt zijn. "
                        + "Lowest-varianten kiezen dynamisch (laagste lvl of laagste % van target).");
        top.add(new JLabel("Melee prioriteit (volgorde)"));
        top.add(meleePriorityCombo);
        top.add(new JLabel("Huidig Att / Str / Def"));
        top.add(new JLabel(currentMeleeLevels));
        String calibSuffix = lastCalibMs > 0 ? " (laatste: " + formatDateTime(lastCalibMs) + ")" : " (nog niet)";
        top.add(new JLabel("Bank calibreren bij login" + calibSuffix));
        top.add(cbCalibrateBank);
        top.add(new JLabel("Bank snapshot (account)"));
        top.add(new JLabel(bankSnapshotSummary));
        top.add(new JLabel("Anti-ban acties (account)"));
        top.add(new JLabel(antiBanSummary));
        top.add(new JLabel("Laatste anti-ban actie"));
        top.add(new JLabel(antiBanLast));
        top.add(new JLabel("Magic auto update (Imps)"));
        top.add(cbMagicAutoUpdate);
        top.add(new JLabel("Centers: alleen globale lijsten"));
        top.add(cbGlobalCenterListsOnly);

        JComboBox<Object> startSkillAccountCombo = new JComboBox<>();
        startSkillAccountCombo.addItem(null);
        for (CombatBotConfig.StartSkill sk : CombatBotConfig.StartSkill.values()) {
            startSkillAccountCombo.addItem(sk);
        }
        startSkillAccountCombo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        startSkillAccountCombo.setAlignmentX(Component.LEFT_ALIGNMENT);
        startSkillAccountCombo.setToolTipText(
                "Alleen als \"Centers: alleen globale lijsten\" hierboven UIT staat: "
                        + "start skill voor dit account i.p.v. de globale keuze op de Rotation-tab. "
                        + "Staat globale lijsten AAN, dan geldt altijd de Rotation-tab (opgeslagen keuze blijft bewaard).");
        startSkillAccountCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                    boolean isSelected, boolean cellHasFocus) {
                Object disp = value;
                if (value instanceof CombatBotConfig.StartSkill) {
                    disp = ((CombatBotConfig.StartSkill) value).toString();
                } else if (value == null) {
                    disp = "Globaal (Rotation-tab)";
                }
                return super.getListCellRendererComponent(list, disp, index, isSelected, cellHasFocus);
            }
        });
        String sso = r.startSkillOverride != null ? r.startSkillOverride.trim() : "";
        if (sso.isEmpty()) {
            startSkillAccountCombo.setSelectedIndex(0);
        } else {
            CombatBotConfig.StartSkill found = null;
            try {
                found = CombatBotConfig.StartSkill.valueOf(sso.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
            }
            if (found != null) {
                startSkillAccountCombo.setSelectedItem(found);
            } else {
                startSkillAccountCombo.setSelectedIndex(0);
            }
        }
        final JPanel[] perAccountSkillBlockHolder = new JPanel[1];
        Runnable syncPerAccountBlocks = () -> {
            boolean perAccount = !cbGlobalCenterListsOnly.isSelected();
            startSkillAccountCombo.setEnabled(perAccount);
            JPanel b = perAccountSkillBlockHolder[0];
            if (b != null) {
                b.setVisible(perAccount);
            }
        };
        cbGlobalCenterListsOnly.addActionListener(e -> syncPerAccountBlocks.run());
        syncPerAccountBlocks.run();
        top.add(new JLabel("Start skill (alleen als globale lijsten uit staan)"));
        top.add(startSkillAccountCombo);

        top.add(new JLabel("Wereld (account-wissel)"));
        JPanel worldHopRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        worldHopRow.setOpaque(false);
        worldHopRow.add(cbWorldHop);
        worldHopRow.add(fWorld);
        top.add(worldHopRow);
        form.add(top, BorderLayout.NORTH);

        JPanel centers = new JPanel();
        centers.setLayout(new BoxLayout(centers, BoxLayout.Y_AXIS));
        centers.setBackground(BG_DARK);
        centers.setBorder(new EmptyBorder(0, 4, 0, 0));

        String centersHelpHtml = "<p><b>Centers: alleen globale lijsten</b> (bovenaan dit venster)</p>"
                + "<p>Staat dit <b>aan</b>: dit account gebruikt altijd de center-lijsten van de tab <b>Centers</b> "
                + "en de globale rotatie (Rotation-tab).</p>"
                + "<p>Staat het <b>uit</b>: je stelt hieronder per skill in of je de volledige globale lijst gebruikt "
                + "of zelf locaties aan/uit zet.</p>"
                + "<p><b>Dubbelklik login</b> (Accounts-tab): vul <i>Profiel credentials.properties</i> in "
                + "(of <code>jagex:pad</code> in de accountlijst) zodat RuneLite geen \"mist username/session\" geeft.</p>";
        JPanel centersHead = accountSectionHeader("Centers & account", createAccountInfoButton(parent, "Centers & account", centersHelpHtml));
        centersHead.setBorder(new EmptyBorder(0, 0, 8, 0));
        centers.add(centersHead);

        JPanel perAccountSkillBlock = new JPanel();
        perAccountSkillBlock.setLayout(new BoxLayout(perAccountSkillBlock, BoxLayout.Y_AXIS));
        perAccountSkillBlock.setOpaque(true);
        perAccountSkillBlock.setBackground(BG_SECTION);
        perAccountSkillBlock.setAlignmentX(Component.LEFT_ALIGNMENT);
        perAccountSkillBlock.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(new Color(52, 56, 72), 1),
                new EmptyBorder(10, 12, 12, 12)));
        perAccountSkillBlockHolder[0] = perAccountSkillBlock;

        String skillsHelpHtml = "<p>Alleen zichtbaar als <b>Centers: alleen globale lijsten</b> <b>uit</b> staat.</p>"
                + "<p><b>Gebruik globale lijst</b> per skill: aan = alle locaties van de tab Centers; uit = vink hieronder "
                + "welke locaties dit account gebruikt (minstens één = skill aan).</p>"
                + "<p><b>Drop / FM / Cook</b> per locatie: overschrijft de globale skill-vinken voor dit account op die tile. "
                + "Leeg gelaten = zelfde als de hoofdtab (WC / Mining / Fishing).</p>"
                + "<p><b>Imps (Karamja)</b>: de globale lijst is <i>exact</i> wat je op de tab <b>Centers</b> onder "
                + "<b>🎯 Imps (Karamja)</b> → <b>Imps hunting centers</b> hebt staan (zelfde data als in de plugin-instellingen).</p>"
                + "<p><b>Imps combat</b> en <b>Giants combat</b>: zelfde idee als op de skill-tabs (Globaal of vast MELEE/RANGED/MAGE).</p>"
                + "<p><b>Giants — in rotatie</b>: uit = dit account doet nooit Giants; aan = volgens Giants-tab "
                + "(of vast \"aan\" als je dat eerder zo had opgeslagen).</p>";
        JPanel skillsHead = accountSectionHeader("Skills, locaties & Giants", createAccountInfoButton(parent, "Skills, locaties & Giants", skillsHelpHtml));
        skillsHead.setBorder(new EmptyBorder(0, 0, 10, 0));
        perAccountSkillBlock.add(skillsHead);

        JComboBox<String> impsStyleCombo = new JComboBox<>(new String[] {
                "Globaal (Imps-tab)",
                "MELEE",
                "RANGED",
                "MAGE"
        });
        impsStyleCombo.setToolTipText(
                "Imps-modus: Globaal = Combat style op de Imps-tab; anders alleen voor dit account (MELEE / RANGED / MAGE).");
        String curOv = r.impsCombatStyleOverride != null ? r.impsCombatStyleOverride.trim() : "";
        if (curOv.isEmpty()) {
            impsStyleCombo.setSelectedIndex(0);
        } else {
            String up = curOv.toUpperCase(Locale.ROOT);
            int sel = 0;
            for (int i = 1; i < impsStyleCombo.getItemCount(); i++) {
                if (up.equals(impsStyleCombo.getItemAt(i))) {
                    sel = i;
                    break;
                }
            }
            impsStyleCombo.setSelectedIndex(sel);
        }

        JComboBox<String> giantsCombatCombo = new JComboBox<>(new String[] {
                "Globaal (Giants-tab)",
                "MELEE",
                "RANGED",
                "MAGE"
        });
        giantsCombatCombo.setToolTipText(
                "Giants combat: Globaal = Combat style op de Giants-tab; anders alleen dit account (MELEE / RANGED / MAGE).");
        String gcOv = r.giantsCombatStyleOverride != null ? r.giantsCombatStyleOverride.trim() : "";
        if (gcOv.isEmpty()) {
            giantsCombatCombo.setSelectedIndex(0);
        } else {
            String upG = gcOv.toUpperCase(Locale.ROOT);
            int selG = 0;
            for (int i = 1; i < giantsCombatCombo.getItemCount(); i++) {
                if (upG.equals(giantsCombatCombo.getItemAt(i))) {
                    selG = i;
                    break;
                }
            }
            giantsCombatCombo.setSelectedIndex(selG);
        }

        // Per-account "Imps Mode (zonder radius)" toggle — uiterlijk bij het Imps (Karamja) blok.
        JCheckBox cbImpsModeForceOn = new JCheckBox("Imps Mode (zonder radius) — voor dit account");
        cbImpsModeForceOn.setFont(FONT_LABEL);
        cbImpsModeForceOn.setBackground(BG_SECTION);
        cbImpsModeForceOn.setForeground(TEXT);
        cbImpsModeForceOn.setToolTipText(
                "Aan: forceer Imps voor dit account, ook zonder Imps-centers (gebruikt Hunting X/Y van de Imps-tab). "
                        + "Uit: geen override; het Imps (Karamja) blok hieronder en globale instellingen bepalen of Imps draait.");
        {
            String imOv = r.impsModeOverride != null ? r.impsModeOverride.trim() : "";
            cbImpsModeForceOn.setSelected("1".equals(imOv));
        }

        JPanel comboGrid = new JPanel(new GridBagLayout());
        comboGrid.setOpaque(false);
        comboGrid.setAlignmentX(Component.LEFT_ALIGNMENT);
        Dimension comboPref = new Dimension(210, 26);
        impsStyleCombo.setPreferredSize(comboPref);
        giantsCombatCombo.setPreferredSize(comboPref);
        GridBagConstraints cg = new GridBagConstraints();
        cg.anchor = GridBagConstraints.WEST;
        cg.insets = new Insets(0, 0, 8, 10);
        JLabel limps = new JLabel("Imps combat (per account)");
        limps.setFont(FONT_LABEL);
        limps.setForeground(TEXT);
        limps.setPreferredSize(new Dimension(200, 26));
        cg.gridx = 0;
        cg.gridy = 0;
        comboGrid.add(limps, cg);
        cg.gridx = 1;
        cg.weightx = 1.0;
        cg.fill = GridBagConstraints.HORIZONTAL;
        comboGrid.add(impsStyleCombo, cg);
        JLabel lGiantC = new JLabel("Giants combat (per account)");
        lGiantC.setFont(FONT_LABEL);
        lGiantC.setForeground(TEXT);
        lGiantC.setPreferredSize(new Dimension(200, 26));
        cg.gridx = 0;
        cg.gridy = 1;
        cg.weightx = 0;
        cg.fill = GridBagConstraints.NONE;
        comboGrid.add(lGiantC, cg);
        cg.gridx = 1;
        cg.weightx = 1.0;
        cg.fill = GridBagConstraints.HORIZONTAL;
        comboGrid.add(giantsCombatCombo, cg);

        String[] rangedAmmoUiChoices = new String[ManagedJagexAccountsStore.RANGED_AMMO_TYPE_CHOICES.length + 1];
        System.arraycopy(ManagedJagexAccountsStore.RANGED_AMMO_TYPE_CHOICES, 0, rangedAmmoUiChoices, 0,
                ManagedJagexAccountsStore.RANGED_AMMO_TYPE_CHOICES.length);
        rangedAmmoUiChoices[rangedAmmoUiChoices.length - 1] = ManagedJagexAccountsStore.RANGED_AMMO_BEST_LABEL;
        JComboBox<String> rangedAmmoCombo = new JComboBox<>(rangedAmmoUiChoices);
        rangedAmmoCombo.setToolTipText(
                "Pijl-type voor RANGED: geldt voor Combat, Imps, Giants, … op dit account. "
                        + "Vast type negeert betere pijlen in bank; Beste = Rune → Bronze.");
        String ammoUiSel = ManagedJagexAccountsStore.rangedAmmoUiLabel(r.rangedAmmoType);
        for (int i = 0; i < rangedAmmoCombo.getItemCount(); i++) {
            if (ammoUiSel.equalsIgnoreCase(rangedAmmoCombo.getItemAt(i))) {
                rangedAmmoCombo.setSelectedIndex(i);
                break;
            }
        }
        rangedAmmoCombo.setPreferredSize(comboPref);
        cg.gridx = 0;
        cg.gridy = 2;
        cg.weightx = 0;
        cg.fill = GridBagConstraints.NONE;
        JLabel lAmmo = new JLabel("Ranged pijl-type (per account)");
        lAmmo.setFont(FONT_LABEL);
        lAmmo.setForeground(TEXT);
        lAmmo.setPreferredSize(new Dimension(200, 26));
        comboGrid.add(lAmmo, cg);
        cg.gridx = 1;
        cg.weightx = 1.0;
        cg.fill = GridBagConstraints.HORIZONTAL;
        comboGrid.add(rangedAmmoCombo, cg);

        perAccountSkillBlock.add(comboGrid);

        perAccountSkillBlock.add(Box.createVerticalStrut(6));
        JSeparator sepCombatSkills = new JSeparator(SwingConstants.HORIZONTAL);
        sepCombatSkills.setForeground(new Color(60, 64, 80));
        sepCombatSkills.setMaximumSize(new Dimension(Integer.MAX_VALUE, 8));
        perAccountSkillBlock.add(sepCombatSkills);
        perAccountSkillBlock.add(Box.createVerticalStrut(8));

        AccountCenterUiState stCombat = accountCenterStateFromRow(config.combatCenters(), r.combatCenters, r.useGlobalCombatCenters);
        AccountCenterUiState stWc = accountCenterStateFromRow(config.wcCenters(), r.wcCenters, r.useGlobalWcCenters);
        stWc.behaviorsBlob = r.wcCenterBehaviorsBlob != null ? r.wcCenterBehaviorsBlob : "";
        AccountCenterUiState stMine = accountCenterStateFromRow(config.miningCenters(), r.miningCenters, r.useGlobalMiningCenters);
        stMine.behaviorsBlob = r.miningCenterBehaviorsBlob != null ? r.miningCenterBehaviorsBlob : "";
        AccountCenterUiState stFish = accountCenterStateFromRow(config.fishingCenters(), r.fishingCenters, r.useGlobalFishingCenters);
        stFish.behaviorsBlob = r.fishingCenterBehaviorsBlob != null ? r.fishingCenterBehaviorsBlob : "";
        AccountCenterUiState stImps = accountCenterStateFromRow(
                impsMasterCentersForAccountEditor(config.impsCenters(), r.impsCenters),
                r.impsCenters,
                r.useGlobalImpsCenters);

        AccountSkillBlock blkCombat = buildAccountSkillCenterBlock("Combat", stCombat, null);
        perAccountSkillBlock.add(blkCombat.root);
        perAccountSkillBlock.add(Box.createVerticalStrut(4));
        AccountSkillBlock blkWc = buildAccountSkillCenterBlock("Woodcutting", stWc,
                AccountCenterBehaviorStore.SkillKind.WOODCUTTING);
        perAccountSkillBlock.add(blkWc.root);
        perAccountSkillBlock.add(Box.createVerticalStrut(4));
        AccountSkillBlock blkMine = buildAccountSkillCenterBlock("Mining", stMine,
                AccountCenterBehaviorStore.SkillKind.MINING);
        perAccountSkillBlock.add(blkMine.root);
        perAccountSkillBlock.add(Box.createVerticalStrut(4));
        AccountSkillBlock blkFish = buildAccountSkillCenterBlock("Fishing", stFish,
                AccountCenterBehaviorStore.SkillKind.FISHING);
        perAccountSkillBlock.add(blkFish.root);
        perAccountSkillBlock.add(Box.createVerticalStrut(4));
        cbImpsModeForceOn.setAlignmentX(Component.LEFT_ALIGNMENT);
        cbImpsModeForceOn.setBorder(new EmptyBorder(2, 2, 4, 2));
        perAccountSkillBlock.add(cbImpsModeForceOn);
        JCheckBox cbImpsCompetitorHop = new JCheckBox("Imps: wereld-hop bij andere imp-jager");
        cbImpsCompetitorHop.setFont(FONT_LABEL);
        cbImpsCompetitorHop.setBackground(BG_SECTION);
        cbImpsCompetitorHop.setForeground(TEXT);
        cbImpsCompetitorHop.setSelected(r.impsCompetitorWorldHopEnabled);
        cbImpsCompetitorHop.setToolTipText(
                "Alleen op Karamja. Vereist ook de globale vink \"Wereld-hop bij andere imp-jager\" in Imps Mode.");
        cbImpsCompetitorHop.setAlignmentX(Component.LEFT_ALIGNMENT);
        cbImpsCompetitorHop.setBorder(new EmptyBorder(0, 2, 4, 2));
        perAccountSkillBlock.add(cbImpsCompetitorHop);
        AccountSkillBlock blkImps = buildAccountSkillCenterBlock("Imps (Karamja)", stImps, null);
        perAccountSkillBlock.add(blkImps.root);

        centers.add(perAccountSkillBlock);

        // "Giants — in rotatie" hoort niet onder het per-account skillblok thuis: dat blok wordt
        // verborgen als "Centers: alleen globale lijsten" AAN staat, terwijl Giants-rotatie ook
        // onafhankelijk van dat toggle moet kunnen worden aan/uit gezet. Vandaar buiten het blok.
        String prevGiantsOvSnapshot = r.giantsModeOverride != null ? r.giantsModeOverride.trim() : "";
        JCheckBox cbGiantsInRotation = new JCheckBox("Giants — in rotatie");
        cbGiantsInRotation.setFont(FONT_LABEL);
        cbGiantsInRotation.setBackground(BG_DARK);
        cbGiantsInRotation.setForeground(TEXT);
        cbGiantsInRotation.setToolTipText("Uit = Giants nooit in rotatie voor dit account. Aan = Giants wisselt mee (onafhankelijk van de globale Giants-tab).");
        {
            String gOv = prevGiantsOvSnapshot;
            boolean giantsOff = "0".equals(gOv) || "false".equalsIgnoreCase(gOv) || "off".equalsIgnoreCase(gOv);
            cbGiantsInRotation.setSelected(!giantsOff);
        }
        cbGiantsInRotation.setAlignmentX(Component.LEFT_ALIGNMENT);
        cbGiantsInRotation.setBorder(new EmptyBorder(8, 4, 0, 0));
        centers.add(cbGiantsInRotation);

        // Per-account "📦 GE inkoop" blok (allow-list + caps).
        GeShopBlock geShopBlock = buildGeShopBlock(parent, r);
        centers.add(Box.createVerticalStrut(10));
        JSeparator sepGe = new JSeparator(SwingConstants.HORIZONTAL);
        sepGe.setForeground(new Color(60, 64, 80));
        sepGe.setMaximumSize(new Dimension(Integer.MAX_VALUE, 8));
        centers.add(sepGe);
        centers.add(Box.createVerticalStrut(4));
        centers.add(geShopBlock.root);

        // Per-account "📜 Chronicle teleport (Diango)" toggle.
        centers.add(Box.createVerticalStrut(10));
        JSeparator sepChron = new JSeparator(SwingConstants.HORIZONTAL);
        sepChron.setForeground(new Color(60, 64, 80));
        sepChron.setMaximumSize(new Dimension(Integer.MAX_VALUE, 8));
        centers.add(sepChron);

        String chronicleHelpHtml = "<p>Aan: gebruik <b>Chronicle</b> (boek van Diango) om naar Varrock te teleporteren "
                + "in plaats van de Magic 25 Varrock-spell. Werkt zowel met chronicle in <b>inventory</b> als "
                + "<b>equipped</b> (rechtermuisknop → Teleport).</p>"
                + "<p>De bot houdt zelf in de account-JSON bij hoeveel <b>charges</b> er nog op staan en "
                + "hoeveel losse <b>Teleport cards</b> in inv/bank liggen — zo hoeft hij niet onnodig te "
                + "banken om dat te checken. Charge-decrement gebeurt na elke teleport-actie.</p>"
                + "<p><b>Volgende update</b>: bot kan zelf naar Diango lopen om 1 chronicle + cards te kopen "
                + "(3–10 cards normaal, 10–30 als coins ≥ 100k).</p>";
        JPanel chronHead = accountSectionHeader(
                "📜 Chronicle teleport (Diango)",
                createAccountInfoButton(parent, "Chronicle teleport", chronicleHelpHtml));
        chronHead.setBorder(new EmptyBorder(4, 0, 4, 0));
        centers.add(chronHead);

        JCheckBox cbChronicle = new JCheckBox("Gebruik Chronicle in plaats van Magic 25 Varrock-tp");
        cbChronicle.setFont(FONT_LABEL);
        cbChronicle.setBackground(BG_DARK);
        cbChronicle.setForeground(TEXT);
        cbChronicle.setSelected(r.useChronicleForVarrock);
        cbChronicle.setAlignmentX(Component.LEFT_ALIGNMENT);
        cbChronicle.setBorder(new EmptyBorder(2, 4, 2, 0));
        cbChronicle.setToolTipText(
                "Aan: bot teleport via chronicle (rechts-klik → Teleport, in inv of equipped). "
                        + "Uit: gewone Magic-25 Varrock-tp / walk.");
        centers.add(cbChronicle);

        // Toon huidige bekende chronicle-state uit JSON, zodat de user weet of de bot al weet
        // hoeveel charges/cards er liggen of dat er nog gesynced moet worden.
        AccountStateJsonStore.AccountEntry chronEntry = AccountStateJsonStore.getEntry(r.displayName);
        String chronStatusText;
        if (chronEntry == null) {
            chronStatusText = "Status: nog geen JSON-entry voor dit account.";
        } else {
            chronStatusText = String.format(
                    "Status (uit JSON): chronicle=%s | charges=%d | cards inv=%d | cards bank=%d",
                    chronEntry.chronicleOwned ? "ja" : "nee",
                    Math.max(0, chronEntry.chronicleCharges),
                    Math.max(0, chronEntry.chronicleCardsInv),
                    Math.max(0, chronEntry.chronicleCardsBank));
        }
        JLabel chronStatus = new JLabel(chronStatusText);
        chronStatus.setFont(new Font("SansSerif", Font.PLAIN, 11));
        chronStatus.setForeground(TEXT_DIM);
        chronStatus.setAlignmentX(Component.LEFT_ALIGNMENT);
        chronStatus.setBorder(new EmptyBorder(0, 6, 0, 0));
        centers.add(chronStatus);

        syncPerAccountBlocks.run();

        JScrollPane centersScroll = new JScrollPane(centers);
        centersScroll.setBorder(null);
        centersScroll.getViewport().setBackground(BG_DARK);
        centersScroll.getVerticalScrollBar().setUnitIncrement(16);
        // Vaste minimum hoogte zodat ALLE accounts dezelfde dialooggrootte krijgen, ook
        // wanneer "Centers: alleen globale lijsten" aan staat en het skillblok verborgen is.
        // Anders scaalt JOptionPane elke keer een andere hoogte op basis van de zichtbare content.
        centersScroll.setPreferredSize(new Dimension(880, 380));
        centersScroll.setMinimumSize(new Dimension(640, 200));
        form.add(centersScroll, BorderLayout.CENTER);

        // Vaste preferred-size voor de hele form. Voorkomt dat het venster verschilt per account
        // (bv. klein bij accounts zonder per-account skill-overrides, groot bij accounts met alles
        // ingevuld). Resizable dialog (zie hieronder) staat nog steeds toe dat de user 'm aanpast.
        form.setPreferredSize(new Dimension(920, 720));
        form.setMinimumSize(new Dimension(720, 520));

        // Manueel JOptionPane bouwen i.p.v. showConfirmDialog → we kunnen de dialog resizable maken,
        // 'm relatief aan parent positioneren en de vaste preferred-size respecteren.
        JOptionPane optionPane = new JOptionPane(
                form,
                JOptionPane.PLAIN_MESSAGE,
                JOptionPane.OK_CANCEL_OPTION);
        JDialog editDialog = optionPane.createDialog(parent, "Account bewerken");
        editDialog.setResizable(true);
        editDialog.pack();
        // Zet de minimum size NA pack zodat OS-window-decoraties al meegerekend zijn.
        editDialog.setMinimumSize(new Dimension(720, 520));
        if (parent != null) {
            editDialog.setLocationRelativeTo(parent);
        }
        editDialog.setVisible(true);
        editDialog.dispose();
        Object selVal = optionPane.getValue();
        if (selVal == null || selVal == JOptionPane.UNINITIALIZED_VALUE || !isJOptionPaneOkValue(selVal)) {
            return false;
        }
        r.displayName = fName.getText().trim();
        r.characterId = fChar.getText().trim();
        r.sessionId = fSess.getText().trim();
        r.notes = fNotes.getText().trim();
        r.targetAttackLevel = parseNonNegativeInt(fAttTarget.getText());
        r.targetStrengthLevel = parseNonNegativeInt(fStrTarget.getText());
        r.targetDefenceLevel = parseNonNegativeInt(fDefTarget.getText());
        Object meleePrioSelected = meleePriorityCombo.getSelectedItem();
        if (meleePrioSelected instanceof MeleePriorityOption) {
            r.targetMeleePriority = ((MeleePriorityOption) meleePrioSelected).key;
        } else {
            r.targetMeleePriority = "";
        }
        r.calibrateBankOnNextLogin = cbCalibrateBank.isSelected();
        r.magicAutoUpdate = cbMagicAutoUpdate.isSelected();
        r.useGlobalCenterListsOnly = cbGlobalCenterListsOnly.isSelected();
        if (!r.useGlobalCenterListsOnly) {
            Object ssobj = startSkillAccountCombo.getSelectedItem();
            if (ssobj instanceof CombatBotConfig.StartSkill) {
                r.startSkillOverride = ((CombatBotConfig.StartSkill) ssobj).name();
            } else {
                r.startSkillOverride = "";
            }
        } else {
            r.startSkillOverride = "";
        }
        if (impsStyleCombo.getSelectedIndex() <= 0) {
            r.impsCombatStyleOverride = "";
        } else {
            r.impsCombatStyleOverride = Objects.requireNonNull(impsStyleCombo.getSelectedItem()).toString();
        }
        if (giantsCombatCombo.getSelectedIndex() <= 0) {
            r.giantsCombatStyleOverride = "";
        } else {
            r.giantsCombatStyleOverride = Objects.requireNonNull(giantsCombatCombo.getSelectedItem()).toString();
        }
        Object ammoSel = rangedAmmoCombo.getSelectedItem();
        r.rangedAmmoType = ManagedJagexAccountsStore.rangedAmmoFromUiLabel(
                ammoSel != null ? ammoSel.toString() : "");
        if (!cbGiantsInRotation.isSelected()) {
            r.giantsModeOverride = "0";
        } else if ("1".equals(prevGiantsOvSnapshot) || "true".equalsIgnoreCase(prevGiantsOvSnapshot)
                || "on".equalsIgnoreCase(prevGiantsOvSnapshot)) {
            r.giantsModeOverride = "1";
        } else {
            r.giantsModeOverride = "";
        }
        // Aan/uit checkbox semantiek: aangevinkt = forceer Imps Mode aan voor dit account ("1");
        // uitgevinkt = geen override ("") zodat het Imps-centers blok / globale instellingen weer leiden.
        r.impsModeOverride = cbImpsModeForceOn.isSelected() ? "1" : "";
        r.impsCompetitorWorldHopEnabled = cbImpsCompetitorHop.isSelected();
        if (r.useGlobalCenterListsOnly) {
            r.rotationUseCustomProfile = false;
        } else {
            r.rotationUseCustomProfile = true;
        }
        blkCombat.syncUiToState(config);
        blkWc.syncUiToState(config);
        blkMine.syncUiToState(config);
        blkFish.syncUiToState(config);
        blkImps.syncUiToState(config);
        applyAccountCenterUiState(stCombat, r, (row, s) -> row.combatCenters = s, (row, u) -> row.useGlobalCombatCenters = u);
        applyAccountCenterUiState(stWc, r, (row, s) -> row.wcCenters = s, (row, u) -> row.useGlobalWcCenters = u);
        r.wcCenterBehaviorsBlob = stWc.behaviorsBlob != null ? stWc.behaviorsBlob : "";
        applyAccountCenterUiState(stMine, r, (row, s) -> row.miningCenters = s, (row, u) -> row.useGlobalMiningCenters = u);
        r.miningCenterBehaviorsBlob = stMine.behaviorsBlob != null ? stMine.behaviorsBlob : "";
        applyAccountCenterUiState(stFish, r, (row, s) -> row.fishingCenters = s, (row, u) -> row.useGlobalFishingCenters = u);
        r.fishingCenterBehaviorsBlob = stFish.behaviorsBlob != null ? stFish.behaviorsBlob : "";
        applyAccountCenterUiState(stImps, r, (row, s) -> row.impsCenters = s, (row, u) -> row.useGlobalImpsCenters = u);
        if (r.rotationUseCustomProfile) {
            r.rotationPickCombat = r.useGlobalCombatCenters;
            r.rotationPickWc = r.useGlobalWcCenters;
            r.rotationPickMining = r.useGlobalMiningCenters;
            r.rotationPickFishing = r.useGlobalFishingCenters;
            r.rotationPickImps = r.useGlobalImpsCenters;
            r.rotationPickGiants = cbGiantsInRotation.isSelected();
            r.impsGiantsFocus = "";
        }
        r.accountSwitchWorldHopEnabled = cbWorldHop.isSelected();
        r.accountSwitchWorld = fWorld.getText().trim();
        geShopBlock.applyToRow(r);
        r.useChronicleForVarrock = cbChronicle.isSelected();
        // Spiegel toggle naar JSON zodat handlers/helpers het ook zonder ManagedRow lookup weten.
        if (r.displayName != null && !r.displayName.trim().isEmpty()) {
            AccountStateJsonStore.setChronicleTeleportEnabled(r.displayName.trim(), r.useChronicleForVarrock);
        }
        return true;
    }

    private static String formatDateTime(long epochMs) {
        if (epochMs <= 0) {
            return "-";
        }
        return Instant.ofEpochMilli(epochMs)
                .atZone(ZoneId.systemDefault())
                .format(DATE_TIME_FMT);
    }

    private static String formatAntiBanSummary(AccountStateJsonStore.AccountEntry e) {
        if (e == null || e.antiBanActionsTotal <= 0) {
            return "nog geen acties";
        }
        String top = "-";
        if (e.antiBanActionCounts != null && !e.antiBanActionCounts.isEmpty()) {
            top = e.antiBanActionCounts.entrySet().stream()
                    .sorted((a, b) -> Integer.compare(Math.max(0, b.getValue()), Math.max(0, a.getValue())))
                    .limit(3)
                    .map(en -> en.getKey() + "=" + Math.max(0, en.getValue()))
                    .collect(java.util.stream.Collectors.joining(", "));
        }
        return e.antiBanActionsTotal + " totaal | top: " + top;
    }

    private static String formatAntiBanLastAction(AccountStateJsonStore.AccountEntry e) {
        if (e == null || e.antiBanLastAction == null || e.antiBanLastAction.trim().isEmpty()) {
            return "-";
        }
        String when = e.antiBanLastActionMs > 0 ? formatDateTime(e.antiBanLastActionMs) : "-";
        return e.antiBanLastAction + " (" + when + ")";
    }

    private static String formatBankSnapshotSummary(AccountStateJsonStore.AccountEntry e) {
        if (e == null || !e.bankCalibrated) {
            return "nog niet gekalibreerd";
        }
        int trackedItems = AccountStateJsonStore.knownBankQtyMap(e).size();
        long totalCoins = AccountStateJsonStore.knownCoinsApprox(e);
        String rsn = e.displayName != null ? e.displayName : "";
        int equipped = EquipmentSnapshotPlanner.equippedSlotCount(rsn);
        if (equipped <= 0) {
            equipped = AccountStateJsonStore.knownEquippedQtyMap(e).size();
        }
        String wornBrief = EquipmentSnapshotPlanner.formatEquippedSlotsBrief(rsn);
        return "bank=" + Math.max(0L, e.knownBankCoins)
                + " gp, inv=" + Math.max(0L, e.knownInventoryCoins)
                + " gp, items=" + trackedItems
                + ", totaal≈" + totalCoins + " gp"
                + " | worn=" + equipped + " (" + wornBrief + ")";
    }

    /** Tijdelijke UI-state voor subset per skill in account-dialoog. */
    private static final class AccountCenterUiState {
        String masterBlob = "";
        String accountSubsetBlob = "";
        /** Skill actief (na {@link AccountSkillBlock#syncUiToState()}). */
        boolean mainOn;
        /** Checkbox: volledige globale lijst i.p.v. eigen locaties. */
        boolean useGlobalFullList;
        /** Per-tile drop/bank (+ FM/cook) overrides — {@link AccountCenterBehaviorStore}. */
        String behaviorsBlob = "";
    }

    private static AccountCenterUiState accountCenterStateFromRow(String master, String centersBlob,
            boolean useGlobalCenters) {
        AccountCenterUiState st = new AccountCenterUiState();
        st.masterBlob = master != null ? master : "";
        st.accountSubsetBlob = centersBlob != null ? centersBlob : "";
        List<CenterManager.Center> masterList = CenterManager.parse(st.masterBlob);
        if (!useGlobalCenters) {
            st.mainOn = false;
            st.useGlobalFullList = false;
            return st;
        }
        if (masterList.isEmpty()) {
            // Geen master-centers; alleen "gebruik globale lijst" maakt nog zin.
            st.mainOn = true;
            st.useGlobalFullList = true;
            return st;
        }
        String sub = st.accountSubsetBlob.trim();
        if (sub.isEmpty()) {
            // useGlobal=true zonder subset = expliciete keuze "gebruik globale lijst".
            st.mainOn = true;
            st.useGlobalFullList = true;
            return st;
        }
        // Belangrijk: NIET auto-collapsen naar "gebruik globale lijst" wanneer de subset toevallig
        // alle masters dekt. Dat zou ervoor zorgen dat de user-UI bij OK + heropenen anders staat
        // dan wat de user zelf had aangevinkt. Houd subset-modus aan; user heeft expliciet
        // individuele locaties gekozen.
        st.mainOn = true;
        st.useGlobalFullList = false;
        return st;
    }

    private static String impsMasterCentersForAccountEditor(String currentConfigCenters, String accountSubsetCenters) {
        String cur = currentConfigCenters != null ? currentConfigCenters.trim() : "";
        if (CenterManager.countActive(cur) > 0) {
            return currentConfigCenters;
        }
        String subset = accountSubsetCenters != null ? accountSubsetCenters.trim() : "";
        if (CenterManager.countActive(subset) > 0) {
            return accountSubsetCenters;
        }
        // applyManagedCentersForDisplayName() mag impsCenters tijdelijk leeg zetten voor een account
        // waar Imps uit staat. Het accountvenster mag die runtime-leegte niet als "er bestaan geen
        // Imps centers" behandelen, anders springt de Imps-vink bij heropenen terug uit.
        return DEFAULT_IMPS_CENTERS;
    }

    private static final class AccountLocationRowUi {
        final CenterManager.Center center;
        final JCheckBox locCb;
        final JCheckBox dropCb;
        final JCheckBox extraCb;

        AccountLocationRowUi(CenterManager.Center center, JCheckBox locCb, JCheckBox dropCb, JCheckBox extraCb) {
            this.center = center;
            this.locCb = locCb;
            this.dropCb = dropCb;
            this.extraCb = extraCb;
        }
    }

    private static final class AccountSkillBlock {
        final AccountCenterUiState st;
        final AccountCenterBehaviorStore.SkillKind behaviorKind;
        final JCheckBox useGlobalListCb;
        final AccountLocationRowUi[] locationRows;
        final JLabel statusLbl;
        final JPanel locPanel;
        final JPanel root;

        AccountSkillBlock(AccountCenterUiState st, AccountCenterBehaviorStore.SkillKind behaviorKind,
                JCheckBox useGlobalListCb, AccountLocationRowUi[] locationRows,
                JLabel statusLbl, JPanel locPanel, JPanel root) {
            this.st = st;
            this.behaviorKind = behaviorKind;
            this.useGlobalListCb = useGlobalListCb;
            this.locationRows = locationRows;
            this.statusLbl = statusLbl;
            this.locPanel = locPanel;
            this.root = root;
        }

        void refreshStatus() {
            List<CenterManager.Center> master = CenterManager.parse(st.masterBlob);
            if (master.isEmpty()) {
                statusLbl.setText("geen centers");
                return;
            }
            if (useGlobalListCb.isSelected()) {
                statusLbl.setText("globaal (" + master.size() + " loc.)");
                return;
            }
            int n = 0;
            for (AccountLocationRowUi row : locationRows) {
                if (row.locCb.isSelected()) {
                    n++;
                }
            }
            if (n == 0) {
                statusLbl.setText("(uit)");
            } else if (n == master.size()) {
                statusLbl.setText("alle " + master.size() + " loc.");
            } else {
                statusLbl.setText(n + "/" + master.size() + " loc.");
            }
        }

        void syncUiToState(CombatBotConfig cfg) {
            List<CenterManager.Center> master = CenterManager.parse(st.masterBlob);
            boolean global = useGlobalListCb.isSelected();
            st.useGlobalFullList = global;
            if (master.isEmpty()) {
                st.mainOn = global;
                st.accountSubsetBlob = "";
                syncBehaviorsBlob(cfg, master, global);
                return;
            }
            if (global) {
                st.accountSubsetBlob = "";
                st.mainOn = true;
                syncBehaviorsBlob(cfg, master, true);
                return;
            }
            boolean[] sel = new boolean[locationRows.length];
            for (int i = 0; i < locationRows.length; i++) {
                sel[i] = locationRows[i].locCb.isSelected();
            }
            String built = CenterManager.buildSubsetFromSelection(st.masterBlob, sel);
            int picked = CenterManager.parse(built).size();
            st.mainOn = picked > 0;
            st.accountSubsetBlob = st.mainOn ? built : "";
            syncBehaviorsBlob(cfg, master, false);
        }

        private void syncBehaviorsBlob(CombatBotConfig cfg, List<CenterManager.Center> master, boolean globalList) {
            if (behaviorKind == null || cfg == null || master == null || master.isEmpty()) {
                st.behaviorsBlob = "";
                return;
            }
            AccountCenterBehaviorStore.CenterBehavior global =
                    AccountCenterBehaviorStore.globalDefaults(behaviorKind, cfg);
            Map<String, AccountCenterBehaviorStore.CenterBehavior> map =
                    new LinkedHashMap<>(AccountCenterBehaviorStore.parse(st.behaviorsBlob));
            for (int i = 0; i < master.size() && i < locationRows.length; i++) {
                AccountLocationRowUi row = locationRows[i];
                CenterManager.Center c = master.get(i);
                if (c == null || c.point == null) {
                    continue;
                }
                boolean active = globalList || row.locCb.isSelected();
                if (!active) {
                    continue;
                }
                boolean drop = row.dropCb.isSelected();
                boolean extra = row.extraCb != null && row.extraCb.isSelected();
                String key = AccountCenterBehaviorStore.tileKey(c.point);
                if (drop == global.drop && extra == global.extra) {
                    map.remove(key);
                } else {
                    map.put(key, new AccountCenterBehaviorStore.CenterBehavior(drop, extra, true));
                }
            }
            st.behaviorsBlob = AccountCenterBehaviorStore.serialize(map);
        }
    }

    private AccountSkillBlock buildAccountSkillCenterBlock(String skillLabel, AccountCenterUiState st,
            AccountCenterBehaviorStore.SkillKind behaviorKind) {
        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBackground(BG_DARK);
        root.setAlignmentX(Component.LEFT_ALIGNMENT);
        root.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(46, 50, 64), 1),
                new EmptyBorder(6, 8, 8, 8)));

        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setOpaque(false);
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));

        JCheckBox useGlobalListCb = new JCheckBox(skillLabel + " — gebruik globale lijst");
        useGlobalListCb.setSelected(st.mainOn && st.useGlobalFullList);
        useGlobalListCb.setFont(FONT_LABEL);
        useGlobalListCb.setBackground(BG_DARK);
        useGlobalListCb.setForeground(TEXT);
        boolean impsKaramjaRow = "Imps (Karamja)".equals(skillLabel);
        useGlobalListCb.setToolTipText(impsKaramjaRow
                ? "Aan: exact de center-lijst van de tab Centers → sectie 🎯 Imps (Karamja) → Imps hunting centers "
                        + "(dezelfde locaties als in je plugin-instellingen). Uit: vink daarvan een deel aan voor dit account."
                : "Aan: alle locaties van de tab Centers voor deze skill. Uit: vink hieronder welke locaties dit account gebruikt (minstens één = aan).");

        JLabel statusLbl = new JLabel();
        statusLbl.setFont(FONT_LABEL);
        statusLbl.setForeground(TEXT_DIM);

        JPanel statusWrap = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        statusWrap.setOpaque(false);
        statusWrap.add(statusLbl);

        header.add(useGlobalListCb, BorderLayout.WEST);
        header.add(statusWrap, BorderLayout.EAST);
        root.add(header);

        List<CenterManager.Center> master = CenterManager.parse(st.masterBlob);
        if (master.isEmpty()) {
            String emptyHtml = impsKaramjaRow
                    ? "<html><div style='width:340px;color:#a8a8c0'>Nog geen Imps-locaties in de config. Ga naar de tab "
                    + "<b>Centers</b>, open <b>🎯 Imps (Karamja)</b> en vul <b>Imps hunting centers</b> in — "
                    + "met <b>gebruik globale lijst</b> aan gebruikt dit account precies die lijst.</div></html>"
                    : "<html><div style='width:320px;color:#a8a8c0'>Nog geen centers voor deze skill op de tab "
                    + "<b>Centers</b>. Voeg daar eerst locaties toe.</div></html>";
            JLabel emptyLbl = new JLabel(emptyHtml);
            emptyLbl.setFont(FONT_LABEL);
            emptyLbl.setForeground(TEXT_DIM);
            emptyLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
            emptyLbl.setBorder(new EmptyBorder(0, 2, 6, 2));
            root.add(emptyLbl);
        }

        JPanel locPanel = new JPanel();
        locPanel.setLayout(new BoxLayout(locPanel, BoxLayout.Y_AXIS));
        locPanel.setOpaque(true);
        locPanel.setBackground(new Color(34, 36, 46));
        locPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(52, 56, 70)),
                new EmptyBorder(6, 18, 4, 4)));
        locPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        boolean showBehaviorCols = behaviorKind != null;
        if (showBehaviorCols && !master.isEmpty()) {
            JPanel hdr = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            hdr.setOpaque(false);
            hdr.setAlignmentX(Component.LEFT_ALIGNMENT);
            hdr.add(Box.createHorizontalStrut(4));
            JLabel hLoc = new JLabel("Locatie");
            hLoc.setFont(FONT_LABEL);
            hLoc.setForeground(TEXT_DIM);
            hdr.add(hLoc);
            hdr.add(Box.createHorizontalStrut(72));
            JLabel hDrop = new JLabel("Drop");
            hDrop.setFont(FONT_LABEL);
            hDrop.setForeground(TEXT_DIM);
            hDrop.setToolTipText("Aan = droppen; uit = banken (overschrijft globale skill-vink voor dit account op deze tile).");
            hdr.add(hDrop);
            if (behaviorKind != AccountCenterBehaviorStore.SkillKind.MINING) {
                String extraLab = behaviorKind == AccountCenterBehaviorStore.SkillKind.FISHING ? "Cook" : "FM";
                JLabel hExtra = new JLabel(extraLab);
                hExtra.setFont(FONT_LABEL);
                hExtra.setForeground(TEXT_DIM);
                hdr.add(hExtra);
            }
            locPanel.add(hdr);
        }

        Map<String, AccountCenterBehaviorStore.CenterBehavior> parsedBehaviors =
                behaviorKind != null
                        ? AccountCenterBehaviorStore.parse(st.behaviorsBlob)
                        : Collections.emptyMap();
        AccountCenterBehaviorStore.CenterBehavior globalBeh = behaviorKind != null
                ? AccountCenterBehaviorStore.globalDefaults(behaviorKind, config)
                : AccountCenterBehaviorStore.CenterBehavior.inherit();

        AccountLocationRowUi[] locationRows = new AccountLocationRowUi[master.size()];
        boolean[] mask;
        if (!st.mainOn) {
            mask = new boolean[master.size()];
        } else if (st.useGlobalFullList) {
            mask = new boolean[master.size()];
            Arrays.fill(mask, true);
        } else {
            mask = CenterManager.selectionMaskFromSubset(st.masterBlob, st.accountSubsetBlob);
        }

        for (int i = 0; i < master.size(); i++) {
            CenterManager.Center c = master.get(i);
            String lab = (c.name != null && !c.name.isEmpty()) ? c.name
                    : ("(" + c.point.getX() + "," + c.point.getY() + ")");
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            row.setOpaque(false);
            row.setAlignmentX(Component.LEFT_ALIGNMENT);

            JCheckBox locCb = new JCheckBox(lab + "  r=" + c.radius);
            locCb.setSelected(mask[i]);
            locCb.setFont(FONT_LABEL);
            locCb.setBackground(new Color(34, 36, 46));
            locCb.setForeground(TEXT);

            JCheckBox dropCb = null;
            JCheckBox extraCb = null;
            if (showBehaviorCols && c.point != null) {
                String key = AccountCenterBehaviorStore.tileKey(c.point);
                AccountCenterBehaviorStore.CenterBehavior b = parsedBehaviors.get(key);
                boolean dropVal = b != null && b.explicit ? b.drop : globalBeh.drop;
                boolean extraVal = b != null && b.explicit ? b.extra : globalBeh.extra;
                dropCb = new JCheckBox("Drop");
                dropCb.setSelected(dropVal);
                dropCb.setFont(FONT_LABEL);
                dropCb.setBackground(new Color(34, 36, 46));
                dropCb.setForeground(TEXT);
                dropCb.setToolTipText("Aan = droppen; uit = banken op dit center voor dit account.");
                if (behaviorKind != AccountCenterBehaviorStore.SkillKind.MINING) {
                    String extraLab = behaviorKind == AccountCenterBehaviorStore.SkillKind.FISHING
                            ? "Cook" : "FM";
                    extraCb = new JCheckBox(extraLab);
                    extraCb.setSelected(extraVal);
                    extraCb.setFont(FONT_LABEL);
                    extraCb.setBackground(new Color(34, 36, 46));
                    extraCb.setForeground(TEXT);
                    extraCb.setToolTipText(behaviorKind == AccountCenterBehaviorStore.SkillKind.FISHING
                            ? "Vis koken op vuur voor dit account op dit center."
                            : "Firemaking / bonfire op dit center voor dit account.");
                }
            }

            row.add(locCb);
            if (dropCb != null) {
                row.add(dropCb);
            }
            if (extraCb != null) {
                row.add(extraCb);
            }
            locationRows[i] = new AccountLocationRowUi(c, locCb, dropCb, extraCb);
            locPanel.add(row);
        }

        AccountSkillBlock block = new AccountSkillBlock(st, behaviorKind, useGlobalListCb, locationRows,
                statusLbl, locPanel, root);

        Runnable refreshAll = () -> {
            boolean hideLocChecks = useGlobalListCb.isSelected();
            for (AccountLocationRowUi row : locationRows) {
                row.locCb.setVisible(!hideLocChecks);
                if (hideLocChecks) {
                    row.locCb.setSelected(true);
                }
            }
            locPanel.setVisible(!master.isEmpty());
            block.refreshStatus();
        };

        useGlobalListCb.addActionListener(e -> refreshAll.run());
        for (AccountLocationRowUi row : locationRows) {
            row.locCb.addActionListener(e -> refreshAll.run());
        }

        root.add(locPanel);
        refreshAll.run();

        return block;
    }

    private void applyAccountCenterUiState(AccountCenterUiState st,
            ManagedJagexAccountsStore.ManagedJagexAccountRow r,
            java.util.function.BiConsumer<ManagedJagexAccountsStore.ManagedJagexAccountRow, String> setSubset,
            java.util.function.BiConsumer<ManagedJagexAccountsStore.ManagedJagexAccountRow, Boolean> setUseGlobal) {
        List<CenterManager.Center> master = CenterManager.parse(st.masterBlob);
        if (!st.mainOn) {
            setSubset.accept(r, "");
            setUseGlobal.accept(r, false);
            return;
        }
        if (master.isEmpty()) {
            setSubset.accept(r, "");
            setUseGlobal.accept(r, false);
            return;
        }
        if (st.accountSubsetBlob == null || st.accountSubsetBlob.trim().isEmpty()) {
            setSubset.accept(r, "");
            setUseGlobal.accept(r, true);
            return;
        }
        if (CenterManager.countActive(st.accountSubsetBlob) == 0) {
            setSubset.accept(r, "");
            setUseGlobal.accept(r, false);
            return;
        }
        setSubset.accept(r, st.accountSubsetBlob);
        setUseGlobal.accept(r, true);
    }

    private static void addFormRow(JPanel form, String label, JTextField field) {
        field.setForeground(Color.WHITE);
        field.setBackground(new Color(35, 37, 48));
        form.add(new JLabel(label));
        form.add(field);
    }

    private static int parseNonNegativeInt(String raw) {
        try {
            return Math.max(0, Integer.parseInt(raw == null ? "0" : raw.trim()));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /**
     * Beschikbare keuzes voor het account-veld "Melee prioriteit (volgorde)".
     * De {@code key} wordt opgeslagen op {@link ManagedJagexAccountsStore.ManagedJagexAccountRow#targetMeleePriority}
     * en gelezen door {@code CombatBotPlugin#pickMeleeStyleByPriority}.
     */
    private enum MeleePriorityOption {
        ATT_STR_DEF("ATT_STR_DEF", "Att → Str → Def (klassiek)"),
        STR_ATT_DEF("STR_ATT_DEF", "Str → Att → Def (Strength eerst)"),
        DEF_ATT_STR("DEF_ATT_STR", "Def → Att → Str (Defence eerst)"),
        LOWEST_FIRST("LOWEST_FIRST", "Laagste lvl eerst (absoluut)"),
        LOWEST_PCT_FIRST("LOWEST_PCT_FIRST", "Laagste % onder target eerst");

        final String key;
        final String label;

        MeleePriorityOption(String key, String label) {
            this.key = key;
            this.label = label;
        }

        static MeleePriorityOption fromKey(String key) {
            if (key == null || key.trim().isEmpty()) return ATT_STR_DEF;
            String norm = key.trim().toUpperCase();
            for (MeleePriorityOption o : values()) {
                if (o.key.equals(norm)) return o;
            }
            return ATT_STR_DEF;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    // ===================== DEBUG TAB =====================

    private static void ensureLogFileExistsForOpen(File f) throws java.io.IOException {
        if (!f.exists()) {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            Files.write(f.toPath(), new byte[0]);
        }
    }

    /** Opent {@link DebugLog#getLogFilePath()} in de default app (meestal editor). */
    private void openDebugLogFileForExternalShare() {
        try {
            File logFile = DebugLog.getLogFilePath();
            ensureLogFileExistsForOpen(logFile);
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(logFile);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    /** Opent {@link DebugLog#getMlClicksFilePath()} (alleen regels als ML-log aan staat). */
    private void openMlClicksFileForExternalShare() {
        try {
            File f = DebugLog.getMlClicksFilePath();
            ensureLogFileExistsForOpen(f);
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(f);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Korte status-tekst voor het human-profile in de Widget inspector header.
     * Toont: niet aanwezig | leeftijd + sample-grootte + medianen.
     */
    private String humanProfileStatusText() {
        HumanProfile hp = HumanProfile.getOrLoad();
        if (hp == null) {
            return "Profiel: (geen — klik 'Bouw human-profiel')";
        }
        long ageMs = Math.max(0, System.currentTimeMillis() - hp.generatedAtMs);
        long ageMin = ageMs / 60_000L;
        String age;
        if (ageMin < 1) age = "<1m oud";
        else if (ageMin < 60) age = ageMin + "m oud";
        else if (ageMin < 1440) age = (ageMin / 60) + "h oud";
        else age = (ageMin / 1440) + "d oud";
        double moveDt = hp.moveDtMs != null ? hp.moveDtMs.p50 : 0.0;
        double dwell = hp.pressReleaseDwellMs != null ? hp.pressReleaseDwellMs.p50 : 0.0;
        double clickGap = hp.interClickGapMs != null ? hp.interClickGapMs.p50 : 0.0;
        return String.format("Profiel: %s · samples=%d · moveDt p50=%.0fms · dwell p50=%.0fms · clickGap p50=%.0fms",
                age, hp.usedSamples, moveDt, dwell, clickGap);
    }

    /** Opent {@link DebugLog#getMouseTraceFilePath()} (move/click/drag trace, NDJSON). */
    private void openMouseTraceFileForExternalShare() {
        try {
            File f = DebugLog.getMouseTraceFilePath();
            ensureLogFileExistsForOpen(f);
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(f);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    /** Opent {@link DebugLog#getWalkTilesFilePath()} (auto-log van walk-clicks/traversals). */
    private void openWalkTilesFileForExternalShare() {
        try {
            File f = DebugLog.getWalkTilesFilePath();
            ensureLogFileExistsForOpen(f);
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(f);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Logt de top-{@code n} click- en pad-tiles naar de Debug-tab onder bron <b>WalkHotspot</b>.
     * Werkt ook als de overlay zelf uitgeschakeld is, maar de aggregaten zijn dan vaak leeg.
     */
    private void dumpTopWalkHotspots(int n) {
        java.util.List<MovementHelper.WalkClickInfo> clicks = MovementHelper.topClickHotspots(n);
        java.util.List<MovementHelper.WalkClickInfo> paths = MovementHelper.topPathHotspots(n);
        java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add("─── Top " + n + " click-hotspots ───");
        if (clicks.isEmpty()) {
            lines.add("(geen click-tiles geregistreerd)");
        } else {
            int i = 1;
            for (MovementHelper.WalkClickInfo info : clicks) {
                lines.add(String.format("#%-2d (%4d,%4d,p%d) clicks=%d  traversals=%d",
                        i++, info.point.getX(), info.point.getY(), info.point.getPlane(),
                        info.count, info.traversals));
            }
        }
        lines.add("─── Top " + n + " path-hotspots ───");
        if (paths.isEmpty()) {
            lines.add("(geen pad-tiles geregistreerd)");
        } else {
            int i = 1;
            for (MovementHelper.WalkClickInfo info : paths) {
                lines.add(String.format("#%-2d (%4d,%4d,p%d) traversals=%d  clicks=%d",
                        i++, info.point.getX(), info.point.getY(), info.point.getPlane(),
                        info.traversals, info.count));
            }
        }
        DebugLog.logBlock("WalkHotspot", lines);
    }

    /**
     * Logt een complete diagnose van de walk-tile opslag naar bron <b>WalkClickDbg</b>:
     * totaal opgeslagen, in-scene vs out-of-scene, distance-histogram, en hint over wat te
     * verwachten als er niets rendert. Werkt op elke locatie — gebruik dit om uit te zoeken
     * waarom de overlay op een specifieke plek niets tekent.
     */
    private void diagnoseWalkTiles() {
        try {
            net.storm.api.domain.actors.IPlayer lp = net.storm.sdk.entities.Players.getLocal();
            net.runelite.api.coords.WorldPoint pos = lp != null ? lp.getWorldLocation() : null;
            java.util.List<String> lines = MovementHelper.diagnoseWalkTiles(pos);
            DebugLog.logBlock("WalkClickDbg", lines);
        } catch (Throwable ex) {
            DebugLog.log("WalkClickDbg", "Diagnose error: " + ex.getMessage());
        }
    }

    /** Schrijft een one-shot snapshot van alle walk-tile-aggregaten naar een nieuw JSONL bestand. */
    private void exportWalkTilesNow() {
        try {
            java.util.List<String> lines = MovementHelper.exportWalkTilesAsJsonl();
            if (lines.isEmpty()) {
                DebugLog.log("WalkHotspot", "Export: geen walk-tile data om te exporteren.");
                return;
            }
            File out = DebugLog.exportWalkTilesSnapshot(lines);
            if (out == null) {
                DebugLog.log("WalkHotspot", "Export FAILED — kon bestand niet schrijven.");
                return;
            }
            DebugLog.log("WalkHotspot", "Export OK: " + lines.size() + " tiles → " + out.getAbsolutePath());
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                File parent = out.getParentFile();
                if (parent != null && parent.exists()) {
                    Desktop.getDesktop().open(parent);
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
            DebugLog.log("WalkHotspot", "Export ERROR: " + ex.getMessage());
        }
    }

    /** Opent Verkenner / bestandsbeheer in {@code ~/.runelite/prive-logs} (beide logtypen staan hier). */
    private void openPriveLogsFolder() {
        try {
            File dir = DebugLog.getLogFilePath().getParentFile();
            if (dir != null) {
                if (!dir.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    dir.mkdirs();
                }
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    Desktop.getDesktop().open(dir);
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Debug-tab: één {@link BorderLayout}-paneel zonder extra buitenste scrollpane — anders blijft er een grijs
     * leeg vlak onder de controls (viewport groter dan de voorkeurs-hoogte van de inhoud).
     */
    private JPanel createDebugTab() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(BG_DARK);

        // Top controls: responsief in 2 rijen zodat alles zichtbaar blijft op smalle vensters
        JPanel controls = new JPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
        controls.setBackground(BG_DARK);
        controls.setAlignmentX(Component.LEFT_ALIGNMENT);
        controls.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        JPanel controlButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        controlButtons.setBackground(BG_DARK);
        controlButtons.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel checkToggles = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        checkToggles.setBackground(BG_DARK);
        checkToggles.setAlignmentX(Component.LEFT_ALIGNMENT);

        JCheckBox enableCb = new JCheckBox("Debug Log Aan");
        enableCb.setSelected(DebugLog.isEnabled());
        enableCb.setFont(FONT_LABEL);
        enableCb.setForeground(GREEN);
        enableCb.setBackground(BG_DARK);
        enableCb.addActionListener(e -> {
            DebugLog.setEnabled(enableCb.isSelected());
            refreshDebugLogViews();
        });
        checkToggles.add(enableCb);

        JCheckBox starterMeleeDbgCb = new JCheckBox("Starter melee-style debug");
        starterMeleeDbgCb.setSelected(config.starterMeleeStyleDebug());
        starterMeleeDbgCb.setFont(FONT_LABEL);
        starterMeleeDbgCb.setForeground(TEXT);
        starterMeleeDbgCb.setBackground(BG_DARK);
        starterMeleeDbgCb.addActionListener(e -> setConfig("starterMeleeStyleDebug", starterMeleeDbgCb.isSelected()));
        checkToggles.add(starterMeleeDbgCb);

        JCheckBox impsOpenerDbgCb = new JCheckBox("Imps melee-opener debug");
        impsOpenerDbgCb.setSelected(config.impsMeleeOpeningAirStrikeDebug());
        impsOpenerDbgCb.setFont(FONT_LABEL);
        impsOpenerDbgCb.setForeground(TEXT);
        impsOpenerDbgCb.setBackground(BG_DARK);
        impsOpenerDbgCb.addActionListener(e -> setConfig("impsMeleeOpeningAirStrikeDebug", impsOpenerDbgCb.isSelected()));
        checkToggles.add(impsOpenerDbgCb);

        JCheckBox walkClickDbgCb = new JCheckBox("Walk-klik tiles (overlay)");
        walkClickDbgCb.setSelected(config.debugWalkClickOverlay());
        walkClickDbgCb.setFont(FONT_LABEL);
        walkClickDbgCb.setForeground(TEXT);
        walkClickDbgCb.setBackground(BG_DARK);
        walkClickDbgCb.setToolTipText("<html>Master-switch voor walk-overlay (max. 2000 tiles, 24u TTL).<br>Hieronder per laag: click-tiles (x{n}) en pad-tiles (·{n}).</html>");
        walkClickDbgCb.addActionListener(e -> setConfig("debugWalkClickOverlay", walkClickDbgCb.isSelected()));
        checkToggles.add(walkClickDbgCb);

        JCheckBox walkClickShowClicksCb = new JCheckBox("└ Toon click-tiles (x{n})");
        walkClickShowClicksCb.setSelected(config.debugWalkOverlayShowClickTiles());
        walkClickShowClicksCb.setFont(FONT_LABEL);
        walkClickShowClicksCb.setForeground(TEXT);
        walkClickShowClicksCb.setBackground(BG_DARK);
        walkClickShowClicksCb.setToolTipText("<html>Tekent elke 'Walk here' klik (heatmap) met x{count} label.<br>Vereist master 'Walk-klik tiles' aan.</html>");
        walkClickShowClicksCb.addActionListener(e -> setConfig("debugWalkOverlayShowClickTiles", walkClickShowClicksCb.isSelected()));
        checkToggles.add(walkClickShowClicksCb);

        JCheckBox walkClickShowPathsCb = new JCheckBox("└ Toon pad-tiles (·{n})");
        walkClickShowPathsCb.setSelected(config.debugWalkOverlayShowPathTiles());
        walkClickShowPathsCb.setFont(FONT_LABEL);
        walkClickShowPathsCb.setForeground(TEXT);
        walkClickShowPathsCb.setBackground(BG_DARK);
        walkClickShowPathsCb.setToolTipText("<html>Tekent elke tile waar de speler overheen wandelt (groen).<br>Vereist master 'Walk-klik tiles' aan.</html>");
        walkClickShowPathsCb.addActionListener(e -> setConfig("debugWalkOverlayShowPathTiles", walkClickShowPathsCb.isSelected()));
        checkToggles.add(walkClickShowPathsCb);

        JCheckBox walkAutoLogCb = new JCheckBox("└ Auto-log → JSONL");
        walkAutoLogCb.setSelected(config.debugWalkAutoLogToDisk());
        walkAutoLogCb.setFont(FONT_LABEL);
        walkAutoLogCb.setForeground(TEXT);
        walkAutoLogCb.setBackground(BG_DARK);
        walkAutoLogCb.setToolTipText("<html>Schrijft elke walk-klik en pad-traversal naar<br><code>combat-bot-walk-tiles-YYYY-MM-DD.jsonl</code><br>Vereist master 'Walk-klik tiles' aan.</html>");
        walkAutoLogCb.addActionListener(e -> setConfig("debugWalkAutoLogToDisk", walkAutoLogCb.isSelected()));
        checkToggles.add(walkAutoLogCb);

        JCheckBox walkHotspotCb = new JCheckBox("└ Hotspot-stuck waarschuwing");
        walkHotspotCb.setSelected(config.debugWalkStuckHotspotEnabled());
        walkHotspotCb.setFont(FONT_LABEL);
        walkHotspotCb.setForeground(TEXT);
        walkHotspotCb.setBackground(BG_DARK);
        walkHotspotCb.setToolTipText("<html>Logt waarschuwing in Debug-tab als één tile binnen het tijdvenster<br>te vaak geklikt wordt. Drempel/venster: zie Settings > Bot Control.</html>");
        walkHotspotCb.addActionListener(e -> setConfig("debugWalkStuckHotspotEnabled", walkHotspotCb.isSelected()));
        checkToggles.add(walkHotspotCb);

        JCheckBox loopWatchCb = new JCheckBox("LoopWatch: herstel + logout bij loop");
        loopWatchCb.setSelected(config.loopWatchRecoveryEnabled());
        loopWatchCb.setFont(FONT_LABEL);
        loopWatchCb.setForeground(TEXT);
        loopWatchCb.setBackground(BG_DARK);
        loopWatchCb.setToolTipText("<html>Bij [LoopWatch] in debug (zelfde status te lang):<br>"
                + "±90s zacht herstel, ±180s bot uit + logout. Drempels: Settings → Bot Control.</html>");
        loopWatchCb.addActionListener(e -> setConfig("loopWatchRecoveryEnabled", loopWatchCb.isSelected()));
        checkToggles.add(loopWatchCb);

        JCheckBox mouseDbgCb = new JCheckBox("Toon muispositie (overlay)");
        mouseDbgCb.setSelected(config.debugMouseOverlay());
        mouseDbgCb.setFont(FONT_LABEL);
        mouseDbgCb.setForeground(TEXT);
        mouseDbgCb.setBackground(BG_DARK);
        mouseDbgCb.setToolTipText("Tekent cursor-kruis + X/Y in-game.");
        mouseDbgCb.addActionListener(e -> setConfig("debugMouseOverlay", mouseDbgCb.isSelected()));
        checkToggles.add(mouseDbgCb);

        JCheckBox invIdOverlayCb = new JCheckBox("Inventory item-ID overlay");
        invIdOverlayCb.setSelected(config.debugInventoryItemIdOverlay());
        invIdOverlayCb.setFont(FONT_LABEL);
        invIdOverlayCb.setForeground(TEXT);
        invIdOverlayCb.setBackground(BG_DARK);
        invIdOverlayCb.setToolTipText("<html>Tekent item-ID op inventory-vakjes alleen met Inventory-tab open.<br>"
                + "Niet op Worn Equipment / andere tabs. Clue scrolls: ook tier.</html>");
        invIdOverlayCb.addActionListener(e ->
                setConfig("debugInventoryItemIdOverlay", invIdOverlayCb.isSelected()));
        checkToggles.add(invIdOverlayCb);

        JCheckBox beginnerClueCb = new JCheckBox("Beginner clue solver", config.beginnerClueSolverEnabled());
        beginnerClueCb.setFont(FONT_LABEL);
        beginnerClueCb.setForeground(TEXT);
        beginnerClueCb.setBackground(BG_DARK);
        beginnerClueCb.setToolTipText("<html>Bank → GE (ontbrekende items) → Reldo (Strange device).<br>"
                + "Daarna talk/emote/map dig/hot-cold/Charlie.</html>");
        beginnerClueCb.addActionListener(e ->
                configManager.setConfiguration("combatbot", "beginnerClueSolverEnabled", beginnerClueCb.isSelected()));
        checkToggles.add(beginnerClueCb);

        JCheckBox beginnerClueGeCb = new JCheckBox("Beginner clues: GE koop ontbrekend", config.beginnerClueGeBuyMissing());
        beginnerClueGeCb.setFont(FONT_LABEL);
        beginnerClueGeCb.setForeground(TEXT);
        beginnerClueGeCb.setBackground(BG_DARK);
        beginnerClueGeCb.setToolTipText("Ontbrekende kit op GE (niet Strange device — die komt van Reldo).");
        beginnerClueGeCb.addActionListener(e ->
                configManager.setConfiguration("combatbot", "beginnerClueGeBuyMissing", beginnerClueGeCb.isSelected()));
        checkToggles.add(beginnerClueGeCb);

        beginnerClueKitPane = new JTextPane();
        beginnerClueKitPane.setContentType("text/html");
        beginnerClueKitPane.setEditable(false);
        beginnerClueKitPane.setBackground(new Color(35, 38, 48));
        beginnerClueKitPane.setForeground(TEXT);
        beginnerClueKitPane.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        beginnerClueKitPane.setPreferredSize(new Dimension(300, 140));
        beginnerClueKitPane.setMaximumSize(new Dimension(Integer.MAX_VALUE, 200));
        beginnerClueKitPane.setText("<html><body style='color:#888;font-family:SansSerif;font-size:10px'>"
                + "Kit-lijst verschijnt tijdens beginner-clue bank/GE prep.</body></html>");

        JPanel clueKitBlock = new JPanel(new BorderLayout(0, 4));
        clueKitBlock.setBackground(BG_DARK);
        clueKitBlock.setAlignmentX(Component.LEFT_ALIGNMENT);
        clueKitBlock.setMaximumSize(new Dimension(Integer.MAX_VALUE, 220));
        JLabel clueKitTitle = new JLabel("Beginner clue kit");
        clueKitTitle.setFont(FONT_SECTION);
        clueKitTitle.setForeground(GOLD);
        clueKitTitle.setBorder(new EmptyBorder(8, 0, 0, 0));
        JLabel clueKitLegend = new JLabel("<html><span style='color:#64FF82'>■</span> inv/equip &nbsp;"
                + "<span style='color:#64DCFF'>■</span> bank &nbsp;"
                + "<span style='color:#AAAAAA'>■</span> nog nodig (GE) &nbsp;"
                + "<span style='color:#C898FF'>■</span> Reldo</html>");
        clueKitLegend.setFont(new Font("SansSerif", Font.PLAIN, 10));
        clueKitLegend.setForeground(TEXT_DIM);
        JPanel clueKitNorth = new JPanel();
        clueKitNorth.setLayout(new BoxLayout(clueKitNorth, BoxLayout.Y_AXIS));
        clueKitNorth.setBackground(BG_DARK);
        clueKitNorth.add(clueKitTitle);
        clueKitNorth.add(clueKitLegend);
        clueKitBlock.add(clueKitNorth, BorderLayout.NORTH);
        JScrollPane clueKitScroll = new JScrollPane(beginnerClueKitPane);
        clueKitScroll.setBorder(new LineBorder(new Color(55, 60, 75)));
        clueKitScroll.setPreferredSize(new Dimension(300, 130));
        clueKitBlock.add(clueKitScroll, BorderLayout.CENTER);

        JCheckBox areaMenuCb = new JCheckBox("Rechtermenu: centers & tiles");
        areaMenuCb.setSelected(config.debugAreaContextMenu());
        areaMenuCb.setFont(FONT_LABEL);
        areaMenuCb.setForeground(TEXT);
        areaMenuCb.setBackground(BG_DARK);
        areaMenuCb.setToolTipText("<html>Toont bij rechtsklik op een tile de bot-opties<br>"
                + "(voeg center, radius ±, tile markers). Uit = normaal OSRS-menu.</html>");
        areaMenuCb.addActionListener(e ->
                configManager.setConfiguration("combatbot", "debugAreaContextMenu", areaMenuCb.isSelected()));
        checkToggles.add(areaMenuCb);

        JButton resetWalkTilesBtn = new JButton("↺ Reset walk-tiles");
        resetWalkTilesBtn.setFont(new Font("Arial", Font.PLAIN, 10));
        resetWalkTilesBtn.setPreferredSize(new Dimension(118, 24));
        resetWalkTilesBtn.setBackground(new Color(55, 75, 95));
        resetWalkTilesBtn.setForeground(Color.WHITE);
        resetWalkTilesBtn.setFocusPainted(false);
        resetWalkTilesBtn.setToolTipText("Wist alle getekende walk-klik tiles (overlay kan aan blijven).");
        resetWalkTilesBtn.addActionListener(e -> MovementHelper.clearDebugWalkHighlights());
        controlButtons.add(resetWalkTilesBtn);

        JButton topHotspotsBtn = new JButton("📊 Top hotspots");
        topHotspotsBtn.setFont(new Font("Arial", Font.PLAIN, 10));
        topHotspotsBtn.setPreferredSize(new Dimension(120, 24));
        topHotspotsBtn.setBackground(new Color(60, 70, 120));
        topHotspotsBtn.setForeground(Color.WHITE);
        topHotspotsBtn.setFocusPainted(false);
        topHotspotsBtn.setToolTipText("Logt top-10 click- én pad-tiles naar de Debug-tab (bron WalkHotspot).");
        topHotspotsBtn.addActionListener(e -> dumpTopWalkHotspots(10));
        controlButtons.add(topHotspotsBtn);

        JButton diagWalkBtn = new JButton("🔬 Diagnose walk-tiles");
        diagWalkBtn.setFont(new Font("Arial", Font.PLAIN, 10));
        diagWalkBtn.setPreferredSize(new Dimension(160, 24));
        diagWalkBtn.setBackground(new Color(80, 60, 120));
        diagWalkBtn.setForeground(Color.WHITE);
        diagWalkBtn.setFocusPainted(false);
        diagWalkBtn.setToolTipText("<html>Logt naar Debug-tab (bron WalkClickDbg):<br>"
                + "• totaal opgeslagen tiles<br>"
                + "• in-scene vs out-of-scene<br>"
                + "• speler-positie en scene-base<br>"
                + "Gebruik dit ter plekke om te zien waarom tiles niet renderen.</html>");
        diagWalkBtn.addActionListener(e -> diagnoseWalkTiles());
        controlButtons.add(diagWalkBtn);

        JButton exportWalkBtn = new JButton("💾 Export walk-tiles");
        exportWalkBtn.setFont(new Font("Arial", Font.PLAIN, 10));
        exportWalkBtn.setPreferredSize(new Dimension(146, 24));
        exportWalkBtn.setBackground(new Color(35, 85, 55));
        exportWalkBtn.setForeground(Color.WHITE);
        exportWalkBtn.setFocusPainted(false);
        exportWalkBtn.setToolTipText("<html>Exporteert huidige walk-tile-aggregaten naar<br><code>combat-bot-walk-tiles-export-YYYY-MM-DD-HHmmss.jsonl</code></html>");
        exportWalkBtn.addActionListener(e -> exportWalkTilesNow());
        controlButtons.add(exportWalkBtn);

        JButton clearBtn = new JButton("🗑 Clear");
        clearBtn.setFont(new Font("Arial", Font.PLAIN, 10));
        clearBtn.setPreferredSize(new Dimension(70, 24));
        clearBtn.setBackground(new Color(70, 40, 40));
        clearBtn.setForeground(Color.WHITE);
        clearBtn.setFocusPainted(false);
        clearBtn.addActionListener(e -> {
            DebugLog.clear();
            for (JTextArea ta : debugLogTextAreas) {
                if (ta != null && ta.isDisplayable()) {
                    ta.setText("");
                }
            }
        });
        controlButtons.add(clearBtn);

        JButton lookupTestBtn = new JButton("🧪 Test lookup");
        lookupTestBtn.setFont(new Font("Arial", Font.PLAIN, 10));
        lookupTestBtn.setPreferredSize(new Dimension(106, 24));
        lookupTestBtn.setBackground(new Color(60, 70, 120));
        lookupTestBtn.setForeground(Color.WHITE);
        lookupTestBtn.setFocusPainted(false);
        lookupTestBtn.setToolTipText("Test 1x anti-ban speler lookup (rechtsklik + Lookup).");
        lookupTestBtn.addActionListener(e -> onTestPlayerLookupRequested.run());
        controlButtons.add(lookupTestBtn);

        JButton playTestBtn = new JButton("▶ Test Play");
        playTestBtn.setFont(new Font("Arial", Font.PLAIN, 10));
        playTestBtn.setPreferredSize(new Dimension(96, 24));
        playTestBtn.setBackground(new Color(60, 100, 70));
        playTestBtn.setForeground(Color.WHITE);
        playTestBtn.setFocusPainted(false);
        playTestBtn.setToolTipText("<html>Welkomstscherm: log getState + klik 378,77 CLICK HERE TO PLAY.<br>Zet <b>Debug Log Aan</b> aan — bron <b>Login</b>.</html>");
        playTestBtn.addActionListener(e -> {
            WelcomeScreenPlayHelper.debugTestClickPlay();
            SwingUtilities.invokeLater(this::refreshDebugLogViews);
        });
        controlButtons.add(playTestBtn);

        JButton clueDbgBtn = new JButton("📜 Clue info");
        clueDbgBtn.setFont(new Font("Arial", Font.PLAIN, 10));
        clueDbgBtn.setPreferredSize(new Dimension(96, 24));
        clueDbgBtn.setBackground(new Color(90, 75, 50));
        clueDbgBtn.setForeground(Color.WHITE);
        clueDbgBtn.setFocusPainted(false);
        clueDbgBtn.setToolTipText(ClueScrollHelper.solverFeasibilitySummary());
        clueDbgBtn.addActionListener(e -> {
            ClueScrollHelper.logInventoryCluesToDebug();
            SwingUtilities.invokeLater(this::refreshDebugLogViews);
        });
        controlButtons.add(clueDbgBtn);

        JButton beginnerDbBtn = new JButton("📜 Beginner DB");
        beginnerDbBtn.setFont(new Font("Arial", Font.PLAIN, 10));
        beginnerDbBtn.setPreferredSize(new Dimension(108, 24));
        beginnerDbBtn.setBackground(new Color(75, 90, 55));
        beginnerDbBtn.setForeground(Color.WHITE);
        beginnerDbBtn.setFocusPainted(false);
        beginnerDbBtn.setToolTipText("<html>Log alle bekende beginner-stappen naar Debug-tab.<br>"
                + "Gebaseerd op OSRS Wiki + RuneLite clue-database.</html>");
        beginnerDbBtn.addActionListener(e -> {
            ClueScrollHelper.logBeginnerReferenceToDebug();
            SwingUtilities.invokeLater(this::refreshDebugLogViews);
        });
        controlButtons.add(beginnerDbBtn);

        JButton lampHoverTestBtn = new JButton("🧞 Test lamp hover");
        lampHoverTestBtn.setFont(new Font("Arial", Font.PLAIN, 10));
        lampHoverTestBtn.setPreferredSize(new Dimension(126, 24));
        lampHoverTestBtn.setBackground(new Color(70, 85, 120));
        lampHoverTestBtn.setForeground(Color.WHITE);
        lampHoverTestBtn.setFocusPainted(false);
        lampHoverTestBtn.setToolTipText("Klik lamp (Rub/Use) en hover daarna Attack, Magic en Confirm widgets (zonder klikken).");
        lampHoverTestBtn.addActionListener(e -> onTestLampHoverRequested.run());
        controlButtons.add(lampHoverTestBtn);

        JButton openFileBtn = new JButton("📂 Debug-log (.log)");
        openFileBtn.setFont(new Font("Arial", Font.PLAIN, 10));
        openFileBtn.setPreferredSize(new Dimension(136, 24));
        openFileBtn.setBackground(new Color(40, 70, 110));
        openFileBtn.setForeground(Color.WHITE);
        openFileBtn.setFocusPainted(false);
        openFileBtn.setToolTipText("<html>Per dag:<br><code>~/.runelite/prive-logs/combat-bot-debug-YYYY-MM-DD.log</code><br>"
                + "Handig om naar support/AI te sturen bij bugs.</html>");
        openFileBtn.addActionListener(e -> openDebugLogFileForExternalShare());

        JButton openMlBtn = new JButton("📂 ML-clicks (.jsonl)");
        openMlBtn.setFont(new Font("Arial", Font.PLAIN, 10));
        openMlBtn.setPreferredSize(new Dimension(148, 24));
        openMlBtn.setBackground(new Color(40, 70, 110));
        openMlBtn.setForeground(Color.WHITE);
        openMlBtn.setFocusPainted(false);
        openMlBtn.setToolTipText("<html>Als <b>Log menu-klikken (ML)</b> aan staat:<br>"
                + "<code>combat-bot-ml-clicks-YYYY-MM-DD.jsonl</code><br>"
                + "Zelfde map als de debug-log.</html>");
        openMlBtn.addActionListener(e -> openMlClicksFileForExternalShare());

        JButton openMouseTraceBtn = new JButton("📂 Muis-trace (.jsonl)");
        openMouseTraceBtn.setFont(new Font("Arial", Font.PLAIN, 10));
        openMouseTraceBtn.setPreferredSize(new Dimension(160, 24));
        openMouseTraceBtn.setBackground(new Color(40, 70, 110));
        openMouseTraceBtn.setForeground(Color.WHITE);
        openMouseTraceBtn.setFocusPainted(false);
        openMouseTraceBtn.setToolTipText("<html>Als <b>Record muis trace</b> aan staat:<br>"
                + "<code>combat-bot-mouse-trace-YYYY-MM-DD.jsonl</code><br>"
                + "Met move/click/drag + timing.</html>");
        openMouseTraceBtn.addActionListener(e -> openMouseTraceFileForExternalShare());

        JButton openWalkTilesBtn = new JButton("📂 Walk-tiles (.jsonl)");
        openWalkTilesBtn.setFont(new Font("Arial", Font.PLAIN, 10));
        openWalkTilesBtn.setPreferredSize(new Dimension(160, 24));
        openWalkTilesBtn.setBackground(new Color(40, 70, 110));
        openWalkTilesBtn.setForeground(Color.WHITE);
        openWalkTilesBtn.setFocusPainted(false);
        openWalkTilesBtn.setToolTipText("<html>Als <b>Auto-log walk-tiles</b> aan staat:<br>"
                + "<code>combat-bot-walk-tiles-YYYY-MM-DD.jsonl</code><br>"
                + "Per regel: type (click/traverse), x, y, plane, totaal counts.</html>");
        openWalkTilesBtn.addActionListener(e -> openWalkTilesFileForExternalShare());

        JButton openFolderBtn = new JButton("📁 Map prive-logs");
        openFolderBtn.setFont(new Font("Arial", Font.PLAIN, 10));
        openFolderBtn.setPreferredSize(new Dimension(130, 24));
        openFolderBtn.setBackground(new Color(35, 85, 55));
        openFolderBtn.setForeground(Color.WHITE);
        openFolderBtn.setFocusPainted(false);
        openFolderBtn.setToolTipText("Opent de map met beide bestanden van vandaag (en eerdere dagen).");
        openFolderBtn.addActionListener(e -> openPriveLogsFolder());

        controlButtons.add(openFileBtn);
        controlButtons.add(openMlBtn);
        controlButtons.add(openMouseTraceBtn);
        controlButtons.add(openWalkTilesBtn);
        controlButtons.add(openFolderBtn);
        controls.add(controlButtons);
        controls.add(checkToggles);
        controls.add(clueKitBlock);

        JLabel debugHint = new JLabel("<html><div style='color:#a0a0b0;font-size:10px'>"
                + "Staat <b>Debug Log Aan</b> uit, dan zie je hier geen nieuwe regels. "
                + "Gebruik het <b>Combat Bot</b>-icoon in de RuneLite-zijbalk (tab 🔍 Debug).<br>"
                + "<b>Naar AI/support:</b> knoppen hierboven → meestal het <b>.log</b>-bestand; bij widget/klik-problemen ook "
                + "<b>.jsonl</b> (zet ML-log eerst aan)."
                + "</div></html>");
        debugHint.setBorder(new EmptyBorder(0, 0, 4, 0));
        debugHint.setAlignmentX(Component.LEFT_ALIGNMENT);
        debugHint.setHorizontalAlignment(SwingConstants.LEFT);
        debugHint.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        JPanel north = new JPanel();
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        north.setBackground(BG_DARK);
        north.add(controls);

        JPanel widgetInspectorBlock = new JPanel();
        widgetInspectorBlock.setLayout(new BoxLayout(widgetInspectorBlock, BoxLayout.Y_AXIS));
        widgetInspectorBlock.setBackground(BG_DARK);
        widgetInspectorBlock.setAlignmentX(Component.LEFT_ALIGNMENT);
        widgetInspectorBlock.setBorder(new EmptyBorder(6, 0, 10, 0));
        JPanel wiHeader = new JPanel(new BorderLayout());
        wiHeader.setOpaque(false);
        wiHeader.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Body-panel met daarin de fields + buttons; toggle-knop opent/sluit dit.
        JPanel wiBody = new JPanel();
        wiBody.setLayout(new BoxLayout(wiBody, BoxLayout.Y_AXIS));
        wiBody.setBackground(BG_DARK);
        wiBody.setAlignmentX(Component.LEFT_ALIGNMENT);
        wiBody.setVisible(false); // default ingeklapt — meer ruimte voor de log

        JButton wiToggleBtn = new JButton("▶");
        wiToggleBtn.setFont(new Font("Arial", Font.BOLD, 11));
        wiToggleBtn.setPreferredSize(new Dimension(28, 22));
        wiToggleBtn.setBackground(new Color(45, 60, 75));
        wiToggleBtn.setForeground(Color.WHITE);
        wiToggleBtn.setFocusPainted(false);
        wiToggleBtn.setMargin(new Insets(0, 0, 0, 0));
        wiToggleBtn.setToolTipText("Klap Widget inspector + tools open/dicht");

        JLabel wiTitle = new JLabel("  🔎 Widget inspector + tools");
        wiTitle.setFont(FONT_SECTION);
        wiTitle.setForeground(GOLD);
        wiTitle.setBorder(new EmptyBorder(6, 0, 3, 0));
        wiTitle.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));

        JPanel wiHeaderLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        wiHeaderLeft.setOpaque(false);
        wiHeaderLeft.add(wiToggleBtn);
        wiHeaderLeft.add(wiTitle);
        wiHeader.add(wiHeaderLeft, BorderLayout.WEST);

        Runnable wiToggle = () -> {
            boolean now = !wiBody.isVisible();
            wiBody.setVisible(now);
            wiToggleBtn.setText(now ? "▼" : "▶");
            widgetInspectorBlock.revalidate();
            widgetInspectorBlock.repaint();
        };
        wiToggleBtn.addActionListener(e -> wiToggle.run());
        wiTitle.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) { wiToggle.run(); }
        });

        JButton wiInfoBtn = new JButton("ℹ Info");
        wiInfoBtn.setFont(new Font("Arial", Font.PLAIN, 10));
        wiInfoBtn.setPreferredSize(new Dimension(72, 22));
        wiInfoBtn.setFocusPainted(false);
        wiInfoBtn.setBackground(new Color(55, 75, 95));
        wiInfoBtn.setForeground(Color.WHITE);
        wiInfoBtn.addActionListener(e -> JOptionPane.showMessageDialog(
                panel,
                "<html><div style='width:360px;color:#e6e6f0;font-size:11px'>"
                        + "De dump verschijnt in het <b>groene logveld</b> (bron <b>WIDGET</b>). Zet <b>Debug Log Aan</b> en "
                        + "laat bron <b>WIDGET</b> niet uit staan.<br><br>"
                        + "Ook naar stdout/logbestand zoals andere debug-regels. Interval werkt alleen als je <b>ingelogd</b> bent.<br><br>"
                        + "<b>Log menu-klikken (ML)</b>: bron <b>ML_CLICK</b> + bestand "
                        + "<code>prive-logs/combat-bot-ml-clicks-*.jsonl</code>.<br><br>"
                        + "Voor vaste widgets in code: zie <b>WidgetRegistry</b>. "
                        + "Vink <b>Toon widget-info onder muis</b> voor live tooltip (id/iface/naam).<br><br>"
                        + "Zelfde opties staan ook onder RuneLite <i>Plugin Configuration</i> → <b>Combat Bot</b> → <b>Widget inspector</b>."
                        + "</div></html>",
                "Widget inspector info",
                JOptionPane.INFORMATION_MESSAGE
        ));
        wiHeader.add(wiInfoBtn, BorderLayout.EAST);
        widgetInspectorBlock.add(wiHeader);

        JPanel wiFields = new JPanel();
        wiFields.setLayout(new BoxLayout(wiFields, BoxLayout.Y_AXIS));
        wiFields.setBackground(BG_DARK);
        wiFields.setAlignmentX(Component.LEFT_ALIGNMENT);
        addSlider(wiFields, "Dump elke N sec (0=uit)", config.widgetInspectorIntervalSeconds(), 0, 120,
                v -> setConfig("widgetInspectorIntervalSeconds", v));
        addSlider(wiFields, "Max regels", config.widgetInspectorMaxLines(), 50, 2000,
                v -> setConfig("widgetInspectorMaxLines", v));
        addTextField(wiFields, "Filter (substring)", config.widgetInspectorFilter(),
                v -> setConfig("widgetInspectorFilter", v));
        addToggle(wiFields, "Sla reeds-gelogde widgets over (dedupe)", config.widgetInspectorSkipAlreadyPrinted(),
                v -> setConfig("widgetInspectorSkipAlreadyPrinted", v));
        addToggle(wiFields, "Toon widget-info onder muis (overlay)", config.widgetHoverInspectorEnabled(),
                v -> setConfig("widgetHoverInspectorEnabled", v));
        addToggle(wiFields, "Inventory item-ID op vakjes (overlay)", config.debugInventoryItemIdOverlay(),
                v -> setConfig("debugInventoryItemIdOverlay", v));
        addToggle(wiFields, "Beginner clue solver", config.beginnerClueSolverEnabled(),
                v -> setConfig("beginnerClueSolverEnabled", v));
        addToggle(wiFields, "Beginner clues: GE koop ontbrekend", config.beginnerClueGeBuyMissing(),
                v -> setConfig("beginnerClueGeBuyMissing", v));
        addToggle(wiFields, "Log menu-klikken (ML) — jsonl + ML_CLICK", config.gameplayMlClickLog(),
                v -> setConfig("gameplayMlClickLog", v));
        addToggle(wiFields, "ML alleen handmatige klikken (geen bot)", config.gameplayMlClickLogOnlyAuthentic(),
                v -> setConfig("gameplayMlClickLogOnlyAuthentic", v));
        addToggle(wiFields, "Record muis trace (move/click/drag) — jsonl", config.gameplayMouseTraceLog(),
                v -> setConfig("gameplayMouseTraceLog", v));
        addToggle(wiFields, "Mouse trace alleen met bot UIT", config.gameplayMouseTraceOnlyWhenBotOff(),
                v -> setConfig("gameplayMouseTraceOnlyWhenBotOff", v));
        addSlider(wiFields, "Mouse trace move sample (ms)", config.gameplayMouseTraceMoveSampleMs(), 10, 200,
                v -> setConfig("gameplayMouseTraceMoveSampleMs", v));
        wiBody.add(wiFields);

        JPanel wiBtnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        wiBtnRow.setBackground(BG_DARK);
        wiBtnRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        JButton wiDumpNow = new JButton("📋 Dump nu");
        wiDumpNow.setFont(new Font("Arial", Font.PLAIN, 10));
        wiDumpNow.setToolTipText("Zware scan; kort lag mogelijk. Moet ingelogd zijn.");
        wiDumpNow.setBackground(new Color(50, 80, 120));
        wiDumpNow.setForeground(Color.WHITE);
        wiDumpNow.setFocusPainted(false);
        wiDumpNow.addActionListener(e -> {
            wiDumpNow.setEnabled(false);
            new SwingWorker<Void, Void>() {
                @Override
                protected Void doInBackground() {
                    int max = Math.max(50, Math.min(config.widgetInspectorMaxLines(), 2000));
                    try {
                        WidgetDebugHelper.dumpVisibleWidgetsToDebug(
                                config.widgetInspectorFilter(), max, config.widgetInspectorSkipAlreadyPrinted());
                    } catch (Throwable t) {
                        t.printStackTrace();
                    }
                    return null;
                }

                @Override
                protected void done() {
                    wiDumpNow.setEnabled(true);
                    SwingUtilities.invokeLater(() -> refreshDebugLogViews());
                }
            }.execute();
        });
        wiBtnRow.add(wiDumpNow);

        JButton buildHumanProfileBtn = new JButton("🧠 Bouw human-profiel");
        buildHumanProfileBtn.setFont(new Font("Arial", Font.PLAIN, 10));
        buildHumanProfileBtn.setBackground(new Color(70, 50, 100));
        buildHumanProfileBtn.setForeground(Color.WHITE);
        buildHumanProfileBtn.setFocusPainted(false);
        buildHumanProfileBtn.setToolTipText("<html>Leest het muis-trace bestand en berekent percentielen voor "
                + "muis-step / dwell / inter-click gap.<br>"
                + "Schrijft <code>combat-bot-human-profile.json</code> en past het direct toe op de anti-ban "
                + "micro-mouse cadence.</html>");
        JLabel humanProfileStatus = new JLabel(humanProfileStatusText());
        humanProfileStatus.setFont(new Font("Arial", Font.PLAIN, 10));
        humanProfileStatus.setForeground(TEXT_DIM);
        humanProfileStatus.setBorder(new EmptyBorder(0, 8, 0, 0));
        buildHumanProfileBtn.addActionListener(e -> {
            buildHumanProfileBtn.setEnabled(false);
            humanProfileStatus.setText("Bezig…");
            new SwingWorker<HumanProfileBuilder.BuildResult, Void>() {
                @Override
                protected HumanProfileBuilder.BuildResult doInBackground() {
                    try {
                        return HumanProfileBuilder.buildAndSaveFromTodayTrace();
                    } catch (Throwable t) {
                        return new HumanProfileBuilder.BuildResult(null, "Fout: " + t.getMessage(), false);
                    }
                }

                @Override
                protected void done() {
                    buildHumanProfileBtn.setEnabled(true);
                    HumanProfileBuilder.BuildResult res;
                    try {
                        res = get();
                    } catch (Exception ex) {
                        res = new HumanProfileBuilder.BuildResult(null, "Fout: " + ex.getMessage(), false);
                    }
                    humanProfileStatus.setText(humanProfileStatusText());
                    JOptionPane.showMessageDialog(panel,
                            new JScrollPane(new JTextArea(res.summary, 18, 60)),
                            res.success ? "Human profile gebouwd" : "Human profile fout",
                            res.success ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
                }
            }.execute();
        });
        wiBtnRow.add(buildHumanProfileBtn);

        JButton openHumanProfileBtn = new JButton("📂 Profiel json");
        openHumanProfileBtn.setFont(new Font("Arial", Font.PLAIN, 10));
        openHumanProfileBtn.setBackground(new Color(40, 70, 110));
        openHumanProfileBtn.setForeground(Color.WHITE);
        openHumanProfileBtn.setFocusPainted(false);
        openHumanProfileBtn.setToolTipText("Opent <code>combat-bot-human-profile.json</code> in je standaardprogramma.");
        openHumanProfileBtn.addActionListener(e -> {
            try {
                File f = HumanProfile.getProfileFile();
                if (f.exists() && Desktop.isDesktopSupported()
                        && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    Desktop.getDesktop().open(f);
                } else {
                    JOptionPane.showMessageDialog(panel,
                            "Geen profiel-bestand gevonden. Klik eerst op 'Bouw human-profiel'.",
                            "Profiel ontbreekt", JOptionPane.INFORMATION_MESSAGE);
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        });
        wiBtnRow.add(openHumanProfileBtn);

        JButton testMicroBtn = new JButton("🧪 Test micro-mouse nu");
        testMicroBtn.setFont(new Font("Arial", Font.PLAIN, 10));
        testMicroBtn.setBackground(new Color(120, 70, 50));
        testMicroBtn.setForeground(Color.WHITE);
        testMicroBtn.setFocusPainted(false);
        testMicroBtn.setToolTipText("<html>Forceert direct één micro-mouse beweging "
                + "met human-profiel waardes.<br>Zo zie je in de console of het profiel actief is "
                + "en hoeveel ms cooldown wordt toegepast.</html>");
        testMicroBtn.addActionListener(e -> {
            String status = onTestHumanMicroMouseRequested.get();
            JOptionPane.showMessageDialog(panel,
                    new JScrollPane(new JTextArea(status, 6, 60)),
                    "Micro-mouse test",
                    JOptionPane.INFORMATION_MESSAGE);
            refreshDebugLogViews();
        });
        wiBtnRow.add(testMicroBtn);

        JButton testFidgetBtn = new JButton("🐭 Test fidget burst");
        testFidgetBtn.setFont(new Font("Arial", Font.PLAIN, 10));
        testFidgetBtn.setBackground(new Color(50, 100, 70));
        testFidgetBtn.setForeground(Color.WHITE);
        testFidgetBtn.setFocusPainted(false);
        testFidgetBtn.setToolTipText("<html>Forceert direct een fidget-burst (2-4 micro-bewegingen).<br>"
                + "Werkt alleen als 'Continuous muis-fidget' aanstaat.</html>");
        testFidgetBtn.addActionListener(e -> {
            String status = onTestFidgetBurstRequested.get();
            JOptionPane.showMessageDialog(panel,
                    new JScrollPane(new JTextArea(status, 6, 60)),
                    "Fidget burst test",
                    JOptionPane.INFORMATION_MESSAGE);
            refreshDebugLogViews();
        });
        wiBtnRow.add(testFidgetBtn);

        wiBtnRow.add(humanProfileStatus);
        wiBody.add(wiBtnRow);
        widgetInspectorBlock.add(wiBody);
        north.add(widgetInspectorBlock);

        JPanel sourceToggles = new JPanel(new GridLayout(0, 3, 4, 2));
        sourceToggles.setBackground(BG_DARK);
        DebugLog.registerSourceForPanel("ML_CLICK");
        for (String src : DebugLog.getKnownSources()) {
            JCheckBox cbSrc = new JCheckBox(src);
            cbSrc.setSelected(DebugLog.isSourceEnabled(src));
            cbSrc.setFont(new Font("Arial", Font.PLAIN, 9));
            cbSrc.setForeground(TEXT_DIM);
            cbSrc.setBackground(BG_DARK);
            cbSrc.setMargin(new Insets(0, 0, 0, 0));
            cbSrc.setBorder(new EmptyBorder(0, 0, 0, 0));
            cbSrc.addActionListener(e -> {
                DebugLog.setSourceEnabled(src, cbSrc.isSelected());
                setConfig("debugDisabledSourcesCsv", DebugLog.getDisabledSourcesCsv());
                refreshDebugLogViews();
            });
            sourceToggles.add(cbSrc);
        }
        JScrollPane sourceScroll = new JScrollPane(sourceToggles);
        sourceScroll.setBorder(new EmptyBorder(0, 0, 2, 0));
        sourceScroll.getViewport().setBackground(BG_DARK);
        sourceScroll.setBackground(BG_DARK);
        sourceScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sourceScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        sourceScroll.setPreferredSize(new Dimension(0, 90));
        sourceScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 96));
        sourceScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        north.add(sourceScroll);
        north.add(debugHint);
        panel.add(north, BorderLayout.NORTH);

        // Log text area (meerdere instanties: hoofd + los venster — zelfde lijst)
        JTextArea debugTextArea = new JTextArea();
        debugLogTextAreas.add(debugTextArea);
        debugTextArea.setEditable(false);
        debugTextArea.setFont(new Font("Monospaced", Font.PLAIN, 10));
        debugTextArea.setBackground(new Color(20, 20, 30));
        debugTextArea.setForeground(new Color(0, 255, 120));
        debugTextArea.setCaretColor(GREEN);
        debugTextArea.setLineWrap(true);
        debugTextArea.setWrapStyleWord(true);

        JScrollPane scroll = new JScrollPane(debugTextArea);
        scroll.setBackground(BG_DARK);
        scroll.getViewport().setBackground(new Color(20, 20, 30));
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        panel.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && panel.isShowing()) {
                SwingUtilities.invokeLater(() -> {
                    refreshDebugLogViews();
                    JScrollBar bar = scroll.getVerticalScrollBar();
                    if (bar != null) {
                        bar.setValue(bar.getMaximum());
                    }
                });
            }
        });
        panel.add(scroll, BorderLayout.CENTER);

        SwingUtilities.invokeLater(this::refreshDebugLogViews);
        return panel;
    }

    // ===================== STATS HELPERS =====================

    private void addSectionTitle(JPanel panel, String title) {
        JLabel lbl = new JLabel(title);
        lbl.setFont(FONT_SECTION);
        lbl.setForeground(GOLD);
        lbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        lbl.setBorder(new EmptyBorder(6, 0, 3, 0));
        panel.add(lbl);
    }

    private void addStatRow(JPanel panel, String label, JLabel value) {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));

        JLabel lbl = new JLabel(label);
        lbl.setForeground(TEXT_DIM);
        lbl.setFont(FONT_LABEL);

        value.setForeground(Color.WHITE);
        value.setFont(FONT_VALUE);
        value.setHorizontalAlignment(SwingConstants.RIGHT);

        row.add(lbl, BorderLayout.WEST);
        row.add(value, BorderLayout.EAST);
        panel.add(row);
    }

    private void addSep(JPanel panel) {
        JSeparator sep = new JSeparator();
        sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 2));
        sep.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(sep);
    }

    // ===================== LIVE UPDATES =====================

    private void updateLabels() {
        refreshDebugLogViews();
        updateBeginnerClueKitPane();
        if (paint == null) return;
        lblRuntime.setText(paint.formatRuntime());
        lblActiveSkill.setText(paint.getActiveSkillName());
        lblStatus.setText(paint.getCurrentStatus());

        lblCombatXp.setText(formatXp(paint.getCombatXpGained()) + " (" + formatXp(paint.getCombatXpPerHour()) + "/hr)");
        lblWcXp.setText(formatXp(paint.getWcXpGained()) + " (" + formatXp(paint.getWcXpPerHour()) + "/hr)");
        lblMiningXp.setText(formatXp(paint.getMiningXpGained()) + " (" + formatXp(paint.getMiningXpPerHour()) + "/hr)");
        lblFishingXp.setText(formatXp(paint.getFishingXpGained()) + " (" + formatXp(paint.getFishingXpPerHour()) + "/hr)");
        lblFmXp.setText(formatXp(paint.getFmXpGained()) + " (" + formatXp(paint.getFmXpPerHour()) + "/hr)");

        lblKills.setText(paint.getKills() + " (" + paint.getKillsPerHour() + "/hr)");
        lblLoot.setText(paint.formatGp(paint.getTotalLootValue()));
        lblLogs.setText(String.valueOf(paint.getLogsChopped()));
        lblOres.setText(String.valueOf(paint.getOresMined()));
        lblFish.setText(String.valueOf(paint.getFishCaught()));

        // Re-log status
        if (paint.isRelogEnabled()) {
            long secs;
            String prefix;
            if (paint.isRelogInPause() && paint.getRelogPauseSecondsRemaining() > 0) {
                secs = paint.getRelogPauseSecondsRemaining();
                prefix = "Pauze ";
            } else {
                secs = paint.getRelogSecondsUntilLogout();
                prefix = "Logout over ";
            }
            long m = secs / 60;
            long s = secs % 60;
            lblRelog.setText(prefix + String.format("%02d:%02d", m, s));
        } else {
            lblRelog.setText("Uit");
        }

        accountsBotBarHandles.removeIf(h -> h.anchor == null || !h.anchor.isDisplayable());
        for (BotBarHandle h : accountsBotBarHandles) {
            h.refresh.run();
        }
        repaint();
    }

    private void notifyBotStartRequested() {
        CombatBotPlugin plugin = CombatBotRuntime.getActivePlugin();
        if (plugin != null) {
            plugin.onBotStartRequested();
        }
    }

    private void updateBeginnerClueKitPane() {
        if (beginnerClueKitPane == null || paint == null) {
            return;
        }
        if (!paint.isBeginnerClueKitOverlayVisible()) {
            if (!beginnerClueKitPane.getText().contains("Kit-lijst verschijnt")) {
                beginnerClueKitPane.setText("<html><body style='color:#888;font-family:SansSerif;font-size:10px'>"
                        + "Kit-lijst verschijnt tijdens beginner-clue bank/GE prep.</body></html>");
            }
            return;
        }
        List<CombatBotPaint.BeginnerKitLine> lines = paint.getBeginnerClueKitLines();
        StringBuilder html = new StringBuilder("<html><body style='font-family:SansSerif;font-size:10px;line-height:14px'>");
        html.append("<b style='color:#FFD878'>").append(paint.getBeginnerClueKitHaveCount())
                .append("/").append(paint.getBeginnerClueKitTotalCount()).append("</b><br/>");
        for (CombatBotPaint.BeginnerKitLine line : lines) {
            String color = "#AAAAAA";
            switch (line.availability) {
                case IN_INVENTORY:
                    color = "#64FF82";
                    break;
                case IN_BANK:
                    color = "#64DCFF";
                    break;
                case RELDO_ONLY:
                    color = "#C898FF";
                    break;
                case MISSING:
                default:
                    color = "#B0B0B8";
                    break;
            }
            html.append("<span style='color:").append(color).append("'>")
                    .append(escapeHtml(line.itemName)).append("</span><br/>");
        }
        html.append("</body></html>");
        beginnerClueKitPane.setText(html.toString());
    }

    private static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Vult alle geregistreerde Debug-tab tekstvakken (hoofd + los venster). */
    private void refreshDebugLogViews() {
        debugLogTextAreas.removeIf(ta -> ta == null || !ta.isDisplayable());
        if (debugLogTextAreas.isEmpty()) {
            return;
        }
        if (!DebugLog.isEnabled()) {
            String msg = "(Debug logging uit — vink \"Debug Log Aan\" aan bovenaan deze tab)";
            for (JTextArea ta : debugLogTextAreas) {
                if (!msg.equals(ta.getText())) {
                    ta.setText(msg);
                }
            }
            return;
        }
        String text = DebugLog.getText();
        for (JTextArea ta : debugLogTextAreas) {
            try {
                if (!text.equals(ta.getText())) {
                    ta.setText(text);
                    ta.setCaretPosition(ta.getDocument().getLength());
                }
            } catch (Exception ignored) {
            }
        }
    }

    private String formatXp(long xp) {
        if (xp >= 1_000_000) return String.format("%.1fM", xp / 1_000_000.0);
        if (xp >= 1_000) return String.format("%.1fK", xp / 1_000.0);
        return String.valueOf(xp);
    }

    public void stopTimer() {
        if (refreshTimer != null) refreshTimer.stop();
        if (detachedFrame != null) {
            detachedFrame.dispose();
            detachedFrame = null;
        }
    }

    /**
     * Pop-out: opent het panel in een apart, resizable JFrame.
     * Het venster is vrij schaalbaar en alle content past mee.
     */
    private void toggleDetachedWindow() {
        if (detachedFrame != null) {
            detachedFrame.dispose();
            detachedFrame = null;
            return;
        }

        detachedFrame = new JFrame("⚔ Combat Bot");
        detachedFrame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        detachedFrame.setSize(400, 700);
        detachedFrame.setMinimumSize(new Dimension(300, 400));
        detachedFrame.getContentPane().setBackground(BG_DARK);

        // Maak een nieuwe tabbed pane voor het losse venster
        JTabbedPane detachedTabs = new JTabbedPane();
        detachedTabs.setFont(new Font("Arial", Font.BOLD, 12));
        detachedTabs.setBackground(BG_DARK);
        detachedTabs.addTab("👤 Accounts", createAccountsManagerTab());
        detachedTabs.addTab("⚙ Settings", createSettingsTab());
        detachedTabs.addTab("🎯 Skills", createSkillTabsTab());
        detachedTabs.addTab("📊 Stats", createStatsTab());
        detachedTabs.addTab("📍 Centers", createCentersTab(dr -> detachedCentersRefs = dr));
        detachedTabs.addTab("🔍 Debug", createDebugTab());
        detachedTabs.setSelectedIndex(0);

        detachedTabs.addChangeListener(e -> {
            if (!(e.getSource() instanceof JTabbedPane)) {
                return;
            }
            JTabbedPane tp = (JTabbedPane) e.getSource();
            int i = tp.getSelectedIndex();
            if (i < 0) {
                return;
            }
            String tabTitle = tp.getTitleAt(i);
            if (tabTitle != null && tabTitle.contains("Centers")) {
                refreshCentersTabRowsFromConfig();
            }
        });

        detachedFrame.add(detachedTabs, BorderLayout.CENTER);
        detachedFrame.setLocationRelativeTo(null);
        detachedFrame.setVisible(true);

        // Cleanup bij sluiten
        detachedFrame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                detachedFrame = null;
                detachedCentersRefs = null;
            }
        });
    }

    /**
     * Opent een URL in de standaard browser.
     * Probeert Desktop.browse, daarna OS-specifieke fallbacks.
     */
    private void openBrowser(String url) {
        new Thread(() -> {
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(new URI(url));
                    return;
                }
            } catch (Exception ignored) {}

            // OS-specifieke fallback
            try {
                String os = System.getProperty("os.name").toLowerCase();
                ProcessBuilder pb;
                if (os.contains("win")) {
                    pb = new ProcessBuilder("cmd", "/c", "start", "", url);
                } else if (os.contains("mac")) {
                    pb = new ProcessBuilder("open", url);
                } else {
                    pb = new ProcessBuilder("xdg-open", url);
                }
                pb.start();
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    try {
                        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                                .setContents(new java.awt.datatransfer.StringSelection(url), null);
                        JOptionPane.showMessageDialog(CombatBotPanel.this,
                                "Browser kon niet geopend worden.\nURL gekopieerd naar clipboard:\n" + url,
                                "Web GUI", JOptionPane.INFORMATION_MESSAGE);
                    } catch (Exception ignored) {}
                });
            }
        }, "BrowserOpener").start();
    }
}
