package dev.actionable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/** Writes the Gemma agent's own log (FR-12): invalid payloads, connection drops and 1-based line numbers. */
final class GemmaLog {
    private static final int MAX_LINES = 25;
    private static volatile Path file = Path.of("logs", "gemma_agent.log");

    private GemmaLog() { }

    static void setFile(Path path) {
        file = path;
    }

    static Path file() {
        return file;
    }

    static void info(String message) {
        write("INFO", message);
    }

    static void warn(String message) {
        write("WARN", message);
    }

    /** Logs a rejected payload with every line numbered from 1, plus the line the parser gave up on. */
    static void invalidPayload(String payload, int errorLine, String error) {
        StringBuilder text = new StringBuilder("invalid_gemma_payload line=").append(errorLine)
                .append(" error=").append(ActionableLog.text(error));
        String[] lines = payload == null ? new String[0] : payload.split("\\R", -1);
        for (int index = 0; index < Math.min(lines.length, MAX_LINES); index++) {
            text.append("\n  ").append(index + 1).append(": ").append(ActionableLog.text(lines[index]));
        }
        write("WARN", text.toString());
    }

    private static synchronized void write(String level, String message) {
        try {
            Path path = file;
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, Instant.now() + " [" + level + "] " + message + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            ActionableLog.LOGGER.warn("[Actionable] gemma_log_failed {}", ActionableLog.text(e.toString()));
        }
    }
}
