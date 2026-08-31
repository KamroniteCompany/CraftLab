package com.craftlab.craftlabcore.command;

import com.craftlab.craftlabcore.github.GitHubIntegration;
import com.craftlab.craftlabcore.github.ImportResult;
import com.craftlab.craftlabcore.mod.ModDefinition;
import com.craftlab.craftlabcore.mod.ModRegistry;
import com.craftlab.craftlabcore.mod.ModStatus;
import com.craftlab.craftlabcore.proposal.ModProposal;
import com.craftlab.craftlabcore.proposal.ProposalManager;
import com.craftlab.craftlabcore.proposal.ProposalStatus;
import com.craftlab.craftlabcore.vote.VoteChoice;
import com.craftlab.craftlabcore.vote.VoteManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.Optional;

public final class ModCommand {

    private ModCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("mod")
                .then(Commands.literal("list")
                    .executes(ModCommand::runList))
                .then(Commands.literal("info")
                    .then(Commands.argument("modId", StringArgumentType.word())
                        .executes(ModCommand::runInfo)))
                .then(Commands.literal("vote")
                    .then(Commands.argument("modId", StringArgumentType.word())
                        .then(Commands.literal("yes")
                            .executes(ctx -> runVote(ctx, VoteChoice.YES)))
                        .then(Commands.literal("no")
                            .executes(ctx -> runVote(ctx, VoteChoice.NO)))))
                .then(Commands.literal("start")
                    .requires(source -> source.hasPermission(2))
                    .then(Commands.argument("modId", StringArgumentType.word())
                        .executes(ModCommand::runStart)))
                .then(Commands.literal("end")
                    .requires(source -> source.hasPermission(2))
                    .then(Commands.argument("modId", StringArgumentType.word())
                        .executes(ModCommand::runEnd)))
                .then(Commands.literal("register")
                    .requires(source -> source.hasPermission(2))
                    .then(Commands.argument("modId", StringArgumentType.word())
                        .then(Commands.argument("name", StringArgumentType.string())
                            .then(Commands.argument("author", StringArgumentType.string())
                                .then(Commands.argument("version", StringArgumentType.string())
                                    .executes(ctx -> runRegister(ctx, false))
                                    .then(Commands.argument("description", StringArgumentType.string())
                                        .executes(ctx -> runRegister(ctx, true))))))))
                .then(Commands.literal("unregister")
                    .requires(source -> source.hasPermission(2))
                    .then(Commands.argument("modId", StringArgumentType.word())
                        .executes(ModCommand::runUnregister)))
                .then(Commands.literal("github")
                    .requires(source -> source.hasPermission(2))
                    .then(Commands.literal("import")
                        .then(Commands.argument("url", StringArgumentType.greedyString())
                            .executes(ModCommand::runGitHubImport)))
                    .then(Commands.literal("refresh")
                        .executes(ModCommand::runGitHubRefresh)))
        );
    }

    private static int runList(CommandContext<CommandSourceStack> ctx) {
        Collection<ModDefinition> mods = ModRegistry.get().getAll();
        ctx.getSource().sendSuccess(() -> Component.literal(formatList(mods)), false);
        return 1;
    }

    private static int runInfo(CommandContext<CommandSourceStack> ctx) {
        String modId = StringArgumentType.getString(ctx, "modId");
        Optional<ModDefinition> modOpt = ModRegistry.get().get(modId);

        if (modOpt.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("Aucun mod enregistré avec l'ID '" + modId + "'."));
            return 0;
        }

        ModDefinition mod = modOpt.get();
        ModProposal proposal = ProposalManager.get().get(modId).orElse(null);
        ctx.getSource().sendSuccess(() -> Component.literal(formatInfo(mod, proposal)), false);
        return 1;
    }

    private static int runVote(CommandContext<CommandSourceStack> ctx, VoteChoice choice) throws CommandSyntaxException {
        String modId = StringArgumentType.getString(ctx, "modId");
        ServerPlayer player = ctx.getSource().getPlayerOrException();

        ProposalManager.CastResult result = ProposalManager.get().castVote(modId, player.getUUID(), choice);
        String choiceLabel = choice == VoteChoice.YES ? "POUR" : "CONTRE";

        switch (result) {
            case UNKNOWN_MOD ->
                ctx.getSource().sendFailure(Component.literal("Aucun mod enregistré avec l'ID '" + modId + "'."));
            case NO_ACTIVE_PROPOSAL ->
                ctx.getSource().sendFailure(Component.literal("Aucun vote n'est actuellement en cours pour ce mod."));
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
        String modId = StringArgumentType.getString(ctx, "modId");
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();

        ProposalManager.StartResult result = ProposalManager.get().startProposal(modId, server);

        switch (result) {
            case UNKNOWN_MOD ->
                source.sendFailure(Component.literal("Aucun mod enregistré avec l'ID '" + modId + "'."));
            case ALREADY_ACTIVE ->
                source.sendFailure(Component.literal("Une proposition est déjà en cours pour '" + modId + "'."));
            case STARTED ->
                source.sendSuccess(() -> Component.literal("Proposition démarrée pour '" + modId + "'."), true);
        }
        return 1;
    }

    private static int runEnd(CommandContext<CommandSourceStack> ctx) {
        String modId = StringArgumentType.getString(ctx, "modId");
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();

        ProposalManager.EndResult result = ProposalManager.get().endProposal(modId, server);

        if (result == ProposalManager.EndResult.NO_ACTIVE_PROPOSAL) {
            source.sendFailure(Component.literal("Aucune proposition en cours pour '" + modId + "'."));
        } else {
            source.sendSuccess(() -> Component.literal("Proposition terminée manuellement pour '" + modId + "'."), true);
        }
        return 1;
    }

    private static int runRegister(CommandContext<CommandSourceStack> ctx, boolean hasDescription) {
        String id = StringArgumentType.getString(ctx, "modId");
        String name = StringArgumentType.getString(ctx, "name");
        String author = StringArgumentType.getString(ctx, "author");
        String version = StringArgumentType.getString(ctx, "version");
        String description = hasDescription ? StringArgumentType.getString(ctx, "description") : "";

        ModDefinition mod = new ModDefinition(id, name, author, version, description, ModStatus.TESTING);
        boolean registered = ModRegistry.get().register(mod);

        CommandSourceStack source = ctx.getSource();
        if (!registered) {
            source.sendFailure(Component.literal("Un mod avec l'ID '" + id + "' est déjà enregistré."));
        } else {
            source.sendSuccess(() -> Component.literal("Mod enregistré : " + name + " (" + id + ")."), true);
        }
        return 1;
    }

    private static int runUnregister(CommandContext<CommandSourceStack> ctx) {
        String modId = StringArgumentType.getString(ctx, "modId");
        CommandSourceStack source = ctx.getSource();
        boolean removed = ModRegistry.get().unregister(modId);

        if (removed) {
            source.sendSuccess(() -> Component.literal("Mod '" + modId + "' retiré du registre."), true);
        } else {
            source.sendFailure(Component.literal("Aucun mod enregistré avec l'ID '" + modId + "'."));
        }
        return 1;
    }

    private static int runGitHubImport(CommandContext<CommandSourceStack> ctx) {
        String url = StringArgumentType.getString(ctx, "url");
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();

        source.sendSuccess(() -> Component.literal("Import GitHub en cours : " + url + " ..."), false);

        // L'appel réseau est entièrement asynchrone (voir GitHubClient) : il ne bloque jamais
        // le thread principal. whenComplete() s'exécute sur un thread GitHub en arrière-plan ;
        // on repasse explicitement par server.execute(...) avant tout message en jeu.
        GitHubIntegration.importer().importFromUrl(url).whenComplete((result, throwable) ->
            server.execute(() -> {
                if (throwable != null) {
                    source.sendFailure(Component.literal(
                        "✗ Impossible d'importer le mod\n\nRaison :\nErreur inattendue : " + throwable.getMessage()));
                    return;
                }
                reportImportResult(source, result);
            })
        );
        return 1;
    }

    private static int runGitHubRefresh(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();

        source.sendSuccess(() -> Component.literal(
            "Vérification des releases GitHub pour tous les mods ACCEPTED déjà rattachés..."), false);

        GitHubIntegration.importer().refreshAll().whenComplete((entries, throwable) ->
            server.execute(() -> {
                if (throwable != null) {
                    source.sendFailure(Component.literal(
                        "✗ Échec de la vérification\n\nRaison :\nErreur inattendue : " + throwable.getMessage()));
                    return;
                }
                reportRefreshResult(source, entries);
            })
        );
        return 1;
    }

    private static void reportRefreshResult(CommandSourceStack source, java.util.List<com.craftlab.craftlabcore.github.GitHubModImporter.RefreshEntry> entries) {
        if (entries.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                "Aucun mod ACCEPTED avec une source GitHub à vérifier."), false);
            return;
        }

        StringBuilder sb = new StringBuilder("Vérification terminée :\n");
        for (var entry : entries) {
            if (!entry.result().isSuccess()) {
                sb.append("\n✗ ").append(entry.modId()).append(" : ").append(entry.result().getMessage());
                continue;
            }
            String newVersion = entry.result().getMod().getVersion();
            if (newVersion.equals(entry.previousVersion())) {
                sb.append("\n= ").append(entry.modId()).append(' ').append(newVersion).append(" (déjà à jour)");
            } else {
                sb.append("\n~ ").append(entry.modId()).append(' ')
                    .append(entry.previousVersion()).append(" → ").append(newVersion);
            }
        }
        sb.append("\n\nPense à lancer /modpack prepare <modId> pour les mods mis à jour, "
            + "puis à vérifier /modpack diff avant /modpack apply.");
        source.sendSuccess(() -> Component.literal(sb.toString()), true);
    }

    private static void reportImportResult(CommandSourceStack source, ImportResult result) {
        if (result.isSuccess()) {
            ModDefinition mod = result.getMod();
            String repositoryUrl = mod.getSource() != null ? mod.getSource().getRepositoryUrl() : "?";
            String releaseTag = mod.getRelease() != null ? mod.getRelease().getTag() : "?";
            String assetName = mod.getRelease() != null ? mod.getRelease().getAssetName() : "?";

            String message = "✓ Mod importé depuis GitHub\n\n"
                + "Nom : " + mod.getName() + "\n"
                + "ID : " + mod.getId() + "\n"
                + "Auteur : " + mod.getAuthor() + "\n"
                + "Version : " + mod.getVersion() + "\n\n"
                + "GitHub :\n" + repositoryUrl + "\n\n"
                + "Release :\n" + releaseTag + "\n\n"
                + "Asset :\n" + assetName + "\n\n"
                + (result.isUpdated()
                    ? "Le mod a été mis à jour dans le ModRegistry."
                    : "Le mod est maintenant disponible dans le ModRegistry.");
            source.sendSuccess(() -> Component.literal(message), true);
        } else {
            String message = "✗ Impossible d'importer le mod\n\nRaison :\n" + result.getMessage();
            source.sendFailure(Component.literal(message));
        }
    }

    private static String formatList(Collection<ModDefinition> mods) {
        if (mods.isEmpty()) {
            return "Aucun mod enregistré pour le moment.";
        }

        StringBuilder sb = new StringBuilder("Mods communautaires :\n");
        for (ModDefinition mod : mods) {
            sb.append('\n').append(statusIcon(mod.getStatus())).append(' ').append(mod.getName()).append('\n');
            sb.append("   Statut : ").append(mod.getStatus()).append('\n');
        }
        return sb.toString();
    }

    private static String formatInfo(ModDefinition mod, ModProposal proposal) {
        StringBuilder sb = new StringBuilder();
        sb.append(mod.getName()).append("\n\n");
        sb.append("ID : ").append(mod.getId()).append('\n');
        sb.append("Auteur : ").append(mod.getAuthor()).append('\n');
        sb.append("Version : ").append(mod.getVersion()).append('\n');
        if (mod.getDescription() != null && !mod.getDescription().isBlank()) {
            sb.append("Description : ").append(mod.getDescription()).append('\n');
        }
        sb.append("\nStatut : ").append(mod.getStatus()).append('\n');

        if (proposal != null) {
            sb.append('\n').append("👍 Pour : ").append(proposal.countYes()).append('\n');
            sb.append("👎 Contre : ").append(proposal.countNo()).append('\n');

            if (proposal.getStatus() == ProposalStatus.TESTING) {
                sb.append("\nFin du vote : ").append(VoteManager.formatDuration(proposal.secondsRemaining()));
            } else {
                sb.append("\nRésultat : ").append(proposal.getStatus() == ProposalStatus.ACCEPTED ? "ACCEPTÉ" : "REJETÉ");
            }
        }

        return sb.toString();
    }

    private static String statusIcon(ModStatus status) {
        return switch (status) {
            case TESTING -> "🧪";
            case ACCEPTED -> "✅";
            case REJECTED -> "❌";
        };
    }
}
