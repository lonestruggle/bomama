package com.combatbot;

import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.storm.api.domain.actors.INPC;
import net.storm.api.domain.actors.IPlayer;
import net.storm.api.domain.items.IInventoryItem;
import net.storm.api.domain.tiles.ITileItem;
import net.storm.api.domain.tiles.ITileObject;
import net.runelite.client.util.Text;
import net.storm.api.plugins.config.ConfigManager;
import net.storm.sdk.entities.NPCs;
import net.storm.sdk.entities.Players;
import net.storm.sdk.entities.TileItems;
import net.storm.sdk.entities.TileObjects;
import net.storm.sdk.items.Bank;
import net.storm.sdk.items.DepositBox;
import net.storm.sdk.items.Equipment;
import net.storm.sdk.items.GrandExchange;
import net.storm.sdk.items.Inventory;
import net.storm.sdk.movement.Movement;
import net.storm.sdk.game.Combat;
import net.storm.sdk.game.Prices;
import net.storm.sdk.game.Skills;
import net.storm.sdk.magic.Magic;
import net.storm.api.magic.SpellBook;
import net.storm.api.widgets.Tab;
import net.storm.sdk.widgets.Tabs;
import net.storm.sdk.widgets.Widgets;
import net.storm.sdk.widgets.Dialog;
import net.storm.api.domain.widgets.IWidget;
import net.runelite.api.Skill;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.function.BooleanSupplier;

/**
 * ImpsHandler - Speciale combat modus voor het killen van Imps op Karamja.
 *
 * Flow:
 * 1. Kill Imps
 * 2. Loot: beads, Mind rune, Mind talisman, Fiendish ashes
 * 3. Scatter Fiendish ashes onmiddellijk
 * 4. Als aangevallen door scorpion: ren naar veilig punt (2826,3181)
 * 5. Als inventory vol/genoeg loot: neem boot naar Port Sarim (30gp)
 * 6. Gebruik deposit box bij Port Sarim, deposit beads + mind rune/talisman
 *    MAAR NOOIT coins deponeren - houd al je geld altijd bij je!
 * 7. Te weinig gp voor boot (30): niet naar Port Sarim lopen — eerst GE-verkoop (loot/verkooplijst) of bank/gear prep.
 * 8. Als geen coins (< impsMinCoins) na bank: home teleport -> switch naar normaal combat
 *
 * Gear prep: alleen bij het STARTEN van imps mode, niet elke trip.
 *   Nodig: wapen OF bow + 500 bronze arrows OF air staff + 500 mind runes.
 *   Pak ook AL je coins mee uit de bank.
 */
public class ImpsHandler {

    private final CombatBotConfig config;
    private final AntiBan antiBan;
    private final CombatBotPaint paint;
    private final Random random = new Random();

    /** Excluded tiles: bij Imps-mode worden markers in TileMarkerManager onder COMBAT opgeslagen (default list). */
    private TileMarkerManager tileMarkerManager;

    /** Optioneel: voor {@link AccountQuestProgressStore#setHammerFromImp} bij Hammer ground-loot. */
    private ConfigManager questProgressConfigManager;

    private Client rlClient;
    private ClientThread rlClientThread;
    private BooleanSupplier competitorWorldHopEnabled;
    private int pendingImpsHopTargetWorld = -1;
    private int pendingImpsHopStartWorld = -1;
    private int pendingImpsHopRetryCount = 0;
    private long pendingImpsHopRequestedMs = 0L;
    private long lastImpsWorldHopMs = 0L;
    private static final long IMPS_WORLD_HOP_COOLDOWN_MS = 14_000L;
    private static final long IMPS_HOP_VERIFY_TIMEOUT_MS = 22_000L;

    private static final String[] WIZARD_HAT_LOOT_NAMES = {"Blue wizard hat", "Wizard hat"};

    private static boolean isWizardHatLootName(String name) {
        if (name == null) return false;
        for (String n : WIZARD_HAT_LOOT_NAMES) {
            if (n.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    // Items worden DYNAMISCH uit config geladen (niet meer gecached)
    private static final String[] DEFAULT_LOOT_ITEMS = {
            "Black bead", "Red bead", "Yellow bead", "White bead",
            "Mind rune", "Mind talisman", "Fiendish ashes",
            "Blue wizard hat", "Wizard hat"
    };
    // Standaard items om te verkopen in de GE (als instellingen leeg zijn)
    private static final String[] DEFAULT_GE_SELL_ITEMS = {
            "Black bead", "Red bead", "Yellow bead", "White bead", "Mind talisman", "Fiendish ashes"
    };

    /** Items uit instellingen (GE verkoop items) of standaard lijst. */
    private List<String> getGeSellItemsFromConfig() {
        String raw = config.geSellLootItems();
        if (raw != null && !raw.trim().isEmpty()) {
            List<String> list = new ArrayList<>();
            for (String s : raw.split(",")) {
                String t = s.trim();
                if (!t.isEmpty()) list.add(t);
            }
            if (!list.isEmpty()) return list;
        }
        return Arrays.asList(DEFAULT_GE_SELL_ITEMS);
    }
    // Items die we NOOIT deponeren - gear, coins, ammo, runes, amulet (gear prep)
    private static final String[] KEEP_ITEMS = {
            "Coins", "Law rune", "Mind rune", "Chaos rune", "Death rune", "Nature rune",
            "Air rune", "Water rune", "Earth rune", "Fire rune",
            "Body rune", "Cosmic rune", "Astral rune", "Blood rune", "Soul rune", "Wrath rune",
            "Bronze arrow", "Iron arrow", "Steel arrow", "Mithril arrow", "Adamant arrow", "Rune arrow",
            "Amulet of power"
    };
    private static final int GENIE_LAMP_ITEM_ID = 2528;

    /**
     * Dynamische keep-list die rekening houdt met de gekozen spell.
     * Voorkomt dat benodigde runes (bijv. Air rune bij Fire Strike) gebanked worden.
     */
    private List<String> getFullKeepList() {
        List<String> keep = new ArrayList<>(Arrays.asList(KEEP_ITEMS));
        // Voeg spell-specifieke runes toe
        CombatBotConfig.ImpsMageSpell spell = getActiveMageSpell();
        if (getEffectiveStyle() == CombatBotConfig.ImpsCombatStyle.MAGE) {
            if (!keep.contains(spell.getElementalRune())) keep.add(spell.getElementalRune());
            if (!keep.contains(spell.getCatalystRune())) keep.add(spell.getCatalystRune());
            if (spell.needsAirRune() && !keep.contains("Air rune")) keep.add("Air rune");
        }
        if (config.impsMeleeOpeningAirStrike() && !starterSkillBridge) {
            if (!keep.contains("Air rune")) keep.add("Air rune");
            if (!keep.contains("Mind rune")) keep.add("Mind rune");
        }
        return keep;
    }

    private static final int BOAT_FARE = 30;
    /** Musa Point Customs officer (OSRS npc id 380). */
    private static final int CUSTOMS_OFFICER_NPC_ID = 380;
    /** Port Sarim → Karamja: Seaman Lorris, Seaman Thresnor, Captain Tobias. */
    private static final int[] PORT_SARIM_BOAT_NPC_IDS = {364, 365, 326};
    private static final int PORT_SARIM_BOAT_NPC_SEARCH_RADIUS = 22;
    /**
     * Aanlooptile vóór Customs officer — niet op NPC-tegel (2956,3143), anders pathfinder Talk-to.
     */
    private static final WorldPoint KARAMJA_BOAT_APPROACH = new WorldPoint(2954, 3144, 0);

    // Locaties
    private static final WorldPoint KARAMJA_DOCK_NPC = new WorldPoint(2956, 3143, 0);
    /** Karamja-box: vier door gebruiker opgegeven hoeken (rechthoek = min/max X/Y daarvan). */
    private static final WorldPoint KARAMJA_BOX_CORNER_1 = new WorldPoint(2962, 3130, 0);
    private static final WorldPoint KARAMJA_BOX_CORNER_2 = new WorldPoint(2960, 3190, 0);
    private static final WorldPoint KARAMJA_BOX_CORNER_3 = new WorldPoint(2816, 3210, 0);
    private static final WorldPoint KARAMJA_BOX_CORNER_4 = new WorldPoint(2815, 3136, 0);
    private static final int KARAMJA_REGION_MIN_X = minX(KARAMJA_BOX_CORNER_1, KARAMJA_BOX_CORNER_2,
            KARAMJA_BOX_CORNER_3, KARAMJA_BOX_CORNER_4);
    private static final int KARAMJA_REGION_MAX_X = maxX(KARAMJA_BOX_CORNER_1, KARAMJA_BOX_CORNER_2,
            KARAMJA_BOX_CORNER_3, KARAMJA_BOX_CORNER_4);
    private static final int KARAMJA_REGION_MIN_Y = minY(KARAMJA_BOX_CORNER_1, KARAMJA_BOX_CORNER_2,
            KARAMJA_BOX_CORNER_3, KARAMJA_BOX_CORNER_4);
    private static final int KARAMJA_REGION_MAX_Y = maxY(KARAMJA_BOX_CORNER_1, KARAMJA_BOX_CORNER_2,
            KARAMJA_BOX_CORNER_3, KARAMJA_BOX_CORNER_4);
    private static final WorldPoint PORTSARIM_DOCK_WAYPOINT = new WorldPoint(3028, 3210, 0);
    private static final WorldPoint PORTSARIM_DOCK_NPC = new WorldPoint(3027, 3218, 0);
    private static final WorldPoint PORTSARIM_DEPOSIT_BOX = new WorldPoint(3029, 3210, 0);
    private WorldPoint getPortSarimGoblinAnchor() {
        return new WorldPoint(config.impsGoblinCoinCenterX(), config.impsGoblinCoinCenterY(), 0);
    }

    private int getPortSarimGoblinRadius() {
        return Math.max(3, config.impsGoblinCoinRadius());
    }
    private static final WorldPoint DRAYNOR_BANK = new WorldPoint(3092, 3243, 0);
    private static final WorldPoint SCORPION_SAFE_POINT = new WorldPoint(2826, 3181, 0);

    // Rally point - de bot loopt EERST hierheen voordat hij gaat killen
    // Dit voorkomt pathfinding failures op lange paden vanuit de dock (radius via config.impsRallyPointRadius())
    private static final WorldPoint KARAMJA_RALLY_POINT = new WorldPoint(2830, 3182, 0);
    // Hardcoded extra imp areas (user-request): treated as one combined hunting region.
    private static final WorldPoint EXTRA_IMP_AREA_CENTER_1 = new WorldPoint(2826, 3149, 0);
    private static final WorldPoint EXTRA_IMP_AREA_CENTER_2 = new WorldPoint(2832, 3200, 0);
    /** Drop buiten-area targets kort om in/out ping-pong te voorkomen. */
    private static final long OUTSIDE_TARGET_COOLDOWN_MS = 1_800L;
    private static final int SAFE_POINT_RADIUS = 5;
    private static final int FLEE_RANDOM_OFFSET = 8;

    // Scorpion danger zones rond de vulkaan
    private static final WorldPoint SCORPION_ZONE_1 = new WorldPoint(2852, 3185, 0);
    private static final WorldPoint SCORPION_ZONE_2 = new WorldPoint(2853, 3161, 0);
    // SCORPION_ZONE_RADIUS nu configureerbaar via config.impsScorpionZoneRadius()

    // Imp teleport tracking
    private WorldPoint lastTargetPosition = null;
    private INPC currentTarget = null;

    // State
    private boolean targetWasAlive = false;
    private long lastAttackTime = 0;
    private static final long ATTACK_COOLDOWN_MS = 1800;
    private boolean shouldSwitchToNormal = false;
    private boolean isBankingTrip = false;
    private boolean isRestocking = false; // true = volwaardige bank (restock), false = deposit box (loot dump)
    private boolean fleeingFromScorpion = false;

    // Loot pickup state - voorkomt dat bot een imp aanvalt terwijl hij naar loot loopt
    private boolean pickingUpLoot = false;
    private boolean pendingWizardHatEquip = false;
    private long pendingWizardHatEquipSinceMs = 0;
    private long lastWizardHatEquipAttemptMs = 0;
    private static final long WIZARD_HAT_EQUIP_TIMEOUT_MS = 12_000;
    private static final long WIZARD_HAT_EQUIP_RETRY_MS = 650;

    // Loot delay tracking (gebruikt globale config)
    private int killsSinceLastLoot = 0;
    private long lootDelayUntil = 0;

    // Deposit box interactie state
    private long lastDepositAttempt = 0;
    private boolean quantityAllSelected = false;

    // Pre-rotation banking state
    private boolean bankingBeforeRotation = false;

    // Gear preparation state (bij switch NAAR imps - eenmalig)
    private boolean preparingGear = false;
    private boolean gearPrepComplete = false;

    // MAGE: voorkom herhaald equip-pogingen waardoor staff kan heen-en-weer springen
    private long lastMageStaffEquipTimeMs = 0;
    /** Game message "geen ammo" — Imps attack-loop gebruikt aparte timestamp van {@link CombatHandler}. */
    private long lastImpsRangedEmptyQuiverGameMessageMs = 0L;
    private static final long MAGE_STAFF_EQUIP_COOLDOWN_MS = 2500;
    /** Voorkom oneindig preferred-staff withdrawen tijdens gear prep. */
    private int preferredStaffWithdrawAttempts = 0;
    private long lastPreferredStaffWithdrawMs = 0;
    private static final int MAX_PREFERRED_STAFF_WITHDRAW_ATTEMPTS = 2;
    private static final long PREFERRED_STAFF_WITHDRAW_COOLDOWN_MS = 2200;
    /** Voorkom withdraw↔deposit arrow-lus tijdens gear prep. */
    private String gearPrepLastArrowWithdrawName = "";
    private int gearPrepArrowWithdrawStreak = 0;
    private static final int GEAR_PREP_ARROW_WITHDRAW_MAX_STREAK = 4;
    /** Voorkom armor withdraw-lus (zelfde slot/item zonder equip). */
    private String gearPrepLastArmourWithdrawKey = "";
    private int gearPrepArmourWithdrawStreak = 0;
    private static final int GEAR_PREP_ARMOUR_WITHDRAW_MAX_STREAK = 4;
    /** Eén batch-withdraw van alle armor-slots vóór equip (equip sluit bank-UI). */
    private boolean gearPrepArmourBatchWithdrawDone = false;
    /** Na bank-withdraw: alleen uit inventory equippen — bank niet opnieuw openen per stuk. */
    private boolean gearPrepInvEquipPhase = false;
    private static final int GEAR_PREP_MIN_ARROWS = 100;

    // Loot pickup cooldown - voorkom te snel klikken
    private long lastLootPickupTime = 0;
    private static final long LOOT_PICKUP_COOLDOWN_MS = 2400;
    private long lastAirStrikeOpenDebugMs = 0;

    // Idle roaming - als geen imp gevonden, loop rond in de zone
    private long lastImpFoundTime = 0;
    private boolean isRoaming = false;

    // Walking state - voorkomt dat loot/combat de loop naar hunting area onderbreekt
    private boolean walkingToHuntArea = false;
    private long outsideTargetCooldownUntilMs = 0L;
    private long lastAutoFireUpgradeLogMs = 0L;

    /**
     * Actief imp-center (rechtermuisklik / impsCenters). Als null: {@link CombatBotConfig#impsHuntingX()}/{@link CombatBotConfig#impsHuntingY()} + {@link CombatBotConfig#impsHuntingRadius()}.
     */
    private WorldPoint activeHuntCenter = null;
    private int activeHuntRadius = 0;
    /**
     * Laatste spell waarvoor autocast succesvol stond; opnieuw proberen als actieve spell wijzigt
     * of client autocast verliest.
     */
    private CombatBotConfig.ImpsMageSpell mageAutocastAppliedFor = null;

    // GE selling state
    private int bankTripCount = 0;
    /** Indicatieve lootwaarde (HA-gebaseerd) sinds vorige afgeronde banktrip. */
    private int impsTripLootValueAccumulator = 0;
    private boolean isSellingAtGe = false;
    /** Hard escape: geen boot-gp => home->GE, en desnoods goblins tot 30 gp. */
    private boolean coinRecoveryMode = false;
    /** Na home teleport direct GE-verkoop proberen voordat goblin-farm start. */
    private boolean coinRecoveryGeSellPending = false;
    /** Goblin fallback mag pas nadat de bank/gear-prep live is gecontroleerd. */
    private boolean coinRecoveryBankChecked = false;
    private int geSellStep = 0; // 0=walk to bank, 1=withdraw noted, 2=walk to GE, 3=sell, 4=done
    private long lastGeInteraction = 0;
    private static final long GE_INTERACTION_COOLDOWN = 1500;
    private int geSellItemIndex = 0; // welk item we nu verkopen
    private boolean geCollectedExisting = false;
    private boolean lawRunesBoughtThisGeTrip = false;
    /** Na GE-verkoop: bij volgende bank-open eerst alle coins naar inv (collect(true) zat op bank). */
    private boolean impsPostGeSellPullAllCoins = false;
    /** Minstens één keer loot uit bank gehad of verkoop/collect tijdens deze GE-trip. */
    private boolean geSellTripMadeProgress = false;
    /** Aantal afgeronde GE-verkooptrips zonder vooruitgang → melee fallback. */
    private int geSellNoProgressCycles = 0;

    // GE selling: lock de prijs per item zodat we niet eindeloos blijven "prijzen aanpassen"
    private String lockedSellItemName = null;
    private int lockedSellPrice = -1;
    private boolean lockedSellUsedBeadMarketFirst = false;
    /** Voorkomt oneindig hangen op 1 item als GE exchange() false blijft teruggeven. */
    private int geSellItemAttemptFails = 0;
    private static final int GE_SELL_MAX_FAILS_PER_ITEM = 5;

    // Alleen Law runes kopen bij GE (tijdens gear prep als bank te weinig heeft)
    private boolean isBuyingLawRunesAtGe = false;
    private int buyLawRunesStep = 0;
    private int teleportRuneWithdrawAttempts = 0; // voorkom eindloos withdraw van Law runes
    /** Melee opener Air/Mind: zonder limiet blijf je withdraw herhalen als inv niet stijgt (noted / volle inv). */
    private int meleeOpenerAirWithdrawAttempts = 0;
    private int meleeOpenerMindWithdrawAttempts = 0;
    private static final int MELEE_OPENER_RUNE_MAX_WITHDRAW_TRIES = 4;
    /** Law voor Varrock/Fally/Lummy teleport tijdens gear prep; zelfde anti-loop als melee opener. */
    private static final int TELEPORT_LAW_MAX_WITHDRAW_TRIES = 4;

    // GE locatie
    private static final WorldPoint GE_LOCATION = new WorldPoint(3164, 3487, 0);
    /** Typische landing na standaard Home teleport (Lumbridge). */
    private static final WorldPoint LUMBRIDGE_HOME_TELEPORT_ANCHOR = new WorldPoint(3222, 3218, 0);
    /** Eénmalige starter-GE-trip: doel Mind runes (melee opener / air strike). */
    private static final int STARTER_BRIDGE_MIND_RUNE_GE_TARGET = 1000;
    private static final int ITEM_ID_MIND_RUNE = 561;
    private static final int ITEM_ID_STAFF_OF_AIR = 1381;
    private static final int ITEM_ID_STAFF_OF_FIRE = 1387;
    private static final int ITEM_ID_BLACK_AXE = 1361;
    private static final int ITEM_ID_MITHRIL_AXE = 1355;
    private static final int ITEM_ID_ADAMANT_AXE = 1357;
    private static final int ITEM_ID_FLY_FISHING_ROD = 309;
    private static final int ITEM_ID_FEATHER = 314;
    /** OSRS item ID voor Law rune (voor GrandExchange.exchange). */
    private static final int LAW_RUNE_ITEM_ID = 563;

    /** Zelfde volgorde als {@link #bankHasRequiredItemsForStyle} MAGE (fallback-staven). */
    private static final String[] MAGE_BANK_STAFF_FALLBACKS = {
            "Staff of air", "Staff of fire", "Staff of water", "Staff of earth",
            "Mystic air staff", "Mystic fire staff", "Mystic water staff", "Mystic earth staff",
            "Air staff", "Fire staff", "Water staff", "Earth staff", "Staff"
    };

    // GE ammo restock state (Tier 1 failsafe)
    private boolean isBuyingAmmoAtGe = false;
    private int buyAmmoStep = 0;
    private String geAmmoItemName = null;
    private int geAmmoQuantity = 500;
    private int geAmmoPrice = 10;
    /** Meerdere aankopen in één GE-trip (staff + runes); voorkomt GE→bank→GE loops. */
    private ArrayList<GeBuySlot> geBuyQueue = null;
    private int geBuyQueueIndex = 0;

    private static final class GeBuySlot {
        final String itemName;
        final int quantity;
        final int startPricePerUnit;

        GeBuySlot(String itemName, int quantity, int startPricePerUnit) {
            this.itemName = itemName;
            this.quantity = quantity;
            this.startPricePerUnit = startPricePerUnit;
        }
    }

    /** Na {@link #beginStarterSkillBridge}: 30 gp boot, melee, GE-wealth stop bij 6k — los van normale imps-config. */
    private boolean starterSkillBridge;
    private boolean starterPostGeWealthCheck;
    private Runnable starterOnGeBankWealth6000Stop;
    private static final int STARTER_BRIDGE_MIN_COINS = 30;
    private static final int STARTER_POST_GE_WEALTH_STOP_GP = 6000;
    /**
     * Na starter-handoff: één keer volle of bijna volle imp-inventory → Home teleport → GE → 1000 Mind + Staff of air.
     * Daarna normale imps/wealth-check.
     */
    private boolean starterBridgeFirstGeCyclePending;
    private boolean starterBridgeHomeTeleportToGePending;
    private boolean starterBridgeGeBuyMindStaffActive;
    private int starterBridgeGeBuyExtrasStep;
    /**
     * Starter Home→GE-trip triggert bij volle of bijna volle inv: hoogstens dit aantal vrije slots
     * (incl. 0 = helemaal vol).
     */
    private static final int STARTER_BRIDGE_GE_TRIP_MAX_FREE_SLOTS = 4;
    /** Throttle voor debug: waarom Home→GE niet gekozen wordt tijdens banking. */
    private long lastStarterHomeGeSkipDiagnosticLogMs;
    /** Throttle: Home teleport niet castbaar / algemene teleport-cooldown. */
    private long lastStarterHomeTeleportDiagLogMs;

    // Combat style fallback state (Tier 2 failsafe)
    private CombatBotConfig.ImpsCombatStyle effectiveStyle = null;
    private boolean fallbackStyleActive = false;
    /** Detecteert account/config-wissel melee↔mage tijdens imps-run. */
    private CombatBotConfig.ImpsCombatStyle lastObservedImpsBaselineStyle = null;
    // Mage spell override voor runtime-fallback (bv. Fire Strike -> Wind Strike bij air-rune/coin problemen)
    private CombatBotConfig.ImpsMageSpell forcedMageSpell = null;
    // Als bank alleen noted/ongeldige withdraw geeft, forceer dit item direct naar GE-flow.
    private String forceGeMissingItem = null;

    /** Ashes in kleine random batches; bij volle inventory forceer ruimte maken. */
    private boolean scatteringBatchStarted = false;
    private int scatterAshesRemainingThisBatch = 0;
    private int nextAshScatterThreshold = 0;

    // Loot: na te veel mislukte pickups eerst imp killen, daarna opnieuw proberen (geen tijd-skip-loop)
    private int consecutiveLootAttempts = 0;
    private static final int MAX_CONSECUTIVE_LOOT_ATTEMPTS = 3;
    /** True = normale imp-loot even niet pakken tot er een kill geregistreerd is. */
    private boolean deferNormalLootUntilAfterKill = false;
    // Special loot tijdelijk forceren zodat die niet gemist wordt.
    private long prioritizeSpecialLootUntil = 0;

    // Walking re-click timing - laat nieuwe walkTo toe terwijl we nog bewegen
    private long lastWalkClickTime = 0;
    /** Klik opnieuw tijdens lopen zodat we niet pas na stilstand verder lopen. */
    private static final long WALK_RECLICK_INTERVAL_MS = 400;
    /** GE-lopen: tussen elke walk-klik (blijf doorlopen, niet te snel). */
    private static final long GE_WALK_RECLICK_MIN_MS = 550;
    private static final long GE_WALK_RECLICK_MAX_MS = 950;

    // Teleport / boot timing (voorkom dubbel klikken)
    private long lastTeleportTime = 0;
    private static final long TELEPORT_COOLDOWN_MS = 15_000;
    private long lastBoatClickTime = 0;
    private static final long BOAT_CLICK_COOLDOWN_MS = 6_000;
    /** Gear prep: alleen na mislukte boot-pogingen — fallback Home teleport (primair = zelfde boot als loot-dump). */
    private boolean gearPrepKaramjaUseHomeTele = false;
    /** Na Home tele cast: geen dock/officer-klik tot grace verloopt of mainland. */
    private long karamjaHomeTeleExitGraceUntilMs = 0L;
    private static final long KARAMJA_HOME_TELE_EXIT_GRACE_MS = 50_000L;
    /** Gear prep boot-fouten op Karamja vóór Home-tele-fallback. */
    private int gearPrepKaramjaBoatFailStreak = 0;
    private static final int GEAR_PREP_KARAMJA_BOAT_FAIL_FALLBACK = 3;
    /** Anti-spam voor informatieve boot-debugregels ("geen boot-NPC..."). */
    private long lastBoatMissingNpcDebugLogMs = 0L;
    /** Anti-spam voor movement-debug (rally/hunt-center looplogs). */
    private long lastRallyMovementDebugLogMs = 0L;
    /**
     * Port Sarim → Karamja: na Pay-fare is inv onder 30 gp tot de oversteek klaar is — activeert alleen na echte betaling
     * ({@link #awaitingPortSarimToKaramjaCrossing}), niet als je bij de NPC bent met te weinig gp (anders dock ↔ goblins-loop).
     */
    private long lastBoatTowardKaramjaInteractMs;
    private static final long BOAT_TOWARD_KARAMJA_GRACE_MS = 40_000L;
    /** Min. wacht na Pay-fare vóór opnieuw klikken (zelfde tile). */
    private static final long PORT_SARIM_CROSSING_MIN_WAIT_MS = 12_000L;
    /** Geen beweging op starttile na deze tijd → herpositioneren / retry. */
    private static final long PORT_SARIM_CROSSING_STUCK_MS = 20_000L;
    private static final int PORT_SARIM_BOAT_INTERACT_RANGE = 3;
    private static final int PORT_SARIM_MAX_PAY_RETRIES_SAME_SPOT = 3;
    /** {@link #payFareBack()} mag normale flow hervatten na {@link #handleAwaitingPortSarimCrossing}. */
    private static final int PAY_FARE_RESUME_NORMAL = Integer.MIN_VALUE;
    /** True vanaf Pay-fare met genoeg gp tot Musa Point of timeout — voorkomt grace op mislukte/teloze NPC-klik. */
    private boolean awaitingPortSarimToKaramjaCrossing = false;
    private WorldPoint portSarimCrossingStartTile;
    private int portSarimPayAttemptsSameTile;
    private int portSarimCoinsSnapshotBeforePay = -1;
    private boolean teleportUsedForDockTrip = false;
    private boolean varrockTeleportUsedThisGeTrip = false;
    /** Zelflerend route-voorkeur: bij herhaalde dock-stucks eerst via tussenpunt lopen. */
    private boolean preferAlternateKaramjaDockRoute = false;
    private static final WorldPoint KARAMJA_DOCK_ALT_WAYPOINT = new WorldPoint(2947, 3154, 0);
    /**
     * Bij Musa Point: pathfinder kan over de loopbrug/gangplank routeren waardoor Customs/Seaman niet
     * interacteerbaar is. Dan blijft {@link #findTravelNpc()} leeg terwijl we "op de dock" staan.
     */
    private static final long KARAMJA_BOAT_NPC_MISSING_FORCE_ALT_MS = 18_000L;
    private long karamjaPortBoatNpcMissingSinceMs;
    private long lastScorpionAttackRecordMs = 0L;
    private WorldPoint lastScorpionAttackRecordPoint = null;
    private static final long SCORPION_ATTACK_RECORD_COOLDOWN_MS = 8_000L;
    private static final int LEARNED_SCORPION_HOTSPOT_RADIUS = 4;

    /** Reset lichte runtime-state zodat de handler "schoon" opnieuw kan starten. */
    /**
     * LoopWatch / stuck-recovery: breek bank-gear-lus (armor withdraw, deposit, …).
     */
    public void recoverFromStuckLoop(String statusBucket) {
        gearPrepLastArmourWithdrawKey = "";
        gearPrepArmourWithdrawStreak = 0;
        gearPrepArmourBatchWithdrawDone = false;
        gearPrepInvEquipPhase = false;
        gearPrepLastArrowWithdrawName = "";
        gearPrepArrowWithdrawStreak = 0;
        preferredStaffWithdrawAttempts = MAX_PREFERRED_STAFF_WITHDRAW_ATTEMPTS;
        gearPrepKaramjaUseHomeTele = false;
        karamjaHomeTeleExitGraceUntilMs = 0L;
        gearPrepKaramjaBoatFailStreak = 0;
        resetPortSarimBoatCrossingState();
        lastBoatClickTime = 0L;
        boolean boatStuck = isBoatStuckStatusBucket(statusBucket);
        if (!boatStuck) {
            preparingGear = true;
            gearPrepComplete = false;
        } else {
            preparingGear = false;
            if (hasAdequateGearForStyle(getEffectiveStyle()) && getCoinCount() >= impsRuntimeMinCoins()) {
                gearPrepComplete = true;
            }
        }
        try {
            if (Bank.isOpen()) {
                Bank.close();
            }
        } catch (Exception ignored) {
        }
        chatLog("[LoopWatch] Imps herstel" + (statusBucket != null && !statusBucket.isEmpty()
                ? " (" + statusBucket + ")" : "")
                + (boatStuck ? " — boot-state reset (geen gear-prep)" : " — bank gesloten, prep streaks reset"));
    }

    private static boolean isBoatStuckStatusBucket(String statusBucket) {
        if (statusBucket == null || statusBucket.isEmpty()) {
            return false;
        }
        String b = statusBucket.toLowerCase(Locale.ROOT);
        return b.contains("boat") || b.contains("karamja_dock") || b.contains("port_sarim") || b.contains("naar_karamja");
    }

    private void resetPortSarimBoatCrossingState() {
        awaitingPortSarimToKaramjaCrossing = false;
        lastBoatTowardKaramjaInteractMs = 0L;
        portSarimCrossingStartTile = null;
        portSarimPayAttemptsSameTile = 0;
        portSarimCoinsSnapshotBeforePay = -1;
    }

    public void resetState() {
        clearPendingImpsWorldHop();
        preparingGear = false;
        gearPrepComplete = false;
        isBankingTrip = false;
        karamjaSharedBoatTrip = false;
        isRestocking = false;
        walkingToHuntArea = false;
        outsideTargetCooldownUntilMs = 0L;
        fleeingFromScorpion = false;
        isSellingAtGe = false;
        coinRecoveryMode = false;
        coinRecoveryGeSellPending = false;
        coinRecoveryBankChecked = false;
        geSellStep = 0;
        geSellItemIndex = 0;
        geCollectedExisting = false;
        lawRunesBoughtThisGeTrip = false;
        impsPostGeSellPullAllCoins = false;
        geSellTripMadeProgress = false;
        geSellNoProgressCycles = 0;
        geSellItemAttemptFails = 0;
        isBuyingLawRunesAtGe = false;
        buyLawRunesStep = 0;
        teleportRuneWithdrawAttempts = 0;
        meleeOpenerAirWithdrawAttempts = 0;
        meleeOpenerMindWithdrawAttempts = 0;
        teleportUsedForDockTrip = false;
        varrockTeleportUsedThisGeTrip = false;
        preferAlternateKaramjaDockRoute = false;
        karamjaPortBoatNpcMissingSinceMs = 0L;
        lastScorpionAttackRecordMs = 0L;
        lastScorpionAttackRecordPoint = null;
        isBuyingAmmoAtGe = false;
        buyAmmoStep = 0;
        geAmmoItemName = null;
        effectiveStyle = null;
        fallbackStyleActive = false;
        lastObservedImpsBaselineStyle = null;
        forcedMageSpell = null;
        mageAutocastAppliedFor = null;
        forceGeMissingItem = null;
        currentTarget = null;
        lastTargetPosition = null;
        targetWasAlive = false;
        consecutiveLootAttempts = 0;
        deferNormalLootUntilAfterKill = false;
        isRoaming = false;
        pickingUpLoot = false;
        pendingWizardHatEquip = false;
        pendingWizardHatEquipSinceMs = 0;
        lastWizardHatEquipAttemptMs = 0;
        prioritizeSpecialLootUntil = 0;
        lastMageStaffEquipTimeMs = 0;
        lastImpsRangedEmptyQuiverGameMessageMs = 0L;
        preferredStaffWithdrawAttempts = 0;
        lastPreferredStaffWithdrawMs = 0;
        gearPrepLastArrowWithdrawName = "";
        gearPrepArrowWithdrawStreak = 0;
        gearPrepLastArmourWithdrawKey = "";
        gearPrepArmourWithdrawStreak = 0;
        gearPrepArmourBatchWithdrawDone = false;
        gearPrepInvEquipPhase = false;
        scatteringBatchStarted = false;
        scatterAshesRemainingThisBatch = 0;
        nextAshScatterThreshold = 0;
        lastAirStrikeOpenDebugMs = 0;
        starterSkillBridge = false;
        starterPostGeWealthCheck = false;
        starterOnGeBankWealth6000Stop = null;
        starterBridgeFirstGeCyclePending = false;
        starterBridgeHomeTeleportToGePending = false;
        starterBridgeGeBuyMindStaffActive = false;
        impsTripLootValueAccumulator = 0;
        starterBridgeGeBuyExtrasStep = 0;
        loggedMageSpellDowngrade = false;
        geBuyQueue = null;
        geBuyQueueIndex = 0;
        lastStarterHomeGeSkipDiagnosticLogMs = 0;
        lastStarterHomeTeleportDiagLogMs = 0;
        lastBoatTowardKaramjaInteractMs = 0L;
        gearPrepKaramjaUseHomeTele = false;
        karamjaHomeTeleExitGraceUntilMs = 0L;
        gearPrepKaramjaBoatFailStreak = 0;
        resetPortSarimBoatCrossingState();
    }

    public void setPreferAlternateKaramjaDockRoute(boolean preferAlternate) {
        this.preferAlternateKaramjaDockRoute = preferAlternate;
    }

    /**
     * Eénmalige brug na StarterSkillHandler: min. coins 30, altijd melee, geen Fire-Strike air-gate,
     * geen dure gear-prep teleport, na GE (optioneel) bank+inv wealth ≥ 6k → callback (bot uit + logout).
     */
    public void beginStarterSkillBridge(Runnable onGeBankWealth6000Stop) {
        this.starterOnGeBankWealth6000Stop = onGeBankWealth6000Stop;
        this.starterSkillBridge = true;
        this.starterPostGeWealthCheck = false;
        this.shouldSwitchToNormal = false;
        String rsn = tryLocalRsnForQuest();
        boolean firstGeAlreadyDone = rsn != null && AccountStateJsonStore.isImpsStarterFirstGeCycleDone(rsn);
        this.starterBridgeFirstGeCyclePending = !firstGeAlreadyDone;
        if (firstGeAlreadyDone) {
            chatLog("[Starter] Eénmalige GE-trip (Mind+Staff) stond al in account-JSON — normale imps-bankloop");
        }
        this.starterBridgeHomeTeleportToGePending = false;
        this.starterBridgeGeBuyMindStaffActive = false;
        this.starterBridgeGeBuyExtrasStep = 0;
    }

    private int impsRuntimeMinCoins() {
        return starterSkillBridge ? STARTER_BRIDGE_MIN_COINS : config.impsMinCoins();
    }

    private int minFreeSlotsForGearPrepComplete() {
        return starterSkillBridge ? 5 : 15;
    }

    /**
     * Volledige combat-kit al aan: geen 15 vrije slots eisen (alleen ruimte voor coins/law/deposit).
     */
    private int minFreeSlotsRequiredForGearPrep(CombatBotConfig.ImpsCombatStyle style) {
        int equipSwap = InventoryEquipHelper.minFreeSlotsForStyleEquip(style);
        if (hasAdequateGearForStyle(style)) {
            if (impsMeleeSkipsArmour(style)) {
                return Math.max(starterSkillBridge ? 3 : 5, equipSwap);
            }
            if (StyleArmourBankHelper.hasCoreArmourEquipped(style)) {
                return Math.max(starterSkillBridge ? 3 : 5, equipSwap);
            }
        }
        return Math.max(minFreeSlotsForGearPrepComplete(), equipSwap);
    }

    private String impsLocalRsnOrNull() {
        IPlayer local = Players.getLocal();
        if (local == null || local.getName() == null || local.getName().trim().isEmpty()) {
            return null;
        }
        return Text.removeTags(local.getName()).trim();
    }

    /** Globale Imps-stijl + optionele per-account override (Accounts-tab → Bewerken). */
    private CombatBotConfig.ImpsCombatStyle impsCombatStyleBaselineForAccount() {
        String rsn = impsLocalRsnOrNull();
        if (rsn == null || rsn.isEmpty()) {
            return config.impsCombatStyle();
        }
        return ManagedJagexAccountsStore.resolveImpsCombatStyleForDisplayName(config, rsn);
    }

    /** Ammo-telling voor Imps ranged: inv + quiver; bij vast type ook andere bruikbare ammo als fallback. */
    private int getImpsEffectiveArrowCount() {
        if (RangedAmmoPreference.useBestInBank(config)) {
            return getTotalArrows();
        }
        String pref = RangedAmmoPreference.preferredItemName(config);
        int prefTotal = getItemQuantity(pref);
        if (prefTotal >= GEAR_PREP_MIN_ARROWS) {
            return prefTotal;
        }
        int rngLvl;
        try {
            rngLvl = Skills.getLevel(Skill.RANGED);
        } catch (Exception e) {
            rngLvl = 1;
        }
        return Math.max(prefTotal, RangedAmmoKit.getTotalUsableRangedAmmoCount(rngLvl));
    }

    /** Haal de effectieve combat style op (kan afwijken van config na fallback). */
    private CombatBotConfig.ImpsCombatStyle getEffectiveStyle() {
        if (starterSkillBridge) {
            return CombatBotConfig.ImpsCombatStyle.MELEE;
        }
        return effectiveStyle != null ? effectiveStyle : impsCombatStyleBaselineForAccount();
    }

    /**
     * Config staat op MAGE maar we zijn in Tier-2 melee-fallback: geen Mind/elemental-restock meer forceren
     * (anders eindeloze bank/GE-loop terwijl we melee spelen).
     */
    private boolean impsConfigMageSuppliesRestockActive() {
        if (impsCombatStyleBaselineForAccount() != CombatBotConfig.ImpsCombatStyle.MAGE) {
            return false;
        }
        return !(fallbackStyleActive && getEffectiveStyle() == CombatBotConfig.ImpsCombatStyle.MELEE);
    }

    /** Eénmalig loggen als config-spell niet castbaar is i.v.m. Magic level. */
    private boolean loggedMageSpellDowngrade = false;

    private int getMagicLevelSafe() {
        try {
            return Skills.getLevel(Skill.MAGIC);
        } catch (Exception e) {
            return 99;
        }
    }

    /**
     * Hoogste spell uit config-enum die bij huidig Magic level nog castbaar is
     * (Wind Strike = level 1; Fire Strike = 13; …).
     */
    private CombatBotConfig.ImpsMageSpell getHighestCastableConfiguredSpell() {
        int mag = getMagicLevelSafe();
        CombatBotConfig.ImpsMageSpell best = CombatBotConfig.ImpsMageSpell.WIND_STRIKE;
        for (CombatBotConfig.ImpsMageSpell s : CombatBotConfig.ImpsMageSpell.values()) {
            if (s.getLevelReq() <= mag && s.getLevelReq() >= best.getLevelReq()) {
                best = s;
            }
        }
        return best;
    }

    private CombatBotConfig.ImpsMageSpell getActiveMageSpell() {
        if (forcedMageSpell != null) {
            return forcedMageSpell;
        }
        CombatBotConfig.ImpsMageSpell cfg = config.impsMageSpell();
        int mag = getMagicLevelSafe();
        if (cfg.getLevelReq() > mag) {
            // Fire Strike vereist 13 Magic — expliciet Wind Strike (Staff of air), geen andere "hogere" strike.
            if (cfg == CombatBotConfig.ImpsMageSpell.FIRE_STRIKE) {
                if (!loggedMageSpellDowngrade) {
                    chatLog("[~] Magic " + mag + " < 13 (Fire Strike) — gebruik Wind Strike + Staff of air");
                    loggedMageSpellDowngrade = true;
                }
                return CombatBotConfig.ImpsMageSpell.WIND_STRIKE;
            }
            CombatBotConfig.ImpsMageSpell fallback = getHighestCastableConfiguredSpell();
            if (!loggedMageSpellDowngrade && fallback != cfg) {
                chatLog("[~] Magic " + mag + " < " + cfg.getSpellName() + " (lvl " + cfg.getLevelReq()
                        + ") — gebruik " + fallback.getSpellName());
                loggedMageSpellDowngrade = true;
            }
            return fallback;
        }
        return cfg;
    }

    /** Fire Strike: 2 Air per cast (staff dekt Fire runes). Wind Strike: 1 Air per cast zonder Air staff. */
    private static int airRunesPerCastForSpell(CombatBotConfig.ImpsMageSpell s) {
        if (!s.needsAirRune()) {
            return s == CombatBotConfig.ImpsMageSpell.WIND_STRIKE ? 1 : 0;
        }
        switch (s) {
            case FIRE_STRIKE:
            case WATER_STRIKE:
            case EARTH_STRIKE:
            case WATER_BOLT:
            case EARTH_BOLT:
            case FIRE_BOLT:
                return 2;
            case WIND_BOLT:
                return 2;
            default:
                return 2;
        }
    }

    /** Elemental runes per cast als geen passende staff (OSRS standaard spellbook). */
    private static int elementalRunesPerCast(CombatBotConfig.ImpsMageSpell s) {
        switch (s) {
            case WIND_STRIKE:
                return 1;
            case WATER_STRIKE:
            case EARTH_STRIKE:
                return 1;
            case FIRE_STRIKE:
                return 3;
            case WIND_BOLT:
                return 2;
            case WATER_BOLT:
            case EARTH_BOLT:
                return 2;
            case FIRE_BOLT:
                return 3;
            default:
                return 1;
        }
    }

    private static int catalystRunesPerCast(CombatBotConfig.ImpsMageSpell s) {
        return s.getCatalystRune().toLowerCase(Locale.ROOT).contains("chaos") ? 2 : 1;
    }

    private int minCatalystRunesForTrip(CombatBotConfig.ImpsMageSpell spell) {
        int budget = Math.max(1, config.impsMageTripCastBudget());
        return Math.max(50, budget * catalystRunesPerCast(spell));
    }

    /** Voor Mind rune wil je pas restocken onder 100 (user requirement). */
    private int effectiveCatalystTripMinimum(CombatBotConfig.ImpsMageSpell spell) {
        String catalyst = spell != null ? spell.getCatalystRune() : null;
        if (catalyst != null && catalyst.equalsIgnoreCase("Mind rune")) {
            return 100;
        }
        return minCatalystRunesForTrip(spell);
    }

    /**
     * Kleine hysteresis voor trip-minimum:
     * na 1 directe cast (bij vertrek/target-open) willen we niet meteen terug naar restock.
     */
    private boolean isBelowCatalystTripMinimum(int currentCount, CombatBotConfig.ImpsMageSpell spell) {
        int minCat = effectiveCatalystTripMinimum(spell);
        int oneCastBuffer = Math.max(1, catalystRunesPerCast(spell));
        return currentCount + oneCastBuffer < minCat;
    }

    private int minElementalRunesForTrip(CombatBotConfig.ImpsMageSpell spell) {
        String element = spell.getElementalRune().toLowerCase(Locale.ROOT).replace(" rune", "");
        if (hasStaffWithElement(element)) {
            return 0;
        }
        int budget = Math.max(1, config.impsMageTripCastBudget());
        return Math.max(50, budget * elementalRunesPerCast(spell));
    }

    private int requiredAirRunesForCurrentMagePlan() {
        CombatBotConfig.ImpsMageSpell spell = getActiveMageSpell();
        // Wind Strike: Air is de elemental rune; trip-hoeveelheid zit in minElementalRunesForTrip (geen dubbele telling).
        if (spell == CombatBotConfig.ImpsMageSpell.WIND_STRIKE) {
            return 0;
        }
        if (!spell.needsAirRune()) {
            return 0;
        }
        if (hasStaffWithElement("air")) {
            return 0;
        }
        int budget = Math.max(1, config.impsMageTripCastBudget());
        return budget * airRunesPerCastForSpell(spell);
    }

    /**
     * Minimaal Air vóór vertrek / combat-ready: trip-budget uit config, maar niet meer dan je met de
     * huidige catalyst-stack (meestal Mind) daadwerkelijk kunt casten — Fire Strike = 2 Air per Mind per cast.
     */
    private int requiredAirRunesForMageDeparture() {
        CombatBotConfig.ImpsMageSpell spell = getActiveMageSpell();
        if (spell == null || spell == CombatBotConfig.ImpsMageSpell.WIND_STRIKE || !spell.needsAirRune()) {
            return 0;
        }
        if (hasStaffWithElement("air")) {
            return 0;
        }
        int trip = requiredAirRunesForCurrentMagePlan();
        if (trip <= 0) {
            return 0;
        }
        int cat = getItemQuantity(spell.getCatalystRune());
        int perCast = Math.max(1, catalystRunesPerCast(spell));
        int casts = cat / perCast;
        int airPer = airRunesPerCastForSpell(spell);
        int paired = casts * airPer;
        return Math.min(trip, Math.max(airPer, paired));
    }

    /**
     * Accounts met "Magic auto update (Imps)": Fire Strike-setup is {@code Staff of fire} + {@code Air rune} in inventory.
     * Geen blijvende {@code Staff of air} + {@code Fire rune} (dat cast wel, maar bot moet upgraden naar fire staff + air).
     *
     * @return benodigde Air in inventory (gepaard met Mind), of {@code -1} als deze regel niet geldt.
     */
    private int requiredInventoryAirForAccountAutoFireSetup() {
        if (starterSkillBridge || !isMageImpsRouteActive()) {
            return -1;
        }
        if (!isCurrentAccountMagicAutoUpdateEnabled()
                || getMagicLevelSafe() < CombatBotConfig.ImpsMageSpell.FIRE_STRIKE.getLevelReq()) {
            return -1;
        }
        if (!shouldUseAutoFireStrikeUpgrade() && getActiveMageSpell() != CombatBotConfig.ImpsMageSpell.FIRE_STRIKE) {
            return -1;
        }
        int budget = Math.max(1, config.impsMageTripCastBudget());
        int trip = budget * airRunesPerCastForSpell(CombatBotConfig.ImpsMageSpell.FIRE_STRIKE);
        String catName = CombatBotConfig.ImpsMageSpell.FIRE_STRIKE.getCatalystRune();
        int cat = getItemQuantity(catName);
        int perCast = Math.max(1, catalystRunesPerCast(CombatBotConfig.ImpsMageSpell.FIRE_STRIKE));
        int casts = cat / perCast;
        int airPer = airRunesPerCastForSpell(CombatBotConfig.ImpsMageSpell.FIRE_STRIKE);
        int paired = casts * airPer;
        return Math.min(trip, Math.max(airPer, paired));
    }

    /** True = nog niet klaar voor Fire Strike-vertrek qua fire staff + Air inventory (Magic auto-accounts). */
    private boolean fireStrikeAirDepartureGateFails() {
        if (!isFireStrikeAirGateActive()) {
            return false;
        }
        int airCount = getItemQuantity("Air rune");
        int strict = requiredInventoryAirForAccountAutoFireSetup();
        if (strict >= 0) {
            return !hasStaffWithElement("fire") || airCount < strict;
        }
        boolean airCovered = hasStaffWithElement("air");
        int airTripNeeded = requiredAirRunesForMageDeparture();
        return !airCovered && airCount < airTripNeeded;
    }

    private boolean isCurrentAccountMagicAutoUpdateEnabled() {
        IPlayer local = Players.getLocal();
        if (local == null || local.getName() == null || local.getName().trim().isEmpty()) {
            return false;
        }
        String rsn = local.getName().trim();
        List<ManagedJagexAccountsStore.ManagedJagexAccountRow> rows =
                ManagedJagexAccountsStore.parseRows(config.managedJagexAccountsBlob());
        for (ManagedJagexAccountsStore.ManagedJagexAccountRow r : rows) {
            if (r == null || r.displayName == null) {
                continue;
            }
            if (r.displayName.trim().equalsIgnoreCase(rsn)) {
                return r.magicAutoUpdate;
            }
        }
        return false;
    }

    /**
     * MAGE-route: óf Imps-combat op MAGE in config, óf effectief MAGE (na reset/fallback moet dit synchroon blijven).
     */
    private boolean isMageImpsRouteActive() {
        if (starterSkillBridge) {
            return false;
        }
        if (fallbackStyleActive && getEffectiveStyle() == CombatBotConfig.ImpsCombatStyle.MELEE) {
            return false;
        }
        return impsCombatStyleBaselineForAccount() == CombatBotConfig.ImpsCombatStyle.MAGE
                || getEffectiveStyle() == CombatBotConfig.ImpsCombatStyle.MAGE;
    }

    private boolean shouldUseAutoFireStrikeUpgrade() {
        if (starterSkillBridge) {
            return false;
        }
        if (!isMageImpsRouteActive()) {
            return false;
        }
        if (!isCurrentAccountMagicAutoUpdateEnabled()) {
            return false;
        }
        return getMagicLevelSafe() >= CombatBotConfig.ImpsMageSpell.FIRE_STRIKE.getLevelReq();
    }

    private int maybeAutoUpgradeToFireStrike(IPlayer local) {
        if (!shouldUseAutoFireStrikeUpgrade()) {
            return 0;
        }
        if (forcedMageSpell != CombatBotConfig.ImpsMageSpell.FIRE_STRIKE) {
            forcedMageSpell = CombatBotConfig.ImpsMageSpell.FIRE_STRIKE;
            long now = System.currentTimeMillis();
            if (now - lastAutoFireUpgradeLogMs > 12_000L) {
                lastAutoFireUpgradeLogMs = now;
                chatLog("[AutoMage] Fire Strike auto-update actief voor account.");
            }
        }
        boolean hasFireStaff = hasStaffWithElement("fire");
        int airHave = getItemQuantity("Air rune");
        int invAirStrict = requiredInventoryAirForAccountAutoFireSetup();
        if (invAirStrict >= 0) {
            if (!hasFireStaff || airHave < invAirStrict) {
                preparingGear = true;
                gearPrepComplete = false;
                effectiveStyle = CombatBotConfig.ImpsCombatStyle.MAGE;
                fallbackStyleActive = false;
                paint.setCurrentStatus("Imps: Auto Fire Strike — fire staff + Air inv nodig…");
                return handleGearPreparation(local);
            }
        } else {
            boolean hasAirStaff = hasStaffWithElement("air");
            int airNeed = requiredAirRunesForMageDeparture();
            if (!hasFireStaff || (!hasAirStaff && airHave < airNeed)) {
                preparingGear = true;
                gearPrepComplete = false;
                effectiveStyle = CombatBotConfig.ImpsCombatStyle.MAGE;
                fallbackStyleActive = false;
                paint.setCurrentStatus("Imps: Auto Fire Strike upgrade...");
                return handleGearPreparation(local);
            }
        }
        return 0;
    }

    /** Minstens 1 cast mogelijk met huidige runes + staff-dekking (geen attack zonder voldoende runes). */
    private boolean hasRunesForAtLeastOneCast() {
        CombatBotConfig.ImpsMageSpell spell = getActiveMageSpell();
        String elementalRune = spell.getElementalRune();
        String element = elementalRune.toLowerCase(Locale.ROOT).replace(" rune", "");
        if (getItemQuantity(spell.getCatalystRune()) < catalystRunesPerCast(spell)) {
            return false;
        }
        if (!hasStaffWithElement(element) && getItemQuantity(elementalRune) < elementalRunesPerCast(spell)) {
            return false;
        }
        if (spell.needsAirRune()) {
            int strictAir = requiredInventoryAirForAccountAutoFireSetup();
            if (strictAir >= 0) {
                if (getItemQuantity("Air rune") < airRunesPerCastForSpell(spell)) {
                    return false;
                }
            } else if (!hasStaffWithElement("air")
                    && getItemQuantity("Air rune") < airRunesPerCastForSpell(spell)) {
                return false;
            }
        }
        return true;
    }

    private boolean isFireStrikeAirGateActive() {
        if (starterSkillBridge) {
            return false;
        }
        return isMageImpsRouteActive()
                && getActiveMageSpell() == CombatBotConfig.ImpsMageSpell.FIRE_STRIKE;
    }

    public ImpsHandler(CombatBotConfig config, AntiBan antiBan, CombatBotPaint paint) {
        this.config = config;
        this.antiBan = antiBan;
        this.paint = paint;
    }

    public void setTileMarkerManager(TileMarkerManager tileMarkerManager) {
        this.tileMarkerManager = tileMarkerManager;
    }

    public void setQuestProgressConfigManager(ConfigManager questProgressConfigManager) {
        this.questProgressConfigManager = questProgressConfigManager;
    }

    public void setWorldHopClient(Client client, ClientThread clientThread) {
        this.rlClient = client;
        this.rlClientThread = clientThread;
    }

    public void setCompetitorWorldHopEnabled(BooleanSupplier enabled) {
        if (enabled != null) {
            this.competitorWorldHopEnabled = enabled;
        }
    }

    private String tryLocalRsnForQuest() {
        try {
            IPlayer lp = Players.getLocal();
            if (lp == null || lp.getName() == null) {
                return null;
            }
            return Text.removeTags(lp.getName());
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Zet het jachtgebied vanuit imp-centers (skill-rotatie / menu). Null of radius ≤ 0 = gebruik config Hunting X/Y + radius.
     */
    public void setActiveCenter(WorldPoint center, int radius) {
        WorldPoint prevCenter = activeHuntCenter;
        int prevRadius = activeHuntRadius;
        boolean sameCenter = (prevCenter == null && center == null)
                || (prevCenter != null && center != null
                && prevCenter.getX() == center.getX()
                && prevCenter.getY() == center.getY()
                && prevCenter.getPlane() == center.getPlane());
        boolean sameRadius = prevRadius == radius;

        if (center == null || radius <= 0) {
            activeHuntCenter = null;
            activeHuntRadius = 0;
        } else {
            activeHuntCenter = center;
            activeHuntRadius = radius;
        }

        if (sameCenter && sameRadius) {
            return;
        }
        if (activeHuntCenter == null || activeHuntRadius <= 0) {
            debugLog("[AREA] active center=CONFIG fallback "
                    + config.impsHuntingX() + "," + config.impsHuntingY() + ",0"
                    + " radius=" + config.impsHuntingRadius());
        } else {
            debugLog("[AREA] active center="
                    + activeHuntCenter.getX() + "," + activeHuntCenter.getY() + "," + activeHuntCenter.getPlane()
                    + " radius=" + activeHuntRadius);
        }
    }

    /** True als dit NPC het geconfigureerde imp-doelwit is (ID of naam \"Imp\"). */
    private boolean impsCompetitorWorldHopEnabled() {
        if (competitorWorldHopEnabled != null) {
            try {
                return competitorWorldHopEnabled.getAsBoolean();
            } catch (Exception ignored) {
            }
        }
        return config.impsCompetitorWorldHop();
    }

    private boolean hasOtherPlayerOnImpInHuntArea(IPlayer local) {
        if (local == null) {
            return false;
        }
        try {
            List<INPC> contested = NPCs.getAll(npc ->
                    isImpNpc(npc)
                            && !npc.isDead()
                            && isInHuntingArea(npc.getWorldLocation())
                            && NpcCombatTargetHelper.isNpcInCombatWithOther(npc, local));
            return contested != null && !contested.isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    private Client resolveRlClient() {
        if (rlClient != null) {
            return rlClient;
        }
        try {
            Object wrapped = net.storm.sdk.game.Client.getClient().getWrapped();
            if (wrapped instanceof Client) {
                rlClient = (Client) wrapped;
                return rlClient;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void clearPendingImpsWorldHop() {
        pendingImpsHopTargetWorld = -1;
        pendingImpsHopStartWorld = -1;
        pendingImpsHopRetryCount = 0;
        pendingImpsHopRequestedMs = 0L;
    }

    private int processPendingImpsWorldHop() {
        if (pendingImpsHopTargetWorld <= 0 || pendingImpsHopRequestedMs <= 0L) {
            return 0;
        }
        if (AccountSwitchWorldHop.tryAcceptWorldHopConfirmation()) {
            return antiBan.varyDelay(randomDelay(400, 800));
        }
        Client rl = resolveRlClient();
        if (rl == null) {
            return antiBan.varyDelay(randomDelay(1200, 2000));
        }
        if (AccountSwitchWorldHop.isWorldRecentlyRejected(pendingImpsHopTargetWorld)) {
            int retry = AccountSwitchWorldHop.pickRandomF2pWorldId(rl);
            if (retry > 0 && retry != pendingImpsHopTargetWorld
                    && AccountSwitchWorldHop.scheduleHopToWorld(rl, rlClientThread, retry)) {
                pendingImpsHopStartWorld = rl.getWorld();
                pendingImpsHopTargetWorld = retry;
                pendingImpsHopRetryCount = 0;
                pendingImpsHopRequestedMs = System.currentTimeMillis();
                paint.setCurrentStatus("Imps: 🌍 Andere wereld (w" + retry + ")…");
                return antiBan.varyDelay(randomDelay(2000, 3500));
            }
        }
        GameState gs = rl.getGameState();
        if (gs == GameState.HOPPING) {
            paint.setCurrentStatus("Imps: 🌍 Wereld wisselen (laden)…");
            return antiBan.varyDelay(randomDelay(1100, 2000));
        }
        int cur = rl.getWorld();
        if (cur == pendingImpsHopTargetWorld
                || (pendingImpsHopStartWorld > 0 && cur != pendingImpsHopStartWorld)) {
            clearPendingImpsWorldHop();
            return 0;
        }
        long since = System.currentTimeMillis() - pendingImpsHopRequestedMs;
        paint.setCurrentStatus("Imps: 🌍 Wereld wisselen → w" + pendingImpsHopTargetWorld + "…");
        if (since < IMPS_HOP_VERIFY_TIMEOUT_MS) {
            return antiBan.varyDelay(randomDelay(900, 1600));
        }
        if (pendingImpsHopRetryCount >= 2) {
            paint.setLastAntiBanAction("Imps: 🌍 hop timeout — later opnieuw");
            clearPendingImpsWorldHop();
            return antiBan.varyDelay(randomDelay(1500, 2600));
        }
        pendingImpsHopRetryCount++;
        pendingImpsHopRequestedMs = System.currentTimeMillis();
        if (AccountSwitchWorldHop.scheduleHopToWorld(rl, rlClientThread, pendingImpsHopTargetWorld)) {
            paint.setLastAntiBanAction("Imps: 🌍 hop retry " + pendingImpsHopRetryCount
                    + " → w" + pendingImpsHopTargetWorld);
        }
        return antiBan.varyDelay(randomDelay(2000, 3500));
    }

    private int tryImpsCompetitorWorldHop(IPlayer local) {
        if (!impsCompetitorWorldHopEnabled() || !hasOtherPlayerOnImpInHuntArea(local)) {
            return 0;
        }
        return tryScheduleImpsWorldHop("andere speler op imp");
    }

    private int tryScheduleImpsWorldHop(String reason) {
        Client rl = resolveRlClient();
        if (rl == null) {
            return 0;
        }
        long now = System.currentTimeMillis();
        if (now - lastImpsWorldHopMs < IMPS_WORLD_HOP_COOLDOWN_MS && pendingImpsHopTargetWorld <= 0) {
            return 0;
        }
        try {
            int target = AccountSwitchWorldHop.pickRandomF2pWorldId(rl);
            if (!AccountSwitchWorldHop.scheduleHopToWorld(rl, rlClientThread, target)) {
                return 0;
            }
            pendingImpsHopStartWorld = rl.getWorld();
            pendingImpsHopTargetWorld = target;
            pendingImpsHopRetryCount = 0;
            pendingImpsHopRequestedMs = now;
            lastImpsWorldHopMs = now;
            walkingToHuntArea = false;
            pickingUpLoot = false;
            currentTarget = null;
            isRoaming = false;
            paint.setLastAntiBanAction("Imps: 🌍 " + reason + " → w" + target);
            paint.setCurrentStatus("Imps: 🌍 Wereld wisselen (concurrentie)");
            chatLog("🌍 Wereld-hop: " + reason + " (w" + pendingImpsHopStartWorld + " → w" + target + ")");
            return antiBan.varyDelay(randomDelay(2500, 4000));
        } catch (Exception e) {
            return 0;
        }
    }

    private boolean isImpNpc(INPC npc) {
        if (npc == null) {
            return false;
        }
        int idCfg = config.impsNpcId();
        if (idCfg > 0) {
            return npc.getId() == idCfg;
        }
        return npc.getName() != null && npc.getName().equalsIgnoreCase("Imp");
    }

    /** Haal de actuele loot items op uit config (dynamisch, niet gecached). */
    private String[] getLootItems() {
        String configLoot = config.impsLootItems();
        if (configLoot != null && !configLoot.trim().isEmpty()) {
            return Arrays.stream(configLoot.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toArray(String[]::new);
        }
        return DEFAULT_LOOT_ITEMS;
    }

    /** Haal speciale loot items op uit config (altijd direct oppakken). */
    private String[] getSpecialLootItems() {
        String raw = config.specialLootItems();
        if (raw != null && !raw.trim().isEmpty()) {
            return Arrays.stream(raw.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toArray(String[]::new);
        }
        return new String[0];
    }

    public boolean shouldSwitchToNormalCombat() {
        return shouldSwitchToNormal;
    }

    /**
     * Start gear preparation: bank trip om coins, wapen en ammo/runes op te halen.
     * Wordt EENMALIG aangeroepen bij switch naar imps mode.
     * Checkt EERST of we al gear hebben voor de gekozen combat style.
     */
    /** Start direct een GE-verkoopronde (bv. via "Sell now" knop). */
    public void startGeSellNow() {
        isSellingAtGe = true;
        geSellStep = 0;
    }

    /** Gebruikt door {@link CombatBotPlugin}: tijdens "Sell now" mag Starter niet over Imps heen geforceerd worden. */
    public boolean isGeSellNowFlowActive() {
        return isSellingAtGe;
    }

    public void startGearPreparation() {
        effectiveStyle = null; // reset fallback bij nieuwe gear prep
        fallbackStyleActive = false;
        CombatBotConfig.ImpsCombatStyle style = getEffectiveStyle();
        mageAutocastAppliedFor = null; // opnieuw autocast na gear prep / spell-wissel
        preferredStaffWithdrawAttempts = 0;
        lastPreferredStaffWithdrawMs = 0;
        walkingToHuntArea = false;
        teleportRuneWithdrawAttempts = 0;
        meleeOpenerAirWithdrawAttempts = 0;
        meleeOpenerMindWithdrawAttempts = 0;
        gearPrepLastArrowWithdrawName = "";
        gearPrepArrowWithdrawStreak = 0;
        gearPrepLastArmourWithdrawKey = "";
        gearPrepArmourWithdrawStreak = 0;
        gearPrepArmourBatchWithdrawDone = false;
        gearPrepInvEquipPhase = false;
        // Alleen overslaan als ook genoeg coins op zak (boot Musa Point = 30 gp; starter-bridge gebruikt zelfde minimum).
        // Anders markeerde we gear als "klaar" zonder gp → handleInsufficientCoinsForBoatFare sloeg bank-trek over.
        if (style != CombatBotConfig.ImpsCombatStyle.MELEE
                && hasCombatReadyGearForStyle(style)
                && getCoinCount() >= impsRuntimeMinCoins()
                && Inventory.getFreeSlots() >= minFreeSlotsRequiredForGearPrep(style)) {
            if (style == CombatBotConfig.ImpsCombatStyle.MAGE) {
                equipGear(CombatBotConfig.ImpsCombatStyle.MAGE);
            }
            gearPrepComplete = true;
            preparingGear = false;
            chatLog("[OK] Gear voor " + style + " al aanwezig en genoeg gp (" + getCoinCount() + "), geen preparation nodig.");
            return;
        }
        preparingGear = true;
        gearPrepComplete = false;
        chatLog("Gear preparation gestart voor style: " + style);
    }

    /**
     * Check of we adequate gear hebben voor de GEKOZEN combat style.
     * Checkt zowel equipment als inventory.
     */
    private boolean hasAdequateGearForStyle(CombatBotConfig.ImpsCombatStyle style) {
        switch (style) {
            case MELEE:
                return hasMeleeWeapon();
            case RANGED:
                return hasRangedSetup();
            case MAGE:
                return hasMageSetup();
            default:
                return false;
        }
    }

    /**
     * Gear-check tijdens imps-combat: staff + runes voor minstens één cast.
     * Trip-budget ({@link #minCatalystRunesForTrip}) geldt alleen voor bank/withdraw — niet voor "mag ik verder jagen?".
     */
    private boolean hasCombatReadyGearForStyle(CombatBotConfig.ImpsCombatStyle style) {
        if (style == CombatBotConfig.ImpsCombatStyle.MAGE) {
            return hasMageCombatReady();
        }
        return hasAdequateGearForStyle(style);
    }

    private boolean hasMageCombatReady() {
        if (!isStaffEquippedForMage()) {
            return false;
        }
        return hasRunesForAtLeastOneCast();
    }

    private boolean isStaffEquippedForMage() {
        return Equipment.contains(item -> item != null && item.getName() != null
                && item.getName().toLowerCase(Locale.ROOT).contains("staff"));
    }

    /** Mage gear prep: melee niet in keep-list (fallback MELEE in config telt dan niet). */
    private boolean isStrictMageGearPrep() {
        return preparingGear
                && getEffectiveStyle() == CombatBotConfig.ImpsCombatStyle.MAGE
                && !fallbackStyleActive;
    }

    private boolean shouldRetainMeleeWeaponsDuringGearPrep() {
        if (isStrictMageGearPrep()) {
            return false;
        }
        CombatBotConfig.ImpsCombatStyle style = getEffectiveStyle();
        CombatBotConfig.ImpsCombatStyle fallback = config.impsFallbackStyle();
        return style == CombatBotConfig.ImpsCombatStyle.MELEE
                || (fallbackStyleActive && fallback == CombatBotConfig.ImpsCombatStyle.MELEE);
    }

    /** Imps melee: geen armor (gewicht) — alleen wapen + coins/runes. */
    private boolean impsMeleeSkipsArmour() {
        return getEffectiveStyle() == CombatBotConfig.ImpsCombatStyle.MELEE;
    }

    private boolean impsMeleeSkipsArmour(CombatBotConfig.ImpsCombatStyle style) {
        return style == CombatBotConfig.ImpsCombatStyle.MELEE;
    }

    /**
     * Bank open: melee-armor uit inv/equipment wegleggen (imps rennen veel, imps zijn zwak).
     * @return delay &gt; 0 als actie gedaan
     */
    private int bankImpsMeleeArmourIfNeeded() {
        if (!impsMeleeSkipsArmour() || !Bank.isOpen()) {
            return 0;
        }
        for (net.storm.api.domain.items.IInventoryItem item : Inventory.getAll()) {
            if (item == null || item.getName() == null) {
                continue;
            }
            if (!StyleArmourBankHelper.isMeleeStyleArmourItemName(item.getName())) {
                continue;
            }
            Bank.depositAll(item.getName());
            chatLog("[Bank] Imps melee: armor naar bank (licht blijven): " + item.getName());
            sleep(300, 500);
            return antiBan.varyDelay(randomDelay(400, 700));
        }
        if (StyleArmourBankHelper.tryUnequipOneMeleeStyleArmourPiece()) {
            chatLog("[Bank] Imps melee: armor uit slot (licht blijven)");
            return antiBan.varyDelay(randomDelay(500, 900));
        }
        return 0;
    }

    /**
     * Melee in wapen-slot blokkeert staff wield. Haal melee uit equipment en bank inv-melee tijdens mage prep.
     */
    private int clearEquippedMeleeWeaponBlockingMage() {
        if (getEffectiveStyle() != CombatBotConfig.ImpsCombatStyle.MAGE
                && !isStrictMageGearPrep()) {
            return 0;
        }
        try {
            var equipped = Equipment.getAll(item -> item != null && item.getName() != null
                    && isCombatMeleeWeaponName(item.getName().toLowerCase(Locale.ROOT)));
            if (equipped != null) {
                for (var eq : equipped) {
                    if (eq == null) {
                        continue;
                    }
                    for (String action : new String[]{"Remove", "Unequip"}) {
                        if (eq.hasAction(action)) {
                            eq.interact(action);
                            chatLog("Mage switch: melee uit slot (" + eq.getName() + ", " + action + ")");
                            return antiBan.varyDelay(randomDelay(550, 950));
                        }
                    }
                }
            }
        } catch (Exception e) {
            debugLog("clearEquippedMeleeWeaponBlockingMage: " + e.getMessage());
        }
        if (Bank.isOpen() && isStrictMageGearPrep()) {
            for (String weapon : meleeWeaponUpgradeOrder()) {
                if (Inventory.contains(weapon)) {
                    Bank.deposit(weapon, Integer.MAX_VALUE);
                    chatLog("Mage prep: melee wapen naar bank: " + weapon);
                    return antiBan.varyDelay(randomDelay(500, 900));
                }
            }
        }
        return 0;
    }

    /**
     * Sluit gear prep af: equip + voor MAGE staff verplicht gedragen; voor MELEE wapen aan.
     * @return delay &gt; 0 als nog bezig, 0 als niet klaar, -1 als afgerond
     */
    private int tryCompleteGearPreparation(CombatBotConfig.ImpsCombatStyle style) {
        if (getCoinCount() < impsRuntimeMinCoins()) {
            return 0;
        }
        if (Inventory.getFreeSlots() < minFreeSlotsRequiredForGearPrep(style)) {
            return 0;
        }
        if (!hasAdequateGearForStyle(style)) {
            return 0;
        }
        if (style == CombatBotConfig.ImpsCombatStyle.MAGE) {
            int clearMelee = clearEquippedMeleeWeaponBlockingMage();
            if (clearMelee > 0) {
                return clearMelee;
            }
            if (!hasMageSetup()) {
                return 0;
            }
            equipGear(CombatBotConfig.ImpsCombatStyle.MAGE);
            if (!isStaffEquippedForMage()) {
                if (Inventory.contains(item -> item != null && item.getName() != null
                        && item.getName().toLowerCase(Locale.ROOT).contains("staff"))) {
                    return antiBan.varyDelay(randomDelay(700, 1100));
                }
                return 0;
            }
            selectAutocastSpell(getActiveMageSpell());
            if (tryEquipStyleArmour(CombatBotConfig.ImpsCombatStyle.MAGE)) {
                return antiBan.varyDelay(randomDelay(650, 1050));
            }
            if (isFireStrikeAirGateActive() && fireStrikeAirDepartureGateFails()) {
                return 0;
            }
        } else if (style == CombatBotConfig.ImpsCombatStyle.MELEE) {
            equipGear(CombatBotConfig.ImpsCombatStyle.MELEE);
            if (!impsMeleeSkipsArmour(style) && tryEquipStyleArmour(style)) {
                return antiBan.varyDelay(randomDelay(650, 1050));
            }
            if (!hasMeleeWeapon()) {
                return 0;
            }
        } else if (style == CombatBotConfig.ImpsCombatStyle.RANGED) {
            equipGear(CombatBotConfig.ImpsCombatStyle.RANGED);
            if (tryEquipStyleArmour(style)) {
                return antiBan.varyDelay(randomDelay(650, 1050));
            }
            if (!hasCombatReadyGearForStyle(style)) {
                return 0;
            }
        }
        if (Bank.isOpen()) {
            Bank.close();
            sleep(400, 700);
        }
        gearPrepComplete = true;
        preparingGear = false;
        EquipmentStylePlanner.ArmorStyle worn = EquipmentStylePlanner.inferStyleFromEquipped(
                BankSnapshotPlanner.currentDisplayName());
        chatLog("[OK] Gear preparation compleet voor " + style + " (uitgerust"
                + (worn != EquipmentStylePlanner.ArmorStyle.UNKNOWN ? ", armor: " + worn : "") + ")");
        return -1;
    }

    /**
     * Equip-fase na één bank-withdraw: bank blijft dicht, geen {@code tryOpenBankAtGrandExchange} per armor-stuk.
     * @return delay &gt; 0 als nog bezig, -1 als afgerond via {@link #tryCompleteGearPreparation}
     */
    private int handleGearPrepEquipPhase(CombatBotConfig.ImpsCombatStyle style) {
        paint.setCurrentStatus("Imps: Gear aantrekken (bank dicht)...");

        if (style == CombatBotConfig.ImpsCombatStyle.MAGE) {
            int clearMelee = clearEquippedMeleeWeaponBlockingMage();
            if (clearMelee > 0) {
                return clearMelee;
            }
        }

        boolean amuletEquipped = Equipment.contains(item -> item != null && item.getName() != null
                && item.getName().toLowerCase(Locale.ROOT).contains("amulet"));
        if (!amuletEquipped) {
            String invAmulet = bestInventoryAmuletForStyle(style);
            if (invAmulet != null) {
                IInventoryItem amulet = Inventory.getFirst(item -> item != null && item.getName() != null
                        && item.getName().equalsIgnoreCase(invAmulet));
                if (amulet != null) {
                    InventoryActionHelper.interact(config, amulet, amulet.hasAction("Wear") ? "Wear" : "Wield");
                    chatLog(invAmulet + " aangedaan");
                    return antiBan.varyDelay(randomDelay(600, 1000));
                }
            }
        }

        equipGear(style);
        if (!impsMeleeSkipsArmour(style) && tryEquipStyleArmour(style)) {
            return antiBan.varyDelay(randomDelay(650, 1050));
        }

        int prepDone = tryCompleteGearPreparation(style);
        if (prepDone == -1) {
            gearPrepInvEquipPhase = false;
            return randomDelay(600, 1000);
        }
        if (prepDone > 0) {
            return prepDone;
        }

        if (hasAdequateGearForStyle(style) && getCoinCount() >= impsRuntimeMinCoins()) {
            chatLog("[!] Gear prep equip-fase: items op zak maar equip mislukt — bank opnieuw");
        }
        gearPrepInvEquipPhase = false;
        gearPrepArmourBatchWithdrawDone = false;
        return antiBan.varyDelay(randomDelay(600, 1000));
    }

    /** Bank-withdraw klaar → sluit bank en start equip-fase. */
    private int beginGearPrepEquipPhase(CombatBotConfig.ImpsCombatStyle style) {
        gearPrepInvEquipPhase = true;
        if (Bank.isOpen()) {
            Bank.close();
            sleep(400, 700);
        }
        return handleGearPrepEquipPhase(style);
    }

    /** Config/account style gewijzigd (bv. melee→mage): opnieuw banken + equippen. */
    private void maybeRestartGearPrepOnBaselineStyleChange() {
        CombatBotConfig.ImpsCombatStyle baseline = impsCombatStyleBaselineForAccount();
        if (lastObservedImpsBaselineStyle == null) {
            lastObservedImpsBaselineStyle = baseline;
            return;
        }
        if (baseline == lastObservedImpsBaselineStyle) {
            return;
        }
        chatLog("[~] Imps combat style gewijzigd: " + lastObservedImpsBaselineStyle + " -> " + baseline
                + " — gear prep opnieuw");
        lastObservedImpsBaselineStyle = baseline;
        effectiveStyle = null;
        fallbackStyleActive = false;
        mageAutocastAppliedFor = null;
        preparingGear = true;
        gearPrepComplete = false;
    }

    private boolean hasMeleeWeapon() {
        // Check equipment
        boolean equipped = Equipment.contains(item -> {
            if (item == null || item.getName() == null) return false;
            return isCombatMeleeWeaponName(item.getName().toLowerCase());
        });
        if (equipped) return true;
        // Check inventory
        return Inventory.contains(item -> {
            if (item == null || item.getName() == null) return false;
            return isCombatMeleeWeaponName(item.getName().toLowerCase());
        });
    }

    private boolean isToolItemName(String lower) {
        if (lower == null) return false;
        return lower.contains("pickaxe") || lower.contains("hatchet");
    }

    private boolean isCombatMeleeWeaponName(String lower) {
        if (lower == null) return false;
        if (isToolItemName(lower)) return false;
        if (lower.contains("battleaxe")) return true; // combat axe
        if (lower.contains("axe")) return false;      // bijv. Iron axe tool
        return lower.contains("scimitar") || lower.contains("sword") || lower.contains("dagger")
                || lower.contains("mace") || lower.contains("halberd")
                || lower.contains("longsword") || lower.contains("warhammer");
    }

    private boolean hasRangedSetup() {
        if (inventoryOrEquipBestRangedBowProgressRank() >= Integer.MAX_VALUE) {
            return false;
        }
        return hasRangedAmmoSufficientForTrip();
    }

    private boolean hasRangedAmmoSufficientForTrip() {
        return getImpsEffectiveArrowCount() >= GEAR_PREP_MIN_ARROWS;
    }

    private boolean hasMageSetup() {
        CombatBotConfig.ImpsMageSpell spell = getActiveMageSpell();

        // Staff: equipped of in inventory (niet op hasAction("Wield") vertrouwen - kan per client anders zijn)
        boolean hasStaffEquipped = Equipment.contains(item -> {
            if (item == null || item.getName() == null) return false;
            return item.getName().toLowerCase().contains("staff");
        });
        boolean hasStaffInInv = Inventory.contains(item -> {
            if (item == null || item.getName() == null) return false;
            return item.getName().toLowerCase().contains("staff");
        });
        if (!hasStaffEquipped && !hasStaffInInv) return false;

        int catalystCount = getItemQuantity(spell.getCatalystRune());
        if (isBelowCatalystTripMinimum(catalystCount, spell)) {
            return false;
        }

        String elementalRune = spell.getElementalRune();
        String element = elementalRune.toLowerCase(Locale.ROOT).replace(" rune", "");
        boolean staffCoversElemental = hasStaffWithElement(element);
        int minElem = minElementalRunesForTrip(spell);
        if (!staffCoversElemental && getItemQuantity(elementalRune) < minElem) {
            return false;
        }

        // Strike/bolt met extra Air naast elemental (bv. Fire Strike + Fire staff: nog Air runes nodig)
        if (spell.needsAirRune()) {
            int invAirStrict = requiredInventoryAirForAccountAutoFireSetup();
            if (invAirStrict >= 0) {
                if (!hasStaffWithElement("fire") || getItemQuantity("Air rune") < invAirStrict) {
                    return false;
                }
            } else {
                boolean staffCoversAir = hasStaffWithElement("air");
                int airNeed = requiredAirRunesForMageDeparture();
                if (!staffCoversAir && getItemQuantity("Air rune") < airNeed) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Staff in equipment óf inventory die het element dekt (fire/air/water/earth). */
    private boolean hasStaffWithElement(String element) {
        if (element == null || element.isEmpty()) {
            return false;
        }
        String el = element.toLowerCase(Locale.ROOT);
        return Equipment.contains(item -> item != null && item.getName() != null
                && item.getName().toLowerCase(Locale.ROOT).contains(el)
                && item.getName().toLowerCase(Locale.ROOT).contains("staff"))
                || Inventory.contains(item -> item != null && item.getName() != null
                && item.getName().toLowerCase(Locale.ROOT).contains(el)
                && item.getName().toLowerCase(Locale.ROOT).contains("staff"));
    }

    /** Equipped of in inventory (voor starter GE-koop Staff of air). */
    private boolean hasAirStaffEquippedOrInventory() {
        return hasStaffWithElement("air");
    }

    private boolean hasStaffInInvOrEquip() {
        return Equipment.contains(item -> item != null && item.getName() != null
                && item.getName().toLowerCase(Locale.ROOT).contains("staff"))
                || Inventory.contains(item -> item != null && item.getName() != null
                && item.getName().toLowerCase(Locale.ROOT).contains("staff"));
    }

    /** Bank heeft een bruikbare staff (preferred of fallback-lijst). */
    private boolean bankContainsUsableStaff(CombatBotConfig.ImpsMageSpell spell) {
        if (spell == null) {
            return false;
        }
        if (bankHasUsableItemNamed(spell.getPreferredStaff())) {
            return true;
        }
        for (String s : MAGE_BANK_STAFF_FALLBACKS) {
            if (bankHasUsableItemNamed(s)) {
                return true;
            }
        }
        return false;
    }

    /** GE startprijs per staff (min. 2000 gp budget voor Staff of air/fire). */
    private int estimateStaffGeStartPrice(String staffName) {
        if (staffName == null) {
            return 2000;
        }
        try {
            if (staffName.equalsIgnoreCase("Staff of air")) {
                return Math.max(2000, (int) Math.ceil(Prices.getItemPrice(ITEM_ID_STAFF_OF_AIR) * 1.15));
            }
            if (staffName.equalsIgnoreCase("Staff of fire")) {
                return Math.max(2000, (int) Math.ceil(Prices.getItemPrice(ITEM_ID_STAFF_OF_FIRE) * 1.15));
            }
        } catch (Exception ignored) {
        }
        return 2000;
    }

    private int estimateGeBuySlotCostGp(GeBuySlot s) {
        if (s.quantity == 1 && s.itemName != null && s.itemName.toLowerCase(Locale.ROOT).contains("staff")) {
            return s.startPricePerUnit + 400;
        }
        long row = (long) s.quantity * (long) s.startPricePerUnit;
        return (int) Math.min(row + 800L, Integer.MAX_VALUE / 4);
    }

    private int estimateMageQueueTotalGp(ArrayList<GeBuySlot> q) {
        int sum = 0;
        for (GeBuySlot s : q) {
            sum = (int) Math.min((long) sum + (long) estimateGeBuySlotCostGp(s), Integer.MAX_VALUE / 2);
        }
        return sum;
    }

    /** Hoeveel catalyst-runen we nog moeten <i>kopen</i> om trip-minimum te halen (inv+bank elders meegeteld via getItemQuantity). */
    private int catalystDeficitToTripMinimum(CombatBotConfig.ImpsMageSpell spell) {
        if (spell == null) {
            return 0;
        }
        String cat = spell.getCatalystRune();
        int have = getItemQuantity(cat);
        int target = effectiveCatalystTripMinimum(spell);
        return Math.max(0, target - have);
    }

    /**
     * Minimum waarde voor het {@link GeBuySlot#quantity}-veld tijdens shrink/trim.
     * Voor catalyst/Air/elemental is dat bij GE altijd het <b>gewenste totaal in inventory</b>
     * (zelfde semantiek als {@link GeRestockHelper}: quantity = target stack in inv).
     */
    private int minBuyQtyFloorForGeSlot(String itemName, CombatBotConfig.ImpsMageSpell spell) {
        if (itemName == null || spell == null) {
            return 1;
        }
        if (itemName.equalsIgnoreCase(spell.getCatalystRune())) {
            return Math.max(1, effectiveCatalystTripMinimum(spell));
        }
        if ("Air rune".equalsIgnoreCase(itemName)) {
            int need = requiredAirRunesForCurrentMagePlan();
            int have = getItemQuantity("Air rune");
            if (need <= 0) {
                return 1;
            }
            return Math.max(1, Math.max(need, have));
        }
        String elem = spell.getElementalRune();
        if (itemName.equalsIgnoreCase(elem)) {
            int minElem = minElementalRunesForTrip(spell);
            int have = getItemQuantity(elem);
            return Math.max(1, Math.max(minElem, have));
        }
        return 1;
    }

    /**
     * Verlaag rune-stacks in de bundel tot ze binnen budget passen — niet meteen hele regels droppen
     * (anders verdween Mind vóór staff bij staff+1000 Mind i.p.v. staff+85 Mind).
     */
    private void shrinkMageGeBuyBundleToBudget(ArrayList<GeBuySlot> bundle, int maxCoins, CombatBotConfig.ImpsMageSpell spell) {
        if (bundle == null || spell == null) {
            return;
        }
        for (int round = 0; round < 260 && !bundle.isEmpty() && estimateMageQueueTotalGp(bundle) > maxCoins; round++) {
            boolean changed = false;
            for (int i = 0; i < bundle.size(); i++) {
                GeBuySlot s = bundle.get(i);
                if (s.itemName != null && s.itemName.toLowerCase(Locale.ROOT).contains("staff")) {
                    continue;
                }
                int floor = minBuyQtyFloorForGeSlot(s.itemName, spell);
                if (s.quantity <= floor) {
                    continue;
                }
                int next = Math.max(floor, (s.quantity * 2) / 3);
                if (next < s.quantity) {
                    bundle.set(i, new GeBuySlot(s.itemName, next, s.startPricePerUnit));
                    changed = true;
                    break;
                }
            }
            if (!changed) {
                break;
            }
        }
    }

    /**
     * Als het ná shrinken nog te duur is: verwijder vanaf het einde, maar nooit de catalyst-regel op zijn minimum
     * (dan liever utility/staff-keuze aan andere logica overlaten).
     */
    private void trimMageGeBuyBundleOverBudget(ArrayList<GeBuySlot> bundle, int maxCoins, CombatBotConfig.ImpsMageSpell spell) {
        if (bundle == null || spell == null) {
            return;
        }
        for (int safety = 0; safety < 40 && !bundle.isEmpty() && estimateMageQueueTotalGp(bundle) > maxCoins; safety++) {
            int victim = -1;
            for (int i = bundle.size() - 1; i >= 0; i--) {
                GeBuySlot s = bundle.get(i);
                if (s.itemName != null && s.itemName.toLowerCase(Locale.ROOT).contains("staff")) {
                    continue;
                }
                int floor = minBuyQtyFloorForGeSlot(s.itemName, spell);
                if (s.itemName != null && s.itemName.equalsIgnoreCase(spell.getCatalystRune()) && s.quantity <= floor) {
                    continue;
                }
                victim = i;
                break;
            }
            if (victim < 0) {
                break;
            }
            bundle.remove(victim);
        }
    }

    /**
     * Bundel alles wat voor MAGE nog ontbreekt en niet uit de bank komt (volgorde: staff → mind → air → elemental).
     */
    private ArrayList<GeBuySlot> buildMageGeBuyQueue() {
        ArrayList<GeBuySlot> q = new ArrayList<>();
        CombatBotConfig.ImpsMageSpell spell = getActiveMageSpell();
        if (spell == null) {
            return q;
        }
        int runePrice = Math.max(3, config.impsAmmoRestockPrice());

        String elem = spell.getElementalRune();
        String el = elem.toLowerCase(Locale.ROOT).replace(" rune", "");
        String preferredStaff = spell.getPreferredStaff();
        boolean hasPreferredElementStaff = hasStaffWithElement(el);
        boolean bankHasPreferredStaff = preferredStaff != null && !preferredStaff.trim().isEmpty() && bankHasUsableItemNamed(preferredStaff);
        boolean queuedPreferredStaff = false;
        if (!hasPreferredElementStaff && !bankHasPreferredStaff) {
            q.add(new GeBuySlot(preferredStaff, 1, estimateStaffGeStartPrice(preferredStaff)));
            queuedPreferredStaff = true;
        }
        // Staff of fire dekt Fire Strike elemental — geen Fire runes kopen in dezelfde trip als we net die staff kopen.
        boolean elementalCoveredByStaffPlan = hasPreferredElementStaff || bankHasPreferredStaff || queuedPreferredStaff;

        String cat = spell.getCatalystRune();
        if (isBelowCatalystTripMinimum(getItemQuantity(cat), spell)
                && (!Bank.isOpen() || bankStackQuantityIfOpen(cat) == 0)) {
            int coinsForCat = getCoinCount();
            try {
                if (Bank.isOpen() && Bank.contains("Coins")) {
                    var bc = Bank.getFirst("Coins");
                    if (bc != null) {
                        coinsForCat += bc.getQuantity();
                    }
                }
            } catch (Exception ignored) {
            }
            int tripFloor = effectiveCatalystTripMinimum(spell);
            int haveCat = getItemQuantity(cat);
            int maxAffordableUnits = runePrice > 0 ? coinsForCat / runePrice : 0;
            // GeRestockHelper: quantity = gewenst TOTAAL in inventory (niet "koop zoveel stuks").
            int affordReach = haveCat + Math.max(0, maxAffordableUnits);
            int targetTotal = Math.min(1000, Math.max(tripFloor, affordReach));
            q.add(new GeBuySlot(cat, targetTotal, runePrice));
        }

        int airMin = requiredAirRunesForCurrentMagePlan();
        boolean willBuyPreferredAirStaff = !hasPreferredElementStaff
                && !bankHasPreferredStaff
                && preferredStaff != null
                && preferredStaff.toLowerCase(Locale.ROOT).contains("air");
        if (spell.needsAirRune() && !willBuyPreferredAirStaff && !hasStaffWithElement("air") && getItemQuantity("Air rune") < airMin
                && (!Bank.isOpen() || bankStackQuantityIfOpen("Air rune") == 0)) {
            int coinInv = getCoinCount();
            int coins = coinInv;
            try {
                if (Bank.isOpen() && Bank.contains("Coins")) {
                    var bc = Bank.getFirst("Coins");
                    if (bc != null) {
                        coins += bc.getQuantity();
                    }
                }
            } catch (Exception ignored) {
            }
            int costFor2k = 2000 * runePrice;
            int wantBulkCap = coins >= costFor2k ? 2000 : 1000;
            int haveAir = getItemQuantity("Air rune");
            int maxAirAff = runePrice > 0 ? coins / runePrice : 0;
            int airTargetTotal = Math.min(wantBulkCap, Math.max(airMin, haveAir + Math.max(0, maxAirAff)));
            q.add(new GeBuySlot("Air rune", airTargetTotal, runePrice));
        }

        int minElem = minElementalRunesForTrip(spell);
        if (!elementalCoveredByStaffPlan && !hasStaffWithElement(el) && getItemQuantity(elem) < minElem
                && (!Bank.isOpen() || bankStackQuantityIfOpen(elem) == 0)) {
            q.add(new GeBuySlot(elem, 500, runePrice));
        }

        return q;
    }

    private int estimateGeStartPriceByItemId(int itemId, int minPrice, double multiplier) {
        int fallback = Math.max(1, minPrice);
        try {
            int p = Math.max(1, Prices.getItemPrice(itemId));
            return Math.max(fallback, (int) Math.ceil(p * multiplier));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private boolean hasAnyBankInvEquipItem(String name) {
        if (name == null || name.trim().isEmpty()) {
            return false;
        }
        if (getItemQuantity(name) > 0) {
            return true;
        }
        return bankHasUsableItemNamed(name);
    }

    private boolean hasAtLeastFeathersAcrossBankInv(int target) {
        if (getItemQuantity("Feather") >= target) {
            return true;
        }
        try {
            if (Bank.contains("Feather")) {
                var st = Bank.getFirst("Feather");
                if (st != null && st.getQuantity() >= target) {
                    return true;
                }
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * Utility aankopen die handig zijn voor skilling na GE:
     * black/mithril/adamant axe + fly fishing rod + 1000 feathers.
     * Wordt alleen toegevoegd als item nog nergens staat (bank/inv/equip) en budget het toelaat.
     */
    private void appendUtilityGeBuysIfAffordable(ArrayList<GeBuySlot> q, int totalCoinBudget) {
        if (q == null) {
            return;
        }
        int used = estimateMageQueueTotalGp(q);
        int left = Math.max(0, totalCoinBudget - used);
        if (left <= 0) {
            return;
        }
        String rsn = tryLocalRsnForQuest();
        boolean axePackBought = rsn != null && AccountStateJsonStore.isGeUtilityAxePackBought(rsn);
        boolean fishingPackBought = rsn != null && AccountStateJsonStore.isGeUtilityFishingPackBought(rsn);

        if (!axePackBought && !hasAnyBankInvEquipItem("Black axe")) {
            GeBuySlot s = new GeBuySlot("Black axe", 1, estimateGeStartPriceByItemId(ITEM_ID_BLACK_AXE, 800, 1.30));
            int c = estimateGeBuySlotCostGp(s);
            if (left >= c) {
                q.add(s);
                left -= c;
            }
        }
        if (!axePackBought && !hasAnyBankInvEquipItem("Mithril axe")) {
            GeBuySlot s = new GeBuySlot("Mithril axe", 1, estimateGeStartPriceByItemId(ITEM_ID_MITHRIL_AXE, 1800, 1.35));
            int c = estimateGeBuySlotCostGp(s);
            if (left >= c) {
                q.add(s);
                left -= c;
            }
        }
        if (!axePackBought && !hasAnyBankInvEquipItem("Adamant axe")) {
            GeBuySlot s = new GeBuySlot("Adamant axe", 1, estimateGeStartPriceByItemId(ITEM_ID_ADAMANT_AXE, 5000, 1.40));
            int c = estimateGeBuySlotCostGp(s);
            if (left >= c) {
                q.add(s);
                left -= c;
            }
        }
        if (!fishingPackBought && !hasAnyBankInvEquipItem("Fly fishing rod")) {
            GeBuySlot s = new GeBuySlot("Fly fishing rod", 1, estimateGeStartPriceByItemId(ITEM_ID_FLY_FISHING_ROD, 120, 1.20));
            int c = estimateGeBuySlotCostGp(s);
            if (left >= c) {
                q.add(s);
                left -= c;
            }
        }
        if (!fishingPackBought && !hasAtLeastFeathersAcrossBankInv(1000)) {
            GeBuySlot s = new GeBuySlot("Feather", 1000, estimateGeStartPriceByItemId(ITEM_ID_FEATHER, 3, 1.25));
            int c = estimateGeBuySlotCostGp(s);
            if (left >= c) {
                q.add(s);
            }
        }
    }

    private void maybeMarkUtilityGeBuysCompleteForAccount() {
        String rsn = tryLocalRsnForQuest();
        if (rsn == null || rsn.trim().isEmpty()) {
            return;
        }
        if (hasAnyBankInvEquipItem("Black axe")
                && hasAnyBankInvEquipItem("Mithril axe")
                && hasAnyBankInvEquipItem("Adamant axe")) {
            AccountStateJsonStore.markGeUtilityAxePackBought(rsn);
        }
        if (hasAnyBankInvEquipItem("Fly fishing rod")
                && hasAtLeastFeathersAcrossBankInv(1000)) {
            AccountStateJsonStore.markGeUtilityFishingPackBought(rsn);
        }
    }

    private String summarizeGeBuyQueue(ArrayList<GeBuySlot> q) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < q.size(); i++) {
            GeBuySlot s = q.get(i);
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(s.quantity).append("× ").append(s.itemName);
        }
        return sb.toString();
    }

    /**
     * Vol of “bijna vol” voor starter-bridge eerste Karamja-ronde: pas dan Home→GE / banking,
     * niet bij alleen 30 gp boottekort met 1–2 loot stacks.
     */
    private boolean starterBridgeInventoryStuffedForGeTrip() {
        return Inventory.getFreeSlots() <= STARTER_BRIDGE_GE_TRIP_MAX_FREE_SLOTS;
    }

    /**
     * Als we wél gaan banken maar de Home→GE-snelroute niet pakken: leg uit waarom (max. eens per 8s).
     */
    private void maybeLogStarterHomeGeSkippedWhileBanking() {
        if (!starterSkillBridge) {
            return;
        }
        if (starterBridgeFirstGeCyclePending && hasDepositItems()
                && starterBridgeInventoryStuffedForGeTrip()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastStarterHomeGeSkipDiagnosticLogMs < 8000L) {
            return;
        }
        lastStarterHomeGeSkipDiagnosticLogMs = now;
        StringBuilder sb = new StringBuilder();
        sb.append("[Starter] Home→GE overgeslagen → gewone loot/bank-trip: ");
        if (!starterBridgeFirstGeCyclePending) {
            sb.append("eerste GE-cyclus al gedaan (account JSON); ");
        }
        if (!starterBridgeInventoryStuffedForGeTrip()) {
            sb.append("inv niet vol genoeg (nodig ≤").append(STARTER_BRIDGE_GE_TRIP_MAX_FREE_SLOTS)
                    .append(" vrije slots, nu ").append(Inventory.getFreeSlots()).append("); ");
        }
        if (!hasDepositItems()) {
            sb.append("geen deposit-items (alleen keep-items/coins/gear); ");
        }
        chatLog(sb.toString().trim());
    }

    private int getItemQuantity(String itemName) {
        int count = 0;
        try {
            var equippedItems = Equipment.getAll(item ->
                    item != null && item.getName() != null && item.getName().equalsIgnoreCase(itemName));
            if (equippedItems != null) {
                for (var eq : equippedItems) {
                    if (eq != null) count += eq.getQuantity();
                }
            }
        } catch (Exception e) {}
        // Niet Inventory.getFirst: die ziet vaak maar 1 stack en mist noted/andere stacks -> valse "withdraw mislukt" -> GE.
        count += getInventoryQuantityIncludingStacks(itemName);
        return count;
    }

    /**
     * Alle stacks in inventory met deze naam (noted + unnoted), voor runes/coins/stackables.
     */
    private int getInventoryQuantityIncludingStacks(String itemName) {
        if (itemName == null || itemName.isEmpty()) return 0;
        int bySdk = 0;
        try {
            bySdk = Inventory.getCount(true, itemName);
        } catch (Exception ignored) {
        }
        int byScan = 0;
        try {
            for (IInventoryItem it : Inventory.getAll()) {
                if (it != null && it.getName() != null && it.getName().equalsIgnoreCase(itemName)) {
                    byScan += it.getQuantity();
                }
            }
        } catch (Exception ignored) {
        }
        return Math.max(bySdk, byScan);
    }

    /** Bank moet unnoted withdraw gebruiken voor runes; UI kan traag zijn - meerdere keren forceren. */
    private void ensureBankWithdrawUnnoted() {
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                if (!Bank.isNotedWithdrawMode()) {
                    return;
                }
                Bank.setWithdrawMode(false);
                sleep(400, 700);
            } catch (Exception ignored) {
                return;
            }
        }
    }

    /** Bank moet noted withdraw gebruiken voor GE-sell; UI kan traag zijn - meerdere keren forceren. */
    private void ensureBankWithdrawNoted() {
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                if (Bank.isNotedWithdrawMode()) {
                    return;
                }
                Bank.setWithdrawMode(true);
                sleep(350, 600);
            } catch (Exception ignored) {
                return;
            }
        }
    }

    /** Na {@link Bank#withdraw}: wacht tot equip+inv-totaal stijgt (langere timeout dan 1 tick). */
    private boolean waitUntilItemQuantityIncreases(String itemName, int qtyBeforeWithdraw, long maxWaitMs) {
        long deadline = System.currentTimeMillis() + maxWaitMs;
        while (System.currentTimeMillis() < deadline) {
            if (getItemQuantity(itemName) > qtyBeforeWithdraw) {
                return true;
            }
            try {
                Thread.sleep(80);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * Hoeveelheid stackable in bank voor deze exacte naam — placeholders tellen niet mee (qty 0).
     * Los staat van {@link Bank#contains}: die kan "wel een slot" tonen terwijl er niets opneembaar is.
     */
    private int bankStackQuantityIfOpen(String itemName) {
        if (itemName == null || itemName.trim().isEmpty() || !Bank.isOpen()) {
            return 0;
        }
        try {
            return Math.max(0, Bank.getCount(true, item -> item != null && item.getName() != null
                    && item.getName().equalsIgnoreCase(itemName)));
        } catch (Exception ignored) {
            return 0;
        }
    }

    /**
     * Er ligt daadwerkelijk voorraad in de bank (niet alleen placeholder). Bank dicht: val terug op {@code contains}.
     */
    private boolean bankHasUsableItemNamed(String itemName) {
        if (itemName == null || itemName.trim().isEmpty()) {
            return false;
        }
        if (Bank.isOpen()) {
            return bankStackQuantityIfOpen(itemName) > 0;
        }
        try {
            return Bank.contains(itemName);
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Varrock teleport: 1 Law, 3 Air, 1 Fire. Staff of fire dekt Fire, staff of air dekt Air. */
    private boolean staffCoversFire() {
        return Equipment.contains(item -> {
            if (item == null || item.getName() == null) return false;
            String n = item.getName().toLowerCase();
            return n.contains("fire") && n.contains("staff");
        });
    }

    private boolean staffCoversAir() {
        return Equipment.contains(item -> {
            if (item == null || item.getName() == null) return false;
            String n = item.getName().toLowerCase();
            return n.contains("air") && n.contains("staff");
        });
    }

    public boolean isPreparingGear() {
        return preparingGear && !gearPrepComplete;
    }

    private static int minX(WorldPoint a, WorldPoint b, WorldPoint c, WorldPoint d) {
        return Math.min(Math.min(a.getX(), b.getX()), Math.min(c.getX(), d.getX()));
    }

    private static int maxX(WorldPoint a, WorldPoint b, WorldPoint c, WorldPoint d) {
        return Math.max(Math.max(a.getX(), b.getX()), Math.max(c.getX(), d.getX()));
    }

    private static int minY(WorldPoint a, WorldPoint b, WorldPoint c, WorldPoint d) {
        return Math.min(Math.min(a.getY(), b.getY()), Math.min(c.getY(), d.getY()));
    }

    private static int maxY(WorldPoint a, WorldPoint b, WorldPoint c, WorldPoint d) {
        return Math.max(Math.max(a.getY(), b.getY()), Math.max(c.getY(), d.getY()));
    }

    /** Musa Point / Karamja eiland — binnen {@link #KARAMJA_BOX_CORNER_1}…{@link #KARAMJA_BOX_CORNER_4} box. */
    public static boolean isOnKaramja(WorldPoint pos) {
        if (pos == null || pos.getPlane() != 0) {
            return false;
        }
        int x = pos.getX();
        int y = pos.getY();
        return x >= KARAMJA_REGION_MIN_X && x <= KARAMJA_REGION_MAX_X
                && y >= KARAMJA_REGION_MIN_Y && y <= KARAMJA_REGION_MAX_Y;
    }

    public static boolean isOnKaramja(IPlayer local) {
        return local != null && isOnKaramja(local.getWorldLocation());
    }

    /**
     * Gedeeld Karamja → Port Sarim: zelfde boot als loot-dump ({@link #travelFromKaramjaToPortSarim} + Travel).
     * Voor skill-wissel / gear prep op mainland — geen gear-prep Home-tele shortcut.
     *
     * @return tick-delay &gt; 0 als actie gestart; 0 als speler niet op Karamja
     */
    public int travelToPortSarimFromKaramja(IPlayer local) {
        if (!isOnKaramja(local)) {
            karamjaSharedBoatTrip = false;
            return 0;
        }
        WorldPoint pos = local.getWorldLocation();
        // Zelfde state-reset als handleBankingTrip op Karamja (loot-dump)
        gearPrepKaramjaUseHomeTele = false;
        gearPrepKaramjaBoatFailStreak = 0;
        karamjaSharedBoatTrip = true;
        return travelFromKaramjaToPortSarim(local, pos, true);
    }

    /** {@code true} tijdens gedeelde Karamja-boot (gear prep / skill-wissel / BankHelper) — zelfde {@link #payFare()} als loot-dump. */
    private boolean karamjaSharedBoatTrip = false;

    private boolean isLootDumpStyleKaramjaBoat() {
        return isBankingTrip || karamjaSharedBoatTrip;
    }

    private void clearKaramjaSharedBoatIfMainland(WorldPoint pos) {
        if (pos != null && !isOnKaramja(pos)) {
            karamjaSharedBoatTrip = false;
        }
    }

    /**
     * Start banking van beads/talisman voor skill rotatie.
     */
    public boolean startBankingBeforeRotation() {
        if (hasDepositItems()) {
            bankingBeforeRotation = true;
            isBankingTrip = true;
            return true;
        }
        return false;
    }

    public boolean isBankingBeforeRotation() {
        return bankingBeforeRotation;
    }

    public int loop() {
        try {
            IPlayer local = Players.getLocal();
            if (local == null) return 1000;
            maybeRestartGearPrepOnBaselineStyleChange();
            if (config.impsGeSellEnabled()) {
                paint.setImpsGeBankTripProgress(bankTripCount, Math.max(1, config.impsGeSellAfterBanks()));
            } else {
                paint.setImpsGeBankTripProgress(0, 0);
            }

            // Als de user de bot uitzet terwijl we nog in een handler-call zitten,
            // moeten we direct stoppen i.p.v. nog een fallback/GE-chain door te zetten.
            if (!config.botEnabled()) {
                isSellingAtGe = false;
                isBuyingLawRunesAtGe = false;
                isBuyingAmmoAtGe = false;
                preparingGear = false;
                gearPrepComplete = false;
                shouldSwitchToNormal = false;
                coinRecoveryMode = false;
                coinRecoveryGeSellPending = false;
                coinRecoveryBankChecked = false;
                buyAmmoStep = 0;
                buyLawRunesStep = 0;
                geSellStep = 0;
                isBankingTrip = false;
                fleeingFromScorpion = false;
                pickingUpLoot = false;
                starterPostGeWealthCheck = false;
                starterBridgeHomeTeleportToGePending = false;
                starterBridgeGeBuyMindStaffActive = false;
                return 1000;
            }

            if (starterPostGeWealthCheck) {
                return handleStarterPostGeWealthCheck(local);
            }

            int hopWait = processPendingImpsWorldHop();
            if (hopWait > 0) {
                return hopWait;
            }

            // [OK] FAILSAFE: als shouldSwitchToNormal al gezet is, stop DIRECT
            // Voorkom dat de bot nog een banking trip / deposit box start na een hard stop
            if (shouldSwitchToNormal) {
                paint.setCurrentStatus("[!] Imps: Switching naar normal combat...");
                return 2000;
            }

            if (starterBridgeHomeTeleportToGePending) {
                return handleStarterBridgeHomeTeleportThenGe(local);
            }

            // GE selling trip: hoogste prioriteit zodat "Sell now" direct naar bank/GE gaat
            if (isSellingAtGe) {
                return handleGeSelling(local);
            }

            if (coinRecoveryMode) {
                return handleCoinRecoveryMode(local);
            }

            if (starterBridgeGeBuyMindStaffActive) {
                return handleStarterBridgeGeBuyMindStaff(local);
            }

            // Alleen Law runes kopen bij GE (gear prep had te weinig in bank)
            if (isBuyingLawRunesAtGe) {
                return handleBuyLawRunesAtGe(local);
            }

            // GE ammo/rune restock (Tier 1 failsafe bij lege bank)
            if (isBuyingAmmoAtGe) {
                return handleBuyAmmoAtGe(local);
            }

            // Gear preparation: bank trip om coins + gear op te halen (eenmalig)
            if (preparingGear && !gearPrepComplete) {
                return handleGearPreparation(local);
            }
            // Scorpion flee ALLEEN als we NIET op een banking trip zijn
            if (!isBankingTrip) {
                if (isBeingAttackedByScorpion(local)) {
                    fleeingFromScorpion = true;
                }
                if (fleeingFromScorpion) {
                    return handleFleeFromScorpion(local);
                }
            } else {
                // Op banking trip: negeer scorpion, gewoon doorlopen
                fleeingFromScorpion = false;
            }

            // Altijd eerst eten als HP laag
            if (shouldEat() && hasFoodToEat()) {
                paint.setCurrentStatus("Imps: Eten...");
                return handleEating();
            }

            // Special loot prioriteit: als inventory vol is door ashes, eerst 1+ ashes strooien voor ruimte.
            if (config.impsScatterAshes()
                    && hasSpecialLootNearby()
                    && Inventory.isFull()
                    && getFiendishAshesCount() > 0) {
                paint.setCurrentStatus("Imps: Ruimte maken voor speciale loot (ashes)");
                return handleScatterAshes();
            }

            // Fiendish ashes in random batches (1-10), of geforceerd als inventory vol raakt.
            if (config.impsScatterAshes() && shouldScatterAshesNow()) {
                paint.setCurrentStatus("Imps: Fiendish ashes verstrooien");
                return handleScatterAshes();
            }

            // Als we op een banking trip zijn
            if (isBankingTrip) {
                return handleBankingTrip(local);
            }

            int autoFireDelay = maybeAutoUpgradeToFireStrike(local);
            if (autoFireDelay > 0) {
                return autoFireDelay;
            }

                // Fire Strike: fire staff + Air inventory (Magic auto-accounts); géén blijven hangen op Air staff + Fire rune.
                // Tier 2 melee-fallback: niet opnieuw MAGE forceren — dat veroorzaakte een banker/gear-prep loop.
                if (!(fallbackStyleActive && getEffectiveStyle() == CombatBotConfig.ImpsCombatStyle.MELEE)
                        && fireStrikeAirDepartureGateFails()) {
                    int strict = requiredInventoryAirForAccountAutoFireSetup();
                    int disp = strict >= 0 ? strict : requiredAirRunesForMageDeparture();
                    chatLog("[!] Fire Strike gate: Staff of fire + Air inv nodig (" + getItemQuantity("Air rune") + "/" + disp + ") -> gear prep");
                    effectiveStyle = CombatBotConfig.ImpsCombatStyle.MAGE;
                    fallbackStyleActive = false;
                    preparingGear = true;
                    gearPrepComplete = false;
                    return handleGearPreparation(local);
                }

                // Check of we moeten gaan banken
            if (shouldStartBanking()) {
                // Nooit loot dumpen terwijl Fiendish ashes nog in inventory zitten:
                // eerst ashes verstrooien, daarna pas banking opnieuw evalueren.
                if (config.impsScatterAshes() && getFiendishAshesCount() > 0) {
                    paint.setCurrentStatus("Imps: Eerst Fiendish ashes verstrooien");
                    return handleScatterAshes();
                }

                maybeLogStarterHomeGeSkippedWhileBanking();
                if (starterSkillBridge && starterBridgeFirstGeCyclePending && hasDepositItems()
                        && starterBridgeInventoryStuffedForGeTrip()) {
                    starterBridgeHomeTeleportToGePending = true;
                    if (getCoinCount() < BOAT_FARE) {
                        chatLog("[Starter] Inv bijna vol (" + Inventory.getFreeSlots() + " vrije slots) + geen "
                                + BOAT_FARE + " gp voor terugboot — Home teleport → GE");
                    } else {
                        chatLog("[Starter] Inv bijna vol (" + Inventory.getFreeSlots() + " vrije slots) — Home teleport → GE");
                    }
                    return handleStarterBridgeHomeTeleportThenGe(local);
                }

                int coins = getCoinCount();
                boolean restockNeeded = checkIfRestockNeeded();
                chatLog("Banking check: coins=" + coins + ", min=" + impsRuntimeMinCoins()
                        + ", inv full=" + Inventory.isFull() + ", restock=" + restockNeeded);

                if (coins < impsRuntimeMinCoins()) {
                    // Coins kunnen nog op de bank staan - probeer EERST gear prep opnieuw
                    // Alleen switchen als gearPrepComplete al true is (we zijn al bij de bank geweest)
                    if (gearPrepComplete) {
                        chatLog("[!] Te weinig coins (" + coins + ") en gear prep al gedaan! Switching naar normal combat.");
                        paint.setCurrentStatus("[!] Imps: Te weinig coins (" + coins + ")! Switching...");
                        shouldSwitchToNormal = true;
                        return 2000;
                    } else {
                        // Nog niet bij bank geweest - start gear prep om coins op te halen
                        chatLog("Te weinig coins (" + coins + "), maar gear prep nog niet gedaan - ga naar bank.");
                        preparingGear = true;
                        return handleGearPreparation(local);
                    }
                }

                isBankingTrip = true;
                isRestocking = restockNeeded;
                if (restockNeeded) {
                    chatLog("[~] RESTOCK trip: naar volwaardige bank (supplies aanvullen)");
                } else {
                    chatLog("[loot] LOOT DUMP trip: naar deposit box (alleen loot dumpen)");
                }
                return handleBankingTrip(local);
            }

            // MAGE vertrek-check: niet naar Karamja met te weinig trip-runes (ook als alleen config MAGE is).
            if (isMageImpsRouteActive() && checkIfRestockNeeded()) {
                chatLog("[~] MAGE supplies onder trip-minimum — restock vóór vertrek/jagen");
                isBankingTrip = true;
                isRestocking = true;
                return handleBankingTrip(local);
            }

            // MAGE: volledige trip-setup (hasMageSetup), niet alleen ≥1 cast — anders boot met 0 Air bij Fire Strike.
            if (isMageImpsRouteActive()) {
                if (!hasMageSetup()) {
                    chatLog("[!] Mage trip onvolledig (spell=" + getActiveMageSpell().getSpellName()
                            + ", Wind config + Fire auto-upgrade telt als Fire) -> gear prep");
                    preparingGear = true;
                    gearPrepComplete = false;
                    effectiveStyle = CombatBotConfig.ImpsCombatStyle.MAGE;
                    fallbackStyleActive = false;
                    return handleGearPreparation(local);
                }
            } else if (!hasCombatReadyGearForStyle(getEffectiveStyle())) {
                chatLog(" Gear niet compleet voor " + getEffectiveStyle() + " - terug naar bank voor gear prep");
                preparingGear = true;
                gearPrepComplete = false;
                return handleGearPreparation(local);
            }

            // Check of we in het hunting gebied zijn (Karamja)
            WorldPoint myPos = local.getWorldLocation();
            boolean onKaramja = isOnKaramja(myPos);
            if (onKaramja) {
                resetPortSarimBoatCrossingState();
            } else {
                karamjaPortBoatNpcMissingSinceMs = 0L;
            }
            if (!onKaramja) {
                if (isMageImpsRouteActive() && !hasMageSetup()) {
                    chatLog("[!] Boot geblokkeerd: mage runes/staff nog niet op trip-niveau");
                    preparingGear = true;
                    gearPrepComplete = false;
                    effectiveStyle = CombatBotConfig.ImpsCombatStyle.MAGE;
                    fallbackStyleActive = false;
                    walkingToHuntArea = false;
                    return handleGearPreparation(local);
                }
                if (getCoinCount() < BOAT_FARE) {
                    if (awaitingPortSarimToKaramjaCrossing) {
                        long sinceTowardKaramja = System.currentTimeMillis() - lastBoatTowardKaramjaInteractMs;
                        if (sinceTowardKaramja >= BOAT_TOWARD_KARAMJA_GRACE_MS) {
                            awaitingPortSarimToKaramjaCrossing = false;
                        }
                    }
                    if (awaitingPortSarimToKaramjaCrossing) {
                        walkingToHuntArea = true;
                        paint.setCurrentStatus("Imps: [boat] Oversteek naar Karamja (tarief betaald)…");
                        return payFareBack();
                    }
                    return handleInsufficientCoinsForBoatFare(local);
                }
                walkingToHuntArea = true;
                paint.setCurrentStatus("Imps: [boat] Naar Karamja...");
                return payFareBack();
            }

            if (!preparingGear && !isBankingTrip && !isSellingAtGe && !coinRecoveryMode
                    && !isBuyingLawRunesAtGe && !isBuyingAmmoAtGe) {
                int competitorHop = tryImpsCompetitorWorldHop(local);
                if (competitorHop > 0) {
                    return competitorHop;
                }
            }

            // Check of we in het hunting gebied zijn
            WorldPoint huntArea = getHuntingArea();
            int huntRadius = getHuntingRadius();
            int distToHunt = myPos.distanceTo(huntArea);
            int distToRally = myPos.distanceTo(KARAMJA_RALLY_POINT);

            // ========= WALK-TO-HUNT AREA LOGICA =========
            // Als we nog aan het lopen zijn naar de hunting area, BLOKKEER loot en combat
            // Start pas jagen als we echt binnen de geconfigureerde hunting-cirkel staan.
            // Aankomen bij hunt-center strakker houden dan de algemene hunt-radius.
            // Anders (bijv. radius 45) start zoeken al halverwege onderweg.
            int arrivalRadius = Math.min(huntRadius, Math.max(1, config.impsArrivalRadius()));
            // Hysteresis tegen heen-en-weer:
            // - Buiten hunting radius: terug naar center/area lopen.
            // - Eenmaal binnen arrivalRadius: pas "aangekomen" — niet al bij dist <= huntRadius (anders jagen halverwege).
            boolean outsideHuntArea = distToHunt > (huntRadius + 1);
            if (!walkingToHuntArea && outsideHuntArea) {
                walkingToHuntArea = true;
            }
            if (walkingToHuntArea) {

                boolean atHunt = distToHunt <= arrivalRadius;
                if (atHunt) {
                    walkingToHuntArea = false;
                    chatLog("[OK] Aangekomen bij hunt-center (dist=" + distToHunt + "/" + arrivalRadius + " tiles)");
                    debugAreaState("arrived-hunt-area", local);
                    lastImpFoundTime = System.currentTimeMillis();
                } else {
                    // Nog niet aangekomen - blijf lopen, NIET stoppen voor loot/imps
                    long now = System.currentTimeMillis();
                    boolean canReclick = (now - lastWalkClickTime) > WALK_RECLICK_INTERVAL_MS;
                    if (!canReclick && local.isMoving()) {
                        paint.setCurrentStatus("Imps: Lopen naar hunting area (" + distToHunt + " tiles)");
                        return antiBan.varyDelay(randomDelay(600, 1200));
                    }

                    // Geef (opnieuw) een walkTo opdracht, ook als we nog bewegen, maar met interval
                    int stepMin = configuredImpsStepMin();
                    int stepMax = configuredImpsStepMax(stepMin);

                    WorldPoint dest;
                    if (distToRally > config.impsRallyPointRadius() && distToHunt > 30) {
                        dest = KARAMJA_RALLY_POINT;
                        paint.setCurrentStatus("Imps: Lopen naar hunting area (" + distToHunt + " tiles)");
                        maybeLogRallyMovementDebug("Lopen naar rally point (afstand: " + distToRally + ")");
                    } else if (isPointInScorpionRadius(huntArea)) {
                        dest = KARAMJA_RALLY_POINT;
                        paint.setCurrentStatus("Imps: Hunting center in scorpion-zone - naar rally point");
                    } else {
                        dest = huntArea;
                        paint.setCurrentStatus("Imps: Lopen naar hunting area (" + distToHunt + " tiles)");
                        chatLog("Lopen naar hunting center (afstand: " + distToHunt + ")");
                    }

                    int distToDest = myPos.distanceTo(dest);
                    // Uitzondering: naar dock of hunt zone mag we door scorpion-zone - ren er doorheen
                    boolean allowThroughZone = (dest.equals(KARAMJA_RALLY_POINT) || dest.equals(huntArea));
                    if (allowThroughZone) {
                        ensureRunEnabledForScorpionZone();
                    }
                    if (distToDest > stepMax) {
                        int dx = dest.getX() - myPos.getX();
                        int dy = dest.getY() - myPos.getY();
                        double dist = Math.sqrt(dx * dx + dy * dy);
                        int stepLen = Math.min(distToDest - 1, randomDelay(stepMin, stepMax));
                        int stepX = myPos.getX() + (int) (dx / dist * stepLen);
                        int stepY = myPos.getY() + (int) (dy / dist * stepLen);
                        WorldPoint stepPoint = new WorldPoint(stepX, stepY, dest.getPlane());
                        if (dest.equals(huntArea)) {
                            stepPoint = clampToHuntCircle(stepPoint);
                        }
                        if (!allowThroughZone && isPointInScorpionRadius(stepPoint)) {
                            stepLen = stepLen / 2;
                            if (stepLen < 2) {
                                paint.setCurrentStatus("Imps: Wachten (scorpion-zone in pad)");
                                return antiBan.varyDelay(randomDelay(800, 1500));
                            }
                            stepX = myPos.getX() + (int) (dx / dist * stepLen);
                            stepY = myPos.getY() + (int) (dy / dist * stepLen);
                            stepPoint = new WorldPoint(stepX, stepY, dest.getPlane());
                            if (dest.equals(huntArea)) {
                                stepPoint = clampToHuntCircle(stepPoint);
                            }
                            if (isPointInScorpionRadius(stepPoint)) {
                                paint.setCurrentStatus("Imps: Pad blokkeert (scorpion-zone)");
                                return antiBan.varyDelay(randomDelay(800, 1500));
                            }
                        }
                        MovementHelper.walkTo(stepPoint);
                    } else {
                        if (dest.equals(huntArea)) {
                            dest = clampToHuntCircle(dest);
                        }
                        if (!allowThroughZone && isPointInScorpionRadius(dest)) {
                            paint.setCurrentStatus("Imps: Bestemming in scorpion-zone - niet lopen");
                            return antiBan.varyDelay(randomDelay(1000, 2000));
                        }
                        MovementHelper.walkTo(dest);
                    }

                    lastWalkClickTime = now;
                    return antiBan.varyDelay(randomDelay(1200, 2000));
                }
            }
            // ========= EINDE WALK-TO LOGICA =========

            // [OK] We zijn IN de hunting area - vrij rondlopen en aanvallen

            tryFinishWizardHatEquip();

            // [OK] AUTOCAST: Magic-tab + selectSpell + setAutoCast (setAutoCast alleen is onbetrouwbaar)
            if (getEffectiveStyle() == CombatBotConfig.ImpsCombatStyle.MAGE) {
                CombatBotConfig.ImpsMageSpell spell = getActiveMageSpell();
                SpellBook.Standard stdSpell = spell.getStandardSpell();
                if (Magic.isAutoCasting(stdSpell)) {
                    mageAutocastAppliedFor = spell;
                } else {
                    if (selectAutocastSpell(spell)) {
                        mageAutocastAppliedFor = spell;
                    }
                    return antiBan.varyDelay(randomDelay(600, 1000));
                }
            }

            // [OK] RUNE CHECK: alleen minstens 1 cast (trip-vulling via banking/restock)
            if (getEffectiveStyle() == CombatBotConfig.ImpsCombatStyle.MAGE) {
                String runeWarning = checkRuneSupply();
                if (runeWarning != null) {
                    paint.setCurrentStatus("[!] Imps: " + runeWarning + " -> bank");
                    chatLog(" " + runeWarning + " -> naar bank om runes te halen.");
                    preparingGear = true;
                    gearPrepComplete = false;
                    return handleGearPreparation(local);
                }
            }

            // [OK] LOOT PRIORITEIT: als we bezig zijn met loot oppakken, stop gevecht en ga door met looten
            // Dit voorkomt dat auto-retaliate de loot pickup onderbreekt
            if (pickingUpLoot) {
                // Loot is op of inv vol - klaar met looten
                if (!hasImpLootNearby() || Inventory.isFull()) {
                    pickingUpLoot = false;
                    consecutiveLootAttempts = 0;
                    if (!hasImpLootNearby()) {
                        deferNormalLootUntilAfterKill = false;
                    }
                } else if (consecutiveLootAttempts >= MAX_CONSECUTIVE_LOOT_ATTEMPTS) {
                    // Special loot niet aggressief skippen.
                    if (hasSpecialLootNearby()) {
                        consecutiveLootAttempts = 0;
                        paint.setCurrentStatus("Imps: Speciale loot prioriteit");
                        return handleLooting();
                    }
                    // B: te veel mislukte pogingen -> eerst kill, daarna opnieuw loot proberen
                    deferNormalLootUntilAfterKill = true;
                    pickingUpLoot = false;
                    consecutiveLootAttempts = 0;
                    paint.setCurrentStatus("Imps: Loot uitgesteld tot na kill (stuck)");
                    return handleKilling(local);
                } else {
                    // Nog loot op de grond - PRIORITEIT: breek gevecht af en pak op
                    if (local.isInteracting()) {
                        // Stop gevecht zodat we loot kunnen pakken
                        MovementHelper.walkTo(local.getWorldLocation()); // klik op eigen tile = stop combat
                        paint.setCurrentStatus("Imps: Gevecht afbreken voor loot");
                        return antiBan.varyDelay(randomDelay(300, 600));
                    }
                    // Niet als "mislukte poging" tellen terwijl we nog onderweg/animating zijn.
                    if (!local.isMoving() && local.getAnimation() == -1) {
                        consecutiveLootAttempts++;
                    }
                    paint.setCurrentStatus("Imps: Loot oppakken (prioriteit)");
                    return handleLooting();
                }
            }

            // Speciale loot: ALTIJD direct oppakken, zelfs tijdens gevecht (hoogste prioriteit)
            if (hasSpecialLootNearby() && !Inventory.isFull()) {
                if (local.isInteracting()) {
                    MovementHelper.walkTo(local.getWorldLocation());
                    paint.setCurrentStatus("Imps: Gevecht afbreken voor speciale loot!");
                    return antiBan.varyDelay(randomDelay(300, 600));
                }
                prioritizeSpecialLootUntil = System.currentTimeMillis() + 8000;
                pickingUpLoot = true;
                paint.setCurrentStatus("Imps: Speciale loot oppakken!");
                return handleLooting();
            }

            // Normale loot: alleen als we niet in gevecht zijn
            if (!local.isInteracting() && isLootAllowed() && hasImpLootNearby() && !Inventory.isFull()
                    && !deferNormalLootUntilAfterKill) {
                if (consecutiveLootAttempts >= MAX_CONSECUTIVE_LOOT_ATTEMPTS) {
                    deferNormalLootUntilAfterKill = true;
                    consecutiveLootAttempts = 0;
                    paint.setCurrentStatus("Imps: Loot uitgesteld tot na kill (te veel pogingen)");
                    return handleKilling(local);
                }
                // Onderweg naar loot telt niet als stuck-poging.
                if (!local.isMoving() && local.getAnimation() == -1) {
                    consecutiveLootAttempts++;
                }
                pickingUpLoot = true; // markeer dat we bezig zijn met looten
                paint.setCurrentStatus("Imps: Loot oppakken");
                return handleLooting();
            }
            if (deferNormalLootUntilAfterKill && !hasImpLootNearby() && !hasSpecialLootNearby()) {
                deferNormalLootUntilAfterKill = false;
            }
            consecutiveLootAttempts = 0;
            if (hasImpLootNearby() && !isLootAllowed()) {
                paint.setCurrentStatus("Imps: Loot delay actief (" + killsSinceLastLoot + "/" + config.lootDelayKills() + " kills)");
            }

            // Imps killen (alleen als er geen loot te pakken valt of we niet mogen looten)
            paint.setCurrentStatus("Imps: Killen");
            return handleKilling(local);

        } catch (Exception e) {
            paint.setCurrentStatus("[!] Imps fout: " + e.getMessage());
            return 2000;
        }
    }

    public int getBankTripCount() {
        return bankTripCount;
    }

    public void resetBankTripCount() {
        bankTripCount = 0;
    }

    // ===================== SCORPION FLEE =====================

    private boolean isBeingAttackedByScorpion(IPlayer local) {
        INPC scorpion = NPCs.getNearest(npc ->
                npc.getName() != null
                        && npc.getName().equalsIgnoreCase("Scorpion")
                        && npc.isInteracting()
                        && npc.getInteracting() != null
                        && npc.getInteracting().equals(local)
        );
        if (scorpion != null) {
            recordScorpionAttackHotspot(local);
        }
        return scorpion != null;
    }

    private void recordScorpionAttackHotspot(IPlayer local) {
        if (local == null || local.getWorldLocation() == null || questProgressConfigManager == null) {
            return;
        }
        long now = System.currentTimeMillis();
        WorldPoint p = local.getWorldLocation();
        if (now - lastScorpionAttackRecordMs < SCORPION_ATTACK_RECORD_COOLDOWN_MS) {
            return;
        }
        if (lastScorpionAttackRecordPoint != null && lastScorpionAttackRecordPoint.distanceTo(p) <= 1) {
            return;
        }
        String rsn = local.getName() != null ? Text.removeTags(local.getName()).trim() : "";
        if (rsn.isEmpty()) {
            return;
        }
        ScorpionAttackMemoryStore.recordAttack(questProgressConfigManager, config, rsn, p);
        lastScorpionAttackRecordMs = now;
        lastScorpionAttackRecordPoint = p;
    }

    private int handleFleeFromScorpion(IPlayer local) {
        WorldPoint myPos = local.getWorldLocation();
        int dist = myPos.distanceTo(SCORPION_SAFE_POINT);

        if (dist <= SAFE_POINT_RADIUS) {
            if (!isBeingAttackedByScorpion(local)) {
                fleeingFromScorpion = false;
                paint.setCurrentStatus("Imps: Veilig, hervatten...");
                return randomDelay(1000, 2000);
            }
            paint.setCurrentStatus("Imps: Wachten tot scorpion weg is...");
            return randomDelay(1500, 3000);
        }

        // Vlucht naar safe point met random offset (mag ook tijdens bewegen re-clicken)
        int rx = SCORPION_SAFE_POINT.getX() + random.nextInt(FLEE_RANDOM_OFFSET * 2 + 1) - FLEE_RANDOM_OFFSET;
        int ry = SCORPION_SAFE_POINT.getY() + random.nextInt(FLEE_RANDOM_OFFSET * 2 + 1) - FLEE_RANDOM_OFFSET;
        WorldPoint fleeTarget = new WorldPoint(rx, ry, 0);
        paint.setCurrentStatus("Imps: Vluchten voor scorpion!");
        MovementHelper.walkTo(fleeTarget);
        lastWalkClickTime = System.currentTimeMillis();
        return antiBan.varyDelay(randomDelay(800, 1500));
    }

    // ===================== KILLING =====================

    public void onChatMessage(String message) {
        if (message != null && RangedAmmoKit.isRangedEmptyQuiverGameMessage(message)) {
            lastImpsRangedEmptyQuiverGameMessageMs = System.currentTimeMillis();
        }
    }

    private boolean hasImpsRangedWeaponEquipped() {
        var list = Equipment.getAll(item -> {
            if (item == null || item.getName() == null) {
                return false;
            }
            String n = item.getName().toLowerCase(Locale.ROOT);
            return n.contains("shortbow") || n.contains("longbow") || n.contains("crossbow")
                    || n.contains("ballista") || n.contains("blowpipe") || n.contains("bow");
        });
        return list != null && !list.isEmpty();
    }

    /**
     * Alleen voor Imps RANGED: quiver moet gevuld zijn voordat {@code Attack} mag.
     * Anders 1 equip-poging per tick of gear prep als er geen bruikbare stacks in de rugzak zitten.
     */
    private int enforceImpsRangedAmmoOrDelay(IPlayer local) {
        if (getEffectiveStyle() != CombatBotConfig.ImpsCombatStyle.RANGED) {
            return 0;
        }
        if (Equipment.contains("Toxic blowpipe")) {
            return 0;
        }
        if (!hasImpsRangedWeaponEquipped()) {
            return 0;
        }
        int rngLvl;
        try {
            rngLvl = Skills.getLevel(Skill.RANGED);
        } catch (Exception e) {
            rngLvl = 1;
        }
        boolean recentEmpty = System.currentTimeMillis() - lastImpsRangedEmptyQuiverGameMessageMs < 6000L;
        int eq = RangedAmmoKit.getEquippedRangedAmmoQuantity();
        if (eq > 0 && !recentEmpty) {
            return 0;
        }
        if (RangedAmmoKit.inventoryHasUsableRangedAmmo(rngLvl)) {
            String equipped = RangedAmmoKit.tryEquipRangedAmmoFromInventoryReturningDisplayName(
                    config,
                    rngLvl,
                    lastImpsRangedEmptyQuiverGameMessageMs,
                    (a, b) -> sleep(a, b),
                    false);
            if (equipped == null) {
                debugLog("Imps: ranged equip geen quiver-fill — volgende tick opnieuw");
                return antiBan.varyDelay(randomDelay(450, 850));
            }
            if (!equipped.isEmpty()) {
                lastImpsRangedEmptyQuiverGameMessageMs = 0L;
                chatLog("🏹 Imps: ammo uit inventory: " + equipped);
            }
            return antiBan.varyDelay(randomDelay(450, 850));
        }
        chatLog("[!] Imps ranged: quiver leeg, geen arrows in inv — gear prep / bank");
        preparingGear = true;
        gearPrepComplete = false;
        return handleGearPreparation(local);
    }

    /** Failsafe: bij MAGE moet staff gedragen zijn voor we aanvallen. Retourneert >0 om te wachten/equippen/naar bank. */
    private int ensureStaffEquippedBeforeAttack(IPlayer local) {
        if (getEffectiveStyle() != CombatBotConfig.ImpsCombatStyle.MAGE) return 0;
        if (isStaffEquippedForMage()) return 0;
        int clearMelee = clearEquippedMeleeWeaponBlockingMage();
        if (clearMelee > 0) return clearMelee;
        boolean staffInInv = Inventory.contains(item ->
                item != null && item.getName() != null && item.getName().toLowerCase().contains("staff"));
        if (staffInInv) {
            // Als we recent al een staff hebben ge-equipt, wacht even zodat de client equipment state eerst updatet.
            long now = System.currentTimeMillis();
            if (now - lastMageStaffEquipTimeMs < MAGE_STAFF_EQUIP_COOLDOWN_MS) {
                return antiBan.varyDelay(randomDelay(800, 1200));
            }
            equipGear(CombatBotConfig.ImpsCombatStyle.MAGE);
            chatLog("Failsafe: staff uit inventory aangedaan voor aanvallen");
            lastMageStaffEquipTimeMs = System.currentTimeMillis();
            return antiBan.varyDelay(randomDelay(800, 1200));
        }
        chatLog("[!] Geen staff gedragen of in inv - naar bank voor gear prep");
        preparingGear = true;
        gearPrepComplete = false;
        return handleGearPreparation(local);
    }

    /**
     * Check of een NPC binnen de hunting area valt.
     */
    private boolean isInHuntingArea(WorldPoint pos) {
        if (pos == null) return false;
        WorldPoint huntArea = getHuntingArea();
        int huntRadius = getHuntingRadius();
        if (pos.distanceTo(huntArea) <= huntRadius) {
            return true;
        }
        if (pos.distanceTo(EXTRA_IMP_AREA_CENTER_1) <= huntRadius) {
            return true;
        }
        return pos.distanceTo(EXTRA_IMP_AREA_CENTER_2) <= huntRadius;
    }

    /**
     * Clamp een punt naar de rand van de actieve hunting-cirkel zodat we geen out-of-area walk targets geven.
     */
    private WorldPoint clampToHuntCircle(WorldPoint point) {
        if (point == null) {
            return null;
        }
        WorldPoint center = getHuntingArea();
        int radius = Math.max(1, getHuntingRadius());
        int dx = point.getX() - center.getX();
        int dy = point.getY() - center.getY();
        double dist = Math.sqrt((double) dx * dx + (double) dy * dy);
        if (dist <= radius) {
            return point;
        }
        double scale = radius / dist;
        int clampedX = center.getX() + (int) Math.round(dx * scale);
        int clampedY = center.getY() + (int) Math.round(dy * scale);
        return new WorldPoint(clampedX, clampedY, center.getPlane());
    }

    /** Of de speler binnen de geconfigureerde hunting-cirkel staat (vereist om te jagen / imp-loot). */
    private boolean isPlayerInHuntingArea(IPlayer local) {
        return local != null && isInHuntingArea(local.getWorldLocation());
    }

    private int configuredImpsStepMin() {
        if (config.impsForceLargeSteps()) {
            return 15;
        }
        return Math.max(3, config.impsStepMinDistance());
    }

    private int configuredImpsStepMax(int stepMin) {
        if (config.impsForceLargeSteps()) {
            return 20;
        }
        return Math.max(stepMin + 1, config.impsStepMaxDistance());
    }

    private void debugAreaState(String reason, IPlayer local) {
        if (local == null || local.getWorldLocation() == null) {
            debugLog("[AREA] " + reason + " pos=unknown");
            return;
        }
        WorldPoint pos = local.getWorldLocation();
        WorldPoint center = getHuntingArea();
        int radius = getHuntingRadius();
        int dist = pos.distanceTo(center);
        long cooldownLeft = Math.max(0L, outsideTargetCooldownUntilMs - System.currentTimeMillis());
        debugLog("[AREA] " + reason + " dist=" + dist + "/" + radius + " cooldownMs=" + cooldownLeft
                + " pos=" + pos.getX() + "," + pos.getY() + "," + pos.getPlane()
                + " center=" + center.getX() + "," + center.getY() + "," + center.getPlane());
    }

    private boolean shouldTryMeleeOpeningAirStrike() {
        return config.impsMeleeOpeningAirStrike()
                && getEffectiveStyle() == CombatBotConfig.ImpsCombatStyle.MELEE;
    }

    private void debugAirStrikeOpen(String msg) {
        if (!config.impsMeleeOpeningAirStrikeDebug()) return;
        long now = System.currentTimeMillis();
        if (now - lastAirStrikeOpenDebugMs < 1200) return;
        lastAirStrikeOpenDebugMs = now;
        debugLog("[AirStrikeOpen] " + msg);
    }

    /** Probeer 1x Air Strike op deze imp voordat melee wordt ingezet. */
    private int tryOpeningAirStrikeOnImp(INPC imp) {
        if (imp == null || imp.getWorldLocation() == null) return -1;
        if (!shouldTryMeleeOpeningAirStrike()) return -1;
        IPlayer local = Players.getLocal();
        if (local == null || local.getWorldLocation() == null) return -1;
        if (imp.isDead()) return -1;
        // Alleen als target nog NIET in gevecht is.
        if (imp.getInteracting() != null) {
            debugAirStrikeOpen("Skip: imp al in gevecht");
            return -1;
        }
        int minDist = Math.max(1, config.impsMeleeOpeningAirStrikeMinDistance());
        int dist = local.getWorldLocation().distanceTo(imp.getWorldLocation());
        if (dist <= minDist) {
            debugAirStrikeOpen("Skip: afstand " + dist + " <= min " + minDist);
            return -1;
        }

        SpellBook.Standard airStrike = SpellBook.Standard.WIND_STRIKE;
        try {
            if (!airStrike.canCast()) {
                debugAirStrikeOpen("Skip: canCast=false (check runes/spellbook/level)");
                return -1;
            }
            // Gebruik SDK target-cast direct op NPC (betrouwbaarder dan spell-select + klik).
            Magic.cast(airStrike, imp);
            sleep(180, 320);

            IPlayer me = Players.getLocal();
            boolean castLikelyStarted = me != null && (me.getAnimation() != -1 || me.isInteracting());
            if (!castLikelyStarted) {
                debugAirStrikeOpen("Cast poging gedaan maar geen anim/interact");
                return -1; // niet locken op target als cast niet startte
            }

            debugAirStrikeOpen("OK: cast gestart op afstand " + dist);
            paint.setCurrentStatus("Imps: Openen met Air Strike");
            return antiBan.varyDelay(randomDelay(700, 1200));
        } catch (Exception ignored) {
            debugAirStrikeOpen("Exception tijdens cast");
            return -1;
        }
    }

    private static final int IMP_TELEPORT_SWITCH_DISTANCE = 12;

    private int handleKilling(IPlayer local) {
        // Alleen jagen binnen hunting area: buiten zone geen aanvallen (ook geen gevecht voortzetten tot buiten)
        if (!isPlayerInHuntingArea(local)) {
            walkingToHuntArea = true;
            isRoaming = false;
            paint.setCurrentStatus("Imps: Eerst naar hunting area om te jagen");
            WorldPoint huntArea = getHuntingArea();
            int huntRadius = getHuntingRadius();
            MovementHelper.walkToArea(huntArea, huntRadius);
            chatLog("Buiten hunting area - terug naar zone");
            debugAreaState("player-outside-area-return", local);
            return antiBan.varyDelay(randomDelay(450, 850));
        }

        // Als we al in gevecht zijn: gevecht afmaken, niet onderbreken voor loot
        if (local.isInteracting()) {
            if (local.getInteracting() instanceof INPC) {
                INPC current = (INPC) local.getInteracting();
                currentTarget = current;
                lastTargetPosition = current.getWorldLocation();
                WorldPoint myPos = local.getWorldLocation();

                // Imp ver weg geteleporteerd? Zoek een imp die dichter bij ons staat
                if (isImpNpc(current)) {
                    if (!isInHuntingArea(current.getWorldLocation())) {
                        outsideTargetCooldownUntilMs = System.currentTimeMillis() + OUTSIDE_TARGET_COOLDOWN_MS;
                        paint.setCurrentStatus("Imps: Target buiten area - negeren");
                        debugAreaState("drop-target-outside-area", local);
                        isRoaming = false;
                        return antiBan.varyDelay(randomDelay(450, 850));
                    }
                    int distToTarget = myPos.distanceTo(current.getWorldLocation());
                    if (distToTarget > IMP_TELEPORT_SWITCH_DISTANCE) {
                        // Stap 1: probeer een dichterbij imp BUITEN scorpion-danger-zone.
                        INPC closerImp = NPCs.getNearest(npc ->
                                npc != null && npc != current
                                        && isImpNpc(npc)
                                        && !npc.isDead()
                                        && (!npc.isInteracting()
                                            || (npc.getInteracting() != null && npc.getInteracting().equals(local)))
                                        && npc.hasAction("Attack")
                                        && isInHuntingArea(npc.getWorldLocation())
                                        && !isPointInScorpionRadius(npc.getWorldLocation())
                                        && !isNpcAttackExcluded(npc)
                                        && myPos.distanceTo(npc.getWorldLocation()) < distToTarget
                        );

                        // Stap 2: als er niks buiten de zone te vinden is, dan toch imps binnen zone aanvallen.
                        // Dit voorkomt dat "impsAvoidScorpions" de bot blokkeert omdat alle imps in de danger-zone staan.
                        if (closerImp == null && config.impsAvoidScorpions()) {
                            closerImp = NPCs.getNearest(npc ->
                                    npc != null && npc != current
                                            && isImpNpc(npc)
                                            && !npc.isDead()
                                            && (!npc.isInteracting()
                                                || (npc.getInteracting() != null && npc.getInteracting().equals(local)))
                                            && npc.hasAction("Attack")
                                            && isInHuntingArea(npc.getWorldLocation())
                                            && !isNpcAttackExcluded(npc)
                                            && myPos.distanceTo(npc.getWorldLocation()) < distToTarget
                            );
                        }
                        if (closerImp != null) {
                            int ammoDelay = enforceImpsRangedAmmoOrDelay(local);
                            if (ammoDelay > 0) {
                                return ammoDelay;
                            }
                            int delay = ensureStaffEquippedBeforeAttack(local);
                            if (delay > 0) return delay;
                            paint.setCurrentStatus("Imps: Andere imp (dichterbij)");
                            closerImp.interact("Attack");
                            currentTarget = closerImp;
                            lastTargetPosition = closerImp.getWorldLocation();
                            return antiBan.varyDelay(randomDelay(600, 1000));
                        }
                    }
                }
            }
            targetWasAlive = true;
            isRoaming = false;
            return antiBan.varyDelay(randomDelay(600, 1200));
        }

        // We zijn NIET meer in interactie - check of target dood is (kill registreren)
        if (targetWasAlive) {
            targetWasAlive = false;
            INPC last = currentTarget;
            currentTarget = null;
            lastTargetPosition = null;
            String npcName = last != null ? last.getName() : null;
            if (npcName != null) {
                paint.addKill(npcName);
            } else {
                paint.addKill();
            }
            killsSinceLastLoot++;
            deferNormalLootUntilAfterKill = false;
            return randomDelay(600, 1000);
        }

        // We zijn NIET interacting en NIET bezig - zoek een imp
        // Respecteer attack cooldown
        if (System.currentTimeMillis() - lastAttackTime < ATTACK_COOLDOWN_MS) {
            return antiBan.varyDelay(randomDelay(300, 600));
        }
        if (System.currentTimeMillis() < outsideTargetCooldownUntilMs) {
            paint.setCurrentStatus("Imps: Buiten-area target cooldown");
            debugAreaState("outside-target-cooldown-active", local);
            return antiBan.varyDelay(randomDelay(300, 650));
        }

        // Zoek dichtstbijzijnde imp ALLEEN binnen hunting area
        // Stap 1: zoek imp BUITEN scorpion danger zone.
        INPC imp = NPCs.getNearest(npc ->
                isImpNpc(npc)
                        && !npc.isDead()
                        && (!npc.isInteracting()
                            || (npc.getInteracting() != null && npc.getInteracting().equals(local)))
                        && npc.hasAction("Attack")
                        && isInHuntingArea(npc.getWorldLocation())
                        && !isPointInScorpionRadius(npc.getWorldLocation())
                        && !isNpcAttackExcluded(npc)
        );

        // Stap 2: als niets buiten de zone gevonden wordt, zoek opnieuw (binnen zone mag dan).
        if (imp == null && config.impsAvoidScorpions()) {
            imp = NPCs.getNearest(npc ->
                    isImpNpc(npc)
                            && !npc.isDead()
                            && (!npc.isInteracting()
                                || (npc.getInteracting() != null && npc.getInteracting().equals(local)))
                            && npc.hasAction("Attack")
                            && isInHuntingArea(npc.getWorldLocation())
                            && !isNpcAttackExcluded(npc)
            );
        }

        if (imp != null) {
            int openAirDelay = tryOpeningAirStrikeOnImp(imp);
            if (openAirDelay > 0) {
                currentTarget = imp;
                lastTargetPosition = imp.getWorldLocation();
                return openAirDelay;
            }
            int ammoGate = enforceImpsRangedAmmoOrDelay(local);
            if (ammoGate > 0) {
                return ammoGate;
            }
            int delay = ensureStaffEquippedBeforeAttack(local);
            if (delay > 0) return delay;
            lastAttackTime = System.currentTimeMillis();
            lastImpFoundTime = System.currentTimeMillis();
            isRoaming = false;
            currentTarget = imp;
            lastTargetPosition = imp.getWorldLocation();
            imp.interact("Attack");
            return antiBan.varyDelay(randomDelay(800, 1400));
        }

        // Geen imp gevonden - probeer scorpions als fallback (ook alleen in area)
        if (config.impsAttackScorpions()) {
            INPC scorpion = NPCs.getNearest(npc ->
                    npc.getName() != null
                            && npc.getName().equalsIgnoreCase("Scorpion")
                            && !npc.isDead()
                            && npc.hasAction("Attack")
                            && isInHuntingArea(npc.getWorldLocation())
                            && !isPointInScorpionRadius(npc.getWorldLocation())
                            && !isNpcAttackExcluded(npc)
            );
            if (scorpion != null) {
                int ammoGateSc = enforceImpsRangedAmmoOrDelay(local);
                if (ammoGateSc > 0) {
                    return ammoGateSc;
                }
                int delay = ensureStaffEquippedBeforeAttack(local);
                if (delay > 0) return delay;
                lastAttackTime = System.currentTimeMillis();
                lastImpFoundTime = System.currentTimeMillis();
                isRoaming = false;
                currentTarget = scorpion;
                lastTargetPosition = scorpion.getWorldLocation();
                scorpion.interact("Attack");
                chatLog("Geen imps, scorpion aanvallen als fallback");
                return antiBan.varyDelay(randomDelay(800, 1400));
            }
        }

        // Idle roaming - als te lang geen imp gevonden, loop rond in de zone
        int idleTimeout = config.impsIdleRoamSeconds();
        if (idleTimeout > 0 && lastImpFoundTime > 0) {
            long idleMs = System.currentTimeMillis() - lastImpFoundTime;
            if (idleMs > idleTimeout * 1000L) {
                // Als we al aan het rondlopen zijn, laat huidige movement meestal afmaken
                if (local.isMoving() && isRoaming) {
                    paint.setCurrentStatus("Imps: Rondlopen (onderweg)");
                    return antiBan.varyDelay(randomDelay(800, 1500));
                }

                WorldPoint huntArea = getHuntingArea();
                int huntRadius = getHuntingRadius();

                // Probeer meerdere random punten totdat we een pad kunnen vinden
                for (int attempt = 0; attempt < 6; attempt++) {
                    int rx = huntArea.getX() + random.nextInt(huntRadius * 2 + 1) - huntRadius;
                    int ry = huntArea.getY() + random.nextInt(huntRadius * 2 + 1) - huntRadius;
                    WorldPoint roamPoint = new WorldPoint(rx, ry, 0);

                    // Altijd binnen hunting-cirkel (vereist om te jagen; geen idle-roam buiten de zone)
                    if (roamPoint.distanceTo(huntArea) > huntRadius) {
                        continue;
                    }

                    // Nooit in scorpion-radius lopen (ook niet bij zoeken naar imps)
                    if (isPointInScorpionRadius(roamPoint)) {
                        continue;
                    }

                    // Check of er een geldig pad bestaat naar dit punt
                    boolean reachable = false;
                    try {
                        int pathDist = Movement.calculateDistance(roamPoint);
                        // Movement.calculateDistance kan -1 of een heel grote waarde geven als er geen pad is
                        if (pathDist > 0 && pathDist < huntRadius * 4) {
                            reachable = true;
                        }
                    } catch (Exception e) {
                        reachable = false;
                    }

                    if (!reachable) {
                        continue;
                    }

                    paint.setCurrentStatus("Imps: Rondlopen (geen imp gevonden)");
                    MovementHelper.walkTo(clampToHuntCircle(roamPoint));
                    isRoaming = true;
                    // Reset idle timer zodat we niet meteen weer een nieuwe roam target pakken
                    lastImpFoundTime = System.currentTimeMillis();
                    lastWalkClickTime = System.currentTimeMillis();
                    return antiBan.varyDelay(randomDelay(1500, 3000));
                }

                // Fallback: geen goed random punt gevonden - alleen naar center als die buiten scorpion-zone ligt
                if (!isPointInScorpionRadius(huntArea)) {
                    paint.setCurrentStatus("Imps: Terug naar hunting center (fallback)");
                    MovementHelper.walkToArea(huntArea, getHuntingRadius());
                } else {
                    // Center ligt in scorpion-zone: niet meteen "wegklikken" als er wel imps net erbuiten liggen.
                    INPC impOutside = NPCs.getNearest(npc ->
                            npc != null
                                    && isImpNpc(npc)
                                    && !npc.isDead()
                                    && npc.hasAction("Attack")
                                    && isInHuntingArea(npc.getWorldLocation())
                                    && !isPointInScorpionRadius(npc.getWorldLocation())
                                    && !isNpcAttackExcluded(npc)
                    );
                    if (impOutside != null) {
                        paint.setCurrentStatus("Imps: Imps buiten scorpion gevonden -> naar imp");
                        MovementHelper.walkTo(clampToHuntCircle(impOutside.getWorldLocation()));
                        isRoaming = false;
                        lastImpFoundTime = System.currentTimeMillis();
                        lastWalkClickTime = System.currentTimeMillis();
                        return antiBan.varyDelay(randomDelay(800, 1400));
                    }

                    paint.setCurrentStatus("Imps: Center in scorpion-zone -> rally point (geen imps buiten zone)");
                    MovementHelper.walkTo(clampToHuntCircle(KARAMJA_RALLY_POINT));
                }
                isRoaming = true;
                lastImpFoundTime = System.currentTimeMillis();
                lastWalkClickTime = System.currentTimeMillis();
                return antiBan.varyDelay(randomDelay(1500, 3000));
            }
        }

        // Eerste keer - start timer
        if (lastImpFoundTime == 0) {
            lastImpFoundTime = System.currentTimeMillis();
        }

        return antiBan.varyDelay(randomDelay(1000, 2000));
    }

    /** Rennen aanzetten als we door de scorpion-zone gaan (naar dock of hunt zone). */
    private void ensureRunEnabledForScorpionZone() {
        try {
            if (!Movement.isRunEnabled() && Movement.getRunEnergy() > 20) {
                Movement.toggleRun();
            }
        } catch (Exception e) {
            // Run API niet beschikbaar
        }
    }

    /**
     * Of een punt binnen de scorpion-radius ligt EN daadwerkelijk gevaarlijk is.
     * Hergebruikt de danger-logica: als je combat level hoog genoeg is (of avoid uit staat),
     * wordt de zone als veilig beschouwd en blokkeren we lopen/targets daar niet meer.
     */
    private boolean isPointInScorpionRadius(WorldPoint point) {
        return isInScorpionDangerZone(point);
    }

    /**
     * Check of een positie in een scorpion danger zone ligt (voor flee-logica).
     * Gebruikt de OSRS agro formule: je bent veilig als jouw level > NPC level * 2 + 1.
     */
    private boolean isInScorpionDangerZone(WorldPoint point) {
        if (!config.impsAvoidScorpions()) return false;
        if (point == null) return false;

        // Check combat level: als hoog genoeg, scorpions zijn niet agro
        try {
            IPlayer local = Players.getLocal();
            if (local != null) {
                int myLevel = local.getCombatLevel();
                int scorpionLevel = config.impsScorpionLevel();
                if (myLevel > (scorpionLevel * 2) + 1) {
                    return false;
                }
            }
        } catch (Exception e) {
        }

        int zoneRadius = config.impsScorpionZoneRadius();
        if (point.distanceTo(SCORPION_ZONE_1) <= zoneRadius) return true;
        if (point.distanceTo(SCORPION_ZONE_2) <= zoneRadius) return true;
        if (isInLearnedScorpionHotspot(point)) return true;
        return false;
    }

    private boolean isInLearnedScorpionHotspot(WorldPoint point) {
        IPlayer local = Players.getLocal();
        if (local == null || local.getName() == null) {
            return false;
        }
        String rsn = Text.removeTags(local.getName()).trim();
        if (rsn.isEmpty()) {
            return false;
        }
        List<ScorpionAttackMemoryStore.Entry> learned = ScorpionAttackMemoryStore.getRecentForRsn(
                config, rsn, 2, 3L * 24L * 60L * 60L * 1000L);
        if (learned.isEmpty()) {
            return false;
        }
        for (ScorpionAttackMemoryStore.Entry e : learned) {
            if (e == null) continue;
            if (e.plane != point.getPlane()) continue;
            WorldPoint hp = new WorldPoint(e.x, e.y, e.plane);
            if (point.distanceTo(hp) <= LEARNED_SCORPION_HOTSPOT_RADIUS) {
                return true;
            }
        }
        return false;
    }

    // ===================== LOOTING =====================

    /**
     * Excluded tiles staan in TileMarkerManager onder COMBAT (IMPS valt in default branch daar).
     */
    private boolean isLootOnExcludedTile(WorldPoint wp) {
        if (tileMarkerManager == null || wp == null) return false;
        return tileMarkerManager.isTileExcluded(CombatBotPlugin.ActiveSkill.COMBAT, wp);
    }

    /** NPC-footprint + ring (zelfde idee als {@link CombatHandler}). */
    private boolean isNpcAttackExcluded(INPC npc) {
        if (tileMarkerManager == null || npc == null || npc.getWorldLocation() == null) return false;
        int npcSize = 1;
        try {
            if (npc.getComposition() != null) npcSize = npc.getComposition().getSize();
        } catch (Exception ignored) {
        }
        WorldPoint sw = npc.getWorldLocation();
        for (int dx = -1; dx <= npcSize; dx++) {
            for (int dy = -1; dy <= npcSize; dy++) {
                WorldPoint check = new WorldPoint(sw.getX() + dx, sw.getY() + dy, sw.getPlane());
                if (tileMarkerManager.isTileExcluded(CombatBotPlugin.ActiveSkill.COMBAT, check)) return true;
            }
        }
        return false;
    }

    /** Loot op excluded tile of in scorpion-radius telt niet mee en wordt niet gepakt. */
    private boolean isImpLootGroundItemOk(ITileItem item) {
        if (item == null || item.getWorldLocation() == null) return false;
        if (isPointInScorpionRadius(item.getWorldLocation())) return false;
        if (isLootOnExcludedTile(item.getWorldLocation())) return false;
        return true;
    }

    private boolean hasImpLootNearby() {
        return TileItems.getNearest(item ->
                item.getName() != null && isImpLoot(item.getName()) && isImpLootGroundItemOk(item)
        ) != null;
    }

    /** Check of er speciale loot items op de grond liggen (hoogste prioriteit). */
    private boolean hasSpecialLootNearby() {
        String[] specials = getSpecialLootItems();
        if (specials.length == 0) return false;
        return TileItems.getNearest(item -> {
            if (item.getName() == null || !isImpLootGroundItemOk(item)) return false;
            for (String s : specials) {
                if (s.equalsIgnoreCase(item.getName())) return true;
            }
            return false;
        }) != null;
    }

    private static final String[] BONE_NAMES_IMPS = {
            "Bones", "Big bones", "Babydragon bones", "Dragon bones", "Dagannoth bones",
            "Wyvern bones", "Lava dragon bones", "Superior dragon bones", "Wyrm bones",
            "Drake bones", "Hydra bones", "Jogre bones", "Zogre bones", "Shaikahan bones",
            "Fayrg bones", "Raurg bones", "Ourg bones"
    };

    private boolean isImpLoot(String name) {
        if (name == null) return false;
        if (isWizardHatLootName(name)) {
            return !hasWizardHat();
        }
        // Check imp-specifieke loot lijst (dynamisch uit config)
        for (String loot : getLootItems()) {
            if (loot.equalsIgnoreCase(name)) return true;
        }
        // Check speciale loot items (altijd oppakken)
        for (String special : getSpecialLootItems()) {
            if (special.equalsIgnoreCase(name)) return true;
        }
        // Check bones & ashes
        if (config.lootBonesAndAshes()) {
            if ("Fiendish ashes".equalsIgnoreCase(name)) return true;
            for (String bone : BONE_NAMES_IMPS) {
                if (bone.equalsIgnoreCase(name)) return true;
            }
        }
        return false;
    }

    private boolean hasWizardHat() {
        return Inventory.contains(item ->
                item != null && item.getName() != null && isWizardHatLootName(item.getName()))
                || isWizardHatEquipped();
    }

    private boolean isWizardHatEquipped() {
        return Equipment.contains(item ->
                item != null && item.getName() != null && isWizardHatLootName(item.getName()));
    }

    private void tryFinishWizardHatEquip() {
        if (!pendingWizardHatEquip) return;
        if (isWizardHatEquipped()) {
            pendingWizardHatEquip = false;
            return;
        }
        long now = System.currentTimeMillis();
        if (now - pendingWizardHatEquipSinceMs > WIZARD_HAT_EQUIP_TIMEOUT_MS) {
            pendingWizardHatEquip = false;
            return;
        }
        if (!Inventory.contains(item ->
                item != null && item.getName() != null && isWizardHatLootName(item.getName()))) {
            return;
        }
        if (now - lastWizardHatEquipAttemptMs < WIZARD_HAT_EQUIP_RETRY_MS) return;
        lastWizardHatEquipAttemptMs = now;
        equipWizardHatIfNeeded();
    }

    private void equipWizardHatIfNeeded() {
        if (isWizardHatEquipped()) return;
        IInventoryItem hat = Inventory.getFirst(item ->
                item != null && item.getName() != null && isWizardHatLootName(item.getName()));
        if (hat == null) return;
        String action = hat.hasAction("Wear") ? "Wear" : (hat.hasAction("Wield") ? "Wield" : "Wear");
        InventoryActionHelper.interact(config, hat, action);
        debugLog((hat.getName() != null ? hat.getName() : "Wizard hat") + " equipped: " + action);
        sleep(600, 1000);
    }

    private boolean isLootAllowed() {
        if (!config.lootDelayEnabled()) return true;

        int killsRequired = config.lootDelayKills();
        if (killsRequired <= 0) return true;

        // Enige criterium: genoeg kills sinds laatste loot
        return killsSinceLastLoot >= killsRequired;
    }

    private int handleLooting() {
        boolean forceSpecial = System.currentTimeMillis() < prioritizeSpecialLootUntil;

        IPlayer local = Players.getLocal();

        tryFinishWizardHatEquip();

        // Imp-loot alleen binnen hunting area (zelfde regel als jagen)
        if (local != null && !isPlayerInHuntingArea(local)) {
            walkingToHuntArea = true;
            pickingUpLoot = false;
            prioritizeSpecialLootUntil = 0;
            if (local.isInteracting()) {
                MovementHelper.walkTo(local.getWorldLocation());
            }
            paint.setCurrentStatus("Imps: Eerst naar hunting area (geen loot buiten zone)");
            return antiBan.varyDelay(randomDelay(400, 800));
        }

        // Voorkom te snel achter elkaar klikken op loot
        if (!forceSpecial && System.currentTimeMillis() - lastLootPickupTime < LOOT_PICKUP_COOLDOWN_MS) {
            return antiBan.varyDelay(randomDelay(400, 800));
        }

        // Check of speler nog bezig is met vorige pickup
        if (!forceSpecial && local != null && (local.isMoving() || local.getAnimation() != -1)) {
            return antiBan.varyDelay(randomDelay(300, 600));
        }

        ITileItem loot = null;
        if (forceSpecial) {
            String[] specials = getSpecialLootItems();
            loot = TileItems.getNearest(item -> {
                if (item == null || item.getName() == null || !isImpLootGroundItemOk(item)) return false;
                for (String s : specials) {
                    if (s.equalsIgnoreCase(item.getName())) return true;
                }
                return false;
            });
            if (loot == null) {
                prioritizeSpecialLootUntil = 0;
            }
        }
        if (loot == null) {
            loot = TileItems.getNearest(item ->
                    item.getName() != null && isImpLoot(item.getName()) && isImpLootGroundItemOk(item)
            );
        }

        if (loot != null && !Inventory.isFull()) {
            String name = loot.getName();
            int qty = loot.getQuantity();
            int ha = loot.getHaPrice() * qty;
            if (ha > 0) {
                impsTripLootValueAccumulator = Math.min(Integer.MAX_VALUE, impsTripLootValueAccumulator + ha);
            }
            loot.pickup();
            lastLootPickupTime = System.currentTimeMillis();
            if (questProgressConfigManager != null && name != null && name.equalsIgnoreCase("Hammer")) {
                String rsnQ = tryLocalRsnForQuest();
                if (rsnQ != null) {
                    AccountQuestProgressStore.setHammerFromImp(questProgressConfigManager, config, rsnQ);
                    paint.setLastAntiBanAction("Quest: Hammer geloot — opgeslagen per account");
                }
            }
            if (name != null && isWizardHatLootName(name)) {
                pendingWizardHatEquip = true;
                pendingWizardHatEquipSinceMs = System.currentTimeMillis();
            }
            if (name != null) {
                paint.addLoot(name, qty, ha);
            } else {
                paint.addLoot(ha);
            }

            // Reset loot delay counters
            killsSinceLastLoot = 0;
            lootDelayUntil = 0; // tijd-based delay niet meer gebruiken

            return antiBan.varyDelay(randomDelay(1400, 2200));
        }

        // Inv vol + er ligt nog imp-loot wat we willen → probeer 1 hap te eten om plek te maken.
        if (loot != null && Inventory.isFull()) {
            int eatDelay = EatForLootSpaceHelper.tryEatForSpace(
                    config, false, 6, null, this::debugLog);
            if (eatDelay > 0) {
                paint.setCurrentStatus("🍗 Eet voor loot-ruimte");
                lastLootPickupTime = System.currentTimeMillis();
                return antiBan.varyDelay(eatDelay);
            }
        }

        // Geen loot meer gevonden - klaar
        pickingUpLoot = false;
        prioritizeSpecialLootUntil = 0;
        return 600;
    }

    // ===================== ASHES SCATTER =====================

    private boolean shouldScatterAshesNow() {
        int count = getFiendishAshesCount();
        if (count == 0) {
            scatteringBatchStarted = false;
            scatterAshesRemainingThisBatch = 0;
            nextAshScatterThreshold = 0;
            return false;
        }

        if (nextAshScatterThreshold <= 0) {
            nextAshScatterThreshold = randomDelay(1, 10);
        }

        boolean forceBecauseInventoryFull = Inventory.isFull();
        boolean thresholdReached = count >= nextAshScatterThreshold;

        if ((forceBecauseInventoryFull || thresholdReached) && !scatteringBatchStarted) {
            int batchMax = Math.min(count, 10);
            scatterAshesRemainingThisBatch = Math.max(1, randomDelay(1, Math.max(1, batchMax)));
            scatteringBatchStarted = true;
        }

        return scatteringBatchStarted && scatterAshesRemainingThisBatch > 0;
    }

    private int getFiendishAshesCount() {
        int count = 0;
        for (IInventoryItem item : Inventory.getAll()) {
            if (item != null && "Fiendish ashes".equalsIgnoreCase(item.getName()))
                count += item.getQuantity();
        }
        return count;
    }

    /** Menselijk tempo: min/max ms tussen elke scatter actie. Iets sneller maar nog variabel. */
    private static final int SCATTER_DELAY_MIN_MS = 900;
    private static final int SCATTER_DELAY_MAX_MS = 2100;

    private int handleScatterAshes() {
        IInventoryItem ashes = Inventory.getFirst("Fiendish ashes");
        if (ashes != null) {
            InventoryActionHelper.interact(config, ashes, "Scatter");
            if (scatterAshesRemainingThisBatch > 0) {
                scatterAshesRemainingThisBatch--;
            }

            // Bij volle inventory: stop meteen zodra er 1 slot vrij komt en evalueer opnieuw.
            if (!Inventory.isFull() || scatterAshesRemainingThisBatch <= 0) {
                scatteringBatchStarted = false;
                scatterAshesRemainingThisBatch = 0;
                nextAshScatterThreshold = randomDelay(1, 10);
            }
            return antiBan.varyDelay(randomDelay(SCATTER_DELAY_MIN_MS, SCATTER_DELAY_MAX_MS));
        }
        scatteringBatchStarted = false;
        scatterAshesRemainingThisBatch = 0;
        nextAshScatterThreshold = 0;
        return 800;
    }

    // ===================== BANKING TRIP =====================

    /**
     * Bepaalt of de bot een RESTOCK nodig heeft (volwaardige bank) of alleen een loot dump (deposit box).
     *
     * Restock nodig als:
     * - RANGED: minder dan 100 arrows totaal
     * - MAGE: onder trip-minima (cast budget × runes per cast, min. 50) voor catalyst/elemental/extra Air
     * - Coins onder het minimum ({@link CombatBotConfig#impsMinCoins()} of 30 tijdens starter-bridge)
     *
     * @return true als de bot naar een volwaardige bank moet voor restock
     */
    private boolean checkIfRestockNeeded() {
        // Coins check: altijd restock als te weinig coins
        if (getCoinCount() < impsRuntimeMinCoins()) {
            debugLog("checkIfRestockNeeded: coins " + getCoinCount() + " < min " + impsRuntimeMinCoins());
            return true;
        }

        if (getEffectiveStyle() == CombatBotConfig.ImpsCombatStyle.MELEE && config.impsMeleeOpeningAirStrike()
                && !starterSkillBridge) {
            if (getItemQuantity("Air rune") < 10 || getItemQuantity("Mind rune") < 10) {
                debugLog("checkIfRestockNeeded: melee opener runes laag (air/mind)");
                return true;
            }
        }

        // Imps MAGE in config: mage-voorraad checken, behalve tijdens Tier-2 melee-fallback (anders GE/bank-loop).
        CombatBotConfig.ImpsCombatStyle style = getEffectiveStyle();
        if (impsConfigMageSuppliesRestockActive()) {
            style = CombatBotConfig.ImpsCombatStyle.MAGE;
        }
        switch (style) {
            case RANGED: {
                if (!hasRangedAmmoSufficientForTrip()) {
                    debugLog("checkIfRestockNeeded: ranged ammo < " + GEAR_PREP_MIN_ARROWS
                            + " (effectief=" + getImpsEffectiveArrowCount()
                            + ", pref=" + RangedAmmoPreference.preferredItemName(config) + ")");
                    return true;
                }
                break;
            }
            case MAGE: {
                CombatBotConfig.ImpsMageSpell spell = getActiveMageSpell();
                int catalystCount = getItemQuantity(spell.getCatalystRune());
                int minCat = minCatalystRunesForTrip(spell);
                if (isBelowCatalystTripMinimum(catalystCount, spell)) {
                    debugLog("checkIfRestockNeeded: catalyst " + spell.getCatalystRune() + " " + catalystCount + " < " + minCat);
                    return true;
                }

                // Check elemental rune (tenzij staff het dekt)
                String elementalRune = spell.getElementalRune();
                String element = elementalRune.toLowerCase().replace(" rune", "");
                boolean staffCoversElemental = hasStaffWithElement(element);
                int minElem = minElementalRunesForTrip(spell);
                if (!staffCoversElemental && getItemQuantity(elementalRune) < minElem) {
                    debugLog("checkIfRestockNeeded: elemental " + elementalRune + " < " + minElem + " (staff dekt niet)");
                    return true;
                }

                // Extra Air (bv. Fire Strike + fire staff) — niet Wind Strike (daar is Air elemental)
                if (spell.needsAirRune()) {
                    int strictAir = requiredInventoryAirForAccountAutoFireSetup();
                    if (strictAir >= 0) {
                        if (!hasStaffWithElement("fire") || getItemQuantity("Air rune") < strictAir) {
                            debugLog("checkIfRestockNeeded: auto-Fire loadout: fire staff of Air inv < " + strictAir);
                            return true;
                        }
                    } else {
                        boolean staffCoversAir = hasStaffWithElement("air");
                        int airMin = requiredAirRunesForMageDeparture();
                        if (!staffCoversAir && getItemQuantity("Air rune") < airMin) {
                            debugLog("checkIfRestockNeeded: Air rune < " + airMin + " (staff dekt niet, departure/paired)");
                            return true;
                        }
                    }
                }
                break;
            }
            case MELEE:
            default:
                // Melee heeft geen consumables -> nooit restock nodig op basis van supplies
                break;
        }

        return false;
    }

    /** Tel arrows: inventory-stacks + ammo in quiver (Equipment-telling dekt sommige clients niet). */
    private int getTotalArrows() {
        String[] arrowTypes = {"Bronze arrow", "Iron arrow", "Steel arrow", "Mithril arrow", "Adamant arrow", "Rune arrow"};
        int fromInv = 0;
        for (String arrow : arrowTypes) {
            fromInv += getInventoryQuantityIncludingStacks(arrow);
        }
        int fromQuiver = 0;
        try {
            fromQuiver = RangedAmmoKit.getEquippedRangedAmmoQuantity();
        } catch (Throwable ignored) {
        }
        return fromInv + fromQuiver;
    }

    /**
     * Law rune start vaak een nieuwe stack; bij 0 vrije slots faalt withdraw stil.
     * Bunker overtollige elementaire runes (niet Law) of arrows boven trip-minimum.
     */
    private int tryMakeFreeSlotForTeleportLawRunes() {
        if (!Bank.isOpen()) {
            return -1;
        }
        if (Inventory.getFreeSlots() >= 1) {
            return -1;
        }
        if (getItemQuantity("Law rune") > 0) {
            return -1;
        }
        String[] trimRunes = {"Mind rune", "Air rune", "Water rune", "Earth rune", "Fire rune", "Chaos rune"};
        for (String name : trimRunes) {
            int inv = getInventoryQuantityIncludingStacks(name);
            if (inv > 150) {
                int dep = inv - 100;
                Bank.deposit(name, dep);
                chatLog("[gear-prep] " + name + " deels gebunkerd (-" + dep + ") voor Law-rune slot");
                return antiBan.varyDelay(randomDelay(500, 900));
            }
        }
        int totalArr = getTotalArrows();
        if (totalArr > 160) {
            String[] arrowTypes = {"Bronze arrow", "Iron arrow", "Steel arrow", "Mithril arrow", "Adamant arrow", "Rune arrow"};
            for (String arrow : arrowTypes) {
                int inv = getInventoryQuantityIncludingStacks(arrow);
                if (inv > 120) {
                    int dep = Math.min(inv - 80, totalArr - 100);
                    if (dep > 0) {
                        Bank.deposit(arrow, dep);
                        chatLog("[gear-prep] " + arrow + " deels gebunkerd (-" + dep + ") voor Law-rune slot");
                        return antiBan.varyDelay(randomDelay(500, 900));
                    }
                }
            }
        }
        return -1;
    }

    private boolean shouldStartBanking() {
        // Count stacks/slots die NIET in keep list staan en GEEN gear zijn.
        // Let op: quantity (bijv. 30 runes) mag niet als 30 "loot items" tellen.
        int depositableStacks = 0;
        for (IInventoryItem item : Inventory.getAll()) {
            if (item != null && item.getName() != null && !shouldKeepItem(item)) {
                depositableStacks++;
            }
        }
        // Alleen banken als er daadwerkelijk depositable items zijn
        if (depositableStacks == 0) return false;
        if (hasForeignItemsForImps()) return true;

        boolean onKaramjaStarterFirst = false;
        try {
            IPlayer lp = Players.getLocal();
            if (starterSkillBridge && starterBridgeFirstGeCyclePending && lp != null
                    && lp.getWorldLocation() != null && isOnKaramja(lp.getWorldLocation())) {
                onKaramjaStarterFirst = true;
            }
        } catch (Throwable ignored) {
        }

        // Eerste Karamja-ronde (starter-bridge): géén vroege banking alleen omdat boottarief op is — eerst bijna volle inv.
        if (onKaramjaStarterFirst && getCoinCount() < BOAT_FARE && hasDepositItems()) {
            return starterBridgeInventoryStuffedForGeTrip();
        }

        boolean fullEnough = Inventory.isFull() || depositableStacks >= config.impsBankThreshold();
        if (!fullEnough) {
            return false;
        }
        // Zelfde ronde: anders wachten tot inv bijna vol voor eerste GE-trip
        if (onKaramjaStarterFirst && !starterBridgeInventoryStuffedForGeTrip()) {
            return false;
        }
        return true;
    }

    /** Vreemde items = niet keep, en ook geen imp-loot/special loot voor deze skill. */
    private boolean hasForeignItemsForImps() {
        String[] specials = getSpecialLootItems();
        return Inventory.getFirst(item -> {
            if (item == null || item.getName() == null) return false;
            String name = item.getName();
            if (shouldKeepItem(item)) return false;
            if (isImpLoot(name)) return false;
            for (String s : specials) {
                if (s != null && s.equalsIgnoreCase(name)) return false;
            }
            // Food laten we ook toe tijdens imps trips.
            if (item.hasAction("Eat") || item.hasAction("Drink")) return false;
            return true;
        }) != null;
    }

    public boolean hasDepositItems() {
        for (IInventoryItem item : Inventory.getAll()) {
            if (item != null && item.getName() != null && !shouldKeepItem(item)) {
                return true;
            }
        }
        return false;
    }

    private void addGearPrepKeepName(List<String> keep, String name) {
        if (name == null || name.isEmpty()) {
            return;
        }
        for (String k : keep) {
            if (k != null && k.equalsIgnoreCase(name)) {
                return;
            }
        }
        keep.add(name);
    }

    /** Gear prep: houd ALLEEN strikt benodigde items; de rest moet naar bank. */
    private List<String> getGearPrepKeepNames() {
        List<String> keep = new ArrayList<>(getFullKeepList());
        addGearPrepKeepName(keep, "Coins");
        CombatBotConfig.ImpsCombatStyle prepStyle = getEffectiveStyle();
        if (!impsMeleeSkipsArmour(prepStyle)) {
            for (String armourName : StyleArmourBankHelper.armourItemNamesForStyle(prepStyle)) {
                addGearPrepKeepName(keep, armourName);
            }
        }
        if (prepStyle == CombatBotConfig.ImpsCombatStyle.RANGED) {
            for (String bow : RANGED_BOW_BEST_FIRST) {
                addGearPrepKeepName(keep, bow);
            }
        }
        if (prepStyle == CombatBotConfig.ImpsCombatStyle.MAGE) {
            CombatBotConfig.ImpsMageSpell spell = getActiveMageSpell();
            if (spell != null) {
                String pref = spell.getPreferredStaff();
                if (pref != null) {
                    addGearPrepKeepName(keep, pref.trim());
                }
                String el = spell.getElementalRune();
                if (el != null) {
                    el = el.toLowerCase().replace(" rune", "");
                    String[] staves = el.equals("air") ? new String[]{"Staff of air", "Mystic air staff", "Air staff"}
                            : el.equals("fire") ? new String[]{"Staff of fire", "Mystic fire staff", "Fire staff"}
                            : el.equals("water") ? new String[]{"Staff of water", "Mystic water staff", "Water staff"}
                            : el.equals("earth") ? new String[]{"Staff of earth", "Mystic earth staff", "Earth staff"}
                            : new String[0];
                    for (String st : staves) {
                        addGearPrepKeepName(keep, st);
                    }
                }
            }
        }
        return keep;
    }

    /** Gear prep variant: check op basis van strikte keep-list i.p.v. algemene shouldKeepItem(). */
    private boolean hasGearPrepDepositItems() {
        List<String> keep = getGearPrepKeepNames();
        for (IInventoryItem item : Inventory.getAll()) {
            if (item == null || item.getName() == null) continue;
            String name = item.getName();
            boolean allowed = false;
            for (String k : keep) {
                if (k.equalsIgnoreCase(name)) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed && shouldKeepItem(item)) {
                allowed = true;
            }
            if (!allowed) return true;
        }
        return false;
    }

    /**
     * Check of een item behouden moet worden (niet deponeren).
     * Behoudt: coins, ammo/runes, en alle equipped gear types.
     */
    private boolean shouldKeepItem(IInventoryItem item) {
        if (item == null || item.getName() == null) return false;
        if (item.getId() == GENIE_LAMP_ITEM_ID) return true;
        String name = item.getName();

        String lower = name.toLowerCase();
        boolean isMeleeWeapon = isCombatMeleeWeaponName(lower);
        if (isMeleeWeapon && !shouldRetainMeleeWeaponsDuringGearPrep()) {
            return false;
        }

        // Equipped gear behouden, behalve melee-wapen tijdens strikte mage prep / imps-melee armor.
        if (Equipment.contains(eq -> eq != null && eq.getName() != null && eq.getName().equalsIgnoreCase(name))) {
            if (isMeleeWeapon && isStrictMageGearPrep()) {
                return false;
            }
            if (impsMeleeSkipsArmour()
                    && StyleArmourBankHelper.isMeleeStyleArmourItemName(name)) {
                return false;
            }
            return true;
        }

        // Check tegen dynamische keep list (inclusief spell-specifieke runes)
        for (String keep : getFullKeepList()) {
            if (keep.equalsIgnoreCase(name)) return true;
        }

        // Houd alleen wapens die bij huidige/fallback Imps combat style passen.
        CombatBotConfig.ImpsCombatStyle style = getEffectiveStyle();
        CombatBotConfig.ImpsCombatStyle fallback = config.impsFallbackStyle();
        boolean keepMeleeWeapon = shouldRetainMeleeWeaponsDuringGearPrep();
        boolean keepRangedWeapon = style == CombatBotConfig.ImpsCombatStyle.RANGED || fallback == CombatBotConfig.ImpsCombatStyle.RANGED;
        boolean keepMageWeapon = style == CombatBotConfig.ImpsCombatStyle.MAGE || fallback == CombatBotConfig.ImpsCombatStyle.MAGE;

        if (isMeleeWeapon && keepMeleeWeapon) return true;
        if (isRangedBowItemName(name) && keepRangedWeapon) return true;
        if ((lower.contains("staff") || lower.contains("wand")) && keepMageWeapon) return true;

        if (impsMeleeSkipsArmour() && StyleArmourBankHelper.isMeleeStyleArmourItemName(name)) {
            return false;
        }

        // Armor/amulet/cape etc. mag wel blijven (niet imps-melee armor — zie hierboven).
        if (lower.contains("shield") || lower.contains("defender")
                || lower.contains("helm") || lower.contains("hat") || lower.contains("hood")
                || lower.contains("plate") || lower.contains("chain") || lower.contains("robe")
                || lower.contains("legs") || lower.contains("skirt") || lower.contains("chaps")
                || (lower.contains("boots") && !StyleArmourBankHelper.isCosmeticOrNonCombatFootwear(name))
                || lower.contains("gloves") || lower.contains("vambraces")
                || lower.contains("cape") || lower.contains("cloak") || lower.contains("amulet")
                || lower.contains("necklace") || lower.contains("ring") || lower.contains("bracelet")
                || lower.contains("quiver") || lower.contains("body")) {
            return true;
        }

        return false;
    }

    // Deposit box widget IDs - OSRS deposit box interface
    private static final int DEPOSIT_BOX_GROUP = 192;
    private int depositBoxFailCount = 0;
    private static final int MAX_DEPOSIT_BOX_FAILS = 5;

    private int handleBankingTrip(IPlayer local) {
        // Loot-dump boot: nooit gear-prep Home-tele-fallback state meenemen
        gearPrepKaramjaUseHomeTele = false;
        gearPrepKaramjaBoatFailStreak = 0;

        WorldPoint myPos = local.getWorldLocation();
        boolean onKaramja = isOnKaramja(myPos);

        debugLog("handleBankingTrip() pos=" + myPos.getX() + "," + myPos.getY() + " karamja=" + onKaramja);

        if (onKaramja && starterSkillBridge && starterBridgeFirstGeCyclePending
                && getCoinCount() < BOAT_FARE && hasDepositItems()
                && starterBridgeInventoryStuffedForGeTrip()) {
            isBankingTrip = false;
            isRestocking = false;
            starterBridgeHomeTeleportToGePending = true;
            chatLog("[Starter] Banking-trip: inv bijna vol + geen gp voor terugboot — Home teleport → GE i.p.v. boot");
            return handleStarterBridgeHomeTeleportThenGe(local);
        }

        // Als deposit box open is -> deponeer via widget
        if (DepositBox.isOpen()) {
            debugLog("DepositBox is OPEN");
            if (!hasDepositItems()) {
                debugLog("Geen deposit items meer, sluiten");
                DepositBox.close();
                depositBoxFailCount = 0;
                quantityAllSelected = false;
                return finishBankingTrip("deposit-box");
            }
            paint.setCurrentStatus("Imps: Deponeren...");
            return handleDepositBoxDeposit();
        }

        // Als bank open is -> restock of loot dump via Bank API
        if (Bank.isOpen()) {
            debugLog("Bank is OPEN");
            if (isRestocking) {
                // RESTOCK MODE: deposit loot, dan withdraw supplies
                return handleBankRestock();
            }
            // LOOT DUMP via bank (fallback als deposit box faalde)
            if (!hasDepositItems()) {
                return finishBankingTrip("bank");
            }
            paint.setCurrentStatus("Imps: Deponeren (bank)...");
            return handleBankDeposit();
        }

        if (onKaramja) {
            return travelFromKaramjaToPortSarim(local, myPos, true);
        }

        // ===================== SCENARIO A: RESTOCK -> Volwaardige bank =====================
        if (isRestocking) {
            debugLog("RESTOCK MODE: ga naar volwaardige bank (negeer deposit box)");
            long now = System.currentTimeMillis();
            boolean canReclick = (now - lastWalkClickTime) > WALK_RECLICK_INTERVAL_MS;
            if (!canReclick && local.isMoving()) {
                paint.setCurrentStatus("Imps: -> Bank (restock)");
                return antiBan.varyDelay(randomDelay(800, 1400));
            }
            lastWalkClickTime = now;
            return walkToAndOpenBank(myPos);
        }

        // ===================== SCENARIO B: LOOT DUMP -> Deposit box =====================
        debugLog("LOOT DUMP MODE: ga naar deposit box");

        // Te veel deposit box fails -> fallback naar volwaardige bank
        if (depositBoxFailCount >= MAX_DEPOSIT_BOX_FAILS) {
            debugLog("Deposit box gefaald " + depositBoxFailCount + "x, fallback naar volwaardige bank");
            long now = System.currentTimeMillis();
            boolean canReclick = (now - lastWalkClickTime) > WALK_RECLICK_INTERVAL_MS;
            if (!canReclick && local.isMoving()) {
                paint.setCurrentStatus("Imps: -> Bank (fallback)");
                return antiBan.varyDelay(randomDelay(800, 1400));
            }
            lastWalkClickTime = now;
            return walkToAndOpenBank(myPos);
        }

        // Zoek deposit box in de buurt
        ITileObject nearbyDepositBox = TileObjects.getNearest(obj -> obj.getId() == 50902);

        if (nearbyDepositBox != null) {
            WorldPoint boxPos = nearbyDepositBox.getWorldLocation();
            int distToBox = myPos.distanceTo(boxPos);
            debugLog("Deposit box gevonden op " + boxPos.getX() + "," + boxPos.getY() + " dist=" + distToBox);

            if (distToBox > 5) {
                long now = System.currentTimeMillis();
                boolean canReclick = (now - lastWalkClickTime) > WALK_RECLICK_INTERVAL_MS;
                if (!canReclick && local.isMoving()) {
                    paint.setCurrentStatus("Imps: -> Deposit box (" + distToBox + " tiles)");
                    return antiBan.varyDelay(randomDelay(800, 1400));
                }
                paint.setCurrentStatus("Imps: -> Deposit box (" + distToBox + " tiles)");
                MovementHelper.walkTo(boxPos);
                lastWalkClickTime = now;
                return antiBan.varyDelay(randomDelay(1500, 2500));
            }

            paint.setCurrentStatus("Imps: Deposit box openen...");
            String[] openActions = {"Deposit", "Open", "Use"};
            for (String action : openActions) {
                if (nearbyDepositBox.hasAction(action)) {
                    nearbyDepositBox.interact(action);
                        return antiBan.varyDelay(randomDelay(900, 1500));
                }
            }
            nearbyDepositBox.interact(0);
                return antiBan.varyDelay(randomDelay(900, 1500));
        }

        // Deposit box niet zichtbaar - loop naar bekende positie
        int distToBox = myPos.distanceTo(PORTSARIM_DEPOSIT_BOX);
        if (distToBox > 3) {
            long now = System.currentTimeMillis();
            boolean canReclick = (now - lastWalkClickTime) > WALK_RECLICK_INTERVAL_MS;
            if (!canReclick && local.isMoving()) {
                paint.setCurrentStatus("Imps: -> Deposit box (" + distToBox + " tiles)");
                return antiBan.varyDelay(randomDelay(800, 1400));
            }
            paint.setCurrentStatus("Imps: -> Deposit box (" + distToBox + " tiles)");
            MovementHelper.walkTo(PORTSARIM_DEPOSIT_BOX);
            lastWalkClickTime = now;
            return antiBan.varyDelay(randomDelay(1500, 2500));
        }

        paint.setCurrentStatus("Imps: Zoeken deposit box...");
        return antiBan.varyDelay(randomDelay(1200, 1800));
    }

    private String[] amuletOrderForStyle(CombatBotConfig.ImpsCombatStyle style) {
        if (style == CombatBotConfig.ImpsCombatStyle.MAGE) {
            return new String[]{"Amulet of magic", "Amulet of power", "Amulet of accuracy", "Amulet of defence"};
        }
        if (style == CombatBotConfig.ImpsCombatStyle.MELEE) {
            return new String[]{"Amulet of strength", "Amulet of power", "Amulet of accuracy", "Amulet of defence"};
        }
        return new String[]{"Amulet of power", "Amulet of accuracy", "Amulet of defence"};
    }

    private String bestInventoryAmuletForStyle(CombatBotConfig.ImpsCombatStyle style) {
        for (String amulet : amuletOrderForStyle(style)) {
            if (Inventory.contains(item -> item != null && item.getName() != null
                    && item.getName().equalsIgnoreCase(amulet))) {
                return amulet;
            }
        }
        return null;
    }

    private String bestBankAmuletForStyle(CombatBotConfig.ImpsCombatStyle style) {
        for (String amulet : amuletOrderForStyle(style)) {
            if (Bank.contains(amulet)) {
                return amulet;
            }
        }
        return null;
    }

    /**
     * Deposit via de deposit box widget.
     * Pakt de parent widget (group 192), zoekt children, en klikt op items
     * die niet in de keep-list staan.
     */
    private int handleDepositBoxDeposit() {
        debugLog("handleDepositBoxDeposit() gestart");

        // STAP 1: Zorg dat quantity mode op "All" staat
        // Check of "All" al geselecteerd is via widget 192,4 (quantity All button)
        if (!quantityAllSelected) {
            debugLog("Quantity All selecteren...");
            DepositBox.selectQuantityAll();
            quantityAllSelected = true;
            // Nog iets sneller: kortere wacht tot UI updated
            return antiBan.varyDelay(randomDelay(40, 80));
        }

        // STAP 2: Nu gewoon op items klikken (quantity All staat al aan)
        // Gebruik Inventory items en zoek ze in de deposit box widget
        for (IInventoryItem invItem : Inventory.getAll()) {
            if (invItem == null || invItem.getName() == null) continue;
            String itemName = invItem.getName();

            // Skip items die we moeten houden
            if (shouldKeepItemByName(itemName)) {
                debugLog("KEEP: " + itemName);
                continue;
            }

            // Gevonden: item dat gedeponeerd moet worden - klik erop in de deposit box
            debugLog("DEPOSIT: " + itemName);

            // Zoek het item in de deposit box widget container
            IWidget itemContainer = findDepositBoxContainer();
            if (itemContainer != null) {
                IWidget[] slots = getContainerChildren(itemContainer);
                if (slots != null) {
                    for (IWidget slot : slots) {
                        if (slot == null) continue;
                        int itemId = slot.getItemId();
                        if (itemId <= 0 || itemId == 6512) continue;

                        String slotName = slot.getName();
                        if (slotName != null) {
                            slotName = slotName.replaceAll("<[^>]*>", "").trim();
                        }

                        if (slotName != null && slotName.equalsIgnoreCase(itemName)) {
                            // Gewoon klikken - quantity All staat al aan
                            slot.interact(0);
                            depositBoxFailCount = 0;
                            // Sneller maar nog met variatie
                            return antiBan.varyDelay(randomDelay(50, 110));
                        }
                    }
                }
            }

            // Fallback: als widget scan niet lukt, probeer interact op inventory item zelf
            debugLog("Widget scan mislukt, fallback interact op inv item: " + itemName);
            invItem.interact("Deposit-All");
            depositBoxFailCount = 0;
            return antiBan.varyDelay(randomDelay(50, 110));
        }

        debugLog("Geen depositable items meer - inventaris klaar");
        quantityAllSelected = false; // Reset voor volgende keer
        depositBoxFailCount = 0;
        return antiBan.varyDelay(randomDelay(50, 110));
    }

    /**
     * Zoek de item container widget binnen de deposit box (group 192).
     */
    private IWidget findDepositBoxContainer() {
        for (int childId = 0; childId <= 30; childId++) {
            IWidget parent = Widgets.get(DEPOSIT_BOX_GROUP, childId);
            if (parent == null) continue;
            IWidget[] children = getContainerChildren(parent);
            if (children != null && children.length >= 28) {
                return parent;
            }
        }
        return null;
    }

    /**
     * Haal children op van een widget container (probeer meerdere methoden).
     */
    private IWidget[] getContainerChildren(IWidget parent) {
        IWidget[] children = parent.getDynamicChildren();
        if (children != null && children.length > 0) return children;
        children = parent.getChildren();
        if (children != null && children.length > 0) return children;
        children = parent.getNestedChildren();
        if (children != null && children.length > 0) return children;
        return null;
    }

    /**
     * Gemeenschappelijke afronding van een banking trip (deposit box of bank).
     * Telt de trip, checkt GE verkoop, reset state.
     */
    private int finishBankingTrip(String source) {
        debugLog("finishBankingTrip(" + source + ")");

        if (!preparingGear) {
            bankTripCount++;
            if (impsTripLootValueAccumulator > 0) {
                String rsn = null;
                try {
                    IPlayer p = Players.getLocal();
                    rsn = p != null ? p.getName() : null;
                } catch (Exception ignored) {
                }
                if (rsn != null && !rsn.trim().isEmpty()) {
                    AccountStateJsonStore.recordImpsTripGp(rsn, impsTripLootValueAccumulator);
                }
            }
            impsTripLootValueAccumulator = 0;
            chatLog("Bank trip #" + bankTripCount + " compleet (" + source + ")");
        } else {
            chatLog("Bank trip (gear prep, " + source + ") compleet - GE teller niet verhogen");
        }

        if (bankingBeforeRotation) {
            bankingBeforeRotation = false;
            isBankingTrip = false;
            isRestocking = false;
            paint.setCurrentStatus("Imps: Gebankt, klaar voor rotatie");
            return 500;
        }

        // Check of we naar GE moeten
        if (!preparingGear && config.impsGeSellEnabled() && bankTripCount >= config.impsGeSellAfterBanks()) {
            bankTripCount = 0;
            isBankingTrip = false;
            isRestocking = false;
            isSellingAtGe = true;
            geSellStep = 0;
            geSellItemIndex = 0;
            geCollectedExisting = false;
            chatLog("[GE] Na " + config.impsGeSellAfterBanks() + " bank trips: naar GE om loot te verkopen!");
            return randomDelay(600, 1000);
        }

        isBankingTrip = false;
        isRestocking = false;
        teleportUsedForDockTrip = false;
        paint.setCurrentStatus("Imps: [boat] Terug naar Karamja");
        return randomDelay(600, 1000);
    }

    /**
     * RESTOCK MODE: deposit loot, dan withdraw supplies (arrows/runes/coins) uit de bank.
     * Gebruikt de volwaardige bank om in een sessie alles aan te vullen.
     */
    private int handleBankRestock() {
        if (shouldAbortActions()) {
            return antiBan.varyDelay(randomDelay(250, 500));
        }
        if (!Bank.isOpen()) return walkToAndOpenBank(Players.getLocal().getWorldLocation());

        // Zelfde logica als {@link #checkIfRestockNeeded()}: MAGE-supplies uit config, behalve Tier-2 melee-fallback.
        CombatBotConfig.ImpsCombatStyle style = getEffectiveStyle();
        if (impsConfigMageSuppliesRestockActive()) {
            style = CombatBotConfig.ImpsCombatStyle.MAGE;
        }
        paint.setCurrentStatus("Imps: Restock (" + style + ")");
        ensureBankWithdrawUnnoted();

        // Stap 1: Deposit alles behalve keep list
        if (hasDepositItems()) {
            List<String> keepNames = getFullKeepList();
            keepNames.add("Coins");
            for (IInventoryItem item : Inventory.getAll()) {
                if (item != null && item.getName() != null && shouldKeepItem(item) && !keepNames.contains(item.getName())) {
                    keepNames.add(item.getName());
                }
            }
            debugLog("handleBankRestock: depositAllExcept (keep list)");
            Bank.depositAllExcept(keepNames.toArray(new String[0]));
            sleep(400, 700);
            return antiBan.varyDelay(randomDelay(600, 1000));
        }

        // Stap 2: Coins aanvullen
        if (getCoinCount() < impsRuntimeMinCoins() && Bank.contains("Coins")) {
            int before = getCoinCount();
            Bank.withdraw("Coins", Integer.MAX_VALUE);
            sleep(500, 900);
            int after = getCoinCount();
            if (after <= before) {
                chatLog("[!] Restock: coins withdraw lijkt niet gelukt (before=" + before + ", after=" + after + ") -> retry");
                Bank.withdraw("Coins", Integer.MAX_VALUE);
                sleep(600, 1000);
            }
            chatLog("Restock: alle coins opgehaald");
            sleep(400, 700);
            return antiBan.varyDelay(randomDelay(400, 800));
        }

        // Stap 3: Supplies aanvullen op basis van combat style
        switch (style) {
            case RANGED: {
                int totalArrows = getImpsEffectiveArrowCount();
                if (totalArrows < 500) {
                    for (String arrow : RangedAmmoPreference.withdrawOrder(config)) {
                        if (Bank.contains(arrow)) {
                            Bank.withdraw(arrow, Integer.MAX_VALUE);
                            chatLog("Restock: alle " + arrow + " opgehaald");
                            sleep(400, 700);
                            break;
                        }
                    }
                    return antiBan.varyDelay(randomDelay(400, 800));
                }
                String bowUp = firstUsableBetterRangedBowInBank();
                if (bowUp != null) {
                    Bank.withdraw(bowUp, 1);
                    chatLog("Restock: betere boog opgehaald: " + bowUp);
                    sleep(400, 700);
                    return antiBan.varyDelay(randomDelay(400, 800));
                }
                int restockArmour = handleStyleArmourAtBank(CombatBotConfig.ImpsCombatStyle.RANGED);
                if (restockArmour > 0) {
                    return restockArmour;
                }
                break;
            }
            case MAGE: {
                CombatBotConfig.ImpsMageSpell spell = getActiveMageSpell();

                int catalystCount = getItemQuantity(spell.getCatalystRune());
                int minCat = minCatalystRunesForTrip(spell);
                if (isBelowCatalystTripMinimum(catalystCount, spell) && !bankHasUsableItemNamed(spell.getCatalystRune())) {
                    // Voorkom "Restock compleet" terwijl de kern-rune ontbreekt: ga direct naar GE/fallback.
                    return handleRestockRuneWithdrawStuck(spell.getCatalystRune(), catalystCount, minCat);
                }
                if (isBelowCatalystTripMinimum(catalystCount, spell) && bankHasUsableItemNamed(spell.getCatalystRune())) {
                    ensureBankWithdrawUnnoted();
                    Bank.withdraw(spell.getCatalystRune(), Integer.MAX_VALUE);
                    if (!waitUntilItemQuantityIncreases(spell.getCatalystRune(), catalystCount, 4500)) {
                        ensureBankWithdrawUnnoted();
                        Bank.withdraw(spell.getCatalystRune(), Integer.MAX_VALUE);
                        if (!waitUntilItemQuantityIncreases(spell.getCatalystRune(), catalystCount, 2500)) {
                            return handleRestockRuneWithdrawStuck(spell.getCatalystRune(), catalystCount, minCat);
                        }
                    }
                    int afterCat = getItemQuantity(spell.getCatalystRune());
                    if (afterCat < minCat && !bankHasUsableItemNamed(spell.getCatalystRune())) {
                        return handleRestockRuneWithdrawStuck(spell.getCatalystRune(), afterCat, minCat);
                    }
                    chatLog("Restock: alle " + spell.getCatalystRune() + " opgehaald");
                    return antiBan.varyDelay(randomDelay(400, 800));
                }

                // Elemental runes (als staff niet dekt)
                String elementalRune = spell.getElementalRune();
                String element = elementalRune.toLowerCase().replace(" rune", "");
                boolean staffCoversElemental = hasStaffWithElement(element);
                int minElem = minElementalRunesForTrip(spell);
                if (!staffCoversElemental && getItemQuantity(elementalRune) < minElem && !bankHasUsableItemNamed(elementalRune)) {
                    return handleRestockRuneWithdrawStuck(elementalRune, getItemQuantity(elementalRune), minElem);
                }
                if (!staffCoversElemental && getItemQuantity(elementalRune) < minElem && bankHasUsableItemNamed(elementalRune)) {
                    int beforeEl = getItemQuantity(elementalRune);
                    ensureBankWithdrawUnnoted();
                    Bank.withdraw(elementalRune, Integer.MAX_VALUE);
                    if (!waitUntilItemQuantityIncreases(elementalRune, beforeEl, 4500)) {
                        ensureBankWithdrawUnnoted();
                        Bank.withdraw(elementalRune, Integer.MAX_VALUE);
                        if (!waitUntilItemQuantityIncreases(elementalRune, beforeEl, 2500)) {
                            return handleRestockRuneWithdrawStuck(elementalRune, beforeEl, minElem);
                        }
                    }
                    int afterEl = getItemQuantity(elementalRune);
                    if (afterEl < minElem && !bankHasUsableItemNamed(elementalRune)) {
                        return handleRestockRuneWithdrawStuck(elementalRune, afterEl, minElem);
                    }
                    chatLog("Restock: alle " + elementalRune + " opgehaald (staff dekt niet)");
                    return antiBan.varyDelay(randomDelay(400, 800));
                }

                // Air runes voor non-wind spells
                if (spell.needsAirRune()) {
                    boolean staffCoversAir = hasStaffWithElement("air");
                    int airCount = getItemQuantity("Air rune");
                    int airMin = requiredAirRunesForCurrentMagePlan();
                    if (!staffCoversAir && airCount < airMin && !bankHasUsableItemNamed("Air rune")) {
                        return handleRestockRuneWithdrawStuck("Air rune", airCount, airMin);
                    }
                    if (!staffCoversAir && airCount < airMin && bankHasUsableItemNamed("Air rune")) {
                        ensureBankWithdrawUnnoted();
                        Bank.withdraw("Air rune", Integer.MAX_VALUE);
                        if (!waitUntilItemQuantityIncreases("Air rune", airCount, 4500)) {
                            ensureBankWithdrawUnnoted();
                            Bank.withdraw("Air rune", Integer.MAX_VALUE);
                            if (!waitUntilItemQuantityIncreases("Air rune", airCount, 2500)) {
                                return handleRestockRuneWithdrawStuck("Air rune", airCount, airMin);
                            }
                        }
                        int afterAir = getItemQuantity("Air rune");
                        if (afterAir < airMin && !bankHasUsableItemNamed("Air rune")) {
                            return handleRestockRuneWithdrawStuck("Air rune", afterAir, airMin);
                        }
                        chatLog("Restock: alle Air rune opgehaald");
                        return antiBan.varyDelay(randomDelay(400, 800));
                    }
                }
                break;
            }
            case MELEE:
            default:
                if (config.impsMeleeOpeningAirStrike()) {
                    int airCount = getItemQuantity("Air rune");
                    if (airCount < 100 && !bankHasUsableItemNamed("Air rune")) {
                        return handleRestockRuneWithdrawStuck("Air rune", airCount, 100);
                    }
                    if (airCount < 100 && bankHasUsableItemNamed("Air rune")) {
                        Bank.withdraw("Air rune", Integer.MAX_VALUE);
                        chatLog("Restock: Air runes opgehaald voor melee opener");
                        return antiBan.varyDelay(randomDelay(400, 800));
                    }
                    int mindCount = getItemQuantity("Mind rune");
                    if (mindCount < 100 && !bankHasUsableItemNamed("Mind rune")) {
                        return handleRestockRuneWithdrawStuck("Mind rune", mindCount, 100);
                    }
                    if (mindCount < 100 && bankHasUsableItemNamed("Mind rune")) {
                        Bank.withdraw("Mind rune", Integer.MAX_VALUE);
                        chatLog("Restock: Mind runes opgehaald voor melee opener");
                        return antiBan.varyDelay(randomDelay(400, 800));
                    }
                }
                break;
        }

        // Stap 4: Alles aangevuld - klaar
        Bank.close();
        sleep(400, 700);
        chatLog("[OK] Restock compleet! Supplies aangevuld.");
        return finishBankingTrip("restock");
    }

    private int handleRestockRuneWithdrawStuck(String runeName, int have, int needed) {
        chatLog("[!] Restock: " + runeName + " blijft te laag (" + have + "/" + needed
                + ") of withdraw stijgt niet -> GE failsafe");
        forceGeMissingItem = runeName;
        isBankingTrip = false;
        isRestocking = false;
        return handleGearShortage(CombatBotConfig.ImpsCombatStyle.MAGE);
    }

    /**
     * Fallback deposit via Draynor Village bank.
     * Zet weg wat we niet gebruiken (houd coins, runes, arrows, combat gear).
     */
    private int handleBankDeposit() {
        if (shouldAbortActions()) {
            return antiBan.varyDelay(randomDelay(250, 500));
        }
        List<String> keepNames = getFullKeepList();
        keepNames.add("Coins");
        for (IInventoryItem item : Inventory.getAll()) {
            if (item != null && item.getName() != null && shouldKeepItem(item) && !keepNames.contains(item.getName())) {
                keepNames.add(item.getName());
            }
        }
        debugLog("handleBankDeposit() - Bank.depositAllExcept(keep list)");
        Bank.depositAllExcept(keepNames.toArray(new String[0]));
        sleep(400, 700);

        // Na deposit: check of alles weg is
        if (!hasDepositItems()) {
            return finishBankingTrip("bank-deposit");
        }
        return antiBan.varyDelay(randomDelay(400, 800));
    }

    /**
     * Check of een item-naam behouden moet worden (voor widget-gebaseerde deposit box check).
     */
    private boolean shouldKeepItemByName(String name) {
        if (name == null || name.isEmpty()) return false;
        IInventoryItem lamp = Inventory.getFirst(item -> item != null && item.getId() == GENIE_LAMP_ITEM_ID);
        if (lamp != null && lamp.getName() != null && lamp.getName().equalsIgnoreCase(name)) return true;
        String lower = name.toLowerCase();
        boolean isMeleeWeapon = isCombatMeleeWeaponName(lower);
        if (isMeleeWeapon && !shouldRetainMeleeWeaponsDuringGearPrep()) {
            return false;
        }
        if (Equipment.contains(eq -> eq != null && eq.getName() != null && eq.getName().equalsIgnoreCase(name))) {
            if (isMeleeWeapon && isStrictMageGearPrep()) {
                return false;
            }
            if (impsMeleeSkipsArmour()
                    && StyleArmourBankHelper.isMeleeStyleArmourItemName(name)) {
                return false;
            }
            return true;
        }
        // Check tegen dynamische keep list (inclusief spell-specifieke runes)
        for (String keep : getFullKeepList()) {
            if (keep.equalsIgnoreCase(name)) return true;
        }
        // Alle runes behouden
        if (lower.endsWith(" rune") || lower.equals("rune")) return true;
        CombatBotConfig.ImpsCombatStyle style = getEffectiveStyle();
        CombatBotConfig.ImpsCombatStyle fallback = config.impsFallbackStyle();
        boolean keepMeleeWeapon = shouldRetainMeleeWeaponsDuringGearPrep();
        boolean keepRangedWeapon = style == CombatBotConfig.ImpsCombatStyle.RANGED || fallback == CombatBotConfig.ImpsCombatStyle.RANGED;
        boolean keepMageWeapon = style == CombatBotConfig.ImpsCombatStyle.MAGE || fallback == CombatBotConfig.ImpsCombatStyle.MAGE;
        if (isMeleeWeapon && keepMeleeWeapon) return true;
        if (isRangedBowItemName(name) && keepRangedWeapon) return true;
        if ((lower.contains("staff") || lower.contains("wand")) && keepMageWeapon) return true;

        if (impsMeleeSkipsArmour() && StyleArmourBankHelper.isMeleeStyleArmourItemName(name)) {
            return false;
        }

        return lower.contains("arrow")
                || lower.contains("shield") || lower.contains("defender")
                || lower.contains("helm") || lower.contains("hat") || lower.contains("hood")
                || lower.contains("plate") || lower.contains("chain") || lower.contains("robe")
                || lower.contains("legs") || lower.contains("skirt") || lower.contains("chaps")
                || (lower.contains("boots") && !StyleArmourBankHelper.isCosmeticOrNonCombatFootwear(name))
                || lower.contains("gloves") || lower.contains("vambraces")
                || lower.contains("cape") || lower.contains("cloak") || lower.contains("amulet")
                || lower.contains("necklace") || lower.contains("ring") || lower.contains("bracelet")
                || lower.contains("quiver") || lower.contains("body");
    }
    // ===================== BOOT REIZEN =====================

    /**
     * Boot vanaf **Karamja (Musa Point)** naar **Port Sarim** — gebruikt tijdens {@link #handleBankingTrip}
     * om loot te dumpen (deposit box) of naar een volwaardige bank te gaan. Daarna weer naar Karamja via {@link #payFareBack()}.
     */
    private int payFare() {
        IPlayer localEarly = Players.getLocal();
        WorldPoint myEarly = localEarly != null ? localEarly.getWorldLocation() : null;
        clearKaramjaSharedBoatIfMainland(myEarly);
        if (!isLootDumpStyleKaramjaBoat() && preparingGear && gearPrepKaramjaUseHomeTele
                && myEarly != null && isOnKaramja(myEarly)) {
            return gearPrepExitKaramjaViaHomeTeleport(localEarly, myEarly);
        }

        int dialogDelay = tryHandleBoatTravelDialog();
        if (dialogDelay > 0) {
            return dialogDelay;
        }

        long now = System.currentTimeMillis();
        // Voorkom dubbel-klikken op Customs terwijl oversteek loopt
        if (now - lastBoatClickTime < BOAT_CLICK_COOLDOWN_MS) {
            IPlayer localCooldown = Players.getLocal();
            if (localCooldown != null && localCooldown.isMoving()) {
                return antiBan.varyDelay(randomDelay(800, 1400));
            }
            if (myEarly != null && isOnKaramja(myEarly)) {
                paint.setCurrentStatus("Imps: [boat] Wacht op oversteek…");
                return antiBan.varyDelay(randomDelay(1200, 2000));
            }
        }

        IPlayer local = Players.getLocal();
        WorldPoint my = local != null ? local.getWorldLocation() : null;

        INPC travelNpc = findKaramjaCustomsOfficer();

        if (travelNpc != null) {
            karamjaPortBoatNpcMissingSinceMs = 0L;
            chatLog("[boat] Karamja → Port Sarim — NPC: " + travelNpc.getName());
            lastBoatClickTime = now;
            if (travelNpc.hasAction("Travel")) {
                travelNpc.interact("Travel");
            } else if (travelNpc.hasAction("Pay-fare") || travelNpc.hasAction("Pay-Fare")) {
                travelNpc.interact("Pay-fare");
            } else {
                travelNpc.interact("Talk-to");
            }
            if (!isLootDumpStyleKaramjaBoat() && preparingGear) {
                gearPrepKaramjaBoatFailStreak = 0;
            }
            return antiBan.varyDelay(randomDelay(3000, 5000));
        }

        // NOOIT gangplank/crossplank gebruiken!
        maybeLogBoatMissingNpcDebug("[boat] Geen Customs officer bij dock — dichter lopen…");
        if (my != null) {
            int dDock = distanceToKaramjaBoatDock(my);
            if (dDock <= 16) {
                if (karamjaPortBoatNpcMissingSinceMs == 0L) {
                    karamjaPortBoatNpcMissingSinceMs = now;
                } else if (now - karamjaPortBoatNpcMissingSinceMs >= KARAMJA_BOAT_NPC_MISSING_FORCE_ALT_MS) {
                    preferAlternateKaramjaDockRoute = true;
                    chatLog("[boat] Lang geen Customs officer bij dock — alternatieve aanloop (geen loopbrug-hang)");
                    karamjaPortBoatNpcMissingSinceMs = now - 10_000L;
                }
            } else {
                karamjaPortBoatNpcMissingSinceMs = 0L;
            }
            return walkTowardKaramjaBoatDockStepped(my, now, "");
        }

        return antiBan.varyDelay(randomDelay(1500, 2500));
    }

    private void markKaramjaHomeTeleExitPending() {
        long now = System.currentTimeMillis();
        karamjaHomeTeleExitGraceUntilMs = now + KARAMJA_HOME_TELE_EXIT_GRACE_MS;
        gearPrepKaramjaUseHomeTele = true;
    }

    /** Tel boot-fout tijdens gear prep; na drempel → Home teleport fallback. Niet tijdens loot-dump. */
    private void recordGearPrepKaramjaBoatFailure() {
        if (isLootDumpStyleKaramjaBoat() || !preparingGear || gearPrepKaramjaUseHomeTele) {
            return;
        }
        gearPrepKaramjaBoatFailStreak++;
        if (gearPrepKaramjaBoatFailStreak >= GEAR_PREP_KARAMJA_BOAT_FAIL_FALLBACK) {
            chatLog("[gear-prep] Boot/Travel " + gearPrepKaramjaBoatFailStreak
                    + "x mislukt — fallback Home teleport (loot-dump boot eerst geprobeerd)");
            gearPrepKaramjaBoatFailStreak = 0;
            gearPrepKaramjaUseHomeTele = true;
        }
    }

    /**
     * Gear prep fallback: Home teleport naar mainland als boot/Travel op Karamja blijft falen.
     */
    private int gearPrepExitKaramjaViaHomeTeleport(IPlayer local, WorldPoint myPos) {
        if (local == null || myPos == null) {
            return antiBan.varyDelay(randomDelay(800, 1400));
        }
        gearPrepKaramjaUseHomeTele = true;

        if (Dialog.isOpen()) {
            dismissBoatTalkDialog();
            chatLog("[gear-prep] Talk-dialoog gesloten — Home tele fallback");
            return antiBan.varyDelay(randomDelay(450, 750));
        }

        long now = System.currentTimeMillis();
        if ((now - lastTeleportTime) <= TELEPORT_COOLDOWN_MS) {
            paint.setCurrentStatus("Imps: gear prep wacht Home tele (geen Customs officer)");
            return antiBan.varyDelay(randomDelay(900, 1500));
        }

        SpellBook.Standard home = SpellBook.Standard.HOME_TELEPORT;
        if (home.canCast()) {
            paint.setCurrentStatus("Imps: gear prep Home teleport fallback (Karamja → mainland bank)");
            chatLog("[gear-prep] Home teleport fallback vanaf Karamja (boot faalde)");
            markKaramjaHomeTeleExitPending();
            try {
                Magic.cast(home);
                lastTeleportTime = now;
            } catch (Exception e) {
                chatLog("[!] Gear prep Home teleport fout: " + e.getMessage());
                return antiBan.varyDelay(randomDelay(1200, 2000));
            }
            return antiBan.varyDelay(randomDelay(4500, 7000));
        }

        paint.setCurrentStatus("Imps: gear prep wacht Home tele beschikbaar");
        return antiBan.varyDelay(randomDelay(1500, 2300));
    }

    /**
     * Gear prep op Karamja: zelfde dock + {@link #payFare()} als loot-dump; Home tele alleen na herhaalde boot-fout.
     * Pas op mainland (Port Sarim+) volgt normale gear-prep bank-flow in {@link #handleGearPreparation}.
     */
    private int travelFromKaramjaToPortSarim(IPlayer local, WorldPoint myPos, boolean bankingTripContext) {
        if (local == null || myPos == null) {
            return antiBan.varyDelay(randomDelay(800, 1400));
        }
        if (!isOnKaramja(myPos)) {
            karamjaSharedBoatTrip = false;
            return 0;
        }
        if (!bankingTripContext && gearPrepKaramjaUseHomeTele) {
            return gearPrepExitKaramjaViaHomeTeleport(local, myPos);
        }
        if (getCoinCount() < BOAT_FARE) {
            if (bankingTripContext) {
                chatLog("[bank-trip] Op Karamja zonder " + BOAT_FARE + " gp voor terugboot (" + getCoinCount()
                        + ") — geen dock-lopen; coin-recovery / GE.");
                isBankingTrip = false;
                isRestocking = false;
            }
            return handleInsufficientCoinsForBoatFare(local);
        }
        int distToDock = distanceToKaramjaBoatDock(myPos);
        if (distToDock > 10) {
            long now = System.currentTimeMillis();
            String suffix = bankingTripContext ? "" : " gear prep";
            return walkTowardKaramjaBoatDockStepped(myPos, now, suffix);
        }
        paint.setCurrentStatus("Imps: [boat] Boot naar Port Sarim" + (bankingTripContext ? "" : " (gear prep)"));
        return payFare();
    }

    /** Zelfde stepped walk als loot-dump bank-trip naar Customs officer. */
    private int walkTowardKaramjaBoatDockStepped(WorldPoint myPos, long now, String statusSuffix) {
        IPlayer local = Players.getLocal();
        int distToDock = distanceToKaramjaBoatDock(myPos);
        boolean canReclick = (now - lastWalkClickTime) > WALK_RECLICK_INTERVAL_MS;
        if (local != null && local.isMoving() && !canReclick) {
            paint.setCurrentStatus("Imps: -> Karamja dock (" + distToDock + " tiles)" + statusSuffix
                    + (preferAlternateKaramjaDockRoute ? " alt" : ""));
            return antiBan.varyDelay(randomDelay(800, 1400));
        }
        paint.setCurrentStatus("Imps: -> Karamja dock (" + distToDock + " tiles)" + statusSuffix
                + (preferAlternateKaramjaDockRoute ? " alt" : ""));
        if (isPointInScorpionRadius(myPos)) {
            ensureRunEnabledForScorpionZone();
        }
        WorldPoint dockTarget = getKaramjaBoatWalkTarget();
        int stepMin = configuredImpsStepMin();
        int stepMax = configuredImpsStepMax(stepMin);
        if (distToDock > stepMax) {
            int dx = dockTarget.getX() - myPos.getX();
            int dy = dockTarget.getY() - myPos.getY();
            double dist = Math.sqrt(dx * dx + dy * dy);
            int stepLen = Math.min(distToDock - 1, randomDelay(stepMin, stepMax));
            int stepX = myPos.getX() + (int) (dx / dist * stepLen);
            int stepY = myPos.getY() + (int) (dy / dist * stepLen);
            WorldPoint stepToDock = new WorldPoint(stepX, stepY, dockTarget.getPlane());
            if (isPointInScorpionRadius(stepToDock)) {
                ensureRunEnabledForScorpionZone();
            }
            MovementHelper.walkTo(stepToDock);
        } else {
            MovementHelper.walkTo(dockTarget);
        }
        lastWalkClickTime = now;
        return antiBan.varyDelay(randomDelay(1500, 2500));
    }

    /**
     * Boot naar Musa Point kost {@link #BOAT_FARE} gp. Met minder op zak niet naar Port Sarim lopen;
     * eerst loot verkopen via bestaande GE-flow (inventory én items op bank volgens verkooplijst), of gear prep.
     */
    private int handleInsufficientCoinsForBoatFare(IPlayer local) {
        int coins = getCoinCount();
        chatLog("[boat] Te weinig gp voor boot (" + coins + "/" + BOAT_FARE + ") — eerst GE/bank, niet Port Sarim.");
        paint.setCurrentStatus("Imps: Te weinig gp voor boot — eerst loot/bank");

        if (inventoryContainsAnyGeSellListItem()) {
            chatLog("[boat] Loot op zak of bank (verkooplijst) — start GE-verkoop.");
            startGeSellNow();
            return handleGeSelling(local);
        }
        // Belangrijk: bank moet eerst live gecheckt zijn voordat we concluderen dat er "geen loot" is.
        if (!coinRecoveryBankChecked) {
            chatLog("[boat] Bank nog niet live gecheckt op verkooploot — eerst gear prep/bank.");
            preparingGear = true;
            gearPrepComplete = false;
            coinRecoveryMode = false;
            return handleGearPreparation(local);
        }
        if (bankContainsAnyGeSellListItem()) {
            chatLog("[boat] Loot op zak of bank (verkooplijst) — start GE-verkoop.");
            startGeSellNow();
            return handleGeSelling(local);
        }

        if (!coinRecoveryMode) {
            chatLog("[boat] Geen verkoopbare loot — coin-recovery actief (Karamja=Home tele, mainland=goblins tot 30 gp).");
            coinRecoveryMode = true;
            coinRecoveryGeSellPending = true;
            return antiBan.varyDelay(randomDelay(500, 900));
        }

        return handleCoinRecoveryMode(local);
    }

    /** Minstens één item in inventory dat matcht {@link #getGeSellItemsFromConfig()} (beads, talisman, …). */
    private boolean inventoryContainsAnyGeSellListItem() {
        for (String sell : getGeSellItemsFromConfig()) {
            if (sell == null || sell.trim().isEmpty()) {
                continue;
            }
            String needle = sell.trim().toLowerCase();
            for (IInventoryItem item : Inventory.getAll()) {
                if (item == null || item.getName() == null) {
                    continue;
                }
                String n = Text.removeTags(item.getName()).toLowerCase();
                if (n.equals(needle) || n.contains(needle)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Minstens één stack in bank die bij de GE-verkooplijst hoort (zelfde namen als {@link #handleGeSelling} withdraw). */
    private boolean bankContainsAnyGeSellListItem() {
        try {
            for (String sell : getGeSellItemsFromConfig()) {
                if (sell == null || sell.trim().isEmpty()) {
                    continue;
                }
                String name = sell.trim();
                if (Bank.contains(name)) {
                    return true;
                }
            }
        } catch (Exception e) {
            chatLog("[boat] Bank-check GE-items: " + e.getMessage());
        }
        return false;
    }

    /**
     * Na één mislukte coin-withdraw (geen stijging inv). Zelfde fallback als bij boot-tarief —
     * eerst GE als er nog loot is (inv/bank), anders imps stoppen.
     */
    private int handleGearPrepCoinsWithdrawExhausted(IPlayer local) {
        if (local == null) {
            return 1000;
        }
        if (!Bank.isOpen() && !coinRecoveryBankChecked) {
            chatLog("[boat] Nog geen live bank-check voor coins/loot — eerst gear prep/bank, geen goblins.");
            preparingGear = true;
            gearPrepComplete = false;
            return handleGearPreparation(local);
        }
        if (inventoryContainsAnyGeSellListItem() || bankContainsAnyGeSellListItem()) {
            chatLog("[gear-prep] GE-verkoop na mislukte coin-withdraw (loot op zak of bank)");
            startGeSellNow();
            return handleGeSelling(local);
        }
        if (Bank.isOpen()) {
            if (!hasMeleeWeapon() && Bank.contains(item -> {
                if (item == null || item.getName() == null) return false;
                return isCombatMeleeWeaponName(item.getName().toLowerCase(Locale.ROOT));
            })) {
                paint.setCurrentStatus("Imps: coin-recovery melee wapen halen");
                return withdrawMeleeGear();
            }
            int recoveryArmour = impsMeleeSkipsArmour() ? 0 : handleStyleArmourAtBank(CombatBotConfig.ImpsCombatStyle.MELEE);
            if (recoveryArmour > 0) {
                paint.setCurrentStatus("Imps: coin-recovery armor uit bank");
                return recoveryArmour;
            }
        }
        coinRecoveryBankChecked = true;
        if (Bank.isOpen()) {
            Bank.close();
            sleep(400, 700);
        }
        coinRecoveryMode = true;
        coinRecoveryGeSellPending = false;
        preparingGear = false;
        gearPrepComplete = false;
        paint.setCurrentStatus("Imps: coin-recovery (geen bank-gp)");
        chatLog("[gear-prep] Geen bank-gp + geen sell-loot -> goblins voor " + BOAT_FARE + " gp");
        return antiBan.varyDelay(randomDelay(700, 1200));
    }

    /** Hard escape zonder switch naar andere skill: GE proberen, anders goblins farmen voor 30 gp. */
    private int handleCoinRecoveryMode(IPlayer local) {
        if (local == null || local.getWorldLocation() == null) {
            return 1000;
        }
        if (getCoinCount() >= BOAT_FARE) {
            coinRecoveryMode = false;
            coinRecoveryGeSellPending = false;
            coinRecoveryBankChecked = false;
            paint.setCurrentStatus("Imps: coin-recovery klaar (" + getCoinCount() + " gp)");
            chatLog("[coins] Coin-recovery voltooid: " + getCoinCount() + " gp, terug naar imps-flow.");
            return antiBan.varyDelay(randomDelay(400, 800));
        }

        WorldPoint pos = local.getWorldLocation();
        boolean onKaramja = isOnKaramja(pos);
        if (onKaramja) {
            return handleCoinRecoveryHomeTeleportThenGe(local);
        }

        if (coinRecoveryGeSellPending) {
            coinRecoveryGeSellPending = false;
            if (inventoryContainsAnyGeSellListItem()) {
                chatLog("[coins] Coin-recovery buiten Karamja: GE-verkoop zonder Home teleport.");
                startGeSellNow();
                return antiBan.varyDelay(randomDelay(500, 900));
            }
            if (coinRecoveryBankChecked && bankContainsAnyGeSellListItem()) {
                chatLog("[coins] Coin-recovery buiten Karamja: GE-verkoop uit bankloot.");
                startGeSellNow();
                return antiBan.varyDelay(randomDelay(500, 900));
            }
        }

        if (!coinRecoveryBankChecked) {
            chatLog("[coins] Coin-recovery: bank nog niet live gecheckt — eerst bank/gear prep.");
            preparingGear = true;
            gearPrepComplete = false;
            coinRecoveryMode = false;
            return handleGearPreparation(local);
        }

        return handleGoblinCoinFarmAtPortSarim(local);
    }

    /** Home teleport zodat we direct naar GE-flow/goblin fallback kunnen zonder boot-gp wachtrij. */
    private int handleCoinRecoveryHomeTeleportThenGe(IPlayer local) {
        if (local == null || local.getWorldLocation() == null) {
            return 1000;
        }
        WorldPoint pos = local.getWorldLocation();
        if (isOnKaramja(pos) && Dialog.isOpen()) {
            dismissBoatTalkDialog();
            return antiBan.varyDelay(randomDelay(450, 750));
        }
        if (pos.distanceTo(LUMBRIDGE_HOME_TELEPORT_ANCHOR) <= 22 && pos.getPlane() == 0) {
            coinRecoveryGeSellPending = false;
            if (inventoryContainsAnyGeSellListItem()) {
                chatLog("[coins] Lumbridge na Home tele — GE-verkoop poging.");
                startGeSellNow();
                return antiBan.varyDelay(randomDelay(500, 900));
            }
            if (!coinRecoveryBankChecked) {
                chatLog("[coins] Lumbridge: bank nog niet live gecheckt op verkooploot — eerst gear prep/bank.");
                preparingGear = true;
                gearPrepComplete = false;
                coinRecoveryMode = false;
                return handleGearPreparation(local);
            }
            if (bankContainsAnyGeSellListItem()) {
                chatLog("[coins] Lumbridge: verkooploot in bank gevonden — GE-verkoop poging.");
                startGeSellNow();
                return antiBan.varyDelay(randomDelay(500, 900));
            }
            chatLog("[coins] Lumbridge na Home tele — niets te verkopen, goblins voor 30 gp.");
            return antiBan.varyDelay(randomDelay(500, 900));
        }

        long now = System.currentTimeMillis();
        if ((now - lastTeleportTime) <= TELEPORT_COOLDOWN_MS) {
            paint.setCurrentStatus("Imps: coin-recovery wacht teleport-cooldown");
            return antiBan.varyDelay(randomDelay(900, 1500));
        }
        SpellBook.Standard home = SpellBook.Standard.HOME_TELEPORT;
        if (home.canCast()) {
            paint.setCurrentStatus("Imps: coin-recovery Home teleport");
            chatLog("[coins] Home teleport casten voor coin-recovery.");
            try {
                Magic.cast(home);
                lastTeleportTime = now;
                markKaramjaHomeTeleExitPending();
            } catch (Exception e) {
                chatLog("[!] Coin-recovery Home teleport fout: " + e.getMessage());
            }
            return antiBan.varyDelay(randomDelay(4500, 7000));
        }

        paint.setCurrentStatus("Imps: home teleport cooldown actief, farm imps verder");
        if (preparingGear || gearPrepKaramjaUseHomeTele
                || System.currentTimeMillis() < karamjaHomeTeleExitGraceUntilMs) {
            paint.setCurrentStatus("Imps: coin-recovery wacht Home tele (geen Customs officer)");
            return antiBan.varyDelay(randomDelay(900, 1500));
        }
        if (isPlayerInHuntingArea(local)) {
            // Geen teleport beschikbaar: niet stilstaan; op Karamja doorgaan met imps tot inventory-trip.
            return handleKilling(local);
        }
        return antiBan.varyDelay(randomDelay(1500, 2300));
    }

    /** Port Sarim fallback: farm goblins met melee tot minimaal 30 gp. */
    private int handleGoblinCoinFarmAtPortSarim(IPlayer local) {
        if (local == null || local.getWorldLocation() == null) {
            return 1000;
        }
        equipGear(CombatBotConfig.ImpsCombatStyle.MELEE);

        ITileItem groundCoins = TileItems.getNearest(item ->
                item != null && item.getName() != null
                        && "Coins".equalsIgnoreCase(Text.removeTags(item.getName()).trim())
                        && item.getWorldLocation() != null
                        && item.getWorldLocation().distanceTo(getPortSarimGoblinAnchor()) <= getPortSarimGoblinRadius() + 4
        );
        if (groundCoins != null && !Inventory.isFull()) {
            paint.setCurrentStatus("Imps: coin-recovery loot coins");
            groundCoins.pickup();
            return antiBan.varyDelay(randomDelay(1000, 1600));
        }

        WorldPoint myPos = local.getWorldLocation();
        WorldPoint goblinAnchor = getPortSarimGoblinAnchor();
        int goblinRadius = getPortSarimGoblinRadius();
        int dist = myPos.distanceTo(goblinAnchor);
        if (dist > goblinRadius) {
            paint.setCurrentStatus("Imps: coin-recovery -> Port Sarim goblins");
            MovementHelper.walkTo(goblinAnchor);
            return antiBan.varyDelay(randomDelay(900, 1500));
        }

        if (local.isInteracting() && local.getInteracting() instanceof INPC) {
            paint.setCurrentStatus("Imps: coin-recovery goblin gevecht");
            return antiBan.varyDelay(randomDelay(600, 1000));
        }

        INPC goblin = NPCs.getNearest(npc ->
                npc != null
                        && npc.getName() != null
                        && npc.getName().toLowerCase(Locale.ROOT).contains("goblin")
                        && npc.hasAction("Attack")
                        && !npc.isDead()
                        && npc.getWorldLocation() != null
                        && npc.getWorldLocation().distanceTo(goblinAnchor) <= goblinRadius
                        && (!npc.isInteracting() || (npc.getInteracting() != null && npc.getInteracting().equals(local)))
        );
        if (goblin != null) {
            paint.setCurrentStatus("Imps: coin-recovery attack goblin");
            goblin.interact("Attack");
            lastAttackTime = System.currentTimeMillis();
            return antiBan.varyDelay(randomDelay(900, 1400));
        }

        paint.setCurrentStatus("Imps: coin-recovery zoek goblin");
        MovementHelper.walkTo(goblinAnchor);
        return antiBan.varyDelay(randomDelay(800, 1300));
    }

    /**
     * Boot vanaf **Port Sarim** naar **Musa Point (Karamja)** — eerste oversteek om imps te gaan doen,
     * of terugkeren na {@link #payFare()} / bankieren.
     */
    private int payFareBack() {
        IPlayer local = Players.getLocal();
        if (local == null) {
            return 2000;
        }
        WorldPoint myPos = local.getWorldLocation();
        if (myPos != null && isOnKaramja(myPos)) {
            resetPortSarimBoatCrossingState();
            walkingToHuntArea = true;
            return antiBan.varyDelay(randomDelay(400, 700));
        }

        long now = System.currentTimeMillis();
        if (now - lastBoatClickTime < BOAT_CLICK_COOLDOWN_MS) {
            if (local.isMoving()) {
                return antiBan.varyDelay(randomDelay(800, 1400));
            }
        }

        INPC travelNpc = findTravelNpc(false);

        if (travelNpc != null) {
            int coins = getCoinCount();
            boolean canAffordFare = coins >= BOAT_FARE;
            if (!canAffordFare && !awaitingPortSarimToKaramjaCrossing) {
                maybeLogBoatMissingNpcDebug("[boat] Pay geblokkeerd: " + coins + " gp < " + BOAT_FARE
                        + " (geen actieve oversteek na betaling)");
                return antiBan.varyDelay(randomDelay(700, 1300));
            }
            if (canAffordFare) {
                awaitingPortSarimToKaramjaCrossing = true;
            }
            chatLog("[boat] Port Sarim → Karamja (naar imps) — NPC: " + travelNpc.getName());
            lastBoatClickTime = now;
            lastBoatTowardKaramjaInteractMs = now;
            if (travelNpc.hasAction("Travel")) {
                travelNpc.interact("Travel");
            } else if (travelNpc.hasAction("Pay-fare") || travelNpc.hasAction("Pay-Fare")) {
                travelNpc.interact("Pay-fare");
            } else {
                travelNpc.interact("Talk-to");
            }
            return antiBan.varyDelay(randomDelay(3000, 5000));
        }

        return walkTowardPortSarimBoatDock(local, myPos);
    }

    /**
     * Wacht op echte oversteek; geen Pay-fare-spam zolang we nog op dezelfde tile staan.
     * @return {@link #PAY_FARE_RESUME_NORMAL} om opnieuw Pay-fare te proberen
     */
    private int handleAwaitingPortSarimCrossing(IPlayer local, WorldPoint myPos, long now) {
        if (myPos != null && isOnKaramja(myPos)) {
            resetPortSarimBoatCrossingState();
            walkingToHuntArea = true;
            chatLog("[boat] Op Karamja aangekomen");
            return antiBan.varyDelay(randomDelay(400, 700));
        }
        if (local.isMoving() || local.getAnimation() != -1) {
            lastBoatTowardKaramjaInteractMs = now;
            portSarimPayAttemptsSameTile = 0;
            paint.setCurrentStatus("Imps: [boat] Oversteek bezig…");
            return antiBan.varyDelay(randomDelay(1500, 2500));
        }
        if (portSarimCoinsSnapshotBeforePay >= 0 && getCoinCount() < portSarimCoinsSnapshotBeforePay - 20) {
            portSarimCoinsSnapshotBeforePay = -1;
            lastBoatTowardKaramjaInteractMs = now;
            paint.setCurrentStatus("Imps: [boat] Tarief betaald — wacht op Karamja…");
            return antiBan.varyDelay(randomDelay(2000, 3500));
        }
        long sinceInteract = now - lastBoatTowardKaramjaInteractMs;
        if (sinceInteract < PORT_SARIM_CROSSING_MIN_WAIT_MS) {
            paint.setCurrentStatus("Imps: [boat] Wacht op oversteek…");
            return antiBan.varyDelay(randomDelay(1200, 2000));
        }
        boolean sameStartTile = portSarimCrossingStartTile != null && myPos != null
                && portSarimCrossingStartTile.equals(myPos);
        if (sameStartTile) {
            if (sinceInteract < PORT_SARIM_CROSSING_STUCK_MS) {
                paint.setCurrentStatus("Imps: [boat] Wacht op oversteek…");
                return antiBan.varyDelay(randomDelay(1200, 2000));
            }
            portSarimPayAttemptsSameTile++;
            if (portSarimPayAttemptsSameTile < PORT_SARIM_MAX_PAY_RETRIES_SAME_SPOT) {
                chatLog("[!][boat] Geen oversteek na Pay-fare (" + portSarimPayAttemptsSameTile + "/"
                        + PORT_SARIM_MAX_PAY_RETRIES_SAME_SPOT + ") — nieuwe poging");
                awaitingPortSarimToKaramjaCrossing = false;
                portSarimCoinsSnapshotBeforePay = -1;
                lastBoatClickTime = 0L;
                return PAY_FARE_RESUME_NORMAL;
            }
            chatLog("[!][boat] Dock vast op " + myPos.getX() + "," + myPos.getY()
                    + " — loop opnieuw naar Seaman");
            resetPortSarimBoatCrossingState();
            return walkTowardPortSarimBoatDock(local, myPos);
        }
        if (sinceInteract < BOAT_TOWARD_KARAMJA_GRACE_MS) {
            paint.setCurrentStatus("Imps: [boat] Naar Karamja…");
            return antiBan.varyDelay(randomDelay(1500, 2500));
        }
        chatLog("[!][boat] Oversteek-timeout (" + (sinceInteract / 1000) + "s) — opnieuw Pay-fare");
        resetPortSarimBoatCrossingState();
        return PAY_FARE_RESUME_NORMAL;
    }

    private int walkTowardPortSarimBoatDock(IPlayer local, WorldPoint myPos) {
        if (local == null) {
            return 2000;
        }
        maybeLogBoatMissingNpcDebug("[boat] Geen boot-NPC in de buurt — lopen naar Port Sarim dock…");
        MovementHelper.walkTo(PORTSARIM_DOCK_NPC);
        int dist = myPos != null ? myPos.distanceTo(PORTSARIM_DOCK_NPC) : 0;
        paint.setCurrentStatus("Imps: -> Port Sarim dock (" + dist + " tiles)");
        return antiBan.varyDelay(randomDelay(1200, 2200));
    }

    private void maybeLogBoatMissingNpcDebug(String msg) {
        long now = System.currentTimeMillis();
        // Niet in game-chat spammen; alleen debug en maximaal 1x per 8s.
        if (now - lastBoatMissingNpcDebugLogMs < 8_000L) {
            return;
        }
        lastBoatMissingNpcDebugLogMs = now;
        debugLog(msg);
    }

    private void maybeLogRallyMovementDebug(String msg) {
        long now = System.currentTimeMillis();
        // Alleen debug-tab en niet vaker dan ~1x per 2.5s.
        if (now - lastRallyMovementDebugLogMs < 2_500L) {
            return;
        }
        lastRallyMovementDebugLogMs = now;
        debugLog(msg);
    }

    private INPC findTravelNpc() {
        IPlayer local = Players.getLocal();
        boolean onKaramja = local != null && local.getWorldLocation() != null
                && isOnKaramja(local.getWorldLocation());
        return findTravelNpc(onKaramja);
    }

    /** Musa Point: alleen Customs officer (npc 380), niet Seaman op andere eilanden. */
    private INPC findKaramjaCustomsOfficer() {
        try {
            INPC byId = NPCs.getNearest(npc -> npc != null && npc.getId() == CUSTOMS_OFFICER_NPC_ID);
            if (byId != null) {
                return byId;
            }
        } catch (Exception ignored) {
        }
        return NPCs.getNearest(npc -> {
            String name = npc.getName();
            return name != null && name.equalsIgnoreCase("Customs officer");
        });
    }

    private WorldPoint getKaramjaBoatWalkTarget() {
        if (preferAlternateKaramjaDockRoute) {
            return KARAMJA_DOCK_ALT_WAYPOINT;
        }
        return KARAMJA_BOAT_APPROACH;
    }

    private int distanceToKaramjaBoatDock(WorldPoint my) {
        if (my == null) {
            return Integer.MAX_VALUE;
        }
        INPC customs = findKaramjaCustomsOfficer();
        if (customs != null && customs.getWorldLocation() != null) {
            return my.distanceTo(customs.getWorldLocation());
        }
        return my.distanceTo(getKaramjaBoatWalkTarget());
    }

    /**
     * Zoek boot-NPC. Op Karamja: alleen Customs officer.
     */
    private INPC findTravelNpc(boolean onKaramja) {
        if (onKaramja) {
            return findKaramjaCustomsOfficer();
        }
        return NPCs.getNearest(npc -> {
            String name = npc.getName();
            if (name == null) {
                return false;
            }
            String lower = name.toLowerCase(Locale.ROOT);
            return lower.contains("customs") || lower.contains("seaman") || lower.contains("captain tobias");
        });
    }

    /** Port Sarim dock: Seaman / Captain Tobias binnen straal van {@link #PORTSARIM_DOCK_NPC}. */
    private INPC findPortSarimBoatNpc() {
        WorldPoint dock = PORTSARIM_DOCK_NPC;
        INPC best = null;
        int bestScore = Integer.MIN_VALUE;
        try {
            for (INPC npc : NPCs.getAll()) {
                if (npc == null) {
                    continue;
                }
                if (!isPortSarimBoatNpcCandidate(npc)) {
                    continue;
                }
                WorldPoint wp = npc.getWorldLocation();
                if (wp == null || dock.distanceTo(wp) > PORT_SARIM_BOAT_NPC_SEARCH_RADIUS) {
                    continue;
                }
                int score = scoreBoatTravelNpc(npc, false);
                if (score > bestScore) {
                    bestScore = score;
                    best = npc;
                }
            }
        } catch (Exception ignored) {
        }
        if (best != null) {
            return best;
        }
        return NPCs.getNearest(npc -> {
            if (npc == null || npc.getWorldLocation() == null) {
                return false;
            }
            return dock.distanceTo(npc.getWorldLocation()) <= PORT_SARIM_BOAT_NPC_SEARCH_RADIUS
                    && isPortSarimBoatNpcCandidate(npc);
        });
    }

    private static boolean isPortSarimBoatNpcCandidate(INPC npc) {
        try {
            int id = npc.getId();
            for (int boatId : PORT_SARIM_BOAT_NPC_IDS) {
                if (id == boatId) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        String name = npc.getName();
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("seaman") || lower.contains("captain tobias");
    }

    private int scoreBoatTravelNpc(INPC npc, boolean onKaramja) {
        String name = npc.getName() != null ? npc.getName().toLowerCase(Locale.ROOT) : "";
        int score = 0;
        if (name.contains("customs officer")) {
            score += 200;
        } else if (name.contains("customs")) {
            score += 150;
        }
        if (npcActionIndexForBoat(npc, onKaramja) >= 0) {
            score += 120;
        }
        if (onKaramja && name.contains("seaman")) {
            score -= 50;
        }
        try {
            IPlayer local = Players.getLocal();
            if (local != null && local.getWorldLocation() != null && npc.getWorldLocation() != null) {
                int dist = local.getWorldLocation().distanceTo(npc.getWorldLocation());
                score += Math.max(0, 40 - dist);
            }
        } catch (Exception ignored) {
        }
        return score;
    }

    /** Travel / Pay-fare — nooit Talk-to. Karamja: Travel is bovenste menu-optie (zie screenshot). */
    private boolean interactBoatTravelNpc(INPC travelNpc, boolean fromKaramja) {
        if (travelNpc == null) {
            return false;
        }
        if (fromKaramja) {
            return interactKaramjaCustomsTravel(travelNpc);
        }
        return interactMainlandBoatNpc(travelNpc);
    }

    /**
     * Musa Point Customs officer: {@code Travel} (menu-optie 1) — zelfde code als loot-dump.
     */
    private boolean interactKaramjaCustomsTravel(INPC npc) {
        return interactBoatNpcByActionIndex(npc, true);
    }

    private boolean interactMainlandBoatNpc(INPC npc) {
        return interactBoatNpcByActionIndex(npc, false);
    }

    /**
     * NPC-menu: altijd de echte index van Travel/Pay-fare — nooit blind {@code NPC_FIRST_OPTION}
     * (dat is vaak Talk-to of Walk here, niet de boot-actie).
     */
    private boolean interactBoatNpcByActionIndex(INPC npc, boolean fromKaramja) {
        if (npc == null) {
            return false;
        }
        String npcName = npc.getName() != null ? npc.getName() : "NPC";
        int idx = npcActionIndexForBoat(npc, fromKaramja);
        if (idx >= 0) {
            String actionLabel = safeNpcActionAt(npc, idx);
            MenuAction menuAction = menuActionForNpcOptionIndex(idx);
            if (menuAction != null
                    && tryInvokeNpcMenuAction(npc, menuAction, actionLabel, npcName)) {
                chatLog("[boat] menu " + menuAction + " idx=" + idx + " \"" + actionLabel + "\" → " + npcName);
                return true;
            }
            try {
                npc.interact(idx);
                chatLog("[boat] interact(" + idx + ") \"" + actionLabel + "\" → " + npcName);
                return true;
            } catch (Throwable ignored) {
            }
        }

        String[] byName = fromKaramja
                ? new String[] {"Travel", "Port Sarim", "Pay-fare", "Pay-Fare"}
                : new String[] {"Pay-fare", "Pay-Fare", "Pay fare", "Travel"};
        for (String action : byName) {
            try {
                npc.interact(action);
                chatLog("[boat] interact(\"" + action + "\") → " + npcName);
                return true;
            } catch (Throwable ignored) {
            }
        }

        logNpcBoatActionsOnce(npc, fromKaramja);
        return false;
    }

    private static MenuAction menuActionForNpcOptionIndex(int index) {
        switch (index) {
            case 0:
                return MenuAction.NPC_FIRST_OPTION;
            case 1:
                return MenuAction.NPC_SECOND_OPTION;
            case 2:
                return MenuAction.NPC_THIRD_OPTION;
            case 3:
                return MenuAction.NPC_FOURTH_OPTION;
            case 4:
                return MenuAction.NPC_FIFTH_OPTION;
            default:
                return null;
        }
    }

    /** {@link net.storm.sdk.game.Client#invokeMenuAction} — zelfde params als loot-dump fallback. */
    private boolean tryInvokeNpcMenuAction(INPC npc, MenuAction menuAction, String option, String target) {
        if (npc == null || menuAction == null) {
            return false;
        }
        int npcIndex;
        int npcId;
        try {
            npcIndex = npc.getIndex();
            npcId = npc.getId();
        } catch (Throwable t) {
            return false;
        }
        try {
            net.storm.sdk.game.Client.invokeMenuAction(
                    npcIndex, 0, menuAction.getId(), npcId, npcIndex, -1, option, target);
            return true;
        } catch (Throwable t1) {
            try {
                net.storm.sdk.game.Client.invokeMenuAction(
                        npcIndex, 0, menuAction.getId(), npcIndex, npcId, -1, option, target);
                return true;
            } catch (Throwable t2) {
                return false;
            }
        }
    }

    /**
     * OSRS Musa Point Customs officer: {@code Travel} of {@code Port Sarim} (niet Talk-to).
     * @return menu-index &gt;= 0, of -1
     */
    private int npcActionIndexForBoat(INPC npc, boolean fromKaramja) {
        if (npc == null) {
            return -1;
        }
        String[] actions = npc.getActions();
        if (actions == null || actions.length == 0) {
            return -1;
        }
        String[] preferred = fromKaramja
                ? new String[] {"Travel", "Port Sarim", "Pay-fare", "Pay-Fare", "Pay fare"}
                : new String[] {"Pay-fare", "Pay-Fare", "Pay fare", "Travel"};
        for (String pref : preferred) {
            for (int i = 0; i < actions.length; i++) {
                String a = actions[i];
                if (a == null) {
                    continue;
                }
                if (isNpcMenuActionExcluded(a.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                if (a.equalsIgnoreCase(pref)) {
                    return i;
                }
            }
        }
        for (int i = 0; i < actions.length; i++) {
            String a = actions[i];
            if (a == null) {
                continue;
            }
            String low = a.toLowerCase(Locale.ROOT);
            if (isNpcMenuActionExcluded(low)) {
                continue;
            }
            if (fromKaramja) {
                if (low.contains("travel") || low.contains("port sarim")
                        || (low.contains("pay") && low.contains("fare"))) {
                    return i;
                }
            } else if ((low.contains("pay") && low.contains("fare")) || low.contains("travel")) {
                return i;
            }
        }
        return -1;
    }

    private static String safeNpcActionAt(INPC npc, int index) {
        try {
            String[] actions = npc.getActions();
            if (actions != null && index >= 0 && index < actions.length) {
                return actions[index];
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private long lastNpcBoatActionDebugLogMs = 0L;

    private void logNpcBoatActionsOnce(INPC npc, boolean fromKaramja) {
        long now = System.currentTimeMillis();
        if (now - lastNpcBoatActionDebugLogMs < 8_000L) {
            return;
        }
        lastNpcBoatActionDebugLogMs = now;
        if (npc == null) {
            return;
        }
        StringBuilder sb = new StringBuilder("[boat] NPC menu " + npc.getName() + " id=" + npc.getId() + ": ");
        String[] actions = npc.getActions();
        if (actions == null) {
            sb.append("(geen actions)");
        } else {
            for (int i = 0; i < actions.length; i++) {
                if (i > 0) {
                    sb.append(" | ");
                }
                sb.append(i).append("=").append(actions[i]);
            }
        }
        sb.append(" (gekozen idx=").append(npcActionIndexForBoat(npc, fromKaramja)).append(")");
        chatLog(sb.toString());
    }

    private static boolean isNpcMenuActionExcluded(String low) {
        if (low == null || low.isEmpty()) {
            return true;
        }
        return low.equals("talk-to") || low.equals("talk") || low.equals("examine")
                || low.contains("walk here") || low.equals("walk")
                || low.contains("attack") || low.contains("pickpocket");
    }

    private static boolean isBoatDialogExcludedOption(String option) {
        if (option == null) {
            return true;
        }
        String low = option.toLowerCase(Locale.ROOT);
        return low.contains("walk here") || low.equals("walk")
                || low.equals("no") || low.contains("cancel") || low.contains("never mind")
                || low.equals("talk-to") || low.equals("talk");
    }

    private static boolean isBoatDialogOption(String option) {
        if (option == null || isBoatDialogExcludedOption(option)) {
            return false;
        }
        String low = option.toLowerCase(Locale.ROOT);
        return low.contains("travel") || low.contains("port sarim")
                || (low.contains("pay") && low.contains("fare"))
                || low.contains("journey") || low.contains("ship") || low.contains("sail")
                || low.contains("yes") || low.contains("karamja") || low.contains("musa");
    }

    private static boolean boatDialogOptionMatches(String option, String needle) {
        if (option == null || needle == null || isBoatDialogExcludedOption(option)) {
            return false;
        }
        return option.equalsIgnoreCase(needle)
                || option.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    /** Choose-menu na Pay-fare/Travel: expliciet Travel/Pay-fare, nooit Walk here. */
    private boolean chooseBoatDialogOption() {
        if (!Dialog.isViewingOptions()) {
            return false;
        }
        String[] preferred = {
                "Travel", "Pay-fare", "Pay fare", "Port Sarim", "Yes", "Okay", "Ok"
        };
        for (String key : preferred) {
            if (Dialog.hasOption(s -> boatDialogOptionMatches(s, key))) {
                Dialog.chooseOption(s -> boatDialogOptionMatches(s, key));
                chatLog("[boat] Dialoog choose: \"" + key + "\"");
                return true;
            }
        }
        if (Dialog.hasOption(ImpsHandler::isBoatDialogOption)) {
            Dialog.chooseOption(ImpsHandler::isBoatDialogOption);
            chatLog("[boat] Dialoog choose: boot-optie (Travel/Pay-fare)");
            return true;
        }
        chatLog("[boat] Dialoog choose-menu open — geen Travel/Pay-fare optie gevonden");
        return false;
    }

    private void dismissBoatTalkDialog() {
        try {
            net.storm.sdk.input.Keyboard.type(String.valueOf((char) java.awt.event.KeyEvent.VK_ESCAPE), false);
        } catch (Exception ignored) {
        }
    }

    /** Boot-dialoog: opties / bevestiging (continue), niet meteen Escape (breekt Pay-fare-bevestiging). */
    private int tryHandleBoatTravelDialog() {
        try {
            if (!Dialog.isOpen()) {
                return 0;
            }
            if (Dialog.isViewingOptions()) {
                if (chooseBoatDialogOption()) {
                    lastBoatTowardKaramjaInteractMs = System.currentTimeMillis();
                    return antiBan.varyDelay(randomDelay(600, 1000));
                }
                return antiBan.varyDelay(randomDelay(400, 700));
            }
            if (Dialog.canContinue()) {
                Dialog.continueSpace();
                chatLog("[boat] Dialoog: doorgaan (tarief/bevestiging)");
                lastBoatTowardKaramjaInteractMs = System.currentTimeMillis();
                return antiBan.varyDelay(randomDelay(600, 1000));
            }
            dismissBoatTalkDialog();
            chatLog("[boat] Talk-to venster gesloten (Escape) — opnieuw Pay-fare");
            if (!isLootDumpStyleKaramjaBoat() && preparingGear && !gearPrepKaramjaUseHomeTele) {
                recordGearPrepKaramjaBoatFailure();
            }
            return antiBan.varyDelay(randomDelay(450, 750));
        } catch (Exception ignored) {
        }
        return 0;
    }

    // ===================== FOOD =====================

    private boolean shouldEat() {
        try {
            return Combat.getHealthPercent() < config.eatPercent();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasFoodToEat() {
        return Inventory.getFirst(item ->
                item != null && item.getActions() != null &&
                        Arrays.stream(item.getActions()).anyMatch(a ->
                                a != null && (a.equalsIgnoreCase("Eat") || a.equalsIgnoreCase("Drink"))
                        )
        ) != null;
    }

    private int handleEating() {
        IInventoryItem food = Inventory.getFirst(item ->
                item != null && item.getActions() != null &&
                        Arrays.stream(item.getActions()).anyMatch(a ->
                                a != null && (a.equalsIgnoreCase("Eat") || a.equalsIgnoreCase("Drink"))
                        )
        );
        if (food != null) {
            InventoryActionHelper.interact(config, food, "Eat");
            return antiBan.varyDelay(randomDelay(1200, 1800));
        }
        return 600;
    }

    private boolean canCastGearPrepTeleport(SpellBook.Standard spell, int requiredMagicLevel) {
        try {
            return Skills.getLevel(Skill.MAGIC) >= requiredMagicLevel && spell.canCast();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Probeer eerst te teleporteren en loop daarna naar dichtstbijzijnde bank.
     * Prioriteit: Varrock -> Falador -> Lumbridge (op basis van wat castbaar is).
     */
    private int tryTeleportToNearestBankForGearPrep(IPlayer local) {
        if (starterSkillBridge) {
            return -1;
        }
        if (local == null || local.getWorldLocation() == null) return -1;
        if (BankHelper.isNearFullBank()) return -1;

        long now = System.currentTimeMillis();
        if ((now - lastTeleportTime) <= TELEPORT_COOLDOWN_MS) return -1;

        SpellBook.Standard teleportSpell = null;
        String label = null;

        if (canCastGearPrepTeleport(SpellBook.Standard.VARROCK_TELEPORT, 25)) {
            teleportSpell = SpellBook.Standard.VARROCK_TELEPORT;
            label = "Varrock";
        } else if (canCastGearPrepTeleport(SpellBook.Standard.FALADOR_TELEPORT, 37)) {
            teleportSpell = SpellBook.Standard.FALADOR_TELEPORT;
            label = "Falador";
        } else if (canCastGearPrepTeleport(SpellBook.Standard.LUMBRIDGE_TELEPORT, 31)) {
            teleportSpell = SpellBook.Standard.LUMBRIDGE_TELEPORT;
            label = "Lumbridge";
        }

        if (teleportSpell == null) return -1;

        chatLog("[TP] Gear prep: " + label + " teleport -> daarna dichtstbijzijnde bank");
        paint.setCurrentStatus("Imps: [tp] " + label + " -> bank");
        try {
            // Chronicle-pad heeft voorrang op de Varrock-spell als de account-toggle aan staat
            // EN er een chronicle in inv ligt met charges (of cards om te laden).
            if (teleportSpell == SpellBook.Standard.VARROCK_TELEPORT) {
                String rsn = impsLocalRsnOrNull();
                if (VarrockTeleportHelper.tryExecuteChronicleTeleport(config, rsn)) {
                    chatLog("[TP] Chronicle (Diango) gebruikt i.p.v. Varrock-spell");
                    lastTeleportTime = now;
                    lastWalkClickTime = now;
                    return antiBan.varyDelay(randomDelay(4800, 6800));
                }
            }
            Magic.cast(teleportSpell);
            lastTeleportTime = now;
            lastWalkClickTime = now;
            return antiBan.varyDelay(randomDelay(4800, 6800));
        } catch (Exception e) {
            chatLog("[!] Gear prep teleport fout: " + e.getMessage());
            return -1;
        }
    }

    // ===================== GEAR PREPARATION =====================

    /**
     * Gear preparation bij switch naar Imps (EENMALIG):
     * 1. Ga naar bank
     * 2. Pak AL je coins mee (nooit banken!)
     * 3. Check of je al gear hebt in inv/equipment voor de gekozen style - zo ja, skip
     * 4. Pak ALLEEN gear voor de gekozen combat style, beste eerst
     */
    private int handleGearPreparation(IPlayer local) {
        if (shouldAbortActions()) {
            preparingGear = false;
            return antiBan.varyDelay(randomDelay(250, 500));
        }
        // Zelfde spell als maybeAutoUpgrade: gear prep draait vóór die call op ticks met preparingGear —
        // zonder deze sync blijft active spell Wind, faalt de Fire-Strike gate niet, en wordt prep te vroeg "compleet".
        if (shouldUseAutoFireStrikeUpgrade()
                && forcedMageSpell != CombatBotConfig.ImpsMageSpell.FIRE_STRIKE) {
            forcedMageSpell = CombatBotConfig.ImpsMageSpell.FIRE_STRIKE;
        }

        CombatBotConfig.ImpsCombatStyle style = getEffectiveStyle();
        if (isFireStrikeAirGateActive()
                && !(fallbackStyleActive && effectiveStyle == CombatBotConfig.ImpsCombatStyle.MELEE)) {
            style = CombatBotConfig.ImpsCombatStyle.MAGE;
            effectiveStyle = CombatBotConfig.ImpsCombatStyle.MAGE;
            fallbackStyleActive = false;
        }

        WorldPoint gearPrepPos = local.getWorldLocation();
        if (gearPrepPos != null && !isOnKaramja(gearPrepPos)) {
            gearPrepKaramjaUseHomeTele = false;
            gearPrepKaramjaBoatFailStreak = 0;
        }
        // Op Karamja: exact loot-dump pad — travelFromKaramjaToPortSarim(..., true) → payFare()
        if (gearPrepPos != null && isOnKaramja(gearPrepPos)) {
            paint.setCurrentStatus("Imps: gear prep → Port Sarim (zelfde boot als loot-dump)");
            if (gearPrepKaramjaUseHomeTele) {
                return gearPrepExitKaramjaViaHomeTeleport(local, gearPrepPos);
            }
            return travelToPortSarimFromKaramja(local);
        }

        // Equip-fase: bank dicht houden tot alles aangetrokken is
        if (gearPrepInvEquipPhase) {
            if (Bank.isOpen()) {
                Bank.close();
                sleep(400, 700);
                return antiBan.varyDelay(randomDelay(400, 700));
            }
            return handleGearPrepEquipPhase(style);
        }

        // 1. Check of we al gear + coins hebben ZONDER bank te openen
        if (hasAdequateGearForStyle(style)) {
            // Heeft gear in inventory? Doe het dan aan (zonder bank te openen).
            if (hasGearInInventory(style)) {
                gearPrepInvEquipPhase = true;
                return handleGearPrepEquipPhase(style);
            }

            int coins = getCoinCount();
            int freeSlots = Inventory.getFreeSlots();
            boolean gateSatisfied = true;
            if (isFireStrikeAirGateActive() && style == CombatBotConfig.ImpsCombatStyle.MAGE) {
                gateSatisfied = !fireStrikeAirDepartureGateFails();
                if (!gateSatisfied) {
                    int strict = requiredInventoryAirForAccountAutoFireSetup();
                    int disp = strict >= 0 ? strict : requiredAirRunesForMageDeparture();
                    chatLog("[!] Fire Strike gate in gear prep: Staff of fire + Air inv nodig (Air "
                            + getItemQuantity("Air rune") + "/" + disp + ")");
                }
            }
            if (coins >= impsRuntimeMinCoins() && freeSlots >= minFreeSlotsRequiredForGearPrep(style) && gateSatisfied) {
                int prepDone = tryCompleteGearPreparation(style);
                if (prepDone == -1) {
                    return randomDelay(600, 1000);
                }
                if (prepDone > 0) {
                    return prepDone;
                }
            }
        }

        // 2. Bank niet open -> loop naar dichtstbijzijnde volwaardige bank (NOOIT deposit box voor gear prep)
        if (!Bank.isOpen()) {
            paint.setCurrentStatus("Imps: -> Bank (Gear prep)");
            WorldPoint myPos = local.getWorldLocation();
            if (myPos != null && BankHelper.isNearGrandExchange(myPos)) {
                if (BankHelper.tryOpenBankAtGrandExchange()) {
                    return antiBan.varyDelay(randomDelay(1200, 2000));
                }
                return antiBan.varyDelay(randomDelay(800, 1400));
            }
            if (BankHelper.interactIfNearby()) {
                return antiBan.varyDelay(randomDelay(1500, 2500));
            }
            // Teleporteer als we rune/level hebben; daarna naar dichtstbijzijnde bank lopen.
            int tpDelay = tryTeleportToNearestBankForGearPrep(local);
            if (tpDelay > 0) {
                return tpDelay;
            }

            long now = System.currentTimeMillis();
            boolean canReclick = (now - lastWalkClickTime) > WALK_RECLICK_INTERVAL_MS;
            if (!canReclick && local.isMoving()) {
                return antiBan.varyDelay(randomDelay(600, 1200));
            }

            // Loop naar dichtstbijzijnde volwaardige bank
            if (!BankHelper.walkToNearestFullBank()) {
                chatLog("[!] Geen bank gevonden!");
            }
            lastWalkClickTime = now;
            return antiBan.varyDelay(randomDelay(1400, 2200));
        }

        // 2b. Na GE-verkoop zitten coins vaak op de bank (collect naar bank). Trek eerst naar inv zodat
        // shortage-checks en mind-koop niet alleen op 500 inv-gp blijven hangen.
        if (impsPostGeSellPullAllCoins) {
            impsPostGeSellPullAllCoins = false;
            if (Bank.contains("Coins")) {
                ensureBankWithdrawUnnoted();
                int before = getCoinCount();
                Bank.withdraw("Coins", Integer.MAX_VALUE);
                sleep(550, 900);
                int after = getCoinCount();
                if (after > before) {
                    chatLog("[GE] Na verkoop: coins bank→inv (+" + (after - before) + " gp, nu " + after + " gp op zak)");
                }
                return antiBan.varyDelay(randomDelay(500, 900));
            }
        }

        // 3. Bank is open: zet ELKE gear-prep eerst onnodige items weg (niet alleen bij weinig slots)
        if (hasGearPrepDepositItems()) {
            paint.setCurrentStatus("Imps: Onnodige items banken...");
            // Runes/ammo moeten unnoted in inventory komen tijdens gear prep
            try {
                if (Bank.isNotedWithdrawMode()) {
                    Bank.setWithdrawMode(false);
                    sleep(400, 700);
                }
            } catch (Exception ignored) {
            }
            List<String> keepNames = getGearPrepKeepNames();
            BankDepositHelper.depositAllExceptKeep(keepNames.toArray(new String[0]));
            sleep(800, 1200);
            if (hasGearPrepDepositItems()) {
                BankDepositHelper.depositAllExceptKeep(keepNames.toArray(new String[0]));
                sleep(400, 600);
            }
            return antiBan.varyDelay(randomDelay(600, 1000));
        }

        // 3b. Extra ruimte-check
        if (Inventory.getFreeSlots() < minFreeSlotsRequiredForGearPrep(style)) {
            paint.setCurrentStatus("Imps: Ruimte maken (min. " + minFreeSlotsRequiredForGearPrep(style) + " slots)...");
            return antiBan.varyDelay(randomDelay(500, 900));
        }

        // 4. Coins ophalen (één poging; geen effect → direct GE of stop)
        if (getCoinCount() < impsRuntimeMinCoins() && Bank.contains("Coins")) {
            ensureBankWithdrawUnnoted();
            int before = getCoinCount();
            Bank.withdraw("Coins", Integer.MAX_VALUE);
            sleep(700, 1100);
            int after = getCoinCount();
            if (after > before) {
                chatLog("Coins uit bank (+" + (after - before) + ", nu=" + after + " gp)");
                sleep(400, 700);
                return antiBan.varyDelay(randomDelay(600, 1000));
            }
            chatLog("[!] Gear prep: coins withdraw geen effect (before=" + before + ", after=" + after + ") — GE of stop");
            Bank.close();
            sleep(400, 700);
            return handleGearPrepCoinsWithdrawExhausted(local);
        }

        // Boot-gp is onderdeel van gear prep. Als de bank geen coins heeft, niet "gear complete" zeggen,
        // maar eerst sell-loot proberen of goblins farmen voor 30 gp.
        if (getCoinCount() < impsRuntimeMinCoins()) {
            chatLog("[gear-prep] Nog te weinig gp voor boot (" + getCoinCount() + "/" + impsRuntimeMinCoins()
                    + ") en geen coins uit bank beschikbaar — eerst coin-recovery.");
            return handleGearPrepCoinsWithdrawExhausted(local);
        }

        if (impsMeleeSkipsArmour(style)) {
            int bankMeleeArmour = bankImpsMeleeArmourIfNeeded();
            if (bankMeleeArmour > 0) {
                return bankMeleeArmour;
            }
        }

        // 4a. Optioneel in MELEE: Air+Mind runes meenemen om imps 1x met Air Strike te openen.
        if (style == CombatBotConfig.ImpsCombatStyle.MELEE && config.impsMeleeOpeningAirStrike() && !starterSkillBridge) {
            ensureBankWithdrawUnnoted();
            int airCount = getItemQuantity("Air rune");
            if (airCount >= 100) {
                meleeOpenerAirWithdrawAttempts = 0;
            } else if (airCount < 100 && bankHasUsableItemNamed("Air rune")) {
                if (meleeOpenerAirWithdrawAttempts < MELEE_OPENER_RUNE_MAX_WITHDRAW_TRIES) {
                    int before = airCount;
                    Bank.withdraw("Air rune", Integer.MAX_VALUE);
                    meleeOpenerAirWithdrawAttempts++;
                    chatLog("Melee opener: Air withdraw poging " + meleeOpenerAirWithdrawAttempts + "/" + MELEE_OPENER_RUNE_MAX_WITHDRAW_TRIES);
                    boolean inc = waitUntilItemQuantityIncreases("Air rune", before, 2500);
                    if (!inc) {
                        chatLog("[!] Melee opener: Air in inv steeg niet (voor=" + before + ", nu=" + getItemQuantity("Air rune")
                                + ") — check noted withdraw / volle inv");
                    }
                    return antiBan.varyDelay(randomDelay(500, 900));
                }
                chatLog("[!] Melee opener: stop Air withdraw (" + airCount + " in inv, bank heeft nog wel — ga verder i.p.v. oneindige lus)");
            }
            int mindCount = getItemQuantity("Mind rune");
            if (mindCount >= 100) {
                meleeOpenerMindWithdrawAttempts = 0;
            } else if (mindCount < 100 && bankHasUsableItemNamed("Mind rune")) {
                if (meleeOpenerMindWithdrawAttempts < MELEE_OPENER_RUNE_MAX_WITHDRAW_TRIES) {
                    int before = mindCount;
                    Bank.withdraw("Mind rune", Integer.MAX_VALUE);
                    meleeOpenerMindWithdrawAttempts++;
                    chatLog("Melee opener: Mind withdraw poging " + meleeOpenerMindWithdrawAttempts + "/" + MELEE_OPENER_RUNE_MAX_WITHDRAW_TRIES);
                    boolean inc = waitUntilItemQuantityIncreases("Mind rune", before, 2500);
                    if (!inc) {
                        chatLog("[!] Melee opener: Mind in inv steeg niet (voor=" + before + ", nu=" + getItemQuantity("Mind rune") + ")");
                    }
                    return antiBan.varyDelay(randomDelay(500, 900));
                }
                chatLog("[!] Melee opener: stop Mind withdraw — ga verder");
            }
        }

        // 4b. Alleen Law runes voor teleport (bv. Varrock naar GE) - 10 is genoeg
        if (config.impsUseVarrockTeleport() || config.impsUseFaladorTeleport() || config.impsUseLumbridgeTeleport()) {
            int law = getItemQuantity("Law rune");
            if (law >= 10) {
                teleportRuneWithdrawAttempts = 0;
            } else if (!bankHasUsableItemNamed("Law rune")
                    && config.impsTeleportBuyRunes()
                    && getCoinCount() >= config.impsLawRuneBuyPrice() * 15) {
                chatLog("[GE] Te weinig Law runes in bank -> naar GE om te kopen");
                isBuyingLawRunesAtGe = true;
                buyLawRunesStep = 0;
                teleportRuneWithdrawAttempts = 0;
                Bank.close();
                return antiBan.varyDelay(randomDelay(600, 1000));
            } else if (bankHasUsableItemNamed("Law rune")) {
                if (Inventory.getFreeSlots() < 1) {
                    int makeRoom = tryMakeFreeSlotForTeleportLawRunes();
                    if (makeRoom >= 0) {
                        return makeRoom;
                    }
                }
                if (teleportRuneWithdrawAttempts < TELEPORT_LAW_MAX_WITHDRAW_TRIES) {
                    int before = law;
                    teleportRuneWithdrawAttempts++;
                    Bank.withdraw("Law rune", 10);
                    chatLog("Law runes opgehaald (10) voor teleports");
                    if (!waitUntilItemQuantityIncreases("Law rune", before, 2500)) {
                        chatLog("[!] Gear prep: Law withdraw geen effect (voor=" + before + ", nu=" + getItemQuantity("Law rune")
                                + ") — volle inv of noted withdraw?");
                    }
                    return antiBan.varyDelay(randomDelay(500, 900));
                }
                chatLog("[!] Gear prep: stop Law withdraw na " + TELEPORT_LAW_MAX_WITHDRAW_TRIES + " pogingen (law="
                        + getItemQuantity("Law rune") + ") — ga verder met gear");
            }
        }

        // 4c. Beste amulet per style: ophalen uit bank (aantrekken in equip-fase).
        boolean amuletEquipped = Equipment.contains(item -> item != null && item.getName() != null
                && item.getName().toLowerCase().contains("amulet"));
        if (!amuletEquipped) {
            String invAmulet = bestInventoryAmuletForStyle(style);
            if (invAmulet != null) {
                // Al op zak — equip-fase trekt hem aan
            } else {
                String bankAmulet = bestBankAmuletForStyle(style);
                if (bankAmulet != null) {
                    Bank.withdraw(bankAmulet, 1);
                    chatLog(bankAmulet + " opgehaald uit bank");
                    return antiBan.varyDelay(randomDelay(500, 900));
                }
            }
        }

        // 4c2. Ranged: boog + arrows in dezelfde banksessie (equip sluit bank-UI).
        if (style == CombatBotConfig.ImpsCombatStyle.RANGED) {
            int rangedWithdraw = withdrawRangedGearFromBankIfNeeded();
            if (rangedWithdraw > 0) {
                return rangedWithdraw;
            }
        }

        // 4d. Style-armor: batch-withdraw (equip pas na bank-sluit) — imps melee: skip (gewicht)
        int armourTick = impsMeleeSkipsArmour(style) ? bankImpsMeleeArmourIfNeeded()
                : handleStyleArmourAtBank(style);
        if (armourTick > 0) {
            return armourTick;
        }

        // 5. Check of we nu adequate gear hebben (incl. Fire Strike air-gate alleen voor MAGE)
        if (hasAdequateGearForStyle(style)) {
            if (style == CombatBotConfig.ImpsCombatStyle.MELEE) {
                String weaponUpgrade = bestUsableBankUpgrade(meleeWeaponUpgradeOrder(), true);
                if (weaponUpgrade != null) {
                    Bank.withdraw(weaponUpgrade, 1);
                    chatLog("Melee wapen upgrade opgehaald: " + weaponUpgrade);
                    return antiBan.varyDelay(randomDelay(600, 1000));
                }
                if (!impsMeleeSkipsArmour(style)) {
                    armourTick = handleStyleArmourAtBank(style);
                    if (armourTick > 0) {
                        return armourTick;
                    }
                }
            }
            if (style == CombatBotConfig.ImpsCombatStyle.RANGED) {
                String bowUp = firstUsableBetterRangedBowInBank();
                if (bowUp != null) {
                    Bank.withdraw(bowUp, 1);
                    chatLog("Ranged boog upgrade opgehaald: " + bowUp);
                    return antiBan.varyDelay(randomDelay(600, 1000));
                }
                armourTick = handleStyleArmourAtBank(style);
                if (armourTick > 0) {
                    return armourTick;
                }
            }
            if (isFireStrikeAirGateActive() && style == CombatBotConfig.ImpsCombatStyle.MAGE) {
                if (fireStrikeAirDepartureGateFails()) {
                    if (!bankHasRequiredItemsForStyle(style)) {
                        return handleGearShortage(style);
                    }
                    return withdrawMageGear();
                }
            }
            return beginGearPrepEquipPhase(style);
        }

        // 5b. Alleen naar GE/fallback als bank de benodigde items echt niet heeft (zelfde checks als withdraw)
        if (!bankHasRequiredItemsForStyle(style)) {
            return handleGearShortage(style);
        }

        // 6. Pak gear uit bank: juiste staff + runes (Fire Strike + Staff of fire: vooral Air + Mind, geen Fire runes)
        switch (style) {
            case MELEE:
                return withdrawMeleeGear();
            case RANGED:
                return withdrawRangedGear();
            case MAGE:
                return withdrawMageGear();
        }

        // Zou niet bereikt moeten worden
        chatLog("[!] Onbekende combat style: " + style);
        Bank.close();
        gearPrepComplete = true;
        preparingGear = false;
        return antiBan.varyDelay(randomDelay(800, 1200));
    }

    // ===================== GEAR FAILSAFE (3-TIER) =====================

    /**
     * Check of de bank de essentiele items bevat voor de gegeven style.
     * Retourneert false als de bank NIET het ontbrekende item bevat.
     */
    private boolean bankHasRequiredItemsForStyle(CombatBotConfig.ImpsCombatStyle style) {
        switch (style) {
            case MELEE:
                if (hasMeleeWeapon()) return true; // al in inv/equip
                return Bank.contains(item -> {
                    if (item == null || item.getName() == null) return false;
                    String n = item.getName().toLowerCase();
                    return n.contains("scimitar") || n.contains("sword") || n.contains("dagger")
                            || n.contains("mace") || n.contains("axe") || n.contains("halberd")
                            || n.contains("longsword") || n.contains("battleaxe") || n.contains("warhammer");
                });

            case RANGED: {
                boolean haveUsableBow = inventoryOrEquipBestRangedBowProgressRank() < Integer.MAX_VALUE
                        || bankHasUsableRangedBow();
                if (!haveUsableBow) {
                    return false;
                }
                if (getImpsEffectiveArrowCount() >= GEAR_PREP_MIN_ARROWS) {
                    return true;
                }
                return RangedAmmoPreference.bankHasPreferredAmmo(config);
            }

            case MAGE: {
                CombatBotConfig.ImpsMageSpell spell = getActiveMageSpell();
                // Zelfde staff-check als withdrawMageGear: preferred eerst, dan fallback-lijst
                boolean hasStaff = Equipment.contains(item -> item != null && item.getName() != null && item.getName().toLowerCase().contains("staff"))
                        || Inventory.contains(item -> item != null && item.getName() != null && item.getName().toLowerCase().contains("staff"));
                if (!hasStaff) {
                    if (bankHasUsableItemNamed(spell.getPreferredStaff())) return true;
                    String[] staves = {"Staff of air", "Staff of fire", "Staff of water", "Staff of earth", "Mystic air staff", "Mystic fire staff", "Mystic water staff", "Mystic earth staff", "Air staff", "Fire staff", "Water staff", "Earth staff", "Staff"};
                    for (String s : staves) { if (bankHasUsableItemNamed(s)) return true; }
                    return false;
                }
                int minCat = minCatalystRunesForTrip(spell);
                if (isBelowCatalystTripMinimum(getItemQuantity(spell.getCatalystRune()), spell)
                        && !bankHasUsableItemNamed(spell.getCatalystRune())) return false;
                String element = spell.getElementalRune().toLowerCase().replace(" rune", "");
                int minElem = minElementalRunesForTrip(spell);
                if (!hasStaffWithElement(element) && getItemQuantity(spell.getElementalRune()) < minElem
                        && !bankHasUsableItemNamed(spell.getElementalRune())) return false;
                int strictAir = requiredInventoryAirForAccountAutoFireSetup();
                if (strictAir >= 0) {
                    if (!hasStaffWithElement("fire")
                            && !bankHasUsableItemNamed(CombatBotConfig.ImpsMageSpell.FIRE_STRIKE.getPreferredStaff())) {
                        return false;
                    }
                    if (getItemQuantity("Air rune") < strictAir && !bankHasUsableItemNamed("Air rune")) return false;
                } else {
                    int airMin = requiredAirRunesForMageDeparture();
                    if (spell.needsAirRune() && !hasStaffWithElement("air") && getItemQuantity("Air rune") < airMin
                            && !bankHasUsableItemNamed("Air rune")) return false;
                }
                return true;
            }

            default:
                return false;
        }
    }

    /**
     * Bepaal welk ammo/rune item ontbreekt voor GE restock.
     * Alleen als we te weinig in INVENTORY hebben en (bank open en bank heeft het niet).
     * Als we genoeg in inv hebben, of de bank heeft het wel -> niet "missing" (niet naar GE).
     */
    private String getMissingAmmoItem(CombatBotConfig.ImpsCombatStyle style) {
        switch (style) {
            case RANGED:
                if (getImpsEffectiveArrowCount() >= GEAR_PREP_MIN_ARROWS) {
                    return null;
                }
                if (Bank.isOpen() && RangedAmmoPreference.bankHasPreferredAmmo(config)) {
                    return null;
                }
                return RangedAmmoPreference.geBuyItemName(config);
            case MAGE: {
                CombatBotConfig.ImpsMageSpell spell = getActiveMageSpell();
                String element = spell.getElementalRune().toLowerCase().replace(" rune", "");
                boolean hasPreferredElementStaff = hasStaffWithElement(element);
                if (!hasPreferredElementStaff) {
                    if (!Bank.isOpen() || !bankHasUsableItemNamed(spell.getPreferredStaff())) {
                        return spell.getPreferredStaff();
                    }
                }
                int minCat = minCatalystRunesForTrip(spell);
                if (isBelowCatalystTripMinimum(getItemQuantity(spell.getCatalystRune()), spell)) {
                    if (!Bank.isOpen() || !bankHasUsableItemNamed(spell.getCatalystRune()))
                        return spell.getCatalystRune();
                }
                // Air runes eerst prioriteren bij non-wind spells (zeker met Fire-Strike gate)
                int strictAir = requiredInventoryAirForAccountAutoFireSetup();
                int airMin = strictAir >= 0 ? strictAir : requiredAirRunesForMageDeparture();
                if (spell.needsAirRune() && getItemQuantity("Air rune") < airMin) {
                    boolean needAirFromGe = strictAir >= 0 || !hasStaffWithElement("air");
                    if (needAirFromGe && (!Bank.isOpen() || !bankHasUsableItemNamed("Air rune"))) {
                        return "Air rune";
                    }
                }
                int minElem = minElementalRunesForTrip(spell);
                if (!hasStaffWithElement(element) && getItemQuantity(spell.getElementalRune()) < minElem) {
                    try {
                        if (spell.getPreferredStaff() != null && bankHasUsableItemNamed(spell.getPreferredStaff())) {
                            return null;
                        }
                    } catch (Exception ignored) {
                    }
                    if (!Bank.isOpen() || !bankHasUsableItemNamed(spell.getElementalRune()))
                        return spell.getElementalRune();
                }
                return null;
            }
            default:
                return null;
        }
    }

    /**
     * 3-tier failsafe bij ontbrekende gear/ammo:
     * Tier 1: GE Restock (koop ammo/runes als genoeg coins)
     * Tier 2: Fallback combat style (config.impsFallbackStyle())
     * Tier 3: Hard stop (switch naar normal combat)
     */
    private int handleGearShortage(CombatBotConfig.ImpsCombatStyle failedStyle) {
        chatLog("[!] Bank leeg voor " + failedStyle + " gear - failsafe chain starten");

        // Eerst expliciet controleren: hebben we de runes/ammo WEL in de bank? Dan niet naar GE - eerst opnemen
        String missingItem = getMissingAmmoItem(failedStyle);
        boolean forcedGeMissing = false;
        if (forceGeMissingItem != null && !forceGeMissingItem.trim().isEmpty()) {
            missingItem = forceGeMissingItem;
            forcedGeMissing = true;
            chatLog("[!] Force GE override actief voor item: " + missingItem);
            forceGeMissingItem = null;
        }
        CombatBotConfig.ImpsMageSpell failedMageSpell = null;
        boolean mindCatalystMissing = false;
        if (failedStyle == CombatBotConfig.ImpsCombatStyle.MAGE) {
            failedMageSpell = getActiveMageSpell();
            if (failedMageSpell != null) {
                // Te weinig catalyst voor trip-minimum → GE voorkeur vóór melee fallback.
                // Apart: withdraw noted kan inv niet vullen; placeholders kunnen contains "true" geven zonder qty (zie bankStackQuantityIfOpen).
                int catalystCount = getItemQuantity(failedMageSpell.getCatalystRune());
                if (isBelowCatalystTripMinimum(catalystCount, failedMageSpell)) {
                    // Forceer: beschouw Mind rune als missing voor Tier-1 GE flow
                    // (ook als de bank het item wel "contains" maar alleen als noted levert).
                    missingItem = failedMageSpell.getCatalystRune();
                    mindCatalystMissing = true;
                }
            }
        }
        final String missingItemFinal = missingItem;
        if (missingItemFinal != null && Bank.isOpen() && !mindCatalystMissing && !forcedGeMissing) {
            boolean bankHasIt = bankHasUsableItemNamed(missingItemFinal);
            if (bankHasIt) {
                chatLog("Bank heeft " + missingItemFinal + " wel - eerst uit bank opnemen (niet naar GE)");
                return antiBan.varyDelay(randomDelay(600, 1000));
            }
        }

        // === TIER 1a: MAGE — staff + ontbrekende runes in één GE-trip (geen GE→bank→GE loop) ===
        if (failedStyle == CombatBotConfig.ImpsCombatStyle.MAGE && failedMageSpell != null) {
            ArrayList<GeBuySlot> bundle = buildMageGeBuyQueue();
            int coins = getTotalCoinsInvPlusOpenBank();
            shrinkMageGeBuyBundleToBudget(bundle, coins, failedMageSpell);
            trimMageGeBuyBundleOverBudget(bundle, coins, failedMageSpell);
            appendUtilityGeBuysIfAffordable(bundle, coins);
            if (!bundle.isEmpty()) {
                int needGp = estimateMageQueueTotalGp(bundle);
                if (coins >= needGp) {
                    chatLog("[GE] Tier 1 bundel (" + bundle.size() + "): " + summarizeGeBuyQueue(bundle)
                            + " — ~" + needGp + " gp (voorraad " + coins + " gp)");
                    if (Bank.isOpen() && getCoinCount() < needGp && Bank.contains("Coins")) {
                        Bank.withdraw("Coins", needGp + 2000);
                        sleep(350, 600);
                    }
                    geBuyQueue = bundle;
                    geBuyQueueIndex = 0;
                    GeBuySlot first = bundle.get(0);
                    geAmmoItemName = first.itemName;
                    geAmmoQuantity = first.quantity;
                    geAmmoPrice = first.startPricePerUnit;
                    isBuyingAmmoAtGe = true;
                    buyAmmoStep = 0;
                    if (Bank.isOpen()) {
                        Bank.close();
                        sleep(280, 500);
                    }
                    return antiBan.varyDelay(randomDelay(450, 800));
                }
                chatLog("[!] Tier 1 bundel: ~" + needGp + " gp nodig, " + coins + " gp beschikbaar");
                boolean wantMind = false;
                for (GeBuySlot s : bundle) {
                    if (s.itemName.equalsIgnoreCase("Mind rune")) {
                        wantMind = true;
                        break;
                    }
                }
                if (wantMind) {
                    chatLog("[~] Probeer imp-loot te verkopen op GE voor coins (Mind runes in bundel)");
                    isSellingAtGe = true;
                    geSellStep = 0;
                    return antiBan.varyDelay(randomDelay(700, 1100));
                }
            }
        }

        // === TIER 1: GE Restock (enkel item; o.a. RANGED of legacy MAGE) ===
        if (missingItem != null) {
            geBuyQueue = null;
            geBuyQueueIndex = 0;
            // In Imps MAGE is de "catalyst rune" typisch Mind rune — grote stack om bank-loops te beperken.
            CombatBotConfig.ImpsMageSpell spell = getActiveMageSpell();
            boolean isMindRuneMissing = spell != null && missingItem.equalsIgnoreCase(spell.getCatalystRune());
            boolean isAirRuneMissing = missingItem.equalsIgnoreCase("Air rune");
            boolean isStaffBuy = missingItem.toLowerCase(Locale.ROOT).contains("staff");

            int price = config.impsAmmoRestockPrice();
            int coins = getTotalCoinsInvPlusOpenBank();

            int desiredQty;
            int unitPriceForGe;
            int cost;
            if (isStaffBuy) {
                desiredQty = 1;
                unitPriceForGe = estimateStaffGeStartPrice(missingItem);
                cost = unitPriceForGe + 300;
            } else {
                unitPriceForGe = price;
                if (isAirRuneMissing) {
                    int costFor2k = 2000 * price;
                    desiredQty = coins >= costFor2k ? 2000 : 1000;
                } else if (isMindRuneMissing && spell != null) {
                    int tripMinBuy = effectiveCatalystTripMinimum(spell);
                    int maxAff = price > 0 ? coins / price : 0;
                    desiredQty = maxAff >= tripMinBuy ? Math.min(1000, maxAff) : tripMinBuy;
                } else if (isMindRuneMissing) {
                    desiredQty = 1000;
                } else {
                    desiredQty = 500;
                }
                cost = desiredQty * price;
            }

            ArrayList<GeBuySlot> queue = new ArrayList<>();
            queue.add(new GeBuySlot(missingItem, desiredQty, unitPriceForGe));
            appendUtilityGeBuysIfAffordable(queue, coins);
            int totalQueueCost = estimateMageQueueTotalGp(queue);

            if (coins >= cost) {
                chatLog("[GE] Tier 1: Naar GE om " + missingItem + " te kopen ("
                        + (isStaffBuy ? ("1× @" + unitPriceForGe + " gp") : (desiredQty + "x @" + price + " gp")) + ")");
                if (Bank.isOpen() && getCoinCount() < totalQueueCost && Bank.contains("Coins")) {
                    int before = getCoinCount();
                    Bank.withdraw("Coins", totalQueueCost + 1000);
                    sleep(400, 700);
                    int after = getCoinCount();
                    if (after <= before) {
                        chatLog("[!] GE failsafe: coins withdraw lijkt niet gelukt (before=" + before + ", after=" + after + ") -> retry");
                        Bank.withdraw("Coins", totalQueueCost + 1000);
                        sleep(500, 800);
                    }
                }
                geBuyQueue = queue;
                geBuyQueueIndex = 0;
                GeBuySlot first = queue.get(0);
                geAmmoItemName = first.itemName;
                geAmmoQuantity = first.quantity;
                geAmmoPrice = first.startPricePerUnit;
                isBuyingAmmoAtGe = true;
                buyAmmoStep = 0;
                if (Bank.isOpen()) {
                    Bank.close();
                    sleep(300, 550);
                }
                if (queue.size() > 1) {
                    chatLog("[GE] Extra utility aankopen toegevoegd: " + summarizeGeBuyQueue(queue));
                }
                return antiBan.varyDelay(randomDelay(500, 850));
            }

            // Geen coins voor Air runes? Probeer eerst Air-Strike fallback (Wind Strike + Air staff).
            if (isAirRuneMissing) {
                if (shouldUseAutoFireStrikeUpgrade()) {
                    chatLog("[AutoMage] Air runes tekort voor Fire Strike en coins te laag (" + coins + " < " + cost + "). Verkoop loot eerst.");
                    isSellingAtGe = true;
                    geSellStep = 0;
                    isBuyingAmmoAtGe = false;
                    geBuyQueue = null;
                    geBuyQueueIndex = 0;
                    return antiBan.varyDelay(randomDelay(800, 1200));
                }
                boolean airStaffEquipped = hasStaffWithElement("air");
                boolean airStaffInInv = Inventory.contains(item ->
                        item != null && item.getName() != null
                                && item.getName().toLowerCase().contains("air")
                                && item.getName().toLowerCase().contains("staff"));

                if (!airStaffEquipped && airStaffInInv) {
                    forcedMageSpell = CombatBotConfig.ImpsMageSpell.WIND_STRIKE;
                    equipGear(CombatBotConfig.ImpsCombatStyle.MAGE);
                    chatLog("[~] Te weinig coins voor Air runes -> Wind Strike + Air staff (inventory)");
                    return antiBan.varyDelay(randomDelay(700, 1100));
                }

                if (!airStaffEquipped && !airStaffInInv && Bank.isOpen()) {
                    String[] airStaves = {"Staff of air", "Mystic air staff", "Air staff"};
                    for (String s : airStaves) {
                        if (Bank.contains(s)) {
                            forcedMageSpell = CombatBotConfig.ImpsMageSpell.WIND_STRIKE;
                            Bank.withdraw(s, 1);
                            chatLog("[~] Te weinig coins voor Air runes -> Air staff ophalen voor Wind Strike");
                            return antiBan.varyDelay(randomDelay(700, 1100));
                        }
                    }
                }

                if (airStaffEquipped || airStaffInInv) {
                    forcedMageSpell = CombatBotConfig.ImpsMageSpell.WIND_STRIKE;
                    preparingGear = true;
                    gearPrepComplete = false;
                    chatLog("[~] Te weinig coins voor Air runes -> fallback naar Wind Strike");
                    return antiBan.varyDelay(randomDelay(700, 1100));
                }
            }

            if (isStaffBuy && shouldUseAutoFireStrikeUpgrade()) {
                chatLog("[AutoMage] Fire staff ontbreekt en coins te laag (" + coins + " < " + cost + "). Verkoop loot eerst.");
                isSellingAtGe = true;
                geSellStep = 0;
                isBuyingAmmoAtGe = false;
                geBuyQueue = null;
                geBuyQueueIndex = 0;
                return antiBan.varyDelay(randomDelay(800, 1200));
            }

            // Geen coins voor Mind runes? Eerst imp-loot verkopen op GE en daarna opnieuw proberen.
            if (isMindRuneMissing) {
                chatLog("[!] Mind runes ontbreken en coins zijn te laag (" + coins + " < " + cost + "). Sell imp loot op GE -> daarna Mind runes kopen.");
                isSellingAtGe = true;
                geSellStep = 0;
                isBuyingAmmoAtGe = false;
                geBuyQueue = null;
                geBuyQueueIndex = 0;
                return antiBan.varyDelay(randomDelay(800, 1200));
            }

            chatLog("[!] Tier 1 gefaald: niet genoeg coins (" + coins + " < " + cost + ") voor GE restock");
        }

        // === TIER 2: Fallback combat style ===
        CombatBotConfig.ImpsCombatStyle fallback = config.impsFallbackStyle();
        if (fallback != failedStyle && !fallbackStyleActive) {
            chatLog("[~] Tier 2: Fallback naar " + fallback + " (was: " + failedStyle + ")");
            fallbackStyleActive = true;
            effectiveStyle = fallback;

            // Check of fallback gear al beschikbaar is
            if (hasAdequateGearForStyle(fallback)) {
                int prepDone = tryCompleteGearPreparation(fallback);
                if (prepDone == -1) {
                    chatLog("[OK] Fallback " + fallback + " gear al aanwezig!");
                    return antiBan.varyDelay(randomDelay(800, 1200));
                }
                if (prepDone > 0) {
                    return prepDone;
                }
            }

            if (bankHasRequiredItemsForStyle(fallback)) {
                chatLog("Fallback " + fallback + " gear in bank gevonden, ophalen...");
                // Laat handleGearPreparation opnieuw draaien met de nieuwe effectiveStyle
                return antiBan.varyDelay(randomDelay(600, 1000));
            }

            chatLog("[!] Tier 2 gefaald: ook geen " + fallback + " gear in bank");
        } else if (fallback == failedStyle) {
            chatLog(" Fallback style is hetzelfde als primary (" + failedStyle + "), skip Tier 2");
        }

        // === TIER 3: Hard stop ===
        chatLog("[X] HARD STOP: Geen gear voor " + failedStyle + " of fallback " + config.impsFallbackStyle()
                + " - switch naar normal combat! Zorg voor gear of coins.");
        paint.setCurrentStatus("[X] Imps: Geen gear beschikbaar! Switching...");
        if (Bank.isOpen()) Bank.close();
        gearPrepComplete = true;
        preparingGear = false;
        shouldSwitchToNormal = true;
        return 2000;
    }

    /**
     * GE ammo/rune restock flow (Tier 1 failsafe).
     * Gebruikt GeRestockHelper voor de daadwerkelijke aankoop.
     */
    private int handleBuyAmmoAtGe(IPlayer local) {
        WorldPoint myPos = local.getWorldLocation();

        switch (buyAmmoStep) {
            case 0: // Loop naar GE
                if (Bank.isOpen()) {
                    Bank.close();
                    sleep(400, 700);
                }
                if (myPos.distanceTo(GE_LOCATION) <= 8) {
                    buyAmmoStep = 1;
                    return randomDelay(600, 1000);
                }
                paint.setCurrentStatus("[GE] Ammo restock: -> Grand Exchange");
                long now = System.currentTimeMillis();
                if ((now - lastWalkClickTime) >= GE_WALK_RECLICK_MIN_MS) {
                    MovementHelper.walkTo(GE_LOCATION);
                    lastWalkClickTime = now;
                }
                return antiBan.varyDelay(randomDelay((int) GE_WALK_RECLICK_MIN_MS, (int) GE_WALK_RECLICK_MAX_MS));

            case 1: // Koop via GeRestockHelper (optioneel wachtrij: staff + runes in één trip)
                GeRestockHelper.RestockResult result;
                if (geBuyQueue != null && !geBuyQueue.isEmpty()) {
                    GeBuySlot slot = geBuyQueue.get(geBuyQueueIndex);
                    paint.setCurrentStatus("[GE] Bundel " + (geBuyQueueIndex + 1) + "/" + geBuyQueue.size()
                            + ": " + slot.quantity + "× " + slot.itemName);
                    result = GeRestockHelper.buyWithEscalation(
                            slot.itemName,
                            slot.quantity,
                            slot.startPricePerUnit,
                            () -> config.botEnabled()
                    );
                } else {
                    paint.setCurrentStatus("[GE] Ammo restock: " + geAmmoQuantity + "x " + geAmmoItemName);
                    result = GeRestockHelper.buyWithEscalation(
                            geAmmoItemName,
                            geAmmoQuantity,
                            geAmmoPrice,
                            () -> config.botEnabled()
                    );
                }

                if (!config.botEnabled()) {
                    isBuyingAmmoAtGe = false;
                    buyAmmoStep = 0;
                    geBuyQueue = null;
                    geBuyQueueIndex = 0;
                    return 1000;
                }

                switch (result) {
                    case SUCCESS:
                        if (geBuyQueue != null && !geBuyQueue.isEmpty()) {
                            GeBuySlot done = geBuyQueue.get(geBuyQueueIndex);
                            chatLog("[OK] GE: " + done.quantity + "× " + done.itemName);
                            geBuyQueueIndex++;
                            if (geBuyQueueIndex < geBuyQueue.size()) {
                                GeBuySlot next = geBuyQueue.get(geBuyQueueIndex);
                                geAmmoItemName = next.itemName;
                                geAmmoQuantity = next.quantity;
                                geAmmoPrice = next.startPricePerUnit;
                                return randomDelay(280, 520);
                            }
                            chatLog("[OK] GE bundel klaar (" + geBuyQueue.size() + "): " + summarizeGeBuyQueue(geBuyQueue));
                            geBuyQueue = null;
                            geBuyQueueIndex = 0;
                        } else {
                            chatLog("[OK] GE restock succesvol: " + geAmmoQuantity + "x " + geAmmoItemName);
                        }
                        maybeMarkUtilityGeBuysCompleteForAccount();
                        isBuyingAmmoAtGe = false;
                        buyAmmoStep = 0;
                        preparingGear = true;
                        gearPrepComplete = false;
                        return antiBan.varyDelay(randomDelay(550, 950));

                    case FAILED:
                        if (geBuyQueue != null && !geBuyQueue.isEmpty()) {
                            GeBuySlot failedSlot = geBuyQueue.get(geBuyQueueIndex);
                            chatLog("[!] GE bundel gefaald bij " + failedSlot.itemName + " -> fallback");
                            geBuyQueue = null;
                            geBuyQueueIndex = 0;
                        } else {
                            chatLog("[!] GE restock gefaald voor " + geAmmoItemName + " -> probeer fallback");
                        }
                        isBuyingAmmoAtGe = false;
                        buyAmmoStep = 0;
                        CombatBotConfig.ImpsCombatStyle fallback = config.impsFallbackStyle();
                        CombatBotConfig.ImpsCombatStyle currentStyle = getEffectiveStyle();
                        if (fallback != currentStyle && !fallbackStyleActive) {
                            chatLog("[~] Tier 2: Fallback naar " + fallback);
                            fallbackStyleActive = true;
                            effectiveStyle = fallback;
                            preparingGear = true;
                            gearPrepComplete = false;
                            return antiBan.varyDelay(randomDelay(600, 1000));
                        }
                        chatLog("[X] HARD STOP: GE restock en fallback gefaald -> switching naar normal combat!");
                        paint.setCurrentStatus("[X] Imps: Geen ammo beschikbaar! Switching...");
                        shouldSwitchToNormal = true;
                        return 2000;

                    case GE_NOT_AVAILABLE:
                        chatLog("[!] GE niet beschikbaar - retry...");
                        return antiBan.varyDelay(randomDelay(2000, 4000));
                }
                break;
        }
        return 1000;
    }

    /**
     * Check of er gear in de INVENTORY zit (niet equipped) voor de gekozen style.
     */
    private boolean hasGearInInventory(CombatBotConfig.ImpsCombatStyle style) {
        switch (style) {
            case MELEE: {
                int equippedRank = getBestEquippedMeleeWeaponRank();
                int invBetterRank = getBestInventoryMeleeWeaponRankBetterThan(equippedRank);
                return invBetterRank >= 0;
            }
            case RANGED: {
                int equippedBowRank = equippedRangedBowProgressRankOnly();
                if (equippedBowRank >= Integer.MAX_VALUE) {
                    return Inventory.contains(item -> item != null && item.getName() != null
                            && isRangedBowItemName(item.getName()) && item.hasAction("Wield")
                            && canUseRangedBow(item.getName()));
                }
                for (IInventoryItem item : Inventory.getAll()) {
                    if (item == null || item.getName() == null || !item.hasAction("Wield")) {
                        continue;
                    }
                    if (!isRangedBowItemName(item.getName()) || !canUseRangedBow(item.getName())) {
                        continue;
                    }
                    if (rangedBowProgressRank(item.getName()) < equippedBowRank) {
                        return true;
                    }
                }
                return false;
            }
            case MAGE:
                return hasMageStaffUpgradeInInventory();
            default:
                return false;
        }
    }

    /**
     * Voor MAGE willen we alleen "gear in inventory" melden als er echt iets te equippen valt:
     * - preferred/effectieve elementstaff nog niet gedragen, OF
     * - er is helemaal geen staff gedragen.
     * Dit voorkomt loops waarbij een extra staff in inv blijft bestaan terwijl setup al correct is.
     */
    private boolean hasMageStaffUpgradeInInventory() {
        CombatBotConfig.ImpsMageSpell spell = getActiveMageSpell();
        String preferred = spell != null ? spell.getPreferredStaff() : null;
        String elementalRune = spell != null ? spell.getElementalRune() : null;
        String element = elementalRune != null ? elementalRune.toLowerCase(Locale.ROOT).replace(" rune", "") : null;

        boolean preferredEquipped = preferred != null && Equipment.contains(item ->
                item != null && item.getName() != null && item.getName().equalsIgnoreCase(preferred));
        boolean elementCoveredByEquippedStaff = element != null && !element.isEmpty() && Equipment.contains(item ->
                item != null
                        && item.getName() != null
                        && item.getName().toLowerCase(Locale.ROOT).contains("staff")
                        && item.getName().toLowerCase(Locale.ROOT).contains(element));
        boolean anyStaffEquipped = Equipment.contains(item ->
                item != null && item.getName() != null && item.getName().toLowerCase(Locale.ROOT).contains("staff"));

        if (preferredEquipped || elementCoveredByEquippedStaff) {
            return false;
        }
        if (!anyStaffEquipped) {
            return Inventory.contains(item ->
                    item != null && item.getName() != null && item.getName().toLowerCase(Locale.ROOT).contains("staff"));
        }
        if (preferred != null && !preferred.trim().isEmpty()) {
            boolean preferredInInv = Inventory.contains(item ->
                    item != null && item.getName() != null && item.getName().equalsIgnoreCase(preferred));
            if (preferredInInv) return true;
        }
        if (element != null && !element.isEmpty()) {
            return Inventory.contains(item ->
                    item != null
                            && item.getName() != null
                            && item.getName().toLowerCase(Locale.ROOT).contains("staff")
                            && item.getName().toLowerCase(Locale.ROOT).contains(element));
        }
        return false;
    }

    /** Kleinere rank = beter (volgens {@link #meleeWeaponUpgradeOrder()}); -1 = niet in lijst/geen bruikbaar melee-weapon. */
    private int meleeWeaponRank(String weaponName) {
        if (weaponName == null || weaponName.trim().isEmpty()) {
            return -1;
        }
        String[] order = meleeWeaponUpgradeOrder();
        for (int i = 0; i < order.length; i++) {
            if (order[i].equalsIgnoreCase(weaponName.trim())) {
                return i;
            }
        }
        return -1;
    }

    /** Beste (laagste) melee-rank in equipment; als niets bekend is, heel laag prioriteit. */
    private int getBestEquippedMeleeWeaponRank() {
        int best = Integer.MAX_VALUE;
        try {
            var equipped = Equipment.getAll(item ->
                    item != null
                            && item.getName() != null
                            && isCombatMeleeWeaponName(item.getName().toLowerCase(Locale.ROOT)));
            if (equipped != null) {
                for (var eq : equipped) {
                    if (eq == null || eq.getName() == null) continue;
                    int rank = meleeWeaponRank(eq.getName());
                    if (rank >= 0 && rank < best) {
                        best = rank;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return best == Integer.MAX_VALUE ? Integer.MAX_VALUE : best;
    }

    /** Beste inventory-melee die écht een upgrade is t.o.v. equippedRank. */
    private int getBestInventoryMeleeWeaponRankBetterThan(int equippedRank) {
        int best = Integer.MAX_VALUE;
        String[] order = meleeWeaponUpgradeOrder();
        for (String name : order) {
            IInventoryItem item = Inventory.getFirst(name);
            if (item == null || !item.hasAction("Wield") || !canUseMeleeTierItem(name, true)) {
                continue;
            }
            int rank = meleeWeaponRank(name);
            if (rank < 0) continue;
            if (equippedRank != Integer.MAX_VALUE && rank >= equippedRank) {
                continue; // geen upgrade, dus niet equippen
            }
            if (rank < best) {
                best = rank;
            }
        }
        return best == Integer.MAX_VALUE ? -1 : best;
    }

    /**
     * Equip gear uit inventory (Wield/Wear) + selecteer autocast spell voor MAGE.
     */
    private void equipGear(CombatBotConfig.ImpsCombatStyle style) {
        IInventoryItem weapon = null;

        if (style == CombatBotConfig.ImpsCombatStyle.MAGE) {
            clearEquippedMeleeWeaponBlockingMage();
            // Deterministische staff selectie i.p.v. "Inventory.getFirst" (kan heen-en-weer wisselen).
            CombatBotConfig.ImpsMageSpell spell = getActiveMageSpell();
            String preferred = spell != null ? spell.getPreferredStaff() : null;

            String elementalRune = spell != null ? spell.getElementalRune() : null;
            String element = elementalRune != null ? elementalRune.toLowerCase().replace(" rune", "") : null;
            boolean preferredEquipped = preferred != null && Equipment.contains(item ->
                    item != null && item.getName() != null && item.getName().equalsIgnoreCase(preferred));
            boolean elementCoveredByEquippedStaff = element != null && !element.isEmpty() && Equipment.contains(item ->
                    item != null
                            && item.getName() != null
                            && item.getName().toLowerCase().contains("staff")
                            && item.getName().toLowerCase().contains(element));
            boolean anyStaffEquipped = Equipment.contains(item ->
                    item != null && item.getName() != null && item.getName().toLowerCase().contains("staff"));

            // Voorkom togglen tussen staves: als preferred/equipped staff al goed is, niet opnieuw wielden.
            if (preferredEquipped || elementCoveredByEquippedStaff) {
                selectAutocastSpell(getActiveMageSpell());
                return;
            }

            // 1) Preferred staff exact (meest stabiel)
            if (preferred != null && !preferred.trim().isEmpty()) {
                weapon = Inventory.getFirst(item ->
                        item != null && item.getName() != null && item.getName().equalsIgnoreCase(preferred));
            }
            // 2) Staff die het elemental dekt
            if (weapon == null && element != null && !element.isEmpty()) {
                String finalElement = element; // effectively final
                weapon = Inventory.getFirst(item ->
                        item != null && item.getName() != null
                                && item.getName().toLowerCase().contains(finalElement)
                                && item.getName().toLowerCase().contains("staff"));
            }
            // 3) Elke staff (laatste fallback) alleen als er nog geen staff equipped is.
            if (weapon == null && !anyStaffEquipped) {
                weapon = Inventory.getFirst(item ->
                        item != null && item.getName() != null && item.getName().toLowerCase().contains("staff"));
            }

            debugLog("equipGear[MAGE]: gekozen staff=" + (weapon != null ? weapon.getName() : "null")
                    + ", preferred=" + preferred + ", element=" + element);
        } else {
            if (style == CombatBotConfig.ImpsCombatStyle.MELEE) {
                String[] meleeWeapons = {
                        "Dragon scimitar", "Rune scimitar", "Adamant scimitar", "Mithril scimitar", "Black scimitar",
                        "Dragon longsword", "Rune longsword", "Adamant longsword", "Mithril longsword", "Black longsword",
                        "Dragon sword", "Rune sword", "Adamant sword", "Mithril sword", "Black sword",
                        "Dragon battleaxe", "Rune battleaxe", "Dragon dagger", "Rune dagger",
                        "Steel scimitar", "Steel longsword", "Steel sword",
                        "Iron scimitar", "Iron longsword", "Iron sword",
                        "Bronze scimitar", "Bronze longsword", "Bronze sword"
                };
                for (String name : meleeWeapons) {
                    IInventoryItem candidate = Inventory.getFirst(name);
                    if (candidate != null && candidate.hasAction("Wield") && canUseMeleeTierItem(name, true)) {
                        weapon = candidate;
                        break;
                    }
                }
            } else if (style == CombatBotConfig.ImpsCombatStyle.RANGED) {
                int bestRank = Integer.MAX_VALUE;
                for (IInventoryItem item : Inventory.getAll()) {
                    if (item == null || item.getName() == null || !item.hasAction("Wield")) {
                        continue;
                    }
                    if (!isRangedBowItemName(item.getName()) || !canUseRangedBow(item.getName())) {
                        continue;
                    }
                    int r = rangedBowProgressRank(item.getName());
                    if (r < bestRank) {
                        bestRank = r;
                        weapon = item;
                    }
                }
            }
        }
        if (weapon != null) {
            if (!InventoryEquipHelper.tryWieldOrWear(config, weapon)) {
                debugLog("equipGear: geen ruimte voor " + weapon.getName()
                        + " (need " + InventoryEquipHelper.minFreeSlotsToWield(weapon.getName())
                        + " free, have " + InventoryEquipHelper.freeSlots() + ")");
                return;
            }
            chatLog("Gear equipped: " + weapon.getName());
            sleep(800, 1200);

            // Na het equippen van een staff: selecteer autocast spell
            if (style == CombatBotConfig.ImpsCombatStyle.MAGE) {
                selectAutocastSpell(getActiveMageSpell());
            }

            if (style == CombatBotConfig.ImpsCombatStyle.MAGE) {
                lastMageStaffEquipTimeMs = System.currentTimeMillis();
            }
        }
    }

    /**
     * Pak het beste melee wapen uit de bank. Lijst van beste naar slechtste.
     */
    private int withdrawMeleeGear() {
        String[] meleeWeapons = meleeWeaponUpgradeOrder();
        String weaponUpgrade = bestUsableBankUpgrade(meleeWeapons, true);
        if (weaponUpgrade != null) {
            Bank.withdraw(weaponUpgrade, 1);
            chatLog("Melee wapen upgrade opgehaald: " + weaponUpgrade);
            return antiBan.varyDelay(randomDelay(600, 1000));
        }
        if (!hasMeleeWeapon()) {
            for (String weapon : meleeWeapons) {
                if (Bank.contains(weapon) && canUseMeleeTierItem(weapon, true)) {
                    Bank.withdraw(weapon, 1);
                    chatLog("Melee wapen opgehaald: " + weapon);
                    return antiBan.varyDelay(randomDelay(600, 1000));
                }
            }
            chatLog("[!] Geen melee wapen gevonden in bank");
            return antiBan.varyDelay(randomDelay(400, 800));
        }

        int armourTick = impsMeleeSkipsArmour() ? bankImpsMeleeArmourIfNeeded()
                : handleStyleArmourAtBank(CombatBotConfig.ImpsCombatStyle.MELEE);
        if (armourTick > 0) {
            return armourTick;
        }

        equipGear(CombatBotConfig.ImpsCombatStyle.MELEE);
        if (!impsMeleeSkipsArmour() && tryEquipStyleArmour(CombatBotConfig.ImpsCombatStyle.MELEE)) {
            return antiBan.varyDelay(randomDelay(650, 1050));
        }
        int prepDone = tryCompleteGearPreparation(CombatBotConfig.ImpsCombatStyle.MELEE);
        if (prepDone == -1) {
            chatLog("[OK] Melee wapen al aanwezig (uitgerust, geen armor — imps melee)");
            return antiBan.varyDelay(randomDelay(300, 600));
        }
        if (prepDone > 0) {
            return prepDone;
        }
        chatLog("[OK] Melee wapen al aanwezig (geen armor — imps melee)");
        return antiBan.varyDelay(randomDelay(300, 600));
    }

    private String[] meleeWeaponUpgradeOrder() {
        return new String[] {
                "Dragon scimitar", "Rune scimitar", "Adamant scimitar", "Mithril scimitar", "Black scimitar",
                "Dragon longsword", "Rune longsword", "Adamant longsword", "Mithril longsword", "Black longsword",
                "Dragon sword", "Rune sword", "Adamant sword", "Mithril sword", "Black sword",
                "Dragon battleaxe", "Rune battleaxe",
                "Dragon dagger", "Rune dagger",
                "Steel scimitar", "Steel longsword", "Steel sword",
                "Iron scimitar", "Iron longsword", "Iron sword",
                "Bronze scimitar", "Bronze longsword", "Bronze sword"
        };
    }

    private boolean isMeleeRecoveryGearReady() {
        return hasMeleeWeapon();
    }

    /** HEAD/BODY/LEGS/SHIELD: live inv+equip, snapshot-slot, of melee-plate/hele in dat slot. */
    private boolean meleeArmorSlotReady(String slotName, String... itemNames) {
        String rsn = BankSnapshotPlanner.currentDisplayName();
        if (EquipmentStylePlanner.slotMatchesStyle(rsn, slotName, EquipmentStylePlanner.ArmorStyle.MELEE)) {
            return true;
        }
        return hasAnyInventoryOrEquipped(itemNames);
    }

    private boolean withdrawBestMeleeArmorPieceFromBank() {
        String[][] groups = {
                {"Rune full helm", "Rune med helm", "Adamant full helm", "Adamant med helm", "Mithril full helm", "Mithril med helm", "Black full helm", "Black med helm", "Steel full helm", "Steel med helm", "Iron full helm", "Iron med helm", "Bronze full helm", "Bronze med helm"},
                {"Rune platebody", "Adamant platebody", "Mithril platebody", "Black platebody", "Steel platebody", "Iron platebody", "Bronze platebody", "Rune chainbody", "Adamant chainbody", "Mithril chainbody", "Black chainbody", "Steel chainbody", "Iron chainbody", "Bronze chainbody"},
                {"Rune platelegs", "Adamant platelegs", "Mithril platelegs", "Black platelegs", "Steel platelegs", "Iron platelegs", "Bronze platelegs", "Rune plateskirt", "Adamant plateskirt", "Mithril plateskirt", "Black plateskirt", "Steel plateskirt", "Iron plateskirt", "Bronze plateskirt"},
                {"Rune kiteshield", "Adamant kiteshield", "Mithril kiteshield", "Black kiteshield", "Steel kiteshield", "Iron kiteshield", "Bronze kiteshield", "Wooden shield"}
        };
        String[] labels = {"helm", "body", "legs", "shield"};
        for (int i = 0; i < groups.length; i++) {
            String upgrade = bestUsableBankUpgrade(groups[i], false);
            if (upgrade != null) {
                Bank.withdraw(upgrade, 1);
                chatLog("Melee " + labels[i] + " upgrade opgehaald: " + upgrade);
                return true;
            }
        }
        return false;
    }

    private String bestUsableBankUpgrade(String[] orderedBestFirst, boolean weapon) {
        int currentBest = bestInventoryOrEquippedTier(orderedBestFirst);
        for (String name : orderedBestFirst) {
            int tier = meleeTier(name);
            if (tier <= currentBest) {
                continue;
            }
            if (canUseMeleeTierItem(name, weapon) && Bank.contains(name)) {
                return name;
            }
        }
        return null;
    }

    private int bestInventoryOrEquippedTier(String[] names) {
        int best = -1;
        for (String name : names) {
            if (Inventory.contains(name) || Equipment.contains(name)) {
                best = Math.max(best, meleeTier(name));
            }
        }
        String rsn = BankSnapshotPlanner.currentDisplayName();
        best = Math.max(best, EquipmentStylePlanner.bestMeleeTierFromSnapshot(rsn, names, this::meleeTier));
        return best;
    }

    private boolean canUseMeleeTierItem(String name, boolean weapon) {
        if (!weapon && name != null && StyleArmourBankHelper.requiresDragonSlayerToWear(name)) {
            return StyleArmourBankHelper.canWear(CombatBotConfig.ImpsCombatStyle.MELEE, name);
        }
        int required = meleeTierRequiredLevel(name);
        try {
            return Skills.getLevel(weapon ? Skill.ATTACK : Skill.DEFENCE) >= required;
        } catch (Exception e) {
            return required <= 1;
        }
    }

    private int meleeTierRequiredLevel(String name) {
        int tier = meleeTier(name);
        switch (tier) {
            case 7: return 60; // Dragon
            case 6: return 40; // Rune
            case 5: return 30; // Adamant
            case 4: return 20; // Mithril
            case 3: return 10; // Black
            case 2: return 5;  // Steel
            default: return 1; // Iron/Bronze/Wood
        }
    }

    private int meleeTier(String name) {
        if (name == null) return -1;
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("dragon")) return 7;
        if (n.contains("rune")) return 6;
        if (n.contains("adamant")) return 5;
        if (n.contains("mithril")) return 4;
        if (n.contains("black")) return 3;
        if (n.contains("steel")) return 2;
        if (n.contains("iron")) return 1;
        if (n.contains("bronze") || n.contains("wooden")) return 0;
        return -1;
    }

    private boolean wearBestMeleeArmorFromInventory() {
        String[][] groups = {
                {"Rune full helm", "Rune med helm", "Adamant full helm", "Adamant med helm", "Mithril full helm", "Mithril med helm", "Black full helm", "Black med helm", "Steel full helm", "Steel med helm", "Iron full helm", "Iron med helm", "Bronze full helm", "Bronze med helm"},
                {"Rune platebody", "Adamant platebody", "Mithril platebody", "Black platebody", "Steel platebody", "Iron platebody", "Bronze platebody", "Rune chainbody", "Adamant chainbody", "Mithril chainbody", "Black chainbody", "Steel chainbody", "Iron chainbody", "Bronze chainbody"},
                {"Rune platelegs", "Adamant platelegs", "Mithril platelegs", "Black platelegs", "Steel platelegs", "Iron platelegs", "Bronze platelegs", "Rune plateskirt", "Adamant plateskirt", "Mithril plateskirt", "Black plateskirt", "Steel plateskirt", "Iron plateskirt", "Bronze plateskirt"},
                {"Rune kiteshield", "Adamant kiteshield", "Mithril kiteshield", "Black kiteshield", "Steel kiteshield", "Iron kiteshield", "Bronze kiteshield", "Wooden shield"}
        };
        for (String[] group : groups) {
            for (String name : group) {
                IInventoryItem item = Inventory.getFirst(name);
                if (item != null && canUseMeleeTierItem(name, false)) {
                    String action = item.hasAction("Wear") ? "Wear" : (item.hasAction("Wield") ? "Wield" : "Wear");
                    InventoryActionHelper.interact(config, item, action);
                    chatLog("Melee armor aangedaan: " + name);
                    return true;
                }
            }
        }
        return false;
    }

    private String firstBankMatch(String... names) {
        for (String n : names) {
            if (Bank.contains(n)) return n;
        }
        return null;
    }

    private boolean hasAnyInventoryOrEquipped(String... names) {
        String rsn = BankSnapshotPlanner.currentDisplayName();
        for (String n : names) {
            if (n == null || n.isEmpty()) {
                continue;
            }
            if (Inventory.contains(n) || Equipment.contains(n)) {
                return true;
            }
            if (EquipmentSnapshotPlanner.hasEquippedItem(rsn, n)) {
                return true;
            }
        }
        return false;
    }

    /** Zonder crossbow — alleen bogen (substring "bow" + geen "crossbow"). */
    private static boolean isRangedBowItemName(String name) {
        if (name == null) {
            return false;
        }
        String l = name.toLowerCase(Locale.ROOT);
        if (!l.contains("bow")) {
            return false;
        }
        return !l.contains("crossbow");
    }

    /** Beste eerst; parallel met {@link #RANGED_BOW_LEVEL_REQ}. */
    private static final String[] RANGED_BOW_BEST_FIRST = {
            "Magic shortbow (i)", "Magic shortbow", "Magic longbow",
            "Yew shortbow", "Yew longbow",
            "Maple shortbow", "Maple longbow",
            "Willow shortbow", "Willow longbow",
            "Oak shortbow", "Oak longbow",
            "Shortbow", "Longbow"
    };

    private static final int[] RANGED_BOW_LEVEL_REQ = {
            50, 50, 50,
            40, 40,
            30, 30,
            20, 20,
            5, 5,
            1, 1
    };

    private static int inferBowRangedRequirement(String lower) {
        if (lower.contains("magic")) {
            return 50;
        }
        if (lower.contains("yew")) {
            return 40;
        }
        if (lower.contains("maple")) {
            return 30;
        }
        if (lower.contains("willow")) {
            return 20;
        }
        if (lower.contains("oak")) {
            return 5;
        }
        if (lower.contains("training")) {
            return 1;
        }
        return 1;
    }

    private int bowRequiredRangedLevel(String name) {
        if (name == null) {
            return 99;
        }
        String t = name.trim();
        for (int i = 0; i < RANGED_BOW_BEST_FIRST.length; i++) {
            if (RANGED_BOW_BEST_FIRST[i].equalsIgnoreCase(t)) {
                return RANGED_BOW_LEVEL_REQ[i];
            }
        }
        return inferBowRangedRequirement(name.toLowerCase(Locale.ROOT));
    }

    private boolean canUseRangedBow(String name) {
        try {
            return Skills.getLevel(Skill.RANGED) >= bowRequiredRangedLevel(name);
        } catch (Exception e) {
            return bowRequiredRangedLevel(name) <= 1;
        }
    }

    private int rangedNameIndexInBestBowList(String name) {
        if (name == null) {
            return -1;
        }
        String t = name.trim();
        for (int i = 0; i < RANGED_BOW_BEST_FIRST.length; i++) {
            if (RANGED_BOW_BEST_FIRST[i].equalsIgnoreCase(t)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Lagere waarde = betere boog. Onbekende boognamen: 1000 − vereiste ranged (hogere tier = lager rank).
     */
    private int rangedBowProgressRank(String name) {
        int idx = rangedNameIndexInBestBowList(name);
        if (idx >= 0) {
            return idx;
        }
        if (!isRangedBowItemName(name)) {
            return Integer.MAX_VALUE;
        }
        return 1000 - bowRequiredRangedLevel(name);
    }

    private int equippedRangedBowProgressRankOnly() {
        int best = Integer.MAX_VALUE;
        try {
            var equipped = Equipment.getAll(item ->
                    item != null && item.getName() != null && isRangedBowItemName(item.getName()));
            if (equipped != null) {
                for (var eq : equipped) {
                    if (eq == null || eq.getName() == null || !canUseRangedBow(eq.getName())) {
                        continue;
                    }
                    best = Math.min(best, rangedBowProgressRank(eq.getName()));
                }
            }
        } catch (Exception ignored) {
        }
        return best;
    }

    private int inventoryOrEquipBestRangedBowProgressRank() {
        int best = Integer.MAX_VALUE;
        try {
            var equipped = Equipment.getAll(item ->
                    item != null && item.getName() != null && isRangedBowItemName(item.getName()));
            if (equipped != null) {
                for (var eq : equipped) {
                    if (eq == null || eq.getName() == null || !canUseRangedBow(eq.getName())) {
                        continue;
                    }
                    best = Math.min(best, rangedBowProgressRank(eq.getName()));
                }
            }
            var inv = Inventory.getAll(item ->
                    item != null && item.getName() != null && isRangedBowItemName(item.getName()));
            if (inv != null) {
                for (var it : inv) {
                    if (it == null || it.getName() == null || !canUseRangedBow(it.getName())) {
                        continue;
                    }
                    best = Math.min(best, rangedBowProgressRank(it.getName()));
                }
            }
        } catch (Exception ignored) {
        }
        return best;
    }

    private boolean bankHasUsableRangedBow() {
        for (String bow : RANGED_BOW_BEST_FIRST) {
            if (Bank.contains(bow) && canUseRangedBow(bow)) {
                return true;
            }
        }
        try {
            return Bank.contains(item -> item != null && item.getName() != null
                    && isRangedBowItemName(item.getName()) && canUseRangedBow(item.getName()));
        } catch (Exception e) {
            return false;
        }
    }

    /** Eerste strikt betere boog in bank dan beste bruikbare boog in equip+inv. */
    private String firstUsableBetterRangedBowInBank() {
        int current = inventoryOrEquipBestRangedBowProgressRank();
        for (String bow : RANGED_BOW_BEST_FIRST) {
            if (!Bank.contains(bow) || !canUseRangedBow(bow)) {
                continue;
            }
            if (rangedBowProgressRank(bow) < current) {
                return bow;
            }
        }
        return null;
    }

    private int rangedArmorDefenceReq(String name) {
        if (name == null) {
            return 99;
        }
        String n = name.trim();
        if (n.equalsIgnoreCase("Leather cowl")
                || n.equalsIgnoreCase("Leather body")
                || n.equalsIgnoreCase("Leather chaps")) {
            return 1;
        }
        if (n.equalsIgnoreCase("Leather vambraces")
                || n.equalsIgnoreCase("Coif")
                || n.equalsIgnoreCase("Green d'hide chaps")
                || n.equalsIgnoreCase("Green d'hide vambraces")) {
            return 0;
        }
        if (n.equalsIgnoreCase("Hard leather body")) {
            return 10;
        }
        if (n.equalsIgnoreCase("Studded body") || n.equalsIgnoreCase("Studded chaps")) {
            return 20;
        }
        if (n.equalsIgnoreCase("Green d'hide coif") || n.equalsIgnoreCase("Green d'hide body")) {
            return 40;
        }
        return 99;
    }

    private int rangedArmorRangedReq(String name) {
        if (name == null) {
            return 99;
        }
        String n = name.trim();
        if (n.equalsIgnoreCase("Coif")) {
            return 20;
        }
        if (n.equalsIgnoreCase("Green d'hide coif")
                || n.equalsIgnoreCase("Green d'hide body")
                || n.equalsIgnoreCase("Green d'hide chaps")
                || n.equalsIgnoreCase("Green d'hide vambraces")) {
            return 40;
        }
        return 0;
    }

    private boolean canWearRangedArmorPiece(String name) {
        if (StyleArmourBankHelper.canWear(CombatBotConfig.ImpsCombatStyle.RANGED, name)) {
            return true;
        }
        if (name != null && StyleArmourBankHelper.requiresDragonSlayerToWear(name)) {
            return false;
        }
        try {
            int def = Skills.getLevel(Skill.DEFENCE);
            int rng = Skills.getLevel(Skill.RANGED);
            return def >= rangedArmorDefenceReq(name) && rng >= rangedArmorRangedReq(name);
        } catch (Exception e) {
            return rangedArmorDefenceReq(name) <= 1 && rangedArmorRangedReq(name) <= 1;
        }
    }

    private int bestEquippedOrInvRangedArmorRank(String[] slotOrder) {
        int best = Integer.MAX_VALUE;
        for (int i = 0; i < slotOrder.length; i++) {
            String n = slotOrder[i];
            if ((Inventory.contains(n) || Equipment.contains(n)) && canWearRangedArmorPiece(n)) {
                best = Math.min(best, i);
            }
        }
        String rsn = BankSnapshotPlanner.currentDisplayName();
        int snap = EquipmentStylePlanner.bestRangedRankFromSnapshot(rsn, slotOrder);
        if (snap != Integer.MAX_VALUE) {
            String snapName = slotOrder[snap];
            if (snapName != null && canWearRangedArmorPiece(snapName)) {
                best = Math.min(best, snap);
            }
        }
        return best;
    }

    private String bestUsableRangedArmorBankUpgrade(String[] slotOrder) {
        int current = bestEquippedOrInvRangedArmorRank(slotOrder);
        for (int i = 0; i < slotOrder.length; i++) {
            String piece = slotOrder[i];
            if (!Bank.contains(piece) || !canWearRangedArmorPiece(piece)) {
                continue;
            }
            if (current != Integer.MAX_VALUE && i >= current) {
                continue;
            }
            if (Inventory.contains(piece) || Equipment.contains(piece)) {
                continue;
            }
            return piece;
        }
        return null;
    }

    private boolean withdrawBestRangedArmorPieceFromBank() {
        String[][] groups = {
                rangedArmorHelmOrder(),
                rangedArmorBodyOrder(),
                rangedArmorChapsOrder(),
                rangedArmorVambOrder()
        };
        String[] labels = {"helm", "body", "chaps", "vambraces"};
        for (int g = 0; g < groups.length; g++) {
            String upgrade = bestUsableRangedArmorBankUpgrade(groups[g]);
            if (upgrade != null) {
                Bank.withdraw(upgrade, 1);
                chatLog("Ranged " + labels[g] + " upgrade opgehaald: " + upgrade);
                return true;
            }
        }
        return false;
    }

    private boolean wearBestRangedArmorFromInventory() {
        String[][] groups = {
                rangedArmorHelmOrder(),
                rangedArmorBodyOrder(),
                rangedArmorChapsOrder(),
                rangedArmorVambOrder()
        };
        for (String[] group : groups) {
            for (String pieceName : group) {
                IInventoryItem item = Inventory.getFirst(pieceName);
                if (item != null && canWearRangedArmorPiece(pieceName)) {
                    String action = item.hasAction("Wear") ? "Wear" : (item.hasAction("Wield") ? "Wield" : "Wear");
                    InventoryActionHelper.interact(config, item, action);
                    chatLog("Ranged armor aangedaan: " + pieceName);
                    return true;
                }
            }
        }
        return false;
    }

    private static String[] rangedArmorHelmOrder() {
        return new String[] {
                "Green d'hide coif",
                "Coif",
                "Leather cowl"
        };
    }

    private static String[] rangedArmorBodyOrder() {
        return new String[] {
                "Green d'hide body",
                "Studded body",
                "Hard leather body",
                "Leather body"
        };
    }

    private static String[] rangedArmorChapsOrder() {
        return new String[] {
                "Green d'hide chaps",
                "Studded chaps",
                "Leather chaps"
        };
    }

    private static String[] rangedArmorVambOrder() {
        return new String[] {
                "Green d'hide vambraces",
                "Leather vambraces"
        };
    }

    /**
     * Ranged bank-withdraw: boog + arrows (geen equip — dat gebeurt in {@link #handleGearPrepEquipPhase}).
     */
    private int withdrawRangedGearFromBankIfNeeded() {
        if (!Bank.isOpen()) {
            return 0;
        }
        String better = firstUsableBetterRangedBowInBank();
        if (better != null) {
            Bank.withdraw(better, 1);
            chatLog("Ranged boog (upgrade) opgehaald: " + better);
            return antiBan.varyDelay(randomDelay(600, 1000));
        }

        if (inventoryOrEquipBestRangedBowProgressRank() >= Integer.MAX_VALUE) {
            for (String bow : RANGED_BOW_BEST_FIRST) {
                if (Bank.contains(bow) && canUseRangedBow(bow)) {
                    Bank.withdraw(bow, 1);
                    chatLog("Ranged boog opgehaald: " + bow);
                    return antiBan.varyDelay(randomDelay(600, 1000));
                }
            }
        }

        return withdrawBestArrowsFromBankIfNeeded();
    }

    /**
     * Eén arrow-withdraw per aanroep; alleen als totaal &lt; {@link #GEAR_PREP_MIN_ARROWS}.
     * @return delay &gt; 0 als withdraw geprobeerd
     */
    private int withdrawBestArrowsFromBankIfNeeded() {
        if (!Bank.isOpen()) {
            return 0;
        }
        if (getImpsEffectiveArrowCount() >= GEAR_PREP_MIN_ARROWS) {
            gearPrepArrowWithdrawStreak = 0;
            gearPrepLastArrowWithdrawName = "";
            return 0;
        }
        for (String arrow : RangedAmmoPreference.withdrawOrder(config)) {
            if (!Bank.contains(arrow)) {
                continue;
            }
            if (arrow.equalsIgnoreCase(gearPrepLastArrowWithdrawName)) {
                gearPrepArrowWithdrawStreak++;
            } else {
                gearPrepLastArrowWithdrawName = arrow;
                gearPrepArrowWithdrawStreak = 1;
            }
            if (gearPrepArrowWithdrawStreak > GEAR_PREP_ARROW_WITHDRAW_MAX_STREAK) {
                chatLog("[!] Gear prep: stop arrow withdraw na " + gearPrepArrowWithdrawStreak
                        + "x (inv=" + getImpsEffectiveArrowCount() + "/" + GEAR_PREP_MIN_ARROWS
                        + ", type=" + RangedAmmoPreference.preferredItemName(config) + ") — check ruimte/deposit");
                return 0;
            }
            int before = getImpsEffectiveArrowCount();
            ensureBankWithdrawUnnoted();
            Bank.withdraw(arrow, Integer.MAX_VALUE);
            sleep(550, 900);
            int after = getImpsEffectiveArrowCount();
            if (after > before) {
                chatLog("Arrows opgehaald: " + arrow + " (+" + (after - before) + ", nu " + after + ")");
                gearPrepArrowWithdrawStreak = 0;
            } else {
                chatLog("[!] Arrow withdraw geen effect: " + arrow + " (voor=" + before + ", na=" + after + ")");
            }
            return antiBan.varyDelay(randomDelay(600, 1000));
        }
        return 0;
    }

    /**
     * Pak ranged setup uit de bank (withdraw only); equip via {@link #beginGearPrepEquipPhase}.
     */
    private int withdrawRangedGear() {
        int rangedWithdraw = withdrawRangedGearFromBankIfNeeded();
        if (rangedWithdraw > 0) {
            return rangedWithdraw;
        }

        int armourTick = handleStyleArmourAtBank(CombatBotConfig.ImpsCombatStyle.RANGED);
        if (armourTick > 0) {
            return armourTick;
        }

        if (!bankHasRequiredItemsForStyle(CombatBotConfig.ImpsCombatStyle.RANGED)
                && !hasAdequateGearForStyle(CombatBotConfig.ImpsCombatStyle.RANGED)) {
            chatLog("[!] Geen ranged gear (boog/arrows) gevonden in bank");
            return antiBan.varyDelay(randomDelay(400, 800));
        }

        return beginGearPrepEquipPhase(CombatBotConfig.ImpsCombatStyle.RANGED);
    }

    /**
     * Bank: per armor-slot beste stuk voor style (level-check), ophalen of aantrekken.
     * @return delay &gt; 0 als actie gedaan, anders 0
     */
    private int handleStyleArmourAtBank(CombatBotConfig.ImpsCombatStyle style) {
        if (impsMeleeSkipsArmour(style)) {
            gearPrepArmourBatchWithdrawDone = true;
            return bankImpsMeleeArmourIfNeeded();
        }
        if (!Bank.isOpen()) {
            gearPrepArmourBatchWithdrawDone = false;
            return 0;
        }
        ensureBankWithdrawUnnoted();

        if (!gearPrepArmourBatchWithdrawDone) {
            StyleArmourBankHelper.withdrawAllForStyle(style, this::sleep);
            gearPrepArmourBatchWithdrawDone = true;
            chatLog("[Bank] " + style + " armor batch-withdraw (equip na bank-sluit)");
            return antiBan.varyDelay(randomDelay(500, 900));
        }

        StyleArmourBankHelper.ArmourAction withdraw = StyleArmourBankHelper.tryWithdrawOneUpgrade(style);
        if (withdraw != null) {
            String key = withdraw.slot + "|" + withdraw.itemName;
            if (key.equalsIgnoreCase(gearPrepLastArmourWithdrawKey)) {
                gearPrepArmourWithdrawStreak++;
            } else {
                gearPrepLastArmourWithdrawKey = key;
                gearPrepArmourWithdrawStreak = 1;
            }
            if (gearPrepArmourWithdrawStreak > GEAR_PREP_ARMOUR_WITHDRAW_MAX_STREAK) {
                chatLog("[!] Gear prep: stop armor withdraw na " + gearPrepArmourWithdrawStreak
                        + "x " + withdraw.itemName + " (" + withdraw.slot + ")");
                gearPrepArmourWithdrawStreak = 0;
                gearPrepLastArmourWithdrawKey = "";
                return 0;
            }
            chatLog("[Bank] " + style + " armor opgehaald: " + withdraw.itemName
                    + " (slot " + withdraw.slot + ", Def/Rng/Mag lvl OK)");
            sleep(300, 500);
            return antiBan.varyDelay(randomDelay(400, 700));
        }
        gearPrepArmourWithdrawStreak = 0;
        gearPrepLastArmourWithdrawKey = "";
        return 0;
    }

    private boolean tryEquipStyleArmour(CombatBotConfig.ImpsCombatStyle style) {
        if (impsMeleeSkipsArmour(style)) {
            return false;
        }
        StyleArmourBankHelper.ArmourAction equip = StyleArmourBankHelper.tryEquipOneFromInventory(config, style);
        if (equip == null) {
            return false;
        }
        if ("unequip".equals(equip.slot)) {
            chatLog(style + ": melee armor uit voor ranged");
            return true;
        }
        chatLog(style + " armor aangedaan: " + equip.itemName + " (" + equip.slot + ")");
        return true;
    }

    /**
     * Pak mage gear uit de bank: staff + runes op basis van gekozen spell.
     */
    private int withdrawMageGear() {
        CombatBotConfig.ImpsMageSpell spell = getActiveMageSpell();

        // Runes altijd unnoted; daarna pas withdraw (anders telt verify fout -> GE).
        ensureBankWithdrawUnnoted();

        // Check of we al een staff hebben
        boolean hasStaff = Equipment.contains(item ->
                item.getName() != null && item.getName().toLowerCase().contains("staff")
        ) || Inventory.contains(item ->
                item.getName() != null && item.getName().toLowerCase().contains("staff")
        );

        // Prefer altijd spell-specifieke staff (bv. Fire Strike -> Staff of fire) voor rune-keuzes.
        String preferred = spell.getPreferredStaff();
        String elementalRuneEarly = spell.getElementalRune();
        String elementKeyEarly = elementalRuneEarly != null
                ? elementalRuneEarly.toLowerCase(Locale.ROOT).replace(" rune", "")
                : "";
        boolean spellElementStaffOk = !elementKeyEarly.isEmpty() && hasStaffWithElement(elementKeyEarly);

        boolean preferredEquipped = Equipment.contains(item ->
                item != null && item.getName() != null && item.getName().equalsIgnoreCase(preferred));
        boolean preferredInInv = Inventory.contains(item ->
                item != null && item.getName() != null && item.getName().equalsIgnoreCase(preferred));

        // Anti-loop: als preferred staff al equipped is, bank andere staves uit inventory weg
        // (bijv. Staff of air) zodat mage checks niet blijven flippen.
        if (preferredEquipped) {
            String extraStaff = getNonPreferredStaffInInventory(preferred);
            if (extraStaff != null && Bank.contains(extraStaff)) {
                Bank.deposit(extraStaff, Integer.MAX_VALUE);
                chatLog("Extra staff gebankt: " + extraStaff + " (preferred equipped: " + preferred + ")");
                return antiBan.varyDelay(randomDelay(550, 900));
            }
        }
        if (!preferredEquipped && preferredInInv) {
            equipGear(CombatBotConfig.ImpsCombatStyle.MAGE);
            chatLog("Preferred staff uit inventory aangedaan: " + preferred);
            return antiBan.varyDelay(randomDelay(700, 1100));
        }
        // Ook bij verkeerde staff (bv. nog Air equipped na Fire-Strike upgrade): preferred uit bank.
        boolean needPreferredFromBank = !preferredEquipped && Bank.contains(preferred)
                && (!hasStaff || !spellElementStaffOk);
        if (needPreferredFromBank) {
            long now = System.currentTimeMillis();
            if (preferredStaffWithdrawAttempts < MAX_PREFERRED_STAFF_WITHDRAW_ATTEMPTS
                    && now - lastPreferredStaffWithdrawMs >= PREFERRED_STAFF_WITHDRAW_COOLDOWN_MS) {
                Bank.withdraw(preferred, 1);
                preferredStaffWithdrawAttempts++;
                lastPreferredStaffWithdrawMs = now;
                chatLog("Preferred staff opgehaald: " + preferred
                        + " (" + preferredStaffWithdrawAttempts + "/" + MAX_PREFERRED_STAFF_WITHDRAW_ATTEMPTS + ")");
                return antiBan.varyDelay(randomDelay(700, 1100));
            }

            debugLog("preferred staff withdraw guard actief: attempts=" + preferredStaffWithdrawAttempts
                    + ", hasStaff=" + hasStaff + ", preferred=" + preferred + ", spellElementOk=" + spellElementStaffOk);
        } else if (!preferredEquipped && hasStaff && spellElementStaffOk) {
            debugLog("element-staff sluit aan bij " + spell.getSpellName() + " -> geen extra preferred-withdraw");
        }

        if (!hasStaff) {
            // Probeer de preferred staff voor deze spell eerst
            if (Bank.contains(preferred)) {
                Bank.withdraw(preferred, 1);
                chatLog("Staff opgehaald: " + preferred);
                return antiBan.varyDelay(randomDelay(600, 1000));
            }
            // Fallback: elke staff
            String[] staves = {
                    "Staff of air", "Staff of fire", "Staff of water", "Staff of earth",
                    "Mystic air staff", "Mystic fire staff", "Mystic water staff", "Mystic earth staff",
                    "Air staff", "Fire staff", "Water staff", "Earth staff", "Staff"
            };
            for (String staff : staves) {
                if (Bank.contains(staff)) {
                    Bank.withdraw(staff, 1);
                    chatLog("Staff opgehaald (fallback): " + staff);
                    return antiBan.varyDelay(randomDelay(600, 1000));
                }
            }
            chatLog("[!] Geen staff gevonden in bank");
            return antiBan.varyDelay(randomDelay(400, 800));
        }

        String catalystRune = spell.getCatalystRune();
        int catalystCount = getItemQuantity(catalystRune);
        int minCat = minCatalystRunesForTrip(spell);
        if (isBelowCatalystTripMinimum(catalystCount, spell) && bankHasUsableItemNamed(catalystRune)) {
            ensureBankWithdrawUnnoted();
            Bank.withdraw(catalystRune, Integer.MAX_VALUE);
            if (!waitUntilItemQuantityIncreases(catalystRune, catalystCount, 4500)) {
                ensureBankWithdrawUnnoted();
                Bank.withdraw(catalystRune, Integer.MAX_VALUE);
                if (!waitUntilItemQuantityIncreases(catalystRune, catalystCount, 4500)) {
                    chatLog("[!] Bank withdraw gaf geen extra " + catalystRune + " (na retry) -> GE failsafe");
                    forceGeMissingItem = catalystRune;
                    return handleGearShortage(CombatBotConfig.ImpsCombatStyle.MAGE);
                }
            }
            chatLog("Catalyst runes opgehaald: alles van " + catalystRune);
            return antiBan.varyDelay(randomDelay(600, 1000));
        }

        String elementalRune = spell.getElementalRune();
        String elementKey = elementalRune.toLowerCase(Locale.ROOT).replace(" rune", "");
        boolean staffCoversElemental = hasStaffWithElement(elementKey);

        int elementalCount = getItemQuantity(elementalRune);
        int minElem = minElementalRunesForTrip(spell);
        if (!staffCoversElemental && elementalCount < minElem && bankHasUsableItemNamed(elementalRune)) {
            ensureBankWithdrawUnnoted();
            Bank.withdraw(elementalRune, Integer.MAX_VALUE);
            if (!waitUntilItemQuantityIncreases(elementalRune, elementalCount, 4500)) {
                ensureBankWithdrawUnnoted();
                Bank.withdraw(elementalRune, Integer.MAX_VALUE);
                if (!waitUntilItemQuantityIncreases(elementalRune, elementalCount, 4500)) {
                    chatLog("[!] Bank withdraw gaf geen extra " + elementalRune + " (na retry) -> GE failsafe");
                    forceGeMissingItem = elementalRune;
                    return handleGearShortage(CombatBotConfig.ImpsCombatStyle.MAGE);
                }
            }
            chatLog("Elemental runes opgehaald: alles van " + elementalRune + " (staff dekt niet)");
            return antiBan.varyDelay(randomDelay(600, 1000));
        }

        if (spell.needsAirRune()) {
            int strictAirW = requiredInventoryAirForAccountAutoFireSetup();
            int airCount = getItemQuantity("Air rune");
            int airMin;
            boolean needAirWithdraw;
            if (strictAirW >= 0) {
                airMin = strictAirW;
                needAirWithdraw = airCount < strictAirW;
            } else {
                boolean staffCoversAir = hasStaffWithElement("air");
                airMin = requiredAirRunesForCurrentMagePlan();
                needAirWithdraw = !staffCoversAir && airCount < airMin;
            }
            if (needAirWithdraw && bankHasUsableItemNamed("Air rune")) {
                ensureBankWithdrawUnnoted();
                        Bank.withdraw("Air rune", Integer.MAX_VALUE);
                if (!waitUntilItemQuantityIncreases("Air rune", airCount, 4500)) {
                    ensureBankWithdrawUnnoted();
                    Bank.withdraw("Air rune", Integer.MAX_VALUE);
                    if (!waitUntilItemQuantityIncreases("Air rune", airCount, 4500)) {
                        chatLog("[!] Bank withdraw gaf geen extra Air rune (na retry) -> GE failsafe");
                        forceGeMissingItem = "Air rune";
                        return handleGearShortage(CombatBotConfig.ImpsCombatStyle.MAGE);
                    }
                }
                chatLog("Air runes opgehaald: alles uit bank (nodig voor " + spell.getSpellName() + ")");
                return antiBan.varyDelay(randomDelay(600, 1000));
            }
        }

        // Law runes voor teleport (bv. Varrock naar GE) - 10 is genoeg
        try {
            int magicLevel = Skills.getLevel(Skill.MAGIC);
            if (magicLevel >= 25 && getItemQuantity("Law rune") < 10 && bankHasUsableItemNamed("Law rune")) {
                Bank.withdraw("Law rune", 10);
                chatLog("Law runes opgehaald (10) voor teleports");
                return antiBan.varyDelay(randomDelay(600, 1000));
            }
        } catch (Exception e) {
            // als level check faalt, gewoon doorgaan
        }

        // Staff in inventory maar nog niet gedragen? Eerst aantrekken (dan is gear echt compleet)
        boolean staffEquipped = Equipment.contains(item ->
                item != null && item.getName() != null && item.getName().toLowerCase().contains("staff"));
        boolean staffInInv = Inventory.contains(item ->
                item != null && item.getName() != null && item.getName().toLowerCase().contains("staff"));
        if (staffInInv && !staffEquipped) {
            equipGear(CombatBotConfig.ImpsCombatStyle.MAGE);
            chatLog("Staff uit inventory aangedaan");
            return antiBan.varyDelay(randomDelay(800, 1200));
        }

        int mageArmourTick = handleStyleArmourAtBank(CombatBotConfig.ImpsCombatStyle.MAGE);
        if (mageArmourTick > 0) {
            return mageArmourTick;
        }
        if (tryEquipStyleArmour(CombatBotConfig.ImpsCombatStyle.MAGE)) {
            return antiBan.varyDelay(randomDelay(650, 1050));
        }

        if (spell.needsAirRune() && fireStrikeAirDepartureGateFails()) {
            int strictEnd = requiredInventoryAirForAccountAutoFireSetup();
            int dispEnd = strictEnd >= 0 ? strictEnd : requiredAirRunesForMageDeparture();
            if (!bankHasRequiredItemsForStyle(CombatBotConfig.ImpsCombatStyle.MAGE)) {
                return handleGearShortage(CombatBotConfig.ImpsCombatStyle.MAGE);
            }
            chatLog(" Nog niet klaar voor " + spell.getSpellName() + " (fire staff + Air inv, Air "
                    + getItemQuantity("Air rune") + "/" + dispEnd + ") — opnieuw uit bank");
            return antiBan.varyDelay(randomDelay(500, 900));
        }

        int prepDone = tryCompleteGearPreparation(CombatBotConfig.ImpsCombatStyle.MAGE);
        if (prepDone == -1) {
            chatLog("[OK] Mage setup compleet voor " + spell.getSpellName());
            return antiBan.varyDelay(randomDelay(1200, 1800));
        }
        if (prepDone > 0) {
            return prepDone;
        }
        return antiBan.varyDelay(randomDelay(500, 900));
    }

    private String getNonPreferredStaffInInventory(String preferredStaffName) {
        IInventoryItem extra = Inventory.getFirst(item -> {
            if (item == null || item.getName() == null) return false;
            String n = item.getName().trim();
            if (n.isEmpty()) return false;
            String low = n.toLowerCase(Locale.ROOT);
            if (!low.contains("staff")) return false;
            return preferredStaffName == null || !n.equalsIgnoreCase(preferredStaffName);
        });
        return extra != null ? extra.getName() : null;
    }

    // ===================== AUTOCAST SPELL SELECTION =====================

    /**
     * Standaardspell + offensive autocast: eerst Magic-tab en {@link Magic#selectSpell},
     * daarna {@link Magic#setAutoCast} — anders blijft vaak geen spell geselecteerd (alleen runes/staff).
     */
    private boolean selectAutocastSpell(CombatBotConfig.ImpsMageSpell spell) {
        if (spell == null) {
            return false;
        }
        try {
            SpellBook.Standard stdSpell = spell.getStandardSpell();
            if (!stdSpell.canCast()) {
                chatLog("[!] Autocast: " + spell.getSpellName() + " nog niet castbaar (runes/spellbook/level)");
                return false;
            }
            if (Magic.isAutoCasting(stdSpell)) {
                return true;
            }
            chatLog("Autocast instellen: " + spell.getSpellName());
            try {
                Tabs.open(Tab.MAGIC);
                sleep(280, 520);
            } catch (Throwable ignored) {
            }
            try {
                Magic.selectSpell(stdSpell);
                sleep(220, 420);
            } catch (Throwable t) {
                chatLog("[!] selectSpell: " + t.getMessage());
            }
            Magic.setAutoCast(stdSpell, false);
            sleep(800, 1200);
            if (Magic.isAutoCasting(stdSpell)) {
                chatLog("[OK] Autocast actief: " + spell.getSpellName());
                return true;
            }
            Magic.selectSpell(stdSpell);
            sleep(200, 400);
            Magic.setAutoCast(stdSpell, false);
            sleep(600, 1000);
            if (Magic.isAutoCasting(stdSpell)) {
                chatLog("[OK] Autocast na retry: " + spell.getSpellName());
                return true;
            }
            chatLog("[!] Autocast mislukt voor " + spell.getSpellName() + " — volgende loop opnieuw");
            return false;
        } catch (Exception e) {
            chatLog("[!] Autocast fout: " + e.getMessage());
            return false;
        }
    }

    /**
     * Starter-bridge: cast Home teleport naar Lumbridge, daarna start {@link #handleGeSelling} (loot zat al in inv → bank stort eerst).
     */
    private int handleStarterBridgeHomeTeleportThenGe(IPlayer local) {
        paint.setCurrentStatus("Imps: Starter — Home teleport → GE");
        if (local == null || local.getWorldLocation() == null) {
            return 1000;
        }
        WorldPoint pos = local.getWorldLocation();
        if (pos.distanceTo(LUMBRIDGE_HOME_TELEPORT_ANCHOR) <= 22 && pos.getPlane() == 0) {
            starterBridgeHomeTeleportToGePending = false;
            isSellingAtGe = true;
            geSellStep = 0;
            geSellItemIndex = 0;
            geCollectedExisting = false;
            varrockTeleportUsedThisGeTrip = false;
            lawRunesBoughtThisGeTrip = false;
            chatLog("[Starter] Lumbridge bereikt — GE-trip (bank + verkoop + daarna Mind/Staff)");
            return antiBan.varyDelay(randomDelay(600, 1000));
        }

        long now = System.currentTimeMillis();
        if ((now - lastTeleportTime) <= TELEPORT_COOLDOWN_MS) {
            paint.setCurrentStatus("Imps: Starter — wacht op teleport-cooldown…");
            if (now - lastStarterHomeTeleportDiagLogMs >= 10_000L) {
                lastStarterHomeTeleportDiagLogMs = now;
                chatLog("[Starter] Wacht op algemene teleport-cooldown (" + TELEPORT_COOLDOWN_MS + " ms na vorige teleport) vóór Home teleport");
            }
            return antiBan.varyDelay(randomDelay(900, 1600));
        }

        SpellBook.Standard home = SpellBook.Standard.HOME_TELEPORT;
        if (home.canCast()) {
            chatLog("[Starter] Home teleport casten (Lumbridge)…");
            try {
                Magic.cast(home);
                lastTeleportTime = now;
            } catch (Exception e) {
                chatLog("[!] Starter Home teleport fout: " + e.getMessage());
            }
            return antiBan.varyDelay(randomDelay(4500, 7000));
        }

        paint.setCurrentStatus("Imps: Starter — Home teleport nog niet beschikbaar…");
        if (now - lastStarterHomeTeleportDiagLogMs >= 10_000L) {
            lastStarterHomeTeleportDiagLogMs = now;
            chatLog("[Starter] Home teleport niet castbaar (canCast=false) — vaak 30 min F2P-cooldown op het gratis teleportslot; wacht…");
        }
        return antiBan.varyDelay(randomDelay(2000, 3500));
    }

    /** Na eerste starter GE-verkoop: koop 1000 Mind rune + Staff of air, daarna wealth-check. */
    private int handleStarterBridgeGeBuyMindStaff(IPlayer local) {
        if (local == null || local.getWorldLocation() == null) {
            return 1000;
        }
        WorldPoint myPos = local.getWorldLocation();

        switch (starterBridgeGeBuyExtrasStep) {
            case 0:
                if (myPos.distanceTo(GE_LOCATION) > 8) {
                    paint.setCurrentStatus("[Starter] GE: → Grand Exchange (Mind + Staff)…");
                    MovementHelper.walkTo(GE_LOCATION);
                    return antiBan.varyDelay(randomDelay((int) GE_WALK_RECLICK_MIN_MS, (int) GE_WALK_RECLICK_MAX_MS));
                }
                starterBridgeGeBuyExtrasStep = 1;
                return randomDelay(400, 800);

            case 1: {
                int invMind = Inventory.getCount(true, "Mind rune");
                if (invMind >= STARTER_BRIDGE_MIND_RUNE_GE_TARGET) {
                    chatLog("[Starter] Al " + invMind + " Mind rune — skip GE-koop");
                    starterBridgeGeBuyExtrasStep = 2;
                    return randomDelay(300, 600);
                }
                if (!GrandExchange.isOpen()) {
                    GrandExchange.open();
                    lastGeInteraction = System.currentTimeMillis();
                    return antiBan.varyDelay(randomDelay(1500, 2500));
                }
                int startMind = Math.max(3, (int) Math.ceil(Math.max(1, Prices.getItemPrice(ITEM_ID_MIND_RUNE)) * 1.12));
                paint.setCurrentStatus("[Starter] GE: 1000× Mind rune…");
                GeRestockHelper.RestockResult rr = GeRestockHelper.buyWithEscalation(
                        "Mind rune", STARTER_BRIDGE_MIND_RUNE_GE_TARGET, startMind, () -> config.botEnabled());
                lastGeInteraction = System.currentTimeMillis();
                if (!config.botEnabled()) {
                    starterBridgeGeBuyMindStaffActive = false;
                    starterBridgeGeBuyExtrasStep = 0;
                    return 1000;
                }
                if (rr == GeRestockHelper.RestockResult.SUCCESS) {
                    chatLog("[OK] [Starter] 1000 Mind rune gekocht");
                } else {
                    chatLog("[!] [Starter] Mind rune GE: " + rr + " — verder met Staff");
                }
                starterBridgeGeBuyExtrasStep = 2;
                return antiBan.varyDelay(randomDelay(1500, 2500));
            }

            case 2: {
                if (hasAirStaffEquippedOrInventory()) {
                    chatLog("[Starter] Air staff al aanwezig — skip koop");
                    starterBridgeGeBuyExtrasStep = 3;
                    return randomDelay(300, 600);
                }
                if (!GrandExchange.isOpen()) {
                    GrandExchange.open();
                    lastGeInteraction = System.currentTimeMillis();
                    return antiBan.varyDelay(randomDelay(1500, 2500));
                }
                int startStaff = Math.max(1, (int) Math.ceil(Prices.getItemPrice(ITEM_ID_STAFF_OF_AIR) * 1.15));
                paint.setCurrentStatus("[Starter] GE: Staff of air…");
                GeRestockHelper.RestockResult rr = GeRestockHelper.buyWithEscalation(
                        "Staff of air", 1, startStaff, () -> config.botEnabled());
                lastGeInteraction = System.currentTimeMillis();
                if (!config.botEnabled()) {
                    starterBridgeGeBuyMindStaffActive = false;
                    starterBridgeGeBuyExtrasStep = 0;
                    return 1000;
                }
                if (rr == GeRestockHelper.RestockResult.SUCCESS) {
                    chatLog("[OK] [Starter] Staff of air gekocht");
                } else {
                    chatLog("[!] [Starter] Staff of air GE: " + rr);
                }
                starterBridgeGeBuyExtrasStep = 3;
                return antiBan.varyDelay(randomDelay(1500, 2500));
            }

            case 3:
            default:
                if (GrandExchange.isOpen()) {
                    if (GrandExchange.canCollect()) {
                        GrandExchange.collect(false);
                        lastGeInteraction = System.currentTimeMillis();
                        return antiBan.varyDelay(randomDelay(800, 1400));
                    }
                    net.storm.sdk.input.Keyboard.type(String.valueOf((char) java.awt.event.KeyEvent.VK_ESCAPE), false);
                    return antiBan.varyDelay(randomDelay(400, 800));
                }
                starterBridgeGeBuyMindStaffActive = false;
                starterBridgeGeBuyExtrasStep = 0;
                starterBridgeFirstGeCyclePending = false;
                String rsnDone = tryLocalRsnForQuest();
                if (rsnDone != null) {
                    AccountStateJsonStore.markImpsStarterFirstGeCycleDone(rsnDone);
                }
                chatLog("[Starter] Eénmalige GE-trip (Mind + Staff) klaar — wealth-check");
                if (starterSkillBridge && starterOnGeBankWealth6000Stop != null) {
                    starterPostGeWealthCheck = true;
                } else {
                    preparingGear = true;
                    gearPrepComplete = false;
                }
                return antiBan.varyDelay(randomDelay(600, 1200));
        }
    }

    /**
     * Na GE-verkoop in starter-bridge: open bank, tel coins inv+bank, bij ≥6k callback (bot uit + logout).
     */
    private int handleStarterPostGeWealthCheck(IPlayer local) {
        paint.setCurrentStatus("Imps: Starter — wealth-check na GE");
        if (!Bank.isOpen()) {
            if (BankHelper.interactIfNearby()) {
                return antiBan.varyDelay(randomDelay(1500, 2500));
            }
            if (BankHelper.tryOpenFullBank()) {
                return antiBan.varyDelay(randomDelay(1500, 2500));
            }
            if (local != null && local.getWorldLocation() != null && !BankHelper.walkToNearestFullBank()) {
                chatLog("[!] Starter wealth-check: geen bank bereikbaar");
                return randomDelay(2000, 3500);
            }
            return antiBan.varyDelay(randomDelay(1400, 2200));
        }

        int invCoins = getCoinCount();
        int bankCoins = 0;
        try {
            if (Bank.contains("Coins")) {
                var stack = Bank.getFirst("Coins");
                if (stack != null) {
                    bankCoins = stack.getQuantity();
                }
            }
        } catch (Throwable ignored) {
        }
        int total = invCoins + bankCoins;
        chatLog("[Starter] Wealth na GE: inv=" + invCoins + " bank=" + bankCoins + " totaal=" + total);

        if (total >= STARTER_POST_GE_WEALTH_STOP_GP && starterOnGeBankWealth6000Stop != null) {
            starterPostGeWealthCheck = false;
            starterSkillBridge = false;
            starterBridgeFirstGeCyclePending = false;
            starterBridgeHomeTeleportToGePending = false;
            starterBridgeGeBuyMindStaffActive = false;
            Runnable cb = starterOnGeBankWealth6000Stop;
            starterOnGeBankWealth6000Stop = null;
            try {
                Bank.close();
            } catch (Throwable ignored) {
            }
            chatLog("[Starter] ≥" + STARTER_POST_GE_WEALTH_STOP_GP + " gp — stop + logout callback");
            cb.run();
            return 2000;
        }

        starterPostGeWealthCheck = false;
        preparingGear = true;
        gearPrepComplete = false;
        try {
            Bank.close();
        } catch (Throwable ignored) {
        }
        chatLog("[Starter] Wealth < " + STARTER_POST_GE_WEALTH_STOP_GP + " gp — verder met gear prep");
        return antiBan.varyDelay(randomDelay(600, 1000));
    }

    // ===================== GE SELLING =====================

    /**
     * GE verkoopflow:
     * Step 0: Loop naar dichtstbijzijnde bank (niet vast Draynor); open bank
     * Step 1: Withdraw loot items als notes + teleport runes (Law) als we die niet hebben
     * Step 2: Path naar GE (bot teleporteert zelf als hij runes bij zich heeft)
     * Step 3: Open GE, collect existing, sell items
     * Step 4: Klaar - start gear prep en terug naar Karamja
     */
    private int handleGeSelling(IPlayer local) {
        if (shouldAbortActions()) {
            isSellingAtGe = false;
            geSellStep = 0;
            return antiBan.varyDelay(randomDelay(250, 500));
        }
        WorldPoint myPos = local.getWorldLocation();

        switch (geSellStep) {
            case 0: // Walk to nearest bank (voor items + evt. teleport runes), niet vast Draynor
                if (Bank.isOpen()) {
                    geSellTripMadeProgress = false;
                    geSellStep = 1;
                    lawRunesBoughtThisGeTrip = false;
                    varrockTeleportUsedThisGeTrip = false;
                    return randomDelay(600, 1000);
                }
                if (BankHelper.interactIfNearby()) {
                    paint.setCurrentStatus("[GE] GE: Bank openen");
                    return antiBan.varyDelay(randomDelay(1500, 2500));
                }
                if (local.isMoving()) {
                    paint.setCurrentStatus("[GE] GE: -> Bank (dichtstbij)");
                    return randomDelay(600, 1000);
                }
                if (!BankHelper.walkToNearestFullBank("Coins")) {
                    if (BankHelper.wasLastWalkSkippedDueToSnapshot()) {
                        paint.setCurrentStatus("[GE] GE: snapshot geen coins in bank → GE");
                        geSellStep = 2;
                        return antiBan.varyDelay(randomDelay(800, 1200));
                    }
                    paint.setCurrentStatus("[GE] GE: Geen bank gevonden");
                    return 3000;
                }
                paint.setCurrentStatus("[GE] GE: -> Bank (" + myPos.distanceTo(GE_LOCATION) + " tiles van GE)");
                return antiBan.varyDelay(randomDelay(1500, 2500));

            case 1: // Withdraw loot (noted) uit bank - moet echt gebeuren voordat we naar GE lopen
                if (!Bank.isOpen()) {
                    BankHelper.openSdkBankAndWait();
                    return antiBan.varyDelay(randomDelay(1500, 2500));
                }
                paint.setCurrentStatus("[GE] GE: Loot ophalen (noted) voor verkoop...");

                ensureBankWithdrawUnnoted();
                if (hasDepositItems()) {
                    List<String> keepNames = new ArrayList<>(getFullKeepList());
                    keepNames.add("Coins");
                    for (IInventoryItem item : Inventory.getAll()) {
                        if (item != null && item.getName() != null && shouldKeepItem(item) && !keepNames.contains(item.getName())) {
                            keepNames.add(item.getName());
                        }
                    }
                    paint.setCurrentStatus("[GE] GE: Eerst loot naar bank (volle inv)…");
                    Bank.depositAllExcept(keepNames.toArray(new String[0]));
                    sleep(400, 700);
                    return antiBan.varyDelay(randomDelay(600, 1000));
                }

                // Zet withdraw mode op NOTED (met retry om bank-mode flippen te voorkomen).
                ensureBankWithdrawNoted();

                // Withdraw alle sell items (uit instellingen: GE verkoop items) - dit stuk niet overslaan
                boolean withdrawnAny = false;
                List<String> sellItems = getGeSellItemsFromConfig();
                for (String item : sellItems) {
                    if (shouldAbortActions()) {
                        break;
                    }
                    int bankQty = bankStackQuantity(item);
                    if (bankQty <= 0) {
                        continue;
                    }
                    int beforeInv = getItemQuantity(item);
                    // Noted-all withdraw is betrouwbaarder met withdrawAll dan met "X=Integer.MAX_VALUE".
                    ensureBankWithdrawNoted();
                    Bank.withdrawAll(item);
                    sleep(500, 900);
                    int afterInv = getItemQuantity(item);
                    if (afterInv <= beforeInv) {
                        // Een tweede poging helpt als bank mode/UI even terugflipte.
                        ensureBankWithdrawNoted();
                        Bank.withdrawAll(item);
                        sleep(450, 750);
                        afterInv = getItemQuantity(item);
                    }
                    if (afterInv > beforeInv) {
                        withdrawnAny = true;
                        geSellTripMadeProgress = true;
                        chatLog("[GE] Loot opgehaald voor verkoop: " + item + " (+" + (afterInv - beforeInv) + ", bank had " + bankQty + ")");
                    } else {
                        chatLog("[GE] [!!] Bank had " + item + " (" + bankQty + ") maar withdraw verhoogde inv niet — skip (noted/plaats?)");
                    }
                }
                if (!withdrawnAny && !sellItems.isEmpty()) {
                    chatLog("[GE] Geen verkoopitems in bank (gezocht: " + String.join(", ", sellItems) + ")");
                }

                // Terug naar ITEM mode alleen als needed (scheelt onnodige toggles).
                if (Bank.isNotedWithdrawMode()) {
                    Bank.setWithdrawMode(false);
                    sleep(400, 600);
                }

                // Teleport runes voor Varrock: alleen als we willen teleporteren, level 25+ hebben, en op basis van staff alleen de runes pakken die we nodig hebben
                try {
                    int magicLevel = Skills.getLevel(Skill.MAGIC);
                    if (config.impsUseVarrockTeleport() && magicLevel >= 25) {
                        boolean needLaw = getItemQuantity("Law rune") < 5;
                        boolean needAir = !staffCoversAir() && getItemQuantity("Air rune") < 15; // Varrock = 3 Air per cast
                        boolean needFire = !staffCoversFire() && getItemQuantity("Fire rune") < 5; // Varrock = 1 Fire per cast
                        if (needLaw && bankHasUsableItemNamed("Law rune")) {
                            Bank.withdraw("Law rune", 10);
                            sleep(400, 600);
                            chatLog("Law runes opgehaald voor Varrock teleport naar GE");
                        }
                        if (needAir && bankHasUsableItemNamed("Air rune")) {
                            Bank.withdraw("Air rune", 20);
                            sleep(400, 600);
                            chatLog("Air runes opgehaald voor Varrock teleport (staff dekt geen Air)");
                        }
                        if (needFire && bankHasUsableItemNamed("Fire rune")) {
                            Bank.withdraw("Fire rune", 10);
                            sleep(400, 600);
                            chatLog("Fire runes opgehaald voor Varrock teleport (staff dekt geen Fire)");
                        }
                    }
                } catch (Exception e) {
                    // level check faalt: geen runes pakken
                }

                Bank.close();
                sleep(600, 1000);

                if (!withdrawnAny) {
                    // Geen loot in bank/inv: GE-trip overslaan (voorkomt nutteloos heen-en-weer lopen).
                    chatLog("[GE] Geen loot items in bank/inventory om te verkopen — GE-trip overgeslagen");
                    geSellStep = 4; // afronden via bestaande done-path (incl. no-progress handling)
                    lockedSellItemName = null;
                    lockedSellPrice = -1;
        lockedSellUsedBeadMarketFirst = false;
                    return antiBan.varyDelay(randomDelay(500, 900));
                }
                // Alleen naar GE als we daadwerkelijk verkoopbare loot hebben.
                geSellStep = 2;
                lockedSellItemName = null;
                lockedSellPrice = -1;
                return antiBan.varyDelay(randomDelay(800, 1200));

            case 2: // Walk to GE - blijf doorlopen: periodiek opnieuw klikken (niet wachten tot stilstand), maar niet te snel
                long now = System.currentTimeMillis();
                // Probeer Varrock teleport te gebruiken als dat geactiveerd is en zinvol is
                if (config.impsUseVarrockTeleport() && !varrockTeleportUsedThisGeTrip) {
                    SpellBook.Standard varrockTele = SpellBook.Standard.VARROCK_TELEPORT;
                    String rsnTp = impsLocalRsnOrNull();
                    boolean canChronicle = VarrockTeleportHelper.tryExecuteChronicleTeleport(config, rsnTp);
                    if (canChronicle) {
                        chatLog("[TP] Chronicle (Diango) gebruikt i.p.v. Varrock-spell -> GE");
                        lastTeleportTime = now;
                        varrockTeleportUsedThisGeTrip = true;
                        return antiBan.varyDelay(randomDelay(5000, 7000));
                    }
                    if (myPos.distanceTo(GE_LOCATION) > 40
                            && (now - lastTeleportTime) > TELEPORT_COOLDOWN_MS
                            && varrockTele.canCast()) {
                        chatLog(" Varrock teleport gebruiken om sneller bij de GE te komen");
                        try {
                            Magic.cast(varrockTele);
                        } catch (Exception e) {
                            chatLog(" Varrock teleport cast fout: " + e.getMessage());
                        }
                        lastTeleportTime = now;
                        varrockTeleportUsedThisGeTrip = true;
                        return antiBan.varyDelay(randomDelay(5000, 7000));
                    }
                }
                if (myPos.distanceTo(GE_LOCATION) > 8) {
                    MovementHelper.walkTo(GE_LOCATION);
                    paint.setCurrentStatus("[GE] GE: -> Grand Exchange (" + myPos.distanceTo(GE_LOCATION) + " tiles)");
                    return antiBan.varyDelay(randomDelay((int)GE_WALK_RECLICK_MIN_MS, (int)GE_WALK_RECLICK_MAX_MS));
                }
                geSellStep = 3;
                geSellItemIndex = 0;
                geCollectedExisting = false;
                return randomDelay(600, 1000);

            case 3: // Open GE and sell
                return handleGeTrading();

            case 4: // Done - gear prep en terug (starter-bridge: eerst wealth-check)
                isSellingAtGe = false;
                geSellStep = 0;
                lockedSellItemName = null;
                lockedSellPrice = -1;
                geSellItemAttemptFails = 0;
                impsPostGeSellPullAllCoins = true;
                boolean geSellHadProgress = geSellTripMadeProgress;
                geSellTripMadeProgress = false;
                if (!starterSkillBridge) {
                    if (!geSellHadProgress) {
                        geSellNoProgressCycles++;
                        chatLog("[GE] GE-trip zonder withdraw/verkoop-vooruitgang (" + geSellNoProgressCycles + "x) — coins zaten mogelijk al op bank");
                        if (geSellNoProgressCycles >= 1) {
                            CombatBotConfig.ImpsCombatStyle fb = config.impsFallbackStyle();
                            if (fb != CombatBotConfig.ImpsCombatStyle.MAGE && !fallbackStyleActive) {
                                geSellNoProgressCycles = 0;
                                chatLog("[!] GE-verkoop pakt geen loot meer — Tier 2: fallback " + fb);
                                fallbackStyleActive = true;
                                effectiveStyle = fb;
                                preparingGear = true;
                                gearPrepComplete = false;
                                return antiBan.varyDelay(randomDelay(700, 1200));
                            }
                        }
                    } else {
                        geSellNoProgressCycles = 0;
                    }
                }
                if (starterSkillBridge && starterBridgeFirstGeCyclePending) {
                    starterBridgeGeBuyMindStaffActive = true;
                    starterBridgeGeBuyExtrasStep = 0;
                    chatLog("[Starter] GE-verkoop klaar — koop 1000 Mind rune + Staff of air");
                    return randomDelay(600, 1000);
                }
                if (starterSkillBridge && starterOnGeBankWealth6000Stop != null) {
                    starterPostGeWealthCheck = true;
                    chatLog("[GE] GE klaar — starter wealth-check (bank+inv ≥ " + STARTER_POST_GE_WEALTH_STOP_GP + " gp)…");
                    return randomDelay(600, 1000);
                }
                if (!starterSkillBridge) {
                    ArrayList<GeBuySlot> utilQueue = new ArrayList<>();
                    appendUtilityGeBuysIfAffordable(utilQueue, getCoinCount());
                    if (!utilQueue.isEmpty()) {
                        geBuyQueue = utilQueue;
                        geBuyQueueIndex = 0;
                        GeBuySlot first = utilQueue.get(0);
                        geAmmoItemName = first.itemName;
                        geAmmoQuantity = first.quantity;
                        geAmmoPrice = first.startPricePerUnit;
                        isBuyingAmmoAtGe = true;
                        buyAmmoStep = 0;
                        chatLog("[GE] Na verkoop: extra utility aankopen (" + summarizeGeBuyQueue(utilQueue) + ")");
                        return randomDelay(600, 1000);
                    }
                }
                preparingGear = true;
                gearPrepComplete = false;
                chatLog("[GE] GE verkoop klaar! Terug naar gear prep...");
                return randomDelay(600, 1000);

            default:
                isSellingAtGe = false;
                geSellStep = 0;
                geSellItemAttemptFails = 0;
                return 1000;
        }
    }

    /**
     * Handel de GE trading af: open GE, collect bestaande offers, verkoop items.
     */
    private int handleGeTrading() {
        if (shouldAbortActions()) {
            isSellingAtGe = false;
            geSellStep = 0;
            return antiBan.varyDelay(randomDelay(250, 500));
        }
        // Cooldown check (snellere GE interacties, maar nog steeds menselijk)
        if (System.currentTimeMillis() - lastGeInteraction < GE_INTERACTION_COOLDOWN) {
            return randomDelay(400, 800);
        }

        // Open GE als niet open
        if (!GrandExchange.isOpen()) {
            GrandExchange.open();
            lastGeInteraction = System.currentTimeMillis();
            paint.setCurrentStatus("[GE] GE: Openen...");
            return antiBan.varyDelay(randomDelay(1500, 2500));
        }

        // Stap 1: Collect bestaande offers (annuleer + collect)
        if (!geCollectedExisting) {
            // Annuleer alle bestaande offers eerst
            java.util.List<net.runelite.api.GrandExchangeOffer> offers = GrandExchange.getOffers();
            if (offers != null) {
                for (net.runelite.api.GrandExchangeOffer offer : offers) {
                    if (offer != null && offer.getState() != null) {
                        String stateName = offer.getState().name();
                        // Annuleer lopende offers
                        if (stateName.contains("BUYING") || stateName.contains("SELLING")) {
                            GrandExchange.abortOffer(offer.getItemId());
                            lastGeInteraction = System.currentTimeMillis();
                            paint.setCurrentStatus("[GE] GE: Offer annuleren...");
                            return antiBan.varyDelay(randomDelay(1200, 2000));
                        }
                    }
                }
            }

            // Collect alles
            if (GrandExchange.canCollect()) {
                GrandExchange.collect(true); // Collect naar bank
                geSellTripMadeProgress = true;
                lastGeInteraction = System.currentTimeMillis();
                paint.setCurrentStatus("[GE] GE: Bestaande offers ophalen...");
                return antiBan.varyDelay(randomDelay(1200, 2000));
            }

            geCollectedExisting = true;
            // Na het opruimen van offers: reset Law-rune koopflag voor deze GE-trip
            lawRunesBoughtThisGeTrip = false;
            return randomDelay(600, 1000);
        }

        // Stap 2: Eventueel Law runes bijkopen (exchange() doet alle stappen, prijs +10% voor sneller vullen)
        if (!lawRunesBoughtThisGeTrip
                && getEffectiveStyle() == CombatBotConfig.ImpsCombatStyle.MAGE
                && config.impsTeleportBuyRunes()) {
            try {
                int magicLevel = Skills.getLevel(Skill.MAGIC);
                int haveLaws = getItemQuantity("Law rune");
                int buyPrice = config.impsLawRuneBuyPrice();
                int coins = getCoinCount();

                int desiredTotal = 10;
                if (magicLevel >= 25 && haveLaws < desiredTotal && coins > buyPrice * 15) {
                    int priceWithBonus = priceWith2x5Percent(buyPrice);
                    paint.setCurrentStatus("[GE] GE: Law runes kopen -> tot " + desiredTotal);
                    GeRestockHelper.RestockResult rr = GeRestockHelper.buyWithEscalation(
                            "Law rune",
                            desiredTotal,
                            priceWithBonus,
                            () -> config.botEnabled()
                    );
                    lastGeInteraction = System.currentTimeMillis();
                    lawRunesBoughtThisGeTrip = true; // voorkomen dat we eindeloos blijven proberen
                    if (rr == GeRestockHelper.RestockResult.SUCCESS) {
                        geSellTripMadeProgress = true;
                        chatLog("[OK] GE: Law runes restock OK -> tot " + desiredTotal);
                    } else {
                        chatLog(" GE: Law runes restock niet gelukt (" + rr + ")");
                    }
                    return antiBan.varyDelay(randomDelay(2500, 4000));
                }
            } catch (Exception e) {
                chatLog(" Law rune buy check fout: " + e.getMessage());
                lawRunesBoughtThisGeTrip = true;
            }
        }

        // Stap 3: Verkoop items een voor een via exchange() (itemnaam, vaste prijs uit config)
        List<String> sellItems = getGeSellItemsFromConfig();
        if (geSellItemIndex < sellItems.size()) {
            String itemName = sellItems.get(geSellItemIndex);
            IInventoryItem item = Inventory.getFirst(itemName);

            if (item == null) {
                geSellItemAttemptFails = 0;
                geSellItemIndex++;
                return randomDelay(300, 600);
            }

            if (GrandExchange.isFull()) {
                // Geen ruimte om meer te verkopen: soms direct collect, soms eerst nog wat wachten
                if (GrandExchange.canCollect()) {
                    if (random.nextInt(3) == 0) { // ~33% kans om nu te collecten
                        GrandExchange.collect(true);
                        geSellTripMadeProgress = true;
                        lastGeInteraction = System.currentTimeMillis();
                        return antiBan.varyDelay(randomDelay(1200, 2000));
                    }
                }
                chatLog(" GE vol, wachten op vrije slot...");
                return antiBan.varyDelay(randomDelay(2000, 3500));
            }

            int qty = item.getQuantity();

            // GE selling: lock de gekozen prijs voor dit item totdat exchange() done=true is.
            // Dit voorkomt dat we bij elk loopje opnieuw een andere prijs plaatsen.
            if (lockedSellItemName == null
                    || !lockedSellItemName.equalsIgnoreCase(itemName)
                    || lockedSellPrice <= 0) {
                int basePrice = config.impsGeSellPrice();
                int price = basePrice;
                // Verkoopprijs: kleine variatie van 10 gp rond de ingestelde prijs
                if (basePrice > 0) {
                    int delta = random.nextInt(21) - 10; // -10 .. +10
                    price = Math.max(1, basePrice + delta);
                }
                boolean useBeadFirst = config.geSellBeadMarketFirst() && isBeadItemName(itemName);
                if (useBeadFirst) {
                    int beadFirst = resolveBeadFirstTrySellPrice(item, price);
                    if (beadFirst > 0) {
                        price = beadFirst;
                        lockedSellUsedBeadMarketFirst = true;
                    } else {
                        lockedSellUsedBeadMarketFirst = false;
                    }
                } else {
                    lockedSellUsedBeadMarketFirst = false;
                }
                lockedSellItemName = itemName;
                lockedSellPrice = price;
            }

            paint.setCurrentStatus("[GE] GE: Verkoop " + itemName + " (" + qty + "x) @" + lockedSellPrice + "gp");
            boolean done = GrandExchange.exchange(false, itemName, qty, lockedSellPrice, true, true);
            lastGeInteraction = System.currentTimeMillis();
            if (done) {
                geSellTripMadeProgress = true;
                geSellItemAttemptFails = 0;
                chatLog("[GE] Verkoop: " + qty + "x " + itemName + " voor " + lockedSellPrice + "gp elk");
                geSellItemIndex++;
                lockedSellItemName = null;
                lockedSellPrice = -1;
            } else {
                geSellItemAttemptFails++;
                if (lockedSellUsedBeadMarketFirst && geSellItemAttemptFails == 1) {
                    int basePrice = config.impsGeSellPrice();
                    int delta = random.nextInt(21) - 10; // -10 .. +10
                    lockedSellPrice = Math.max(1, basePrice + delta);
                    lockedSellUsedBeadMarketFirst = false;
                    chatLog("[GE] Bead first-try niet verkocht voor " + itemName + " -> fallback prijs " + lockedSellPrice + "gp");
                    return antiBan.varyDelay(randomDelay(750, 1200));
                }
                if (geSellItemAttemptFails >= GE_SELL_MAX_FAILS_PER_ITEM) {
                    chatLog("[GE] [!!] Verkoop blijft falen voor " + itemName
                            + " — skip na " + geSellItemAttemptFails + " pogingen");
                    try {
                        GrandExchange.abortOffer(itemName);
                    } catch (Exception ignored) {
                    }
                    geSellItemAttemptFails = 0;
                    geSellItemIndex++;
                    lockedSellItemName = null;
                    lockedSellPrice = -1;
                    lockedSellUsedBeadMarketFirst = false;
                    return antiBan.varyDelay(randomDelay(900, 1400));
                }
            }
            return antiBan.varyDelay(done ? randomDelay(1200, 2000) : randomDelay(800, 1400));
        }

        // Stap 4: Wacht tot alle offers compleet zijn en collect
        if (GrandExchange.canCollect()) {
            GrandExchange.collect(true);
            geSellTripMadeProgress = true;
            lastGeInteraction = System.currentTimeMillis();
            paint.setCurrentStatus("[GE] GE: Opbrengst ophalen...");
            return antiBan.varyDelay(randomDelay(1200, 2000));
        }

        // Check of er nog lopende offers zijn
        java.util.List<net.runelite.api.GrandExchangeOffer> offers = GrandExchange.getOffers();
        boolean hasActiveOffers = false;
        if (offers != null) {
            for (net.runelite.api.GrandExchangeOffer offer : offers) {
                if (offer != null && offer.getState() != null) {
                    String stateName = offer.getState().name();
                    if (stateName.contains("SELLING") || stateName.contains("BUYING")) {
                        hasActiveOffers = true;
                        break;
                    }
                }
            }
        }

        if (hasActiveOffers) {
            paint.setCurrentStatus("[GE] GE: Wachten op verkoop...");
            return antiBan.varyDelay(randomDelay(2000, 4000));
        }

        // Klaar - sluit GE via ESC
        net.storm.sdk.input.Keyboard.type(String.valueOf((char) java.awt.event.KeyEvent.VK_ESCAPE), false);
        geSellStep = 4;
        chatLog("[GE] GE verkoop afgerond!");
        return antiBan.varyDelay(randomDelay(1000, 2000));
    }

    private boolean isBeadItemName(String itemName) {
        if (itemName == null) return false;
        return itemName.toLowerCase(Locale.ROOT).contains("bead");
    }

    private int resolveBeadFirstTrySellPrice(IInventoryItem item, int fallbackPrice) {
        if (item == null || item.getId() <= 0) return Math.max(1, fallbackPrice);
        try {
            int guide = Prices.getItemPrice(item.getId());
            if (guide <= 0) return Math.max(1, fallbackPrice);
            return Math.max(1, (int) Math.floor(guide * 0.95));
        } catch (Exception ignored) {
            return Math.max(1, fallbackPrice);
        }
    }

    /**
     * Alleen Law runes kopen bij de GE (wanneer bank te weinig had tijdens gear prep).
     * Stappen: loop naar bank (sluit evt.), loop naar GE, open GE, koop Law runes, collect, klaar.
     */
    private int handleBuyLawRunesAtGe(IPlayer local) {
        WorldPoint myPos = local.getWorldLocation();
        int buyPrice = config.impsLawRuneBuyPrice();
        int coins = getCoinCount();

        switch (buyLawRunesStep) {
            case 0: // Loop naar GE (we hebben al coins)
                if (myPos.distanceTo(GE_LOCATION) > 8) {
                    paint.setCurrentStatus("[GE] Law runes: -> Grand Exchange");
                    long now = System.currentTimeMillis();
                    if ((now - lastWalkClickTime) >= GE_WALK_RECLICK_MIN_MS) {
                        MovementHelper.walkTo(GE_LOCATION);
                        lastWalkClickTime = now;
                    }
                    return antiBan.varyDelay(randomDelay((int) GE_WALK_RECLICK_MIN_MS, (int) GE_WALK_RECLICK_MAX_MS));
                }
                buyLawRunesStep = 1;
                return randomDelay(600, 1000);

            case 1: // Open GE en koop Law runes
                if (!GrandExchange.isOpen()) {
                    GrandExchange.open();
                    return antiBan.varyDelay(randomDelay(1500, 2500));
                }
                if (coins < buyPrice * 15) {
                    chatLog("[!] Te weinig coins om Law runes te kopen bij GE");
                    isBuyingLawRunesAtGe = false;
                    buyLawRunesStep = 0;
                    return randomDelay(600, 1000);
                }
                int haveLaws = getItemQuantity("Law rune");
                int desiredTotal = 10;
                if (haveLaws >= desiredTotal) {
                    chatLog("[OK] Genoeg Law runes na GE-koop");
                    net.storm.sdk.input.Keyboard.type(String.valueOf((char) java.awt.event.KeyEvent.VK_ESCAPE), false);
                    isBuyingLawRunesAtGe = false;
                    buyLawRunesStep = 0;
                    return antiBan.varyDelay(randomDelay(800, 1200));
                }
                int priceWithBonus = priceWith2x5Percent(buyPrice);
                paint.setCurrentStatus("[GE] Law runes restock -> tot " + desiredTotal);
                GeRestockHelper.RestockResult rr = GeRestockHelper.buyWithEscalation(
                        "Law rune",
                        desiredTotal,
                        priceWithBonus,
                        () -> config.botEnabled()
                );
                if (!config.botEnabled()) {
                    isBuyingLawRunesAtGe = false;
                    buyLawRunesStep = 0;
                    return 1000;
                }
                lastGeInteraction = System.currentTimeMillis();
                if (rr == GeRestockHelper.RestockResult.SUCCESS) {
                    chatLog("[OK] Law runes restock OK -> tot " + desiredTotal);
                } else {
                    chatLog(" Law runes restock faalde (" + rr + ")");
                }
                // klaar om loop te voorkomen
                isBuyingLawRunesAtGe = false;
                buyLawRunesStep = 0;
                return antiBan.varyDelay(randomDelay(2500, 4000));

        }
        return 1000;
    }

    // ===================== HELPERS =====================

    // getNextWaypoint verwijderd - vervangen door rally point systeem

    private WorldPoint getHuntingArea() {
        if (activeHuntCenter != null) {
            return activeHuntCenter;
        }
        return new WorldPoint(config.impsHuntingX(), config.impsHuntingY(), 0);
    }

    private int getHuntingRadius() {
        if (activeHuntCenter != null && activeHuntRadius > 0) {
            return activeHuntRadius;
        }
        return config.impsHuntingRadius();
    }

    /** Prijs verhogen met 25% = 10% zodat GE-offers sneller vullen. */
    private int priceWith2x5Percent(int basePrice) {
        return (int) Math.round(basePrice * 1.10);
    }

    /**
     * Alle coin-stacks in inventory opgeteld ({@link Inventory#getFirst} telt maar één stack).
     */
    private int getCoinCount() {
        int sum = 0;
        try {
            for (IInventoryItem it : Inventory.getAll()) {
                if (it == null || it.getName() == null) {
                    continue;
                }
                if ("Coins".equalsIgnoreCase(Text.removeTags(it.getName()).trim())) {
                    sum += it.getQuantity();
                }
            }
        } catch (Exception ignored) {
        }
        return sum;
    }

    /** Inventory + bankcoins als bank open is (voor GE-budget). */
    private int getTotalCoinsInvPlusOpenBank() {
        int coins = getCoinCount();
        try {
            if (Bank.isOpen() && Bank.contains("Coins")) {
                var bc = Bank.getFirst("Coins");
                if (bc != null) {
                    coins += bc.getQuantity();
                }
            }
        } catch (Exception ignored) {
        }
        return coins;
    }

    /** Hoeveelheid van {@code name} in bank (0 als bank dicht of stack ontbreekt). */
    private int bankStackQuantity(String name) {
        if (name == null || name.isEmpty()) return 0;
        try {
            if (!Bank.isOpen()) return 0;
            var it = Bank.getFirst(name);
            return it != null ? it.getQuantity() : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * Loop naar dichtstbijzijnde volwaardige bank en open hem.
     * Gebruikt BankHelper voor dynamische bankdetectie.
     */
    private int walkToAndOpenBank(WorldPoint myPos) {
        // Probeer dichtbij te interacten
        if (BankHelper.interactIfNearby()) {
            return antiBan.varyDelay(randomDelay(1500, 2500));
        }
        String[] hoped = (hasDepositItems() || isRestocking) ? null : impsHopedBankWithdrawItems();
        boolean walked = hoped == null || hoped.length == 0
                ? BankHelper.walkToNearestFullBank()
                : BankHelper.walkToNearestFullBank(hoped);
        if (!walked) {
            if (BankHelper.wasLastWalkSkippedDueToSnapshot()) {
                paint.setLastAntiBanAction("Imps: snapshot bank leeg → GE");
            } else {
                paint.setLastAntiBanAction("[!] Geen bank gevonden!");
            }
            return antiBan.varyDelay(randomDelay(2000, 3000));
        }
        paint.setCurrentStatus("Imps: -> Bank");
        return antiBan.varyDelay(randomDelay(1500, 2500));
    }

    /** Runes/staff/coins alleen meenemen in bank-walk als snapshot zegt dat ze er liggen. */
    private String[] impsHopedBankWithdrawItems() {
        String[] candidates = {
                "Mind rune", "Air rune", "Water rune", "Earth rune", "Fire rune", "Body rune",
                "Chaos rune", "Death rune",
                "Staff of air", "Staff of fire", "Staff of water", "Staff of earth",
                "Amulet of power", "Coins"
        };
        java.util.List<String> out = new java.util.ArrayList<>();
        String rsn = BankSnapshotPlanner.currentDisplayName();
        for (String c : candidates) {
            if (BankSnapshotPlanner.shouldWalkToBankForWithdraw(rsn, c)) {
                out.add(c);
            }
        }
        return out.toArray(new String[0]);
    }

    /**
     * Tijdens imps-combat: alleen controleren of er nog genoeg runes voor minstens één cast zijn.
     * Trip-budget ({@link #minCatalystRunesForTrip}) hoort bij bank/restock ({@link #checkIfRestockNeeded}), niet hier.
     */
    private String checkRuneSupply() {
        if (hasRunesForAtLeastOneCast()) {
            return null;
        }
        CombatBotConfig.ImpsMageSpell spell = getActiveMageSpell();
        return "Onvoldoende runes voor 1× " + spell.getSpellName();
    }

    private void chatLog(String message) {
        try {
            net.runelite.api.Client rlClient = net.storm.sdk.game.Client.getClient().getWrapped();
            if (rlClient != null) {
                rlClient.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "[Imps] " + message, null);
            }
        } catch (Exception e) {
            System.out.println("[ImpsChat] " + message);
        }
        // Ook naar debug log
        debugLog(message);
    }

    private void debugLog(String message) {
        DebugLog.log("Imps", message);
    }

    private boolean shouldAbortActions() {
        return config == null || !config.botEnabled();
    }

    private int randomDelay(int min, int max) {
        if (max <= min) {
            return Math.max(0, min);
        }
        return min + random.nextInt((max - min) + 1);
    }

    private void sleep(int min, int max) {
        try { Thread.sleep(randomDelay(min, max)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}




