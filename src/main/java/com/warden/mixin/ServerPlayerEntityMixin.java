package com.warden.mixin;

import com.warden.WardenMod;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.TeleportTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// catch-all for /execute in, end gateways, other mods: anything that moves a player between worlds
@Mixin(ServerPlayerEntity.class)
public abstract class ServerPlayerEntityMixin {

    @Inject(method = "teleportTo(Lnet/minecraft/world/TeleportTarget;)Lnet/minecraft/server/network/ServerPlayerEntity;",
            at = @At("HEAD"), cancellable = true)
    private void warden$blockDimension(TeleportTarget target, CallbackInfoReturnable<ServerPlayerEntity> cir) {
        ServerPlayerEntity self = (ServerPlayerEntity) (Object) this;
        if (target.world() != self.getEntityWorld() && WardenMod.isDimensionBlocked(self, target.world().getRegistryKey())) {
            cir.setReturnValue(self);
        }
    }
}
