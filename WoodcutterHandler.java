package com.combatbot;

import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.storm.api.domain.actors.INPC;
import net.storm.api.domain.actors.IPlayer;
import net.storm.api.domain.items.IInventoryItem;
import net.storm.api.domain.tiles.ITileObject;
import net.storm.sdk.entities.NPCs;
import net.storm.sdk.entities.Players;
import net.storm.sdk.entities.TileObjects;
import net.storm.sdk.items.Bank;
import net.storm.sdk.items.Equipment;
import net.storm.sdk.items.Inventory;
import net.storm.sdk.game.Skills;
import net.storm.sdk.widgets.Production;

import java.util.Locale;
import java.util.Random;

/**
 * WoodcutterHandler - Revised Edition.
 *
 * Verbeteringen t.o.v. origineel:
 * 1. Gecombineerde Gear Prep: Axe & Tinderbox in één banktrip.
 * 2. Strikte Area Check: Scant pas naar bomen als de bot in het WC Center is.
 * 3. Fake Tree Blacklist: Negeert specifieke 'nep-bomen' op vaste coördinaten (Draynor fix).
 * 4. Level Validatie: Checkt WC en FM levels voordat acties worden ondernomen.
 * 5. isNearBank: Voorkomt dat preBankPosition bij de bank wordt opgeslagen.
 * 6. Axe upgrade met level check.
 */
public class WoodcutterHandler {
    public static final class ImpsCashFarmRequest {
        public final String missingAxeName;
        public final int estimatedAxeCostGp;
        public final int currentCoinsGp;
        public final int estimatedShortfallGp;
        public final int estimatedImpsTrips;

        private ImpsCashFarmRequest(String missingAxeName, int estimatedAxeCostGp, int currentCoinsGp,
                                    int estimatedShortfallGp, int estimatedImpsTrips) {
            this.missingAxeName = missingAxeName;
            this.estimatedAxeCostGp = estimatedAxeCostGp;
            this.currentCoinsGp = currentCoinsGp;
            this.estimatedShortfallGp = estimatedShortfallGp;
            this.estimatedImpsTrips = estimatedImpsTrips;
        }
    }

    private final CombatBotConfig config;
    private final AntiBan antiBan;
    private final CombatBotPaint paint;
    private final Random random = new Random();
    private TileMarkerManager tileMarkerManager;

    private static final int LOCATION_THRESHOLD = 5;

    // Animation IDs — alleen firemaking/bonfire als uitzondering; we filteren NIET op
    // een specifiek woodcutting-ID omdat elke axe type een ander animation-ID heeft.
    private static final int ANIM_FIREMAKING  = 733;
    /**
     * Bonfire / Forester's campfire (RuneLite {@code AnimationID} 10563–10573) + kleine marge voor client-updates.
     * Was 10565–10580; 10563–10564 miste (o.a. arctic pine / blisterwood op forester-vuur).
     */
    private static final int ANIM_BONFIRE_CLUSTER_LOW  = 10563;
    private static final int ANIM_BONFIRE_CLUSTER_HIGH = 10580;

    private static boolean isBonfireAnimation(int animId) {
        return animId >= ANIM_BONFIRE_CLUSTER_LOW && animId <= ANIM_BONFIRE_CLUSTER_HIGH;
    }

    /**
     * Player woodcutting-poses (OSRS / RuneLite-nabootsing). Alleen gebruikt om “bonfire tail” niet te verwarren met echte kap.
     * Crystal (in)actief o.a. 8324, 10073 — anders denkt de bot dat je nog op het vuur zit terwijl je kapt.
     */
    private static boolean isWoodcuttingPlayerAnimation(int animId) {
        if (animId < 0) return false;
        if (animId >= 867 && animId <= 876) return true;
        if (animId >= 3282 && animId <= 3305) return true;
        if (animId == 8324 || animId == 10073) return true;
        return false;
    }

    // Geen hardcoded banklocatie meer — gebruik BankHelper voor dynamische bank detectie

    private static final String[] AXE_NAMES = {
            "Bronze axe", "Iron axe", "Steel axe", "Black axe",
            "Mithril axe", "Adamant axe", "Rune axe", "Dragon axe",
            "Infernal axe", "Crystal axe", "3rd age axe"
    };

    public enum WcState {
        CHOPPING, DROPPING, WALKING_TO_BANK, BANKING, WALKING_TO_TREES,
        WALKING_BACK_TO_SPOT, GEAR_PREP, IDLE, FIREMAKING
    }

    private WcState currentState = WcState.IDLE;
    private WorldPoint treeArea;
    private WorldPoint preBankPosition = null;

    private int areaRadius = 10;
    /** Eén gekozen punt in de radius per trip; na aankomst null zodat volgende trip nieuw punt pakt. */
    private WorldPoint centerWalkTarget = null;
    private long lastTravelClickTime = 0;

    private static final long IDLE_TIMEOUT_MS = 30_000;
    private long idleStartTime = 0;

    private long lastInteractTime = 0;
    private static final long INTERACT_COOLDOWN_MS = 1200;

    private long lastBankOpenAttempt = 0;
    private static final long BANK_OPEN_COOLDOWN_MS = 3000;

    private boolean firemakingFailed = false;
    private boolean firemakingMoveTile = false;
    private WorldPoint ownFireLocation = null;

    // Pathfinding failure tracking
    private int walkToTreesFailCount = 0;

    // Cooking-only fire tile (niet gebruiken voor WC bonfire)
    private static final WorldPoint COOKING_ONLY_FIRE_TILE = new WorldPoint(3096, 3237, 0);

    private boolean isBurningLogs = false;
    private int firemakingTransitionDelay = 0; // Delay van chopping → firemaking (ms)

    private int bonfireFailCount = 0;
    private static final int MAX_BONFIRE_FAILS = 3;
    private ImpsCashFarmRequest pendingImpsCashFarmRequest = null;
    private long lastImpsCashFarmRequestMs = 0L;
    /** Cooldown zodat we niet elke tick opnieuw een upgrade-trip triggeren. */
    private long lastAxeUpgradeTripCheckMs = 0L;

    // Bonfire stall detectie
    private int lastBonfireLogCount = -1;
    private long lastBonfireLogChangeTime = 0;
    private static final long BONFIRE_STALL_TIMEOUT_MS = 5000; // 5 seconden stall = herstart

    // Bonfire actief bezig: na Production.chooseOption wachten we tot alle logs op zijn
    private boolean bonfireInProgress = false;

    private int cantLightHereCount = 0;
    /** Forestry: spel vraagt om Forester's Campfire te tenden i.p.v. eigen vuur binnen 5 tiles. */
    private boolean foresterCampfireTendPreferred = false;
    private long lastFireAttemptTime = 0;
    private long lastFiremakingTime = 0;
    // (removed postBonfireFirstChopDone — no longer needed)
    private int pendingLogCheckCount = -1;
    private long lastCenterRetargetMs = 0L;

    private final UniversalBankingManager bankingManager = new UniversalBankingManager();

    public WoodcutterHandler(CombatBotConfig config, AntiBan antiBan, CombatBotPaint paint) {
        this.config = config;
        this.antiBan = antiBan;
        this.paint = paint;
    }

    /** Optionele debug helper voor Woodcutting — logt naar de Debug tab. */
    private void debug(String msg) {
        DebugLog.log("Woodcutting", msg);
    }

    /** Reset lichte runtime-state zodat de handler "schoon" opnieuw kan starten. */
    public void resetState() {
        currentState = WcState.IDLE;
        preBankPosition = null;
        idleStartTime = 0;
        lastInteractTime = 0;
        firemakingFailed = false;
        firemakingMoveTile = false;
        ownFireLocation = null;
        walkToTreesFailCount = 0;
        isBurningLogs = false;
        bonfireInProgress = false;
        bonfireFailCount = 0;
        lastBonfireLogCount = -1;
        lastBonfireLogChangeTime = 0;
        cantLightHereCount = 0;
        foresterCampfireTendPreferred = false;
        lastFireAttemptTime = 0;
        lastFiremakingTime = 0;
        lastTravelClickTime = 0;
        pendingImpsCashFarmRequest = null;
        lastImpsCashFarmRequestMs = 0L;
        lastAxeUpgradeTripCheckMs = 0L;
        lastCenterRetargetMs = 0L;
    }

    public ImpsCashFarmRequest pollImpsCashFarmRequest() {
        ImpsCashFarmRequest out = pendingImpsCashFarmRequest;
        pendingImpsCashFarmRequest = null;
        return out;
    }

    public void setTileMarkerManager(TileMarkerManager manager) {
        this.tileMarkerManager = manager;
    }

    public void setActiveCenter(WorldPoint center, int radius) {
        this.treeArea = center;
        this.areaRadius = radius;
        this.preBankPosition = null;
        this.walkToTreesFailCount = 0;
        this.centerWalkTarget = null;
        this.lastTravelClickTime = 0;
    }

    /**
     * OSRS game message: ons eigen vuur is opgebrand. Zo weten we zeker dat we geen
     * andermans vuur meer gebruiken en of we opnieuw moeten aansteken (logs) of naar WC.
     */
    public void onGameMessage(String message) {
        if (message == null || !config.wcFiremaking()) return;
        String m = message.trim();
        String lower = m.toLowerCase(Locale.ROOT).replace('\u2019', '\'');
        boolean forestryNear = lower.contains("forester's campfire")
                || lower.contains("help tend to that one or move further away");
        boolean vanillaCantLight = lower.contains("can't light a fire") || lower.contains("cannot light a fire")
                || lower.contains("you can't light") || lower.contains("unable to light a fire");
        if (forestryNear || vanillaCantLight) {
            ownFireLocation = null;
            bonfireInProgress = false;
            resetBonfireStall();
            firemakingMoveTile = true;
            if (forestryNear) {
                foresterCampfireTendPreferred = true;
            }
            if (vanillaCantLight) {
                cantLightHereCount = 1;
            }
            debug("game message: vuur geweigerd — " + m);
            paint.setLastAntiBanAction(forestryNear
                    ? "Forestry: tend Forester's Campfire (logs op dat vuur)"
                    : "Vuur geweigerd (spel) — andere tegel");
            return;
        }

        if (lower.contains("you finish adding the logs to the fire")) {
            debug("game message: logs klaar in bonfire — " + m);
            bonfireInProgress = false;
            resetBonfireStall();
            if (getBestBurnableLog() == null) {
                isBurningLogs = false;
                ownFireLocation = null;
                bonfireFailCount = 0;
                paint.setLastAntiBanAction("🔥 Bonfire klaar — geen logs meer, terug naar hakken");
            }
            return;
        }

        if (!m.contains("The fire has burned out")) return;

        debug("game message: vuur opgebrand — " + m);
        ownFireLocation = null;
        bonfireInProgress = false;
        resetBonfireStall();

        if (getBestBurnableLog() == null) {
            isBurningLogs = false;
            bonfireFailCount = 0;
            paint.setLastAntiBanAction("🔥 Vuur uit — geen logs → verder met hakken");
        } else {
            paint.setLastAntiBanAction("🔥 Vuur uit — nieuw eigen vuur steken");
        }
    }

    public WcState getCurrentState() {
        return currentState;
    }

    /** Tel alle logs in inventory (alle types). */
    private int countAllLogs() {
        int total = 0;
        for (String[] entry : WoodcutterConfig.TREE_LEVELS) {
            String logName = entry[2];
            if (logName != null) total += Inventory.getCount(logName);
        }
        return total;
    }

    /** Plan een log-count check na een chop interactie. */
    private void scheduleLogCheck(int logsBefore) {
        pendingLogCheckCount = logsBefore;
    }

    /** Check of er daadwerkelijk logs zijn bijgekomen sinds de laatste chop. Roep aan in loop(). */
    private void checkPendingLogCount() {
        if (pendingLogCheckCount >= 0) {
            int logsNow = countAllLogs();
            int gained = logsNow - pendingLogCheckCount;
            if (gained > 0) {
                for (int i = 0; i < gained; i++) {
                    paint.addLogChopped();
                }
                debug("Log count: +" + gained + " (was " + pendingLogCheckCount + ", nu " + logsNow + ")");
            }
            pendingLogCheckCount = -1;
        }
    }

    public int loop() {
        try {
            if (shouldAbortActions()) {
                return 300;
            }
            // 0. Check of er logs zijn bijgekomen sinds laatste chop
            checkPendingLogCount();

            // 1. WC Level Validatie
            if (!validateWcLevel()) {
                paint.setCurrentStatus("⚠ WC Level te laag voor huidig doel!");
                return 5000;
            }

            // 2. Alles-of-Niets check: axe + optional tinderbox
            if (needsGearPrep()) {
                if (Bank.isOpen()) {
                    currentState = WcState.BANKING;
                    paint.setCurrentStatus("🏦 Supplies ophalen");
                    return handleBanking();
                }
                currentState = WcState.WALKING_TO_BANK;
                paint.setCurrentStatus("→ Bank (gear ophalen)");
                return handleWalkToBank();
            }

            // 2b. Slimme axe-upgrade: als level omhoog ging en betere axe volgens account-JSON in bank staat,
            // forceer een korte bank-trip om te upgraden (en oude axe te deponeren via UBM sessie).
            if (shouldDoAxeUpgradeTrip()) {
                if (Bank.isOpen()) {
                    currentState = WcState.BANKING;
                    paint.setCurrentStatus("🏦 Betere axe ophalen");
                    return handleBanking();
                }
                currentState = WcState.WALKING_TO_BANK;
                paint.setCurrentStatus("→ Bank (betere axe ophalen)");
                return handleWalkToBank();
            }

            currentState = determineState();

            switch (currentState) {
                case CHOPPING:
                    paint.setCurrentStatus("🪓 Hout hakken");
                    return handleChopping();
                case DROPPING:
                    paint.setCurrentStatus("🗑 Logs droppen");
                    return handleDropping();
                case FIREMAKING:
                    paint.setCurrentStatus("🔥 Logs verbranden");
                    return handleFiremaking();
                case WALKING_TO_BANK:
                    paint.setCurrentStatus("→ Lopen naar bank");
                    return handleWalkingToBank();
                case BANKING:
                    paint.setCurrentStatus("🏦 Aan het banken");
                    return handleBanking();
                case WALKING_TO_TREES:
                    paint.setCurrentStatus("→ Lopen naar bomen");
                    return handleWalkingToTrees();
                case WALKING_BACK_TO_SPOT:
                    paint.setCurrentStatus("↩ Terug naar kapspot");
                    return handleWalkingBackToSpot();
                case IDLE:
                default:
                    paint.setCurrentStatus("⏳ Wachten op boom...");
                    if (config.wcFiremaking()) {
                        IPlayer pIdle = Players.getLocal();
                        int bonfireBreak = tryForceChopToClearBonfireTail(pIdle);
                        if (bonfireBreak > 0) return bonfireBreak;
                    }
                    return antiBan.varyDelay(randomDelay(1000, 2000));
            }
        } catch (Exception e) {
            paint.setCurrentStatus("⚠ WC fout: " + e.getMessage());
            e.printStackTrace();
            return 2000;
        }
    }

    private WcState determineState() {
        // Als inventory items bevat die niet bij WC horen (bv. beads), bank ze eerst.
        if (hasForeignItemsForWc()) {
            saveBankPosition();
            return Bank.isOpen() ? WcState.BANKING : WcState.WALKING_TO_BANK;
        }

        // Als bank open is maar we geen banking nodig hebben → sluiten
        if (Bank.isOpen() && !needsGearPrep() && !Inventory.isFull()) {
            Bank.close();
            bankingManager.waitForBankClose();
            return WcState.IDLE;
        }

        // Als we bezig zijn met verbranden, BLIJF verbranden totdat alle logs op zijn
        if (isBurningLogs) {
            String bestLog = getBestBurnableLog();
            if (bestLog != null && hasLogsInInventory(bestLog)) {
                // Extra check: FM level hoog genoeg?
                if (Skills.getLevel(Skill.FIREMAKING) >= getRequiredFmLevelForLog(bestLog)) {
                    lastFiremakingTime = System.currentTimeMillis();
                    return WcState.FIREMAKING;
                } else {
                    // Level te laag, stop burning
                    isBurningLogs = false;
                }
            } else {
                isBurningLogs = false;
                bonfireFailCount = 0;
                ownFireLocation = null;
            }
        }

        if (Inventory.isFull()) {
            if (config.wcFiremaking() && !firemakingFailed && getBestBurnableLog() != null) {
                isBurningLogs = true;
                // Delay van chopping → firemaking wordt afgehandeld door firemakingTransitionDelay
                firemakingTransitionDelay = randomDelay(3, 8) * 600; // 3-8 game ticks
                return WcState.FIREMAKING;
            }
            if (config.wcDropLogs()) return WcState.DROPPING;
            saveBankPosition();
            return WcState.WALKING_TO_BANK;
        }

        if (preBankPosition != null && !isAtLocation(preBankPosition)) {
            // Voorkom vastlopen: als preBankPosition bij een bank is, reset en loop naar center
            if (isNearBank(preBankPosition)) {
                preBankPosition = null;
                return WcState.WALKING_TO_TREES;
            }
            return WcState.WALKING_BACK_TO_SPOT;
        }

        // --- STRIKTE AREA CHECK ---
        // Bot MOET eerst in het center zijn voordat hij bomen scant
        int areaThreshold = treeArea != null ? areaRadius : LOCATION_THRESHOLD;
        if (treeArea != null && !isAtLocationWithRange(treeArea, areaThreshold)) {
            idleStartTime = 0;
            if (isNearBank(Players.getLocal().getWorldLocation())) {
                preBankPosition = null;
            }
            return WcState.WALKING_TO_TREES;
        }
        if (treeArea != null && isAtLocationWithRange(treeArea, areaRadius)) {
            centerWalkTarget = null; // aangekomen, volgende trip nieuw punt in radius
        }

        // --- BOOM ZOEKEN (pas als we in de area zijn) ---
        String treeKeyword = getTargetTreeName();
        ITileObject tree = TileObjects.getNearest(obj ->
                obj.getName() != null
                        && matchesTreeKeyword(obj.getName(), treeKeyword)
                        && !obj.getName().toLowerCase().contains("dead")
                        && obj.hasAction("Chop down")
                        && !isFakeTree(obj.getWorldLocation())
                        && isWithinArea(obj.getWorldLocation())
                        && !isTileExcluded(obj.getWorldLocation())
        );

        // Geen geschikte boom in dit center? Probeer een ander actief WC-center met deze boom.
        if (tree == null && tryRetargetToCenterWithTree(treeKeyword)) {
            idleStartTime = 0;
            return WcState.WALKING_TO_TREES;
        }

        if (tree != null) {
            idleStartTime = 0;
            return WcState.CHOPPING;
        }

        if (idleStartTime == 0) idleStartTime = System.currentTimeMillis();

        if (treeArea != null && System.currentTimeMillis() - idleStartTime > IDLE_TIMEOUT_MS) {
            idleStartTime = 0;
            preBankPosition = null;
            return WcState.WALKING_TO_TREES;
        }

        return WcState.IDLE;
    }

    private boolean hasForeignItemsForWc() {
        return Inventory.getFirst(item -> {
            if (item == null || item.getName() == null) return false;
            String n = item.getName().toLowerCase();
            // Toegestaan: axes/tools + logs + firemaking consumables.
            boolean allowed = n.contains("axe")
                    || n.contains("logs")
                    || n.equals("tinderbox")
                    || n.equals("knife")
                    || (item.hasAction("Eat") || item.hasAction("Drink"))
                    || n.contains("coins");
            return !allowed;
        }) != null;
    }

    // ===================== FAKE TREE BLACKLIST =====================

    /**
     * Blacklist voor specifieke nep-bomen/decoratie op vaste coördinaten.
     * Voorkomt dat de bot probeert een quest-boom of scenery object te kappen.
     */
    private boolean isFakeTree(WorldPoint point) {
        if (point == null) return false;
        int x = point.getX();
        int y = point.getY();
        // Draynor "guard/illusion" bomen
        return (x == 3086 && y == 3244)
                || (x == 3086 && y == 3243)
                || (x == 3085 && y == 3243)
                || (x == 3085 && y == 3244);
    }

    /** Checkt of een punt dicht bij een bank is (dynamisch). */
    private boolean isNearBank(WorldPoint point) {
        if (point == null) return false;
        return BankHelper.isNearAnyBank(point);
    }

    // ===================== LEVEL VALIDATIE =====================

    /** Check of het huidige WC level hoog genoeg is voor het doel. */
    private boolean validateWcLevel() {
        int wcLevel = Skills.getLevel(Skill.WOODCUTTING);
        String targetTree = config.wcUseSpecificTree() ? config.wcTreeName() : WoodcutterConfig.getBestTreeForLevel(wcLevel);
        int requiredLevel = getRequiredWcLevel(targetTree);
        return wcLevel >= requiredLevel;
    }

    // ===================== GECOMBINEERDE GEAR PREP =====================

    private boolean needsGearPrep() {
        boolean needsAxe = !hasAxe();
        boolean needsTinderbox = config.wcFiremaking() && !firemakingFailed && !Inventory.contains("Tinderbox");
        return needsAxe || needsTinderbox;
    }

    /**
     * Zoek de beste beschikbare axe (inventory + equipment + bank).
     * Bank moet open zijn voor bank check.
     */
    private String findBestAvailableAxe() {
        int wcLevel = Skills.getLevel(Skill.WOODCUTTING);
        for (String axe : reverseAxes()) {
            int reqLevel = getRequiredLevelForAxe(axe);
            if (wcLevel >= reqLevel) {
                if (Inventory.contains(axe) || Equipment.contains(axe)) return axe;
                if (Bank.isOpen() && Bank.contains(axe)) return axe;
            }
        }
        // Fallback: huidige axe
        String current = getCurrentAxe();
        return current != null ? current : "Bronze axe";
    }

    /** Hoogste axe die puur op basis van huidig WC-level gebruikt mag worden. */
    private String getBestTrainableAxeByLevel() {
        int wcLevel = Skills.getLevel(Skill.WOODCUTTING);
        for (String axe : reverseAxes()) {
            if (wcLevel >= getRequiredLevelForAxe(axe)) {
                return axe;
            }
        }
        return "Bronze axe";
    }

    /**
     * Trigger bank-trip voor axe-upgrade op basis van account JSON bank-snapshot.
     * Zo hoeven we niet te wachten tot een willekeurig bankmoment.
     */
    private boolean shouldDoAxeUpgradeTrip() {
        long now = System.currentTimeMillis();
        if (now - lastAxeUpgradeTripCheckMs < 2500L) {
            return false;
        }
        lastAxeUpgradeTripCheckMs = now;

        String currentAxe = getCurrentAxe();
        String bestByLevel = getBestTrainableAxeByLevel();
        int currentIdx = getAxeIndex(currentAxe);
        int bestIdx = getAxeIndex(bestByLevel);
        if (bestIdx < 0 || currentIdx >= bestIdx) {
            return false;
        }

        IPlayer local = Players.getLocal();
        if (local == null || local.getName() == null || local.getName().trim().isEmpty()) {
            return false;
        }
        AccountStateJsonStore.AccountEntry e = AccountStateJsonStore.getEntry(local.getName().trim());
        if (e == null) {
            return false;
        }

        // Snapshot-check: kijk of de gewenste level-axe in bekende bank-items staat.
        String knownCsv = e.knownBankItemsCsv != null ? e.knownBankItemsCsv : "";
        boolean hasDesiredInBankSnapshot = knownCsv.toLowerCase(Locale.ROOT)
                .contains(bestByLevel.toLowerCase(Locale.ROOT));
        if (!hasDesiredInBankSnapshot) {
            return false;
        }

        paint.setLastAntiBanAction("WC upgrade: " + (currentAxe != null ? currentAxe : "geen axe")
                + " → " + bestByLevel + " (bank)");
        debug("Axe upgrade trip: current=" + currentAxe + " bestByLevel=" + bestByLevel
                + " snapshotHasDesired=true");
        return true;
    }

    private int estimateAxeGePrice(String axeName) {
        if (axeName == null) return 2000;
        String a = axeName.toLowerCase(Locale.ROOT);
        if (a.contains("bronze")) return 100;
        if (a.contains("iron")) return 200;
        if (a.contains("steel")) return 800;
        if (a.contains("black")) return 1400;      // ~ +30% marge
        if (a.contains("mithril")) return 2700;    // ~ +35% marge
        if (a.contains("adamant")) return 7000;    // ~ +40% marge
        if (a.contains("rune")) return 20000;
        if (a.contains("dragon")) return 90000;
        return 3000;
    }

    private int getCoinsInvPlusBankIfOpen() {
        int coins = 0;
        try {
            coins += Inventory.getCount(true, "Coins");
        } catch (Exception ignored) {
        }
        try {
            if (Bank.isOpen() && Bank.contains("Coins")) {
                var bc = Bank.getFirst("Coins");
                if (bc != null) {
                    coins += bc.getQuantity();
                }
            }
        } catch (Exception ignored) {
        }
        return Math.max(0, coins);
    }

    private int estimateImpsTripsForShortfall(int shortfallGp) {
        int lootItemsPerTrip = Math.max(1, config.impsBankThreshold());
        int sellPrice = Math.max(1, config.impsGeSellPrice());
        int configExpectedTripGp = Math.max(200, (int) (lootItemsPerTrip * sellPrice * 0.75));
        int accountExpectedTripGp = 0;
        try {
            IPlayer local = Players.getLocal();
            String rsn = local != null ? local.getName() : null;
            if (rsn != null && !rsn.trim().isEmpty()) {
                accountExpectedTripGp = AccountStateJsonStore.getImpsTripAvgGp(rsn);
            }
        } catch (Exception ignored) {
        }
        int expectedTripGp = accountExpectedTripGp > 0
                ? Math.max(200, (int) Math.round(accountExpectedTripGp * 0.60 + configExpectedTripGp * 0.40))
                : configExpectedTripGp;
        int trips = (int) Math.ceil(shortfallGp / (double) expectedTripGp);
        return Math.max(1, Math.min(Math.max(1, config.wcAxeImpsTripCap()), trips));
    }

    /** Build UBM requirements: beste axe + optional tinderbox. */
    private java.util.List<UniversalBankingManager.Requirement> buildWcRequirements() {
        java.util.List<UniversalBankingManager.Requirement> reqs = new java.util.ArrayList<>();
        String bestAxe = findBestAvailableAxe();
        reqs.add(new UniversalBankingManager.Requirement(bestAxe, 1, 1));
        if (config.wcFiremaking() && !firemakingFailed) {
            reqs.add(new UniversalBankingManager.Requirement("Tinderbox", 1, 1));
        }
        return reqs;
    }

    /** Helper: loop naar bank en open. Wordt gebruikt door GEAR_PREP en WALKING_TO_BANK. */
    private int handleWalkToBank() {
        if (Bank.isOpen()) return 600;
        // Probeer dichtbij bank te openen, anders walk
        if (BankHelper.interactIfNearby()) {
            return antiBan.varyDelay(randomDelay(1500, 2500));
        }
        if (!BankHelper.walkToNearestFullBank()) {
            paint.setLastAntiBanAction("⚠ Geen bank gevonden!");
            return 5000;
        }
        return antiBan.varyDelay(randomDelay(2000, 3000));
    }

    // ===================== FIREMAKING (bonfire alleen op eigen vuur) =====================

    private String getBestBurnableLog() {
        int fmLevel = Skills.getLevel(Skill.FIREMAKING);
        for (int i = WoodcutterConfig.TREE_LEVELS.length - 1; i >= 0; i--) {
            String[] entry = WoodcutterConfig.TREE_LEVELS[i];
            String logName = entry[2];
            int requiredLevel = Integer.parseInt(entry[1]);
            if (fmLevel >= requiredLevel && Inventory.contains(logName)) {
                return logName;
            }
        }
        return null;
    }

    private boolean hasLogsInInventory(String logName) {
        return Inventory.contains(logName);
    }

    /** Checkt of er nog ENIGE brandbare logs in de inventory zitten (ongeacht type). */
    private boolean hasAnyBurnableLogs() {
        for (String[] entry : WoodcutterConfig.TREE_LEVELS) {
            String name = entry[2];
            if (name != null && Inventory.contains(name)) {
                return true;
            }
        }
        return false;
    }

    private int handleFiremaking() {
        if (shouldAbortActions()) return 300;
        IPlayer local = Players.getLocal();
        if (local == null) return 1000;

        // Alleen logs die bij huidige FM-brandbaar zijn (anders blijft bonfireInProgress/anim-wacht hangen).
        if (getBestBurnableLog() == null) {
            firemakingTransitionDelay = 0;
            return finishBonfire("Geen brandbare logs (FM) — terug naar WC");
        }

        // Transitie-delay van chopping → firemaking (3-8 game ticks)
        if (firemakingTransitionDelay > 0) {
            int delay = firemakingTransitionDelay;
            firemakingTransitionDelay = 0;
            int ticks = delay / 600;
            paint.setLastAntiBanAction("🪓→🔥 Wacht " + ticks + " ticks voor bonfire");
            debug("chopping→firemaking transitie delay: " + ticks + " ticks (" + delay + "ms)");
            return delay;
        }

        String logName = getBestBurnableLog();
        if (logName == null) {
            return finishBonfire("Geen geschikte logs — terug naar WC");
        }

        // Game-chat "can't light": meteen andere tegel — vóór animatie-/bewegings-wacht. Anders blijft
        // cantLightHereCount hangen terwijl de client kort in vuur-pose staat → opnieuw useOn op dezelfde tile.
        if (cantLightHereCount > 0) {
            cantLightHereCount = 0;
            firemakingMoveTile = false;
            moveRandomly(local);
            paint.setLastAntiBanAction("Andere tegel voor vuur (spel blokkeerde)");
            return antiBan.varyDelay(randomDelay(1200, 2000));
        }

        int currentLogCount = Inventory.getCount(logName);

        // === STAP 2: Laatste log net verbrand (count = 0)? ===
        if (currentLogCount == 0) {
            String nextLog = getBestBurnableLog();
            if (nextLog != null && Inventory.contains(nextLog)) {
                bonfireInProgress = false;
                resetBonfireStall();
                paint.setLastAntiBanAction("🔥 " + logName + " op → door met " + nextLog);
                return antiBan.varyDelay(randomDelay(600, 1000));
            }
            return finishBonfire("Geen " + logName + " meer");
        }

        // === ANTI-SPAM FIX: Als we al bezig zijn met vuur-animatie of lopen, STOP MET KLIKKEN! ===
        // Dit staat BOVENAAN zodat we NOOIT halverwege opnieuw logs op het vuur proberen te gebruiken.
        int currentAnim = local.getAnimation();
        boolean isDoingFiremaking = (currentAnim == ANIM_FIREMAKING || isBonfireAnimation(currentAnim));

        if (local.isMoving() || isDoingFiremaking) {
            // Inventory kan al leeg zijn terwijl pose nog “vuur” is — niet eindeloos wachten.
            if (getBestBurnableLog() == null) {
                firemakingTransitionDelay = 0;
                return finishBonfire("Geen brandbare logs (tijdens pose/movement)");
            }
            // Alleen wachten op pose als we echt nog in firemaking-context zitten.
            // Na "The fire has burned out" kan de pose nog kort blijven hangen terwijl er geen vuur meer is.
            boolean hasNearbyFireContext = Production.isOpen()
                    || bonfireInProgress
                    || ownFireLocation != null
                    || TileObjects.getNearest(obj ->
                    obj != null
                            && obj.getName() != null
                            && isFireObject(obj.getName())
                            && obj.getWorldLocation() != null
                            && obj.getWorldLocation().distanceTo(local.getWorldLocation()) <= 2) != null;
            if (local.isMoving() || hasNearbyFireContext) {
                return antiBan.varyDelay(randomDelay(600, 1200));
            }
            debug("skip fire-pose wait: geen actief vuur-context (anim=" + currentAnim + ")");
        }

        // === STAP 3: Production menu is open → kies optie en markeer bonfire als actief ===
        if (Production.isOpen()) {
            Production.chooseOption(logName);
            paint.setLastAntiBanAction("🔥 Gekozen: " + logName);
            bonfireFailCount = 0;
            bonfireInProgress = true;
            lastBonfireLogCount = currentLogCount;
            lastBonfireLogChangeTime = System.currentTimeMillis();
            return antiBan.varyDelay(randomDelay(1200, 2000));
        }

        if (bonfireInProgress) {
            if (getBestBurnableLog() == null) {
                return finishBonfire("Geen brandbare logs meer (bonfire)");
            }
            // Track of logs afnemen
            if (lastBonfireLogCount == -1) {
                lastBonfireLogCount = currentLogCount;
                lastBonfireLogChangeTime = System.currentTimeMillis();
            }

            if (currentLogCount < lastBonfireLogCount) {
                // Logs worden verbrand — bonfire werkt! Reset timer + update lastFiremakingTime.
                lastBonfireLogCount = currentLogCount;
                lastBonfireLogChangeTime = System.currentTimeMillis();
                lastFiremakingTime = System.currentTimeMillis();
                bonfireFailCount = 0;
                debug("bonfire: log verbrand, nog " + currentLogCount + " over");
                return antiBan.varyDelay(randomDelay(600, 1200));
            }

            // Logs nog hetzelfde? Check op bonfire animatie (alle log types) → gewoon wachten
            int bonfireAnim = local.getAnimation();
            if (isBonfireAnimation(bonfireAnim) || bonfireAnim == ANIM_FIREMAKING) {
                if (getBestBurnableLog() == null) {
                    return finishBonfire("Geen brandbare logs (tijdens vuur-animatie)");
                }
                return antiBan.varyDelay(randomDelay(600, 1200));
            }

            // Niet animating, logs onveranderd — maar er ZIJN nog logs, dus wacht geduldig
            long stalledMs = System.currentTimeMillis() - lastBonfireLogChangeTime;
            if (stalledMs < BONFIRE_STALL_TIMEOUT_MS) {
                // Nog niet lang genoeg gestalled — gewoon wachten, NIET opnieuw interacten
                return antiBan.varyDelay(randomDelay(800, 1400));
            }

            // BONFIRE_STALL_TIMEOUT_MS zonder log-verandering EN niet animating → bonfire is echt gestopt
            // Alleen NU pas resetten en opnieuw proberen
            debug("bonfire: stall na " + (stalledMs / 1000) + "s, herstart");
            paint.setLastAntiBanAction("⚠ Bonfire gestopt (" + (stalledMs / 1000) + "s) — herstart");
            bonfireInProgress = false;
            resetBonfireStall();
            ownFireLocation = null;
            // Val door naar STAP 5 om opnieuw vuur te zoeken
        }

        // === STAP 5: Bonfire nog niet gestart — zoek vuur en gebruik log erop ===
        IInventoryItem logItem = Inventory.getFirst(logName);
        if (logItem == null) {
            return finishBonfire("Log item niet gevonden");
        }

        // Forestry: zelfde mechaniek als bonfire — log op Forester's Campfire → production-UI, volle FM-xp.
        if (foresterCampfireTendPreferred) {
            ITileObject foresterFire = TileObjects.getNearest(obj ->
                    obj != null
                            && obj.getName() != null
                            && isForesterCampfireObject(obj.getName())
                            && !COOKING_ONLY_FIRE_TILE.equals(obj.getWorldLocation())
            );
            if (foresterFire != null) {
                WorldPoint fireWp = foresterFire.getWorldLocation();
                int distToFire = fireWp.distanceTo(local.getWorldLocation());
                if (distToFire > 2) {
                    MovementHelper.walkTo(fireWp);
                    paint.setLastAntiBanAction("Forestry: lopen naar Forester's Campfire");
                    return antiBan.varyDelay(randomDelay(800, 1400));
                }
                logItem.useOn(foresterFire);
                paint.setLastAntiBanAction("Forestry: tend — " + logName + " op Forester's Campfire");
                bonfireFailCount = 0;
                firemakingMoveTile = false;
                return antiBan.varyDelay(randomDelay(1800, 2500));
            }
            debug("forester tend: geen Forester's Campfire in bereik — andere tegel");
            foresterCampfireTendPreferred = false;
            cantLightHereCount = 1;
        }

        // Alleen logs op ons eigen vuur — nooit andermans vuren in de buurt gebruiken.
        if (ownFireLocation != null) {
            final WorldPoint anchor = ownFireLocation;
            ITileObject ourFire = TileObjects.getNearest(obj ->
                    obj != null
                            && obj.getName() != null
                            && isFireObject(obj.getName())
                            && !COOKING_ONLY_FIRE_TILE.equals(obj.getWorldLocation())
                            && obj.getWorldLocation().distanceTo(anchor) <= 1
            );
            if (ourFire != null) {
                WorldPoint fireWp = ourFire.getWorldLocation();
                int distToFire = fireWp.distanceTo(local.getWorldLocation());
                if (distToFire > 1) {
                    MovementHelper.walkTo(fireWp);
                    paint.setLastAntiBanAction("🔥 Lopen naar eigen vuur (" + distToFire + " tiles)");
                    return antiBan.varyDelay(randomDelay(800, 1400));
                }
                logItem.useOn(ourFire);
                paint.setLastAntiBanAction("🔥 Bonfire: " + logName + " op eigen vuur");
                ownFireLocation = fireWp;
                bonfireFailCount = 0;
                firemakingMoveTile = false;
                return antiBan.varyDelay(randomDelay(1800, 2500));
            }
            // Geen object meer op bekende tile (afgebrand zonder bericht of gedespawned)
            debug("bonfire: ownFireLocation gezet maar geen vuur-object → opnieuw steken");
            ownFireLocation = null;
        }

        bonfireFailCount++;

        // Geen eigen vuur (meer) → nieuw vuur met tinderbox
        if (bonfireFailCount >= MAX_BONFIRE_FAILS || ownFireLocation == null) {
            if (Inventory.contains("Tinderbox")) {
                if (COOKING_ONLY_FIRE_TILE.equals(local.getWorldLocation())) {
                    moveRandomly(local);
                    paint.setLastAntiBanAction("🔥 Cooking-tile vermeden voor bonfire");
                    return antiBan.varyDelay(randomDelay(1000, 1600));
                }

                if (firemakingMoveTile) {
                    firemakingMoveTile = false;
                    moveRandomly(local);
                    paint.setLastAntiBanAction("🔥 Verplaatsen voor nieuw vuur");
                    return antiBan.varyDelay(randomDelay(1200, 2000));
                }

                IInventoryItem tinderbox = Inventory.getFirst("Tinderbox");
                if (tinderbox != null) {
                    ownFireLocation = local.getWorldLocation();
                    lastFireAttemptTime = System.currentTimeMillis();
                    tinderbox.useOn(logItem);
                    paint.setLastAntiBanAction("🔥 Nieuw vuur aansteken met " + logName);
                    bonfireFailCount = 0;
                    firemakingMoveTile = true;
                    return antiBan.varyDelay(randomDelay(3500, 5000));
                }
            }

            firemakingFailed = true;
            isBurningLogs = false;
            bonfireInProgress = false;
            paint.setLastAntiBanAction("⚠ Geen tinderbox — firemaking uit");
            return 600;
        }

        paint.setLastAntiBanAction("🔥 Wacht op vuur... (" + bonfireFailCount + "/" + MAX_BONFIRE_FAILS + ")");
        return antiBan.varyDelay(randomDelay(1000, 1800));
    }

    /**
     * Stop bonfire en wacht 2-6 game ticks voordat we weer gaan hakken.
     * Animatie-check wordt uitgeschakeld tot de eerste boom-klik.
     */
    private int finishBonfire(String reason) {
        isBurningLogs = false;
        bonfireInProgress = false;
        ownFireLocation = null;
        foresterCampfireTendPreferred = false;
        bonfireFailCount = 0;
        resetBonfireStall();
        lastFiremakingTime = System.currentTimeMillis();
        
        int tickDelay = randomDelay(3, 8); // 3-8 game ticks
        int msDelay = tickDelay * 600;     // 1 game tick = 600ms
        paint.setLastAntiBanAction("🔥 " + reason + " — " + tickDelay + " tick pauze");
        debug("finishBonfire: " + reason + " | wacht " + tickDelay + " ticks (" + msDelay + "ms)");
        return msDelay;
    }

    /**
     * Kies een nieuwe tile in de buurt voor bonfire/WC:
     * - Vermijd tiles direct naast huizen/muren/deuren (±2 tiles rond obstakels).
     * - Maximaal een paar pogingen, daarna fallback naar de eerste keuze.
     */
    private void moveRandomly(IPlayer local) {
        if (shouldAbortActions()) return;
        WorldPoint current = local.getWorldLocation();
        if (current == null) return;

        WorldPoint fallback = null;

        for (int attempt = 0; attempt < 5 && !shouldAbortActions(); attempt++) {
            int dx = random.nextInt(5) - 2;
            int dy = random.nextInt(5) - 2;
            if (dx == 0 && dy == 0) dx = 2;
            WorldPoint candidate = new WorldPoint(current.getX() + dx, current.getY() + dy, current.getPlane());
            if (fallback == null) fallback = candidate;

            // Check op “in de buurt van huizen”: zoek objecten met typische namen in een kleine radius
            ITileObject nearObstacle = TileObjects.getNearest(obj ->
                    obj != null && obj.getName() != null
                            && obj.getWorldLocation() != null
                            && obj.getWorldLocation().distanceTo(candidate) <= 2
                            && isObstacleForBonfire(obj.getName().toLowerCase())
            );
            if (nearObstacle != null) {
                // Deze tile ligt te dicht bij een muur/huis/deur → andere proberen
                continue;
            }

            MovementHelper.walkTo(candidate);
            return;
        }

        // Geen “schone” tile gevonden, gebruik fallback
        if (fallback != null) {
            MovementHelper.walkTo(fallback);
        }
    }

    /** Objecten die we willen vermijden voor bonfire (huizen, muren, deuren, hekken). */
    private boolean isObstacleForBonfire(String nameLower) {
        return nameLower.contains("wall")
                || nameLower.contains("door")
                || nameLower.contains("house")
                || nameLower.contains("building")
                || nameLower.contains("fence")
                || nameLower.contains("gate");
    }

    private int getRequiredFmLevelForLog(String logName) {
        if (logName == null) return 1;
        for (String[] entry : WoodcutterConfig.TREE_LEVELS) {
            if (entry[2].equalsIgnoreCase(logName)) {
                return Integer.parseInt(entry[1]);
            }
        }
        return 1;
    }

    private void resetBonfireStall() {
        lastBonfireLogCount = -1;
        lastBonfireLogChangeTime = 0;
    }

    private boolean isFireObject(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase();
        return lower.equals("fire") || lower.contains("campfire") || lower.contains("camp fire");
    }

    /** Forestry-werelobject (niet elke gewone "Campfire"). */
    private boolean isForesterCampfireObject(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.contains("forester") && (lower.contains("campfire") || lower.contains("camp fire"))) {
            return true;
        }
        return lower.contains("forester's campfire");
    }

    private int dropAllLogs(String logName) {
        // Drop ALLE log types, niet alleen het opgegeven type
        for (String[] entry : WoodcutterConfig.TREE_LEVELS) {
            String entryLog = entry[2];
            if (entryLog == null) continue;
            var logs = Inventory.getAll(entryLog);
            if (logs != null && !logs.isEmpty()) {
                for (var log : logs) {
                    log.interact("Drop");
                    sleep(150, 350);
                }
            }
        }
        return antiBan.varyDelay(randomDelay(600, 1000));
    }

    // ===================== BANK OPENEN (via BankHelper) =====================

    private boolean tryOpenBank() {
        return BankHelper.tryOpenFullBank();
    }

    // ===================== CHOPPING =====================

    /**
     * Geen brandbare logs meer maar player zit vast in vuur-pose of SDK meldt {@link IPlayer#isAnimating()} zonder {@code getAnimation()}-ID:
     * kap een boom om de client te laten doorlopen. Niet aanroepen bij woodcutting-anim (andere bijl-IDs).
     *
     * @return ms om te wachten (&gt;0), of 0 als we niets deden
     */
    private int tryForceChopToClearBonfireTail(IPlayer local) {
        if (local == null) return 0;
        if (hasAnyBurnableLogs()) return 0;
        if (!local.isAnimating() || local.isMoving()) return 0;

        int anim = local.getAnimation();
        if (isWoodcuttingPlayerAnimation(anim)) return 0;

        boolean bonfireLikePose = (anim == ANIM_FIREMAKING || isBonfireAnimation(anim) || anim < 0);
        if (!bonfireLikePose) return 0;

        String treeKeyword = getTargetTreeName();
        ITileObject targetTree = null;
        int minDist = Integer.MAX_VALUE;

        for (ITileObject obj : TileObjects.getAll(o -> o.getName() != null)) {
            if (obj.getName() != null
                    && matchesTreeKeyword(obj.getName(), treeKeyword)
                    && !obj.getName().toLowerCase().contains("dead")
                    && obj.hasAction("Chop down")
                    && !isFakeTree(obj.getWorldLocation())
                    && isWithinArea(obj.getWorldLocation())
                    && !isTileExcluded(obj.getWorldLocation())) {

                int dist = obj.getWorldLocation().distanceTo(local.getWorldLocation());
                if (dist < minDist) {
                    minDist = dist;
                    targetTree = obj;
                }
            }
        }

        if (targetTree != null) {
            targetTree.interact("Chop down");
            lastInteractTime = System.currentTimeMillis();
            debug("FORCEER chop: bonfire-tail (anim=" + anim + " isAnimating=" + local.isAnimating() + ")");
            return randomDelay(3, 8) * 600;
        }
        return antiBan.varyDelay(randomDelay(600, 1000));
    }

    private int handleChopping() {
        if (shouldAbortActions()) return 300;
        IPlayer local = Players.getLocal();
        if (local == null) return 1000;

        int forced = tryForceChopToClearBonfireTail(local);
        if (forced > 0) return forced;

        // 2. NORMALE BLOKKADES
        if (local.isAnimating()) {
            int anim = local.getAnimation();
            // Na firemaking kan de client soms in een "tail"-animatie blijven hangen die géén echte WC-animatie is.
            // Als er ook geen brandbare logs meer zijn, wachten we die pose niet uit en forceren we direct de volgende chop.
            if (hasAnyBurnableLogs() || isWoodcuttingPlayerAnimation(anim)) {
                return antiBan.varyDelay(randomDelay(600, 1200));
            }
            debug("skip anim-wacht: non-WC tail anim=" + anim + " zonder brandbare logs");
        }

        if (local.isMoving()) {
            return antiBan.varyDelay(randomDelay(400, 800));
        }

        if (System.currentTimeMillis() - lastInteractTime < INTERACT_COOLDOWN_MS) {
            return antiBan.varyDelay(randomDelay(400, 800));
        }

        // Configureerbare extra delay tussen interacties
        int delayMin = config.wcInteractDelayMin();
        int delayMax = config.wcInteractDelayMax();
        if (delayMin > 0 && delayMax > 0 && delayMax >= delayMin) {
            long timeSinceInteract = System.currentTimeMillis() - lastInteractTime;
            int requiredDelay = randomDelay(delayMin, delayMax);
            if (timeSinceInteract < requiredDelay) {
                return antiBan.varyDelay(randomDelay(400, 800));
            }
        }

        // 3. NORMALE KLIK (Voor normale woodcutting loops)
        String treeKeyword = getTargetTreeName();
        ITileObject tree = null;
        int minDistance = Integer.MAX_VALUE;

        for (ITileObject obj : TileObjects.getAll(o -> o.getName() != null)) {
            if (obj.getName() != null
                    && matchesTreeKeyword(obj.getName(), treeKeyword)
                    && !obj.getName().toLowerCase().contains("dead")
                    && obj.hasAction("Chop down")
                    && !isFakeTree(obj.getWorldLocation())
                    && isWithinArea(obj.getWorldLocation())
                    && !isTileExcluded(obj.getWorldLocation())) {

                int dist = obj.getWorldLocation().distanceTo(local.getWorldLocation());
                if (dist < minDistance) {
                    minDistance = dist;
                    tree = obj;
                }
            }
        }

        if (tree != null) {
            int logsBefore = countAllLogs();
            tree.interact("Chop down");
            lastInteractTime = System.currentTimeMillis();
            scheduleLogCheck(logsBefore);
            return antiBan.varyDelay(randomDelay(1200, 2000));
        }

        return antiBan.varyDelay(randomDelay(1000, 2000));
    }

    // ===================== DROPPING =====================

    private int handleDropping() {
        if (shouldAbortActions()) return 300;
        String logName = WoodcutterConfig.getLogName(getTargetTreeName());
        var logs = Inventory.getAll(logName);
        if (logs != null && !logs.isEmpty()) {
            for (var log : logs) {
                if (shouldAbortActions()) break;
                log.interact("Drop");
                sleep(100, 300);
            }
            paint.addLogDropped();
            return antiBan.varyDelay(randomDelay(600, 1000));
        }
        return 600;
    }

    // ===================== WALKING & BANKING =====================

    private int handleWalkingToBank() {
        if (shouldAbortActions()) return 300;
        if (Bank.isOpen()) return 600;
        if (BankHelper.interactIfNearby()) {
            return antiBan.varyDelay(randomDelay(1200, 1800));
        }
        if (!BankHelper.walkToNearestFullBank()) {
            paint.setLastAntiBanAction("⚠ Geen bank gevonden!");
            return 5000;
        }
        return antiBan.varyDelay(randomDelay(2000, 3000));
    }

    /**
     * Unified banking via UBM: deposit logs, keep/withdraw axe + tinderbox.
     * Axe upgrade is impliciet: buildWcRequirements kiest de beste beschikbare axe.
     */
    private int handleBanking() {
        if (shouldAbortActions()) return 300;
        if (!Bank.isOpen()) {
            tryOpenBank();
            return antiBan.varyDelay(randomDelay(1500, 2000));
        }

        // Tel logs vóór deposit (voor stats)
        String logName = WoodcutterConfig.getLogName(getTargetTreeName());
        int logCount = Inventory.getCount(true, logName);

        // UBM: deposit alles behalve axe+tinderbox, withdraw tekorten
        java.util.List<UniversalBankingManager.Requirement> reqs = buildWcRequirements();
        UniversalBankingManager.BankSessionStatus status = bankingManager.runBankSession(reqs);
        if (shouldAbortActions()) return 300;

        debug("handleBanking: UBM result=" + status.getResult());

        if (!status.isOk()) {
            debug("handleBanking: " + status.getRestockItemName() + " niet beschikbaar");
            paint.setLastAntiBanAction("⚠ " + status.getRestockItemName() + " niet in bank");
            String missing = status.getRestockItemName();
            if (config.wcAxeCashViaImpsEnabled()
                    && missing != null
                    && missing.toLowerCase(Locale.ROOT).contains("axe")) {
                long now = System.currentTimeMillis();
                if (now - lastImpsCashFarmRequestMs > 8000L) {
                    int estimatedCost = estimateAxeGePrice(missing);
                    int coins = getCoinsInvPlusBankIfOpen();
                    if (coins < estimatedCost) {
                        int shortfall = Math.max(0, estimatedCost - coins);
                        int trips = estimateImpsTripsForShortfall(shortfall);
                        pendingImpsCashFarmRequest = new ImpsCashFarmRequest(
                                missing, estimatedCost, coins, shortfall, trips
                        );
                        lastImpsCashFarmRequestMs = now;
                        debug("WC->Imps cash request: axe=" + missing + " cost~" + estimatedCost
                                + " coins=" + coins + " shortfall=" + shortfall + " trips=" + trips);
                        paint.setLastAntiBanAction("WC: te weinig gp voor " + missing + " → Imps x" + trips);
                    }
                }
            }
        }

        // Kleine pauze voordat we bank sluiten (menselijk gedrag)
        sleep(300, 600);
        Bank.close();
        bankingManager.waitForBankClose();

        paint.addLogBanked(logCount);
        paint.setLastAntiBanAction(status.isOk() ? "✓ Banking compleet" : "⚠ Banking onvolledig");
        return antiBan.varyDelay(randomDelay(600, 1000));
    }

    /** Vereist WC level per axe type. */
    private int getRequiredLevelForAxe(String axeName) {
        String lower = axeName.toLowerCase();
        if (lower.contains("bronze")) return 1;
        if (lower.contains("iron")) return 1;
        if (lower.contains("steel")) return 6;
        if (lower.contains("black")) return 11;
        if (lower.contains("mithril")) return 21;
        if (lower.contains("adamant")) return 31;
        if (lower.contains("rune")) return 41;
        if (lower.contains("dragon")) return 61;
        if (lower.contains("infernal")) return 61;
        if (lower.contains("crystal")) return 71;
        if (lower.contains("3rd age")) return 61;
        return 1;
    }

    private int handleWalkingToTrees() {
        if (shouldAbortActions()) return 300;
        if (treeArea == null) return 1000;

        IPlayer local = Players.getLocal();
        if (local == null) return 1000;

        long travelNow = System.currentTimeMillis();
        if (!TravelWalkHelper.reclickReady(lastTravelClickTime, config)) {
            return antiBan.varyDelay(TravelWalkHelper.shortWaitMs(random));
        }

        // 1× per trip een punt in de radius kiezen
        if (centerWalkTarget == null) {
            centerWalkTarget = MovementHelper.getRandomPointInRadius(treeArea, areaRadius);
        }
        boolean walkIssued = MovementHelper.walkTowardTarget(treeArea, areaRadius, centerWalkTarget, 25);
        if (!walkIssued) {
            walkToTreesFailCount++;
            paint.setCurrentStatus("⚠ Pathfinding mislukt, poging " + walkToTreesFailCount);
            paint.setLastAntiBanAction("Pathfind fallback (" + walkToTreesFailCount + "x)");
            // Na 5 mislukte pogingen: reset centerWalkTarget zodat een nieuw punt wordt gekozen
            if (walkToTreesFailCount >= 5) {
                centerWalkTarget = null;
                walkToTreesFailCount = 0;
            }
            return antiBan.varyDelay(randomDelay(1000, 1800));
        }

        walkToTreesFailCount = 0;
        lastTravelClickTime = travelNow;
        paint.setLastAntiBanAction("→ Boom locatie");
        return antiBan.varyDelay(TravelWalkHelper.postClickDelayMs(config, random));
    }

    private int handleWalkingBackToSpot() {
        if (shouldAbortActions()) return 300;
        if (preBankPosition != null) {
            if (isAtLocation(preBankPosition)) {
                preBankPosition = null;
                return 600;
            }
            long travelNow = System.currentTimeMillis();
            if (!TravelWalkHelper.reclickReady(lastTravelClickTime, config)) {
                return antiBan.varyDelay(TravelWalkHelper.shortWaitMs(random));
            }
            MovementHelper.walkTo(preBankPosition);
            lastTravelClickTime = travelNow;
            paint.setLastAntiBanAction("↩ Terug naar kapspot");
            return antiBan.varyDelay(TravelWalkHelper.postClickDelayMs(config, random));
        }
        preBankPosition = null;
        return 600;
    }

    // ===================== HELPERS =====================

    private boolean isWithinArea(WorldPoint point) {
        if (treeArea == null || point == null) return true;
        return Math.abs(point.getX() - treeArea.getX()) <= areaRadius
                && Math.abs(point.getY() - treeArea.getY()) <= areaRadius;
    }

    private void saveBankPosition() {
        if (preBankPosition == null) {
            IPlayer local = Players.getLocal();
            if (local != null) {
                // Sla positie NIET op als we bij een bank staan
                if (!isNearBank(local.getWorldLocation())) {
                    preBankPosition = local.getWorldLocation();
                }
            }
        }
    }

    private boolean isAtLocation(WorldPoint target) {
        return isAtLocationWithRange(target, LOCATION_THRESHOLD);
    }

    private boolean isAtLocationWithRange(WorldPoint target, int range) {
        IPlayer local = Players.getLocal();
        if (local == null || target == null) return true;
        return local.getWorldLocation().distanceTo(target) <= range;
    }

    private boolean isTileExcluded(WorldPoint point) {
        if (tileMarkerManager == null || point == null) return false;
        return tileMarkerManager.isTileExcluded(CombatBotPlugin.ActiveSkill.WOODCUTTING, point);
    }

    private boolean matchesTreeKeyword(String objectName, String keyword) {
        return WoodcutterConfig.matchesTreeObjectName(objectName, keyword);
    }

    private boolean isWithinCenter(WorldPoint point, WorldPoint center, int radius) {
        if (point == null || center == null) return false;
        if (point.getPlane() != center.getPlane()) return false;
        return Math.abs(point.getX() - center.getX()) <= radius
                && Math.abs(point.getY() - center.getY()) <= radius;
    }

    private boolean centerHasTargetTree(CenterManager.Center c, String treeKeyword) {
        if (c == null || c.point == null || treeKeyword == null || treeKeyword.trim().isEmpty()) {
            return false;
        }
        final String kw = treeKeyword;
        final int radius = Math.max(1, c.radius);
        ITileObject found = TileObjects.getNearest(obj ->
                obj != null
                        && obj.getName() != null
                        && matchesTreeKeyword(obj.getName(), kw)
                        && !obj.getName().toLowerCase().contains("dead")
                        && obj.hasAction("Chop down")
                        && !isFakeTree(obj.getWorldLocation())
                        && isWithinCenter(obj.getWorldLocation(), c.point, radius)
                        && !isTileExcluded(obj.getWorldLocation())
        );
        return found != null;
    }

    private boolean tryRetargetToCenterWithTree(String treeKeyword) {
        long now = System.currentTimeMillis();
        if (now - lastCenterRetargetMs < 8000L) {
            return false;
        }
        String centersData = config.wcCenters();
        if (CenterManager.countActive(centersData) < 2) {
            return false;
        }
        WorldPoint currentCenter = treeArea;
        if (currentCenter == null) {
            return false;
        }

        // Eerst: kleine auto-shift rond huidig center (paar tiles) voordat we naar ander center springen.
        WorldPoint shifted = findShiftedPointWithTree(currentCenter, areaRadius, treeKeyword);
        if (shifted != null && !shifted.equals(currentCenter)) {
            lastCenterRetargetMs = now;
            setActiveCenter(shifted, areaRadius);
            paint.setLastAntiBanAction("WC center shift: " + shifted.getX() + "," + shifted.getY());
            debug("Retarget WC center shift -> " + shifted + " (tree=" + treeKeyword + ")");
            return true;
        }

        IPlayer local = Players.getLocal();
        WorldPoint myPos = local != null ? local.getWorldLocation() : null;

        CenterManager.Center best = null;
        int bestDist = Integer.MAX_VALUE;
        for (CenterManager.Center c : CenterManager.parse(centersData)) {
            if (c == null || !c.active || c.point == null) continue;
            if (c.point.equals(currentCenter)) continue;
            if (!centerHasTargetTree(c, treeKeyword)) continue;
            int dist = myPos != null ? myPos.distanceTo(c.point) : 0;
            if (best == null || dist < bestDist) {
                best = c;
                bestDist = dist;
            }
        }
        if (best == null) {
            return false;
        }

        lastCenterRetargetMs = now;
        setActiveCenter(best.point, Math.max(1, best.radius));
        paint.setLastAntiBanAction("WC center switch: " + (best.name == null || best.name.isEmpty()
                ? (best.point.getX() + "," + best.point.getY())
                : best.name));
        debug("Retarget WC center -> " + best + " (tree=" + treeKeyword + ")");
        return true;
    }

    /**
     * Als center net naast de boom-cluster staat, probeer kleine verschuivingen (max ~3 tiles).
     * Dit helpt bij centers die net "mis" staan zonder direct van center te wisselen.
     */
    private WorldPoint findShiftedPointWithTree(WorldPoint base, int radius, String treeKeyword) {
        if (base == null) return null;
        // Check eerst huidige center nogmaals.
        CenterManager.Center self = new CenterManager.Center(base, Math.max(1, radius), "", true);
        if (centerHasTargetTree(self, treeKeyword)) {
            return base;
        }
        int[][] offsets = {
                {1, 0}, {-1, 0}, {0, 1}, {0, -1},
                {1, 1}, {1, -1}, {-1, 1}, {-1, -1},
                {2, 0}, {-2, 0}, {0, 2}, {0, -2},
                {2, 1}, {2, -1}, {-2, 1}, {-2, -1},
                {1, 2}, {-1, 2}, {1, -2}, {-1, -2},
                {3, 0}, {-3, 0}, {0, 3}, {0, -3}
        };
        for (int[] o : offsets) {
            WorldPoint p = new WorldPoint(base.getX() + o[0], base.getY() + o[1], base.getPlane());
            CenterManager.Center c = new CenterManager.Center(p, Math.max(1, radius), "", true);
            if (centerHasTargetTree(c, treeKeyword)) {
                return p;
            }
        }
        return null;
    }

    private String getTargetTreeName() {
        int wcLevel = Skills.getLevel(Skill.WOODCUTTING);

        if (config.wcUseSpecificTree()) {
            String specific = config.wcTreeName();
            int requiredLevel = getRequiredWcLevel(specific);
            if (wcLevel < requiredLevel) {
                // Specifieke boom te hoog → zoek beste boom IN het center
                String fallback = getBestTreeInCenter(wcLevel);
                paint.setLastAntiBanAction("⚠ WC lvl " + wcLevel + " < " + requiredLevel + " voor " + specific + " → " + fallback);
                return fallback;
            }
            return specific;
        }

        // Geen specifieke boom → zoek de beste boom die daadwerkelijk IN het center staat
        return getBestTreeInCenter(wcLevel);
    }

    /**
     * Scan welke bomen er daadwerkelijk in het center staan en kies de beste
     * die de speler kan hakken (op basis van WC level).
     * Fallback naar getBestTreeForLevel als er geen center is of scan mislukt.
     */
    private String getBestTreeInCenter(int wcLevel) {
        if (treeArea == null) {
            return WoodcutterConfig.getBestTreeForLevel(wcLevel);
        }

        // Scan van hoog naar laag welke bomen er in het center staan
        String bestFound = null;
        for (int i = WoodcutterConfig.TREE_LEVELS.length - 1; i >= 0; i--) {
            String[] entry = WoodcutterConfig.TREE_LEVELS[i];
            String keyword = entry[0];
            int requiredLevel = Integer.parseInt(entry[1]);

            if (wcLevel < requiredLevel) continue;

            // Check of deze boom daadwerkelijk in het center aanwezig is
            final String kw = keyword;
            ITileObject found = TileObjects.getNearest(obj ->
                    obj.getName() != null
                            && matchesTreeKeyword(obj.getName(), kw)
                            && !obj.getName().toLowerCase().contains("dead")
                            && obj.hasAction("Chop down")
                            && !isFakeTree(obj.getWorldLocation())
                            && isWithinArea(obj.getWorldLocation())
                            && !isTileExcluded(obj.getWorldLocation())
            );

            if (found != null) {
                bestFound = keyword;
                break; // Hoogste level boom gevonden, gebruik deze
            }
        }

        if (bestFound != null) {
            return bestFound;
        }

        // Geen enkele boom in center gevonden, fallback
        return WoodcutterConfig.getBestTreeForLevel(wcLevel);
    }

    private int getRequiredWcLevel(String treeKeyword) {
        if (treeKeyword == null) return 1;
        String lower = treeKeyword.toLowerCase();
        for (String[] entry : WoodcutterConfig.TREE_LEVELS) {
            if (lower.contains(entry[0].toLowerCase())) {
                return Integer.parseInt(entry[1]);
            }
        }
        return 1;
    }

    private boolean hasAxe() {
        for (String axe : AXE_NAMES) {
            if (Inventory.contains(axe) || Equipment.contains(axe)) return true;
        }
        return false;
    }

    private String getCurrentAxe() {
        for (int i = AXE_NAMES.length - 1; i >= 0; i--) {
            if (Inventory.contains(AXE_NAMES[i]) || Equipment.contains(AXE_NAMES[i])) {
                return AXE_NAMES[i];
            }
        }
        return null;
    }

    private int getAxeIndex(String axeName) {
        if (axeName == null) return -1;
        for (int i = 0; i < AXE_NAMES.length; i++) {
            if (AXE_NAMES[i].equals(axeName)) return i;
        }
        return -1;
    }

    private String[] reverseAxes() {
        String[] reversed = AXE_NAMES.clone();
        for (int i = 0; i < reversed.length / 2; i++) {
            String tmp = reversed[i];
            reversed[i] = reversed[reversed.length - 1 - i];
            reversed[reversed.length - 1 - i] = tmp;
        }
        return reversed;
    }

    private int randomDelay(int min, int max) {
        if (max <= min) {
            return min;
        }
        return min + random.nextInt(max - min);
    }

    private void sleep(int min, int max) {
        if (shouldAbortActions()) return;
        try {
            Thread.sleep(randomDelay(min, max));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean shouldAbortActions() {
        return config == null || !config.botEnabled();
    }
}
