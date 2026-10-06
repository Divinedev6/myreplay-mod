package com.example.client;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.netty.buffer.Unpooled;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.SynchedEntityData;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class ExampleModClient implements ClientModInitializer {
	private static final int MAGIC = 0x52570005; // file format v5

	// data = entity's synced data (colour, variant, baby, etc.), only present every 20 ticks
	private record EntityFrame(int id, String type, double x, double y, double z,
							   float yRot, float xRot, float head,
							   boolean swing, int hurt, int item,
							   boolean sneak, boolean sprint,
							   byte[] data) {}

	private record Frame(double x, double y, double z, float yRot, float xRot,
						 boolean swing, boolean sneak, boolean sprint,
						 int hurt, int item,
						 List<EntityFrame> ents) {}

	private static List<Frame> frames = new ArrayList<>();
	private static boolean recording = false;
	private static boolean playing = false;
	private static int playIndex = 0;
	private static long captureTick = 0;

	// Shadow Rewind
	private static final ArrayDeque<Frame> shadow = new ArrayDeque<>();
	private static boolean shadowOn = false;
	private static int shadowTicks = 20 * 120; // default: 2 minutes

	// State saved when a replay starts, restored when it ends
	private static ItemStack savedHand = ItemStack.EMPTY;
	private static boolean handSaved = false;
	private static double savedX, savedY, savedZ;
	private static float savedYaw, savedPitch;

	private static final Map<Integer, Entity> ghosts = new HashMap<>();
	private static final Set<String> badTypes = new HashSet<>();

	// Used by the mixin to stop sending position packets during replay
	public static boolean isPlaying() {
		return playing;
	}

	@Override
	public void onInitializeClient() {
		registerCommands();

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			LocalPlayer p = client.player;
			ClientLevel level = client.level;

			if (p == null || level == null) {
				// Left the world
				recording = false;
				playing = false;
				ghosts.clear();
				shadow.clear();
				handSaved = false;
				savedHand = ItemStack.EMPTY;
				return;
			}

			// Replay stopped: restore position and held item
			if (!playing && handSaved) {
				restorePlayerState(p);
			}

			// Recording and Shadow Rewind (no capture during replay)
			if (!playing && (recording || shadowOn)) {
				Frame current = capture(p, level);
				if (recording) {
					frames.add(current);
				}
				if (shadowOn) {
					shadow.addLast(current);
					while (shadow.size() > shadowTicks) {
						shadow.pollFirst();
					}
				}
			}

			if (playing) {
				if (!handSaved) {
					savedHand = p.getMainHandItem().copy();
					savedX = p.getX();
					savedY = p.getY();
					savedZ = p.getZ();
					savedYaw = p.getYRot();
					savedPitch = p.getXRot();
					handSaved = true;
				}
				if (playIndex >= frames.size()) {
					playing = false;
					clearGhosts();
					restorePlayerState(p);
					say(p, "Replay finished.");
					return;
				}
				int idx = playIndex;
				Frame f = frames.get(playIndex++);
				p.setPos(f.x(), f.y(), f.z());
				p.setYRot(f.yRot());
				p.setXRot(f.xRot());
				p.setDeltaMovement(Vec3.ZERO);
				p.fallDistance = 0;
				p.setShiftKeyDown(f.sneak());
				p.setSprinting(f.sprint());
				if (f.swing()) {
					p.swing(InteractionHand.MAIN_HAND);
				}

				// POV hurt (screen shake)
				p.hurtTime = f.hurt();
				if (f.hurt() > 0) {
					p.hurtDuration = 10;
				}

				// POV held item
				Item item = BuiltInRegistries.ITEM.byId(f.item());
				if (item != null && !p.getMainHandItem().is(item)) {
					p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item));
				}

				updateGhosts(level, f, idx);
			}
		});
	}

	private static Frame capture(LocalPlayer p, ClientLevel level) {
		captureTick++;
		boolean withData = (captureTick % 20 == 0);

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

			byte[] data = withData ? packData(e, level) : new byte[0];

			list.add(new EntityFrame(
				e.getId(),
				EntityType.getKey(e.getType()).toString(),
				e.getX(), e.getY(), e.getZ(),
				e.getYRot(), e.getXRot(), e.getYHeadRot(),
				swing, hurt, item,
				e.isShiftKeyDown(), e.isSprinting(),
				data));
		}

		boolean pSwing = p.swinging && p.swingTime == 0;
		int pItem = BuiltInRegistries.ITEM.getId(p.getMainHandItem().getItem());
		return new Frame(
			p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot(),
			pSwing, p.isShiftKeyDown(), p.isSprinting(),
			p.hurtTime, pItem, list);
	}

	// Turn the entity's synced data into bytes
	private static byte[] packData(Entity e, ClientLevel level) {
		try {
			List<SynchedEntityData.DataValue<?>> values = e.getEntityData().getNonDefaultValues();
			if (values == null || values.isEmpty()) return new byte[0];
			RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
			ClientboundSetEntityDataPacket.STREAM_CODEC.encode(buf, new ClientboundSetEntityDataPacket(e.getId(), values));
			byte[] out = new byte[buf.readableBytes()];
			buf.readBytes(out);
			return out;
		} catch (Exception ex) {
			return new byte[0];
		}
	}

	// Put saved synced data onto a ghost entity
	private static void applyData(Entity g, byte[] data, ClientLevel level) {
		if (data == null || data.length == 0) return;
		try {
			RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(data), level.registryAccess());
			ClientboundSetEntityDataPacket pkt = ClientboundSetEntityDataPacket.STREAM_CODEC.decode(buf);
			g.getEntityData().assignValues(pkt.packedItems());
			g.setNoGravity(true);
			g.setSilent(true);
		} catch (Exception ex) {
			// ignore, ghost just keeps default look
		}
	}

	// Find the next saved data for this entity in the coming frames
	private static byte[] lookAhead(int from, int id) {
		int end = Math.min(frames.size(), from + 25);
		for (int i = from; i < end; i++) {
			for (EntityFrame ef : frames.get(i).ents()) {
				if (ef.id() == id && ef.data().length > 0) {
					return ef.data();
				}
			}
		}
		return new byte[0];
	}

	private static void restorePlayerState(LocalPlayer p) {
		if (handSaved) {
			p.setItemInHand(InteractionHand.MAIN_HAND, savedHand);
			p.setPos(savedX, savedY, savedZ);
			p.setYRot(savedYaw);
			p.setXRot(savedPitch);
			p.setDeltaMovement(Vec3.ZERO);
			p.fallDistance = 0;
			p.hurtTime = 0;
			handSaved = false;
			savedHand = ItemStack.EMPTY;
		}
	}

	private static void updateGhosts(ClientLevel level, Frame f, int idx) {
		Set<Integer> present = new HashSet<>();

		for (EntityFrame ef : f.ents()) {
			present.add(ef.id());
			Entity g = ghosts.get(ef.id());
			if (g == null) {
				g = spawnGhost(level, ef);
				if (g == null) continue;
				ghosts.put(ef.id(), g);
				byte[] data = ef.data().length > 0 ? ef.data() : lookAhead(idx, ef.id());
				applyData(g, data, level);
			} else if (ef.data().length > 0) {
				applyData(g, ef.data(), level);
			}
			place(g, ef);
			if (ef.swing() && g instanceof LivingEntity le) {
				le.swing(InteractionHand.MAIN_HAND);
			}
		}

		Iterator<Map.Entry<Integer, Entity>> it = ghosts.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<Integer, Entity> en = it.next();
			if (!present.contains(en.getKey())) {
				en.getValue().discard();
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
			le.hurtTime = ef.hurt();

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
	}

	private void registerCommands() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
			dispatcher.register(ClientCommandManager.literal("rw")
				.then(ClientCommandManager.literal("start").executes(ctx -> {
					clearGhosts();
					frames = new ArrayList<>();
					playing = false;
					recording = true;
					ctx.getSource().sendFeedback(Component.literal("Recording started!"));
					return 1;
				}))
				.then(ClientCommandManager.literal("cancel").executes(ctx -> {
					recording = false;
					playing = false;
					clearGhosts();
					ctx.getSource().sendFeedback(Component.literal("Cancelled."));
					return 1;
				}))
				.then(ClientCommandManager.literal("stop")
					.then(ClientCommandManager.argument("name", StringArgumentType.word()).executes(ctx -> {
						String name = StringArgumentType.getString(ctx, "name");
						recording = false;
						try {
							save(name, frames);
							ctx.getSource().sendFeedback(Component.literal(
								"Saved: " + name + " (" + frames.size() + " ticks)"));
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
							ctx.getSource().sendFeedback(Component.literal("Playing replay: " + name));
						} catch (IOException e) {
							ctx.getSource().sendFeedback(Component.literal("Load error: " + e.getMessage()));
						}
						return 1;
					})))
				.then(ClientCommandManager.literal("save")
					.then(ClientCommandManager.argument("name", StringArgumentType.word()).executes(ctx -> {
						String name = StringArgumentType.getString(ctx, "name");
						if (shadow.isEmpty()) {
							ctx.getSource().sendFeedback(Component.literal(
								"Shadow buffer is empty. Run /rw shadow on first."));
							return 1;
						}
						List<Frame> copy = new ArrayList<>(shadow);
						try {
							save(name, copy);
							ctx.getSource().sendFeedback(Component.literal(
								"Shadow saved: " + name + " (" + (copy.size() / 20) + " seconds)"));
						} catch (IOException e) {
							ctx.getSource().sendFeedback(Component.literal("Save error: " + e.getMessage()));
						}
						return 1;
					})))
				.then(ClientCommandManager.literal("shadow")
					.then(ClientCommandManager.literal("on").executes(ctx -> {
						shadowOn = true;
						ctx.getSource().sendFeedback(Component.literal(
							"Shadow Rewind enabled (" + (shadowTicks / 20) + " seconds)"));
						return 1;
					}))
					.then(ClientCommandManager.literal("off").executes(ctx -> {
						shadowOn = false;
						shadow.clear();
						ctx.getSource().sendFeedback(Component.literal("Shadow Rewind disabled."));
						return 1;
					}))
					.then(ClientCommandManager.literal("time")
						.then(ClientCommandManager.argument("seconds", IntegerArgumentType.integer(30, 600)).executes(ctx -> {
							int s = IntegerArgumentType.getInteger(ctx, "seconds");
							shadowTicks = s * 20;
							ctx.getSource().sendFeedback(Component.literal(
								"Shadow time: " + s + " seconds"));
							return 1;
						}))))
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

	private static void save(String name, List<Frame> data) throws IOException {
		try (DataOutputStream out = new DataOutputStream(
				new BufferedOutputStream(Files.newOutputStream(dir().resolve(name + ".rw"))))) {
			out.writeInt(MAGIC);
			out.writeInt(data.size());
			for (Frame f : data) {
				out.writeDouble(f.x());
				out.writeDouble(f.y());
				out.writeDouble(f.z());
				out.writeFloat(f.yRot());
				out.writeFloat(f.xRot());
				out.writeBoolean(f.swing());
				out.writeBoolean(f.sneak());
				out.writeBoolean(f.sprint());
				out.writeInt(f.hurt());
				out.writeInt(f.item());
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
					out.writeInt(e.data().length);
					out.write(e.data());
				}
			}
		}
	}

	private static void load(String name) throws IOException {
		try (DataInputStream in = new DataInputStream(
				new BufferedInputStream(Files.newInputStream(dir().resolve(name + ".rw"))))) {
			if (in.readInt() != MAGIC) {
				throw new IOException("This is an old file format, please record a new one");
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
				int hurt = in.readInt();
				int item = in.readInt();
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
					int eHurt = in.readInt();
					int eItem = in.readInt();
					boolean eSneak = in.readBoolean();
					boolean eSprint = in.readBoolean();
					int len = in.readInt();
					if (len < 0 || len > 100000) {
						throw new IOException("Corrupt replay file");
					}
					byte[] data = new byte[len];
					in.readFully(data);
					ents.add(new EntityFrame(id, type, ex, ey, ez, eyRot, exRot, head,
						eSwing, eHurt, eItem, eSneak, eSprint, data));
				}
				loaded.add(new Frame(x, y, z, yRot, xRot, swing, sneak, sprint, hurt, item, ents));
			}
			frames = loaded;
		}
	}
					}
