package io.github.hzllfeu.claudecraft.client;

import com.google.gson.JsonObject;
import io.github.hzllfeu.claudecraft.ClaudeCraft;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/**
 * DEV ONLY, inert unless -Dclaudecraft.autoshot=true ({@code ./gradlew runClient -Pautoshot}).
 * Creates a singleplayer world (which runs the server side of the mod), drives real conversations through a bridge
 * connected to it, stages a few scenes in nice places and saves screenshots to run/screenshots/.
 * The conversations run in the sample projects of {@code -Dclaudecraft.demoRoot} (default ~/claudecraft-demo):
 * redstone-alu, villager-market (with an empty docs/ folder) and server-dashboard.
 */
final class DemoDriver {
	static final boolean ENABLED = Boolean.getBoolean("claudecraft.autoshot");
	private static final String ROOT = System.getProperty("claudecraft.demoRoot", "~/claudecraft-demo");

	private record Step(String name, long timeoutMs, BooleanSupplier ready, Runnable run) {
	}

	private record Cam(double x, double y, double z, float yaw, float pitch) {
	}

	private static final List<Step> STEPS = new ArrayList<>();
	private static int step;
	private static long stepStart;
	private static boolean worldRequested;
	private static CompletableFuture<Cam> cam;
	private static String convA, convB, convC; // redstone-alu (opus, auto), villager-market (sonnet, manual), server-dashboard (haiku)
	private static ClientState.Toast toastBefore;

	private DemoDriver() {
	}

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	private static ClientState st() {
		return ClientState.INSTANCE;
	}

	private static long dt() {
		return System.currentTimeMillis() - stepStart;
	}

	private static void step(String name, long timeoutMs, BooleanSupplier ready, Runnable run) {
		STEPS.add(new Step(name, timeoutMs, ready, run));
	}

	static {
		step("setup", 60_000, () -> dt() > 6000 && mc().player != null, () -> {
			for (String c : List.of("gamerule doDaylightCycle false", "gamerule doWeatherCycle false", "weather clear",
				"gamerule sendCommandFeedback false", "gamemode spectator")) command(c);
			mc().options.pauseOnLostFocus = false;
			mc().options.renderDistance().set(16);
		});
		step("bridge", 120_000, () -> dt() > 2000 && st().bridgeConnected, () -> newConv("redstone-alu"));
		step("conv A", 20_000, () -> conv("redstone-alu") != null, () -> {
			convA = conv("redstone-alu");
			set(convA, "set_model", "model", "opus");
			set(convA, "set_mode", "mode", "auto");
			newConv("villager-market");
		});
		step("conv B", 20_000, () -> conv("villager-market") != null, () -> {
			convB = conv("villager-market");
			set(convB, "set_model", "model", "sonnet");
			newConv("server-dashboard");
		});
		step("conv C", 20_000, () -> conv("server-dashboard") != null, () -> {
			convC = conv("server-dashboard");
			set(convC, "set_model", "model", "haiku");
			prompt(convC, "In two sentences, what is this project for?");
			cam = locate(Biomes.CHERRY_GROVE, false, 26, 9, 200);
		});

		// 1. live activity, in a cherry grove
		step("cherry grove", 120_000, () -> cam.isDone(), () -> {
			teleport(4000);
			prompt(convA, "Add a subtract(x, y) function to src/alu.js that reuses the adder (two's complement), "
				+ "add tests for it in test/alu.test.js, then run the test suite.");
		});
		step("open A", 30_000, () -> dt() > 5000, () -> open(convA));
		step("shot live", 180_000, () -> live(convA) && tools(convA) >= 2, () -> shot("01-live-activity"));

		// 2. permission card, in the mountains
		step("mountains", 5_000, () -> dt() > 800, () -> {
			mc().setScreen(null);
			cam = locate(Biomes.JAGGED_PEAKS, false, 40, 6, 135);
		});
		step("tp mountains", 120_000, () -> cam.isDone(), () -> {
			teleport(2500);
			prompt(convB, "Write docs/API.md documenting every route of src/server.js, with one curl example each.");
		});
		step("perm B", 240_000, () -> dt() > 6000 && perm(convB) != null, () -> {
			open(convB);
			perm(convB).showDetail = true;
		});
		step("shot permission", 10_000, () -> dt() > 1500, () -> shot("02-permission"));

		// 3. action bar notification, in a village at sunset
		step("village", 5_000, () -> dt() > 500, () -> {
			mc().setScreen(null);
			cam = locate(null, true, 34, 12, 0);
		});
		step("tp village", 120_000, () -> cam.isDone(), () -> teleport(12350));
		step("allow B", 30_000, () -> dt() > 7000, () -> {
			ClientState.Perm p = perm(convB);
			if (p != null) answer(p, true);
			toastBefore = st().toasts.isEmpty() ? null : last();
			mc().options.hideGui = false;
		});
		step("shot notification", 240_000, () -> !st().toasts.isEmpty() && last() != toastBefore && last().kind().equals("done") && dt() > 200,
			() -> shot("03-notification"));

		// 4. a finished answer (thinking, tool calls, reply)
		step("answer A", 300_000, () -> dt() > 1500 && done(convA), () -> open(convA));
		step("shot answer", 10_000, () -> dt() > 1500, () -> shot("04-answer"));

		// 5. model menu + context, 6. folder picker, in the badlands
		step("badlands", 5_000, () -> dt() > 500, () -> {
			mc().setScreen(null);
			cam = locate(Biomes.BADLANDS, false, 30, 10, 300);
		});
		step("tp badlands", 120_000, () -> cam.isDone(), () -> teleport(6000));
		step("model menu", 10_000, () -> dt() > 6000, () -> {
			open(convB);
			if (mc().screen instanceof ClaudeScreen s) s.demoOpenModelMenu();
		});
		step("shot model", 10_000, () -> dt() > 1500, () -> shot("05-model-and-context"));
		step("folder picker", 10_000, () -> dt() > 500, () -> {
			open(convC);
			if (mc().screen instanceof ClaudeScreen s) s.demoOpenDirs(ROOT);
		});
		step("shot folders", 20_000, () -> dt() > 2500 && st().dirsAt > stepStart, () -> shot("06-folder-picker"));
		step("done", 10_000, () -> dt() > 2000, () -> {
			ClaudeCraft.LOG.info("[demo] done");
			mc().stop();
		});
	}

	static void tick(Minecraft mc) {
		if (!ENABLED) return;
		if (mc.level == null) {
			if (!worldRequested && mc.screen instanceof TitleScreen) {
				worldRequested = true;
				ClaudeCraft.LOG.info("[demo] creating world");
				mc.createWorldOpenFlows().createFreshLevel("ClaudeDemo",
					new LevelSettings("ClaudeDemo", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
					new WorldOptions(-4172144997902289642L, true, false),
					reg -> reg.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.NORMAL).value().createWorldDimensions(),
					mc.screen);
			}
			return;
		}
		if (stepStart == 0) stepStart = System.currentTimeMillis();
		// conversations other than B run unattended: accept whatever they ask
		for (String id : new String[]{convA, convC}) {
			ClientState.Perm p = perm(id);
			if (p != null) answer(p, true);
		}
		if (step >= STEPS.size()) return;
		Step s = STEPS.get(step);
		boolean ready = s.ready().getAsBoolean();
		if (!ready && dt() < s.timeoutMs()) return;
		ClaudeCraft.LOG.info("[demo] {}{}", s.name(), ready ? "" : " (timed out)");
		s.run().run();
		step++;
		stepStart = System.currentTimeMillis();
	}

	// ------------------------------------------------------------------ world and camera

	/** Finds a biome (or the nearest village) and a camera spot looking at it from {@code dist} blocks, {@code height} above ground. */
	private static CompletableFuture<Cam> locate(ResourceKey<Biome> biome, boolean village, double dist, double height, double sideDeg) {
		IntegratedServer server = mc().getSingleplayerServer();
		BlockPos origin = mc().player.blockPosition();
		return server.submit(() -> {
			ServerLevel lvl = server.overworld();
			BlockPos target = null;
			if (village) target = lvl.findNearestMapStructure(StructureTags.VILLAGE, origin, 100, false);
			else {
				var found = lvl.findClosestBiome3d(h -> h.is(biome), origin, 6400, 32, 64);
				if (found != null) target = found.getFirst();
			}
			if (target == null) target = origin;
			int ty = surface(lvl, target.getX(), target.getZ());
			double rad = Math.toRadians(sideDeg);
			double cx = target.getX() + Math.cos(rad) * dist, cz = target.getZ() + Math.sin(rad) * dist;
			double cy = Math.max(surface(lvl, (int) cx, (int) cz) + 3, ty) + height;
			double dx = target.getX() - cx, dz = target.getZ() - cz, dy = ty + 2 - cy;
			float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90);
			float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
			ClaudeCraft.LOG.info("[demo] camera {} {} {} -> {}", (int) cx, (int) cy, (int) cz, target);
			return new Cam(cx, cy, cz, yaw, pitch);
		});
	}

	private static int surface(ServerLevel lvl, int x, int z) {
		lvl.getChunk(x >> 4, z >> 4);
		return lvl.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
	}

	private static void teleport(int time) {
		Cam c = cam.join();
		command(String.format(java.util.Locale.ROOT, "tp @s %.1f %.1f %.1f %.1f %.1f", c.x(), c.y(), c.z(), c.yaw(), c.pitch()));
		command("time set " + time);
		mc().gui.getChat().clearMessages(false);
	}

	private static void command(String c) {
		mc().player.connection.sendCommand(c);
	}

	private static void open(String conv) {
		st().focused = conv;
		mc().options.hideGui = true; // no crosshair behind the screen
		mc().gui.getChat().clearMessages(false);
		mc().setScreen(new ClaudeScreen());
	}

	private static void shot(String name) {
		Screenshot.grab(mc().gameDirectory, "claudecraft-" + name + ".png", mc().getMainRenderTarget(),
			msg -> ClaudeCraft.LOG.info("[demo] screenshot {}: {}", name, msg.getString()));
	}

	// ------------------------------------------------------------------ conversations

	private static String conv(String folder) {
		for (ClientState.Conv c : st().convs.values()) if (c.cwd.endsWith("/" + folder)) return c.id;
		return null;
	}

	private static void newConv(String folder) {
		JsonObject a = ClaudeScreen.obj("new_conv");
		a.addProperty("cwd", ROOT + "/" + folder);
		ClaudeCraftClient.send(a);
	}

	private static void set(String conv, String type, String key, String value) {
		JsonObject a = ClaudeScreen.obj(type);
		a.addProperty("conv", conv);
		a.addProperty(key, value);
		ClaudeCraftClient.send(a);
	}

	private static void prompt(String conv, String text) {
		set(conv, "prompt", "text", text);
	}

	private static void answer(ClientState.Perm p, boolean allow) {
		JsonObject a = ClaudeScreen.obj("perm_reply");
		a.addProperty("id", p.id);
		a.addProperty("allow", allow);
		ClaudeCraftClient.send(a);
		for (ClientState.Conv c : st().convs.values()) c.perms.remove(p.id);
	}

	private static ClientState.Conv get(String id) {
		return id == null ? null : st().convs.get(id);
	}

	private static ClientState.Perm perm(String id) {
		ClientState.Conv c = get(id);
		return c == null || c.perms.isEmpty() ? null : c.perms.values().iterator().next();
	}

	private static boolean live(String id) {
		ClientState.Conv c = get(id);
		return c != null && !c.phase.isEmpty() && !"starting".equals(c.phase);
	}

	private static int tools(String id) {
		ClientState.Conv c = get(id);
		return c == null ? 0 : (int) c.msgs.stream().filter(m -> "tool".equals(m.role)).count();
	}

	private static boolean done(String id) {
		ClientState.Conv c = get(id);
		return c != null && "idle".equals(c.status) && c.msgs.stream().anyMatch(m -> "result".equals(m.role));
	}

	private static ClientState.Toast last() {
		List<ClientState.Toast> t = st().toasts;
		return t.get(t.size() - 1);
	}
}
