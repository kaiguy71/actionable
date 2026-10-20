package dev.actionable;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ContextCaptureTest {
    @Test
    void writesMarkdownWithExchanges() throws Exception {
        Path dir = Files.createTempDirectory("capture");
        ContextCapture.clear();
        ContextCapture.exchange("action", "SYSTEM TEXT", "USER TEXT", false, "{\"a\":1}", 5, null);
        ContextCapture.event("mod", "hello");
        Path file = ContextCapture.write(dir, "task=x");
        String text = Files.readString(file);
        assertTrue(file.getFileName().toString().endsWith("-contextOutput.md"));
        assertTrue(file.getParent().endsWith("logs/actionable"));
        assertTrue(text.contains("SYSTEM TEXT") && text.contains("USER TEXT") && text.contains("hello"));
        ContextCapture.clear();
    }

    @Test
    void largeCapturesAreZipped() throws Exception {
        Path dir = Files.createTempDirectory("capture");
        ContextCapture.clear();
        for (int i = 0; i < 100; i++) {
            ContextCapture.event("mod", "x".repeat(24_000) + i);
        }
        Path file = ContextCapture.write(dir, "s");
        assertTrue(file.getFileName().toString().endsWith("-contextOutput.zip"));
        ContextCapture.clear();
    }
}
