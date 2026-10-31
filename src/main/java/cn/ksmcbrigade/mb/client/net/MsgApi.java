package cn.ksmcbrigade.mb.client.net;

import cn.ksmcbrigade.mb.MsgBoardMod;
import net.minecraft.client.resources.language.I18n;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Talks to the Cloudflare Worker backend. All calls are asynchronous; the callback runs on
 * the {@link HttpClient}'s executor, so callers must hop back to the client thread.
 */
public final class MsgApi {

    public static final String TYPE_NORMAL = "normal";
    public static final String TYPE_CAPSULE = "capsule";

    private static HttpClient http;
    private static int httpTimeout = -1;

    private MsgApi() {
    }

    public record Msg(String id, String type, String content, long createdAt, String playerName) {
        public LocalTime localTime() {
            return LocalTime.ofInstant(Instant.ofEpochMilli(createdAt), ZoneId.systemDefault());
        }
    }

    public record SendResult(boolean ok, String id, String error, long retryAfterMs) {
    }

    public record ListResult(boolean ok, List<Msg> messages, String error, boolean truncated) {
    }

    public record OpenResult(boolean ok, Msg message, String error, Long openAt) {
    }

    private static synchronized HttpClient client() {
        MsgConfig config = MsgConfig.get();
        if (http == null || httpTimeout != config.timeoutSeconds) {
            httpTimeout = config.timeoutSeconds;
            http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(config.timeoutSeconds))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
        }
        return http;
    }

    /** Minutes east of UTC for the local timezone, which is what the backend expects. */
    public static int localTzOffsetMinutes() {
        return TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000;
    }

    public static void send(String type, String content, String playerUuid, String playerName, Long openAt,
                            Consumer<SendResult> callback) {
        JsonObject body = new JsonObject();
        body.addProperty("type", type);
        body.addProperty("content", content);
        body.addProperty("playerUuid", playerUuid);
        body.addProperty("playerName", playerName);
        if (openAt != null) {
            body.addProperty("openAt", openAt.longValue());
        }

        request("POST", "/v1/messages", body.toString()).whenComplete((response, error) ->
                callback.accept(parseSend(response, error)));
    }

    /**
     * @param since when non-null, only rows newer than this instant are returned. The board
     *              uses it so a refresh reads a few rows instead of the whole day.
     */
    public static void list(LocalDate date, String type, Long since, Consumer<ListResult> callback) {
        String query = "?date=" + URLEncoder.encode(date.toString(), StandardCharsets.UTF_8)
                + "&tzOffset=" + localTzOffsetMinutes()
                + "&type=" + URLEncoder.encode(type, StandardCharsets.UTF_8);
        if (since != null) {
            query += "&since=" + since;
        }
        request("GET", "/v1/messages" + query, null).whenComplete((response, error) ->
                callback.accept(parseList(response, error)));
    }

    public static void open(String messageId, Consumer<OpenResult> callback) {
        String path = "/v1/messages/" + URLEncoder.encode(messageId, StandardCharsets.UTF_8);
        request("GET", path, null).whenComplete((response, error) ->
                callback.accept(parseOpen(response, error)));
    }

    private static CompletableFuture<HttpResponse<String>> request(String method, String path, String body) {
        String base = MsgConfig.get().baseUrl;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(base + path))
                    .timeout(Duration.ofSeconds(MsgConfig.get().timeoutSeconds))
                    .header("Accept", "application/json");
            if ("POST".equals(method)) {
                builder.header("Content-Type", "application/json; charset=utf-8")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            } else {
                builder.GET();
            }
            return client().sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            MsgBoardMod.LOGGER.warn("Could not build request to {}", base + path, e);
            return CompletableFuture.failedFuture(e);
        }
    }

    private static SendResult parseSend(HttpResponse<String> response, Throwable error) {
        if (error != null || response == null) {
            MsgBoardMod.LOGGER.warn("Send failed", error);
            return new SendResult(false, null, "network", 0L);
        }
        JsonObject json = parseObject(response.body());
        if (response.statusCode() == 201 && json != null && json.has("id")) {
            return new SendResult(true, json.get("id").getAsString(), null, 0L);
        }
        String code = errorCode(json);
        long retryAfterMs = 0L;
        if (json != null && json.has("retryAfterMs")) {
            retryAfterMs = json.get("retryAfterMs").getAsLong();
        }
        if (code == null) {
            code = "http_" + response.statusCode();
        }
        return new SendResult(false, null, code, retryAfterMs);
    }

    private static ListResult parseList(HttpResponse<String> response, Throwable error) {
        if (error != null || response == null) {
            MsgBoardMod.LOGGER.warn("List failed", error);
            return new ListResult(false, List.of(), "network", false);
        }
        JsonObject json = parseObject(response.body());
        if (response.statusCode() != 200 || json == null || !json.has("messages")) {
            String code = errorCode(json);
            return new ListResult(false, List.of(),
                    code == null ? "http_" + response.statusCode() : code, false);
        }
        List<Msg> messages = new ArrayList<>();
        JsonArray array = json.getAsJsonArray("messages");
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            messages.add(readMessage(element.getAsJsonObject()));
        }
        return new ListResult(true, messages, null,
                json.has("truncated") && json.get("truncated").getAsBoolean());
    }

    private static OpenResult parseOpen(HttpResponse<String> response, Throwable error) {
        if (error != null || response == null) {
            MsgBoardMod.LOGGER.warn("Open failed", error);
            return new OpenResult(false, null, "network", null);
        }
        JsonObject json = parseObject(response.body());
        if (response.statusCode() == 200 && json != null && json.has("message")) {
            return new OpenResult(true, readMessage(json.getAsJsonObject("message")), null, null);
        }
        String code = errorCode(json);
        Long openAt = null;
        if (json != null && json.has("openAt") && !json.get("openAt").isJsonNull()) {
            openAt = json.get("openAt").getAsLong();
        }
        return new OpenResult(false, null, code == null ? "http_" + response.statusCode() : code, openAt);
    }

    private static Msg readMessage(JsonObject object) {
        String id = stringOr(object, "id", "");
        String type = stringOr(object, "type", TYPE_NORMAL);
        String content = stringOr(object, "content", "");
        long createdAt = object.has("createdAt") ? object.get("createdAt").getAsLong() : 0L;
        String playerName = object.has("playerName") && !object.get("playerName").isJsonNull()
                ? object.get("playerName").getAsString()
                : null;
        return new Msg(id, type, content, createdAt, playerName);
    }

    private static String stringOr(JsonObject object, String key, String fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : fallback;
    }

    private static JsonObject parseObject(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonElement element = JsonParser.parseString(body);
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String errorCode(JsonObject json) {
        return json != null && json.has("error") && !json.get("error").isJsonNull()
                ? json.get("error").getAsString()
                : null;
    }

    /** Human readable, localised message for a backend error code. */
    public static String describe(String code) {
        if (code == null) {
            return I18n.get("crc_msg_board.err.unknown");
        }
        return switch (code) {
            case "network" -> I18n.get("crc_msg_board.err.network");
            case "empty_content" -> I18n.get("crc_msg_board.err.empty_content");
            case "too_long" -> I18n.get("crc_msg_board.err.too_long");
            case "rate_limited" -> I18n.get("crc_msg_board.err.rate_limited");
            case "not_yet_open" -> I18n.get("crc_msg_board.err.not_yet_open");
            case "not_found" -> I18n.get("crc_msg_board.err.not_found");
            case "bad_type" -> I18n.get("crc_msg_board.err.bad_type");
            case "bad_date", "bad_tz" -> I18n.get("crc_msg_board.err.bad_date");
            case "bad_player" -> I18n.get("crc_msg_board.err.bad_player");
            case "bad_open_at" -> I18n.get("crc_msg_board.err.bad_open_at");
            default -> I18n.get("crc_msg_board.err.http", code);
        };
    }
}
