package com.warden.mixin.vanish;

import com.warden.vanish.Vanish;
import net.minecraft.advancement.AdvancementDisplay;
import net.minecraft.advancement.AdvancementEntry;
import net.minecraft.advancement.PlayerAdvancementTracker;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(PlayerAdvancementTracker.class)
public abstract class PlayerAdvancementTrackerMixin {

    @Shadow
    private ServerPlayerEntity owner;

    // the announce sits in the display lambda grantCriterion hands to Optional.ifPresent
    @Redirect(method = "method_53637",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/PlayerManager;broadcast(Lnet/minecraft/text/Text;Z)V"))
    private void warden$silentAdvancement(PlayerManager manager, Text message, boolean overlay, AdvancementEntry advancement, AdvancementDisplay display) {
        if (!Vanish.isVanished(owner)) {
            manager.broadcast(message, overlay);
        }
    }
}
