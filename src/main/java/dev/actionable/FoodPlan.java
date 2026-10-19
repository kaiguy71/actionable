package dev.actionable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Food sense: what is edible and how filling, how much food the player carries, when to eat, and which
 * animals are food. Health only regenerates when the hunger bar is high, so food is a need like tools.
 */
final class FoodPlan {
    /** Carried food worth having, in hunger points: about three cooked meats. */
    static final int STOCK_WANTED = 24;
    /** Raw chicken can poison; only eat it when this hungry or worse. */
    private static final int STARVING = 6;

    private FoodPlan() {
    }

    /** Hunger points restored per item, best first. */
    static final Map<String, Integer> NUTRITION = new LinkedHashMap<>();
    /** What each food animal drops when killed. */
    static final Map<String, String> ANIMAL_FOOD = new LinkedHashMap<>();

    static {
        NUTRITION.put("minecraft:cooked_beef", 8);
        NUTRITION.put("minecraft:cooked_porkchop", 8);
        NUTRITION.put("minecraft:cooked_mutton", 6);
        NUTRITION.put("minecraft:cooked_chicken", 6);
        NUTRITION.put("minecraft:cooked_salmon", 6);
        NUTRITION.put("minecraft:bread", 5);
        NUTRITION.put("minecraft:baked_potato", 5);
        NUTRITION.put("minecraft:cooked_rabbit", 5);
        NUTRITION.put("minecraft:cooked_cod", 5);
        NUTRITION.put("minecraft:apple", 4);
        NUTRITION.put("minecraft:beef", 3);
        NUTRITION.put("minecraft:porkchop", 3);
        NUTRITION.put("minecraft:rabbit", 3);
        NUTRITION.put("minecraft:carrot", 3);
        NUTRITION.put("minecraft:mutton", 2);
        NUTRITION.put("minecraft:sweet_berries", 2);
        NUTRITION.put("minecraft:cod", 2);
        NUTRITION.put("minecraft:salmon", 2);
        NUTRITION.put("minecraft:chicken", 2);
        NUTRITION.put("minecraft:potato", 1);
        ANIMAL_FOOD.put("minecraft:cow", "minecraft:beef");
        ANIMAL_FOOD.put("minecraft:pig", "minecraft:porkchop");
        ANIMAL_FOOD.put("minecraft:sheep", "minecraft:mutton");
        ANIMAL_FOOD.put("minecraft:chicken", "minecraft:chicken");
        ANIMAL_FOOD.put("minecraft:rabbit", "minecraft:rabbit");
    }

    static int stock(Map<String, Integer> inventory) {
        int total = 0;
        for (Map.Entry<String, Integer> entry : inventory.entrySet()) {
            total += NUTRITION.getOrDefault(entry.getKey(), 0) * entry.getValue();
        }
        return total;
    }

    static boolean needFood(Map<String, Integer> inventory) {
        return stock(inventory) < STOCK_WANTED;
    }

    static boolean isFood(String item) {
        return NUTRITION.containsKey(item);
    }

    /** Eat when hungry, or when hurt and not full, since health only regenerates on a high hunger bar. */
    static boolean shouldEat(int food, float health, float maxHealth) {
        return food < 20 && (food <= 14 || (health < maxHealth - 4 && food <= 17));
    }

    /** The best thing to eat now: the most filling item, leaving raw chicken for emergencies. */
    static Optional<String> pick(Map<String, Integer> inventory, int food) {
        for (String item : NUTRITION.keySet()) {
            if (inventory.getOrDefault(item, 0) > 0 && (!item.equals("minecraft:chicken") || food <= STARVING)) {
                return Optional.of(item);
            }
        }
        return Optional.empty();
    }
}
