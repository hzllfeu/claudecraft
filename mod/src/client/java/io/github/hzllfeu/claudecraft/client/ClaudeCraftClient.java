package io.github.hzllfeu.claudecraft.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import io.github.hzllfeu.claudecraft.client.ClientState.Toast;
import io.github.hzllfeu.claudecraft.net.ActionPayload;
import io.github.hzllfeu.claudecraft.net.EventPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public final class ClaudeCraftClient implements ClientModInitializer {
	private static KeyMapping openKey;

	@Override
	public void onInitializeClient() {
		openKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.claudecraft.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_C, "key.categories.claudecraft"));

		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			DemoDriver.tick(mc);
			while (openKey.consumeClick()) {
				if (mc.screen != null) continue;
				if (!ClientState.INSTANCE.serverHasMod) {
					if (mc.player != null) mc.player.displayClientMessage(Component.literal("This server does not have ClaudeCraft.").withStyle(ChatFormatting.GRAY), true);
					continue;
				}
				mc.setScreen(new ClaudeScreen());
			}
		});

		ClientPlayNetworking.registerGlobalReceiver(EventPayload.TYPE, (payload, ctx) -> ctx.client().execute(() -> onEvent(payload.json())));

		ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
			ClientState.INSTANCE.reset();
			ClientState.INSTANCE.serverHasMod = ClientPlayNetworking.canSend(ActionPayload.TYPE);
			if (ClientState.INSTANCE.serverHasMod) send(ClaudeScreen.obj("sync"));
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> mc.execute(ClientState.INSTANCE::reset));
	}

	public static void send(JsonObject action) {
		if (ClientPlayNetworking.canSend(ActionPayload.TYPE)) ClientPlayNetworking.send(new ActionPayload(action.toString()));
	}

	static String openKeyName() {
		return openKey.getTranslatedKeyMessage().getString().toUpperCase(java.util.Locale.ROOT);
	}

	private static void onEvent(String json) {
		JsonObject ev;
		try {
			ev = JsonParser.parseString(json).getAsJsonObject();
		} catch (RuntimeException e) {
			return;
		}
		ClientState st = ClientState.INSTANCE;
		st.serverHasMod = true;
		Toast t = st.apply(ev);
		if (t == null) return;
		Minecraft mc = Minecraft.getInstance();
		// The only in-game indicator outside the Claude screen: one action bar line (not shown behind the open screen).
		if (mc.screen instanceof ClaudeScreen) return;
		switch (t.kind()) {
			case "done" -> mc.gui.setOverlayMessage(Component.literal("✔ " + t.title() + " · " + t.text()).withStyle(ChatFormatting.GREEN), false);
			case "error" -> mc.gui.setOverlayMessage(Component.literal("✖ " + t.title()).withStyle(ChatFormatting.RED), false);
			case "perm" -> mc.gui.setOverlayMessage(Component.literal("● " + t.title() + " (press " + openKeyName() + ")").withStyle(ChatFormatting.GOLD), false);
			default -> {
			}
		}
	}
}
