package dev.actionable;

import static dev.actionable.StepCheck.Verdict.DONE;
import static dev.actionable.StepCheck.Verdict.NOT_DONE;
import static dev.actionable.StepCheck.Verdict.UNKNOWN;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class StepCheckTest {
    private static final Map<String, Integer> INVENTORY = Map.of(
            "minecraft:oak_log", 6, "minecraft:cobblestone", 20, "minecraft:coal", 3,
            "minecraft:raw_iron", 12);

    @Test
    void satisfiedWhenInventoryHoldsEnough() {
        assertEquals(DONE, StepCheck.check("mine 4 minecraft:oak_log", INVENTORY));
        assertEquals(DONE, StepCheck.check("Mine 6 oak_log", INVENTORY));
        assertEquals(DONE, StepCheck.check("get 20 cobblestone", INVENTORY));
    }

    @Test
    void notSatisfiedBelowQuantity() {
        assertEquals(NOT_DONE, StepCheck.check("mine 7 minecraft:oak_log", INVENTORY));
        assertEquals(NOT_DONE, StepCheck.check("mine 5 minecraft:diamond_ore", INVENTORY));
    }

    @Test
    void oresAndStoneMapToTheirDrops() {
        assertEquals(DONE, StepCheck.check("mine 12 minecraft:iron_ore", INVENTORY));
        assertEquals(NOT_DONE, StepCheck.check("mine 4 minecraft:coal_ore", INVENTORY));
        assertEquals(DONE, StepCheck.check("mine 16 minecraft:stone", INVENTORY));
    }

    @Test
    void toleratesPluralAndFiller() {
        assertEquals(DONE, StepCheck.check("collect at least 4 of the oak_logs", INVENTORY));
        assertEquals(DONE, StepCheck.check("get 4 minecraft:oak_log from the nearby trees", INVENTORY));
    }

    @Test
    void unreadableStepsAreUnknown() {
        assertEquals(UNKNOWN, StepCheck.check("goto the clearing at 10 70 -5", INVENTORY));
        assertEquals(UNKNOWN, StepCheck.check("explore to find a cave", INVENTORY));
        assertEquals(UNKNOWN, StepCheck.check("mine 0 minecraft:oak_log", INVENTORY));
    }
}
