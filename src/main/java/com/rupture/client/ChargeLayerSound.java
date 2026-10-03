package com.rupture.client;

import com.rupture.item.SwordOfRuptureItem;
import com.rupture.registry.ModSounds;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

/**
 * Один слой звука зарядки, следует за игроком. Слои перетекают друг в друга по заряду:
 * <ol>
 *   <li>GRIND — сухой тектонический скрежет цилиндров (0–85%), раскручивается;</li>
 *   <li>HOWL — вопль атмосферы, рассекаемой микро-разрывами (25–90%), высота растёт;</li>
 *   <li>VACUUM — «вакуумная тишина»: давление и ультразвуковой писк Текстуры Мира (82–100%).</li>
 * </ol>
 */
final class ChargeLayerSound extends AbstractTickableSoundInstance {
    enum Layer { GRIND, HOWL, VACUUM }

    private final Player player;
    private final Layer layer;

    ChargeLayerSound(Player player, Layer layer) {
        super(switch (layer) {
            case GRIND -> ModSounds.SPIN_GRIND.get();
            case HOWL -> ModSounds.ATMOS_HOWL.get();
            case VACUUM -> ModSounds.VACUUM.get();
        }, SoundSource.PLAYERS, SoundInstance.createUnseededRandom());
        this.player = player;
        this.layer = layer;
        this.looping = true;
        this.delay = 0;
        this.volume = layer == Layer.GRIND ? 0.6f : 0.0001f;
        this.pitch = layer == Layer.GRIND ? 0.5f : 1.0f;
        follow();
    }

    static float smooth(float c, float a, float b) {
        float k = Mth.clamp((c - a) / (b - a), 0f, 1f);
        return k * k * (3 - 2 * k);
    }

    /** Насколько «вакуумная тишина» включена при данном заряде (0..1). */
    static float vacuumLevel(float charge) {
        return smooth(charge, 0.82f, 0.92f);
    }

    private void follow() {
        this.x = player.getX();
        this.y = player.getY();
        this.z = player.getZ();
    }

    @Override
    public void tick() {
        if (player.isRemoved() || !player.isUsingItem() || !(player.getUseItem().getItem() instanceof SwordOfRuptureItem)) {
            stop();
            return;
        }
        float c = SwordOfRuptureItem.chargeFromTicks(player.getTicksUsingItem());
        follow();
        switch (layer) {
            case GRIND -> {
                this.volume = Math.max(0.0001f, (0.6f + 0.4f * c) * (1f - smooth(c, 0.6f, 0.85f)));
                this.pitch = 0.5f + 0.6f * Math.min(c, 0.6f) / 0.6f;   // раскрутка
            }
            case HOWL -> {
                this.volume = Math.max(0.0001f, 0.85f * smooth(c, 0.25f, 0.55f) * (1f - smooth(c, 0.82f, 0.9f)));
                this.pitch = 0.7f + 0.9f * c;
            }
            case VACUUM -> {
                this.volume = Math.max(0.0001f, 0.8f * vacuumLevel(c));
                this.pitch = 1.0f;
            }
        }
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }
}
