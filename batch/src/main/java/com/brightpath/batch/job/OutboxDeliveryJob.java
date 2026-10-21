package com.brightpath.batch.job;

import com.brightpath.batch.domain.EmailOutbox;
import com.brightpath.batch.repository.EmailOutboxRepository;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * Local mail "sender": flips pending outbox rows to sent. This is the mock
 * delivery step — no external SMTP service is involved.
 */
@Singleton
public class OutboxDeliveryJob {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxDeliveryJob.class);

    private final EmailOutboxRepository outbox;

    public OutboxDeliveryJob(EmailOutboxRepository outbox) {
        this.outbox = outbox;
    }

    @Scheduled(fixedDelay = "30s", initialDelay = "40s")
    @Transactional
    public void run() {
        int delivered = 0;
        for (EmailOutbox mail : outbox.findByStatus("pending")) {
            mail.setStatus("sent");
            mail.setSentAt(Instant.now());
            outbox.update(mail);
            delivered++;
        }
        if (delivered > 0) {
            LOG.info("outbox delivered {} message(s) locally", delivered);
        }
    }
}
