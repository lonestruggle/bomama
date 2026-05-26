package com.combatbot;

import net.runelite.api.coords.WorldPoint;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Per-account gedrag per center-tile (drop/bank, FM, cook). Formaat blob: één regel per tile
 * {@code x:y:z|drop=0|extra=1} (drop: 1=droppen 0=banken; extra: WC=FM, Fishing=Cook).
 * Ontbrekende regel = volg globale skill-instellingen.
 */
public final class AccountCenterBehaviorStore {

    public enum SkillKind {
        WOODCUTTING,
        MINING,
        FISHING
    }

    public static final class CenterBehavior {
        /** true = droppen, false = banken. */
        public final boolean drop;
        /** WC: firemaking; Fishing: cooking. */
        public final boolean extra;
        public final boolean explicit;

        public CenterBehavior(boolean drop, boolean extra, boolean explicit) {
            this.drop = drop;
            this.extra = extra;
            this.explicit = explicit;
        }

        public static CenterBehavior inherit() {
            return new CenterBehavior(false, false, false);
        }
    }

    private AccountCenterBehaviorStore() {
    }

    public static String tileKey(WorldPoint p) {
        if (p == null) {
            return "";
        }
        return p.getX() + ":" + p.getY() + ":" + p.getPlane();
    }

    public static String tileKey(CenterManager.Center c) {
        return c != null && c.point != null ? tileKey(c.point) : "";
    }

    public static Map<String, CenterBehavior> parse(String blob) {
        Map<String, CenterBehavior> out = new LinkedHashMap<>();
        if (blob == null || blob.trim().isEmpty()) {
            return out;
        }
        for (String line : blob.split("\\n")) {
            if (line == null) {
                continue;
            }
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }
            String[] parts = t.split("\\|", -1);
            if (parts.length < 1 || parts[0].trim().isEmpty()) {
                continue;
            }
            String key = parts[0].trim();
            boolean drop = true;
            boolean extra = false;
            for (int i = 1; i < parts.length; i++) {
                String kv = parts[i].trim().toLowerCase(Locale.ROOT);
                if (kv.startsWith("drop=")) {
                    drop = "1".equals(kv.substring(5).trim()) || "true".equals(kv.substring(5).trim());
                } else if (kv.startsWith("extra=")) {
                    extra = "1".equals(kv.substring(6).trim()) || "true".equals(kv.substring(6).trim());
                }
            }
            out.put(key, new CenterBehavior(drop, extra, true));
        }
        return out;
    }

    public static String serialize(Map<String, CenterBehavior> map) {
        if (map == null || map.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, CenterBehavior> e : map.entrySet()) {
            if (e.getKey() == null || e.getKey().isEmpty() || e.getValue() == null || !e.getValue().explicit) {
                continue;
            }
            CenterBehavior b = e.getValue();
            sb.append(e.getKey())
                    .append("|drop=").append(b.drop ? "1" : "0")
                    .append("|extra=").append(b.extra ? "1" : "0")
                    .append('\n');
        }
        return sb.toString().trim();
    }

    public static CenterBehavior resolve(SkillKind kind, WorldPoint center, String blob, CombatBotConfig config) {
        if (config == null || center == null) {
            return CenterBehavior.inherit();
        }
        CenterBehavior ovr = parse(blob).get(tileKey(center));
        if (ovr == null || !ovr.explicit) {
            return globalDefaults(kind, config);
        }
        return ovr;
    }

    public static CenterBehavior globalDefaults(SkillKind kind, CombatBotConfig config) {
        switch (kind) {
            case WOODCUTTING:
                return new CenterBehavior(config.wcDropLogs(), config.wcFiremaking(), false);
            case MINING:
                return new CenterBehavior(config.miningDropOre(), false, false);
            case FISHING:
                return new CenterBehavior(config.fishingDropFish(), config.fishingCookEnabled(), false);
            default:
                return CenterBehavior.inherit();
        }
    }

    public static CenterBehavior forCenter(SkillKind kind, WorldPoint center, CombatBotConfig config) {
        if (config == null) {
            return CenterBehavior.inherit();
        }
        ManagedJagexAccountsStore.ManagedJagexAccountRow row =
                ManagedJagexAccountsStore.findRowForDisplayName(config, localRsnOrNull());
        if (row == null) {
            return globalDefaults(kind, config);
        }
        String blob;
        switch (kind) {
            case WOODCUTTING:
                blob = row.wcCenterBehaviorsBlob;
                break;
            case MINING:
                blob = row.miningCenterBehaviorsBlob;
                break;
            case FISHING:
                blob = row.fishingCenterBehaviorsBlob;
                break;
            default:
                blob = "";
        }
        return resolve(kind, center, blob, config);
    }

    private static String localRsnOrNull() {
        try {
            net.storm.api.domain.actors.IPlayer local = net.storm.sdk.entities.Players.getLocal();
            if (local == null || local.getName() == null) {
                return null;
            }
            return net.runelite.client.util.Text.removeTags(local.getName()).trim();
        } catch (Throwable t) {
            return null;
        }
    }
}
