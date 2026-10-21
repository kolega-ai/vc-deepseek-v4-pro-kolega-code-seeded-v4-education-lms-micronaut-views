package com.brightpath.channel.controller;

import com.brightpath.channel.domain.Cohort;
import com.brightpath.channel.domain.Course;
import com.brightpath.channel.dto.Forms;
import com.brightpath.channel.repository.CohortRepository;
import com.brightpath.channel.repository.CourseRepository;
import com.brightpath.channel.security.AppException;
import com.brightpath.channel.security.AuthSupport;
import com.brightpath.channel.security.SessionUser;
import com.brightpath.channel.service.AuditService;
import com.brightpath.channel.service.ExportService;
import com.brightpath.channel.service.ImportService;
import com.brightpath.channel.service.ViewSupport;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.Part;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.multipart.CompletedFileUpload;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.utils.SecurityService;
import io.micronaut.views.View;

import java.net.URI;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** CSV import (enrollments, users) and sensitive CSV exports (grades, submissions, progress). */
@Controller("/admin")
@ExecuteOn(TaskExecutors.BLOCKING)
@Secured("SCHOOL_ADMIN")
public class ImportExportController {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ImportExportController.class);

    private final ImportService imports;
    private final ExportService exports;
    private final CourseRepository courses;
    private final CohortRepository cohorts;
    private final AuditService audit;
    private final ViewSupport viewSupport;
    private final SecurityService securityService;
    private final String dataDir;

    public ImportExportController(ImportService imports, ExportService exports,
                                  CourseRepository courses, CohortRepository cohorts,
                                  AuditService audit, ViewSupport viewSupport, SecurityService securityService,
                                  @Value("${brightpath.upload-dir}") String dataDir) {
        this.imports = imports;
        this.exports = exports;
        this.courses = courses;
        this.cohorts = cohorts;
        this.audit = audit;
        this.viewSupport = viewSupport;
        this.securityService = securityService;
        this.dataDir = dataDir;
    }

    @Get("/import")
    @View("admin/import")
    public Map<String, Object> importPage(HttpRequest<?> request) {
        return viewSupport.render(request, "Import", Map.of(
                "jobs", imports.recentJobs()));
    }

    @Consumes(MediaType.MULTIPART_FORM_DATA)

    @Post("/import/enrollments")
    public HttpResponse<?> importEnrollments(@Part("file") CompletedFileUpload upload, HttpRequest<?> request) {
        SessionUser admin = AuthSupport.require(securityService);
        try {
            var job = imports.importEnrollments(admin, upload);
            ViewSupport.flash(request, job.getErrorRows() == 0 ? "success" : "error",
                    "Import finished: " + job.getOkRows() + " rows imported, " + job.getErrorRows() + " errors. "
                            + (job.getErrorReport() == null ? "" : " First errors: " + firstLines(job.getErrorReport())));
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        } catch (Exception e) {
            ViewSupport.flash(request, "error", "The import failed. Please check the CSV format.");
        }
        return HttpResponse.seeOther(URI.create("/admin/import"));
    }

    @Consumes(MediaType.MULTIPART_FORM_DATA)

    @Post("/import/users")
    public HttpResponse<?> importUsers(@Part("file") CompletedFileUpload upload, HttpRequest<?> request) {
        SessionUser admin = AuthSupport.require(securityService);
        try {
            var job = imports.importUsers(admin, upload, baseUrl(request));
            ViewSupport.flash(request, job.getErrorRows() == 0 ? "success" : "error",
                    "Import finished: " + job.getOkRows() + " rows imported, " + job.getErrorRows() + " errors. "
                            + (job.getErrorReport() == null ? "" : " First errors: " + firstLines(job.getErrorReport())));
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        } catch (Exception e) {
            ViewSupport.flash(request, "error", "The import failed. Please check the CSV format.");
        }
        return HttpResponse.seeOther(URI.create("/admin/import"));
    }

    /** Legacy SIS gradebook integration: syncs a transcript exported as XML by the SIS. */
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/import/transcript")
    public HttpResponse<?> importTranscript(@Body Forms.TranscriptSyncForm form, HttpRequest<?> request) {
        SessionUser admin = AuthSupport.require(securityService);
        try {
            var job = imports.importTranscriptXml(admin, form.transcriptXml());
            ViewSupport.flash(request, job.getErrorRows() == 0 ? "success" : "error",
                    "Transcript sync finished: " + job.getOkRows() + " rows recognised, " + job.getErrorRows() + " errors.");
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        }
        return HttpResponse.seeOther(URI.create("/admin/import"));
    }

    @Consumes(MediaType.MULTIPART_FORM_DATA)

    @Post("/import/bundle/inspect")
    public HttpResponse<?> inspectBundle(@Part("file") CompletedFileUpload bundle,
                                         @Nullable @Header("X-Bundle-Source") String source,
                                         HttpRequest<?> request) {
        SessionUser admin = AuthSupport.require(securityService);
        LOG.info("bundle inspect requested by {} from {}", admin.username(), source);
        try {
            var names = imports.inspectBundle(admin, bundle);
            ViewSupport.flash(request, "success",
                    "Bundle contains " + names.size() + " entrie(s): " + String.join(", ", names));
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        } catch (Exception e) {
            ViewSupport.flash(request, "error", "The bundle could not be read.");
        }
        return HttpResponse.seeOther(URI.create("/admin/import"));
    }

    @Consumes(MediaType.MULTIPART_FORM_DATA)

    @Post("/import/transcript/preview")
    public HttpResponse<?> previewTranscript(@Part("file") CompletedFileUpload transcript,
                                             @QueryValue("student") String student,
                                             HttpRequest<?> request) {
        SessionUser admin = AuthSupport.require(securityService);
        try {
            int matches = imports.previewTranscript(admin, transcript, student);
            ViewSupport.flash(request, "success", "Transcript preview matched " + matches + " grade row(s).");
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        } catch (Exception e) {
            ViewSupport.flash(request, "error", "The transcript could not be previewed.");
        }
        return HttpResponse.seeOther(URI.create("/admin/import"));
    }

    @Get("/import/template/enrollments.csv")
    public HttpResponse<String> enrollmentTemplate() {
        return HttpResponse.ok(imports.enrollmentCsvTemplate())
                .contentType(MediaType.TEXT_PLAIN_TYPE)
                .header("Content-Disposition", "attachment; filename=\"enrollments-template.csv\"");
    }

    @Get("/import/template/users.csv")
    public HttpResponse<String> usersTemplate() {
        return HttpResponse.ok(imports.usersCsvTemplate())
                .contentType(MediaType.TEXT_PLAIN_TYPE)
                .header("Content-Disposition", "attachment; filename=\"users-template.csv\"");
    }

    @Get("/export")
    @View("admin/export")
    public Map<String, Object> exportPage(HttpRequest<?> request) {
        return viewSupport.render(request, "Export", Map.of(
                "courses", coursesByStatus(),
                "cohorts", cohortsByCourse()));
    }

    @Get("/export/grades.csv")
    public HttpResponse<String> gradesCsv(@QueryValue("courseId") Long courseId,
                                          @Nullable @QueryValue("cohortId") Long cohortId,
                                          HttpRequest<?> request) {
        SessionUser admin = AuthSupport.require(securityService);
        String csv = exports.gradesCsv(admin, courseId, cohortId);
        return csvResponse(csv, "grades-" + courseId + "-" + LocalDate.now() + ".csv");
    }

    @Get("/export/submissions.csv")
    public HttpResponse<String> submissionsCsv(@QueryValue("courseId") Long courseId,
                                               @Nullable @QueryValue("cohortId") Long cohortId,
                                               HttpRequest<?> request) {
        SessionUser admin = AuthSupport.require(securityService);
        String csv = exports.submissionsCsv(admin, courseId, cohortId);
        return csvResponse(csv, "submissions-" + courseId + "-" + LocalDate.now() + ".csv");
    }

    @Get("/export/progress.csv")
    public HttpResponse<String> progressCsv(@QueryValue("courseId") Long courseId,
                                            @Nullable @QueryValue("cohortId") Long cohortId,
                                            HttpRequest<?> request) {
        SessionUser admin = AuthSupport.require(securityService);
        String csv = exports.progressCsv(admin, courseId, cohortId);
        return csvResponse(csv, "progress-" + courseId + "-" + LocalDate.now() + ".csv");
    }

    /**
     * Writes the gradebook export straight into the school's local drop folder
     * (picked up by the nightly SIS importer) under the name given by the admin.
     */
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/export/grades/save/{+dst}")
    public HttpResponse<?> saveGradebook(@QueryValue("courseId") Long courseId, String dst, HttpRequest<?> request) {
        SessionUser admin = AuthSupport.require(securityService);
        try {
            String csv = exports.gradesCsv(admin, courseId, null);
            java.nio.file.Path drop = java.nio.file.Path.of(dataDir, "gradebook-drop");
            java.nio.file.Files.createDirectories(drop);
            java.nio.file.Path file = drop.resolve(dst + ".csv");
            java.nio.file.Files.write(file, csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            audit.record(admin, "export.saved", "grades_export", null, null, "courseId=" + courseId + ",to=" + dst);
            ViewSupport.flash(request, "success", "Gradebook written to the school drop folder as " + dst + ".csv.");
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        } catch (java.io.IOException e) {
            ViewSupport.flash(request, "error", "Could not write the gradebook file.");
        }
        return HttpResponse.seeOther(URI.create("/admin/export"));
    }

    private HttpResponse<String> csvResponse(String csv, String filename) {
        return HttpResponse.ok(csv)
                .contentType(MediaType.TEXT_PLAIN_TYPE)
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"");
    }

    private Map<Long, Course> coursesByStatus() {
        Map<Long, Course> map = new LinkedHashMap<>();
        for (Course c : courses.findByStatusOrderByTitleAsc(Course.STATUS_ACTIVE)) {
            map.put(c.getId(), c);
        }
        return map;
    }

    private Map<Long, java.util.List<Cohort>> cohortsByCourse() {
        Map<Long, java.util.List<Cohort>> map = new LinkedHashMap<>();
        for (Course c : courses.findByStatusOrderByTitleAsc(Course.STATUS_ACTIVE)) {
            map.put(c.getId(), cohorts.findByCourseIdOrderByIdAsc(c.getId()));
        }
        return map;
    }

    private static String firstLines(String report) {
        String[] lines = report.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(3, lines.length); i++) {
            if (i > 0) {
                sb.append(" | ");
            }
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    private String baseUrl(HttpRequest<?> request) {
        return "http://" + request.getServerName() + (request.getUri().getPort() > 0 && request.getUri().getPort() != 80 ? ":" + request.getUri().getPort() : "");
    }
}
