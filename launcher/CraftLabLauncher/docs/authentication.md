# Authentification Microsoft — architecture et flux

## Pourquoi ce document existe

Ne réutilise pas aveuglément un ancien tutoriel : le flux Mojang "auth legacy" n'existe plus,
et Microsoft/Mojang ont changé plusieurs fois le détail exact des endpoints. Ce document décrit
le flux vérifié valide au moment de l'implémentation (2026-09-01), avec les sources pour chaque
étape, pour qu'une future modification puisse être comparée à un état de référence explicite
plutôt que redécouverte de zéro.

## Architecture

```
AuthProvider (interface, inchangée)
├── OfflineAuthProvider   — inchangé, toujours le fournisseur actif par défaut
└── MicrosoftAuthProvider — nouveau, orchestre les classes ci-dessous
        ├── MicrosoftOAuthClient     (étape 1 : Microsoft Identity / Entra ID)
        ├── LoopbackRedirectServer   (capture la redirection du navigateur)
        ├── PkceUtil                 (code_verifier / code_challenge)
        ├── XboxLiveClient           (étape 2 : Xbox Live)
        ├── XstsClient               (étape 3 : XSTS)
        ├── MinecraftServicesClient  (étapes 4-5 : jeton Minecraft + profil)
        └── MicrosoftTokenStore      (persistance chiffrée DPAPI du refresh token)
```

Le reste du launcher (`MinecraftLauncher`, `CraftLabLauncherApp`) ne dépend que de
`AuthProvider`/`AuthSession` — jamais d'un type spécifique à Microsoft. Aucun fichier en dehors
de `com.craftlab.launcher.auth.microsoft` n'a été modifié pour cette fonctionnalité.

## Le flux exact, étape par étape

### 1. Microsoft Identity (Entra ID) — obtention d'un jeton Microsoft

**Flux choisi : Authorization Code + PKCE, avec redirection "loopback" (`http://127.0.0.1:PORT/callback`)**,
pas le device code flow. C'est le flux que Microsoft documente comme recommandé pour un client
"public" desktop qui peut ouvrir un navigateur :
https://learn.microsoft.com/en-us/entra/identity-platform/scenario-desktop-acquire-token

Le device code flow (coller un code sur microsoft.com/link) reste supporté mais est positionné
par Microsoft pour les appareils à saisie limitée (TV, consoles sans clavier) — pas l'usage visé
ici, qui veut un retour automatique au launcher sans recopie manuelle.

Séquence réelle :
1. Le launcher génère `code_verifier` (aléatoire) et `code_challenge = base64url(SHA256(code_verifier))`
   (voir `PkceUtil`).
2. Le launcher démarre un serveur HTTP local sur `127.0.0.1:<port éphémère>` (voir
   `LoopbackRedirectServer`) — jamais accessible depuis le réseau.
3. Le launcher ouvre le **navigateur système par défaut** (jamais un composant web embarqué
   dans l'application) sur :
   ```
   https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize
     ?client_id={CLIENT_ID}
     &response_type=code
     &redirect_uri=http://127.0.0.1:{PORT}/callback
     &scope=XboxLive.signin offline_access
     &code_challenge={CHALLENGE}
     &code_challenge_method=S256
     &state={valeur aléatoire}
   ```
   Tenant **`consumers`** (pas `common` ni `organizations`) : les comptes Minecraft/Xbox sont
   des comptes Microsoft personnels, jamais professionnels/scolaires.
4. L'utilisateur se connecte sur la vraie page microsoftonline.com — mot de passe jamais visible
   par cette application, exactement comme n'importe quel autre logiciel utilisant OAuth
   correctement.
5. Microsoft redirige le navigateur vers `http://127.0.0.1:{PORT}/callback?code=...`, capturé
   par `LoopbackRedirectServer`.
6. Le launcher échange ce code contre des jetons :
   ```
   POST https://login.microsoftonline.com/consumers/oauth2/v2.0/token
   grant_type=authorization_code&code=...&redirect_uri=...&client_id=...&code_verifier=...
   ```
   Réponse : `access_token` (jeton Microsoft, PAS un jeton Minecraft), `refresh_token`,
   `expires_in`.

**Scope demandé : `XboxLive.signin offline_access`** — `XboxLive.signin` est le seul scope
nécessaire pour l'étape Xbox Live suivante ; `offline_access` est ce qui permet d'obtenir un
`refresh_token` (sans lui, l'utilisateur devrait se reconnecter à chaque lancement).

### 2. Xbox Live (XBL)

```
POST https://user.auth.xboxlive.com/user/authenticate
{
  "Properties": {"AuthMethod": "RPS", "SiteName": "user.auth.xboxlive.com", "RpsTicket": "d={jeton Microsoft}"},
  "RelyingParty": "http://auth.xboxlive.com",
  "TokenType": "JWT"
}
```
Réponse : `Token` (jeton Xbox Live) + `DisplayClaims.xui[0].uhs` (le "user hash", nécessaire à
l'étape suivante). Voir `XboxLiveClient`.

### 3. XSTS (Xbox Security Token Service)

```
POST https://xsts.auth.xboxlive.com/xsts/authorize
{
  "Properties": {"SandboxId": "RETAIL", "UserTokens": ["{jeton Xbox Live}"]},
  "RelyingParty": "rp://api.minecraftservices.com/",
  "TokenType": "JWT"
}
```
C'est **cette étape précisément** qui échoue avec un code `XErr` identifiable si le compte ne
peut structurellement pas jouer (voir `XstsClient.describeXErr`) :

| XErr | Signification | Message affiché |
|---|---|---|
| 2148916233 | Pas de profil Xbox associé au compte Microsoft | Invite à créer un profil sur xbox.com/live |
| 2148916235 | Xbox Live indisponible dans le pays du compte | Message informatif |
| 2148916236 / 2148916237 | Vérification d'âge requise (majorité) | Invite à la faire sur xbox.com |
| 2148916238 | Compte enfant sans accord parental | Invite un adulte à l'ajouter à une famille Microsoft |

Ces codes ne sont pas documentés officiellement par Microsoft, mais leur signification est
stable et vérifiée par la communauté des développeurs de launchers tiers depuis plusieurs
années — comportement volontairement isolé dans une seule méthode pour être facilement
réévalué si jamais un cas ne correspondait plus.

### 4. Minecraft Services — obtention du jeton Minecraft

```
POST https://api.minecraftservices.com/authentication/login_with_xbox
{"identityToken": "XBL3.0 x={user hash};{jeton XSTS}"}
```
Réponse : `access_token` — **c'est ce jeton, et uniquement celui-ci, qui est transmis à
Minecraft** (`${auth_access_token}`). Les jetons Microsoft/Xbox/XSTS précédents ne servent
jamais après cette étape.

### 5. Récupération du profil Minecraft

```
GET https://api.minecraftservices.com/minecraft/profile
Authorization: Bearer {jeton Minecraft}
```
Réponse : `id` (UUID **sans tirets** — voir plus bas) et `name` (le pseudo réel du joueur,
jamais choisi localement). Un code 404 signifie que ce compte Microsoft ne possède pas
Minecraft: Java Edition — message explicite plutôt qu'une erreur générique.

## Identité finale transmise à Minecraft

```java
new AuthSession(
    profile.name(),          // ${auth_player_name}
    profile.id(),            // ${auth_uuid} — SANS tirets (vérifié : OfflineAuthProvider utilise
                              // déjà ce même format sans tirets, et l'API Minecraft Services
                              // renvoie nativement l'UUID sous cette forme — aucune conversion)
    minecraftAuth.accessToken(), // ${auth_access_token} ET ${auth_session} (format hérité)
    "msa"                     // ${user_type} — "msa" pour un vrai compte Microsoft, jamais "legacy"
)
```

`MinecraftLauncher` n'a **pas été modifié** : il consomme déjà `AuthSession` de façon générique
(voir `values.put("auth_uuid", session.uuid())` etc.), quel que soit le `AuthProvider` qui l'a
produit.

## Stockage sécurisé des jetons

**Seul le refresh token Microsoft (longue durée de vie) est persisté** — jamais les jetons
Microsoft/Xbox/XSTS/Minecraft à courte durée de vie. Pourquoi : ces derniers expirent en 1h à
24h et sont bon marché à réobtenir (quelques appels HTTPS rapides) ; les mettre en cache
introduirait de la complexité (suivre quatre expirations différentes) pour un bénéfice marginal.

Chiffrement au repos via **DPAPI** (Data Protection API Windows — le même mécanisme que Windows
Credential Manager utilise en interne), invoqué via PowerShell
(`ConvertTo-SecureString`/`ConvertFrom-SecureString` sans `-Key`, donc lié par Windows à
l'utilisateur + la machine courants) plutôt qu'une dépendance native (JNA/JNI). Le secret est
systématiquement transmis au sous-processus PowerShell par **stdin**, jamais en argument de
ligne de commande (qui apparaîtrait en clair dans la liste des processus). Voir
`MicrosoftTokenStore`.

Fichier : `%APPDATA%\CraftLabLauncher\msa-refresh-token.dat` — copié sur une autre machine ou
lu par un autre compte Windows, il ne peut plus être déchiffré (comportement DPAPI normal, pas
un bug).

## Lancements suivants (renouvellement silencieux)

```
CraftLab Launcher démarre
      ↓
Refresh token enregistré présent ?
      ↓ oui                              ↓ non
POST /token (grant_type=refresh_token)   Flux interactif complet (navigateur)
      ↓ succès         ↓ échec (expiré/révoqué)
Nouveau jeton Microsoft   Flux interactif complet (jamais une erreur fatale)
      ↓
Xbox Live → XSTS → Minecraft Services → profil (toujours refaits en entier : rapides, jamais mis en cache)
```

## Si l'authentification échoue

Toute étape de la chaîne qui échoue lève `MicrosoftAuthException` (message déjà présentable au
joueur), que `MicrosoftAuthProvider.authenticate()` reconvertit en
`AuthenticationFailedException` (non vérifiée). `CraftLabLauncherApp.runLaunch()` capture déjà
`Exception` autour de l'appel à `authenticate()` (voir son bloc try/catch existant) et affiche
le message dans le journal sans jamais planter — **aucune modification de
`CraftLabLauncherApp` n'a été nécessaire** pour que "message clair, pas de crash, possibilité de
réessayer" soit déjà vrai : cliquer de nouveau sur "Jouer" relance `authenticate()` depuis zéro.

## Ce qui manque pour une utilisation réelle (action manuelle requise)

**Une application Microsoft Entra ID doit être enregistrée** avant que quoi que ce soit ici ne
puisse fonctionner contre les vrais services Microsoft — impossible à faire par ce launcher lui-même :

1. https://portal.azure.com → "Inscriptions d'applications" → "Nouvelle inscription".
2. Type de compte : **"Comptes dans n'importe quel annuaire organisationnel et comptes
   Microsoft personnels"** (nécessaire pour les comptes Minecraft personnels).
3. Type de plateforme : **"Application mobile et de bureau"**, URI de redirection
   `http://127.0.0.1` (le port exact varie à chaque lancement — Microsoft accepte un préfixe
   loopback sans port fixe pour ce type de plateforme, voir la doc Microsoft citée plus haut).
4. Récupérer le "ID d'application (client)" généré — c'est le `clientId` attendu par
   `MicrosoftAuthProvider(clientId, craftLabRoot)`.
5. **Aucun secret client requis ni à créer** : un client "public" desktop ne peut pas garder de
   secret confidentiel (PKCE remplace ce rôle) — ne jamais générer ni committer de "client
   secret" pour cette inscription.

Ce `clientId` n'est pas un secret au sens strict (il est visible dans l'URL d'autorisation), mais
reste spécifique au déploiement CraftLab — à traiter comme n'importe quelle valeur de
configuration (voir `docs/versioning.md`/la configuration par défaut du launcher), jamais codé
en dur dans un exemple de test.

## Endpoints utilisés (résumé)

| Étape | Méthode | URL |
|---|---|---|
| Autorisation | GET (navigateur) | `https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize` |
| Échange / renouvellement | POST | `https://login.microsoftonline.com/consumers/oauth2/v2.0/token` |
| Xbox Live | POST | `https://user.auth.xboxlive.com/user/authenticate` |
| XSTS | POST | `https://xsts.auth.xboxlive.com/xsts/authorize` |
| Connexion Minecraft | POST | `https://api.minecraftservices.com/authentication/login_with_xbox` |
| Profil Minecraft | GET | `https://api.minecraftservices.com/minecraft/profile` |

## Ce qui n'a jamais été testé en conditions réelles (limitation assumée)

Sans inscription Azure réelle ni compte Microsoft de test, la vraie page de connexion Microsoft
et les vrais services (Xbox Live, XSTS, Minecraft Services) n'ont jamais été contactés — tous les
tests utilisent de vrais serveurs HTTP locaux répondant avec la forme exacte des réponses
documentées. Chaque classe cliente est néanmoins conçue pour être testée contre le vrai endpoint
en changeant uniquement le constructeur utilisé (voir `MicrosoftAuthProvider(String clientId,
Path craftLabRoot)`, le constructeur "réel" par défaut) — dès qu'un `clientId` réel existe, un
test manuel complet (voir `docs/player-installation-guide.md` pour le niveau de détail attendu
d'un tel test) reste nécessaire avant toute mise à disposition aux joueurs.
