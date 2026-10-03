package com.rupture.strike;

import com.rupture.RuptureConfig;
import com.rupture.network.StrikeFxPayload;
import com.rupture.registry.ModSounds;
import com.rupture.registry.ModTags;
import com.rupture.restore.RestoreJob;
import com.rupture.restore.RuptureSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Выстрел Разрыва: конусообразный луч + удар по области в точке попадания + вырезание блоков.
 * Вызывается только на сервере.
 */
public final class RuptureStrike {
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

        // Радиус урона растёт пропорционально заряду
        double impactRadius = Math.max(1.5, RuptureConfig.IMPACT_RADIUS_MAX.get() * charge);
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
            // Радиус разрыва растёт пропорционально заряду
            double blockRadius = RuptureConfig.BLOCK_RADIUS_MAX.get() * charge;
            BlockPos center = BlockPos.containing(impact);

            if (blockRadius >= 1.0 && level.mayInteract(player, center)) { // уважаем защиту спавна
                long restoreAt = level.getGameTime() + RuptureConfig.RESTORE_DELAY_SECONDS.get() * 20L;
                RestoreJob job = RestoreJob.carve(level, center, blockRadius, RuptureConfig.MAX_BLOCKS_PER_STRIKE.get(), restoreAt);
                if (!job.isEmpty()) {
                    RuptureSavedData.get(level).addJob(job);
                }
            }
        }

        // 4. Визуал рисуют клиенты (алый вихрь, вспышка, тряска), звук — сервер
        Vec3 beamStart = eye.add(look.scale(0.8)).subtract(0, 0.25, 0);
        Vec3 mid = beamStart.add(impact).scale(0.5);
        PacketDistributor.sendToPlayersNear(level, null, mid.x, mid.y, mid.z, 160.0 + beamLength,
                new StrikeFxPayload(beamStart, impact, charge, (float) impactRadius));
        playStrikeSounds(level, eye, impact, charge);
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

    private static void playStrikeSounds(ServerLevel level, Vec3 from, Vec3 at, float charge) {
        // На слабом заряде звук тише и выше, на полном — громче и ниже
        float vol = 0.5f + charge * 2.5f;
        float pitch = 1.25f - charge * 0.3f;
        level.playSound(null, from.x, from.y, from.z, ModSounds.RELEASE.get(), SoundSource.PLAYERS, vol, pitch);
        level.playSound(null, at.x, at.y, at.z, ModSounds.IMPACT.get(), SoundSource.PLAYERS, vol, pitch);
    }
}
