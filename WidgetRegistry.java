package com.combatbot;

import net.runelite.client.util.Text;
import net.storm.api.domain.widgets.IWidget;
import net.storm.sdk.widgets.Widgets;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Vaste aliases voor widgets die je uit de inspector-dump haalt ({@code id=…} of {@code iface=g,c}).
 * Daarna kun je overal {@link #get(String)} of {@link Widgets#get(int)} met {@link #getPackedId(String)} gebruiken
 * i.p.v. opnieuw te scannen of te dumpen.
 * <p>
 * Gebruik {@code humanLabel="…"} uit de dump (of een stukje {@code name}/{@code text}/{@code actions}) als
 * {@code mustContainFragment} zodat {@link #getVerified(String)} kan controleren of je nog het juiste id hebt.
 */
public final class WidgetRegistry {

    private static final ConcurrentHashMap<String, Integer> ALIAS_TO_PACKED = new ConcurrentHashMap<>();
    /** Optioneel: fragment dat in naam, tekst of acties van de widget moet voorkomen (case-insensitief). */
    private static final ConcurrentHashMap<String, String> ALIAS_TO_VERIFY_FRAGMENT = new ConcurrentHashMap<>();

    private WidgetRegistry() {
    }

    /**
     * Registreer via packed component-id uit de dumpregel ({@code id=10551358}).
     * Eventuele eerdere verify-tekst voor deze alias wordt gewist.
     */
    public static void registerPacked(String alias, int packedComponentId) {
        if (alias == null || alias.isBlank() || packedComponentId <= 0) {
            return;
        }
        String key = normalizeAlias(alias);
        ALIAS_TO_PACKED.put(key, packedComponentId);
        ALIAS_TO_VERIFY_FRAGMENT.remove(key);
    }

    /**
     * Zelfde als {@link #registerPacked(String, int)}, maar bewaart een fragment om later te controleren
     * (bijv. {@code humanLabel="Inventory"} of {@code Wiki} uit de dump).
     */
    public static void registerPacked(String alias, int packedComponentId, String mustContainFragment) {
        if (alias == null || alias.isBlank() || packedComponentId <= 0) {
            return;
        }
        String key = normalizeAlias(alias);
        ALIAS_TO_PACKED.put(key, packedComponentId);
        String frag = mustContainFragment == null ? "" : mustContainFragment.trim();
        if (frag.isEmpty()) {
            ALIAS_TO_VERIFY_FRAGMENT.remove(key);
        } else {
            ALIAS_TO_VERIFY_FRAGMENT.put(key, frag);
        }
    }

    /**
     * Registreer via group + child uit de dump ({@code iface=161,62}).
     */
    public static void registerIface(String alias, int group, int child) {
        int packed = (group << 16) | (child & 0xFFFF);
        registerPacked(alias, packed);
    }

    public static void registerIface(String alias, int group, int child, String mustContainFragment) {
        int packed = (group << 16) | (child & 0xFFFF);
        registerPacked(alias, packed, mustContainFragment);
    }

    public static Integer getPackedId(String alias) {
        if (alias == null || alias.isBlank()) {
            return null;
        }
        return ALIAS_TO_PACKED.get(normalizeAlias(alias));
    }

    /**
     * Huidige widget ophalen (kan {@code null} zijn als niet geregistreerd of niet geladen).
     */
    public static IWidget get(String alias) {
        if (alias == null || alias.isBlank()) {
            return null;
        }
        Integer packed = getPackedId(alias);
        if (packed == null) {
            return null;
        }
        try {
            return Widgets.get(packed);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Fragment uit {@link #registerPacked(String, int, String)} (leeg als niet gezet).
     */
    public static String getVerifyFragment(String alias) {
        if (alias == null || alias.isBlank()) {
            return "";
        }
        String f = ALIAS_TO_VERIFY_FRAGMENT.get(normalizeAlias(alias));
        return f == null ? "" : f;
    }

    /**
     * Zoals {@link #get(String)}, maar als er een verify-fragment is geregistreerd en de live widget
     * dat niet meer in naam/tekst/acties heeft, wordt {@code null} teruggegeven en een regel naar {@link DebugLog} geschreven.
     */
    public static IWidget getVerified(String alias) {
        IWidget w = get(alias);
        String frag = getVerifyFragment(alias);
        if (frag.isEmpty()) {
            return w;
        }
        if (w == null) {
            DebugLog.log("WIDGET", "WidgetRegistry verify: alias \"" + alias + "\" — widget null (niet geladen?) verwachtte fragment: " + frag);
            return null;
        }
        if (!widgetContainsFragment(w, frag)) {
            DebugLog.log("WIDGET", "WidgetRegistry verify MISLUKT alias \"" + alias + "\" id=" + safeId(w)
                    + " — verwachtte fragment \"" + frag + "\" in naam/tekst/acties. "
                    + "Dump opnieuw: verkeerd id of UI gewijzigd.");
            return null;
        }
        return w;
    }

    private static int safeId(IWidget w) {
        try {
            return w.getId();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static boolean widgetContainsFragment(IWidget w, String fragment) {
        String h = fragment.toLowerCase(Locale.ROOT).trim();
        if (h.isEmpty()) {
            return true;
        }
        try {
            String n = cleanWidgetString(w.getName());
            String t = cleanWidgetString(w.getText());
            StringBuilder acts = new StringBuilder();
            String[] a = w.getActions();
            if (a != null) {
                for (String x : a) {
                    if (x != null && !x.isBlank()) {
                        if (acts.length() > 0) {
                            acts.append(' ');
                        }
                        acts.append(x);
                    }
                }
            }
            String hay = (n + " " + t + " " + acts).toLowerCase(Locale.ROOT);
            return hay.contains(h);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String cleanWidgetString(String s) {
        if (s == null) {
            return "";
        }
        return Text.removeTags(s).replace('\n', ' ').trim();
    }

    public static boolean isRegistered(String alias) {
        return alias != null && !alias.isBlank() && ALIAS_TO_PACKED.containsKey(normalizeAlias(alias));
    }

    public static boolean unregister(String alias) {
        if (alias == null) {
            return false;
        }
        String key = normalizeAlias(alias);
        ALIAS_TO_VERIFY_FRAGMENT.remove(key);
        return ALIAS_TO_PACKED.remove(key) != null;
    }

    public static void clear() {
        ALIAS_TO_PACKED.clear();
        ALIAS_TO_VERIFY_FRAGMENT.clear();
    }

    public static Map<String, Integer> snapshot() {
        return Collections.unmodifiableMap(new ConcurrentHashMap<>(ALIAS_TO_PACKED));
    }

    private static String normalizeAlias(String alias) {
        return alias == null ? "" : alias.trim();
    }
}
