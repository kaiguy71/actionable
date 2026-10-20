package dev.actionable;

import java.util.Comparator;
import java.util.List;

/** Observable threats and fight/escape decisions; no model or Minecraft dependencies. */
final class ThreatRules {
    enum Mode { NONE, FIGHT, FLEE }
    record Threat(int entityId, String kind, double x, double y, double z, double distance,
            boolean reachable, boolean visible, boolean explosive, boolean targeting, boolean closing,
            boolean primed, double verticalGap) {
        Threat(int entityId, String kind, double x, double y, double z, double distance,
                boolean reachable, boolean visible, boolean explosive) {
            this(entityId, kind, x, y, z, distance, reachable, visible, explosive, false, false, false, 0);
        }
    }
    record Decision(Mode mode, Threat threat, String reason) { }
    private ThreatRules() { }

    static boolean hostile(String kind, boolean enemy, boolean targeting, boolean aggressive) {
        boolean neutral = switch (kind) {
            case "minecraft:enderman", "minecraft:piglin", "minecraft:zombified_piglin",
                    "minecraft:spider", "minecraft:cave_spider" -> true;
            default -> false;
        };
        return targeting || (enemy && (!neutral || aggressive));
    }

    static boolean avoidMelee(String kind) {
        return switch (kind) {
            case "minecraft:creeper", "minecraft:warden", "minecraft:wither", "minecraft:ender_dragon",
                    "minecraft:player" -> true;
            default -> false;
        };
    }

    static Decision choose(float health, List<Threat> threats) {
        List<Threat> observed = threats.stream().filter(t -> t.distance() <= 16)
                .sorted(Comparator.comparingDouble(Threat::distance)).toList();
        List<Threat> nearby = observed.stream().filter(ThreatRules::immediate).toList();
        if (nearby.isEmpty()) return new Decision(Mode.NONE,
                observed.stream().filter(Threat::visible).findFirst().orElse(null),
                "watch only: no immediate attack evidence or close threat");
        Threat explosive = nearby.stream().filter(t -> t.explosive() || avoidMelee(t.kind())).findFirst().orElse(null);
        if (explosive != null) return new Decision(Mode.FLEE, explosive,
                explosive.explosive() ? "incoming projectile, primed explosive or environmental damage"
                        : "close or attacking creeper, boss or player; do not melee");
        Threat nearest = nearby.get(0);
        if (health <= 6) return new Decision(Mode.FLEE, nearest, "low health with a nearby threat");
        if (nearby.stream().filter(t -> t.distance() <= 8).count() > 2)
            return new Decision(Mode.FLEE, nearest, "outnumbered at close range");
        Threat reachable = nearby.stream().filter(t -> t.reachable() && !avoidMelee(t.kind())).findFirst().orElse(null);
        if (reachable != null) return new Decision(Mode.FIGHT, reachable, "hostile in melee reach");
        return new Decision(Mode.FLEE, nearest, "hostile outside safe melee reach");
    }

    /** Visibility alone is observation, not proof of an immediate attack. */
    private static boolean immediate(Threat t) {
        if (t.explosive()) return t.distance() <= 12;
        // Actual targets/damage sources still matter briefly after disappearing behind cover.
        if (t.targeting()) return true;
        // Thin cover is not reliable protection from a nearby active fuse.
        if (t.kind().equals("minecraft:creeper") && t.primed() && t.distance() <= 6) return true;
        if (!t.visible()) return false;
        if (t.kind().equals("minecraft:creeper")) {
            return t.primed() && t.distance() <= 12
                    || Math.abs(t.verticalGap()) <= 4 && (t.distance() <= 7 || t.closing() && t.distance() <= 10);
        }
        if (t.kind().equals("minecraft:warden") || t.kind().equals("minecraft:wither")
                || t.kind().equals("minecraft:ender_dragon")) return true;
        boolean ranged = switch (t.kind()) {
            case "minecraft:skeleton", "minecraft:stray", "minecraft:bogged", "minecraft:pillager",
                    "minecraft:witch", "minecraft:blaze", "minecraft:ghast", "minecraft:shulker",
                    "minecraft:guardian", "minecraft:elder_guardian", "minecraft:evoker" -> true;
            default -> false;
        };
        return t.reachable() || Math.abs(t.verticalGap()) <= 4 && (t.distance() <= 4
                || t.closing() && t.distance() <= 10) || ranged && t.distance() <= 10;
    }

    /** Entity motion toward the player; independent of the player's own movement. */
    static boolean closing(double dx, double dz, double vx, double vz) {
        double distance = Math.sqrt(dx * dx + dz * dz);
        return distance > 0.01 && (dx * vx + dz * vz) / distance > 0.04;
    }

    /** Relative position from projectile to player; only react to a near-future collision course. */
    static boolean approaching(double dx, double dy, double dz, double vx, double vy, double vz) {
        double speed = vx * vx + vy * vy + vz * vz;
        if (speed < 0.01) return false;
        double ticks = (dx * vx + dy * vy + dz * vz) / speed;
        if (ticks <= 0 || ticks > 20) return false;
        double x = dx - ticks * vx, y = dy - ticks * vy, z = dz - ticks * vz;
        return x * x + y * y + z * z <= 4;
    }
}
