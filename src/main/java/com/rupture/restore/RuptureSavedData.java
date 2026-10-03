package com.rupture.restore;

import com.rupture.RuptureConfig;
import com.rupture.registry.ModSounds;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Очередь кратеров. Хранится в каждом измерении отдельно
 * (world/[dim]/data/rupture_restore.dat), поэтому переживает перезапуск сервера.
 */
public final class RuptureSavedData extends SavedData {
    private static final String NAME = "rupture_restore";
    private static final SavedData.Factory<RuptureSavedData> FACTORY =
            new SavedData.Factory<>(RuptureSavedData::new, RuptureSavedData::load);

    private final List<RestoreJob> jobs = new ArrayList<>();

    public static RuptureSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    public void addJob(RestoreJob job) {
        jobs.add(job);
        setDirty();
    }

    public boolean hasJobs() {
        return !jobs.isEmpty();
    }

    void tick(ServerLevel level) {
        if (jobs.isEmpty()) return;
        int carve = RuptureConfig.CARVE_BLOCKS_PER_TICK.get();
        int visit = carve * 15; // проверка «внутри вихря» дешёвая, блоки читаются только внутри
        int restore = RuptureConfig.RESTORE_BLOCKS_PER_TICK.get();

        Iterator<RestoreJob> it = jobs.iterator();
        while (it.hasNext()) {
            RestoreJob job = it.next();
            job.tick(level, carve, visit, restore);
            if (job.isDone()) {
                job.releaseChunks(level);
                var c = job.center();
                // мир собрался — пространство схлопывается обратно
                level.playSound(null, c.getX() + 0.5, c.getY() + 0.5, c.getZ() + 0.5,
                        ModSounds.COLLAPSE.get(), SoundSource.BLOCKS, 1.0f, 0.8f);
                it.remove();
            }
        }
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (RestoreJob job : jobs) list.add(job.save());
        tag.put("jobs", list);
        return tag;
    }

    private static RuptureSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        RuptureSavedData data = new RuptureSavedData();
        ListTag list = tag.getList("jobs", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            data.jobs.add(RestoreJob.load(list.getCompound(i), registries));
        }
        return data;
    }
}
