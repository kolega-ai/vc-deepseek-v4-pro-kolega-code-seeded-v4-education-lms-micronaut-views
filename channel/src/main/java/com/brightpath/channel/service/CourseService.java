package com.brightpath.channel.service;

import com.brightpath.channel.domain.Course;
import com.brightpath.channel.domain.CourseModule;
import com.brightpath.channel.domain.CourseResource;
import com.brightpath.channel.domain.FileRef;
import com.brightpath.channel.domain.Lesson;
import com.brightpath.channel.repository.CourseModuleRepository;
import com.brightpath.channel.repository.CourseRepository;
import com.brightpath.channel.repository.CourseResourceRepository;
import com.brightpath.channel.repository.LessonRepository;
import com.brightpath.channel.security.AppException;
import com.brightpath.channel.security.SessionUser;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.http.multipart.CompletedFileUpload;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Course catalog, content structure (modules/lessons/resources) and admin lifecycle. */
@Singleton
public class CourseService {

    private final CourseRepository courses;
    private final CourseModuleRepository modules;
    private final LessonRepository lessons;
    private final CourseResourceRepository resources;
    private final FileStorageService fileStorage;
    private final AuditService audit;

    public CourseService(CourseRepository courses, CourseModuleRepository modules,
                         LessonRepository lessons, CourseResourceRepository resources,
                         FileStorageService fileStorage, AuditService audit) {
        this.courses = courses;
        this.modules = modules;
        this.lessons = lessons;
        this.resources = resources;
        this.fileStorage = fileStorage;
        this.audit = audit;
    }

    public Page<Course> search(String q, String category, String level, String status, Pageable pageable) {
        String normalized = q == null || q.isBlank() ? null : q.trim();
        String cat = category == null || category.isBlank() ? null : category;
        String lvl = level == null || level.isBlank() ? null : level;
        String st = status == null || status.isBlank() ? null : status;
        return courses.search(normalized, cat, lvl, st, pageable);
    }

    public List<Course> activeCourses() {
        return courses.findByStatusOrderByTitleAsc(Course.STATUS_ACTIVE);
    }

    public List<CourseModule> modulesFor(Long courseId) {
        return modules.findByCourseIdOrderByPositionAsc(courseId);
    }

    public List<Lesson> lessonsForModule(Long moduleId) {
        return lessons.findByModuleIdOrderByPositionAsc(moduleId);
    }

    public List<CourseResource> resourcesFor(Long courseId) {
        return resources.findByCourseIdOrderByIdAsc(courseId);
    }

    @Transactional
    public Course create(SessionUser actor, String code, String title, String subtitle, String description,
                         String syllabus, String category, String level, String coverColor) {
        if (!actor.isAdmin()) {
            throw AppException.forbidden("Only school administrators can create courses.");
        }
        String normalizedCode = normalizeCode(code);
        if (courses.findByStatusOrderByTitleAsc("active").stream().anyMatch(c -> c.getCode().equalsIgnoreCase(normalizedCode))) {
            throw AppException.conflict("A course with this code already exists.");
        }
        Course course = new Course();
        course.setCode(normalizedCode);
        course.setTitle(title.trim());
        course.setSubtitle(subtitle == null ? null : subtitle.trim());
        course.setDescription(description);
        course.setSyllabus(syllabus);
        course.setCategory(category == null || category.isBlank() ? "General" : category);
        course.setLevel(level == null || level.isBlank() ? "Intermediate" : level);
        course.setCoverColor(coverColor == null || coverColor.isBlank() ? "indigo" : coverColor);
        course.setStatus(Course.STATUS_ACTIVE);
        course.setEnrollmentOpen(true);
        courses.save(course);
        audit.record(actor, "course.created", "course", String.valueOf(course.getId()), null, "code=" + normalizedCode);
        return course;
    }

    @Transactional
    public Course update(SessionUser actor, Long courseId, String title, String subtitle, String description,
                         String syllabus, String category, String level, boolean enrollmentOpen, String coverColor) {
        Course course = courses.findById(courseId).orElseThrow(() -> AppException.notFound("Course not found."));
        if (!actor.isAdmin()) {
            throw AppException.forbidden("Only school administrators can edit courses.");
        }
        course.setTitle(title.trim());
        course.setSubtitle(subtitle == null ? null : subtitle.trim());
        course.setDescription(description);
        course.setSyllabus(syllabus);
        course.setCategory(category == null || category.isBlank() ? "General" : category);
        course.setLevel(level == null || level.isBlank() ? "Intermediate" : level);
        course.setEnrollmentOpen(enrollmentOpen);
        course.setCoverColor(coverColor == null || coverColor.isBlank() ? "indigo" : coverColor);
        courses.update(course);
        audit.record(actor, "course.updated", "course", String.valueOf(courseId), null, null);
        return course;
    }

    @Transactional
    public void setStatus(SessionUser actor, Long courseId, String newStatus) {
        if (!actor.isAdmin()) {
            throw AppException.forbidden("Only school administrators can change course status.");
        }
        Course course = courses.findById(courseId).orElseThrow(() -> AppException.notFound("Course not found."));
        if (!Course.STATUS_ACTIVE.equals(newStatus) && !Course.STATUS_ARCHIVED.equals(newStatus)) {
            throw AppException.badRequest("Unknown course status.");
        }
        String before = course.getStatus();
        course.setStatus(newStatus);
        courses.update(course);
        audit.record(actor, "course.status_changed", "course", String.valueOf(courseId), "status=" + before, "status=" + newStatus);
    }

    // ---- modules & lessons ----------------------------------------------------

    @Transactional
    public CourseModule addModule(SessionUser actor, Long courseId, String title, String description) {
        if (!actor.canManageCourse(courseId)) {
            throw AppException.forbidden("You do not manage this course.");
        }
        CourseModule module = new CourseModule();
        module.setCourseId(courseId);
        module.setTitle(title.trim());
        module.setDescription(description);
        module.setPosition(modules.findByCourseIdOrderByPositionAsc(courseId).size() + 1);
        modules.save(module);
        audit.record(actor, "course.module_added", "course_module", String.valueOf(module.getId()), null, "courseId=" + courseId);
        return module;
    }

    @Transactional
    public void removeModule(SessionUser actor, Long courseId, Long moduleId) {
        if (!actor.canManageCourse(courseId)) {
            throw AppException.forbidden("You do not manage this course.");
        }
        CourseModule module = modules.findById(moduleId)
                .orElseThrow(() -> AppException.notFound("Module not found."));
        if (!module.getCourseId().equals(courseId)) {
            throw AppException.notFound("Module not found.");
        }
        lessons.deleteByModuleId(moduleId);
        modules.deleteById(moduleId);
        audit.record(actor, "course.module_removed", "course_module", String.valueOf(moduleId), null, "courseId=" + courseId);
    }

    @Transactional
    public Lesson addLesson(SessionUser actor, Long courseId, Long moduleId, String title,
                            String contentType, String content, String videoUrl, Integer durationMinutes) {
        if (!actor.canManageCourse(courseId)) {
            throw AppException.forbidden("You do not manage this course.");
        }
        CourseModule module = modules.findById(moduleId)
                .orElseThrow(() -> AppException.notFound("Module not found."));
        if (!module.getCourseId().equals(courseId)) {
            throw AppException.notFound("Module not found.");
        }
        if (!Set.of(Lesson.TYPE_TEXT, Lesson.TYPE_MARKDOWN, Lesson.TYPE_VIDEO).contains(contentType)) {
            throw AppException.badRequest("Unknown lesson content type.");
        }
        Lesson lesson = new Lesson();
        lesson.setModuleId(moduleId);
        lesson.setCourseId(courseId);
        lesson.setTitle(title.trim());
        lesson.setContentType(contentType);
        lesson.setContent(content);
        lesson.setVideoUrl(videoUrl);
        lesson.setDurationMinutes(durationMinutes);
        lesson.setPosition(lessons.findByModuleIdOrderByPositionAsc(moduleId).size() + 1);
        lessons.save(lesson);
        audit.record(actor, "course.lesson_added", "lesson", String.valueOf(lesson.getId()), null, "courseId=" + courseId);
        return lesson;
    }

    @Transactional
    public void removeLesson(SessionUser actor, Long courseId, Long lessonId) {
        if (!actor.canManageCourse(courseId)) {
            throw AppException.forbidden("You do not manage this course.");
        }
        Lesson lesson = lessons.findById(lessonId).orElseThrow(() -> AppException.notFound("Lesson not found."));
        if (!lesson.getCourseId().equals(courseId)) {
            throw AppException.notFound("Lesson not found.");
        }
        lessons.deleteById(lessonId);
        audit.record(actor, "course.lesson_removed", "lesson", String.valueOf(lessonId), null, "courseId=" + courseId);
    }

    // ---- resources -------------------------------------------------------------

    @Transactional
    public CourseResource addResource(SessionUser actor, Long courseId, String title, String kind, String externalUrl) {
        if (!actor.canManageCourse(courseId)) {
            throw AppException.forbidden("You do not manage this course.");
        }
        CourseResource resource = new CourseResource();
        resource.setCourseId(courseId);
        resource.setTitle(title.trim());
        resource.setKind(kind);
        resource.setExternalUrl(externalUrl);
        resources.save(resource);
        audit.record(actor, "course.resource_added", "course_resource", String.valueOf(resource.getId()), null, "courseId=" + courseId);
        return resource;
    }

    @Transactional
    public CourseResource addResourceFile(SessionUser actor, Long courseId, String title, CompletedFileUpload upload) throws IOException {
        if (!actor.canManageCourse(courseId)) {
            throw AppException.forbidden("You do not manage this course.");
        }
        FileRef file = fileStorage.store(upload, actor, FileRef.CATEGORY_COURSE_RESOURCE, courseId, null, null, Set.of());
        CourseResource resource = new CourseResource();
        resource.setCourseId(courseId);
        resource.setTitle(title.trim());
        resource.setKind(CourseResource.KIND_READING);
        resource.setFileId(file.getId());
        resources.save(resource);
        audit.record(actor, "course.resource_uploaded", "course_resource", String.valueOf(resource.getId()),
                null, "fileId=" + file.getId() + ",courseId=" + courseId);
        return resource;
    }

    @Transactional
    public void removeResource(SessionUser actor, Long courseId, Long resourceId) {
        if (!actor.canManageCourse(courseId)) {
            throw AppException.forbidden("You do not manage this course.");
        }
        CourseResource resource = resources.findById(resourceId)
                .orElseThrow(() -> AppException.notFound("Resource not found."));
        if (!resource.getCourseId().equals(courseId)) {
            throw AppException.notFound("Resource not found.");
        }
        resources.deleteById(resourceId);
        audit.record(actor, "course.resource_removed", "course_resource", String.valueOf(resourceId), null, "courseId=" + courseId);
    }

    private static String normalizeCode(String code) {
        if (code == null || code.isBlank() || code.length() > 24 || !code.matches("[A-Za-z0-9-]+")) {
            throw AppException.badRequest("Course codes must be up to 24 letters, numbers or dashes.");
        }
        return code.trim().toUpperCase();
    }
}
