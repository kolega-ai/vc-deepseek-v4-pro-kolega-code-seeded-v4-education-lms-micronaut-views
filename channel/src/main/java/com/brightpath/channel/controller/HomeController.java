package com.brightpath.channel.controller;

import com.brightpath.channel.security.AuthSupport;
import com.brightpath.channel.security.SessionUser;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.security.utils.SecurityService;

import java.net.URI;

/** Root entry point: routes to the role-appropriate dashboard. */
@Controller
@ExecuteOn(TaskExecutors.BLOCKING)
public class HomeController {

    private final SecurityService securityService;

    public HomeController(SecurityService securityService) {
        this.securityService = securityService;
    }

    @Get
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public HttpResponse<?> index() {
        SessionUser user = AuthSupport.require(securityService);
        return HttpResponse.seeOther(URI.create(user.isAdmin() ? "/admin" : "/dashboard"));
    }
}
