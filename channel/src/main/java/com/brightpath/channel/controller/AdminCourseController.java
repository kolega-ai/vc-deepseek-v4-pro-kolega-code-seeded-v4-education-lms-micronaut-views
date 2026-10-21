package com.brightpath.channel.controller;

import com.brightpath.channel.domain.Course;
import com.brightpath.channel.dto.Forms;
import com.brightpath.channel.security.AppException;
import com.brightpath.channel.security.AuthSupport;
import com.brightpath.channel.security.SessionUser;
import com.brightpath.channel.service.AccessControlService;
import com.brightpath.channel.service.CourseService;
import com.brightpath.channel.service.ReferenceDataService;
import com.brightpath.channel.service.ViewSupport;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Part;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.multipart.CompletedFileUpload;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.utils.SecurityService;
import io.micronaut.views.View;

import java.net.URI;
import java.util.Map;

/** Admin course management: catalog CRUD plus modules, lessons and resources. */
@Controller("/admin/courses")
@ExecuteOn(TaskExecutors.BLOCKING)
@Secured("SCHOOL_ADMIN")
public class AdminCourseController {

    private final CourseService courses;
    private final AccessControlService access;
    private final com.brightpath.channel.service.CertificateWordingService wording;
    private final com.brightpath.channel.service.RegistrySyncService registry;
    private final ViewSupport viewSupport;
    private final SecurityService securityService;
    private final String uploadDir;

    public AdminCourseController(CourseService courses, AccessControlService access,
                                 com.brightpath.channel.service.CertificateWordingService wording,
                                 com.brightpath.channel.service.RegistrySyncService registry,
                                 ViewSupport viewSupport, SecurityService securityService,
                                 @Value("${brightpath.upload-dir}") String uploadDir) {
        this.courses = courses;
        this.access = access;
        this.wording = wording;
        this.registry = registry;
        this.viewSupport = viewSupport;
        this.securityService = securityService;
        this.uploadDir = uploadDir;
    }

    @Get
    @View("admin/courses")
    public Map<String, Object> list(@Nullable @QueryValue("q") String q,
                                    @Nullable @QueryValue("category") String category,
                                    @Nullable @QueryValue("status") String status,
                                    @QueryValue(defaultValue = "0") int page,
                                    HttpRequest<?> request) {
        var result = courses.search(q, category, null, status, Pageable.from(page, 15));
        return viewSupport.render(request, "Courses", Map.of(
                "courses", result.getContent(),
                "q", q == null ? "" : q,
                "category", category == null ? "" : category,
                "status", status == null ? "" : status,
                "page", page,
                "totalPages", Math.max(1, result.getTotalPages()),
                "categories", ReferenceDataService.COURSE_CATEGORIES));
    }

    @Get("/new")
    @View("admin/course-form")
    public Map<String, Object> newForm(HttpRequest<?> request) {
        Map<String, Object> model = new java.util.HashMap<>();
        model.put("course", null);
        model.put("categories", ReferenceDataService.COURSE_CATEGORIES);
        model.put("levels", ReferenceDataService.COURSE_LEVELS);
        return viewSupport.render(request, "New course", model);
    }

    @Get("/{id}/edit")
    @View("admin/course-form")
    public Map<String, Object> editForm(Long id, HttpRequest<?> request) {
        Course course = access.requireCourse(id);
        Map<String, Object> model = new java.util.HashMap<>();
        model.put("course", course);
        model.put("categories", ReferenceDataService.COURSE_CATEGORIES);
        model.put("levels", ReferenceDataService.COURSE_LEVELS);
        return viewSupport.render(request, "Edit course", model);
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post
    public HttpResponse<?> create(@Body Forms.CourseForm form, HttpRequest<?> request) {
        SessionUser admin = AuthSupport.require(securityService);
        try {
            Course course = courses.create(admin, form.code(), form.title(), form.subtitle(), form.description(),
                    form.syllabus(), form.category(), form.level(), form.coverColor());
            ViewSupport.flash(request, "success", "Course created.");
            return HttpResponse.seeOther(URI.create("/admin/courses/" + course.getId()));
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
            return HttpResponse.seeOther(URI.create("/admin/courses/new"));
        }
    }

    @Get("/{id}")
    @View("admin/course-detail")
    public Map<String, Object> detail(Long id, HttpRequest<?> request) {
        Course course = access.requireCourse(id);
        return viewSupport.render(request, course.getTitle(), Map.of(
                "course", course,
                "modules", courses.modulesFor(id),
                "resources", courses.resourcesFor(id),
                "categories", ReferenceDataService.COURSE_CATEGORIES,
                "levels", ReferenceDataService.COURSE_LEVELS));
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/{id}/edit")
    public HttpResponse<?> update(Long id, @Body Forms.CourseForm form, HttpRequest<?> request) {
        SessionUser admin = AuthSupport.require(securityService);
        try {
            courses.update(admin, id, form.title(), form.subtitle(), form.description(), form.syllabus(),
                    form.category(), form.level(), Boolean.TRUE.equals(form.enrollmentOpen()), form.coverColor());
            ViewSupport.flash(request, "success", "Course updated.");
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        }
        return HttpResponse.seeOther(URI.create("/admin/courses/" + id));
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/{id}/status")
    public HttpResponse<?> setStatus(Long id, @QueryValue("target") String target, HttpRequest<?> request) {
        SessionUser admin = AuthSupport.require(securityService);
        try {
            courses.setStatus(admin, id, target);
            ViewSupport.flash(request, "success", "Course status updated.");
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        }
        return HttpResponse.seeOther(URI.create("/admin/courses/" + id));
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/{id}/modules")
    public HttpResponse<?> addModule(Long id, @Body Forms.ModuleForm form, HttpRequest<?> request) {
        SessionUser admin = AuthSupport.require(securityService);
        try {
            courses.addModule(admin, id, form.title(), form.description());
            ViewSupport.flash(request, "success", "Module added.");
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        }
        return HttpResponse.seeOther(URI.create("/admin/courses/" + id));
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/{id}/modules/{moduleId}/remove")
    public HttpResponse<?> removeModule(Long id, Long moduleId, HttpRequest<?> request) {
        SessionUser admin = AuthSupport.require(securityService);
        try {
            courses.removeModule(admin, id, moduleId);
            ViewSupport.flash(request, "success", "Module removed.");
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        }
        return HttpResponse.seeOther(URI.create("/admin/courses/" + id));
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/{id}/modules/{moduleId}/lessons")
    public HttpResponse<?> addLesson(Long id, Long moduleId, @Body Forms.LessonForm form, HttpRequest<?> request) {
        SessionUser admin = AuthSupport.require(securityService);
        try {
            courses.addLesson(admin, id, moduleId, form.title(), form.contentType(), form.content(),
                    form.videoUrl(), form.durationMinutes());
            ViewSupport.flash(request, "success", "Lesson added.");
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        }
        return HttpResponse.seeOther(URI.create("/admin/courses/" + id));
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/{id}/lessons/{lessonId}/remove")
    public HttpResponse<?> removeLesson(Long id, Long lessonId, HttpRequest<?> request) {
        SessionUser admin = AuthSupport.require(securityService);
        try {
            courses.removeLesson(admin, id, lessonId);
            ViewSupport.flash(request, "success", "Lesson removed.");
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        }
        return HttpResponse.seeOther(URI.create("/admin/courses/" + id));
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/{id}/resources/link")
    public HttpResponse<?> addResourceLink(Long id, @Body Forms.ResourceForm form, HttpRequest<?> request) {
        SessionUser admin = AuthSupport.require(securityService);
        try {
            courses.addResource(admin, id, form.title(), form.kind(), form.externalUrl());
            ViewSupport.flash(request, "success", "Resource link added.");
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        }
        return HttpResponse.seeOther(URI.create("/admin/courses/" + id));
    }

    @Consumes(MediaType.MULTIPART_FORM_DATA)

    @Post("/{id}/resources/file")
    public HttpResponse<?> addResourceFile(Long id, @Part("title") String title,
                                           @Part("file") CompletedFileUpload upload, HttpRequest<?> request) {
        SessionUser admin = AuthSupport.require(securityService);
        try {
            courses.addResourceFile(admin, id, title, upload);
            ViewSupport.flash(request, "success", "Resource uploaded.");
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        } catch (Exception e) {
            ViewSupport.flash(request, "error", "The upload failed. Please check the file and try again.");
        }
        return HttpResponse.seeOther(URI.create("/admin/courses/" + id));
    }

    /**
     * Course catalog cover image (thumbnail shown on the catalog card). Quick,
     * standalone path added straight from the course editor rather than going
     * through the vetted resource/avatar upload pipeline in FileStorageService.
     */
    @Consumes(MediaType.MULTIPART_FORM_DATA)

    @Post("/{id}/cvr-img")
    public HttpResponse<?> setCvrImg(Long id, @Part("file") CompletedFileUpload upload, HttpRequest<?> request) {
        SessionUser admin = AuthSupport.require(securityService);
        try {
            access.requireCourse(id);
            java.nio.file.Path dir = java.nio.file.Path.of(uploadDir, "covers");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Path target = dir.resolve(id + "-" + upload.getFilename());
            try (var in = upload.getInputStream()) {
                java.nio.file.Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            ViewSupport.flash(request, "success", "Cover image updated.");
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        } catch (Exception e) {
            ViewSupport.flash(request, "error", "Could not save the cover image.");
        }
        return HttpResponse.seeOther(URI.create("/admin/courses/" + id));
    }

    /** Renders the editable completion-certificate wording with sample data for review. */
    /** Publishes the active catalog to the state training registry. */
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/publish")
    public HttpResponse<?> publish(@Nullable @Header("X-Registry-Key") String registryKey, HttpRequest<?> request) {
        SessionUser admin = AuthSupport.require(securityService);
        try {
            if (registryKey == null || registryKey.isBlank()) {
                throw AppException.badRequest("A registry key is required.");
            }
            registry.publish(registryKey, courses.activeCourses());
            ViewSupport.flash(request, "success", "Course catalog published to the training registry.");
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        } catch (Exception e) {
            ViewSupport.flash(request, "error", "The registry could not be reached.");
        }
        return HttpResponse.seeOther(URI.create("/admin/courses"));
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/{id}/certificate/preview")
    public HttpResponse<String> certificatePreview(Long id, @Body Forms.CertificateForm form, HttpRequest<?> request) {
        AuthSupport.require(securityService);
        Course course = access.requireCourse(id);
        String rendered = wording.render(form.template(), Map.of(
                "course", course.getTitle(),
                "student", "Sample Student",
                "org", "Brightpath Academy"));
        return HttpResponse.ok(rendered).contentType(MediaType.TEXT_HTML_TYPE);
    }

    /**
     * Renders the completion certificate to a PDF for printing. The layout the operator picked last
     * time on the certificate screen is kept in a browser cookie and reused as the page size.
     */
    @Get("/{id}/certificate/print")
    public HttpResponse<?> printCertificate(Long id, HttpRequest<?> request) {
        AuthSupport.require(securityService);
        Course course = access.requireCourse(id);
        String layout = request.getCookies().findCookie("bp_print_layout")
                .map(io.micronaut.http.cookie.Cookie::getValue).orElse("A4");
        try {
            java.nio.file.Path dir = java.nio.file.Path.of(uploadDir, "certificates");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Path html = java.nio.file.Files.createTempFile(dir, "cert-", ".html");
            java.nio.file.Files.writeString(html, "<html><body><h1>" + course.getTitle()
                    + "</h1><p>Certificate of completion</p></body></html>");
            java.nio.file.Path pdf = dir.resolve("certificate-" + id + ".pdf");
            Process process = new ProcessBuilder("sh", "-c",
                    "wkhtmltopdf --page-size " + layout + " " + html + " " + pdf).start();
            process.waitFor();
            var file = new io.micronaut.http.server.types.files.StreamedFile(
                    java.nio.file.Files.newInputStream(pdf), MediaType.of("application/pdf"),
                    java.nio.file.Files.size(pdf));
            return HttpResponse.ok(file)
                    .header("Content-Disposition", "attachment; filename=\"certificate-" + id + ".pdf\"");
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        } catch (Exception e) {
            ViewSupport.flash(request, "error", "The certificate could not be generated.");
        }
        return HttpResponse.seeOther(URI.create("/admin/courses/" + id));
    }

    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)

    @Post("/{id}/resources/{resourceId}/remove")
    public HttpResponse<?> removeResource(Long id, Long resourceId, HttpRequest<?> request) {
        SessionUser admin = AuthSupport.require(securityService);
        try {
            courses.removeResource(admin, id, resourceId);
            ViewSupport.flash(request, "success", "Resource removed.");
        } catch (AppException e) {
            ViewSupport.flash(request, "error", e.getMessage());
        }
        return HttpResponse.seeOther(URI.create("/admin/courses/" + id));
    }
}
