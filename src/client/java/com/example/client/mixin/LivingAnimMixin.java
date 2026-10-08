package com.example.client.mixin;

import com.example.client.ExampleModClient;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public class LivingAnimMixin {
	// During replay, the game's own walk-animation update for our player is skipped,
	// the mod does it itself (so it doesn't fight with the replay movement)
	@Inject(method = "calculateEntityAnimation", at = @At("HEAD"), cancellable = true)
	private void rw$ownWalkAnimation(boolean flying, CallbackInfo ci) {
		if (ExampleModClient.shouldBlockVanillaAnimation((LivingEntity) (Object) this)) {
			ci.cancel();
		}
	}
    }
