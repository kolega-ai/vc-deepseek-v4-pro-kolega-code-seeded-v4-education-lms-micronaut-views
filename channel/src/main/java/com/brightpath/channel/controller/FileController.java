package com.brightpath.channel.controller;

import com.brightpath.channel.domain.FileRef;
import com.brightpath.channel.security.AuthSupport;
import com.brightpath.channel.security.SessionUser;
import com.brightpath.channel.service.AccessControlService;
import com.brightpath.channel.service.AuditService;
import com.brightpath.channel.service.FileStorageService;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.server.types.files.StreamedFile;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.security.utils.SecurityService;

import java.nio.file.Path;

/**
 * Authenticated file downloads. Authorization happens in
 * AccessControlService (shared service, one call frame above this
 * controller); the path itself is resolved through FileStorageService's
 * canonicalised base directory before anything touches the filesystem.
 */
@Controller("/files")
@ExecuteOn(TaskExecutors.BLOCKING)
public class FileController {

    private final AccessControlService access;
    private final FileStorageService storage;
    private final AuditService audit;
    private final SecurityService securityService;

    public FileController(AccessControlService access, FileStorageService storage,
                          AuditService audit, SecurityService securityService) {
        this.access = access;
        this.storage = storage;
        this.audit = audit;
        this.securityService = securityService;
    }

    @Get("/{id}")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public HttpResponse<?> download(Long id, @Nullable @QueryValue("embed") String embed, HttpRequest<?> request) throws java.io.IOException {
        SessionUser user = AuthSupport.require(securityService);
        FileRef ref = access.assertCanDownloadFile(user, id);
        Path path = storage.resolveStoredPath(ref.getStoredName());
        MediaType mediaType = MediaType.of(ref.getMimeType() == null ? "application/octet-stream" : ref.getMimeType());
        audit.record(user, "file.download", "file", String.valueOf(ref.getId()),
                null, "category=" + ref.getCategory() + ",bytes=" + ref.getSizeBytes());
        if (embed != null) {
            // Inline preview mode (the "Preview" link next to a course resource): served
            // without Content-Disposition: attachment and with framing protection relaxed
            // so the resource can render inside the course page's own preview panel.
            StreamedFile inlineFile = new StreamedFile(java.nio.file.Files.newInputStream(path), mediaType, ref.getSizeBytes());
            return HttpResponse.ok(inlineFile).header("X-Frame-Options", "ALLOWALL");
        }
        StreamedFile file = new StreamedFile(java.nio.file.Files.newInputStream(path), mediaType, ref.getSizeBytes())
                .attach(ref.getOriginalName());
        return HttpResponse.ok(file);
    }
}
