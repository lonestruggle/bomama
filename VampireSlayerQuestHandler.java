package com.combatbot;

import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.util.Text;
import net.storm.api.domain.actors.INPC;
import net.storm.api.domain.actors.IPlayer;
import net.storm.api.domain.items.IInventoryItem;
import net.storm.api.domain.tiles.ITileObject;
import net.storm.api.domain.widgets.IWidget;
import net.storm.api.magic.SpellBook;
import net.storm.api.plugins.config.ConfigManager;
import net.storm.sdk.entities.NPCs;
import net.storm.sdk.entities.Players;
import net.storm.sdk.entities.TileObjects;
import net.storm.sdk.game.Combat;
import net.storm.sdk.game.Skills;
import net.storm.sdk.game.Vars;
import net.storm.sdk.items.Bank;
import net.storm.sdk.items.Equipment;
import net.storm.sdk.items.Inventory;
import net.storm.sdk.magic.Magic;
import net.storm.sdk.widgets.Dialog;
import net.storm.sdk.widgets.Widgets;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Random;

/**
 * Vampyre Slayer — quest-flow puur op basis van VarPlayer 178 ({@code QUEST_VAMPYRE_SLAYER})
 * en de Storm SDK {@link Dialog} helper. Geen JSON-opslag meer.
 *
 * VarPlayer 178 waarden:
 *  - 0 : niet gestart                 → praten met Morgan in Draynor
 *  - 1 : quest gestart                → garlic uit cupboard, beer + stake bij Dr Harlow,
 *                                       hammer kopen (of via Imp), naar Draynor Manor
 *  - 2 : klaar voor Count Draynor     → coffin openen + Count verslaan
 *  - 3 : quest voltooid
 */
public final class VampireSlayerQuestHandler {

    /** VarPlayer ID voor Vampyre Slayer (vast in OSRS). */
    private static final int VARP_VAMPYRE_SLAYER = 178;

    private static final WorldPoint MORGAN_TILE = new WorldPoint(3098, 3268, 0);
    /** Veilige tegel binnen Morgan's huis; pathfinder opent benodigde deuren zelf. */
    private static final WorldPoint MORGAN_HOUSE_INSIDE_TILE = new WorldPoint(3098, 3267, 0);
    private static final WorldPoint UPSTAIRS_AREA = new WorldPoint(3100, 3267, 1);
    /** Alleen trappen/kast binnen deze afstand van Morgan (voorkomt verkeerde trap elders in Draynor). */
    private static final int MORGAN_HOUSE_OBJECT_MAX_DIST = 14;
    /** Pas garlic/trap-logica uit als we echt bij Morgan in Draynor zijn (niet in Varrock Blue Moon e.d.). */
    private static final int GARLIC_MORGAN_AREA_MAX_DIST = 28;
    private static final WorldPoint DRAYNOR_BANK_TILE = new WorldPoint(3093, 3243, 0);
    private static final WorldPoint HARLOW_TILE = new WorldPoint(3222, 3398, 0);
    private static final WorldPoint BARTENDER_TILE = new WorldPoint(3226, 3400, 0);
    private static final WorldPoint VARROCK_GEN_STORE = new WorldPoint(3217, 3415, 0);
    /** Tegel vóór de hoofdingang Draynor Manor (lopen naar deze punt). */
    private static final WorldPoint MANOR_DOOR_STAND_TILE = new WorldPoint(3108, 3351, 0);
    /** Hoofdingang — interactie "Open Large door". */
    private static final WorldPoint MANOR_LARGE_DOOR_TILE = new WorldPoint(3108, 3353, 0);
    /** Binnen begane grond: trap naar kelder/crypt — "Walk-down Stairs" (object op 3115,3357). */
    private static final WorldPoint MANOR_STAIRS_DOWN_TILE = new WorldPoint(3115, 3357, 0);
    /**
     * Grote voordeur staat op Y=3353; alles op begane grond met Y≥3354 is binnen het manor (niet meer via voordeur).
     */
    private static final int MANOR_INTERIOR_MIN_Y = 3354;
    /** Coffin in de Draynor Manor crypt. */
    private static final WorldPoint COFFIN_TILE = new WorldPoint(3078, 9772, 0);

    private static final int CUPBOARD_ID = 2612;
    /** Draynor Manor begane grond — trap naar beneden (Object ID, RuneLite overlay). */
    private static final int MANOR_STAIRS_DOWN_OBJECT_ID = 2616;
    /** Bier voor Dr Harlow in de Blue Moon Inn kost 2 gp; dit is de minimum inventory-check. */
    private static final int MIN_COINS_BEER = 2;

    /** Shop interface widget group. */
    private static final int SHOP_WIDGET_GROUP = 300;
    /** Per-fase failsafe — als we langer dan dit op één fase zitten, reset state. */
    private static final long PHASE_FAILSAFE_MS = 5 * 60 * 1000L;
    /** Minimaal aantal runes (inv + equipment) om mage vs Count te blijven gebruiken. */
    private static final int MIN_RUNES_COUNT_FIGHT = 5;
    /** Low-level safety: neem wat food mee als het in de bank ligt. */
    private static final int MIN_QUEST_FOOD = 5;
    private static final String[] QUEST_FOOD_PRIORITY = {
            "Swordfish", "Lobster", "Tuna", "Salmon", "Trout", "Herring", "Sardine",
            "Shrimps", "Anchovies", "Cooked meat", "Cooked chicken", "Bread"
    };

    private final CombatBotConfig config;
    private final AntiBan antiBan;
    private final CombatBotPaint paint;
    private final ConfigManager cm;
    private final Random random = new Random();

    private long lastWalkMs;
    private long lastNpcMs;
    private long lastObjMs;
    private long lastDialogMs;
    private long lastShopMs;
    private long lastBankActionMs;
    private long lastEatMs;
    private long coffinOpenMs;
    private long lastCoffinInteractMs;
    /** Eén keer coffin Open/Search; pas na failsafe opnieuw. */
    private boolean coffinOpenedOnce;
    private boolean attackedCount;
    private boolean inBankSession = false;
    private boolean visitedDraynorBank = false;
    private boolean outOfFoodForQuest = false;
    private final Deque<Long> recentDoorMs = new ArrayDeque<>();
    private long morganDoorClickMs;

    private int lastPhase = -1;
    private long phaseStartMs = 0L;
    private long lastMeleeEquipMs;

    public VampireSlayerQuestHandler(CombatBotConfig config, AntiBan antiBan, CombatBotPaint paint, ConfigManager cm) {
        this.config = config;
        this.antiBan = antiBan;
        this.paint = paint;
        this.cm = cm;
    }

    public int loop() {
        IPlayer local = Players.getLocal();
        if (local == null || local.getWorldLocation() == null) {
            return 1000;
        }

        // 1. Eerst altijd dialoog/menu's afhandelen — ongeacht stap.
        Integer dialogDelay = handleDialog();
        if (dialogDelay != null) {
            return dialogDelay;
        }

        int eatDelay = tryEatAtOrBelowFiveHp();
        if (eatDelay > 0) {
            return eatDelay;
        }

        int questVar = readQuestVar();
        trackPhase(questVar);
        paint.setCurrentStatus("Vampyre Slayer (varp=" + questVar + ")");

        // FIX #1: Bank wordt NIET meer blind gesloten — alleen als we niet midden in een
        // bank-sessie zitten (visitDraynorBank zet inBankSession).
        if (Bank.isOpen() && !inBankSession) {
            Bank.close();
            return antiBan.varyDelay(randomDelay(400, 800));
        }

        switch (questVar) {
            case 0:
                return phaseStart(local);
            case 1:
                return phasePrepareAndHarlow(local);
            case 2:
                return phaseManorAndCount(local);
            case 3:
            default:
                return finishComplete();
        }
    }

    public boolean isOutOfFoodForQuest() {
        return outOfFoodForQuest;
    }

    public void resetOutOfFoodForQuest() {
        outOfFoodForQuest = false;
    }

    /** Reset per-fase failsafe state als de fase wijzigt. */
    private void trackPhase(int phase) {
        long now = System.currentTimeMillis();
        if (phase != lastPhase) {
            lastPhase = phase;
            phaseStartMs = now;
            visitedDraynorBank = false;
            inBankSession = false;
            lastMeleeEquipMs = 0L;
            if (phase == 2) {
                coffinOpenedOnce = false;
                attackedCount = false;
                coffinOpenMs = 0L;
            }
            DebugLog.log("QUEST", "Fase gewijzigd → varp=" + phase);
        } else if (phaseStartMs > 0 && now - phaseStartMs > PHASE_FAILSAFE_MS) {
            DebugLog.log("QUEST", "Failsafe: fase " + phase + " > 5 min, reset state");
            phaseStartMs = now;
            visitedDraynorBank = false;
            inBankSession = false;
            attackedCount = false;
            coffinOpenedOnce = false;
            coffinOpenMs = 0L;
        }
    }

    // ===================================================================
    // Dialog handling — gebruikt Storm SDK Dialog API
    // ===================================================================

    /**
     * Handelt elk open dialoog of optie-menu af.
     * @return delay in ms, of null als geen dialoog open is.
     */
    private Integer handleDialog() {
        if (!Dialog.isOpen()) {
            return null;
        }
        // throttle dialog clicks
        long now = System.currentTimeMillis();
        if (now - lastDialogMs < 350) {
            return 200;
        }
        lastDialogMs = now;

        if (Dialog.isViewingOptions()) {
            // FIX #2: Korte woorden ("Yes") moeten exact matchen, lange substrings via contains.
            String[] exactPreferred = {
                    "Yes", "Yes.", "Yes please.", "Yes, I will.",
                    // Blue Moon Inn / barman (OSRS): bier wordt "ale" genoemd, niet "beer"
                    "A glass of your finest ale please.",
                    // Dr Harlow — 1e gesprek (wiki transcript)
                    "Morgan needs your help!",
                    "His village is being terrorised by a vampyre! He told me to ask you about how I can stop it.",
                    "His village is being terrorized by a vampyre! He told me to ask you about how I can stop it.",
                    "But this is your friend Morgan we're talking about!",
                    // Dr Harlow — 2e gesprek met bier op zak: bier afgeven, daarna stake
                    "Here you go.",
            };
            for (String opt : exactPreferred) {
                final String target = opt;
                if (Dialog.hasOption(s -> s != null && s.equalsIgnoreCase(target))) {
                    Dialog.chooseOption(s -> s != null && s.equalsIgnoreCase(target));
                    DebugLog.log("QUEST", "Dialog option (exact): " + opt);
                    return antiBan.varyDelay(randomDelay(700, 1300));
                }
            }
            String[] containsPreferred = {
                    "Morgan needs your help",
                    "here you go",
                    "terrorised by a vampyre", "terrorized by a vampyre",
                    "friend morgan we're talking about",
                    "So tell me how to kill vampyres",
                    "I'm in search of a vampyre", "vampyre", "vampire",
                    "I need another stake", "another stake",
                    "glass of your finest ale", "finest ale", "ale please",
                    "A beer please", "One beer please.", "beer",
                    "buy", "trade"
            };
            for (String opt : containsPreferred) {
                final String key = opt.toLowerCase(Locale.ROOT);
                if (Dialog.hasOption(s -> s != null && s.toLowerCase(Locale.ROOT).contains(key))) {
                    Dialog.chooseOption(s -> s != null && s.toLowerCase(Locale.ROOT).contains(key));
                    DebugLog.log("QUEST", "Dialog option (contains): " + opt);
                    return antiBan.varyDelay(randomDelay(700, 1300));
                }
            }
            // Fallback: eerste optie
            Dialog.chooseOption(0);
            DebugLog.log("QUEST", "Dialog: fallback optie 0 gekozen");
            return antiBan.varyDelay(randomDelay(700, 1300));
        }

        if (Dialog.canContinue()) {
            Dialog.continueSpace();
            DebugLog.log("QUEST", "Dialog continue (spatie)");
            return antiBan.varyDelay(randomDelay(450, 800));
        }

        // Open maar niet-continueable, niet-options — even wachten
        return antiBan.varyDelay(randomDelay(400, 700));
    }

    // ===================================================================
    // Quest fases
    // ===================================================================

    /** varp == 0 — praat met Morgan om de quest te starten. */
    private int phaseStart(IPlayer local) {
        if (!insideMorganHouse(local.getWorldLocation())) {
            // Alleen path naar binnen (Storm pathfinder opent deuren). Geen losse deur-zoekradius:
            // binnen ~10t van Morgan staan meerdere deuren in Draynor.
            issueWalkToMorganHouseInside();
            paint.setCurrentStatus("Quest: Morgan — path naar huis binnen");
            return travelDelay();
        }
        morganDoorClickMs = 0L;
        if (local.getWorldLocation().distanceTo(MORGAN_TILE) > 6) {
            issueWalk(MORGAN_TILE);
            if (nearMorganHouseForDoor(local)) {
                tryClickNearbyDoor(local);
            }
            return travelDelay();
        }
        return talkToNpcContaining(local, "Morgan");
    }

    /** varp == 1 — verzamel garlic + stake + hammer, praat met Harlow. */
    private int phasePrepareAndHarlow(IPlayer local) {
        boolean hasGarlic = inventoryHasGarlic();
        boolean hasStake = inventoryHasStake();
        boolean hasHammer = Inventory.contains("Hammer");
        boolean hasEnoughCoins = inventoryCoinCount() >= MIN_COINS_BEER;
        boolean hasEnoughFood = inventoryFoodCount() >= MIN_QUEST_FOOD;

        // Garlic uit Morgan's cupboard (boven)
        if (!hasGarlic) {
            return getGarlicFromCupboard(local);
        }

        // Hammer + coins al op zak maar bank-vlag nog niet gezet (bijv. na withdraw, volgende tick skip-te bank)
        if (hasHammer && hasEnoughCoins && hasEnoughFood && !visitedDraynorBank) {
            visitedDraynorBank = true;
            inBankSession = false;
            if (Bank.isOpen()) {
                Bank.close();
            }
        }

        // Na garlic: eerst naar Draynor bank voor hammer + coins + minimaal 5 food.
        if ((!hasHammer || !hasEnoughCoins || !hasEnoughFood) && !visitedDraynorBank) {
            return visitDraynorBank(local, hasHammer, hasEnoughCoins, hasEnoughFood);
        }
        if (!hasEnoughFood) {
            paint.setCurrentStatus("Quest: wacht — minimaal " + MIN_QUEST_FOOD + " food nodig");
            DebugLog.log("QUEST", "Vampire Slayer pauze: onvoldoende food in inventory (" + inventoryFoodCount()
                    + "/" + MIN_QUEST_FOOD + ")");
            outOfFoodForQuest = true;
            return antiBan.varyDelay(randomDelay(3500, 5500));
        }

        // Stake bij Dr Harlow (vereist bier — niet leeg!)
        if (!hasStake) {
            // FIX #4: Beer-safeguard. "Beer glass" (leeg) telt niet, alleen volle Beer.
            boolean hasFullBeer = inventoryHasFullBeer();
            if (!hasFullBeer) {
                return getBeerFromBartender(local);
            }
            return talkToHarlowForStake(local);
        }

        // Hammer kopen in Varrock General Store als we hem nog niet hebben
        if (!hasHammer) {
            return buyHammer(local);
        }

        // Alles geregeld → naar Draynor Manor
        return walkToManor(local);
    }

    /**
     * Loopt naar de Draynor bank, opent hem en probeert hammer + coins te withdrawen.
     */
    private int visitDraynorBank(IPlayer local, boolean hasHammer, boolean hasEnoughCoins, boolean hasEnoughFood) {
        paint.setCurrentStatus("Quest: Draynor bank — hammer/coins/food ophalen");

        if (local.getWorldLocation().distanceTo(DRAYNOR_BANK_TILE) > 6) {
            issueWalk(DRAYNOR_BANK_TILE);
            return travelDelay();
        }

        // Altijd markeren zodat loop() de bank niet sluit vóór we klaar zijn (ook als bank al open stond).
        inBankSession = true;

        if (!Bank.isOpen()) {
            BankHelper.openSdkBankAndWait();
            lastBankActionMs = System.currentTimeMillis();
            DebugLog.log("QUEST", "Draynor bank openen");
            return antiBan.varyDelay(randomDelay(1200, 2000));
        }

        // throttle bank acties
        if (System.currentTimeMillis() - lastBankActionMs < 600) {
            return antiBan.varyDelay(randomDelay(300, 500));
        }

        // Hammer
        if (!hasHammer && Bank.contains("Hammer")) {
            Bank.withdraw("Hammer", 1);
            lastBankActionMs = System.currentTimeMillis();
            DebugLog.log("QUEST", "Hammer gewithdrawn");
            return antiBan.varyDelay(randomDelay(700, 1200));
        }

        // Coins — volledige bankstack (withdraw-via-X is hier onbetrouwbaar)
        if (!hasEnoughCoins && Bank.contains("Coins")) {
            Bank.withdrawAll("Coins");
            lastBankActionMs = System.currentTimeMillis();
            DebugLog.log("QUEST", "Alle coins uit bank gehaald (withdrawAll)");
            return antiBan.varyDelay(randomDelay(700, 1200));
        }

        // Food — neem minimaal 5 mee als er iets eetbaars in de bank ligt.
        if (!hasEnoughFood) {
            String food = firstAvailableQuestFoodInBank();
            if (food != null) {
                int need = Math.max(1, MIN_QUEST_FOOD - inventoryFoodCount());
                Bank.withdraw(food, need);
                lastBankActionMs = System.currentTimeMillis();
                DebugLog.log("QUEST", "Food meegenomen voor Vampire Slayer: " + food + " x" + need);
                return antiBan.varyDelay(randomDelay(700, 1200));
            }
            DebugLog.log("QUEST", "Geen bekende food in bank — quest wacht tot food beschikbaar is");
            paint.setCurrentStatus("Quest: geen food in bank — voeg food toe");
            outOfFoodForQuest = true;
            return antiBan.varyDelay(randomDelay(3500, 5500));
        }

        // Klaar — sluit en markeer
        visitedDraynorBank = true;
        inBankSession = false;
        Bank.close();
        DebugLog.log("QUEST", "Draynor bank klaar — door naar volgende stap");
        return antiBan.varyDelay(randomDelay(500, 900));
    }

    /** varp == 2 — coffin openen en Count Draynor verslaan. */
    private int phaseManorAndCount(IPlayer local) {
        // Veiligheidscheck: ga nooit naar Count zonder stake + hammer.
        // In sommige runs springt varp al naar 2 terwijl je die items nog niet in inventory hebt.
        boolean hasStake = inventoryHasStake();
        boolean hasHammer = Inventory.contains("Hammer");
        if (local.getWorldLocation().getY() < 9000 && inventoryFoodCount() < MIN_QUEST_FOOD) {
            paint.setCurrentStatus("Quest: Count uitgesteld — minimaal " + MIN_QUEST_FOOD + " food nodig");
            DebugLog.log("QUEST", "varp2 guard: onvoldoende food voor Count (" + inventoryFoodCount()
                    + "/" + MIN_QUEST_FOOD + ")");
            if (!visitedDraynorBank) {
                return visitDraynorBank(local, hasHammer, inventoryCoinCount() >= MIN_COINS_BEER, false);
            }
            outOfFoodForQuest = true;
            return antiBan.varyDelay(randomDelay(3500, 5500));
        }
        if (!hasStake || !hasHammer) {
            if (!hasStake) {
                if (!inventoryHasFullBeer()) {
                    paint.setCurrentStatus("Quest: varp2 maar geen stake — eerst bier halen");
                    DebugLog.log("QUEST", "varp2 guard: stake ontbreekt, bier halen");
                    return getBeerFromBartender(local);
                }
                paint.setCurrentStatus("Quest: varp2 maar geen stake — terug naar Dr Harlow");
                DebugLog.log("QUEST", "varp2 guard: stake ontbreekt, praat met Dr Harlow");
                return talkToHarlowForStake(local);
            }
            paint.setCurrentStatus("Quest: varp2 maar geen hammer — hammer kopen");
            DebugLog.log("QUEST", "varp2 guard: hammer ontbreekt, koop hammer");
            return buyHammer(local);
        }

        // In de crypt? → coffin/Count
        if (local.getWorldLocation().getY() >= 9000) {
            return openCoffinAndKill(local);
        }

        // Boven de grond: nooit trap-actie proberen zolang we nog buiten staan.
        // Eerst via voordeur naar binnen; pas daarna afdalen.
        if (!manorBeganeGrondBinnen(local.getWorldLocation())) {
            return walkToManor(local);
        }

        return descendToBasement(local);
    }

    private int finishComplete() {
        if (cm != null) {
            cm.setConfiguration("combatbot", "vampireSlayerQuestMode", "false");
        }
        paint.setLastAntiBanAction("Vampyre Slayer voltooid");
        paint.setCurrentStatus("Quest voltooid");
        return antiBan.varyDelay(randomDelay(1800, 3200));
    }

    // ===================================================================
    // Sub-stappen
    // ===================================================================

    private int getGarlicFromCupboard(IPlayer local) {
        if (Inventory.isFull()) {
            paint.setCurrentStatus("Quest: maak 1 inventory-slot vrij voor Garlic");
            return antiBan.varyDelay(randomDelay(2000, 3500));
        }
        WorldPoint me = local.getWorldLocation();
        if (distance2D(me, MORGAN_TILE) > GARLIC_MORGAN_AREA_MAX_DIST) {
            issueWalk(MORGAN_TILE);
            paint.setCurrentStatus("Quest: garlic — eerst naar Morgan (Draynor)");
            DebugLog.log("QUEST", "Garlic: te ver van Morgan — lopen i.p.v. trap elders (bijv. Varrock inn)");
            return travelDelay();
        }
        if (local.getPlane() < 1) {
            if (!insideMorganHouse(local.getWorldLocation())) {
                issueWalkToMorganHouseInside();
                paint.setCurrentStatus("Quest: garlic — path naar Morgan huis binnen");
                return travelDelay();
            }
            morganDoorClickMs = 0L;
            return interactStaircase(local, "Climb-up", MORGAN_TILE, MORGAN_HOUSE_OBJECT_MAX_DIST);
        }
        if (!clickCooldownOk(lastObjMs)) {
            return antiBan.varyDelay(randomDelay(400, 700));
        }
        ITileObject cup = TileObjects.getNearest(obj ->
                obj != null
                        && obj.getWorldLocation().distanceTo(UPSTAIRS_AREA) <= MORGAN_HOUSE_OBJECT_MAX_DIST
                        && (obj.getId() == CUPBOARD_ID
                        || (obj.getName() != null && obj.getName().toLowerCase(Locale.ROOT).contains("cupboard"))));
        if (cup == null) {
            issueWalk(UPSTAIRS_AREA);
            return travelDelay();
        }
        if (local.getWorldLocation().distanceTo(cup.getWorldLocation()) > 5) {
            issueWalk(cup.getWorldLocation());
            return travelDelay();
        }
        if (cup.hasAction("Search")) {
            cup.interact("Search");
        } else if (cup.hasAction("Open")) {
            cup.interact("Open");
        } else {
            cup.interact(0);
        }
        lastObjMs = System.currentTimeMillis();
        DebugLog.log("QUEST", "Cupboard interactie");
        return antiBan.varyDelay(randomDelay(1800, 2800));
    }

    /** 2D tile-afstand (negeert plane) om trap-op/af pingpong rond Morgan-house te voorkomen. */
    private static int distance2D(WorldPoint a, WorldPoint b) {
        if (a == null || b == null) {
            return Integer.MAX_VALUE;
        }
        int dx = Math.abs(a.getX() - b.getX());
        int dy = Math.abs(a.getY() - b.getY());
        return Math.max(dx, dy);
    }

    private int getBeerFromBartender(IPlayer local) {
        if (inventoryCoinCount() < MIN_COINS_BEER) {
            // Eén bank-poging per fase; anders eindeloos open/sluit als bank leeg is.
            if (!visitedDraynorBank) {
                paint.setCurrentStatus("Quest: coins op — Draynor bank");
                return visitDraynorBank(local, Inventory.contains("Hammer"), false,
                        inventoryFoodCount() >= MIN_QUEST_FOOD);
            }
            paint.setCurrentStatus("Quest: te weinig coins (bank leeg?) — voeg gp toe of leeg inventaris");
            return antiBan.varyDelay(randomDelay(3500, 5500));
        }
        if (local.getWorldLocation().distanceTo(BARTENDER_TILE) > 6) {
            issueWalk(BARTENDER_TILE);
            return travelDelay();
        }
        return talkToNpcContaining(local, "Bartender");
    }

    private int talkToHarlowForStake(IPlayer local) {
        if (local.getWorldLocation().distanceTo(HARLOW_TILE) > 6) {
            issueWalk(HARLOW_TILE);
            return travelDelay();
        }
        // OSRS: 1e Talk-to = Morgan-keten (eindigt met "koop drank"); 2e Talk-to met bier = "Here you go." → stake.
        // Iets kortere cooldown zodat het tweede gesprek snel start zodra het eerste dialoog dicht is.
        boolean beerButNoStake = inventoryHasFullBeer() && !inventoryHasStake();
        return talkToNpcContaining(local, "Dr Harlow", beerButNoStake ? 380L : 900L);
    }

    /**
     * FIX #13: Echte hammer-aankoop. Loopt naar shop, opent trade, koopt 1 hammer
     * via right-click "Buy 1" op het hammer-item in de shop-widget.
     */
    private int buyHammer(IPlayer local) {
        if (local.getWorldLocation().distanceTo(VARROCK_GEN_STORE) > 8) {
            issueWalk(VARROCK_GEN_STORE);
            return travelDelay();
        }

        // Shop al open?
        IWidget shopRoot = Widgets.get(SHOP_WIDGET_GROUP, 0);
        if (shopRoot != null && !shopRoot.isHidden()) {
            return buyHammerFromShopWidget();
        }

        if (!clickCooldownOk(lastNpcMs)) {
            return antiBan.varyDelay(randomDelay(400, 800));
        }
        INPC seller = NPCs.getNearest(n -> n != null && n.getName() != null
                && (n.getName().contains("Shop keeper") || n.getName().contains("Shop assistant")));
        if (seller == null) {
            paint.setLastAntiBanAction("Quest: winkel-NPC zoeken…");
            return antiBan.varyDelay(randomDelay(1000, 1800));
        }
        if (local.getWorldLocation().distanceTo(seller.getWorldLocation()) > 6) {
            issueWalk(seller.getWorldLocation());
            return travelDelay();
        }
        if (seller.hasAction("Trade")) {
            seller.interact("Trade");
        } else {
            seller.interact(0);
        }
        lastNpcMs = System.currentTimeMillis();
        DebugLog.log("QUEST", "Trade met " + seller.getName());
        return antiBan.varyDelay(randomDelay(1500, 2400));
    }

    private int buyHammerFromShopWidget() {
        long now = System.currentTimeMillis();
        if (now - lastShopMs < 800) {
            return antiBan.varyDelay(randomDelay(300, 600));
        }
        // Loop door alle children van de shop-widget en zoek hammer
        IWidget shopRoot = Widgets.get(SHOP_WIDGET_GROUP, 16); // items container (vaak child 16)
        IWidget[] children = getWidgetChildren(shopRoot);
        if (children == null || children.length == 0) {
            // Fallback: alle child-widgets van groep 300 doorlopen
            for (int childId = 0; childId < 80; childId++) {
                IWidget w = Widgets.get(SHOP_WIDGET_GROUP, childId);
                if (w == null) continue;
                IWidget[] sub = getWidgetChildren(w);
                if (sub == null) continue;
                Integer delay = tryBuyHammerInChildren(sub);
                if (delay != null) return delay;
            }
            DebugLog.log("QUEST", "Shop open maar Hammer niet gevonden");
            return antiBan.varyDelay(randomDelay(800, 1300));
        }
        Integer delay = tryBuyHammerInChildren(children);
        if (delay != null) return delay;
        DebugLog.log("QUEST", "Hammer niet in shop-children gevonden");
        return antiBan.varyDelay(randomDelay(800, 1300));
    }

    private Integer tryBuyHammerInChildren(IWidget[] children) {
        for (IWidget item : children) {
            if (item == null) continue;
            String name = item.getName();
            if (name == null) continue;
            String clean = Text.removeTags(name).toLowerCase(Locale.ROOT);
            if (clean.contains("hammer")) {
                if (item.hasAction("Buy 1")) {
                    item.interact("Buy 1");
                } else if (item.hasAction("Buy")) {
                    item.interact("Buy");
                } else {
                    item.interact(0);
                }
                lastShopMs = System.currentTimeMillis();
                DebugLog.log("QUEST", "Hammer gekocht uit shop-widget");
                return antiBan.varyDelay(randomDelay(1200, 1900));
            }
        }
        return null;
    }

    /**
     * Buiten: tegel vóór deur → "Open Large door" → naar trap.
     * Al binnen (noordelijk van de voordeur): nooit terug naar voordeur — alleen naar de trap lopen.
     */
    private int walkToManor(IPlayer local) {
        WorldPoint me = local.getWorldLocation();
        if (manorBeganeGrondBinnen(me)) {
            if (me.distanceTo(MANOR_STAIRS_DOWN_TILE) > 4) {
                issueWalk(MANOR_STAIRS_DOWN_TILE);
                paint.setCurrentStatus("Quest: Manor — naar trap (kelder)");
                return travelDelay();
            }
            return antiBan.varyDelay(randomDelay(300, 600));
        }
        if (me.distanceTo(MANOR_DOOR_STAND_TILE) > 4) {
            issueWalk(MANOR_DOOR_STAND_TILE);
            return travelDelay();
        }
        if (tryOpenManorLargeDoor(local)) {
            lastObjMs = System.currentTimeMillis();
            DebugLog.log("QUEST", "Manor: Large door geopend");
            return antiBan.varyDelay(randomDelay(900, 1500));
        }
        if (me.distanceTo(MANOR_STAIRS_DOWN_TILE) > 4) {
            issueWalk(MANOR_STAIRS_DOWN_TILE);
            return travelDelay();
        }
        return antiBan.varyDelay(randomDelay(450, 800));
    }

    /** Begane grond binnen Draynor Manor (niet op de binnenplaats ten zuiden van de grote deur). */
    private static boolean manorBeganeGrondBinnen(WorldPoint me) {
        return me.getPlane() == 0 && me.getY() >= MANOR_INTERIOR_MIN_Y;
    }

    /** Alleen nog voor generieke deur-helper / compat; cluster rond manor-ingang + trap. */
    private static boolean nearDraynorManorForDoor(IPlayer local) {
        WorldPoint me = local.getWorldLocation();
        return me.distanceTo(MANOR_DOOR_STAND_TILE) <= 14
                || me.distanceTo(MANOR_LARGE_DOOR_TILE) <= 14
                || me.distanceTo(MANOR_STAIRS_DOWN_TILE) <= 14;
    }

    /**
     * "Open Large door" op vaste tegel (geen willekeurige deuren onderweg).
     */
    private boolean tryOpenManorLargeDoor(IPlayer local) {
        if (!clickCooldownOk(lastObjMs)) {
            return false;
        }
        if (manorBeganeGrondBinnen(local.getWorldLocation())) {
            return false;
        }
        if (local.getWorldLocation().distanceTo(MANOR_LARGE_DOOR_TILE) > 9) {
            return false;
        }
        ITileObject door = TileObjects.getNearest(obj -> {
            if (obj == null || obj.getName() == null) {
                return false;
            }
            if (obj.getWorldLocation().distanceTo(MANOR_LARGE_DOOR_TILE) > 5) {
                return false;
            }
            String n = obj.getName().toLowerCase(Locale.ROOT);
            if (!n.contains("door")) {
                return false;
            }
            return obj.hasAction("Open") || obj.hasAction("Handle");
        });
        if (door == null) {
            return false;
        }
        if (local.getWorldLocation().distanceTo(door.getWorldLocation()) > 9) {
            return false;
        }
        if (door.hasAction("Open")) {
            door.interact("Open");
        } else {
            door.interact("Handle");
        }
        return true;
    }

    private static boolean nearMorganHouseForDoor(IPlayer local) {
        return local.getWorldLocation().distanceTo(MORGAN_TILE) <= 12;
    }

    private static boolean insideMorganHouse(WorldPoint p) {
        if (p == null || p.getPlane() != 0) {
            return false;
        }
        // Iets ruimer dan 2 tegels: drempel na deur / pathfinder-stop valt anders buiten "binnen".
        if (p.distanceTo(MORGAN_HOUSE_INSIDE_TILE) <= 3) {
            return true;
        }
        return p.distanceTo(MORGAN_TILE) <= 1;
    }

    /**
     * Kelder: trap op 3115,3357 — "Walk-down Stairs" (geen hasAction in zoekfilter: SDK kan acties nog niet tonen).
     */
    private int descendToBasement(IPlayer local) {
        if (!clickCooldownOk(lastObjMs)) {
            return antiBan.varyDelay(randomDelay(400, 700));
        }
        if (local.getWorldLocation().distanceTo(MANOR_STAIRS_DOWN_TILE) > 3) {
            issueWalk(MANOR_STAIRS_DOWN_TILE);
            paint.setCurrentStatus("Quest: Manor — dichter bij trap");
            return travelDelay();
        }

        ITileObject stairs = findManorStairsDownObject();
        if (stairs != null) {
            if (local.getWorldLocation().distanceTo(stairs.getWorldLocation()) > 3) {
                issueWalk(stairs.getWorldLocation());
                paint.setCurrentStatus("Quest: Manor — naar trap object");
                return travelDelay();
            }
            interactManorStairsWalkDown(stairs);
            lastObjMs = System.currentTimeMillis();
            return antiBan.varyDelay(randomDelay(3200, 5000));
        }

        ITileObject trapdoor = TileObjects.getNearest(obj ->
                obj != null && obj.getName() != null
                        && obj.getWorldLocation().distanceTo(MANOR_STAIRS_DOWN_TILE) <= 12
                        && obj.getName().equalsIgnoreCase("Trapdoor"));

        if (trapdoor != null) {
            if (local.getWorldLocation().distanceTo(trapdoor.getWorldLocation()) > 3) {
                issueWalk(trapdoor.getWorldLocation());
                return travelDelay();
            }
            if (trapdoor.hasAction("Open")) {
                trapdoor.interact("Open");
                lastObjMs = System.currentTimeMillis();
                return antiBan.varyDelay(randomDelay(1500, 2200));
            }
            if (trapdoor.hasAction("Climb-down")) {
                trapdoor.interact("Climb-down");
            } else {
                trapdoor.interact(0);
            }
            lastObjMs = System.currentTimeMillis();
            DebugLog.log("QUEST", "Manor trapdoor fallback");
            return antiBan.varyDelay(randomDelay(3200, 5000));
        }

        DebugLog.log("QUEST", "Manor: geen trap gevonden — naar anker-tegel");
        issueWalk(MANOR_STAIRS_DOWN_TILE);
        return travelDelay();
    }

    /**
     * Keldertrap: alleen object-ID 2616 (RuneLite), dicht bij anker — geen losse "stair"-naam
     * (dan pakt getNearest soms een andere trap in het manor).
     */
    private ITileObject findManorStairsDownObject() {
        ITileObject byId = TileObjects.getNearest(obj ->
                obj != null
                        && obj.getId() == MANOR_STAIRS_DOWN_OBJECT_ID
                        && obj.getWorldLocation().getPlane() == 0
                        && obj.getWorldLocation().distanceTo(MANOR_STAIRS_DOWN_TILE) <= 24);
        if (byId != null) {
            return byId;
        }
        ITileObject loose = TileObjects.getNearest(obj ->
                obj != null
                        && obj.getId() == MANOR_STAIRS_DOWN_OBJECT_ID
                        && obj.getWorldLocation().getPlane() == 0);
        if (loose != null && loose.getWorldLocation().distanceTo(MANOR_STAIRS_DOWN_TILE) <= 40) {
            DebugLog.log("QUEST", "Manor trap: 2616 gevonden (brede radius)");
            return loose;
        }
        DebugLog.log("QUEST", "Manor trap: object 2616 niet in scene — loop dichterbij / wacht laden");
        return null;
    }

    /**
     * Storm geeft vaak geen hasAction voor "Walk-down"; interact(String) is dan een no-op.
     * Eerst expliciete acties, anders eerste menu-optie ({@code interact(0)}).
     */
    private void interactManorStairsWalkDown(ITileObject stairs) {
        paint.setCurrentStatus("Quest: Manor — trap naar beneden");
        String rawName = stairs.getName();
        DebugLog.log("QUEST", "Manor trap klik: id=" + stairs.getId() + " name="
                + (rawName != null ? Text.removeTags(rawName) : "?"));

        if (stairs.hasAction("Walk-down")) {
            stairs.interact("Walk-down");
            DebugLog.log("QUEST", "Manor: Walk-down");
            return;
        }
        if (stairs.hasAction("Climb-down")) {
            stairs.interact("Climb-down");
            DebugLog.log("QUEST", "Manor: Climb-down");
            return;
        }
        stairs.interact(0);
        DebugLog.log("QUEST", "Manor: trap interact(0) — eerste menu-optie (Walk-down)");
    }

    private int openCoffinAndKill(IPlayer local) {
        boolean mageMode = config.combatStyle() == CombatBotConfig.ImpsCombatStyle.MAGE;
        boolean useMagic = mageMode && hasRunesForConfiguredMageSpell();
        if (mageMode && !useMagic) {
            long now = System.currentTimeMillis();
            if (now - lastMeleeEquipMs >= 900 && tryEquipBestMeleeFromInventory()) {
                lastMeleeEquipMs = now;
                DebugLog.log("QUEST", "Geen runes voor mage-spell → beste melee uit inventory");
                paint.setCurrentStatus("Quest: Count — melee (geen runes)");
                return antiBan.varyDelay(randomDelay(600, 1100));
            }
        } else if (useMagic) {
            ensureMagicAutocast();
        }

        INPC count = NPCs.getNearest(n -> n != null && n.getName() != null
                && n.getName().contains("Count Draynor")
                && !n.isDead());

        if (count != null) {
            attackedCount = true;
            coffinOpenMs = 0; // reset coffin-failsafe
            if (local.getWorldLocation().distanceTo(count.getWorldLocation()) > 10) {
                issueWalk(count.getWorldLocation());
                return travelDelay();
            }
            if (InteractionThrottle.shouldWaitAfterGlobalInteraction(local)) {
                paint.setCurrentStatus("Quest: wacht op Count-interactie…");
                return antiBan.varyDelay(randomDelay(500, 900));
            }
            if (local.getInteracting() == null) {
                if (count.hasAction("Attack")) {
                    count.interact("Attack");
                } else {
                    count.interact(0);
                }
                InteractionThrottle.markGlobalInteraction();
                DebugLog.log("QUEST", "Attack Count Draynor");
                return antiBan.varyDelay(randomDelay(800, 1400));
            }
            return antiBan.varyDelay(randomDelay(600, 1100));
        }

        // FIX #9: Failsafe — als coffin lang open is en geen Count, pas dan opnieuw klikken.
        if (coffinOpenMs > 0 && System.currentTimeMillis() - coffinOpenMs > 90_000L
                && !recentInteractionInProgress(local, lastCoffinInteractMs)) {
            DebugLog.log("QUEST", "Failsafe: Count niet gespawnt, coffin opnieuw");
            coffinOpenMs = 0;
            attackedCount = false;
            coffinOpenedOnce = false;
        }

        // Coffin al gebruikt: geen tweede Open/Search — wacht op Count of op varp 3 (quest klaar).
        if (coffinOpenedOnce) {
            if (attackedCount) {
                paint.setCurrentStatus("Quest: Count verslagen — wachten op update");
                return antiBan.varyDelay(randomDelay(1000, 2000));
            }
            if (local.getWorldLocation().distanceTo(COFFIN_TILE) > 8) {
                issueWalk(COFFIN_TILE);
                return travelDelay();
            }
            paint.setCurrentStatus("Quest: wacht op Count…");
            return antiBan.varyDelay(randomDelay(600, 1200));
        }

        // Eerste keer: naar coffin en één keer Open (of Search als al open)
        if (local.getWorldLocation().distanceTo(COFFIN_TILE) > 6) {
            issueWalk(COFFIN_TILE);
            return travelDelay();
        }
        if (!clickCooldownOk(lastObjMs)) {
            return antiBan.varyDelay(randomDelay(400, 700));
        }
        if (recentInteractionInProgress(local, lastCoffinInteractMs)
                || InteractionThrottle.shouldWaitAfterGlobalInteraction(local)) {
            paint.setCurrentStatus("Quest: wacht op coffin-interactie…");
            return antiBan.varyDelay(randomDelay(600, 1100));
        }
        ITileObject coffin = TileObjects.getNearest(obj ->
                obj != null && obj.getName() != null
                        && obj.getName().toLowerCase(Locale.ROOT).contains("coffin")
                        && (obj.hasAction("Open") || obj.hasAction("Search")));
        if (coffin == null) {
            return antiBan.varyDelay(randomDelay(800, 1400));
        }
        coffinOpenedOnce = true;
        if (coffin.hasAction("Open")) {
            coffin.interact("Open");
        } else {
            coffin.interact("Search");
        }
        lastObjMs = System.currentTimeMillis();
        lastCoffinInteractMs = lastObjMs;
        InteractionThrottle.markGlobalInteraction();
        coffinOpenMs = System.currentTimeMillis();
        attackedCount = false;
        DebugLog.log("QUEST", "Coffin geopend (1x)");
        return antiBan.varyDelay(randomDelay(2200, 3600));
    }

    // ===================================================================
    // Helpers
    // ===================================================================

    private int readQuestVar() {
        try {
            return Vars.getVarp(VARP_VAMPYRE_SLAYER);
        } catch (Exception e) {
            DebugLog.log("QUEST", "Vars.getVarp fout: " + e.getMessage());
            return 0;
        }
    }

    private int talkToNpcContaining(IPlayer local, String namePart) {
        return talkToNpcContaining(local, namePart, 900L);
    }

    /**
     * @param minMsSinceLastNpc minimaal ms sinds vorige NPC-klik (Dr Harlow 2e gesprek gebruikt korter interval).
     */
    private int talkToNpcContaining(IPlayer local, String namePart, long minMsSinceLastNpc) {
        if (System.currentTimeMillis() - lastNpcMs < minMsSinceLastNpc) {
            return antiBan.varyDelay(randomDelay(400, 800));
        }
        if (recentInteractionInProgress(local, lastNpcMs)) {
            paint.setCurrentStatus("Quest: wacht op NPC-interactie…");
            return antiBan.varyDelay(randomDelay(500, 900));
        }
        String key = namePart.toLowerCase(Locale.ROOT);
        INPC npc = NPCs.getNearest(n -> n != null && n.getName() != null
                && n.getName().toLowerCase(Locale.ROOT).contains(key));
        if (npc == null) {
            paint.setLastAntiBanAction("Quest: zoek " + namePart + "…");
            return antiBan.varyDelay(randomDelay(800, 1400));
        }
        if (local.getWorldLocation().distanceTo(npc.getWorldLocation()) > 8) {
            issueWalk(npc.getWorldLocation());
            return travelDelay();
        }
        if (npc.hasAction("Talk-to")) {
            npc.interact("Talk-to");
        } else {
            npc.interact(0);
        }
        lastNpcMs = System.currentTimeMillis();
        DebugLog.log("QUEST", "Talk-to " + npc.getName());
        return antiBan.varyDelay(randomDelay(1200, 2200));
    }

    private static boolean inventoryHasGarlic() {
        return Inventory.contains(i -> {
            if (i == null || i.getName() == null) {
                return false;
            }
            String n = Text.removeTags(i.getName()).trim();
            return n.equalsIgnoreCase("Garlic");
        });
    }

    private static boolean inventoryHasFullBeer() {
        return Inventory.contains(i -> {
            if (i == null || i.getName() == null) {
                return false;
            }
            String n = Text.removeTags(i.getName()).trim();
            return n.equalsIgnoreCase("Beer");
        });
    }

    private static boolean inventoryHasStake() {
        return Inventory.contains(i -> {
            if (i == null || i.getName() == null) {
                return false;
            }
            String n = Text.removeTags(i.getName()).trim();
            return n.equalsIgnoreCase("Stake");
        });
    }

    /**
     * Coins in inventory (alle stacks, noted-aware). {@link Inventory#getCount(String)} geeft hier soms 0 terug
     * terwijl er wél een stack ligt — zelfde aanpak als {@link #totalItemCountInvPlusEq} / ImpsHandler.
     */
    private int inventoryCoinCount() {
        return totalItemCountInvPlusEq("Coins");
    }

    private int interactStaircase(IPlayer local, String action, WorldPoint hintTile) {
        return interactStaircase(local, action, hintTile, -1);
    }

    /**
     * @param maxDistFromHint als &gt;= 0: alleen trappen binnen deze Chebyshev/WorldPoint-afstand van {@code hintTile}
     *                        (nodig bij Morgan — anders pakt getNearest een trap in een ander Draynor-huis).
     */
    private int interactStaircase(IPlayer local, String action, WorldPoint hintTile, int maxDistFromHint) {
        if (!clickCooldownOk(lastObjMs)) {
            return antiBan.varyDelay(randomDelay(400, 700));
        }
        if (recentInteractionInProgress(local, lastObjMs)) {
            paint.setCurrentStatus("Quest: wacht op object-interactie…");
            return antiBan.varyDelay(randomDelay(500, 900));
        }
        final int maxD = maxDistFromHint;
        // Dubbele vangrail: nooit Climb-up nabij verkeerde trap (bijv. Blue Moon Inn) als hint Morgan is.
        if ("Climb-up".equals(action) && maxD >= 0
                && local.getWorldLocation().distanceTo(hintTile) > GARLIC_MORGAN_AREA_MAX_DIST + 6) {
            issueWalk(hintTile);
            DebugLog.log("QUEST", "Staircase Climb-up uitgesteld — eerst naar hint-tile");
            return travelDelay();
        }
        ITileObject stairs = TileObjects.getNearest(obj -> {
            if (obj == null || obj.getName() == null) {
                return false;
            }
            if (!obj.getName().toLowerCase(Locale.ROOT).contains("staircase") || !obj.hasAction(action)) {
                return false;
            }
            if (maxD >= 0 && obj.getWorldLocation().distanceTo(hintTile) > maxD) {
                return false;
            }
            return true;
        });
        if (stairs == null) {
            if (local.getWorldLocation().distanceTo(hintTile) > 3) {
                issueWalk(hintTile);
                return travelDelay();
            }
            return antiBan.varyDelay(randomDelay(900, 1500));
        }
        if (local.getWorldLocation().distanceTo(stairs.getWorldLocation()) > 4) {
            issueWalk(stairs.getWorldLocation());
            return travelDelay();
        }
        stairs.interact(action);
        lastObjMs = System.currentTimeMillis();
        DebugLog.log("QUEST", "Staircase " + action);
        return antiBan.varyDelay(randomDelay(2200, 3600));
    }

    private boolean tryClickNearbyDoor(IPlayer local) {
        long now = System.currentTimeMillis();
        pruneDoorQueue(now);
        if (recentInteractionInProgress(local, lastObjMs)) {
            return false;
        }
        if (!recentDoorMs.isEmpty() && now - recentDoorMs.peekLast() < 2200) {
            return false;
        }
        ITileObject door = TileObjects.getNearest(obj -> {
            if (obj == null || obj.getName() == null) {
                return false;
            }
            String n = obj.getName().toLowerCase(Locale.ROOT);
            if (!n.contains("door") && !n.contains("gate")) {
                return false;
            }
            return obj.hasAction("Open") || obj.hasAction("Handle");
        });
        if (door != null && local.getWorldLocation().distanceTo(door.getWorldLocation()) <= 6) {
            if (door.hasAction("Open")) {
                door.interact("Open");
            } else {
                door.interact("Handle");
            }
            recentDoorMs.addLast(now);
            return true;
        }
        return false;
    }

    private boolean recentInteractionInProgress(IPlayer local, long lastInteractionMs) {
        return InteractionThrottle.shouldWaitAfterInteraction(local, lastInteractionMs);
    }

    private int inventoryFoodCount() {
        int count = 0;
        for (IInventoryItem item : Inventory.getAll()) {
            if (item != null && item.getName() != null && isQuestFoodName(item.getName())) {
                count += Math.max(1, item.getQuantity());
            }
        }
        return count;
    }

    private int tryEatAtOrBelowFiveHp() {
        int hp = currentHitpointsEstimate();
        if (hp > 5 && !shouldEatByHpPercent()) {
            return 0;
        }
        long now = System.currentTimeMillis();
        if (now - lastEatMs < 1_200L) {
            return antiBan.varyDelay(randomDelay(300, 600));
        }
        IInventoryItem food = Inventory.getFirst(item -> {
            if (item == null || item.getName() == null || item.getActions() == null) {
                return item != null && item.getName() != null && isQuestFoodName(item.getName());
            }
            for (String action : item.getActions()) {
                if (action != null && (action.equalsIgnoreCase("Eat") || action.equalsIgnoreCase("Drink"))) {
                    return true;
                }
            }
            return isQuestFoodName(item.getName());
        });
        if (food == null) {
            return 0;
        }
        String action = food.hasAction("Eat") ? "Eat" : (food.hasAction("Drink") ? "Drink" : "Eat");
        food.interact(action);
        InteractionThrottle.markGlobalInteraction();
        lastEatMs = now;
        paint.setCurrentStatus("Quest: nood-eat bij " + hp + " HP");
        DebugLog.log("QUEST", "Nood-eat bij HP<=" + hp + ": " + food.getName());
        return antiBan.varyDelay(randomDelay(900, 1400));
    }

    private int currentHitpointsEstimate() {
        try {
            double pct = Combat.getHealthPercent();
            int lvl = Math.max(1, Skills.getLevel(Skill.HITPOINTS));
            int cur = (int) Math.floor((pct * lvl) / 100.0);
            if (pct > 0 && cur < 1) {
                cur = 1;
            }
            return Math.max(0, cur);
        } catch (Throwable t) {
            return 99;
        }
    }

    private boolean shouldEatByHpPercent() {
        try {
            double hpPct = Combat.getHealthPercent();
            int maxHp = Math.max(1, Skills.getLevel(Skill.HITPOINTS));
            double threshold = 5.0 / maxHp;
            if (hpPct > 1.0) {
                threshold *= 100.0;
            }
            return hpPct <= threshold;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private String firstAvailableQuestFoodInBank() {
        for (String food : QUEST_FOOD_PRIORITY) {
            try {
                if (Bank.contains(food)) {
                    return food;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private boolean isQuestFoodName(String name) {
        if (name == null) {
            return false;
        }
        String clean = Text.removeTags(name).trim();
        for (String food : QUEST_FOOD_PRIORITY) {
            if (food.equalsIgnoreCase(clean)) {
                return true;
            }
        }
        return false;
    }

    private void pruneDoorQueue(long now) {
        while (!recentDoorMs.isEmpty() && now - recentDoorMs.peekFirst() > 8000) {
            recentDoorMs.pollFirst();
        }
    }

    /** Zelfde idee als Imps: alleen equipped staff met element in de naam. */
    private boolean hasStaffWithElement(String element) {
        if (element == null || element.isEmpty()) {
            return false;
        }
        String el = element.toLowerCase(Locale.ROOT);
        return Equipment.contains(item -> item != null && item.getName() != null
                && item.getName().toLowerCase(Locale.ROOT).contains(el)
                && item.getName().toLowerCase(Locale.ROOT).contains("staff"));
    }

    private int totalItemCountInvPlusEq(String itemName) {
        if (itemName == null || itemName.isEmpty()) {
            return 0;
        }
        int count = 0;
        try {
            count += Inventory.getCount(true, itemName);
        } catch (Exception e) {
            try {
                count += Inventory.getCount(itemName);
            } catch (Exception e2) {
                try {
                    for (IInventoryItem it : Inventory.getAll()) {
                        if (it != null && it.getName() != null && it.getName().equalsIgnoreCase(itemName)) {
                            count += it.getQuantity();
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
        try {
            var eq = Equipment.getAll(i -> i != null && i.getName() != null && i.getName().equalsIgnoreCase(itemName));
            if (eq != null) {
                for (var e : eq) {
                    if (e != null) {
                        count += e.getQuantity();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return count;
    }

    /**
     * Genoeg runes voor de geconfigureerde {@link CombatBotConfig.ImpsMageSpell} (incl. staff die elemental/air dekt).
     */
    private boolean hasRunesForConfiguredMageSpell() {
        CombatBotConfig.ImpsMageSpell spell = config.impsMageSpell();
        if (spell == null) {
            return false;
        }
        String elementalRune = spell.getElementalRune();
        String elementKey = elementalRune.toLowerCase(Locale.ROOT).replace(" rune", "");
        if (!hasStaffWithElement(elementKey)
                && totalItemCountInvPlusEq(elementalRune) < MIN_RUNES_COUNT_FIGHT) {
            return false;
        }
        if (totalItemCountInvPlusEq(spell.getCatalystRune()) < MIN_RUNES_COUNT_FIGHT) {
            return false;
        }
        if (spell.needsAirRune() && !hasStaffWithElement("air")
                && totalItemCountInvPlusEq("Air rune") < MIN_RUNES_COUNT_FIGHT) {
            return false;
        }
        return true;
    }

    private boolean tryEquipBestMeleeFromInventory() {
        IInventoryItem best = null;
        int bestScore = -1;
        try {
            for (IInventoryItem it : Inventory.getAll()) {
                if (it == null || it.getName() == null) {
                    continue;
                }
                String n = Text.removeTags(it.getName()).toLowerCase(Locale.ROOT);
                if (!isMeleeWeaponCandidate(n)) {
                    continue;
                }
                if (wieldVerb(it) == null) {
                    continue;
                }
                int sc = scoreMeleeWeaponFromName(n);
                if (sc > bestScore) {
                    bestScore = sc;
                    best = it;
                }
            }
        } catch (Exception e) {
            DebugLog.log("QUEST", "tryEquipBestMeleeFromInventory: " + e.getMessage());
            return false;
        }
        if (best == null || bestScore < 0) {
            return false;
        }
        String act = wieldVerb(best);
        if (act == null) {
            return false;
        }
        best.interact(act);
        return true;
    }

    private static String wieldVerb(IInventoryItem it) {
        if (it.hasAction("Wield")) {
            return "Wield";
        }
        if (it.hasAction("Wear")) {
            return "Wear";
        }
        return null;
    }

    private static boolean isMeleeWeaponCandidate(String n) {
        if (n.contains("staff") || n.contains("bow") || n.contains("crossbow") || n.contains("salamander")) {
            return false;
        }
        if (n.contains("pickaxe") || "hammer".equals(n)) {
            return false;
        }
        if (n.contains(" dart") || n.endsWith(" dart") || n.contains("throwing")) {
            return false;
        }
        return n.contains("scimitar") || n.contains(" sword") || n.endsWith(" sword") || n.contains("longsword")
                || n.contains("dagger") || n.contains("mace") || n.contains("flail")
                || n.contains("battleaxe") || (n.contains("axe") && !n.contains("pickaxe"))
                || n.contains("halberd") || n.contains("spear") || n.contains("hasta")
                || n.contains("whip") || n.contains("claw") || n.contains("warhammer");
    }

    private static int scoreMeleeWeaponFromName(String n) {
        int tier;
        if (n.contains("abyssal whip") || n.contains("abyssal bludgeon")) {
            tier = 950;
        } else if (n.contains("dragon")) {
            tier = 900;
        } else if (n.contains("rune")) {
            tier = 800;
        } else if (n.contains("granite")) {
            tier = 780;
        } else if (n.contains("adamant")) {
            tier = 700;
        } else if (n.contains("mithril")) {
            tier = 600;
        } else if (n.contains("black")) {
            tier = 550;
        } else if (n.contains("white")) {
            tier = 520;
        } else if (n.contains("steel")) {
            tier = 500;
        } else if (n.contains("iron")) {
            tier = 400;
        } else if (n.contains("bronze")) {
            tier = 300;
        } else {
            tier = 450;
        }
        int typeBonus = 0;
        if (n.contains("scimitar")) {
            typeBonus = 5;
        } else if (n.contains("whip")) {
            typeBonus = 8;
        } else if (n.contains(" sword") || n.endsWith(" sword")) {
            typeBonus = 4;
        }
        return tier + typeBonus;
    }

    private void ensureMagicAutocast() {
        try {
            CombatBotConfig.ImpsMageSpell spell = config.impsMageSpell();
            if (spell == null) {
                DebugLog.log("QUEST", "ensureMagicAutocast: spell == null, skip");
                return;
            }
            SpellBook.Standard std = spell.getStandardSpell();
            if (std == null) {
                DebugLog.log("QUEST", "ensureMagicAutocast: standard spell == null, skip");
                return;
            }
            if (!Magic.isAutoCasting(std)) {
                Magic.setAutoCast(std, false);
                DebugLog.log("QUEST", "Autocast ingesteld op " + std.name());
            }
        } catch (Exception e) {
            DebugLog.log("QUEST", "ensureMagicAutocast fout: " + e.getMessage());
        }
    }

    private void issueWalk(WorldPoint target) {
        long now = System.currentTimeMillis();
        if (now - lastWalkMs < 900) {
            return;
        }
        lastWalkMs = now;
        MovementHelper.walkTo(target);
    }

    /** Geen RSN-tegel-shift: kleine huisjes + deur-lijn verkeerd bij ±1 random offset. */
    private void issueWalkToMorganHouseInside() {
        long now = System.currentTimeMillis();
        if (now - lastWalkMs < 900) {
            return;
        }
        lastWalkMs = now;
        MovementHelper.walkToExact(MORGAN_HOUSE_INSIDE_TILE);
    }

    private int travelDelay() {
        return antiBan.varyDelay(randomDelay(
                Math.max(400, config.travelPostClickDelayMin()),
                Math.max(600, config.travelPostClickDelayMax())));
    }

    private boolean clickCooldownOk(long last) {
        return System.currentTimeMillis() - last > 900;
    }

    private int randomDelay(int min, int max) {
        if (max <= min) {
            return min;
        }
        return min + random.nextInt(max - min);
    }

    private IWidget[] getWidgetChildren(IWidget parent) {
        if (parent == null) return null;
        IWidget[] children = parent.getDynamicChildren();
        if (children != null && children.length > 0) return children;
        children = parent.getChildren();
        if (children != null && children.length > 0) return children;
        return parent.getStaticChildren();
    }

    @SuppressWarnings("unused")
    private String localRsn(IPlayer local) {
        if (local.getName() == null) {
            return null;
        }
        return Text.removeTags(local.getName());
    }
}
