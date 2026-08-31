package com.craftlab.launcher.auth.microsoft;

/** userHash ("uhs") : identifiant Xbox nécessaire à l'étape XSTS suivante, distinct du token lui-même. */
record XboxLiveToken(String token, String userHash) {
}
