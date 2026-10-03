package com.rupture.restore;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Каждый серверный тик собирает разорванный мир обратно порциями.
 */
public final class RestoreManager {
    private RestoreManager() {}

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        RuptureSavedData data = RuptureSavedData.get(level);
        if (!data.hasJobs()) return;
        data.tick(level);
    }
}
