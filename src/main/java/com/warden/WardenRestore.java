package com.warden;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.GameMode;
import net.minecraft.world.border.WorldBorder;
import net.minecraft.world.rule.GameRules;

import java.util.ArrayList;
import java.util.List;

// drives /warden restore: a countdown (numbers on screen, then a custom title) that lifts the
// fresh-world lockdown, followed by a pvp grace period that counts down to a fixed activation
// title, with "smart" halving reminders along the way.
public final class WardenRestore {

    private enum Phase { IDLE, COUNTDOWN, PVP_TIMER }

    private static Phase phase = Phase.IDLE;
    private static int countdownTicksLeft;
    private static Text endTitle;
    private static int pvpTicksLeft;
    private static List<Integer> pvpNotifyTicks = List.of();
    private static int nextNotifyIndex;

    private WardenRestore() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(WardenRestore::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            phase = Phase.IDLE;
            countdownTicksLeft = 0;
            pvpTicksLeft = 0;
            pvpNotifyTicks = List.of();
            nextNotifyIndex = 0;
            endTitle = null;
        });
    }

    public static boolean isActive() {
        return phase != Phase.IDLE;
    }

    public static void start(MinecraftServer server, int countdownSeconds, Text endTitleText, int pvpDelayMinutes) {
        countdownTicksLeft = Math.max(0, countdownSeconds) * 20;
        endTitle = endTitleText;
        int pvpTotalTicks = Math.max(0, pvpDelayMinutes) * 60 * 20;
        pvpNotifyTicks = computeNotifyThresholds(pvpTotalTicks);
        nextNotifyIndex = 0;
        pvpTicksLeft = pvpTotalTicks;
        phase = Phase.COUNTDOWN;
    }

    private static void tick(MinecraftServer server) {
        switch (phase) {
            case COUNTDOWN -> tickCountdown(server);
            case PVP_TIMER -> tickPvpTimer(server);
            case IDLE -> {
            }
        }
    }

    private static void tickCountdown(MinecraftServer server) {
        if (countdownTicksLeft % 20 == 0) {
            int secondsLeft = countdownTicksLeft / 20;
            if (secondsLeft > 0) {
                broadcastTitle(server, Text.literal(String.valueOf(secondsLeft)).formatted(Formatting.YELLOW, Formatting.BOLD), 0, 20, 5);
            }
        }
        if (countdownTicksLeft <= 0) {
            finishCountdown(server);
            return;
        }
        countdownTicksLeft--;
    }

    private static void finishCountdown(MinecraftServer server) {
        server.setDefaultGameMode(GameMode.SURVIVAL);
        WardenWorldState.setLocked(server, false);
        server.getTickManager().setFrozen(false);
        server.getOverworld().getGameRules().setValue(GameRules.RESPAWN_RADIUS, 10, server);
        WorldBorder border = server.getOverworld().getWorldBorder();
        border.setCenter(0.0, 0.0);
        border.setSize(WardenNewWorldWatcher.VANILLA_BORDER_SIZE);
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            restoreGameMode(player);
        }
        broadcastTitle(server, endTitle, 5, 40, 10);
        WardenMod.LOGGER.info("[Warden] World restored");

        if (pvpTicksLeft <= 0) {
            enablePvp(server);
        } else {
            phase = Phase.PVP_TIMER;
        }
    }

    static void restoreGameMode(ServerPlayerEntity player) {
        if (!isExemptFromRestoreGameMode(player)) {
            player.changeGameMode(GameMode.SURVIVAL);
        }
    }

    private static boolean isExemptFromRestoreGameMode(ServerPlayerEntity player) {
        if (player.getPermissions().hasPermission(new Permission.Level(PermissionLevel.GAMEMASTERS))) {
            return true;
        }
        GameMode mode = player.getGameMode();
        return mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR;
    }

    private static void tickPvpTimer(MinecraftServer server) {
        if (pvpTicksLeft <= 0) {
            enablePvp(server);
            return;
        }
        while (nextNotifyIndex < pvpNotifyTicks.size() && pvpTicksLeft <= pvpNotifyTicks.get(nextNotifyIndex)) {
            announcePvpTimeRemaining(server, pvpTicksLeft);
            nextNotifyIndex++;
        }
        pvpTicksLeft--;
    }

    private static void enablePvp(MinecraftServer server) {
        server.getOverworld().getGameRules().setValue(GameRules.PVP, true, server);
        broadcastTitle(server, Text.literal("PVP Enabled").formatted(Formatting.RED, Formatting.BOLD), 5, 40, 10);
        phase = Phase.IDLE;
    }

    private static void announcePvpTimeRemaining(MinecraftServer server, int ticksRemaining) {
        String duration = formatDuration(ticksRemaining / 20);
        server.getPlayerManager().broadcast(WardenMod.wardenPrefix()
                .append(Text.literal("PVP will be enabled in " + duration + ".").formatted(Formatting.GOLD)), false);
    }

    // notify at 1/2, then 1/4, 1/6, 1/12 ... of the remaining time, each rounded to a granule
    // scaled to the total length so the checkpoints land on round numbers (e.g. a 1hr timer
    // notifies at 30/15/10/5 min, a 2hr timer at 1hr/30/20/10 min).
    // totalTicks is always a whole number of minutes (pvpDelayMinutes * 1200), so the granule
    // is worked out in minutes too - otherwise a total that isn't a multiple of 12 minutes
    // produces fractional-minute checkpoints (e.g. 12.5, 6.25) instead of round numbers.
    private static List<Integer> computeNotifyThresholds(int totalTicks) {
        List<Integer> thresholds = new ArrayList<>();
        if (totalTicks <= 0) {
            return thresholds;
        }
        long totalMinutes = totalTicks / 1200;
        long granule = Math.max(1, Math.round(totalMinutes / 12.0));
        long current = totalMinutes;
        while (true) {
            double half = current / 2.0;
            long rounded = Math.round(half / granule) * granule;
            if (rounded <= 0 || rounded >= current) {
                break;
            }
            thresholds.add((int) (rounded * 1200));
            current = rounded;
        }
        return thresholds;
    }

    private static String formatDuration(int totalSeconds) {
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int seconds = totalSeconds % 60;
        List<String> parts = new ArrayList<>();
        if (hours > 0) {
            parts.add(hours + (hours == 1 ? " hour" : " hours"));
        }
        if (minutes > 0) {
            parts.add(minutes + (minutes == 1 ? " minute" : " minutes"));
        }
        if (seconds > 0) {
            parts.add(seconds + (seconds == 1 ? " second" : " seconds"));
        }
        return parts.isEmpty() ? "0 seconds" : String.join(" ", parts);
    }

    private static void broadcastTitle(MinecraftServer server, Text title, int fadeInTicks, int stayTicks, int fadeOutTicks) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            player.networkHandler.sendPacket(new TitleFadeS2CPacket(fadeInTicks, stayTicks, fadeOutTicks));
            player.networkHandler.sendPacket(new TitleS2CPacket(title));
        }
    }
}
