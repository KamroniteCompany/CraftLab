package com.craftlab.craftlabcore.client;

import java.util.Optional;

/**
 * Résout le serveur CraftLab à rejoindre, pour le bouton "Jouer" de {@link CraftLabTitleScreen}.
 *
 * Le CraftLab Launcher ne rejoint plus automatiquement le serveur au démarrage (Quick Play a
 * été désactivé pour le lancement normal — voir MinecraftLauncher côté launcher, qui affiche
 * désormais systématiquement CraftLabTitleScreen avant toute connexion). server_address et
 * server_port restent néanmoins fournis par le launcher, via deux propriétés système
 * ({@code -Dcraftlab.server.address}/{@code -Dcraftlab.server.port}) plutôt que dupliqués dans
 * une config séparée côté mod qui pourrait diverger de ce que le launcher utilise réellement :
 * le bouton "Jouer" cible alors TOUJOURS le serveur configuré dans launcher.properties, jamais
 * une valeur codée en dur.
 *
 * Ordre de résolution :
 *   1. -Dcraftlab.server.address / -Dcraftlab.server.port — le mécanisme actuel (voir ci-dessus).
 *   2. --quickPlayMultiplayer <host:port> dans les arguments du processus — conservé en repli
 *      pour le seul cas où Quick Play serait un jour réactivé côté launcher (voir
 *      MinecraftLauncher.QUICK_PLAY_FEATURES), sans qu'aucun code ne soit à changer ici.
 *   3. "localhost:25565" — uniquement si le jeu n'a pas été lancé par le CraftLab Launcher (ex.
 *      lancement direct depuis l'environnement de dev ForgeGradle, sans ces propriétés/arguments).
 */
final class ServerTarget {

    private static final String ADDRESS_PROPERTY = "craftlab.server.address";
    private static final String PORT_PROPERTY = "craftlab.server.port";
    private static final String QUICK_PLAY_MULTIPLAYER_ARG = "--quickPlayMultiplayer";
    private static final String DEFAULT_ADDRESS = "localhost:25565";

    private static volatile String cached;

    private ServerTarget() {
    }

    /** Format "host:port", identique à celui attendu par ServerAddress.parseString. */
    static String resolve() {
        String value = cached;
        if (value == null) {
            value = detect();
            cached = value;
        }
        return value;
    }

    private static String detect() {
        String fromProperties = detectFromSystemProperties();
        if (fromProperties != null) {
            return fromProperties;
        }
        String fromQuickPlayArg = detectFromProcessArguments();
        if (fromQuickPlayArg != null) {
            return fromQuickPlayArg;
        }
        return DEFAULT_ADDRESS;
    }

    private static String detectFromSystemProperties() {
        String address = System.getProperty(ADDRESS_PROPERTY);
        String port = System.getProperty(PORT_PROPERTY);
        if (address == null || address.isBlank() || port == null || port.isBlank()) {
            return null;
        }
        return address + ":" + port;
    }

    private static String detectFromProcessArguments() {
        Optional<String[]> arguments = ProcessHandle.current().info().arguments();
        if (arguments.isPresent()) {
            String[] args = arguments.get();
            for (int i = 0; i < args.length - 1; i++) {
                if (QUICK_PLAY_MULTIPLAYER_ARG.equals(args[i])) {
                    return args[i + 1];
                }
            }
        }
        return null;
    }
}
