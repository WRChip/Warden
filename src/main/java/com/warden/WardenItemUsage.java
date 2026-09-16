package com.warden;

import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;

public class WardenItemUsage {
    public static void register() {
        UseItemCallback.EVENT.register((player, world, hand) -> check(player, hand));
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> check(player, hand));
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> check(player, hand));
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> check(player, Hand.MAIN_HAND));
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> check(player, Hand.MAIN_HAND));
        // Recheck at completion in case the held item or rules changed while mining.
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, entity) ->
                check(player, Hand.MAIN_HAND) != ActionResult.FAIL);
    }

    static ActionResult check(PlayerEntity player, Hand hand) {
        if (!(player instanceof ServerPlayerEntity) || player.isSpectator()
                || !WardenMod.CONFIG.itemUsageEnabled || WardenMod.CONFIG.blockedItemUsage.isEmpty()
                || WardenMod.isExempt(player)) {
            return ActionResult.PASS;
        }
        var stack = player.getStackInHand(hand);
        return !stack.isEmpty() && WardenMod.CONFIG.blockedItemUsage.contains(Registries.ITEM.getId(stack.getItem()).toString())
                ? ActionResult.FAIL : ActionResult.PASS;
    }
}
