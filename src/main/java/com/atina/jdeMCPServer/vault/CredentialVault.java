package com.atina.jdeMCPServer.vault;

/**
 * Abstracción del vault externo donde vive la credencial JDE cifrada.
 * Ni Keycloak ni este servidor almacenan contraseñas JDE.
 */
public interface CredentialVault {

    /**
     * @throws VaultCredentialNotFoundException si no existe secret para el usuario
     * @throws VaultUnavailableException        si el vault no responde o rechaza el acceso
     */
    JdeCredential getCredential(String jdeUser);
}
