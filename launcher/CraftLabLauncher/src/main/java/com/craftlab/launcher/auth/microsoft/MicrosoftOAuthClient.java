package com.craftlab.launcher.auth.microsoft;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Étape 1 de la chaîne (Microsoft Identity), flux OAuth2 "Authorization Code + PKCE" contre
 * Entra ID (anciennement Azure AD) — voir docs/authentication.md pour le détail complet du flux
 * et pourquoi ce choix plutôt que le device code flow. Tenant "consumers" : les comptes
 * Minecraft/Xbox sont des comptes Microsoft personnels, jamais professionnels/scolaires.
 *
 * IMPORTANT : le résultat de cette classe (un jeton Microsoft) n'est PAS un jeton Minecraft.
 * Il ne sert qu'à s'authentifier ensuite auprès de Xbox Live (voir XboxLiveClient) — jamais
 * transmis à Minecraft lui-même.
 */
final class MicrosoftOAuthClient {

    private static final String DEFAULT_AUTHORITY = "https://login.microsoftonline.com/consumers/oauth2/v2.0";
    private static final String SCOPE = "XboxLive.signin offline_access";

    private final String clientId;
    private final String authority;
    private final HttpClient httpClient;

    MicrosoftOAuthClient(String clientId) {
        this(clientId, DEFAULT_AUTHORITY, HttpClient.newHttpClient());
    }

    /** Pour les tests : autorité substituable par un serveur HTTP local. */
    MicrosoftOAuthClient(String clientId, String authority, HttpClient httpClient) {
        this.clientId = clientId;
        this.authority = authority;
        this.httpClient = httpClient;
    }

    /**
     * URL à ouvrir dans le navigateur système de l'utilisateur — jamais dans une fenêtre
     * embarquée par cette application, pour que la page de connexion soit vérifiablement la
     * vraie page Microsoft (barre d'adresse visible, aucune interception possible du mot de
     * passe par le launcher).
     */
    String buildAuthorizeUrl(String redirectUri, String codeChallenge, String state) {
        return authority + "/authorize"
            + "?client_id=" + encode(clientId)
            + "&response_type=code"
            + "&redirect_uri=" + encode(redirectUri)
            + "&scope=" + encode(SCOPE)
            + "&code_challenge=" + encode(codeChallenge)
            + "&code_challenge_method=S256"
            + "&state=" + encode(state)
            + "&prompt=select_account";
    }

    MicrosoftTokenResponse exchangeAuthorizationCode(String code, String redirectUri, String codeVerifier)
            throws MicrosoftAuthException {
        return requestToken(Map.of(
            "client_id", clientId,
            "grant_type", "authorization_code",
            "code", code,
            "redirect_uri", redirectUri,
            "code_verifier", codeVerifier
        ));
    }

    /** Renouvellement silencieux : ne relance jamais le navigateur si le refresh token est encore valide. */
    MicrosoftTokenResponse refresh(String refreshToken) throws MicrosoftAuthException {
        return requestToken(Map.of(
            "client_id", clientId,
            "grant_type", "refresh_token",
            "refresh_token", refreshToken,
            "scope", SCOPE
        ));
    }

    private MicrosoftTokenResponse requestToken(Map<String, String> formParams) throws MicrosoftAuthException {
        String form = formParams.entrySet().stream()
            .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
            .collect(Collectors.joining("&"));

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(authority + "/token"))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new MicrosoftAuthException("Impossible de contacter le service de connexion Microsoft : " + e.getMessage(), e);
        }

        JsonObject body;
        try {
            body = JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new MicrosoftAuthException("Réponse illisible du service de connexion Microsoft.", e);
        }

        if (response.statusCode() != 200) {
            String errorDescription = body.has("error_description") ? body.get("error_description").getAsString()
                : body.has("error") ? body.get("error").getAsString() : "code " + response.statusCode();
            throw new MicrosoftAuthException("Connexion Microsoft refusée : " + errorDescription);
        }

        if (!body.has("access_token") || !body.has("refresh_token")) {
            throw new MicrosoftAuthException("Réponse Microsoft incomplète (access_token/refresh_token manquant).");
        }

        return new MicrosoftTokenResponse(
            body.get("access_token").getAsString(),
            body.get("refresh_token").getAsString(),
            body.has("expires_in") ? body.get("expires_in").getAsLong() : 3600L
        );
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
