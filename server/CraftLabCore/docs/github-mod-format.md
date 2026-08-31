# Publier un mod sur la plateforme CraftLab via GitHub

Ce document explique comment structurer un repository GitHub pour qu'il puisse être
importé automatiquement dans le `ModRegistry` du serveur, via la commande :

```
/mod github import https://github.com/<owner>/<repository>
```

## 1. Le fichier `community-mod.json`

Ce fichier est **obligatoire**. Il doit se trouver **à la racine** du repository, sur la
branche par défaut (celle affichée par GitHub quand on ouvre le repository).

### Champs obligatoires

| Champ              | Type   | Contraintes                                                                 |
|---------------------|--------|------------------------------------------------------------------------------|
| `modId`             | string | Non vide. Lettres minuscules, chiffres, `-` ou `_`, doit commencer par une lettre. Doit être unique sur le serveur. |
| `name`               | string | Non vide. Nom affiché du mod.                                                |
| `author`             | string | Non vide.                                                                     |
| `description`        | string | Non vide.                                                                     |
| `minecraftVersion`    | string | Doit être exactement `1.21.1` pour ce serveur.                               |
| `loader`              | string | Doit être exactement `forge` (Fabric et NeoForge sont refusés pour l'instant). |

### Exemple complet

```json
{
  "modId": "gravityboots",
  "name": "Gravity Boots",
  "author": "Alex",
  "description": "Adds gravity boots.",
  "minecraftVersion": "1.21.1",
  "loader": "forge"
}
```

Si l'un de ces champs est absent, vide, ou si `minecraftVersion`/`loader` ne correspondent
pas à ce serveur, l'import est refusé avec un message clair — rien n'est ajouté au registre.

## 2. Créer une GitHub Release contenant le `.jar`

1. Compile ton mod normalement (`./gradlew build`), tu obtiens un fichier dans `build/libs/`.
2. Sur GitHub, va dans **Releases** → **Draft a new release**.
3. Renseigne un tag de version, par exemple `v1.1.0`.
4. **Ne coche PAS** "Set as a pre-release" et **ne publie pas en tant que draft** — une
   release en brouillon (*draft*) ou en pré-version (*prerelease*) est ignorée par
   l'import.
5. Dans la section **Assets**, attache **exactement un** fichier `.jar` (par exemple
   `gravityboots-1.1.0.jar`). Si plusieurs `.jar` sont attachés à la même release,
   l'import est refusé car il ne peut pas deviner lequel utiliser.
6. Publie la release ("Publish release").

Le système choisit automatiquement la release publique (non-draft, non-prerelease) la
plus récente qui contient un fichier `.jar`. Publier une nouvelle release plus tard (par
exemple `v1.2.0`) et relancer `/mod github import <url>` met à jour le mod existant dans
le registre — le `modId` reste l'identifiant unique, aucun doublon n'est créé.

## 3. Ce qui se passe après l'import

L'import **n'installe pas** le `.jar`, ne redémarre pas le serveur et ne lance **pas**
automatiquement de vote. Il se contente d'enregistrer les métadonnées du mod (nom,
auteur, version, lien vers la release et l'asset `.jar`) dans le `ModRegistry`.

C'est ensuite à un administrateur de décider de lancer une période de test :

```
/mod start <modId>
```

## 4. Erreurs courantes

| Situation                                   | Message                                              |
|-----------------------------------------------|--------------------------------------------------------|
| URL mal formée                                | `L'URL doit être de la forme https://github.com/owner/repository.` |
| Repository inexistant ou privé                | `Repository introuvable ou privé : ...`                |
| `community-mod.json` absent                    | `Le fichier community-mod.json est introuvable dans ...` |
| `minecraftVersion` incorrecte                   | `Ce serveur tourne en 1.21.1, mais le mod cible '...'.` |
| `loader` différent de `forge`                    | `Seul le loader 'forge' est accepté pour le moment.`   |
| Aucune release valide avec un `.jar`             | `Aucune release Forge 1.21.1 valide contenant un fichier .jar n'a été trouvée.` |
| Plusieurs `.jar` dans la même release             | `La release ... contient plusieurs fichiers .jar : publiez-en un seul par release.` |
| `modId` déjà utilisé par un autre mod              | `L'ID '...' est déjà utilisé par un autre mod enregistré.` |

**Précision sur le conflit de `modId` (depuis 1.0.1).** Ce conflit n'est déclenché que si le
mod existant a déjà une source GitHub **confirmée et différente**. Une entrée existante sans
aucune source (par exemple `craftlabcore`, enregistré automatiquement au tout premier
démarrage, jamais lié à GitHub) n'a jamais rien à protéger : l'importer l'autorise à se
rattacher normalement au repository importé, exactement comme une mise à jour.

## 5. Vérifier toutes les nouvelles releases en une fois (`/mod github refresh`)

Pour un mod déjà `ACCEPTED` et déjà rattaché à une source GitHub, il n'est pas nécessaire de
retaper `/mod github import <url>` à chaque nouvelle release :

```
/mod github refresh
```

relance l'import pour **tous** les mods `ACCEPTED` déjà liés à GitHub d'un coup, et rapporte
pour chacun `=` (déjà à jour), `~ ancienne → nouvelle` (mise à jour détectée dans le
`ModRegistry`), ou une erreur individuelle (ex. GitHub temporairement inaccessible) sans faire
échouer les autres mods.

## 6. Automatisation : `GitHubRefreshScheduler`

Depuis cette phase, `/mod github refresh` n'a plus besoin d'être tapé à la main : un scheduler
en arrière-plan (`GitHubRefreshScheduler`, démarré/arrêté avec le serveur) relance
automatiquement le même `refreshAll()` à intervalle régulier.

- **Configuration** : `config/craftlabcore/config.properties`, clé
  `github_refresh_interval_minutes` (défaut **60**). `0` désactive complètement le scheduler —
  la commande manuelle `/mod github refresh` reste disponible dans tous les cas.
- **Ce qu'il fait en plus de la simple vérification** : pour chaque mod dont une nouvelle
  version vient d'être enregistrée dans le `ModRegistry`, il lance automatiquement l'équivalent
  de `/modpack prepare <modId>` (téléchargement, vérification SHA-256, ajout à `NEXT`).
- **Ce qu'il ne fait JAMAIS** : préparer un mod non-`ACCEPTED` (même filtre que `refreshAll()`),
  ni exécuter `/modpack apply`, ni toucher `CURRENT` de quelque façon que ce soit. La promotion
  `NEXT → CURRENT` reste une décision manuelle distincte (voir `docs/modpack-lifecycle.md`) —
  c'est ce qui garantit que publier une release GitHub ne se traduit jamais en déploiement
  immédiat sans contrôle humain.
- **Testé en conditions réelles** (audit du 2026-08-31) : ModRegistry rembobiné vers une
  version réelle antérieure de `blankmod`, scheduler configuré à 1 minute, redémarrage — la
  nouvelle version a été détectée et `NEXT` préparé automatiquement, sans aucune commande
  manuelle, `CURRENT` restant strictement inchangé.

Flux complet obtenu :

```
Développeur publie une release GitHub
      ↓
GitHubRefreshScheduler détecte (toutes les N minutes)
      ↓
ModRegistry mis à jour automatiquement
      ↓
NEXT préparé automatiquement (téléchargement + SHA-256)
      ↓
/modpack diff  (revue humaine)
      ↓
/modpack apply (décision humaine, jamais automatique)
      ↓
CURRENT
```
