package com.atina.jdeMCPServer.auth;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class JdeTokenStore {

    private final AtomicReference<String> currentToken = new AtomicReference<>();

    public Optional<String> getToken() {
        return Optional.ofNullable(currentToken.get());
    }

    public void setToken(String token) {
        this.currentToken.set(token);
    }

    public void clear() {
        this.currentToken.set(null);
    }
}