package dev.actionable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ActionPlannerTest {
    @Test
    void everyPlanningStageStartsWithNetherObjective() {
        for (String stage : new String[] {"director", "decomposition", "action", "judge"}) {
            String prompt = ActionPlanner.systemPrompt(stage);
            assertTrue(prompt.startsWith("GET TO THE NETHER!!!\n\nYou "), stage);
            assertTrue(prompt.contains("Return only JSON"), stage);
        }
    }

    @Test
    void promptsAllowTravelBeyondHomeAndMiningStart() {
        assertTrue(ActionPlanner.systemPrompt("action").contains("mining may go beyond the start area"));
        assertTrue(ActionPlanner.systemPrompt("action").contains("no fixed travel-distance cutoff"));
        assertTrue(ActionPlanner.systemPrompt("director").contains("Travel has no fixed home radius"));
        var movement = action("{\"type\":\"baritone\",\"command\":\"goto 1000 70 -1000\"}");
        assertEquals("goto 1000 70 -1000", movement.command());
    }

    private static ActionPlanner.PlannedAction action(String json) {
        return ActionPlanner.parsePlan("{\"summary\":\"s\",\"goal\":\"g\",\"complete\":false,\"action\":" + json + "}")
                .action().orElseThrow();
    }

    @Test
    void craftActionCarriesItemAndTotal() {
        ActionPlanner.PlannedAction craft = action("{\"type\":\"craft\",\"item\":\"minecraft:stick\",\"count\":4}");
        assertEquals("craft", craft.type());
        assertEquals("minecraft:stick", craft.block());
        assertEquals("4", craft.command());
    }

    @Test
    void craftDefaultsAndNormalisesTheItem() {
        ActionPlanner.PlannedAction craft = action("{\"type\":\"craft\",\"item\":\"Wooden_Pickaxe\"}");
        assertEquals("minecraft:wooden_pickaxe", craft.block());
        assertEquals("1", craft.command());
    }

    @Test
    void invalidCraftsAreDropped() {
        String plan = "{\"summary\":\"s\",\"goal\":\"g\",\"complete\":false,\"action\":%s}";
        assertTrue(ActionPlanner.parsePlan(String.format(plan,
                "{\"type\":\"craft\",\"item\":\"minecraft:stick\",\"count\":0}")).action().isEmpty());
        assertTrue(ActionPlanner.parsePlan(String.format(plan,
                "{\"type\":\"craft\",\"item\":\"bad item!\",\"count\":1}")).action().isEmpty());
        assertTrue(ActionPlanner.parsePlan(String.format(plan, "{\"type\":\"craft\"}")).action().isEmpty());
    }

    @Test
    void existingActionsStillParse() {
        assertEquals("mine 4 minecraft:oak_log",
                action("{\"type\":\"baritone\",\"command\":\"mine 4 minecraft:oak_log\"}").command());
        assertEquals("wait", action("{\"type\":\"wait\"}").type());
        assertEquals("mine 3 minecraft:stone", action("{\"type\":\"mine\",\"quantity\":3,\"block\":\"minecraft:stone\"}")
                .command());
    }

    @Test
    void directorReturnsGoalAndReason() {
        ActionPlanner.GoalChoice choice = ActionPlanner.parseGoal(
                "{\"goal\":\"craft 1 minecraft:wooden_pickaxe\",\"why\":\"stone needs a pickaxe\"}");
        assertEquals("craft 1 minecraft:wooden_pickaxe", choice.goal());
        assertEquals("stone needs a pickaxe", choice.why());
        assertEquals("", ActionPlanner.parseGoal("{\"goal\":\"explore\"}").why());
        assertThrows(IllegalStateException.class, () -> ActionPlanner.parseGoal("{\"goal\":\"  \"}"));
    }

    @Test
    void planStepsAreValidMineCommands() {
        // Direct execution relies on generated mine steps passing command validation.
        for (String step : ProgressPlanner.stepsFor("minecraft:torch", 16, java.util.Map.of(), false, "minecraft:oak_log")) {
            if (step.startsWith("mine ")) {
                assertEquals(Optional.of(step), ActionPlanner.validateCommand(step));
            }
        }
    }

    @Test
    void exploreIsNotACommandAndDiamondMiningIs() {
        assertEquals(Optional.empty(), ActionPlanner.validateCommand("explore"));
        assertEquals(Optional.empty(), ActionPlanner.validateCommand("Explore"));
        assertEquals(Optional.of("mine 3 minecraft:diamond"), ActionPlanner.validateCommand("mine 3 minecraft:diamond"));
        for (String stage : new String[] {"director", "decomposition", "action"}) {
            String prompt = ActionPlanner.systemPrompt(stage).toLowerCase(java.util.Locale.ROOT);
            assertFalse(prompt.contains("- explore:") || prompt.contains("goto, explore") || prompt.contains("\"explore\""),
                    stage + " prompt still offers explore");
        }
    }

    @Test
    void schemasAreValidJsonAndAllowCrafting() {
        for (String stage : new String[] {"director", "decomposition", "judge", "action"}) {
            JsonParser.parseString(PlannerSchema.forStage(stage));
        }
        JsonObject action = JsonParser.parseString(PlannerSchema.forStage("action")).getAsJsonObject()
                .getAsJsonObject("properties").getAsJsonObject("action");
        assertTrue(action.getAsJsonObject("properties").getAsJsonObject("type").get("enum").toString()
                .contains("\"craft\""));
        assertTrue(action.getAsJsonObject("properties").has("item"));
        assertTrue(action.getAsJsonObject("properties").has("count"));
        JsonObject director = JsonParser.parseString(PlannerSchema.forStage("director")).getAsJsonObject();
        assertTrue(director.get("required").toString().contains("\"why\""), "the director must explain itself");
    }

    @Test
    void breakBlockCarriesItsTarget() {
        ActionPlanner.PlannedAction action = action("{\"type\":\"break_block\",\"x\":4,\"y\":65,\"z\":-9}");
        assertEquals("break_block", action.type());
        assertEquals(4, action.x());
        assertEquals(65, action.y());
        assertEquals(-9, action.z());
        assertTrue(PlannerSchema.forStage("action").contains("\"break_block\""));
    }
}
