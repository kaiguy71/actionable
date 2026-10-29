package dev.actionable;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A concrete preparation route: harvest obsidian with a diamond pickaxe, then build/light a portal. */
final class NetherProgress {
    static final String ENTER = "enter the nether";
    private NetherProgress() { }
    static final List<ProgressPlanner.Milestone> MILESTONES = List.of(
            hold("iron_pickaxe", 1, "iron pickaxe harvests diamond ore"),
            hold("iron_sword", 1, "a stronger weapon before deep mining"),
            hold("shield", 1, "carry and equip a shield; active blocking is not automated"),
            hold("iron_chestplate", 1, "wear armor before mining diamonds"),
            hold("iron_leggings", 1, "wear armor before mining diamonds"),
            hold("iron_helmet", 1, "wear armor before mining diamonds"),
            hold("iron_boots", 1, "wear armor before mining diamonds"),
            hold("bucket", 1, "carry water while mining and making obsidian"),
            hold("water_bucket", 1, "fill the bucket from an observed water source before deep mining"),
            hold("flint_and_steel", 1, "ignite the completed obsidian portal frame"),
            hold("diamond_pickaxe", 1, "only a diamond-or-better pickaxe harvests obsidian"),
            hold("obsidian", 10, "10 ordinary obsidian blocks make a cornerless Nether portal"),
            new ProgressPlanner.Milestone("nether", 1,
                    "build the frame, ignite it, enter and verify minecraft:the_nether", ProgressPlanner.Kind.NETHER));

    private static ProgressPlanner.Milestone hold(String item, int count, String why) {
        return new ProgressPlanner.Milestone("minecraft:" + item, count, why);
    }

    static Optional<ProgressPlanner.Milestone> next(Map<String, Integer> inventory) {
        return MILESTONES.stream().filter(m -> !ProgressPlanner.satisfied(m, inventory)).findFirst();
    }

    /** Count both unspent iron and iron already invested in useful gear, avoiding endless raw-iron restocking. */
    static int ironProgress(Map<String, Integer> inventory) {
        int total = inventory.getOrDefault("minecraft:raw_iron", 0) + inventory.getOrDefault("minecraft:iron_ingot", 0);
        for (String item : List.of("iron_pickaxe", "iron_sword", "iron_axe", "iron_shovel", "iron_helmet",
                "iron_chestplate", "iron_leggings", "iron_boots", "bucket", "shield", "flint_and_steel")) {
            int cost = TechTree.recipeFor("minecraft:" + item).orElseThrow().inputs().getOrDefault("minecraft:iron_ingot", 0);
            int held = inventory.getOrDefault("minecraft:" + item, 0);
            if (item.equals("bucket")) held += inventory.getOrDefault("minecraft:water_bucket", 0)
                    + inventory.getOrDefault("minecraft:lava_bucket", 0);
            total += cost * held;
        }
        return total;
    }
}
