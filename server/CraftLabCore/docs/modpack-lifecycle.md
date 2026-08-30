# Cycle de vie du ModPack CraftLab

```
ModRegistry (ACCEPTED)
      ↓
NEXT ModPack (téléchargé, SHA-256 vérifié — voir docs/modpack.md)
      ↓
/modpack apply
      ↓
Validation (tous les fichiers de NEXT présents + SHA-256 correct)
      ↓
Backup (état /mods actuellement géré)
      ↓
Staging (nouveaux .jar copiés et re-vérifiés dans une zone de transit)
      ↓
Swap (uniquement les fichiers ADD/UPDATE/REMOVE réels, dans le vrai dossier /mods de Forge)
      ↓
CURRENT ModPack (promu depuis NEXT, generation +1)
      ↓
Redémarrage manuel du serveur
      ↓
Forge scanne /mods au lancement de la JVM
```

## Pourquoi ce découpage précis ? Le comportement réel de Forge

C'est le point le plus important à comprendre avant de toucher à ce système.

Sur Forge 1.21.1/52.1.0, le dossier `/mods` est scanné par ModLauncher **au tout début du
démarrage de la JVM**, avant que le code de nos propres mods (dont CraftLabCore, qui est
lui-même chargé *depuis* `/mods`) ne s'exécute. Le jeu des mods actifs pour un démarrage
donné est donc déjà figé bien avant qu'un `@SubscribeEvent` de CraftLabCore ne se
déclenche.

**Conséquence :** aucun code tournant dans un mod Forge ne peut changer les mods chargés
pour le démarrage **en cours**. Il ne peut que préparer `/mods` pour le **prochain**
démarrage.

**Mais :** modifier `/mods` pendant que le serveur tourne n'a strictement aucun effet sur
l'instance Forge déjà lancée (elle ne rescane jamais ce dossier). C'est donc une opération
sûre, qui peut se faire à tout moment sans risque pour la partie en cours — c'est
exactement ce que fait `/modpack apply` : il modifie le vrai `/mods` **immédiatement**,
mais le résultat ne sera visible qu'au **prochain** redémarrage, déclenché manuellement.

Ce système ne tente donc jamais de charger ou décharger un mod à chaud, et ne redémarre
jamais le serveur lui-même — les deux étant explicitement hors périmètre.

## CURRENT vs NEXT vs le vrai `/mods`

Trois choses distinctes, à ne pas confondre :

1. **NEXT** (`next-modpack.json`) : ce qui a été préparé (voté `ACCEPTED`, téléchargé,
   vérifié) mais pas forcément encore déployé.
2. **CURRENT** (`current-modpack.json`) : la description logique de ce qui **est
   effectivement déployé** dans `/mods` par CraftLab, telle que CraftLab la connaît.
3. **Le contenu réel de `/mods`** : ce que Forge charge réellement. `/modpack apply`
   garantit que ce contenu correspond exactement à CURRENT après promotion — mais rien
   n'empêche un opérateur de modifier `/mods` à la main entre-temps ; CraftLab ne peut le
   détecter qu'au prochain redémarrage (log informatif, sans correction forcée).

## Staging

Avant tout remplacement dans le vrai `/mods`, chaque fichier à ajouter ou mettre à jour
est d'abord copié dans `config/craftlabcore/staging/` (jamais dans `downloads/`, qui
reste le cache source, ni directement dans `/mods`), puis son SHA-256 est recalculé sur
cette copie. Ce n'est qu'après cette double vérification que le fichier est déplacé
(`Files.move`, atomique si possible) vers `/mods`. Un fichier partiellement copié ne peut
donc jamais se retrouver dans `/mods`.

## Backups

Juste avant toute modification de `/mods`, un instantané est pris dans
`config/craftlabcore/backups/<horodatage>/` : une copie de chaque fichier actuellement
géré par CraftLab, une copie du manifest (`managed-mods.json`) et une copie de
`current-modpack.json`. Rien n'est jamais supprimé automatiquement dans `backups/` — la
politique de nettoyage est explicitement laissée pour plus tard.

Les fichiers de `config/craftlabcore/downloads/` (le cache de téléchargement) ne sont
eux non plus jamais supprimés lors d'un retrait de mod : ils restent disponibles pour un
rollback ou une réinstallation future.

## Rollback et détection de crash

`ModPackApplier` écrit un marqueur (`config/craftlabcore/modpack/apply-state.txt`)
juste avant l'étape risquée (le remplacement effectif des fichiers dans `/mods`), et
l'efface juste après. Si le processus est interrompu entre les deux (crash, `kill -9`,
coupure), ce marqueur reste sur `APPLYING`.

Au démarrage suivant, `ModPackApplier.checkForInterruptedApply()` détecte ce cas **avant**
toute autre logique et restaure automatiquement le dernier backup disponible — fichiers
`/mods` gérés et manifest inclus — avant que quoi que ce soit d'autre ne s'exécute.

`/modpack rollback` permet aussi de déclencher manuellement une restauration vers le
dernier backup, par exemple si un ModPack appliqué s'avère problématique.

## Mods gérés par CraftLab vs mods externes

`config/craftlabcore/managed-mods.json` liste précisément quels fichiers de `/mods` sont
sous la responsabilité de CraftLab (modId, nom de fichier, SHA-256). Tout fichier présent
dans `/mods` mais absent de ce manifest (JEI, un plugin serveur, etc.) n'est **jamais**
touché par `ModPackApplier` — ni à l'ajout, ni à la mise à jour, ni au retrait, ni au
rollback.

## Cas particuliers gérés

- **CURRENT == NEXT** : `/modpack apply` détecte un diff vide et ne touche à rien.
- **CURRENT absent, NEXT présent et valide** (premier démarrage) : `bootstrapIfNeeded()`
  applique automatiquement NEXT au démarrage — avec le même décalage d'un redémarrage que
  toute autre application, pour les mêmes raisons de timing Forge.
- **NEXT absent** : rien ne se passe, CURRENT reste inchangé.
- **NEXT invalide** (fichier manquant ou SHA-256 incorrect) : NEXT passe à `NOT_READY`,
  CURRENT n'est jamais touché, message d'erreur clair.

## Limitations actuelles (volontaires)

- Aucun chargement ou déchargement de mod à chaud.
- Aucun redémarrage automatique du serveur — un opérateur humain doit redémarrer pour que
  Forge charge le nouvel état.
- Aucune politique de nettoyage automatique des backups ou du cache de téléchargement.
- La détection d'un `/mods` modifié manuellement en dehors de CraftLab est purement
  informative (log), sans correction automatique.
- Toujours hors périmètre : launcher, synchronisation client, site web, API publique.
