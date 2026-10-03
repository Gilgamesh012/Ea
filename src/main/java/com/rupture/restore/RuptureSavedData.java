package com.rupture.restore;

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
 * Очередь разрывов, ожидающих восстановления. Хранится в каждом измерении отдельно
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

    /** Восстанавливает готовые разрывы, не больше {@code budget} блоков за тик на измерение. */
    void tick(ServerLevel level, int budget) {
        if (jobs.isEmpty()) return;
        long now = level.getGameTime();
        boolean changed = false;

        Iterator<RestoreJob> it = jobs.iterator();
        while (it.hasNext() && budget > 0) {
            RestoreJob job = it.next();
            if (!job.isReady(now)) continue;

            budget -= job.restoreSome(level, budget);
            changed = true;

            if (job.isDone()) {
                var c = job.center();
                level.playSound(null, c.getX() + 0.5, c.getY() + 0.5, c.getZ() + 0.5,
                        SoundEvents.RESPAWN_ANCHOR_SET_SPAWN, SoundSource.BLOCKS, 2.0f, 0.6f);
                it.remove();
            }
        }
        if (changed) setDirty();
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
