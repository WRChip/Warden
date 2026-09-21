package com.warden;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import com.mojang.serialization.Codec;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.entity.ContainerUser;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.StackWithSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.RegistryOps;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.slot.Slot;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerConfigEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Util;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class WardenModeration {

    private record FreezePoint(ServerWorld world, double x, double y, double z) {}

    private static final int FIRST_DEAD_SLOT = 41;
    private static final int[] MAIN_VIEW = mainViewSlots();
    private static final int[] ENDER_VIEW = enderViewSlots();

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

    static boolean openInventoryView(MinecraftServer server, ServerPlayerEntity moderator, PlayerConfigEntry profile) {
        PlayerData data = PlayerData.create(server, profile);
        if (data == null) {
            return false;
        }
        Inventory view = new PlayerDataView(data.inventory(), MAIN_VIEW);
        moderator.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, inv, p) -> new MainInventoryScreenHandler(syncId, inv, view),
                viewTitle(server, profile, "Inventory")));
        return true;
    }

    static boolean openEnderChestView(MinecraftServer server, ServerPlayerEntity moderator, PlayerConfigEntry profile) {
        PlayerData data = PlayerData.create(server, profile);
        if (data == null) {
            return false;
        }
        Inventory view = new PlayerDataView(data.enderChest(), ENDER_VIEW);
        moderator.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, inv, p) -> GenericContainerScreenHandler.createGeneric9x3(syncId, inv, view),
                viewTitle(server, profile, "Ender Chest")));
        return true;
    }

    private static Text viewTitle(MinecraftServer server, PlayerConfigEntry profile, String what) {
        boolean offline = server.getPlayerManager().getPlayer(profile.id()) == null;
        return Text.literal(profile.name() + "'s " + what + (offline ? " (offline)" : ""));
    }

    // rows 1-4 are the 36 storage slots, row 5 is head/chest/legs/feet/offhand then four dead slots
    private static int[] mainViewSlots() {
        int[] slots = new int[45];
        for (int i = 0; i < 36; i++) {
            slots[i] = i;
        }
        slots[36] = 39;
        slots[37] = 38;
        slots[38] = 37;
        slots[39] = 36;
        slots[40] = PlayerInventory.OFF_HAND_SLOT;
        Arrays.fill(slots, FIRST_DEAD_SLOT, slots.length, -1);
        return slots;
    }

    private static int[] enderViewSlots() {
        int[] slots = new int[27];
        for (int i = 0; i < slots.length; i++) {
            slots[i] = i;
        }
        return slots;
    }

    // the generic container always lays out a full 9xN grid, so the slots past the offhand map to
    // nothing - swap those for slots that refuse to take or give items
    private static final class MainInventoryScreenHandler extends GenericContainerScreenHandler {
        private MainInventoryScreenHandler(int syncId, PlayerInventory moderatorInventory, Inventory view) {
            super(ScreenHandlerType.GENERIC_9X5, syncId, moderatorInventory, view, 5);
            for (int i = FIRST_DEAD_SLOT; i < MAIN_VIEW.length; i++) {
                Slot old = slots.get(i);
                Slot dead = new Slot(view, i, old.x, old.y) {
                    @Override
                    public boolean canInsert(ItemStack stack) {
                        return false;
                    }

                    @Override
                    public boolean canTakeItems(PlayerEntity player) {
                        return false;
                    }
                };
                dead.id = old.id;
                slots.set(i, dead);
            }
        }
    }

    private interface SlotAccess {
        ItemStack get(int slot);

        void set(int slot, ItemStack stack);

        void flush();
    }

    private static final class PlayerDataView implements Inventory {
        private final SlotAccess access;
        private final int[] slotMap;

        private PlayerDataView(SlotAccess access, int[] slotMap) {
            this.access = access;
            this.slotMap = slotMap;
        }

        @Override
        public int size() {
            return slotMap.length;
        }

        @Override
        public boolean isEmpty() {
            for (int i = 0; i < slotMap.length; i++) {
                if (!getStack(i).isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public ItemStack getStack(int slot) {
            int mapped = slotMap[slot];
            return mapped < 0 ? ItemStack.EMPTY : access.get(mapped);
        }

        @Override
        public ItemStack removeStack(int slot, int amount) {
            ItemStack stack = getStack(slot);
            if (stack.isEmpty()) {
                return ItemStack.EMPTY;
            }
            ItemStack taken = stack.split(amount);
            setStack(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
            return taken;
        }

        @Override
        public ItemStack removeStack(int slot) {
            ItemStack stack = getStack(slot);
            setStack(slot, ItemStack.EMPTY);
            return stack;
        }

        @Override
        public void setStack(int slot, ItemStack stack) {
            int mapped = slotMap[slot];
            if (mapped >= 0) {
                access.set(mapped, stack);
            }
        }

        @Override
        public void markDirty() {
            access.flush();
        }

        @Override
        public void onClose(ContainerUser user) {
            access.flush();
        }

        @Override
        public boolean canPlayerUse(PlayerEntity player) {
            return true;
        }

        @Override
        public void clear() {
            for (int i = 0; i < slotMap.length; i++) {
                setStack(i, ItemStack.EMPTY);
            }
            markDirty();
        }
    }

    /**
     * Looks the target up by uuid on every access rather than holding a ServerPlayerEntity: that
     * object is thrown away on respawn and on relog, and edits against the stale one silently
     * vanish. While the target is offline the saved playerdata is read and written in its place.
     */
    private static final class PlayerData {
        private static final int INVENTORY_SLOTS = 43;
        private static final int ENDER_SLOTS = 27;
        private static final Codec<List<StackWithSlot>> STACK_LIST = StackWithSlot.CODEC.listOf();

        private final MinecraftServer server;
        private final PlayerConfigEntry profile;
        private NbtCompound saved;
        private ItemStack[] inventory;
        private ItemStack[] enderChest;

        private PlayerData(MinecraftServer server, PlayerConfigEntry profile) {
            this.server = server;
            this.profile = profile;
        }

        static PlayerData create(MinecraftServer server, PlayerConfigEntry profile) {
            PlayerData data = new PlayerData(server, profile);
            return data.target() == null && data.saved == null ? null : data;
        }

        SlotAccess inventory() {
            return new SlotAccess() {
                @Override
                public ItemStack get(int slot) {
                    ServerPlayerEntity player = target();
                    return player != null ? player.getInventory().getStack(slot) : inventory[slot];
                }

                @Override
                public void set(int slot, ItemStack stack) {
                    ServerPlayerEntity player = target();
                    if (player != null) {
                        player.getInventory().setStack(slot, stack);
                    } else {
                        inventory[slot] = stack;
                    }
                }

                @Override
                public void flush() {
                    PlayerData.this.flush();
                }
            };
        }

        SlotAccess enderChest() {
            return new SlotAccess() {
                @Override
                public ItemStack get(int slot) {
                    ServerPlayerEntity player = target();
                    return player != null ? player.getEnderChestInventory().getStack(slot) : enderChest[slot];
                }

                @Override
                public void set(int slot, ItemStack stack) {
                    ServerPlayerEntity player = target();
                    if (player != null) {
                        player.getEnderChestInventory().setStack(slot, stack);
                    } else {
                        enderChest[slot] = stack;
                    }
                }

                @Override
                public void flush() {
                    PlayerData.this.flush();
                }
            };
        }

        // resolves the live target, loading or dropping the offline snapshot as they log out and in
        private ServerPlayerEntity target() {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(profile.id());
            if (player != null) {
                saved = null;
                inventory = null;
                enderChest = null;
            } else if (inventory == null) {
                load();
            }
            return player;
        }

        private void load() {
            inventory = new ItemStack[INVENTORY_SLOTS];
            enderChest = new ItemStack[ENDER_SLOTS];
            Arrays.fill(inventory, ItemStack.EMPTY);
            Arrays.fill(enderChest, ItemStack.EMPTY);
            saved = server.getPlayerManager().loadPlayerData(profile).orElse(null);
            if (saved == null) {
                return;
            }
            RegistryOps<NbtElement> ops = server.getRegistryManager().getOps(NbtOps.INSTANCE);
            unpack(saved, "Inventory", ops, inventory);
            unpack(saved, "EnderItems", ops, enderChest);
        }

        private void flush() {
            ServerPlayerEntity player = target();
            if (player != null) {
                player.getInventory().markDirty();
                player.currentScreenHandler.sendContentUpdates();
                return;
            }
            if (saved == null) {
                return;
            }
            RegistryOps<NbtElement> ops = server.getRegistryManager().getOps(NbtOps.INSTANCE);
            saved.put("Inventory", STACK_LIST, ops, pack(inventory));
            saved.put("EnderItems", STACK_LIST, ops, pack(enderChest));
            NbtHelper.putDataVersion(saved);
            write();
        }

        // same temp-file dance as PlayerSaveHandler, so a half-written file can't eat an inventory
        private void write() {
            Path dir = server.getSavePath(WorldSavePath.PLAYERDATA);
            try {
                Path temp = Files.createTempFile(dir, profile.id() + "-", ".dat");
                NbtIo.writeCompressed(saved, temp);
                Util.backupAndReplace(dir.resolve(profile.id() + ".dat"), temp, dir.resolve(profile.id() + ".dat_old"));
            } catch (IOException e) {
                WardenMod.LOGGER.error("[Warden] failed to write playerdata for {}", profile.name(), e);
            }
        }

        private static void unpack(NbtCompound root, String key, RegistryOps<NbtElement> ops, ItemStack[] out) {
            for (StackWithSlot entry : root.get(key, STACK_LIST, ops).orElse(List.of())) {
                if (entry.isValidSlot(out.length)) {
                    out[entry.slot()] = entry.stack();
                }
            }
        }

        private static List<StackWithSlot> pack(ItemStack[] stacks) {
            List<StackWithSlot> packed = new ArrayList<>();
            for (int i = 0; i < stacks.length; i++) {
                if (!stacks[i].isEmpty()) {
                    packed.add(new StackWithSlot(i, stacks[i]));
                }
            }
            return packed;
        }
    }
}
