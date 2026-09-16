package com.warden.mixin.vanish;

import com.warden.vanish.Vanish;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.ViewerCountManager;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// chest lids, shulker animations and the open/close sounds all hang off the viewer count
@Mixin(ViewerCountManager.class)
public abstract class ViewerCountManagerMixin {

    @Inject(method = "openContainer", at = @At("HEAD"), cancellable = true)
    private void warden$silentOpen(LivingEntity entity, World world, BlockPos pos, BlockState state, double distance, CallbackInfo ci) {
        if (Vanish.isVanished(entity)) {
            ci.cancel();
        }
    }

    @Inject(method = "closeContainer", at = @At("HEAD"), cancellable = true)
    private void warden$silentClose(LivingEntity entity, World world, BlockPos pos, BlockState state, CallbackInfo ci) {
        if (Vanish.isVanished(entity)) {
            ci.cancel();
        }
    }
}
