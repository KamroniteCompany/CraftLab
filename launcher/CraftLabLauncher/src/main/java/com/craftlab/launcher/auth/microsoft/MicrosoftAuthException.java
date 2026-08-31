package com.craftlab.launcher.auth.microsoft;

/**
 * Toute étape de la chaîne Microsoft -> Xbox Live -> XSTS -> Minecraft Services qui échoue lève
 * ce type unique, avec un message déjà adapté à un affichage direct au joueur (jamais une trace
 * technique brute) — voir MicrosoftAuthProvider, qui ne laisse jamais une autre exception
 * s'échapper vers l'appelant.
 */
public class MicrosoftAuthException extends Exception {

    public MicrosoftAuthException(String message) {
        super(message);
    }

    public MicrosoftAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
