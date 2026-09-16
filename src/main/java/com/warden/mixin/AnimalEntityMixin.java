package com.warden.mixin;

import com.warden.WardenMod;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AnimalEntity.class)
public abstract class AnimalEntityMixin {

    @Inject(method = "breed(Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/entity/passive/AnimalEntity;Lnet/minecraft/entity/passive/PassiveEntity;)V", at = @At("HEAD"))
    private void warden$trackBreedingXp(ServerWorld world, AnimalEntity other, PassiveEntity baby, CallbackInfo ci) {
        WardenMod.XP_SOURCE.set("breeding");
        WardenMod.XP_CONTEXT.set(Registries.ENTITY_TYPE.getId(((AnimalEntity) (Object) this).getType()).toString());
    }

    @Inject(method = "breed(Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/entity/passive/AnimalEntity;Lnet/minecraft/entity/passive/PassiveEntity;)V", at = @At("TAIL"))
    private void warden$clearBreedingXp(ServerWorld world, AnimalEntity other, PassiveEntity baby, CallbackInfo ci) {
        WardenMod.XP_SOURCE.set("unknown");
        WardenMod.XP_CONTEXT.set("");
    }
}
