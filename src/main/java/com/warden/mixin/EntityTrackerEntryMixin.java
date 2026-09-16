package com.warden.mixin;

import com.warden.vanish.Vanish;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.EntityTrackerEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// the entity itself: a viewer who walks into range of a vanished player never gets the spawn packet
@Mixin(EntityTrackerEntry.class)
public abstract class EntityTrackerEntryMixin {

    @Shadow
    @Final
    private Entity entity;

    @Inject(method = "startTracking", at = @At("HEAD"), cancellable = true)
    private void warden$hideVanished(ServerPlayerEntity player, CallbackInfo ci) {
        if (!Vanish.canSee(player, entity)) {
            ci.cancel();
        }
    }
}
