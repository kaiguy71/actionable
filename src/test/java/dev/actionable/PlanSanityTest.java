package dev.actionable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PlanSanityTest {
    @Test
    void neverRejectsAGameFeature() {
        for (String goal : new String[] {"craft a wooden pickaxe", "Smelt the iron ore",
                "cook the beef", "brew a potion", "enchant the sword", "trade with the villager",
                "build a shelter", "travel to the Nether", "mine 4 minecraft:crafting_table"}) {
            assertTrue(PlanSanity.usable(goal), goal);
            assertEquals(List.of(goal), PlanSanity.sanitizeSteps(List.of(goal)), goal);
        }
    }

    @Test
    void blankTextIsNotUsable() {
        assertFalse(PlanSanity.usable("  "));
        assertFalse(PlanSanity.usable(null));
    }

    @Test
    void clampsLargeQuantities() {
        assertEquals("mine 64 minecraft:stone", PlanSanity.clampQuantity("mine 500 minecraft:stone"));
        assertEquals("mine 12 minecraft:coal_ore", PlanSanity.clampQuantity("mine 12 minecraft:coal_ore"));
        assertEquals("explore", PlanSanity.clampQuantity("explore"));
        assertEquals("gather 64 logs, mine 8 stone", PlanSanity.clampQuantity("gather 200 logs, mine 8 stone"));
    }

    @Test
    void sanitizeDropsBlankAndBackToBackDuplicateSteps() {
        List<String> cleaned = PlanSanity.sanitizeSteps(List.of(
                "mine 4 minecraft:oak_log",
                "mine  4  minecraft:oak_log",
                "  ",
                "craft 2 minecraft:stick from 2 minecraft:dark_oak_planks",
                "mine 900 minecraft:cobblestone",
                "mine 64 minecraft:cobblestone"));
        assertEquals(List.of("mine 4 minecraft:oak_log",
                "craft 2 minecraft:stick from 2 minecraft:dark_oak_planks",
                "mine 64 minecraft:cobblestone"), cleaned);
    }

    @Test
    void sameStepLaterInThePlanIsKept() {
        List<String> steps = List.of("mine 4 minecraft:oak_log", "craft 4 minecraft:oak_planks",
                "mine 4 minecraft:oak_log");
        assertEquals(steps, PlanSanity.sanitizeSteps(steps));
    }

    @Test
    void sanitizeCanReturnEmpty() {
        assertTrue(PlanSanity.sanitizeSteps(List.of("", "  ")).isEmpty());
    }
}
