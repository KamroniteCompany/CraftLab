# Le système ModPack (CURRENT / NEXT)

Ce document explique comment le serveur passe d'un mod voté `ACCEPTED` à un fichier `.jar`
téléchargé, vérifié et prêt — **sans jamais** le charger ni redémarrer le serveur
automatiquement (cette étape est volontairement hors périmètre, voir plus bas).

## 1. ModRegistry vs ModPack

Ce sont deux choses différentes, à ne pas confondre :

- **`ModRegistry`** décrit tous les mods *connus* de la plateforme, quel que soit leur
  statut (`TESTING`, `ACCEPTED`, `REJECTED`). C'est le catalogue complet.
- **`ModPack`** décrit uniquement les mods *actuellement préparés* pour un serveur en
  fonctionnement — toujours des versions exactes, jamais "la dernière version".

Un mod peut donc exister dans le `ModRegistry` sans jamais apparaître dans un `ModPack`
(par exemple s'il est encore `TESTING`, ou `REJECTED`).

## 2. CURRENT vs NEXT

Il existe **deux** `ModPack` distincts, persistés séparément :

- **CURRENT** (`current-modpack.json`) : ce avec quoi le serveur tourne *actuellement*.
  Cette étape ne le modifie jamais automatiquement.
- **NEXT** (`next-modpack.json`) : ce qui est *préparé* pour le prochain démarrage. C'est
  ce fichier que `/modpack prepare`, `/modpack remove` et `/modpack sync` modifient.

Le serveur continue de fonctionner normalement avec CURRENT pendant que NEXT se prépare en
arrière-plan (téléchargements asynchrones). Faire passer NEXT → CURRENT (`/modpack apply`) est
une étape distincte, couverte en détail dans `docs/modpack-lifecycle.md` (staging, backup,
rollback, redémarrage manuel requis).

## 3. Règle d'activation

Seul un mod dont le statut dans le `ModRegistry` est exactement `ACCEPTED` peut être
préparé dans NEXT. Un mod `TESTING` ou `REJECTED` est refusé par `/modpack prepare`.

## 4. Téléchargement

`ModDownloadManager` télécharge le `.jar` associé à un `ModDefinition` (son
`assetDownloadUrl`, renseigné lors de l'import GitHub) :

- HTTPS uniquement — une URL en `http://` est refusée avant même la requête ; les
  redirections HTTPS → HTTP sont refusées nativement par le client HTTP.
- Écriture dans un fichier temporaire `<asset>.jar.part`, jamais directement dans le
  fichier final.
- La taille est bornée pendant le téléchargement (pas seulement vérifiée après coup) : un
  `BodySubscriber` personnalisé interrompt le flux dès que la limite configurée est
  dépassée, que le `Content-Length` annoncé soit correct, absent, ou mensonger.
- Le fichier n'est déplacé vers son emplacement final qu'**après** validation complète
  (taille non nulle, SHA-256 correct) — un fichier partiellement téléchargé ne peut donc
  jamais être pris pour un fichier valide.
- Le tout est asynchrone (`java.net.http.HttpClient` + `CompletableFuture`), exécuté sur un
  pool de threads dédié, jamais sur le thread principal du serveur.

## 5. SHA-256

Chaque entrée de ModPack porte un `sha256`. Deux sources possibles :

1. Si GitHub expose un champ `digest` (format `sha256:...`) pour l'asset au moment de
   l'import, il est retenu comme hash attendu.
2. Sinon, le SHA-256 est calculé **après** le premier téléchargement réussi et devient
   lui-même la référence pour les vérifications suivantes.

Un fichier local déjà présent n'est retéléchargé que si son SHA-256 ne correspond pas à
celui attendu (fichier absent, corrompu, ou version différente).

## 6. Emplacement des fichiers

```
config/craftlabcore/
├── mods.json                          (ModRegistry)
├── config.properties                   (vote_duration_seconds, github_token,
│                                          max_mod_download_size_mb,
│                                          max_concurrent_mod_downloads)
├── proposals/<modId>.json
├── downloads/
│   └── <modId>/
│       └── <version>/
│           └── <assetName>.jar        (fichier validé) / .jar.part (en cours)
└── modpack/
    ├── current-modpack.json
    └── next-modpack.json
```

Tout reste sous `config/craftlabcore/`, jamais dans le dossier `mods/` actif de Forge.

## 7. Préparation d'un mod (`/modpack prepare <modId>`)

```
ModDefinition (ACCEPTED)
      ↓
Vérification du statut
      ↓
Téléchargement (ou réutilisation si déjà valide)
      ↓
Vérification SHA-256
      ↓
Ajout au NEXT ModPack
```

Si une étape échoue, le mod **n'est pas ajouté** à NEXT (aucune entrée invalide n'est
jamais persistée) et l'administrateur reçoit un message d'erreur clair.

## 8. États

- **ModPack** (CURRENT ou NEXT, dans son ensemble) : `READY`, `DOWNLOADING`, `VALIDATING`
  (réservé), `FAILED` — reflète le résultat de la dernière opération.
- **ModPackEntry** (par mod) : `READY` uniquement en pratique, puisqu'une entrée n'est
  jamais ajoutée sans être entièrement validée.

`/modpack status` affiche l'état des deux ModPack et un résumé des différences (`+`
ajouté, `-` retiré, `~` mis à jour) via `ModPackDiff`.

## 9. Limitations actuelles (volontaires)

- Le serveur ne recharge **jamais** un mod à chaud.
- NEXT n'est **jamais** appliqué automatiquement à CURRENT : `/modpack apply` doit être
  déclenché explicitement par un opérateur (voir `docs/modpack-lifecycle.md`).
- Aucun redémarrage automatique du serveur n'est déclenché après `/modpack apply` — un
  opérateur humain doit redémarrer pour que Forge charge le nouvel état.
- Un mod `REJECTED` alors qu'il était dans NEXT en est retiré, mais son `.jar` déjà
  téléchargé **n'est pas supprimé** (cache conservé pour une réintroduction future).

**Mise à jour (audit du 2026-08-31)** : le CraftLab Launcher (`launcher/CraftLabLauncher/`)
existe désormais et consomme l'export de CURRENT (`current-modpack-launcher.json`, voir
`LauncherModPackExporter`) pour synchroniser une instance Minecraft/Forge locale — voir
`launcher/CraftLabLauncher/docs/launcher.md`.

## 10. Pourquoi pas de chargement à chaud ?

Modifier le classpath ou les mods chargés d'un serveur Forge en cours d'exécution n'est
pas une opération supportée de façon fiable : Forge initialise ses registries, événements
et mods une seule fois au démarrage. Tenter de le faire à chaud risquerait des états
incohérents (recettes, blocs, entités à moitié enregistrés) pouvant corrompre des mondes
en cours. La seule méthode fiable est un redémarrage propre avec le nouvel ensemble de
mods déjà en place — ce que CURRENT/NEXT prépare, sans le déclencher.
