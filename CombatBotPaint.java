package com.combatbot;

import java.awt.*;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.runelite.api.Skill;
import net.storm.sdk.game.Skills;

public class CombatBotPaint {

    private final Instant startTime = Instant.now();
    private String lastAntiBanAction = "Geen";

    // === Combat stats ===
    private final AtomicInteger killCount = new AtomicInteger(0);
    private final AtomicLong totalLootValue = new AtomicLong(0);
    private final AtomicInteger lootedItems = new AtomicInteger(0);

    // Per-NPC kill count en per-item loot count (alle skills samen)
    private final Map<String, AtomicInteger> killsByNpc = new LinkedHashMap<>();
    private final Map<String, AtomicInteger> lootByItem = new LinkedHashMap<>();

    // === Woodcutting stats ===
    private final AtomicInteger logsChopped = new AtomicInteger(0);
    private final AtomicInteger logsDropped = new AtomicInteger(0);
    private final AtomicInteger logsBanked = new AtomicInteger(0);

    // === Mining stats ===
    private final AtomicInteger oresMined = new AtomicInteger(0);
    private final AtomicInteger oresDropped = new AtomicInteger(0);
    private final AtomicInteger oresBanked = new AtomicInteger(0);

    // === Fishing stats ===
    private final AtomicInteger fishCaught = new AtomicInteger(0);
    private final AtomicInteger fishDropped = new AtomicInteger(0);
    private final AtomicInteger fishBanked = new AtomicInteger(0);

    // === XP Tracking ===
    private long startAttackXp = -1, startStrengthXp = -1, startDefenceXp = -1, startHitpointsXp = -1;
    private long startRangedXp = -1, startMagicXp = -1;
    private long startWcXp = -1, startMiningXp = -1, startFishingXp = -1, startFmXp = -1;
    private boolean xpInitialized = false;

    // === Skill rotation info ===
    private String activeSkill = "Combat";
    private long secondsUntilSwitch = 0;
    private long totalSwitchSeconds = 1;
    private boolean rotationEnabled = false;

    // === Current status per skill ===
    private String currentStatus = "Opstarten...";

    /** Starter-modus: overlay (geen aparte tile-overlay; zelfde panel als combat). */
    private String starterPhaseName = "";
    private boolean starterInNpcCombat = false;
    /** Gerolde doelen (Att/Str/Def gelijk; WC; FM). -1 = nog niet gezet. */
    private int starterMeleeGoal = -1;
    private int starterWcGoal = -1;
    private int starterFmGoal = -1;

    /** Barb loot: voltooide bank trips naar volgende GE-run / totaal (0 = niet tonen). */
    private int barbLootGeBankTripsDone = 0;
    private int barbLootGeBankTripsTotal = 0;
    /** Imps: voltooide bank trips naar volgende GE-run / totaal (0 = niet tonen). */
    private int impsGeBankTripsDone = 0;
    private int impsGeBankTripsTotal = 0;

    // === Account switcher info ===
    private String currentAccount = "Geen";
    private long accountSecondsLeft = 0;
    private int totalAccounts = 0;
    private int currentAccountNum = 0;
    private boolean accountSwitchEnabled = false;
    private final AtomicInteger accountSwitches = new AtomicInteger(0);

    // === Re-log (zelfde account) info ===
    private boolean relogEnabled = false;
    private long relogSecondsUntilLogout = 0;
    private long relogPauseSecondsRemaining = 0;
    private boolean relogInPause = false;

    // --- XP initialization ---
    public void initializeXp() {
        if (xpInitialized) return;
        try {
            startAttackXp = Skills.getExperience(Skill.ATTACK);
            startStrengthXp = Skills.getExperience(Skill.STRENGTH);
            startDefenceXp = Skills.getExperience(Skill.DEFENCE);
            startHitpointsXp = Skills.getExperience(Skill.HITPOINTS);
            startRangedXp = Skills.getExperience(Skill.RANGED);
            startMagicXp = Skills.getExperience(Skill.MAGIC);
            startWcXp = Skills.getExperience(Skill.WOODCUTTING);
            startMiningXp = Skills.getExperience(Skill.MINING);
            startFishingXp = Skills.getExperience(Skill.FISHING);
            startFmXp = Skills.getExperience(Skill.FIREMAKING);
            xpInitialized = true;
        } catch (Exception e) {
            // Game niet volledig geladen
        }
    }

    // --- XP getters ---
    public long getCombatXpGained() {
        if (!xpInitialized) return 0;
        try {
            return (Skills.getExperience(Skill.ATTACK) - startAttackXp)
                    + (Skills.getExperience(Skill.STRENGTH) - startStrengthXp)
                    + (Skills.getExperience(Skill.DEFENCE) - startDefenceXp)
                    + (Skills.getExperience(Skill.HITPOINTS) - startHitpointsXp)
                    + (Skills.getExperience(Skill.RANGED) - startRangedXp)
                    + (Skills.getExperience(Skill.MAGIC) - startMagicXp);
        } catch (Exception e) { return 0; }
    }

    public long getWcXpGained() {
        if (!xpInitialized) return 0;
        try { return Skills.getExperience(Skill.WOODCUTTING) - startWcXp; }
        catch (Exception e) { return 0; }
    }

    public long getMiningXpGained() {
        if (!xpInitialized) return 0;
        try { return Skills.getExperience(Skill.MINING) - startMiningXp; }
        catch (Exception e) { return 0; }
    }

    public long getFishingXpGained() {
        if (!xpInitialized) return 0;
        try { return Skills.getExperience(Skill.FISHING) - startFishingXp; }
        catch (Exception e) { return 0; }
    }

    public long getFmXpGained() {
        if (!xpInitialized) return 0;
        try { return Skills.getExperience(Skill.FIREMAKING) - startFmXp; }
        catch (Exception e) { return 0; }
    }

    // XP per hour
    public long getCombatXpPerHour() { return perHour(getCombatXpGained()); }
    public long getWcXpPerHour() { return perHour(getWcXpGained()); }
    public long getMiningXpPerHour() { return perHour(getMiningXpGained()); }
    public long getFishingXpPerHour() { return perHour(getFishingXpGained()); }
    public long getFmXpPerHour() { return perHour(getFmXpGained()); }

    // --- Combat ---
    public void addKill() { killCount.incrementAndGet(); }

    /** Kill met NPC-naam, voor per-NPC statistieken. */
    public void addKill(String npcName) {
        killCount.incrementAndGet();
        if (npcName == null || npcName.isEmpty()) return;
        killsByNpc.computeIfAbsent(npcName, n -> new AtomicInteger(0)).incrementAndGet();
    }

    public void addLoot(int haValue) { totalLootValue.addAndGet(haValue); lootedItems.incrementAndGet(); }

    /** Loot met item-naam en hoeveelheid, voor per-item statistieken. */
    public void addLoot(String itemName, int quantity, int haValue) {
        totalLootValue.addAndGet(haValue);
        lootedItems.addAndGet(quantity <= 0 ? 1 : quantity);
        if (itemName == null || itemName.isEmpty()) return;
        lootByItem.computeIfAbsent(itemName, n -> new AtomicInteger(0))
                .addAndGet(quantity <= 0 ? 1 : quantity);
    }

    // --- Woodcutting ---
    public void addLogChopped() { logsChopped.incrementAndGet(); }
    public void addLogDropped() { logsDropped.incrementAndGet(); }
    public void addLogBanked(int amount) { logsBanked.addAndGet(amount); }

    // --- Mining ---
    public void addOreMined() { oresMined.incrementAndGet(); }
    public void addOreDropped(int amount) { oresDropped.addAndGet(amount); }
    public void addOreBanked(int amount) { oresBanked.addAndGet(amount); }

    // --- Fishing ---
    public void addFishCaught() { fishCaught.incrementAndGet(); }
    public void addFishDropped(int amount) { fishDropped.addAndGet(amount); }
    public void addFishBanked(int amount) { fishBanked.addAndGet(amount); }

    // --- Rotation ---
    public void setActiveSkill(String skill) { this.activeSkill = skill; }
    public void setSecondsUntilSwitch(long seconds) { this.secondsUntilSwitch = seconds; }
    public void setTotalSwitchSeconds(long total) { this.totalSwitchSeconds = Math.max(1, total); }
    public void setRotationEnabled(boolean enabled) { this.rotationEnabled = enabled; }
    public void setLastAntiBanAction(String action) { this.lastAntiBanAction = action; }
    public void setCurrentStatus(String status) { this.currentStatus = status; }

    /** Zichtbaar onder Actief: Starter — fase + of NPC-combat actief is (zelfde gate als account-wissel). */
    public void setStarterOverlayDebug(String phaseName, boolean inNpcCombat) {
        this.starterPhaseName = phaseName != null ? phaseName : "";
        this.starterInNpcCombat = inNpcCombat;
    }

    /** Toont melee/WC/FM-doelen op de Starter-regel in de overlay. */
    public void setStarterSkillGoals(int meleeGoal, int wcGoal, int fmGoal) {
        this.starterMeleeGoal = meleeGoal;
        this.starterWcGoal = wcGoal;
        this.starterFmGoal = fmGoal;
    }

    /**
     * Barb fishing loot: teller voor «GE na X bank trips».
     * @param completed reeds getelde trips met vis gestort (0..total)
     * @param total config-waarde; bij 0 verdwijnt de regel op de overlay
     */
    public void setBarbLootGeBankTripProgress(int completed, int total) {
        this.barbLootGeBankTripsDone = Math.max(0, completed);
        this.barbLootGeBankTripsTotal = Math.max(0, total);
    }

    public void setImpsGeBankTripProgress(int completed, int total) {
        this.impsGeBankTripsDone = Math.max(0, completed);
        this.impsGeBankTripsTotal = Math.max(0, total);
    }

    // --- Account ---
    public void setAccountInfo(String name, long secondsLeft, int total, int current) {
        this.currentAccount = name;
        this.accountSecondsLeft = Math.max(0, secondsLeft);
        this.totalAccounts = total;
        this.currentAccountNum = current;
        this.accountSwitchEnabled = total > 1;
    }

    /** Houd alleen de actieve accountnaam in sync met lokale spelernaam. */
    public void setCurrentAccountNameOnly(String name) {
        if (name == null || name.trim().isEmpty()) {
            return;
        }
        this.currentAccount = name.trim();
    }

    /** Weergave accountwissel: uren, minuten, seconden (compact). */
    public static String formatAccountSwitchHms(long totalSeconds) {
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        return String.format("%d:%02d:%02d", h, m, s);
    }
    public void incrementAccountSwitches() { accountSwitches.incrementAndGet(); }

    public void setRelogInfo(boolean enabled, long secondsUntilLogout, long pauseSecondsRemaining, boolean inPause) {
        this.relogEnabled = enabled;
        this.relogSecondsUntilLogout = Math.max(0, secondsUntilLogout);
        this.relogPauseSecondsRemaining = Math.max(0, pauseSecondsRemaining);
        this.relogInPause = inPause;
    }

    // --- Getters ---
    public Duration getRuntime() { return Duration.between(startTime, Instant.now()); }
    public int getKills() { return killCount.get(); }
    public long getTotalLootValue() { return totalLootValue.get(); }
    public int getLootedItems() { return lootedItems.get(); }
    public int getLogsChopped() { return logsChopped.get(); }
    public int getOresMined() { return oresMined.get(); }
    public int getFishCaught() { return fishCaught.get(); }
    public String getActiveSkillName() { return activeSkill; }
    public String getCurrentStatus() { return currentStatus; }
    public String getLastAntiBanAction() { return lastAntiBanAction; }
    public int getKillsPerHour() { return perHour(getKills()); }
    public boolean isRotationEnabled() { return rotationEnabled; }
    public long getSecondsUntilSwitch() { return secondsUntilSwitch; }
    public boolean isAccountSwitchEnabled() { return accountSwitchEnabled; }

    /**
     * Korte accountregel voor Discord; null als geen multi-account.
     */
    public String getDiscordAccountSummary() {
        if (!accountSwitchEnabled) {
            return null;
        }
        return currentAccount + " (" + currentAccountNum + "/" + totalAccounts + "), wissel over "
                + formatAccountSwitchHms(accountSecondsLeft);
    }

    /** XP-samenvatting passend bij de actieve modus (zelfde string als plugin getSkillName). */
    public String getDiscordXpLine(String modeDisplayName) {
        if (modeDisplayName == null) {
            return null;
        }
        initializeXp();
        if (modeDisplayName.startsWith("Starter")) {
            return String.format("Starter: Combat +%s · WC +%s · FM +%s",
                    formatXpShort(getCombatXpGained()),
                    formatXpShort(getWcXpGained()),
                    formatXpShort(getFmXpGained()));
        }
        if (modeDisplayName.startsWith("Combat") || modeDisplayName.startsWith("Imps") || modeDisplayName.startsWith("Giants")) {
            return String.format("Combat-XP: +%s (~%s/u)",
                    formatXpShort(getCombatXpGained()), formatXpShort(getCombatXpPerHour()));
        }
        if (modeDisplayName.startsWith("Woodcut")) {
            return String.format("WC-XP: +%s (~%s/u)",
                    formatXpShort(getWcXpGained()), formatXpShort(getWcXpPerHour()));
        }
        if (modeDisplayName.startsWith("Mining")) {
            return String.format("Mining-XP: +%s (~%s/u)",
                    formatXpShort(getMiningXpGained()), formatXpShort(getMiningXpPerHour()));
        }
        if (modeDisplayName.startsWith("Fishing")) {
            return String.format("Fish-XP: +%s (~%s/u)",
                    formatXpShort(getFishingXpGained()), formatXpShort(getFishingXpPerHour()));
        }
        if (modeDisplayName.startsWith("Loot")) {
            return String.format("Fish: %d · FM-XP: +%s (~%s/u)",
                    getFishCaught(), formatXpShort(getFmXpGained()), formatXpShort(getFmXpPerHour()));
        }
        return null;
    }

    public boolean isRelogEnabled() { return relogEnabled; }
    public long getRelogSecondsUntilLogout() { return relogSecondsUntilLogout; }
    public long getRelogPauseSecondsRemaining() { return relogPauseSecondsRemaining; }
    public boolean isRelogInPause() { return relogInPause; }
    public String getCurrentAccountName() { return currentAccount; }
    public int getCurrentAccountNumber() { return currentAccountNum; }
    public int getTotalAccounts() { return totalAccounts; }

    /** Seconden tot geplande account-wissel (Discord-samenvatting). */
    public long getAccountSecondsLeft() {
        return accountSecondsLeft;
    }

    private int perHour(int count) {
        long s = getRuntime().getSeconds();
        return s == 0 ? 0 : (int) (count * 3600.0 / s);
    }
    private long perHour(long count) {
        long s = getRuntime().getSeconds();
        return s == 0 ? 0 : (long) (count * 3600.0 / s);
    }

    public String formatRuntime() {
        Duration d = getRuntime();
        return String.format("%02d:%02d:%02d", d.toHours(), d.toMinutesPart(), d.toSecondsPart());
    }

    public String formatGp(long value) {
        if (value >= 1_000_000) return String.format("%.1fM", value / 1_000_000.0);
        if (value >= 1_000) return String.format("%.1fK", value / 1_000.0);
        return String.valueOf(value);
    }

    private String formatXpShort(long xp) {
        if (xp >= 1_000_000) return String.format("%.1fM", xp / 1_000_000.0);
        if (xp >= 1_000) return String.format("%.1fK", xp / 1_000.0);
        return String.valueOf(xp);
    }

    /**
     * Render de overlay en geef de Dimension terug zodat RuneLite weet hoe groot de overlay is.
     */
    public Dimension render(Graphics2D g) {
        // Initialize XP bij eerste render (game is dan geladen)
        initializeXp();

        int x = 10, y = 30, lh = 18, pw = 260;

        int lines = 6;
        if (rotationEnabled) lines += 2;
        if (accountSwitchEnabled) lines += 2;
        if (relogEnabled) lines += 1;
        lines += getActiveSkillLines();
        lines += 3; // XP info (altijd tonen)
        lines += 1; // Anti-ban regel (altijd eigen regel)
        if (rotationEnabled) lines += 1; // mini-stats andere skills (inactive summary)
        if (barbLootGeBankTripsTotal > 0 && activeSkill != null && activeSkill.startsWith("Loot")) {
            lines += 1;
        }
        if (impsGeBankTripsTotal > 0 && activeSkill != null && activeSkill.startsWith("Imps")) {
            lines += 1;
        }
        // Extra ruimte voor Combat-details (per-NPC / per-item) als er data is
        if (!killsByNpc.isEmpty() || !lootByItem.isEmpty()) {
            lines += 6;
        }

        int ph = (lines * lh) + 28;

        // Background
        g.setColor(new Color(0, 0, 0, 190));
        g.fillRoundRect(x, y - 16, pw, ph, 10, 10);

        // Border color per skill
        g.setColor(getBorderColor());
        g.drawRoundRect(x, y - 16, pw, ph, 10, 10);

        // Title
        g.setFont(new Font("Arial", Font.BOLD, 14));
        g.setColor(new Color(255, 215, 0));
        g.drawString(getSkillIcon() + " Multi-Skill Bot", x + 10, y);
        g.setFont(new Font("Arial", Font.PLAIN, 12));

        y += lh + 4;
        g.setColor(Color.WHITE);
        g.drawString("Runtime: " + formatRuntime(), x + 10, y);

        y += lh;
        g.setColor(new Color(255, 215, 0));
        g.drawString("▶ Actief: " + activeSkill, x + 10, y);

        // Status balk
        y += lh;
        g.setColor(getStatusColor());
        g.setFont(new Font("Arial", Font.BOLD, 12));
        g.drawString("● " + currentStatus, x + 10, y);
        g.setFont(new Font("Arial", Font.PLAIN, 12));

        if (barbLootGeBankTripsTotal > 0 && activeSkill != null && activeSkill.startsWith("Loot")) {
            y += lh;
            int nog = Math.max(0, barbLootGeBankTripsTotal - barbLootGeBankTripsDone);
            g.setColor(new Color(160, 210, 255));
            g.setFont(new Font("Arial", Font.PLAIN, 11));
            g.drawString("🛒 GE: nog " + nog + " trip(s) · voortgang "
                    + barbLootGeBankTripsDone + "/" + barbLootGeBankTripsTotal, x + 10, y);
            g.setFont(new Font("Arial", Font.PLAIN, 12));
        }

        if (impsGeBankTripsTotal > 0 && activeSkill != null && activeSkill.startsWith("Imps")) {
            y += lh;
            int nog = Math.max(0, impsGeBankTripsTotal - impsGeBankTripsDone);
            g.setColor(new Color(160, 210, 255));
            g.setFont(new Font("Arial", Font.PLAIN, 11));
            g.drawString("🛒 Imps GE: nog " + nog + " trip(s) · voortgang "
                    + impsGeBankTripsDone + "/" + impsGeBankTripsTotal, x + 10, y);
            g.setFont(new Font("Arial", Font.PLAIN, 12));
        }

        if (rotationEnabled) {
            y += lh;
            long mins = secondsUntilSwitch / 60;
            long secs = secondsUntilSwitch % 60;
            String countdownStr = String.format("%02d:%02d", mins, secs);
            Color countdownColor;
            if (secondsUntilSwitch < 60)       countdownColor = new Color(255, 100, 100);
            else if (secondsUntilSwitch < 180)  countdownColor = new Color(255, 200, 80);
            else                                countdownColor = new Color(200, 180, 255);
            g.setColor(countdownColor);
            g.drawString("⏱ Wissel over: " + countdownStr, x + 10, y);

            y += 8;
            int barW = pw - 20;
            g.setColor(new Color(50, 50, 50));
            g.fillRoundRect(x + 10, y, barW, 5, 3, 3);
            float progress = Math.max(0f, Math.min(1f, (float) secondsUntilSwitch / totalSwitchSeconds));
            int filled = (int) (barW * progress);
            g.setColor(countdownColor);
            if (filled > 0) g.fillRoundRect(x + 10, y, filled, 5, 3, 3);
            y += 2;
        }

        // Account info
        if (accountSwitchEnabled) {
            y += lh;
            g.setColor(new Color(180, 130, 255));
            g.drawString("👤 " + currentAccount + " (" + currentAccountNum + "/" + totalAccounts + ")", x + 10, y);
            y += lh;
            g.setColor(new Color(160, 160, 255));
            g.drawString("🔄 Account wissel: " + formatAccountSwitchHms(accountSecondsLeft)
                    + " (x" + accountSwitches.get() + ")", x + 10, y);
        }

        // Re-log timer (zelfde account)
        if (relogEnabled) {
            y += lh;
            long secs;
            String label;
            if (relogInPause && relogPauseSecondsRemaining > 0) {
                secs = relogPauseSecondsRemaining;
                label = "⏳ Re-log pauze: ";
            } else {
                secs = relogSecondsUntilLogout;
                label = "⏱ Re-log over: ";
            }
            long mins = secs / 60;
            long s = secs % 60;
            g.setColor(new Color(160, 220, 255));
            g.drawString(label + String.format("%02d:%02d", mins, s), x + 10, y);
        }

        // Separator
        y += lh - 4;
        g.setColor(new Color(100, 100, 100));
        g.drawLine(x + 10, y, x + pw - 10, y);
        y += 4;

        // Active skill stats
        y = renderSkillStats(g, x, y, lh);

        // Per-NPC kills & loot (Combat categorieën) — ook bij Starter (zelfde combat-loot pad)
        if (activeSkill.equals("Combat") || activeSkill.equals("Starter")) {
            y = renderCombatDetails(g, x, y, lh);
        }

        // === XP Info ===
        y += 4;
        g.setColor(new Color(100, 100, 100));
        g.drawLine(x + 10, y, x + pw - 10, y);
        y += lh - 2;

        g.setFont(new Font("Arial", Font.PLAIN, 11));
        y = renderXpInfo(g, x, y, lh - 2);
        y += lh; // Houd altijd een volle regel afstand na XP-regels.

        // Mini-stats andere skills (aparte regel zodat geen overlap met Anti-ban)
        if (rotationEnabled) {
            y += 2;
            g.setColor(new Color(120, 120, 120));
            g.setFont(new Font("Arial", Font.ITALIC, 10));
            g.drawString(getInactiveSkillSummary(), x + 10, y);
            y += lh;  // volle regelruimte zodat Anti-ban niet over summary heen komt
        }
        // Anti-ban altijd op eigen regel, onder de vorige tekst
        y += 2;
        g.setColor(new Color(180, 180, 180));
        g.setFont(new Font("Arial", Font.PLAIN, 11));
        g.drawString("Anti-ban: " + lastAntiBanAction, x + 10, y);
        y += lh;

        return new Dimension(pw, (y - 14) + 20);
    }

    /** Render XP info voor de actieve skill + totaal. */
    private int renderXpInfo(Graphics2D g, int x, int y, int lh) {
        g.setColor(new Color(200, 200, 255));
        g.setFont(new Font("Arial", Font.BOLD, 11));
        g.drawString("XP Verdiend:", x + 10, y);
        g.setFont(new Font("Arial", Font.PLAIN, 11));

        y += lh;
        // Toon XP voor actieve skill
        switch (activeSkill) {
            case "Combat":
                g.setColor(new Color(255, 120, 120));
                g.drawString("Combat: " + formatXpShort(getCombatXpGained()) + " (" + formatXpShort(getCombatXpPerHour()) + "/hr)", x + 10, y);
                break;
            case "Starter":
                g.setColor(new Color(255, 140, 100));
                g.drawString("Combat: " + formatXpShort(getCombatXpGained()) + " (" + formatXpShort(getCombatXpPerHour()) + "/hr)", x + 10, y);
                y += lh;
                g.setColor(new Color(139, 195, 74));
                g.drawString("WC: " + formatXpShort(getWcXpGained()) + " (" + formatXpShort(getWcXpPerHour()) + "/hr)", x + 10, y);
                y += lh;
                g.setColor(new Color(255, 160, 60));
                g.drawString("FM: " + formatXpShort(getFmXpGained()) + " (" + formatXpShort(getFmXpPerHour()) + "/hr)", x + 10, y);
                break;
            case "Woodcutting":
                g.setColor(new Color(139, 195, 74));
                g.drawString("WC: " + formatXpShort(getWcXpGained()) + " (" + formatXpShort(getWcXpPerHour()) + "/hr)", x + 10, y);
                if (getFmXpGained() > 0) {
                    y += lh;
                    g.setColor(new Color(255, 160, 60));
                    g.drawString("FM: " + formatXpShort(getFmXpGained()) + " (" + formatXpShort(getFmXpPerHour()) + "/hr)", x + 10, y);
                }
                break;
            case "Mining":
                g.setColor(new Color(205, 170, 100));
                g.drawString("Mining: " + formatXpShort(getMiningXpGained()) + " (" + formatXpShort(getMiningXpPerHour()) + "/hr)", x + 10, y);
                break;
            case "Fishing":
                g.setColor(new Color(100, 180, 255));
                g.drawString("Fishing: " + formatXpShort(getFishingXpGained()) + " (" + formatXpShort(getFishingXpPerHour()) + "/hr)", x + 10, y);
                break;
        }

        // Totale XP onderaan
        y += lh;
        long totalXp = getCombatXpGained() + getWcXpGained() + getMiningXpGained() + getFishingXpGained() + getFmXpGained();
        g.setColor(new Color(255, 215, 0));
        g.drawString("Totaal: " + formatXpShort(totalXp) + " (" + formatXpShort(perHour(totalXp)) + "/hr)", x + 10, y);

        return y;
    }

    /** Render per-NPC kills en per-item loot onder de Combat-sectie. */
    private int renderCombatDetails(Graphics2D g, int x, int y, int lh) {
        if (killsByNpc.isEmpty() && lootByItem.isEmpty()) {
            return y;
        }

        y += 4;
        g.setColor(new Color(100, 100, 100));
        g.drawLine(x + 10, y, x + 250, y);
        y += lh - 4;

        g.setFont(new Font("Arial", Font.BOLD, 11));
        g.setColor(new Color(255, 180, 180));
        g.drawString("Kills per NPC:", x + 10, y);
        g.setFont(new Font("Arial", Font.PLAIN, 11));

        int shown = 0;
        for (Map.Entry<String, AtomicInteger> e : killsByNpc.entrySet()) {
            if (shown >= 4) break;
            y += lh - 4;
            g.setColor(new Color(255, 220, 220));
            g.drawString("- " + e.getKey() + ": " + e.getValue().get(), x + 14, y);
            shown++;
        }

        if (!lootByItem.isEmpty()) {
            y += lh - 2;
            g.setColor(new Color(100, 100, 100));
            g.drawLine(x + 10, y, x + 250, y);
            y += lh - 4;

            g.setFont(new Font("Arial", Font.BOLD, 11));
            g.setColor(new Color(200, 220, 255));
            g.drawString("Loot per item:", x + 10, y);
            g.setFont(new Font("Arial", Font.PLAIN, 11));

            shown = 0;
            for (Map.Entry<String, AtomicInteger> e : lootByItem.entrySet()) {
                if (shown >= 4) break;
                y += lh - 4;
                g.setColor(new Color(200, 230, 255));
                g.drawString("- " + e.getKey() + ": " + e.getValue().get(), x + 14, y);
                shown++;
            }
        }

        return y;
    }

    private Color getStatusColor() {
        if (currentStatus.startsWith("⏸")) return new Color(160, 160, 160);
        if (currentStatus.contains("NPC-gevecht") || currentStatus.contains("gevecht")) return new Color(255, 90, 90);
        if (currentStatus.startsWith("⚠")) return new Color(255, 80, 80);
        if (currentStatus.contains("🔄")) return new Color(180, 130, 255);
        if (currentStatus.contains("Bank") || currentStatus.contains("bank")) return new Color(255, 200, 80);
        if (currentStatus.contains("→") || currentStatus.contains("lopen") || currentStatus.contains("Locatie")) return new Color(100, 200, 255);
        if (currentStatus.contains("✓")) return new Color(80, 255, 120);
        return new Color(120, 255, 120);
    }

    private int getActiveSkillLines() {
        switch (activeSkill) {
            case "Combat": return 4;
            case "Starter": return starterInNpcCombat ? 6 : 5;
            case "Woodcutting": return 3;
            case "Mining": return 3;
            case "Fishing": return 3;
            default: return 3;
        }
    }

    private Color getBorderColor() {
        switch (activeSkill) {
            case "Combat": return new Color(200, 60, 60, 220);
            case "Starter": return new Color(220, 120, 40, 220);
            case "Woodcutting": return new Color(60, 180, 60, 220);
            case "Mining": return new Color(150, 120, 60, 220);
            case "Fishing": return new Color(60, 120, 200, 220);
            default: return new Color(200, 200, 200, 220);
        }
    }

    private String getSkillIcon() {
        switch (activeSkill) {
            case "Combat": return "⚔";
            case "Starter": return "🎓";
            case "Woodcutting": return "🪓";
            case "Mining": return "⛏";
            case "Fishing": return "🎣";
            default: return "▶";
        }
    }

    private int renderSkillStats(Graphics2D g, int x, int y, int lh) {
        g.setFont(new Font("Arial", Font.PLAIN, 12));

        switch (activeSkill) {
            case "Combat":
                y += lh;
                g.setColor(new Color(255, 80, 80));
                g.drawString("Kills: " + getKills() + " (" + perHour(getKills()) + "/hr)", x + 10, y);
                y += lh;
                g.setColor(new Color(80, 255, 80));
                g.drawString("Loot items: " + getLootedItems(), x + 10, y);
                y += lh;
                g.setColor(new Color(255, 255, 100));
                g.drawString("Loot waarde: " + formatGp(getTotalLootValue()), x + 10, y);
                y += lh;
                g.setColor(new Color(100, 200, 255));
                g.drawString("GP/uur: " + formatGp(perHour(getTotalLootValue())), x + 10, y);
                break;

            case "Woodcutting":
                y += lh;
                g.setColor(new Color(139, 195, 74));
                g.drawString("Logs gekapt: " + getLogsChopped() + " (" + perHour(getLogsChopped()) + "/hr)", x + 10, y);
                y += lh;
                g.setColor(new Color(255, 183, 77));
                g.drawString("Logs gedropt: " + logsDropped.get(), x + 10, y);
                y += lh;
                g.setColor(new Color(100, 200, 255));
                g.drawString("Logs gebankt: " + logsBanked.get(), x + 10, y);
                break;

            case "Mining":
                y += lh;
                g.setColor(new Color(205, 170, 100));
                g.drawString("Erts gemijnd: " + getOresMined() + " (" + perHour(getOresMined()) + "/hr)", x + 10, y);
                y += lh;
                g.setColor(new Color(255, 183, 77));
                g.drawString("Erts gedropt: " + oresDropped.get(), x + 10, y);
                y += lh;
                g.setColor(new Color(100, 200, 255));
                g.drawString("Erts gebankt: " + oresBanked.get(), x + 10, y);
                break;

            case "Fishing":
                y += lh;
                g.setColor(new Color(100, 180, 255));
                g.drawString("Vis gevangen: " + getFishCaught() + " (" + perHour(getFishCaught()) + "/hr)", x + 10, y);
                y += lh;
                g.setColor(new Color(255, 183, 77));
                g.drawString("Vis gedropt: " + fishDropped.get(), x + 10, y);
                y += lh;
                g.setColor(new Color(100, 200, 255));
                g.drawString("Vis gebankt: " + fishBanked.get(), x + 10, y);
                break;

            case "Starter":
                y += lh;
                g.setColor(new Color(255, 200, 120));
                g.drawString("Fase: " + (starterPhaseName.isEmpty() ? "—" : starterPhaseName), x + 10, y);
                y += lh;
                if (starterMeleeGoal > 0 && starterWcGoal > 0 && starterFmGoal > 0) {
                    g.setColor(new Color(200, 220, 255));
                    g.drawString("Doelen: Melee (Att/Str/Def) ≥" + starterMeleeGoal
                            + " · WC ≥" + starterWcGoal + " · FM ≥" + starterFmGoal, x + 10, y);
                    y += lh;
                }
                if (starterInNpcCombat) {
                    g.setColor(new Color(255, 100, 100));
                    g.drawString("NPC-gevecht: wacht — geen bank/lopen/Jon", x + 10, y);
                    y += lh;
                }
                g.setColor(new Color(255, 80, 80));
                g.drawString("Kills: " + getKills() + " (" + perHour(getKills()) + "/hr)", x + 10, y);
                y += lh;
                g.setColor(new Color(139, 195, 74));
                g.drawString("Logs: " + getLogsChopped() + " · FM-XP zie onder", x + 10, y);
                break;
        }
        return y;
    }

    private String getInactiveSkillSummary() {
        StringBuilder sb = new StringBuilder();
        if (!activeSkill.equals("Combat") && getKills() > 0)
            sb.append("⚔").append(getKills()).append("k ");
        if (!activeSkill.equals("Woodcutting") && getLogsChopped() > 0)
            sb.append("🪓").append(getLogsChopped()).append(" ");
        if (!activeSkill.equals("Mining") && getOresMined() > 0)
            sb.append("⛏").append(getOresMined()).append(" ");
        if (!activeSkill.equals("Fishing") && getFishCaught() > 0)
            sb.append("🎣").append(getFishCaught()).append(" ");
        return sb.length() > 0 ? sb.toString().trim() : "Geen andere skills actief";
    }
}
