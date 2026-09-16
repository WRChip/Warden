package com.warden.mixin;

import com.warden.WardenMod;
import net.minecraft.block.BlockState;
import net.minecraft.block.FluidBlock;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * tryDrainFluid is where a bucket (or a dispenser) picks up a water/lava source block; it's the
 * single call BucketItem#use goes through, and it removes the block before this method even
 * returns. Rate-limiting here, rather than after the fact, is what actually stops the drain
 * instead of just reacting to it — refusing the call leaves the source block in place.
 *
 * Dispenser-driven draining isn't touched: there's no player to rate-limit or notify, and vanilla
 * dispensers draining a moat isn't the scenario the rule is aimed at.
 */
@Mixin(FluidBlock.class)
public abstract class FluidBlockMixin {

    @Inject(method = "tryDrainFluid", at = @At("HEAD"), cancellable = true)
    private void warden$rateLimitDrain(LivingEntity entity, WorldAccess world, BlockPos pos, BlockState state,
                                        CallbackInfoReturnable<ItemStack> cir) {
        if (!(entity instanceof ServerPlayerEntity player)) {
            return;
        }
        if (!WardenMod.CONFIG.bucketDrainEnabled || WardenMod.isExempt(player)) {
            return;
        }
        if (WardenMod.registerBucketDrain(player)) {
            WardenMod.sendNotice(player, WardenMod.NoticeCategory.BUCKET, "bucket draining too fast - slow down");
            WardenMod.LOGGER.warn("[Warden] {} tripped the bucket-drain limit at {}", player.getName().getString(), pos.toShortString());
            cir.setReturnValue(ItemStack.EMPTY);
        }
    }
}
