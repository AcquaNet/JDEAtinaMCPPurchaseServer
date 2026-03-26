package com.atina.jdeMCPServer.auth;

/**
 * Se lanza cuando un tool intenta operar sin sesión JDE activa.
 * El mensaje está redactado para que Claude lo entienda y pueda
 * guiar al usuario a ejecutar jde_login.
 */
public class JdeSessionNotFoundException extends RuntimeException {
    public JdeSessionNotFoundException(String message) {
        super(message);
    }
}