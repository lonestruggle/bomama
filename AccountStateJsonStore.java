package com.combatbot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Per-account voortgang in één JSON-bestand (o.a. starter-fase, Jon geclaimd, imps eerste GE-trip, quest-stappen).
 * Pad: {@code ~/.runelite/combatbot-account-state.json}
 */
public final class AccountStateJsonStore {

    private static final int VERSION = 1;
    private static final Object FILE_LOCK = new Object();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private AccountStateJsonStore() {
    }

    public static Path defaultPath() {
        return Paths.get(System.getProperty("user.home"), ".runelite", "combatbot-account-state.json");
    }

    public static final class AccountProgressFile {
        public int version = VERSION;
        public Map<String, AccountEntry> accounts = new LinkedHashMap<>();
    }

    public static final class AccountEntry {
        /** Weergavenaam (RSN) zoals in-game. */
        public String displayName;
        /** Adventurer Jon (Claim) minstens één keer afgerond voor dit account. */
        public boolean jonClaimComplete;
        /** Laatst bekende {@link StarterSkillHandler} fase (enum-naam). */
        public String starterPhase;
        /** Alleen ≥ {@link AccountQuestProgressStore#VAMPIRE_SLAYER_STEP_DONE} als klaar; anders 0 (tussenstappen niet opgeslagen). */
        public int vampireSlayerStep;
        public boolean hammerFromImp;
        /** Eénmalige starter-imps-trip: Home TP → GE → 1000 Mind + Staff of air. */
        public boolean impsStarterFirstGeCycleDone;
        /** GE utility-buy: black/mithril/adamant axe pakket al gekocht voor dit account. */
        public boolean geUtilityAxePackBought;
        /** GE utility-buy: fly fishing rod + 1000 feathers al gekocht voor dit account. */
        public boolean geUtilityFishingPackBought;
        /** Leesbare questregel (o.a. Vampire Slayer), voor in het JSON-bestand. */
        public String questSummary;
        /** 1x (of handmatig) bankkalibratie voltooid voor dit account. */
        public boolean bankCalibrated;
        /** Laatste snapshot van bekende bank-items (komma-gescheiden itemnamen). */
        public String knownBankItemsCsv;
        /** Compact JSON met key-item -> quantity in bank ten tijde van snapshot. */
        public String knownBankItemQtyJson;
        /** Laatst bekende coin stack in bank (snapshot-moment). */
        public long knownBankCoins;
        /** Laatst bekende coin stack in inventory (snapshot-moment). */
        public long knownInventoryCoins;
        /** Rolling gemiddelde (EWMA) van gp-opbrengst per Imps-banktrip voor dit account. */
        public int impsTripAvgGp;
        /** Aantal Imps-trip samples dat is meegenomen in de gemiddelde opbrengst. */
        public int impsTripSamples;
        /** Laatste gemeten Imps-tripopbrengst in gp. */
        public int impsTripLastGp;
        /** Totaal aantal anti-ban acties dat voor dit account is uitgevoerd. */
        public int antiBanActionsTotal;
        /** Laatste anti-ban actie label (menselijk leesbaar). */
        public String antiBanLastAction;
        /** Epoch-ms van de laatste anti-ban actie. */
        public long antiBanLastActionMs;
        /** Per anti-ban actie-key hoeveel keer uitgevoerd (bijv. CAMERA_MMB=12). */
        public Map<String, Integer> antiBanActionCounts = new LinkedHashMap<>();
        public long lastBankCalibrationMs;
        public long lastUpdatedMs;

        // ----- Chronicle (Varrock teleport via Diango Chronicle + Teleport cards) -----
        /** {@code true} als we ergens (inv/equip/bank) een Chronicle bezitten. Update bij bank-snapshot of trade. */
        public boolean chronicleOwned;
        /** Laatst bekende charges-aantal (decrementeer bij elke teleport, increment bij card-use). */
        public int chronicleCharges;
        /** Losse Teleport cards in bank (bijgehouden via bank-snapshot/withdraw/deposit). */
        public int chronicleCardsBank;
        /** Losse Teleport cards in inventory (bijgehouden via withdraw/deposit/use-on-chronicle). */
        public int chronicleCardsInv;
        /** Epoch-ms van laatste sync van bovenstaande chronicle-velden. */
        public long chronicleLastSyncMs;
        /** Vink uit/aan via account-tab; gespiegeld vanuit ManagedJagexAccountRow voor snel uitlezen. */
        public boolean chronicleTeleportEnabled;
    }

    public static AccountProgressFile loadFile() {
        synchronized (FILE_LOCK) {
            Path p = defaultPath();
            if (!Files.isRegularFile(p)) {
                return new AccountProgressFile();
            }
            try {
                String json = Files.readString(p, StandardCharsets.UTF_8);
                if (json == null || json.trim().isEmpty()) {
                    return new AccountProgressFile();
                }
                AccountProgressFile root = GSON.fromJson(json, AccountProgressFile.class);
                if (root == null) {
                    return new AccountProgressFile();
                }
                if (root.accounts == null) {
                    root.accounts = new LinkedHashMap<>();
                }
                if (root.version <= 0) {
                    root.version = VERSION;
                }
                return root;
            } catch (IOException e) {
                DebugLog.log("AccountStateJson", "load: " + e.getMessage());
                return new AccountProgressFile();
            }
        }
    }

    public static void saveFile(AccountProgressFile root) {
        if (root == null) {
            return;
        }
        synchronized (FILE_LOCK) {
            root.version = VERSION;
            if (root.accounts == null) {
                root.accounts = new LinkedHashMap<>();
            }
            Path p = defaultPath();
            try {
                Path dir = p.getParent();
                if (dir != null) {
                    Files.createDirectories(dir);
                }
                Files.writeString(p, GSON.toJson(root), StandardCharsets.UTF_8);
            } catch (IOException e) {
                DebugLog.log("AccountStateJson", "save: " + e.getMessage());
            }
        }
    }

    private static String key(String displayName) {
        if (displayName == null) {
            return "";
        }
        return displayName.trim().toLowerCase(Locale.ROOT);
    }

    public static AccountEntry getEntry(String displayName) {
        String k = key(displayName);
        if (k.isEmpty()) {
            return null;
        }
        AccountProgressFile root = loadFile();
        return root.accounts.get(k);
    }

    public static void update(String displayName, Consumer<AccountEntry> mutator) {
        if (mutator == null) {
            return;
        }
        String k = key(displayName);
        if (k.isEmpty()) {
            return;
        }
        synchronized (FILE_LOCK) {
            AccountProgressFile root = loadFile();
            AccountEntry e = root.accounts.get(k);
            if (e == null) {
                e = new AccountEntry();
                e.displayName = displayName.trim();
                root.accounts.put(k, e);
            }
            mutator.accept(e);
            e.lastUpdatedMs = System.currentTimeMillis();
            saveFile(root);
        }
    }

    public static boolean isImpsStarterFirstGeCycleDone(String displayName) {
        AccountEntry e = getEntry(displayName);
        return e != null && e.impsStarterFirstGeCycleDone;
    }

    public static void markImpsStarterFirstGeCycleDone(String displayName) {
        update(displayName, e -> e.impsStarterFirstGeCycleDone = true);
    }

    public static boolean isGeUtilityAxePackBought(String displayName) {
        AccountEntry e = getEntry(displayName);
        return e != null && e.geUtilityAxePackBought;
    }

    public static void markGeUtilityAxePackBought(String displayName) {
        update(displayName, e -> e.geUtilityAxePackBought = true);
    }

    public static boolean isGeUtilityFishingPackBought(String displayName) {
        AccountEntry e = getEntry(displayName);
        return e != null && e.geUtilityFishingPackBought;
    }

    public static void markGeUtilityFishingPackBought(String displayName) {
        update(displayName, e -> e.geUtilityFishingPackBought = true);
    }

    public static boolean isBankCalibrated(String displayName) {
        AccountEntry e = getEntry(displayName);
        return e != null && e.bankCalibrated;
    }

    public static void putBankSnapshot(String displayName, String knownBankItemsCsv, String knownBankItemQtyJson,
                                       long bankCoins, long invCoins, boolean calibrated) {
        update(displayName, e -> {
            e.knownBankItemsCsv = knownBankItemsCsv;
            e.knownBankItemQtyJson = knownBankItemQtyJson;
            e.knownBankCoins = Math.max(0, bankCoins);
            e.knownInventoryCoins = Math.max(0, invCoins);
            if (calibrated) {
                e.bankCalibrated = true;
                e.lastBankCalibrationMs = System.currentTimeMillis();
            }
        });
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Double> knownBankQtyMap(AccountEntry e) {
        if (e == null || e.knownBankItemQtyJson == null || e.knownBankItemQtyJson.trim().isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Double> raw = GSON.fromJson(e.knownBankItemQtyJson, Map.class);
            return raw != null ? raw : new LinkedHashMap<>();
        } catch (Throwable t) {
            DebugLog.log("AccountStateJson", "knownBankQtyMap: " + t.getMessage());
            return new LinkedHashMap<>();
        }
    }

    public static int knownBankQty(AccountEntry e, String itemName) {
        if (e == null || itemName == null || itemName.trim().isEmpty()) {
            return 0;
        }
        Map<String, Double> qty = knownBankQtyMap(e);
        for (Map.Entry<String, Double> it : qty.entrySet()) {
            if (it.getKey() != null && it.getKey().equalsIgnoreCase(itemName.trim())) {
                Double v = it.getValue();
                return v != null ? Math.max(0, v.intValue()) : 0;
            }
        }
        return 0;
    }

    public static boolean hasKnownBankItem(AccountEntry e, String itemName) {
        return knownBankQty(e, itemName) > 0;
    }

    public static long knownCoinsApprox(AccountEntry e) {
        if (e == null) {
            return 0L;
        }
        return Math.max(0L, e.knownInventoryCoins) + Math.max(0L, e.knownBankCoins);
    }

    public static int getImpsTripAvgGp(String displayName) {
        AccountEntry e = getEntry(displayName);
        return e != null ? Math.max(0, e.impsTripAvgGp) : 0;
    }

    public static void recordImpsTripGp(String displayName, int tripGp) {
        int sample = Math.max(0, tripGp);
        if (sample <= 0) {
            return;
        }
        update(displayName, e -> {
            int prev = Math.max(0, e.impsTripAvgGp);
            // EWMA: 70% historisch + 30% nieuwste trip.
            int next = prev <= 0 ? sample : (int) Math.round(prev * 0.70 + sample * 0.30);
            e.impsTripAvgGp = Math.max(0, next);
            e.impsTripLastGp = sample;
            e.impsTripSamples = Math.min(Integer.MAX_VALUE, Math.max(0, e.impsTripSamples) + 1);
        });
    }

    public static void recordAntiBanAction(String displayName, String actionKey, String actionLabel) {
        if (displayName == null || displayName.trim().isEmpty()) {
            return;
        }
        final String safeKey = (actionKey == null || actionKey.trim().isEmpty())
                ? "UNKNOWN"
                : actionKey.trim().toUpperCase(Locale.ROOT);
        final String safeLabel = (actionLabel == null || actionLabel.trim().isEmpty())
                ? safeKey
                : actionLabel.trim();
        update(displayName, e -> {
            if (e.antiBanActionCounts == null) {
                e.antiBanActionCounts = new LinkedHashMap<>();
            }
            int prev = Math.max(0, e.antiBanActionCounts.getOrDefault(safeKey, 0));
            e.antiBanActionCounts.put(safeKey, prev + 1);
            e.antiBanActionsTotal = Math.max(0, e.antiBanActionsTotal) + 1;
            e.antiBanLastAction = safeLabel;
            e.antiBanLastActionMs = System.currentTimeMillis();
        });
    }

    /** Questvelden (Vampire Slayer + hammer) — gebruikt door {@link AccountQuestProgressStore}. */
    public static AccountQuestProgressStore.QuestEntry getQuestEntry(String displayName) {
        AccountEntry e = getEntry(displayName);
        if (e == null) {
            return null;
        }
        AccountQuestProgressStore.QuestEntry q = new AccountQuestProgressStore.QuestEntry();
        q.vampireSlayerStep = e.vampireSlayerStep;
        q.hammerFromImp = e.hammerFromImp;
        return q;
    }

    // ============================================================
    // Chronicle helpers (Varrock teleport via Diango Chronicle + Teleport cards)
    // ============================================================

    /**
     * Schrijf nieuwe chronicle-state weg. Velden waarvan je niets weet: geef {@code -1} of {@code null} mee.
     * Daarmee blijft de bestaande waarde behouden.
     */
    public static void putChronicleState(String displayName, Boolean owned, Integer charges,
                                         Integer cardsBank, Integer cardsInv) {
        update(displayName, e -> {
            if (owned != null) e.chronicleOwned = owned;
            if (charges != null && charges >= 0) e.chronicleCharges = Math.max(0, charges);
            if (cardsBank != null && cardsBank >= 0) e.chronicleCardsBank = Math.max(0, cardsBank);
            if (cardsInv != null && cardsInv >= 0) e.chronicleCardsInv = Math.max(0, cardsInv);
            e.chronicleLastSyncMs = System.currentTimeMillis();
        });
    }

    public static void setChronicleTeleportEnabled(String displayName, boolean enabled) {
        update(displayName, e -> e.chronicleTeleportEnabled = enabled);
    }

    /** Decrement charges met 1 nadat een teleport gelukt is. */
    public static void decrementChronicleCharges(String displayName) {
        update(displayName, e -> {
            int n = Math.max(0, e.chronicleCharges) - 1;
            e.chronicleCharges = Math.max(0, n);
            e.chronicleLastSyncMs = System.currentTimeMillis();
        });
    }

    /** Card → chronicle gebruikt: charges += 1, cardsInv -= 1. */
    public static void registerCardChargedFromInv(String displayName) {
        update(displayName, e -> {
            e.chronicleCharges = Math.max(0, e.chronicleCharges) + 1;
            int inv = Math.max(0, e.chronicleCardsInv) - 1;
            e.chronicleCardsInv = Math.max(0, inv);
            e.chronicleLastSyncMs = System.currentTimeMillis();
        });
    }

    public static void putQuestEntry(String displayName, AccountQuestProgressStore.QuestEntry quest) {
        if (quest == null) {
            return;
        }
        update(displayName, e -> {
            int step = quest.vampireSlayerStep >= AccountQuestProgressStore.VAMPIRE_SLAYER_STEP_DONE
                    ? quest.vampireSlayerStep
                    : 0;
            e.vampireSlayerStep = step;
            e.hammerFromImp = quest.hammerFromImp;
            StringBuilder sb = new StringBuilder();
            if (step >= AccountQuestProgressStore.VAMPIRE_SLAYER_STEP_DONE) {
                sb.append("Vampire Slayer: voltooid");
            }
            if (quest.hammerFromImp) {
                if (sb.length() > 0) {
                    sb.append("; ");
                }
                sb.append("Hammer (imp): ja");
            }
            e.questSummary = sb.length() > 0 ? sb.toString() : null;
        });
    }
}
