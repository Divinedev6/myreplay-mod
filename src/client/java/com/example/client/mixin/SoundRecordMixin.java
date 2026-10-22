package com.example.client.mixin;

import com.example.client.ExampleModClient;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SoundManager.class)
public class SoundRecordMixin {
	// Every sound the game plays goes through here. While recording, the mod writes
	// the sound down (what, where, how loud). "require = 0": if the method looks
	// different in some version, this is skipped instead of crashing the game
	// (the save message then shows 0 sounds).
	@Inject(method = "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)Lnet/minecraft/client/sounds/SoundEngine$PlayResult;",
		at = @At("HEAD"), cancellable = true, require = 0)
	private void rw$recordSound(SoundInstance sound, CallbackInfoReturnable<?> cir) {
		if (ExampleModClient.shouldMuteSound()) {
			cir.setReturnValue(null);
			return;
		}
		ExampleModClient.onSound(sound);
	}
}
