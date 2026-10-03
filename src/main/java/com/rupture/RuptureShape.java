package com.rupture;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Форма вихря «Энума Элиш» в мире: расширяющаяся труба от меча вдоль луча.
 * Радиус на расстоянии t — {@link RuptureMath#vortexRadius}. Если луч упёрся в блок,
 * вихрь бурит дальше на {@code penetration} блоков и заканчивается полусферой.
 * Разрушается и получает урон ровно то, чего касается этот объём.
 */
public final class RuptureShape {
    /** Ближе этого к мечу ничего не рвём — чтобы не вырвать землю из-под ног самого игрока. */
    public static final double SAFE_DISTANCE = 2.0;

    public final Vec3 start, dir;
    /** Длина трубы: до точки удара + бурение. */
    public final double length;
    public final float charge;
    private final double capRadius;

    public RuptureShape(Vec3 start, Vec3 dir, double length, float charge) {
        this.start = start;
        this.dir = dir.normalize();
        this.length = Math.max(0.0, length);
        this.charge = charge;
        this.capRadius = RuptureMath.vortexRadius(this.length, charge);
    }

    public double radiusAt(double t) {
        return RuptureMath.vortexRadius(Math.min(Math.max(t, 0), length), charge);
    }

    public double maxRadius() {
        return capRadius;
    }

    public Vec3 end() {
        return start.add(dir.scale(length));
    }

    /** Точка внутри вихря? {@code minT} — сколько пропустить у меча. */
    public boolean contains(double x, double y, double z, double minT) {
        double vx = x - start.x, vy = y - start.y, vz = z - start.z;
        double t = vx * dir.x + vy * dir.y + vz * dir.z;
        if (t < minT || t > length + capRadius) return false;
        if (t > length) {
            double ex = start.x + dir.x * length - x, ey = start.y + dir.y * length - y, ez = start.z + dir.z * length - z;
            return ex * ex + ey * ey + ez * ez <= capRadius * capRadius;
        }
        double perp2 = vx * vx + vy * vy + vz * vz - t * t;
        double r = radiusAt(t);
        return perp2 <= r * r;
    }

    public AABB bounds() {
        Vec3 e = end();
        return new AABB(start, e).inflate(capRadius + 1.0);
    }

    /**
     * Консервативные границы по X/Z для слоя y: {minX, maxX, minZ, maxZ} или null, если слой не задет.
     * Нужно, чтобы не перебирать весь огромный параллелепипед косого луча.
     */
    public int[] layerBounds(int y) {
        double R = capRadius + 1.0;
        double cy = y + 0.5;
        double t0, t1;
        if (Math.abs(dir.y) > 1.0e-4) {
            double a = (cy - R - start.y) / dir.y, b = (cy + R - start.y) / dir.y;
            t0 = Math.min(a, b);
            t1 = Math.max(a, b);
        } else {
            if (Math.abs(cy - start.y) > R) return null;
            t0 = Double.NEGATIVE_INFINITY;
            t1 = Double.POSITIVE_INFINITY;
        }
        t0 = Math.max(t0, 0.0);
        t1 = Math.min(t1, length + capRadius);
        if (t0 > t1) return null;
        double xa = start.x + dir.x * t0, xb = start.x + dir.x * t1;
        double za = start.z + dir.z * t0, zb = start.z + dir.z * t1;
        return new int[]{
                (int) Math.floor(Math.min(xa, xb) - R), (int) Math.ceil(Math.max(xa, xb) + R),
                (int) Math.floor(Math.min(za, zb) - R), (int) Math.ceil(Math.max(za, zb) + R)};
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putDouble("sx", start.x); t.putDouble("sy", start.y); t.putDouble("sz", start.z);
        t.putDouble("dx", dir.x); t.putDouble("dy", dir.y); t.putDouble("dz", dir.z);
        t.putDouble("len", length);
        t.putFloat("charge", charge);
        return t;
    }

    public static RuptureShape load(CompoundTag t) {
        return new RuptureShape(new Vec3(t.getDouble("sx"), t.getDouble("sy"), t.getDouble("sz")),
                new Vec3(t.getDouble("dx"), t.getDouble("dy"), t.getDouble("dz")), t.getDouble("len"), t.getFloat("charge"));
    }
}
