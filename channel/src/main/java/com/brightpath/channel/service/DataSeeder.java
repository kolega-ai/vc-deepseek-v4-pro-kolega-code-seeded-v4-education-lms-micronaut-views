package com.brightpath.channel.service;

import com.brightpath.channel.domain.Assignment;
import com.brightpath.channel.domain.Cohort;
import com.brightpath.channel.domain.CohortStaff;
import com.brightpath.channel.domain.Course;
import com.brightpath.channel.domain.Enrollment;
import com.brightpath.channel.domain.User;
import com.brightpath.channel.repository.CohortRepository;
import com.brightpath.channel.repository.CohortStaffRepository;
import com.brightpath.channel.repository.CourseRepository;
import com.brightpath.channel.repository.EnrollmentRepository;
import com.brightpath.channel.repository.UserRepository;
import com.brightpath.channel.repository.WebhookEndpointRepository;
import com.brightpath.channel.security.DecisionClient;
import com.brightpath.channel.security.Roles;
import com.brightpath.channel.security.SessionUser;
import io.micronaut.context.annotation.Value;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.StartupEvent;
import io.micronaut.runtime.server.event.ServerStartupEvent;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Seed data. Runs once when the database is empty. Every record that lives on
 * a state machine is produced by calling the real workflow actions (create ->
 * publish -> close -> archive; enroll -> approve; submit -> grade -> release),
 * so every seeded status is reachable through the explicit edge lists.
 */
@Singleton
public class DataSeeder implements ApplicationEventListener<StartupEvent> {

    private static final Logger LOG = LoggerFactory.getLogger(DataSeeder.class);

    private static final String STUDENT_PASSWORD = "StudentDemo1!";
    private static final String INSTRUCTOR_PASSWORD = "InstructorDemo1!";
    private static final String ASSISTANT_PASSWORD = "AssistantDemo1!";
    private static final String ADMIN_PASSWORD = "AdminDemo123!";

    private final boolean enabled;
    private final UserRepository users;
    private final CourseRepository courses;
    private final CohortRepository cohorts;
    private final CohortStaffRepository cohortStaff;
    private final EnrollmentRepository enrollments;
    private final DecisionClient decisionClient;
    private final AssignmentService assignments;
    private final SubmissionService submissions;
    private final GradingService grading;
    private final CourseService courseService;
    private final EnrollmentService enrollmentService;
    private final CohortService cohortService;
    private final DiscussionService discussions;
    private final WebhookEndpointRepository webhooks;
    private final ApiKeyService apiKeys;
    private final String coreMockHookUrl;
    private final String webhookSecret;

    private final Map<Long, Set<Long>> studentCourses = new HashMap<>();
    private final Map<Long, Map<Long, String>> staffCourses = new HashMap<>();

    public DataSeeder(@Value("${brightpath.seed.enabled:true}") boolean enabled,
                      UserRepository users, CourseRepository courses, CohortRepository cohorts,
                      CohortStaffRepository cohortStaff, EnrollmentRepository enrollments,
                      DecisionClient decisionClient, AssignmentService assignments,
                      SubmissionService submissions, GradingService grading, CourseService courseService,
                      EnrollmentService enrollmentService, CohortService cohortService,
                      DiscussionService discussions, WebhookEndpointRepository webhooks,
                      ApiKeyService apiKeys,
                      @Value("${brightpath.coremock.webhook-url:http://localhost:8082/v1/hooks}") String coreMockHookUrl,
                      @Value("${brightpath.coremock.webhook-secret:brightpath_webhook_dev_secret}") String webhookSecret) {
        this.enabled = enabled;
        this.users = users;
        this.courses = courses;
        this.cohorts = cohorts;
        this.cohortStaff = cohortStaff;
        this.enrollments = enrollments;
        this.decisionClient = decisionClient;
        this.assignments = assignments;
        this.submissions = submissions;
        this.grading = grading;
        this.courseService = courseService;
        this.enrollmentService = enrollmentService;
        this.cohortService = cohortService;
        this.discussions = discussions;
        this.webhooks = webhooks;
        this.apiKeys = apiKeys;
        this.coreMockHookUrl = coreMockHookUrl;
        this.webhookSecret = webhookSecret;
    }

    @Override
    public void onApplicationEvent(StartupEvent event) {
        if (!enabled) {
            LOG.info("seeding disabled (brightpath.seed.enabled=false)");
            return;
        }
        try {
            waitForDecision();
            run();
        } catch (Exception e) {
            LOG.error("seeding failed: {}", e.getMessage(), e);
        }
    }

    /** The decision tier must be reachable before user/scope sync starts (compose starts it first). */
    private void waitForDecision() {
        for (int attempt = 1; attempt <= 60; attempt++) {
            if (decisionClient.ping()) {
                return;
            }
            try {
                Thread.sleep(2000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (attempt % 10 == 0) {
                LOG.warn("waiting for the decision tier (attempt {})…", attempt);
            }
        }
        LOG.warn("decision tier did not answer within 120s; seeding will continue but scope syncs may fail");
    }

    // Not transactional: every seeded action commits independently so one bad row never rolls back the whole seed.
    public void run() {
        if (users.count() > 0) {
            LOG.info("seed skipped: database already contains users");
            return;
        }
        LOG.info("seeding Brightpath demo data…");

        User admin = createUser("admin", "admin@brightpath.example", "Dana Whitfield", Roles.SCHOOL_ADMIN, ADMIN_PASSWORD);
        User instructor1 = createUser("instructor.anna", "anna.kovacs@brightpath.example", "Anna Kovacs", Roles.INSTRUCTOR, INSTRUCTOR_PASSWORD);
        User instructor2 = createUser("instructor.marcus", "marcus.okafor@brightpath.example", "Marcus Okafor", Roles.INSTRUCTOR, INSTRUCTOR_PASSWORD);
        User instructor3 = createUser("instructor.priya", "priya.nair@brightpath.example", "Priya Nair", Roles.INSTRUCTOR, INSTRUCTOR_PASSWORD);
        User ta1 = createUser("ta.lucas", "lucas.ferreira@brightpath.example", "Lucas Ferreira", Roles.TEACHING_ASSISTANT, ASSISTANT_PASSWORD);
        User ta2 = createUser("ta.mei", "mei.chen@brightpath.example", "Mei Chen", Roles.TEACHING_ASSISTANT, ASSISTANT_PASSWORD);
        User ta3 = createUser("ta.samir", "samir.haddad@brightpath.example", "Samir Haddad", Roles.TEACHING_ASSISTANT, ASSISTANT_PASSWORD);

        String[][] studentNames = {
                {"alex.rivera", "Alex Rivera"}, {"jordan.lee", "Jordan Lee"}, {"sam.taylor", "Sam Taylor"},
                {"maya.patel", "Maya Patel"}, {"noah.kim", "Noah Kim"}, {"emma.wilson", "Emma Wilson"},
                {"liam.garcia", "Liam Garcia"}, {"olivia.brown", "Olivia Brown"}, {"ethan.nguyen", "Ethan Nguyen"},
                {"ava.martinez", "Ava Martinez"}, {"sofia.rossi", "Sofia Rossi"}, {"lucas.silva", "Lucas Silva"},
                {"isabella.costa", "Isabella Costa"}, {"mateo.alvarez", "Mateo Alvarez"}, {"charlotte.dubois", "Charlotte Dubois"},
                {"amir.hassan", "Amir Hassan"}, {"yuki.tanaka", "Yuki Tanaka"}, {"grace.obrien", "Grace O'Brien"},
                {"diego.ramirez", "Diego Ramirez"}, {"hannah.schmidt", "Hannah Schmidt"}, {"omar.farouk", "Omar Farouk"},
                {"zoe.andersen", "Zoe Andersen"}, {"felix.wagner", "Felix Wagner"}, {"nora.johansson", "Nora Johansson"},
                {"kiran.desai", "Kiran Desai"}, {"elena.petrova", "Elena Petrova"}, {"marco.bianchi", "Marco Bianchi"},
                {"aisha.bello", "Aisha Bello"}, {"jonas.weber", "Jonas Weber"}, {"mia.nakamura", "Mia Nakamura"},
                {"tomas.novak", "Tomas Novak"}, {"freya.larsen", "Freya Larsen"}};
        List<User> students = new ArrayList<>();
        for (int i = 0; i < studentNames.length; i++) {
            students.add(createUser(studentNames[i][0], studentNames[i][0] + "@brightpath.example",
                    studentNames[i][1], Roles.STUDENT, STUDENT_PASSWORD));
        }

        // ---- courses ----
        Course cs101 = createCourse("CS-101", "Introduction to Programming", "Python from first principles",
                "Computer Science", "Beginner", "indigo",
                "A hands-on introduction to programming with Python. Variables, control flow, functions, data structures and a final project.",
                "Weeks 1-2: syntax and variables\nWeeks 3-4: control flow\nWeeks 5-6: functions and modules\nWeeks 7-8: final project",
                "M1: Getting started", "M2: Control flow", "M3: Functions and files", "M4: Final project");
        Course cs201 = createCourse("CS-201", "Data Structures & Algorithms", "Lists, trees, graphs and complexity",
                "Computer Science", "Intermediate", "teal",
                "Classic data structures and the algorithms that operate on them, with complexity analysis.",
                "Stacks, queues, linked lists, trees, hash maps, graphs, and Big-O analysis.", null, null, null, null);
        Course web301 = createCourse("CS-301", "Web Development Fundamentals", "Build full web applications",
                "Computer Science", "Intermediate", "ocean",
                "HTML, CSS, JavaScript and server-side fundamentals culminating in a portfolio project.",
                "Frontend foundations, browser APIs, server rendering and deployment basics.", null, null, null, null);
        Course ds101 = createCourse("DS-101", "Data Analysis with Python", "From spreadsheets to insight",
                "Data Science", "Beginner", "amber",
                "Practical data analysis with pandas, visualisation and reporting.",
                "Data wrangling, aggregation, visualisation and a capstone analysis.", null, null, null, null);
        Course ds201 = createCourse("DS-201", "Machine Learning Foundations", "Models, evaluation, ethics",
                "Data Science", "Advanced", "plum",
                "Supervised and unsupervised learning foundations with scikit-learn.",
                "Regression, classification, clustering, evaluation and model ethics.", null, null, null, null);
        Course bus101 = createCourse("BUS-101", "Business Communication", "Clear writing for the workplace",
                "Business", "Beginner", "slate",
                "Emails, memos, reports and presentations that get results.",
                "Audience analysis, structure, tone and professional editing.", null, null, null, null);
        Course des101 = createCourse("DES-101", "UI Design Fundamentals", "Interfaces people enjoy",
                "Design", "Beginner", "rose",
                "Layout, type, colour and interaction patterns for digital products.",
                "Design principles, systems and critique.", null, null, null, null);
        Course mth101 = createCourse("MTH-101", "Statistics for Everyone", "Data, chance and decisions",
                "Mathematics", "Intermediate", "olive",
                "Descriptive statistics, probability and hypothesis testing for non-specialists.",
                "Distributions, sampling, confidence intervals and regression intuition.", null, null, null, null);
        createCourse("ENG-101", "Technical Writing", "Documentation engineers read",
                "Communications", "Intermediate", "slate",
                "Specifications, guides and API docs for technical audiences.",
                "Structure, precision, review cycles.", null, null, null, null);

        // modules/lessons for CS-101 (done in createCourse); add a couple for other courses
        addModule(cs201, "Algorithms & complexity", List.of(
                new String[]{"Big-O notation", "text", "Complexity classes and why they matter."},
                new String[]{"Sorting algorithms", "text", "Insertion, merge and quick sort, with trade-offs."},
                new String[]{"Searching", "text", "Linear and binary search."}));
        addModule(cs201, "Data structures", List.of(
                new String[]{"Stacks & queues", "text", "LIFO/FIFO with real examples."},
                new String[]{"Trees", "text", "Binary trees and traversal."}));
        addModule(ds101, "Pandas basics", List.of(
                new String[]{"DataFrames", "text", "Loading, selecting, filtering."},
                new String[]{"Grouping & aggregation", "text", "groupby, pivot tables."}));
        addModule(web301, "Frontend foundations", List.of(
                new String[]{"HTML & semantics", "text", "Document structure and accessibility."},
                new String[]{"CSS layouts", "text", "Flexbox, grid, responsive design."},
                new String[]{"JavaScript essentials", "text", "DOM, events, fetch."}));
        addModule(des101, "Design principles", List.of(
                new String[]{"Visual hierarchy", "text", "Guiding attention with size, space and contrast."},
                new String[]{"Colour systems", "text", "Tokens, palettes and accessibility."}));

        // ---- cohorts ----
        Cohort cs101a = createCohort(cs101, "Fall 2026 — Morning group", "Mon & Wed, 09:00–11:00",
                LocalDate.now().minusDays(20), LocalDate.now().plusDays(70), 20, "active");
        Cohort cs101b = createCohort(cs101, "Fall 2026 — Evening group", "Tue & Thu, 18:00–20:00",
                LocalDate.now().plusDays(14), LocalDate.now().plusDays(104), 20, "upcoming");
        Cohort cs201f = createCohort(cs201, "Fall 2026 — Advanced track", "Tue & Thu, 10:00–12:00",
                LocalDate.now().minusDays(40), LocalDate.now().plusDays(50), 18, "active");
        Cohort ds101s = createCohort(ds101, "Summer 2026 — Data cohort", "Mon–Fri intensive",
                LocalDate.now().minusDays(120), LocalDate.now().minusDays(20), 20, "completed");
        Cohort web301f = createCohort(web301, "Spring 2027 — Web track", "Sat, 10:00–14:00",
                LocalDate.now().plusDays(45), LocalDate.now().plusDays(135), 24, "upcoming");

        // staff assignment (instructor/TA scopes)
        assignStaff(cs101a, instructor1, CohortStaff.ROLE_INSTRUCTOR, admin);
        assignStaff(cs101a, ta1, CohortStaff.ROLE_ASSISTANT, admin);
        assignStaff(cs101b, instructor1, CohortStaff.ROLE_INSTRUCTOR, admin);
        assignStaff(cs101b, ta2, CohortStaff.ROLE_ASSISTANT, admin);
        assignStaff(cs201f, instructor2, CohortStaff.ROLE_INSTRUCTOR, admin);
        assignStaff(cs201f, ta1, CohortStaff.ROLE_ASSISTANT, admin);
        assignStaff(ds101s, instructor3, CohortStaff.ROLE_INSTRUCTOR, admin);
        assignStaff(ds101s, ta3, CohortStaff.ROLE_ASSISTANT, admin);
        assignStaff(web301f, instructor2, CohortStaff.ROLE_INSTRUCTOR, admin);
        assignStaff(web301f, ta2, CohortStaff.ROLE_ASSISTANT, admin);

        // ---- enrollments ----
        int[] cs101aCount = {0};
        enroll(students, 0, 13, cs101a, "active", cs101aCount, admin);
        enroll(students, 14, 21, cs101b, "active", new int[]{0}, admin);
        enroll(students, 22, 24, cs101b, "pending", new int[]{0}, admin);
        enroll(students, 0, 10, cs201f, "active", new int[]{0}, admin);
        enroll(students, 11, 12, cs201f, "pending", new int[]{0}, admin);
        enroll(students, 10, 21, ds101s, "completed", new int[]{0}, admin);
        enroll(students, 20, 25, web301f, "active", new int[]{0}, admin);
        // one dropped enrollment for demo filters
        Enrollment dropped = enrollments.findByCohortIdAndStudentId(cs101a.getId(), students.get(13).getId())
                .orElseGet(() -> {
                    Enrollment e = new Enrollment();
                    e.setCohortId(cs101a.getId());
                    e.setStudentId(students.get(13).getId());
                    e.setStatus(Enrollment.STATUS_PENDING);
                    return enrollments.save(e);
                });
        enrollmentService.approve(user(admin), dropped.getId());
        enrollmentService.drop(user(admin), dropped.getId());

        // ---- assignments ----
        SessionUser anna = staffUser(instructor1);
        SessionUser marcus = staffUser(instructor2);
        SessionUser priya = staffUser(instructor3);

        Assignment cs101Quiz = createAssignment(anna, cs101, cs101a, "Quiz 1: Python basics",
                "Multiple topics from modules 1-2", "Answer the quiz questions in the assignment text. Show your working for full marks.",
                Instant.now().plus(java.time.Duration.ofDays(4)), 20, "[\n  {\"key\":\"c1\",\"name\":\"Correctness\",\"points\":12,\"description\":\"Answers are correct\"},\n  {\"key\":\"c2\",\"name\":\"Explanation\",\"points\":8,\"description\":\"Reasoning is clear\"}\n]");
        publish(anna, cs101Quiz);

        Assignment cs101Project = createAssignment(anna, cs101, cs101a, "Final project: weather CLI",
                "Build a small command-line app", "Build a CLI tool that fetches and displays weather data using a public API. Include error handling and tests.",
                Instant.now().plus(java.time.Duration.ofDays(21)), 100, "[\n  {\"key\":\"c1\",\"name\":\"Functionality\",\"points\":50,\"description\":\"Works as specified\"},\n  {\"key\":\"c2\",\"name\":\"Code quality\",\"points\":25,\"description\":\"Readable, tested\"},\n  {\"key\":\"c3\",\"name\":\"Report\",\"points\":25,\"description\":\"Clear documentation\"}\n]");
        publish(anna, cs101Project);

        Assignment cs101Late = createAssignment(anna, cs101, cs101a, "Practice set 2: loops & functions",
                "Exercises from module 3", "Complete exercises 1-10 from module 3. Submit your solutions file.",
                Instant.now().minus(java.time.Duration.ofDays(6)), 30, "[]");
        publish(anna, cs101Late);

        Assignment cs101Draft = createAssignment(anna, cs101, cs101a, "Midterm review (draft)",
                "Not yet published", "Review questions for the midterm.", Instant.now().plus(java.time.Duration.ofDays(10)), 40, "[]");

        Assignment cs101Archived = createAssignment(anna, cs101, cs101a, "Week 1 check-in",
                "Intro survey", "Tell us about yourself and your goals.", Instant.now().minus(java.time.Duration.ofDays(30)), 5, "[]");
        publish(anna, cs101Archived);
        transition(anna, cs101Archived, Assignment.STATUS_CLOSED);
        transition(anna, cs101Archived, Assignment.STATUS_ARCHIVED);

        Assignment cs201Trees = createAssignment(marcus, cs201, cs201f, "Tree traversal lab",
                "Binary tree traversals", "Implement in-order, pre-order and post-order traversal and compare their outputs.",
                Instant.now().plus(java.time.Duration.ofDays(7)), 50, "[\n  {\"key\":\"c1\",\"name\":\"Implementation\",\"points\":30,\"description\":\"All three traversals correct\"},\n  {\"key\":\"c2\",\"name\":\"Analysis\",\"points\":20,\"description\":\"Complexity analysis correct\"}\n]");
        publish(marcus, cs201Trees);

        Assignment cs201Sort = createAssignment(marcus, cs201, cs201f, "Sorting comparison report",
                "Benchmark three sorts", "Benchmark insertion, merge and quick sort on random and sorted inputs; report your findings.",
                Instant.now().minus(java.time.Duration.ofDays(3)), 60, "[]");
        publish(marcus, cs201Sort);

        Assignment cs201Draft = createAssignment(marcus, cs201, cs201f, "Graph algorithms (draft)",
                "Dijkstra and friends", "Implement Dijkstra's algorithm and discuss applications.",
                Instant.now().plus(java.time.Duration.ofDays(18)), 70, "[]");

        Assignment ds101Report = createAssignment(priya, ds101, ds101s, "Capstone analysis report",
                "Final data analysis", "Choose a public dataset, clean it, produce three visualisations and a findings report.",
                Instant.now().minus(java.time.Duration.ofDays(45)), 80, "[\n  {\"key\":\"c1\",\"name\":\"Analysis\",\"points\":40,\"description\":\"Correct, insightful\"},\n  {\"key\":\"c2\",\"name\":\"Visualisations\",\"points\":25,\"description\":\"Clear, labelled\"},\n  {\"key\":\"c3\",\"name\":\"Report\",\"points\":15,\"description\":\"Well written\"}\n]");
        publish(priya, ds101Report);

        Assignment web301Site = createAssignment(marcus, web301, web301f, "Portfolio site",
                "Personal portfolio", "Build a responsive portfolio site with semantic HTML and CSS.",
                Instant.now().plus(java.time.Duration.ofDays(30)), 100, "[\n  {\"key\":\"c1\",\"name\":\"Structure\",\"points\":30,\"description\":\"Semantic, accessible\"},\n  {\"key\":\"c2\",\"name\":\"Design\",\"points\":35,\"description\":\"Coherent visual system\"},\n  {\"key\":\"c3\",\"name\":\"Responsiveness\",\"points\":35,\"description\":\"Works on mobile and desktop\"}\n]");
        publish(marcus, web301Site);

        Assignment bus101Memo = createAssignment(adminUser(), bus101, null, "Persuasive memo",
                "Write a decision memo", "Write a one-page memo recommending a decision with evidence.",
                Instant.now().plus(java.time.Duration.ofDays(9)), 25, "[]");
        publish(adminUser(), bus101Memo);

        Assignment des101Critique = createAssignment(adminUser(), des101, null, "Interface critique",
                "Evaluate a real product", "Choose a mobile app and critique its visual hierarchy, type and colour in 600 words.",
                Instant.now().plus(java.time.Duration.ofDays(5)), 30, "[]");
        publish(adminUser(), des101Critique);

        Assignment mth101Hw = createAssignment(adminUser(), mth101, null, "Probability problem set",
                "Distributions practice", "Complete the probability problem set from chapter 4.",
                Instant.now().minus(java.time.Duration.ofDays(2)), 45, "[]");
        publish(adminUser(), mth101Hw);

        // ---- submissions (produced through the submit -> grade -> release actions) ----
        // Only assignments whose students are actually enrolled get submissions.
        List<Assignment> submittedAssignments = List.of(cs101Quiz, cs101Project, cs101Late,
                cs201Trees, cs201Sort, ds101Report, web301Site);

        int submissionCounter = 0;
        Map<Long, List<User>> cohortStudents = new HashMap<>();
        cohortStudents.put(cs101a.getId(), students.subList(0, 13));
        cohortStudents.put(cs101b.getId(), students.subList(14, 25));
        cohortStudents.put(cs201f.getId(), students.subList(0, 13));
        cohortStudents.put(ds101s.getId(), students.subList(10, 22));
        cohortStudents.put(web301f.getId(), students.subList(20, 26));

        Map<Long, Integer> targets = Map.of(
                cs101Quiz.getId(), 13, cs101Project.getId(), 12, cs101Late.getId(), 12,
                cs201Trees.getId(), 10, cs201Sort.getId(), 9, ds101Report.getId(), 10,
                web301Site.getId(), 5);

        for (Assignment assignment : submittedAssignments) {
            List<User> pool = cohortStudents.getOrDefault(assignment.getCohortId(), List.of());
            SessionUser grader = assignment.getCourseId().equals(cs101.getId()) ? anna
                    : assignment.getCourseId().equals(cs201.getId()) || assignment.getCourseId().equals(web301.getId())
                    ? marcus : priya;
            int target = Math.min(targets.getOrDefault(assignment.getId(), 6), pool.size());
            for (int i = 0; i < target; i++) {
                User student = pool.get(i);
                SessionUser studentActor = studentUser(student);
                try {
                    var submission = submissions.submit(studentActor, assignment.getId(),
                            sampleText(assignment, student), List.of());
                    submissionCounter++;
                    if (submissionCounter % 4 == 0) {
                        continue; // leave some freshly submitted/late, awaiting grading
                    }
                    BigDecimal max = assignment.getMaxPoints();
                    BigDecimal grade = max.multiply(BigDecimal.valueOf(0.62 + ((i * 7) % 36) / 100.0))
                            .setScale(1, java.math.RoundingMode.HALF_UP).min(max);
                    if (assignment.getRubric() == null || "[]".equals(assignment.getRubric())) {
                        grading.saveDraft(grader, submission.getId(), Map.of(), grade,
                                "Good progress. See the rubric breakdown for details.", null);
                    } else {
                        Map<String, BigDecimal> scores = new HashMap<>();
                        for (var criterion : RubricSupport.parse(assignment.getRubric())) {
                            scores.put(criterion.key(), criterion.points()
                                    .multiply(BigDecimal.valueOf(0.6 + ((i * 5) % 35) / 100.0))
                                    .setScale(1, java.math.RoundingMode.HALF_UP).min(criterion.points()));
                        }
                        grading.saveDraft(grader, submission.getId(), scores, null,
                                "Solid work overall — check the notes in each criterion.", "Keep an eye on deadlines.");
                    }
                    if (i % 4 == 0) {
                        grading.release(grader, submission.getId());
                    }
                    if (i % 9 == 5) {
                        grading.requestResubmission(grader, submission.getId(),
                                "Please expand the analysis section and resubmit.");
                    }
                } catch (Exception e) {
                    LOG.warn("seed submission failed for assignment {} student {}: {}",
                            assignment.getId(), student.getUsername(), e.getMessage());
                }
            }
        }
        // Close the two assignments whose deadlines are long past (submissions happened above).
        transition(marcus, cs201Sort, Assignment.STATUS_CLOSED);
        transition(priya, ds101Report, Assignment.STATUS_CLOSED);
        LOG.info("seed: {} submissions created", submissionCounter);

        // ---- discussions ----
        seedDiscussions(cs101, cs101a, instructor1, students.subList(0, 14), anna, 6);
        seedDiscussions(cs201, cs201f, instructor2, students.subList(0, 13), marcus, 5);
        seedDiscussions(ds101, ds101s, instructor3, students.subList(10, 22), priya, 4);
        seedDiscussions(web301, web301f, instructor2, students.subList(20, 26), marcus, 3);
        seedDiscussions(des101, null, instructor1, List.of(), adminUser(), 2);

        // ---- webhook endpoint + demo api key ----
        if (webhooks.findAll().isEmpty()) {
            var endpoint = new com.brightpath.channel.domain.WebhookEndpoint();
            endpoint.setName("Vendor SIS (mock)");
            endpoint.setUrl(coreMockHookUrl);
            endpoint.setSecret(webhookSecret);
            endpoint.setEventTypes("grade.released,enrollment.activated,enrollment.dropped,assignment.published");
            endpoint.setEnabled(true);
            endpoint.setCreatedBy(admin.getId());
            webhooks.save(endpoint);
            LOG.info("seed: webhook endpoint -> {}", coreMockHookUrl);
        }
        if (apiKeys.list().isEmpty()) {
            String key = apiKeys.create(user(admin), "Registrar demo key",
                    List.of(ApiKeyService.SCOPE_READ_COURSES, ApiKeyService.SCOPE_READ_GRADES));
            LOG.info("seed: demo API key (shown once): {}", key);
        }

        LOG.info("seed complete: {} users, {} courses, {} cohorts, {} enrollments, {} threads",
                users.count(), courses.count(), cohorts.count(), enrollments.count(), 20);
    }

    private static String SubmissionStatusLate() {
        return "late";
    }

    // ---------- helpers ----------

    private void seedDiscussions(Course course, Cohort cohort, User instructor, List<User> pool, SessionUser staffActor, int count) {
        String[][] seeds = {
                {"Welcome! Introduce yourself", "Hi everyone! Post a short introduction: your background and what you hope to get out of this course."},
                {"Stuck on an exercise? Ask here", "If you are stuck on any exercise, describe what you tried and where it broke — we will help out."},
                {"Study group this week", "Anyone interested in a study group before the next assignment is due?"},
                {"Clarification on the rubric", "How is 'explanation' weighted versus 'correctness' in the rubric?"},
                {"Career advice thread", "What kind of roles do people usually go for after this course?"},
                {"Resources & links", "Share useful resources, articles and tutorials here."}};
        int made = 0;
        int seedIndex = 0;
        while (made < count) {
            String[] seed = seeds[seedIndex % seeds.length];
            seedIndex++;
            var thread = discussions.createThread(pool.isEmpty() ? staffActor : studentUser(pool.get(made % pool.size())),
                    course.getId(), seed[0] + (seedIndex > seeds.length ? " (part " + (seedIndex / seeds.length + 1) + ")" : ""), seed[1], false);
            if (made % 3 == 0 && pool.size() > 1) {
                discussions.addComment(studentUser(pool.get((made + 1) % pool.size())), thread.getId(),
                        "Great question — I was wondering the same thing. " + (made % 2 == 0 ? "Following!" : "Thanks for posting."));
            }
            if (made % 4 == 2) {
                discussions.addComment(staffActor, thread.getId(), "Good discussion. Remember the assignment deadline on the course page.");
                if (made % 8 == 2) {
                    discussions.setPinned(staffActor, thread.getId(), true);
                }
            }
            made++;
        }
    }

    private String sampleText(Assignment assignment, User student) {
        if (assignment.getTitle().startsWith("Quiz")) {
            return "1) b\n2) a\n3) c\n4) b\n5) a\n\nShort answers:\n6) A loop executes a block repeatedly while its condition is true.\n"
                    + "7) Lists are mutable, tuples are not.\n8) `def f(x): return x * 2`";
        }
        if (assignment.getTitle().toLowerCase().contains("report") || assignment.getTitle().toLowerCase().contains("critique")) {
            return "My analysis is attached below.\n\n1. Overview of the dataset and cleaning steps.\n2. Key findings with three visualisations.\n"
                    + "3. Limitations and next steps.\n\nThank you for the feedback.";
        }
        if (assignment.getTitle().toLowerCase().contains("memo")) {
            return "MEMORANDUM\nTo: Leadership team\nFrom: " + student.getDisplayName() + "\n\nRecommendation: adopt the hybrid scheduling policy.\n"
                    + "Supporting evidence: staff survey (n=42) shows 78% preference; pilot cost analysis is neutral.\n"
                    + "Risks: coordination overhead, mitigated by a shared calendar policy.";
        }
        return "My solution for \"" + assignment.getTitle() + "\".\n\nApproach:\n- Read the requirements and broke them into steps.\n"
                + "- Implemented each step with tests where applicable.\n- Verified the output against the examples.\n\n"
                + "See the attached notes for the full walk-through.";
    }

    private User createUser(String username, String email, String displayName, String role, String password) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setDisplayName(displayName);
        user.setRole(role);
        user.setEnabled(true);
        user.setPasswordHash(UserService.hash(password));
        user.setTimezone("UTC");
        users.save(user);
        decisionClient.syncUser(username, user.getId(), email, displayName, role, true);
        decisionClient.syncLimit(username, "upload_mb", switch (role) {
            case Roles.STUDENT -> "10";
            case Roles.INSTRUCTOR, Roles.TEACHING_ASSISTANT -> "50";
            default -> "25";
        });
        if (Roles.STUDENT.equals(role)) {
            decisionClient.syncLimit(username, "max_submission_files", "3");
        }
        return user;
    }

    private void syncLimitQuiet(String username, String key, String value) {
        try {
            decisionClient.syncLimit(username, key, value);
        } catch (Exception e) {
            LOG.warn("limit sync failed for {}: {}", username, e.getMessage());
        }
    }

    private Course createCourse(String code, String title, String subtitle, String category, String level,
                                String color, String description, String syllabus,
                                String m1, String m2, String m3, String m4) {
        Course course = new Course();
        course.setCode(code);
        course.setTitle(title);
        course.setSubtitle(subtitle);
        course.setCategory(category);
        course.setLevel(level);
        course.setCoverColor(color);
        course.setDescription(description);
        course.setSyllabus(syllabus);
        course.setStatus(Course.STATUS_ACTIVE);
        course.setEnrollmentOpen(true);
        courses.save(course);
        if (m1 != null) {
            addModule(course, m1, List.of(
                    new String[]{"Lesson 1 — " + m1, "text", "Welcome to " + title + ". This lesson introduces the module's goals and the key vocabulary you will use throughout."},
                    new String[]{"Lesson 2 — Core concepts", "text", "The core concepts of this module, explained step by step with short examples."},
                    new String[]{"Lesson 3 — Practice", "text", "Worked examples and practice exercises. Try them before moving to the next module."}));
        }
        if (m2 != null) {
            addModule(course, m2, List.of(
                    new String[]{"Lesson 1 — Foundations", "text", "The building blocks you need for this module."},
                    new String[]{"Lesson 2 — Applied examples", "text", "Applying the concepts to realistic problems."}));
        }
        if (m3 != null) {
            addModule(course, m3, List.<String[]>of(
                    new String[]{"Lesson 1 — " + m3, "text", "Introduction to " + m3.toLowerCase() + ", with a worked example."}));
        }
        if (m4 != null) {
            addModule(course, m4, List.<String[]>of(
                    new String[]{"Project brief", "text", "Everything you need to know to start the final project."}));
        }
        return course;
    }

    private void addModule(Course course, String title, List<String[]> lessons) {
        var module = courseService.addModule(adminUser(), course.getId(), title, null);
        for (String[] lesson : lessons) {
            courseService.addLesson(adminUser(), course.getId(), module.getId(), lesson[0], lesson[1], lesson[2], null, 45);
        }
    }

    private Cohort createCohort(Course course, String name, String schedule, LocalDate start, LocalDate end,
                                int max, String status) {
        Cohort cohort = new Cohort();
        cohort.setCourseId(course.getId());
        cohort.setName(name);
        cohort.setScheduleSummary(schedule);
        cohort.setStartDate(start);
        cohort.setEndDate(end);
        cohort.setMaxStudents(max);
        cohort.setStatus(status);
        return cohorts.save(cohort);
    }

    private void assignStaff(Cohort cohort, User staff, String role, User admin) {
        cohortService.assignStaff(user(admin), cohort.getId(), staff.getId(), role);
        staffCourses.computeIfAbsent(staff.getId(), k -> new HashMap<>())
                .put(cohort.getCourseId(), CohortStaff.ROLE_ASSISTANT.equals(role) ? "TA" : Roles.INSTRUCTOR);
    }

    private void enroll(List<User> students, int from, int toExclusive, Cohort cohort, String status, int[] counter, User admin) {
        for (int i = from; i < Math.min(toExclusive, students.size()); i++) {
            User student = students.get(i);
            Enrollment enrollment = new Enrollment();
            enrollment.setCohortId(cohort.getId());
            enrollment.setStudentId(student.getId());
            enrollment.setStatus(Enrollment.STATUS_PENDING);
            enrollments.save(enrollment);
            if (Enrollment.STATUS_ACTIVE.equals(status) || Enrollment.STATUS_COMPLETED.equals(status)) {
                enrollmentService.approve(user(admin), enrollment.getId());
                if (Enrollment.STATUS_COMPLETED.equals(status)) {
                    enrollmentService.complete(user(admin), enrollment.getId());
                }
            }
            studentCourses.computeIfAbsent(student.getId(), k -> new HashSet<>()).add(cohort.getCourseId());
        }
    }

    private Assignment createAssignment(SessionUser actor, Course course, Cohort cohort, String title, String summary,
                                        String instructions, Instant dueAt, int maxPoints, String rubric) {
        try {
            return assignments.create(actor, course.getId(), cohort == null ? null : cohort.getId(), title, summary,
                    instructions, dueAt, null, true, BigDecimal.valueOf(maxPoints), rubric, "pdf,docx,zip", 3);
        } catch (Exception e) {
            LOG.warn("assignment creation failed {}: {}", title, e.getMessage());
            throw e;
        }
    }

    private void publish(SessionUser actor, Assignment assignment) {
        transitions(actor, assignment, Assignment.STATUS_PUBLISHED);
    }

    private void transition(SessionUser actor, Assignment assignment, String target) {
        transitions(actor, assignment, target);
    }

    private void transitions(SessionUser actor, Assignment assignment, String target) {
        if (!assignment.getStatus().equals(target)) {
            assignments.transition(actor, assignment.getId(), target);
        }
    }

    /** Seeder-side SessionUser factory: scopes come from the maps the seeder itself maintains (mirroring decision). */
    private SessionUser user(User user) {
        return new SessionUser(user.getId(), user.getUsername(), user.getDisplayName(), user.getEmail(), user.getRole(),
                true, studentCourses.getOrDefault(user.getId(), Set.of()),
                staffCourses.getOrDefault(user.getId(), Map.of()), Map.of(), "seed", null);
    }

    private SessionUser studentUser(User student) {
        return user(student);
    }

    private SessionUser staffUser(User staff) {
        return user(staff);
    }

    private SessionUser adminUser() {
        return users.findByUsername("admin").map(this::user).orElseThrow();
    }
}
