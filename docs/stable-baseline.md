# CraftLab — Baseline stable

Référence figée de l'état stable du projet à la date ci-dessous. Un futur développeur (ou une
future session de travail) doit pouvoir cloner le dépôt et retrouver exactement cet état sans
connaître l'historique des sessions qui l'ont produit.

**Date de référence : 2026-08-31.**

## 1. Versions exactes

| Composant | Version | Source de vérité |
|---|---|---|
| Minecraft | 1.21.1 | `server/CraftLabCore/gradle.properties` (`minecraft_version`) |
| Forge | 52.1.0 | `server/CraftLabCore/gradle.properties` (`forge_version`) |
| Java (dev/build) | 21 | `java.toolchain` dans chaque `build.gradle` |
| CraftLabCore | 1.0.1 | `server/CraftLabCore/gradle.properties` (`mod_version`) |
| BlankMod (déployé en production) | 1.0.0 | `ModRegistry` réel (voir §3) |
| BlankMod (dernière release disponible en amont) | 1.1.0 | `KamroniteCompany/CraftLabTest`, non encore importée en production |
| CraftLab Launcher | 0.1.0 | `launcher/CraftLabLauncher/build.gradle` (`version`) |

Convention de version et procédure de release détaillées : [`docs/versioning.md`](versioning.md).

## 2. État Git (vérifié à la date de référence)

```
branche : main
commit  : d8c4eef
origin/main : d8c4eef (identique — aucun décalage)
working tree : clean (aucune modification non commitée)
```

```
git log --oneline -5
d8c4eef fix: jlink runtime missed java.compiler needed by Forge/Mixin
112bc25 fix: packaged launcher reopened itself instead of starting Minecraft
a4a9660 feat: Windows installer via jpackage + fix packaged app not starting
90f1c7f test: cover ModPack sync/download/comparator paths on the launcher
8f0eacc fix: HttpModPackProvider now wraps async network failures too
```

## 3. État GitHub (vérifié à la date de référence)

**`KamroniteCompany/CraftLab`** (monorepo serveur + launcher) :

| Tag | Commit | Release | Asset | SHA-256 |
|---|---|---|---|---|
| `v1.0.0` | `9073514` | CraftLabCore 1.0.0 | `craftlabcore-1.0.0.jar` | `a3061fd7...f363d4c` |
| `v1.0.1` | `6c2a2d2` | CraftLabCore 1.0.1 (latest) | `craftlabcore-1.0.1.jar` | `cefb5221...67eda1` |

Les deux tags pointent vers des commits **distincts**, chacun correspondant réellement au code
du JAR publié sous cette release (corrigé le 2026-08-31 — voir l'historique de commits pour
l'incident où `v1.0.1` pointait par erreur vers le même commit que `v1.0.0`).

**`KamroniteCompany/CraftLabTest`** (mod de test indépendant, `modId=blankmod`) :

| Tag | Release | Asset |
|---|---|---|
| `v1.0.0` | CraftLab Test 1.0.0 | `blankmod-1.0.0.jar` |
| `v1.1.0` | latest | `blankmod-1.1.0.jar` |

## 4. État réellement déployé en production

Serveur réel : `C:\Users\lamou\Desktop\ServOpenSrcMC`.

- **ModRegistry** (`config/craftlabcore/mods.json`) : `craftlabcore` 1.0.1 ACCEPTED,
  `blankmod` 1.0.0 ACCEPTED. Aucune autre entrée (le reliquat `communitytest` a été retiré le
  2026-08-31, voir `server/CraftLabCore/docs/renaming-communitytest-to-craftlabcore.md`).
- **ModPack CURRENT** (`config/craftlabcore/modpack/current-modpack.json`) : génération 3,
  `applyState=APPLIED`, mêmes deux mods et mêmes versions que le ModRegistry.
- **`mods/`** : `craftlabcore-1.0.1.jar` + `blankmod-1.0.0.jar`, correspondant exactement aux
  SHA-256 attendus.
- **RCON** : `enable-rcon=false`, vérifié réellement fermé en mémoire après redémarrage
  (`Connection refused` sur le port 25575).
- **BlankMod 1.1.0** existe en amont sur GitHub mais n'a volontairement pas été importé en
  production — comportement normal du système (validation communautaire requise avant toute
  mise à jour, voir `docs/modpack-update-procedure.md`).

## 5. Architecture (résumé — voir les documents dédiés pour le détail)

```
Développeur → GitHub Release
                  ↓
     GitHubModImporter / GitHubRefreshScheduler (détection automatique, intervalle configurable)
                  ↓
              ModRegistry (catalogue de tous les mods connus, tous statuts)
                  ↓
     /modpack prepare (manuel ou automatique après détection) → NEXT
                  ↓
     /modpack diff → /modpack apply (toujours manuel) → CURRENT
                  ↓
     LauncherModPackExporter → current-modpack-launcher.json
                  ↓
              CraftLab Launcher (synchronisation, téléchargement, vérification SHA-256)
                  ↓
        Minecraft 1.21.1 + Forge 52.1.0 (instance isolée sous %APPDATA%\CraftLabLauncher\)
                  ↓
    Écran CraftLab (CraftLabTitleScreen : Jouer / Mods / Paramètres / Quitter)
                  ↓
              Serveur CraftLab (connexion uniquement au clic sur "Jouer")
```

Documents de référence par composant :
- Serveur / ModRegistry / import GitHub : `server/CraftLabCore/docs/github-mod-format.md`
- ModPack (préparation) : `server/CraftLabCore/docs/modpack.md`
- ModPack (application/rollback) : `server/CraftLabCore/docs/modpack-lifecycle.md`
- Écran client : `server/CraftLabCore/docs/client-title-screen.md`
- Launcher (architecture, build, jpackage) : `launcher/CraftLabLauncher/docs/launcher.md`
- Convention de version et procédure de release : `docs/versioning.md`

Procédures opérationnelles :
- Publier une release de mod : `server/CraftLabCore/docs/github-mod-format.md` (sections 1-2)
- Mettre à jour le ModPack en production : `docs/modpack-update-procedure.md`
- Publier une nouvelle version du launcher : `launcher/CraftLabLauncher/docs/releasing.md`
- Installer CraftLab (joueur) : `docs/player-installation-guide.md`

## 6. Tests (état à la date de référence)

```
CraftLabCore : 33/33 tests, 0 échec (./gradlew clean build)
Launcher     : 26/26 tests, 0 échec (./gradlew clean build)
```

Détail des composants testés/non testés et raisons : voir les commits `1a1d0ff` (serveur) et
`90f1c7f` (launcher) dans l'historique Git — leurs messages documentent précisément ce qui est
couvert et ce qui reste hors de portée d'un test JUnit pur (`ModPackApplier`/`ModPackManager`,
couplés à `FMLPaths`/`ModRegistry` sans extraction raisonnable possible).

## 7. Procédure de build reproductible (vérifiée depuis un clone propre)

```bash
git clone https://github.com/KamroniteCompany/CraftLab.git
cd CraftLab

cd server/CraftLabCore && ./gradlew.bat clean build && cd ../..
cd launcher/CraftLabLauncher && ./gradlew.bat clean build && cd ../..
```

Aucun état local préalable requis (JDK 21 provisionné automatiquement par le toolchain Gradle
si absent). Pour l'installeur Windows du launcher, voir les prérequis (WiX Toolset) dans
`launcher/CraftLabLauncher/docs/launcher.md`.

## 8. Limitations connues à cette date

- Installeur Windows du launcher (`jpackageWindows`) : en cours de validation finale (correctifs
  JavaFX + relance Minecraft + runtime jlink appliqués et vérifiés partiellement ; test complet
  d'installation réelle joueur → Minecraft → écran CraftLab → serveur en cours).
- Launcher : authentification hors-ligne uniquement (`OfflineAuthProvider`), pas encore
  d'authentification Microsoft réelle.
- Launcher : un seul profil serveur (`craftlab`), pas de support multi-serveurs.
- `next-modpack.json.generation` reste volontairement toujours à 0 (non pertinent pour NEXT,
  voir `server/CraftLabCore/docs/modpack-lifecycle.md`).
- Entrée `runtime/bin` du launcher packagé : contient désormais le JDK complet (jlink
  `ALL-MODULE-PATH`) plutôt qu'un sous-ensemble réduit, par nécessité (Forge/Mixin et les mods
  peuvent requérir n'importe quel module JDK, imprévisible à l'avance) — voir `build.gradle`,
  tâche `jlinkRuntime`, pour l'historique de cette décision.
