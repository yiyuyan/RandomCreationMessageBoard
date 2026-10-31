package cn.ksmcbrigade.mb.datagen;

import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricLanguageProvider;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.DataProvider;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Language generation. Both locales are produced from one provider so the keys can never drift
 * apart between English and Chinese; the client resolves them with {@code I18n.get(...)}.
 */
public final class MsgBoardLangProvider extends FabricLanguageProvider {

    private final Map<String, String> entries;

    private MsgBoardLangProvider(FabricPackOutput output,
                                CompletableFuture<HolderLookup.Provider> registries,
                                String languageCode,
                                Map<String, String> entries) {
        super(output, languageCode, registries);
        this.entries = entries;
    }

    public static DataProvider english(FabricPackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
        Map<String, String> e = new LinkedHashMap<>();
        items(e, "Message Board", "Time Capsule",
                "Unsealed - right-click to write and seal a message", "Message id: %s",
                "Opens at %s", "Not yet time - cannot be opened");

        // Message board screen
        e.put("crc_msg_board.board.title", "Message Board");
        e.put("crc_msg_board.board.input", "Message");
        e.put("crc_msg_board.board.hint", "Say something… (max 200)");
        e.put("crc_msg_board.board.send", "Send");
        e.put("crc_msg_board.board.refresh", "Refresh");
        e.put("crc_msg_board.board.calendar", "Cal");
        e.put("crc_msg_board.board.clock", "Hr");
        e.put("crc_msg_board.board.header", "Messages on %s");
        e.put("crc_msg_board.board.empty", "No messages on this day yet");
        e.put("crc_msg_board.board.times", "Time");
        e.put("crc_msg_board.board.sent", "Message sent");
        e.put("crc_msg_board.board.send_failed", "Send failed: %s");

        // Capsule screen
        e.put("crc_msg_board.capsule.title", "Time Capsule");
        e.put("crc_msg_board.capsule.content", "Message");
        e.put("crc_msg_board.capsule.hint", "Write something for the future…");
        e.put("crc_msg_board.capsule.pick_time", "Pick time");
        e.put("crc_msg_board.capsule.plus10", "+10s");
        e.put("crc_msg_board.capsule.now", "Now");
        e.put("crc_msg_board.capsule.done", "Seal");
        e.put("crc_msg_board.capsule.close", "Close");
        e.put("crc_msg_board.capsule.open_at", "Opens at %s");
        e.put("crc_msg_board.capsule.dev_hint", "Dev: backdate up to 30 min");
        e.put("crc_msg_board.capsule.backdate_limit", "Dev mode allows at most 30 minutes back");
        e.put("crc_msg_board.capsule.future_required", "Please pick a future date");
        e.put("crc_msg_board.capsule.sealed", "Sealed, opens at %s");
        e.put("crc_msg_board.capsule.seal_failed", "Seal failed: %s");
        e.put("crc_msg_board.capsule.not_yet_open", "Not yet time - cannot be opened");
        e.put("crc_msg_board.capsule.open_failed", "Open failed: %s");
        e.put("crc_msg_board.capsule.empty_content", "Nothing here yet");
        e.put("crc_msg_board.capsule.locked", "Not yet time - cannot be opened (%s)");

        // Shared
        e.put("crc_msg_board.screen.loading", "Loading…");
        e.put("crc_msg_board.screen.load_failed", "Load failed: %s");
        e.put("crc_msg_board.error.empty_content", "Message must not be empty");
        e.put("crc_msg_board.error.rate_limited", "Only one message every 3 seconds");
        e.put("crc_msg_board.error.rate_limited_capsule", "Only one submission every 3 seconds");
        errors(e,
                "Cannot reach the server",
                "Content must not be empty",
                "Content is too long",
                "Too many requests, wait 3 seconds",
                "Not yet time",
                "Message not found",
                "Invalid message type",
                "Invalid date",
                "Invalid player identity",
                "Invalid opening time",
                "Server error (%s)", "Unknown error");
        return new MsgBoardLangProvider(output, registries, "en_us", e);
    }

    public static DataProvider chinese(FabricPackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
        Map<String, String> e = new LinkedHashMap<>();
        items(e, "留言板", "时空胶囊",
                "尚未封存 - 右键写下留言并封存", "留言 ID：%s",
                "开启时间：%s", "时间未到，无法打开");

        e.put("crc_msg_board.board.title", "留言板");
        e.put("crc_msg_board.board.input", "留言");
        e.put("crc_msg_board.board.hint", "说点什么…（最多 200 字）");
        e.put("crc_msg_board.board.send", "发送");
        e.put("crc_msg_board.board.refresh", "刷新");
        e.put("crc_msg_board.board.calendar", "历");
        e.put("crc_msg_board.board.clock", "时");
        e.put("crc_msg_board.board.header", "%s 的留言");
        e.put("crc_msg_board.board.empty", "这一天还没有留言");
        e.put("crc_msg_board.board.times", "时间");
        e.put("crc_msg_board.board.sent", "留言发送成功");
        e.put("crc_msg_board.board.send_failed", "发送失败：%s");

        e.put("crc_msg_board.capsule.title", "时空胶囊");
        e.put("crc_msg_board.capsule.content", "留言内容");
        e.put("crc_msg_board.capsule.hint", "写下你想留给未来的话…");
        e.put("crc_msg_board.capsule.pick_time", "选择时间");
        e.put("crc_msg_board.capsule.plus10", "10秒后");
        e.put("crc_msg_board.capsule.now", "立刻");
        e.put("crc_msg_board.capsule.done", "完成");
        e.put("crc_msg_board.capsule.close", "关闭");
        e.put("crc_msg_board.capsule.open_at", "打开时间：%s");
        e.put("crc_msg_board.capsule.dev_hint", "开发模式：可回拨 30 分钟");
        e.put("crc_msg_board.capsule.backdate_limit", "开发模式最多只能回拨 30 分钟");
        e.put("crc_msg_board.capsule.future_required", "请选择一个未来的日期");
        e.put("crc_msg_board.capsule.sealed", "已封存，可在 %s 打开");
        e.put("crc_msg_board.capsule.seal_failed", "封存失败：%s");
        e.put("crc_msg_board.capsule.not_yet_open", "时间未到，无法打开");
        e.put("crc_msg_board.capsule.open_failed", "打开失败：%s");
        e.put("crc_msg_board.capsule.empty_content", "这里空空如也");
        e.put("crc_msg_board.capsule.locked", "时间未到，无法打开（%s）");

        e.put("crc_msg_board.screen.loading", "加载中…");
        e.put("crc_msg_board.screen.load_failed", "加载失败：%s");
        e.put("crc_msg_board.error.empty_content", "留言不得为空");
        e.put("crc_msg_board.error.rate_limited", "每 3 秒只能发送一条留言");
        e.put("crc_msg_board.error.rate_limited_capsule", "每 3 秒只能提交一次");
        errors(e,
                "无法连接到服务器",
                "内容不能为空",
                "内容超出长度限制",
                "发送过于频繁，请等待 3 秒",
                "时间未到，无法打开",
                "未找到该留言",
                "留言类型无效",
                "日期无效",
                "玩家身份无效",
                "打开时间无效",
                "服务器错误（%s）", "未知错误");
        return new MsgBoardLangProvider(output, registries, "zh_cn", e);
    }

    private static void items(Map<String, String> e, String board, String capsule,
                              String unsealed, String id, String openAt, String locked) {
        e.put("item.crc_msg_board.msg_board", board);
        e.put("item.crc_msg_board.copper_time_capsules", capsule);
        e.put("tooltip.crc_msg_board.capsule.empty", unsealed);
        e.put("tooltip.crc_msg_board.capsule.id", id);
        e.put("tooltip.crc_msg_board.capsule.open_at", openAt);
        e.put("tooltip.crc_msg_board.capsule.locked", locked);
    }

    /** Backend error codes shown to the player. */
    private static void errors(Map<String, String> e, String network, String empty, String tooLong,
                              String rateLimited, String notYetOpen, String notFound, String badType,
                              String badDate, String badPlayer, String badOpenAt,
                              String http, String unknown) {
        e.put("crc_msg_board.err.network", network);
        e.put("crc_msg_board.err.empty_content", empty);
        e.put("crc_msg_board.err.too_long", tooLong);
        e.put("crc_msg_board.err.rate_limited", rateLimited);
        e.put("crc_msg_board.err.not_yet_open", notYetOpen);
        e.put("crc_msg_board.err.not_found", notFound);
        e.put("crc_msg_board.err.bad_type", badType);
        e.put("crc_msg_board.err.bad_date", badDate);
        e.put("crc_msg_board.err.bad_player", badPlayer);
        e.put("crc_msg_board.err.bad_open_at", badOpenAt);
        e.put("crc_msg_board.err.http", http);
        e.put("crc_msg_board.err.unknown", unknown);
    }

    @Override
    public void generateTranslations(HolderLookup.Provider registries, TranslationBuilder builder) {
        entries.forEach(builder::add);
    }
}
