package com.warden.mixin;

import com.warden.WardenMod;
import com.warden.seed.SeedHash;
import net.minecraft.inventory.LootableInventory;
import net.minecraft.util.math.random.Random;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Covers the loot chests that aren't placed from an NBT structure piece: end cities, shipwrecks,
 * dungeons, the world-spawn bonus chest, and decorated pots. See StructureTemplateMixin for the
 * path everything else (bastions, ancient cities, ruined portals, buried treasure, etc.) takes.
 */
@Mixin(LootableInventory.class)
public interface LootableInventoryMixin {

    @Redirect(method = "setLootTable(Lnet/minecraft/world/BlockView;Lnet/minecraft/util/math/random/Random;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/registry/RegistryKey;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/math/random/Random;nextLong()J"))
    private static long warden$scrambleGeneratedLootSeed(Random random) {
        long rolled = random.nextLong();
        return WardenMod.CONFIG.antiSeedCrackEnabled ? rolled ^ SeedHash.get() : rolled;
    }
}
