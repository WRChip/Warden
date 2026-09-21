package com.warden;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-wide caps: how many of an item may exist at once across the whole server, rather than
 * per player. The census covers dropped item entities and everything an online player carries -
 * inventory, ender chest and the stack on their cursor - nested bundles and shulkers included.
 *
 * What it can't see: containers placed in the world, and the inventories of offline players.
 * Exempt players are skipped entirely: their items neither count nor get trimmed.
 */
public class WardenGlobalLimits {

    /** Totals from the last sweep. Read by the crafting gate, which can't afford a fresh census. */
    private static final Map<String, Integer> COUNTS = new ConcurrentHashMap<>();

    public static void sweep(MinecraftServer server) {
        var cfg = WardenMod.CONFIG;
        if (!cfg.globalItemLimitsEnabled || cfg.globalItemLimits.isEmpty()) {
            COUNTS.clear();
            return;
        }

        Map<String, Integer> totals = census(server);
        for (Map.Entry<String, Integer> e : cfg.globalItemLimits.entrySet()) {
            int total = totals.getOrDefault(e.getKey(), 0);
            int excess = total - e.getValue();
            if (excess > 0) {
                totals.put(e.getKey(), total - trim(server, e.getKey(), excess, e.getValue()));
            }
        }

        COUNTS.keySet().retainAll(totals.keySet());
        COUNTS.putAll(totals);
    }

    public static Map<String, Integer> census(MinecraftServer server) {
        Map<String, Integer> totals = new HashMap<>();
        var tracked = WardenMod.CONFIG.globalItemLimits.keySet();
        for (ServerWorld world : server.getWorlds()) {
            for (ItemEntity entity : world.getEntitiesByType(EntityType.ITEM, e -> !e.isRemoved())) {
                WardenMod.countTracked(entity.getStack(), totals, tracked);
            }
        }
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (WardenMod.isExempt(player)) continue;
            var inv = player.getInventory();
            for (int i = 0; i < inv.size(); i++) {
                WardenMod.countTracked(inv.getStack(i), totals, tracked);
            }
            var ender = player.getEnderChestInventory();
            for (int i = 0; i < ender.size(); i++) {
                WardenMod.countTracked(ender.getStack(i), totals, tracked);
            }
            var handler = player.currentScreenHandler;
            if (handler != null) {
                WardenMod.countTracked(handler.getCursorStack(), totals, tracked);
            }
        }
        return totals;
    }

    /** Last known server-wide total, as of the previous sweep. */
    public static int count(String itemId) {
        return COUNTS.getOrDefault(itemId, 0);
    }

    /** True if adding {@code amount} more of the item would put the server over its cap. */
    public static boolean wouldExceed(String itemId, int amount) {
        var cfg = WardenMod.CONFIG;
        if (!cfg.globalItemLimitsEnabled) return false;
        Integer limit = cfg.globalItemLimits.get(itemId);
        return limit != null && count(itemId) + amount > limit;
    }

    /** Books the item in against the cap between sweeps, so a burst of crafting can't slip past. */
    public static void record(String itemId, int amount) {
        if (WardenMod.CONFIG.globalItemLimits.containsKey(itemId)) {
            COUNTS.merge(itemId, amount, Integer::sum);
        }
    }

    /** Dropped items go first - nobody is holding them - then whoever is carrying the most. */
    private static int trim(MinecraftServer server, String itemId, int excess, int limit) {
        int remaining = excess;
        for (ServerWorld world : server.getWorlds()) {
            if (remaining <= 0) break;
            for (ItemEntity entity : world.getEntitiesByType(EntityType.ITEM, e -> !e.isRemoved())) {
                if (remaining <= 0) break;
                // a shulker lying on the ground counts, so it has to be trimmable too
                ItemStack stack = entity.getStack().copy();
                int taken = WardenMod.deleteFromStack(stack, itemId, remaining);
                if (taken == 0) continue;
                remaining -= taken;
                if (stack.isEmpty()) {
                    entity.discard();
                } else {
                    entity.setStack(stack);
                }
            }
        }

        if (remaining > 0) {
            List<ServerPlayerEntity> holders = new ArrayList<>();
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                if (!WardenMod.isExempt(player) && WardenMod.countCarried(player, itemId) > 0) holders.add(player);
            }
            holders.sort(Comparator.comparingInt((ServerPlayerEntity p) -> WardenMod.countCarried(p, itemId)).reversed());
            for (ServerPlayerEntity player : holders) {
                if (remaining <= 0) break;
                int taken = WardenMod.deleteCarried(player, itemId, remaining);
                if (taken > 0) {
                    remaining -= taken;
                    WardenMod.sendNotice(player, WardenMod.NoticeCategory.ITEM,
                            "removed " + taken + "x " + WardenMod.shortId(itemId) + " (server limit: " + limit + ")");
                }
            }
        }

        int removed = excess - remaining;
        if (removed > 0) {
            WardenMod.LOGGER.info("[Warden] removed {} excess {} - server limit is {}", removed, itemId, limit);
        }
        return removed;
    }
}
