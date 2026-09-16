package com.warden.mixin;

import com.warden.WardenMod;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A dropped item's full stack goes out in its entity data, so a single oversized item on the
 * ground bans the chunk for anyone who renders it. Check once on the entity's first tick.
 */
@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {

    @Unique
    private boolean warden$sizeChecked;

    @Inject(method = "tick", at = @At("HEAD"))
    private void warden$discardOversized(CallbackInfo ci) {
        if (warden$sizeChecked) {
            return;
        }
        warden$sizeChecked = true;
        ItemEntity self = (ItemEntity) (Object) this;
        if (self.getEntityWorld().isClient()) {
            return;
        }
        ItemStack stack = self.getStack();
        if (WardenMod.isOversized(stack)) {
            WardenMod.logOversized("drop:" + self.getBlockPos().asLong(),
                    "discarded dropped " + WardenMod.shortId(Registries.ITEM.getId(stack.getItem()).toString())
                            + " at " + self.getBlockPos().toShortString() + " (" + (WardenMod.itemBytes(stack) / 1024) + " KB)");
            self.discard();
        }
    }
}
