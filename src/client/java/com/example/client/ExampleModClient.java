package com.example.client;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.class_1268;
import net.minecraft.class_1297;
import net.minecraft.class_1299;
import net.minecraft.class_1309;
import net.minecraft.class_1657;
import net.minecraft.class_1792;
import net.minecraft.class_1799;
import net.minecraft.class_243;
import net.minecraft.class_2561;
import net.minecraft.class_3730;
import net.minecraft.class_638;
import net.minecraft.class_746;
import net.minecraft.class_7923;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class ExampleModClient implements ClientModInitializer {
	private static final int MAGIC = 0x52570003; // file format v3

	private record EntityFrame(int id, String type, double x, double y, double z,
							   float yRot, float xRot, float head,
							   boolean swing, int hurt, int item,
							   boolean sneak, boolean sprint) {}

	private record Frame(double x, double y, double z, float yRot, float xRot,
						 boolean swing, boolean sneak, boolean sprint,
						 List<EntityFrame> ents) {}

	private static List<Frame> frames = new ArrayList<>();
	private static boolean recording = false;
	private static boolean playing = false;
	private static int playIndex = 0;

	private static final Map<Integer, class_1297> ghosts = new HashMap<>();
	private static final Map<Integer, Integer> lastHurt = new HashMap<>();
	private static final Set<String> badTypes = new HashSet<>();

	@Override
	public void onInitializeClient() {
		registerCommands();

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			class_746 p = client.field_1724;
			class_638 level = client.field_1687;

			if (p == null || level == null) {
				recording = false;
				playing = false;
				ghosts.clear();
				lastHurt.clear();
				return;
			}

			if (recording) {
				List<EntityFrame> list = new ArrayList<>();
				for (class_1297 e : level.method_18112()) {
					if (e instanceof class_1657) continue;
					if (e.method_5858(p) > 48.0 * 48.0) continue;

					boolean swing = false;
					int hurt = 0;
					int item = 0;
					if (e instanceof class_1309 le) {
						swing = le.field_6252 && le.field_6279 == 0;
						hurt = le.field_6235;
						item = class_7923.field_41178.method_10206(le.method_6047().method_7909());
					}

					list.add(new EntityFrame(
						e.method_5628(),
						class_1299.method_5890(e.method_5864()).toString(),
						e.method_23317(), e.method_23318(), e.method_23321(),
						e.method_36454(), e.method_36455(), e.method_5791(),
						swing, hurt, item,
						e.method_5715(), e.method_5624()));
				}
				boolean pSwing = p.field_6252 && p.field_6279 == 0;
				frames.add(new Frame(
					p.method_23317(), p.method_23318(), p.method_23321(), p.method_36454(), p.method_36455(),
					pSwing, p.method_5715(), p.method_5624(), list));
			}

			if (playing) {
				if (playIndex >= frames.size()) {
					playing = false;
					clearGhosts();
					say(p, "Replay khatam.");
					return;
				}
				Frame f = frames.get(playIndex++);
				p.method_5814(f.x(), f.y(), f.z());
				p.method_36456(f.yRot());
				p.method_36457(f.xRot());
				p.method_18799(class_243.field_1353);
				p.method_5660(f.sneak());
				p.method_5728(f.sprint());
				if (f.swing()) {
					p.method_6104(class_1268.field_5808);
				}
				updateGhosts(level, f);
			}
		});
	}

	private static void updateGhosts(class_638 level, Frame f) {
		Set<Integer> present = new HashSet<>();

		for (EntityFrame ef : f.ents()) {
			present.add(ef.id());
			class_1297 g = ghosts.get(ef.id());
			if (g == null) {
				g = spawnGhost(level, ef);
				if (g == null) continue;
				ghosts.put(ef.id(), g);
			}
			place(g, ef);
			if (g instanceof class_1309 le) {
				if (ef.swing()) {
					le.method_6104(class_1268.field_5808);
				}
				// hurt: rising edge par vanilla "hurt" event (2) chalao.
				// Isse hurt sound, leg flail aur lal flash teeno aate hain.
				int prev = lastHurt.getOrDefault(ef.id(), 0);
				if (ef.hurt() > 0 && ef.hurt() > prev) {
					le.method_5711((byte) 2);
				}
				lastHurt.put(ef.id(), ef.hurt());
			}
		}

		Iterator<Map.Entry<Integer, class_1297>> it = ghosts.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<Integer, class_1297> en = it.next();
			if (!present.contains(en.getKey())) {
				en.getValue().method_31472();
				lastHurt.remove(en.getKey());
				it.remove();
			}
		}
	}

	private static class_1297 spawnGhost(class_638 level, EntityFrame ef) {
		if (badTypes.contains(ef.type())) return null;
		try {
			Optional<class_1299<?>> type = class_1299.method_5898(ef.type());
			if (type.isEmpty()) {
				badTypes.add(ef.type());
				return null;
			}
			class_1297 g = type.get().method_5883(level, class_3730.field_16462);
			if (g == null) {
				badTypes.add(ef.type());
				return null;
			}
			g.method_5875(true);
			g.method_5803(true);
			place(g, ef);
			level.method_53875(g);
			return g;
		} catch (Exception ex) {
			badTypes.add(ef.type());
			return null;
		}
	}

	private static void place(class_1297 g, EntityFrame ef) {
		g.method_5814(ef.x(), ef.y(), ef.z());
		g.method_36456(ef.yRot());
		g.method_36457(ef.xRot());
		g.method_5847(ef.head());
		g.method_18799(class_243.field_1353);
		g.method_5660(ef.sneak());
		g.method_5728(ef.sprint());

		if (g instanceof class_1309 le) {
			le.method_5636(ef.yRot());

			class_1792 item = class_7923.field_41178.method_10200(ef.item());
			if (item != null && !le.method_6047().method_31574(item)) {
				le.method_6122(class_1268.field_5808, new class_1799(item));
			}
		}
	}

	private static void clearGhosts() {
		for (class_1297 g : ghosts.values()) {
			g.method_31472();
		}
		ghosts.clear();
		lastHurt.clear();
	}

	private void registerCommands() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
			dispatcher.register(ClientCommandManager.literal("rw")
				.then(ClientCommandManager.literal("start").executes(ctx -> {
					clearGhosts();
					frames = new ArrayList<>();
					playing = false;
					recording = true;
					ctx.getSource().sendFeedback(class_2561.method_43470("Recording shuru!"));
					return 1;
				}))
				.then(ClientCommandManager.literal("cancel").executes(ctx -> {
					recording = false;
					playing = false;
					clearGhosts();
					ctx.getSource().sendFeedback(class_2561.method_43470("Roka gaya."));
					return 1;
				}))
				.then(ClientCommandManager.literal("stop")
					.then(ClientCommandManager.argument("name", StringArgumentType.word()).executes(ctx -> {
						String name = StringArgumentType.getString(ctx, "name");
						recording = false;
						try {
							save(name);
							ctx.getSource().sendFeedback(class_2561.method_43470(
								"Save ho gaya: " + name + " (" + frames.size() + " ticks)"));
						} catch (IOException e) {
							ctx.getSource().sendFeedback(class_2561.method_43470("Save error: " + e.getMessage()));
						}
						return 1;
					})))
				.then(ClientCommandManager.literal("play")
					.then(ClientCommandManager.argument("name", StringArgumentType.word()).executes(ctx -> {
						String name = StringArgumentType.getString(ctx, "name");
						try {
							load(name);
							recording = false;
							clearGhosts();
							playIndex = 0;
							playing = true;
							ctx.getSource().sendFeedback(class_2561.method_43470("Replay chal raha hai: " + name));
						} catch (IOException e) {
							ctx.getSource().sendFeedback(class_2561.method_43470("Load error: " + e.getMessage()));
						}
						return 1;
					})))
			)
		);
	}

	private static void say(class_746 p, String msg) {
		p.method_7353(class_2561.method_43470(msg), false);
	}

	private static Path dir() throws IOException {
		Path d = FabricLoader.getInstance().getGameDir().resolve("replays");
		Files.createDirectories(d);
		return d;
	}

	private static void save(String name) throws IOException {
		try (DataOutputStream out = new DataOutputStream(
				new BufferedOutputStream(Files.newOutputStream(dir().resolve(name + ".rw"))))) {
			out.writeInt(MAGIC);
			out.writeInt(frames.size());
			for (Frame f : frames) {
				out.writeDouble(f.x());
				out.writeDouble(f.y());
				out.writeDouble(f.z());
				out.writeFloat(f.yRot());
				out.writeFloat(f.xRot());
				out.writeBoolean(f.swing());
				out.writeBoolean(f.sneak());
				out.writeBoolean(f.sprint());
				out.writeInt(f.ents().size());
				for (EntityFrame e : f.ents()) {
					out.writeInt(e.id());
					out.writeUTF(e.type());
					out.writeDouble(e.x());
					out.writeDouble(e.y());
					out.writeDouble(e.z());
					out.writeFloat(e.yRot());
					out.writeFloat(e.xRot());
					out.writeFloat(e.head());
					out.writeBoolean(e.swing());
					out.writeInt(e.hurt());
					out.writeInt(e.item());
					out.writeBoolean(e.sneak());
					out.writeBoolean(e.sprint());
				}
			}
		}
	}

	private static void load(String name) throws IOException {
		try (DataInputStream in = new DataInputStream(
				new BufferedInputStream(Files.newInputStream(dir().resolve(name + ".rw"))))) {
			if (in.readInt() != MAGIC) {
				throw new IOException("Ye purani file hai, nayi record karo");
			}
			int n = in.readInt();
			List<Frame> loaded = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				double x = in.readDouble();
				double y = in.readDouble();
				double z = in.readDouble();
				float yRot = in.readFloat();
				float xRot = in.readFloat();
				boolean swing = in.readBoolean();
				boolean sneak = in.readBoolean();
				boolean sprint = in.readBoolean();
				int count = in.readInt();
				List<EntityFrame> ents = new ArrayList<>();
				for (int k = 0; k < count; k++) {
					int id = in.readInt();
					String type = in.readUTF();
					double ex = in.readDouble();
					double ey = in.readDouble();
					double ez = in.readDouble();
					float eyRot = in.readFloat();
					float exRot = in.readFloat();
					float head = in.readFloat();
					boolean eSwing = in.readBoolean();
					int hurt = in.readInt();
					int item = in.readInt();
					boolean eSneak = in.readBoolean();
					boolean eSprint = in.readBoolean();
					ents.add(new EntityFrame(id, type, ex, ey, ez, eyRot, exRot, head,
						eSwing, hurt, item, eSneak, eSprint));
				}
				loaded.add(new Frame(x, y, z, yRot, xRot, swing, sneak, sprint, ents));
			}
			frames = loaded;
		}
	}
							  }
