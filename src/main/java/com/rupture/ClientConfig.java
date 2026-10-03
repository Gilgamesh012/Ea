package com.rupture;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Клиентский конфиг (config/rupture-client.toml): личные настройки эффектов.
 */
public final class ClientConfig {
    private static final ModConfigSpec.Builder B = new ModConfigSpec.Builder();

    public static final ModConfigSpec.DoubleValue SCREEN_SHAKE = B
            .comment("Сила тряски камеры (0 = выключить)")
            .defineInRange("screenShake", 1.0, 0.0, 3.0);
    public static final ModConfigSpec.BooleanValue FLASHES = B
            .comment("Вспышки экрана при ударе")
            .define("flashes", true);
    public static final ModConfigSpec.BooleanValue CRACKS = B
            .comment("Трещины в воздухе")
            .define("cracks", true);
    public static final ModConfigSpec.DoubleValue PARTICLES = B
            .comment("Множитель количества частиц вихря")
            .defineInRange("particleDensity", 1.0, 0.1, 3.0);

    public static final ModConfigSpec SPEC = B.build();

    private ClientConfig() {}

    public static double shake() { return safe(SCREEN_SHAKE, 1.0); }
    public static boolean flashes() { try { return FLASHES.get(); } catch (IllegalStateException e) { return true; } }
    public static boolean cracks() { try { return CRACKS.get(); } catch (IllegalStateException e) { return true; } }
    public static double particles() { return safe(PARTICLES, 1.0); }

    private static double safe(ModConfigSpec.DoubleValue v, double def) {
        try { return v.get(); } catch (IllegalStateException e) { return def; }
    }
}
