package com.example.client.mixin;

import com.example.client.ExampleModClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public class BlockChangeMixin {
	// While recording, tell the mod every time a block in the client world changes
	// (broken, placed, burnt, grown...). "require = 0": if the game's method looks
	// different in some version, this is skipped instead of crashing the game
	// (the save message then shows 0 block changes).
	@Inject(method = "sendBlockUpdated(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;I)V",
		at = @At("HEAD"), require = 0)
	private void rw$recordBlockChange(BlockPos pos, BlockState oldState, BlockState newState,
			int flags, CallbackInfo ci) {
		ExampleModClient.onBlockChanged(pos, oldState, newState);
	}
}
