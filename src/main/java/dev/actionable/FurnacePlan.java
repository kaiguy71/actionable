package dev.actionable;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Decides what to smelt and with which fuel, from the inventory alone. */
final class FurnacePlan {
    /** Planks kept back from burning: enough for a bed and a door. */
    static final int PLANK_RESERVE = 10;

    private FurnacePlan() {
    }

    /** Smeltable inputs, most useful first, with what they become. */
    static final List<String[]> INPUTS = List.of(
            new String[] {"minecraft:raw_iron", "minecraft:iron_ingot"},
            new String[] {"minecraft:raw_gold", "minecraft:gold_ingot"},
            new String[] {"minecraft:raw_copper", "minecraft:copper_ingot"},
            new String[] {"minecraft:beef", "minecraft:cooked_beef"},
            new String[] {"minecraft:porkchop", "minecraft:cooked_porkchop"},
            new String[] {"minecraft:mutton", "minecraft:cooked_mutton"},
            new String[] {"minecraft:chicken", "minecraft:cooked_chicken"},
            new String[] {"minecraft:rabbit", "minecraft:cooked_rabbit"},
            new String[] {"minecraft:cod", "minecraft:cooked_cod"},
            new String[] {"minecraft:salmon", "minecraft:cooked_salmon"},
            new String[] {"minecraft:sand", "minecraft:glass"});

    /** A furnace load: how many of what, and how much of which fuel. */
    record Load(String input, int count, String fuel, int fuelCount) {
    }

    /** Items each fuel smelts; logs and planks are 1.5, counted as 3 per 2. */
    static double burns(String fuel) {
        if (TechTree.matches(TechTree.COALS, fuel)) {
            return 8;
        }
        if (TechTree.matches(TechTree.LOGS, fuel) || TechTree.matches(TechTree.PLANKS, fuel)) {
            return 1.5;
        }
        return 0;
    }

    static Optional<String> resultOf(String input) {
        return INPUTS.stream().filter(entry -> entry[0].equals(input)).map(entry -> entry[1]).findFirst();
    }

    static Optional<Load> choose(Map<String, Integer> inventory) {
        String input = null;
        int held = 0;
        for (String[] entry : INPUTS) {
            held = inventory.getOrDefault(entry[0], 0);
            if (held > 0) {
                input = entry[0];
                break;
            }
        }
        if (input == null) {
            return Optional.empty();
        }
        String chosen = input;
        int count = Math.min(held, 64);
        return fuelFor(inventory, count)
                .map(fuel -> new Load(chosen, Math.min(count, (int) Math.floor(fuel.count() * burns(fuel.item()))),
                        fuel.item(), fuel.count()));
    }

    /** A fuel stack: which fuel and how many. */
    record Fuel(String item, int count) {
    }

    /**
     * Fuel for smelting {@code items}: coal first, then logs, then spare planks, never the planks a bed
     * or door still needs. May cover fewer items than asked when fuel is short; empty when there is none.
     */
    static Optional<Fuel> fuelFor(Map<String, Integer> inventory, int items) {
        for (String key : List.of(TechTree.COALS, TechTree.LOGS, TechTree.PLANKS)) {
            for (Map.Entry<String, Integer> entry : inventory.entrySet()) {
                if (!TechTree.matches(key, entry.getKey()) || entry.getValue() <= 0) {
                    continue;
                }
                int available = key.equals(TechTree.PLANKS)
                        ? Math.min(entry.getValue(), Math.max(0, TechTree.count(inventory, TechTree.PLANKS) - PLANK_RESERVE))
                        : entry.getValue();
                double per = burns(entry.getKey());
                int fuel = Math.min((int) Math.ceil(items / per), available);
                if (fuel > 0 && Math.floor(fuel * per) > 0) {
                    return Optional.of(new Fuel(entry.getKey(), fuel));
                }
            }
        }
        return Optional.empty();
    }

    /** Raw meat, fish or ore waiting to be smelted, whether or not there is fuel. */
    static boolean hasSmeltables(Map<String, Integer> inventory) {
        return INPUTS.stream().anyMatch(entry -> inventory.getOrDefault(entry[0], 0) > 0);
    }
}
