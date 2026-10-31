package cn.ksmcbrigade.mb.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Small drawing and feedback helpers shared by both screens. */
public final class Ui {

    public static final int BG = 0xE6161616;
    public static final int BG_INNER = 0x50101010;
    public static final int BORDER = 0xFF3C3C3C;
    public static final int TEXT = 0xFFFFFFFF;
    public static final int TEXT_DIM = 0xFF9A9A9A;
    public static final int ACCENT = 0xFF7FD1FF;
    public static final int SELECTED = 0x66A0D8FF;
    public static final int TODAY = 0x66FFFFFF;

    private static final SystemToast.SystemToastId TOAST_ID = new SystemToast.SystemToastId(5000L);

    private Ui() {
    }

    /**
     * Draws a 1px border that stays strictly inside the given rectangle.
     *
     * <p>{@code GuiGraphicsExtractor.outline} renders past the far edge (the right/bottom line
     * lands one pixel outside the rectangle), so the border is composed from four fills with
     * explicit half-open ranges instead.</p>
     */
    public static void border(GuiGraphicsExtractor gg, int x, int y, int w, int h, int color) {
        if (w <= 0 || h <= 0) {
            return;
        }
        int x2 = x + w;
        int y2 = y + h;
        gg.fill(x, y, x2, y + 1, color);                 // top
        gg.fill(x, y2 - 1, x2, y2, color);               // bottom
        gg.fill(x, y + 1, x + 1, y2 - 1, color);         // left
        gg.fill(x2 - 1, y + 1, x2, y2 - 1, color);       // right
    }

    public static void panel(GuiGraphicsExtractor gg, int x, int y, int w, int h) {
        gg.fill(x, y, x + w, y + h, BG);
        border(gg, x, y, w, h, BORDER);
    }

    public static void inner(GuiGraphicsExtractor gg, int x, int y, int w, int h) {
        gg.fill(x, y, x + w, y + h, BG_INNER);
        border(gg, x, y, w, h, BORDER);
    }

    /** Always-on feedback: every send attempt reports success or failure. */
    public static void toast(boolean success, String text) {
        Minecraft minecraft = Minecraft.getInstance();
        Component title = Component.translatable("crc_msg_board.board.title");
        SystemToast.add(minecraft.getToastManager(), TOAST_ID, title, Component.literal(text));
    }

    /** Greedy wrap that measures per character, so CJK text (which has no spaces) wraps too. */
    public static List<String> wrap(Font font, String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty() || maxWidth <= 0) {
            return lines;
        }
        for (String paragraph : text.split("\n", -1)) {
            StringBuilder current = new StringBuilder();
            for (int i = 0; i < paragraph.length(); i++) {
                char c = paragraph.charAt(i);
                if (font.width(current.toString() + c) > maxWidth && current.length() > 0) {
                    lines.add(current.toString());
                    current.setLength(0);
                }
                current.append(c);
            }
            lines.add(current.toString());
        }
        return lines;
    }

    public static String clock(java.time.LocalTime time) {
        return String.format("%02d:%02d", time.getHour(), time.getMinute());
    }

    public static String formatMillis(long epochMillis) {
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(java.time.ZoneId.systemDefault())
                .format(java.time.Instant.ofEpochMilli(epochMillis));
    }
}
