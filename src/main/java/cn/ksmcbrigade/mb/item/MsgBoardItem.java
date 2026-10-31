package cn.ksmcbrigade.mb.item;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/**
 * Message board item: right-click opens the board screen (client side only).
 */
public class MsgBoardItem extends Item {

    public MsgBoardItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) {
            openBoard();
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * Kept in a separate method so the client-only classes are never resolved on a
     * dedicated server (the caller is guarded by {@code level.isClientSide()}).
     */
    private static void openBoard() {
        cn.ksmcbrigade.mb.client.ClientHooks.openMessageBoard();
    }
}
