package dev.actionable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The player's sense of its situation: what it has, what it needs next and why, and what it can do
 * right now. At night without a bed it can only work at home, so it picks the most useful home job:
 * finishing the house, crafting tools, smelting, or digging the house mine for cobblestone and iron.
 */
final class NeedsPlanner {
    /** Cobblestone worth gathering before going deeper: stone tools, a furnace and some spare. */
    static final int COBBLE_WANTED = 24;
    /** Raw iron worth gathering in the house mine. */
    static final int IRON_WANTED = 12;
    static final String MINE_COBBLE = "dig house mine for " + COBBLE_WANTED + " minecraft:cobblestone";
    static final String MINE_IRON = "dig house mine for " + IRON_WANTED + " minecraft:raw_iron";
    static final String PLACE_FURNACE = "place minecraft:furnace in house";
    static final String SMELT = "smelt in house furnace";

    private NeedsPlanner() {
    }

    record Task(String goal, String why) {
    }

    /** Everything the planner needs to know besides the inventory. */
    record Situation(ProgressPlanner.Facts facts, boolean night, boolean furnaceWaiting, boolean mineAllowed,
            boolean strayDoors) {
        Situation(ProgressPlanner.Facts facts, boolean night, boolean furnaceWaiting, boolean mineAllowed) {
            this(facts, night, furnaceWaiting, mineAllowed, false);
        }

        boolean furnaceInHouse() {
            return facts.furnaceInHouse();
        }

        boolean mineFinished() {
            return facts.mineFinished();
        }
    }

    /**
     * The best job that can be done entirely at home: inside the house, at its table and furnace, or
     * in the house mine. Empty when there is nothing useful, and the player just waits indoors.
     */
    static Optional<Task> homeTask(Map<String, Integer> inventory, Situation situation) {
        ProgressPlanner.Facts facts = situation.facts();
        boolean table = facts.tableInHouse();
        TechTree.Tier tier = TechTree.pickaxeTier(inventory);
        int cobble = TechTree.count(inventory, TechTree.STONE_MATERIAL);
        List<Task> candidates = new ArrayList<>();
        if (!facts.doorPlaced()) {
            candidates.add(new Task(ProgressPlanner.PLACE_DOOR, "the doorway is open and mobs can walk in"));
        }
        if (!table) {
            candidates.add(new Task(ProgressPlanner.PLACE_TABLE_IN_HOUSE, "every tool and the bed are crafted at it"));
        }
        if (!facts.bedPlaced() && TechTree.held(inventory, TechTree.WHITE_WOOL) >= 3) {
            candidates.add(new Task(ProgressPlanner.PLACE_BED, "the wool for a bed is already here; then sleep"));
        }
        if (tier == TechTree.Tier.NONE) {
            candidates.add(new Task("craft 1 minecraft:wooden_pickaxe", "the house mine needs a pickaxe"));
        }
        if (!inventory.keySet().stream().anyMatch(item -> item.endsWith("_sword"))) {
            candidates.add(new Task("craft 1 minecraft:wooden_sword", "mobs can spawn in the dark mine"));
        }
        if (tier.compareTo(TechTree.Tier.STONE) < 0 && cobble >= 3) {
            candidates.add(new Task("craft 1 minecraft:stone_pickaxe", "iron ore needs a stone pickaxe"));
        }
        if (cobble >= 2 && !HomeRange.geared(inventory) && inventory.keySet().stream()
                .noneMatch(item -> item.endsWith("_sword") && !item.equals("minecraft:wooden_sword"))) {
            candidates.add(new Task("craft 1 minecraft:stone_sword", "a stronger weapon, and it lets you travel farther"));
        }
        if (!situation.furnaceInHouse() && cobble >= 8) {
            candidates.add(new Task(PLACE_FURNACE, "smelts raw iron into ingots and cooks meat"));
        }
        if (situation.furnaceInHouse() && (situation.furnaceWaiting() || FurnacePlan.choose(inventory).isPresent())) {
            candidates.add(new Task(SMELT, "turn raw iron and raw meat into ingots and food while waiting"));
        }
        if (tier != TechTree.Tier.NONE && !situation.mineFinished() && situation.mineAllowed()) {
            if (cobble < COBBLE_WANTED) {
                candidates.add(new Task(MINE_COBBLE, "nothing else to do at home; cobblestone is needed for stone"
                        + " tools and a furnace, and the house mine is safe"));
            } else if (tier.compareTo(TechTree.Tier.STONE) >= 0
                    && TechTree.held(inventory, "minecraft:raw_iron") < IRON_WANTED) {
                candidates.add(new Task(MINE_IRON, "enough cobblestone; dig deeper toward iron"));
            }
        }
        for (Task task : candidates) {
            if (doableAtHome(task.goal(), inventory, situation)) {
                return Optional.of(task);
            }
        }
        return Optional.empty();
    }

    /** True when every step of the task's plan happens at home, so it is safe at night. */
    static boolean doableAtHome(String goal, Map<String, Integer> inventory, Situation situation) {
        if (goal.equals(MINE_COBBLE) || goal.equals(MINE_IRON) || goal.equals(SMELT)) {
            return true;
        }
        if (goal.equals(ProgressPlanner.PLACE_DOOR) && situation.strayDoors()) {
            // Misplaced doors are taken back and rehung; no crafting needed.
            return true;
        }
        ProgressPlanner.TableAccess table = situation.facts().tableInHouse()
                ? ProgressPlanner.TableAccess.HOME_NEARBY : ProgressPlanner.TableAccess.NONE;
        List<String> steps = ProgressPlanner.stepsForGoal(goal, inventory, table, "minecraft:oak_log");
        return !steps.isEmpty() && steps.stream().allMatch(NeedsPlanner::homeStep);
    }

    /** Steps that never leave home: crafting, placing in the house, walking home, smelting, the house mine. */
    static boolean homeStep(String step) {
        String text = step.toLowerCase(Locale.ROOT);
        return text.startsWith("craft ") || text.endsWith(" in house") || text.equals(ProgressPlanner.GO_HOME)
                || text.equals(ProgressPlanner.LAY_FLOOR) || text.startsWith("dig house mine")
                || text.equals(SMELT) || text.startsWith("place minecraft:crafting_table nearby");
    }

    /** A short, plain report of the player's situation for the model and the debug panel. */
    static String report(Map<String, Integer> inventory, Situation situation) {
        ProgressPlanner.Facts facts = situation.facts();
        StringBuilder text = new StringBuilder("Situation:");
        int wool = TechTree.held(inventory, TechTree.WHITE_WOOL);
        if (situation.night()) {
            text.append(facts.bedPlaced() ? " night; a bed is placed, so sleep."
                    : " night and no bed (" + wool + "/3 white wool), so you cannot sleep; stay home and do home jobs.");
        } else {
            text.append(" day.");
        }
        String weapon = inventory.keySet().stream().filter(TechTree::isWeapon).findFirst().orElse("none");
        text.append(" Have: weapon=").append(weapon)
                .append(", food=").append(FoodPlan.stock(inventory)).append(" hunger points")
                .append(", pickaxe=").append(TechTree.pickaxeTier(inventory).name().toLowerCase(Locale.ROOT))
                .append(", wood=").append(TechTree.count(inventory, TechTree.LOGS)).append(" logs/")
                .append(TechTree.count(inventory, TechTree.PLANKS)).append(" planks")
                .append(", cobblestone=").append(TechTree.count(inventory, TechTree.STONE_MATERIAL))
                .append(", raw_iron=").append(inventory.getOrDefault("minecraft:raw_iron", 0))
                .append(", house=").append(facts.houseBuilt() ? "built" : "none")
                .append(", door=").append(facts.doorPlaced()).append(", table=").append(facts.tableInHouse())
                .append(", furnace=").append(situation.furnaceInHouse()).append(", bed=").append(facts.bedPlaced())
                .append('.');
        List<String> needs = new ArrayList<>();
        if (!facts.bedPlaced()) {
            needs.add("bed: " + Math.max(0, 3 - wool) + " more white wool (kill white sheep by day)");
        }
        if (!facts.doorPlaced()) {
            needs.add("door: 6 planks at the house table");
        }
        if (TechTree.pickaxeTier(inventory).compareTo(TechTree.Tier.STONE) < 0) {
            needs.add("stone pickaxe: 3 cobblestone (house mine) + 2 sticks");
        }
        if (!situation.furnaceInHouse()) {
            needs.add("furnace: 8 cobblestone");
        }
        if (FurnacePlan.hasSmeltables(inventory)) {
            needs.add(FurnacePlan.choose(inventory).isPresent() && situation.furnaceInHouse()
                    ? "smelt: raw items ready to cook/smelt in the house furnace (goal \"" + SMELT + "\")"
                    : "smelt: raw items waiting; needs " + (situation.furnaceInHouse() ? "fuel (coal, logs or spare planks)"
                    : "a furnace in the house"));
        }
        ProgressPlanner.coalTooEarly("coal", inventory, facts)
                .ifPresent(reason -> needs.add("coal: not yet; " + reason));
        int food = FoodPlan.stock(inventory);
        if (food < FoodPlan.STOCK_WANTED) {
            needs.add("food: carrying " + food + "/" + FoodPlan.STOCK_WANTED + " hunger points; kill pigs, cows or"
                    + " chickens near home (food is eaten automatically when hungry)");
        }
        text.append(" Needs: ").append(needs.isEmpty() ? "none urgent" : String.join("; ", needs)).append('.');
        homeTask(inventory, situation).ifPresent(task -> text.append(" Best home job now: ").append(task.goal())
                .append(" (").append(task.why()).append(')'));
        return text.toString();
    }
}
