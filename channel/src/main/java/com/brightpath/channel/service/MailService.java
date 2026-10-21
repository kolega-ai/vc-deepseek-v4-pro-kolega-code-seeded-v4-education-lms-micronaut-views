package com.brightpath.channel.service;

import com.brightpath.channel.domain.EmailOutbox;
import com.brightpath.channel.repository.EmailOutboxRepository;
import jakarta.inject.Singleton;

/**
 * Local email mock. Outbound messages are persisted to the email_outbox table;
 * no external SMTP/SaaS is used. The batch module can flip rows to "sent" to
 * simulate delivery.
 */
@Singleton
public class MailService {

    public static final String TEMPLATE_PASSWORD_RESET = "password_reset";
    public static final String TEMPLATE_WELCOME = "welcome";
    public static final String TEMPLATE_GRADE_RELEASED = "grade_released";
    public static final String TEMPLATE_ENROLLMENT_APPROVED = "enrollment_approved";
    public static final String TEMPLATE_ASSIGNMENT_PUBLISHED = "assignment_published";
    public static final String TEMPLATE_DEADLINE_REMINDER = "deadline_reminder";
    public static final String TEMPLATE_ACCOUNT_CREATED = "account_created";

    private final EmailOutboxRepository repository;

    public MailService(EmailOutboxRepository repository) {
        this.repository = repository;
    }

    public void queue(String toAddress, String subject, String bodyText, String templateKey) {
        EmailOutbox mail = new EmailOutbox();
        mail.setToAddress(toAddress);
        mail.setSubject(subject);
        mail.setBodyText(bodyText);
        mail.setTemplateKey(templateKey);
        repository.save(mail);
    }
}
