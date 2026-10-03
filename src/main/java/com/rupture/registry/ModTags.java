package com.rupture.registry;

import com.rupture.RuptureMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;

public final class ModTags {
    /** Блоки, которые Разрыв не трогает (data/rupture/tags/block/rupture_immune.json). */
    public static final TagKey<Block> RUPTURE_IMMUNE = TagKey.create(Registries.BLOCK, RuptureMod.id("rupture_immune"));

    /** Обычный урон разрыва. */
    public static final ResourceKey<DamageType> RUPTURE = ResourceKey.create(Registries.DAMAGE_TYPE, RuptureMod.id("rupture"));
    /** «Истинный» разрыв — в обход брони, эффектов, зачарований, щита и i-frames (см. теги damage_type). */
    public static final ResourceKey<DamageType> RUPTURE_TRUE = ResourceKey.create(Registries.DAMAGE_TYPE, RuptureMod.id("rupture_true"));

    /** Анти-мировой удар на 100% заряда: обходит всё, включая неуязвимость и тотем бессмертия. */
    public static final ResourceKey<DamageType> RUPTURE_ABSOLUTE = ResourceKey.create(Registries.DAMAGE_TYPE, RuptureMod.id("rupture_absolute"));

    private ModTags() {}
}
