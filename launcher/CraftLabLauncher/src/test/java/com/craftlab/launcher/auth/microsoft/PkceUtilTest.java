package com.craftlab.launcher.auth.microsoft;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PkceUtilTest {

    @Test
    void codeVerifier_hasValidLengthPerRfc7636() {
        String verifier = PkceUtil.generateCodeVerifier();
        assertTrue(verifier.length() >= 43 && verifier.length() <= 128,
            "RFC 7636 exige 43-128 caractères, obtenu " + verifier.length());
    }

    @Test
    void codeVerifier_isUniqueAcrossCalls() {
        assertNotEquals(PkceUtil.generateCodeVerifier(), PkceUtil.generateCodeVerifier());
    }

    @Test
    void codeChallenge_isDeterministic_forTheSameVerifier() {
        String verifier = "test-verifier-fixe-pour-ce-test";
        assertEquals(PkceUtil.codeChallenge(verifier), PkceUtil.codeChallenge(verifier));
    }

    @Test
    void codeChallenge_matchesKnownRfc7636TestVector() {
        // Vecteur de test officiel de la RFC 7636, appendice B.
        String verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", PkceUtil.codeChallenge(verifier));
    }
}
