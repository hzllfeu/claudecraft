package io.github.hzllfeu.claudecraft.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.github.hzllfeu.claudecraft.ClaudeCraft;
import io.github.hzllfeu.claudecraft.net.ActionPayload;
import io.github.hzllfeu.claudecraft.net.EventPayload;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dedicated server side: routes each player's actions to THEIR bridge (their own machine, their own Claude Code
 * login) and the bridge's events back to that player — as structured payloads for the ClaudeCraft client screen,
 * or as chat / action bar for players without the client mod.
 */
public final class ClaudeCraftServer implements BridgeHub.Listener {
	static final UUID CONSOLE = new UUID(0L, 0L);
	private Config config;
	private BridgeHub hub;
	private volatile MinecraftServer server;
	/** conv id -> title, per player name, for the chat fallback */
	private final Map<String, Map<String, String>> titles = new ConcurrentHashMap<>();

	public void init() {
		config = Config.load(FabricLoader.getInstance().getConfigDir().resolve("claudecraft.json"));
		hub = new BridgeHub(config, this);
		ServerLifecycleEvents.SERVER_STARTED.register(s -> {
			server = s;
			hub.start();
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> hub.stop());

		ServerPlayNetworking.registerGlobalReceiver(ActionPayload.TYPE, (payload, ctx) -> {
			ServerPlayer p = ctx.player();
			JsonObject a;
			try {
				a = JsonParser.parseString(payload.json()).getAsJsonObject();
			} catch (RuntimeException e) {
				return;
			}
			Config.Link link = linkOf(p);
			if (link == null) {
				sendEvent(p, event("bridge", "connected", false, "linked", false));
				return;
			}
			if (link.uuid == null) {
				link.uuid = p.getUUID().toString();
				config.save();
			}
			boolean up = hub.isConnected(link);
			if ("sync".equals(str(a, "type"))) {
				JsonObject b = event("bridge", "connected", up, "linked", true);
				b.addProperty("host", hub.host(link));
				sendEvent(p, b);
			}
			if (up) hub.send(link, a);
			else if (!"sync".equals(str(a, "type"))) sendEvent(p, notice("Your Claude is not connected: start the bridge on your machine.", "error"));
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> {
			ServerPlayer p = handler.getPlayer();
			Config.Link link = config.findLink(p.getUUID(), p.getGameProfile().getName());
			if (link != null && link.uuid == null) {
				link.uuid = p.getUUID().toString();
				config.save();
			}
		});

		CommandRegistrationCallback.EVENT.register((dispatcher, reg, env) -> dispatcher.register(buildCommand()));
	}

	// ======================================================================= commands (chat users and console)

	private LiteralArgumentBuilder<CommandSourceStack> buildCommand() {
		// /claude link is open to every player: it only lets them connect THEIR OWN bridge (their own machine, their
		// own Claude). Everything else needs a link (or operator rights / allowedPlayers).
		return Commands.literal("claude")
			.then(Commands.literal("link")
				.executes(c -> linkSelf(c))
				.then(Commands.argument("player", StringArgumentType.word()).requires(s -> s.hasPermission(4))
					.executes(c -> linkOther(c, StringArgumentType.getString(c, "player")))))
			.then(Commands.literal("unlink").requires(s -> s.hasPermission(4))
				.then(Commands.argument("player", StringArgumentType.word()).executes(c -> {
					String n = StringArgumentType.getString(c, "player");
					Config.Link l = config.findLink(new UUID(1, 1), n);
					if (l != null) hub.disconnect(l);
					boolean ok = config.unlink(n);
					c.getSource().sendSystemMessage(prefix().append(Component.literal(ok ? n + " unlinked." : "No link for " + n)));
					return ok ? 1 : 0;
				})))
			.then(Commands.literal("sessions").requires(this::allowed).executes(c -> action(c, "sessions", null, null)))
			.then(Commands.literal("status").requires(this::allowed).executes(c -> action(c, "status", null, null)))
			.then(Commands.literal("stop").requires(this::allowed).executes(c -> action(c, "stop", null, null)))
			.then(Commands.literal("mode").requires(this::allowed)
				.executes(c -> action(c, "set_mode", null, null))
				.then(Commands.argument("mode", StringArgumentType.word())
					.suggests((c, b) -> b.suggest("manual").suggest("edits").suggest("auto").buildFuture())
					.executes(c -> action(c, "set_mode", "mode", StringArgumentType.getString(c, "mode")))))
			.then(Commands.literal("new").requires(this::allowed)
				.executes(c -> action(c, "new_conv", null, null))
				.then(Commands.argument("folder", StringArgumentType.greedyString())
					.executes(c -> action(c, "new_conv", "cwd", StringArgumentType.getString(c, "folder")))))
			.then(Commands.literal("resume").requires(this::allowed)
				.then(Commands.argument("session", StringArgumentType.word())
					.executes(c -> action(c, "open_session", "session", StringArgumentType.getString(c, "session")))))
			.then(Commands.literal("allow").requires(this::allowed).then(Commands.argument("id", StringArgumentType.word()).executes(c -> perm(c, true, false))))
			.then(Commands.literal("always").requires(this::allowed).then(Commands.argument("id", StringArgumentType.word()).executes(c -> perm(c, true, true))))
			.then(Commands.literal("deny").requires(this::allowed).then(Commands.argument("id", StringArgumentType.word()).executes(c -> perm(c, false, false))))
			.then(Commands.argument("message", StringArgumentType.greedyString()).requires(this::allowed)
				.executes(c -> action(c, "prompt", "text", StringArgumentType.getString(c, "message"))))
			.executes(c -> {
				c.getSource().sendSystemMessage(prefix().append(Component.literal(
					"/claude <message> · sessions · resume <n> · new [folder] · mode [manual|edits|auto] · stop · status · link")
					.withStyle(ChatFormatting.GRAY)));
				return 1;
			});
	}

	private boolean allowed(CommandSourceStack src) {
		ServerPlayer p = src.getPlayer();
		if (p == null || src.hasPermission(4)) return src.hasPermission(4);
		return config.isAllowed(p.getUUID(), p.getGameProfile().getName());
	}

	private Config.Link linkOf(ServerPlayer p) {
		return p == null ? config.findLink(CONSOLE, "console") : config.findLink(p.getUUID(), p.getGameProfile().getName());
	}

	private int linkSelf(CommandContext<CommandSourceStack> c) {
		ServerPlayer p = c.getSource().getPlayer();
		if (p == null) return linkOther(c, "console");
		Config.Link old = linkOf(p);
		if (old != null) hub.disconnect(old);
		String token = config.link(p.getGameProfile().getName(), p.getUUID());
		c.getSource().getServer().getCommands().sendCommands(p);
		p.sendSystemMessage(prefix().append(Component.literal("Your personal token (keep it secret): ").withStyle(ChatFormatting.GRAY))
			.append(Component.literal("[Copy]").withStyle(st -> st.withColor(ChatFormatting.AQUA)
				.withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, token))
				.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Copy the token to the clipboard"))))));
		p.sendSystemMessage(prefix().append(Component.literal("Paste it into bridge/config.json (\"token\") on your machine, then start the bridge. Any previous token stops working.")
			.withStyle(ChatFormatting.GRAY)));
		return 1;
	}

	private int linkOther(CommandContext<CommandSourceStack> c, String name) {
		UUID uuid = null;
		if ("console".equalsIgnoreCase(name)) uuid = CONSOLE;
		else {
			ServerPlayer online = c.getSource().getServer().getPlayerList().getPlayerByName(name);
			if (online != null) uuid = online.getUUID();
		}
		Config.Link old = config.findLink(uuid == null ? new UUID(1, 1) : uuid, name);
		if (old != null) hub.disconnect(old);
		String token = config.link(name, uuid);
		if (!config.allowedPlayers.contains(name) && !"console".equalsIgnoreCase(name)) {
			config.allowedPlayers.add(name);
			config.save();
		}
		ServerPlayer target = c.getSource().getServer().getPlayerList().getPlayerByName(name);
		if (target != null) c.getSource().getServer().getCommands().sendCommands(target);
		c.getSource().sendSystemMessage(Component.literal("[Claude] Token for " + name + ": " + token));
		return 1;
	}

	private int action(CommandContext<CommandSourceStack> c, String type, String key, String value) {
		CommandSourceStack src = c.getSource();
		Config.Link link = linkOf(src.getPlayer());
		if (link == null) {
			src.sendFailure(Component.literal("[Claude] No Claude linked. Run /claude link and start the bridge on your machine."));
			return 0;
		}
		if (!hub.isConnected(link)) {
			src.sendFailure(Component.literal("[Claude] Your bridge is not connected (is your machine on?)."));
			return 0;
		}
		JsonObject a = new JsonObject();
		a.addProperty("type", type);
		if (key != null) a.addProperty(key, value);
		hub.send(link, a);
		if (type.equals("prompt")) src.sendSystemMessage(prefix().append(Component.literal("> " + value).withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC)));
		return 1;
	}

	private int perm(CommandContext<CommandSourceStack> c, boolean allow, boolean always) {
		Config.Link link = linkOf(c.getSource().getPlayer());
		if (link == null || !hub.isConnected(link)) return 0;
		JsonObject a = new JsonObject();
		a.addProperty("type", "perm_reply");
		a.addProperty("id", StringArgumentType.getString(c, "id"));
		a.addProperty("allow", allow);
		a.addProperty("always", always);
		hub.send(link, a);
		return 1;
	}

	// ======================================================================= bridge -> player

	@Override
	public void onConnect(Config.Link link, String host) {
		run(() -> {
			JsonObject b = event("bridge", "connected", true, "linked", true);
			b.addProperty("host", host);
			deliver(link, b);
		});
	}

	@Override
	public void onDisconnect(Config.Link link) {
		run(() -> deliver(link, event("bridge", "connected", false, "linked", true)));
	}

	@Override
	public void onEvent(Config.Link link, JsonObject ev) {
		run(() -> deliver(link, ev));
	}

	private void run(Runnable r) {
		MinecraftServer s = server;
		if (s != null) s.execute(r);
	}

	private void deliver(Config.Link link, JsonObject ev) {
		rememberTitles(link, ev);
		if ("console".equalsIgnoreCase(link.name)) {
			Component line = ChatRenderer.render(ev, titleMap(link));
			if (line != null) ClaudeCraft.LOG.info("[Claude/console] {}{}", line.getString(),
				"perm".equals(str(ev, "type")) ? "  (claude allow|always|deny " + str(ev, "id") + ")" : "");
			return;
		}
		ServerPlayer p = player(link);
		if (p == null) return;
		if (ServerPlayNetworking.canSend(p, EventPayload.TYPE)) {
			sendEvent(p, ev);
			return;
		}
		// No client mod: chat + action bar
		String type = str(ev, "type");
		if ("toast".equals(type) && !"perm".equals(str(ev, "kind"))) {
			p.displayClientMessage(Component.literal(("done".equals(str(ev, "kind")) ? "✔ " : "✖ ") + str(ev, "title") + " · " + str(ev, "text"))
				.withStyle("done".equals(str(ev, "kind")) ? ChatFormatting.GREEN : ChatFormatting.RED), true);
			return;
		}
		for (Component line : ChatRenderer.renderAll(ev, titleMap(link))) p.sendSystemMessage(line);
	}

	private void rememberTitles(Config.Link link, JsonObject ev) {
		Map<String, String> m = titleMap(link);
		String type = str(ev, "type");
		if ("conv".equals(type)) {
			JsonObject c = ev.getAsJsonObject("conv");
			m.put(str(c, "id"), str(c, "title"));
		} else if ("state".equals(type)) {
			m.clear();
			JsonArray arr = ev.getAsJsonArray("convs");
			if (arr != null) for (JsonElement e : arr) m.put(str(e.getAsJsonObject(), "id"), str(e.getAsJsonObject(), "title"));
		}
	}

	private Map<String, String> titleMap(Config.Link link) {
		return titles.computeIfAbsent(link.name.toLowerCase(java.util.Locale.ROOT), k -> new ConcurrentHashMap<>());
	}

	private ServerPlayer player(Config.Link link) {
		MinecraftServer s = server;
		if (s == null) return null;
		if (link.uuid != null && !link.uuid.isEmpty()) {
			try {
				return s.getPlayerList().getPlayer(UUID.fromString(link.uuid));
			} catch (IllegalArgumentException ignored) {
			}
		}
		return s.getPlayerList().getPlayerByName(link.name);
	}

	private static void sendEvent(ServerPlayer p, JsonObject ev) {
		if (ServerPlayNetworking.canSend(p, EventPayload.TYPE)) ServerPlayNetworking.send(p, new EventPayload(ev.toString()));
	}

	static JsonObject event(String type, String k1, boolean v1, String k2, boolean v2) {
		JsonObject o = new JsonObject();
		o.addProperty("type", type);
		o.addProperty(k1, v1);
		o.addProperty(k2, v2);
		return o;
	}

	static JsonObject notice(String text, String style) {
		JsonObject o = new JsonObject();
		o.addProperty("type", "notice");
		o.addProperty("text", text);
		o.addProperty("style", style);
		return o;
	}

	static MutableComponent prefix() {
		return Component.literal("[Claude] ").withStyle(ChatFormatting.LIGHT_PURPLE);
	}

	static String str(JsonObject o, String k) {
		if (o == null) return "";
		JsonElement e = o.get(k);
		if (e == null || e.isJsonNull()) return "";
		return e.isJsonPrimitive() ? e.getAsString() : e.toString();
	}
}
