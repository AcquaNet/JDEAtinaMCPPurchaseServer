package com.atina.jdeMCPServer.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Resolución para usuarios nativos de Keycloak (alta manual):
 * lee la tabla identity_mapping por keycloak_sub.
 */
@Component
@ConditionalOnProperty(name = "jde.identity.resolver", havingValue = "native", matchIfMissing = true)
public class NativeMappingResolver implements IdentityResolver {

    private static final Logger log = LoggerFactory.getLogger(NativeMappingResolver.class);

    private final JdbcTemplate jdbcTemplate;

    public NativeMappingResolver(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public JdeIdentity resolve(String keycloakSub) {
        try {
            JdeIdentity identity = jdbcTemplate.queryForObject(
                    "SELECT jde_user, jde_environment, jde_role FROM identity_mapping WHERE keycloak_sub = ?",
                    (rs, rowNum) -> new JdeIdentity(
                            rs.getString("jde_user"),
                            rs.getString("jde_environment"),
                            rs.getString("jde_role")
                    ),
                    keycloakSub
            );
            log.debug("Sub [{}] resuelto a usuario JDE [{}]", keycloakSub, identity.jdeUser());
            return identity;
        } catch (EmptyResultDataAccessException e) {
            throw new UnmappedIdentityException(keycloakSub);
        }
    }
}
