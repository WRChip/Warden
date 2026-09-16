package com.warden.mixin;

import com.warden.WardenMod;
import com.warden.seed.SeedHash;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.SaveProperties;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {

    @Shadow
    @Final
    protected SaveProperties saveProperties;

    @Inject(method = "createWorlds", at = @At("HEAD"))
    private void warden$precomputeSeedHash(CallbackInfo ci) {
        SeedHash.precompute(saveProperties.getGeneratorOptions().getSeed());
        WardenMod.REGISTRIES = ((MinecraftServer) (Object) this).getRegistryManager();
    }
}
