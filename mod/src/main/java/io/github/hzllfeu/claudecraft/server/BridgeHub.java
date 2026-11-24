package io.github.hzllfeu.claudecraft.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.hzllfeu.claudecraft.ClaudeCraft;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loopback socket that player bridges connect to (through their own `ssh -L`). Newline-delimited JSON.
 * The first line is {"type":"auth","token":"…","host":"…"}; the token identifies the player (see /claude link).
 * One live connection per player: a new one replaces the old.
 */
public final class BridgeHub {
	public interface Listener {
		void onConnect(Config.Link link, String host);

		void onDisconnect(Config.Link link);

		void onEvent(Config.Link link, JsonObject event);
	}

	private static final class Conn {
		final Config.Link link;
		final Socket socket;
		final Writer writer;
		final String host;

		Conn(Config.Link link, Socket socket, String host) throws IOException {
			this.link = link;
			this.socket = socket;
			this.host = host;
			this.writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
		}
	}

	private final Config config;
	private final Listener listener;
	private final Map<String, Conn> byName = new ConcurrentHashMap<>(); // lower-case player name -> conn
	private volatile ServerSocket serverSocket;
	private volatile boolean running;

	public BridgeHub(Config config, Listener listener) {
		this.config = config;
		this.listener = listener;
	}

	public void start() {
		running = true;
		Thread t = new Thread(this::acceptLoop, "ClaudeCraft-hub");
		t.setDaemon(true);
		t.start();
	}

	public void stop() {
		running = false;
		for (Conn c : byName.values()) close(c.socket);
		try {
			if (serverSocket != null) serverSocket.close();
		} catch (IOException ignored) {
		}
	}

	private static String key(Config.Link l) {
		return l.name.toLowerCase(java.util.Locale.ROOT);
	}

	public boolean isConnected(Config.Link link) {
		return link != null && byName.containsKey(key(link));
	}

	public String host(Config.Link link) {
		Conn c = link == null ? null : byName.get(key(link));
		return c == null ? "" : c.host;
	}

	public void disconnect(Config.Link link) {
		Conn c = byName.get(key(link));
		if (c != null) close(c.socket);
	}

	public boolean send(Config.Link link, JsonObject msg) {
		Conn c = link == null ? null : byName.get(key(link));
		if (c == null) return false;
		synchronized (c) {
			try {
				c.writer.write(msg.toString());
				c.writer.write('\n');
				c.writer.flush();
				return true;
			} catch (IOException e) {
				close(c.socket);
				return false;
			}
		}
	}

	private void acceptLoop() {
		try (ServerSocket ss = new ServerSocket()) {
			serverSocket = ss;
			ss.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), config.port));
			ClaudeCraft.LOG.info("ClaudeCraft hub listening on 127.0.0.1:{}", config.port);
			while (running) {
				Socket s = ss.accept();
				Thread h = new Thread(() -> handle(s), "ClaudeCraft-bridge");
				h.setDaemon(true);
				h.start();
			}
		} catch (IOException e) {
			if (running) ClaudeCraft.LOG.error("Hub socket stopped", e);
		}
	}

	private void handle(Socket s) {
		Conn conn = null;
		try {
			s.setTcpNoDelay(true);
			s.setSoTimeout(15_000);
			BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
			String first = in.readLine();
			Config.Link link = null;
			String host = "";
			try {
				JsonObject o = JsonParser.parseString(first == null ? "" : first).getAsJsonObject();
				if ("auth".equals(o.get("type").getAsString())) link = config.findByToken(o.get("token").getAsString());
				if (o.has("host")) host = o.get("host").getAsString();
			} catch (RuntimeException ignored) {
			}
			if (link == null) {
				ClaudeCraft.LOG.warn("Rejected a bridge connection (unknown token)");
				s.close();
				return;
			}
			s.setSoTimeout(0);
			conn = new Conn(link, s, host);
			Conn old = byName.put(key(link), conn);
			if (old != null) close(old.socket);
			ClaudeCraft.LOG.info("Bridge of {} connected from {}", link.name, host);
			listener.onConnect(link, host);
			String line;
			while ((line = in.readLine()) != null) {
				if (line.isBlank()) continue;
				try {
					listener.onEvent(link, JsonParser.parseString(line).getAsJsonObject());
				} catch (RuntimeException e) {
					ClaudeCraft.LOG.warn("Bad message from {}'s bridge: {}", link.name, e.toString());
				}
			}
		} catch (IOException ignored) {
		} finally {
			close(s);
			if (conn != null && byName.remove(key(conn.link), conn)) {
				ClaudeCraft.LOG.info("Bridge of {} disconnected", conn.link.name);
				listener.onDisconnect(conn.link);
			}
		}
	}

	private static void close(Socket s) {
		try {
			if (s != null) s.close();
		} catch (IOException ignored) {
		}
	}
}
