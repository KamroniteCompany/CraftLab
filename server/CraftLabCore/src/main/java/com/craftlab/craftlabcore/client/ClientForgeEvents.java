package com.craftlab.craftlabcore.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Remplace l'écran-titre vanilla par {@link CraftLabTitleScreen}.
 *
 * Déclencheur retenu : {@code ScreenEvent.Init.Post}, PAS {@code ScreenEvent.Opening} — pourtant
 * l'événement "naturel" pour ce cas d'usage (substituer un écran avant son ouverture). Constaté
 * empiriquement, avec un poller direct sur {@code Minecraft.getInstance().screen} (sans passer
 * par l'event bus, pour observer l'état réel indépendamment de nos propres hypothèses) : dans
 * cet environnement, {@code ScreenEvent.Opening} n'atteint jamais notre gestionnaire pour le
 * {@code TitleScreen} réellement créé au démarrage — testé avec enregistrement automatique par
 * annotation, enregistrement manuel d'une méthode statique, et enregistrement manuel d'une
 * instance (le chemin le plus standard et le plus testé de l'écosystème Forge) : aucun des trois
 * ne fait recevoir l'événement. {@code ScreenEvent.Init.Post} — posté directement par
 * {@code Screen.init(Minecraft, int, int)}, sans passer par l'indirection
 * {@code ForgeEventFactoryClient} qu'utilise {@code Opening} — a été vérifié fiable dans les
 * mêmes conditions et est donc utilisé à la place : dès qu'un {@code TitleScreen} (ou
 * {@code JoinMultiplayerScreen}) termine son propre {@code init()}, on demande immédiatement
 * {@code Minecraft.setScreen(new CraftLabTitleScreen())}. Le bref écran vanilla qui vient de
 * s'initialiser n'est jamais rendu (la substitution se produit avant la prochaine frame) : le
 * résultat visible par le joueur est identique à un remplacement direct sur {@code Opening}.
 *
 * Enregistrée manuellement (instance) sur {@code MinecraftForge.EVENT_BUS} depuis
 * {@link ClientModEvents#onClientSetup}, via {@code FMLClientSetupEvent.enqueueWork(...)} pour
 * s'exécuter sur le thread principal une fois la phase de setup parallèle terminée.
 * {@code ClientModEvents} porte déjà sa propre restriction {@code Dist.CLIENT} : cette classe
 * n'est donc jamais chargée sur le serveur dédié puisqu'elle n'est référencée que depuis là.
 *
 * Depuis que le launcher n'utilise plus Quick Play pour le lancement normal (voir
 * MinecraftLauncher côté launcher), Minecraft crée systématiquement un {@code TitleScreen} au
 * démarrage (branche {@code else} de {@code Minecraft.buildInitialScreens} — la branche Quick
 * Play, qui appellerait {@code QuickPlay.connect(...)} à la place et sauterait tout écran,
 * n'est plus jamais empruntée) : cette classe intercepte donc bien ce {@code TitleScreen} à
 * chaque premier lancement, avant toute connexion.
 *
 * Deux écrans sont interceptés, tous deux vérifiés directement dans les sources Mojang/Forge
 * 1.21.1 pour éviter toute hypothèse :
 * <ul>
 *   <li>{@code TitleScreen} — le tout premier écran au démarrage, et le retour au menu quand
 *       {@code Minecraft.setScreen(null)} est appelé sans niveau chargé (ex. quitter une
 *       partie locale) — voir {@code Minecraft.setScreen}.</li>
 *   <li>{@code JoinMultiplayerScreen} — la liste de serveurs vanilla. INDISPENSABLE, pas
 *       seulement défensif : {@code PauseScreen.onDisconnect()} appelle explicitement
 *       {@code this.minecraft.setScreen(new JoinMultiplayerScreen(titlescreen))} quand le
 *       joueur clique "Déconnecter" en jeu (serveur non-Realm) — sans cette interception, une
 *       déconnexion volontaire depuis le menu pause afficherait le menu multijoueur vanilla au
 *       lieu de {@code CraftLabTitleScreen}.</li>
 * </ul>
 *
 * Aucun risque de boucle : {@code CraftLabTitleScreen} étend {@code Screen} directement (pas
 * {@code TitleScreen}), donc {@code instanceof TitleScreen} et
 * {@code instanceof JoinMultiplayerScreen} sont tous deux faux pour notre propre écran — la
 * substitution ne peut jamais se redéclencher sur elle-même, y compris quand sa PROPRE
 * {@code Init.Post} se déclenche (ex. redimensionnement de la fenêtre).
 *
 * Le bouton "Jouer" de {@code CraftLabTitleScreen} passe {@code this} (pas un nouveau
 * {@code TitleScreen}) comme écran parent à {@code ConnectScreen.startConnecting(...)} : en cas
 * d'erreur de connexion ou de déconnexion ultérieure côté serveur (kick, perte réseau),
 * {@code ClientCommonPacketListenerImpl.createDisconnectScreen} réutilise directement ce même
 * {@code postDisconnectScreen} — le joueur revient donc sur l'instance exacte de
 * {@code CraftLabTitleScreen} depuis laquelle il avait cliqué "Jouer", sans même transiter par
 * les écrans interceptés ici.
 */
@OnlyIn(Dist.CLIENT)
public final class ClientForgeEvents {

    ClientForgeEvents() {
    }

    @SubscribeEvent
    public void onScreenInitPost(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof TitleScreen || event.getScreen() instanceof JoinMultiplayerScreen) {
            Minecraft.getInstance().setScreen(new CraftLabTitleScreen());
        }
    }
}
