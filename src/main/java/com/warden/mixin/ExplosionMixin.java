package com.warden.mixin;

import com.warden.WardenMod;
import com.warden.config.WardenConfig;
import net.minecraft.entity.Entity;
import net.minecraft.entity.TntEntity;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.projectile.FireballEntity;
import net.minecraft.entity.projectile.WitherSkullEntity;
import net.minecraft.entity.vehicle.TntMinecartEntity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.explosion.ExplosionBehavior;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Caps World#createExplosion power based on entity/source type.
 *
 * BedBlock and RespawnAnchorBlock both call the Vec3d overload (not the DDD overload),
 * so both overloads are hooked.
 *
 * Distinguishing bed vs respawn_anchor: BedBlock passes null for ExplosionBehavior;
 * RespawnAnchorBlock passes a non-null ExplosionBehavior instance. Both use the
 * BAD_RESPAWN_POINT damage type, so the behavior-null check is the reliable tell.
 *
 * The power argument is rewritten in place rather than re-invoking the method under a
 * re-entry guard: end crystals and tnt minecarts detonate synchronously inside the blast
 * that hits them, and a guard held for the whole call let those chained explosions through
 * at full power. If the cap is 0 the explosion is cancelled outright.
 */
@Mixin(World.class)
public abstract class ExplosionMixin {

    private static final String DDD = "createExplosion(Lnet/minecraft/entity/Entity;Lnet/minecraft/entity/damage/DamageSource;Lnet/minecraft/world/explosion/ExplosionBehavior;DDDFZLnet/minecraft/world/World$ExplosionSourceType;)V";
    private static final String VEC = "createExplosion(Lnet/minecraft/entity/Entity;Lnet/minecraft/entity/damage/DamageSource;Lnet/minecraft/world/explosion/ExplosionBehavior;Lnet/minecraft/util/math/Vec3d;FZLnet/minecraft/world/World$ExplosionSourceType;)V";

    @Inject(method = DDD, at = @At("HEAD"), cancellable = true)
    private void warden$cancelExplosionDDD(@Nullable Entity entity, @Nullable DamageSource source, @Nullable ExplosionBehavior behavior,
                                           double x, double y, double z, float power, boolean createFire,
                                           World.ExplosionSourceType sourceType, CallbackInfo ci) {
        if (cappedPower(entity, source, behavior, sourceType, power) <= 0f) ci.cancel();
    }

    @ModifyVariable(method = DDD, at = @At("HEAD"), argsOnly = true)
    private float warden$capExplosionDDD(float power, @Nullable Entity entity, @Nullable DamageSource source, @Nullable ExplosionBehavior behavior,
                                         double x, double y, double z, float original, boolean createFire,
                                         World.ExplosionSourceType sourceType) {
        return cappedPower(entity, source, behavior, sourceType, power);
    }

    @Inject(method = VEC, at = @At("HEAD"), cancellable = true)
    private void warden$cancelExplosionVec3d(@Nullable Entity entity, @Nullable DamageSource source, @Nullable ExplosionBehavior behavior,
                                             Vec3d pos, float power, boolean createFire,
                                             World.ExplosionSourceType sourceType, CallbackInfo ci) {
        if (cappedPower(entity, source, behavior, sourceType, power) <= 0f) ci.cancel();
    }

    @ModifyVariable(method = VEC, at = @At("HEAD"), argsOnly = true)
    private float warden$capExplosionVec3d(float power, @Nullable Entity entity, @Nullable DamageSource source, @Nullable ExplosionBehavior behavior,
                                           Vec3d pos, float original, boolean createFire,
                                           World.ExplosionSourceType sourceType) {
        return cappedPower(entity, source, behavior, sourceType, power);
    }

    /** The configured cap for this explosion, or {@code power} unchanged when none applies. */
    private static float cappedPower(@Nullable Entity entity, @Nullable DamageSource source, @Nullable ExplosionBehavior behavior,
                                     World.ExplosionSourceType sourceType, float power) {
        if (!WardenMod.CONFIG.explosionLimitsEnabled) return power;

        String sourceKey = resolveSourceKey(entity, source, behavior, sourceType);
        if (sourceKey == null) return power;

        WardenConfig.ExplosionSourceConfig srcCfg = WardenMod.CONFIG.explosionSources.get(sourceKey);
        if (srcCfg == null || !srcCfg.enabled) return power;

        float capped = Math.min(power, srcCfg.maxPower);
        if (capped < power) {
            WardenMod.LOGGER.debug("[Warden] {} explosion capped: {} -> {}", sourceKey, power, capped);
        }
        return capped;
    }

    private static @Nullable String resolveSourceKey(
            @Nullable Entity entity,
            @Nullable DamageSource source,
            @Nullable ExplosionBehavior behavior,
            World.ExplosionSourceType sourceType) {
        if (entity != null) {
            if (entity instanceof TntEntity)          return "tnt";
            if (entity instanceof TntMinecartEntity)  return "tnt_minecart";
            if (entity instanceof CreeperEntity)      return "creeper";
            if (entity instanceof EndCrystalEntity)   return "end_crystal";
            if (entity instanceof WitherEntity)       return "wither";
            if (entity instanceof WitherSkullEntity)  return "wither_skull";
            if (entity instanceof FireballEntity)     return "ghast";
            return null;
        }
        // No entity — block-triggered explosion.
        // Both BedBlock and RespawnAnchorBlock use BAD_RESPAWN_POINT damage type.
        // The reliable distinction: BedBlock passes null ExplosionBehavior;
        // RespawnAnchorBlock passes a non-null ExplosionBehavior instance.
        if (sourceType == World.ExplosionSourceType.BLOCK) {
            if (source != null && source.getTypeRegistryEntry().matchesKey(DamageTypes.BAD_RESPAWN_POINT)) {
                return behavior != null ? "respawn_anchor" : "bed";
            }
        }
        return null;
    }
}
