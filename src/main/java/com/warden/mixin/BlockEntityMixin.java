package com.warden.mixin;

import com.warden.WardenMod;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * toInitialChunkDataNbt is what goes on the wire for a block entity, both in chunk packets and in
 * single block entity updates. A sign, lectern, banner or beehive with a huge payload gets sent
 * empty instead of taking the whole chunk packet over the client's frame limit. The block still
 * exists and works server-side; the client just doesn't get its display data.
 */
@Mixin(BlockEntity.class)
public abstract class BlockEntityMixin {

    @Inject(method = "toInitialChunkDataNbt", at = @At("RETURN"), cancellable = true)
    private void warden$capChunkNbt(RegistryWrapper.WrapperLookup registries, CallbackInfoReturnable<NbtCompound> cir) {
        if (WardenMod.CONFIG == null || !WardenMod.CONFIG.chunkBanEnabled) {
            return;
        }
        NbtCompound nbt = cir.getReturnValue();
        if (nbt == null) {
            return;
        }
        int size = nbt.getSizeInBytes();
        if (size > WardenMod.CONFIG.maxBlockEntityBytes) {
            BlockEntity self = (BlockEntity) (Object) this;
            WardenMod.logOversized("be:" + self.getPos().asLong(),
                    "sent empty data for " + self.getType() + " at " + self.getPos().toShortString()
                            + " (" + (size / 1024) + " KB, over the per block entity cap)");
            cir.setReturnValue(new NbtCompound());
        }
    }
}
