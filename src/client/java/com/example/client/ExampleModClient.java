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
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.status.ChunkStatus;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipException;

public class ExampleModClient implements ClientModInitializer {
	private static final int MAGIC = 0x52570009; // file format v9 (gzip + terrain + block changes)
	private static final int MAGIC_V8 = 0x52570008; // old format (no block changes), still readable

	// All equipment slots (head, chest, legs, feet, offhand, body armor, saddle...)
	private static final EquipmentSlot[] SLOTS = EquipmentSlot.values();

	// Terrain saving: how far up and down from the player we save blocks
	private static final int TERRAIN_RANGE = 32;
	// At most this many new chunks are saved per tick (so the game doesn't freeze)
	private static final int TERRAIN_PER_TICK = 4;

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

	// One saved chunk column: blocks from yStart for "layers" layers.
	// rle = pairs of (block state id, how many times in a row)
	private record TerrainChunk(int cx, int cz, int yStart, int layers, int[] rle) {}

	// One block change: it happens at the start of tick "tick", the block at x,y,z
	// becomes block state "state" (a block state id, 0 = air)
	private record BlockChange(int tick, int x, int y, int z, int state) {}

	private static final int MAX_BLOCK_EVENTS = 200000;
	private static List<BlockChange> blockEvents = new ArrayList<>(); // while recording
	private static boolean blockLimitWarned = false;
	private static List<BlockChange> loadedBlockEvents = new ArrayList<>(); // while replaying
	private static int blockEventPtr = 0;
	// Changes whose chunk isn't shown yet (the terrain is added a few chunks per tick)
	private static final List<BlockChange> deferredBlocks = new ArrayList<>();
	// What the replay changed in the real world: block position -> block before the change
	private static final Map<Long, BlockState> blockOriginals = new HashMap<>();

	private static List<Frame> frames = new ArrayList<>();
	private static boolean recording = false;
	private static boolean playing = false;
	private static int playIndex = 0;
	private static long captureTick = 0;

	// Terrain saved while recording, and terrain loaded from a replay file
	private static final Map<Long, TerrainChunk> terrain = new LinkedHashMap<>();
	private static Map<Long, TerrainChunk> loadedTerrain = new LinkedHashMap<>();

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

	// Position of the previous replay frame
	private static double prevFrameX, prevFrameY, prevFrameZ;

	// Step 2: saved terrain shown during replay
	private static boolean terrainApplied = false;
	private static boolean terrainRadiusRaised = false;
	private static int terrainAdded = 0;
	private static final List<TerrainChunk> terrainPending = new ArrayList<>();
	private static final List<Long> addedChunks = new ArrayList<>();
	// New chunks added per tick (so the game doesn't freeze) and the biggest chunk area
	private static final int TERRAIN_SHOW_PER_TICK = 6;
	private static final int TERRAIN_MAX_RADIUS = 64;
	// Light of the replay terrain, calculated in the background (null = not ready yet)
	private static volatile Map<Long, LightChunk> terrainLight = null;
	private static volatile int terrainLightGen = 0;
	// Chunks whose light is given to the game a second time a bit later (and their
	// sections redrawn). Each entry = {chunk key, tick when it is due}
	private static final List<long[]> lightRefresh = new ArrayList<>();
	private static int refreshTick = 0;
	private static final int REFRESH_DELAY_TICKS = 20;
	private static final int REFRESH_PER_TICK = 6;
	private static int refreshChunks = 0;
	private static int refreshMissing = 0;
	private static int refreshChanged = 0;

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

	// Used by the block mixin: the client world just changed a block
	public static void onBlockChanged(BlockPos pos, BlockState newState) {
		if (!recording || playing) return;
		if (blockEvents.size() >= MAX_BLOCK_EVENTS) {
			if (!blockLimitWarned) {
				blockLimitWarned = true;
				LocalPlayer p = Minecraft.getInstance().player;
				if (p != null) say(p, "Too many block changes, the rest are not saved.");
			}
			return;
		}
		blockEvents.add(new BlockChange(frames.size(), pos.getX(), pos.getY(), pos.getZ(),
			Block.getId(newState)));
	}

	// Used by the name screen
	public static void saveFrames(String name) throws IOException {
		save(name, frames, terrain, blockEvents);

		// Tell how much terrain was saved (so we can check the file size)
		LocalPlayer p = Minecraft.getInstance().player;
		if (p != null) {
			long kb = Files.size(dir().resolve(name + ".rw")) / 1024;
			say(p, "Terrain: " + terrain.size() + " chunks saved, " + blockEvents.size()
				+ " block changes, file size " + kb + " KB");
		}
	}

	public static int frameCount() {
		return frames.size();
	}

	public static void discardRecording() {
		frames = new ArrayList<>();
		terrain.clear();
		blockEvents = new ArrayList<>();
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
				terrainApplied = false;
				terrainRadiusRaised = false;
				terrainPending.clear();
				addedChunks.clear();
				lightRefresh.clear();
				deferredBlocks.clear();
				blockOriginals.clear();
				handSaved = false;
				savedHand = ItemStack.EMPTY;
				return;
			}

			// Replay stopped: take the replay terrain away again
			if (!playing && terrainApplied) {
				restoreBlocks(level);
				removeTerrain(level);
				terrainApplied = false;
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
					terrain.clear();
					blockEvents = new ArrayList<>();
					blockLimitWarned = false;
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
					captureTerrain(p, level);
				}
				if (shadowOn) {
					shadow.addLast(current);
					while (shadow.size() > shadowTicks) {
						shadow.pollFirst();
					}
				}
			}

			if (playing) {
				if (!terrainApplied) {
					startTerrain(level, p);
					terrainApplied = true;
				}
				showTerrain(level, p);
				refreshLight(level, p);

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
					say(p, "[debug] your real position: " + (int) savedX + " " + (int) savedY + " " + (int) savedZ
						+ " | replay starts at " + (int) frames.get(0).x() + " " + (int) frames.get(0).y()
						+ " " + (int) frames.get(0).z());
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
				applyBlockChanges(level, idx);

				// Position of the previous replay frame. We don't use the player's
				// current position here, because the game moves the player a little
				// during its own tick, which made the speed (and camera) jump around.
				double oldX = (idx == 0) ? f.x() : prevFrameX;
				double oldY = (idx == 0) ? f.y() : prevFrameY;
				double oldZ = (idx == 0) ? f.z() : prevFrameZ;
				prevFrameX = f.x();
				prevFrameY = f.y();
				prevFrameZ = f.z();

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

				// The real speed of this tick (the elytra wings and body banking
				// look at the speed direction, so it must not be zero)
				p.setDeltaMovement(f.x() - oldX, f.y() - oldY, f.z() - oldZ);
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

	// ---------------------------------------------------------------
	// Block changes during replay (blocks broken / placed while recording)
	// ---------------------------------------------------------------

	// Plays all block changes that are due at replay tick "idx"
	private static void applyBlockChanges(ClientLevel level, int idx) {
		// First try the changes that had to wait for their chunk
		if (!deferredBlocks.isEmpty()) {
			Iterator<BlockChange> it = deferredBlocks.iterator();
			while (it.hasNext()) {
				BlockChange b = it.next();
				if (setReplayBlock(level, b)) {
					it.remove();
				} else if (terrainPending.isEmpty()) {
					it.remove(); // all terrain is shown and this chunk is still not there: give up
				}
			}
		}

		while (blockEventPtr < loadedBlockEvents.size()) {
			BlockChange b = loadedBlockEvents.get(blockEventPtr);
			if (b.tick() > idx) break;
			blockEventPtr++;
			if (!setReplayBlock(level, b) && !terrainPending.isEmpty()) {
				deferredBlocks.add(b);
			}
		}
	}

	// Sets one block (false = the chunk isn't there). The block that was there before is
	// remembered, so the real world can be put back when the replay ends.
	private static boolean setReplayBlock(ClientLevel level, BlockChange b) {
		if (!isChunkLoaded(level, b.x() >> 4, b.z() >> 4)) return false;
		if (level.isOutsideBuildHeight(b.y())) return true;
		BlockPos pos = new BlockPos(b.x(), b.y(), b.z());
		long key = pos.asLong();
		if (!blockOriginals.containsKey(key)) {
			blockOriginals.put(key, level.getBlockState(pos));
		}
		// 18 = tell the renderer, but don't update the neighbours (the recording
		// already has their changes as separate events)
		level.setBlock(pos, Block.stateById(b.state()), 18);
		return true;
	}

	// Puts back the real blocks the replay changed. Chunks that were added by the
	// replay are dropped anyway, so only the real chunks are touched.
	private static void restoreBlocks(ClientLevel level) {
		deferredBlocks.clear();
		if (blockOriginals.isEmpty()) return;
		Set<Long> added = new HashSet<>(addedChunks);
		for (Map.Entry<Long, BlockState> e : blockOriginals.entrySet()) {
			BlockPos pos = BlockPos.of(e.getKey());
			int cx = pos.getX() >> 4;
			int cz = pos.getZ() >> 4;
			if (added.contains(chunkKey(cx, cz))) continue;
			if (!isChunkLoaded(level, cx, cz)) continue;
			level.setBlock(pos, e.getValue(), 18);
		}
		blockOriginals.clear();
	}

	// ---------------------------------------------------------------
	// Terrain saving (step 1: only saves, the replay doesn't use it yet)
	// ---------------------------------------------------------------

	// Is this chunk really loaded in the client right now?
	// (level.hasChunk() is not reliable here: it said yes for far away chunks)
	private static boolean isChunkLoaded(ClientLevel level, int cx, int cz) {
		return level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false) != null;
	}

	private static long chunkKey(int cx, int cz) {
		return (((long) cx) << 32) ^ (cz & 0xFFFFFFFFL);
	}

	// Saves the chunks around the player (inside the render distance) that
	// were not saved yet, a few per tick.
	private static void captureTerrain(LocalPlayer p, ClientLevel level) {
		int rd = Minecraft.getInstance().options.renderDistance().get();
		int pcx = p.getBlockX() >> 4;
		int pcz = p.getBlockZ() >> 4;
		int yStart = p.getBlockY() - TERRAIN_RANGE;
		int layers = TERRAIN_RANGE * 2 + 1;
		int budget = TERRAIN_PER_TICK;

		// Nearest chunks first (ring by ring)
		for (int r = 0; r <= rd && budget > 0; r++) {
			for (int dx = -r; dx <= r && budget > 0; dx++) {
				for (int dz = -r; dz <= r && budget > 0; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
					int cx = pcx + dx;
					int cz = pcz + dz;
					long key = chunkKey(cx, cz);
					if (terrain.containsKey(key)) continue;
					if (!isChunkLoaded(level, cx, cz)) continue;
					terrain.put(key, readChunk(level, cx, cz, yStart, layers));
					budget--;
				}
			}
		}
	}

	// Reads the blocks of one chunk column and packs them as (id, run length) pairs
	private static TerrainChunk readChunk(ClientLevel level, int cx, int cz, int yStart, int layers) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int[] buf = new int[1024];
		int n = 0;
		int lastId = -1;
		int run = 0;

		for (int y = yStart; y < yStart + layers; y++) {
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					int id = 0; // 0 = air
					if (!level.isOutsideBuildHeight(y)) {
						pos.set((cx << 4) + x, y, (cz << 4) + z);
						id = Block.getId(level.getBlockState(pos));
					}
					if (id == lastId) {
						run++;
					} else {
						if (run > 0) {
							if (n + 2 > buf.length) {
								buf = Arrays.copyOf(buf, buf.length * 2);
							}
							buf[n++] = lastId;
							buf[n++] = run;
						}
						lastId = id;
						run = 1;
					}
				}
			}
		}
		if (run > 0) {
			if (n + 2 > buf.length) {
				buf = Arrays.copyOf(buf, buf.length * 2);
			}
			buf[n++] = lastId;
			buf[n++] = run;
		}
		return new TerrainChunk(cx, cz, yStart, layers, Arrays.copyOf(buf, n));
	}

	// ---------------------------------------------------------------
	// Terrain in replay (step 2)
	// ---------------------------------------------------------------

	// Finds the saved chunks that are missing in the real world, and makes the
	// client's chunk area big enough for them (the game ignores chunks that are
	// outside its chunk area)
	private static void startTerrain(ClientLevel level, LocalPlayer p) {
		blockEventPtr = 0;
		deferredBlocks.clear();
		blockOriginals.clear();
		terrainPending.clear();
		addedChunks.clear();
		lightRefresh.clear();
		refreshChunks = 0;
		refreshMissing = 0;
		refreshChanged = 0;
		terrainAdded = 0;
		terrainRadiusRaised = false;

		if (frames.isEmpty()) return;
		if (loadedTerrain.isEmpty()) {
			say(p, "This replay has no saved terrain.");
			return;
		}

		int centerX = p.getBlockX() >> 4;
		int centerZ = p.getBlockZ() >> 4;
		int far = 0;
		int alreadyLoaded = 0;
		int tooFar = 0;
		int nearest = Integer.MAX_VALUE;
		for (TerrainChunk t : loadedTerrain.values()) {
			int dist = Math.max(Math.abs(t.cx() - centerX), Math.abs(t.cz() - centerZ));
			nearest = Math.min(nearest, dist);
			if (isChunkLoaded(level, t.cx(), t.cz())) { // the real chunk is already there
				alreadyLoaded++;
				continue;
			}
			if (dist > TERRAIN_MAX_RADIUS) {
				tooFar++;
				continue;
			}
			terrainPending.add(t);
			far = Math.max(far, dist);
		}

		// Debug line (will be removed later)
		say(p, "[debug] you are in chunk " + centerX + "," + centerZ
			+ " | saved " + loadedTerrain.size()
			+ ", already loaded " + alreadyLoaded
			+ ", too far " + tooFar
			+ ", to add " + terrainPending.size()
			+ ", nearest saved chunk is " + nearest + " chunks away");

		if (terrainPending.isEmpty()) {
			return;
		}

		// The chunks nearest to where the replay starts come first
		final int startX = Mth.floor(frames.get(0).x()) >> 4;
		final int startZ = Mth.floor(frames.get(0).z()) >> 4;
		terrainPending.sort((a, b) -> Integer.compare(
			Math.max(Math.abs(a.cx() - startX), Math.abs(a.cz() - startZ)),
			Math.max(Math.abs(b.cx() - startX), Math.abs(b.cz() - startZ))));

		int rd = Minecraft.getInstance().options.renderDistance().get();
		if (far + 1 > rd) {
			level.getChunkSource().updateViewRadius(far + 1);
			terrainRadiusRaised = true;
		}

		// Calculate the light of all these chunks in the background
		terrainLight = null;
		final int gen = ++terrainLightGen;
		final List<TerrainChunk> forLight = new ArrayList<>(terrainPending);
		Thread lightThread = new Thread(() -> {
			Map<Long, LightChunk> result;
			try {
				result = computeLight(forLight);
			} catch (Throwable ex) {
				result = new HashMap<>(); // no light data: the terrain gets plain full sky light
			}
			if (gen == terrainLightGen) {
				terrainLight = result;
			}
		}, "rw-terrain-light");
		lightThread.setDaemon(true);
		lightThread.start();

		say(p, "Adding " + terrainPending.size() + " chunks of terrain (calculating light first)...");
	}

	// Adds a few missing chunks per tick
	private static void showTerrain(ClientLevel level, LocalPlayer p) {
		if (terrainPending.isEmpty()) return;
		if (terrainLight == null) return; // still calculating the light

		int minSection = level.dimensionType().minY() >> 4;

		for (int n = 0; n < TERRAIN_SHOW_PER_TICK && !terrainPending.isEmpty(); n++) {
			TerrainChunk t = terrainPending.remove(0);
			if (isChunkLoaded(level, t.cx(), t.cz())) continue;
			addTerrainChunk(level, t, minSection);
		}

		if (terrainPending.isEmpty()) {
			say(p, "Terrain shown: " + terrainAdded + " chunks");
		}
	}

	// Builds one chunk from the saved blocks and gives it to the game the same way
	// the server does (chunk + light packet). That way the game sets up the light and
	// render mods like Sodium know about the chunk, so it is really drawn.
	private static void addTerrainChunk(ClientLevel level, TerrainChunk t, int minSection) {
		try {
			int[] ids = decodeTerrain(t);
			LevelChunk chunk = new LevelChunk(level, new ChunkPos(t.cx(), t.cz()));
			int sectionCount = chunk.getSections().length;

			for (int s = 0; s < sectionCount; s++) {
				int sectionY = minSection + s;
				LevelChunkSection section = chunk.getSection(s);
				for (int ly = 0; ly < 16; ly++) {
					int layer = ((sectionY << 4) + ly) - t.yStart();
					if (layer < 0 || layer >= t.layers()) continue; // outside the saved range: stays air
					for (int z = 0; z < 16; z++) {
						for (int x = 0; x < 16; x++) {
							int id = ids[(layer * 16 + z) * 16 + x];
							if (id != 0) {
								section.setBlockState(x, ly, z, Block.stateById(id), false);
							}
						}
					}
				}
			}

			ClientPacketListener connection = Minecraft.getInstance().getConnection();
			if (connection == null) return;
			new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null)
				.handle(connection);

			// The saved terrain has no light data, so the light was calculated by the mod
			// (see LightCalc). It is handed to the light engine like a server would do it.
			Map<Long, LightChunk> lightMap = terrainLight;
			LightChunk lc = (lightMap == null) ? null : lightMap.get(chunkKey(t.cx(), t.cz()));
			for (int s = 0; s < sectionCount; s++) {
				int sectionY = minSection + s;
				SectionPos sp = SectionPos.of(t.cx(), sectionY, t.cz());
				level.getLightEngine().queueSectionData(LightLayer.SKY, sp,
					buildLightLayer(lc, t, sectionY, true));
				level.getLightEngine().queueSectionData(LightLayer.BLOCK, sp,
					buildLightLayer(lc, t, sectionY, false));
			}

			if (isChunkLoaded(level, t.cx(), t.cz())) {
				addedChunks.add(chunkKey(t.cx(), t.cz()));
				lightRefresh.add(new long[] {chunkKey(t.cx(), t.cz()), refreshTick + REFRESH_DELAY_TICKS});
				terrainAdded++;
			}
		} catch (Exception ex) {
			// skip this chunk
		}
	}

	// A little while after a chunk was added, its light is given to the game again and
	// the chunk's sections are redrawn. The first light data can get lost or arrive
	// after the chunk was already drawn; the game then keeps the dark picture forever.
	// It also counts how many sections the game was missing or had different light,
	// and prints that once at the end (so we can see if this was the problem).
	private static void refreshLight(ClientLevel level, LocalPlayer p) {
		refreshTick++;
		if (lightRefresh.isEmpty()) return;
		Map<Long, LightChunk> lightMap = terrainLight;
		if (lightMap == null) return;

		int minSection = level.dimensionType().minY() >> 4;
		int sectionCount = level.getSectionsCount();

		for (int n = 0; n < REFRESH_PER_TICK && !lightRefresh.isEmpty(); n++) {
			long[] entry = lightRefresh.get(0);
			if (entry[1] > refreshTick) break; // not due yet (the list is in order)
			lightRefresh.remove(0);

			long key = entry[0];
			int cx = (int) (key >> 32);
			int cz = (int) key;
			LightChunk lc = lightMap.get(key);
			if (lc == null || !isChunkLoaded(level, cx, cz)) continue;
			TerrainChunk t = lc.t;
			refreshChunks++;

			for (int s = 0; s < sectionCount; s++) {
				int sectionY = minSection + s;
				SectionPos sp = SectionPos.of(cx, sectionY, cz);
				for (int k = 0; k < 2; k++) {
					boolean sky = (k == 0);
					LightLayer layerType = sky ? LightLayer.SKY : LightLayer.BLOCK;
					DataLayer ours = buildLightLayer(lc, t, sectionY, sky);
					DataLayer current = level.getLightEngine().getLayerListener(layerType).getDataLayerData(sp);
					if (current == null) {
						refreshMissing++;
					} else if (!Arrays.equals(current.getData(), ours.getData())) {
						refreshChanged++;
					}
					level.getLightEngine().queueSectionData(layerType, sp, ours);
				}
				// redraw only the sections that hold saved blocks
				int from = (sectionY << 4) - t.yStart();
				if (from < t.layers() && from + 16 > 0) {
					level.setSectionDirtyWithNeighbors(cx, sectionY, cz);
				}
			}
		}

		if (lightRefresh.isEmpty() && refreshChunks > 0) {
			say(p, "[debug] light check: " + refreshChunks + " chunks, sections the game had no light for: "
				+ refreshMissing + ", with different light: " + refreshChanged + " (light given again)");
			refreshChunks = 0;
			refreshMissing = 0;
			refreshChanged = 0;
		}
	}

	// ---------------------------------------------------------------
	// Light of the replay terrain (calculated by the mod, because the saved
	// terrain has no light data)
	// ---------------------------------------------------------------

	private static final int[] DX = {1, -1, 0, 0, 0, 0};
	private static final int[] DY = {0, 0, 0, 0, 1, -1};
	private static final int[] DZ = {0, 0, 1, -1, 0, 0};

	// Block ids and the calculated light levels (0 to 15) of one saved chunk.
	// Cell index = (layer * 16 + z) * 16 + x
	private static final class LightChunk {
		final TerrainChunk t;
		final int[] ids;
		final byte[] sky;
		final byte[] block;

		LightChunk(TerrainChunk t, int[] ids) {
			this.t = t;
			this.ids = ids;
			this.sky = new byte[ids.length];
			this.block = new byte[ids.length];
		}
	}

	private static Map<Long, LightChunk> computeLight(List<TerrainChunk> list) {
		Map<Long, LightChunk> map = new HashMap<>();
		for (TerrainChunk t : list) {
			map.put(chunkKey(t.cx(), t.cz()), new LightChunk(t, decodeTerrain(t)));
		}
		new LightCalc(map).run();
		return map;
	}

	// Turns the light levels of one chunk section into a light layer for the game
	private static DataLayer buildLightLayer(LightChunk lc, TerrainChunk t, int sectionY, boolean sky) {
		byte[] data = new byte[2048];
		for (int ly = 0; ly < 16; ly++) {
			int layer = ((sectionY << 4) + ly) - t.yStart();
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					int value;
					if (lc != null && layer >= 0 && layer < t.layers()) {
						value = (sky ? lc.sky : lc.block)[(layer * 16 + z) * 16 + x];
					} else if (sky) {
						// above the saved part (or no calculation): open sky
						value = (lc == null || layer >= t.layers()) ? 15 : 0;
					} else {
						value = 0;
					}
					int index = (ly << 8) | (z << 4) | x;
					int byteIndex = index >> 1;
					if ((index & 1) == 0) {
						data[byteIndex] = (byte) ((data[byteIndex] & 0xF0) | (value & 15));
					} else {
						data[byteIndex] = (byte) ((data[byteIndex] & 0x0F) | ((value & 15) << 4));
					}
				}
			}
		}
		return new DataLayer(data);
	}

	// Calculates sky light and block light for all saved chunks, with the same
	// rules as the game: light goes down from the sky, spreads to the sides and
	// gets weaker by 1 per block (more through leaves, water, etc.)
	private static final class LightCalc {
		private final Map<Long, LightChunk> chunks;
		private final short[] opacityCache = new short[65536]; // opacity + 1, 0 = not known yet
		private final short[] emissionCache = new short[65536]; // emission + 1, 0 = not known yet
		private long[] queue = new long[1 << 16];
		private int head;
		private int tail;
		private long lastKey = Long.MIN_VALUE;
		private LightChunk lastChunk;

		LightCalc(Map<Long, LightChunk> chunks) {
			this.chunks = chunks;
		}

		void run() {
			skyDown();
			skyStart();
			spread(true);
			blockStart();
			spread(false);
		}

		private LightChunk chunkAt(int cx, int cz) {
			long key = chunkKey(cx, cz);
			if (key != lastKey) {
				lastKey = key;
				lastChunk = chunks.get(key);
			}
			return lastChunk;
		}

		private int opacity(int id) {
			if (id < 0 || id >= opacityCache.length) {
				return Block.stateById(id).getLightBlock();
			}
			int v = opacityCache[id];
			if (v == 0) {
				v = Block.stateById(id).getLightBlock() + 1;
				opacityCache[id] = (short) v;
			}
			return v - 1;
		}

		private int emission(int id) {
			if (id < 0 || id >= emissionCache.length) {
				return Block.stateById(id).getLightEmission();
			}
			int v = emissionCache[id];
			if (v == 0) {
				v = Block.stateById(id).getLightEmission() + 1;
				emissionCache[id] = (short) v;
			}
			return v - 1;
		}

		// Index of a cell inside a chunk, or -1 if it is outside the saved layers
		private int cellIndex(LightChunk c, int y, int wx, int wz) {
			int ly = y - c.t.yStart();
			if (ly < 0 || ly >= c.t.layers()) return -1;
			return (ly * 16 + (wz & 15)) * 16 + (wx & 15);
		}

		// Sky light straight down from the top of the saved part
		private void skyDown() {
			for (LightChunk c : chunks.values()) {
				int layers = c.t.layers();
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						int cur = 15;
						for (int ly = layers - 1; ly >= 0; ly--) {
							int idx = (ly * 16 + z) * 16 + x;
							int op = opacity(c.ids[idx]);
							if (op >= 15) {
								cur = 0;
							} else if (op > 0) {
								cur = Math.max(0, cur - op);
							}
							c.sky[idx] = (byte) cur;
						}
					}
				}
			}
		}

		// Sky-lit cells that have a darker neighbour next to them start the spreading
		private void skyStart() {
			head = 0;
			tail = 0;
			for (LightChunk c : chunks.values()) {
				int layers = c.t.layers();
				for (int ly = 0; ly < layers; ly++) {
					int y = c.t.yStart() + ly;
					for (int z = 0; z < 16; z++) {
						for (int x = 0; x < 16; x++) {
							int idx = (ly * 16 + z) * 16 + x;
							int level = c.sky[idx];
							if (level <= 1) continue;
							int wx = (c.t.cx() << 4) + x;
							int wz = (c.t.cz() << 4) + z;
							for (int d = 0; d < 4; d++) {
								int nx = wx + DX[d];
								int nz = wz + DZ[d];
								LightChunk nc = chunkAt(nx >> 4, nz >> 4);
								if (nc == null) continue;
								int ni = cellIndex(nc, y, nx, nz);
								if (ni < 0) continue;
								if (opacity(nc.ids[ni]) >= 15) continue;
								if (nc.sky[ni] < level - 1) {
									push(wx, y, wz);
									break;
								}
							}
						}
					}
				}
			}
		}

		// Every light-emitting block (torch, lantern, glowstone...) starts the spreading
		private void blockStart() {
			head = 0;
			tail = 0;
			for (LightChunk c : chunks.values()) {
				int layers = c.t.layers();
				for (int ly = 0; ly < layers; ly++) {
					int y = c.t.yStart() + ly;
					for (int z = 0; z < 16; z++) {
						for (int x = 0; x < 16; x++) {
							int idx = (ly * 16 + z) * 16 + x;
							int id = c.ids[idx];
							if (id == 0) continue;
							int em = emission(id);
							if (em > 0) {
								c.block[idx] = (byte) em;
								push((c.t.cx() << 4) + x, y, (c.t.cz() << 4) + z);
							}
						}
					}
				}
			}
		}

		// Spreads the light from the queued cells to their neighbours
		private void spread(boolean sky) {
			while (head < tail) {
				long v = queue[head++];
				int x = unpackX(v);
				int y = unpackY(v);
				int z = unpackZ(v);

				LightChunk c = chunkAt(x >> 4, z >> 4);
				if (c == null) continue;
				int ci = cellIndex(c, y, x, z);
				if (ci < 0) continue;
				int level = (sky ? c.sky : c.block)[ci];
				if (level <= 1) continue;

				for (int d = 0; d < 6; d++) {
					int nx = x + DX[d];
					int ny = y + DY[d];
					int nz = z + DZ[d];
					LightChunk nc = chunkAt(nx >> 4, nz >> 4);
					if (nc == null) continue;
					int ni = cellIndex(nc, ny, nx, nz);
					if (ni < 0) continue;

					int op = opacity(nc.ids[ni]);
					int newLevel;
					if (sky && d == 5 && level == 15 && op == 0) {
						newLevel = 15; // full sky light keeps going straight down
					} else {
						newLevel = level - Math.max(1, op);
					}
					if (newLevel <= 0) continue;

					byte[] arr = sky ? nc.sky : nc.block;
					if (newLevel > arr[ni]) {
						arr[ni] = (byte) newLevel;
						push(nx, ny, nz);
					}
				}
			}
		}

		private void push(int x, int y, int z) {
			if (tail == queue.length) {
				queue = Arrays.copyOf(queue, queue.length * 2);
			}
			queue[tail++] = ((long) (x & 0x3FFFFFF) << 38)
				| ((long) (z & 0x3FFFFFF) << 12)
				| (long) ((y + 2048) & 0xFFF);
		}

		private static int unpackX(long v) {
			int x = (int) (v >> 38);
			return (x << 6) >> 6;
		}

		private static int unpackZ(long v) {
			int z = (int) ((v >> 12) & 0x3FFFFFF);
			return (z << 6) >> 6;
		}

		private static int unpackY(long v) {
			return (int) (v & 0xFFF) - 2048;
		}
	}

	// Turns the saved (block id, run length) pairs back into one id per block
	private static int[] decodeTerrain(TerrainChunk t) {
		int[] out = new int[t.layers() * 256];
		int pos = 0;
		for (int i = 0; i + 1 < t.rle().length; i += 2) {
			int id = t.rle()[i];
			int run = t.rle()[i + 1];
			for (int k = 0; k < run && pos < out.length; k++) {
				out[pos++] = id;
			}
		}
		return out;
	}

	// Removes only the chunks we added, so the real world stays as it was
	private static void removeTerrain(ClientLevel level) {
		terrainPending.clear();
		lightRefresh.clear();
		terrainLight = null;
		terrainLightGen++;
		for (long key : addedChunks) {
			int cx = (int) (key >> 32);
			int cz = (int) key;
			level.getChunkSource().drop(new ChunkPos(cx, cz));
		}
		addedChunks.clear();
		if (terrainRadiusRaised) {
			level.getChunkSource().updateViewRadius(Minecraft.getInstance().options.renderDistance().get());
			terrainRadiusRaised = false;
		}
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
			say(p, "[debug] put back at " + (int) savedX + " " + (int) savedY + " " + (int) savedZ);
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
				.then(ClientCommandManager.literal("cancel").executes(ctx -> {
					recording = false;
					playing = false;
					clearGhosts();
					ctx.getSource().sendFeedback(Component.literal("Cancelled."));
					return 1;
				}))
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
							// Shadow Rewind doesn't save terrain yet
							save(name, copy, new LinkedHashMap<>(), new ArrayList<>());
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

	private static void save(String name, List<Frame> data, Map<Long, TerrainChunk> terr,
			List<BlockChange> changes) throws IOException {
		try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
				new GZIPOutputStream(Files.newOutputStream(dir().resolve(name + ".rw")))))) {
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

			// Terrain
			out.writeInt(terr.size());
			for (TerrainChunk t : terr.values()) {
				out.writeInt(t.cx());
				out.writeInt(t.cz());
				out.writeInt(t.yStart());
				out.writeInt(t.layers());
				out.writeInt(t.rle().length);
				for (int v : t.rle()) {
					out.writeInt(v);
				}
			}

			// Block changes
			out.writeInt(changes.size());
			for (BlockChange b : changes) {
				out.writeInt(b.tick());
				out.writeInt(b.x());
				out.writeInt(b.y());
				out.writeInt(b.z());
				out.writeInt(b.state());
			}
		}
	}

	private static void load(String name) throws IOException {
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(
				new GZIPInputStream(Files.newInputStream(dir().resolve(name + ".rw")))))) {
			int magic = in.readInt();
			if (magic != MAGIC && magic != MAGIC_V8) {
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

			// Terrain
			int chunkCount = in.readInt();
			if (chunkCount < 0 || chunkCount > 200000) {
				throw new IOException("Corrupt replay file");
			}
			Map<Long, TerrainChunk> loadedMap = new LinkedHashMap<>();
			for (int i = 0; i < chunkCount; i++) {
				int cx = in.readInt();
				int cz = in.readInt();
				int yStart = in.readInt();
				int layers = in.readInt();
				int rleLen = in.readInt();
				if (rleLen < 0 || rleLen > 40000) {
					throw new IOException("Corrupt replay file");
				}
				int[] rle = new int[rleLen];
				for (int j = 0; j < rleLen; j++) {
					rle[j] = in.readInt();
				}
				loadedMap.put(chunkKey(cx, cz), new TerrainChunk(cx, cz, yStart, layers, rle));
			}

			// Block changes (files of the older format have none)
			List<BlockChange> changes = new ArrayList<>();
			if (magic == MAGIC) {
				int changeCount = in.readInt();
				if (changeCount < 0 || changeCount > MAX_BLOCK_EVENTS) {
					throw new IOException("Corrupt replay file");
				}
				for (int i = 0; i < changeCount; i++) {
					int tick = in.readInt();
					int bx = in.readInt();
					int by = in.readInt();
					int bz = in.readInt();
					int state = in.readInt();
					changes.add(new BlockChange(tick, bx, by, bz, state));
				}
			}

			frames = loaded;
			loadedTerrain = loadedMap;
			loadedBlockEvents = changes;
		} catch (ZipException ex) {
			throw new IOException("This is an old file format, please record a new one");
		}
	}
}
