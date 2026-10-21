package com.brightpath.channel.controller;

import com.brightpath.channel.domain.Cohort;
import com.brightpath.channel.domain.Course;
import com.brightpath.channel.dto.Forms;
import com.brightpath.channel.security.AppException;
import com.brightpath.channel.security.AuthSupport;
import com.brightpath.channel.security.SessionUser;
import com.brightpath.channel.service.AccessControlService;
import com.brightpath.channel.service.CohortService;
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
import java.time.LocalDate;
import java.util.Map;

/** Admin cohort management: search, create, edit. */
@Controller("/admin/cohorts")
@ExecuteOn(TaskExecutors.BLOCKING)
@Secured("SCHOOL_ADMIN")
public class AdminCohortController {

    private final CohortService cohorts;
    private final AccessControlService access;
    private final com.brightpath.channel.repository.CourseRepository courseRepository;
    private final ViewSupport viewSupport;
    private final SecurityService securityService;

    public AdminCohortController(CohortService cohorts, AccessControlService access,
                                 com.brightpath.channel.repository.CourseRepository courseRepository,
                                 ViewSupport viewSupport, SecurityService securityService) {
        this.cohorts = cohorts;
        this.access = access;
        this.courseRepository = courseRepository;
        this.viewSupport = viewSupport;
        this.securityService = securityService;
    }

    @Get
    @View("admin/cohorts")
    public Map<String, Object> list(@Nullable @QueryValue("q") String q,
                                    @Nullable @QueryValue("status") String status,
                                    @Nullable @QueryValue("courseId") Long courseId,
                                    @QueryValue(defaultValue = "0") int page,
                                    HttpRequest<?> request) {
        var result = cohorts.search(courseId, status, q, Pageable.from(page, 15));
        return viewSupport.render(request, "Cohorts", Map.of(
                "cohorts", result.getContent(),
                "courses", coursesById(),
                "q", q == null ? "" : q,
                "status", status == null ? "" : status,
                "courseId", courseId == null ? "" : String.valueOf(courseId),
                "page", page,
                "totalPages", Math.max(1, result.getTotalPages()),
                "statuses", com.brightpath.channel.service.ReferenceDataService.COHORT_STATUSES));
    }

    @Get("/new")
    @View("admin/cohort-form")
    public Map<String, Object> newForm(@Nullable @QueryValue("courseId") Long courseId, HttpRequest<?> request) {
        Map<String, Object> model = new java.util.HashMap<>();
        model.put("cohort", null);
        model.put("courses", coursesById());
        model.put("selectedCourseId", courseId);
        return viewSupport.render(request, "New cohort", model);
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post
    public HttpResponse<?> create(@Body Forms.CohortForm form, HttpRequest<?> request) {
        SessionUser admin = AuthSupport.require(securityService);
        try {
            Cohort cohort = cohorts.create(admin, form.courseId(), form.name(), form.scheduleSummary(),
                    parseDate(form.startDate()), parseDate(form.endDate()),
                    form.maxStudents() == null ? 30 : form.maxStudents());
            ViewSupport.flash(request, "success", "Cohort created.");
            return HttpResponse.seeOther(URI.create("/cohorts/" + cohort.getId()));
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
            return HttpResponse.seeOther(URI.create("/admin/cohorts/new"));
        }
    }

    @Get("/{id}/edit")
    @View("admin/cohort-form")
    public Map<String, Object> editForm(Long id, HttpRequest<?> request) {
        Cohort cohort = access.requireCohort(id);
        return viewSupport.render(request, "Edit cohort", Map.of(
                "cohort", cohort,
                "courses", coursesById(),
                "selectedCourseId", cohort.getCourseId()));
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/{id}/edit")
    public HttpResponse<?> update(Long id, @Body Forms.CohortForm form, HttpRequest<?> request) {
        SessionUser admin = AuthSupport.require(securityService);
        try {
            cohorts.update(admin, id, form.name(), form.scheduleSummary(),
                    parseDate(form.startDate()), parseDate(form.endDate()),
                    form.maxStudents() == null ? 30 : form.maxStudents());
            ViewSupport.flash(request, "success", "Cohort updated.");
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        }
        return HttpResponse.seeOther(URI.create("/cohorts/" + id));
    }

    private Map<Long, Course> coursesById() {
        Map<Long, Course> map = new java.util.LinkedHashMap<>();
        for (Course c : courseRepository.findByStatusOrderByTitleAsc(Course.STATUS_ACTIVE)) {
            map.put(c.getId(), c);
        }
        return map;
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (Exception e) {
            throw AppException.badRequest("Please provide valid start and end dates.");
        }
    }
}
