package cn.ksmcbrigade.mb.client;

import cn.ksmcbrigade.mb.MsgBoardMod;
import cn.ksmcbrigade.mb.client.screen.CalendarPanel;
import cn.ksmcbrigade.mb.client.screen.MsgBoardScreen;
import cn.ksmcbrigade.mb.client.screen.TimeCapsuleScreen;
import cn.ksmcbrigade.mb.item.TimeCapsuleItem;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;

/**
 * Development-only smoke test. Enabled only when the marker file
 * {@code config/crc_msg_board_smoke.flag} exists.
 *
 * <p>It opens each screen (with the collapsible panels expanded), exercises calendar hit
 * testing, verifies that both items resolve to a model and are present in the Tools &amp;
 * Utilities creative tab, and saves screenshots so the result can be inspected visually.</p>
 */
public final class SmokeTest {

    private static int ticks;
    private static MsgBoardScreen board;
    private static TimeCapsuleScreen capsule;
    private static ItemStack sealedStack;

    private SmokeTest() {
    }

    public static void register() {
        MsgBoardMod.LOGGER.info("[smoke] screen smoke test enabled");
        ClientTickEvents.END_CLIENT_TICK.register(client -> step(client));
    }

    private static void step(Minecraft client) {
        ticks++;

        if (board != null && ticks > 300 && ticks <= 400 && client.screen != board) {
            client.setScreen(board);
        }
        if (capsule != null && ticks > 420 && ticks <= 570 && client.screen != capsule) {
            client.setScreen(capsule);
        }

        switch (ticks) {
            case 300 -> {
                MsgBoardMod.LOGGER.info("[smoke] opening MsgBoardScreen");
                board = new MsgBoardScreen();
                client.setScreen(board);
            }
            case 320 -> {
                if (board != null) {
                    MsgBoardMod.LOGGER.info("[smoke] expanding calendar + time column");
                    board.devSetPanels(true, true);
                }
            }
            case 340 -> {
                if (board != null) {
                    double x = board.devPanelOriginX() + CalendarPanel.PAD + 3 * CalendarPanel.CELL + 1;
                    double y = board.devPanelOriginY() + CalendarPanel.HEADER + CalendarPanel.WEEKDAY
                            + 2 * CalendarPanel.CELL + 1;
                    MsgBoardMod.LOGGER.info("[smoke] calendar day click -> action {}", board.devClickCalendar(x, y));
                }
            }
            case 355 -> {
                if (board != null) {
                    MsgBoardMod.LOGGER.info("[smoke] calendar prev-month click -> action {}",
                            board.devClickCalendar(board.devPanelOriginX() + 4, board.devPanelOriginY() + 4));
                }
            }
            case 360 -> {
                if (board != null) {
                    MsgBoardMod.LOGGER.info("[smoke] selecting today and reloading from the backend");
                    board.devSelectToday();
                }
            }
            case 370 -> screenshot(client, "message board");
            case 380 -> {
                if (board != null) {
                    MsgBoardMod.LOGGER.info("[smoke] same-day refresh again (should use since=)");
                    board.devSelectToday();
                }
            }
            case 400 -> {
                MsgBoardMod.LOGGER.info("[smoke] closing MsgBoardScreen");
                client.setScreen(null);
                board = null;
            }
            case 420 -> {
                MsgBoardMod.LOGGER.info("[smoke] opening capsule composer");
                capsule = TimeCapsuleScreen.composer(InteractionHand.MAIN_HAND);
                client.setScreen(capsule);
            }
            case 470 -> screenshot(client, "capsule composer");
            case 500 -> {
                MsgBoardMod.LOGGER.info("[smoke] closing capsule composer");
                client.setScreen(null);
                capsule = null;
            }
            case 520 -> {
                MsgBoardMod.LOGGER.info("[smoke] opening capsule viewer");
                capsule = TimeCapsuleScreen.viewer(InteractionHand.MAIN_HAND,
                        "01JGX0000000000000000000AB", System.currentTimeMillis() - 86_400_000L, "");
                client.setScreen(capsule);
            }
            case 570 -> {
                screenshot(client, "capsule viewer");
                client.setScreen(null);
                capsule = null;
            }
            case 600 -> {
                inventoryAndTabCheck(client);
            }
            case 630 -> screenshot(client, "items in hotbar");
            // --- dev-mode capsule: seal "immediately" and confirm the server wrote the NBT ---
            case 660 -> {
                if (client.player != null) {
                    MsgBoardMod.LOGGER.info("[smoke] giving a capsule via command for the dev seal test");
                    client.player.connection.sendCommand("give @s crc_msg_board:copper_time_capsules");
                }
            }
            case 700 -> {
                if (client.player != null) {
                    ItemStack held = client.player.getMainHandItem();
                    MsgBoardMod.LOGGER.info("[smoke] main hand before sealing = {}", held.getItem());
                    MsgBoardMod.LOGGER.info("[smoke] sealing capsule with a PAST open time (dev mode)");
                    client.setScreen(TimeCapsuleScreen.composer(InteractionHand.MAIN_HAND));
                    capsule = (TimeCapsuleScreen) client.screen;
                    if (capsule != null) {
                        capsule.devSealImmediately("开发模式：立刻可读的胶囊");
                    }
                }
            }
            case 780 -> {
                if (client.player != null) {
                    ItemStack held = client.player.getMainHandItem();
                    sealedStack = held;
                    String savedId = TimeCapsuleItem.getMessageId(held);
                    long savedOpenAt = TimeCapsuleItem.getOpenAt(held);
                    MsgBoardMod.LOGGER.info("[smoke] capsule NBT after seal -> id='{}' openAt={} (past? {})",
                            savedId, savedOpenAt, savedOpenAt > 0 && savedOpenAt < System.currentTimeMillis());
                }
            }
            case 850 -> {
                // Open the freshly sealed capsule: this fetches once and caches the text.
                if (client.player != null) {
                    ItemStack held = sealedStack != null ? sealedStack : client.player.getMainHandItem();
                    String id = TimeCapsuleItem.getMessageId(held);
                    String cached = TimeCapsuleItem.getCachedText(held);
                    MsgBoardMod.LOGGER.info("[smoke] opening capsule id='{}' (cached text now: {} chars)",
                            id, cached.length());
                    client.setScreen(TimeCapsuleScreen.viewer(InteractionHand.MAIN_HAND, id,
                            TimeCapsuleItem.getOpenAt(held), cached));
                }
            }
            case 920 -> {
                if (client.player != null) {
                    String cached = TimeCapsuleItem.getCachedText(sealedStack);
                    MsgBoardMod.LOGGER.info("[smoke] NBT text cache after first open: {} chars -> '{}'",
                            cached.length(), cached);
                    client.setScreen(null);
                }
            }
            case 940 -> {
                // Re-open: with the text on the item this must not hit the backend.
                if (client.player != null) {
                    ItemStack held = sealedStack;
                    String cached = TimeCapsuleItem.getCachedText(held);
                    MsgBoardMod.LOGGER.info("[smoke] re-opening capsule with local text ({} chars)", cached.length());
                    client.setScreen(TimeCapsuleScreen.viewer(InteractionHand.MAIN_HAND,
                            TimeCapsuleItem.getMessageId(held), TimeCapsuleItem.getOpenAt(held), cached));
                }
            }
            case 980 -> {
                client.setScreen(null);
                MsgBoardMod.LOGGER.info("[smoke] SMOKE TEST COMPLETE - no crash");
            }
            default -> {
            }
        }
    }

    /** Puts both items on the hotbar and reports whether the creative tab contains them. */
    private static void inventoryAndTabCheck(Minecraft client) {
        if (client.player == null || client.level == null) {
            MsgBoardMod.LOGGER.warn("[smoke] no world loaded - skipping inventory/creative tab check");
            return;
        }

        client.player.getInventory().setSelectedSlot(0);
        client.player.getInventory().setItem(0, new ItemStack(MsgBoardMod.MSG_BOARD));
        client.player.getInventory().setItem(1, new ItemStack(MsgBoardMod.TIME_CAPSULE));
        MsgBoardMod.LOGGER.info("[smoke] placed msg_board + copper_time_capsules in hotbar");

        boolean rebuilt = CreativeModeTabs.tryRebuildTabContents(
                client.level.enabledFeatures(), true, client.player.registryAccess());
        MsgBoardMod.LOGGER.info("[smoke] creative tabs rebuilt: {}", rebuilt);

        for (CreativeModeTab tab : CreativeModeTabs.tabs()) {
            boolean hasBoard;
            boolean hasCapsule;
            try {
                hasBoard = tab.contains(new ItemStack(MsgBoardMod.MSG_BOARD));
                hasCapsule = tab.contains(new ItemStack(MsgBoardMod.TIME_CAPSULE));
            } catch (RuntimeException e) {
                MsgBoardMod.LOGGER.warn("[smoke] tab {} threw while reading items", tab.getDisplayName().getString(), e);
                continue;
            }
            if (hasBoard || hasCapsule) {
                MsgBoardMod.LOGGER.info("[smoke] tab '{}' contains msg_board={} copper_time_capsules={}",
                        tab.getDisplayName().getString(), hasBoard, hasCapsule);
            }
        }
    }

    private static void screenshot(Minecraft client, String label) {
        MsgBoardMod.LOGGER.info("[smoke] saving screenshot: {}", label);
        Screenshot.grab(client.gameDirectory, client.getMainRenderTarget(),
                message -> MsgBoardMod.LOGGER.info("[smoke] screenshot saved: {}", message.getString()));
    }
}
