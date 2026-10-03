package com.rupture.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.rupture.ClientConfig;
import com.rupture.RuptureMath;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Анимация «Энума Элиш»: от меча к цели раскручивается алый спиральный туннель,
 * в точке удара — вспышка-звезда, расходящиеся кольца и схлопывающийся вихрь.
 */
final class StrikeFx {
    static final Vector3f SCARLET = new Vector3f(1.0f, 0.03f, 0.03f);
    static final Vector3f CRIMSON = new Vector3f(0.8f, 0.0f, 0.04f);
    static final Vector3f BLOOD = new Vector3f(0.45f, 0.0f, 0.02f);

    /** Сколько тиков вихрь летит до цели. */
    private static final int TRAVEL = 6;
    private static final int LIFE = 55;
    /** Волны давления живут дольше самого вихря — уходят далеко за горизонт. */
    private static final int WAVE_LIFE = 130;

    private final Vec3 start, end, dir, u, v;
    private final double length;
    private final float charge, impactRadius, craterDepth;

    private int age;

    StrikeFx(Vec3 start, Vec3 end, float charge, float impactRadius, float craterDepth) {
        this.craterDepth = craterDepth;
        this.start = start;
        this.end = end;
        this.charge = charge;
        this.impactRadius = impactRadius;
        Vec3 d = end.subtract(start);
        this.length = Math.max(0.5, d.length());
        this.dir = d.scale(1.0 / length);
        Vec3 up = Math.abs(dir.y) > 0.95 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        this.u = dir.cross(up).normalize();
        this.v = dir.cross(u).normalize();

    }

    /** Радиус вихря растёт с расстоянием от меча: у клинка узкий, у цели огромный. */
    private double radiusAt(double d) {
        return RuptureMath.vortexRadius(d, charge);
    }

    private Vec3 around(double d, double angle, double r) {
        Vec3 c = start.add(dir.scale(d));
        return c.add(u.scale(Math.cos(angle) * r)).add(v.scale(Math.sin(angle) * r));
    }

    private float envelope(float t) {
        float in = Mth.clamp(t / 2f, 0f, 1f);
        float out = t < TRAVEL + 10 ? 1f : Mth.clamp(1f - (t - TRAVEL - 10) / (LIFE - TRAVEL - 10f), 0f, 1f);
        return in * out;
    }

    /** @return false, когда эффект закончился. */
    boolean tick(ClientLevel level) {
        age++;
        RandomSource rnd = level.random;
        double density = ClientConfig.particles();

        if (age == 1) {
            level.addParticle(ParticleTypes.FLASH, start.x, start.y, start.z, 0, 0, 0);
        }

        // Частицы вдоль раскручивающегося туннеля
        if (age <= TRAVEL + 8) {
            double front = length * Math.min(1.0, age / (double) TRAVEL);
            double window = Math.max(12.0, length / TRAVEL * 1.5);
            for (double d = Math.max(0, front - window); d <= front; ) {
                double r = radiusAt(d);
                // Чем шире вихрь, тем больше частиц на витке и тем крупнее они
                int armsHere = 3 + (int) Math.min(9, r * 0.6);
                float size = (float) Math.min(4.0, 1.0 + charge * 1.5 + r * 0.06);
                for (int k = 0; k < armsHere; k++) {
                    double a = age * 0.9 + d * (0.8 / Math.max(1.0, r * 0.3)) + k * (Math.PI * 2 / armsHere);
                    Vec3 p = around(d, a, r);
                    Vec3 tangent = u.scale(-Math.sin(a)).add(v.scale(Math.cos(a))).scale(0.12 + r * 0.01);
                    level.addParticle(new DustColorTransitionOptions(rnd.nextFloat() < 0.7f ? SCARLET : CRIMSON, BLOOD, size),
                            p.x, p.y, p.z, tangent.x, tangent.y, tangent.z);
                }
                d += Math.max(0.6, r * 0.12) / density;
            }
        }

        if (age == TRAVEL) {
            ClientFx.onImpact(end, charge, impactRadius, craterDepth);
            level.addParticle(ParticleTypes.FLASH, end.x, end.y, end.z, 0, 0, 0);
            int emitters = charge >= 0.5f ? 1 + (int) (charge * 3) : 0;
            for (int i = 0; i < emitters; i++) {
                level.addParticle(ParticleTypes.EXPLOSION_EMITTER,
                        end.x + rnd.nextGaussian(), end.y + rnd.nextGaussian(), end.z + rnd.nextGaussian(), 0, 0, 0);
            }
            if (emitters == 0) level.addParticle(ParticleTypes.EXPLOSION, end.x, end.y, end.z, 0, 0, 0);
            // Взрыв алых и фиолетовых осколков
            int burst = (int) ((30 + charge * 150) * density);
            for (int i = 0; i < burst; i++) {
                Vec3 vel = new Vec3(rnd.nextGaussian(), rnd.nextGaussian() + 0.3, rnd.nextGaussian()).normalize().scale(0.3 + rnd.nextDouble() * (0.6 + charge));
                level.addParticle(new DustColorTransitionOptions(rnd.nextBoolean() ? SCARLET : CRIMSON, BLOOD, 1.5f + charge * 2f),
                        end.x, end.y, end.z, vel.x, vel.y, vel.z);
            }
        }

        // После удара: вихрь схлопывается в точку
        if (age > TRAVEL && age < LIFE) {
            float t = (age - TRAVEL) / (float) (LIFE - TRAVEL);
            int n = (int) ((4 + charge * 10) * density);
            double maxR = impactRadius * 1.5;
            for (int i = 0; i < n; i++) {
                double a = age * 0.45 + i * (Math.PI * 2 / n);
                double r = maxR * (1.0 - t) + 0.3;
                double h = (rnd.nextDouble() - 0.5) * impactRadius * (1 - t);
                Vec3 p = end.add(Math.cos(a) * r, h, Math.sin(a) * r);
                level.addParticle(new DustColorTransitionOptions(SCARLET, BLOOD, 1.0f + charge * 1.5f), p.x, p.y, p.z, 0, 0.02, 0);
            }
        }
        return age < WAVE_LIFE;
    }

    /** Светящиеся спиральные ленты туннеля и кольца ударной волны. */
    void render(VertexConsumer vc, Matrix4f pose, Vec3 cam, float partial) {
        float t = age + partial;
        renderPressureWaves(vc, pose, cam, t);
        float env = envelope(t);
        if (env <= 0.01f) return;

        // Ядро гипер-луча: плотный багровый конус по оси вихря (как взгляд с орбиты)
        double coreFront = length * Math.min(1.0, t / TRAVEL);
        double step = Math.max(2.0, length / 40.0);
        for (double d = 0; d < coreFront; d += step) {
            double d2 = Math.min(coreFront, d + step);
            Vec3 a = start.add(dir.scale(d)), b = start.add(dir.scale(d2));
            float w = (float) Math.min(10.0, 0.15 + radiusAt(d2) * 0.28);
            CrackField.quad(vc, pose, cam, a, b, w * 2.2f, 0.75f, 0.0f, 0.03f, env * 0.25f);
            CrackField.quad(vc, pose, cam, a, b, w, 1.0f, 0.06f, 0.05f, env * 0.55f);
            CrackField.quad(vc, pose, cam, a, b, w * 0.3f, 1.0f, 0.55f, 0.45f, env * 0.7f);
        }

        double front = length * Math.min(1.0, t / TRAVEL);
        float spin = t * 0.35f;

        // 3 алые ленты по часовой + 3 фиолетовые внутренние против часовой
        for (int layer = 0; layer < 2; layer++) {
            boolean inner = layer == 1;
            double rMul = inner ? 0.6 : 1.0;
            double twist = inner ? -0.55 : 0.75;
            float r = inner ? 0.6f : 1.0f, g = 0.0f, b = inner ? 0.02f : 0.03f;
            int arms = inner ? 3 : 4;
            for (int k = 0; k < arms; k++) {
                double phase = (inner ? -spin : spin) + k * (Math.PI * 2 / arms);
                Vec3 prev = null;
                double angleAcc = phase;
                double prevD = 0;
                for (double d = 0; d <= front; ) {
                    double rad = radiusAt(d) * rMul;
                    // Скорость закрутки падает с радиусом, чтобы витки не превращались в кашу
                    angleAcc += (d - prevD) * twist / Math.max(1.0, rad * 0.35);
                    prevD = d;
                    Vec3 p = around(d, angleAcc, rad);
                    if (prev != null) {
                        float w = (float) ((inner ? 0.10 : 0.18) * (0.6 + charge) * (1.0 + rad * 0.12));
                        float a = env * (inner ? 0.55f : 0.75f) * (0.6f + 0.4f * (float) (d / length));
                        CrackField.quad(vc, pose, cam, prev, p, w * 2.6f, r * 0.8f, g, b, a * 0.35f); // свечение
                        CrackField.quad(vc, pose, cam, prev, p, w, r, 0.12f, 0.08f, a);           // сердцевина
                    }
                    prev = p;
                    d += Math.max(0.45, rad * 0.1);
                }
            }
        }

        // Кольца, бегущие по туннелю к цели
        double ringGap = Math.max(3.0, length / 20.0);
        for (double d = (t * 2.5) % ringGap; d <= front; d += ringGap) {
            double rr = radiusAt(d) * 1.15;
            ring(vc, pose, cam, start.add(dir.scale(d)), u, v, rr, (float) (0.06 * (0.6 + charge) * (1.0 + rr * 0.1)), env * 0.6f, 1.0f, 0.04f, 0.04f);
        }

        // Ударные кольца в точке удара
        if (t > TRAVEL) {
            float k = (t - TRAVEL) / 14f;
            if (k < 1f) {
                double r = impactRadius * (0.3 + k * 2.2);
                float a = (1f - k) * env;
                ring(vc, pose, cam, end, new Vec3(1, 0, 0), new Vec3(0, 0, 1), r, 0.25f + charge * 0.3f, a, 1f, 0.05f, 0.04f);
                ring(vc, pose, cam, end, u, v, r * 0.7, 0.18f + charge * 0.2f, a, 0.75f, 0.0f, 0.03f);
            }
        }
    }

    /** Волны давления по земле: три кольца разбегаются от точки удара далеко за горизонт. */
    private void renderPressureWaves(VertexConsumer vc, Matrix4f pose, Vec3 cam, float t) {
        if (t <= TRAVEL || charge < 0.3f) return;
        float since = t - TRAVEL;
        double speed = 2.0 + 6.0 * charge;              // блоков за тик
        for (int k = 0; k < 3; k++) {
            float tt = since - k * 8f;
            if (tt <= 0) continue;
            double r = impactRadius + tt * speed;
            float alpha = (float) Math.max(0, 1.0 - tt / (WAVE_LIFE - TRAVEL)) * (0.35f + 0.4f * charge);
            if (alpha < 0.02f) continue;
            float w = (float) (0.4 + r * 0.01);
            int seg = 96;
            Vec3 prev = null;
            for (int i = 0; i <= seg; i++) {
                double a = i * (Math.PI * 2 / seg);
                Vec3 p = end.add(Math.cos(a) * r, 0.3, Math.sin(a) * r);
                if (prev != null) {
                    CrackField.quad(vc, pose, cam, prev, p, w * 2.5f, 0.8f, 0.0f, 0.03f, alpha * 0.3f);
                    CrackField.quad(vc, pose, cam, prev, p, w, 1.0f, 0.12f, 0.08f, alpha);
                }
                prev = p;
            }
        }
    }

    private static void ring(VertexConsumer vc, Matrix4f pose, Vec3 cam, Vec3 c, Vec3 a, Vec3 b, double r, float w,
                             float alpha, float cr, float cg, float cb) {
        if (alpha <= 0.01f) return;
        int seg = 32;
        Vec3 prev = null;
        for (int i = 0; i <= seg; i++) {
            double ang = i * (Math.PI * 2 / seg);
            Vec3 p = c.add(a.scale(Math.cos(ang) * r)).add(b.scale(Math.sin(ang) * r));
            if (prev != null) {
                CrackField.quad(vc, pose, cam, prev, p, w * 2.5f, cr, cg, cb, alpha * 0.3f);
                CrackField.quad(vc, pose, cam, prev, p, w, cr, cg + 0.12f, cb + 0.08f, alpha);
            }
            prev = p;
        }
    }
}
