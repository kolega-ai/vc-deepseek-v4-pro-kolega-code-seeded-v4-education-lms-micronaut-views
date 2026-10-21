package com.brightpath.channel.controller;

import com.brightpath.channel.security.AppException;
import com.brightpath.channel.service.ViewSupport;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Error;
import io.micronaut.http.annotation.Get;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.authentication.AuthenticationException;
import io.micronaut.security.authentication.AuthorizationException;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.views.ModelAndView;
import io.micronaut.views.thymeleaf.ThymeleafViewsRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.StringWriter;
import java.net.URI;
import java.util.Map;

/** Error pages and the global error handler. Messages are generic unless the caller caused them. */
@Controller
@ExecuteOn(TaskExecutors.BLOCKING)
public class ErrorController {

    private static final Logger LOG = LoggerFactory.getLogger(ErrorController.class);

    private static final String FALLBACK_ERROR_HTML = "<!DOCTYPE html><html lang=\"en\"><head>"
            + "<meta charset=\"utf-8\"><title>Error</title></head><body>"
            + "<h1>Something went wrong</h1><p>Please try again or contact support.</p></body></html>";

    private final ViewSupport viewSupport;
    private final ThymeleafViewsRenderer<Object> viewsRenderer;
    private final io.micronaut.security.utils.SecurityService securityService;

    public ErrorController(ViewSupport viewSupport, ThymeleafViewsRenderer<Object> viewsRenderer,
                           io.micronaut.security.utils.SecurityService securityService) {
        this.viewSupport = viewSupport;
        this.viewsRenderer = viewsRenderer;
        this.securityService = securityService;
    }

    @Get("/403")
    @Secured(SecurityRule.IS_ANONYMOUS)
    public ModelAndView<Map<String, Object>> forbiddenPage(HttpRequest<?> request) {
        return new ModelAndView<>("error/403", viewSupport.render(request, "Access denied", Map.of()));
    }

    @Get("/404")
    @Secured(SecurityRule.IS_ANONYMOUS)
    public ModelAndView<Map<String, Object>> notFoundPage(HttpRequest<?> request) {
        return new ModelAndView<>("error/404", viewSupport.render(request, "Page not found", Map.of()));
    }

    @Get("/500")
    @Secured(SecurityRule.IS_ANONYMOUS)
    public ModelAndView<Map<String, Object>> serverErrorPage(HttpRequest<?> request) {
        return new ModelAndView<>("error/500", viewSupport.render(request, "Something went wrong", Map.of()));
    }

    @Error(global = true)
    public HttpResponse<String> handleError(HttpRequest<?> request, Throwable exception) {
        LOG.error("handleError {} {} -> {}: {}", request.getMethod(), request.getPath(),
                exception.getClass().getName(), exception.getMessage());
        HttpStatus status;
        String message;
        boolean userMessage = false;
        if (exception instanceof AppException appException) {
            status = appException.getStatus();
            message = appException.getMessage();
            userMessage = true;
        } else if (exception instanceof AuthorizationException) {
            // The security filter reports anonymous visitors as authorization
            // failures; send them to the login page like any browser flow.
            if (securityService.getAuthentication().isEmpty() && !request.getPath().startsWith("/api/")) {
                return HttpResponse.seeOther(URI.create("/login"));
            }
            status = HttpStatus.FORBIDDEN;
            message = null;
        } else if (exception instanceof AuthenticationException) {
            // Browser flow: send anonymous visitors to the login page.
            // API clients keep a plain 401 so scripts can act on it.
            if (!request.getPath().startsWith("/api/")) {
                return HttpResponse.seeOther(URI.create("/login"));
            }
            status = HttpStatus.UNAUTHORIZED;
            message = null;
        } else if (exception instanceof HttpStatusException statusException) {
            status = statusException.getStatus();
            message = null;
        } else {
            LOG.error("Unhandled error for {} {}", request.getMethod(), request.getPath(), exception);
            status = HttpStatus.INTERNAL_SERVER_ERROR;
            message = null;
        }
        String view = switch (status.getCode()) {
            case 404 -> "error/404";
            case 403 -> "error/403";
            case 400, 401, 409, 422 -> "error/400";
            default -> "error/500";
        };
        Map<String, Object> model = viewSupport.render(request, "Error", Map.of(
                "errorStatus", status.getCode(),
                "errorMessage", userMessage ? message : "Something went wrong. Please try again or contact support.",
                "showMessage", userMessage));
        // Render eagerly so the response carries an explicit status and
        // content type and cannot be mis-encoded (e.g. JSON) for HTML clients.
        String html;
        try {
            StringWriter writer = new StringWriter();
            viewsRenderer.render(view, model, request).writeTo(writer);
            html = writer.toString();
        } catch (Exception renderError) {
            LOG.error("Error page render failed for {}", view, renderError);
            html = FALLBACK_ERROR_HTML;
        }
        return HttpResponse.status(status).contentType(MediaType.TEXT_HTML).body(html);
    }
}
