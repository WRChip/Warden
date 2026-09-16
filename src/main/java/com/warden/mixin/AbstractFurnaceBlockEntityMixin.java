package com.warden.mixin;

import com.warden.WardenMod;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractFurnaceBlockEntity.class)
public abstract class AbstractFurnaceBlockEntityMixin {

    // taking output from the furnace screen is the only path that knows the player
    @Inject(method = "dropExperienceForRecipesUsed", at = @At("HEAD"))
    private void warden$trackFurnaceUser(ServerPlayerEntity player, CallbackInfo ci) {
        WardenMod.XP_PLAYER.set(player);
    }

    @Inject(method = "dropExperienceForRecipesUsed", at = @At("RETURN"))
    private void warden$clearFurnaceUser(ServerPlayerEntity player, CallbackInfo ci) {
        WardenMod.XP_PLAYER.set(null);
    }

    @Inject(method = "dropExperience", at = @At("HEAD"))
    private static void warden$trackFurnaceXp(ServerWorld world, Vec3d pos, int multiplier, float experience, CallbackInfo ci) {
        WardenMod.XP_SOURCE.set("furnace");
        WardenMod.XP_CONTEXT.set("");
    }

    @Inject(method = "dropExperience", at = @At("TAIL"))
    private static void warden$clearFurnaceXp(ServerWorld world, Vec3d pos, int multiplier, float experience, CallbackInfo ci) {
        WardenMod.XP_SOURCE.set("unknown");
        WardenMod.XP_CONTEXT.set("");
    }
}
