package dev.actionable;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Rolling record of everything the model and the mod said to each other, written out on demand by
 * {@code /actiondebug capture}. Memory is bounded: old events fall off and long texts are truncated.
 */
final class ContextCapture {
    private static final int MAX_EVENTS = 600;
    private static final int MAX_TEXT = 24_000;
    static final long ZIP_ABOVE_BYTES = 2L * 1024 * 1024;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private static final ArrayDeque<String> EVENTS = new ArrayDeque<>();
    private static final Map<String, String> SYSTEM_PROMPTS = new LinkedHashMap<>();

    private ContextCapture() {
    }

    /** Short mod-side event (chat, action, result). */
    static synchronized void event(String kind, String text) {
        add("### " + LocalDateTime.now().format(CLOCK) + " " + kind + "\n\n" + fence(text));
    }

    /** One model round trip. The system prompt is stored once per stage and referenced afterwards. */
    static synchronized void exchange(String stage, String system, String user, boolean hasImage, String response,
            long elapsedMs, String error) {
        SYSTEM_PROMPTS.putIfAbsent(stage, system);
        add("### " + LocalDateTime.now().format(CLOCK) + " model request: " + stage + " (" + elapsedMs + " ms)\n\n"
                + "System prompt: see \"System prompts\" > " + stage + "\n\nUser message"
                + (hasImage ? " (a screenshot was attached)" : "") + ":\n\n" + fence(user)
                + "\n\n" + (error == null ? "Model response:\n\n" + fence(response) : "FAILED: " + error));
    }

    static synchronized void clear() {
        EVENTS.clear();
        SYSTEM_PROMPTS.clear();
    }

    private static void add(String entry) {
        EVENTS.addLast(entry);
        while (EVENTS.size() > MAX_EVENTS) {
            EVENTS.removeFirst();
        }
    }

    private static String fence(String text) {
        String value = text == null ? "" : text;
        if (value.length() > MAX_TEXT) {
            value = value.substring(0, MAX_TEXT) + "\n... [truncated " + (value.length() - MAX_TEXT) + " chars]";
        }
        return "```text\n" + value.replace("```", "'''") + "\n```";
    }

    static Path directory(Path gameDirectory) {
        return gameDirectory.resolve("logs").resolve("actionable");
    }

    /**
     * Writes the markdown report. {@code state} is the live mod state (task, plan, history, etc.). Output larger
     * than 2 MB is written as a zip of the same name instead. Returns the path written.
     */
    static synchronized Path write(Path gameDirectory, String state) throws IOException {
        Path dir = directory(gameDirectory);
        Files.createDirectories(dir);
        String base = LocalDateTime.now().format(STAMP) + "-contextOutput";
        StringBuilder out = new StringBuilder("# Actionable context capture\n\n## Current state\n\n")
                .append(fence(state)).append("\n\n## System prompts\n");
        for (Map.Entry<String, String> prompt : SYSTEM_PROMPTS.entrySet()) {
            out.append("\n### ").append(prompt.getKey()).append("\n\n").append(fence(prompt.getValue())).append('\n');
        }
        out.append("\n## Timeline (oldest first, last ").append(MAX_EVENTS).append(" events)\n");
        for (String entry : EVENTS) {
            out.append('\n').append(entry).append('\n');
        }
        byte[] bytes = out.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= ZIP_ABOVE_BYTES) {
            Path file = dir.resolve(base + ".md");
            Files.write(file, bytes);
            return file;
        }
        Path archive = dir.resolve(base + ".zip");
        try (OutputStream file = Files.newOutputStream(archive); ZipOutputStream zip = new ZipOutputStream(file)) {
            zip.putNextEntry(new ZipEntry(base + ".md"));
            zip.write(bytes);
            zip.closeEntry();
        }
        return archive;
    }
}
