package com.brightpath.channel.controller;

import com.brightpath.channel.domain.WebhookDelivery;
import com.brightpath.channel.dto.Forms;
import com.brightpath.channel.repository.WebhookDeliveryRepository;
import com.brightpath.channel.security.AppException;
import com.brightpath.channel.security.AuthSupport;
import com.brightpath.channel.security.SessionUser;
import com.brightpath.channel.service.ViewSupport;
import com.brightpath.channel.service.WebhookService;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.utils.SecurityService;
import io.micronaut.views.View;

import java.net.URI;
import java.util.Map;

/** Admin webhook endpoint management and delivery history. */
@Controller("/admin/webhooks")
@ExecuteOn(TaskExecutors.BLOCKING)
@Secured("SCHOOL_ADMIN")
public class WebhookController {

    private final WebhookService webhooks;
    private final WebhookDeliveryRepository deliveries;
    private final ViewSupport viewSupport;
    private final SecurityService securityService;

    public WebhookController(WebhookService webhooks, WebhookDeliveryRepository deliveries,
                             ViewSupport viewSupport, SecurityService securityService) {
        this.webhooks = webhooks;
        this.deliveries = deliveries;
        this.viewSupport = viewSupport;
        this.securityService = securityService;
    }

    @Get
    @View("admin/webhooks")
    public Map<String, Object> list(HttpRequest<?> request) {
        return viewSupport.render(request, "Webhooks", Map.of(
                "endpoints", webhooks.list(),
                "eventTypes", WebhookService.EVENT_TYPES));
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post
    public HttpResponse<?> create(@Body Forms.WebhookForm form, HttpRequest<?> request) {
        SessionUser admin = AuthSupport.require(securityService);
        try {
            webhooks.create(admin, form.name(), form.url(), form.secret(),
                    form.eventTypes() == null ? "" : String.join(",", form.eventTypes()));
            ViewSupport.flash(request, "success", "Webhook endpoint created.");
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        }
        return HttpResponse.seeOther(URI.create("/admin/webhooks"));
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/{id}/toggle")
    public HttpResponse<?> toggle(Long id, HttpRequest<?> request) {
        SessionUser admin = AuthSupport.require(securityService);
        try {
            boolean current = webhooks.list().stream()
                    .filter(e -> e.getId().equals(id)).findFirst()
                    .orElseThrow(() -> AppException.notFound("Webhook not found.")).isEnabled();
            webhooks.setEnabled(admin, id, !current);
            ViewSupport.flash(request, "success", current ? "Webhook disabled." : "Webhook enabled.");
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        }
        return HttpResponse.seeOther(URI.create("/admin/webhooks"));
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/{id}/delete")
    public HttpResponse<?> delete(Long id, HttpRequest<?> request) {
        SessionUser admin = AuthSupport.require(securityService);
        try {
            webhooks.delete(admin, id);
            ViewSupport.flash(request, "success", "Webhook endpoint deleted.");
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        }
        return HttpResponse.seeOther(URI.create("/admin/webhooks"));
    }

    @Get("/{id}/deliveries")
    @View("admin/webhook-deliveries")
    public Map<String, Object> deliveries(Long id, HttpRequest<?> request) {
        var endpoint = webhooks.list().stream()
                .filter(e -> e.getId().equals(id)).findFirst()
                .orElseThrow(() -> AppException.notFound("Webhook not found."));
        var result = deliveries.findByEndpointIdOrderByCreatedAtDesc(id, Pageable.from(0, 25));
        return viewSupport.render(request, "Webhook deliveries", Map.of(
                "endpoint", endpoint,
                "deliveries", result.getContent()));
    }
}
