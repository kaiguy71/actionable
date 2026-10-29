package dev.actionable;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NetherRecipesTest {
    @Test
    void newPatternsAndIngredientsMatchTheInstalledMinecraftRecipes() throws Exception {
        var items = new ArrayList<String>();
        for (String material : List.of("iron", "diamond")) {
            for (String piece : List.of("pickaxe", "axe", "sword", "shovel", "helmet", "chestplate", "leggings", "boots")) {
                items.add(material + '_' + piece);
            }
        }
        items.addAll(List.of("bucket", "flint_and_steel", "shield", "golden_boots"));
        for (String item : items) {
            var ours = TechTree.recipeFor("minecraft:" + item).orElseThrow();
            var vanilla = resource("/data/minecraft/recipe/" + item + ".json");
            assertEquals("minecraft:" + item, vanilla.getAsJsonObject("result").get("id").getAsString());
            assertEquals(1, ours.yield());
            if (vanilla.get("type").getAsString().equals("minecraft:crafting_shapeless")) {
                assertEquals(Map.of("minecraft:iron_ingot", 1, "minecraft:flint", 1), ours.inputs());
                assertEquals(2, vanilla.getAsJsonArray("ingredients").size());
                continue;
            }
            var pattern = vanilla.getAsJsonArray("pattern");
            assertEquals(pattern.size(), ours.height(), item);
            for (int row = 0; row < pattern.size(); row++) {
                String actual = pattern.get(row).getAsString();
                assertEquals(actual.length(), ours.pattern().get(row).length(), item);
                for (int col = 0; col < actual.length(); col++) {
                    char expectedKey = actual.charAt(col), ourKey = ours.pattern().get(row).charAt(col);
                    assertEquals(expectedKey == ' ', ourKey == ' ', item + " cell " + row + ',' + col);
                    if (expectedKey == ' ') continue;
                    String ingredient = vanilla.getAsJsonObject("key").get(String.valueOf(expectedKey)).getAsString();
                    String oursIngredient = ours.keys().get(ourKey);
                    if (oursIngredient.equals(TechTree.PLANKS)) oursIngredient = "minecraft:oak_planks";
                    assertTrue(accepts(ingredient, oursIngredient), item + ": " + ingredient + " must accept " + oursIngredient);
                }
            }
        }
    }

    private static boolean accepts(String ingredient, String item) throws Exception {
        if (!ingredient.startsWith("#")) return ingredient.equals(item);
        String[] tag = ingredient.substring(1).split(":", 2);
        var values = resource("/data/" + tag[0] + "/tags/item/" + tag[1] + ".json").getAsJsonArray("values");
        for (var value : values) if (accepts(value.getAsString(), item)) return true;
        return false;
    }

    private static JsonObject resource(String path) throws Exception {
        try (var stream = NetherRecipesTest.class.getResourceAsStream(path)) {
            assertNotNull(stream, "Minecraft recipe data must be available: " + path);
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
