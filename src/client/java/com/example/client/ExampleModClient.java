package com.example.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.netty.buffer.Unpooled;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

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
	private static final int MAGIC = 0x52570007; // file format v7 (unchanged)

	// All equipment slots (head, chest, legs, feet, offhand, body armor, saddle...)
	private static final EquipmentSlot[] SLOTS = EquipmentSlot.values();

	// data = entity's synced data (colour, variant, baby, etc.), only present every 20 ticks
	// equip = item id for each equipment slot, in the order of SLOTS
	private record EntityFrame(int id, String type, double x, double y, double z,
							   float yRot, float xRot, float head,
							   boolean swing, int hurt, int item,
							   boolean sneak, boolean sprint,
							   int[] equip,
							   byte[] data) {}

	// pose = ordinal of the player's Pose (standing, crouching, swimming, elytra...)
	private record Frame(double x, double y, double z, float yRot, float xRot,
						 boolean swing, boolean sneak, boolean sprint,
						 int hurt, int item,
						 int pose, boolean swimming, boolean fallFlying,
						 int[] equip,
						 List<EntityFrame> ents) {}

	private static List<Frame> frames = new ArrayList<>();
	private static boolean recording = false;
	private static boolean playing = false;
	private static int playIndex = 0;
	private static long captureTick = 0;

	// Key to start/stop recording (default: G)
	private static KeyMapping toggleKey;

	// Shadow Rewind
	private static final ArrayDeque<Frame> shadow = new ArrayDeque<>();
	private static boolean shadowOn = false;
	private static int shadowTicks = 20 * 120; // default: 2 minutes

	// State saved when a replay starts, restored when it ends
	private static ItemStack savedHand = ItemStack.EMPTY;
	private static ItemStack[] savedEquip = new ItemStack[SLOTS.length];
	private static boolean handSaved = false;
	private static double savedX, savedY, savedZ;
	private static float savedYaw, savedPitch;

	// Replay body/head rotation (body turns slowly toward the walking direction)
	private static float bodyYaw = 0;
	private static float headYaw = 0;

	// Replay helpers for the mixins
	private static boolean animBypass = false;
	private static boolean replayCrouch = false;

	private static final Map<Integer, Entity> ghosts = new HashMap<>();
	// Game ids of the ghost entities, so the render mixin can tell ghosts from real entities
	private static final Set<Integer> ghostEntityIds = new HashSet<>();
	private static final Set<String> badTypes = new HashSet<>();

	// Used by the mixin to stop sending position packets during replay
	public static boolean isPlaying() {
		return playing;
	}

	// Used by the render mixin: during replay hide every real entity
	// except our own player and the recorded ghosts
	public static boolean shouldHide(Entity e) {
		if (!playing) return false;
		if (e == Minecraft.getInstance().player) return false;
		return !ghostEntityIds.contains(e.getId());
	}

	// Used by the animation mixin: during replay the mod updates the player's
	// walk animation itself, so the game's own update is skipped
	public static boolean shouldBlockVanillaAnimation(LivingEntity e) {
		return playing && !animBypass && e == Minecraft.getInstance().player;
	}

	// Used by the crouch mixin: is the replayed player crouching right now
	public static boolean replayCrouching() {
		return replayCrouch;
	}

	// Used by the name screen
	public static void saveFrames(String name) throws IOException {
		save(name, frames);
	}

	public static int frameCount() {
		return frames.size();
	}

	public static void discardRecording() {
		frames = new ArrayList<>();
	}

	@Override
	public void onInitializeClient() {
		registerCommands();

		KeyMapping.Category category = KeyMapping.Category.register(
			Identifier.fromNamespaceAndPath("modid", "empyrean"));
		toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
			"key.modid.toggle_recording",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_G,
			category));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			LocalPlayer p = client.player;
			ClientLevel level = client.level;

			if (p == null || level == null) {
				// Left the world
				recording = false;
				playing = false;
				replayCrouch = false;
				ghosts.clear();
				ghostEntityIds.clear();
				shadow.clear();
				handSaved = false;
				savedHand = ItemStack.EMPTY;
				return;
			}

			// Replay stopped: restore position, held item and armor
			if (!playing && handSaved) {
				restorePlayerState(p);
			}

			// G key: start recording / stop recording and ask for a name
			while (toggleKey.consumeClick()) {
				if (playing) {
					say(p, "Can't record during a replay.");
				} else if (!recording) {
					clearGhosts();
					frames = new ArrayList<>();
					recording = true;
					say(p, "Recording started! Press G again to stop.");
				} else {
					recording = false;
					client.setScreen(new RecNameScreen());
				}
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
					for (int i = 0; i < SLOTS.length; i++) {
						savedEquip[i] = p.getItemBySlot(SLOTS[i]).copy();
					}
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

				// Remember the old position so the game can see how far we moved
				double oldX = p.getX();
				double oldY = p.getY();
				double oldZ = p.getZ();

				p.setPos(f.x(), f.y(), f.z());
				p.xo = oldX;
				p.yo = oldY;
				p.zo = oldZ;
				p.setYRot(f.yRot());
				p.setXRot(f.xRot());

				// Body turns toward the walking direction, head follows the camera
				// (like the real game, so the body doesn't snap with the camera)
				float yaw = f.yRot();
				if (idx == 0) {
					bodyYaw = yaw;
					headYaw = yaw;
				}
				float prevBody = bodyYaw;
				float prevHead = headYaw;

				double dx = f.x() - oldX;
				double dz = f.z() - oldZ;
				float target = bodyYaw;
				if (dx * dx + dz * dz > 0.0025) {
					float moveYaw = (float) (Math.atan2(dz, dx) * 57.29577951308232) - 90.0F;
					float diff = Math.abs(Mth.wrapDegrees(yaw) - moveYaw);
					if (diff > 95.0F && diff < 265.0F) {
						moveYaw -= 180.0F;
					}
					target = moveYaw;
				}
				bodyYaw += Mth.wrapDegrees(target - bodyYaw) * 0.3F;

				// The head can't turn more than 75 degrees away from the body
				float headDiff = Mth.wrapDegrees(yaw - bodyYaw);
				if (headDiff > 75.0F) {
					bodyYaw = yaw - 75.0F;
				} else if (headDiff < -75.0F) {
					bodyYaw = yaw + 75.0F;
				}
				headYaw = yaw;

				p.yBodyRotO = prevBody;
				p.yBodyRot = bodyYaw;
				p.yHeadRotO = prevHead;
				p.yHeadRot = headYaw;

				p.setDeltaMovement(Vec3.ZERO);
				p.fallDistance = 0;
				p.setShiftKeyDown(f.sneak());
				p.setSprinting(f.sprint());

				// Pose: standing, crouching, swimming, elytra flight
				Pose pose = poseFor(f.pose());
				p.setPose(pose);
				replayCrouch = (pose == Pose.CROUCHING);
				p.setSwimming(f.swimming());
				if (f.fallFlying() && !p.isFallFlying()) {
					p.startFallFlying();
				} else if (!f.fallFlying() && p.isFallFlying()) {
					p.stopFallFlying();
				}

				// Armor and offhand (an elytra needs the chest slot item)
				applyPlayerEquipment(p, f.equip());

				// Walking/running/swimming/flying limb animation (visible in third person)
				animBypass = true;
				p.calculateEntityAnimation(f.swimming() || f.fallFlying());
				animBypass = false;

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

	// Only these poses are replayed on the player; others fall back to standing
	private static Pose poseFor(int ordinal) {
		Pose[] all = Pose.values();
		if (ordinal < 0 || ordinal >= all.length) return Pose.STANDING;
		Pose pose = all[ordinal];
		if (pose == Pose.FALL_FLYING || pose == Pose.SWIMMING || pose == Pose.CROUCHING) {
			return pose;
		}
		return Pose.STANDING;
	}

	private static boolean isPlayerWornSlot(EquipmentSlot s) {
		return s == EquipmentSlot.HEAD || s == EquipmentSlot.CHEST
			|| s == EquipmentSlot.LEGS || s == EquipmentSlot.FEET
			|| s == EquipmentSlot.OFFHAND;
	}

	// Temporarily wear the recorded armor/offhand (client side only)
	private static void applyPlayerEquipment(LocalPlayer p, int[] equip) {
		for (int i = 0; i < SLOTS.length && i < equip.length; i++) {
			EquipmentSlot s = SLOTS[i];
			if (!isPlayerWornSlot(s)) continue;
			Item it = BuiltInRegistries.ITEM.byId(equip[i]);
			if (it != null && !p.getItemBySlot(s).is(it)) {
				try {
					p.setItemSlot(s, new ItemStack(it));
				} catch (Exception ex) {
					// ignore
				}
			}
		}
	}

	private static Frame capture(LocalPlayer p, ClientLevel level) {
		captureTick++;
		boolean withData = (captureTick % 20 == 0);

		// Record everything inside the video-settings render distance (in chunks)
		int rd = Minecraft.getInstance().options.renderDistance().get();
		int pcx = p.getBlockX() >> 4;
		int pcz = p.getBlockZ() >> 4;

		List<EntityFrame> list = new ArrayList<>();
		for (Entity e : level.entitiesForRendering()) {
			if (e instanceof Player) continue;
			if (Math.abs((e.getBlockX() >> 4) - pcx) > rd
					|| Math.abs((e.getBlockZ() >> 4) - pcz) > rd) continue;

			boolean swing = false;
			int hurt = 0;
			int item = 0;
			int[] equip = new int[SLOTS.length];
			if (e instanceof LivingEntity le) {
				swing = le.swinging && le.swingTime == 0;
				hurt = le.hurtTime;
				item = BuiltInRegistries.ITEM.getId(le.getMainHandItem().getItem());
				for (int i = 0; i < SLOTS.length; i++) {
					equip[i] = BuiltInRegistries.ITEM.getId(le.getItemBySlot(SLOTS[i]).getItem());
				}
			}

			byte[] data = withData ? packData(e, level) : new byte[0];

			list.add(new EntityFrame(
				e.getId(),
				EntityType.getKey(e.getType()).toString(),
				e.getX(), e.getY(), e.getZ(),
				e.getYRot(), e.getXRot(), e.getYHeadRot(),
				swing, hurt, item,
				e.isShiftKeyDown(), e.isSprinting(),
				equip,
				data));
		}

		boolean pSwing = p.swinging && p.swingTime == 0;
		int pItem = BuiltInRegistries.ITEM.getId(p.getMainHandItem().getItem());

		int[] pEquip = new int[SLOTS.length];
		for (int i = 0; i < SLOTS.length; i++) {
			pEquip[i] = BuiltInRegistries.ITEM.getId(p.getItemBySlot(SLOTS[i]).getItem());
		}

		return new Frame(
			p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot(),
			pSwing, p.isShiftKeyDown(), p.isSprinting(),
			p.hurtTime, pItem,
			p.getPose().ordinal(), p.isSwimming(), p.isFallFlying(),
			pEquip,
			list);
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
			for (int i = 0; i < SLOTS.length; i++) {
				if (!isPlayerWornSlot(SLOTS[i]) || savedEquip[i] == null) continue;
				try {
					p.setItemSlot(SLOTS[i], savedEquip[i]);
				} catch (Exception ex) {
					// ignore
				}
			}
			p.setPos(savedX, savedY, savedZ);
			p.setYRot(savedYaw);
			p.setXRot(savedPitch);
			p.setDeltaMovement(Vec3.ZERO);
			p.fallDistance = 0;
			p.hurtTime = 0;
			p.setPose(Pose.STANDING);
			p.setSwimming(false);
			if (p.isFallFlying()) {
				p.stopFallFlying();
			}
			replayCrouch = false;
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
				ghostEntityIds.add(g.getId());
				byte[] data = ef.data().length > 0 ? ef.data() : lookAhead(idx, ef.id());
				applyData(g, data, level);
			} else if (ef.data().length > 0) {
				applyData(g, ef.data(), level);
			}
			place(g, ef, true);
			if (ef.swing() && g instanceof LivingEntity le) {
				le.swing(InteractionHand.MAIN_HAND);
			}
		}

		Iterator<Map.Entry<Integer, Entity>> it = ghosts.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<Integer, Entity> en = it.next();
			if (!present.contains(en.getKey())) {
				ghostEntityIds.remove(en.getValue().getId());
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
			place(g, ef, false);
			level.addEntity(g);
			return g;
		} catch (Exception ex) {
			badTypes.add(ef.type());
			return null;
		}
	}

	// animate = true: also update the walk/run leg animation from the movement since last tick
	private static void place(Entity g, EntityFrame ef, boolean animate) {
		// Remember the old position so the game can see how far the entity moved
		double px = g.getX();
		double py = g.getY();
		double pz = g.getZ();

		g.setPos(ef.x(), ef.y(), ef.z());
		g.xo = px;
		g.yo = py;
		g.zo = pz;
		g.setYRot(ef.yRot());
		g.setXRot(ef.xRot());
		g.setYHeadRot(ef.head());
		g.setDeltaMovement(Vec3.ZERO);
		g.setShiftKeyDown(ef.sneak());
		g.setSprinting(ef.sprint());

		if (g instanceof LivingEntity le) {
			le.setYBodyRot(ef.yRot());
			le.hurtTime = ef.hurt();

			if (animate) {
				le.calculateEntityAnimation(false);
			}

			Item item = BuiltInRegistries.ITEM.byId(ef.item());
			if (item != null && !le.getMainHandItem().is(item)) {
				le.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item));
			}

			// Armor, offhand, saddle, horse armor, etc.
			for (int i = 0; i < SLOTS.length && i < ef.equip().length; i++) {
				if (SLOTS[i] == EquipmentSlot.MAINHAND) continue;
				Item eq = BuiltInRegistries.ITEM.byId(ef.equip()[i]);
				if (eq != null && !le.getItemBySlot(SLOTS[i]).is(eq)) {
					try {
						le.setItemSlot(SLOTS[i], new ItemStack(eq));
					} catch (Exception ex) {
						// this entity can't wear that, ignore
					}
				}
			}
		}
	}

	private static void clearGhosts() {
		for (Entity g : ghosts.values()) {
			ghostEntityIds.remove(g.getId());
			g.discard();
		}
		ghosts.clear();
		ghostEntityIds.clear();
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
				out.writeInt(f.pose());
				out.writeBoolean(f.swimming());
				out.writeBoolean(f.fallFlying());
				out.writeInt(f.equip().length);
				for (int v : f.equip()) {
					out.writeInt(v);
				}
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
					out.writeInt(e.equip().length);
					for (int v : e.equip()) {
						out.writeInt(v);
					}
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
				int pose = in.readInt();
				boolean swimming = in.readBoolean();
				boolean fallFlying = in.readBoolean();
				int pEqLen = in.readInt();
				if (pEqLen < 0 || pEqLen > 32) {
					throw new IOException("Corrupt replay file");
				}
				int[] pEquip = new int[pEqLen];
				for (int j = 0; j < pEqLen; j++) {
					pEquip[j] = in.readInt();
				}
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
					int eqLen = in.readInt();
					if (eqLen < 0 || eqLen > 32) {
						throw new IOException("Corrupt replay file");
					}
					int[] equip = new int[eqLen];
					for (int j = 0; j < eqLen; j++) {
						equip[j] = in.readInt();
					}
					int len = in.readInt();
					if (len < 0 || len > 100000) {
						throw new IOException("Corrupt replay file");
					}
					byte[] data = new byte[len];
					in.readFully(data);
					ents.add(new EntityFrame(id, type, ex, ey, ez, eyRot, exRot, head,
						eSwing, eHurt, eItem, eSneak, eSprint, equip, data));
				}
				loaded.add(new Frame(x, y, z, yRot, xRot, swing, sneak, sprint, hurt, item,
					pose, swimming, fallFlying, pEquip, ents));
			}
			frames = loaded;
		}
	}
					}
