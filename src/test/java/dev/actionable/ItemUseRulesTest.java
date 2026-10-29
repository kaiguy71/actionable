package dev.actionable;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class ItemUseRulesTest {
    @Test
    void acceptsOnlyValidatedItemFaceAndBoundedIntegerCoordinates() {
        assertTrue(ItemUseRules.valid("minecraft:bucket", "up", 0, 64, 0));
        for (String coordinate : new String[] {"1.5", "2147483648", "-9223372036854775808", "\"0\""}) {
            var action = JsonParser.parseString("{\"type\":\"use_item\",\"item\":\"minecraft:bucket\",\"x\":"
                    + coordinate + ",\"y\":64,\"z\":0}").getAsJsonObject();
            assertTrue(ActionPlanner.parseAction(action).isEmpty(), coordinate);
        }
        assertFalse(ItemUseRules.valid("minecraft:bucket", "up", Long.MIN_VALUE, 0, 0));
        assertFalse(ItemUseRules.valid("minecraft:tnt", "up", 0, 64, 0));
        assertFalse(ItemUseRules.valid("minecraft:bucket", "up;cancel", 0, 64, 0));
    }

    @Test
    void acceptsPortalIgnitionAndTracksFaceAndCoordinatesInRepeatGuard() {
        var action = ActionPlanner.parseAction(JsonParser.parseString("""
                {"type":"use_item","item":"minecraft:flint_and_steel","x":2,"y":72,"z":-50,"face":"up"}
                """).getAsJsonObject()).orElseThrow();
        assertEquals("use_item:minecraft:flint_and_steel:up@2,72,-50", ActionLoopGuard.signature(action));
        assertTrue(PlannerSchema.forStage("action").contains("use_item"));
    }

    @Test
    void rejectsWaterInNetherLavaAtFeetAndOccupiedDestinations() {
        assertFalse(ItemUseRules.mayPlace("minecraft:water_bucket", true, true, false, 4));
        assertTrue(ItemUseRules.mayPlace("minecraft:water_bucket", false, true, false, 4));
        assertFalse(ItemUseRules.mayPlace("minecraft:lava_bucket", false, true, false, 2));
        assertFalse(ItemUseRules.mayPlace("minecraft:lava_bucket", false, true, true, 4));
        assertFalse(ItemUseRules.mayPlace("minecraft:water_bucket", false, false, false, 4));
    }

    @Test
    void neverUsesExplosiveBedsInNetherOrEnd() {
        assertFalse(ItemUseRules.mayUseBed("minecraft:the_nether"));
        assertFalse(ItemUseRules.mayUseBed("minecraft:the_end"));
        assertTrue(ItemUseRules.mayUseBed("minecraft:overworld"));
    }
}
