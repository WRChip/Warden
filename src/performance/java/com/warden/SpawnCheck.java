package com.warden;

import com.mojang.authlib.GameProfile;
import com.warden.config.WardenConfig;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.EntityType;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
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
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
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
        checkItemUsage(server);
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

    private static void checkItemUsage(MinecraftServer server) throws Exception {
        var original = WardenMod.CONFIG;
        try {
            WardenMod.CONFIG = new WardenConfig();
            var player = player(server, "UsageCheck", GameMode.SURVIVAL);
            var world = server.getOverworld();
            var stack = new ItemStack(Items.ENDER_PEARL, 8);
            var hand = Hand.MAIN_HAND;
            var fail = ActionResult.FAIL;
            var pass = ActionResult.PASS;
            player.setStackInHand(hand, stack);
            var dispatcher = server.getCommandManager().getDispatcher();
            require(dispatcher.execute("warden usage block ender_pearl", server.getCommandSource()) == 1, "block command");
            require(WardenMod.CONFIG.blockedItemUsage.contains("minecraft:ender_pearl"), "canonical item id");
            WardenMod.CONFIG = WardenConfig.load();
            require(WardenItemUsage.check(player, hand) == fail, "usage rule survives save and reload");
            require(UseItemCallback.EVENT.invoker()
                    .interact(player, world, hand) == fail, "right click in air");
            var pos = new BlockPos(0, -61, 0);
            var hit = new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false);
            require(UseBlockCallback.EVENT.invoker()
                    .interact(player, world, hand, hit) == fail, "right click block");
            require(UseEntityCallback.EVENT.invoker()
                    .interact(player, world, hand, player, null) == fail, "right click entity");
            require(AttackEntityCallback.EVENT.invoker()
                    .interact(player, world, hand, player, null) == fail, "left click entity");
            require(AttackBlockCallback.EVENT.invoker()
                    .interact(player, world, hand, pos, Direction.UP) == fail, "left click block");
            require(!PlayerBlockBreakEvents.BEFORE.invoker()
                    .beforeBlockBreak(world, player, pos, world.getBlockState(pos), null), "mining completion");
            WardenMod.enforceItemLimits(player);
            require(stack.getCount() == 8 && player.getStackInHand(hand) == stack, "usage restriction preserves inventory");
            require(WardenMod.CONFIG.itemLimits.isEmpty(), "usage rule must not become a crafting or inventory ban");
            player.setStackInHand(hand, ItemStack.EMPTY);
            player.setStackInHand(Hand.OFF_HAND, stack);
            require(UseItemCallback.EVENT.invoker()
                    .interact(player, world, Hand.OFF_HAND) == fail, "offhand use blocked");
            require(WardenItemUsage.check(player, hand) == pass, "unrestricted hand allowed");
            player.setStackInHand(hand, stack);
            WardenMod.CONFIG.exemptPlayers.add(player.getUuidAsString());
            require(WardenItemUsage.check(player, hand) == pass, "player exemption");
            WardenMod.CONFIG.exemptPlayers.clear();
            player.changeGameMode(GameMode.CREATIVE);
            require(WardenItemUsage.check(player, hand) == pass, "creative exemption");
            WardenMod.CONFIG.exemptCreative = false;
            require(WardenItemUsage.check(player, hand) == fail, "creative exemption disabled");
            player.changeGameMode(GameMode.SPECTATOR);
            require(WardenItemUsage.check(player, hand) == pass, "spectator unaffected");
            player.changeGameMode(GameMode.SURVIVAL);
            require(dispatcher.execute("warden usage toggle false", server.getCommandSource()) == 1, "toggle command");
            require(WardenItemUsage.check(player, hand) == pass, "disabled restrictions");
            dispatcher.execute("warden usage toggle true", server.getCommandSource());
            dispatcher.execute("warden usage allow ender_pearl", server.getCommandSource());
            require(WardenItemUsage.check(player, hand) == pass, "allow command");
            WardenMod.CONFIG.blockedItemUsage.add("minecraft:ender_pearl");
            require(WardenMod.CONFIG.resetCategory("usage") && WardenMod.CONFIG.blockedItemUsage.isEmpty(), "usage reset");
            WardenMod.CONFIG.blockedItemUsage.add("minecraft:ender_pearl");
            WardenMod.CONFIG.resetAll();
            require(WardenMod.CONFIG.blockedItemUsage.isEmpty(), "full reset");
            System.out.println("Warden item usage checks passed");
        } finally {
            WardenMod.CONFIG = original;
            original.save();
        }
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
