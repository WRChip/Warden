package com.warden.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.warden.WardenClient;
import com.warden.WardenMod;
import com.warden.net.ConfigUpdatePayload;
import com.warden.net.NoticePrefsPayload;
import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.LabelOption;
import dev.isxander.yacl3.api.ListOption;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.OptionGroup;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.IntegerFieldControllerBuilder;
import dev.isxander.yacl3.api.controller.StringControllerBuilder;
import dev.isxander.yacl3.api.controller.TickBoxControllerBuilder;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * YACL screen. "Notices" is per-player and works for everyone; "Server rules" shows what the
 * server sent and becomes editable when the server said we have permission. Edits are made on a
 * copy of the synced JSON and sent back on save; the server re-checks permission and re-syncs.
 */
public final class WardenConfigScreen {

    private static final Map<String, String> NOTICE_LABELS = Map.of(
            "item", "Item limit notices",
            "weapon", "Weapon limit notices",
            "enchantment", "Enchantment cap notices",
            "effect", "Effect cap notices",
            "xp", "XP cap notices",
            "dimension", "Blocked dimension notices");
    private static final List<String> NOTICE_ORDER = List.of("item", "weapon", "enchantment", "effect", "xp", "dimension");

    private WardenConfigScreen() {
    }

    public static Screen create(Screen parent) {
        boolean connected = WardenClientState.connected();
        JsonObject draft = connected ? WardenClientState.serverConfig.deepCopy() : null;
        JsonObject original = draft == null ? null : draft.deepCopy();
        boolean canEdit = connected && WardenClientState.canEdit;
        Set<String> disabled = new LinkedHashSet<>(WardenClientState.disabledNotices);
        boolean[] noticesDirty = {false};
        boolean[] draftDirty = {false};
        List<String> rejected = new ArrayList<>();

        YetAnotherConfigLib.Builder yacl = YetAnotherConfigLib.createBuilder()
                .title(Text.literal("Warden"))
                .category(notices(connected, disabled, noticesDirty));
        if (draft != null) {
            yacl.category(rules(draft, canEdit, draftDirty, rejected));
        }
        yacl.save(() -> {
            WardenClient.CONFIG.save();
            if (noticesDirty[0] && WardenClientState.connected()) {
                ClientPlayNetworking.send(new NoticePrefsPayload(String.join(",", disabled)));
                noticesDirty[0] = false;
            }
            if (draftDirty[0] && canEdit && WardenClientState.connected()) {
                ClientPlayNetworking.send(ConfigUpdatePayload.between(original, draft));
                draftDirty[0] = false;
            }
            if (!rejected.isEmpty()) {
                MinecraftClient client = MinecraftClient.getInstance();
                if (client.player != null) {
                    client.player.sendMessage(Text.literal("[Warden] ignored " + rejected.size()
                            + " line(s) that didn't parse: " + String.join(", ", rejected)).formatted(Formatting.RED), false);
                }
                rejected.clear();
            }
        });
        return yacl.build().generateScreen(parent);
    }

    // ---------------------------------------------------------------- notices

    private static ConfigCategory notices(boolean connected, Set<String> disabled, boolean[] dirty) {
        ConfigCategory.Builder cat = ConfigCategory.createBuilder().name(Text.literal("Notices"));

        cat.option(Option.<Boolean>createBuilder()
                .name(Text.literal("Block swings while weapon is on cooldown"))
                .description(OptionDescription.of(Text.literal(
                        "The server refuses hits during a configured weapon cooldown anyway. "
                                + "This also stops the swing animation so it doesn't look like lag.")))
                .binding(true, () -> WardenClient.CONFIG.blockSwingsOnCooldown, v -> WardenClient.CONFIG.blockSwingsOnCooldown = v)
                .controller(TickBoxControllerBuilder::create)
                .build());

        OptionGroup.Builder group = OptionGroup.createBuilder()
                .name(Text.literal("Action bar messages"))
                .description(OptionDescription.of(Text.literal(connected
                        ? "Which Warden messages show in your action bar on this server."
                        : "Join a server running Warden to change these.")));
        for (String key : NOTICE_ORDER) {
            group.option(Option.<Boolean>createBuilder()
                    .name(Text.literal(NOTICE_LABELS.get(key)))
                    .binding(true, () -> !disabled.contains(key), v -> {
                        if (v) disabled.remove(key); else disabled.add(key);
                        dirty[0] = true;
                    })
                    .controller(TickBoxControllerBuilder::create)
                    .available(connected)
                    .build());
        }
        cat.group(group.build());
        return cat.build();
    }

    // ---------------------------------------------------------------- server rules

    private static ConfigCategory rules(JsonObject draft, boolean canEdit, boolean[] dirty, List<String> rejected) {
        ConfigCategory.Builder cat = ConfigCategory.createBuilder().name(Text.literal("Server rules"));
        cat.option(LabelOption.create(Text.literal(canEdit
                ? "You can edit these. Saving applies them on the server for everyone."
                : "Read only. Ask an operator to change them.").formatted(canEdit ? Formatting.GREEN : Formatting.GRAY)));

        JsonObject items = draft.getAsJsonObject("item_limits");
        JsonObject globalItems = draft.has("global_item_limits") ? draft.getAsJsonObject("global_item_limits") : null;
        JsonObject usage = draft.has("item_usage") ? draft.getAsJsonObject("item_usage") : null;
        JsonObject effects = draft.getAsJsonObject("effect_limits");
        JsonObject enchants = draft.getAsJsonObject("enchantment_limits");
        JsonObject weapons = draft.getAsJsonObject("weapon_limits");
        JsonObject explosions = draft.getAsJsonObject("explosion_limits");
        JsonObject xp = draft.getAsJsonObject("xp_limits");
        JsonObject dimensions = draft.getAsJsonObject("dimension_limits");
        JsonObject antiCrack = draft.getAsJsonObject("anti_seedcrack");
        JsonObject chunkBan = draft.getAsJsonObject("chunk_ban");
        JsonObject exempt = draft.has("exempt") ? draft.getAsJsonObject("exempt") : null;

        OptionGroup.Builder general = OptionGroup.createBuilder().name(Text.literal("General"));
        general.option(toggle("Item limits", items, "enabled", canEdit, dirty));
        if (globalItems != null) general.option(toggle("Server-wide item limits", globalItems, "enabled", canEdit, dirty));
        if (usage != null) general.option(toggle("Item usage restrictions", usage, "enabled", canEdit, dirty));
        general.option(toggle("Weapon limits", weapons, "enabled", canEdit, dirty));
        general.option(toggle("Enchantment caps", enchants, "enabled", canEdit, dirty));
        general.option(toggle("Effect caps", effects, "enabled", canEdit, dirty));
        general.option(toggle("Explosion caps", explosions, "enabled", canEdit, dirty));
        general.option(toggle("XP caps", xp, "enabled", canEdit, dirty));
        general.option(toggle("Dimension restrictions", dimensions, "enabled", canEdit, dirty));
        general.option(toggle("Scramble structure loot seeds", antiCrack, "enabled", canEdit, dirty));
        general.option(toggle("Chunk-ban guard", chunkBan, "enabled", canEdit, dirty));
        general.option(intField("Chunk-ban: max bytes per item", chunkBan, "max_item_bytes", 1024, canEdit, dirty));
        general.option(intField("Chunk-ban: max bytes per block entity", chunkBan, "max_block_entity_bytes", 1024, canEdit, dirty));
        general.option(intField("Chunk-ban: max block entity bytes per chunk", chunkBan, "max_chunk_block_entity_bytes", 65536, canEdit, dirty));
        general.option(intField("Item check interval (ticks)", items, "check_interval_ticks", 1, canEdit, dirty));
        general.option(intField("Dropped overflow pickup delay (ticks)", items, "drop_pickup_delay", 0, canEdit, dirty));
        general.option(toggle("Delete overflow instead of dropping it", items, "delete_overflow_item", canEdit, dirty));
        if (exempt != null) {
            general.option(toggle("Creative players are exempt", exempt, "creative", canEdit, dirty));
        }
        cat.group(general.build());

        cat.group(mapList("Item limits",
                "One per line: item id = max count. 0 bans the item everywhere the player can reach it.",
                items, "items", true, canEdit, dirty, rejected,
                (id, el) -> id + " = " + el.getAsInt(),
                (map, id, value) -> map.addProperty(id, Integer.parseInt(value))));

        if (globalItems != null) {
            cat.group(mapList("Server-wide item limits",
                    "One per line: item id = max count across the whole server. Counts dropped items and "
                            + "what players carry, nested containers included. 0 keeps the item off the server entirely.",
                    globalItems, "items", true, canEdit, dirty, rejected,
                    (id, el) -> id + " = " + el.getAsInt(),
                    (map, id, value) -> map.addProperty(id, Integer.parseInt(value))));
        }

        cat.group(mapList("Effect caps",
                "One per line: effect id = max level / max ticks. 0 for either blocks the effect, -1 means no cap.",
                effects, "effects", true, canEdit, dirty, rejected,
                (id, el) -> id + " = " + el.getAsJsonObject().get("max_level").getAsInt()
                        + " / " + el.getAsJsonObject().get("max_duration").getAsInt(),
                (map, id, value) -> {
                    String[] parts = value.split("/");
                    JsonObject o = new JsonObject();
                    o.addProperty("max_level", Integer.parseInt(parts[0].trim()));
                    o.addProperty("max_duration", Integer.parseInt(parts[1].trim()));
                    map.add(id, o);
                }));

        cat.group(mapList("Enchantment caps",
                "One per line: enchantment id = max level. 0 removes the enchantment, -1 means no cap.",
                enchants, "enchantments", true, canEdit, dirty, rejected,
                (id, el) -> id + " = " + el.getAsInt(),
                (map, id, value) -> map.addProperty(id, Integer.parseInt(value))));

        JsonObject explosionSources = explosions.has("sources") ? explosions.getAsJsonObject("sources") : new JsonObject();
        cat.group(mapList("Explosion caps",
                "One per line: source = max power. 0 cancels the explosion. Sources: tnt, tnt_minecart, creeper, "
                        + "end_crystal, bed, respawn_anchor, wither, wither_skull, ghast.",
                explosions, "sources", false, canEdit, dirty, rejected,
                (id, el) -> id + " = " + el.getAsJsonObject().get("max_power").getAsFloat(),
                (map, id, value) -> {
                    JsonObject prev = explosionSources.has(id) ? explosionSources.getAsJsonObject(id) : null;
                    JsonObject o = new JsonObject();
                    o.addProperty("enabled", prev == null || !prev.has("enabled") || prev.get("enabled").getAsBoolean());
                    o.addProperty("max_power", Float.parseFloat(value));
                    map.add(id, o);
                }));

        cat.group(mapList("XP caps",
                "One per line: source = max xp per drop. -1 means no cap. Sources: all, entitiesKilling, blocksMining, "
                        + "villager_trading, furnace, fishing, breeding, xp_bottle.",
                xp, "sources", false, canEdit, dirty, rejected,
                (id, el) -> id + " = " + el.getAsInt(),
                (map, id, value) -> map.addProperty(id, Integer.parseInt(value))));

        OptionGroup.Builder weaponGroup = OptionGroup.createBuilder()
                .name(Text.literal("Weapon limits"))
                .description(OptionDescription.of(Text.literal(
                        "Per-weapon stat caps. These have several fields each, so edit them with /warden weapon.")))
                .collapsed(true);
        JsonObject weaponItems = weapons.has("items") ? weapons.getAsJsonObject("items") : new JsonObject();
        if (weaponItems.isEmpty()) {
            weaponGroup.option(LabelOption.create(Text.literal("(none configured)").formatted(Formatting.DARK_GRAY)));
        }
        for (Map.Entry<String, JsonElement> e : weaponItems.entrySet()) {
            StringBuilder sb = new StringBuilder(WardenMod.shortId(e.getKey())).append(": ");
            for (Map.Entry<String, JsonElement> f : e.getValue().getAsJsonObject().entrySet()) {
                sb.append(f.getKey()).append('=').append(f.getValue().getAsString()).append("  ");
            }
            weaponGroup.option(LabelOption.create(Text.literal(sb.toString().trim())));
        }
        cat.group(weaponGroup.build());

        if (usage != null) {
            List<String> blockedItems = new ArrayList<>();
            usage.getAsJsonArray("blocked").forEach(el -> blockedItems.add(el.getAsString()));
            cat.group(ListOption.<String>createBuilder()
                    .name(Text.literal("Blocked item usage"))
                    .description(OptionDescription.of(Text.literal(
                            "Item ids whose left and right clicks are blocked. Inventory and crafting remain allowed. "
                                    + "Remove any item limit of 0 to keep these items. Exempt players can still use them.")))
                    .binding(List.copyOf(blockedItems), () -> blockedItems, v -> {
                        blockedItems.clear();
                        blockedItems.addAll(v);
                        JsonArray arr = new JsonArray();
                        v.stream().map(String::trim).filter(s -> !s.isEmpty()).forEach(arr::add);
                        usage.add("blocked", arr);
                        dirty[0] = true;
                    })
                    .controller(StringControllerBuilder::create)
                    .initial("")
                    .available(canEdit)
                    .collapsed(true)
                    .build());
        }

        List<String> blockedDims = new ArrayList<>();
        if (dimensions.has("blocked")) {
            dimensions.getAsJsonArray("blocked").forEach(el -> blockedDims.add(el.getAsString()));
        }
        cat.group(ListOption.<String>createBuilder()
                .name(Text.literal("Blocked dimensions"))
                .description(OptionDescription.of(Text.literal(
                        "Dimension ids nobody can enter, e.g. minecraft:the_nether or minecraft:the_end. Exempt players still can.")))
                .binding(List.copyOf(blockedDims), () -> blockedDims, v -> {
                    blockedDims.clear();
                    blockedDims.addAll(v);
                    JsonArray arr = new JsonArray();
                    v.stream().map(String::trim).filter(s -> !s.isEmpty()).forEach(arr::add);
                    dimensions.add("blocked", arr);
                    dirty[0] = true;
                })
                .controller(StringControllerBuilder::create)
                .initial("")
                .available(canEdit)
                .collapsed(true)
                .build());

        if (exempt != null) {
            List<String> players = new ArrayList<>();
            if (exempt.has("players")) {
                exempt.getAsJsonArray("players").forEach(el -> players.add(el.getAsString()));
            }
            cat.group(ListOption.<String>createBuilder()
                    .name(Text.literal("Exempt players"))
                    .description(OptionDescription.of(Text.literal("Names or UUIDs that skip every limit.")))
                    .binding(List.copyOf(players), () -> players, v -> {
                        players.clear();
                        players.addAll(v);
                        JsonArray arr = new JsonArray();
                        v.stream().map(String::trim).filter(s -> !s.isEmpty()).forEach(arr::add);
                        exempt.add("players", arr);
                        dirty[0] = true;
                    })
                    .controller(StringControllerBuilder::create)
                    .initial("")
                    .available(canEdit)
                    .collapsed(true)
                    .build());
        }

        return cat.build();
    }

    private static Option<Boolean> toggle(String label, JsonObject section, String key, boolean canEdit, boolean[] dirty) {
        return Option.<Boolean>createBuilder()
                .name(Text.literal(label))
                .binding(true, () -> section.has(key) && section.get(key).getAsBoolean(), v -> {
                    section.addProperty(key, v);
                    dirty[0] = true;
                })
                .controller(TickBoxControllerBuilder::create)
                .available(canEdit)
                .build();
    }

    private static Option<Integer> intField(String label, JsonObject section, String key, int min, boolean canEdit, boolean[] dirty) {
        int def = section.has(key) ? section.get(key).getAsInt() : min;
        return Option.<Integer>createBuilder()
                .name(Text.literal(label))
                .binding(def, () -> section.has(key) ? section.get(key).getAsInt() : min, v -> {
                    section.addProperty(key, Math.max(min, v));
                    dirty[0] = true;
                })
                .controller(opt -> IntegerFieldControllerBuilder.create(opt).min(min))
                .available(canEdit)
                .build();
    }

    interface MapWriter {
        void write(JsonObject map, String key, String value);
    }

    /** A JSON object of id -> value, shown as editable "id = value" lines. */
    private static ListOption<String> mapList(String label, String help, JsonObject section, String mapKey,
                                              boolean namespaced, boolean canEdit, boolean[] dirty, List<String> rejected,
                                              BiFunction<String, JsonElement, String> format, MapWriter writer) {
        List<String> lines = new ArrayList<>();
        JsonObject map = section.has(mapKey) ? section.getAsJsonObject(mapKey) : new JsonObject();
        for (Map.Entry<String, JsonElement> e : map.entrySet()) {
            lines.add(format.apply(e.getKey(), e.getValue()));
        }
        return ListOption.<String>createBuilder()
                .name(Text.literal(label))
                .description(OptionDescription.of(Text.literal(help)))
                .binding(List.copyOf(lines), () -> lines, v -> {
                    lines.clear();
                    lines.addAll(v);
                    JsonObject rebuilt = new JsonObject();
                    for (String line : v) {
                        if (line.isBlank()) continue;
                        int eq = line.indexOf('=');
                        String id = eq < 0 ? "" : line.substring(0, eq).trim();
                        String value = eq < 0 ? "" : line.substring(eq + 1).trim();
                        if (id.isEmpty() || value.isEmpty()) {
                            rejected.add(line);
                            continue;
                        }
                        if (namespaced && !id.contains(":")) {
                            id = "minecraft:" + id;
                        }
                        try {
                            writer.write(rebuilt, id, value);
                        } catch (RuntimeException bad) {
                            rejected.add(line);
                        }
                    }
                    section.add(mapKey, rebuilt);
                    dirty[0] = true;
                })
                .controller(StringControllerBuilder::create)
                .initial("")
                .available(canEdit)
                .collapsed(true)
                .build();
    }
}
