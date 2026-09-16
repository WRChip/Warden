package com.warden.mixin.vanish;

import com.warden.vanish.Vanish;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(PlayerManager.class)
public abstract class PlayerManagerMixin {

    @Shadow
    public abstract List<ServerPlayerEntity> getPlayerList();

    @Redirect(method = "onPlayerConnect",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/PlayerManager;broadcast(Lnet/minecraft/text/Text;Z)V"))
    private void warden$silentJoin(PlayerManager manager, Text message, boolean overlay,
                                   ClientConnection connection, ServerPlayerEntity player, ConnectedClientData data) {
        if (!Vanish.isVanished(player)) {
            manager.broadcast(message, overlay);
        }
    }

    // runs once the joining client already has the real tab list, so the removals stick
    @Inject(method = "onPlayerConnect", at = @At("TAIL"))
    private void warden$hideOnJoin(ClientConnection connection, ServerPlayerEntity player, ConnectedClientData data, CallbackInfo ci) {
        if (!Vanish.anyVanished()) {
            return;
        }
        for (ServerPlayerEntity other : getPlayerList()) {
            if (other == player) {
                continue;
            }
            if (!Vanish.canSee(player, other)) {
                player.networkHandler.sendPacket(new PlayerRemoveS2CPacket(List.of(other.getUuid())));
            }
            if (!Vanish.canSee(other, player)) {
                other.networkHandler.sendPacket(new PlayerRemoveS2CPacket(List.of(player.getUuid())));
            }
        }
    }
}
