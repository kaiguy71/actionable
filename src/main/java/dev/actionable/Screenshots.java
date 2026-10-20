package dev.actionable;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.minecraft.client.Minecraft;

/**
 * Captures the player's view for the model. Shots are only kept on disk while the debug sidebar is
 * on; they are zipped into one archive when the session ends so they use little space.
 */
final class Screenshots {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss.SSS");
    /** Shots are downscaled by this factor before being sent to the model. */
    private static final int DOWNSCALE = 2;
    private static final List<Path> SESSION_FILES = new ArrayList<>();

    private Screenshots() {
    }

    static Path directory(Minecraft client) {
        return client.gameDirectory.toPath().resolve("screenshots").resolve("LLM-screenshots");
    }

    /** Captures the current frame; {@code onResult} receives base64 PNG or null on failure (client thread). */
    static void capture(Minecraft client, boolean keepOnDisk, Consumer<String> onResult) {
        try {
            net.minecraft.client.Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), DOWNSCALE,
                    image -> client.execute(() -> onResult.accept(encode(client, image, keepOnDisk))));
        } catch (RuntimeException e) {
            ActionableLog.LOGGER.warn("[Actionable] screenshot_failed {}", ActionableLog.text(e.toString()));
            onResult.accept(null);
        }
    }

    private static String encode(Minecraft client, NativeImage image, boolean keepOnDisk) {
        try (image) {
            Path dir = directory(client);
            Files.createDirectories(dir);
            Path file = dir.resolve(LocalDateTime.now().format(STAMP) + ".png");
            image.writeToFile(file);
            byte[] bytes = Files.readAllBytes(file);
            if (keepOnDisk) {
                SESSION_FILES.add(file);
            } else {
                Files.deleteIfExists(file);
            }
            return Base64.getEncoder().encodeToString(bytes);
        } catch (IOException | RuntimeException e) {
            ActionableLog.LOGGER.warn("[Actionable] screenshot_encode_failed {}", ActionableLog.text(e.toString()));
            return null;
        }
    }

    /** Zips this session's shots into one archive and removes the loose PNG files. */
    static void archiveSession(Minecraft client) {
        if (SESSION_FILES.isEmpty()) {
            return;
        }
        Path archive = directory(client).resolve("session-" + LocalDateTime.now().format(STAMP) + ".zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (Path file : SESSION_FILES) {
                if (Files.exists(file)) {
                    zip.putNextEntry(new ZipEntry(file.getFileName().toString()));
                    Files.copy(file, zip);
                    zip.closeEntry();
                }
            }
        } catch (IOException e) {
            ActionableLog.LOGGER.warn("[Actionable] screenshot_archive_failed {}", ActionableLog.text(e.toString()));
            return;
        }
        for (Path file : SESSION_FILES) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // A locked loose file is harmless; the archive already holds it.
            }
        }
        SESSION_FILES.clear();
        ActionableLog.LOGGER.info("[Actionable] screenshots_archived {}", ActionableLog.text(archive.toString()));
    }
}
