# Installer CraftLab (guide joueur)

Aucune connaissance technique requise. Vous n'avez pas besoin d'installer Java, Minecraft, Forge
ou les mods séparément — le CraftLab Launcher s'occupe de tout.

## 1. Télécharger l'installeur

Téléchargez `CraftLab Launcher-X.Y.Z.exe` depuis la page des releases du projet.

## 2. Installer

Double-cliquez sur le fichier téléchargé. L'installation :
- ne nécessite **pas** de droits administrateur (installation pour votre compte utilisateur
  uniquement) ;
- crée un raccourci sur le Bureau et dans le menu Démarrer ;
- installe le launcher dans un dossier dédié — vos futures parties, mods téléchargés et réglages
  seront stockés séparément, dans votre dossier utilisateur, jamais mélangés avec une éventuelle
  autre installation de Minecraft.

## 3. Lancer CraftLab

Double-cliquez sur le raccourci **CraftLab Launcher** (Bureau ou menu Démarrer).

À l'ouverture, le launcher vérifie automatiquement :
- si Minecraft et Forge sont déjà installés (sinon, il les télécharge) ;
- si tous les mods du serveur sont à jour (sinon, il télécharge ce qui manque).

La première ouverture peut prendre quelques minutes (téléchargement de Minecraft/Forge). Les
ouvertures suivantes sont quasi instantanées si rien n'a changé côté serveur.

## 4. Jouer

Une fois le statut affiché "Prêt à jouer", cliquez sur **Jouer**. Minecraft démarre avec Forge
et tous les mods du serveur déjà installés — vous n'avez rien d'autre à faire.

## 5. L'écran CraftLab

Minecraft s'ouvre sur un écran d'accueil CraftLab avec quatre boutons :

| Bouton | Ce qu'il fait |
|---|---|
| **Jouer** | Se connecte au serveur CraftLab |
| **Mods** | Affiche la liste des mods actuellement chargés |
| **Paramètres** | Réglages Minecraft habituels (vidéo, son, contrôles) |
| **Quitter** | Ferme le jeu |

Le jeu ne se connecte **jamais** automatiquement au serveur : la connexion n'a lieu qu'au moment
précis où vous cliquez sur "Jouer" depuis cet écran.

## Si quelque chose ne fonctionne pas

- **Le launcher ne s'ouvre pas du tout** : vérifiez qu'aucun antivirus n'a bloqué le
  fichier — l'installeur n'étant pas signé numériquement, Windows peut afficher un avertissement
  "éditeur inconnu" à l'installation ; c'est normal pour cette version, cliquez sur "Informations
  complémentaires" puis "Exécuter quand même".
- **Le launcher reste bloqué sur "Vérification..."** : vérifiez votre connexion internet — le
  launcher a besoin d'accéder à GitHub pour vérifier les mods.
- **Minecraft ne démarre pas après avoir cliqué "Jouer"** : consultez le journal affiché dans le
  launcher (zone de texte en bas de la fenêtre), qui indique la raison précise de l'échec.
- **Vous voulez désinstaller** : Paramètres Windows → Applications → CraftLab Launcher →
  Désinstaller. Cela ne supprime **pas** vos mondes/paramètres/mods téléchargés (conservés dans
  votre dossier utilisateur) — seul le launcher lui-même est retiré.

## Ce que l'installeur ne fait jamais

- Il ne touche jamais à une installation Minecraft/Forge que vous auriez déjà par ailleurs.
- Il ne modifie jamais de fichiers en dehors de son propre dossier d'installation et de votre
  dossier utilisateur.
- Il ne demande jamais votre mot de passe Microsoft/Mojang (authentification hors-ligne pour
  l'instant — un simple pseudo).
