package com.craftlab.craftlabcore.github;

import com.craftlab.craftlabcore.mod.ModDefinition;
import com.craftlab.craftlabcore.mod.ModReleaseInfo;
import com.craftlab.craftlabcore.mod.ModRegistry;
import com.craftlab.craftlabcore.mod.ModSource;
import com.craftlab.craftlabcore.mod.ModStatus;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.regex.Pattern;

/**
 * Orchestre un import GitHub de bout en bout : validation de l'URL, appels réseau via
 * GitHubService, règles métier (loader/version/release/conflit de modId), puis écriture
 * dans ModRegistry. C'est la SEULE classe qui connaît à la fois les types GitHub* et
 * ModRegistry/ModDefinition — ModRegistry lui-même ne voit jamais de type GitHub*, et les
 * classes de vote (proposal/vote) ne sont jamais touchées ici.
 */
public final class GitHubModImporter {

    private static final String TARGET_MINECRAFT_VERSION = "1.21.1";
    private static final String TARGET_LOADER = "forge";
    private static final String METADATA_FILE_NAME = "community-mod.json";
    private static final Pattern MOD_ID_PATTERN = Pattern.compile("^[a-z][a-z0-9_-]{1,63}$");

    private enum Stage { REPOSITORY, METADATA, RELEASES }

    private final GitHubService service;

    public GitHubModImporter(GitHubService service) {
        this.service = service;
    }

    public CompletableFuture<ImportResult> importFromUrl(String url) {
        Optional<GitHubUrl> parsed = GitHubUrl.parse(url);
        if (parsed.isEmpty()) {
            return CompletableFuture.completedFuture(ImportResult.failure(
                ImportResult.Status.INVALID_URL,
                "L'URL doit être de la forme https://github.com/owner/repository."
            ));
        }

        String owner = parsed.get().owner();
        String repo = parsed.get().repository();

        return service.fetchRepository(owner, repo)
            .exceptionally(t -> { throw new StageFailure(translate(t, Stage.REPOSITORY, owner, repo)); })
            .thenCompose(repository -> service.fetchCommunityModMetadata(owner, repo)
                .exceptionally(t -> { throw new StageFailure(translate(t, Stage.METADATA, owner, repo)); })
                .thenCompose(metadata -> {
                    ImportResult validationFailure = validateMetadata(metadata);
                    if (validationFailure != null) {
                        throw new StageFailure(validationFailure);
                    }
                    return service.fetchReleases(owner, repo)
                        .exceptionally(t -> { throw new StageFailure(translate(t, Stage.RELEASES, owner, repo)); })
                        .thenApply(releases -> buildResult(repository, metadata, releases));
                })
            )
            .exceptionally(this::unwrap);
    }

    private ImportResult validateMetadata(CommunityModMetadata metadata) {
        if (!MOD_ID_PATTERN.matcher(metadata.modId()).matches()) {
            return ImportResult.failure(ImportResult.Status.METADATA_INVALID,
                "modId invalide : '" + metadata.modId() + "' (lettres minuscules, chiffres, '-' ou '_', "
                    + "doit commencer par une lettre).");
        }
        if (!TARGET_MINECRAFT_VERSION.equals(metadata.minecraftVersion())) {
            return ImportResult.failure(ImportResult.Status.WRONG_MINECRAFT_VERSION,
                "Ce serveur tourne en " + TARGET_MINECRAFT_VERSION + ", mais le mod cible '"
                    + metadata.minecraftVersion() + "'.");
        }
        if (!TARGET_LOADER.equalsIgnoreCase(metadata.loader())) {
            return ImportResult.failure(ImportResult.Status.WRONG_LOADER,
                "Seul le loader 'forge' est accepté pour le moment (reçu : '" + metadata.loader() + "').");
        }
        return null;
    }

    private ImportResult buildResult(GitHubRepository repository, CommunityModMetadata metadata, List<GitHubRelease> releases) {
        Optional<GitHubRelease> selected = selectRelease(releases);
        if (selected.isEmpty()) {
            return ImportResult.failure(ImportResult.Status.NO_VALID_RELEASE,
                "Aucune release Forge " + TARGET_MINECRAFT_VERSION + " valide contenant un fichier .jar n'a été trouvée.");
        }

        GitHubRelease release = selected.get();
        List<GitHubAsset> jars = release.jarAssets();
        if (jars.size() > 1) {
            return ImportResult.failure(ImportResult.Status.MULTIPLE_JAR_ASSETS,
                "La release " + release.tag() + " contient plusieurs fichiers .jar : publiez-en un seul par release.");
        }
        GitHubAsset jar = jars.get(0);

        String modId = metadata.modId();
        Optional<ModDefinition> existing = ModRegistry.get().get(modId);

        if (isConflict(existing, repository)) {
            return ImportResult.failure(ImportResult.Status.MOD_ID_CONFLICT,
                "L'ID '" + modId + "' est déjà utilisé par un autre mod enregistré.");
        }

        boolean updated = existing.isPresent();
        ModStatus status = existing.map(ModDefinition::getStatus).orElse(ModStatus.TESTING);

        ModDefinition mod = new ModDefinition(
            modId,
            metadata.name(),
            metadata.author(),
            normalizeVersion(release.tag()),
            metadata.description(),
            status
        );
        mod.setSource(new ModSource(ModSource.TYPE_GITHUB, repository.owner(), repository.name(), repository.htmlUrl()));
        mod.setRelease(new ModReleaseInfo(release.tag(), release.id(), jar.name(), jar.downloadUrl(), jar.sha256Digest()));

        // ModRegistry est thread-safe (méthodes synchronized) : l'écriture peut se faire ici,
        // sur le thread HTTP GitHub. Seul l'envoi du message en jeu doit repasser par le thread
        // principal du serveur (voir ModCommand#runGitHubImport).
        if (updated) {
            ModRegistry.get().put(mod);
        } else {
            ModRegistry.get().register(mod);
        }

        return ImportResult.success(mod, updated);
    }

    /**
     * Un conflit réel n'existe que si l'entrée existante a déjà une source GitHub CONFIRMÉE et
     * différente (protège contre le hijack d'un modId déjà lié à un autre repository). Une entrée
     * existante sans source (bootstrap de CraftLabCore, ou /mod register manuel) n'a jamais été
     * liée à GitHub : rien à protéger, l'import doit pouvoir l'attacher. Pure — aucune dépendance
     * à ModRegistry — pour rester testable indépendamment de tout environnement Forge/FML.
     */
    boolean isConflict(Optional<ModDefinition> existing, GitHubRepository repository) {
        return existing.isPresent() && existing.get().getSource() != null && !isSameGitHubSource(existing.get(), repository);
    }

    private boolean isSameGitHubSource(ModDefinition existing, GitHubRepository repository) {
        ModSource source = existing.getSource();
        return source != null
            && ModSource.TYPE_GITHUB.equals(source.getType())
            && repository.owner().equalsIgnoreCase(source.getOwner())
            && repository.name().equalsIgnoreCase(source.getRepository());
    }

    private Optional<GitHubRelease> selectRelease(List<GitHubRelease> releases) {
        return releases.stream()
            .filter(r -> !r.draft() && !r.prerelease())
            .filter(r -> !r.jarAssets().isEmpty())
            .max(Comparator.comparing(GitHubModImporter::parsePublishedAt));
    }

    private static Instant parsePublishedAt(GitHubRelease release) {
        try {
            return release.publishedAt() != null ? Instant.parse(release.publishedAt()) : Instant.EPOCH;
        } catch (DateTimeParseException e) {
            return Instant.EPOCH;
        }
    }

    private static String normalizeVersion(String tag) {
        if (tag != null && tag.length() > 1 && (tag.charAt(0) == 'v' || tag.charAt(0) == 'V') && Character.isDigit(tag.charAt(1))) {
            return tag.substring(1);
        }
        return tag;
    }

    private ImportResult translate(Throwable throwable, Stage stage, String owner, String repo) {
        Throwable cause = unwrapCompletionException(throwable);

        if (cause instanceof GitHubException githubException) {
            return switch (githubException.getReason()) {
                case RATE_LIMITED -> ImportResult.failure(ImportResult.Status.RATE_LIMITED,
                    "Limite de requêtes GitHub atteinte, réessaie dans quelques minutes.");
                case NETWORK_ERROR, API_ERROR -> ImportResult.failure(ImportResult.Status.GITHUB_UNAVAILABLE,
                    "GitHub est actuellement inaccessible : " + githubException.getMessage());
                case INVALID_CONTENT -> ImportResult.failure(ImportResult.Status.METADATA_INVALID,
                    githubException.getMessage());
                case NOT_FOUND -> switch (stage) {
                    case REPOSITORY -> ImportResult.failure(ImportResult.Status.REPOSITORY_NOT_FOUND,
                        "Repository introuvable ou privé : " + owner + "/" + repo
                            + " (l'API publique ne distingue pas les deux cas).");
                    case METADATA -> ImportResult.failure(ImportResult.Status.METADATA_MISSING,
                        "Le fichier " + METADATA_FILE_NAME + " est introuvable dans " + owner + "/" + repo + ".");
                    case RELEASES -> ImportResult.failure(ImportResult.Status.NO_VALID_RELEASE,
                        "Aucune release trouvée pour " + owner + "/" + repo + ".");
                };
            };
        }

        return ImportResult.failure(ImportResult.Status.UNKNOWN_ERROR, "Erreur inattendue : " + cause.getMessage());
    }

    private ImportResult unwrap(Throwable throwable) {
        Throwable cause = unwrapCompletionException(throwable);
        if (cause instanceof StageFailure stageFailure) {
            return stageFailure.result;
        }
        return ImportResult.failure(ImportResult.Status.UNKNOWN_ERROR, "Erreur inattendue : " + cause.getMessage());
    }

    private static Throwable unwrapCompletionException(Throwable throwable) {
        Throwable cause = throwable;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    /** Exception interne servant uniquement à transporter un ImportResult déjà construit jusqu'au unwrap() final. */
    private static final class StageFailure extends RuntimeException {
        private final ImportResult result;

        StageFailure(ImportResult result) {
            super(result.getMessage());
            this.result = result;
        }
    }
}
