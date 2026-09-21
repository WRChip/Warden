package com.warden.mixin;

import com.warden.WardenGlobalLimits;
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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
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
        if (WardenMod.isExempt(player)) {
            return;
        }

        ItemStack result = ((Slot) (Object) this).getStack();
        if (result.isEmpty()) {
            return;
        }

        String itemId = Registries.ITEM.getId(result.getItem()).toString();

        if (WardenGlobalLimits.wouldExceed(itemId, result.getCount())) {
            if (player instanceof net.minecraft.server.network.ServerPlayerEntity serverPlayer) {
                WardenMod.sendNotice(serverPlayer, WardenMod.NoticeCategory.ITEM,
                        "can't craft " + WardenMod.shortId(itemId) + " - server is at the limit ("
                                + WardenMod.CONFIG.globalItemLimits.get(itemId) + ")");
            }
            cir.setReturnValue(false);
            return;
        }

        if (!WardenMod.CONFIG.itemLimitsEnabled) {
            return;
        }
        Integer limit = WardenMod.CONFIG.itemLimits.get(itemId);
        if (limit == null) {
            return;
        }

        PlayerInventory inv = player.getInventory();
        int currentCount = WardenMod.countItemsInInventory(inv, itemId);

        if (currentCount + result.getCount() > limit) {
            if (player instanceof net.minecraft.server.network.ServerPlayerEntity serverPlayer) {
                WardenMod.sendNotice(serverPlayer, WardenMod.NoticeCategory.ITEM,
                        "can't craft " + WardenMod.shortId(itemId) + " - at limit (" + limit + ")");
            }
            cir.setReturnValue(false);
        }
    }

    // the census only refreshes on the sweep tick; book the output in now so a burst of
    // crafting between sweeps can't overshoot the cap
    @Inject(method = "onTakeItem", at = @At("HEAD"))
    private void warden$recordCraftedOutput(PlayerEntity player, ItemStack stack, CallbackInfo ci) {
        Slot self = (Slot) (Object) this;
        if (!(self.inventory instanceof CraftingResultInventory) && !(self instanceof FurnaceOutputSlot)) {
            return;
        }
        if (stack.isEmpty() || WardenMod.isExempt(player)) {
            return;
        }
        WardenGlobalLimits.record(Registries.ITEM.getId(stack.getItem()).toString(), stack.getCount());
    }
}
