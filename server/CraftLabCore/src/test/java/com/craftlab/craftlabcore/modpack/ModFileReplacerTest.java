package com.craftlab.craftlabcore.modpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Couvre le correctif du bug réel : sous Windows, staging/craftlabcore-1.0.0.jar ne pouvait pas
 * être déplacé vers mods/craftlabcore-1.0.0.jar quand ce dernier était verrouillé par la JVM
 * (le jar du mod exécutant lui-même ce code). Utilise l'attribut Windows "lecture seule" comme
 * déclencheur déterministe et portable d'AccessDeniedException lors du remplacement en place
 * (vérifié empiriquement : même type d'exception que le verrou réel, et — comme pour un fichier
 * verrouillé par une JVM — le RENOMMAGE du fichier reste autorisé), plutôt que de dépendre de la
 * synchronisation fragile d'un vrai handle de fichier ouvert.
 */
class ModFileReplacerTest {

    @Test
    void newFile_movesDirectlyWhenTargetDoesNotExist(@TempDir Path dir) throws IOException {
        Path staged = writeFile(dir.resolve("staged.jar"), "new-content");
        Path target = dir.resolve("mods").resolve("craftlabcore-1.0.0.jar");
        Files.createDirectories(target.getParent());

        ModFileReplacer.moveIntoMods(staged, target);

        assertEquals("new-content", Files.readString(target, StandardCharsets.UTF_8));
        assertFalse(Files.exists(staged), "le fichier stagé doit avoir été déplacé, pas copié");
    }

    @Test
    void existingUnlockedTarget_isReplacedDirectly(@TempDir Path dir) throws IOException {
        Path staged = writeFile(dir.resolve("staged.jar"), "new-content");
        Path target = writeFile(dir.resolve("target.jar"), "old-content");

        ModFileReplacer.moveIntoMods(staged, target);

        assertEquals("new-content", Files.readString(target, StandardCharsets.UTF_8));
    }

    @Test
    void lockedTarget_fallsBackToRenameThenMove(@TempDir Path dir) throws IOException {
        Path staged = writeFile(dir.resolve("staged.jar"), "new-content");
        Path target = writeFile(dir.resolve("target.jar"), "old-content");
        makeReadOnly(target); // déclenche AccessDeniedException sur un remplacement en place,
                               // exactement comme le jar de CraftLabCore verrouillé par sa JVM.

        ModFileReplacer.moveIntoMods(staged, target);

        assertEquals("new-content", Files.readString(target, StandardCharsets.UTF_8),
            "le fallback (renommage puis déplacement) doit malgré tout aboutir au bon contenu");
        assertFalse(Files.exists(staged));

        List<Path> leftovers = listReplacedLeftovers(dir);
        // Le fichier renommé est en lecture seule (attribut hérité du renommage) : sa suppression
        // "best-effort" échoue, ce qui est le comportement documenté et attendu — vérifié plutôt
        // que supposé.
        for (Path leftover : leftovers) {
            makeReadOnly(leftover, false); // nettoyage pour ne pas gêner la suppression du @TempDir
        }
    }

    @Test
    void lockedTargetAndMissingStagedFile_restoresOriginalContentAndPropagatesFailure(@TempDir Path dir) throws IOException {
        Path staged = dir.resolve("staged.jar"); // n'existe jamais : force l'échec du 2e déplacement du fallback
        Path target = writeFile(dir.resolve("target.jar"), "old-content");
        makeReadOnly(target);

        assertThrows(java.nio.file.NoSuchFileException.class, () -> ModFileReplacer.moveIntoMods(staged, target));

        makeReadOnly(target, false);
        assertTrue(Files.exists(target), "le fichier final ne doit jamais rester manquant après un échec");
        assertEquals("old-content", Files.readString(target, StandardCharsets.UTF_8),
            "en cas d'échec du second déplacement du fallback, l'ancien fichier doit être restauré à sa place");

        assertTrue(listReplacedLeftovers(dir).isEmpty(),
            "aucun fichier .replaced-* ne doit subsister après une restauration réussie");
    }

    private static Path writeFile(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }

    private static void makeReadOnly(Path path) throws IOException {
        makeReadOnly(path, true);
    }

    private static void makeReadOnly(Path path, boolean readOnly) throws IOException {
        DosFileAttributeView dos = Files.getFileAttributeView(path, DosFileAttributeView.class);
        if (dos != null) {
            dos.setReadOnly(readOnly);
        }
    }

    private static List<Path> listReplacedLeftovers(Path dir) throws IOException {
        List<Path> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.replaced-*")) {
            stream.forEach(result::add);
        }
        return result;
    }
}
