package com.warden.mixin.client;

import com.warden.WardenClient;
import com.warden.WardenMod;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The server already refuses the hit; without this the client still plays the swing and
 * the player reads it as lag. Uses the synced limits so it only fires when the server would.
 */
@Mixin(ClientPlayerInteractionManager.class)
public abstract class ClientPlayerInteractionManagerMixin {

    @Inject(method = "attackEntity", at = @At("HEAD"), cancellable = true)
    private void warden$blockAttackOnCooldown(PlayerEntity player, Entity target, CallbackInfo ci) {
        if (WardenClient.CONFIG != null && !WardenClient.CONFIG.blockSwingsOnCooldown) {
            return;
        }
        if (WardenMod.isWeaponAttackBlockedByCooldown(player)) {
            ci.cancel();
        }
    }
}
