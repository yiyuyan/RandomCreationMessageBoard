package cn.ksmcbrigade.mb.network;

import cn.ksmcbrigade.mb.MsgBoardMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client to server: stamp a newly created capsule onto the held item, so the
 * message id lands in the server-authoritative ItemStack (and therefore persists).
 *
 * @param hand      ordinal of the {@link net.minecraft.world.InteractionHand} holding the capsule
 * @param messageId 26 character ULID returned by the backend
 * @param openAt    epoch millis at which the capsule may be opened
 */
public record CapsulePayload(int hand, String messageId, long openAt) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<CapsulePayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.tryBuild(MsgBoardMod.MOD_ID, "capsule_write"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CapsulePayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, CapsulePayload::hand,
                    ByteBufCodecs.STRING_UTF8, CapsulePayload::messageId,
                    ByteBufCodecs.VAR_LONG, CapsulePayload::openAt,
                    CapsulePayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
