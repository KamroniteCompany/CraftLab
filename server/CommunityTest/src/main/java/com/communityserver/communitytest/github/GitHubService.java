package com.communityserver.communitytest.github;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Traduit les réponses brutes de GitHubClient en objets typés du domaine GitHub
 * (GitHubRepository, GitHubRelease, GitHubAsset, CommunityModMetadata). Un petit cache en
 * mémoire à durée de vie fixe évite de refaire deux fois le même appel réseau lors d'une
 * seule opération d'import — volontairement simple : pas d'éviction, juste un TTL vérifié à
 * la lecture.
 */
public class GitHubService {

    private static final Gson GSON = new GsonBuilder().create();
    private static final long CACHE_TTL_MILLIS = 60_000L;

    private final GitHubClient client;
    private final Map<String, CacheEntry<GitHubRepository>> repositoryCache = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry<List<GitHubRelease>>> releasesCache = new ConcurrentHashMap<>();

    public GitHubService(GitHubClient client) {
        this.client = client;
    }

    public CompletableFuture<GitHubRepository> fetchRepository(String owner, String repo) {
        String key = owner + "/" + repo;
        GitHubRepository cached = getFresh(repositoryCache, key);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }

        return client.getApi("/repos/" + owner + "/" + repo)
            .thenApply(body -> {
                GitHubRepository repository = parseRepository(body);
                repositoryCache.put(key, new CacheEntry<>(repository, System.currentTimeMillis()));
                return repository;
            });
    }

    public CompletableFuture<List<GitHubRelease>> fetchReleases(String owner, String repo) {
        String key = owner + "/" + repo;
        List<GitHubRelease> cached = getFresh(releasesCache, key);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }

        return client.getApi("/repos/" + owner + "/" + repo + "/releases")
            .thenApply(body -> {
                List<GitHubRelease> releases = parseReleases(body);
                releasesCache.put(key, new CacheEntry<>(releases, System.currentTimeMillis()));
                return releases;
            });
    }

    /** Récupère et parse community-mod.json à la racine du dépôt, via l'API Contents officielle
     *  (pas de scraping) : GET /repos/{owner}/{repo}/contents/community-mod.json. */
    public CompletableFuture<CommunityModMetadata> fetchCommunityModMetadata(String owner, String repo) {
        return client.getApi("/repos/" + owner + "/" + repo + "/contents/community-mod.json")
            .thenApply(this::decodeFileContent)
            .thenApply(this::parseMetadata);
    }

    private String decodeFileContent(String body) {
        try {
            JsonObject obj = GSON.fromJson(body, JsonObject.class);
            if (obj == null || !obj.has("content")) {
                throw new GitHubException(GitHubException.Reason.INVALID_CONTENT, "Réponse GitHub sans contenu de fichier exploitable.");
            }
            String encoded = obj.get("content").getAsString().replace("\n", "");
            byte[] decoded = Base64.getDecoder().decode(encoded);
            return new String(decoded, StandardCharsets.UTF_8);
        } catch (JsonParseException | IllegalArgumentException e) {
            throw new GitHubException(GitHubException.Reason.INVALID_CONTENT, "Contenu de fichier GitHub illisible.");
        }
    }

    private CommunityModMetadata parseMetadata(String json) {
        JsonObject obj;
        try {
            obj = GSON.fromJson(json, JsonObject.class);
        } catch (JsonParseException e) {
            throw new GitHubException(GitHubException.Reason.INVALID_CONTENT, "community-mod.json n'est pas un JSON valide.");
        }
        if (obj == null) {
            throw new GitHubException(GitHubException.Reason.INVALID_CONTENT, "community-mod.json est vide.");
        }

        return new CommunityModMetadata(
            requireField(obj, "modId"),
            requireField(obj, "name"),
            requireField(obj, "author"),
            requireField(obj, "description"),
            requireField(obj, "minecraftVersion"),
            requireField(obj, "loader")
        );
    }

    private String requireField(JsonObject obj, String field) {
        if (!obj.has(field) || obj.get(field).isJsonNull()) {
            throw new GitHubException(GitHubException.Reason.INVALID_CONTENT, "Champ obligatoire manquant dans community-mod.json : " + field);
        }
        String value = obj.get(field).getAsString();
        if (value.isBlank()) {
            throw new GitHubException(GitHubException.Reason.INVALID_CONTENT, "Champ vide dans community-mod.json : " + field);
        }
        return value;
    }

    private GitHubRepository parseRepository(String body) {
        try {
            JsonObject obj = GSON.fromJson(body, JsonObject.class);
            String owner = obj.getAsJsonObject("owner").get("login").getAsString();
            String name = obj.get("name").getAsString();
            String url = obj.get("html_url").getAsString();
            String description = obj.has("description") && !obj.get("description").isJsonNull()
                ? obj.get("description").getAsString() : null;
            String homepage = obj.has("homepage") && !obj.get("homepage").isJsonNull() && !obj.get("homepage").getAsString().isBlank()
                ? obj.get("homepage").getAsString() : null;
            int stars = obj.has("stargazers_count") ? obj.get("stargazers_count").getAsInt() : 0;
            String updatedAt = obj.has("updated_at") && !obj.get("updated_at").isJsonNull()
                ? obj.get("updated_at").getAsString() : null;

            return new GitHubRepository(owner, name, url, description, homepage, stars, updatedAt);
        } catch (JsonParseException | NullPointerException | IllegalStateException e) {
            throw new GitHubException(GitHubException.Reason.INVALID_CONTENT, "Réponse GitHub illisible pour ce repository.");
        }
    }

    private List<GitHubRelease> parseReleases(String body) {
        List<GitHubRelease> releases = new ArrayList<>();
        try {
            JsonArray array = GSON.fromJson(body, JsonArray.class);
            if (array == null) {
                return releases;
            }
            for (JsonElement element : array) {
                releases.add(parseRelease(element.getAsJsonObject()));
            }
            return releases;
        } catch (JsonParseException | IllegalStateException e) {
            throw new GitHubException(GitHubException.Reason.INVALID_CONTENT, "Liste de releases GitHub illisible.");
        }
    }

    private GitHubRelease parseRelease(JsonObject obj) {
        long id = obj.get("id").getAsLong();
        String tag = obj.has("tag_name") && !obj.get("tag_name").isJsonNull() ? obj.get("tag_name").getAsString() : "";
        String name = obj.has("name") && !obj.get("name").isJsonNull() ? obj.get("name").getAsString() : tag;
        String description = obj.has("body") && !obj.get("body").isJsonNull() ? obj.get("body").getAsString() : "";
        String publishedAt = obj.has("published_at") && !obj.get("published_at").isJsonNull() ? obj.get("published_at").getAsString() : null;
        boolean draft = obj.has("draft") && obj.get("draft").getAsBoolean();
        boolean prerelease = obj.has("prerelease") && obj.get("prerelease").getAsBoolean();
        String htmlUrl = obj.has("html_url") && !obj.get("html_url").isJsonNull() ? obj.get("html_url").getAsString() : "";

        List<GitHubAsset> assets = new ArrayList<>();
        if (obj.has("assets") && obj.get("assets").isJsonArray()) {
            for (JsonElement assetElement : obj.getAsJsonArray("assets")) {
                assets.add(parseAsset(assetElement.getAsJsonObject()));
            }
        }

        return new GitHubRelease(id, tag, name, description, publishedAt, draft, prerelease, htmlUrl, assets);
    }

    private GitHubAsset parseAsset(JsonObject obj) {
        String name = obj.get("name").getAsString();
        long size = obj.has("size") ? obj.get("size").getAsLong() : 0L;
        String contentType = obj.has("content_type") && !obj.get("content_type").isJsonNull()
            ? obj.get("content_type").getAsString() : null;
        String downloadUrl = obj.get("browser_download_url").getAsString();

        String sha256Digest = null;
        if (obj.has("digest") && !obj.get("digest").isJsonNull()) {
            String digest = obj.get("digest").getAsString();
            if (digest.startsWith("sha256:")) {
                sha256Digest = digest.substring("sha256:".length());
            }
        }

        return new GitHubAsset(name, size, contentType, downloadUrl, sha256Digest);
    }

    private <T> T getFresh(Map<String, CacheEntry<T>> cache, String key) {
        CacheEntry<T> entry = cache.get(key);
        if (entry != null && System.currentTimeMillis() - entry.timestamp() < CACHE_TTL_MILLIS) {
            return entry.value();
        }
        return null;
    }

    private record CacheEntry<T>(T value, long timestamp) {
    }
}
