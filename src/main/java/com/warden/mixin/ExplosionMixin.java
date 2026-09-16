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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts World#createExplosion at HEAD, caps the power based on entity/source type.
 *
 * BedBlock and RespawnAnchorBlock both call the Vec3d overload (not the DDD overload),
 * so we inject into BOTH overloads. A ThreadLocal re-entry guard prevents recursion.
 *
 * Distinguishing bed vs respawn_anchor: BedBlock passes null for ExplosionBehavior;
 * RespawnAnchorBlock passes a non-null ExplosionBehavior instance. Both use the
 * BAD_RESPAWN_POINT damage type, so the behavior-null check is the reliable tell.
 *
 * If capped power > 0: re-invokes with the capped value and cancels the original.
 * If capped power <= 0: cancels the explosion entirely.
 */
@Mixin(World.class)
public abstract class ExplosionMixin {

    private static final ThreadLocal<Boolean> APPLYING = ThreadLocal.withInitial(() -> false);

    // -------------------------------------------------------------------------
    // DDD overload — used by entity-based explosions (TNT, creeper, etc.)
    // -------------------------------------------------------------------------

    @Inject(
        method = "createExplosion(Lnet/minecraft/entity/Entity;Lnet/minecraft/entity/damage/DamageSource;Lnet/minecraft/world/explosion/ExplosionBehavior;DDDFZLnet/minecraft/world/World$ExplosionSourceType;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void limitExplosionDDD(
        @Nullable Entity entity,
        @Nullable DamageSource source,
        @Nullable ExplosionBehavior behavior,
        double x, double y, double z,
        float power,
        boolean createFire,
        World.ExplosionSourceType sourceType,
        CallbackInfo ci
    ) {
        if (APPLYING.get()) return;
        if (!WardenMod.CONFIG.explosionLimitsEnabled) return;

        String sourceKey = resolveSourceKey(entity, source, behavior, sourceType);
        if (sourceKey == null) return;

        WardenConfig.ExplosionSourceConfig srcCfg = WardenMod.CONFIG.explosionSources.get(sourceKey);
        if (srcCfg == null || !srcCfg.enabled) return;

        float capped = Math.min(power, srcCfg.maxPower);
        if (capped >= power) return;

        WardenMod.LOGGER.debug("[Warden] {} explosion capped: {} -> {}", sourceKey, power, capped);

        if (capped <= 0f) {
            ci.cancel();
            return;
        }

        World world = (World)(Object)this;
        APPLYING.set(true);
        try {
            world.createExplosion(entity, source, behavior, x, y, z, capped, createFire, sourceType);
            ci.cancel();
        } finally {
            APPLYING.set(false);
        }
    }

    // -------------------------------------------------------------------------
    // Vec3d overload — used by BedBlock and RespawnAnchorBlock
    // -------------------------------------------------------------------------

    @Inject(
        method = "createExplosion(Lnet/minecraft/entity/Entity;Lnet/minecraft/entity/damage/DamageSource;Lnet/minecraft/world/explosion/ExplosionBehavior;Lnet/minecraft/util/math/Vec3d;FZLnet/minecraft/world/World$ExplosionSourceType;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void limitExplosionVec3d(
        @Nullable Entity entity,
        @Nullable DamageSource source,
        @Nullable ExplosionBehavior behavior,
        Vec3d pos,
        float power,
        boolean createFire,
        World.ExplosionSourceType sourceType,
        CallbackInfo ci
    ) {
        if (APPLYING.get()) return;
        if (!WardenMod.CONFIG.explosionLimitsEnabled) return;

        String sourceKey = resolveSourceKey(entity, source, behavior, sourceType);
        if (sourceKey == null) return;

        WardenConfig.ExplosionSourceConfig srcCfg = WardenMod.CONFIG.explosionSources.get(sourceKey);
        if (srcCfg == null || !srcCfg.enabled) return;

        float capped = Math.min(power, srcCfg.maxPower);
        if (capped >= power) return;

        WardenMod.LOGGER.debug("[Warden] {} explosion capped: {} -> {}", sourceKey, power, capped);

        if (capped <= 0f) {
            ci.cancel();
            return;
        }

        World world = (World)(Object)this;
        APPLYING.set(true);
        try {
            world.createExplosion(entity, source, behavior, pos, capped, createFire, sourceType);
            ci.cancel();
        } finally {
            APPLYING.set(false);
        }
    }

    // -------------------------------------------------------------------------

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
