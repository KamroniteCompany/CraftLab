package com.craftlab.launcher.auth.microsoft;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Étape 3 (XSTS — Xbox Security Token Service) : échange le jeton Xbox Live contre un jeton
 * "autorisé pour Minecraft" (RelyingParty = api.minecraftservices.com). C'est cette étape,
 * spécifiquement, qui échoue avec un code XErr identifiable si le compte ne peut structurellement
 * pas jouer à Minecraft (pas de compte Xbox associé, restriction régionale, compte enfant sans
 * accord parental) — jamais un problème côté launcher dans ces cas, un message clair doit
 * l'indiquer plutôt que de faire échouer silencieusement la suite de la chaîne.
 */
final class XstsClient {

    private static final String DEFAULT_ENDPOINT = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String RELYING_PARTY = "rp://api.minecraftservices.com/";

    private final String endpoint;
    private final HttpClient httpClient;

    XstsClient() {
        this(DEFAULT_ENDPOINT, HttpClient.newHttpClient());
    }

    /** Pour les tests : endpoint substituable par un serveur HTTP local. */
    XstsClient(String endpoint, HttpClient httpClient) {
        this.endpoint = endpoint;
        this.httpClient = httpClient;
    }

    XboxLiveToken authorize(String xboxLiveToken) throws MicrosoftAuthException {
        JsonObject properties = new JsonObject();
        properties.addProperty("SandboxId", "RETAIL");
        com.google.gson.JsonArray userTokens = new com.google.gson.JsonArray();
        userTokens.add(xboxLiveToken);
        properties.add("UserTokens", userTokens);

        JsonObject payload = new JsonObject();
        payload.add("Properties", properties);
        payload.addProperty("RelyingParty", RELYING_PARTY);
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
            throw new MicrosoftAuthException("Impossible de contacter le service d'autorisation Xbox (XSTS) : " + e.getMessage(), e);
        }

        JsonObject root;
        try {
            root = JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new MicrosoftAuthException("Réponse XSTS illisible.", e);
        }

        if (response.statusCode() == 401 && root.has("XErr")) {
            throw new MicrosoftAuthException(describeXErr(root.get("XErr").getAsLong()));
        }
        if (response.statusCode() != 200) {
            throw new MicrosoftAuthException("Le service d'autorisation Xbox a refusé la connexion (code " + response.statusCode() + ").");
        }

        return XboxLiveClient.parse(response.body());
    }

    /**
     * Codes documentés publiquement par la communauté des développeurs de launchers tiers
     * (aucune documentation officielle Microsoft ne les liste, mais leur signification est
     * stable et largement vérifiée en pratique) — voir docs/authentication.md pour les sources.
     */
    private static String describeXErr(long xErr) {
        // switch ne supporte pas `long` en Java, et ces codes dépassent Integer.MAX_VALUE.
        if (xErr == 2148916233L) {
            return "Ce compte Microsoft n'a pas de profil Xbox. Créez-en un sur "
                + "https://www.xbox.com/live puis réessayez.";
        }
        if (xErr == 2148916235L) {
            return "Xbox Live n'est pas disponible dans le pays associé à ce compte Microsoft.";
        }
        if (xErr == 2148916236L || xErr == 2148916237L) {
            return "Ce compte doit d'abord valider son âge (vérification adulte requise) "
                + "sur xbox.com avant de pouvoir se connecter ici.";
        }
        if (xErr == 2148916238L) {
            return "Ce compte est un compte enfant : un adulte doit l'ajouter à une famille Microsoft "
                + "et accorder la permission de jouer en ligne avant de pouvoir se connecter ici.";
        }
        return "Xbox Live a refusé ce compte (code XErr " + xErr + ").";
    }
}
