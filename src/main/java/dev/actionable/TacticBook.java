package dev.actionable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Mob knowledge for the tactical agent: which tactics are legal against each mob, the deterministic default
 * when Gemma gives no usable advice, and the translation of a tactic into Baritone commands and input flags (FR-2).
 */
final class TacticBook {
    static final double RETREAT_LEG = 16;
    static final double SIDESTEP_BLOCKS = 3;
    static final double STRAFE_BLOCKS = 4;
    static final double DEEP_DARK_ESCAPE = 220;
    static final double CHARGE_SAFE_DISTANCE = 8;
    static final double WITCH_RUSH_RANGE = 8;

    /** What the agent should do this tick besides pathing. */
    record Plan(Tactic tactic, List<String> baritone, boolean raiseShield, boolean sneak, boolean sprint,
            boolean equipGold, boolean cancelPathing) {
        static Plan hold() {
            return new Plan(Tactic.DEFENSIVE_HOLD, List.of("cancel"), true, false, false, false, true);
        }
    }

    private TacticBook() { }

    static boolean isTactical(String mob) {
        return !allowed(mob).isEmpty();
    }

    static Set<Tactic> allowed(String mob) {
        String id = bare(mob);
        return switch (id) {
            case "spider", "cave_spider" -> EnumSet.of(Tactic.HIGH_GROUND, Tactic.OVERHANG_SHELTER,
                    Tactic.WATER_REFUGE, Tactic.APPROACH, Tactic.RETREAT);
            case "enderman" -> EnumSet.of(Tactic.HIGH_GROUND, Tactic.OVERHANG_SHELTER, Tactic.WATER_REFUGE,
                    Tactic.RETREAT);
            case "shulker", "warden" -> EnumSet.of(Tactic.COVER_PILLAR, Tactic.BREAK_LINE_OF_SIGHT,
                    Tactic.LEAVE_DEEP_DARK, Tactic.RETREAT);
            case "skeleton", "stray", "bogged" -> EnumSet.of(Tactic.SHIELD_ADVANCE, Tactic.APPROACH, Tactic.RETREAT);
            case "witch" -> EnumSet.of(Tactic.RUSH, Tactic.SHIELD_ADVANCE, Tactic.APPROACH);
            case "piglin" -> EnumSet.of(Tactic.EQUIP_GOLD, Tactic.CONTINUE, Tactic.RETREAT);
            case "piglin_brute" -> EnumSet.of(Tactic.KITE, Tactic.EQUIP_GOLD, Tactic.RETREAT);
            case "hoglin", "zoglin" -> EnumSet.of(Tactic.SIDESTEP, Tactic.KEEP_DISTANCE, Tactic.RETREAT);
            case "blaze", "ghast" -> EnumSet.of(Tactic.STRAFE_COVER, Tactic.COVER_PILLAR, Tactic.APPROACH,
                    Tactic.RETREAT);
            case "pillager", "vindicator", "ravager", "evoker" -> EnumSet.of(Tactic.INTERCEPT_PRIORITY,
                    Tactic.SHIELD_ADVANCE, Tactic.RETREAT);
            case "sculk_shrieker" -> EnumSet.of(Tactic.LEAVE_DEEP_DARK);
            default -> EnumSet.noneOf(Tactic.class);
        };
    }

    /** Lower is hit first; raid casters and archers before brutes (FR-9). */
    static int raidPriority(String mob) {
        return switch (bare(mob)) {
            case "evoker" -> 0;
            case "pillager" -> 1;
            case "vindicator" -> 2;
            case "ravager" -> 3;
            default -> 4;
        };
    }

    /** The rule-based answer used before Gemma replies, and to veto replies that are not legal for the mob. */
    static Tactic defaultFor(TacticalState state) {
        String id = bare(state.mob());
        return switch (id) {
            case "spider", "cave_spider" -> Tactic.OVERHANG_SHELTER;
            case "enderman" -> state.waterNearby() ? Tactic.WATER_REFUGE : Tactic.OVERHANG_SHELTER;
            case "shulker" -> Tactic.COVER_PILLAR;
            case "sculk_shrieker" -> Tactic.LEAVE_DEEP_DARK;
            case "warden" -> state.mobCharging() ? Tactic.BREAK_LINE_OF_SIGHT : Tactic.LEAVE_DEEP_DARK;
            case "skeleton", "stray", "bogged" -> Tactic.SHIELD_ADVANCE;
            case "witch" -> Tactic.RUSH;
            case "piglin" -> state.hasGoldArmor() ? Tactic.CONTINUE : Tactic.EQUIP_GOLD;
            case "piglin_brute" -> Tactic.KITE;
            case "hoglin", "zoglin" -> state.mobCharging() ? Tactic.SIDESTEP : Tactic.KEEP_DISTANCE;
            case "blaze", "ghast" -> Tactic.STRAFE_COVER;
            case "pillager", "vindicator", "ravager", "evoker" -> Tactic.INTERCEPT_PRIORITY;
            default -> Tactic.DEFENSIVE_HOLD;
        };
    }

    /** Hard overrides that no model answer may talk the agent out of. */
    static Tactic enforce(TacticalState state, Tactic proposed) {
        String id = bare(state.mob());
        if (id.equals("witch") && (state.mobDrinking() || state.distance() <= WITCH_RUSH_RANGE)) {
            return Tactic.RUSH;
        }
        if (id.equals("hoglin") && state.mobCharging()) {
            return Tactic.SIDESTEP;
        }
        if (id.equals("warden") && state.mobCharging()) {
            return Tactic.BREAK_LINE_OF_SIGHT;
        }
        if (id.equals("piglin") && !state.hasGoldArmor() && proposed != Tactic.RETREAT) {
            return Tactic.EQUIP_GOLD;
        }
        if (proposed == Tactic.DEFENSIVE_HOLD) {
            return proposed;
        }
        return allowed(state.mob()).contains(proposed) ? proposed : defaultFor(state);
    }

    static Plan translate(TacticalState state, Tactic tactic) {
        double awayX = state.px() - state.mx();
        double awayZ = state.pz() - state.mz();
        double length = Math.hypot(awayX, awayZ);
        if (length < 1e-6) {
            awayX = 1;
            awayZ = 0;
            length = 1;
        }
        awayX /= length;
        awayZ /= length;
        double sideX = -awayZ;
        double sideZ = awayX;
        List<String> commands = new ArrayList<>();
        boolean shield = false;
        boolean sneak = false;
        boolean sprint = false;
        boolean gold = false;
        boolean cancel = false;
        switch (tactic) {
            case DEFENSIVE_HOLD -> {
                commands.add("cancel");
                shield = true;
                cancel = true;
            }
            case RETREAT -> commands.add(gotoAway(state, awayX, awayZ, RETREAT_LEG));
            case APPROACH -> commands.add(gotoPoint(state.mx(), state.my(), state.mz()));
            case MINE_COVER -> commands.add("mine stone cobblestone dirt");
            case HIGH_GROUND -> commands.add(gotoPoint(state.px() + awayX * 2, state.py() + 3, state.pz() + awayZ * 2));
            case OVERHANG_SHELTER -> commands.add("tunnel 2 1 2");
            case WATER_REFUGE -> commands.add("goto water");
            case COVER_PILLAR -> commands.add(coverPoint(state, awayX, awayZ));
            case BREAK_LINE_OF_SIGHT -> {
                commands.add(coverPoint(state, awayX, awayZ));
                sneak = state.shriekerNearby();
            }
            case SHIELD_ADVANCE -> {
                commands.add(gotoPoint(state.mx(), state.my(), state.mz()));
                shield = state.hasShield();
            }
            case RUSH -> {
                commands.add("cancel");
                cancel = true;
                sprint = true;
            }
            case EQUIP_GOLD -> gold = true;
            case KITE -> commands.add(gotoAway(state, awayX, awayZ, 3));
            case KEEP_DISTANCE -> {
                if (state.distance() < CHARGE_SAFE_DISTANCE) {
                    commands.add(gotoAway(state, awayX, awayZ, CHARGE_SAFE_DISTANCE - state.distance() + 2));
                }
            }
            case SIDESTEP -> commands.add(gotoPoint(state.px() + sideX * SIDESTEP_BLOCKS, state.py(),
                    state.pz() + sideZ * SIDESTEP_BLOCKS));
            case STRAFE_COVER -> {
                if (state.cover() != null) {
                    commands.add(coverPoint(state, awayX, awayZ));
                } else {
                    commands.add(gotoPoint(state.px() + sideX * STRAFE_BLOCKS, state.py(),
                            state.pz() + sideZ * STRAFE_BLOCKS));
                }
            }
            case INTERCEPT_PRIORITY -> {
                commands.add(gotoPoint(state.mx(), state.my(), state.mz()));
                shield = state.hasShield();
            }
            case LEAVE_DEEP_DARK -> {
                commands.add(gotoAway(state, awayX, awayZ, DEEP_DARK_ESCAPE));
                sneak = true;
            }
            case CONTINUE -> { }
        }
        return new Plan(tactic, List.copyOf(commands), shield, sneak, sprint, gold, cancel);
    }

    private static String coverPoint(TacticalState state, double awayX, double awayZ) {
        int[] cover = state.cover();
        if (cover == null) {
            // No solid column nearby: have Baritone pillar up two blocks on the side away from the mob.
            return gotoPoint(state.px() + awayX, state.py() + 2, state.pz() + awayZ);
        }
        double dx = cover[0] + 0.5 - state.mx();
        double dz = cover[2] + 0.5 - state.mz();
        double length = Math.max(1e-6, Math.hypot(dx, dz));
        return gotoPoint(cover[0] + 0.5 + dx / length * 1.5, cover[1], cover[2] + 0.5 + dz / length * 1.5);
    }

    private static String gotoAway(TacticalState state, double awayX, double awayZ, double blocks) {
        return "goto " + Math.round(state.px() + awayX * blocks) + " " + Math.round(state.py()) + " "
                + Math.round(state.pz() + awayZ * blocks);
    }

    private static String gotoPoint(double x, double y, double z) {
        return "goto " + (int) Math.floor(x) + " " + (int) Math.floor(y) + " " + (int) Math.floor(z);
    }

    private static String bare(String mob) {
        if (mob == null) {
            return "";
        }
        return mob.startsWith("minecraft:") ? mob.substring("minecraft:".length()) : mob;
    }
}
