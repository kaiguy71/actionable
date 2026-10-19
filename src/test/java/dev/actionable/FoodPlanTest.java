package dev.actionable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FoodPlanTest {
    @Test
    void stockCountsHungerPointsCarried() {
        assertEquals(0, FoodPlan.stock(Map.of("minecraft:oak_log", 10)));
        assertEquals(8 * 2 + 3, FoodPlan.stock(Map.of("minecraft:cooked_beef", 2, "minecraft:porkchop", 1)));
        assertTrue(FoodPlan.needFood(Map.of("minecraft:porkchop", 2)));
        assertFalse(FoodPlan.needFood(Map.of("minecraft:cooked_porkchop", 3)));
    }

    @Test
    void pigsCowsChickensAndSheepAreFood() {
        assertEquals("minecraft:porkchop", FoodPlan.ANIMAL_FOOD.get("minecraft:pig"));
        assertEquals("minecraft:beef", FoodPlan.ANIMAL_FOOD.get("minecraft:cow"));
        assertTrue(FoodPlan.ANIMAL_FOOD.containsKey("minecraft:chicken"));
        assertTrue(FoodPlan.ANIMAL_FOOD.containsKey("minecraft:sheep"));
        assertFalse(FoodPlan.ANIMAL_FOOD.containsKey("minecraft:horse"));
        assertTrue(FoodPlan.isFood("minecraft:porkchop"));
        assertFalse(FoodPlan.isFood("minecraft:leather"));
    }

    @Test
    void eatsWhenHungryOrWhenHurtAndNotFull() {
        assertTrue(FoodPlan.shouldEat(14, 20, 20), "hungry");
        assertFalse(FoodPlan.shouldEat(18, 20, 20), "full enough and healthy");
        assertTrue(FoodPlan.shouldEat(17, 12, 20), "hurt: eat so health regenerates");
        assertFalse(FoodPlan.shouldEat(20, 5, 20), "cannot eat on a full bar");
    }

    @Test
    void picksTheMostFillingFoodAndAvoidsRawChicken() {
        assertEquals(Optional.of("minecraft:cooked_porkchop"),
                FoodPlan.pick(Map.of("minecraft:porkchop", 3, "minecraft:cooked_porkchop", 1), 12));
        assertEquals(Optional.of("minecraft:porkchop"),
                FoodPlan.pick(Map.of("minecraft:porkchop", 3, "minecraft:chicken", 2), 12));
        assertEquals(Optional.empty(), FoodPlan.pick(Map.of("minecraft:chicken", 2), 12), "raw chicken can poison");
        assertEquals(Optional.of("minecraft:chicken"), FoodPlan.pick(Map.of("minecraft:chicken", 2), 4), "unless starving");
    }

    @Test
    void theReportMentionsFood() {
        NeedsPlanner.Situation day = new NeedsPlanner.Situation(new ProgressPlanner.Facts(true, true, true, true,
                false, true, true, false), false, false, true);
        String report = NeedsPlanner.report(Map.of("minecraft:porkchop", 1), day);
        assertTrue(report.contains("food: carrying 3/24 hunger points; kill pigs, cows or chickens"), report);
    }
}
