package com.warden;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.warden.config.WardenConfig;
import com.warden.net.ConfigSyncPayload;
import com.warden.net.ConfigUpdatePayload;
import com.warden.net.NoticePrefsPayload;
import com.warden.net.WeaponLimitsSyncPayload;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class WardenNetworking {

    private static final List<String> EDITABLE_SECTIONS = List.of(
            "explosion_limits", "item_limits", "item_usage", "weapon_limits", "enchantment_limits",
            "effect_limits", "xp_limits", "dimension_limits", "anti_seedcrack", "chunk_ban", "action_bar", "exempt");
    private static final List<String> NOTICE_CATEGORIES = List.of("item", "weapon", "enchantment", "effect", "xp", "dimension");
    private static boolean prefsDirty;

    private WardenNetworking() {
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(WeaponLimitsSyncPayload.ID, WeaponLimitsSyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ConfigSyncPayload.ID, ConfigSyncPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ConfigUpdatePayload.ID, ConfigUpdatePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(NoticePrefsPayload.ID, NoticePrefsPayload.CODEC);

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                syncWeaponLimits(handler.player));
        ServerPlayNetworking.registerGlobalReceiver(ConfigUpdatePayload.ID, (payload, context) ->
                applyConfigUpdate(context.player(), payload.json()));
        ServerPlayNetworking.registerGlobalReceiver(NoticePrefsPayload.ID, (payload, context) ->
                applyNoticePrefs(context.player(), payload.disabled()));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (prefsDirty && server.getTicks() % 20 == 0) savePrefs();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> savePrefs());
    }

    public static void syncWeaponLimits(ServerPlayerEntity player) {
        if (ServerPlayNetworking.canSend(player, WeaponLimitsSyncPayload.ID)) {
            ServerPlayNetworking.send(player, new WeaponLimitsSyncPayload(serializeWeaponLimits()));
        }
        if (ServerPlayNetworking.canSend(player, ConfigSyncPayload.ID)) {
            boolean canEdit = canEdit(player);
            ServerPlayNetworking.send(player, new ConfigSyncPayload(configViewFor(player, canEdit).toString(), canEdit));
        }
    }

    public static void syncWeaponLimits(MinecraftServer server) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            syncWeaponLimits(player);
        }
    }

    private static boolean canEdit(ServerPlayerEntity player) {
        return player.getPermissions().hasPermission(new Permission.Level(PermissionLevel.GAMEMASTERS));
    }

    // what the config screen gets: everything except other players' prefs, and no exempt list
    // unless they could edit it anyway
    private static JsonObject configViewFor(ServerPlayerEntity player, boolean canEdit) {
        JsonObject root = WardenMod.CONFIG.toJsonObject();
        root.getAsJsonObject("action_bar").remove("players");
        if (!canEdit) {
            root.remove("exempt");
        }
        JsonArray mine = new JsonArray();
        Set<String> disabled = WardenMod.CONFIG.playerActionBarDisabled.get(player.getUuidAsString());
        if (disabled == null) {
            disabled = WardenMod.CONFIG.playerActionBarDisabled.get(player.getName().getString());
        }
        if (disabled != null) {
            disabled.forEach(mine::add);
        }
        root.add("my_disabled_notices", mine);
        return root;
    }

    private static void applyConfigUpdate(ServerPlayerEntity player, String json) {
        String name = player.getName().getString();
        if (!canEdit(player)) {
            WardenMod.LOGGER.warn("[Warden] {} sent a config update without permission, ignored", name);
            return;
        }
        JsonObject incoming;
        try {
            incoming = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            WardenMod.LOGGER.warn("[Warden] bad config update from {}: {}", name, e.getMessage());
            return;
        }

        JsonObject current = WardenMod.CONFIG.toJsonObject();
        JsonObject merged = current.deepCopy();
        for (String section : EDITABLE_SECTIONS) {
            if (incoming.has(section) && incoming.get(section).isJsonObject()) {
                merged.add(section, incoming.getAsJsonObject(section));
            }
        }
        // per-player notice prefs never travel with the editor payload; keep what the server has
        merged.getAsJsonObject("action_bar").add("players", current.getAsJsonObject("action_bar").get("players"));

        try {
            WardenMod.CONFIG = WardenConfig.fromJson(merged);
        } catch (RuntimeException e) {
            WardenMod.LOGGER.warn("[Warden] config update from {} did not parse: {}", name, e.getMessage());
            syncWeaponLimits(player);
            return;
        }
        WardenMod.CONFIG.save();
        WardenMod.LOGGER.info("[Warden] config updated from the in-game screen by {}", name);
        syncWeaponLimits(player.getEntityWorld().getServer());
    }

    private static void applyNoticePrefs(ServerPlayerEntity player, String csv) {
        Set<String> disabled = new LinkedHashSet<>();
        for (String cat : csv.split(",")) {
            if (NOTICE_CATEGORIES.contains(cat)) {
                disabled.add(cat);
            }
        }
        Set<String> previous = WardenMod.CONFIG.playerActionBarDisabled.get(player.getUuidAsString());
        if (!WardenMod.CONFIG.playerActionBarDisabled.containsKey(player.getName().getString())
                && Objects.equals(previous == null ? Set.of() : previous, disabled)) return;
        WardenMod.CONFIG.playerActionBarDisabled.remove(player.getName().getString());
        if (disabled.isEmpty()) {
            WardenMod.CONFIG.playerActionBarDisabled.remove(player.getUuidAsString());
        } else {
            WardenMod.CONFIG.playerActionBarDisabled.put(player.getUuidAsString(), disabled);
        }
        prefsDirty = true;
    }

    private static void savePrefs() {
        if (!prefsDirty) return;
        WardenMod.CONFIG.save();
        prefsDirty = false;
    }

    private static String serializeWeaponLimits() {
        JsonObject root = new JsonObject();
        root.addProperty("enabled", WardenMod.CONFIG.weaponLimitsEnabled);

        JsonObject items = new JsonObject();
        for (var entry : WardenMod.CONFIG.weaponLimits.entrySet()) {
            WardenConfig.WeaponLimitConfig cfg = entry.getValue();
            if (cfg == null || !cfg.isConfigured()) {
                continue;
            }
            JsonObject item = new JsonObject();
            if (cfg.disableCooldownTicks != null) {
                item.addProperty("disable_cooldown_ticks", cfg.disableCooldownTicks);
            }
            if (cfg.projectileDamage != null) {
                item.addProperty("projectile_damage", cfg.projectileDamage);
            }
            if (cfg.rechargeTicks != null) {
                item.addProperty("recharge_ticks", cfg.rechargeTicks);
            }
            items.add(entry.getKey(), item);
        }

        root.add("items", items);
        return root.toString();
    }
}
