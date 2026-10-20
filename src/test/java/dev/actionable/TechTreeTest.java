package dev.actionable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TechTreeTest {
    @Test
    void briefingKnowledgeMatchesImplementedTableAndFurnaceBehavior() {
        assertTrue(TechTree.useOf(TechTree.CRAFTING_TABLE).orElseThrow().contains("leave it standing"));
        assertTrue(TechTree.useOf("minecraft:furnace").orElseThrow().contains("smelt in house furnace"));
        assertFalse(TechTree.useOf("minecraft:raw_iron").orElseThrow().contains("not automated"));
    }

    @Test
    void cobblestoneIsMinedFromStoneNotFromPlacedCobblestone() {
        assertEquals("minecraft:stone", TechTree.blockToMine("minecraft:cobblestone"));
        assertEquals("minecraft:stone", TechTree.blockToMine("minecraft:stone"));
        assertEquals("minecraft:coal_ore", TechTree.blockToMine("minecraft:coal"));
        assertEquals("minecraft:iron_ore", TechTree.blockToMine("minecraft:raw_iron"));
        assertEquals("minecraft:birch_log", TechTree.blockToMine("minecraft:birch_log"));
        assertEquals("minecraft:dirt", TechTree.blockToMine("minecraft:dirt"));
    }

    @Test
    void stoneTypeBlocksNeedTheRightPickaxe() {
        assertEquals(TechTree.Tier.WOOD, TechTree.toolFor("minecraft:stone"));
        assertEquals(TechTree.Tier.WOOD, TechTree.toolFor("minecraft:coal_ore"));
        assertEquals(TechTree.Tier.STONE, TechTree.toolFor("minecraft:iron_ore"));
        assertEquals(TechTree.Tier.IRON, TechTree.toolFor("minecraft:diamond_ore"));
        assertEquals(TechTree.Tier.NONE, TechTree.toolFor("minecraft:oak_log"));
    }

    @Test
    void pickaxeTierIsTheBestHeld() {
        assertEquals(TechTree.Tier.NONE, TechTree.pickaxeTier(Map.of("minecraft:oak_log", 3)));
        assertEquals(TechTree.Tier.STONE, TechTree.pickaxeTier(
                Map.of("minecraft:wooden_pickaxe", 1, "minecraft:stone_pickaxe", 1)));
        assertEquals(TechTree.Tier.WOOD, TechTree.pickaxeTier(Map.of("minecraft:golden_pickaxe", 1)));
    }

    @Test
    void woodVariantsCountTogether() {
        Map<String, Integer> inventory = Map.of("minecraft:oak_log", 2, "minecraft:birch_log", 3,
                "minecraft:spruce_planks", 4, "minecraft:cobblestone", 5);
        assertEquals(5, TechTree.held(inventory, "minecraft:oak_log"));
        assertEquals(4, TechTree.held(inventory, "minecraft:oak_planks"));
        assertEquals(5, TechTree.held(inventory, "minecraft:stone"), "stone counts its cobblestone drop");
    }

    @Test
    void planksRecipeFollowsTheLogType() {
        TechTree.Recipe recipe = TechTree.recipeFor("minecraft:birch_planks").orElseThrow();
        assertEquals(4, recipe.yield());
        assertEquals(Map.of(TechTree.LOGS, 1), recipe.inputs());
        assertEquals("minecraft:birch_planks", TechTree.planksFor("minecraft:birch_log"));
        assertEquals("minecraft:oak_planks", TechTree.planksFor("minecraft:stripped_oak_log"));
        assertEquals("minecraft:crimson_planks", TechTree.planksFor("minecraft:crimson_stem"));
        assertEquals("minecraft:crimson_stem", TechTree.logFor("minecraft:crimson_planks"));
        assertEquals("minecraft:oak_log", TechTree.logFor("minecraft:oak_planks"));
    }

    @Test
    void recipeInputsAndTableRequirement() {
        TechTree.Recipe pickaxe = TechTree.recipeFor("minecraft:wooden_pickaxe").orElseThrow();
        assertEquals(Map.of(TechTree.PLANKS, 3, "minecraft:stick", 2), pickaxe.inputs());
        assertTrue(pickaxe.needsTable());
        assertFalse(TechTree.recipeFor("minecraft:crafting_table").orElseThrow().needsTable());
        assertFalse(TechTree.recipeFor("minecraft:stick").orElseThrow().needsTable());
        assertFalse(TechTree.recipeFor("minecraft:torch").orElseThrow().needsTable());
        assertTrue(TechTree.recipeFor("minecraft:furnace").orElseThrow().needsTable());
        assertEquals(8, TechTree.recipeFor("minecraft:furnace").orElseThrow().inputs().get(TechTree.STONE_MATERIAL));
    }

    @Test
    void layoutMapsPatternCellsToGridSlots() {
        TechTree.Recipe pickaxe = TechTree.recipeFor("minecraft:wooden_pickaxe").orElseThrow();
        // 3x3 table grid: slots 1-9 row-major after the result slot.
        assertEquals(Map.of(1, TechTree.PLANKS, 2, TechTree.PLANKS, 3, TechTree.PLANKS,
                5, "minecraft:stick", 8, "minecraft:stick"), pickaxe.layout(3));
        TechTree.Recipe table = TechTree.recipeFor("minecraft:crafting_table").orElseThrow();
        // 2x2 inventory grid: slots 1-4.
        assertEquals(Map.of(1, TechTree.PLANKS, 2, TechTree.PLANKS, 3, TechTree.PLANKS, 4, TechTree.PLANKS),
                table.layout(2));
        assertEquals(Map.of(1, TechTree.COALS, 3, "minecraft:stick"),
                TechTree.recipeFor("minecraft:torch").orElseThrow().layout(2));
    }

    @Test
    void groupsMatchTheirMembersOnly() {
        assertTrue(TechTree.matches(TechTree.LOGS, "minecraft:dark_oak_log"));
        assertTrue(TechTree.matches(TechTree.LOGS, "minecraft:warped_stem"));
        assertFalse(TechTree.matches(TechTree.LOGS, "minecraft:oak_planks"));
        assertTrue(TechTree.matches(TechTree.STONE_MATERIAL, "minecraft:cobbled_deepslate"));
        assertFalse(TechTree.matches(TechTree.STONE_MATERIAL, "minecraft:stone"));
        assertTrue(TechTree.matches(TechTree.COALS, "minecraft:charcoal"));
    }

    @Test
    void everyHeldProgressionItemHasAUse() {
        for (String item : List.of("minecraft:oak_log", "minecraft:acacia_planks", "minecraft:stick",
                "minecraft:cobblestone", "minecraft:coal", "minecraft:charcoal", "minecraft:torch",
                "minecraft:raw_iron", "minecraft:wooden_pickaxe")) {
            assertTrue(TechTree.useOf(item).isPresent(), item);
        }
        assertTrue(TechTree.useOf("minecraft:poppy").isEmpty());
    }

    @Test
    void craftableNowReflectsInventoryAndTable() {
        Map<String, Integer> inventory = Map.of("minecraft:oak_planks", 5, "minecraft:stick", 2);
        List<String> withoutTable = TechTree.craftableNow(inventory, false);
        assertTrue(withoutTable.contains("minecraft:crafting_table"));
        assertTrue(withoutTable.contains("minecraft:stick"));
        assertTrue(withoutTable.contains("minecraft:wooden_pickaxe [needs a crafting table]"));
        assertTrue(TechTree.craftableNow(inventory, true).contains("minecraft:wooden_pickaxe [at table]"));
        assertFalse(withoutTable.stream().anyMatch(entry -> entry.startsWith("minecraft:stone_pickaxe")));
        assertTrue(TechTree.craftableNow(Map.of("minecraft:birch_log", 1), false).contains("minecraft:birch_planks"));
    }

    @Test
    void cannotMineYetListsBlocksAboveTheCurrentTier() {
        List<String> bare = TechTree.cannotMineYet(Map.of());
        assertTrue(bare.contains("minecraft:stone (needs minecraft:wooden_pickaxe)"));
        assertTrue(bare.contains("minecraft:iron_ore (needs minecraft:stone_pickaxe)"));
        List<String> wooden = TechTree.cannotMineYet(Map.of("minecraft:wooden_pickaxe", 1));
        assertFalse(wooden.stream().anyMatch(entry -> entry.startsWith("minecraft:stone ")));
        assertTrue(wooden.contains("minecraft:iron_ore (needs minecraft:stone_pickaxe)"));
    }

    @Test
    void placedWorkstationsDropThemselves() {
        assertTrue(TechTree.isReclaimable("minecraft:crafting_table"));
        assertFalse(TechTree.isReclaimable("minecraft:stick"));
        assertEquals("minecraft:crafting_table", TechTree.blockToMine("minecraft:crafting_table"));
        assertEquals("minecraft:crafting_table", TechTree.dropOf("minecraft:crafting_table"));
        assertEquals(TechTree.Tier.NONE, TechTree.toolFor("minecraft:crafting_table"));
        assertTrue(TechTree.useOf("minecraft:crafting_table").orElseThrow().contains("reusable"));
        assertFalse(TechTree.cannotMineYet(Map.of()).stream().anyMatch(entry -> entry.contains("furnace")));
    }

    @Test
    void doorsAreSixPlanksOfOneWoodForThree() {
        TechTree.Recipe door = TechTree.recipeFor("minecraft:spruce_door").orElseThrow();
        assertEquals(3, door.yield());
        assertEquals(Map.of("minecraft:spruce_planks", 6), door.inputs());
        assertTrue(door.needsTable());
        assertEquals("minecraft:oak_door", TechTree.doorFor("minecraft:oak_planks"));
        assertFalse(TechTree.isDoor("minecraft:iron_door"));
        assertFalse(TechTree.isDoor("minecraft:oak_trapdoor"));
        assertTrue(TechTree.useOf("minecraft:oak_door").orElseThrow().contains("doorway"));
    }

    @Test
    void weaponsAreRecognised() {
        assertTrue(TechTree.isWeapon("minecraft:stone_sword"));
        assertTrue(TechTree.isWeapon("minecraft:wooden_axe"));
        assertFalse(TechTree.isWeapon("minecraft:wooden_pickaxe"));
    }
}
