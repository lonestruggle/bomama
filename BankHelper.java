package com.combatbot;

import net.runelite.api.coords.WorldPoint;
import net.storm.api.domain.actors.INPC;
import net.storm.api.domain.actors.IPlayer;
import net.storm.api.domain.tiles.ITileObject;
import net.storm.api.movement.pathfinder.model.BankLocation;
import net.storm.sdk.entities.NPCs;
import net.storm.sdk.entities.Players;
import net.storm.sdk.entities.TileObjects;
import net.storm.sdk.items.Bank;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * BankHelper - Gedeelde banking utility voor alle handlers.
 *
 * Biedt:
 * - F2P-veilige bankkeuze: Cooking Guild en Crafting Guild (P2P) worden uitgesloten
 * - Dynamisch dichtstbijzijnde bank (object/NPC of fallback lijst F2P banken)
 * - Onderscheid tussen volwaardige banken en deposit boxes
 * - Strikte regel: deposit boxes ALLEEN voor pure loot dumps, NOOIT voor gear/tool withdrawal
 * - Unified bank open logica (booth → NPC banker → Bank.open() fallback)
 */
public class BankHelper {

    private static void debug(String msg) {
        // Log naar shared DebugLog zodat het in de Combat debug tab zichtbaar is
        DebugLog.log("BankHelper", msg);
        // Extra fallback naar console voor het geval debug uit staat
        System.out.println("[BankHelper] " + msg);
    }

    private static long nextBankOpenAttemptAllowedMs = 0;

    /** Reset static bank-open cooldown bij login/account-switch/fresh start. */
    public static void resetOpenCooldown() {
        nextBankOpenAttemptAllowedMs = 0;
    }

    /** Gebieden die we niet als bank gebruiken: Cooking Guild (F2P maar user wil niet), Crafting Guild (P2P). */
    // Cooking Guild staat rond 3143,3443
    private static final WorldPoint COOKING_GUILD_CENTER = new WorldPoint(3143, 3443, 0);
    private static final WorldPoint CRAFTING_GUILD_CENTER = new WorldPoint(2934, 3282, 0);
    private static final int AVOID_BANK_RADIUS = 20;

    /** Lumbridge Castle center — voor detectie of nearest bank Lumbridge is. */
    private static final WorldPoint LUMBRIDGE_CASTLE_CENTER = new WorldPoint(3208, 3220, 0);
    private static final int LUMBRIDGE_DETECT_RADIUS = 40;

    /** F2P banklocaties (geen Cooking Guild, geen Crafting Guild) — fallback als geen booth/banker in wereld. */
    private static final List<WorldPoint> F2P_BANK_POINTS = Arrays.asList(
            new WorldPoint(3206, 3208, 0),   // Lumbridge Castle (trap tile — bot gaat eerst trap op)
            new WorldPoint(3253, 3422, 0),   // Varrock East
            new WorldPoint(3189, 3436, 0),   // Varrock West
            new WorldPoint(3164, 3486, 0),   // Grand Exchange
            new WorldPoint(3092, 3243, 0),   // Draynor
            new WorldPoint(3096, 3492, 0),  // Edgeville
            new WorldPoint(3013, 3355, 0),   // Falador East
            new WorldPoint(2946, 3368, 0),   // Falador West
            new WorldPoint(3269, 3167, 0)    // Al Kharid
    );

    /**
     * Lumbridge kasteel trappen:
     * Als we in het kasteel staan op plane 0 of 1, probeer dan de "Staircase" met "Climb-up" te gebruiken
     * zodat we naar de bankverdieping gaan in plaats van vast te lopen beneden.
     */
    /** Bekende locatie van de trap in Lumbridge Castle (begane grond). */
    private static final WorldPoint LUMBRIDGE_STAIRS_TILE = new WorldPoint(3206, 3208, 0);
    private static final WorldPoint LUMBRIDGE_STAIRS_TILE_F1 = new WorldPoint(3206, 3208, 1);

    public static boolean handleLumbridgeStairs() {
        IPlayer local = Players.getLocal();
        if (local == null) return false;
        WorldPoint myPos = local.getWorldLocation();
        if (myPos == null) return false;

        // Ruime bounding box rond Lumbridge Castle — detecteer vroeg zodat bot direct naar trap gaat
        int x = myPos.getX();
        int y = myPos.getY();
        if (x > 3195 && x < 3220 && y > 3195 && y < 3235) {
            int plane = myPos.getPlane();
            if (plane < 2) {
                if (MovementHelper.lumbridgeStairAscendInProgress(myPos)) {
                    return true;
                }
                if (!MovementHelper.lumbridgeStairsMayInteractNow()) {
                    return false;
                }
                // Zoek trap
                ITileObject stairs = TileObjects.getNearest(obj ->
                        obj != null
                                && obj.getName() != null
                                && obj.getName().equalsIgnoreCase("Staircase")
                                && (obj.hasAction("Top-floor") || obj.hasAction("Climb-up"))
                );
                if (stairs != null) {
                    int distToStairs = myPos.distanceTo(stairs.getWorldLocation());
                    if (distToStairs > 3) {
                        // Te ver van de trap — loop er eerst naartoe
                        debug("handleLumbridgeStairs: te ver van trap (dist=" + distToStairs + "), loop naar trap");
                        MovementHelper.walkTo(stairs.getWorldLocation());
                        return true;
                    }
                    String action = stairs.hasAction("Top-floor") ? "Top-floor" : "Climb-up";
                    debug("handleLumbridgeStairs: plane=" + plane + " action=" + action);
                    stairs.interact(action);
                    MovementHelper.lumbridgeMarkAscendInteract(plane);
                    MovementHelper.lumbridgeStairsRegisterInteract();
                    return true;
                } else {
                    // Trap niet zichtbaar (te ver weg) — loop naar bekende traptile
                    WorldPoint target = plane == 0 ? LUMBRIDGE_STAIRS_TILE : LUMBRIDGE_STAIRS_TILE_F1;
                    debug("handleLumbridgeStairs: trap niet gevonden, loop naar " + target);
                    MovementHelper.walkTo(target);
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isInExcludedBankArea(WorldPoint p) {
        if (p == null) return true;
        return p.distanceTo(COOKING_GUILD_CENTER) <= AVOID_BANK_RADIUS
                || p.distanceTo(CRAFTING_GUILD_CENTER) <= AVOID_BANK_RADIUS;
    }

    /** Dichtstbijzijnde F2P bankpunt (voor fallback loop). */
    public static WorldPoint getNearestF2pBankPoint(WorldPoint from) {
        if (from == null) return null;
        WorldPoint best = null;
        int bestDist = Integer.MAX_VALUE;
        for (WorldPoint w : F2P_BANK_POINTS) {
            int d = from.distanceTo(w);
            if (d < bestDist) {
                bestDist = d;
                best = w;
            }
        }
        return best;
    }

    /**
     * Check of een bank booth/chest/NPC in de buurt is (binnen 5 tiles).
     * Negeert deposit boxes.
     */
    public static boolean isNearFullBank() {
        IPlayer local = Players.getLocal();
        if (local == null) return false;
        WorldPoint myPos = local.getWorldLocation();

        ITileObject booth = findNearestFullBankObject();
        if (booth != null && myPos.distanceTo(booth.getWorldLocation()) <= 5) return true;

        INPC banker = findNearestBankerNpc();
        if (banker != null && myPos.distanceTo(banker.getWorldLocation()) <= 5) return true;

        return false;
    }

    /**
     * Check of een punt dicht bij een bekende bank is.
     * Gebruikt BankLocation.getNearest() voor dynamische check.
     */
    public static boolean isNearAnyBank(WorldPoint point) {
        if (point == null) return false;
        // Check tegen bekende F2P bank punten
        for (WorldPoint bankPoint : F2P_BANK_POINTS) {
            if (point.distanceTo(bankPoint) <= 8) return true;
        }
        // Check tegen dynamische BankLocation
        BankLocation nearest = BankLocation.getNearest();
        if (nearest == null) return false;
        try {
            WorldPoint bankPos = nearest.getArea().toWorldPoint();
            return bankPos != null && point.distanceTo(bankPos) <= 8;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Zoek het dichtstbijzijnde volwaardige bank object (booth/chest/counter).
     * Negeert deposit boxes. Sluit Cooking Guild en Crafting Guild uit (F2P-veilig).
     */
    public static ITileObject findNearestFullBankObject() {
        return TileObjects.getNearest(obj -> {
            if (obj == null || obj.getName() == null) return false;
            if (isInExcludedBankArea(obj.getWorldLocation())) return false;
            String name = obj.getName().toLowerCase();
            if (name.contains("deposit")) return false;
            return (name.contains("bank booth") || name.contains("bank chest")
                    || name.contains("bank counter") || name.equals("bank"))
                    && (obj.hasAction("Bank") || obj.hasAction("Use"));
        });
    }

    /**
     * Zoek de dichtstbijzijnde banker NPC (F2P-veilig: geen Cooking/Crafting Guild).
     */
    public static INPC findNearestBankerNpc() {
        return NPCs.getNearest(npc -> {
            if (npc == null || npc.getName() == null) return false;
            if (isInExcludedBankArea(npc.getWorldLocation())) return false;
            return npc.getName().toLowerCase().contains("banker")
                    && npc.hasAction("Bank");
        });
    }

    private static final int BANK_OPEN_RANGE = 5;
    /** Booth/banker zichtbaar maar te ver om te klikken: loop eerst naartoe (chebyshev / RL distance). */
    private static final int BANK_APPROACH_MAX = 28;
    /** Nabij F2P-ankerpunt: {@link Bank#open()} fallback (tiles). Iets ruim dan exact 6 om GE/Varrock-rand te dekken. */
    private static final int F2P_BANK_OPEN_ANCHOR_RANGE = 12;
    /** Standaard wachttijd op {@link Bank#isOpen()} na interact / {@link Bank#open()}; bij timeout failure-nudge. */
    public static final int DEFAULT_BANK_INTERFACE_WAIT_MS = 4000;

    private enum NearbyBankStep {
        /** Geen interact, geen walk — caller kan zelf naar bank routeren. */
        NONE,
        /** Naar booth/bankier gelopen — nog geen klik; geen wacht op UI. */
        WALKING,
        /** Klik gedaan — wacht op {@link Bank#isOpen()} indien gewenst. */
        CLICKED
    }

    /**
     * @return {@code true} als de bank al open is; na booth/banker-klik tot {@value #DEFAULT_BANK_INTERFACE_WAIT_MS} ms
     *         wachten op {@link Bank#isOpen()} (bij timeout: failure-nudge, {@code false}).
     *         Bij interactie-cooldown + dicht bij booth/banker: {@code true} (geen dubbele klik; caller blijft pollen).
     *         {@code false} als we nog moeten lopen of geen interact mogelijk is.
     */
    public static boolean tryOpenFullBank() {
        if (Bank.isOpen()) {
            return true;
        }

        IPlayer local = Players.getLocal();
        WorldPoint myPos = local != null ? local.getWorldLocation() : null;
        long now = System.currentTimeMillis();

        ITileObject booth = findNearestFullBankObject();
        INPC banker = findNearestBankerNpc();
        boolean nearBooth = myPos != null && booth != null
                && myPos.distanceTo(booth.getWorldLocation()) <= BANK_OPEN_RANGE;
        boolean nearBanker = myPos != null && banker != null
                && myPos.distanceTo(banker.getWorldLocation()) <= BANK_OPEN_RANGE;

        if (now < nextBankOpenAttemptAllowedMs) {
            if (nearBooth || nearBanker) {
                return true;
            }
            return false;
        }

        if (!nearBooth && !nearBanker) {
            if (tryWalkTowardVisibleBank(myPos)) {
                return true;
            }
            return false;
        }

        HumanBanking.pauseBeforeBankOpenClick();
        nextBankOpenAttemptAllowedMs = now + 5500 + ThreadLocalRandom.current().nextInt(2501);

        if (nearBooth) {
            String action = booth.hasAction("Bank") ? "Bank" : "Use";
            booth.interact(action);
            return waitUntilBankOpenVerified(DEFAULT_BANK_INTERFACE_WAIT_MS, "tryOpenFullBank booth");
        }
        banker.interact("Bank");
        return waitUntilBankOpenVerified(DEFAULT_BANK_INTERFACE_WAIT_MS, "tryOpenFullBank banker");
    }

    private static boolean waitUntilBankOpenVerified(int timeoutMs, String logContext) {
        if (Bank.isOpen()) {
            return true;
        }
        return CombatBotRuntime.waitWithFailCheck(Bank::isOpen, timeoutMs, logContext);
    }

    /**
     * Storm {@link Bank#open()} (void) + wacht op interface; nudge bij timeout.
     * Alleen nuttig als je al binnen SDK-afstand van een bank bent.
     */
    public static boolean openSdkBankAndWait(int timeoutMs) {
        if (Bank.isOpen()) {
            return true;
        }
        try {
            Bank.open();
        } catch (Throwable ignored) {
        }
        return waitUntilBankOpenVerified(timeoutMs, "Bank.open SDK");
    }

    /** Zie {@link #openSdkBankAndWait(int)} met {@value #DEFAULT_BANK_INTERFACE_WAIT_MS} ms. */
    public static boolean openSdkBankAndWait() {
        return openSdkBankAndWait(DEFAULT_BANK_INTERFACE_WAIT_MS);
    }

    /**
     * Begane grond / verdieping 1: loop naar Lumbridge Castle trap en omhoog (zelfde logica als
     * {@link #walkToNearestFullBank()} bij Lumbridge-detectie).
     *
     * @return {@code true} als een stap is gestart, {@code false} om door te vallen naar algemene bank-zoeklogica
     */
    private static boolean walkLumbridgeCastleStairsPathFromGround(WorldPoint myPos) {
        if (myPos == null || myPos.getPlane() >= 2) {
            return false;
        }
        if (MovementHelper.tryClickLumbridgeStairsFromDistance(myPos)) {
            debug("walkLumbridgeCastle: trap geklikt vanuit de verte!");
            return true;
        }
        int x = myPos.getX();
        int y = myPos.getY();
        boolean inCastle = (x > 3195 && x < 3220 && y > 3195 && y < 3235);
        if (inCastle && MovementHelper.lumbridgeStairAscendInProgress(myPos)) {
            return true;
        }
        if (!inCastle) {
            debug("walkLumbridgeCastle: plane=" + myPos.getPlane() + ", NIET in kasteel → trap tile");
            MovementHelper.walkTo(LUMBRIDGE_STAIRS_TILE);
            return true;
        }
        return handleLumbridgeStairs();
    }

    /**
     * Alleen Lumbridge Castle-bank (trap + boven). Gebruikt <b>geen</b> Draynor/Falador/GE fallback.
     * Voor Starter-modus “Lumbridge schaapskooi”: vanaf elke plek wordt eerst naar de kasteeltrap gelopen.
     *
     * @return true als een walk/interact is gestart
     */
    public static boolean walkToLumbridgeCastleBank() {
        IPlayer local = Players.getLocal();
        WorldPoint myPos = local != null ? local.getWorldLocation() : null;
        if (myPos == null) {
            return false;
        }

        if (myPos.getPlane() >= 2) {
            if (tryOpenFullBank()) {
                return true;
            }
            ITileObject booth = findNearestFullBankObject();
            if (booth != null && myPos.distanceTo(booth.getWorldLocation()) > BANK_OPEN_RANGE) {
                try {
                    if (MovementHelper.walkTo(booth.getWorldLocation())) {
                        return true;
                    }
                } catch (Throwable ignored) { }
            }
            INPC banker = findNearestBankerNpc();
            if (banker != null && myPos.distanceTo(banker.getWorldLocation()) > BANK_OPEN_RANGE) {
                try {
                    if (MovementHelper.walkTo(banker.getWorldLocation())) {
                        return true;
                    }
                } catch (Throwable ignored) { }
            }
            debug("walkToLumbridgeCastleBank: plane>=2 — naar vaste trap bankverdieping");
            WorldPoint stairGoal = myPos.getPlane() == 2
                    ? MovementHelper.LUMBRIDGE_BANK_FLOOR_STAIRS_TILE
                    : new WorldPoint(3206, 3208, myPos.getPlane());
            MovementHelper.walkTo(stairGoal);
            return true;
        }

        if (!walkLumbridgeCastleStairsPathFromGround(myPos)) {
            debug("walkToLumbridgeCastleBank: fallback trap tile");
            MovementHelper.walkTo(LUMBRIDGE_STAIRS_TILE);
        }
        return true;
    }

    /**
     * Loop naar de dichtstbijzijnde volwaardige bank.
     * F2P-veilig: Cooking Guild en Crafting Guild worden niet gebruikt.
     * Zoekt eerst booth/banker in wereld; anders fallback naar dichtstbijzijnde F2P bank uit lijst.
     *
     * @return true als walkTo succesvol is gestart, false als geen bank gevonden
     */
    public static boolean walkToNearestFullBank() {
        IPlayer local = Players.getLocal();
        WorldPoint myPos = local != null ? local.getWorldLocation() : null;

        // ============================================================
        // LUMBRIDGE SPECIALE CASE — ALTIJD EERST TRAP OP!
        // Als we in de buurt van Lumbridge Castle zijn EN op plane 0 of 1,
        // MOET de bot eerst naar de trap lopen en omhoog gaan.
        // Dit voorkomt dat de bot naar de banklocatie op de begane grond loopt
        // waar een deur de trap blokkeert.
        // ============================================================
        if (myPos != null && myPos.getPlane() < 2) {
            // Check of we in de buurt van Lumbridge zijn
            boolean nearLumbridge = myPos.distanceTo(LUMBRIDGE_CASTLE_CENTER) <= LUMBRIDGE_DETECT_RADIUS;

            // Check ook of de nearest F2P bank Lumbridge is (voor als we ver weg staan maar Lumbridge dichtst bij is)
            if (!nearLumbridge) {
                WorldPoint nearestF2p = getNearestF2pBankPoint(myPos);
                if (nearestF2p != null && nearestF2p.equals(F2P_BANK_POINTS.get(0))) {
                    // Nearest F2P bank is Lumbridge
                    nearLumbridge = true;
                }
            }

            if (nearLumbridge && walkLumbridgeCastleStairsPathFromGround(myPos)) {
                return true;
            }
        }

        // 1. Bank object in wereld (excl. Cooking/Crafting Guild)
        ITileObject booth = findNearestFullBankObject();
        if (booth != null) {
            if (myPos != null && myPos.distanceTo(booth.getWorldLocation()) <= BANK_OPEN_RANGE) {
                if (tryOpenFullBank()) {
                    return true;
                }
            }
            try {
                if (MovementHelper.walkTo(booth.getWorldLocation())) return true;
            } catch (Throwable ignored) { }
            int dBooth = myPos != null ? myPos.distanceTo(booth.getWorldLocation()) : 0;
            if (myPos != null && dBooth > BANK_OPEN_RANGE && dBooth <= BANK_APPROACH_MAX) {
                int step = Math.min(18, Math.max(6, dBooth - 4));
                try {
                    if (MovementHelper.walkTowardTarget(booth.getWorldLocation(), step)) {
                        debug("walkToNearestFullBank: walkTowardTarget booth step=" + step);
                        return true;
                    }
                } catch (Throwable ignored) { }
            }
        }

        // 2. Banker NPC (excl. Cooking/Crafting Guild)
        INPC banker = findNearestBankerNpc();
        if (banker != null) {
            if (myPos != null && myPos.distanceTo(banker.getWorldLocation()) <= BANK_OPEN_RANGE) {
                if (tryOpenFullBank()) {
                    return true;
                }
            }
            try {
                if (MovementHelper.walkTo(banker.getWorldLocation())) return true;
            } catch (Throwable ignored) { }
            int dBanker = myPos != null ? myPos.distanceTo(banker.getWorldLocation()) : 0;
            if (myPos != null && dBanker > BANK_OPEN_RANGE && dBanker <= BANK_APPROACH_MAX) {
                int step = Math.min(18, Math.max(6, dBanker - 4));
                try {
                    if (MovementHelper.walkTowardTarget(banker.getWorldLocation(), step)) {
                        debug("walkToNearestFullBank: walkTowardTarget banker step=" + step);
                        return true;
                    }
                } catch (Throwable ignored) { }
            }
        }

        // 3. Fallback: dichtstbijzijnde F2P bank uit vaste lijst (geen Cooking/Crafting Guild)
        WorldPoint f2p = getNearestF2pBankPoint(myPos);
        if (f2p != null) {
            try {
                if (MovementHelper.walkTo(f2p)) return true;
            } catch (Throwable ignored) { }
            try {
                if (MovementHelper.walkToExact(f2p)) return true;
            } catch (Throwable ignored) { }
        }

        // 4. Laatste fallback: SDK BankLocation — alleen als het géén Cooking/Crafting Guild is
        try {
            BankLocation nearest = BankLocation.getNearestPath(true);
            if (nearest == null) {
                nearest = BankLocation.getNearest();
            }
            if (nearest != null) {
                WorldPoint bp = nearest.getArea().toWorldPoint();
                if (bp != null && !isInExcludedBankArea(bp)) {
                    if (MovementHelper.walkTo(bp)) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        debug("walkToNearestFullBank: geen pad (Cooking/Crafting Guild of SDK-locatie uitgesloten)");
        return false;
    }

    /**
     * Loop naar de dichtstbijzijnde bank — inclusief deposit boxes als allowDepositBox=true.
     * Gebruik allowDepositBox=true ALLEEN als de bot uitsluitend loot wil dumpen.
     *
     * @param allowDepositBox true als deposit boxes ook acceptabel zijn
     * @return true als walkTo gestart, false als geen bank gevonden
     */
    public static boolean walkToNearestBank(boolean allowDepositBox) {
        if (!allowDepositBox) {
            return walkToNearestFullBank();
        }

        // Als deposit boxes OK zijn, gebruik gewoon BankLocation.getNearest()
        BankLocation nearest = BankLocation.getNearestPath(true);
        if (nearest == null) nearest = BankLocation.getNearest();
        if (nearest == null) return false;
        return MovementHelper.walkTo(nearest);
    }

    /**
     * Interacteer met de dichtstbijzijnde bank en wacht tot de interface open is (timeout + failure-nudge).
     * {@code maxWaitForOpenMs == 0}: oude gedrag — alleen klik starten, geen wacht (zeldzaam).
     */
    public static boolean interactIfNearby() {
        return interactIfNearby(DEFAULT_BANK_INTERFACE_WAIT_MS);
    }

    /**
     * @param maxWaitForOpenMs &gt; 0: poll {@link Bank#isOpen()} tot timeout (dan nudge); 0: alleen interact starten.
     * @return true als bank open is (al was of na wachten); false bij geen interact / cooldown / timeout.
     */
    public static boolean interactIfNearby(int maxWaitForOpenMs) {
        if (Bank.isOpen()) {
            return true;
        }
        NearbyBankStep step = attemptNearbyBankInteract();
        if (step == NearbyBankStep.NONE) {
            return false;
        }
        if (step == NearbyBankStep.WALKING) {
            return true;
        }
        if (maxWaitForOpenMs <= 0) {
            return true;
        }
        return waitUntilBankOpenVerified(maxWaitForOpenMs, "interactIfNearby bank UI");
    }

    /**
     * Loop naar dichtere booth/bankier als die in zicht is maar buiten klikbereik.
     * Moet alleen {@code true} teruggeven als er daadwerkelijk een walk is gestart — anders denkt
     * {@link #interactIfNearby()} dat er voortgang is (Imps blijft wachten terwijl {@code moving=false}).
     */
    private static boolean tryWalkTowardVisibleBank(WorldPoint myPos) {
        if (myPos == null) {
            return false;
        }
        ITileObject booth = findNearestFullBankObject();
        INPC banker = findNearestBankerNpc();
        int dB = booth != null ? myPos.distanceTo(booth.getWorldLocation()) : Integer.MAX_VALUE;
        int dN = banker != null ? myPos.distanceTo(banker.getWorldLocation()) : Integer.MAX_VALUE;
        boolean bWalk = dB > BANK_OPEN_RANGE && dB <= BANK_APPROACH_MAX;
        boolean nWalk = dN > BANK_OPEN_RANGE && dN <= BANK_APPROACH_MAX;
        if (!bWalk && !nWalk) {
            return false;
        }

        boolean preferBanker = nWalk && (!bWalk || dN < dB);
        WorldPoint primary = preferBanker ? banker.getWorldLocation() : booth.getWorldLocation();
        String which = preferBanker ? "banker" : "booth";
        int dPrimary = preferBanker ? dN : dB;

        if (MovementHelper.walkTo(primary)) {
            debug("tryWalkTowardVisibleBank: loop naar " + which + " (dist=" + dPrimary + ")");
            return true;
        }
        debug("tryWalkTowardVisibleBank: walkTo " + which + " mislukt (dist=" + dPrimary + ") — fallbacks");

        WorldPoint f2p = getNearestF2pBankPoint(myPos);
        if (f2p != null && myPos.distanceTo(f2p) > BANK_OPEN_RANGE) {
            if (MovementHelper.walkTo(f2p)) {
                debug("tryWalkTowardVisibleBank: fallback walk naar F2P anker "
                        + f2p.getX() + "," + f2p.getY());
                return true;
            }
            if (MovementHelper.walkToExact(f2p)) {
                debug("tryWalkTowardVisibleBank: fallback walkToExact F2P anker");
                return true;
            }
        }

        int maxStep = Math.min(18, Math.max(6, dPrimary - 4));
        if (MovementHelper.walkTowardTarget(primary, maxStep)) {
            debug("tryWalkTowardVisibleBank: walkTowardTarget maxStep=" + maxStep);
            return true;
        }
        debug("tryWalkTowardVisibleBank: alle aanloop-pogingen mislukt");
        return false;
    }

    /**
     * Booth/banker klik, aanloop-lopen, of F2P {@link Bank#open()} fallback.
     */
    private static NearbyBankStep attemptNearbyBankInteract() {
        IPlayer local = Players.getLocal();
        if (local == null) {
            return NearbyBankStep.NONE;
        }
        WorldPoint myPos = local.getWorldLocation();
        if (myPos != null) {
            debug("interactIfNearby: myPos=" + myPos.getX() + "," + myPos.getY() + "," + myPos.getPlane());
        }

        ITileObject booth = findNearestFullBankObject();
        INPC banker = findNearestBankerNpc();
        int dB = booth != null && myPos != null ? myPos.distanceTo(booth.getWorldLocation()) : Integer.MAX_VALUE;
        int dN = banker != null && myPos != null ? myPos.distanceTo(banker.getWorldLocation()) : Integer.MAX_VALUE;

        if (booth != null) {
            debug("interactIfNearby: found booth '" + booth.getName() + "' dist=" + dB);
        }
        if (banker != null) {
            debug("interactIfNearby: found banker '" + banker.getName() + "' dist=" + dN);
        }

        long now = System.currentTimeMillis();
        boolean cooldown = now < nextBankOpenAttemptAllowedMs;

        if (booth != null && dB <= BANK_OPEN_RANGE) {
            if (!cooldown) {
                HumanBanking.pauseBeforeBankOpenClick();
                nextBankOpenAttemptAllowedMs = now + 5500 + ThreadLocalRandom.current().nextInt(2501);
                String action = booth.hasAction("Bank") ? "Bank" : "Use";
                debug("interactIfNearby: klik booth action=" + action);
                booth.interact(action);
                return NearbyBankStep.CLICKED;
            }
            // Geen tweede target proberen: vorige klik kan UI nog openen — anders FALSE → Imps GE-failsafe loop.
            debug("interactIfNearby: booth in range, cooldown na vorige open-klik — wacht (geen dubbele klik)");
            return NearbyBankStep.WALKING;
        }

        if (banker != null && dN <= BANK_OPEN_RANGE) {
            if (!cooldown) {
                HumanBanking.pauseBeforeBankOpenClick();
                nextBankOpenAttemptAllowedMs = now + 5500 + ThreadLocalRandom.current().nextInt(2501);
                debug("interactIfNearby: klik banker");
                banker.interact("Bank");
                return NearbyBankStep.CLICKED;
            }
            debug("interactIfNearby: banker in range, cooldown na vorige open-klik — wacht (geen dubbele klik)");
            return NearbyBankStep.WALKING;
        }

        if (tryWalkTowardVisibleBank(myPos)) {
            return NearbyBankStep.WALKING;
        }

        if (myPos != null) {
            WorldPoint nearestF2p = getNearestF2pBankPoint(myPos);
            if (nearestF2p != null) {
                int distF2p = myPos.distanceTo(nearestF2p);
                debug("interactIfNearby: F2P ankerpunt=" +
                        nearestF2p.getX() + "," + nearestF2p.getY() + " dist=" + distF2p);
                if (distF2p <= F2P_BANK_OPEN_ANCHOR_RANGE) {
                    if (cooldown) {
                        debug("interactIfNearby: cooldown — wacht (F2P fallback)");
                        return NearbyBankStep.NONE;
                    }
                    HumanBanking.pauseBeforeBankOpenClick();
                    nextBankOpenAttemptAllowedMs = now + 5500 + ThreadLocalRandom.current().nextInt(2501);
                    debug("interactIfNearby: fallback Bank.open() nabij F2P anker");
                    try {
                        Bank.open();
                    } catch (Throwable ignored) {
                    }
                    return NearbyBankStep.CLICKED;
                }
            }
        }

        debug("interactIfNearby: geen bank binnen klik-/aanloopbereik (open=" + BANK_OPEN_RANGE
                + " aanloop≤" + BANK_APPROACH_MAX + " F2P≤" + F2P_BANK_OPEN_ANCHOR_RANGE + ")");
        return NearbyBankStep.NONE;
    }
}
