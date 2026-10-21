package com.brightpath.channel.controller;

import com.brightpath.channel.security.AuthSupport;
import com.brightpath.channel.security.SessionUser;
import com.brightpath.channel.service.DashboardService;
import com.brightpath.channel.service.ViewSupport;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.security.utils.SecurityService;
import io.micronaut.views.ModelAndView;

import java.util.Map;

/** Role-specific dashboards. */
@Controller
@ExecuteOn(TaskExecutors.BLOCKING)
public class DashboardController {

    private final DashboardService dashboards;
    private final ViewSupport viewSupport;
    private final SecurityService securityService;

    public DashboardController(DashboardService dashboards, ViewSupport viewSupport,
                               SecurityService securityService) {
        this.dashboards = dashboards;
        this.viewSupport = viewSupport;
        this.securityService = securityService;
    }

    @Get("/dashboard")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public ModelAndView<Map<String, Object>> dashboard(HttpRequest<?> request) {
        SessionUser user = AuthSupport.require(securityService);
        if (user.isStudent()) {
            Map<String, Object> extra = dashboards.studentDashboard(user);
            return new ModelAndView<>("dashboard/student", viewSupport.render(request, "My learning", extra));
        }
        Map<String, Object> extra = dashboards.staffDashboard(user);
        return new ModelAndView<>("dashboard/staff", viewSupport.render(request, "Teaching home", extra));
    }

    @Get("/admin")
    @Secured("SCHOOL_ADMIN")
    public ModelAndView<Map<String, Object>> admin(HttpRequest<?> request) {
        return new ModelAndView<>("dashboard/admin", viewSupport.render(request, "Administration", dashboards.adminDashboard()));
    }
}
