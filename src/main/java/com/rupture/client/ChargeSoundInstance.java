package com.rupture.client;

import com.rupture.item.SwordOfRuptureItem;
import com.rupture.registry.ModSounds;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;

/**
 * Закольцованный слой звука зарядки, следует за игроком:
 * TURBINE — механическая раскрутка (громкость и высота растут с зарядом),
 * SUBHUM — сверхнизкий гул давления, нарастает к полному заряду.
 */
final class ChargeSoundInstance extends AbstractTickableSoundInstance {
    enum Layer { TURBINE, SUBHUM }

    private final Player player;
    private final Layer layer;

    ChargeSoundInstance(Player player, Layer layer) {
        super(layer == Layer.TURBINE ? ModSounds.CHARGE_LOOP.get() : ModSounds.SUBHUM.get(),
                SoundSource.PLAYERS, SoundInstance.createUnseededRandom());
        this.player = player;
        this.layer = layer;
        this.looping = true;
        this.delay = 0;
        this.volume = layer == Layer.TURBINE ? 0.35f : 0.0001f;
        this.pitch = layer == Layer.TURBINE ? 0.6f : 0.8f;
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
        this.x = player.getX();
        this.y = player.getY();
        this.z = player.getZ();
        if (layer == Layer.TURBINE) {
            this.volume = 0.35f + 0.65f * c;
            this.pitch = 0.6f + 0.75f * c;      // раскрутка ротора
        } else {
            this.volume = Math.max(0.0001f, (float) Math.pow(c, 1.5));
            this.pitch = 0.8f + 0.4f * c;
        }
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }
}
