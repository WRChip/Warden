package com.warden.mixin.vanish;

import com.warden.vanish.Vanish;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// the leave message moved out of PlayerManager.remove and into the handler teardown
@Mixin(ServerPlayNetworkHandler.class)
public abstract class ServerPlayNetworkHandlerMixin {

    @Shadow
    public ServerPlayerEntity player;

    @Redirect(method = "cleanUp",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/PlayerManager;broadcast(Lnet/minecraft/text/Text;Z)V"))
    private void warden$silentLeave(PlayerManager manager, Text message, boolean overlay) {
        if (!Vanish.isVanished(player)) {
            manager.broadcast(message, overlay);
        }
    }
}
