package com.craftlab.launcher.auth.microsoft;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * PKCE (Proof Key for Code Exchange, RFC 7636) — obligatoire pour un client "public" (une
 * application desktop ne peut garder aucun secret confidentiel) dans le flux Authorization Code
 * recommandé par Microsoft pour ce type de client :
 * https://learn.microsoft.com/en-us/entra/identity-platform/scenario-desktop-acquire-token
 *
 * Empêche qu'un attaquant ayant intercepté le "code" d'autorisation (ex. un autre programme
 * local écoutant sur le même ordinateur) puisse l'échanger lui-même contre des jetons : seul le
 * processus qui connaît code_verifier (jamais transmis avant l'échange final) peut compléter
 * l'échange.
 */
final class PkceUtil {

    private static final SecureRandom RANDOM = new SecureRandom();

    private PkceUtil() {
    }

    /** 32 octets aléatoires encodés en base64url : dans la plage de longueur 43-128 exigée par le RFC. */
    static String generateCodeVerifier() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Méthode S256 (SHA-256 puis base64url) — la seule acceptée par Microsoft Entra ID pour PKCE. */
    static String codeChallenge(String codeVerifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 non disponible sur cette JVM.", e);
        }
    }
}
