package dev.actionable;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class EquipRulesTest {
    @Test
    void classifiesWearableItems() {
        assertEquals(EquipRules.HEAD, EquipRules.armorSlot("minecraft:iron_helmet"));
        assertEquals(EquipRules.HEAD, EquipRules.armorSlot("minecraft:turtle_helmet"));
        assertEquals(EquipRules.CHEST, EquipRules.armorSlot("minecraft:diamond_chestplate"));
        assertEquals(EquipRules.CHEST, EquipRules.armorSlot("minecraft:elytra"));
        assertEquals(EquipRules.LEGS, EquipRules.armorSlot("minecraft:leather_leggings"));
        assertEquals(EquipRules.FEET, EquipRules.armorSlot("minecraft:netherite_boots"));
        assertEquals(EquipRules.OFFHAND, EquipRules.armorSlot("minecraft:shield"));
    }

    @Test
    void toolsAndBlocksAreNotWearable() {
        assertEquals(EquipRules.NOT_ARMOR, EquipRules.armorSlot("minecraft:iron_sword"));
        assertEquals(EquipRules.NOT_ARMOR, EquipRules.armorSlot("minecraft:stone"));
        assertEquals(EquipRules.NOT_ARMOR, EquipRules.armorSlot("minecraft:boots_of_nothing_helmet_stand"));
    }

    @Test
    void upgradesArmorAndEmptyOffhandWithoutDowngradingGear() {
        assertEquals(java.util.List.of("minecraft:iron_chestplate", "minecraft:shield"), EquipRules.upgrades(
                java.util.Map.of("minecraft:iron_chestplate", 1, "minecraft:leather_helmet", 1, "minecraft:shield", 1),
                java.util.Map.of(EquipRules.HEAD, "minecraft:diamond_helmet"), false));
        assertEquals(java.util.List.of(), EquipRules.upgrades(
                java.util.Map.of("minecraft:iron_chestplate", 1, "minecraft:shield", 1),
                java.util.Map.of(EquipRules.CHEST, "minecraft:elytra", EquipRules.OFFHAND, "minecraft:torch"), false));
    }

    @Test
    void keepsOrEquipsGoldBootsForNetherWithoutReplacingThemWithIron() {
        assertEquals(java.util.List.of("minecraft:golden_boots"), EquipRules.upgrades(
                java.util.Map.of("minecraft:golden_boots", 1), java.util.Map.of(EquipRules.FEET, "minecraft:iron_boots"), true));
        assertEquals(java.util.List.of(), EquipRules.upgrades(java.util.Map.of("minecraft:iron_boots", 1),
                java.util.Map.of(EquipRules.FEET, "minecraft:golden_boots"), true));
        assertEquals(java.util.List.of("minecraft:iron_boots"), EquipRules.upgrades(java.util.Map.of("minecraft:iron_boots", 1),
                java.util.Map.of(EquipRules.FEET, "minecraft:golden_boots"), false));
    }

    @Test
    void namesSlots() {
        assertEquals("chest", EquipRules.slotName(EquipRules.CHEST));
        assertEquals("offhand", EquipRules.slotName(EquipRules.OFFHAND));
        assertEquals("hand", EquipRules.slotName(EquipRules.NOT_ARMOR));
    }
}
