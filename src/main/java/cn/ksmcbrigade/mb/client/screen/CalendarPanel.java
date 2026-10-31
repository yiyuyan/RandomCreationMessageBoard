package cn.ksmcbrigade.mb.client.screen;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * A tiny month calendar drawn by hand (vanilla has no reusable date picker).
 * Rendering and hit testing share the same layout constants.
 */
public final class CalendarPanel {

    public static final int CELL = 16;
    public static final int PAD = 4;
    public static final int HEADER = 14;
    public static final int WEEKDAY = 10;
    public static final int WIDTH = 7 * CELL + PAD * 2;
    public static final int HEIGHT = HEADER + WEEKDAY + 6 * CELL + PAD;

    public static final int NONE = 0;
    public static final int PREV_MONTH = 1;
    public static final int NEXT_MONTH = 2;
    public static final int DAY_SELECTED = 3;

    private static final String[] WEEKDAYS = {"M", "T", "W", "T", "F", "S", "S"};

    private LocalDate selected;
    private YearMonth view;

    public CalendarPanel(LocalDate selected) {
        this.selected = selected;
        this.view = YearMonth.from(selected);
    }

    public LocalDate selected() {
        return selected;
    }

    public void selected(LocalDate date) {
        this.selected = date;
        this.view = YearMonth.from(date);
    }

    public void draw(GuiGraphicsExtractor gg, Font font, int x, int y) {
        Ui.panel(gg, x, y, WIDTH, HEIGHT);

        String month = view.getYear() + "-" + String.format("%02d", view.getMonthValue());
        gg.centeredText(font, month, x + WIDTH / 2, y + 3, Ui.ACCENT);
        gg.text(font, "<", x + 3, y + 3, Ui.ACCENT);
        gg.text(font, ">", x + WIDTH - 7, y + 3, Ui.ACCENT);

        for (int i = 0; i < WEEKDAYS.length; i++) {
            gg.text(font, WEEKDAYS[i], x + PAD + i * CELL + 4, y + HEADER, Ui.TEXT_DIM);
        }

        LocalDate first = view.atDay(1);
        int offset = first.getDayOfWeek().getValue() - 1;
        int daysInMonth = view.lengthOfMonth();
        LocalDate today = LocalDate.now();

        for (int slot = 0; slot < 42; slot++) {
            int day = slot - offset + 1;
            if (day < 1 || day > daysInMonth) {
                continue;
            }
            int cellX = x + PAD + (slot % 7) * CELL;
            int cellY = y + HEADER + WEEKDAY + (slot / 7) * CELL;
            LocalDate date = view.atDay(day);

            if (date.equals(selected)) {
                gg.fill(cellX, cellY, cellX + CELL, cellY + CELL, Ui.SELECTED);
            } else if (date.equals(today)) {
                Ui.border(gg, cellX, cellY, CELL, CELL, Ui.TODAY);
            }

            String label = Integer.toString(day);
            gg.text(font, label, cellX + (CELL - font.width(label)) / 2, cellY + 3,
                    date.equals(selected) ? Ui.TEXT : Ui.TEXT_DIM);
        }
    }

    /**
     * @return one of {@link #NONE}, {@link #PREV_MONTH}, {@link #NEXT_MONTH} or {@link #DAY_SELECTED}
     */
    public int handleClick(int x, int y, double mouseX, double mouseY) {
        if (mouseX < x || mouseX >= x + WIDTH || mouseY < y || mouseY >= y + HEIGHT) {
            return NONE;
        }
        if (mouseY < y + HEADER) {
            if (mouseX < x + 12) {
                view = view.minusMonths(1);
                return PREV_MONTH;
            }
            if (mouseX > x + WIDTH - 12) {
                view = view.plusMonths(1);
                return NEXT_MONTH;
            }
            return NONE;
        }

        int col = (int) ((mouseX - (x + PAD)) / CELL);
        int row = (int) ((mouseY - (y + HEADER + WEEKDAY)) / CELL);
        if (col < 0 || col > 6 || row < 0 || row > 5) {
            return NONE;
        }

        LocalDate first = view.atDay(1);
        int offset = first.getDayOfWeek().getValue() - 1;
        int day = row * 7 + col - offset + 1;
        if (day < 1 || day > view.lengthOfMonth()) {
            return NONE;
        }
        selected = view.atDay(day);
        return DAY_SELECTED;
    }
}
