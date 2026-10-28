package dev.actionable;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** The Ollama endpoint used for tactical decisions, changeable at runtime with /llmagent config endpoint (FR-11). */
final class GemmaConfig {
    static final String DEFAULT_ENDPOINT = "http://localhost:11434";
    private static final String CHAT_PATH = "/api/chat";

    private static volatile String endpoint = System.getProperty("actionable.gemma.url", DEFAULT_ENDPOINT);
    private static volatile Path store = Path.of("config", "actionable_gemma_endpoint.txt");

    private GemmaConfig() { }

    static void setStore(Path path) {
        store = path;
    }

    static String endpoint() {
        return endpoint;
    }

    /** The full chat URL: a bare host:port gets the standard Ollama path appended. */
    static URI chatUri() {
        URI base = URI.create(endpoint);
        String path = base.getPath() == null ? "" : base.getPath();
        if (path.isEmpty() || path.equals("/")) {
            return base.resolve(CHAT_PATH);
        }
        return base;
    }

    /** Accepts "http://host:port", "host:port" or "host"; returns the normalized URL, or empty if unusable. */
    static Optional<String> normalize(String input) {
        if (input == null || input.isBlank()) {
            return Optional.empty();
        }
        String value = input.trim();
        if (!value.contains("://")) {
            value = "http://" + value;
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (uri.getHost() == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
                    || uri.getPort() > 65535 || uri.getUserInfo() != null) {
                return Optional.empty();
            }
            return Optional.of(value);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    /** Applies and persists a new endpoint; returns false when the text is not a valid http(s) URL. */
    static boolean setEndpoint(String input) {
        Optional<String> normalized = normalize(input);
        if (normalized.isEmpty()) {
            return false;
        }
        endpoint = normalized.get();
        GemmaLog.info("endpoint_configured url=" + ActionableLog.text(endpoint));
        try {
            Path path = store;
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, endpoint, StandardCharsets.UTF_8);
        } catch (IOException e) {
            GemmaLog.warn("endpoint_persist_failed " + ActionableLog.text(e.toString()));
        }
        return true;
    }

    /** Loads a previously saved endpoint, if any. */
    static void load() {
        try {
            if (Files.exists(store)) {
                normalize(Files.readString(store, StandardCharsets.UTF_8)).ifPresent(value -> endpoint = value);
            }
        } catch (IOException e) {
            GemmaLog.warn("endpoint_load_failed " + ActionableLog.text(e.toString()));
        }
    }
}
