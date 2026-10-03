package com.rupture;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Серверный конфиг: world/serverconfig/rupture-server.toml
 */
public final class RuptureConfig {
    private static final ModConfigSpec.Builder B = new ModConfigSpec.Builder();

    // --- Зарядка ---
    public static final ModConfigSpec.DoubleValue CHARGE_TIME_SECONDS;
    public static final ModConfigSpec.DoubleValue MIN_CHARGE_TO_FIRE;
    public static final ModConfigSpec.IntValue COOLDOWN_TICKS;

    // --- Урон ---
    public static final ModConfigSpec.DoubleValue MAX_DAMAGE;
    public static final ModConfigSpec.DoubleValue DAMAGE_EXPONENT;
    public static final ModConfigSpec.DoubleValue TRUE_DAMAGE_THRESHOLD;

    // --- Луч ---
    public static final ModConfigSpec.DoubleValue BEAM_RANGE;
    public static final ModConfigSpec.DoubleValue CONE_HALF_ANGLE;
    public static final ModConfigSpec.DoubleValue IMPACT_RADIUS_MAX;

    // --- Разрушение блоков ---
    public static final ModConfigSpec.BooleanValue DESTROY_BLOCKS;
    public static final ModConfigSpec.DoubleValue BLOCK_MIN_CHARGE;
    public static final ModConfigSpec.DoubleValue BLOCK_RADIUS_MAX;
    public static final ModConfigSpec.IntValue MAX_BLOCKS_PER_STRIKE;

    // --- Восстановление ---
    public static final ModConfigSpec.IntValue RESTORE_DELAY_SECONDS;
    public static final ModConfigSpec.IntValue RESTORE_BLOCKS_PER_TICK;

    static {
        B.push("charge");
        CHARGE_TIME_SECONDS = B.comment("Время зарядки от 0 до 100%, в секундах")
                .defineInRange("chargeTimeSeconds", 15.0, 0.5, 120.0);
        MIN_CHARGE_TO_FIRE = B.comment("Минимальный заряд (0..1), ниже которого удар не происходит")
                .defineInRange("minChargeToFire", 0.05, 0.0, 1.0);
        COOLDOWN_TICKS = B.comment("Перезарядка после удара, в тиках")
                .defineInRange("cooldownTicks", 40, 0, 72000);
        B.pop();

        B.push("damage");
        MAX_DAMAGE = B.comment("Урон на 100% заряда")
                .defineInRange("maxDamage", 200.0, 0.0, 100000.0);
        DAMAGE_EXPONENT = B.comment("Кривая урона: урон = maxDamage * заряд^k (1.0 = строго пропорционально заряду)")
                .defineInRange("damageCurveK", 1.0, 0.1, 10.0);
        TRUE_DAMAGE_THRESHOLD = B.comment("С этого заряда урон игнорирует броню, зачарования, эффекты, щит и кадры неуязвимости")
                .defineInRange("trueDamageThreshold", 0.75, 0.0, 1.0);
        B.pop();

        B.push("beam");
        BEAM_RANGE = B.comment("Дальность «Энума Элиш», блоков")
                .defineInRange("beamRangeBlocks", 256.0, 4.0, 1024.0);
        CONE_HALF_ANGLE = B.comment("Половина угла конуса луча, градусы")
                .defineInRange("coneHalfAngle", 10.0, 0.0, 60.0);
        IMPACT_RADIUS_MAX = B.comment("Радиус урона в точке удара на 100% заряда (растёт пропорционально заряду)")
                .defineInRange("impactRadiusMax", 8.0, 0.0, 64.0);
        B.pop();

        B.push("blocks");
        DESTROY_BLOCKS = B.comment("Разрушать ли блоки (они всегда восстанавливаются)")
                .define("destroyBlocks", true);
        BLOCK_MIN_CHARGE = B.comment("Ниже этого заряда мир только «моргает», блоки целы")
                .defineInRange("blockMinChargeV2", 0.1, 0.0, 1.0);
        BLOCK_RADIUS_MAX = B.comment("Радиус разрыва блоков на 100% заряда (растёт пропорционально заряду)")
                .defineInRange("blockRadiusMax", 10.0, 0.0, 32.0);
        MAX_BLOCKS_PER_STRIKE = B.comment("Предохранитель: максимум блоков за один удар")
                .defineInRange("maxBlocksPerStrike", 8000, 0, 200000);
        B.pop();

        B.push("restore");
        RESTORE_DELAY_SECONDS = B.comment("Через сколько секунд мир начинает собираться обратно")
                .defineInRange("restoreDelaySeconds", 5, 0, 3600);
        RESTORE_BLOCKS_PER_TICK = B.comment("Сколько блоков восстанавливать за тик (меньше = плавнее для сервера)")
                .defineInRange("restoreBlocksPerTick", 200, 1, 10000);
        B.pop();
    }

    public static final ModConfigSpec SPEC = B.build();

    private RuptureConfig() {}

    /** Время зарядки в тиках; безопасно вызывать и на клиенте до загрузки конфига. */
    public static int chargeTicks() {
        double sec;
        try {
            sec = CHARGE_TIME_SECONDS.get();
        } catch (IllegalStateException notLoaded) {
            sec = CHARGE_TIME_SECONDS.getDefault();
        }
        return Math.max(1, (int) Math.round(sec * 20.0));
    }
}
