package com.communityserver.communitytest;

import com.communityserver.communitytest.command.LegacyCommunityTestCommand;
import com.communityserver.communitytest.command.ModCommand;
import com.communityserver.communitytest.command.ModPackCommand;
import com.communityserver.communitytest.download.DownloadConfig;
import com.communityserver.communitytest.github.GitHubConfig;
import com.communityserver.communitytest.github.GitHubIntegration;
import com.communityserver.communitytest.mod.ModRegistry;
import com.communityserver.communitytest.modpack.ModPackApplier;
import com.communityserver.communitytest.modpack.ModPackManager;
import com.communityserver.communitytest.proposal.ProposalManager;
import com.communityserver.communitytest.vote.VoteConfig;
import com.communityserver.communitytest.vote.VoteScheduler;
import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

@Mod(CommunityTest.MOD_ID)
public class CommunityTest {

    public static final String MOD_ID = "communitytest";
    private static final String MINECRAFT_VERSION = "1.21.1";
    private static final String FORGE_VERSION = "52.1.0";
    private static final Logger LOGGER = LogUtils.getLogger();

    public CommunityTest() {
        // Nécessaire pour recevoir RegisterCommandsEvent / ServerStartingEvent / ServerStoppingEvent,
        // tous postés sur le bus Forge (pas le bus de mod).
        MinecraftForge.EVENT_BUS.register(this);

        LOGGER.info("[CommunityTest] Mod chargé avec succès !");
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        ModCommand.register(event.getDispatcher());
        LegacyCommunityTestCommand.register(event.getDispatcher());
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
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        VoteScheduler.stop();
    }
}
