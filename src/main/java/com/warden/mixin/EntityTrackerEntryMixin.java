package com.warden.mixin;

import com.warden.WardenModeration;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.EntityTrackerEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// stops a vanished player from being (re-)spawned client-side for a viewer who walks into range;
// WardenModeration handles the immediate hide/show packets for players already tracking them
@Mixin(EntityTrackerEntry.class)
public abstract class EntityTrackerEntryMixin {

    @Shadow
    @Final
    private Entity entity;

    @Inject(method = "startTracking", at = @At("HEAD"), cancellable = true)
    private void warden$hideVanished(ServerPlayerEntity player, CallbackInfo ci) {
        if (entity instanceof ServerPlayerEntity target && target != player && WardenModeration.isVanished(target.getUuid())) {
            ci.cancel();
        }
    }
}
