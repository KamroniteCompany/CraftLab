# Renommage : CommunityTest → CraftLabCore

Ce document trace la migration du mod principal du serveur, initialement nommé `CommunityTest`
(un nom hérité du template de démonstration utilisé au tout début du projet), vers son identité
définitive de mod cœur officiel de CraftLab : `CraftLabCore`.

## Ce qui a changé

| Aspect | Avant | Après |
|---|---|---|
| Dossier du mod | `server/CommunityTest/` | `server/CraftLabCore/` |
| `modId` | `communitytest` | `craftlabcore` |
| Nom affiché (`mods.toml`) | `CommunityTest` | `CraftLabCore` |
| Package Java | `com.communityserver.communitytest` | `com.craftlab.craftlabcore` |
| Classe principale | `CommunityTest` | `CraftLabCore` |
| `mod_group_id` | `com.communityserver.communitytest` | `com.craftlab.craftlabcore` |
| Dossier de config (`FMLPaths.CONFIGDIR`) | `config/communitytest/` | `config/craftlabcore/` |
| JAR produit | `communitytest-1.0.0.jar` | `craftlabcore-1.0.0.jar` |
| `mod_authors` | `CommunityTest Team` (placeholder lié à l'ancien nom) | `OpenSourceDev` (valeur déjà utilisée pour ce mod dans le `ModRegistry` réel — pas une valeur inventée) |
| `mod_description` | "Mod de démonstration du serveur communautaire..." | "Mod principal de CraftLab." (phrase déjà écrite par l'équipe dans un `community-mod.json` historique, commit `73168d4`, avant sa suppression — pas inventée) |
| Statut par défaut à l'amorçage (`ModRegistry.loadOrBootstrap`) | `TESTING` | `ACCEPTED` (le mod cœur fait partie de la plateforme dès l'installation, il n'a pas besoin d'un vote communautaire pour lui-même) |
| Façade `/communitytest` (`LegacyCommunityTestCommand`) | présente | **supprimée** — déjà auto-documentée comme "à retirer plus tard, rien d'autre n'en dépend" ; `/mod info craftlabcore`, `/mod vote craftlabcore ...`, `/mod start`/`end craftlabcore` couvrent exactement la même chose de façon générique |

## Ce qui n'a PAS changé

- **`CraftLabTest` / `blankmod`** : mod de test totalement distinct, non touché par cette migration.
- **Minecraft 1.21.1, Forge 52.1.0, Java 21** : aucune version modifiée.
- **Le dépôt GitHub** : il n'existe qu'un seul dépôt (`KamroniteCompany/CraftLab`, monorepo
  server + launcher). Il n'y a jamais eu de dépôt GitHub séparé nommé `CommunityTest` à
  renommer ou migrer — le champ `issueTrackerURL` de `mods.toml` pointait vers un placeholder
  de template (`toncompte/CommunityTest`) jamais renseigné ; il pointe maintenant vers le vrai
  dépôt (`KamroniteCompany/CraftLab/issues`).
- **Le ModPack (`current-modpack.json`/`next-modpack.json`/`current-modpack-launcher.json`)** :
  au moment du renommage, `communitytest`/`craftlabcore` n'y figurait pas encore — c'était
  alors le mod cœur, installé manuellement dans `mods/` des deux côtés (serveur et instance
  du launcher), au même titre que Forge lui-même. Aucune migration de ces fichiers n'était
  donc nécessaire pour cette raison précise, à cette date.

  **Mise à jour (CraftLabCore 1.0.1, audit du 2026-08-31)** : ce n'est plus le cas.
  `craftlabcore` est désormais distribué exactement comme `blankmod`, via le mécanisme
  GitHub-release + SHA-256 du ModPack (voir `docs/modpack.md` et `docs/modpack-lifecycle.md`) :
  il figure dans `ModRegistry`, `current-modpack.json`, `next-modpack.json` et l'export
  launcher au même titre que n'importe quel autre mod communautaire.

## Données migrées (hors dépôt Git)

Le serveur réellement déployé (hors du dépôt Git) possédait des données persistées sous
`config/communitytest/`. Elles ont été **copiées** (pas déplacées) vers `config/craftlabcore/` :

- `mods.json` — l'entrée `communitytest` est devenue `craftlabcore` (mêmes auteur/version/statut).
- `proposals/communitytest.json` → `proposals/craftlabcore.json` — le vote historique (déjà
  résolu, `ACCEPTED`) est conservé intégralement (horodatages, votes) ; seuls `modId` et
  `proposalId` sont mis à jour pour rester cohérents avec le nouvel identifiant, afin que
  `/mod info craftlabcore` retrouve bien cet historique.
- `managed-mods.json`, `modpack/current-modpack.json`, `modpack/next-modpack.json` — copiés
  tels quels : leur contenu ne mentionnait que `blankmod`, rien à changer.

L'ancien dossier `config/communitytest/` avait été laissé intact sur le disque, comme
sauvegarde, en attendant cette validation. **Mise à jour (audit du 2026-08-31)** : `craftlabcore`
fonctionne correctement depuis longtemps et ce dossier a depuis été nettoyé — il n'existe plus
sur le serveur réel.

**Reliquat supprimé (2026-08-31).** L'entrée `communitytest` a existé dans le `ModRegistry` réel
(`config/craftlabcore/mods.json`) jusqu'à cette date, statut `TESTING`, avec une source GitHub
pointant vers `KamroniteCompany/CraftLabCore` — un dépôt qui n'a jamais existé (le monorepo est
`KamroniteCompany/CraftLab`). Analyse complète avant suppression : aucun vote associé
(`proposals/communitytest.json` inexistant), absente de `managed-mods.json` (jamais gérée par
le ModPack), absente de tout backup, jamais traitée par `/mod github refresh` (qui ne touche
que les mods `ACCEPTED`). Confirmée totalement isolée et sans dépendance, elle a été retirée de
`mods.json` (sauvegarde préalable dans `config/craftlabcore/backups/manual-<horodatage>-pre-communitytest-removal/`),
serveur arrêté puis redémarré proprement, `/mod list` ne l'affiche plus.

## Étapes manuelles restantes (hors du contrôle de ce dépôt)

1. Remplacer `communitytest-1.0.0.jar` par `craftlabcore-1.0.0.jar` dans le dossier `mods/` du
   serveur réellement déployé, et dans `%APPDATA%\CraftLabLauncher\instances\craftlab\mods\` —
   **retirer l'ancien fichier** en même temps (les deux JAR ont désormais des `modId`
   différents et se chargeraient tous les deux si on les laissait coexister).
2. Recharger le projet Gradle dans l'IDE (`.idea/` n'est pas régénéré automatiquement par ce
   renommage — il n'a aucun impact sur `gradlew build`/`gradlew runServer`, seulement sur le
   confort d'édition dans l'IDE).
