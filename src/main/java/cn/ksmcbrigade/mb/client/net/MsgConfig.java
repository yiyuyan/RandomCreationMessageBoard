package cn.ksmcbrigade.mb.client.net;

import cn.ksmcbrigade.mb.MsgBoardMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Client configuration, read from {@code config/crc_msg_board.json}. The file is created
 * with defaults on first use so players only have to edit the base url.
 */
public final class MsgConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static MsgConfig instance;

    /** Base url of the deployed Cloudflare Worker, without a trailing slash. */
    public String baseUrl = "https://crc-msg-board.YOUR-SUBDOMAIN.workers.dev";
    /** Connect timeout for backend calls, in seconds. */
    public int timeoutSeconds = 10;

    public static synchronized MsgConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    private static MsgConfig load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("crc_msg_board.json");
        MsgConfig config = new MsgConfig();
        try {
            if (Files.notExists(path)) {
                Files.createDirectories(path.getParent());
                Files.writeString(path, GSON.toJson(config), StandardCharsets.UTF_8);
                MsgBoardMod.LOGGER.info("Wrote default message board config to {}", path);
                return config;
            }
            JsonObject json = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), JsonObject.class);
            if (json != null) {
                if (json.has("baseUrl")) {
                    String url = json.get("baseUrl").getAsString();
                    if (url != null && !url.isBlank()) {
                        config.baseUrl = url.trim();
                    }
                }
                if (json.has("timeoutSeconds")) {
                    config.timeoutSeconds = Math.max(3, Math.min(60, json.get("timeoutSeconds").getAsInt()));
                }
            }
        } catch (IOException | RuntimeException e) {
            MsgBoardMod.LOGGER.warn("Could not read config at {} - falling back to defaults", path, e);
        }
        if (config.baseUrl.endsWith("/")) {
            config.baseUrl = config.baseUrl.substring(0, config.baseUrl.length() - 1);
        }
        return config;
    }

    public static void reload() {
        instance = null;
    }
}
