package com.combatbot;

import net.runelite.api.coords.WorldPoint;
import net.storm.api.domain.actors.INPC;
import net.storm.api.domain.actors.IPlayer;
import net.storm.api.domain.items.IInventoryItem;
import net.storm.api.domain.tiles.ITileObject;
import net.storm.api.movement.pathfinder.model.BankLocation;
import net.storm.sdk.entities.NPCs;
import net.storm.sdk.entities.Players;
import net.storm.sdk.entities.TileObjects;
import net.storm.sdk.game.Prices;
import net.storm.sdk.game.Skills;
import net.storm.sdk.items.Bank;
import net.storm.sdk.items.Equipment;
import net.storm.sdk.items.Inventory;
import net.storm.sdk.items.GrandExchange;
import net.storm.sdk.movement.Movement;
import net.runelite.api.Skill;
import net.storm.sdk.widgets.Production;
import net.storm.sdk.magic.Magic;
import net.storm.api.magic.SpellBook;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.function.Consumer;

/**
 * FishingHandler - beheert alle fishing logica.
 *
 * Banking architectuur:
 * - ÉÉN unified bank handler (handleUnifiedBanking) voor ALLE scenario's:
 *   tool ophalen, bait ophalen, vis dumpen — allemaal in één bank visit via UBM.
 * - Nooit meerdere keren bank open/sluiten per trip.
 * - GE restock als bait ontbreekt in bank (collect naar inventory).
 */
public class FishingHandler {
    private static final int GENIE_LAMP_ITEM_ID = 2528;
    public static final class ImpsCashFarmRequest {
        public final int estimatedImpsTrips;
        public final String missingItemName;
        public final int availableCoinsGp;
        public final int neededCoinsGp;

        public ImpsCashFarmRequest(int estimatedImpsTrips, String missingItemName, int availableCoinsGp, int neededCoinsGp) {
            this.estimatedImpsTrips = Math.max(1, estimatedImpsTrips);
            this.missingItemName = missingItemName != null ? missingItemName : "fishing supplies";
            this.availableCoinsGp = Math.max(0, availableCoinsGp);
            this.neededCoinsGp = Math.max(0, neededCoinsGp);
        }
    }

    private static void debug(String msg) {
        DebugLog.log("Fishing", msg);
        System.out.println("[Fishing] " + msg);
    }

    private final CombatBotConfig config;
    private final AntiBan antiBan;
    private final CombatBotPaint paint;
    private final Random random = new Random();
    private TileMarkerManager tileMarkerManager;

    private static final int LOCATION_THRESHOLD = 5;
    private static final int BARBARIAN_MIN_RADIUS = 22;

    private static final WorldPoint DRAYNOR_FIRE = new WorldPoint(3096, 3237, 0);
    private static final WorldPoint BARBARIAN_FIRE = new WorldPoint(3106, 3432, 0);

    private static final WorldPoint GE_LOCATION = new WorldPoint(3165, 3487, 0);
    private static final WorldPoint FISHING_SHOP = new WorldPoint(3013, 3225, 0);
    private static final WorldPoint COOKING_GUILD_CENTER = new WorldPoint(3272, 3182, 0);
    private static final int COOKING_GUILD_AVOID_RADIUS = 25;
    private static final WorldPoint EDGEVILLE_BANK = new WorldPoint(3096, 3492, 0);
    /** Zelfde als {@link BankHelper} F2P-lijst: Varrock West bank. */
    private static final WorldPoint VARROCK_WEST_BANK = new WorldPoint(3189, 3436, 0);
    private static final int EDGEVILLE_FISHING_PROFILE_RADIUS = 80;

    /**
     * Vereenvoudigde state machine — geen aparte FETCH_TOOL/FETCH_BAIT states.
     * WALKING_TO_BANK + BANKING handelen ALLES af (tool, bait, vis dump) in één bezoek.
     */
    public enum FishingState {
        FISHING, DROPPING, WALKING_TO_BANK, BANKING, WALKING_TO_SPOT,
        WALKING_BACK_TO_SPOT,
        RESTOCK_WALKING_TO_GE, RESTOCKING,
        WALKING_TO_FIRE, COOKING,
        IDLE
    }

    private FishingState currentState = FishingState.IDLE;
    private WorldPoint fishingSpot;
    private WorldPoint preBankPosition = null;
    private int areaRadius = 10;

    private static final long IDLE_TIMEOUT_MS = 30_000;
    private long idleStartTime = 0;

    private long lastInteractTime = 0;
    private static final long INTERACT_COOLDOWN_MS = 1400;
    /** Na vis-spot klik: even niet opnieuw klikken tot animatie of timeout (bank/spot-loop voorkomen). */
    private long fishingPostClickGraceUntil = 0;
    private static final int FISHING_POST_CLICK_GRACE_MIN_MS = 5000;
    private static final int FISHING_POST_CLICK_GRACE_MAX_MS = 8000;
    private long lastTravelClickTime = 0;

    private int bankRetries = 0;
    private static final int MAX_BANK_RETRIES = 5;

    private WorldPoint walkTargetSpot = null;
    private long walkTargetSetTime = 0;
    private static final long WALK_TARGET_TIMEOUT_MS = 35_000;
    // Fishing-bank travel mag iets agressiever reclicken voor vloeiender lopen.
    private static final int FISHING_BANK_RECLICK_MIN_MS = 450;
    private static final int FISHING_BANK_RECLICK_MAX_MS = 900;

    private boolean needsCooking = false;
    private WorldPoint cookingFireLocation = null;
    private String lastCookingAttemptItemName = null;

    private boolean varrockTeleportUsedThisRestockTrip = false;
    private long lastVarrockTeleportTime = 0;
    private static final long VARROCK_TELEPORT_COOLDOWN_MS = 6000;

    // Bank sessie guards (voorkomt herhaald UBM runnen als bank nog open is)
    private boolean bankSessionCompleted = false;
    private long bankSessionCompletedTime = 0;
    private static final long BANK_SESSION_COOLDOWN_MS = 3000;

    private final UniversalBankingManager bankingManager = new UniversalBankingManager();
    /** Bij fatale bank-route (Edgeville + Varrock West mislukt): plugin zet bot uit + logout. */
    private final Consumer<String> fatalBankWalkFailure;
    private boolean restockNeedsCoinRaise = false;
    private ImpsCashFarmRequest pendingImpsCashFarmRequest;
    /** Na reset/login/account-switch eerst korte grace voordat een bank-route als "fataal" telt. */
    private long fatalBankRouteGraceUntilMs = 0L;
    private int consecutiveBankRoutePathFails = 0;
    private long lastBankRoutePathFailMs = 0L;
    private static final long FATAL_BANK_ROUTE_GRACE_MS = 90_000L;
    private static final long BANK_ROUTE_FAIL_CHAIN_WINDOW_MS = 20_000L;
    private static final int BANK_ROUTE_FAILS_BEFORE_FATAL = 3;

    private static final int FISHING_RESTOCK_STACK_TARGET = 1000;
    private static final int GE_PRICE_FISHING_ROD = 1000;
    private static final int GE_PRICE_FLY_ROD = 1000;
    private static final int GE_PRICE_SMALL_NET = 500;
    private static final int GE_PRICE_HARPOON = 1500;
    private static final int GE_PRICE_LOBSTER_POT = 1500;
    private static final int GE_PRICE_DEFAULT_TOOL = 1000;
    private static final int MAX_GE_RESTOCK_FAILS = 3;
    private static final long GE_RESTOCK_FAIL_COOLDOWN_MS = 4000L;
    private int geRestockFailStreak = 0;
    private String geRestockFailItem = "";
    /** &gt;0: eerst dit bedrag aan coins uit bank halen, daarna GE-restock. */
    private int pendingGeCoinsWithdrawTarget = 0;
    private static final String[] DEFAULT_IMPS_GE_SELL_ITEMS = {
            "Black bead", "Red bead", "Yellow bead", "White bead", "Mind talisman", "Fiendish ashes"
    };

    public FishingHandler(CombatBotConfig config, AntiBan antiBan, CombatBotPaint paint, Consumer<String> fatalBankWalkFailure) {
        this.config = config;
        this.antiBan = antiBan;
        this.paint = paint;
        this.fatalBankWalkFailure = fatalBankWalkFailure;
    }

    public void resetState() {
        currentState = FishingState.IDLE;
        preBankPosition = null;
        idleStartTime = 0;
        lastInteractTime = 0;
        fishingPostClickGraceUntil = 0;
        lastTravelClickTime = 0;
        walkTargetSpot = null;
        walkTargetSetTime = 0;
        needsCooking = false;
        cookingFireLocation = null;
        lastCookingAttemptItemName = null;
        bankRetries = 0;
        bankSessionCompleted = false;
        bankSessionCompletedTime = 0;
        restockNeedsCoinRaise = false;
        pendingImpsCashFarmRequest = null;
        geRestockFailStreak = 0;
        geRestockFailItem = "";
        pendingGeCoinsWithdrawTarget = 0;
        fatalBankRouteGraceUntilMs = System.currentTimeMillis() + FATAL_BANK_ROUTE_GRACE_MS;
        consecutiveBankRoutePathFails = 0;
        lastBankRoutePathFailMs = 0L;
    }

    /** Build requirements voor UBM: tool (1,1) en optionele bait (min, alles uit bank). */
    private List<UniversalBankingManager.Requirement> buildFishingRequirements() {
        String[] method = getTargetMethod();
        String toolName = method[2];
        // Methode-bait is leidend. Dit voorkomt mismatch (bijv. Feather ingesteld
        // terwijl huidige methode Fishing bait nodig heeft).
        String methodBait = method[3];
        String baitName = methodBait != null ? methodBait.trim() : "";

        List<UniversalBankingManager.Requirement> list = new ArrayList<>();
        list.add(new UniversalBankingManager.Requirement(toolName, 1, 1));
        if (baitName != null && !baitName.isEmpty()) {
            int baitMin = Math.max(0, config.fishingBaitMin());
            int baitNow = getInventoryStackCount(baitName);
            int effectiveTarget = computeBaitWithdrawTargetAll(baitName, baitNow);
            debug("buildFishingRequirements: bait=" + baitName + " now=" + baitNow
                    + " bankTriggerMin=" + baitMin + " withdrawTarget=" + effectiveTarget
                    + " (bank=" + getBankStackCount(baitName) + ")");
            list.add(new UniversalBankingManager.Requirement(baitName, baitMin, effectiveTarget));
        }
        return list;
    }

    /**
     * Hele bankstack bait/feathers (stackable = 1 slot). {@code fishingBaitMin} is alleen
     * drempel wanneer te banken — geen withdraw-limiet (niet meer vast 50).
     * Slots na deposit meegerekend (vis weg vóór withdraw in UBM).
     */
    private int computeBaitWithdrawTargetAll(String baitName, int baitNow) {
        int bankQty = getBankStackCount(baitName);
        if (bankQty <= 0) {
            return baitNow;
        }
        int slotsAfterDeposit = Inventory.getFreeSlots() + countInventorySlotsFreedByFishingDeposit();
        if (baitNow > 0 || slotsAfterDeposit >= 1) {
            return baitNow + bankQty;
        }
        return baitNow;
    }

    /** Slots die vrijkomen als UBM alles behalve tool+bait stort. */
    private int countInventorySlotsFreedByFishingDeposit() {
        String[] method = getTargetMethod();
        if (method == null) {
            return 0;
        }
        String toolName = method.length > 2 && method[2] != null ? method[2].trim() : "";
        String baitName = method.length > 3 && method[3] != null ? method[3].trim() : "";
        List<IInventoryItem> all = Inventory.getAll();
        if (all == null) {
            return 0;
        }
        int freed = 0;
        for (IInventoryItem item : all) {
            if (item == null || item.getName() == null) {
                continue;
            }
            String name = item.getName();
            if (!toolName.isEmpty() && name.equalsIgnoreCase(toolName)) {
                continue;
            }
            if (!baitName.isEmpty() && name.equalsIgnoreCase(baitName)) {
                continue;
            }
            freed++;
        }
        return freed;
    }

    private int getInventoryStackCount(String itemName) {
        if (itemName == null || itemName.isEmpty()) return 0;
        try {
            return Math.max(0, Inventory.getCount(true, itemName));
        } catch (Exception ignored) {
            return getItemQuantity(itemName);
        }
    }

    private int getBankStackCount(String itemName) {
        if (itemName == null || itemName.isEmpty() || !Bank.isOpen()) return 0;
        try {
            return Math.max(0, Bank.getCount(true, itemName));
        } catch (Exception ignored) {
            return Bank.contains(itemName) ? 10_000 : 0;
        }
    }

    public void setActiveCenter(WorldPoint center, int radius) {
        this.fishingSpot = center;
        this.areaRadius = radius;
        this.walkTargetSpot = null;
        debug("setActiveCenter: " + (center != null ? center.getX() + "," + center.getY() : "null") + " radius=" + radius);
    }

    public void setTileMarkerManager(TileMarkerManager manager) {
        this.tileMarkerManager = manager;
    }

    public WorldPoint getFishingSpot() { return fishingSpot; }

    private AccountCenterBehaviorStore.CenterBehavior fishingCenterBehavior() {
        return AccountCenterBehaviorStore.forCenter(
                AccountCenterBehaviorStore.SkillKind.FISHING, fishingSpot, config);
    }

    private boolean effectiveFishingDropFish() {
        return fishingCenterBehavior().drop;
    }

    private boolean effectiveFishingCookEnabled() {
        return fishingCenterBehavior().extra;
    }

    public FishingState getCurrentState() { return currentState; }

    public ImpsCashFarmRequest pollImpsCashFarmRequest() {
        ImpsCashFarmRequest req = pendingImpsCashFarmRequest;
        pendingImpsCashFarmRequest = null;
        return req;
    }

    // ===================== MAIN LOOP =====================

    public int loop() {
        try {
            debug("loop() start");
            String[] method = getTargetMethod();
            String requiredTool = method[2];
            String requiredBait = method[3];

            // 1. GE restock in progress → finish first
            if (currentState == FishingState.RESTOCK_WALKING_TO_GE) {
                return handleRestockWalkToGE();
            }
            if (currentState == FishingState.RESTOCKING) {
                return handleRestocking();
            }

            // 1b. Eerst coins uit bank (GE kan niet withdrawen; snapshot JSON mist Coins soms)
            if (pendingGeCoinsWithdrawTarget > 0) {
                int coinTarget = pendingGeCoinsWithdrawTarget;
                if (Bank.isOpen()) {
                    debug("loop: bank open → withdraw " + coinTarget + " gp voor GE");
                    withdrawCoinsForGeIfNeeded(coinTarget);
                    pendingGeCoinsWithdrawTarget = 0;
                    bankSessionCompleted = false;
                    sleep(300, 500);
                    Bank.close();
                    sleep(300, 500);
                    currentState = FishingState.RESTOCK_WALKING_TO_GE;
                    paint.setCurrentStatus("🛒 → Grand Exchange");
                    return handleRestockWalkToGE();
                }
                currentState = FishingState.WALKING_TO_BANK;
                paint.setCurrentStatus("→ Bank (coins voor GE)");
                return handleWalkToBank();
            }

            // 2. Alles-of-Niets check: tool + bait (baitMin = wanneer banken, niet hoeveel ophalen)
            boolean needsTool = !hasTool(requiredTool);
            int baitMinCheck = Math.max(0, config.fishingBaitMin());
            boolean needsBait = !requiredBait.isEmpty()
                    && (baitMinCheck <= 0
                    ? !Inventory.contains(requiredBait)
                    : getInventoryStackCount(requiredBait) < baitMinCheck);

            if (needsTool || needsBait) {
                if (bankRetries >= MAX_BANK_RETRIES) {
                    debug("loop: bank retries uitgeput (" + MAX_BANK_RETRIES + "x) → IDLE");
                    paint.setCurrentStatus("⏹ Kan supplies niet vinden (retries op)");
                    currentState = FishingState.IDLE;
                    return 5000;
                }

                String missing = needsTool ? requiredTool : requiredBait;
                debug("loop: supplies nodig (tool=" + needsTool + " bait=" + needsBait + ") poging " + (bankRetries + 1) + "/" + MAX_BANK_RETRIES);

                if (Bank.isOpen()) {
                    currentState = FishingState.BANKING;
                    paint.setCurrentStatus("🏦 Supplies ophalen");
                    return handleUnifiedBanking();
                }

                String rsn = BankSnapshotPlanner.currentDisplayName();
                boolean walkBank = false;
                if (needsTool && BankSnapshotPlanner.shouldWalkToBankForWithdraw(rsn, requiredTool)) {
                    walkBank = true;
                }
                if (needsBait && !requiredBait.isEmpty()
                        && BankSnapshotPlanner.shouldWalkToBankForWithdraw(rsn, requiredBait)) {
                    walkBank = true;
                }
                if (!BankSnapshotPlanner.hasPersistedSnapshot(rsn)) {
                    walkBank = true;
                }
                if (!walkBank) {
                    if (!config.fishingRestockEnabled()) {
                        saveBankPosition();
                        currentState = FishingState.WALKING_TO_BANK;
                        paint.setCurrentStatus("→ Bank (" + missing + " ophalen)");
                        return handleWalkToBank();
                    }
                    int coinsNeeded = estimateRequiredCoinsForRestockPlan(
                            buildFishingRestockPlan(requiredTool, requiredBait));
                    if (getInventoryCoinCount() < coinsNeeded
                            && BankSnapshotPlanner.shouldWithdrawBankCoinsBeforeGe(rsn, coinsNeeded)) {
                        debug("loop: inv gp=" + getInventoryCoinCount() + " nodig=" + coinsNeeded
                                + " bankSnap=" + BankSnapshotPlanner.knownBankCoinsQty(rsn) + " → bank");
                        pendingGeCoinsWithdrawTarget = coinsNeeded;
                        saveBankPosition();
                        currentState = FishingState.WALKING_TO_BANK;
                        paint.setCurrentStatus("→ Bank (coins voor GE)");
                        return handleWalkToBank();
                    }
                    debug("loop: snapshot bank leeg voor supplies → GE");
                    bankSessionCompleted = false;
                    currentState = FishingState.RESTOCK_WALKING_TO_GE;
                    paint.setCurrentStatus("🛒 → Grand Exchange (" + missing + ")");
                    return handleRestockWalkToGE();
                }

                saveBankPosition();
                currentState = FishingState.WALKING_TO_BANK;
                paint.setCurrentStatus("→ Bank (" + missing + " ophalen)");
                return handleWalkToBank();
            }
            bankRetries = 0;

            // 3. Cooking check
            if (needsCooking && effectiveFishingCookEnabled()) {
                debug("loop: needsCooking → fire/cooking");
                if (cookingFireLocation != null && !isAtLocationWithRange(cookingFireLocation, 3)) {
                    currentState = FishingState.WALKING_TO_FIRE;
                    paint.setCurrentStatus("🔥 → Lopen naar vuur");
                    return handleWalkingToFire();
                }
                currentState = FishingState.COOKING;
                paint.setCurrentStatus("🔥 Vis koken");
                return handleCooking();
            }

            // 4. Normal state machine
            currentState = determineState();
            debug("loop: state=" + currentState);

            switch (currentState) {
                case FISHING:
                    paint.setCurrentStatus("🎣 Aan het vissen");
                    return handleFishing();
                case DROPPING:
                    paint.setCurrentStatus("🗑 Vis droppen");
                    return handleDropping();
                case WALKING_TO_BANK:
                    paint.setCurrentStatus("→ Lopen naar bank");
                    return handleWalkToBank();
                case BANKING:
                    paint.setCurrentStatus("🏦 Aan het banken");
                    return handleUnifiedBanking();
                case WALKING_TO_SPOT:
                    paint.setCurrentStatus("→ Lopen naar vis spot");
                    return handleWalkingToSpot();
                case WALKING_BACK_TO_SPOT:
                    paint.setCurrentStatus("↩ Terug naar vis spot");
                    return handleWalkingBackToSpot();
                case IDLE:
                default:
                    paint.setCurrentStatus("⏳ Wachten op vis spot...");
                    return handleIdleAtSpot();
            }
        } catch (Exception e) {
            debug("loop: EXCEPTION " + e.getMessage());
            paint.setCurrentStatus("⚠ Fishing fout: " + e.getMessage());
            return 2000;
        }
    }

    // ===================== STATE DETERMINATION =====================

    private FishingState determineState() {
        // Vreemde items (niet fishing/tool/fish/food/coins) eerst banken.
        if (hasForeignItemsForFishing()) {
            // Forceer een nieuwe banksessie; anders kan bank direct weer sluiten door oude completion-flag.
            bankSessionCompleted = false;
            bankRetries = 0;
            saveBankPosition();
            return Bank.isOpen() ? FishingState.BANKING : FishingState.WALKING_TO_BANK;
        }

        String[] methodForBait = getTargetMethod();
        if (methodForBait != null && methodForBait.length > 3) {
            String baitCheck = methodForBait[3] != null ? methodForBait[3].trim() : "";
            int baitMinState = Math.max(0, config.fishingBaitMin());
            if (!baitCheck.isEmpty() && baitMinState > 0
                    && getInventoryStackCount(baitCheck) < baitMinState) {
                bankSessionCompleted = false;
                saveBankPosition();
                debug("determineState: bait onder min (" + getInventoryStackCount(baitCheck)
                        + "/" + baitMinState + ") → bank");
                return Bank.isOpen() ? FishingState.BANKING : FishingState.WALKING_TO_BANK;
            }
        }

        if (Inventory.isFull()) {
            debug("determineState: inv vol");
            if (effectiveFishingCookEnabled() && hasRawFishToCook()) {
                needsCooking = true;
                cookingFireLocation = findNearestFire();
                if (cookingFireLocation != null) {
                    debug("determineState: cooking aan + rauwe vis → WALKING_TO_FIRE");
                    return FishingState.WALKING_TO_FIRE;
                }
                debug("determineState: cooking aan maar geen cook-locatie gevonden");
            }
            if (effectiveFishingDropFish()) {
                debug("determineState: → DROPPING");
                return FishingState.DROPPING;
            }
            // Als bank open is maar we net klaar waren → sluit bank, ga niet opnieuw banken
            if (Bank.isOpen()) {
                if (bankSessionCompleted) {
                    debug("determineState: bank nog open na sessie, sluiten");
                    Bank.close();
                    return FishingState.IDLE; // wacht tot bank dicht is
                }
                debug("determineState: → BANKING");
                return FishingState.BANKING;
            }
            saveBankPosition();
            walkTargetSpot = null;
            debug("determineState: → WALKING_TO_BANK");
            return FishingState.WALKING_TO_BANK;
        }

        // Als bank open is maar inventory niet vol en geen supplies nodig → sluit bank
        if (Bank.isOpen()) {
            if (bankSessionCompleted) {
                debug("determineState: bank nog open na sessie (inv niet vol), sluiten");
                Bank.close();
                return FishingState.IDLE;
            }
            debug("determineState: bank open → BANKING");
            return FishingState.BANKING;
        }

        if (preBankPosition != null && !isAtLocation(preBankPosition)) {
            debug("determineState: niet op preBankPosition → WALKING_BACK_TO_SPOT");
            return FishingState.WALKING_BACK_TO_SPOT;
        }

        if (fishingSpot != null && !isPlayerWithinFishingArea()) {
            debug("determineState: buiten area → WALKING_TO_SPOT");
            idleStartTime = 0;
            return FishingState.WALKING_TO_SPOT;
        }

        String[] method = getTargetMethod();
        IPlayer local = Players.getLocal();
        INPC spot = findFishingSpot(method, local);

        if (spot != null) {
            debug("determineState: spot in range → FISHING");
            idleStartTime = 0;
            return FishingState.FISHING;
        }

        // In vis-gebied maar spot nog buiten interact (Barbarian: grote radius vs. verre spots)
        if (fishingSpot != null && local != null && local.getWorldLocation() != null) {
            WorldPoint myWp = local.getWorldLocation();
            WorldPoint nearestSpotTile = findNearestFishingSpotPositionInArea(method, local);
            if (nearestSpotTile != null) {
                int d = myWp.distanceTo(nearestSpotTile);
                if (d > 2) {
                    walkTargetSpot = nearestSpotTile;
                    idleStartTime = 0;
                    debug("determineState: spot te ver voor klik (" + d + " tiles) → WALKING_TO_SPOT");
                    return FishingState.WALKING_TO_SPOT;
                }
            } else if (MovementHelper.distanceToArea(myWp, fishingSpot, getEffectiveAreaRadius()) > 0) {
                // Geen NPC geladen en nog buiten het werkgebied: loop naar een willekeurige tile
                // ruim binnen de radius, niet naar de exacte center of rand.
                walkTargetSpot = randomInnerFishingAreaTarget();
                idleStartTime = 0;
                debug("determineState: geen spot-NPC gevonden, loop naar random area target → WALKING_TO_SPOT");
                return FishingState.WALKING_TO_SPOT;
            } else {
                walkTargetSpot = pickWalkTargetWhenInsideAreaWithoutSpot();
                idleStartTime = 0;
                debug("determineState: in area-box zonder spot-NPC → WALKING_TO_SPOT");
                return FishingState.WALKING_TO_SPOT;
            }
        }

        if (idleStartTime == 0) idleStartTime = System.currentTimeMillis();
        long idleMs = System.currentTimeMillis() - idleStartTime;

        if (fishingSpot != null && idleMs > IDLE_TIMEOUT_MS) {
            debug("determineState: idle timeout " + idleMs + "ms → WALKING_TO_SPOT");
            idleStartTime = 0;
            return FishingState.WALKING_TO_SPOT;
        }

        debug("determineState: geen spot in range, idle " + idleMs + "ms → IDLE");
        return FishingState.IDLE;
    }

    private boolean hasForeignItemsForFishing() {
        return Inventory.getFirst(item -> {
            if (item == null || item.getName() == null) return false;
            if (item.getId() == GENIE_LAMP_ITEM_ID) return false; // Lamp nooit banken; ook niet als "foreign" markeren.
            String n = item.getName().toLowerCase();
            if (n.contains("lamp")) return false; // Extra safety voor lamp-varianten/renames.
            boolean fishLike = n.contains("raw ")
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
            boolean toolLike = n.contains("net")
                    || n.contains("rod")
                    || n.contains("harpoon")
                    || n.contains("bait")
                    || n.contains("feather")
                    || n.contains("fishing");
            boolean allowed = fishLike || toolLike || (item.hasAction("Eat") || item.hasAction("Drink")) || n.contains("coins");
            return !allowed;
        }) != null;
    }

    // ===================== AREA CHECKS =====================

    private int getEffectiveAreaRadius() {
        if (fishingSpot == null) return areaRadius;
        if (FishingConfig.isBarbarianFishingLocation(fishingSpot.getX(), fishingSpot.getY())) {
            return Math.max(areaRadius, BARBARIAN_MIN_RADIUS);
        }
        return areaRadius;
    }

    /** Tiles: Barbarian rivier-spread — iets groter dan standaard. */
    private int getFishingInteractRange() {
        if (fishingSpot != null && FishingConfig.isBarbarianFishingLocation(fishingSpot.getX(), fishingSpot.getY())) {
            return 22;
        }
        return 18;
    }

    private boolean isPlayerWithinFishingArea() {
        IPlayer local = Players.getLocal();
        if (local == null || fishingSpot == null) return true;
        WorldPoint myPos = local.getWorldLocation();
        if (myPos == null) return true;
        int r = getEffectiveAreaRadius();
        boolean inBox = Math.abs(myPos.getX() - fishingSpot.getX()) <= r
                && Math.abs(myPos.getY() - fishingSpot.getY()) <= r;
        if (!inBox) {
            return false;
        }
        if (isDraynorFishingArea(fishingSpot) && BankHelper.isNearAnyBank(myPos)) {
            return false;
        }
        return true;
    }

    private boolean isWithinArea(WorldPoint point) {
        if (fishingSpot == null || point == null) return true;
        int r = getEffectiveAreaRadius();
        return Math.abs(point.getX() - fishingSpot.getX()) <= r
                && Math.abs(point.getY() - fishingSpot.getY()) <= r;
    }

    private int getSearchRadius() {
        return Math.max(getEffectiveAreaRadius() + 14, 22);
    }

    private boolean isWithinSearchRadius(WorldPoint point) {
        if (fishingSpot == null || point == null) return true;
        int r = getSearchRadius();
        return Math.abs(point.getX() - fishingSpot.getX()) <= r
                && Math.abs(point.getY() - fishingSpot.getY()) <= r;
    }

    private INPC findFishingSpot(String[] method, IPlayer local) {
        if (method == null || method.length < 2) return null;
        String spotName = method[0];
        String action = method[1];
        WorldPoint myPos = local != null ? local.getWorldLocation() : null;
        final int interactRange = getFishingInteractRange();

        INPC found = NPCs.getNearest(npc -> {
            if (npc == null || npc.getName() == null) return false;
            if (!npc.getName().toLowerCase().contains(spotName.toLowerCase())) return false;
            String[] actions = npc.getActions();
            boolean hasAction = false;
            if (actions != null) {
                for (String a : actions) {
                    if (a != null && a.toLowerCase().contains(action.toLowerCase())) {
                        hasAction = true;
                        break;
                    }
                }
            }
            if (!hasAction) return false;
            WorldPoint npcPos = npc.getWorldLocation();
            if (npcPos == null) return false;
            if (!isWithinArea(npcPos)) return false;
            if (tileMarkerManager != null && tileMarkerManager.isTileExcluded(CombatBotPlugin.ActiveSkill.FISHING, npcPos)) return false;
            if (myPos != null && myPos.distanceTo(npcPos) > interactRange) return false;
            return true;
        });
        if (found != null && myPos != null) {
            debug("findFishingSpot: gevonden op " + found.getWorldLocation().getX() + "," + found.getWorldLocation().getY() + " afstand=" + myPos.distanceTo(found.getWorldLocation()));
            return found;
        }
        // Fallback: sommige spots bieden niet altijd de exact gekozen actie.
        // Zoek dan een spot met een actie die de speler NU kan uitvoeren.
        INPC fallback = NPCs.getNearest(npc -> {
            if (npc == null || npc.getName() == null) return false;
            if (!npc.getName().toLowerCase().contains(spotName.toLowerCase())) return false;
            String[] actions = npc.getActions();
            if (actions == null) return false;
            boolean hasUsableAction = false;
            for (String a : actions) {
                if (a != null && isUsableFishingAction(a)) {
                    hasUsableAction = true;
                    break;
                }
            }
            if (!hasUsableAction) return false;
            WorldPoint npcPos = npc.getWorldLocation();
            if (npcPos == null) return false;
            if (!isWithinArea(npcPos)) return false;
            if (tileMarkerManager != null && tileMarkerManager.isTileExcluded(CombatBotPlugin.ActiveSkill.FISHING, npcPos)) return false;
            if (myPos != null && myPos.distanceTo(npcPos) > interactRange) return false;
            return true;
        });
        if (fallback != null && myPos != null) {
            debug("findFishingSpot: fallback gevonden op " + fallback.getWorldLocation().getX() + "," + fallback.getWorldLocation().getY()
                    + " afstand=" + myPos.distanceTo(fallback.getWorldLocation()));
        }
        return fallback;
    }

    private WorldPoint findNearestFishingSpotPositionInArea(String[] method, IPlayer local) {
        if (method == null || method.length < 2 || local == null) return null;
        final String spotName = method[0];
        final String action = method[1];
        WorldPoint myPos = local.getWorldLocation();
        List<INPC> candidates = NPCs.getAll(npc -> {
            if (npc == null || npc.getName() == null) return false;
            if (!npc.getName().toLowerCase().contains(spotName.toLowerCase())) return false;
            String[] actions = npc.getActions();
            if (actions == null) return false;
            boolean hasAction = false;
            for (String a : actions) {
                if (a != null && a.toLowerCase().contains(action.toLowerCase())) {
                    hasAction = true;
                    break;
                }
            }
            if (!hasAction) return false;
            WorldPoint npcPos = npc.getWorldLocation();
            return npcPos != null && isWithinSearchRadius(npcPos)
                    && (tileMarkerManager == null || !tileMarkerManager.isTileExcluded(CombatBotPlugin.ActiveSkill.FISHING, npcPos));
        });
        INPC nearest = null;
        int nearestDist = Integer.MAX_VALUE;
        if (candidates != null) {
            for (INPC npc : candidates) {
                WorldPoint npcPos = npc.getWorldLocation();
                if (npcPos == null) continue;
                int d = myPos.distanceTo(npcPos);
                if (d < nearestDist) {
                    nearestDist = d;
                    nearest = npc;
                }
            }
        }
        if (nearest != null) {
            debug("findNearestFishingSpotInArea: doel spot " + nearest.getWorldLocation().getX() + "," + nearest.getWorldLocation().getY() + " afstand=" + nearestDist);
            return nearest.getWorldLocation();
        }
        return null;
    }

    // ===================== RESTOCK VIA GE =====================

    private int handleRestockWalkToGE() {
        IPlayer local = Players.getLocal();
        if (local == null) return 1000;
        WorldPoint myPos = local.getWorldLocation();
        if (myPos.distanceTo(GE_LOCATION) <= 10) {
            debug("handleRestockWalkToGE: aangekomen bij GE → direct kopen");
            varrockTeleportUsedThisRestockTrip = false;
            currentState = FishingState.RESTOCKING;
            paint.setCurrentStatus("🛒 GE: supplies kopen");
            return handleRestocking();
        }
        // Tijdens travel blijven reclicken, ook als we al bewegen.
        // Varrock teleport
        long now = System.currentTimeMillis();
        if (config.fishingUseVarrockTeleport() && !varrockTeleportUsedThisRestockTrip
                && myPos.distanceTo(GE_LOCATION) > 40
                && (now - lastVarrockTeleportTime) >= VARROCK_TELEPORT_COOLDOWN_MS) {
            SpellBook.Standard varrockTele = SpellBook.Standard.VARROCK_TELEPORT;
            if (varrockTele != null && varrockTele.canCast()) {
                debug("handleRestockWalkToGE: Varrock teleport");
                paint.setCurrentStatus("🛒 Varrock teleport → GE");
                try {
                    Magic.cast(varrockTele);
                    lastVarrockTeleportTime = now;
                    varrockTeleportUsedThisRestockTrip = true;
                    return antiBan.varyDelay(randomDelay(5000, 7000));
                } catch (Exception e) {
                    debug("Varrock teleport fout: " + e.getMessage());
                }
            }
        }
        long travelNow = System.currentTimeMillis();
        if (!TravelWalkHelper.reclickReady(lastTravelClickTime, config)) {
            return antiBan.varyDelay(TravelWalkHelper.shortWaitMs(random));
        }
        debug("handleRestockWalkToGE: walkTo GE");
        MovementHelper.walkTo(GE_LOCATION);
        lastTravelClickTime = travelNow;
        paint.setCurrentStatus("🛒 → Grand Exchange");
        return antiBan.varyDelay(TravelWalkHelper.postClickDelayMs(config, random));
    }

    private int handleRestocking() {
        if (!config.botEnabled()) {
            currentState = FishingState.IDLE;
            return 200;
        }
        String[] method = getTargetMethod();
        String toolName = method != null && method.length > 2 ? method[2] : "";
        String baitName = method != null && method.length > 3 ? method[3] : "";

        List<RestockBuy> buysPreview = buildFishingRestockPlan(toolName, baitName);
        int coinsNeededPreview = estimateRequiredCoinsForRestockPlan(buysPreview);
        if (hasEmergencySellLootInInventory() && getInventoryCoinCount() < coinsNeededPreview) {
            boolean sold = sellInventoryFishForCoins();
            if (sold) {
                paint.setLastAntiBanAction("✓ Eerst loot verkocht, daarna kopen");
                return antiBan.varyDelay(randomDelay(800, 1300));
            }
        }

        if (restockNeedsCoinRaise) {
            boolean sold = sellInventoryFishForCoins();
            restockNeedsCoinRaise = false;
            if (sold) {
                paint.setLastAntiBanAction("✓ GE coins raised via loot sale");
                return antiBan.varyDelay(randomDelay(800, 1300));
            }
        }

        List<RestockBuy> buys = buildFishingRestockPlan(toolName, baitName);
        if (buys.isEmpty() && stillNeedsFishingSupplies(toolName, baitName)) {
            debug("handleRestocking: leeg koopplan maar tool/bait ontbreekt nog → bank");
            closeGeIfOpen();
            saveBankPosition();
            currentState = FishingState.WALKING_TO_BANK;
            paint.setCurrentStatus("→ Bank (" + toolName + " ophalen)");
            return antiBan.varyDelay(randomDelay(1000, 1800));
        }
        if (buys.isEmpty()) {
            debug("handleRestocking: niets te kopen, supplies OK");
            bankRetries = 0;
            currentState = FishingState.IDLE;
            return 600;
        }

        int totalRequiredCoins = estimateRequiredCoinsForRestockPlan(buys);
        String rsnCoins = BankSnapshotPlanner.currentDisplayName();
        int invCoins = getInventoryCoinCount();
        if (invCoins < totalRequiredCoins) {
            if (Bank.isOpen()) {
                withdrawCoinsForGeIfNeeded(totalRequiredCoins);
                invCoins = getInventoryCoinCount();
            } else if (BankSnapshotPlanner.shouldWithdrawBankCoinsBeforeGe(rsnCoins, totalRequiredCoins)) {
                debug("handleRestocking: inv gp=" + invCoins + "/" + totalRequiredCoins
                        + " bankSnap=" + BankSnapshotPlanner.knownBankCoinsQty(rsnCoins) + " → bank");
                pendingGeCoinsWithdrawTarget = totalRequiredCoins;
                closeGeIfOpen();
                saveBankPosition();
                currentState = FishingState.WALKING_TO_BANK;
                paint.setCurrentStatus("→ Bank (coins ophalen voor GE)");
                return antiBan.varyDelay(randomDelay(1000, 1800));
            }
            paint.setLastAntiBanAction("⚠ Coins laag voor fishing restock: " + invCoins + "/" + totalRequiredCoins
                    + " (bankSnap=" + BankSnapshotPlanner.knownBankCoinsQty(rsnCoins) + ")");
        }

        for (RestockBuy buy : buys) {
            if (!config.botEnabled()) {
                currentState = FishingState.IDLE;
                return 200;
            }
            int coinsNow = getInventoryCoinCount();
            int needCoinsForThisBuy = Math.max(1, buy.targetQty) * Math.max(1, buy.pricePerItem);
            if (coinsNow < needCoinsForThisBuy) {
                boolean sold = sellInventoryFishForCoins();
                if (sold) {
                    paint.setCurrentStatus("🛒 GE: loot verkopen voor gp");
                    return antiBan.varyDelay(randomDelay(800, 1300));
                }
                withdrawCoinsForGeIfNeeded(needCoinsForThisBuy);
                coinsNow = getInventoryCoinCount();
                if (coinsNow < needCoinsForThisBuy) {
                    String rsn = BankSnapshotPlanner.currentDisplayName();
                    if (BankSnapshotPlanner.shouldWithdrawBankCoinsBeforeGe(rsn, needCoinsForThisBuy)) {
                        debug("handleRestocking: gp in bank-snapshot → bank voor " + buy.itemName);
                        pendingGeCoinsWithdrawTarget = needCoinsForThisBuy;
                        closeGeIfOpen();
                        saveBankPosition();
                        currentState = FishingState.WALKING_TO_BANK;
                        paint.setCurrentStatus("→ Bank (coins voor " + buy.itemName + ")");
                        return antiBan.varyDelay(randomDelay(1000, 1800));
                    }
                    paint.setLastAntiBanAction("⚠ Te weinig coins voor " + buy.itemName + ": "
                            + coinsNow + "/" + needCoinsForThisBuy);
                    debug("handleRestocking: coin-gate blokkeert buy " + buy.itemName
                            + " coins=" + coinsNow + " nodig=" + needCoinsForThisBuy);
                    closeGeIfOpen();
                    pendingImpsCashFarmRequest = new ImpsCashFarmRequest(
                            1, buy.itemName, coinsNow, needCoinsForThisBuy);
                    paint.setCurrentStatus("⚠ Te weinig gp voor " + buy.itemName + " → Imps");
                    currentState = FishingState.IDLE;
                    return antiBan.varyDelay(randomDelay(1000, 1800));
                }
            }
            paint.setCurrentStatus("🛒 Kopen: " + buy.targetQty + "x " + buy.itemName);
            GeRestockHelper.RestockResult result = GeRestockHelper.buyWithEscalation(
                    buy.itemName,
                    buy.targetQty,
                    buy.pricePerItem,
                    config::botEnabled
            );
            if (result == GeRestockHelper.RestockResult.SUCCESS) {
                continue;
            }
            if (result == GeRestockHelper.RestockResult.BLOCKED_BY_ACCOUNT_POLICY) {
                paint.setCurrentStatus("⏹ GE-shop uit: " + buy.itemName);
                paint.setLastAntiBanAction("⏹ Account policy blokkeert " + buy.itemName);
                bankRetries = MAX_BANK_RETRIES;
                currentState = FishingState.IDLE;
                return antiBan.varyDelay(randomDelay(2000, 3000));
            }
            if (result == GeRestockHelper.RestockResult.GE_NOT_AVAILABLE) {
                paint.setLastAntiBanAction("⚠ Geen GE Clerk gevonden");
                debug("handleRestocking: GE niet beschikbaar");
                currentState = FishingState.RESTOCK_WALKING_TO_GE;
                paint.setCurrentStatus("🛒 → Grand Exchange (clerk)");
                return antiBan.varyDelay(randomDelay(1000, 2000));
            }
            paint.setLastAntiBanAction("⚠ GE koop gefaald: " + buy.itemName + " — retry GE");
            debug("handleRestocking: buy FAILED item=" + buy.itemName + " -> RESTOCK_WALKING_TO_GE");
            currentState = FishingState.RESTOCK_WALKING_TO_GE;
            paint.setCurrentStatus("🛒 GE retry: " + buy.itemName);
            return antiBan.varyDelay(randomDelay(1200, 2200));
        }

        paint.setLastAntiBanAction("✓ Fishing restock compleet (" + buys.size() + " items)");
        debug("handleRestocking: restock plan success");
        bankRetries = 0;
        currentState = FishingState.IDLE;
        return antiBan.varyDelay(randomDelay(1000, 2000));
    }

    private static final class RestockBuy {
        final String itemName;
        final int targetQty;
        final int pricePerItem;

        RestockBuy(String itemName, int targetQty, int pricePerItem) {
            this.itemName = itemName;
            this.targetQty = targetQty;
            this.pricePerItem = Math.max(1, pricePerItem);
        }
    }

    private List<RestockBuy> buildFishingRestockPlan(String toolName, String baitName) {
        List<RestockBuy> out = new ArrayList<>();
        String tool = toolName == null ? "" : toolName.trim();
        String bait = baitName == null ? "" : baitName.trim();

        addToolRestockIfNeeded(out, tool);

        // Eerst tool kopen; pas daarna bait (voorkomt open/sluit-loop op 1000x bait zonder rod/gp).
        if (!tool.isEmpty() && !hasTool(tool)) {
            return out;
        }

        int baitRestockQty = Math.max(config.fishingBaitMin(), config.fishingRestockAmount());
        baitRestockQty = Math.min(baitRestockQty, FISHING_RESTOCK_STACK_TARGET);

        if ("Fishing bait".equalsIgnoreCase(bait)) {
            if (Inventory.getCount(true, "Fishing bait") < baitRestockQty) {
                out.add(new RestockBuy("Fishing bait", baitRestockQty, Math.max(1, config.fishingBaitPrice())));
            }
        } else if ("Feather".equalsIgnoreCase(bait)) {
            if (Inventory.getCount(true, "Feather") < baitRestockQty) {
                out.add(new RestockBuy("Feather", baitRestockQty, Math.max(1, config.fishingFeatherPrice())));
            }
        }
        return out;
    }

    private boolean stillNeedsFishingSupplies(String toolName, String baitName) {
        String tool = toolName == null ? "" : toolName.trim();
        String bait = baitName == null ? "" : baitName.trim();
        return (!tool.isEmpty() && !hasTool(tool)) || (!bait.isEmpty() && !Inventory.contains(bait));
    }

    private void addToolRestockIfNeeded(List<RestockBuy> out, String tool) {
        String t = tool == null ? "" : tool.trim();
        if (t.isEmpty() || hasTool(t)) {
            return;
        }
        out.add(new RestockBuy(t, 1, gePriceForTool(t)));
    }

    private int gePriceForTool(String tool) {
        if (tool == null) {
            return GE_PRICE_DEFAULT_TOOL;
        }
        switch (tool.toLowerCase(Locale.ROOT)) {
            case "fishing rod":
                return GE_PRICE_FISHING_ROD;
            case "fly fishing rod":
                return GE_PRICE_FLY_ROD;
            case "barbarian rod":
                return GE_PRICE_FISHING_ROD;
            case "small fishing net":
                return GE_PRICE_SMALL_NET;
            case "harpoon":
                return GE_PRICE_HARPOON;
            case "lobster pot":
                return GE_PRICE_LOBSTER_POT;
            default:
                return GE_PRICE_DEFAULT_TOOL;
        }
    }

    private void closeGeIfOpen() {
        try {
            if (GrandExchange.isOpen()) {
                net.storm.sdk.input.Keyboard.type(
                        String.valueOf((char) java.awt.event.KeyEvent.VK_ESCAPE), false);
                sleep(300, 500);
            }
        } catch (Exception ignored) {
        }
    }

    private int estimateRequiredCoinsForRestockPlan(List<RestockBuy> buys) {
        int total = 0;
        for (RestockBuy b : buys) {
            int have = Inventory.getCount(true, b.itemName);
            int need = Math.max(0, b.targetQty - have);
            total += need * Math.max(1, b.pricePerItem);
        }
        return Math.max(0, total);
    }

    private boolean sellInventoryFishForCoins() {
        if (!config.botEnabled()) return false;
        if (!GrandExchange.isOpen()) {
            try {
                GrandExchange.open();
                sleep(900, 1500);
            } catch (Exception ignored) {
            }
        }
        if (!GrandExchange.isOpen()) {
            return false;
        }
        boolean soldAny = false;
        String[] sellOrder = {"Black bead", "Red bead", "Yellow bead", "White bead", "Mind talisman"};
        for (String n : sellOrder) {
            if (!config.botEnabled()) return soldAny;
            int qty = Math.max(0, Inventory.getCount(true, n));
            if (qty <= 0) continue;
            int fallbackPrice = resolveEmergencySellPriceEach(n);
            int gpEach = resolveBeadFirstTryPriceEach(n, fallbackPrice);
            boolean done = tryGeSell(n, qty, gpEach);
            if (!done && shouldUseBeadMarketFirst(n) && gpEach != fallbackPrice) {
                debug("sellInventoryFishForCoins: bead first-try niet verkocht (" + n + " @" + gpEach
                        + ") -> fallback @" + fallbackPrice);
                sleep(260, 460);
                done = tryGeSell(n, qty, fallbackPrice);
                gpEach = fallbackPrice;
            }
            if (done) {
                soldAny = true;
                debug("sellInventoryFishForCoins: verkocht " + qty + "x " + n + " @~" + gpEach + "gp");
            } else {
                debug("sellInventoryFishForCoins: verkoop mislukt voor " + n + " qty=" + qty);
            }
            sleep(700, 1200);
        }
        if (soldAny) {
            try {
                GrandExchange.collect(false);
            } catch (Exception ignored) {
            }
            sleep(700, 1200);
        }
        return soldAny;
    }

    private boolean tryGeSell(String itemName, int qty, int gpEach) {
        try {
            return GrandExchange.exchange(false, itemName, qty, Math.max(1, gpEach), true, true);
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean hasEmergencySellLootInInventory() {
        String[] sellOrder = {"Black bead", "Red bead", "Yellow bead", "White bead", "Mind talisman"};
        for (String n : sellOrder) {
            if (Inventory.getCount(true, n) > 0) return true;
        }
        return false;
    }

    private int resolveEmergencySellPriceEach(String itemName) {
        if (itemName == null || itemName.isEmpty()) return 1;
        CombatBotConfig.GeSellPriceMode mode = config.geSellPriceMode();
        if (mode == CombatBotConfig.GeSellPriceMode.FIXED) {
            return Math.max(1, config.geSellFixedPrice());
        }

        int guide = resolveGuidePriceByItemName(itemName);
        if (guide <= 0) {
            return estimateFastSellPriceEach(itemName);
        }
        int configuredBelow = Math.max(1, Math.min(50, config.geSellPercentBelow()));
        int jitterDown = random.nextInt(4); // 0..3%
        int effectiveBelow = Math.max(1, configuredBelow - jitterDown);
        int p = (int) Math.floor(guide * (100 - effectiveBelow) / 100.0);
        int out = Math.max(1, p);
        debug("resolveEmergencySellPriceEach: " + itemName
                + " guide=" + guide
                + " mode=%below"
                + " cfg=" + configuredBelow
                + " eff=" + effectiveBelow
                + " => " + out + "gp");
        return out;
    }

    private boolean shouldUseBeadMarketFirst(String itemName) {
        if (!config.geSellBeadMarketFirst() || itemName == null) return false;
        String n = itemName.toLowerCase(Locale.ROOT);
        return n.contains("bead");
    }

    private int resolveBeadFirstTryPriceEach(String itemName, int fallbackPrice) {
        if (!shouldUseBeadMarketFirst(itemName)) {
            return Math.max(1, fallbackPrice);
        }
        int guide = resolveGuidePriceByItemName(itemName);
        if (guide <= 0) {
            return Math.max(1, fallbackPrice);
        }
        // Gebaseerd op wiki/guide-market: eerst ~5% lager proberen.
        int beadFirst = Math.max(1, (int) Math.floor(guide * 0.95));
        debug("resolveBeadFirstTryPriceEach: " + itemName + " guide=" + guide + " => firstTry=" + beadFirst
                + ", fallback=" + fallbackPrice);
        return beadFirst;
    }

    private int resolveGuidePriceByItemName(String itemName) {
        if (itemName == null || itemName.isEmpty()) return -1;
        try {
            IInventoryItem invItem = Inventory.getFirst(i ->
                    i != null && i.getName() != null && i.getName().equalsIgnoreCase(itemName));
            if (invItem != null && invItem.getId() > 0) {
                int p = Prices.getItemPrice(invItem.getId());
                if (p > 0) return p;
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    private int estimateFastSellPriceEach(String itemName) {
        if (itemName == null) return 1;
        String n = itemName.toLowerCase(Locale.ROOT);
        if (n.contains("fiendish ashes")) return 220;
        if (n.contains("mind talisman")) return 180;
        if (n.contains("bead")) return 120;
        if (n.contains("salmon")) return 50;
        if (n.contains("trout")) return 35;
        if (n.contains("pike")) return 28;
        if (n.contains("herring")) return 20;
        if (n.contains("sardine")) return 16;
        if (n.contains("anchovies")) return 14;
        if (n.contains("shrimp")) return 12;
        if (n.contains("burnt")) return 1;
        return 10;
    }

    private boolean isEmergencySellLootName(String itemName) {
        if (itemName == null || itemName.isEmpty()) return false;
        String n = itemName.trim().toLowerCase(Locale.ROOT);
        // Fishing emergency-cash: ALLEEN imp-loot (beads + mind talisman), geen (raw/cooked/burnt) fish.
        return n.contains("bead") || n.equals("mind talisman");
    }

    private List<String> getImpGeSellItemsForEmergencyCoins() {
        String raw = config.geSellLootItems();
        List<String> out = new ArrayList<>();
        if (raw != null && !raw.trim().isEmpty()) {
            for (String s : raw.split(",")) {
                String t = s != null ? s.trim() : "";
                if (!t.isEmpty()) out.add(t);
            }
        }
        if (!out.isEmpty()) return out;
        for (String s : DEFAULT_IMPS_GE_SELL_ITEMS) out.add(s);
        return out;
    }

    // ===================== COOKING =====================

    private boolean hasRawFishToCook() {
        int cookingLevel = Skills.getLevel(Skill.COOKING);
        var items = Inventory.getAll(item ->
                item != null && item.getName() != null
                        && isRawFish(item.getName())
                        && FishingConfig.canCook(item.getName(), cookingLevel));
        return items != null && !items.isEmpty();
    }

    private ITileObject findNearestCookObject() {
        IPlayer local = Players.getLocal();
        WorldPoint myPos = local != null ? local.getWorldLocation() : null;
        boolean atBarbarian = fishingSpot != null && FishingConfig.isBarbarianFishingLocation(fishingSpot.getX(), fishingSpot.getY());
        final int maxTiles = atBarbarian ? 80 : 70;

        return TileObjects.getNearest(obj -> {
            if (obj == null || obj.getName() == null || !obj.hasAction("Cook")) return false;
            String name = obj.getName().toLowerCase();
            boolean isCookSpot = name.equals("fire") || name.contains("campfire") || name.contains("forester")
                    || name.contains("range") || name.contains("stove") || name.equals("cooking range");
            if (!isCookSpot) return false;
            if (myPos != null && myPos.distanceTo(obj.getWorldLocation()) > maxTiles) return false;
            return true;
        });
    }

    private static boolean isDraynorFishingArea(WorldPoint center) {
        return center != null && FishingConfig.isDraynorFishingLocation(center.getX(), center.getY(), null);
    }

    private static boolean isEdgeFishingArea(WorldPoint center) {
        if (center == null) return false;
        return center.distanceTo(EDGEVILLE_BANK) <= EDGEVILLE_FISHING_PROFILE_RADIUS
                || FishingConfig.isBarbarianFishingLocation(center.getX(), center.getY());
    }

    private WorldPoint findNearestFire() {
        if (fishingSpot != null && isDraynorFishingArea(fishingSpot)) {
            return DRAYNOR_FIRE;
        }
        if (fishingSpot != null && FishingConfig.isBarbarianFishingLocation(fishingSpot.getX(), fishingSpot.getY())) {
            return BARBARIAN_FIRE;
        }
        ITileObject fire = findNearestCookObject();
        if (fire != null) {
            return fire.getWorldLocation();
        }
        return null;
    }

    private ITileObject getCookObjectAtConfiguredFire() {
        WorldPoint target = cookingFireLocation != null ? cookingFireLocation : findNearestFire();
        if (target == null) return null;
        return TileObjects.getNearest(obj -> obj != null
                && obj.getWorldLocation() != null
                && obj.hasAction("Cook")
                && obj.getWorldLocation().distanceTo(target) <= 1);
    }

    private int handleWalkingToFire() {
        if (cookingFireLocation == null) {
            cookingFireLocation = findNearestFire();
            if (cookingFireLocation == null) {
                needsCooking = false;
                return 600;
            }
        }

        IPlayer local = Players.getLocal();
        if (local == null) return 1000;

        int distToFire = local.getWorldLocation().distanceTo(cookingFireLocation);
        // Pas naar cooking als we echt dichtbij het vaste vuur staan.
        if (distToFire <= 2) {
            currentState = FishingState.COOKING;
            return 600;
        }

        // Ook tijdens bewegen door-clicken op een menselijk reclick-ritme.
        if (!TravelWalkHelper.reclickReady(lastTravelClickTime, config)) {
            return antiBan.varyDelay(TravelWalkHelper.shortWaitMs(random));
        }

        // Dichtbij: forceer korte stappen zodat we niet halverwege blijven hangen.
        boolean walkIssued = distToFire <= 8
                ? walkTowardTarget(cookingFireLocation, 6)
                : MovementHelper.walkTo(cookingFireLocation);
        if (!walkIssued) {
            walkIssued = walkTowardTarget(cookingFireLocation, 20);
        }
        if (!walkIssued) {
            paint.setCurrentStatus("⚠ Geen pad naar vuur");
            return antiBan.varyDelay(randomDelay(1000, 1800));
        }

        lastTravelClickTime = System.currentTimeMillis();
        return antiBan.varyDelay(TravelWalkHelper.postClickDelayMs(config, random));
    }

    private long cookingFireClickTime = 0;
    private static final long COOKING_FIRE_LOCK_MS = 5000;

    public void onGameMessage(String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        String lower = message.replaceAll("<[^>]*>", "").toLowerCase();
        if (!lower.contains("you can't cook that") && !lower.contains("you cannot cook that")) {
            return;
        }
        needsCooking = false;
        cookingFireClickTime = 0L;
        cookingFireLocation = null;
        currentState = effectiveFishingDropFish() ? FishingState.DROPPING : FishingState.WALKING_TO_BANK;
        String attempted = lastCookingAttemptItemName != null ? lastCookingAttemptItemName : "?";
        paint.setLastAntiBanAction("Fishing: cook skip (" + attempted + ")");
        debug("onGameMessage: can't cook that for item='" + attempted + "' rawInv=" + getRawFishInventorySnapshot()
                + " → cooking gestopt, door naar " + currentState);
        lastCookingAttemptItemName = null;
    }

    private IInventoryItem getCookableRawFish() {
        int cookingLevel = Skills.getLevel(Skill.COOKING);
        return Inventory.getFirst(item ->
                item != null
                        && item.getName() != null
                        && isRawFish(item.getName())
                        && FishingConfig.canCook(item.getName(), cookingLevel));
    }

    private String getRawFishInventorySnapshot() {
        List<String> raw = new ArrayList<>();
        for (IInventoryItem item : Inventory.getAll()) {
            if (item == null || item.getName() == null) continue;
            if (isRawFish(item.getName())) {
                raw.add(item.getName() + "x" + Math.max(1, item.getQuantity()));
            }
        }
        if (raw.isEmpty()) return "-";
        return String.join(", ", raw);
    }

    private boolean tryOpenDraynorBankDirect(IPlayer local) {
        if (local == null) return false;
        WorldPoint myPos = local.getWorldLocation();
        if (myPos == null) return false;
        if (myPos.distanceTo(DRAYNOR_FIRE) > 18 && !isDraynorFishingArea(fishingSpot)) {
            return false;
        }
        ITileObject booth = TileObjects.getNearest(obj ->
                obj != null
                        && obj.getWorldLocation() != null
                        && obj.getName() != null
                        && obj.getName().toLowerCase().contains("bank")
                        && (obj.hasAction("Bank") || obj.hasAction("Use"))
                        && myPos.distanceTo(obj.getWorldLocation()) <= 12);
        if (booth == null) {
            return false;
        }
        int dist = myPos.distanceTo(booth.getWorldLocation());
        if (dist <= 6) {
            String action = booth.hasAction("Bank") ? "Bank" : "Use";
            booth.interact(action);
            paint.setLastAntiBanAction("🏦 Draynor bank direct openen");
            return true;
        }
        return walkTowardTarget(booth.getWorldLocation(), 8);
    }

    private int handleCooking() {
        IPlayer local = Players.getLocal();
        if (local == null) return 1000;

        if (!hasRawFishToCook()) {
            needsCooking = false;
            cookingFireLocation = null;
            cookingFireClickTime = 0;
            if (effectiveFishingDropFish()) {
                currentState = FishingState.DROPPING;
                return 600;
            } else {
                if (Bank.isOpen() || BankHelper.interactIfNearby() || tryOpenDraynorBankDirect(local)) {
                    currentState = FishingState.BANKING;
                    paint.setLastAntiBanAction("🏦 Direct bank openen na cooking");
                    return antiBan.varyDelay(randomDelay(900, 1400));
                }
                saveBankPosition();
                currentState = FishingState.WALKING_TO_BANK;
                return 600;
            }
        }

        net.storm.api.domain.widgets.IWidget cookWidget = net.storm.sdk.widgets.Widgets.get(270, 0);
        if (cookWidget != null && !cookWidget.isHidden()) {
            net.storm.sdk.input.Keyboard.type(String.valueOf((char) java.awt.event.KeyEvent.VK_SPACE), false);
            paint.setLastAntiBanAction("🔥 Kookscherm: spatie gedrukt");
            cookingFireClickTime = System.currentTimeMillis();
            return antiBan.varyDelay(randomDelay(1200, 2000));
        }

        if (Production.isOpen()) {
            var rawFishForProd = getCookableRawFish();
            if (rawFishForProd != null) {
                lastCookingAttemptItemName = rawFishForProd.getName();
                Production.chooseOption(rawFishForProd.getName());
                paint.setLastAntiBanAction("🔥 Koken: " + rawFishForProd.getName());
            } else {
                needsCooking = false;
                currentState = effectiveFishingDropFish() ? FishingState.DROPPING : FishingState.WALKING_TO_BANK;
                return antiBan.varyDelay(randomDelay(500, 900));
            }
            cookingFireClickTime = System.currentTimeMillis();
            return antiBan.varyDelay(randomDelay(1200, 2000));
        }

        if (local.isAnimating()) {
            cookingFireClickTime = System.currentTimeMillis();
            paint.setCurrentStatus("🔥 Aan het koken...");
            return antiBan.varyDelay(randomDelay(600, 1200));
        }

        if (System.currentTimeMillis() - cookingFireClickTime < COOKING_FIRE_LOCK_MS) {
            paint.setCurrentStatus("🔥 Wacht op kookscherm...");
            return antiBan.varyDelay(randomDelay(400, 800));
        }

        ITileObject fire = getCookObjectAtConfiguredFire();
        if (fire == null) {
            currentState = FishingState.WALKING_TO_FIRE;
            paint.setCurrentStatus("🔥 Wacht op juist vuur...");
            return antiBan.varyDelay(randomDelay(700, 1200));
        }

        var rawFish = getCookableRawFish();

        if (rawFish != null) {
            lastCookingAttemptItemName = rawFish.getName();
            rawFish.useOn(fire);
            cookingFireClickTime = System.currentTimeMillis();
            paint.setLastAntiBanAction("🔥 Vis op vuur gelegd, wacht op interface...");
            return antiBan.varyDelay(randomDelay(2000, 3500));
        }

        needsCooking = false;
        cookingFireClickTime = 0;
        lastCookingAttemptItemName = null;
        return 600;
    }

    // ===================== HELPERS: VIS HERKENNING =====================

    private boolean isRawFish(String name) {
        if (name == null) return false;
        return name.startsWith("Raw ") || name.equals("Shrimps") || name.equals("Anchovies");
    }

    private boolean isCookedFish(String name) {
        if (name == null) return false;
        for (String[] data : FishingConfig.FISH_DATA) {
            if (data[1].equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    // ===================== BANK HELPERS =====================

    private void saveBankPosition() {
        if (preBankPosition == null) {
            IPlayer local = Players.getLocal();
            if (local != null) {
                WorldPoint pos = local.getWorldLocation();
                // Sla positie NIET op als we bij een bank staan
                if (pos != null && !BankHelper.isNearAnyBank(pos)) {
                    preBankPosition = pos;
                }
            }
        }
    }

    private boolean isAtLocation(WorldPoint target) {
        return isAtLocationWithRange(target, LOCATION_THRESHOLD);
    }

    private boolean isAtLocationWithRange(WorldPoint target, int range) {
        IPlayer local = Players.getLocal();
        if (local == null) return true;
        return local.getWorldLocation().distanceTo(target) <= range;
    }

    private boolean tryOpenBank() {
        return BankHelper.tryOpenFullBank();
    }

    // ===================== WALK TO BANK (unified) =====================

    private int handleWalkToBank() {
        if (Bank.isOpen()) {
            debug("handleWalkToBank: bank al open → BANKING");
            currentState = FishingState.BANKING;
            return 600;
        }

        IPlayer local = Players.getLocal();
        if (local == null) return 1000;

        if (BankHelper.interactIfNearby()) {
            debug("handleWalkToBank: interactIfNearby");
            paint.setLastAntiBanAction("🏦 Bank openen");
            return antiBan.varyDelay(randomDelay(650, 1050));
        }
        if (tryOpenDraynorBankDirect(local)) {
            debug("handleWalkToBank: Draynor direct bank/open");
            return antiBan.varyDelay(randomDelay(550, 950));
        }

        // Niet wachten tot stilstand: onderweg periodiek reclicken.
        if (!fishingBankReclickReady(lastTravelClickTime)) {
            return antiBan.varyDelay(randomDelay(120, 260));
        }

        if (!walkToFishingBank()) {
            if (BankHelper.wasLastWalkSkippedDueToSnapshot()) {
                debug("handleWalkToBank: snapshot → GE");
                bankSessionCompleted = false;
                currentState = FishingState.RESTOCK_WALKING_TO_GE;
                paint.setCurrentStatus("🛒 → Grand Exchange");
                return handleRestockWalkToGE();
            }
            if (config.botEnabled()) {
                paint.setLastAntiBanAction("⚠ Geen bank gevonden!");
            }
            return 5000;
        }
        lastTravelClickTime = System.currentTimeMillis();
        return antiBan.varyDelay(randomDelay(220, 480));
    }

    private boolean fishingBankReclickReady(long lastClickMs) {
        long seed = (lastClickMs * 1103515245L + 12345L) & 0x7fffffffL;
        int ms = FISHING_BANK_RECLICK_MIN_MS + (int) (seed % (FISHING_BANK_RECLICK_MAX_MS - FISHING_BANK_RECLICK_MIN_MS + 1));
        return System.currentTimeMillis() - lastClickMs >= ms;
    }

    /**
     * Edgeville-bank coördinaten ({@link #EDGEVILLE_BANK}) — dat <i>is</i> al “naar die coords lopen”.
     * Als {@link MovementHelper#walkTo} faalt (geen pad in één keer), proberen we hetzelfde doel opnieuw via
     * {@link MovementHelper#walkToArea} (klein radius rond de tile) en {@link MovementHelper#walkTowardTarget}
     * (stappen langs de vector), wat langere routes soms wél triggert.
     */
    private boolean walkToEdgevilleBankReliable() {
        if (MovementHelper.walkTo(EDGEVILLE_BANK)) {
            return true;
        }
        debug("walkToEdgeville: walkTo faalde → walkToArea(r=3)");
        if (MovementHelper.walkToArea(EDGEVILLE_BANK, 3)) {
            return true;
        }
        debug("walkToEdgeville: walkToArea faalde → walkTowardTarget(maxStep=25)");
        if (MovementHelper.walkTowardTarget(EDGEVILLE_BANK, 25)) {
            return true;
        }
        debug("walkToEdgeville: movement-fallbacks gefaald");
        return false;
    }

    private boolean walkToVarrockWestBankReliable() {
        if (MovementHelper.walkTo(VARROCK_WEST_BANK)) {
            return true;
        }
        debug("walkToVarrockWest: walkTo faalde → walkToArea(r=3)");
        if (MovementHelper.walkToArea(VARROCK_WEST_BANK, 3)) {
            return true;
        }
        debug("walkToVarrockWest: walkToArea faalde → walkTowardTarget(maxStep=25)");
        if (MovementHelper.walkTowardTarget(VARROCK_WEST_BANK, 25)) {
            return true;
        }
        debug("walkToVarrockWest: movement-fallbacks gefaald");
        return false;
    }

    /**
     * Barbarian/Edgeville-profiel: Edgeville → Varrock West; bij totale mislukking callback (bot uit + logout).
     */
    private boolean walkBarbarianBankRouteWithFallbacks() {
        if (walkToEdgevilleBankReliable()) {
            consecutiveBankRoutePathFails = 0;
            return true;
        }
        debug("walkToFishingBank: Edgeville mislukt → Varrock West bank");
        if (walkToVarrockWestBankReliable()) {
            consecutiveBankRoutePathFails = 0;
            return true;
        }
        long now = System.currentTimeMillis();
        if (now - lastBankRoutePathFailMs > BANK_ROUTE_FAIL_CHAIN_WINDOW_MS) {
            consecutiveBankRoutePathFails = 0;
        }
        lastBankRoutePathFailMs = now;
        consecutiveBankRoutePathFails++;

        if (now < fatalBankRouteGraceUntilMs) {
            debug("walkToFishingBank: bank-route fail tijdens login/switch grace ("
                    + consecutiveBankRoutePathFails + "/" + BANK_ROUTE_FAILS_BEFORE_FATAL + "), nog niet fataal");
            return false;
        }
        if (consecutiveBankRoutePathFails < BANK_ROUTE_FAILS_BEFORE_FATAL) {
            debug("walkToFishingBank: bank-route fail "
                    + consecutiveBankRoutePathFails + "/" + BANK_ROUTE_FAILS_BEFORE_FATAL + " (nog retry)");
            return false;
        }

        String reason = "Vissen (Barbarian/Edgeville): pathfinding naar Edgeville én Varrock West bank "
                + BANK_ROUTE_FAILS_BEFORE_FATAL + "x gefaald — bot uitgezet en uitgelogd.";
        debug("FATAL: " + reason);
        if (fatalBankWalkFailure != null) {
            fatalBankWalkFailure.accept(reason);
        }
        return false;
    }

    /**
     * Bank route voor fishing.
     * Barbarian / Edgeville-visspot (center binnen profiel van Edgeville bank) → altijd pad naar Edgeville bank,
     * ook als je na re-login ergens anders staat — zelfde bank als bij de rivier.
     * Anders: dichtstbijzijnde F2P-bank via {@link BankHelper#walkToNearestFullBank()}.
     */
    private boolean walkToFishingBank() {
        IPlayer local = Players.getLocal();
        WorldPoint myPos = local != null ? local.getWorldLocation() : null;

        if (fishingSpot != null && fishingSpot.distanceTo(EDGEVILLE_BANK) <= 60) {
            debug("walkToFishingBank: Barbarian/Edgeville-profiel → Edgeville bank");
            return walkBarbarianBankRouteWithFallbacks();
        }
        if (myPos != null && myPos.distanceTo(EDGEVILLE_BANK) <= 60) {
            debug("walkToFishingBank: speler bij Edgeville → Edgeville bank");
            return walkBarbarianBankRouteWithFallbacks();
        }
        if (myPos != null && myPos.distanceTo(COOKING_GUILD_CENTER) <= COOKING_GUILD_AVOID_RADIUS) {
            debug("walkToFishingBank: Cooking Guild regio → Edgeville bank");
            return walkBarbarianBankRouteWithFallbacks();
        }
        debug("walkToFishingBank: walkToNearestFullBank");
        return walkToFishingBankWithSnapshot();
    }

    private boolean walkToFishingBankWithSnapshot() {
        String[] hoped = fishingHopedWithdrawItemNames();
        if (hoped.length == 0) {
            return BankHelper.walkToNearestFullBank();
        }
        return BankHelper.walkToNearestFullBank(hoped);
    }

    private String[] fishingHopedWithdrawItemNames() {
        String[] method = getTargetMethod();
        String tool = method[2];
        String bait = method[3] != null ? method[3].trim() : "";
        if (bait.isEmpty()) {
            return new String[] { tool };
        }
        return new String[] { tool, bait };
    }

    // ===================== UNIFIED BANKING (ÉÉN methode voor ALLES) =====================

    /**
     * Unified banking handler — vervangt alle aparte fetch/deposit handlers.
     * Eén bank visit:
     *   1) UBM.runBankSession() → depositeert junk, houdt tool+bait, withdrawt tekorten
     *   2) Als UBM zegt NEEDS_GE_RESTOCK → coins pakken + naar GE
     *   3) Als tool ontbreekt in bank → stop met duidelijke melding
     *   4) Bank sluiten → klaar
     */
    private int handleUnifiedBanking() {
        String[] methodGate = getTargetMethod();
        String toolGate = methodGate[2];
        String baitGate = methodGate[3] != null ? methodGate[3] : "";
        boolean suppliesOk = hasTool(toolGate) && (baitGate.isEmpty() || Inventory.contains(baitGate));
        // Oude bug: sessie "klaar" gezet terwijl tool/bait nog misten → volgende bankbezoek werd direct
        // gesloten zonder withdraw (oneindige loop bij bank). Alleen overslaan als supplies echt OK zijn.
        if (bankSessionCompleted && suppliesOk) {
            debug("handleUnifiedBanking: sessie al afgerond + supplies OK, bank sluiten");
            if (Bank.isOpen()) {
                Bank.close();
                sleep(300, 500);
            }
            currentState = FishingState.IDLE;
            return antiBan.varyDelay(randomDelay(1000, 2000));
        }
        if (bankSessionCompleted && !suppliesOk) {
            debug("handleUnifiedBanking: sessie-flag maar supplies missen → reset, UBM opnieuw");
            bankSessionCompleted = false;
        }

        if (!Bank.isOpen()) {
            tryOpenBank();
            return antiBan.varyDelay(randomDelay(1200, 1800));
        }

        // Wacht even zodat bank-interface volledig geladen is
        sleep(300, 500);

        // Retry guard: alleen tellen wanneer UBM daadwerkelijk runt (bank is open)
        bankRetries++;
        if (bankRetries > MAX_BANK_RETRIES) {
            debug("handleUnifiedBanking: max retries bereikt → IDLE");
            Bank.close();
            sleep(300, 500);
            paint.setCurrentStatus("⏹ Bank retries uitgeput");
            currentState = FishingState.IDLE;
            bankSessionCompleted = true;
            bankSessionCompletedTime = System.currentTimeMillis();
            return antiBan.varyDelay(randomDelay(3000, 5000));
        }

        debug("handleUnifiedBanking: start (poging " + bankRetries + "/" + MAX_BANK_RETRIES + ")");

        // Tel vis items vóór deposit (voor stats)
        String[] method = getTargetMethod();
        String toolName = method[2];
        String baitName = method[3];
        int fishCount = countFishInInventory(toolName, baitName);

        // Één UBM sessie: deposit alles behalve tool+bait, withdraw tekorten
        List<UniversalBankingManager.Requirement> reqs = buildFishingRequirements();
        UniversalBankingManager.BankSessionStatus status = bankingManager.runBankSession(reqs);

        debug("handleUnifiedBanking: UBM result=" + status.getResult());

        if (status.needsGeRestock()) {
            String missingItem = status.getRestockItemName();
            debug("handleUnifiedBanking: " + missingItem + " ontbreekt in bank");

            // Als we al minimaal genoeg bait hebben, niet naar GE pushen voor target-aanvulling.
            String[] methodForThreshold = getTargetMethod();
            String activeBait = methodForThreshold != null && methodForThreshold.length > 3 ? methodForThreshold[3] : "";
            int baitMin = Math.max(0, config.fishingBaitMin());
            int baitNow = (activeBait == null || activeBait.isEmpty()) ? 0 : getItemQuantity(activeBait);
            if (activeBait != null && !activeBait.isEmpty()
                    && missingItem != null && missingItem.equalsIgnoreCase(activeBait)
                    && baitNow >= baitMin) {
                debug("handleUnifiedBanking: bait >= min (" + baitNow + "/" + baitMin + ") -> geen GE nodig");
                bankSessionCompleted = true;
                bankSessionCompletedTime = System.currentTimeMillis();
                Bank.close();
                sleep(300, 500);
                paint.setLastAntiBanAction("✓ Bait voldoende voor nu: " + baitNow + " (min " + baitMin + ")");
                bankRetries = 0;
                currentState = FishingState.IDLE;
                paint.addFishBanked(fishCount);
                return antiBan.varyDelay(randomDelay(700, 1200));
            }

            // Markeer sessie als afgerond zodat we niet opnieuw UBM runnen
            bankSessionCompleted = true;
            bankSessionCompletedTime = System.currentTimeMillis();

            // Tool of bait mist: probeer GE restock flow.
            debug("handleUnifiedBanking: fishing restock enabled=" + config.fishingRestockEnabled()
                    + " missing=" + missingItem);
            if (config.fishingRestockEnabled()) {
                int[] planAndCoins = estimateRequiredAndAvailableCoinsForFishingPlan(toolName, baitName);
                int needCoins = planAndCoins[0];
                int availCoins = planAndCoins[1];
                if (availCoins >= needCoins) {
                    // GE-restock pad moet niet tegen bank retry-limiet aanlopen.
                    bankRetries = 0;
                    withdrawVarrockRunesForGeIfEnabled();
                    Bank.close();
                    sleep(300, 500);
                    currentState = FishingState.RESTOCK_WALKING_TO_GE;
                    paint.setLastAntiBanAction("⚠ Fishing kit mist (" + missingItem + ") → GE restock");
                    paint.addFishBanked(fishCount);
                    return antiBan.varyDelay(randomDelay(1000, 2000));
                } else {
                    // Coin-tekort: probeer eerst verkoopbare vis als coins-raise op GE.
                    boolean withdrewForSale = withdrawBankFishForEmergencySale();
                    withdrawVarrockRunesForGeIfEnabled();
                    Bank.close();
                    sleep(300, 500);
                    if (withdrewForSale) {
                        // Coins-raise gebeurt via GE; reset bank retry teller.
                        bankRetries = 0;
                        restockNeedsCoinRaise = true;
                        currentState = FishingState.RESTOCK_WALKING_TO_GE;
                        paint.setLastAntiBanAction("⚠ Coins laag (" + availCoins + "/" + needCoins + ") → eerst loot verkopen");
                        paint.addFishBanked(fishCount);
                        return antiBan.varyDelay(randomDelay(1000, 2000));
                    }
                    paint.setLastAntiBanAction("⚠ Geen geld voor fishing restock (" + availCoins + "/" + needCoins + ")");
                    paint.setCurrentStatus("⏹ Geen geld voor fishing kit");
                    pendingImpsCashFarmRequest = new ImpsCashFarmRequest(1, missingItem, availCoins, needCoins);
                    bankRetries = MAX_BANK_RETRIES;
                    currentState = FishingState.IDLE;
                    paint.addFishBanked(fishCount);
                    return antiBan.varyDelay(randomDelay(2000, 3000));
                }
            }

            // Restock uit → stop
            Bank.close();
            sleep(300, 500);
            paint.setLastAntiBanAction("⚠ " + missingItem + " op, restock uitgeschakeld");
            bankRetries = MAX_BANK_RETRIES;
            currentState = FishingState.IDLE;
            paint.addFishBanked(fishCount);
            return antiBan.varyDelay(randomDelay(2000, 3000));
        }

        // Succes! Verify dat we nu daadwerkelijk tool + bait hebben (inv + equipment voor tool)
        boolean hasToolNow = hasTool(toolName);
        boolean hasBaitNow = baitName.isEmpty() || Inventory.contains(baitName);

        // Kleine pauze voordat we bank sluiten (menselijk gedrag + verificatie ruimte)
        sleep(400, 700);
        Bank.close();
        sleep(300, 500);

        paint.addFishBanked(fishCount);

        if (hasToolNow && hasBaitNow) {
            // Alleen dan: volgende keer geen dubbele UBM in dezelfde "succes"-flow
            bankSessionCompleted = true;
            bankSessionCompletedTime = System.currentTimeMillis();
            bankRetries = 0;
            paint.setLastAntiBanAction("✓ Supplies compleet: " + toolName + (baitName.isEmpty() ? "" : " + " + baitName));
            debug("handleUnifiedBanking: supplies compleet, klaar");
        } else {
            // Geen sessie-flag: anders blijft bank open-sluit-loop zonder withdraw
            bankSessionCompleted = false;
            debug("handleUnifiedBanking: UBM zei OK maar tool=" + hasToolNow + " bait=" + hasBaitNow
                    + " → geen sessie-klaar; volgende tick opnieuw banken");
            paint.setLastAntiBanAction("⚠ Na bank: nog geen " + toolName + (baitName.isEmpty() ? "" : "/" + baitName)
                    + " — opnieuw proberen…");
        }

        currentState = FishingState.IDLE;
        return antiBan.varyDelay(randomDelay(600, 1000));
    }

    /** Tel vis-items in inventory (niet tool/bait). */
    private int countFishInInventory(String toolName, String baitName) {
        List<IInventoryItem> all = Inventory.getAll();
        if (all == null) return 0;
        int count = 0;
        for (IInventoryItem item : all) {
            if (item == null || item.getName() == null) continue;
            String name = item.getName();
            if (name.equals(toolName) || name.equals(baitName)) continue;
            if (isRawFish(name) || isCookedFish(name) || name.startsWith("Burnt ")) {
                count++;
            }
        }
        return count;
    }

    private boolean bankHasCoins() {
        return Bank.contains("Coins")
                || Bank.contains(item -> item != null && item.getName() != null
                && item.getName().equalsIgnoreCase("Coins"));
    }

    private void withdrawCoinsForGeIfNeeded(int minCoinsToHave) {
        if (!Bank.isOpen()) return;
        IInventoryItem coins = Inventory.getFirst("Coins");
        int have = coins != null ? coins.getQuantity() : 0;
        if (have >= minCoinsToHave) return;
        if (!bankHasCoins()) {
            debug("withdrawCoinsForGeIfNeeded: geen Coins in bank zichtbaar");
            return;
        }
        int need = minCoinsToHave - have;
        debug("withdrawCoinsForGeIfNeeded: withdraw need=" + need + " have=" + have);
        Bank.withdraw("Coins", need);
        sleep(300, 500);
        // Tweede poging als eerste klik niet genoeg was.
        int nowHave = getInventoryCoinCount();
        if (nowHave < minCoinsToHave && bankHasCoins()) {
            int stillNeed = minCoinsToHave - nowHave;
            debug("withdrawCoinsForGeIfNeeded: retry withdraw stillNeed=" + stillNeed);
            Bank.withdraw("Coins", stillNeed);
            sleep(300, 500);
        }
    }

    private int getRequiredCoinsForBaitRestock() {
        String[] method = getTargetMethod();
        String baitN = method != null && method.length > 3 ? method[3] : "";
        int amount = config.fishingRestockAmount();
        int pricePerBait = "Feather".equalsIgnoreCase(baitN) ? config.fishingFeatherPrice() : config.fishingBaitPrice();
        return amount * pricePerBait;
    }

    private int getInventoryCoinCount() {
        IInventoryItem coins = Inventory.getFirst("Coins");
        return coins != null ? coins.getQuantity() : 0;
    }

    private boolean canAffordBaitRestock() {
        int required = getRequiredCoinsForBaitRestock();
        debug("canAffordBaitRestock: nodig=" + required + " invCoins(vóór)=" + getInventoryCoinCount());
        withdrawCoinsForGeIfNeeded(required);
        int have = getInventoryCoinCount();
        debug("canAffordBaitRestock: invCoins(na withdraw)=" + have);
        return have >= required;
    }

    private int[] estimateRequiredAndAvailableCoinsForFishingPlan(String toolName, String baitName) {
        List<RestockBuy> buys = buildFishingRestockPlan(toolName, baitName);
        int required = estimateRequiredCoinsForRestockPlan(buys);
        if (Bank.isOpen()) {
            withdrawCoinsForGeIfNeeded(required);
        }
        int available = getInventoryCoinCount();
        String rsn = BankSnapshotPlanner.currentDisplayName();
        if (available < required && rsn != null) {
            long bankCoins = BankSnapshotPlanner.knownBankCoinsQty(rsn);
            available = (int) Math.min(Integer.MAX_VALUE, available + bankCoins);
        }
        return new int[]{required, available};
    }

    private boolean withdrawBankFishForEmergencySale() {
        if (!Bank.isOpen()) {
            return false;
        }
        ensureBankWithdrawNoted();
        // Alleen imp-loot voor emergency coins (nieuwe account/trade-limited): geen fish withdrawen.
        List<String> candidates = new ArrayList<>();
        candidates.add("Black bead");
        candidates.add("Red bead");
        candidates.add("Yellow bead");
        candidates.add("White bead");
        candidates.add("Mind talisman");
        boolean withdrewAny = false;
        for (String item : candidates) {
            if (!Bank.contains(item)) continue;
            try {
                Bank.withdrawAll(item);
                sleep(300, 600);
                if (Inventory.contains(item)) {
                    debug("withdrawBankFishForEmergencySale: " + item + " voor GE sale");
                    withdrewAny = true;
                }
            } catch (Exception ignored) {
            }
        }
        if (withdrewAny) {
            return true;
        }
        // Fallback: pak willekeurig bead/talisman item uit bank op naam.
        try {
            var fish = Bank.getFirst(i -> {
                if (i == null || i.getName() == null) return false;
                String n = i.getName().toLowerCase(Locale.ROOT);
                return n.contains("bead") || n.equals("mind talisman");
            });
            if (fish != null && fish.getName() != null) {
                Bank.withdrawAll(fish.getName());
                sleep(300, 600);
                return Inventory.contains(fish.getName());
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private void ensureBankWithdrawNoted() {
        if (!Bank.isOpen()) {
            return;
        }
        for (int i = 0; i < 3; i++) {
            try {
                Bank.setWithdrawMode(true);
                sleep(140, 260);
            } catch (Exception ignored) {
            }
        }
    }

    private int getItemQuantity(String itemName) {
        IInventoryItem inv = Inventory.getFirst(itemName);
        return inv != null ? inv.getQuantity() : 0;
    }

    private boolean staffCoversFire() {
        return Equipment.contains(item -> item != null && item.getName() != null
                && item.getName().toLowerCase().contains("fire") && item.getName().toLowerCase().contains("staff"));
    }

    private boolean staffCoversAir() {
        return Equipment.contains(item -> item != null && item.getName() != null
                && item.getName().toLowerCase().contains("air") && item.getName().toLowerCase().contains("staff"));
    }

    private void withdrawVarrockRunesForGeIfEnabled() {
        if (!Bank.isOpen() || !config.fishingUseVarrockTeleport()) return;
        // Fishing-specifiek:
        // - Air < 3 => probeer air staff te pakken + equippen.
        // - Geen air staff EN geen 3 air runes => later WALK naar GE.
        int magic = Skills.getLevel(Skill.MAGIC);
        if (magic < 25) {
            debug("withdrawVarrockRunesForGe: magic<25 -> walk");
            return;
        }

        if (getItemQuantity("Law rune") < 1 && Bank.contains("Law rune")) {
            Bank.withdraw("Law rune", Integer.MAX_VALUE);
            sleep(250, 450);
        }

        int airRunes = getItemQuantity("Air rune");
        if (airRunes < 3 && !staffCoversAir()) {
            boolean equippedAirStaff = tryWithdrawAndEquipAirStaffForFishing();
            if (!equippedAirStaff) {
                if (Bank.contains("Air rune")) {
                    Bank.withdraw("Air rune", Integer.MAX_VALUE);
                    sleep(250, 450);
                }
                airRunes = getItemQuantity("Air rune");
                if (airRunes < 3 && !staffCoversAir()) {
                    debug("withdrawVarrockRunesForGe: geen air staff en air<3 -> walk");
                    return;
                }
            }
        } else if (airRunes < 3 && Bank.contains("Air rune")) {
            Bank.withdraw("Air rune", Integer.MAX_VALUE);
            sleep(250, 450);
        }

        if (getItemQuantity("Fire rune") < 1 && !staffCoversFire() && Bank.contains("Fire rune")) {
            Bank.withdraw("Fire rune", Integer.MAX_VALUE);
            sleep(250, 450);
        }

        boolean hasLaw = getItemQuantity("Law rune") >= 1;
        boolean hasAirReq = staffCoversAir() || getItemQuantity("Air rune") >= 3;
        boolean hasFireReq = staffCoversFire() || getItemQuantity("Fire rune") >= 1;
        debug("withdrawVarrockRunesForGe: law=" + hasLaw + " airReq=" + hasAirReq + " fireReq=" + hasFireReq);
    }

    private boolean tryWithdrawAndEquipAirStaffForFishing() {
        if (!Bank.isOpen()) return false;
        if (staffCoversAir()) return true;

        String staffName = null;
        if (Inventory.contains("Staff of air")) {
            staffName = "Staff of air";
        } else if (Inventory.contains("Air battlestaff")) {
            staffName = "Air battlestaff";
        } else if (Bank.contains("Staff of air")) {
            Bank.withdraw("Staff of air", 1);
            sleep(250, 450);
            staffName = "Staff of air";
        } else if (Bank.contains("Air battlestaff")) {
            Bank.withdraw("Air battlestaff", 1);
            sleep(250, 450);
            staffName = "Air battlestaff";
        }

        if (staffName == null) return false;
        var staff = Inventory.getFirst(staffName);
        if (staff == null) return false;
        InventoryActionHelper.interact(config, staff, "Wield");
        sleep(300, 550);
        return staffCoversAir();
    }

    // ===================== VISSEN =====================

    private int handleFishing() {
        // Reset bank guards: we zijn nu daadwerkelijk aan het vissen
        bankSessionCompleted = false;

        IPlayer local = Players.getLocal();
        if (local == null) return 1000;

        long now = System.currentTimeMillis();
        if (now < fishingPostClickGraceUntil) {
            if (local.isAnimating()) {
                fishingPostClickGraceUntil = 0;
            } else {
                return antiBan.varyDelay(randomDelay(350, 750));
            }
        }

        if (local.isAnimating()) {
            return antiBan.varyDelay(randomDelay(600, 1200));
        }
        if (System.currentTimeMillis() - lastInteractTime < INTERACT_COOLDOWN_MS) {
            return antiBan.varyDelay(randomDelay(400, 800));
        }

        int delayMin = config.fishingInteractDelayMin();
        int delayMax = config.fishingInteractDelayMax();
        if (delayMin > 0 && delayMax > 0 && delayMax >= delayMin) {
            long timeSinceInteract = System.currentTimeMillis() - lastInteractTime;
            int requiredDelay = randomDelay(delayMin, delayMax);
            if (timeSinceInteract < requiredDelay) {
                return antiBan.varyDelay(randomDelay(400, 800));
            }
        }

        String[] method = getTargetMethod();
        INPC spot = findFishingSpot(method, local);

        if (spot != null) {
            String clickAction = chooseBestFishingAction(spot, method[1]);
            debug("handleFishing: interact " + clickAction + " op spot " + spot.getWorldLocation().getX() + "," + spot.getWorldLocation().getY());
            paint.addFishCaught();
            spot.interact(clickAction);
            lastInteractTime = System.currentTimeMillis();
            fishingPostClickGraceUntil = lastInteractTime
                    + randomDelay(FISHING_POST_CLICK_GRACE_MIN_MS, FISHING_POST_CLICK_GRACE_MAX_MS);
            return antiBan.varyDelay(randomDelay(400, 900));
        }
        debug("handleFishing: geen spot in range");
        return antiBan.varyDelay(randomDelay(1000, 2000));
    }

    // ===================== DROPPEN =====================

    private int handleDropping() {
        String[] method = getTargetMethod();
        String toolName = method[2];
        String baitName = method[3];

        var toDrop = Inventory.getAll(item ->
                item != null && item.getName() != null
                        && !item.getName().equals(toolName)
                        && !item.getName().equals(baitName)
                        && (isRawFish(item.getName())
                        || item.getName().startsWith("Burnt ")
                        || isCookedFish(item.getName()))
        );
        if (toDrop != null && !toDrop.isEmpty()) {
            debug("handleDropping: drop " + toDrop.size() + " items");
            for (var f : toDrop) {
                InventoryActionHelper.interact(config, f, "Drop");
                sleep(100, 300);
            }
            paint.addFishDropped(toDrop.size());
            return antiBan.varyDelay(randomDelay(600, 1000));
        }
        debug("handleDropping: niets om te droppen");
        return 600;
    }

    // ===================== WALK TO SPOT / BACK =====================

    private int handleWalkingToSpot() {
        if (fishingSpot == null) {
            debug("handleWalkingToSpot: fishingSpot=null");
            return 1000;
        }

        IPlayer local = Players.getLocal();
        if (local == null) return 1000;
        WorldPoint myPos = local.getWorldLocation();

        // Als een vis-spot al direct klikbaar is, meteen starten met vissen.
        if (tryDirectFishingFromCurrentPosition(local)) {
            return antiBan.varyDelay(randomDelay(900, 1500));
        }

        long now = System.currentTimeMillis();
        if (walkTargetSpot == null || (now - walkTargetSetTime > WALK_TARGET_TIMEOUT_MS)) {
            String[] method = getTargetMethod();
            walkTargetSpot = findNearestFishingSpotPositionInArea(method, local);
            walkTargetSetTime = now;
            if (walkTargetSpot == null) {
                debug("handleWalkingToSpot: fallback naar random tile binnen radius");
                walkTargetSpot = randomInnerFishingAreaTarget();
            }
        }

        boolean targetIsCenterFallback = walkTargetSpot != null && fishingSpot != null && walkTargetSpot.equals(fishingSpot);
        int distToTarget = walkTargetSpot != null ? myPos.distanceTo(walkTargetSpot) : Integer.MAX_VALUE;
        if (targetIsCenterFallback) {
            if (MovementHelper.distanceToArea(myPos, fishingSpot, getEffectiveAreaRadius()) <= 0) {
                String[] methodHere = getTargetMethod();
                if (findFishingSpot(methodHere, local) != null) {
                    debug("handleWalkingToSpot: binnen vis-radius + spot klikbaar (center-doel)");
                    walkTargetSpot = null;
                    return antiBan.varyDelay(randomDelay(400, 800));
                }
                // Oscillatie-bug: “binnen center-radius” terwijl er geen NPC geladen/binnen interact is
                // (bv. halverwege Edge-bank ↔ Barbarian-rivier) — niet stoppen; trek naar rivier-anchor.
                if (FishingConfig.isBarbarianFishingLocation(fishingSpot.getX(), fishingSpot.getY())) {
                    WorldPoint anchor = FishingConfig.getBarbarianRiverAnchor();
                    if (myPos.distanceTo(anchor) > 4) {
                        walkTargetSpot = anchor;
                        walkTargetSetTime = now;
                        debug("handleWalkingToSpot: geen NPC in gebied → walk naar rivier-anchor "
                                + anchor.getX() + "," + anchor.getY());
                    } else {
                        debug("handleWalkingToSpot: op rivier-anchor, nog geen NPC — val door naar walk-logica");
                    }
                } else {
                    debug("handleWalkingToSpot: binnen area zonder NPC (niet-Barbarian) — val door naar walk-logica");
                }
            }
        }

        targetIsCenterFallback = walkTargetSpot != null && fishingSpot != null && walkTargetSpot.equals(fishingSpot);
        distToTarget = walkTargetSpot != null ? myPos.distanceTo(walkTargetSpot) : Integer.MAX_VALUE;

        if (!targetIsCenterFallback) {
            String[] methodArrived = getTargetMethod();
            if (findFishingSpot(methodArrived, local) != null) {
                debug("handleWalkingToSpot: spot klikbaar (dist=" + distToTarget + ")");
                walkTargetSpot = null;
                return antiBan.varyDelay(randomDelay(400, 800));
            }
            if (distToTarget <= 2) {
                walkTargetSpot = findNearestFishingSpotPositionInArea(methodArrived, local);
                if (walkTargetSpot == null) {
                    walkTargetSpot = pickWalkTargetWhenInsideAreaWithoutSpot();
                }
                walkTargetSetTime = now;
                debug("handleWalkingToSpot: dichtbij doel maar geen spot — nieuw doel "
                        + (walkTargetSpot != null ? walkTargetSpot.getX() + "," + walkTargetSpot.getY() : "null"));
            }
        }

        long travelNow = System.currentTimeMillis();
        if (!TravelWalkHelper.reclickReady(lastTravelClickTime, config)) {
            return antiBan.varyDelay(TravelWalkHelper.shortWaitMs(random));
        }
        boolean walkIssued;
        if (targetIsCenterFallback) {
            // Lange afstand (bijv. na bank elders / teleport): walkToArea met kleine radius faalt vaak —
            // eerst het center-punt zelf, daarna eventueel area-nauwkeurigheid.
            int distCenter = myPos.distanceTo(fishingSpot);
            int areaR = Math.max(8, Math.min(getEffectiveAreaRadius(), 22));
            if (distCenter > 32) {
                walkIssued = MovementHelper.walkTo(fishingSpot);
                if (!walkIssued) {
                    walkIssued = MovementHelper.walkToArea(fishingSpot, Math.max(areaR, 12));
                }
            } else {
                walkIssued = MovementHelper.walkToArea(fishingSpot, areaR);
                if (!walkIssued) {
                    walkIssued = MovementHelper.walkTo(fishingSpot);
                }
            }
            if (!walkIssued) {
                int step = Math.min(35, Math.max(12, distCenter / 2));
                walkIssued = MovementHelper.walkTowardTarget(fishingSpot, step);
            }
        } else if (distToTarget > 14) {
            walkIssued = MovementHelper.walkTo(walkTargetSpot);
            if (!walkIssued && myPos.distanceTo(walkTargetSpot) > 35) {
                walkIssued = MovementHelper.walkTowardTarget(walkTargetSpot, 30);
            }
        } else {
            int step = FishingConfig.isBarbarianFishingLocation(fishingSpot.getX(), fishingSpot.getY()) ? 18 : 14;
            walkIssued = walkTowardTarget(walkTargetSpot, step);
        }
        if (!walkIssued) {
            paint.setCurrentStatus("⚠ Geen pad naar vis spot");
            return antiBan.varyDelay(randomDelay(1000, 1800));
        }
        lastTravelClickTime = travelNow;
        paint.setLastAntiBanAction("→ Vis spot");
        return antiBan.varyDelay(TravelWalkHelper.postClickDelayMs(config, random));
    }

    private int handleWalkingBackToSpot() {
        if (fishingSpot == null) {
            preBankPosition = null;
            return 600;
        }
        IPlayer local = Players.getLocal();
        if (local != null && tryDirectFishingFromCurrentPosition(local)) {
            preBankPosition = null;
            return antiBan.varyDelay(randomDelay(900, 1500));
        }
        if (isPlayerWithinFishingArea()) {
            preBankPosition = null;
            return 600;
        }
        long now = System.currentTimeMillis();
        if (!TravelWalkHelper.reclickReady(lastTravelClickTime, config)) {
            return antiBan.varyDelay(TravelWalkHelper.shortWaitMs(random));
        }
        WorldPoint me = local.getWorldLocation();
        int r = getEffectiveAreaRadius();
        int dist = me != null ? me.distanceTo(fishingSpot) : 0;
        boolean walkOk;
        if (dist > 32) {
            walkOk = MovementHelper.walkTo(fishingSpot);
            if (!walkOk) walkOk = MovementHelper.walkToArea(fishingSpot, Math.max(10, r));
        } else {
            walkOk = MovementHelper.walkToArea(fishingSpot, r);
            if (!walkOk) walkOk = MovementHelper.walkTo(fishingSpot);
        }
        if (!walkOk) {
            walkOk = MovementHelper.walkTowardTarget(fishingSpot, Math.min(35, Math.max(12, dist / 2)));
        }
        lastTravelClickTime = now;
        paint.setLastAntiBanAction("↩ Terug naar vis spot");
        return antiBan.varyDelay(TravelWalkHelper.postClickDelayMs(config, random));
    }

    private WorldPoint randomInnerFishingAreaTarget() {
        if (fishingSpot == null) return null;
        if (FishingConfig.isBarbarianFishingLocation(fishingSpot.getX(), fishingSpot.getY())) {
            return FishingConfig.getBarbarianRiverAnchor();
        }
        int radius = getEffectiveAreaRadius();
        int inner = radius <= 2 ? Math.max(1, radius)
                : Math.max(1, Math.min(radius - 2, (int) Math.floor(radius * 0.70)));
        return MovementHelper.getRandomPointInRadius(fishingSpot, inner);
    }

    private WorldPoint pickWalkTargetWhenInsideAreaWithoutSpot() {
        if (fishingSpot != null && FishingConfig.isBarbarianFishingLocation(fishingSpot.getX(), fishingSpot.getY())) {
            return FishingConfig.getBarbarianRiverAnchor();
        }
        return randomInnerFishingAreaTarget();
    }

    private int handleIdleAtSpot() {
        if (fishingSpot == null) {
            return antiBan.varyDelay(randomDelay(1000, 2000));
        }
        IPlayer local = Players.getLocal();
        if (local == null) return antiBan.varyDelay(randomDelay(1000, 2000));
        long idleMs = System.currentTimeMillis() - idleStartTime;
        WorldPoint myPos = local.getWorldLocation();
        if (myPos == null) return antiBan.varyDelay(randomDelay(1000, 2000));

        boolean atBarbarian = FishingConfig.isBarbarianFishingLocation(fishingSpot.getX(), fishingSpot.getY());
        if (atBarbarian && idleMs > 1200 && !local.isMoving()) {
            String[] method = getTargetMethod();
            if (findFishingSpot(method, local) != null) {
                idleStartTime = 0;
                currentState = FishingState.FISHING;
                return handleFishing();
            }
            WorldPoint nearest = findNearestFishingSpotPositionInArea(method, local);
            WorldPoint stepTarget = nearest != null ? nearest : FishingConfig.getBarbarianRiverAnchor();
            int d0 = myPos.distanceTo(stepTarget);
            if (d0 > 1) {
                walkTowardTarget(stepTarget, Math.min(18, Math.max(6, d0 - 1)));
                idleStartTime = 0;
                currentState = FishingState.WALKING_TO_SPOT;
                walkTargetSpot = stepTarget;
                walkTargetSetTime = System.currentTimeMillis();
                paint.setLastAntiBanAction("→ Stap naar rivier-spot");
                return antiBan.varyDelay(randomDelay(600, 1200));
            }
        }
        if (!atBarbarian && idleMs > 2000 && !local.isMoving()) {
            int dx = fishingSpot.getX() - myPos.getX();
            int dy = fishingSpot.getY() - myPos.getY();
            int dist = Math.max(Math.abs(dx), Math.abs(dy));
            if (dist > 2) {
                int step = Math.min(3, dist);
                int stepX = myPos.getX() + (dx * step / Math.max(1, dist));
                int stepY = myPos.getY() + (dy * step / Math.max(1, dist));
                MovementHelper.walkTo(new WorldPoint(stepX, stepY, myPos.getPlane()));
                idleStartTime = 0;
                paint.setLastAntiBanAction("→ Stap naar center (spot zoeken)");
                return antiBan.varyDelay(randomDelay(800, 1500));
            }
        }
        return antiBan.varyDelay(randomDelay(1000, 2000));
    }

    // ===================== HELPERS =====================

    private boolean walkTowardTarget(WorldPoint target, int maxStep) {
        return MovementHelper.walkTowardTarget(target, maxStep);
    }

    private boolean hasTool(String toolName) {
        return Inventory.contains(toolName) || Equipment.contains(toolName);
    }

    private boolean tryDirectFishingFromCurrentPosition(IPlayer local) {
        if (local == null || local.isMoving() || local.isAnimating()) return false;
        if (System.currentTimeMillis() - lastInteractTime < INTERACT_COOLDOWN_MS) return false;
        String[] method = getTargetMethod();
        INPC spot = findFishingSpot(method, local);
        if (spot == null) return false;
        String clickAction = chooseBestFishingAction(spot, method[1]);
        debug("tryDirectFishing: direct interact " + clickAction + " op spot "
                + spot.getWorldLocation().getX() + "," + spot.getWorldLocation().getY());
        spot.interact(clickAction);
        lastInteractTime = System.currentTimeMillis();
        fishingPostClickGraceUntil = lastInteractTime
                + randomDelay(FISHING_POST_CLICK_GRACE_MIN_MS, FISHING_POST_CLICK_GRACE_MAX_MS);
        currentState = FishingState.FISHING;
        paint.setLastAntiBanAction("🎣 Direct spot vanaf huidige positie");
        return true;
    }

    private boolean isUsableFishingAction(String action) {
        if (action == null) return false;
        String a = action.toLowerCase();
        if (a.contains("net")) return hasTool("Small fishing net");
        if (a.contains("bait")) return hasTool("Fishing rod") && Inventory.contains("Fishing bait");
        if (a.contains("lure")) return hasTool("Fly fishing rod") && Inventory.contains("Feather");
        if (a.contains("use-rod")) {
            return (hasTool("Barbarian rod") || hasTool("Fishing rod")) && Inventory.contains("Fishing bait");
        }
        if (a.contains("cage")) return hasTool("Lobster pot");
        if (a.contains("harpoon")) return hasTool("Harpoon");
        return false;
    }

    private String chooseBestFishingAction(INPC spot, String preferredAction) {
        String[] actions = spot != null ? spot.getActions() : null;
        if (actions == null || actions.length == 0) {
            return preferredAction;
        }
        if (preferredAction != null) {
            for (String a : actions) {
                if (a != null && a.toLowerCase().contains(preferredAction.toLowerCase()) && isUsableFishingAction(a)) {
                    return a;
                }
            }
        }
        for (String a : actions) {
            if (a != null && isUsableFishingAction(a)) {
                return a;
            }
        }
        return preferredAction;
    }

    /**
     * AUTO Barbarian kiest op hoog level "Rod Fishing spot" + Barbarian rod + bait.
     * Veel accounts hebben alleen Fly rod + Feather — die werken op dezelfde rivier (Lure).
     * Als de Barbarian-kit nergens beschikbaar is maar de fly-kit wél, schakel dan over naar Lure.
     */
    private String[] maybeFallbackBarbarianToFlyLureIfNoBarbarianKit(String[] m, int fishingLevel) {
        if (fishingLevel < 20 || m == null || m.length < 4) {
            return m;
        }
        if (!isBarbarianRodRiverMethod(m)) {
            return m;
        }
        boolean kitInv = hasTool("Barbarian rod") && Inventory.contains("Fishing bait");
        if (kitInv) {
            return m;
        }
        boolean flyInv = hasTool("Fly fishing rod") && Inventory.contains("Feather");
        if (flyInv) {
            debug("maybeFallbackBarbarianToFlyLure: inv heeft fly kit, geen Barbarian rod+bait → Lure");
            return FishingConfig.getFlyLureMethod();
        }
        if (Bank.isOpen()) {
            boolean bankBarbKit = bankHasStack("Barbarian rod") && bankHasStack("Fishing bait");
            boolean bankFlyKit = bankHasStack("Fly fishing rod") && bankHasStack("Feather");
            if (!bankBarbKit && bankFlyKit) {
                debug("maybeFallbackBarbarianToFlyLure: bank heeft fly kit, geen complete Barbarian kit → Lure");
                return FishingConfig.getFlyLureMethod();
            }
            if (bankHasStack("Barbarian rod") && !bankHasStack("Fishing bait") && bankFlyKit) {
                debug("maybeFallbackBarbarianToFlyLure: wel Barbarian rod in bank maar geen bait; fly kit wel → Lure");
                return FishingConfig.getFlyLureMethod();
            }
        }
        return m;
    }

    private static boolean isBarbarianRodRiverMethod(String[] m) {
        return "Rod Fishing spot".equals(m[0])
                && "Use-rod".equalsIgnoreCase(m[1])
                && "Barbarian rod".equals(m[2]);
    }

    private boolean bankHasStack(String itemName) {
        try {
            return Bank.contains(itemName);
        } catch (Throwable t) {
            return false;
        }
    }

    private String[] getTargetMethod() {
        if (config.fishingUseSpecificMethod()) {
            String spotName = config.fishingSpotName();
            String action = config.fishingAction();
            String tool = FishingConfig.getToolForMethod(spotName, action);
            String bait = FishingConfig.getBaitForMethod(spotName, action);
            debug("getTargetMethod: SPECIFIC area=custom spot=" + spotName + " action=" + action + " tool=" + tool + " bait=" + bait);
            return new String[]{spotName, action, tool, bait};
        }
        int fishingLevel = Skills.getLevel(Skill.FISHING);
        // Barbarian eerst: die valt ook binnen Edge-bank-radius (isEdgeFishingArea), maar methode moet Barbarian-tabel zijn.
        if (fishingSpot != null && FishingConfig.isBarbarianFishingLocation(fishingSpot.getX(), fishingSpot.getY())) {
            String[] m = FishingConfig.getBestMethodForLevelBarbarian(fishingLevel);
            m = maybeFallbackBarbarianToFlyLureIfNoBarbarianKit(m, fishingLevel);
            debug("getTargetMethod: AUTO area=barbarian lvl=" + fishingLevel + " spot=" + m[0] + " action=" + m[1] + " tool=" + m[2] + " bait=" + m[3]);
            return m;
        }
        if (isEdgeFishingArea(fishingSpot)) {
            String[] m = FishingConfig.getBestMethodForLevelEdgeFeatherOnly(fishingLevel);
            debug("getTargetMethod: AUTO area=edge lvl=" + fishingLevel + " spot=" + m[0] + " action=" + m[1] + " tool=" + m[2] + " bait=" + m[3]);
            return m;
        }
        if (isDraynorFishingArea(fishingSpot)) {
            String[] m = FishingConfig.getBestMethodForLevelDraynor(fishingLevel);
            debug("getTargetMethod: AUTO area=draynor lvl=" + fishingLevel + " spot=" + m[0] + " action=" + m[1] + " tool=" + m[2] + " bait=" + m[3]);
            return m;
        }
        String[] m = FishingConfig.getBestMethodForLevelNonBarbarian(fishingLevel);
        debug("getTargetMethod: AUTO area=default lvl=" + fishingLevel + " spot=" + m[0] + " action=" + m[1] + " tool=" + m[2] + " bait=" + m[3]);
        return m;
    }

    private int randomDelay(int min, int max) {
        if (max <= min) {
            return min;
        }
        return min + random.nextInt(max - min);
    }

    private void sleep(int min, int max) {
        try {
            Thread.sleep(randomDelay(min, max));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
