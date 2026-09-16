package com.warden.mixin.vanish;

import com.warden.vanish.Vanish;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.TargetPredicate;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// the choke point for mob targeting and every getClosestPlayer that takes a predicate
@Mixin(TargetPredicate.class)
public abstract class TargetPredicateMixin {

    @Inject(method = "test", at = @At("HEAD"), cancellable = true)
    private void warden$ignoreVanished(ServerWorld world, LivingEntity tester, LivingEntity target, CallbackInfoReturnable<Boolean> cir) {
        if (!Vanish.canSee(tester, target)) {
            cir.setReturnValue(false);
        }
    }
}
