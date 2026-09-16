package com.warden.mixin.vanish;

import com.warden.vanish.Vanish;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ServerPlayerEntity.class)
public abstract class ServerPlayerEntityMixin {

    @Redirect(method = "onDeath",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/PlayerManager;broadcast(Lnet/minecraft/text/Text;Z)V"))
    private void warden$silentDeath(PlayerManager manager, Text message, boolean overlay, DamageSource source) {
        if (!Vanish.isVanished((ServerPlayerEntity) (Object) this)) {
            manager.broadcast(message, overlay);
        }
    }
}
