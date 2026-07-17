package com.atina.jdeMCPServer.identity;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stub para cuando Keycloak federe contra LDAP/AD.
 *
 * La idea: en modo federado el mapeo a JDE no sale de la tabla local sino de
 * atributos del directorio (o de claims que Keycloak propague desde LDAP).
 * Activarlo debe ser un cambio de configuración (jde.identity.resolver=federated),
 * no una reescritura.
 *
 * TODO: implementar cuando se active la federación LDAP/AD.
 */
@Component
@ConditionalOnProperty(name = "jde.identity.resolver", havingValue = "federated")
public class FederatedAttributeResolver implements IdentityResolver {

    @Override
    public JdeIdentity resolve(String keycloakSub) {
        throw new UnsupportedOperationException(
                "FederatedAttributeResolver no está implementado todavía. " +
                        "Usar jde.identity.resolver=native.");
    }
}
