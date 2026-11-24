package io.github.hzllfeu.claudecraft.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Everything the screen and HUD draw, fed by bridge events. Client thread only. */
public final class ClientState {
	public static final ClientState INSTANCE = new ClientState();

	public boolean serverHasMod;
	public boolean linked = true;
	public boolean bridgeConnected;
	public String host = "";
	public final Map<String, Conv> convs = new LinkedHashMap<>();
	public String focused;
	public List<Session> sessions = new ArrayList<>();
	public long sessionsReceivedAt;
	public final List<Toast> toasts = new ArrayList<>();
	public final List<String> promptHistory = new ArrayList<>();
	public String lastNotice = "";
	public long lastNoticeAt;
	public boolean lastNoticeError;
	/** plan usage windows (5 h session, week…), as reported by the bridge */
	public List<UsageWin> usage = new ArrayList<>();
	public long usageAt;
	/** folder picker listing */
	public String dirsPath = "", dirsParent = "", dirsError = "";
	public List<DirEntry> dirsEntries = new ArrayList<>();
	public List<String> dirsRecent = new ArrayList<>();
	public long dirsAt;

	public record UsageWin(String key, String label, double pct, String resets) {
	}

	public record DirEntry(String name, boolean git) {
	}

	public static final class Conv {
		public String id, title = "", project = "", cwd = "", branch = "", status = "idle", mode = "manual", sessionId = "", activity = "";
		public String model = "", modelName = "";
		public long ctxUsed, ctxWindow;
		/** live progress of the running turn: phase (starting|requesting|thinking|writing|tool), detail, output tokens */
		public String phase = "", liveDetail = "";
		public int liveTokens;
		public long liveSince;
		public long statusSince = System.currentTimeMillis();
		public int unread;
		public final List<Msg> msgs = new ArrayList<>();
		public final Map<String, Perm> perms = new LinkedHashMap<>();
		public int scroll; // pixels scrolled up from the bottom
		public String draft = "";
	}

	public static final class Msg {
		public String id, role = "", text = "", tool = "", status = "";
		public boolean ok = true, fin = true, expanded;
		public int secs;
		// layout cache
		public int cacheWidth = -1, cacheLen = -1;
		public List<FormattedCharSequence> cacheLines;
	}

	public static final class Perm {
		public String id, tool, summary, detail;
		public int plus, minus;
		public boolean showDetail;
	}

	public record Session(String n, String id, String title, String project, String ago, boolean open) {
	}

	public record Toast(String kind, String title, String text, long createdAt) {
	}

	public Conv focusedConv() {
		Conv c = focused == null ? null : convs.get(focused);
		if (c == null && !convs.isEmpty()) {
			c = convs.values().iterator().next();
			focused = c.id;
		}
		return c;
	}

	public void reset() {
		serverHasMod = false;
		bridgeConnected = false;
		linked = true;
		host = "";
		convs.clear();
		focused = null;
		sessions.clear();
		toasts.clear();
	}

	public UsageWin usage(String key) {
		for (UsageWin w : usage) if (w.key().equals(key)) return w;
		return null;
	}

	// ------------------------------------------------------------------ events

	/** @return a toast to announce (for sounds / action bar), or null */
	public Toast apply(JsonObject ev) {
		switch (s(ev, "type")) {
			case "bridge" -> {
				bridgeConnected = ev.get("connected").getAsBoolean();
				if (ev.has("linked")) linked = ev.get("linked").getAsBoolean();
				if (ev.has("host")) host = s(ev, "host");
			}
			case "state" -> {
				Map<String, Conv> old = new LinkedHashMap<>(convs);
				convs.clear();
				JsonArray arr = ev.getAsJsonArray("convs");
				if (arr != null) for (JsonElement e : arr) {
					JsonObject o = e.getAsJsonObject();
					Conv c = old.getOrDefault(s(o, "id"), new Conv());
					fill(c, o);
					convs.put(c.id, c);
				}
				String f = s(ev, "focused");
				if (!f.isEmpty() && (focused == null || !convs.containsKey(focused))) focused = f;
				if (ev.has("host")) host = s(ev, "host");
				bridgeConnected = true;
			}
			case "conv" -> {
				JsonObject o = ev.getAsJsonObject("conv");
				Conv c = convs.computeIfAbsent(s(o, "id"), k -> new Conv());
				String before = c.status;
				fill(c, o);
				if (!before.equals(c.status)) c.statusSince = System.currentTimeMillis();
				if (focused == null) focused = c.id;
			}
			case "conv_closed" -> {
				convs.remove(s(ev, "conv"));
				if (s(ev, "conv").equals(focused)) focused = convs.isEmpty() ? null : convs.keySet().iterator().next();
			}
			case "history" -> {
				Conv c = convs.get(s(ev, "conv"));
				if (c != null) {
					c.msgs.clear();
					for (JsonElement e : ev.getAsJsonArray("messages")) c.msgs.add(msg(e.getAsJsonObject(), new Msg()));
				}
			}
			case "msg" -> {
				Conv c = convs.get(s(ev, "conv"));
				if (c != null) {
					Msg m = msg(ev.getAsJsonObject("m"), new Msg());
					c.msgs.add(m);
					if (c.msgs.size() > 400) c.msgs.remove(0);
					if (!c.id.equals(focused) && ("assistant".equals(m.role) || "result".equals(m.role))) c.unread++;
				}
			}
			case "delta" -> {
				Conv c = convs.get(s(ev, "conv"));
				Msg m = c == null ? null : find(c, s(ev, "id"));
				if (m != null) m.text += s(ev, "text");
			}
			case "msg_update" -> {
				Conv c = convs.get(s(ev, "conv"));
				JsonObject o = ev.getAsJsonObject("m");
				Msg m = c == null ? null : find(c, s(o, "id"));
				if (m != null) msg(o, m);
			}
			case "perm" -> {
				Conv c = convs.get(s(ev, "conv"));
				if (c != null) {
					Perm p = c.perms.getOrDefault(s(ev, "id"), new Perm()); // re-sent on sync: keep its UI state
					p.id = s(ev, "id");
					p.tool = s(ev, "tool");
					p.summary = s(ev, "summary");
					p.detail = s(ev, "detail");
					p.plus = ev.has("plus") ? ev.get("plus").getAsInt() : 0;
					p.minus = ev.has("minus") ? ev.get("minus").getAsInt() : 0;
					c.perms.put(p.id, p);
				}
			}
			case "perm_done" -> {
				Conv c = convs.get(s(ev, "conv"));
				if (c != null) c.perms.remove(s(ev, "id"));
			}
			case "sessions" -> {
				sessions = new ArrayList<>();
				for (JsonElement e : ev.getAsJsonArray("items")) {
					JsonObject o = e.getAsJsonObject();
					sessions.add(new Session(s(o, "n"), s(o, "id"), s(o, "title"), s(o, "project"), s(o, "ago"), o.has("open") && o.get("open").getAsBoolean()));
				}
				sessionsReceivedAt = System.currentTimeMillis();
			}
			case "toast" -> {
				Toast t = new Toast(s(ev, "kind"), s(ev, "title"), s(ev, "text"), System.currentTimeMillis());
				toasts.add(t);
				while (toasts.size() > 4) toasts.remove(0);
				return t;
			}
			case "live" -> {
				Conv c = convs.get(s(ev, "conv"));
				if (c != null) {
					String ph = s(ev, "phase");
					if (c.phase.isEmpty() && !ph.isEmpty()) c.liveSince = System.currentTimeMillis();
					c.phase = ph;
					c.liveDetail = s(ev, "detail");
					c.liveTokens = ev.has("tokens") ? ev.get("tokens").getAsInt() : 0;
				}
			}
			case "usage" -> {
				usage = new ArrayList<>();
				JsonArray arr = ev.getAsJsonArray("windows");
				if (arr != null) for (JsonElement e : arr) {
					JsonObject o = e.getAsJsonObject();
					usage.add(new UsageWin(s(o, "key"), s(o, "label"), o.has("pct") ? o.get("pct").getAsDouble() : 0, s(o, "resets")));
				}
				usageAt = System.currentTimeMillis();
			}
			case "dirs" -> {
				dirsPath = s(ev, "path");
				dirsParent = s(ev, "parent");
				dirsError = s(ev, "error");
				dirsEntries = new ArrayList<>();
				JsonArray arr = ev.getAsJsonArray("entries");
				if (arr != null) for (JsonElement e : arr) {
					JsonObject o = e.getAsJsonObject();
					dirsEntries.add(new DirEntry(s(o, "name"), o.has("git") && o.get("git").getAsBoolean()));
				}
				dirsRecent = new ArrayList<>();
				JsonArray rec = ev.getAsJsonArray("recent");
				if (rec != null) for (JsonElement e : rec) dirsRecent.add(e.getAsString());
				dirsAt = System.currentTimeMillis();
			}
			case "notice" -> {
				lastNotice = s(ev, "text");
				lastNoticeError = "error".equals(s(ev, "style"));
				lastNoticeAt = System.currentTimeMillis();
			}
			default -> {
			}
		}
		return null;
	}

	private static void fill(Conv c, JsonObject o) {
		c.id = s(o, "id");
		c.title = s(o, "title");
		c.project = s(o, "project");
		c.cwd = s(o, "cwd");
		c.branch = s(o, "branch");
		c.status = s(o, "status");
		c.mode = s(o, "mode");
		c.sessionId = s(o, "sessionId");
		c.activity = s(o, "activity");
		c.model = s(o, "model");
		c.modelName = s(o, "modelName");
		c.ctxUsed = o.has("ctxUsed") ? o.get("ctxUsed").getAsLong() : 0;
		c.ctxWindow = o.has("ctxWindow") ? o.get("ctxWindow").getAsLong() : 0;
		if (!"thinking".equals(c.status) && !"waiting".equals(c.status)) c.phase = "";
	}

	private static Msg msg(JsonObject o, Msg m) {
		m.id = s(o, "id");
		m.role = s(o, "role");
		m.text = s(o, "text");
		m.tool = s(o, "tool");
		m.status = s(o, "status");
		m.ok = !o.has("ok") || o.get("ok").getAsBoolean();
		m.fin = !("assistant".equals(m.role) || "thinking".equals(m.role)) || (o.has("final") && o.get("final").getAsBoolean());
		m.secs = o.has("secs") ? o.get("secs").getAsInt() : 0;
		m.cacheWidth = -1;
		return m;
	}

	private static Msg find(Conv c, String id) {
		for (int i = c.msgs.size() - 1; i >= 0; i--) if (c.msgs.get(i).id.equals(id)) return c.msgs.get(i);
		return null;
	}

	static String s(JsonObject o, String k) {
		if (o == null) return "";
		JsonElement e = o.get(k);
		if (e == null || e.isJsonNull()) return "";
		return e.isJsonPrimitive() ? e.getAsString() : e.toString();
	}
}
