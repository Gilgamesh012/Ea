package com.rupture;

/**
 * Общие формулы для сервера и клиента: чтобы кратер точно совпадал с вихрем, который видит игрок.
 */
public final class RuptureMath {
    /** Предел радиуса вихря (и кратера), блоков. */
    public static final double MAX_VORTEX_RADIUS = 100.0;

    private RuptureMath() {}

    /** Насколько вихрь расширяется на каждый блок пути от меча. */
    public static double vortexSpread(float charge) {
        return (0.05 + 0.09 * charge) * 2.5;
    }

    /** Радиус вихря на расстоянии {@code distance} от меча. У клинка узкий, у цели огромный. */
    public static double vortexRadius(double distance, float charge) {
        return Math.min(MAX_VORTEX_RADIUS, 0.3 + charge * 0.5 + distance * vortexSpread(charge));
    }

    /**
     * Глубина кратера вниз от точки удара: радиус + до {@code bonus} блоков на полном заряде.
     * 15% вблизи → неглубокая ямка; 100% вдали → радиус + 50.
     */
    public static double craterDepth(double radius, float charge, double bonus) {
        return radius + bonus * charge * charge;
    }
}
