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
    public static final ModConfigSpec.BooleanValue ABSOLUTE_AT_FULL;

    // --- Луч ---
    public static final ModConfigSpec.DoubleValue BEAM_RANGE;
    public static final ModConfigSpec.DoubleValue CONE_HALF_ANGLE;

    // --- Разрушение блоков ---
    public static final ModConfigSpec.BooleanValue DESTROY_BLOCKS;
    public static final ModConfigSpec.DoubleValue BLOCK_MIN_CHARGE;
    public static final ModConfigSpec.DoubleValue CRATER_DEPTH_BONUS;
    public static final ModConfigSpec.IntValue CARVE_BLOCKS_PER_TICK;
    public static final ModConfigSpec.IntValue MAX_BLOCKS_PER_STRIKE;

    // --- Восстановление ---
    public static final ModConfigSpec.IntValue RESTORE_DELAY_SECONDS;
    public static final ModConfigSpec.IntValue RESTORE_BLOCKS_PER_TICK;

    static {
        B.push("charge");
        CHARGE_TIME_SECONDS = B.comment("Время зарядки от 0 до 100%, в секундах")
                .defineInRange("chargeTimeSecondsV2", 10.0, 0.5, 120.0);
        MIN_CHARGE_TO_FIRE = B.comment("Минимальный заряд (0..1), ниже которого удар не происходит")
                .defineInRange("minChargeToFire", 0.05, 0.0, 1.0);
        COOLDOWN_TICKS = B.comment("Перезарядка после удара, в тиках")
                .defineInRange("cooldownTicks", 40, 0, 72000);
        B.pop();

        B.push("damage");
        MAX_DAMAGE = B.comment("Урон чуть ниже 100% заряда (на 100% — абсолютный ваншот, см. absoluteAtFullCharge)")
                .defineInRange("maxDamageV2", 2000.0, 0.0, 1000000.0);
        ABSOLUTE_AT_FULL = B.comment("На 100% заряда «Энума Элиш» — анти-мировой удар: убивает всё живое (кроме игроков в творческом), игнорирует броню, щиты, эффекты и тотем")
                .define("absoluteAtFullCharge", true);
        DAMAGE_EXPONENT = B.comment("Кривая урона: урон = maxDamage * заряд^k (1.0 = строго пропорционально заряду)")
                .defineInRange("damageCurveK", 1.0, 0.1, 10.0);
        TRUE_DAMAGE_THRESHOLD = B.comment("С этого заряда урон игнорирует броню, зачарования, эффекты, щит и кадры неуязвимости")
                .defineInRange("trueDamageThreshold", 0.75, 0.0, 1.0);
        B.pop();

        B.push("beam");
        BEAM_RANGE = B.comment("Дальность «Энума Элиш», блоков")
                .defineInRange("enumaRangeBlocks", 370.0, 4.0, 1024.0);
        CONE_HALF_ANGLE = B.comment("Половина угла конуса луча, градусы")
                .defineInRange("coneHalfAngle", 10.0, 0.0, 60.0);
        B.pop();

        B.push("blocks");
        DESTROY_BLOCKS = B.comment("Разрушать ли блоки (они всегда восстанавливаются)")
                .define("destroyBlocks", true);
        BLOCK_MIN_CHARGE = B.comment("Ниже этого заряда мир только «моргает», блоки целы")
                .defineInRange("blockMinChargeV2", 0.1, 0.0, 1.0);
        CRATER_DEPTH_BONUS = B.comment("Кратер шириной с вихрь в точке удара. Глубина вниз = радиус + этот бонус × заряд²")
                .defineInRange("craterDepthBonus", 50.0, 0.0, 256.0);
        CARVE_BLOCKS_PER_TICK = B.comment("Сколько блоков кратер выгрызает за тик (меньше = плавнее для сервера, дольше обрушение)")
                .defineInRange("carveBlocksPerTick", 40000, 100, 500000);
        MAX_BLOCKS_PER_STRIKE = B.comment("Предохранитель: максимум блоков за один удар")
                .defineInRange("maxBlocksPerStrikeV3", 4000000, 0, 20000000);
        B.pop();

        B.push("restore");
        RESTORE_DELAY_SECONDS = B.comment("Через сколько секунд мир начинает собираться обратно")
                .defineInRange("restoreDelaySeconds", 5, 0, 3600);
        RESTORE_BLOCKS_PER_TICK = B.comment("Сколько блоков восстанавливать за тик (меньше = плавнее для сервера)")
                .defineInRange("restoreBlocksPerTickV3", 20000, 1, 500000);
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
