package com.combatbot;

import net.storm.sdk.items.Bank;
import net.storm.sdk.items.GrandExchange;
import net.storm.sdk.items.Inventory;

import java.util.List;
import java.util.Random;

/**
 * GeRestockHelper — GE-inkoop met {@link GrandExchange#exchange(boolean, String, int, int, boolean, boolean)}
 * als hoofdpad, handmatige fallback als {@code exchange} false blijft.
 *
 * <p><b>Let op {@code quantity}:</b> gewenst <b>totaal</b> van dit item in inventory na de flow
 * (zoals Giants food-GE); intern wordt {@code remaining = quantity - huidige inv} berekend.
 *
 * <p>Te hoge prijzen komen meestal van: (1) plugin-config ({@code impsAmmoRestockPrice}, combat food-basis, enz.),
 * (2) schattingen in o.a. {@code ImpsHandler} ({@code estimateStaffGeStartPrice}, {@code estimateGeStartPriceByItemId}),
 * (3) escalatie in deze helper (+10% per mislukte poging). Zodra het item in de GE-setup staat, wordt de
 * prijs per stuk begrensd t.o.v. {@link GrandExchange#getGuidePrice()} om de guide-price waarschuwing te vermijden.
 */
public final class GeRestockHelper {

    private GeRestockHelper() {}

    private static final Random random = new Random();
    private static final int DEFAULT_MAX_PRICE_INCREASES = 8;
    private static final long DEFAULT_OFFER_WAIT_MS = 10_000;
    private static final int DEFAULT_INCREASE_PERCENT = 15;
    private static final long POLL_INTERVAL_MS = 650;

    /** Maximaal deze factor boven GE-guide (alleen als je hoger biedt dan guide); lager dan guide blijft ongewijzigd. */
    private static final double MAX_PER_UNIT_PRICE_VS_GUIDE = 2.00;

    /**
     * Kortere pauzes dan de oude v2-backup (1200–2200 ms na open GE): minder “wachten om menselijk te lijken”.
     */
    private static final int DELAY_AFTER_OPEN_GE_MS_MIN = 380;
    private static final int DELAY_AFTER_OPEN_GE_MS_MAX = 720;
    private static final int DELAY_BEFORE_BUY_MS_MIN = 160;
    private static final int DELAY_BEFORE_BUY_MS_MAX = 380;
    private static final int DELAY_AFTER_BUY_MS_MIN = 280;
    private static final int DELAY_AFTER_BUY_MS_MAX = 520;
    private static final int DELAY_AFTER_COLLECT_MS_MIN = 240;
    private static final int DELAY_AFTER_COLLECT_MS_MAX = 480;
    private static final int DELAY_BETWEEN_ATTEMPTS_MS_MIN = 280;
    private static final int DELAY_BETWEEN_ATTEMPTS_MS_MAX = 580;
    private static final int EXTRA_BUY_BUFFER_MIN = 500;
    private static final int EXTRA_BUY_BUFFER_MAX = 1000;

    public enum RestockResult {
        SUCCESS,
        FAILED,
        GE_NOT_AVAILABLE
    }

    private static void debug(String msg) {
        DebugLog.log("GeRestock", msg);
    }

    public static synchronized RestockResult buyWithEscalation(String itemName, int quantity, int startPrice) {
        return buyWithEscalationConfigured(
                itemName,
                quantity,
                startPrice,
                DEFAULT_OFFER_WAIT_MS,
                DEFAULT_INCREASE_PERCENT,
                DEFAULT_MAX_PRICE_INCREASES
        );
    }

    public static synchronized RestockResult buyWithEscalation(
            String itemName, int quantity, int startPrice, java.util.function.BooleanSupplier isEnabled) {
        if (isEnabled == null) {
            return buyWithEscalation(itemName, quantity, startPrice);
        }
        return buyWithEscalationConfiguredCancelAware(
                itemName,
                quantity,
                startPrice,
                DEFAULT_OFFER_WAIT_MS,
                DEFAULT_INCREASE_PERCENT,
                DEFAULT_MAX_PRICE_INCREASES,
                isEnabled
        );
    }

    public static synchronized RestockResult buyWithEscalationConfigured(String itemName,
                                                                         int quantity,
                                                                         int startPrice,
                                                                         long offerWaitMs,
                                                                         int increasePct,
                                                                         int maxIncreases) {
        debug("[GE] start: " + quantity + "x " + itemName + " @ " + startPrice + "gp");
        return buyWithEscalationConfiguredCore(itemName, quantity, startPrice, offerWaitMs, increasePct, maxIncreases, null);
    }

    private static synchronized RestockResult buyWithEscalationConfiguredCancelAware(String itemName,
                                                                                      int quantity,
                                                                                      int startPrice,
                                                                                      long offerWaitMs,
                                                                                      int increasePct,
                                                                                      int maxIncreases,
                                                                                      java.util.function.BooleanSupplier isEnabled) {
        debug("[GE] start(cancel-aware): " + quantity + "x " + itemName + " @ " + startPrice + "gp");
        return buyWithEscalationConfiguredCore(itemName, quantity, startPrice, offerWaitMs, increasePct, maxIncreases, isEnabled);
    }

    private static RestockResult buyWithEscalationConfiguredCore(String itemName,
                                                                  int quantity,
                                                                  int startPrice,
                                                                  long offerWaitMs,
                                                                  int increasePct,
                                                                  int maxIncreases,
                                                                  java.util.function.BooleanSupplier isEnabled) {

        if (isEnabled != null && !isEnabled.getAsBoolean()) {
            debug("[GE] cancelled before start -> FAILED");
            return RestockResult.FAILED;
        }

        if (quantity <= 0 || itemName == null || itemName.trim().isEmpty()) {
            debug("[GE] ongeldige input -> FAILED");
            return RestockResult.FAILED;
        }

        int invNow = Inventory.getCount(true, itemName);
        if (invNow >= quantity) {
            debug("[GE] al in inventory (" + invNow + "), skip GE -> SUCCESS");
            return RestockResult.SUCCESS;
        }

        if (Bank.isOpen()) {
            debug("[GE] bank open -> sluiten");
            try {
                Bank.close();
                sleep(400, 700);
            } catch (Exception e) {
                debug("[GE] bank sluiten: " + e.getMessage());
            }
            sleep(400, 700);
        }

        if (!GrandExchange.isOpen()) {
            debug("[GE] GE openen");
            if (!openGrandExchange()) {
                debug("[GE] kan GE niet openen -> GE_NOT_AVAILABLE");
                return RestockResult.GE_NOT_AVAILABLE;
            }
            sleep(DELAY_AFTER_OPEN_GE_MS_MIN, DELAY_AFTER_OPEN_GE_MS_MAX);
        }
        debug("[GE] GE is open");

        if (GrandExchange.canCollect()) {
            debug("[GE] bestaande offer(s) ophalen");
            GrandExchange.collect(false);
            sleep(DELAY_AFTER_COLLECT_MS_MIN, DELAY_AFTER_COLLECT_MS_MAX);
            invNow = Inventory.getCount(true, itemName);
            if (invNow >= quantity) {
                debug("[GE] item na collect in inv (" + invNow + ") -> SUCCESS");
                closeGe();
                return RestockResult.SUCCESS;
            }
        }

        long effectiveOfferWaitMs = offerWaitMs <= 0 ? DEFAULT_OFFER_WAIT_MS : offerWaitMs;
        int effectiveIncreasePct = increasePct <= 0 ? DEFAULT_INCREASE_PERCENT : increasePct;
        int effectiveMaxIncreases = Math.max(0, maxIncreases);

        int currentPrice = Math.max(1, startPrice);
        int desiredExtraUnits = computeDesiredExtraUnits(itemName);
        if (desiredExtraUnits > 0) {
            debug("[GE] bulk buffer actief voor " + itemName + ": +" + desiredExtraUnits + " (als coins het toelaten)");
        }

        for (int attempt = 0; attempt <= effectiveMaxIncreases; attempt++) {
            if (isEnabled != null && !isEnabled.getAsBoolean()) {
                debug("[GE] cancelled during attempts -> FAILED");
                closeGe();
                return RestockResult.FAILED;
            }

            invNow = Inventory.getCount(true, itemName);
            if (invNow >= quantity) {
                debug("[GE] item al in inv aan start poging " + (attempt + 1) + " -> SUCCESS");
                closeGe();
                return RestockResult.SUCCESS;
            }

            debug("buyWithEscalation: poging " + (attempt + 1) + "/" + (effectiveMaxIncreases + 1) + " prijs=" + currentPrice);

            int remainingBase = quantity - invNow;
            if (remainingBase <= 0) {
                debug("[GE] remaining<=0 -> SUCCESS");
                closeGe();
                return RestockResult.SUCCESS;
            }

            int coinsNow = Inventory.getCount(true, "Coins");
            int minNeededNow = Math.max(1, remainingBase) * Math.max(1, currentPrice);
            if (coinsNow < minNeededNow) {
                debug("[GE] te weinig coins voor poging: have=" + coinsNow + " need~=" + minNeededNow
                        + " (" + remainingBase + "x@" + currentPrice + ") -> FAILED");
                closeGe();
                return RestockResult.FAILED;
            }

            int extraAffordable = Math.max(0, (coinsNow / Math.max(1, currentPrice)) - remainingBase);
            int extraToBuy = Math.min(desiredExtraUnits, extraAffordable);
            int remaining = remainingBase + extraToBuy;
            if (extraToBuy > 0) {
                debug("[GE] koopbuffer deze poging: base=" + remainingBase + " extra=" + extraToBuy
                        + " => qty=" + remaining + " @" + currentPrice + "gp");
            }

            boolean offerPlaced = placeBuyOfferExchange(itemName, remaining, currentPrice, isEnabled);
            if (!offerPlaced) {
                debug("[GE] offer niet geplaatst -> abort + collect");
                abortOfferByName(itemName);
                collectToInventory();
                sleep(220, 420);
                if (Inventory.getCount(true, itemName) >= quantity) {
                    debug("[GE] item in inv na collect -> SUCCESS");
                    closeGe();
                    return RestockResult.SUCCESS;
                }
            } else {
                boolean filled = waitForOfferFilled(effectiveOfferWaitMs, isEnabled);
                if (filled) {
                    debug("[GE] collect naar inventory");
                    collectToInventory();
                    sleep(320, 550);
                    boolean hasItem = waitUntilItemInInventory(itemName, quantity, 5000, isEnabled);
                    int invCount = Inventory.getCount(true, itemName);
                    debug("[GE] na collect: " + itemName + " in inv = " + invCount + ", hasItem=" + hasItem);
                    if (hasItem || invCount >= quantity) {
                        debug("[GE] SUCCESS — gekocht @ " + currentPrice + "gp");
                        closeGe();
                        return RestockResult.SUCCESS;
                    }
                    sleep(500, 900);
                    if (Inventory.getCount(true, itemName) >= quantity) {
                        closeGe();
                        return RestockResult.SUCCESS;
                    }
                }
                debug("[GE] offer niet gevuld -> abort + collect");
                abortOfferByName(itemName);
                collectToInventory();
                sleep(220, 420);
                if (Inventory.getCount(true, itemName) >= quantity) {
                    debug("[GE] item in inv na abort+collect -> SUCCESS");
                    closeGe();
                    return RestockResult.SUCCESS;
                }
            }

            if (attempt < effectiveMaxIncreases) {
                int posted = safeCurrentGePrice();
                if (posted > 0 && posted != currentPrice) {
                    debug("[GE] laatste geplaatste prijs was " + posted + "gp (gevraagd " + currentPrice + "gp)");
                    // Laat een lagere GE-posting niet de escalatie terugdraaien.
                    currentPrice = Math.max(currentPrice, posted);
                }
                double factor = 1.0 + (effectiveIncreasePct / 100.0);
                currentPrice = Math.max(1, (int) Math.ceil(currentPrice * factor));
                debug("[GE] prijs verhoogd naar " + currentPrice + "gp (+" + effectiveIncreasePct + "%), volgende poging");
                sleep(DELAY_BETWEEN_ATTEMPTS_MS_MIN, DELAY_BETWEEN_ATTEMPTS_MS_MAX);
            }
        }

        debug("[GE] alle pogingen gefaald -> FAILED");
        closeGe();
        return RestockResult.FAILED;
    }

    private static boolean placeBuyOfferExchange(String itemName, int quantity, int price, java.util.function.BooleanSupplier isEnabled) {
        if (isEnabled != null && !isEnabled.getAsBoolean()) return false;
        if (!GrandExchange.isOpen()) {
            if (!openGrandExchange()) return false;
            sleep(DELAY_AFTER_OPEN_GE_MS_MIN, DELAY_AFTER_OPEN_GE_MS_MAX);
        }
        if (GrandExchange.canCollect()) {
            debug("[GE] Collect bestaande offer voor we nieuwe plaatsen");
            GrandExchange.collect(false);
            sleep(DELAY_AFTER_COLLECT_MS_MIN, DELAY_AFTER_COLLECT_MS_MAX);
        }
        sleep(DELAY_BEFORE_BUY_MS_MIN, DELAY_BEFORE_BUY_MS_MAX);
        // Altijd de handmatige flow gebruiken:
        // voorkomt dubbel item-typen (snelle SDK-search + menselijke fallback).
        debug("[GE] handmatige buy-flow voor " + itemName + " qty=" + quantity + " price=" + price);
        return placeBuyOfferManual(itemName, quantity, price, isEnabled);
    }

    private static boolean waitForOfferFilled(long offerWaitMs, java.util.function.BooleanSupplier isEnabled) {
        long start = System.currentTimeMillis();
        long wait = offerWaitMs <= 0 ? DEFAULT_OFFER_WAIT_MS : offerWaitMs;
        long deadline = start + wait;
        int pollCount = 0;
        while (System.currentTimeMillis() < deadline) {
            if (isEnabled != null && !isEnabled.getAsBoolean()) {
                debug("[GE] cancelled during offer wait -> FAILED");
                return false;
            }
            if (GrandExchange.canCollect()) {
                debug("[GE] offer collectable na " + ((System.currentTimeMillis() - start) / 1000) + "s");
                return true;
            }
            pollCount++;
            if (pollCount % 3 == 0) {
                long waited = (System.currentTimeMillis() - start) / 1000;
                debug("[GE] wachten op offer... " + waited + "s");
            }
            sleep((int) POLL_INTERVAL_MS, (int) (POLL_INTERVAL_MS + 350));
        }
        boolean collectNow = GrandExchange.canCollect();
        debug("[GE] timeout na " + (wait / 1000) + "s — canCollect=" + collectNow);
        return collectNow;
    }

    private static boolean waitUntilItemInInventory(String itemName, int quantity, long timeoutMs, java.util.function.BooleanSupplier isEnabled) {
        long start = System.currentTimeMillis();
        long deadline = start + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (isEnabled != null && !isEnabled.getAsBoolean()) {
                debug("[GE] cancelled during inv wait -> FAILED");
                return false;
            }
            int count = Inventory.getCount(true, itemName);
            if (count >= quantity) {
                long waited = (System.currentTimeMillis() - start) / 1000;
                debug("[GE] item in inventory na " + waited + "s (count=" + count + ")");
                return true;
            }
            sleep(350, 600);
        }
        int count = Inventory.getCount(true, itemName);
        debug("[GE] timeout: item nog niet in inv (count=" + count + ", nodig=" + quantity + ")");
        return count >= quantity;
    }

    private static void abortOfferByName(String itemName) {
        try {
            GrandExchange.abortOffer(itemName);
            sleep(650, 1000);
        } catch (Exception e) {
            debug("abortOfferByName: exception: " + e.getMessage());
        }
    }

    private static boolean placeBuyOfferManual(String itemName, int quantity, int price, java.util.function.BooleanSupplier isEnabled) {
        debug("[GE] handmatig: " + itemName + " qty=" + quantity + " price=" + price);
        if (isEnabled != null && !isEnabled.getAsBoolean()) return false;

        if (!GrandExchange.isOpen()) {
            if (!openGrandExchange()) return false;
        }

        if (GrandExchange.canCollect()) {
            debug("[GE] handmatig: eerst collecten");
            GrandExchange.collect(false);
            sleep(400, 650);
        }

        debug("[GE] handmatig: createBuyOffer()");
        try {
            GrandExchange.createBuyOffer();
            sleep(400, 650);
        } catch (Exception e) {
            debug("[GE] handmatig createBuyOffer exception: " + e.getMessage());
            return false;
        }

        if (!waitForCondition(() -> GrandExchange.isSearchingItem(), 5000, isEnabled)) {
            debug("[GE] handmatig: search niet actief na 5s, retry createBuyOffer");
            try {
                GrandExchange.createBuyOffer();
            } catch (Exception e) {
                return false;
            }
            if (!waitForCondition(() -> GrandExchange.isSearchingItem(), 3000, isEnabled)) {
                debug("[GE] handmatig: search nog steeds niet actief, reset GE scherm");
                closeGe();
                sleep(350, 600);
                if (!openGrandExchange()) {
                    return false;
                }
                sleep(DELAY_AFTER_OPEN_GE_MS_MIN, DELAY_AFTER_OPEN_GE_MS_MAX);
                try {
                    GrandExchange.createBuyOffer();
                    sleep(350, 600);
                } catch (Exception e) {
                    return false;
                }
                if (!waitForCondition(() -> GrandExchange.isSearchingItem(), 3500, isEnabled)) {
                    debug("[GE] handmatig: search blijft inactief na GE reset");
                    return false;
                }
            }
        }
        debug("[GE] handmatig: zoekbalk open, typ itemnaam");

        sleep(240, 450);
        debug("[GE] handmatig: clear + typen + Enter '" + itemName + "'");
        GeHelper.typeItemNameAndSelectFirstFresh(itemName);

        sleep(500, 800);
        if (!waitForCondition(() -> !GrandExchange.isSearchingItem(), 5000, isEnabled)) {
            debug("[GE] handmatig: zoekbalk nog open — fallback getSearchResults");
            GrandExchange.GESearchResult matchingResult = null;
            long searchDeadline = System.currentTimeMillis() + 4000;
            while (System.currentTimeMillis() < searchDeadline) {
                if (isEnabled != null && !isEnabled.getAsBoolean()) return false;
                List<GrandExchange.GESearchResult> results = GrandExchange.getSearchResults();
                if (results != null && !results.isEmpty()) {
                    for (GrandExchange.GESearchResult result : results) {
                        if (result.getItemName() != null && result.getItemName().equalsIgnoreCase(itemName)) {
                            matchingResult = result;
                            break;
                        }
                    }
                    if (matchingResult == null) matchingResult = results.get(0);
                    try {
                        matchingResult.chooseOption();
                        sleep(400, 650);
                        break;
                    } catch (Exception e) {
                        debug("[GE] handmatig chooseOption: " + e.getMessage());
                    }
                }
                sleep(240, 420);
            }
            if (!waitForCondition(() -> !GrandExchange.isSearchingItem(), 3000, isEnabled)) {
                debug("[GE] handmatig: nog in search-state na fallback");
                return false;
            }
        }

        sleep(450, 750);
        String selectedItem = GrandExchange.getItemName();
        debug("[GE] handmatig: geselecteerd item: " + selectedItem);

        int unitPrice = normalizeRequestedPrice(itemName, price);
        debug("[GE] handmatig: setPrice(" + unitPrice + ")" + (unitPrice != price ? " (was " + price + "gp)" : ""));
        int actualPrice = trySetGePriceRobust(unitPrice);
        if (actualPrice > 0 && actualPrice != unitPrice) {
            debug("[GE] handmatig: prijs in GE na zetten = " + actualPrice + "gp (target was " + unitPrice + "gp)");
        }
        sleep(300, 500);

        if (quantity != 1) {
            debug("[GE] handmatig: setQuantity(" + quantity + ")");
            try {
                GrandExchange.setQuantity(quantity);
            } catch (Exception e) {
                debug("[GE] handmatig setQuantity: " + e.getMessage());
            }
            sleep(450, 700);
            debug("[GE] handmatig: typing quantity digits=" + quantity);
            try {
                GeHelper.typeDigitsHumanLike(String.valueOf(quantity));
            } catch (Exception e) {
                // ignore
            }
            sleep(240, 420);
            try {
                GrandExchange.setQuantity(quantity);
            } catch (Exception e2) {
                debug("[GE] handmatig setQuantity retry: " + e2.getMessage());
            }
            sleep(300, 500);
        } else {
            debug("[GE] handmatig: quantity=1, skip setQuantity");
        }

        sleep(300, 500);
        debug("[GE] handmatig: confirm()");
        try {
            GrandExchange.confirm();
        } catch (Exception e) {
            debug("[GE] handmatig confirm: " + e.getMessage());
            return false;
        }

        sleep(450, 800);
        debug("[GE] handmatig: offer bevestigd");
        return true;
    }

    /**
     * Gebruik de gevraagde prijs, maar forceer minimaal ~active-trade benadering:
     * als SDK geen echte active-trade prijs geeft, neem guide * 1.10 als baseline.
     */
    private static int normalizeRequestedPrice(String itemName, int requestedPerUnit) {
        int r = Math.max(1, requestedPerUnit);
        try {
            int guide = GrandExchange.getGuidePrice();
            if (guide <= 0) return r;
            int activeLike = Math.max(guide, (int) Math.ceil(guide * 1.10));
            int normalized = Math.max(r, activeLike);
            // Rods mogen bewust hoog geplaatst worden voor instant buy.
            if (itemName != null) {
                String n = itemName.trim().toLowerCase();
                if (n.equals("fishing rod") || n.equals("fly fishing rod")) {
                    return normalized;
                }
            }
            int cap = Math.max(activeLike, (int) Math.ceil(guide * MAX_PER_UNIT_PRICE_VS_GUIDE));
            if (normalized > cap) {
                debug("normalizeRequestedPrice: " + normalized + "gp -> " + cap + "gp (guide " + guide + "gp)");
                return cap;
            }
            return normalized;
        } catch (Throwable t) {
            return r;
        }
    }

    private static int computeDesiredExtraUnits(String itemName) {
        if (itemName == null) return 0;
        String n = itemName.trim().toLowerCase();
        boolean isRune = n.endsWith(" rune") || n.contains("rune");
        boolean isBait = n.equals("fishing bait") || n.equals("bait") || n.equals("feather") || n.contains("feather");
        boolean isArrow = n.contains("arrow");
        if (!(isRune || isBait || isArrow)) {
            return 0;
        }
        return EXTRA_BUY_BUFFER_MIN + random.nextInt(EXTRA_BUY_BUFFER_MAX - EXTRA_BUY_BUFFER_MIN + 1);
    }

    private static int trySetGePriceRobust(int unitPrice) {
        int target = Math.max(1, unitPrice);
        int actual = -1;
        try {
            GrandExchange.setPrice(target);
        } catch (Exception e) {
            debug("[GE] handmatig setPrice: " + e.getMessage());
        }
        sleep(420, 650);
        actual = safeCurrentGePrice();
        if (actual == target) return actual;
        debug("[GE] handmatig: setPrice gaf " + actual + "gp, type prijs handmatig");
        if (!canTypeGePriceNow()) {
            debug("[GE] handmatig: prijsveld lijkt niet actief (search/open item mismatch) — skip handmatig typen");
            return actual;
        }
        try {
            GeHelper.typeDigitsHumanLike(String.valueOf(target));
            sleep(180, 320);
            GeHelper.pressEnterHumanLike();
            sleep(360, 620);
        } catch (Exception e) {
            debug("[GE] handmatig price-type: " + e.getMessage());
        }
        int typedActual = safeCurrentGePrice();
        if (typedActual > 0) {
            return typedActual;
        }
        return actual;
    }

    private static boolean canTypeGePriceNow() {
        if (!GrandExchange.isOpen()) return false;
        if (GrandExchange.isSearchingItem()) return false;
        try {
            String selected = GrandExchange.getItemName();
            return selected != null && !selected.trim().isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static int safeCurrentGePrice() {
        try {
            return GrandExchange.getPrice();
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static boolean openGrandExchange() {
        try {
            GrandExchange.open();
        } catch (Exception e) {
            debug("openGrandExchange: exception: " + e.getMessage());
            return false;
        }
        sleep(450, 800);

        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (GrandExchange.isOpen()) return true;
            sleep(200, 400);
        }
        return GrandExchange.isOpen();
    }

    private static void collectToInventory() {
        try {
            if (GrandExchange.canCollect()) {
                GrandExchange.collect(false);
                sleep(DELAY_AFTER_COLLECT_MS_MIN, DELAY_AFTER_COLLECT_MS_MAX);
            }
        } catch (Exception e) {
            debug("collectToInventory: exception: " + e.getMessage());
        }
    }

    private static void closeGe() {
        try {
            net.storm.sdk.input.Keyboard.type(
                    String.valueOf((char) java.awt.event.KeyEvent.VK_ESCAPE), false);
            sleep(400, 800);
        } catch (Exception e) {
            debug("closeGe: exception: " + e.getMessage());
        }
    }

    private static boolean waitForCondition(java.util.function.BooleanSupplier condition,
                                            long timeoutMs,
                                            java.util.function.BooleanSupplier isEnabled) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (isEnabled != null && !isEnabled.getAsBoolean()) return false;
            if (condition.getAsBoolean()) return true;
            sleep(150, 320);
        }
        if (isEnabled != null && !isEnabled.getAsBoolean()) return false;
        return condition.getAsBoolean();
    }

    private static void sleep(int min, int max) {
        try {
            Thread.sleep(min + random.nextInt(Math.max(1, max - min)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
