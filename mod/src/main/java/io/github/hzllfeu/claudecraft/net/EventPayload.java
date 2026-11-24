package io.github.hzllfeu.claudecraft.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server -> client: one bridge event as JSON (state, msg, delta, perm, toast…). */
public record EventPayload(String json) implements CustomPacketPayload {
	public static final Type<EventPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("claudecraft", "event"));
	public static final StreamCodec<ByteBuf, EventPayload> CODEC = ByteBufCodecs.stringUtf8(1 << 20).map(EventPayload::new, EventPayload::json);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
