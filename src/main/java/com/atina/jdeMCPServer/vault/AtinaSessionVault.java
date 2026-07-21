package com.atina.jdeMCPServer.vault;

import java.util.Optional;

/**
 * Lectura del token de sesión JDE emitido por Atina y guardado en el vault
 * (etapa 2). A diferencia de {@link CredentialVault}, no devuelve una credencial
 * (usuario/password) sino directamente el token de sesión JDE, que se usa como
 * X-Approver-Token sin login previo contra Mulesoft.
 */
public interface AtinaSessionVault {

    /**
     * @param key clave del secreto (el {@code sub} de Keycloak del usuario autenticado)
     * @return el token si existe en el vault; vacío si no hay secreto para esa clave
     * @throws VaultUnavailableException si el vault no responde o rechaza el acceso
     */
    Optional<String> getAtinaSessionToken(String key);
}
