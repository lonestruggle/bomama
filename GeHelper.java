package com.combatbot;

import net.storm.sdk.input.Keyboard;
import net.storm.sdk.items.GrandExchange;

import java.awt.event.KeyEvent;
import java.util.List;
import java.util.Random;

/**
 * Grand Exchange helpers: letter-by-letter typing for item search (anti-ban).
 * Na het typen moet Enter worden gedrukt om het eerste zoekresultaat te selecteren.
 */
public final class GeHelper {

    private static final Random RANDOM = new Random();
    private static final int TYPING_DELAY_MIN_MS = 95;
    private static final int TYPING_DELAY_MAX_MS = 195;
    private static final int CLEAR_BACKSPACE_COUNT = 14;

    /**
     * Alleen letters; geen Enter. OSRS GE filtert de lijst vaak live, maar sommige states hebben Enter
     * nodig — gebruik {@link #pressEnterAfterGeSearchTyping()} of {@link #typeItemNameAndSelectFirst(String)}.
     */
    public static void typeItemNameLetterByLetter(String itemName) {
        typeItemNameLetterByLetter(itemName, null);
    }

    public static void typeItemNameLetterByLetter(String itemName, java.util.function.BooleanSupplier proceed) {
        if (itemName == null || itemName.isEmpty()) {
            return;
        }
        for (int i = 0; i < itemName.length(); i++) {
            if (!shouldProceed(proceed)) {
                return;
            }
            String ch = String.valueOf(itemName.charAt(i));
            Keyboard.type(ch, false);
            try {
                Thread.sleep(TYPING_DELAY_MIN_MS + RANDOM.nextInt(Math.max(1, TYPING_DELAY_MAX_MS - TYPING_DELAY_MIN_MS)));
                if (itemName.charAt(i) == ' ') {
                    Thread.sleep(60 + RANDOM.nextInt(80));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * Eén Enter na typen in de GE-zoekbalk (geen extra letters). Sommige client-states geven pas
     * {@link net.storm.sdk.items.GrandExchange#getSearchResults()} terug na Enter; dit is géén officiële SDK-verplichting,
     * wel een veelgebruikte UI-aanvulling naast letter-voor-letter typen.
     */
    public static void pressEnterAfterGeSearchTyping() {
        try {
            Thread.sleep(180 + RANDOM.nextInt(220));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        Keyboard.type(String.valueOf((char) KeyEvent.VK_ENTER), false);
    }

    /**
     * Typ de itemnaam letter-voor-letter en druk dan Enter om het eerste zoekresultaat
     * in de GE te selecteren (nodig om daarna prijs te zetten en te bevestigen).
     */
    public static void typeItemNameAndSelectFirst(String itemName) {
        typeItemNameLetterByLetter(itemName);
        try {
            Thread.sleep(200 + RANDOM.nextInt(300));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Keyboard.type(String.valueOf((char) KeyEvent.VK_ENTER), false);
    }

    /** Typ cijfers met kleine variabele delay, minder bot-achtig dan in 1 burst. */
    public static void typeDigitsHumanLike(String digits) {
        if (digits == null || digits.isEmpty()) return;
        for (int i = 0; i < digits.length(); i++) {
            char ch = digits.charAt(i);
            if (!Character.isDigit(ch)) continue;
            Keyboard.type(String.valueOf(ch), false);
            try {
                Thread.sleep(24 + RANDOM.nextInt(36));
                if (i > 0 && i % 3 == 0) {
                    Thread.sleep(30 + RANDOM.nextInt(60));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /** Enter met lichte random-reactietijd. */
    public static void pressEnterHumanLike() {
        try {
            Thread.sleep(320 + RANDOM.nextInt(280));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        Keyboard.type(String.valueOf((char) KeyEvent.VK_ENTER), false);
    }

    /**
     * Clear the GE search input first, then type/select.
     * This prevents duplicated queries like "Law runeLaw rune*".
     */
    public static void typeItemNameAndSelectFirstFresh(String itemName) {
        clearSearchInputBestEffort();
        typeItemNameAndSelectFirst(itemName);
    }

    /**
     * GE-zoeken zonder blind Enter op het eerste resultaat (bv. Gilded spade vóór Spade).
     * Selecteert alleen een rij waar de naam exact overeenkomt met {@code exactItemName}.
     */
    public static boolean typeItemNameAndSelectExactFresh(String exactItemName) {
        return typeItemNameAndSelectExactFresh(exactItemName, null);
    }

    /**
     * @param proceed null of altijd doorgaan; false = stop (bot uit / GE sluiten)
     */
    public static boolean typeItemNameAndSelectExactFresh(String exactItemName,
            java.util.function.BooleanSupplier proceed) {
        if (exactItemName == null || exactItemName.isEmpty() || !shouldProceed(proceed)) {
            return false;
        }
        clearSearchInputBestEffort(proceed);
        if (!shouldProceed(proceed)) {
            return false;
        }
        String query = geSearchQuery(exactItemName);
        typeItemNameLetterByLetter(query, proceed);
        if (!shouldProceed(proceed)) {
            return false;
        }
        try {
            Thread.sleep(280 + RANDOM.nextInt(320));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (selectExactGeSearchResult(exactItemName)) {
            return true;
        }
        pressEnterAfterGeSearchTyping();
        try {
            Thread.sleep(350 + RANDOM.nextInt(400));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        long deadline = System.currentTimeMillis() + 2800L;
        while (System.currentTimeMillis() < deadline) {
            if (!shouldProceed(proceed)) {
                return false;
            }
            if (selectExactGeSearchResult(exactItemName)) {
                return true;
            }
            try {
                Thread.sleep(120 + RANDOM.nextInt(160));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    public static boolean matchesExactGeItemName(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return expected.trim().equalsIgnoreCase(actual.trim());
    }

    private static String geSearchQuery(String exactItemName) {
        if ("Spade".equalsIgnoreCase(exactItemName.trim())) {
            return "spade";
        }
        return exactItemName;
    }

    private static boolean selectExactGeSearchResult(String exactItemName) {
        try {
            List<GrandExchange.GESearchResult> results = GrandExchange.getSearchResults();
            if (results == null || results.isEmpty()) {
                return false;
            }
            for (GrandExchange.GESearchResult result : results) {
                if (result == null || result.getItemName() == null) {
                    continue;
                }
                if (matchesExactGeItemName(exactItemName, result.getItemName())) {
                    result.chooseOption();
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Best-effort clearing for GE search field.
     * We avoid key combos and simply send multiple backspaces/deletes.
     */
    public static void clearSearchInputBestEffort() {
        clearSearchInputBestEffort(null);
    }

    /** Kort wissen — geen 30+ backspaces (blokkeerde client en overschreef typen). */
    public static void clearSearchInputBestEffort(java.util.function.BooleanSupplier proceed) {
        try {
            for (int i = 0; i < CLEAR_BACKSPACE_COUNT; i++) {
                if (!shouldProceed(proceed)) {
                    return;
                }
                Keyboard.type(String.valueOf((char) KeyEvent.VK_BACK_SPACE), false);
                Thread.sleep(12 + RANDOM.nextInt(18));
            }
            Thread.sleep(80 + RANDOM.nextInt(120));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // best-effort
        }
    }

    private static boolean shouldProceed(java.util.function.BooleanSupplier proceed) {
        return proceed == null || proceed.getAsBoolean();
    }
}
