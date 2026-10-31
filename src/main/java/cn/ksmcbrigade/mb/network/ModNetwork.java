package cn.ksmcbrigade.mb.network;

import cn.ksmcbrigade.mb.MsgBoardMod;
import cn.ksmcbrigade.mb.item.TimeCapsuleItem;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Server side networking. The backend (Cloudflare Worker) is the source of truth for
 * message text, but the capsule's message id and opening time live in the item, so the
 * server has to write them for them to survive a reconnect.
 */
public final class ModNetwork {

    /** Mirrors the backend's development backdating limit. */
    private static final long DEV_MAX_BACKDATE_MS = 30 * 60 * 1000L;

    private ModNetwork() {
    }

    public static void init() {
        PayloadTypeRegistry.serverboundPlay().register(CapsulePayload.TYPE, CapsulePayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(CapsuleTextPayload.TYPE, CapsuleTextPayload.STREAM_CODEC);

        ServerPlayNetworking.registerGlobalReceiver(CapsulePayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            // Payload handling must touch world state on the server thread.
            context.server().execute(() -> apply(player, payload));
        });

        ServerPlayNetworking.registerGlobalReceiver(CapsuleTextPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            context.server().execute(() -> applyText(player, payload));
        });
    }

    /**
     * Caches the text of an already-opened capsule onto the item, so reopening it needs no
     * backend request. Validates the hand, the item and the id it was sealed with.
     */
    private static void applyText(ServerPlayer player, CapsuleTextPayload payload) {
        String messageId = payload.messageId();
        String text = payload.text();
        if (messageId == null || messageId.isEmpty() || text == null || text.isEmpty()) {
            return;
        }
        if (text.codePointCount(0, text.length()) > 500) {
            MsgBoardMod.LOGGER.warn("Rejected capsule text from {}: too long", player.getScoreboardName());
            return;
        }
        if (payload.hand() != 0 && payload.hand() != 1) {
            return;
        }

        InteractionHand hand = payload.hand() == 0 ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
        ItemStack stack = player.getItemInHand(hand);
        if (!stack.is(MsgBoardMod.TIME_CAPSULE)) {
            return;
        }
        // Only cache onto the capsule the text actually belongs to.
        if (!messageId.equals(TimeCapsuleItem.getMessageId(stack))) {
            MsgBoardMod.LOGGER.warn("Rejected capsule text: id mismatch on {}", player.getScoreboardName());
            return;
        }
        // A sealed capsule's text is immutable once cached.
        if (!TimeCapsuleItem.getCachedText(stack).isEmpty()) {
            return;
        }

        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag ->
                tag.putString(TimeCapsuleItem.TAG_TEXT, text));
        player.getInventory().setChanged();
    }

    private static void apply(ServerPlayer player, CapsulePayload payload) {
        String messageId = payload.messageId();
        if (messageId == null || messageId.length() != 26) {
            MsgBoardMod.LOGGER.warn("Rejected capsule payload with malformed id from {}", player.getScoreboardName());
            return;
        }
        // Production: a capsule may only be sealed for a moment in the future.
        // Development: backdating is allowed, but only within the 30 minute window - even
        // while debugging, the server refuses an arbitrarily old opening instant.
        long now = System.currentTimeMillis();
        long earliest = FabricLoader.getInstance().isDevelopmentEnvironment()
                ? now - DEV_MAX_BACKDATE_MS
                : now + 1;
        if (payload.openAt() < earliest) {
            MsgBoardMod.LOGGER.warn("Rejected capsule payload with open time {} (earliest {}) from {}",
                    payload.openAt(), earliest, player.getScoreboardName());
            return;
        }
        if (payload.hand() != 0 && payload.hand() != 1) {
            return;
        }

        InteractionHand hand = payload.hand() == 0 ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
        ItemStack stack = player.getItemInHand(hand);
        if (!stack.is(MsgBoardMod.TIME_CAPSULE)) {
            MsgBoardMod.LOGGER.warn("Rejected capsule payload: player {} is not holding a capsule",
                    player.getScoreboardName());
            return;
        }
        // Already sealed capsules are never overwritten.
        if (!TimeCapsuleItem.getMessageId(stack).isEmpty()) {
            return;
        }

        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.putString(TimeCapsuleItem.TAG_MESSAGE_ID, messageId);
            tag.putLong(TimeCapsuleItem.TAG_OPEN_AT, payload.openAt());
        });
        player.getInventory().setChanged();
    }
}
