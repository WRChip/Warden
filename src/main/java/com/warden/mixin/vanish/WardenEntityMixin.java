package com.warden.mixin.vanish;

import com.warden.vanish.Vanish;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.WardenEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WardenEntity.class)
public abstract class WardenEntityMixin {

    @Inject(method = "isValidTarget", at = @At("HEAD"), cancellable = true)
    private void warden$ignoreVanished(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (Vanish.isVanished(entity)) {
            cir.setReturnValue(false);
        }
    }
}
