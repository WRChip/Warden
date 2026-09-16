package com.warden.mixin;

import com.warden.WardenMod;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.s2c.play.ChunkData;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Second line behind BlockEntityMixin: many block entities that each sit under the per-entity cap
 * can still add up past the frame limit (a chunk wallpapered in lecterns). If the chunk's total
 * block entity payload is over the cap, drop all of it from this packet and log the chunk.
 */
@Mixin(ChunkData.class)
public abstract class ChunkDataMixin {

    @Shadow
    @Final
    private List<?> blockEntities;

    @Inject(method = "<init>(Lnet/minecraft/world/chunk/WorldChunk;)V", at = @At("TAIL"))
    private void warden$capChunkTotal(WorldChunk chunk, CallbackInfo ci) {
        if (WardenMod.CONFIG == null || !WardenMod.CONFIG.chunkBanEnabled) {
            return;
        }
        long total = 0;
        for (Object entry : blockEntities) {
            NbtCompound nbt = ((ChunkDataBlockEntityDataAccessor) entry).warden$nbt();
            if (nbt != null) {
                total += nbt.getSizeInBytes();
            }
        }
        if (total > WardenMod.CONFIG.maxChunkBlockEntityBytes) {
            WardenMod.logOversized("chunk:" + chunk.getPos().toLong(),
                    "chunk " + chunk.getPos() + " carries " + (total / 1024) + " KB of block entity data; sent none of it");
            blockEntities.clear();
        }
    }
}
