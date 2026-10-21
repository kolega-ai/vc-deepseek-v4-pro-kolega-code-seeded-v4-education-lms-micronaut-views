package com.brightpath.channel.service;

import com.brightpath.channel.repository.NotificationRepository;
import com.brightpath.channel.security.AuthSupport;
import com.brightpath.channel.security.CsrfFilter;
import com.brightpath.channel.security.SessionUser;
import com.brightpath.channel.security.Sessions;
import io.micronaut.http.HttpRequest;
import io.micronaut.security.utils.SecurityService;
import jakarta.inject.Singleton;

import java.util.LinkedHashMap;
import java.util.Map;

/** Builds the shared Thymeleaf model: principal, CSRF token, flash messages, org settings. */
@Singleton
public class ViewSupport {

    private final SecurityService securityService;
    private final SettingsService settings;
    private final NotificationRepository notifications;
    private final AssetVersionProvider assetVersions;

    public ViewSupport(SecurityService securityService,
                       SettingsService settings,
                       NotificationRepository notifications,
                       AssetVersionProvider assetVersions) {
        this.securityService = securityService;
        this.settings = settings;
        this.notifications = notifications;
        this.assetVersions = assetVersions;
    }

    public Map<String, Object> render(HttpRequest<?> request, String title, Map<String, Object> extra) {
        Map<String, Object> model = new LinkedHashMap<>(extra);
        model.put("title", title);
        model.put("orgName", settings.orgName());
        model.put("supportEmail", settings.supportEmail());
        model.put("assetVersion", assetVersions.version());
        AuthSupport.current(securityService).ifPresent(user -> {
            model.put("principal", user);
            model.put("unreadCount", notifications.countByUserIdAndReadAtIsNull(user.id()));
        });
        Sessions.of(request).ifPresent(session -> {
            model.put("csrf", session.get(CsrfFilter.CSRF_KEY).orElse(""));
            model.put("flash", session.get("flash").orElse(null));
            model.put("flashType", session.get("flashType").orElse(null));
            session.remove("flash");
            session.remove("flashType");
        });
        return model;
    }

    public static void flash(HttpRequest<?> request, String type, String message) {
        Sessions.of(request).ifPresent(session -> {
            session.put("flash", message);
            session.put("flashType", type);
        });
    }

    public static String csrf(HttpRequest<?> request) {
        return Sessions.of(request)
                .map(s -> (String) s.get(CsrfFilter.CSRF_KEY).orElse(""))
                .orElse("");
    }

    public SessionUser principal() {
        return AuthSupport.current(securityService).orElse(null);
    }
}
