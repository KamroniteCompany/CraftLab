package com.craftlab.launcher.auth.microsoft;

/** accessToken : jeton Microsoft/Entra ID brut — jamais utilisé directement pour lancer Minecraft, seulement pour s'authentifier auprès de Xbox Live (étape suivante). */
record MicrosoftTokenResponse(String accessToken, String refreshToken, long expiresInSeconds) {
}
