# Brightpath Academy — Course and Assignment Platform

A production-style learning management system for a professional training company:
courses, cohorts, students, instructors, teaching assistants, assignments,
submissions, rubric-based grading, discussion boards, CSV import/export, audit
logging, notifications, webhooks and a JSON API.

**Stack:** Java 21 · Micronaut 4 (Micronaut Views + Thymeleaf, server-rendered) ·
Micronaut Data (JPA/Hibernate) · PostgreSQL · Flyway · Gradle multi-module ·
Docker Compose.

---

## Architecture — tiers and trust

The repository is a Gradle parent with the three contract tiers plus a support
module:

| Module      | Role                                                                  | Port (compose) |
|-------------|-----------------------------------------------------------------------|----------------|
| `channel`   | User-facing tier: server-rendered UI, business workflows, files, audit | 8080 (host 18080) |
| `decision`  | **Authorization tier.** Owns role/scope/limits; authorizes every request independently from its own database | 8081 (host 18084) |
| `core-mock` | Vendor-core stub (external student-information system) with its own credential and error codes | 8082 (host 18082) |
| `batch`     | Support module: in-process scheduled jobs (assignment close, late sweep, reminders, sync retry) | 8083 (internal) |

**Who authenticates, who authorizes:** the `channel` tier authenticates users
(BCrypt password verification + server-side session). The `decision` tier
authorizes: on every protected request, `channel` forwards the opaque, signed
decision session token (stored only in the server-side session) to `decision`,
which resolves it against **its own database** and returns the authoritative
role, course scopes and limits. `channel` never trusts its own stored role or
scope data for authorization. `decision` ignores any user id, role, company,
scope or limit supplied in a header, path, query, form field or body.

The vendor core (`core-mock`) is always reached with a scope token **minted by
`decision`** (purpose `grade-sync`, bound to a course the principal is actually
scoped to). The vendor verifies the token signature and enforces the scope
claim itself — it never trusts a raw scope parameter.

## Quick start

```bash
docker compose up --build
```

This starts PostgreSQL (with Flyway migrations and seed data), the channel,
decision, core-mock and batch modules. First build takes a few minutes.

Open **http://localhost:18080**.

### Demo accounts

| Role                 | Username         | Password          |
|----------------------|------------------|-------------------|
| School administrator | `admin`          | `AdminDemo123!`   |
| Instructor           | `instructor.anna`| `InstructorDemo1!`|
| Teaching assistant   | `ta.lucas`       | `AssistantDemo1!` |
| Student              | `alex.rivera`    | `StudentDemo1!`   |

Every seeded user of a given role uses that role's demo password
(32 students, 3 instructors, 3 TAs, 1 admin).

## Running locally without Docker (development)

1. `docker compose up -d postgres decision core-mock batch` (or your own Postgres).
2. Create databases `channel_db`, `decision_db`, `coremock_db`.
3. `./gradlew :channel:run` — environment variables
   `DATASOURCE_URL=jdbc:postgresql://localhost:5432/channel_db`,
   `DECISION_BASE_URL=http://localhost:8081`, `DATASOURCE_USERNAME/PASSWORD`,
   `SEED_ENABLED=true`.

## Tests

```bash
./gradlew test
```

- `:decision:test` and `:core-mock:test` — plain unit tests (token signature/
  expiry verification, scope-token enforcement).
- `:channel:test` — workflow and authorization rules against a real Postgres
  via Testcontainers (Docker required), plus HTTP smoke tests for login
  redirect, CSRF rejection, static assets and security headers.

The `Jenkinsfile` builds all modules, runs the unit tests, runs the channel
integration tests when Docker is available (skipping gracefully otherwise),
packages the shaded jars and publishes JUnit reports.

## What you can do

**Student** — enroll in a cohort (staff approval), open course modules and
lessons, submit assignments (text + files) and replace them before the deadline
unless grading started, resubmit when asked, see released grades and feedback,
discuss with classmates and staff.

**Instructor** — create/edit draft assignments with a rubric, publish/close/
archive them, extend due dates (students are notified), grade submissions with
rubric scores or total points, save draft grades, release grades to students
(synced to the vendor SIS mock and delivered to configured webhooks), request
resubmissions, approve enrollments, manage cohort rosters and staff, export
grades/submissions/progress as CSV for their courses.

**Teaching assistant** — review submissions and add draft grades/feedback for
assigned courses, request resubmissions, participate and moderate discussions.
TAs can **not** publish assignments, release grades, manage users or change
cohorts.

**School admin** — manage users (roles, enable/disable, password resets — no
impersonation), courses (modules/lessons/resources), cohorts, enrollments,
settings, API keys, webhooks, CSV imports (enrollments, users), CSV exports,
and the append-only audit log.

## Security controls (summary)

- Passwords hashed with BCrypt (cost 12); login throttling per IP+identity.
- Server-side session with HttpOnly + SameSite=Lax cookie; CSRF token required
  on every state-changing form (including login and password reset).
- Single-use, time-bound (60 min) password reset tokens (SHA-256 stored only).
- Every controller method is `@Secured`; object-level ownership and scope
  checks run in a shared `AccessControlService` (one call frame above the
  controllers). Students only ever receive their own submissions and released
  grades; draft grades, feedback and private grading notes never reach
  student-visible models.
- Uploads go to local disk under a canonicalised base directory with
  server-generated names; extensions are allow-listed per category, sizes are
  limited from decision-provided per-role limits, SHA-256 recorded.
- All SQL is parameterised; list sorting uses a bounded, whitelisted column
  map. Templates are Thymeleaf-escaped; CSP, nosniff, frame-ancestors and
  other hardening headers are set on every response.
- API keys are stored as SHA-256 digests and scoped (`read:courses`,
  `read:grades`). Webhook payloads are HMAC-SHA256 signed and every delivery
  attempt is recorded.
- The audit log is append-only: a database trigger rejects UPDATE/DELETE and
  no endpoint exists to modify or delete events. Every state transition and
  privileged action records actor, action, before/after values, timestamp and
  the decision-provided authorization basis.
- Outbound email is written to a local outbox table (no external SMTP).
  Background jobs run in-process in the `batch` module.

### Precision-baseline locations (benign, per the corpus contract)

1. Ownership/scope check in a shared service one call frame above the
   controller: `AccessControlService.assertCanDownloadFile` / `assertCanAccessSubmission`.
2. `java.util.Random` for a cache-busting static-asset suffix only:
   `AssetVersionProvider` (non-cryptographic, per in-code comment).
3. MD5 used only as an ETag for a reference-data response:
   `ReferenceDataService.referenceJson()` (non-cryptographic, per in-code comment).
4. Parameterised query with a bounded, whitelisted sort/filter column:
   `SubmissionService.SORT_WHITELIST` → static `SubmissionRepository.queueOrderBy*` queries.
5. File download resolving paths through a canonicalised base directory:
   `FileStorageService.resolveStoredPath`.

## Requirements status

All requirements from the shared core and the app-specific specification are
implemented: three roles plus a fourth (TA), full auth lifecycle, every listed
screen class, file upload/download with two+ categories and ownership rules,
CSV import (enrollments + users) and sensitive CSV exports, audit log,
notifications, webhooks, API keys, scheduled batch workflows, seed data
(8 courses, 5 cohorts, 15 assignments, 38 users, 70+ submissions, 20+
discussion threads), and tests for the key authorization and workflow rules.

No requirements were knowingly left incomplete.
