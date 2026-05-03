package com.combatbot;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Gedeelde, rustige pauzes tussen bank-acties (deposit/withdraw) — vergelijkbaar met
 * {@link UniversalBankingManager} (±1 game tick tussen klikken).
 */
public final class HumanBanking {

    private HumanBanking() {
    }

    /**
     * Extra pauze vóór een bank-/booth-open poging (minder spam na trap of lopen).
     * Alleen gebruiken waar de caller al een tick-delay heeft; optioneel.
     */
    public static void pauseBeforeBankOpenClick() {
        sleepMs(320 + ThreadLocalRandom.current().nextInt(381));
    }

    /** Tussen elke deposit- of withdraw-klik (~550–1050 ms). */
    public static void pauseBetweenActions() {
        sleepMs(550 + ThreadLocalRandom.current().nextInt(501));
    }

    /** Iets langere pauze vóór {@link net.storm.sdk.items.Bank#close()} na een reeks acties. */
    public static void pauseBeforeClose() {
        sleepMs(450 + ThreadLocalRandom.current().nextInt(451));
    }

    /** Korte pauze nadat de bank gesloten is (UI wegsterven). */
    public static void pauseAfterClose() {
        sleepMs(280 + ThreadLocalRandom.current().nextInt(321));
    }

    /** Tussen equip/wear klikken na withdraw. */
    public static void pauseWearOrWield() {
        sleepMs(200 + ThreadLocalRandom.current().nextInt(251));
    }

    /** Na laatste withdraw, vóór inventaris-acties (equip); bank blijft open. */
    public static void pauseAfterWithdrawBatch() {
        sleepMs(350 + ThreadLocalRandom.current().nextInt(351));
    }

    private static void sleepMs(int ms) {
        try {
            Thread.sleep(Math.max(50, ms));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
