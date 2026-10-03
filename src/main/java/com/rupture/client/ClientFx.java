package com.rupture.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.rupture.ClientConfig;
import com.rupture.RuptureMod;
import com.rupture.item.SwordOfRuptureItem;
import com.rupture.network.StrikeFxPayload;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import com.rupture.registry.ModItems;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Все клиентские эффекты Меча Разрыва: алый вихрь при зарядке, трещины в воздухе,
 * тряска камеры, потемнение неба/алый туман, вспышки и анимация удара.
 */
@EventBusSubscriber(modid = RuptureMod.MODID, value = Dist.CLIENT)
public final class ClientFx {
    private static final Vector3f CRIMSON = new Vector3f(0.9f, 0.04f, 0.06f);
    private static final Vector3f BLOOD = new Vector3f(0.45f, 0.0f, 0.03f);
    private static final Vector3f SCARLET = new Vector3f(1.0f, 0.02f, 0.02f);

    private static final Map<UUID, CrackField> casterFields = new HashMap<>();
    private static final List<CrackField> fadingFields = new ArrayList<>();
    private static final List<StrikeFx> strikes = new ArrayList<>();
    /** Горизонтальный вихрь вокруг заряжающего: {угол, угол прошлого тика, заряд}. */
    private static final Map<UUID, float[]> casterSpin = new HashMap<>();
    /** Свечение бездны над кратерами: {x, y, z, радиус, глубина, заряд, возраст}. */
    private static final List<float[]> abysses = new ArrayList<>();
    private static final int ABYSS_LIFE = 260;

    /** «Напряжение мира» 0..1: от заряда ближайшего заряжающего игрока. Управляет тьмой, туманом, тряской. */
    private static float tension, tensionPrev;
    private static float impactShake, flash;
    private static long ticks;

    private ClientFx() {}

    // ================================================================ события сервера

    public static void onStrike(StrikeFxPayload p) {
        if (Minecraft.getInstance().level == null) return;
        strikes.add(new StrikeFx(p.start(), p.end(), p.charge(), p.impactRadius(), p.craterDepth()));
        // Отдача у самого меча
        Player me = Minecraft.getInstance().player;
        if (me != null && me.position().distanceTo(p.start()) < 4) {
            impactShake = Math.max(impactShake, 0.25f + p.charge() * 0.35f);
        }
    }

    /** Вызывается из StrikeFx, когда вихрь достиг цели. */
    static void onImpact(Vec3 at, float charge, float radius, float depth) {
        Player me = Minecraft.getInstance().player;
        if (me == null) return;
        double dist = me.position().distanceTo(at);
        float near = (float) Mth.clamp(1.0 - dist / (48.0 + charge * 64.0), 0.0, 1.0);
        // На слабом заряде мир только «моргает»
        impactShake = Math.max(impactShake, (0.15f + charge * 0.85f) * near);
        flash = Math.max(flash, (0.35f + charge * 0.65f) * near);
        long seed = (long) (at.x * 31 + at.z * 17 + ticks);
        fadingFields.add(CrackField.aroundImpact(at, seed, Math.max(1.5f, radius), charge, 60 + (int) (charge * 80)));
        // Раскол неба и земли: разлом в небе над кратером (с 40% заряда)
        if (charge >= 0.4f) {
            fadingFields.add(CrackField.skyRift(at, seed ^ 0x5EEDL, radius, charge, 200 + (int) (charge * 200)));
        }
        // Из глубины кратера поднимается тьма подземного мира
        abysses.add(new float[]{(float) at.x, (float) at.y, (float) at.z, radius, depth, charge, 0f});
    }

    // ================================================================ тик

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        Player me = mc.player;
        if (level == null || me == null) {
            casterFields.clear();
            casterSpin.clear();
            abysses.clear();
            fadingFields.clear();
            strikes.clear();
            tension = tensionPrev = impactShake = flash = 0f;
            return;
        }
        if (mc.isPaused()) return;
        ticks++;

        float target = 0f;
        Set<UUID> seen = new HashSet<>();
        for (AbstractClientPlayer p : level.players()) {
            if (!p.isUsingItem() || !(p.getUseItem().getItem() instanceof SwordOfRuptureItem)) continue;
            double dist = p == me ? 0.0 : p.distanceTo(me);
            if (dist > 96.0) continue;
            float charge = SwordOfRuptureItem.chargeFromTicks(p.getTicksUsingItem());
            float falloff = (float) Mth.clamp(1.0 - dist / 64.0, 0.0, 1.0);
            target = Math.max(target, charge * falloff);

            UUID id = p.getUUID();
            seen.add(id);
            CrackField field = casterFields.computeIfAbsent(id, u -> {
                // Новый заряжающий: запускаем закольцованный рёв вихря
                mc.getSoundManager().play(new ChargeSoundInstance(p));
                return CrackField.aroundCaster(p.position(), u.getLeastSignificantBits() ^ ticks);
            });
            field.charge = charge;
            float[] spin = casterSpin.computeIfAbsent(id, u -> new float[4]);
            spin[1] = spin[0];
            spin[0] += 0.07f + 0.5f * charge;   // скорость вращения растёт с зарядом
            spin[2] = charge;
            spin[3] += 1f;                      // тиков с начала зарядки (золотая рябь Врат Вавилона)
            spawnChargeParticles(level, p, charge);
        }
        // Игрок перестал заряжать — его трещины гаснут
        for (Iterator<Map.Entry<UUID, CrackField>> it = casterFields.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, CrackField> e = it.next();
            if (!seen.contains(e.getKey())) {
                casterSpin.remove(e.getKey());
                e.getValue().release();
                fadingFields.add(e.getValue());
                it.remove();
            }
        }

        tensionPrev = tension;
        tension += (target - tension) * 0.12f;
        impactShake *= 0.9f;
        flash *= 0.84f;

        strikes.removeIf(s -> !s.tick(level));
        abysses.removeIf(a -> !tickAbyss(level, a));
        fadingFields.removeIf(f -> !f.tick());
    }

    /** Тьма и алое свечение поднимаются из глубины кратера (подземный мир сквозь разрыв). */
    private static boolean tickAbyss(ClientLevel level, float[] a) {
        a[6] += 1f;
        float age = a[6];
        if (age > ABYSS_LIFE) return false;
        RandomSource rnd = level.random;
        float fade = age < 20 ? age / 20f : Math.max(0f, 1f - (age - 20) / (ABYSS_LIFE - 20f));
        double radius = a[3], depth = a[4];
        int n = (int) ((3 + a[5] * 10 + radius * 0.25) * fade * ClientConfig.particles());
        for (int i = 0; i < n; i++) {
            double ang = rnd.nextDouble() * Math.PI * 2, r = Math.sqrt(rnd.nextDouble()) * radius * 0.9;
            double x = a[0] + Math.cos(ang) * r, z = a[2] + Math.sin(ang) * r;
            // чем ближе к центру, тем глубже дно
            double bottom = a[1] - depth * Math.sqrt(Math.max(0, 1 - (r * r) / (radius * radius)));
            double y = bottom + rnd.nextDouble() * 3;
            if (rnd.nextFloat() < 0.5f) {
                level.addParticle(ParticleTypes.LARGE_SMOKE, x, y, z, 0, 0.15 + rnd.nextDouble() * 0.25, 0);
            } else {
                level.addParticle(new DustColorTransitionOptions(SCARLET, BLOOD, 2.0f + (float) rnd.nextDouble() * 2f),
                        x, y, z, 0, 0.2 + rnd.nextDouble() * 0.3, 0);
            }
        }
        return true;
    }

    /** Алый вихрь вокруг заряжающего: спирали, затягивание пространства, подъём обломков, осыпание мира. */
    private static boolean isLocalFirstPerson(Player p) {
        Minecraft mc = Minecraft.getInstance();
        return p == mc.player && mc.options.getCameraType().isFirstPerson();
    }

    /** Точка перед прицелом (в конусе ~18°)? Такие частицы у себя в первом лице не спавним — цель должна быть видна. */
    private static boolean blocksView(Player p, double x, double y, double z) {
        Vec3 eye = p.getEyePosition();
        double vx = x - eye.x, vy = y - eye.y, vz = z - eye.z;
        double d = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (d < 1.0e-3) return true;
        Vec3 look = p.getViewVector(1f);
        return (vx * look.x + vy * look.y + vz * look.z) / d > CrackField.VIEW_WINDOW_COS;
    }

    private static void addChargeParticle(ClientLevel level, Player p, boolean fp, net.minecraft.core.particles.ParticleOptions opt,
                                          double x, double y, double z, double vx, double vy, double vz) {
        if (fp && blocksView(p, x, y, z)) return;
        level.addParticle(opt, x, y, z, vx, vy, vz);
    }

    private static void spawnChargeParticles(ClientLevel level, Player p, float charge) {
        RandomSource rnd = level.random;
        boolean fp = isLocalFirstPerson(p);
        double density = ClientConfig.particles();
        double cx = p.getX(), cy = p.getY(), cz = p.getZ();

        // 1. Горизонтальный алый вихрь вокруг персонажа: радиус, высота, плотность и скорость растут с зарядом
        float[] spin = casterSpin.get(p.getUUID());
        double base = spin != null ? spin[0] : ticks * 0.1;
        int arms = 2 + (int) (charge * 4);
        int perArm = (int) ((2 + charge * 6) * density);
        double height = 0.4 + charge * 2.4;
        double baseR = 1.4 + charge * 3.2;
        double tangential = 0.12 + charge * 0.45;
        for (int k = 0; k < arms; k++) {
            for (int i = 0; i < perArm; i++) {
                double h = 0.1 + rnd.nextDouble() * height;
                double a = base + k * (Math.PI * 2 / arms) - rnd.nextDouble() * 0.9; // хвост за рукавом
                double r = baseR * (0.8 + 0.3 * rnd.nextDouble());
                double x = cx + Math.cos(a) * r, z = cz + Math.sin(a) * r;
                double vx = -Math.sin(a) * tangential, vz = Math.cos(a) * tangential;
                addChargeParticle(level, p, fp, new DustColorTransitionOptions(rnd.nextFloat() < 0.75f ? SCARLET : CRIMSON, BLOOD, 0.9f + charge * 1.6f),
                        x, cy + h, z, vx, 0.0, vz);
            }
        }

        // 2. Пространство затягивает к мечу: алые искры летят к игроку
        int pull = (int) ((1 + charge * 4) * density);
        for (int i = 0; i < pull; i++) {
            double ox = (rnd.nextDouble() - 0.5) * 7, oy = rnd.nextDouble() * 3, oz = (rnd.nextDouble() - 0.5) * 7;
            addChargeParticle(level, p, fp, new DustParticleOptions(SCARLET, 0.7f + charge),
                    cx + ox, cy + oy, cz + oz, -ox * 0.08, (1.0 - oy) * 0.04, -oz * 0.08);
        }

        // 3. С 35%: земля трескается, обломки поднимаются в вихрь
        if (charge > 0.35f && rnd.nextFloat() < charge) {
            for (int i = 0; i < (int) (1 + charge * 3 * density); i++) {
                double a = rnd.nextDouble() * Math.PI * 2, r = rnd.nextDouble() * baseR * 1.5;
                BlockPos ground = BlockPos.containing(cx + Math.cos(a) * r, cy - 0.5, cz + Math.sin(a) * r);
                BlockState state = level.getBlockState(ground);
                if (state.isAir()) state = Blocks.BLACKSTONE.defaultBlockState();
                addChargeParticle(level, p, fp, new BlockParticleOption(ParticleTypes.BLOCK, state),
                        ground.getX() + 0.5, cy + 0.1, ground.getZ() + 0.5, -Math.sin(a) * 0.2, 0.25 + charge * 0.4, Math.cos(a) * 0.2);
            }
        }

        // 4. С 70%: мир осыпается — алая и чёрная пыль падает сверху
        if (charge > 0.7f) {
            int fall = (int) ((charge - 0.7f) * 20 * density);
            for (int i = 0; i < fall; i++) {
                BlockState dust = rnd.nextBoolean() ? Blocks.REDSTONE_BLOCK.defaultBlockState() : Blocks.BLACKSTONE.defaultBlockState();
                addChargeParticle(level, p, fp, new BlockParticleOption(ParticleTypes.FALLING_DUST, dust),
                        cx + (rnd.nextDouble() - 0.5) * 16, cy + 4 + rnd.nextDouble() * 10, cz + (rnd.nextDouble() - 0.5) * 16, 0, 0, 0);
            }
        }

        // 5. 100%: искры у клинка
        if (charge >= 1f && rnd.nextFloat() < 0.6f && !isLocalFirstPerson(p)) {
            Vec3 tip = p.getEyePosition().add(p.getLookAngle().scale(1.2));
            addChargeParticle(level, p, fp, new DustParticleOptions(CRIMSON, 2.0f), tip.x, tip.y - 0.3, tip.z,
                    rnd.nextGaussian() * 0.05, rnd.nextGaussian() * 0.05, rnd.nextGaussian() * 0.05);
        }
    }

    // ================================================================ камера, небо, туман

    @SubscribeEvent
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        float partial = (float) event.getPartialTick();
        float ten = Mth.lerp(partial, tensionPrev, tension);
        boolean fp = Minecraft.getInstance().options.getCameraType().isFirstPerson();
        float amp = (float) ((Math.pow(ten, 1.4) * (fp ? 0.9 : 1.6) + impactShake * 6.0) * ClientConfig.shake());
        if (amp < 0.001f) return;
        float t = ticks + partial;
        event.setYaw(event.getYaw() + amp * noise(t, 0.3f));
        event.setPitch(event.getPitch() + amp * noise(t, 2.1f));
        event.setRoll(event.getRoll() + amp * 0.7f * noise(t, 4.4f));
    }

    private static float noise(float t, float seed) {
        return Mth.sin(t * 1.9f + seed) * 0.5f + Mth.sin(t * 4.7f + seed * 2f) * 0.3f + Mth.sin(t * 9.3f + seed * 3f) * 0.2f;
    }

    @SubscribeEvent
    public static void onFogColor(ViewportEvent.ComputeFogColor event) {
        float k = Math.min(1f, tension * 0.9f + flash * 0.3f);
        if (k <= 0.001f) return;
        event.setRed(Mth.lerp(k, event.getRed(), 0.28f));
        event.setGreen(Mth.lerp(k, event.getGreen(), 0.0f));
        event.setBlue(Mth.lerp(k, event.getBlue(), 0.03f));
    }

    @SubscribeEvent
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        float k = tension;
        if (k <= 0.02f) return;
        // Небо темнеет до кроваво-красного, но дальность видимости сохраняется — цель должна быть видна
        float origFar = event.getFarPlaneDistance();
        float far = Mth.lerp(k * 0.5f, origFar, Math.max(Math.min(origFar, 96f), origFar * 0.55f));
        float near = Math.min(event.getNearPlaneDistance(), far * (1f - 0.5f * k));
        event.setFarPlaneDistance(far);
        event.setNearPlaneDistance(near);
        event.setCanceled(true);
    }

    // ================================================================ 3D: трещины и вихрь удара

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        boolean cracks = ClientConfig.cracks();
        if (strikes.isEmpty() && casterSpin.isEmpty() && (!cracks || fadingFields.isEmpty())) return;

        Minecraft mc = Minecraft.getInstance();
        Vec3 cam = event.getCamera().getPosition();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        float time = ticks + partial;
        Matrix4f pose = event.getPoseStack().last().pose();
        // «Окно прицела»: в первом лице всё, что перед центром экрана, рисуется почти прозрачным
        org.joml.Vector3f lv = event.getCamera().getLookVector();
        CrackField.setViewWindow(mc.options.getCameraType().isFirstPerson(), cam, new Vec3(lv.x(), lv.y(), lv.z()));

        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer vc = buffers.getBuffer(RenderType.lightning());
        if (cracks) {
            for (CrackField f : casterFields.values()) f.render(vc, pose, cam, time);
            for (CrackField f : fadingFields) f.render(vc, pose, cam, time);
        }
        for (StrikeFx s : strikes) s.render(vc, pose, cam, partial);
        if (mc.level != null && !casterSpin.isEmpty()) {
            for (AbstractClientPlayer p : mc.level.players()) {
                float[] spin = casterSpin.get(p.getUUID());
                if (spin != null) {
                    Vec3 pos = p.getPosition(partial);
                    renderCasterVortex(vc, pose, cam, pos, Mth.lerp(partial, spin[1], spin[0]), spin[2], time);
                    if (spin[3] < 30f) renderGateRipple(vc, pose, cam, p, pos, spin[3] + partial);
                }
            }
        }
        buffers.endBatch(RenderType.lightning());
        CrackField.setViewWindow(false, cam, Vec3.ZERO);
    }

    /**
     * Алые ленты ветра, кружащие вокруг персонажа по горизонтали.
     * С ростом заряда появляется больше колец, они шире, выше, длиннее и быстрее.
     */
    private static void renderCasterVortex(VertexConsumer vc, Matrix4f pose, Vec3 cam, Vec3 pos, float angle, float charge, float time) {
        int bands = 1 + (int) Math.ceil(charge * 5);
        double radius = 1.4 + charge * 3.2;
        double arcLen = 0.9 + charge * 2.0;               // длина ленты, радианы
        for (int j = 0; j < bands; j++) {
            float appear = Mth.clamp(charge * 6f - j, 0f, 1f); // кольца появляются по очереди
            if (appear <= 0.01f) continue;
            double y = pos.y + 0.15 + j * (0.3 + 0.25 * charge);
            double r = radius * (1.0 + 0.1 * Math.sin(j * 1.7)) + j * 0.15;
            double bandAngle = angle * (1.0 + j * 0.08) + j * 1.3;
            for (int arm = 0; arm < 2; arm++) {
                double a0 = bandAngle + arm * Math.PI;
                int seg = 18;
                Vec3 prev = null;
                for (int i = 0; i <= seg; i++) {
                    double f = i / (double) seg;           // 0 = хвост, 1 = голова
                    double a = a0 - arcLen * (1 - f);
                    double wobble = 0.08 * Math.sin(a * 2 + time * 0.2 + j);
                    Vec3 pt = new Vec3(pos.x + Math.cos(a) * r, y + wobble, pos.z + Math.sin(a) * r);
                    if (prev != null) {
                        float taper = (float) Math.sin(Math.PI * f);   // тонкие концы
                        float alpha = (0.25f + 0.6f * charge) * appear * taper;
                        float w = (0.05f + 0.16f * charge) * (0.4f + 0.6f * taper);
                        CrackField.quad(vc, pose, cam, prev, pt, w * 2.6f, 0.8f, 0.0f, 0.02f, alpha * 0.35f);
                        CrackField.quad(vc, pose, cam, prev, pt, w, 1.0f, 0.08f, 0.05f, alpha);
                    }
                    prev = pt;
                }
            }
        }
    }

    /**
     * Врата Вавилона: в начале зарядки за спиной расходится золотая рябь — Эа извлечён из сокровищницы.
     */
    private static void renderGateRipple(VertexConsumer vc, Matrix4f pose, Vec3 cam, Player p, Vec3 pos, float age) {
        Vec3 look = p.getViewVector(1f);
        Vec3 flat = new Vec3(look.x, 0, look.z);
        if (flat.lengthSqr() < 1.0e-4) flat = new Vec3(0, 0, 1);
        flat = flat.normalize();
        Vec3 c = pos.add(0, 1.5, 0).subtract(flat.scale(0.9));
        Vec3 u = new Vec3(-flat.z, 0, flat.x);
        Vec3 v = new Vec3(0, 1, 0);
        for (int ring = 0; ring < 3; ring++) {
            float t = (age - ring * 4f) / 22f;
            if (t <= 0f || t >= 1f) continue;
            double r = 0.15 + t * 1.6;
            float alpha = (1f - t) * 0.9f;
            Vec3 prev = null;
            for (int i = 0; i <= 28; i++) {
                double a = i * (Math.PI * 2 / 28);
                Vec3 pt = c.add(u.scale(Math.cos(a) * r)).add(v.scale(Math.sin(a) * r));
                if (prev != null) {
                    CrackField.quad(vc, pose, cam, prev, pt, 0.12f, 1.0f, 0.7f, 0.15f, alpha * 0.35f);
                    CrackField.quad(vc, pose, cam, prev, pt, 0.035f, 1.0f, 0.9f, 0.5f, alpha);
                }
                prev = pt;
            }
        }
    }

    // ================================================================ 2D: алая пелена и вспышка

    static void renderOverlay(GuiGraphics g, DeltaTracker delta) {
        int w = g.guiWidth(), h = g.guiHeight();
        float ten = tension;
        if (ten > 0.01f) {
            // Алая пелена только по краям: центр экрана (прицел) остаётся чистым
            int edge = argb(ten * 0.7f, 90, 0, 10);
            g.fillGradient(0, 0, w, h / 4, edge, 0x00000000);
            g.fillGradient(0, h - h / 4, w, h, 0x00000000, edge);
        }
        if (flash > 0.01f && ClientConfig.flashes()) {
            float f = Math.min(1f, flash);
            // Белая вспышка, уходящая в алый
            g.fill(0, 0, w, h, argb(f * 0.85f, 255, (int) (60 + 195 * f * f), (int) (50 + 205 * f * f)));
        }
    }

    private static int argb(float alpha, int r, int g, int b) {
        int a = Mth.clamp((int) (alpha * 255f), 0, 255);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** Регистрация 2D-слоя на шине мода. */
    @EventBusSubscriber(modid = RuptureMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class ModEvents {
        @SubscribeEvent
        public static void registerLayers(RegisterGuiLayersEvent event) {
            event.registerAbove(VanillaGuiLayers.CAMERA_OVERLAYS, RuptureMod.id("rupture_overlay"), ClientFx::renderOverlay);
        }

        /** Части модели Эа (рукоять и три цилиндра) грузятся как отдельные модели. */
        @SubscribeEvent
        public static void registerModels(ModelEvent.RegisterAdditional event) {
            event.register(EaSwordRenderer.STATIC);
            event.register(EaSwordRenderer.CYL_1);
            event.register(EaSwordRenderer.CYL_2);
            event.register(EaSwordRenderer.CYL_3);
        }

        @SubscribeEvent
        public static void registerItemRenderer(RegisterClientExtensionsEvent event) {
            event.registerItem(new IClientItemExtensions() {
                private EaSwordRenderer renderer;

                @Override
                public net.minecraft.client.model.HumanoidModel.ArmPose getArmPose(net.minecraft.world.entity.LivingEntity entity,
                        net.minecraft.world.InteractionHand hand, net.minecraft.world.item.ItemStack stack) {
                    // В третьем лице при зарядке клинок поднят, как копьё перед броском
                    if (entity.isUsingItem() && entity.getUsedItemHand() == hand) {
                        return net.minecraft.client.model.HumanoidModel.ArmPose.THROW_SPEAR;
                    }
                    return null;
                }

                @Override
                public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                    if (renderer == null) renderer = new EaSwordRenderer();
                    return renderer;
                }
            }, ModItems.SWORD_OF_RUPTURE.get());
        }
    }
}
