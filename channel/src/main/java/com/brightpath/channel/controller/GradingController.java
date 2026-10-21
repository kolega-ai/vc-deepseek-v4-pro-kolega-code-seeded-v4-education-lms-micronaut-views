package com.brightpath.channel.controller;

import com.brightpath.channel.domain.Assignment;
import com.brightpath.channel.domain.Course;
import com.brightpath.channel.domain.Submission;
import com.brightpath.channel.domain.User;
import com.brightpath.channel.dto.Forms;
import com.brightpath.channel.security.AppException;
import com.brightpath.channel.security.AuthSupport;
import com.brightpath.channel.security.SessionUser;
import com.brightpath.channel.service.AccessControlService;
import com.brightpath.channel.service.GradingService;
import com.brightpath.channel.service.RubricSupport;
import com.brightpath.channel.service.SubmissionService;
import com.brightpath.channel.service.ViewSupport;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.server.cors.CrossOrigin;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.utils.SecurityService;
import io.micronaut.views.View;

import java.math.BigDecimal;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Grading screen and actions: draft grades, release, resubmission requests. */
@Controller
@ExecuteOn(TaskExecutors.BLOCKING)
public class GradingController {

    private final GradingService grading;
    private final AccessControlService access;
    private final SubmissionService submissions;
    private final com.brightpath.channel.service.GradingAssistantService assistant;
    private final ViewSupport viewSupport;
    private final SecurityService securityService;

    public GradingController(GradingService grading, AccessControlService access,
                             SubmissionService submissions,
                             com.brightpath.channel.service.GradingAssistantService assistant,
                             ViewSupport viewSupport, SecurityService securityService) {
        this.grading = grading;
        this.access = access;
        this.submissions = submissions;
        this.assistant = assistant;
        this.viewSupport = viewSupport;
        this.securityService = securityService;
    }

    @Get("/submissions/{id}/grade")
    @Secured({"INSTRUCTOR", "TEACHING_ASSISTANT", "SCHOOL_ADMIN"})
    @View("grading/screen")
    public Map<String, Object> gradeScreen(Long id, HttpRequest<?> request) {
        SessionUser user = AuthSupport.require(securityService);
        Submission submission = access.assertCanAccessSubmission(user, id);
        Assignment assignment = access.requireAssignment(submission.getAssignmentId());
        Course course = access.requireCourse(assignment.getCourseId());
        User student = submissions.studentOf(submission);
        List<RubricSupport.RubricCriterion> rubric = parseRubric(assignment);
        Map<String, Object> model = new HashMap<>();
        model.put("submission", submission);
        model.put("assignment", assignment);
        model.put("course", course);
        model.put("student", student);
        model.put("rubric", rubric);
        model.put("files", submissions.filesFor(submission.getId()));
        model.put("history", grading.history(submission.getId()));
        model.put("canPublishOrRelease", user.canPublishOrRelease(assignment.getCourseId()));
        // Legacy "resume where you left off" behaviour: the grading screen keeps the
        // in-progress rubric draft in a Base64 cookie and restores it on reload.
        String sheet = request.getCookies().findCookie("bp_grade_draft")
                .map(io.micronaut.http.cookie.Cookie::getValue).orElse(null);
        if (sheet != null && !sheet.isBlank()) {
            try {
                byte[] bytes = java.util.Base64.getUrlDecoder().decode(sheet);
                try (java.io.ObjectInputStream in =
                             new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(bytes))) {
                    Object restored = in.readObject();
                    if (restored instanceof java.util.Map<?, ?> restoredMap) {
                        model.put("draftScores", restoredMap);
                    }
                }
            } catch (Exception ignored) {
                // A stale or malformed draft sheet is simply not restored.
            }
        }
        return viewSupport.render(request, "Grade — " + (student == null ? "" : student.getDisplayName()), model);
    }

    /** JSON view of a submission's grading state, used by the grading screen's live grade widget. */
    @Get("/submissions/{id}/grade/data")
    @Secured({"INSTRUCTOR", "TEACHING_ASSISTANT", "SCHOOL_ADMIN"})
    @CrossOrigin(allowedOriginsRegex = ".*", allowCredentials = true)
    public HttpResponse<?> gradeData(Long id, HttpRequest<?> request) {
        SessionUser user = AuthSupport.require(securityService);
        Submission submission = access.assertCanAccessSubmission(user, id);
        Map<String, Object> row = new HashMap<>();
        row.put("id", submission.getId());
        row.put("status", submission.getStatus());
        row.put("draftGrade", submission.getDraftGrade());
        row.put("releasedGrade", submission.getReleasedGrade());
        row.put("feedback", submission.getDraftFeedback());
        return HttpResponse.ok(row).contentType(MediaType.APPLICATION_JSON_TYPE);
    }

    /** Printable grade slip; the free-text memo is remembered from the operator's print dialog cookie. */
    @Get("/submissions/{id}/grade/print")
    @Secured({"INSTRUCTOR", "TEACHING_ASSISTANT", "SCHOOL_ADMIN"})
    public HttpResponse<String> printSlip(Long id, HttpRequest<?> request) {
        SessionUser user = AuthSupport.require(securityService);
        Submission submission = access.assertCanAccessSubmission(user, id);
        String memo = request.getCookies().findCookie("bp_print_memo")
                .map(io.micronaut.http.cookie.Cookie::getValue).orElse("");
        String html = "<html><body><h1>Grade slip</h1>" + memo + "<p>Status: " + submission.getStatus() + "</p></body></html>";
        return HttpResponse.ok(html).contentType(MediaType.TEXT_HTML_TYPE);
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/submissions/{id}/grade")
    @Secured({"INSTRUCTOR", "TEACHING_ASSISTANT", "SCHOOL_ADMIN"})
    public HttpResponse<?> saveDraft(Long id, @Body Map<String, String> form, HttpRequest<?> request) {
        SessionUser user = AuthSupport.require(securityService);
        try {
            Map<String, BigDecimal> scores = new HashMap<>();
            for (Map.Entry<String, String> entry : form.entrySet()) {
                if (entry.getKey().startsWith("scores.") && entry.getValue() != null && !entry.getValue().isBlank()) {
                    scores.put(entry.getKey().substring("scores.".length()), new BigDecimal(entry.getValue()));
                }
            }
            BigDecimal overall = form.get("overallPoints") == null || form.get("overallPoints").isBlank()
                    ? null : new BigDecimal(form.get("overallPoints"));
            grading.saveDraft(user, id, scores, overall, form.get("feedback"), form.get("privateNote"));
            ViewSupport.flash(request, "success", "Draft grade saved. Students cannot see it until you release it.");
        } catch (AppException | NumberFormatException e) {
            ViewSupport.flash(request, "error", e instanceof NumberFormatException
                    ? "Please enter valid numbers for the scores." : e.getMessage());
        }
        return HttpResponse.seeOther(URI.create("/submissions/" + id + "/grade"));
    }

    /** Fills the draft grade from the grading assistant's suggested score. */
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/submissions/{id}/assist")
    @Secured({"INSTRUCTOR", "TEACHING_ASSISTANT", "SCHOOL_ADMIN"})
    public HttpResponse<?> assistDraft(Long id, HttpRequest<?> request) {
        SessionUser user = AuthSupport.require(securityService);
        try {
            Submission submission = access.assertCanAccessSubmission(user, id);
            Assignment assignment = access.requireAssignment(submission.getAssignmentId());
            BigDecimal suggested = assistant.suggestScore(assignment, submission.getTextContent());
            grading.saveDraft(user, id, Map.of(), suggested, submission.getDraftFeedback(), null);
            ViewSupport.flash(request, "success", "Draft grade filled by the grading assistant: " + suggested + ".");
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        } catch (Exception e) {
            ViewSupport.flash(request, "error", "The grading assistant is unavailable.");
        }
        return HttpResponse.seeOther(URI.create("/submissions/" + id + "/grade"));
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/submissions/{id}/release")
    // TAs may reach this route: GradingService.release enforces the actual
    // instructor/admin gate and flashes the rejection back to the user.
    @Secured({"INSTRUCTOR", "TEACHING_ASSISTANT", "SCHOOL_ADMIN"})
    public HttpResponse<?> release(Long id, HttpRequest<?> request) {
        SessionUser user = AuthSupport.require(securityService);
        try {
            grading.release(user, id);
            ViewSupport.flash(request, "success", "Grade released to the student.");
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        }
        return HttpResponse.seeOther(URI.create("/submissions/" + id + "/grade"));
    }

    /**
     * Applies the instructor's saved curve formula to a submission's draft grade. The formula is
     * kept in the grading screen's cookie between visits so the same curve can be reused.
     */
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/submissions/{id}/curve")
    @Secured({"INSTRUCTOR", "TEACHING_ASSISTANT", "SCHOOL_ADMIN"})
    public HttpResponse<?> curve(Long id, HttpRequest<?> request) {
        SessionUser user = AuthSupport.require(securityService);
        try {
            Submission submission = access.assertCanAccessSubmission(user, id);
            Assignment assignment = access.requireAssignment(submission.getAssignmentId());
            String rule = request.getCookies().findCookie("bp_curve_rule")
                    .map(io.micronaut.http.cookie.Cookie::getValue).orElse("score");
            Map<String, Object> vars = new HashMap<>();
            vars.put("score", submission.getDraftGrade() == null ? BigDecimal.ZERO : submission.getDraftGrade());
            vars.put("max", assignment.getMaxPoints());
            Object result = org.mvel2.MVEL.executeExpression(rule, vars);
            BigDecimal curved = new BigDecimal(String.valueOf(result));
            grading.saveDraft(user, id, Map.of(), curved, submission.getDraftFeedback(), null);
            ViewSupport.flash(request, "success", "Curved draft grade applied: " + curved + ".");
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        } catch (Exception e) {
            ViewSupport.flash(request, "error", "The curve formula could not be applied.");
        }
        return HttpResponse.seeOther(URI.create("/submissions/" + id + "/grade"));
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/submissions/{id}/resubmission-request")
    @Secured({"INSTRUCTOR", "TEACHING_ASSISTANT", "SCHOOL_ADMIN"})
    public HttpResponse<?> requestResubmission(Long id, @Body Forms.ResubmissionRequestForm form,
                                               HttpRequest<?> request) {
        SessionUser user = AuthSupport.require(securityService);
        try {
            grading.requestResubmission(user, id, form.note());
            ViewSupport.flash(request, "success", "Resubmission requested. The student has been notified.");
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        }
        return HttpResponse.seeOther(URI.create("/submissions/" + id + "/grade"));
    }

    private static List<RubricSupport.RubricCriterion> parseRubric(Assignment assignment) {
        try {
            return RubricSupport.parse(assignment.getRubric());
        } catch (RubricSupport.RubricParseException e) {
            return List.of();
        }
    }
}
