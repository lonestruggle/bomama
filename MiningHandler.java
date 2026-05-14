package com.combatbot;

import net.runelite.api.coords.WorldPoint;
import net.storm.api.domain.actors.IPlayer;
import net.storm.api.domain.tiles.ITileObject;

import net.storm.sdk.entities.Players;
import net.storm.sdk.entities.TileObjects;
import net.storm.sdk.game.Skills;
import net.storm.sdk.items.Bank;
import net.storm.sdk.items.Equipment;
import net.storm.sdk.items.Inventory;
import net.runelite.api.Skill;

import java.util.List;
import java.util.Random;

/**
 * MiningHandler - beheert alle mining logica.
 *
 * Verbeteringen v0.2.0:
 * - Gebruikt gecentraliseerde MovementHelper.walkTowardTarget (geen eigen fallback meer)
 * - saveBankPosition slaat geen banklocaties op (voorkomt terugloop naar bank)
 * - Enkele handleBanking methode (duplicaat verwijderd)
 */
public class MiningHandler {

    private final CombatBotConfig config;
    private final AntiBan antiBan;
    private final CombatBotPaint paint;
    private final Random random = new Random();
    private TileMarkerManager tileMarkerManager;

    private static final int LOCATION_THRESHOLD = 5;

    public enum MiningState {
        MINING, DROPPING, WALKING_TO_BANK, BANKING, WALKING_TO_SPOT,
        WALKING_BACK_TO_SPOT, IDLE
    }

    private MiningState currentState = MiningState.IDLE;
    private WorldPoint miningSpot;
    private WorldPoint preBankPosition = null;
    private int areaRadius = 10;
    private WorldPoint centerWalkTarget = null;
    private int walkToSpotFailCount = 0;
    private long lastTravelClickTime = 0;

    private static final long IDLE_TIMEOUT_MS = 30_000;
    /**
     * Kortere "geen rock in zicht" timeout wanneer we op de rand van het werkgebied staan
     * (= verder dan innerWorkRadius van het center). Voorkomt dat de bot 30s op de hoek
     * blijft hangen omdat Storm's pathfinder zelf voor een rand-tile koos.
     */
    private static final long IDLE_EDGE_TIMEOUT_MS = 2_500;
    private long idleStartTime = 0;

    private long lastInteractTime = 0;
    private static final long INTERACT_COOLDOWN_MS = 1200;

    private final UniversalBankingManager bankingManager = new UniversalBankingManager();

    public MiningHandler(CombatBotConfig config, AntiBan antiBan, CombatBotPaint paint) {
        this.config = config;
        this.antiBan = antiBan;
        this.paint = paint;
    }

    private void debug(String msg) {
        DebugLog.log("Mining", msg);
    }

    public void resetState() {
        currentState = MiningState.IDLE;
        preBankPosition = null;
        idleStartTime = 0;
        lastInteractTime = 0;
        walkToSpotFailCount = 0;
        centerWalkTarget = null;
        lastTravelClickTime = 0;
    }

    public void setActiveCenter(WorldPoint center, int radius) {
        this.miningSpot = center;
        this.areaRadius = radius;
        this.centerWalkTarget = null;
        this.walkToSpotFailCount = 0;
        this.lastTravelClickTime = 0;
    }

    public void setTileMarkerManager(TileMarkerManager manager) {
        this.tileMarkerManager = manager;
    }

    public WorldPoint getMiningSpot() { return miningSpot; }
    public MiningState getCurrentState() { return currentState; }

    public int loop() {
        try {
            if (shouldAbortActions()) {
                return 300;
            }
            if (!hasPickaxe()) {
                if (Bank.isOpen()) {
                    currentState = MiningState.BANKING;
                    paint.setCurrentStatus("🏦 Pickaxe ophalen");
                    return handleBanking();
                }
                saveBankPosition();
                currentState = MiningState.WALKING_TO_BANK;
                paint.setCurrentStatus("→ Bank (pickaxe ophalen)");
                return handleWalkingToBank();
            }

            currentState = determineState();

            switch (currentState) {
                case MINING:
                    paint.setCurrentStatus("⛏ Aan het mijnen");
                    return handleMining();
                case DROPPING:
                    paint.setCurrentStatus("🗑 Erts droppen");
                    return handleDropping();
                case WALKING_TO_BANK:
                    paint.setCurrentStatus("→ Lopen naar bank");
                    return handleWalkingToBank();
                case BANKING:
                    paint.setCurrentStatus("🏦 Aan het banken");
                    return handleBanking();
                case WALKING_TO_SPOT:
                    paint.setCurrentStatus("→ Lopen naar mining spot");
                    return handleWalkingToSpot();
                case WALKING_BACK_TO_SPOT:
                    paint.setCurrentStatus("↩ Terug naar mijn spot");
                    return handleWalkingBackToSpot();
                case IDLE:
                default:
                    paint.setCurrentStatus("⏳ Wachten op rots...");
                    return antiBan.varyDelay(randomDelay(1000, 2000));
            }
        } catch (Exception e) {
            paint.setCurrentStatus("⚠ Mining fout: " + e.getMessage());
            return 2000;
        }
    }

    private MiningState determineState() {
        // Vreemde items (niet mining/tool/food/coins) eerst banken.
        if (hasForeignItemsForMining()) {
            saveBankPosition();
            return Bank.isOpen() ? MiningState.BANKING : MiningState.WALKING_TO_BANK;
        }

        // Bank open maar we hebben alles + inv niet vol → sluiten
        if (Bank.isOpen() && hasPickaxe() && !Inventory.isFull()) {
            Bank.close();
            bankingManager.waitForBankClose();
            return MiningState.IDLE;
        }

        if (Inventory.isFull()) {
            if (config.miningDropOre()) {
                return MiningState.DROPPING;
            }
            saveBankPosition();
            return Bank.isOpen() ? MiningState.BANKING : MiningState.WALKING_TO_BANK;
        }

        if (preBankPosition != null && !isAtLocation(preBankPosition)) {
            return MiningState.WALKING_BACK_TO_SPOT;
        }

        int areaThreshold = miningSpot != null ? areaRadius : LOCATION_THRESHOLD;
        if (miningSpot != null && !isAtLocationWithRange(miningSpot, areaThreshold)) {
            idleStartTime = 0;
            return MiningState.WALKING_TO_SPOT;
        }
        if (miningSpot != null && isAtLocationWithRange(miningSpot, areaRadius)) {
            centerWalkTarget = null;
        }

        String oreName = getTargetOreName();
        ITileObject rock = TileObjects.getNearest(obj ->
                obj.getName() != null
                        && obj.getName().toLowerCase().contains(oreName.toLowerCase())
                        && obj.hasAction("Mine")
                        && isWithinArea(obj.getWorldLocation())
                        && !isTileExcluded(obj.getWorldLocation())
        );

        if (rock != null) {
            idleStartTime = 0;
            return MiningState.MINING;
        }

        if (idleStartTime == 0) idleStartTime = System.currentTimeMillis();

        if (miningSpot != null) {
            long idleAge = System.currentTimeMillis() - idleStartTime;
            IPlayer pIdle = Players.getLocal();
            int innerR = innerWorkRadius(areaRadius);
            int distFromCenter = pIdle != null && pIdle.getWorldLocation() != null
                    ? pIdle.getWorldLocation().distanceTo(miningSpot)
                    : 0;
            boolean onOuterRing = distFromCenter > innerR;
            long effectiveTimeout = onOuterRing ? IDLE_EDGE_TIMEOUT_MS : IDLE_TIMEOUT_MS;
            if (idleAge > effectiveTimeout) {
                idleStartTime = 0;
                centerWalkTarget = null;
                return MiningState.WALKING_TO_SPOT;
            }
        }

        return MiningState.IDLE;
    }

    private boolean hasForeignItemsForMining() {
        return Inventory.getFirst(item -> {
            if (item == null || item.getName() == null) return false;
            String n = item.getName().toLowerCase();
            boolean allowed = n.contains("pickaxe")
                    || n.contains("ore")
                    || n.equals("coal")
                    || n.equals("clay")
                    || n.contains("gem")
                    || n.equals("hammer")
                    || n.equals("chisel")
                    || (item.hasAction("Eat") || item.hasAction("Drink"))
                    || n.contains("coins");
            return !allowed;
        }) != null;
    }

    private boolean isWithinArea(WorldPoint point) {
        if (miningSpot == null || point == null) return true;
        return Math.abs(point.getX() - miningSpot.getX()) <= areaRadius
                && Math.abs(point.getY() - miningSpot.getY()) <= areaRadius;
    }

    private int innerWorkRadius(int radius) {
        if (radius <= 2) return Math.max(1, radius);
        return Math.max(1, Math.min(radius - 2, (int) Math.floor(radius * 0.70)));
    }

    private boolean isTileExcluded(WorldPoint point) {
        if (tileMarkerManager == null || point == null) return false;
        return tileMarkerManager.isTileExcluded(CombatBotPlugin.ActiveSkill.MINING, point);
    }

    // ===================== SAVE BANK POSITION =====================

    private void saveBankPosition() {
        if (preBankPosition == null) {
            IPlayer local = Players.getLocal();
            if (local != null) {
                WorldPoint pos = local.getWorldLocation();
                // Sla positie NIET op als we bij een bank staan (voorkomt terugloop naar bank)
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

    // ===================== MINING =====================

    private int handleMining() {
        if (shouldAbortActions()) return 300;
        IPlayer local = Players.getLocal();
        if (local == null) return 1000;

        if (local.isAnimating()) {
            return antiBan.varyDelay(randomDelay(600, 1200));
        }
        if (System.currentTimeMillis() - lastInteractTime < INTERACT_COOLDOWN_MS) {
            return antiBan.varyDelay(randomDelay(400, 800));
        }

        // Configureerbare extra delay
        int delayMin = config.miningInteractDelayMin();
        int delayMax = config.miningInteractDelayMax();
        if (delayMin > 0 && delayMax > 0 && delayMax >= delayMin) {
            long timeSinceInteract = System.currentTimeMillis() - lastInteractTime;
            int requiredDelay = randomDelay(delayMin, delayMax);
            if (timeSinceInteract < requiredDelay) {
                return antiBan.varyDelay(randomDelay(400, 800));
            }
        }

        String oreName = getTargetOreName();
        ITileObject rock = TileObjects.getNearest(obj ->
                obj.getName() != null
                        && obj.getName().toLowerCase().contains(oreName.toLowerCase())
                        && obj.hasAction("Mine")
                        && isWithinArea(obj.getWorldLocation())
                        && !isTileExcluded(obj.getWorldLocation())
        );

        if (rock != null) {
            rock.interact("Mine");
            lastInteractTime = System.currentTimeMillis();
            paint.addOreMined();
            return antiBan.varyDelay(randomDelay(1200, 2000));
        }

        return antiBan.varyDelay(randomDelay(1000, 2000));
    }

    // ===================== DROPPING =====================

    private int handleDropping() {
        if (shouldAbortActions()) return 300;
        String oreItem = MiningConfig.getOreItemName(getTargetOreName());
        var ores = Inventory.getAll(oreItem);
        if (ores != null && !ores.isEmpty()) {
            for (var ore : ores) {
                if (shouldAbortActions()) break;
                ore.interact("Drop");
                sleep(100, 300);
            }
            paint.addOreDropped(ores.size());
            return antiBan.varyDelay(randomDelay(600, 1000));
        }
        return 600;
    }

    // ===================== BANKING =====================

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

    /** Zoek beste beschikbare pickaxe (inventory + equipment + bank). */
    private String findBestAvailablePickaxe() {
        int miningLevel = Skills.getLevel(Skill.MINING);
        for (String[] pick : reversedPickaxes()) {
            int reqLevel = Integer.parseInt(pick[1]);
            if (miningLevel >= reqLevel) {
                if (Inventory.contains(pick[0]) || Equipment.contains(pick[0])) return pick[0];
                if (Bank.isOpen() && Bank.contains(pick[0])) return pick[0];
            }
        }
        String current = getCurrentPickaxe();
        return current != null ? current : "Bronze pickaxe";
    }

    private java.util.List<UniversalBankingManager.Requirement> buildMiningRequirements() {
        java.util.List<UniversalBankingManager.Requirement> reqs = new java.util.ArrayList<>();
        String bestPick = findBestAvailablePickaxe();
        reqs.add(new UniversalBankingManager.Requirement(bestPick, 1, 1));
        return reqs;
    }

    /** Unified banking via UBM: deposit erts, keep/withdraw pickaxe. */
    private int handleBanking() {
        if (shouldAbortActions()) return 300;
        if (!Bank.isOpen()) {
            BankHelper.tryOpenFullBank();
            return antiBan.varyDelay(randomDelay(1500, 2000));
        }

        int oreCount = Inventory.getCount(i ->
                i.getName() != null && (i.getName().toLowerCase().contains("ore") || i.getName().equals("Coal")));

        java.util.List<UniversalBankingManager.Requirement> reqs = buildMiningRequirements();
        UniversalBankingManager.BankSessionStatus status = bankingManager.runBankSession(reqs);
        if (shouldAbortActions()) return 300;

        debug("handleBanking: UBM result=" + status.getResult());

        if (!status.isOk()) {
            paint.setLastAntiBanAction("⚠ " + status.getRestockItemName() + " niet in bank");
        }

        // Kleine pauze voordat we bank sluiten (menselijk gedrag)
        sleep(300, 600);
        Bank.close();
        bankingManager.waitForBankClose();

        paint.addOreBanked(oreCount);
        paint.setLastAntiBanAction(status.isOk() ? "✓ Banking compleet" : "⚠ Geen pickaxe gevonden");
        return antiBan.varyDelay(randomDelay(600, 1000));
    }

    // ===================== WALKING =====================

    private int handleWalkingToSpot() {
        if (shouldAbortActions()) return 300;
        if (miningSpot == null) return 1000;

        IPlayer local = Players.getLocal();
        if (local == null) return 1000;

        long travelNow = System.currentTimeMillis();
        if (!TravelWalkHelper.reclickReady(lastTravelClickTime, config)) {
            return antiBan.varyDelay(TravelWalkHelper.shortWaitMs(random));
        }

        if (centerWalkTarget == null) {
            // Kies geen rand/center als harde bestemming. Elke tile ruim binnen de radius is ok;
            // zodra een rock klikbaar is mag mining starten, ook tijdens het lopen.
            centerWalkTarget = MovementHelper.getRandomPointInRadius(miningSpot, innerWorkRadius(areaRadius));
        }

        // Gebruik gecentraliseerde walkTowardTarget
        boolean walkIssued = MovementHelper.walkTowardTarget(miningSpot, areaRadius, centerWalkTarget, 25);
        if (!walkIssued) {
            walkToSpotFailCount++;
            paint.setCurrentStatus("⚠ Pathfinding mislukt, poging " + walkToSpotFailCount);
            if (walkToSpotFailCount >= 5) {
                centerWalkTarget = null;
                walkToSpotFailCount = 0;
            }
            return antiBan.varyDelay(randomDelay(1000, 1800));
        }

        walkToSpotFailCount = 0;
        lastTravelClickTime = travelNow;
        paint.setLastAntiBanAction("→ Mining locatie");
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
            paint.setLastAntiBanAction("↩ Terug naar mijn spot");
            return antiBan.varyDelay(TravelWalkHelper.postClickDelayMs(config, random));
        }
        preBankPosition = null;
        return 600;
    }

    // ===================== HELPERS =====================

    private boolean hasPickaxe() {
        for (String[] pick : MiningConfig.PICKAXE_LEVELS) {
            if (Inventory.contains(pick[0]) || Equipment.contains(pick[0])) return true;
        }
        return false;
    }

    private String getCurrentPickaxe() {
        String[][] picks = MiningConfig.PICKAXE_LEVELS;
        for (int i = picks.length - 1; i >= 0; i--) {
            if (Inventory.contains(picks[i][0]) || Equipment.contains(picks[i][0])) {
                return picks[i][0];
            }
        }
        return null;
    }

    private int getPickaxeIndex(String pickName) {
        if (pickName == null) return -1;
        String[][] picks = MiningConfig.PICKAXE_LEVELS;
        for (int i = 0; i < picks.length; i++) {
            if (picks[i][0].equals(pickName)) return i;
        }
        return -1;
    }

    private String[][] reversedPickaxes() {
        String[][] picks = MiningConfig.PICKAXE_LEVELS.clone();
        for (int i = 0; i < picks.length / 2; i++) {
            String[] tmp = picks[i];
            picks[i] = picks[picks.length - 1 - i];
            picks[picks.length - 1 - i] = tmp;
        }
        return picks;
    }

    private String getTargetOreName() {
        if (config.miningUseSpecificOre()) {
            return config.miningOreName();
        }
        int miningLevel = Skills.getLevel(Skill.MINING);
        return MiningConfig.getBestOreForLevel(miningLevel);
    }

    private int randomDelay(int min, int max) {
        if (max <= min) {
            return min;
        }
        return min + random.nextInt(max - min);
    }

    private void sleep(int min, int max) {
        if (shouldAbortActions()) return;
        try { Thread.sleep(randomDelay(min, max)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private boolean shouldAbortActions() {
        return config == null || !config.botEnabled();
    }
}
