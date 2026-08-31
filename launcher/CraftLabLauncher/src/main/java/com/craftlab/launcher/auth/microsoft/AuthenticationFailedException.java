package com.craftlab.launcher.auth.microsoft;

/**
 * Non vérifiée : AuthProvider.authenticate() ne déclare aucune exception (voir l'interface,
 * partagée avec OfflineAuthProvider qui n'en lève jamais). L'appelant actuel
 * (CraftLabLauncherApp.runLaunch()) capture déjà Exception autour de authenticate() et affiche
 * son message dans le journal sans jamais planter — le message porté ici doit donc déjà être
 * directement présentable au joueur.
 */
public class AuthenticationFailedException extends RuntimeException {

    public AuthenticationFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
