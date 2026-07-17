package com.atina.jdeMCPServer.vault;

/**
 * Falla de infraestructura del vault (no responde, token inválido, error 5xx).
 * Distinta de una credencial JDE inválida: son capas diferentes y no deben
 * verse iguales en logs ni en el error devuelto al usuario.
 */
public class VaultUnavailableException extends RuntimeException {

    public VaultUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public VaultUnavailableException(String message) {
        super(message);
    }
}
