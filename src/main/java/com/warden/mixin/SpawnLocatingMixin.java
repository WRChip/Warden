package com.warden.mixin;

import net.minecraft.server.network.SpawnLocating;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.CollisionView;
import net.minecraft.world.GameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

@Mixin(SpawnLocating.class)
public abstract class SpawnLocatingMixin {
    @Shadow
    private static Vec3d findPosInColumn(CollisionView world, BlockPos pos) {
        throw new AssertionError();
    }

    @Inject(method = "locateSpawnPos", at = @At("HEAD"), cancellable = true)
    private static void warden$loadSpawnColumn(ServerWorld world, BlockPos pos,
                                              CallbackInfoReturnable<CompletableFuture<Vec3d>> cir) {
        if (world.getDimension().hasSkyLight() && world.getServer().getDefaultGameMode() != GameMode.ADVENTURE) {
            return;
        }
        // Vanilla's direct-column path checks collisions without loading chunks, treating missing terrain as air.
        cir.setReturnValue(world.getChunkManager()
                .addChunkLoadingTicket(ChunkTicketType.SPAWN_SEARCH, new ChunkPos(pos), 0)
                .thenApplyAsync(ignored -> findPosInColumn(world, pos), world.getServer()));
    }
}
