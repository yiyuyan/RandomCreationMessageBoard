package cn.ksmcbrigade.mb.network;

import cn.ksmcbrigade.mb.MsgBoardMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client to server: once a capsule has been opened and its text fetched from the backend,
 * write that text into the item so later openings read it locally instead of costing a
 * request. Display-only cache; the server never trusts it for anything else.
 *
 * @param hand      ordinal of the {@link net.minecraft.world.InteractionHand} holding the capsule
 * @param messageId id stored in the item, used to make sure we cache onto the right capsule
 * @param text      the message body that was just read, at most 500 code points
 */
public record CapsuleTextPayload(int hand, String messageId, String text) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<CapsuleTextPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.tryBuild(MsgBoardMod.MOD_ID, "capsule_text"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CapsuleTextPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, CapsuleTextPayload::hand,
                    ByteBufCodecs.STRING_UTF8, CapsuleTextPayload::messageId,
                    ByteBufCodecs.STRING_UTF8, CapsuleTextPayload::text,
                    CapsuleTextPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
