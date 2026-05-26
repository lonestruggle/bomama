package com.combatbot;

import net.storm.sdk.items.Bank;
import net.storm.sdk.items.GrandExchange;
import net.storm.sdk.items.Inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.ToIntFunction;

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
 *
 * <p>Bank-bezoek vóór GE: gebruik {@link BankSnapshotPlanner} / {@link BankHelper#walkToNearestFullBank(String...)}
 * zodat een lege snapshot geen nutteloze bankloop triggert.</p>
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
        GE_NOT_AVAILABLE,
        /** Per-account policy verbiedt aankoop van dit item (zie GeShopPolicy + Account-edit dialog). */
        BLOCKED_BY_ACCOUNT_POLICY
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
        return buyWithEscalation(itemName, quantity, startPrice, isEnabled, true);
    }

    /**
     * @param closeGeWhenDone false = GE open laten voor volgende koop in dezelfde sessie (fishing multi-buy)
     */
    public static synchronized RestockResult buyWithEscalation(
            String itemName,
            int quantity,
            int startPrice,
            java.util.function.BooleanSupplier isEnabled,
            boolean closeGeWhenDone) {
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
                isEnabled,
                closeGeWhenDone
        );
    }

    public static synchronized RestockResult buyWithEscalationConfigured(String itemName,
                                                                         int quantity,
                                                                         int startPrice,
                                                                         long offerWaitMs,
                                                                         int increasePct,
                                                                         int maxIncreases) {
        debug("[GE] start: " + quantity + "x " + itemName + " @ " + startPrice + "gp");
        return buyWithEscalationConfiguredCore(itemName, quantity, startPrice, offerWaitMs, increasePct, maxIncreases, null, true);
    }

    private static synchronized RestockResult buyWithEscalationConfiguredCancelAware(String itemName,
                                                                                      int quantity,
                                                                                      int startPrice,
                                                                                      long offerWaitMs,
                                                                                      int increasePct,
                                                                                      int maxIncreases,
                                                                                      java.util.function.BooleanSupplier isEnabled,
                                                                                      boolean closeGeWhenDone) {
        debug("[GE] start(cancel-aware): " + quantity + "x " + itemName + " @ " + startPrice + "gp"
                + " closeGe=" + closeGeWhenDone);
        return buyWithEscalationConfiguredCore(itemName, quantity, startPrice, offerWaitMs, increasePct, maxIncreases, isEnabled, closeGeWhenDone);
    }

    private static RestockResult buyWithEscalationConfiguredCore(String itemName,
                                                                  int quantity,
                                                                  int startPrice,
                                                                  long offerWaitMs,
                                                                  int increasePct,
                                                                  int maxIncreases,
                                                                  java.util.function.BooleanSupplier isEnabled,
                                                                  boolean closeGeWhenDone) {

        if (isEnabled != null && !isEnabled.getAsBoolean()) {
            debug("[GE] cancelled before start -> FAILED");
            closeGeIfOpen();
            return RestockResult.FAILED;
        }

        if (quantity <= 0 || itemName == null || itemName.trim().isEmpty()) {
            debug("[GE] ongeldige input -> FAILED");
            return RestockResult.FAILED;
        }

        // Per-account policy: mag dit item überhaupt gekocht worden?
        if (!GeShopPolicy.allowsBuy(itemName)) {
            debug("[GE] BLOKKADE per-account policy: '" + itemName + "' (categorie="
                    + GeShopPolicy.categoryFor(itemName) + ") staat UIT in account-tab. -> BLOCKED");
            return RestockResult.BLOCKED_BY_ACCOUNT_POLICY;
        }

        // Per-account cap toepassen op de gevraagde hoeveelheid (bv. Law rune cap = 30).
        int cappedQty = GeShopPolicy.effectiveQuantity(itemName, quantity);
        if (cappedQty != quantity) {
            debug("[GE] per-account cap actief: '" + itemName + "' " + quantity + " -> " + cappedQty);
            quantity = cappedQty;
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
        if (!prepareGeBuyInterface()) {
            debug("[GE] buy-scherm niet bereikbaar — retry zonder GE te sluiten");
            sleep(500, 900);
            if (!prepareGeBuyInterface()) {
                debug("[GE] buy-scherm nog steeds niet bereikbaar -> FAILED");
                finishGeSession(closeGeWhenDone);
                return RestockResult.FAILED;
            }
        }

        if (GrandExchange.canCollect()) {
            debug("[GE] bestaande offer(s) ophalen");
            GrandExchange.collect(false);
            sleep(DELAY_AFTER_COLLECT_MS_MIN, DELAY_AFTER_COLLECT_MS_MAX);
            invNow = Inventory.getCount(true, itemName);
            if (invNow >= quantity) {
                debug("[GE] item na collect in inv (" + invNow + ") -> SUCCESS");
                finishGeSession(closeGeWhenDone);
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
                closeGeIfOpen();
                return RestockResult.FAILED;
            }

            invNow = Inventory.getCount(true, itemName);
            if (invNow >= quantity) {
                debug("[GE] item al in inv aan start poging " + (attempt + 1) + " -> SUCCESS");
                finishGeSession(closeGeWhenDone);
                return RestockResult.SUCCESS;
            }

            debug("buyWithEscalation: poging " + (attempt + 1) + "/" + (effectiveMaxIncreases + 1) + " prijs=" + currentPrice);

            int remainingBase = quantity - invNow;
            if (remainingBase <= 0) {
                debug("[GE] remaining<=0 -> SUCCESS");
                finishGeSession(closeGeWhenDone);
                return RestockResult.SUCCESS;
            }

            int coinsNow = Inventory.getCount(true, "Coins");
            int minNeededNow = Math.max(1, remainingBase) * Math.max(1, currentPrice);
            if (coinsNow < minNeededNow) {
                debug("[GE] te weinig coins voor poging: have=" + coinsNow + " need~=" + minNeededNow
                        + " (" + remainingBase + "x@" + currentPrice + ") -> FAILED");
                finishGeSession(closeGeWhenDone);
                return RestockResult.FAILED;
            }

            int extraAffordable = Math.max(0, (coinsNow / Math.max(1, currentPrice)) - remainingBase);
            int extraToBuy = Math.min(desiredExtraUnits, extraAffordable);
            // Per-account cap geldt ook voor bulk buffer: nooit méér totaal kopen dan de cap toelaat.
            int totalIfAdded = invNow + remainingBase + extraToBuy;
            int policyCap = GeShopPolicy.effectiveQuantity(itemName, totalIfAdded);
            if (policyCap < totalIfAdded) {
                int allowedExtra = Math.max(0, policyCap - invNow - remainingBase);
                if (allowedExtra < extraToBuy) {
                    debug("[GE] policy-cap reduceert bulk buffer: " + extraToBuy + " -> " + allowedExtra);
                    extraToBuy = allowedExtra;
                }
            }
            int remaining = remainingBase + extraToBuy;
            if (extraToBuy > 0) {
                debug("[GE] koopbuffer deze poging: base=" + remainingBase + " extra=" + extraToBuy
                        + " => qty=" + remaining + " @" + currentPrice + "gp");
            }

            abortAllActiveGeOffers();
            sleep(280, 480);

            boolean offerPlaced = placeBuyOfferExchange(itemName, remaining, currentPrice, isEnabled, attempt > 0);
            if (!offerPlaced) {
                debug("[GE] offer niet geplaatst -> abort alle slots + collect");
                abortAllActiveGeOffers();
                collectToInventory();
                sleep(220, 420);
                if (Inventory.getCount(true, itemName) >= quantity) {
                    debug("[GE] item in inv na collect -> SUCCESS");
                    finishGeSession(closeGeWhenDone);
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
                        finishGeSession(closeGeWhenDone);
                        return RestockResult.SUCCESS;
                    }
                    sleep(500, 900);
                    if (Inventory.getCount(true, itemName) >= quantity) {
                        finishGeSession(closeGeWhenDone);
                        return RestockResult.SUCCESS;
                    }
                }
                debug("[GE] offer niet gevuld -> abort alle slots + collect");
                abortAllActiveGeOffers();
                collectToInventory();
                sleep(220, 420);
                if (Inventory.getCount(true, itemName) >= quantity) {
                    debug("[GE] item in inv na abort+collect -> SUCCESS");
                    finishGeSession(closeGeWhenDone);
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
                int escalated = Math.max(1, (int) Math.ceil(currentPrice * factor));
                // Minimaal +1 gp zodat we niet op dezelfde prijs blijven hangen (guide-cap / afronding).
                currentPrice = Math.max(escalated, currentPrice + 1);
                debug("[GE] prijs verhoogd naar " + currentPrice + "gp (+" + effectiveIncreasePct + "%, min +1gp), volgende poging");
                sleep(DELAY_BETWEEN_ATTEMPTS_MS_MIN, DELAY_BETWEEN_ATTEMPTS_MS_MAX);
            }
        }

        debug("[GE] alle pogingen gefaald -> FAILED");
        finishGeSession(closeGeWhenDone);
        return RestockResult.FAILED;
    }

    private static void finishGeSession(boolean closeGeWhenDone) {
        if (closeGeWhenDone) {
            closeGe();
        }
    }

    private static boolean placeBuyOfferExchange(String itemName, int quantity, int price,
            java.util.function.BooleanSupplier isEnabled, boolean escalatedAttempt) {
        if (isEnabled != null && !isEnabled.getAsBoolean()) return false;
        if (!GrandExchange.isOpen()) {
            if (!openGrandExchange()) return false;
            sleep(DELAY_AFTER_OPEN_GE_MS_MIN, DELAY_AFTER_OPEN_GE_MS_MAX);
        }
        prepareGeBuyInterface();
        if (GrandExchange.canCollect()) {
            debug("[GE] Collect bestaande offer voor we nieuwe plaatsen");
            GrandExchange.collect(false);
            sleep(DELAY_AFTER_COLLECT_MS_MIN, DELAY_AFTER_COLLECT_MS_MAX);
        }
        sleep(DELAY_BEFORE_BUY_MS_MIN, DELAY_BEFORE_BUY_MS_MAX);
        int bid = Math.max(1, price);
        try {
            debug("[GE] exchange() buy " + itemName + " qty=" + quantity + " @" + bid + "gp");
            boolean done = GrandExchange.exchange(true, itemName, quantity, bid, true, true);
            if (done) {
                sleep(DELAY_AFTER_BUY_MS_MIN, DELAY_AFTER_BUY_MS_MAX);
                if (GrandExchange.canCollect()) {
                    collectToInventory();
                }
                return true;
            }
        } catch (Exception e) {
            debug("[GE] exchange() buy mislukt: " + e.getMessage() + " -> handmatige flow");
        }
        debug("[GE] handmatige buy-flow voor " + itemName + " qty=" + quantity + " price=" + bid
                + (escalatedAttempt ? " (escalatie)" : ""));
        return placeBuyOfferManual(itemName, quantity, bid, isEnabled, escalatedAttempt);
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

    /** Annuleer alle actieve GE-slots (voorkomt dat een oude offer de prijs blokkeert). */
    private static void abortAllActiveGeOffers() {
        boolean aborted = false;
        try {
            java.util.List<net.runelite.api.GrandExchangeOffer> offers = GrandExchange.getOffers();
            if (offers != null) {
                for (net.runelite.api.GrandExchangeOffer offer : offers) {
                    if (offer == null || offer.getState() == null) {
                        continue;
                    }
                    String stateName = offer.getState().name();
                    if (stateName.contains("BUYING") || stateName.contains("SELLING") || stateName.contains("PENDING")) {
                        try {
                            GrandExchange.abortOffer(offer.getItemId());
                            aborted = true;
                        } catch (Exception e) {
                            debug("abortAllActiveGeOffers itemId: " + e.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            debug("abortAllActiveGeOffers: " + e.getMessage());
        }
        if (aborted) {
            sleep(650, 1000);
        }
    }

    private static boolean placeBuyOfferManual(String itemName, int quantity, int price,
            java.util.function.BooleanSupplier isEnabled, boolean escalatedAttempt) {
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
        String exactName = BeginnerClueReference.canonicalKitItemName(itemName);
        if (exactName == null || exactName.isEmpty()) {
            exactName = itemName;
        }
        int unitPriceQuick = escalatedAttempt
                ? Math.max(1, price)
                : normalizeRequestedPrice(itemName, price);
        try {
            debug("[GE] exchange() quick buy " + exactName + " qty=" + quantity + " @" + unitPriceQuick);
            boolean quick = GrandExchange.exchange(true, exactName, quantity, unitPriceQuick, true, true);
            if (quick) {
                sleep(DELAY_AFTER_BUY_MS_MIN, DELAY_AFTER_BUY_MS_MAX, isEnabled);
                if (isEnabled != null && !isEnabled.getAsBoolean()) {
                    return false;
                }
                return true;
            }
        } catch (Exception e) {
            debug("[GE] exchange() quick mislukt: " + e.getMessage());
        }

        debug("[GE] handmatig: zoekbalk open, typ itemnaam");
        sleep(200, 380, isEnabled);
        if (isEnabled != null && !isEnabled.getAsBoolean()) {
            return false;
        }
        debug("[GE] handmatig: exact select '" + exactName + "'");
        if (!GeHelper.typeItemNameAndSelectExactFresh(exactName, isEnabled)) {
            debug("[GE] handmatig: geen exact GE-resultaat voor '" + exactName + "'");
            return false;
        }

        sleep(400, 650, isEnabled);
        if (isEnabled != null && !isEnabled.getAsBoolean()) {
            return false;
        }
        if (!waitForCondition(() -> !GrandExchange.isSearchingItem(), 3500, isEnabled)) {
            debug("[GE] handmatig: zoekbalk nog open na select — één retry");
            if (!GeHelper.typeItemNameAndSelectExactFresh(exactName, isEnabled)) {
                return false;
            }
            if (!waitForCondition(() -> !GrandExchange.isSearchingItem(), 2500, isEnabled)) {
                debug("[GE] handmatig: nog in search-state na retry");
                return false;
            }
        }

        sleep(450, 750);
        String selectedItem = GrandExchange.getItemName();
        debug("[GE] handmatig: geselecteerd item: " + selectedItem);
        if (selectedItem != null && !GeHelper.matchesExactGeItemName(exactName, selectedItem)) {
            debug("[GE] handmatig: verkeerd item geselecteerd (wil " + exactName + ", was " + selectedItem + ")");
            return false;
        }

        int unitPrice = escalatedAttempt
                ? Math.max(1, price)
                : normalizeRequestedPrice(itemName, price);
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

        // Dure / 'precieze' runes — kleine buffer (max +20) zodat we niet 160+ Law runes kopen
        // terwijl de bot er per teleport maar 1 nodig heeft. Eerder gold de generieke 500..1000
        // buffer voor élke "rune" en dan stopte de coin-affordability ergens halverwege.
        boolean isExpensiveRune = isRune && (
                n.contains("law")
                        || n.contains("death")
                        || n.contains("nature")
                        || n.contains("cosmic")
                        || n.contains("soul")
                        || n.contains("blood")
                        || n.contains("wrath")
                        || n.contains("astral")
        );
        if (isExpensiveRune) {
            return 5 + random.nextInt(16); // +5..+20
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

    /**
     * GE kan open zijn op het overzicht zonder actief koop-slot — zorg dat createBuyOffer/search bereikbaar is.
     */
    private static boolean prepareGeBuyInterface() {
        if (!GrandExchange.isOpen()) {
            return false;
        }
        try {
            if (GrandExchange.isSearchingItem()) {
                return true;
            }
            String selected = GrandExchange.getItemName();
            if (selected != null && !selected.trim().isEmpty()) {
                return true;
            }
        } catch (Exception ignored) {
        }
        try {
            debug("[GE] prepareGeBuyInterface: createBuyOffer()");
            GrandExchange.createBuyOffer();
            sleep(400, 650);
        } catch (Exception e) {
            debug("[GE] prepareGeBuyInterface createBuyOffer: " + e.getMessage());
            return false;
        }
        try {
            if (GrandExchange.isSearchingItem()) {
                return true;
            }
            String selected = GrandExchange.getItemName();
            return selected != null && !selected.trim().isEmpty();
        } catch (Exception ignored) {
            return false;
        }
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
        sleep(min, max, null);
    }

    private static void sleep(int min, int max, java.util.function.BooleanSupplier isEnabled) {
        if (isEnabled != null && !isEnabled.getAsBoolean()) {
            return;
        }
        try {
            Thread.sleep(min + random.nextInt(Math.max(1, max - min)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Meerdere koop-offers in één GE-sessie: GE één keer open, offers plaatsen zonder tussentijds abort,
     * daarna wachten en één keer collect. Geen GE sluiten tussen items.
     *
     * @return aantal items uit de lijst dat na de batch nog steeds niet (voldoende) in inventory ligt
     */
    public static synchronized int buyMultipleInOneSession(List<String> itemNames, ToIntFunction<String> startPricePerItem) {
        return buyMultipleInOneSession(itemNames, startPricePerItem, null);
    }

    /**
     * @deprecated Prefer één item per game-tick via {@link #buyWithEscalation} — deze batch blokkeert lang.
     */
    public static synchronized int buyMultipleInOneSession(List<String> itemNames,
            ToIntFunction<String> startPricePerItem,
            java.util.function.BooleanSupplier isEnabled) {
        if (itemNames == null || itemNames.isEmpty()) {
            return 0;
        }
        if (isEnabled != null && !isEnabled.getAsBoolean()) {
            closeGeIfOpen();
            return itemNames.size();
        }
        List<String> pending = new ArrayList<>();
        for (String item : itemNames) {
            if (item == null || item.trim().isEmpty()) {
                continue;
            }
            if (!GeShopPolicy.allowsBuy(item)) {
                debug("[GE] batch skip policy: " + item);
                continue;
            }
            if (Inventory.getCount(true, item) >= 1) {
                continue;
            }
            if (!pending.contains(item)) {
                pending.add(item);
            }
        }
        if (pending.isEmpty()) {
            debug("[GE] batch: niets te kopen");
            return 0;
        }

        debug("[GE] batch start: " + pending.size() + " item(s) in één sessie");

        if (Bank.isOpen()) {
            try {
                Bank.close();
                sleep(400, 700);
            } catch (Throwable ignored) {
            }
        }

        if (!GrandExchange.isOpen()) {
            if (!openGrandExchange()) {
                debug("[GE] batch: GE niet open -> alles mist");
                return pending.size();
            }
            sleep(DELAY_AFTER_OPEN_GE_MS_MIN, DELAY_AFTER_OPEN_GE_MS_MAX);
        }

        if (!prepareGeBuyInterface()) {
            debug("[GE] batch: buy-scherm niet bereikbaar");
            closeGe();
            return pending.size();
        }

        if (GrandExchange.canCollect()) {
            GrandExchange.collect(false);
            sleep(DELAY_AFTER_COLLECT_MS_MIN, DELAY_AFTER_COLLECT_MS_MAX);
        }

        abortAllActiveGeOffers();
        sleep(280, 480);

        int placed = 0;
        for (String item : pending) {
            if (isEnabled != null && !isEnabled.getAsBoolean()) {
                debug("[GE] batch geannuleerd (bot uit)");
                closeGeIfOpen();
                return pending.size();
            }
            if (Inventory.getCount(true, item) >= 1) {
                continue;
            }
            int startPrice = startPricePerItem != null ? startPricePerItem.applyAsInt(item) : 1000;
            int price = normalizeRequestedPrice(item, Math.max(1, startPrice));
            int coins = Inventory.getCount(true, "Coins");
            if (coins < price) {
                debug("[GE] batch skip (gp): " + item + " need~" + price + " have=" + coins);
                continue;
            }
            debug("[GE] batch plaats offer: " + item + " @ " + price + "gp");
            if (placeBuyOfferManual(item, 1, price, isEnabled, true)) {
                placed++;
                sleep(DELAY_BETWEEN_ATTEMPTS_MS_MIN, DELAY_BETWEEN_ATTEMPTS_MS_MAX, isEnabled);
                try {
                    GrandExchange.createBuyOffer();
                    sleep(300, 500, isEnabled);
                } catch (Throwable ignored) {
                }
            } else {
                debug("[GE] batch plaats mislukt: " + item);
            }
        }

        if (placed == 0) {
            debug("[GE] batch: geen offers geplaatst");
            closeGe();
            return pending.size();
        }

        long waitMs = Math.min(90_000L, 8_000L + placed * 9_000L);
        debug("[GE] batch wacht max " + (waitMs / 1000) + "s op " + placed + " offer(s)");
        waitForOfferFilled(waitMs, isEnabled);
        collectToInventory();
        sleep(400, 700);
        if (GrandExchange.canCollect()) {
            collectToInventory();
        }

        int stillMissing = 0;
        for (String item : pending) {
            if (Inventory.getCount(true, item) < 1) {
                stillMissing++;
                debug("[GE] batch mist nog: " + item);
            } else {
                debug("[GE] batch ok: " + item);
            }
        }

        debug("[GE] batch klaar — nog " + stillMissing + " item(s) missen, GE blijft open voor eventuele retry");
        return stillMissing;
    }

    /** Sluit GE na batch (aanroepen vanuit clue-handler als prep klaar is). */
    public static synchronized void closeGeIfOpen() {
        if (GrandExchange.isOpen()) {
            closeGe();
        }
    }
}
