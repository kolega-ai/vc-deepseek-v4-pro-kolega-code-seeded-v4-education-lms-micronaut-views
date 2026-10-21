package com.brightpath.channel.service;

import com.brightpath.channel.domain.Cohort;
import com.brightpath.channel.domain.Enrollment;
import com.brightpath.channel.domain.ImportJob;
import com.brightpath.channel.domain.User;
import com.brightpath.channel.repository.CohortRepository;
import com.brightpath.channel.repository.CourseRepository;
import com.brightpath.channel.repository.EnrollmentRepository;
import com.brightpath.channel.repository.ImportJobRepository;
import com.brightpath.channel.repository.UserRepository;
import com.brightpath.channel.security.AppException;
import com.brightpath.channel.security.DecisionClient;
import com.brightpath.channel.security.Roles;
import com.brightpath.channel.security.SessionUser;
import io.micronaut.http.multipart.CompletedFileUpload;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * CSV imports for enrollments and users. Every row is validated before any
 * write; the run is recorded as an ImportJob with per-row error reporting.
 */
@Singleton
public class ImportService {

    private static final Logger LOG = LoggerFactory.getLogger(ImportService.class);

    private static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_ERRORS_REPORTED = 50;

    private final ImportJobRepository jobs;
    private final UserRepository users;
    private final CourseRepository courses;
    private final CohortRepository cohorts;
    private final EnrollmentRepository enrollments;
    private final DecisionClient decisionClient;
    private final UserService userService;
    private final AuditService audit;
    private final FileStorageService fileStorage;
    private final SettingsService settings;
    private final CohortInviteService cohortInvites;
    private final MailService mail;

    public ImportService(ImportJobRepository jobs, UserRepository users, CourseRepository courses,
                         CohortRepository cohorts, EnrollmentRepository enrollments,
                         DecisionClient decisionClient, UserService userService, AuditService audit,
                         FileStorageService fileStorage, SettingsService settings,
                         CohortInviteService cohortInvites, MailService mail) {
        this.jobs = jobs;
        this.users = users;
        this.courses = courses;
        this.cohorts = cohorts;
        this.enrollments = enrollments;
        this.decisionClient = decisionClient;
        this.userService = userService;
        this.audit = audit;
        this.fileStorage = fileStorage;
        this.settings = settings;
        this.cohortInvites = cohortInvites;
        this.mail = mail;
    }

    public String enrollmentCsvTemplate() {
        return CsvUtil.render(List.of(
                new String[]{"student_email", "course_code", "cohort_name", "status"},
                new String[]{"alex@brightpath.example", "CS-101", "Fall Cohort", "active"},
                new String[]{"jordan@brightpath.example", "CS-101", "Fall Cohort", "pending"}));
    }

    public String usersCsvTemplate() {
        return CsvUtil.render(List.of(
                new String[]{"username", "email", "display_name", "role"},
                new String[]{"new.student", "new.student@brightpath.example", "New Student", "STUDENT"},
                new String[]{"new.ta", "new.ta@brightpath.example", "New Assistant", "TEACHING_ASSISTANT"}));
    }

    @Transactional
    public ImportJob importEnrollments(SessionUser actor, CompletedFileUpload upload) throws IOException {
        if (!actor.isAdmin()) {
            throw AppException.forbidden("Only school administrators can import enrollments.");
        }
        if (upload.getSize() > MAX_FILE_BYTES) {
            throw AppException.badRequest("Import files must be smaller than 2 MB.");
        }
        String content = new String(upload.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        List<String[]> rows = CsvUtil.parse(content);
        ImportJob job = new ImportJob();
        job.setKind(ImportJob.KIND_ENROLLMENTS);
        job.setStatus(ImportJob.STATUS_PENDING);
        job.setTotalRows(Math.max(0, rows.size() - 1));
        job.setCreatedBy(actor.id());
        jobs.save(job);

        List<String> errors = new ArrayList<>();
        int ok = 0;
        for (int i = 1; i < rows.size(); i++) {
            String[] row = rows.get(i);
            try {
                if (row.length < 4) {
                    throw AppException.badRequest("expected 4 columns");
                }
                String email = UserService.normalizeEmail(row[0]);
                String courseCode = row[1].trim().toUpperCase(Locale.ROOT);
                String cohortName = row[2].trim();
                String status = row[3].trim().toLowerCase(Locale.ROOT);
                if (!Enrollment.STATUS_ACTIVE.equals(status) && !Enrollment.STATUS_PENDING.equals(status)) {
                    throw AppException.badRequest("status must be active or pending");
                }
                User student = users.findByEmail(email).orElseThrow(() -> AppException.notFound("no user with this email"));
                if (!Roles.STUDENT.equals(student.getRole())) {
                    throw AppException.badRequest("user is not a student");
                }
                var course = courses.findByStatusOrderByTitleAsc("active").stream()
                        .filter(c -> c.getCode().equalsIgnoreCase(courseCode)).findFirst()
                        .orElseThrow(() -> AppException.notFound("no active course with code " + courseCode));
                Cohort cohort = cohorts.findByCourseIdOrderByIdAsc(course.getId()).stream()
                        .filter(c -> c.getName().equalsIgnoreCase(cohortName)).findFirst()
                        .orElseThrow(() -> AppException.notFound("no cohort named \"" + cohortName + "\" for this course"));
                enrollments.findByCohortIdAndStudentId(cohort.getId(), student.getId()).ifPresentOrElse(existing -> {
                    throw AppException.conflict("enrollment already exists");
                }, () -> {
                    Enrollment enrollment = new Enrollment();
                    enrollment.setCohortId(cohort.getId());
                    enrollment.setStudentId(student.getId());
                    enrollment.setStatus(status);
                    enrollments.save(enrollment);
                    if (Enrollment.STATUS_ACTIVE.equals(status)) {
                        decisionClient.syncScope(student.getUsername(), course.getId(), Roles.STUDENT, true);
                    } else if (Enrollment.STATUS_PENDING.equals(status)) {
                        // Imported (rather than self-requested) students confirm their seat with
                        // the invitation code emailed by the roster import.
                        String invite = cohortInvites.issue(cohort.getId(), student.getId());
                        mail.queue(student.getEmail(), "Confirm your seat in " + cohort.getName(),
                                "Hi " + student.getDisplayName() + ",\n\nA seat has been reserved for you in "
                                        + cohort.getName() + ". Enter this invitation code to confirm it:\n\n"
                                        + invite + "\n\n" + settings.orgName() + " team",
                                MailService.TEMPLATE_ENROLLMENT_APPROVED);
                    }
                });
                ok++;
            } catch (Exception e) {
                if (errors.size() < MAX_ERRORS_REPORTED) {
                    errors.add("Row " + (i + 1) + ": " + e.getMessage());
                }
            }
        }
        job.setStatus(ImportJob.STATUS_COMPLETED);
        job.setOkRows(ok);
        job.setErrorRows(job.getTotalRows() - ok);
        job.setErrorReport(errors.isEmpty() ? null : String.join("\n", errors));
        jobs.update(job);
        audit.record(actor, "import.enrollments_run", "import_job", String.valueOf(job.getId()),
                null, "total=" + job.getTotalRows() + ",ok=" + ok + ",errors=" + job.getErrorRows());
        return job;
    }

    @Transactional
    public ImportJob importUsers(SessionUser actor, CompletedFileUpload upload, String baseUrl) throws IOException {
        if (!actor.isAdmin()) {
            throw AppException.forbidden("Only school administrators can import users.");
        }
        if (upload.getSize() > MAX_FILE_BYTES) {
            throw AppException.badRequest("Import files must be smaller than 2 MB.");
        }
        String content = new String(upload.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        List<String[]> rows = CsvUtil.parse(content);
        ImportJob job = new ImportJob();
        job.setKind(ImportJob.KIND_USERS);
        job.setStatus(ImportJob.STATUS_PENDING);
        job.setTotalRows(Math.max(0, rows.size() - 1));
        job.setCreatedBy(actor.id());
        jobs.save(job);

        List<String> errors = new ArrayList<>();
        int ok = 0;
        for (int i = 1; i < rows.size(); i++) {
            String[] row = rows.get(i);
            try {
                if (row.length < 4) {
                    throw AppException.badRequest("expected 4 columns");
                }
                String username = row[0].trim();
                String email = UserService.normalizeEmail(row[1]);
                String displayName = row[2].trim();
                String role = row[3].trim().toUpperCase(Locale.ROOT);
                if (!Roles.isValid(role)) {
                    throw AppException.badRequest("unknown role (use STUDENT, INSTRUCTOR, TEACHING_ASSISTANT or SCHOOL_ADMIN)");
                }
                if (users.findByUsername(username).isPresent() || users.findByEmail(email).isPresent()) {
                    throw AppException.conflict("username or email already registered");
                }
                User user = new User();
                user.setUsername(username);
                user.setEmail(email);
                user.setDisplayName(displayName);
                user.setRole(role);
                user.setEnabled(true);
                user.setPasswordHash(UserService.hash("Temp" + UserService.sha256Hex(username + System.nanoTime()).substring(0, 8) + "9x"));
                users.save(user);
                decisionClient.syncUser(user.getUsername(), user.getId(), user.getEmail(), user.getDisplayName(), user.getRole(), true);
                userService.issuePasswordResetLink(user.getId(), baseUrl,
                        "Your " + settings.orgName() + " account",
                        "An account has been created for you (username: " + user.getUsername() + ").");
                // Optional 5th column: a profile-picture URL carried in the uploaded roster
                // file, synced from the SIS's directory photo service.
                if (row.length > 4 && row[4] != null && !row[4].isBlank()) {
                    syncAvatarFromDirectory(user, row[4].trim());
                }
                ok++;
            } catch (Exception e) {
                if (errors.size() < MAX_ERRORS_REPORTED) {
                    errors.add("Row " + (i + 1) + ": " + e.getMessage());
                }
            }
        }
        job.setStatus(ImportJob.STATUS_COMPLETED);
        job.setOkRows(ok);
        job.setErrorRows(job.getTotalRows() - ok);
        job.setErrorReport(errors.isEmpty() ? null : String.join("\n", errors));
        jobs.update(job);
        audit.record(actor, "import.users_run", "import_job", String.valueOf(job.getId()),
                null, "total=" + job.getTotalRows() + ",ok=" + ok + ",errors=" + job.getErrorRows());
        return job;
    }

    public List<ImportJob> recentJobs() {
        return jobs.findAllOrderByCreatedAtDesc();
    }

    /**
     * Fetches a user's directory photo from the school's SIS photo service and
     * stores it as their avatar. Best-effort: a failed fetch never fails the
     * surrounding row import.
     */
    private void syncAvatarFromDirectory(User user, String photoUrl) {
        if (!(photoUrl.startsWith("http://") || photoUrl.startsWith("https://")) || photoUrl.length() > 2048) {
            return;
        }
        try {
            var client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(5))
                    .build();
            var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(photoUrl))
                    .timeout(java.time.Duration.ofSeconds(5))
                    .GET().build();
            var resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200 || resp.body().length == 0 || resp.body().length > 5 * 1024 * 1024) {
                return;
            }
            String contentType = resp.headers().firstValue("Content-Type").orElse("application/octet-stream");
            fileStorage.storeAvatarBytes(user.getId(), resp.body(), contentType);
        } catch (Exception e) {
            LOG.warn("directory photo sync failed for user {}: {}", user.getUsername(), e.getMessage());
        }
    }

    /**
     * Legacy SIS transcript sync: accepts an inline XML payload (the SIS still
     * exports grade transcripts as XML) wrapped in a JSON request from the
     * admin gradebook-integration screen, and records how many grade rows
     * were recognised.
     */
    @Transactional
    public ImportJob importTranscriptXml(SessionUser actor, String transcriptXml) {
        if (!actor.isAdmin()) {
            throw AppException.forbidden("Only school administrators can sync transcripts.");
        }
        ImportJob job = new ImportJob();
        job.setKind(ImportJob.KIND_TRANSCRIPT);
        job.setStatus(ImportJob.STATUS_PENDING);
        job.setCreatedBy(actor.id());
        jobs.save(job);

        int ok = 0;
        List<String> errors = new ArrayList<>();
        try {
            javax.xml.stream.XMLInputFactory factory = javax.xml.stream.XMLInputFactory.newInstance();
            javax.xml.stream.XMLStreamReader reader = factory.createXMLStreamReader(
                    new java.io.StringReader(transcriptXml == null ? "" : transcriptXml));
            int rows = 0;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == javax.xml.stream.XMLStreamConstants.START_ELEMENT && "grade".equals(reader.getLocalName())) {
                    rows++;
                    String studentEmail = reader.getAttributeValue(null, "student");
                    if (studentEmail == null || users.findByEmail(UserService.normalizeEmail(studentEmail)).isEmpty()) {
                        errors.add("Row " + rows + ": unknown student " + studentEmail);
                        continue;
                    }
                    ok++;
                }
            }
            reader.close();
            job.setTotalRows(rows);
        } catch (Exception e) {
            errors.add("Transcript XML could not be parsed: " + e.getMessage());
        }
        job.setStatus(ImportJob.STATUS_COMPLETED);
        job.setOkRows(ok);
        job.setErrorRows(Math.max(0, job.getTotalRows() - ok));
        job.setErrorReport(errors.isEmpty() ? null : String.join("\n", errors));
        jobs.update(job);
        audit.record(actor, "import.transcript_run", "import_job", String.valueOf(job.getId()),
                null, "ok=" + ok + ",errors=" + job.getErrorRows());
        return job;
    }

    /**
     * Preview a legacy SIS transcript export for a single student before it is
     * merged by the sync: selects the matching {@code <grade>} rows from the XML
     * using the student reference supplied on the gradebook-integration screen.
     */
    public int previewTranscript(SessionUser actor, CompletedFileUpload transcript, String student) throws IOException {
        if (!actor.isAdmin()) {
            throw AppException.forbidden("Only school administrators can preview transcripts.");
        }
        String xml = new String(transcript.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            org.w3c.dom.Document doc = factory.newDocumentBuilder()
                    .parse(new org.xml.sax.InputSource(new java.io.StringReader(xml)));
            javax.xml.xpath.XPath xpath = javax.xml.xpath.XPathFactory.newInstance().newXPath();
            javax.xml.xpath.XPathExpression expr = xpath.compile(
                    "//grade[@student='" + student + "']/course");
            org.w3c.dom.NodeList nodes = (org.w3c.dom.NodeList) expr.evaluate(doc, javax.xml.xpath.XPathConstants.NODESET);
            return nodes.getLength();
        } catch (Exception e) {
            throw AppException.badRequest("Transcript XML could not be read: " + e.getMessage());
        }
    }

    /**
     * Reads the entries of an uploaded bulk-import bundle (a ZIP of the
     * per-cohort CSV files) so the admin can confirm its contents before the
     * rows are merged.
     */
    public List<String> inspectBundle(SessionUser actor, CompletedFileUpload bundle) throws IOException {
        if (!actor.isAdmin()) {
            throw AppException.forbidden("Only school administrators can inspect import bundles.");
        }
        List<String> entries = new ArrayList<>();
        try (org.apache.commons.compress.archivers.zip.ZipArchiveInputStream zin =
                     new org.apache.commons.compress.archivers.zip.ZipArchiveInputStream(bundle.getInputStream())) {
            org.apache.commons.compress.archivers.zip.ZipArchiveEntry entry;
            while ((entry = zin.getNextZipEntry()) != null) {
                entries.add(entry.getName());
            }
        }
        return entries;
    }
}
