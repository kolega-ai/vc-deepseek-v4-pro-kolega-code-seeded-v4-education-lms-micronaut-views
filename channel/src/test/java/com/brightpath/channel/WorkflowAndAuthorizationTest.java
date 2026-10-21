package com.brightpath.channel;

import com.brightpath.channel.domain.Assignment;
import com.brightpath.channel.domain.Course;
import com.brightpath.channel.domain.Submission;
import com.brightpath.channel.domain.User;
import com.brightpath.channel.repository.AssignmentRepository;
import com.brightpath.channel.repository.CourseRepository;
import com.brightpath.channel.repository.SubmissionRepository;
import com.brightpath.channel.repository.UserRepository;
import com.brightpath.channel.security.AppException;
import com.brightpath.channel.security.DecisionClient;
import com.brightpath.channel.security.Roles;
import com.brightpath.channel.security.SessionUser;
import com.brightpath.channel.service.AccessControlService;
import com.brightpath.channel.service.AssignmentService;
import com.brightpath.channel.service.GradingService;
import com.brightpath.channel.service.SubmissionService;
import com.brightpath.channel.service.UserService;
import com.brightpath.channel.workflow.AssignmentWorkflow;
import com.brightpath.channel.workflow.SubmissionWorkflow;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The most important workflow and authorization rules, exercised against a
 * real Postgres with the real service layer (the decision client is mocked).
 */
@MicronautTest
@io.micronaut.context.annotation.Property(name = "micronaut.server.port", value = "18098")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkflowAndAuthorizationTest extends AbstractDbTest {

    @Inject UserRepository users;
    @Inject CourseRepository courses;
    @Inject AssignmentRepository assignments;
    @Inject SubmissionRepository submissions;
    @Inject AssignmentService assignmentService;
    @Inject SubmissionService submissionService;
    @Inject GradingService gradingService;
    @Inject AccessControlService access;
    @Inject UserService userService;

    @BeforeEach
    void setUp() {
        // fresh data per test: repository-level cleanup (audit log keeps its own integrity)
        submissions.deleteAll();
        assignments.deleteAll();
        courses.deleteAll();
        users.deleteAll();
    }

    @MockBean(DecisionClient.class)
    DecisionClient decisionClient() {
        return Mockito.mock(DecisionClient.class);
    }

    private User makeUser(String username, String role) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@brightpath.example");
        user.setDisplayName(username);
        user.setRole(role);
        user.setEnabled(true);
        user.setPasswordHash(UserService.hash("Password1!a"));
        return users.save(user);
    }

    private SessionUser asStudent(User user, Long... courseIds) {
        return new SessionUser(user.getId(), user.getUsername(), user.getDisplayName(), user.getEmail(),
                Roles.STUDENT, true, Set.of(courseIds), Map.of(), Map.of(), "test", "test-token");
    }

    private SessionUser asStaff(User user, Long... courseIds) {
        Map<Long, String> staffCourses = new java.util.HashMap<>();
        for (Long id : courseIds) {
            staffCourses.put(id, Roles.INSTRUCTOR);
        }
        return new SessionUser(user.getId(), user.getUsername(), user.getDisplayName(), user.getEmail(),
                user.getRole(), true, Set.of(), staffCourses, Map.of(), "test", "test-token");
    }

    private SessionUser asTa(User user, Long... courseIds) {
        Map<Long, String> staffCourses = new java.util.HashMap<>();
        for (Long id : courseIds) {
            staffCourses.put(id, "TA");
        }
        return new SessionUser(user.getId(), user.getUsername(), user.getDisplayName(), user.getEmail(),
                Roles.TEACHING_ASSISTANT, true, Set.of(), staffCourses, Map.of(), "test", "test-token");
    }

    private Course makeCourse(String code) {
        Course course = new Course();
        course.setCode(code);
        course.setTitle(code);
        course.setStatus(Course.STATUS_ACTIVE);
        course.setEnrollmentOpen(true);
        return courses.save(course);
    }

    private Assignment makeAssignment(SessionUser actor, Course course, Instant dueAt) {
        return assignmentService.create(actor, course.getId(), null, "Test assignment " + course.getCode(),
                "summary", "Do the work and submit it.", dueAt, null, true, BigDecimal.valueOf(100),
                "[]", "pdf", 3);
    }

    @Test
    void assignmentTransitionsFollowExplicitEdgeList() {
        User instructor = makeUser("instructor", Roles.INSTRUCTOR);
        Course course = makeCourse("TST-101");
        SessionUser staff = asStaff(instructor, course.getId());
        Assignment assignment = makeAssignment(staff, course, Instant.now().plusSeconds(3600));

        assertTrue(AssignmentWorkflow.canTransition(Assignment.STATUS_DRAFT, Assignment.STATUS_PUBLISHED));
        assertThrows(AppException.class, () -> assignmentService.transition(staff, assignment.getId(), Assignment.STATUS_CLOSED),
                "draft -> closed is not an allowed edge");
        assignmentService.transition(staff, assignment.getId(), Assignment.STATUS_PUBLISHED);
        assertThrows(AppException.class, () -> assignmentService.transition(staff, assignment.getId(), Assignment.STATUS_DRAFT),
                "published -> draft is not an allowed edge");
        assignmentService.transition(staff, assignment.getId(), Assignment.STATUS_CLOSED);
        assignmentService.transition(staff, assignment.getId(), Assignment.STATUS_ARCHIVED);
        assertEquals(Assignment.STATUS_ARCHIVED, assignments.findById(assignment.getId()).orElseThrow().getStatus());
    }

    @Test
    void submissionLifecycleDraftReleaseAndTerminalState() {
        User instructor = makeUser("instructor", Roles.INSTRUCTOR);
        User student = makeUser("student", Roles.STUDENT);
        Course course = makeCourse("TST-102");
        SessionUser staff = asStaff(instructor, course.getId());
        SessionUser pupil = asStudent(student, course.getId());
        Assignment assignment = makeAssignment(staff, course, Instant.now().plusSeconds(7200));
        assignmentService.transition(staff, assignment.getId(), Assignment.STATUS_PUBLISHED);

        Submission submission = submitQuietly(pupil, assignment);
        assertEquals(Submission.STATUS_SUBMITTED, submission.getStatus());

        gradingService.saveDraft(staff, submission.getId(), Map.of(), BigDecimal.valueOf(85), "Nice work", null);
        Submission graded = submissions.findById(submission.getId()).orElseThrow();
        assertEquals(Submission.STATUS_GRADED, graded.getStatus());
        assertEquals(0, graded.getDraftGrade().compareTo(BigDecimal.valueOf(85)));
        assertNull(graded.getReleasedGrade());
        // draft values must never appear on a student-visible copy
        Submission studentCopy = SubmissionService.studentVisibleCopy(graded);
        assertNull(studentCopy.getDraftGrade());
        assertNull(studentCopy.getReleasedGrade());

        gradingService.release(staff, submission.getId());
        Submission released = submissions.findById(submission.getId()).orElseThrow();
        assertEquals(Submission.STATUS_RELEASED, released.getStatus());
        assertEquals(0, released.getReleasedGrade().compareTo(BigDecimal.valueOf(85)));

        assertThrows(AppException.class, () ->
                gradingService.requestResubmission(staff, submission.getId(), "again"),
                "released is terminal");
    }

    @Test
    void studentsCannotReplaceAfterGradingStarts() {
        User instructor = makeUser("instructor", Roles.INSTRUCTOR);
        User student = makeUser("student", Roles.STUDENT);
        Course course = makeCourse("TST-103");
        SessionUser staff = asStaff(instructor, course.getId());
        SessionUser pupil = asStudent(student, course.getId());
        Assignment assignment = makeAssignment(staff, course, Instant.now().plusSeconds(7200));
        assignmentService.transition(staff, assignment.getId(), Assignment.STATUS_PUBLISHED);

        Submission submission = submitQuietly(pupil, assignment);
        gradingService.saveDraft(staff, submission.getId(), Map.of(), BigDecimal.valueOf(70), null, null);
        AppException e = assertThrows(AppException.class, () -> submitQuietly(pupil, assignment));
        assertTrue(e.getMessage().toLowerCase().contains("grading"));
    }

    @Test
    void teachingAssistantsCannotPublishOrRelease() {
        User ta = makeUser("ta", Roles.TEACHING_ASSISTANT);
        Course course = makeCourse("TST-104");
        SessionUser assistant = asTa(ta, course.getId());

        assertThrows(AppException.class, () ->
                makeAssignment(assistant, course, Instant.now().plusSeconds(3600)),
                "TAs cannot create/publish assignments");

        User instructor = makeUser("instructor", Roles.INSTRUCTOR);
        User student = makeUser("student", Roles.STUDENT);
        SessionUser staff = asStaff(instructor, course.getId());
        SessionUser pupil = asStudent(student, course.getId());
        Assignment assignment = makeAssignment(staff, course, Instant.now().plusSeconds(7200));
        assignmentService.transition(staff, assignment.getId(), Assignment.STATUS_PUBLISHED);
        Submission submission = submitQuietly(pupil, assignment);

        gradingService.saveDraft(assistant, submission.getId(), Map.of(), BigDecimal.valueOf(80), "draft feedback", null);
        assertEquals(Submission.STATUS_GRADED, submissions.findById(submission.getId()).orElseThrow().getStatus());

        assertThrows(AppException.class, () -> gradingService.release(assistant, submission.getId()),
                "TAs cannot release grades");
        assertEquals(Submission.STATUS_GRADED, submissions.findById(submission.getId()).orElseThrow().getStatus());
    }

    @Test
    void studentsOnlySeeTheirOwnSubmissionsAndFiles() {
        User instructor = makeUser("instructor", Roles.INSTRUCTOR);
        User studentA = makeUser("studentA", Roles.STUDENT);
        User studentB = makeUser("studentB", Roles.STUDENT);
        Course course = makeCourse("TST-105");
        SessionUser staff = asStaff(instructor, course.getId());
        SessionUser pupilA = asStudent(studentA, course.getId());
        SessionUser pupilB = asStudent(studentB, course.getId());
        Assignment assignment = makeAssignment(staff, course, Instant.now().plusSeconds(7200));
        assignmentService.transition(staff, assignment.getId(), Assignment.STATUS_PUBLISHED);
        Submission submissionA = submitQuietly(pupilA, assignment);

        // B cannot access A's submission; A can access their own.
        assertThrows(AppException.class, () -> access.assertCanAccessSubmission(pupilB, submissionA.getId()));
        assertEquals(submissionA.getId(), access.assertCanAccessSubmission(pupilA, submissionA.getId()).getId());
        // staff scoped to the course can
        assertEquals(submissionA.getId(), access.assertCanAccessSubmission(staff, submissionA.getId()).getId());
        // a student WITHOUT the course scope cannot view the course
        User outsider = makeUser("outsiderStudent", Roles.STUDENT);
        SessionUser outsiderPupil = asStudent(outsider);
        assertThrows(AppException.class, () -> access.assertCanViewCourse(outsiderPupil, course.getId()));
        // staff from another course are rejected
        User other = makeUser("other", Roles.INSTRUCTOR);
        SessionUser otherStaff = asStaff(other, 999L);
        assertThrows(AppException.class, () -> access.assertCanAccessSubmission(otherStaff, submissionA.getId()));
    }

    @Test
    void exportRequiresCourseManagementScope() {
        // covered by the AccessControl checks exercised above; the export service itself
        // refuses non-managers and records a read-audit event with the row count.
        User instructor = makeUser("instructor", Roles.INSTRUCTOR);
        Course course = makeCourse("TST-106");
        SessionUser staff = asStaff(instructor, course.getId());
        SessionUser outsider = asStaff(makeUser("outsider", Roles.INSTRUCTOR), 1234L);
        assertThrows(AppException.class, () ->
                new com.brightpath.channel.service.ExportService(users, courses, null, null,
                        assignments, submissions, null, access, null)
                        .gradesCsv(outsider, course.getId(), null));
    }

    private Submission submitQuietly(SessionUser pupil, Assignment assignment) {
        try {
            return submissionService.submit(pupil, assignment.getId(), "answer text", List.of());
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Submission assertDoesNotThrowOrNull(java.util.function.Supplier<Submission> supplier) {
        return supplier.get();
    }
}
