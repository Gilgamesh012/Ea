package com.rupture.registry;

import com.rupture.RuptureMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Оригинальные синтезированные звуки «Энума Элиш» (tools/sfx/synth_enuma.py). */
public final class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, RuptureMod.MODID);

    // Зарядка — три закольцованных слоя по фазам (см. ChargeLayerSound)
    public static final DeferredHolder<SoundEvent, SoundEvent> SPIN_GRIND = fixed("enuma.spin_grind", 64f);
    public static final DeferredHolder<SoundEvent, SoundEvent> ATMOS_HOWL = fixed("enuma.atmos_howl", 80f);
    public static final DeferredHolder<SoundEvent, SoundEvent> VACUUM = fixed("enuma.vacuum", 48f);
    public static final DeferredHolder<SoundEvent, SoundEvent> CRACK = variable("enuma.crack");
    public static final DeferredHolder<SoundEvent, SoundEvent> FULL_CHARGE = fixed("enuma.full_charge", 96f);
    /** Выстрел и удар слышно далеко — удар может прийти за 370 блоков. */
    public static final DeferredHolder<SoundEvent, SoundEvent> RELEASE = fixed("enuma.release", 192f);
    public static final DeferredHolder<SoundEvent, SoundEvent> IMPACT = fixed("enuma.impact", 400f);
    /** Фаза 4: пространство схлопывается обратно. */
    public static final DeferredHolder<SoundEvent, SoundEvent> COLLAPSE = fixed("enuma.collapse", 300f);

    private static DeferredHolder<SoundEvent, SoundEvent> variable(String name) {
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(RuptureMod.id(name)));
    }

    private static DeferredHolder<SoundEvent, SoundEvent> fixed(String name, float range) {
        return SOUNDS.register(name, () -> SoundEvent.createFixedRangeEvent(RuptureMod.id(name), range));
    }

    private ModSounds() {}
}
