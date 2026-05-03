package com.combatbot;

import net.storm.sdk.game.Client;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.swing.SwingUtilities;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Discord webhook sender (multipart/form-data) met optionele screenshot attachment.
 */
public final class DiscordWebhookSender {

    private static Robot robot;

    private DiscordWebhookSender() {}

    private static Robot getRobot() {
        if (robot != null) return robot;
        try {
            robot = new Robot();
            return robot;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Screenshot via AWT Robot (schermpixels onder het canvas).
     * Als het OSRS-venster niet zichtbaar bovenaan ligt, is dit vaak zwart of verkeerd — voor Discord
     * gebruikt {@link CombatBotPlugin} voorkeur {@code DrawManager.requestNextFrameListener} (game-frame, zoals RuneLite/Dink).
     */
    public static BufferedImage captureCanvas() {
        try {
            Robot r = getRobot();
            if (r == null) return null;

            Canvas canvas = Client.getCanvas();
            if (canvas == null) return null;

            // Als RuneLite niet bovenaan staat, kan Robot screenshots maken van wat er vóór het canvas ligt.
            // Daarom: haal window/canvas kort naar voren vóór we capture doen.
            Window window = SwingUtilities.getWindowAncestor(canvas);
            Window active = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
            boolean movedToFront = false;
            if (window != null && active != window) {
                try {
                    window.toFront();
                    window.requestFocus();
                    movedToFront = true;
                } catch (Exception ignored) {}
            }

            // Kleine wachttijd zodat het OS repainten/stabiliseren kan afronden.
            if (movedToFront) {
                r.waitForIdle();
                try {
                    Thread.sleep(250, 450);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }

            Point loc = canvas.getLocationOnScreen();
            Dimension size = canvas.getSize();
            if (loc == null || size == null || size.width <= 0 || size.height <= 0) {
                return null;
            }

            Rectangle bounds = new Rectangle(loc.x, loc.y, size.width, size.height);
            BufferedImage img = r.createScreenCapture(bounds);

            // Restore focus (best-effort), zodat user flow niet te veel onderbroken wordt.
            if (movedToFront && active != null && active != window) {
                try {
                    active.toFront();
                    active.requestFocus();
                } catch (Exception ignored) {}
            }

            return img;
        } catch (Exception e) {
            return null;
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        String out = s;
        out = out.replace("\\", "\\\\");
        out = out.replace("\"", "\\\"");
        out = out.replace("\r\n", "\n");
        out = out.replace("\r", "\n");
        out = out.replace("\n", "\\n");
        return out;
    }

    public static boolean sendWebhook(String webhookUrl, String content, BufferedImage screenshot) {
        if (webhookUrl == null || webhookUrl.trim().isEmpty()) return false;

        try {
            if (screenshot == null) {
                // Fallback: alleen text (JSON body)
                String payload = "{\"content\":\"" + escapeJson(content) + "\"}";
                return postJson(webhookUrl, payload);
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            if (!ImageIO.write(screenshot, "png", baos)) {
                // Als png encode faalt → fallback text only
                String payload = "{\"content\":\"" + escapeJson(content) + "\"}";
                return postJson(webhookUrl, payload);
            }
            byte[] imageBytes = baos.toByteArray();

            String boundary = "----CombatBotBoundary" + UUID.randomUUID();
            HttpURLConnection conn = (HttpURLConnection) new URL(webhookUrl).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setUseCaches(false);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

            try (OutputStream os = conn.getOutputStream()) {
                // payload_json part
                String payloadJson = "{\"content\":\"" + escapeJson(content) + "\"}";
                os.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                os.write(("Content-Disposition: form-data; name=\"payload_json\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                os.write((payloadJson + "\r\n").getBytes(StandardCharsets.UTF_8));

                // file part
                os.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                os.write(("Content-Disposition: form-data; name=\"file\"; filename=\"combatbot.png\"\r\n").getBytes(StandardCharsets.UTF_8));
                os.write(("Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                os.write(imageBytes);
                os.write("\r\n".getBytes(StandardCharsets.UTF_8));

                // end
                os.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int code = conn.getResponseCode();
            return code >= 200 && code < 300;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean postJson(String webhookUrl, String jsonBody) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(webhookUrl).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setUseCaches(false);
            conn.setRequestProperty("Content-Type", "application/json");
            byte[] body = jsonBody.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(body.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
                os.flush();
            }
            int code = conn.getResponseCode();
            return code >= 200 && code < 300;
        } catch (Exception e) {
            return false;
        }
    }
}

