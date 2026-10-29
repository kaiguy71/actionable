package dev.actionable;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class OllamaProtocolTest {
    @Test
    void allStagesUseExplicitContextReplyAllowanceAndStructuredOutput() {
        for (String stage : new String[] {"action", "director", "decomposition", "judge"}) {
            JsonObject body = new JsonObject();
            OllamaProtocol.configure(body, stage);
            assertTrue(body.get("format").isJsonObject());
            assertEquals(8192, body.getAsJsonObject("options").get("num_ctx").getAsInt());
            assertEquals(768, body.getAsJsonObject("options").get("num_predict").getAsInt());
            assertTrue(body.getAsJsonObject("format").has("required"));
        }
    }

    @Test
    void lengthLimitedReplyIsReportedBeforeTryingToParseBrokenJson() {
        var error = assertThrows(IllegalStateException.class, () -> OllamaProtocol.content(
                "{\"done_reason\":\"length\",\"prompt_eval_count\":4091,\"eval_count\":5,"
                + "\"message\":{\"content\":\"{ unfinished\"}}"));
        assertTrue(error.getMessage().startsWith("OLLAMA_RESPONSE_TRUNCATED"));
        assertTrue(error.getMessage().contains("4091"));
    }

    @Test
    void completeRepliesPreserveContentAndMissingContentIsAnError() {
        assertEquals("{}", OllamaProtocol.content("{\"done_reason\":\"stop\",\"message\":{\"content\":\"{}\"}}"));
        assertThrows(IllegalStateException.class, () -> OllamaProtocol.content("{\"message\":{}}"));
        assertThrows(IllegalStateException.class, () -> OllamaProtocol.content("{\"error\":\"model not found\"}"));
    }
}
