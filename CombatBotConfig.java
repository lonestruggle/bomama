package com.combatbot;

import net.storm.api.plugins.config.Config;
import net.storm.api.plugins.config.ConfigGroup;
import net.storm.api.plugins.config.ConfigItem;
import net.storm.api.plugins.config.ConfigSection;
import net.storm.api.magic.SpellBook;

@ConfigGroup("combatbot")
public interface CombatBotConfig extends Config {

    // ===================== SECTIONS =====================

    @ConfigSection(name = "▶ Bot Control", description = "Start en stop de bot", position = 0)
    String controlSection = "controlSection";

    @ConfigSection(name = "Combat Settings", description = "Instellingen voor combat", position = 1)
    String combatSection = "combatSection";

    @ConfigSection(name = "Loot Settings", description = "Loot items, waarde, en loot delay (voor alle combat)", position = 2)
    String lootSection = "lootSection";

    @ConfigSection(name = "Banking Settings", description = "Instellingen voor banking", position = 3)
    String bankSection = "bankSection";

    @ConfigSection(name = "Woodcutting Settings", description = "Instellingen voor woodcutting", position = 4)
    String wcSection = "wcSection";

    @ConfigSection(name = "Mining Settings", description = "Instellingen voor mining", position = 5)
    String miningSection = "miningSection";

    @ConfigSection(name = "Fishing Settings", description = "Instellingen voor fishing", position = 6)
    String fishingSection = "fishingSection";

    @ConfigSection(name = "Skill Rotation", description = "Instellingen voor het wisselen tussen skills", position = 7)
    String rotationSection = "rotationSection";

    @ConfigSection(name = "Account Switcher", description = "Ook in plugin: 👤 Accounts-tab (leidend voor rotatie + pad)", position = 8)
    String accountSection = "accountSection";

    @ConfigSection(name = "Re-log (zelfde account)", description = "Tijdelijk uit- en weer inloggen met hetzelfde account", position = 9)
    String reLogoutSection = "reLogoutSection";

    @ConfigSection(name = "Jagex accounts (intern)", description = "Wordt door de Accounts-tab gevuld; niet dubbel onder Settings", position = 10)
    String jagexPasteSection = "jagexPasteSection";

    @ConfigSection(name = "🎯 Imps Mode", description = "Imp hunting op Karamja", position = 11)
    String impsSection = "impsSection";

    @ConfigSection(name = "🗡 Giants Mode", description = "Hill Giants in Edgeville Dungeon", position = 12)
    String giantsSection = "giantsSection";

    @ConfigSection(name = "🚶 Movement", description = "Lopen: vloeiende pad-updates (reclick) voor skills", position = 13)
    String movementSection = "movementSection";

    @ConfigSection(name = "Anti-Ban Settings", description = "Instellingen voor anti-ban", position = 14)
    String antiBanSection = "antiBanSection";

    // ===================== BOT CONTROL =====================

    @ConfigItem(
            keyName = "botEnabled",
            name = "▶ Bot inschakelen (START/STOP)",
            description = "Zet aan om de bot te starten, zet uit om de bot te pauzeren.",
            section = controlSection,
            position = 0
    )
    default boolean botEnabled() { return false; }

    @ConfigItem(
            keyName = "debugDisabledSourcesCsv",
            name = "Debug sources uit (intern)",
            description = "Komma-gescheiden lijst met debug-sources die uit staan; beheerd via Debug-tab.",
            section = controlSection,
            position = 1
    )
    default String debugDisabledSourcesCsv() { return ""; }

    @ConfigItem(
            keyName = "impsForceLargeSteps",
            name = "Forceer grote loopstappen (15-20)",
            description = "Globaal: gebruik waar mogelijk grotere walk-steps van 15-20 tiles (bank/GE/skills), i.p.v. kleine tussenstappen.",
            section = controlSection,
            position = 2
    )
    default boolean impsForceLargeSteps() { return false; }

    @ConfigItem(
            keyName = "debugWalkClickOverlay",
            name = "Debug: walk-klik tiles (overlay)",
            description = "Master-switch voor de walk-tile overlay. Zet hieronder per laag aan/uit (click-tiles met x{count}, en/of pad-tiles met ·{n}). Bewaart max. 2000 unieke tiles, 24 uur zichtbaar. Spoor wordt opgeslagen en blijft na plugin-reload zichtbaar zolang overlay aan staat. Reset via Debug-tab.",
            section = controlSection,
            position = 3
    )
    default boolean debugWalkClickOverlay() { return false; }

    @ConfigItem(
            keyName = "debugWalkOverlayShowClickTiles",
            name = "└ Toon click-tiles (x{n})",
            description = "Tekent ELKE 'Walk here' klik (van bot of jezelf) als heatmap-tile met een x{count} label. Vereist dat de master 'walk-klik tiles (overlay)' aan staat.",
            section = controlSection,
            position = 4
    )
    default boolean debugWalkOverlayShowClickTiles() { return true; }

    @ConfigItem(
            keyName = "debugWalkOverlayShowPathTiles",
            name = "└ Toon pad-tiles (·{n})",
            description = "Tekent elke tile waar de speler overheen wandelt (groene heatmap-tile met ·{n} bij ≥ 2 traversals). Vereist dat de master 'walk-klik tiles (overlay)' aan staat. Tip: zet uit als de overlay te druk wordt en je alleen klikken wil zien.",
            section = controlSection,
            position = 5
    )
    default boolean debugWalkOverlayShowPathTiles() { return true; }

    @ConfigItem(
            keyName = "debugWalkClickPersistedQueue",
            name = "Intern: walk-overlay trail",
            description = "Automatisch beheerd — laatste loop-punten (x,y,vlak,verval). Leegmaken: Debug-tab \"Reset walk-tiles\".",
            section = controlSection,
            position = 6
    )
    default String debugWalkClickPersistedQueue() { return ""; }

    @ConfigItem(
            keyName = "debugWalkAutoLogToDisk",
            name = "└ Auto-log walk-tiles → JSONL",
            description = "Schrijft elke walk-klik en pad-traversal naar ~/.runelite/prive-logs/combat-bot-walk-tiles-YYYY-MM-DD.jsonl voor offline analyse. "
                    + "Eén regel per event ({type, x, y, plane, count, traversals, t}). Vereist dat de master overlay aan staat.",
            section = controlSection,
            position = 7
    )
    default boolean debugWalkAutoLogToDisk() { return false; }

    @ConfigItem(
            keyName = "debugWalkStuckHotspotEnabled",
            name = "└ Hotspot-stuck waarschuwing",
            description = "Logt een waarschuwing in de Debug-tab als één tile binnen het tijdvenster meer dan N keer geklikt wordt — handig om vast te stellen dat de bot op één plek blijft kloppen.",
            section = controlSection,
            position = 8
    )
    default boolean debugWalkStuckHotspotEnabled() { return true; }

    @ConfigItem(
            keyName = "debugWalkStuckHotspotThreshold",
            name = "└ Hotspot-drempel (clicks)",
            description = "Aantal clicks op dezelfde tile binnen het tijdvenster vóór een waarschuwing. Aanbevolen 8–15.",
            section = controlSection,
            position = 9
    )
    default int debugWalkStuckHotspotThreshold() { return 10; }

    @ConfigItem(
            keyName = "debugWalkStuckHotspotWindowSec",
            name = "└ Hotspot-tijdvenster (sec)",
            description = "Tijdvenster waarbinnen de clicks geteld worden. Bijv. 120 = 'als bot 10x op dezelfde tile klikt in 2 minuten → waarschuwing'.",
            section = controlSection,
            position = 10
    )
    default int debugWalkStuckHotspotWindowSec() { return 120; }

    @ConfigItem(
            keyName = "loopWatchRecoveryEnabled",
            name = "LoopWatch: herstel bij vaste loop",
            description = "Als skill+status+tile te lang hetzelfde blijven (zie [LoopWatch] in debug): eerst zacht herstel, daarna bot uit + logout.",
            section = controlSection,
            position = 11
    )
    default boolean loopWatchRecoveryEnabled() { return true; }

    @ConfigItem(
            keyName = "loopWatchTriggerSec",
            name = "LoopWatch: herstel na (sec)",
            description = "Zelfde loop-signature langer dan dit → zacht herstel (handler reset / account-wissel).",
            section = controlSection,
            position = 12
    )
    default int loopWatchTriggerSec() { return 90; }

    @ConfigItem(
            keyName = "loopWatchLogoutSec",
            name = "LoopWatch: logout na (sec)",
            description = "Zelfde loop-signature langer dan dit → bot uit + uitloggen (ook met bank open).",
            section = controlSection,
            position = 13
    )
    default int loopWatchLogoutSec() { return 180; }

    @ConfigItem(
            keyName = "debugAreaContextMenu",
            name = "Debug: rechtermenu centers & tiles",
            description = "Toont in-game bij rechtsklik op een tile de Combat Bot-opties (voeg/verwijder center, radius, tile markers). Uit = alleen het normale OSRS-menu. Schakel ook via tab Debug.",
            section = controlSection,
            position = 14
    )
    default boolean debugAreaContextMenu() { return true; }

    @ConfigItem(
            keyName = "debugMouseOverlay",
            name = "Debug: toon muispositie overlay",
            description = "Toont een cursor-markering + X/Y in-game zodat je precies ziet waar de muis staat.",
            section = controlSection,
            position = 5
    )
    default boolean debugMouseOverlay() { return false; }

    @ConfigItem(
            keyName = "debugInventoryItemIdOverlay",
            name = "Debug: inventory item-ID overlay",
            description = "Tekent item-ID's op elk inventory-vakje (iface 149) alleen als de Inventory-tab open is — niet op Worn Equipment of andere tabs. Clue scrolls tonen ook tier.",
            section = controlSection,
            position = 15
    )
    default boolean debugInventoryItemIdOverlay() { return false; }

    @ConfigItem(
            keyName = "beginnerClueSolverEnabled",
            name = "Beginner clue solver",
            description = "Met beginner clue scroll (23182): bank (inv leeg, kit), GE voor ontbrekende items, Reldo voor Strange device; daarna stappen.",
            section = controlSection,
            position = 16
    )
    default boolean beginnerClueSolverEnabled() { return false; }

    @ConfigItem(
            keyName = "beginnerClueGeBuyMissing",
            name = "Beginner clues: GE koop ontbrekend",
            description = "Na bank: elk ontbrekend kit-item (behalve Strange device) op GE kopen. Device alleen via Reldo.",
            section = controlSection,
            position = 17
    )
    default boolean beginnerClueGeBuyMissing() { return true; }

    // ===================== COMBAT =====================

    @ConfigItem(keyName = "monsterName", name = "Monster namen", description = "Komma-gescheiden lijst van monsters om aan te vallen (bijv: Goblin,Cow,Chicken)", section = combatSection, position = 0)
    default String monsterName() { return "Goblin"; }

    // Food keuze als enum dropdown
    enum FoodChoice {
        ANY,
        SHARK, MONKFISH, LOBSTER, SWORDFISH, TUNA, PIKE, TROUT, SALMON,
        SARDINE, HERRING, ANCHOVIES, SHRIMPS,
        COOKED_CHICKEN, COOKED_MEAT, BASS,
        BREAD, CAKE, CHOCOLATE_CAKE,
        POTATO_WITH_CHEESE, POTATO_WITH_BUTTER,
        STEW, CURRY, CHILI_CON_CARNE,
        JUG_OF_WINE, WINE_OF_ZAMORAK,
        GUTHIX_REST, PURPLE_SWEETS,
        KARAMBWAN, DARK_CRAB,
        MANTA_RAY, SEA_TURTLE,
        ANGLERFISH, CAVE_EEL,
        COOKED_KARAMBWAN;

        public String toItemName() {
            switch (this) {
                case ANY:                    return "any";
                case COOKED_CHICKEN:         return "Cooked chicken";
                case COOKED_MEAT:            return "Cooked meat";
                case JUG_OF_WINE:            return "Jug of wine";
                case WINE_OF_ZAMORAK:        return "Wine of zamorak";
                case GUTHIX_REST:            return "Guthix rest(4)";
                case PURPLE_SWEETS:          return "Purple sweets";
                case DARK_CRAB:              return "Dark crab";
                case MANTA_RAY:              return "Manta ray";
                case SEA_TURTLE:             return "Sea turtle";
                case CAVE_EEL:              return "Cave eel";
                case COOKED_KARAMBWAN:      return "Cooked karambwan";
                case CHILI_CON_CARNE:       return "Chili con carne";
                case POTATO_WITH_CHEESE:    return "Potato with cheese";
                case POTATO_WITH_BUTTER:    return "Potato with butter";
                case CHOCOLATE_CAKE:        return "Chocolate cake";
                default:
                    String raw = name().replace("_", " ");
                    StringBuilder sb = new StringBuilder();
                    for (String word : raw.split(" ")) {
                        if (!word.isEmpty()) {
                            sb.append(Character.toUpperCase(word.charAt(0)))
                                    .append(word.substring(1).toLowerCase())
                                    .append(" ");
                        }
                    }
                    return sb.toString().trim();
            }
        }
    }

    @ConfigItem(keyName = "foodChoice", name = "Food (dropdown)", description = "Kies welk food de bot gebruikt. ANY = alles wat eetbaar is in de inventory.", section = combatSection, position = 1)
    default FoodChoice foodChoice() { return FoodChoice.ANY; }

    @ConfigItem(keyName = "eatPercent", name = "Eat HP %", description = "Eet wanneer HP onder dit percentage daalt", section = combatSection, position = 2)
    default int eatPercent() { return 30; }

    @ConfigItem(keyName = "disableCombatNoFood", name = "Stop combat bij geen food", description = "Pauzeer combat wanneer er geen food in inventory is (tenzij banken aanstaat)", section = combatSection, position = 3)
    default boolean disableCombatNoFood() { return true; }

    @ConfigItem(keyName = "attackRange", name = "Attack range (tiles)", description = "Hoeveel tiles van de safespot de bot mag aanvallen (voor safespot modus).", section = combatSection, position = 4)
    default int attackRange() { return 5; }

    @ConfigItem(keyName = "attackDelayMin", name = "Attack delay min (ms)", description = "Minimale vertraging tussen aanvallen in ms (0 = standaard)", section = combatSection, position = 5)
    default int attackDelayMin() { return 0; }

    @ConfigItem(keyName = "attackDelayMax", name = "Attack delay max (ms)", description = "Maximale vertraging tussen aanvallen in ms (0 = standaard)", section = combatSection, position = 6)
    default int attackDelayMax() { return 0; }

    // ===================== BURY BONES / SCATTER ASHES =====================

    @ConfigItem(keyName = "buryBones", name = "Bury Bones / Scatter Ashes", description = "Automatisch bones begraven en ashes verstrooien uit de inventory", section = combatSection, position = 7)
    default boolean buryBones() { return false; }

    @ConfigItem(keyName = "buryBonesMinBatch", name = "Bones/ashes bij min. X stuks", description = "Gebruik bones/ashes wanneer je minstens zoveel hebt (of inv vol). 1 = na elke kill.", section = combatSection, position = 8)
    default int buryBonesMinBatch() { return 3; }

    // ===================== COMBAT CENTERS =====================

    @ConfigItem(keyName = "combatCenters", name = "Combat centers", description = "Center-locaties voor combat (X:Y:Z:R:Naam). Beheer via rechtermuisklik in-game.", section = combatSection, position = 9)
    default String combatCenters() { return ""; }

    @ConfigItem(keyName = "combatStyle", name = "Combat style (normaal combat)", description = "MELEE, RANGED of MAGE voor gewone combat (onafhankelijk van Imps). Bepaalt welk wapen/runes/ammo uit bank wordt gehaald.", section = combatSection, position = 10)
    default ImpsCombatStyle combatStyle() { return ImpsCombatStyle.MELEE; }

    @ConfigItem(
            keyName = "combatMeleeTrainingStyle",
            name = "Melee attack style (Combat)",
            description = "Alleen als Combat style = MELEE: kies welke skill je traint.",
            section = combatSection,
            position = 10_1
    )
    default MeleeTrainingStyle combatMeleeTrainingStyle() { return MeleeTrainingStyle.BALANCED; }

    @ConfigItem(keyName = "showCombatOverlay", name = "Toon Combat overlay", description = "Toon het combat-gebied als overlay in-game", section = combatSection, position = 11)
    default boolean showCombatOverlay() { return true; }

    // ===================== SAFESPOT =====================

    @ConfigItem(keyName = "safespotEnabled", name = "Safespot inschakelen", description = "Gebruik een safespot voor ranged/magic combat. Voeg safespot toe via rechtermuisklik op een tile.", section = combatSection, position = 12)
    default boolean safespotEnabled() { return false; }

    enum MeleeTrainingStyle {
        BALANCED,
        ATTACK,
        STRENGTH,
        DEFENCE
    }

    // Combat style enum voor imps
    enum ImpsCombatStyle { MELEE, RANGED, MAGE }

    // Mage spell enum
    enum ImpsMageSpell {
        WIND_STRIKE("Wind Strike", "Air rune", "Mind rune", "Staff of air", 1, false, SpellBook.Standard.WIND_STRIKE),
        WATER_STRIKE("Water Strike", "Water rune", "Mind rune", "Staff of water", 5, true, SpellBook.Standard.WATER_STRIKE),
        EARTH_STRIKE("Earth Strike", "Earth rune", "Mind rune", "Staff of earth", 9, true, SpellBook.Standard.EARTH_STRIKE),
        FIRE_STRIKE("Fire Strike", "Fire rune", "Mind rune", "Staff of fire", 13, true, SpellBook.Standard.FIRE_STRIKE),
        WIND_BOLT("Wind Bolt", "Air rune", "Chaos rune", "Staff of air", 17, false, SpellBook.Standard.WIND_BOLT),
        WATER_BOLT("Water Bolt", "Water rune", "Chaos rune", "Staff of water", 23, true, SpellBook.Standard.WATER_BOLT),
        EARTH_BOLT("Earth Bolt", "Earth rune", "Chaos rune", "Staff of earth", 29, true, SpellBook.Standard.EARTH_BOLT),
        FIRE_BOLT("Fire Bolt", "Fire rune", "Chaos rune", "Staff of fire", 35, true, SpellBook.Standard.FIRE_BOLT);

        private final String spellName;
        private final String elementalRune;
        private final String catalystRune;
        private final String preferredStaff;
        private final int levelReq;
        private final boolean needsAirRune; // true als spell EXTRA air runes nodig heeft
        private final SpellBook.Standard standardSpell; // Storm SDK spell enum

        ImpsMageSpell(String spellName, String elementalRune, String catalystRune, String preferredStaff, int levelReq, boolean needsAirRune, SpellBook.Standard standardSpell) {
            this.spellName = spellName;
            this.elementalRune = elementalRune;
            this.catalystRune = catalystRune;
            this.preferredStaff = preferredStaff;
            this.levelReq = levelReq;
            this.needsAirRune = needsAirRune;
            this.standardSpell = standardSpell;
        }

        public String getSpellName() { return spellName; }
        public String getElementalRune() { return elementalRune; }
        public String getCatalystRune() { return catalystRune; }
        public String getPreferredStaff() { return preferredStaff; }
        public int getLevelReq() { return levelReq; }
        /** True als deze spell Air runes nodig heeft BOVENOP de elemental rune (alle non-wind spells) */
        public boolean needsAirRune() { return needsAirRune; }
        /** Geeft de Storm SDK SpellBook.Standard enum waarde voor setAutoCast() */
        public SpellBook.Standard getStandardSpell() { return standardSpell; }
    }

    @ConfigItem(keyName = "impsCenters", name = "Imps centers", description = "Center-locaties (X:Y:Z:R:Naam) via rechtermuisklik. Bij ≥1 actief center: jacht + overlay gebruiken dat center en zijn radius. Anders: Hunting X/Y + radius hieronder.", section = impsSection, position = 0)
    default String impsCenters() { return "2826:3181:0:12:Karamja imps:1"; }

    @ConfigItem(keyName = "impsMode", name = "🎯 Imps Mode", description = "Schakel Imps in als skill in de rotatie. Beheer via de Centers tab in de web GUI.", section = impsSection, position = -1)
    default boolean impsMode() { return false; }

    @ConfigItem(keyName = "impsCombatStyle", name = "Combat style", description = "Kies MELEE, RANGED of MAGE. Bot pakt alleen gear voor deze stijl.", section = impsSection, position = 1)
    default ImpsCombatStyle impsCombatStyle() { return ImpsCombatStyle.MELEE; }

    @ConfigItem(
            keyName = "impsMeleeTrainingStyle",
            name = "Melee attack style (Imps)",
            description = "Alleen als Imps combat style = MELEE: kies welke skill je traint.",
            section = impsSection,
            position = 1_1
    )
    default MeleeTrainingStyle impsMeleeTrainingStyle() { return MeleeTrainingStyle.BALANCED; }

    @ConfigItem(keyName = "impsMageSpell", name = "Mage spell", description = "Welke spell de bot gebruikt (bepaalt welke runes en staff nodig zijn). Bij Magic level te laag voor deze spell: hoogste castbare spell (o.a. Fire Strike 13+ → anders Wind Strike).", section = impsSection, position = 2)
    default ImpsMageSpell impsMageSpell() { return ImpsMageSpell.WIND_STRIKE; }

    @ConfigItem(
            keyName = "impsMageTripCastBudget",
            name = "Mage trip: geplande casts",
            description = "Voor rune-voorraad: o.a. Fire Strike met Fire staff → Air runes nodig = dit × 2 (OSRS: 2 Air per cast). Mind runes ≈ dit × 1. Staf heeft voorkeur boven losse runes waar mogelijk.",
            section = impsSection,
            position = 35
    )
    default int impsMageTripCastBudget() { return 1000; }

    @ConfigItem(keyName = "impsNpcId", name = "Imp NPC ID (Storm)", description = "Storm NPC ID om als imp te zien (standaard 5007). Zet op 0 om alleen op de naam \"Imp\" te filteren.", section = impsSection, position = -2)
    default int impsNpcId() { return 5007; }

    @ConfigItem(keyName = "impsLootItems", name = "Imps loot items", description = "Extra loot voor imps (komma-gescheiden). Blue wizard hat / Wizard hat: altijd automatisch oppakken en dragen zolang je er geen hebt — hoef je hier niet te zetten.", section = impsSection, position = 3)
    default String impsLootItems() { return "Black bead,Red bead,Yellow bead,White bead,Mind rune,Mind talisman,Fiendish ashes"; }

    @ConfigItem(keyName = "impsScatterAshes", name = "Fiendish ashes verstrooien", description = "Verstrooi fiendish ashes automatisch na looten", section = impsSection, position = 4)
    default boolean impsScatterAshes() { return true; }

    @ConfigItem(keyName = "impsBankThreshold", name = "Bank bij X loot items", description = "Ga banken wanneer je dit aantal loot items hebt", section = impsSection, position = 5)
    default int impsBankThreshold() { return 20; }

    @ConfigItem(keyName = "impsMinCoins", name = "Min. coins voor trip", description = "Minimaal aantal coins nodig (heen+terug boot)", section = impsSection, position = 6)
    default int impsMinCoins() { return 60; }

    @ConfigItem(keyName = "impsHuntingX", name = "Hunting area X", description = "Fallback X als er geen actieve Imps centers zijn", section = impsSection, position = 7)
    default int impsHuntingX() { return 2826; }

    @ConfigItem(keyName = "impsHuntingY", name = "Hunting area Y", description = "Fallback Y als er geen actieve Imps centers zijn", section = impsSection, position = 8)
    default int impsHuntingY() { return 3181; }

    @ConfigItem(keyName = "impsHuntingRadius", name = "Hunting radius", description = "Fallback radius (tiles) zonder actieve Imps centers", section = impsSection, position = 9)
    default int impsHuntingRadius() { return 20; }

    @ConfigItem(keyName = "impsAvoidScorpions", name = "Vermijd scorpion zones", description = "Vermijd gebieden met scorpions als je combat level te laag is (lvl > NPC*2+1)", section = impsSection, position = 10)
    default boolean impsAvoidScorpions() { return true; }

    @ConfigItem(keyName = "impsScorpionLevel", name = "Scorpion NPC level", description = "Combat level van de scorpions (voor agro formule: jouw lvl > NPC*2+1)", section = impsSection, position = 11)
    default int impsScorpionLevel() { return 14; }

    @ConfigItem(keyName = "showImpsOverlay", name = "Toon Imps hunting overlay", description = "Toon het imp hunting-gebied als overlay in-game", section = impsSection, position = 12)
    default boolean showImpsOverlay() { return true; }

    @ConfigItem(keyName = "impsIdleRoamSeconds", name = "Idle roam timeout (sec)", description = "Na X seconden zonder imp → loop rond in de zone (0 = uit)", section = impsSection, position = 13)
    default int impsIdleRoamSeconds() { return 10; }

    @ConfigItem(keyName = "impsAttackScorpions", name = "Val scorpions aan (fallback)", description = "Val scorpions aan als er geen imps zijn (aanbevolen i.c.m. vermijd scorpion zones).", section = impsSection, position = 14)
    default boolean impsAttackScorpions() { return true; }

    @ConfigItem(keyName = "impsScorpionZoneRadius", name = "Scorpion zone radius", description = "Radius van de scorpion danger zones in tiles", section = impsSection, position = 15)
    default int impsScorpionZoneRadius() { return 8; }

    @ConfigItem(
            keyName = "impsCompetitorWorldHop",
            name = "Wereld-hop bij andere imp-jager",
            description = "Op Karamja: wissel wereld als een andere speler imps in het jachtgebied aanvalt (per account ook uit te zetten). "
                    + "OSRS vraagt soms om bevestiging: bot klikt Switch world; permanent uitzetten via World Switcher → tandwiel (Configure).",
            section = impsSection,
            position = 35
    )
    default boolean impsCompetitorWorldHop() { return true; }

    @ConfigItem(keyName = "impsGeSellEnabled", name = "🛒 GE verkoop", description = "Verkoop loot via Grand Exchange na X bank trips", section = impsSection, position = 16)
    default boolean impsGeSellEnabled() { return false; }

    @ConfigItem(keyName = "impsGeSellAfterBanks", name = "GE na X bank trips", description = "Na hoeveel bank trips de bot naar de GE gaat om loot te verkopen", section = impsSection, position = 17)
    default int impsGeSellAfterBanks() { return 5; }

    @ConfigItem(keyName = "impsGeSellPrice", name = "GE verkoopprijs (gp)", description = "Verkoopprijs per item in de GE (standaard 999)", section = impsSection, position = 18)
    default int impsGeSellPrice() { return 999; }

    @ConfigItem(keyName = "impsLawRuneBuyPrice", name = "Law rune koopprijs (gp)", description = "GE koopprijs per Law rune (iets boven marktprijs instellen)", section = impsSection, position = 19)
    default int impsLawRuneBuyPrice() { return 300; }

    @ConfigItem(keyName = "impsTeleportBuyRunes", name = "Teleports: runes bijkopen", description = "Koop Law runes in de GE als je te weinig hebt en genoeg coins", section = impsSection, position = 20)
    default boolean impsTeleportBuyRunes() { return true; }

    @ConfigItem(keyName = "impsUseVarrockTeleport", name = "Varrock teleport gebruiken", description = "Gebruik Varrock teleport om sneller naar de GE te gaan (25 Magic+)", section = impsSection, position = 21)
    default boolean impsUseVarrockTeleport() { return true; }

    @ConfigItem(keyName = "impsUseFaladorTeleport", name = "Falador teleport gebruiken", description = "Gebruik Falador teleport bij trips richting Karamja/Port Sarim (indien nuttig)", section = impsSection, position = 22)
    default boolean impsUseFaladorTeleport() { return true; }

    @ConfigItem(keyName = "impsUseLumbridgeTeleport", name = "Lumbridge teleport gebruiken", description = "Gebruik Lumbridge teleport bij trips richting Karamja (indien nuttig)", section = impsSection, position = 23)
    default boolean impsUseLumbridgeTeleport() { return true; }

    @ConfigItem(keyName = "impsStepMinDistance", name = "Loop stap min (tiles)", description = "Minimale stap-grootte voor lange afstanden (jachtgebied, dock, …)", section = impsSection, position = 24)
    default int impsStepMinDistance() { return 10; }

    @ConfigItem(keyName = "impsStepMaxDistance", name = "Loop stap max (tiles)", description = "Maximale stap-grootte voor lange afstanden (jachtgebied, dock, …)", section = impsSection, position = 25)
    default int impsStepMaxDistance() { return 22; }

    @ConfigItem(keyName = "impsRallyPointRadius", name = "Rally point radius (tiles)", description = "Binnen deze radius van het rally point wordt geacht 'aangekomen'; bot loopt niet helemaal naar center", section = impsSection, position = 26)
    default int impsRallyPointRadius() { return 10; }

    @ConfigItem(
            keyName = "impsArrivalRadius",
            name = "Arrival radius (tiles)",
            description = "Wanneer de bot dicht genoeg bij hunt-center is om met zoeken te starten (lager = dichterbij lopen)",
            section = impsSection,
            position = 27
    )
    default int impsArrivalRadius() { return 12; }

    @ConfigItem(keyName = "impsShowRallyRadius", name = "Toon rally radius overlay", description = "Toon de rally point radius in-game als overlay", section = impsSection, position = 28)
    default boolean impsShowRallyRadius() { return false; }

    @ConfigItem(
            keyName = "impsGoblinCoinCenterX",
            name = "Goblin coin center X",
            description = "Center X voor coin-recovery goblin gebied (Port Sarim fallback)",
            section = impsSection,
            position = 28_1
    )
    default int impsGoblinCoinCenterX() { return 3002; }

    @ConfigItem(
            keyName = "impsGoblinCoinCenterY",
            name = "Goblin coin center Y",
            description = "Center Y voor coin-recovery goblin gebied (Port Sarim fallback)",
            section = impsSection,
            position = 28_2
    )
    default int impsGoblinCoinCenterY() { return 3207; }

    @ConfigItem(
            keyName = "impsGoblinCoinRadius",
            name = "Goblin coin radius",
            description = "Radius (tiles) van het goblin coin-recovery gebied",
            section = impsSection,
            position = 28_3
    )
    default int impsGoblinCoinRadius() { return 12; }

    @ConfigItem(
            keyName = "impsShowGoblinCoinOverlay",
            name = "Toon goblin coin overlay",
            description = "Toon de coin-recovery goblin zone als overlay in-game",
            section = impsSection,
            position = 28_4
    )
    default boolean impsShowGoblinCoinOverlay() { return true; }

    @ConfigItem(keyName = "impsSellNow", name = "🛒 Sell now (start GE verkoop)", description = "Vink aan om direct naar de GE te gaan om loot te verkopen", section = impsSection, position = 29)
    default boolean impsSellNow() { return false; }

    @ConfigItem(keyName = "impsFallbackStyle", name = "Fallback combat style",
        description = "Als primaire style niet mogelijk is (geen gear/ammo/geld), schakel over naar deze stijl. MELEE vereist geen ammo.",
        section = impsSection, position = 29)
    default ImpsCombatStyle impsFallbackStyle() { return ImpsCombatStyle.MELEE; }

    @ConfigItem(keyName = "impsAmmoRestockPrice", name = "Ammo/rune koopprijs (gp)",
        description = "Maximale GE prijs per rune/arrow bij automatische restock wanneer bank leeg is",
        section = impsSection, position = 30)
    default int impsAmmoRestockPrice() { return 10; }

    @ConfigItem(
            keyName = "impsMeleeOpeningAirStrike",
            name = "Melee: open met 1x Air Strike",
            description = "Als aan: in melee mode probeert de bot elke imp eerst 1x met Air Strike te taggen en neemt Air+Mind runes mee als beschikbaar.",
            section = impsSection,
            position = 31
    )
    default boolean impsMeleeOpeningAirStrike() { return false; }

    @ConfigItem(
            keyName = "impsMeleeOpeningAirStrikeMinDistance",
            name = "Melee opener min afstand",
            description = "Alleen Air Strike opener gebruiken als imp verder staat dan deze afstand (tiles).",
            section = impsSection,
            position = 32
    )
    default int impsMeleeOpeningAirStrikeMinDistance() { return 4; }

    @ConfigItem(
            keyName = "impsMeleeOpeningAirStrikeDebug",
            name = "Melee opener debug",
            description = "Log in debug tab waarom Air Strike opener wel/niet triggert.",
            section = impsSection,
            position = 33
    )
    default boolean impsMeleeOpeningAirStrikeDebug() { return true; }

    @ConfigItem(
            keyName = "starterMeleeStyleDebug",
            name = "Starter melee-style debug",
            description = "Log in debug tab wanneer de starter melee-style wisselt.",
            section = rotationSection,
            position = 15
    )
    default boolean starterMeleeStyleDebug() { return true; }

    @ConfigItem(
            keyName = "impsStayInsideRadius",
            name = "Blijf binnen hunting radius (legacy)",
            description = "Niet meer van toepassing: idle roaming blijft altijd binnen de hunting cirkel — jagen en imp-loot alleen binnen die zone.",
            section = impsSection,
            position = 34
    )
    default boolean impsStayInsideRadius() { return false; }

    // ===================== TILE MARKERS =====================

    @ConfigSection(name = "Tile Markers", description = "Gemarkeerde tiles per skill — rechtermuisklik om te markeren", position = 11)
    String tileMarkerSection = "tileMarkerSection";

    @ConfigItem(keyName = "combatTiles", name = "Combat tiles (opgeslagen)", description = "Intern — automatisch bijgewerkt door rechtermuisklik in-game", section = tileMarkerSection, position = 0)
    default String combatTiles() { return ""; }

    @ConfigItem(keyName = "wcTiles", name = "WC tiles (opgeslagen)", description = "Intern — automatisch bijgewerkt door rechtermuisklik in-game", section = tileMarkerSection, position = 1)
    default String wcTiles() { return ""; }

    @ConfigItem(keyName = "miningTiles", name = "Mining tiles (opgeslagen)", description = "Intern — automatisch bijgewerkt door rechtermuisklik in-game", section = tileMarkerSection, position = 2)
    default String miningTiles() { return ""; }

    @ConfigItem(keyName = "fishingTiles", name = "Fishing tiles (opgeslagen)", description = "Intern — automatisch bijgewerkt door rechtermuisklik in-game", section = tileMarkerSection, position = 3)
    default String fishingTiles() { return ""; }

    @ConfigItem(keyName = "tilePresetName", name = "Preset naam", description = "Naam voor opslaan van de huidige tile markering", section = tileMarkerSection, position = 4)
    default String tilePresetName() { return "Mijn preset"; }

    @ConfigItem(keyName = "saveTilePreset", name = "💾 Sla tile preset op", description = "Sla alle huidige tile markeringen op als preset", section = tileMarkerSection, position = 5)
    default boolean saveTilePreset() { return false; }

    @ConfigItem(keyName = "tilePresets", name = "Opgeslagen presets", description = "Opgeslagen tile preset sets — intern beheerd", section = tileMarkerSection, position = 6)
    default String tilePresets() { return ""; }

    @ConfigItem(keyName = "loadTilePreset", name = "Preset laden (naam)", description = "Voer de naam in van een opgeslagen preset om te laden", section = tileMarkerSection, position = 7)
    default String loadTilePreset() { return ""; }

    @ConfigItem(keyName = "doLoadTilePreset", name = "📂 Laad tile preset", description = "Laad de tile preset met de naam ingevuld bij 'Preset laden (naam)'", section = tileMarkerSection, position = 8)
    default boolean doLoadTilePreset() { return false; }

    @ConfigItem(keyName = "clearTileMarkers", name = "🗑 Wis alle tile markers", description = "Verwijder alle tile markeringen voor de actieve skill", section = tileMarkerSection, position = 9)
    default boolean clearTileMarkers() { return false; }

    // ===================== LOOT =====================

    @ConfigItem(keyName = "lootItems", name = "Loot items", description = "Komma-gescheiden lijst van items om op te rapen. Typ 'rune' om alle runes te looten.", section = lootSection, position = 0)
    default String lootItems() { return "Bones,Coins"; }

    @ConfigItem(keyName = "lootMinValue", name = "Min HA waarde", description = "Raap items op met HA waarde boven dit bedrag", section = lootSection, position = 1)
    default int lootMinValue() { return 0; }

    @ConfigItem(keyName = "lootByMinValue", name = "Loot op minimale waarde", description = "Pak ook items op die niet in de lijst staan maar boven de min HA waarde zijn.", section = lootSection, position = 2)
    default boolean lootByMinValue() { return false; }

    @ConfigItem(keyName = "lootOnlyOwn", name = "Alleen eigen drops", description = "Raap alleen items op die door jou gedropped zijn (jouw kills)", section = lootSection, position = 3)
    default boolean lootOnlyOwn() { return true; }

    @ConfigItem(keyName = "specialLootItems", name = "Speciale loots (direct)", description = "Items die ALTIJD direct opgepakt worden (komma-gescheiden)", section = lootSection, position = 4)
    default String specialLootItems() { return ""; }

    @ConfigItem(keyName = "lootBonesAndAshes", name = "Pak bones & Fiendish ashes", description = "Raap ook bones (alle types) en Fiendish ashes op (eigen drops)", section = lootSection, position = 5)
    default boolean lootBonesAndAshes() { return false; }

    @ConfigItem(keyName = "lootDelayEnabled", name = "Loot delay (per kills)", description = "Ga door met killen tot X kills, pak dan al je loot op", section = lootSection, position = 6)
    default boolean lootDelayEnabled() { return false; }

    @ConfigItem(keyName = "lootDelayKills", name = "Kills vóór looten", description = "Bereik dit aantal kills, pak daarna alle loot op (0 = direct looten)", section = lootSection, position = 7)
    default int lootDelayKills() { return 2; }

    @ConfigItem(keyName = "lootDelayMinSeconds", name = "Min. wachttijd (sec)", description = "Optioneel: wacht min. zoveel seconden (0 = alleen kills tellen)", section = lootSection, position = 8)
    default int lootDelayMinSeconds() { return 0; }

    @ConfigItem(keyName = "lootDelayMaxSeconds", name = "Max. wachttijd (sec)", description = "Optioneel: max seconden (0 = alleen kills tellen)", section = lootSection, position = 9)
    default int lootDelayMaxSeconds() { return 0; }

    @ConfigItem(
            keyName = "eatToMakeSpaceForLoot",
            name = "Eet om ruimte vrij te maken voor loot",
            description = "Als je inventory vol is, er ligt loot op de grond (geen bones/ashes) en je HP is niet vol → "
                    + "eet 1 portie food om een slot vrij te maken. Werkt voor alle skills die looten "
                    + "(Combat, Giants, Imps, Barb-loot). Bury/scatter heeft altijd voorrang.",
            section = lootSection,
            position = 10
    )
    default boolean eatToMakeSpaceForLoot() { return true; }

    // ===================== BANKING =====================

    @ConfigItem(keyName = "bankWhenNoFood", name = "Bank als food op is", description = "Ga naar de bank wanneer je geen food meer hebt. Alleen van toepassing op Combat- en Giants-mode.", section = bankSection, position = 0)
    default boolean bankWhenNoFood() { return true; }

    @ConfigItem(keyName = "bankLootedItems", name = "Bank gelootte items", description = "Bank automatisch als je inventory vol zit met gelootte items (minimaal 5 loot items nodig)", section = bankSection, position = 1)
    default boolean bankLootedItems() { return false; }

    @ConfigItem(keyName = "foodAmount", name = "Aantal food uit bank", description = "Hoeveel food ophalen uit de bank", section = bankSection, position = 2)
    default int foodAmount() { return 10; }

    // ===================== WOODCUTTING =====================

    @ConfigItem(keyName = "wcEnabled", name = "Woodcutting inschakelen", description = "Schakel woodcutting in als skill rotatie", section = wcSection, position = 0)
    default boolean wcEnabled() { return false; }

    @ConfigItem(keyName = "wcUseSpecificTree", name = "Specifieke boom", description = "Gebruik een specifieke boom i.p.v. beste voor je level", section = wcSection, position = 1)
    default boolean wcUseSpecificTree() { return false; }

    @ConfigItem(keyName = "wcTreeName", name = "Boom naam", description = "Naam van de specifieke boom (bijv. Willow, Yew)", section = wcSection, position = 2)
    default String wcTreeName() { return "Willow"; }

    @ConfigItem(keyName = "wcDropLogs", name = "Logs droppen", description = "Drop logs i.p.v. banken (powerchopping)", section = wcSection, position = 3)
    default boolean wcDropLogs() { return true; }

    @ConfigItem(keyName = "wcFiremaking", name = "🔥 Firemaking (bonfire)", description = "Verbrand logs op je eigen vuur. Pakt tinderbox uit bank als nodig.", section = wcSection, position = 4)
    default boolean wcFiremaking() { return false; }

    @ConfigItem(
            keyName = "wcAxeCashViaImpsEnabled",
            name = "WC: farm cash via Imps voor axe",
            description = "Als beste axe voor je level niet in bank staat en gp te laag is: tijdelijk Imps doen voor cash.",
            section = wcSection,
            position = 4_1
    )
    default boolean wcAxeCashViaImpsEnabled() { return true; }

    @ConfigItem(
            keyName = "wcAxeImpsTripCap",
            name = "WC: max Imps trips voor axe",
            description = "Maximaal aantal geschatte Imps bank-trips voor cash-farm voordat bot teruggaat.",
            section = wcSection,
            position = 4_2
    )
    default int wcAxeImpsTripCap() { return 8; }

    @ConfigItem(keyName = "wcCenters", name = "WC centers", description = "Center-locaties voor woodcutting (X:Y:Z:R:Naam). Beheer via rechtermuisklik in-game.", section = wcSection, position = 5)
    default String wcCenters() { return "3086:3232:0:14:Draynor willows:1"; }

    @ConfigItem(keyName = "showWcOverlay", name = "Toon WC overlay", description = "Toon het woodcutting-gebied als overlay in-game", section = wcSection, position = 6)
    default boolean showWcOverlay() { return true; }

    @ConfigItem(keyName = "wcInteractDelayMin", name = "WC delay min (ms)", description = "Minimale vertraging voordat de volgende boom wordt gekapt (0 = standaard)", section = wcSection, position = 7)
    default int wcInteractDelayMin() { return 0; }

    @ConfigItem(keyName = "wcInteractDelayMax", name = "WC delay max (ms)", description = "Maximale vertraging voordat de volgende boom wordt gekapt (0 = standaard)", section = wcSection, position = 8)
    default int wcInteractDelayMax() { return 0; }

    // ===================== MINING =====================

    @ConfigItem(keyName = "miningEnabled", name = "Mining inschakelen", description = "Schakel mining in als skill rotatie", section = miningSection, position = 0)
    default boolean miningEnabled() { return false; }

    @ConfigItem(
            keyName = "miningDoricsQuestAuto",
            name = "Doric's Quest automatisch",
            description = "Bij mining onder level 10: eerst 6 clay, 4 copper ore en 2 iron ore (unnoted) uit bank/GE, "
                    + "daarna één trip naar Doric om te starten én af te ronden.",
            section = miningSection,
            position = 1
    )
    default boolean miningDoricsQuestAuto() { return true; }

    @ConfigItem(keyName = "miningUseSpecificOre", name = "Specifiek erts", description = "Gebruik een specifiek erts i.p.v. beste voor je level", section = miningSection, position = 2)
    default boolean miningUseSpecificOre() { return false; }

    @ConfigItem(keyName = "miningOreName", name = "Erts naam", description = "Naam van het specifieke erts (bijv. Iron rocks, Coal rocks)", section = miningSection, position = 3)
    default String miningOreName() { return "Iron rocks"; }

    @ConfigItem(
            keyName = "miningDropOre",
            name = "Erts droppen (standaard)",
            description = "Standaard bij volle inventaris: erts droppen i.p.v. banken. "
                    + "Overschreven per locatie: Al Kharid 2 = altijd banken (ijzer), "
                    + "Al Kharid 3 = altijd droppen (ijzer). Zie paint: spot · bank/drop.",
            section = miningSection,
            position = 4
    )
    default boolean miningDropOre() { return true; }

    @ConfigItem(keyName = "miningCenters", name = "Mining centers", description = "Center-locaties voor mining (X:Y:Z:R:Naam). Beheer via rechtermuisklik in-game.", section = miningSection, position = 5)
    default String miningCenters() {
        return "3285:3368:0:14:Varrock east mine:1|"
                + "3226:3146:0:10:lumb zuid:1|"
                + "3232:3148:0:10:draynor zuid:1|"
                + "3297:3291:0:15:alkarid 2:1|"
                + "3295:3310:0:4:alkarid 3:1";
    }

    @ConfigItem(keyName = "showMiningOverlay", name = "Toon Mining overlay", description = "Toon het mining-gebied als overlay in-game", section = miningSection, position = 6)
    default boolean showMiningOverlay() { return true; }

    @ConfigItem(keyName = "showMiningRockTarget", name = "Markeer doelrots", description = "Highlight de rots die de bot gaat minen (tile in-game)", section = miningSection, position = 7)
    default boolean showMiningRockTarget() { return true; }

    @ConfigItem(keyName = "miningInteractDelayMin", name = "Mining delay min (ms)", description = "Minimale vertraging voordat de volgende rots wordt gemijnd (0 = standaard)", section = miningSection, position = 8)
    default int miningInteractDelayMin() { return 0; }

    @ConfigItem(keyName = "miningInteractDelayMax", name = "Mining delay max (ms)", description = "Maximale vertraging voordat de volgende rots wordt gemijnd (0 = standaard)", section = miningSection, position = 9)
    default int miningInteractDelayMax() { return 0; }

    /**
     * Intern: welke ingebouwde mining-standaardset al is samengevoegd met jouw bestaande miningCenters.
     * Bij verhoging in de plugin worden ontbrekende standaard-tiles toegevoegd (geen restore van combat/wc/fish/imps).
     */
    @ConfigItem(keyName = "miningBuiltinPackVersion", name = "[intern] Mining-standaardset", description = "0 = nog niet samengevoegd. Niet handmatig wijzigen tenzij je opnieuw wilt laten aanvullen.", section = miningSection, position = 50)
    default int miningBuiltinPackVersion() { return 0; }

    // ===================== FISHING =====================

    @ConfigItem(keyName = "fishingEnabled", name = "Fishing inschakelen", description = "Schakel fishing in als skill rotatie", section = fishingSection, position = 0)
    default boolean fishingEnabled() { return false; }

    @ConfigItem(keyName = "fishingUseSpecificMethod", name = "Specifieke methode", description = "Kies een specifieke vismethode i.p.v. auto-detect", section = fishingSection, position = 1)
    default boolean fishingUseSpecificMethod() { return false; }

    @ConfigItem(keyName = "fishingSpotName", name = "Spot naam", description = "Naam van de fishing spot NPC (bijv. Fishing spot, Cage/Harpoon fishing spot)", section = fishingSection, position = 2)
    default String fishingSpotName() { return "Fishing spot"; }

    @ConfigItem(keyName = "fishingAction", name = "Vis actie", description = "Actie op de spot (bijv. Net, Bait, Lure, Cage, Harpoon)", section = fishingSection, position = 3)
    default String fishingAction() { return "Net"; }

    @ConfigItem(keyName = "fishingDropFish", name = "Vis droppen", description = "Drop vis i.p.v. banken (als cooking uit staat)", section = fishingSection, position = 4)
    default boolean fishingDropFish() { return true; }

    @ConfigItem(keyName = "fishingCookEnabled", name = "🔥 Cooking inschakelen", description = "Kook vis bij een vuur voordat je dropt/bankt", section = fishingSection, position = 5)
    default boolean fishingCookEnabled() { return false; }

    @ConfigItem(keyName = "fishingRestockEnabled", name = "🛒 Bait restock (GE)", description = "Koop automatisch bait bij de Grand Exchange als het op is", section = fishingSection, position = 6)
    default boolean fishingRestockEnabled() { return true; }

    @ConfigItem(keyName = "fishingRestockAmount", name = "Restock hoeveelheid", description = "Willekeurig tussen 500 en dit aantal bait kopen", section = fishingSection, position = 7)
    default int fishingRestockAmount() { return 1000; }

    @ConfigItem(keyName = "fishingBaitName", name = "Bait naam (Universal Banking)", description = "Itemnaam voor bait (bijv. Feather, Fishing bait). Bank onder min, haal target op.", section = fishingSection, position = 8)
    default String fishingBaitName() { return "Feather"; }

    @ConfigItem(keyName = "fishingBaitMin", name = "Bait min (bank onder)", description = "Ga banken als bait onder dit aantal", section = fishingSection, position = 9)
    default int fishingBaitMin() { return 50; }

    @ConfigItem(keyName = "fishingBaitTarget", name = "Bait target (ophalen)", description = "Haal tot dit aantal bait uit de bank", section = fishingSection, position = 10)
    default int fishingBaitTarget() { return 500; }

    @ConfigItem(keyName = "fishingBaitPrice", name = "Max prijs per Bait (gp)", description = "Maximale prijs per Fishing bait in de GE", section = fishingSection, position = 11)
    default int fishingBaitPrice() { return 5; }

    @ConfigItem(keyName = "fishingFeatherPrice", name = "Max prijs per Feather (gp)", description = "Maximale prijs per Feather in de GE", section = fishingSection, position = 12)
    default int fishingFeatherPrice() { return 3; }

    @ConfigItem(keyName = "fishingCenters", name = "Fishing centers", description = "Center-locaties voor fishing (X:Y:Z:R:Naam). Beheer via rechtermuisklik in-game.", section = fishingSection, position = 13)
    default String fishingCenters() { return "3104:3433:0:14:Barbarian village:1|3090:3230:0:14:Draynor village:1"; }

    @ConfigItem(keyName = "fishingUseVarrockTeleport", name = "Varrock teleport naar GE", description = "Gebruik Varrock teleport om sneller naar de GE te gaan (25 Magic+, runes uit bank)", section = fishingSection, position = 14)
    default boolean fishingUseVarrockTeleport() { return true; }

    // ===================== GLOBAL GE SELLING =====================

    @ConfigSection(name = "🛒 GE Verkoop", description = "Verkoop loot via de Grand Exchange", position = 8)
    String geSection = "geSection";

    @ConfigItem(keyName = "geSellEnabled", name = "GE verkoop inschakelen", description = "Verkoop loot automatisch via de Grand Exchange", section = geSection, position = 0)
    default boolean geSellEnabled() { return false; }

    enum GeSellPriceMode { FIXED, PERCENT_BELOW_GE }

    @ConfigItem(keyName = "geSellPriceMode", name = "Prijs modus", description = "FIXED = vaste prijs, PERCENT_BELOW_GE = percentage onder marktprijs", section = geSection, position = 1)
    default GeSellPriceMode geSellPriceMode() { return GeSellPriceMode.FIXED; }

    @ConfigItem(keyName = "geSellFixedPrice", name = "Vaste verkoopprijs (gp)", description = "Verkoopprijs per item als modus FIXED is", section = geSection, position = 2)
    default int geSellFixedPrice() { return 999; }

    @ConfigItem(keyName = "geSellPercentBelow", name = "% onder GE prijs", description = "Verkoop X% onder de huidige GE marktprijs (bijv. 5 = 5% korting)", section = geSection, position = 3)
    default int geSellPercentBelow() { return 5; }

    @ConfigItem(keyName = "geSellBeadMarketFirst", name = "Beads: eerst markt-5% (met fallback)", description = "Probeer bij beads eerst ~5% onder actuele markt/guide prijs. Als dat niet verkoopt: gebruik de ingestelde verkoopprijs.", section = geSection, position = 4)
    default boolean geSellBeadMarketFirst() { return true; }

    @ConfigItem(keyName = "geSellLootItems", name = "GE verkoop items", description = "Komma-gescheiden lijst van items om in de GE te verkopen", section = geSection, position = 5)
    default String geSellLootItems() { return ""; }

    @ConfigItem(keyName = "geSellAfterBanks", name = "GE na X bank trips", description = "Na hoeveel bank trips de bot naar de GE gaat om loot te verkopen", section = geSection, position = 6)
    default int geSellAfterBanks() { return 5; }

    @ConfigItem(keyName = "showFishingOverlay", name = "Toon Fishing overlay", description = "Toon het fishing-gebied als overlay in-game", section = fishingSection, position = 14)
    default boolean showFishingOverlay() { return true; }

    @ConfigItem(keyName = "fishingInteractDelayMin", name = "Fishing delay min (ms)", description = "Minimale vertraging voordat de volgende spot wordt gevist (0 = standaard)", section = fishingSection, position = 15)
    default int fishingInteractDelayMin() { return 0; }

    @ConfigItem(keyName = "fishingInteractDelayMax", name = "Fishing delay max (ms)", description = "Maximale vertraging voordat de volgende spot wordt gevist (0 = standaard)", section = fishingSection, position = 16)
    default int fishingInteractDelayMax() { return 0; }

    // ===================== SKILL ROTATION =====================

    enum StartSkill {
        /** Eenmalige Tutorial Island pre-skill voor nieuwe accounts. */
        TUT("Tutorial (pre-skill)"),
        /** F2P starter-pad (Draynor → train → Jon → imps-bridge). */
        STARTER("Starter"),
        COMBAT("Combat"),
        BARBARIAN("Barbarian"),
        WOODCUTTING("Woodcutting"),
        MINING("Mining"),
        FISHING("Fishing"),
        IMPS("Imps"),
        GIANTS("Giants"),
        LOOT("Loot (Barb fish)"),
        /** Start met Barb loot alleen nuttig bij Magic &lt; 5; geen verplichte Barb-loot-vink. */
        BARB_LOOT("Barb loot (start, Magic<5)"),
        /** Vampyre Slayer quest (als nog niet voltooid; daarna val je terug op Combat-centers). */
        VAMPIRE_SLAYER("Vampyre Slayer (quest)"),
        RANDOM("Random");

        private final String label;

        StartSkill(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Voorkeur skill voor Genie lamp interface. */
    enum GenieLampSkill {
        NONE("Geen (niet gebruiken)"),
        ATTACK("Attack"),
        STRENGTH("Strength"),
        DEFENCE("Defence"),
        HITPOINTS("Hitpoints"),
        RANGED("Ranged"),
        PRAYER("Prayer"),
        MAGIC("Magic"),
        COOKING("Cooking"),
        WOODCUTTING("Woodcutting"),
        FLETCHING("Fletching"),
        FISHING("Fishing"),
        FIREMAKING("Firemaking"),
        CRAFTING("Crafting"),
        SMITHING("Smithing"),
        MINING("Mining"),
        HERBLORE("Herblore"),
        AGILITY("Agility"),
        THIEVING("Thieving"),
        SLAYER("Slayer"),
        FARMING("Farming"),
        RUNECRAFT("Runecraft"),
        HUNTER("Hunter"),
        CONSTRUCTION("Construction"),
        SAILING("Sailing");

        private final String displayName;
        GenieLampSkill(String displayName) { this.displayName = displayName; }
        public String displayName() { return displayName; }
    }

    @ConfigItem(keyName = "showStarterOverlay", name = "Toon Starter train-gebied", description = "Tekent het vaste starter-trainradius (combat/WC) op de grond wanneer de actieve modus Starter is.", section = rotationSection, position = -1)
    default boolean showStarterOverlay() { return true; }

    /** Train-anker voor Start skill = Starter (Draynor vs Lumbridge koeienveld). */
    enum StarterTrainRegion {
        DRAYNOR("Draynor (rats + goblins)"),
        LUMBRIDGE_SHEEP("Lumbridge koeienveld (cows, geen goblins op train)");

        private final String label;

        StarterTrainRegion(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    @ConfigItem(
            keyName = "starterTrainRegion",
            name = "Starter: train-gebied",
            description = "Alleen bij Start skill = Starter. Draynor = klassiek spot met goblins voor coins op train. Lumbridge koeienveld = cows/cow calf; bank via kasteel (trap); na doelen: bank, Jon (Claim, betere gear), bank rommel, en alleen als je nog geen 30 gp hebt: Port Sarim goblins, dan imps.",
            section = rotationSection,
            position = -2
    )
    default StarterTrainRegion starterTrainRegion() { return StarterTrainRegion.DRAYNOR; }

    @ConfigItem(
            keyName = "starterMeleeTrainingStyle",
            name = "Starter melee attack style",
            description = "Starter: BALANCED rouleert op laagste stat; andere keuzes forceren een vaste style.",
            section = rotationSection,
            position = -3
    )
    default MeleeTrainingStyle starterMeleeTrainingStyle() { return MeleeTrainingStyle.BALANCED; }

    @ConfigItem(keyName = "startSkill", name = "Start skill", description = "Met welke skill de bot begint bij het opstarten. Starter = F2P-pad (Draynor/train/Jon) → imps met 30 gp-bridge. Vampyre Slayer = quest-flow tot voltooid (ook via Centers-tab vink). RANDOM = willekeurig uit aangevinkte skills. Barb loot (start): alleen bij Magic < 5; wordt per account onthouden na Magic ≥ 5.", section = rotationSection, position = 0)
    default StartSkill startSkill() { return StartSkill.COMBAT; }

    @ConfigItem(
            keyName = "tutModeEnabled",
            name = "Tut mode (eenmalig pre-skill)",
            description = "Als aan: nieuwe accounts doen eerst Tutorial Island voordat normale skills starten.",
            section = rotationSection,
            position = 0
    )
    default boolean tutModeEnabled() { return false; }

    @ConfigItem(
            keyName = "tutDirectToStarter",
            name = "Tut: direct naar Starter",
            description = "Na Tutorial Island direct door naar Starter in plaats van normale start-skill resolutie.",
            section = rotationSection,
            position = 0
    )
    default boolean tutDirectToStarter() { return true; }

    @ConfigItem(
            keyName = "tutSwitchAccountOnStuck",
            name = "Tut: switch account bij stuck",
            description = "Als Tut te lang op dezelfde stap blijft hangen: meld stuck, log uit en ga naar volgende account.",
            section = rotationSection,
            position = 0
    )
    default boolean tutSwitchAccountOnStuck() { return true; }

    @ConfigItem(
            keyName = "tutDisplayNamePool",
            name = "Tut: display-name lijst",
            description = "Optioneel: komma- of regelgescheiden RuneScape-namen. De bot kiest willekeurig; namen die 'not available' zijn worden deze sessie overgeslagen. Leeg = automatisch gegenereerde namen.",
            section = rotationSection,
            position = 0
    )
    default String tutDisplayNamePool() { return ""; }

    @ConfigItem(keyName = "combatInRotation", name = "Combat in rotatie", description = "Als aan: combat is een skill in de rotatie. Als uit: alleen WC/Mining/Fishing/Imps roteren (combat niet standaard mee).", section = rotationSection, position = 1)
    default boolean combatInRotation() { return false; }

    @ConfigItem(keyName = "barbarianMode", name = "🛡 Barbarian mode (skill)", description = "Vecht Barbarians in Varrock longhall, pak cooked meat en bank in Edgeville.", section = rotationSection, position = 1)
    default boolean barbarianMode() { return false; }

    @ConfigItem(keyName = "barbarianHallX", name = "Barbarian hall X", description = "Center X voor Barbarian longhall", section = rotationSection, position = 1)
    default int barbarianHallX() { return 3078; }

    @ConfigItem(keyName = "barbarianHallY", name = "Barbarian hall Y", description = "Center Y voor Barbarian longhall", section = rotationSection, position = 1)
    default int barbarianHallY() { return 3441; }

    @ConfigItem(keyName = "barbarianHallRadius", name = "Barbarian hall radius", description = "Radius (tiles) voor Barbarian longhall", section = rotationSection, position = 1)
    default int barbarianHallRadius() { return 6; }

    @ConfigItem(keyName = "barbarianPickupCookedMeat", name = "Pak cooked meat", description = "Pak cooked meat van de longhall tafel als gratis food-aanvulling", section = rotationSection, position = 1)
    default boolean barbarianPickupCookedMeat() { return true; }

    @ConfigItem(keyName = "barbarianFoodMin", name = "Barbarian food min", description = "Als food <= dit aantal: banken of tafel-food pakken", section = rotationSection, position = 1)
    default int barbarianFoodMin() { return 4; }

    @ConfigItem(keyName = "barbarianFoodTarget", name = "Barbarian food target", description = "Doel-aantal food in inventory na banken/pakken", section = rotationSection, position = 1)
    default int barbarianFoodTarget() { return 12; }

    @ConfigItem(keyName = "barbarianCorner1X", name = "Barbarian hoek 1 X", description = "Longhall hoek-coord 1 X", section = rotationSection, position = 1)
    default int barbarianCorner1X() { return 3075; }

    @ConfigItem(keyName = "barbarianCorner1Y", name = "Barbarian hoek 1 Y", description = "Longhall hoek-coord 1 Y", section = rotationSection, position = 1)
    default int barbarianCorner1Y() { return 3445; }

    @ConfigItem(keyName = "barbarianCorner2X", name = "Barbarian hoek 2 X", description = "Longhall hoek-coord 2 X", section = rotationSection, position = 1)
    default int barbarianCorner2X() { return 3082; }

    @ConfigItem(keyName = "barbarianCorner2Y", name = "Barbarian hoek 2 Y", description = "Longhall hoek-coord 2 Y", section = rotationSection, position = 1)
    default int barbarianCorner2Y() { return 3445; }

    @ConfigItem(keyName = "barbarianCorner3X", name = "Barbarian hoek 3 X", description = "Longhall hoek-coord 3 X", section = rotationSection, position = 1)
    default int barbarianCorner3X() { return 3075; }

    @ConfigItem(keyName = "barbarianCorner3Y", name = "Barbarian hoek 3 Y", description = "Longhall hoek-coord 3 Y", section = rotationSection, position = 1)
    default int barbarianCorner3Y() { return 3436; }

    @ConfigItem(keyName = "barbarianCorner4X", name = "Barbarian hoek 4 X", description = "Longhall hoek-coord 4 X", section = rotationSection, position = 1)
    default int barbarianCorner4X() { return 3082; }

    @ConfigItem(keyName = "barbarianCorner4Y", name = "Barbarian hoek 4 Y", description = "Longhall hoek-coord 4 Y", section = rotationSection, position = 1)
    default int barbarianCorner4Y() { return 3436; }

    @ConfigItem(keyName = "barbarianLootBones", name = "Barbarian loot bones", description = "Pak bones op in de longhall", section = rotationSection, position = 1)
    default boolean barbarianLootBones() { return true; }

    @ConfigItem(keyName = "barbarianLootCoins", name = "Barbarian loot coins", description = "Pak coins op in de longhall", section = rotationSection, position = 1)
    default boolean barbarianLootCoins() { return true; }

    @ConfigItem(keyName = "barbarianShowOverlay", name = "Toon Barbarian overlay", description = "Toon longhall-gebied overlay in-game", section = rotationSection, position = 1)
    default boolean barbarianShowOverlay() { return true; }

    @ConfigItem(keyName = "rotationMinMinutes", name = "Min. minuten per skill", description = "Minimaal aantal minuten voordat er gewisseld wordt", section = rotationSection, position = 2)
    default int rotationMinMinutes() { return 15; }

    @ConfigItem(keyName = "rotationMaxMinutes", name = "Max. minuten per skill", description = "Maximaal aantal minuten voordat er gewisseld wordt", section = rotationSection, position = 3)
    default int rotationMaxMinutes() { return 45; }

    @ConfigItem(keyName = "barbLootEnabled", name = "🐟 Barb fishing loot (skill)", description = "Loot trout/salmon bij Barbarian fishing spot; bank Edgeville (ook bijl/tinderbox/logs). Bij geen vis: wacht 5–8 min of F2P hop — geen woodcutting in deze modus.", section = rotationSection, position = 4)
    default boolean barbLootEnabled() { return false; }

    @ConfigItem(keyName = "barbLootBonfireWait", name = "Bonfire tijdens loot-wacht (niet gebruikt)", description = "Voorheen bonfire tijdens wacht; Barb loot doet geen WC/bonfire meer. Optie blijft voor compatibiliteit.", section = rotationSection, position = 5)
    default boolean barbLootBonfireWait() { return true; }

    @ConfigItem(
            keyName = "barbLootBankFishCount",
            name = "Barb loot: bank bij X fish",
            description = "Bank zodra je minstens X trout/salmon (raw of cooked) in inventory hebt. 0 = alleen banken bij volle inventory/andere triggers.",
            section = rotationSection,
            position = 6
    )
    default int barbLootBankFishCount() { return 0; }

    @ConfigItem(keyName = "barbLootGeAfterBanks", name = "Barb loot: GE na X bank trips", description = "Na zoveel keer vis naar Edgeville bank storten: Varrock GE — Trout/Salmon verkopen (% onder markt), daarna inkopen (reserve, mind runes, air staff, steel/black axe +50% guide, fly rod +20%). Te weinig gp voor nog ontbrekend item uit die set → Barb loot uit, volgende skill. 0 = uit.", section = rotationSection, position = 7)
    default int barbLootGeAfterBanks() { return 5; }

    @ConfigItem(keyName = "barbLootGePercentBelow", name = "Barb GE: % onder marktprijs", description = "Verkoopprijs t.o.v. wiki/GE-guide (bijv. 10 = 10% onder)", section = rotationSection, position = 8)
    default int barbLootGePercentBelow() { return 10; }

    @ConfigItem(keyName = "barbLootGeMinCash", name = "Barb GE: min. cash na verkopen", description = "Minimaal dit aantal coins in inventory vóór mind/staff-inkoop (reserve)", section = rotationSection, position = 9)
    default int barbLootGeMinCash() { return 5000; }

    @ConfigItem(keyName = "barbLootGeMindRunes", name = "Barb GE: mind runes (doel totaal)", description = "Streef-totaal Mind rune in inventory (wat budget na reserve toelaat)", section = rotationSection, position = 10)
    default int barbLootGeMindRunes() { return 1000; }

    @ConfigItem(
            keyName = "vampireSlayerQuestMode",
            name = "Vampyre Slayer (quest-modus)",
            description = "Zelfde als de vink op tab Centers: quest heeft prioriteit in de loop zolang hij niet klaar is. Ook: kies Start skill = Vampyre Slayer (quest) om direct te beginnen. Voortgang per RSN (JSON/config); vink gaat uit bij voltooiing.",
            section = rotationSection,
            position = 16
    )
    default boolean vampireSlayerQuestMode() { return false; }

    @ConfigItem(keyName = "barbLootGeBuyAirStaff", name = "Barb GE: Staff of air kopen", description = "Als je geen Staff of air hebt: 1x kopen zolang budget na reserve het toelaat", section = rotationSection, position = 11)
    default boolean barbLootGeBuyAirStaff() { return true; }

    @ConfigItem(
            keyName = "switchNow",
            name = "⚡ Switch Now",
            description = "Vink aan om direct naar de volgende skill te wisselen",
            section = rotationSection,
            position = 11
    )
    default boolean switchNow() { return false; }

    // ===================== COMBAT / GIANTS GE FOOD =====================

    /** Alleen F2P-food voor GE-koop (geen member fish / pineapple pizza). */
    enum CombatGeFoodType {
        ANY,
        ANCHOVY_PIZZA("Anchovy pizza"),
        SWORDFISH("Swordfish"),
        LOBSTER("Lobster"),
        TUNA("Tuna"),
        SALMON("Salmon"),
        TROUT("Trout"),
        MEAT_PIZZA("Meat pizza"),
        PLAIN_PIZZA("Plain pizza");

        private final String geItemName;

        CombatGeFoodType() {
            this.geItemName = null;
        }

        CombatGeFoodType(String geItemName) {
            this.geItemName = geItemName;
        }

        /** OSRS itemnaam voor GE, of {@code null} bij {@link #ANY} (beste uit bank / prioriteitenlijst). */
        public String toItemName() {
            return geItemName;
        }

        @Override
        public String toString() {
            if (this == ANY) {
                return "Any (F2P — beste in bank)";
            }
            return geItemName != null ? geItemName : name();
        }
    }

    @ConfigItem(
            keyName = "combatGeFoodEnabled",
            name = "GE food (Combat/Giants)",
            description = "Koop F2P food op de GE als de bank leeg is (Combat/Giants)",
            section = bankSection,
            position = 5
    )
    default boolean combatGeFoodEnabled() { return true; }

    @ConfigItem(
            keyName = "combatGeFoodType",
            name = "GE food type (F2P)",
            description = "Welk F2P food op de GE kopen als de bank leeg is. Any = voorkeur o.a. Anchovy pizza (18 HP) dan swordfish enz.; zie bank-prioriteit (geen member items).",
            section = bankSection,
            position = 6
    )
    default CombatGeFoodType combatGeFoodType() { return CombatGeFoodType.ANY; }

    @ConfigItem(
            keyName = "combatGeFoodBasePrice",
            name = "GE food basisprijs (gp)",
            description = "Basisprijs per stuk; bot probeert eerst deze prijs en daarna +20%",
            section = bankSection,
            position = 7
    )
    default int combatGeFoodBasePrice() { return 150; }

    @ConfigItem(
            keyName = "combatGeRangedAmmoEnabled",
            name = "GE ranged ammo (Combat)",
            description = "Als bank geen ammo heeft voor RANGED: koop op GE (type = per account, Accounts → Bewerken).",
            section = bankSection,
            position = 8
    )
    default boolean combatGeRangedAmmoEnabled() { return true; }

    @ConfigItem(
            keyName = "combatGeRangedAmmoItem",
            name = "GE ranged ammo itemnaam (legacy)",
            description = "Niet meer gebruikt — zie Ranged pijl-type per account (Accounts → Bewerken).",
            section = bankSection,
            position = 9,
            hidden = true
    )
    default String combatGeRangedAmmoItem() { return "Bronze arrow"; }

    @ConfigItem(
            keyName = "combatGeRangedAmmoBasePrice",
            name = "GE ranged ammo basisprijs (gp/stuk)",
            description = "Startbod per pijl/bolt; daarna +20% zoals food.",
            section = bankSection,
            position = 11
    )
    default int combatGeRangedAmmoBasePrice() { return 8; }

    // ===================== ACCOUNT SWITCHER =====================

    @ConfigItem(keyName = "accountSwitchEnabled", name = "Account rotatie inschakelen", description = "Zelfde schakelaar staat op de Accounts-tab. Wisselt automatisch tussen accounts na min/max minuten.", section = accountSection, position = 0)
    default boolean accountSwitchEnabled() { return false; }

    @ConfigItem(keyName = "accountList", name = "Account lijst (geavanceerd)", description = "Optioneel: jagex:pad|… — meestal leeg; accounts komen uit de Accounts-tab + geplakte blob.", section = accountSection, position = 1)
    default String accountList() { return ""; }

    @ConfigItem(
            keyName = "loginScreenCredentialsPath",
            name = "Profiel credentials.properties (dubbelklik login)",
            description = "Zelfde veld als op de Accounts-tab. Pad naar credentials.properties; dubbelklik op een account schrijft JX_* hierheen. Leeg = eerste jagex:-regel in Account lijst (indien gezet).",
            section = accountSection,
            position = 2
    )
    default String loginScreenCredentialsPath() { return ""; }

    @ConfigItem(keyName = "accountMinMinutes", name = "Min. minuten per account", description = "Minimaal aantal minuten op een account voordat er gewisseld wordt", section = accountSection, position = 3)
    default int accountMinMinutes() { return 30; }

    @ConfigItem(keyName = "accountMaxMinutes", name = "Max. minuten per account", description = "Maximaal aantal minuten op een account voordat er gewisseld wordt", section = accountSection, position = 4)
    default int accountMaxMinutes() { return 90; }

    @ConfigItem(
            keyName = "resetAccountTimersOnStop",
            name = "Reset per-account timers bij stop",
            description = "Als aan: bij Bot uit/stop worden account skill-timers gewist zodat elk account fris start",
            section = accountSection,
            position = 5
    )
    default boolean resetAccountTimersOnStop() { return true; }

    // ===================== RE-LOGOUT (ZELFDE ACCOUNT) =====================

    @ConfigItem(keyName = "reLogoutEnabled", name = "Re-log inschakelen", description = "Log na een willekeurige tijd uit en weer in met hetzelfde account; bot gaat daarna verder waar hij was.", section = reLogoutSection, position = 0)
    default boolean reLogoutEnabled() { return false; }

    @ConfigItem(keyName = "reLogoutMinMinutes", name = "Min. minuten tot re-log", description = "Minimaal aantal minuten voordat er wordt uit- en ingelogd", section = reLogoutSection, position = 1)
    default int reLogoutMinMinutes() { return 20; }

    @ConfigItem(keyName = "reLogoutMaxMinutes", name = "Max. minuten tot re-log", description = "Maximaal aantal minuten voordat er wordt uit- en ingelogd", section = reLogoutSection, position = 2)
    default int reLogoutMaxMinutes() { return 45; }

    @ConfigItem(keyName = "reLogoutPauseMinMinutes", name = "Min. pauze (min)", description = "Minimale pauze op het login scherm voordat opnieuw wordt ingelogd (0 = geen pauze)", section = reLogoutSection, position = 3)
    default int reLogoutPauseMinMinutes() { return 2; }

    @ConfigItem(keyName = "reLogoutPauseMaxMinutes", name = "Max. pauze (min)", description = "Maximale pauze op het login scherm (0 = geen pauze)", section = reLogoutSection, position = 4)
    default int reLogoutPauseMaxMinutes() { return 5; }

    @ConfigItem(keyName = "reLogoutAccount", name = "Account (re-log)", description = "Email:wachtwoord, jagex:pad, of pasted:DisplayName voor geplakte Jagex accounts", section = reLogoutSection, position = 5)
    default String reLogoutAccount() { return ""; }

    @ConfigItem(keyName = "loginNow", name = "🔐 Log in", description = "Vink aan om nu in te loggen (op het login-scherm; gebruikt Account re-log of geplakte Jagex)", section = reLogoutSection, position = 6)
    default boolean loginNow() { return false; }

    // ===================== JAGEX PLAKKEN =====================

    @ConfigItem(keyName = "pastedCredentials", name = "Geplakte credentials", description = "Plak hier Jagex credentials (JX_DISPLAY_NAME=, JX_ACCESS_TOKEN=, etc.). Scheid meerdere accounts met ---", section = jagexPasteSection, position = 0)
    default String pastedCredentials() { return ""; }

    @ConfigItem(keyName = "enabledDisplayNames", name = "Aangevinkte display namen", description = "Komma-gescheiden lijst van display namen die actief zijn (intern)", section = jagexPasteSection, position = 1)
    default String enabledDisplayNames() { return ""; }

    @ConfigItem(keyName = "managedJagexAccountsBlob", name = "Accountbeheer (intern)", description = "Opgeslagen accounts van de 👤 Accounts-tab — niet handmatig wijzigen", section = jagexPasteSection, position = 2)
    default String managedJagexAccountsBlob() { return ""; }

    @ConfigItem(keyName = "accountStatSnapshotsBlob", name = "Account stats snapshot (intern)", description = "Laatst bijgehouden levels/GP per account (RSN)", section = jagexPasteSection, position = 3)
    default String accountStatSnapshotsBlob() { return ""; }

    @ConfigItem(keyName = "accountLocalProgressBlob", name = "Account skill-voortgang (intern)", description = "Per RSN: actieve skill + rotatie-timer (voor wisselen na logout)", section = jagexPasteSection, position = 4)
    default String accountLocalProgressBlob() { return ""; }

    @ConfigItem(keyName = "accountQuestProgressBlob", name = "Quest voortgang (intern)", description = "Per RSN: Vampyre Slayer stap + vlag hamer van imp", section = jagexPasteSection, position = 5)
    default String accountQuestProgressBlob() { return ""; }

    @ConfigItem(keyName = "barbLootMagicSkipBlob", name = "Barb loot Magic-skip (intern)", description = "Per RSN: Barb loot voor Magic-training niet meer nodig (Magic ≥ 5)", section = jagexPasteSection, position = 6)
    default String barbLootMagicSkipBlob() { return ""; }

    @ConfigItem(keyName = "stuckLearningBlob", name = "Stuck-learning (intern)", description = "Per account/context: teller van vastlopers voor adaptieve recovery", section = jagexPasteSection, position = 7)
    default String stuckLearningBlob() { return ""; }

    @ConfigItem(keyName = "impsScorpionAttackMemoryBlob", name = "Imps scorpion attack memory (intern)", description = "Per account: tiles waar scorpion aggro is gezien (voor betere avoid)", section = jagexPasteSection, position = 8)
    default String impsScorpionAttackMemoryBlob() { return ""; }

    @ConfigItem(keyName = "starterStatusLearningBlob", name = "Starter status learning (intern)", description = "Per account/status: teller van Starter-vastlopers voor adaptieve recovery", section = jagexPasteSection, position = 9)
    default String starterStatusLearningBlob() { return ""; }

    @ConfigItem(keyName = "tutCompletedAccountsBlob", name = "Tut complete per account (intern)", description = "Per RSN: Tutorial Island eenmalig voltooid", section = jagexPasteSection, position = 9)
    default String tutCompletedAccountsBlob() { return ""; }

    @ConfigItem(keyName = "accountBehaviorProfileBlob", name = "Gedragsprofiel per RSN (intern)", description = "Per account: seed|delay%|anti-ban gewichten|struggle|fatigue|failureNudge (0–10, +5%/stap op delay; decay bij bank sluiten; +1 bij stuck-recovery); niet handmatig knoeien", section = jagexPasteSection, position = 10)
    default String accountBehaviorProfileBlob() { return ""; }

    @ConfigItem(keyName = "accountsTableHideSuspectBanned", name = "Accounts-tabel: verberg ban-vermoeden", description = "Als aan: verberg rijen op de 👤 Accounts-tab waarvan hiscore-stats als mogelijk geband markeren.", section = jagexPasteSection, position = 11)
    default boolean accountsTableHideSuspectBanned() { return false; }

    // ===================== MOVEMENT (TRAVEL) =====================

    @ConfigItem(keyName = "travelReclickIntervalMs", name = "Reclick interval (ms)", description = "Min. tijd tussen walk-clicks tijdens lang lopen (lager = vaker een nieuw pad, vloeiender)", section = movementSection, position = 0)
    default int travelReclickIntervalMs() { return 700; }

    @ConfigItem(keyName = "travelPostClickDelayMin", name = "Delay na click min (ms)", description = "Korte tick-wacht na een travel-click (min)", section = movementSection, position = 1)
    default int travelPostClickDelayMin() { return 400; }

    @ConfigItem(keyName = "travelPostClickDelayMax", name = "Delay na click max (ms)", description = "Korte tick-wacht na een travel-click (max)", section = movementSection, position = 2)
    default int travelPostClickDelayMax() { return 900; }

    @ConfigItem(keyName = "travelUsePathfinderWalk", name = "Pathfinder walk (Storm)", description = "Historisch vlagveld; primaire navigatie loopt via MovementHelper. Kan later weer aan reis-fallbacks worden gekoppeld.", section = movementSection, position = 3)
    default boolean travelUsePathfinderWalk() { return true; }

    // ===================== ANTI-BAN =====================

    enum AntiBanIntensity {
        LOW,
        NORMAL,
        HIGH
    }

    @ConfigItem(keyName = "antiBanEnabled", name = "Anti-ban inschakelen", description = "Schakel het anti-ban systeem in", section = antiBanSection, position = 0)
    default boolean antiBanEnabled() { return true; }

    @ConfigItem(keyName = "antiBanFrequency", name = "Anti-ban frequentie", description = "Gemiddeld aantal seconden tussen anti-ban acties", section = antiBanSection, position = 1)
    default int antiBanFrequency() { return 45; }

    @ConfigItem(
            keyName = "antiBanIntensity",
            name = "Anti-ban heftigheid",
            description = "LOW = rustiger/minder vaak, NORMAL = standaard, HIGH = actiever/vaker.",
            section = antiBanSection,
            position = 1_1
    )
    default AntiBanIntensity antiBanIntensity() { return AntiBanIntensity.NORMAL; }

    @ConfigItem(keyName = "cameraMovement", name = "Camera bewegingen", description = "Willekeurige camera rotaties uitvoeren", section = antiBanSection, position = 2)
    default boolean cameraMovement() { return true; }

    // ===================== DISCORD SCREENSHOTS =====================

    @ConfigSection(
            name = "Discord",
            description = "Screenshots naar Discord webhook sturen",
            position = 10
    )
    String discordSection = "discordSection";

    @ConfigItem(
            keyName = "discordWebhookUrl",
            name = "Discord webhook URL",
            description = "Volledige Discord webhook URL voor screenshots",
            section = discordSection,
            position = 0
    )
    default String discordWebhookUrl() { return ""; }

    @ConfigItem(
            keyName = "discordScreenshotsEnabled",
            name = "Screenshots inschakelen",
            description = "Verstuur periodiek screenshots naar Discord",
            section = discordSection,
            position = 1
    )
    default boolean discordScreenshotsEnabled() { return false; }

    @ConfigItem(
            keyName = "discordScreenshotIntervalSeconds",
            name = "Interval (sec)",
            description = "Hoe vaak een screenshot sturen (30–300 sec)",
            section = discordSection,
            position = 2
    )
    default int discordScreenshotIntervalSeconds() { return 120; }

    @ConfigItem(
            keyName = "discordDetailedWebhookText",
            name = "Uitgebreide webhook-tekst",
            description = "Aan: wereld, RSN, cash, HP/gebed/run, locatie, food, FPS, gewicht, rotatie, XP, anti-ban, … Uit: compact (client, mode, status, re-log, korte stats, runtime).",
            section = discordSection,
            position = 3
    )
    default boolean discordDetailedWebhookText() { return true; }

    @ConfigItem(
            keyName = "discordRelogPausePingsEnabled",
            name = "Re-log: Discord-updates",
            description = "Stuur elke 5 minuten een korte tekst naar de webhook tijdens re-log (pauze op login-scherm / inloggen), met hoe lang het nog duurt tot auto-login. Werkt ook zonder screenshots.",
            section = discordSection,
            position = 4
    )
    default boolean discordRelogPausePingsEnabled() { return true; }

    @ConfigItem(keyName = "cameraDurationMin", name = "Camera min duur (ms)", description = "Minimale duur van een camera beweging in milliseconden", section = antiBanSection, position = 3)
    default int cameraDurationMin() { return 600; }

    @ConfigItem(keyName = "cameraDurationMax", name = "Camera max duur (ms)", description = "Maximale duur van een camera beweging in milliseconden", section = antiBanSection, position = 4)
    default int cameraDurationMax() { return 2500; }

    @ConfigItem(keyName = "mmbDragSpeedMin", name = "MMB stap snelheid min (ms)", description = "Minimale vertraging per muisstap bij MMB drag (hoger = langzamer)", section = antiBanSection, position = 41)
    default int mmbDragSpeedMin() { return 25; }

    @ConfigItem(keyName = "mmbDragSpeedMax", name = "MMB stap snelheid max (ms)", description = "Maximale vertraging per muisstap bij MMB drag", section = antiBanSection, position = 42)
    default int mmbDragSpeedMax() { return 50; }

    @ConfigItem(keyName = "mmbDragDistanceMin", name = "MMB drag afstand min (px)", description = "Minimale horizontale drag afstand in pixels", section = antiBanSection, position = 43)
    default int mmbDragDistanceMin() { return 40; }

    @ConfigItem(keyName = "mmbDragDistanceMax", name = "MMB drag afstand max (px)", description = "Maximale horizontale drag afstand in pixels", section = antiBanSection, position = 44)
    default int mmbDragDistanceMax() { return 120; }

    // ===================== ARROW PICKUP =====================

    @ConfigItem(keyName = "pickupArrows", name = "Arrows/bolts oppakken", description = "Pak je arrows/bolts op van de grond als je niet in combat bent", section = combatSection, position = 11)
    default boolean pickupArrows() { return false; }

    @ConfigItem(keyName = "pickupArrowsMinKills", name = "Arrow pickup min kills", description = "Minimaal aantal kills voordat arrows worden opgepakt", section = combatSection, position = 12)
    default int pickupArrowsMinKills() { return 3; }

    @ConfigItem(keyName = "pickupArrowsMaxKills", name = "Arrow pickup max kills", description = "Maximaal aantal kills voordat arrows worden opgepakt", section = combatSection, position = 13)
    default int pickupArrowsMaxKills() { return 8; }

    /** Universal banking: ga banken als arrows onder dit aantal (0 = niet gebruiken). */
    @ConfigItem(keyName = "combatArrowMin", name = "Arrow min (bank onder)", description = "Minimum pijlen/bolts (inv+quiver) voordat je naar bank/GE gaat; nooit lager toegepast dan 100.", section = combatSection, position = 14)
    default int combatArrowMin() { return 100; }

    @ConfigItem(keyName = "combatArrowTarget", name = "Arrow target (ophalen)", description = "Haal tot dit aantal arrows uit de bank", section = combatSection, position = 15)
    default int combatArrowTarget() { return 500; }

    @ConfigItem(keyName = "combatRuneMin", name = "Rune min (bank onder)", description = "Ga banken als runes onder dit aantal (Universal Banking)", section = combatSection, position = 16)
    default int combatRuneMin() { return 50; }

    @ConfigItem(keyName = "combatRuneTarget", name = "Rune target (ophalen)", description = "Haal tot dit aantal runes uit de bank", section = combatSection, position = 17)
    default int combatRuneTarget() { return 500; }

    @ConfigItem(
            keyName = "genieLampSkill",
            name = "Genie lamp skill",
            description = "Welke skill kiezen bij Genie lamp interface (daarna Confirm).",
            section = combatSection,
            position = 18
    )
    default GenieLampSkill genieLampSkill() { return GenieLampSkill.NONE; }

    @ConfigItem(keyName = "idleChecks", name = "Idle pauzes", description = "Af en toe kort stoppen alsof je AFK bent", section = antiBanSection, position = 5)
    default boolean idleChecks() { return true; }

    @ConfigItem(keyName = "randomMouseMovement", name = "Muis bewegingen", description = "Willekeurige muisbewegingen maken", section = antiBanSection, position = 6)
    default boolean randomMouseMovement() { return true; }

    @ConfigItem(
            keyName = "mouseFidgetEnabled",
            name = "Continuous muis-fidget",
            description = "Achtergrond-thread die tussen bot-acties continu kleine muis-bewegingen doet. "
                    + "Mens-realisme: cursor staat nooit lang stil tijdens vechten/skillen. "
                    + "Pauseert automatisch tijdens echte clicks zodat hij niet interfereert. "
                    + "Pixel/duur ranges instelbaar via 'Fidget amp/duur' sliders hieronder.",
            section = antiBanSection,
            position = 7
    )
    default boolean mouseFidgetEnabled() { return true; }

    @ConfigItem(
            keyName = "mouseFidgetAmpMinPx",
            name = "Fidget amplitude min (px)",
            description = "Minimum afstand (in pixels) per fidget-beweging. Lager = subtieler. "
                    + "Standaard 20 px. Aanbevolen 1–200.",
            section = antiBanSection,
            position = 50
    )
    default int mouseFidgetAmpMinPx() { return 20; }

    @ConfigItem(
            keyName = "mouseFidgetAmpMaxPx",
            name = "Fidget amplitude max (px)",
            description = "Maximum afstand (in pixels) per fidget-beweging. "
                    + "Standaard 80 px. Aanbevolen 1–200.",
            section = antiBanSection,
            position = 51
    )
    default int mouseFidgetAmpMaxPx() { return 80; }

    @ConfigItem(
            keyName = "mouseFidgetDurMinMs",
            name = "Fidget duur min (ms)",
            description = "Minimum duur in ms van één smooth fidget-beweging. "
                    + "Lager = sneller (kan houteriger ogen). Standaard 180. Aanbevolen 50–2000.",
            section = antiBanSection,
            position = 52
    )
    default int mouseFidgetDurMinMs() { return 180; }

    @ConfigItem(
            keyName = "mouseFidgetDurMaxMs",
            name = "Fidget duur max (ms)",
            description = "Maximum duur in ms van één smooth fidget-beweging. "
                    + "Hoger = trager (loomer). Standaard 600. Aanbevolen 50–2000.",
            section = antiBanSection,
            position = 53
    )
    default int mouseFidgetDurMaxMs() { return 600; }

    @ConfigItem(
            keyName = "mouseMicroMoveAmpMinPx",
            name = "Micro-move amplitude min (px)",
            description = "Minimum afstand (px) van een micro-muisbeweging tussen bot-acties (toggle: 'Muis bewegingen'). "
                    + "Standaard 30. Aanbevolen 1–300.",
            section = antiBanSection,
            position = 54
    )
    default int mouseMicroMoveAmpMinPx() { return 30; }

    @ConfigItem(
            keyName = "mouseMicroMoveAmpMaxPx",
            name = "Micro-move amplitude max (px)",
            description = "Maximum afstand (px) van een micro-muisbeweging. Standaard 110. "
                    + "Aanbevolen 1–300. (HumanProfile kan de bovenkant nog oprekken o.b.v. jouw eigen p95.)",
            section = antiBanSection,
            position = 55
    )
    default int mouseMicroMoveAmpMaxPx() { return 110; }

    @ConfigItem(
            keyName = "mouseMicroMoveDurMinMs",
            name = "Micro-move duur min (ms)",
            description = "Minimum duur (ms) van één smooth micro-muisbeweging. Standaard 200. Aanbevolen 50–2000.",
            section = antiBanSection,
            position = 56
    )
    default int mouseMicroMoveDurMinMs() { return 200; }

    @ConfigItem(
            keyName = "mouseMicroMoveDurMaxMs",
            name = "Micro-move duur max (ms)",
            description = "Maximum duur (ms) van één smooth micro-muisbeweging. Standaard 600. Aanbevolen 50–2000.",
            section = antiBanSection,
            position = 57
    )
    default int mouseMicroMoveDurMaxMs() { return 600; }

    @ConfigItem(keyName = "misClickEnabled", name = "Misclicks inschakelen", description = "Simuleer af en toe een rechtermuisklik-misclick", section = antiBanSection, position = 8)
    default boolean misClickEnabled() { return true; }

    @ConfigItem(keyName = "misClickPercent", name = "Misclick kans %", description = "Percentage kans dat een misclick plaatsvindt (1-100)", section = antiBanSection, position = 9)
    default int misClickPercent() { return 8; }

    @ConfigItem(keyName = "tabGlanceEnabled", name = "Tab-wissel (inventory)", description = "Anti-ban: willekeurige F-toets (F1,F2,F4–F8), 2–4 s wachten, dan ESC voor inventory. Op Tutorial Island: Storm Tabs.open (tab-widgets), geen F-toetsen. Frequentie volgt anti-ban seconden (random ½–1½×).", section = antiBanSection, position = 10)
    default boolean tabGlanceEnabled() { return true; }

    @ConfigItem(
            keyName = "openInventoryViaMouseClick",
            name = "Use mouse to inv",
            description = "Vóór elke inventory-actie (eten, drinken, wield, drop, bury, use, …): controleer of de Inventory-tab open is; zo niet, klik op tab-widget iface 161,62. Uit = geen extra tab-open (huidig gedrag). Anti-ban tab-glance: aan = inv-klik i.p.v. ESC.",
            section = antiBanSection,
            position = 100
    )
    default boolean openInventoryViaMouseClick() { return false; }

    @ConfigItem(keyName = "accountBehaviorProfileEnabled", name = "Per-RSN gedragsprofiel", description = "Elk account: eigen anti-ban-timing/gewichten + licht verschoven bank-nadertegels (zelfde seed als profiel; Lumbridge-traproute ongemoeid). Intern opgeslagen — geen ML.", section = antiBanSection, position = 11)
    default boolean accountBehaviorProfileEnabled() { return true; }

    @ConfigItem(
            keyName = "playerLookupAntibanEnabled",
            name = "Speler lookup (rechtsklik)",
            description = "Anti-ban: af en toe op een random nearby speler rechtsklikken en Lookup kiezen (met geheugen, geen directe herhaling).",
            section = antiBanSection,
            position = 11
    )
    default boolean playerLookupAntibanEnabled() { return false; }

    @ConfigItem(
            keyName = "skillHoverEnabled",
            name = "Skill hover (anti-ban)",
            description = "Opent af en toe het Skills-paneel en hovert soepel ~1–2 sec op het icoon van de actieve skill (Attack/Strength/WC/etc.). Géén klik. Daarna terug naar Inventory. Voelt menselijk: 'ik kijk even hoe ver ik ben'.",
            section = antiBanSection,
            position = 12
    )
    default boolean skillHoverEnabled() { return true; }

    // ===================== GIANTS MODE =====================

    @ConfigItem(keyName = "giantsMode", name = "🗡 Giants Mode", description = "Standaard: Giants als skill in de rotatie (Edgeville Dungeon). Per account: Accounts → Bewerken → Giants rotatie (Globaal / aan / uit).", section = giantsSection, position = -1)
    default boolean giantsMode() { return false; }

    @ConfigItem(keyName = "giantsMonsterName", name = "Monster naam", description = "Naam van het monster om te killen (standaard: Hill Giant)", section = giantsSection, position = 0)
    default String giantsMonsterName() { return "Hill Giant"; }

    @ConfigItem(keyName = "giantsCombatStyle", name = "Combat style", description = "MELEE, RANGED of MAGE. Bot haalt bij gear prep wapen/runes uit bank (zoals Imps).", section = giantsSection, position = 1)
    default ImpsCombatStyle giantsCombatStyle() { return ImpsCombatStyle.MELEE; }

    @ConfigItem(
            keyName = "giantsMeleeTrainingStyle",
            name = "Melee attack style (Giants)",
            description = "Alleen als Giants combat style = MELEE: kies welke skill je traint.",
            section = giantsSection,
            position = 1_1
    )
    default MeleeTrainingStyle giantsMeleeTrainingStyle() { return MeleeTrainingStyle.BALANCED; }

    @ConfigItem(keyName = "giantsMageSpell", name = "Mage spell", description = "Welke spell bij MAGE (bepaalt runes + staff). Alleen van toepassing als Combat style = MAGE.", section = giantsSection, position = 2)
    default ImpsMageSpell giantsMageSpell() { return ImpsMageSpell.WIND_STRIKE; }

    @ConfigItem(keyName = "giantsBrassKeyPriceMin", name = "Brass key prijs min (gp)", description = "Minimale GE-koopprijs (random tussen min en max)", section = giantsSection, position = 3)
    default int giantsBrassKeyPriceMin() { return 900; }

    @ConfigItem(keyName = "giantsBrassKeyPriceMax", name = "Brass key prijs max (gp)", description = "Maximale GE-koopprijs (random tussen min en max)", section = giantsSection, position = 4)
    default int giantsBrassKeyPriceMax() { return 1000; }

    @ConfigItem(keyName = "giantsLootItems", name = "Loot items", description = "Items om te looten (komma-gescheiden). Leeg = standaard: Big bones, Limpwurt root, etc.", section = giantsSection, position = 5)
    default String giantsLootItems() { return "Big bones,Limpwurt root"; }

    @ConfigItem(keyName = "giantsBankWhenLoot", name = "Bank bij X loot items", description = "Ga banken wanneer je dit aantal loot items hebt (zoals Imps bank threshold)", section = giantsSection, position = 6)
    default int giantsBankWhenLoot() { return 28; }

    @ConfigItem(keyName = "showGiantsOverlay", name = "Toon Giants overlay", description = "Toon het Giants-gebied als overlay in-game", section = giantsSection, position = 7)
    default boolean showGiantsOverlay() { return true; }

    @ConfigItem(keyName = "giantsPreferVarrock", name = "Voorkeur Varrock ingang", description = "Gebruik de Varrock shed ingang (met brass key) als voorkeur. Uit = altijd Edgeville trapdoor.", section = giantsSection, position = 8)
    default boolean giantsPreferVarrock() { return true; }

    @ConfigItem(
            keyName = "giantsBankForFood",
            name = "Giants: bank voor food",
            description = "Onafhankelijke Giants-toggle. Zodra food in inventory onder de drempel komt → terug naar de bank, ongeacht HP. Werkt los van de globale 'Bank als food op' switch.",
            section = giantsSection,
            position = 9
    )
    default boolean giantsBankForFood() { return true; }

    @ConfigItem(
            keyName = "giantsLowFoodBankThreshold",
            name = "Giants: food drempel",
            description = "Onder dit aantal food in inventory loopt de bot terug naar de bank (ongeacht HP). 0 = uit (alleen bij 0 food). Voorkomt paniek-eat-loops in de dungeon.",
            section = giantsSection,
            position = 10
    )
    default int giantsLowFoodBankThreshold() { return 2; }

    // ===================== WEB GUI =====================

    @ConfigSection(name = "Web GUI", description = "Instellingen voor de web-interface", position = 12)
    String webGuiSection = "webGuiSection";

    @ConfigItem(keyName = "webGuiUrl", name = "Web GUI URL", description = "URL naar de web-based GUI (extern). Knop 🌐 Web opent deze URL; /config.json blijft op localhost voor sync.", section = webGuiSection, position = 0)
    default String webGuiUrl() { return "https://id-preview--189c9144-0c91-40c6-81e5-f3542a0829c4.lovable.app"; }

    // ===================== WIDGET INSPECTOR =====================

    @ConfigSection(name = "Widget inspector", description = "Zichtbare UI-widgets naar de console (stdout). Ook bereikbaar in-game: Combat Bot-paneel → tab 🔍 Debug.", position = 101)
    String widgetInspectorSection = "widgetInspectorSection";

    @ConfigItem(keyName = "widgetInspectorIntervalSeconds", name = "Dump elke N sec (0=uit)", description = "Als je ingelogd bent: elke N seconden een widget-lijst naar het Combat Bot Debug-tabblad (bron WIDGET), logbestand en stdout.", section = widgetInspectorSection, position = 0)
    default int widgetInspectorIntervalSeconds() { return 0; }

    @ConfigItem(keyName = "widgetInspectorFilter", name = "Filter (substring)", description = "Alleen regels die dit bevatten (case-insensitief). Leeg = alle widgets met tekst, naam, menu-acties of item.", section = widgetInspectorSection, position = 1)
    default String widgetInspectorFilter() { return ""; }

    @ConfigItem(keyName = "widgetInspectorMaxLines", name = "Max regels per dump", description = "Limiet om stdout niet te vullen.", section = widgetInspectorSection, position = 2)
    default int widgetInspectorMaxLines() { return 400; }

    @ConfigItem(keyName = "widgetInspectorSkipAlreadyPrinted", name = "Sla reeds-geloste widgets over", description = "Zelfde widget (zelfde id/tekst/acties) niet opnieuw loggen tot je de dedupe-cache wist. Handig bij interval-dumps.", section = widgetInspectorSection, position = 3)
    default boolean widgetInspectorSkipAlreadyPrinted() { return true; }

    @ConfigItem(keyName = "widgetHoverInspectorEnabled", name = "Toon widget onder muis", description = "Groene tooltip bij de cursor: humanLabel, id, iface, naam/tekst/acties en bounds van het kleinste widget op die pixel (zelfde stijl als de dump).", section = widgetInspectorSection, position = 4)
    default boolean widgetHoverInspectorEnabled() { return false; }

    @ConfigItem(
            keyName = "gameplayMlClickLog",
            name = "Log menu-klikken (ML)",
            description = "Elke gekozen menu-actie loggen (canvas-positie, optie, target, widget-params). Ook naar combat-bot-ml-clicks-YYYY-MM-DD.jsonl voor pipelines.",
            section = widgetInspectorSection,
            position = 5)
    default boolean gameplayMlClickLog() { return false; }

    @ConfigItem(
            keyName = "gameplayMlClickLogOnlyAuthentic",
            name = "ML-log alleen handmatige klikken",
            description = "Uit = ook geautomatiseerde/plugin-klikken loggen (o.a. bot). Aan = alleen echte speler-muisklikken (aanbevolen voor jouw gameplay als trainingsdata).",
            section = widgetInspectorSection,
            position = 6)
    default boolean gameplayMlClickLogOnlyAuthentic() { return true; }

    @ConfigItem(
            keyName = "gameplayMouseTraceLog",
            name = "Record muis trace (move/click/drag)",
            description = "Log ruwe muis-events naar combat-bot-mouse-trace-YYYY-MM-DD.jsonl (x,y,timing,click,drag). Gebruik dit om jouw handmatige speelstijl te analyseren.",
            section = widgetInspectorSection,
            position = 7)
    default boolean gameplayMouseTraceLog() { return false; }

    @ConfigItem(
            keyName = "gameplayMouseTraceOnlyWhenBotOff",
            name = "Mouse trace alleen met bot UIT",
            description = "Aanbevolen voor pure handmatige data. Uit = ook events tijdens bot-run opnemen.",
            section = widgetInspectorSection,
            position = 8)
    default boolean gameplayMouseTraceOnlyWhenBotOff() { return true; }

    @ConfigItem(
            keyName = "gameplayMouseTraceMoveSampleMs",
            name = "Mouse trace move sample (ms)",
            description = "Minimale tijd tussen move/drag samples (lager = gedetailleerder, groter bestand).",
            section = widgetInspectorSection,
            position = 9)
    default int gameplayMouseTraceMoveSampleMs() { return 35; }

    // ===================== COMPATIBILITY =====================
    default String foodName() {
        return foodChoice().toItemName();
    }
}
