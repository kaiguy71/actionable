package dev.actionable;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Explicit inference settings and envelope validation, independent of Minecraft. */
final class OllamaProtocol {
    static final int CONTEXT_TOKENS = 8192;
    static final int RESPONSE_TOKENS = 768;
    private OllamaProtocol() { }

    static void configure(JsonObject body, String stage) {
        body.add("format", JsonParser.parseString(PlannerSchema.forStage(stage)));
        JsonObject options = new JsonObject();
        options.addProperty("num_ctx", CONTEXT_TOKENS);
        options.addProperty("num_predict", RESPONSE_TOKENS);
        options.addProperty("temperature", 0.2);
        body.add("options", options);
    }

    static String content(String response) {
        JsonObject envelope = JsonParser.parseString(response).getAsJsonObject();
        if (envelope.has("error")) {
            throw new IllegalStateException("OLLAMA_RESPONSE_ERROR: " + envelope.get("error").getAsString());
        }
        if (envelope.has("done_reason") && envelope.get("done_reason").getAsString().equals("length")) {
            throw new IllegalStateException("OLLAMA_RESPONSE_TRUNCATED: response reached its token limit; "
                    + "prompt_tokens=" + envelope.get("prompt_eval_count") + ", output_tokens=" + envelope.get("eval_count"));
        }
        if (!envelope.has("message") || !envelope.get("message").isJsonObject()
                || !envelope.getAsJsonObject("message").has("content")
                || !envelope.getAsJsonObject("message").get("content").isJsonPrimitive()) {
            throw new IllegalStateException("OLLAMA_RESPONSE_INVALID: missing message.content");
        }
        String content = envelope.getAsJsonObject("message").get("content").getAsString();
        if (content.isBlank()) {
            throw new IllegalStateException("OLLAMA_RESPONSE_INVALID: empty message.content");
        }
        return content;
    }
}
