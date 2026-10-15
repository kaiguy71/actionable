package dev.actionable;

/** Schemas constrain model output; gameplay checks still validate every requested action. */
final class PlannerSchema {
    private PlannerSchema() { }

    static String forStage(String stage) {
        return switch (stage) {
            case "director" -> """
                    {"type":"object","properties":{"goal":{"type":"string","minLength":1},
                    "why":{"type":"string","minLength":1}},"required":["goal","why"],"additionalProperties":false}
                    """;
            case "decomposition" -> """
                    {"type":"object","properties":{"steps":{"type":"array","minItems":1,"maxItems":8,
                    "items":{"type":"string","minLength":1}}},"required":["steps"],"additionalProperties":false}
                    """;
            case "judge" -> """
                    {"type":"object","properties":{"done":{"type":"boolean"},"why":{"type":"string"}},
                    "required":["done"],"additionalProperties":false}
                    """;
            case "action" -> """
                    {"type":"object","properties":{"summary":{"type":"string"},"goal":{"type":"string"},
                    "complete":{"type":"boolean"},"step_complete":{"type":"boolean"},"action":{
                    "type":"object","properties":{"type":{"type":"string","enum":["baritone","wait",
                    "lookup","place_block","jump_place","attack","craft","use_block","menu","screenshot"]},
                    "op":{"type":"string","enum":["read","quick_move","move","drop","close"]},
                    "slot":{"type":"integer"},"to_slot":{"type":"integer"},"command":{"type":"string"},
                    "query":{"type":"string"},"block":{"type":"string"},"item":{"type":"string"},
                    "count":{"type":"integer","minimum":1,"maximum":256},"x":{"type":"integer"},
                    "y":{"type":"integer"},"z":{"type":"integer"},
                    "entity_id":{"type":"integer","minimum":0,"maximum":2147483647}},
                    "required":["type"],"additionalProperties":false}},
                    "required":["summary","goal","complete"],"additionalProperties":false}
                    """;
            default -> throw new IllegalArgumentException("Unknown planner stage: " + stage);
        };
    }
}
