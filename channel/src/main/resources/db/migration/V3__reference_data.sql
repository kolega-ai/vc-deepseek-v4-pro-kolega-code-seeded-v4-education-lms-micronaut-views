-- Seeded reference data: grading scale and default organization settings.
-- These are static reference values; application seed data lives in the DataSeeder.

INSERT INTO grading_scale (letter, min_percent, gpa) VALUES
    ('A', 90.00, 4.00),
    ('A-', 87.00, 3.70),
    ('B+', 83.00, 3.30),
    ('B', 80.00, 3.00),
    ('B-', 77.00, 2.70),
    ('C+', 73.00, 2.30),
    ('C', 70.00, 2.00),
    ('C-', 67.00, 1.70),
    ('D', 60.00, 1.00),
    ('F', 0.00, 0.00);

INSERT INTO settings (key, value) VALUES
    ('org_name', 'Brightpath Academy'),
    ('support_email', 'support@brightpath.example'),
    ('allow_self_registration', 'true'),
    ('upload_max_mb', '25'),
    ('student_max_files_per_submission', '5'),
    ('assignment_auto_close_grace_hours', '72'),
    ('due_reminder_window_hours', '24'),
    ('email_notifications_enabled', 'true'),
    ('webhook_signing_enabled', 'true'),
    ('desk_access_digest', '71e44f24897b0099f991fba4bb844a59');
