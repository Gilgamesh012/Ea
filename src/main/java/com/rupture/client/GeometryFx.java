package com.rupture.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * «Геометрические фракталы» вокруг Эа (по визуалу CloverWorks):
 * идеальные концентрические круги у клинка и геодезическая сетка-сфера вокруг владельца,
 * которые проступают по мере заряда.
 */
final class GeometryFx {
    private static final List<Vec3> VERTS = new ArrayList<>();
    private static final List<int[]> EDGES = new ArrayList<>();
    /** Порог появления каждого ребра (0..1) — сетка «прорастает» с зарядом. */
    private static final List<Float> EDGE_THRESHOLD = new ArrayList<>();

    static {
        buildGeodesic();
    }

    private GeometryFx() {}

    /** Икосаэдр, один раз подразделённый: 42 вершины, 120 рёбер. */
    private static void buildGeodesic() {
        double t = (1 + Math.sqrt(5)) / 2;
        double[][] v = {{-1, t, 0}, {1, t, 0}, {-1, -t, 0}, {1, -t, 0}, {0, -1, t}, {0, 1, t}, {0, -1, -t}, {0, 1, -t},
                {t, 0, -1}, {t, 0, 1}, {-t, 0, -1}, {-t, 0, 1}};
        int[][] f = {{0, 11, 5}, {0, 5, 1}, {0, 1, 7}, {0, 7, 10}, {0, 10, 11}, {1, 5, 9}, {5, 11, 4}, {11, 10, 2}, {10, 7, 6},
                {7, 1, 8}, {3, 9, 4}, {3, 4, 2}, {3, 2, 6}, {3, 6, 8}, {3, 8, 9}, {4, 9, 5}, {2, 4, 11}, {6, 2, 10}, {8, 6, 7}, {9, 8, 1}};
        for (double[] p : v) VERTS.add(new Vec3(p[0], p[1], p[2]).normalize());
        Map<Long, Integer> mid = new HashMap<>();
        List<int[]> faces = new ArrayList<>();
        for (int[] tri : f) {
            int a = midpoint(tri[0], tri[1], mid), b = midpoint(tri[1], tri[2], mid), c = midpoint(tri[2], tri[0], mid);
            faces.add(new int[]{tri[0], a, c});
            faces.add(new int[]{tri[1], b, a});
            faces.add(new int[]{tri[2], c, b});
            faces.add(new int[]{a, b, c});
        }
        java.util.Set<Long> seen = new java.util.HashSet<>();
        java.util.Random rnd = new java.util.Random(77);
        for (int[] tri : faces) {
            for (int k = 0; k < 3; k++) {
                int i = tri[k], j = tri[(k + 1) % 3];
                long key = ((long) Math.min(i, j) << 32) | Math.max(i, j);
                if (seen.add(key)) {
                    EDGES.add(new int[]{i, j});
                    // нижние рёбра появляются раньше — сетка «поднимается» от земли
                    double y = (VERTS.get(i).y + VERTS.get(j).y) * 0.5;
                    EDGE_THRESHOLD.add((float) Mth.clamp(0.15 + (y + 1) * 0.3 + rnd.nextFloat() * 0.2, 0, 0.95));
                }
            }
        }
    }

    private static int midpoint(int i, int j, Map<Long, Integer> cache) {
        long key = ((long) Math.min(i, j) << 32) | Math.max(i, j);
        Integer idx = cache.get(key);
        if (idx != null) return idx;
        VERTS.add(VERTS.get(i).add(VERTS.get(j)).normalize());
        cache.put(key, VERTS.size() - 1);
        return VERTS.size() - 1;
    }

    /** Геодезическая сфера вокруг владельца: медленно вращается, рёбра проступают с зарядом. */
    static void renderGeodesic(VertexConsumer vc, Matrix4f pose, Vec3 cam, Vec3 center, float charge, float time) {
        if (charge < 0.15f) return;
        double r = 2.6 + charge * 2.4;
        double ang = time * 0.01;
        double ca = Math.cos(ang), sa = Math.sin(ang);
        for (int e = 0; e < EDGES.size(); e++) {
            float vis = Mth.clamp((charge - EDGE_THRESHOLD.get(e)) / 0.08f, 0f, 1f);
            if (vis <= 0.01f) continue;
            Vec3 a = rot(VERTS.get(EDGES.get(e)[0]), ca, sa), b = rot(VERTS.get(EDGES.get(e)[1]), ca, sa);
            Vec3 pa = center.add(a.scale(r)), pb = center.add(b.scale(r));
            float flicker = 0.8f + 0.2f * Mth.sin(time * 0.4f + e);
            CrackField.quad(vc, pose, cam, pa, pb, 0.05f, 0.85f, 0.0f, 0.03f, 0.25f * vis * flicker);
            CrackField.quad(vc, pose, cam, pa, pb, 0.014f, 1.0f, 0.15f, 0.1f, 0.85f * vis * flicker);
        }
    }

    private static Vec3 rot(Vec3 v, double c, double s) {
        return new Vec3(v.x * c - v.z * s, v.y, v.x * s + v.z * c);
    }

    /**
     * Идеальные концентрические круги, разрывающие пространство вокруг клинка — в плоскости,
     * перпендикулярной взгляду, перед грудью. Число и размер кругов растут с зарядом.
     */
    static void renderBladeRings(VertexConsumer vc, Matrix4f pose, Vec3 cam, Vec3 center, Vec3 look, float charge, float time) {
        Vec3 up = Math.abs(look.y) > 0.95 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 u = look.cross(up).normalize();
        Vec3 v = look.cross(u).normalize();
        int rings = 1 + (int) (charge * 6);
        for (int i = 0; i < rings; i++) {
            float appear = Mth.clamp(charge * 7f - i, 0f, 1f);
            // круги «дышат» наружу и гаснут на краю, как волны
            double phase = (time * 0.02 + i / (double) rings) % 1.0;
            double r = 0.35 + phase * (1.2 + charge * 2.8);
            float alpha = (float) (appear * (1.0 - phase) * (0.35 + 0.55 * charge));
            if (alpha < 0.02f) continue;
            int seg = 40;
            Vec3 prev = null;
            for (int s = 0; s <= seg; s++) {
                double a = s * (Math.PI * 2 / seg) + i * 0.3;
                Vec3 p = center.add(u.scale(Math.cos(a) * r)).add(v.scale(Math.sin(a) * r));
                if (prev != null) {
                    CrackField.quad(vc, pose, cam, prev, p, 0.04f, 0.85f, 0.0f, 0.03f, alpha * 0.3f);
                    CrackField.quad(vc, pose, cam, prev, p, 0.012f, 1.0f, 0.2f, 0.12f, alpha);
                }
                prev = p;
            }
        }
    }
}
