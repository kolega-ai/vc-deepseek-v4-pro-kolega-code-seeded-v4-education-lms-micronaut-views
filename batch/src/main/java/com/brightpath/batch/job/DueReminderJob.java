package com.brightpath.batch.job;

import com.brightpath.batch.domain.Assignment;
import com.brightpath.batch.domain.EmailOutbox;
import com.brightpath.batch.domain.Enrollment;
import com.brightpath.batch.domain.Notification;
import com.brightpath.batch.domain.User;
import com.brightpath.batch.repository.AssignmentRepository;
import com.brightpath.batch.repository.CohortRepository;
import com.brightpath.batch.repository.EmailOutboxRepository;
import com.brightpath.batch.repository.EnrollmentRepository;
import com.brightpath.batch.repository.NotificationRepository;
import com.brightpath.batch.repository.SettingRepository;
import com.brightpath.batch.repository.SubmissionRepository;
import com.brightpath.batch.repository.UserRepository;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * Scheduled reminder workflow: for every published assignment due within the
 * configured window, active students without a submission get an in-app
 * notification and an email queued to the local outbox (no external mail).
 */
@Singleton
public class DueReminderJob {

    private static final Logger LOG = LoggerFactory.getLogger(DueReminderJob.class);

    private final AssignmentRepository assignments;
    private final EnrollmentRepository enrollments;
    private final CohortRepository cohorts;
    private final SubmissionRepository submissions;
    private final UserRepository users;
    private final EmailOutboxRepository outbox;
    private final NotificationRepository notifications;
    private final SettingRepository settings;

    public DueReminderJob(AssignmentRepository assignments, EnrollmentRepository enrollments,
                          CohortRepository cohorts, SubmissionRepository submissions,
                          UserRepository users, EmailOutboxRepository outbox,
                          NotificationRepository notifications, SettingRepository settings) {
        this.assignments = assignments;
        this.enrollments = enrollments;
        this.cohorts = cohorts;
        this.submissions = submissions;
        this.users = users;
        this.outbox = outbox;
        this.notifications = notifications;
        this.settings = settings;
    }

    @Scheduled(fixedDelay = "1h", initialDelay = "45s")
    @Transactional
    public void run() {
        int windowHours = settings.findById("due_reminder_window_hours")
                .map(s -> parse(s.getValue(), 24)).orElse(24);
        Instant now = Instant.now();
        Instant windowEnd = now.plusSeconds(windowHours * 3600L);
        int reminded = 0;
        for (Assignment assignment : assignments.findDueSoon(Assignment.STATUS_PUBLISHED, now, windowEnd)) {
            reminded += remindFor(assignment);
        }
        if (reminded > 0) {
            LOG.info("due reminders sent: {}", reminded);
        }
    }

    private int remindFor(Assignment assignment) {
        Set<Long> students = new HashSet<>();
        if (assignment.getCohortId() != null) {
            for (Enrollment e : enrollments.findByCohortIdAndStatus(assignment.getCohortId(), Enrollment.STATUS_ACTIVE)) {
                students.add(e.getStudentId());
            }
        } else {
            for (var cohort : cohorts.findByCourseId(assignment.getCourseId())) {
                for (Enrollment e : enrollments.findByCohortIdAndStatus(cohort.getId(), Enrollment.STATUS_ACTIVE)) {
                    students.add(e.getStudentId());
                }
            }
        }
        int reminded = 0;
        for (Long studentId : students) {
            if (submissions.countByAssignmentAndStudent(assignment.getId(), studentId) > 0) {
                continue;
            }
            User user = users.findById(studentId).orElse(null);
            if (user == null) {
                continue;
            }
            Notification notification = new Notification();
            notification.setUserId(studentId);
            notification.setKind("assignment.deadline_reminder");
            notification.setTitle("Due soon: " + assignment.getTitle());
            notification.setBody("Your submission is due by " + assignment.getDueAt() + ". Don't forget to submit!");
            notification.setLink("/assignments/" + assignment.getId());
            notifications.save(notification);

            EmailOutbox mail = new EmailOutbox();
            mail.setToAddress(user.getEmail());
            mail.setSubject("Reminder: " + assignment.getTitle() + " is due soon");
            mail.setBodyText("Hi " + user.getDisplayName() + ",\n\nThis is a reminder that \""
                    + assignment.getTitle() + "\" is due by " + assignment.getDueAt()
                    + ".\n\nSubmit your work before the deadline.\n\nBrightpath Academy");
            mail.setTemplateKey("deadline_reminder");
            outbox.save(mail);
            reminded++;
        }
        return reminded;
    }

    private static int parse(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
