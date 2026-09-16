package com.warden.mixin;

import com.warden.WardenMod;
import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FishingBobberEntity.class)
public abstract class FishingBobberEntityMixin {

    @Inject(method = "use", at = @At("HEAD"))
    private void warden$trackFishingXp(ItemStack rod, CallbackInfoReturnable<Integer> cir) {
        WardenMod.XP_SOURCE.set("fishing");
        WardenMod.XP_CONTEXT.set("");
    }

    @Inject(method = "use", at = @At("RETURN"))
    private void warden$clearFishingXp(ItemStack rod, CallbackInfoReturnable<Integer> cir) {
        WardenMod.XP_SOURCE.set("unknown");
        WardenMod.XP_CONTEXT.set("");
    }
}
