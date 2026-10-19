package dev.actionable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Map;
import org.junit.jupiter.api.Test;

class InventoryLayoutTest {
    @Test
    void toolsFirstFoodInSevenWeaponInEight() {
        Map<Integer, String> plan = InventoryLayout.plan(Map.of("minecraft:wooden_pickaxe", 1,
                "minecraft:stone_pickaxe", 1, "minecraft:wooden_axe", 1, "minecraft:stone_sword", 1,
                "minecraft:wooden_sword", 1, "minecraft:mutton", 5, "minecraft:sweet_berries", 9, "minecraft:dirt", 20));
        assertEquals("minecraft:stone_pickaxe", plan.get(InventoryLayout.PICKAXE_SLOT), "best pickaxe");
        assertEquals("minecraft:wooden_axe", plan.get(InventoryLayout.AXE_SLOT), "an axe, not the pickaxe");
        assertEquals("minecraft:mutton", plan.get(InventoryLayout.FOOD_SLOT));
        assertEquals("minecraft:stone_sword", plan.get(InventoryLayout.WEAPON_SLOT), "best sword");
        assertFalse(plan.containsKey(InventoryLayout.SHOVEL_SLOT), "nothing planned for tools not held");
    }

    @Test
    void rawChickenStillGetsTheFoodSlotWhenItIsAllThereIs() {
        assertEquals("minecraft:chicken", InventoryLayout.plan(Map.of("minecraft:chicken", 2)).get(InventoryLayout.FOOD_SLOT));
        assertEquals("minecraft:cooked_beef", InventoryLayout.plan(Map.of("minecraft:chicken", 2,
                "minecraft:cooked_beef", 1)).get(InventoryLayout.FOOD_SLOT));
    }

    @Test
    void emptyInventoryPlansNothing() {
        assertEquals(Map.of(), InventoryLayout.plan(Map.of("minecraft:dirt", 3)));
    }
}
