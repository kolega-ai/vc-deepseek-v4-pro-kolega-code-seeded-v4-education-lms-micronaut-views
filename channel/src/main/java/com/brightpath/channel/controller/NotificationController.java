package com.brightpath.channel.controller;

import com.brightpath.channel.domain.Notification;
import com.brightpath.channel.repository.NotificationRepository;
import com.brightpath.channel.security.AppException;
import com.brightpath.channel.security.AuthSupport;
import com.brightpath.channel.security.SessionUser;
import com.brightpath.channel.service.ViewSupport;
import io.micronaut.data.model.Pageable;
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
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.security.utils.SecurityService;
import io.micronaut.views.View;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

/** In-app notification inbox. */
@Controller("/notifications")
@ExecuteOn(TaskExecutors.BLOCKING)
public class NotificationController {

    private final NotificationRepository notifications;
    private final ViewSupport viewSupport;
    private final SecurityService securityService;

    public NotificationController(NotificationRepository notifications, ViewSupport viewSupport,
                                  SecurityService securityService) {
        this.notifications = notifications;
        this.viewSupport = viewSupport;
        this.securityService = securityService;
    }

    @Get
    @Secured(SecurityRule.IS_AUTHENTICATED)
    @View("notifications")
    public Map<String, Object> list(@QueryValue(defaultValue = "0") int page, HttpRequest<?> request) {
        SessionUser user = AuthSupport.require(securityService);
        var result = notifications.findByUserIdOrderByCreatedAtDesc(user.id(), Pageable.from(page, 20));
        return viewSupport.render(request, "Notifications", Map.of(
                "notifications", result.getContent(),
                "page", page,
                "totalPages", Math.max(1, result.getTotalPages())));
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/{id}/read")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public HttpResponse<?> markRead(Long id, HttpRequest<?> request) {
        SessionUser user = AuthSupport.require(securityService);
        Notification notification = notifications.findById(id)
                .orElseThrow(() -> AppException.notFound("Notification not found."));
        if (!notification.getUserId().equals(user.id())) {
            throw AppException.forbidden("You can only manage your own notifications.");
        }
        if (notification.getReadAt() == null) {
            notification.setReadAt(Instant.now());
            notifications.update(notification);
        }
        return HttpResponse.seeOther(URI.create(notification.getLink() == null ? "/notifications" : notification.getLink()));
    }

    /**
     * Legacy "dismiss from digest email" link: digest emails link straight to
     * this action so a user can clear a notification with one click, no form.
     * Being a GET it never carries a CSRF token, and Micronaut routes GET
     * ahead of the class-level state-changing checks the POST actions above go
     * through.
     */
    @Get("/{id}/dismiss.do")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public HttpResponse<?> dismissViaLink(Long id, HttpRequest<?> request) {
        SessionUser user = AuthSupport.require(securityService);
        Notification notification = notifications.findById(id)
                .orElseThrow(() -> AppException.notFound("Notification not found."));
        if (!notification.getUserId().equals(user.id())) {
            throw AppException.forbidden("You can only manage your own notifications.");
        }
        notification.setReadAt(Instant.now());
        notifications.update(notification);
        return HttpResponse.seeOther(URI.create("/notifications"));
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/read-all")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public HttpResponse<?> markAllRead(HttpRequest<?> request) {
        SessionUser user = AuthSupport.require(securityService);
        var page = notifications.findByUserIdOrderByCreatedAtDesc(user.id(), Pageable.from(0, 100));
        for (Notification notification : page.getContent()) {
            if (notification.getReadAt() == null) {
                notification.setReadAt(Instant.now());
                notifications.update(notification);
            }
        }
        return HttpResponse.seeOther(URI.create("/notifications"));
    }
}
