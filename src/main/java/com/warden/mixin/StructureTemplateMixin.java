package com.warden.mixin;

import com.warden.WardenMod;
import com.warden.seed.SeedHash;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.util.math.random.Random;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * StructureTemplate#place rolls the loot-table seed for any chest placed from an NBT structure
 * piece (most modern structures: bastions, ancient cities, trial chambers, ruined portals,
 * buried treasure, pillager outposts, and more). Scrambling that roll here means the seed a
 * player reads out of a chest can no longer be traced back to the world seed.
 */
@Mixin(StructureTemplate.class)
public abstract class StructureTemplateMixin {

    @Redirect(method = "place", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/math/random/Random;nextLong()J"))
    private long warden$scrambleLootSeed(Random random) {
        long rolled = random.nextLong();
        return WardenMod.CONFIG.antiSeedCrackEnabled ? rolled ^ SeedHash.get() : rolled;
    }
}
