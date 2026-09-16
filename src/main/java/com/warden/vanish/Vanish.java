package com.warden.vanish;

import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// a vanished player should be undetectable by anything below moderator level: no entity, no tab entry,
// no sound, no game events, no mob attention, no selector hits. the mixins in com.warden.mixin.vanish
// all funnel through canSee, so adding a new leak point means adding one more caller here.
public final class Vanish {

    // a viewer needs at least this level to see through vanish
    private static final Permission SEE_PERMISSION = new Permission.Level(PermissionLevel.GAMEMASTERS);

    private static final Set<UUID> VANISHED = ConcurrentHashMap.newKeySet();

    private Vanish() {
    }

    public static boolean anyVanished() {
        return !VANISHED.isEmpty();
    }

    public static boolean isVanished(UUID id) {
        return VANISHED.contains(id);
    }

    public static boolean isVanished(@Nullable Entity entity) {
        return entity instanceof PlayerEntity && VANISHED.contains(entity.getUuid());
    }

    // mobs, projectiles, command blocks and ordinary players all fall through to false
    public static boolean canSee(@Nullable Entity viewer, Entity target) {
        if (!isVanished(target)) {
            return true;
        }
        if (viewer == target) {
            return true;
        }
        return viewer instanceof ServerPlayerEntity player && canSeeThrough(player);
    }

    public static boolean canSee(@Nullable Entity viewer, UUID target) {
        if (!isVanished(target)) {
            return true;
        }
        if (viewer != null && viewer.getUuid().equals(target)) {
            return true;
        }
        return viewer instanceof ServerPlayerEntity player && canSeeThrough(player);
    }

    // the console keeps full sight so moderation tooling still works, command blocks do not
    public static boolean canSee(ServerCommandSource source, Entity target) {
        if (!isVanished(target)) {
            return true;
        }
        Entity executor = source.getEntity();
        if (executor != null) {
            return canSee(executor, target);
        }
        return source.getPermissions().hasPermission(new Permission.Level(PermissionLevel.OWNERS));
    }

    public static boolean canSeeThrough(ServerPlayerEntity player) {
        return player.getPermissions().hasPermission(SEE_PERMISSION);
    }

    public static boolean set(ServerPlayerEntity player, boolean vanished) {
        if (vanished ? !VANISHED.add(player.getUuid()) : !VANISHED.remove(player.getUuid())) {
            return false;
        }
        MinecraftServer server = player.getEntityWorld().getServer();
        for (ServerPlayerEntity viewer : server.getPlayerManager().getPlayerList()) {
            if (viewer == player || canSeeThrough(viewer)) {
                continue;
            }
            if (vanished) {
                viewer.networkHandler.sendPacket(new PlayerRemoveS2CPacket(List.of(player.getUuid())));
            } else {
                viewer.networkHandler.sendPacket(PlayerListS2CPacket.entryFromPlayer(List.of(player)));
            }
            viewer.sendMessage(connectionMessage(player, vanished));
        }
        // makes every tracker re-run startTracking, which is where the entity itself gets hidden or restored
        ServerWorld world = (ServerWorld) player.getEntityWorld();
        world.getChunkManager().unloadEntity(player);
        world.getChunkManager().loadEntity(player);
        return true;
    }

    public static boolean toggle(ServerPlayerEntity player) {
        boolean vanished = !isVanished(player.getUuid());
        set(player, vanished);
        return vanished;
    }

    // vanishing looks exactly like a disconnect to everyone who cannot see it
    public static Text connectionMessage(ServerPlayerEntity player, boolean vanished) {
        return Text.translatable(vanished ? "multiplayer.player.left" : "multiplayer.player.joined",
                player.getDisplayName()).formatted(Formatting.YELLOW);
    }

    public static void forget(UUID id) {
        VANISHED.remove(id);
    }
}
