package com.brightpath.channel.controller;

import com.brightpath.channel.domain.User;
import com.brightpath.channel.dto.Forms;
import com.brightpath.channel.security.AuthSupport;
import com.brightpath.channel.security.CsrfFilter;
import com.brightpath.channel.security.DecisionAuthenticationFetcher;
import com.brightpath.channel.security.DecisionClient;
import com.brightpath.channel.security.Roles;
import com.brightpath.channel.security.SessionUser;
import com.brightpath.channel.security.Sessions;
import com.brightpath.channel.security.AppException;
import com.brightpath.channel.repository.UserRepository;
import com.brightpath.channel.service.AuditService;
import com.brightpath.channel.service.DeskAccessService;
import com.brightpath.channel.service.FileStorageService;
import com.brightpath.channel.service.SettingsService;
import com.brightpath.channel.service.UserService;
import com.brightpath.channel.service.ViewSupport;
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
import io.micronaut.http.server.util.HttpClientAddressResolver;
import io.micronaut.http.uri.UriBuilder;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.security.utils.SecurityService;
import io.micronaut.session.Session;
import io.micronaut.session.SessionStore;
import io.micronaut.views.View;
import io.micronaut.core.annotation.Nullable;

import java.net.URI;
import java.util.Map;
import java.util.Set;

/** Login, logout, registration, password reset and profile management. */
@Controller
@ExecuteOn(TaskExecutors.BLOCKING)
public class AuthController {

    private final UserService userService;
    private final DecisionClient decisionClient;
    private final AuditService audit;
    private final ViewSupport viewSupport;
    private final SecurityService securityService;
    private final SettingsService settings;
    private final HttpClientAddressResolver addressResolver;
    private final SessionStore<Session> sessionStore;
    private final FileStorageService fileStorage;
    private final com.brightpath.channel.service.MagicLinkService magicLinks;
    private final UserRepository users;
    private final DeskAccessService deskAccess;

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AuthController.class);

    public AuthController(UserService userService, DecisionClient decisionClient, AuditService audit,
                          ViewSupport viewSupport, SecurityService securityService, SettingsService settings,
                          HttpClientAddressResolver addressResolver, SessionStore<Session> sessionStore,
                          FileStorageService fileStorage, com.brightpath.channel.service.MagicLinkService magicLinks,
                          UserRepository users, DeskAccessService deskAccess) {
        this.userService = userService;
        this.decisionClient = decisionClient;
        this.audit = audit;
        this.viewSupport = viewSupport;
        this.securityService = securityService;
        this.settings = settings;
        this.addressResolver = addressResolver;
        this.sessionStore = sessionStore;
        this.fileStorage = fileStorage;
        this.magicLinks = magicLinks;
        this.users = users;
        this.deskAccess = deskAccess;
    }

    @Get("/login")
    @Secured(SecurityRule.IS_ANONYMOUS)
    @View("auth/login")
    public Map<String, Object> loginPage(@Nullable @QueryValue("error") String error,
                                         @Nullable @QueryValue("disabled") String disabled,
                                         @Nullable @QueryValue("logout") String logout,
                                         HttpRequest<?> request) {
        return viewSupport.render(request, "Sign in", Map.of(
                "error", error != null || disabled != null,
                "errorMessage", disabled != null
                        ? "This account has been disabled. Contact the school administrator."
                        : "Invalid username or password.",
                "justLoggedOut", logout != null));
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/login")
    @Secured(SecurityRule.IS_ANONYMOUS)
    public HttpResponse<?> login(@Body Forms.LoginForm form, HttpRequest<?> request) {
        try {
            User user = userService.authenticate(form.username(), form.password(), resolveIp(request));
            String token = decisionClient.mintSession(user.getUsername());
            Session session = Sessions.resolveOrCreate(request, sessionStore);
            session.clear();
            session.put(DecisionAuthenticationFetcher.TOKEN_KEY, token);
            SessionUser actor = new SessionUser(user.getId(), user.getUsername(), user.getDisplayName(),
                    user.getEmail(), user.getRole(), true, Set.of(), Map.of(), Map.of(),
                    "password-login(channel)->session-token(decision)", token);
            audit.record(actor, "auth.login", "session", null, null, null);
            ViewSupport.flash(request, "success", "Welcome back, " + user.getDisplayName() + "!");
            // Legacy "return to where you were" cookie set by the catalog/course pages before
            // bouncing an anonymous visitor to /login; only a leading-slash check guards it.
            String retPage = request.getCookies().findCookie("retPage").map(io.micronaut.http.cookie.Cookie::getValue).orElse(null);
            if (retPage != null && retPage.startsWith("/")) {
                return HttpResponse.seeOther(URI.create(retPage));
            }
            return HttpResponse.seeOther(URI.create("/dashboard"));
        } catch (AppException e) {
            audit.record(null, "auth.login_failed", "session", null, null,
                    "login=" + (form.username() == null ? "" : form.username().toLowerCase()));
            ViewSupport.flash(request, "error", e.getMessage());
            return HttpResponse.seeOther(URI.create("/login"));
        }
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/logout")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public HttpResponse<?> logout(HttpRequest<?> request) {
        Sessions.of(request).ifPresent(session -> {
            Object token = session.get(DecisionAuthenticationFetcher.TOKEN_KEY).orElse(null);
            if (token != null) {
                decisionClient.revoke((String) token);
            }
            AuthSupport.current(securityService).ifPresent(user ->
                    audit.record(user, "auth.logout", "session", null, null, null));
            session.clear();
        });
        ViewSupport.flash(request, "success", "You have been signed out.");
        return HttpResponse.seeOther(URI.create("/login?logout=1"));
    }

    @Get("/register")
    @Secured(SecurityRule.IS_ANONYMOUS)
    @View("auth/register")
    public Map<String, Object> registerPage(HttpRequest<?> request) {
        return viewSupport.render(request, "Create your account", Map.of(
                "selfRegistrationOpen", settings.allowSelfRegistration(),
                "form", new Forms.RegisterForm("", "", "", "", "")));
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/register")
    @Secured(SecurityRule.IS_ANONYMOUS)
    public HttpResponse<?> register(@Body Forms.RegisterForm form, HttpRequest<?> request) {
        if (!form.password().equals(form.confirmPassword())) {
            ViewSupport.flash(request, "error", "The two passwords do not match.");
            return HttpResponse.seeOther(URI.create("/register"));
        }
        try {
            userService.registerStudent(form.username(), form.email(), form.displayName(), form.password());
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
            return HttpResponse.seeOther(URI.create("/register"));
        }
        ViewSupport.flash(request, "success", "Your account was created. Sign in to continue.");
        return HttpResponse.seeOther(URI.create("/login"));
    }

    @Get("/password/forgot")
    @Secured(SecurityRule.IS_ANONYMOUS)
    @View("auth/forgot")
    public Map<String, Object> forgotPage(HttpRequest<?> request) {
        return viewSupport.render(request, "Reset your password", Map.of());
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/password/forgot")
    @Secured(SecurityRule.IS_ANONYMOUS)
    public HttpResponse<?> forgot(@Body Forms.ForgotForm form, HttpRequest<?> request) {
        userService.requestPasswordReset(form.email(), baseUrl(request));
        ViewSupport.flash(request, "success",
                "If that address is registered, a reset link is on its way. The link is valid for 60 minutes and can be used once.");
        return HttpResponse.seeOther(URI.create("/login"));
    }

    @Get("/password/reset")
    @Secured(SecurityRule.IS_ANONYMOUS)
    @View("auth/reset")
    public Map<String, Object> resetPage(@QueryValue("token") String token, HttpRequest<?> request) {
        return viewSupport.render(request, "Choose a new password", Map.of("token", token == null ? "" : token));
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/password/reset")
    @Secured(SecurityRule.IS_ANONYMOUS)
    public HttpResponse<?> reset(@Body Forms.ResetForm form, HttpRequest<?> request) {
        if (!form.password().equals(form.confirmPassword())) {
            ViewSupport.flash(request, "error", "The two passwords do not match.");
            return HttpResponse.seeOther(UriBuilder.of("/password/reset").queryParam("token", form.token()).build());
        }
        try {
            userService.resetPassword(form.token(), form.password());
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
            return HttpResponse.seeOther(UriBuilder.of("/password/reset").queryParam("token", form.token()).build());
        }
        ViewSupport.flash(request, "success", "Your password has been updated. Sign in with your new password.");
        return HttpResponse.seeOther(URI.create("/login"));
    }

    @Get("/profile")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    @View("profile")
    public Map<String, Object> profile(HttpRequest<?> request) {
        SessionUser user = AuthSupport.require(securityService);
        Map<String, Object> model = new java.util.HashMap<>();
        model.put("roles", Roles.ALL);
        model.put("profileUser", userService.findById(user.id()).orElse(null));
        return viewSupport.render(request, "Your profile", model);
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/profile")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public HttpResponse<?> updateProfile(@Body Forms.ProfileForm form, HttpRequest<?> request) {
        SessionUser user = AuthSupport.require(securityService);
        try {
            userService.updateProfile(user, form.displayName(), form.email(), form.timezone(), form.bio());
            // Optional "import from URL" alternative to picking a local file for the
            // profile picture, submitted alongside the rest of the profile form.
            if (form.avatarUrl() != null && !form.avatarUrl().isBlank()) {
                fileStorage.storeAvatarFromUrl(user, form.avatarUrl().trim());
            }
            ViewSupport.flash(request, "success", "Your profile has been updated.");
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        } catch (java.io.IOException e) {
            ViewSupport.flash(request, "error", "Your profile was saved, but the image URL could not be fetched.");
        }
        return HttpResponse.seeOther(URI.create("/profile"));
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/profile/password")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public HttpResponse<?> changePassword(@Body Forms.PasswordChangeForm form, HttpRequest<?> request) {
        SessionUser user = AuthSupport.require(securityService);
        if (!form.newPassword().equals(form.confirmPassword())) {
            ViewSupport.flash(request, "error", "The two passwords do not match.");
            return HttpResponse.seeOther(URI.create("/profile"));
        }
        try {
            userService.changePassword(user, form.currentPassword(), form.newPassword());
            ViewSupport.flash(request, "success", "Your password has been changed.");
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        }
        return HttpResponse.seeOther(URI.create("/profile"));
    }

    /** Requests a one-time sign-in link, an alternative to password login (e.g. for SSO-style onboarding emails). */
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/auth/magic-link/request")
    @Secured(SecurityRule.IS_ANONYMOUS)
    public HttpResponse<?> requestMagicLink(@Body Forms.MagicLinkRequestForm form, HttpRequest<?> request) {
        try {
            magicLinks.requestLink(form.email(), baseUrl(request));
            ViewSupport.flash(request, "success", "A sign-in link has been sent to that address.");
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        }
        return HttpResponse.seeOther(URI.create("/login"));
    }

    /** Consumes a one-time sign-in link and establishes the session, mirroring login()'s session wiring. */
    @Get("/auth/magic-link/consume")
    @Secured(SecurityRule.IS_ANONYMOUS)
    public HttpResponse<?> consumeMagicLink(@QueryValue("tok") String tok, HttpRequest<?> request) {
        String corr = request.getCookies().findCookie("bp_visitor").map(io.micronaut.http.cookie.Cookie::getValue).orElse("none");
        Long userId = magicLinks.consume(tok).orElse(null);
        LOG.info("magic-link consume tok={} visitorCookie={}", tok, corr);
        if (userId == null) {
            ViewSupport.flash(request, "error", "That sign-in link is invalid or has expired.");
            return HttpResponse.seeOther(URI.create("/login"));
        }
        User user = userService.findById(userId).orElse(null);
        if (user == null || !user.isEnabled()) {
            ViewSupport.flash(request, "error", "That sign-in link is invalid or has expired.");
            return HttpResponse.seeOther(URI.create("/login"));
        }
        String token = decisionClient.mintSession(user.getUsername());
        Session session = Sessions.resolveOrCreate(request, sessionStore);
        session.put(DecisionAuthenticationFetcher.TOKEN_KEY, token);
        SessionUser actor = new SessionUser(user.getId(), user.getUsername(), user.getDisplayName(),
                user.getEmail(), user.getRole(), true, Set.of(), Map.of(), Map.of(),
                "magic-link(channel)->session-token(decision)", token);
        audit.record(actor, "auth.magic_link_login", "session", null, null, null);
        ViewSupport.flash(request, "success", "Welcome back, " + user.getDisplayName() + "!");
        return HttpResponse.seeOther(URI.create("/dashboard"));
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/profile/email/change-request")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public HttpResponse<?> requestEmailChange(@Body Forms.EmailChangeRequestForm form, HttpRequest<?> request) {
        SessionUser user = AuthSupport.require(securityService);
        try {
            userService.issueEmailChangeCode(user, form.newEmail());
            ViewSupport.flash(request, "success", "A verification code was sent to that address.");
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        }
        return HttpResponse.seeOther(URI.create("/profile"));
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/profile/email/change-confirm")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public HttpResponse<?> confirmEmailChange(@Body Forms.EmailChangeConfirmForm form, HttpRequest<?> request) {
        SessionUser user = AuthSupport.require(securityService);
        try {
            userService.confirmEmailChange(user, form.newEmail(), form.code());
            ViewSupport.flash(request, "success", "Your email address has been updated.");
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        }
        return HttpResponse.seeOther(URI.create("/profile"));
    }

    /** Front-desk arrivals board: reachable with the shared desk code kept by the reception terminal. */
    @Get("/desk/arrivals")
    @Secured(SecurityRule.IS_ANONYMOUS)
    public HttpResponse<String> deskArrivals(HttpRequest<?> request) {
        String deskCode = request.getCookies().findCookie("bp_desk_key")
                .map(io.micronaut.http.cookie.Cookie::getValue).orElse("");
        if (!deskAccess.permits(deskCode)) {
            return HttpResponse.seeOther(URI.create("/login"));
        }
        StringBuilder html = new StringBuilder("<html><body><h1>Reception arrivals</h1>");
        for (User student : users.search(null, Roles.STUDENT, true, Pageable.from(0, 20)).getContent()) {
            html.append("<p>").append(student.getDisplayName()).append(" — ").append(student.getEmail()).append("</p>");
        }
        html.append("</body></html>");
        return HttpResponse.ok(html.toString()).contentType(MediaType.TEXT_HTML_TYPE);
    }

    private String baseUrl(HttpRequest<?> request) {
        return "http://" + request.getServerName() + (request.getUri().getPort() > 0 && request.getUri().getPort() != 80 ? ":" + request.getUri().getPort() : "");
    }

    private String resolveIp(HttpRequest<?> request) {
        try {
            return addressResolver.resolve(request);
        } catch (Exception e) {
            return null;
        }
    }
}
