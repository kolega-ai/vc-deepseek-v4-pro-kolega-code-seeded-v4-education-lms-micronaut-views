package com.brightpath.channel.controller;

import com.brightpath.channel.dto.Forms;
import com.brightpath.channel.security.AuthSupport;
import com.brightpath.channel.security.SessionUser;
import com.brightpath.channel.service.ExportService;
import com.brightpath.channel.service.ReportCodeService;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.security.utils.SecurityService;

/** Shareable report links: an encrypted code carries the course/cohort of a saved export. */
@Controller("/reports")
@ExecuteOn(TaskExecutors.BLOCKING)
public class ReportLinkController {

    private final ReportCodeService codes;
    private final ExportService exports;
    private final SecurityService securityService;

    public ReportLinkController(ReportCodeService codes, ExportService exports,
                                SecurityService securityService) {
        this.codes = codes;
        this.exports = exports;
        this.securityService = securityService;
    }

    @Get("/link")
    @Secured({"INSTRUCTOR", "TEACHING_ASSISTANT", "SCHOOL_ADMIN"})
    public HttpResponse<String> link(@QueryValue("courseId") Long courseId,
                                     @Nullable @QueryValue("cohortId") Long cohortId) {
        String value = courseId + (cohortId == null ? "" : ":" + cohortId);
        return HttpResponse.ok(codes.seal(value)).contentType(MediaType.TEXT_PLAIN_TYPE);
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/open")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public HttpResponse<String> open(@Body Forms.ReportCodeForm form, HttpRequest<?> request) {
        SessionUser user = AuthSupport.require(securityService);
        try {
            String[] parts = codes.reveal(form.code()).split(":", 2);
            Long courseId = Long.valueOf(parts[0]);
            Long cohortId = parts.length > 1 && !parts[1].isBlank() ? Long.valueOf(parts[1]) : null;
            String csv = exports.gradesCsv(user, courseId, cohortId);
            return HttpResponse.ok(csv).contentType(MediaType.TEXT_PLAIN_TYPE)
                    .header("Content-Disposition", "attachment; filename=\"report.csv\"");
        } catch (javax.crypto.BadPaddingException e) {
            return HttpResponse.status(HttpStatus.BAD_REQUEST).body("report code could not be opened");
        } catch (NumberFormatException e) {
            return HttpResponse.status(HttpStatus.UNPROCESSABLE_ENTITY).body("report code has an unsupported format");
        } catch (java.security.GeneralSecurityException e) {
            return HttpResponse.status(HttpStatus.BAD_GATEWAY).body("report code could not be decoded");
        }
    }
}
