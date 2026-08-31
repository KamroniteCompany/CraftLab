package com.craftlab.launcher;

/**
 * Version réelle du launcher, lue depuis le manifeste du JAR (Implementation-Version, posé par
 * build.gradle à partir de `version = ...`) au lieu d'être recopiée à la main quelque part dans
 * le code. En exécution via `./gradlew run` (classes explosées, pas de JAR/manifeste), la valeur
 * n'existe pas : on retombe sur "dev" plutôt que d'inventer un numéro.
 */
public final class AppVersion {

    private static final String FALLBACK = "dev";

    private AppVersion() {
    }

    public static String current() {
        String version = AppVersion.class.getPackage().getImplementationVersion();
        return version != null ? version : FALLBACK;
    }
}
