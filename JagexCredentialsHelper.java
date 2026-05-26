package com.combatbot;

import net.storm.api.account.GameAccount;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Parsed geplakte Jagex credentials (JX_DISPLAY_NAME=, JX_ACCESS_TOKEN=, etc.)
 * en bouwt GameAccount voor login. Ondersteunt meerdere blokken gescheiden door "---".
 * Laadt ook credentials uit bestanden (zoals Storm/RuneLite profielen in een map).
 * Bij het laden worden credentials per account opgeslagen zodat ze beschikbaar blijven
 * als het oorspronkelijke bestand later door een ander account wordt overschreven.
 */
public final class JagexCredentialsHelper {

    private static final int CREDENTIALS_WRITE_RETRIES = 4;
    private static final int CREDENTIALS_WRITE_RETRY_MS = 60;

    private static final String STORE_SUBDIR = ".runelite/combatbot/accounts";

    private static final String BLOCK_SEP = "---";
    private static final String[] USERNAME_KEYS = { "JX_LOGIN_EMAIL", "JX_USERNAME", "login", "username" };
    private static final String[] PASSWORD_KEYS = { "JX_PASSWORD", "JX_ACCESS_TOKEN", "JX_SESSION_ID", "password", "session" };

    /** Keys voor username in credentials-bestand (incl. Storm profielen: Character ID, etc.). */
    private static final String[] FILE_USERNAME_KEYS = {
        "JX_LOGIN_EMAIL", "JX_USERNAME", "JX_CHARACTER_ID", "characterId", "character_id",
        "Character ID", "login", "username"
    };
    /** Keys voor password/session in credentials-bestand (incl. Storm: Session ID). OAuth gebruikt meestal access token eerst. */
    private static final String[] FILE_PASSWORD_KEYS = {
        "JX_PASSWORD", "JX_ACCESS_TOKEN", "JX_SESSION_ID", "sessionId", "session_id",
        "Session ID", "access_token", "password", "session"
    };
    private static final String[] FILE_DISPLAY_NAME_KEYS = {
        "JX_DISPLAY_NAME", "displayName", "display_name", "Display Name"
    };

    /**
     * Eén geparst Jagex-account (display name + map van alle JX_* keys).
     */
    public static class ParsedJagexAccount {
        private final String displayName;
        private final Map<String, String> props;

        public ParsedJagexAccount(String displayName, Map<String, String> props) {
            this.displayName = displayName;
            this.props = props;
        }

        public String getDisplayName() { return displayName; }
        public String get(String key) { return props.get(key); }

        /** Bouwt een GameAccount voor gebruik met Storm login. */
        public GameAccount toGameAccount() {
            String username = firstPresent(props, USERNAME_KEYS);
            if (username == null || username.isEmpty()) {
                username = firstPresent(props, new String[] { "JX_DISPLAY_NAME", "JX_CHARACTER_ID" });
            }
            if (username == null) username = displayName != null ? displayName : "Jagex";
            String password = firstPresent(props, PASSWORD_KEYS);
            if (password == null) password = "";
            if (displayName != null && !displayName.isEmpty()) {
                return new GameAccount(username, password, displayName, password, "");
            }
            return new GameAccount(username, password);
        }

        /** Alle JX_* (en bekende) keys voor wegschrijven naar credentials.properties. */
        public Properties toProperties() {
            Properties p = new Properties();
            for (Map.Entry<String, String> e : props.entrySet()) {
                p.setProperty(e.getKey(), e.getValue());
            }
            return p;
        }
    }

    /**
     * Parse de geplakte tekst.
     *
     * Ondersteunt:
     *  - Blokken gescheiden door een regel met '---'
     *  - Lege regels tussen blokken
     *  - Of simpelweg meerdere credentials onder elkaar: elke nieuwe JX_DISPLAY_NAME= start een nieuw blok.
     */
    public static List<ParsedJagexAccount> parsePastedCredentials(String pasted) {
        List<ParsedJagexAccount> out = new ArrayList<>();
        if (pasted == null || pasted.trim().isEmpty()) return out;

        Map<String, String> current = new LinkedHashMap<>();

        String[] lines = pasted.split("\\n");
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                // Lege regel = blok afsluiten
                if (!current.isEmpty()) {
                    out.add(buildAccountFromProps(current));
                    current = new LinkedHashMap<>();
                }
                continue;
            }

            if (line.equals(BLOCK_SEP)) {
                // Expliciete scheidingsregel '---'
                if (!current.isEmpty()) {
                    out.add(buildAccountFromProps(current));
                    current = new LinkedHashMap<>();
                }
                continue;
            }

            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq).trim();
            String val = line.substring(eq + 1).trim();

            // Nieuwe JX_DISPLAY_NAME terwijl we er al één hebben → nieuw blok starten
            if ("JX_DISPLAY_NAME".equalsIgnoreCase(key) && !current.isEmpty() && current.containsKey("JX_DISPLAY_NAME")) {
                out.add(buildAccountFromProps(current));
                current = new LinkedHashMap<>();
            }

            if (!key.isEmpty()) {
                current.put(key, val);
            }
        }

        if (!current.isEmpty()) {
            out.add(buildAccountFromProps(current));
        }

        return out;
    }

    private static ParsedJagexAccount buildAccountFromProps(Map<String, String> props) {
        String displayName = props.get("JX_DISPLAY_NAME");
        if (displayName == null) displayName = props.get("displayName");
        if (displayName == null) displayName = props.get("display_name");
        if (displayName == null || displayName.isEmpty()) displayName = "Onbekend";
        return new ParsedJagexAccount(displayName, props);
    }

    /**
     * Bouwt een {@link GameAccount} voor OAuth-login uit een beheerde accountrij (display, character id, session).
     * Jagex OAuth verwacht het numeric character id als login-id; de display name is wat op het login-scherm getoond wordt.
     */
    public static GameAccount gameAccountFromManagedRow(ManagedJagexAccountsStore.ManagedJagexAccountRow row) {
        if (row == null || row.isEmpty()) {
            return null;
        }
        if (row.sessionId == null || row.sessionId.trim().isEmpty()) {
            return null;
        }
        String sess = row.sessionId.trim();
        String disp = row.displayName != null ? row.displayName.trim() : "";
        String loginId;
        if (row.characterId != null && !row.characterId.trim().isEmpty()) {
            loginId = row.characterId.trim();
        } else if (!disp.isEmpty()) {
            loginId = disp;
        } else {
            return null;
        }
        if (disp.isEmpty()) {
            disp = loginId;
        }
        // auth = zelfde token als password: sommige Storm/RuneLite-paden lezen getAuth() voor OAuth.
        return new GameAccount(loginId, sess, disp, sess, "");
    }

    /** Slaat Jagex-keys op onder display name (zelfde pad als account switcher / re-log). */
    public static void saveManagedRowCredentials(ManagedJagexAccountsStore.ManagedJagexAccountRow row) {
        if (row == null || row.isEmpty() || row.displayName == null || row.displayName.trim().isEmpty()) {
            return;
        }
        if (row.sessionId == null || row.sessionId.trim().isEmpty()) {
            return;
        }
        Properties props = new Properties();
        fillPropertiesFromManagedRow(props, row);
        saveCredentialsForAccount(row.displayName.trim(), props);
    }

    /**
     * Zelfde JX_*-inhoud als {@link #saveManagedRowCredentials}, maar naar een willekeurig bestand
     * (typisch het actieve RuneLite-profiel {@code credentials.properties}) zodat
     * {@link #loadFromFile}/{@link #gameAccountFromProperties} en Storm slagen.
     * <p>
     * Bestaande inhoud wordt <b>gemerged</b>: het hele bestand overschrijven zou anders o.a. {@code JX_REFRESH_TOKEN}
     * en andere launcher-sleutels wissen — daardoor faalt daarna zelfs normale RuneLite-login.
     * </p>
     *
     * @return false bij ontbrekende session of I/O-fout
     */
    public static boolean writeCredentialsPropertiesFile(File file, ManagedJagexAccountsStore.ManagedJagexAccountRow row) {
        if (file == null || row == null || row.isEmpty()) {
            return false;
        }
        if (row.sessionId == null || row.sessionId.trim().isEmpty()) {
            return false;
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        Properties props = new Properties();
        if (file.exists() && file.canRead()) {
            try (FileInputStream fis = new FileInputStream(file)) {
                props.load(fis);
            } catch (IOException ignored) {
                // beschadigd of leeg → alleen onze keys
            }
        }
        fillPropertiesFromManagedRow(props, row);
        IOException last = null;
        for (int attempt = 1; attempt <= CREDENTIALS_WRITE_RETRIES; attempt++) {
            try (FileOutputStream fos = new FileOutputStream(file)) {
                props.store(fos, "CombatBot — synced from Accounts tab (double-click)");
                fos.getFD().sync();
                return true;
            } catch (IOException e) {
                last = e;
                try {
                    Thread.sleep(CREDENTIALS_WRITE_RETRY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        DebugLog.log("Accounts", "Schrijven mislukt na " + CREDENTIALS_WRITE_RETRIES + " pogingen: "
                + file.getAbsolutePath() + " — " + (last != null ? last.getMessage() : "?"));
        return false;
    }

    /** Trim + opeenvolgende spaties → één spatie; voor matching rotatie-label ↔ Accounts-tab. */
    public static String normalizeDisplayNameForMatch(String s) {
        if (s == null) {
            return "";
        }
        return s.trim().replaceAll("\\s+", " ");
    }

    private static void fillPropertiesFromManagedRow(Properties props, ManagedJagexAccountsStore.ManagedJagexAccountRow row) {
        props.setProperty("JX_DISPLAY_NAME", row.displayName.trim());
        if (row.characterId != null && !row.characterId.trim().isEmpty()) {
            props.setProperty("JX_CHARACTER_ID", row.characterId.trim());
            props.remove("JX_USERNAME");
        } else {
            props.setProperty("JX_USERNAME", row.displayName.trim());
            props.remove("JX_CHARACTER_ID");
        }
        String sess = row.sessionId.trim();
        props.setProperty("JX_SESSION_ID", sess);
        // Launcher/RuneLite verwacht dit veld vaak naast SESSION_ID; zonder faalt OAuth vaak met "Failed to login".
        props.setProperty("JX_ACCESS_TOKEN", sess);
    }

    /** Eerste {@code jagex:pad} uit Account lijst, of {@code null}. */
    public static String firstJagexCredentialsPathFromAccountList(String accountList) {
        if (accountList == null || accountList.trim().isEmpty()) {
            return null;
        }
        for (String entry : accountList.split("[|\\n]+")) {
            String e = entry.trim();
            if (e.toLowerCase().startsWith("jagex:")) {
                String p = e.substring(6).trim();
                return !p.isEmpty() ? p : null;
            }
        }
        return null;
    }

    /**
     * Doelbestand voor profiel-sync bij dubbelklik: expliciet config-pad, anders eerste jagex:-regel.
     */
    public static File resolveLoginScreenCredentialsFile(String loginScreenCredentialsPath, String accountList) {
        return resolveLoginScreenCredentialsFile(loginScreenCredentialsPath, accountList, null);
    }

    /**
     * Zelfde als {@link #resolveLoginScreenCredentialsFile(String, String)}, maar als dat niets oplevert:
     * optioneel pad uit de account-switcher entry (bijv. {@code jagex:C:/.../credentials.properties} voor dit account).
     * Zonder dit blijft het profielbestand ongewijzigd als de geavanceerde accountlijst leeg is en alleen
     * geplakte/Accounts-tab accounts worden gebruikt — dan moet de gebruiker óf "Profiel credentials.properties"
     * zetten óf minstens één jagex:-regel in de lijst.
     */
    public static File resolveLoginScreenCredentialsFile(String loginScreenCredentialsPath, String accountList,
            String jagexPathFromAccountEntry) {
        if (loginScreenCredentialsPath != null) {
            String t = loginScreenCredentialsPath.trim();
            if (!t.isEmpty()) {
                return new File(t);
            }
        }
        String jag = firstJagexCredentialsPathFromAccountList(accountList);
        if (jag != null && !jag.trim().isEmpty()) {
            return new File(jag.trim());
        }
        if (jagexPathFromAccountEntry != null) {
            String t = jagexPathFromAccountEntry.trim();
            if (!t.isEmpty()) {
                return new File(t);
            }
        }
        return null;
    }

    private static String firstPresent(Map<String, String> map, String[] keys) {
        for (String k : keys) {
            String v = map.get(k);
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return null;
    }

    private static String getProp(Properties props, String... keys) {
        for (String key : keys) {
            String v = props.getProperty(key);
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return null;
    }

    /** Map waar we per account een kopie van de credentials bewaren (niet overschreven door andere login). */
    private static File getAccountsDir() {
        String home = System.getProperty("user.home", "");
        File dir = new File(home, STORE_SUBDIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private static String sanitizeDisplayNameForFile(String displayName) {
        if (displayName == null || displayName.isEmpty()) return "unknown";
        String safe = displayName.replaceAll("[^a-zA-Z0-9_-]", "_");
        return safe.length() > 80 ? safe.substring(0, 80) : safe;
    }

    /**
     * Slaat credentials op onder de display name zodat we later altijd met dit account kunnen inloggen,
     * ook als het oorspronkelijke bestand door een ander account is overschreven.
     */
    public static void saveCredentialsForAccount(String displayName, Properties props) {
        if (props == null || displayName == null || displayName.isEmpty()) return;
        File dir = getAccountsDir();
        File file = new File(dir, sanitizeDisplayNameForFile(displayName) + ".properties");
        try (FileOutputStream fos = new FileOutputStream(file)) {
            props.store(fos, "CombatBot opgeslagen credentials voor " + displayName);
        } catch (IOException ignored) {
            // Geen paint hier; storage is best-effort
        }
    }

    /**
     * Laadt eerder opgeslagen credentials voor dit account (onafhankelijk van het oorspronkelijke pad).
     * Retourneert null als er niets opgeslagen is of het bestand ongeldig is.
     */
    public static GameAccount loadSavedCredentials(String displayName) {
        Properties props = loadSavedCredentialsProperties(displayName);
        return props != null ? gameAccountFromProperties(props) : null;
    }

    /** Zelfde bestand als {@link #loadSavedCredentials}, als ruwe Properties (voor profiel-sync). */
    public static Properties loadSavedCredentialsProperties(String displayName) {
        if (displayName == null || displayName.isEmpty()) {
            return null;
        }
        File dir = getAccountsDir();
        File file = new File(dir, sanitizeDisplayNameForFile(displayName) + ".properties");
        if (!file.exists() || !file.canRead()) {
            return null;
        }
        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(file)) {
            props.load(fis);
        } catch (IOException e) {
            return null;
        }
        return props;
    }

    /**
     * Voegt/overschrijft Jagex-login keys in het RuneLite-profielbestand (merge, geen volledige wipe).
     * Nodig bij account-wissel: anders blijft het vorige account op schijf staan en logt Play weer die in.
     */
    public static boolean mergeAccountPropertiesIntoProfileFile(File profileFile, Properties accountKeys) {
        if (profileFile == null || accountKeys == null || accountKeys.isEmpty()) {
            return false;
        }
        File parent = profileFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        Properties merged = new Properties();
        if (profileFile.exists()) {
            try (FileInputStream fis = new FileInputStream(profileFile)) {
                merged.load(fis);
            } catch (IOException ignored) {
            }
        }
        for (String name : accountKeys.stringPropertyNames()) {
            if (shouldOverlayCredentialKey(name)) {
                String v = accountKeys.getProperty(name);
                if (v != null) {
                    merged.setProperty(name, v);
                }
            }
        }
        IOException last = null;
        for (int attempt = 1; attempt <= CREDENTIALS_WRITE_RETRIES; attempt++) {
            try (FileOutputStream fos = new FileOutputStream(profileFile)) {
                merged.store(fos, "CombatBot — account switch (sync naar profiel)");
                fos.getFD().sync();
                return true;
            } catch (IOException e) {
                last = e;
                try {
                    Thread.sleep(CREDENTIALS_WRITE_RETRY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        DebugLog.log("Accounts", "Merge naar profiel mislukt: " + profileFile.getAbsolutePath()
                + " — " + (last != null ? last.getMessage() : "?"));
        return false;
    }

    private static boolean shouldOverlayCredentialKey(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        if (name.startsWith("JX_")) {
            return true;
        }
        for (String k : FILE_DISPLAY_NAME_KEYS) {
            if (k.equalsIgnoreCase(name)) {
                return true;
            }
        }
        for (String k : FILE_USERNAME_KEYS) {
            if (k.equalsIgnoreCase(name)) {
                return true;
            }
        }
        for (String k : FILE_PASSWORD_KEYS) {
            if (k.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Schrijft het juiste account naar het profiel-credentialsbestand vóór Play bij account-wissel.
     * Volgorde: blok uit geplakte config → stash onder ~/.runelite/combatbot/accounts → minimale OAuth uit GameAccount.
     */
    public static boolean syncAccountToProfileForSwitcher(File profileFile, String displayName,
            String pastedCredentials, GameAccount fallbackAccount) {
        if (profileFile == null || displayName == null || displayName.trim().isEmpty()) {
            return false;
        }
        String dn = displayName.trim();
        String dnNorm = normalizeDisplayNameForMatch(dn);
        for (ParsedJagexAccount p : parsePastedCredentials(pastedCredentials != null ? pastedCredentials : "")) {
            if (normalizeDisplayNameForMatch(p.getDisplayName()).equalsIgnoreCase(dnNorm)) {
                return mergeAccountPropertiesIntoProfileFile(profileFile, p.toProperties());
            }
        }
        Properties stash = loadSavedCredentialsProperties(dn);
        if (stash != null && !stash.isEmpty()) {
            return mergeAccountPropertiesIntoProfileFile(profileFile, stash);
        }
        if (fallbackAccount != null) {
            Properties minimal = propertiesFromOAuthGameAccount(fallbackAccount, dn);
            return mergeAccountPropertiesIntoProfileFile(profileFile, minimal);
        }
        return false;
    }

    private static Properties propertiesFromOAuthGameAccount(GameAccount ga, String displayName) {
        Properties p = new Properties();
        if (displayName != null && !displayName.isEmpty()) {
            p.setProperty("JX_DISPLAY_NAME", displayName.trim());
        }
        String sess = ga.getPassword();
        if (sess != null && !sess.trim().isEmpty()) {
            sess = sess.trim();
            p.setProperty("JX_ACCESS_TOKEN", sess);
            p.setProperty("JX_SESSION_ID", sess);
        }
        String u = ga.getUsername();
        if (u != null && !u.trim().isEmpty()) {
            u = u.trim();
            boolean numericId = u.chars().allMatch(Character::isDigit);
            if (numericId) {
                p.setProperty("JX_CHARACTER_ID", u);
                p.remove("JX_USERNAME");
            } else {
                p.setProperty("JX_USERNAME", u);
            }
        }
        return p;
    }

    /**
     * Laadt credentials uit een bestand en slaat ze meteen op per account,
     * zodat ze later (bij account wissel) nog beschikbaar zijn als het bestand is overschreven.
     */
    public static GameAccount loadFromFile(String path) {
        File file = new File(path);
        if (!file.exists() || !file.canRead()) {
            return null;
        }
        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(file)) {
            props.load(fis);
        } catch (IOException e) {
            return null;
        }
        GameAccount account = gameAccountFromProperties(props);
        if (account != null) {
            String displayName = getProp(props, FILE_DISPLAY_NAME_KEYS);
            if (displayName == null || displayName.isEmpty()) displayName = account.getDisplayName();
            if (displayName == null || displayName.isEmpty()) displayName = account.getUsername();
            if (displayName != null && !displayName.isEmpty()) {
                saveCredentialsForAccount(displayName, props);
            }
        }
        return account;
    }

    /**
     * Bouwt een GameAccount uit al geladen properties (zelfde key-varianten als loadFromFile).
     */
    public static GameAccount gameAccountFromProperties(Properties props) {
        if (props == null) return null;
        String username = getProp(props, FILE_USERNAME_KEYS);
        String password = getProp(props, FILE_PASSWORD_KEYS);
        String displayName = getProp(props, FILE_DISPLAY_NAME_KEYS);
        if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
            return null;
        }
        if (displayName != null && !displayName.isEmpty()) {
            return new GameAccount(username, password, displayName, password, "");
        }
        return new GameAccount(username, password);
    }

    /** Parse enabled display names uit comma-separated string. */
    public static Set<String> parseEnabledDisplayNames(String enabledStr) {
        if (enabledStr == null || enabledStr.trim().isEmpty()) return java.util.Collections.emptySet();
        return Arrays.stream(enabledStr.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    /**
     * Lijst enabled geplakte Jagex-accounts (volgorde uit pasted blob).
     */
    public static List<ParsedJagexAccount> listEnabledPastedJagexAccounts(CombatBotConfig config) {
        Set<String> enabledNames = parseEnabledDisplayNames(config.enabledDisplayNames());
        List<ParsedJagexAccount> out = new ArrayList<>();
        for (ParsedJagexAccount acc : parsePastedCredentials(config.pastedCredentials())) {
            if (enabledNames.contains(acc.getDisplayName())) {
                out.add(acc);
            }
        }
        return out;
    }

    /**
     * Kies één enabled Jagex-account voor Re-log als {@code reLogoutAccount} leeg is.
     * Bij meerdere: launcher-RSN op login-scherm, of precies één rotatie-vink in Accounts-tab.
     */
    public static ParsedJagexAccount resolveEnabledJagexAccountForAutoLogin(CombatBotConfig config) {
        List<ParsedJagexAccount> active = listEnabledPastedJagexAccounts(config);
        if (active.isEmpty()) {
            return null;
        }
        if (active.size() == 1) {
            return active.get(0);
        }
        String hint = loginScreenDisplayNameHint();
        if (hint != null && !hint.isEmpty()) {
            for (ParsedJagexAccount acc : active) {
                if (acc.getDisplayName().equalsIgnoreCase(hint)) {
                    return acc;
                }
            }
        }
        int rotationVinks = 0;
        ManagedJagexAccountsStore.ManagedJagexAccountRow rotationRow = null;
        for (ManagedJagexAccountsStore.ManagedJagexAccountRow r
                : ManagedJagexAccountsStore.parseRows(config.managedJagexAccountsBlob())) {
            if (r != null && r.rotationEnabled && r.displayName != null && !r.displayName.trim().isEmpty()) {
                rotationVinks++;
                rotationRow = r;
            }
        }
        if (rotationVinks == 1 && rotationRow != null) {
            String dn = rotationRow.displayName.trim();
            for (ParsedJagexAccount acc : active) {
                if (acc.getDisplayName().equalsIgnoreCase(dn)) {
                    return acc;
                }
            }
        }
        return null;
    }

    /** RSN/launcher op login-scherm — hint welk enabled account te kiezen. */
    public static String loginScreenDisplayNameHint() {
        try {
            CombatBotPlugin plugin = CombatBotRuntime.getActivePlugin();
            if (plugin != null && plugin.getRuneliteClient() != null) {
                String launcher = plugin.getRuneliteClient().getLauncherDisplayName();
                if (launcher != null && !launcher.trim().isEmpty()) {
                    return launcher.trim();
                }
                String user = plugin.getRuneliteClient().getUsername();
                if (user != null && !user.trim().isEmpty()) {
                    return user.trim();
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            String storm = net.storm.sdk.game.Client.getDisplayName();
            if (storm != null && !storm.trim().isEmpty()) {
                return storm.trim();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Serialiseer enabled display names naar comma-separated. */
    public static String serializeEnabledDisplayNames(Set<String> names) {
        if (names == null || names.isEmpty()) return "";
        return String.join(",", names);
    }
}
