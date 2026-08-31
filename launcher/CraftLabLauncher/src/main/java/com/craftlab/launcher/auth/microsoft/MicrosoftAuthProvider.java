package com.craftlab.launcher.auth.microsoft;

import com.craftlab.launcher.auth.AuthProvider;
import com.craftlab.launcher.auth.AuthSession;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Authentification Microsoft réelle pour Minecraft Java Edition — voir docs/authentication.md
 * pour le détail complet du flux (Microsoft Identity -> Xbox Live -> XSTS -> Minecraft Services
 * -> profil) et pourquoi chaque étape existe. N'implémente PAS de logique réseau elle-même :
 * orchestre les quatre clients dédiés, chacun responsable d'une seule étape.
 *
 * Le paramètre `username` de authenticate(String) (hérité de AuthProvider, partagé avec
 * OfflineAuthProvider où l'utilisateur choisit son pseudo) est ignoré ici : un compte Microsoft
 * réel a un pseudo Minecraft déjà défini côté serveur (le profil récupéré à la dernière étape),
 * jamais choisi localement. Conserver la même interface évite de faire dépendre le reste du
 * launcher (MinecraftLauncher, CraftLabLauncherApp) d'un type spécifique à Microsoft.
 */
public final class MicrosoftAuthProvider implements AuthProvider {

    private static final Duration BROWSER_LOGIN_TIMEOUT = Duration.ofMinutes(5);

    private final MicrosoftOAuthClient oauthClient;
    private final XboxLiveClient xboxLiveClient;
    private final XstsClient xstsClient;
    private final MinecraftServicesClient minecraftServicesClient;
    private final MicrosoftTokenStore tokenStore;
    private final Consumer<String> browserOpener;

    public MicrosoftAuthProvider(String clientId, Path craftLabRoot) {
        this(new MicrosoftOAuthClient(clientId), new XboxLiveClient(), new XstsClient(),
            new MinecraftServicesClient(), new MicrosoftTokenStore(craftLabRoot), MicrosoftAuthProvider::openSystemBrowser);
    }

    /** Pour les tests : chaque étape substituable indépendamment, sans jamais toucher un vrai service Microsoft. */
    MicrosoftAuthProvider(MicrosoftOAuthClient oauthClient, XboxLiveClient xboxLiveClient, XstsClient xstsClient,
                          MinecraftServicesClient minecraftServicesClient, MicrosoftTokenStore tokenStore,
                          Consumer<String> browserOpener) {
        this.oauthClient = oauthClient;
        this.xboxLiveClient = xboxLiveClient;
        this.xstsClient = xstsClient;
        this.minecraftServicesClient = minecraftServicesClient;
        this.tokenStore = tokenStore;
        this.browserOpener = browserOpener;
    }

    @Override
    public AuthSession authenticate(String usernameIgnored) {
        try {
            String microsoftAccessToken = obtainMicrosoftAccessToken();
            XboxLiveToken xboxLiveToken = xboxLiveClient.authenticate(microsoftAccessToken);
            XboxLiveToken xstsToken = xstsClient.authorize(xboxLiveToken.token());
            MinecraftAuthResult minecraftAuth = minecraftServicesClient.loginWithXbox(xstsToken.userHash(), xstsToken.token());
            MinecraftProfile profile = minecraftServicesClient.fetchProfile(minecraftAuth.accessToken());

            return new AuthSession(profile.name(), profile.id(), minecraftAuth.accessToken(), "msa");
        } catch (MicrosoftAuthException e) {
            throw new AuthenticationFailedException(e.getMessage(), e);
        }
    }

    /** Déconnexion explicite : la prochaine authentification redemandera obligatoirement une connexion interactive. */
    public void signOut() throws IOException {
        tokenStore.clear();
    }

    /**
     * Renouvellement silencieux d'abord (voir docs/authentication.md, "Lancements suivants") :
     * un refresh token enregistré et encore valide évite toute nouvelle fenêtre de connexion.
     * Un refresh qui échoue (jeton expiré/révoqué) n'est jamais fatal : retombe simplement sur
     * le flux interactif complet, exactement comme au tout premier lancement.
     */
    private String obtainMicrosoftAccessToken() throws MicrosoftAuthException {
        Optional<String> storedRefreshToken = tokenStore.load();
        if (storedRefreshToken.isPresent()) {
            try {
                MicrosoftTokenResponse refreshed = oauthClient.refresh(storedRefreshToken.get());
                tokenStore.store(refreshed.refreshToken());
                return refreshed.accessToken();
            } catch (MicrosoftAuthException ignored) {
                // Jeton de renouvellement invalide/expiré : on retombe sur le flux interactif ci-dessous.
            }
        }
        return runInteractiveLogin();
    }

    private String runInteractiveLogin() throws MicrosoftAuthException {
        String codeVerifier = PkceUtil.generateCodeVerifier();
        String codeChallenge = PkceUtil.codeChallenge(codeVerifier);
        String state = PkceUtil.generateCodeVerifier(); // valeur aléatoire opaque, réutilise le même générateur

        try (LoopbackRedirectServer server = LoopbackRedirectServer.start()) {
            String redirectUri = server.redirectUri();
            String authorizeUrl = oauthClient.buildAuthorizeUrl(redirectUri, codeChallenge, state);

            browserOpener.accept(authorizeUrl);
            String code = server.awaitAuthorizationCode(BROWSER_LOGIN_TIMEOUT);

            MicrosoftTokenResponse tokenResponse = oauthClient.exchangeAuthorizationCode(code, redirectUri, codeVerifier);
            tokenStore.store(tokenResponse.refreshToken());
            return tokenResponse.accessToken();
        } catch (IOException e) {
            throw new MicrosoftAuthException("Impossible de démarrer le serveur local de connexion Microsoft : " + e.getMessage(), e);
        }
    }

    /** Navigateur système par défaut — jamais un navigateur embarqué dans l'application (voir LoopbackRedirectServer). */
    private static void openSystemBrowser(String url) {
        try {
            java.awt.Desktop.getDesktop().browse(URI.create(url));
        } catch (Exception e) {
            throw new RuntimeException("Impossible d'ouvrir le navigateur système : " + e.getMessage(), e);
        }
    }
}
