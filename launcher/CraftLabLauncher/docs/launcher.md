# CraftLab Launcher

Launcher desktop (JavaFX, Java 21) qui reproduit localement le ModPack officiel du serveur
CraftLab et lance Minecraft/Forge en conséquence. Projet Gradle totalement séparé du code
serveur (`CraftLab/launcher/`), aucun fichier serveur n'a été déplacé.

## Pourquoi JavaFX

Minecraft/Forge s'exécute obligatoirement sur une JVM, quel que soit le langage du launcher.
Écrire le launcher en Java élimine tout problème de "quel Java utiliser pour lancer le jeu" :
`ProcessHandle.current().info().command()` donne directement l'exécutable de la JVM du
launcher, réutilisé tel quel pour exécuter l'installeur Forge et pour lancer le jeu. Le reste
du projet (serveur, Gson, `HttpClient`, SHA-256 via `MessageDigest`) est déjà en Java : le
launcher réutilise les mêmes patterns plutôt que d'introduire un second écosystème (ex.
Rust/Tauri) sans bénéfice fonctionnel réel. `jpackage` (inclus dans le JDK) produit un vrai
`.exe`/installeur Windows avec JRE embarqué.

## Architecture

```
ModPackProvider (interface)
├── HttpModPackProvider     — URL HTTPS
└── LocalFileModPackProvider — fichier local, pour les tests

SyncManager
├── ModPackComparator  — compare RemoteModPack (serveur) à InstalledModPack (local)
├── ModDownloader      — HTTPS uniquement, .part temporaire, taille bornée, SHA-256
└── InstalledModPackStorage — instances/craftlab/installed-modpack.json

ForgeInstaller — télécharge et exécute l'installeur OFFICIEL de Forge (--installClient)

VersionManifestResolver — lit (sans rien télécharger) le profil de version que Forge a produit,
fusionne enfant + parent vanilla (inheritsFrom), construit classpath + arguments

MinecraftLauncher — résout les ${...}, construit la commande, lance le sous-processus java
ServerConnectionTarget — adresse/port du serveur à rejoindre automatiquement (voir plus bas)

AuthProvider (interface)
└── OfflineAuthProvider — UUID dérivé du pseudo, aucun jeton réel (voir "Authentification" ci-dessous)
```

## Comment le launcher récupère le ModPack

Le serveur CraftLab produit déjà `current-modpack.json` (CURRENT), mais ce format ne
contient pas d'URL de téléchargement par mod (elle vit séparément dans le `ModRegistry`,
via `ModDefinition.release`). Une petite addition côté serveur
(`LauncherModPackExporter`, appelée à chaque promotion `NEXT → CURRENT`, ou manuellement via
`/modpack export`) écrit `config/craftlabcore/modpack/current-modpack-launcher.json` : une
**projection** de CURRENT enrichie de cette seule information manquante — pas un second
format parallèle.

Pour cette première version, ce fichier doit être servi tel quel (n'importe quel serveur de
fichiers statique HTTPS : GitHub raw, nginx, etc.) ou utilisé en local via
`modpack_url=file:...` dans `launcher.properties`. `ModPackProvider` est une interface :
remplacer cette source par une vraie API CraftLab plus tard ne touchera qu'une nouvelle
implémentation, jamais le reste du launcher.

## Préparation de Minecraft vanilla

`MinecraftRuntimeManager` télécharge Minecraft 1.21.1 directement depuis l'API publique de
Mojang (`piston-meta`) : manifest de versions → JSON de version → client `.jar` → bibliothèques
→ index et objets d'assets. Aucune dépendance à un launcher officiel préalablement lancé —
tout est écrit dans `instances/craftlab/` (jamais dans le `.minecraft` de l'utilisateur), avec
vérification SHA-1 (le format fourni par Mojang) à chaque téléchargement.

## Installation de Forge — pourquoi pas `--installClient`

Le mode `java -jar forge-installer.jar --installClient <dir>` a été essayé en premier, mais il
échoue systématiquement sur une instance synthétique avec l'erreur *"There is no minecraft
launcher profile in ..., you need to run the launcher first!"*. Ce mode ne se contente pas de
préparer les fichiers : il tente aussi d'enregistrer un profil dans `launcher_profiles.json`
pour le launcher Mojang officiel, et **exige que ce fichier existe déjà** — ce qui suppose que
le launcher officiel ait été lancé au moins une fois sur ce dossier. Une instance CraftLab
créée de toutes pièces ne remplit jamais cette condition.

La solution retenue n'est pas de créer artificiellement ce fichier, mais de **ne jamais
invoquer ce mode du tout**. Le `.jar` installeur est une simple archive zip contenant :

- `install_profile.json` — la liste des étapes ("processors") à exécuter ;
- `version.json` — le profil de version Forge final (le même que `--installClient` aurait
  écrit) ;
- un dossier `maven/` — les bibliothèques Forge déjà empaquetées.

`ForgeInstaller` extrait ces éléments et **exécute lui-même les processors** (chaque étape est
un outil Java invoqué en `java -cp <classpath résolu> <classe principale> <arguments
résolus>`, exactement comme le ferait l'installeur en interne) — c'est le même mécanisme que
celui utilisé par les launchers tiers établis (MultiMC, Prism Launcher, etc.) pour la même
raison. Aucun `launcher_profiles.json` n'est jamais créé, lu, ni requis.

**Résolution des valeurs "data"** — `install_profile.json` contient une section `data` avec
des tokens comme `BINPATCH`, `MAPPINGS`, etc. Chaque valeur "client" prend l'une de 3 formes,
confirmées contre le comportement réel de l'installeur officiel :

1. `'texte'` (guillemets simples) → chaîne littérale, guillemets retirés.
2. `[groupe:artefact:version[:classifieur]@ext]` → coordonnée Maven, résolue vers son chemin
   réel dans `libraries/`.
3. **Toute autre valeur** (ex. `/data/client.lzma`) → chemin **à l'intérieur du jar installeur
   lui-même**, qui doit être extrait vers un vrai fichier sur disque
   (`staging/installer-data/...`) avant de pouvoir être passé en argument à un outil externe.

C'est ce 3ème cas (les patches binaires spécifiques à chaque build de Forge, embarqués sous
`data/` à la racine du jar, pas dans `maven/`) qui manquait initialement et provoquait
l'échec de l'étape `binarypatcher` avec `FileNotFoundException: \data\client.lzma` — la
valeur littérale `/data/client.lzma` était passée telle quelle, et Windows l'interprète comme
un chemin à la racine du lecteur courant.

**Limite honnête** : cette implémentation suit le format `install_profile.json` tel que connu
et stable depuis plusieurs générations de Forge, mais n'a pas pu être testée contre le vrai
`forge-1.21.1-52.1.0-installer.jar` dans cet environnement de développement (pas d'accès à
Maven Central ni à un Windows). Si une étape échoue avec un nom de processor inattendu, la
première chose à vérifier est le contenu réel de l'installeur :
```
unzip -p forge-installer.jar install_profile.json
```

## Lecture du profil de version

`VersionManifestResolver` lit `instances/craftlab/versions/<id>/<id>.json`, remonte à son
parent vanilla (`inheritsFrom`), fusionne bibliothèques et arguments des deux, et filtre
les règles `os.name` (Windows/macOS/Linux). Pour les versions modernes (1.19+), les
bibliothèques natives LWJGL sont de simples JAR de classpath — aucune extraction de natives
n'est nécessaire ; `${natives_directory}` pointe par précaution vers un dossier vide créé à
cet effet.

## Pas de connexion automatique — l'écran CraftLab décide

Le launcher démarre Minecraft Forge et s'arrête là : il ne rejoint **jamais** le serveur à la
place du joueur. C'est l'écran d'accueil CraftLab, côté mod client (`CraftLabTitleScreen`, voir
`docs/client-title-screen.md` dans `server/CraftLabCore`), qui déclenche la connexion —
uniquement quand le joueur clique sur "Jouer".

```
CraftLab Launcher
      ↓
Minecraft Forge démarre (aucune connexion automatique)
      ↓
CraftLabTitleScreen (Jouer / Mods / Paramètres / Quitter)
      ↓
[ Jouer ]
      ↓
Connexion au serveur CraftLab
```

**Historique — pourquoi pas `--server`/`--port` ni Quick Play.** `--server <ip>` / `--port
<port>` (le mécanisme de "connexion directe" des très anciens launchers) n'existe plus dans les
arguments déclarés par le profil 1.21.1 — Minecraft les ignore silencieusement :
```
[main/INFO] [minecraft/Main]: Completely ignored arguments: [--server, localhost, --port, 25565]
```
Une version précédente utilisait donc **Quick Play** (`--quickPlayMultiplayer`, introduit en
1.20) pour connecter automatiquement le joueur dès le démarrage. Problème découvert ensuite :
quand Quick Play réussit, Minecraft **ne crée jamais d'écran-titre du tout** (vérifié dans les
sources Mojang, `Minecraft.<init>` → `buildInitialScreens` : la branche Quick Play appelle
`QuickPlay.connect(...)` au lieu de `setScreen(new TitleScreen(true))`) — l'écran CraftLab
n'avait donc jamais l'occasion de s'afficher, empêchant l'accès à Mods/Paramètres/Quitter avant
de rejoindre le serveur. C'est précisément le comportement qu'on ne veut plus.

**État actuel.** `MinecraftLauncher.ENABLED_FEATURES` n'active plus que
`has_custom_resolution` — ni `has_quick_plays_support` ni `is_quick_play_multiplayer` — donc
`VersionManifestResolver` ne construit plus jamais `--quickPlayMultiplayer`/`--quickPlayPath`
(aucun `${...}` orphelin à résoudre : ces arguments sont entièrement retirés de la commande,
pas laissés avec une valeur vide). Minecraft démarre donc toujours sur un `TitleScreen`
classique, que le mod client remplace par `CraftLabTitleScreen` avant toute connexion.

L'abstraction Quick Play n'a pas été supprimée du projet : `QUICK_PLAY_FEATURES` (constante non
utilisée, avec instructions de réactivation en commentaire) et
`ServerConnectionTarget.toQuickPlayAddress()` restent disponibles pour une éventuelle
réutilisation future, sans être exercés par le chemin de lancement normal.

**`server_address` / `server_port`** (dans `launcher.properties`, déjà existants) continuent de
piloter la cible — mais transitent maintenant vers le client via deux propriétés système
ajoutées à la commande, `-Dcraftlab.server.address=<adresse>` et
`-Dcraftlab.server.port=<port>`, lues par `ServerTarget` côté mod quand le joueur clique sur
"Jouer". Aucune valeur codée en dur, ni côté launcher ni côté client.

**Serveur indisponible** — le launcher ne peut plus jamais bloquer sur ce point puisqu'il ne
tente lui-même aucune connexion : Minecraft affiche normalement l'écran CraftLab, et c'est
seulement au clic sur "Jouer" que `ConnectScreen.startConnecting(...)` (mécanisme vanilla, avec
son propre timeout réseau) peut échouer proprement — voir `docs/client-title-screen.md`.

Journal typique au lancement :
```
[CraftLab] Aucune connexion automatique : l'écran CraftLab s'affichera au démarrage.
[CraftLab] Serveur configuré (utilisé par le bouton "Jouer" en jeu) : localhost:25565
[CraftLab] ... (résolution du profil, classpath, arguments) ...
[CraftLab] Commande finale (...) : ... -Dcraftlab.server.address=localhost -Dcraftlab.server.port=25565 ...
[CraftLab] Minecraft process started. (pid ...)
[CraftLab] [Minecraft] ... [CraftLabTitleScreen]: [CraftLab] Écran CraftLab chargé.
```

**Évolution vers plusieurs serveurs** — `ServerConnectionTarget` isole déjà "quel serveur
rejoindre" de "comment construire la commande de lancement". Ajouter un choix de serveur
(2, 3, ...) reviendra à produire une instance différente de ce type (ex. depuis un profil
sélectionné dans l'IHM) sans toucher à `MinecraftLauncher`.

## Cache et retrait d'un mod

`downloads/<modId>/<version>/<assetName>.jar` sert de cache, jamais purgé automatiquement.
Un mod retiré du ModPack distant est retiré de `instances/craftlab/mods/` (le dossier actif
que Forge charge), mais son fichier en cache reste disponible : le réintroduire plus tard ne
retélécharge rien si le SHA-256 correspond toujours.

## SHA-256 et téléchargement sécurisé

Même stratégie que côté serveur (`ModDownloadManager`) : HTTPS uniquement, écriture dans un
fichier temporaire `.part`, taille bornée **pendant** le flux (pas seulement vérifiée après
coup, via un `BodySubscriber` personnalisé), SHA-256 recalculé et comparé avant tout
déplacement vers l'emplacement final. Un fichier partiellement téléchargé ne peut jamais être
pris pour un fichier valide.

## Authentification

Cette version utilise une authentification **hors-ligne** (`OfflineAuthProvider`) : un UUID
dérivé du pseudo, sans jeton réel. Cela ne fonctionne que si le serveur CraftLab tourne avec
`online-mode=false` dans son `server.properties` — c'est une configuration côté serveur,
non gérée par ce launcher. Une authentification Microsoft complète (device code flow, jeton
Xbox Live, jeton Minecraft) demanderait d'implémenter ce flux OAuth et d'échanger les jetons
successifs ; elle est **isolée derrière `AuthProvider`** pour pouvoir être ajoutée plus tard
sans toucher au reste du launcher. Aucun mot de passe Microsoft ne doit jamais transiter par
cette application.

## Limitations actuelles (volontaires)

- Un seul profil ("craftlab"), pas de gestion multi-serveurs.
- Pas d'authentification Microsoft, pas de reprise de téléchargement par morceaux après
  interruption (on repart proprement du fichier `.part`, supprimé et retéléchargé).
- Pas de mise à jour automatique du launcher lui-même.
- Le launcher ne modifie jamais le serveur CraftLab : lecture seule de `ModPackProvider`.

## Build et lancement en développement

Java 21 requis.

```powershell
cd launcher/CraftLabLauncher
gradlew.bat run
```

## Build

```powershell
cd launcher/CraftLabLauncher
gradlew.bat clean build
```

## Build de distribution

```bash
./gradlew build
./gradlew installDist
```
`installDist` produit `build/install/craftlab-launcher/` avec toutes les dépendances
(JavaFX, Gson) et un script de lancement — c'est la base pour `jpackage`.

## Répertoire de l'instance, configuration locale, fichiers non suivis

Rien de ce qui est propre à une machine ou généré à l'exécution n'est versionné :

- **Instance Minecraft/Forge** (`instances/craftlab/`), **cache de téléchargement**
  (`downloads/`) et **logs** (`logs/`) : jamais dans le dépôt — le launcher les écrit et les
  régénère lui-même au premier lancement, sous `%APPDATA%\CraftLabLauncher\` (ou
  `~/.craftlab-launcher`).
- **`launcher.properties`** (source du ModPack, adresse/port du serveur, pseudo,
  résolution) : généré avec des valeurs par défaut au premier lancement dans ce même
  répertoire (voir `LauncherConfig`) — jamais dans le dépôt, à modifier localement selon ton
  serveur.
- **`current-modpack-launcher.json`** (à la racine de ce projet) est la seule exception :
  c'est un exemple versionné, avec des valeurs génériques (`REMPLACE_PAR_LE_VRAI_SHA256_DU_JAR`,
  URL GitHub placeholder), utilisé par `modpack_url=file:./current-modpack-launcher.json` pour
  tester sans dépendre d'un serveur HTTP réel. Adapte-le localement avec de vraies valeurs si tu
  veux t'en servir ; ne commit pas de vraies valeurs propres à ton déploiement dedans.
- **`build/`, `.gradle/`** : sorties Gradle, régénérées par `gradlew.bat build` — ignorées par
  Git (voir `.gitignore` à la racine du dépôt).

## Procédure d'installation Windows

```powershell
cd launcher/CraftLabLauncher
./gradlew installDist
jpackage `
  --type exe `
  --name "CraftLab Launcher" `
  --app-version 0.1.0 `
  --input build/install/craftlab-launcher/lib `
  --main-jar craftlab-launcher.jar `
  --main-class com.craftlab.launcher.CraftLabLauncherApp `
  --dest build/dist `
  --win-shortcut `
  --win-menu
```
`jpackage` (inclus dans le JDK 21) embarque un JRE minimal : l'utilisateur final n'a pas
besoin d'installer Java. La commande exacte peut nécessiter un ajustement selon la
disposition réelle des JAR générés par `installDist` sur ta machine — vérifie
`build/install/craftlab-launcher/lib` avant de lancer `jpackage`.

## Procédure de test complète

Prérequis : un `current-modpack-launcher.json` de test (fourni à la racine du projet, à
adapter avec de vraies valeurs — voir `BlankMod` du tour précédent pour un mod réel à
héberger sur GitHub) et `modpack_url=file:./current-modpack-launcher.json` dans
`launcher.properties` (généré au premier lancement dans `%APPDATA%\CraftLabLauncher\`).

**Test 1 — Installation propre**
Supprime `%APPDATA%\CraftLabLauncher` (ou `~/.craftlab-launcher`) s'il existe, lance
`./gradlew run`. Attendu : téléchargement de l'installeur Forge, exécution de
`--installClient`, apparition du fichier `instances/craftlab/versions/1.21.1-forge-52.1.0/
1.21.1-forge-52.1.0.json`, mods du ModPack téléchargés dans `downloads/`, copiés dans
`instances/craftlab/mods/`.

**Test 2 — Déjà à jour**
Relance `./gradlew run` sans rien changer. Attendu (journal) : "déjà à jour" pour chaque
mod, aucun nouveau téléchargement.

**Test 3 — Nouveau mod**
Ajoute une entrée dans `current-modpack-launcher.json`, relance. Attendu : seul ce mod est
téléchargé.

**Test 4 — Nouvelle version**
Change la `version`/`sha256`/`downloadUrl` d'un mod existant dans le fichier de test.
Attendu : mise à jour détectée, nouveau téléchargement, ancienne version conservée dans
`downloads/<modId>/<ancienne-version>/`.

**Test 5 — Mauvais SHA-256**
Modifie un octet d'un fichier dans `downloads/<modId>/<version>/`, relance. Attendu :
fichier détecté invalide (`isAlreadyValid` retourne faux), retéléchargement.

**Test 6 — Mod retiré**
Retire une entrée du fichier de test, relance. Attendu (journal) : "n'est plus requis,
retiré de l'installation active" ; vérifie que le fichier a disparu de
`instances/craftlab/mods/` mais existe toujours dans `downloads/`.

**Test 7 — Réseau indisponible**
Coupe le réseau (ou pointe `downloadUrl` vers une URL invalide), relance. Attendu : message
d'erreur clair dans le statut et le journal, aucun fichier `.part` résiduel dans
`downloads/`.

**Test 8 — Lancement**
Une fois `[ Jouer ]` activé, clique dessus. Attendu (journal) : ligne "Lancement : ...", un
processus `java` démarre (vérifiable via le gestionnaire de tâches), Minecraft s'ouvre.

**Test 9 — Connexion depuis l'écran CraftLab, serveur disponible**
Démarre le serveur CraftLab (`server_address`/`server_port` de `launcher.properties`), lance le
launcher. Minecraft démarre sur `CraftLabTitleScreen` **sans connexion automatique** (voir
"Pas de connexion automatique" ci-dessus) ; clique toi-même sur le bouton **Jouer** de cet
écran. Attendu : `ConnectScreen.startConnecting(...)` se déclenche (log `ConnectScreen:
Connecting to ...` puis `Connected to a modded server.`), sans jamais passer par l'écran
Multijoueur vanilla.

**Test 10 — Connexion depuis l'écran CraftLab, serveur indisponible**
Arrête le serveur (ou pointe `server_port` vers un port fermé), clique **Jouer** sur
`CraftLabTitleScreen`. Attendu : Minecraft affiche `Couldn't connect to server` proprement
(mécanisme vanilla, avec son propre timeout), sans jamais rester bloqué — et sans que le
launcher lui-même n'ait tenté quoi que ce soit.

**Test 11 — Adresse configurable**
Change `server_address`/`server_port` dans `launcher.properties`, relance, vérifie dans le
journal que `Serveur configuré (utilisé par le bouton "Jouer" en jeu) : ...` reflète bien les
nouvelles valeurs, et que la commande finale contient bien
`-Dcraftlab.server.address=<adresse> -Dcraftlab.server.port=<port>` à jour (pas
`--quickPlayMultiplayer`, retiré depuis que Quick Play a été abandonné — voir ci-dessus).
