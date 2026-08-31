# Procédure : mettre à jour le ModPack en production

Guide opérationnel, pas-à-pas, pour un administrateur qui veut faire passer une nouvelle version
d'un mod (déjà publiée sur GitHub) jusqu'au serveur réel. Pour la mécanique interne (pourquoi
CURRENT/NEXT, comment le rollback fonctionne, etc.), voir
`server/CraftLabCore/docs/modpack.md` et `modpack-lifecycle.md` — ce document ne répète pas ces
explications, il donne la séquence de commandes réelle.

## Pré-requis

- Le mod est déjà `ACCEPTED` dans le `ModRegistry` (import GitHub + vote déjà passés — voir
  `server/CraftLabCore/docs/github-mod-format.md`).
- Une nouvelle release existe sur GitHub pour ce mod.

## Étape 1 — Détecter la nouvelle version

Automatique par défaut (`GitHubRefreshScheduler`, toutes les `github_refresh_interval_minutes`
minutes, voir `config/craftlabcore/config.properties`) : si une mise à jour est détectée pour un
mod `ACCEPTED`, elle est **automatiquement préparée dans NEXT** (téléchargée, vérifiée) — sans
jamais toucher CURRENT.

Pour vérifier/déclencher manuellement sans attendre le prochain cycle automatique :

```
/mod github refresh
```

Rapporte pour chaque mod `ACCEPTED` déjà lié à GitHub : `=` (déjà à jour), `~ ancienne →
nouvelle` (mise à jour détectée **et préparée dans NEXT automatiquement**), ou une erreur
individuelle.

## Étape 2 — Vérifier ce qui a changé

```
/modpack diff
```

Affiche précisément ce qui différencie NEXT de CURRENT (`+` ajouté, `~` mis à jour, `-` retiré).
**Ne rien appliquer si le diff ne correspond pas à ce qui est attendu.**

Si un mod doit être préparé manuellement (cas rare — normalement déjà fait par le scheduler) :

```
/modpack prepare <modId>
```

## Étape 3 — Vérifier l'intégrité avant d'appliquer

```
/modpack verify
```

Confirme que tous les fichiers de NEXT existent réellement sur disque et correspondent à leur
SHA-256 attendu. Lecture seule, sans effet de bord.

## Étape 4 — Appliquer (décision manuelle, jamais automatique)

```
/modpack apply
```

Séquence interne : backup → mise en zone de transit (staging) → validation → remplacement
effectif dans `/mods` → promotion NEXT → CURRENT. **N'a aucun effet sur l'instance du serveur en
cours d'exécution** (Forge ne rescane `/mods` qu'au prochain démarrage de la JVM) — c'est
strictement sans risque de faire `/modpack apply` à tout moment, y compris avec des joueurs
connectés.

## Étape 5 — Redémarrer le serveur (manuel, à un moment choisi)

Le nouveau jeu de mods ne sera chargé qu'au **prochain redémarrage**. Choisir un moment adapté
(hors ligne, ou après avoir prévenu les joueurs). Redémarrage propre :

- Idéalement via un `stop` console/RCON.
- Si RCON est désactivé (état recommandé en production) et qu'aucune console interactive n'est
  attachée : demander l'arrêt manuel plutôt qu'un `taskkill` forcé — voir la règle générale sur
  l'arrêt propre du serveur.

## Étape 6 — Vérifier après redémarrage

```
/mod list
/modpack status
```

Confirmer que le mod affiche bien la nouvelle version, `ACCEPTED`, et que
`/modpack status` indique `Current ModPack : APPLIED (vN)` avec `N` incrémenté, sans changement
en attente (`aucun changement`).

Vérifier aussi que l'export a bien été régénéré pour le launcher :

```
config/craftlabcore/modpack/current-modpack-launcher.json
```

doit refléter la nouvelle version (généré automatiquement à chaque promotion, voir
`LauncherModPackExporter`).

## En cas de problème

```
/modpack rollback
```

Restaure le dernier backup connu (`config/craftlabcore/backups/<horodatage>/`). Chaque backup
contient une copie complète des fichiers gérés + le manifeste + `current-modpack.json` d'avant
l'application — rien n'est jamais supprimé automatiquement dans `backups/`.

Si le serveur a crashé pendant une application (rare, milieu du remplacement de fichiers),
`ModPackApplier.checkForInterruptedApply()` détecte et restaure automatiquement ce même dernier
backup **avant toute autre logique**, au redémarrage suivant — aucune action manuelle requise
dans ce cas précis.

## Ce que cette procédure ne fait jamais

- Ne court-circuite jamais le vote communautaire : seul un mod déjà `ACCEPTED` peut être préparé.
- Ne promeut jamais NEXT vers CURRENT automatiquement (`/modpack apply` reste toujours une
  décision humaine explicite).
- Ne redémarre jamais le serveur automatiquement.
