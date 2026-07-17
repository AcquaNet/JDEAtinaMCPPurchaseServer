-- Mapeo identidad Keycloak -> credencial JDE (Identity Bridge)
-- Se puebla a mano por ahora (alta manual de usuarios)

CREATE TABLE identity_mapping (
    keycloak_sub    VARCHAR(255) PRIMARY KEY,
    source_mode     VARCHAR(20)  NOT NULL DEFAULT 'NATIVE', -- NATIVE | FEDERATED
    jde_user        VARCHAR(50)  NOT NULL,
    jde_environment VARCHAR(50)  NOT NULL,
    jde_role        VARCHAR(50),
    created_at      TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX idx_identity_mapping_jde_user ON identity_mapping (jde_user);
