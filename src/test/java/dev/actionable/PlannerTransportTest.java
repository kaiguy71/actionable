package dev.actionable;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PlannerTransportTest {
    @Test
    void actionRequestSendsSchemaAndContextSettingsAndPreservesCurrentObservation() throws Exception {
        var captured = new AtomicReference<JsonObject>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            captured.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8)).getAsJsonObject());
            byte[] reply = ("{\"done_reason\":\"stop\",\"message\":{\"content\":"
                    + "\"{\\\"summary\\\":\\\"wait\\\",\\\"goal\\\":\\\"wood\\\",\\\"complete\\\":false,"
                    + "\\\"action\\\":{\\\"type\\\":\\\"wait\\\"}}\"}}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        String oldProvider = System.getProperty("actionable.model.provider");
        String oldUrl = System.getProperty("actionable.ollama.url");
        server.start();
        try {
            System.setProperty("actionable.model.provider", "ollama");
            System.setProperty("actionable.ollama.url", "http://127.0.0.1:" + server.getAddress().getPort() + "/api/chat");
            var plan = new ActionPlanner().plan("mine 20 minecraft:oak_log", "inventory={oak_log=7}; air=300/300",
                    "mine", "still gathering", "CURRENT STEP mine 20 minecraft:oak_log", "history ".repeat(800))
                    .get(10, TimeUnit.SECONDS);
            assertEquals("wait", plan.action().orElseThrow().type());
            var body = captured.get();
            assertTrue(body.get("format").isJsonObject());
            assertEquals(8192, body.getAsJsonObject("options").get("num_ctx").getAsInt());
            var messages = body.getAsJsonArray("messages");
            assertTrue(messages.get(0).getAsJsonObject().get("content").getAsString().startsWith("GET TO THE NETHER!!!"));
            assertTrue(messages.get(1).getAsJsonObject().get("content").getAsString().endsWith("inventory={oak_log=7}; air=300/300"));
        } finally {
            server.stop(0);
            restore("actionable.model.provider", oldProvider);
            restore("actionable.ollama.url", oldUrl);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key); else System.setProperty(key, value);
    }
}
