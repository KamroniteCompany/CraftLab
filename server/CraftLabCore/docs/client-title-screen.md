# L'écran d'accueil CraftLab (client)

Ce document explique comment le menu principal de Minecraft est personnalisé pour CraftLab,
pourquoi ce code vit dans `CraftLabCore` (le mod principal du serveur, anciennement nommé
`CommunityTest` — voir `docs/renaming-communitytest-to-craftlabcore.md`), et pourquoi la
connexion au serveur n'a lieu que si le joueur clique explicitement sur "Jouer" depuis cet écran.

## 1. Flux général

```
CraftLab Launcher
      ↓
Minecraft Forge démarre (aucune connexion automatique — voir §4)
      ↓
CraftLabTitleScreen
      ↓
[ Jouer ]
      ↓
Connexion au serveur CraftLab (server_address / server_port)
```

## 2. Pourquoi dans CraftLabCore, et pourquoi c'est sans danger pour le serveur dédié

`CraftLabCore` reste un seul mod, chargé `side="BOTH"` (voir `mods.toml`) : il n'y a pas de
second mod client. Le code propre à l'interface vit dans le sous-package
`com.craftlab.craftlabcore.client`, et chaque classe qui y référence des types
`net.minecraft.client.*` est annotée `@Mod.EventBusSubscriber(..., value = Dist.CLIENT, ...)`
ou `@OnlyIn(Dist.CLIENT)`.

C'est cette annotation — pas une convention de nommage ni un test manuel — qui garantit que
Forge ne charge jamais ces classes sur un serveur dédié : FML lit `value = Dist.CLIENT` au
moment de scanner les classes du mod, **avant** de les charger, et les ignore côté serveur.
Un serveur dédié qui chargerait `CraftLabTitleScreen` planterait immédiatement
(`NoClassDefFoundError` sur `net.minecraft.client.Minecraft` ou équivalent, absent d'un jar
serveur) — cette annotation est ce qui empêche exactement ça. Testé directement : `./gradlew
runServer` démarre et affiche `Done (...)!` sans jamais toucher à ces classes.

Deux classes :

- `ClientModEvents` (bus **MOD**, `@Mod.EventBusSubscriber(Dist.CLIENT)`, `FMLClientSetupEvent`)
  — log d'initialisation, et enregistre `ClientForgeEvents` sur le bus Forge (voir §3).
- `ClientForgeEvents` (`@OnlyIn(Dist.CLIENT)`, référencée uniquement depuis `ClientModEvents`)
  — remplace l'écran-titre.

## 3. Comment l'écran-titre est remplacé

Déclencheur retenu : **`ScreenEvent.Init.Post`**, pas `ScreenEvent.Opening` (pourtant
l'événement "naturel" pour substituer un écran avant son ouverture, et documenté comme tel par
Forge). Ce choix vient d'un constat empirique, pas d'une préférence de style :

- Un poller direct sur `Minecraft.getInstance().screen` (sans passer par l'event bus, pour
  observer l'état réel indépendamment de toute hypothèse) a montré que `mc.screen` devenait
  bien un `TitleScreen` — donc que `Minecraft.setScreen(new TitleScreen(true))` était bien
  appelé, et donc que `ScreenEvent.Opening` était bien posté (c'est la même méthode qui poste
  l'événement juste avant).
- Mais `ScreenEvent.Opening` n'atteignait jamais notre gestionnaire, quel que soit le mode
  d'enregistrement essayé : automatique par annotation (`@Mod.EventBusSubscriber(bus=FORGE)`),
  manuel d'une méthode statique, manuel d'une instance (le chemin le plus standard et le plus
  testé de l'écosystème Forge) — les trois enregistrent sans lever d'exception, mais aucun ne
  fait recevoir l'événement.
- `ScreenEvent.Init.Post` — posté directement par `Screen.init(Minecraft, int, int)`, sans
  passer par l'indirection `ForgeEventFactoryClient` qu'utilise `Opening` — s'est révélé fiable
  dans les mêmes conditions, avec le même mécanisme d'enregistrement (instance).

En pratique : dès qu'un `TitleScreen` (ou `JoinMultiplayerScreen`, voir plus bas) termine son
propre `init()`, on demande immédiatement `Minecraft.getInstance().setScreen(new
CraftLabTitleScreen())`. Le bref écran vanilla qui vient de s'initialiser n'est jamais rendu à
l'écran (la substitution se produit avant la prochaine frame) : le résultat visible par le
joueur est identique à un remplacement direct sur `Opening`. Vérifié en conditions réelles :
`[CraftLab] Écran CraftLab chargé.` apparaît bien, sans double affichage ni scintillement.

`CraftLabTitleScreen` étend `Screen` directement, **pas** `TitleScreen` : `TitleScreen`
n'expose pas ses champs internes (splash, logo, notifications Realms) aux sous-classes, et en
hériter risquerait une boucle de substitution. En héritant seulement de `Screen`, `instanceof
TitleScreen` ne matche jamais notre propre écran — y compris sur sa PROPRE `Init.Post` (ex.
redimensionnement de la fenêtre) — donc aucun risque de boucle. Le fond animé (panorama
vanilla) reste disponible via `Screen.renderPanorama` : aucune image externe n'est nécessaire.

`ClientForgeEvents` intercepte deux écrans vanilla, tous deux vérifiés directement dans les
sources Mojang/Forge 1.21.1 pour éviter toute hypothèse :

- **`TitleScreen`** — le tout premier écran au démarrage, et le retour au menu quand
  `Minecraft.setScreen(null)` est appelé sans niveau chargé (ex. quitter une partie locale).
- **`JoinMultiplayerScreen`** — la liste de serveurs vanilla. INDISPENSABLE, pas seulement
  défensif : `PauseScreen.onDisconnect()` appelle explicitement `this.minecraft.setScreen(new
  JoinMultiplayerScreen(titlescreen))` quand le joueur clique "Déconnecter" en jeu (serveur
  non-Realm) — sans cette interception, une déconnexion volontaire depuis le menu pause
  afficherait le menu multijoueur vanilla au lieu de `CraftLabTitleScreen`.

Le bouton "Jouer" de `CraftLabTitleScreen` passe `this` (pas un nouveau `TitleScreen`) comme
écran parent à `ConnectScreen.startConnecting(...)` : en cas d'erreur de connexion ou de
déconnexion ultérieure côté serveur (kick, perte réseau),
`ClientCommonPacketListenerImpl.createDisconnectScreen` réutilise directement ce même
`postDisconnectScreen` — le joueur revient donc sur l'instance exacte de `CraftLabTitleScreen`
depuis laquelle il avait cliqué "Jouer", sans même transiter par les écrans interceptés
ci-dessus.

## 4. Pourquoi plus de connexion automatique (Quick Play)

Une version précédente utilisait Quick Play (`--quickPlayMultiplayer`) côté launcher pour
rejoindre le serveur dès le démarrage. Problème : **quand Quick Play réussit, Minecraft ne crée
jamais de `TitleScreen` du tout** (vérifié dans `Minecraft.<init>`, méthode
`buildInitialScreens` : la branche Quick Play appelle `QuickPlay.connect(...)` à la place et
saute tout écran) — donc `CraftLabTitleScreen` ne s'affichait jamais, empêchant justement
l'accès à Mods/Paramètres/Quitter avant de rejoindre le serveur.

Le launcher (voir `docs/launcher.md`) ne construit donc plus les arguments Quick Play pour le
lancement normal. Minecraft démarre toujours sur un `TitleScreen` classique, substitué comme
décrit au §3, **avant** toute tentative de connexion. `server_address`/`server_port` restent
définis dans `launcher.properties`, mais transitent désormais vers le client via deux
propriétés système (`-Dcraftlab.server.address`, `-Dcraftlab.server.port`) lues par
`ServerTarget` — jamais codés en dur côté mod, et jamais utilisés pour connecter automatiquement
qui que ce soit : ils ne servent QUE quand le joueur clique lui-même sur "Jouer".

## 5. Fonctionnalités disponibles depuis l'écran CraftLab

| Bouton | Action | Détail |
|---|---|---|
| Jouer | Connexion au serveur CraftLab | `ConnectScreen.startConnecting(...)` — la même méthode native que le bouton "Rejoindre" vanilla, pas de logique réseau réimplémentée |
| Mods | Liste des mods chargés | `net.minecraftforge.client.gui.ModListScreen`, l'écran Forge standard (inchangé) |
| Paramètres | Réglages Minecraft | `net.minecraft.client.gui.screens.options.OptionsScreen`, inchangé |
| Quitter | Ferme le client | `Minecraft.stop()` |

Singleplayer, Multiplayer (liste de serveurs) et Realms ne sont plus proposés comme boutons sur
cet écran d'accueil — leurs classes/écrans vanilla ne sont pas supprimés, seulement plus
accessibles depuis ce point d'entrée précis.

## 6. Résolution et disposition

Les boutons sont positionnés relativement à `this.width`/`this.height` (comme le fait
`TitleScreen` vanilla), pas avec des coordonnées fixes pour 1280x720 — l'écran reste utilisable
si l'utilisateur change `resolution_width`/`resolution_height` dans `launcher.properties` ou
redimensionne la fenêtre.
