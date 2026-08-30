package com.craftlab.launcher.auth;

/**
 * Isole la méthode d'authentification utilisée pour lancer Minecraft. Pour cette première
 * version, seule OfflineAuthProvider existe. Une authentification Microsoft complète
 * (OAuth device code flow + jeton Xbox Live + jeton Minecraft) pourrait être ajoutée plus
 * tard via une nouvelle implémentation de cette interface, sans changer MinecraftLauncher ni
 * aucune autre partie du launcher. Aucun mot de passe Microsoft ne doit jamais transiter par
 * cette application.
 */
public interface AuthProvider {
    AuthSession authenticate(String username);
}
