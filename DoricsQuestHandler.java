package com.combatbot;

import net.runelite.api.Quest;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.util.Text;
import net.storm.api.domain.actors.INPC;
import net.storm.api.domain.actors.IPlayer;
import net.storm.api.domain.items.IInventoryItem;
import net.storm.api.domain.tiles.ITileObject;
import net.storm.api.domain.widgets.IWidget;
import net.storm.api.plugins.config.ConfigManager;
import net.storm.sdk.entities.NPCs;
import net.storm.sdk.entities.Players;
import net.storm.sdk.entities.TileObjects;
import net.storm.sdk.game.Skills;
import net.storm.sdk.game.Vars;
import net.storm.sdk.items.Bank;
import net.storm.sdk.items.GrandExchange;
import net.storm.sdk.items.Inventory;
import net.storm.sdk.widgets.Dialog;

import java.util.Locale;
import java.util.Random;

/**
 * Doric's Quest (VarPlayer 31) vóór mining training onder level 10.
 * <p>Materialen (unnoted): 6× Clay, 4× Copper ore, 2× Iron ore — in één keer afleveren.</p>
 * <p>Eerst materialen via bank/GE in inventaris, daarna <b>één trip</b> naar Doric (start + afleveren).</p>
 */
public final class DoricsQuestHandler {

    /** {@link net.runelite.api.gameval.VarPlayerID#DORICS_QUEST} */
    private static final int VARP_DORICS_QUEST = 31;
    private static final int VARP_QUEST_COMPLETE = 100;

    private static final int MINING_LEVEL_SKIP_QUEST = 10;

    /** Doric NPC. */
    private static final WorldPoint DORIC_TILE = new WorldPoint(2951, 3451, 0);
    /** Binnen-hut anker (±1 tegel = binnen). */
    private static final WorldPoint DORIC_HOUSE_ANCHOR = new WorldPoint(2952, 3451, 0);
    private static final int DORIC_INSIDE_TILE_RADIUS = 1;
    /** Hutdeur-object — alleen deze tegel (Open/Close op 2950, 3450). */
    private static final WorldPoint DORIC_DOOR_TILE = new WorldPoint(2950, 3450, 0);
    /** Buiten vóór de deur (zuid van 2950,3450). */
    private static final WorldPoint DORIC_DOOR_APPROACH_TILE = new WorldPoint(2950, 3449, 0);
    private static final WorldPoint GE_TILE = new WorldPoint(3164, 3486, 0);

    private static final String CLAY = "Clay";
    private static final String COPPER = "Copper ore";
    private static final String IRON = "Iron ore";

    private static final int NEED_CLAY = 6;
    private static final int NEED_COPPER = 4;
    private static final int NEED_IRON = 2;

    /** Unnoted item-ids (noted = id+1) — quest accepteert geen noted stacks. */
    private static final int ID_CLAY = 434;
    private static final int ID_COPPER_ORE = 436;
    private static final int ID_IRON_ORE = 440;

    private static final String[] GE_BUY_ORDER = { CLAY, COPPER, IRON };

    private static long lastSkipLogMs;

    private enum Phase {
        GATHER,
        WALK_BANK,
        BANKING,
        GE_BUY,
        /** Loot/erts verkopen op GE voor gp (automatisch, geen handmatige gp). */
        GE_SELL_COINS,
        WALK_DORIC,
        TALK_DORIC
    }

    private final CombatBotConfig config;
    private final AntiBan antiBan;
    private final CombatBotPaint paint;
    private final Random random = new Random();
    private final UniversalBankingManager bankingManager = new UniversalBankingManager();

    private Phase phase = Phase.GATHER;
    private String pendingGeItem;
    private int pendingGeQty;
    private long lastWalkMs;
    private long lastNpcMs;
    private long lastDialogMs;
    private long lastBankMs;
    private long lastObjMs;
    private boolean inBankSession;
    /** Laatste bank-open: unnoted voorraad (geen herhaald banken tijdens GE-shop). */
    private int bankSnapClay = -1;
    private int bankSnapCopper = -1;
    private int bankSnapIron = -1;
    private boolean bankMaterialSnapValid;
    /** Meerdere GE-aankopen achter elkaar zonder tussendoor naar de bank. */
    private boolean geShoppingSession;
    private int dialogFallbackHits;
    private String lastDialogSig = "";
    /** Chat "I can't reach that!" → deur dicht, eerst Open. */
    private boolean doricDoorBlocksTalk;
    /** GE faalde door 0 gp op zak — eerst coins uit bank, anders loot verkopen op GE. */
    private boolean needCoinsForGe;
    private int geFailStreak;
    private String geSellForCoinsItem;

    private static final int MAX_GE_FAIL_STREAK = 3;

    /** Verkoop-volgorde voor gp (geen quest-stacks die we nog nodig hebben). */
    private static final String[] COIN_SELL_PRIORITY = {
            "Tin ore", "Logs", "Oak logs", "Willow logs", "Maple logs",
            "Copper ore", "Clay", "Iron ore"
    };

    public DoricsQuestHandler(CombatBotConfig config, AntiBan antiBan, CombatBotPaint paint, ConfigManager cm) {
        this.config = config;
        this.antiBan = antiBan;
        this.paint = paint;
    }

    /**
     * Doric vóór mining training (&lt;10). Geen {@code miningEnabled()}-check: rotatie kan mining al
     * actief hebben via centers / account-profiel ({@code rotationPickMining}) zonder globale mining-vink.
     */
    public static boolean shouldRunQuest(CombatBotConfig config) {
        if (config == null || !config.miningDoricsQuestAuto()) {
            return false;
        }
        if (Skills.getLevel(Skill.MINING) >= MINING_LEVEL_SKIP_QUEST) {
            return false;
        }
        int varp = readDoricVarp();
        if (varp >= VARP_QUEST_COMPLETE) {
            return false;
        }
        // VarPlayer 31 is leidend; IQuests alleen als extra bij varp 0 (voltooid volgens client).
        if (varp == 0 && StormQuestHelper.isQuestFinished(Quest.DORICS_QUEST)) {
            return false;
        }
        return true;
    }

    /** Debug: waarom Doric niet start (max 1× per 20s). */
    public static void logSkipReasonIfRelevant(CombatBotConfig config) {
        long now = System.currentTimeMillis();
        if (now - lastSkipLogMs < 20_000L) {
            return;
        }
        if (config == null || config.miningDoricsQuestAuto()) {
            if (config != null && Skills.getLevel(Skill.MINING) < MINING_LEVEL_SKIP_QUEST
                    && readDoricVarp() < VARP_QUEST_COMPLETE) {
                return;
            }
        }
        lastSkipLogMs = now;
        if (config == null) {
            DebugLog.log("DoricQuest", "skip: geen config");
            return;
        }
        if (!config.miningDoricsQuestAuto()) {
            DebugLog.log("DoricQuest", "skip: Doric's Quest automatisch = UIT");
            return;
        }
        if (Skills.getLevel(Skill.MINING) >= MINING_LEVEL_SKIP_QUEST) {
            DebugLog.log("DoricQuest", "skip: mining level " + Skills.getLevel(Skill.MINING) + " >= " + MINING_LEVEL_SKIP_QUEST);
            return;
        }
        int varp = readDoricVarp();
        if (varp >= VARP_QUEST_COMPLETE) {
            DebugLog.log("DoricQuest", "skip: varp=" + varp + " (quest klaar)");
            return;
        }
        if (varp == 0 && StormQuestHelper.isQuestFinished(Quest.DORICS_QUEST)) {
            DebugLog.log("DoricQuest", "skip: IQuests=DORICS voltooid (varp=0)");
        }
    }

    public void resetState() {
        phase = Phase.GATHER;
        pendingGeItem = null;
        pendingGeQty = 0;
        inBankSession = false;
        clearBankMaterialSnapshot();
        geShoppingSession = false;
        doricDoorBlocksTalk = false;
        needCoinsForGe = false;
        geFailStreak = 0;
        geSellForCoinsItem = null;
    }

    /** {@link CombatBotPlugin#onChatMessage} — deur dicht vóór Doric. */
    public void onChatMessage(String message) {
        if (message == null) {
            return;
        }
        String low = Text.removeTags(message).trim().toLowerCase(Locale.ROOT);
        if (low.contains("can't reach that") || low.contains("cant reach that")
                || low.contains("i can't reach that") || low.contains("i cant reach that")) {
            doricDoorBlocksTalk = true;
            phase = Phase.WALK_DORIC;
            lastNpcMs = 0L;
            DebugLog.log("DoricQuest", "Chat: can't reach → deur openen");
        }
    }

    public int loop() {
        IPlayer local = Players.getLocal();
        if (local == null || local.getWorldLocation() == null) {
            return 1000;
        }

        Integer dialogDelay = handleDialog();
        if (dialogDelay != null) {
            return dialogDelay;
        }

        int varp = readDoricVarp();
        if (varp >= VARP_QUEST_COMPLETE || StormQuestHelper.isQuestFinished(Quest.DORICS_QUEST)) {
            paint.setCurrentStatus("Doric's Quest voltooid");
            return 600;
        }

        if (Bank.isOpen()) {
            if (phase == Phase.WALK_BANK || phase == Phase.BANKING || hasNotedQuestMaterialInInventory()) {
                inBankSession = true;
                phase = Phase.BANKING;
                return phaseBanking(local);
            }
            if (!inBankSession && phase != Phase.GE_BUY && phase != Phase.GE_SELL_COINS) {
                Bank.close();
                return antiBan.varyDelay(randomDelay(400, 800));
            }
        }

        if (hasNotedQuestMaterialInInventory() && !isActiveGeShoppingSession()) {
            paint.setCurrentStatus("Doric's Quest · noted → bank (unnoted)");
            phase = Phase.WALK_BANK;
            geShoppingSession = false;
            return resumeGatheringUntilReady(local);
        }

        // Strikt: alleen naar Doric met 6 clay + 4 copper + 2 iron unnoted in inventaris (niet "genoeg in bank").
        if (!hasAllMaterialsInInventory()) {
            paint.setCurrentStatus("Doric's Quest (varp=" + varp + ") · " + materialProgressLabel());
            return resumeGatheringUntilReady(local);
        }

        paint.setCurrentStatus("Doric's Quest (varp=" + varp + ") · → Doric · " + materialProgressLabel());
        phase = Phase.WALK_DORIC;
        return walkOrTalkDoric(local, varp);
    }

    /** Bank / GE tot inventaris exact compleet is; nooit Doric met gedeeltelijke stack. */
    private int resumeGatheringUntilReady(IPlayer local) {
        if (phase == Phase.WALK_DORIC || phase == Phase.TALK_DORIC) {
            phase = Phase.GATHER;
        }
        paint.setLastAntiBanAction("Doric: materialen: " + materialProgressLabel());
        switch (phase) {
            case WALK_BANK:
                return phaseWalkBank(local);
            case BANKING:
                return phaseBanking(local);
            case GE_BUY:
                return phaseGeBuy(local);
            case GE_SELL_COINS:
                return phaseGeSellForCoins(local);
            case GATHER:
            default:
                return phaseGather(local);
        }
    }

    private int phaseGather(IPlayer local) {
        loadPersistedBankMaterialSnapshot();

        if (geShoppingSession) {
            phase = Phase.GE_BUY;
            pendingGeItem = firstItemNeedingGePurchase();
            if (pendingGeItem != null) {
                return phaseGeBuy(local);
            }
            geShoppingSession = false;
        }

        if (totalAvailable(CLAY) >= NEED_CLAY
                && totalAvailable(COPPER) >= NEED_COPPER
                && totalAvailable(IRON) >= NEED_IRON) {
            if (bankVisitWorthwhile()) {
                phase = Phase.WALK_BANK;
                return phaseWalkBank(local);
            }
            if (hasAllMaterialsInInventory()) {
                phase = Phase.WALK_DORIC;
                return antiBan.varyDelay(randomDelay(400, 800));
            }
        }

        String geItem = firstItemNeedingGePurchase();
        if (geItem != null && !bankVisitWorthwhile()) {
            if (!canAffordGePurchase(geItem)) {
                String rsn = BankSnapshotPlanner.currentDisplayName();
                if (BankSnapshotPlanner.snapshotConfirmsAbsent(rsn, "Coins")) {
                    paint.setCurrentStatus("Doric's Quest · geen gp (bank snapshot leeg) — gp toevoegen");
                    paint.setLastAntiBanAction("Doric: wacht op gp (geen GE-loop)");
                    DebugLog.log("DoricQuest", "Hard stop: snapshot geen coins + inv="
                            + getCoinCount() + " — geen GE");
                    geShoppingSession = false;
                    return antiBan.varyDelay(randomDelay(8000, 12000));
                }
                needCoinsForGe = true;
                phase = Phase.WALK_BANK;
                paint.setCurrentStatus("Doric's Quest · geen gp voor GE → bank");
                paint.setLastAntiBanAction("Doric: eerst coins (GE " + geItem + ")");
                DebugLog.log("DoricQuest", "GE uitgesteld (0 gp): " + materialProgressLabel()
                        + " | inv coins=" + getCoinCount());
                return phaseWalkBank(local);
            }
            return startGeShopping(local, geItem, "snapshot: bank leeg voor resterende materialen");
        }

        if (needCoinsForGe) {
            phase = Phase.WALK_BANK;
            return phaseWalkBank(local);
        }

        if (bankVisitWorthwhile()) {
            if (Bank.isOpen()) {
                inBankSession = true;
                phase = Phase.BANKING;
                return phaseBanking(local);
            }
            if (BankHelper.isNearAnyBank(local.getWorldLocation())) {
                if (BankHelper.interactIfNearby()) {
                    inBankSession = true;
                    phase = Phase.BANKING;
                    return antiBan.varyDelay(randomDelay(800, 1400));
                }
            }
            phase = Phase.WALK_BANK;
            return phaseWalkBank(local);
        }

        if (geItem != null) {
            return startGeShopping(local, geItem, "geen bruikbare bank-snapshot / alles via GE");
        }

        phase = Phase.WALK_BANK;
        return phaseWalkBank(local);
    }

    /** Bank alleen als snapshot/live bank quest-materiaal heeft of noted stacks gefixt moeten worden. */
    private boolean bankVisitWorthwhile() {
        if (needCoinsForGe) {
            return true;
        }
        if (hasNotedQuestMaterialInInventory()) {
            return true;
        }
        String rsn = BankSnapshotPlanner.currentDisplayName();
        String geItem = firstItemNeedingGePurchase();
        if (geItem != null && !canAffordGePurchase(geItem)
                && BankSnapshotPlanner.shouldWalkToBankForWithdraw(rsn, "Coins")) {
            return true;
        }
        if (countExactUnnotedInv(CLAY) < NEED_CLAY
                && BankSnapshotPlanner.shouldWalkToBankForWithdraw(rsn, CLAY)) {
            return true;
        }
        if (countExactUnnotedInv(COPPER) < NEED_COPPER
                && BankSnapshotPlanner.shouldWalkToBankForWithdraw(rsn, COPPER)) {
            return true;
        }
        if (countExactUnnotedInv(IRON) < NEED_IRON
                && BankSnapshotPlanner.shouldWalkToBankForWithdraw(rsn, IRON)) {
            return true;
        }
        return false;
    }

    private int startGeShopping(IPlayer local, String item, String reason) {
        if (!canAffordGePurchase(item)) {
            return startCoinRecovery(local, "GE niet gestart (geen gp)");
        }
        geFailStreak = 0;
        geShoppingSession = true;
        pendingGeItem = item;
        pendingGeQty = geBuyQuantityFor(item);
        phase = Phase.GE_BUY;
        paint.setLastAntiBanAction("Doric: GE → " + pendingGeQty + "× " + item);
        DebugLog.log("DoricQuest", "GE direct (" + reason + "): " + materialProgressLabel()
                + " | bank clay=" + bankCountFor(CLAY) + " copper=" + bankCountFor(COPPER)
                + " iron=" + bankCountFor(IRON)
                + " | persisted=" + BankSnapshotPlanner.hasPersistedSnapshot(BankSnapshotPlanner.currentDisplayName()));
        return phaseGeBuy(local);
    }

    private int phaseWalkBank(IPlayer local) {
        WorldPoint me = local.getWorldLocation();
        if (me != null && BankHelper.isNearGrandExchange(me)) {
            BankHelper.closeGrandExchangeIfOpen();
            if (BankHelper.tryOpenBankAtGrandExchange()) {
                inBankSession = true;
                phase = Phase.BANKING;
                paint.setLastAntiBanAction("Doric: GE-bank open");
                return phaseBanking(local);
            }
            paint.setLastAntiBanAction("Doric: → GE bankbooth");
            return travelDelay();
        }
        if (me != null && BankHelper.isNearAnyBank(me)) {
            BankHelper.closeGrandExchangeIfOpen();
            if (BankHelper.interactIfNearby(8000)) {
                inBankSession = true;
                phase = Phase.BANKING;
                return antiBan.varyDelay(randomDelay(800, 1400));
            }
        }
        BankHelper.walkToNearestFullBank();
        paint.setLastAntiBanAction("Doric: → bank (materialen)");
        return travelDelay();
    }

    private int phaseBanking(IPlayer local) {
        if (!Bank.isOpen()) {
            inBankSession = false;
            phase = Phase.WALK_BANK;
            return phaseWalkBank(local);
        }
        inBankSession = true;
        long now = System.currentTimeMillis();
        if (now - lastBankMs < 400) {
            return 200;
        }
        lastBankMs = now;

        if (ensureBankWithdrawUnnoted()) {
            return antiBan.varyDelay(randomDelay(400, 700));
        }

        int coinPull = tryWithdrawCoinsForGe();
        if (coinPull > 0) {
            return coinPull;
        }

        refreshBankMaterialSnapshot();

        depositNonQuestItems();
        if (hasNotedQuestMaterialInInventory()) {
            depositAllQuestMaterials();
            ensureBankWithdrawUnnoted();
        }

        withdrawUpTo(CLAY, NEED_CLAY);
        withdrawUpTo(COPPER, NEED_COPPER);
        withdrawUpTo(IRON, NEED_IRON);
        refreshBankMaterialSnapshot();

        if (hasAllMaterialsInInventory()) {
            Bank.close();
            bankingManager.waitForBankClose();
            inBankSession = false;
            clearBankMaterialSnapshot();
            geShoppingSession = false;
            phase = Phase.WALK_DORIC;
            return antiBan.varyDelay(randomDelay(500, 900));
        }

        String missingGe = firstItemNeedingGePurchase();
        if (missingGe != null) {
            Bank.close();
            bankingManager.waitForBankClose();
            inBankSession = false;
            geShoppingSession = true;
            pendingGeItem = missingGe;
            pendingGeQty = geBuyQuantityFor(missingGe);
            phase = Phase.GE_BUY;
            paint.setLastAntiBanAction("Doric: GE-shop → " + pendingGeQty + "× " + missingGe);
            DebugLog.log("DoricQuest", "GE-shop start: " + materialProgressLabel()
                    + " | bank snap clay=" + bankSnapClay + " copper=" + bankSnapCopper + " iron=" + bankSnapIron);
            return antiBan.varyDelay(randomDelay(600, 1000));
        }

        // Genoeg in bank+inv, nog niet alles unnoted in inventaris — nog één withdraw-poging.
        withdrawUpTo(CLAY, NEED_CLAY);
        withdrawUpTo(COPPER, NEED_COPPER);
        withdrawUpTo(IRON, NEED_IRON);
        if (hasAllMaterialsInInventory()) {
            Bank.close();
            bankingManager.waitForBankClose();
            inBankSession = false;
            clearBankMaterialSnapshot();
            geShoppingSession = false;
            phase = Phase.WALK_DORIC;
            return antiBan.varyDelay(randomDelay(500, 900));
        }

        Bank.close();
        inBankSession = false;
        phase = Phase.GATHER;
        paint.setLastAntiBanAction("Doric: inv vol? " + materialProgressLabel());
        return antiBan.varyDelay(randomDelay(800, 1200));
    }

    private int phaseGeBuy(IPlayer local) {
        if (hasAllMaterialsInInventory()) {
            geShoppingSession = false;
            pendingGeItem = null;
            clearBankMaterialSnapshot();
            phase = Phase.WALK_DORIC;
            return antiBan.varyDelay(randomDelay(600, 1000));
        }

        if (pendingGeItem == null || pendingGeItem.isEmpty()) {
            pendingGeItem = firstItemNeedingGePurchase();
        }
        if (pendingGeItem == null) {
            geShoppingSession = false;
            phase = Phase.WALK_BANK;
            paint.setLastAntiBanAction("Doric: GE klaar → bank (ophalen)");
            return antiBan.varyDelay(randomDelay(600, 1000));
        }

        WorldPoint me = local.getWorldLocation();
        if (me != null && me.distanceTo(GE_TILE) > 12) {
            issueWalk(GE_TILE);
            return travelDelay();
        }

        int have = countExactUnnotedInv(pendingGeItem);
        int buyQty = geBuyQuantityFor(pendingGeItem);
        int startPrice = geStartPrice(pendingGeItem);

        GeRestockHelper.RestockResult res = GeRestockHelper.buyWithEscalation(
                pendingGeItem, have + buyQty, startPrice);

        if (res == GeRestockHelper.RestockResult.SUCCESS) {
            geFailStreak = 0;
            needCoinsForGe = false;
            if (hasNotedQuestMaterialInInventory()) {
                geShoppingSession = false;
                phase = Phase.WALK_BANK;
                paint.setLastAntiBanAction("Doric: GE ok maar noted → bank");
                DebugLog.log("DoricQuest", "GE SUCCESS met noted stacks → bank voor unnoted");
                return antiBan.varyDelay(randomDelay(700, 1100));
            }
            String bought = pendingGeItem;
            pendingGeItem = firstItemNeedingGePurchase();
            if (pendingGeItem != null) {
                pendingGeQty = geBuyQuantityFor(pendingGeItem);
                paint.setLastAntiBanAction("Doric: GE " + bought + " ok → " + pendingGeQty + "× " + pendingGeItem);
                DebugLog.log("DoricQuest", "GE volgende: " + pendingGeItem + " (" + materialProgressLabel() + ")");
                return antiBan.varyDelay(randomDelay(500, 900));
            }
            geShoppingSession = false;
            if (hasAllMaterialsInInventory()) {
                clearBankMaterialSnapshot();
                phase = Phase.WALK_DORIC;
                paint.setLastAntiBanAction("Doric: materialen compleet → Doric");
            } else {
                phase = Phase.WALK_BANK;
                paint.setLastAntiBanAction("Doric: GE-shop klaar → bank (ophalen)");
            }
            return antiBan.varyDelay(randomDelay(800, 1400));
        }
        if (res == GeRestockHelper.RestockResult.BLOCKED_BY_ACCOUNT_POLICY) {
            paint.setLastAntiBanAction("Doric: GE geblokkeerd voor " + pendingGeItem);
            geShoppingSession = false;
            pendingGeItem = null;
            phase = Phase.GATHER;
            return 3000;
        }

        geFailStreak++;
        int invCoins = getCoinCount();
        int needGp = estimatedGeCostFor(pendingGeItem);
        if (invCoins < needGp) {
            geShoppingSession = false;
            DebugLog.log("DoricQuest", "GE FAILED (coins): have=" + invCoins + " need~=" + needGp
                    + " voor " + pendingGeQty + "× " + pendingGeItem);
            return startCoinRecovery(local, "GE te weinig gp");
        }

        if (geFailStreak >= MAX_GE_FAIL_STREAK) {
            geFailStreak = 0;
            geShoppingSession = false;
            clearBankMaterialSnapshot();
            phase = Phase.WALK_BANK;
            paint.setCurrentStatus("Doric's Quest · GE mislukt — bank openen / gp toevoegen");
            paint.setLastAntiBanAction("Doric: GE-stop → bank (geen GE-loop)");
            DebugLog.log("DoricQuest", "GE te vaak mislukt → bank i.p.v. GE-loop");
            return antiBan.varyDelay(randomDelay(2000, 3500));
        }

        paint.setLastAntiBanAction("Doric: GE mislukt (" + res + ") #" + geFailStreak);
        return antiBan.varyDelay(randomDelay(1500, 2500));
    }

    private int walkOrTalkDoric(IPlayer local, int varp) {
        if (Dialog.isOpen()) {
            return antiBan.varyDelay(randomDelay(300, 600));
        }
        if (!hasAllMaterialsInInventory()) {
            return resumeGatheringUntilReady(local);
        }
        WorldPoint me = local.getWorldLocation();
        if (me == null) {
            return 1000;
        }
        if (doricDoorBlocksTalk) {
            if (isDoricDoorOpen() && insideDoricHouse(me)) {
                doricDoorBlocksTalk = false;
            } else {
                phase = Phase.WALK_DORIC;
                return approachDoricHouseFromOutside(local, me);
            }
        }
        if (!canTalkToDoric(me)) {
            phase = Phase.WALK_DORIC;
            return approachDoricHouseFromOutside(local, me);
        }
        if (insideDoricHouse(me) && me.distanceTo(DORIC_TILE) > 4) {
            phase = Phase.WALK_DORIC;
            issueWalk(DORIC_TILE);
            paint.setLastAntiBanAction("Doric: → Doric (binnen)");
            return travelDelay();
        }
        phase = Phase.TALK_DORIC;
        return talkToDoric(local);
    }

    /** Buiten: alleen Open als deur dicht is; bij open deur direct binnenlopen. */
    private int approachDoricHouseFromOutside(IPlayer local, WorldPoint me) {
        if (isDoricDoorOpen()) {
            issueWalkToDoricHouseInside();
            paint.setLastAntiBanAction("Doric: deur al open → binnen");
            return travelDelay();
        }
        if (nearDoricHouseForDoor(local)) {
            if (tryOpenDoricDoor(local)) {
                paint.setLastAntiBanAction("Doric: deur openen");
                return antiBan.varyDelay(randomDelay(1400, 2400));
            }
            if (me.distanceTo(DORIC_DOOR_APPROACH_TILE) > 3) {
                issueWalk(DORIC_DOOR_APPROACH_TILE);
                paint.setLastAntiBanAction("Doric: → deur");
                return travelDelay();
            }
            if (tryOpenDoricDoor(local)) {
                paint.setLastAntiBanAction("Doric: deur openen");
                return antiBan.varyDelay(randomDelay(1400, 2400));
            }
        }
        issueWalkToDoricHouseInside();
        paint.setLastAntiBanAction("Doric: → hut binnen");
        return travelDelay();
    }

    private int talkToDoric(IPlayer local) {
        WorldPoint me = local.getWorldLocation();
        if (me == null || !canTalkToDoric(me)) {
            phase = Phase.WALK_DORIC;
            DebugLog.log("DoricQuest", "Talk-to geblokkeerd: deur dicht / te ver (" + me + ")");
            return approachDoricHouseFromOutside(local, me != null ? me : DORIC_DOOR_APPROACH_TILE);
        }
        if (System.currentTimeMillis() - lastNpcMs < 900) {
            return antiBan.varyDelay(randomDelay(400, 800));
        }
        INPC doric = findDoricNpc();
        if (doric == null) {
            paint.setLastAntiBanAction("Doric: zoek NPC (binnen)…");
            issueWalk(DORIC_TILE);
            return travelDelay();
        }
        if (me.distanceTo(doric.getWorldLocation()) > 4) {
            issueWalk(DORIC_TILE);
            return travelDelay();
        }
        if (doric.hasAction("Talk-to")) {
            doric.interact("Talk-to");
        } else {
            doric.interact(0);
        }
        lastNpcMs = System.currentTimeMillis();
        DebugLog.log("DoricQuest", "Talk-to Doric @ " + me);
        return antiBan.varyDelay(randomDelay(1200, 2200));
    }

    private static INPC findDoricNpc() {
        return NPCs.getNearest(n -> {
            if (n == null || n.getName() == null) {
                return false;
            }
            if (!n.getName().equalsIgnoreCase("Doric")
                    && !n.getName().toLowerCase(Locale.ROOT).contains("doric")) {
                return false;
            }
            WorldPoint np = n.getWorldLocation();
            return np != null && (insideDoricHouse(np) || isDoricDoorOpen());
        });
    }

    private Integer handleDialog() {
        if (!Dialog.isOpen()) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (now - lastDialogMs < 350) {
            return 200;
        }
        lastDialogMs = now;

        Integer questYes = tryAcceptDoricYesDialog();
        if (questYes != null) {
            return questYes;
        }

        if (Dialog.isViewingOptions()) {
            if (Dialog.hasOption(DoricsQuestHandler::isNumberedYesOption)) {
                Dialog.chooseOption(DoricsQuestHandler::isNumberedYesOption);
                dialogFallbackHits = 0;
                lastDialogSig = "";
                DebugLog.log("DoricQuest", "Dialog: (1) Yes / genummerde ja");
                return antiBan.varyDelay(randomDelay(700, 1300));
            }
            String[] exactPreferred = {
                    "(1) Yes.",
                    "(1) Yes",
                    "Yes, I will get you the materials.",
                    "Yes.",
                    "Yes",
            };
            for (String opt : exactPreferred) {
                final String target = opt;
                if (Dialog.hasOption(s -> s != null && s.equalsIgnoreCase(target))) {
                    Dialog.chooseOption(s -> s != null && s.equalsIgnoreCase(target));
                    dialogFallbackHits = 0;
                    lastDialogSig = "";
                    DebugLog.log("DoricQuest", "Dialog exact: " + opt);
                    return antiBan.varyDelay(randomDelay(700, 1300));
                }
            }
            String[] containsPreferred = {
                    "start doric",
                    "get you the materials", "materials",
                    "whetstone", "anvil", "use your anvils", "use your anvil",
                    "clay", "copper", "iron",
            };
            for (String key : containsPreferred) {
                final String k = key.toLowerCase(Locale.ROOT);
                if (Dialog.hasOption(s -> s != null && s.toLowerCase(Locale.ROOT).contains(k))) {
                    Dialog.chooseOption(s -> s != null && s.toLowerCase(Locale.ROOT).contains(k));
                    dialogFallbackHits = 0;
                    lastDialogSig = "";
                    DebugLog.log("DoricQuest", "Dialog contains: " + key);
                    return antiBan.varyDelay(randomDelay(700, 1300));
                }
            }
            String dialogSig = buildDialogOptionsSignature();
            if (!dialogSig.isEmpty() && dialogSig.equals(lastDialogSig)) {
                dialogFallbackHits++;
            } else {
                dialogFallbackHits = 1;
                lastDialogSig = dialogSig;
            }
            DebugLog.log("DoricQuest", "Dialog fallback | opties=" + dialogSig);
            if (antiBan != null) {
                antiBan.notifyHandlerAction("doric-dialog-fallback", 1500);
            }
            if (dialogFallbackHits >= 1) {
                try {
                    net.storm.sdk.input.Keyboard.pressed(java.awt.event.KeyEvent.VK_1);
                    DebugLog.log("DoricQuest", "Dialog: keyboard '1' (Start quest / Yes)");
                    return antiBan.varyDelay(randomDelay(900, 1500));
                } catch (Throwable t) {
                    DebugLog.log("DoricQuest", "Dialog keyboard '1': " + t.getMessage());
                }
            }
            Dialog.chooseOption(0);
            return antiBan.varyDelay(randomDelay(700, 1300));
        }

        if (Dialog.canContinue()) {
            if (antiBan != null) {
                antiBan.notifyHandlerAction("doric-dialog-continue", 1000);
            }
            Dialog.continueSpace();
            return antiBan.varyDelay(randomDelay(500, 900));
        }
        return antiBan.varyDelay(randomDelay(400, 800));
    }

    private void depositNonQuestItems() {
        try {
            for (IInventoryItem item : Inventory.getAll()) {
                if (item == null || item.getName() == null) {
                    continue;
                }
                String n = Text.removeTags(item.getName()).trim();
                if (n.equalsIgnoreCase("Coins")) {
                    continue;
                }
                if (isQuestMaterialName(n)) {
                    continue;
                }
                // Bronze pickaxe van Doric of eigen pickaxe: niet mee naar quest-aflevering (12 erts-slots).
                if (n.toLowerCase(Locale.ROOT).contains("pickaxe")) {
                    Bank.depositAll(n);
                    HumanBanking.pauseBetweenActions();
                    continue;
                }
                Bank.depositAll(n);
                HumanBanking.pauseBetweenActions();
            }
        } catch (Exception e) {
            DebugLog.log("DoricQuest", "depositNonQuestItems: " + e.getMessage());
        }
    }

    private void withdrawUpTo(String itemName, int target) {
        int have = countExactUnnotedInv(itemName);
        int need = target - have;
        if (need <= 0) {
            return;
        }
        if (!Bank.contains(itemName)) {
            return;
        }
        Bank.withdraw(itemName, need);
        HumanBanking.pauseBetweenActions();
    }

    private static boolean isQuestMaterialName(String n) {
        return n.equalsIgnoreCase(CLAY) || n.equalsIgnoreCase(COPPER) || n.equalsIgnoreCase(IRON);
    }

    private static int requiredFor(String itemName) {
        if (CLAY.equalsIgnoreCase(itemName)) {
            return NEED_CLAY;
        }
        if (COPPER.equalsIgnoreCase(itemName)) {
            return NEED_COPPER;
        }
        if (IRON.equalsIgnoreCase(itemName)) {
            return NEED_IRON;
        }
        return 0;
    }

    /** Eerste materiaal dat echt via GE gekocht moet worden (bank+inv samen nog tekort). */
    private String firstItemNeedingGePurchase() {
        for (String item : GE_BUY_ORDER) {
            if (needsGePurchase(item)) {
                return item;
            }
        }
        return null;
    }

    private boolean needsGePurchase(String itemName) {
        int need = requiredFor(itemName);
        if (countExactUnnotedInv(itemName) >= need) {
            return false;
        }
        return countExactUnnotedInv(itemName) + bankCountFor(itemName) < need;
    }

    private int geBuyQuantityFor(String itemName) {
        int need = requiredFor(itemName);
        int have = countExactUnnotedInv(itemName);
        return Math.max(1, need - have);
    }

    private static int getCoinCount() {
        try {
            return Inventory.getCount(true, "Coins");
        } catch (Exception e) {
            return 0;
        }
    }

    private int estimatedGeCostFor(String itemName) {
        if (itemName == null || itemName.isEmpty()) {
            return 100;
        }
        return Math.max(1, geBuyQuantityFor(itemName)) * Math.max(1, geStartPrice(itemName));
    }

    /** Genoeg gp op zak, of bank/snapshot heeft coins om te withdrawen. */
    private boolean canAffordGePurchase(String itemName) {
        int cost = estimatedGeCostFor(itemName);
        if (getCoinCount() >= cost) {
            return true;
        }
        String rsn = BankSnapshotPlanner.currentDisplayName();
        if (BankSnapshotPlanner.knownBankQty(rsn, "Coins") > 0) {
            return true;
        }
        if (!BankSnapshotPlanner.hasPersistedSnapshot(rsn)) {
            return true;
        }
        if (Bank.isOpen() && Bank.contains("Coins")) {
            return true;
        }
        return false;
    }

    /**
     * Trek coins naar inv vóór GE. Return delay &gt; 0 als withdraw gedaan.
     * Bij geen coins in bank: needCoinsForGe uit, GE-sessie uit.
     */
    private int tryWithdrawCoinsForGe() {
        if (!needCoinsForGe && !geShoppingSession) {
            return 0;
        }
        String pending = pendingGeItem != null ? pendingGeItem : firstItemNeedingGePurchase();
        int needGp = pending != null ? estimatedGeCostFor(pending) : 100;
        if (getCoinCount() >= needGp) {
            needCoinsForGe = false;
            return 0;
        }
        if (!Bank.contains("Coins")) {
            int sellPrep = tryWithdrawSellableForCoinsFromBank();
            if (sellPrep > 0) {
                return sellPrep;
            }
            if (findSellableItemInInventory() != null) {
                geSellForCoinsItem = findSellableItemInInventory();
                Bank.close();
                bankingManager.waitForBankClose();
                inBankSession = false;
                phase = Phase.GE_SELL_COINS;
                paint.setLastAntiBanAction("Doric: GE-verkoop voor gp");
                return antiBan.varyDelay(randomDelay(500, 900));
            }
            DebugLog.log("DoricQuest", "Geen coins en geen verkoopbaar loot (need~=" + needGp + ")");
            phase = Phase.GATHER;
            return 0;
        }
        int before = getCoinCount();
        Bank.withdraw("Coins", Integer.MAX_VALUE);
        HumanBanking.pauseBetweenActions();
        int after = getCoinCount();
        needCoinsForGe = false;
        DebugLog.log("DoricQuest", "Coins bank→inv (+" + (after - before) + ", nu " + after + ", need~=" + needGp + ")");
        paint.setLastAntiBanAction("Doric: coins opgehaald (" + after + " gp)");
        if (geShoppingSession && pendingGeItem != null && after >= needGp) {
            Bank.close();
            bankingManager.waitForBankClose();
            inBankSession = false;
            phase = Phase.GE_BUY;
        }
        return antiBan.varyDelay(randomDelay(500, 900));
    }

    private int startCoinRecovery(IPlayer local, String reason) {
        needCoinsForGe = true;
        if (getCoinCount() >= minCoinsNeededForGe()) {
            needCoinsForGe = false;
            if (geShoppingSession && pendingGeItem != null) {
                phase = Phase.GE_BUY;
                return phaseGeBuy(local);
            }
            phase = Phase.GATHER;
            return phaseGather(local);
        }
        if (findSellableItemInInventory() != null) {
            geSellForCoinsItem = findSellableItemInInventory();
            phase = Phase.GE_SELL_COINS;
            paint.setCurrentStatus("Doric's Quest · loot verkopen voor gp");
            paint.setLastAntiBanAction("Doric: GE-verkoop (" + reason + ")");
            DebugLog.log("DoricQuest", "Coin recovery → GE sell: " + geSellForCoinsItem);
            return phaseGeSellForCoins(local);
        }
        phase = Phase.WALK_BANK;
        paint.setCurrentStatus("Doric's Quest · gp ophalen/verdienen");
        paint.setLastAntiBanAction("Doric: → bank (gp, " + reason + ")");
        DebugLog.log("DoricQuest", "Coin recovery → bank: " + reason);
        return phaseWalkBank(local);
    }

    private int minCoinsNeededForGe() {
        String item = pendingGeItem != null ? pendingGeItem : firstItemNeedingGePurchase();
        if (item == null) {
            return 90;
        }
        return estimatedGeCostFor(item);
    }

    private boolean canSellItemForCoins(String itemName) {
        if (itemName == null || itemName.isEmpty()) {
            return false;
        }
        if (!isQuestMaterialName(itemName)) {
            return true;
        }
        return totalAvailable(itemName) > requiredFor(itemName);
    }

    private String findSellableItemInInventory() {
        for (String name : COIN_SELL_PRIORITY) {
            if (!canSellItemForCoins(name)) {
                continue;
            }
            try {
                if (Inventory.getCount(true, name) > 0) {
                    return name;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private String findSellableItemInBank() {
        if (!Bank.isOpen()) {
            return null;
        }
        for (String name : COIN_SELL_PRIORITY) {
            if (!canSellItemForCoins(name)) {
                continue;
            }
            if (Bank.contains(name)) {
                return name;
            }
        }
        return null;
    }

    /** Haal één verkoopbare stack uit bank voor GE-verkoop. */
    private int tryWithdrawSellableForCoinsFromBank() {
        if (!Bank.isOpen()) {
            return 0;
        }
        String sell = findSellableItemInBank();
        if (sell == null) {
            return 0;
        }
        int qty = Math.min(28, Math.max(1, Bank.getCount(true, sell)));
        Bank.withdraw(sell, qty);
        HumanBanking.pauseBetweenActions();
        geSellForCoinsItem = sell;
        phase = Phase.GE_SELL_COINS;
        paint.setLastAntiBanAction("Doric: " + qty + "× " + sell + " voor GE-verkoop");
        DebugLog.log("DoricQuest", "Withdraw voor GE-sell: " + qty + "× " + sell);
        return antiBan.varyDelay(randomDelay(500, 900));
    }

    private int phaseGeSellForCoins(IPlayer local) {
        int needGp = minCoinsNeededForGe();
        if (getCoinCount() >= needGp) {
            needCoinsForGe = false;
            geSellForCoinsItem = null;
            paint.setLastAntiBanAction("Doric: gp ok (" + getCoinCount() + ") → verder");
            if (geShoppingSession && firstItemNeedingGePurchase() != null) {
                pendingGeItem = firstItemNeedingGePurchase();
                pendingGeQty = geBuyQuantityFor(pendingGeItem);
                phase = Phase.GE_BUY;
                return phaseGeBuy(local);
            }
            phase = Phase.GATHER;
            return phaseGather(local);
        }

        if (hasNotedQuestMaterialInInventory() && !isActiveGeShoppingSession()) {
            phase = Phase.WALK_BANK;
            geShoppingSession = false;
            return antiBan.varyDelay(randomDelay(600, 1000));
        }

        WorldPoint me = local.getWorldLocation();
        if (me != null && me.distanceTo(GE_TILE) > 12) {
            issueWalk(GE_TILE);
            paint.setLastAntiBanAction("Doric: → GE (verkoop voor gp)");
            return travelDelay();
        }

        try {
            if (!GrandExchange.isOpen()) {
                GrandExchange.open();
                return antiBan.varyDelay(randomDelay(900, 1500));
            }
            if (GrandExchange.canCollect()) {
                GrandExchange.collect(false);
                return antiBan.varyDelay(randomDelay(600, 1000));
            }
        } catch (Exception e) {
            DebugLog.log("DoricQuest", "GE sell open: " + e.getMessage());
        }

        if (geSellForCoinsItem == null || geSellForCoinsItem.isEmpty()) {
            geSellForCoinsItem = findSellableItemInInventory();
        }
        if (geSellForCoinsItem == null) {
            phase = Phase.WALK_BANK;
            paint.setLastAntiBanAction("Doric: geen loot om te verkopen → bank");
            return phaseWalkBank(local);
        }

        int qty;
        try {
            qty = Inventory.getCount(true, geSellForCoinsItem);
        } catch (Exception e) {
            qty = 0;
        }
        if (qty <= 0) {
            geSellForCoinsItem = findSellableItemInInventory();
            if (geSellForCoinsItem == null) {
                phase = Phase.WALK_BANK;
                return phaseWalkBank(local);
            }
            qty = Inventory.getCount(true, geSellForCoinsItem);
        }

        int sellPrice = geSellPrice(geSellForCoinsItem);
        paint.setCurrentStatus("Doric's Quest · verkoop " + qty + "× " + geSellForCoinsItem + " @" + sellPrice);
        try {
            boolean done = GrandExchange.exchange(false, geSellForCoinsItem, qty, sellPrice, true, true);
            if (done) {
                DebugLog.log("DoricQuest", "GE sell done: " + qty + "× " + geSellForCoinsItem + " @" + sellPrice);
                geSellForCoinsItem = null;
            }
            return antiBan.varyDelay(randomDelay(done ? 1200 : 800, done ? 2000 : 1400));
        } catch (Exception e) {
            DebugLog.log("DoricQuest", "GE sell: " + e.getMessage());
            geSellForCoinsItem = null;
            phase = Phase.WALK_BANK;
            return phaseWalkBank(local);
        }
    }

    private static int geSellPrice(String itemName) {
        int guide = geStartPrice(itemName);
        return Math.max(1, (int) (guide * 0.85));
    }

    private void clearBankMaterialSnapshot() {
        bankSnapClay = -1;
        bankSnapCopper = -1;
        bankSnapIron = -1;
        bankMaterialSnapValid = false;
    }

    private void refreshBankMaterialSnapshot() {
        if (!Bank.isOpen()) {
            return;
        }
        bankSnapClay = countExactUnnotedBank(CLAY);
        bankSnapCopper = countExactUnnotedBank(COPPER);
        bankSnapIron = countExactUnnotedBank(IRON);
        bankMaterialSnapValid = true;
    }

    private int bankCountFor(String itemName) {
        if (Bank.isOpen()) {
            return countExactUnnotedBank(itemName);
        }
        loadPersistedBankMaterialSnapshot();
        if (bankMaterialSnapValid) {
            if (CLAY.equalsIgnoreCase(itemName)) {
                return Math.max(0, bankSnapClay);
            }
            if (COPPER.equalsIgnoreCase(itemName)) {
                return Math.max(0, bankSnapCopper);
            }
            if (IRON.equalsIgnoreCase(itemName)) {
                return Math.max(0, bankSnapIron);
            }
        }
        return Math.max(0, BankSnapshotPlanner.knownBankQty(BankSnapshotPlanner.currentDisplayName(), itemName));
    }

    private void loadPersistedBankMaterialSnapshot() {
        if (bankMaterialSnapValid || Bank.isOpen()) {
            return;
        }
        String rsn = BankSnapshotPlanner.currentDisplayName();
        if (!BankSnapshotPlanner.hasPersistedSnapshot(rsn)) {
            return;
        }
        bankSnapClay = BankSnapshotPlanner.knownBankQty(rsn, CLAY);
        bankSnapCopper = BankSnapshotPlanner.knownBankQty(rsn, COPPER);
        bankSnapIron = BankSnapshotPlanner.knownBankQty(rsn, IRON);
        bankMaterialSnapValid = true;
    }

    private int totalAvailable(String itemName) {
        return countExactUnnotedInv(itemName) + bankCountFor(itemName);
    }

    private static int unnotedIdFor(String itemName) {
        if (CLAY.equalsIgnoreCase(itemName)) {
            return ID_CLAY;
        }
        if (COPPER.equalsIgnoreCase(itemName)) {
            return ID_COPPER_ORE;
        }
        if (IRON.equalsIgnoreCase(itemName)) {
            return ID_IRON_ORE;
        }
        return -1;
    }

    private static int countExactUnnotedInv(String itemName) {
        int id = unnotedIdFor(itemName);
        if (id <= 0) {
            return 0;
        }
        int total = 0;
        try {
            for (IInventoryItem item : Inventory.getAll()) {
                if (item == null) {
                    continue;
                }
                if (item.getId() == id) {
                    total += Math.max(0, item.getQuantity());
                }
            }
        } catch (Exception ignored) {
        }
        return total;
    }

    /** Tijdens GE-shop: noted stacks in inv zijn ok — bank pas na alle GE-kopen. */
    private boolean isActiveGeShoppingSession() {
        return geShoppingSession || phase == Phase.GE_BUY;
    }

    private static boolean hasNotedQuestMaterialInInventory() {
        return inventoryContainsId(ID_CLAY + 1)
                || inventoryContainsId(ID_COPPER_ORE + 1)
                || inventoryContainsId(ID_IRON_ORE + 1);
    }

    private static boolean inventoryContainsId(int itemId) {
        if (itemId <= 0) {
            return false;
        }
        try {
            for (IInventoryItem item : Inventory.getAll()) {
                if (item != null && item.getId() == itemId && item.getQuantity() > 0) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private void depositAllQuestMaterials() {
        depositQuestMaterial(CLAY);
        depositQuestMaterial(COPPER);
        depositQuestMaterial(IRON);
    }

    private void depositQuestMaterial(String itemName) {
        try {
            if (countExactUnnotedInv(itemName) > 0 || inventoryContainsId(unnotedIdFor(itemName) + 1)) {
                Bank.depositAll(itemName);
                HumanBanking.pauseBetweenActions();
            }
        } catch (Exception e) {
            DebugLog.log("DoricQuest", "depositQuestMaterial " + itemName + ": " + e.getMessage());
        }
    }

    /** @return true als we nog een tick moeten wachten op unnoted-withdraw-modus */
    private boolean ensureBankWithdrawUnnoted() {
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                if (!Bank.isNotedWithdrawMode()) {
                    return false;
                }
                Bank.setWithdrawMode(false);
                return true;
            } catch (Exception ignored) {
                return false;
            }
        }
        return false;
    }

    private static int countExactUnnotedBank(String itemName) {
        if (!Bank.isOpen()) {
            return 0;
        }
        int id = unnotedIdFor(itemName);
        if (id <= 0) {
            return 0;
        }
        try {
            return Bank.getCount(true, item -> item != null && item.getId() == id);
        } catch (Exception e) {
            return 0;
        }
    }

    private static boolean hasAllMaterialsInInventory() {
        return countExactUnnotedInv(CLAY) >= NEED_CLAY
                && countExactUnnotedInv(COPPER) >= NEED_COPPER
                && countExactUnnotedInv(IRON) >= NEED_IRON;
    }

    private static String materialProgressLabel() {
        return countExactUnnotedInv(CLAY) + "/" + NEED_CLAY + " clay, "
                + countExactUnnotedInv(COPPER) + "/" + NEED_COPPER + " copper, "
                + countExactUnnotedInv(IRON) + "/" + NEED_IRON + " iron";
    }

    private static int geStartPrice(String itemName) {
        if (itemName == null) {
            return 50;
        }
        try {
            if (GrandExchange.isOpen()) {
                int guide = GrandExchange.getGuidePrice();
                if (guide > 0) {
                    return Math.max(1, (int) Math.ceil(guide * 1.15));
                }
            }
        } catch (Exception ignored) {
        }
        String n = itemName.toLowerCase(Locale.ROOT);
        if (n.contains("clay")) {
            return 15;
        }
        if (n.contains("copper")) {
            return 60;
        }
        if (n.contains("iron")) {
            return 180;
        }
        return 50;
    }

    private static int readDoricVarp() {
        try {
            return Vars.getVarp(VARP_DORICS_QUEST);
        } catch (Exception e) {
            DebugLog.log("DoricQuest", "Vars.getVarp: " + e.getMessage());
            return 0;
        }
    }

    /** Binnen hut: anker 2952,3451 ±1 tegel. */
    private static boolean insideDoricHouse(WorldPoint p) {
        return p != null && p.getPlane() == 0
                && p.distanceTo(DORIC_HOUSE_ANCHOR) <= DORIC_INSIDE_TILE_RADIUS;
    }

    /** Deur open → mag praten; anders moet je binnen ±1 van anker staan. */
    private static boolean canTalkToDoric(WorldPoint me) {
        if (me == null || me.getPlane() != 0) {
            return false;
        }
        if (insideDoricHouse(me)) {
            return true;
        }
        return isDoricDoorOpen() && me.distanceTo(DORIC_HOUSE_ANCHOR) <= 4;
    }

    private static boolean nearDoricHouseForDoor(IPlayer local) {
        WorldPoint me = local.getWorldLocation();
        return me != null
                && (me.distanceTo(DORIC_DOOR_TILE) <= 14
                || me.distanceTo(DORIC_DOOR_APPROACH_TILE) <= 14
                || me.distanceTo(DORIC_HOUSE_ANCHOR) <= 14);
    }

    private static ITileObject findDoricHouseDoor() {
        return TileObjects.getNearest(obj -> {
            if (obj == null || obj.getName() == null || obj.getWorldLocation() == null) {
                return false;
            }
            WorldPoint loc = obj.getWorldLocation();
            if (loc.getPlane() != 0 || loc.distanceTo(DORIC_DOOR_TILE) > 1) {
                return false;
            }
            return obj.getName().toLowerCase(Locale.ROOT).contains("door");
        });
    }

    /** Deur al open (tooltip "Close Door") — niet nogmaals klikken. */
    private static boolean isDoricDoorOpen() {
        ITileObject door = findDoricHouseDoor();
        return door != null && door.hasAction("Close");
    }

    /** Alleen "Open" — nooit "Close" of "Handle" op een al open deur. */
    private boolean tryOpenDoricDoor(IPlayer local) {
        if (!clickCooldownOk(lastObjMs)) {
            return false;
        }
        if (isDoricDoorOpen() || insideDoricHouse(local.getWorldLocation())) {
            return false;
        }
        WorldPoint me = local.getWorldLocation();
        if (me == null || me.distanceTo(DORIC_DOOR_TILE) > 10) {
            return false;
        }
        ITileObject door = findDoricHouseDoor();
        if (door == null || !door.hasAction("Open")) {
            return false;
        }
        if (me.distanceTo(DORIC_DOOR_TILE) > 2 && me.distanceTo(DORIC_DOOR_APPROACH_TILE) > 2) {
            issueWalk(DORIC_DOOR_APPROACH_TILE);
            return false;
        }
        door.interact("Open");
        lastObjMs = System.currentTimeMillis();
        DebugLog.log("DoricQuest", "Deur Open @ " + door.getWorldLocation());
        return true;
    }

    /**
     * "Start Doric's Quest?" gebruikt soms geen {@link Dialog#isViewingOptions()} — widget/keyboard.
     */
    private Integer tryAcceptDoricYesDialog() {
        try {
            java.util.List<IWidget> opts = Dialog.getOptions();
            if (opts != null && !opts.isEmpty()) {
                for (IWidget w : opts) {
                    String txt = widgetText(w);
                    if (isNumberedYesOption(txt)) {
                        w.interact(0);
                        dialogFallbackHits = 0;
                        lastDialogSig = "";
                        DebugLog.log("DoricQuest", "Dialog widget Yes: " + txt);
                        return antiBan.varyDelay(randomDelay(700, 1300));
                    }
                }
                String sig = buildDialogOptionsSignature();
                if (sig.toLowerCase(Locale.ROOT).contains("yes") || sig.contains("(1)")) {
                    opts.get(0).interact(0);
                    DebugLog.log("DoricQuest", "Dialog widget optie 0: " + sig);
                    return antiBan.varyDelay(randomDelay(700, 1300));
                }
            }
        } catch (Throwable t) {
            DebugLog.log("DoricQuest", "Dialog widget klik: " + t.getMessage());
        }
        if (Dialog.hasOption(DoricsQuestHandler::isNumberedYesOption)) {
            Dialog.chooseOption(DoricsQuestHandler::isNumberedYesOption);
            dialogFallbackHits = 0;
            lastDialogSig = "";
            DebugLog.log("DoricQuest", "Dialog chooseOption Yes");
            return antiBan.varyDelay(randomDelay(700, 1300));
        }
        String sig = buildDialogOptionsSignature();
        if (!sig.isEmpty() && (sig.toLowerCase(Locale.ROOT).contains("yes") || sig.contains("(1)"))) {
            try {
                net.storm.sdk.input.Keyboard.pressed(java.awt.event.KeyEvent.VK_1);
                DebugLog.log("DoricQuest", "Dialog keyboard 1 (quest Yes): " + sig);
                return antiBan.varyDelay(randomDelay(900, 1500));
            } catch (Throwable t) {
                DebugLog.log("DoricQuest", "Dialog keyboard 1: " + t.getMessage());
            }
        }
        return null;
    }

    private static String widgetText(IWidget w) {
        if (w == null || w.getText() == null) {
            return "";
        }
        return Text.removeTags(w.getText()).trim();
    }

    private static boolean isNumberedYesOption(String raw) {
        if (raw == null) {
            return false;
        }
        String t = Text.removeTags(raw).trim().toLowerCase(Locale.ROOT);
        if (t.equals("no.") || t.equals("no") || (t.contains("no") && !t.contains("yes"))) {
            return false;
        }
        if (t.contains("(1)") && t.contains("yes")) {
            return true;
        }
        return t.equals("yes.") || t.equals("yes");
    }

    private static String buildDialogOptionsSignature() {
        try {
            java.util.List<IWidget> shown = Dialog.getOptions();
            if (shown == null || shown.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < shown.size(); i++) {
                if (i > 0) {
                    sb.append('|');
                }
                IWidget w = shown.get(i);
                String txt = "";
                if (w != null && w.getText() != null) {
                    txt = Text.removeTags(w.getText()).trim();
                }
                sb.append(txt);
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private boolean clickCooldownOk(long last) {
        return System.currentTimeMillis() - last > 900;
    }

    /** Geen RSN-offset: exacte binnen-tegel zodat de deur-lijn klopt. */
    private void issueWalkToDoricHouseInside() {
        long now = System.currentTimeMillis();
        if (now - lastWalkMs < 900) {
            return;
        }
        lastWalkMs = now;
        MovementHelper.walkToExact(DORIC_HOUSE_ANCHOR);
    }

    private void issueWalk(WorldPoint target) {
        long now = System.currentTimeMillis();
        if (now - lastWalkMs < 900) {
            return;
        }
        lastWalkMs = now;
        MovementHelper.walkTo(target);
    }

    private int travelDelay() {
        return antiBan.varyDelay(randomDelay(
                Math.max(400, config.travelPostClickDelayMin()),
                Math.max(600, config.travelPostClickDelayMax())));
    }

    private int randomDelay(int min, int max) {
        if (max <= min) {
            return min;
        }
        return min + random.nextInt(max - min);
    }
}
