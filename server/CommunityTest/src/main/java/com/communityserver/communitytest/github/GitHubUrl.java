package com.communityserver.communitytest.github;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extrait proprement owner/repository depuis une URL GitHub de la forme
 * https://github.com/owner/repository (avec ou sans slash final, avec ou sans .git).
 * Toute autre forme (chemin supplémentaire, autre domaine, requête) est refusée proprement.
 */
public record GitHubUrl(String owner, String repository) {

    private static final Pattern PATTERN = Pattern.compile(
        "^https?://github\\.com/([A-Za-z0-9-]+)/([A-Za-z0-9._-]+?)(?:\\.git)?/?$"
    );

    public static Optional<GitHubUrl> parse(String url) {
        if (url == null) {
            return Optional.empty();
        }
        Matcher matcher = PATTERN.matcher(url.trim());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(new GitHubUrl(matcher.group(1), matcher.group(2)));
    }
}
