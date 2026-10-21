package com.brightpath.channel.controller;

import com.brightpath.channel.domain.Cohort;
import com.brightpath.channel.domain.Enrollment;
import com.brightpath.channel.domain.User;
import com.brightpath.channel.repository.CohortRepository;
import com.brightpath.channel.repository.EnrollmentRepository;
import com.brightpath.channel.repository.UserRepository;
import com.brightpath.channel.security.AppException;
import com.brightpath.channel.security.AuthSupport;
import com.brightpath.channel.security.SessionUser;
import com.brightpath.channel.service.EnrollmentService;
import com.brightpath.channel.service.ViewSupport;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.utils.SecurityService;
import io.micronaut.views.View;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Admin enrollment management: approvals queue and roster-wide actions. */
@Controller("/admin/enrollments")
@ExecuteOn(TaskExecutors.BLOCKING)
@Secured("SCHOOL_ADMIN")
public class AdminEnrollmentController {

    private final EnrollmentService enrollments;
    private final EnrollmentRepository enrollmentRepo;
    private final CohortRepository cohorts;
    private final UserRepository users;
    private final ViewSupport viewSupport;
    private final SecurityService securityService;

    public AdminEnrollmentController(EnrollmentService enrollments, EnrollmentRepository enrollmentRepo,
                                     CohortRepository cohorts, UserRepository users,
                                     ViewSupport viewSupport, SecurityService securityService) {
        this.enrollments = enrollments;
        this.enrollmentRepo = enrollmentRepo;
        this.cohorts = cohorts;
        this.users = users;
        this.viewSupport = viewSupport;
        this.securityService = securityService;
    }

    @Get
    @View("admin/enrollments")
    public Map<String, Object> list(@Nullable @QueryValue("cohortId") Long cohortId,
                                    @Nullable @QueryValue("status") String status,
                                    HttpRequest<?> request) {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<Cohort> cohortList = cohortId != null
                ? List.of(cohorts.findById(cohortId).orElseThrow(() -> AppException.notFound("Cohort not found.")))
                : cohorts.findAll();
        for (Cohort cohort : cohortList) {
            for (Enrollment e : enrollmentRepo.findByCohortId(cohort.getId())) {
                if (status != null && !status.isBlank() && !status.equals(e.getStatus())) {
                    continue;
                }
                User student = users.findById(e.getStudentId()).orElse(null);
                rows.add(Map.of("enrollment", e, "cohort", cohort, "student", student));
            }
        }
        return viewSupport.render(request, "Enrollments", Map.of(
                "rows", rows,
                "cohorts", cohortList,
                "cohortId", cohortId == null ? "" : String.valueOf(cohortId),
                "status", status == null ? "" : status,
                "statuses", com.brightpath.channel.service.ReferenceDataService.ENROLLMENT_STATUSES));
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/{id}/approve")
    public HttpResponse<?> approve(Long id, @Nullable @QueryValue("redirect") String redirect, HttpRequest<?> request) {
        SessionUser admin = AuthSupport.require(securityService);
        try {
            enrollments.approve(admin, id);
            ViewSupport.flash(request, "success", "Enrollment approved.");
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        }
        return HttpResponse.seeOther(URI.create(redirect == null ? "/admin/enrollments" : redirect));
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/{id}/drop")
    public HttpResponse<?> drop(Long id, @Nullable @QueryValue("redirect") String redirect, HttpRequest<?> request) {
        SessionUser admin = AuthSupport.require(securityService);
        try {
            enrollments.drop(admin, id);
            ViewSupport.flash(request, "success", "Enrollment dropped.");
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        }
        return HttpResponse.seeOther(URI.create(redirect == null ? "/admin/enrollments" : redirect));
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/{id}/complete")
    public HttpResponse<?> complete(Long id, @Nullable @QueryValue("redirect") String redirect, HttpRequest<?> request) {
        SessionUser admin = AuthSupport.require(securityService);
        try {
            enrollments.complete(admin, id);
            ViewSupport.flash(request, "success", "Enrollment completed.");
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        }
        return HttpResponse.seeOther(URI.create(redirect == null ? "/admin/enrollments" : redirect));
    }

    /** Support-tool correction for a mis-drop or mis-complete; see EnrollmentService.resolveState. */
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/{id}/resolve-state")
    public HttpResponse<?> resolveState(Long id, @QueryValue("target") String target,
                                        @Nullable @QueryValue("redirect") String redirect, HttpRequest<?> request) {
        SessionUser admin = AuthSupport.require(securityService);
        try {
            enrollments.resolveState(admin, id, target);
            ViewSupport.flash(request, "success", "Enrollment status set to \"" + target + "\".");
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        }
        return HttpResponse.seeOther(URI.create(redirect == null ? "/admin/enrollments" : redirect));
    }
}
