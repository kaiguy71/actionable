package dev.actionable;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Stateless, tool-free Claude print requests, executed on the planner's worker thread. */
final class ClaudeCli {
    private ClaudeCli() {
    }

    static String request(String system, String prompt) {
        return request(system, prompt, null);
    }

    static String request(String system, String prompt, String schema) {
        Path directory = null;
        Process process = null;
        try {
            directory = Files.createTempDirectory("actionable-claude-");
            Path input = directory.resolve("input.txt");
            Path rules = directory.resolve("system.txt");
            Path output = directory.resolve("output.json");
            Path errors = directory.resolve("stderr.txt");
            Path mcp = directory.resolve("mcp.json");
            Files.writeString(input, prompt, StandardCharsets.UTF_8);
            Files.writeString(rules, system, StandardCharsets.UTF_8);
            Files.writeString(mcp, "{\"mcpServers\":{}}", StandardCharsets.UTF_8);

            ProcessBuilder builder = new ProcessBuilder(
                    System.getProperty("actionable.claude.command", "claude"),
                    "-p", "--model", System.getProperty("actionable.claude.model", "claude-sonnet-5-5"),
                    "--output-format", "json", "--system-prompt-file", rules.toString(),
                    "--tools", "", "--strict-mcp-config", "--mcp-config", mcp.toString(),
                    "--no-session-persistence", "--setting-sources", "user")
                    .directory(directory.toFile())
                    .redirectInput(input.toFile())
                    .redirectOutput(output.toFile())
                    .redirectError(errors.toFile());
            if (schema != null) {
                builder.command().add("--json-schema");
                String compactSchema = JsonParser.parseString(schema).toString();
                // Windows' native argv parser consumes unescaped quotes in inline JSON.
                if (System.getProperty("os.name", "").startsWith("Windows")) {
                    compactSchema = compactSchema.replace("\"", "\\\"");
                }
                builder.command().add(compactSchema);
            }
            // Do not inherit a parent Claude Code session's nesting marker.
            builder.environment().remove("CLAUDECODE");
            process = builder.start();
            if (!process.waitFor(180, TimeUnit.SECONDS)) {
                throw new IllegalStateException("CLAUDE_TIMEOUT: Claude did not respond within 180 seconds");
            }
            if (process.exitValue() != 0) {
                String detail = Files.readString(errors, StandardCharsets.UTF_8).strip();
                if (detail.isEmpty()) {
                    detail = Files.readString(output, StandardCharsets.UTF_8).strip();
                }
                throw new IllegalStateException("CLAUDE_REQUEST_FAILED: exit " + process.exitValue()
                        + ": " + detail.substring(0, Math.min(detail.length(), 500)));
            }
            return parseResponse(Files.readString(output, StandardCharsets.UTF_8));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("CLAUDE_REQUEST_FAILED: request interrupted");
        } catch (IOException exception) {
            throw new IllegalStateException("CLAUDE_REQUEST_FAILED: " + exception.getMessage()
                    + "; check actionable.claude.command and Claude login");
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(child -> child.destroyForcibly());
                process.destroyForcibly();
                try {
                    process.waitFor(5, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            if (directory != null) {
                for (String name : new String[]{"input.txt", "system.txt", "output.json", "stderr.txt", "mcp.json"}) {
                    try {
                        Files.deleteIfExists(directory.resolve(name));
                    } catch (IOException ignored) {
                        // Cleanup must not hide the planning result or its error.
                    }
                }
                try {
                    Files.deleteIfExists(directory);
                } catch (IOException ignored) {
                    // The CLI may leave additional files in its temporary working directory.
                }
            }
        }
    }

    static String parseResponse(String output) {
        try {
            return parseEnvelope(output);
        } catch (com.google.gson.JsonParseException | IllegalStateException | UnsupportedOperationException exception) {
            if (exception.getMessage() != null && exception.getMessage().startsWith("CLAUDE_")) {
                throw exception;
            }
            throw new IllegalStateException("CLAUDE_INVALID_JSON: expected a JSON object in structured_output or result"
                    + " (CLI output length=" + output.length() + ")");
        }
    }

    private static String parseEnvelope(String output) {
        JsonObject envelope = JsonParser.parseString(output).getAsJsonObject();
        if (envelope.has("is_error") && envelope.get("is_error").getAsBoolean()) {
            String subtype = envelope.has("subtype") ? envelope.get("subtype").getAsString() : "unspecified";
            throw new IllegalStateException("CLAUDE_REQUEST_FAILED: CLI error subtype=" + subtype);
        }
        if (envelope.has("structured_output") && !envelope.get("structured_output").isJsonNull()) {
            if (!envelope.get("structured_output").isJsonObject()) {
                throw new IllegalStateException("CLAUDE_INVALID_JSON: structured_output must be an object");
            }
            return envelope.getAsJsonObject("structured_output").toString();
        }
        if (!envelope.has("result") || !envelope.get("result").isJsonPrimitive()) {
            throw new IllegalStateException("CLAUDE_REQUEST_FAILED: response has no text result");
        }
        String result = envelope.get("result").getAsString().strip();
        if (result.startsWith("```") && result.endsWith("```") && result.contains("\n")) {
            result = result.substring(result.indexOf('\n') + 1, result.length() - 3).strip();
        }
        // Every planner call requires a JSON object, regardless of transport.
        JsonParser.parseString(result).getAsJsonObject();
        return result;
    }
}
