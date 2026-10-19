package com.example.client.mixin;

import com.example.client.ExampleModClient;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public class AttackIndicatorMixin {
	// During replay our own player always counts as "fully charged", so the cooldown
	// bar under the crosshair doesn't flicker. "require = 0": if the method looks
	// different in some version, this is skipped instead of crashing the game.
	@Inject(method = "getAttackStrengthScale(F)F", at = @At("HEAD"), cancellable = true, require = 0)
	private void rw$noCooldownInReplay(float adjustTicks, CallbackInfoReturnable<Float> cir) {
		if (ExampleModClient.hideCooldown((Player) (Object) this)) {
			cir.setReturnValue(1.0F);
		}
	}
}
