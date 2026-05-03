package com.combatbot;

import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.storm.api.domain.actors.INPC;
import net.storm.api.domain.actors.IPlayer;
import net.storm.api.domain.items.IInventoryItem;
import net.storm.api.domain.tiles.ITileItem;
import net.storm.api.domain.tiles.ITileObject;
import net.storm.api.movement.pathfinder.model.BankLocation;
import net.storm.sdk.entities.NPCs;
import net.storm.sdk.entities.Players;
import net.storm.sdk.entities.TileItems;
import net.storm.sdk.entities.TileObjects;
import net.storm.sdk.game.Combat;
import net.storm.sdk.game.Prices;
import net.storm.sdk.game.Skills;
import net.storm.sdk.items.Bank;
import net.storm.sdk.magic.Magic;
import net.storm.api.magic.SpellBook;
import net.storm.sdk.items.Equipment;
import net.storm.sdk.items.GrandExchange;
import net.storm.sdk.items.Inventory;
import net.storm.sdk.movement.Movement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * GiantsHandler — Hill Giants in Edgeville Dungeon.
 *
 * Twee ingangen:
 * 1. Varrock-ingang (shed): vereist Brass key om de deur te openen. Sneller.
 * 2. Edgeville-ingang (trapdoor): geen key nodig, langere route.
 *
 * Brass key ophalen:
 * - Check bank/inventory → als aanwezig, klaar.
 * - Probeer bij GE te kopen (als coins beschikbaar).
 * - Fallback: ga via Edgeville trapdoor naar brass key ground spawn (3131, 9862, 0).
 *   Als key er niet ligt: climb ladder omhoog, wacht 3 min (world hop), ga terug.
 *
 * Loot: Big bones, Limpwurt root + configureerbare lijst.
 */
public class GiantsHandler {

    private final CombatBotConfig config;
    private final AntiBan antiBan;
    private final CombatBotPaint paint;
    private final Random random = new Random();

    /** Excluded tiles (COMBAT) — zelfde skill als algemene combat-markers. */
    private TileMarkerManager tileMarkerManager;

    // ===================== LOCATIES =====================

    /** Hill Giants vecht-area in Edgeville Dungeon (plane 0, underground). */
    // Center/zone die je in-game HUD laat zien (anders lijkt bot "ver weg" giants aan te vallen).
    private static final WorldPoint HILL_GIANTS_CENTER = new WorldPoint(3115, 9837, 0);
    // >22 zodat hij binnen de echte hunt/battle zone blijft.
    private static final int HILL_GIANTS_RADIUS = 24;

    /** Pre-trapdoor waypoint (bovengronds). */
    private static final WorldPoint EDGEVILLE_PRE_TRAPDOOR = new WorldPoint(3094, 3471, 0);
    /** Edgeville trapdoor (bovengronds). */
    private static final WorldPoint EDGEVILLE_TRAPDOOR = new WorldPoint(3097, 3468, 0);
    /** Edgeville trapdoor object ID. */
    private static final int EDGEVILLE_TRAPDOOR_ID = 12342;
    /** Edgeville ladder onder (in dungeon). */
    private static final WorldPoint EDGEVILLE_LADDER_BELOW = new WorldPoint(3096, 9867, 0);

    /** Brass key ground spawn locatie in Edgeville Dungeon. */
    private static final WorldPoint BRASS_KEY_SPAWN = new WorldPoint(3131, 9862, 0);
    /** Brass key item ID (voor ground spawn). */
    private static final int BRASS_KEY_ITEM_ID = 983;
    /** Ladder bij brass key spawn om te hoppen (object ID 12441, tile 3116,9852,0). */
    private static final WorldPoint BRASS_KEY_LADDER_TILE = new WorldPoint(3116, 9852, 0);
    private static final int BRASS_KEY_LADDER_ID = 12441;
    /** Wachttijd als brass key niet op de grond ligt (3 minuten). */
    private static final long BRASS_KEY_WAIT_MS = 3 * 60 * 1000;

    /** Varrock shed (bovengronds) — brass key deur. */
    private static final WorldPoint VARROCK_SHED = new WorldPoint(3115, 3452, 0);
    /** Varrock shed ingang (in dungeon, na brass key deur). */
    private static final WorldPoint VARROCK_SHED_BELOW = new WorldPoint(3115, 9852, 0);

    /** Edgeville bank locatie. */
    private static final WorldPoint EDGEVILLE_BANK = new WorldPoint(3094, 3491, 0);

    /** Grand Exchange locatie voor brass key kopen. */
    private static final WorldPoint GE_LOCATION = new WorldPoint(3164, 3487, 0);
    /** Zoek-radius voor een bank dicht bij GE (zodat we niet per ongeluk naar Edgeville bank lopen). */
    private static final int GE_BANK_SEARCH_RADIUS = 25;
    // Voor Giants: bank bij GE (zodat we niet telkens naar Edgeville bank lopen).
    private static final WorldPoint GIANTS_BANK_WALK_POINT = GE_LOCATION;

    /** Brass key item naam (voor inventory-check). */
    private static final String BRASS_KEY = "Brass key";
    private static final int GENIE_LAMP_ITEM_ID = 2528;

    /** Items die we nooit deponeren — zoals Imps (gear, coins, key, runes, ammo, amulet). */
    private static final String[] GIANTS_KEEP_BASE = {
            "Coins", BRASS_KEY, "Amulet of power",
            "Mind rune", "Chaos rune", "Death rune", "Air rune", "Water rune", "Earth rune", "Fire rune",
            "Bronze arrow", "Iron arrow", "Steel arrow", "Mithril arrow", "Adamant arrow", "Rune arrow"
    };

    /** Dynamische keep-list voor Giants (zoals Imps getFullKeepList): spell-runes + gear. */
    private List<String> getGiantsKeepList() {
        List<String> keep = new ArrayList<>(Arrays.asList(GIANTS_KEEP_BASE));
        CombatBotConfig.ImpsCombatStyle style = config.giantsCombatStyle();
        if (style == CombatBotConfig.ImpsCombatStyle.MAGE) {
            CombatBotConfig.ImpsMageSpell spell = config.giantsMageSpell();
            if (!keep.contains(spell.getElementalRune())) keep.add(spell.getElementalRune());
            if (!keep.contains(spell.getCatalystRune())) keep.add(spell.getCatalystRune());
            if (spell.needsAirRune() && !keep.contains("Air rune")) keep.add("Air rune");
        }
        return keep;
    }

    /** Strikte bank keep-list voor Giants banking/gear prep. */
    private List<String> getGiantsStrictBankKeepNames() {
        List<String> keep = new ArrayList<>(getGiantsKeepList());
        if (!keep.contains("Coins")) keep.add("Coins");
        IInventoryItem lamp = Inventory.getFirst(item -> item != null && item.getId() == GENIE_LAMP_ITEM_ID);
        if (lamp != null && lamp.getName() != null && !lamp.getName().isEmpty()) {
            boolean hasLamp = false;
            for (String k : keep) {
                if (k.equalsIgnoreCase(lamp.getName())) {
                    hasLamp = true;
                    break;
                }
            }
            if (!hasLamp) keep.add(lamp.getName());
        }
        // Bewaar huidige food stacks; de rest mag naar bank.
        for (IInventoryItem item : Inventory.getAll()) {
            if (item != null && item.getName() != null && (item.hasAction("Eat") || item.hasAction("Drink"))) {
                boolean exists = false;
                for (String k : keep) {
                    if (k.equalsIgnoreCase(item.getName())) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) keep.add(item.getName());
            }
        }
        return keep;
    }

    private boolean hasGiantsStrictDepositItems() {
        List<String> keep = getGiantsStrictBankKeepNames();
        for (IInventoryItem item : Inventory.getAll()) {
            if (item == null || item.getName() == null) continue;
            boolean allowed = false;
            for (String k : keep) {
                if (k.equalsIgnoreCase(item.getName())) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) return true;
        }
        return false;
    }

    /** Of een item behouden moet worden (niet deponeren) — zoals Imps shouldKeepItem. */
    private boolean shouldKeepItemGiants(IInventoryItem item) {
        if (item == null || item.getName() == null) return false;
        if (item.getId() == GENIE_LAMP_ITEM_ID) return true;
        String name = item.getName();
        for (String keep : getGiantsKeepList()) {
            if (keep.equalsIgnoreCase(name)) return true;
        }
        if (item.hasAction("Eat") || item.hasAction("Drink")) return true;
        String lower = name.toLowerCase();
        if (lower.contains("scimitar") || lower.contains("sword") || lower.contains("dagger") || lower.contains("mace")
                || lower.contains("bow") || lower.contains("staff") || lower.contains("halberd")
                || lower.contains("axe") || lower.contains("longsword") || lower.contains("battleaxe") || lower.contains("warhammer"))
            return true;
        if (lower.contains("arrow")) return true;
        if (lower.endsWith("rune")) return true;
        return false;
    }

    // ===================== STATE =====================

    private enum State {
        CHECKING_LOCATION,
        BUYING_KEY_AT_GE,
        WALKING_TO_ENTRANCE,
        ENTERING_DUNGEON,
        /** Lopen naar brass key ground spawn in dungeon. */
        FETCHING_BRASS_KEY,
        WALKING_TO_GIANTS,
        FIGHTING,
        LOOTING,
        EATING,
        WALKING_TO_BANK,
        BANKING,
        RETURNING_TO_DUNGEON,
        POST_KILL_WAIT,
        BURYING_BONES,
        PAUSED_NO_FOOD
    }

    // Runtime state
    private boolean hasBrassKey = false;
    private boolean isInDungeon = false;
    private boolean gearPrepComplete = false;
    private boolean preparingGear = false;
    /** Na brass key ophalen (grond of GE): eerst naar bank voor food/gear */
    private boolean needsGearPrepAfterKey = false;

    // Brass key ground spawn state
    private boolean fetchingBrassKeyFromGround = false;
    private boolean climbedLadderForKeyWait = false;
    private long brassKeyWaitStart = 0;

    // Combat state
    private boolean targetWasAlive = false;
    private long lootWindowEnd = 0;
    private int lastAttackedNpcIndex = -1;
    private long lastAttackClickTime = 0;
    private static final long ATTACK_CLICK_COOLDOWN_MS = 1800;
    private long lastLootPickupTime = 0;
    private static final long LOOT_PICKUP_COOLDOWN_MS = 2400;
    private static final int POST_KILL_WAIT_MS = 1200;

    // Banking state
    private WorldPoint preBankPosition = null;
    private boolean walkingBackAfterBank = false;

    // GE state
    private int geBuyStep = 0;
    private long lastGeInteraction = 0;
    private static final long GE_INTERACTION_COOLDOWN = 1500;
    private boolean geCollectedExisting = false;
    private volatile boolean gePurchaseInProgress = false;

    // MAGE: autocast eenmalig per sessie (zoals Imps)
    private boolean autocastSet = false;

    // Burying state
    private boolean buryingBatchStarted = false;
    private long lastBuryActionMs = 0;
    private static final long BURY_ACTION_COOLDOWN_MS = 900;

    /** Laatste keer dat we inv-check hebben gelogd (bovengronds); throttle om spam te voorkomen. */
    private long lastInvCheckLogTime = 0;
    private static final long INV_CHECK_LOG_INTERVAL_MS = 20_000;

    // Kill tracking
    private int killsSinceLastLoot = 0;

    /** Panel-uitloggen: geen nieuwe Hill Giant aanvallen; wel loot. */
    private boolean suppressNewAttacksForLogout = false;

    /** Travel reclick (zelfde gedrag als Fishing/WC/Mining). */
    private long giantsTravelLastClick = 0;
    private long lastShedDoorClickMs = 0;
    private long lastShedTrapClickMs = 0;
    private long lastDungeonExitClickMs = 0;

    private static final List<String> KNOWN_FOOD_ACTIONS = Arrays.asList("Eat", "Drink");

    private static final String[] BONE_NAMES = {
            "Bones", "Big bones", "Babydragon bones", "Dragon bones"
    };

    private static final String[] DEFAULT_GIANTS_LOOT = {
            "Big bones", "Limpwurt root", "Nature rune", "Law rune", "Cosmic rune",
            "Death rune", "Body rune", "Mind rune", "Chaos rune",
            "Iron ore", "Coal", "Steel arrow", "Iron arrow",
            "Coins"
    };

    private void debug(String msg) {
        DebugLog.log("Giants", msg);
    }

    /** Lang lopen met periodieke nieuwe path-click (configureerbaar interval). */
    private int giantsTravelTo(WorldPoint dest, int waitMsIfNotReady) {
        if (dest == null) return waitMsIfNotReady;
        long now = System.currentTimeMillis();
        if (!TravelWalkHelper.reclickReady(giantsTravelLastClick, config)) {
            return waitMsIfNotReady;
        }
        MovementHelper.walkTo(dest);
        giantsTravelLastClick = now;
        return TravelWalkHelper.postClickDelayMs(config, random);
    }

    private void chatLog(String message) {
        try {
            net.runelite.api.Client rlClient = net.storm.sdk.game.Client.getClient().getWrapped();
            if (rlClient != null) {
                rlClient.addChatMessage(net.runelite.api.ChatMessageType.GAMEMESSAGE, "", "[Giants] " + message, null);
            }
        } catch (Exception e) {
            System.out.println("[GiantsChat] " + message);
        }
    }

    public GiantsHandler(CombatBotConfig config, AntiBan antiBan, CombatBotPaint paint) {
        this.config = config;
        this.antiBan = antiBan;
        this.paint = paint;
    }

    public void setTileMarkerManager(TileMarkerManager tileMarkerManager) {
        this.tileMarkerManager = tileMarkerManager;
    }

    private boolean isGroundLootTileExcluded(WorldPoint wp) {
        if (tileMarkerManager == null || wp == null) return false;
        return tileMarkerManager.isTileExcluded(CombatBotPlugin.ActiveSkill.COMBAT, wp);
    }

    /**
     * Zelfde regel als {@link CombatHandler}: NPC-footprint + 1 ring rond ZW-hoek.
     */
    private boolean isTileExcludedForNpc(WorldPoint swCorner, int npcSize) {
        if (tileMarkerManager == null || swCorner == null) return false;
        for (int dx = -1; dx <= npcSize; dx++) {
            for (int dy = -1; dy <= npcSize; dy++) {
                WorldPoint check = new WorldPoint(swCorner.getX() + dx, swCorner.getY() + dy, swCorner.getPlane());
                if (tileMarkerManager.isTileExcluded(CombatBotPlugin.ActiveSkill.COMBAT, check)) return true;
            }
        }
        return false;
    }

    private int getNpcSize(INPC npc) {
        try {
            if (npc != null && npc.getComposition() != null) {
                return npc.getComposition().getSize();
            }
        } catch (Exception ignored) {
        }
        return 1;
    }

    public void resetState() {
        hasBrassKey = false;
        isInDungeon = false;
        gearPrepComplete = false;
        preparingGear = false;
        needsGearPrepAfterKey = false;
        autocastSet = false;
        fetchingBrassKeyFromGround = false;
        climbedLadderForKeyWait = false;
        brassKeyWaitStart = 0;
        targetWasAlive = false;
        lootWindowEnd = 0;
        lastAttackedNpcIndex = -1;
        lastAttackClickTime = 0;
        lastLootPickupTime = 0;
        preBankPosition = null;
        walkingBackAfterBank = false;
        geBuyStep = 0;
        lastGeInteraction = 0;
        geCollectedExisting = false;
        gePurchaseInProgress = false;
        buryingBatchStarted = false;
        killsSinceLastLoot = 0;
        giantsTravelLastClick = 0;
        lastDungeonExitClickMs = 0;
    }

    public void startGearPreparation() {
        if (hasBrassKeyAnywhere()) {
            gearPrepComplete = true;
            preparingGear = false;
            debug("Brass key al aanwezig, geen gear prep nodig.");
            return;
        }
        preparingGear = true;
        gearPrepComplete = false;
        chatLog("Gear preparation gestart — brass key nodig.");
    }

    public int loop() {
        try {
            if (shouldAbortActions()) {
                return 300;
            }
            State state = determineState();
            switch (state) {
                case EATING:
                    paint.setCurrentStatus("🍖 [Giants] Eten...");
                    return handleEating();
                case BURYING_BONES:
                    paint.setCurrentStatus("🦴 [Giants] Bones begraven...");
                    return handleBuryingBones();
                case PAUSED_NO_FOOD:
                    paint.setCurrentStatus("⛔ [Giants] Geen food — naar bank");
                    return handleWalkToBank();
                case POST_KILL_WAIT:
                    paint.setCurrentStatus("💀 [Giants] Kill! Wachten op loot...");
                    return POST_KILL_WAIT_MS;
                case LOOTING:
                    paint.setCurrentStatus("💰 [Giants] Loot oppakken");
                    return handleLooting();
                case BUYING_KEY_AT_GE:
                    paint.setCurrentStatus("🔑 [Giants] Brass key kopen bij GE...");
                    return handleBuyingKeyAtGe();
                case FETCHING_BRASS_KEY:
                    paint.setCurrentStatus("🔑 [Giants] Brass key ophalen van grond...");
                    return handleFetchingBrassKey();
                case WALKING_TO_ENTRANCE:
                    paint.setCurrentStatus("→ [Giants] Lopen naar dungeon ingang...");
                    return handleWalkToEntrance();
                case ENTERING_DUNGEON:
                    paint.setCurrentStatus("🚪 [Giants] Dungeon betreden...");
                    return handleEnteringDungeon();
                case WALKING_TO_GIANTS:
                    paint.setCurrentStatus("→ [Giants] Lopen naar Hill Giants...");
                    return handleWalkToGiants();
                case WALKING_TO_BANK:
                    paint.setCurrentStatus("→ [Giants] Lopen naar bank...");
                    return handleWalkToBank();
                case BANKING:
                    paint.setCurrentStatus(needsGearPrepAfterKey ? "🛡 [Giants] Gear prep: food + key + wapen..." : "🏦 [Giants] Aan het banken...");
                    return handleBanking();
                case RETURNING_TO_DUNGEON:
                    paint.setCurrentStatus("↩ [Giants] Terug naar dungeon...");
                    return handleReturningToDungeon();
                case FIGHTING:
                default:
                    paint.setCurrentStatus("⚔ [Giants] Vechten tegen Hill Giants");
                    return handleFighting();
            }
        } catch (Exception e) {
            paint.setCurrentStatus("⚠ [Giants] Fout: " + e.getMessage());
            debug("Error: " + e.getMessage());
            return 2000;
        }
    }

    // ===================== STATE DETERMINATION =====================

    private State determineState() {
        IPlayer player = Players.getLocal();
        if (player == null) return State.CHECKING_LOCATION;

        WorldPoint pos = player.getWorldLocation();
        isInDungeon = pos != null && pos.getY() > 9000;

        // Bij het begin van Giants (bovengronds): eerst inv-check — kijk wat we hebben en wat we nodig hebben
        if (!isInDungeon) {
            doInventoryCheckAtStart();
        }

        // Eten altijd eerste prioriteit
        if (shouldEat() && hasFoodToEat()) return State.EATING;

        // Bones begraven
        if (config.buryBones()) {
            int count = countBuryableItems();
            if (count == 0) buryingBatchStarted = false;
            else if (Inventory.isFull() || count >= config.buryBonesMinBatch()) buryingBatchStarted = true;
            if (buryingBatchStarted && count > 0) return State.BURYING_BONES;
        }

        // GE: brass key kopen — ALTIJD prioriteit als dit actief is
        // maar: als key inmiddels al in inv/equipment/bank(open) staat, stop de GE-flow.
        if (geBuyStep > 0 && geBuyStep < 5) {
            if (hasBrassKeyAnywhere()) {
                hasBrassKey = true;
                geBuyStep = 5;
                debug("GE key-flow geannuleerd: brass key inmiddels aanwezig (inv/equipment/bank)");
                if (!isInDungeon && needsBankingFromInvCheck()) {
                    return Bank.isOpen() ? State.BANKING : State.WALKING_TO_BANK;
                }
            } else if (geBuyStep == 1) {
                // Extra safety: vóór GE aankoop altijd eerst één bankcheck uitvoeren.
                return Bank.isOpen() ? State.BANKING : State.WALKING_TO_BANK;
            }
            return State.BUYING_KEY_AT_GE;
        }

        // Brass key ground spawn fetch in progress
        if (fetchingBrassKeyFromGround) {
            // Check of we hem inmiddels hebben
            if (Inventory.contains(BRASS_KEY)) {
                fetchingBrassKeyFromGround = false;
                climbedLadderForKeyWait = false;
                brassKeyWaitStart = 0;
                hasBrassKey = true;
                needsGearPrepAfterKey = true;
                debug("Brass key opgepakt! Nu eerst gear prep (bank voor food/wapen).");
                chatLog("✅ Brass key opgepakt! Ga nu naar bank voor food & gear.");
            } else {
                return State.FETCHING_BRASS_KEY;
            }
        }

        // Na brass key ophalen: eerst naar bank voor food/gear/runes
        if (needsGearPrepAfterKey) {
            if (Bank.isOpen()) {
                return State.BANKING;
            }
            if (isInDungeon) {
                return State.WALKING_TO_BANK;
            }
            // Alleen naar dungeon als we alles hebben: ingesteld food-aantal, key, wapen/runes
            if (hasBrassKey && !needsBankingFromInvCheck()) {
                needsGearPrepAfterKey = false;
                debug("Gear prep klaar — food + key + gear/runes aanwezig");
                return State.WALKING_TO_ENTRANCE;
            }
            return State.WALKING_TO_BANK;
        }

        // Niet in dungeon — check brass key EERST, dan pas food
        if (!isInDungeon) {
            // Alleen terug naar dungeon na bank als we niet opnieuw naar de bank moeten (food/gear/depot).
            // Anders: pingpong ladder omhoog (bank) ↔ Varrock shed naar beneden (returning).
            if (walkingBackAfterBank && !needsBankingFromInvCheck()) {
                return State.RETURNING_TO_DUNGEON;
            }
            if (walkingBackAfterBank && needsBankingFromInvCheck()) {
                walkingBackAfterBank = false;
                debug("Terug naar dungeon uitgesteld: nog bank nodig (inv/food/gear)");
            }

            hasBrassKey = hasBrassKeyAnywhere();

            // Geen brass key? → Koop bij GE of ga naar ground spawn
            // Dit MOET vóór de food-check, anders loopt de bot zinloos naar de bank
            if (!hasBrassKey) {
                if (geBuyStep >= 5) {
                    if (!fetchingBrassKeyFromGround) {
                        fetchingBrassKeyFromGround = true;
                        climbedLadderForKeyWait = false;
                        brassKeyWaitStart = 0;
                        debug("GE flow klaar zonder key → brass key ophalen van ground spawn");
                    }
                    return State.FETCHING_BRASS_KEY;
                }

                if (geBuyStep == 0) {
                    // Altijd eerst bank-check doen (key kan al in bank liggen).
                    // Pas als banking flow bevestigt dat key niet in bank zit, zetten we geBuyStep=1.
                    debug("Geen brass key in inv/equipment → eerst bank checken op key");
                    return Bank.isOpen() ? State.BANKING : State.WALKING_TO_BANK;
                }
                return State.BUYING_KEY_AT_GE;
            }

            // Brass key aanwezig — op basis van inv-check: missen we iets? → dan eerst banken
            if (needsBankingFromInvCheck()) {
                return Bank.isOpen() ? State.BANKING : State.WALKING_TO_BANK;
            }
            // Geen food maar alleen weg als we echt moeten eten (hp onder drempel).
            if (config.disableCombatNoFood() && shouldEat() && !hasFoodToEat()) {
                return State.PAUSED_NO_FOOD;
            }
            if (Inventory.isFull()) {
                return Bank.isOpen() ? State.BANKING : State.WALKING_TO_BANK;
            }

            return State.WALKING_TO_ENTRANCE;
        }

        // === In dungeon ===

        // Brass key fetch actief in dungeon?
        if (fetchingBrassKeyFromGround) {
            return State.FETCHING_BRASS_KEY;
        }

        // Geen food:
        // - alleen vluchten/pausen als we echt "moeten eten" (hp onder drempel)
        // - als hp nog ok is, blijven vechten totdat we effectief eten nodig hebben
        if (!hasFoodToEat() && shouldEat()) {
            if (config.bankWhenNoFood()) {
                return Bank.isOpen() ? State.BANKING : State.WALKING_TO_BANK;
            } else if (config.disableCombatNoFood()) {
                return State.PAUSED_NO_FOOD;
            }
        }

        // MAGE: te weinig runes → naar bank (nooit casten zonder runes)
        if (config.giantsCombatStyle() == CombatBotConfig.ImpsCombatStyle.MAGE) {
            String runeWarning = checkGiantsRuneSupply();
            if (runeWarning != null) {
                debug(runeWarning + " → naar bank");
                return Bank.isOpen() ? State.BANKING : State.WALKING_TO_BANK;
            }
        }

        // Belangrijk: tijdens combat niet wegrennen naar bank op loot/inv-cap redenen.
        // Combat detectie moet ook werken als je "uit jezelf" wordt aangevallen (NPC -> player),
        // dus niet alleen op player.isInteracting().
        boolean inCombatNow = isInCombat();

        // Inventory vol met nog genoeg food? Dan doorvechten (niet direct banken).
        boolean fullInvWithFoodBuffer = Inventory.isFull() && countFoodInInv() > 5;

        // Inventory vol of genoeg loot (instelling: bank bij X loot items)
        if (!inCombatNow && Inventory.isFull() && !fullInvWithFoodBuffer) {
            return Bank.isOpen() ? State.BANKING : State.WALKING_TO_BANK;
        }
        if (!inCombatNow && countLootItemsInInv() >= config.giantsBankWhenLoot() && !fullInvWithFoodBuffer) {
            return Bank.isOpen() ? State.BANKING : State.WALKING_TO_BANK;
        }

        // Zijn we bij Hill Giants?
        if (pos != null && pos.distanceTo(HILL_GIANTS_CENTER) > HILL_GIANTS_RADIUS + 5) {
            return State.WALKING_TO_GIANTS;
        }

        // Post-kill loot window
        if (lootWindowEnd > 0 && System.currentTimeMillis() < lootWindowEnd) {
            return State.POST_KILL_WAIT;
        }

        // Panel-uitloggen: loot voorrang, geen nieuwe kills
        if (suppressNewAttacksForLogout && !isInCombat()) {
            if (hasSpecialLootNearby() || hasLootNearby()) {
                return State.LOOTING;
            }
        }

        // Loot alleen na kill-window (anders gaat hij soms looten terwijl de giant nog leeft/aanvalt)
        if (lootWindowEnd > 0
                && (hasSpecialLootNearby() || hasLootNearby())
                && !isInCombat()) {
            return State.LOOTING;
        }

        return State.FIGHTING;
    }

    // ===================== HANDLERS =====================

    private int handleEating() {
        IInventoryItem food = Inventory.getFirst(item ->
                item != null && item.getName() != null && item.hasAction("Eat"));
        if (food != null) {
            food.interact("Eat");
            return 1200 + random.nextInt(600);
        }
        IInventoryItem drink = Inventory.getFirst(item ->
                item != null && item.getName() != null && item.hasAction("Drink"));
        if (drink != null) {
            drink.interact("Drink");
            return 1200 + random.nextInt(600);
        }
        return 600;
    }

    private int handleBuryingBones() {
        int buryDelay = buryOneBoneIfReady();
        if (buryDelay > 0) return buryDelay;
        buryingBatchStarted = false;
        return 300;
    }

    private int handleLooting() {
        long now = System.currentTimeMillis();
        if (now - lastLootPickupTime < LOOT_PICKUP_COOLDOWN_MS) return 300;

        String[] lootNames = getLootItems();
        List<String> specialLootNames = getSpecialLootNames();
        IPlayer player = Players.getLocal();
        if (player == null) return 600;
        WorldPoint myPos = player.getWorldLocation();

        // Als inventory vol is en we willen looten:
        // 1) probeer eerst slim ruimte te maken (alleen eten als HP niet vol is, bones begraven als kan)
        // 2) lukt dat niet, dan niet blijven hangen in LOOTING maar verder met vechten
        if (Inventory.isFull()) {
            int spaceActionDelay = tryMakeSpaceForLootWhenFull();
            if (spaceActionDelay > 0) return spaceActionDelay;
        }

        // Special loot: altijd direct oppakken (bypass lootOnlyOwn).
        if (specialLootNames != null && !specialLootNames.isEmpty()) {
            ITileItem special = TileItems.getNearest(item ->
                    item != null && item.getName() != null
                            && item.getWorldLocation() != null
                            && !isGroundLootTileExcluded(item.getWorldLocation())
                            && specialLootNames.stream().anyMatch(n -> n.equalsIgnoreCase(item.getName()))
                            && canLootItemNow(item)
                            && item.getWorldLocation().distanceTo(myPos) <= HILL_GIANTS_RADIUS + 3);
            if (special != null) {
                recordLootToPaint(special);
                special.interact("Take");
                lastLootPickupTime = now;
                debug("Special loot: " + special.getName());
                return 800 + random.nextInt(600);
            }
        }

        // Normale loot
        if (config.lootOnlyOwn()) {
            ITileItem mine = pickNearestFromMine(lootNames, myPos);
            if (mine != null) {
                if (!canLootItemNow(mine)) return 300;
                recordLootToPaint(mine);
                mine.interact("Take");
                lastLootPickupTime = now;
                debug("Loot (mine): " + mine.getName());
                return 800 + random.nextInt(600);
            }
        }

        for (String lootName : lootNames) {
            ITileItem loot = TileItems.getNearest(item ->
                    item != null && item.getName() != null
                            && item.getWorldLocation() != null
                            && !isGroundLootTileExcluded(item.getWorldLocation())
                            && item.getName().equalsIgnoreCase(lootName)
                            && canLootItemNow(item)
                            && item.getWorldLocation().distanceTo(myPos) <= HILL_GIANTS_RADIUS + 3);
            if (loot != null) {
                recordLootToPaint(loot);
                loot.interact("Take");
                lastLootPickupTime = now;
                debug("Loot: " + lootName);
                return 800 + random.nextInt(600);
            }
        }

        if (Inventory.isFull()) {
            // Niets lootbaars kunnen pakken terwijl inventory vol is -> niet vast blijven hangen.
            lootWindowEnd = 0;
        }
        killsSinceLastLoot = 0;
        return 300;
    }

    public void setSuppressNewAttacksForLogout(boolean suppress) {
        this.suppressNewAttacksForLogout = suppress;
    }

    /** Voor panel-uitloggen: nog loot op de grond of post-kill venster? */
    public boolean hasPendingLootBeforePanelLogout() {
        if (isInCombat()) {
            return false;
        }
        if (lootWindowEnd > 0 && System.currentTimeMillis() < lootWindowEnd) {
            return true;
        }
        return hasLootNearby() || hasSpecialLootNearby();
    }

    /** Zelfde als interne combat-detectie (Hill Giants). */
    public boolean isCurrentlyInCombat() {
        return isInCombat();
    }

    private int handleFighting() {
        IPlayer player = Players.getLocal();
        if (player == null) return 600;

        // Al in combat?
        if (isInCombat()) {
            targetWasAlive = true;
            return 600 + random.nextInt(400);
        }

        // Target was alive en nu niet meer → kill!
        if (targetWasAlive) {
            targetWasAlive = false;
            killsSinceLastLoot++;
            lootWindowEnd = System.currentTimeMillis() + POST_KILL_WAIT_MS;
            return 300;
        }

        if (suppressNewAttacksForLogout) {
            return 400 + random.nextInt(300);
        }

        // MAGE: eerst autocast instellen en staff equipen (eenmalig)
        if (config.giantsCombatStyle() == CombatBotConfig.ImpsCombatStyle.MAGE) {
            if (!autocastSet) {
                CombatBotConfig.ImpsMageSpell spell = config.giantsMageSpell();
                if (Skills.getLevel(Skill.MAGIC) < spell.getLevelReq()) {
                    chatLog("⚠ Magic level te laag voor " + spell.getSpellName() + " (nodig " + spell.getLevelReq() + ")");
                    return 2000;
                }
                // Staff in inv maar niet gedragen? Eerst equipen
                boolean staffEquipped = Equipment.contains(i -> i != null && i.getName() != null && i.getName().toLowerCase().contains("staff"));
                boolean staffInInv = Inventory.contains(i -> i != null && i.getName() != null && i.getName().toLowerCase().contains("staff"));
                if (staffInInv && !staffEquipped) {
                    IInventoryItem staff = Inventory.getFirst(item -> item != null && item.getName() != null && item.getName().toLowerCase().contains("staff"));
                    if (staff != null) {
                        staff.interact(staff.hasAction("Wield") ? "Wield" : "Wear");
                        sleep(800, 1200);
                        return 1000;
                    }
                }
                SpellBook.Standard stdSpell = spell.getStandardSpell();
                if (!Magic.isAutoCasting(stdSpell)) {
                    chatLog("Autocast instellen: " + spell.getSpellName());
                    Magic.setAutoCast(stdSpell, false);
                    sleep(800, 1200);
                    if (Magic.isAutoCasting(stdSpell)) {
                        autocastSet = true;
                        chatLog("✅ Autocast actief: " + spell.getSpellName());
                    }
                    return 1000;
                }
                autocastSet = true;
            }
        }

        // Zoek Hill Giant
        long now = System.currentTimeMillis();
        if (now - lastAttackClickTime < ATTACK_CLICK_COOLDOWN_MS) return 300;

        String monsterName = config.giantsMonsterName();
        WorldPoint myPos = player.getWorldLocation();

        boolean multi = NpcCombatTargetHelper.isInMultiCombatZone();
        List<INPC> candidates = NPCs.getAll(npc -> {
            if (npc == null || npc.getName() == null) return false;
            if (!npc.getName().equalsIgnoreCase(monsterName)) return false;
            if (npc.isDead()) return false;
            if (!multi && NpcCombatTargetHelper.isNpcInCombatWithOther(npc, player)) return false;
            return npc.getWorldLocation() != null
                    && npc.getWorldLocation().distanceTo(HILL_GIANTS_CENTER) <= HILL_GIANTS_RADIUS;
        });

        // Kies bewust de dichtstbijzijnde (en bij voorkeur "kortste route") target.
        INPC target = null;
        int bestPathDist = Integer.MAX_VALUE;
        int bestTileDist = Integer.MAX_VALUE;
        if (candidates != null) {
            for (INPC npc : candidates) {
                if (npc == null || npc.getWorldLocation() == null) continue;

                int tileDist = myPos.distanceTo(npc.getWorldLocation());
                int pathDist;
                try {
                    pathDist = Movement.calculateDistance(npc.getWorldLocation());
                } catch (Exception e) {
                    pathDist = tileDist;
                }

                // Als er geen pad is (of extreem), sla over.
                if (pathDist < 0) continue;
                if (pathDist > tileDist * 3 && pathDist > 15) continue;

                if (pathDist < bestPathDist
                        || (pathDist == bestPathDist && tileDist < bestTileDist)) {
                    bestPathDist = pathDist;
                    bestTileDist = tileDist;
                    target = npc;
                }
            }
        }

        if (target != null) {
            target.interact("Attack");
            lastAttackClickTime = now;
            lastAttackedNpcIndex = target.getIndex();
            debug("Aanval: " + monsterName);
            return 600 + random.nextInt(600);
        }

        // Geen target gevonden → loop rond
        if (myPos.distanceTo(HILL_GIANTS_CENTER) > 5) {
            return giantsTravelTo(HILL_GIANTS_CENTER, 350 + random.nextInt(200));
        }
        return 1000 + random.nextInt(1000);
    }

    private int handleBuyingKeyAtGe() {
        if (shouldAbortActions()) {
            geBuyStep = 0;
            gePurchaseInProgress = false;
            return 300;
        }
        long now = System.currentTimeMillis();
        if (now - lastGeInteraction < GE_INTERACTION_COOLDOWN) return 300;

        IPlayer player = Players.getLocal();
        if (player == null) return 600;
        WorldPoint pos = player.getWorldLocation();

        switch (geBuyStep) {
            case 1: // Eerst bank: food + coins + (key als aanwezig), daarna pas GE
                if (Bank.isOpen()) {
                    // 1. Food eerst — bot moet food meenemen voordat hij naar GE gaat (minimaal 1, ook als instelling op 0 staat)
                    if (!hasFoodToEat()) {
                        int amount = getFoodAmount();
                        String foodName = config.foodChoice().toItemName();
                        if (foodName.equalsIgnoreCase("any")) {
                            String best = findBestFoodInBank();
                            if (best != null) {
                                withdrawAmountThenAll(best, amount, 400, 700);
                            }
                        } else if (Bank.contains(foodName)) {
                            withdrawAmountThenAll(foodName, amount, 400, 700);
                        }
                    }

                    // 2. Coins voor GE
                    if (Bank.contains("Coins")) {
                        Bank.withdrawAll("Coins");
                        sleep(400, 700);
                    }

                    // 3. Brass key uit bank als aanwezig (dan hoeven we niet naar GE)
                    if (Bank.contains(BRASS_KEY)) {
                        Bank.withdraw(BRASS_KEY, 1);
                        sleep(400, 700);
                    }

                    // 4. Teleport runes
                    VarrockTeleportHelper.prepareVarrockTeleport();

                    // 5. Sluit bank
                    Bank.close();
                    sleep(500, 900);

                    if (Inventory.contains(BRASS_KEY)) {
                        hasBrassKey = true;
                        geBuyStep = 5;
                        needsGearPrepAfterKey = false;
                        debug("Brass key + food uit bank gehaald in één sessie! Klaar.");
                        chatLog("✅ Brass key gevonden in bank! Op naar Hill Giants!");
                        return 600;
                    }

                    // Geen brass key in bank → nu pas naar GE lopen (we hebben al food + coins)
                    geBuyStep = 2;
                    lastGeInteraction = now;
                    debug("Bank klaar (food + coins), nu naar GE voor brass key");
                    return 1000;
                }
                // Bank niet open → open een bank-object dicht bij GE (voorkomt heen-en-weer naar Edgeville bank).
                return tryOpenBankNearGE(pos);

            case 2: // Walk naar GE
                if (pos.distanceTo(GE_LOCATION) <= 8) {
                    geBuyStep = 3;
                    return 300;
                }
                int geTravel = giantsTravelTo(GE_LOCATION, 320 + random.nextInt(180));
                lastGeInteraction = now;
                return geTravel;

            case 3: // Koop brass key via GeRestockHelper
                if (gePurchaseInProgress) {
                    return 800;
                }

                gePurchaseInProgress = true;
                try {
                    int minP = Math.max(1, config.giantsBrassKeyPriceMin());
                    int maxP = Math.max(minP, config.giantsBrassKeyPriceMax());
                    int buyPrice = minP + random.nextInt(maxP - minP + 1);
                    debug("Brass key kopen via GeRestockHelper @ " + buyPrice + "gp (instelling: " + minP + "-" + maxP + ")");
                    GeRestockHelper.RestockResult result = GeRestockHelper.buyWithEscalation(
                            BRASS_KEY,
                            1,
                            buyPrice,
                            config::botEnabled
                    );

                    if (result == GeRestockHelper.RestockResult.SUCCESS) {
                        if (Inventory.contains(BRASS_KEY)) {
                            geBuyStep = 5;
                            hasBrassKey = true;
                            needsGearPrepAfterKey = true; // Nog food ophalen!
                            chatLog("✅ Brass key gekocht! Ga nu naar bank voor food & gear.");
                            debug("Brass key SUCCESS via GeRestockHelper → gear prep");
                            return 600;
                        }
                        debug("GeRestockHelper SUCCESS maar key niet in inventory, retry collect...");
                        return 1500;
                    } else if (result == GeRestockHelper.RestockResult.GE_NOT_AVAILABLE) {
                        debug("GE niet beschikbaar — opnieuw proberen");
                        return 3000;
                    } else {
                        // FAILED — fallback naar ground spawn
                        debug("Brass key kopen GEFAALD → ground spawn als fallback");
                        chatLog("⚠ Brass key kopen mislukt, ga key ophalen in dungeon...");
                        hasBrassKey = false;
                        geBuyStep = 5;
                        fetchingBrassKeyFromGround = true;
                        return 600;
                    }
                } finally {
                    gePurchaseInProgress = false;
                    lastGeInteraction = System.currentTimeMillis();
                }

            case 5:
            default:
                return 300;
        }
    }

    // ===================== BRASS KEY GROUND SPAWN =====================

    /**
     * Brass key ophalen van ground spawn in Edgeville Dungeon.
     *
     * Flow:
     * 1. Loop naar BRASS_KEY_SPAWN (3131, 9862, 0).
     * 2. Check of de key op de grond ligt (item ID 983) → pak op.
     * 3. Na oppakken: ren weg van NPCs (terug richting ladder).
     * 4. Key niet gevonden? Climb ladder omhoog, wacht 3 min, ga weer naar beneden.
     */
    private int handleFetchingBrassKey() {
        IPlayer player = Players.getLocal();
        if (player == null) return 600;
        WorldPoint pos = player.getWorldLocation();

        // Al in inventory? Klaar!
        if (Inventory.contains(BRASS_KEY)) {
            fetchingBrassKeyFromGround = false;
            climbedLadderForKeyWait = false;
            brassKeyWaitStart = 0;
            hasBrassKey = true;
            debug("Brass key in inventory → klaar!");
            chatLog("✅ Brass key opgepakt!");
            return 300;
        }

        // We zijn bovengronds na ladder climb → wachten
        if (climbedLadderForKeyWait && !isInDungeon) {
            long waited = System.currentTimeMillis() - brassKeyWaitStart;
            if (waited < BRASS_KEY_WAIT_MS) {
                long remaining = (BRASS_KEY_WAIT_MS - waited) / 1000;
                debug("Wachten op brass key respawn... nog " + remaining + "s");
                paint.setCurrentStatus("⏳ [Giants] Wachten op key respawn (" + remaining + "s)...");
                // Wacht in stappen van 10-15 sec (niet de hele 3 min in 1 sleep)
                return 10000 + random.nextInt(5000);
            }
            // Wachttijd voorbij → ga weer naar beneden
            debug("Wachttijd voorbij, terug naar dungeon voor brass key");
            climbedLadderForKeyWait = false;
            brassKeyWaitStart = 0;
            // Ga via trapdoor terug
            return handleEnteringDungeon();
        }

        // Niet in dungeon → ga eerst naar trapdoor en ga naar beneden
        if (!isInDungeon) {
            // Loop eerst naar pre-trapdoor waypoint
            if (pos.distanceTo(EDGEVILLE_PRE_TRAPDOOR) > 8) {
                debug("Lopen naar pre-trapdoor waypoint...");
                return giantsTravelTo(EDGEVILLE_PRE_TRAPDOOR, 350 + random.nextInt(200));
            }
            // Open trapdoor
            return handleEnteringDungeon();
        }

        // In dungeon — loop naar brass key spawn
        int distToSpawn = pos.distanceTo(BRASS_KEY_SPAWN);
        if (distToSpawn > 5) {
            debug("Lopen naar brass key spawn (afstand: " + distToSpawn + ")");
            return giantsTravelTo(BRASS_KEY_SPAWN, 350 + random.nextInt(200));
        }

        // Bij de spawn — check of key op de grond ligt
        ITileItem brassKeyItem = TileItems.getNearest(item ->
                item != null && item.getName() != null
                        && item.getWorldLocation() != null
                        && !isGroundLootTileExcluded(item.getWorldLocation())
                        && item.getName().equalsIgnoreCase(BRASS_KEY)
                        && item.getWorldLocation().distanceTo(BRASS_KEY_SPAWN) <= 4);

        if (brassKeyItem != null) {
            debug("Brass key gevonden op de grond! Oppakken...");
            brassKeyItem.interact("Take");
            // Na oppakken: ren terug naar veilige plek (ladder)
            sleep(600, 1000);
            if (Inventory.contains(BRASS_KEY)) {
                hasBrassKey = true;
                fetchingBrassKeyFromGround = false;
                debug("Brass key opgepakt! Wegrennen van NPCs...");
                chatLog("✅ Brass key opgepakt!");
                // Ren weg van gevaarlijke NPCs
                return giantsTravelTo(EDGEVILLE_LADDER_BELOW, 400);
            }
            return 600;
        }

        // Key niet op de grond → ladder omhoog, wacht 3 min
        debug("Brass key niet op de grond → ladder omhoog, wacht 3 minuten");
        chatLog("Brass key niet gevonden, wacht 3 min en probeer opnieuw...");

        // Zoek de ladder om omhoog te klimmen
        ITileObject ladder = TileObjects.getNearest(o ->
                o != null && o.getName() != null
                        && o.getName().equalsIgnoreCase("Ladder")
                        && o.hasAction("Climb-up")
                        && o.getWorldLocation().distanceTo(BRASS_KEY_LADDER_TILE) <= 8);

        if (ladder != null) {
            ladder.interact("Climb-up");
            climbedLadderForKeyWait = true;
            brassKeyWaitStart = System.currentTimeMillis();
            debug("Ladder omhoog geklommen, wachttijd gestart");
            return 2000 + random.nextInt(1000);
        }

        // Ladder niet gevonden, loop ernaartoe
        if (pos.distanceTo(BRASS_KEY_LADDER_TILE) > 5) {
            return giantsTravelTo(BRASS_KEY_LADDER_TILE, 400);
        }

        // Fallback: probeer elke ladder in de buurt
        ITileObject anyLadder = TileObjects.getNearest(o ->
                o != null && o.getName() != null
                        && o.getName().equalsIgnoreCase("Ladder")
                        && o.hasAction("Climb-up"));
        if (anyLadder != null) {
            anyLadder.interact("Climb-up");
            climbedLadderForKeyWait = true;
            brassKeyWaitStart = System.currentTimeMillis();
            return 2000;
        }

        return 1500;
    }

    private int handleWalkToEntrance() {
        IPlayer player = Players.getLocal();
        if (player == null) return 600;
        WorldPoint pos = player.getWorldLocation();

        if (hasBrassKey && !fetchingBrassKeyFromGround) {
            if (pos.distanceTo(VARROCK_SHED) <= 3) {
                return handleEnteringDungeon();
            }
            debug("Lopen naar Varrock shed (brass key)");
            return giantsTravelTo(VARROCK_SHED, 350 + random.nextInt(200));
        }
        if (pos.distanceTo(EDGEVILLE_PRE_TRAPDOOR) > 8) {
            debug("Lopen naar pre-trapdoor waypoint");
            return giantsTravelTo(EDGEVILLE_PRE_TRAPDOOR, 350 + random.nextInt(200));
        }
        if (pos.distanceTo(EDGEVILLE_TRAPDOOR) <= 3) {
            return handleEnteringDungeon();
        }
        debug("Lopen naar Edgeville trapdoor (geen key)");
        return giantsTravelTo(EDGEVILLE_TRAPDOOR, 350 + random.nextInt(200));
    }

    private int handleEnteringDungeon() {
        if (hasBrassKey && !fetchingBrassKeyFromGround) {
            WorldPoint myPos = Players.getLocal() != null ? Players.getLocal().getWorldLocation() : null;
            // Deur eerst openen; pas trap klikken als we echt bij de trap staan (voorkomt deur/trap loop).
            ITileObject door = TileObjects.getNearest(o ->
                    o != null && o.getName() != null
                            && o.getName().equalsIgnoreCase("Door")
                            && o.hasAction("Open")
                            && o.getWorldLocation().distanceTo(VARROCK_SHED) <= 5);
            ITileObject trapdoor = TileObjects.getNearest(o ->
                    o != null && o.getName() != null
                            && (o.getName().equalsIgnoreCase("Trapdoor") || o.getName().equalsIgnoreCase("Ladder"))
                            && (o.hasAction("Climb-down") || o.hasAction("Open"))
                            && o.getWorldLocation().distanceTo(VARROCK_SHED) <= 8);

            // Als we nog niet bij de trap zijn: eerst deur openen (met cooldown)
            if (trapdoor == null || myPos == null || myPos.distanceTo(trapdoor.getWorldLocation()) > 2) {
                long now = System.currentTimeMillis();
                if (door != null && now - lastShedDoorClickMs > 2200) {
                    door.interact("Open");
                    lastShedDoorClickMs = now;
                    debug("Varrock shed deur geopend");
                    return 2200 + random.nextInt(600);
                }
                // Als deur niet gevonden wordt: blijf naar shed tile lopen
                return giantsTravelTo(VARROCK_SHED, 450);
            }

            // We staan bij de trap: nu pas naar beneden (met cooldown)
            long now = System.currentTimeMillis();
            if (now - lastShedTrapClickMs > 2200) {
                String action = trapdoor.hasAction("Climb-down") ? "Climb-down" : "Open";
                trapdoor.interact(action);
                lastShedTrapClickMs = now;
                debug("Varrock shed trapdoor/ladder gebruikt");
                return 2200 + random.nextInt(600);
            }
            return 600;
        }

        // Edgeville trapdoor — zoek op ID voor betrouwbaarheid
        ITileObject trapdoor = TileObjects.getNearest(o ->
                o != null && o.getName() != null
                        && o.getName().equalsIgnoreCase("Trapdoor")
                        && o.getWorldLocation().distanceTo(EDGEVILLE_TRAPDOOR) <= 5);

        if (trapdoor != null) {
            // Eerst "Open" als het dicht is, dan "Climb-down"
            if (trapdoor.hasAction("Open")) {
                trapdoor.interact("Open");
                debug("Edgeville trapdoor geopend");
                return 1500 + random.nextInt(500);
            }
            if (trapdoor.hasAction("Climb-down")) {
                trapdoor.interact("Climb-down");
                debug("Edgeville trapdoor Climb-down");
                return 2000 + random.nextInt(1000);
            }
        }

        // Probeer ook Manhole (sommige versies)
        ITileObject manhole = TileObjects.getNearest(o ->
                o != null && o.getName() != null
                        && o.getName().equalsIgnoreCase("Manhole")
                        && (o.hasAction("Climb-down") || o.hasAction("Open"))
                        && o.getWorldLocation().distanceTo(EDGEVILLE_TRAPDOOR) <= 5);
        if (manhole != null) {
            String action = manhole.hasAction("Climb-down") ? "Climb-down" : "Open";
            manhole.interact(action);
            debug("Edgeville manhole gebruikt");
            return 2000 + random.nextInt(1000);
        }

        return giantsTravelTo(EDGEVILLE_TRAPDOOR, 450);
    }

    private int handleWalkToGiants() {
        IPlayer player = Players.getLocal();
        if (player == null) return 600;
        WorldPoint pos = player.getWorldLocation();

        if (needsBankingFromInvCheck()) {
            debug("[WalkGiants] nog niet klaar voor giants (food/key/gear) → eerst bank");
            if (Bank.isOpen()) {
                return handleBanking();
            }
            return handleWalkToBank();
        }

        if (pos.distanceTo(HILL_GIANTS_CENTER) <= HILL_GIANTS_RADIUS) {
            debug("Aangekomen bij Hill Giants!");
            return 300;
        }

        return giantsTravelTo(HILL_GIANTS_CENTER, 350 + random.nextInt(200));
    }

    private int handleWalkToBank() {
        if (isInDungeon) {
            // Exit omhoog is afhankelijk van waar je staat:
            // - Screenshot/HUD komt overeen met BRASS_KEY_LADDER_TILE (3116,9852,0)
            // - fallback kan EDGEVILLE_LADDER_BELOW (3096,9867,0) zijn
            WorldPoint[] preferredExitCenters = new WorldPoint[] {
                    BRASS_KEY_LADDER_TILE,
                    EDGEVILLE_LADDER_BELOW,
                    EDGEVILLE_TRAPDOOR
            };

            ITileObject exitUp = null;
            WorldPoint exitCenter = null;
            for (WorldPoint center : preferredExitCenters) {
                if (center == null) continue;
                ITileObject found = TileObjects.getNearest(o ->
                        o != null
                                && o.getName() != null
                                && (o.getName().equalsIgnoreCase("Ladder")
                                || o.getName().equalsIgnoreCase("Trapdoor")
                                || o.getName().equalsIgnoreCase("Manhole"))
                                && o.hasAction("Climb-up")
                                && o.getWorldLocation() != null
                                && o.getWorldLocation().distanceTo(center) <= 12);
                if (found != null) {
                    exitUp = found;
                    exitCenter = center;
                    break;
                }
            }

            if (exitUp != null) {
                String action = exitUp.hasAction("Climb-up") ? "Climb-up" : "Open";
                long now = System.currentTimeMillis();
                if (now - lastDungeonExitClickMs > 2200) {
                    preBankPosition = Players.getLocal() != null ? Players.getLocal().getWorldLocation() : null;
                    exitUp.interact(action);
                    lastDungeonExitClickMs = now;
                    debug("Dungeon exit omhoog (naar bank) via " + exitUp.getName() + " action=" + action);
                    return 2200 + random.nextInt(800);
                }
                // Cooldown: recheck even
                return 600 + random.nextInt(400);
            }

            // Geen exit gevonden → wandel naar eerste preferred exit center en recheck.
            WorldPoint walkTarget = preferredExitCenters[0] != null ? preferredExitCenters[0] : EDGEVILLE_LADDER_BELOW;
            return giantsTravelTo(walkTarget, 400);
        }

        // Bovengronds
        if (Bank.isOpen()) {
            return handleBanking();
        }

        IPlayer player = Players.getLocal();
        WorldPoint pos = player != null ? player.getWorldLocation() : null;
        // Altijd bank zoeken/openen in GE-regio (voorkomt Edgeville-bank en "stilstaan" rond verkeerde interact ranges).
        return tryOpenBankNearGE(pos);
    }

    /**
     * Probeert een bank te openen in de buurt van {@link #GE_LOCATION}.
     * Zo voorkom je dat SDK "nearest bank" pakt (vaak Edgeville) terwijl we eigenlijk bij GE willen zijn.
     */
    private int tryOpenBankNearGE(WorldPoint pos) {
        if (Bank.isOpen()) return 600;
        if (pos == null) return 600;

        // Als we nog niet richting GE zijn: eerst naar GE lopen
        if (pos.distanceTo(GE_LOCATION) > 18) {
            return giantsTravelTo(GE_LOCATION, 300 + random.nextInt(150));
        }

        // Zoek bank-object in de buurt van GE.
        // Niet op naam filteren; sommige clients/objects geven onbetrouwbare namen terug.
        ITileObject bankObj = TileObjects.getNearest(obj ->
                obj != null
                        && obj.getWorldLocation() != null
                        && obj.getWorldLocation().distanceTo(GE_LOCATION) <= GE_BANK_SEARCH_RADIUS
                        && obj.hasAction("Bank"));

        if (bankObj != null) {
            // Als we dichtbij staan: interact; anders lopen.
            if (pos.distanceTo(bankObj.getWorldLocation()) <= 5) {
                String action = "Bank";
                bankObj.interact(action);
                debug("GE-bank openen nabij: " + bankObj.getName() + " actie=" + action);
                return 2000;
            }

            debug("GE-bankobj gevonden, loop ernaartoe: dist=" + pos.distanceTo(bankObj.getWorldLocation()));
            return giantsTravelTo(bankObj.getWorldLocation(), 350 + random.nextInt(200));
        }

        // Fallback 1: sta nog niet echt in de GE-bankzone → eerst naar GE-center.
        int geDist = pos.distanceTo(GE_LOCATION);
        if (geDist > 5) {
            debug("GE-bankobj niet gevonden (distToGE=" + geDist + ") → eerst naar GE lopen");
            return giantsTravelTo(GE_LOCATION, 320 + random.nextInt(180));
        }

        // Fallback 2: probeer algemene bank-open helper i.p.v. direct Bank.open() spam.
        debug("GE-bankobj niet gevonden in radius (distToGE=" + geDist + ") → fallback BankHelper.tryOpenFullBank()");
        BankHelper.tryOpenFullBank();
        return 1400 + random.nextInt(400);
    }

    /**
     * Eén banksessie (zoals Imps): deposit alles behalve keep-list, dan alle withdrawals, dan pas sluiten.
     * Geen bank open/dicht tussendoor.
     */
    private int handleBanking() {
        if (shouldAbortActions()) {
            preparingGear = false;
            return 300;
        }
        if (!Bank.isOpen()) return handleWalkToBank();

        // Stap 1: Inv-check + deposit alles behalve keep-list (zoals Imps handleBankRestock)
        doInventoryCheckAndLog();
        List<String> keepNames = getGiantsStrictBankKeepNames();
        Bank.depositAllExcept(keepNames.toArray(new String[0]));
        sleep(500, 800);
        if (hasGiantsStrictDepositItems()) {
            var remaining = Inventory.getAll(item -> {
                if (item == null || item.getName() == null) return false;
                for (String k : keepNames) {
                    if (k.equalsIgnoreCase(item.getName())) return false;
                }
                return true;
            });
            if (remaining != null) {
                for (var rem : remaining) {
                    if (shouldAbortActions()) {
                        break;
                    }
                    if (rem != null && rem.getName() != null) {
                        Bank.depositAll(rem.getName());
                        sleep(100, 250);
                    }
                }
            }
            sleep(300, 500);
        }
        // Bank-interface laten stabiliseren vóór withdraw (anders faalt contains/quickWithdraw soms).
        sleep(600, 1000);

        // Stap 2: Food — v2: doel-totaal in inv; meerdere pogingen; fail-safe; GE pas als bank geen food meer heeft of GE zinvol
        int foodNeed = getFoodAmount();
        int foodHave = countFoodInInv();
        if (foodHave < foodNeed && foodNeed >= 1) {
            debug("[Bank] food-sessie v2 need=" + foodNeed + " have=" + foodHave + " (bank blijft open tot food ok)");

            String invFoodName = getPreferredInventoryFoodName();
            String foodNameCfg = config.foodChoice().toItemName();
            String attemptedBankFood = invFoodName;

            for (int pass = 1; pass <= 3 && countFoodInInv() < foodNeed; pass++) {
                if (shouldAbortActions()) {
                    return 300;
                }
                if (invFoodName != null && Bank.contains(invFoodName)) {
                    attemptedBankFood = invFoodName;
                    debug("[Bank] pass " + pass + "/3 top-up " + invFoodName + " → tot " + foodNeed + " in inv");
                    withdrawAmountThenAll(invFoodName, foodNeed, 450, 850);
                    sleep(400, 700);
                }
                if (countFoodInInv() >= foodNeed) break;

                if (foodNameCfg.equalsIgnoreCase("any")) {
                    String best = findBestFoodInBank();
                    if (best != null) {
                        attemptedBankFood = best;
                        debug("[Bank] pass " + pass + "/3 ANY → " + best + " tot " + foodNeed);
                        withdrawAmountThenAll(best, foodNeed, 450, 850);
                        sleep(400, 700);
                    }
                } else if (Bank.contains(foodNameCfg)) {
                    attemptedBankFood = foodNameCfg;
                    debug("[Bank] pass " + pass + "/3 config food → " + foodNameCfg + " tot " + foodNeed);
                    withdrawAmountThenAll(foodNameCfg, foodNeed, 450, 850);
                    sleep(400, 700);
                } else {
                    String best = findBestFoodInBank();
                    if (best != null) {
                        attemptedBankFood = best;
                        debug("[Bank] pass " + pass + "/3 fallback bank-best → " + best + " tot " + foodNeed);
                        withdrawAmountThenAll(best, foodNeed, 450, 850);
                        sleep(400, 700);
                    }
                }
                if (countFoodInInv() >= foodNeed) break;

                if (pass >= 2) {
                    String retryFood = pickGiantsBankFoodName(invFoodName, foodNameCfg, attemptedBankFood);
                    if (retryFood != null && Bank.contains(retryFood)) {
                        debug("[Bank] FAIL-SAFE pass " + pass + ": alle eatables depsoiten → " + foodNeed + "x " + retryFood);
                        depositAllEatableFromInventory();
                        sleep(500, 800);
                        withdrawAmountThenAll(retryFood, foodNeed, 450, 850);
                        sleep(400, 700);
                    }
                }
            }

            foodHave = countFoodInInv();
            String foodName = foodNameCfg;

            if (foodHave < foodNeed) {
                String last = pickGiantsBankFoodName(invFoodName, foodName, attemptedBankFood);
                if (last != null && Bank.contains(last)) {
                    debug("[Bank] laatste withdraw vóór GE: " + foodNeed + "x " + last + " (bank open)");
                    withdrawAmountThenAll(last, foodNeed, 500, 900);
                    sleep(500, 800);
                    foodHave = countFoodInInv();
                }
            }

            if (foodHave < foodNeed && config.combatGeFoodEnabled()) {
                if (getInvCount("Coins") < 500 && Bank.contains("Coins")) {
                    debug("[Bank] Weinig coins in inv → withdrawAll Coins voor eventuele GE-food");
                    Bank.withdrawAll("Coins");
                    sleep(400, 700);
                }
                String geFood = pickGiantsBankFoodName(invFoodName, foodName, attemptedBankFood);
                boolean bankStillHas = geFood != null && Bank.contains(geFood);
                int coinsInv = getInvCount("Coins");
                if (bankStillHas && coinsInv < 50) {
                    debug("[Bank] GE overgeslagen: nog '" + geFood + "' in bank maar inv tekort (" + foodHave + "/" + foodNeed
                            + "); weinig/geen coins — geen fake GE-loop; bank open laten, volgende tick opnieuw food");
                } else {
                    int shortage = foodNeed - foodHave;
                    if (shortage > 0 && tryGeRestockFood(shortage, attemptedBankFood)) {
                        Bank.close();
                        sleep(400, 700);
                        BankHelper.tryOpenFullBank();
                        return 1200;
                    }
                }
            }
        }

        // Stap 3: Gear (wapen/runes)
        CombatBotConfig.ImpsCombatStyle style = config.giantsCombatStyle();
        if (!hasWeaponForStyle(style)) {
            withdrawAllGearForStyleInOneGo(style);
        }

        // Stap 4: Brass key
        if (!Inventory.contains(BRASS_KEY) && Bank.contains(BRASS_KEY)) {
            Bank.withdraw(BRASS_KEY, 1);
            sleep(500, 800);
            hasBrassKey = true;
        } else if (!Inventory.contains(BRASS_KEY) && !Bank.contains(BRASS_KEY) && geBuyStep == 0) {
            // Eerste bank-check gedaan en key ontbreekt echt in bank → start GE koopflow.
            geBuyStep = 1;
            debug("[Bank] Brass key niet in bank gevonden → GE koopflow gestart");
        }

        // Stap 5: Amulet of power (gear prep voor elke style)
        if (!hasAmuletOfPowerEquippedOrInInv() && Bank.contains("Amulet of power")) {
            Bank.withdraw("Amulet of power", 1);
            sleep(500, 800);
        }

        // Stap 6: Staff direct aantrekken als we die in inv hebben (MAGE)
        if (style == CombatBotConfig.ImpsCombatStyle.MAGE) {
            boolean staffEquipped = Equipment.contains(i -> i != null && i.getName() != null && i.getName().toLowerCase().contains("staff"));
            boolean staffInInv = Inventory.contains(i -> i != null && i.getName() != null && i.getName().toLowerCase().contains("staff"));
            if (staffInInv && !staffEquipped) {
                IInventoryItem staff = Inventory.getFirst(item -> item != null && item.getName() != null && item.getName().toLowerCase().contains("staff"));
                if (staff != null) {
                    staff.interact(staff.hasAction("Wield") ? "Wield" : "Wear");
                    sleep(600, 1000);
                }
            }
        }

        // Stap 7: Amulet of power aantrekken als in inv
        boolean amuletEquipped = Equipment.contains(i -> i != null && i.getName() != null && i.getName().equalsIgnoreCase("Amulet of power"));
        if (!amuletEquipped && Inventory.contains(i -> i != null && i.getName() != null && i.getName().equalsIgnoreCase("Amulet of power"))) {
            IInventoryItem amulet = Inventory.getFirst(item -> item != null && item.getName() != null && item.getName().equalsIgnoreCase("Amulet of power"));
            if (amulet != null) {
                amulet.interact(amulet.hasAction("Wear") ? "Wear" : "Wield");
                sleep(500, 800);
            }
        }

        // Stap 8: Sluit bank alleen als essentials echt compleet zijn.
        int foodNow = countFoodInInv();
        boolean readyFood = foodNow >= foodNeed;
        boolean readyGear = hasWeaponForStyle(style);
        boolean readyKey = Inventory.contains(BRASS_KEY);
        if (!readyFood || !readyGear || !readyKey) {
            debug("[Bank] Nog niet klaar: food=" + foodNow + "/" + foodNeed
                    + ", gear=" + readyGear + ", key=" + readyKey + " → bank open laten");
            return 900 + random.nextInt(400);
        }

        // Stap 9: Pas nu bank sluiten (één sessie klaar)
        Bank.close();
        sleep(500, 800);

        if (needsGearPrepAfterKey && hasFoodToEat() && Inventory.contains(BRASS_KEY)) {
            needsGearPrepAfterKey = false;
            chatLog("✅ Gear klaar! Op naar Hill Giants!");
        }
        walkingBackAfterBank = true;
        return 800;
    }

    private int handleReturningToDungeon() {
        IPlayer player = Players.getLocal();
        if (player == null) return 600;
        WorldPoint pos = player.getWorldLocation();

        if (pos.getY() > 9000) {
            walkingBackAfterBank = false;
            isInDungeon = true;
            return 300;
        }

        hasBrassKey = Inventory.contains(BRASS_KEY);
        int result = handleWalkToEntrance();
        if (result > 0) return result;

        if (Players.getLocal() != null && Players.getLocal().getWorldLocation().getY() > 9000) {
            walkingBackAfterBank = false;
            isInDungeon = true;
        }
        return 1200;
    }

    // ===================== HELPERS =====================
    /**
     * Withdraw van bank tot dit item minstens {@code targetInInventory} keer in inventory zit.
     * (Niet “delta” doorgeven: anders faalde top-up bij 5 in inv + 5 nodig met foute early-return.)
     */
    private void withdrawAmountThenAll(String itemName, int targetInInventory, int sleepMin, int sleepMax) {
        if (shouldAbortActions()) return;
        if (itemName == null || itemName.isEmpty() || targetInInventory <= 0) return;
        if (!Bank.contains(itemName)) return;

        int before = getInvCount(itemName);
        if (before >= targetInInventory) {
            sleep(300, 700);
            return;
        }
        int withdrawFromBank = targetInInventory - before;
        if (withdrawFromBank <= 0) return;

        try {
            if (Bank.isNotedWithdrawMode()) {
                Bank.setWithdrawMode(false);
                sleep(200, 400);
            }
        } catch (Exception ignored) {
        }

        try {
            Bank.quickWithdraw(itemName, withdrawFromBank);
        } catch (Exception e) {
            Bank.withdraw(itemName, withdrawFromBank);
        }
        sleep(sleepMin, sleepMax);
        sleep(300, 700);

        int after = getInvCount(itemName);
        long start = System.currentTimeMillis();
        while (!shouldAbortActions() && after < targetInInventory && System.currentTimeMillis() - start < 2000) {
            sleep(120, 220);
            after = getInvCount(itemName);
        }

        if (after < targetInInventory && Bank.contains(itemName)) {
            Bank.withdrawAll(itemName);
            sleep(sleepMin, sleepMax);
            sleep(300, 700);
        }
    }

    /** Alle Eat/Drink items uit inv naar bank (fail-safe: schone stack voor opnieuw withdraw). */
    private void depositAllEatableFromInventory() {
        if (!Bank.isOpen()) return;
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        java.util.List<IInventoryItem> all = Inventory.getAll();
        if (all == null) return;
        for (IInventoryItem item : all) {
            if (item != null && item.getName() != null
                    && (item.hasAction("Eat") || item.hasAction("Drink"))) {
                names.add(item.getName());
            }
        }
        for (String n : names) {
            try {
                Bank.depositAll(n);
                sleep(300, 700);
            } catch (Exception ignored) {
            }
        }
    }

    /** Kies welk bank-food item we bij fail-safe opnieuw nemen. */
    private String pickGiantsBankFoodName(String invFoodName, String configFoodName, String attempted) {
        if (invFoodName != null && Bank.contains(invFoodName)) return invFoodName;
        if (attempted != null && Bank.contains(attempted)) return attempted;
        if (configFoodName != null && !configFoodName.equalsIgnoreCase("any") && Bank.contains(configFoodName)) {
            return configFoodName;
        }
        return findBestFoodInBank();
    }

    /** Kies beste beschikbare food in bank (hoog → laag). */
    private String findBestFoodInBank() {
        return CombatFoodPriority.findBestInBank();
    }

    private boolean hasBrassKeyAnywhere() {
        if (Inventory.contains(BRASS_KEY)) return true;
        if (Bank.isOpen() && Bank.contains(BRASS_KEY)) return true;
        return Equipment.contains(item ->
                item != null && item.getName() != null && item.getName().equalsIgnoreCase(BRASS_KEY));
    }

    private boolean hasAmuletOfPowerEquippedOrInInv() {
        if (Equipment.contains(i -> i != null && i.getName() != null && i.getName().equalsIgnoreCase("Amulet of power"))) return true;
        return Inventory.contains(i -> i != null && i.getName() != null && i.getName().equalsIgnoreCase("Amulet of power"));
    }

    /**
     * GE-koop tot {@link #getFoodAmount()} stuks van {@code itemName} in inventory (GeRestockHelper gebruikt doel-totaal, geen “tekort”).
     */
    private boolean tryGeRestockFoodOneItem(String itemName) {
        if (itemName == null || itemName.isEmpty()) return false;
        int targetTotal = getFoodAmount();
        if (targetTotal <= 0) return false;

        int basePrice = Math.max(1, config.combatGeFoodBasePrice());

        debug("[GE-Food][Giants] start restock: totaal " + targetTotal + "x " + itemName + " (inv→doel) @" + basePrice);

        GeRestockHelper.RestockResult r = GeRestockHelper.buyWithEscalation(
                itemName,
                targetTotal,
                basePrice,
                config::botEnabled
        );
        if (r == GeRestockHelper.RestockResult.SUCCESS) {
            debug("GE food restock (Giants) OK: totaal " + targetTotal + "x " + itemName + " @" + basePrice);
            return true;
        }

        int increased = (int) Math.round(basePrice * 1.2);
        debug("[GE-Food][Giants] eerste poging geen SUCCESS, retry met +20% → " + increased);
        r = GeRestockHelper.buyWithEscalation(
                itemName,
                targetTotal,
                increased,
                config::botEnabled
        );
        if (r == GeRestockHelper.RestockResult.SUCCESS) {
            debug("GE food restock (Giants) OK (20% hoger): totaal " + targetTotal + "x " + itemName + " @" + increased);
            return true;
        }

        debug("GE food restock (Giants) FAILED voor " + itemName + " doel=" + targetTotal);
        return false;
    }

    /**
     * @param startHintForAny bij {@link CombatBotConfig.CombatGeFoodType#ANY}: eerste te proberen tier (bank miste dit);
     *                         {@code null} → {@link CombatFoodPriority#firstTargetWhenAnyAndBankUnknown()}.
     */
    private boolean tryGeRestockFood(int shortage, String startHintForAny) {
        if (shortage <= 0) return false;
        if (config.combatGeFoodType() == CombatBotConfig.CombatGeFoodType.ANY) {
            String start = startHintForAny;
            if (start == null || start.isEmpty()) {
                start = CombatFoodPriority.firstTargetWhenAnyAndBankUnknown();
            }
            int idx = CombatFoodPriority.indexInBestFirst(start);
            for (int i = idx; i < CombatFoodPriority.BEST_FIRST.length; i++) {
                if (tryGeRestockFoodOneItem(CombatFoodPriority.BEST_FIRST[i])) {
                    return true;
                }
            }
            return false;
        }
        String itemName = config.combatGeFoodType().toItemName();
        return itemName != null && tryGeRestockFoodOneItem(itemName);
    }

    /** Inv-check aan het begin van Giants (bovengronds): kijk wat we hebben en wat we nodig hebben; log (throttled). Bank hoeft niet open. */
    private void doInventoryCheckAtStart() {
        long now = System.currentTimeMillis();
        if (lastInvCheckLogTime > 0 && now - lastInvCheckLogTime < INV_CHECK_LOG_INTERVAL_MS) return;
        lastInvCheckLogTime = now;

        int foodHave = countFoodInInv();
        int foodNeed = getFoodAmount();
        boolean haveKey = Inventory.contains(BRASS_KEY);
        CombatBotConfig.ImpsCombatStyle style = config.giantsCombatStyle();
        boolean haveGear = hasWeaponForStyle(style);
        int coins = getInvCount("Coins");
        debug("[Inv] (begin Giants) Hebben: food=" + foodHave + ", brass key=" + haveKey + ", gear " + style + "=" + haveGear + ", coins=" + coins);
        debug("[Inv] Nodig: food=" + foodNeed + ", key=1, gear=" + style + " (uit instellingen)");
        if (foodHave < foodNeed) debug("[Inv] → moet banken: food ophalen (" + (foodNeed - foodHave) + ")");
        if (!haveKey) debug("[Inv] → brass key ophalen (GE of grond)");
        if (!haveGear) debug("[Inv] → moet banken: wapen/runes ophalen voor " + style);
        if (config.giantsCombatStyle() == CombatBotConfig.ImpsCombatStyle.MAGE) {
            String r = checkGiantsRuneSupply();
            if (r != null) debug("[Inv] → moet banken: " + r);
        }
        if (needsBankingFromInvCheck()) debug("[Inv] → conclusie: naar bank");
        else if (haveKey) debug("[Inv] → conclusie: alles aanwezig, naar dungeon");
    }

    /** Of we moeten banken op basis van inv: missen we food/gear/runes of is inv vol? (Bovengronds, key kan al aanwezig zijn.) */
    private boolean needsBankingFromInvCheck() {
        if (hasForeignItemsForGiants()) return true;
        // Altijd eerst het ingestelde food-aantal meenemen (ook bij volle HP — anders liep de bot bij herstart zonder food naar de dungeon).
        if (countFoodInInv() < getFoodAmount()) return true;
        // Bank/uitwijken zonder food enkel als we echt moeten eten.
        if (shouldEat() && !hasFoodToEat() && config.bankWhenNoFood()) return true;
        if (!hasWeaponForStyle(config.giantsCombatStyle())) return true;
        if (config.giantsCombatStyle() == CombatBotConfig.ImpsCombatStyle.MAGE && checkGiantsRuneSupply() != null) return true;
        if (Inventory.isFull()) return true;
        return false;
    }

    /** Vreemde items voor Giants: niet in keep-list, geen food, geen Giants-loot. */
    private boolean hasForeignItemsForGiants() {
        final String[] giantsLoot = getLootItems();
        return Inventory.getFirst(item -> {
            if (item == null || item.getName() == null) return false;
            String name = item.getName();
            // Keep-list + food zijn toegestaan
            for (String k : getGiantsStrictBankKeepNames()) {
                if (k.equalsIgnoreCase(name)) return false;
            }
            if (item.hasAction("Eat") || item.hasAction("Drink")) return false;
            // Giants loot is ook toegestaan (wordt door normale loot-bank flow afgehandeld)
            for (String l : giantsLoot) {
                if (l != null && l.equalsIgnoreCase(name)) return false;
            }
            return true;
        }) != null;
    }

    /** Inv-check: log wat we hebben en wat we nodig hebben (instellingen). Roep aan met bank open. */
    private void doInventoryCheckAndLog() {
        int foodHave = countFoodInInv();
        int foodNeed = getFoodAmount();
        boolean haveKey = Inventory.contains(BRASS_KEY);
        CombatBotConfig.ImpsCombatStyle style = config.giantsCombatStyle();
        boolean haveGear = hasWeaponForStyle(style);
        int coins = getInvCount("Coins");
        debug("[Inv] Hebben: food=" + foodHave + ", brass key=" + haveKey + ", gear " + style + "=" + haveGear + ", coins=" + coins);
        debug("[Inv] Nodig: food=" + foodNeed + ", key=1, gear=" + style + " (uit instellingen)");
        if (foodHave < foodNeed) debug("[Inv] → food ophalen: " + (foodNeed - foodHave));
        if (!haveKey) debug("[Inv] → brass key ophalen");
        if (!haveGear) debug("[Inv] → wapen/runes ophalen voor " + style);
    }

    private int countFoodInInv() {
        int count = 0;
        for (IInventoryItem item : Inventory.getAll()) {
            if (item != null && item.getName() != null && (item.hasAction("Eat") || item.hasAction("Drink")))
                count += item.getQuantity();
        }
        return count;
    }

    /** Pak een bestaand food-type uit inventory als top-up voorkeur (bijv. Anchovy pizza). */
    private String getPreferredInventoryFoodName() {
        IInventoryItem existingFood = Inventory.getFirst(item ->
                item != null && item.getName() != null && (item.hasAction("Eat") || item.hasAction("Drink")));
        return existingFood != null ? existingFood.getName() : null;
    }

    /** Haal alle gear voor deze style in één keer (één bank sessie, zoals Imps). */
    private void withdrawAllGearForStyleInOneGo(CombatBotConfig.ImpsCombatStyle style) {
        switch (style) {
            case MELEE: {
                String[] meleeWeapons = {"Rune scimitar", "Adamant scimitar", "Mithril scimitar", "Steel scimitar", "Iron scimitar", "Bronze scimitar", "Scimitar"};
                for (String w : meleeWeapons) {
                    if (Bank.contains(w)) {
                        Bank.withdraw(w, 1);
                        sleep(500, 800);
                        debug("[Bank] wapen " + w);
                        return;
                    }
                }
                break;
            }
            case RANGED: {
                if (Bank.contains("Shortbow")) {
                    Bank.withdraw("Shortbow", 1);
                    sleep(500, 800);
                } else if (Bank.contains("Longbow")) {
                    Bank.withdraw("Longbow", 1);
                    sleep(500, 800);
                }
                String[] arrowTypes = {"Rune arrow", "Adamant arrow", "Mithril arrow", "Steel arrow", "Iron arrow", "Bronze arrow"};
                for (String arrow : arrowTypes) {
                    if (Bank.contains(arrow)) {
                        Bank.withdraw(arrow, Integer.MAX_VALUE);
                        sleep(500, 800);
                        break;
                    }
                }
                debug("[Bank] bow + pijlen (alle)");
                break;
            }
            case MAGE:
                withdrawAllMageGearInOneGo();
                break;
            default:
                break;
        }
    }

    /** Alle mage gear in één sessie (zoals Imps withdrawMageGear): staff + alle runes. */
    private void withdrawAllMageGearInOneGo() {
        CombatBotConfig.ImpsMageSpell spell = config.giantsMageSpell();
        try {
            if (Skills.getLevel(Skill.MAGIC) < spell.getLevelReq()) return;
        } catch (Exception e) { return; }

        boolean hasStaff = Equipment.contains(i -> i != null && i.getName() != null && i.getName().toLowerCase().contains("staff"))
                || Inventory.contains(i -> i != null && i.getName() != null && i.getName().toLowerCase().contains("staff"));
        if (!hasStaff) {
            String preferred = spell.getPreferredStaff();
            if (Bank.contains(preferred)) {
                Bank.withdraw(preferred, 1);
                sleep(500, 800);
            } else {
                String[] staves = {"Staff of air", "Staff of fire", "Staff of water", "Staff of earth",
                        "Mystic air staff", "Mystic fire staff", "Mystic water staff", "Mystic earth staff", "Staff"};
                for (String s : staves) {
                    if (Bank.contains(s)) {
                        Bank.withdraw(s, 1);
                        sleep(500, 800);
                        break;
                    }
                }
            }
        }
        String catalyst = spell.getCatalystRune();
        if (getInvCount(catalyst) < 30 && Bank.contains(catalyst)) {
            Bank.withdraw(catalyst, Integer.MAX_VALUE);
            sleep(500, 800);
        }
        String elemental = spell.getElementalRune();
        String element = elemental.toLowerCase().replace(" rune", "");
        if (!hasStaffWithElement(element) && getInvCount(elemental) < 30 && Bank.contains(elemental)) {
            Bank.withdraw(elemental, Integer.MAX_VALUE);
            sleep(500, 800);
        }
        if (spell.needsAirRune() && !hasStaffWithElement("air") && getInvCount("Air rune") < 30 && Bank.contains("Air rune")) {
            Bank.withdraw("Air rune", Integer.MAX_VALUE);
            sleep(500, 800);
        }
        debug("[Bank] mage gear compleet: " + spell.getSpellName());
    }

    private boolean shouldEat() {
        try {
            int hp = Skills.getBoostedLevel(Skill.HITPOINTS);
            int maxHp = Skills.getLevel(Skill.HITPOINTS);
            if (maxHp <= 0) return false;
            double percent = (hp * 100.0) / maxHp;
            return percent <= config.eatPercent();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasFoodToEat() {
        return Inventory.contains(item ->
                item != null && item.getName() != null
                        && (item.hasAction("Eat") || item.hasAction("Drink")));
    }

    /** Aantal food om op te halen; minimaal 1 ook als "Aantal food" in instellingen op 0 staat. */
    private int getFoodAmount() {
        return Math.max(1, config.foodAmount());
    }

    private boolean isInCombat() {
        IPlayer player = Players.getLocal();
        if (player == null) return false;
        WorldPoint myPos = player.getWorldLocation();
        if (myPos == null) return false;

        // 1) Eigen aanval-animatie is leidend: dan zijn we echt in actieve combat.
        // Alleen "interacting" is te breed en kan leiden tot stilstaan/lusjes.
        if (player.getAnimation() != -1) return true;
        if (player.isInteracting()) {
            // Interacting zonder animatie behandelen we NIET als vaste combat-lock.
            // Zo kunnen we opnieuw klikken als giant vastzit of ons niet echt kan raken.
            return false;
        }

        // 2) Als we automatisch worden aangevallen: NPC interageert met ons EN kan ons praktisch bereiken.
        //    We markeren dit alleen als "in combat" wanneer onze animatie ook loopt.
        //    Anders mag de bot opnieuw "Attack" klikken (zoals door gebruiker gevraagd).
        String monsterName = config.giantsMonsterName();
        List<INPC> attackers = NPCs.getAll(npc ->
                npc != null
                        && npc.getName() != null
                        && npc.getName().equalsIgnoreCase(monsterName)
                        && !npc.isDead()
                        && npc.getInteracting() != null
                        && npc.getInteracting() == player
                        && npc.getWorldLocation() != null
                        && npc.getWorldLocation().distanceTo(HILL_GIANTS_CENTER) <= HILL_GIANTS_RADIUS
                        && npc.getWorldLocation().distanceTo(myPos) <= 2
        );
        if (attackers == null || attackers.isEmpty()) return false;

        for (INPC attacker : attackers) {
            if (attacker == null || attacker.getWorldLocation() == null) continue;
            int pathDist = Movement.calculateDistance(myPos, attacker.getWorldLocation());
            // -1/extreem groot = pad niet bruikbaar/geen route; dan niet als actieve combat behandelen.
            if (pathDist >= 0 && pathDist <= 4 && player.getAnimation() != -1) {
                return true;
            }
        }
        return false;
    }

    private boolean hasLootNearby() {
        IPlayer player = Players.getLocal();
        if (player == null) return false;
        WorldPoint myPos = player.getWorldLocation();
        String[] lootNames = getLootItems();
        if (config.lootOnlyOwn()) {
            var mineItems = TileItems.getAllMine();
            if (mineItems == null) return false;
            for (ITileItem item : mineItems) {
                if (item == null || item.getName() == null || item.getWorldLocation() == null) continue;
                if (isGroundLootTileExcluded(item.getWorldLocation())) continue;
                boolean match = false;
                for (String name : lootNames) {
                    if (item.getName().equalsIgnoreCase(name)) {
                        match = true;
                        break;
                    }
                }
                if (match && item.getWorldLocation().distanceTo(myPos) <= HILL_GIANTS_RADIUS + 3) return true;
            }
            return false;
        } else {
            for (String name : lootNames) {
                ITileItem item = TileItems.getNearest(i ->
                        i != null && i.getName() != null
                                && i.getWorldLocation() != null
                                && !isGroundLootTileExcluded(i.getWorldLocation())
                                && i.getName().equalsIgnoreCase(name)
                                && i.getWorldLocation().distanceTo(myPos) <= HILL_GIANTS_RADIUS + 3);
                if (item != null) return true;
            }
            return false;
        }
    }

    private boolean hasSpecialLootNearby() {
        List<String> special = getSpecialLootNames();
        if (special == null || special.isEmpty()) return false;
        IPlayer player = Players.getLocal();
        if (player == null) return false;
        WorldPoint myPos = player.getWorldLocation();

        ITileItem item = TileItems.getNearest(i ->
                i != null && i.getName() != null
                        && i.getWorldLocation() != null
                        && !isGroundLootTileExcluded(i.getWorldLocation())
                        && special.stream().anyMatch(n -> n.equalsIgnoreCase(i.getName()))
                        && i.getWorldLocation().distanceTo(myPos) <= HILL_GIANTS_RADIUS + 3);
        return item != null;
    }

    private List<String> getSpecialLootNames() {
        String raw = config.specialLootItems();
        if (raw == null || raw.trim().isEmpty()) return new ArrayList<>();
        List<String> list = new ArrayList<>();
        for (String s : raw.split(",")) {
            if (s == null) continue;
            String t = s.trim();
            if (t.isEmpty()) continue;
            if (!list.contains(t)) list.add(t);
        }
        return list;
    }

    private ITileItem pickNearestFromMine(String[] names, WorldPoint myPos) {
        if (names == null || names.length == 0 || myPos == null) return null;
        var mineItems = TileItems.getAllMine();
        if (mineItems == null) return null;

        ITileItem best = null;
        int bestDist = Integer.MAX_VALUE;
        for (ITileItem item : mineItems) {
            if (item == null || item.getName() == null || item.getWorldLocation() == null) continue;
            boolean match = false;
            for (String name : names) {
                if (item.getName().equalsIgnoreCase(name)) {
                    match = true;
                    break;
                }
            }
            if (!match) continue;
            if (isGroundLootTileExcluded(item.getWorldLocation())) continue;

            int d = item.getWorldLocation().distanceTo(myPos);
            if (d > HILL_GIANTS_RADIUS + 3) continue;
            if (d < bestDist) {
                bestDist = d;
                best = item;
            }
        }
        return best;
    }

    /** Mag dit loot-item nu gepakt worden? Bij volle inventory alleen stackables die we al hebben. */
    private boolean canLootItemNow(ITileItem item) {
        if (item == null || item.getName() == null) return false;
        if (!Inventory.isFull()) return true;

        String name = item.getName();
        if (!isMineLootItem(item)) return false;
        if (Inventory.getCount(true, name) <= 0) return false;
        return looksStackableByName(name);
    }

    /**
     * Maak ruimte vrij voor loot:
     * - Eet alleen als HP niet vol is (geen onnodig eten)
     * - Begraaf bones/ashes waar mogelijk
     * Return >0 = actie gedaan (delay), 0 = geen ruimte kunnen maken.
     */
    private int tryMakeSpaceForLootWhenFull() {
        if (!Inventory.isFull()) return 0;

        // 1) Begraaf eerst als dat kan (directe inv-slot vrijmaken zonder food te verspillen)
        int buryDelay = buryOneBoneIfReady();
        if (buryDelay > 0) return buryDelay;

        // 2) Alleen eten als HP niet vol is
        if (!isHpFull()) {
            IInventoryItem food = Inventory.getFirst(item ->
                    item != null && item.getName() != null
                            && (item.hasAction("Eat") || item.hasAction("Drink")));
            if (food != null) {
                if (food.hasAction("Eat")) food.interact("Eat");
                else food.interact("Drink");
                return 900 + random.nextInt(500);
            }
        }

        // Geen ruimte kunnen maken -> caller laat loot liggen en gaat verder.
        return 0;
    }

    /** Bury helper met cooldown zodat we niet dubbel op dezelfde bone klikken. */
    private int buryOneBoneIfReady() {
        long now = System.currentTimeMillis();
        if (now - lastBuryActionMs < BURY_ACTION_COOLDOWN_MS) return 0;

        IInventoryItem bone = Inventory.getFirst(item -> {
            if (item == null || item.getName() == null) return false;
            String n = item.getName();
            for (String b : BONE_NAMES) {
                if (n.equalsIgnoreCase(b)) return true;
            }
            return false;
        });
        if (bone == null || !bone.hasAction("Bury")) return 0;

        bone.interact("Bury");
        lastBuryActionMs = now;
        return 600 + random.nextInt(350);
    }

    private boolean isHpFull() {
        try {
            int hp = Skills.getBoostedLevel(Skill.HITPOINTS);
            int maxHp = Skills.getLevel(Skill.HITPOINTS);
            return maxHp > 0 && hp >= maxHp;
        } catch (Exception e) {
            return true;
        }
    }

    /** Alleen "mijn" grondloot accepteren (ownership check) voor strikte full-inv stack-mode. */
    private boolean isMineLootItem(ITileItem item) {
        if (item == null || item.getWorldLocation() == null || item.getName() == null) return false;
        List<ITileItem> mine = TileItems.getAllMine();
        if (mine == null || mine.isEmpty()) return false;
        for (ITileItem m : mine) {
            if (m == null || m.getWorldLocation() == null || m.getName() == null) continue;
            if (!m.getName().equalsIgnoreCase(item.getName())) continue;
            if (m.getId() != item.getId()) continue;
            if (!m.getWorldLocation().equals(item.getWorldLocation())) continue;
            return true;
        }
        return false;
    }

    /** Simpele stackable detectie op itemnaam (veilig voor Giants-loot en currency/runes/ammo). */
    private boolean looksStackableByName(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        return n.equals("coins")
                || n.endsWith(" rune")
                || n.endsWith(" arrow")
                || n.contains("bolt")
                || n.contains("dart")
                || n.contains("knife")
                || n.contains("javelin")
                || n.contains("tip")
                || n.contains("feather")
                || n.contains("grimy");
    }

    /** Registreer lootwaarde in paint met GE-prijs (fallback HA-prijs). */
    private void recordLootToPaint(ITileItem item) {
        if (item == null) return;
        String name = item.getName();
        int qty = Math.max(1, item.getQuantity());

        // Voor Giants waarde-rapportage: bones/ashes niet meetellen in GP-waarde.
        if (isExcludedFromLootValue(name)) {
            if (name != null) paint.addLoot(name, qty, 0);
            else paint.addLoot(0);
            return;
        }

        int valueEach = -1;
        try {
            valueEach = Prices.getItemPrice(item.getId());
        } catch (Exception ignored) {
        }
        if (valueEach <= 0) valueEach = Math.max(0, item.getHaPrice());
        int total = Math.max(0, valueEach * qty);

        if (name != null) paint.addLoot(name, qty, total);
        else paint.addLoot(total);
    }

    private boolean isExcludedFromLootValue(String name) {
        if (name == null) return false;
        return name.equalsIgnoreCase("Big bones")
                || name.equalsIgnoreCase("Bones")
                || name.equalsIgnoreCase("Fiendish ashes");
    }

    private String[] getLootItems() {
        String configLoot = config.giantsLootItems();
        if (configLoot == null || configLoot.trim().isEmpty()) return DEFAULT_GIANTS_LOOT;
        List<String> list = new ArrayList<>();
        for (String s : configLoot.split(",")) {
            String t = s.trim();
            if (!t.isEmpty() && !list.contains(t)) list.add(t);
        }
        return list.isEmpty() ? DEFAULT_GIANTS_LOOT : list.toArray(new String[0]);
    }

    /** Telt aantal loot-items (uit getLootItems()) in inventory. */
    private int countLootItemsInInv() {
        int count = 0;
        for (String name : getLootItems()) {
            count += Inventory.getCount(true, name);
        }
        return count;
    }

    private boolean hasWeaponForStyle(CombatBotConfig.ImpsCombatStyle style) {
        switch (style) {
            case MELEE:
                return Inventory.contains(item -> item != null && item.getName() != null && isMeleeWeapon(item.getName()))
                        || Equipment.contains(item -> item != null && item.getName() != null && isMeleeWeapon(item.getName()));
            case RANGED:
                return Equipment.contains(i -> i != null && i.getName() != null && i.getName().toLowerCase().contains("bow"))
                        || Inventory.contains(i -> i != null && i.getName() != null && i.getName().toLowerCase().contains("bow"));
            case MAGE: {
                CombatBotConfig.ImpsMageSpell spell = config.giantsMageSpell();
                try {
                    if (Skills.getLevel(Skill.MAGIC) < spell.getLevelReq()) return false;
                } catch (Exception e) { return false; }
                boolean hasStaff = Equipment.contains(i -> i != null && i.getName() != null && i.getName().toLowerCase().contains("staff"))
                        || Inventory.contains(i -> i != null && i.getName() != null && i.getName().toLowerCase().contains("staff"));
                if (!hasStaff) return false;
                if (getInvCount(spell.getCatalystRune()) < 50) return false;
                String element = spell.getElementalRune().toLowerCase().replace(" rune", "");
                if (!hasStaffWithElement(element) && getInvCount(spell.getElementalRune()) < 50) return false;
                if (spell.needsAirRune() && !hasStaffWithElement("air") && getInvCount("Air rune") < 50) return false;
                return true;
            }
            default:
                return false;
        }
    }

    private boolean hasStaffWithElement(String element) {
        return Equipment.contains(item -> {
            if (item == null || item.getName() == null) return false;
            String n = item.getName().toLowerCase();
            return n.contains(element) && n.contains("staff");
        }) || Inventory.contains(item -> {
            if (item == null || item.getName() == null) return false;
            String n = item.getName().toLowerCase();
            return n.contains(element) && n.contains("staff");
        });
    }

    /** Zoals Imps: null = genoeg runes, anders waarschuwingsstring (dan naar bank). */
    private String checkGiantsRuneSupply() {
        CombatBotConfig.ImpsMageSpell spell = config.giantsMageSpell();
        if (getInvCount(spell.getCatalystRune()) < 30)
            return "Te weinig " + spell.getCatalystRune();
        String element = spell.getElementalRune().toLowerCase().replace(" rune", "");
        if (!hasStaffWithElement(element) && getInvCount(spell.getElementalRune()) < 30)
            return "Te weinig " + spell.getElementalRune();
        if (spell.needsAirRune() && !hasStaffWithElement("air") && getInvCount("Air rune") < 30)
            return "Te weinig Air rune";
        return null;
    }

    private boolean isMeleeWeapon(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        return n.contains("scimitar") || n.contains("sword") || n.contains("dagger") || n.contains("mace")
                || n.contains("axe") || n.contains("halberd") || n.contains("longsword") || n.contains("battleaxe") || n.contains("warhammer");
    }

    private void withdrawMageGearGiants() {
        CombatBotConfig.ImpsMageSpell spell = config.giantsMageSpell();
        try {
            if (Skills.getLevel(Skill.MAGIC) < spell.getLevelReq()) {
                debug("Magic level " + Skills.getLevel(Skill.MAGIC) + " < " + spell.getLevelReq() + " voor " + spell.getSpellName());
                return;
            }
        } catch (Exception e) { return; }

        boolean hasStaff = Equipment.contains(i -> i != null && i.getName() != null && i.getName().toLowerCase().contains("staff"))
                || Inventory.contains(i -> i != null && i.getName() != null && i.getName().toLowerCase().contains("staff"));
        if (!hasStaff) {
            String preferred = spell.getPreferredStaff();
            if (Bank.contains(preferred)) {
                Bank.withdraw(preferred, 1);
                debug("Gear prep: staff " + preferred);
                return;
            }
            String[] staves = {"Staff of air", "Staff of fire", "Staff of water", "Staff of earth", "Staff"};
            for (String s : staves) {
                if (Bank.contains(s)) {
                    Bank.withdraw(s, 1);
                    debug("Gear prep: staff " + s);
                    return;
                }
            }
            return;
        }
        // Alle benodigde runes pakken (alles wat in de bank zit)
        String catalyst = spell.getCatalystRune();
        if (getInvCount(catalyst) < 30 && Bank.contains(catalyst)) {
            Bank.withdraw(catalyst, Integer.MAX_VALUE);
            sleep(500, 800);
            debug("Gear prep: " + catalyst + " (alle)");
            return;
        }
        String elemental = spell.getElementalRune();
        String element = elemental.toLowerCase().replace(" rune", "");
        if (!hasStaffWithElement(element) && getInvCount(elemental) < 30 && Bank.contains(elemental)) {
            Bank.withdraw(elemental, Integer.MAX_VALUE);
            sleep(500, 800);
            debug("Gear prep: " + elemental + " (alle)");
            return;
        }
        if (spell.needsAirRune() && !hasStaffWithElement("air") && getInvCount("Air rune") < 30 && Bank.contains("Air rune")) {
            Bank.withdraw("Air rune", Integer.MAX_VALUE);
            sleep(500, 800);
            debug("Gear prep: Air rune (alle)");
            return;
        }
    }

    private void withdrawWeaponForStyle(CombatBotConfig.ImpsCombatStyle style) {
        switch (style) {
            case MELEE:
                String[] meleeWeapons = {"Rune scimitar", "Adamant scimitar", "Mithril scimitar", "Steel scimitar", "Iron scimitar", "Bronze scimitar", "Scimitar"};
                for (String w : meleeWeapons) {
                    if (Bank.contains(w)) {
                        Bank.withdraw(w, 1);
                        debug("Gear prep: wapen " + w + " opgehaald");
                        return;
                    }
                }
                break;
            case RANGED:
                if (Bank.contains("Shortbow") || Bank.contains("Longbow")) {
                    Bank.withdraw(Bank.contains("Shortbow") ? "Shortbow" : "Longbow", 1);
                    if (Bank.contains("Arrow")) Bank.withdraw("Arrow", 100);
                    else if (Bank.contains("Iron arrow")) Bank.withdraw("Iron arrow", 100);
                    debug("Gear prep: bow + pijlen opgehaald");
                }
                break;
            case MAGE:
                withdrawMageGearGiants();
                break;
            default:
                break;
        }
    }

    private int countBuryableItems() {
        int count = 0;
        for (String boneName : BONE_NAMES) {
            count += getInvCount(boneName);
        }
        return count;
    }

    private int getInvCount(String name) {
        if (name == null || name.isEmpty()) return 0;
        return Inventory.getCount(true, name);
    }

    private void sleep(int minMs, int maxMs) {
        try {
            if (maxMs <= minMs) {
                Thread.sleep(minMs);
                return;
            }
            Thread.sleep(minMs + random.nextInt(maxMs - minMs));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean shouldAbortActions() {
        return config == null || !config.botEnabled();
    }
}
