package com.atina.jdeMCPServer.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AtinaSessionSourceTest {

    @Test
    void vacioOnulo_default_claim() {
        assertEquals(AtinaSessionSource.CLAIM, AtinaSessionSource.parse(null));
        assertEquals(AtinaSessionSource.CLAIM, AtinaSessionSource.parse(""));
        assertEquals(AtinaSessionSource.CLAIM, AtinaSessionSource.parse("  "));
    }

    @Test
    void admiteGuionesYMayusculas() {
        assertEquals(AtinaSessionSource.VAULT, AtinaSessionSource.parse("vault"));
        assertEquals(AtinaSessionSource.CLAIM_THEN_VAULT, AtinaSessionSource.parse("claim-then-vault"));
        assertEquals(AtinaSessionSource.VAULT_THEN_CLAIM, AtinaSessionSource.parse("  Vault-Then-Claim "));
    }

    @Test
    void valorInvalido_falla() {
        assertThrows(IllegalArgumentException.class, () -> AtinaSessionSource.parse("openbao"));
    }
}
