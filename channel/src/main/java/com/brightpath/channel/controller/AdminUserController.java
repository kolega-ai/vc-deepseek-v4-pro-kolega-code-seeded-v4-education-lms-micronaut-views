package com.brightpath.channel.controller;

import com.brightpath.channel.domain.User;
import com.brightpath.channel.dto.Forms;
import com.brightpath.channel.repository.UserRepository;
import com.brightpath.channel.security.AppException;
import com.brightpath.channel.security.AuthSupport;
import com.brightpath.channel.security.Roles;
import com.brightpath.channel.security.SessionUser;
import com.brightpath.channel.service.UserService;
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
import io.micronaut.security.utils.SecurityService;
import io.micronaut.views.View;

import java.net.URI;
import java.util.Map;

/** Admin user management: search, create, edit, role changes, enable/disable, password reset. */
@Controller("/admin/users")
@ExecuteOn(TaskExecutors.BLOCKING)
@Secured("SCHOOL_ADMIN")
public class AdminUserController {

    private final UserRepository users;
    private final UserService userService;
    private final com.brightpath.channel.service.EnrollmentService enrollmentService;
    private final com.brightpath.channel.repository.CohortRepository cohortRepository;
    private final com.brightpath.channel.repository.CourseRepository courseRepository;
    private final com.brightpath.channel.service.DirectorySearchService directory;
    private final ViewSupport viewSupport;
    private final SecurityService securityService;

    public AdminUserController(UserRepository users, UserService userService,
                               com.brightpath.channel.service.EnrollmentService enrollmentService,
                               com.brightpath.channel.repository.CohortRepository cohortRepository,
                               com.brightpath.channel.repository.CourseRepository courseRepository,
                               com.brightpath.channel.service.DirectorySearchService directory,
                               ViewSupport viewSupport, SecurityService securityService) {
        this.users = users;
        this.userService = userService;
        this.enrollmentService = enrollmentService;
        this.cohortRepository = cohortRepository;
        this.courseRepository = courseRepository;
        this.directory = directory;
        this.viewSupport = viewSupport;
        this.securityService = securityService;
    }

    @Get
    @View("admin/users")
    public Map<String, Object> list(@Nullable @QueryValue("q") String q,
                                    @Nullable @QueryValue("role") String role,
                                    @Nullable @QueryValue("enabled") String enabled,
                                    @QueryValue(defaultValue = "0") int page,
                                    HttpRequest<?> request) {
        Boolean enabledFilter = enabled == null || enabled.isBlank() ? null : Boolean.parseBoolean(enabled);
        var result = users.search(q, role, enabledFilter, Pageable.from(page, 20));
        return viewSupport.render(request, "Users", Map.of(
                "users", result.getContent(),
                "q", q == null ? "" : q,
                "role", role == null ? "" : role,
                "enabled", enabled == null ? "" : enabled,
                "page", page,
                "totalPages", Math.max(1, result.getTotalPages()),
                "roles", Roles.ALL));
    }

    @Get("/new")
    @View("admin/user-form")
    public Map<String, Object> newForm(HttpRequest<?> request) {
        return viewSupport.render(request, "New user", Map.of(
                "user", null,
                "roles", Roles.ALL,
                "form", new Forms.AdminUserForm("", "", "", Roles.STUDENT, true, null)));
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post
    public HttpResponse<?> create(@Body Forms.AdminUserForm form, HttpRequest<?> request) {
        SessionUser admin = AuthSupport.require(securityService);
        try {
            userService.adminCreateUser(admin, form.username(), form.email(), form.displayName(),
                    form.role(), Boolean.TRUE.equals(form.enabled()), baseUrl(request));
            ViewSupport.flash(request, "success", "User created. A setup email with a password link was queued.");
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        }
        return HttpResponse.seeOther(URI.create("/admin/users"));
    }

    @Get("/{id}")
    @View("admin/user-detail")
    public Map<String, Object> detail(Long id, HttpRequest<?> request) {
        User user = users.findById(id).orElseThrow(() -> AppException.notFound("User not found."));
        java.util.List<Map<String, Object>> enrollmentRows = new java.util.ArrayList<>();
        for (var enrollment : enrollmentService.listForStudent(id)) {
            cohortRepository.findById(enrollment.getCohortId()).ifPresent(cohort ->
                    courseRepository.findById(cohort.getCourseId()).ifPresent(course ->
                            enrollmentRows.add(Map.of(
                                    "cohortName", cohort.getName(),
                                    "courseTitle", course.getTitle(),
                                    "status", enrollment.getStatus()))));
        }
        return viewSupport.render(request, user.getDisplayName(), Map.of(
                "user", user,
                "roles", Roles.ALL,
                "enrollmentRows", enrollmentRows));
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/{id}/edit")
    public HttpResponse<?> update(Long id, @Body Forms.AdminUserForm form, HttpRequest<?> request) {
        SessionUser admin = AuthSupport.require(securityService);
        try {
            userService.adminUpdateUser(admin, id, form.displayName(), form.email(),
                    form.role(), form.enabled());
            ViewSupport.flash(request, "success", "User updated.");
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        }
        return HttpResponse.seeOther(URI.create("/admin/users/" + id));
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/{id}/reset-password")
    public HttpResponse<?> resetPassword(Long id, HttpRequest<?> request) {
        SessionUser admin = AuthSupport.require(securityService);
        try {
            String link = userService.adminIssuePasswordReset(admin, id, baseUrl(request));
            ViewSupport.flash(request, "success",
                    "Reset link issued (also emailed): " + link);
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        }
        return HttpResponse.seeOther(URI.create("/admin/users/" + id));
    }

    /** Searches the school's staff directory so an admin can confirm an account before creating it. */
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/directory")
    public HttpResponse<?> directory(@Body Forms.DirectoryQueryForm form, HttpRequest<?> request) {
        SessionUser admin = AuthSupport.require(securityService);
        try {
            var found = directory.findByUid(form.uid());
            String names = found.stream().map(row -> row.get("name") + " <" + row.get("mail") + ">")
                    .reduce((a, b) -> a + ", " + b).orElse("(none)");
            ViewSupport.flash(request, "success",
                    "Directory matched " + found.size() + " entrie(s) for \"" + form.uid() + "\": " + names);
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        }
        return HttpResponse.seeOther(URI.create("/admin/users"));
    }

    private String baseUrl(HttpRequest<?> request) {
        return "http://" + request.getServerName() + (request.getUri().getPort() > 0 && request.getUri().getPort() != 80 ? ":" + request.getUri().getPort() : "");
    }
}
