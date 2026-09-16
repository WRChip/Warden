package com.warden.mixin;

import com.warden.WardenMod;
import com.warden.seed.SeedHash;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.structure.StructureSet;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.placement.StructurePlacementCalculator;
import net.minecraft.world.gen.noise.NoiseConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Where a structure's placement grid cell lands (villages, temples, bastions, ...) and the
 * stronghold ring's angle both come from the seed handed to StructurePlacementCalculator here.
 * It's the one place both real generation and /locate read the seed from, so scrambling it here
 * keeps the two in sync automatically - /locate still points at wherever things actually
 * generate, it just can no longer be used to work backward to the world seed.
 *
 * This covers structure POSITION. It does not touch structure ROTATION/mirror, which is chosen
 * separately per structure type and isn't the vector seedcracking tools actually use, since it
 * carries far less information than an exact chunk coordinate.
 */
@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorMixin {

    @Redirect(method = "createStructurePlacementCalculator",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/gen/chunk/placement/StructurePlacementCalculator;create(Lnet/minecraft/world/gen/noise/NoiseConfig;JLnet/minecraft/world/biome/source/BiomeSource;Lnet/minecraft/registry/RegistryWrapper;)Lnet/minecraft/world/gen/chunk/placement/StructurePlacementCalculator;"))
    private StructurePlacementCalculator warden$scrambleStructureSeed(
            NoiseConfig noiseConfig, long seed, BiomeSource biomeSource, RegistryWrapper<StructureSet> structureSets) {
        long effective = WardenMod.CONFIG.antiSeedCrackEnabled ? seed ^ SeedHash.get() : seed;
        return StructurePlacementCalculator.create(noiseConfig, effective, biomeSource, structureSets);
    }
}
