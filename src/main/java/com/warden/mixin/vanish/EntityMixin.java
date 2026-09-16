package com.warden.mixin.vanish;

import com.warden.vanish.Vanish;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.world.event.GameEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntityMixin {

    // no sculk vibrations, no warden, no allay, no raid sensors
    @Inject(method = "emitGameEvent(Lnet/minecraft/registry/entry/RegistryEntry;Lnet/minecraft/entity/Entity;)V",
            at = @At("HEAD"), cancellable = true)
    private void warden$silentEvents(RegistryEntry<GameEvent> event, Entity source, CallbackInfo ci) {
        if (Vanish.isVanished((Entity) (Object) this) || Vanish.isVanished(source)) {
            ci.cancel();
        }
    }

    @Inject(method = "pushAwayFrom", at = @At("HEAD"), cancellable = true)
    private void warden$noPush(Entity entity, CallbackInfo ci) {
        if (Vanish.isVanished((Entity) (Object) this) || Vanish.isVanished(entity)) {
            ci.cancel();
        }
    }

    // items and xp orbs would otherwise vanish out from under whoever is watching
    @Inject(method = "onPlayerCollision", at = @At("HEAD"), cancellable = true)
    private void warden$noPickup(PlayerEntity player, CallbackInfo ci) {
        if (Vanish.isVanished(player)) {
            ci.cancel();
        }
    }
}
