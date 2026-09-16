package com.warden.mixin;

import com.warden.WardenMod;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Block.class)
public abstract class BlockMixin {

    // dropExperience doesn't know who mined the block; afterBreak does
    @Inject(method = "afterBreak", at = @At("HEAD"))
    private void warden$trackMiner(World world, PlayerEntity player, BlockPos pos, BlockState state, BlockEntity blockEntity, ItemStack tool, CallbackInfo ci) {
        WardenMod.XP_PLAYER.set(player instanceof ServerPlayerEntity serverPlayer ? serverPlayer : null);
    }

    @Inject(method = "afterBreak", at = @At("RETURN"))
    private void warden$clearMiner(World world, PlayerEntity player, BlockPos pos, BlockState state, BlockEntity blockEntity, ItemStack tool, CallbackInfo ci) {
        WardenMod.XP_PLAYER.set(null);
    }

    // the amount itself is capped in ExperienceOrbEntityMixin when the orbs are spawned
    @Inject(method = "dropExperience", at = @At("HEAD"))
    private void warden$trackBlockMiningXp(ServerWorld world, BlockPos pos, int size, CallbackInfo ci) {
        WardenMod.XP_SOURCE.set("blocksMining");
        WardenMod.XP_CONTEXT.set(Registries.BLOCK.getId((Block)(Object)this).toString());
    }

    @Inject(method = "dropExperience", at = @At("TAIL"))
    private void warden$clearBlockMiningXp(ServerWorld world, BlockPos pos, int size, CallbackInfo ci) {
        WardenMod.XP_SOURCE.set("unknown");
        WardenMod.XP_CONTEXT.set("");
    }
}
