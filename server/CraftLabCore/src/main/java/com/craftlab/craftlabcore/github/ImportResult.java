package com.craftlab.craftlabcore.github;

import com.craftlab.craftlabcore.mod.ModDefinition;

/** Résultat d'une tentative d'import GitHub : succès avec le ModDefinition créé/mis à jour, ou échec avec une raison précise. */
public final class ImportResult {

    public enum Status {
        SUCCESS,
        INVALID_URL,
        REPOSITORY_NOT_FOUND,
        GITHUB_UNAVAILABLE,
        RATE_LIMITED,
        METADATA_MISSING,
        METADATA_INVALID,
        WRONG_MINECRAFT_VERSION,
        WRONG_LOADER,
        NO_VALID_RELEASE,
        MULTIPLE_JAR_ASSETS,
        MOD_ID_CONFLICT,
        UNKNOWN_ERROR
    }

    private final Status status;
    private final String message;
    private final ModDefinition mod;
    private final boolean updated;

    private ImportResult(Status status, String message, ModDefinition mod, boolean updated) {
        this.status = status;
        this.message = message;
        this.mod = mod;
        this.updated = updated;
    }

    public static ImportResult success(ModDefinition mod, boolean updated) {
        return new ImportResult(Status.SUCCESS, null, mod, updated);
    }

    public static ImportResult failure(Status status, String message) {
        return new ImportResult(status, message, null, false);
    }

    public Status getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

    public ModDefinition getMod() {
        return mod;
    }

    public boolean isUpdated() {
        return updated;
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }
}
