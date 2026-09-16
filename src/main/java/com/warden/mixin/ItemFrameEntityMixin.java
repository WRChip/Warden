package com.warden.mixin;

import com.warden.WardenMod;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** An item frame's held stack is tracked entity data, same hazard as a dropped item. Refuse it. */
@Mixin(ItemFrameEntity.class)
public abstract class ItemFrameEntityMixin {

    @Inject(method = "setHeldItemStack(Lnet/minecraft/item/ItemStack;Z)V", at = @At("HEAD"), cancellable = true)
    private void warden$refuseOversized(ItemStack stack, boolean update, CallbackInfo ci) {
        ItemFrameEntity self = (ItemFrameEntity) (Object) this;
        if (self.getEntityWorld().isClient() || !WardenMod.isOversized(stack)) {
            return;
        }
        WardenMod.logOversized("frame:" + self.getBlockPos().asLong(),
                "refused oversized " + WardenMod.shortId(Registries.ITEM.getId(stack.getItem()).toString())
                        + " in an item frame at " + self.getBlockPos().toShortString());
        ci.cancel();
    }
}
