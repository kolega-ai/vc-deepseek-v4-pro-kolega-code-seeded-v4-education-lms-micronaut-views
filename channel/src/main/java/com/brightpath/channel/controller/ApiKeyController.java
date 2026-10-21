package com.brightpath.channel.controller;

import com.brightpath.channel.dto.Forms;
import com.brightpath.channel.security.AppException;
import com.brightpath.channel.security.AuthSupport;
import com.brightpath.channel.security.SessionUser;
import com.brightpath.channel.service.ApiKeyService;
import com.brightpath.channel.service.ViewSupport;
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
import java.util.Arrays;
import java.util.Map;

/** Admin API key management for the JSON integration surface. */
@Controller("/admin/api-keys")
@ExecuteOn(TaskExecutors.BLOCKING)
@Secured("SCHOOL_ADMIN")
public class ApiKeyController {

    private final ApiKeyService apiKeys;
    private final ViewSupport viewSupport;
    private final SecurityService securityService;

    public ApiKeyController(ApiKeyService apiKeys, ViewSupport viewSupport, SecurityService securityService) {
        this.apiKeys = apiKeys;
        this.viewSupport = viewSupport;
        this.securityService = securityService;
    }

    @Get
    @View("admin/api-keys")
    public Map<String, Object> list(HttpRequest<?> request) {
        return viewSupport.render(request, "API keys", Map.of(
                "keys", apiKeys.list(),
                "scopes", java.util.List.of(ApiKeyService.SCOPE_READ_COURSES, ApiKeyService.SCOPE_READ_GRADES)));
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post
    public HttpResponse<?> create(@Body Forms.ApiKeyForm form, HttpRequest<?> request) {
        SessionUser admin = AuthSupport.require(securityService);
        try {
            String plaintext = apiKeys.create(admin, form.name(), form.scopes());
            ViewSupport.flash(request, "success",
                    "API key created. Store it now — it is shown only once: " + plaintext);
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        }
        return HttpResponse.seeOther(URI.create("/admin/api-keys"));
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/{id}/toggle")
    public HttpResponse<?> toggle(Long id, HttpRequest<?> request) {
        SessionUser admin = AuthSupport.require(securityService);
        try {
            boolean current = apiKeys.list().stream()
                    .filter(k -> k.getId().equals(id)).findFirst()
                    .orElseThrow(() -> AppException.notFound("API key not found.")).isEnabled();
            apiKeys.setEnabled(admin, id, !current);
            ViewSupport.flash(request, "success", current ? "API key disabled." : "API key enabled.");
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        }
        return HttpResponse.seeOther(URI.create("/admin/api-keys"));
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/{id}/revoke")
    public HttpResponse<?> revoke(Long id, HttpRequest<?> request) {
        SessionUser admin = AuthSupport.require(securityService);
        try {
            apiKeys.revoke(admin, id);
            ViewSupport.flash(request, "success", "API key revoked.");
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        }
        return HttpResponse.seeOther(URI.create("/admin/api-keys"));
    }
}
