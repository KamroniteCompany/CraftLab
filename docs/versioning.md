# Stratégie de versions CraftLab

Ce document fixe une convention simple pour éviter la situation qu'on a réellement rencontrée
sur CraftLabCore : le tag `v1.0.1` pointait sur un commit dont le code déclarait encore
`mod_version=1.0.0`, alors que le JAR effectivement publié sous cette release avait bien été
construit avec le vrai code 1.0.1 — juste depuis un commit différent, jamais retagué. Corrigé le
2026-08-31 (`v1.0.1` retargeté vers le bon commit), mais uniquement parce que quelqu'un a pensé à
vérifier. La procédure ci-dessous existe pour que ça n'ait plus besoin d'être découvert après coup.

## 1. Ce qui a une version, et comment

Chaque artefact **distribué indépendamment** a son propre numéro SemVer (`MAJOR.MINOR.PATCH`) :

| Artefact | Où vit le numéro | Exposé où |
|---|---|---|
| `CraftLabCore` | `server/CraftLabCore/gradle.properties` (`mod_version`) | `mods.toml` (`version="..."`), nom du JAR, `ModRegistry` |
| `BlankMod` (`CraftLabTest`) | `gradle.properties` du dépôt `KamroniteCompany/CraftLabTest` (`mod_version`) | idem |
| `CraftLab Launcher` | `launcher/CraftLabLauncher/build.gradle` (`version = '...'`) | manifeste du JAR (`Implementation-Version`, voir `AppVersion.current()`), argument `launcher_version` passé à Minecraft |

**`ModPack` (CURRENT/NEXT) n'a volontairement pas de numéro SemVer.** Ce n'est pas un artefact
distribué de façon autonome : c'est un instantané de "quelles versions des autres mods sont
actives en ce moment", et son seul identifiant nécessaire est déjà le compteur `generation`
(voir `server/CraftLabCore/docs/modpack-lifecycle.md`, section "Génération"). Lui donner un
SemVer séparé n'apporterait rien de plus que ce que `generation` + la liste des mods fournit
déjà, et ajouterait une deuxième numérotation à garder en synchronisation pour rien.

## 2. Incrémenter quoi, quand

- **MAJOR** : rupture de compatibilité (ex. changement de Minecraft/Forge — hors périmètre actuel
  puisque 1.21.1/52.1.0 sont fixés pour l'instant).
- **MINOR** : nouvelle fonctionnalité rétrocompatible (ex. `GitHubRefreshScheduler` ajouté à
  CraftLabCore justifierait un `1.1.0`, pas un `1.0.2`).
- **PATCH** : correctif de bug sans nouvelle fonctionnalité (ex. le vrai correctif 1.0.1 :
  `ModFileReplacer` + le fix de conflit d'import GitHub).

## 3. Procédure de release — l'ordre qui garantit la traçabilité

L'incident qu'on a eu vient d'un tag créé/déplacé indépendamment du code réellement publié.
La procédure suivante empêche structurellement que ça se reproduise :

```
1. Bump du numéro de version dans un commit dédié
   (gradle.properties pour un mod, build.gradle pour le launcher)
        ↓
2. Commit poussé sur main
        ↓
3. Tag créé EXACTEMENT sur ce commit (jamais avant, jamais après)
   git tag vX.Y.Z <sha-du-commit-de-l'étape-2>
        ↓
4. Tag poussé
   git push origin vX.Y.Z
        ↓
5. Checkout PROPRE de ce tag (jamais un build depuis un répertoire de travail
   qui pourrait contenir des changements non commités)
   git clone / git checkout vX.Y.Z, PUIS build
        ↓
6. JAR obtenu à l'étape 5 attaché à une nouvelle GitHub Release utilisant ce tag
        ↓
7. GitHub calcule le SHA-256 (champ `digest`) : c'est la valeur de référence,
   jamais recalculée à la main
```

**Règle absolue : un tag qui a déjà une Release publiée ne se déplace plus.** Si une erreur est
découverte après coup (comme celle qu'on vient de corriger), la déplacer est une opération
exceptionnelle qui nécessite : documentation précise de l'erreur, confirmation humaine explicite,
jamais un simple `git tag -f` réflexe. Le cas normal pour corriger une erreur détectée après
publication est de couper une nouvelle version (PATCH), pas de retoucher l'ancienne.

## 4. Pourquoi l'étape 5 (checkout propre) compte

Construire depuis un `git clone` frais du tag — pas depuis le répertoire de travail habituel —
élimine la classe de bug qu'on a eue : un build lancé depuis un poste de dev peut légitimement
contenir des commits pas encore poussés, des fichiers modifiés, ou simplement ne plus être sur
la bonne branche. `docs/versioning.md` recommande explicitement le clone frais ; l'automatiser
complètement (un JAR construit uniquement par une CI qui checkout le tag elle-même, jamais par
une machine de développeur) est le sujet de la Priorité CI/CD — voir la todo-list du projet.

## 5. État au 2026-08-31

| Artefact | Version actuelle | Tag correspondant | Vérifié |
|---|---|---|---|
| CraftLabCore | 1.0.1 | `v1.0.1` → commit `6c2a2d2` | ✅ code du commit déclare bien `1.0.1`, JAR publié contient bien `ModFileReplacer.class` |
| BlankMod | 1.1.0 (upstream GitHub) / 1.0.0 (déployé sur le serveur réel) | `v1.1.0` / `v1.0.0` (CraftLabTest, tags distincts) | ✅ |
| CraftLab Launcher | 0.1.0 | pas encore taggé (jamais publié comme release séparée) | — |

Le launcher n'a pas encore de tag/release dédiés car il n'a jamais été distribué en dehors de ce
dépôt — le jour où un premier installeur (`CraftLab-Launcher-Setup.exe`, voir la priorité
"launcher prêt à être distribué") est produit pour distribution, il doit suivre exactement la
même procédure ci-dessus, avec ses propres tags (proposition : `launcher-vX.Y.Z`, pour ne jamais
être ambigu avec les tags `vX.Y.Z` de CraftLabCore qui vivent dans le même dépôt Git).
