package com.combatbot;

import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.util.Text;
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

    /** Dynamische keep-list voor Giants (zoals Imps getFullKeepList): spell-runes + style-armor + wapens. */
    private List<String> getGiantsKeepList() {
        List<String> keep = new ArrayList<>(Arrays.asList(GIANTS_KEEP_BASE));
        CombatBotConfig.ImpsCombatStyle style = giantsCombatStyleBaselineForAccount();
        for (String armour : StyleArmourBankHelper.armourItemNamesForStyle(style)) {
            addGiantsKeepName(keep, armour);
        }
        if (style == CombatBotConfig.ImpsCombatStyle.MELEE) {
            for (String w : MELEE_TIER) {
                addGiantsKeepName(keep, w);
            }
        } else if (style == CombatBotConfig.ImpsCombatStyle.RANGED) {
            addGiantsKeepName(keep, "Shortbow");
            addGiantsKeepName(keep, "Oak shortbow");
            addGiantsKeepName(keep, "Willow shortbow");
            addGiantsKeepName(keep, "Maple shortbow");
            addGiantsKeepName(keep, "Yew shortbow");
            addGiantsKeepName(keep, "Magic shortbow");
            addGiantsKeepName(keep, "Longbow");
            addGiantsKeepName(keep, "Oak longbow");
            addGiantsKeepName(keep, "Willow longbow");
            addGiantsKeepName(keep, "Maple longbow");
            addGiantsKeepName(keep, "Yew longbow");
            addGiantsKeepName(keep, "Magic longbow");
        } else if (style == CombatBotConfig.ImpsCombatStyle.MAGE) {
            CombatBotConfig.ImpsMageSpell spell = config.giantsMageSpell();
            if (!keep.contains(spell.getElementalRune())) keep.add(spell.getElementalRune());
            if (!keep.contains(spell.getCatalystRune())) keep.add(spell.getCatalystRune());
            if (spell.needsAirRune() && !keep.contains("Air rune")) keep.add("Air rune");
            addGiantsKeepName(keep, spell.getPreferredStaff());
            addGiantsKeepName(keep, "Staff of air");
            addGiantsKeepName(keep, "Staff of fire");
            addGiantsKeepName(keep, "Staff of water");
            addGiantsKeepName(keep, "Staff of earth");
            addGiantsKeepName(keep, "Staff");
        }
        return keep;
    }

    private static void addGiantsKeepName(List<String> keep, String name) {
        if (name == null || name.isEmpty()) {
            return;
        }
        for (String k : keep) {
            if (k.equalsIgnoreCase(name)) {
                return;
            }
        }
        keep.add(name);
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
                addGiantsKeepName(keep, item.getName());
            }
        }
        // Uitrusting mag niet per ongeluk gedeponeerd worden tijdens deposit-fase.
        try {
            var equipped = Equipment.getAll(item -> item != null && item.getName() != null);
            if (equipped != null) {
                for (var eq : equipped) {
                    addGiantsKeepName(keep, eq.getName());
                }
            }
        } catch (Exception ignored) {
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
        CombatBotConfig.ImpsCombatStyle style = giantsCombatStyleBaselineForAccount();
        if (StyleArmourBankHelper.canWear(style, name)) return true;
        if (StyleArmourBankHelper.isCosmeticOrNonCombatFootwear(name)) return false;
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
    /**
     * Anti-stuck: laatste keer dat de speler een combat-animatie liet zien (slaan/casten).
     * Wordt gebruikt om los te breken uit een "giant target ons maar kan niet raken" deadlock —
     * dan staat isInCombat() op true (NPC heeft ons als interacting target) maar gebeurt er niets.
     * Als deze tijd langer dan {@link #combatStuckThresholdMs} achterloopt → forceer een Attack
     * op de blokkerende giant zodat we ofwel verplaatsen ofwel daadwerkelijk gaan vechten.
     */
    private long lastPlayerCombatAnimMs = 0;
    /** Random drempel 3-8s, opnieuw gegenereerd na elke succesvolle anti-stuck actie. */
    private long combatStuckThresholdMs = 3000L + new Random().nextInt(5000);
    private long lastCombatStuckBreakoutMs = 0;
    private static final long COMBAT_STUCK_BREAKOUT_COOLDOWN_MS = 1800;

    // Banking state
    private WorldPoint preBankPosition = null;
    private boolean walkingBackAfterBank = false;
    /** Bovengronds: korte prioriteit voor bank-route na needsBanking (voorkomt pingpong shed ↔ GE bij 1-tick inv/equip fluctuaties). */
    private long giantsSurfaceBankRouteHoldUntilMs = 0;
    private static final long GIANTS_SURFACE_BANK_ROUTE_HOLD_MS = 12_000L;

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

    private String giantsLocalRsnOrNull() {
        try {
            IPlayer local = Players.getLocal();
            if (local == null || local.getName() == null || local.getName().trim().isEmpty()) {
                return null;
            }
            return Text.removeTags(local.getName()).trim();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Globale Giants-stijl + optionele per-account override (Accounts → Bewerken). */
    private CombatBotConfig.ImpsCombatStyle giantsCombatStyleBaselineForAccount() {
        String rsn = giantsLocalRsnOrNull();
        if (rsn == null || rsn.isEmpty()) {
            return config.giantsCombatStyle();
        }
        return ManagedJagexAccountsStore.resolveGiantsCombatStyleForDisplayName(config, rsn);
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
        giantsSurfaceBankRouteHoldUntilMs = 0;
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
                // Fase 1 = withdraw food/coins/key + bank sluiten + naar GE. Dat hoort in
                // {@link #handleBuyingKeyAtGe()}, niet in {@link #handleBanking()}: anders blijft de bank
                // open met "key=false → bank open laten" en komt de GE-koop nooit van de grond.
                return State.BUYING_KEY_AT_GE;
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
            long nowMs = System.currentTimeMillis();
            if (Bank.isOpen()) {
                giantsSurfaceBankRouteHoldUntilMs = 0;
                return State.BANKING;
            }
            if (needsBankingFromInvCheck()) {
                giantsSurfaceBankRouteHoldUntilMs = nowMs + GIANTS_SURFACE_BANK_ROUTE_HOLD_MS;
            }
            boolean bankRoutePriority = needsBankingFromInvCheck() || nowMs < giantsSurfaceBankRouteHoldUntilMs;

            // Bank-route vóór "terug naar dungeon": anders pingpong tussen Varrock shed (~3115,3452) en GE-bank
            // wanneer needsBanking kort flipt of walkingBackAfterBank nog true is.
            if (bankRoutePriority) {
                if (walkingBackAfterBank) {
                    walkingBackAfterBank = false;
                    debug("Giants: bank-route heeft voorrang — terug naar dungeon uitgesteld");
                }
                return State.WALKING_TO_BANK;
            }

            if (walkingBackAfterBank && !needsBankingFromInvCheck()) {
                return State.RETURNING_TO_DUNGEON;
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

            // Brass key aanwezig — bank-route hierboven al afgehandeld (bankRoutePriority)
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

        // Giants-eigen "bank voor food"-flow: onafhankelijk van globale bankWhenNoFood.
        // Triggert proactief (ongeacht HP) zodra food in inv onder de drempel komt — zo
        // voorkomen we paniek-eat-loops op lage HP en lange runs zonder food.
        if (config.giantsBankForFood()) {
            int threshold = Math.max(1, config.giantsLowFoodBankThreshold());
            int foodNow = countFoodInInv();
            if (foodNow < threshold) {
                debug("[Giants] food=" + foodNow + " < drempel " + threshold + " → terug naar bank");
                return Bank.isOpen() ? State.BANKING : State.WALKING_TO_BANK;
            }
        }

        // Fallback: als we geen food hebben en HP onder drempel komt, gebruik de globale switches.
        if (!hasFoodToEat() && shouldEat()) {
            if (config.bankWhenNoFood() || config.giantsBankForFood()) {
                return Bank.isOpen() ? State.BANKING : State.WALKING_TO_BANK;
            } else if (config.disableCombatNoFood()) {
                return State.PAUSED_NO_FOOD;
            }
        }

        // MAGE: te weinig runes → naar bank (nooit casten zonder runes)
        if (giantsCombatStyleBaselineForAccount() == CombatBotConfig.ImpsCombatStyle.MAGE) {
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
            InventoryActionHelper.interact(config, food, "Eat");
            return 1200 + random.nextInt(600);
        }
        IInventoryItem drink = Inventory.getFirst(item ->
                item != null && item.getName() != null && item.hasAction("Drink"));
        if (drink != null) {
            InventoryActionHelper.interact(config, drink, "Drink");
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
                if (!canLootItemNow(mine)) {
                    lootWindowEnd = 0;
                    killsSinceLastLoot = 0;
                    debug("Loot skip: " + mine.getName() + " niet lootbaar nu (invFull="
                            + Inventory.isFull() + ", stackable=" + looksStackableByName(mine.getName()) + ")");
                    return 600;
                }
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

        long nowFightTick = System.currentTimeMillis();

        // Track laatste combat-animatie zodat we een "blocked giant"-deadlock kunnen detecteren.
        if (player.getAnimation() != -1) {
            lastPlayerCombatAnimMs = nowFightTick;
        }

        // Al in combat?
        if (isInCombat()) {
            targetWasAlive = true;

            // Anti-stuck: giant target ons maar kan ons niet raken (object ertussen, pad geblokkeerd).
            // Symptoom: isInCombat()=true (NPC.getInteracting()==player) maar player.animation=-1
            // langer dan onze random drempel. Forceer dan een Attack op de blokkerende giant —
            // de bot loopt dan automatisch naar een tile waar hij wél kan slaan.
            int stuckBreakDelay = maybeBreakOutOfStuckCombat(player, nowFightTick);
            if (stuckBreakDelay > 0) return stuckBreakDelay;

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
        if (giantsCombatStyleBaselineForAccount() == CombatBotConfig.ImpsCombatStyle.MAGE) {
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
                        if (InventoryEquipHelper.tryWieldOrWear(config, staff)) {
                            sleep(800, 1200);
                            return 1000;
                        }
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
                    VarrockTeleportHelper.prepareVarrockTeleport(config);

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
        for (int depPass = 0; depPass < 4 && hasGiantsStrictDepositItems(); depPass++) {
            depositForeignGiantsInventoryItems();
            BankDepositHelper.depositAllExceptKeep(keepNames.toArray(new String[0]));
            sleep(450, 750);
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
        CombatBotConfig.ImpsCombatStyle style = giantsCombatStyleBaselineForAccount();
        if (!hasWeaponForStyle(style)) {
            withdrawAllGearForStyleInOneGo(style);
        }
        // Stap 3b: Armor per style. Optioneel: als armor ontbreekt, gaan we niet loopen/blokkeren.
        withdrawBestArmourForStyleInOneGo(style);

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

        // Zodra we naar GE gaan voor de key: meteen alle coins meenemen. Anders blijft inv op bv. 90 gp
        // terwijl er honduizenden op de bank staan — de log leek dan alsof er geen geld was.
        if (geBuyStep == 1 && Bank.contains("Coins")) {
            int needGp = Math.max(500, Math.max(config.giantsBrassKeyPriceMin(), config.giantsBrassKeyPriceMax()));
            if (getInvCount("Coins") < needGp) {
                Bank.withdrawAll("Coins");
                sleep(400, 700);
                debug("[Bank] GE brass key: withdrawAll Coins (inv < " + needGp + " gp, bank had stack)");
            }
        }

        // Stap 5: Amulet of power (gear prep voor elke style)
        if (!hasAmuletOfPowerEquippedOrInInv() && Bank.contains("Amulet of power")) {
            Bank.withdraw("Amulet of power", 1);
            sleep(500, 800);
        }

        // Stap 5b: Melee — equip het beste melee-wapen uit inv zodra het strikt beter is dan wat
        // we nu dragen (of als we niets dragen). Voorkomt dat handleBanking opnieuw deposits +
        // withdraws van hetzelfde wapen doet (de eerdere loop) en regelt meteen "upgrade" als er
        // een hoger-tier wapen uit de bank kwam.
        if (style == CombatBotConfig.ImpsCombatStyle.MELEE) {
            String equippedMelee = null;
            for (String w : MELEE_TIER) {
                if (Equipment.contains(item -> item != null && item.getName() != null
                        && w.equalsIgnoreCase(item.getName()))) {
                    equippedMelee = w;
                    break;
                }
            }
            String bestInvMelee = null;
            IInventoryItem bestInvItem = null;
            for (String w : MELEE_TIER) {
                IInventoryItem hit = Inventory.getFirst(item -> item != null && item.getName() != null
                        && w.equalsIgnoreCase(item.getName()));
                if (hit != null) {
                    bestInvMelee = w;
                    bestInvItem = hit;
                    break;
                }
            }
            int equippedIdx = meleeTierIndexCi(equippedMelee);
            int bestInvIdx = meleeTierIndexCi(bestInvMelee);
            boolean shouldEquipFromInv = bestInvItem != null
                    && (equippedIdx < 0 || bestInvIdx < equippedIdx);
            if (shouldEquipFromInv) {
                if (InventoryEquipHelper.tryWieldOrWear(config, bestInvItem)) {
                    debug("[Bank] Wield " + bestInvMelee
                            + (equippedMelee != null ? " (swap " + equippedMelee + " → " + bestInvMelee + ")" : " (auto-equip)"));
                    sleep(500, 800);
                } else {
                    debug("[Bank] skip melee wield " + bestInvMelee + " — need "
                            + InventoryEquipHelper.minFreeSlotsToWield(bestInvMelee) + " free slots");
                }
            }
        }

        // Stap 5c: Style-armor direct aantrekken (melee/ranged/mage), net als wapen/staff/amulet.
        equipBestArmourFromInventory(style);

        // Stap 6: Staff direct aantrekken als we die in inv hebben (MAGE)
        if (style == CombatBotConfig.ImpsCombatStyle.MAGE) {
            boolean staffEquipped = Equipment.contains(i -> i != null && i.getName() != null && i.getName().toLowerCase().contains("staff"));
            boolean staffInInv = Inventory.contains(i -> i != null && i.getName() != null && i.getName().toLowerCase().contains("staff"));
            if (staffInInv && !staffEquipped) {
                IInventoryItem staff = Inventory.getFirst(item -> item != null && item.getName() != null && item.getName().toLowerCase().contains("staff"));
                if (staff != null) {
                    if (InventoryEquipHelper.tryWieldOrWear(config, staff)) {
                        sleep(600, 1000);
                    }
                }
            }
        }

        // Stap 7: fallback amulet equippen als armor-tiering nog geen amulet heeft gedragen.
        boolean amuletEquipped = Equipment.contains(i -> i != null && i.getName() != null
                && i.getName().toLowerCase().contains("amulet"));
        if (!amuletEquipped && Inventory.contains(i -> i != null && i.getName() != null
                && i.getName().toLowerCase().contains("amulet"))) {
            IInventoryItem amulet = Inventory.getFirst(item -> item != null && item.getName() != null
                    && item.getName().toLowerCase().contains("amulet"));
            if (amulet != null) {
                InventoryActionHelper.interact(config, amulet, amulet.hasAction("Wear") ? "Wear" : "Wield");
                sleep(500, 800);
            }
        }

        // Stap 8: Sluit bank alleen als essentials echt compleet zijn (geen vreemde inv-items).
        int foodNow = countFoodInInv();
        boolean readyFood = foodNow >= foodNeed;
        boolean readyGear = hasWeaponForStyle(style);
        boolean readyKey = Inventory.contains(BRASS_KEY);
        boolean readyInv = !hasForeignItemsForGiants() && !hasGiantsStrictDepositItems();
        if (!readyFood || !readyGear || !readyKey || !readyInv) {
            debug("[Bank] Nog niet klaar: food=" + foodNow + "/" + foodNeed
                    + ", gear=" + readyGear + ", key=" + readyKey + ", invClean=" + readyInv
                    + " → bank open laten");
            if (!readyInv && Bank.isOpen()) {
                depositForeignGiantsInventoryItems();
                BankDepositHelper.depositAllExceptKeep(getGiantsStrictBankKeepNames().toArray(new String[0]));
            }
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
        giantsSurfaceBankRouteHoldUntilMs = 0;
        return 800;
    }

    private int handleReturningToDungeon() {
        IPlayer player = Players.getLocal();
        if (player == null) return 600;
        WorldPoint pos = player.getWorldLocation();

        if (pos.getY() > 9000) {
            walkingBackAfterBank = false;
            giantsSurfaceBankRouteHoldUntilMs = 0;
            isInDungeon = true;
            return 300;
        }

        hasBrassKey = Inventory.contains(BRASS_KEY);
        int result = handleWalkToEntrance();
        if (result > 0) return result;

        if (Players.getLocal() != null && Players.getLocal().getWorldLocation().getY() > 9000) {
            walkingBackAfterBank = false;
            giantsSurfaceBankRouteHoldUntilMs = 0;
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

    /**
     * Log-context voor coins: alleen inv is misleidend als er veel op de bank ligt.
     * {@link AccountStateJsonStore} wordt bij bank-open geüpdatet ({@code knownBankCoins}).
     */
    private String coinsContextForDebugLog() {
        int inv = getInvCount("Coins");
        if (Bank.isOpen()) {
            return "inv=" + inv + " (bank open — live data)";
        }
        String rsn = giantsLocalRsnOrNull();
        if (rsn == null || rsn.isEmpty()) {
            return "inv=" + inv;
        }
        AccountStateJsonStore.AccountEntry e = AccountStateJsonStore.getEntry(rsn);
        if (e == null || !e.bankCalibrated || e.knownBankCoins <= 0) {
            return "inv=" + inv;
        }
        return "inv=" + inv + ", laatste bank-snapshot(JSON)≈" + e.knownBankCoins + " gp";
    }

    /** Inv-check aan het begin van Giants (bovengronds): kijk wat we hebben en wat we nodig hebben; log (throttled). Bank hoeft niet open. */
    private void doInventoryCheckAtStart() {
        long now = System.currentTimeMillis();
        if (lastInvCheckLogTime > 0 && now - lastInvCheckLogTime < INV_CHECK_LOG_INTERVAL_MS) return;
        lastInvCheckLogTime = now;

        int foodHave = countFoodInInv();
        int foodNeed = getFoodAmount();
        boolean haveKey = Inventory.contains(BRASS_KEY);
        CombatBotConfig.ImpsCombatStyle style = giantsCombatStyleBaselineForAccount();
        boolean haveGear = hasWeaponForStyle(style);
        debug("[Inv] (begin Giants) Hebben: food=" + foodHave + ", brass key=" + haveKey + ", gear " + style + "=" + haveGear
                + ", coins " + coinsContextForDebugLog());
        debug("[Inv] Nodig: food=" + foodNeed + ", key=1, gear=" + style + " (uit instellingen)");
        if (foodHave < foodNeed) debug("[Inv] → moet banken: food ophalen (" + (foodNeed - foodHave) + ")");
        if (!haveKey) debug("[Inv] → brass key ophalen (GE of grond)");
        if (!haveGear) debug("[Inv] → moet banken: wapen/runes ophalen voor " + style);
        if (giantsCombatStyleBaselineForAccount() == CombatBotConfig.ImpsCombatStyle.MAGE) {
            String r = checkGiantsRuneSupply();
            if (r != null) debug("[Inv] → moet banken: " + r);
        }
        if (hasForeignItemsForGiants()) {
            debug("[Inv] → moet banken: vreemde items in inv (niet passend bij " + style + ")");
        }
        if (needsBankingFromInvCheck()) debug("[Inv] → conclusie: naar bank");
        else if (haveKey) debug("[Inv] → conclusie: alles aanwezig, naar dungeon");
    }

    /** Of we moeten banken op basis van inv: missen we food/gear/runes of is inv vol? (Bovengronds, key kan al aanwezig zijn.) */
    private boolean needsBankingFromInvCheck() {
        if (hasForeignItemsForGiants()) return true;
        // Altijd eerst het ingestelde food-aantal meenemen (ook bij volle HP — anders liep de bot bij herstart zonder food naar de dungeon).
        if (countFoodInInv() < getFoodAmount()) return true;
        // Giants-eigen drempel of globale "bank als food op"-switch — beide kunnen los aan.
        if (config.giantsBankForFood()) {
            int threshold = Math.max(1, config.giantsLowFoodBankThreshold());
            if (countFoodInInv() < threshold) return true;
        }
        if (shouldEat() && !hasFoodToEat()
                && (config.bankWhenNoFood() || config.giantsBankForFood())) return true;
        if (!hasWeaponForStyle(giantsCombatStyleBaselineForAccount())) return true;
        if (giantsCombatStyleBaselineForAccount() == CombatBotConfig.ImpsCombatStyle.MAGE && checkGiantsRuneSupply() != null) return true;
        if (Inventory.isFull()) return true;
        return false;
    }

    /** Vreemde items voor Giants: niet in keep-list, geen food, geen Giants-loot, geen style-armor/wapen. */
    private boolean hasForeignItemsForGiants() {
        final String[] giantsLoot = getLootItems();
        return Inventory.getFirst(item -> {
            if (item == null || item.getName() == null) return false;
            String name = item.getName();
            if (item.hasAction("Eat") || item.hasAction("Drink")) return false;
            if (shouldKeepItemGiants(item)) return false;
            for (String k : getGiantsKeepList()) {
                if (k.equalsIgnoreCase(name)) return false;
            }
            for (String l : giantsLoot) {
                if (l != null && l.equalsIgnoreCase(name)) return false;
            }
            return true;
        }) != null;
    }

    /** Deponeer expliciet items die niet bij Giants-style horen (bv. mage-chaps bij melee). */
    private void depositForeignGiantsInventoryItems() {
        if (!Bank.isOpen()) {
            return;
        }
        for (IInventoryItem item : Inventory.getAll()) {
            if (item == null || item.getName() == null) {
                continue;
            }
            if (item.hasAction("Eat") || item.hasAction("Drink")) {
                continue;
            }
            if (shouldKeepItemGiants(item)) {
                continue;
            }
            boolean inKeep = false;
            for (String k : getGiantsKeepList()) {
                if (k.equalsIgnoreCase(item.getName())) {
                    inKeep = true;
                    break;
                }
            }
            if (inKeep) {
                continue;
            }
            Bank.depositAll(item.getName());
            debug("[Bank] vreemd item weg: " + item.getName());
            sleep(300, 550);
        }
    }

    /** Inv-check: log wat we hebben en wat we nodig hebben (instellingen). Roep aan met bank open. */
    private void doInventoryCheckAndLog() {
        int foodHave = countFoodInInv();
        int foodNeed = getFoodAmount();
        boolean haveKey = Inventory.contains(BRASS_KEY);
        CombatBotConfig.ImpsCombatStyle style = giantsCombatStyleBaselineForAccount();
        boolean haveGear = hasWeaponForStyle(style);
        debug("[Inv] Hebben: food=" + foodHave + ", brass key=" + haveKey + ", gear " + style + "=" + haveGear
                + ", coins " + coinsContextForDebugLog());
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

    /**
     * Tier-volgorde van melee-wapens van best (lage index) → slechtst (hoge index).
     * Wordt gebruikt voor zowel "wat heb ik nu best" als "is er een upgrade in de bank".
     */
    private static final String[] MELEE_TIER = {
            "Rune scimitar", "Adamant scimitar", "Mithril scimitar",
            "Steel scimitar", "Iron scimitar", "Bronze scimitar", "Scimitar"
    };

    /** Attack-level vereist per item in {@link #MELEE_TIER}. Geen entry = 1 (geen vereiste). */
    private static final java.util.Map<String, Integer> MELEE_TIER_ATTACK_REQ;
    static {
        java.util.Map<String, Integer> m = new java.util.HashMap<>();
        m.put("Rune scimitar", 40);
        m.put("Adamant scimitar", 30);
        m.put("Mithril scimitar", 20);
        m.put("Steel scimitar", 5);
        m.put("Iron scimitar", 1);
        m.put("Bronze scimitar", 1);
        m.put("Scimitar", 1);
        MELEE_TIER_ATTACK_REQ = java.util.Collections.unmodifiableMap(m);
    }

    /** Index in {@link #MELEE_TIER} (-1 als geen match). Lager = beter. */
    private int meleeTierIndexCi(String name) {
        if (name == null) return -1;
        for (int i = 0; i < MELEE_TIER.length; i++) {
            if (MELEE_TIER[i].equalsIgnoreCase(name)) return i;
        }
        return -1;
    }

    private int safeAttackLevel() {
        try {
            return Skills.getLevel(Skill.ATTACK);
        } catch (Throwable ignored) {
            return 1;
        }
    }

    /** Mag deze speler dit melee-wapen daadwerkelijk wielden (Attack-level)? */
    private boolean canWieldMelee(String name) {
        if (name == null) return false;
        Integer req = MELEE_TIER_ATTACK_REQ.get(name);
        if (req == null) {
            for (java.util.Map.Entry<String, Integer> e : MELEE_TIER_ATTACK_REQ.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) {
                    req = e.getValue();
                    break;
                }
            }
        }
        if (req == null) req = 1;
        try {
            int lvl = Skills.getLevel(Skill.ATTACK);
            return lvl >= req;
        } catch (Throwable ignored) {
            return req <= 1;
        }
    }

    /** Beste melee-wapen dat we al hebben (in inventory of aangetrokken). {@code null} als geen. */
    private String bestOwnedMeleeWeaponName() {
        for (String w : MELEE_TIER) {
            boolean owned =
                    Inventory.contains(item -> item != null && item.getName() != null && w.equalsIgnoreCase(item.getName()))
                    || Equipment.contains(item -> item != null && item.getName() != null && w.equalsIgnoreCase(item.getName()));
            if (owned) return w;
        }
        return null;
    }

    /**
     * Beste melee-wapen in de bank dat we ook daadwerkelijk kunnen wielden (Attack-level check).
     * Voorkomt dat de bot een Rune scimitar pakt op een 1 Attack-account → wield faalt → bank → loop.
     * {@code null} als niets in bank past.
     */
    private String bestBankMeleeWeaponName() {
        for (String w : MELEE_TIER) {
            if (!canWieldMelee(w)) continue;
            if (Bank.contains(w)) return w;
        }
        return null;
    }

    private int safeDefenseLevel() {
        try {
            return Skills.getLevel(Skill.DEFENCE);
        } catch (Throwable ignored) {
            return 1;
        }
    }

    private int safeRangedLevel() {
        try {
            return Skills.getLevel(Skill.RANGED);
        } catch (Throwable ignored) {
            return 1;
        }
    }

    private int safeMagicLevel() {
        try {
            return Skills.getLevel(Skill.MAGIC);
        } catch (Throwable ignored) {
            return 1;
        }
    }

    private static final class GearPiece {
        final String slot;
        final String name;
        final int defenseReq;
        final int rangedReq;
        final int magicReq;

        GearPiece(String slot, String name, int defenseReq, int rangedReq, int magicReq) {
            this.slot = slot;
            this.name = name;
            this.defenseReq = defenseReq;
            this.rangedReq = rangedReq;
            this.magicReq = magicReq;
        }
    }

    private static GearPiece gp(String slot, String name, int def, int range, int magic) {
        return new GearPiece(slot, name, def, range, magic);
    }

    /** Best → worst per slot. Alleen armor, geen wapen/ammo. */
    private static final GearPiece[] MELEE_ARMOUR_TIER = {
            gp("HEAD", "Rune full helm", 40, 0, 0), gp("HEAD", "Rune med helm", 40, 0, 0),
            gp("HEAD", "Adamant full helm", 30, 0, 0), gp("HEAD", "Adamant med helm", 30, 0, 0),
            gp("HEAD", "Mithril full helm", 20, 0, 0), gp("HEAD", "Mithril med helm", 20, 0, 0),
            gp("HEAD", "Steel full helm", 5, 0, 0), gp("HEAD", "Steel med helm", 5, 0, 0),
            gp("HEAD", "Iron full helm", 1, 0, 0), gp("HEAD", "Bronze full helm", 1, 0, 0),

            // Rune platebody heeft Dragon Slayer nodig; chainbody is veiliger voor fresh accounts.
            gp("BODY", "Rune chainbody", 40, 0, 0), gp("BODY", "Adamant platebody", 30, 0, 0),
            gp("BODY", "Adamant chainbody", 30, 0, 0), gp("BODY", "Mithril platebody", 20, 0, 0),
            gp("BODY", "Mithril chainbody", 20, 0, 0), gp("BODY", "Steel platebody", 5, 0, 0),
            gp("BODY", "Steel chainbody", 5, 0, 0), gp("BODY", "Iron platebody", 1, 0, 0),
            gp("BODY", "Bronze platebody", 1, 0, 0),

            gp("LEGS", "Rune platelegs", 40, 0, 0), gp("LEGS", "Rune plateskirt", 40, 0, 0),
            gp("LEGS", "Adamant platelegs", 30, 0, 0), gp("LEGS", "Adamant plateskirt", 30, 0, 0),
            gp("LEGS", "Mithril platelegs", 20, 0, 0), gp("LEGS", "Mithril plateskirt", 20, 0, 0),
            gp("LEGS", "Steel platelegs", 5, 0, 0), gp("LEGS", "Iron platelegs", 1, 0, 0),
            gp("LEGS", "Bronze platelegs", 1, 0, 0),

            gp("SHIELD", "Rune kiteshield", 40, 0, 0), gp("SHIELD", "Rune sq shield", 40, 0, 0),
            gp("SHIELD", "Adamant kiteshield", 30, 0, 0), gp("SHIELD", "Adamant sq shield", 30, 0, 0),
            gp("SHIELD", "Mithril kiteshield", 20, 0, 0), gp("SHIELD", "Mithril sq shield", 20, 0, 0),
            gp("SHIELD", "Steel kiteshield", 5, 0, 0), gp("SHIELD", "Iron kiteshield", 1, 0, 0),
            gp("SHIELD", "Bronze kiteshield", 1, 0, 0),

            gp("HANDS", "Leather gloves", 1, 0, 0),
            gp("FEET", "Fighting boots", 1, 0, 0), gp("FEET", "Fancy boots", 1, 0, 0),
            gp("FEET", "Leather boots", 1, 0, 0),
            gp("AMULET", "Amulet of strength", 1, 0, 0), gp("AMULET", "Amulet of power", 1, 0, 0),
            gp("AMULET", "Amulet of accuracy", 1, 0, 0), gp("AMULET", "Amulet of defence", 1, 0, 0)
    };

    private static final GearPiece[] RANGED_ARMOUR_TIER = {
            gp("HEAD", "Coif", 1, 20, 0), gp("HEAD", "Leather cowl", 1, 1, 0),
            gp("BODY", "Studded body", 20, 20, 0), gp("BODY", "Hardleather body", 10, 1, 0),
            gp("BODY", "Leather body", 1, 1, 0),
            gp("LEGS", "Green d'hide chaps", 1, 40, 0), gp("LEGS", "Studded chaps", 1, 20, 0),
            gp("LEGS", "Leather chaps", 1, 1, 0),
            gp("HANDS", "Green d'hide vambraces", 1, 40, 0), gp("HANDS", "Leather vambraces", 1, 1, 0),
            gp("FEET", "Fighting boots", 1, 0, 0), gp("FEET", "Fancy boots", 1, 0, 0),
            gp("FEET", "Leather boots", 1, 0, 0),
            gp("AMULET", "Amulet of power", 1, 0, 0), gp("AMULET", "Amulet of accuracy", 1, 0, 0),
            gp("AMULET", "Amulet of defence", 1, 0, 0)
    };

    private static final GearPiece[] MAGE_ARMOUR_TIER = {
            gp("HEAD", "Mystic hat", 20, 0, 20), gp("HEAD", "Wizard hat", 1, 0, 1),
            gp("HEAD", "Blue wizard hat", 1, 0, 1),
            gp("BODY", "Mystic robe top", 20, 0, 20), gp("BODY", "Wizard robe", 1, 0, 1),
            gp("BODY", "Blue wizard robe", 1, 0, 1),
            gp("LEGS", "Mystic robe bottom", 20, 0, 20), gp("LEGS", "Zamorak monk bottom", 1, 0, 1),
            gp("LEGS", "Monk's robe", 1, 0, 1),
            gp("HANDS", "Mystic gloves", 20, 0, 20),
            gp("FEET", "Mystic boots", 20, 0, 20), gp("FEET", "Leather boots", 1, 0, 0),
            gp("AMULET", "Amulet of magic", 1, 0, 1), gp("AMULET", "Amulet of power", 1, 0, 0)
    };

    private boolean canWearGear(GearPiece g) {
        if (g == null) return false;
        return safeDefenseLevel() >= g.defenseReq
                && safeRangedLevel() >= g.rangedReq
                && safeMagicLevel() >= g.magicReq;
    }

    private GearPiece[] armourTierForStyle(CombatBotConfig.ImpsCombatStyle style) {
        switch (style) {
            case MELEE:
                return MELEE_ARMOUR_TIER;
            case RANGED:
                return RANGED_ARMOUR_TIER;
            case MAGE:
                return MAGE_ARMOUR_TIER;
            default:
                return new GearPiece[0];
        }
    }

    private int gearTierIndex(GearPiece[] list, String name) {
        if (name == null) return -1;
        for (int i = 0; i < list.length; i++) {
            if (list[i].name.equalsIgnoreCase(name)) return i;
        }
        return -1;
    }

    private GearPiece bestOwnedGearForSlot(GearPiece[] list, String slot) {
        for (GearPiece g : list) {
            if (!g.slot.equals(slot)) continue;
            boolean owned = Inventory.contains(item -> item != null && item.getName() != null && g.name.equalsIgnoreCase(item.getName()))
                    || Equipment.contains(item -> item != null && item.getName() != null && g.name.equalsIgnoreCase(item.getName()));
            if (owned) return g;
        }
        return null;
    }

    private GearPiece bestBankGearForSlot(GearPiece[] list, String slot) {
        for (GearPiece g : list) {
            if (!g.slot.equals(slot)) continue;
            if (!canWearGear(g)) continue;
            if (Bank.contains(g.name)) return g;
        }
        return null;
    }

    private List<String> gearSlots(GearPiece[] list) {
        List<String> out = new ArrayList<>();
        for (GearPiece g : list) {
            if (!out.contains(g.slot)) out.add(g.slot);
        }
        return out;
    }

    /** Pak per slot het beste draagbare armorstuk uit de bank. Ontbrekende armor blokkeert Giants niet. */
    private void withdrawBestArmourForStyleInOneGo(CombatBotConfig.ImpsCombatStyle style) {
        StyleArmourBankHelper.withdrawAllForStyle(style, this::sleep);
    }

    /** Equip alle beste style-armor uit inventory. */
    private void equipBestArmourFromInventory(CombatBotConfig.ImpsCombatStyle style) {
        StyleArmourBankHelper.equipAllFromInventory(config, style, this::sleep);
    }

    /** Haal alle gear voor deze style in één keer (één bank sessie, zoals Imps). */
    private void withdrawAllGearForStyleInOneGo(CombatBotConfig.ImpsCombatStyle style) {
        switch (style) {
            case MELEE: {
                // Eerst eventuele "junk" wapens dumpen die in inv liggen maar te hoog tier zijn voor
                // ons Attack-level. Zonder dit blijft de bot eindeloos proberen te wielden +
                // re-withdrawen elke cycle.
                for (String w : MELEE_TIER) {
                    if (canWieldMelee(w)) continue;
                    if (Inventory.contains(item -> item != null && item.getName() != null && w.equalsIgnoreCase(item.getName()))) {
                        Bank.depositAll(w);
                        sleep(300, 600);
                        debug("[Bank] " + w + " gedeponeerd — Attack-level te laag (req="
                                + MELEE_TIER_ATTACK_REQ.getOrDefault(w, 1)
                                + ", lvl=" + safeAttackLevel() + ")");
                    }
                }

                String owned = bestOwnedMeleeWeaponName();
                String bankBest = bestBankMeleeWeaponName();
                int ownedIdx = meleeTierIndexCi(owned);
                int bankIdx = meleeTierIndexCi(bankBest);

                if (bankBest == null) {
                    if (owned == null) debug("[Bank] Geen wieldbaar melee-wapen gevonden (Attack lvl="
                            + safeAttackLevel() + ")");
                    return;
                }

                if (owned != null && bankIdx >= ownedIdx) {
                    return;
                }

                if (owned != null && bankIdx < ownedIdx) {
                    Bank.depositAll(owned);
                    sleep(400, 700);
                    debug("[Bank] upgrade gevonden: " + owned + " → " + bankBest + " (bank = beter)");
                }

                Bank.withdraw(bankBest, 1);
                sleep(500, 800);
                debug("[Bank] wapen " + bankBest + " (Attack lvl=" + safeAttackLevel()
                        + ", req=" + MELEE_TIER_ATTACK_REQ.getOrDefault(bankBest, 1) + ")");
                return;
            }
            case RANGED: {
                if (Bank.contains("Shortbow")) {
                    Bank.withdraw("Shortbow", 1);
                    sleep(500, 800);
                } else if (Bank.contains("Longbow")) {
                    Bank.withdraw("Longbow", 1);
                    sleep(500, 800);
                }
                for (String arrow : RangedAmmoPreference.withdrawOrder(config)) {
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

    /**
     * Detecteert "we worden getarget maar kunnen niet vechten" deadlock en breekt eruit door
     * actief de blokkerende giant aan te vallen. Retourneert delay in ms als er actie is gedaan,
     * 0 als er niks aan de hand is.
     */
    private int maybeBreakOutOfStuckCombat(IPlayer player, long now) {
        if (now - lastCombatStuckBreakoutMs < COMBAT_STUCK_BREAKOUT_COOLDOWN_MS) {
            return 0;
        }
        // Animatie op -1 langer dan onze random 3-8s drempel?
        if (lastPlayerCombatAnimMs == 0) {
            // Eerste tick in combat: initialiseer baseline zodat we niet meteen forceren.
            lastPlayerCombatAnimMs = now;
            return 0;
        }
        long idleSinceAnim = now - lastPlayerCombatAnimMs;
        if (idleSinceAnim < combatStuckThresholdMs) {
            return 0;
        }

        // Kies de giant die ons als target heeft — bij meerdere: dichtste in path-distance.
        WorldPoint myPos = player.getWorldLocation();
        if (myPos == null) return 0;
        String monsterName = config.giantsMonsterName();
        List<INPC> attackers = NPCs.getAll(npc ->
                npc != null
                        && npc.getName() != null
                        && npc.getName().equalsIgnoreCase(monsterName)
                        && !npc.isDead()
                        && npc.getInteracting() == player
                        && npc.getWorldLocation() != null
                        && npc.getWorldLocation().distanceTo(HILL_GIANTS_CENTER) <= HILL_GIANTS_RADIUS
        );
        if (attackers == null || attackers.isEmpty()) return 0;

        INPC target = null;
        int bestPathDist = Integer.MAX_VALUE;
        int bestTileDist = Integer.MAX_VALUE;
        for (INPC npc : attackers) {
            if (npc == null || npc.getWorldLocation() == null) continue;
            int tileDist = myPos.distanceTo(npc.getWorldLocation());
            int pathDist;
            try {
                pathDist = Movement.calculateDistance(npc.getWorldLocation());
            } catch (Exception e) {
                pathDist = tileDist;
            }
            if (pathDist < 0) continue;
            if (pathDist < bestPathDist
                    || (pathDist == bestPathDist && tileDist < bestTileDist)) {
                bestPathDist = pathDist;
                bestTileDist = tileDist;
                target = npc;
            }
        }
        if (target == null) return 0;

        target.interact("Attack");
        lastAttackClickTime = now;
        lastAttackedNpcIndex = target.getIndex();
        lastCombatStuckBreakoutMs = now;
        // Reset animatie-baseline + nieuwe random drempel zodat we niet binnen dezelfde dode periode
        // direct opnieuw forceren als de bot op weg is naar de target.
        lastPlayerCombatAnimMs = now;
        combatStuckThresholdMs = 3000L + random.nextInt(5000);
        debug("[Anti-stuck] " + idleSinceAnim + "ms geen anim terwijl giant ons targette → "
                + "Attack geforceerd op " + target.getName() + " (pathDist=" + bestPathDist + ")");
        return 600 + random.nextInt(500);
    }

    private boolean isInCombat() {
        IPlayer player = Players.getLocal();
        if (player == null) return false;
        WorldPoint myPos = player.getWorldLocation();
        if (myPos == null) return false;

        String monsterName = config.giantsMonsterName();

        // Actieve animatie (slaan, casten, …) — duidelijk bezig met een actie.
        if (player.getAnimation() != -1) return true;

        // Tussen twee hits is animatie vaak -1, maar de client houdt nog combat-lock op de NPC.
        // Zonder deze check spamt de bot "Attack" terwijl we al op de giant zitten (zelfde patroon als ImpsHandler).
        if (player.isInteracting() && player.getInteracting() instanceof INPC) {
            INPC focus = (INPC) player.getInteracting();
            if (focus.getName() != null
                    && focus.getName().equalsIgnoreCase(monsterName)
                    && !focus.isDead()
                    && focus.getWorldLocation() != null
                    && focus.getWorldLocation().distanceTo(HILL_GIANTS_CENTER) <= HILL_GIANTS_RADIUS) {
                return true;
            }
        }

        // Giant valt ons aan (ook tussen ticks / idle pose — niet afhankelijk van speler-animatie).
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
            if (pathDist >= 0 && pathDist <= 4) {
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
                if (match
                        && canLootItemNow(item)
                        && item.getWorldLocation().distanceTo(myPos) <= HILL_GIANTS_RADIUS + 3) return true;
            }
            return false;
        } else {
            for (String name : lootNames) {
                ITileItem item = TileItems.getNearest(i ->
                        i != null && i.getName() != null
                                && i.getWorldLocation() != null
                                && !isGroundLootTileExcluded(i.getWorldLocation())
                                && i.getName().equalsIgnoreCase(name)
                                && canLootItemNow(i)
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
                        && canLootItemNow(i)
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
     * - Begraaf bones/ashes waar mogelijk (altijd, gratis slot)
     * - Eet alleen als de globale toggle {@link CombatBotConfig#eatToMakeSpaceForLoot()} aan staat,
     *   HP niet vol is én er ligt loot waar het de moeite waard voor is (geen bones/ashes).
     * Return >0 = actie gedaan (delay), 0 = geen ruimte kunnen maken.
     */
    private int tryMakeSpaceForLootWhenFull() {
        if (!Inventory.isFull()) return 0;

        // 1) Begraaf eerst als dat kan (directe inv-slot vrijmaken zonder food te verspillen)
        int buryDelay = buryOneBoneIfReady();
        if (buryDelay > 0) return buryDelay;

        // 2) Eet om plek te maken voor non-bone/ash loot — gate'd op config toggle
        List<String> wanted = new ArrayList<>();
        String[] lootArr = getLootItems();
        if (lootArr != null) {
            for (String s : lootArr) if (s != null) wanted.add(s);
        }
        List<String> sp = getSpecialLootNames();
        if (sp != null) wanted.addAll(sp);
        int eatDelay = EatForLootSpaceHelper.tryEatForSpace(
                config,
                config.lootOnlyOwn(),
                HILL_GIANTS_RADIUS + 3,
                wanted.isEmpty() ? null : wanted,
                this::debug);
        if (eatDelay > 0) {
            return eatDelay;
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

        InventoryActionHelper.interact(config, bone, "Bury");
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
