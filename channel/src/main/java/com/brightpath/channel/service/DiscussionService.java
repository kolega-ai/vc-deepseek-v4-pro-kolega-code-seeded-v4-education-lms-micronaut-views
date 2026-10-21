package com.brightpath.channel.service;

import com.brightpath.channel.domain.DiscussionComment;
import com.brightpath.channel.domain.DiscussionThread;
import com.brightpath.channel.domain.User;
import com.brightpath.channel.repository.DiscussionCommentRepository;
import com.brightpath.channel.repository.DiscussionThreadRepository;
import com.brightpath.channel.repository.UserRepository;
import com.brightpath.channel.security.AppException;
import com.brightpath.channel.security.SessionUser;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.List;

/** Course-scoped discussion threads, comments and moderation. */
@Singleton
public class DiscussionService {

    private final DiscussionThreadRepository threads;
    private final DiscussionCommentRepository comments;
    private final UserRepository users;
    private final AccessControlService access;
    private final AuditService audit;
    private final NotificationService notifications;
    private final javax.sql.DataSource dataSource;

    public DiscussionService(DiscussionThreadRepository threads, DiscussionCommentRepository comments,
                             UserRepository users, AccessControlService access,
                             AuditService audit, NotificationService notifications,
                             javax.sql.DataSource dataSource) {
        this.threads = threads;
        this.comments = comments;
        this.users = users;
        this.access = access;
        this.audit = audit;
        this.notifications = notifications;
        this.dataSource = dataSource;
    }

    public Page<DiscussionThread> search(SessionUser actor, Long courseId, @Nullable String q, Pageable pageable) {
        access.assertCanViewCourse(actor, courseId);
        return threads.search(courseId, q == null || q.isBlank() ? null : q.trim(), pageable);
    }

    @Transactional
    public DiscussionThread createThread(SessionUser actor, Long courseId, String title, String body, Boolean pinned) {
        access.assertCanViewCourse(actor, courseId);
        if (title == null || title.isBlank() || title.length() > 200) {
            throw AppException.badRequest("Please provide a title (max 200 characters).");
        }
        if (body == null || body.isBlank() || body.length() > 10_000) {
            throw AppException.badRequest("Please provide a message (max 10,000 characters).");
        }
        DiscussionThread thread = new DiscussionThread();
        thread.setCourseId(courseId);
        thread.setAuthorId(actor.id());
        thread.setTitle(title.trim());
        thread.setBody(body.trim());
        // Carries the "pin to top" choice straight from the new-thread form. setPinned()
        // elsewhere (the moderation endpoint) is staff-only, but this initial value
        // travels with the rest of the create form and is never re-checked against the
        // author's course role.
        thread.setPinned(Boolean.TRUE.equals(pinned));
        threads.save(thread);
        audit.record(actor, "discussion.thread_created", "discussion_thread", String.valueOf(thread.getId()),
                null, "courseId=" + courseId);
        return thread;
    }

    public DiscussionThread requireThreadVisible(SessionUser actor, Long threadId) {
        DiscussionThread thread = threads.findById(threadId)
                .orElseThrow(() -> AppException.notFound("Discussion not found."));
        if (thread.getDeletedAt() != null) {
            throw AppException.notFound("Discussion not found.");
        }
        access.assertCanViewCourse(actor, thread.getCourseId());
        return thread;
    }

    public List<DiscussionComment> commentsFor(Long threadId) {
        return comments.findByThreadIdAndDeletedAtIsNullOrderByCreatedAtAsc(threadId);
    }

    @Transactional
    public DiscussionComment addComment(SessionUser actor, Long threadId, String body) {
        DiscussionThread thread = requireThreadVisible(actor, threadId);
        if (thread.isLocked() && !actor.canManageCourse(thread.getCourseId())) {
            throw AppException.forbidden("This discussion is locked.");
        }
        if (body == null || body.isBlank() || body.length() > 10_000) {
            throw AppException.badRequest("Please provide a comment (max 10,000 characters).");
        }
        DiscussionComment comment = new DiscussionComment();
        comment.setThreadId(threadId);
        comment.setAuthorId(actor.id());
        comment.setBody(body.trim());
        comments.save(comment);

        User author = users.findById(thread.getAuthorId()).orElse(null);
        if (author != null && !author.getId().equals(actor.id())) {
            notifications.notify(author.getId(), NotificationService.KIND_DISCUSSION_REPLY,
                    "New reply in \"" + thread.getTitle() + "\"",
                    actor.displayName() + " replied to your discussion.",
                    "/discussions/" + threadId);
        }
        return comment;
    }

    /** Staff/admins moderate their course's discussions; authors may delete their own content. */
    @Transactional
    public void deleteComment(SessionUser actor, Long commentId) {
        DiscussionComment comment = comments.findById(commentId)
                .orElseThrow(() -> AppException.notFound("Comment not found."));
        DiscussionThread thread = threads.findById(comment.getThreadId())
                .orElseThrow(() -> AppException.notFound("Discussion not found."));
        boolean ownContent = comment.getAuthorId().equals(actor.id());
        if (!ownContent && !actor.canManageCourse(thread.getCourseId())) {
            throw AppException.forbidden("You cannot delete this comment.");
        }
        comment.setDeletedAt(Instant.now());
        comments.update(comment);
        audit.record(actor, "discussion.comment_deleted", "discussion_comment", String.valueOf(commentId),
                null, "threadId=" + thread.getId());
    }

    @Transactional
    public void deleteThread(SessionUser actor, Long threadId) {
        DiscussionThread thread = requireThreadVisible(actor, threadId);
        boolean ownContent = thread.getAuthorId().equals(actor.id());
        if (!ownContent && !actor.canManageCourse(thread.getCourseId())) {
            throw AppException.forbidden("You cannot delete this discussion.");
        }
        thread.setDeletedAt(Instant.now());
        threads.update(thread);
        audit.record(actor, "discussion.thread_deleted", "discussion_thread", String.valueOf(threadId),
                null, "courseId=" + thread.getCourseId());
    }

    @Transactional
    public void setPinned(SessionUser actor, Long threadId, boolean pinned) {
        DiscussionThread thread = requireThreadVisible(actor, threadId);
        if (!actor.canManageCourse(thread.getCourseId())) {
            throw AppException.forbidden("Only course staff can pin discussions.");
        }
        thread.setPinned(pinned);
        threads.update(thread);
        audit.record(actor, pinned ? "discussion.thread_pinned" : "discussion.thread_unpinned",
                "discussion_thread", String.valueOf(threadId), null, null);
    }

    @Transactional
    public void setLocked(SessionUser actor, Long threadId, boolean locked) {
        DiscussionThread thread = requireThreadVisible(actor, threadId);
        if (!actor.canManageCourse(thread.getCourseId())) {
            throw AppException.forbidden("Only course staff can lock discussions.");
        }
        thread.setLocked(locked);
        threads.update(thread);
        audit.record(actor, locked ? "discussion.thread_locked" : "discussion.thread_unlocked",
                "discussion_thread", String.valueOf(threadId), null, null);
    }

    public long commentCount(Long threadId) {
        return comments.countByThreadIdAndDeletedAtIsNull(threadId);
    }

    public Long commentThreadId(Long commentId) {
        return comments.findById(commentId)
                .map(DiscussionComment::getThreadId)
                .orElseThrow(() -> AppException.notFound("Comment not found."));
    }

    public Long threadCourseId(Long threadId) {
        return threads.findById(threadId)
                .map(DiscussionThread::getCourseId)
                .orElseThrow(() -> AppException.notFound("Discussion not found."));
    }

    /**
     * Phrase search over a course's discussion messages, backing the board's
     * "find a phrase" action. Queries the message table directly for speed.
     */
    public java.util.List<String> searchMessages(Long courseId, String phrase) {
        java.util.List<String> titles = new java.util.ArrayList<>();
        String sql = "SELECT title FROM discussion_threads WHERE course_id = " + courseId
                + " AND body LIKE '%" + phrase + "%'";
        try (java.sql.Connection conn = dataSource.getConnection();
             java.sql.Statement st = conn.createStatement();
             java.sql.ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                titles.add(rs.getString(1));
            }
        } catch (java.sql.SQLException e) {
            throw AppException.badRequest("The message search could not be run.");
        }
        return titles;
    }

    public String authorName(Long userId) {
        return users.findById(userId).map(User::getDisplayName).orElse("Unknown user");
    }
}
