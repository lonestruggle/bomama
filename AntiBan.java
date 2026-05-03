package com.combatbot;

import net.storm.api.domain.actors.INPC;
import net.storm.api.domain.actors.IPlayer;
import net.storm.api.widgets.Tab;
import net.storm.sdk.entities.NPCs;
import net.storm.sdk.entities.Players;
import net.storm.sdk.game.Client;
import net.storm.sdk.input.Keyboard;
import net.storm.sdk.input.Mouse;
import net.storm.sdk.widgets.Tabs;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.time.Instant;
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
    private enum ActionType { NONE, CAMERA, IDLE, MOUSE, MISCLICK, TAB_GLANCE }

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

    public AntiBan(CombatBotConfig config, CombatBotPaint paint) {
        this.config = config;
        this.paint = paint;
        this.nextActionDelay = calculateNextDelay();
    }

    public void setBehaviorProfile(AccountBehaviorProfileStore.Profile profile) {
        this.behaviorProfile = profile;
    }

    public int check() {
        if (!config.antiBanEnabled()) {
            return 0;
        }

        long elapsed = java.time.Duration.between(lastActionTime, Instant.now()).getSeconds();
        if (elapsed < nextActionDelay) {
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
        int totalWeight = 0;
        if (config.cameraMovement()) totalWeight += 35;
        if (config.idleChecks()) totalWeight += 25;
        if (config.randomMouseMovement()) totalWeight += 25;
        if (config.misClickEnabled()) totalWeight += 15;
        boolean tabGlanceAllowed = config.tabGlanceEnabled()
                && (System.currentTimeMillis() - lastTabGlanceMs) >= nextTabGlanceCooldownMs;
        // Lager gewicht + cooldown zodat tab-wissel minder vaak gebeurt.
        if (tabGlanceAllowed) totalWeight += 10;

        if (totalWeight == 0) return -1;

        // Als vorige actie camera was, herbereken gewichten zonder camera
        int effectiveWeight = totalWeight;
        boolean skipCamera = (lastActionType == ActionType.CAMERA) && config.cameraMovement();
        if (skipCamera) {
            effectiveWeight -= 35;
            if (effectiveWeight <= 0) {
                lastActionType = ActionType.NONE;
                return -1;
            }
        }

        int roll = random.nextInt(effectiveWeight);
        int cumulative = 0;

        if (config.cameraMovement() && !skipCamera) {
            cumulative += 35;
            if (roll < cumulative) {
                lastActionType = ActionType.CAMERA;
                return doCameraMovement();
            }
        }

        if (config.idleChecks()) {
            cumulative += 25;
            if (roll < cumulative) {
                lastActionType = ActionType.IDLE;
                return doIdlePause();
            }
        }

        if (config.randomMouseMovement()) {
            cumulative += 25;
            if (roll < cumulative) {
                lastActionType = ActionType.MOUSE;
                return doRandomMouseMovement();
            }
        }

        if (tabGlanceAllowed) {
            cumulative += 10;
            if (roll < cumulative) {
                lastActionType = ActionType.TAB_GLANCE;
                return doTabGlance();
            }
        }

        if (config.misClickEnabled()) {
            lastActionType = ActionType.MISCLICK;
            return doMisClick();
        }

        return -1;
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

        paint.setLastAntiBanAction("Camera: MMB drag");
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

        paint.setLastAntiBanAction(label);
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

        paint.setLastAntiBanAction("Camera: top-down + rondom");
        return totalDuration + randomRange(150, 500);
    }

    private int doIdlePause() {
        int pauseType = random.nextInt(3);

        switch (pauseType) {
            case 0:
                paint.setLastAntiBanAction("Korte pauze");
                return randomRange(1000, 3000);
            case 1:
                paint.setLastAntiBanAction("Middel pauze");
                return randomRange(3000, 8000);
            case 2:
                paint.setLastAntiBanAction("Lange pauze");
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
                paint.setLastAntiBanAction("Muis bewegen");
                return randomRange(200, 600);
            }
            case 1: {
                long time = System.currentTimeMillis();
                int edgeX = random.nextBoolean() ? randomRange(0, 30) : randomRange(730, 760);
                int edgeY = randomRange(50, 450);
                Mouse.moved(edgeX, edgeY, canvas, time);
                paint.setLastAntiBanAction("Muis naar rand");
                return randomRange(400, 1000);
            }
            case 2: {
                long time2 = System.currentTimeMillis();
                int hx = randomRange(150, 600);
                int hy = randomRange(100, 400);
                Mouse.moved(hx, hy, canvas, time2);
                paint.setLastAntiBanAction("Muis hover");
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
        nextTabGlanceCooldownMs = randomRange(90_000, 180_001);
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

        paint.setLastAntiBanAction("Tab: F-toets → ESC (inv)");
        return randomRange(250, 550);
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
        paint.setLastAntiBanAction("Tab: Tabs.open (Tut)");
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
                    paint.setLastAntiBanAction("Misclick: NPC rechtsklk");
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
                    paint.setLastAntiBanAction("Misclick: speler rechtsklk");
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
                    paint.setLastAntiBanAction("Misclick: inv rechtsklk");
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
                        paint.setLastAntiBanAction("Misclick: tab Tabs.open (Tut)");
                    } else {
                        int tabIndex = random.nextInt(SIDE_PANEL_TAB_X.length);
                        Mouse.click(SIDE_PANEL_TAB_X[tabIndex], SIDE_PANEL_TAB_Y, false);
                        paint.setLastAntiBanAction("Misclick: tab rechtsklk");
                    }
                } catch (Throwable ignored) {
                    int tabIndex = random.nextInt(SIDE_PANEL_TAB_X.length);
                    Mouse.click(SIDE_PANEL_TAB_X[tabIndex], SIDE_PANEL_TAB_Y, false);
                    paint.setLastAntiBanAction("Misclick: tab rechtsklk");
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
                    paint.setLastAntiBanAction("Misclick: minimap hover");
                    return randomRange(300, 700);
                }
                return -1;
            }

            case 5: {
                int rx = randomRange(50, 500);
                int ry = randomRange(50, 350);
                Mouse.click(rx, ry, false);
                paint.setLastAntiBanAction("Misclick: random rechtsklk");
                try { Thread.sleep(randomRange(400, 800)); } catch (InterruptedException ignored) {}
                Mouse.click(randomRange(300, 500), randomRange(300, 400), true);
                return randomRange(300, 800);
            }

            default:
                return -1;
        }
    }

    public int varyDelay(int baseDelay) {
        if (!config.antiBanEnabled()) return baseDelay;

        double variance = 0.8 + (random.nextDouble() * 0.4);
        int varied = (int) (baseDelay * variance);
        varied = applyProfileDelayScale(varied);

        if (random.nextInt(100) < 5) {
            varied += randomRange(500, 2000);
        }

        return varied;
    }

    private int calculateNextDelay() {
        int base = config.antiBanFrequency();
        int lo = base / 2;
        int hi = (int) (base * 1.5);
        lo = applyProfileDelayScale(lo);
        hi = applyProfileDelayScale(hi);
        if (hi <= lo) {
            hi = lo + 1;
        }
        return randomRange(lo, hi);
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
}
