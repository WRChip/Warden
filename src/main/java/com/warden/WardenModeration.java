package com.warden;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class WardenModeration {

    private record FreezePoint(ServerWorld world, double x, double y, double z) {}

    private static final Set<UUID> FROZEN = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, FreezePoint> FROZEN_POS = new ConcurrentHashMap<>();
    private static final Set<UUID> MUTED = ConcurrentHashMap.newKeySet();

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(WardenModeration::tick);
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            if (!isMuted(sender.getUuid())) {
                return true;
            }
            sender.sendMessage(Text.literal("[WARDEN] You are muted and cannot send chat messages.").formatted(Formatting.RED));
            return false;
        });
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, entity) ->
                !(player instanceof ServerPlayerEntity sp && isFrozen(sp.getUuid())));
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) ->
                player instanceof ServerPlayerEntity sp && isFrozen(sp.getUuid()) ? ActionResult.FAIL : ActionResult.PASS);
        UseItemCallback.EVENT.register((player, world, hand) ->
                player instanceof ServerPlayerEntity sp && isFrozen(sp.getUuid()) ? ActionResult.FAIL : ActionResult.PASS);
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) ->
                player instanceof ServerPlayerEntity sp && isFrozen(sp.getUuid()) ? ActionResult.FAIL : ActionResult.PASS);
    }

    private static void tick(MinecraftServer server) {
        if (FROZEN.isEmpty()) {
            return;
        }
        for (UUID id : FROZEN) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(id);
            FreezePoint point = FROZEN_POS.get(id);
            if (player == null || point == null) {
                continue;
            }
            if (player.getEntityWorld() != point.world()) {
                player.teleport(point.world(), point.x(), point.y(), point.z(), Set.of(), player.getYaw(), player.getPitch(), false);
                continue;
            }
            if (player.getX() != point.x() || player.getY() != point.y() || player.getZ() != point.z()) {
                player.setPosition(point.x(), point.y(), point.z());
            }
            player.setVelocity(0, 0, 0);
        }
    }

    static boolean isFrozen(UUID id) {
        return FROZEN.contains(id);
    }

    static boolean isMuted(UUID id) {
        return MUTED.contains(id);
    }

    static boolean toggleFreeze(ServerPlayerEntity target) {
        UUID id = target.getUuid();
        if (FROZEN.remove(id)) {
            FROZEN_POS.remove(id);
            return false;
        }
        FROZEN.add(id);
        FROZEN_POS.put(id, new FreezePoint((ServerWorld) target.getEntityWorld(), target.getX(), target.getY(), target.getZ()));
        return true;
    }

    static boolean toggleMute(ServerPlayerEntity target) {
        UUID id = target.getUuid();
        if (MUTED.remove(id)) {
            return false;
        }
        MUTED.add(id);
        return true;
    }

    static void openInventoryView(ServerPlayerEntity moderator, ServerPlayerEntity target) {
        Inventory view = new PlayerMainInventoryView(target);
        moderator.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, inv, p) -> new GenericContainerScreenHandler(ScreenHandlerType.GENERIC_9X4, syncId, inv, view, 4),
                Text.literal(target.getName().getString() + "'s Inventory")));
    }

    static void openEnderChestView(ServerPlayerEntity moderator, ServerPlayerEntity target) {
        moderator.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, inv, p) -> GenericContainerScreenHandler.createGeneric9x3(syncId, inv, target.getEnderChestInventory()),
                Text.literal(target.getName().getString() + "'s Ender Chest")));
    }

    // mirrors the target's real main inventory (36 slots) so edits apply live instead of to a snapshot
    private static final class PlayerMainInventoryView implements Inventory {
        private final ServerPlayerEntity target;

        private PlayerMainInventoryView(ServerPlayerEntity target) {
            this.target = target;
        }

        @Override
        public int size() {
            return 36;
        }

        @Override
        public boolean isEmpty() {
            for (int i = 0; i < size(); i++) {
                if (!target.getInventory().getStack(i).isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public ItemStack getStack(int slot) {
            return target.getInventory().getStack(slot);
        }

        @Override
        public ItemStack removeStack(int slot, int amount) {
            ItemStack result = target.getInventory().removeStack(slot, amount);
            markDirty();
            return result;
        }

        @Override
        public ItemStack removeStack(int slot) {
            ItemStack result = target.getInventory().removeStack(slot);
            markDirty();
            return result;
        }

        @Override
        public void setStack(int slot, ItemStack stack) {
            target.getInventory().setStack(slot, stack);
            markDirty();
        }

        @Override
        public void markDirty() {
            target.getInventory().markDirty();
            target.currentScreenHandler.sendContentUpdates();
        }

        @Override
        public boolean canPlayerUse(PlayerEntity player) {
            return target.isAlive();
        }

        @Override
        public void clear() {
            for (int i = 0; i < size(); i++) {
                setStack(i, ItemStack.EMPTY);
            }
        }
    }
}
