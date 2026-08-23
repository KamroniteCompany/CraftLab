package com.communityserver.communitytest.command;

import com.communityserver.communitytest.modpack.ApplyResult;
import com.communityserver.communitytest.modpack.ModPack;
import com.communityserver.communitytest.modpack.ModPackApplier;
import com.communityserver.communitytest.modpack.ModPackDiff;
import com.communityserver.communitytest.modpack.ModPackEntry;
import com.communityserver.communitytest.modpack.ModPackManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

public final class ModPackCommand {

    private ModPackCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("modpack")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("list")
                    .executes(ModPackCommand::runList))
                .then(Commands.literal("next")
                    .executes(ModPackCommand::runNext))
                .then(Commands.literal("status")
                    .executes(ModPackCommand::runStatus))
                .then(Commands.literal("diff")
                    .executes(ModPackCommand::runDiff))
                .then(Commands.literal("verify")
                    .executes(ModPackCommand::runVerify))
                .then(Commands.literal("apply")
                    .executes(ModPackCommand::runApply))
                .then(Commands.literal("rollback")
                    .executes(ModPackCommand::runRollback))
                .then(Commands.literal("prepare")
                    .then(Commands.argument("modId", StringArgumentType.word())
                        .executes(ModPackCommand::runPrepare)))
                .then(Commands.literal("remove")
                    .then(Commands.argument("modId", StringArgumentType.word())
                        .executes(ModPackCommand::runRemove)))
                .then(Commands.literal("sync")
                    .executes(ModPackCommand::runSync))
        );
    }

    private static int runList(CommandContext<CommandSourceStack> ctx) {
        ModPack current = ModPackManager.get().getCurrent();
        ctx.getSource().sendSuccess(() -> Component.literal(formatPack("Active ModPack", current, false)), false);
        return 1;
    }

    private static int runNext(CommandContext<CommandSourceStack> ctx) {
        ModPack next = ModPackManager.get().getNext();
        String message = formatPack("Next ModPack", next, true);
        ctx.getSource().sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private static int runStatus(CommandContext<CommandSourceStack> ctx) {
        ModPack current = ModPackManager.get().getCurrent();
        ModPack next = ModPackManager.get().getNext();
        ModPackDiff diff = ModPackManager.get().diffCurrentVsNext();

        String message = "Current ModPack : " + current.getApplyState() + " (v" + current.getGeneration() + ")\n"
            + "Next ModPack    : " + next.getApplyState() + "\n\n"
            + "Changes:\n" + formatDiff(diff);

        ctx.getSource().sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private static int runDiff(CommandContext<CommandSourceStack> ctx) {
        ModPackDiff diff = ModPackManager.get().diffCurrentVsNext();
        ctx.getSource().sendSuccess(() -> Component.literal(formatDiff(diff)), false);
        return 1;
    }

    private static int runVerify(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        boolean ok = ModPackApplier.get().verifyNext();
        if (ok) {
            source.sendSuccess(() -> Component.literal(
                "Tous les fichiers du prochain ModPack sont présents et valides (SHA-256 correct)."), false);
        } else {
            source.sendFailure(Component.literal(
                "Un ou plusieurs fichiers du prochain ModPack sont manquants ou invalides."));
        }
        return 1;
    }

    private static int runApply(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();

        source.sendSuccess(() -> Component.literal("Application du prochain ModPack en cours..."), false);

        // Backup + staging + validation + swap s'exécutent sur un thread dédié (voir
        // ModPackApplier) ; on repasse explicitement par server.execute(...) avant tout message.
        ModPackApplier.get().applyAsync().whenComplete((result, throwable) ->
            server.execute(() -> {
                if (throwable != null) {
                    source.sendFailure(Component.literal("Erreur inattendue lors de l'application : " + throwable.getMessage()));
                    return;
                }
                switch (result.getStatus()) {
                    case ALREADY_UP_TO_DATE ->
                        source.sendSuccess(() -> Component.literal("Le ModPack actif est déjà à jour."), true);
                    case VALIDATION_FAILED, APPLY_FAILED ->
                        source.sendFailure(Component.literal(result.getMessage()));
                    case NOT_READY ->
                        source.sendFailure(Component.literal("Le prochain ModPack n'est pas prêt."));
                    case APPLIED ->
                        source.sendSuccess(() -> Component.literal(
                            "NEXT ModPack is ready to be applied on next server restart."), true);
                }
            })
        );
        return 1;
    }

    private static int runRollback(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();

        source.sendSuccess(() -> Component.literal("Rollback en cours..."), false);

        ModPackApplier.get().rollbackAsync().whenComplete((result, throwable) ->
            server.execute(() -> {
                if (throwable != null) {
                    source.sendFailure(Component.literal("Erreur inattendue lors du rollback : " + throwable.getMessage()));
                    return;
                }
                if (result.getStatus() == ApplyResult.Status.APPLIED) {
                    source.sendSuccess(() -> Component.literal(
                        "Rollback préparé vers le dernier backup valide. Redémarrage nécessaire pour l'appliquer."), true);
                } else {
                    source.sendFailure(Component.literal(result.getMessage()));
                }
            })
        );
        return 1;
    }

    private static int runPrepare(CommandContext<CommandSourceStack> ctx) {
        String modId = StringArgumentType.getString(ctx, "modId");
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();

        source.sendSuccess(() -> Component.literal("Préparation de '" + modId + "' en cours..."), false);

        ModPackManager.get().prepareMod(modId).whenComplete((result, throwable) ->
            server.execute(() -> {
                if (throwable != null) {
                    source.sendFailure(Component.literal("Erreur inattendue lors de la préparation : " + throwable.getMessage()));
                    return;
                }
                switch (result) {
                    case MOD_NOT_FOUND ->
                        source.sendFailure(Component.literal("Aucun mod enregistré avec l'ID '" + modId + "'."));
                    case NOT_ACCEPTED ->
                        source.sendFailure(Component.literal("Le mod '" + modId + "' n'est pas ACCEPTED : impossible de le préparer."));
                    case NO_RELEASE_INFO ->
                        source.sendFailure(Component.literal("Le mod '" + modId + "' n'a pas d'information de release GitHub exploitable."));
                    case DOWNLOAD_FAILED ->
                        source.sendFailure(Component.literal("Échec de la préparation de '" + modId + "' (téléchargement ou vérification)."));
                    case ALREADY_PREPARED ->
                        source.sendSuccess(() -> Component.literal("'" + modId + "' était déjà prêt et à jour dans le prochain ModPack."), true);
                    case PREPARED ->
                        source.sendSuccess(() -> Component.literal(
                            "Mod ajouté au prochain ModPack actif.\n\nRedémarrage nécessaire pour appliquer la modification."), true);
                }
            })
        );
        return 1;
    }

    private static int runRemove(CommandContext<CommandSourceStack> ctx) {
        String modId = StringArgumentType.getString(ctx, "modId");
        CommandSourceStack source = ctx.getSource();

        boolean removed = ModPackManager.get().removeMod(modId);
        if (removed) {
            source.sendSuccess(() -> Component.literal(
                "'" + modId + "' retiré du prochain ModPack. Le serveur en fonctionnement n'est pas affecté."), true);
        } else {
            source.sendFailure(Component.literal("'" + modId + "' n'était pas dans le prochain ModPack."));
        }
        return 1;
    }

    private static int runSync(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();

        source.sendSuccess(() -> Component.literal("Synchronisation du ModPack en cours..."), false);

        ModPackManager.get().sync().whenComplete((processed, throwable) ->
            server.execute(() -> {
                if (throwable != null) {
                    source.sendFailure(Component.literal("Erreur inattendue lors de la synchronisation : " + throwable.getMessage()));
                    return;
                }
                String message = processed.isEmpty()
                    ? "Le prochain ModPack était déjà synchronisé avec le ModRegistry."
                    : "Synchronisation terminée : " + String.join(", ", processed) + ".";
                source.sendSuccess(() -> Component.literal(message), true);
            })
        );
        return 1;
    }

    private static String formatPack(String title, ModPack pack, boolean includeState) {
        StringBuilder sb = new StringBuilder(title).append("\n\n");
        if (pack.getMods().isEmpty()) {
            sb.append("(aucun mod)");
        } else {
            boolean first = true;
            for (ModPackEntry entry : pack.getMods()) {
                if (!first) {
                    sb.append('\n');
                }
                first = false;
                sb.append("✓ ").append(entry.getName()).append(' ').append(entry.getVersion());
            }
        }
        if (includeState) {
            sb.append("\n\nÉtat des fichiers : ").append(pack.getState());
            sb.append("\nÉtat d'application : ").append(pack.getApplyState());
        }
        return sb.toString();
    }

    private static String formatDiff(ModPackDiff diff) {
        if (diff.isEmpty()) {
            return "(aucun changement)";
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (ModPackEntry entry : diff.getAdded()) {
            if (!first) {
                sb.append('\n');
            }
            first = false;
            sb.append("+ ").append(entry.getName()).append(' ').append(entry.getVersion());
        }
        for (ModPackEntry entry : diff.getRemoved()) {
            if (!first) {
                sb.append('\n');
            }
            first = false;
            sb.append("- ").append(entry.getName()).append(' ').append(entry.getVersion());
        }
        for (ModPackDiff.UpdatedEntry updated : diff.getUpdated()) {
            if (!first) {
                sb.append('\n');
            }
            first = false;
            sb.append("~ ").append(updated.to().getName()).append(' ')
                .append(updated.from().getVersion()).append(" → ").append(updated.to().getVersion());
        }
        return sb.toString();
    }
}
