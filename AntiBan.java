package com.combatbot;

import net.runelite.api.MenuEntry;
import net.runelite.client.util.Text;
import net.storm.api.domain.actors.INPC;
import net.storm.api.domain.actors.IPlayer;
import net.storm.api.widgets.Tab;
import net.storm.sdk.entities.NPCs;
import net.storm.sdk.entities.Players;
import net.storm.sdk.game.Client;
import net.storm.sdk.input.Keyboard;
import net.storm.sdk.input.Mouse;
import net.storm.sdk.items.Bank;
import net.storm.sdk.widgets.Dialog;
import net.storm.sdk.widgets.Tabs;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Random;

/**
 * Anti-ban systeem met vloeiende camera bewegingen (pijltjes + middenmuisknop),
 * idle pauzes, muisbewegingen, tab-glance (F-toetsen + ESC buiten Tutorial Island;
 * op Tutorial Island: {@link Tabs#open(Tab)} op tab-widgets i.p.v. F-toetsen en misclicks.
 * Voorkomt dubbele camera acties achter elkaar.
 */
public class AntiBan {

    private final Random random = new Random();
    private Instant lastActionTime = Instant.now();
    private int nextActionDelay;
    private final CombatBotConfig config;
    private final CombatBotPaint paint;
    /** Optioneel: per-RSN verschillende timing/gewichten ({@link AccountBehaviorProfileStore}). */
    private AccountBehaviorProfileStore.Profile behaviorProfile;

    // Als we net hebben ingezoomd / naar beneden gekanteld, dwing volgende camera-actie
    // om juist uit te zoomen en top-down / rondom te draaien.
    private boolean forceTopDownNextCamera = false;

    // Track laatste actie type om dubbele camera te voorkomen
    private enum ActionType { NONE, CAMERA, IDLE, MOUSE, MISCLICK, TAB_GLANCE, PLAYER_LOOKUP, SKILL_HOVER }

    /** Sidepanel-tab X (fixed classic) — misclick/tab buiten Tut of fallback. */
    private static final int[] SIDE_PANEL_TAB_X = {528, 562, 596, 630, 664, 698, 732};
    private static final int SIDE_PANEL_TAB_Y = 168;
    private static final int INVENTORY_TAB_INDEX = 3;

    /** Onderste rij zijpaneel (zonder inventory) voor tab-glance / Tut misclick via {@link Tabs#open(Tab)}. */
    private static final Tab[] MAIN_BAR_TABS_EXCEPT_INVENTORY = {
            Tab.COMBAT, Tab.SKILLS, Tab.QUESTS, Tab.EQUIPMENT, Tab.PRAYER, Tab.MAGIC
    };

    /** Andere sidepanel-tabs (OSRS default: F3 = inventory — die slaan we over). Terug: ESC (inventory speler-instelling). */
    private static final int[] TAB_GLANCE_F_KEYS = {
            KeyEvent.VK_F1, KeyEvent.VK_F2, KeyEvent.VK_F4, KeyEvent.VK_F5,
            KeyEvent.VK_F6, KeyEvent.VK_F7, KeyEvent.VK_F8
    };
    private ActionType lastActionType = ActionType.NONE;
    private long lastTabGlanceMs = 0L;
    private long nextTabGlanceCooldownMs = 120_000L;
    private long lastPlayerLookupMs = 0L;
    private long lastMicroMouseMoveMs = 0L;
    private final ArrayDeque<String> recentLookupNames = new ArrayDeque<>();
    private static final int LOOKUP_RECENT_MEMORY = 4;

    // Bewust geen java.awt.Robot meer — die zou de fysieke OS-cursor van de user overnemen.
    // We gebruiken alleen Storm SDK's Mouse.move(x,y) die de user's mouseBehavior-config respecteert
    // (synthetic events in MOUSE_EVENTS-mode, fysiek pas als de user expliciet MOUSE_POS koos).

    /**
     * Achtergrond-fidget: continue mens-achtige micro-bewegingen tussen bot-acties.
     * Pauseert automatisch tijdens echte clicks via {@link #markBotActivity()}.
     */
    private MouseFidgetWorker fidgetWorker;
    private volatile long lastBotActivityMs = 0L;

    public AntiBan(CombatBotConfig config, CombatBotPaint paint) {
        this.config = config;
        this.paint = paint;
        this.nextActionDelay = calculateNextDelay();
    }

    /** Door de plugin aangeroepen na startUp om de fidget-worker te starten. */
    public void startFidgetWorker() {
        if (fidgetWorker != null) return;
        fidgetWorker = new MouseFidgetWorker();
        fidgetWorker.start();
    }

    /** Door de plugin aangeroepen bij shutDown om de fidget-worker netjes te stoppen. */
    public void stopFidgetWorker() {
        if (fidgetWorker != null) {
            fidgetWorker.shutdown();
            fidgetWorker = null;
        }
    }

    /**
     * Markeer dat de bot net een echte actie deed (click/drag/typing/etc.).
     * Fidget-worker geeft daarna ~600 ms ruimte zodat synthetische events niet botsen
     * met de daadwerkelijke gameplay-click.
     */
    public void markBotActivity() {
        lastBotActivityMs = System.currentTimeMillis();
    }

    /**
     * Handler-side hint dat er gevoelige interactie aan de gang is (dialog, NPC-click,
     * widget-knop). Geeft de fidget-worker een wat langere pauze zodat hij niet over
     * de click heen beweegt. Veiliger dan {@link #markBotActivity()} omdat het ook
     * vooruitkijkt: de pauze geldt vanaf NU voor {@code millisAhead} ms.
     */
    public void notifyHandlerAction(String reason, int millisAhead) {
        lastBotActivityMs = System.currentTimeMillis() + Math.max(0, millisAhead - 600);
        if (reason != null && !reason.isEmpty()) {
            // Niet loggen elke call (te veel spam) — alleen op DEBUG bron als verbose nodig is.
        }
    }

    /** Convenience: standaard 1200ms pauze (genoeg voor 1 dialog-click cyclus). */
    public void notifyHandlerAction(String reason) {
        notifyHandlerAction(reason, 1200);
    }

    /** Voor de Debug-test-knop: doe direct één fidget-burst. */
    public String triggerFidgetBurstTest() {
        if (fidgetWorker == null) {
            return "Fidget-worker draait niet (toggle 'Continuous muis-fidget' uitstaan?).";
        }
        return fidgetWorker.runOneBurstNow();
    }

    public void setBehaviorProfile(AccountBehaviorProfileStore.Profile profile) {
        this.behaviorProfile = profile;
    }

    /** Debug-zichtbaarheid: één keer per gebouwd profiel een ANTIBAN-log met de medianen. */
    private long lastHumanProfileGenAtMsLogged = -1;

    /**
     * Lazy snapshot van het opgeslagen {@link HumanProfile} (gegenereerd uit muis-trace).
     * Cache wordt door {@link HumanProfile#getOrLoad()} zelf afgehandeld; we mogen dit per call aanroepen.
     * Retourneert {@code null} als er geen bruikbaar profiel is — callers moeten dan op fallback terugvallen.
     */
    private HumanProfile humanProfile() {
        try {
            HumanProfile p = HumanProfile.getOrLoad();
            if (p != null && p.isUsable()) {
                if (p.generatedAtMs != lastHumanProfileGenAtMsLogged) {
                    lastHumanProfileGenAtMsLogged = p.generatedAtMs;
                    double moveDt = p.moveDtMs != null ? p.moveDtMs.p50 : 0.0;
                    double dwell = p.pressReleaseDwellMs != null ? p.pressReleaseDwellMs.p50 : 0.0;
                    double clickGap = p.interClickGapMs != null ? p.interClickGapMs.p50 : 0.0;
                    DebugLog.log("ANTIBAN", String.format(
                            "Human-profiel ACTIEF — moveDt p50=%.0fms, dwell p50=%.0fms, clickGap p50=%.0fms (samples=%d)",
                            moveDt, dwell, clickGap, p.usedSamples));
                }
                return p;
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String localRsnOrNull() {
        try {
            IPlayer lp = Players.getLocal();
            if (lp != null && lp.getName() != null && !lp.getName().trim().isEmpty()) {
                return Text.removeTags(lp.getName()).trim();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void reportAntiBanAction(String actionKey, String actionLabel) {
        paint.setLastAntiBanAction(actionLabel);
        String rsn = localRsnOrNull();
        if (rsn != null && !rsn.isEmpty()) {
            AccountStateJsonStore.recordAntiBanAction(rsn, actionKey, actionLabel);
        }
        // Pauze de fidget-thread kort zodat hij niet bovenop een click/drag komt zitten.
        markBotActivity();
    }

    public int check() {
        if (!config.antiBanEnabled()) {
            return 0;
        }

        long elapsed = java.time.Duration.between(lastActionTime, Instant.now()).getSeconds();
        if (elapsed < nextActionDelay) {
            return 0;
        }

        // KRITIEKE INTERFACES — geen anti-ban actie tijdens een dialog of bank/deposit-flow.
        // Anti-ban acties als doRandomMouseMovement, doSkillHover, doTabGlance, doPlayerLookup en
        // doMisClick verplaatsen de muis of openen andere tabs. Als de bot tegelijkertijd een
        // Dialog.chooseOption(...) of Bank-click aan het uitvoeren is, kan de cursor net naast
        // de widget staan op het moment van de click → de actie gaat verloren en de bot zit vast
        // in een retry-loop (zie Vampire Slayer "stake ontbreekt" loop bij Dr Harlow). Door hier
        // simpelweg te skippen blijft de timing van de volgende anti-ban actie gewoon door tikken.
        try {
            if (Dialog.isOpen()) {
                lastActionTime = Instant.now();
                return 0;
            }
            if (Bank.isOpen()) {
                lastActionTime = Instant.now();
                return 0;
            }
        } catch (Throwable ignored) {
        }
        // Respecteer ook handler-side activity (bv. notifyHandlerAction("dialog-option-...", 1500))
        // voor gevallen waar de dialog net (her)opent of net dichtgegaan is en we nog stabilisatie-
        // tijd nodig hebben.
        if (System.currentTimeMillis() < lastBotActivityMs + 600) {
            return 0;
        }

        int delay = performRandomAction();
        lastActionTime = Instant.now();
        nextActionDelay = calculateNextDelay();
        if (delay < 0) {
            return 0;
        }
        return Math.max(delay, 200);
    }

    private int performRandomAction() {
        int cameraWeight = scaledWeight(35);
        int idleWeight = scaledWeight(25);
        int mouseWeight = scaledWeight(25);
        int misclickWeight = scaledWeight(15);
        int tabWeight = scaledWeight(10);
        int lookupWeight = scaledWeight(10);
        int skillHoverWeight = scaledWeight(20);

        int totalWeight = 0;
        if (config.cameraMovement()) totalWeight += cameraWeight;
        if (config.idleChecks()) totalWeight += idleWeight;
        if (config.randomMouseMovement()) totalWeight += mouseWeight;
        if (config.misClickEnabled()) totalWeight += misclickWeight;
        boolean tabGlanceAllowed = config.tabGlanceEnabled()
                && (System.currentTimeMillis() - lastTabGlanceMs) >= nextTabGlanceCooldownMs;
        // Lager gewicht + cooldown zodat tab-wissel minder vaak gebeurt.
        if (tabGlanceAllowed) totalWeight += tabWeight;
        if (config.playerLookupAntibanEnabled() && isPlayerLookupReady()) totalWeight += lookupWeight;
        boolean skillHoverAllowed = config.skillHoverEnabled() && lastActionType != ActionType.SKILL_HOVER;
        if (skillHoverAllowed) totalWeight += skillHoverWeight;

        if (totalWeight == 0) return -1;

        // Als vorige actie camera was, herbereken gewichten zonder camera
        int effectiveWeight = totalWeight;
        boolean skipCamera = (lastActionType == ActionType.CAMERA) && config.cameraMovement();
        if (skipCamera) {
            effectiveWeight -= cameraWeight;
            if (effectiveWeight <= 0) {
                lastActionType = ActionType.NONE;
                return -1;
            }
        }

        int roll = random.nextInt(effectiveWeight);
        int cumulative = 0;

        if (config.cameraMovement() && !skipCamera) {
            cumulative += cameraWeight;
            if (roll < cumulative) {
                lastActionType = ActionType.CAMERA;
                return doCameraMovement();
            }
        }

        if (config.idleChecks()) {
            cumulative += idleWeight;
            if (roll < cumulative) {
                lastActionType = ActionType.IDLE;
                return doIdlePause();
            }
        }

        if (config.randomMouseMovement()) {
            cumulative += mouseWeight;
            if (roll < cumulative) {
                lastActionType = ActionType.MOUSE;
                return doRandomMouseMovement();
            }
        }

        if (tabGlanceAllowed) {
            cumulative += tabWeight;
            if (roll < cumulative) {
                lastActionType = ActionType.TAB_GLANCE;
                return doTabGlance();
            }
        }

        if (config.playerLookupAntibanEnabled() && isPlayerLookupReady()) {
            cumulative += lookupWeight;
            if (roll < cumulative) {
                lastActionType = ActionType.PLAYER_LOOKUP;
                return doRandomPlayerLookup(false);
            }
        }

        if (skillHoverAllowed) {
            cumulative += skillHoverWeight;
            if (roll < cumulative) {
                lastActionType = ActionType.SKILL_HOVER;
                int r = doSkillHover();
                return r > 0 ? r : 250;
            }
        }

        if (config.misClickEnabled()) {
            lastActionType = ActionType.MISCLICK;
            return doMisClick();
        }

        return -1;
    }

    public int triggerPlayerLookupTest() {
        return Math.max(200, doRandomPlayerLookup(true));
    }

    /**
     * Forceer één micro-mouse beweging met de huidige config + (indien aanwezig) human-profiel.
     * Bypasst cooldown + kans-roll zodat je in de Debug-tab direct ziet of het werkt.
     * Retourneert een leesbare status-string voor de UI/popup.
     */
    public String triggerHumanMicroMouseTest() {
        long now = System.currentTimeMillis();
        Canvas canvas = Client.getCanvas();
        HumanProfile hp = humanProfile();
        boolean usedHuman = hp != null;

        int microCooldownMs = scaleByIntensity(12_000);
        if (usedHuman && hp.interClickGapMs != null && hp.interClickGapMs.isUsable()) {
            int sampled = hp.sampleInterClickGapMs(random, microCooldownMs);
            int humanCool = (int) Math.max(1500, Math.min(20_000L, (long) sampled * 6L));
            microCooldownMs = (microCooldownMs + humanCool) / 2;
        }

        int stepMaxX = 24, stepMaxY = 18;
        if (usedHuman && hp.moveStepPx != null && hp.moveStepPx.isUsable()) {
            int humanP95 = (int) Math.round(hp.moveStepPx.p95);
            int humanFactor = Math.max(8, Math.min(40, 6 + humanP95 * 6));
            stepMaxX = (stepMaxX + humanFactor) / 2;
            stepMaxY = (stepMaxY + humanFactor) / 2;
        }

        if (canvas == null) {
            DebugLog.log("ANTIBAN", "Test micro-mouse: canvas=null (niet ingelogd?). human-profiel="
                    + (usedHuman ? "JA" : "NEE"));
            return "Canvas niet beschikbaar — log eerst in. (human-profiel="
                    + (usedHuman ? "geladen" : "ontbreekt") + ")";
        }

        Point cur = currentMousePoint();
        int baseX = cur != null ? cur.x : randomRange(140, 640);
        int baseY = cur != null ? cur.y : randomRange(110, 420);
        // Forceer minstens ±10 px verplaatsing zodat de gebruiker de cursor visueel ziet bewegen.
        int dxSign = random.nextBoolean() ? 1 : -1;
        int dySign = random.nextBoolean() ? 1 : -1;
        int dx = dxSign * Math.max(10, randomRange(10, Math.max(11, stepMaxX + 1)));
        int dy = dySign * Math.max(8, randomRange(8, Math.max(9, stepMaxY + 1)));
        int toX = Math.max(8, Math.min(748, baseX + dx));
        int toY = Math.max(8, Math.min(498, baseY + dy));
        boolean moved = moveOsCursorTo(canvas, toX, toY);
        lastMicroMouseMoveMs = now;

        String label = usedHuman
                ? "Muis micro-move TEST (human-profiel via Storm.Mouse.moved)"
                : "Muis micro-move TEST (default via Storm.Mouse.moved)";
        reportAntiBanAction("MOUSE_MICRO_TEST", label);
        DebugLog.log("ANTIBAN", String.format(
                "Test micro-mouse: human=%s cool=%dms stepMaxX=%d stepMaxY=%d move=(%d,%d->%d,%d) via=Storm.Mouse.moved ok=%s",
                usedHuman ? "JA" : "NEE", microCooldownMs, stepMaxX, stepMaxY, baseX, baseY, toX, toY,
                moved ? "JA" : "NEE"));
        return String.format(
                "human-profiel=%s · cooldown=%dms · stepMaxX=%d · stepMaxY=%d · van (%d,%d) naar (%d,%d) · via Storm.Mouse.moved (jouw OS-cursor blijft staan)",
                usedHuman ? "geladen" : "ontbreekt",
                microCooldownMs, stepMaxX, stepMaxY, baseX, baseY, toX, toY);
    }

    /**
     * Camera beweging — kiest random tussen pijltjestoetsen en middenmuisknop drag.
     * Slechts 1x per anti-ban check (nooit 2x achter elkaar).
     */
    private int doCameraMovement() {
        // Speciaal geval: vorige camera-actie was "inzoomen / naar beneden",
        // dus nu forceren we een uitzoom + top-down/rondom view.
        if (forceTopDownNextCamera) {
            forceTopDownNextCamera = false;
            return doTopDownCameraReset();
        }

        // 50% kans op middenmuisknop drag, 50% pijltjestoetsen
        if (random.nextBoolean()) {
            return doMiddleMouseCameraDrag();
        }
        return doKeyboardCameraMovement();
    }

    /**
     * Middenmuisknop camera drag — houdt MMB ingedrukt, beweegt muis random, laat los.
     * Simuleert menselijke camera rotatie.
     */
    private int doMiddleMouseCameraDrag() {
        Canvas canvas = Client.getCanvas();
        if (canvas == null) return doKeyboardCameraMovement();

        int startX = randomRange(280, 480);
        int startY = randomRange(180, 320);

        // Kies een richting: horizontale sweep (menselijk camera pan)
        boolean goRight = random.nextBoolean();
        int dragDistMin = config.mmbDragDistanceMin();
        int dragDistMax = config.mmbDragDistanceMax();
        if (dragDistMax <= dragDistMin) dragDistMax = dragDistMin + 20;
        int totalDragX = randomRange(dragDistMin, dragDistMax) * (goRight ? 1 : -1);
        int totalDragY = randomRange(-10, 10); // minimale verticale drift

        int minDur = config.cameraDurationMin();
        int maxDur = config.cameraDurationMax();
        if (maxDur <= minDur) maxDur = minDur + 500;
        int totalDuration = randomRange(minDur, maxDur);

        // Stap snelheid uit config (hoger = langzamer, vloeiender)
        int stepDelayMin = config.mmbDragSpeedMin();
        int stepDelayMax = config.mmbDragSpeedMax();
        if (stepDelayMax <= stepDelayMin) stepDelayMax = stepDelayMin + 10;

        // Bereken stappen op basis van totale duur en stap snelheid
        int avgStepDelay = (stepDelayMin + stepDelayMax) / 2;
        int steps = Math.max(6, totalDuration / avgStepDelay);

        try {
            // Beweeg muis eerst naar startpositie
            Mouse.moved(startX, startY, canvas, System.currentTimeMillis());
            Thread.sleep(randomRange(80, 200));

            // Druk MMB in
            Mouse.pressed(startX, startY, canvas, System.currentTimeMillis(), 2);
            Thread.sleep(randomRange(60, 140));

            int currentX = startX;
            int currentY = startY;

            for (int i = 1; i <= steps; i++) {
                // Ease-in-out via sinusoïde: langzaam starten, versnellen, langzaam stoppen
                double t = (double) i / steps;
                double eased = (1.0 - Math.cos(t * Math.PI)) / 2.0;

                int targetX = startX + (int)(totalDragX * eased);
                int targetY = startY + (int)(totalDragY * eased);

                // Kleine menselijke jitter (±1px)
                targetX += randomRange(-1, 1);
                targetY += randomRange(-1, 1);

                targetX = Math.max(50, Math.min(710, targetX));
                targetY = Math.max(50, Math.min(430, targetY));

                if (targetX != currentX || targetY != currentY) {
                    Mouse.moved(targetX, targetY, canvas, System.currentTimeMillis());
                    currentX = targetX;
                    currentY = targetY;
                }

                // Configureerbare stap vertraging — langzamer = vloeiender
                Thread.sleep(randomRange(stepDelayMin, stepDelayMax));
            }

            // Langere pauze voor loslaten (menselijk)
            Thread.sleep(randomRange(80, 200));
            Mouse.released(currentX, currentY, canvas, System.currentTimeMillis(), 2);

        } catch (InterruptedException ignored) {
            // Ensure release
        }

        reportAntiBanAction("CAMERA_MMB", "Camera: MMB drag");
        return totalDuration + randomRange(150, 500);
    }

    /**
     * Pijltjestoetsen camera beweging — korte pulsen.
     */
    private int doKeyboardCameraMovement() {
        int action = random.nextInt(4);
        int keyCode;
        String label;

        switch (action) {
            case 0: keyCode = KeyEvent.VK_LEFT;  label = "Camera links draaien"; break;
            case 1: keyCode = KeyEvent.VK_RIGHT; label = "Camera rechts draaien"; break;
            case 2: keyCode = KeyEvent.VK_UP;    label = "Camera omhoog"; break;
            case 3:
                keyCode = KeyEvent.VK_DOWN;
                label = "Camera omlaag";
                // Deze actie zien we als "inzoomen / naar beneden draaien" → volgende
                // anti-ban camera-actie moet uitzoomen en top-down/rondom gaan kijken.
                forceTopDownNextCamera = true;
                break;
            default: keyCode = KeyEvent.VK_LEFT;  label = "Camera links"; break;
        }

        int minDur = config.cameraDurationMin();
        int maxDur = config.cameraDurationMax();
        if (maxDur <= minDur) maxDur = minDur + 500;

        // Enkele vloeiende puls i.p.v. meerdere (voorkomt dubbel-gevoel)
        int totalDuration = randomRange(minDur, maxDur);

        try {
            Keyboard.pressed(keyCode);
            Thread.sleep(totalDuration);
            Keyboard.released(keyCode);
        } catch (InterruptedException ignored) {
            Keyboard.released(keyCode);
        }

        reportAntiBanAction("CAMERA_KEYS", label);
        return totalDuration + randomRange(100, 400);
    }

    /**
     * Geforceerde camera-reset na een neerwaartse / inzoom-actie:
     * - eerst flink omhoog kantelen (uitzoomen / top-down)
     * - daarna kort links/rechts draaien om "om het karakter heen" te kijken.
     */
    private int doTopDownCameraReset() {
        int minDur = config.cameraDurationMin();
        int maxDur = config.cameraDurationMax();
        if (maxDur <= minDur) maxDur = minDur + 500;

        // Iets langere duur zodat we echt top-down komen.
        int tiltDuration = randomRange(minDur + 200, maxDur + 400);

        int totalDuration = 0;

        try {
            // Eerst uitzoomen / omhoog kantelen
            Keyboard.pressed(KeyEvent.VK_UP);
            Thread.sleep(tiltDuration);
            Keyboard.released(KeyEvent.VK_UP);
            totalDuration += tiltDuration;

            // Korte pauze
            int pause = randomRange(80, 200);
            Thread.sleep(pause);
            totalDuration += pause;

            // Dan een korte draai links of rechts om "omheen" te kijken
            boolean goRight = random.nextBoolean();
            int rotateKey = goRight ? KeyEvent.VK_RIGHT : KeyEvent.VK_LEFT;
            int rotateDuration = randomRange(minDur / 2, maxDur / 2);

            Keyboard.pressed(rotateKey);
            Thread.sleep(rotateDuration);
            Keyboard.released(rotateKey);
            totalDuration += rotateDuration;

        } catch (InterruptedException ignored) {
            // Zorg dat toetsen sowieso losgelaten worden
            Keyboard.released(KeyEvent.VK_UP);
            Keyboard.released(KeyEvent.VK_LEFT);
            Keyboard.released(KeyEvent.VK_RIGHT);
        }

        reportAntiBanAction("CAMERA_TOPDOWN", "Camera: top-down + rondom");
        return totalDuration + randomRange(150, 500);
    }

    private int doIdlePause() {
        int pauseType = random.nextInt(3);

        switch (pauseType) {
            case 0:
                reportAntiBanAction("IDLE_SHORT", "Korte pauze");
                return randomRange(1000, 3000);
            case 1:
                reportAntiBanAction("IDLE_MEDIUM", "Middel pauze");
                return randomRange(3000, 8000);
            case 2:
                reportAntiBanAction("IDLE_LONG", "Lange pauze");
                return randomRange(5000, 15000);
            default:
                return 1000;
        }
    }

    private int doRandomMouseMovement() {
        Canvas canvas = Client.getCanvas();

        int moveType = random.nextInt(3);

        switch (moveType) {
            case 0: {
                long time = System.currentTimeMillis();
                int x = randomRange(100, 700);
                int y = randomRange(100, 450);
                Mouse.moved(x, y, canvas, time);
                reportAntiBanAction("MOUSE_MOVE", "Muis bewegen");
                return randomRange(200, 600);
            }
            case 1: {
                long time = System.currentTimeMillis();
                int edgeX = random.nextBoolean() ? randomRange(0, 30) : randomRange(730, 760);
                int edgeY = randomRange(50, 450);
                Mouse.moved(edgeX, edgeY, canvas, time);
                reportAntiBanAction("MOUSE_EDGE", "Muis naar rand");
                return randomRange(400, 1000);
            }
            case 2: {
                long time2 = System.currentTimeMillis();
                int hx = randomRange(150, 600);
                int hy = randomRange(100, 400);
                Mouse.moved(hx, hy, canvas, time2);
                reportAntiBanAction("MOUSE_HOVER", "Muis hover");
                return randomRange(300, 700);
            }
            default:
                return 200;
        }
    }

    /**
     * Wissel naar een andere sidepanel-tab, kort kijken, terug naar inventory.
     * Op Tutorial Island: F-toetsen falen vaak — {@link Tabs#open(Tab)} klikt de widget (geen handmatige pixel-meting).
     */
    private int doTabGlance() {
        lastTabGlanceMs = System.currentTimeMillis();
        int lo = scaleByIntensity(90_000);
        int hi = Math.max(lo + 1, scaleByIntensity(180_001));
        nextTabGlanceCooldownMs = randomRange(lo, hi);
        try {
            IPlayer lp = Players.getLocal();
            if (lp != null && TutorialModeHandler.isOnTutorialIsland(lp)) {
                return doTabGlanceTutorialIsland();
            }
        } catch (Throwable ignored) {
        }

        int key = TAB_GLANCE_F_KEYS[random.nextInt(TAB_GLANCE_F_KEYS.length)];
        try {
            Keyboard.pressed(key);
            Thread.sleep(randomRange(55, 130));
            Keyboard.released(key);
            Thread.sleep(randomRange(100, 260));

            int glanceMs = randomRange(2000, 4001);
            Thread.sleep(glanceMs);

            Keyboard.pressed(KeyEvent.VK_ESCAPE);
            Thread.sleep(randomRange(55, 120));
            Keyboard.released(KeyEvent.VK_ESCAPE);
            Thread.sleep(randomRange(70, 180));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            try {
                Keyboard.released(key);
                Keyboard.released(KeyEvent.VK_ESCAPE);
            } catch (Throwable ignored) {}
        }

        reportAntiBanAction("TAB_GLANCE_KEYS", "Tab: F-toets → ESC (inv)");
        return randomRange(250, 550);
    }

    /**
     * Skill hover anti-ban: opent de Skills-tab, beweegt soepel naar het icoon van de actieve
     * skill (of een willekeurig icoon als de skill niet te bepalen is) en blijft daar 0.8–2.5s
     * staan. Géén klik; dit is puur "ik kijk even hoe ver ik ben". Daarna wordt de Inventory-tab
     * weer geopend zodat de bot z'n volgende actie kan doen.
     */
    private int doSkillHover() {
        Canvas canvas = Client.getCanvas();
        if (canvas == null) return -1;
        try {
            Tabs.open(Tab.SKILLS);
            Thread.sleep(randomRange(220, 480));

            // Skills interface = group 320. Probeer een specifiek skill-icoon te raken; anders
            // val terug op de bounds van het hele paneel.
            Rectangle target = pickSkillIconBoundsForActiveSkill();
            if (target == null) {
                target = readWidgetBounds(320, 0);
            }

            int toX, toY;
            if (target != null && target.width > 4 && target.height > 4) {
                int padX = Math.max(2, target.width / 6);
                int padY = Math.max(2, target.height / 6);
                toX = target.x + padX + random.nextInt(Math.max(1, target.width - 2 * padX));
                toY = target.y + padY + random.nextInt(Math.max(1, target.height - 2 * padY));
            } else {
                int cw = Math.max(50, canvas.getWidth());
                int ch = Math.max(50, canvas.getHeight());
                toX = randomRange(Math.max(80, cw - 220), Math.max(120, cw - 40));
                toY = randomRange(Math.max(80, ch / 2 - 80), Math.max(180, ch / 2 + 60));
            }

            int durationMs = 260 + random.nextInt(341); // 260..600 ms
            smoothMoveTo(canvas, toX, toY, durationMs);
            Thread.sleep(randomRange(800, 2501));

            // Soms (60%) een tweede subtiele jitter binnen het icoon — alsof je je cursor verlegt
            if (random.nextInt(100) < 60) {
                int jx = toX + randomRange(-6, 7);
                int jy = toY + randomRange(-6, 7);
                smoothMoveTo(canvas, jx, jy, 140 + random.nextInt(200));
                Thread.sleep(randomRange(220, 700));
            }

            // Terug naar inventory zodat normale bot-flow door kan
            Tabs.open(Tab.INVENTORY);
            Thread.sleep(randomRange(120, 300));

        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            try { Tabs.open(Tab.INVENTORY); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            DebugLog.log("ANTIBAN", "Skill hover error: " + t.getMessage());
        }

        String label = "Skill hover" + activeSkillLabelSuffix();
        reportAntiBanAction("SKILL_HOVER", label);
        return randomRange(280, 650);
    }

    /** Geeft de bounds van het skill-icoon dat overeenkomt met de actieve skill, of {@code null}. */
    private Rectangle pickSkillIconBoundsForActiveSkill() {
        String name = paint != null ? paint.getActiveSkillName() : null;
        if (name == null || name.trim().isEmpty()) return null;
        String n = name.trim().toLowerCase();
        // Mapping van bot-skill-label naar OSRS skill-tab child-id binnen interface group 320.
        // De skills-interface heeft per skill een container-child. We gebruiken bekende
        // child-indices voor de iconen die de bot daadwerkelijk traint.
        int childId = -1;
        if (n.startsWith("attack")) childId = 1;
        else if (n.startsWith("strength")) childId = 11;
        else if (n.startsWith("defence") || n.startsWith("defense")) childId = 21;
        else if (n.startsWith("hitpoints") || n.contains("hp")) childId = 31;
        else if (n.startsWith("ranged") || n.contains("range")) childId = 41;
        else if (n.startsWith("prayer")) childId = 51;
        else if (n.startsWith("magic") || n.startsWith("mage")) childId = 61;
        else if (n.startsWith("cooking")) childId = 71;
        else if (n.startsWith("woodcutting") || n.contains("woodcut")) childId = 81;
        else if (n.startsWith("fishing") || n.contains("fish")) childId = 116;
        else if (n.startsWith("firemaking") || n.contains("fire")) childId = 91;
        else if (n.startsWith("crafting")) childId = 101;
        else if (n.startsWith("smithing")) childId = 111;
        else if (n.startsWith("mining") || n.contains("mine")) childId = 126;
        else if (n.startsWith("combat") || n.startsWith("imps") || n.startsWith("giants")
                || n.startsWith("barbarian") || n.contains("barb")) {
            // Combat-achtige rotaties: pak Attack/Strength/Defence willekeurig
            int[] combatChildren = {1, 11, 21};
            childId = combatChildren[random.nextInt(combatChildren.length)];
        }
        if (childId < 0) return null;
        return readWidgetBounds(320, childId);
    }

    private String activeSkillLabelSuffix() {
        try {
            String name = paint != null ? paint.getActiveSkillName() : null;
            if (name != null && !name.trim().isEmpty()) {
                return " (" + name.trim() + ")";
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private Rectangle readWidgetBounds(int group, int child) {
        try {
            net.storm.api.domain.widgets.IWidget w = net.storm.sdk.widgets.Widgets.get(group, child);
            if (w == null || w.isHidden()) return null;
            Rectangle b = w.getBounds();
            if (b == null || b.width <= 0 || b.height <= 0) return null;
            return b;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Tab-glance op Tut: Storm opent tabs via interface-widgets (niet F-toetsen, geen canvas-pixelankers). */
    private int doTabGlanceTutorialIsland() {
        Tab glance = MAIN_BAR_TABS_EXCEPT_INVENTORY[random.nextInt(MAIN_BAR_TABS_EXCEPT_INVENTORY.length)];
        try {
            Tabs.open(glance);
            Thread.sleep(randomRange(2000, 4001));
            Tabs.open(Tab.INVENTORY);
            Thread.sleep(randomRange(80, 220));
            if (random.nextInt(100) < 35) {
                Keyboard.pressed(KeyEvent.VK_ESCAPE);
                Thread.sleep(randomRange(55, 120));
                Keyboard.released(KeyEvent.VK_ESCAPE);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            try {
                Keyboard.released(KeyEvent.VK_ESCAPE);
            } catch (Throwable ignored) {}
        }
        reportAntiBanAction("TAB_GLANCE_TUT", "Tab: Tabs.open (Tut)");
        return randomRange(280, 650);
    }

    /**
     * Misclick — rechtermuisklik op NPC's, spelers, inventory, minimap etc.
     */
    private int doMisClick() {
        if (random.nextInt(100) >= config.misClickPercent()) {
            return -1;
        }

        Canvas canvas = Client.getCanvas();
        int clickType = random.nextInt(6);

        switch (clickType) {
            case 0: {
                INPC randomNpc = NPCs.getNearest(npc ->
                        npc != null && npc.getName() != null &&
                                !npc.getName().equalsIgnoreCase(config.monsterName()) &&
                                npc.getWorldLocation().distanceTo(Players.getLocal().getWorldLocation()) < 10
                );
                if (randomNpc != null && randomNpc.getCanvasTilePoly() != null) {
                    Rectangle bounds = randomNpc.getCanvasTilePoly().getBounds();
                    int cx = bounds.x + bounds.width / 2;
                    int cy = bounds.y + bounds.height / 2;
                    Mouse.click(cx, cy, false);
                    reportAntiBanAction("MISCLICK_NPC", "Misclick: NPC rechtsklk");
                    try { Thread.sleep(randomRange(400, 900)); } catch (InterruptedException ignored) {}
                    Mouse.click(randomRange(300, 500), randomRange(300, 400), true);
                    return randomRange(600, 1500);
                }
            }

            case 1: {
                IPlayer nearbyPlayer = Players.getNearest(p ->
                        p != null && p.getName() != null &&
                                !p.equals(Players.getLocal()) &&
                                p.getWorldLocation().distanceTo(Players.getLocal().getWorldLocation()) < 10
                );
                if (nearbyPlayer != null && nearbyPlayer.getCanvasTilePoly() != null) {
                    Rectangle bounds = nearbyPlayer.getCanvasTilePoly().getBounds();
                    int cx = bounds.x + bounds.width / 2;
                    int cy = bounds.y + bounds.height / 2;
                    Mouse.click(cx, cy, false);
                    reportAntiBanAction("MISCLICK_PLAYER", "Misclick: speler rechtsklk");
                    try { Thread.sleep(randomRange(500, 1200)); } catch (InterruptedException ignored) {}
                    Mouse.click(randomRange(300, 500), randomRange(300, 400), true);
                    return randomRange(600, 1500);
                }
            }

            case 2: {
                if (canvas != null) {
                    int slotX = 580 + (randomRange(0, 4) * 42);
                    int slotY = 228 + (randomRange(0, 7) * 36);
                    Mouse.click(slotX, slotY, false);
                    reportAntiBanAction("MISCLICK_INV", "Misclick: inv rechtsklk");
                    try { Thread.sleep(randomRange(300, 700)); } catch (InterruptedException ignored) {}
                    Mouse.click(randomRange(300, 500), randomRange(300, 400), true);
                    return randomRange(400, 1000);
                }
                return -1;
            }

            case 3: {
                try {
                    IPlayer lp = Players.getLocal();
                    if (lp != null && TutorialModeHandler.isOnTutorialIsland(lp)) {
                        Tab t = MAIN_BAR_TABS_EXCEPT_INVENTORY[random.nextInt(MAIN_BAR_TABS_EXCEPT_INVENTORY.length)];
                        Tabs.open(t);
                        reportAntiBanAction("MISCLICK_TAB_TUT", "Misclick: tab Tabs.open (Tut)");
                    } else {
                        int tabIndex = random.nextInt(SIDE_PANEL_TAB_X.length);
                        Mouse.click(SIDE_PANEL_TAB_X[tabIndex], SIDE_PANEL_TAB_Y, false);
                        reportAntiBanAction("MISCLICK_TAB", "Misclick: tab rechtsklk");
                    }
                } catch (Throwable ignored) {
                    int tabIndex = random.nextInt(SIDE_PANEL_TAB_X.length);
                    Mouse.click(SIDE_PANEL_TAB_X[tabIndex], SIDE_PANEL_TAB_Y, false);
                    reportAntiBanAction("MISCLICK_TAB", "Misclick: tab rechtsklk");
                }
                try { Thread.sleep(randomRange(300, 600)); } catch (InterruptedException ignored) {}
                Mouse.click(randomRange(300, 500), randomRange(300, 400), true);
                return randomRange(400, 1000);
            }

            case 4: {
                if (canvas != null) {
                    int minimapX = 644 + randomRange(-30, 30);
                    int minimapY = 84 + randomRange(-30, 30);
                    Mouse.moved(minimapX, minimapY, canvas, System.currentTimeMillis());
                    reportAntiBanAction("MISCLICK_MINIMAP", "Misclick: minimap hover");
                    return randomRange(300, 700);
                }
                return -1;
            }

            case 5: {
                int rx = randomRange(50, 500);
                int ry = randomRange(50, 350);
                Mouse.click(rx, ry, false);
                reportAntiBanAction("MISCLICK_RANDOM", "Misclick: random rechtsklk");
                try { Thread.sleep(randomRange(400, 800)); } catch (InterruptedException ignored) {}
                Mouse.click(randomRange(300, 500), randomRange(300, 400), true);
                return randomRange(300, 800);
            }

            default:
                return -1;
        }
    }

    private boolean isPlayerLookupReady() {
        long elapsed = System.currentTimeMillis() - lastPlayerLookupMs;
        return elapsed >= randomRange(70_000, 150_000);
    }

    private int doRandomPlayerLookup(boolean forced) {
        IPlayer local = Players.getLocal();
        if (local == null || local.getWorldLocation() == null) {
            return forced ? 700 : -1;
        }

        java.util.function.Predicate<IPlayer> baseFilter = p ->
                p != null
                        && p.getName() != null
                        && !p.equals(local)
                        && p.getWorldLocation() != null
                        && p.getCanvasTilePoly() != null;

        // Belangrijk: skip spelers waarvan het model-klikpunt achter UI valt (inventory,
        // minimap, chatbox, side panel). Anders gaat de rechtsklik op de UI i.p.v. de speler.
        java.util.function.Predicate<IPlayer> visibleClickPoint = p -> {
            Point cp = computeModelClickPoint(p);
            if (cp == null) return false;
            return !isPointBlockedByUi(cp.x, cp.y);
        };

        List<IPlayer> candidates = Players.getAll(p ->
                baseFilter.test(p)
                        && p.getWorldLocation().distanceTo(local.getWorldLocation()) <= 7
                        && !recentLookupNames.contains(p.getName().toLowerCase())
                        && visibleClickPoint.test(p)
        );

        if ((candidates == null || candidates.isEmpty()) && forced) {
            candidates = Players.getAll(p ->
                    baseFilter.test(p)
                            && p.getWorldLocation().distanceTo(local.getWorldLocation()) <= 14
                            && visibleClickPoint.test(p)
            );
        }

        if (candidates == null || candidates.isEmpty()) {
            // Mogelijk dat er WEL spelers zijn maar allemaal achter UI vielen — log dit zodat
            // de user begrijpt waarom er niets gebeurt.
            int rawCount = (int) Players.getAll(baseFilter).size();
            String msg = forced
                    ? "Lookup test: geen bruikbare speler (raw=" + rawCount + ", allemaal achter UI of buiten range)"
                    : "Lookup: geen bruikbare speler (raw=" + rawCount + ")";
            reportAntiBanAction("LOOKUP_NO_PLAYER", msg);
            DebugLog.log("LOOKUP", msg);
            return forced ? 1000 : -1;
        }

        IPlayer target = candidates.get(random.nextInt(candidates.size()));
        if (target == null || target.getCanvasTilePoly() == null) {
            return forced ? 800 : -1;
        }
        if (!target.hasAction("Lookup")) {
            reportAntiBanAction("LOOKUP_NO_OPTION", forced ? "Lookup test: optie ontbreekt" : "Lookup: optie ontbreekt");
            return forced ? 900 : -1;
        }

        Point clickPt = computeModelClickPoint(target);
        if (clickPt == null) {
            reportAntiBanAction("LOOKUP_NO_CLICKPOINT", forced ? "Lookup test: geen klikpunt" : "Lookup: geen klikpunt");
            return forced ? 800 : -1;
        }
        int targetX = clickPt.x + randomRange(-3, 4);
        int targetY = clickPt.y + randomRange(-4, 5);
        targetX = Math.max(8, Math.min(745, targetX));
        targetY = Math.max(8, Math.min(495, targetY));
        DebugLog.log("LOOKUP", "[1/4] Target=" + target.getName() + " click=(" + targetX + "," + targetY + ")");
        smoothMoveMouseHuman(targetX, targetY);

        try {
            Thread.sleep(randomRange(50, 120));
        } catch (InterruptedException ignored) {}

        // Open contextmenu met max 2 pogingen; refresh clickpoint per poging.
        boolean menuOpen = false;
        long pollStart = 0L;
        int rcAttempts = 0;
        while (rcAttempts < 2 && !menuOpen) {
            rcAttempts++;
            int rcX = targetX;
            int rcY = targetY;

            try {
                if (target.getCanvasTilePoly() != null) {
                    var freshCp = target.getClickPoint();
                    if (freshCp != null) {
                        rcX = freshCp.getX() + randomRange(-3, 4);
                        rcY = freshCp.getY() + randomRange(-4, 5);
                    } else {
                        Rectangle b2 = target.getCanvasTilePoly().getBounds();
                        rcX = b2.x + Math.max(3, b2.width / 2) + randomRange(-3, 4);
                        rcY = b2.y + Math.max(3, b2.height / 2) + randomRange(-4, 5);
                    }
                    rcX = Math.max(8, Math.min(745, rcX));
                    rcY = Math.max(8, Math.min(495, rcY));
                }
            } catch (Throwable ignored) {
            }

            // Retry: cursor eerst opnieuw naar verse coords.
            if (rcAttempts > 1 && (rcX != targetX || rcY != targetY)) {
                smoothMoveMouseHuman(rcX, rcY);
                try {
                    Thread.sleep(randomRange(40, 90));
                } catch (InterruptedException ignored) {
                }
            }

            Mouse.click(rcX, rcY, false);
            DebugLog.log("LOOKUP", "[2/4] Right-click verzonden attempt=" + rcAttempts
                    + " coords=(" + rcX + "," + rcY + ")");

            pollStart = System.currentTimeMillis();
            while (System.currentTimeMillis() - pollStart < 500) {
                if (isContextMenuOpen()) {
                    menuOpen = true;
                    break;
                }
                try {
                    Thread.sleep(25);
                } catch (InterruptedException ignored) {
                }
            }
            DebugLog.log("LOOKUP", "[3/4] Menu open=" + menuOpen + " na "
                    + (System.currentTimeMillis() - pollStart) + "ms (attempt " + rcAttempts + ")");

            if (!menuOpen && rcAttempts < 2) {
                try {
                    Thread.sleep(randomRange(150, 280));
                } catch (InterruptedException ignored) {
                }
            }
        }

        if (!menuOpen) {
            reportAntiBanAction("LOOKUP_NO_MENU", forced ? "Lookup test: menu opende niet" : "Lookup: menu opende niet");
            lastPlayerLookupMs = System.currentTimeMillis();
            return forced ? 800 : -1;
        }

        // Hover naar Lookup-rij + klik er vervolgens op.
        boolean hoveredLookup = moveMouseToLookupMenuEntry(target.getName());
        DebugLog.log("LOOKUP", "[4/4] Hover Lookup attempt1=" + hoveredLookup);
        if (!hoveredLookup && isContextMenuOpen()) {
            try {
                Thread.sleep(randomRange(120, 200));
            } catch (InterruptedException ignored) {}
            hoveredLookup = moveMouseToLookupMenuEntry(target.getName());
            DebugLog.log("LOOKUP", "[4b/4] Hover Lookup retry=" + hoveredLookup);
        }
        if (!hoveredLookup || !isContextMenuOpen()) {
            try {
                Keyboard.pressed(KeyEvent.VK_ESCAPE);
                Thread.sleep(randomRange(50, 110));
                Keyboard.released(KeyEvent.VK_ESCAPE);
            } catch (InterruptedException ignored) {
            }
            DebugLog.log("LOOKUP", "[4/4] Lookup-rij niet gevonden — menu gesloten");
            reportAntiBanAction("LOOKUP_NO_ROW", forced ? "Lookup test: rij niet gevonden" : "Lookup: rij niet gevonden");
            lastPlayerLookupMs = System.currentTimeMillis();
            return forced ? 800 : -1;
        }

        Point cur = currentMousePoint();
        int lcx = cur != null ? cur.x : targetX;
        int lcy = cur != null ? cur.y : targetY;

        // Vuur Lookup-actie bulletproof via de menu-API (negeert pixel-positie).
        // Als de snapshot ontbreekt/faalt, fallback naar Mouse.click op huidige hover-positie.
        boolean firedViaApi = fireLookupViaMenuAction();
        if (firedViaApi) {
            // Sluit het visuele menu netjes — actie is al verzonden naar de game.
            try {
                Keyboard.pressed(KeyEvent.VK_ESCAPE);
                Thread.sleep(randomRange(40, 90));
                Keyboard.released(KeyEvent.VK_ESCAPE);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            DebugLog.log("LOOKUP", "[4/4] Lookup via menu-API afgevuurd (cursor stond op "
                    + lcx + "," + lcy + ")");
        } else {
            Mouse.click(lcx, lcy, true);
            DebugLog.log("LOOKUP", "[4/4] Lookup pixel-geklikt op (" + lcx + "," + lcy
                    + ") — fallback want geen MenuEntry snapshot");
        }

        lastPlayerLookupMs = System.currentTimeMillis();
        rememberLookedUpName(target.getName());
        String msg = (forced ? "Rechtsklik test: " : "Rechtsklik speler: ")
                + target.getName() + (firedViaApi ? " (API)" : " (pixel)");
        reportAntiBanAction("LOOKUP_SUCCESS", msg);
        return randomRange(700, 1400);
    }

    private void rememberLookedUpName(String name) {
        if (name == null || name.isEmpty()) {
            return;
        }
        String normalized = name.toLowerCase();
        recentLookupNames.remove(normalized);
        recentLookupNames.addLast(normalized);
        while (recentLookupNames.size() > LOOKUP_RECENT_MEMORY) {
            recentLookupNames.removeFirst();
        }
    }

    /**
     * Detecteert of een canvas-pixel (x,y) onder een UI-laag valt (chatbox, minimap, side panel)
     * en daarmee een rechtsklik op een speler eronder zou blokkeren.
     * <p>
     * Belangrijk: een <b>verborgen</b> chat (collapse / hide chat box in Resizable mode) blokkeert
     * NIET — daar kun je gewoon doorheen klikken op spelers eronder. Hetzelfde voor verborgen
     * minimap of side panel. We honoreren dus de echte {@link
     * net.storm.api.domain.widgets.IWidget#isHidden()} state per widget.
     * <p>
     * Layered approach:
     * <ol>
     *   <li>Probeer alle bekende UI-widgets te lezen. Als ten minste één leesbaar is, is dát
     *       de waarheid: alleen blokkeren als een ZICHTBARE widget de pixel bevat.</li>
     *   <li>Als geen enkele widget uit te lezen was (zeldzaam, bv. tijdens login of laden),
     *       fallback naar safe pixel-constants per mode.</li>
     * </ol>
     */
    private boolean isPointBlockedByUi(int x, int y) {
        if (x < 0 || y < 0) return true;

        // Kandidaat-widgets om te checken. 162=chatbox, 160=minimap, 548=fixed-mode sidebar,
        // 161=resizable side bar, 164=resizable bottom line.
        int[][] uiWidgets = new int[][]{
                {162, 0}, {160, 0}, {548, 0}, {161, 0}, {164, 0}
        };

        WidgetReachability r = pointInsideAnyVisibleWidget(x, y, uiWidgets);
        if (r.anyWidgetReadable) {
            // Vertrouw de widget-state: alleen blokkeren als zichtbare widget de pixel bevat.
            // Verborgen chat (gecollapsed) → r.insideVisible blijft false → niet blokkeren.
            return r.insideVisible;
        }

        // Fallback heuristiek (alleen als widgets niet uit te lezen zijn):
        try {
            var stormClient = Client.getClient();
            if (stormClient != null && stormClient.getWrapped() != null) {
                boolean resized = stormClient.getWrapped().isResized();
                Canvas canvas = Client.getCanvas();
                int cw = canvas != null ? canvas.getWidth() : 765;
                int ch = canvas != null ? canvas.getHeight() : 503;
                if (!resized) {
                    if (x >= 519) return true;
                    if (y >= 339 && x <= 516) return true;
                    if (y < 4 || x < 4) return true;
                } else {
                    if (x >= cw - 225) return true;
                    if (y >= ch - 175) return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** Resultaat van een widget-zichtbaarheidscheck: konden we ook maar één widget lezen? Zat het punt in een zichtbare? */
    private static final class WidgetReachability {
        final boolean anyWidgetReadable;
        final boolean insideVisible;
        WidgetReachability(boolean readable, boolean inside) {
            this.anyWidgetReadable = readable;
            this.insideVisible = inside;
        }
    }

    /**
     * Loopt door {@code groupChildPairs} en beoordeelt of (x,y) binnen een ZICHTBARE widget valt.
     * Verborgen widgets ({@link net.storm.api.domain.widgets.IWidget#isHidden()} == true) tellen
     * niet als blokkade — daar kun je doorheen klikken.
     */
    private WidgetReachability pointInsideAnyVisibleWidget(int x, int y, int[][] groupChildPairs) {
        if (groupChildPairs == null) return new WidgetReachability(false, false);
        boolean readable = false;
        boolean insideVisible = false;
        for (int[] pair : groupChildPairs) {
            if (pair == null || pair.length < 2) continue;
            try {
                net.storm.api.domain.widgets.IWidget w =
                        net.storm.sdk.widgets.Widgets.get(pair[0], pair[1]);
                if (w == null) continue;
                readable = true;
                if (w.isHidden()) continue; // verborgen UI = doorklikbaar, niet blokkerend
                Rectangle b = w.getBounds();
                if (b == null || b.width <= 0 || b.height <= 0) continue;
                if (b.contains(x, y)) {
                    insideVisible = true;
                    // niet break — we willen `readable` ook voor andere widgets bevestigen,
                    // maar voor de blokkade hebben we al genoeg.
                    break;
                }
            } catch (Throwable ignored) {
                // doorgaan; mogelijk leest een andere widget wel.
            }
        }
        return new WidgetReachability(readable, insideVisible);
    }

    /** Midden van player-model (convex hull) of fallback tile-poly center (iets omhoog). */
    private Point computeModelClickPoint(IPlayer p) {
        if (p == null) return null;
        try {
            java.lang.reflect.Method m = p.getClass().getMethod("getConvexHull");
            Object hull = m.invoke(p);
            if (hull instanceof java.awt.Shape) {
                Rectangle b = ((java.awt.Shape) hull).getBounds();
                if (b != null && b.width > 0 && b.height > 0) {
                    return new Point(b.x + b.width / 2, b.y + b.height / 2);
                }
            }
        } catch (Throwable ignored) {
        }
        if (p.getCanvasTilePoly() != null) {
            Rectangle b = p.getCanvasTilePoly().getBounds();
            if (b != null && b.width > 0 && b.height > 0) {
                return new Point(b.x + b.width / 2, b.y + b.height / 2 - Math.max(8, b.height / 2));
            }
        }
        return null;
    }

    private Point currentMousePoint() {
        try {
            var stormClient = Client.getClient();
            if (stormClient != null && stormClient.getWrapped() != null) {
                var mp = stormClient.getWrapped().getMouseCanvasPosition();
                if (mp != null && mp.getX() >= 0 && mp.getY() >= 0) {
                    return new Point(mp.getX(), mp.getY());
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Beweegt naar (canvasX, canvasY) via Storm SDK's {@link net.storm.sdk.input.Mouse#moved}.
     * Stuurt enkel een synthetisch AWT MouseMoved event naar het game-canvas. De fysieke
     * OS-cursor van de user blijft staan waar 'ie is — de bot neemt dus nooit de muis over.
     *
     * <p>Eerder gebruikten we {@code java.awt.Robot.mouseMove} om de fysieke cursor te bewegen,
     * maar dat overrulet de user's eigen muis. Op uitdrukkelijk verzoek van de user is dat
     * verwijderd.
     */
    private boolean moveOsCursorTo(Canvas canvas, int canvasX, int canvasY) {
        if (canvas == null) return false;
        try {
            Mouse.moved(canvasX, canvasY, canvas, System.currentTimeMillis());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Vloeiende muisbeweging van de huidige cursor-positie naar (toX,toY) via een gekromde
     * cosinus-easing met kleine jitter per stap. Stuurt 6–28 synthetische Storm-Mouse.moved
     * events achter elkaar zodat het er menselijk uitziet (geen springende sprongen). Blokkeert
     * voor de opgegeven duur. Neemt de OS-cursor NIET over (alleen synthetic in-game events).
     *
     * @param canvas      het Storm canvas (mag niet {@code null} zijn)
     * @param toX         doel-X op het canvas
     * @param toY         doel-Y op het canvas
     * @param durationMs  totale duur van de beweging (~120–800 ms typisch)
     * @return            {@code true} als de beweging is gestart (canvas en path geldig)
     */
    private boolean smoothMoveTo(Canvas canvas, int toX, int toY, int durationMs) {
        if (canvas == null) return false;
        Point cur = currentMousePoint();
        int fromX = cur != null ? cur.x : randomRange(150, 600);
        int fromY = cur != null ? cur.y : randomRange(120, 400);
        int dx = toX - fromX;
        int dy = toY - fromY;
        double dist = Math.sqrt((double) dx * dx + (double) dy * dy);
        if (dist < 1.0) return true;
        int steps = Math.max(6, Math.min(28, (int) Math.round(dist / 5.5)));
        int totalMs = Math.max(120, durationMs);
        int sleepPerStep = Math.max(8, totalMs / steps);
        long startMs = System.currentTimeMillis();
        int cw = Math.max(50, canvas.getWidth());
        int ch = Math.max(50, canvas.getHeight());
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            double eased = (1.0 - Math.cos(t * Math.PI)) / 2.0;
            int x = (int) Math.round(fromX + dx * eased) + randomRange(-1, 2);
            int y = (int) Math.round(fromY + dy * eased) + randomRange(-1, 2);
            x = Math.max(3, Math.min(cw - 3, x));
            y = Math.max(3, Math.min(ch - 3, y));
            try {
                Mouse.moved(x, y, canvas, startMs + (long) (totalMs * t));
            } catch (Throwable ignored) {
                // negeer enkele move-fouten; volgende stappen kunnen weer slagen
            }
            try {
                Thread.sleep(sleepPerStep);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    private void smoothMoveMouseHuman(int toX, int toY) {
        Canvas canvas = Client.getCanvas();
        if (canvas == null) {
            return;
        }
        int startX = randomRange(120, 650);
        int startY = randomRange(110, 420);
        int steps = randomRange(10, 17);
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / (double) steps;
            double eased = (1.0 - Math.cos(t * Math.PI)) / 2.0;
            int x = (int) Math.round(startX + ((toX - startX) * eased)) + randomRange(-1, 2);
            int y = (int) Math.round(startY + ((toY - startY) * eased)) + randomRange(-1, 2);
            Mouse.moved(Math.max(3, Math.min(760, x)), Math.max(3, Math.min(500, y)), canvas, System.currentTimeMillis());
            try {
                Thread.sleep(randomRange(16, 34));
            } catch (InterruptedException ignored) {}
        }
    }

    /**
     * Snapshot van de "Lookup <naam>"-MenuEntry op het moment dat we 'm vinden. Zo kunnen we
     * later via {@code Client.invokeMenuAction(...)} de actie 100% bulletproof afvuren — zelfs
     * als onze gehoverde pixel-positie net naast de rij valt. Voorkomt dat een pixel-offset
     * accidenteel "Follow" of een andere regel kiest.
     */
    private static final class LookupMenuSnapshot {
        final int param0, param1, opcodeId, identifier, itemId, worldViewId;
        final String option, target;
        LookupMenuSnapshot(MenuEntry e) {
            this.param0 = e.getParam0();
            this.param1 = e.getParam1();
            int op = -1;
            try {
                op = e.getType() != null ? e.getType().getId() : -1;
            } catch (Throwable ignored) {}
            this.opcodeId = op;
            this.identifier = e.getIdentifier();
            int it = -1;
            try { it = e.getItemId(); } catch (Throwable ignored) {}
            this.itemId = it;
            int wv = -1;
            try {
                java.lang.reflect.Method m = e.getClass().getMethod("getWorldViewId");
                Object v = m.invoke(e);
                if (v instanceof Integer) wv = (Integer) v;
            } catch (Throwable ignored) {}
            this.worldViewId = wv;
            this.option = String.valueOf(e.getOption());
            this.target = String.valueOf(e.getTarget());
        }
    }

    /** Laatst gevonden Lookup-entry; gebruikt door de klik-stap om de juiste actie te vuren. */
    private LookupMenuSnapshot pendingLookupSnapshot = null;

    /**
     * Houdt het menu open door de cursor op de "Lookup <naam>" regel te zetten (zonder te klikken).
     * Slaat ook de MenuEntry-params op zodat de klik-stap 'm via API kan vuren i.p.v. via pixel.
     */
    private boolean moveMouseToLookupMenuEntry(String targetName) {
        try {
            var stormClient = Client.getClient();
            if (stormClient == null || stormClient.getWrapped() == null) {
                return false;
            }
            var rl = stormClient.getWrapped();
            if (!isContextMenuOpen()) {
                return false;
            }
            MenuEntry[] entries = rl.getMenuEntries();
            if (entries == null || entries.length == 0) {
                DebugLog.log("LOOKUP", "  hover: geen menu entries");
                return false;
            }

            int lookupIdx = -1;
            String normalizedTarget = targetName == null ? "" : targetName.toLowerCase();
            for (int i = 0; i < entries.length; i++) {
                MenuEntry e = entries[i];
                if (e == null) continue;
                String option = Text.removeTags(String.valueOf(e.getOption())).trim().toLowerCase();
                if (!"lookup".equals(option)) continue;
                String target = Text.removeTags(String.valueOf(e.getTarget())).trim().toLowerCase();
                if (normalizedTarget.isEmpty() || target.contains(normalizedTarget)) {
                    lookupIdx = i;
                    break;
                }
            }
            if (lookupIdx < 0) {
                DebugLog.log("LOOKUP", "  hover: Lookup niet gevonden in " + entries.length + " entries");
                pendingLookupSnapshot = null;
                return false;
            }

            // Snapshot de MenuEntry-params nu, zodat we later via API kunnen vuren
            // ook al verandert het menu of valt onze pixel net naast de rij.
            try {
                pendingLookupSnapshot = new LookupMenuSnapshot(entries[lookupIdx]);
            } catch (Throwable t) {
                pendingLookupSnapshot = null;
                DebugLog.log("LOOKUP", "  hover: kon entry-snapshot niet maken: " + t.getMessage());
            }

            int menuX = rl.getMenuX();
            int menuY = rl.getMenuY();
            int menuW = rl.getMenuWidth();
            int menuH = rl.getMenuHeight();
            if (menuW <= 0 || menuH <= 0) {
                DebugLog.log("LOOKUP", "  hover: ongeldige menu bounds " + menuW + "x" + menuH);
                return false;
            }

            // RuneLite menu-entries komen omgekeerd terug t.o.v. visuele volgorde.
            int rowFromTop = Math.max(0, (entries.length - 1) - lookupIdx);

            // Vaste OSRS context-menu metrics (stabieler dan delen-door-aantal-entries):
            //   header  = 19px   (titel-balk "Choose Option")
            //   rij     = 15px   (RuneLite default per item)
            //   bot pad =  4px
            // Voorheen werd rowH dynamisch geschat; bij afwijkend menu-padding dreef de rij
            // 1 row weg → klik kwam op "Follow" i.p.v. "Lookup".
            final int headerH = 19;
            final int rowH = 15;
            int rowTopY = menuY + headerH + (rowFromTop * rowH);
            int rowCenterY = rowTopY + (rowH / 2);
            // Verticale jitter klein houden (max ±2px) zodat we ALTIJD binnen de 15px rij blijven.
            int vJitter = 2;
            int hoverY = rowCenterY + randomRange(-vJitter, vJitter + 1);

            int hMargin = 12;
            int hoverX = menuX + hMargin + randomRange(0, Math.max(1, menuW - (hMargin * 2)));

            hoverX = Math.max(menuX + 4, Math.min(menuX + menuW - 4, hoverX));
            hoverY = Math.max(menuY + headerH + 2, Math.min(menuY + menuH - 4, hoverY));

            DebugLog.log("LOOKUP", "  hover: idx=" + lookupIdx + "/" + entries.length
                    + " row=" + rowFromTop + " rowH=" + rowH + " (vast)"
                    + " menu=(" + menuX + "," + menuY + " " + menuW + "x" + menuH + ")"
                    + " -> (" + hoverX + "," + hoverY + ")"
                    + " snapshot=" + (pendingLookupSnapshot != null ? "ok" : "FAIL"));

            smoothMoveMouseFromCurrent(hoverX, hoverY);
            return true;
        } catch (Throwable ignored) {
            pendingLookupSnapshot = null;
            return false;
        }
    }

    /**
     * Vuurt de Lookup-actie via {@link net.storm.sdk.game.Client#invokeMenuAction} met de
     * gesnapshotte parameters. Hierdoor maakt het niet uit of onze pixel-positie net naast de
     * rij valt — de game krijgt exact de juiste actie binnen.
     *
     * @return true bij succes.
     */
    private boolean fireLookupViaMenuAction() {
        LookupMenuSnapshot snap = pendingLookupSnapshot;
        if (snap == null) return false;
        try {
            Client.invokeMenuAction(
                    snap.param0,
                    snap.param1,
                    snap.opcodeId,
                    snap.identifier,
                    snap.itemId,
                    snap.worldViewId,
                    snap.option,
                    snap.target
            );
            DebugLog.log("LOOKUP", "  fired via invokeMenuAction: option='" + snap.option
                    + "' target='" + snap.target + "' opcode=" + snap.opcodeId
                    + " id=" + snap.identifier + " p0=" + snap.param0 + " p1=" + snap.param1
                    + " wv=" + snap.worldViewId);
            return true;
        } catch (Throwable t) {
            DebugLog.log("LOOKUP", "  invokeMenuAction faalde: " + t.getMessage());
            return false;
        } finally {
            pendingLookupSnapshot = null;
        }
    }

    private void smoothMoveMouseFromCurrent(int toX, int toY) {
        Canvas canvas = Client.getCanvas();
        if (canvas == null) {
            return;
        }

        int startX = toX;
        int startY = toY;
        int curX = toX;
        int curY = toY;
        int mX = -1, mY = -1, mW = -1, mH = -1;

        try {
            var stormClient = Client.getClient();
            if (stormClient != null && stormClient.getWrapped() != null) {
                var rl = stormClient.getWrapped();
                mX = rl.getMenuX();
                mY = rl.getMenuY();
                mW = rl.getMenuWidth();
                mH = rl.getMenuHeight();
                var mp = rl.getMouseCanvasPosition();
                if (mp != null && mp.getX() >= 0 && mp.getY() >= 0) {
                    startX = mp.getX();
                    startY = mp.getY();
                    curX = startX;
                    curY = startY;
                }
            }
        } catch (Throwable ignored) {
        }

        boolean haveMenu = mW > 0 && mH > 0;
        final int hPad = 4;
        final int topPad = 21;
        final int botPad = 4;

        // Startpositie direct binnen menu clampen, zodat we niet eerst buitenlangs bewegen.
        if (haveMenu) {
            startX = Math.max(mX + hPad, Math.min(mX + mW - hPad, curX));
            startY = Math.max(mY + topPad, Math.min(mY + mH - botPad, curY));
        }

        int steps = randomRange(7, 12);
        for (int i = 1; i <= steps; i++) {
            if (!isContextMenuOpen()) {
                return;
            }
            double t = (double) i / (double) steps;
            double eased = (1.0 - Math.cos(t * Math.PI)) / 2.0;
            int x = (int) Math.round(startX + ((toX - startX) * eased)) + randomRange(-1, 2);
            int y = (int) Math.round(startY + ((toY - startY) * eased)) + randomRange(-1, 2);

            // Kritiek: zolang menu open is, ieder frame binnen menu-rectangle houden.
            if (haveMenu) {
                x = Math.max(mX + hPad, Math.min(mX + mW - hPad, x));
                y = Math.max(mY + topPad, Math.min(mY + mH - botPad, y));
            } else {
                x = Math.max(3, Math.min(760, x));
                y = Math.max(3, Math.min(500, y));
            }

            Mouse.moved(x, y, canvas, System.currentTimeMillis());
            try {
                Thread.sleep(randomRange(18, 34));
            } catch (InterruptedException ignored) {
            }
        }
        // Korte "hover hold" zodat menu stabiel blijft op de Lookup-regel.
        try {
            Thread.sleep(randomRange(160, 260));
        } catch (InterruptedException ignored) {
        }
    }

    private boolean isContextMenuOpen() {
        try {
            var stormClient = Client.getClient();
            if (stormClient == null || stormClient.getWrapped() == null) {
                return false;
            }
            return stormClient.getWrapped().isMenuOpen();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public int varyDelay(int baseDelay) {
        if (!config.antiBanEnabled()) return baseDelay;

        double variance = 0.8 + (random.nextDouble() * 0.4);
        int varied = (int) (baseDelay * variance);
        varied = scaleByIntensity(varied);
        varied = applyProfileDelayScale(varied);

        if (random.nextInt(100) < 5) {
            varied += randomRange(500, 2000);
        }

        maybeDoMicroMouseMovement(varied);

        return varied;
    }

    /**
     * Kleine cursor-correctie tijdens wachtmomenten zodat de muis niet "bevroren" lijkt tussen acties.
     * Bewust zacht: lage kans + cooldown, zonder extra sleep.
     */
    private void maybeDoMicroMouseMovement(int delayMs) {
        if (!config.randomMouseMovement()) {
            return;
        }
        // Alleen bij bot AAN: anders genereren we synthetic mouse-events terwijl de user zelf aan
        // het spelen is.
        if (!config.botEnabled()) {
            return;
        }
        if (delayMs < 900) {
            return;
        }
        // KRITIEKE INTERFACES — geen micro-move tijdens een dialog/bank flow.
        // Reden: Storm SDK's Dialog.chooseOption(...) en Bank.* doen intern muis-clicks. Een micro-move
        // die net op dat moment de cursor verplaatst kan de click verkeerd laten landen → de dialog/
        // banking actie gaat verloren en de bot zit vast in een retry-loop (zie Vampire Slayer
        // "stake ontbreekt" loop bij Dr Harlow). Dezelfde guard staat al in de fidget-worker.
        try {
            if (Dialog.isOpen()) return;
            if (Bank.isOpen()) return;
        } catch (Throwable ignored) {
        }
        // Respecteer ook de "bot is bezig met handler-actie" stempel — net als fidget.
        if (System.currentTimeMillis() < lastBotActivityMs + 600) {
            return;
        }
        long now = System.currentTimeMillis();

        // Cooldown: standaard 12s (door intensiteit geschaald). Met human-profile schalen we dit
        // naar de mediaan inter-click gap × 6 zodat de cadence dichter bij de echte speler ligt
        // (typisch 2–8 s i.p.v. een vaste 12 s).
        int microCooldownMs = scaleByIntensity(12_000);
        HumanProfile hp = humanProfile();
        boolean usedHuman = false;
        if (hp != null && hp.interClickGapMs != null && hp.interClickGapMs.isUsable()) {
            int sampled = hp.sampleInterClickGapMs(random, microCooldownMs);
            int humanCool = (int) Math.max(1500, Math.min(20_000L, (long) sampled * 6L));
            microCooldownMs = (microCooldownMs + humanCool) / 2;
            usedHuman = true;
        }
        if (now - lastMicroMouseMoveMs < microCooldownMs) {
            return;
        }
        int chancePct = intensityPercent(16, 22, 32);
        if (random.nextInt(100) >= chancePct) {
            return;
        }
        Canvas canvas = Client.getCanvas();
        if (canvas == null) {
            return;
        }

        // Soepele macro-step. Min/max amplitude komen uit de config-sliders zodat de gebruiker
        // ze kan tunen. HumanProfile mag de bovenkant nog verder oprekken op basis van p95.
        int[] ampRange = clampAmpRange(config.mouseMicroMoveAmpMinPx(), config.mouseMicroMoveAmpMaxPx(),
                /*absMin=*/1, /*absMax=*/400);
        int ampMin = ampRange[0];
        int ampMax = ampRange[1];
        if (hp != null && hp.moveStepPx != null && hp.moveStepPx.isUsable()) {
            int humanP95 = (int) Math.max(1, Math.round(hp.moveStepPx.p95));
            ampMax = Math.max(ampMin + 1, Math.min(400, ampMax + humanP95 * 4));
        }
        int amp = ampMin + random.nextInt(Math.max(1, ampMax - ampMin + 1));
        double angle = random.nextDouble() * Math.PI * 2.0;
        int dx = (int) Math.round(Math.cos(angle) * amp);
        int dy = (int) Math.round(Math.sin(angle) * amp);

        Point cur = currentMousePoint();
        int baseX = cur != null ? cur.x : randomRange(140, 640);
        int baseY = cur != null ? cur.y : randomRange(110, 420);
        int cw = Math.max(50, canvas.getWidth());
        int ch = Math.max(50, canvas.getHeight());
        int toX = Math.max(8, Math.min(cw - 8, baseX + dx));
        int toY = Math.max(8, Math.min(ch - 8, baseY + dy));
        if (Math.abs(toX - baseX) < 6 && Math.abs(toY - baseY) < 6) {
            return;
        }
        int[] durRange = clampDurRange(config.mouseMicroMoveDurMinMs(), config.mouseMicroMoveDurMaxMs(),
                /*absMin=*/30, /*absMax=*/4000);
        int durationMs = durRange[0] + random.nextInt(Math.max(1, durRange[1] - durRange[0] + 1));
        smoothMoveTo(canvas, toX, toY, durationMs);
        lastMicroMouseMoveMs = now;
        String label = usedHuman
                ? "Muis micro-move smooth (human-profiel, cool=" + microCooldownMs + "ms, amp=" + amp + "px)"
                : "Muis micro-move smooth (default, cool=" + microCooldownMs + "ms, amp=" + amp + "px)";
        reportAntiBanAction("MOUSE_MICRO", label);
    }

    private int calculateNextDelay() {
        int base = config.antiBanFrequency();
        int lo = base / 2;
        int hi = (int) (base * 1.5);
        lo = scaleByIntensity(lo);
        hi = scaleByIntensity(hi);
        lo = applyProfileDelayScale(lo);
        hi = applyProfileDelayScale(hi);
        if (hi <= lo) {
            hi = lo + 1;
        }
        return randomRange(lo, hi);
    }

    private CombatBotConfig.AntiBanIntensity antiBanIntensity() {
        CombatBotConfig.AntiBanIntensity i = config.antiBanIntensity();
        return i != null ? i : CombatBotConfig.AntiBanIntensity.NORMAL;
    }

    private int intensityPercent(int low, int normal, int high) {
        switch (antiBanIntensity()) {
            case LOW:
                return low;
            case HIGH:
                return high;
            case NORMAL:
            default:
                return normal;
        }
    }

    private int scaleByIntensity(int value) {
        int pct = intensityPercent(120, 100, 82);
        return Math.max(1, (int) Math.round(value * (pct / 100.0)));
    }

    /**
     * Helpers voor de muis-amplitude/duration sliders: clampt min/max binnen [absMin..absMax]
     * en wisselt ze om als de gebruiker per ongeluk min &gt; max heeft gezet.
     */
    private static int[] clampAmpRange(int min, int max, int absMin, int absMax) {
        return clampRangeInternal(min, max, absMin, absMax);
    }

    private static int[] clampDurRange(int min, int max, int absMin, int absMax) {
        return clampRangeInternal(min, max, absMin, absMax);
    }

    private static int[] clampRangeInternal(int min, int max, int absMin, int absMax) {
        if (absMin < 1) absMin = 1;
        if (absMax < absMin) absMax = absMin;
        int lo = Math.max(absMin, Math.min(absMax, min));
        int hi = Math.max(absMin, Math.min(absMax, max));
        if (hi < lo) {
            int tmp = lo;
            lo = hi;
            hi = tmp;
        }
        return new int[] { lo, hi };
    }

    private int scaledWeight(int baseWeight) {
        int pct = intensityPercent(75, 100, 135);
        return Math.max(1, (int) Math.round(baseWeight * (pct / 100.0)));
    }

    /** Schaalt delays met profiel (basis + fatigue + {@link AccountBehaviorProfileStore.Profile#failureDelayMultiplier()}). */
    private int applyProfileDelayScale(int ms) {
        if (behaviorProfile == null) {
            return ms;
        }
        int p = behaviorProfile.effectiveDelayMultiplierPercent();
        double scaled = ms * (p / 100.0) * behaviorProfile.failureDelayMultiplier();
        return Math.max(1, (int) Math.round(scaled));
    }

    private int randomRange(int min, int max) {
        if (min >= max) return min;
        return min + random.nextInt(max - min);
    }

    /**
     * Achtergrond-thread die continu mens-achtige micro-fidgets uitvoert tussen bot-acties.
     * <ul>
     *   <li>Random sleep 200-1500 ms (intensiteit-geschaald).</li>
     *   <li>Skip als de bot net (≤300 ms) een echte actie deed — voorkomt click-interferentie.</li>
     *   <li>Skip als toggle uit staat of canvas niet beschikbaar is.</li>
     *   <li>Amplitude 1-4 px per fidget. 8% kans op een burst van 2-4 fidgets.</li>
     *   <li>Daemon thread → stopt automatisch met de JVM. Expliciet stoppen via {@link #shutdown()}.</li>
     * </ul>
     */
    private final class MouseFidgetWorker extends Thread {
        private volatile boolean running = true;
        private final Random fidgetRandom = new Random();
        private long lastFidgetMs = 0L;
        private long fidgetCount = 0L;

        MouseFidgetWorker() {
            super("CombatBot-MouseFidget");
            setDaemon(true);
        }

        void shutdown() {
            running = false;
            interrupt();
        }

        @Override
        public void run() {
            DebugLog.log("FIDGET", "Worker gestart (synthetic-only via Storm Mouse.move)");
            while (running) {
                try {
                    int sleepMs = pickInterval();
                    Thread.sleep(sleepMs);
                    if (!running) break;
                    if (!shouldFidgetNow()) continue;

                    // Niet elke wakker-moment fidgeten: extra kans-roll om mens-achtige variatie te
                    // krijgen (~55% kans bij Normal). Voorkomt dat iedere ~3-6s exact één beweging is.
                    int rollPct = intensityPercent(40, 55, 70);
                    if (fidgetRandom.nextInt(100) >= rollPct) continue;

                    boolean burst = fidgetRandom.nextInt(100) < 5;
                    int bursts = burst ? 2 + fidgetRandom.nextInt(2) : 1;
                    for (int i = 0; i < bursts && running; i++) {
                        if (!shouldFidgetNow()) break;
                        doOneFidget(false);
                        if (i + 1 < bursts) {
                            Thread.sleep(120 + fidgetRandom.nextInt(280));
                        }
                    }
                } catch (InterruptedException ie) {
                    if (!running) break;
                    Thread.currentThread().interrupt();
                } catch (Throwable t) {
                    DebugLog.log("FIDGET", "Worker error (continuing): " + t.getMessage());
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            DebugLog.log("FIDGET", "Worker gestopt (totale fidgets: " + fidgetCount + ")");
        }

        /** Force één fidget (eventueel burst) voor de test-knop. */
        String runOneBurstNow() {
            try {
                int n = 1 + fidgetRandom.nextInt(3);
                StringBuilder log = new StringBuilder();
                for (int i = 0; i < n; i++) {
                    String r = doOneFidget(true);
                    if (i > 0) log.append(" | ");
                    log.append(r);
                    if (i + 1 < n) {
                        Thread.sleep(80 + fidgetRandom.nextInt(180));
                    }
                }
                return "Burst " + n + "x: " + log;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return "Burst onderbroken";
            } catch (Throwable t) {
                return "Burst error: " + t.getMessage();
            }
        }

        private int pickInterval() {
            // Veel rustiger basis: 2.5s - 9s, en daar bovenop schaalt antiBanIntensity nog.
            // Burst-momenten worden binnen run() apart afgehandeld.
            int base = 2500 + fidgetRandom.nextInt(6500);
            int pct = intensityPercent(160, 100, 70);
            return Math.max(1500, (int) Math.round(base * (pct / 100.0)));
        }

        private boolean shouldFidgetNow() {
            if (!config.mouseFidgetEnabled()) return false;
            // Alleen fidgeten als de BOT zelf aan staat — niet zomaar zodra de plugin geladen is.
            // Anders zou de bot mens-achtige mouse-events naar het canvas sturen terwijl de user
            // gewoon zelf aan het spelen is.
            if (!config.botEnabled()) return false;
            // Pauze ietsje langer na bot-actie: minder kans dat onze fidget en een ingame click elkaar
            // overlappen. Werkt ook met "vooruitkijkende" notifyHandlerAction-stempels.
            if (System.currentTimeMillis() < lastBotActivityMs + 600) return false;
            if (Client.getCanvas() == null) return false;

            // KRITIEKE INTERFACES — geen fidget tijdens een dialog/bank/deposit-box flow.
            // Tijdens een dialog kan een fidget-move richting de minimap (of compass-orbs) een
            // op handen zijnde dialog-knop-click verstoren. Bank/deposit-box hebben strakke widget-
            // bounds waar we ook geen synthetische cursor-bewegingen overheen willen laten lopen.
            try {
                if (Dialog.isOpen()) return false;
                if (Bank.isOpen()) return false;
            } catch (Throwable ignored) {
            }

            return true;
        }

        private String doOneFidget(boolean force) {
            Canvas canvas = Client.getCanvas();
            if (canvas == null) return "no-canvas";

            // Soepele micro-beweging (config-instelbaar). Voelt menselijk: de cursor "drijft" een
            // stukje in een gekromde lijn. Geen klik, geen drag.
            int[] ampRange = clampAmpRange(config.mouseFidgetAmpMinPx(), config.mouseFidgetAmpMaxPx(),
                    /*absMin=*/1, /*absMax=*/400);
            int amp = ampRange[0] + fidgetRandom.nextInt(Math.max(1, ampRange[1] - ampRange[0] + 1));
            double angle = fidgetRandom.nextDouble() * Math.PI * 2.0;
            int dx = (int) Math.round(Math.cos(angle) * amp);
            int dy = (int) Math.round(Math.sin(angle) * amp);

            Point cur = currentMousePoint();
            int baseX = cur != null ? cur.x : 200 + fidgetRandom.nextInt(400);
            int baseY = cur != null ? cur.y : 150 + fidgetRandom.nextInt(250);
            int cw = Math.max(50, canvas.getWidth());
            int ch = Math.max(50, canvas.getHeight());
            int toX = Math.max(10, Math.min(cw - 10, baseX + dx));
            int toY = Math.max(10, Math.min(ch - 10, baseY + dy));

            int[] durRange = clampDurRange(config.mouseFidgetDurMinMs(), config.mouseFidgetDurMaxMs(),
                    /*absMin=*/30, /*absMax=*/4000);
            int durationMs = durRange[0] + fidgetRandom.nextInt(Math.max(1, durRange[1] - durRange[0] + 1));
            boolean moved = smoothMoveTo(canvas, toX, toY, durationMs);
            lastFidgetMs = System.currentTimeMillis();
            fidgetCount++;

            if (force || (fidgetCount % 25 == 1)) {
                DebugLog.log("FIDGET", "fidget #" + fidgetCount
                        + " amp=" + amp + "px dur=" + durationMs + "ms"
                        + " (" + baseX + "," + baseY + ")->(" + toX + "," + toY + ")"
                        + " via=smooth Storm.Mouse.moved (geen Robot, OS-cursor blijft van user)"
                        + " ok=" + (moved ? "JA" : "NEE"));
            }
            return amp + "px smooth" + (moved ? "" : " (failed)");
        }
    }
}
