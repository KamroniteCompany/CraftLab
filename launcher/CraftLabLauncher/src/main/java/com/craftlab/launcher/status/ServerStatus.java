package com.craftlab.launcher.status;

/**
 * Résultat d'une vérification de statut serveur (Server List Ping). `online` est le seul champ
 * garanti fiable : `onlinePlayers`/`maxPlayers` restent null si le serveur a répondu mais sans
 * inclure ce champ (jamais 0 par défaut, pour ne pas confondre "inconnu" et "aucun joueur").
 */
public record ServerStatus(boolean online, Integer onlinePlayers, Integer maxPlayers, String detail) {

    public static ServerStatus online(Integer onlinePlayers, Integer maxPlayers) {
        return new ServerStatus(true, onlinePlayers, maxPlayers, null);
    }

    public static ServerStatus offline(String reason) {
        return new ServerStatus(false, null, null, reason);
    }
}
