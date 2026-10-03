package com.rupture;

import com.mojang.logging.LogUtils;
import com.rupture.network.StrikeFxPayload;
import com.rupture.registry.ModItems;
import com.rupture.restore.RestoreManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;

/**
 * Меч Разрыва (Ea) — главный класс мода.
 */
@Mod(RuptureMod.MODID)
public class RuptureMod {
    public static final String MODID = "rupture";
    public static final Logger LOGGER = LogUtils.getLogger();

    public RuptureMod(IEventBus modBus, ModContainer container) {
        ModItems.ITEMS.register(modBus);
        modBus.addListener(this::addToCreativeTabs);

        // SERVER-конфиг: хранится в мире (serverconfig/) и синхронизируется клиентам при входе.
        container.registerConfig(ModConfig.Type.SERVER, RuptureConfig.SPEC);
        // CLIENT-конфиг: личные настройки эффектов (тряска, вспышки, трещины).
        container.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);

        modBus.addListener(this::registerPayloads);

        // Восстановление мира — тикается на игровой шине.
        NeoForge.EVENT_BUS.addListener(RestoreManager::onLevelTick);
    }

    private void addToCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.COMBAT) {
            event.accept(ModItems.SWORD_OF_RUPTURE.get());
        }
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(StrikeFxPayload.TYPE, StrikeFxPayload.CODEC, StrikeFxPayload::handle);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}
