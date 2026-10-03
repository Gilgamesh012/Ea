package com.rupture.strike;

import com.rupture.RuptureConfig;
import com.rupture.registry.ModTags;
import com.rupture.restore.RestoreJob;
import com.rupture.restore.RuptureSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Выстрел Разрыва: конусообразный луч + удар по области в точке попадания + вырезание блоков.
 * Вызывается только на сервере.
 */
public final class RuptureStrike {
    private static final Vector3f CRIMSON = new Vector3f(0.9f, 0.05f, 0.1f);

    private RuptureStrike() {}

    public static void fire(ServerLevel level, Player player, float charge) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        double range = RuptureConfig.BEAM_RANGE.get();

        // 1. Точка удара: первый блок или первое существо на линии взгляда
        Vec3 end = eye.add(look.scale(range));
        BlockHitResult blockHit = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        Vec3 impact = blockHit.getType() == HitResult.Type.MISS ? end : blockHit.getLocation();
        impact = firstEntityOnRay(level, player, eye, impact).orElse(impact);
        double beamLength = eye.distanceTo(impact);

        // 2. Урон по кривой
        float damage = (float) (RuptureConfig.MAX_DAMAGE.get() * Math.pow(charge, RuptureConfig.DAMAGE_EXPONENT.get()));
        boolean trueDamage = charge >= RuptureConfig.TRUE_DAMAGE_THRESHOLD.get();
        DamageSource source = damageSource(level, player, trueDamage);

        double impactRadius = Mth.lerp(charge, RuptureConfig.IMPACT_RADIUS_MIN.get(), RuptureConfig.IMPACT_RADIUS_MAX.get());
        double cosHalfAngle = Math.cos(Math.toRadians(RuptureConfig.CONE_HALF_ANGLE.get()));

        Set<LivingEntity> targets = new LinkedHashSet<>();
        // 2a. Всё, что попало в конус луча
        double coneWidth = beamLength * Math.tan(Math.toRadians(RuptureConfig.CONE_HALF_ANGLE.get())) + 1.0;
        AABB coneBox = new AABB(eye, impact).inflate(coneWidth);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, coneBox, e -> isValidTarget(e, player))) {
            Vec3 to = e.getBoundingBox().getCenter().subtract(eye);
            double dist = to.length();
            if (dist > 0.01 && dist <= beamLength + 1.0 && to.scale(1.0 / dist).dot(look) >= cosHalfAngle) {
                targets.add(e);
            }
        }
        // 2b. Всё в радиусе точки удара
        double r2 = impactRadius * impactRadius;
        final Vec3 impactPos = impact;
        targets.addAll(level.getEntitiesOfClass(LivingEntity.class, new AABB(impactPos, impactPos).inflate(impactRadius),
                e -> isValidTarget(e, player) && e.getBoundingBox().getCenter().distanceToSqr(impactPos) <= r2));

        for (LivingEntity target : targets) {
            if (trueDamage) target.invulnerableTime = 0;
            target.hurt(source, damage);
        }

        // 3. Блоки: снимок + исчезновение
        if (RuptureConfig.DESTROY_BLOCKS.get() && charge >= RuptureConfig.BLOCK_MIN_CHARGE.get()) {
            double minC = RuptureConfig.BLOCK_MIN_CHARGE.get();
            double t = minC >= 1.0 ? 1.0 : (charge - minC) / (1.0 - minC);
            double blockRadius = Mth.lerp(t, RuptureConfig.BLOCK_RADIUS_MIN.get(), RuptureConfig.BLOCK_RADIUS_MAX.get());
            BlockPos center = BlockPos.containing(impact);

            if (level.mayInteract(player, center)) { // уважаем защиту спавна
                long restoreAt = level.getGameTime() + RuptureConfig.RESTORE_DELAY_SECONDS.get() * 20L;
                RestoreJob job = RestoreJob.carve(level, center, blockRadius, RuptureConfig.MAX_BLOCKS_PER_STRIKE.get(), restoreAt);
                if (!job.isEmpty()) {
                    RuptureSavedData.get(level).addJob(job);
                }
            }
        }

        // 4. Базовый визуал (полноценные трещины/тряска — следующий этап)
        spawnBeamParticles(level, eye, look, beamLength, charge);
        spawnImpactEffects(level, impact, charge);
    }

    private static boolean isValidTarget(LivingEntity e, Player owner) {
        return e != owner && e.isAlive() && !e.isSpectator() && !e.isAlliedTo(owner);
    }

    /** Ближайшее существо, которое пересекает отрезок eye→end. */
    private static Optional<Vec3> firstEntityOnRay(ServerLevel level, Player player, Vec3 eye, Vec3 end) {
        AABB box = new AABB(eye, end).inflate(1.0);
        Vec3 best = null;
        double bestDist = Double.MAX_VALUE;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, e -> isValidTarget(e, player))) {
            Optional<Vec3> hit = e.getBoundingBox().inflate(0.3).clip(eye, end);
            if (hit.isPresent()) {
                double d = eye.distanceToSqr(hit.get());
                if (d < bestDist) {
                    bestDist = d;
                    best = hit.get();
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private static DamageSource damageSource(ServerLevel level, Player player, boolean trueDamage) {
        Holder<DamageType> type = level.registryAccess()
                .registryOrThrow(Registries.DAMAGE_TYPE)
                .getHolderOrThrow(trueDamage ? ModTags.RUPTURE_TRUE : ModTags.RUPTURE);
        return new DamageSource(type, player);
    }

    private static void spawnBeamParticles(ServerLevel level, Vec3 eye, Vec3 look, double length, float charge) {
        double tan = Math.tan(Math.toRadians(RuptureConfig.CONE_HALF_ANGLE.get()));
        DustParticleOptions dust = new DustParticleOptions(CRIMSON, 1.0f + charge * 2f);
        Vec3 start = eye.add(look.scale(1.0)).subtract(0, 0.2, 0);
        for (double d = 0; d < length; d += 0.5) {
            Vec3 p = start.add(look.scale(d));
            double spread = d * tan * 0.5; // конус расширяется к цели
            level.sendParticles(dust, p.x, p.y, p.z, 2 + (int) (charge * 4), spread, spread, spread, 0);
            if (d % 2 == 0) {
                level.sendParticles(ParticleTypes.REVERSE_PORTAL, p.x, p.y, p.z, 2, spread, spread, spread, 0.02);
            }
        }
        level.playSound(null, eye.x, eye.y, eye.z, SoundEvents.WARDEN_SONIC_BOOM, SoundSource.PLAYERS,
                2.0f + charge * 2f, 1.4f - charge * 0.8f);
    }

    private static void spawnImpactEffects(ServerLevel level, Vec3 at, float charge) {
        // «Моргание» мира на любом заряде
        level.sendParticles(ParticleTypes.FLASH, at.x, at.y, at.z, 1, 0, 0, 0, 0);
        if (charge >= 0.5f) {
            level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, at.x, at.y, at.z, 1 + (int) (charge * 3), 1.5, 1.5, 1.5, 0);
        } else {
            level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y, at.z, 3, 0.5, 0.5, 0.5, 0);
        }
        level.playSound(null, at.x, at.y, at.z, SoundEvents.END_PORTAL_SPAWN, SoundSource.PLAYERS,
                1.0f + charge * 3f, 1.6f - charge);
    }
}
