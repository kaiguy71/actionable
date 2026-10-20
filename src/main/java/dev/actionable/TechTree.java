package dev.actionable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Curated early-game knowledge: how each material is obtained, what it is for, and the exact crafting
 * patterns Actionable can carry out. Pure data with no game access, so it can be unit tested.
 */
final class TechTree {
    static final String LOGS = "#logs";
    static final String PLANKS = "#planks";
    static final String STONE_MATERIAL = "#stone_crafting";
    static final String COALS = "#coals";
    static final String CRAFTING_TABLE = "minecraft:crafting_table";
    static final String WHITE_WOOL = "minecraft:white_wool";
    static final String WHITE_BED = "minecraft:white_bed";

    private TechTree() {
    }

    /** Pickaxe tiers, ordered: each tier mines everything the tiers before it can. */
    enum Tier { NONE, WOOD, STONE, IRON, DIAMOND }

    /** A crafting-grid recipe. Pattern rows use keys from {@code keys}; a space is an empty cell. */
    record Recipe(String result, int yield, List<String> pattern, Map<Character, String> keys) {
        int width() {
            return pattern.stream().mapToInt(String::length).max().orElse(0);
        }

        int height() {
            return pattern.size();
        }

        /** Recipes wider or taller than two cells need a placed crafting table. */
        boolean needsTable() {
            return width() > 2 || height() > 2;
        }

        /** Ingredient key (item ID or #group) to count per craft, in pattern order. */
        Map<String, Integer> inputs() {
            Map<String, Integer> inputs = new LinkedHashMap<>();
            for (String row : pattern) {
                for (char cell : row.toCharArray()) {
                    if (cell != ' ') {
                        inputs.merge(keys.get(cell), 1, Integer::sum);
                    }
                }
            }
            return inputs;
        }

        /** Crafting-grid slot index to ingredient key, for a grid {@code gridWidth} cells wide. */
        Map<Integer, String> layout(int gridWidth) {
            Map<Integer, String> layout = new TreeMap<>();
            for (int row = 0; row < pattern.size(); row++) {
                String cells = pattern.get(row);
                for (int column = 0; column < cells.length(); column++) {
                    char cell = cells.charAt(column);
                    if (cell != ' ') {
                        // Slot 0 is the result slot in both the inventory and crafting table menus.
                        layout.put(1 + row * gridWidth + column, keys.get(cell));
                    }
                }
            }
            return layout;
        }
    }

    /** An item obtained by mining {@code block} with at least a {@code tool}-tier pickaxe. */
    record Source(String item, String block, Tier tool) {
    }

    private static final Map<String, Recipe> RECIPES = new LinkedHashMap<>();
    private static final List<Source> SOURCES = List.of(
            new Source("minecraft:cobblestone", "minecraft:stone", Tier.WOOD),
            new Source("minecraft:cobblestone", "minecraft:cobblestone", Tier.WOOD),
            new Source("minecraft:cobbled_deepslate", "minecraft:deepslate", Tier.WOOD),
            new Source("minecraft:coal", "minecraft:coal_ore", Tier.WOOD),
            new Source("minecraft:coal", "minecraft:deepslate_coal_ore", Tier.WOOD),
            new Source("minecraft:raw_iron", "minecraft:iron_ore", Tier.STONE),
            new Source("minecraft:raw_iron", "minecraft:deepslate_iron_ore", Tier.STONE),
            new Source("minecraft:raw_copper", "minecraft:copper_ore", Tier.STONE),
            new Source("minecraft:lapis_lazuli", "minecraft:lapis_ore", Tier.STONE),
            new Source("minecraft:raw_gold", "minecraft:gold_ore", Tier.IRON),
            new Source("minecraft:redstone", "minecraft:redstone_ore", Tier.IRON),
            new Source("minecraft:diamond", "minecraft:diamond_ore", Tier.IRON),
            new Source("minecraft:diamond", "minecraft:deepslate_diamond_ore", Tier.IRON),
            new Source("minecraft:obsidian", "minecraft:obsidian", Tier.DIAMOND),
            new Source("minecraft:dirt", "minecraft:dirt", Tier.NONE),
            new Source("minecraft:dirt", "minecraft:grass_block", Tier.NONE),
            new Source("minecraft:sand", "minecraft:sand", Tier.NONE),
            new Source("minecraft:gravel", "minecraft:gravel", Tier.NONE),
            new Source("minecraft:flint", "minecraft:gravel", Tier.NONE),
            // Placed workstations drop themselves, so mining one picks it back up.
            new Source(CRAFTING_TABLE, CRAFTING_TABLE, Tier.NONE),
            new Source("minecraft:chest", "minecraft:chest", Tier.NONE),
            new Source("minecraft:furnace", "minecraft:furnace", Tier.WOOD));
    private static final Map<String, String> USES = new LinkedHashMap<>();

    static {
        recipe("minecraft:stick", 4, List.of("P", "P"), 'P', PLANKS);
        recipe(CRAFTING_TABLE, 1, List.of("PP", "PP"), 'P', PLANKS);
        recipe("minecraft:wooden_pickaxe", 1, List.of("PPP", " S ", " S "), 'P', PLANKS, 'S', "minecraft:stick");
        recipe("minecraft:wooden_axe", 1, List.of("PP", "PS", " S"), 'P', PLANKS, 'S', "minecraft:stick");
        recipe("minecraft:wooden_sword", 1, List.of("P", "P", "S"), 'P', PLANKS, 'S', "minecraft:stick");
        recipe("minecraft:wooden_shovel", 1, List.of("P", "S", "S"), 'P', PLANKS, 'S', "minecraft:stick");
        recipe("minecraft:stone_pickaxe", 1, List.of("CCC", " S ", " S "), 'C', STONE_MATERIAL, 'S', "minecraft:stick");
        recipe("minecraft:stone_axe", 1, List.of("CC", "CS", " S"), 'C', STONE_MATERIAL, 'S', "minecraft:stick");
        recipe("minecraft:stone_sword", 1, List.of("C", "C", "S"), 'C', STONE_MATERIAL, 'S', "minecraft:stick");
        recipe("minecraft:stone_shovel", 1, List.of("C", "S", "S"), 'C', STONE_MATERIAL, 'S', "minecraft:stick");
        recipe("minecraft:torch", 4, List.of("C", "S"), 'C', COALS, 'S', "minecraft:stick");
        recipe("minecraft:furnace", 1, List.of("CCC", "C C", "CCC"), 'C', STONE_MATERIAL);
        recipe("minecraft:chest", 1, List.of("PPP", "P P", "PPP"), 'P', PLANKS);
        // A bed needs three wool of one colour; white is by far the most common sheep.
        recipe(WHITE_BED, 1, List.of("WWW", "PPP"), 'W', WHITE_WOOL, 'P', PLANKS);

        for (String material : List.of("iron", "diamond")) {
            String input = material.equals("iron") ? "minecraft:iron_ingot" : "minecraft:diamond";
            recipe("minecraft:" + material + "_pickaxe", 1, List.of("MMM", " S ", " S "), 'M', input, 'S', "minecraft:stick");
            recipe("minecraft:" + material + "_axe", 1, List.of("MM", "MS", " S"), 'M', input, 'S', "minecraft:stick");
            recipe("minecraft:" + material + "_sword", 1, List.of("M", "M", "S"), 'M', input, 'S', "minecraft:stick");
            recipe("minecraft:" + material + "_shovel", 1, List.of("M", "S", "S"), 'M', input, 'S', "minecraft:stick");
            recipe("minecraft:" + material + "_helmet", 1, List.of("MMM", "M M"), 'M', input);
            recipe("minecraft:" + material + "_chestplate", 1, List.of("M M", "MMM", "MMM"), 'M', input);
            recipe("minecraft:" + material + "_leggings", 1, List.of("MMM", "M M", "M M"), 'M', input);
            recipe("minecraft:" + material + "_boots", 1, List.of("M M", "M M"), 'M', input);
        }
        recipe("minecraft:bucket", 1, List.of("I I", " I "), 'I', "minecraft:iron_ingot");
        recipe("minecraft:flint_and_steel", 1, List.of("I ", " F"), 'I', "minecraft:iron_ingot", 'F', "minecraft:flint");
        recipe("minecraft:shield", 1, List.of("PIP", "PPP", " P "), 'P', PLANKS, 'I', "minecraft:iron_ingot");
        recipe("minecraft:golden_boots", 1, List.of("G G", "G G"), 'G', "minecraft:gold_ingot");

        USES.put(LOGS, "craft into planks (1 log -> 4 planks); every wooden item starts here");
        USES.put(PLANKS, "crafting table (4), sticks (2 -> 4), wooden tools (3 per pickaxe), chest (8)");
        USES.put("minecraft:stick", "handles for every tool, and torches");
        USES.put(CRAFTING_TABLE, "place it to unlock 3x3 recipes: pickaxes, axes, swords, furnace, chest;"
                + " reusable: walk to the placed table and leave it standing after crafting; never craft a second one");
        USES.put("minecraft:wooden_pickaxe", "mines stone (drops cobblestone) and coal ore");
        USES.put("minecraft:stone_pickaxe", "mines iron, copper and lapis ore, and stone faster");
        USES.put("minecraft:wooden_sword", "melee weapon for nights and caves");
        USES.put("minecraft:stone_sword", "stronger melee weapon for nights and caves");
        USES.put("minecraft:wooden_axe", "chops logs faster; usable as a weapon");
        USES.put("minecraft:stone_axe", "chops logs faster; usable as a weapon");
        USES.put("minecraft:cobblestone", "stone tools (3 per pickaxe, 2 per sword) and furnace (8)");
        USES.put("minecraft:cobbled_deepslate", "same uses as cobblestone: stone tools and furnace");
        USES.put(COALS, "torches (1 coal + 1 stick -> 4 torches) and furnace fuel");
        USES.put("minecraft:torch", "lights caves so you can see and mobs stop spawning; recommended for underground work");
        USES.put("minecraft:furnace", "smelts raw ore and cooks food through the smelt in house furnace goal");
        USES.put("minecraft:chest", "stores spare items");
        USES.put("minecraft:raw_iron", "smelt into iron ingots with the house furnace; craft iron tools, armor, shield, bucket and flint and steel");
        USES.put("minecraft:iron_pickaxe", "mines diamond ore; use three diamonds for a diamond pickaxe");
        USES.put("minecraft:diamond_pickaxe", "required to harvest obsidian for the Nether portal");
        USES.put("minecraft:bucket", "use_item at a water source to fill; place water next to lava sources to make obsidian");
        USES.put("minecraft:obsidian", "10 blocks make a cornerless 4-wide by 5-high portal frame, with a 2x3 empty interior; crying obsidian does not work");
        USES.put("minecraft:flint", "gravel sometimes drops flint; break individual gravel blocks until one drops");
        USES.put("minecraft:flint_and_steel", "use_item on the top of an obsidian base block to ignite a complete portal; observe portal blocks before entering");
        USES.put("minecraft:shield", "equip to offhand; active blocking is not automated");
        USES.put("minecraft:golden_boots", "wear in the Nether to avoid provoking ordinary piglins; not protection from piglin brutes");
        USES.put("minecraft:dirt", "cheap filler block for pillaring up or blocking holes");
        USES.put(WHITE_WOOL, "3 white wool + 3 planks make a bed (from killing white sheep)");
        USES.put(WHITE_BED, "place it inside the house and sleep at night to skip the night and set respawn");
        USES.put("minecraft:mutton", "food; eat it to keep health regenerating");
    }

    private static void recipe(String result, int yield, List<String> pattern, Object... keys) {
        Map<Character, String> keyMap = new LinkedHashMap<>();
        for (int index = 0; index < keys.length; index += 2) {
            keyMap.put((Character) keys[index], (String) keys[index + 1]);
        }
        RECIPES.put(result, new Recipe(result, yield, List.copyOf(pattern), Map.copyOf(keyMap)));
    }

    /**
     * Crafted blocks that drop themselves when mined, so a placed one can be picked up and reused
     * instead of crafting another.
     */
    static boolean isReclaimable(String item) {
        return item.equals(CRAFTING_TABLE) || item.equals("minecraft:chest") || item.equals("minecraft:furnace");
    }

    /** True when {@code item} satisfies an ingredient key, which is an item ID or a #group. */
    static boolean matches(String key, String item) {
        String path = item.substring(item.indexOf(':') + 1);
        return switch (key) {
            case LOGS -> path.endsWith("_log") || path.endsWith("_stem");
            case PLANKS -> path.endsWith("_planks");
            case STONE_MATERIAL -> item.equals("minecraft:cobblestone") || item.equals("minecraft:cobbled_deepslate")
                    || item.equals("minecraft:blackstone");
            case COALS -> item.equals("minecraft:coal") || item.equals("minecraft:charcoal");
            default -> key.equals(item);
        };
    }

    /** The group an item is interchangeable within for counting, or the item itself. */
    static String groupOf(String item) {
        for (String group : List.of(LOGS, PLANKS)) {
            if (matches(group, item)) {
                return group;
            }
        }
        return item;
    }

    static int count(Map<String, Integer> inventory, String key) {
        int total = 0;
        for (Map.Entry<String, Integer> entry : inventory.entrySet()) {
            if (matches(key, entry.getKey())) {
                total += entry.getValue();
            }
        }
        return total;
    }

    /** How many of a block's drop (or of an item) the inventory holds, counting wood variants together. */
    static int held(Map<String, Integer> inventory, String blockOrItem) {
        return count(inventory, groupOf(dropOf(blockOrItem)));
    }

    static Optional<Recipe> recipeFor(String item) {
        if (matches(PLANKS, item)) {
            return Optional.of(new Recipe(item, 4, List.of("L"), Map.of('L', LOGS)));
        }
        if (isDoor(item)) {
            // A wooden door needs six planks of its own wood type and yields three doors.
            return Optional.of(new Recipe(item, 3, List.of("PP", "PP", "PP"), Map.of('P', item.replace("_door", "_planks"))));
        }
        return Optional.ofNullable(RECIPES.get(item));
    }

    /** Every item Actionable knows how to craft, planks included. */
    static List<String> craftableItems() {
        List<String> items = new ArrayList<>();
        items.add("minecraft:oak_planks (any *_planks)");
        items.add("minecraft:oak_door (any wooden *_door, from 6 planks of that wood)");
        items.addAll(RECIPES.keySet());
        return items;
    }

    /** The mob killed to obtain an item, for items that come from hunting rather than mining. */
    static Optional<String> huntedFrom(String item) {
        return item.equals(WHITE_WOOL) || item.equals("minecraft:mutton")
                ? Optional.of("minecraft:sheep") : Optional.empty();
    }

    /** The block to mine for an item: cobblestone comes from stone, coal from coal ore, and so on. */
    static Optional<Source> sourceFor(String item) {
        if (matches(LOGS, item)) {
            return Optional.of(new Source(item, item, Tier.NONE));
        }
        return SOURCES.stream().filter(source -> source.item().equals(item)).findFirst();
    }

    /** The item a mined block drops, or the input itself when it is not a known source block. */
    static String dropOf(String blockOrItem) {
        return SOURCES.stream().filter(source -> source.block().equals(blockOrItem))
                .map(Source::item).findFirst().orElse(blockOrItem);
    }

    /**
     * The block to mine when a model names an item: "mine minecraft:cobblestone" means mine stone,
     * because placed cobblestone is rare and usually sits deep in dungeons or caves.
     */
    static String blockToMine(String blockOrItem) {
        boolean isBlock = SOURCES.stream().anyMatch(source -> source.block().equals(blockOrItem)
                && !source.block().equals(source.item()));
        if (isBlock) {
            return blockOrItem;
        }
        return sourceFor(blockOrItem).map(Source::block).orElse(blockOrItem);
    }

    static Tier toolFor(String block) {
        if (matches(LOGS, block)) {
            return Tier.NONE;
        }
        return SOURCES.stream().filter(source -> source.block().equals(block))
                .map(Source::tool).findFirst().orElse(Tier.NONE);
    }

    static Tier tierOf(String item) {
        String path = item.substring(item.indexOf(':') + 1);
        if (!path.endsWith("_pickaxe")) {
            return Tier.NONE;
        }
        return switch (path.substring(0, path.length() - "_pickaxe".length())) {
            case "wooden", "golden" -> Tier.WOOD;
            case "stone", "copper" -> Tier.STONE;
            case "iron" -> Tier.IRON;
            case "diamond", "netherite" -> Tier.DIAMOND;
            default -> Tier.NONE;
        };
    }

    static Tier pickaxeTier(Map<String, Integer> inventory) {
        Tier best = Tier.NONE;
        for (Map.Entry<String, Integer> entry : inventory.entrySet()) {
            Tier tier = tierOf(entry.getKey());
            if (entry.getValue() > 0 && tier.compareTo(best) > 0) {
                best = tier;
            }
        }
        return best;
    }

    static String pickaxeFor(Tier tier) {
        return switch (tier) {
            case NONE, WOOD -> "minecraft:wooden_pickaxe";
            case STONE -> "minecraft:stone_pickaxe";
            case IRON -> "minecraft:iron_pickaxe";
            case DIAMOND -> "minecraft:diamond_pickaxe";
        };
    }

    static boolean isWeapon(String item) {
        String path = item.substring(item.indexOf(':') + 1);
        return path.endsWith("_sword") || path.endsWith("_axe") || path.equals("mace") || path.equals("trident");
    }

    static boolean isDoor(String item) {
        return item.endsWith("_door") && !item.equals("minecraft:iron_door") && !item.contains("copper");
    }

    static String doorFor(String planks) {
        return planks.replace("_planks", "_door");
    }

    static String logFor(String planks) {
        String wood = planks.replace("_planks", "");
        return wood.endsWith("crimson") || wood.endsWith("warped") ? wood + "_stem" : wood + "_log";
    }

    static String planksFor(String log) {
        return log.replace("stripped_", "").replaceAll("_(log|stem)$", "_planks");
    }

    /** What an item or its group is for, or empty when it has no known use in progression. */
    static Optional<String> useOf(String item) {
        String use = USES.get(item);
        if (use == null) {
            use = USES.get(groupOf(item));
        }
        if (use == null && matches(COALS, item)) {
            use = USES.get(COALS);
        }
        if (use == null && isDoor(item)) {
            use = "hang it in the house doorway to keep mobs out";
        }
        return Optional.ofNullable(use);
    }

    /** Items whose ingredients are all in the inventory right now, with what each is for. */
    static List<String> craftableNow(Map<String, Integer> inventory, boolean tableNearby) {
        List<String> craftable = new ArrayList<>();
        List<Recipe> candidates = new ArrayList<>();
        inventory.keySet().stream().filter(item -> matches(LOGS, item)).findFirst()
                .ifPresent(log -> recipeFor(planksFor(log)).ifPresent(candidates::add));
        candidates.addAll(RECIPES.values());
        for (Recipe recipe : candidates) {
            boolean enough = recipe.inputs().entrySet().stream()
                    .allMatch(input -> count(inventory, input.getKey()) >= input.getValue());
            if (!enough) {
                continue;
            }
            boolean hasTable = tableNearby || inventory.getOrDefault(CRAFTING_TABLE, 0) > 0;
            String where = !recipe.needsTable() ? "" : tableNearby ? " [at table]"
                    : hasTable ? " [place table first]" : " [needs a crafting table]";
            craftable.add(recipe.result() + where);
        }
        return craftable;
    }

    /** Blocks the current pickaxe tier cannot harvest, with the pickaxe each one needs. */
    static List<String> cannotMineYet(Map<String, Integer> inventory) {
        Tier tier = pickaxeTier(inventory);
        Map<String, String> blocked = new LinkedHashMap<>();
        for (Source source : SOURCES) {
            if (source.tool().compareTo(tier) > 0 && !source.block().contains("deepslate")
                    && !isReclaimable(source.block())) {
                blocked.putIfAbsent(source.block(), source.block() + " (needs " + pickaxeFor(source.tool()) + ")");
            }
        }
        return List.copyOf(blocked.values());
    }
}
