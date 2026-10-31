package cn.ksmcbrigade.mb.client.screen;

import cn.ksmcbrigade.mb.client.net.MsgApi;
import cn.ksmcbrigade.mb.network.CapsulePayload;
import cn.ksmcbrigade.mb.network.CapsuleTextPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Time capsule screen, in two modes:
 * <ul>
 *     <li>{@code COMPOSE} - write a message, pick the date it may be opened, then seal it.</li>
 *     <li>{@code VIEW} - show a capsule's message once its opening time has passed. When the
 *     text is already cached in the item's NBT it is rendered without touching the backend.</li>
 * </ul>
 */
public class TimeCapsuleScreen extends Screen {

    private enum Mode { COMPOSE, VIEW }

    private static final int MAX_LENGTH = 500;
    private static final long SEND_INTERVAL_MS = 3000L;
    private static final boolean DEV = FabricLoader.getInstance().isDevelopmentEnvironment();
    /** Even in dev a capsule may not be backdated by more than this. */
    private static final long DEV_MAX_BACKDATE_MS = 30 * 60 * 1000L;

    private final Mode mode;
    private final InteractionHand hand;
    private final String messageId;
    private final long openAt;
    private final CalendarPanel calendar =
            new CalendarPanel(DEV ? LocalDate.now() : LocalDate.now().plusDays(1));

    /**
     * Development only: an explicit opening instant (epoch millis) that overrides the
     * calendar date, so a capsule can be opened seconds later - or immediately - instead
     * of waiting for a future day to start.
     */
    private Long devOpenAt;

    /** Message body already stored on the item; when non-empty the viewer stays offline. */
    private String cachedText;

    private EditBox input;
    private boolean calendarOpen;
    private boolean loading;
    private String status = "";
    private String content = "";
    private long lastSentAt;

    private int left;
    private int top;
    private int panelW;
    private int panelH;
    private int calX;
    private int calY;

    private TimeCapsuleScreen(Mode mode, InteractionHand hand, String messageId, long openAt, String cachedText) {
        super(Component.translatable("crc_msg_board.capsule.title"));
        this.mode = mode;
        this.hand = hand;
        this.messageId = messageId;
        this.openAt = openAt;
        this.cachedText = cachedText == null ? "" : cachedText;
    }

    public static TimeCapsuleScreen composer(InteractionHand hand) {
        return new TimeCapsuleScreen(Mode.COMPOSE, hand, null, 0L, "");
    }

    public static TimeCapsuleScreen viewer(InteractionHand hand, String messageId, long openAt, String cachedText) {
        return new TimeCapsuleScreen(Mode.VIEW, hand, messageId, openAt, cachedText);
    }

    @Override
    protected void init() {
        if (mode == Mode.COMPOSE) {
            initCompose();
        } else {
            initView();
        }
    }

    private void initCompose() {
        panelW = 320;
        panelH = DEV ? 250 : 234;
        left = (width - panelW) / 2;
        top = (height - panelH) / 2;
        calX = left + 20;
        calY = top + 104;

        input = new EditBox(font, left + 20, top + 44, panelW - 40, 18,
                Component.translatable("crc_msg_board.capsule.content"));
        input.setMaxLength(MAX_LENGTH);
        input.setHint(Component.translatable("crc_msg_board.capsule.hint"));
        addRenderableWidget(input);

        addRenderableWidget(Button.builder(Component.translatable("crc_msg_board.capsule.pick_time"), b -> {
            calendarOpen = !calendarOpen;
        }).bounds(left + 20, top + 70, 72, 18).build());

        if (DEV) {
            // Debug shortcuts: +10 s relative to the currently selected instant, and "now".
            addRenderableWidget(Button.builder(Component.translatable("crc_msg_board.capsule.plus10"), b -> {
                devOpenAt = effectiveOpenAt() + 10_000L;
            }).bounds(left + 96, top + 70, 52, 18).build());
            addRenderableWidget(Button.builder(Component.translatable("crc_msg_board.capsule.now"), b -> {
                devOpenAt = System.currentTimeMillis() - 1_000L;
            }).bounds(left + 152, top + 70, 42, 18).build());
        }

        addRenderableWidget(Button.builder(Component.translatable("crc_msg_board.capsule.done"), b -> submit())
                .bounds(left + panelW / 2 - 40, top + panelH - 34, 80, 20).build());
    }

    /** The instant the capsule will open: debug override first, otherwise the chosen date. */
    private long effectiveOpenAt() {
        if (devOpenAt != null) {
            return devOpenAt;
        }
        return calendar.selected().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /**
     * Development hook used by the smoke test: seal the given text immediately, i.e. with an
     * opening instant a second in the past.
     */
    public void devSealImmediately(String text) {
        if (input != null) {
            input.setValue(text);
        }
        devOpenAt = System.currentTimeMillis() - 1_000L;
        submit();
    }

    private void initView() {
        panelW = 320;
        panelH = 180;
        left = (width - panelW) / 2;
        top = (height - panelH) / 2;

        addRenderableWidget(Button.builder(Component.translatable("crc_msg_board.capsule.close"), b -> onClose())
                .bounds(left + panelW / 2 - 40, top + panelH - 30, 80, 20).build());

        if (!cachedText.isEmpty()) {
            // Already cached on the item: show it immediately, no request.
            cn.ksmcbrigade.mb.MsgBoardMod.LOGGER.info("[capsule] viewer opened from item NBT (local, {} chars)",
                    cachedText.length());
            content = cachedText;
            return;
        }

        cn.ksmcbrigade.mb.MsgBoardMod.LOGGER.info("[capsule] viewer fetching from backend (no cached text)");
        loading = true;
        MsgApi.open(messageId, result -> Minecraft.getInstance().execute(() -> {
            loading = false;
            if (result.ok()) {
                content = result.message().content();
                cachedText = content;
                cacheTextOnItem(content);
            } else if ("not_yet_open".equals(result.error())) {
                status = I18n.get("crc_msg_board.capsule.not_yet_open");
            } else {
                status = I18n.get("crc_msg_board.capsule.open_failed", MsgApi.describe(result.error()));
            }
        }));
    }

    /** Hands the freshly read text to the server so it lands in the item's NBT. */
    private void cacheTextOnItem(String text) {
        if (text == null || text.isEmpty() || messageId == null || messageId.isEmpty()) {
            return;
        }
        ClientPlayNetworking.send(new CapsuleTextPayload(hand.ordinal(), messageId, text));
    }

    private void submit() {
        String raw = input == null ? null : input.getValue();
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            Ui.toast(false, I18n.get("crc_msg_board.error.empty_content"));
            return;
        }
        long openAtMillis = effectiveOpenAt();
        long nowMillis = System.currentTimeMillis();
        if (DEV) {
            if (openAtMillis < nowMillis - DEV_MAX_BACKDATE_MS) {
                Ui.toast(false, I18n.get("crc_msg_board.capsule.backdate_limit"));
                return;
            }
        } else if (openAtMillis <= nowMillis) {
            Ui.toast(false, I18n.get("crc_msg_board.capsule.future_required"));
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastSentAt < SEND_INTERVAL_MS) {
            Ui.toast(false, I18n.get("crc_msg_board.error.rate_limited_capsule"));
            return;
        }
        lastSentAt = now;
        loading = true;

        Minecraft minecraft = Minecraft.getInstance();
        String uuid = minecraft.getUser().getProfileId().toString();
        String name = minecraft.getUser().getName();

        MsgApi.send(MsgApi.TYPE_CAPSULE, text, uuid, name, openAtMillis, result -> minecraft.execute(() -> {
            loading = false;
            if (!result.ok()) {
                if (result.retryAfterMs() > 0) {
                    lastSentAt = System.currentTimeMillis();
                }
                Ui.toast(false, I18n.get("crc_msg_board.capsule.seal_failed", MsgApi.describe(result.error())));
                return;
            }
            // The id has to be written into the server-side stack, otherwise it is lost on sync.
            ClientPlayNetworking.send(new CapsulePayload(hand.ordinal(), result.id(), openAtMillis));
            Ui.toast(true, I18n.get("crc_msg_board.capsule.sealed", Ui.formatMillis(openAtMillis)));
            onClose();
        }));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor gg, int mouseX, int mouseY, float partialTick) {
        Ui.panel(gg, left, top, panelW, panelH);
        gg.centeredText(font, title, left + panelW / 2, top + 8, Ui.ACCENT);

        if (mode == Mode.COMPOSE) {
            drawCompose(gg);
        } else {
            drawView(gg);
        }

        super.extractRenderState(gg, mouseX, mouseY, partialTick);
    }

    private void drawCompose(GuiGraphicsExtractor gg) {
        gg.text(font, Component.translatable("crc_msg_board.capsule.content"), left + 20, top + 32, Ui.TEXT_DIM);
        String counter = (input.getValue() == null ? 0 : input.getValue().length()) + " / " + MAX_LENGTH;
        gg.text(font, counter, left + panelW - 20 - font.width(counter), top + 32, Ui.TEXT_DIM);

        long preview = effectiveOpenAt();
        boolean validTime = DEV
                ? preview >= System.currentTimeMillis() - DEV_MAX_BACKDATE_MS
                : preview > System.currentTimeMillis();
        gg.text(font, Component.translatable("crc_msg_board.capsule.open_at", Ui.formatMillis(preview)),
                left + 20, top + 94, validTime ? Ui.ACCENT : 0xFFFF6B6B);
        if (DEV) {
            Component devHint = Component.translatable("crc_msg_board.capsule.dev_hint");
            gg.text(font, devHint, left + panelW - 20 - font.width(devHint), top + 94, 0xFF8FE38F);
        }

        if (calendarOpen) {
            calendar.draw(gg, font, calX, calY);
        }

        String line = loading ? I18n.get("crc_msg_board.screen.loading") : status;
        if (!line.isEmpty()) {
            gg.text(font, line, left + 20, top + panelH - 44, Ui.TEXT_DIM);
        }
    }

    private void drawView(GuiGraphicsExtractor gg) {
        gg.text(font, Component.translatable("crc_msg_board.capsule.open_at", Ui.formatMillis(openAt)),
                left + 20, top + 28, Ui.TEXT_DIM);

        if (!content.isEmpty()) {
            List<String> lines = Ui.wrap(font, content, panelW - 40);
            int totalHeight = lines.size() * 10;
            int y = top + (panelH - totalHeight) / 2 - 6;
            for (String line : lines) {
                gg.centeredText(font, line, left + panelW / 2, y, Ui.TEXT);
                y += 10;
            }
        } else {
            String line = loading
                    ? I18n.get("crc_msg_board.screen.loading")
                    : (status.isEmpty() ? I18n.get("crc_msg_board.capsule.empty_content") : status);
            gg.centeredText(font, line, left + panelW / 2, top + panelH / 2 - 6,
                    status.isEmpty() ? Ui.TEXT_DIM : 0xFFFF6B6B);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (mode == Mode.COMPOSE && calendarOpen) {
            int action = calendar.handleClick(calX, calY, event.x(), event.y());
            if (action == CalendarPanel.DAY_SELECTED) {
                // Picking a date explicitly overrides any debug instant.
                devOpenAt = null;
                return true;
            }
            if (action != CalendarPanel.NONE) {
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
