package com.atina.jdeMCPServer.identity;

/**
 * El sub de Keycloak autenticado no tiene un usuario JDE asociado.
 * Debe traducirse en un mensaje claro para el usuario final,
 * no en un 500 genérico.
 */
public class UnmappedIdentityException extends RuntimeException {

    public UnmappedIdentityException(String keycloakSub) {
        super("El usuario Keycloak [" + keycloakSub + "] no tiene un usuario JDE asociado todavía. " +
                "Solicitá el alta del mapeo en identity_mapping.");
    }
}
