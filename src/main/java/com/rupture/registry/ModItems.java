package com.rupture.registry;

import com.rupture.RuptureMod;
import com.rupture.item.SwordOfRuptureItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(RuptureMod.MODID);

    public static final DeferredItem<SwordOfRuptureItem> SWORD_OF_RUPTURE =
            ITEMS.register("sword_of_rupture", () -> new SwordOfRuptureItem(new Item.Properties()));

    private ModItems() {}
}
