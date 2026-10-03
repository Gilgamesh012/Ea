package com.rupture.client;

import com.rupture.item.SwordOfRuptureItem;
import com.rupture.registry.ModSounds;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;

/** Закольцованный рёв вихря, следует за заряжающим игроком; громкость и высота растут с зарядом. */
final class ChargeSoundInstance extends AbstractTickableSoundInstance {
    private final Player player;

    ChargeSoundInstance(Player player) {
        super(ModSounds.CHARGE_LOOP.get(), SoundSource.PLAYERS, SoundInstance.createUnseededRandom());
        this.player = player;
        this.looping = true;
        this.delay = 0;
        this.volume = 0.7f;
        this.pitch = 0.5f;
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
        float charge = SwordOfRuptureItem.chargeFromTicks(player.getTicksUsingItem());
        this.x = player.getX();
        this.y = player.getY();
        this.z = player.getZ();
        // Громкость >1 в Minecraft не громче, а слышно дальше: с самого начала громко, к 100% — на 64 блока
        this.volume = 0.7f + 3.3f * charge;
        // Раскрутка цилиндров: высота от 0.5 до 1.7
        this.pitch = 0.5f + 1.2f * charge;
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }
}
