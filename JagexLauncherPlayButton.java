package com.combatbot;

import net.storm.sdk.game.Client;
import net.storm.sdk.game.Game;
import net.storm.sdk.input.Mouse;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Random;

/**
 * Klikt op de <strong>Play</strong>-knop op het <strong>RuneLite game-canvas</strong> ({@link Client#getCanvas()}),
 * dus het scherm <em>in de client</em> (o.a. "Welcome to RuneScape" met grijze <strong>Play Now</strong>).
 * Dit is <strong>niet</strong> het aparte Jagex Launcher-programma buiten RuneLite om.
 * <p>
 * Detectie: template (optioneel), dan grijze knop met witte tekst (zoals jouw screenshot), dan groene knop-variant,
 * dan vaste midden-coördinaten.
 */
public final class JagexLauncherPlayButton {

    private static final Random RANDOM = new Random();
    private static Robot robot;

    /** Vaste coördinaten als fallback (zelfde als oorspronkelijke snippet). */
    private static final int BUTTON_WIDTH = 210;
    private static final int BUTTON_HEIGHT = 55;
    private static final int BUTTON_Y = 235;

    /** Kleurdetectie: groen = G dominant en voldoende helder (Play-knop groen). */
    private static final int GREEN_MIN = 80;
    private static final double GREEN_RATIO = 1.1; // G >= GREEN_RATIO * max(R,B)

    /** Detectie grijze "Play Now"-knop: witte tekst (R,G,B allemaal hoog). */
    private static final int WHITE_MIN = 200;       // pixel is "wit" als R,G,B >= WHITE_MIN
    private static final double MIN_WHITE_FRAC = 0.08; // min. fractie witte pixels (tekst op knop)

    /** Zoekgebied: midden van het scherm, waar de knop typisch zit. */
    private static final double SEARCH_WIDTH_FRAC = 0.8;
    private static final int SEARCH_Y_START = 160;
    private static final int SEARCH_Y_END = 360;
    /** Min. fractie groene pixels in een rechthoek om als knop te tellen. */
    private static final double MIN_GREEN_FRAC = 0.25;

    private JagexLauncherPlayButton() {
    }

    private static Robot getRobot() {
        if (robot == null) {
            try {
                robot = new Robot();
            } catch (AWTException e) {
                return null;
            }
        }
        return robot;
    }

    /**
     * Bepaalt de schermcoördinaten van het game-canvas (voor screenshot).
     */
    private static Rectangle getCanvasScreenBounds() {
        Canvas canvas = Client.getCanvas();
        if (canvas == null) {
            return null;
        }
        try {
            Point loc = canvas.getLocationOnScreen();
            Dimension size = canvas.getSize();
            if (size == null || size.width <= 0 || size.height <= 0) {
                return null;
            }
            return new Rectangle(loc.x, loc.y, size.width, size.height);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Maakt een screenshot van het game-canvas.
     */
    private static BufferedImage captureCanvas() {
        Robot r = getRobot();
        Rectangle bounds = getCanvasScreenBounds();
        if (r == null || bounds == null || bounds.width <= 0 || bounds.height <= 0) {
            return null;
        }
        try {
            return r.createScreenCapture(bounds);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Zoekt naar een groene "Play"-achtige knop in het midden van de afbeelding.
     * Retourneert de canvas-coördinaten van het midden van de gevonden rechthoek, of null.
     */
    public static Point findPlayButtonByColor(BufferedImage capture) {
        if (capture == null) {
            return null;
        }
        int w = capture.getWidth();
        int h = capture.getHeight();
        if (w < BUTTON_WIDTH || h < BUTTON_HEIGHT) {
            return null;
        }

        int searchXStart = (int) ((1 - SEARCH_WIDTH_FRAC) / 2 * w);
        int searchXEnd = w - searchXStart;
        int yStart = Math.max(0, SEARCH_Y_START);
        int yEnd = Math.min(h - BUTTON_HEIGHT, SEARCH_Y_END);
        if (yEnd <= yStart) {
            yEnd = Math.min(h - BUTTON_HEIGHT, SEARCH_Y_END);
            yStart = Math.max(0, SEARCH_Y_START);
        }

        int[] pixels = getPixels(capture);
        if (pixels == null) {
            return null;
        }

        int bestScore = 0;
        int bestX = 0, bestY = 0;

        for (int by = yStart; by <= yEnd; by += 4) {
            for (int bx = searchXStart; bx + BUTTON_WIDTH <= searchXEnd; bx += 4) {
                int greenCount = 0;
                int total = 0;
                for (int dy = 0; dy < BUTTON_HEIGHT; dy += 2) {
                    for (int dx = 0; dx < BUTTON_WIDTH; dx += 2) {
                        int x = bx + dx;
                        int y = by + dy;
                        if (x >= 0 && x < w && y >= 0 && y < h) {
                            total++;
                            int rgb = pixels[y * w + x];
                            int r = (rgb >> 16) & 0xFF;
                            int g = (rgb >> 8) & 0xFF;
                            int b = rgb & 0xFF;
                            if (g >= GREEN_MIN && g >= GREEN_RATIO * Math.max(r, b)) {
                                greenCount++;
                            }
                        }
                    }
                }
                if (total > 0 && (double) greenCount / total >= MIN_GREEN_FRAC) {
                    int score = greenCount;
                    if (score > bestScore) {
                        bestScore = score;
                        bestX = bx + BUTTON_WIDTH / 2;
                        bestY = by + BUTTON_HEIGHT / 2;
                    }
                }
            }
        }

        if (bestScore == 0) {
            return null;
        }
        return new Point(bestX, bestY);
    }

    /**
     * Zoekt naar de grijze "Play Now"-knop met witte tekst (geen groen).
     * Detecteert een rechthoek met veel witte pixels (= de "Play Now" tekst).
     */
    public static Point findPlayButtonByWhiteText(BufferedImage capture) {
        if (capture == null) {
            return null;
        }
        int w = capture.getWidth();
        int h = capture.getHeight();
        if (w < BUTTON_WIDTH || h < BUTTON_HEIGHT) {
            return null;
        }

        int searchXStart = (int) ((1 - SEARCH_WIDTH_FRAC) / 2 * w);
        int searchXEnd = w - searchXStart;
        int yStart = Math.max(0, SEARCH_Y_START);
        int yEnd = Math.min(h - BUTTON_HEIGHT, SEARCH_Y_END);
        if (yEnd <= yStart) {
            yEnd = Math.min(h - BUTTON_HEIGHT, SEARCH_Y_END);
            yStart = Math.max(0, SEARCH_Y_START);
        }

        int[] pixels = getPixels(capture);
        if (pixels == null) {
            return null;
        }

        int bestScore = 0;
        int bestX = 0, bestY = 0;

        for (int by = yStart; by <= yEnd; by += 3) {
            for (int bx = searchXStart; bx + BUTTON_WIDTH <= searchXEnd; bx += 3) {
                int whiteCount = 0;
                int total = 0;
                for (int dy = 0; dy < BUTTON_HEIGHT; dy += 2) {
                    for (int dx = 0; dx < BUTTON_WIDTH; dx += 2) {
                        int x = bx + dx;
                        int y = by + dy;
                        if (x >= 0 && x < w && y >= 0 && y < h) {
                            total++;
                            int rgb = pixels[y * w + x];
                            int r = (rgb >> 16) & 0xFF;
                            int g = (rgb >> 8) & 0xFF;
                            int b = rgb & 0xFF;
                            if (r >= WHITE_MIN && g >= WHITE_MIN && b >= WHITE_MIN) {
                                whiteCount++;
                            }
                        }
                    }
                }
                if (total > 0 && (double) whiteCount / total >= MIN_WHITE_FRAC) {
                    if (whiteCount > bestScore) {
                        bestScore = whiteCount;
                        bestX = bx + BUTTON_WIDTH / 2;
                        bestY = by + BUTTON_HEIGHT / 2;
                    }
                }
            }
        }

        if (bestScore == 0) {
            return null;
        }
        return new Point(bestX, bestY);
    }

    private static int[] getPixels(BufferedImage img) {
        if (img.getType() == BufferedImage.TYPE_INT_ARGB || img.getType() == BufferedImage.TYPE_INT_RGB) {
            return ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        }
        int w = img.getWidth();
        int h = img.getHeight();
        int[] pixels = new int[w * h];
        img.getRGB(0, 0, w, h, pixels, 0, w);
        return pixels;
    }

    /**
     * Eenvoudige template matching: zoekt het beste overeenkomende gebied (genormaliseerde correlatie).
     * template en capture moeten hetzelfde pixelformaat hebben (INT_ARGB/INT_RGB) voor snelle toegang.
     *
     * @return canvas-coördinaten van het midden van de beste match, of null
     */
    public static Point findPlayButtonByTemplate(BufferedImage capture, BufferedImage template) {
        if (capture == null || template == null) {
            return null;
        }
        int cw = capture.getWidth();
        int ch = capture.getHeight();
        int tw = template.getWidth();
        int th = template.getHeight();
        if (tw > cw || th > ch || tw < 10 || th < 10) {
            return null;
        }

        int[] capPx = getPixels(capture);
        int[] tplPx = getPixels(template);
        if (capPx == null || tplPx == null) {
            return null;
        }

        double bestScore = Double.NEGATIVE_INFINITY;
        int bestX = 0, bestY = 0;

        int yEnd = Math.min(ch - th, SEARCH_Y_END + 50);
        int yStart = Math.max(0, SEARCH_Y_START - 20);
        int xStart = (cw - tw) / 2 - (cw / 4);
        int xEnd = (cw + tw) / 2 + (cw / 4);
        if (xStart < 0) xStart = 0;
        if (xEnd > cw - tw) xEnd = cw - tw;

        for (int y = yStart; y <= yEnd; y += 2) {
            for (int x = xStart; x <= xEnd; x += 2) {
                double score = matchTemplateAt(capPx, cw, ch, tplPx, tw, th, x, y);
                if (score > bestScore) {
                    bestScore = score;
                    bestX = x + tw / 2;
                    bestY = y + th / 2;
                }
            }
        }

        if (bestScore < 0.3) {
            return null;
        }
        return new Point(bestX, bestY);
    }

    private static double matchTemplateAt(int[] cap, int cw, int ch, int[] tpl, int tw, int th, int ox, int oy) {
        double sumT = 0, sumC = 0, sumT2 = 0, sumC2 = 0, sumTC = 0;
        int n = 0;
        for (int dy = 0; dy < th; dy++) {
            for (int dx = 0; dx < tw; dx++) {
                int cx = ox + dx;
                int cy = oy + dy;
                if (cx >= cw || cy >= ch) continue;
                int tc = tpl[dy * tw + dx];
                int cc = cap[cy * cw + cx];
                double tg = gray(tc);
                double cg = gray(cc);
                sumT += tg;
                sumC += cg;
                sumT2 += tg * tg;
                sumC2 += cg * cg;
                sumTC += tg * cg;
                n++;
            }
        }
        if (n < 10) return -1;
        double nD = n;
        double meanT = sumT / nD;
        double meanC = sumC / nD;
        double varT = sumT2 / nD - meanT * meanT;
        double varC = sumC2 / nD - meanC * meanC;
        if (varT <= 0 || varC <= 0) return -1;
        double cov = sumTC / nD - meanT * meanC;
        return cov / (Math.sqrt(varT) * Math.sqrt(varC));
    }

    private static double gray(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return 0.299 * r + 0.587 * g + 0.114 * b;
    }

    /**
     * Probeert een template te laden van de classpath (bijv. resources/play_button.png).
     */
    private static BufferedImage loadTemplate() {
        try {
            java.io.InputStream in = JagexLauncherPlayButton.class.getResourceAsStream("/play_button.png");
            if (in == null) {
                in = JagexLauncherPlayButton.class.getResourceAsStream("play_button.png");
            }
            if (in != null) {
                BufferedImage img = javax.imageio.ImageIO.read(in);
                in.close();
                return img;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static BufferedImage templateCache = null;

    private static BufferedImage getTemplate() {
        if (templateCache == null) {
            templateCache = loadTemplate();
        }
        return templateCache;
    }

    /**
     * Zoekt de Play-knop op het game-canvas en klikt.
     * <p>Eerst {@link WelcomeScreenPlayHelper} (iface 378,77 CLICK HERE TO PLAY + SDK), daarna visueel:
     * template → witte tekst → groen → vaste coördinaten. Pixel-klik kan tweede logout/inlog-cyclus onstabieler maken
     * dan een echte widget-{@code interact}.
     *
     * @return true als er een klik is uitgevoerd
     */
    private static long lastCanvasFocusAttemptMs;
    private static final long CANVAS_FOCUS_INTERVAL_MS = 12_000L;

    public static boolean clickPlayButton() {
        WelcomeScreenPlayHelper.LoginPhase phase = WelcomeScreenPlayHelper.resolveLoginPhase();
        if (phase == WelcomeScreenPlayHelper.LoginPhase.WELCOME_LOBBY) {
            int delay = WelcomeScreenPlayHelper.advanceWelcomeLobbyClick();
            return delay > 0;
        }
        if (phase != WelcomeScreenPlayHelper.LoginPhase.LOGIN_SCREEN
                && !(Game.isOnLoginScreen() && !Game.isLoggedIn())) {
            return false;
        }
        if (!WelcomeScreenPlayHelper.shouldAttemptPlayClick()) {
            return false;
        }

        // Jagex login-scherm: alleen canvas Play Now (widgets/blind CC_OP doen niets).
        if (Game.isOnLoginScreen() && !Game.isLoggedIn()) {
            if (WelcomeScreenPlayHelper.isPostJagexLoginScreenPlaySettling()) {
                return false;
            }
            boolean clicked = clickPlayNowOnCanvas("login-scherm Play Now");
            if (clicked) {
                WelcomeScreenPlayHelper.notifyJagexLoginScreenPlayClicked();
            }
            return clicked;
        }

        if (WelcomeScreenPlayHelper.tryClickPlay()) {
            return true;
        }

        if (!WelcomeScreenPlayHelper.mayUseCanvasPlayFallback()) {
            return false;
        }
        return clickPlayNowOnCanvas("welkomst-lobby canvas");
    }

    /**
     * Klik op grijze/groene Play-knop via canvas-detectie (template, witte tekst, kleur, vaste coords).
     */
    public static boolean clickPlayNowOnCanvas(String reason) {
        long now = System.currentTimeMillis();
        if (now - lastCanvasFocusAttemptMs >= CANVAS_FOCUS_INTERVAL_MS) {
            lastCanvasFocusAttemptMs = now;
            WelcomeScreenPlayHelper.tryFocusGameWindow();
        }

        Canvas canvas = Client.getCanvas();
        if (canvas == null) {
            return false;
        }

        BufferedImage capture = captureCanvas();
        if (capture != null) {
            BufferedImage template = getTemplate();
            if (template != null) {
                Point p = findPlayButtonByTemplate(capture, template);
                if (p != null) {
                    int x = p.x + RANDOM.nextInt(11) - 5;
                    int y = p.y + RANDOM.nextInt(11) - 5;
                    Mouse.click(Math.max(0, x), Math.max(0, y), true);
                    DebugLog.log("Login", "Play Now (" + reason + ") template @ " + x + "," + y);
                    return true;
                }
            }
            Point p = findPlayButtonByWhiteText(capture);
            if (p == null) {
                p = findPlayButtonByColor(capture);
            }
            if (p != null) {
                int x = p.x + RANDOM.nextInt(11) - 5;
                int y = p.y + RANDOM.nextInt(11) - 5;
                Mouse.click(Math.max(0, x), Math.max(0, y), true);
                DebugLog.log("Login", "Play Now (" + reason + ") detectie @ " + x + "," + y);
                return true;
            }
        }

        int canvasWidth = canvas.getWidth();
        int canvasHeight = canvas.getHeight();
        int halfWidth = BUTTON_WIDTH / 2;
        int buttonX = (canvasWidth / 2) - halfWidth;
        int buttonY = BUTTON_Y;
        if (buttonX < 0) {
            buttonX = 0;
        }
        if (buttonY < 0) {
            buttonY = 0;
        }
        int maxX = Math.min(buttonX + BUTTON_WIDTH, canvasWidth);
        int maxY = Math.min(buttonY + BUTTON_HEIGHT, canvasHeight);
        int w = maxX - buttonX;
        int h = maxY - buttonY;
        if (w <= 0 || h <= 0) {
            return false;
        }
        int x = buttonX + RANDOM.nextInt(w);
        int y = buttonY + RANDOM.nextInt(h);
        Mouse.click(x, y, true);
        DebugLog.log("Login", "Play Now (" + reason + ") vaste coords @ " + x + "," + y);
        return true;
    }
}
