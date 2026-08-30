package com.craftlab.launcher.launch;

/**
 * Le serveur auquel Minecraft doit se connecter automatiquement au démarrage. Isolé dans son
 * propre type (plutôt que deux paramètres address/port épars dans MinecraftLauncher) pour que
 * l'évolution vers plusieurs serveurs sélectionnables (voir LauncherConfig) n'ait qu'à produire
 * une instance différente de ce type, sans toucher à la logique de construction des arguments
 * de lancement elle-même.
 */
public record ServerConnectionTarget(String address, int port) {

    public ServerConnectionTarget {
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("server_address ne peut pas être vide.");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("server_port invalide : " + port);
        }
    }

    /**
     * Format "host:port" attendu par l'argument --quickPlayMultiplayer de Minecraft 1.21.1.
     * Non utilisé par MinecraftLauncher.launch() actuellement (voir sa documentation de
     * classe : la connexion automatique par Quick Play a été désactivée au profit de
     * CraftLabTitleScreen côté mod client) — conservé pour une éventuelle réactivation future.
     */
    public String toQuickPlayAddress() {
        return address + ":" + port;
    }

    @Override
    public String toString() {
        return address + ":" + port;
    }
}
