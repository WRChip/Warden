package com.warden.mixin;

import com.warden.WardenMod;
import com.warden.xp.ExperienceOrbEntityAccessor;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ExperienceOrbEntity.class)
public abstract class ExperienceOrbEntityMixin implements ExperienceOrbEntityAccessor {

    @Unique
    private String warden$source = "unknown";
    @Unique
    private String warden$context = "";

    @Override
    public void warden$setXpSource(String source) {
        this.warden$source = source;
    }

    @Override
    public void warden$setXpContext(String context) {
        this.warden$context = context;
    }

    // the (World,DDDI) constructor and ExperienceOrbEntity.spawn both route through this one
    @Inject(method = "<init>(Lnet/minecraft/world/World;Lnet/minecraft/util/math/Vec3d;Lnet/minecraft/util/math/Vec3d;I)V", at = @At("TAIL"))
    private void warden$tagOrbOnCreation(net.minecraft.world.World world, net.minecraft.util.math.Vec3d pos, net.minecraft.util.math.Vec3d velocity, int amount, CallbackInfo ci) {
        this.warden$source = WardenMod.XP_SOURCE.get();
        this.warden$context = WardenMod.XP_CONTEXT.get();
    }

    @Inject(method = "onPlayerCollision", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/PlayerEntity;addExperience(I)V"))
    private void warden$setXpSourceBeforeAdding(PlayerEntity player, CallbackInfo ci) {
        WardenMod.XP_SOURCE_OVERRIDE.set(warden$source);
        WardenMod.XP_CONTEXT_OVERRIDE.set(warden$context);
    }

    @Inject(method = "onPlayerCollision", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/PlayerEntity;addExperience(I)V", shift = At.Shift.AFTER))
    private void warden$clearXpSourceAfterAdding(PlayerEntity player, CallbackInfo ci) {
        WardenMod.XP_SOURCE_OVERRIDE.set(null);
        WardenMod.XP_CONTEXT_OVERRIDE.set(null);
    }
}
