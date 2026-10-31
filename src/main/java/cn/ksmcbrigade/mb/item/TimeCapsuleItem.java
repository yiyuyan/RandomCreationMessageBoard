package cn.ksmcbrigade.mb.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/**
 * Time capsule: first right-click composes a message and an opening time, then the
 * item carries that message id in its custom data until the chosen moment arrives.
 */
public class TimeCapsuleItem extends Item {

    public static final String TAG_MESSAGE_ID = "MessageId";
    public static final String TAG_OPEN_AT = "OpenAt";
    /** Cached body, written after the first successful open so later reads are local. */
    public static final String TAG_TEXT = "Text";

    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    public TimeCapsuleItem(Properties properties) {
        super(properties);
    }

    public static String getMessageId(ItemStack stack) {
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return tag.getStringOr(TAG_MESSAGE_ID, "");
    }

    public static long getOpenAt(ItemStack stack) {
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return tag.getLongOr(TAG_OPEN_AT, 0L);
    }

    /**
     * The message body cached on the item after the first successful open. When present the
     * viewer renders it directly and never touches the backend.
     */
    public static String getCachedText(ItemStack stack) {
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return tag.getStringOr(TAG_TEXT, "");
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        String messageId = getMessageId(stack);

        if (level.isClientSide()) {
            if (messageId.isEmpty()) {
                openComposer(hand);
            } else {
                long openAt = getOpenAt(stack);
                if (openAt > 0 && System.currentTimeMillis() < openAt) {
                    // Locked capsules refuse to open at all, per design.
                    warnLocked(openAt);
                } else {
                    openViewer(hand, messageId, openAt, getCachedText(stack));
                }
            }
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                Consumer<Component> tooltip, TooltipFlag flag) {
        String messageId = getMessageId(stack);
        if (messageId.isEmpty()) {
            tooltip.accept(Component.translatable("tooltip.crc_msg_board.capsule.empty")
                    .withStyle(ChatFormatting.GRAY));
            return;
        }

        long openAt = getOpenAt(stack);
        tooltip.accept(Component.translatable("tooltip.crc_msg_board.capsule.id", messageId)
                .withStyle(ChatFormatting.DARK_GRAY));
        if (openAt > 0) {
            tooltip.accept(Component.translatable("tooltip.crc_msg_board.capsule.open_at",
                            FORMAT.format(Instant.ofEpochMilli(openAt)))
                    .withStyle(ChatFormatting.GOLD));
        }
        if (openAt > 0 && System.currentTimeMillis() < openAt) {
            tooltip.accept(Component.translatable("tooltip.crc_msg_board.capsule.locked")
                    .withStyle(ChatFormatting.RED));
        }
    }

    private static void openComposer(InteractionHand hand) {
        cn.ksmcbrigade.mb.client.ClientHooks.openCapsuleComposer(hand);
    }

    private static void openViewer(InteractionHand hand, String messageId, long openAt, String cachedText) {
        cn.ksmcbrigade.mb.client.ClientHooks.openCapsuleViewer(hand, messageId, openAt, cachedText);
    }

    private static void warnLocked(long openAt) {
        cn.ksmcbrigade.mb.client.ClientHooks.warnCapsuleLocked(openAt);
    }
}
