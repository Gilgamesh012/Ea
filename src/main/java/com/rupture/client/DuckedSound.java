package com.rupture.client;

import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

/** Обёртка, приглушающая чужой звук во время «вакуумной тишины». */
final class DuckedSound implements SoundInstance {
    private final SoundInstance inner;
    private final float factor;

    DuckedSound(SoundInstance inner, float factor) {
        this.inner = inner;
        this.factor = factor;
    }

    @Override public ResourceLocation getLocation() { return inner.getLocation(); }
    @Override @Nullable public WeighedSoundEvents resolve(SoundManager manager) { return inner.resolve(manager); }
    @Override public Sound getSound() { return inner.getSound(); }
    @Override public SoundSource getSource() { return inner.getSource(); }
    @Override public boolean isLooping() { return inner.isLooping(); }
    @Override public boolean isRelative() { return inner.isRelative(); }
    @Override public int getDelay() { return inner.getDelay(); }
    @Override public float getVolume() { return inner.getVolume() * factor; }
    @Override public float getPitch() { return inner.getPitch(); }
    @Override public double getX() { return inner.getX(); }
    @Override public double getY() { return inner.getY(); }
    @Override public double getZ() { return inner.getZ(); }
    @Override public Attenuation getAttenuation() { return inner.getAttenuation(); }
    @Override public boolean canStartSilent() { return inner.canStartSilent(); }
    @Override public boolean canPlaySound() { return inner.canPlaySound(); }

    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary buffers, Sound sound, boolean looping) {
        return inner.getStream(buffers, sound, looping);
    }
}
