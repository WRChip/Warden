package com.warden.mixin;

import com.warden.WardenMod;
import net.minecraft.entity.projectile.thrown.ExperienceBottleEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.hit.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ExperienceBottleEntity.class)
public abstract class ExperienceBottleEntityMixin {

    @Inject(method = "onCollision", at = @At("HEAD"))
    private void warden$trackBottleXp(HitResult hit, CallbackInfo ci) {
        WardenMod.XP_SOURCE.set("xp_bottle");
        WardenMod.XP_CONTEXT.set("");
        WardenMod.XP_PLAYER.set(((ExperienceBottleEntity) (Object) this).getOwner() instanceof ServerPlayerEntity thrower ? thrower : null);
    }

    @Inject(method = "onCollision", at = @At("TAIL"))
    private void warden$clearBottleXp(HitResult hit, CallbackInfo ci) {
        WardenMod.XP_SOURCE.set("unknown");
        WardenMod.XP_CONTEXT.set("");
        WardenMod.XP_PLAYER.set(null);
    }
}
