package com.craftlab.launcher.auth;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Authentification "hors-ligne" : UUID dérivé du pseudo par le même algorithme que le mode
 * hors-ligne vanilla (UUID.nameUUIDFromBytes("OfflinePlayer:" + pseudo)), aucun jeton réel.
 * Ne fonctionne que si le serveur CraftLab tourne avec online-mode=false dans son
 * server.properties — ce n'est pas configuré ici, c'est une contrainte côté serveur à
 * respecter séparément.
 */
public class OfflineAuthProvider implements AuthProvider {

    @Override
    public AuthSession authenticate(String username) {
        UUID offlineUuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
        return new AuthSession(username, offlineUuid.toString().replace("-", ""), "0", "legacy");
    }
}
