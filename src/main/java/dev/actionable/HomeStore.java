package dev.actionable;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Remembers each world's home (the house corner) across restarts, in config/actionable-homes.json,
 * so a restarted game does not build a second house.
 */
final class HomeStore {
    private HomeStore() {
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("actionable-homes.json");
    }

    /** Identifies the current world: the save name in single player, the server address otherwise. */
    static String worldKey(Minecraft client) {
        String world = client.getSingleplayerServer() != null
                ? "sp:" + client.getSingleplayerServer().getWorldData().getLevelName()
                : client.getCurrentServer() != null ? "mp:" + client.getCurrentServer().ip : "unknown";
        String dimension = client.level == null ? "" : client.level.dimension().identifier().toString();
        return world + "|" + dimension;
    }

    static int[] load(Minecraft client) {
        try {
            Path path = file();
            if (!Files.exists(path)) {
                return null;
            }
            JsonObject homes = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject home = homes.getAsJsonObject(worldKey(client));
            return home == null ? null : new int[] {home.get("x").getAsInt(), home.get("y").getAsInt(),
                    home.get("z").getAsInt()};
        } catch (Exception broken) {
            ActionableLog.LOGGER.warn("[Actionable] home_load_failed {}", ActionableLog.text(broken.toString()));
            return null;
        }
    }

    static void save(Minecraft client, int[] home) {
        try {
            Path path = file();
            JsonObject homes = Files.exists(path)
                    ? JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject()
                    : new JsonObject();
            JsonObject entry = new JsonObject();
            entry.addProperty("x", home[0]);
            entry.addProperty("y", home[1]);
            entry.addProperty("z", home[2]);
            homes.add(worldKey(client), entry);
            Files.createDirectories(path.getParent());
            Files.writeString(path, homes.toString(), StandardCharsets.UTF_8);
        } catch (Exception broken) {
            ActionableLog.LOGGER.warn("[Actionable] home_save_failed {}", ActionableLog.text(broken.toString()));
        }
    }
}
