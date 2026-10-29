package dev.actionable;

import java.util.Set;

/** Shape and item checks before an item-use request can touch Minecraft. */
final class ItemUseRules {
    private static final Set<String> ITEMS = Set.of("minecraft:bucket", "minecraft:water_bucket",
            "minecraft:lava_bucket", "minecraft:flint_and_steel");
    private static final Set<String> FACES = Set.of("up", "down", "north", "south", "east", "west");
    private ItemUseRules() { }

    static boolean valid(String item, String face, long x, long y, long z) {
        return ITEMS.contains(item) && FACES.contains(face) && x >= -29_999_984 && x <= 29_999_984
                && z >= -29_999_984 && z <= 29_999_984 && y >= -2048 && y <= 2048;
    }

    static boolean mayPlace(String item, boolean inNether, boolean replaceable, boolean atPlayer,
            double playerDistance) {
        return replaceable && !atPlayer && !(item.equals("minecraft:water_bucket") && inNether)
                && !(item.equals("minecraft:lava_bucket") && playerDistance < 3);
    }

    static boolean mayUseBed(String dimension) {
        return !dimension.equals("minecraft:the_nether") && !dimension.equals("minecraft:the_end");
    }
}
