package com.warden.mixin;

import com.warden.WardenMod;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.inventory.CraftingResultInventory;
import net.minecraft.screen.slot.FurnaceOutputSlot;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Slot.class)
public abstract class CraftingResultSlotMixin {

    @Inject(method = "canTakeItems", at = @At("HEAD"), cancellable = true)
    private void warden$blockOverLimitCraft(PlayerEntity player, CallbackInfoReturnable<Boolean> cir) {
        // crafting table, smithing, anvil, stonecutter, loom and cartography all write their output
        // into a CraftingResultInventory; furnaces use their own slot type
        Slot self = (Slot) (Object) this;
        if (!(self.inventory instanceof CraftingResultInventory) && !(self instanceof FurnaceOutputSlot)) {
            return;
        }
        if (!WardenMod.CONFIG.itemLimitsEnabled || WardenMod.isExempt(player)) {
            return;
        }

        ItemStack result = ((Slot) (Object) this).getStack();
        if (result.isEmpty()) {
            return;
        }

        String itemId = Registries.ITEM.getId(result.getItem()).toString();
        Integer limit = WardenMod.CONFIG.itemLimits.get(itemId);
        if (limit == null) {
            return;
        }

        PlayerInventory inv = player.getInventory();
        int currentCount = WardenMod.countItemsInInventory(inv, itemId);

        if (currentCount + result.getCount() > limit) {
            WardenMod.sendNotice((net.minecraft.server.network.ServerPlayerEntity) player, WardenMod.NoticeCategory.ITEM,
                    "can't craft " + WardenMod.shortId(itemId) + " - at limit (" + limit + ")");
            cir.setReturnValue(false);
        }
    }
}
