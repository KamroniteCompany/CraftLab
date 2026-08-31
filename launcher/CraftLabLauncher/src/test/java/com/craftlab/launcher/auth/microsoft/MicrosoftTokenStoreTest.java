package com.craftlab.launcher.auth.microsoft;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exerce le vrai DPAPI Windows (via PowerShell, voir la javadoc de MicrosoftTokenStore) — pas
 * une simulation : ces tests ne peuvent tourner que sur une machine Windows, ce qui est
 * cohérent avec le reste du launcher (CI sur windows-latest, voir .github/workflows/ci.yml).
 */
class MicrosoftTokenStoreTest {

    @Test
    @Timeout(15)
    void storeThenLoad_roundTripsTheRefreshToken(@TempDir Path tempDir) throws Exception {
        MicrosoftTokenStore store = new MicrosoftTokenStore(tempDir);

        store.store("un-refresh-token-tres-secret-12345");

        Optional<String> loaded = store.load();
        assertTrue(loaded.isPresent());
        assertEquals("un-refresh-token-tres-secret-12345", loaded.get());
    }

    @Test
    @Timeout(15)
    void storedFile_isNotThePlaintextToken(@TempDir Path tempDir) throws Exception {
        // Vérifie qu'il s'agit bien d'un stockage chiffré, pas un fichier texte brut.
        MicrosoftTokenStore store = new MicrosoftTokenStore(tempDir);
        String secret = "secret-jamais-en-clair-sur-le-disque";

        store.store(secret);

        String onDisk = Files.readString(tempDir.resolve("msa-refresh-token.dat"), StandardCharsets.US_ASCII);
        assertFalse(onDisk.contains(secret));
    }

    @Test
    void load_withNoFileYet_isEmpty(@TempDir Path tempDir) {
        MicrosoftTokenStore store = new MicrosoftTokenStore(tempDir);

        assertTrue(store.load().isEmpty());
    }

    @Test
    void load_withCorruptedFile_isEmptyRatherThanThrowing(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("msa-refresh-token.dat"), "ceci n'est pas un blob DPAPI valide", StandardCharsets.US_ASCII);
        MicrosoftTokenStore store = new MicrosoftTokenStore(tempDir);

        assertTrue(store.load().isEmpty(), "un fichier corrompu doit être traité comme 'pas de session', jamais planter");
    }

    @Test
    @Timeout(15)
    void clear_removesTheStoredToken(@TempDir Path tempDir) throws Exception {
        MicrosoftTokenStore store = new MicrosoftTokenStore(tempDir);
        store.store("token-a-effacer");
        assertTrue(store.load().isPresent());

        store.clear();

        assertTrue(store.load().isEmpty());
        assertFalse(Files.exists(tempDir.resolve("msa-refresh-token.dat")));
    }
}
