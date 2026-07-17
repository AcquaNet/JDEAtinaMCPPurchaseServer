package com.atina.jdeMCPServer.vault;

/**
 * Credencial real de JDE leída del vault. Nunca se persiste ni se loguea.
 */
public record JdeCredential(String user, String password) {

    @Override
    public String toString() {
        // Evita que la contraseña termine en logs por un toString accidental
        return "JdeCredential[user=" + user + ", password=****]";
    }
}
