package com.craftlab.launcher.auth.microsoft;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Étape 2 (Xbox Live) : échange le jeton Microsoft (jamais le mot de passe, jamais transmis à
 * cette application) contre un jeton Xbox Live — nécessaire avant de pouvoir demander un jeton
 * XSTS (étape suivante), lui-même nécessaire avant d'obtenir un jeton Minecraft. Le compte
 * Minecraft Java Edition EST un compte Xbox depuis la migration Mojang -> Microsoft ; cette
 * étape est obligatoire même pour un joueur qui ne s'est jamais considéré comme "joueur Xbox".
 */
final class XboxLiveClient {

    private static final String DEFAULT_ENDPOINT = "https://user.auth.xboxlive.com/user/authenticate";

    private final String endpoint;
    private final HttpClient httpClient;

    XboxLiveClient() {
        this(DEFAULT_ENDPOINT, HttpClient.newHttpClient());
    }

    /** Pour les tests : endpoint substituable par un serveur HTTP local. */
    XboxLiveClient(String endpoint, HttpClient httpClient) {
        this.endpoint = endpoint;
        this.httpClient = httpClient;
    }

    XboxLiveToken authenticate(String microsoftAccessToken) throws MicrosoftAuthException {
        JsonObject properties = new JsonObject();
        properties.addProperty("AuthMethod", "RPS");
        properties.addProperty("SiteName", "user.auth.xboxlive.com");
        // "d=" est le préfixe attendu par Xbox Live devant le jeton Microsoft brut (format RPS ticket).
        properties.addProperty("RpsTicket", "d=" + microsoftAccessToken);

        JsonObject payload = new JsonObject();
        payload.add("Properties", properties);
        payload.addProperty("RelyingParty", "http://auth.xboxlive.com");
        payload.addProperty("TokenType", "JWT");

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
            .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new MicrosoftAuthException("Impossible de contacter Xbox Live : " + e.getMessage(), e);
        }

        if (response.statusCode() != 200) {
            throw new MicrosoftAuthException("Xbox Live a refusé la connexion (code " + response.statusCode() + ").");
        }

        return parse(response.body());
    }

    static XboxLiveToken parse(String json) throws MicrosoftAuthException {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            String token = root.get("Token").getAsString();
            String userHash = root.getAsJsonObject("DisplayClaims")
                .getAsJsonArray("xui").get(0).getAsJsonObject()
                .get("uhs").getAsString();
            return new XboxLiveToken(token, userHash);
        } catch (RuntimeException e) {
            throw new MicrosoftAuthException("Réponse Xbox Live illisible ou incomplète.", e);
        }
    }
}
