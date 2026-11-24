package io.github.hzllfeu.claudecraft;

import io.github.hzllfeu.claudecraft.net.ActionPayload;
import io.github.hzllfeu.claudecraft.net.EventPayload;
import io.github.hzllfeu.claudecraft.server.ClaudeCraftServer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Common init (both sides): payload types must be registered on client and server. */
public final class ClaudeCraft implements ModInitializer {
	public static final Logger LOG = LoggerFactory.getLogger("claudecraft");

	@Override
	public void onInitialize() {
		PayloadTypeRegistry.playS2C().register(EventPayload.TYPE, EventPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(ActionPayload.TYPE, ActionPayload.CODEC);
		// Server logic runs on dedicated servers AND on the singleplayer integrated server.
		new ClaudeCraftServer().init();
	}
}
