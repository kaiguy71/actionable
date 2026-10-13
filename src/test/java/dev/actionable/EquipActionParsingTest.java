package dev.actionable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EquipActionParsingTest {
    @Test
    void parsesEquipWithItemField() {
        var plan = ActionPlanner.parsePlan(
                "{\"summary\":\"s\",\"goal\":\"g\",\"complete\":false,"
                        + "\"action\":{\"type\":\"equip\",\"item\":\"minecraft:iron_sword\"}}");
        var action = plan.action().orElseThrow();
        assertEquals("equip", action.type());
        assertEquals("minecraft:iron_sword", action.command());
    }

    @Test
    void acceptsBlockFieldAsFallbackFromSmallModels() {
        var plan = ActionPlanner.parsePlan(
                "{\"summary\":\"s\",\"goal\":\"g\",\"complete\":false,"
                        + "\"action\":{\"type\":\"equip\",\"block\":\"minecraft:shield\"}}");
        assertEquals("minecraft:shield", plan.action().orElseThrow().command());
    }

    @Test
    void rejectsMalformedItemIds() {
        for (String bad : new String[] {"iron sword", "minecraft:iron_sword; op me", ""}) {
            var plan = ActionPlanner.parsePlan(
                    "{\"summary\":\"s\",\"goal\":\"g\",\"complete\":false,"
                            + "\"action\":{\"type\":\"equip\",\"item\":\"" + bad + "\"}}");
            assertTrue(plan.action().isEmpty(), bad);
        }
    }

    @Test
    void equipWithoutItemIsRejected() {
        var plan = ActionPlanner.parsePlan(
                "{\"summary\":\"s\",\"goal\":\"g\",\"complete\":false,\"action\":{\"type\":\"equip\"}}");
        assertTrue(plan.action().isEmpty());
    }
}
