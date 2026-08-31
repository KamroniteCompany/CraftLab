# Procédure : publier une nouvelle version du CraftLab Launcher

Applique la convention générale de `docs/versioning.md` (racine du dépôt) au launcher
spécifiquement. Suivre cet ordre exact garantit que le tag Git, le code source, et l'installeur
distribué correspondent tous exactement — voir `docs/versioning.md` pour l'incident que cet
ordre précis a pour but d'empêcher.

## 1. Bump de version

Modifier `version = '...'` dans `launcher/CraftLabLauncher/build.gradle`. C'est la **seule**
source de vérité pour la version du launcher (voir `AppVersion.current()`, qui la lit depuis le
manifeste du JAR à l'exécution — jamais recopiée à la main ailleurs).

## 2. Vérifier avant de commiter

```powershell
cd launcher/CraftLabLauncher
./gradlew.bat clean build
```

Doit être `BUILD SUCCESSFUL`, tous les tests au vert.

## 3. Commit et tag

```bash
git add launcher/CraftLabLauncher/build.gradle
git commit -m "chore: bump launcher to X.Y.Z"
git push origin main

git tag launcher-vX.Y.Z <sha-du-commit-ci-dessus>
git push origin launcher-vX.Y.Z
```

**Préfixe `launcher-` obligatoire** (`launcher-v0.2.0`, pas `v0.2.0`) : ce dépôt est un monorepo
partagé avec CraftLabCore, dont les tags utilisent déjà `vX.Y.Z` sans préfixe — les deux
composants ne doivent jamais pouvoir se confondre dans la liste des tags.

## 4. Build depuis un checkout propre du tag

Ne jamais construire l'installeur depuis un répertoire de travail qui pourrait contenir des
changements non commités :

```powershell
git clone https://github.com/KamroniteCompany/CraftLab.git craftlab-release-build
cd craftlab-release-build/launcher/CraftLabLauncher
git checkout launcher-vX.Y.Z
```

## 5. Prérequis machine (une fois par machine de build)

WiX Toolset v3.x (voir `docs/launcher.md`, section "Prérequis") :

```powershell
winget install --id WiXToolset.WiXToolset --source winget --accept-package-agreements --accept-source-agreements
```

## 6. Générer l'installeur

```powershell
./gradlew.bat jpackageWindows
```

Produit `build/jpackage/CraftLab Launcher-X.Y.Z.exe`. Cette tâche construit d'abord un runtime
Java complet via `jlink` (`ALL-MODULE-PATH` — voir le commentaire de la tâche `jlinkRuntime`
dans `build.gradle` pour pourquoi une liste de modules réduite n'est pas sûre pour cette
application précise), puis l'installeur lui-même via `jpackage`/WiX.

## 7. Vérifier avant de considérer la release prête

**Ne jamais distribuer un installeur non testé.** Checklist minimale (voir aussi
`docs/player-installation-guide.md` pour le déroulé complet côté joueur) :

1. Installer sur une machine de test réelle (pas seulement `--type app-image`, le vrai `.exe`).
2. Lancer depuis le raccourci créé — l'interface JavaFX doit s'ouvrir sans Java externe installé.
3. Vérifier la récupération du ModPack (le launcher doit afficher les mods disponibles).
4. Cliquer "Jouer" — Minecraft/Forge doit démarrer, puis l'écran CraftLab doit apparaître.
5. Désinstaller — vérifier que `%APPDATA%\CraftLabLauncher\` **survit** à la désinstallation
   (seul le launcher lui-même, dans `%LOCALAPPDATA%\CraftLab Launcher\`, doit être supprimé).

Un problème découvert à cette étape (deux exemples réels rencontrés en pratique : "Error:
JavaFX runtime components are missing" à cause d'une classe `Main-Class` héritant directement de
`javafx.application.Application`, et "Module java.compiler not found" à cause d'un runtime jlink
trop restreint) doit être corrigé et l'installeur régénéré **avant** l'étape suivante — jamais
publié en connaissance d'un bug de lancement.

## 8. Publier la release GitHub

```
gh release create launcher-vX.Y.Z "build/jpackage/CraftLab Launcher-X.Y.Z.exe" \
  --repo KamroniteCompany/CraftLab \
  --title "CraftLab Launcher X.Y.Z" \
  --notes "..."
```

Le SHA-256 calculé par GitHub (champ `digest` de l'asset) fait foi — ne jamais le recalculer à
la main pour l'annoncer ailleurs.

## Ce que cette procédure ne couvre pas encore

- Mise à jour automatique du launcher lui-même (le joueur doit retélécharger et réinstaller
  manuellement une nouvelle version pour l'instant).
- Signature de code de l'exécutable (l'installeur n'est actuellement pas signé — Windows
  SmartScreen affichera un avertissement "éditeur inconnu" à l'installation).
