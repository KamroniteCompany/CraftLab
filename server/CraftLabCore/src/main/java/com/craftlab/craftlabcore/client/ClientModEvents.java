package com.craftlab.craftlabcore.client;

import com.craftlab.craftlabcore.CraftLabCore;
import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.slf4j.Logger;

/**
 * Écoute le bus de mod (pas {@code MinecraftForge.EVENT_BUS}), uniquement côté client —
 * {@code value = Dist.CLIENT} est ce qui garantit que cette classe n'est jamais chargée sur le
 * serveur dédié, et donc que {@link ClientForgeEvents} (référencée uniquement ici) ne l'est pas
 * non plus (voir docs/client-title-screen.md pour le détail de cette séparation client/serveur).
 */
@Mod.EventBusSubscriber(modid = CraftLabCore.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ClientModEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    private ClientModEvents() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        LOGGER.info("[CraftLab] Initialisation de l'interface client...");
        // FMLClientSetupEvent est un ParallelDispatchEvent : son gestionnaire s'exécute sur un
        // thread worker, en parallèle des autres mods — enqueueWork() est le mécanisme Forge
        // prévu pour différer ce genre d'appel jusqu'au retour sur le thread principal, une fois
        // toute la phase de setup parallèle terminée (voir ClientForgeEvents pour le détail des
        // deux essais infructueux qui ont précédé cette forme).
        event.enqueueWork(() -> MinecraftForge.EVENT_BUS.register(new ClientForgeEvents()));
    }
}
