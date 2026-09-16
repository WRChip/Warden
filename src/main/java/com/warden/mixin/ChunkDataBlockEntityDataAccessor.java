package com.warden.mixin;

import net.minecraft.nbt.NbtCompound;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.network.packet.s2c.play.ChunkData$BlockEntityData")
public interface ChunkDataBlockEntityDataAccessor {

    @Accessor("nbt")
    NbtCompound warden$nbt();
}
