package com.rupture.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Набор светящихся алых трещин в воздухе вокруг точки. Трещина «прорастает», когда заряд
 * превышает её порог, поэтому чем выше заряд — тем больше и длиннее трещин.
 */
final class CrackField {
    private record Crack(float threshold, List<Vec3> points, float width, boolean sky) {}

    private final List<Crack> cracks = new ArrayList<>();
    final Vec3 anchor;
    /** Текущий заряд 0..1 (для поля вокруг заряжающего игрока обновляется каждый тик). */
    float charge;
    private float fade = 1f;
    private boolean dying;
    /** -1 = живёт, пока его не «отпустят»; иначе — сколько тиков осталось. */
    private int life = -1;

    private CrackField(Vec3 anchor) {
        this.anchor = anchor;
    }

    /** Поле вокруг заряжающего: трещины на оболочке minR..maxR + решётка в небе. */
    static CrackField aroundCaster(Vec3 anchor, long seed) {
        CrackField f = new CrackField(anchor);
        RandomSource rnd = RandomSource.create(seed);
        for (int i = 0; i < 56; i++) {
            Vec3 dir = new Vec3(rnd.nextGaussian(), Math.abs(rnd.nextGaussian()) * 0.8 + 0.1, rnd.nextGaussian()).normalize();
            double r = 1.8 + rnd.nextDouble() * 4.5;
            Vec3 start = anchor.add(0, 0.9, 0).add(dir.scale(r));
            f.addCrack(rnd, start, rnd.nextFloat(), 0.011f, false, 4 + rnd.nextInt(6), 0.25, 0.6);
        }
        // Алая решётка в небе (как разлом над головой) — появляется во второй половине заряда
        for (int i = 0; i < 18; i++) {
            Vec3 start = anchor.add((rnd.nextDouble() - 0.5) * 30, 10 + rnd.nextDouble() * 18, (rnd.nextDouble() - 0.5) * 30);
            f.addCrack(rnd, start, 0.45f + rnd.nextFloat() * 0.55f, 0.04f, true, 3 + rnd.nextInt(4), 2.0, 4.5);
        }
        return f;
    }

    /** Поле вокруг точки удара: зафиксированный заряд, само гаснет через {@code ticks}. */
    static CrackField aroundImpact(Vec3 anchor, long seed, float radius, float charge, int ticks) {
        CrackField f = new CrackField(anchor);
        RandomSource rnd = RandomSource.create(seed);
        int count = 12 + (int) (charge * 40);
        for (int i = 0; i < count; i++) {
            Vec3 dir = new Vec3(rnd.nextGaussian(), rnd.nextGaussian() * 0.7, rnd.nextGaussian()).normalize();
            Vec3 start = anchor.add(dir.scale(radius * (0.6 + rnd.nextDouble() * 0.7)));
            float w = (0.016f + charge * 0.014f) * (1f + radius * 0.03f);
            double segLen = 0.3 + radius * 0.04;
            f.addCrack(rnd, start, 0f, w, false, 5 + rnd.nextInt(6), segLen, segLen * 3);
        }
        f.charge = 1f;
        f.life = ticks;
        return f;
    }

    /**
     * «Расколотые небо и земля»: огромный рваный разлом в небе над кратером.
     * Ширина — во весь кратер, висит, пока мир не соберётся.
     */
    static CrackField skyRift(Vec3 impact, long seed, float radius, float charge, int ticks) {
        CrackField f = new CrackField(impact);
        RandomSource rnd = RandomSource.create(seed);
        double span = Math.max(12.0, radius * 2.4);
        double y = impact.y + 35 + radius * 0.6;
        double ang = rnd.nextDouble() * Math.PI;
        Vec3 dir = new Vec3(Math.cos(ang), 0, Math.sin(ang));
        Vec3 start = new Vec3(impact.x, y, impact.z).subtract(dir.scale(span / 2));
        int segs = 14 + (int) (charge * 10);
        double segLen = span / segs;
        float width = 0.12f + charge * 0.25f + radius * 0.01f;
        // главный шов
        List<Vec3> pts = new ArrayList<>();
        Vec3 p = start;
        pts.add(p);
        for (int i = 0; i < segs; i++) {
            Vec3 side = new Vec3(-dir.z, 0, dir.x).scale((rnd.nextDouble() - 0.5) * segLen * 0.9);
            p = p.add(dir.scale(segLen)).add(side).add(0, (rnd.nextDouble() - 0.5) * segLen * 0.5, 0);
            pts.add(p);
            // ответвления — трещины расходятся по небу
            if (rnd.nextFloat() < 0.55f) {
                Vec3 bdir = new Vec3(rnd.nextGaussian(), rnd.nextGaussian() * 0.3, rnd.nextGaussian()).normalize();
                f.addCrack(rnd, p, 0f, width * 0.45f, true, 3 + rnd.nextInt(4), segLen * 0.4, segLen * 1.2);
            }
        }
        f.cracks.add(new Crack(0f, pts, width, true));
        f.charge = 1f;
        f.life = ticks;
        return f;
    }

    private void addCrack(RandomSource rnd, Vec3 start, float threshold, float width, boolean sky, int segments, double minLen, double maxLen) {
        List<Vec3> pts = new ArrayList<>();
        pts.add(start);
        Vec3 dir = sky
                ? new Vec3(rnd.nextGaussian(), rnd.nextGaussian() * 0.25, rnd.nextGaussian()).normalize()
                : new Vec3(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian()).normalize();
        Vec3 p = start;
        for (int s = 0; s < segments; s++) {
            Vec3 jitter = new Vec3(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian()).scale(sky ? 0.35 : 0.8);
            dir = dir.add(jitter).normalize();
            p = p.add(dir.scale(minLen + rnd.nextDouble() * (maxLen - minLen)));
            pts.add(p);
            // Ответвление
            if (!sky && s > 0 && rnd.nextFloat() < 0.25f) {
                List<Vec3> branch = new ArrayList<>();
                branch.add(p);
                Vec3 bd = new Vec3(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian()).normalize();
                Vec3 bp = p;
                for (int b = 0; b < 2 + rnd.nextInt(3); b++) {
                    bd = bd.add(new Vec3(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian()).scale(0.7)).normalize();
                    bp = bp.add(bd.scale(minLen * 0.8 + rnd.nextDouble() * (maxLen - minLen) * 0.6));
                    branch.add(bp);
                }
                cracks.add(new Crack(Math.min(1f, threshold + 0.08f), branch, width * 0.7f, false));
            }
        }
        cracks.add(new Crack(threshold, pts, width, sky));
    }

    void release() {
        dying = true;
    }

    /** @return false, когда поле полностью погасло. */
    boolean tick() {
        if (life > 0) {
            life--;
            if (life < 25) fade = life / 25f;
            return life > 0;
        }
        if (dying) {
            fade -= 0.07f;
            return fade > 0f;
        }
        return true;
    }

    void render(VertexConsumer vc, Matrix4f pose, Vec3 cam, float time) {
        if (fade <= 0f) return;
        int idx = 0;
        for (Crack c : cracks) {
            idx++;
            float vis = Mth.clamp((charge - c.threshold) / 0.12f, 0f, 1f) * fade;
            if (vis <= 0.001f) continue;
            float flicker = 0.75f + 0.25f * Mth.sin(time * 0.6f + idx * 1.7f);
            List<Vec3> pts = c.points;
            float segsVisible = (pts.size() - 1) * vis; // трещина прорастает
            for (int s = 0; s < pts.size() - 1 && s < segsVisible; s++) {
                Vec3 a = pts.get(s);
                Vec3 b = pts.get(s + 1);
                float part = Math.min(1f, segsVisible - s);
                if (part < 1f) b = a.add(b.subtract(a).scale(part));
                float glowW = c.width * (c.sky ? 3.0f : 3.2f);
                // Широкое алое свечение + яркая сердцевина
                quad(vc, pose, cam, a, b, glowW, 0.85f, 0.03f, 0.05f, 0.30f * vis * flicker);
                quad(vc, pose, cam, a, b, c.width, 1.0f, 0.15f, 0.1f, 0.95f * vis * flicker);
            }
        }
    }

    /** Косинус половины угла «окна прицела» (~18°). */
    static final double VIEW_WINDOW_COS = Math.cos(Math.toRadians(18.0));
    private static boolean windowActive;
    private static Vec3 windowLook = Vec3.ZERO;

    static void setViewWindow(boolean active, Vec3 cam, Vec3 look) {
        windowActive = active;
        windowLook = look;
    }

    /** Отрезок a→b шириной w, развёрнутый к камере. Рисуется с обеих сторон. */
    static void quad(VertexConsumer vc, Matrix4f m, Vec3 cam, Vec3 a, Vec3 b, float w, float r, float g, float bl, float al) {
        if (windowActive) {
            double mx = (a.x + b.x) * 0.5 - cam.x, my = (a.y + b.y) * 0.5 - cam.y, mz = (a.z + b.z) * 0.5 - cam.z;
            double md = Math.sqrt(mx * mx + my * my + mz * mz);
            if (md > 1.0e-3) {
                double cos = (mx * windowLook.x + my * windowLook.y + mz * windowLook.z) / md;
                // плавно: на краю окна полная яркость, в центре — 10%
                if (cos > VIEW_WINDOW_COS - 0.03) {
                    double k = Mth.clamp((cos - (VIEW_WINDOW_COS - 0.03)) / 0.06, 0.0, 1.0);
                    al *= (float) (1.0 - 0.9 * k);
                }
            }
        }
        Vec3 dir = b.subtract(a);
        Vec3 toCam = cam.subtract(a.add(b).scale(0.5));
        Vec3 side = dir.cross(toCam);
        double len = side.length();
        if (len < 1.0e-6) return;
        side = side.scale(w / len);
        float ax = (float) (a.x - cam.x), ay = (float) (a.y - cam.y), az = (float) (a.z - cam.z);
        float bx = (float) (b.x - cam.x), by = (float) (b.y - cam.y), bz = (float) (b.z - cam.z);
        float sx = (float) side.x, sy = (float) side.y, sz = (float) side.z;
        // лицевая сторона
        vc.addVertex(m, ax - sx, ay - sy, az - sz).setColor(r, g, bl, al);
        vc.addVertex(m, ax + sx, ay + sy, az + sz).setColor(r, g, bl, al);
        vc.addVertex(m, bx + sx, by + sy, bz + sz).setColor(r, g, bl, al);
        vc.addVertex(m, bx - sx, by - sy, bz - sz).setColor(r, g, bl, al);
        // обратная сторона
        vc.addVertex(m, bx - sx, by - sy, bz - sz).setColor(r, g, bl, al);
        vc.addVertex(m, bx + sx, by + sy, bz + sz).setColor(r, g, bl, al);
        vc.addVertex(m, ax + sx, ay + sy, az + sz).setColor(r, g, bl, al);
        vc.addVertex(m, ax - sx, ay - sy, az - sz).setColor(r, g, bl, al);
    }
}
