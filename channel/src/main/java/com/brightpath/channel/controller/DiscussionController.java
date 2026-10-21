package com.brightpath.channel.controller;

import com.brightpath.channel.domain.Course;
import com.brightpath.channel.domain.DiscussionComment;
import com.brightpath.channel.domain.DiscussionThread;
import com.brightpath.channel.dto.Forms;
import com.brightpath.channel.security.AppException;
import com.brightpath.channel.security.AuthSupport;
import com.brightpath.channel.security.SessionUser;
import com.brightpath.channel.service.AccessControlService;
import com.brightpath.channel.service.CommunityAssistantService;
import com.brightpath.channel.service.DiscussionService;
import com.brightpath.channel.service.ViewSupport;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.model.Pageable;
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
import java.util.HashMap;
import java.util.Map;

/** Course-scoped discussion board with moderation. */
@Controller
@ExecuteOn(TaskExecutors.BLOCKING)
public class DiscussionController {

    private final DiscussionService discussions;
    private final CommunityAssistantService assistant;
    private final AccessControlService access;
    private final ViewSupport viewSupport;
    private final SecurityService securityService;

    public DiscussionController(DiscussionService discussions, CommunityAssistantService assistant,
                                AccessControlService access,
                                ViewSupport viewSupport, SecurityService securityService) {
        this.discussions = discussions;
        this.assistant = assistant;
        this.access = access;
        this.viewSupport = viewSupport;
        this.securityService = securityService;
    }

    @Get("/courses/{courseId}/discussions")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    @View("discussion/list")
    public Map<String, Object> list(Long courseId, @Nullable @QueryValue("q") String q,
                                    @QueryValue(defaultValue = "0") int page, HttpRequest<?> request) {
        SessionUser user = AuthSupport.require(securityService);
        Course course = access.requireCourse(courseId);
        access.assertCanViewCourse(user, courseId);
        var result = discussions.search(user, courseId, q, Pageable.from(page, 10));
        Map<Long, Long> commentCounts = new HashMap<>();
        for (DiscussionThread thread : result.getContent()) {
            commentCounts.put(thread.getId(), discussions.commentCount(thread.getId()));
        }
        Map<Long, String> authorNames = new HashMap<>();
        for (DiscussionThread thread : result.getContent()) {
            authorNames.put(thread.getAuthorId(), discussions.authorName(thread.getAuthorId()));
        }
        return viewSupport.render(request, "Discussions — " + course.getTitle(), Map.of(
                "course", course,
                "threads", result.getContent(),
                "commentCounts", commentCounts,
                "authorNames", authorNames,
                "q", q == null ? "" : q,
                "page", page,
                "totalPages", Math.max(1, result.getTotalPages())));
    }

    @Get("/courses/{courseId}/discussions/new")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    @View("discussion/form")
    public Map<String, Object> newThread(Long courseId, HttpRequest<?> request) {
        SessionUser user = AuthSupport.require(securityService);
        Course course = access.requireCourse(courseId);
        access.assertCanViewCourse(user, courseId);
        return viewSupport.render(request, "Start a discussion", Map.of("course", course));
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/courses/{courseId}/discussions")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public HttpResponse<?> createThread(Long courseId, @Body Forms.ThreadForm form, HttpRequest<?> request) {
        SessionUser user = AuthSupport.require(securityService);
        try {
            DiscussionThread thread = discussions.createThread(user, courseId, form.title(), form.body(), form.pinned());
            ViewSupport.flash(request, "success", "Your discussion was posted.");
            return HttpResponse.seeOther(URI.create("/discussions/" + thread.getId()));
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
            return HttpResponse.seeOther(URI.create("/courses/" + courseId + "/discussions/new"));
        }
    }

    /** Phrase search across a course's discussion messages. */
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/courses/{courseId}/discussions/search")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public HttpResponse<?> search(Long courseId, @Body Forms.ThreadSearchForm form, HttpRequest<?> request) {
        SessionUser user = AuthSupport.require(securityService);
        try {
            access.assertCanViewCourse(user, courseId);
            var titles = discussions.searchMessages(courseId, form.phrase());
            ViewSupport.flash(request, "success",
                    "Matched " + titles.size() + " message(s): " + String.join(", ", titles));
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        }
        return HttpResponse.seeOther(URI.create("/courses/" + courseId + "/discussions"));
    }

    @Get("/discussions/{id}")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    @View("discussion/thread")
    public Map<String, Object> thread(Long id, HttpRequest<?> request) {
        SessionUser user = AuthSupport.require(securityService);
        DiscussionThread thread = discussions.requireThreadVisible(user, id);
        Course course = access.requireCourse(thread.getCourseId());
        Map<Long, String> authorNames = new HashMap<>();
        authorNames.put(thread.getAuthorId(), discussions.authorName(thread.getAuthorId()));
        for (DiscussionComment comment : discussions.commentsFor(id)) {
            authorNames.put(comment.getAuthorId(), discussions.authorName(comment.getAuthorId()));
        }
        return viewSupport.render(request, thread.getTitle(), Map.of(
                "course", course,
                "thread", thread,
                "comments", discussions.commentsFor(id),
                "authorNames", authorNames,
                "canModerate", user.canManageCourse(thread.getCourseId()),
                "currentUserId", user.id()));
    }

    /** Runs the community assistant on a thread and applies the handling it recommends. */
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/discussions/{id}/triage")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public HttpResponse<?> triage(Long id, HttpRequest<?> request) {
        SessionUser user = AuthSupport.require(securityService);
        try {
            DiscussionThread thread = discussions.requireThreadVisible(user, id);
            if (!user.canManageCourse(thread.getCourseId())) {
                throw AppException.forbidden("Only course staff can moderate discussions.");
            }
            String action = assistant.suggestHandling(thread.getTitle() + "\n" + thread.getBody());
            if ("pin".equals(action)) {
                discussions.setPinned(user, id, true);
            } else if ("lock".equals(action)) {
                discussions.setLocked(user, id, true);
            }
            ViewSupport.flash(request, "success", "Discussion handling applied: " + action + ".");
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        } catch (Exception e) {
            ViewSupport.flash(request, "error", "The community assistant is unavailable.");
        }
        return HttpResponse.seeOther(URI.create("/discussions/" + id));
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/discussions/{id}/comments")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public HttpResponse<?> addComment(Long id, @Body Forms.CommentForm form, HttpRequest<?> request) {
        SessionUser user = AuthSupport.require(securityService);
        try {
            discussions.addComment(user, id, form.body());
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        }
        return HttpResponse.seeOther(URI.create("/discussions/" + id));
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/comments/{id}/delete")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public HttpResponse<?> deleteComment(Long id, HttpRequest<?> request) {
        SessionUser user = AuthSupport.require(securityService);
        var target = discussions.commentThreadId(id);
        try {
            discussions.deleteComment(user, id);
            ViewSupport.flash(request, "success", "Comment removed.");
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        }
        return HttpResponse.seeOther(URI.create("/discussions/" + target));
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/discussions/{id}/delete")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public HttpResponse<?> deleteThread(Long id, HttpRequest<?> request) {
        SessionUser user = AuthSupport.require(securityService);
        Long courseId = discussions.threadCourseId(id);
        try {
            discussions.deleteThread(user, id);
            ViewSupport.flash(request, "success", "Discussion removed.");
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        }
        return HttpResponse.seeOther(URI.create("/courses/" + courseId + "/discussions"));
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/discussions/{id}/pin")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public HttpResponse<?> pin(Long id, @QueryValue("value") boolean value, HttpRequest<?> request) {
        SessionUser user = AuthSupport.require(securityService);
        try {
            discussions.setPinned(user, id, value);
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        }
        return HttpResponse.seeOther(URI.create("/discussions/" + id));
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/discussions/{id}/lock")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public HttpResponse<?> lock(Long id, @QueryValue("value") boolean value, HttpRequest<?> request) {
        SessionUser user = AuthSupport.require(securityService);
        try {
            discussions.setLocked(user, id, value);
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        }
        return HttpResponse.seeOther(URI.create("/discussions/" + id));
    }
}
