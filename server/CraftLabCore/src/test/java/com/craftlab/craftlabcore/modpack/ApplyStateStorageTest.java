package com.craftlab.craftlabcore.modpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * apply-state.txt est le marqueur qui permet à ModPackApplier.checkForInterruptedApply() de
 * détecter une application interrompue (crash pendant le remplacement des fichiers dans /mods)
 * au redémarrage suivant. Doit rester lisible même en son absence ou en cas de contenu corrompu.
 */
class ApplyStateStorageTest {

    @Test
    void noFileYet_defaultsToNotReady(@TempDir Path dir) {
        ApplyStateStorage storage = new ApplyStateStorage(dir.resolve("apply-state.txt"));

        assertEquals(ApplyState.NOT_READY, storage.load());
    }

    @Test
    void markApplying_isReadBackAsApplying_theCaseCheckForInterruptedApplyDetects(@TempDir Path dir) {
        ApplyStateStorage storage = new ApplyStateStorage(dir.resolve("apply-state.txt"));

        storage.markApplying();

        assertEquals(ApplyState.APPLYING, storage.load(),
            "un crash entre markApplying() et markApplied()/markFailed() doit rester détectable au redémarrage");
    }

    @Test
    void markAppliedThenFailed_eachOverwritesThePrevious(@TempDir Path dir) {
        ApplyStateStorage storage = new ApplyStateStorage(dir.resolve("apply-state.txt"));

        storage.markApplying();
        storage.markApplied();
        assertEquals(ApplyState.APPLIED, storage.load());

        storage.markFailed();
        assertEquals(ApplyState.FAILED, storage.load());
    }

    @Test
    void corruptedContent_isTreatedAsNotReadyInsteadOfThrowing(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("apply-state.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "GARBAGE_NOT_A_STATE", StandardCharsets.UTF_8);
        ApplyStateStorage storage = new ApplyStateStorage(file);

        assertEquals(ApplyState.NOT_READY, storage.load(),
            "un contenu illisible ne doit jamais faire planter checkForInterruptedApply() au démarrage");
    }
}
