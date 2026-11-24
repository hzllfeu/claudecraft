package io.github.hzllfeu.claudecraft.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client -> server: one UI action as JSON (prompt, new_conv, perm_reply, set_mode, sync…), relayed to the player's bridge. */
public record ActionPayload(String json) implements CustomPacketPayload {
	public static final Type<ActionPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("claudecraft", "action"));
	public static final StreamCodec<ByteBuf, ActionPayload> CODEC = ByteBufCodecs.stringUtf8(30_000).map(ActionPayload::new, ActionPayload::json);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
