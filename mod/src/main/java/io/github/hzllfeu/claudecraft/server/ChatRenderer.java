package io.github.hzllfeu.claudecraft.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.github.hzllfeu.claudecraft.server.ClaudeCraftServer.prefix;
import static io.github.hzllfeu.claudecraft.server.ClaudeCraftServer.str;

/** Fallback rendering of bridge events as chat lines, for players without the ClaudeCraft client screen. */
final class ChatRenderer {
	private ChatRenderer() {
	}

	static Component render(JsonObject ev, Map<String, String> titles) {
		List<Component> all = renderAll(ev, titles);
		if (all.isEmpty()) return null;
		MutableComponent out = Component.empty();
		for (int i = 0; i < all.size(); i++) {
			if (i > 0) out.append("\n");
			out.append(all.get(i));
		}
		return out;
	}

	static List<Component> renderAll(JsonObject ev, Map<String, String> titles) {
		List<Component> out = new ArrayList<>();
		String type = str(ev, "type");
		String conv = str(ev, "conv");
		MutableComponent head = prefix();
		if (!conv.isEmpty() && titles.size() > 1) {
			head.append(Component.literal(shorten(titles.getOrDefault(conv, conv), 18) + " › ").withStyle(ChatFormatting.DARK_PURPLE));
		}
		switch (type) {
			case "msg", "msg_update" -> {
				JsonObject m = ev.getAsJsonObject("m");
				String role = str(m, "role");
				String text = str(m, "text");
				switch (role) {
					case "assistant" -> {
						if (!m.has("final") || !m.get("final").getAsBoolean()) break;
						for (String l : lines(text)) out.add(head.copy().append(Component.literal(l).withStyle(ChatFormatting.WHITE)));
					}
					case "tool" -> {
						if ("msg".equals(type)) out.add(head.copy().append(Component.literal("⚙ " + str(m, "tool") + " · " + text).withStyle(ChatFormatting.DARK_AQUA)));
						else if ("error".equals(str(m, "status")))
							out.add(head.copy().append(Component.literal("✖ " + str(m, "tool") + " failed").withStyle(ChatFormatting.RED)));
					}
					case "info" -> out.add(head.copy().append(Component.literal(text).withStyle(ChatFormatting.GRAY)));
					case "error" -> out.add(head.copy().append(Component.literal(text).withStyle(ChatFormatting.RED)));
					case "result" -> out.add(head.copy().append(Component.literal(text).withStyle(ChatFormatting.DARK_GRAY)));
					default -> {
					}
				}
			}
			case "perm" -> {
				String id = str(ev, "id");
				MutableComponent l = head.copy()
					.append(Component.literal(str(ev, "tool") + " ? ").withStyle(ChatFormatting.GOLD))
					.append(Component.literal(str(ev, "summary") + " ").withStyle(ChatFormatting.WHITE));
				int plus = ev.has("plus") ? ev.get("plus").getAsInt() : 0, minus = ev.has("minus") ? ev.get("minus").getAsInt() : 0;
				if (plus + minus > 0) {
					l.append(Component.literal("+" + plus).withStyle(ChatFormatting.GREEN)).append(" ")
						.append(Component.literal("−" + minus + " ").withStyle(ChatFormatting.RED));
				}
				l.append(button("[Allow]", "/claude allow " + id, ChatFormatting.GREEN, str(ev, "detail")))
					.append(" ").append(button("[Always]", "/claude always " + id, ChatFormatting.AQUA, "Stop asking for this tool in this conversation"))
					.append(" ").append(button("[Deny]", "/claude deny " + id, ChatFormatting.RED, "Block this action"));
				out.add(l);
			}
			case "notice" -> out.add(prefix().append(Component.literal(str(ev, "text"))
				.withStyle("error".equals(str(ev, "style")) ? ChatFormatting.RED : ChatFormatting.GRAY)));
			case "bridge" -> {
				boolean up = ev.get("connected").getAsBoolean();
				out.add(prefix().append(Component.literal(up ? "Your Claude is connected (" + str(ev, "host") + ")." : "Your Claude is disconnected.")
					.withStyle(up ? ChatFormatting.GREEN : ChatFormatting.GRAY)));
			}
			case "toast" -> {
				if ("perm".equals(str(ev, "kind"))) break;
				out.add(prefix().append(Component.literal(str(ev, "title") + " · " + str(ev, "text"))
					.withStyle("done".equals(str(ev, "kind")) ? ChatFormatting.GREEN : ChatFormatting.RED)));
			}
			case "sessions" -> {
				out.add(prefix().append(Component.literal("Recent sessions (click to open):").withStyle(ChatFormatting.AQUA)));
				for (JsonElement e : ev.getAsJsonArray("items")) {
					JsonObject it = e.getAsJsonObject();
					out.add(Component.literal(" " + str(it, "n") + ". ").withStyle(ChatFormatting.GRAY)
						.append(button(str(it, "title"), "/claude resume " + str(it, "n"), ChatFormatting.WHITE, str(it, "id")))
						.append(Component.literal("  " + str(it, "project") + " · " + str(it, "ago")).withStyle(ChatFormatting.DARK_GRAY)));
				}
			}
			default -> {
			}
		}
		return out;
	}

	private static List<String> lines(String text) {
		List<String> out = new ArrayList<>();
		boolean fence = false;
		for (String raw : text.replace("\r", "").split("\n")) {
			if (raw.trim().startsWith("```")) {
				fence = !fence;
				continue;
			}
			String l = fence ? raw : raw.replaceAll("\\*\\*(.+?)\\*\\*", "$1").replaceAll("^#{1,6}\\s+", "").replaceAll("`([^`]+)`", "$1");
			if (l.isBlank()) continue;
			while (l.length() > 230) {
				int cut = l.lastIndexOf(' ', 230);
				if (cut < 120) cut = 230;
				out.add(l.substring(0, cut));
				l = l.substring(cut).stripLeading();
			}
			out.add(l);
		}
		return out;
	}

	private static String shorten(String s, int n) {
		return s.length() <= n ? s : s.substring(0, n - 1) + "…";
	}

	static MutableComponent button(String label, String command, ChatFormatting color, String hover) {
		return Component.literal(label).withStyle(st -> st.withColor(color)
			.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
			.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover == null || hover.isEmpty() ? command : hover))));
	}
}
