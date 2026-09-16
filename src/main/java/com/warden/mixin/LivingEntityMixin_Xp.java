package com.warden.mixin;

import com.warden.WardenMod;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin_Xp {

    // the amount itself is capped in ExperienceOrbEntityMixin when the orbs are spawned
    @Inject(method = "dropExperience", at = @At("HEAD"))
    private void warden$trackMobKillingXp(net.minecraft.server.world.ServerWorld world, net.minecraft.entity.Entity attacker, CallbackInfo ci) {
        WardenMod.XP_SOURCE.set("entitiesKilling");
        WardenMod.XP_CONTEXT.set(net.minecraft.registry.Registries.ENTITY_TYPE.getId(((LivingEntity)(Object)this).getType()).toString());
        WardenMod.XP_PLAYER.set(attacker instanceof ServerPlayerEntity player ? player : null);
    }

    @Inject(method = "dropExperience", at = @At("TAIL"))
    private void warden$clearMobKillingXp(net.minecraft.server.world.ServerWorld world, net.minecraft.entity.Entity attacker, CallbackInfo ci) {
        WardenMod.XP_SOURCE.set("unknown");
        WardenMod.XP_CONTEXT.set("");
        WardenMod.XP_PLAYER.set(null);
    }
}
