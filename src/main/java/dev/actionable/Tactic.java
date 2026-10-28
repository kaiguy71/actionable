package dev.actionable;

import java.util.Locale;
import java.util.Optional;

/** High-level tactical orders Gemma may issue; each one is translated into Baritone calls by {@link TacticBook}. */
enum Tactic {
    DEFENSIVE_HOLD,
    RETREAT,
    APPROACH,
    MINE_COVER,
    HIGH_GROUND,
    OVERHANG_SHELTER,
    WATER_REFUGE,
    COVER_PILLAR,
    BREAK_LINE_OF_SIGHT,
    SHIELD_ADVANCE,
    RUSH,
    EQUIP_GOLD,
    KITE,
    KEEP_DISTANCE,
    SIDESTEP,
    STRAFE_COVER,
    INTERCEPT_PRIORITY,
    LEAVE_DEEP_DARK,
    CONTINUE;

    static Optional<Tactic> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (Tactic tactic : values()) {
            if (tactic.name().equals(normalized)) {
                return Optional.of(tactic);
            }
        }
        return Optional.empty();
    }
}
