-- Brightpath channel schema v1

CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    role VARCHAR(32) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    bio TEXT,
    timezone VARCHAR(64) NOT NULL DEFAULT 'UTC',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_login_at TIMESTAMPTZ
);

CREATE TABLE password_reset_token (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash CHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_prt_user ON password_reset_token(user_id);

CREATE TABLE course (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(24) NOT NULL UNIQUE,
    title VARCHAR(200) NOT NULL,
    subtitle VARCHAR(200),
    description TEXT,
    syllabus TEXT,
    category VARCHAR(64) NOT NULL DEFAULT 'General',
    level VARCHAR(32) NOT NULL DEFAULT 'Intermediate',
    status VARCHAR(24) NOT NULL DEFAULT 'active',
    enrollment_open BOOLEAN NOT NULL DEFAULT TRUE,
    cover_color VARCHAR(16) NOT NULL DEFAULT 'indigo',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE course_module (
    id BIGSERIAL PRIMARY KEY,
    course_id BIGINT NOT NULL REFERENCES course(id) ON DELETE CASCADE,
    position INT NOT NULL,
    title VARCHAR(200) NOT NULL,
    description TEXT
);
CREATE INDEX idx_module_course ON course_module(course_id);

CREATE TABLE lesson (
    id BIGSERIAL PRIMARY KEY,
    module_id BIGINT NOT NULL REFERENCES course_module(id) ON DELETE CASCADE,
    course_id BIGINT NOT NULL REFERENCES course(id) ON DELETE CASCADE,
    position INT NOT NULL,
    title VARCHAR(200) NOT NULL,
    content_type VARCHAR(32) NOT NULL DEFAULT 'text',
    content TEXT,
    video_url VARCHAR(500),
    duration_minutes INT
);
CREATE INDEX idx_lesson_module ON lesson(module_id);

CREATE TABLE course_resource (
    id BIGSERIAL PRIMARY KEY,
    course_id BIGINT NOT NULL REFERENCES course(id) ON DELETE CASCADE,
    title VARCHAR(200) NOT NULL,
    kind VARCHAR(32) NOT NULL DEFAULT 'reading',
    external_url VARCHAR(500),
    file_id BIGINT,
    added_by BIGINT REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_resource_course ON course_resource(course_id);

CREATE TABLE cohort (
    id BIGSERIAL PRIMARY KEY,
    course_id BIGINT NOT NULL REFERENCES course(id) ON DELETE CASCADE,
    name VARCHAR(200) NOT NULL,
    schedule_summary VARCHAR(500),
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'upcoming',
    max_students INT NOT NULL DEFAULT 30,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_cohort_course ON cohort(course_id);

CREATE TABLE cohort_staff (
    id BIGSERIAL PRIMARY KEY,
    cohort_id BIGINT NOT NULL REFERENCES cohort(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    staff_role VARCHAR(24) NOT NULL,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (cohort_id, user_id)
);

CREATE TABLE enrollment (
    id BIGSERIAL PRIMARY KEY,
    cohort_id BIGINT NOT NULL REFERENCES cohort(id) ON DELETE CASCADE,
    student_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status VARCHAR(24) NOT NULL DEFAULT 'pending',
    enrolled_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    dropped_at TIMESTAMPTZ,
    UNIQUE (cohort_id, student_id)
);
CREATE INDEX idx_enrollment_student ON enrollment(student_id);

CREATE TABLE assignment (
    id BIGSERIAL PRIMARY KEY,
    course_id BIGINT NOT NULL REFERENCES course(id) ON DELETE CASCADE,
    cohort_id BIGINT REFERENCES cohort(id) ON DELETE SET NULL,
    title VARCHAR(200) NOT NULL,
    summary VARCHAR(500),
    instructions TEXT NOT NULL,
    due_at TIMESTAMPTZ NOT NULL,
    available_at TIMESTAMPTZ,
    allow_late BOOLEAN NOT NULL DEFAULT FALSE,
    max_points NUMERIC(6,2) NOT NULL,
    rubric JSONB NOT NULL DEFAULT '[]',
    allowed_file_types VARCHAR(255) NOT NULL DEFAULT 'pdf,docx,zip',
    max_files INT NOT NULL DEFAULT 3,
    status VARCHAR(24) NOT NULL DEFAULT 'draft',
    created_by BIGINT REFERENCES users(id),
    published_at TIMESTAMPTZ,
    closed_at TIMESTAMPTZ,
    archived_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_assignment_course ON assignment(course_id);
CREATE INDEX idx_assignment_status ON assignment(status);

CREATE TABLE submission (
    id BIGSERIAL PRIMARY KEY,
    assignment_id BIGINT NOT NULL REFERENCES assignment(id) ON DELETE CASCADE,
    student_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    attempt INT NOT NULL DEFAULT 1,
    status VARCHAR(24) NOT NULL DEFAULT 'submitted',
    submitted_at TIMESTAMPTZ,
    text_content TEXT,
    draft_grade NUMERIC(6,2),
    draft_feedback TEXT,
    draft_private_note TEXT,
    draft_graded_by BIGINT REFERENCES users(id),
    draft_graded_at TIMESTAMPTZ,
    released_grade NUMERIC(6,2),
    released_feedback TEXT,
    released_at TIMESTAMPTZ,
    released_by BIGINT REFERENCES users(id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (assignment_id, student_id, attempt)
);
CREATE INDEX idx_submission_assignment ON submission(assignment_id);
CREATE INDEX idx_submission_student ON submission(student_id);
CREATE INDEX idx_submission_status ON submission(status);

CREATE TABLE submission_file (
    id BIGSERIAL PRIMARY KEY,
    submission_id BIGINT NOT NULL REFERENCES submission(id) ON DELETE CASCADE,
    file_id BIGINT NOT NULL,
    added_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_subfile_submission ON submission_file(submission_id);

CREATE TABLE grade_change (
    id BIGSERIAL PRIMARY KEY,
    submission_id BIGINT NOT NULL REFERENCES submission(id) ON DELETE CASCADE,
    changed_by BIGINT NOT NULL REFERENCES users(id),
    change_type VARCHAR(32) NOT NULL,
    old_grade NUMERIC(6,2),
    new_grade NUMERIC(6,2),
    old_feedback TEXT,
    new_feedback TEXT,
    note TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_gradechange_submission ON grade_change(submission_id);

CREATE TABLE discussion_thread (
    id BIGSERIAL PRIMARY KEY,
    course_id BIGINT NOT NULL REFERENCES course(id) ON DELETE CASCADE,
    author_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title VARCHAR(200) NOT NULL,
    body TEXT NOT NULL,
    pinned BOOLEAN NOT NULL DEFAULT FALSE,
    locked BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_thread_course ON discussion_thread(course_id);

CREATE TABLE discussion_comment (
    id BIGSERIAL PRIMARY KEY,
    thread_id BIGINT NOT NULL REFERENCES discussion_thread(id) ON DELETE CASCADE,
    author_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    body TEXT NOT NULL,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_comment_thread ON discussion_comment(thread_id);

CREATE TABLE file_ref (
    id BIGSERIAL PRIMARY KEY,
    stored_name VARCHAR(64) NOT NULL UNIQUE,
    original_name VARCHAR(255) NOT NULL,
    mime_type VARCHAR(255),
    size_bytes BIGINT NOT NULL,
    sha256 CHAR(64),
    category VARCHAR(32) NOT NULL,
    owner_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    course_id BIGINT,
    assignment_id BIGINT,
    submission_id BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_fileref_owner ON file_ref(owner_id);
CREATE INDEX idx_fileref_submission ON file_ref(submission_id);

CREATE TABLE audit_event (
    id BIGSERIAL PRIMARY KEY,
    actor_id BIGINT,
    actor_username VARCHAR(64),
    actor_role VARCHAR(32),
    action VARCHAR(64) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    resource_id VARCHAR(64),
    before_state TEXT,
    after_state TEXT,
    authz_basis VARCHAR(200),
    ip_address VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_actor ON audit_event(actor_id);
CREATE INDEX idx_audit_resource ON audit_event(resource_type, resource_id);
CREATE INDEX idx_audit_created ON audit_event(created_at);

CREATE TABLE notification (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind VARCHAR(48) NOT NULL,
    title VARCHAR(200) NOT NULL,
    body TEXT,
    link VARCHAR(300),
    read_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_notification_user ON notification(user_id, read_at);

CREATE TABLE email_outbox (
    id BIGSERIAL PRIMARY KEY,
    to_address VARCHAR(255) NOT NULL,
    subject VARCHAR(300) NOT NULL,
    body_text TEXT NOT NULL,
    template_key VARCHAR(64),
    status VARCHAR(16) NOT NULL DEFAULT 'pending',
    sent_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE webhook_endpoint (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    url VARCHAR(500) NOT NULL,
    secret VARCHAR(200) NOT NULL,
    event_types VARCHAR(255) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by BIGINT REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE webhook_delivery (
    id BIGSERIAL PRIMARY KEY,
    endpoint_id BIGINT NOT NULL REFERENCES webhook_endpoint(id) ON DELETE CASCADE,
    event_type VARCHAR(64) NOT NULL,
    payload_sha256 CHAR(64),
    response_code INT,
    success BOOLEAN NOT NULL DEFAULT FALSE,
    attempt_count INT NOT NULL DEFAULT 1,
    error_message VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE api_key (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    prefix VARCHAR(16) NOT NULL,
    key_hash CHAR(64) NOT NULL,
    scopes VARCHAR(255) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by BIGINT REFERENCES users(id),
    last_used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE import_job (
    id BIGSERIAL PRIMARY KEY,
    kind VARCHAR(32) NOT NULL,
    file_id BIGINT,
    status VARCHAR(24) NOT NULL DEFAULT 'pending',
    total_rows INT NOT NULL DEFAULT 0,
    ok_rows INT NOT NULL DEFAULT 0,
    error_rows INT NOT NULL DEFAULT 0,
    error_report TEXT,
    created_by BIGINT REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE grade_sync_event (
    id BIGSERIAL PRIMARY KEY,
    course_id BIGINT NOT NULL,
    submission_ids TEXT NOT NULL,
    sync_id VARCHAR(64),
    status VARCHAR(24) NOT NULL DEFAULT 'pending',
    response_text VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE settings (
    key VARCHAR(64) PRIMARY KEY,
    value TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE grading_scale (
    id BIGSERIAL PRIMARY KEY,
    letter VARCHAR(4) NOT NULL,
    min_percent NUMERIC(5,2) NOT NULL,
    gpa NUMERIC(3,2) NOT NULL
);
