-- Brightpath vendor core mock (SIS gradebook) schema.

CREATE TABLE gradebook_record (
    id BIGSERIAL PRIMARY KEY,
    sync_id VARCHAR(64),
    course_id BIGINT NOT NULL,
    assignment_id BIGINT NOT NULL,
    student_id BIGINT NOT NULL,
    points NUMERIC(6,2) NOT NULL,
    max_points NUMERIC(6,2) NOT NULL,
    released_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (course_id, assignment_id, student_id)
);
CREATE INDEX idx_gradebook_course ON gradebook_record(course_id);

CREATE TABLE webhook_received (
    id BIGSERIAL PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    payload_sha256 CHAR(64),
    received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
