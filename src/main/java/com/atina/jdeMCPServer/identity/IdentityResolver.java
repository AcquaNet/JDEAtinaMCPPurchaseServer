package com.atina.jdeMCPServer.identity;

/**
 * Traduce el sub de Keycloak autenticado en una identidad JDE.
 *
 * La implementación activa se selecciona por configuración
 * (property jde.identity.resolver), no por código:
 *   - "native"    -> NativeMappingResolver (tabla identity_mapping)
 *   - "federated" -> FederatedAttributeResolver (LDAP/AD, futuro)
 */
public interface IdentityResolver {

    /**
     * @throws UnmappedIdentityException si el sub no tiene un mapeo JDE
     */
    JdeIdentity resolve(String keycloakSub);
}
