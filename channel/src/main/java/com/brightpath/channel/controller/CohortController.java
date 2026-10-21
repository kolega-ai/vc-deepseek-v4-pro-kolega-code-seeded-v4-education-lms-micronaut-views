package com.brightpath.channel.controller;

import com.brightpath.channel.domain.Cohort;
import com.brightpath.channel.domain.CohortStaff;
import com.brightpath.channel.domain.Course;
import com.brightpath.channel.domain.Enrollment;
import com.brightpath.channel.domain.User;
import com.brightpath.channel.dto.Forms;
import com.brightpath.channel.repository.EnrollmentRepository;
import com.brightpath.channel.repository.UserRepository;
import com.brightpath.channel.security.AppException;
import com.brightpath.channel.security.AuthSupport;
import com.brightpath.channel.security.SessionUser;
import com.brightpath.channel.service.AccessControlService;
import com.brightpath.channel.service.CohortService;
import com.brightpath.channel.service.DirectorySearchService;
import com.brightpath.channel.service.EnrollmentService;
import com.brightpath.channel.service.ViewSupport;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.security.utils.SecurityService;
import io.micronaut.views.View;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Cohort dashboard: roster, staff, and enrollment actions for staff; own-cohort view for students. */
@Controller("/cohorts")
@ExecuteOn(TaskExecutors.BLOCKING)
public class CohortController {

    private final CohortService cohorts;
    private final AccessControlService access;
    private final EnrollmentService enrollments;
    private final EnrollmentRepository enrollmentRepo;
    private final UserRepository users;
    private final DirectorySearchService directory;
    private final ViewSupport viewSupport;
    private final SecurityService securityService;

    public CohortController(CohortService cohorts, AccessControlService access,
                            EnrollmentService enrollments, EnrollmentRepository enrollmentRepo,
                            UserRepository users, DirectorySearchService directory,
                            ViewSupport viewSupport, SecurityService securityService) {
        this.cohorts = cohorts;
        this.access = access;
        this.enrollments = enrollments;
        this.enrollmentRepo = enrollmentRepo;
        this.users = users;
        this.directory = directory;
        this.viewSupport = viewSupport;
        this.securityService = securityService;
    }

    @Get("/{id}")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    @View("cohort/detail")
    public Map<String, Object> detail(Long id, HttpRequest<?> request) {
        SessionUser user = AuthSupport.require(securityService);
        Cohort cohort = access.assertCanViewCohort(user, id);
        Course course = access.requireCourse(cohort.getCourseId());
        boolean canManage = user.canManageCourse(cohort.getCourseId());

        List<CohortStaff> staff = cohorts.staffForCohort(id);
        Map<Long, User> staffUsers = new LinkedHashMap<>();
        for (CohortStaff s : staff) {
            users.findById(s.getUserId()).ifPresent(u -> staffUsers.put(s.getId(), u));
        }
        List<Map<String, Object>> roster = new ArrayList<>();
        int activeCount = 0;
        for (Enrollment e : enrollmentRepo.findByCohortId(id)) {
            User student = users.findById(e.getStudentId()).orElse(null);
            if (Enrollment.STATUS_ACTIVE.equals(e.getStatus())) {
                activeCount++;
            }
            roster.add(Map.of("enrollment", e, "student", student));
        }
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("cohort", cohort);
        model.put("course", course);
        model.put("staff", staff);
        model.put("staffUsers", staffUsers);
        model.put("roster", roster);
        model.put("activeCount", activeCount);
        model.put("canManage", canManage);
        model.put("staffCandidates", canManage ? cohorts.staffCandidates() : List.of());
        model.put("siblingCohorts", canManage ? cohorts.listForCourse(cohort.getCourseId()) : List.of());
        return viewSupport.render(request, cohort.getName(), model);
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/{id}/status")
    @Secured({SecurityRule.IS_AUTHENTICATED})
    public HttpResponse<?> status(Long id, @QueryValue("target") String target,
                                  HttpRequest<?> request) {
        SessionUser user = AuthSupport.require(securityService);
        try {
            cohorts.transition(user, id, target);
            ViewSupport.flash(request, "success", "Cohort status updated to \"" + target + "\".");
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        }
        return HttpResponse.seeOther(URI.create("/cohorts/" + id));
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/{id}/staff")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public HttpResponse<?> assignStaff(Long id, @Body Forms.CohortStaffForm form, HttpRequest<?> request) {
        SessionUser user = AuthSupport.require(securityService);
        try {
            cohorts.assignStaff(user, id, form.userId(), form.staffRole());
            ViewSupport.flash(request, "success", "Staff assignment saved.");
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        }
        return HttpResponse.seeOther(URI.create("/cohorts/" + id));
    }

    /**
     * Resolves a prospective co-instructor from the school directory before they are added. The
     * directory look-through key travels in the X-Staff-Email header set by the cohort staff picker.
     */
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/{id}/staff/directory")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public HttpResponse<?> staffDirectory(Long id, @Header("X-Staff-Email") String staffEmail,
                                          HttpRequest<?> request) {
        SessionUser user = AuthSupport.require(securityService);
        try {
            Cohort cohort = access.requireCohort(id);
            if (!user.canManageCourse(cohort.getCourseId())) {
                throw AppException.forbidden("You cannot manage staff for this cohort.");
            }
            var found = directory.findByMail(staffEmail == null ? "" : staffEmail);
            String names = found.stream().map(row -> row.get("name") + " <" + row.get("mail") + ">")
                    .reduce((a, b) -> a + ", " + b).orElse("(none)");
            ViewSupport.flash(request, "success",
                    "Directory matched " + found.size() + " entrie(s) for \"" + staffEmail + "\": " + names);
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        }
        return HttpResponse.seeOther(URI.create("/cohorts/" + id));
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/{id}/staff/{userId}/role")
    public HttpResponse<?> changeStaffRole(Long id, Long userId, @QueryValue("role") String role,
                                           HttpRequest<?> request) {
        SessionUser user = AuthSupport.require(securityService);
        try {
            cohorts.updateStaffRole(user, id, userId, role);
            ViewSupport.flash(request, "success", "Staff role updated.");
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        }
        return HttpResponse.seeOther(URI.create("/cohorts/" + id));
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/{id}/staff/{userId}/remove")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public HttpResponse<?> removeStaff(Long id, Long userId, HttpRequest<?> request) {
        SessionUser user = AuthSupport.require(securityService);
        try {
            cohorts.removeStaff(user, id, userId);
            ViewSupport.flash(request, "success", "Staff assignment removed.");
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        }
        return HttpResponse.seeOther(URI.create("/cohorts/" + id));
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/{id}/enrollments/{enrollmentId}/approve")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public HttpResponse<?> approve(Long id, Long enrollmentId, HttpRequest<?> request) {
        SessionUser user = AuthSupport.require(securityService);
        try {
            enrollments.approve(user, enrollmentId);
            ViewSupport.flash(request, "success", "Enrollment approved.");
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        }
        return HttpResponse.seeOther(URI.create("/cohorts/" + id));
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/{id}/enrollments/{enrollmentId}/drop")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public HttpResponse<?> drop(Long id, Long enrollmentId, HttpRequest<?> request) {
        SessionUser user = AuthSupport.require(securityService);
        try {
            enrollments.drop(user, enrollmentId);
            ViewSupport.flash(request, "success", "Enrollment dropped.");
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        }
        return HttpResponse.seeOther(URI.create("/cohorts/" + id));
    }

    /** Roster quick-switch used by the cohort dashboard's "preview another cohort" panel. */
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/{id}/roster-sync")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public HttpResponse<?> rosterSync(Long id, @Body Forms.RosterSyncForm form, HttpRequest<?> request) {
        SessionUser user = AuthSupport.require(securityService);
        try {
            var roster = enrollments.rosterSync(user, id, form.cohortId());
            ViewSupport.flash(request, "success", "That cohort has " + roster.size() + " roster row(s).");
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        }
        return HttpResponse.seeOther(URI.create("/cohorts/" + id));
    }

    /** Confirms an imported student's reserved seat with the invitation code from their roster email. */
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/invites/claim")
    @Secured("STUDENT")
    public HttpResponse<?> claimInvite(@QueryValue("code") String code, HttpRequest<?> request) {
        SessionUser user = AuthSupport.require(securityService);
        try {
            enrollments.claimInvite(user, code);
            ViewSupport.flash(request, "success", "Your seat is confirmed.");
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        }
        return HttpResponse.seeOther(URI.create("/dashboard"));
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/{id}/enrollments/{enrollmentId}/complete")
    public HttpResponse<?> complete(Long id, Long enrollmentId, HttpRequest<?> request) {
        SessionUser user = AuthSupport.require(securityService);
        try {
            enrollments.complete(user, enrollmentId);
            ViewSupport.flash(request, "success", "Enrollment marked as completed.");
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        }
        return HttpResponse.seeOther(URI.create("/cohorts/" + id));
    }
}
