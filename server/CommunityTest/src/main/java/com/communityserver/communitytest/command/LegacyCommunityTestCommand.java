package com.communityserver.communitytest.command;

import com.communityserver.communitytest.mod.ModDefinition;
import com.communityserver.communitytest.mod.ModRegistry;
import com.communityserver.communitytest.proposal.ModProposal;
import com.communityserver.communitytest.proposal.ProposalManager;
import com.communityserver.communitytest.vote.VoteChoice;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * Façade de compatibilité : /communitytest continue de fonctionner à l'identique, mais
 * délègue entièrement au système générique (ModRegistry / ProposalManager) avec
 * modId="communitytest" codé en dur UNIQUEMENT dans cette classe — jamais dans la
 * logique métier (ModRegistry, ProposalManager, VoteManager), qui reste totalement
 * générique et ignore jusqu'à l'existence de ce mod en particulier.
 * À retirer plus tard si /communitytest n'est plus nécessaire ; rien d'autre n'en dépend.
 */
public final class LegacyCommunityTestCommand {

    private static final String MOD_ID = "communitytest";

    private LegacyCommunityTestCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("communitytest")
                .executes(LegacyCommunityTestCommand::runBase)
                .then(Commands.literal("status")
                    .executes(LegacyCommunityTestCommand::runStatus))
                .then(Commands.literal("vote")
                    .then(Commands.literal("yes")
                        .executes(ctx -> runVote(ctx, VoteChoice.YES)))
                    .then(Commands.literal("no")
                        .executes(ctx -> runVote(ctx, VoteChoice.NO))))
                .then(Commands.literal("start")
                    .requires(source -> source.hasPermission(2))
                    .executes(LegacyCommunityTestCommand::runStart))
                .then(Commands.literal("end")
                    .requires(source -> source.hasPermission(2))
                    .executes(LegacyCommunityTestCommand::runEnd))
        );
    }

    private static int runBase(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> Component.literal("CommunityTest fonctionne !"), false);
        return 1;
    }

    private static int runStatus(CommandContext<CommandSourceStack> ctx) {
        Optional<ModDefinition> mod = ModRegistry.get().get(MOD_ID);
        if (mod.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("Le mod '" + MOD_ID + "' n'est plus enregistré."));
            return 0;
        }

        ModProposal proposal = ProposalManager.get().get(MOD_ID).orElse(null);
        ctx.getSource().sendSuccess(() -> Component.literal(ModCommand.formatInfo(mod.get(), proposal)), false);
        return 1;
    }

    private static int runVote(CommandContext<CommandSourceStack> ctx, VoteChoice choice) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ProposalManager.CastResult result = ProposalManager.get().castVote(MOD_ID, player.getUUID(), choice);
        String choiceLabel = choice == VoteChoice.YES ? "POUR" : "CONTRE";

        switch (result) {
            case UNKNOWN_MOD ->
                ctx.getSource().sendFailure(Component.literal("Le mod '" + MOD_ID + "' n'est plus enregistré."));
            case NO_ACTIVE_PROPOSAL ->
                ctx.getSource().sendFailure(Component.literal("Aucun vote n'est actuellement en cours."));
            case ALREADY_VOTED_SAME ->
                ctx.getSource().sendFailure(Component.literal("Vous avez déjà voté."));
            case REGISTERED ->
                ctx.getSource().sendSuccess(() -> Component.literal("Votre vote a été enregistré : " + choiceLabel), false);
            case CHANGED ->
                ctx.getSource().sendSuccess(() -> Component.literal("Votre vote a été modifié : " + choiceLabel), false);
        }
        return 1;
    }

    private static int runStart(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        ProposalManager.StartResult result = ProposalManager.get().startProposal(MOD_ID, server);

        switch (result) {
            case UNKNOWN_MOD ->
                source.sendFailure(Component.literal("Le mod '" + MOD_ID + "' n'est plus enregistré."));
            case ALREADY_ACTIVE ->
                source.sendFailure(Component.literal("Un vote est déjà en cours pour " + MOD_ID + "."));
            case STARTED ->
                source.sendSuccess(() -> Component.literal("Vote démarré pour " + MOD_ID + "."), true);
        }
        return 1;
    }

    private static int runEnd(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        ProposalManager.EndResult result = ProposalManager.get().endProposal(MOD_ID, server);

        if (result == ProposalManager.EndResult.NO_ACTIVE_PROPOSAL) {
            source.sendFailure(Component.literal("Aucun vote n'est actuellement en cours."));
        } else {
            source.sendSuccess(() -> Component.literal("Vote terminé manuellement."), true);
        }
        return 1;
    }
}
