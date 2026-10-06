package com.example.client.mixin;

import com.example.client.ExampleModClient;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
public class ExampleClientMixin {
	// During replay, don't tell the server where the player is
	@Inject(method = "sendPosition", at = @At("HEAD"), cancellable = true)
	private void rw$blockMovePackets(CallbackInfo ci) {
		if (ExampleModClient.isPlaying()) {
			ci.cancel();
		}
	}
}
