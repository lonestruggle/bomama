package com.combatbot;

import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.storm.api.domain.actors.IPlayer;
import net.storm.api.domain.tiles.ITileObject;
import net.storm.api.movement.IMovement;
import net.storm.api.movement.TilePath;
import net.storm.api.movement.WalkOptions;
import net.storm.api.movement.pathfinder.model.BankLocation;
import net.storm.sdk.entities.Players;
import net.storm.sdk.entities.TileObjects;
import net.storm.sdk.movement.Movement;

import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * MovementHelper — Gedeelde movement-logica via Storm SDK.
 *
 * Alle navigatie-methoden zijn hier gecentraliseerd zodat handlers
 * niet elk hun eigen fallback-logica hoeven te implementeren.
 * Gebruik {@link #walkTo(WorldPoint)} voor gepersonaliseerde aanloop; {@link #walkToExact(WorldPoint)} waar een exacte tegel vereist is (safespots).
 * Alleen binnen deze klasse wordt {@link Movement#walkTo} als laatste SDK-fallback gebruikt.
 *
 * Lumbridge Castle begane grond wordt actief vermeden bij reguliere navigatie.
 */
public final class MovementHelper {

    private static final Random RANDOM = new Random();

    /** Teleporteren en transports toegestaan. */
    private static final WalkOptions WALK_OPTIONS = WalkOptions.builder()
            .useTransports(true)
            .useTeleports(true)
            .build();

    // ===================== LUMBRIDGE CASTLE AVOIDANCE =====================

    /** Lumbridge Castle binnenkant (begane grond) — dit gebied vermijden bij regulier lopen. */
    private static final int LB_CASTLE_X_MIN = 3203;
    private static final int LB_CASTLE_X_MAX = 3213;
    private static final int LB_CASTLE_Y_MIN = 3207;
    private static final int LB_CASTLE_Y_MAX = 3230;

    // ===================== LUMBRIDGE DINING ROOM AVOIDANCE =====================

    /** Lumbridge Castle dining room (begane grond) — niemand loopt hier normaal doorheen. */
    private static final int LB_DINING_X_MIN = 3205;
    private static final int LB_DINING_X_MAX = 3212;
    private static final int LB_DINING_Y_MIN = 3218;
    private static final int LB_DINING_Y_MAX = 3226;

    /**
     * Waypoints om ROND het kasteel te lopen (begane grond).
     * Zuid-route: onder het kasteel langs.
     * Noord-route: boven het kasteel langs.
     */
    private static final WorldPoint LB_WAYPOINT_SOUTH = new WorldPoint(3208, 3200, 0);
    private static final WorldPoint LB_WAYPOINT_EAST  = new WorldPoint(3218, 3210, 0);
    private static final WorldPoint LB_WAYPOINT_WEST  = new WorldPoint(3198, 3210, 0);
    private static final WorldPoint LB_WAYPOINT_NORTH = new WorldPoint(3208, 3237, 0);

    /** Lumbridge stairs tile — voor bank-route interactie. */
    private static final WorldPoint LUMBRIDGE_STAIRS_TILE = new WorldPoint(3206, 3208, 0);
    /**
     * Trap op bankverdieping (plane 2) — altijd hier {@code Climb-down} voor natuurlijk afdalen
     * (geen willekeurige andere trap).
     */
    public static final WorldPoint LUMBRIDGE_BANK_FLOOR_STAIRS_TILE = new WorldPoint(3205, 3208, 2);
    /** Afstand waarop we de trap al proberen te klikken. */
    private static final int STAIRS_INTERACT_DISTANCE = 15;

    /** Geen spam op Lumbridge-trappen: min. ~0,85–1,55 s tussen trap-klikken. */
    private static long nextLumbridgeStairInteractAllowedMs;

    /**
     * Na één Climb-up/Top-floor: niet opnieuw op dezelfde trap klikken tot de speler een hogere plane heeft
     * (anders dubbele interact tijdens animatie).
     */
    private static int lumbridgeAscendStartedPlane = Integer.MIN_VALUE;
    private static long lumbridgeAscendInteractWallMs;

    public static boolean lumbridgeStairsMayInteractNow() {
        return System.currentTimeMillis() >= nextLumbridgeStairInteractAllowedMs;
    }

    public static void lumbridgeStairsRegisterInteract() {
        long now = System.currentTimeMillis();
        nextLumbridgeStairInteractAllowedMs = now + 850 + ThreadLocalRandom.current().nextInt(701);
    }

    private static void onLumbridgeStairAscendInteract(int fromPlane) {
        lumbridgeAscendStartedPlane = fromPlane;
        lumbridgeAscendInteractWallMs = System.currentTimeMillis() + 6000;
    }

    /** Aanroepen direct na een succesvolle Climb-up/Top-floor op de kasteeltrap. */
    public static void lumbridgeMarkAscendInteract(int fromPlane) {
        onLumbridgeStairAscendInteract(fromPlane);
    }

    /** {@code true} = ladder/trap-animatie nog bezig — geen tweede omhoog-klik. */
    public static boolean lumbridgeStairAscendInProgress(WorldPoint myPos) {
        if (myPos == null) {
            return false;
        }
        if (lumbridgeAscendStartedPlane == Integer.MIN_VALUE) {
            return false;
        }
        if (myPos.getPlane() > lumbridgeAscendStartedPlane) {
            lumbridgeAscendStartedPlane = Integer.MIN_VALUE;
            return false;
        }
        if (System.currentTimeMillis() > lumbridgeAscendInteractWallMs) {
            lumbridgeAscendStartedPlane = Integer.MIN_VALUE;
            return false;
        }
        return true;
    }

    // ===================== WALK-COMMANDO’S (anti-spam + periodieke herklik) =====================

    /**
     * Laatste punt/gebied waarvoor we pathfinding hebben gestart.
     * Zolang je beweegt naar hetzelfde doel: geen tweede klik binnen {@link #DUPLICATE_WALK_SUPPRESS_MS}
     * (voorkomt spam). Daarna opnieuw klikken zodat lange routes blijven doorlopen (zelfde gedachte als
     * {@link TravelWalkHelper}).
     */
    private static WorldPoint lastIssuedWalkTarget;
    private static WorldPoint lastIssuedAreaCenter;
    private static int lastIssuedAreaRadius = -1;
    private static long lastWalkCommandIssuedMs;
    private static int lastWalkArrivalReclickDistance = 3;
    private static long lastWalkForceReclickAfterMs;

    private static final int SAME_WALK_TARGET_TOLERANCE = 3;
    /** Zelfde doel + nog bezig met lopen: onder deze interval geen dubbele walk-issue (tick-spam). */
    private static final int DUPLICATE_WALK_SUPPRESS_MS = 800;
    /** Vloeiend doorklikken op lange routes: geen stap-voor-stap wachten tot eindtile. */
    private static final int FLOW_RECLICK_MS_MIN = 1_100;
    private static final int FLOW_RECLICK_MS_MAX = 2_000;
    /** Voor hetzelfde einddoel pas opnieuw klikken als we natuurlijk dichtbij zijn. */
    private static final int MIN_ARRIVAL_RECLICK_DISTANCE = 2;
    private static final int MAX_ARRIVAL_RECLICK_DISTANCE = 5;
    private static final int MIN_FORCE_RECLICK_MS = 8_000;
    private static final int MAX_FORCE_RECLICK_MS = 12_000;
    /** Alleen nabij/middel-lange klikdoelen krijgen arrival-gate; lange pathfinder-routes blijven vloeiend. */
    private static final int ARRIVAL_GATE_MAX_DISTANCE = 35;
    /** Globale optie uit Bot Control: forceer grotere walk-steps waar mogelijk. */
    private static volatile boolean forceLargeStepMode = false;
    private static final int FORCE_STEP_MIN = 15;
    private static final int FORCE_STEP_MAX = 20;
    private static final int FORCE_STEP_TRIGGER_DISTANCE = 28;

    public static void setForceLargeStepMode(boolean enabled) {
        forceLargeStepMode = enabled;
    }

    // ===================== DEBUG: walk-click tile highlight =====================

    private static volatile boolean debugWalkHighlightEnabled;
    /** Rolling cap zodat een lange sessie toch alle unieke walk-tiles toont. */
    private static final int DEBUG_WALK_HIGHLIGHT_MAX = 2000;
    private static final long DEBUG_WALK_HIGHLIGHT_TTL_MS = 24L * 60L * 60L * 1000L;

    /**
     * Per-tile aggregaat:
     *  - {@code count}      = aantal "Walk here" klikken op deze tile
     *  - {@code traversals} = aantal keer dat de speler over deze tile gewandeld is
     *  - {@code lastClickMs}/{@code lastTraverseMs} = laatste tijdstempels per type
     *  - {@code expireAtMs} = TTL — wordt bij elke nieuwe klik of traversal verlengd
     */
    public static final class WalkClickInfo {
        public final WorldPoint point;
        public int count;
        public int traversals;
        public long lastClickMs;
        public long lastTraverseMs;
        public long expireAtMs;

        WalkClickInfo(WorldPoint point, long nowMs, long expireMs) {
            this.point = point;
            this.count = 0;
            this.traversals = 0;
            this.lastClickMs = nowMs;
            this.lastTraverseMs = nowMs;
            this.expireAtMs = expireMs;
        }
    }

    /**
     * LinkedHashMap met access-order zodat we LRU-evictie krijgen wanneer we de
     * cap raken. We synchronizen op de map zelf voor thread-safety.
     */
    private static final java.util.LinkedHashMap<WorldPoint, WalkClickInfo> debugWalkClicks =
            new java.util.LinkedHashMap<>(64, 0.75f, true);

    private static volatile Consumer<String> debugWalkHighlightPersistFn;
    private static volatile Supplier<String> debugWalkHighlightLoadFn;

    /**
     * Optionele observer: krijgt elke click/traverse event door als één JSON-regel.
     * Plugin koppelt dit aan {@link DebugLog#appendWalkTilesJsonLine} (gate'd op config toggle).
     */
    private static volatile Consumer<String> walkTileEventSink;

    /**
     * Optionele observer voor hotspot-stuck-detectie. Krijgt {@link WalkClickInfo} +
     * windowMs door wanneer de drempel wordt overschreden. Plugin levert de drempel/window
     * vanuit config en logt een waarschuwing naar de Debug-tab.
     */
    public interface StuckHotspotListener {
        void onHotspotThresholdReached(WalkClickInfo info, int threshold, int windowSec);
    }
    private static volatile StuckHotspotListener stuckListener;
    private static volatile int stuckThreshold = 0;        // 0/negatief = uit
    private static volatile int stuckWindowSec = 120;
    /** Track per-tile click-counts binnen het rollende venster. */
    private static final java.util.HashMap<WorldPoint, java.util.ArrayDeque<Long>> stuckClickWindow =
            new java.util.HashMap<>();
    /** Per-tile timestamp van laatste warning, voorkomt spam. */
    private static final java.util.HashMap<WorldPoint, Long> stuckLastWarnedAt = new java.util.HashMap<>();

    /**
     * Koppel profile-opslag voor walk-debug trail (plugin {@code startUp}).
     */
    public static void setWalkHighlightPersistence(Consumer<String> persistFn, Supplier<String> loadFn) {
        debugWalkHighlightPersistFn = persistFn;
        debugWalkHighlightLoadFn = loadFn;
    }

    /** Plugin → koppel auto-log sink (mag null zijn om uit te zetten). */
    public static void setWalkTileEventSink(Consumer<String> sink) {
        walkTileEventSink = sink;
    }

    /**
     * Plugin → koppel een hook die elke keer aangeroepen wordt voordat we een walk-actie
     * naar de SDK sturen. Hier kan bv. de anti-ban worker geïnformeerd worden zodat hij
     * even pauzeert. Mag {@code null} zijn (= geen hook).
     */
    private static volatile Runnable preWalkActionHook;
    public static void setPreWalkActionHook(Runnable hook) {
        preWalkActionHook = hook;
    }
    private static void firePreWalkAction() {
        Runnable r = preWalkActionHook;
        if (r != null) {
            try { r.run(); } catch (Throwable ignored) {}
        }
    }

    /**
     * Plugin → koppel een check die {@code true} retourneert als een wereld-tile in de
     * <i>huidige</i> RuneLite-scene zit (bv. via {@code LocalPoint.fromWorld != null}).
     * Wordt door {@link #diagnoseWalkTiles} gebruikt om aan te tonen waarom tiles niet
     * renderen op een bepaalde locatie. Mag {@code null} zijn — dan slaan we de
     * scene-check over.
     */
    private static volatile java.util.function.Predicate<WorldPoint> sceneInBoundsCheck;
    public static void setSceneInBoundsCheck(java.util.function.Predicate<WorldPoint> check) {
        sceneInBoundsCheck = check;
    }

    /**
     * Plugin → koppel een check die {@code true} retourneert als de overlay een geldig
     * canvas-polygon kan tekenen voor de tile (Perspective.getCanvasTilePoly != null).
     * Een tile kan in-scene zijn maar tóch geen polygon krijgen (camera off-screen, occlusie).
     */
    private static volatile java.util.function.Predicate<WorldPoint> polygonRenderableCheck;
    public static void setPolygonRenderableCheck(java.util.function.Predicate<WorldPoint> check) {
        polygonRenderableCheck = check;
    }

    /**
     * Levert leesbare stats over de walk-tile opslag (totaal, click/path-tellers, scene-check).
     * Output is een lijst regels, geschikt voor {@link DebugLog#logBlock}.
     */
    public static java.util.List<String> diagnoseWalkTiles(WorldPoint playerPos) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        java.util.List<WalkClickInfo> all = snapshotDebugWalkClickInfos();
        int total = all.size();
        int clickTiles = 0;
        int pathTiles = 0;
        int totalClicks = 0;
        int totalTraversals = 0;
        for (WalkClickInfo i : all) {
            if (i.count > 0) clickTiles++;
            if (i.traversals > 0) pathTiles++;
            totalClicks += i.count;
            totalTraversals += i.traversals;
        }
        int inScene = 0;
        int outScene = 0;
        int renderable = 0;
        int sceneButNoPoly = 0;
        java.util.function.Predicate<WorldPoint> chk = sceneInBoundsCheck;
        java.util.function.Predicate<WorldPoint> polyChk = polygonRenderableCheck;
        if (chk != null) {
            for (WalkClickInfo i : all) {
                boolean inS = chk.test(i.point);
                if (inS) {
                    inScene++;
                    if (polyChk != null) {
                        if (polyChk.test(i.point)) renderable++;
                        else sceneButNoPoly++;
                    }
                } else {
                    outScene++;
                }
            }
        }

        lines.add("─── Walk-tile diagnose ───");
        lines.add("opslag: " + total + " unieke tiles  (cap=" + DEBUG_WALK_HIGHLIGHT_MAX
                + ", TTL=" + (DEBUG_WALK_HIGHLIGHT_TTL_MS / 1000L / 60L / 60L) + "h)");
        lines.add("click-tiles=" + clickTiles + " (sum=" + totalClicks
                + "), pad-tiles=" + pathTiles + " (sum=" + totalTraversals + ")");
        if (chk != null) {
            lines.add("scene-check: in-scene=" + inScene + " | out-of-scene=" + outScene
                    + (total > 0 ? "  (" + (inScene * 100 / Math.max(1, total)) + "% in-scene)" : ""));
        } else {
            lines.add("scene-check: (geen predicate gezet)");
        }
        if (polyChk != null) {
            lines.add("polygon-check: renderbaar=" + renderable
                    + " | in-scene maar GEEN polygon=" + sceneButNoPoly
                    + (inScene > 0 ? "  (" + (renderable * 100 / Math.max(1, inScene)) + "% v/d in-scene tiles)" : ""));
        }
        if (playerPos != null) {
            lines.add("speler-pos: " + playerPos.getX() + "," + playerPos.getY()
                    + ",p" + playerPos.getPlane());
            // Distance histogram naar speler — helpt zien of tiles ver weg liggen
            int near = 0; int mid = 0; int far = 0; int veryFar = 0;
            for (WalkClickInfo i : all) {
                int d = i.point.distanceTo(playerPos);
                if (d <= 15) near++;
                else if (d <= 50) mid++;
                else if (d <= 100) far++;
                else veryFar++;
            }
            lines.add("afstand tot speler: ≤15=" + near + " | 16-50=" + mid
                    + " | 51-100=" + far + " | >100=" + veryFar);
        }
        if (total == 0) {
            lines.add("→ GEEN tiles geregistreerd. Check: master overlay aan? Bot loopt of klikt walk?");
        } else if (chk != null && inScene == 0) {
            lines.add("→ ALLE tiles vallen buiten de huidige scene → niets om te tekenen.");
            lines.add("   (na teleport / lange wandeling / dungeon-overgang verdwijnen oude tiles uit scene)");
        } else if (polyChk != null && inScene > 0 && renderable == 0) {
            lines.add("→ Tiles zitten in scene MAAR krijgen geen polygon → camera/hoogte issue.");
            lines.add("   (zoom uit, kantel camera omhoog, of beweeg muis: vaak komt overlay terug)");
        } else if (polyChk != null && renderable > 0) {
            lines.add("→ " + renderable + " tiles ZOUDEN nu zichtbaar moeten zijn op je scherm.");
            lines.add("   Zie je niets? Check: andere overlays er overheen? Reset walk-tiles + maak handmatig 1 walk.");
        }
        return lines;
    }

    /** Plugin → configureer hotspot-stuck-detectie. threshold &le; 0 = uit. */
    public static void setStuckHotspotConfig(StuckHotspotListener listener, int threshold, int windowSec) {
        stuckListener = listener;
        stuckThreshold = Math.max(0, threshold);
        stuckWindowSec = Math.max(5, windowSec);
        synchronized (stuckClickWindow) {
            stuckClickWindow.clear();
            stuckLastWarnedAt.clear();
        }
    }

    public static void setDebugWalkHighlightEnabled(boolean enabled) {
        debugWalkHighlightEnabled = enabled;
        if (!enabled) {
            synchronized (debugWalkClicks) {
                debugWalkClicks.clear();
            }
            return;
        }
        synchronized (debugWalkClicks) {
            pruneExpiredDebugWalkHighlightsLocked();
            if (debugWalkClicks.isEmpty()) {
                restoreDebugWalkHighlightsFromConfigLocked();
            }
        }
    }

    /** Verwijdert alle walk-debug tiles en wist opgeslagen trail in config. */
    public static void clearDebugWalkHighlights() {
        synchronized (debugWalkClicks) {
            debugWalkClicks.clear();
        }
        persistDebugWalkHighlightsRaw("");
    }

    private static void persistDebugWalkHighlightsLocked() {
        Consumer<String> fn = debugWalkHighlightPersistFn;
        if (fn == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (WalkClickInfo info : debugWalkClicks.values()) {
            if (n++ >= DEBUG_WALK_HIGHLIGHT_MAX) {
                break;
            }
            if (sb.length() > 0) {
                sb.append(';');
            }
            WorldPoint p = info.point;
            // formaat v3: x,y,p,count,traversals,expire
            sb.append(p.getX()).append(',')
                    .append(p.getY()).append(',')
                    .append(p.getPlane()).append(',')
                    .append(info.count).append(',')
                    .append(info.traversals).append(',')
                    .append(info.expireAtMs);
        }
        fn.accept(sb.toString());
    }

    private static void persistDebugWalkHighlightsRaw(String raw) {
        Consumer<String> fn = debugWalkHighlightPersistFn;
        if (fn != null) {
            fn.accept(raw == null ? "" : raw);
        }
    }

    private static void restoreDebugWalkHighlightsFromConfigLocked() {
        Supplier<String> sup = debugWalkHighlightLoadFn;
        if (sup == null) {
            return;
        }
        String raw = sup.get();
        if (raw == null || raw.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        debugWalkClicks.clear();
        for (String piece : raw.split(";")) {
            String seg = piece.trim();
            if (seg.isEmpty()) {
                continue;
            }
            String[] pts = seg.split(",");
            try {
                int x;
                int y;
                int plane;
                int count;
                int traversals = 0;
                long until;
                if (pts.length == 6) {
                    // formaat v3: x,y,p,count,traversals,until
                    x = Integer.parseInt(pts[0].trim());
                    y = Integer.parseInt(pts[1].trim());
                    plane = Integer.parseInt(pts[2].trim());
                    count = Integer.parseInt(pts[3].trim());
                    traversals = Integer.parseInt(pts[4].trim());
                    until = Long.parseLong(pts[5].trim());
                } else if (pts.length == 5) {
                    // legacy v2: x,y,p,count,until
                    x = Integer.parseInt(pts[0].trim());
                    y = Integer.parseInt(pts[1].trim());
                    plane = Integer.parseInt(pts[2].trim());
                    count = Integer.parseInt(pts[3].trim());
                    until = Long.parseLong(pts[4].trim());
                } else if (pts.length == 4) {
                    // legacy v1: x,y,p,until → count=1
                    x = Integer.parseInt(pts[0].trim());
                    y = Integer.parseInt(pts[1].trim());
                    plane = Integer.parseInt(pts[2].trim());
                    count = 1;
                    until = Long.parseLong(pts[3].trim());
                } else {
                    continue;
                }
                if (until < now) {
                    continue;
                }
                WorldPoint wp = new WorldPoint(x, y, plane);
                WalkClickInfo info = new WalkClickInfo(wp, now, until);
                info.count = Math.max(0, count);
                info.traversals = Math.max(0, traversals);
                debugWalkClicks.put(wp, info);
            } catch (NumberFormatException ignored) {
            }
        }
        while (debugWalkClicks.size() > DEBUG_WALK_HIGHLIGHT_MAX) {
            java.util.Iterator<WorldPoint> it = debugWalkClicks.keySet().iterator();
            if (!it.hasNext()) {
                break;
            }
            it.next();
            it.remove();
        }
        if (debugWalkClicks.isEmpty() && raw != null && !raw.trim().isEmpty()) {
            persistDebugWalkHighlightsRaw("");
        }
    }

    private static void pruneExpiredDebugWalkHighlightsLocked() {
        long now = System.currentTimeMillis();
        debugWalkClicks.values().removeIf(info -> info.expireAtMs < now);
    }

    private static void recordDebugWalkHighlight(WorldPoint wp) {
        if (!debugWalkHighlightEnabled || wp == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long until = now + DEBUG_WALK_HIGHLIGHT_TTL_MS;
        WalkClickInfo snapshot;
        synchronized (debugWalkClicks) {
            WalkClickInfo info = touchInfoLocked(wp, now, until);
            info.count++;
            info.lastClickMs = now;
            persistDebugWalkHighlightsLocked();
            snapshot = info;
        }
        emitWalkTileEvent("click", wp, snapshot, now);
        checkStuckHotspot(wp, now, snapshot);
    }

    /**
     * Registreert dat de speler over een tile is gewandeld (path-traversal).
     * Wordt typisch elke GameTick aangeroepen vanuit de plugin als de speler-positie
     * is veranderd. Aparte teller dan {@link #recordDebugWalkHighlight}.
     */
    public static void recordPathTileTraversal(WorldPoint wp) {
        if (!debugWalkHighlightEnabled || wp == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long until = now + DEBUG_WALK_HIGHLIGHT_TTL_MS;
        WalkClickInfo snapshot;
        synchronized (debugWalkClicks) {
            WalkClickInfo info = touchInfoLocked(wp, now, until);
            info.traversals++;
            info.lastTraverseMs = now;
            persistDebugWalkHighlightsLocked();
            snapshot = info;
        }
        emitWalkTileEvent("traverse", wp, snapshot, now);
    }

    /** Rolling-window hotspot-check. Geen-op als threshold &le; 0 of geen listener. */
    private static void checkStuckHotspot(WorldPoint wp, long nowMs, WalkClickInfo snap) {
        StuckHotspotListener listener = stuckListener;
        int thr = stuckThreshold;
        int winSec = stuckWindowSec;
        if (listener == null || thr <= 0 || wp == null) {
            return;
        }
        long windowMs = winSec * 1000L;
        boolean fire = false;
        int countInWindow;
        synchronized (stuckClickWindow) {
            java.util.ArrayDeque<Long> q = stuckClickWindow.computeIfAbsent(wp, k -> new java.util.ArrayDeque<>());
            q.addLast(nowMs);
            // Prune events ouder dan windowMs
            while (!q.isEmpty() && (nowMs - q.peekFirst()) > windowMs) {
                q.pollFirst();
            }
            countInWindow = q.size();
            // Best-effort cleanup: zorg dat de map niet eindeloos groeit
            if (stuckClickWindow.size() > 4096) {
                stuckClickWindow.entrySet().removeIf(e ->
                        e.getValue().isEmpty() || (nowMs - e.getValue().peekLast()) > windowMs * 2);
            }
            if (countInWindow >= thr) {
                Long last = stuckLastWarnedAt.get(wp);
                // Maximaal 1 waarschuwing per windowMs per tile
                if (last == null || (nowMs - last) >= windowMs) {
                    stuckLastWarnedAt.put(wp, nowMs);
                    fire = true;
                }
            }
        }
        if (fire) {
            try {
                listener.onHotspotThresholdReached(snap, thr, winSec);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void emitWalkTileEvent(String type, WorldPoint wp, WalkClickInfo snap, long nowMs) {
        Consumer<String> sink = walkTileEventSink;
        if (sink == null || wp == null || snap == null) return;
        // Compacte JSON, geen externe dep — zelfde stijl als andere ML-logs in DebugLog.
        StringBuilder sb = new StringBuilder(160);
        sb.append('{')
                .append("\"t\":").append(nowMs).append(',')
                .append("\"type\":\"").append(type).append("\",")
                .append("\"x\":").append(wp.getX()).append(',')
                .append("\"y\":").append(wp.getY()).append(',')
                .append("\"plane\":").append(wp.getPlane()).append(',')
                .append("\"clicks\":").append(snap.count).append(',')
                .append("\"traversals\":").append(snap.traversals)
                .append('}');
        try {
            sink.accept(sb.toString());
        } catch (Throwable ignored) {
        }
    }

    /**
     * Top-N tiles met meeste clicks (descending). Stabiel tegen externe modificaties.
     */
    public static java.util.List<WalkClickInfo> topClickHotspots(int n) {
        if (n <= 0) return java.util.Collections.emptyList();
        java.util.List<WalkClickInfo> all = snapshotDebugWalkClickInfos();
        all.removeIf(i -> i.count <= 0);
        all.sort((a, b) -> Integer.compare(b.count, a.count));
        if (all.size() > n) all = all.subList(0, n);
        return all;
    }

    /**
     * Top-N tiles waar de speler het vaakst over gelopen is (descending).
     */
    public static java.util.List<WalkClickInfo> topPathHotspots(int n) {
        if (n <= 0) return java.util.Collections.emptyList();
        java.util.List<WalkClickInfo> all = snapshotDebugWalkClickInfos();
        all.removeIf(i -> i.traversals <= 0);
        all.sort((a, b) -> Integer.compare(b.traversals, a.traversals));
        if (all.size() > n) all = all.subList(0, n);
        return all;
    }

    /**
     * Bouwt een lijst JSON-regels (één per tile) van de huidige walk-tile aggregaten,
     * geschikt voor {@link DebugLog#exportWalkTilesSnapshot}.
     */
    public static java.util.List<String> exportWalkTilesAsJsonl() {
        java.util.List<WalkClickInfo> all = snapshotDebugWalkClickInfos();
        java.util.ArrayList<String> out = new java.util.ArrayList<>(all.size());
        for (WalkClickInfo info : all) {
            StringBuilder sb = new StringBuilder(180);
            sb.append('{')
                    .append("\"x\":").append(info.point.getX()).append(',')
                    .append("\"y\":").append(info.point.getY()).append(',')
                    .append("\"plane\":").append(info.point.getPlane()).append(',')
                    .append("\"clicks\":").append(info.count).append(',')
                    .append("\"traversals\":").append(info.traversals).append(',')
                    .append("\"lastClickMs\":").append(info.lastClickMs).append(',')
                    .append("\"lastTraverseMs\":").append(info.lastTraverseMs).append(',')
                    .append("\"expireAtMs\":").append(info.expireAtMs)
                    .append('}');
            out.add(sb.toString());
        }
        return out;
    }

    /**
     * Haalt of creëert de {@link WalkClickInfo} voor een tile, prunet expired entries en
     * forceert LRU-evictie. CALLER MOET LOCK OP {@code debugWalkClicks} HOUDEN.
     */
    private static WalkClickInfo touchInfoLocked(WorldPoint wp, long nowMs, long expireMs) {
        pruneExpiredDebugWalkHighlightsLocked();
        WalkClickInfo info = debugWalkClicks.get(wp);
        if (info == null) {
            info = new WalkClickInfo(wp, nowMs, expireMs);
            debugWalkClicks.put(wp, info);
        }
        info.expireAtMs = expireMs;
        while (debugWalkClicks.size() > DEBUG_WALK_HIGHLIGHT_MAX) {
            java.util.Iterator<WorldPoint> it = debugWalkClicks.keySet().iterator();
            if (!it.hasNext()) {
                break;
            }
            it.next();
            it.remove();
        }
        return info;
    }

    /**
     * Externe entry-point: Plugin kan elke "Walk here"-MenuOptionClicked direct hier
     * doorzetten zodat we ook clicks vangen die de SDK intern doet (en dus niet via
     * onze {@link #walkTo}/-{@link #walkToArea} fallbacks lopen).
     */
    public static void recordExternalWalkClick(WorldPoint wp) {
        recordDebugWalkHighlight(wp);
    }

    /** Snapshot van actuele walk-click info per tile (TTL-pruned). */
    public static java.util.List<WalkClickInfo> snapshotDebugWalkClickInfos() {
        if (!debugWalkHighlightEnabled) {
            return java.util.Collections.emptyList();
        }
        synchronized (debugWalkClicks) {
            pruneExpiredDebugWalkHighlightsLocked();
            java.util.ArrayList<WalkClickInfo> out = new java.util.ArrayList<>(debugWalkClicks.size());
            for (WalkClickInfo info : debugWalkClicks.values()) {
                WalkClickInfo copy = new WalkClickInfo(info.point, info.lastClickMs, info.expireAtMs);
                copy.count = info.count;
                copy.traversals = info.traversals;
                copy.lastClickMs = info.lastClickMs;
                copy.lastTraverseMs = info.lastTraverseMs;
                out.add(copy);
            }
            return out;
        }
    }

    /** Backwards-compat (nog gebruikt door oudere overlay code). */
    @Deprecated
    public static java.util.List<WorldPoint> snapshotDebugWalkHighlightPoints() {
        java.util.List<WalkClickInfo> infos = snapshotDebugWalkClickInfos();
        java.util.ArrayList<WorldPoint> out = new java.util.ArrayList<>(infos.size());
        for (WalkClickInfo i : infos) {
            out.add(i.point);
        }
        return out;
    }

    private static boolean isSameWalkTarget(WorldPoint a, WorldPoint b) {
        if (a == null || b == null) {
            return false;
        }
        return a.getPlane() == b.getPlane() && a.distanceTo(b) <= SAME_WALK_TARGET_TOLERANCE;
    }

    private static boolean shouldSkipDuplicatePointWalk(WorldPoint destination) {
        if (destination == null) {
            return false;
        }
        IPlayer local = Players.getLocal();
        if (local == null || !local.isMoving()) {
            return false;
        }
        if (lastIssuedAreaRadius >= 0) {
            return false;
        }
        if (!isSameWalkTarget(lastIssuedWalkTarget, destination)) {
            return false;
        }
        long age = System.currentTimeMillis() - lastWalkCommandIssuedMs;
        WorldPoint pos = local.getWorldLocation();
        int distToTarget = pos != null ? pos.distanceTo(lastIssuedWalkTarget) : Integer.MAX_VALUE;
        if (age < DUPLICATE_WALK_SUPPRESS_MS) {
            return true;
        }
        if (distToTarget <= ARRIVAL_GATE_MAX_DISTANCE
                && age < lastWalkForceReclickAfterMs
                && distToTarget > lastWalkArrivalReclickDistance) {
            return true;
        }
        if (age < currentFlowReclickMs()) {
            return true;
        }
        return false;
    }

    private static void recordPointWalkIssued(WorldPoint destination) {
        lastIssuedWalkTarget = destination;
        lastIssuedAreaCenter = null;
        lastIssuedAreaRadius = -1;
        lastWalkCommandIssuedMs = System.currentTimeMillis();
        randomizeArrivalReclickGate();
        recordDebugWalkHighlight(destination);
    }

    private static boolean shouldSkipDuplicateAreaWalk(WorldPoint center, int radius) {
        if (center == null) {
            return false;
        }
        IPlayer local = Players.getLocal();
        if (local == null || !local.isMoving()) {
            return false;
        }
        if (lastIssuedAreaRadius < 0 || lastIssuedAreaCenter == null) {
            return false;
        }
        if (lastIssuedAreaRadius != radius) {
            return false;
        }
        if (lastIssuedAreaCenter.distanceTo(center) > SAME_WALK_TARGET_TOLERANCE) {
            return false;
        }
        long age = System.currentTimeMillis() - lastWalkCommandIssuedMs;
        if (age < DUPLICATE_WALK_SUPPRESS_MS) {
            return true;
        }
        // Area-walks blijven tijd-gebaseerd; anders worden route-segmenten te houterig.
        if (age < currentFlowReclickMs()) {
            return true;
        }
        return false;
    }

    private static void recordAreaWalkIssued(WorldPoint center, int radius) {
        lastIssuedAreaCenter = center;
        lastIssuedAreaRadius = radius;
        lastIssuedWalkTarget = center;
        lastWalkCommandIssuedMs = System.currentTimeMillis();
        recordDebugWalkHighlight(center);
    }

    private static void randomizeArrivalReclickGate() {
        lastWalkArrivalReclickDistance = ThreadLocalRandom.current().nextInt(
                MIN_ARRIVAL_RECLICK_DISTANCE, MAX_ARRIVAL_RECLICK_DISTANCE + 1);
        lastWalkForceReclickAfterMs = ThreadLocalRandom.current().nextInt(
                MIN_FORCE_RECLICK_MS, MAX_FORCE_RECLICK_MS + 1);
    }

    private static int currentFlowReclickMs() {
        long seed = (lastWalkCommandIssuedMs * 1103515245L + 12345L) & 0x7fffffffL;
        return FLOW_RECLICK_MS_MIN + (int) (seed % (FLOW_RECLICK_MS_MAX - FLOW_RECLICK_MS_MIN + 1));
    }

    private static WorldPoint maybeLargeStepTarget(WorldPoint destination) {
        if (!forceLargeStepMode || destination == null) {
            return destination;
        }
        IPlayer local = Players.getLocal();
        if (local == null || local.getWorldLocation() == null) {
            return destination;
        }
        WorldPoint from = local.getWorldLocation();
        if (from.getPlane() != destination.getPlane()) {
            return destination;
        }
        int dist = from.distanceTo(destination);
        if (dist <= FORCE_STEP_TRIGGER_DISTANCE) {
            return destination;
        }
        int dx = destination.getX() - from.getX();
        int dy = destination.getY() - from.getY();
        double euclid = Math.sqrt((double) dx * dx + (double) dy * dy);
        if (euclid <= 1.0) {
            return destination;
        }
        int step = ThreadLocalRandom.current().nextInt(FORCE_STEP_MIN, FORCE_STEP_MAX + 1);
        step = Math.min(step, Math.max(1, dist - 1));
        int stepX = from.getX() + (int) Math.round(dx / euclid * step);
        int stepY = from.getY() + (int) Math.round(dy / euclid * step);
        return new WorldPoint(stepX, stepY, from.getPlane());
    }

    /** Door-namen die NIET geklikt mogen worden (Lumbridge dining room deuren). */
    private static final String[] BLOCKED_DOOR_NAMES = {"Door", "Large door"};

    private MovementHelper() {}

    /** Check of een punt binnen het Lumbridge Castle begane grond valt. */
    private static boolean isInsideLumbridgeCastle(WorldPoint p) {
        if (p == null || p.getPlane() != 0) return false;
        return p.getX() >= LB_CASTLE_X_MIN && p.getX() <= LB_CASTLE_X_MAX
                && p.getY() >= LB_CASTLE_Y_MIN && p.getY() <= LB_CASTLE_Y_MAX;
    }

    /** Check of een punt binnen de Lumbridge dining room valt. */
    private static boolean isInsideLumbridgeDiningRoom(WorldPoint p) {
        if (p == null || p.getPlane() != 0) return false;
        return p.getX() >= LB_DINING_X_MIN && p.getX() <= LB_DINING_X_MAX
                && p.getY() >= LB_DINING_Y_MIN && p.getY() <= LB_DINING_Y_MAX;
    }

    /** Check of een punt in een verboden Lumbridge zone valt (kasteel interieur OF dining room). */
    public static boolean isInLumbridgeBlockedZone(WorldPoint p) {
        return isInsideLumbridgeCastle(p) || isInsideLumbridgeDiningRoom(p);
    }

    /** Check of punt in de brede Lumbridge regio is (voor detectie). */
    private static boolean isInLumbridgeRegion(WorldPoint p) {
        if (p == null || p.getPlane() != 0) return false;
        return p.getX() >= 3180 && p.getX() <= 3240
                && p.getY() >= 3180 && p.getY() <= 3250;
    }

    /**
     * Kies een waypoint om het kasteel heen, gebaseerd op speler- en doelpositie.
     * Let op: oost-waypoint (3218,3210) alleen als we naar het oosten moeten — niet bij doel ten WESTEN
     * (anders blijft de bot op 3218,3210 hangen terwijl train bij schaapskooi west ligt).
     */
    private static WorldPoint getLumbridgeBypassWaypoint(WorldPoint from, WorldPoint to) {
        boolean toWest = to.getX() < LB_CASTLE_X_MIN;
        boolean toEast = to.getX() > LB_CASTLE_X_MAX;
        boolean fromSouth = from.getY() < LB_CASTLE_Y_MIN;
        boolean fromNorth = from.getY() > LB_CASTLE_Y_MAX;
        boolean fromEast = from.getX() > LB_CASTLE_X_MAX;
        boolean fromWest = from.getX() < LB_CASTLE_X_MIN;

        // Oost ↔ west: altijd eerst via zuid eromheen (niet via oost-waypoint naar westelijk doel)
        if (fromEast && toWest) {
            return LB_WAYPOINT_SOUTH;
        }
        if (fromWest && toEast) {
            return LB_WAYPOINT_SOUTH;
        }
        // Zuid van het kasteel, doel ten westen: west-waypoint — niet opnieuw naar zuid plakken
        if (fromSouth && toWest) {
            return LB_WAYPOINT_WEST;
        }
        if (fromSouth && toEast) {
            return LB_WAYPOINT_EAST;
        }

        if (fromSouth) {
            return LB_WAYPOINT_SOUTH;
        }
        if (fromNorth) {
            return LB_WAYPOINT_NORTH;
        }
        if (fromEast) {
            return LB_WAYPOINT_EAST;
        }
        if (fromWest) {
            return LB_WAYPOINT_WEST;
        }
        return LB_WAYPOINT_SOUTH;
    }

    /**
     * Check of de route van 'from' naar 'to' waarschijnlijk door het kasteel gaat,
     * en we dus moeten omlopen.
     */
    private static boolean shouldBypassCastle(WorldPoint from, WorldPoint to) {
        if (from == null || to == null) return false;
        if (from.getPlane() != 0 || to.getPlane() != 0) return false;
        // Alleen bypass nodig als beide in de Lumbridge regio zijn
        if (!isInLumbridgeRegion(from) && !isInLumbridgeRegion(to)) return false;
        // Als de speler AL in het kasteel/dining room staat, niet bypassen (laat ze er gewoon uit lopen)
        if (isInsideLumbridgeCastle(from) || isInsideLumbridgeDiningRoom(from)) return false;
        // Als het doel IN het kasteel is (bijv. trap), niet bypassen
        if (isInsideLumbridgeCastle(to)) return false;
        // Als het doel IN de dining room is, WEL bypassen (we willen daar NOOIT naartoe)
        if (isInsideLumbridgeDiningRoom(to)) return true;

        // Check of een rechte lijn van from→to het kasteel of dining room zou kruisen
        boolean fromWest = from.getX() < LB_CASTLE_X_MIN;
        boolean fromEast = from.getX() > LB_CASTLE_X_MAX;
        boolean toWest = to.getX() < LB_CASTLE_X_MIN;
        boolean toEast = to.getX() > LB_CASTLE_X_MAX;
        boolean fromSouth = from.getY() < LB_CASTLE_Y_MIN;
        boolean fromNorth = from.getY() > LB_CASTLE_Y_MAX;
        boolean toSouth = to.getY() < LB_CASTLE_Y_MIN;
        boolean toNorth = to.getY() > LB_CASTLE_Y_MAX;

        // Tegenovergestelde kant oost-west met Y in kasteel-bereik
        if ((fromWest && toEast) || (fromEast && toWest)) {
            int avgY = (from.getY() + to.getY()) / 2;
            if (avgY >= LB_CASTLE_Y_MIN && avgY <= LB_CASTLE_Y_MAX) return true;
        }
        // Tegenovergestelde kant noord-zuid met X in kasteel-bereik
        if ((fromSouth && toNorth) || (fromNorth && toSouth)) {
            int avgX = (from.getX() + to.getX()) / 2;
            if (avgX >= LB_CASTLE_X_MIN && avgX <= LB_CASTLE_X_MAX) return true;
        }

        // Extra: check of route door dining room gaat (Y-range 3218-3226)
        // Als van zuid naar noord en X in dining room bereik
        if (from.getY() < LB_DINING_Y_MIN && to.getY() > LB_DINING_Y_MAX) {
            int avgX = (from.getX() + to.getX()) / 2;
            if (avgX >= LB_DINING_X_MIN && avgX <= LB_DINING_X_MAX) return true;
        }
        if (from.getY() > LB_DINING_Y_MAX && to.getY() < LB_DINING_Y_MIN) {
            int avgX = (from.getX() + to.getX()) / 2;
            if (avgX >= LB_DINING_X_MIN && avgX <= LB_DINING_X_MAX) return true;
        }

        return false;
    }

    // ===================== STAIRS FROM DISTANCE =====================

    /**
     * Probeer de Lumbridge trap te klikken als we er dichtbij genoeg zijn
     * en op weg zijn naar de bank (plane 0).
     *
     * @return true als trap-interactie is gestart
     */
    public static boolean tryClickLumbridgeStairsFromDistance(WorldPoint myPos) {
        if (myPos == null || myPos.getPlane() != 0) return false;
        if (!isInLumbridgeRegion(myPos)) return false;
        if (lumbridgeStairAscendInProgress(myPos)) {
            return false;
        }

        int distToStairs = myPos.distanceTo(LUMBRIDGE_STAIRS_TILE);
        if (distToStairs > STAIRS_INTERACT_DISTANCE) return false;

        if (!lumbridgeStairsMayInteractNow()) {
            return false;
        }

        // Probeer de trap te vinden en te klikken
        ITileObject stairs = TileObjects.getNearest(obj ->
                obj != null
                        && obj.getName() != null
                        && obj.getName().equalsIgnoreCase("Staircase")
                        && obj.getWorldLocation().getPlane() == 0
                        && (obj.hasAction("Top-floor") || obj.hasAction("Climb-up"))
        );
        if (stairs != null) {
            String action = stairs.hasAction("Top-floor") ? "Top-floor" : "Climb-up";
            DebugLog.log("MovementHelper", "Lumbridge trap gevonden op afstand " + distToStairs
                    + " tiles, klik " + action);
            stairs.interact(action);
            lumbridgeMarkAscendInteract(0);
            lumbridgeStairsRegisterInteract();
            return true;
        }
        return false;
    }

    /**
     * Check of we op een hogere verdieping in Lumbridge Castle staan en naar beneden moeten.
     * Na banking op plane 2 moet de bot de trap afdalen naar plane 0.
     *
     * @param destination het doel waar we naartoe willen (plane 0)
     * @return true als trap-interactie is gestart (caller moet wachten)
     */
    public static boolean tryDescendLumbridgeStairs(WorldPoint myPos, WorldPoint destination) {
        if (myPos == null || destination == null) return false;
        // Alleen als we op plane 1 of 2 staan EN het doel is plane 0
        if (myPos.getPlane() == 0 || destination.getPlane() != 0) return false;
        // Alleen in Lumbridge Castle regio
        int x = myPos.getX();
        int y = myPos.getY();
        if (!(x >= 3195 && x <= 3220 && y >= 3195 && y <= 3235)) return false;

        DebugLog.log("MovementHelper", "Lumbridge plane " + myPos.getPlane()
                + " → doel plane 0, zoek trap om af te dalen");

        // Plane 2 (bank): altijd vaste trap 3205,3208,2 met Climb-down — geen andere trap
        if (myPos.getPlane() == 2) {
            WorldPoint fixed = LUMBRIDGE_BANK_FLOOR_STAIRS_TILE;
            if (myPos.distanceTo(fixed) > 2) {
                DebugLog.log("MovementHelper", "Lumbridge plane 2: loop naar vaste trap " + fixed);
                if (walkToInternal(fixed)) {
                    return true;
                }
                if (!lumbridgeStairsMayInteractNow()) {
                    return true;
                }
                // Pathfinding faalt soms op verdieping — direct Climb-down als object in bereik
                if (tryInteractNearestPlane2ClimbDown(myPos, fixed)) {
                    return true;
                }
                return false;
            }
            // Cooldown: wacht deze tick (true), anders valt walkTo door naar bypass / andere clicks
            if (!lumbridgeStairsMayInteractNow()) {
                return true;
            }
            ITileObject stairs = TileObjects.getNearest(obj ->
                    obj != null
                            && obj.getName() != null
                            && obj.getName().equalsIgnoreCase("Staircase")
                            && obj.getWorldLocation().getPlane() == 2
                            && obj.hasAction("Climb-down")
                            && obj.getWorldLocation().distanceTo(fixed) <= 3
            );
            if (stairs != null) {
                DebugLog.log("MovementHelper", "Lumbridge trap Climb-down (plane 2, vaste tile)");
                stairs.interact("Climb-down");
                lumbridgeStairsRegisterInteract();
                return true;
            }
            stairs = TileObjects.getNearest(obj ->
                    obj != null
                            && obj.getName() != null
                            && obj.getName().equalsIgnoreCase("Staircase")
                            && obj.getWorldLocation().getPlane() == 2
                            && obj.hasAction("Climb-down")
            );
            if (stairs != null) {
                stairs.interact("Climb-down");
                lumbridgeStairsRegisterInteract();
                return true;
            }
            // Naam kan "Spiral staircase" e.d. zijn — elke trap met Climb-down op plane 2 nabij vaste tile
            if (tryInteractNearestPlane2ClimbDown(myPos, fixed)) {
                return true;
            }
            return walkToInternal(fixed);
        }

        // Plane 1: één verdieping omlaag — eerst dicht bij vaste spiral tile, dan pas interact / cooldown
        WorldPoint stairTile = new WorldPoint(3206, 3208, myPos.getPlane());
        if (myPos.distanceTo(stairTile) > 4) {
            DebugLog.log("MovementHelper", "Lumbridge plane 1: loop naar trap-tile " + stairTile);
            if (walkToInternal(stairTile)) {
                return true;
            }
            if (!lumbridgeStairsMayInteractNow()) {
                return true;
            }
            if (tryInteractPlane1Descend(myPos)) {
                return true;
            }
            return false;
        }
        if (!lumbridgeStairsMayInteractNow()) {
            return true;
        }
        ITileObject stairs = TileObjects.getNearest(obj ->
                obj != null
                        && obj.getName() != null
                        && obj.getName().equalsIgnoreCase("Staircase")
                        && (obj.hasAction("Climb-down") || obj.hasAction("Ground-floor"))
        );
        if (stairs != null) {
            String action = stairs.hasAction("Ground-floor") ? "Ground-floor" : "Climb-down";
            DebugLog.log("MovementHelper", "Lumbridge trap afdalen: plane=" + myPos.getPlane()
                    + " action=" + action);
            stairs.interact(action);
            lumbridgeStairsRegisterInteract();
            return true;
        }
        if (tryInteractPlane1Descend(myPos)) {
            return true;
        }

        DebugLog.log("MovementHelper", "Trap niet gevonden, loop naar " + stairTile);
        return walkToInternal(stairTile);
    }

    /**
     * Plane 2: elke tile object met Climb-down (ongeacht exacte naam) binnen bereik van vaste trap-route.
     */
    private static boolean tryInteractNearestPlane2ClimbDown(WorldPoint myPos, WorldPoint fixed) {
        if (myPos == null || fixed == null) {
            return false;
        }
        ITileObject stairs = TileObjects.getNearest(obj ->
                obj != null
                        && obj.getWorldLocation() != null
                        && obj.getWorldLocation().getPlane() == 2
                        && obj.hasAction("Climb-down")
                        && obj.getWorldLocation().distanceTo(fixed) <= 6
                        && myPos.distanceTo(obj.getWorldLocation()) <= 15
        );
        if (stairs != null) {
            DebugLog.log("MovementHelper", "Lumbridge plane 2: Climb-down (fallback, obj=" + stairs.getName() + ")");
            stairs.interact("Climb-down");
            lumbridgeStairsRegisterInteract();
            return true;
        }
        return false;
    }

    /** Plane 1: trap omlaag als Staircase-filter faalt (alternatieve objectnamen). */
    private static boolean tryInteractPlane1Descend(WorldPoint myPos) {
        if (myPos == null) {
            return false;
        }
        ITileObject stairs = TileObjects.getNearest(obj -> {
            if (obj == null || obj.getName() == null || obj.getWorldLocation() == null) {
                return false;
            }
            String n = obj.getName().toLowerCase(Locale.ROOT);
            if (!n.contains("stair") && !n.contains("ladder")) {
                return false;
            }
            return obj.getWorldLocation().getPlane() == myPos.getPlane()
                    && (obj.hasAction("Climb-down") || obj.hasAction("Ground-floor"))
                    && myPos.distanceTo(obj.getWorldLocation()) <= 15;
        });
        if (stairs != null) {
            String action = stairs.hasAction("Ground-floor") ? "Ground-floor" : "Climb-down";
            DebugLog.log("MovementHelper", "Lumbridge plane 1: afdalen fallback action=" + action
                    + " obj=" + stairs.getName());
            stairs.interact(action);
            lumbridgeStairsRegisterInteract();
            return true;
        }
        return false;
    }

    // ===================== CORE NAVIGATION =====================

    /**
     * Loop naar een gebied (center + radius). Prefereert walkTo() zodat de speler
     * in één keer doorloopt (zoals explore), niet stap-voor-stap opnieuw klikken.
     *
     * Vermijdt automatisch het Lumbridge Castle begane grond.
     */
    public static boolean walkToArea(WorldPoint center, int radius) {
        if (center == null || radius < 0) return false;
        // Geen personalisatie voor area-walks: de SDK kiest zelf al een willekeurige tile
        // binnen de WorldArea, dus per-account ±1 shift voegt niets toe en zorgt voor een
        // boundary-mismatch (handler ziet "buiten area", helper ziet "al binnen").
        IPlayer local = Players.getLocal();
        if (local != null) {
            WorldPoint pos = local.getWorldLocation();
            // Belangrijk: gebruik de ECHTE center voor de "al binnen?" check. Anders kan de
            // bot 1 tile naast de boundary stilstaan terwijl de caller (handler) blijft denken
            // dat we nog moeten lopen → infinite no-op loop.
            if (pos != null && distanceToArea(pos, center, radius) <= 0) return true;

            // Lumbridge: als we op hogere verdieping staan en doel is plane 0, eerst trap af
            if (pos != null && tryDescendLumbridgeStairs(pos, center)) return true;

            // Lumbridge Castle bypass check (plane 0)
            if (pos != null && shouldBypassCastle(pos, center)) {
                WorldPoint waypoint = getLumbridgeBypassWaypoint(pos, center);
                DebugLog.log("MovementHelper", "walkToArea: Lumbridge bypass via " + waypoint);
                return walkToInternal(waypoint);
            }
        }
        return walkToAreaInternal(center, radius);
    }

    /** Interne walkToArea zonder bypass-check (voorkomt recursie). */
    private static boolean walkToAreaInternal(WorldPoint center, int radius) {
        WorldPoint target = maybeLargeStepTarget(center);
        if (shouldSkipDuplicateAreaWalk(target, radius)) {
            return true;
        }
        // Anti-ban hint: walk-actie op handen → fidget mag even niets doen.
        firePreWalkAction();
        WorldArea area = toWorldArea(target, radius);
        boolean usedLargeStepTarget = target != null && !target.equals(center);
        try {
            IMovement movement = net.storm.api.Static.getMovement();
            if (movement != null) {
                if (movement.walkTo(area, WALK_OPTIONS)) {
                    recordAreaWalkIssued(target, radius);
                    return true;
                }
                if (movement.walkTo(target)) {
                    recordAreaWalkIssued(target, radius);
                    return true;
                }
                TilePath path = movement.getPath(area);
                if (path != null && !path.isEmpty()) {
                    path.walk(WALK_OPTIONS);
                    recordAreaWalkIssued(target, radius);
                    return true;
                }
                path = movement.getPath(target);
                if (path != null && !path.isEmpty()) {
                    path.walk(WALK_OPTIONS);
                    recordAreaWalkIssued(target, radius);
                    return true;
                }
                // Fallback: als grote stap-target niet lukt, probeer originele center.
                if (usedLargeStepTarget) {
                    WorldArea centerArea = toWorldArea(center, radius);
                    if (movement.walkTo(centerArea, WALK_OPTIONS) || movement.walkTo(center)) {
                        recordAreaWalkIssued(center, radius);
                        return true;
                    }
                    path = movement.getPath(centerArea);
                    if (path != null && !path.isEmpty()) {
                        path.walk(WALK_OPTIONS);
                        recordAreaWalkIssued(center, radius);
                        return true;
                    }
                    path = movement.getPath(center);
                    if (path != null && !path.isEmpty()) {
                        path.walk(WALK_OPTIONS);
                        recordAreaWalkIssued(center, radius);
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {}
        try {
            if (Movement.walkTo(target)) {
                recordAreaWalkIssued(target, radius);
                return true;
            }
            if (usedLargeStepTarget && Movement.walkTo(center)) {
                recordAreaWalkIssued(center, radius);
                return true;
            }
        } catch (Throwable t) {
            return false;
        }
        return false;
    }

    /**
     * Loop naar een enkel punt met optioneel per-RSN tegel-shift ({@link AccountBehaviorProfileStore#personalizeApproachTile}).
     * Prefereert walkTo() (doorlopen in één keer). Vermijdt Lumbridge Castle begane grond.
     *
     * @param personalize {@code false} voor exacte tegels (safespots, precisie-paden).
     */
    public static boolean walkTo(WorldPoint destination, boolean personalize) {
        if (destination == null) {
            return false;
        }
        WorldPoint dest = destination;
        if (personalize) {
            AccountBehaviorProfileStore.Profile prof = BankWalkPersonality.getActive();
            dest = AccountBehaviorProfileStore.personalizeApproachTile(prof, destination);
        }
        IPlayer local = Players.getLocal();
        if (local != null) {
            WorldPoint pos = local.getWorldLocation();
            // Lumbridge: als we op hogere verdieping staan en doel is plane 0, eerst trap af
            if (pos != null && tryDescendLumbridgeStairs(pos, dest)) {
                return true;
            }
            // Castle bypass op begane grond
            if (pos != null && shouldBypassCastle(pos, dest)) {
                WorldPoint waypoint = getLumbridgeBypassWaypoint(pos, dest);
                DebugLog.log("MovementHelper", "walkTo: Lumbridge bypass via " + waypoint);
                return walkToInternal(waypoint);
            }
        }
        return walkToInternal(dest);
    }

    /**
     * Standaard: wél gedragsprofiel-tegel-shift (bank, bomen, GE, …).
     */
    public static boolean walkTo(WorldPoint destination) {
        return walkTo(destination, true);
    }

    /** Geen tegel-shift — o.a. safespots waar 1 tile verschil kritisch is. */
    public static boolean walkToExact(WorldPoint destination) {
        return walkTo(destination, false);
    }

    /** Pad naar dichtstbijzijnde banklocatie (zelfde personalisatie als {@link #walkTo(WorldPoint)}). */
    public static boolean walkTo(BankLocation bankLocation) {
        if (bankLocation == null) {
            return false;
        }
        try {
            if (bankLocation.getArea() == null) {
                return false;
            }
            WorldPoint bp = bankLocation.getArea().toWorldPoint();
            if (bp == null) {
                return false;
            }
            return walkTo(bp);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Interne walkTo zonder bypass-check (voorkomt recursie). */
    private static boolean walkToInternal(WorldPoint destination) {
        if (destination == null) {
            return false;
        }
        WorldPoint target = maybeLargeStepTarget(destination);
        boolean usedLargeStepTarget = target != null && !target.equals(destination);
        if (shouldSkipDuplicatePointWalk(target)) {
            return true;
        }
        // Anti-ban hint: walk-actie op handen → fidget mag even niets doen.
        firePreWalkAction();
        try {
            IMovement movement = net.storm.api.Static.getMovement();
            if (movement != null) {
                if (movement.walkTo(target)) {
                    recordPointWalkIssued(target);
                    return true;
                }
                TilePath path = movement.getPath(target);
                if (path != null && !path.isEmpty()) {
                    path.walk(WALK_OPTIONS);
                    recordPointWalkIssued(target);
                    return true;
                }
                // Fallback: als grote stap-target niet lukt, probeer originele bestemming.
                if (usedLargeStepTarget) {
                    if (movement.walkTo(destination)) {
                        recordPointWalkIssued(destination);
                        return true;
                    }
                    path = movement.getPath(destination);
                    if (path != null && !path.isEmpty()) {
                        path.walk(WALK_OPTIONS);
                        recordPointWalkIssued(destination);
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {}
        try {
            if (Movement.walkTo(target)) {
                recordPointWalkIssued(target);
                return true;
            }
            if (usedLargeStepTarget && Movement.walkTo(destination)) {
                recordPointWalkIssued(destination);
                return true;
            }
        } catch (Throwable t) {
            return false;
        }
        return false;
    }

    // ===================== SMART WALK TOWARD TARGET =====================

    /**
     * Robuuste navigatie naar een center+radius met meerdere fallback-lagen.
     */
    public static boolean walkTowardTarget(WorldPoint center, int radius, WorldPoint pointInRadius, int maxStep) {
        IPlayer local = Players.getLocal();
        if (local == null || center == null) return false;

        WorldPoint myPos = local.getWorldLocation();
        if (myPos == null) return false;

        // Al binnen het gebied? Klaar.
        if (distanceToArea(myPos, center, radius) <= 0) return true;

        WorldPoint dest = (pointInRadius != null) ? pointInRadius : center;

        // Strategie: bij grote afstand laat de Storm pathfinder zelf transports/regions afhandelen
        // via walkToArea. Maar zodra we dichtbij genoeg zijn dat de volgende klik onze
        // eindbestemming wordt, klikken we exact op pointInRadius — anders kiest Storm
        // de dichtstbijzijnde rand-tile en blijven we precies op de hoek hangen.
        int distToArea = distanceToArea(myPos, center, radius);
        int nearThreshold = Math.max(maxStep, radius * 2 + 4);
        boolean preferExactPoint = pointInRadius != null && distToArea <= nearThreshold;

        if (preferExactPoint) {
            // Poging 1a: klik EXACT op het inner-radius punt zodat we niet op de rand belanden.
            try {
                if (walkTo(dest)) return true;
            } catch (Exception ignored) {}
            // Poging 1b: vangnet via walkToArea (transports/castle bypass).
            try {
                if (walkToArea(center, radius)) return true;
            } catch (Exception ignored) {}
        } else {
            // Poging 1a: lange afstand → walkToArea voor transports + castle bypass.
            try {
                if (walkToArea(center, radius)) return true;
            } catch (Exception ignored) {}
            // Poging 1b: anders direct naar het inner-radius punt.
            try {
                if (walkTo(dest)) return true;
            } catch (Exception ignored) {}
        }

        // Poging 2: walkTo direct naar het center-punt (als verschilt van dest)
        if (pointInRadius != null) {
            try {
                if (walkTo(center)) return true;
            } catch (Exception ignored) {}
        }

        // Poging 4: stap-voor-stap in meerdere afstanden proberen
        int dx = dest.getX() - myPos.getX();
        int dy = dest.getY() - myPos.getY();
        int dist = Math.max(Math.abs(dx), Math.abs(dy));
        if (dist <= 0) return true;

        int[] steps = {maxStep, 15, 10, 5};
        for (int stepSize : steps) {
            int step = Math.min(stepSize, dist);
            int stepX = myPos.getX() + (dx * step / dist);
            int stepY = myPos.getY() + (dy * step / dist);
            WorldPoint stepPoint = new WorldPoint(stepX, stepY, myPos.getPlane());
            // Vermijd stappen IN het kasteel of de dining room
            if (isInsideLumbridgeCastle(stepPoint) || isInsideLumbridgeDiningRoom(stepPoint)) continue;
            try {
                if (walkToInternal(stepPoint)) return true;
            } catch (Exception ignored) {}
        }

        // Poging 5: laatste fallback (zelfde pad als interne SDK-fallback; geen extra personalisatie)
        try {
            if (walkToInternal(dest)) {
                return true;
            }
        } catch (Exception ignored) {}

        return false;
    }

    /**
     * Vereenvoudigde versie zonder center/radius — loop naar een enkel punt met fallbacks.
     */
    public static boolean walkTowardTarget(WorldPoint target, int maxStep) {
        if (target == null) return false;
        IPlayer local = Players.getLocal();
        if (local == null) return false;

        WorldPoint myPos = local.getWorldLocation();
        if (myPos == null) return false;
        if (myPos.distanceTo(target) <= 1) return true;

        // Directe walkTo (met castle bypass)
        try {
            if (walkTo(target)) return true;
        } catch (Exception ignored) {}

        // Stap-voor-stap fallback
        int dx = target.getX() - myPos.getX();
        int dy = target.getY() - myPos.getY();
        int dist = Math.max(Math.abs(dx), Math.abs(dy));
        if (dist <= 0) return true;

        int[] steps = {maxStep, 15, 10, 5};
        for (int stepSize : steps) {
            int step = Math.min(stepSize, dist);
            int stepX = myPos.getX() + (dx * step / dist);
            int stepY = myPos.getY() + (dy * step / dist);
            WorldPoint stepPoint = new WorldPoint(stepX, stepY, myPos.getPlane());
            if (isInsideLumbridgeCastle(stepPoint) || isInsideLumbridgeDiningRoom(stepPoint)) continue;
            try {
                if (walkToInternal(stepPoint)) return true;
            } catch (Exception ignored) {}
        }

        return false;
    }

    // ===================== UTILITY =====================

    /**
     * Kies één willekeurig punt binnen de radius van de center (Chebyshev: vierkant).
     */
    public static WorldPoint getRandomPointInRadius(WorldPoint center, int radius) {
        if (center == null || radius < 0) return center;
        int dx = RANDOM.nextInt(2 * radius + 1) - radius;
        int dy = RANDOM.nextInt(2 * radius + 1) - radius;
        return new WorldPoint(
                center.getX() + dx,
                center.getY() + dy,
                center.getPlane()
        );
    }

    /** Afstand van punt tot rand van gebied (0 = binnen gebied). */
    public static int distanceToArea(WorldPoint point, WorldPoint center, int radius) {
        if (point == null || center == null) return Integer.MAX_VALUE;
        int dx = Math.abs(point.getX() - center.getX());
        int dy = Math.abs(point.getY() - center.getY());
        if (point.getPlane() != center.getPlane()) return Integer.MAX_VALUE;
        return Math.max(0, Math.max(dx, dy) - radius);
    }

    public static WorldArea toWorldArea(WorldPoint center, int radius) {
        int size = Math.max(1, radius * 2 + 1);
        return new WorldArea(
                center.getX() - radius,
                center.getY() - radius,
                size,
                size,
                center.getPlane()
        );
    }
}
