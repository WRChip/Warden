package com.warden.mixin;

import com.warden.WardenMod;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.Map;

/**
 * Blocks item pickup at the collection point when the player is already at the item limit.
 * This is the primary/fast-path defence — fires before the item enters the inventory.
 * The tick-based enforcer in WardenMod acts as fallback for other acquisition paths
 * (crafting, chests, /give, etc.).
 */
@Mixin(ItemEntity.class)
public abstract class ItemPickupMixin {

    @Inject(method = "onPlayerCollision", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/entity/player/PlayerInventory;insertStack(Lnet/minecraft/item/ItemStack;)Z"), cancellable = true)
    private void onPlayerCollision(PlayerEntity player, CallbackInfo ci) {
        if (!(player instanceof ServerPlayerEntity serverPlayer)) return;

        ItemEntity itemEntity = (ItemEntity) (Object) this;
        ItemStack stack = itemEntity.getStack();
        if (stack.isEmpty()) return;

        // chunk-ban payloads never enter an inventory, exempt or not; the entity itself is the hazard
        if (WardenMod.isOversized(stack)) {
            WardenMod.logOversized("pickup:" + player.getName().getString(),
                    "discarded oversized " + WardenMod.shortId(Registries.ITEM.getId(stack.getItem()).toString())
                            + " " + player.getName().getString() + " tried to pick up");
            itemEntity.discard();
            ci.cancel();
            return;
        }

        if (!WardenMod.CONFIG.itemLimitsEnabled || WardenMod.CONFIG.itemLimits.isEmpty()) return;
        if (WardenMod.isExempt(player)) return;

        // counts nested contents too, so a shulker/bundle full of banned items can't be picked up
        Map<String, Integer> picked = new HashMap<>();
        WardenMod.countItemsRecursive(stack, picked);
        if (picked.isEmpty()) return;

        PlayerInventory inv = player.getInventory();
        Map<String, Integer> held = null;
        for (Map.Entry<String, Integer> e : picked.entrySet()) {
            String id = e.getKey();
            Integer limit = WardenMod.CONFIG.itemLimits.get(id);
            if (limit == null) continue;
            if (e.getValue() <= limit && held == null) {
                held = new HashMap<>();
                for (int i = 0; i < inv.size(); i++) WardenMod.countItemsRecursive(inv.getStack(i), held);
            }
            if (e.getValue() > limit || held.getOrDefault(id, 0) + e.getValue() > limit) {
                WardenMod.sendPickupBlockedNotice(serverPlayer, id, limit);
                ci.cancel();
                return;
            }
        }
    }
}
