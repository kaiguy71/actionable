package dev.actionable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;

final class GameKnowledge {
    private static final int MAX_MATCHES = 6;

    private GameKnowledge() {
    }

    static String lookup(Minecraft client, String query) {
        String normalized = query.toLowerCase(Locale.ROOT).strip().replace(' ', '_');
        if (normalized.isEmpty()) {
            return "Lookup query is empty.";
        }

        if (normalized.matches(".*(wood|tree|log|biome).*")) {
            return TreeBiomes.table();
        }

        List<String> blocks = new ArrayList<>();
        for (var block : BuiltInRegistries.BLOCK) {
            Identifier id = BuiltInRegistries.BLOCK.getKey(java.util.Objects.requireNonNull(block));
            if (id != null && matches(id.toString(), normalized)) {
                blocks.add(id.toString());
                if (blocks.size() == MAX_MATCHES) {
                    break;
                }
            }
        }

        List<String> items = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            Identifier id = BuiltInRegistries.ITEM.getKey(java.util.Objects.requireNonNull(item));
            if (id != null && matches(id.toString(), normalized)) {
                items.add(id.toString());
                if (items.size() == MAX_MATCHES) {
                    break;
                }
            }
        }

        List<String> recipes = new ArrayList<>();
        if (client.player != null) {
            for (var entry : client.player.getRecipeBook().getCollections().stream()
                    .flatMap(collection -> collection.getRecipes().stream()).toList()) {
                List<net.minecraft.world.item.ItemStack> results =
                        entry.display().result().resolveForStacks(ContextMap.EMPTY);
                for (var result : results) {
                    if (result.isEmpty()) {
                        continue;
                    }
                    Identifier id = BuiltInRegistries.ITEM.getKey(result.getItem());
                    if (id == null || !matches(id.toString(), normalized)) {
                        continue;
                    }
                    String ingredients = entry.craftingRequirements()
                            .map(GameKnowledge::formatIngredients)
                            .orElse("ingredient details unavailable");
                    recipes.add(id + " <- " + ingredients);
                    break;
                }
                if (recipes.size() == MAX_MATCHES) {
                    break;
                }
            }
        }

        List<String> progression = new ArrayList<>();
        for (String item : items) {
            StringBuilder entry = new StringBuilder(item);
            TechTree.useOf(item).ifPresent(use -> entry.append(" used for: ").append(use));
            TechTree.recipeFor(item).ifPresent(recipe -> entry.append("; Actionable crafts it from ")
                    .append(recipe.inputs()).append(recipe.needsTable() ? " at a crafting table" : " anywhere"));
            TechTree.sourceFor(item).ifPresent(source -> entry.append("; obtained by mining ").append(source.block())
                    .append(source.tool() == TechTree.Tier.NONE ? "" : " with a " + TechTree.pickaxeFor(source.tool())));
            if (entry.length() > item.length()) {
                progression.add(entry.toString());
            }
        }

        return "Local registry matches: blocks=" + blocks + "; items=" + items
                + "; progression knowledge=" + progression
                + "; player-known crafting recipes=" + recipes
                + ". Recipe results only include recipes currently known to the player's recipe book. "
                + "This lookup does not provide global biome or ore-generation ranges; use loaded-world observations.";
    }

    private static boolean matches(String id, String query) {
        String path = id.substring(id.indexOf(':') + 1);
        return id.toLowerCase(Locale.ROOT).contains(query) || path.contains(query)
                || id.replace(':', '_').contains(query);
    }

    private static String formatIngredients(List<Ingredient> ingredients) {
        return ingredients.stream()
                .map(ingredient -> ingredient.display().resolveForStacks(ContextMap.EMPTY).stream()
                        .map(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()))
                        .filter(java.util.Objects::nonNull)
                        .map(Identifier::toString)
                        .distinct()
                        .limit(4)
                        .toList().toString())
                .toList()
                .toString();
    }
}
