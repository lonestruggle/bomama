package com.combatbot;

import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.WidgetID;
import net.runelite.client.util.Text;
import net.storm.api.domain.actors.IPlayer;
import net.storm.api.domain.actors.INPC;
import net.storm.api.domain.items.IInventoryItem;
import net.storm.api.domain.tiles.ITileObject;
import net.storm.api.domain.widgets.IWidget;
import net.storm.api.widgets.Tab;
import net.storm.sdk.entities.NPCs;
import net.storm.sdk.entities.Players;
import net.storm.sdk.entities.TileObjects;
import net.storm.sdk.items.Bank;
import net.storm.sdk.items.Equipment;
import net.storm.sdk.items.Inventory;
import net.storm.sdk.widgets.Dialog;
import net.storm.sdk.widgets.Tabs;
import net.storm.sdk.widgets.Widgets;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Beginner Treasure Trail solver: anagram, cryptic talk, emote, map dig, hot/cold, Charlie item.
 */
public final class BeginnerClueHandler {

    private static final int CLUE_TEXT_GROUP_PRIMARY = 203;
    private static final int[] CLUE_TEXT_GROUP_FALLBACKS = {78, 141, 259};
    /** Extra iface-groepen (TRAIL_CLUETEXT / varianten per client-build). */
    private static final int[] CLUE_TEXT_EXTRA_GROUPS = {193, 219, 229, 248, 345, 712};
    /** NPC/player dialoogtekst (TRAIL + standaard dialog widgets). */
    private static final int[] DIALOG_BODY_GROUPS = {231, 217, 219, 220, 229, 162, 193, 11};
    private static final long CLUE_WIDGET_WAIT_MS = 5000L;
    private static final int[] EMOTE_WIDGET_GROUPS = {216, 141, 593, 464, 187};
    private static final int[] MAP_INTERFACE_GROUPS = {
            WidgetID.BEGINNER_CLUE_MAP_CHAMPIONS_GUILD,
            WidgetID.BEGINNER_CLUE_MAP_VARROCK_EAST_MINE,
            WidgetID.BEGINNER_CLUE_MAP_DRAYNOR,
            WidgetID.BEGINNER_CLUE_MAP_NORTH_OF_FALADOR,
            WidgetID.BEGINNER_CLUE_MAP_WIZARDS_TOWER,
    };
    private static final int WALK_ARRIVAL_DISTANCE = 8;
    private static final int DIG_ARRIVAL_DISTANCE = 1;
    /** Onzekere hot/cold: max N tiles per stap, daarna opnieuw Feel. */
    private static final int HOT_COLD_PROBE_TILES = 10;
    private static final int NPC_TALK_DISTANCE = 6;
    private static final String ITEM_SPADE = "Spade";
    private static final String ITEM_STRANGE_DEVICE = BeginnerClueReference.ITEM_STRANGE_DEVICE;
    /** Deur naar Reldo-kamer (Varrock Palace library). */
    private static final WorldPoint RELDO_LIBRARY_DOOR = new WorldPoint(3210, 3495, 0);
    private static final WorldPoint CHARLIE_TRAMP_TILE = BeginnerClueReference.CHARLIE_TRAMP_TILE;
    private static final WorldPoint RELDO_INSIDE_TILE = new WorldPoint(3210, 3492, 0);
    private static final int RELDO_DOOR_OPEN_RANGE = 2;
    private static final WorldPoint GE_TILE = new WorldPoint(3164, 3486, 0);
    private static final int MIN_GE_COINS = 8000;

    private enum PrepStep {
        WALK_BANK,
        OPEN_BANK,
        DEPOSIT_INV,
        WITHDRAW_KIT,
        WITHDRAW_COINS,
        GE_BUY,
        RELDO_DEVICE,
        DONE
    }

    private static final int MAX_RELDO_DEVICE_PREP_ATTEMPTS = 12;

    private final CombatBotConfig config;
    private final AntiBan antiBan;
    private final CombatBotPaint paint;

    private BeginnerClueReference.ClueEntry activeEntry;
    private String lastParsedClueText = "";
    private long lastNpcInteractMs;
    private long lastDialogMs;
    private long lastReadClueMs;
    /** Na Read: wacht op clue-UI — niet meteen opnieuw lezen of ESC sluiten. */
    private boolean awaitingClueWidgetAfterRead;
    private long awaitingClueWidgetSinceMs;
    /** Item uit Charlie-dialoog ("I really need a cooked trout" → Trout). */
    private String charlieRequestedItem;
    private long lastEmoteMs;
    private boolean emotePerformedThisStep;
    private boolean uriTalkedAfterEmote;
    private long lastDigMs;
    private volatile boolean deviceSaysDigHere;
    private int hotColdFeelAttempts;
    /** Laatste device-temp uit chat (Feel Strange device). */
    private volatile HotColdTemp lastHotColdTemp;
    private volatile boolean hotColdWarmerThanLast;
    private WorldPoint lastHotColdFeelTile;
    private boolean hotColdAwaitingReply;
    private long hotColdFeelSentMs;
    private static final long HOT_COLD_REPLY_TIMEOUT_MS = 3500L;
    private final BeginnerHotColdSolver hotColdSolver = new BeginnerHotColdSolver();
    /** Lopend probe-doel — bij aankomst eerst Feel, niet doorrennen. */
    private WorldPoint hotColdMoveTarget;

    /** OSRS hot/cold — lager = verder weg. Zie Wiki Strange device. */
    private enum HotColdTemp {
        ICE_COLD(0),
        VERY_COLD(1),
        COLD(2),
        WARM(3),
        HOT(4),
        VERY_HOT(5),
        INCREDIBLY_HOT(6),
        VISIBLY_SHAKING(7);

        final int rank;

        HotColdTemp(int rank) {
            this.rank = rank;
        }
    }
    private boolean bankPrepComplete;
    private PrepStep prepStep = PrepStep.WALK_BANK;
    private long lastBankActionMs;
    private int kitWithdrawIndex;
    private int geBuyIndex;
    private final List<String> geBuyQueue = new ArrayList<>();
    private int reldoDevicePrepAttempts;
    /** Na bank nog geen Strange device — Reldo alleen bij hot/cold-cryptic, niet bij Charlie/anagram. */
    private boolean reldoDevicePrepPending;
    private boolean geBatchAttempted;
    private int geBuyRetriesForCurrent;
    private boolean invKitScanned;
    private long lastReldoDoorClickMs;

    public BeginnerClueHandler(CombatBotConfig config, AntiBan antiBan, CombatBotPaint paint) {
        this.config = config;
        this.antiBan = antiBan;
        this.paint = paint;
    }

    /** Stop/pauze/plugin uit: GE/bank dicht, state reset. */
    public void cancelAndReset(String reason) {
        try {
            GeRestockHelper.closeGeIfOpen();
        } catch (Throwable ignored) {
        }
        try {
            if (Bank.isOpen()) {
                Bank.close();
            }
        } catch (Throwable ignored) {
        }
        closeClueScrollInterface();
        resetState();
        if (reason != null && !reason.isEmpty()) {
            DebugLog.log("Clue", "Solver gestopt: " + reason);
        }
        if (paint != null) {
            paint.setLastAntiBanAction("Beginner clue gestopt");
        }
    }

    public void resetState() {
        activeEntry = null;
        lastParsedClueText = "";
        emotePerformedThisStep = false;
        uriTalkedAfterEmote = false;
        deviceSaysDigHere = false;
        hotColdFeelAttempts = 0;
        lastHotColdTemp = null;
        hotColdWarmerThanLast = false;
        lastHotColdFeelTile = null;
        hotColdAwaitingReply = false;
        hotColdMoveTarget = null;
        hotColdSolver.reset();
        hotColdFeelSentMs = 0L;
        awaitingClueWidgetAfterRead = false;
        awaitingClueWidgetSinceMs = 0L;
        charlieRequestedItem = null;
        bankPrepComplete = false;
        prepStep = PrepStep.WALK_BANK;
        kitWithdrawIndex = 0;
        geBuyIndex = 0;
        geBuyQueue.clear();
        reldoDevicePrepAttempts = 0;
        reldoDevicePrepPending = false;
        geBatchAttempted = false;
        geBuyRetriesForCurrent = 0;
        invKitScanned = false;
        lastReldoDoorClickMs = 0L;
        if (paint != null) {
            paint.clearBeginnerClueKitDisplay();
        }
    }

    /** Geen stuck-recovery / account-switch tijdens bank- of GE-prep. */
    public boolean shouldSuppressStuckAndLogout() {
        if (!bankPrepComplete) {
            return true;
        }
        if (paint == null) {
            return false;
        }
        String st = paint.getCurrentStatus();
        return st != null && st.toLowerCase(Locale.ROOT).contains("beginner clue");
    }

    /** Hot/cold device chat (CombatBotPlugin onChatMessage). */
    public void onChatMessage(String msg) {
        if (msg == null || msg.isEmpty()) {
            return;
        }
        tryCaptureClueTextFromChat(msg);
        tryCaptureCharlieItemFromChat(msg);
        String low = Text.removeTags(msg).toLowerCase(Locale.ROOT);
        if (!low.contains("device")) {
            return;
        }
        hotColdWarmerThanLast = low.contains("warmer than last");
        HotColdTemp parsed = parseHotColdDeviceMessage(low);
        if (parsed != null) {
            lastHotColdTemp = parsed;
            DebugLog.log("Clue", "Device temp: " + parsed.name()
                    + (hotColdWarmerThanLast ? " (warmer)" : ""));
        }
        WorldPoint feelAt = lastHotColdFeelTile;
        if (feelAt == null) {
            try {
                IPlayer p = Players.getLocal();
                if (p != null) {
                    feelAt = p.getWorldLocation();
                }
            } catch (Throwable ignored) {
            }
        }
        if (feelAt != null && hotColdSolver.signal(feelAt, low)) {
            int n = hotColdSolver.getPossibleZones().size();
            BeginnerHotColdSolver.BeginnerZone sole = hotColdSolver.soleZone();
            if (sole != null) {
                BeginnerClueReference.ClueEntry e = entryForHotColdZone(sole);
                if (e != null) {
                    activeEntry = e;
                }
                DebugLog.log("Clue", "Hot/cold zone: " + sole.label + " @ "
                        + sole.center.getX() + "," + sole.center.getY());
            } else {
                DebugLog.log("Clue", "Hot/cold solver: " + n + " zones nog mogelijk");
            }
            if (hotColdSolver.hasFinalDigSpot()) {
                deviceSaysDigHere = true;
                DebugLog.log("Clue", "Device: visibly shaking — dig hier");
            }
        }
        if (low.contains("need to dig") || low.contains("dig here")) {
            deviceSaysDigHere = true;
        }
    }

    private static BeginnerClueReference.ClueEntry entryForHotColdZone(
            BeginnerHotColdSolver.BeginnerZone zone) {
        if (zone == null) {
            return null;
        }
        switch (zone) {
            case DRAYNOR_WHEAT:
                return BeginnerClueReference.entryByTextMatch("draynor wheat");
            case ICE_MOUNTAIN:
                return BeginnerClueReference.entryByTextMatch("ice mountain");
            case LUMBRIDGE_COW:
                return BeginnerClueReference.entryByTextMatch("cow field north of lumbridge");
            case DRAYNOR_MUSHROOMS:
                return BeginnerClueReference.entryByTextMatch("mushrooms north-west of draynor manor");
            case AL_KHARID_MINE:
                return BeginnerClueReference.entryByTextMatch("north-east of al kharid mine");
            default:
                return null;
        }
    }

    private static HotColdTemp parseHotColdDeviceMessage(String low) {
        if (low.contains("visibly shaking")) {
            return HotColdTemp.VISIBLY_SHAKING;
        }
        if (low.contains("incredibly hot")) {
            return HotColdTemp.INCREDIBLY_HOT;
        }
        if (low.contains("very hot")) {
            return HotColdTemp.VERY_HOT;
        }
        if (low.contains("very cold")) {
            return HotColdTemp.VERY_COLD;
        }
        if (low.contains("ice cold")) {
            return HotColdTemp.ICE_COLD;
        }
        if (low.contains("the device is hot")) {
            return HotColdTemp.HOT;
        }
        if (low.contains("the device is warm")) {
            return HotColdTemp.WARM;
        }
        if (low.contains("the device is cold")) {
            return HotColdTemp.COLD;
        }
        return null;
    }

    public static boolean hasBeginnerClueScroll() {
        try {
            if (Inventory.contains(BeginnerClueReference.ITEM_CLUE_SCROLL)) {
                return true;
            }
            return Inventory.getFirst(BeginnerClueHandler::isBeginnerClueInventoryItem) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isBeginnerClueInventoryItem(IInventoryItem item) {
        if (item == null) {
            return false;
        }
        if (item.getId() == BeginnerClueReference.ITEM_CLUE_SCROLL) {
            return true;
        }
        String name = item.getName();
        if (name == null) {
            return false;
        }
        String low = name.toLowerCase(Locale.ROOT);
        return low.contains("clue scroll") && low.contains("beginner");
    }

    public int loop() {
        if (config == null || !config.botEnabled()) {
            return 0;
        }
        IPlayer local = Players.getLocal();
        if (local == null || local.getWorldLocation() == null) {
            return 1000;
        }
        if (!hasBeginnerClueScroll()) {
            return obtainClueScrollLoop(local);
        }

        if (!bankPrepComplete) {
            Integer prepDialog = handleDialog();
            if (prepDialog != null) {
                return prepDialog;
            }
            return bankPrepLoop(local);
        }

        if (activeEntry != null) {
            dismissClueScrollUiIfBlocking();
        }
        if (!isCharlieStepActive()) {
            Integer dialogDelay = handleDialog();
            if (dialogDelay != null) {
                return dialogDelay;
            }
        }

        if (activeEntry == null || shouldRunClueTextRefresh()) {
            int readDelay = ensureClueTextLoaded();
            if (readDelay > 0) {
                return readDelay;
            }
            BeginnerClueReference.ClueEntry matched = matchCurrentClueText();
            if (matched == null) {
                matched = matchByOpenMapInterface();
            }
            matched = resolveClueEntryWithDeviceState(matched);
            if (matched == null) {
                paint.setCurrentStatus("Beginner clue: tekst niet herkend — lees scroll");
                if (!lastParsedClueText.isEmpty()) {
                    DebugLog.log("Clue", "Tekst niet herkend: " + truncate(lastParsedClueText, 90));
                }
                awaitingClueWidgetAfterRead = false;
                return 2500;
            }
            awaitingClueWidgetAfterRead = false;
            if (activeEntry != matched) {
                activeEntry = matched;
                charlieRequestedItem = null;
                emotePerformedThisStep = false;
                uriTalkedAfterEmote = false;
                hotColdFeelAttempts = 0;
                deviceSaysDigHere = false;
                hotColdSolver.reset();
                DebugLog.log("Clue", "Stap: " + matched.type + " | " + matched.solution);
            }
            closeClueScrollInterface();
        }

        if (reldoDevicePrepPending && !hasInventoryItem(ITEM_STRANGE_DEVICE)) {
            if (activeEntry != null
                    && activeEntry.type != BeginnerClueReference.StepType.CRYPTIC_SPECIAL
                    && activeEntry.type != BeginnerClueReference.StepType.HOT_COLD) {
                reldoDevicePrepPending = false;
                DebugLog.log("Clue", "Reldo-prep overgeslagen (stap " + activeEntry.type + ")");
            } else {
                return reldoDevicePrepLoop(local);
            }
        }

        paint.setCurrentStatus("Beginner clue · " + activeEntry.type + " — " + truncate(activeEntry.solution, 48));

        switch (activeEntry.type) {
            case ANAGRAM:
                return handleTalkNpc(local);
            case CRYPTIC_TALK:
                if (isCharlieNpc(activeEntry.npcName)) {
                    return handleCharlieCrypticStep(local);
                }
                return handleTalkNpc(local);
            case EMOTE:
                return handleEmoteStep(local);
            case MAP:
                return handleDigStep(local, false);
            case HOT_COLD:
                return handleHotColdStep(local);
            case CRYPTIC_SPECIAL:
                return handleCrypticSpecialStep(local);
            case CHARLIE_TRAMP:
                return handleCharlieStep(local);
            default:
                return 2000;
        }
    }

    /** Geen scroll in inv: uit bank halen (id 23182) vóór bank-kit prep. */
    private int obtainClueScrollLoop(IPlayer local) {
        paint.setCurrentStatus("Beginner clue · scroll ophalen (bank)");
        if (Bank.isOpen()) {
            if (bankThrottle()) {
                return 350;
            }
            if (withdrawBeginnerScrollFromOpenBank()) {
                bankPrepComplete = false;
                prepStep = PrepStep.WALK_BANK;
                paint.setLastAntiBanAction("Clue scroll uit bank");
                return vary(800, 1400);
            }
            Bank.close();
            paint.setLastAntiBanAction("Clue: geen beginner scroll in bank");
            DebugLog.log("Clue", "Geen clue scroll (23182) in bank — zet scroll in inv of bank");
            return 3500;
        }
        if (!BankHelper.isNearFullBank()) {
            BankHelper.walkToNearestFullBank("Clue scroll", "Clue scroll (beginner)");
            paint.setLastAntiBanAction("Clue: → bank (scroll)");
            return travelDelay();
        }
        BankHelper.openSdkBankAndWait();
        lastBankActionMs = System.currentTimeMillis();
        return vary(900, 1500);
    }

    private boolean withdrawBeginnerScrollFromOpenBank() {
        try {
            if (Bank.contains(item -> item != null && item.getId() == BeginnerClueReference.ITEM_CLUE_SCROLL)) {
                Bank.withdraw(BeginnerClueReference.ITEM_CLUE_SCROLL, 1);
                HumanBanking.pauseBetweenActions();
                DebugLog.log("Clue", "Bank withdraw clue scroll id=" + BeginnerClueReference.ITEM_CLUE_SCROLL);
                return true;
            }
            String[] names = {"Clue scroll (beginner)", "Clue scroll"};
            for (String name : names) {
                if (Bank.contains(name)) {
                    Bank.withdraw(name, 1);
                    HumanBanking.pauseBetweenActions();
                    DebugLog.log("Clue", "Bank withdraw: " + name);
                    return true;
                }
            }
        } catch (Throwable t) {
            DebugLog.log("Clue", "withdrawBeginnerScrollFromOpenBank: " + t.getMessage());
        }
        return false;
    }

    private int bankPrepLoop(IPlayer local) {
        if (Bank.isOpen() && prepStep == PrepStep.WALK_BANK) {
            prepStep = PrepStep.DEPOSIT_INV;
        }

        switch (prepStep) {
            case WALK_BANK:
                if (!invKitScanned) {
                    refreshKitAvailability(false);
                    invKitScanned = true;
                }
                if (canSkipBankPrep()) {
                    DebugLog.log("Clue", "Bank prep overgeslagen: volledige kit in inv, geen overbodige items");
                    paint.setCurrentStatus(kitStatusLine("bank overgeslagen"));
                    paint.setLastAntiBanAction("Clue prep: kit al in inv — geen bank");
                    buildGeBuyQueue();
                    if (shouldBuyMissingAtGe() && !geBuyQueue.isEmpty()) {
                        prepStep = PrepStep.GE_BUY;
                        geBatchAttempted = false;
                        return vary(400, 700);
                    }
                    prepStep = PrepStep.DONE;
                    return finishBankPrep();
                }
                paint.setCurrentStatus(kitStatusLine("→ bank"));
                paint.setLastAntiBanAction("Clue prep: naar bank");
                if (Bank.isOpen()) {
                    refreshKitAvailability(true);
                    prepStep = PrepStep.DEPOSIT_INV;
                    return vary(400, 700);
                }
                if (!BankHelper.isNearFullBank()) {
                    BankHelper.walkToNearestFullBank(BeginnerClueReference.allKitItemNames());
                    return travelDelay();
                }
                prepStep = PrepStep.OPEN_BANK;
                return vary(500, 900);
            case OPEN_BANK:
                if (Bank.isOpen()) {
                    refreshKitAvailability(true);
                    prepStep = PrepStep.DEPOSIT_INV;
                    return vary(300, 600);
                }
                BankHelper.openSdkBankAndWait();
                lastBankActionMs = System.currentTimeMillis();
                return vary(1000, 1600);
            case DEPOSIT_INV:
                if (!Bank.isOpen()) {
                    prepStep = PrepStep.OPEN_BANK;
                    return vary(500, 900);
                }
                if (bankThrottle()) {
                    return 300;
                }
                refreshKitAvailability(true);
                depositInventoryExceptNeededKit();
                kitWithdrawIndex = 0;
                prepStep = PrepStep.WITHDRAW_KIT;
                paint.setLastAntiBanAction("Clue prep: rest gedeponeerd (kit+scroll behouden)");
                DebugLog.log("Clue", "Bank prep: deposit alles behalve clue-scroll + kit in inv");
                return vary(700, 1200);
            case WITHDRAW_KIT:
                if (!Bank.isOpen()) {
                    prepStep = PrepStep.OPEN_BANK;
                    return vary(500, 900);
                }
                if (bankThrottle()) {
                    return 300;
                }
                int w = withdrawNextKitItem();
                if (w > 0) {
                    return w;
                }
                refreshKitAvailability(true);
                prepStep = PrepStep.WITHDRAW_COINS;
                return vary(400, 700);
            case WITHDRAW_COINS:
                if (!Bank.isOpen()) {
                    prepStep = PrepStep.OPEN_BANK;
                    return vary(500, 900);
                }
                if (bankThrottle()) {
                    return 300;
                }
                if (config.beginnerClueGeBuyMissing() && getCoinCount() < MIN_GE_COINS && Bank.contains("Coins")) {
                    Bank.withdraw("Coins", Integer.MAX_VALUE);
                    lastBankActionMs = System.currentTimeMillis();
                    paint.setLastAntiBanAction("Clue prep: coins voor GE");
                    return vary(700, 1100);
                }
                buildGeBuyQueue();
                if (config.beginnerClueGeBuyMissing() && !geBuyQueue.isEmpty()) {
                    Bank.close();
                    geBuyIndex = 0;
                    prepStep = PrepStep.GE_BUY;
                    paint.setLastAntiBanAction("Clue prep: GE " + geBuyQueue.size() + " item(s)");
                    return vary(600, 1000);
                }
                prepStep = nextPrepStepAfterGe();
                reldoDevicePrepAttempts = 0;
                return prepStep == PrepStep.RELDO_DEVICE
                        ? reldoDevicePrepLoop(local) : finishBankPrep();
            case GE_BUY:
                return geBuyMissingLoop(local);
            case DONE:
            default:
                return finishBankPrep();
        }
    }

    private int finishBankPrep() {
        if (Bank.isOpen()) {
            Bank.close();
        }
        bankPrepComplete = true;
        prepStep = PrepStep.DONE;
        reldoDevicePrepPending = !hasInventoryItem(ITEM_STRANGE_DEVICE);
        if (paint != null) {
            paint.clearBeginnerClueKitDisplay();
        }
        if (!hasInventoryItem(ITEM_SPADE)) {
            paint.setLastAntiBanAction("Clue prep klaar — geen spade, map/hot-cold lastig");
            DebugLog.log("Clue", "Bank prep klaar maar Spade ontbreekt");
        } else if (!hasInventoryItem(ITEM_STRANGE_DEVICE)) {
            paint.setLastAntiBanAction("Clue prep klaar — Strange device via Reldo bij hot/cold");
            DebugLog.log("Clue", "Bank prep klaar; Strange device nog niet (Reldo bij stap)");
        } else {
            paint.setLastAntiBanAction("Clue prep klaar → stappen");
            DebugLog.log("Clue", "Bank prep voltooid (incl. Strange device)");
        }
        return vary(600, 1000);
    }

    private boolean shouldBuyMissingAtGe() {
        return config == null || config.beginnerClueGeBuyMissing();
    }

    private PrepStep nextPrepStepAfterGe() {
        if (!hasInventoryItem(ITEM_STRANGE_DEVICE)) {
            return PrepStep.RELDO_DEVICE;
        }
        return PrepStep.DONE;
    }

    private int reldoDevicePrepLoop(IPlayer local) {
        if (hasInventoryItem(ITEM_STRANGE_DEVICE)) {
            reldoDevicePrepPending = false;
            prepStep = PrepStep.DONE;
            return finishBankPrep();
        }
        if (reldoDevicePrepAttempts >= MAX_RELDO_DEVICE_PREP_ATTEMPTS) {
            DebugLog.log("Clue", "Reldo prep: geen Strange device na " + MAX_RELDO_DEVICE_PREP_ATTEMPTS
                    + " pogingen (cryptic 'buried beneath' nodig?)");
            prepStep = PrepStep.DONE;
            return finishBankPrep();
        }
        paint.setCurrentStatus("Beginner clue · Reldo → Strange device");
        paint.setLastAntiBanAction("Clue prep: Reldo (geen device)");
        reldoDevicePrepAttempts++;
        return approachAndTalkToReldo(local);
    }

    /** Kit compleet in inv/equip; alleen bank als er junk staat of gp voor GE ontbreekt. */
    private boolean canSkipBankPrep() {
        if (!isFullKitInInventory()) {
            return false;
        }
        if (inventoryHasUnneededItems()) {
            return false;
        }
        if (shouldBuyMissingAtGe()) {
            buildGeBuyQueue();
            if (!geBuyQueue.isEmpty() && getCoinCount() < MIN_GE_COINS) {
                return false;
            }
        }
        return true;
    }

    private boolean isFullKitInInventory() {
        for (String item : BeginnerClueReference.allKitItemNames()) {
            if (BeginnerClueReference.isReldoOnlyItem(item)) {
                continue;
            }
            if (!hasKitItemAnywhere(item)) {
                return false;
            }
        }
        return true;
    }

    /** Iets in inv dat geen clue-scroll, kit-item of coins is. */
    private static boolean inventoryHasUnneededItems() {
        try {
            for (IInventoryItem item : Inventory.getAll()) {
                if (item == null || item.getName() == null) {
                    continue;
                }
                if (item.getId() == BeginnerClueReference.ITEM_CLUE_SCROLL) {
                    continue;
                }
                if (BeginnerClueReference.isStrangeDeviceItem(item.getName(), item.getId())) {
                    continue;
                }
                if ("Coins".equalsIgnoreCase(item.getName())) {
                    continue;
                }
                if (isNeededKitItemName(item.getName())) {
                    continue;
                }
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void buildGeBuyQueue() {
        geBuyQueue.clear();
        for (String item : BeginnerClueReference.allKitItemNames()) {
            if (!BeginnerClueReference.isGePurchasableKitItem(item)) {
                continue;
            }
            String canon = BeginnerClueReference.canonicalKitItemName(item);
            if (!hasKitItemAnywhere(canon) && !geBuyQueue.contains(canon)) {
                geBuyQueue.add(canon);
            }
        }
    }

    /**
     * GE: één item per game-tick (geen lange batch op de main thread — stop werkt weer).
     */
    private int geBuyMissingLoop(IPlayer local) {
        if (!isBotEnabled()) {
            GeRestockHelper.closeGeIfOpen();
            return 0;
        }
        WorldPoint me = local.getWorldLocation();
        if (me != null && me.distanceTo(GE_TILE) > 14) {
            MovementHelper.walkTo(GE_TILE);
            paint.setCurrentStatus("Beginner clue · GE → kit");
            return travelDelay();
        }
        if (getCoinCount() < MIN_GE_COINS) {
            int coinPull = tryPullCoinsFromBankForGe(local);
            if (coinPull > 0) {
                return coinPull;
            }
        }

        if (!geBatchAttempted) {
            geBatchAttempted = true;
            geBuyIndex = 0;
            geBuyRetriesForCurrent = 0;
            buildGeBuyQueue();
            DebugLog.log("Clue", "GE start: " + geBuyQueue.size() + " item(s), 1 per tick");
        }

        while (geBuyIndex < geBuyQueue.size()) {
            String skip = geBuyQueue.get(geBuyIndex);
            if (hasKitItemAnywhere(skip)) {
                geBuyIndex++;
                geBuyRetriesForCurrent = 0;
            } else {
                break;
            }
        }

        if (geBuyIndex >= geBuyQueue.size()) {
            GeRestockHelper.closeGeIfOpen();
            refreshKitAvailability(false);
            prepStep = nextPrepStepAfterGe();
            reldoDevicePrepAttempts = 0;
            return prepStep == PrepStep.RELDO_DEVICE ? reldoDevicePrepLoop(local) : finishBankPrep();
        }

        String item = geBuyQueue.get(geBuyIndex);
        int price = BeginnerClueReference.geStartPrice(item);
        if (getCoinCount() < price) {
            DebugLog.log("Clue", "GE skip (gp): " + item);
            geBuyIndex++;
            geBuyRetriesForCurrent = 0;
            return vary(600, 1000);
        }

        boolean lastItem = geBuyIndex >= geBuyQueue.size() - 1;
        paint.setCurrentStatus(kitStatusLine("GE " + (geBuyIndex + 1) + "/" + geBuyQueue.size() + " " + item));
        paint.setLastAntiBanAction("Clue GE: " + item);
        DebugLog.log("Clue", "GE koop: " + item + " @" + price + "gp");

        GeRestockHelper.RestockResult r = GeRestockHelper.buyWithEscalation(
                item, 1, price, this::isBotEnabled, lastItem);

        if (!isBotEnabled()) {
            GeRestockHelper.closeGeIfOpen();
            return 0;
        }

        if (r == GeRestockHelper.RestockResult.SUCCESS
                || r == GeRestockHelper.RestockResult.BLOCKED_BY_ACCOUNT_POLICY
                || hasKitItemAnywhere(item)) {
            geBuyIndex++;
            geBuyRetriesForCurrent = 0;
            DebugLog.log("Clue", "GE ok: " + item + " (" + r + ")");
        } else {
            geBuyRetriesForCurrent++;
            DebugLog.log("Clue", "GE mislukt: " + item + " (" + r + ") poging " + geBuyRetriesForCurrent);
            if (geBuyRetriesForCurrent >= 2) {
                geBuyIndex++;
                geBuyRetriesForCurrent = 0;
            }
        }

        return vary(900, 1500);
    }

    private boolean isBotEnabled() {
        return config != null && config.botEnabled();
    }

    /** Extra bank-trip voor coins vóór GE als inv te weinig gp heeft. */
    private int tryPullCoinsFromBankForGe(IPlayer local) {
        if (Bank.isOpen()) {
            if (Bank.contains("Coins")) {
                Bank.withdraw("Coins", Integer.MAX_VALUE);
                lastBankActionMs = System.currentTimeMillis();
                return vary(700, 1100);
            }
            Bank.close();
            return vary(400, 700);
        }
        if (!BankHelper.isNearFullBank()) {
            BankHelper.walkToNearestFullBank("Coins");
            return travelDelay();
        }
        BankHelper.openSdkBankAndWait();
        return vary(900, 1400);
    }

    private boolean bankThrottle() {
        long now = System.currentTimeMillis();
        if (now - lastBankActionMs < 550) {
            return true;
        }
        lastBankActionMs = now;
        return false;
    }

    /** Deponeer alles behalve clue-scroll en kit-items die al in inv liggen (vóór bank-withdraw). */
    private void depositInventoryExceptNeededKit() {
        try {
            Bank.setWithdrawMode(false);
        } catch (Throwable ignored) {
        }
        try {
            for (IInventoryItem item : Inventory.getAll()) {
                if (item == null) {
                    continue;
                }
                if (item.getId() == BeginnerClueReference.ITEM_CLUE_SCROLL) {
                    continue;
                }
                if (isInventoryItemNeededForClue(item)) {
                    continue;
                }
                String name = item.getName();
                if (name == null || name.isEmpty()) {
                    continue;
                }
                Bank.depositAll(name);
                HumanBanking.pauseBetweenActions();
            }
        } catch (Throwable t) {
            DebugLog.log("Clue", "depositInventoryExceptNeededKit: " + t.getMessage());
        }
    }

    private static boolean isInventoryItemNeededForClue(IInventoryItem item) {
        if (item == null) {
            return false;
        }
        if (item.getId() == BeginnerClueReference.ITEM_CLUE_SCROLL) {
            return true;
        }
        if (BeginnerClueReference.isStrangeDeviceItem(item.getName(), item.getId())) {
            return true;
        }
        return isNeededKitItemName(item.getName());
    }

    private static boolean isNeededKitItemName(String inventoryItemName) {
        if (inventoryItemName == null || inventoryItemName.isEmpty()) {
            return false;
        }
        if (BeginnerClueReference.isStrangeDeviceItem(inventoryItemName, -1)) {
            return true;
        }
        for (String kit : BeginnerClueReference.allKitItemNames()) {
            if (BeginnerClueReference.kitRequirementMetByItemName(kit, inventoryItemName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Scant inv (en optioneel bank) en werkt gekleurde kit-lijst op overlay/panel bij.
     */
    private void refreshKitAvailability(boolean bankAccessible) {
        if (paint == null) {
            return;
        }
        List<CombatBotPaint.BeginnerKitLine> lines = new ArrayList<>();
        int have = 0;
        int total = 0;
        for (String item : BeginnerClueReference.allKitItemNames()) {
            total++;
            if (BeginnerClueReference.isReldoOnlyItem(item)) {
                boolean owned = hasKitItemAnywhere(item)
                        || inventoryOrEquipmentMatchesId(BeginnerClueReference.ITEM_STRANGE_DEVICE_ID);
                CombatBotPaint.BeginnerKitAvailability avail = owned
                        ? CombatBotPaint.BeginnerKitAvailability.IN_INVENTORY
                        : CombatBotPaint.BeginnerKitAvailability.RELDO_ONLY;
                if (owned) {
                    have++;
                }
                lines.add(new CombatBotPaint.BeginnerKitLine(item, avail));
                continue;
            }
            boolean inv = hasKitItemAnywhere(item);
            boolean bank = bankAccessible && bankContainsKitItem(item);
            CombatBotPaint.BeginnerKitAvailability avail;
            if (inv) {
                avail = CombatBotPaint.BeginnerKitAvailability.IN_INVENTORY;
                have++;
            } else if (bank) {
                avail = CombatBotPaint.BeginnerKitAvailability.IN_BANK;
                have++;
            } else {
                avail = CombatBotPaint.BeginnerKitAvailability.MISSING;
            }
            lines.add(new CombatBotPaint.BeginnerKitLine(item, avail));
        }
        paint.setBeginnerClueKitDisplay(lines, !bankPrepComplete, have, total);
        DebugLog.log("Clue", "Kit scan: " + have + "/" + total + " (bank=" + bankAccessible + ")");
    }

    private String kitStatusLine(String phase) {
        if (paint == null) {
            return "Beginner clue · " + phase;
        }
        return "Beginner clue · kit " + paint.getBeginnerClueKitHaveCount()
                + "/" + paint.getBeginnerClueKitTotalCount() + " · " + phase;
    }

    private static boolean withdrawKitFromOpenBank(String requiredKitName) {
        try {
            var stack = Bank.getFirst(i -> i != null && i.getName() != null
                    && BeginnerClueReference.kitRequirementMetByItemName(requiredKitName, i.getName()));
            if (stack == null || stack.getName() == null) {
                return false;
            }
            Bank.withdraw(stack.getName(), 1);
            return true;
        } catch (Throwable t) {
            DebugLog.log("Clue", "withdrawKitFromOpenBank(" + requiredKitName + "): " + t.getMessage());
            return false;
        }
    }

    private static boolean bankContainsKitItem(String requiredKitName) {
        try {
            if (Bank.contains(requiredKitName)) {
                return true;
            }
            return Bank.contains(item -> item != null && item.getName() != null
                    && BeginnerClueReference.kitRequirementMetByItemName(requiredKitName, item.getName()));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** @return delay &gt; 0 als er nog een withdraw gebeurde */
    private int withdrawNextKitItem() {
        String[] kit = BeginnerClueReference.allKitItemNames();
        while (kitWithdrawIndex < kit.length) {
            String item = kit[kitWithdrawIndex++];
            if (BeginnerClueReference.isReldoOnlyItem(item)) {
                continue;
            }
            if (hasKitItemAnywhere(item)) {
                continue;
            }
            if (!bankContainsKitItem(item)) {
                continue;
            }
            if (!withdrawKitFromOpenBank(item)) {
                continue;
            }
            HumanBanking.pauseBetweenActions();
            refreshKitAvailability(true);
            paint.setLastAntiBanAction("Clue prep: " + item);
            DebugLog.log("Clue", "Bank withdraw: " + item);
            return vary(600, 1000);
        }
        return 0;
    }

    private static int getCoinCount() {
        try {
            return Inventory.getCount(true, "Coins");
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private boolean shouldRefreshClueText() {
        String current = readClueTextFromWidgets();
        return current != null && !current.isEmpty() && !current.equals(lastParsedClueText);
    }

    /** Alleen eerste keer scroll lezen — open clue-UI blokkeerde anders lopen/praten. */
    private boolean shouldRunClueTextRefresh() {
        return activeEntry == null;
    }

    private int ensureClueTextLoaded() {
        String fromWidget = readClueTextFromWidgets();
        if (fromWidget != null && fromWidget.length() >= 12) {
            lastParsedClueText = fromWidget;
            awaitingClueWidgetAfterRead = false;
            return 0;
        }
        if (!lastParsedClueText.isEmpty() && lastParsedClueText.length() >= 12
                && BeginnerClueReference.matchByClueText(lastParsedClueText) != null) {
            awaitingClueWidgetAfterRead = false;
            return 0;
        }
        long now = System.currentTimeMillis();
        if (awaitingClueWidgetAfterRead && now - awaitingClueWidgetSinceMs < CLUE_WIDGET_WAIT_MS) {
            return 400;
        }
        if (now - lastReadClueMs < 2800) {
            return 500;
        }
        IInventoryItem scroll = findBeginnerScrollItem();
        if (scroll == null) {
            return 1500;
        }
        String action = scroll.hasAction("Read") ? "Read" : (scroll.hasAction("Read-clue") ? "Read-clue" : null);
        if (action != null) {
            InventoryActionHelper.interact(config, scroll, action);
            lastReadClueMs = now;
            awaitingClueWidgetAfterRead = true;
            awaitingClueWidgetSinceMs = now;
            DebugLog.log("Clue", "Read clue scroll");
            return vary(900, 1500);
        }
        InventoryActionHelper.interact(config, scroll, 0);
        lastReadClueMs = now;
        awaitingClueWidgetAfterRead = true;
        awaitingClueWidgetSinceMs = now;
        return vary(900, 1500);
    }

    private void tryCaptureClueTextFromChat(String msg) {
        String plain = Text.removeTags(msg).replace('\n', ' ').trim();
        if (plain.length() < 12) {
            return;
        }
        BeginnerClueReference.ClueEntry e = BeginnerClueReference.matchByClueText(plain);
        if (e == null) {
            return;
        }
        if (!plain.equals(lastParsedClueText)) {
            lastParsedClueText = plain;
            DebugLog.log("Clue", "Clue-tekst via chat: " + truncate(plain, 70));
        }
    }

    private BeginnerClueReference.ClueEntry matchCurrentClueText() {
        String text = readClueTextFromWidgets();
        if (text == null || text.length() < 8) {
            text = lastParsedClueText;
        }
        if (text == null || text.isEmpty()) {
            return matchByOpenMapInterface();
        }
        lastParsedClueText = text;
        BeginnerClueReference.ClueEntry matched = BeginnerClueReference.matchByClueText(text);
        return matched != null ? matched : matchByOpenMapInterface();
    }

    private static BeginnerClueReference.ClueEntry matchByOpenMapInterface() {
        for (int group : MAP_INTERFACE_GROUPS) {
            if (!isInterfaceGroupVisible(group)) {
                continue;
            }
            BeginnerClueReference.ClueEntry e = BeginnerClueReference.matchByMapInterfaceGroup(group);
            if (e != null) {
                return e;
            }
        }
        return null;
    }

    private static boolean isInterfaceGroupVisible(int group) {
        try {
            for (int child = 0; child <= 4; child++) {
                IWidget w = Widgets.get(group, child);
                if (w != null && !w.isHidden()) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private int handleCrypticSpecialStep(IPlayer local) {
        if (!hasInventoryItem(ITEM_STRANGE_DEVICE)) {
            paint.setCurrentStatus("Beginner clue · Reldo → Strange device");
            paint.setLastAntiBanAction("Clue: Reldo → Strange device");
            DebugLog.log("Clue", "Geen device — loop naar Reldo (deur 3210,3495)");
            return approachAndTalkToReldo(local);
        }
        BeginnerClueReference.ClueEntry hot = findHotColdEntryInClueText();
        if (hot != null) {
            activeEntry = hot;
            hotColdFeelAttempts = 0;
            deviceSaysDigHere = false;
            DebugLog.log("Clue", "Device + scroll hot/cold: " + hot.textMatch);
            return handleHotColdStep(local);
        }
        DebugLog.log("Clue", "Device in inv — hot/cold (RuneLite solver, Feel om zone te vinden)");
        paint.setCurrentStatus("Beginner clue · hot/cold zone zoeken");
        paint.setLastAntiBanAction("Clue: hot/cold (device OK)");
        hotColdFeelAttempts = 0;
        deviceSaysDigHere = false;
        return handleHotColdStep(local);
    }

    /**
     * Scroll toont soms nog Reldo-tekst terwijl device al in inv zit — ga door naar hot/cold-match.
     */
    private BeginnerClueReference.ClueEntry resolveClueEntryWithDeviceState(
            BeginnerClueReference.ClueEntry matched) {
        if (matched == null || matched.type != BeginnerClueReference.StepType.CRYPTIC_SPECIAL) {
            return matched;
        }
        if (!hasInventoryItem(ITEM_STRANGE_DEVICE)) {
            return matched;
        }
        BeginnerClueReference.ClueEntry hot = findHotColdEntryInClueText();
        return hot != null ? hot : matched;
    }

    private BeginnerClueReference.ClueEntry findHotColdEntryInClueText() {
        String text = readClueTextFromWidgets();
        if (text == null || text.length() < 8) {
            text = lastParsedClueText;
        }
        if (text == null || text.isEmpty()) {
            return null;
        }
        BeginnerClueReference.ClueEntry e = BeginnerClueReference.matchByClueText(text);
        if (e != null && e.type == BeginnerClueReference.StepType.HOT_COLD) {
            return e;
        }
        return null;
    }

    private static void closeClueScrollInterface() {
        try {
            if (readClueTextFromWidgets() != null) {
                net.storm.sdk.input.Keyboard.type(
                        String.valueOf((char) java.awt.event.KeyEvent.VK_ESCAPE), false);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Clue-scroll UI telt soms als Dialog.open — sluit alleen ná stap-match (niet tijdens lezen). */
    private void dismissClueScrollUiIfBlocking() {
        if (activeEntry == null || awaitingClueWidgetAfterRead) {
            return;
        }
        if (readClueTextFromWidgets() != null) {
            closeClueScrollInterface();
        }
    }

    private int handleHotColdStep(IPlayer local) {
        if (!hasInventoryItem(ITEM_STRANGE_DEVICE)) {
            paint.setLastAntiBanAction("Clue: eerst Strange device (Reldo)");
            return approachAndTalkToReldo(local);
        }
        int spadeDelay = ensureInventoryItem(ITEM_SPADE);
        if (spadeDelay > 0) {
            return spadeDelay;
        }

        BeginnerClueReference.ClueEntry fromScroll = findHotColdEntryInClueText();
        if (fromScroll != null) {
            activeEntry = fromScroll;
        }

        BeginnerHotColdSolver.BeginnerZone sole = hotColdSolver.soleZone();
        if (sole != null) {
            BeginnerClueReference.ClueEntry e = entryForHotColdZone(sole);
            if (e != null) {
                activeEntry = e;
            }
        }

        WorldPoint me = local.getWorldLocation();
        if (me == null) {
            return 600;
        }

        int zonesLeft = hotColdSolver.getPossibleZones().size();
        paint.setCurrentStatus("Beginner clue · hot/cold"
                + (zonesLeft < 5 ? " (" + zonesLeft + " zones)" : " · Feel"));

        if (deviceSaysDigHere || hotColdSolver.hasFinalDigSpot()
                || lastHotColdTemp == HotColdTemp.VISIBLY_SHAKING) {
            hotColdMoveTarget = null;
            return digAtHotColdSpot(local);
        }

        WorldPoint dest = resolveHotColdDestination(sole);

        if (!hotColdSolver.hasAnyFeel()) {
            paint.setLastAntiBanAction("Clue hot/cold: eerste Feel (triangulatie)");
            int reply = deviceReplyState();
            if (reply == 1) {
                return vary(350, 650);
            }
            lastHotColdFeelTile = me;
            return clickStrangeDeviceFeel();
        }

        if (hotColdMoveTarget != null) {
            if (me.distanceTo(hotColdMoveTarget) > 2) {
                MovementHelper.walkToExact(hotColdMoveTarget);
                paint.setLastAntiBanAction("Clue hot/cold: probe → "
                        + hotColdMoveTarget.getX() + "," + hotColdMoveTarget.getY());
                return travelDelay();
            }
            hotColdMoveTarget = null;
            int reply = deviceReplyState();
            if (reply == 1) {
                return vary(350, 650);
            }
            lastHotColdFeelTile = me;
            paint.setLastAntiBanAction("Clue hot/cold: Feel na " + HOT_COLD_PROBE_TILES + " tiles");
            return clickStrangeDeviceFeel();
        }

        boolean zoneCertain = zonesLeft == 1;
        boolean nearDigArea = dest != null && me.distanceTo(dest) <= HOT_COLD_PROBE_TILES + 2;

        if (zoneCertain && nearDigArea) {
            if (dest != null && me.distanceTo(dest) > DIG_ARRIVAL_DISTANCE) {
                MovementHelper.walkToExact(dest);
                paint.setLastAntiBanAction("Clue hot/cold: zone bekend → centrum");
                return travelDelay();
            }
            if (hotColdFeelAttempts < 20) {
                int reply = deviceReplyState();
                if (reply == 1) {
                    return vary(350, 650);
                }
                if (reply == 2 || lastHotColdTemp == null) {
                    hotColdFeelAttempts++;
                    lastHotColdFeelTile = me;
                    return clickStrangeDeviceFeel();
                }
                HotColdTemp temp = lastHotColdTemp;
                boolean warmer = hotColdWarmerThanLast;
                lastHotColdTemp = null;
                if (temp == HotColdTemp.VISIBLY_SHAKING) {
                    return digAtHotColdSpot(local);
                }
                paint.setLastAntiBanAction(warmer
                        ? "Clue hot/cold: warmer — grid"
                        : "Clue hot/cold: zelfde temp — andere tile");
                MovementHelper.walkToExact(offsetHotColdSearch(me, hotColdFeelAttempts));
                return travelDelay();
            }
            return digAtHotColdSpot(local);
        }

        if (dest != null) {
            WorldPoint probe = stepToward(me, dest, HOT_COLD_PROBE_TILES);
            if (me.distanceTo(probe) <= 2) {
                int reply = deviceReplyState();
                if (reply == 1) {
                    return vary(350, 650);
                }
                lastHotColdFeelTile = me;
                return clickStrangeDeviceFeel();
            }
            hotColdMoveTarget = probe;
            MovementHelper.walkToExact(probe);
            paint.setLastAntiBanAction("Clue hot/cold: +" + HOT_COLD_PROBE_TILES + " tiles ("
                    + zonesLeft + " zones) → Feel");
            DebugLog.log("Clue", "Hot/cold probe " + me.getX() + "," + me.getY() + " → "
                    + probe.getX() + "," + probe.getY());
            return travelDelay();
        }

        if (hotColdFeelAttempts < 20) {
            lastHotColdFeelTile = me;
            return clickStrangeDeviceFeel();
        }
        return digAtHotColdSpot(local);
    }

    private WorldPoint resolveHotColdDestination(BeginnerHotColdSolver.BeginnerZone sole) {
        if (activeEntry != null && activeEntry.destination != null) {
            return activeEntry.destination;
        }
        if (sole != null) {
            return sole.center;
        }
        return hotColdSolver.centroidOfPossibilities();
    }

    /** Max {@code steps} tiles richting {@code target} (Chebyshev-richting). */
    private static WorldPoint stepToward(WorldPoint from, WorldPoint target, int steps) {
        if (from == null || target == null || steps <= 0) {
            return target;
        }
        int dx = target.getX() - from.getX();
        int dy = target.getY() - from.getY();
        if (dx == 0 && dy == 0) {
            return from;
        }
        int cheb = Math.max(Math.abs(dx), Math.abs(dy));
        if (cheb <= steps) {
            return target;
        }
        return new WorldPoint(
                from.getX() + dx * steps / cheb,
                from.getY() + dy * steps / cheb,
                from.getPlane());
    }

    private int digAtHotColdSpot(IPlayer local) {
        WorldPoint me = local.getWorldLocation();
        WorldPoint dig = hotColdSolver.getFinalDigSpot();
        if (dig != null && me != null && me.distanceTo(dig) > DIG_ARRIVAL_DISTANCE) {
            MovementHelper.walkToExact(dig);
            paint.setLastAntiBanAction("Clue: → dig @ " + dig.getX() + "," + dig.getY());
            return travelDelay();
        }
        int digDelay = digWithSpade();
        if (digDelay > 0) {
            deviceSaysDigHere = false;
            hotColdSolver.reset();
            return digDelay;
        }
        return vary(1200, 2000);
    }

    /** Kleine spiral/grid rond zone-centrum als Feel nog niet 'shaking' is. */
    private static WorldPoint offsetHotColdSearch(WorldPoint center, int attempt) {
        if (center == null) {
            return new WorldPoint(3167, 3360, 0);
        }
        int[][] offs = {{0, 1}, {1, 0}, {0, -1}, {-1, 0}, {1, 1}, {-1, 1}, {1, -1}, {-1, -1}};
        int i = (attempt - 3) % offs.length;
        return new WorldPoint(center.getX() + offs[i][0], center.getY() + offs[i][1], center.getPlane());
    }

    private int handleDigStep(IPlayer local, boolean afterDevice) {
        int spadeDelay = ensureInventoryItem(ITEM_SPADE);
        if (spadeDelay > 0) {
            return spadeDelay;
        }
        WorldPoint dest = activeEntry != null ? activeEntry.destination : null;
        if (dest == null) {
            return 2000;
        }
        WorldPoint me = local.getWorldLocation();
        if (me != null && me.distanceTo(dest) > DIG_ARRIVAL_DISTANCE) {
            MovementHelper.walkToExact(dest);
            paint.setLastAntiBanAction("Clue → dig @ " + dest.getX() + "," + dest.getY());
            return travelDelay();
        }
        int digDelay = digWithSpade();
        if (digDelay > 0) {
            if (afterDevice) {
                deviceSaysDigHere = false;
            }
            return digDelay;
        }
        return vary(1200, 2000);
    }

    private int handleCharlieStep(IPlayer local) {
        return handleCharlieCrypticStep(local);
    }

    /** Cryptic talk Charlie — loop naar 3208,3391, talk, item geven indien aanwezig. */
    private int handleCharlieCrypticStep(IPlayer local) {
        Integer charlieDialog = handleCharlieDialog();
        if (charlieDialog != null) {
            return charlieDialog;
        }
        int giveDelay = tryGiveCharlieRequestedItem(local);
        if (giveDelay > 0) {
            return giveDelay;
        }
        WorldPoint me = local.getWorldLocation();
        WorldPoint dest = activeEntry != null && activeEntry.destination != null
                ? activeEntry.destination : CHARLIE_TRAMP_TILE;
        if (me != null && me.distanceTo(dest) > NPC_TALK_DISTANCE) {
            MovementHelper.walkTo(dest);
            paint.setLastAntiBanAction("Clue → Charlie @ " + dest.getX() + "," + dest.getY());
            DebugLog.log("Clue", "Loop naar Charlie " + dest.getX() + "," + dest.getY()
                    + " (nu " + me.getX() + "," + me.getY() + ", dist=" + me.distanceTo(dest) + ")");
            return travelDelay();
        }
        INPC charlie = findCharlieNpc();
        if (charlie == null) {
            paint.setLastAntiBanAction("Clue: Charlie niet gevonden — tile " + CHARLIE_TRAMP_TILE.getX()
                    + "," + CHARLIE_TRAMP_TILE.getY());
            MovementHelper.walkToExact(CHARLIE_TRAMP_TILE);
            DebugLog.log("Clue", "Charlie NPC niet in scene — walk exact tile");
            return travelDelay();
        }
        return interactTalkToNpc(local, charlie);
    }

    private int tryGiveCharlieRequestedItem(IPlayer local) {
        rememberCharlieItemFromDialog();
        String needed = charlieRequestedItem;
        if (needed == null) {
            paint.setLastAntiBanAction("Clue Charlie: praat voor item-opdracht");
            return 0;
        }
        if (!hasInventoryItem(needed)) {
            paint.setLastAntiBanAction("Clue Charlie: mist " + needed);
            DebugLog.log("Clue", "Charlie wil " + needed + " — niet in inv");
            return 0;
        }
        if (Dialog.isOpen() && Dialog.isViewingOptions()) {
            String lowItem = needed.toLowerCase(Locale.ROOT);
            if (Dialog.hasOption(s -> s != null && s.toLowerCase(Locale.ROOT).contains(lowItem))) {
                Dialog.chooseOption(s -> s != null && s.toLowerCase(Locale.ROOT).contains(lowItem));
                DebugLog.log("Clue", "Charlie dialog optie: " + needed);
                return vary(700, 1200);
            }
            String[] givePhrases = {"here you are", "here you go", "i have", "give"};
            for (String phrase : givePhrases) {
                if (Dialog.hasOption(s -> s != null && s.toLowerCase(Locale.ROOT).contains(phrase))) {
                    Dialog.chooseOption(s -> s != null && s.toLowerCase(Locale.ROOT).contains(phrase));
                    return vary(700, 1200);
                }
            }
        }
        INPC charlie = findCharlieNpc();
        if (charlie == null) {
            MovementHelper.walkToExact(CHARLIE_TRAMP_TILE);
            return travelDelay();
        }
        IInventoryItem invItem = Inventory.getFirst(i -> i != null && i.getName() != null
                && i.getName().equalsIgnoreCase(needed));
        if (invItem == null) {
            return 2000;
        }
        WorldPoint me = local.getWorldLocation();
        WorldPoint np = charlie.getWorldLocation();
        if (me != null && np != null && me.distanceTo(np) > NPC_TALK_DISTANCE) {
            MovementHelper.walkTo(np);
            return travelDelay();
        }
        if (invItem.hasAction("Use")) {
            InventoryActionHelper.interact(config, invItem, "Use");
            charlie.interact(0);
            DebugLog.log("Clue", "Use " + needed + " on Charlie");
            return vary(1100, 1900);
        }
        return 0;
    }

    /**
     * Charlie-dialoog: Please sir → What can I do → I really need X → Sure.
     * Zie wiki Transcript:Charlie_the_Tramp.
     */
    private Integer handleCharlieDialog() {
        if (!Dialog.isOpen()) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (now - lastDialogMs < 350) {
            return 200;
        }
        lastDialogMs = now;
        rememberCharlieItemFromDialog();

        if (Dialog.isViewingOptions()) {
            if (Dialog.hasOption(s -> dialogOptionContains(s, "what can i do"))) {
                Dialog.chooseOption(s -> dialogOptionContains(s, "what can i do"));
                DebugLog.log("Clue", "Charlie dialoog: What can I do for you?");
                return vary(700, 1200);
            }
            if (charlieRequestedItem != null
                    && Dialog.hasOption(s -> dialogOptionContains(s, "sure")
                            || dialogOptionContains(s, "glad to help"))) {
                Dialog.chooseOption(s -> dialogOptionContains(s, "sure")
                        || dialogOptionContains(s, "glad to help"));
                DebugLog.log("Clue", "Charlie dialoog: akkoord helpen");
                return vary(700, 1200);
            }
            if (charlieRequestedItem != null && hasInventoryItem(charlieRequestedItem)) {
                String item = charlieRequestedItem;
                String lowItem = item.toLowerCase(Locale.ROOT);
                if (Dialog.hasOption(s -> s != null && s.toLowerCase(Locale.ROOT).contains(lowItem))) {
                    Dialog.chooseOption(s -> s != null && s.toLowerCase(Locale.ROOT).contains(lowItem));
                    DebugLog.log("Clue", "Charlie dialoog: geef " + item);
                    return vary(700, 1200);
                }
            }
            return null;
        }

        String body = readOpenDialogText();
        if (body != null) {
            String low = body.toLowerCase(Locale.ROOT);
            if (low.contains("please") && low.contains("help")) {
                Dialog.continueSpace();
                DebugLog.log("Clue", "Charlie dialoog: continue (help)");
                return vary(500, 900);
            }
            if (charlieRequestedItem != null && low.contains("really need")) {
                Dialog.continueSpace();
                return vary(500, 900);
            }
        }
        Dialog.continueSpace();
        return vary(500, 900);
    }

    private void rememberCharlieItemFromDialog() {
        if (charlieRequestedItem != null) {
            return;
        }
        String body = readOpenDialogText();
        String parsed = BeginnerClueReference.parseCharlieItemRequestFromDialog(body);
        if (parsed != null) {
            charlieRequestedItem = parsed;
            paint.setLastAntiBanAction("Clue Charlie wil: " + parsed);
            DebugLog.log("Clue", "Charlie vraagt: " + parsed);
        }
    }

    private void tryCaptureCharlieItemFromChat(String msg) {
        String parsed = BeginnerClueReference.parseCharlieItemRequestFromDialog(msg);
        if (parsed != null && !parsed.equals(charlieRequestedItem)) {
            charlieRequestedItem = parsed;
            DebugLog.log("Clue", "Charlie item (chat): " + parsed);
        }
    }

    private static boolean dialogOptionContains(String option, String phrase) {
        if (option == null || phrase == null) {
            return false;
        }
        return Text.removeTags(option).toLowerCase(Locale.ROOT).contains(phrase);
    }

    private static String readOpenDialogText() {
        String best = "";
        for (int group : DIALOG_BODY_GROUPS) {
            for (int child = 0; child <= 8; child++) {
                IWidget w = Widgets.get(group, child);
                String t = extractDialogBodyText(w);
                if (t.length() > best.length()) {
                    best = t;
                }
            }
        }
        try {
            java.util.List<IWidget> opts = Dialog.getOptions();
            if (opts != null) {
                for (IWidget w : opts) {
                    String t = widgetText(w);
                    if (t.length() > best.length()) {
                        best = t;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return best.isEmpty() ? null : best;
    }

    private static String extractDialogBodyText(IWidget root) {
        if (root == null) {
            return "";
        }
        String best = "";
        Deque<IWidget> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            IWidget w = q.poll();
            if (w == null || w.isHidden()) {
                continue;
            }
            String t = widgetText(w);
            if (t.length() > best.length() && t.length() >= 8) {
                best = t;
            }
            IWidget[] ch = safeChildren(w);
            if (ch != null) {
                for (IWidget c : ch) {
                    if (c != null) {
                        q.add(c);
                    }
                }
            }
        }
        return best;
    }

    private boolean isCharlieStepActive() {
        if (activeEntry == null) {
            return false;
        }
        if (activeEntry.type == BeginnerClueReference.StepType.CHARLIE_TRAMP) {
            return true;
        }
        return activeEntry.type == BeginnerClueReference.StepType.CRYPTIC_TALK
                && isCharlieNpc(activeEntry.npcName);
    }

    /**
     * 0 = chat ontvangen, 1 = nog wachten, 2 = timeout (opnieuw Feel).
     */
    private int deviceReplyState() {
        if (!hotColdAwaitingReply) {
            return lastHotColdTemp != null ? 0 : 2;
        }
        if (lastHotColdTemp != null) {
            hotColdAwaitingReply = false;
            return 0;
        }
        if (System.currentTimeMillis() - hotColdFeelSentMs > HOT_COLD_REPLY_TIMEOUT_MS) {
            hotColdAwaitingReply = false;
            DebugLog.log("Clue", "Device Feel: geen chat binnen timeout");
            return 2;
        }
        return 1;
    }

    private int clickStrangeDeviceFeel() {
        long now = System.currentTimeMillis();
        if (now - lastDigMs < 1200) {
            return 400;
        }
        IInventoryItem device = Inventory.getFirst(i -> i != null
                && BeginnerClueReference.isStrangeDeviceItem(i.getName(), i.getId()));
        if (device == null) {
            return 0;
        }
        lastHotColdTemp = null;
        hotColdWarmerThanLast = false;
        deviceSaysDigHere = false;
        String action = device.hasAction("Feel") ? "Feel" : (device.hasAction("Operate") ? "Operate" : null);
        if (action != null) {
            InventoryActionHelper.interact(config, device, action);
        } else {
            InventoryActionHelper.interact(config, device, 0);
        }
        lastDigMs = now;
        hotColdAwaitingReply = true;
        hotColdFeelSentMs = now;
        paint.setLastAntiBanAction("Clue: Strange device Feel");
        return vary(900, 1400);
    }

    private int digWithSpade() {
        long now = System.currentTimeMillis();
        if (now - lastDigMs < 1400) {
            return 400;
        }
        IInventoryItem spade = Inventory.getFirst(i -> i != null && i.getName() != null
                && i.getName().equalsIgnoreCase(ITEM_SPADE));
        if (spade == null) {
            return 0;
        }
        String action = spade.hasAction("Dig") ? "Dig" : null;
        if (action != null) {
            InventoryActionHelper.interact(config, spade, action);
        } else {
            InventoryActionHelper.interact(config, spade, 0);
        }
        lastDigMs = now;
        DebugLog.log("Clue", "Dig @ " + (activeEntry != null && activeEntry.destination != null
                ? activeEntry.destination.getX() + "," + activeEntry.destination.getY() : "?"));
        paint.setLastAntiBanAction("Clue: Dig");
        activeEntry = null;
        lastParsedClueText = "";
        deviceSaysDigHere = false;
        return vary(1200, 2200);
    }

    private int ensureInventoryItem(String itemName) {
        if (hasInventoryItem(itemName)) {
            return 0;
        }
        paint.setLastAntiBanAction("Clue: mis " + itemName);
        DebugLog.log("Clue", "Inventory mist: " + itemName);
        return 3500;
    }

    private static boolean hasInventoryItem(String itemName) {
        return hasKitItemAnywhere(itemName);
    }

    /** Inventory of worn equipment — telt mee vóór deposit/withdraw/GE (incl. cape/helm aliassen). */
    private static boolean hasKitItemAnywhere(String requiredKitName) {
        try {
            if (BeginnerClueReference.isStrangeDeviceItem(requiredKitName, -1)) {
                if (Inventory.contains(BeginnerClueReference.ITEM_STRANGE_DEVICE_ID)
                        || Inventory.contains(ITEM_STRANGE_DEVICE)
                        || Equipment.contains(ITEM_STRANGE_DEVICE)) {
                    return true;
                }
                return inventoryOrEquipmentMatchesId(BeginnerClueReference.ITEM_STRANGE_DEVICE_ID);
            }
            if (Inventory.contains(requiredKitName) || Equipment.contains(requiredKitName)) {
                return true;
            }
            if (inventoryOrEquipmentMatches(requiredKitName, Inventory.getAll())) {
                return true;
            }
            var equipped = Equipment.getAll(item -> item != null && item.getName() != null
                    && BeginnerClueReference.kitRequirementMetByItemName(requiredKitName, item.getName()));
            return equipped != null && !equipped.isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean inventoryOrEquipmentMatches(String requiredKitName, Iterable<IInventoryItem> items) {
        if (items == null) {
            return false;
        }
        for (IInventoryItem item : items) {
            if (item == null || item.getName() == null) {
                continue;
            }
            if (BeginnerClueReference.kitRequirementMetByItemName(requiredKitName, item.getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean inventoryOrEquipmentMatchesId(int itemId) {
        try {
            if (Inventory.contains(itemId)) {
                return true;
            }
            var equipped = Equipment.getAll(item -> item != null && item.getId() == itemId);
            return equipped != null && !equipped.isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private int talkToNpcAt(IPlayer local, String npcName, WorldPoint near) {
        if ("Reldo".equalsIgnoreCase(npcName)) {
            return approachAndTalkToReldo(local);
        }
        WorldPoint me = local.getWorldLocation();
        if (me != null && near != null && me.distanceTo(near) > WALK_ARRIVAL_DISTANCE) {
            MovementHelper.walkTo(near);
            return travelDelay();
        }
        return talkToNpc(local, npcName);
    }

    /**
     * Pad naar Reldo: loop naar library-deur (3210,3495), open binnen 2 tiles, zoek Reldo en Talk-to.
     */
    private int approachAndTalkToReldo(IPlayer local) {
        WorldPoint me = local.getWorldLocation();
        if (me == null) {
            return 600;
        }

        INPC reldo = findNpc("Reldo");
        if (reldo != null) {
            WorldPoint np = reldo.getWorldLocation();
            if (np != null && me.distanceTo(np) <= NPC_TALK_DISTANCE) {
                return talkToNpc(local, "Reldo");
            }
        }

        if (me.distanceTo(RELDO_LIBRARY_DOOR) <= RELDO_DOOR_OPEN_RANGE) {
            if (tryOpenReldoLibraryDoor(me)) {
                paint.setLastAntiBanAction("Clue: library-deur openen");
                return vary(900, 1500);
            }
            if (reldo != null && reldo.getWorldLocation() != null) {
                if (me.distanceTo(reldo.getWorldLocation()) > NPC_TALK_DISTANCE) {
                    MovementHelper.walkTo(reldo.getWorldLocation());
                    return travelDelay();
                }
                return talkToNpc(local, "Reldo");
            }
            MovementHelper.walkTo(RELDO_INSIDE_TILE);
            paint.setLastAntiBanAction("Clue: Reldo-kamer in");
            return travelDelay();
        }

        if (me.distanceTo(RELDO_LIBRARY_DOOR) > WALK_ARRIVAL_DISTANCE) {
            MovementHelper.walkTo(RELDO_LIBRARY_DOOR);
            paint.setLastAntiBanAction("Clue: → Reldo-deur");
            DebugLog.log("Clue", "Loop naar Reldo-deur " + RELDO_LIBRARY_DOOR.getX() + ","
                    + RELDO_LIBRARY_DOOR.getY() + " (nu " + me.getX() + "," + me.getY() + ")");
            return travelDelay();
        }

        if (tryOpenReldoLibraryDoor(me)) {
            return vary(900, 1500);
        }
        if (reldo != null && reldo.getWorldLocation() != null) {
            MovementHelper.walkTo(reldo.getWorldLocation());
        } else {
            MovementHelper.walkTo(RELDO_INSIDE_TILE);
        }
        return travelDelay();
    }

    private static ITileObject findReldoLibraryDoor() {
        try {
            return TileObjects.getNearest(obj -> {
                if (obj == null || obj.getName() == null || obj.getWorldLocation() == null) {
                    return false;
                }
                WorldPoint loc = obj.getWorldLocation();
                if (loc.getPlane() != RELDO_LIBRARY_DOOR.getPlane()
                        || loc.distanceTo(RELDO_LIBRARY_DOOR) > 3) {
                    return false;
                }
                String n = obj.getName().toLowerCase(Locale.ROOT);
                if (!n.contains("door")) {
                    return false;
                }
                return obj.hasAction("Open") || obj.hasAction("Close");
            });
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isReldoLibraryDoorOpen() {
        ITileObject door = findReldoLibraryDoor();
        return door != null && door.hasAction("Close");
    }

    private boolean tryOpenReldoLibraryDoor(WorldPoint me) {
        if (isReldoLibraryDoorOpen()) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - lastReldoDoorClickMs < 2000) {
            return false;
        }
        if (me.distanceTo(RELDO_LIBRARY_DOOR) > RELDO_DOOR_OPEN_RANGE) {
            return false;
        }
        ITileObject door = findReldoLibraryDoor();
        if (door == null || !door.hasAction("Open")) {
            return false;
        }
        door.interact("Open");
        lastReldoDoorClickMs = now;
        DebugLog.log("Clue", "Reldo library deur Open @ " + door.getWorldLocation());
        return true;
    }

    private int handleTalkNpc(IPlayer local) {
        if (activeEntry.npcName == null || activeEntry.npcName.isEmpty()) {
            return 2000;
        }
        if (isCharlieNpc(activeEntry.npcName)) {
            int charlie = tryGiveCharlieRequestedItem(local);
            if (charlie > 0) {
                return charlie;
            }
        }
        WorldPoint dest = activeEntry.destination;
        if (dest != null) {
            WorldPoint me = local.getWorldLocation();
            if (me != null && me.distanceTo(dest) > WALK_ARRIVAL_DISTANCE) {
                MovementHelper.walkTo(dest);
                paint.setLastAntiBanAction("Clue → " + activeEntry.npcName);
                return travelDelay();
            }
        }
        return talkToNpc(local, activeEntry.npcName);
    }

    private int handleEmoteStep(IPlayer local) {
        if (activeEntry.equipItems != null) {
            for (String itemName : activeEntry.equipItems) {
                int eqDelay = ensureEquipped(itemName);
                if (eqDelay > 0) {
                    return eqDelay;
                }
            }
        }
        WorldPoint dest = activeEntry.destination;
        if (dest != null) {
            WorldPoint me = local.getWorldLocation();
            if (me != null && me.distanceTo(dest) > WALK_ARRIVAL_DISTANCE) {
                MovementHelper.walkTo(dest);
                paint.setLastAntiBanAction("Clue emote → locatie");
                return travelDelay();
            }
        }
        if (!emotePerformedThisStep && activeEntry.emote != null) {
            if (performEmote(activeEntry.emote)) {
                emotePerformedThisStep = true;
                paint.setLastAntiBanAction("Clue emote: " + activeEntry.emote);
                return vary(600, 1100);
            }
            paint.setLastAntiBanAction("Clue: emote '" + activeEntry.emote + "' niet gevonden");
            return 2000;
        }
        if (!uriTalkedAfterEmote) {
            INPC uri = findNpc("Uri");
            if (uri == null) {
                return vary(800, 1400);
            }
            uriTalkedAfterEmote = true;
            return talkToNpc(local, "Uri");
        }
        activeEntry = null;
        lastParsedClueText = "";
        emotePerformedThisStep = false;
        uriTalkedAfterEmote = false;
        return vary(800, 1400);
    }

    private int ensureEquipped(String itemName) {
        if (itemName == null || itemName.isEmpty()) {
            return 0;
        }
        try {
            if (Equipment.contains(eq -> eq != null && eq.getName() != null
                    && eq.getName().equalsIgnoreCase(itemName))) {
                return 0;
            }
        } catch (Throwable ignored) {
        }
        IInventoryItem inv = Inventory.getFirst(i -> i != null && i.getName() != null
                && i.getName().equalsIgnoreCase(itemName));
        if (inv == null) {
            paint.setLastAntiBanAction("Clue: mis item " + itemName);
            DebugLog.log("Clue", "Emote equip mist: " + itemName);
            return 3000;
        }
        String action = inv.hasAction("Wear") ? "Wear" : (inv.hasAction("Wield") ? "Wield" : "Wear");
        InventoryActionHelper.interact(config, inv, action);
        return vary(500, 900);
    }

    private int talkToNpc(IPlayer local, String npcName) {
        long now = System.currentTimeMillis();
        if (now - lastNpcInteractMs < 900) {
            return vary(350, 650);
        }
        INPC npc = isCharlieNpc(npcName) ? findCharlieNpc() : findNpc(npcName);
        if (npc == null) {
            paint.setLastAntiBanAction("Clue: zoek " + npcName);
            if ("Reldo".equalsIgnoreCase(npcName)) {
                return approachAndTalkToReldo(local);
            }
            if (isCharlieNpc(npcName)) {
                MovementHelper.walkToExact(CHARLIE_TRAMP_TILE);
                DebugLog.log("Clue", "Zoek Charlie @ " + CHARLIE_TRAMP_TILE.getX() + "," + CHARLIE_TRAMP_TILE.getY()
                        + " (npc id " + BeginnerClueReference.NPC_ID_CHARLIE_TRAMP + ")");
                return travelDelay();
            }
            if (activeEntry != null && activeEntry.destination != null) {
                MovementHelper.walkTo(activeEntry.destination);
            }
            return travelDelay();
        }
        return interactTalkToNpc(local, npc, npcName);
    }

    private int interactTalkToNpc(IPlayer local, INPC npc, String logName) {
        long now = System.currentTimeMillis();
        if (now - lastNpcInteractMs < 900) {
            return vary(350, 650);
        }
        if (npc == null) {
            return travelDelay();
        }
        WorldPoint me = local.getWorldLocation();
        WorldPoint np = npc.getWorldLocation();
        if (me != null && np != null && me.distanceTo(np) > NPC_TALK_DISTANCE) {
            MovementHelper.walkTo(np);
            return travelDelay();
        }
        if (npc.hasAction("Talk-to")) {
            npc.interact("Talk-to");
        } else {
            npc.interact(0);
        }
        lastNpcInteractMs = now;
        if (antiBan != null) {
            antiBan.notifyHandlerAction("clue-talk", 1200);
        }
        String label = logName != null ? logName
                : (npc.getName() != null ? npc.getName() : "npc#" + npc.getId());
        DebugLog.log("Clue", "Talk-to " + label + " (id=" + npc.getId() + ")");
        return vary(1100, 2000);
    }

    private int interactTalkToNpc(IPlayer local, INPC npc) {
        return interactTalkToNpc(local, npc, npc != null && npc.getName() != null ? npc.getName() : null);
    }

    private static INPC findNpc(String npcName) {
        if (npcName == null || npcName.isEmpty()) {
            return null;
        }
        String low = npcName.toLowerCase(Locale.ROOT);
        if ("reldo".equals(low)) {
            INPC libraryReldo = NPCs.getNearest(n -> n != null && n.getName() != null
                    && n.getName().equalsIgnoreCase("Reldo")
                    && n.getWorldLocation() != null
                    && n.getWorldLocation().distanceTo(RELDO_LIBRARY_DOOR) <= 22);
            if (libraryReldo != null) {
                return libraryReldo;
            }
        }
        if (low.contains("charlie")) {
            return findCharlieNpc();
        }
        INPC exact = NPCs.getNearest(n -> n != null && n.getName() != null
                && n.getName().equalsIgnoreCase(npcName));
        if (exact != null) {
            return exact;
        }
        return NPCs.getNearest(n -> {
            if (n == null || n.getName() == null) {
                return false;
            }
            String name = n.getName().toLowerCase(Locale.ROOT);
            return name.contains(low) || low.contains(name);
        });
    }

    private static INPC findCharlieNpc() {
        try {
            INPC byId = NPCs.getNearest(n -> n != null
                    && n.getId() == BeginnerClueReference.NPC_ID_CHARLIE_TRAMP);
            if (byId != null) {
                return byId;
            }
            INPC atTile = NPCs.getNearest(n -> n != null
                    && n.getWorldLocation() != null
                    && n.getWorldLocation().distanceTo(CHARLIE_TRAMP_TILE) <= 18
                    && ((n.getId() == BeginnerClueReference.NPC_ID_CHARLIE_TRAMP)
                    || (n.getName() != null && n.getName().toLowerCase(Locale.ROOT).contains("charlie"))));
            if (atTile != null) {
                return atTile;
            }
            INPC exact = NPCs.getNearest(n -> n != null && n.getName() != null
                    && n.getName().equalsIgnoreCase("Charlie the Tramp"));
            if (exact != null) {
                return exact;
            }
            return NPCs.getNearest(n -> n != null && n.getName() != null
                    && n.getName().toLowerCase(Locale.ROOT).contains("charlie"));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Integer handleDialog() {
        if (readClueTextFromWidgets() != null) {
            if (activeEntry == null || awaitingClueWidgetAfterRead) {
                return null;
            }
            closeClueScrollInterface();
            return null;
        }
        if (!Dialog.isOpen()) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (now - lastDialogMs < 350) {
            return 200;
        }
        lastDialogMs = now;

        if (Dialog.isViewingOptions()) {
            if (tryChooseReldoDeviceDialog()) {
                return vary(700, 1200);
            }
            if (activeEntry != null && activeEntry.type == BeginnerClueReference.StepType.CRYPTIC_SPECIAL
                    && Dialog.hasOption(s -> s != null && s.toLowerCase(Locale.ROOT).contains("reldo"))) {
                Dialog.chooseOption(s -> s != null && s.toLowerCase(Locale.ROOT).contains("reldo"));
                return vary(700, 1200);
            }
            if (activeEntry != null && activeEntry.npcName != null) {
                String npc = activeEntry.npcName;
                if (Dialog.hasOption(s -> s != null && s.toLowerCase(Locale.ROOT).contains(npc.toLowerCase(Locale.ROOT)))) {
                    Dialog.chooseOption(s -> s != null && s.toLowerCase(Locale.ROOT).contains(npc.toLowerCase(Locale.ROOT)));
                    return vary(700, 1200);
                }
            }
            if (Dialog.hasOption(s -> s != null && (s.equalsIgnoreCase("Yes") || s.equalsIgnoreCase("Yes.")))) {
                Dialog.chooseOption(s -> s != null && s.equalsIgnoreCase("Yes"));
                return vary(700, 1200);
            }
            Dialog.chooseOption(0);
            return vary(600, 1000);
        }

        Dialog.continueSpace();
        return vary(500, 900);
    }

    private boolean performEmote(String emoteName) {
        if (emoteName == null || emoteName.isEmpty()) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - lastEmoteMs < 800) {
            return false;
        }
        openEmotesTab();
        String low = emoteName.toLowerCase(Locale.ROOT);
        for (int group : EMOTE_WIDGET_GROUPS) {
            for (int child = 0; child <= 60; child++) {
                IWidget root = Widgets.get(group, child);
                if (root == null || root.isHidden()) {
                    continue;
                }
                IWidget hit = findWidgetWithEmoteName(root, low);
                if (hit != null) {
                    hit.interact(0);
                    lastEmoteMs = now;
                    DebugLog.log("Clue", "Emote click: " + emoteName + " (group " + group + ")");
                    return true;
                }
            }
        }
        return false;
    }

    private static IWidget findWidgetWithEmoteName(IWidget root, String emoteLow) {
        Deque<IWidget> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            IWidget w = q.poll();
            if (w == null || w.isHidden()) {
                continue;
            }
            String combined = widgetText(w).toLowerCase(Locale.ROOT);
            if (combined.equals(emoteLow) || combined.contains(emoteLow)) {
                if (combined.length() <= emoteLow.length() + 6) {
                    return w;
                }
            }
            IWidget[] ch = safeChildren(w);
            if (ch != null) {
                for (IWidget c : ch) {
                    if (c != null) {
                        q.add(c);
                    }
                }
            }
        }
        return null;
    }

    private static void openEmotesTab() {
        try {
            for (Tab tab : Tab.values()) {
                if (tab.name().toUpperCase(Locale.ROOT).contains("EMOTE")) {
                    Tabs.open(tab);
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            Tabs.open(Tab.MAGIC);
        } catch (Throwable ignored2) {
        }
    }

    public static String readClueTextFromWidgets() {
        int[] primaryAndFallbacks = new int[1 + CLUE_TEXT_GROUP_FALLBACKS.length];
        primaryAndFallbacks[0] = CLUE_TEXT_GROUP_PRIMARY;
        System.arraycopy(CLUE_TEXT_GROUP_FALLBACKS, 0, primaryAndFallbacks, 1, CLUE_TEXT_GROUP_FALLBACKS.length);
        String best = scanClueWidgetGroups(primaryAndFallbacks);
        if (best.length() < 12) {
            String extra = scanClueWidgetGroups(CLUE_TEXT_EXTRA_GROUPS);
            if (extra.length() > best.length()) {
                best = extra;
            }
        }
        if (best.length() < 12) {
            String broad = scanClueWidgetGroupsRange(196, 220);
            if (broad.length() > best.length()) {
                best = broad;
            }
        }
        return best.length() >= 12 ? best : null;
    }

    private static String scanClueWidgetGroups(int... groups) {
        String best = "";
        for (int group : groups) {
            for (int child = 0; child <= 20; child++) {
                IWidget w = Widgets.get(group, child);
                String t = extractLongClueText(w);
                if (t.length() > best.length()) {
                    best = t;
                }
            }
        }
        return best;
    }

    private static String scanClueWidgetGroupsRange(int groupMin, int groupMax) {
        String best = "";
        for (int group = groupMin; group <= groupMax; group++) {
            for (int child = 0; child <= 6; child++) {
                IWidget w = Widgets.get(group, child);
                String t = extractLongClueText(w);
                if (t.length() > best.length()) {
                    best = t;
                }
            }
        }
        return best;
    }

    private static String extractLongClueText(IWidget root) {
        if (root == null) {
            return "";
        }
        String best = "";
        Deque<IWidget> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            IWidget w = q.poll();
            if (w == null || w.isHidden()) {
                continue;
            }
            String t = widgetText(w);
            if (t.length() >= 12 && t.length() > best.length()
                    && (looksLikeClueBody(t) || BeginnerClueReference.matchByClueText(t) != null)) {
                best = t;
            }
            IWidget[] ch = safeChildren(w);
            if (ch != null) {
                for (IWidget c : ch) {
                    if (c != null) {
                        q.add(c);
                    }
                }
            }
        }
        return best;
    }

    private static boolean looksLikeClueBody(String t) {
        String low = t.toLowerCase(Locale.ROOT);
        return low.contains("talk") || low.contains("emote") || low.contains("dig")
                || low.contains("anagram") || low.contains("challenge")
                || low.contains("speak") || low.contains("cheer") || low.contains("bow")
                || low.contains("clap") || low.contains("spin") || low.contains("panic")
                || low.contains("raspberry") || low.contains("walks") || low.contains("walking")
                || low.contains("near") || low.contains("buried") || low.contains("charlie")
                || low.contains("tramp") || low.contains("device") || low.contains("map")
                || low.contains("search") || low.contains("castle") || low.contains("duke")
                || low.contains("barbarian") || low.contains("desert") || low.contains("varrock")
                || low.contains("lumbridge") || low.contains("falador") || low.contains("draynor");
    }

    /** Reldo: strange device / search / treasure-trail opties. */
    private boolean tryChooseReldoDeviceDialog() {
        String[] preferred = {
                "search for treasure",
                "search the bookshelves",
                "search the books",
                "search bookshelves",
                "search books",
                "a treasure",
                "treasure trails",
                "treasure trail",
                "strange device",
                "search",
                "device",
                "buried",
                "treasure",
                "yes"
        };
        for (String opt : preferred) {
            final String key = opt.toLowerCase(Locale.ROOT);
            if (Dialog.hasOption(s -> s != null && s.toLowerCase(Locale.ROOT).contains(key))) {
                Dialog.chooseOption(s -> s != null && s.toLowerCase(Locale.ROOT).contains(key));
                DebugLog.log("Clue", "Reldo dialog: " + opt);
                return true;
            }
        }
        return false;
    }

    private static boolean isCharlieNpc(String npcName) {
        if (npcName == null) {
            return false;
        }
        String low = npcName.toLowerCase(Locale.ROOT);
        return low.contains("charlie");
    }

    private static String widgetText(IWidget w) {
        try {
            String t = w.getText();
            if (t != null) {
                return Text.removeTags(t).replace('\n', ' ').trim();
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static IWidget[] safeChildren(IWidget w) {
        try {
            return w.getChildren();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static IInventoryItem findBeginnerScrollItem() {
        try {
            return Inventory.getFirst(i -> i != null && i.getId() == BeginnerClueReference.ITEM_CLUE_SCROLL);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private int travelDelay() {
        return antiBan != null ? antiBan.varyDelay(randomDelay(600, 1100)) : randomDelay(600, 1100);
    }

    private int vary(int min, int max) {
        return antiBan != null ? antiBan.varyDelay(randomDelay(min, max)) : randomDelay(min, max);
    }

    private static int randomDelay(int min, int max) {
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }
}
