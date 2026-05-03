package com.combatbot;

import net.runelite.client.ui.PluginPanel;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.event.TableModelEvent;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import javax.swing.ListSelectionModel;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.Objects;

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
    private static final String DEFAULT_MINING_CENTERS = "3285:3368:0:14:Varrock east mine:1";
    private static final String DEFAULT_FISHING_CENTERS = "3104:3433:0:14:Barbarian village:1|3090:3230:0:14:Draynor village:1";
    private static final String DEFAULT_IMPS_CENTERS = "2826:3181:0:12:Karamja imps:1";

    // Schaalfactor
    private float scaleFactor = 1.0f;

    // Detached frame
    private JFrame detachedFrame = null;
    private JTabbedPane tabs;
    private JPanel mainContent;

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
    /** Besturingsbalk Start/Pauze/Stop/Reset op Accounts-tab — verversen op config + opruimen als tab weg is. */
    private final List<BotBarHandle> accountsBotBarHandles = new ArrayList<>();
    /** Hoofd-Debug-tab + eventueel los venster: allemaal dezelfde DebugLog-inhoud. */
    private final List<JTextArea> debugLogTextAreas = new CopyOnWriteArrayList<>();
    /** Houd toggles met dezelfde config-key live synchroon (o.a. overlays in meerdere tabs). */
    private final Map<String, List<JCheckBox>> mirroredTogglesByConfigKey = new HashMap<>();
    private boolean syncingMirroredToggles = false;
    private static final String[] DEFAULT_DEBUG_SOURCES = {
            "COMBATBOT", "STARTSKILL", "STARTERSKILL", "MELEESTYLE", "COMBAT", "IMPS", "GIANTS",
            "WOODCUTTING", "MINING", "FISHING", "BARBLOOT", "QUEST", "LAMP", "DISCORD",
            "ACCOUNTS", "RELOG", "MOVEMENTHELPER", "BANKHELPER", "UBM", "GERESTOCK",
            "ACCOUNTSTATEJSON", "INVCHECK", "SAFESPOT", "VARROCKTP", "DEBUG", "WIDGET"
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
                          Runnable onResetAllBotState, Runnable onEmergencyStopAll) {
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
        tabs.addTab("📍 Centers", createCentersTab());
        tabs.addTab("🔍 Debug", createDebugTab());
        tabs.setSelectedIndex(0);

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
        addToggle(control, "Bot inschakelen", config.botEnabled(), v -> setConfig("botEnabled", v));
        addToggle(control, "Grote loopstappen 15-20 (globaal)", config.impsForceLargeSteps(), v -> setConfig("impsForceLargeSteps", v));
        addToggle(control, "Reset per-account timers bij stop", config.resetAccountTimersOnStop(), v -> setConfig("resetAccountTimersOnStop", v));
        addTriggerToggle(control, "🚪 Uitloggen (wacht op combat, loot, client thread)", "panelLogoutTrigger", onPanelLogoutRequested);
        panel.add(control);
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
        addToggle(combat, "Stop bij geen food", config.disableCombatNoFood(), v -> setConfig("disableCombatNoFood", v));
        addToggle(combat, "Bury Bones/Ashes", config.buryBones(), v -> setConfig("buryBones", v));
        addSlider(combat, "Bones/ashes bij min. X stuks", config.buryBonesMinBatch(), 1, 28, v -> setConfig("buryBonesMinBatch", v));
        addToggle(combat, "Safespot", config.safespotEnabled(), v -> setConfig("safespotEnabled", v));
        addToggle(combat, "🎯 Imps Mode", config.impsMode(), v -> setConfig("impsMode", v));
        addOverlayToggle(combat, "Combat overlay", config.showCombatOverlay(), "showCombatOverlay");
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
        addToggle(mining, "Specifiek erts", config.miningUseSpecificOre(), v -> setConfig("miningUseSpecificOre", v));
        addTextField(mining, "Erts naam", config.miningOreName(), v -> setConfig("miningOreName", v));
        addToggle(mining, "Erts droppen", config.miningDropOre(), v -> setConfig("miningDropOre", v));
        addSlider(mining, "Mining delay min (ms)", config.miningInteractDelayMin(), 0, 5000, v -> setConfig("miningInteractDelayMin", v));
        addSlider(mining, "Mining delay max (ms)", config.miningInteractDelayMax(), 0, 10000, v -> setConfig("miningInteractDelayMax", v));
        addOverlayToggle(mining, "Mining overlay", config.showMiningOverlay(), "showMiningOverlay");
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
        addToggle(antiban, "Camera bewegingen", config.cameraMovement(), v -> setConfig("cameraMovement", v));
        addToggle(antiban, "Idle pauzes", config.idleChecks(), v -> setConfig("idleChecks", v));
        addToggle(antiban, "Muis bewegingen", config.randomMouseMovement(), v -> setConfig("randomMouseMovement", v));
        addToggle(antiban, "Tab-wissel (inventory)", config.tabGlanceEnabled(), v -> setConfig("tabGlanceEnabled", v));
        addToggle(antiban, "Misclicks", config.misClickEnabled(), v -> setConfig("misClickEnabled", v));
        addSlider(antiban, "Misclick kans %", config.misClickPercent(), 1, 30, v -> setConfig("misClickPercent", v));
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

        panel.add(createCollapsibleSection("🏹 Pijlen & runes & lamp", false, false, c -> {
            addSlider(c, "Arrow min (bank onder)", config.combatArrowMin(), 0, 500, v -> setConfig("combatArrowMin", v));
            addSlider(c, "Arrow target (ophalen)", config.combatArrowTarget(), 1, 1000, v -> setConfig("combatArrowTarget", v));
            addSlider(c, "Rune min (bank onder)", config.combatRuneMin(), 0, 500, v -> setConfig("combatRuneMin", v));
            addSlider(c, "Rune target (ophalen)", config.combatRuneTarget(), 1, 1000, v -> setConfig("combatRuneTarget", v));
            addComboBox(c, "Genie lamp skill", CombatBotConfig.GenieLampSkill.values(), config.genieLampSkill(), v -> setConfig("genieLampSkill", v.name()));
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
            addToggle(c, "Specifiek erts", config.miningUseSpecificOre(), v -> setConfig("miningUseSpecificOre", v));
            addTextField(c, "Erts naam", config.miningOreName(), v -> setConfig("miningOreName", v));
            addToggle(c, "Erts droppen", config.miningDropOre(), v -> setConfig("miningDropOre", v));
        }));
        panel.add(Box.createVerticalStrut(6));
        panel.add(createCollapsibleSection("⏱ Mining delays & overlay", false, false, c -> {
            addSlider(c, "Mining delay min (ms)", config.miningInteractDelayMin(), 0, 5000, v -> setConfig("miningInteractDelayMin", v));
            addSlider(c, "Mining delay max (ms)", config.miningInteractDelayMax(), 0, 10000, v -> setConfig("miningInteractDelayMax", v));
            addOverlayToggle(c, "Mining overlay", config.showMiningOverlay(), "showMiningOverlay");
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

    private JScrollPane createCentersTab() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(BG_DARK);
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));

        JLabel info = new JLabel("<html><b>Center locaties per skill</b><br>Rechtermuisklik in-game om toe te voegen.</html>");
        info.setFont(FONT_LABEL);
        info.setForeground(TEXT_DIM);
        info.setAlignmentX(Component.LEFT_ALIGNMENT);
        info.setBorder(new EmptyBorder(0, 0, 8, 0));
        panel.add(info);

        JPanel accountsGlobal = createSection("👤 Accounts & centra", false);
        addToggle(accountsGlobal,
                "Alle accounts: alleen globale center-lijsten (negeer eigen locaties in Account bewerken)",
                config.accountsUseGlobalCenterListsOnly(),
                v -> setConfig("accountsUseGlobalCenterListsOnly", v));
        JLabel agHint = new JLabel("<html><div style='color:#a0a0b0;font-size:10px;width:420px'>"
                + "Staat dit <b>aan</b> dan volgt elk account bij inloggen/wissel de volledige lijst van deze tab voor elke "
                + "aangevinkte skill — niet de subset uit <b>Bewerken… → Locaties</b>. Staat het <b>uit</b>, dan gelden je "
                + "per-account locatiekiezen weer."
                + "</div></html>");
        agHint.setAlignmentX(Component.LEFT_ALIGNMENT);
        agHint.setBorder(new EmptyBorder(0, 10, 4, 10));
        accountsGlobal.add(agHint);
        panel.add(accountsGlobal);
        panel.add(Box.createVerticalStrut(6));

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
                    "Default centers zijn teruggezet.\nOpen de Centers-tab opnieuw om direct de nieuwe lijst te zien.",
                    "Centers hersteld",
                    JOptionPane.INFORMATION_MESSAGE
            );
        });
        defaultCentersRow.add(btnRestoreDefaults);
        panel.add(defaultCentersRow);
        panel.add(Box.createVerticalStrut(6));

        addCentersSection(panel, "⚔ Combat", config.combatCenters(), "combatCenters");
        panel.add(Box.createVerticalStrut(6));
        addCentersSection(panel, "🪓 Woodcutting", config.wcCenters(), "wcCenters");
        panel.add(Box.createVerticalStrut(6));
        addCentersSection(panel, "⛏ Mining", config.miningCenters(), "miningCenters");
        panel.add(Box.createVerticalStrut(6));
        addCentersSection(panel, "🐟 Fishing", config.fishingCenters(), "fishingCenters");
        panel.add(Box.createVerticalStrut(6));
        addImpsCentersBlock(panel);
        panel.add(Box.createVerticalStrut(6));
        addGiantsSection(panel);
        panel.add(Box.createVerticalStrut(6));
        addBarbarianSection(panel);

        panel.add(Box.createVerticalGlue());

        JScrollPane scroll = new JScrollPane(panel);
        scroll.setBackground(BG_DARK);
        scroll.getViewport().setBackground(BG_DARK);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    /** Imps: rotatie-toggle + lijst hunting centers (zelfde als andere skills). */
    private void addImpsCentersBlock(JPanel parent) {
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
        addCentersSection(parent, "Imps hunting centers", config.impsCenters(), "impsCenters");
    }

    /** Sectie voor Giants mode: geen centers (vaste locatie Edgeville Dungeon), wel toggle. */
    private void addGiantsSection(JPanel parent) {
        JPanel section = createSection("🗡 Giants (Edgeville Dungeon)", false);
        addToggle(section, "Giants als skill kiezen (in rotatie / start skill)", config.giantsMode(), v -> setConfig("giantsMode", v));
        JLabel info = new JLabel("<html><i>Hill Giants — Edgeville Dungeon (brass key vereist).</i></html>");
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

    private void addCentersSection(JPanel parent, String title, String centersData, String configKey) {
        JPanel section = createSection(title, false);
        populateCentersSectionRows(section, centersData, configKey);
        parent.add(section);
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
                        String serialized = CenterManager.serialize(updated);
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
                    String serialized = CenterManager.serialize(updated);
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
                            CenterManager.serialize(updated));
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

        final List<ManagedJagexAccountsStore.ManagedJagexAccountRow> accountRows = new ArrayList<>(
                ManagedJagexAccountsStore.parseRows(config.managedJagexAccountsBlob()));
        if (accountRows.isEmpty()) {
            accountRows.addAll(buildRowsFromPastedFallback());
        }

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
            refillTable.run();
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
                refillTable.run();
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
                refillTable.run();
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

        refillTable.run();
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
            refillTable.run();
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
        btns.add(editBtn);
        btns.add(delBtn);
        btns.add(resetStateBtn);
        root.add(btns, BorderLayout.SOUTH);

        addBtn.addActionListener(e -> {
            ManagedJagexAccountsStore.ManagedJagexAccountRow r = new ManagedJagexAccountsStore.ManagedJagexAccountRow();
            r.displayName = "account" + (accountRows.size() + 1);
            r.rotationEnabled = true;
            accountRows.add(r);
            refillTable.run();
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
            refillTable.run();
            schedulePersistManagedAccounts.run();
            JOptionPane.showMessageDialog(root, "Geïmporteerd uit huidige geplakte credentials.", "Accounts", JOptionPane.INFORMATION_MESSAGE);
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
            refillTable.run();
        });

        Timer refreshTimer = new Timer(8000, e2 -> refillTable.run());
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
        ManagedJagexAccountsStore.AccountStatSnapshot statSnap =
                ManagedJagexAccountsStore.snapshotForRow(
                        ManagedJagexAccountsStore.parseSnapshots(config.accountStatSnapshotsBlob()),
                        r.displayName);
        String currentMeleeLevels = statSnap != null
                ? statSnap.attack + " / " + statSnap.strength + " / " + statSnap.defence
                : "nog onbekend";
        AccountStateJsonStore.AccountEntry accountState = AccountStateJsonStore.getEntry(r.displayName);
        long lastCalibMs = accountState != null ? accountState.lastBankCalibrationMs : 0L;
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
        top.add(new JLabel("Huidig Att / Str / Def"));
        top.add(new JLabel(currentMeleeLevels));
        String calibSuffix = lastCalibMs > 0 ? " (laatste: " + formatDateTime(lastCalibMs) + ")" : " (nog niet)";
        top.add(new JLabel("Bank calibreren bij login" + calibSuffix));
        top.add(cbCalibrateBank);
        top.add(new JLabel("Magic auto update (Imps)"));
        top.add(cbMagicAutoUpdate);
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
        top.add(new JLabel("Imps combat (per account)"));
        top.add(impsStyleCombo);
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
        JLabel cHead = new JLabel("<html><div style='width:360px;color:#c8c8d8'>"
                + "<b>Centers</b><br>"
                + "Globale lijsten staan op de tab <b>Centers</b>. Vink een skill aan en kies via <b>Locaties…</b> "
                + "welke locaties dit account gebruikt. <b>Geen</b> sub-locatie aangevinkt = skill telt niet mee in rotatie.<br><br>"
                + "<b>Dubbelklik login</b>: op de <b>Accounts-tab</b>, vul <i>Profiel credentials.properties</i> "
                + "in (of zet <code>jagex:pad</code> in Account lijst) zodat RuneLite geen \"mist username/session\" meer geeft."
                + "</div></html>");
        cHead.setAlignmentX(Component.LEFT_ALIGNMENT);
        cHead.setBorder(new EmptyBorder(0, 0, 6, 0));
        centers.add(cHead);

        AccountCenterUiState stCombat = new AccountCenterUiState(config.combatCenters(), r.combatCenters, r.useGlobalCombatCenters);
        AccountCenterUiState stWc = new AccountCenterUiState(config.wcCenters(), r.wcCenters, r.useGlobalWcCenters);
        AccountCenterUiState stMine = new AccountCenterUiState(config.miningCenters(), r.miningCenters, r.useGlobalMiningCenters);
        AccountCenterUiState stFish = new AccountCenterUiState(config.fishingCenters(), r.fishingCenters, r.useGlobalFishingCenters);
        AccountCenterUiState stImps = new AccountCenterUiState(config.impsCenters(), r.impsCenters, r.useGlobalImpsCenters);

        centers.add(buildAccountSkillCenterRow(parent, "Combat", stCombat));
        centers.add(Box.createVerticalStrut(4));
        centers.add(buildAccountSkillCenterRow(parent, "Woodcutting", stWc));
        centers.add(Box.createVerticalStrut(4));
        centers.add(buildAccountSkillCenterRow(parent, "Mining", stMine));
        centers.add(Box.createVerticalStrut(4));
        centers.add(buildAccountSkillCenterRow(parent, "Fishing", stFish));
        centers.add(Box.createVerticalStrut(4));
        centers.add(buildAccountSkillCenterRow(parent, "Imps", stImps));

        JScrollPane centersScroll = new JScrollPane(centers);
        centersScroll.setBorder(null);
        centersScroll.getVerticalScrollBar().setUnitIncrement(16);
        form.add(centersScroll, BorderLayout.CENTER);

        int ok = JOptionPane.showConfirmDialog(parent, form, "Account bewerken", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) {
            return false;
        }
        r.displayName = fName.getText().trim();
        r.characterId = fChar.getText().trim();
        r.sessionId = fSess.getText().trim();
        r.notes = fNotes.getText().trim();
        r.targetAttackLevel = parseNonNegativeInt(fAttTarget.getText());
        r.targetStrengthLevel = parseNonNegativeInt(fStrTarget.getText());
        r.targetDefenceLevel = parseNonNegativeInt(fDefTarget.getText());
        r.calibrateBankOnNextLogin = cbCalibrateBank.isSelected();
        r.magicAutoUpdate = cbMagicAutoUpdate.isSelected();
        if (impsStyleCombo.getSelectedIndex() <= 0) {
            r.impsCombatStyleOverride = "";
        } else {
            r.impsCombatStyleOverride = Objects.requireNonNull(impsStyleCombo.getSelectedItem()).toString();
        }
        r.accountSwitchWorldHopEnabled = cbWorldHop.isSelected();
        r.accountSwitchWorld = fWorld.getText().trim();
        applyAccountCenterUiState(stCombat, r, (row, s) -> row.combatCenters = s, (row, u) -> row.useGlobalCombatCenters = u);
        applyAccountCenterUiState(stWc, r, (row, s) -> row.wcCenters = s, (row, u) -> row.useGlobalWcCenters = u);
        applyAccountCenterUiState(stMine, r, (row, s) -> row.miningCenters = s, (row, u) -> row.useGlobalMiningCenters = u);
        applyAccountCenterUiState(stFish, r, (row, s) -> row.fishingCenters = s, (row, u) -> row.useGlobalFishingCenters = u);
        applyAccountCenterUiState(stImps, r, (row, s) -> row.impsCenters = s, (row, u) -> row.useGlobalImpsCenters = u);
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

    /** Tijdelijke UI-state voor subset per skill in account-dialoog. */
    private static final class AccountCenterUiState {
        final String masterBlob;
        String accountSubsetBlob;
        boolean mainOn;

        AccountCenterUiState(String master, String accountSubset, boolean useGlobal) {
            this.masterBlob = master != null ? master : "";
            this.accountSubsetBlob = accountSubset != null ? accountSubset : "";
            this.mainOn = useGlobal;
        }
    }

    private JPanel buildAccountSkillCenterRow(Window parent, String skillLabel, AccountCenterUiState st) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setBackground(BG_DARK);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JCheckBox mainCb = new JCheckBox(skillLabel + " — gebruik globale lijst");
        mainCb.setSelected(st.mainOn);
        mainCb.setFont(FONT_LABEL);
        mainCb.setBackground(BG_DARK);
        mainCb.setForeground(TEXT);

        JLabel statusLbl = new JLabel();
        statusLbl.setFont(FONT_LABEL);
        statusLbl.setForeground(TEXT_DIM);
        Runnable refreshStatus = () -> updateAccountCenterStatusLabel(statusLbl, st);
        refreshStatus.run();

        JButton locBtn = new JButton("Locaties…");
        styleSmallButton(locBtn, new Color(55, 75, 110));
        locBtn.setEnabled(st.mainOn);
        locBtn.addActionListener(e -> {
            if (openAccountCenterSubsetDialog(parent, skillLabel, st)) {
                refreshStatus.run();
            }
        });
        mainCb.addActionListener(e -> {
            st.mainOn = mainCb.isSelected();
            locBtn.setEnabled(st.mainOn);
            if (!st.mainOn) {
                st.accountSubsetBlob = "";
            }
            refreshStatus.run();
        });

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.setOpaque(false);
        right.add(statusLbl);
        right.add(locBtn);

        row.add(mainCb, BorderLayout.WEST);
        row.add(right, BorderLayout.EAST);
        return row;
    }

    private static void updateAccountCenterStatusLabel(JLabel statusLbl, AccountCenterUiState st) {
        if (!st.mainOn) {
            statusLbl.setText("(uit)");
            return;
        }
        List<CenterManager.Center> master = CenterManager.parse(st.masterBlob);
        if (master.isEmpty()) {
            statusLbl.setText("geen globale centers");
            return;
        }
        if (st.accountSubsetBlob == null || st.accountSubsetBlob.trim().isEmpty()) {
            statusLbl.setText("alle " + master.size() + " loc.");
            return;
        }
        int n = CenterManager.parse(st.accountSubsetBlob).size();
        statusLbl.setText(n + "/" + master.size() + " loc.");
    }

    /**
     * Opent subdialoog met checkboxes per globale locatie. Retourneert true bij OK.
     */
    private boolean openAccountCenterSubsetDialog(Window parent, String skillLabel, AccountCenterUiState st) {
        List<CenterManager.Center> master = CenterManager.parse(st.masterBlob);
        if (master.isEmpty()) {
            JOptionPane.showMessageDialog(parent,
                    "Geen centers op de tab Centers voor " + skillLabel + ".",
                    "Geen locaties",
                    JOptionPane.INFORMATION_MESSAGE);
            return false;
        }
        boolean[] mask = CenterManager.selectionMaskFromSubset(st.masterBlob, st.accountSubsetBlob);
        JPanel grid = new JPanel();
        grid.setLayout(new BoxLayout(grid, BoxLayout.Y_AXIS));
        grid.setBackground(BG_DARK);
        JCheckBox[] boxes = new JCheckBox[master.size()];
        for (int i = 0; i < master.size(); i++) {
            CenterManager.Center c = master.get(i);
            String lab = (c.name != null && !c.name.isEmpty()) ? c.name
                    : ("(" + c.point.getX() + "," + c.point.getY() + ")");
            JCheckBox cb = new JCheckBox(lab + "  r=" + c.radius);
            cb.setSelected(mask[i]);
            cb.setFont(FONT_LABEL);
            cb.setBackground(BG_DARK);
            cb.setForeground(TEXT);
            boxes[i] = cb;
            grid.add(cb);
        }
        JScrollPane sp = new JScrollPane(grid);
        sp.setPreferredSize(new Dimension(340, Math.min(280, 40 + master.size() * 28)));
        int ok = JOptionPane.showConfirmDialog(parent, sp,
                skillLabel + " — locaties",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) {
            return false;
        }
        boolean[] sel = new boolean[boxes.length];
        for (int i = 0; i < boxes.length; i++) {
            sel[i] = boxes[i].isSelected();
        }
        String built = CenterManager.buildSubsetFromSelection(st.masterBlob, sel);
        int picked = CenterManager.parse(built).size();
        if (picked == 0) {
            JOptionPane.showMessageDialog(parent,
                    "Kies minstens één locatie, of zet de skill uit.",
                    "Geen locatie",
                    JOptionPane.WARNING_MESSAGE);
            return false;
        }
        if (CenterManager.subsetCoversAllGlobal(st.masterBlob, built)) {
            st.accountSubsetBlob = "";
        } else {
            st.accountSubsetBlob = built;
        }
        return true;
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

    private JScrollPane createDebugTab() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(BG_DARK);

        // Top controls
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        controls.setBackground(BG_DARK);

        JCheckBox enableCb = new JCheckBox("Debug Log Aan");
        enableCb.setSelected(DebugLog.isEnabled());
        enableCb.setFont(FONT_LABEL);
        enableCb.setForeground(GREEN);
        enableCb.setBackground(BG_DARK);
        enableCb.addActionListener(e -> {
            DebugLog.setEnabled(enableCb.isSelected());
            refreshDebugLogViews();
        });
        controls.add(enableCb);

        JCheckBox starterMeleeDbgCb = new JCheckBox("Starter melee-style debug");
        starterMeleeDbgCb.setSelected(config.starterMeleeStyleDebug());
        starterMeleeDbgCb.setFont(FONT_LABEL);
        starterMeleeDbgCb.setForeground(TEXT);
        starterMeleeDbgCb.setBackground(BG_DARK);
        starterMeleeDbgCb.addActionListener(e -> setConfig("starterMeleeStyleDebug", starterMeleeDbgCb.isSelected()));
        controls.add(starterMeleeDbgCb);

        JCheckBox impsOpenerDbgCb = new JCheckBox("Imps melee-opener debug");
        impsOpenerDbgCb.setSelected(config.impsMeleeOpeningAirStrikeDebug());
        impsOpenerDbgCb.setFont(FONT_LABEL);
        impsOpenerDbgCb.setForeground(TEXT);
        impsOpenerDbgCb.setBackground(BG_DARK);
        impsOpenerDbgCb.addActionListener(e -> setConfig("impsMeleeOpeningAirStrikeDebug", impsOpenerDbgCb.isSelected()));
        controls.add(impsOpenerDbgCb);

        JCheckBox walkClickDbgCb = new JCheckBox("Walk-klik tiles (overlay)");
        walkClickDbgCb.setSelected(config.debugWalkClickOverlay());
        walkClickDbgCb.setFont(FONT_LABEL);
        walkClickDbgCb.setForeground(TEXT);
        walkClickDbgCb.setBackground(BG_DARK);
        walkClickDbgCb.setToolTipText("<html>Max. 1000 tiles, 24 uur TTL.<br>Plugin Config → Bot Control → zelfde optie.</html>");
        walkClickDbgCb.addActionListener(e -> setConfig("debugWalkClickOverlay", walkClickDbgCb.isSelected()));
        controls.add(walkClickDbgCb);

        JButton resetWalkTilesBtn = new JButton("↺ Reset walk-tiles");
        resetWalkTilesBtn.setFont(new Font("Arial", Font.PLAIN, 10));
        resetWalkTilesBtn.setPreferredSize(new Dimension(118, 24));
        resetWalkTilesBtn.setBackground(new Color(55, 75, 95));
        resetWalkTilesBtn.setForeground(Color.WHITE);
        resetWalkTilesBtn.setFocusPainted(false);
        resetWalkTilesBtn.setToolTipText("Wist alle getekende walk-klik tiles (overlay kan aan blijven).");
        resetWalkTilesBtn.addActionListener(e -> MovementHelper.clearDebugWalkHighlights());
        controls.add(resetWalkTilesBtn);

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
        controls.add(clearBtn);

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

        JButton openFolderBtn = new JButton("📁 Map prive-logs");
        openFolderBtn.setFont(new Font("Arial", Font.PLAIN, 10));
        openFolderBtn.setPreferredSize(new Dimension(130, 24));
        openFolderBtn.setBackground(new Color(35, 85, 55));
        openFolderBtn.setForeground(Color.WHITE);
        openFolderBtn.setFocusPainted(false);
        openFolderBtn.setToolTipText("Opent de map met beide bestanden van vandaag (en eerdere dagen).");
        openFolderBtn.addActionListener(e -> openPriveLogsFolder());

        controls.add(openFileBtn);
        controls.add(openMlBtn);
        controls.add(openFolderBtn);

        JLabel debugHint = new JLabel("<html><div style='color:#a0a0b0;font-size:10px;width:460px'>"
                + "Staat <b>Debug Log Aan</b> uit, dan zie je hier geen nieuwe regels. "
                + "Gebruik het <b>Combat Bot</b>-icoon in de RuneLite-zijbalk (tab 🔍 Debug).<br>"
                + "<b>Naar AI/support:</b> knoppen hierboven → meestal het <b>.log</b>-bestand; bij widget/klik-problemen ook "
                + "<b>.jsonl</b> (zet ML-log eerst aan)."
                + "</div></html>");
        debugHint.setBorder(new EmptyBorder(0, 4, 4, 4));
        debugHint.setAlignmentX(Component.LEFT_ALIGNMENT);
        JPanel north = new JPanel();
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        north.setBackground(BG_DARK);
        north.add(controls);

        JPanel widgetInspectorBlock = new JPanel();
        widgetInspectorBlock.setLayout(new BoxLayout(widgetInspectorBlock, BoxLayout.Y_AXIS));
        widgetInspectorBlock.setBackground(BG_DARK);
        widgetInspectorBlock.setAlignmentX(Component.LEFT_ALIGNMENT);
        widgetInspectorBlock.setBorder(new EmptyBorder(6, 4, 10, 4));
        addSectionTitle(widgetInspectorBlock, "🔎 Widget inspector");
        JLabel wiHelp = new JLabel("<html><div style='color:#a8a8c0;font-size:10px;width:440px'>"
                + "De dump verschijnt in het <b>groene logveld hieronder</b> (bron <b>WIDGET</b>). Zet <b>Debug Log Aan</b> en "
                + "laat bron <b>WIDGET</b> niet uit staan in de vakjes erboven.<br>"
                + "Ook naar stdout/logbestand zoals andere debug-regels. Interval: alleen als je <b>ingelogd</b> bent.<br>"
                + "<b>Log menu-klikken (ML)</b>: bron <b>ML_CLICK</b> + bestand <code>prive-logs/combat-bot-ml-clicks-*.jsonl</code>.<br>"
                + "Voor vaste widgets in code: zie <b>WidgetRegistry</b> (alias + id uit dump). "
                + "Vink <b>Toon widget-info onder muis</b> voor een live tooltip (id/iface/naam). "
                + "Zelfde opties staan ook onder RuneLite <i>Plugin Configuration</i> → <b>Combat Bot</b> → <b>Widget inspector</b>.</div></html>");
        wiHelp.setAlignmentX(Component.LEFT_ALIGNMENT);
        wiHelp.setBorder(new EmptyBorder(0, 0, 6, 0));
        widgetInspectorBlock.add(wiHelp);

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
        addToggle(wiFields, "Log menu-klikken (ML) — jsonl + ML_CLICK", config.gameplayMlClickLog(),
                v -> setConfig("gameplayMlClickLog", v));
        addToggle(wiFields, "ML alleen handmatige klikken (geen bot)", config.gameplayMlClickLogOnlyAuthentic(),
                v -> setConfig("gameplayMlClickLogOnlyAuthentic", v));
        widgetInspectorBlock.add(wiFields);

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
        widgetInspectorBlock.add(wiBtnRow);
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
        sourceScroll.setBorder(new EmptyBorder(0, 4, 2, 4));
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
        panel.add(scroll, BorderLayout.CENTER);

        SwingUtilities.invokeLater(this::refreshDebugLogViews);

        JScrollPane outerScroll = new JScrollPane(panel);
        outerScroll.setBackground(BG_DARK);
        outerScroll.getViewport().setBackground(BG_DARK);
        outerScroll.setBorder(null);
        return outerScroll;
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
        detachedTabs.addTab("📍 Centers", createCentersTab());
        detachedTabs.addTab("🔍 Debug", createDebugTab());
        detachedTabs.setSelectedIndex(0);

        detachedFrame.add(detachedTabs, BorderLayout.CENTER);
        detachedFrame.setLocationRelativeTo(null);
        detachedFrame.setVisible(true);

        // Cleanup bij sluiten
        detachedFrame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                detachedFrame = null;
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
