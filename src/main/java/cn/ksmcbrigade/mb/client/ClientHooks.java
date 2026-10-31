package cn.ksmcbrigade.mb.client;

import cn.ksmcbrigade.mb.client.screen.MsgBoardScreen;
import cn.ksmcbrigade.mb.client.screen.TimeCapsuleScreen;
import cn.ksmcbrigade.mb.client.screen.Ui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.world.InteractionHand;

/**
 * Client-only indirection so item classes never resolve client classes on a dedicated
 * server. Every method here is only reached from a {@code level.isClientSide()} branch.
 */
public final class ClientHooks {

    private ClientHooks() {
    }

    public static void openMessageBoard() {
        Minecraft.getInstance().setScreen(new MsgBoardScreen());
    }

    public static void openCapsuleComposer(InteractionHand hand) {
        Minecraft.getInstance().setScreen(TimeCapsuleScreen.composer(hand));
    }

    public static void openCapsuleViewer(InteractionHand hand, String messageId, long openAt, String cachedText) {
        Minecraft.getInstance().setScreen(TimeCapsuleScreen.viewer(hand, messageId, openAt, cachedText));
    }

    /** Feedback for a capsule that is still sealed. */
    public static void warnCapsuleLocked(long openAt) {
        Ui.toast(false, I18n.get("crc_msg_board.capsule.locked", Ui.formatMillis(openAt)));
    }
}
