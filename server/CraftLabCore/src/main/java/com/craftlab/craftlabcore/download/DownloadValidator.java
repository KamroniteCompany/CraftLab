package com.craftlab.craftlabcore.download;

import java.util.Locale;
import java.util.Optional;

/** Validations rapides, avant même de tenter une requête réseau. */
public final class DownloadValidator {

    private DownloadValidator() {
    }

    public static Optional<String> validateUrl(String url) {
        if (url == null || !url.toLowerCase(Locale.ROOT).startsWith("https://")) {
            return Optional.of("L'URL de téléchargement doit être en HTTPS.");
        }
        return Optional.empty();
    }

    public static Optional<String> validateAssetName(String assetName) {
        if (assetName == null || !assetName.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            return Optional.of("Le fichier attendu doit avoir l'extension .jar.");
        }
        return Optional.empty();
    }
}
