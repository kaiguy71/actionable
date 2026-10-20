package dev.actionable;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class InteractionPolicyTest {
    @Test
    void normalModeRequiresFocusAndCapturedMouse() {
        assertTrue(InteractionPolicy.mayAttack(false, true, true, false, false));
        assertFalse(InteractionPolicy.mayAttack(false, false, true, false, false));
        assertFalse(InteractionPolicy.mayAttack(false, true, false, false, false));
    }

    @Test
    void backgroundModeAllowsUnfocusedUncapturedCombat() {
        assertTrue(InteractionPolicy.mayAttack(true, false, false, false, false));
        assertTrue(InteractionPolicy.mayAttack(true, true, false, false, false));
    }

    @Test
    void menusAndPausesPreventCombatInEitherMode() {
        for (boolean background : new boolean[] {false, true}) {
            assertFalse(InteractionPolicy.mayAttack(background, true, true, true, false));
            assertFalse(InteractionPolicy.mayAttack(background, true, true, false, true));
            assertFalse(InteractionPolicy.mayAttack(background, false, false, true, true));
        }
    }
}
