package dev.actionable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Source-level check of the EXEC-007 wiring in {@code ActionableClient.placeBlock}: it reads the
 * method body and checks source ordering, the bounded loop structure, where each failure code is
 * returned, and the gates before the placement interaction. It does not execute inventory mutation,
 * support behavior, interaction consumption, networking or server placement; RV-013A validates those
 * in game.
 */
class PlacementWiringTest {
    private static final Path CLIENT = Path.of("src/main/java/dev/actionable/ActionableClient.java");
    private static final List<String> MUTATIONS = List.of("setSelectedSlot(", "handleContainerInput(", "lookAt(",
            "useItemOn(");
    private static final String NO_SUPPORT_RETURN = "if (supportHits.isEmpty()) {"
            + " return \"BLOCK_PLACE_FAILED_NO_SUPPORT:";
    private static final String NOT_ACCEPTED_RETURN = "return \"BLOCK_PLACE_FAILED_INTERACTION_NOT_ACCEPTED:";

    private static String placeBlockBody() throws IOException {
        String source = Files.readString(CLIENT);
        int signature = source.indexOf("private String placeBlock(");
        assertTrue(signature >= 0, "placeBlock not found");
        return body(source, source.indexOf('{', signature)).replaceAll("\\s+", " ");
    }

    /** The text between the brace at {@code open} and its matching brace, skipping strings and comments. */
    private static String body(String source, int open) {
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '"' || c == '\'') {
                for (i++; i < source.length() && source.charAt(i) != c; i++) {
                    if (source.charAt(i) == '\\') {
                        i++;
                    }
                }
            } else if (source.startsWith("//", i)) {
                int newline = source.indexOf('\n', i);
                if (newline < 0) {
                    break;
                }
                i = newline;
            } else if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                if (end < 0) {
                    break;
                }
                i = end + 1;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return source.substring(open + 1, i);
            }
        }
        throw new AssertionError("unbalanced braces after index " + open);
    }

    private static int count(String text, String marker) {
        return text.split(java.util.regex.Pattern.quote(marker), -1).length - 1;
    }

    @Test
    void placementUsesSourceMenuSlotBeforeHotbarButton() throws IOException {
        String body = placeBlockBody();
        assertEquals(1, count(body, "handleContainerInput("), "one SWAP call");
        assertTrue(body.contains("gameMode.handleContainerInput(containerId, preparation.sourceMenuSlot(),"
                        + " preparation.selectedHotbarIndex(), ContainerInput.SWAP, player)"),
                "SWAP passes the container ID, the source menu slot, then the hotbar button");
        int swapGuard = body.indexOf("if (preparation.status().requiresSwap())");
        assertTrue(swapGuard >= 0 && swapGuard < body.indexOf("handleContainerInput("),
                "SWAP is issued only when the preparation requires it");
    }

    @Test
    void placementInteractionIsReachableOnlyAfterVerifiedPreparation() throws IOException {
        String body = placeBlockBody();
        int failureGate = body.indexOf("if (preparation.status().isFailure()) {"
                + " return PlacementPreparation.failureResult(preparation.status(), action.block());");
        int swap = body.indexOf("handleContainerInput(");
        int verifyGate = body.indexOf("if (verified != PlacementPreparation.Status.READY) {"
                + " return PlacementPreparation.failureResult(verified, action.block());");
        int interaction = body.indexOf("useItemOn(");
        assertTrue(failureGate >= 0 && failureGate < swap, "failed preparation returns before any SWAP");
        assertTrue(verifyGate > swap && verifyGate < interaction, "verification gate sits between SWAP and use");
        // One interaction call site, with at most one execution per retained support candidate.
        assertEquals(1, count(body, "useItemOn("), "one placement interaction call site");
        assertEquals(1, count(body, "setSelectedSlot("), "selection happens once");
        assertTrue(body.indexOf("setSelectedSlot(") < verifyGate, "no reselection after verification");
        int look = body.indexOf("lookAt(");
        assertEquals(1, count(body, "lookAt("), "one look toward the support");
        assertTrue(look > verifyGate && look < interaction, "the player turns only after verification");
        assertTrue(body.indexOf("PlacementPreparation.placementIssued(") > interaction,
                "the request-issued result follows the interaction");
    }

    @Test
    void supportIsResolvedBeforeInventoryMutation() throws IOException {
        String body = placeBlockBody();
        int search = body.indexOf("for (Direction offset : Direction.values()) {");
        assertTrue(search >= 0, "support search loop present");
        int loopOpen = body.indexOf('{', search);
        String searchLoop = body(body, loopOpen);
        int loopEnd = loopOpen + 1 + searchLoop.length();
        for (String mutation : MUTATIONS) {
            assertFalse(searchLoop.contains(mutation), "the support search must not call " + mutation);
            assertTrue(body.indexOf(mutation) > loopEnd, mutation + " must follow the support search");
        }
    }

    @Test
    void missingSupportReturnsBeforeSelectionOrSwap() throws IOException {
        String body = placeBlockBody();
        int noSupport = body.indexOf(NO_SUPPORT_RETURN);
        assertTrue(noSupport >= 0, "a missing support returns BLOCK_PLACE_FAILED_NO_SUPPORT");
        for (String mutation : MUTATIONS) {
            assertTrue(noSupport < body.indexOf(mutation), "the no-support return precedes " + mutation);
        }
    }

    @Test
    void allResolvedSupportCandidatesMayBeTriedAfterPreparation() throws IOException {
        String body = placeBlockBody();
        int collection = body.indexOf(
                "java.util.List<BlockHitResult> supportHits = new java.util.ArrayList<>(Direction.values().length);");
        assertTrue(collection >= 0, "candidates are kept in a list bounded by the direction count");
        int searchOpen = body.indexOf('{', body.indexOf("for (Direction offset : Direction.values()) {"));
        String searchLoop = body(body, searchOpen);
        assertTrue(collection < searchOpen, "the candidate list is created before the search");
        assertTrue(searchLoop.contains("supportHits.add(new BlockHitResult("), "the search keeps every valid candidate");
        assertFalse(searchLoop.contains("break;"), "the search does not stop at the first candidate");

        int attemptLoop = body.indexOf("for (BlockHitResult supportHit : supportHits) {");
        assertTrue(attemptLoop >= 0, "the interaction loops over the retained candidates");
        int attemptOpen = body.indexOf('{', attemptLoop);
        String attempts = body(body, attemptOpen);
        int attemptEnd = attemptOpen + 1 + attempts.length();
        for (String preparation : List.of("handleContainerInput(", "setSelectedSlot(", "PlacementPreparation.verify(")) {
            assertTrue(body.indexOf(preparation) < attemptLoop, preparation + " happens once, before the attempts");
            assertFalse(attempts.contains(preparation), preparation + " is not repeated per candidate");
        }
        assertTrue(attempts.indexOf("lookAt(") >= 0 && attempts.indexOf("lookAt(") < attempts.indexOf("useItemOn("),
                "each attempt looks at its candidate before interacting");
        assertTrue(attempts.contains("if (result.consumesAction()) {"
                        + " return PlacementPreparation.placementIssued(action.block(), String.valueOf(target)); }"),
                "a consumed interaction returns the request-issued result");
        assertFalse(attempts.contains("BLOCK_PLACE_FAILED"), "a rejected candidate moves on to the next one");
        assertTrue(body.indexOf(NOT_ACCEPTED_RETURN) > attemptEnd, "all-rejected is reported after the loop");
    }

    @Test
    void rejectedInteractionsAreNotReportedAsNoSupport() throws IOException {
        String body = placeBlockBody();
        assertEquals(1, count(body, "BLOCK_PLACE_FAILED_NO_SUPPORT"), "one no-support result");
        int noSupport = body.indexOf(NO_SUPPORT_RETURN);
        assertTrue(noSupport >= 0, "no support means an empty candidate list");
        for (String mutation : MUTATIONS) {
            assertTrue(noSupport < body.indexOf(mutation), "the no-support return precedes " + mutation);
        }
        int interaction = body.indexOf("useItemOn(");
        assertFalse(body.substring(interaction).contains("BLOCK_PLACE_FAILED_NO_SUPPORT"),
                "no path after an interaction reports no support");
        assertEquals(1, count(body, "BLOCK_PLACE_FAILED_INTERACTION_NOT_ACCEPTED"), "one all-rejected result");
        assertTrue(body.indexOf(NOT_ACCEPTED_RETURN) > interaction, "all-rejected follows the interactions");
    }
}
