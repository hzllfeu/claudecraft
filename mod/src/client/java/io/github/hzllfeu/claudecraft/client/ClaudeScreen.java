package io.github.hzllfeu.claudecraft.client;

import com.google.gson.JsonObject;
import io.github.hzllfeu.claudecraft.client.ClientState.Conv;
import io.github.hzllfeu.claudecraft.client.ClientState.Msg;
import io.github.hzllfeu.claudecraft.client.ClientState.Perm;
import io.github.hzllfeu.claudecraft.client.ClientState.Session;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The ClaudeCraft screen: conversation tabs on the left, the focused conversation on the right, input at the bottom. */
public final class ClaudeScreen extends Screen {
	// palette (mockup)
	static final int PANEL = 0xEB0C0C10, BORDER = 0xFF55555F, DIVIDER = 0xFF33333B, SELECTED = 0xFF2A2A35, ACCENT = 0xFFB9A3FF,
		TEXT = 0xFFE8E8E8, MUTED = 0xFF9A9AA6, DIM = 0xFF7C7C88, YELLOW = 0xFFE0C341, ORANGE = 0xFFE8843C, GREEN = 0xFF5FD38A,
		IDLE = 0xFF6B6B78, RED = 0xFFFF7B7B, BUBBLE = 0xFF3B3360, CODE = 0xFFB8E0B8, CODE_BG = 0xFF15151B;
	private static final int SIDEBAR = 134, HEADER = 18, INPUT_H = 36, PAD = 8;

	private final ClientState st = ClientState.INSTANCE;
	private MultiLineEditBox input;
	private int x0, y0, pw, ph;
	private final List<Hit> hits = new ArrayList<>();
	private final List<MsgRect> msgRects = new ArrayList<>();
	private boolean sessionsOpen;
	private boolean modelOpen;
	private int modelX, modelY; // anchor of the model menu (top-left of the strip button)
	private boolean dirsOpen;
	private String dirsFilter = "";
	private int dirsSel, dirsScroll;
	private List<String> tooltip;
	private int tipX, tipY;
	private int historyIndex = -1;
	/** model menu entries: CLI alias ("" = account default) and label */
	private static final String[][] MODELS = {{"", "Default"}, {"fable", "Fable"}, {"opus", "Opus"}, {"sonnet", "Sonnet"}, {"haiku", "Haiku"}};
	private static final String[] SPIN = {"·", "✢", "✳", "✶", "✻", "✽", "✻", "✶", "✳", "✢"};
	private boolean swallowChar;
	private String renderedConv;

	private record Hit(int x, int y, int w, int h, Runnable left, Runnable middle) {
		boolean in(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	private record MsgRect(int x, int y, int w, int h, String text) {
	}

	public ClaudeScreen() {
		super(Component.literal("Claude"));
	}

	/** Own integer UI scale: crisp pixel font, and the same comfortable size whatever the player's GUI scale is. */
	private double k = 1;
	private int vw, vh;

	private void computeScale() {
		double gs = minecraft.getWindow().getGuiScale();
		int fbw = minecraft.getWindow().getWidth(), fbh = minecraft.getWindow().getHeight();
		int e = (int) Math.max(1, Math.min(gs, Math.min(Math.floor(fbw / 900.0), Math.floor(fbh / 470.0))));
		k = e / gs;
		vw = (int) Math.ceil(width / k);
		vh = (int) Math.ceil(height / k);
	}

	@Override
	protected void init() {
		computeScale();
		pw = Math.min(vw - 24, 720);
		ph = Math.min(vh - 28, 420);
		x0 = (vw - pw) / 2;
		y0 = Math.max(14, (vh - ph) / 2 + 6);
		String keep = input == null ? null : input.getValue();
		input = new MultiLineEditBox(font, x0 + SIDEBAR + PAD + 12, y0 + ph - INPUT_H - 8, pw - SIDEBAR - PAD * 2 - 12, INPUT_H,
			Component.literal(""), Component.literal("Message")) {
			@Override
			protected void renderBorder(GuiGraphics g, int x, int y, int w, int h) {
				g.fill(x, y, x + w, y + h, isFocused() ? 0xFF8A8A98 : BORDER);
				g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF000000);
			}
		};
		Conv c = st.focusedConv();
		input.setValue(keep != null ? keep : c == null ? "" : c.draft);
		addRenderableWidget(input);
		setInitialFocus(input);
		renderedConv = c == null ? null : c.id;
		ClaudeCraftClient.send(obj("sync"));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	// =================================================================== rendering

	@Override
	public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
		g.fill(0, 0, vw, vh, 0x38000000); // light dim, keep the world visible
		hits.clear();
		msgRects.clear();
		tooltip = null;
		g.fill(x0, y0, x0 + pw, y0 + ph, PANEL);
		g.renderOutline(x0 - 1, y0 - 1, pw + 2, ph + 2, BORDER);
		g.renderOutline(x0 - 2, y0 - 2, pw + 4, ph + 4, 0x6655555F);
		g.fill(x0 + SIDEBAR, y0, x0 + SIDEBAR + 1, y0 + ph, DIVIDER);
		g.fill(x0 + SIDEBAR + 1, y0, x0 + SIDEBAR + 2, y0 + ph, DIVIDER);
		Conv c = st.focusedConv();
		if (c != null && !c.id.equals(renderedConv)) switchInputTo(c);
		drawSidebar(g, mx, my);
		drawMain(g, mx, my, c);
		g.drawString(font, "›", x0 + SIDEBAR + PAD + 2, input.getY() + 5, ACCENT, false);
	}

	@Override
	public void render(GuiGraphics g, int rmx, int rmy, float delta) {
		int mx = (int) (rmx / k), my = (int) (rmy / k);
		g.pose().pushPose();
		g.pose().scale((float) k, (float) k, 1f);
		ScissorScale.k = k;
		try {
			renderScaled(g, mx, my, delta);
		} finally {
			ScissorScale.k = 1;
			g.pose().popPose();
		}
	}

	private void renderScaled(GuiGraphics g, int mx, int my, float delta) {
		super.render(g, mx, my, delta); // background (our panels) + the input widget
		if (input.getValue().isEmpty()) {
			g.drawString(font, ellipsize("Message Claude…   Enter send · Shift+Enter new line · Tab switch conversation · Esc close",
				input.getWidth() - 10), input.getX() + 4, input.getY() + 4, 0xFF5E5E6A, false);
		}
		// popups and tooltips on a higher layer, like vanilla tooltips
		g.pose().pushPose();
		g.pose().translate(0, 0, 400);
		if (sessionsOpen) drawSessions(g, mx, my);
		if (modelOpen) drawModelMenu(g, mx, my);
		if (dirsOpen) drawDirs(g, mx, my);
		if (tooltip != null) drawTooltip(g);
		g.pose().popPose();
	}

	private void drawTooltip(GuiGraphics g) {
		int w = 0;
		for (String l : tooltip) w = Math.max(w, font.width(l));
		int h = tooltip.size() * 10 + 6, x = Math.min(tipX, vw - w - 10), y = tipY - h;
		g.fill(x, y, x + w + 8, y + h, 0xFF101016);
		g.renderOutline(x, y, w + 8, h, BORDER);
		int ly = y + 4;
		for (String l : tooltip) {
			g.drawString(font, l, x + 4, ly, TEXT, false);
			ly += 10;
		}
	}

	private void drawSidebar(GuiGraphics g, int mx, int my) {
		int x = x0 + PAD, y = y0 + PAD;
		g.drawString(font, "Claude", x, y, ACCENT, false);
		y += 16;
		int i = 0;
		for (Conv c : st.convs.values()) {
			i++;
			boolean sel = c.id.equals(st.focused);
			int rowW = SIDEBAR - PAD * 2 + 2;
			boolean hover = mx >= x - 2 && mx < x - 2 + rowW && my >= y && my < y + 16;
			if (sel) g.fill(x - 2, y, x - 2 + rowW, y + 16, SELECTED);
			else if (hover) g.fill(x - 2, y, x - 2 + rowW, y + 16, 0xFF1C1C24);
			dot(g, x + 2, y + 6, statusColor(c.status));
			String right = "";
			int rightBg = 0, rightFg = 0;
			if ("waiting".equals(c.status) || !c.perms.isEmpty()) {
				right = String.valueOf(Math.max(1, c.perms.size()));
				rightBg = ORANGE;
				rightFg = 0xFF2A1405;
			} else if ("thinking".equals(c.status)) {
				right = "●●●".substring(0, 1 + (int) ((System.currentTimeMillis() / 350) % 3));
			} else if (c.unread > 0 && !sel) {
				right = String.valueOf(c.unread);
				rightBg = 0xFF5B5B70;
				rightFg = TEXT;
			}
			int rw = right.isEmpty() ? 0 : font.width(right) + (rightBg != 0 ? 6 : 0);
			String title = ellipsize(c.title.isEmpty() ? c.project : c.title, rowW - 20 - rw - (hover ? 10 : 0));
			g.drawString(font, title, x + 10, y + 4, "idle".equals(c.status) && c.msgs.isEmpty() ? MUTED : TEXT, false);
			int rx = x - 2 + rowW - 3 - rw;
			if (hover) {
				int cx = rx - 9;
				g.drawString(font, "×", cx, y + 4, mx >= cx - 2 && mx < cx + 7 ? RED : DIM, false);
				final String id = c.id;
				hits.add(new Hit(cx - 2, y, 10, 16, () -> close(id), null));
			}
			if (!right.isEmpty()) {
				if (rightBg != 0) {
					g.fill(rx, y + 3, rx + rw, y + 13, rightBg);
					g.drawString(font, right, rx + 3, y + 4, rightFg, false);
				} else g.drawString(font, right, rx, y + 4, YELLOW, false);
			}
			if (i <= 9 && Screen.hasControlDown()) g.drawString(font, String.valueOf(i), x - 1, y + 4, DIM, false);
			final String id = c.id;
			hits.add(new Hit(x - 2, y, rowW, 16, () -> focus(id), () -> close(id)));
			y += 18;
			if (y > y0 + ph - 104) break;
		}
		// + new conversation
		int bw = SIDEBAR - PAD * 2;
		boolean hNew = mx >= x && mx < x + bw && my >= y + 4 && my < y + 20;
		dashed(g, x, y + 4, bw, 16, hNew ? MUTED : BORDER);
		center(g, "+ New conversation", x + bw / 2, y + 8, hNew ? TEXT : MUTED);
		hits.add(new Hit(x, y + 4, bw, 16, this::openDirs, null));
		y += 24;
		boolean hS = mx >= x && mx < x + bw && my >= y && my < y + 14;
		center(g, "Resume a session…", x + bw / 2, y + 3, hS ? ACCENT : DIM);
		hits.add(new Hit(x, y, bw, 14, this::toggleSessions, null));

		// footer: bridge connection + plan usage (5 h session, week)
		int fy = y0 + ph - 44;
		if (!st.linked) {
			g.drawString(font, "● Not linked", x, fy, RED, false);
		} else {
			g.drawString(font, "●", x, fy, st.bridgeConnected ? GREEN : RED, false);
			g.drawString(font, ellipsize(st.bridgeConnected ? (st.host.isEmpty() ? "Claude connected" : st.host) : "Disconnected", bw - 10), x + 9, fy, DIM, false);
		}
		usageRow(g, x, fy + 13, bw, "5h", st.usage("five_hour"));
		usageRow(g, x, fy + 24, bw, "Week", st.usage("seven_day"));
		if (mx >= x && mx < x + bw && my >= fy + 11 && my < fy + 34) {
			List<String> tip = new ArrayList<>();
			tip.add("Your Claude plan usage");
			for (ClientState.UsageWin w : st.usage)
				tip.add(String.format(Locale.ROOT, "%s: %.0f%%%s", w.label(), w.pct(), w.resets().isEmpty() ? "" : " · resets " + w.resets()));
			if (st.usage("five_hour") == null) tip.add("5h: no session limit reported for your plan");
			if (st.usage.isEmpty()) tip.add("Waiting for the bridge…");
			tooltip = tip;
			tipX = mx + 8;
			tipY = my - 4;
		}
	}

	private void usageRow(GuiGraphics g, int x, int y, int w, String label, ClientState.UsageWin u) {
		g.drawString(font, label, x, y, DIM, false);
		String pct = u == null ? "—" : String.format(Locale.ROOT, "%.0f%%", u.pct());
		int pctWidth = font.width(pct);
		g.drawString(font, pct, x + w - pctWidth, y, u == null ? DIM : usageColor(u.pct()), false);
		int bx = x + 44, bw = w - 44 - pctWidth - 5;
		if (bw < 10) return;
		g.fill(bx, y + 3, bx + bw, y + 6, 0xFF26262E);
		if (u != null) g.fill(bx, y + 3, bx + (int) Math.round(bw * Math.min(100, u.pct()) / 100.0), y + 6, usageColor(u.pct()));
	}

	static int usageColor(double pct) {
		return pct >= 90 ? RED : pct >= 70 ? YELLOW : ACCENT;
	}

	private void drawMain(GuiGraphics g, int mx, int my, Conv c) {
		int mx0 = x0 + SIDEBAR + 2, mxw = pw - SIDEBAR - 2;
		int top = y0, hy = top + 6;
		g.fill(mx0, top + HEADER + 3, mx0 + mxw, top + HEADER + 5, DIVIDER);
		int areaTop = top + HEADER + 9, areaBottom = y0 + ph - INPUT_H - 26;
		int ax = mx0 + 10, aw = mxw - 22;

		if (c == null) {
			g.drawString(font, "No conversation", ax, hy, MUTED, false);
			emptyState(g, ax, aw, areaTop, areaBottom);
			drawNotice(g, ax, aw, areaBottom);
			return;
		}
		// header right: [mode] [stop] status
		int rx = mx0 + mxw - 8;
		String status = statusLabel(c);
		int sw = font.width(status);
		g.drawString(font, status, rx - sw, hy, statusColor(c.status), false);
		rx -= sw + 10;
		if (c.status.equals("thinking") || c.status.equals("waiting")) {
			String stop = "■ Stop";
			int w = font.width(stop) + 8;
			boolean h = mx >= rx - w && mx < rx && my >= hy - 3 && my < hy + 11;
			g.fill(rx - w, hy - 3, rx, hy + 11, h ? 0xFF5A2323 : 0xFF3A1A1A);
			g.drawString(font, stop, rx - w + 4, hy, RED, false);
			hits.add(new Hit(rx - w, hy - 3, w, 14, () -> act("stop", c.id), null));
			rx -= w + 6;
		}
		String mode = "Mode: " + modeLabel(c.mode);
		int w = font.width(mode) + 8;
		boolean hm = mx >= rx - w && mx < rx && my >= hy - 3 && my < hy + 11;
		int modeColor = "auto".equals(c.mode) ? ORANGE : "edits".equals(c.mode) ? YELLOW : MUTED;
		g.renderOutline(rx - w, hy - 3, w, 14, hm ? modeColor : BORDER);
		g.drawString(font, mode, rx - w + 4, hy, modeColor, false);
		hits.add(new Hit(rx - w, hy - 3, w, 14, () -> act("set_mode", c.id), null));
		rx -= w + 8;
		String loc = c.project + (c.branch.isEmpty() ? "" : " · " + c.branch);
		g.drawString(font, ellipsize(loc, rx - ax - 4), ax, hy, MUTED, false);
		if (mx >= ax && mx < rx && my >= hy - 3 && my < hy + 11) { // full path on hover
			int tw = font.width(c.cwd) + 8;
			g.fill(ax - 2, hy + 11, ax - 2 + tw, hy + 24, 0xFF101016);
			g.renderOutline(ax - 2, hy + 11, tw, 13, BORDER);
			g.drawString(font, c.cwd, ax + 2, hy + 14, TEXT, false);
		}

		// messages (bottom-anchored, scrollable)
		List<Block> blocks = layout(c, aw);
		int contentH = 0;
		for (Block b : blocks) contentH += b.h + 6;
		int areaH = areaBottom - areaTop;
		int maxScroll = Math.max(0, contentH - areaH);
		c.scroll = Mth.clamp(c.scroll, 0, maxScroll);
		int y = areaTop + Math.max(0, areaH - contentH) - (maxScroll - c.scroll);
		g.enableScissor(mx0 + 2, areaTop, mx0 + mxw - 2, areaBottom);
		if (blocks.isEmpty()) emptyState(g, ax, aw, areaTop, areaBottom);
		for (Block b : blocks) {
			if (y + b.h >= areaTop && y <= areaBottom) b.draw(g, ax, y, aw, mx, my);
			y += b.h + 6;
		}
		g.disableScissor();
		if (maxScroll > 0) { // scrollbar
			int barH = Math.max(14, areaH * areaH / contentH);
			int barY = areaTop + (int) ((long) (areaH - barH) * (maxScroll - c.scroll) / maxScroll);
			g.fill(mx0 + mxw - 5, barY, mx0 + mxw - 3, barY + barH, 0xFF4A4A55);
		}
		drawNotice(g, ax, aw, areaBottom);
		drawStrip(g, mx, my, c, ax, aw, y0 + ph - INPUT_H - 20);
	}

	/** Strip above the input: model picker (left), context window usage (right). */
	private void drawStrip(GuiGraphics g, int mx, int my, Conv c, int ax, int aw, int sy) {
		String ml = "◆ " + modelLabel(c) + " ▾";
		int mw = font.width(ml) + 8, bx = ax - 4;
		boolean hm = mx >= bx && mx < bx + mw && my >= sy - 2 && my < sy + 10;
		if (hm || modelOpen) g.fill(bx, sy - 2, bx + mw, sy + 10, 0xFF22222B);
		g.drawString(font, ml, bx + 4, sy, hm || modelOpen ? TEXT : MUTED, false);
		modelX = bx;
		modelY = sy - 2;
		hits.add(new Hit(bx, sy - 2, mw, 12, () -> {
			modelOpen = !modelOpen;
			sessionsOpen = false;
		}, null));

		if (c.ctxUsed <= 0) {
			String t = "Context: —";
			g.drawString(font, t, ax + aw - font.width(t), sy, DIM, false);
			return;
		}
		double pct = c.ctxWindow > 0 ? 100.0 * c.ctxUsed / c.ctxWindow : -1;
		String num = fmtK(c.ctxUsed) + (c.ctxWindow > 0 ? " / " + fmtK(c.ctxWindow) : "");
		String pctText = pct >= 0 ? String.format(Locale.ROOT, " %.0f%%", pct) : "";
		int col = pct < 0 ? MUTED : pct >= 85 ? RED : pct >= 60 ? YELLOW : MUTED;
		int rx = ax + aw;
		int pctWidth = font.width(pctText);
		g.drawString(font, pctText, rx - pctWidth, sy, col, false);
		rx -= pctWidth;
		if (pct >= 0) {
			int barW = 40;
			rx -= barW + 4;
			g.fill(rx, sy + 3, rx + barW, sy + 6, 0xFF26262E);
			g.fill(rx, sy + 3, rx + (int) Math.round(barW * Math.min(100, pct) / 100.0), sy + 6, pct >= 85 ? RED : pct >= 60 ? YELLOW : ACCENT);
			rx -= 5;
		}
		String lbl = "Context " + num;
		g.drawString(font, lbl, rx - font.width(lbl), sy, DIM, false);
		if (mx >= rx - font.width(lbl) && mx < ax + aw && my >= sy - 2 && my < sy + 10) {
			tooltip = List.of("Tokens in the conversation context", "(at the last model call" + (c.modelName.isEmpty() ? "" : ", " + prettyModel(c.modelName)) + ")",
				pct >= 60 ? "Claude compacts the history automatically when it gets full" : "Window: " + (c.ctxWindow > 0 ? fmtK(c.ctxWindow) : "unknown"));
			tipX = mx - 120;
			tipY = sy - 4;
		}
	}

	private void drawModelMenu(GuiGraphics g, int mx, int my) {
		Conv c = st.focusedConv();
		if (c == null) {
			modelOpen = false;
			return;
		}
		int w = 150, h = 18 + MODELS.length * 14, x = modelX, y = modelY - h - 2;
		hits.add(0, new Hit(x, y, w, h, () -> {
		}, null));
		g.fill(x, y, x + w, y + h, 0xFF101016);
		g.renderOutline(x, y, w, h, ACCENT);
		g.drawString(font, "Model for this conversation", x + 6, y + 5, ACCENT, false);
		int ry = y + 16;
		for (String[] m : MODELS) {
			boolean sel = m[0].equals(c.model), hov = mx >= x && mx < x + w && my >= ry && my < ry + 14;
			if (hov) g.fill(x + 1, ry, x + w - 1, ry + 14, SELECTED);
			String label = m[1] + (m[0].isEmpty() && !c.model.isEmpty() ? "" : m[0].isEmpty() && !c.modelName.isEmpty() ? " (" + prettyModel(c.modelName) + ")" : "");
			g.drawString(font, (sel ? "✓ " : "   ") + label, x + 6, ry + 3, sel ? GREEN : TEXT, false);
			final String alias = m[0];
			hits.add(0, new Hit(x, ry, w, 14, () -> {
				JsonObject a = obj("set_model");
				a.addProperty("conv", c.id);
				a.addProperty("model", alias);
				ClaudeCraftClient.send(a);
				c.model = alias;
				modelOpen = false;
			}, null));
			ry += 14;
		}
		hits.add(new Hit(0, 0, vw, vh, () -> modelOpen = false, null));
	}

	private String modelLabel(Conv c) {
		if (!c.model.isEmpty()) {
			String alias = c.model.substring(0, 1).toUpperCase(Locale.ROOT) + c.model.substring(1);
			String real = c.modelName.isEmpty() ? "" : prettyModel(c.modelName);
			return real.isEmpty() || real.toLowerCase(Locale.ROOT).startsWith(c.model.toLowerCase(Locale.ROOT)) ? (real.isEmpty() ? alias : real) : alias + " · " + real;
		}
		return c.modelName.isEmpty() ? "Default model" : prettyModel(c.modelName);
	}

	/** claude-opus-5-5[1m] / claude-haiku-4-5-20251001 -> "Opus 5.5" */
	static String prettyModel(String id) {
		String s = id.replace("[1m]", "").replaceFirst("^claude-", "").replaceAll("-\\d{8}$", "");
		String[] p = s.split("-");
		if (p.length == 0 || p[0].isEmpty()) return id;
		StringBuilder b = new StringBuilder(p[0].substring(0, 1).toUpperCase(Locale.ROOT)).append(p[0].substring(1));
		StringBuilder ver = new StringBuilder();
		for (int i = 1; i < p.length; i++) {
			if (p[i].matches("\\d+")) ver.append(ver.length() == 0 ? "" : ".").append(p[i]);
			else b.append(' ').append(p[i]);
		}
		if (ver.length() > 0) b.append(' ').append(ver);
		return b.toString();
	}

	static String fmtK(long n) {
		if (n >= 1_000_000) return (n % 1_000_000 == 0 ? String.valueOf(n / 1_000_000) : String.format(Locale.ROOT, "%.1f", n / 1e6)) + "M";
		if (n >= 10_000) return (n / 1000) + "k";
		if (n >= 1000) return String.format(Locale.ROOT, "%.1fk", n / 1000.0);
		return String.valueOf(n);
	}

	private void emptyState(GuiGraphics g, int ax, int aw, int top, int bottom) {
		int cy = (top + bottom) / 2 - 10;
		if (!st.linked) {
			center(g, "No Claude linked to your account.", ax + aw / 2, cy, TEXT);
			center(g, "Type /claude link in chat, paste the token into the bridge config, start it.", ax + aw / 2, cy + 14, MUTED);
		} else if (!st.bridgeConnected) {
			center(g, "Your Claude is disconnected.", ax + aw / 2, cy, TEXT);
			center(g, "Start the bridge on your machine (node bridge.mjs).", ax + aw / 2, cy + 14, MUTED);
		} else {
			center(g, "Message Claude to get started.", ax + aw / 2, cy, MUTED);
			center(g, "Ctrl+T new conversation · Ctrl+E resume a session", ax + aw / 2, cy + 14, DIM);
		}
	}

	private void drawNotice(GuiGraphics g, int ax, int aw, int areaBottom) {
		long age = System.currentTimeMillis() - st.lastNoticeAt;
		if (st.lastNotice.isEmpty() || age > 6000) return;
		int alpha = age > 5000 ? (int) (255 * (6000 - age) / 1000) : 255;
		int col = ((st.lastNoticeError ? RED : MUTED) & 0x00FFFFFF) | (Math.max(8, alpha) << 24);
		String t = ellipsize(st.lastNotice, aw);
		g.fill(ax - 4, areaBottom - 12, ax + font.width(t) + 4, areaBottom, (Math.max(8, alpha * 3 / 4) << 24));
		g.drawString(font, t, ax, areaBottom - 10, col, false);
	}

	private void drawSessions(GuiGraphics g, int mx, int my) {
		int w = Math.min(360, pw - SIDEBAR - 30), x = x0 + SIDEBAR + (pw - SIDEBAR - w) / 2, y = y0 + 30;
		List<Session> list = st.sessions;
		int h = 24 + Math.max(1, list.size()) * 16;
		g.fill(x, y, x + w, y + h, 0xFF101016);
		g.renderOutline(x, y, w, h, ACCENT);
		g.drawString(font, "Resume a Claude Code session", x + 8, y + 7, ACCENT, false);
		g.drawString(font, "Esc", x + w - 8 - font.width("Esc"), y + 7, DIM, false);
		int ry = y + 22;
		if (list.isEmpty()) g.drawString(font, "Loading…", x + 8, ry + 3, MUTED, false);
		for (Session s : list) {
			boolean hov = mx >= x && mx < x + w && my >= ry && my < ry + 16;
			if (hov) g.fill(x + 1, ry, x + w - 1, ry + 16, SELECTED);
			String meta = s.project() + " · " + s.ago() + (s.open() ? " · open" : "");
			int mw = font.width(meta);
			g.drawString(font, ellipsize(s.title(), w - 24 - mw), x + 8, ry + 4, TEXT, false);
			g.drawString(font, meta, x + w - 8 - mw, ry + 4, s.open() ? GREEN : DIM, false);
			hits.add(0, new Hit(x, ry, w, 16, () -> {
				JsonObject a = obj("open_session");
				a.addProperty("session", s.id());
				ClaudeCraftClient.send(a);
				sessionsOpen = false;
			}, null));
			ry += 16;
		}
		hits.add(new Hit(0, 0, vw, vh, () -> sessionsOpen = false, null)); // click outside closes
	}

	// =================================================================== folder picker (new conversation anywhere)

	private record DirRow(int kind, String label, String path, boolean git) { // kind: 0 recent, 1 parent, 2 folder
	}

	private long dirsRequestedAt;
	private boolean dirsScrollToSel;

	private List<DirRow> dirRows() {
		List<DirRow> rows = new ArrayList<>();
		String f = dirsFilter.trim().toLowerCase(Locale.ROOT);
		if (isPathFilter()) return rows;
		if (f.isEmpty()) {
			for (String r : st.dirsRecent.subList(0, Math.min(5, st.dirsRecent.size()))) rows.add(new DirRow(0, r, r, false));
			if (!st.dirsParent.isEmpty()) rows.add(new DirRow(1, "..", st.dirsParent, false));
		}
		for (ClientState.DirEntry e : st.dirsEntries)
			if (f.isEmpty() || e.name().toLowerCase(Locale.ROOT).contains(f)) rows.add(new DirRow(2, e.name(), joinPath(st.dirsPath, e.name()), e.git()));
		return rows;
	}

	private boolean isPathFilter() {
		String f = dirsFilter.trim();
		return f.startsWith("/") || f.startsWith("~");
	}

	private static String joinPath(String base, String name) {
		return base.endsWith("/") ? base + name : base + "/" + name;
	}

	private void openDirs() {
		dirsOpen = true;
		sessionsOpen = false;
		modelOpen = false;
		Conv c = st.focusedConv();
		requestDirs(c != null && !c.cwd.isEmpty() ? c.cwd : "~");
	}

	private void requestDirs(String path) {
		JsonObject a = obj("list_dirs");
		a.addProperty("path", path);
		ClaudeCraftClient.send(a);
		dirsFilter = "";
		dirsSel = -1;
		dirsScroll = 0;
		dirsRequestedAt = System.currentTimeMillis();
	}

	private void openConvIn(String path) {
		JsonObject a = obj("new_conv");
		a.addProperty("cwd", path);
		ClaudeCraftClient.send(a);
		dirsOpen = false;
	}

	private void filterChanged() {
		dirsScroll = 0;
		dirsSel = -1;
		if (!dirsFilter.isEmpty()) {
			List<DirRow> rows = dirRows();
			for (int i = 0; i < rows.size(); i++) if (rows.get(i).kind == 2) {
				dirsSel = i;
				break;
			}
		}
	}

	private void activate(DirRow r) {
		switch (r.kind) {
			case 0 -> openConvIn(r.path);
			default -> requestDirs(r.path);
		}
	}

	private boolean dirsKey(int key, String name, boolean ctrl, boolean shift) {
		boolean enter = key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER;
		if (key == GLFW.GLFW_KEY_ESCAPE) {
			dirsOpen = false;
			return true;
		}
		if (enter && (ctrl || shift)) {
			if (!st.dirsPath.isEmpty() && st.dirsError.isEmpty()) openConvIn(st.dirsPath);
			return true;
		}
		List<DirRow> rows = dirRows();
		if (enter) {
			if (isPathFilter()) requestDirs(dirsFilter.trim());
			else if (dirsSel >= 0 && dirsSel < rows.size()) activate(rows.get(dirsSel));
			return true;
		}
		if (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN) {
			if (!rows.isEmpty()) {
				dirsSel = key == GLFW.GLFW_KEY_UP ? (dirsSel <= 0 ? rows.size() - 1 : dirsSel - 1) : (dirsSel + 1) % rows.size();
				dirsScrollToSel = true;
			}
			return true;
		}
		if (key == GLFW.GLFW_KEY_BACKSPACE) {
			if (dirsFilter.isEmpty()) {
				if (!st.dirsParent.isEmpty()) requestDirs(st.dirsParent);
			} else {
				dirsFilter = dirsFilter.substring(0, dirsFilter.length() - 1);
				filterChanged();
			}
			return true;
		}
		if (key == GLFW.GLFW_KEY_TAB && dirsSel >= 0 && dirsSel < rows.size() && rows.get(dirsSel).kind == 2) {
			requestDirs(rows.get(dirsSel).path);
			return true;
		}
		if (ctrl && "v".equals(name)) {
			dirsFilter += minecraft.keyboardHandler.getClipboard().replace("\n", "").trim();
			filterChanged();
			return true;
		}
		return true; // everything else is swallowed; characters arrive through charTyped
	}

	private void drawDirs(GuiGraphics g, int mx, int my) {
		int w = Math.min(460, pw - 40), x = x0 + (pw - w) / 2, y = y0 + 18, h = ph - 36;
		hits.add(0, new Hit(x, y, w, h, () -> {
		}, null)); // clicks on the popup background don't fall through to the screen below
		g.fill(x, y, x + w, y + h, 0xFF101016);
		g.renderOutline(x, y, w, h, ACCENT);
		g.drawString(font, "New conversation · pick a folder", x + 8, y + 7, ACCENT, false);
		g.drawString(font, "Esc", x + w - 8 - font.width("Esc"), y + 7, DIM, false);

		// current folder + "open here"
		boolean ok = !st.dirsPath.isEmpty() && st.dirsError.isEmpty();
		String btn = "Open here  Ctrl+↵";
		int bw = font.width(btn) + 12, bx = x + w - 8 - bw, by = y + 20;
		boolean hb = ok && mx >= bx && mx < bx + bw && my >= by && my < by + 14;
		g.fill(bx, by, bx + bw, by + 14, !ok ? 0xFF26262E : hb ? lighten(0xFF2F6B3A) : 0xFF2F6B3A);
		g.renderOutline(bx, by, bw, 14, ok ? GREEN : BORDER);
		g.drawString(font, btn, bx + 6, by + 3, ok ? TEXT : DIM, false);
		if (ok) hits.add(0, new Hit(bx, by, bw, 14, () -> openConvIn(st.dirsPath), null));
		g.drawString(font, ellipsizeLeft(st.dirsPath.isEmpty() ? "…" : st.dirsPath, bx - x - 16), x + 8, by + 3, TEXT, false);

		// filter / path field
		int fy = y + 39;
		g.fill(x + 6, fy, x + w - 6, fy + 14, 0xFF000000);
		g.renderOutline(x + 6, fy, w - 12, 14, BORDER);
		boolean blink = (System.currentTimeMillis() / 500) % 2 == 0;
		if (dirsFilter.isEmpty()) {
			g.drawString(font, ellipsize("Filter… or type a path (~/…, /…) and press Enter", w - 30), x + 11, fy + 3, 0xFF5E5E6A, false);
			if (blink) g.fill(x + 10, fy + 2, x + 11, fy + 12, TEXT);
		} else {
			String shownF = ellipsizeLeft(dirsFilter, w - 30);
			g.drawString(font, shownF, x + 11, fy + 3, TEXT, false);
			if (blink) g.fill(x + 12 + font.width(shownF), fy + 2, x + 13 + font.width(shownF), fy + 12, TEXT);
		}

		// list
		int top = fy + 20, bottom = y + h - 16;
		List<DirRow> rows = dirRows();
		if (isPathFilter()) {
			g.drawString(font, "Enter: go to " + ellipsizeLeft(dirsFilter.trim(), w - 120), x + 10, top + 2, MUTED, false);
		} else if (dirsRequestedAt > st.dirsAt) {
			g.drawString(font, "Loading…", x + 10, top + 2, MUTED, false);
		} else {
			if (!st.dirsError.isEmpty()) g.drawString(font, st.dirsError, x + 10, top + 2, RED, false);
			// content layout: section headers + rows
			List<int[]> pos = new ArrayList<>(); // y offset per row
			int cy = st.dirsError.isEmpty() ? 0 : 14;
			int lastKind = -1;
			for (DirRow r : rows) {
				int section = r.kind == 0 ? 0 : 1;
				if (section != lastKind) {
					cy += lastKind == -1 ? 0 : 4;
					cy += 12; // header
					lastKind = section;
				}
				pos.add(new int[]{cy});
				cy += 14;
			}
			int viewH = bottom - top, maxScroll = Math.max(0, cy - viewH);
			if (dirsScrollToSel && dirsSel >= 0 && dirsSel < pos.size()) {
				int sy = pos.get(dirsSel)[0];
				if (sy - dirsScroll < 0) dirsScroll = sy - 12;
				if (sy + 14 - dirsScroll > viewH) dirsScroll = sy + 14 - viewH;
				dirsScrollToSel = false;
			}
			dirsScroll = Mth.clamp(dirsScroll, 0, maxScroll);
			g.enableScissor(x + 1, top, x + w - 1, bottom);
			lastKind = -1;
			for (int i = 0; i < rows.size(); i++) {
				DirRow r = rows.get(i);
				int ry = top + pos.get(i)[0] - dirsScroll;
				int section = r.kind == 0 ? 0 : 1;
				if (section != lastKind) {
					String hd = section == 0 ? "Recent" : "Folders" + (dirsFilter.isEmpty() ? "" : " · \"" + dirsFilter + "\"");
					g.drawString(font, hd, x + 10, ry - 11, DIM, false);
					lastKind = section;
				}
				if (ry + 14 < top || ry > bottom) continue;
				boolean visible = ry >= top - 2 && ry + 12 <= bottom + 2;
				boolean hov = visible && mx >= x + 4 && mx < x + w - 4 && my >= ry && my < ry + 14 && my >= top && my < bottom;
				if (i == dirsSel) g.fill(x + 4, ry, x + w - 4, ry + 14, SELECTED);
				else if (hov) g.fill(x + 4, ry, x + w - 4, ry + 14, 0xFF1C1C24);
				String right = r.kind == 0 ? "open ›" : r.kind == 2 && hov ? "Open ›" : "";
				int rw = right.isEmpty() ? 0 : font.width(right) + 8;
				int gitW = r.git ? font.width("git") + 6 : 0;
				switch (r.kind) {
					case 0 -> g.drawString(font, ellipsizeLeft(r.label, w - 40 - rw), x + 12, ry + 3, TEXT, false);
					case 1 -> g.drawString(font, "↑  Parent folder", x + 12, ry + 3, MUTED, false);
					default -> {
						g.drawString(font, "▸ " + ellipsize(r.label, w - 44 - rw - gitW), x + 12, ry + 3, TEXT, false);
						if (r.git) g.drawString(font, "git", x + w - 10 - rw - gitW + 3, ry + 3, 0xFF6FA87F, false);
					}
				}
				if (!visible) continue;
				final DirRow row = r;
				hits.add(0, new Hit(x + 4, Math.max(ry, top), w - 8, Math.min(ry + 14, bottom) - Math.max(ry, top), () -> activate(row), null));
				if (!right.isEmpty()) {
					int rx = x + w - 8 - rw;
					boolean hr = mx >= rx && mx < rx + rw && my >= ry && my < ry + 14;
					g.drawString(font, right, rx + 4, ry + 3, hr ? GREEN : r.kind == 0 ? DIM : MUTED, false);
					hits.add(0, new Hit(rx, ry, rw, 14, () -> openConvIn(row.path), null));
				}
			}
			g.disableScissor();
			if (rows.isEmpty() && st.dirsError.isEmpty())
				g.drawString(font, dirsFilter.isEmpty() ? "No subfolders" : "No matching folder", x + 10, top + 14, MUTED, false);
		}
		g.drawString(font, ellipsize("Click: enter folder · Tab/Enter: same · Backspace: parent · Ctrl+↵: open here", w - 16),
			x + 8, y + h - 12, DIM, false);
		hits.add(new Hit(0, 0, vw, vh, () -> dirsOpen = false, null)); // click outside closes
	}

	String ellipsizeLeft(String s, int w) {
		if (s == null || w <= 8) return "";
		if (font.width(s) <= w) return s;
		int i = 0;
		while (i < s.length() && font.width("…" + s.substring(i)) > w) i++;
		return "…" + s.substring(i);
	}

	// =================================================================== layout of messages

	private abstract static class Block {
		int h;

		abstract void draw(GuiGraphics g, int x, int y, int w, int mx, int my);
	}

	private List<Block> layout(Conv c, int aw) {
		List<Block> out = new ArrayList<>();
		List<Msg> chips = new ArrayList<>();
		for (Msg m : c.msgs) {
			if ("tool".equals(m.role)) {
				chips.add(m);
				continue;
			}
			if (!chips.isEmpty()) {
				out.add(chipRow(new ArrayList<>(chips), aw));
				chips.clear();
			}
			Block b = msgBlock(m, aw);
			if (b != null) out.add(b);
		}
		if (!chips.isEmpty()) out.add(chipRow(chips, aw));
		for (Perm p : c.perms.values()) out.add(permCard(c, p, aw));
		if (c.perms.isEmpty() && (!c.phase.isEmpty() || "thinking".equals(c.status))) out.add(activityLine(c, aw));
		return out;
	}

	/** Claude Code-style live line under the conversation: spinner, what it is doing, elapsed time, output tokens. */
	private Block activityLine(Conv c, int aw) {
		Block b = new Block() {
			void draw(GuiGraphics g, int x, int y, int w, int mx, int my) {
				long now = System.currentTimeMillis();
				long since = c.liveSince > 0 ? c.liveSince : c.statusSince;
				String spin = SPIN[(int) ((now / 120) % SPIN.length)];
				String what = switch (c.phase) {
					case "starting" -> "Starting Claude…";
					case "writing" -> "Writing…";
					case "tool" -> c.liveDetail.isEmpty() ? "Working…" : c.liveDetail;
					default -> "Thinking…";
				};
				String meta = "  " + Math.max(0, (now - since) / 1000) + " s" + (c.liveTokens > 0 ? " · ↓ " + fmtK(c.liveTokens) + " tokens" : "");
				g.drawString(font, spin, x, y, ORANGE, false);
				int mw = font.width(meta);
				String t = ellipsize(what, w - 12 - mw);
				g.drawString(font, t, x + 10, y, ORANGE, false);
				g.drawString(font, meta, x + 10 + font.width(t), y, DIM, false);
			}
		};
		b.h = 10;
		return b;
	}

	private Block msgBlock(Msg m, int aw) {
		switch (m.role) {
			case "user": {
				int maxW = (int) (aw * 0.78) - 12;
				List<FormattedCharSequence> lines = wrap(m, maxW);
				int lw = 0;
				for (FormattedCharSequence l : lines) lw = Math.max(lw, font.width(l));
				final int bw = lw + 12, bh = lines.size() * 10 + 8;
				Block b = new Block() {
					void draw(GuiGraphics g, int x, int y, int w, int mx, int my) {
						int bx = x + w - bw;
						g.fill(bx, y, bx + bw, y + bh, BUBBLE);
						int ly = y + 4;
						for (FormattedCharSequence l : lines) {
							g.drawString(font, l, bx + 6, ly, TEXT, false);
							ly += 10;
						}
						msgRects.add(new MsgRect(bx, y, bw, bh, m.text));
					}
				};
				b.h = bh;
				return b;
			}
			case "assistant": {
				if (m.text.isEmpty() && m.fin) return null;
				List<Line> lines = codeAware(m, aw);
				Block b = new Block() {
					void draw(GuiGraphics g, int x, int y, int w, int mx, int my) {
						int ly = y;
						for (Line l : lines) {
							if (l.code) g.fill(x - 2, ly - 1, x + w, ly + 9, CODE_BG);
							g.drawString(font, l.seq, x + (l.code ? 3 : 0), ly, l.code ? CODE : TEXT, false);
							ly += 10;
						}
						if (!m.fin && (System.currentTimeMillis() / 450) % 2 == 0) {
							Line last = lines.isEmpty() ? null : lines.get(lines.size() - 1);
							int cx = x + (last == null ? 0 : font.width(last.seq) + 1);
							g.fill(cx, ly - 10, cx + 4, ly - 2, ACCENT);
						}
						msgRects.add(new MsgRect(x, y, w, lines.size() * 10, m.text));
					}
				};
				b.h = Math.max(10, lines.size() * 10);
				return b;
			}
			case "thinking": {
				String body = m.text.strip();
				List<FormattedCharSequence> all = body.isEmpty() ? List.of() : wrap(m, aw - 8);
				List<FormattedCharSequence> shown = !m.fin ? all.subList(Math.max(0, all.size() - 3), all.size()) : m.expanded ? all : List.of();
				String head = !m.fin ? "✻ Thinking…" : "✻ Thought" + (m.secs > 0 ? " for " + m.secs + " s" : "")
					+ (body.isEmpty() ? "" : m.expanded ? "  ▴" : "  ▸");
				Block b = new Block() {
					void draw(GuiGraphics g, int x, int y, int w, int mx, int my) {
						boolean hov = !body.isEmpty() && m.fin && mx >= x && mx < x + font.width(head) && my >= y && my < y + 10;
						g.drawString(font, head, x, y, hov ? TEXT : DIM, false);
						if (!body.isEmpty() && m.fin) hits.add(0, new Hit(x, y, font.width(head), 10, () -> m.expanded = !m.expanded, null));
						int ly = y + 11;
						if (!shown.isEmpty()) g.fill(x + 1, ly - 1, x + 2, ly + shown.size() * 10 - 1, DIVIDER);
						for (FormattedCharSequence l : shown) {
							g.drawString(font, l, x + 7, ly, 0xFF6E6E7A, false);
							ly += 10;
						}
						if (!body.isEmpty()) msgRects.add(new MsgRect(x, y, w, 10 + shown.size() * 10, m.text));
					}
				};
				b.h = 10 + (shown.isEmpty() ? 0 : shown.size() * 10 + 2);
				return b;
			}
			default: {
				int color = switch (m.role) {
					case "error" -> RED;
					case "result" -> m.ok ? DIM : RED;
					default -> MUTED;
				};
				List<FormattedCharSequence> lines = wrap(m, aw);
				Block b = new Block() {
					void draw(GuiGraphics g, int x, int y, int w, int mx, int my) {
						int ly = y;
						for (FormattedCharSequence l : lines) {
							g.drawString(font, l, x, ly, color, false);
							ly += 10;
						}
						msgRects.add(new MsgRect(x, y, w, lines.size() * 10, m.text));
					}
				};
				b.h = lines.size() * 10;
				return b;
			}
		}
	}

	private Block chipRow(List<Msg> tools, int aw) {
		List<int[]> pos = new ArrayList<>(); // x, y, w
		int cx = 0, cy = 0;
		for (Msg m : tools) {
			int w = Math.min(aw, font.width(chipLabel(m)) + 10);
			if (cx > 0 && cx + w > aw) {
				cx = 0;
				cy += 15;
			}
			pos.add(new int[]{cx, cy, w});
			cx += w + 4;
		}
		final int rows = cy + 13;
		Block b = new Block() {
			void draw(GuiGraphics g, int x, int y, int w, int mx, int my) {
				for (int i = 0; i < tools.size(); i++) {
					Msg m = tools.get(i);
					int[] p = pos.get(i);
					int bg, fg;
					switch (m.status) {
						case "running" -> {
							bg = 0xFF3A3216;
							fg = 0xFFFFD866;
						}
						case "error" -> {
							bg = 0xFF402020;
							fg = 0xFFFF8A8A;
						}
						default -> {
							bg = 0xFF1F3340;
							fg = 0xFF8FD0FF;
						}
					}
					g.fill(x + p[0], y + p[1], x + p[0] + p[2], y + p[1] + 12, bg);
					g.drawString(font, ellipsize(chipLabel(m), p[2] - 10), x + p[0] + 5, y + p[1] + 2, fg, false);
					msgRects.add(new MsgRect(x + p[0], y + p[1], p[2], 12, m.tool + " " + m.text));
				}
			}
		};
		b.h = rows;
		return b;
	}

	private String chipLabel(Msg m) {
		String mark = switch (m.status) {
			case "running" -> "⟳";
			case "error" -> "✖";
			default -> "✓";
		};
		String t = m.text.length() > 48 ? m.text.substring(0, 47) + "…" : m.text;
		return m.tool + " · " + t + " " + mark;
	}

	private Block permCard(Conv c, Perm p, int aw) {
		List<FormattedCharSequence> detail = p.showDetail && p.detail != null && !p.detail.isEmpty()
			? font.split(Component.literal(p.detail), aw - 20) : List.of();
		int shown = Math.min(detail.size(), 16);
		final int h = 46 + (shown > 0 ? shown * 10 + 6 : 0);
		Block b = new Block() {
			void draw(GuiGraphics g, int x, int y, int w, int mx, int my) {
				g.fill(x, y, x + w, y + h, 0xFF2A1D12);
				g.renderOutline(x, y, w, h, ORANGE);
				g.renderOutline(x + 1, y + 1, w - 2, h - 2, 0x66E8843C);
				g.drawString(font, "Permission needed · " + p.tool, x + 8, y + 6, 0xFFFFB35C, false);
				if (p.detail != null && !p.detail.isEmpty()) {
					String link = p.showDetail ? "Hide details ▴" : "Show details ▾";
					int lw = font.width(link), lx = x + w - 8 - lw;
					boolean lh = mx >= lx && mx < lx + lw && my >= y + 4 && my < y + 15;
					g.drawString(font, link, lx, y + 6, lh ? TEXT : MUTED, false);
					hits.add(0, new Hit(lx - 2, y + 3, lw + 4, 12, () -> p.showDetail = !p.showDetail, null));
				}
				String sum = ellipsize(p.summary, w - 70);
				g.drawString(font, sum, x + 8, y + 18, TEXT, false);
				if (p.plus + p.minus > 0) {
					int dx = x + 12 + font.width(sum);
					g.drawString(font, "+" + p.plus, dx, y + 18, 0xFF7FD17F, false);
					g.drawString(font, "−" + p.minus, dx + font.width("+" + p.plus) + 5, y + 18, RED, false);
				}
				int dy = y + 30;
				if (shown > 0) {
					g.fill(x + 6, dy - 2, x + w - 6, dy + shown * 10 + 2, CODE_BG);
					for (int i = 0; i < shown; i++) {
						FormattedCharSequence l = detail.get(i);
						g.drawString(font, l, x + 10, dy, MUTED, false);
						dy += 10;
					}
					dy += 6;
				}
				int bx = x + 8;
				bx = button(g, mx, my, bx, dy, "Allow  [Y]", 0xFF2F6B3A, GREEN, () -> answer(c, p, true, false));
				bx = button(g, mx, my, bx, dy, "Always  [A]", 0xFF1F3340, 0xFF8FD0FF, () -> answer(c, p, true, true));
				button(g, mx, my, bx, dy, "Deny  [N]", 0xFF5A2323, RED, () -> answer(c, p, false, false));
			}
		};
		b.h = h;
		return b;
	}

	private int button(GuiGraphics g, int mx, int my, int x, int y, String label, int bg, int fg, Runnable r) {
		int w = font.width(label) + 14;
		boolean h = mx >= x && mx < x + w && my >= y && my < y + 13;
		g.fill(x, y, x + w, y + 13, h ? lighten(bg) : bg);
		g.renderOutline(x, y, w, 13, fg);
		g.drawString(font, label, x + 7, y + 3, h ? 0xFFFFFFFF : TEXT, false);
		hits.add(0, new Hit(x, y, w, 13, r, null));
		return x + w + 6;
	}

	private record Line(FormattedCharSequence seq, boolean code) {
	}

	private List<Line> codeAware(Msg m, int aw) {
		List<Line> out = new ArrayList<>();
		boolean fence = false;
		for (String raw : m.text.replace("\r", "").split("\n", -1)) {
			if (raw.trim().startsWith("```")) {
				fence = !fence;
				continue;
			}
			String l = fence ? raw : raw.replaceAll("\\*\\*(.+?)\\*\\*", "$1").replaceAll("^#{1,6}\\s+", "").replaceAll("`([^`]+)`", "$1");
			if (!fence && l.isBlank()) {
				if (!out.isEmpty() && !out.get(out.size() - 1).seq.equals(FormattedCharSequence.EMPTY)) out.add(new Line(FormattedCharSequence.EMPTY, false));
				continue;
			}
			for (FormattedCharSequence s : font.split(Component.literal(l.isEmpty() ? " " : l), aw - (fence ? 6 : 0))) out.add(new Line(s, fence));
		}
		while (!out.isEmpty() && out.get(out.size() - 1).seq == FormattedCharSequence.EMPTY) out.remove(out.size() - 1);
		return out;
	}

	private List<FormattedCharSequence> wrap(Msg m, int w) {
		if (m.cacheWidth != w || m.cacheLen != m.text.length() || m.cacheLines == null) {
			m.cacheLines = font.split(Component.literal(m.text), w);
			m.cacheWidth = w;
			m.cacheLen = m.text.length();
		}
		return m.cacheLines;
	}

	// =================================================================== input

	@Override
	public boolean keyPressed(int key, int scan, int mods) {
		Conv c = st.focusedConv();
		boolean ctrl = Screen.hasControlDown(), shift = Screen.hasShiftDown();
		String name = keyName(key, scan);
		if (dirsOpen) return dirsKey(key, name, ctrl, shift);
		if (key == GLFW.GLFW_KEY_ESCAPE) {
			if (sessionsOpen || modelOpen) {
				sessionsOpen = false;
				modelOpen = false;
				return true;
			}
			onClose();
			return true;
		}
		if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && !shift) {
			submit();
			return true;
		}
		if (key == GLFW.GLFW_KEY_TAB) {
			cycle(shift ? -1 : 1);
			return true;
		}
		if (ctrl && !shift) {
			if (name.length() == 1 && name.charAt(0) >= '1' && name.charAt(0) <= '9' || key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_9) {
				int n = (name.length() == 1 && Character.isDigit(name.charAt(0)) ? name.charAt(0) - '0' : key - GLFW.GLFW_KEY_0) - 1;
				int i = 0;
				for (Conv x : st.convs.values()) if (i++ == n) focus(x.id);
				return true;
			}
			switch (name) {
				case "t" -> {
					openDirs();
					return true;
				}
				case "w" -> {
					if (c != null) close(c.id);
					return true;
				}
				case "m" -> {
					if (c != null) act("set_mode", c.id);
					return true;
				}
				case "e" -> {
					toggleSessions();
					return true;
				}
				case "." , ";" -> {
					if (c != null) act("stop", c.id);
					return true;
				}
				// layout-aware editing shortcuts (AZERTY etc.): hand the widget the key code it expects
				case "a" -> {
					return input.keyPressed(GLFW.GLFW_KEY_A, scan, mods);
				}
				case "c" -> {
					return input.keyPressed(GLFW.GLFW_KEY_C, scan, mods);
				}
				case "v" -> {
					return input.keyPressed(GLFW.GLFW_KEY_V, scan, mods);
				}
				case "x" -> {
					return input.keyPressed(GLFW.GLFW_KEY_X, scan, mods);
				}
				default -> {
				}
			}
		}
		// permission shortcuts when the input is empty
		if (!ctrl && c != null && !c.perms.isEmpty() && input.getValue().isEmpty()) {
			Perm p = c.perms.values().iterator().next();
			switch (name) {
				case "y" -> {
					answer(c, p, true, false);
					swallowChar = true;
					return true;
				}
				case "a" -> {
					answer(c, p, true, true);
					swallowChar = true;
					return true;
				}
				case "n" -> {
					answer(c, p, false, false);
					swallowChar = true;
					return true;
				}
				default -> {
				}
			}
		}
		// prompt history
		if ((key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN) && !st.promptHistory.isEmpty()
			&& (input.getValue().isEmpty() || historyIndex >= 0) && !input.getValue().contains("\n")) {
			int n = st.promptHistory.size();
			historyIndex = key == GLFW.GLFW_KEY_UP ? (historyIndex < 0 ? n - 1 : Math.max(0, historyIndex - 1)) : historyIndex + 1;
			if (historyIndex >= n) {
				historyIndex = -1;
				input.setValue("");
			} else input.setValue(st.promptHistory.get(historyIndex));
			return true;
		}
		if (key == GLFW.GLFW_KEY_PAGE_UP || key == GLFW.GLFW_KEY_PAGE_DOWN) {
			if (c != null) c.scroll += (key == GLFW.GLFW_KEY_PAGE_UP ? 1 : -1) * (ph / 2);
			return true;
		}
		if (!input.isFocused()) setFocused(input);
		return input.keyPressed(key, scan, mods) || super.keyPressed(key, scan, mods);
	}

	@Override
	public boolean charTyped(char ch, int mods) {
		if (swallowChar) {
			swallowChar = false;
			return true;
		}
		if (dirsOpen) {
			if (ch >= ' ' && dirsFilter.length() < 300) {
				dirsFilter += ch;
				filterChanged();
			}
			return true;
		}
		historyIndex = -1;
		if (!input.isFocused()) setFocused(input);
		return input.charTyped(ch, mods);
	}

	@Override
	public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
		return super.mouseDragged(mx / k, my / k, button, dx / k, dy / k);
	}

	@Override
	public boolean mouseReleased(double mx, double my, int button) {
		return super.mouseReleased(mx / k, my / k, button);
	}

	@Override
	public boolean mouseClicked(double rmx, double rmy, int button) {
		double mx = rmx / k, my = rmy / k;
		for (Hit h : new ArrayList<>(hits)) {
			if (!h.in(mx, my)) continue;
			if (button == 0 && h.left != null) {
				h.left.run();
				return true;
			}
			if (button == 2 && h.middle != null) {
				h.middle.run();
				return true;
			}
		}
		if (button == 1) { // right click a message: copy it
			for (MsgRect r : msgRects) {
				if (mx >= r.x && mx < r.x + r.w && my >= r.y && my < r.y + r.h) {
					minecraft.keyboardHandler.setClipboard(r.text);
					st.lastNotice = "Copied to clipboard";
					st.lastNoticeError = false;
					st.lastNoticeAt = System.currentTimeMillis();
					return true;
				}
			}
		}
		boolean r = super.mouseClicked(mx, my, button);
		setFocused(input);
		return r;
	}

	@Override
	public boolean mouseScrolled(double rmx, double rmy, double sx, double sy) {
		double mx = rmx / k, my = rmy / k;
		if (dirsOpen) {
			dirsScroll -= (int) (sy * 28);
			return true;
		}
		Conv c = st.focusedConv();
		if (c != null && mx > x0 + SIDEBAR && my < y0 + ph - INPUT_H - 10) {
			c.scroll += (int) (sy * 20);
			return true;
		}
		return super.mouseScrolled(mx, my, sx, sy);
	}

	@Override
	public void onClose() {
		Conv c = st.focusedConv();
		if (c != null) c.draft = input.getValue();
		super.onClose();
	}

	// =================================================================== actions

	private void submit() {
		String text = input.getValue().strip();
		if (text.isEmpty()) return;
		Conv c = st.focusedConv();
		JsonObject a = obj("prompt");
		if (c != null) a.addProperty("conv", c.id);
		a.addProperty("text", text);
		ClaudeCraftClient.send(a);
		st.promptHistory.add(text);
		if (st.promptHistory.size() > 100) st.promptHistory.remove(0);
		historyIndex = -1;
		input.setValue("");
		if (c != null) {
			c.scroll = 0;
			c.draft = "";
		}
	}

	private void answer(Conv c, Perm p, boolean allow, boolean always) {
		JsonObject a = obj("perm_reply");
		a.addProperty("id", p.id);
		a.addProperty("allow", allow);
		a.addProperty("always", always);
		ClaudeCraftClient.send(a);
		c.perms.remove(p.id);
	}

	private void act(String type, String conv) {
		JsonObject a = obj(type);
		a.addProperty("conv", conv);
		ClaudeCraftClient.send(a);
	}

	private void focus(String id) {
		Conv now = st.focusedConv();
		if (now != null) now.draft = input.getValue();
		st.focused = id;
		Conv c = st.convs.get(id);
		if (c != null) {
			c.unread = 0;
			switchInputTo(c);
		}
		act("focus", id);
	}

	private void switchInputTo(Conv c) {
		input.setValue(c.draft);
		renderedConv = c.id;
		c.unread = 0;
	}

	private void cycle(int dir) {
		List<String> ids = new ArrayList<>(st.convs.keySet());
		if (ids.isEmpty()) return;
		int i = ids.indexOf(st.focused);
		focus(ids.get(Math.floorMod(i + dir, ids.size())));
	}

	private void close(String id) {
		JsonObject a = obj("close_conv");
		a.addProperty("conv", id);
		ClaudeCraftClient.send(a);
	}

	// hooks used by DemoDriver (dev screenshots)
	void demoOpenSessions() {
		if (!sessionsOpen) toggleSessions();
	}

	void demoOpenModelMenu() {
		modelOpen = true;
	}

	void demoOpenDirs(String path) {
		dirsOpen = true;
		requestDirs(path);
	}

	private void toggleSessions() {
		sessionsOpen = !sessionsOpen;
		if (sessionsOpen) {
			st.sessions = new ArrayList<>();
			ClaudeCraftClient.send(obj("sessions"));
		}
	}

	// =================================================================== helpers

	static JsonObject obj(String type) {
		JsonObject o = new JsonObject();
		o.addProperty("type", type);
		return o;
	}

	static int statusColor(String status) {
		return switch (status) {
			case "thinking" -> YELLOW;
			case "waiting" -> ORANGE;
			case "error" -> RED;
			case "idle" -> GREEN;
			default -> IDLE;
		};
	}

	static String modeLabel(String mode) {
		return switch (mode) {
			case "auto" -> "auto";
			case "edits" -> "auto-edits";
			default -> "manual";
		};
	}

	static String statusLabel(Conv c) {
		long s = (System.currentTimeMillis() - c.statusSince) / 1000;
		return switch (c.status) {
			case "thinking" -> "working · " + s + " s";
			case "waiting" -> "needs your permission";
			case "error" -> "error";
			default -> "ready";
		};
	}

	private void dot(GuiGraphics g, int x, int y, int color) {
		g.fill(x - 2, y - 3, x + 3, y + 2, color);
		g.fill(x - 3, y - 2, x + 4, y + 1, color);
	}

	private void dashed(GuiGraphics g, int x, int y, int w, int h, int color) {
		for (int i = 0; i < w; i += 4) {
			g.fill(x + i, y, x + Math.min(w, i + 2), y + 1, color);
			g.fill(x + i, y + h - 1, x + Math.min(w, i + 2), y + h, color);
		}
		for (int i = 0; i < h; i += 4) {
			g.fill(x, y + i, x + 1, y + Math.min(h, i + 2), color);
			g.fill(x + w - 1, y + i, x + w, y + Math.min(h, i + 2), color);
		}
	}

	private void center(GuiGraphics g, String s, int cx, int y, int color) {
		g.drawString(font, s, cx - font.width(s) / 2, y, color, false);
	}

	String ellipsize(String s, int w) {
		if (s == null) return "";
		if (w <= 8) return "";
		if (font.width(s) <= w) return s;
		return font.plainSubstrByWidth(s, w - font.width("…")) + "…";
	}

	private static int lighten(int c) {
		int r = Math.min(255, ((c >> 16) & 0xFF) + 24), g = Math.min(255, ((c >> 8) & 0xFF) + 24), b = Math.min(255, (c & 0xFF) + 24);
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}

	private static String keyName(int key, int scan) {
		String n = GLFW.glfwGetKeyName(key, scan);
		return n == null ? "" : n.toLowerCase(Locale.ROOT);
	}
}
