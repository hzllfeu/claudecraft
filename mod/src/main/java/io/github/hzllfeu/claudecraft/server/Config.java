package io.github.hzllfeu.claudecraft.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.hzllfeu.claudecraft.ClaudeCraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** config/claudecraft.json */
public final class Config {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	/** Loopback port the bridges connect to through `ssh -L`. Never bound on a public interface. */
	public int port = 25580;
	/** Players allowed to use /claude and link their own bridge. Operators (level 4) always can. */
	public List<String> allowedPlayers = new ArrayList<>();
	/** One entry per player bridge. The token identifies the player: keep it secret. */
	public List<Link> links = new ArrayList<>();

	public static final class Link {
		public String name;
		public String uuid; // filled the first time the player joins (online-mode UUID)
		public String token;

		public boolean matches(UUID id, String playerName) {
			if (uuid != null && !uuid.isEmpty()) return uuid.equalsIgnoreCase(id.toString());
			return name != null && name.equalsIgnoreCase(playerName);
		}
	}

	private transient Path file;

	public static Config load(Path file) {
		Config cfg = null;
		try {
			if (Files.exists(file)) cfg = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Config.class);
		} catch (IOException | RuntimeException e) {
			ClaudeCraft.LOG.error("Could not read {}, using defaults", file, e);
		}
		if (cfg == null) cfg = new Config();
		if (cfg.allowedPlayers == null) cfg.allowedPlayers = new ArrayList<>();
		if (cfg.links == null) cfg.links = new ArrayList<>();
		cfg.file = file;
		cfg.save();
		return cfg;
	}

	public synchronized void save() {
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(this), StandardCharsets.UTF_8);
		} catch (IOException e) {
			ClaudeCraft.LOG.error("Could not write {}", file, e);
		}
	}

	public boolean isAllowed(UUID uuid, String name) {
		String u = uuid.toString().toLowerCase(Locale.ROOT), n = name.toLowerCase(Locale.ROOT);
		for (String e : allowedPlayers) {
			String x = e.trim().toLowerCase(Locale.ROOT);
			if (x.equals(u) || x.equals(n)) return true;
		}
		return findLink(uuid, name) != null;
	}

	public synchronized Link findLink(UUID uuid, String name) {
		for (Link l : links) if (l.matches(uuid, name)) return l;
		return null;
	}

	public synchronized Link findByToken(String token) {
		for (Link l : links) if (l.token != null && l.token.equals(token)) return l;
		return null;
	}

	/** Creates (or rotates) the token of a player and returns it. */
	public synchronized String link(String name, UUID uuid) {
		Link l = null;
		for (Link x : links) if (x.name != null && x.name.equalsIgnoreCase(name)) l = x;
		if (l == null) {
			l = new Link();
			links.add(l);
		}
		l.name = name;
		l.uuid = uuid == null ? null : uuid.toString();
		byte[] b = new byte[24];
		new SecureRandom().nextBytes(b);
		l.token = HexFormat.of().formatHex(b);
		save();
		return l.token;
	}

	public synchronized boolean unlink(String name) {
		boolean removed = links.removeIf(l -> l.name != null && l.name.equalsIgnoreCase(name));
		if (removed) save();
		return removed;
	}
}
