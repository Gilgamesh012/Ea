package com.rupture.registry;

import com.rupture.RuptureMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Отдельная вкладка «Сокровищница Вавилона» в творческом режиме. */
public final class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, RuptureMod.MODID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> RUPTURE = TABS.register("rupture", () ->
            CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.rupture"))
                    .icon(() -> new ItemStack(ModItems.SWORD_OF_RUPTURE.get()))
                    .displayItems((params, output) -> output.accept(ModItems.SWORD_OF_RUPTURE.get()))
                    .build());

    private ModCreativeTabs() {}
}
