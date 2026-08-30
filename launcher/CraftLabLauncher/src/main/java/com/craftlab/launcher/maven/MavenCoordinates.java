package com.craftlab.launcher.maven;

/**
 * Résout des coordonnées Maven (group:artifact:version[:classifier][@ext]) en chemin relatif
 * de dépôt (group/with/slashes/artifact/version/artifact-version[-classifier].ext). Utilisé à
 * la fois pour lire un version.json (VersionManifestResolver) et pour exécuter les processors
 * de l'installeur Forge (ForgeInstaller) — même format, même logique, un seul endroit.
 */
public final class MavenCoordinates {

    private MavenCoordinates() {
    }

    public static String toRelativePath(String coordinates) {
        String ext = "jar";
        String base = coordinates;
        int extSeparator = coordinates.indexOf('@');
        if (extSeparator >= 0) {
            ext = coordinates.substring(extSeparator + 1);
            base = coordinates.substring(0, extSeparator);
        }

        String[] parts = base.split(":");
        if (parts.length < 3) {
            throw new IllegalArgumentException("Coordonnées Maven invalides : " + coordinates);
        }
        String group = parts[0].replace('.', '/');
        String artifact = parts[1];
        String version = parts[2];
        String classifier = parts.length > 3 ? "-" + parts[3] : "";

        return group + "/" + artifact + "/" + version + "/" + artifact + "-" + version + classifier + "." + ext;
    }
}
