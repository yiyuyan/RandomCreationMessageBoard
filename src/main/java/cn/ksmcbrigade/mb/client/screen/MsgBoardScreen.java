package cn.ksmcbrigade.mb.client.screen;

import cn.ksmcbrigade.mb.client.net.MsgApi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The message board: a collapsible calendar on the left, the selected day's messages in the
 * middle, a collapsible clock column on the right, and the composer along the bottom.
 */
public class MsgBoardScreen extends Screen {

    private static final int PANEL_W = 384;
    private static final int PANEL_H = 226;
    private static final int TOGGLE_W = 22;
    private static final int TIMES_W = 52;
    private static final int MAX_LENGTH = 200;
    private static final long SEND_INTERVAL_MS = 3000L;
    /** Same-day refetches inside this window are served from memory. */
    private static final long CACHE_TTL_MS = 15_000L;

    private final CalendarPanel calendar = new CalendarPanel(LocalDate.now());

    private EditBox input;
    private boolean calendarOpen;
    private boolean timesOpen;
    private boolean loading;
    private String status = "";
    private List<MsgApi.Msg> messages = List.of();
    private int scroll;
    private int contentHeight;
    private long lastSentAt;
    private long lastFetchAt;
    private LocalDate lastFetchDate;
    private long newestCreatedAt;

    private int left;
    private int top;
    private int listX;
    private int listY;
    private int listW;
    private int listH;
    private int calX;
    private int calY;
    private int timesX;
    private int timesY;
    private int timesH;

    public MsgBoardScreen() {
        super(Component.translatable("crc_msg_board.board.title"));
    }

    @Override
    protected void init() {
        left = (width - PANEL_W) / 2;
        top = (height - PANEL_H) / 2;
        int bottomY = top + PANEL_H - 24;

        input = new EditBox(font, left + 6, bottomY, PANEL_W - 118, 18, Component.translatable("crc_msg_board.board.input"));
        input.setMaxLength(MAX_LENGTH);
        input.setHint(Component.translatable("crc_msg_board.board.hint"));
        addRenderableWidget(input);

        addRenderableWidget(Button.builder(Component.translatable("crc_msg_board.board.send"), b -> send())
                .bounds(left + PANEL_W - 106, bottomY, 48, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("crc_msg_board.board.refresh"), b -> refresh(true))
                .bounds(left + PANEL_W - 54, bottomY, 48, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("crc_msg_board.board.calendar"), b -> {
            calendarOpen = !calendarOpen;
            layout();
        }).bounds(left + 6, top + 22, TOGGLE_W, 16).build());
        addRenderableWidget(Button.builder(Component.translatable("crc_msg_board.board.clock"), b -> {
            timesOpen = !timesOpen;
            layout();
        }).bounds(left + PANEL_W - 6 - TOGGLE_W, top + 22, TOGGLE_W, 16).build());

        layout();
        if (messages.isEmpty() && !loading) {
            refresh();
        }
    }

    private void layout() {
        calX = left + 6;
        calY = top + 44;
        timesX = left + PANEL_W - 6 - TIMES_W;
        timesY = calY;
        timesH = PANEL_H - 78;

        int listLeft = left + (calendarOpen ? 6 + CalendarPanel.WIDTH + 8 : 6 + TOGGLE_W + 8);
        int listRight = left + PANEL_W - (timesOpen ? 6 + TIMES_W + 8 : 6 + TOGGLE_W + 8);
        listX = listLeft;
        listW = Math.max(60, listRight - listLeft);
        listY = calY;
        listH = PANEL_H - 78;
    }

    /** Reuses a recent identical fetch so reopening the board does not cost a request. */
    private void refresh() {
        refresh(false);
    }

    private void refresh(boolean force) {
        LocalDate date = calendar.selected();
        long now = System.currentTimeMillis();
        boolean sameDay = date.equals(lastFetchDate);

        if (!force && sameDay && now - lastFetchAt < CACHE_TTL_MS) {
            return;
        }

        // Incremental fetch: only rows newer than what we already hold for this day.
        Long since = sameDay && newestCreatedAt > 0 ? Long.valueOf(newestCreatedAt) : null;
        loading = true;
        status = "";

        MsgApi.list(date, MsgApi.TYPE_NORMAL, since, result -> Minecraft.getInstance().execute(() -> {
            loading = false;
            if (!result.ok()) {
                // Never throw away a good cache because a delta refresh failed.
                if (since == null) {
                    messages = List.of();
                }
                status = I18n.get("crc_msg_board.screen.load_failed", MsgApi.describe(result.error()));
                return;
            }

            Map<String, MsgApi.Msg> byId = new LinkedHashMap<>();
            if (since != null) {
                for (MsgApi.Msg existing : messages) {
                    byId.put(existing.id(), existing);
                }
            }
            for (MsgApi.Msg fetched : result.messages()) {
                byId.put(fetched.id(), fetched);
            }
            List<MsgApi.Msg> merged = new ArrayList<>(byId.values());
            merged.sort(Comparator.comparingLong(MsgApi.Msg::createdAt));

            messages = merged;
            newestCreatedAt = merged.isEmpty() ? 0L : merged.get(merged.size() - 1).createdAt();
            lastFetchAt = System.currentTimeMillis();
            lastFetchDate = date;
            if (since == null) {
                scroll = 0;
            }
            status = "";
        }));
    }

    private void send() {
        String raw = input.getValue();
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            Ui.toast(false, I18n.get("crc_msg_board.error.empty_content"));
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastSentAt < SEND_INTERVAL_MS) {
            Ui.toast(false, I18n.get("crc_msg_board.error.rate_limited"));
            return;
        }
        lastSentAt = now;

        Minecraft minecraft = Minecraft.getInstance();
        String uuid = minecraft.getUser().getProfileId().toString();
        String name = minecraft.getUser().getName();
        loading = true;

        MsgApi.send(MsgApi.TYPE_NORMAL, text, uuid, name, null, result -> minecraft.execute(() -> {
            loading = false;
            if (result.ok()) {
                input.setValue("");
                Ui.toast(true, I18n.get("crc_msg_board.board.sent"));
                // A new message is stamped "now", so jump the view to today to make it visible.
                LocalDate today = LocalDate.now();
                if (!calendar.selected().equals(today)) {
                    calendar.selected(today);
                }
                refresh(true);
            } else {
                if (result.retryAfterMs() > 0) {
                    lastSentAt = System.currentTimeMillis();
                }
                Ui.toast(false, I18n.get("crc_msg_board.board.send_failed", MsgApi.describe(result.error())));
            }
        }));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor gg, int mouseX, int mouseY, float partialTick) {
        Ui.panel(gg, left, top, PANEL_W, PANEL_H);
        gg.centeredText(font, title, left + PANEL_W / 2, top + 8, Ui.ACCENT);

        if (calendarOpen) {
            calendar.draw(gg, font, calX, calY);
        }
        drawMessages(gg);
        if (timesOpen) {
            drawTimes(gg);
        }

        String line = loading ? I18n.get("crc_msg_board.screen.loading") : status;
        if (!line.isEmpty()) {
            gg.text(font, line, left + 6, top + PANEL_H - 36, Ui.TEXT_DIM);
        }
        String counter = (input.getValue() == null ? 0 : input.getValue().length()) + " / " + MAX_LENGTH;
        gg.text(font, counter, left + PANEL_W - 112 - font.width(counter), top + PANEL_H - 36, Ui.TEXT_DIM);

        super.extractRenderState(gg, mouseX, mouseY, partialTick);
    }

    private void drawMessages(GuiGraphicsExtractor gg) {
        Ui.inner(gg, listX, listY, listW, listH);
        gg.enableScissor(listX, listY, listX + listW, listY + listH);

        gg.text(font, Component.translatable("crc_msg_board.board.header", calendar.selected()), listX + 4, listY + 4, Ui.ACCENT);
        int y = listY + 18 - scroll;
        int startY = y;

        if (messages.isEmpty()) {
            gg.text(font, loading ? I18n.get("crc_msg_board.screen.loading") : I18n.get("crc_msg_board.board.empty"), listX + 6, y + 2, Ui.TEXT_DIM);
            y += 12;
        } else {
            for (MsgApi.Msg message : messages) {
                List<String> lines = Ui.wrap(font, message.content(), listW - 50);
                gg.text(font, Ui.clock(message.localTime()), listX + 4, y, Ui.TEXT_DIM);
                int textY = y;
                for (String line : lines) {
                    gg.text(font, line, listX + 40, textY, Ui.TEXT);
                    textY += 10;
                }
                y = Math.max(textY, y + 12) + 4;
            }
        }

        contentHeight = (y - startY) + 20;
        gg.disableScissor();
        clampScroll();
    }

    private void drawTimes(GuiGraphicsExtractor gg) {
        Ui.inner(gg, timesX, timesY, TIMES_W, timesH);
        gg.enableScissor(timesX, timesY, timesX + TIMES_W, timesY + timesH);
        gg.text(font, Component.translatable("crc_msg_board.board.times"), timesX + 4, timesY + 4, Ui.ACCENT);
        int y = timesY + 18 - scroll;
        for (MsgApi.Msg message : messages) {
            gg.text(font, Ui.clock(message.localTime()), timesX + 4, y, Ui.TEXT);
            y += 11;
        }
        gg.disableScissor();
    }

    private void clampScroll() {
        int maxScroll = Math.max(0, contentHeight - listH + 8);
        if (scroll > maxScroll) {
            scroll = maxScroll;
        }
        if (scroll < 0) {
            scroll = 0;
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (calendarOpen) {
            int action = calendar.handleClick(calX, calY, event.x(), event.y());
            if (action == CalendarPanel.DAY_SELECTED) {
                refresh();
                return true;
            }
            if (action == CalendarPanel.PREV_MONTH || action == CalendarPanel.NEXT_MONTH) {
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= listX && mouseX <= listX + listW && mouseY >= listY && mouseY <= listY + listH) {
            scroll -= (int) (scrollY * 12);
            clampScroll();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // --- development hooks, used only by SmokeTest -------------------------------------

    public void devSetPanels(boolean calendar, boolean times) {
        this.calendarOpen = calendar;
        this.timesOpen = times;
        layout();
    }

    /** Exercised by the smoke test so calendar hit testing is not dead code. */
    public int devClickCalendar(double mouseX, double mouseY) {
        int action = calendar.handleClick(calX, calY, mouseX, mouseY);
        if (action == CalendarPanel.DAY_SELECTED) {
            refresh();
        }
        return action;
    }

    public int devPanelOriginX() {
        return calX;
    }

    public int devPanelOriginY() {
        return calY;
    }

    /** Selects today and reloads, so tests see the same day a fresh message lands on. */
    public void devSelectToday() {
        calendar.selected(java.time.LocalDate.now());
        refresh(true);
    }
}
