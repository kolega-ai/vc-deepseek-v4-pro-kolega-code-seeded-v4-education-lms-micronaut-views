package com.brightpath.channel.controller;

import com.brightpath.channel.domain.Assignment;
import com.brightpath.channel.domain.Course;
import com.brightpath.channel.domain.Submission;
import com.brightpath.channel.domain.User;
import com.brightpath.channel.security.AppException;
import com.brightpath.channel.security.AuthSupport;
import com.brightpath.channel.security.SessionUser;
import com.brightpath.channel.service.AccessControlService;
import com.brightpath.channel.service.GradingService;
import com.brightpath.channel.service.ReferenceDataService;
import com.brightpath.channel.service.SubmissionService;
import com.brightpath.channel.service.ViewSupport;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.multipart.CompletedFileUpload;
import io.micronaut.http.multipart.CompletedPart;
import io.micronaut.http.server.multipart.MultipartBody;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.security.utils.SecurityService;
import io.micronaut.views.View;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Submission view (owner or scoped staff), submission queue, and the student submit flow. */
@Controller
@ExecuteOn(TaskExecutors.BLOCKING)
public class SubmissionController {

    private static final Logger LOG = LoggerFactory.getLogger(SubmissionController.class);

    private final SubmissionService submissions;
    private final AccessControlService access;
    private final GradingService grading;
    private final com.brightpath.channel.service.QueueCursorCodec cursors;
    private final ViewSupport viewSupport;
    private final SecurityService securityService;

    public SubmissionController(SubmissionService submissions, AccessControlService access,
                                GradingService grading, com.brightpath.channel.service.QueueCursorCodec cursors,
                                ViewSupport viewSupport, SecurityService securityService) {
        this.submissions = submissions;
        this.access = access;
        this.grading = grading;
        this.cursors = cursors;
        this.viewSupport = viewSupport;
        this.securityService = securityService;
    }

    @Get("/submissions/{id}")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    @View("submission/detail")
    public Map<String, Object> detail(Long id, HttpRequest<?> request) {
        SessionUser user = AuthSupport.require(securityService);
        Submission submission = access.assertCanAccessSubmission(user, id);
        Assignment assignment = access.requireAssignment(submission.getAssignmentId());
        Course course = access.requireCourse(assignment.getCourseId());
        User student = submissions.studentOf(submission);
        boolean staffView = user.isAdmin() || user.staffCourses().containsKey(assignment.getCourseId());
        Submission visible;
        if (staffView) {
            visible = submission;
        } else {
            // Students never receive draft grades, feedback or private notes — not even in the model.
            visible = SubmissionService.studentVisibleCopy(submission);
        }
        Map<String, Object> detailModel = new HashMap<>();
        detailModel.put("submission", visible);
        detailModel.put("assignment", assignment);
        detailModel.put("course", course);
        detailModel.put("student", student);
        detailModel.put("files", submissions.filesFor(submission.getId()));
        detailModel.put("staffView", staffView);
        detailModel.put("history", staffView ? grading.history(submission.getId()) : List.of());
        detailModel.put("canPublishOrRelease", user.canPublishOrRelease(assignment.getCourseId()));
        return viewSupport.render(request, "Submission by " + (student == null ? "student" : student.getDisplayName()), detailModel);
    }

    @Get("/assignments/{assignmentId}/submissions")
    @Secured({SecurityRule.IS_AUTHENTICATED})
    @View("submission/queue")
    public Map<String, Object> queue(Long assignmentId,
                                     @Nullable @QueryValue("courseId") Long courseId,
                                     @Nullable @QueryValue("status") String status,
                                     @Nullable @QueryValue("q") String q,
                                     @Nullable @QueryValue("sort") String sort,
                                     @QueryValue(defaultValue = "0") int page,
                                     HttpRequest<?> request) {
        SessionUser user = AuthSupport.require(securityService);
        Assignment assignment = access.requireAssignment(assignmentId);
        access.assertCanManageAssignment(user, assignmentId);
        var result = submissions.queue(user, courseId, assignmentId, status, q, sort, Pageable.from(page, 15));
        Map<Long, User> studentNames = new HashMap<>();
        for (Submission s : result.getContent()) {
            User student = submissions.studentOf(s);
            if (student != null) {
                studentNames.put(s.getId(), student);
            }
        }
        Course course = access.requireCourse(assignment.getCourseId());
        return viewSupport.render(request, "Submissions — " + assignment.getTitle(), Map.of(
                "assignment", assignment,
                "course", course,
                "submissions", result.getContent(),
                "studentNames", studentNames,
                "page", page,
                "totalPages", Math.max(1, result.getTotalPages()),
                "status", status == null ? "" : status,
                "q", q == null ? "" : q,
                "sort", sort == null ? "recent" : sort,
                "statuses", ReferenceDataService.SUBMISSION_STATUSES));
    }

    /** Resumes the grading queue from the encrypted "where I stopped" cursor carried on the URL. */
    @Get("/submissions/queue/slice")
    @Secured({"INSTRUCTOR", "TEACHING_ASSISTANT", "SCHOOL_ADMIN"})
    public HttpResponse<?> queueSlice(@QueryValue("at") String at, HttpRequest<?> request) {
        SessionUser user = AuthSupport.require(securityService);
        try {
            long position = cursors.reveal(at);
            var result = submissions.queue(user, null, null, null, null, null, Pageable.from((int) position, 15));
            return HttpResponse.ok(Map.of("position", position, "rows", result.getContent().size()))
                    .contentType(MediaType.APPLICATION_JSON_TYPE);
        } catch (javax.crypto.BadPaddingException e) {
            return HttpResponse.status(HttpStatus.FORBIDDEN).body("queue cursor rejected");
        } catch (IllegalArgumentException | java.security.GeneralSecurityException e) {
            return HttpResponse.status(HttpStatus.UNPROCESSABLE_ENTITY).body("queue cursor has an unsupported format");
        }
    }

    /**
     * Student submission (create/replace/resubmit) with attachments.
     * Parts are read from a {@link MultipartBody} because Micronaut cannot bind
     * a list of file parts with {@code @Part}.
     */
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Post("/assignments/{assignmentId}/submit")
    @Secured("STUDENT")
    public HttpResponse<?> submit(Long assignmentId, @Body MultipartBody body, HttpRequest<?> request) {
        SessionUser user = AuthSupport.require(securityService);
        try {
            List<CompletedPart> parts = Flux.from(body).collectList().block(Duration.ofSeconds(30));
            String text = null;
            List<CompletedFileUpload> files = new ArrayList<>();
            if (parts != null) {
                for (CompletedPart part : parts) {
                    if (part instanceof CompletedFileUpload upload) {
                        if (upload.getFilename() != null && !upload.getFilename().isBlank()) {
                            files.add(upload);
                        }
                    } else if ("text".equals(part.getName())) {
                        text = new String(part.getBytes(), StandardCharsets.UTF_8);
                    }
                }
            }
            Submission submission = submissions.submit(user, assignmentId, text, files);
            ViewSupport.flash(request, "success", "Submission saved. Status: " + submission.getStatus() + ".");
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        } catch (Exception e) {
            LOG.error("submission failed", e);
            ViewSupport.flash(request, "error", "The submission could not be saved. Please try again.");
        }
        return HttpResponse.seeOther(URI.create("/assignments/" + assignmentId));
    }
}
