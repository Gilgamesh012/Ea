package com.rupture.flight;

import com.rupture.item.SwordOfRuptureItem;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Контролируемый полёт во время зарядки «Энума Элиш»: как полёт в творческом, но заметно медленнее.
 * Способность выдаётся только на время зарядки и всегда снимается (отпустил ПКМ, сменил предмет, умер, перезашёл).
 * После полёта урон от падения не наносится до первого касания земли.
 */
public final class ChargeFlight {
    private static final String FLAG = "rupture_charge_flight";
    private static final String PREV_MAYFLY = "rupture_prev_mayfly";
    private static final String NO_FALL = "rupture_no_fall";
    /** Скорость полёта в творческом — 0.05. */
    public static final float FLY_SPEED = 0.022f;

    private ChargeFlight() {}

    private static boolean exempt(Player p) {
        return p.isCreative() || p.isSpectator();
    }

    public static void start(ServerPlayer p) {
        if (exempt(p)) return;
        CompoundTag data = p.getPersistentData();
        Abilities ab = p.getAbilities();
        if (!data.getBoolean(FLAG)) {
            data.putBoolean(FLAG, true);
            data.putBoolean(PREV_MAYFLY, ab.mayfly);
            ab.mayfly = true;
            ab.flying = true;           // сразу зависаем — без рывка вверх
            ab.setFlyingSpeed(FLY_SPEED);
            p.onUpdateAbilities();
        }
        p.resetFallDistance();
    }

    public static void stop(ServerPlayer p) {
        CompoundTag data = p.getPersistentData();
        if (!data.getBoolean(FLAG)) return;
        Abilities ab = p.getAbilities();
        boolean prev = data.getBoolean(PREV_MAYFLY);
        data.remove(FLAG);
        data.remove(PREV_MAYFLY);
        if (!exempt(p)) {
            ab.mayfly = prev;
            if (!prev) ab.flying = false;
        }
        ab.setFlyingSpeed(0.05f);
        p.onUpdateAbilities();
        if (!p.onGround()) data.putBoolean(NO_FALL, true);  // мягкое приземление
    }

    /** Подстраховка: если зарядка прервалась любым путём — снимаем полёт. */
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        CompoundTag data = p.getPersistentData();
        if (data.getBoolean(FLAG)) {
            boolean charging = p.isUsingItem() && p.getUseItem().getItem() instanceof SwordOfRuptureItem;
            if (!charging) stop(p);
        }
        if (data.getBoolean(NO_FALL)) {
            p.resetFallDistance();
            if (p.onGround() || p.isInWater()) data.remove(NO_FALL);
        }
    }

    /** Без урона от падения после полёта. */
    public static void onFall(LivingFallEvent event) {
        if (event.getEntity() instanceof Player p && p.getPersistentData().getBoolean(NO_FALL)) {
            event.setDistance(0f);
            event.setDamageMultiplier(0f);
            p.getPersistentData().remove(NO_FALL);
        }
    }
}
