package com.combatbot;

import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.storm.api.domain.actors.IPlayer;
import net.storm.api.domain.items.IInventoryItem;
import net.storm.api.domain.tiles.ITileObject;

import net.storm.sdk.entities.Players;
import net.storm.sdk.entities.TileObjects;
import net.storm.sdk.game.Skills;
import net.storm.sdk.items.Bank;
import net.storm.sdk.items.Equipment;
import net.storm.sdk.items.Inventory;
import net.runelite.api.Skill;
import net.runelite.client.util.Text;

import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * MiningHandler — mining + bank/power-mining, center-specifieke ertskeuze ({@link MiningSiteRules}),
 * pickaxe-upgrade via bank-snapshot (zoals WC), concurrentie → chat/inv/obj-id pay-detectie, F2P wereld-hop.
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
    private static final long IDLE_EDGE_TIMEOUT_MS = 2_500;
    private long idleStartTime = 0;

    private long lastInteractTime = 0;
    private static final long INTERACT_COOLDOWN_MS = 1200;
    /** Kort na Mine-klik: scene/actions kunnen 1 tick scheef staan; niet meteen retargeten. */
    private static final long ROCK_GONE_GRACE_AFTER_CLICK_MS = 600L;

    private String activeCenterLabel = "";
    private Client rlClient;
    private ClientThread rlClientThread;
    private long lastMiningWorldHopMs;
    private long lastPickaxeUpgradeCheckMs;
    /** Actieve bank-trip voor betere pickaxe — geen {@link #preBankPosition} terugloop tussendoor. */
    private boolean pickaxeUpgradeTripPending;
    /** Inv-ore snapshot vóór laatste Mine-klik; -1 = geen pending swing. */
    private int pendingOreInvSnapshot = -1;
    /** Chat "You manage to mine some…" na klik op {@link #activeRockTile}. */
    private boolean pendingSwingChatOre;
    /** Rots opgegaan zonder onze erts (ander had de pay) → wereld-hop na drempel. */
    private int consecutiveRocksLostToOthers;
    private int skipRockCompetitionTicks;

    /** Tile van de rots waar we op klikten; leeg = geen actieve swing. */
    private WorldPoint activeRockTile;
    /**
     * Objectnaam van de rots bij de laatste Mine-klik. Voorkomt false "rots weg" wanneer
     * {@link #getTargetOreName()} per tick op copper/tin-sites wisselt (random pick).
     */
    private String activeRockClickedName;
    /** Storm object-id bij laatste Mine-klik; uitgepute rots heeft meestal andere id. */
    private int activeRockClickedObjectId;
    /** Rots weg (ander speler / uitgeput): direct andere rots, geen animatie- of interact-cooldown. */
    private boolean urgentRockRetarget;
    /** Laatst uitgeputte tile (niet opnieuw klikken bij urgent switch). */
    private WorldPoint lastDepletedRockTile;
    /** Tot deze tijd: laatst uitgeputte tile overslaan bij targets (ook na urgent=false), tegen dubbele klik op lege rots. */
    private long depletedRockAvoidUntilMs;
    private static final int ANIMATION_POLL_MIN_MS = 120;
    private static final int ANIMATION_POLL_MAX_MS = 280;
    /** Wereld-hop: zoveel keer achter elkaar de rots kwijt zonder eigen erts/chat. */
    private static final int ROCKS_LOST_BEFORE_WORLD_HOP = 4;
    /** Wacht op chat/inv vóór we "erts door ander" vaststellen. */
    private static final long PENDING_SWING_RESOLVE_MIN_MS = 1_200L;
    /** Geen tweede Mine-klik terwijl we naar de rots lopen / mining nog niet gestart is. */
    private static final long ENGAGE_ROCK_MAX_WAIT_MS = 4_500L;
    private static final long MINING_WORLD_HOP_COOLDOWN_MS = 14_000L;
    private static final long MINING_HOP_VERIFY_TIMEOUT_MS = 22_000L;
    private int pendingMiningHopTargetWorld = -1;
    private int pendingMiningHopStartWorld = -1;
    private int pendingMiningHopRetryCount = 0;
    private long pendingMiningHopRequestedMs = 0L;
    /** Kans op 2 snelle Mine-klikken bij start op nieuwe rots (korte delay ertussen). */
    private static final int QUICK_DOUBLE_CLICK_CHANCE_PERCENT = 34;
    private static final int SWING_RETRY_CLICK_CHANCE_PERCENT = 50;
    private boolean scheduledQuickSecondClick;
    private long quickSecondClickAtMs;
    private int swingsWithoutOreOnRock;
    private int swingsWithoutOreTarget;
    private long lastNoOreSwingCountedMs;
    /** Drop-sessie (menselijk patroon). */
    private List<IInventoryItem> pendingDropQueue;
    private int pendingDropIndex;
    private MiningDropHelper.DropStyle activeDropStyle;
    /** Powermine: drop na volgende tick (na erts of volle inv). */
    private boolean dropOresAfterSwing;
    private int oreSwingsSinceDrop;

    private final UniversalBankingManager bankingManager = new UniversalBankingManager();

    public MiningHandler(CombatBotConfig config, AntiBan antiBan, CombatBotPaint paint) {
        this.config = config;
        this.antiBan = antiBan;
        this.paint = paint;
    }

    public void setWorldHopClient(Client client, ClientThread clientThread) {
        this.rlClient = client;
        this.rlClientThread = clientThread;
    }

    /**
     * OSRS: "You manage to mine some copper ore." — bevestigt dat <b>wij</b> de pay kregen.
     * {@link CombatBotPlugin#onChatMessage}
     */
    public void onChatMessage(String message) {
        if (message == null || pendingOreInvSnapshot < 0) {
            return;
        }
        String low = Text.removeTags(message).trim().toLowerCase(Locale.ROOT).replace('\u2019', '\'');
        if (low.contains("you manage to mine")) {
            pendingSwingChatOre = true;
            debug("chat: onze erts @ " + formatRockTile(activeRockTile)
                    + " obj=" + activeRockClickedObjectId);
        }
    }

    private void debug(String msg) {
        DebugLog.log("Mining", msg);
    }

    private static String formatRockTile(WorldPoint tile) {
        if (tile == null) {
            return "?";
        }
        return tile.getX() + "," + tile.getY();
    }

    public void resetState() {
        currentState = MiningState.IDLE;
        preBankPosition = null;
        idleStartTime = 0;
        lastInteractTime = 0;
        walkToSpotFailCount = 0;
        centerWalkTarget = null;
        lastTravelClickTime = 0;
        pendingOreInvSnapshot = -1;
        pendingSwingChatOre = false;
        consecutiveRocksLostToOthers = 0;
        skipRockCompetitionTicks = 0;
        activeRockTile = null;
        activeRockClickedName = null;
        activeRockClickedObjectId = 0;
        urgentRockRetarget = false;
        lastDepletedRockTile = null;
        depletedRockAvoidUntilMs = 0L;
        clearEngageClickState();
        clearDropSession();
        dropOresAfterSwing = false;
        oreSwingsSinceDrop = 0;
        pickaxeUpgradeTripPending = false;
        clearPendingMiningWorldHop();
        if (paint != null) {
            paint.setMiningTargetOverlayLine("");
        }
    }

    private void clearDropSession() {
        pendingDropQueue = null;
        pendingDropIndex = 0;
        activeDropStyle = null;
    }

    private void schedulePowerDropIfAppropriate() {
        if (!effectivePowerDrop()) {
            return;
        }
        if (Inventory.isFull()) {
            dropOresAfterSwing = true;
            return;
        }
        oreSwingsSinceDrop++;
        int r = random.nextInt(100);
        if (r < 16) {
            dropOresAfterSwing = true;
            oreSwingsSinceDrop = 0;
            return;
        }
        int threshold = 3 + random.nextInt(5);
        if (oreSwingsSinceDrop >= threshold && r < 52) {
            dropOresAfterSwing = true;
            oreSwingsSinceDrop = 0;
        }
    }

    private boolean shouldDropOresNow() {
        if (!effectivePowerDrop()) {
            return false;
        }
        if (MiningDropHelper.collectDroppableOres().isEmpty()) {
            dropOresAfterSwing = false;
            return false;
        }
        if (Inventory.isFull() || dropOresAfterSwing) {
            dropOresAfterSwing = false;
            return true;
        }
        return false;
    }

    /** Voor {@link AreaOverlay}: tile van de rots die we targeten. */
    public WorldPoint getOverlayTargetRockTile() {
        return activeRockTile;
    }

    public void setActiveCenter(WorldPoint center, int radius) {
        setActiveCenter(center, radius, "");
    }

    public void setActiveCenter(WorldPoint center, int radius, String centerLabel) {
        WorldPoint prev = this.miningSpot;
        int prevR = this.areaRadius;
        this.miningSpot = center;
        this.areaRadius = radius;
        this.activeCenterLabel = centerLabel != null ? centerLabel : "";
        boolean centerChanged = prev == null || center == null || prevR != radius
                || (center != null && !prev.equals(center));
        if (centerChanged) {
            this.centerWalkTarget = null;
            this.walkToSpotFailCount = 0;
            this.lastTravelClickTime = 0;
            this.lastDepletedRockTile = null;
            this.depletedRockAvoidUntilMs = 0L;
        }
        this.pendingOreInvSnapshot = -1;
        this.consecutiveRocksLostToOthers = 0;
        this.pendingSwingChatOre = false;
        this.skipRockCompetitionTicks = 0;
        this.activeRockTile = null;
        this.activeRockClickedName = null;
        this.activeRockClickedObjectId = 0;
        this.urgentRockRetarget = false;
        clearEngageClickState();
        if (paint != null) {
            if (center != null) {
                String lab = activeCenterLabel != null && !activeCenterLabel.isEmpty()
                        ? activeCenterLabel
                        : "Mining";
                paint.setMiningTargetOverlayLine(lab);
            } else {
                paint.setMiningTargetOverlayLine("");
            }
        }
    }

    public void setTileMarkerManager(TileMarkerManager manager) {
        this.tileMarkerManager = manager;
    }

    public WorldPoint getMiningSpot() {
        return miningSpot;
    }

    public MiningState getCurrentState() {
        return currentState;
    }

    public int loop() {
        try {
            if (shouldAbortActions()) {
                return 300;
            }
            int hopWait = processPendingMiningWorldHop();
            if (hopWait > 0) {
                return hopWait;
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

            if (pickaxeUpgradeTripPending || shouldDoPickaxeUpgradeTrip()) {
                if (!needsPickaxeUpgradeByLevel()) {
                    pickaxeUpgradeTripPending = false;
                } else if (!hasBetterPickaxeKnownInBank()) {
                    pickaxeUpgradeTripPending = false;
                } else {
                    pickaxeUpgradeTripPending = true;
                    if (Bank.isOpen()) {
                        currentState = MiningState.BANKING;
                        paint.setCurrentStatus("🏦 Betere pickaxe ophalen");
                        return handleBanking();
                    }
                    currentState = MiningState.WALKING_TO_BANK;
                    paint.setCurrentStatus("→ Bank (betere pickaxe)");
                    return handleWalkingToBank();
                }
            }

            currentState = determineState();

            refreshMiningDisposalPaintHint();

            switch (currentState) {
                case MINING:
                    paint.setCurrentStatus("⛏ Aan het mijnen");
                    return handleMining();
                case DROPPING:
                    paint.setCurrentStatus("🗑 Erts droppen (powermine)");
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

    private MiningSiteRules.MiningSiteKind siteKind() {
        return MiningSiteRules.classify(activeCenterLabel, miningSpot);
    }

    private boolean accountPreferDropOre() {
        return AccountCenterBehaviorStore.forCenter(
                AccountCenterBehaviorStore.SkillKind.MINING, miningSpot, config).drop;
    }

    private boolean effectivePowerDrop() {
        int lvl = Skills.getLevel(Skill.MINING);
        String rock = getTargetOreName();
        return MiningSiteRules.shouldDropOre(siteKind(), accountPreferDropOre(), lvl, rock);
    }

    /** Voor rotatie-cleanup: banken als volle inventaris niet gedropt wordt (incl. site-regels AK2/3). */
    public boolean shouldBankFilledInventory() {
        return !effectivePowerDrop();
    }

    private void refreshMiningDisposalPaintHint() {
        if (paint == null) {
            return;
        }
        int lvl = Skills.getLevel(Skill.MINING);
        String rock = getTargetOreName();
        MiningSiteRules.MiningSiteKind k = siteKind();
        String mode = MiningSiteRules.oreDisposalLabel(k, accountPreferDropOre(), lvl, rock);
        String lab = activeCenterLabel != null && !activeCenterLabel.isEmpty()
                ? activeCenterLabel
                : "Mining";
        paint.setMiningTargetOverlayLine(lab + " · " + mode);
    }

    private MiningState determineState() {
        if (hasForeignItemsForMining()) {
            saveBankPosition();
            return Bank.isOpen() ? MiningState.BANKING : MiningState.WALKING_TO_BANK;
        }

        if (Bank.isOpen() && hasPickaxe() && !Inventory.isFull()) {
            Bank.close();
            bankingManager.waitForBankClose();
            return MiningState.IDLE;
        }

        // Nog bij de bank na sluiten van de UI: géén tussenstop naar pre-bank-tegel meer.
        // Vanuit het bankgebouw faalt pad naar veel "preBankPosition"-tegels vaak langdurig → stilstand (StuckWarn).
        WorldPoint cur = Players.getLocal() != null ? Players.getLocal().getWorldLocation() : null;
        if (preBankPosition != null && cur != null && BankHelper.isNearAnyBank(cur)) {
            preBankPosition = null;
        }

        if (shouldDropOresNow()) {
            return MiningState.DROPPING;
        }

        if (Inventory.isFull()) {
            if (effectivePowerDrop()) {
                return MiningState.DROPPING;
            }
            saveBankPosition();
            return Bank.isOpen() ? MiningState.BANKING : MiningState.WALKING_TO_BANK;
        }

        if (!pickaxeUpgradeTripPending && preBankPosition != null && !isAtLocation(preBankPosition)) {
            return MiningState.WALKING_BACK_TO_SPOT;
        }

        int areaThreshold = miningSpot != null ? areaRadius : LOCATION_THRESHOLD;
        WorldPoint approachTile = MiningSiteRules.miningApproachTile(siteKind());
        int approachRadius = MiningSiteRules.miningApproachRadius(siteKind());
        if (approachTile != null && !isWithinMiningArea()) {
            if (!isAtLocationWithRange(approachTile, approachRadius)) {
                idleStartTime = 0;
                return MiningState.WALKING_TO_SPOT;
            }
            centerWalkTarget = null;
        } else if (miningSpot != null && !isWithinMiningArea()) {
            idleStartTime = 0;
            return MiningState.WALKING_TO_SPOT;
        }
        if (miningSpot != null && isWithinMiningArea()) {
            centerWalkTarget = null;
        }

        ITileObject rock = pickNearestMineableRockForSite(null, false);

        if (rock != null) {
            idleStartTime = 0;
            return MiningState.MINING;
        }

        if (idleStartTime == 0) {
            idleStartTime = System.currentTimeMillis();
        }

        if (miningSpot != null) {
            long idleAge = System.currentTimeMillis() - idleStartTime;
            IPlayer pIdle = Players.getLocal();
            int innerR = innerWorkRadius(areaRadius);
            WorldPoint idleAnchor = approachTile != null && !isWithinMiningArea() ? approachTile : miningSpot;
            int distFromCenter = pIdle != null && pIdle.getWorldLocation() != null && idleAnchor != null
                    ? pIdle.getWorldLocation().distanceTo(idleAnchor)
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
        return Inventory.getFirst(item -> item != null && !MiningDropHelper.isAllowedWhileMining(item)) != null;
    }

    private boolean isWithinArea(WorldPoint point) {
        if (miningSpot == null || point == null) {
            return true;
        }
        return Math.abs(point.getX() - miningSpot.getX()) <= areaRadius
                && Math.abs(point.getY() - miningSpot.getY()) <= areaRadius
                && point.getPlane() == miningSpot.getPlane();
    }

    /** Speler binnen mining-center radius — daarna geen vaste stand-tile meer afdwingen (AK3). */
    private boolean isWithinMiningArea() {
        if (miningSpot == null) {
            return true;
        }
        IPlayer local = Players.getLocal();
        if (local == null) {
            return false;
        }
        WorldPoint loc = local.getWorldLocation();
        return loc != null && isWithinArea(loc);
    }

    private int innerWorkRadius(int radius) {
        if (radius <= 2) {
            return Math.max(1, radius);
        }
        return Math.max(1, Math.min(radius - 2, (int) Math.floor(radius * 0.70)));
    }

    private boolean isTileExcluded(WorldPoint point) {
        if (tileMarkerManager == null || point == null) {
            return false;
        }
        return tileMarkerManager.isTileExcluded(CombatBotPlugin.ActiveSkill.MINING, point);
    }

    private void saveBankPosition() {
        if (preBankPosition == null) {
            IPlayer local = Players.getLocal();
            if (local != null) {
                WorldPoint pos = local.getWorldLocation();
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
        if (local == null) {
            return true;
        }
        return local.getWorldLocation().distanceTo(target) <= range;
    }

    private void markRockDepleted(String reason) {
        if (activeRockTile != null) {
            lastDepletedRockTile = activeRockTile;
            debug(reason + " @ " + activeRockTile.getX() + "," + activeRockTile.getY());
        }
        activeRockTile = null;
        activeRockClickedName = null;
        activeRockClickedObjectId = 0;
        pendingOreInvSnapshot = -1;
        pendingSwingChatOre = false;
        clearEngageClickState();
        urgentRockRetarget = true;
        depletedRockAvoidUntilMs = System.currentTimeMillis() + 1800L;
    }

    private boolean hasPendingSwing() {
        return pendingOreInvSnapshot >= 0;
    }

    private boolean pendingSwingResolvedAsOurs() {
        if (pendingSwingChatOre) {
            return true;
        }
        if (pendingOreInvSnapshot < 0) {
            return false;
        }
        return countMiningOreInInventory() > pendingOreInvSnapshot;
    }

    private void creditPendingSwingOreToPaint() {
        if (pendingOreInvSnapshot < 0) {
            return;
        }
        int oreNow = countMiningOreInInventory();
        int gained = oreNow - pendingOreInvSnapshot;
        if (gained > 0) {
            for (int i = 0; i < gained; i++) {
                paint.addOreMined();
            }
        } else if (pendingSwingChatOre) {
            // Drop-mode: ore zit niet in inv, wel chat "You manage to mine some…"
            paint.addOreMined();
        }
    }

    private void completeOurSwing(String reason) {
        creditPendingSwingOreToPaint();
        consecutiveRocksLostToOthers = 0;
        schedulePowerDropIfAppropriate();
        debug(reason + " (onze erts) @ " + formatRockTile(activeRockTile)
                + " obj=" + activeRockClickedObjectId);
        markRockDepleted(reason);
    }

    private int noteRockStolenByOther(String reason) {
        if (competitionWorldHopEnabled()) {
            consecutiveRocksLostToOthers++;
            debug(reason + " — geen chat/erts (streak=" + consecutiveRocksLostToOthers + "/"
                    + ROCKS_LOST_BEFORE_WORLD_HOP + ") @ " + formatRockTile(activeRockTile)
                    + " wasObj=" + activeRockClickedObjectId);
            if (consecutiveRocksLostToOthers >= ROCKS_LOST_BEFORE_WORLD_HOP) {
                int hop = tryCompetitionWorldHop(ROCKS_LOST_BEFORE_WORLD_HOP + "x rots zonder eigen erts");
                markRockDepleted(reason);
                return hop > 0 ? hop : 0;
            }
        } else {
            debug(reason + " @ " + formatRockTile(activeRockTile));
        }
        markRockDepleted(reason);
        return 0;
    }

    /**
     * Rots niet meer dezelfde (obj-id/naam) of leeg — bepaal of wij of een ander de erts kreeg.
     * @return delay ms als wereld-hop gepland (&gt;0), anders 0
     */
    private int resolveActiveRockChanged(long now, String oreName, String depletedReason) {
        long sinceMine = now - lastInteractTime;
        if (sinceMine >= 0 && sinceMine < ROCK_GONE_GRACE_AFTER_CLICK_MS) {
            return -1;
        }
        if (hasPendingSwing() && sinceMine < PENDING_SWING_RESOLVE_MIN_MS) {
            return -1;
        }
        if (hasPendingSwing() && pendingSwingResolvedAsOurs()) {
            completeOurSwing(depletedReason + " (wij)");
            return 0;
        }
        if (hasPendingSwing()) {
            return noteRockStolenByOther(depletedReason + " (ander)");
        }
        markRockDepleted(depletedReason);
        return 0;
    }

    private void clearEngageClickState() {
        scheduledQuickSecondClick = false;
        quickSecondClickAtMs = 0L;
        swingsWithoutOreOnRock = 0;
        swingsWithoutOreTarget = 0;
        lastNoOreSwingCountedMs = 0L;
    }

    private void resetSwingRetryCountersForNewRock() {
        swingsWithoutOreOnRock = 0;
        swingsWithoutOreTarget = 3 + random.nextInt(3);
        lastNoOreSwingCountedMs = 0L;
    }

    private ITileObject findActiveRockObject(String oreRockFragment) {
        if (activeRockTile == null) {
            return null;
        }
        return findMineableRockObjectAt(activeRockTile, oreRockFragment);
    }

    private boolean shouldWaitForActiveRockEngagement(IPlayer local, long now, String oreName) {
        if (!hasPendingSwing() || activeRockTile == null || urgentRockRetarget) {
            return false;
        }
        if (pendingSwingResolvedAsOurs()) {
            return false;
        }
        if (scheduledQuickSecondClick && now < quickSecondClickAtMs) {
            return true;
        }
        if (local.isMoving()) {
            return true;
        }
        long since = now - lastInteractTime;
        if (since < 0 || since > ENGAGE_ROCK_MAX_WAIT_MS) {
            return false;
        }
        if (local.isAnimating()) {
            return false;
        }
        return rockStillMineableAt(activeRockTile, oreName);
    }

    private Integer tryExecuteQuickSecondClick(long now, String oreName) {
        if (!scheduledQuickSecondClick || now < quickSecondClickAtMs) {
            return null;
        }
        scheduledQuickSecondClick = false;
        ITileObject rock = findActiveRockObject(oreName);
        if (rock == null) {
            return null;
        }
        rock.interact("Mine");
        lastInteractTime = now;
        debug("snelle 2e Mine @ " + formatRockTile(activeRockTile));
        return antiBan.varyDelay(randomDelay(70, 160));
    }

    private Integer trySwingRetryReclick(IPlayer local, long now, String oreName) {
        if (!hasPendingSwing() || activeRockTile == null || local.isAnimating() || urgentRockRetarget) {
            return null;
        }
        if (!rockStillMineableAt(activeRockTile, oreName) || pendingSwingResolvedAsOurs()) {
            return null;
        }
        long since = now - lastInteractTime;
        if (since < PENDING_SWING_RESOLVE_MIN_MS) {
            return null;
        }
        if (now - lastNoOreSwingCountedMs < 700) {
            return null;
        }
        lastNoOreSwingCountedMs = now;
        swingsWithoutOreOnRock++;
        if (swingsWithoutOreTarget <= 0) {
            swingsWithoutOreTarget = 3 + random.nextInt(3);
        }
        if (swingsWithoutOreOnRock < swingsWithoutOreTarget) {
            return null;
        }
        if (random.nextInt(100) >= SWING_RETRY_CLICK_CHANCE_PERCENT) {
            swingsWithoutOreOnRock = 0;
            swingsWithoutOreTarget = 3 + random.nextInt(3);
            return null;
        }
        int reached = swingsWithoutOreOnRock;
        ITileObject rock = findActiveRockObject(oreName);
        if (rock == null) {
            return null;
        }
        rock.interact("Mine");
        lastInteractTime = now;
        pendingSwingChatOre = false;
        swingsWithoutOreOnRock = 0;
        swingsWithoutOreTarget = 3 + random.nextInt(3);
        debug("herklik Mine na " + reached + " swings zonder erts @ " + formatRockTile(activeRockTile));
        return antiBan.varyDelay(randomDelay(450, 950));
    }

    private int issueMineClick(ITileObject rock, boolean wasUrgent) {
        WorldPoint rockTile = rock.getWorldLocation();
        activeRockTile = rockTile;
        activeRockClickedName = rock.getName();
        activeRockClickedObjectId = safeGetObjectId(rock);
        urgentRockRetarget = false;
        pendingOreInvSnapshot = countMiningOreInInventory();
        pendingSwingChatOre = false;
        resetSwingRetryCountersForNewRock();
        scheduledQuickSecondClick = false;
        long now = System.currentTimeMillis();
        if (!wasUrgent && random.nextInt(100) < QUICK_DOUBLE_CLICK_CHANCE_PERCENT) {
            scheduledQuickSecondClick = true;
            quickSecondClickAtMs = now + randomDelay(65, 175);
        }
        debug("Mine @ " + rockTile.getX() + "," + rockTile.getY()
                + " id=" + activeRockClickedObjectId + " " + activeRockClickedName
                + (scheduledQuickSecondClick ? " (+snelle 2e gepland)" : ""));
        rock.interact("Mine");
        lastInteractTime = now;
        return antiBan.varyDelay(randomDelay(wasUrgent ? 350 : 1200, wasUrgent ? 750 : 2000));
    }

    private int handleMining() {
        if (shouldAbortActions()) {
            return 300;
        }
        IPlayer local = Players.getLocal();
        if (local == null) {
            return 1000;
        }

        long now = System.currentTimeMillis();
        String oreName = getTargetOreName();

        Integer quickSecond = tryExecuteQuickSecondClick(now, oreName);
        if (quickSecond != null) {
            return quickSecond;
        }

        if (hasPendingSwing() && activeRockTile != null && !urgentRockRetarget) {
            if (pendingSwingResolvedAsOurs()) {
                completeOurSwing("erts bevestigd");
                return antiBan.varyDelay(randomDelay(350, 700));
            }
            if (shouldWaitForActiveRockEngagement(local, now, oreName)) {
                return antiBan.varyDelay(randomDelay(280, 520));
            }
            Integer swingRetry = trySwingRetryReclick(local, now, oreName);
            if (swingRetry != null) {
                return swingRetry;
            }
        }

        if (activeRockTile != null) {
            boolean occupied = otherPlayerOnTile(activeRockTile);
            boolean rockOk = rockStillMineableAt(activeRockTile, oreName);
            if (occupied) {
                markRockDepleted("speler op rots");
            } else if (!rockOk) {
                int resolve = resolveActiveRockChanged(now, oreName, "rots uitgeput");
                if (resolve > 0) {
                    return resolve;
                }
                if (resolve < 0) {
                    return antiBan.varyDelay(randomDelay(250, 550));
                }
            }
        }

        // Tijdens animatie: vaak pollen of rots nog Mine heeft (niet wachten tot animatie stopt).
        if (local.isAnimating() && activeRockTile != null) {
            boolean occupied = otherPlayerOnTile(activeRockTile);
            boolean rockOk = rockStillMineableAt(activeRockTile, oreName);
            if (occupied || !rockOk) {
                if (occupied) {
                    markRockDepleted("speler op rots (tijdens swing)");
                } else {
                    int resolve = resolveActiveRockChanged(now, oreName, "rots weg tijdens swing");
                    if (resolve > 0) {
                        return resolve;
                    }
                    if (resolve < 0) {
                        return antiBan.varyDelay(randomDelay(ANIMATION_POLL_MIN_MS, ANIMATION_POLL_MAX_MS));
                    }
                }
            } else {
                return antiBan.varyDelay(randomDelay(ANIMATION_POLL_MIN_MS, ANIMATION_POLL_MAX_MS));
            }
        }

        if (local.isAnimating() && activeRockTile == null) {
            urgentRockRetarget = true;
        }

        if (!urgentRockRetarget && now - lastInteractTime < INTERACT_COOLDOWN_MS) {
            return antiBan.varyDelay(randomDelay(400, 800));
        }

        if (!urgentRockRetarget) {
            int delayMin = config.miningInteractDelayMin();
            int delayMax = config.miningInteractDelayMax();
            if (delayMin > 0 && delayMax > 0 && delayMax >= delayMin) {
                long timeSinceInteract = now - lastInteractTime;
                int requiredDelay = randomDelay(delayMin, delayMax);
                if (timeSinceInteract < requiredDelay) {
                    return antiBan.varyDelay(randomDelay(400, 800));
                }
            }
        }

        if (skipRockCompetitionTicks > 0) {
            skipRockCompetitionTicks--;
            return antiBan.varyDelay(randomDelay(350, 700));
        }

        WorldPoint avoidTile = null;
        if (lastDepletedRockTile != null
                && (urgentRockRetarget || now < depletedRockAvoidUntilMs)) {
            avoidTile = lastDepletedRockTile;
        }
        ITileObject rock = pickNearestMineableRockForSite(avoidTile, true);

        if (rock == null && MiningSiteRules.shouldApplyMiningCompetitionWorldHop(siteKind())) {
            rock = pickNearestMineableRockForSite(avoidTile, false);
        }

        if (rock != null && MiningSiteRules.shouldApplyMiningCompetitionWorldHop(siteKind())
                && otherPlayerOnTile(rock.getWorldLocation())) {
            skipRockCompetitionTicks = 2;
            return antiBan.varyDelay(randomDelay(300, 600));
        }

        if (rock != null) {
            boolean wasUrgent = urgentRockRetarget;
            if (wasUrgent) {
                debug("directe switch → " + rock.getWorldLocation().getX() + ","
                        + rock.getWorldLocation().getY());
            }
            return issueMineClick(rock, wasUrgent);
        }

        urgentRockRetarget = false;
        activeRockTile = null;
        activeRockClickedName = null;
        activeRockClickedObjectId = 0;
        return antiBan.varyDelay(randomDelay(400, 900));
    }

    private int countMiningOreInInventory() {
        try {
            return Inventory.getCount(i ->
                    i != null && i.getName() != null
                            && (i.getName().toLowerCase(Locale.ROOT).contains("ore")
                            || i.getName().equalsIgnoreCase("Coal")));
        } catch (Throwable t) {
            return 0;
        }
    }

    private boolean otherPlayerOnTile(WorldPoint tile) {
        if (tile == null) {
            return false;
        }
        IPlayer me = Players.getLocal();
        if (me == null) {
            return false;
        }
        try {
            List<IPlayer> others = Players.getAll(p ->
                    p != null
                            && !p.equals(me)
                            && p.getWorldLocation() != null
                            && p.getWorldLocation().equals(tile));
            return others != null && !others.isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean competitionWorldHopEnabled() {
        return MiningSiteRules.shouldApplyMiningCompetitionWorldHop(siteKind());
    }

    private int tryCompetitionWorldHop(String reason) {
        if (!competitionWorldHopEnabled()) {
            return 0;
        }
        return tryScheduleMiningWorldHop(reason);
    }

    private Client resolveRlClient() {
        if (rlClient != null) {
            return rlClient;
        }
        try {
            Object wrapped = net.storm.sdk.game.Client.getClient().getWrapped();
            if (wrapped instanceof Client) {
                rlClient = (Client) wrapped;
                return rlClient;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void clearPendingMiningWorldHop() {
        pendingMiningHopTargetWorld = -1;
        pendingMiningHopStartWorld = -1;
        pendingMiningHopRetryCount = 0;
        pendingMiningHopRequestedMs = 0L;
    }

    /** Wacht op wereld-hop (zoals {@link LootHandler#maybeRetryPendingHop}). */
    private int processPendingMiningWorldHop() {
        if (pendingMiningHopTargetWorld <= 0 || pendingMiningHopRequestedMs <= 0L) {
            return 0;
        }
        if (AccountSwitchWorldHop.tryAcceptWorldHopConfirmation()) {
            return antiBan.varyDelay(randomDelay(400, 800));
        }
        Client rl = resolveRlClient();
        if (rl == null) {
            return antiBan.varyDelay(randomDelay(1200, 2000));
        }
        if (AccountSwitchWorldHop.isWorldRecentlyRejected(pendingMiningHopTargetWorld)) {
            int retry = AccountSwitchWorldHop.pickRandomF2pWorldId(rl);
            if (retry > 0 && retry != pendingMiningHopTargetWorld
                    && AccountSwitchWorldHop.scheduleHopToWorld(rl, rlClientThread, retry)) {
                pendingMiningHopStartWorld = rl.getWorld();
                pendingMiningHopTargetWorld = retry;
                pendingMiningHopRetryCount = 0;
                pendingMiningHopRequestedMs = System.currentTimeMillis();
                paint.setCurrentStatus("🌍 Andere wereld (w" + retry + ")…");
                return antiBan.varyDelay(randomDelay(2000, 3500));
            }
        }
        GameState gs = rl.getGameState();
        if (gs == GameState.HOPPING) {
            paint.setCurrentStatus("🌍 Wereld wisselen (laden)…");
            return antiBan.varyDelay(randomDelay(1100, 2000));
        }
        int cur = rl.getWorld();
        if (cur == pendingMiningHopTargetWorld
                || (pendingMiningHopStartWorld > 0 && cur != pendingMiningHopStartWorld)) {
            debug("world hop confirm: start=w" + pendingMiningHopStartWorld + " now=w" + cur
                    + " target=w" + pendingMiningHopTargetWorld);
            clearPendingMiningWorldHop();
            return 0;
        }
        long since = System.currentTimeMillis() - pendingMiningHopRequestedMs;
        paint.setCurrentStatus("🌍 Wereld wisselen → w" + pendingMiningHopTargetWorld + "…");
        if (since < MINING_HOP_VERIFY_TIMEOUT_MS) {
            return antiBan.varyDelay(randomDelay(900, 1600));
        }
        if (pendingMiningHopRetryCount >= 2) {
            debug("world hop verify timeout (w" + cur + " → w" + pendingMiningHopTargetWorld + ")");
            paint.setLastAntiBanAction("Mining: 🌍 hop timeout — later opnieuw");
            clearPendingMiningWorldHop();
            return antiBan.varyDelay(randomDelay(1500, 2600));
        }
        pendingMiningHopRetryCount++;
        pendingMiningHopRequestedMs = System.currentTimeMillis();
        if (AccountSwitchWorldHop.scheduleHopToWorld(rl, rlClientThread, pendingMiningHopTargetWorld)) {
            paint.setLastAntiBanAction("Mining: 🌍 hop retry " + pendingMiningHopRetryCount
                    + " → w" + pendingMiningHopTargetWorld);
            debug("world hop retry " + pendingMiningHopRetryCount);
        }
        return antiBan.varyDelay(randomDelay(2000, 3500));
    }

    private int tryScheduleMiningWorldHop(String reason) {
        Client rl = resolveRlClient();
        if (rl == null) {
            debug("world hop: geen client");
            return 0;
        }
        long now = System.currentTimeMillis();
        if (now - lastMiningWorldHopMs < MINING_WORLD_HOP_COOLDOWN_MS && pendingMiningHopTargetWorld <= 0) {
            return 0;
        }
        try {
            int target = AccountSwitchWorldHop.pickRandomF2pWorldId(rl);
            if (!AccountSwitchWorldHop.scheduleHopToWorld(rl, rlClientThread, target)) {
                debug("world hop: schedule mislukt (w" + target + " niet in lijst)");
                return 0;
            }
            pendingMiningHopStartWorld = rl.getWorld();
            pendingMiningHopTargetWorld = target;
            pendingMiningHopRetryCount = 0;
            pendingMiningHopRequestedMs = now;
            lastMiningWorldHopMs = now;
            consecutiveRocksLostToOthers = 0;
            pendingOreInvSnapshot = -1;
            pendingSwingChatOre = false;
            clearEngageClickState();
            activeRockTile = null;
            activeRockClickedName = null;
            activeRockClickedObjectId = 0;
            lastDepletedRockTile = null;
            depletedRockAvoidUntilMs = 0L;
            urgentRockRetarget = false;
            currentState = MiningState.IDLE;
            paint.setLastAntiBanAction("Mining: 🌍 " + reason + " → w" + target);
            paint.setCurrentStatus("🌍 Wereld wisselen (mining)");
            debug("world hop (ingame Storm): " + reason + " w" + pendingMiningHopStartWorld + " → w" + target);
            return antiBan.varyDelay(randomDelay(2500, 4000));
        } catch (Throwable t) {
            debug("world hop failed: " + t.getMessage());
            clearPendingMiningWorldHop();
            return 0;
        }
    }

    private int handleDropping() {
        if (shouldAbortActions()) {
            return 300;
        }
        if (pendingDropQueue == null || pendingDropIndex >= pendingDropQueue.size()) {
            List<IInventoryItem> loot = MiningDropHelper.collectDroppableOres();
            if (loot.isEmpty()) {
                clearDropSession();
                return 600;
            }
            activeDropStyle = MiningDropHelper.pickStyle(random);
            pendingDropQueue = MiningDropHelper.orderForStyle(loot, activeDropStyle, random);
            pendingDropIndex = 0;
            debug("drop sessie: " + activeDropStyle + " (" + pendingDropQueue.size() + " items)");
        }

        int remaining = pendingDropQueue.size() - pendingDropIndex;
        int batchMax = MiningDropHelper.pickDropBatchSize(remaining, pendingDropQueue.size(), random);
        int dropped = 0;
        while (pendingDropIndex < pendingDropQueue.size() && dropped < batchMax) {
            if (shouldAbortActions()) {
                break;
            }
            IInventoryItem item = pendingDropQueue.get(pendingDropIndex++);
            if (item != null && MiningDropHelper.isDroppableMiningLoot(item)) {
                InventoryActionHelper.interact(config, item, "Drop");
                sleepBetweenOreDrops();
                dropped++;
            }
        }

        if (pendingDropIndex >= pendingDropQueue.size()) {
            if (dropped > 0) {
                paint.addOreDropped(dropped);
            }
            clearDropSession();
            oreSwingsSinceDrop = 0;
            return antiBan.varyDelay(randomDelay(700, 1400));
        }
        if (dropped > 0) {
            paint.addOreDropped(dropped);
        }
        return antiBan.varyDelay(randomDelay(550, 1150));
    }

    private void sleepBetweenOreDrops() {
        int base = randomDelay(220, 520);
        if (random.nextInt(100) < 24) {
            base += randomDelay(140, 420);
        }
        sleep(base, base + random.nextInt(120) + 60);
    }

    private int handleWalkingToBank() {
        if (shouldAbortActions()) {
            return 300;
        }
        if (Bank.isOpen()) {
            return 600;
        }
        if (BankHelper.interactIfNearby()) {
            return antiBan.varyDelay(randomDelay(1200, 1800));
        }
        MiningSiteRules.MiningBankAnchor anchor = MiningSiteRules.bankAnchor(
                siteKind(), Skills.getLevel(Skill.MINING), getTargetOreName());
        boolean walked;
        if (anchor == MiningSiteRules.MiningBankAnchor.DRAYNOR) {
            walked = BankHelper.walkToMiningBankAnchor(MiningSiteRules.draynorBankAnchor());
        } else if (anchor == MiningSiteRules.MiningBankAnchor.AL_KHARID) {
            walked = BankHelper.walkToMiningBankAnchor(MiningSiteRules.alKharidBankAnchor());
        } else {
            walked = BankHelper.walkToNearestFullBank();
        }
        if (!walked) {
            paint.setLastAntiBanAction("⚠ Geen bank gevonden!");
            return 5000;
        }
        return antiBan.varyDelay(randomDelay(2000, 3000));
    }

    private String findBestAvailablePickaxe() {
        int miningLevel = Skills.getLevel(Skill.MINING);
        for (String[] pick : reversedPickaxes()) {
            int reqLevel = Integer.parseInt(pick[1]);
            if (miningLevel >= reqLevel) {
                if (Inventory.contains(pick[0]) || Equipment.contains(pick[0])) {
                    return pick[0];
                }
                if (Bank.isOpen() && Bank.contains(pick[0])) {
                    return pick[0];
                }
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

    private int handleBanking() {
        if (shouldAbortActions()) {
            return 300;
        }
        if (!Bank.isOpen()) {
            BankHelper.tryOpenFullBank();
            return antiBan.varyDelay(randomDelay(1500, 2000));
        }

        int oreCount = Inventory.getCount(i ->
                i.getName() != null && (i.getName().toLowerCase(Locale.ROOT).contains("ore")
                        || i.getName().equalsIgnoreCase("Coal")));

        java.util.List<UniversalBankingManager.Requirement> reqs = buildMiningRequirements();
        UniversalBankingManager.BankSessionStatus status = bankingManager.runBankSession(reqs);
        if (shouldAbortActions()) {
            return 300;
        }

        debug("handleBanking: UBM result=" + status.getResult());

        if (!status.isOk()) {
            paint.setLastAntiBanAction("⚠ " + status.getRestockItemName() + " niet in bank");
        }

        sleep(300, 600);
        Bank.close();
        bankingManager.waitForBankClose();

        paint.addOreBanked(oreCount);
        if (!needsPickaxeUpgradeByLevel()) {
            pickaxeUpgradeTripPending = false;
        }
        paint.setLastAntiBanAction(status.isOk() ? "✓ Banking compleet" : "⚠ Geen pickaxe gevonden");
        return antiBan.varyDelay(randomDelay(600, 1000));
    }

    private int handleWalkingToSpot() {
        if (shouldAbortActions()) {
            return 300;
        }
        if (miningSpot == null) {
            return 1000;
        }

        IPlayer local = Players.getLocal();
        if (local == null) {
            return 1000;
        }

        long travelNow = System.currentTimeMillis();
        if (!TravelWalkHelper.reclickReady(lastTravelClickTime, config)) {
            return antiBan.varyDelay(TravelWalkHelper.shortWaitMs(random));
        }

        WorldPoint approachTile = MiningSiteRules.miningApproachTile(siteKind());
        int approachRadius = MiningSiteRules.miningApproachRadius(siteKind());
        if (approachTile != null && !isWithinMiningArea()) {
            if (!isAtLocationWithRange(approachTile, approachRadius)) {
                centerWalkTarget = approachTile;
                boolean walkIssued = MovementHelper.walkToExact(approachTile)
                        || MovementHelper.walkTowardTarget(miningSpot, areaRadius, approachTile, 25);
                if (!walkIssued) {
                    walkToSpotFailCount++;
                    paint.setCurrentStatus("⚠ Path naar AK3 stand mislukt (" + walkToSpotFailCount + ")");
                    if (walkToSpotFailCount >= 5) {
                        centerWalkTarget = null;
                        walkToSpotFailCount = 0;
                    }
                    return antiBan.varyDelay(randomDelay(1000, 1800));
                }
                walkToSpotFailCount = 0;
                lastTravelClickTime = travelNow;
                paint.setLastAntiBanAction("→ Al Kharid 3 stand (3297,3311)");
                return antiBan.varyDelay(TravelWalkHelper.postClickDelayMs(config, random));
            }
        }

        if (approachTile != null && isWithinMiningArea()) {
            walkToSpotFailCount = 0;
            centerWalkTarget = null;
            return antiBan.varyDelay(randomDelay(300, 600));
        }

        centerWalkTarget = miningWalkWaypointOrCenter(local);

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
        if (shouldAbortActions()) {
            return 300;
        }
        if (preBankPosition != null) {
            if (isAtLocation(preBankPosition)) {
                preBankPosition = null;
                return 600;
            }
            long travelNow = System.currentTimeMillis();
            if (!TravelWalkHelper.reclickReady(lastTravelClickTime, config)) {
                return antiBan.varyDelay(TravelWalkHelper.shortWaitMs(random));
            }
            boolean walked = MovementHelper.walkTo(preBankPosition);
            if (!walked) {
                debug("handleWalkingBackToSpot: walkTo preBank mislukt → direct naar mining center");
                preBankPosition = null;
                centerWalkTarget = null;
                return antiBan.varyDelay(randomDelay(400, 900));
            }
            lastTravelClickTime = travelNow;
            paint.setLastAntiBanAction("↩ Terug naar mijn spot");
            return antiBan.varyDelay(TravelWalkHelper.postClickDelayMs(config, random));
        }
        preBankPosition = null;
        return 600;
    }

    private boolean hasPickaxe() {
        for (String[] pick : MiningConfig.PICKAXE_LEVELS) {
            if (Inventory.contains(pick[0]) || Equipment.contains(pick[0])) {
                return true;
            }
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
        if (pickName == null) {
            return -1;
        }
        String[][] picks = MiningConfig.PICKAXE_LEVELS;
        for (int i = 0; i < picks.length; i++) {
            if (picks[i][0].equals(pickName)) {
                return i;
            }
        }
        return -1;
    }

    private String getBestPickaxeNameForMiningLevel() {
        int miningLevel = Skills.getLevel(Skill.MINING);
        String best = "Bronze pickaxe";
        for (String[] p : MiningConfig.PICKAXE_LEVELS) {
            int req = Integer.parseInt(p[1]);
            if (miningLevel >= req) {
                best = p[0];
            }
        }
        return best;
    }

    private boolean needsPickaxeUpgradeByLevel() {
        String current = getCurrentPickaxe();
        String bestByLevel = getBestPickaxeNameForMiningLevel();
        return getPickaxeIndex(current) < getPickaxeIndex(bestByLevel);
    }

    private boolean hasBetterPickaxeKnownInBank() {
        String bestByLevel = getBestPickaxeNameForMiningLevel();
        try {
            if (Bank.isOpen() && Bank.contains(bestByLevel)) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        IPlayer local = Players.getLocal();
        if (local == null || local.getName() == null || local.getName().trim().isEmpty()) {
            return false;
        }
        String rsn = Text.removeTags(local.getName()).trim();
        AccountStateJsonStore.AccountEntry e = AccountStateJsonStore.getEntry(rsn);
        return e != null && AccountStateJsonStore.hasKnownBankItem(e, bestByLevel);
    }

    private boolean shouldDoPickaxeUpgradeTrip() {
        if (!needsPickaxeUpgradeByLevel()) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - lastPickaxeUpgradeCheckMs < 2500L) {
            return false;
        }
        lastPickaxeUpgradeCheckMs = now;
        if (!hasBetterPickaxeKnownInBank()) {
            return false;
        }
        String current = getCurrentPickaxe();
        String bestByLevel = getBestPickaxeNameForMiningLevel();
        paint.setLastAntiBanAction("Mining upgrade: " + (current != null ? current : "geen")
                + " → " + bestByLevel + " (bank)");
        return true;
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
        int miningLevel = Skills.getLevel(Skill.MINING);
        MiningSiteRules.MiningSiteKind kind = siteKind();
        // Site-regels gaan voor: "Specifiek erts" (default vaak ijzer in config) mag tin/copper-mijnen niet breken.
        if (MiningSiteRules.siteOnlyCopperTin(kind)) {
            return MiningSiteRules.pickRockName(kind, miningLevel, random);
        }
        if (kind == MiningSiteRules.MiningSiteKind.DRAYNOR_SOUTH && miningLevel < 30) {
            return MiningSiteRules.pickRockName(kind, miningLevel, random);
        }
        if (MiningSiteRules.siteTrainsIronOre(kind) && miningLevel < 15) {
            return MiningSiteRules.pickRockName(kind, miningLevel, random);
        }
        if (config.miningUseSpecificOre()) {
            String named = config.miningOreName();
            if (named != null && !named.trim().isEmpty()) {
                return named.trim();
            }
        }
        return MiningSiteRules.pickRockName(kind, miningLevel, random);
    }

    private int randomDelay(int min, int max) {
        if (max <= min) {
            return min;
        }
        return min + random.nextInt(max - min);
    }

    private void sleep(int min, int max) {
        if (shouldAbortActions()) {
            return;
        }
        try {
            Thread.sleep(randomDelay(min, max));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Uitgepute / generieke remnant (geen erts-type in de naam) — geen target. */
    private static boolean isBareRocksLabel(String name) {
        if (name == null) {
            return false;
        }
        switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "rock":
            case "rocks":
                return true;
            default:
                return false;
        }
    }

    private static int safeGetObjectId(ITileObject obj) {
        if (obj == null) {
            return 0;
        }
        try {
            int id = obj.getId();
            return id > 0 ? id : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Of {@code current} nog de geklikte rots is (zelfde type), los van wisselende {@link #getTargetOreName()} per tick. */
    private boolean objectMatchesClickedRock(String clickedName, String currentName) {
        if (isBareRocksLabel(currentName)) {
            return false;
        }
        if (clickedName == null || clickedName.isEmpty() || currentName == null || currentName.isEmpty()) {
            return false;
        }
        return clickedName.equalsIgnoreCase(currentName)
                || matchesRockName(currentName, clickedName)
                || matchesRockName(clickedName, currentName);
    }

    private boolean matchesRockName(String objName, String oreRockFragment) {
        if (objName == null || oreRockFragment == null) {
            return false;
        }
        if (isBareRocksLabel(objName)) {
            return false;
        }
        String o = objName.toLowerCase(Locale.ROOT);
        String f = oreRockFragment.toLowerCase(Locale.ROOT);
        if (o.contains(f) || f.contains(o)) {
            return true;
        }
        String oNorm = o.endsWith(" rocks") ? (o.substring(0, o.length() - 1).trim()) : o;
        String fNorm = f.endsWith(" rocks") ? (f.substring(0, f.length() - 1).trim()) : f;
        return oNorm.contains(fNorm) || fNorm.contains(oNorm);
    }

    /** Exacte tegel (niet ±1): anders slaan we na 1 mine 8 buurrots onterecht over. */
    private static boolean exactRockTile(WorldPoint a, WorldPoint b) {
        if (a == null || b == null) {
            return false;
        }
        return a.getPlane() == b.getPlane() && a.getX() == b.getX() && a.getY() == b.getY();
    }

    private boolean rockStillMineableAt(WorldPoint tile, String oreRockFragment) {
        return findMineableRockObjectAt(tile, oreRockFragment) != null;
    }

    /**
     * Zoek minebare rots op tegel — alleen op <b>naam</b>, niet object-id (tin/copper hebben meerdere ids).
     */
    private ITileObject findMineableRockObjectAt(WorldPoint tile, String oreRockFragment) {
        if (tile == null) {
            return null;
        }
        try {
            for (ITileObject obj : TileObjects.getAll(o -> o != null && o.getName() != null)) {
                WorldPoint wp = obj.getWorldLocation();
                if (wp == null || !exactRockTile(wp, tile)) {
                    continue;
                }
                if (isBareRocksLabel(obj.getName()) || !obj.hasAction("Mine")) {
                    continue;
                }
                String n = obj.getName();
                boolean nameOk = activeRockClickedName != null && !activeRockClickedName.isEmpty()
                        ? objectMatchesClickedRock(activeRockClickedName, n)
                        : matchesRockName(n, oreRockFragment);
                if (nameOk) {
                    return obj;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Op mijnen waar meerdere rotstypes tegelijk mogen (tin/copper, Draynor coal+mithril),
     * niet elke tick willekeurig een type uit {@link MiningSiteRules#pickRockName} gebruiken maar
     * degene pakken die het dichtst bij de speler staat — minder zigzag en echte nearest-first.
     */
    private ITileObject pickNearestMineableRockForSite(WorldPoint avoidTile, boolean preferUnoccupied) {
        MiningSiteRules.MiningSiteKind k = siteKind();
        int miningLevel = Skills.getLevel(Skill.MINING);
        if (MiningSiteRules.siteOnlyCopperTin(k)) {
            return findNearestMineableRockAnyOf(avoidTile, preferUnoccupied, "Copper rocks", "Tin rocks");
        }
        if (k == MiningSiteRules.MiningSiteKind.DRAYNOR_SOUTH && miningLevel >= 55) {
            return findNearestMineableRockAnyOf(avoidTile, preferUnoccupied, "Coal rocks", "Mithril rocks");
        }
        return findNearestMineableRock(getTargetOreName(), preferUnoccupied, avoidTile);
    }

    /** Eén pass: dichtstbijzijnde rots over alle typen (ids mogen verschillen, zelfde naam). */
    private ITileObject findNearestMineableRockAnyOf(WorldPoint avoidTile, boolean preferUnoccupied,
            String... oreRockFragments) {
        if (oreRockFragments == null || oreRockFragments.length == 0) {
            return null;
        }
        IPlayer local = Players.getLocal();
        if (local == null || local.getWorldLocation() == null) {
            return null;
        }
        WorldPoint me = local.getWorldLocation();
        ITileObject best = null;
        int bestDist = Integer.MAX_VALUE;
        try {
            for (ITileObject obj : TileObjects.getAll(o -> o != null && o.getName() != null)) {
                if (isBareRocksLabel(obj.getName()) || !obj.hasAction("Mine")) {
                    continue;
                }
                boolean nameMatch = false;
                for (String frag : oreRockFragments) {
                    if (frag != null && matchesRockName(obj.getName(), frag)) {
                        nameMatch = true;
                        break;
                    }
                }
                if (!nameMatch) {
                    continue;
                }
                WorldPoint wp = obj.getWorldLocation();
                if (wp == null || !isWithinArea(wp) || isTileExcluded(wp)) {
                    continue;
                }
                if (avoidTile != null && exactRockTile(wp, avoidTile)) {
                    continue;
                }
                if (preferUnoccupied && otherPlayerOnTile(wp)) {
                    continue;
                }
                int d = wp.distanceTo(me);
                boolean better = best == null
                        || d < bestDist
                        || (d == bestDist && compareRockTiles(wp, best.getWorldLocation()) < 0);
                if (better) {
                    bestDist = d;
                    best = obj;
                }
            }
        } catch (Throwable ignored) {
        }
        if (best == null && avoidTile != null) {
            return findNearestMineableRockAnyOf(null, preferUnoccupied, oreRockFragments);
        }
        return best;
    }

    private ITileObject pickNearerMineableRock(ITileObject a, ITileObject b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        IPlayer local = Players.getLocal();
        WorldPoint me = local != null ? local.getWorldLocation() : null;
        WorldPoint wa = a.getWorldLocation();
        WorldPoint wb = b.getWorldLocation();
        if (me == null) {
            return a;
        }
        if (wa == null) {
            return b;
        }
        if (wb == null) {
            return a;
        }
        int da = wa.distanceTo(me);
        int db = wb.distanceTo(me);
        if (da < db) {
            return a;
        }
        if (db < da) {
            return b;
        }
        if (wa.getX() != wb.getX()) {
            return wa.getX() < wb.getX() ? a : b;
        }
        return wa.getY() <= wb.getY() ? a : b;
    }

    /** Looppunt binnen mining-area: dichstbijzijnde mijnbare rots; AK3 nooit naar center op heuvel. */
    private WorldPoint miningWalkWaypointOrCenter(IPlayer local) {
        if (miningSpot == null || local == null) {
            return miningSpot;
        }
        WorldPoint approachTile = MiningSiteRules.miningApproachTile(siteKind());
        ITileObject rock = pickNearestMineableRockForSite(null, false);
        if (rock != null && rock.getWorldLocation() != null && isWithinArea(rock.getWorldLocation())) {
            return rock.getWorldLocation();
        }
        if (approachTile != null && !isWithinMiningArea()) {
            return approachTile;
        }
        return miningSpot;
    }

    /** Stabiele keuze bij twee rotsen op gelijke grid-afstand. */
    private static int compareRockTiles(WorldPoint a, WorldPoint b) {
        if (a == null || b == null) {
            return 0;
        }
        int cx = Integer.compare(a.getX(), b.getX());
        if (cx != 0) {
            return cx;
        }
        return Integer.compare(a.getY(), b.getY());
    }

    /**
     * Dichtstbijzijnde minebare rots t.o.v. {@link IPlayer#getWorldLocation() spelerpositie}
     * (grid-afstand via {@link WorldPoint#distanceTo}); gefilterd op {@code oreRockFragment}, area en exclude-tiles.
     * {@code preferUnoccupied} false = negeer spelers op tile (ijzer-fallback).
     */
    private ITileObject findNearestMineableRock(String oreRockFragment, boolean preferUnoccupied) {
        return findNearestMineableRock(oreRockFragment, preferUnoccupied, null);
    }

    private ITileObject findNearestMineableRock(String oreRockFragment, boolean preferUnoccupied,
            WorldPoint avoidTile) {
        IPlayer local = Players.getLocal();
        if (local == null || local.getWorldLocation() == null) {
            return null;
        }
        WorldPoint me = local.getWorldLocation();
        ITileObject best = null;
        int bestDist = Integer.MAX_VALUE;
        try {
            for (ITileObject obj : TileObjects.getAll(o -> o != null && o.getName() != null)) {
                if (isBareRocksLabel(obj.getName()) || !matchesRockName(obj.getName(), oreRockFragment)
                        || !obj.hasAction("Mine")) {
                    continue;
                }
                WorldPoint wp = obj.getWorldLocation();
                if (wp == null || !isWithinArea(wp) || isTileExcluded(wp)) {
                    continue;
                }
                if (avoidTile != null && exactRockTile(wp, avoidTile)) {
                    continue;
                }
                if (preferUnoccupied && otherPlayerOnTile(wp)) {
                    continue;
                }
                int d = wp.distanceTo(me);
                boolean better = best == null
                        || d < bestDist
                        || (d == bestDist && compareRockTiles(wp, best.getWorldLocation()) < 0);
                if (better) {
                    bestDist = d;
                    best = obj;
                }
            }
        } catch (Throwable ignored) {
        }
        if (best == null && avoidTile != null) {
            return findNearestMineableRock(oreRockFragment, preferUnoccupied, null);
        }
        return best;
    }

    private boolean shouldAbortActions() {
        return config == null || !config.botEnabled();
    }
}
