package com.combatbot;

import net.storm.api.plugins.config.ConfigItem;
import net.storm.api.plugins.config.ConfigManager;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Exporteert en importeert Combat Bot-instellingen als JSON-bestand
 * (zelfde {@code combatbot}-groep als Storm-config), voor gebruik op een andere PC.
 * Export omvat alle {@link ConfigItem}-keys op {@link CombatBotConfig}, behalve de keys in {@code NEVER_TRANSFER_KEYS}.
 * Accountlijsten, geplakte Jagex-credentials en re-log accounttekst worden bewust niet meegenomen.
 */
public final class CombatBotSettingsTransfer {

    public static final String EXPORT_VERSION_KEY = "_combatbotSettingsExport";
    public static final int EXPORT_VERSION = 2;

    private static final String GROUP = "combatbot";

    /** Geen export/import: gevoelige of pc-lokale accountdata. */
    private static final Set<String> NEVER_TRANSFER_KEYS;

    static {
        Set<String> nt = new HashSet<>();
        nt.add("accountList");
        nt.add("loginScreenCredentialsPath");
        nt.add("pastedCredentials");
        nt.add("enabledDisplayNames");
        nt.add("reLogoutAccount");
        nt.add("loginNow");
        nt.add("managedJagexAccountsBlob");
        nt.add("accountStatSnapshotsBlob");
        NEVER_TRANSFER_KEYS = Collections.unmodifiableSet(nt);
    }

    /** Eenmalige actie-keys: na import altijd veilig op false. */
    private static final String[] RESET_AFTER_IMPORT = {
            "loginNow",
            "switchNow",
            "impsSellNow",
            "saveTilePreset",
            "doLoadTilePreset",
            "clearTileMarkers"
    };

    private CombatBotSettingsTransfer() {
    }

    public static String exportToJson(CombatBotConfig config) {
        TreeMap<String, Object> values = new TreeMap<>();
        for (Method m : CombatBotConfig.class.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers()) || m.isBridge()) {
                continue;
            }
            ConfigItem ci = m.getAnnotation(ConfigItem.class);
            if (ci == null || m.getParameterCount() != 0 || m.getReturnType() == void.class) {
                continue;
            }
            String key = ci.keyName();
            if (NEVER_TRANSFER_KEYS.contains(key) || EXPORT_VERSION_KEY.equals(key)) {
                continue;
            }
            Object val;
            try {
                val = m.invoke(config);
            } catch (ReflectiveOperationException e) {
                continue;
            }
            values.putIfAbsent(key, val);
        }

        StringBuilder sb = new StringBuilder(16384);
        sb.append('{');
        appendInt(sb, EXPORT_VERSION_KEY, EXPORT_VERSION);
        for (Map.Entry<String, Object> e : values.entrySet()) {
            sb.append(',');
            appendConfigValue(sb, e.getKey(), e.getValue());
        }
        sb.append('}');
        return sb.toString();
    }

    private static void appendConfigValue(StringBuilder sb, String key, Object val) {
        if (val == null) {
            appendStr(sb, key, "");
            return;
        }
        if (val instanceof Boolean) {
            appendBool(sb, key, (Boolean) val);
        } else if (val instanceof Integer) {
            appendInt(sb, key, (Integer) val);
        } else if (val instanceof String) {
            appendStr(sb, key, (String) val);
        } else if (val instanceof Enum) {
            appendStr(sb, key, ((Enum<?>) val).name());
        } else {
            appendStr(sb, key, String.valueOf(val));
        }
    }

    /**
     * Past alle keys uit het JSON-bestand toe. Strings met komma's/quotes worden ondersteund.
     * Na afloop worden eenmalige trigger-keys op {@code false} gezet.
     *
     * @return aantal toegepaste keys (excl. metadata)
     */
    public static int importFromJson(String json, ConfigManager configManager) {
        Map<String, String> map = parseJsonObject(json);
        int applied = 0;
        for (Map.Entry<String, String> e : map.entrySet()) {
            String key = e.getKey();
            if (EXPORT_VERSION_KEY.equals(key) || NEVER_TRANSFER_KEYS.contains(key)) {
                continue;
            }
            configManager.setConfiguration(GROUP, key, e.getValue());
            applied++;
        }
        for (String k : RESET_AFTER_IMPORT) {
            configManager.setConfiguration(GROUP, k, "false");
        }
        return applied;
    }

    private static void appendStr(StringBuilder sb, String key, String val) {
        sb.append('"').append(key).append("\":\"").append(escapeJson(val != null ? val : "")).append('"');
    }

    private static void appendInt(StringBuilder sb, String key, int val) {
        sb.append('"').append(key).append("\":").append(val);
    }

    private static void appendBool(StringBuilder sb, String key, boolean val) {
        sb.append('"').append(key).append("\":").append(val);
    }

    private static String escapeJson(String s) {
        StringBuilder o = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\':
                    o.append("\\\\");
                    break;
                case '"':
                    o.append("\\\"");
                    break;
                case '\n':
                    o.append("\\n");
                    break;
                case '\r':
                    break;
                case '\t':
                    o.append("\\t");
                    break;
                default:
                    o.append(c);
            }
        }
        return o.toString();
    }

    /** Minimale JSON object → map (alle waarden als string, nummers/booleans ongewijzigd als tekst). */
    static Map<String, String> parseJsonObject(String raw) {
        Parser p = new Parser(raw.trim());
        if (!p.consumeIf('{')) {
            throw new IllegalArgumentException("Geen geldig JSON object (verwacht '{').");
        }
        Map<String, String> map = new LinkedHashMap<>();
        p.skipWs();
        if (p.consumeIf('}')) {
            return map;
        }
        while (true) {
            p.skipWs();
            String key = p.readString();
            p.skipWs();
            if (!p.consumeIf(':')) {
                throw new IllegalArgumentException("Verwacht ':' na key");
            }
            p.skipWs();
            String value = p.readValue();
            map.put(key, value);
            p.skipWs();
            if (p.consumeIf('}')) {
                break;
            }
            if (!p.consumeIf(',')) {
                throw new IllegalArgumentException("Verwacht ',' of '}'");
            }
        }
        return map;
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        void skipWs() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                    i++;
                } else {
                    break;
                }
            }
        }

        boolean consumeIf(char c) {
            if (i < s.length() && s.charAt(i) == c) {
                i++;
                return true;
            }
            return false;
        }

        String readString() {
            if (i >= s.length() || s.charAt(i) != '"') {
                throw new IllegalArgumentException("Verwacht string (\")");
            }
            i++;
            StringBuilder b = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return b.toString();
                }
                if (c == '\\' && i < s.length()) {
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"':
                        case '\\':
                        case '/':
                            b.append(e);
                            break;
                        case 'n':
                            b.append('\n');
                            break;
                        case 'r':
                            b.append('\r');
                            break;
                        case 't':
                            b.append('\t');
                            break;
                        case 'u':
                            if (i + 4 > s.length()) {
                                throw new IllegalArgumentException("Incomplete \\u escape");
                            }
                            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default:
                            b.append(e);
                    }
                } else {
                    b.append(c);
                }
            }
            throw new IllegalArgumentException("Niet-afgesloten string");
        }

        String readValue() {
            if (i >= s.length()) {
                throw new IllegalArgumentException("Onverwacht einde");
            }
            char c = s.charAt(i);
            if (c == '"') {
                return readString();
            }
            if (c == '-' || (c >= '0' && c <= '9')) {
                return readNumber();
            }
            int start = i;
            if (s.regionMatches(i, "true", 0, 4)) {
                i += 4;
                return "true";
            }
            if (s.regionMatches(i, "false", 0, 5)) {
                i += 5;
                return "false";
            }
            if (s.regionMatches(i, "null", 0, 4)) {
                i += 4;
                return "";
            }
            throw new IllegalArgumentException("Onbekende waarde op positie " + start);
        }

        String readNumber() {
            int start = i;
            if (s.charAt(i) == '-') {
                i++;
            }
            while (i < s.length()) {
                char ch = s.charAt(i);
                if ((ch >= '0' && ch <= '9') || ch == '.' || ch == 'e' || ch == 'E' || ch == '+' || ch == '-') {
                    i++;
                } else {
                    break;
                }
            }
            return s.substring(start, i);
        }
    }
}
