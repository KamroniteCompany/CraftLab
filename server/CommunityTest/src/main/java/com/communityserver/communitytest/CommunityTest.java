package com.communityserver.communitytest;

import com.communityserver.communitytest.command.LegacyCommunityTestCommand;
import com.communityserver.communitytest.command.ModCommand;
import com.communityserver.communitytest.github.GitHubConfig;
import com.communityserver.communitytest.github.GitHubIntegration;
import com.communityserver.communitytest.mod.ModRegistry;
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
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        // Ordre important : config -> registre des mods -> propositions -> planificateur -> GitHub.
        VoteConfig.load();
        ModRegistry.get().loadOrBootstrap();
        ProposalManager.get().load();
        VoteScheduler.start(event.getServer());

        GitHubConfig.load();
        GitHubIntegration.initialize();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        VoteScheduler.stop();
    }
}
