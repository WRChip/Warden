package com.warden.mixin;

import com.warden.WardenMod;
import net.minecraft.block.EndPortalBlock;
import net.minecraft.entity.Entity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.TeleportTarget;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// vanilla builds the obsidian spawn platform in createTeleportTarget, so cancel at HEAD
@Mixin(EndPortalBlock.class)
public abstract class EndPortalBlockMixin {

    @Inject(method = "createTeleportTarget", at = @At("HEAD"), cancellable = true)
    private void warden$blockDimension(ServerWorld world, Entity entity, BlockPos pos, CallbackInfoReturnable<TeleportTarget> cir) {
        RegistryKey<World> dest = world.getRegistryKey() == World.END ? World.OVERWORLD : World.END;
        if (WardenMod.isDimensionBlocked(entity, dest)) {
            cir.setReturnValue(null);
        }
    }
}
