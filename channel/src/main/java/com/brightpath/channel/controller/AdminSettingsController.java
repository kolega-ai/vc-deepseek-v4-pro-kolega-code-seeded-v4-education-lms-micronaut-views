package com.brightpath.channel.controller;

import com.brightpath.channel.dto.Forms;
import com.brightpath.channel.security.AuthSupport;
import com.brightpath.channel.security.SessionUser;
import com.brightpath.channel.service.AuditService;
import com.brightpath.channel.service.SettingsService;
import com.brightpath.channel.service.ViewSupport;
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
import java.util.LinkedHashMap;
import java.util.Map;

/** Admin organization settings. */
@Controller("/admin/settings")
@ExecuteOn(TaskExecutors.BLOCKING)
@Secured("SCHOOL_ADMIN")
public class AdminSettingsController {

    public static final java.util.List<String> EDITABLE_KEYS = java.util.List.of(
            "org_name", "support_email", "allow_self_registration",
            "upload_max_mb", "student_max_files_per_submission",
            "assignment_auto_close_grace_hours", "due_reminder_window_hours",
            "email_notifications_enabled");

    private final SettingsService settings;
    private final com.brightpath.channel.service.NoticeWordingService wording;
    private final AuditService audit;
    private final ViewSupport viewSupport;
    private final SecurityService securityService;

    public AdminSettingsController(SettingsService settings,
                                   com.brightpath.channel.service.NoticeWordingService wording,
                                   AuditService audit,
                                   ViewSupport viewSupport, SecurityService securityService) {
        this.settings = settings;
        this.wording = wording;
        this.audit = audit;
        this.viewSupport = viewSupport;
        this.securityService = securityService;
    }

    @Get
    @View("admin/settings")
    public Map<String, Object> settings(HttpRequest<?> request) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : EDITABLE_KEYS) {
            values.put(key, settings.get(key, ""));
        }
        return viewSupport.render(request, "Settings", Map.of("values", values));
    }

    /** Renders the editable notice wording with sample values so an admin can review it. */
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/notices/preview")
    public HttpResponse<String> previewNotice(@Body Forms.NoticeForm form, HttpRequest<?> request) {
        AuthSupport.require(securityService);
        String rendered = wording.render(form.wording());
        return HttpResponse.ok(rendered).contentType(MediaType.TEXT_HTML_TYPE);
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post
    public HttpResponse<?> save(@Body Forms.SettingsForm form, HttpRequest<?> request) {
        SessionUser admin = AuthSupport.require(securityService);
        for (String key : EDITABLE_KEYS) {
            String value = form.values() == null ? null : form.values().get(key);
            if (value != null && !value.isBlank()) {
                String before = settings.get(key, "");
                settings.set(key, value.trim());
                audit.record(admin, "settings.updated", "setting", key, "value=" + before, "value=" + value.trim());
            }
        }
        ViewSupport.flash(request, "success", "Settings saved.");
        return HttpResponse.seeOther(URI.create("/admin/settings"));
    }
}
