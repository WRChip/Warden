package com.warden.mixin;

import com.warden.WardenMod;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.village.TradeOffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VillagerEntity.class)
public abstract class VillagerEntityMixin {

    @Inject(method = "afterUsing", at = @At("HEAD"))
    private void warden$trackVillagerTradingXp(TradeOffer offer, CallbackInfo ci) {
        WardenMod.XP_SOURCE.set("villager_trading");
        WardenMod.XP_CONTEXT.set("");
    }

    @Inject(method = "afterUsing", at = @At("TAIL"))
    private void warden$clearVillagerTradingXp(TradeOffer offer, CallbackInfo ci) {
        WardenMod.XP_SOURCE.set("unknown");
        WardenMod.XP_CONTEXT.set("");
    }
}
