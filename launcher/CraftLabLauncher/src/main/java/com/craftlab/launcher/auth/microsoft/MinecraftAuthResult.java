package com.craftlab.launcher.auth.microsoft;

/** accessToken : LE jeton réellement transmis à Minecraft (${auth_access_token}) — pas les jetons Microsoft/Xbox précédents. */
record MinecraftAuthResult(String accessToken, long expiresInSeconds) {
}
