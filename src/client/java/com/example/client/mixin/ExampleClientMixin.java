package com.example.client.mixin;

import com.example.client.ExampleModClient;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LocalPlayer.class)
public class ExampleClientMixin {
	// During replay, don't tell the server where the player is
	@Inject(method = "sendPosition", at = @At("HEAD"), cancellable = true)
	private void rw$blockMovePackets(CallbackInfo ci) {
		if (ExampleModClient.isPlaying()) {
			ci.cancel();
		}
	}

	// During replay, the player model crouches when the recording was crouching
	@Inject(method = "isCrouching", at = @At("HEAD"), cancellable = true)
	private void rw$replayCrouch(CallbackInfoReturnable<Boolean> cir) {
		if (ExampleModClient.isPlaying()) {
			cir.setReturnValue(ExampleModClient.replayCrouching());
		}
	}
}
