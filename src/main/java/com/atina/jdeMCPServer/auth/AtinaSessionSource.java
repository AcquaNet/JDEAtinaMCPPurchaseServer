package com.atina.jdeMCPServer.auth;

/**
 * Fuente del token de sesión JDE de Atina cuando el usuario se autentica con
 * Keycloak. Permite que la etapa 1 (claim "atina_token" en el JWT) y la etapa 2
 * (token guardado en OpenBao) convivan, eligiendo cuál se usa —o el orden de
 * fallback— vía la property {@code jde.atina.session-source}.
 */
public enum AtinaSessionSource {

    /** Solo el claim "atina_token" del JWT de Keycloak (etapa 1). */
    CLAIM,

    /** Solo el token guardado en OpenBao (etapa 2). */
    VAULT,

    /** Primero el claim; si no está, OpenBao. */
    CLAIM_THEN_VAULT,

    /** Primero OpenBao; si no está (o el vault falla), el claim. */
    VAULT_THEN_CLAIM;

    /**
     * Parsea el valor de la property (admite guiones, ej. "claim-then-vault").
     * Vacío/ausente = {@link #CLAIM} (comportamiento de la etapa 1, no rompe nada).
     *
     * @throws IllegalArgumentException si el valor no corresponde a ninguna fuente
     */
    public static AtinaSessionSource parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return CLAIM;
        }
        String normalized = raw.trim().toUpperCase().replace('-', '_');
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Valor inválido para jde.atina.session-source: '" + raw +
                            "'. Válidos: claim, vault, claim-then-vault, vault-then-claim.");
        }
    }
}
