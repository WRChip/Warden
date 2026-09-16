package com.warden;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.warden.config.WardenConfig;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.Set;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class WardenCommand {

    private static MutableText wardenPrefix() {
        return Text.literal("[")
                .append(Text.literal("WARDEN").formatted(Formatting.DARK_PURPLE, Formatting.BOLD))
                .append(Text.literal("] "));
    }

    private static final SimpleCommandExceptionType INVALID_ACTION_BAR_CATEGORY =
            new SimpleCommandExceptionType(wardenPrefix().append(Text.literal("Unknown action bar category").formatted(Formatting.RED)));
    private static final DynamicCommandExceptionType INVALID_WEAPON_STAT_TARGET =
            new DynamicCommandExceptionType(message -> wardenPrefix().append(Text.literal(message.toString()).formatted(Formatting.RED)));
    private static final DynamicCommandExceptionType UNKNOWN_ITEM =
            new DynamicCommandExceptionType(id -> wardenPrefix().append(Text.literal("Unknown item: " + id).formatted(Formatting.RED)));
    private static final DynamicCommandExceptionType UNKNOWN_EXPLOSION_SOURCE =
            new DynamicCommandExceptionType(id -> wardenPrefix().append(Text.literal("Unknown explosion source: " + id + " (expected one of " + String.join(", ", WardenCommand.EXPLOSION_SOURCES) + ")").formatted(Formatting.RED)));
    private static final DynamicCommandExceptionType PLAYER_NOT_FOUND =
            new DynamicCommandExceptionType(name -> wardenPrefix().append(Text.literal("Player not found or offline: " + name).formatted(Formatting.RED)));
    private static final List<String> RESET_CATEGORIES = List.of(
            "explosion", "item", "usage", "weapon", "enchant", "effect", "xp", "dimension", "actionbar", "exempt"
    );

    private static final List<String> EXPLOSION_SOURCES = List.of(
            "tnt", "tnt_minecart", "creeper", "bed", "respawn_anchor", "end_crystal", "ghast",
            "wither", "wither_skull"
    );

    private static final Map<String, List<String>> WEAPON_TARGETS = createWeaponTargets();
    private static final java.util.Set<String> ENCHANT_TARGET_SUGGESTIONS = createEnchantTargetSuggestions();
    private static final java.util.Set<String> WEAPON_TARGET_SUGGESTIONS = createWeaponTargetSuggestions();
    private static final List<String> ACTION_BAR_CATEGORIES = List.of("item", "weapon", "enchantment", "effect", "xp");

    private static Map<String, List<String>> createWeaponTargets() {
        Map<String, List<String>> targets = new LinkedHashMap<>();
        targets.put("sword", WardenToolType.SWORD.items);
        targets.put("axe", WardenToolType.AXE.items);
        targets.put("mace", WardenToolType.MACE.items);
        targets.put("spear", WardenToolType.SPEAR.items);
        targets.put("bow", WardenToolType.BOW.items);
        targets.put("crossbow", WardenToolType.CROSSBOW.items);
        targets.put("trident", WardenToolType.TRIDENT.items);
        List<String> all = new ArrayList<>();
        for (List<String> items : targets.values()) {
            all.addAll(items);
        }
        targets.put("weapon", List.copyOf(all));
        return Map.copyOf(targets);
    }

    private static java.util.Set<String> createEnchantTargetSuggestions() {
        java.util.Set<String> suggestions = new LinkedHashSet<>(WardenToolType.keys());
        for (WardenToolType type : WardenToolType.values()) {
            for (String id : type.items) {
                suggestions.add(id.substring("minecraft:".length()));
            }
        }
        return java.util.Collections.unmodifiableSet(suggestions);
    }

    private static java.util.Set<String> getConfiguredEnchantmentSuggestions() {
        java.util.Set<String> suggestions = new LinkedHashSet<>(WardenMod.CONFIG.enchantmentLimits.keySet());
        for (Map<String, Integer> overrides : WardenMod.CONFIG.itemEnchantmentOverrides.values()) {
            suggestions.addAll(overrides.keySet());
        }
        return suggestions;
    }

    private static java.util.Set<String> createWeaponTargetSuggestions() {
        java.util.Set<String> suggestions = new LinkedHashSet<>(WEAPON_TARGETS.keySet());
        for (List<String> items : WEAPON_TARGETS.values()) {
            for (String id : items) {
                suggestions.add(id.substring("minecraft:".length()));
            }
        }
        return java.util.Collections.unmodifiableSet(suggestions);
    }

    private static boolean isMeleeWeaponTarget(String itemId) {
        WardenToolType type = WardenToolType.byItemId(itemId);
        return type == WardenToolType.SWORD
                || type == WardenToolType.AXE
                || type == WardenToolType.MACE
                || type == WardenToolType.SPEAR
                || type == WardenToolType.TRIDENT;
    }

    private static boolean isRangedWeaponTarget(String itemId) {
        WardenToolType type = WardenToolType.byItemId(itemId);
        return type == WardenToolType.BOW
                || type == WardenToolType.CROSSBOW
                || type == WardenToolType.TRIDENT;
    }

    private static List<String> resolveTargetItems(String target) {
        WardenToolType type = WardenToolType.byKey(target);
        if (type != null) {
            return type.items;
        }
        if (target.contains(":")) {
            return List.of(target);
        }
        return List.of("minecraft:" + target);
    }

    private static void requireKnownItems(List<String> items) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        for (String itemId : items) {
            if (getItem(itemId) == null) throw UNKNOWN_ITEM.create(itemId);
        }
    }

    private static List<String> resolveWeaponItems(String target) {
        List<String> items = WEAPON_TARGETS.get(target);
        if (items != null) {
            return items;
        }
        if (target.contains(":")) {
            return List.of(target);
        }
        return List.of("minecraft:" + target);
    }

    private static void syncWeaponRules(ServerCommandSource source) {
        WardenNetworking.syncWeaponLimits(source.getServer());
    }

    private static boolean hasAdminPermission(ServerCommandSource src) {
        return src.getPermissions().hasPermission(new Permission.Level(PermissionLevel.GAMEMASTERS));
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(buildRootCommand());
        });
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> buildRootCommand() {
        var root = literal("warden");
        root.then(literal("reload").requires(WardenCommand::hasAdminPermission).executes(WardenCommand::reload));
        root.then(buildResetCommand());
        root.then(buildRestoreCommand());
        root.then(literal("status").executes(WardenCommand::status));
        root.then(literal("help").executes(WardenCommand::help));
        root.then(buildActionBarCommand());
        root.then(buildExplosionCommand());
        root.then(buildItemCommand());
        root.then(buildUsageCommand());
        root.then(buildWeaponCommand());
        root.then(buildEnchantCommand());
        root.then(buildEffectCommand());
        root.then(buildXpCommand());
        root.then(buildDimensionCommand());
        root.then(buildExemptCommand());
        root.then(buildConfigCommand());
        root.then(buildFreezeCommand());
        root.then(buildMuteCommand());
        root.then(buildVanishCommand());
        root.then(buildInventoryCommand());
        root.then(buildEnderChestCommand());
        return root;
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestOnlinePlayers(
            CommandContext<ServerCommandSource> ctx,
            com.mojang.brigadier.suggestion.SuggestionsBuilder builder
    ) {
        return CommandSource.suggestMatching(
                ctx.getSource().getServer().getPlayerManager().getPlayerList().stream().map(p -> p.getName().getString()).toList(), builder);
    }

    private static ServerPlayerEntity resolveOnlinePlayer(CommandContext<ServerCommandSource> ctx, String argName)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String name = StringArgumentType.getString(ctx, argName);
        ServerPlayerEntity target = ctx.getSource().getServer().getPlayerManager().getPlayer(name);
        if (target == null) {
            throw PLAYER_NOT_FOUND.create(name);
        }
        return target;
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> buildFreezeCommand() {
        return literal("freeze").requires(WardenCommand::hasAdminPermission)
                .then(argument("player", StringArgumentType.word())
                        .suggests(WardenCommand::suggestOnlinePlayers)
                        .executes(WardenCommand::freezeToggle));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> buildMuteCommand() {
        return literal("mute").requires(WardenCommand::hasAdminPermission)
                .then(argument("player", StringArgumentType.word())
                        .suggests(WardenCommand::suggestOnlinePlayers)
                        .executes(WardenCommand::muteToggle));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> buildVanishCommand() {
        return literal("vanish")
                .requires(src -> hasAdminPermission(src) && src.getEntity() instanceof ServerPlayerEntity)
                .executes(WardenCommand::vanishToggle);
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> buildInventoryCommand() {
        return literal("inv").requires(WardenCommand::hasAdminPermission)
                .then(argument("player", StringArgumentType.word())
                        .suggests(WardenCommand::suggestOnlinePlayers)
                        .executes(WardenCommand::openInventory));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> buildEnderChestCommand() {
        return literal("enderchest").requires(WardenCommand::hasAdminPermission)
                .then(argument("player", StringArgumentType.word())
                        .suggests(WardenCommand::suggestOnlinePlayers)
                        .executes(WardenCommand::openEnderChest));
    }

    private static int freezeToggle(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity target = resolveOnlinePlayer(ctx, "player");
        boolean frozen = WardenModeration.toggleFreeze(target);
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal(target.getName().getString()).formatted(Formatting.AQUA))
                .append(Text.literal(frozen ? " is now frozen." : " is no longer frozen.").formatted(frozen ? Formatting.RED : Formatting.GREEN)), true);
        target.sendMessage(wardenPrefix().append(Text.literal(frozen ? "You have been frozen by an admin." : "You have been unfrozen.")
                .formatted(frozen ? Formatting.RED : Formatting.GREEN)));
        return 1;
    }

    private static int muteToggle(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity target = resolveOnlinePlayer(ctx, "player");
        boolean muted = WardenModeration.toggleMute(target);
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal(target.getName().getString()).formatted(Formatting.AQUA))
                .append(Text.literal(muted ? " is now muted." : " is no longer muted.").formatted(muted ? Formatting.RED : Formatting.GREEN)), true);
        target.sendMessage(wardenPrefix().append(Text.literal(muted ? "You have been muted by an admin." : "You have been unmuted.")
                .formatted(muted ? Formatting.RED : Formatting.GREEN)));
        return 1;
    }

    private static int vanishToggle(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        boolean vanished = com.warden.vanish.Vanish.toggle(player);
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal(vanished ? "Vanish enabled." : "Vanish disabled.").formatted(vanished ? Formatting.GREEN : Formatting.RED)), false);
        return 1;
    }

    private static int openInventory(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity moderator = ctx.getSource().getPlayerOrThrow();
        ServerPlayerEntity target = resolveOnlinePlayer(ctx, "player");
        WardenModeration.openInventoryView(moderator, target);
        return 1;
    }

    private static int openEnderChest(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity moderator = ctx.getSource().getPlayerOrThrow();
        ServerPlayerEntity target = resolveOnlinePlayer(ctx, "player");
        WardenModeration.openEnderChestView(moderator, target);
        return 1;
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> buildResetCommand() {
        var reset = literal("reset").requires(WardenCommand::hasAdminPermission).executes(WardenCommand::resetAll);
        reset.then(argument("category", StringArgumentType.word())
                .suggests((ctx, builder) -> CommandSource.suggestMatching(RESET_CATEGORIES, builder))
                .executes(WardenCommand::resetCategory));
        return reset;
    }

    // /warden restore <countdownSeconds> <endTitle> <pvpDelayMinutes>
    // endTitle needs quotes if it has spaces, since it sits between two numeric arguments.
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> buildRestoreCommand() {
        return literal("restore").requires(WardenCommand::hasAdminPermission)
                .then(argument("countdownSeconds", IntegerArgumentType.integer(0))
                        .then(argument("endTitle", StringArgumentType.string())
                                .then(argument("pvpDelayMinutes", IntegerArgumentType.integer(0))
                                        .executes(WardenCommand::restore))));
    }

    private static int restore(CommandContext<ServerCommandSource> ctx) {
        if (WardenRestore.isActive()) {
            ctx.getSource().sendFeedback(() -> wardenPrefix().append(Text.literal("A restore is already in progress.").formatted(Formatting.RED)), false);
            return 0;
        }
        int countdownSeconds = IntegerArgumentType.getInteger(ctx, "countdownSeconds");
        String endTitle = StringArgumentType.getString(ctx, "endTitle");
        int pvpDelayMinutes = IntegerArgumentType.getInteger(ctx, "pvpDelayMinutes");
        WardenRestore.start(ctx.getSource().getServer(), countdownSeconds,
                Text.literal(endTitle).formatted(Formatting.GREEN, Formatting.BOLD), pvpDelayMinutes);
        ctx.getSource().sendFeedback(() -> wardenPrefix().append(Text.literal(
                "Restore started: " + countdownSeconds + "s countdown, PVP enables in " + pvpDelayMinutes + " min.")
                .formatted(Formatting.GREEN)), true);
        return 1;
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> buildActionBarCommand() {
        var actionBar = literal("actionbar")
                .requires(src -> src.getEntity() instanceof net.minecraft.server.network.ServerPlayerEntity)
                .then(literal("status").executes(WardenCommand::actionBarStatus));
        var category = argument("category", StringArgumentType.word())
                .suggests((ctx, builder) -> CommandSource.suggestMatching(ACTION_BAR_CATEGORIES, builder))
                .executes(WardenCommand::actionBarCategoryStatus);
        category.then(argument("value", BoolArgumentType.bool()).executes(WardenCommand::actionBarSet));
        actionBar.then(category);
        return actionBar;
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> buildExplosionCommand() {
        var explosion = literal("explosion").requires(WardenCommand::hasAdminPermission);
        var status = literal("status").executes(WardenCommand::statusExplosionAll);
        status.then(argument("source", StringArgumentType.word())
                .suggests((ctx, builder) -> CommandSource.suggestMatching(EXPLOSION_SOURCES, builder))
                .executes(WardenCommand::statusExplosion));
        explosion.then(status);
        explosion.then(literal("set")
                .then(argument("source", StringArgumentType.word())
                        .suggests((ctx, builder) -> CommandSource.suggestMatching(EXPLOSION_SOURCES, builder))
                        .then(literal("maxPower")
                                .then(argument("value", FloatArgumentType.floatArg(0f)).executes(WardenCommand::explosionSet))
                                .then(literal("default").executes(WardenCommand::explosionSetDefault)))));
        explosion.then(literal("disable")
                .then(argument("source", StringArgumentType.word())
                        .suggests((ctx, builder) -> CommandSource.suggestMatching(EXPLOSION_SOURCES, builder))
                        .executes(WardenCommand::explosionDisable)));
        explosion.then(literal("remove")
                .then(argument("source", StringArgumentType.word())
                        .suggests((ctx, builder) -> CommandSource.suggestMatching(WardenMod.CONFIG.explosionSources.keySet(), builder))
                        .executes(WardenCommand::explosionRemove)));
        return explosion;
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> buildItemCommand() {
        var item = literal("item").requires(WardenCommand::hasAdminPermission);
        var status = literal("status").executes(WardenCommand::statusItemAll);
        status.then(argument("item", IdentifierArgumentType.identifier())
                .suggests((ctx, builder) -> CommandSource.suggestMatching(WardenMod.CONFIG.itemLimits.keySet(), builder))
                .executes(WardenCommand::statusItem));
        item.then(status);
        item.then(literal("set")
                .then(argument("item", IdentifierArgumentType.identifier())
                        .suggests((ctx, builder) -> CommandSource.suggestIdentifiers(Registries.ITEM.getIds(), builder))
                        .then(literal("maxCount")
                                .then(argument("value", IntegerArgumentType.integer(0)).executes(WardenCommand::itemSet))
                                .then(literal("default").executes(WardenCommand::itemSetDefault)))));
        item.then(literal("disable")
                .then(argument("item", IdentifierArgumentType.identifier())
                        .suggests((ctx, builder) -> CommandSource.suggestIdentifiers(Registries.ITEM.getIds(), builder))
                        .executes(WardenCommand::itemDisable)));
        item.then(literal("remove")
                .then(argument("item", IdentifierArgumentType.identifier())
                        .suggests((ctx, builder) -> CommandSource.suggestMatching(WardenMod.CONFIG.itemLimits.keySet(), builder))
                        .executes(WardenCommand::itemRemove)));
        return item;
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> buildWeaponCommand() {
        var weapon = literal("weapon").requires(WardenCommand::hasAdminPermission);
        var status = literal("status").executes(WardenCommand::statusWeaponAll);
        status.then(argument("target", StringArgumentType.word())
                .suggests((ctx, builder) -> CommandSource.suggestMatching(WEAPON_TARGET_SUGGESTIONS, builder))
                .executes(WardenCommand::statusWeaponTarget));
        weapon.then(status);

        var target = argument("target", StringArgumentType.word())
                .suggests((ctx, builder) -> CommandSource.suggestMatching(WEAPON_TARGET_SUGGESTIONS, builder));
        var stat = argument("stat", StringArgumentType.word())
                .suggests((ctx, builder) -> {
                    String t = StringArgumentType.getString(ctx, "target");
                    List<String> items = resolveWeaponItems(t);
                    List<String> stats = new ArrayList<>();
                    if (items.stream().anyMatch(WardenCommand::isMeleeWeaponTarget)) {
                        stats.addAll(List.of("damage", "attackSpeed", "reach", "disableCooldown"));
                    }
                    if (items.stream().anyMatch(WardenCommand::isRangedWeaponTarget)) {
                        stats.addAll(List.of("projectileDamage", "rechargeTime"));
                    }
                    if (stats.isEmpty()) {
                        stats.addAll(List.of("damage", "attackSpeed", "reach", "disableCooldown", "projectileDamage", "rechargeTime"));
                    }
                    return CommandSource.suggestMatching(stats, builder);
                });
        stat.then(argument("value", FloatArgumentType.floatArg(0f)).executes(WardenCommand::weaponSetStat));
        stat.then(literal("default").executes(WardenCommand::weaponSetStatDefault));
        target.then(stat);
        weapon.then(literal("set").then(target));

        weapon.then(literal("disable")
                .then(argument("target", StringArgumentType.word())
                        .suggests((ctx, builder) -> CommandSource.suggestMatching(WEAPON_TARGET_SUGGESTIONS, builder))
                        .executes(WardenCommand::weaponDisable)));
        weapon.then(literal("remove")
                .then(argument("target", StringArgumentType.word())
                        .suggests((ctx, builder) -> CommandSource.suggestMatching(WEAPON_TARGET_SUGGESTIONS, builder))
                        .executes(WardenCommand::weaponRemove)));
        return weapon;
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> buildEnchantCommand() {
        var enchant = literal("enchant").requires(WardenCommand::hasAdminPermission);

        var status = literal("status").executes(WardenCommand::statusEnchantAll);
        var enchantment = argument("enchantment", IdentifierArgumentType.identifier())
                .suggests((ctx, builder) -> CommandSource.suggestMatching(getConfiguredEnchantmentSuggestions(), builder))
                .executes(WardenCommand::statusEnchantDetail);
        enchantment.then(literal("for")
                .then(argument("target", StringArgumentType.word())
                        .suggests((ctx, builder) -> CommandSource.suggestMatching(ENCHANT_TARGET_SUGGESTIONS, builder))
                        .executes(WardenCommand::statusEnchantForTarget)));
        status.then(enchantment);
        enchant.then(status);

        var setEnchantment = argument("enchantment", IdentifierArgumentType.identifier())
                .suggests(WardenCommand::suggestAllEnchantments);
        var maxLevel = literal("maxLevel");
        var setValue = argument("value", IntegerArgumentType.integer(-1)).executes(WardenCommand::enchantSet);
        setValue.then(literal("for")
                .then(argument("target", StringArgumentType.word())
                        .suggests((ctx, builder) -> CommandSource.suggestMatching(ENCHANT_TARGET_SUGGESTIONS, builder))
                        .executes(WardenCommand::enchantToolSet)));
        maxLevel.then(setValue);
        var setDefault = literal("default").executes(WardenCommand::enchantSetDefault);
        setDefault.then(literal("for")
                .then(argument("target", StringArgumentType.word())
                        .suggests((ctx, builder) -> CommandSource.suggestMatching(ENCHANT_TARGET_SUGGESTIONS, builder))
                        .executes(WardenCommand::enchantToolSetDefault)));
        maxLevel.then(setDefault);
        setEnchantment.then(maxLevel);
        enchant.then(literal("set").then(setEnchantment));

        var disableEnchantment = argument("enchantment", IdentifierArgumentType.identifier())
                .suggests(WardenCommand::suggestAllEnchantments)
                .executes(WardenCommand::enchantDisable);
        disableEnchantment.then(literal("for")
                .then(argument("target", StringArgumentType.word())
                        .suggests((ctx, builder) -> CommandSource.suggestMatching(ENCHANT_TARGET_SUGGESTIONS, builder))
                        .executes(WardenCommand::enchantToolDisable)));
        enchant.then(literal("disable").then(disableEnchantment));

        var removeEnchantment = argument("enchantment", IdentifierArgumentType.identifier())
                .suggests((ctx, builder) -> CommandSource.suggestMatching(getConfiguredEnchantmentSuggestions(), builder))
                .executes(WardenCommand::enchantRemove);
        removeEnchantment.then(literal("for")
                .then(argument("target", StringArgumentType.word())
                        .suggests((ctx, builder) -> CommandSource.suggestMatching(ENCHANT_TARGET_SUGGESTIONS, builder))
                        .executes(WardenCommand::enchantToolRemove)));
        enchant.then(literal("remove").then(removeEnchantment));
        return enchant;
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> buildEffectCommand() {
        var effect = literal("effect").requires(WardenCommand::hasAdminPermission);
        var status = literal("status").executes(WardenCommand::statusEffectAll);
        status.then(argument("effect", IdentifierArgumentType.identifier())
                .suggests(WardenCommand::suggestAllEffects)
                .executes(WardenCommand::statusEffect));
        effect.then(status);

        var setEffect = argument("effect", IdentifierArgumentType.identifier())
                .suggests(WardenCommand::suggestAllEffects);
        setEffect.then(literal("maxLevel")
                .then(argument("value", IntegerArgumentType.integer(0)).executes(WardenCommand::effectSetLevel))
                .then(literal("default").executes(WardenCommand::effectSetLevelDefault)));
        setEffect.then(literal("maxDuration")
                .then(argument("value", IntegerArgumentType.integer(0)).executes(WardenCommand::effectSetDuration))
                .then(literal("default").executes(WardenCommand::effectSetDurationDefault)));
        effect.then(literal("set").then(setEffect));

        effect.then(literal("disable")
                .then(argument("effect", IdentifierArgumentType.identifier())
                        .suggests(WardenCommand::suggestAllEffects)
                        .executes(WardenCommand::effectDisable)));
        effect.then(literal("remove")
                .then(argument("effect", IdentifierArgumentType.identifier())
                        .suggests((ctx, builder) -> CommandSource.suggestMatching(WardenMod.CONFIG.effectLimits.keySet(), builder))
                        .executes(WardenCommand::effectRemove)));
        return effect;
    }

    private static final List<String> XP_SOURCES = List.of("all", "villager_trading", "entitiesKilling", "blocksMining", "furnace", "fishing", "breeding", "xp_bottle");

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> buildXpCommand() {
        var xp = literal("xp").requires(WardenCommand::hasAdminPermission);
        var status = literal("status").executes(WardenCommand::statusXpAll);
        status.then(argument("source", StringArgumentType.word())
                .suggests(WardenCommand::suggestXpSource)
                .executes(WardenCommand::statusXp)
                .then(literal("for")
                        .then(argument("id", IdentifierArgumentType.identifier())
                                .suggests(WardenCommand::suggestXpId)
                                .executes(WardenCommand::statusXpFor))));
        xp.then(status);
        xp.then(literal("set")
                .then(argument("source", StringArgumentType.word())
                        .suggests(WardenCommand::suggestXpSource)
                        .then(literal("maxGain")
                                .then(argument("value", IntegerArgumentType.integer(-1))
                                        .executes(WardenCommand::xpSet)
                                        .then(literal("for")
                                                .then(argument("id", IdentifierArgumentType.identifier())
                                                        .suggests(WardenCommand::suggestXpId)
                                                        .executes(WardenCommand::xpSetFor)))))
                        .then(literal("default")
                                .executes(WardenCommand::xpSetDefault)
                                .then(literal("for")
                                        .then(argument("id", IdentifierArgumentType.identifier())
                                                .suggests(WardenCommand::suggestXpId)
                                                .executes(WardenCommand::xpSetDefaultFor))))));
        xp.then(literal("disable")
                .then(argument("source", StringArgumentType.word())
                        .suggests(WardenCommand::suggestXpSource)
                        .executes(WardenCommand::xpDisable)
                        .then(literal("for")
                                .then(argument("id", IdentifierArgumentType.identifier())
                                        .suggests(WardenCommand::suggestXpId)
                                        .executes(WardenCommand::xpDisableFor)))));
        xp.then(literal("remove")
                .then(argument("source", StringArgumentType.word())
                        .suggests(WardenCommand::suggestXpSource)
                        .executes(WardenCommand::xpRemove)
                        .then(literal("for")
                                .then(argument("id", IdentifierArgumentType.identifier())
                                        .suggests(WardenCommand::suggestXpId)
                                        .executes(WardenCommand::xpRemoveFor)))));
        return xp;
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestXpSource(
            CommandContext<ServerCommandSource> ctx,
            com.mojang.brigadier.suggestion.SuggestionsBuilder builder
    ) {
        return CommandSource.suggestMatching(XP_SOURCES, builder);
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestXpId(
            CommandContext<ServerCommandSource> ctx,
            com.mojang.brigadier.suggestion.SuggestionsBuilder builder
    ) {
        String source = StringArgumentType.getString(ctx, "source");
        if ("entitiesKilling".equals(source) || "breeding".equals(source)) {
            return CommandSource.suggestIdentifiers(Registries.ENTITY_TYPE.getIds(), builder);
        } else if ("blocksMining".equals(source)) {
            return CommandSource.suggestIdentifiers(Registries.BLOCK.getIds(), builder);
        }
        return builder.buildFuture();
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> buildDimensionCommand() {
        var dimension = literal("dimension").requires(WardenCommand::hasAdminPermission);
        dimension.then(literal("status").executes(WardenCommand::statusDimension));
        dimension.then(literal("block")
                .then(argument("dimension", StringArgumentType.string())
                        .suggests((ctx, builder) -> CommandSource.suggestIdentifiers(
                                ctx.getSource().getServer().getWorldRegistryKeys().stream().map(RegistryKey::getValue), builder))
                        .executes(WardenCommand::dimensionBlock)));
        dimension.then(literal("unblock")
                .then(argument("dimension", StringArgumentType.string())
                        .suggests((ctx, builder) -> CommandSource.suggestMatching(WardenMod.CONFIG.blockedDimensions, builder))
                        .executes(WardenCommand::dimensionUnblock)));
        return dimension;
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> buildExemptCommand() {
        var exempt = literal("exempt").requires(WardenCommand::hasAdminPermission);
        exempt.then(literal("status").executes(WardenCommand::statusExempt));
        exempt.then(literal("add")
                .then(argument("player", StringArgumentType.word())
                        .suggests((ctx, builder) -> CommandSource.suggestMatching(
                                ctx.getSource().getServer().getPlayerManager().getPlayerList()
                                        .stream().map(p -> p.getName().getString()).toList(), builder))
                        .executes(WardenCommand::exemptAdd)));
        exempt.then(literal("remove")
                .then(argument("player", StringArgumentType.word())
                        .suggests((ctx, builder) -> CommandSource.suggestMatching(
                                WardenMod.CONFIG.exemptPlayers.stream().map(e -> exemptDisplayName(ctx.getSource().getServer(), e)).toList(), builder))
                        .executes(WardenCommand::exemptRemove)));
        return exempt;
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> buildConfigCommand() {
        var config = literal("config").requires(WardenCommand::hasAdminPermission).executes(WardenCommand::configShowAll);
        config.then(literal("itemLimitsEnabled")
                .executes(ctx -> configShow(ctx, "itemLimitsEnabled", String.valueOf(WardenMod.CONFIG.itemLimitsEnabled)))
                .then(argument("value", BoolArgumentType.bool()).executes(WardenCommand::configItemLimitsEnabled)));
        config.then(literal("explosionLimitsEnabled")
                .executes(ctx -> configShow(ctx, "explosionLimitsEnabled", String.valueOf(WardenMod.CONFIG.explosionLimitsEnabled)))
                .then(argument("value", BoolArgumentType.bool()).executes(WardenCommand::configExplosionLimitsEnabled)));
        config.then(literal("weaponLimitsEnabled")
                .executes(ctx -> configShow(ctx, "weaponLimitsEnabled", String.valueOf(WardenMod.CONFIG.weaponLimitsEnabled)))
                .then(argument("value", BoolArgumentType.bool()).executes(WardenCommand::configWeaponLimitsEnabled)));
        config.then(literal("enchantmentLimitsEnabled")
                .executes(ctx -> configShow(ctx, "enchantmentLimitsEnabled", String.valueOf(WardenMod.CONFIG.enchantmentLimitsEnabled)))
                .then(argument("value", BoolArgumentType.bool()).executes(WardenCommand::configEnchantmentLimitsEnabled)));
        config.then(literal("effectLimitsEnabled")
                .executes(ctx -> configShow(ctx, "effectLimitsEnabled", String.valueOf(WardenMod.CONFIG.effectLimitsEnabled)))
                .then(argument("value", BoolArgumentType.bool()).executes(WardenCommand::configEffectLimitsEnabled)));
        config.then(literal("dimensionLimitsEnabled")
                .executes(ctx -> configShow(ctx, "dimensionLimitsEnabled", String.valueOf(WardenMod.CONFIG.dimensionLimitsEnabled)))
                .then(argument("value", BoolArgumentType.bool()).executes(WardenCommand::configDimensionLimitsEnabled)));
        config.then(literal("antiSeedCrackEnabled")
                .executes(ctx -> configShow(ctx, "antiSeedCrackEnabled", String.valueOf(WardenMod.CONFIG.antiSeedCrackEnabled)))
                .then(argument("value", BoolArgumentType.bool()).executes(WardenCommand::configAntiSeedCrackEnabled)));
        config.then(literal("chunkBanEnabled")
                .executes(ctx -> configShow(ctx, "chunkBanEnabled", String.valueOf(WardenMod.CONFIG.chunkBanEnabled)))
                .then(argument("value", BoolArgumentType.bool()).executes(ctx -> configSetBool(ctx, "chunkBanEnabled", v -> WardenMod.CONFIG.chunkBanEnabled = v))));
        config.then(literal("maxItemBytes")
                .executes(ctx -> configShow(ctx, "maxItemBytes", String.valueOf(WardenMod.CONFIG.maxItemBytes)))
                .then(argument("value", IntegerArgumentType.integer(1024)).executes(ctx -> configSetInt(ctx, "maxItemBytes", v -> WardenMod.CONFIG.maxItemBytes = v))));
        config.then(literal("maxBlockEntityBytes")
                .executes(ctx -> configShow(ctx, "maxBlockEntityBytes", String.valueOf(WardenMod.CONFIG.maxBlockEntityBytes)))
                .then(argument("value", IntegerArgumentType.integer(1024)).executes(ctx -> configSetInt(ctx, "maxBlockEntityBytes", v -> WardenMod.CONFIG.maxBlockEntityBytes = v))));
        config.then(literal("maxChunkBlockEntityBytes")
                .executes(ctx -> configShow(ctx, "maxChunkBlockEntityBytes", String.valueOf(WardenMod.CONFIG.maxChunkBlockEntityBytes)))
                .then(argument("value", IntegerArgumentType.integer(65536)).executes(ctx -> configSetInt(ctx, "maxChunkBlockEntityBytes", v -> WardenMod.CONFIG.maxChunkBlockEntityBytes = v))));
        config.then(literal("bucketDrainEnabled")
                .executes(ctx -> configShow(ctx, "bucketDrainEnabled", String.valueOf(WardenMod.CONFIG.bucketDrainEnabled)))
                .then(argument("value", BoolArgumentType.bool()).executes(ctx -> configSetBool(ctx, "bucketDrainEnabled", v -> WardenMod.CONFIG.bucketDrainEnabled = v))));
        config.then(literal("maxBucketDrains")
                .executes(ctx -> configShow(ctx, "maxBucketDrains", String.valueOf(WardenMod.CONFIG.maxBucketDrains)))
                .then(argument("value", IntegerArgumentType.integer(1)).executes(ctx -> configSetInt(ctx, "maxBucketDrains", v -> WardenMod.CONFIG.maxBucketDrains = v))));
        config.then(literal("bucketDrainWindowTicks")
                .executes(ctx -> configShow(ctx, "bucketDrainWindowTicks", String.valueOf(WardenMod.CONFIG.bucketDrainWindowTicks)))
                .then(argument("value", IntegerArgumentType.integer(20)).executes(ctx -> configSetInt(ctx, "bucketDrainWindowTicks", v -> WardenMod.CONFIG.bucketDrainWindowTicks = v))));
        config.then(literal("checkIntervalTicks")
                .executes(ctx -> configShow(ctx, "checkIntervalTicks", String.valueOf(WardenMod.CONFIG.checkIntervalTicks)))
                .then(argument("value", IntegerArgumentType.integer(1)).executes(WardenCommand::configCheckIntervalTicks)));
        config.then(literal("dropPickupDelay")
                .executes(ctx -> configShow(ctx, "dropPickupDelay", String.valueOf(WardenMod.CONFIG.dropPickupDelay)))
                .then(argument("value", IntegerArgumentType.integer(0)).executes(WardenCommand::configDropPickupDelay)));
        config.then(literal("deleteOverflowItem")
                .executes(ctx -> configShow(ctx, "deleteOverflowItem", String.valueOf(WardenMod.CONFIG.deleteOverflowItem)))
                .then(argument("value", BoolArgumentType.bool()).executes(WardenCommand::configDeleteOverflowItem)));
        config.then(literal("itemActionBarEnabled")
                .executes(ctx -> configShow(ctx, "itemActionBarEnabled", String.valueOf(WardenMod.CONFIG.itemActionBarEnabled)))
                .then(argument("value", BoolArgumentType.bool()).executes(WardenCommand::configItemActionBarEnabled)));
        config.then(literal("weaponActionBarEnabled")
                .executes(ctx -> configShow(ctx, "weaponActionBarEnabled", String.valueOf(WardenMod.CONFIG.weaponActionBarEnabled)))
                .then(argument("value", BoolArgumentType.bool()).executes(WardenCommand::configWeaponActionBarEnabled)));
        config.then(literal("enchantmentActionBarEnabled")
                .executes(ctx -> configShow(ctx, "enchantmentActionBarEnabled", String.valueOf(WardenMod.CONFIG.enchantmentActionBarEnabled)))
                .then(argument("value", BoolArgumentType.bool()).executes(WardenCommand::configEnchantmentActionBarEnabled)));
        config.then(literal("effectActionBarEnabled")
                .executes(ctx -> configShow(ctx, "effectActionBarEnabled", String.valueOf(WardenMod.CONFIG.effectActionBarEnabled)))
                .then(argument("value", BoolArgumentType.bool()).executes(WardenCommand::configEffectActionBarEnabled)));
        config.then(literal("xpActionBarEnabled")
                .executes(ctx -> configShow(ctx, "xpActionBarEnabled", String.valueOf(WardenMod.CONFIG.xpActionBarEnabled)))
                .then(argument("value", BoolArgumentType.bool()).executes(WardenCommand::configXpActionBarEnabled)));
        config.then(literal("exemptCreative")
                .executes(ctx -> configShow(ctx, "exemptCreative", String.valueOf(WardenMod.CONFIG.exemptCreative)))
                .then(argument("value", BoolArgumentType.bool()).executes(WardenCommand::configExemptCreative)));
        return config;
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestAllEnchantments(
            CommandContext<ServerCommandSource> ctx,
            com.mojang.brigadier.suggestion.SuggestionsBuilder builder
    ) {
        var reg = ctx.getSource().getServer().getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);
        return CommandSource.suggestIdentifiers(reg.streamKeys().map(k -> k.getValue()), builder);
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestAllEffects(
            CommandContext<ServerCommandSource> ctx,
            com.mojang.brigadier.suggestion.SuggestionsBuilder builder
    ) {
        var reg = ctx.getSource().getServer().getRegistryManager().getOrThrow(RegistryKeys.STATUS_EFFECT);
        return CommandSource.suggestIdentifiers(reg.streamKeys().map(k -> k.getValue()), builder);
    }

    private static int actionBarStatus(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var player = ctx.getSource().getPlayerOrThrow();
        MutableText response = wardenPrefix().append(Text.literal("Action bar notice preferences:").formatted(Formatting.GOLD));
        for (String category : ACTION_BAR_CATEGORIES) {
            boolean enabled = isActionBarEnabledForPlayer(player, category);
            response.append(Text.literal("\n  ").formatted(Formatting.GRAY))
                    .append(Text.literal(category).formatted(Formatting.YELLOW))
                    .append(Text.literal(" = ").formatted(Formatting.GRAY))
                    .append(Text.literal(enabled ? "ENABLED" : "DISABLED").formatted(enabled ? Formatting.GREEN : Formatting.RED));
        }
        ctx.getSource().sendFeedback(() -> response, false);
        return 1;
    }

    private static int actionBarCategoryStatus(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var player = ctx.getSource().getPlayerOrThrow();
        String category = getActionBarCategory(ctx);
        boolean enabled = isActionBarEnabledForPlayer(player, category);
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("actionbar.").formatted(Formatting.GRAY))
                .append(Text.literal(category).formatted(Formatting.YELLOW))
                .append(Text.literal(" = ").formatted(Formatting.GRAY))
                .append(Text.literal(enabled ? "ENABLED" : "DISABLED").formatted(enabled ? Formatting.GREEN : Formatting.RED)), false);
        return 1;
    }

    private static int actionBarSet(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var player = ctx.getSource().getPlayerOrThrow();
        String playerName = player.getName().getString();
        String key = player.getUuidAsString();
        String category = getActionBarCategory(ctx);
        boolean value = BoolArgumentType.getBool(ctx, "value");
        // older configs stored these by name; fold that entry into the uuid one
        Set<String> legacy = WardenMod.CONFIG.playerActionBarDisabled.remove(playerName);
        Set<String> disabled = WardenMod.CONFIG.playerActionBarDisabled.computeIfAbsent(key, k -> new LinkedHashSet<>());
        if (legacy != null) {
            disabled.addAll(legacy);
        }
        if (value) {
            disabled.remove(category);
            if (disabled.isEmpty()) {
                WardenMod.CONFIG.playerActionBarDisabled.remove(key);
            }
        } else {
            disabled.add(category);
        }
        WardenMod.CONFIG.save();
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("actionbar.").formatted(Formatting.GRAY))
                .append(Text.literal(category).formatted(Formatting.YELLOW))
                .append(Text.literal(" = ").formatted(Formatting.GRAY))
                .append(Text.literal(value ? "ENABLED" : "DISABLED").formatted(value ? Formatting.GREEN : Formatting.RED))
                .append(Text.literal(" for ").formatted(Formatting.GRAY))
                .append(Text.literal(playerName).formatted(Formatting.AQUA)), false);
        return 1;
    }

    private static String getActionBarCategory(CommandContext<ServerCommandSource> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String category = StringArgumentType.getString(ctx, "category");
        if (!ACTION_BAR_CATEGORIES.contains(category)) {
            throw INVALID_ACTION_BAR_CATEGORY.create();
        }
        return category;
    }

    private static boolean isActionBarEnabledForPlayer(ServerPlayerEntity player, String category) {
        Set<String> disabled = WardenMod.CONFIG.playerActionBarDisabled.get(player.getUuidAsString());
        if (disabled == null) {
            disabled = WardenMod.CONFIG.playerActionBarDisabled.get(player.getName().getString());
        }
        return disabled == null || !disabled.contains(category);
    }

    private static int status(CommandContext<ServerCommandSource> ctx) {
        WardenConfig cfg = WardenMod.CONFIG;
        MutableText response = wardenPrefix().append(Text.literal("Status:").formatted(Formatting.GOLD));
        response.append(Text.literal("\n  explosion limits: ").formatted(Formatting.GRAY))
                .append(Text.literal(cfg.explosionLimitsEnabled ? "ENABLED" : "DISABLED").formatted(cfg.explosionLimitsEnabled ? Formatting.GREEN : Formatting.RED));
        response.append(Text.literal("\n  item limits: ").formatted(Formatting.GRAY))
                .append(Text.literal(cfg.itemLimitsEnabled ? "ENABLED" : "DISABLED").formatted(cfg.itemLimitsEnabled ? Formatting.GREEN : Formatting.RED));
        response.append(Text.literal("\n  weapon limits: ").formatted(Formatting.GRAY))
                .append(Text.literal(cfg.weaponLimitsEnabled ? "ENABLED" : "DISABLED").formatted(cfg.weaponLimitsEnabled ? Formatting.GREEN : Formatting.RED));
        response.append(Text.literal("\n  enchantment limits: ").formatted(Formatting.GRAY))
                .append(Text.literal(cfg.enchantmentLimitsEnabled ? "ENABLED" : "DISABLED").formatted(cfg.enchantmentLimitsEnabled ? Formatting.GREEN : Formatting.RED));
        response.append(Text.literal("\n  effect limits: ").formatted(Formatting.GRAY))
                .append(Text.literal(cfg.effectLimitsEnabled ? "ENABLED" : "DISABLED").formatted(cfg.effectLimitsEnabled ? Formatting.GREEN : Formatting.RED));
        response.append(Text.literal("\n  dimension limits: ").formatted(Formatting.GRAY))
                .append(Text.literal(cfg.dimensionLimitsEnabled ? "ENABLED" : "DISABLED").formatted(cfg.dimensionLimitsEnabled ? Formatting.GREEN : Formatting.RED));

        ctx.getSource().sendFeedback(() -> response, false);
        return 1;
    }

    private static int statusExplosionAll(CommandContext<ServerCommandSource> ctx) {
        WardenConfig cfg = WardenMod.CONFIG;
        MutableText response = wardenPrefix().append(Text.literal("Explosion limits (").formatted(Formatting.GRAY))
                .append(Text.literal(cfg.explosionLimitsEnabled ? "ENABLED" : "DISABLED").formatted(cfg.explosionLimitsEnabled ? Formatting.GREEN : Formatting.RED))
                .append(Text.literal("):").formatted(Formatting.GRAY));

        if (cfg.explosionSources.isEmpty()) {
            response.append(Text.literal("\n  (none configured)").formatted(Formatting.DARK_GRAY));
        } else {
            for (var e : cfg.explosionSources.entrySet()) {
                response.append(Text.literal("\n  ").formatted(Formatting.GRAY))
                        .append(Text.literal(e.getKey()).formatted(Formatting.YELLOW))
                        .append(Text.literal(": enabled=").formatted(Formatting.GRAY))
                        .append(Text.literal(String.valueOf(e.getValue().enabled)).formatted(e.getValue().enabled ? Formatting.GREEN : Formatting.RED))
                        .append(Text.literal(", maxPower=").formatted(Formatting.GRAY))
                        .append(Text.literal(String.valueOf(e.getValue().maxPower)).formatted(Formatting.AQUA));
            }
        }
        ctx.getSource().sendFeedback(() -> response, false);
        return 1;
    }

    private static int statusExplosion(CommandContext<ServerCommandSource> ctx) {
        String source = StringArgumentType.getString(ctx, "source");
        WardenConfig.ExplosionSourceConfig cfg = WardenMod.CONFIG.explosionSources.get(source);
        if (cfg == null) {
            ctx.getSource().sendFeedback(() -> wardenPrefix()
                    .append(Text.literal("explosion.").formatted(Formatting.GRAY))
                    .append(Text.literal(source).formatted(Formatting.YELLOW))
                    .append(Text.literal(": not configured").formatted(Formatting.RED)), false);
        } else {
            ctx.getSource().sendFeedback(() -> wardenPrefix()
                    .append(Text.literal("explosion.").formatted(Formatting.GRAY))
                    .append(Text.literal(source).formatted(Formatting.YELLOW))
                    .append(Text.literal(": enabled=").formatted(Formatting.GRAY))
                    .append(Text.literal(String.valueOf(cfg.enabled)).formatted(cfg.enabled ? Formatting.GREEN : Formatting.RED))
                    .append(Text.literal(", maxPower=").formatted(Formatting.GRAY))
                    .append(Text.literal(String.valueOf(cfg.maxPower)).formatted(Formatting.AQUA)), false);
        }
        return 1;
    }

    private static int statusItemAll(CommandContext<ServerCommandSource> ctx) {
        WardenConfig cfg = WardenMod.CONFIG;
        MutableText response = wardenPrefix().append(Text.literal("Item limits (").formatted(Formatting.GRAY))
                .append(Text.literal(cfg.itemLimitsEnabled ? "ENABLED" : "DISABLED").formatted(cfg.itemLimitsEnabled ? Formatting.GREEN : Formatting.RED))
                .append(Text.literal("):").formatted(Formatting.GRAY));

        if (cfg.itemLimits.isEmpty()) {
            response.append(Text.literal("\n  (none configured)").formatted(Formatting.DARK_GRAY));
        } else {
            for (var e : cfg.itemLimits.entrySet()) {
                response.append(Text.literal("\n  ").formatted(Formatting.GRAY))
                        .append(Text.literal(e.getKey()).formatted(Formatting.YELLOW))
                        .append(Text.literal(": maxCount=").formatted(Formatting.GRAY))
                        .append(Text.literal(String.valueOf(e.getValue())).formatted(Formatting.AQUA));
            }
        }
        ctx.getSource().sendFeedback(() -> response, false);
        return 1;
    }

    private static int statusItem(CommandContext<ServerCommandSource> ctx) {
        String item = IdentifierArgumentType.getIdentifier(ctx, "item").toString();
        Integer limit = WardenMod.CONFIG.itemLimits.get(item);
        if (limit == null) {
            ctx.getSource().sendFeedback(() -> wardenPrefix()
                    .append(Text.literal("item.").formatted(Formatting.GRAY))
                    .append(Text.literal(item).formatted(Formatting.YELLOW))
                    .append(Text.literal(": not configured").formatted(Formatting.RED)), false);
        } else {
            ctx.getSource().sendFeedback(() -> wardenPrefix()
                    .append(Text.literal("item.").formatted(Formatting.GRAY))
                    .append(Text.literal(item).formatted(Formatting.YELLOW))
                    .append(Text.literal(": maxCount=").formatted(Formatting.GRAY))
                    .append(Text.literal(String.valueOf(limit)).formatted(Formatting.AQUA)), false);
        }
        return 1;
    }

    private static int statusWeaponAll(CommandContext<ServerCommandSource> ctx) {
        WardenConfig cfg = WardenMod.CONFIG;
        MutableText response = wardenPrefix().append(Text.literal("Weapon limits (").formatted(Formatting.GRAY))
                .append(Text.literal(cfg.weaponLimitsEnabled ? "ENABLED" : "DISABLED").formatted(cfg.weaponLimitsEnabled ? Formatting.GREEN : Formatting.RED))
                .append(Text.literal("):").formatted(Formatting.GRAY));

        if (cfg.weaponLimits.isEmpty()) {
            response.append(Text.literal("\n  (none configured)").formatted(Formatting.DARK_GRAY));
        } else {
            for (var e : cfg.weaponLimits.entrySet()) {
                response.append(Text.literal("\n  ").formatted(Formatting.GRAY))
                        .append(Text.literal(e.getKey()).formatted(Formatting.YELLOW))
                        .append(Text.literal(": ").formatted(Formatting.GRAY))
                        .append(Text.literal(formatWeaponConfig(e.getValue())).formatted(Formatting.AQUA));
            }
        }
        ctx.getSource().sendFeedback(() -> response, false);
        return 1;
    }

    private static int statusWeaponTarget(CommandContext<ServerCommandSource> ctx) {
        String target = StringArgumentType.getString(ctx, "target");
        List<String> items = resolveWeaponItems(target);
        MutableText response = wardenPrefix().append(Text.literal("Weapon limits for ").formatted(Formatting.GRAY))
                .append(Text.literal(target).formatted(Formatting.YELLOW))
                .append(Text.literal(":").formatted(Formatting.GRAY));

        for (String itemId : items) {
            WardenConfig.WeaponLimitConfig cfg = WardenMod.CONFIG.weaponLimits.get(itemId);
            response.append(Text.literal("\n  ").formatted(Formatting.GRAY))
                    .append(Text.literal(itemId).formatted(Formatting.YELLOW))
                    .append(Text.literal(": ").formatted(Formatting.GRAY))
                    .append(Text.literal(cfg == null ? "not configured" : formatWeaponConfig(cfg)).formatted(cfg == null ? Formatting.RED : Formatting.AQUA));
        }
        ctx.getSource().sendFeedback(() -> response, false);
        return 1;
    }

    private static int statusEnchantAll(CommandContext<ServerCommandSource> ctx) {
        WardenConfig cfg = WardenMod.CONFIG;
        MutableText response = wardenPrefix().append(Text.literal("Enchantment limits (").formatted(Formatting.GRAY))
                .append(Text.literal(cfg.enchantmentLimitsEnabled ? "ENABLED" : "DISABLED").formatted(cfg.enchantmentLimitsEnabled ? Formatting.GREEN : Formatting.RED))
                .append(Text.literal("):").formatted(Formatting.GRAY));

        if (cfg.enchantmentLimits.isEmpty()) {
            response.append(Text.literal("\n  (none)").formatted(Formatting.DARK_GRAY));
        } else {
            for (var e : cfg.enchantmentLimits.entrySet()) {
                response.append(Text.literal("\n  ").formatted(Formatting.GRAY))
                        .append(Text.literal(e.getKey()).formatted(Formatting.YELLOW))
                        .append(Text.literal(": maxLevel=").formatted(Formatting.GRAY))
                        .append(Text.literal(e.getValue() == -1 ? "unlimited" : String.valueOf(e.getValue())).formatted(e.getValue() == -1 ? Formatting.GREEN : Formatting.AQUA));
            }
        }
        if (!cfg.itemEnchantmentOverrides.isEmpty()) {
            response.append(Text.literal("\n  item overrides: ").formatted(Formatting.GRAY))
                    .append(Text.literal(String.join(", ", cfg.itemEnchantmentOverrides.keySet())).formatted(Formatting.YELLOW));
        }
        ctx.getSource().sendFeedback(() -> response, false);
        return 1;
    }

    private static int statusEnchantDetail(CommandContext<ServerCommandSource> ctx) {
        String enchId = IdentifierArgumentType.getIdentifier(ctx, "enchantment").toString();
        WardenConfig cfg = WardenMod.CONFIG;
        MutableText response = wardenPrefix().append(Text.literal("enchant.").formatted(Formatting.GRAY))
                .append(Text.literal(enchId).formatted(Formatting.YELLOW))
                .append(Text.literal(":").formatted(Formatting.GRAY));

        Integer global = cfg.enchantmentLimits.get(enchId);
        response.append(Text.literal("\n  global: ").formatted(Formatting.GRAY))
                .append(global != null ? Text.literal("maxLevel=" + (global == -1 ? "unlimited" : global)).formatted(global == -1 ? Formatting.GREEN : Formatting.AQUA) : Text.literal("not configured").formatted(Formatting.RED));

        for (var itemEntry : cfg.itemEnchantmentOverrides.entrySet()) {
            Integer override = itemEntry.getValue().get(enchId);
            if (override != null) {
                response.append(Text.literal("\n  ").formatted(Formatting.GRAY))
                        .append(Text.literal(itemEntry.getKey()).formatted(Formatting.YELLOW))
                        .append(Text.literal(": maxLevel=").formatted(Formatting.GRAY))
                        .append(Text.literal(override == -1 ? "unlimited" : String.valueOf(override)).formatted(override == -1 ? Formatting.GREEN : Formatting.AQUA));
            }
        }
        ctx.getSource().sendFeedback(() -> response, false);
        return 1;
    }

    private static int statusEnchantForTarget(CommandContext<ServerCommandSource> ctx) {
        String enchId = IdentifierArgumentType.getIdentifier(ctx, "enchantment").toString();
        String target = StringArgumentType.getString(ctx, "target");
        List<String> items = resolveTargetItems(target);
        WardenConfig cfg = WardenMod.CONFIG;
        Integer global = cfg.enchantmentLimits.get(enchId);
        MutableText response = wardenPrefix().append(Text.literal("enchant.").formatted(Formatting.GRAY))
                .append(Text.literal(enchId).formatted(Formatting.YELLOW))
                .append(Text.literal(" for ").formatted(Formatting.GRAY))
                .append(Text.literal(target).formatted(Formatting.YELLOW))
                .append(Text.literal(":").formatted(Formatting.GRAY));

        for (String itemId : items) {
            Map<String, Integer> overrides = cfg.itemEnchantmentOverrides.get(itemId);
            Integer override = overrides != null ? overrides.get(enchId) : null;
            response.append(Text.literal("\n  ").formatted(Formatting.GRAY))
                    .append(Text.literal(itemId).formatted(Formatting.YELLOW))
                    .append(Text.literal(": ").formatted(Formatting.GRAY));
            if (override != null) {
                response.append(Text.literal("maxLevel=" + (override == -1 ? "unlimited" : override)).formatted(override == -1 ? Formatting.GREEN : Formatting.AQUA))
                        .append(Text.literal(" (override)").formatted(Formatting.ITALIC, Formatting.DARK_GRAY));
            } else if (global != null) {
                response.append(Text.literal("maxLevel=" + (global == -1 ? "unlimited" : global)).formatted(global == -1 ? Formatting.GREEN : Formatting.AQUA))
                        .append(Text.literal(" (global)").formatted(Formatting.ITALIC, Formatting.DARK_GRAY));
            } else {
                response.append(Text.literal("not configured").formatted(Formatting.RED));
            }
        }
        ctx.getSource().sendFeedback(() -> response, false);
        return 1;
    }

    private static int reload(CommandContext<ServerCommandSource> ctx) {
        WardenMod.CONFIG = WardenConfig.load();
        syncWeaponRules(ctx.getSource());
        ctx.getSource().sendFeedback(() -> wardenPrefix().append(Text.literal("Config reloaded from disk.").formatted(Formatting.GREEN)), true);
        return 1;
    }

    private static int resetAll(CommandContext<ServerCommandSource> ctx) {
        WardenMod.CONFIG.resetAll();
        WardenMod.CONFIG.save();
        syncWeaponRules(ctx.getSource());
        ctx.getSource().sendFeedback(() -> wardenPrefix().append(Text.literal("Config reset to defaults (all categories wiped)").formatted(Formatting.GREEN)), true);
        return 1;
    }

    private static int resetCategory(CommandContext<ServerCommandSource> ctx) {
        String category = StringArgumentType.getString(ctx, "category");
        if (!WardenMod.CONFIG.resetCategory(category)) {
            ctx.getSource().sendError(wardenPrefix().append(Text.literal("Unknown reset category: " + category).formatted(Formatting.RED)));
            return 0;
        }
        WardenMod.CONFIG.save();
        if ("weapon".equals(category) || "enchant".equals(category)) {
            syncWeaponRules(ctx.getSource());
        }
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("Config reset to defaults for category: ").formatted(Formatting.GREEN))
                .append(Text.literal(category).formatted(Formatting.YELLOW)), true);
        return 1;
    }

    private static int explosionSet(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String source = StringArgumentType.getString(ctx, "source");
        if (!EXPLOSION_SOURCES.contains(source)) throw UNKNOWN_EXPLOSION_SOURCE.create(source);
        float value = FloatArgumentType.getFloat(ctx, "value");
        WardenConfig.ExplosionSourceConfig src = WardenMod.CONFIG.explosionSources.computeIfAbsent(
                source, k -> new WardenConfig.ExplosionSourceConfig(true, value));
        src.maxPower = value;
        src.enabled = true;
        WardenMod.CONFIG.save();
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("explosion.").formatted(Formatting.GRAY))
                .append(Text.literal(source).formatted(Formatting.YELLOW))
                .append(Text.literal(" maxPower = ").formatted(Formatting.GRAY))
                .append(Text.literal(String.valueOf(value)).formatted(Formatting.AQUA)), true);
        return 1;
    }

    private static int explosionSetDefault(CommandContext<ServerCommandSource> ctx) {
        String source = StringArgumentType.getString(ctx, "source");
        WardenMod.CONFIG.explosionSources.remove(source);
        WardenMod.CONFIG.save();
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("explosion.").formatted(Formatting.GRAY))
                .append(Text.literal(source).formatted(Formatting.YELLOW))
                .append(Text.literal(" reset to default (no cap)").formatted(Formatting.GREEN)), true);
        return 1;
    }

    private static int explosionDisable(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String source = StringArgumentType.getString(ctx, "source");
        if (!EXPLOSION_SOURCES.contains(source)) throw UNKNOWN_EXPLOSION_SOURCE.create(source);
        WardenConfig.ExplosionSourceConfig src = WardenMod.CONFIG.explosionSources.computeIfAbsent(
                source, k -> new WardenConfig.ExplosionSourceConfig(true, 0f));
        src.maxPower = 0f;
        src.enabled = true;
        WardenMod.CONFIG.save();
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("explosion.").formatted(Formatting.GRAY))
                .append(Text.literal(source).formatted(Formatting.YELLOW))
                .append(Text.literal(" maxPower = 0 (cancelled)").formatted(Formatting.RED)), true);
        return 1;
    }

    private static int explosionRemove(CommandContext<ServerCommandSource> ctx) {
        String source = StringArgumentType.getString(ctx, "source");
        boolean had = WardenMod.CONFIG.explosionSources.remove(source) != null;
        WardenMod.CONFIG.save();
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("explosion.").formatted(Formatting.GRAY))
                .append(Text.literal(source).formatted(Formatting.YELLOW))
                .append(Text.literal(had ? " cap disabled (default behavior: no cap)" : " already at default behavior (no cap)").formatted(Formatting.GRAY)), true);
        return 1;
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> buildUsageCommand() {
        var cmd = literal("usage").requires(WardenCommand::hasAdminPermission);
        cmd.then(literal("list").executes(ctx -> {
            ctx.getSource().sendFeedback(() -> wardenPrefix().append(Text.literal(
                    "Item usage " + (WardenMod.CONFIG.itemUsageEnabled ? "enabled" : "disabled")
                            + ": " + String.join(", ", WardenMod.CONFIG.blockedItemUsage))), false);
            return 1;
        }));
        cmd.then(literal("toggle").then(argument("enabled", BoolArgumentType.bool()).executes(ctx -> {
            WardenMod.CONFIG.itemUsageEnabled = BoolArgumentType.getBool(ctx, "enabled");
            WardenMod.CONFIG.save();
            WardenNetworking.syncWeaponLimits(ctx.getSource().getServer());
            ctx.getSource().sendFeedback(() -> wardenPrefix().append(Text.literal(
                    "Item usage restrictions: " + WardenMod.CONFIG.itemUsageEnabled)), true);
            return 1;
        })));
        for (String action : List.of("block", "allow")) {
            cmd.then(literal(action).then(argument("item", IdentifierArgumentType.identifier())
                    .suggests((ctx, builder) -> CommandSource.suggestIdentifiers(Registries.ITEM.getIds(), builder))
                    .executes(ctx -> {
                        Identifier id = IdentifierArgumentType.getIdentifier(ctx, "item");
                        if (!Registries.ITEM.containsId(id)) {
                            ctx.getSource().sendError(Text.literal("Unknown item: " + id));
                            return 0;
                        }
                        if (action.equals("block")) WardenMod.CONFIG.blockedItemUsage.add(id.toString());
                        else WardenMod.CONFIG.blockedItemUsage.remove(id.toString());
                        WardenMod.CONFIG.save();
                        WardenNetworking.syncWeaponLimits(ctx.getSource().getServer());
                        ctx.getSource().sendFeedback(() -> wardenPrefix().append(Text.literal(
                                id + " usage " + (action.equals("block") ? "blocked (inventory and crafting allowed)" : "allowed"))), true);
                        return 1;
                    })));
        }
        return cmd;
    }

    private static int itemSet(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String item = IdentifierArgumentType.getIdentifier(ctx, "item").toString();
        if (getItem(item) == null) throw UNKNOWN_ITEM.create(item);
        int value = IntegerArgumentType.getInteger(ctx, "value");
        WardenMod.CONFIG.itemLimits.put(item, value);
        WardenMod.CONFIG.save();
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("item.").formatted(Formatting.GRAY))
                .append(Text.literal(item).formatted(Formatting.YELLOW))
                .append(Text.literal(" maxCount = ").formatted(Formatting.GRAY))
                .append(Text.literal(String.valueOf(value)).formatted(Formatting.AQUA)), true);
        return 1;
    }

    private static int itemSetDefault(CommandContext<ServerCommandSource> ctx) {
        String itemId = IdentifierArgumentType.getIdentifier(ctx, "item").toString();
        WardenMod.CONFIG.itemLimits.remove(itemId);
        WardenMod.CONFIG.save();
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("item.").formatted(Formatting.GRAY))
                .append(Text.literal(itemId).formatted(Formatting.YELLOW))
                .append(Text.literal(" reset to default (no limit)").formatted(Formatting.GREEN)), true);
        return 1;
    }

    private static int itemDisable(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String item = IdentifierArgumentType.getIdentifier(ctx, "item").toString();
        if (getItem(item) == null) throw UNKNOWN_ITEM.create(item);
        WardenMod.CONFIG.itemLimits.put(item, 0);
        WardenMod.CONFIG.save();
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("item.").formatted(Formatting.GRAY))
                .append(Text.literal(item).formatted(Formatting.YELLOW))
                .append(Text.literal(" maxCount = 0 (blocked)").formatted(Formatting.RED)), true);
        return 1;
    }

    private static int itemRemove(CommandContext<ServerCommandSource> ctx) {
        String itemId = IdentifierArgumentType.getIdentifier(ctx, "item").toString();
        boolean had = WardenMod.CONFIG.itemLimits.remove(itemId) != null;
        WardenMod.CONFIG.save();
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("item.").formatted(Formatting.GRAY))
                .append(Text.literal(itemId).formatted(Formatting.YELLOW))
                .append(Text.literal(had ? " cap disabled (default: no cap)" : " already at default behavior (no cap)").formatted(Formatting.GRAY)), true);
        return 1;
    }

    private static int weaponSetStat(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String stat = StringArgumentType.getString(ctx, "stat");
        float value = FloatArgumentType.getFloat(ctx, "value");
        return switch (stat) {
            case "damage" -> applyWeaponLimit(ctx, "attackDamage", value);
            case "attackSpeed" -> applyWeaponLimit(ctx, "attackSpeed", value);
            case "reach" -> applyWeaponLimit(ctx, "reach", value);
            case "disableCooldown" -> applyWeaponCooldownLimit(ctx, Math.round(value));
            case "projectileDamage" -> applyWeaponProjectileDamage(ctx, value);
            case "rechargeTime" -> applyWeaponRechargeTime(ctx, Math.round(value));
            default -> {
                ctx.getSource().sendFeedback(() -> wardenPrefix()
                        .append(Text.literal("Unknown weapon stat: ").formatted(Formatting.RED))
                        .append(Text.literal(stat).formatted(Formatting.YELLOW)), false);
                yield 0;
            }
        };
    }

    private static int weaponSetStatDefault(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String stat = StringArgumentType.getString(ctx, "stat");
        String internalStat = switch (stat) {
            case "damage" -> "attackDamage";
            case "attackSpeed" -> "attackSpeed";
            case "reach" -> "reach";
            case "disableCooldown" -> "disableCooldown";
            case "projectileDamage" -> "projectileDamage";
            case "rechargeTime" -> "rechargeTime";
            default -> null;
        };
        if (internalStat == null) {
            ctx.getSource().sendFeedback(() -> wardenPrefix()
                    .append(Text.literal("Unknown weapon stat: ").formatted(Formatting.RED))
                    .append(Text.literal(stat).formatted(Formatting.YELLOW)), false);
            return 0;
        }
        return applyWeaponDefault(ctx, internalStat);
    }

    private static int weaponSetDamage(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return applyWeaponLimit(ctx, "attackDamage", FloatArgumentType.getFloat(ctx, "value"));
    }

    private static int weaponSetDamageDefault(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return applyWeaponDefault(ctx, "attackDamage");
    }

    private static int weaponSetAttackSpeed(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return applyWeaponLimit(ctx, "attackSpeed", FloatArgumentType.getFloat(ctx, "value"));
    }

    private static int weaponSetAttackSpeedDefault(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return applyWeaponDefault(ctx, "attackSpeed");
    }

    private static int weaponSetReach(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return applyWeaponLimit(ctx, "reach", FloatArgumentType.getFloat(ctx, "value"));
    }

    private static int weaponSetReachDefault(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return applyWeaponDefault(ctx, "reach");
    }

    private static int weaponSetDisableCooldown(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return applyWeaponCooldownLimit(ctx, IntegerArgumentType.getInteger(ctx, "value"));
    }

    private static int weaponSetDisableCooldownDefault(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return applyWeaponDefault(ctx, "disableCooldown");
    }

    private static int weaponSetProjectileDamage(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return applyWeaponProjectileDamage(ctx, FloatArgumentType.getFloat(ctx, "value"));
    }

    private static int weaponSetProjectileDamageDefault(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return applyWeaponDefault(ctx, "projectileDamage");
    }

    private static int weaponSetRechargeTime(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return applyWeaponRechargeTime(ctx, IntegerArgumentType.getInteger(ctx, "value"));
    }

    private static int weaponSetRechargeTimeDefault(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return applyWeaponDefault(ctx, "rechargeTime");
    }

    private static int applyWeaponLimit(CommandContext<ServerCommandSource> ctx, String stat, float value)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String target = StringArgumentType.getString(ctx, "target");
        List<String> items = resolveWeaponItems(target);
        requireKnownItems(items);
        validateWeaponStatTarget(stat, target, items);
        int applied = 0;
        int defaulted = 0;
        for (String itemId : items) {
            Item item = getItem(itemId);
            Double vanillaDouble = item == null ? null : getVanillaWeaponDouble(item, stat);
            Float vanillaFloat = item == null ? null : getVanillaWeaponFloat(item, stat);
            WardenConfig.WeaponLimitConfig cfg = WardenMod.CONFIG.weaponLimits.computeIfAbsent(
                    itemId, k -> new WardenConfig.WeaponLimitConfig()
            );

            if (("attackDamage".equals(stat) || "attackSpeed".equals(stat))
                    && vanillaDouble != null
                    && approximatelyEquals(vanillaDouble, value)) {
                if (clearWeaponStat(cfg, stat)) {
                    defaulted++;
                }
            } else if ("reach".equals(stat)
                    && vanillaFloat != null
                    && approximatelyEquals(vanillaFloat, value)) {
                if (clearWeaponStat(cfg, stat)) {
                    defaulted++;
                }
            } else {
                setWeaponStat(cfg, stat, value);
                applied++;
            }

            if (!cfg.isConfigured()) {
                WardenMod.CONFIG.weaponLimits.remove(itemId);
            }
        }
        WardenMod.CONFIG.save();
        syncWeaponRules(ctx.getSource());
        String label = items.size() == 1 ? items.get(0) : target + " (" + items.size() + " items)";
        MutableText responseText = wardenPrefix()
                .append(Text.literal("weapon.").formatted(Formatting.GRAY))
                .append(Text.literal(label).formatted(Formatting.YELLOW))
                .append(Text.literal(".").formatted(Formatting.GRAY))
                .append(Text.literal(stat).formatted(Formatting.YELLOW))
                .append(Text.literal(" = ").formatted(Formatting.GRAY))
                .append(Text.literal(String.valueOf(value)).formatted(Formatting.AQUA));

        if (applied > 0 && defaulted > 0) {
            responseText.append(Text.literal(" (" + applied + " applied, " + defaulted + " using default behavior: vanilla values)").formatted(Formatting.ITALIC, Formatting.DARK_GRAY));
        } else if (applied == 0 && defaulted > 0) {
            responseText = wardenPrefix()
                    .append(Text.literal("weapon.").formatted(Formatting.GRAY))
                    .append(Text.literal(label).formatted(Formatting.YELLOW))
                    .append(Text.literal(".").formatted(Formatting.GRAY))
                    .append(Text.literal(stat).formatted(Formatting.YELLOW))
                    .append(Text.literal(" cap disabled (default behavior: vanilla values)").formatted(Formatting.GRAY));
        }

        final MutableText finalResponse = responseText;
        ctx.getSource().sendFeedback(() -> finalResponse, true);
        return 1;
    }

    private static int applyWeaponCooldownLimit(CommandContext<ServerCommandSource> ctx, int value)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String target = StringArgumentType.getString(ctx, "target");
        List<String> items = resolveWeaponItems(target);
        requireKnownItems(items);
        validateWeaponStatTarget("disableCooldown", target, items);
        for (String itemId : items) {
            WardenConfig.WeaponLimitConfig cfg = WardenMod.CONFIG.weaponLimits.computeIfAbsent(
                    itemId, k -> new WardenConfig.WeaponLimitConfig()
            );
            cfg.disableCooldownTicks = value;
            if (!cfg.isConfigured()) {
                WardenMod.CONFIG.weaponLimits.remove(itemId);
            }
        }
        WardenMod.CONFIG.save();
        syncWeaponRules(ctx.getSource());
        // Clear any stuck cooldowns on online players caused by the old config value
        for (String itemId : items) {
            Item item = Registries.ITEM.get(Identifier.of(itemId));
            if (item != null) {
                for (ServerPlayerEntity player : ctx.getSource().getServer().getPlayerManager().getPlayerList()) {
                    player.getItemCooldownManager().remove(Registries.ITEM.getId(item));
                }
            }
        }
        String label = items.size() == 1 ? items.get(0) : target + " (" + items.size() + " items)";
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("weapon.").formatted(Formatting.GRAY))
                .append(Text.literal(label).formatted(Formatting.YELLOW))
                .append(Text.literal(".disableCooldown = ").formatted(Formatting.GRAY))
                .append(Text.literal(value + " ticks").formatted(Formatting.AQUA)), true);
        return 1;
    }

    private static int applyWeaponProjectileDamage(CommandContext<ServerCommandSource> ctx, float value)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String target = StringArgumentType.getString(ctx, "target");
        List<String> items = resolveWeaponItems(target);
        requireKnownItems(items);
        validateWeaponStatTarget("projectileDamage", target, items);
        for (String itemId : items) {
            WardenConfig.WeaponLimitConfig cfg = WardenMod.CONFIG.weaponLimits.computeIfAbsent(
                    itemId, k -> new WardenConfig.WeaponLimitConfig()
            );
            cfg.projectileDamage = (double) value;
        }
        WardenMod.CONFIG.save();
        syncWeaponRules(ctx.getSource());
        String label = items.size() == 1 ? items.get(0) : target + " (" + items.size() + " items)";
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("weapon.").formatted(Formatting.GRAY))
                .append(Text.literal(label).formatted(Formatting.YELLOW))
                .append(Text.literal(".projectileDamage = ").formatted(Formatting.GRAY))
                .append(Text.literal(String.valueOf(value)).formatted(Formatting.AQUA)), true);
        return 1;
    }

    private static int applyWeaponRechargeTime(CommandContext<ServerCommandSource> ctx, int value)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String target = StringArgumentType.getString(ctx, "target");
        List<String> items = resolveWeaponItems(target);
        validateWeaponStatTarget("rechargeTime", target, items);
        requireKnownItems(items);
        for (String itemId : items) {
            WardenConfig.WeaponLimitConfig cfg = WardenMod.CONFIG.weaponLimits.computeIfAbsent(
                    itemId, k -> new WardenConfig.WeaponLimitConfig()
            );
            cfg.rechargeTicks = value;
            if (!cfg.isConfigured()) {
                WardenMod.CONFIG.weaponLimits.remove(itemId);
            }
        }
        WardenMod.CONFIG.save();
        syncWeaponRules(ctx.getSource());
        String label = items.size() == 1 ? items.get(0) : target + " (" + items.size() + " items)";
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("weapon.").formatted(Formatting.GRAY))
                .append(Text.literal(label).formatted(Formatting.YELLOW))
                .append(Text.literal(".rechargeTime = ").formatted(Formatting.GRAY))
                .append(Text.literal(value + " ticks").formatted(Formatting.AQUA)), true);
        return 1;
    }

    private static int applyWeaponDefault(CommandContext<ServerCommandSource> ctx, String stat)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String target = StringArgumentType.getString(ctx, "target");
        List<String> items = resolveWeaponItems(target);
        requireKnownItems(items);
        validateWeaponStatTarget(stat, target, items);
        int reset = 0;
        for (String itemId : items) {
            WardenConfig.WeaponLimitConfig cfg = WardenMod.CONFIG.weaponLimits.get(itemId);
            if (cfg == null) {
                continue;
            }
            boolean changed = false;
            if ("attackDamage".equals(stat) && cfg.attackDamage != null) {
                cfg.attackDamage = null;
                changed = true;
            } else if ("attackSpeed".equals(stat) && cfg.attackSpeed != null) {
                cfg.attackSpeed = null;
                changed = true;
            } else if ("reach".equals(stat) && cfg.reach != null) {
                cfg.reach = null;
                changed = true;
            } else if ("disableCooldown".equals(stat) && cfg.disableCooldownTicks != null) {
                cfg.disableCooldownTicks = null;
                changed = true;
                // Clear stuck cooldowns on online players since the limit was removed
                Item item = Registries.ITEM.get(Identifier.of(itemId));
                if (item != null) {
                    for (ServerPlayerEntity player : ctx.getSource().getServer().getPlayerManager().getPlayerList()) {
                        player.getItemCooldownManager().remove(Registries.ITEM.getId(item));
                    }
                }
            } else if ("projectileDamage".equals(stat) && cfg.projectileDamage != null) {
                cfg.projectileDamage = null;
                changed = true;
            } else if ("rechargeTime".equals(stat) && cfg.rechargeTicks != null) {
                cfg.rechargeTicks = null;
                changed = true;
            }
            if (changed) {
                reset++;
                if (!cfg.isConfigured()) {
                    WardenMod.CONFIG.weaponLimits.remove(itemId);
                }
            }
        }
        WardenMod.CONFIG.save();
        syncWeaponRules(ctx.getSource());
        int resetCount = reset;
        String label = items.size() == 1 ? items.get(0) : target + " (" + items.size() + " items)";

        String vanillaValue = "vanilla values";
        if (items.size() == 1) {
            Item item = getItem(items.get(0));
            if (item != null) {
                Double dv = getVanillaWeaponDouble(item, stat);
                Float fv = getVanillaWeaponFloat(item, stat);
                if (dv != null) vanillaValue = "vanilla value: " + dv;
                else if (fv != null) vanillaValue = "vanilla value: " + fv;
                else if ("disableCooldown".equals(stat)) vanillaValue = "default behavior: no extra cooldown";
                else if ("projectileDamage".equals(stat)) vanillaValue = "default behavior: vanilla projectile logic";
                else if ("rechargeTime".equals(stat)) {
                    int recharge = items.get(0).contains("bow") ? 20 : (items.get(0).contains("trident") ? 10 : 0);
                    vanillaValue = recharge > 0 ? "vanilla value: " + recharge + " ticks" : "default behavior: no recharge";
                }
            }
        }

        final String finalVanilla = vanillaValue;
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("weapon.").formatted(Formatting.GRAY))
                .append(Text.literal(label).formatted(Formatting.YELLOW))
                .append(Text.literal(".").formatted(Formatting.GRAY))
                .append(Text.literal(stat).formatted(Formatting.YELLOW))
                .append(Text.literal(resetCount > 0 ? " cap disabled (default behavior: " + finalVanilla + ")" : " already at default behavior (" + finalVanilla + ")").formatted(Formatting.GRAY)), true);
        return 1;
    }

    private static int weaponDisable(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String target = StringArgumentType.getString(ctx, "target");
        List<String> items = resolveWeaponItems(target);
        requireKnownItems(items);
        for (String itemId : items) {
            WardenConfig.WeaponLimitConfig cfg = WardenMod.CONFIG.weaponLimits.computeIfAbsent(itemId, k -> new WardenConfig.WeaponLimitConfig());
            cfg.attackDamage = 0.0;
            cfg.attackSpeed = 0.0;
            cfg.reach = 0.0f;
            cfg.disableCooldownTicks = 0;
            cfg.projectileDamage = 0.0;
            cfg.rechargeTicks = 0;
        }
        WardenMod.CONFIG.save();
        syncWeaponRules(ctx.getSource());
        String label = items.size() == 1 ? items.get(0) : target + " (" + items.size() + " items)";
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("weapon.").formatted(Formatting.GRAY))
                .append(Text.literal(label).formatted(Formatting.YELLOW))
                .append(Text.literal(" all stats set to 0 (blocked)").formatted(Formatting.RED)), true);
        return 1;
    }

    private static int weaponRemove(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String target = StringArgumentType.getString(ctx, "target");
        List<String> items = resolveWeaponItems(target);
        requireKnownItems(items);
        int removed = 0;
        for (String itemId : items) {
            if (WardenMod.CONFIG.weaponLimits.remove(itemId) != null) {
                removed++;
            }
        }
        WardenMod.CONFIG.save();
        syncWeaponRules(ctx.getSource());
        int removedCount = removed;
        String label = items.size() == 1 ? items.get(0) : target + " (" + items.size() + " items)";
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("weapon.").formatted(Formatting.GRAY))
                .append(Text.literal(label).formatted(Formatting.YELLOW))
                .append(Text.literal(removedCount > 0 ? " cap disabled (default behavior: vanilla values)" : " already at default behavior (vanilla values)").formatted(Formatting.GRAY)), true);
        return 1;
    }

    private static int enchantSet(CommandContext<ServerCommandSource> ctx) {
        String enchId = IdentifierArgumentType.getIdentifier(ctx, "enchantment").toString();
        int level = IntegerArgumentType.getInteger(ctx, "value");
        WardenMod.CONFIG.enchantmentLimits.put(enchId, level);
        WardenMod.CONFIG.save();
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("enchant.").formatted(Formatting.GRAY))
                .append(Text.literal(enchId).formatted(Formatting.YELLOW))
                .append(Text.literal(" maxLevel = ").formatted(Formatting.GRAY))
                .append(Text.literal(level == -1 ? "unlimited" : String.valueOf(level)).formatted(level == -1 ? Formatting.GREEN : Formatting.AQUA)), true);
        return 1;
    }

    private static int enchantSetDefault(CommandContext<ServerCommandSource> ctx) {
        String enchId = IdentifierArgumentType.getIdentifier(ctx, "enchantment").toString();
        WardenMod.CONFIG.enchantmentLimits.put(enchId, -1);
        WardenMod.CONFIG.save();
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("enchant.").formatted(Formatting.GRAY))
                .append(Text.literal(enchId).formatted(Formatting.YELLOW))
                .append(Text.literal(" reset to default (unlimited)").formatted(Formatting.GREEN)), true);
        return 1;
    }

    private static int enchantDisable(CommandContext<ServerCommandSource> ctx) {
        String enchId = IdentifierArgumentType.getIdentifier(ctx, "enchantment").toString();
        WardenMod.CONFIG.enchantmentLimits.put(enchId, 0);
        WardenMod.CONFIG.save();
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("enchant.").formatted(Formatting.GRAY))
                .append(Text.literal(enchId).formatted(Formatting.YELLOW))
                .append(Text.literal(" maxLevel = 0 (stripped)").formatted(Formatting.RED)), true);
        return 1;
    }

    private static int enchantRemove(CommandContext<ServerCommandSource> ctx) {
        String enchId = IdentifierArgumentType.getIdentifier(ctx, "enchantment").toString();
        boolean had = WardenMod.CONFIG.enchantmentLimits.remove(enchId) != null;
        WardenMod.CONFIG.save();
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("enchant.").formatted(Formatting.GRAY))
                .append(Text.literal(enchId).formatted(Formatting.YELLOW))
                .append(Text.literal(had ? " cap disabled (default behavior: no cap)" : " already at default behavior (no cap)").formatted(Formatting.GRAY)), true);
        return 1;
    }

    private static int enchantToolSet(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String target = StringArgumentType.getString(ctx, "target");
        String enchId = IdentifierArgumentType.getIdentifier(ctx, "enchantment").toString();
        int level = IntegerArgumentType.getInteger(ctx, "value");
        List<String> items = resolveTargetItems(target);
        requireKnownItems(items);
        for (String itemId : items) {
            WardenMod.CONFIG.itemEnchantmentOverrides.computeIfAbsent(itemId, k -> new LinkedHashMap<>()).put(enchId, level);
        }
        WardenMod.CONFIG.save();
        syncWeaponRules(ctx.getSource());
        String label = items.size() == 1 ? items.get(0) : target + " (" + items.size() + " items)";
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("enchant.").formatted(Formatting.GRAY))
                .append(Text.literal(label).formatted(Formatting.YELLOW))
                .append(Text.literal(".").formatted(Formatting.GRAY))
                .append(Text.literal(enchId).formatted(Formatting.YELLOW))
                .append(Text.literal(" maxLevel = ").formatted(Formatting.GRAY))
                .append(Text.literal(level == -1 ? "unlimited" : String.valueOf(level)).formatted(level == -1 ? Formatting.GREEN : Formatting.AQUA)), true);
        return 1;
    }

    private static int enchantToolSetDefault(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String target = StringArgumentType.getString(ctx, "target");
        String enchId = IdentifierArgumentType.getIdentifier(ctx, "enchantment").toString();
        List<String> items = resolveTargetItems(target);
        requireKnownItems(items);
        for (String itemId : items) {
            WardenMod.CONFIG.itemEnchantmentOverrides.computeIfAbsent(itemId, k -> new LinkedHashMap<>()).put(enchId, -1);
        }
        WardenMod.CONFIG.save();
        syncWeaponRules(ctx.getSource());
        String label = items.size() == 1 ? items.get(0) : target + " (" + items.size() + " items)";
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("enchant.").formatted(Formatting.GRAY))
                .append(Text.literal(label).formatted(Formatting.YELLOW))
                .append(Text.literal(".").formatted(Formatting.GRAY))
                .append(Text.literal(enchId).formatted(Formatting.YELLOW))
                .append(Text.literal(" reset to default (unlimited, overrides global)").formatted(Formatting.GREEN)), true);
        return 1;
    }

    private static int enchantToolDisable(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String target = StringArgumentType.getString(ctx, "target");
        String enchId = IdentifierArgumentType.getIdentifier(ctx, "enchantment").toString();
        List<String> items = resolveTargetItems(target);
        requireKnownItems(items);
        for (String itemId : items) {
            WardenMod.CONFIG.itemEnchantmentOverrides.computeIfAbsent(itemId, k -> new LinkedHashMap<>()).put(enchId, 0);
        }
        WardenMod.CONFIG.save();
        syncWeaponRules(ctx.getSource());
        String label = items.size() == 1 ? items.get(0) : target + " (" + items.size() + " items)";
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("enchant.").formatted(Formatting.GRAY))
                .append(Text.literal(label).formatted(Formatting.YELLOW))
                .append(Text.literal(".").formatted(Formatting.GRAY))
                .append(Text.literal(enchId).formatted(Formatting.YELLOW))
                .append(Text.literal(" maxLevel = 0 (stripped)").formatted(Formatting.RED)), true);
        return 1;
    }

    private static int enchantToolRemove(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String target = StringArgumentType.getString(ctx, "target");
        String enchId = IdentifierArgumentType.getIdentifier(ctx, "enchantment").toString();
        List<String> items = resolveTargetItems(target);
        requireKnownItems(items);
        int removed = 0;
        for (String itemId : items) {
            Map<String, Integer> overrides = WardenMod.CONFIG.itemEnchantmentOverrides.get(itemId);
            if (overrides != null && overrides.remove(enchId) != null) {
                removed++;
                if (overrides.isEmpty()) {
                    WardenMod.CONFIG.itemEnchantmentOverrides.remove(itemId);
                }
            }
        }
        WardenMod.CONFIG.save();
        syncWeaponRules(ctx.getSource());
        int removedCount = removed;
        String label = items.size() == 1 ? items.get(0) : target + " (" + items.size() + " items)";
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("enchant.").formatted(Formatting.GRAY))
                .append(Text.literal(label).formatted(Formatting.YELLOW))
                .append(Text.literal(".").formatted(Formatting.GRAY))
                .append(Text.literal(enchId).formatted(Formatting.YELLOW))
                .append(Text.literal(removedCount > 0 ? " override removed (default behavior: follow global/no cap)" : " no override found (following global/no cap)").formatted(Formatting.GRAY)), true);
        return 1;
    }

    private static int statusEffectAll(CommandContext<ServerCommandSource> ctx) {
        WardenConfig cfg = WardenMod.CONFIG;
        MutableText response = wardenPrefix().append(Text.literal("Effect limits (").formatted(Formatting.GRAY))
                .append(Text.literal(cfg.effectLimitsEnabled ? "ENABLED" : "DISABLED").formatted(cfg.effectLimitsEnabled ? Formatting.GREEN : Formatting.RED))
                .append(Text.literal("):").formatted(Formatting.GRAY));

        if (cfg.effectLimits.isEmpty()) {
            response.append(Text.literal("\n  (none configured)").formatted(Formatting.DARK_GRAY));
        } else {
            for (var e : cfg.effectLimits.entrySet()) {
                response.append(Text.literal("\n  ").formatted(Formatting.GRAY))
                        .append(Text.literal(e.getKey()).formatted(Formatting.YELLOW))
                        .append(Text.literal(": maxLevel=").formatted(Formatting.GRAY))
                        .append(Text.literal(formatEffectLevel(e.getValue().maxLevel)).formatted(Formatting.AQUA))
                        .append(Text.literal(", maxDuration=").formatted(Formatting.GRAY))
                        .append(Text.literal(String.valueOf(e.getValue().maxDuration)).formatted(Formatting.AQUA));
            }
        }
        ctx.getSource().sendFeedback(() -> response, false);
        return 1;
    }

    private static int statusEffect(CommandContext<ServerCommandSource> ctx) {
        String effectId = IdentifierArgumentType.getIdentifier(ctx, "effect").toString();
        WardenConfig.EffectLimitConfig cfg = WardenMod.CONFIG.effectLimits.get(effectId);
        if (cfg == null) {
            ctx.getSource().sendFeedback(() -> wardenPrefix()
                    .append(Text.literal("effect.").formatted(Formatting.GRAY))
                    .append(Text.literal(effectId).formatted(Formatting.YELLOW))
                    .append(Text.literal(": not configured").formatted(Formatting.RED)), false);
        } else {
            ctx.getSource().sendFeedback(() -> wardenPrefix()
                    .append(Text.literal("effect.").formatted(Formatting.GRAY))
                    .append(Text.literal(effectId).formatted(Formatting.YELLOW))
                    .append(Text.literal(": maxLevel=").formatted(Formatting.GRAY))
                    .append(Text.literal(formatEffectLevel(cfg.maxLevel)).formatted(Formatting.AQUA))
                    .append(Text.literal(", maxDuration=").formatted(Formatting.GRAY))
                    .append(Text.literal(String.valueOf(cfg.maxDuration)).formatted(Formatting.AQUA)), false);
        }
        return 1;
    }

    private static int effectSetLevel(CommandContext<ServerCommandSource> ctx) {
        String effectId = IdentifierArgumentType.getIdentifier(ctx, "effect").toString();
        int value = IntegerArgumentType.getInteger(ctx, "value");
        WardenMod.CONFIG.effectLimits.computeIfAbsent(effectId, k -> new WardenConfig.EffectLimitConfig(-1, -1)).maxLevel = value;
        cleanupEffectLimit(effectId);
        WardenMod.CONFIG.save();
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("effect.").formatted(Formatting.GRAY))
                .append(Text.literal(effectId).formatted(Formatting.YELLOW))
                .append(Text.literal(" maxLevel = ").formatted(Formatting.GRAY))
                .append(Text.literal(String.valueOf(value)).formatted(Formatting.AQUA)), true);
        return 1;
    }

    private static int effectSetLevelDefault(CommandContext<ServerCommandSource> ctx) {
        String effectId = IdentifierArgumentType.getIdentifier(ctx, "effect").toString();
        WardenConfig.EffectLimitConfig cfg = WardenMod.CONFIG.effectLimits.get(effectId);
        if (cfg == null) {
            ctx.getSource().sendFeedback(() -> wardenPrefix()
                    .append(Text.literal("effect.").formatted(Formatting.GRAY))
                    .append(Text.literal(effectId).formatted(Formatting.YELLOW))
                    .append(Text.literal(" already at default behavior (no cap)").formatted(Formatting.GRAY)), true);
            return 1;
        }
        cfg.maxLevel = -1;
        cleanupEffectLimit(effectId);
        WardenMod.CONFIG.save();
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("effect.").formatted(Formatting.GRAY))
                .append(Text.literal(effectId).formatted(Formatting.YELLOW))
                .append(Text.literal(" level cap disabled (default behavior: no cap)").formatted(Formatting.GRAY)), true);
        return 1;
    }

    private static int effectSetDuration(CommandContext<ServerCommandSource> ctx) {
        String effectId = IdentifierArgumentType.getIdentifier(ctx, "effect").toString();
        int value = IntegerArgumentType.getInteger(ctx, "value");
        WardenMod.CONFIG.effectLimits.computeIfAbsent(effectId, k -> new WardenConfig.EffectLimitConfig(-1, -1)).maxDuration = value;
        cleanupEffectLimit(effectId);
        WardenMod.CONFIG.save();
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("effect.").formatted(Formatting.GRAY))
                .append(Text.literal(effectId).formatted(Formatting.YELLOW))
                .append(Text.literal(" maxDuration = ").formatted(Formatting.GRAY))
                .append(Text.literal(value + " ticks").formatted(Formatting.AQUA)), true);
        return 1;
    }

    private static int effectSetDurationDefault(CommandContext<ServerCommandSource> ctx) {
        String effectId = IdentifierArgumentType.getIdentifier(ctx, "effect").toString();
        WardenConfig.EffectLimitConfig cfg = WardenMod.CONFIG.effectLimits.get(effectId);
        if (cfg == null) {
            ctx.getSource().sendFeedback(() -> wardenPrefix()
                    .append(Text.literal("effect.").formatted(Formatting.GRAY))
                    .append(Text.literal(effectId).formatted(Formatting.YELLOW))
                    .append(Text.literal(" already at default behavior (no cap)").formatted(Formatting.GRAY)), true);
            return 1;
        }
        cfg.maxDuration = -1;
        cleanupEffectLimit(effectId);
        WardenMod.CONFIG.save();
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("effect.").formatted(Formatting.GRAY))
                .append(Text.literal(effectId).formatted(Formatting.YELLOW))
                .append(Text.literal(" duration cap disabled (default behavior: no cap)").formatted(Formatting.GRAY)), true);
        return 1;
    }

    private static int effectDisable(CommandContext<ServerCommandSource> ctx) {
        String effectId = IdentifierArgumentType.getIdentifier(ctx, "effect").toString();
        WardenConfig.EffectLimitConfig cfg = WardenMod.CONFIG.effectLimits.computeIfAbsent(
                effectId, k -> new WardenConfig.EffectLimitConfig(0, 0));
        cfg.maxLevel = 0;
        cfg.maxDuration = 0;
        WardenMod.CONFIG.save();
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("effect.").formatted(Formatting.GRAY))
                .append(Text.literal(effectId).formatted(Formatting.YELLOW))
                .append(Text.literal(" maxLevel = 0, maxDuration = 0 (blocked)").formatted(Formatting.RED)), true);
        return 1;
    }

    private static int effectRemove(CommandContext<ServerCommandSource> ctx) {
        String effectId = IdentifierArgumentType.getIdentifier(ctx, "effect").toString();
        boolean had = WardenMod.CONFIG.effectLimits.remove(effectId) != null;
        WardenMod.CONFIG.save();
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("effect.").formatted(Formatting.GRAY))
                .append(Text.literal(effectId).formatted(Formatting.YELLOW))
                .append(Text.literal(had ? " cap disabled (default behavior: no cap)" : " already at default behavior (no cap)").formatted(Formatting.GRAY)), true);
        return 1;
    }

    private static int statusXpAll(CommandContext<ServerCommandSource> ctx) {
        MutableText response = wardenPrefix().append(Text.literal("XP Limits Status:").formatted(Formatting.GOLD));
        response.append(Text.literal("\n  Global: ").formatted(Formatting.GRAY))
                .append(Text.literal(WardenMod.CONFIG.xpLimitsEnabled ? "ENABLED" : "DISABLED")
                        .formatted(WardenMod.CONFIG.xpLimitsEnabled ? Formatting.GREEN : Formatting.RED));

        if (!WardenMod.CONFIG.xpLimits.isEmpty()) {
            response.append(Text.literal("\n  Sources:").formatted(Formatting.GOLD));
            for (Map.Entry<String, Integer> entry : WardenMod.CONFIG.xpLimits.entrySet()) {
                response.append(Text.literal("\n  - ").formatted(Formatting.GRAY))
                        .append(Text.literal(entry.getKey()).formatted(Formatting.AQUA))
                        .append(Text.literal(": ").formatted(Formatting.GRAY))
                        .append(Text.literal(entry.getValue() == -1 ? "unlimited" : String.valueOf(entry.getValue())).formatted(entry.getValue() == -1 ? Formatting.GREEN : Formatting.YELLOW))
                        .append(Text.literal(" maxGain").formatted(Formatting.DARK_GRAY));
            }
        }
        if (!WardenMod.CONFIG.xpOverrides.isEmpty()) {
            for (Map.Entry<String, Map<String, Integer>> sourceEntry : WardenMod.CONFIG.xpOverrides.entrySet()) {
                response.append(Text.literal("\n  Overrides for ").formatted(Formatting.GOLD))
                        .append(Text.literal(sourceEntry.getKey()).formatted(Formatting.AQUA))
                        .append(Text.literal(":").formatted(Formatting.GOLD));
                for (Map.Entry<String, Integer> entry : sourceEntry.getValue().entrySet()) {
                    response.append(Text.literal("\n  - ").formatted(Formatting.GRAY))
                            .append(Text.literal(WardenMod.shortId(entry.getKey())).formatted(Formatting.YELLOW))
                            .append(Text.literal(": ").formatted(Formatting.GRAY))
                            .append(Text.literal(entry.getValue() == -1 ? "unlimited" : String.valueOf(entry.getValue())).formatted(entry.getValue() == -1 ? Formatting.GREEN : Formatting.AQUA))
                            .append(Text.literal(" maxGain").formatted(Formatting.DARK_GRAY));
                }
            }
        }

        if (WardenMod.CONFIG.xpLimits.isEmpty() && WardenMod.CONFIG.xpOverrides.isEmpty()) {
            response.append(Text.literal("\n  No XP limits configured").formatted(Formatting.DARK_GRAY));
        }
        ctx.getSource().sendFeedback(() -> response, false);
        return 1;
    }

    private static int statusXpFor(CommandContext<ServerCommandSource> ctx) {
        String source = StringArgumentType.getString(ctx, "source");
        String id = IdentifierArgumentType.getIdentifier(ctx, "id").toString();
        Integer limit = null;
        Map<String, Integer> sourceOverrides = WardenMod.CONFIG.xpOverrides.get(source);
        if (sourceOverrides != null) {
            limit = sourceOverrides.get(id);
        }

        MutableText response = wardenPrefix().append(Text.literal("XP Limit (").formatted(Formatting.GOLD))
                .append(Text.literal(source).formatted(Formatting.AQUA))
                .append(Text.literal(" for ").formatted(Formatting.GRAY))
                .append(Text.literal(WardenMod.shortId(id)).formatted(Formatting.YELLOW))
                .append(Text.literal("): ").formatted(Formatting.GOLD));

        if (limit == null) {
            response.append(Text.literal("FOLLOWS SOURCE").formatted(Formatting.GRAY));
        } else if (limit == -1) {
            response.append(Text.literal("UNLIMITED").formatted(Formatting.GREEN));
        } else {
            response.append(Text.literal(String.valueOf(limit)).formatted(Formatting.YELLOW))
                    .append(Text.literal(" maxGain").formatted(Formatting.DARK_GRAY));
        }
        ctx.getSource().sendFeedback(() -> response, false);
        return 1;
    }

    private static int xpSetFor(CommandContext<ServerCommandSource> ctx) {
        String source = StringArgumentType.getString(ctx, "source");
        String id = IdentifierArgumentType.getIdentifier(ctx, "id").toString();
        int value = IntegerArgumentType.getInteger(ctx, "value");
        WardenMod.CONFIG.xpOverrides.computeIfAbsent(source, k -> new LinkedHashMap<>()).put(id, value);
        WardenMod.CONFIG.save();
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("XP Dropped (").formatted(Formatting.GRAY))
                .append(Text.literal(source).formatted(Formatting.AQUA))
                .append(Text.literal(" for ").formatted(Formatting.GRAY))
                .append(Text.literal(WardenMod.shortId(id)).formatted(Formatting.YELLOW))
                .append(Text.literal(") capped at ").formatted(Formatting.GRAY))
                .append(Text.literal(value == -1 ? "unlimited" : String.valueOf(value)).formatted(value == -1 ? Formatting.GREEN : Formatting.YELLOW))
                .append(Text.literal(" maxGain").formatted(Formatting.GRAY)), true);
        return 1;
    }

    private static int xpSetDefaultFor(CommandContext<ServerCommandSource> ctx) {
        String source = StringArgumentType.getString(ctx, "source");
        String id = IdentifierArgumentType.getIdentifier(ctx, "id").toString();
        WardenMod.CONFIG.xpOverrides.computeIfAbsent(source, k -> new LinkedHashMap<>()).put(id, -1);
        WardenMod.CONFIG.save();
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("XP Dropped (").formatted(Formatting.GRAY))
                .append(Text.literal(source).formatted(Formatting.AQUA))
                .append(Text.literal(" for ").formatted(Formatting.GRAY))
                .append(Text.literal(WardenMod.shortId(id)).formatted(Formatting.YELLOW))
                .append(Text.literal(") reset to default (unlimited)").formatted(Formatting.GREEN)), true);
        return 1;
    }

    private static int xpDisableFor(CommandContext<ServerCommandSource> ctx) {
        String source = StringArgumentType.getString(ctx, "source");
        String id = IdentifierArgumentType.getIdentifier(ctx, "id").toString();
        WardenMod.CONFIG.xpOverrides.computeIfAbsent(source, k -> new LinkedHashMap<>()).put(id, 0);
        WardenMod.CONFIG.save();
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("XP Dropped (").formatted(Formatting.GRAY))
                .append(Text.literal(source).formatted(Formatting.AQUA))
                .append(Text.literal(" for ").formatted(Formatting.GRAY))
                .append(Text.literal(WardenMod.shortId(id)).formatted(Formatting.YELLOW))
                .append(Text.literal(") disabled").formatted(Formatting.RED)), true);
        return 1;
    }

    private static int xpRemoveFor(CommandContext<ServerCommandSource> ctx) {
        String source = StringArgumentType.getString(ctx, "source");
        String id = IdentifierArgumentType.getIdentifier(ctx, "id").toString();
        Map<String, Integer> sourceOverrides = WardenMod.CONFIG.xpOverrides.get(source);
        boolean removed = false;
        if (sourceOverrides != null) {
            removed = sourceOverrides.remove(id) != null;
            if (sourceOverrides.isEmpty()) {
                WardenMod.CONFIG.xpOverrides.remove(source);
            }
        }
        final boolean had = removed;
        WardenMod.CONFIG.save();
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("XP Dropped override (").formatted(Formatting.GRAY))
                .append(Text.literal(source).formatted(Formatting.AQUA))
                .append(Text.literal(" for ").formatted(Formatting.GRAY))
                .append(Text.literal(WardenMod.shortId(id)).formatted(Formatting.YELLOW))
                .append(Text.literal(had ? ") removed (now follows " + source + ")" : ") already at default behavior").formatted(Formatting.GRAY)), true);
        return 1;
    }

    private static int statusXp(CommandContext<ServerCommandSource> ctx) {
        String source = StringArgumentType.getString(ctx, "source");
        Integer limit = WardenMod.CONFIG.xpLimits.get(source);

        MutableText response = wardenPrefix().append(Text.literal("XP Limit (").formatted(Formatting.GOLD))
                .append(Text.literal(source).formatted(Formatting.AQUA))
                .append(Text.literal("): ").formatted(Formatting.GOLD));

        if (limit == null) {
            response.append(Text.literal("all".equals(source) || !WardenMod.CONFIG.xpLimits.containsKey("all") ? "NOT SET (no cap)" : "NOT SET (follows all)").formatted(Formatting.GRAY));
        } else if (limit == -1) {
            response.append(Text.literal("UNLIMITED").formatted(Formatting.GREEN));
        } else {
            response.append(Text.literal(String.valueOf(limit)).formatted(Formatting.YELLOW))
                    .append(Text.literal(" maxGain").formatted(Formatting.DARK_GRAY));
        }
        ctx.getSource().sendFeedback(() -> response, false);
        return 1;
    }

    private static int xpSet(CommandContext<ServerCommandSource> ctx) {
        String source = StringArgumentType.getString(ctx, "source");
        int value = IntegerArgumentType.getInteger(ctx, "value");
        WardenMod.CONFIG.xpLimits.put(source, value);
        WardenMod.CONFIG.save();
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("XP Dropped (").formatted(Formatting.GRAY))
                .append(Text.literal(source).formatted(Formatting.AQUA))
                .append(Text.literal(") capped at ").formatted(Formatting.GRAY))
                .append(Text.literal(value == -1 ? "unlimited" : String.valueOf(value)).formatted(value == -1 ? Formatting.GREEN : Formatting.YELLOW))
                .append(Text.literal(" maxGain").formatted(Formatting.GRAY)), true);
        return 1;
    }

    private static int xpSetDefault(CommandContext<ServerCommandSource> ctx) {
        String source = StringArgumentType.getString(ctx, "source");
        WardenMod.CONFIG.xpLimits.put(source, -1);
        WardenMod.CONFIG.save();
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("XP Dropped (").formatted(Formatting.GRAY))
                .append(Text.literal(source).formatted(Formatting.AQUA))
                .append(Text.literal(") reset to default (unlimited)").formatted(Formatting.GREEN)), true);
        return 1;
    }

    private static int xpDisable(CommandContext<ServerCommandSource> ctx) {
        String source = StringArgumentType.getString(ctx, "source");
        WardenMod.CONFIG.xpLimits.put(source, 0);
        WardenMod.CONFIG.save();
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("XP Dropped (").formatted(Formatting.GRAY))
                .append(Text.literal(source).formatted(Formatting.AQUA))
                .append(Text.literal(") disabled").formatted(Formatting.RED)), true);
        return 1;
    }

    private static int xpRemove(CommandContext<ServerCommandSource> ctx) {
        String source = StringArgumentType.getString(ctx, "source");
        boolean had = WardenMod.CONFIG.xpLimits.remove(source) != null;
        WardenMod.CONFIG.save();
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("XP Dropped (").formatted(Formatting.GRAY))
                .append(Text.literal(source).formatted(Formatting.AQUA))
                .append(Text.literal(had ? ") cap disabled (default behavior: no cap)" : ") already at default behavior (no cap)").formatted(Formatting.GRAY)), true);
        return 1;
    }

    private static int configShow(CommandContext<ServerCommandSource> ctx, String key, String value) {
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal(key).formatted(Formatting.YELLOW))
                .append(Text.literal(" = ").formatted(Formatting.GRAY))
                .append(Text.literal(value).formatted(Formatting.AQUA)), false);
        return 1;
    }

    private static int configShowAll(CommandContext<ServerCommandSource> ctx) {
        WardenConfig cfg = WardenMod.CONFIG;
        MutableText response = wardenPrefix().append(Text.literal("Config:").formatted(Formatting.GOLD));
        response.append(Text.literal("\n  itemLimitsEnabled = ").formatted(Formatting.GRAY))
                .append(Text.literal(String.valueOf(cfg.itemLimitsEnabled)).formatted(cfg.itemLimitsEnabled ? Formatting.GREEN : Formatting.RED));
        response.append(Text.literal("\n  explosionLimitsEnabled = ").formatted(Formatting.GRAY))
                .append(Text.literal(String.valueOf(cfg.explosionLimitsEnabled)).formatted(cfg.explosionLimitsEnabled ? Formatting.GREEN : Formatting.RED));
        response.append(Text.literal("\n  weaponLimitsEnabled = ").formatted(Formatting.GRAY))
                .append(Text.literal(String.valueOf(cfg.weaponLimitsEnabled)).formatted(cfg.weaponLimitsEnabled ? Formatting.GREEN : Formatting.RED));
        response.append(Text.literal("\n  enchantmentLimitsEnabled = ").formatted(Formatting.GRAY))
                .append(Text.literal(String.valueOf(cfg.enchantmentLimitsEnabled)).formatted(cfg.enchantmentLimitsEnabled ? Formatting.GREEN : Formatting.RED));
        response.append(Text.literal("\n  effectLimitsEnabled = ").formatted(Formatting.GRAY))
                .append(Text.literal(String.valueOf(cfg.effectLimitsEnabled)).formatted(cfg.effectLimitsEnabled ? Formatting.GREEN : Formatting.RED));
        response.append(Text.literal("\n  dimensionLimitsEnabled = ").formatted(Formatting.GRAY))
                .append(Text.literal(String.valueOf(cfg.dimensionLimitsEnabled)).formatted(cfg.dimensionLimitsEnabled ? Formatting.GREEN : Formatting.RED));
        response.append(Text.literal("\n  itemActionBarEnabled = ").formatted(Formatting.GRAY))
                .append(Text.literal(String.valueOf(cfg.itemActionBarEnabled)).formatted(cfg.itemActionBarEnabled ? Formatting.GREEN : Formatting.RED));
        response.append(Text.literal("\n  weaponActionBarEnabled = ").formatted(Formatting.GRAY))
                .append(Text.literal(String.valueOf(cfg.weaponActionBarEnabled)).formatted(cfg.weaponActionBarEnabled ? Formatting.GREEN : Formatting.RED));
        response.append(Text.literal("\n  enchantmentActionBarEnabled = ").formatted(Formatting.GRAY))
                .append(Text.literal(String.valueOf(cfg.enchantmentActionBarEnabled)).formatted(cfg.enchantmentActionBarEnabled ? Formatting.GREEN : Formatting.RED));
        response.append(Text.literal("\n  effectActionBarEnabled = ").formatted(Formatting.GRAY))
                .append(Text.literal(String.valueOf(cfg.effectActionBarEnabled)).formatted(cfg.effectActionBarEnabled ? Formatting.GREEN : Formatting.RED));
        response.append(Text.literal("\n  checkIntervalTicks = ").formatted(Formatting.GRAY))
                .append(Text.literal(String.valueOf(cfg.checkIntervalTicks)).formatted(Formatting.AQUA));
        response.append(Text.literal("\n  dropPickupDelay = ").formatted(Formatting.GRAY))
                .append(Text.literal(String.valueOf(cfg.dropPickupDelay)).formatted(Formatting.AQUA));
        response.append(Text.literal("\n  deleteOverflowItem = ").formatted(Formatting.GRAY))
                .append(Text.literal(String.valueOf(cfg.deleteOverflowItem)).formatted(cfg.deleteOverflowItem ? Formatting.GREEN : Formatting.RED));
        response.append(Text.literal("\n  exemptCreative = ").formatted(Formatting.GRAY))
                .append(Text.literal(String.valueOf(cfg.exemptCreative)).formatted(cfg.exemptCreative ? Formatting.GREEN : Formatting.RED));
        response.append(Text.literal("\n  antiSeedCrackEnabled = ").formatted(Formatting.GRAY))
                .append(Text.literal(String.valueOf(cfg.antiSeedCrackEnabled)).formatted(cfg.antiSeedCrackEnabled ? Formatting.GREEN : Formatting.RED));
        response.append(Text.literal("\n  chunkBanEnabled = ").formatted(Formatting.GRAY))
                .append(Text.literal(String.valueOf(cfg.chunkBanEnabled)).formatted(cfg.chunkBanEnabled ? Formatting.GREEN : Formatting.RED));
        response.append(Text.literal("\n  maxItemBytes = ").formatted(Formatting.GRAY))
                .append(Text.literal(String.valueOf(cfg.maxItemBytes)).formatted(Formatting.AQUA));
        response.append(Text.literal("\n  maxBlockEntityBytes = ").formatted(Formatting.GRAY))
                .append(Text.literal(String.valueOf(cfg.maxBlockEntityBytes)).formatted(Formatting.AQUA));
        response.append(Text.literal("\n  maxChunkBlockEntityBytes = ").formatted(Formatting.GRAY))
                .append(Text.literal(String.valueOf(cfg.maxChunkBlockEntityBytes)).formatted(Formatting.AQUA));
        response.append(Text.literal("\n  bucketDrainEnabled = ").formatted(Formatting.GRAY))
                .append(Text.literal(String.valueOf(cfg.bucketDrainEnabled)).formatted(cfg.bucketDrainEnabled ? Formatting.GREEN : Formatting.RED));
        response.append(Text.literal("\n  maxBucketDrains = ").formatted(Formatting.GRAY))
                .append(Text.literal(String.valueOf(cfg.maxBucketDrains)).formatted(Formatting.AQUA));
        response.append(Text.literal("\n  bucketDrainWindowTicks = ").formatted(Formatting.GRAY))
                .append(Text.literal(String.valueOf(cfg.bucketDrainWindowTicks)).formatted(Formatting.AQUA));

        ctx.getSource().sendFeedback(() -> response, false);
        return 1;
    }

    private static int configItemLimitsEnabled(CommandContext<ServerCommandSource> ctx) {
        boolean value = BoolArgumentType.getBool(ctx, "value");
        WardenMod.CONFIG.itemLimitsEnabled = value;
        WardenMod.CONFIG.save();
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("itemLimitsEnabled = ").formatted(Formatting.GRAY))
                .append(Text.literal(String.valueOf(value)).formatted(value ? Formatting.GREEN : Formatting.RED)), true);
        return 1;
    }

    private static int configExplosionLimitsEnabled(CommandContext<ServerCommandSource> ctx) {
        boolean value = BoolArgumentType.getBool(ctx, "value");
        WardenMod.CONFIG.explosionLimitsEnabled = value;
        WardenMod.CONFIG.save();
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("explosionLimitsEnabled = ").formatted(Formatting.GRAY))
                .append(Text.literal(String.valueOf(value)).formatted(value ? Formatting.GREEN : Formatting.RED)), true);
        return 1;
    }

    private static int configWeaponLimitsEnabled(CommandContext<ServerCommandSource> ctx) {
        boolean value = BoolArgumentType.getBool(ctx, "value");
        WardenMod.CONFIG.weaponLimitsEnabled = value;
        WardenMod.CONFIG.save();
        syncWeaponRules(ctx.getSource());
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("weaponLimitsEnabled = ").formatted(Formatting.GRAY))
                .append(Text.literal(String.valueOf(value)).formatted(value ? Formatting.GREEN : Formatting.RED)), true);
        return 1;
    }

    private static int configEnchantmentLimitsEnabled(CommandContext<ServerCommandSource> ctx) {
        boolean value = BoolArgumentType.getBool(ctx, "value");
        WardenMod.CONFIG.enchantmentLimitsEnabled = value;
        WardenMod.CONFIG.save();
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("enchantmentLimitsEnabled = ").formatted(Formatting.GRAY))
                .append(Text.literal(String.valueOf(value)).formatted(value ? Formatting.GREEN : Formatting.RED)), true);
        return 1;
    }

    private static int configCheckIntervalTicks(CommandContext<ServerCommandSource> ctx) {
        int value = IntegerArgumentType.getInteger(ctx, "value");
        WardenMod.CONFIG.checkIntervalTicks = value;
        WardenMod.CONFIG.save();
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("checkIntervalTicks = ").formatted(Formatting.GRAY))
                .append(Text.literal(String.valueOf(value)).formatted(Formatting.AQUA)), true);
        return 1;
    }

    private static int configDropPickupDelay(CommandContext<ServerCommandSource> ctx) {
        int value = IntegerArgumentType.getInteger(ctx, "value");
        WardenMod.CONFIG.dropPickupDelay = value;
        WardenMod.CONFIG.save();
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("dropPickupDelay = ").formatted(Formatting.GRAY))
                .append(Text.literal(value + " ticks").formatted(Formatting.AQUA)), true);
        return 1;
    }

    private static int configDeleteOverflowItem(CommandContext<ServerCommandSource> ctx) {
        boolean value = BoolArgumentType.getBool(ctx, "value");
        WardenMod.CONFIG.deleteOverflowItem = value;
        WardenMod.CONFIG.save();
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("deleteOverflowItem = ").formatted(Formatting.GRAY))
                .append(Text.literal(String.valueOf(value)).formatted(value ? Formatting.GREEN : Formatting.RED)), true);
        return 1;
    }

    private static int configXpActionBarEnabled(CommandContext<ServerCommandSource> ctx) {
        boolean value = BoolArgumentType.getBool(ctx, "value");
        WardenMod.CONFIG.xpActionBarEnabled = value;
        WardenMod.CONFIG.save();
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("xpActionBarEnabled = ").formatted(Formatting.GRAY))
                .append(Text.literal(String.valueOf(value)).formatted(value ? Formatting.GREEN : Formatting.RED)), true);
        return 1;
    }

    private static int configItemActionBarEnabled(CommandContext<ServerCommandSource> ctx) {
        boolean value = BoolArgumentType.getBool(ctx, "value");
        WardenMod.CONFIG.itemActionBarEnabled = value;
        WardenMod.CONFIG.save();
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("itemActionBarEnabled = ").formatted(Formatting.GRAY))
                .append(Text.literal(String.valueOf(value)).formatted(value ? Formatting.GREEN : Formatting.RED)), true);
        return 1;
    }

    private static int configWeaponActionBarEnabled(CommandContext<ServerCommandSource> ctx) {
        boolean value = BoolArgumentType.getBool(ctx, "value");
        WardenMod.CONFIG.weaponActionBarEnabled = value;
        WardenMod.CONFIG.save();
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("weaponActionBarEnabled = ").formatted(Formatting.GRAY))
                .append(Text.literal(String.valueOf(value)).formatted(value ? Formatting.GREEN : Formatting.RED)), true);
        return 1;
    }

    private static int configEnchantmentActionBarEnabled(CommandContext<ServerCommandSource> ctx) {
        boolean value = BoolArgumentType.getBool(ctx, "value");
        WardenMod.CONFIG.enchantmentActionBarEnabled = value;
        WardenMod.CONFIG.save();
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("enchantmentActionBarEnabled = ").formatted(Formatting.GRAY))
                .append(Text.literal(String.valueOf(value)).formatted(value ? Formatting.GREEN : Formatting.RED)), true);
        return 1;
    }

    private static int configEffectActionBarEnabled(CommandContext<ServerCommandSource> ctx) {
        boolean value = BoolArgumentType.getBool(ctx, "value");
        WardenMod.CONFIG.effectActionBarEnabled = value;
        WardenMod.CONFIG.save();
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("effectActionBarEnabled = ").formatted(Formatting.GRAY))
                .append(Text.literal(String.valueOf(value)).formatted(value ? Formatting.GREEN : Formatting.RED)), true);
        return 1;
    }

    private static int configEffectLimitsEnabled(CommandContext<ServerCommandSource> ctx) {
        boolean value = BoolArgumentType.getBool(ctx, "value");
        WardenMod.CONFIG.effectLimitsEnabled = value;
        WardenMod.CONFIG.save();
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("effectLimitsEnabled = ").formatted(Formatting.GRAY))
                .append(Text.literal(String.valueOf(value)).formatted(value ? Formatting.GREEN : Formatting.RED)), true);
        return 1;
    }

    private static int configSetBool(CommandContext<ServerCommandSource> ctx, String key, java.util.function.Consumer<Boolean> setter) {
        boolean value = BoolArgumentType.getBool(ctx, "value");
        setter.accept(value);
        WardenMod.CONFIG.save();
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal(key + " = ").formatted(Formatting.GRAY))
                .append(Text.literal(String.valueOf(value)).formatted(value ? Formatting.GREEN : Formatting.RED)), true);
        return 1;
    }

    private static int configSetInt(CommandContext<ServerCommandSource> ctx, String key, java.util.function.IntConsumer setter) {
        int value = IntegerArgumentType.getInteger(ctx, "value");
        setter.accept(value);
        WardenMod.CONFIG.save();
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal(key + " = ").formatted(Formatting.GRAY))
                .append(Text.literal(String.valueOf(value)).formatted(Formatting.AQUA)), true);
        return 1;
    }

    private static int configAntiSeedCrackEnabled(CommandContext<ServerCommandSource> ctx) {
        boolean value = BoolArgumentType.getBool(ctx, "value");
        WardenMod.CONFIG.antiSeedCrackEnabled = value;
        WardenMod.CONFIG.save();
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("antiSeedCrackEnabled = ").formatted(Formatting.GRAY))
                .append(Text.literal(String.valueOf(value)).formatted(value ? Formatting.GREEN : Formatting.RED)), true);
        return 1;
    }

    private static int configDimensionLimitsEnabled(CommandContext<ServerCommandSource> ctx) {
        boolean value = BoolArgumentType.getBool(ctx, "value");
        WardenMod.CONFIG.dimensionLimitsEnabled = value;
        WardenMod.CONFIG.save();
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("dimensionLimitsEnabled = ").formatted(Formatting.GRAY))
                .append(Text.literal(String.valueOf(value)).formatted(value ? Formatting.GREEN : Formatting.RED)), true);
        return 1;
    }

    private static int statusDimension(CommandContext<ServerCommandSource> ctx) {
        WardenConfig cfg = WardenMod.CONFIG;
        MutableText response = wardenPrefix().append(Text.literal("Dimension limits (").formatted(Formatting.GRAY))
                .append(Text.literal(cfg.dimensionLimitsEnabled ? "ENABLED" : "DISABLED").formatted(cfg.dimensionLimitsEnabled ? Formatting.GREEN : Formatting.RED))
                .append(Text.literal("):").formatted(Formatting.GRAY));
        if (cfg.blockedDimensions.isEmpty()) {
            response.append(Text.literal("\n  (none blocked)").formatted(Formatting.DARK_GRAY));
        } else {
            for (String d : cfg.blockedDimensions) {
                response.append(Text.literal("\n  ").formatted(Formatting.GRAY))
                        .append(Text.literal(d).formatted(Formatting.YELLOW))
                        .append(Text.literal(": blocked").formatted(Formatting.RED));
            }
        }
        ctx.getSource().sendFeedback(() -> response, false);
        return 1;
    }

    // "nether" / "end" are what people actually type
    private static String dimensionId(String raw) {
        String id = switch (raw) {
            case "nether" -> "the_nether";
            case "end" -> "the_end";
            default -> raw;
        };
        Identifier ident = Identifier.tryParse(id);
        return ident == null ? null : ident.toString();
    }

    private static int dimensionBlock(CommandContext<ServerCommandSource> ctx) {
        String id = dimensionId(StringArgumentType.getString(ctx, "dimension"));
        if (id == null) {
            ctx.getSource().sendError(wardenPrefix().append(Text.literal("Invalid dimension id").formatted(Formatting.RED)));
            return 0;
        }
        WardenMod.CONFIG.blockedDimensions.add(id);
        WardenMod.CONFIG.save();
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("dimension.").formatted(Formatting.GRAY))
                .append(Text.literal(id).formatted(Formatting.YELLOW))
                .append(Text.literal(" blocked").formatted(Formatting.RED)), true);
        return 1;
    }

    private static int dimensionUnblock(CommandContext<ServerCommandSource> ctx) {
        String raw = StringArgumentType.getString(ctx, "dimension");
        String id = dimensionId(raw);
        boolean had = WardenMod.CONFIG.blockedDimensions.remove(raw)
                | (id != null && WardenMod.CONFIG.blockedDimensions.remove(id));
        WardenMod.CONFIG.save();
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("dimension.").formatted(Formatting.GRAY))
                .append(Text.literal(id != null ? id : raw).formatted(Formatting.YELLOW))
                .append(Text.literal(had ? " unblocked" : " was not blocked").formatted(Formatting.GRAY)), true);
        return 1;
    }

    private static int configExemptCreative(CommandContext<ServerCommandSource> ctx) {
        boolean value = BoolArgumentType.getBool(ctx, "value");
        WardenMod.CONFIG.exemptCreative = value;
        WardenMod.CONFIG.save();
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("exemptCreative = ").formatted(Formatting.GRAY))
                .append(Text.literal(String.valueOf(value)).formatted(value ? Formatting.GREEN : Formatting.RED)), true);
        return 1;
    }

    private static int statusExempt(CommandContext<ServerCommandSource> ctx) {
        WardenConfig cfg = WardenMod.CONFIG;
        MutableText response = wardenPrefix().append(Text.literal("Exempt config:").formatted(Formatting.GOLD));
        response.append(Text.literal("\n  exemptCreative: ").formatted(Formatting.GRAY))
                .append(Text.literal(String.valueOf(cfg.exemptCreative)).formatted(cfg.exemptCreative ? Formatting.GREEN : Formatting.RED));
        response.append(Text.literal("\n  exemptPlayers (").formatted(Formatting.GRAY))
                .append(Text.literal(String.valueOf(cfg.exemptPlayers.size())).formatted(Formatting.AQUA))
                .append(Text.literal("):").formatted(Formatting.GRAY));

        if (cfg.exemptPlayers.isEmpty()) {
            response.append(Text.literal("\n    (none)").formatted(Formatting.DARK_GRAY));
        } else {
            for (String p : cfg.exemptPlayers) {
                response.append(Text.literal("\n    ").formatted(Formatting.GRAY))
                        .append(Text.literal(exemptDisplayName(ctx.getSource().getServer(), p)).formatted(Formatting.YELLOW));
            }
        }
        ctx.getSource().sendFeedback(() -> response, false);
        return 1;
    }

    // exempt entries are uuids when the player was online at the time (survives name changes),
    // plain names otherwise
    private static String exemptKey(MinecraftServer server, String nameOrUuid) {
        ServerPlayerEntity online = server.getPlayerManager().getPlayer(nameOrUuid);
        return online != null ? online.getUuidAsString() : nameOrUuid;
    }

    private static String exemptDisplayName(MinecraftServer server, String entry) {
        try {
            ServerPlayerEntity online = server.getPlayerManager().getPlayer(UUID.fromString(entry));
            return online != null ? online.getName().getString() : entry;
        } catch (IllegalArgumentException notAUuid) {
            return entry;
        }
    }

    private static int exemptAdd(CommandContext<ServerCommandSource> ctx) {
        String player = StringArgumentType.getString(ctx, "player");
        String key = exemptKey(ctx.getSource().getServer(), player);
        if (!key.equals(player)) WardenMod.CONFIG.exemptPlayers.remove(player);
        WardenMod.CONFIG.exemptPlayers.add(key);
        WardenMod.CONFIG.save();
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal("Exempted: ").formatted(Formatting.GRAY))
                .append(Text.literal(player).formatted(Formatting.AQUA)), true);
        return 1;
    }

    private static int exemptRemove(CommandContext<ServerCommandSource> ctx) {
        String player = StringArgumentType.getString(ctx, "player");
        boolean had = WardenMod.CONFIG.exemptPlayers.remove(player)
                | WardenMod.CONFIG.exemptPlayers.remove(exemptKey(ctx.getSource().getServer(), player));
        WardenMod.CONFIG.save();
        ctx.getSource().sendFeedback(() -> wardenPrefix()
                .append(Text.literal(player).formatted(Formatting.AQUA))
                .append(Text.literal(had ? " removed from exempt list" : " was not in exempt list").formatted(Formatting.GRAY)), true);
        return 1;
    }

    private static int help(CommandContext<ServerCommandSource> ctx) {
        MutableText response = wardenPrefix().append(Text.literal("Available Commands:").formatted(Formatting.GOLD, Formatting.BOLD));
        response.append(Text.literal("\n  /warden usage block|allow <item> | list | toggle <true|false>").formatted(Formatting.AQUA));
        
        response.append(Text.literal("\n  /warden ").formatted(Formatting.AQUA))
                .append(Text.literal("reload | status | help").formatted(Formatting.WHITE));
                
        response.append(Text.literal("\n  /warden ").formatted(Formatting.AQUA))
                .append(Text.literal("reset ").formatted(Formatting.WHITE))
                .append(Text.literal("[<category>]").formatted(Formatting.YELLOW));
        response.append(Text.literal("\n    Categories: ").formatted(Formatting.LIGHT_PURPLE))
                .append(Text.literal("explosion, item, usage, weapon, enchant, effect, xp, dimension, actionbar, exempt").formatted(Formatting.WHITE));

        response.append(Text.literal("\n  /warden ").formatted(Formatting.AQUA))
                .append(Text.literal("restore ").formatted(Formatting.WHITE))
                .append(Text.literal("<countdownSeconds> <\"endTitle\"> <pvpDelayMinutes>").formatted(Formatting.YELLOW));

        response.append(Text.literal("\n  /warden ").formatted(Formatting.AQUA))
                .append(Text.literal("explosion ").formatted(Formatting.WHITE))
                .append(Text.literal("status ").formatted(Formatting.GREEN))
                .append(Text.literal("[<src>]").formatted(Formatting.YELLOW));
        response.append(Text.literal("\n  /warden ").formatted(Formatting.AQUA))
                .append(Text.literal("explosion ").formatted(Formatting.WHITE))
                .append(Text.literal("set ").formatted(Formatting.GREEN))
                .append(Text.literal("<src> maxPower <value|default>").formatted(Formatting.YELLOW));
        response.append(Text.literal("\n  /warden ").formatted(Formatting.AQUA))
                .append(Text.literal("explosion ").formatted(Formatting.WHITE))
                .append(Text.literal("disable/remove ").formatted(Formatting.RED))
                .append(Text.literal("<src>").formatted(Formatting.YELLOW));

        response.append(Text.literal("\n  /warden ").formatted(Formatting.AQUA))
                .append(Text.literal("item ").formatted(Formatting.WHITE))
                .append(Text.literal("status ").formatted(Formatting.GREEN))
                .append(Text.literal("[<item>]").formatted(Formatting.YELLOW));
        response.append(Text.literal("\n  /warden ").formatted(Formatting.AQUA))
                .append(Text.literal("item ").formatted(Formatting.WHITE))
                .append(Text.literal("set ").formatted(Formatting.GREEN))
                .append(Text.literal("<item> maxCount <value|default>").formatted(Formatting.YELLOW));
        response.append(Text.literal("\n  /warden ").formatted(Formatting.AQUA))
                .append(Text.literal("item ").formatted(Formatting.WHITE))
                .append(Text.literal("disable/remove ").formatted(Formatting.RED))
                .append(Text.literal("<item>").formatted(Formatting.YELLOW));

        response.append(Text.literal("\n  /warden ").formatted(Formatting.AQUA))
                .append(Text.literal("weapon ").formatted(Formatting.WHITE))
                .append(Text.literal("status ").formatted(Formatting.GREEN))
                .append(Text.literal("[<target>]").formatted(Formatting.YELLOW));
        response.append(Text.literal("\n  /warden ").formatted(Formatting.AQUA))
                .append(Text.literal("weapon ").formatted(Formatting.WHITE))
                .append(Text.literal("set ").formatted(Formatting.GREEN))
                .append(Text.literal("<target> <stat> <value|default>").formatted(Formatting.YELLOW));
        response.append(Text.literal("\n  /warden ").formatted(Formatting.AQUA))
                .append(Text.literal("weapon ").formatted(Formatting.WHITE))
                .append(Text.literal("disable/remove ").formatted(Formatting.RED))
                .append(Text.literal("<target>").formatted(Formatting.YELLOW));

        response.append(Text.literal("\n  /warden ").formatted(Formatting.AQUA))
                .append(Text.literal("enchant ").formatted(Formatting.WHITE))
                .append(Text.literal("status ").formatted(Formatting.GREEN))
                .append(Text.literal("[<ench> [for <target>]]").formatted(Formatting.YELLOW));
        response.append(Text.literal("\n  /warden ").formatted(Formatting.AQUA))
                .append(Text.literal("enchant ").formatted(Formatting.WHITE))
                .append(Text.literal("set ").formatted(Formatting.GREEN))
                .append(Text.literal("<ench> maxLevel <value|default> [for <target>]").formatted(Formatting.YELLOW));
        response.append(Text.literal("\n  /warden ").formatted(Formatting.AQUA))
                .append(Text.literal("enchant ").formatted(Formatting.WHITE))
                .append(Text.literal("disable/remove ").formatted(Formatting.RED))
                .append(Text.literal("<ench> [for <target>]").formatted(Formatting.YELLOW));

        response.append(Text.literal("\n  /warden ").formatted(Formatting.AQUA))
                .append(Text.literal("effect ").formatted(Formatting.WHITE))
                .append(Text.literal("status ").formatted(Formatting.GREEN))
                .append(Text.literal("[<effect>]").formatted(Formatting.YELLOW));
        response.append(Text.literal("\n  /warden ").formatted(Formatting.AQUA))
                .append(Text.literal("effect ").formatted(Formatting.WHITE))
                .append(Text.literal("set ").formatted(Formatting.GREEN))
                .append(Text.literal("<effect> maxLevel/maxDuration <value|default>").formatted(Formatting.YELLOW));
        response.append(Text.literal("\n  /warden ").formatted(Formatting.AQUA))
                .append(Text.literal("effect ").formatted(Formatting.WHITE))
                .append(Text.literal("disable/remove ").formatted(Formatting.RED))
                .append(Text.literal("<effect>").formatted(Formatting.YELLOW));
        
        response.append(Text.literal("\n  /warden ").formatted(Formatting.AQUA))
                .append(Text.literal("xp ").formatted(Formatting.WHITE))
                .append(Text.literal("status ").formatted(Formatting.GREEN))
                .append(Text.literal("[<src> [for <id>]]").formatted(Formatting.YELLOW));
        response.append(Text.literal("\n  /warden ").formatted(Formatting.AQUA))
                .append(Text.literal("xp ").formatted(Formatting.WHITE))
                .append(Text.literal("set ").formatted(Formatting.GREEN))
                .append(Text.literal("<src> maxGain <val|default> [for <id>]").formatted(Formatting.YELLOW));
        response.append(Text.literal("\n  /warden ").formatted(Formatting.AQUA))
                .append(Text.literal("xp ").formatted(Formatting.WHITE))
                .append(Text.literal("disable/remove ").formatted(Formatting.RED))
                .append(Text.literal("<src> [for <id>]").formatted(Formatting.YELLOW));
        response.append(Text.literal("\n    Sources: ").formatted(Formatting.LIGHT_PURPLE))
                .append(Text.literal("all, villager_trading, entitiesKilling, blocksMining, furnace, fishing, breeding, xp_bottle").formatted(Formatting.WHITE));
        response.append(Text.literal("\n    Note: ").formatted(Formatting.GOLD))
                .append(Text.literal("XP limits apply to XP Dropped from sources. Use 'for' with entitiesKilling/blocksMining.").formatted(Formatting.GRAY));

        response.append(Text.literal("\n  /warden ").formatted(Formatting.AQUA))
                .append(Text.literal("dimension ").formatted(Formatting.WHITE))
                .append(Text.literal("status").formatted(Formatting.GREEN));
        response.append(Text.literal("\n  /warden ").formatted(Formatting.AQUA))
                .append(Text.literal("dimension ").formatted(Formatting.WHITE))
                .append(Text.literal("block/unblock ").formatted(Formatting.GREEN))
                .append(Text.literal("<nether|end|id>").formatted(Formatting.YELLOW));

        response.append(Text.literal("\n  /warden ").formatted(Formatting.AQUA))
                .append(Text.literal("actionbar ").formatted(Formatting.WHITE))
                .append(Text.literal("status").formatted(Formatting.GREEN));
        response.append(Text.literal("\n  /warden ").formatted(Formatting.AQUA))
                .append(Text.literal("actionbar ").formatted(Formatting.WHITE))
                .append(Text.literal("<item|weapon|enchantment|effect> [<true|false>]").formatted(Formatting.YELLOW));

        response.append(Text.literal("\n  /warden ").formatted(Formatting.AQUA))
                .append(Text.literal("exempt ").formatted(Formatting.WHITE))
                .append(Text.literal("status").formatted(Formatting.GREEN));
        response.append(Text.literal("\n  /warden ").formatted(Formatting.AQUA))
                .append(Text.literal("exempt ").formatted(Formatting.WHITE))
                .append(Text.literal("add/remove ").formatted(Formatting.GREEN))
                .append(Text.literal("<player>").formatted(Formatting.YELLOW));

        response.append(Text.literal("\n  /warden ").formatted(Formatting.AQUA))
                .append(Text.literal("config ").formatted(Formatting.WHITE))
                .append(Text.literal("<key> <value>").formatted(Formatting.YELLOW));

        response.append(Text.literal("\n  /warden ").formatted(Formatting.AQUA))
                .append(Text.literal("freeze/mute ").formatted(Formatting.GREEN))
                .append(Text.literal("<player>").formatted(Formatting.YELLOW))
                .append(Text.literal(" (toggle)").formatted(Formatting.GRAY));
        response.append(Text.literal("\n  /warden ").formatted(Formatting.AQUA))
                .append(Text.literal("vanish").formatted(Formatting.GREEN))
                .append(Text.literal(" (toggle, self)").formatted(Formatting.GRAY));
        response.append(Text.literal("\n  /warden ").formatted(Formatting.AQUA))
                .append(Text.literal("inv/enderchest ").formatted(Formatting.GREEN))
                .append(Text.literal("<player>").formatted(Formatting.YELLOW));

        ctx.getSource().sendFeedback(() -> response, false);
        return 1;
    }

    private static String formatWeaponConfig(WardenConfig.WeaponLimitConfig cfg) {
        List<String> parts = new ArrayList<>();
        if (cfg.attackDamage != null) {
            parts.add("damage=" + cfg.attackDamage);
        }
        if (cfg.attackSpeed != null) {
            parts.add("attackSpeed=" + cfg.attackSpeed);
        }
        if (cfg.reach != null) {
            parts.add("reach=" + cfg.reach);
        }
        if (cfg.disableCooldownTicks != null) {
            parts.add("disableCooldown=" + cfg.disableCooldownTicks + " ticks");
        }
        if (cfg.projectileDamage != null) {
            parts.add("projectileDamage=" + cfg.projectileDamage);
        }
        if (cfg.rechargeTicks != null) {
            parts.add("rechargeTime=" + cfg.rechargeTicks + " ticks");
        }
        return parts.isEmpty() ? "not configured" : String.join(", ", parts);
    }

    private static boolean cleanupEffectLimit(String effectId) {
        WardenConfig.EffectLimitConfig cfg = WardenMod.CONFIG.effectLimits.get(effectId);
        if (cfg != null && cfg.maxLevel == -1 && cfg.maxDuration == -1) {
            WardenMod.CONFIG.effectLimits.remove(effectId);
            return true;
        }
        return false;
    }

    private static String formatEffectLevel(int level) {
        return level < 0 ? "default" : String.valueOf(level);
    }

    private static void validateWeaponStatTarget(String stat, String target, List<String> items)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        boolean valid = switch (stat) {
            case "attackDamage", "attackSpeed", "reach", "disableCooldown" ->
                    items.stream().allMatch(WardenCommand::isMeleeWeaponTarget);
            case "projectileDamage", "rechargeTime" ->
                    items.stream().allMatch(WardenCommand::isRangedWeaponTarget);
            default -> true;
        };
        if (!valid) {
            String expected = switch (stat) {
                case "attackDamage", "attackSpeed", "reach", "disableCooldown" ->
                        "damage, attackSpeed, reach, and disableCooldown only apply to melee weapons (sword, axe, mace, spear, trident)";
                case "projectileDamage", "rechargeTime" ->
                        "projectileDamage and rechargeTime only apply to ranged weapons (bow, crossbow, trident)";
                default -> "invalid weapon stat target";
            };
            throw INVALID_WEAPON_STAT_TARGET.create(expected + ": " + target);
        }
    }

    private static boolean clearWeaponStat(WardenConfig.WeaponLimitConfig cfg, String stat) {
        if ("attackDamage".equals(stat) && cfg.attackDamage != null) {
            cfg.attackDamage = null;
            return true;
        }
        if ("attackSpeed".equals(stat) && cfg.attackSpeed != null) {
            cfg.attackSpeed = null;
            return true;
        }
        if ("reach".equals(stat) && cfg.reach != null) {
            cfg.reach = null;
            return true;
        }
        if ("disableCooldown".equals(stat) && cfg.disableCooldownTicks != null) {
            cfg.disableCooldownTicks = null;
            return true;
        }
        if ("projectileDamage".equals(stat) && cfg.projectileDamage != null) {
            cfg.projectileDamage = null;
            return true;
        }
        if ("rechargeTime".equals(stat) && cfg.rechargeTicks != null) {
            cfg.rechargeTicks = null;
            return true;
        }
        return false;
    }

    private static void setWeaponStat(WardenConfig.WeaponLimitConfig cfg, String stat, float value) {
        if ("attackDamage".equals(stat)) {
            cfg.attackDamage = (double) value;
        } else if ("attackSpeed".equals(stat)) {
            cfg.attackSpeed = (double) value;
        } else if ("reach".equals(stat)) {
            cfg.reach = value;
        }
    }

    private static Item getItem(String itemId) {
        Identifier identifier = Identifier.tryParse(itemId);
        return identifier != null && Registries.ITEM.containsId(identifier) ? Registries.ITEM.get(identifier) : null;
    }

    private static Double getVanillaWeaponDouble(Item item, String stat) {
        if ("attackDamage".equals(stat)) {
            return WardenMod.getVanillaAttackDamage(item);
        }
        if ("attackSpeed".equals(stat)) {
            return WardenMod.getVanillaAttackSpeed(item);
        }
        return null;
    }

    private static Float getVanillaWeaponFloat(Item item, String stat) {
        if ("reach".equals(stat)) {
            return WardenMod.getVanillaReach(item);
        }
        return null;
    }

    private static boolean approximatelyEquals(double a, double b) {
        return Math.abs(a - b) < 0.0001;
    }
}
