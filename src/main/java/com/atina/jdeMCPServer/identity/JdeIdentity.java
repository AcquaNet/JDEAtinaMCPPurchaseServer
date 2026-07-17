package com.atina.jdeMCPServer.identity;

/**
 * Credencial lógica de JDE resuelta a partir de una identidad Keycloak.
 * No contiene la contraseña: esa vive en el vault (ver CredentialVault).
 */
public record JdeIdentity(String jdeUser, String jdeEnvironment, String jdeRole) {
}
