package com.combatbot;

import net.runelite.api.Point;
import net.runelite.client.util.Text;
import net.storm.api.domain.widgets.IWidget;
import net.storm.sdk.widgets.Widgets;

import java.awt.Rectangle;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Leest het widget-boom via {@link Widgets#get(int, int)} en schrijft leesbare regels naar {@link DebugLog}
 * (Combat Bot → tab <b>🔍 Debug</b>), plus stdout en dag-logbestand zoals andere debug-regels.
 */
public final class WidgetDebugHelper {

    /** Ruim scanvenster; null-wortels worden snel overgeslagen. */
    private static final int MAX_GROUP = 900;
    private static final int MAX_CHILD = 120;

    private WidgetDebugHelper() {
    }

    public static final String WIDGET_LOG_SOURCE = "WIDGET";

    /**
     * Vingerafdrukken van widgets die al eens gelogd zijn (zonder canvas/bounds, wél tekst/acties).
     * Zo voorkom je dat periodieke dumps dezelfde regels blijven herhalen.
     */
    private static final Set<String> printedDedupeKeys = ConcurrentHashMap.newKeySet();

    public static void clearPrintedWidgetFingerprints() {
        printedDedupeKeys.clear();
    }

    /**
     * @param filterSubstring alleen regels die dit bevatten (naam/tekst/acties/regel); leeg = alle “interessante” widgets
     * @param maxLines        harde limiet
     */
    public static void dumpVisibleWidgetsToDebug(String filterSubstring, int maxLines) {
        dumpVisibleWidgetsToDebug(filterSubstring, maxLines, false);
    }

    /**
     * @param skipAlreadyPrinted als true: sla regels over waarvan naam/tekst/acties/type/item/id gelijk zijn aan een eerdere dump
     *                          in deze sessie (tot {@link #clearPrintedWidgetFingerprints()}).
     */
    public static void dumpVisibleWidgetsToDebug(String filterSubstring, int maxLines, boolean skipAlreadyPrinted) {
        String f = filterSubstring == null ? "" : filterSubstring.trim().toLowerCase(Locale.ROOT);
        boolean filterActive = !f.isEmpty();
        List<String> lines = new ArrayList<>();
        long scannedNodes = 0L;
        int skippedDuplicate = 0;
        outer:
        for (int g = 0; g <= MAX_GROUP; g++) {
            for (int c = 0; c <= MAX_CHILD; c++) {
                IWidget root;
                try {
                    root = Widgets.get(g, c);
                } catch (Throwable ignored) {
                    continue;
                }
                if (root == null) {
                    continue;
                }
                for (IWidget w : flatten(root)) {
                    scannedNodes++;
                    if (w == null) {
                        continue;
                    }
                    try {
                        if (w.isHidden() || !w.isVisible()) {
                            continue;
                        }
                    } catch (Throwable ignored) {
                        continue;
                    }
                    String name = clean(w.getName());
                    String text = clean(w.getText());
                    String actions = formatActions(w);
                    if (!interesting(name, text, actions, w)) {
                        continue;
                    }
                    String line = formatLine(g, c, w, name, text, actions);
                    if (filterActive && !line.toLowerCase(Locale.ROOT).contains(f)) {
                        continue;
                    }
                    if (skipAlreadyPrinted) {
                        String key = dedupeKey(g, c, w, name, text, actions);
                        if (!printedDedupeKeys.add(key)) {
                            skippedDuplicate++;
                            continue;
                        }
                    }
                    lines.add(line);
                    if (lines.size() >= maxLines) {
                        break outer;
                    }
                }
            }
        }
        List<String> block = new ArrayList<>(lines.size() + 4);
        String head = "========== Widget dump (" + lines.size() + " lines, nodes visited " + scannedNodes + ")";
        if (skipAlreadyPrinted) {
            head += ", skipped already-printed " + skippedDuplicate;
        }
        head += ") ==========";
        block.add(head);
        block.addAll(lines);
        block.add("========== end widget dump ==========");
        DebugLog.logBlock(WIDGET_LOG_SOURCE, block);
    }

    /** @deprecated gebruik {@link #dumpVisibleWidgetsToDebug}. */
    @Deprecated
    public static void dumpVisibleWidgetsToStdOut(String filterSubstring, int maxLines) {
        dumpVisibleWidgetsToDebug(filterSubstring, maxLines);
    }

    /**
     * Zelfde widget-logica als de logregel, maar zonder canvas/bounds (die veranderen bij verplaatsing/resize).
     */
    private static String dedupeKey(int scanGroup, int scanChild, IWidget w, String name, String text, String actions) {
        int packed = safeId(w);
        return packed + "\0" + scanGroup + "\0" + scanChild + "\0" + safeType(w) + "\0" + safeItem(w) + "\0" + name + "\0" + text + "\0" + actions;
    }

    private static boolean interesting(String name, String text, String actions, IWidget w) {
        if (name != null && !name.isEmpty()) {
            return true;
        }
        if (text != null && !text.isEmpty()) {
            return true;
        }
        if (actions != null && !actions.isEmpty()) {
            return true;
        }
        try {
            return w.getItemId() > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String clean(String s) {
        if (s == null) {
            return "";
        }
        return Text.removeTags(s).replace('\n', ' ').trim();
    }

    private static String formatActions(IWidget w) {
        try {
            String[] a = w.getActions();
            if (a == null || a.length == 0) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (String x : a) {
                if (x != null && !x.isBlank()) {
                    if (sb.length() > 0) {
                        sb.append(" | ");
                    }
                    sb.append(x);
                }
            }
            return sb.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /**
     * Korte “wat is dit?”-tekst voor in de dump en om te kopiëren naar {@link WidgetRegistry} als verify-fragment.
     */
    public static String deriveHumanLabel(String name, String text, String actions) {
        if (name != null && !name.isEmpty()) {
            return truncate(name, 72);
        }
        if (text != null && !text.isEmpty()) {
            return truncate(text, 72);
        }
        if (actions != null && !actions.isEmpty()) {
            int bar = actions.indexOf(" | ");
            String first = bar > 0 ? actions.substring(0, bar) : actions;
            return truncate(first.trim(), 72);
        }
        return "";
    }

    private static String formatLine(int scanGroup, int scanChild, IWidget w, String name, String text, String actions) {
        int packed = safeId(w);
        int ifaceGroup = packed > 0 ? (packed >>> 16) : -1;
        int ifaceChild = packed > 0 ? (packed & 0xFFFF) : -1;
        Point canvas = safeCanvas(w);
        Rectangle b = safeBounds(w);
        String humanLabel = deriveHumanLabel(name, text, actions);
        return String.format(Locale.ROOT,
                "scanRoot=%d,%d | iface=%d,%d | id=%d | type=%d | humanLabel=\"%s\" | canvas=(%d,%d) bounds=[%d,%d %dx%d] | item=%d | name=%s | text=%s | actions=[%s]",
                scanGroup, scanChild, ifaceGroup, ifaceChild, packed, safeType(w),
                humanLabel.replace("\"", "'"),
                canvas.getX(), canvas.getY(),
                b.x, b.y, b.width, b.height,
                safeItem(w),
                truncate(name, 72), truncate(text, 96), truncate(actions, 96));
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "...";
    }

    private static int safeId(IWidget w) {
        try {
            return w.getId();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static int safeType(IWidget w) {
        try {
            return w.getType();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static int safeItem(IWidget w) {
        try {
            return w.getItemId();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static Point safeCanvas(IWidget w) {
        try {
            Point p = w.getCanvasLocation();
            return p != null ? p : new Point(-1, -1);
        } catch (Throwable ignored) {
            return new Point(-1, -1);
        }
    }

    private static Rectangle safeBounds(IWidget w) {
        try {
            Rectangle r = w.getBounds();
            return r != null ? r : new Rectangle();
        } catch (Throwable ignored) {
            return new Rectangle();
        }
    }

    private static List<IWidget> flatten(IWidget root) {
        List<IWidget> out = new ArrayList<>();
        Deque<IWidget> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            IWidget w = q.poll();
            if (w == null) {
                continue;
            }
            out.add(w);
            enqueueAll(q, w.getChildren());
            enqueueAll(q, w.getDynamicChildren());
            enqueueAll(q, w.getNestedChildren());
        }
        return out;
    }

    private static void enqueueAll(Deque<IWidget> q, IWidget[] arr) {
        if (arr == null) {
            return;
        }
        for (IWidget ch : arr) {
            if (ch != null) {
                q.add(ch);
            }
        }
    }

    /** Resultaat voor hover-overlay (Storm-widget API). */
    public static final class WidgetHoverInfo {
        public final int scanGroup;
        public final int scanChild;
        public final int packedId;
        public final int ifaceGroup;
        public final int ifaceChild;
        public final String humanLabel;
        public final String name;
        public final String text;
        public final String actions;
        public final int canvasX;
        public final int canvasY;
        public final int boundsW;
        public final int boundsH;

        WidgetHoverInfo(int scanGroup, int scanChild, IWidget w, String name, String text, String actions) {
            this.scanGroup = scanGroup;
            this.scanChild = scanChild;
            int packed = safeId(w);
            this.packedId = packed;
            this.ifaceGroup = packed > 0 ? (packed >>> 16) : -1;
            this.ifaceChild = packed > 0 ? (packed & 0xFFFF) : -1;
            this.name = name;
            this.text = text;
            this.actions = actions;
            this.humanLabel = deriveHumanLabel(name, text, actions);
            Point canvas = safeCanvas(w);
            this.canvasX = canvas.getX();
            this.canvasY = canvas.getY();
            Rectangle b = safeBounds(w);
            this.boundsW = b.width;
            this.boundsH = b.height;
        }
    }

    /**
     * Kleinste zichtbare widget onder canvas-muiscoördinaat (Storm {@link Widgets} API).
     * Betrouwbaarder dan alleen RuneLite {@code getBounds()} op game widgets.
     */
    public static WidgetHoverInfo findSmallestStormWidgetAt(int canvasX, int canvasY) {
        BestHit best = new BestHit();
        outer:
        for (int g = 0; g <= MAX_GROUP; g++) {
            for (int c = 0; c <= MAX_CHILD; c++) {
                IWidget root;
                try {
                    root = Widgets.get(g, c);
                } catch (Throwable ignored) {
                    continue;
                }
                if (root == null) {
                    continue;
                }
                visitStormHit(root, g, c, canvasX, canvasY, best);
                if (best.w != null && best.area <= 4) {
                    break outer;
                }
            }
        }
        if (best.w == null) {
            return null;
        }
        return new WidgetHoverInfo(best.scanGroup, best.scanChild, best.w,
                clean(best.w.getName()), clean(best.w.getText()), formatActions(best.w));
    }

    private static void visitStormHit(IWidget w, int scanGroup, int scanChild, int mx, int my, BestHit best) {
        if (w == null) {
            return;
        }
        try {
            if (w.isHidden() || !w.isVisible()) {
                return;
            }
        } catch (Throwable ignored) {
            return;
        }
        Rectangle b = safeBounds(w);
        Point loc = safeCanvas(w);
        int bw = b.width > 0 ? b.width : safeWidth(w);
        int bh = b.height > 0 ? b.height : safeHeight(w);
        int bx = loc.getX() >= 0 ? loc.getX() : b.x;
        int by = loc.getY() >= 0 ? loc.getY() : b.y;
        if (bw > 0 && bh > 0
                && mx >= bx && mx < bx + bw
                && my >= by && my < by + bh) {
            int area = bw * bh;
            if (area < best.area) {
                best.area = area;
                best.w = w;
                best.scanGroup = scanGroup;
                best.scanChild = scanChild;
            }
        }
        visitStormChildren(w.getChildren(), scanGroup, scanChild, mx, my, best);
        visitStormChildren(w.getDynamicChildren(), scanGroup, scanChild, mx, my, best);
        visitStormChildren(w.getNestedChildren(), scanGroup, scanChild, mx, my, best);
    }

    private static void visitStormChildren(IWidget[] children, int scanGroup, int scanChild, int mx, int my, BestHit best) {
        if (children == null) {
            return;
        }
        for (IWidget ch : children) {
            if (ch != null) {
                visitStormHit(ch, scanGroup, scanChild, mx, my, best);
            }
        }
    }

    private static int safeWidth(IWidget w) {
        try {
            return Math.max(0, w.getWidth());
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static int safeHeight(IWidget w) {
        try {
            return Math.max(0, w.getHeight());
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static final class BestHit {
        IWidget w;
        int scanGroup;
        int scanChild;
        int area = Integer.MAX_VALUE;
    }
}
