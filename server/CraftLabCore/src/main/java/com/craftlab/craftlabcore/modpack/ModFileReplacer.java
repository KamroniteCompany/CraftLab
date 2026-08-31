package com.craftlab.craftlabcore.modpack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Déplace un fichier stagé vers son emplacement final dans /mods, avec un contournement Windows
 * pour le cas d'un fichier cible verrouillé : celui de CraftLabCore lui-même reste ouvert par la
 * JVM tant que ce code — qui vit dans ce jar — s'exécute (confirmé : un simple remplacement en
 * place échoue avec AccessDeniedException, alors qu'un renommage du même fichier réussit, Windows
 * n'exigeant pas les mêmes droits de partage pour les deux opérations). Les mods tiers ordinaires
 * (ex. BlankMod) ne sont pas concernés — leur jar n'est généralement pas gardé ouvert par la JVM
 * une fois chargé.
 *
 * Extrait de ModPackApplier dans sa propre classe (aucun changement de comportement) : cette
 * logique est purement basée sur java.nio.file, sans aucune dépendance à ModRegistry ou à
 * FMLPaths, contrairement à ModPackApplier lui-même (singleton à initialisation statique
 * immédiate, qui résout FMLPaths.CONFIGDIR dès le chargement de la classe) — elle reste donc
 * testable indépendamment de tout environnement Forge/FML.
 */
final class ModFileReplacer {

    private ModFileReplacer() {
    }

    static void moveIntoMods(Path stagedFile, Path finalFile) throws IOException {
        try {
            Files.move(stagedFile, finalFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return;
        } catch (IOException atomicFailure) {
            // AtomicMoveNotSupportedException (systèmes de fichiers différents) ou toute autre
            // IOException (ex. AccessDeniedException sur fichier verrouillé) : on retente avant
            // d'abandonner.
        }
        try {
            Files.move(stagedFile, finalFile, StandardCopyOption.REPLACE_EXISTING);
            return;
        } catch (IOException replaceFailure) {
            if (!Files.exists(finalFile)) {
                throw replaceFailure;
            }
        }

        // Le fichier cible existe et n'a pu être remplacé en place — probablement verrouillé.
        // Windows autorise le RENOMMAGE d'un fichier ouvert même quand son remplacement de
        // contenu est refusé : on le déplace donc hors du chemin final avant d'y déplacer le
        // fichier stagé.
        Path displaced = finalFile.resolveSibling(finalFile.getFileName() + ".replaced-" + System.currentTimeMillis());
        Files.move(finalFile, displaced);
        try {
            Files.move(stagedFile, finalFile);
        } catch (IOException moveNewFailure) {
            // Filet local : sans ceci, un échec ici laisserait /mods sans AUCUN fichier pour ce
            // modId. Le rollback global (ModPackApplier.apply -> rollbackInternal) ne comblerait
            // pas ce trou pour une entrée nouvellement ajoutée : ModPackBackupManager ne
            // sauvegarde que les fichiers déjà présents dans l'ANCIEN manifest, donc jamais un
            // fichier qui vient tout juste d'être ajouté par cette même application.
            try {
                Files.move(displaced, finalFile);
            } catch (IOException restoreFailure) {
                moveNewFailure.addSuppressed(restoreFailure);
            }
            throw moveNewFailure;
        }
        try {
            Files.deleteIfExists(displaced);
        } catch (IOException ignored) {
            // Best-effort : ce fichier renommé (extension différente de .jar) n'est jamais
            // rechargé par Forge et n'a donc aucun effet s'il subsiste jusqu'au prochain
            // redémarrage, qui pourra le nettoyer une fois le verrou relâché.
        }
    }
}
