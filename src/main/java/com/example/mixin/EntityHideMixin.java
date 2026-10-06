package com.example.client.mixin;

import com.example.client.ExampleModClient;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderDispatcher.class)
public class EntityHideMixin {
	// During replay, don't draw real entities (only the recorded ghosts are shown)
	@Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
	private void rw$hideRealEntities(Entity entity, Frustum frustum, double x, double y, double z,
			CallbackInfoReturnable<Boolean> cir) {
		if (ExampleModClient.shouldHide(entity)) {
			cir.setReturnValue(false);
		}
	}
  }
