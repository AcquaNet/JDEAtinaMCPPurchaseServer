package com.atina.jdeMCPServer.vault;

/**
 * El vault respondió pero no existe secret para el usuario JDE pedido.
 */
public class VaultCredentialNotFoundException extends RuntimeException {

    public VaultCredentialNotFoundException(String jdeUser) {
        super("No existe credencial en el vault para el usuario JDE [" + jdeUser + "] " +
                "(path esperado: secret/data/jde/" + jdeUser + ").");
    }
}
