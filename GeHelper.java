package com.combatbot;

import net.storm.sdk.input.Keyboard;

import java.awt.event.KeyEvent;
import java.util.Random;

/**
 * Grand Exchange helpers: letter-by-letter typing for item search (anti-ban).
 * Na het typen moet Enter worden gedrukt om het eerste zoekresultaat te selecteren.
 */
public final class GeHelper {

    private static final Random RANDOM = new Random();
    private static final int TYPING_DELAY_MIN_MS = 120;
    private static final int TYPING_DELAY_MAX_MS = 260;

    /**
     * Alleen letters; geen Enter. OSRS GE filtert de lijst vaak live, maar sommige states hebben Enter
     * nodig — gebruik {@link #pressEnterAfterGeSearchTyping()} of {@link #typeItemNameAndSelectFirst(String)}.
     */
    public static void typeItemNameLetterByLetter(String itemName) {
        if (itemName == null || itemName.isEmpty()) return;
        for (int i = 0; i < itemName.length(); i++) {
            String ch = String.valueOf(itemName.charAt(i));
            Keyboard.type(ch, false);
            try {
                Thread.sleep(TYPING_DELAY_MIN_MS + RANDOM.nextInt(Math.max(1, TYPING_DELAY_MAX_MS - TYPING_DELAY_MIN_MS)));
                if (itemName.charAt(i) == ' ') {
                    Thread.sleep(80 + RANDOM.nextInt(120));
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
     * Best-effort clearing for GE search field.
     * We avoid key combos and simply send multiple backspaces/deletes.
     */
    public static void clearSearchInputBestEffort() {
        try {
            for (int i = 0; i < 28; i++) {
                Keyboard.type(String.valueOf((char) KeyEvent.VK_BACK_SPACE), false);
                Thread.sleep(18 + RANDOM.nextInt(28));
            }
            for (int i = 0; i < 6; i++) {
                Keyboard.type(String.valueOf((char) KeyEvent.VK_DELETE), false);
                Thread.sleep(18 + RANDOM.nextInt(28));
            }
            Thread.sleep(130 + RANDOM.nextInt(220));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // best-effort
        }
    }
}
