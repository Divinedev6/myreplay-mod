package com.example.client;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
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

	private static final Map<Integer, Entity> ghosts = new HashMap<>();
	private static final Map<Integer, Integer> lastHurt = new HashMap<>();
	private static final Set<String> badTypes = new HashSet<>();

	@Override
	public void onInitializeClient() {
		registerCommands();

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			LocalPlayer p = client.player;
			ClientLevel level = client.level;

			if (p == null || level == null) {
				recording = false;
				playing = false;
				ghosts.clear();
				lastHurt.clear();
				return;
			}

			if (recording) {
				List<EntityFrame> list = new ArrayList<>();
				for (Entity e : level.entitiesForRendering()) {
					if (e instanceof Player) continue;
					if (e.distanceToSqr(p) > 48.0 * 48.0) continue;

					boolean swing = false;
					int hurt = 0;
					int item = 0;
					if (e instanceof LivingEntity le) {
						swing = le.swinging && le.swingTime == 0;
						hurt = le.hurtTime;
						item = BuiltInRegistries.ITEM.getId(le.getMainHandItem().getItem());
					}

					list.add(new EntityFrame(
						e.getId(),
						EntityType.getKey(e.getType()).toString(),
						e.getX(), e.getY(), e.getZ(),
						e.getYRot(), e.getXRot(), e.getYHeadRot(),
						swing, hurt, item,
						e.isShiftKeyDown(), e.isSprinting()));
				}
				boolean pSwing = p.swinging && p.swingTime == 0;
				frames.add(new Frame(
					p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot(),
					pSwing, p.isShiftKeyDown(), p.isSprinting(), list));
			}

			if (playing) {
				if (playIndex >= frames.size()) {
					playing = false;
					clearGhosts();
					say(p, "Replay khatam.");
					return;
				}
				Frame f = frames.get(playIndex++);
				p.setPos(f.x(), f.y(), f.z());
				p.setYRot(f.yRot());
				p.setXRot(f.xRot());
				p.setDeltaMovement(Vec3.ZERO);
				p.setShiftKeyDown(f.sneak());
				p.setSprinting(f.sprint());
				if (f.swing()) {
					p.swing(InteractionHand.MAIN_HAND);
				}
				updateGhosts(level, f);
			}
		});
	}

	private static void updateGhosts(ClientLevel level, Frame f) {
		Set<Integer> present = new HashSet<>();

		for (EntityFrame ef : f.ents()) {
			present.add(ef.id());
			Entity g = ghosts.get(ef.id());
			if (g == null) {
				g = spawnGhost(level, ef);
				if (g == null) continue;
				ghosts.put(ef.id(), g);
			}
			place(g, ef);
			if (g instanceof LivingEntity le) {
				if (ef.swing()) {
					le.swing(InteractionHand.MAIN_HAND);
				}
				// hurt: rising edge par vanilla "hurt" event (2) chalao.
				// Isse hurt sound, leg flail aur lal flash teeno aate hain.
				int prev = lastHurt.getOrDefault(ef.id(), 0);
				if (ef.hurt() > 0 && ef.hurt() > prev) {
					le.handleEntityEvent((byte) 2);
				}
				lastHurt.put(ef.id(), ef.hurt());
			}
		}

		Iterator<Map.Entry<Integer, Entity>> it = ghosts.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<Integer, Entity> en = it.next();
			if (!present.contains(en.getKey())) {
				en.getValue().discard();
				lastHurt.remove(en.getKey());
				it.remove();
			}
		}
	}

	private static Entity spawnGhost(ClientLevel level, EntityFrame ef) {
		if (badTypes.contains(ef.type())) return null;
		try {
			Optional<EntityType<?>> type = EntityType.byString(ef.type());
			if (type.isEmpty()) {
				badTypes.add(ef.type());
				return null;
			}
			Entity g = type.get().create(level, EntitySpawnReason.COMMAND);
			if (g == null) {
				badTypes.add(ef.type());
				return null;
			}
			g.setNoGravity(true);
			g.setSilent(true);
			place(g, ef);
			level.addEntity(g);
			return g;
		} catch (Exception ex) {
			badTypes.add(ef.type());
			return null;
		}
	}

	private static void place(Entity g, EntityFrame ef) {
		g.setPos(ef.x(), ef.y(), ef.z());
		g.setYRot(ef.yRot());
		g.setXRot(ef.xRot());
		g.setYHeadRot(ef.head());
		g.setDeltaMovement(Vec3.ZERO);
		g.setShiftKeyDown(ef.sneak());
		g.setSprinting(ef.sprint());

		if (g instanceof LivingEntity le) {
			le.setYBodyRot(ef.yRot());

			Item item = BuiltInRegistries.ITEM.byId(ef.item());
			if (item != null && !le.getMainHandItem().is(item)) {
				le.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item));
			}
		}
	}

	private static void clearGhosts() {
		for (Entity g : ghosts.values()) {
			g.discard();
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
					ctx.getSource().sendFeedback(Component.literal("Recording shuru!"));
					return 1;
				}))
				.then(ClientCommandManager.literal("cancel").executes(ctx -> {
					recording = false;
					playing = false;
					clearGhosts();
					ctx.getSource().sendFeedback(Component.literal("Roka gaya."));
					return 1;
				}))
				.then(ClientCommandManager.literal("stop")
					.then(ClientCommandManager.argument("name", StringArgumentType.word()).executes(ctx -> {
						String name = StringArgumentType.getString(ctx, "name");
						recording = false;
						try {
							save(name);
							ctx.getSource().sendFeedback(Component.literal(
								"Save ho gaya: " + name + " (" + frames.size() + " ticks)"));
						} catch (IOException e) {
							ctx.getSource().sendFeedback(Component.literal("Save error: " + e.getMessage()));
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
							ctx.getSource().sendFeedback(Component.literal("Replay chal raha hai: " + name));
						} catch (IOException e) {
							ctx.getSource().sendFeedback(Component.literal("Load error: " + e.getMessage()));
						}
						return 1;
					})))
			)
		);
	}

	private static void say(LocalPlayer p, String msg) {
		p.displayClientMessage(Component.literal(msg), false);
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
