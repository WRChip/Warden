package com.warden;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.warden.config.WardenConfig;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.component.ComponentMap;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttackRangeComponent;
import net.minecraft.component.type.AttributeModifierSlot;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.component.type.BundleContentsComponent;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.item.Item;
import net.minecraft.inventory.Inventory;
import io.netty.buffer.Unpooled;
import net.minecraft.item.ItemStack;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class WardenMod implements ModInitializer {

    private static final double PLAYER_BASE_ATTACK_DAMAGE = 1.0;
    private static final double PLAYER_BASE_ATTACK_SPEED = 4.0;

    private static final ThreadLocal<Boolean> ENFORCING_WEAPON_COMPONENTS = ThreadLocal.withInitial(() -> false);
    public static final ThreadLocal<String> XP_SOURCE = ThreadLocal.withInitial(() -> "unknown");
    public static final ThreadLocal<String> XP_CONTEXT = ThreadLocal.withInitial(() -> "");
    public static final ThreadLocal<String> XP_SOURCE_OVERRIDE = new ThreadLocal<>();
    public static final ThreadLocal<String> XP_CONTEXT_OVERRIDE = new ThreadLocal<>();
    // player responsible for an xp drop, when the drop site knows it; used for exemption and the notice
    public static final ThreadLocal<ServerPlayerEntity> XP_PLAYER = new ThreadLocal<>();

    public enum NoticeCategory {
        ITEM,
        WEAPON,
        ENCHANTMENT,
        EFFECT,
        XP,
        DIMENSION
    }

    public static final Logger LOGGER = LoggerFactory.getLogger("Warden");
    public static WardenConfig CONFIG;
    private static final Map<String, WardenConfig.WeaponLimitConfig> CLIENT_SYNCED_WEAPON_LIMITS = new ConcurrentHashMap<>();
    private static volatile boolean CLIENT_SYNCED_WEAPON_LIMITS_VALID;
    private static volatile boolean CLIENT_SYNCED_WEAPON_LIMITS_ENABLED = true;
    private static final Map<UUID, Integer> LAST_PICKUP_NOTICE = new HashMap<>();
    private record LastNotice(String message, int tick) {}
    // set once the server has its registries; item encoding needs them
    public static volatile DynamicRegistryManager REGISTRIES;
    private static final Map<String, Long> LAST_OVERSIZE_LOG = new ConcurrentHashMap<>();
    private static final Map<UUID, LastNotice> LAST_NOTICE = new HashMap<>();

    @Override
    public void onInitialize() {
        CONFIG = WardenConfig.load();
        LOGGER.info("[Warden] Loaded. Explosion: {}, Items: {}, Weapons: {}, Enchantments: {}, Effects: {}",
                CONFIG.explosionLimitsEnabled, CONFIG.itemLimitsEnabled, CONFIG.weaponLimitsEnabled,
                CONFIG.enchantmentLimitsEnabled, CONFIG.effectLimitsEnabled);

        WardenNetworking.register();
        registerTick();
        WardenCommand.register();
        // dev aid: a "warden-audit" file in the run dir force-loads mixin targets that only load
        // when a player joins, so a broken injection shows in the log without needing a client
        if (java.nio.file.Files.exists(java.nio.file.Path.of("warden-audit"))
                && net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment()) {
            LOGGER.info("[Warden] audit: force-loading mixin targets");
            for (String target : new String[] {
                    "net.minecraft.network.packet.s2c.play.ChunkData",
                    "net.minecraft.network.packet.s2c.play.ChunkData$BlockEntityData"}) {
                try {
                    Class.forName(target);
                } catch (ClassNotFoundException e) {
                    LOGGER.error("[Warden] audit: {} not found", target);
                }
            }
        }
        WardenNewWorldWatcher.register();
        WardenRestore.register();
        WardenModeration.register();
        WardenItemUsage.register();
    }

    private void registerTick() {
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            LAST_PICKUP_NOTICE.remove(handler.player.getUuid());
            LAST_NOTICE.remove(handler.player.getUuid());
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            LAST_PICKUP_NOTICE.clear();
            LAST_NOTICE.clear();
            LAST_OVERSIZE_LOG.clear();
        });
        ServerTickEvents.END_SERVER_TICK.register((MinecraftServer server) -> {
            if (server.getTicks() % Math.max(1, CONFIG.checkIntervalTicks) != 0) {
                return;
            }
            WardenGlobalLimits.sweep(server);
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                enforceChunkBan(player);
                enforceItemLimits(player);
                enforceWeaponLimits(player);
                enforceEnchantmentLimits(player);
                enforceEffectLimits(player);
            }
        });
    }

    public static boolean isExempt(PlayerEntity player) {
        if (CONFIG.exemptCreative && player.isCreative()) {
            return true;
        }
        return !CONFIG.exemptPlayers.isEmpty() && (CONFIG.exemptPlayers.contains(player.getName().getString())
                || CONFIG.exemptPlayers.contains(player.getUuidAsString()));
    }

    public static String shortId(String id) {
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    static MutableText wardenPrefix() {
        return Text.literal("[")
                .append(Text.literal("WARDEN").formatted(Formatting.DARK_PURPLE, Formatting.BOLD))
                .append(Text.literal("] "));
    }

    public static void sendNotice(ServerPlayerEntity player, NoticeCategory category, String message) {
        if (CONFIG == null || player == null) {
            return;
        }
        boolean enabledGlobally = switch (category) {
            case ITEM -> CONFIG.itemActionBarEnabled;
            case WEAPON -> CONFIG.weaponActionBarEnabled;
            case ENCHANTMENT -> CONFIG.enchantmentActionBarEnabled;
            case EFFECT -> CONFIG.effectActionBarEnabled;
            case XP -> CONFIG.xpActionBarEnabled;
            case DIMENSION -> true;
        };
        if (!enabledGlobally) {
            return;
        }
        if (!isActionBarEnabledFor(player, category)) {
            return;
        }
        // beacons and auras re-apply every few seconds; don't repeat the same line
        LastNotice last = LAST_NOTICE.get(player.getUuid());
        int now = player.getEntityWorld().getServer().getTicks();
        if (last != null && last.message.equals(message) && now - last.tick < 100) {
            return;
        }
        LAST_NOTICE.put(player.getUuid(), new LastNotice(message, now));
        player.sendMessage(wardenPrefix().append(Text.literal(message).formatted(Formatting.RED)), true);
    }

    public static boolean isActionBarEnabledFor(PlayerEntity player, NoticeCategory category) {
        java.util.Set<String> disabled = CONFIG.playerActionBarDisabled.get(player.getUuidAsString());
        if (disabled == null) {
            disabled = CONFIG.playerActionBarDisabled.get(player.getName().getString());
        }
        return disabled == null || !disabled.contains(noticeCategoryKey(category));
    }

    public static String noticeCategoryKey(NoticeCategory category) {
        return switch (category) {
            case ITEM -> "item";
            case WEAPON -> "weapon";
            case ENCHANTMENT -> "enchantment";
            case EFFECT -> "effect";
            case XP -> "xp";
            case DIMENSION -> "dimension";
        };
    }

    // called from the portal / teleport hooks before the target world does any work
    public static boolean isDimensionBlocked(Entity entity, RegistryKey<World> dest) {
        if (!CONFIG.dimensionLimitsEnabled || !CONFIG.blockedDimensionKeys.contains(dest)) {
            return false;
        }
        if (entity instanceof ServerPlayerEntity player) {
            if (isExempt(player)) {
                return false;
            }
            sendNotice(player, NoticeCategory.DIMENSION, dimensionName(dest) + " is closed on this server");
        }
        return true;
    }

    public static String dimensionName(RegistryKey<World> key) {
        if (key == World.NETHER) return "The Nether";
        if (key == World.END) return "The End";
        if (key == World.OVERWORLD) return "The Overworld";
        return key.getValue().toString();
    }

    public static boolean shouldSkipDurationCap(StatusEffectInstance instance) {
        return instance.isAmbient();
    }

    public static int countItemsInInventory(Inventory inv, String itemId) {
        int total = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            total += countItemRecursive(stack, itemId);
        }
        return total;
    }

    public static int countItemRecursive(ItemStack stack, String itemId) {
        if (stack.isEmpty()) return 0;
        int count = 0;
        if (Registries.ITEM.getId(stack.getItem()).toString().equals(itemId)) {
            count += stack.getCount();
        }

        // Bundle
        BundleContentsComponent bundle = stack.get(DataComponentTypes.BUNDLE_CONTENTS);
        if (bundle != null) {
            for (ItemStack inner : bundle.iterate()) {
                count += countItemRecursive(inner, itemId);
            }
        }

        // Container
        ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
        if (container != null) {
            for (ItemStack inner : container.iterateNonEmpty()) {
                count += countItemRecursive(inner, itemId);
            }
        }
        return count;
    }

    public static void enforceItemLimits(ServerPlayerEntity player) {
        if (!CONFIG.itemLimitsEnabled || CONFIG.itemLimits.isEmpty() || isExempt(player)) {
            return;
        }

        PlayerInventory inv = player.getInventory();
        Map<String, Integer> counts = new HashMap<>();

        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) {
                continue;
            }
            countItemsRecursive(stack, counts);
        }

        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            String itemId = entry.getKey();
            int total = entry.getValue();
            Integer limit = CONFIG.itemLimits.get(itemId);
            if (limit == null || total <= limit) {
                continue;
            }

            int excess = total - limit;
            // First pass: remove from main inventory
            for (int i = inv.size() - 1; i >= 0 && excess > 0; i--) {
                ItemStack stack = inv.getStack(i);
                if (stack.isEmpty()) {
                    continue;
                }
                final int slot = i;
                excess = removeItemsRecursive(player, stack, itemId, excess, () -> {
                    inv.setStack(slot, ItemStack.EMPTY);
                });
            }

            int removed = total - limit - excess;
            if (removed > 0) {
                sendNotice(player, NoticeCategory.ITEM,
                        "removed " + removed + "x " + shortId(itemId) + " (limit: " + limit + ")");
                LOGGER.debug("[Warden] Removed {} excess {} from {}", removed, itemId, player.getName().getString());
            }
        }

        if (!CONFIG.itemLimits.containsValue(0)) return;

        // banned items (limit 0) are purged anywhere the player can reach them, not just the hotbar/inventory
        purgeBanned(player, player.getEnderChestInventory(), "ender chest");

        // the player's own screen counts too: its cursor and 2x2 grid aren't part of the inventory
        ScreenHandler handler = player.currentScreenHandler;
        if (handler != null) {
            boolean changed = false;
            for (Slot slot : handler.slots) {
                if (slot.inventory == inv) continue;
                ItemStack stack = slot.getStack();
                if (stack.isEmpty()) continue;
                changed |= stripBanned(player, stack, () -> slot.setStack(ItemStack.EMPTY), "container");
            }
            ItemStack cursor = handler.getCursorStack();
            if (!cursor.isEmpty()) {
                changed |= stripBanned(player, cursor, () -> handler.setCursorStack(ItemStack.EMPTY), "cursor");
            }
            if (changed) {
                handler.sendContentUpdates();
            }
        }
    }

    /** Everything a player is carrying: inventory, ender chest and the stack on their cursor. */
    public static int countCarried(ServerPlayerEntity player, String itemId) {
        int total = countItemsInInventory(player.getInventory(), itemId)
                + countItemsInInventory(player.getEnderChestInventory(), itemId);
        ScreenHandler handler = player.currentScreenHandler;
        if (handler != null) {
            total += countItemRecursive(handler.getCursorStack(), itemId);
        }
        return total;
    }

    /** Deletes up to {@code amount} of an item from a loose stack, nested containers included.
     *  No player involved - deletion never drops anything back. Returns how many went. */
    public static int deleteFromStack(ItemStack stack, String itemId, int amount) {
        return amount - removeItemsRecursive(null, stack, itemId, amount, () -> {}, true);
    }

    /** Deletes up to {@code amount} of an item from everything the player is carrying,
     *  outright - a global cap can't be met by dropping the overflow. Returns how many went. */
    public static int deleteCarried(ServerPlayerEntity player, String itemId, int amount) {
        int excess = amount;
        PlayerInventory inv = player.getInventory();
        for (int i = inv.size() - 1; i >= 0 && excess > 0; i--) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            final int slot = i;
            excess = removeItemsRecursive(player, stack, itemId, excess, () -> inv.setStack(slot, ItemStack.EMPTY), true);
        }
        Inventory ender = player.getEnderChestInventory();
        for (int i = ender.size() - 1; i >= 0 && excess > 0; i--) {
            ItemStack stack = ender.getStack(i);
            if (stack.isEmpty()) continue;
            final int slot = i;
            excess = removeItemsRecursive(player, stack, itemId, excess, () -> ender.setStack(slot, ItemStack.EMPTY), true);
        }
        ScreenHandler handler = player.currentScreenHandler;
        if (handler != null && excess > 0 && !handler.getCursorStack().isEmpty()) {
            excess = removeItemsRecursive(player, handler.getCursorStack(), itemId, excess,
                    () -> handler.setCursorStack(ItemStack.EMPTY), true);
            handler.sendContentUpdates();
        }
        return amount - excess;
    }

    // ---------------------------------------------------------------- chunk-ban guard

    /** Network-encoded size of a stack, nested contents included. 0 for plain stacks. */
    public static int itemBytes(ItemStack stack) {
        DynamicRegistryManager registries = REGISTRIES;
        if (registries == null || stack == null || stack.isEmpty() || stack.getComponentChanges().isEmpty()) {
            return 0;
        }
        RegistryByteBuf buf = new RegistryByteBuf(Unpooled.buffer(), registries);
        try {
            ItemStack.OPTIONAL_PACKET_CODEC.encode(buf, stack);
            return buf.readableBytes();
        } catch (RuntimeException | StackOverflowError e) {
            // couldn't even encode it; it would kill the client's decoder too
            return Integer.MAX_VALUE;
        } finally {
            buf.release();
        }
    }

    public static boolean isOversized(ItemStack stack) {
        return CONFIG != null && CONFIG.chunkBanEnabled && itemBytes(stack) > CONFIG.maxItemBytes;
    }

    /** Warn-level log, at most once every 10s per key, so a spammed chunk doesn't flood the console. */
    public static void logOversized(String key, String message) {
        long now = System.currentTimeMillis();
        Long last = LAST_OVERSIZE_LOG.get(key);
        if (last != null && now - last < 10_000) {
            return;
        }
        if (LAST_OVERSIZE_LOG.size() > 1000) {
            LAST_OVERSIZE_LOG.values().removeIf(t -> now - t > 60_000);
        }
        LAST_OVERSIZE_LOG.put(key, now);
        LOGGER.warn("[Warden] chunk-ban guard: {}", message);
    }

    private static String describeOversized(ItemStack stack) {
        return shortId(Registries.ITEM.getId(stack.getItem()).toString()) + " (" + (itemBytes(stack) / 1024) + " KB)";
    }

    public static void enforceChunkBan(ServerPlayerEntity player) {
        if (!CONFIG.chunkBanEnabled || REGISTRIES == null || isExempt(player)) {
            return;
        }
        String name = player.getName().getString();
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (isOversized(stack)) {
                String what = describeOversized(stack);
                inv.setStack(i, ItemStack.EMPTY);
                sendNotice(player, NoticeCategory.ITEM, "removed " + what + " - too much item data");
                logOversized("inv:" + name, "removed " + what + " from " + name + "'s inventory");
            }
        }
        Inventory ender = player.getEnderChestInventory();
        for (int i = 0; i < ender.size(); i++) {
            ItemStack stack = ender.getStack(i);
            if (isOversized(stack)) {
                String what = describeOversized(stack);
                ender.setStack(i, ItemStack.EMPTY);
                sendNotice(player, NoticeCategory.ITEM, "removed " + what + " - too much item data");
                logOversized("ender:" + name, "removed " + what + " from " + name + "'s ender chest");
            }
        }
        ScreenHandler handler = player.currentScreenHandler;
        if (handler != null) {
            boolean changed = purgeOversized(handler, name);
            ItemStack cursor = handler.getCursorStack();
            if (isOversized(cursor)) {
                String what = describeOversized(cursor);
                handler.setCursorStack(ItemStack.EMPTY);
                sendNotice(player, NoticeCategory.ITEM, "removed " + what + " - too much item data");
                changed = true;
            }
            if (changed) {
                handler.sendContentUpdates();
            }
        }
    }

    /** Strips oversized stacks out of every slot of a handler. Also run before the full sync
     *  goes out, so a chest packed with book-stuffed shulkers can't kick whoever opens it. */
    public static boolean purgeOversized(ScreenHandler handler, String who) {
        if (CONFIG == null || !CONFIG.chunkBanEnabled || REGISTRIES == null) {
            return false;
        }
        boolean changed = false;
        for (Slot slot : handler.slots) {
            ItemStack stack = slot.getStack();
            if (isOversized(stack)) {
                String what = describeOversized(stack);
                slot.setStack(ItemStack.EMPTY);
                logOversized("container:" + who, "removed " + what + " from a container opened by " + who);
                changed = true;
            }
        }
        return changed;
    }

    public static void sendPickupBlockedNotice(ServerPlayerEntity player, String itemId, int limit) {
        int now = player.getEntityWorld().getServer().getTicks();
        Integer last = LAST_PICKUP_NOTICE.get(player.getUuid());
        if (last != null && now - last < 40) return;
        LAST_PICKUP_NOTICE.put(player.getUuid(), now);
        sendNotice(player, NoticeCategory.ITEM, limit == 0
                ? shortId(itemId) + " is banned"
                : "can't pick up " + shortId(itemId) + " - at limit (" + limit + ")");
    }

    private static void purgeBanned(ServerPlayerEntity player, Inventory inv, String where) {
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            final int slot = i;
            stripBanned(player, stack, () -> inv.setStack(slot, ItemStack.EMPTY), where);
        }
    }

    private static boolean stripBanned(ServerPlayerEntity player, ItemStack stack, Runnable onStackEmpty, String where) {
        boolean changed = false;
        Map<String, Integer> counts = new HashMap<>();
        countItemsRecursive(stack, counts);
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            Integer limit = CONFIG.itemLimits.get(e.getKey());
            if (limit == null || limit != 0) continue;
            String itemId = e.getKey();
            // Removing a banned outer container can also remove other counted items.
            int count = countItemRecursive(stack, itemId);
            if (count == 0) continue;
            removeItemsRecursive(player, stack, itemId, count, onStackEmpty);
            sendNotice(player, NoticeCategory.ITEM, "removed " + count + "x " + shortId(itemId) + " (banned, " + where + ")");
            LOGGER.debug("[Warden] Removed {} banned {} from {} ({})", count, itemId, player.getName().getString(), where);
            changed = true;
        }
        return changed;
    }

    public static void countItemsRecursive(ItemStack stack, Map<String, Integer> counts) {
        countTracked(stack, counts, CONFIG.itemLimits.keySet());
    }

    public static void countTracked(ItemStack stack, Map<String, Integer> counts, Set<String> tracked) {
        if (stack.isEmpty()) return;

        String id = Registries.ITEM.getId(stack.getItem()).toString();
        if (tracked.contains(id)) {
            counts.merge(id, stack.getCount(), Integer::sum);
        }

        // Check Bundle
        BundleContentsComponent bundle = stack.get(DataComponentTypes.BUNDLE_CONTENTS);
        if (bundle != null) {
            for (ItemStack inner : bundle.iterate()) {
                countTracked(inner, counts, tracked);
            }
        }

        // Check Container (Shulker Box, etc.)
        ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
        if (container != null) {
            for (ItemStack inner : container.iterateNonEmpty()) {
                countTracked(inner, counts, tracked);
            }
        }
    }

    private static int removeItemsRecursive(ServerPlayerEntity player, ItemStack stack, String itemId, int excess, Runnable onStackEmpty) {
        return removeItemsRecursive(player, stack, itemId, excess, onStackEmpty, false);
    }

    private static int removeItemsRecursive(ServerPlayerEntity player, ItemStack stack, String itemId, int excess,
                                            Runnable onStackEmpty, boolean delete) {
        if (stack.isEmpty() || excess <= 0) return excess;

        // Check contents first (deepest first)
        // Bundle
        BundleContentsComponent bundle = stack.get(DataComponentTypes.BUNDLE_CONTENTS);
        if (bundle != null) {
            List<ItemStack> newContents = new ArrayList<>();
            boolean bundleChanged = false;
            for (ItemStack inner : bundle.iterateCopy()) {
                if (excess > 0) {
                    int before = excess;
                    // We don't have a good way to "setStackEmpty" inside bundle easily without rebuilding
                    // removeItemsRecursive returns remaining excess
                    // For bundle, we handle it specially
                    if (Registries.ITEM.getId(inner.getItem()).toString().equals(itemId)) {
                        int drop = Math.min(inner.getCount(), excess);
                        handleOverflow(player, inner, drop, delete);
                        inner.decrement(drop);
                        excess -= drop;
                        bundleChanged = true;
                    }

                    // Recursively check inner containers if any
                    excess = removeItemsRecursive(player, inner, itemId, excess, () -> {
                        // inner is now empty, handled by inner.decrement above or recursive call
                    }, delete);

                    if (before != excess) bundleChanged = true;
                }
                if (!inner.isEmpty()) {
                    newContents.add(inner);
                }
            }
            if (bundleChanged) {
                stack.set(DataComponentTypes.BUNDLE_CONTENTS, new BundleContentsComponent(newContents));
            }
        }

        // Container (Shulker Box)
        ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
        if (container != null) {
            List<ItemStack> stacks = new ArrayList<>(container.stream().toList());
            boolean containerChanged = false;
            for (int i = 0; i < stacks.size(); i++) {
                ItemStack inner = stacks.get(i);
                if (inner.isEmpty()) continue;

                int before = excess;
                final int idx = i;
                excess = removeItemsRecursive(player, inner, itemId, excess, () -> {
                    stacks.set(idx, ItemStack.EMPTY);
                }, delete);

                if (Registries.ITEM.getId(inner.getItem()).toString().equals(itemId) && excess > 0) {
                    int drop = Math.min(inner.getCount(), excess);
                    handleOverflow(player, inner, drop, delete);
                    inner.decrement(drop);
                    if (inner.isEmpty()) {
                        stacks.set(i, ItemStack.EMPTY);
                    }
                    excess -= drop;
                }

                if (before != excess) containerChanged = true;
            }
            if (containerChanged) {
                stack.set(DataComponentTypes.CONTAINER, ContainerComponent.fromStacks(stacks));
            }
        }

        // Finally check the stack itself
        if (excess > 0 && Registries.ITEM.getId(stack.getItem()).toString().equals(itemId)) {
            int drop = Math.min(stack.getCount(), excess);
            handleOverflow(player, stack, drop, delete);
            stack.decrement(drop);
            if (stack.isEmpty()) {
                onStackEmpty.run();
            }
            excess -= drop;
        }

        return excess;
    }

    // player is only needed to drop the overflow; with delete set it may be null
    private static void handleOverflow(ServerPlayerEntity player, ItemStack stack, int amount, boolean delete) {
        Integer limit = CONFIG.itemLimits.get(Registries.ITEM.getId(stack.getItem()).toString());
        if (delete || CONFIG.deleteOverflowItem || (limit != null && limit == 0)) {
            // Already decremented in caller. Banned items are never dropped back into the world.
        } else {
            ItemStack dropped = stack.copyWithCount(amount);
            ItemEntity itemEntity = player.dropItem(dropped, false);
            if (itemEntity != null) {
                itemEntity.setPickupDelay(CONFIG.dropPickupDelay);
            }
        }
    }

    public static void enforceWeaponLimits(ServerPlayerEntity player) {
        if (!CONFIG.weaponLimitsEnabled || isExempt(player)) {
            return;
        }

        boolean changed = false;
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty()) {
                changed |= enforceWeaponComponentsRecursive(stack);
            }
        }

        if (changed) {
            sendNotice(player, NoticeCategory.WEAPON, "weapon stats updated on your items");
        }
    }

    private static boolean enforceWeaponComponentsRecursive(ItemStack stack) {
        if (stack.isEmpty()) return false;
        boolean changed = enforceWeaponComponents(stack);

        // Bundle
        BundleContentsComponent bundle = stack.get(DataComponentTypes.BUNDLE_CONTENTS);
        if (bundle != null && contentsNeedUpdate(bundle.iterate(), true)) {
            List<ItemStack> newContents = new ArrayList<>();
            boolean bundleChanged = false;
            for (ItemStack inner : bundle.iterateCopy()) {
                if (enforceWeaponComponentsRecursive(inner)) {
                    bundleChanged = true;
                }
                newContents.add(inner);
            }
            if (bundleChanged) {
                stack.set(DataComponentTypes.BUNDLE_CONTENTS, new BundleContentsComponent(newContents));
                changed = true;
            }
        }

        // Container
        ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
        if (container != null && contentsNeedUpdate(container.iterateNonEmpty(), true)) {
            List<ItemStack> stacks = container.stream().toList();
            boolean containerChanged = false;
            for (ItemStack inner : stacks) {
                if (enforceWeaponComponentsRecursive(inner)) {
                    containerChanged = true;
                }
            }
            if (containerChanged) {
                stack.set(DataComponentTypes.CONTAINER, ContainerComponent.fromStacks(stacks));
                changed = true;
            }
        }

        return changed;
    }

    public static boolean enforceWeaponComponents(ItemStack stack) {
        return enforceWeaponComponents(stack, true);
    }

    private static boolean enforceWeaponComponents(ItemStack stack, boolean apply) {
        if (ENFORCING_WEAPON_COMPONENTS.get()) return false;
        if (CONFIG == null || !CONFIG.weaponLimitsEnabled || stack == null || stack.isEmpty()) {
            return false;
        }
        WardenConfig.WeaponLimitConfig limit = CONFIG.weaponLimits.isEmpty() ? null
                : CONFIG.weaponLimits.get(Registries.ITEM.getId(stack.getItem()).toString());
        ComponentMap defaults = stack.getItem().getComponents();
        if (limit == null
                && Objects.equals(stack.get(DataComponentTypes.ATTRIBUTE_MODIFIERS), defaults.get(DataComponentTypes.ATTRIBUTE_MODIFIERS))
                && Objects.equals(stack.get(DataComponentTypes.ATTACK_RANGE), defaults.get(DataComponentTypes.ATTACK_RANGE))) {
            return false;
        }
        ENFORCING_WEAPON_COMPONENTS.set(true);
        try {
            boolean changed = false;
            changed |= stripForeignAttackModifiers(stack, apply);
            changed |= applyWeaponAttributeLimit(
                    stack,
                    defaults,
                    EntityAttributes.ATTACK_DAMAGE,
                    Item.BASE_ATTACK_DAMAGE_MODIFIER_ID,
                    toAttributeModifierValue("attackDamage", limit != null ? limit.attackDamage : null), apply
            );
            changed |= applyWeaponAttributeLimit(
                    stack,
                    defaults,
                    EntityAttributes.ATTACK_SPEED,
                    Item.BASE_ATTACK_SPEED_MODIFIER_ID,
                    toAttributeModifierValue("attackSpeed", limit != null ? limit.attackSpeed : null), apply
            );
            changed |= applyWeaponReachLimit(stack, defaults, limit != null ? limit.reach : null, apply);
            return changed;
        } finally {
            ENFORCING_WEAPON_COMPONENTS.remove();
        }
    }

    // vanilla only ever carries attack damage/speed under the base modifier ids. anything else
    // is a /give or hacked-client extra that would stack on top of the cap.
    private static boolean stripForeignAttackModifiers(ItemStack stack, boolean apply) {
        AttributeModifiersComponent current = stack.get(DataComponentTypes.ATTRIBUTE_MODIFIERS);
        if (current == null) {
            return false;
        }
        List<AttributeModifiersComponent.Entry> kept = null;
        for (int i = 0; i < current.modifiers().size(); i++) {
            AttributeModifiersComponent.Entry entry = current.modifiers().get(i);
            boolean attackAttr = entry.attribute().equals(EntityAttributes.ATTACK_DAMAGE)
                    || entry.attribute().equals(EntityAttributes.ATTACK_SPEED);
            boolean baseId = entry.modifier().idMatches(Item.BASE_ATTACK_DAMAGE_MODIFIER_ID)
                    || entry.modifier().idMatches(Item.BASE_ATTACK_SPEED_MODIFIER_ID);
            // a base-id modifier parked in another slot would dodge the mainhand-only cap below
            if (attackAttr && (!baseId || entry.slot() != AttributeModifierSlot.MAINHAND)) {
                if (!apply) return true;
                if (kept == null) kept = new ArrayList<>(current.modifiers().subList(0, i));
                continue;
            }
            if (kept != null) kept.add(entry);
        }
        if (kept != null) {
            stack.set(DataComponentTypes.ATTRIBUTE_MODIFIERS, new AttributeModifiersComponent(kept));
        }
        return kept != null;
    }

    private static Double toAttributeModifierValue(String stat, Double configuredValue) {
        if (configuredValue == null) {
            return null;
        }
        if ("attackDamage".equals(stat)) {
            return configuredValue - PLAYER_BASE_ATTACK_DAMAGE;
        }
        if ("attackSpeed".equals(stat)) {
            return configuredValue - PLAYER_BASE_ATTACK_SPEED;
        }
        return configuredValue;
    }

    private static boolean applyWeaponAttributeLimit(
            ItemStack stack,
            ComponentMap defaultComponents,
            RegistryEntry<net.minecraft.entity.attribute.EntityAttribute> attribute,
            net.minecraft.util.Identifier modifierId,
            Double value, boolean apply
    ) {
        AttributeModifiersComponent current = stack.getOrDefault(
                DataComponentTypes.ATTRIBUTE_MODIFIERS,
                AttributeModifiersComponent.DEFAULT
        );
        AttributeModifiersComponent defaults = defaultComponents.getOrDefault(
                DataComponentTypes.ATTRIBUTE_MODIFIERS,
                AttributeModifiersComponent.DEFAULT
        );
        AttributeModifiersComponent.Entry defaultEntry = findWeaponAttributeEntry(defaults, attribute, modifierId);
        boolean matches = false;
        boolean needsUpdate = false;
        for (AttributeModifiersComponent.Entry entry : current.modifiers()) {
            if (entry.slot() != AttributeModifierSlot.MAINHAND || !entry.attribute().equals(attribute)
                    || !entry.modifier().idMatches(modifierId)) continue;
            matches = true;
            if (value == null ? !entry.equals(defaultEntry)
                    : entry.modifier().value() != value || entry.modifier().operation() != EntityAttributeModifier.Operation.ADD_VALUE) {
                needsUpdate = true;
                break;
            }
        }
        if (!matches && (value != null || defaultEntry != null)) needsUpdate = true;
        if (!needsUpdate || !apply) return needsUpdate;

        List<AttributeModifiersComponent.Entry> entries = new ArrayList<>();
        boolean found = false;
        boolean changed = false;

        for (AttributeModifiersComponent.Entry entry : current.modifiers()) {
            if (entry.slot() == AttributeModifierSlot.MAINHAND
                    && entry.attribute().equals(attribute)
                    && entry.modifier().idMatches(modifierId)) {
                found = true;
                AttributeModifiersComponent.Entry desired = defaultEntry;
                if (value != null) {
                    desired = new AttributeModifiersComponent.Entry(
                            attribute,
                            new EntityAttributeModifier(modifierId, value, EntityAttributeModifier.Operation.ADD_VALUE),
                            AttributeModifierSlot.MAINHAND,
                            entry.display()
                    );
                }

                if (desired == null) {
                    changed = true;
                    continue;
                }

                if (!entry.equals(desired)) {
                    entries.add(desired);
                    changed = true;
                } else {
                    entries.add(entry);
                }
            } else {
                entries.add(entry);
            }
        }

        if (!found) {
            if (value != null) {
                entries.add(new AttributeModifiersComponent.Entry(
                        attribute,
                        new EntityAttributeModifier(modifierId, value, EntityAttributeModifier.Operation.ADD_VALUE),
                        AttributeModifierSlot.MAINHAND
                ));
                changed = true;
            } else if (defaultEntry != null) {
                entries.add(defaultEntry);
                changed = true;
            }
        }

        if (changed) {
            stack.set(DataComponentTypes.ATTRIBUTE_MODIFIERS, new AttributeModifiersComponent(entries));
        }
        return changed;
    }

    private static AttributeModifiersComponent.Entry findWeaponAttributeEntry(
            AttributeModifiersComponent component,
            RegistryEntry<net.minecraft.entity.attribute.EntityAttribute> attribute,
            net.minecraft.util.Identifier modifierId
    ) {
        for (AttributeModifiersComponent.Entry entry : component.modifiers()) {
            if (entry.slot() == AttributeModifierSlot.MAINHAND
                    && entry.attribute().equals(attribute)
                    && entry.modifier().idMatches(modifierId)) {
                return entry;
            }
        }
        return null;
    }

    private static boolean applyWeaponReachLimit(ItemStack stack, ComponentMap defaultComponents, Float reach, boolean apply) {
        AttackRangeComponent current = stack.get(DataComponentTypes.ATTACK_RANGE);
        AttackRangeComponent defaults = defaultComponents.get(DataComponentTypes.ATTACK_RANGE);
        AttackRangeComponent desired;
        if (reach == null) {
            desired = defaults;
        } else if (defaults == null) {
            desired = new AttackRangeComponent(0.0f, reach, 0.0f, reach, 0.0f, 1.0f);
        } else {
            desired = new AttackRangeComponent(
                    Math.min(defaults.minRange(), reach),
                    reach,
                    Math.min(defaults.minCreativeRange(), reach),
                    reach,
                    defaults.hitboxMargin(),
                    defaults.mobFactor()
            );
        }

        if (Objects.equals(desired, current)) {
            return false;
        }
        if (!apply) return true;

        if (desired == null) {
            stack.remove(DataComponentTypes.ATTACK_RANGE);
        } else {
            stack.set(DataComponentTypes.ATTACK_RANGE, desired);
        }
        return true;
    }

    public static Double getVanillaAttackDamage(Item item) {
        Double modifier = getVanillaAttackAttribute(item, EntityAttributes.ATTACK_DAMAGE, Item.BASE_ATTACK_DAMAGE_MODIFIER_ID);
        return modifier == null ? PLAYER_BASE_ATTACK_DAMAGE : modifier + PLAYER_BASE_ATTACK_DAMAGE;
    }

    public static Double getVanillaAttackSpeed(Item item) {
        Double modifier = getVanillaAttackAttribute(item, EntityAttributes.ATTACK_SPEED, Item.BASE_ATTACK_SPEED_MODIFIER_ID);
        return modifier == null ? PLAYER_BASE_ATTACK_SPEED : modifier + PLAYER_BASE_ATTACK_SPEED;
    }

    public static Float getVanillaReach(Item item) {
        AttackRangeComponent reach = item.getComponents().get(DataComponentTypes.ATTACK_RANGE);
        return reach == null ? null : reach.maxRange();
    }

    public static void applyWeaponAttackCooldown(ServerPlayerEntity player) {
        if (CONFIG == null || !CONFIG.weaponLimitsEnabled || player == null || isExempt(player)) {
            return;
        }
        ItemStack stack = player.getMainHandStack();
        if (stack.isEmpty()) {
            return;
        }
        WardenConfig.WeaponLimitConfig limit = getEffectiveWeaponLimit(stack, false);
        if (limit == null || limit.disableCooldownTicks == null || limit.disableCooldownTicks <= 0) {
            return;
        }
        player.getItemCooldownManager().set(stack, limit.disableCooldownTicks);
    }

    public static boolean isWeaponAttackBlockedByCooldown(PlayerEntity player) {
        boolean clientSide = player != null && player.getEntityWorld().isClient();
        if (!areWeaponLimitsEnabled(clientSide) || player == null || (!clientSide && isExempt(player))) {
            return false;
        }
        ItemStack stack = player.getMainHandStack();
        if (stack.isEmpty()) {
            return false;
        }
        WardenConfig.WeaponLimitConfig limit = getEffectiveWeaponLimit(stack, clientSide);
        if (limit == null || limit.disableCooldownTicks == null || limit.disableCooldownTicks <= 0) {
            return false;
        }
        return player.getItemCooldownManager().isCoolingDown(stack);
    }

    public static void applyWeaponUseCooldown(ServerPlayerEntity player, ItemStack stack) {
        if (CONFIG == null || !CONFIG.weaponLimitsEnabled || player == null || isExempt(player) || stack == null || stack.isEmpty()) {
            return;
        }
        WardenConfig.WeaponLimitConfig limit = getEffectiveWeaponLimit(stack, false);
        if (limit == null || limit.rechargeTicks == null || limit.rechargeTicks <= 0) {
            return;
        }
        player.getItemCooldownManager().set(stack, limit.rechargeTicks);
    }

    public static boolean isWeaponUseBlockedByCooldown(PlayerEntity player, ItemStack stack) {
        if (!areWeaponLimitsEnabled(player != null && player.getEntityWorld().isClient())
                || player == null || isExempt(player) || stack == null || stack.isEmpty()) {
            return false;
        }
        WardenConfig.WeaponLimitConfig limit = getEffectiveWeaponLimit(stack, player.getEntityWorld().isClient());
        if (limit == null || limit.rechargeTicks == null || limit.rechargeTicks <= 0) {
            return false;
        }
        return player.getItemCooldownManager().isCoolingDown(stack);
    }

    public static int getConfiguredRangedUseTicks(ItemStack stack, boolean clientSide, int vanillaTicks) {
        if (!areWeaponLimitsEnabled(clientSide) || stack == null || stack.isEmpty()) {
            return vanillaTicks;
        }
        WardenConfig.WeaponLimitConfig limit = getEffectiveWeaponLimit(stack, clientSide);
        if (limit == null || limit.rechargeTicks == null || limit.rechargeTicks <= 0) {
            return vanillaTicks;
        }
        return limit.rechargeTicks;
    }

    public static float getConfiguredBowPullProgress(ItemStack stack, boolean clientSide, int useTicks) {
        int pullTicks = Math.max(1, getConfiguredRangedUseTicks(stack, clientSide, 20));
        float progress = (float) useTicks / (float) pullTicks;
        progress = (progress * progress + progress * 2.0f) / 3.0f;
        return Math.min(progress, 1.0f);
    }

    public static int getConfiguredTridentUseTicks(ItemStack stack, boolean clientSide, int vanillaTicks) {
        return Math.max(1, getConfiguredRangedUseTicks(stack, clientSide, vanillaTicks));
    }

    public static void applyConfiguredProjectileDamage(PersistentProjectileEntity projectile, ItemStack weaponStack) {
        if (projectile == null || weaponStack == null || weaponStack.isEmpty()
                || !areWeaponLimitsEnabled(projectile.getEntityWorld().isClient())) {
            return;
        }
        WardenConfig.WeaponLimitConfig limit = getEffectiveWeaponLimit(weaponStack, projectile.getEntityWorld().isClient());
        if (limit != null && limit.projectileDamage != null) {
            projectile.setDamage(limit.projectileDamage);
        }
    }

    public static boolean enforceProjectileDamage(PersistentProjectileEntity projectile) {
        if (CONFIG == null || !CONFIG.weaponLimitsEnabled || projectile == null || projectile.getEntityWorld().isClient()) {
            return true;
        }
        ItemStack weaponStack = resolveProjectileWeaponStack(projectile);
        if (weaponStack.isEmpty()) {
            return false;
        }
        applyConfiguredProjectileDamage(projectile, weaponStack);
        return true;
    }

    private static ItemStack resolveProjectileWeaponStack(PersistentProjectileEntity projectile) {
        // null for arrows without a recorded weapon: mob and dispenser shots, or ones saved by older versions
        ItemStack weaponStack = projectile.getWeaponStack();
        if (weaponStack != null && !weaponStack.isEmpty()) {
            return weaponStack;
        }
        if (projectile.getOwner() instanceof PlayerEntity player) {
            ItemStack mainHand = player.getMainHandStack();
            if (isRangedWeaponStack(mainHand)) {
                return mainHand;
            }
            ItemStack offHand = player.getOffHandStack();
            if (isRangedWeaponStack(offHand)) {
                return offHand;
            }
        }
        return ItemStack.EMPTY;
    }

    private static boolean isRangedWeaponStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        String itemId = Registries.ITEM.getId(stack.getItem()).toString();
        WardenToolType type = WardenToolType.byItemId(itemId);
        return type == WardenToolType.BOW || type == WardenToolType.CROSSBOW || type == WardenToolType.TRIDENT;
    }

    private static Double getVanillaAttackAttribute(
            Item item,
            RegistryEntry<net.minecraft.entity.attribute.EntityAttribute> attribute,
            net.minecraft.util.Identifier modifierId
    ) {
        AttributeModifiersComponent defaults = item.getComponents().getOrDefault(
                DataComponentTypes.ATTRIBUTE_MODIFIERS,
                AttributeModifiersComponent.DEFAULT
        );
        AttributeModifiersComponent.Entry entry = findWeaponAttributeEntry(defaults, attribute, modifierId);
        return entry == null ? null : entry.modifier().value();
    }

    public static void applySyncedWeaponLimits(String json) {
        CLIENT_SYNCED_WEAPON_LIMITS.clear();
        CLIENT_SYNCED_WEAPON_LIMITS_VALID = false;
        CLIENT_SYNCED_WEAPON_LIMITS_ENABLED = true;

        Map<String, WardenConfig.WeaponLimitConfig> parsed = new HashMap<>();
        boolean enabled;
        try {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        enabled = !root.has("enabled") || root.get("enabled").getAsBoolean();
        if (root.has("items")) {
            JsonObject items = root.getAsJsonObject("items");
            for (Map.Entry<String, JsonElement> entry : items.entrySet()) {
                JsonObject itemCfg = entry.getValue().getAsJsonObject();
                WardenConfig.WeaponLimitConfig cfg = new WardenConfig.WeaponLimitConfig();
                if (itemCfg.has("disable_cooldown_ticks")) {
                    cfg.disableCooldownTicks = itemCfg.get("disable_cooldown_ticks").getAsInt();
                }
                if (itemCfg.has("projectile_damage")) {
                    cfg.projectileDamage = itemCfg.get("projectile_damage").getAsDouble();
                }
                if (itemCfg.has("recharge_ticks")) {
                    cfg.rechargeTicks = itemCfg.get("recharge_ticks").getAsInt();
                }
                if (cfg.isConfigured()) {
                    parsed.put(entry.getKey(), cfg);
                }
            }
        }
        } catch (RuntimeException e) {
            LOGGER.warn("[Warden] ignoring malformed weapon limit sync: {}", e.getMessage());
            return;
        }
        CLIENT_SYNCED_WEAPON_LIMITS.putAll(parsed);
        CLIENT_SYNCED_WEAPON_LIMITS_ENABLED = enabled;
        CLIENT_SYNCED_WEAPON_LIMITS_VALID = true;
    }

    public static void clearSyncedWeaponLimits() {
        CLIENT_SYNCED_WEAPON_LIMITS.clear();
        CLIENT_SYNCED_WEAPON_LIMITS_VALID = false;
        CLIENT_SYNCED_WEAPON_LIMITS_ENABLED = true;
    }

    private static boolean areWeaponLimitsEnabled(boolean clientSide) {
        if (clientSide && CLIENT_SYNCED_WEAPON_LIMITS_VALID) {
            return CLIENT_SYNCED_WEAPON_LIMITS_ENABLED;
        }
        return CONFIG != null && CONFIG.weaponLimitsEnabled;
    }

    private static WardenConfig.WeaponLimitConfig getEffectiveWeaponLimit(ItemStack stack, boolean clientSide) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        String itemId = Registries.ITEM.getId(stack.getItem()).toString();
        if (clientSide && CLIENT_SYNCED_WEAPON_LIMITS_VALID) {
            return CLIENT_SYNCED_WEAPON_LIMITS.get(itemId);
        }
        if (CONFIG == null) {
            return null;
        }
        return CONFIG.weaponLimits.get(itemId);
    }

    public static void enforceEnchantmentLimits(ServerPlayerEntity player) {
        if (!CONFIG.enchantmentLimitsEnabled || isExempt(player)) {
            return;
        }
        if (CONFIG.enchantmentLimits.isEmpty() && CONFIG.itemEnchantmentOverrides.isEmpty()) {
            return;
        }

        PlayerInventory inv = player.getInventory();
        boolean changed = false;

        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) {
                continue;
            }
            changed |= enforceEnchantmentLimitsRecursive(stack);
        }

        if (changed) {
            sendNotice(player, NoticeCategory.ENCHANTMENT, "enchantment(s) capped on your items");
        }
    }

    private static boolean enforceEnchantmentLimitsRecursive(ItemStack stack) {
        if (stack.isEmpty()) return false;
        boolean changed = false;
        changed |= enforceStackEnchantments(stack, DataComponentTypes.ENCHANTMENTS);
        changed |= enforceStackEnchantments(stack, DataComponentTypes.STORED_ENCHANTMENTS);

        // Bundle
        BundleContentsComponent bundle = stack.get(DataComponentTypes.BUNDLE_CONTENTS);
        if (bundle != null && contentsNeedUpdate(bundle.iterate(), false)) {
            List<ItemStack> newContents = new ArrayList<>();
            boolean bundleChanged = false;
            for (ItemStack inner : bundle.iterateCopy()) {
                if (enforceEnchantmentLimitsRecursive(inner)) {
                    bundleChanged = true;
                }
                newContents.add(inner);
            }
            if (bundleChanged) {
                stack.set(DataComponentTypes.BUNDLE_CONTENTS, new BundleContentsComponent(newContents));
                changed = true;
            }
        }

        // Container
        ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
        if (container != null && contentsNeedUpdate(container.iterateNonEmpty(), false)) {
            List<ItemStack> stacks = container.stream().toList();
            boolean containerChanged = false;
            for (ItemStack inner : stacks) {
                if (enforceEnchantmentLimitsRecursive(inner)) {
                    containerChanged = true;
                }
            }
            if (containerChanged) {
                stack.set(DataComponentTypes.CONTAINER, ContainerComponent.fromStacks(stacks));
                changed = true;
            }
        }

        return changed;
    }

    private static boolean contentsNeedUpdate(Iterable<ItemStack> contents, boolean weapons) {
        for (ItemStack stack : contents) {
            if (stack.isEmpty()) continue;
            if (weapons) {
                if (enforceWeaponComponents(stack, false)) return true;
            } else if (capEnchantments(stack, stack.get(DataComponentTypes.ENCHANTMENTS)) != null
                    || capEnchantments(stack, stack.get(DataComponentTypes.STORED_ENCHANTMENTS)) != null) {
                return true;
            }
            BundleContentsComponent bundle = stack.get(DataComponentTypes.BUNDLE_CONTENTS);
            if (bundle != null && contentsNeedUpdate(bundle.iterate(), weapons)) return true;
            ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
            if (container != null && contentsNeedUpdate(container.iterateNonEmpty(), weapons)) return true;
        }
        return false;
    }

    private static boolean enforceStackEnchantments(
            ItemStack stack,
            net.minecraft.component.ComponentType<ItemEnchantmentsComponent> componentType
    ) {
        ItemEnchantmentsComponent enchants = stack.getOrDefault(componentType, ItemEnchantmentsComponent.DEFAULT);
        ItemEnchantmentsComponent capped = capEnchantments(stack, enchants);
        if (capped == null) {
            return false;
        }
        stack.set(componentType, capped);
        return true;
    }

    /** Returns the capped component, or null when nothing was over a limit. */
    public static ItemEnchantmentsComponent capEnchantments(ItemStack stack, ItemEnchantmentsComponent enchants) {
        if (CONFIG == null || !CONFIG.enchantmentLimitsEnabled || enchants == null || enchants.isEmpty()) {
            return null;
        }
        if (CONFIG.enchantmentLimits.isEmpty() && CONFIG.itemEnchantmentOverrides.isEmpty()) {
            return null;
        }

        String itemId = Registries.ITEM.getId(stack.getItem()).toString();
        Map<String, Integer> itemOverrides = CONFIG.itemEnchantmentOverrides.get(itemId);
        ItemEnchantmentsComponent.Builder builder = null;

        for (RegistryEntry<Enchantment> entry : enchants.getEnchantments()) {
            String enchId = entry.getKey().map(k -> k.getValue().toString()).orElse(null);
            if (enchId == null) {
                continue;
            }

            Integer limit = itemOverrides != null ? itemOverrides.get(enchId) : null;
            if (limit != null && limit == -1) {
                // Explicitly no cap for this item, overriding global cap
                continue;
            }

            if (limit == null) {
                limit = CONFIG.enchantmentLimits.get(enchId);
            }
            if (limit == null || limit == -1) {
                continue;
            }

            int current = enchants.getLevel(entry);
            if (current <= limit) {
                continue;
            }

            if (builder == null) {
                builder = new ItemEnchantmentsComponent.Builder(enchants);
            }
            builder.set(entry, limit);
            LOGGER.debug("[Warden] {} on {} capped: {} -> {}", enchId, itemId, current, limit);
        }

        return builder == null ? null : builder.build();
    }

    public static void enforceEffectLimits(ServerPlayerEntity player) {
        if (!CONFIG.effectLimitsEnabled || isExempt(player) || CONFIG.effectLimits.isEmpty()) {
            return;
        }

        List<StatusEffectInstance> effects = new ArrayList<>(player.getStatusEffects());
        for (StatusEffectInstance instance : effects) {
            RegistryEntry<StatusEffect> effectType = instance.getEffectType();
            String effectId = effectType.getKey().map(k -> k.getValue().toString()).orElse(null);
            if (effectId == null) {
                continue;
            }

            WardenConfig.EffectLimitConfig limit = CONFIG.effectLimits.get(effectId);
            if (limit == null) {
                continue;
            }

            int amplifier = instance.getAmplifier();
            int duration = instance.getDuration();

            if (limit.maxDuration == 0 || limit.maxLevel == 0) {
                player.removeStatusEffect(effectType);
                sendNotice(player, NoticeCategory.EFFECT, shortId(effectId) + " blocked");
                LOGGER.debug("[Warden] Removed blocked effect {} from {}", effectId, player.getName().getString());
                continue;
            }

            int newAmplifier = (limit.maxLevel > 0 && amplifier + 1 > limit.maxLevel) ? limit.maxLevel - 1 : amplifier;
            int newDuration = duration;
            if (!shouldSkipDurationCap(instance) && limit.maxDuration > 0 && (duration == -1 || duration > limit.maxDuration)) {
                newDuration = limit.maxDuration;
            }

            if (newAmplifier != amplifier || newDuration != duration) {
                player.removeStatusEffect(effectType);
                player.addStatusEffect(new StatusEffectInstance(effectType, newDuration, newAmplifier,
                        instance.isAmbient(), instance.shouldShowParticles(), instance.shouldShowIcon()));
                sendNotice(player, NoticeCategory.EFFECT, shortId(effectId) + " capped");
                LOGGER.debug("[Warden] Capped effect {} for {}: level {}->{}, duration {}->{}",
                        effectId, player.getName().getString(), amplifier + 1, newAmplifier + 1, duration, newDuration);
            }
        }
    }

    public static int limitExperienceGain(ServerPlayerEntity player, int experience) {
        if (CONFIG == null || !CONFIG.xpLimitsEnabled || (player != null && isExempt(player)) || experience <= 0) {
            return experience;
        }

        String source = XP_SOURCE_OVERRIDE.get();
        String context = XP_CONTEXT_OVERRIDE.get();

        if (source == null) {
            source = XP_SOURCE.get();
            context = XP_CONTEXT.get();
        }

        if (context == null) {
            context = "";
        }

        Integer limit = null;
        boolean specificOverride = false;

        Map<String, Integer> sourceOverrides = CONFIG.xpOverrides.get(source);
        if (sourceOverrides != null && !context.isEmpty()) {
            limit = sourceOverrides.get(context);
            if (limit != null) specificOverride = true;
        }

        if (limit == null) {
            limit = CONFIG.xpLimits.get(source);
            if (limit != null) specificOverride = true;
        }

        if (limit == null && !source.equals("all")) {
            limit = CONFIG.xpLimits.get("all");
        }

        // -1 means explicitly NO limit, overriding global caps
        if (specificOverride && limit != null && limit == -1) {
            return experience;
        }

        if (limit != null && limit >= 0 && experience > limit) {
            String notice = context.isEmpty() ? source : shortId(context);
            if (player != null && CONFIG.xpActionBarEnabled) {
                sendNotice(player, NoticeCategory.XP, "XP Dropped (" + notice + ") capped at " + limit);
            }
            return limit;
        }

        return experience;
    }

    public static void runWithXpSource(String source, Runnable runnable) {
        runWithXpContext(source, "", runnable);
    }

    public static void runWithXpContext(String source, String context, Runnable runnable) {
        String prevSource = XP_SOURCE.get();
        String prevContext = XP_CONTEXT.get();
        XP_SOURCE.set(source);
        XP_CONTEXT.set(context);
        try {
            runnable.run();
        } finally {
            XP_SOURCE.set(prevSource);
            XP_CONTEXT.set(prevContext);
        }
    }
}
