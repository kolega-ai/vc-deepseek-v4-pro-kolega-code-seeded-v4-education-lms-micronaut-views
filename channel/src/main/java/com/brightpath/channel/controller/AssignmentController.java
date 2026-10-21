package com.brightpath.channel.controller;

import com.brightpath.channel.domain.Assignment;
import com.brightpath.channel.domain.Cohort;
import com.brightpath.channel.domain.Course;
import com.brightpath.channel.domain.Submission;
import com.brightpath.channel.dto.Forms;
import com.brightpath.channel.repository.CohortRepository;
import com.brightpath.channel.security.AppException;
import com.brightpath.channel.security.AuthSupport;
import com.brightpath.channel.security.SessionUser;
import com.brightpath.channel.service.AccessControlService;
import com.brightpath.channel.service.AssignmentService;
import com.brightpath.channel.service.RubricSupport;
import com.brightpath.channel.service.SubmissionService;
import com.brightpath.channel.service.ViewSupport;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.security.utils.SecurityService;
import io.micronaut.views.View;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Assignment detail (students), creation/editing and workflow actions (staff). */
@Controller("/assignments")
@ExecuteOn(TaskExecutors.BLOCKING)
public class AssignmentController {

    private final AssignmentService assignments;
    private final AccessControlService access;
    private final SubmissionService submissions;
    private final CohortRepository cohorts;
    private final ViewSupport viewSupport;
    private final SecurityService securityService;

    public AssignmentController(AssignmentService assignments, AccessControlService access,
                                SubmissionService submissions, CohortRepository cohorts,
                                ViewSupport viewSupport, SecurityService securityService) {
        this.assignments = assignments;
        this.access = access;
        this.submissions = submissions;
        this.cohorts = cohorts;
        this.viewSupport = viewSupport;
        this.securityService = securityService;
    }

    @Get("/{id}")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    @View("assignment/detail")
    public Map<String, Object> detail(Long id, HttpRequest<?> request) {
        SessionUser user = AuthSupport.require(securityService);
        Assignment assignment = access.assertCanViewAssignment(user, id);
        Course course = access.requireCourse(assignment.getCourseId());
        Submission mySubmission = user.isStudent()
                ? submissions.findForStudent(id, user.id()).map(SubmissionService::studentVisibleCopy).orElse(null) : null;
        List<RubricSupport.RubricCriterion> rubric = parseRubric(assignment);
        boolean canManage = user.canManageCourse(assignment.getCourseId());
        boolean canPublishOrRelease = user.canPublishOrRelease(assignment.getCourseId());
        boolean canReplace = mySubmission != null
                && (Submission.STATUS_SUBMITTED.equals(mySubmission.getStatus()) || Submission.STATUS_LATE.equals(mySubmission.getStatus()))
                && mySubmission.getDraftGrade() == null
                && Instant.now().isBefore(assignment.getDueAt())
                && Assignment.STATUS_PUBLISHED.equals(assignment.getStatus());
        Cohort cohort = assignment.getCohortId() == null ? null
                : cohorts.findById(assignment.getCohortId()).orElse(null);
        Map<String, Object> model = new java.util.HashMap<>();
        model.put("assignment", assignment);
        model.put("course", course);
        model.put("cohort", cohort);
        model.put("mySubmission", mySubmission);
        model.put("rubric", rubric);
        model.put("canManage", canManage);
        model.put("canPublishOrRelease", canPublishOrRelease);
        model.put("canReplace", canReplace);
        model.put("submissionFiles", mySubmission == null ? List.of() : submissions.filesFor(mySubmission.getId()));
        model.put("now", Instant.now());
        return viewSupport.render(request, assignment.getTitle(), model);
    }

    @Get("/new{?courseId}")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    @View("assignment/form")
    public Map<String, Object> newForm(@QueryValue("courseId") Long courseId, HttpRequest<?> request) {
        SessionUser user = AuthSupport.require(securityService);
        Course course = access.assertCanManageCourse(user, courseId);
        if (!user.canPublishOrRelease(courseId)) {
            throw AppException.forbidden("Only instructors or administrators can create assignments.");
        }
        return viewSupport.render(request, "New assignment", Map.of(
                "course", course,
                "assignment", null,
                "cohorts", cohorts.findByCourseIdOrderByIdAsc(courseId),
                "form", new Forms.AssignmentForm(courseId, null, "", "", "", "", "", true, null, "[]", "pdf,docx,zip", 3)));
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public HttpResponse<?> create(@Body Forms.AssignmentForm form, HttpRequest<?> request) {
        SessionUser user = AuthSupport.require(securityService);
        try {
            Assignment assignment = assignments.create(user, form.courseId(), form.cohortId(), form.title(),
                    form.summary(), form.instructions(), parseInstant(form.dueAt()), parseInstant(form.availableAt()),
                    Boolean.TRUE.equals(form.allowLate()), form.maxPoints(), form.rubricJson(),
                    form.allowedFileTypes(), form.maxFiles() == null ? 3 : form.maxFiles());
            ViewSupport.flash(request, "success", "Assignment created as draft. Publish it when it is ready.");
            return HttpResponse.seeOther(URI.create("/assignments/" + assignment.getId()));
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
            return HttpResponse.seeOther(URI.create("/assignments/new?courseId=" + form.courseId()));
        }
    }

    @Get("/{id}/edit")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    @View("assignment/form")
    public Map<String, Object> editForm(Long id, HttpRequest<?> request) {
        SessionUser user = AuthSupport.require(securityService);
        Assignment assignment = access.assertCanManageAssignment(user, id);
        if (!Assignment.STATUS_DRAFT.equals(assignment.getStatus())) {
            throw AppException.badRequest("Only draft assignments can be edited.");
        }
        Course course = access.requireCourse(assignment.getCourseId());
        java.util.Map<String, Object> editModel = new java.util.HashMap<>();
        editModel.put("course", course);
        editModel.put("assignment", assignment);
        editModel.put("cohorts", cohorts.findByCourseIdOrderByIdAsc(assignment.getCourseId()));
        editModel.put("form", null);
        return viewSupport.render(request, "Edit assignment", editModel);
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/{id}/edit")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public HttpResponse<?> update(Long id, @Body Forms.AssignmentForm form, HttpRequest<?> request) {
        SessionUser user = AuthSupport.require(securityService);
        try {
            assignments.update(user, id, form.title(), form.summary(), form.instructions(),
                    parseInstant(form.dueAt()), parseInstant(form.availableAt()),
                    Boolean.TRUE.equals(form.allowLate()), form.maxPoints(), form.rubricJson(),
                    form.allowedFileTypes(), form.maxFiles() == null ? 3 : form.maxFiles());
            ViewSupport.flash(request, "success", "Assignment updated.");
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        }
        return HttpResponse.seeOther(URI.create("/assignments/" + id));
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/{id}/transition")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public HttpResponse<?> transition(Long id, @QueryValue("target") String target, HttpRequest<?> request) {
        SessionUser user = AuthSupport.require(securityService);
        try {
            assignments.transition(user, id, target);
            ViewSupport.flash(request, "success", "Assignment is now \"" + target + "\".");
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        }
        return HttpResponse.seeOther(URI.create("/assignments/" + id));
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/{id}/extend")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public HttpResponse<?> extend(Long id, @Body Forms.ExtendDueForm form, HttpRequest<?> request) {
        SessionUser user = AuthSupport.require(securityService);
        try {
            assignments.extendDueDate(user, id, parseInstant(form.newDueAt()), form.reason());
            ViewSupport.flash(request, "success", "Due date extended. Students have been notified.");
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        }
        return HttpResponse.seeOther(URI.create("/assignments/" + id));
    }

    private static Instant parseInstant(@Nullable String value) {
        if (value == null || value.isBlank()) {
            throw AppException.badRequest("Please provide a valid date and time.");
        }
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            throw AppException.badRequest("Please provide a valid date and time.");
        }
    }

    private static List<RubricSupport.RubricCriterion> parseRubric(Assignment assignment) {
        try {
            return RubricSupport.parse(assignment.getRubric());
        } catch (RubricSupport.RubricParseException e) {
            return List.of();
        }
    }
}
