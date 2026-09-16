package com.warden.mixin.vanish;

import com.warden.vanish.Vanish;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket;
import net.minecraft.server.network.ServerCommonNetworkHandler;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// catch-all for the tab list: anything that hands a client an ADD_PLAYER entry it should not have
// gets a matching removal chased after it, whatever sent it
@Mixin(ServerCommonNetworkHandler.class)
public abstract class ServerCommonNetworkHandlerMixin {

    @Inject(method = "send(Lnet/minecraft/network/packet/Packet;Lio/netty/channel/ChannelFutureListener;)V", at = @At("RETURN"))
    private void warden$dropVanishedEntries(Packet<?> packet, ChannelFutureListener listener, CallbackInfo ci) {
        if (!Vanish.anyVanished()) {
            return;
        }
        if (!(packet instanceof PlayerListS2CPacket list) || !list.getActions().contains(PlayerListS2CPacket.Action.ADD_PLAYER)) {
            return;
        }
        if (!((Object) this instanceof ServerPlayNetworkHandler handler)) {
            return;
        }
        List<UUID> hidden = new ArrayList<>();
        for (PlayerListS2CPacket.Entry entry : list.getEntries()) {
            if (!Vanish.canSee(handler.player, entry.profileId())) {
                hidden.add(entry.profileId());
            }
        }
        if (!hidden.isEmpty()) {
            handler.sendPacket(new PlayerRemoveS2CPacket(hidden));
        }
    }
}
