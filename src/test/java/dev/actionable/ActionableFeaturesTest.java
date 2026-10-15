package dev.actionable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ActionableFeaturesTest {
    @Test
    void repeatedStepsCollapseToLargestTotal() {
        List<String> steps = ProgressPlanner.normalizeSteps(List.of(
                "mine 2 minecraft:spruce_log", "mine 2 spruce_log", "mine 18 minecraft:birch_log",
                "craft 4 minecraft:birch_planks", "mine 6 spruce_log"));
        assertEquals(List.of("mine 6 minecraft:spruce_log", "mine 18 minecraft:birch_log",
                "craft 4 minecraft:birch_planks"), steps);
    }

    @Test
    void nightOnTheSurfaceIsNotDanger() {
        SafetyAssessment.Result result = SafetyAssessment.assess(new SafetyAssessment.Inputs(
                0, true, 0, true, 20, 20, 0, 0, Map.of("minecraft:oak_log", 4)));
        assertFalse(result.retreat());
        assertTrue(result.level() != SafetyAssessment.Level.DANGER);
        assertTrue(result.describe().contains("do not search for light"));
    }

    @Test
    void progressionFileLoadsInOrder() {
        String text = ProgressPlanner.progression();
        assertTrue(text.indexOf("1 | Get wood") >= 0);
        assertTrue(text.indexOf("Ender Dragon") > text.indexOf("Get wood"));
        assertFalse(text.contains("# "));
    }

    @Test
    void parsesInteractionActions() {
        var use = ActionPlanner.parseAction(JsonParser.parseString(
                "{\"type\":\"use_block\",\"x\":1,\"y\":64,\"z\":-3}").getAsJsonObject());
        assertEquals("use_block", use.orElseThrow().type());
        var menu = ActionPlanner.parseAction(JsonParser.parseString(
                "{\"type\":\"menu\",\"op\":\"move\",\"slot\":3,\"to_slot\":30}").getAsJsonObject());
        assertEquals(3, menu.orElseThrow().x());
        assertEquals(30, menu.orElseThrow().y());
        assertTrue(ActionPlanner.parseAction(JsonParser.parseString(
                "{\"type\":\"menu\",\"op\":\"move\",\"slot\":3}").getAsJsonObject()).isEmpty());
        assertEquals("screenshot", ActionPlanner.parseAction(JsonParser.parseString(
                "{\"type\":\"screenshot\"}").getAsJsonObject()).orElseThrow().type());
    }
}
