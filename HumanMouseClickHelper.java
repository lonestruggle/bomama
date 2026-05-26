package com.combatbot;

import net.storm.api.domain.widgets.IWidget;
import net.storm.sdk.game.Client;
import net.storm.sdk.input.Mouse;

import java.awt.Canvas;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.util.Random;

/**
 * Vloeiende canvas-muisbeweging + klik (cosinus-easing, {@link Mouse#moved}, human-profiel timing).
 * Zelfde aanpak als anti-ban / lamp-hover — geen directe {@code widget.interact()} zonder beweging.
 */
public final class HumanMouseClickHelper {

    private static final Random RNG = new Random();

    private HumanMouseClickHelper() {
    }

    /** Beweeg naar widget en linksklik (inventory-tab, knoppen, …). */
    public static boolean smoothMoveAndLeftClickWidget(IWidget w) {
        if (w == null) {
            return false;
        }
        try {
            if (w.isHidden()) {
                return false;
            }
            Rectangle b = w.getBounds();
            if (b == null || b.width <= 0 || b.height <= 0) {
                return false;
            }
            return smoothMoveAndLeftClick(b);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean smoothMoveAndLeftClick(Rectangle b) {
        if (b == null || b.width <= 0 || b.height <= 0) {
            return false;
        }
        Canvas canvas = Client.getCanvas();
        if (canvas == null) {
            return false;
        }
        int padX = Math.max(2, b.width / 6);
        int padY = Math.max(2, b.height / 6);
        int innerW = Math.max(1, b.width - 2 * padX);
        int innerH = Math.max(1, b.height - 2 * padY);
        int x = b.x + padX + RNG.nextInt(innerW);
        int y = b.y + padY + RNG.nextInt(innerH);
        int maxX = Math.max(20, canvas.getWidth() - 3);
        int maxY = Math.max(20, canvas.getHeight() - 3);
        x = Math.max(3, Math.min(maxX, x));
        y = Math.max(3, Math.min(maxY, y));
        try {
            if (!smoothMoveTo(canvas, x, y)) {
                return false;
            }
            sleepMs(samplePreClickHoverMs());
            long tMove = System.currentTimeMillis();
            Mouse.moved(x, y, canvas, tMove);
            sleepMs(samplePressReleaseDwellMs() / 3);
            long tPress = System.currentTimeMillis();
            Mouse.pressed(x, y, canvas, tPress, MouseEvent.BUTTON1);
            sleepMs(samplePressReleaseDwellMs());
            long tRel = System.currentTimeMillis();
            Mouse.released(x, y, canvas, tRel, MouseEvent.BUTTON1);
            Mouse.clicked(x, y, canvas, tRel, MouseEvent.BUTTON1);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean smoothMoveTo(Canvas canvas, int toX, int toY) {
        if (canvas == null) {
            return false;
        }
        Point cur = currentMousePoint();
        int fromX = cur != null ? cur.x : toX;
        int fromY = cur != null ? cur.y : toY;
        int dx = toX - fromX;
        int dy = toY - fromY;
        double dist = Math.sqrt((double) dx * dx + (double) dy * dy);
        if (dist < 1.0) {
            return true;
        }
        HumanProfile hp = HumanProfile.getOrLoad();
        int durationMs = 260 + RNG.nextInt(340);
        if (hp != null && hp.isUsable()) {
            int perStep = hp.sampleMoveDtMs(RNG, 22);
            int stepsEst = Math.max(6, Math.min(28, (int) Math.round(dist / Math.max(4.0, hp.sampleMoveStepPx(RNG, 8)))));
            durationMs = Math.max(140, Math.min(900, perStep * stepsEst));
        }
        int steps = Math.max(6, Math.min(28, (int) Math.round(dist / 5.5)));
        int totalMs = Math.max(120, durationMs);
        int sleepPerStep = Math.max(8, totalMs / steps);
        long startMs = System.currentTimeMillis();
        int cw = Math.max(50, canvas.getWidth());
        int ch = Math.max(50, canvas.getHeight());
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            double eased = (1.0 - Math.cos(t * Math.PI)) / 2.0;
            int x = (int) Math.round(fromX + dx * eased) + RNG.nextInt(3) - 1;
            int y = (int) Math.round(fromY + dy * eased) + RNG.nextInt(3) - 1;
            x = Math.max(3, Math.min(cw - 3, x));
            y = Math.max(3, Math.min(ch - 3, y));
            try {
                Mouse.moved(x, y, canvas, startMs + (long) (totalMs * t));
            } catch (Throwable ignored) {
            }
            sleepMs(sleepPerStep);
        }
        return true;
    }

    private static Point currentMousePoint() {
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

    private static int samplePreClickHoverMs() {
        HumanProfile hp = HumanProfile.getOrLoad();
        if (hp != null && hp.isUsable()) {
            return hp.sampleInterClickGapMs(RNG, 220) / 4;
        }
        return 180 + RNG.nextInt(141);
    }

    private static int samplePressReleaseDwellMs() {
        HumanProfile hp = HumanProfile.getOrLoad();
        if (hp != null && hp.isUsable()) {
            return hp.samplePressReleaseDwellMs(RNG, 70);
        }
        return 45 + RNG.nextInt(51);
    }

    private static void sleepMs(int ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
