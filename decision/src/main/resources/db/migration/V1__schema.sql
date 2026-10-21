-- Brightpath decision database schema. The decision tier resolves acting
-- users, roles, scopes and limits from ITS OWN database on every request.

CREATE TABLE principal (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    email VARCHAR(255),
    display_name VARCHAR(120),
    role VARCHAR(32) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE principal_session (
    id UUID PRIMARY KEY,
    principal_id BIGINT NOT NULL REFERENCES principal(id) ON DELETE CASCADE,
    issued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ
);
CREATE INDEX idx_psession_principal ON principal_session(principal_id);

CREATE TABLE principal_scope (
    id BIGSERIAL PRIMARY KEY,
    principal_id BIGINT NOT NULL REFERENCES principal(id) ON DELETE CASCADE,
    course_id BIGINT NOT NULL,
    scope_role VARCHAR(24) NOT NULL,
    UNIQUE (principal_id, course_id, scope_role)
);
CREATE INDEX idx_pscope_principal ON principal_scope(principal_id);

CREATE TABLE principal_limit (
    id BIGSERIAL PRIMARY KEY,
    principal_id BIGINT NOT NULL REFERENCES principal(id) ON DELETE CASCADE,
    limit_key VARCHAR(48) NOT NULL,
    limit_value VARCHAR(48) NOT NULL,
    UNIQUE (principal_id, limit_key)
);
