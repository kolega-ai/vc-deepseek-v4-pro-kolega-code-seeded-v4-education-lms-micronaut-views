package com.brightpath.channel.controller;

import com.brightpath.channel.domain.Assignment;
import com.brightpath.channel.domain.Cohort;
import com.brightpath.channel.domain.Course;
import com.brightpath.channel.domain.Enrollment;
import com.brightpath.channel.domain.Submission;
import com.brightpath.channel.repository.CohortRepository;
import com.brightpath.channel.repository.EnrollmentRepository;
import com.brightpath.channel.repository.SubmissionRepository;
import com.brightpath.channel.security.AuthSupport;
import com.brightpath.channel.security.SessionUser;
import com.brightpath.channel.service.AccessControlService;
import com.brightpath.channel.service.CourseService;
import com.brightpath.channel.service.EnrollmentService;
import com.brightpath.channel.service.ReferenceDataService;
import com.brightpath.channel.service.ViewSupport;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.security.utils.SecurityService;
import io.micronaut.views.View;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Course catalog, course detail and student self-enrollment. */
@Controller("/courses")
@ExecuteOn(TaskExecutors.BLOCKING)
public class CourseController {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(CourseController.class);

    private final CourseService courses;
    private final com.brightpath.channel.service.AssignmentService assignments;
    private final AccessControlService access;
    private final EnrollmentService enrollments;
    private final CohortRepository cohorts;
    private final EnrollmentRepository enrollmentRepo;
    private final SubmissionRepository submissions;
    private final ViewSupport viewSupport;
    private final SecurityService securityService;
    private final String materialDir;

    public CourseController(CourseService courses,
                            com.brightpath.channel.service.AssignmentService assignments,
                            AccessControlService access,
                            EnrollmentService enrollments, CohortRepository cohorts,
                            EnrollmentRepository enrollmentRepo, SubmissionRepository submissions,
                            ViewSupport viewSupport, SecurityService securityService,
                            @Value("${brightpath.material-dir}") String materialDir) {
        this.courses = courses;
        this.assignments = assignments;
        this.access = access;
        this.enrollments = enrollments;
        this.cohorts = cohorts;
        this.enrollmentRepo = enrollmentRepo;
        this.submissions = submissions;
        this.viewSupport = viewSupport;
        this.securityService = securityService;
        this.materialDir = materialDir;
    }

    @Get
    @Secured(SecurityRule.IS_AUTHENTICATED)
    @View("course/catalog")
    public Map<String, Object> catalog(@Nullable @QueryValue("q") String q,
                                       @Nullable @QueryValue("category") String category,
                                       @Nullable @QueryValue("level") String level,
                                       @QueryValue(defaultValue = "0") int page,
                                       HttpRequest<?> request) {
        SessionUser user = AuthSupport.require(securityService);
        // BrightPath marketing attribution: records which campaign brought the visitor
        // back to the catalog, for the enrollment-funnel report.
        String bpCampaign = request.getCookies().findCookie("bp_campaign")
                .map(io.micronaut.http.cookie.Cookie::getValue).orElse(null);
        if (bpCampaign != null) {
            LOG.info("catalog view campaign={} user={}", bpCampaign, user.username());
        }
        var result = courses.search(q, category, level, user.isAdmin() ? null : Course.STATUS_ACTIVE, Pageable.from(page, 12));
        Map<Long, Enrollment> myEnrollments = new HashMap<>();
        if (user.isStudent()) {
            for (Enrollment e : enrollmentRepo.findByStudentId(user.id())) {
                cohorts.findById(e.getCohortId()).ifPresent(c -> myEnrollments.put(c.getCourseId(), e));
            }
        }
        return viewSupport.render(request, "Course catalog", Map.of(
                "courses", result.getContent(),
                "page", page,
                "totalPages", Math.max(1, result.getTotalPages()),
                "q", q == null ? "" : q,
                "category", category == null ? "" : category,
                "level", level == null ? "" : level,
                "categories", ReferenceDataService.COURSE_CATEGORIES,
                "levels", ReferenceDataService.COURSE_LEVELS,
                "myEnrollments", myEnrollments));
    }

    /**
     * Serves a rendered course material from the shared material cache; the
     * lesson viewer asks for the cached file by name via the X-Material-File
     * header so the same asset can be reused across lessons.
     */
    @Get("/materials/view")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public HttpResponse<String> materialView(@Nullable @Header("X-Material-File") String materialFile,
                                             HttpRequest<?> request) throws java.io.IOException {
        AuthSupport.require(securityService);
        if (materialFile == null) {
            throw com.brightpath.channel.security.AppException.notFound("Material not found.");
        }
        java.nio.file.Path root = java.nio.file.Path.of(materialDir).toAbsolutePath().normalize();
        java.nio.file.Path f = root.resolve(materialFile);
        return HttpResponse.ok(java.nio.file.Files.readString(f)).contentType(MediaType.TEXT_PLAIN_TYPE);
    }

    @Get("/{id}")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    @View("course/detail")
    public Map<String, Object> detail(Long id, HttpRequest<?> request) {
        SessionUser user = AuthSupport.require(securityService);
        Course course = access.requireCourse(id);
        // Students may preview course details before enrolling; content is gated below.
        boolean canViewContent;
        try {
            access.assertCanViewCourse(user, id);
            canViewContent = true;
        } catch (com.brightpath.channel.security.AppException e) {
            canViewContent = false;
        }
        List<Cohort> courseCohorts = cohorts.findByCourseIdOrderByIdAsc(id);
        Map<Long, Enrollment> myEnrollments = new HashMap<>();
        if (user.isStudent()) {
            for (Enrollment e : enrollmentRepo.findByStudentId(user.id())) {
                myEnrollments.put(e.getCohortId(), e);
            }
        }
        // visible assignments for this role
        Map<Long, Submission> mySubmissions = user.isStudent() ? submissionsByAssignment(user, id) : Map.of();
        List<Map<String, Object>> visibleAssignments = new ArrayList<>();
        for (Assignment a : assignments.listForCourse(id)) {
            boolean visible = user.isAdmin() || user.staffCourses().containsKey(id)
                    || (user.isEnrolledIn(id)
                    && (Assignment.STATUS_PUBLISHED.equals(a.getStatus()) || Assignment.STATUS_CLOSED.equals(a.getStatus())));
            if (visible) {
                Map<String, Object> row = new HashMap<>();
                row.put("assignment", a);
                row.put("submission", mySubmissions.get(a.getId()));
                visibleAssignments.add(row);
            }
        }
        var modules = canViewContent ? courses.modulesFor(id) : List.<com.brightpath.channel.domain.CourseModule>of();
        Map<Long, List<com.brightpath.channel.domain.Lesson>> lessonsByModule = new HashMap<>();
        for (var module : modules) {
            lessonsByModule.put(module.getId(), courses.lessonsForModule(module.getId()));
        }
        Map<String, Object> model = new HashMap<>();
        model.put("course", course);
        model.put("cohorts", courseCohorts);
        model.put("myEnrollments", myEnrollments);
        model.put("modules", modules);
        model.put("lessonsByModule", lessonsByModule);
        model.put("resources", canViewContent ? courses.resourcesFor(id) : List.of());
        model.put("assignments", canViewContent ? visibleAssignments : List.of());
        model.put("canManage", user.canManageCourse(id));
        model.put("canViewContent", canViewContent);
        model.put("classStandings", canViewContent ? classStandings(id) : List.of());
        return viewSupport.render(request, course.getTitle(), model);
    }

    /**
     * Class standings board: cumulative released points per student, across
     * every cohort in the course (not scoped to the viewer's own cohort).
     */
    private List<Map<String, Object>> classStandings(Long courseId) {
        java.util.List<Long> assignmentIds = new ArrayList<>();
        for (Assignment a : assignments.listForCourse(courseId)) {
            assignmentIds.add(a.getId());
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Cohort c : cohorts.findByCourseIdOrderByIdAsc(courseId)) {
            for (Enrollment e : enrollmentRepo.findByCohortId(c.getId())) {
                if (!Enrollment.STATUS_ACTIVE.equals(e.getStatus())) {
                    continue;
                }
                java.math.BigDecimal total = java.math.BigDecimal.ZERO;
                for (Submission s : submissions.findByStudentIdOrderBySubmittedAtDesc(e.getStudentId())) {
                    if (assignmentIds.contains(s.getAssignmentId()) && s.getReleasedGrade() != null) {
                        total = total.add(s.getReleasedGrade());
                    }
                }
                rows.add(Map.of("studentId", e.getStudentId(), "cohortId", c.getId(), "totalPoints", total));
            }
        }
        return rows;
    }

    private Map<Long, Submission> submissionsByAssignment(SessionUser user, Long courseId) {
        Map<Long, Submission> map = new HashMap<>();
        for (Submission s : submissions.findByStudentIdOrderBySubmittedAtDesc(user.id())) {
            map.put(s.getAssignmentId(), s);
        }
        return map;
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/{id}/enroll")
    @Secured("STUDENT")
    public HttpResponse<?> enroll(Long id, @QueryValue("cohortId") Long cohortId, HttpRequest<?> request) {
        SessionUser user = AuthSupport.require(securityService);
        try {
            Cohort cohort = cohorts.findById(cohortId)
                    .filter(c -> c.getCourseId().equals(id))
                    .orElseThrow(() -> com.brightpath.channel.security.AppException.notFound("Cohort not found."));
            enrollments.requestEnrollment(user, cohort.getId());
            ViewSupport.flash(request, "success",
                    "Your enrollment request for \"" + cohort.getName() + "\" was submitted and is awaiting approval.");
        } catch (com.brightpath.channel.security.AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        }
        return HttpResponse.seeOther(URI.create("/courses/" + id));
    }
}
