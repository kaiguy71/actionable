package dev.actionable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class ActionableLogTest {
    @Test
    void fullResponsesRemainRecoverableWithoutMultilineLogInjection() {
        String body = "{\"result\":\"" + "long response ".repeat(200) + "\"}\n[forged log]\r\t";
        String line = ActionableLog.modelResponseBody(42, "action", "claude", body);
        assertTrue(line.startsWith("[Actionable] model_response_body id=42 stage=action provider=claude body="));
        assertFalse(line.contains("\n"));
        assertFalse(line.contains("\r"));
        assertEquals(body, JsonParser.parseString(line.substring(line.indexOf(" body=") + 6)).getAsString());
    }

    @Test
    void malformedRepliesAreLoggedWithoutTryingToParseThem() {
        String body = "not JSON: { unfinished";
        String line = ActionableLog.modelResponseBody(7, "director", "ollama", body);
        assertEquals(body, JsonParser.parseString(line.substring(line.indexOf(" body=") + 6)).getAsString());
    }
}
