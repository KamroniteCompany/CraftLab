package com.craftlab.launcher.auth.microsoft;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Étapes 4 et 5 : échange le jeton XSTS contre un vrai jeton Minecraft
 * (api.minecraftservices.com/authentication/login_with_xbox), puis récupère le profil Minecraft
 * (nom + UUID) avec ce jeton — les deux seules informations, avec le jeton lui-même, que
 * MinecraftLauncher a besoin de connaître ; ni le jeton Microsoft ni les jetons Xbox/XSTS
 * intermédiaires ne sont jamais transmis au jeu.
 */
final class MinecraftServicesClient {

    private static final String DEFAULT_LOGIN_ENDPOINT = "https://api.minecraftservices.com/authentication/login_with_xbox";
    private static final String DEFAULT_PROFILE_ENDPOINT = "https://api.minecraftservices.com/minecraft/profile";

    private final String loginEndpoint;
    private final String profileEndpoint;
    private final HttpClient httpClient;

    MinecraftServicesClient() {
        this(DEFAULT_LOGIN_ENDPOINT, DEFAULT_PROFILE_ENDPOINT, HttpClient.newHttpClient());
    }

    /** Pour les tests : endpoints substituables par un serveur HTTP local. */
    MinecraftServicesClient(String loginEndpoint, String profileEndpoint, HttpClient httpClient) {
        this.loginEndpoint = loginEndpoint;
        this.profileEndpoint = profileEndpoint;
        this.httpClient = httpClient;
    }

    MinecraftAuthResult loginWithXbox(String userHash, String xstsToken) throws MicrosoftAuthException {
        JsonObject payload = new JsonObject();
        // Format exact exigé par l'API : "XBL3.0 x=<uhs>;<xsts token>".
        payload.addProperty("identityToken", "XBL3.0 x=" + userHash + ";" + xstsToken);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(loginEndpoint))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
            .build();

        HttpResponse<String> response = send(request, "Minecraft Services (connexion)");

        if (response.statusCode() != 200) {
            throw new MicrosoftAuthException("Minecraft Services a refusé la connexion (code " + response.statusCode() + ").");
        }

        try {
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            return new MinecraftAuthResult(
                root.get("access_token").getAsString(),
                root.has("expires_in") ? root.get("expires_in").getAsLong() : 86400L
            );
        } catch (RuntimeException e) {
            throw new MicrosoftAuthException("Réponse Minecraft Services illisible.", e);
        }
    }

    MinecraftProfile fetchProfile(String minecraftAccessToken) throws MicrosoftAuthException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(profileEndpoint))
            .timeout(Duration.ofSeconds(15))
            .header("Authorization", "Bearer " + minecraftAccessToken)
            .GET()
            .build();

        HttpResponse<String> response = send(request, "Minecraft Services (profil)");

        if (response.statusCode() == 404) {
            throw new MicrosoftAuthException("Ce compte Microsoft ne possède pas Minecraft: Java Edition. "
                + "Achetez le jeu sur https://www.minecraft.net avant de vous connecter.");
        }
        if (response.statusCode() != 200) {
            throw new MicrosoftAuthException("Impossible de récupérer le profil Minecraft (code " + response.statusCode() + ").");
        }

        try {
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            return new MinecraftProfile(root.get("id").getAsString(), root.get("name").getAsString());
        } catch (RuntimeException e) {
            throw new MicrosoftAuthException("Réponse de profil Minecraft illisible.", e);
        }
    }

    private HttpResponse<String> send(HttpRequest request, String stepName) throws MicrosoftAuthException {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new MicrosoftAuthException("Impossible de contacter " + stepName + " : " + e.getMessage(), e);
        }
    }
}
