package dev.actionable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TacticsTest {
    @TempDir
    Path temp;
    private HttpServer server;

    @BeforeEach
    void setUp() {
        GemmaLog.setFile(temp.resolve("logs/gemma_agent.log"));
        GemmaConfig.setStore(temp.resolve("config/endpoint.txt"));
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static TacticalState state(String mob, double distance, boolean charging, boolean drinking,
            boolean shield, boolean gold, boolean water) {
        return new TacticalState(20, List.of("minecraft:stone_sword"), mob, distance, "idle", 0, 64, 0, 10, 64, 0,
                charging, drinking, false, shield, gold, water, false, null);
    }

    private void serve(String body, long delayMs) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        assertTrue(GemmaConfig.setEndpoint("127.0.0.1:" + server.getAddress().getPort()));
    }

    private static String envelope(String content) {
        com.google.gson.JsonObject message = new com.google.gson.JsonObject();
        message.addProperty("content", content);
        com.google.gson.JsonObject root = new com.google.gson.JsonObject();
        root.add("message", message);
        return root.toString();
    }

    @Test
    void ollamaAsyncPushNoBlock() throws Exception {
        serve(envelope("{\"tactic\":\"RETREAT\",\"reason\":\"low\"}"), 60);
        long started = System.nanoTime();
        var future = new GemmaClient().decide(state("skeleton", 10, false, false, true, false, false));
        long callMs = (System.nanoTime() - started) / 1_000_000;
        assertTrue(callMs < 50, "decide() must return immediately, took " + callMs);
        var outcome = future.get(2, TimeUnit.SECONDS);
        assertTrue(outcome.ok());
        assertEquals(Tactic.RETREAT, outcome.decision().tactic());
    }

    @Test
    void requestBodyCarriesStateAndAllowedTactics() {
        String body = new GemmaClient().requestBody(state("witch", 6, false, true, true, false, false));
        assertTrue(body.contains("hotbar") && body.contains("minecraft:stone_sword"));
        assertTrue(body.contains("RUSH") && body.contains("baritone"));
    }

    @Test
    void ollamaTimeoutFallback() throws Exception {
        serve(envelope("{\"tactic\":\"RETREAT\"}"), 400);
        var outcome = new GemmaClient().decide(state("skeleton", 10, false, false, true, false, false))
                .get(2, TimeUnit.SECONDS);
        assertFalse(outcome.ok());
        assertTrue(Files.readString(GemmaLog.file()).contains("ollama_unavailable"));
        TacticBook.Plan hold = TacticBook.Plan.hold();
        assertTrue(hold.raiseShield() && hold.cancelPathing());
        assertEquals(Tactic.DEFENSIVE_HOLD, hold.tactic());
    }

    @Test
    void logMalformedGemmaOutput() throws Exception {
        var outcome = GemmaClient.parseDecision("{\n  \"tactic\": \"RETREAT\",\n  \"reason\": oops\n}", "skeleton");
        assertFalse(outcome.ok());
        String log = Files.readString(GemmaLog.file());
        assertTrue(log.contains("invalid_gemma_payload line=3"), log);
        assertTrue(log.contains("1: {") && log.contains("3:   \"reason\": oops"), log);
    }

    @Test
    void malformedEnvelopeIsLoggedNotThrown() throws Exception {
        serve("{not json", 0);
        var outcome = new GemmaClient().decide(state("skeleton", 10, false, false, true, false, false))
                .get(2, TimeUnit.SECONDS);
        assertFalse(outcome.ok());
        assertTrue(Files.readString(GemmaLog.file()).contains("invalid_gemma_payload"));
    }

    @Test
    void adminOllamaEndpointConfig() throws Exception {
        assertTrue(GemmaConfig.setEndpoint("http://example.test:8080"));
        assertEquals("http://example.test:8080/api/chat", GemmaConfig.chatUri().toString());
        assertTrue(GemmaConfig.setEndpoint("10.0.0.5:11434"));
        assertEquals("http://10.0.0.5:11434", GemmaConfig.endpoint());
        assertFalse(GemmaConfig.setEndpoint("ftp://bad"));
        assertFalse(GemmaConfig.setEndpoint("   "));
        assertEquals("http://10.0.0.5:11434", Files.readString(temp.resolve("config/endpoint.txt")));
    }

    @Test
    void enforceReachAndCooldown() {
        assertTrue(ReachRule.mayAttack(4.5, 1.0F));
        assertFalse(ReachRule.mayAttack(4.51, 1.0F));
        assertFalse(ReachRule.mayAttack(2.0, 0.5F));
        assertTrue(ReachRule.cooldownReady(0.9F));
    }

    @Test
    void baritoneWaypointExecution() {
        var plan = TacticBook.translate(state("skeleton", 10, false, false, true, false, false), Tactic.RETREAT);
        assertEquals(List.of("goto -16 64 0"), plan.baritone());
        plan = TacticBook.translate(state("skeleton", 10, false, false, true, false, false), Tactic.APPROACH);
        assertEquals(List.of("goto 10 64 0"), plan.baritone());
    }

    @Test
    void spiderEndermanTerrainTactics() {
        assertEquals(Tactic.OVERHANG_SHELTER, TacticBook.defaultFor(state("spider", 5, false, false, false, false, false)));
        assertEquals(Tactic.WATER_REFUGE, TacticBook.defaultFor(state("enderman", 5, false, false, false, false, true)));
        assertEquals(List.of("tunnel 2 1 2"), TacticBook.translate(
                state("spider", 5, false, false, false, false, false), Tactic.OVERHANG_SHELTER).baritone());
        assertEquals(List.of("goto -2 67 0"), TacticBook.translate(
                state("spider", 5, false, false, false, false, false), Tactic.HIGH_GROUND).baritone());
        assertEquals(List.of("goto water"), TacticBook.translate(
                state("enderman", 5, false, false, false, false, true), Tactic.WATER_REFUGE).baritone());
    }

    @Test
    void shulkerWardenCoverTactics() {
        assertEquals(Tactic.COVER_PILLAR, TacticBook.defaultFor(state("shulker", 12, false, false, false, false, false)));
        assertEquals(Tactic.BREAK_LINE_OF_SIGHT,
                TacticBook.enforce(state("warden", 12, true, false, false, false, false), Tactic.RETREAT));
        var withCover = new TacticalState(20, List.of(), "shulker", 10, "idle", 0, 64, 0, 10, 64, 0,
                false, false, true, false, false, false, false, new int[] {4, 64, 0});
        // Behind the column as seen from the shooter at x=10.
        assertEquals(List.of("goto 3 64 0"), TacticBook.translate(withCover, Tactic.COVER_PILLAR).baritone());
        var leave = TacticBook.translate(state("warden", 12, false, false, false, false, false), Tactic.LEAVE_DEEP_DARK);
        assertTrue(leave.sneak());
        assertTrue(leave.baritone().get(0).contains("-220"));
        assertEquals(Tactic.LEAVE_DEEP_DARK, TacticBook.defaultFor(state("sculk_shrieker", 8, false, false, false, false, false)));
    }

    @Test
    void skeletonWitchRushTactics() {
        var skeleton = TacticBook.translate(state("skeleton", 10, false, false, true, false, false), Tactic.SHIELD_ADVANCE);
        assertTrue(skeleton.raiseShield());
        assertEquals(List.of("goto 10 64 0"), skeleton.baritone());
        assertFalse(TacticBook.translate(state("skeleton", 10, false, false, false, false, false),
                Tactic.SHIELD_ADVANCE).raiseShield());
        var witch = state("witch", 7, false, true, true, false, false);
        assertEquals(Tactic.RUSH, TacticBook.enforce(witch, Tactic.RETREAT));
        var rush = TacticBook.translate(witch, Tactic.RUSH);
        assertTrue(rush.sprint() && rush.cancelPathing());
    }

    @Test
    void netherMobTactics() {
        var piglin = state("piglin", 8, false, false, false, false, false);
        assertEquals(Tactic.EQUIP_GOLD, TacticBook.enforce(piglin, Tactic.CONTINUE));
        assertTrue(TacticBook.translate(piglin, Tactic.EQUIP_GOLD).equipGold());
        assertEquals(Tactic.KITE, TacticBook.defaultFor(state("piglin_brute", 5, false, false, false, true, false)));
        var hoglin = state("hoglin", 6, true, false, false, true, false);
        assertEquals(Tactic.SIDESTEP, TacticBook.enforce(hoglin, Tactic.APPROACH));
        // Perpendicular to the charge line (x axis), three blocks to the side.
        assertEquals(List.of("goto 0 64 -3"), TacticBook.translate(hoglin, Tactic.SIDESTEP).baritone());
    }

    @Test
    void blazeGhastStrafeTactics() {
        var blaze = state("blaze", 10, false, false, false, true, false);
        assertEquals(Tactic.STRAFE_COVER, TacticBook.defaultFor(blaze));
        assertEquals(List.of("goto 0 64 -4"), TacticBook.translate(blaze, Tactic.STRAFE_COVER).baritone());
    }

    @Test
    void raidMobPrioritization() {
        assertTrue(TacticBook.raidPriority("minecraft:evoker") < TacticBook.raidPriority("pillager"));
        assertTrue(TacticBook.raidPriority("pillager") < TacticBook.raidPriority("vindicator"));
        assertTrue(TacticBook.raidPriority("vindicator") < TacticBook.raidPriority("ravager"));
        assertTrue(TacticBook.translate(state("pillager", 12, false, false, true, false, false),
                Tactic.INTERCEPT_PRIORITY).raiseShield());
    }

    @Test
    void illegalGemmaTacticIsReplacedByMobDefault() {
        assertEquals(Tactic.COVER_PILLAR,
                TacticBook.enforce(state("shulker", 12, false, false, false, false, false), Tactic.KITE));
    }

    @Test
    void toggleAgentDebugHud() {
        assertEquals(Tactic.DEFENSIVE_HOLD, Tactic.parse("defensive-hold").orElseThrow());
        assertTrue(Tactic.parse("fly_away").isEmpty());
    }
}
