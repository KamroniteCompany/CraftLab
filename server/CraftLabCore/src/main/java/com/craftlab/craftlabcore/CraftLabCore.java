package com.craftlab.craftlabcore;

import com.craftlab.craftlabcore.command.ModCommand;
import com.craftlab.craftlabcore.command.ModPackCommand;
import com.craftlab.craftlabcore.download.DownloadConfig;
import com.craftlab.craftlabcore.github.GitHubConfig;
import com.craftlab.craftlabcore.github.GitHubIntegration;
import com.craftlab.craftlabcore.github.GitHubRefreshConfig;
import com.craftlab.craftlabcore.github.GitHubRefreshScheduler;
import com.craftlab.craftlabcore.mod.ModRegistry;
import com.craftlab.craftlabcore.modpack.ModPackApplier;
import com.craftlab.craftlabcore.modpack.ModPackManager;
import com.craftlab.craftlabcore.proposal.ProposalManager;
import com.craftlab.craftlabcore.vote.VoteConfig;
import com.craftlab.craftlabcore.vote.VoteScheduler;
import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Mod principal/cœur de CraftLab (anciennement nommé {@code CommunityTest} — voir
 * docs/renaming-communitytest-to-craftlabcore.md pour l'historique de la migration).
 */
@Mod(CraftLabCore.MOD_ID)
public class CraftLabCore {

    public static final String MOD_ID = "craftlabcore";
    private static final String MINECRAFT_VERSION = "1.21.1";
    private static final String FORGE_VERSION = "52.1.0";
    private static final Logger LOGGER = LogUtils.getLogger();

    public CraftLabCore() {
        // Nécessaire pour recevoir RegisterCommandsEvent / ServerStartingEvent / ServerStoppingEvent,
        // tous postés sur le bus Forge (pas le bus de mod).
        MinecraftForge.EVENT_BUS.register(this);

        LOGGER.info("[CraftLabCore] Mod chargé avec succès !");
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        ModCommand.register(event.getDispatcher());
        ModPackCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        // Ordre important : chaque config doit être chargée avant le composant qui en dépend.
        VoteConfig.load();
        ModRegistry.get().loadOrBootstrap();
        ProposalManager.get().load();
        VoteScheduler.start(event.getServer());

        GitHubConfig.load();
        GitHubIntegration.initialize();

        // DownloadConfig doit être chargé avant le premier ModPackManager.get() : son
        // constructeur lit DownloadConfig pour dimensionner le pool de téléchargement.
        DownloadConfig.load();
        ModPackManager.get().load(MINECRAFT_VERSION, FORGE_VERSION);

        // Doit s'exécuter après ModPackManager.load() : détecte une application interrompue
        // lors d'un arrêt précédent (crash pendant le swap /mods) et répare si besoin, puis
        // amorce CURRENT depuis NEXT si c'est un tout premier démarrage avec un NEXT déjà prêt.
        // Voir ModPackApplier pour le détail du raisonnement sur le timing de chargement Forge.
        ModPackApplier.get().checkForInterruptedApply();
        ModPackApplier.get().bootstrapIfNeeded();

        // Doit s'exécuter en dernier : dépend de GitHubIntegration (déjà initialisé) et de
        // ModPackManager (déjà chargé, pour pouvoir préparer NEXT quand une mise à jour est détectée).
        GitHubRefreshConfig.load();
        GitHubRefreshScheduler.start(event.getServer());
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        VoteScheduler.stop();
        GitHubRefreshScheduler.stop();
    }
}
