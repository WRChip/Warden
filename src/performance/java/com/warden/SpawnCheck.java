package com.warden;

import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.EntityType;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import net.minecraft.server.Main;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.SpawnLocating;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.CollisionView;
import net.minecraft.world.GameMode;
import net.minecraft.world.rule.GameRules;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class SpawnCheck {
    private static boolean ready;
    private static boolean ran;

    public static void main(String[] args) throws Exception {
        CountDownLatch stopped = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> ready = true);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> stopped.countDown());
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!ready || ran) return;
            ran = true;
            try {
                if (args.length == 0) check(server);
                else checkRestart(server);
            } catch (Throwable t) {
                failure.set(t);
                t.printStackTrace();
            } finally {
                server.stop(false);
            }
        });
        Main.main(new String[]{"--nogui"});
        require(stopped.await(120, TimeUnit.SECONDS), "test server did not stop");
        if (failure.get() != null) throw new AssertionError("spawn checks failed", failure.get());
        require(ran, "test server did not start");
        System.out.println("Warden spawn checks passed");
    }

    private static void checkRestart(MinecraftServer server) {
        require(WardenWorldState.get(server) != null, "world state survives restart");
        require(!WardenWorldState.get(server).isLocked(), "started world stays unlocked after restart");
        require(!server.getTickManager().isFrozen(), "started world stays unfrozen after restart");
        require(server.getDefaultGameMode() == GameMode.SURVIVAL, "Survival default after restart");
        ServerPlayerEntity returning = player(server, "AfterRestart", GameMode.ADVENTURE);
        ServerPlayConnectionEvents.JOIN.invoker().onPlayReady(returning.networkHandler, null, server);
        require(returning.getGameMode() == GameMode.SURVIVAL, "saved Adventure player is corrected after restart");
        require(server.getOverworld().getGameRules().getValue(GameRules.PVP), "completed PVP timer survives restart");
        System.out.println("restored world and returning-player mode verified after restart");
    }

    private static void check(MinecraftServer server) throws Exception {
        var world = server.getOverworld();
        require(WardenWorldState.get(server).isLocked(), "new world starts locked");
        require(server.getDefaultGameMode() == GameMode.ADVENTURE, "lockdown default");
        require(server.getTickManager().isFrozen(), "lockdown freezes ticks");
        BlockPos pos = new BlockPos(4096, 100, 4096);
        require(world.getChunkManager().getWorldChunk(256, 256) == null, "spawn column must start unloaded");
        var vanillaColumn = SpawnLocating.class.getDeclaredMethod("findPosInColumn", CollisionView.class, BlockPos.class);
        vanillaColumn.setAccessible(true);
        Vec3d broken = (Vec3d) vanillaColumn.invoke(null, world, pos);
        require(broken.y == world.getBottomY() + 1, "reproduce vanilla unloaded-column bedrock spawn");

        var spawn = SpawnLocating.locateSpawnPos(world, pos);
        server.runTasks(spawn::isDone);
        Vec3d safe = spawn.join();
        require(safe.y > broken.y, "spawn must be above bedrock");
        require(world.isSpaceEmpty(null, EntityType.PLAYER.getDimensions().getBoxAt(safe)), "spawn has player headroom");
        require(!world.getBlockState(BlockPos.ofFloored(safe).down()).isAir(), "spawn has a floor");
        System.out.println("unloaded Adventure column: vanilla Y=" + broken.y + ", fixed Y=" + safe.y);

        ServerPlayerEntity waiting = player(server, "Waiting", GameMode.ADVENTURE);
        ServerPlayerEntity creative = player(server, "Creative", GameMode.CREATIVE);
        ServerPlayerEntity spectator = player(server, "Spectator", GameMode.SPECTATOR);
        ServerPlayerEntity operator = player(server, "Operator", GameMode.ADVENTURE);
        server.getPlayerManager().addToOperators(operator.getPlayerConfigEntry());
        var online = server.getPlayerManager().getPlayerList();
        online.add(waiting);
        online.add(creative);
        online.add(spectator);
        online.add(operator);
        WardenRestore.start(server, 0, Text.literal("Start"), 1);
        var tick = WardenRestore.class.getDeclaredMethod("tick", MinecraftServer.class);
        tick.setAccessible(true);
        tick.invoke(null, server);
        require(server.getDefaultGameMode() == GameMode.SURVIVAL, "restore must change the server default");
        require(waiting.getGameMode() == GameMode.SURVIVAL, "online player starts in Survival");
        require(creative.getGameMode() == GameMode.CREATIVE, "preserve Creative");
        require(spectator.getGameMode() == GameMode.SPECTATOR, "preserve Spectator");
        require(operator.getGameMode() == GameMode.ADVENTURE, "preserve operator exemption");
        require(!server.getTickManager().isFrozen(), "restore unfreezes ticks");
        require(!world.getGameRules().getValue(GameRules.PVP), "grace period keeps PVP off");
        require(world.getGameRules().getValue(GameRules.RESPAWN_RADIUS) == 10, "restore spawn radius");
        online.clear();

        ServerPlayerEntity returning = player(server, "Returning", GameMode.ADVENTURE);
        ServerPlayConnectionEvents.JOIN.invoker().onPlayReady(returning.networkHandler, null, server);
        require(returning.getGameMode() == GameMode.SURVIVAL, "offline Adventure player joins in Survival after start");
        ServerPlayConnectionEvents.JOIN.invoker().onPlayReady(creative.networkHandler, null, server);
        require(creative.getGameMode() == GameMode.CREATIVE, "join preserves Creative");
        ServerPlayConnectionEvents.JOIN.invoker().onPlayReady(operator.networkHandler, null, server);
        require(operator.getGameMode() == GameMode.ADVENTURE, "join preserves operator exemption");
        require(!WardenWorldState.get(server).isLocked(), "restore clears the persisted lockdown");
        world.getPersistentStateManager().save();

        world.getWorldBorder().setSize(10000);
        var survivalSpawn = SpawnLocating.locateSpawnPos(world, pos.add(32, 0, 32));
        server.runTasks(survivalSpawn::isDone);
        require(survivalSpawn.join().y > broken.y, "normal Survival spawn remains safe");
        for (int i = 0; i <= 1200; i++) tick.invoke(null, server);
        require(world.getGameRules().getValue(GameRules.PVP), "PVP enables after the grace period");
        require(!WardenRestore.isActive(), "restore finishes");
    }

    private static ServerPlayerEntity player(MinecraftServer server, String name, GameMode mode) {
        GameProfile profile = new GameProfile(UUID.randomUUID(), name);
        ServerPlayerEntity player = new ServerPlayerEntity(server, server.getOverworld(), profile, SyncedClientOptions.createDefault());
        player.networkHandler = new ServerPlayNetworkHandler(server, new ClientConnection(NetworkSide.SERVERBOUND),
                player, ConnectedClientData.createDefault(profile, false));
        player.changeGameMode(mode);
        return player;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
