package com.brightpath.channel.service;

import com.brightpath.channel.domain.Notification;
import com.brightpath.channel.repository.NotificationRepository;
import jakarta.inject.Singleton;

import java.util.Collection;

/** In-app notifications for workflow events. */
@Singleton
public class NotificationService {

    public static final String KIND_ENROLLMENT_REQUESTED = "enrollment.requested";
    public static final String KIND_ENROLLMENT_APPROVED = "enrollment.approved";
    public static final String KIND_ENROLLMENT_DROPPED = "enrollment.dropped";
    public static final String KIND_ASSIGNMENT_PUBLISHED = "assignment.published";
    public static final String KIND_ASSIGNMENT_EXTENDED = "assignment.extended";
    public static final String KIND_SUBMISSION_RECEIVED = "submission.received";
    public static final String KIND_RESUBMISSION_REQUESTED = "submission.resubmission_requested";
    public static final String KIND_GRADE_RELEASED = "grade.released";
    public static final String KIND_GRADE_DRAFTED = "grade.drafted";
    public static final String KIND_DISCUSSION_REPLY = "discussion.reply";
    public static final String KIND_ROLE_CHANGED = "account.role_changed";
    public static final String KIND_ACCOUNT_STATUS = "account.status";
    public static final String KIND_DEADLINE_REMINDER = "assignment.deadline_reminder";

    private final NotificationRepository repository;

    public NotificationService(NotificationRepository repository) {
        this.repository = repository;
    }

    public void notify(Long userId, String kind, String title, String body, String link) {
        Notification n = new Notification();
        n.setUserId(userId);
        n.setKind(kind);
        n.setTitle(title);
        n.setBody(body);
        n.setLink(link);
        repository.save(n);
    }

    public void notifyAll(Collection<Long> userIds, String kind, String title, String body, String link) {
        for (Long userId : userIds) {
            notify(userId, kind, title, body, link);
        }
    }
}
