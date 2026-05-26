package com.combatbot;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Bulk-import van Storm {@code storm-accounts.json} (of gelijkwaardig): alleen velden die
 * de Combat Bot nodig heeft voor managed Jagex-accounts (character id, session, weergavenaam).
 * Duplicaten t.o.v. bestaande rijen en binnen hetzelfde bestand worden overgeslagen.
 */
public final class StormAccountsBulkImport {

    public static final class Result {
        public int added;
        public int skippedDuplicate;
        public int skippedInvalid;
        public int skippedNonJagex;
        /** Niet-null = bestand/structuur onbruikbaar (geen accounts toegevoegd in die call). */
        public String fatalError;

        public String summary() {
            if (fatalError != null) {
                return fatalError;
            }
            return added + " toegevoegd, " + skippedDuplicate + " overgeslagen (bestond al), "
                    + skippedInvalid + " overgeslagen (ongeldig)"
                    + (skippedNonJagex > 0 ? ", " + skippedNonJagex + " niet-jagex overgeslagen" : "");
        }
    }

    private StormAccountsBulkImport() {}

    /**
     * Parseert JSON en voegt nieuwe {@link ManagedJagexAccountsStore.ManagedJagexAccountRow}-s toe aan {@code accountRows}.
     * Verwacht top-level {@code "accounts": [ ... ]} met objecten zoals Storm exporteert.
     */
    public static Result importInto(List<ManagedJagexAccountsStore.ManagedJagexAccountRow> accountRows, String json) {
        Result out = new Result();
        if (json == null || json.trim().isEmpty()) {
            return out;
        }
        json = stripBom(json.trim());

        Set<String> existingCid = new HashSet<>();
        Set<String> existingSid = new HashSet<>();
        Set<String> existingDisplayNorm = new HashSet<>();
        for (ManagedJagexAccountsStore.ManagedJagexAccountRow r : accountRows) {
            if (r == null || r.isEmpty()) {
                continue;
            }
            addNorm(existingCid, r.characterId);
            addNorm(existingSid, r.sessionId);
            addNorm(existingDisplayNorm, JagexCredentialsHelper.normalizeDisplayNameForMatch(r.displayName));
        }

        Set<String> seenCid = new HashSet<>(existingCid);
        Set<String> seenSid = new HashSet<>(existingSid);

        JsonElement rootEl;
        try {
            // Geen JsonParser.parseString: die methode bestaat pas vanaf Gson 2.8.6; RuneLite kan een oudere Gson laden.
            rootEl = new Gson().fromJson(json, JsonElement.class);
        } catch (Exception e) {
            out.fatalError = "Ongeldige JSON: " + e.getMessage();
            return out;
        }
        JsonArray accounts;
        if (rootEl.isJsonArray()) {
            accounts = rootEl.getAsJsonArray();
        } else if (rootEl.isJsonObject()) {
            JsonObject root = rootEl.getAsJsonObject();
            accounts = root.getAsJsonArray("accounts");
            if (accounts == null) {
                accounts = root.getAsJsonArray("Accounts");
            }
        } else {
            out.fatalError = "JSON moet een object met \"accounts\" zijn, of een array van account-objecten.";
            return out;
        }
        if (accounts == null || accounts.size() == 0) {
            out.fatalError = "Geen \"accounts\"-array of array is leeg.";
            return out;
        }

        for (JsonElement el : accounts) {
            if (el == null || !el.isJsonObject()) {
                out.skippedInvalid++;
                continue;
            }
            JsonObject o = el.getAsJsonObject();

            String type = str(o, "type");
            if (type != null && !type.trim().isEmpty()
                    && !"jagex".equalsIgnoreCase(type.trim())) {
                out.skippedNonJagex++;
                continue;
            }

            String characterId = firstNonBlank(
                    str(o, "character_id"),
                    str(o, "characterId"),
                    str(o, "JX_CHARACTER_ID"));
            String sessionId = extractSessionToken(o);
            String rawDisplay = firstNonBlank(
                    str(o, "display_name"),
                    str(o, "displayName"),
                    str(o, "username"));

            if (sessionId == null || sessionId.isEmpty()) {
                out.skippedInvalid++;
                continue;
            }

            String cidKey = normKey(characterId);
            String sidKey = normKey(sessionId);
            if (sidKey.isEmpty()) {
                out.skippedInvalid++;
                continue;
            }
            if (!cidKey.isEmpty() && seenCid.contains(cidKey)) {
                out.skippedDuplicate++;
                continue;
            }
            if (seenSid.contains(sidKey)) {
                out.skippedDuplicate++;
                continue;
            }

            String display = resolveDisplayName(rawDisplay, characterId, sessionId);
            String dispNorm = JagexCredentialsHelper.normalizeDisplayNameForMatch(display);
            if (!dispNorm.isEmpty() && existingDisplayNorm.contains(dispNorm)) {
                out.skippedDuplicate++;
                continue;
            }

            ManagedJagexAccountsStore.ManagedJagexAccountRow row = new ManagedJagexAccountsStore.ManagedJagexAccountRow();
            row.displayName = display;
            row.characterId = characterId != null ? characterId : "";
            row.sessionId = sessionId;
            row.rotationEnabled = true;
            row.notes = "Storm import";

            String world = str(o, "world");
            if (world != null && world.trim().matches("\\d+")) {
                row.accountSwitchWorld = world.trim();
            }

            accountRows.add(row);
            if (!cidKey.isEmpty()) {
                seenCid.add(cidKey);
            }
            seenSid.add(sidKey);
            if (!dispNorm.isEmpty()) {
                existingDisplayNorm.add(dispNorm);
            }
            out.added++;
        }

        return out;
    }

    private static void addNorm(Set<String> set, String s) {
        String k = normKey(s);
        if (!k.isEmpty()) {
            set.add(k);
        }
    }

    private static String normKey(String s) {
        if (s == null) {
            return "";
        }
        return s.trim().toLowerCase(Locale.ROOT);
    }

    private static String firstNonBlank(String... parts) {
        if (parts == null) {
            return null;
        }
        for (String a : parts) {
            if (a != null && !a.trim().isEmpty()) {
                return a.trim();
            }
        }
        return null;
    }

    private static String stripBom(String s) {
        if (s != null && !s.isEmpty() && s.charAt(0) == '\uFEFF') {
            return s.substring(1);
        }
        return s;
    }

    /**
     * Storm / launchers gebruiken verschillende veldnamen.
     */
    private static String extractSessionToken(JsonObject o) {
        String flat = firstNonBlank(
                str(o, "session_id"),
                str(o, "sessionId"),
                str(o, "JX_SESSION_ID"),
                str(o, "JX_ACCESS_TOKEN"),
                str(o, "access_token"),
                str(o, "accessToken"),
                str(o, "storm_session"),
                str(o, "session"));
        if (flat != null && !flat.isEmpty()) {
            return flat;
        }
        if (o.has("credentials") && o.get("credentials").isJsonObject()) {
            JsonObject c = o.getAsJsonObject("credentials");
            return firstNonBlank(
                    str(c, "session_id"),
                    str(c, "sessionId"),
                    str(c, "JX_SESSION_ID"),
                    str(c, "JX_ACCESS_TOKEN"),
                    str(c, "access_token"));
        }
        return null;
    }

    private static String str(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
            return null;
        }
        JsonElement e = o.get(key);
        if (e.isJsonPrimitive()) {
            com.google.gson.JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isString()) {
                return p.getAsString();
            }
            if (p.isNumber()) {
                return p.getAsNumber().toString();
            }
            if (p.isBoolean()) {
                return p.getAsBoolean() ? "true" : "false";
            }
        }
        return null;
    }

    private static boolean isGenericDisplayLabel(String s) {
        if (s == null || s.trim().isEmpty()) {
            return true;
        }
        String t = s.trim().toLowerCase(Locale.ROOT);
        return "unknown".equals(t) || "onbekend".equals(t) || "?".equals(t) || "-".equals(t);
    }

    /**
     * Unieke weergavenaam: bij generieke Storm-labels gebruiken we character id of een kort session-suffix.
     */
    private static String resolveDisplayName(String rawDisplay, String characterId, String sessionId) {
        if (!isGenericDisplayLabel(rawDisplay)) {
            return rawDisplay.trim();
        }
        if (characterId != null && !characterId.trim().isEmpty()) {
            return "Char " + characterId.trim();
        }
        if (sessionId != null && sessionId.length() >= 8) {
            return "Sess " + sessionId.trim().substring(0, Math.min(12, sessionId.trim().length()));
        }
        return "Storm account";
    }
}
