package com.brightpath.channel.service;

import com.brightpath.channel.domain.User;
import com.brightpath.channel.repository.UserRepository;
import com.brightpath.channel.security.AppException;
import jakarta.inject.Singleton;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stateless-ish one-time sign-in links: a lightweight alternative to the
 * password-reset flow, used for onboarding emails. Links are tracked
 * in-memory (single-node deployment) rather than in a dedicated table.
 */
@Singleton
public class MagicLinkService {

    /** How long an issued sign-in link stays valid. */
    private static final int LINK_TTL_DAYS = 30;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, Entry> links = new ConcurrentHashMap<>();
    private final UserRepository users;
    private final MailService mail;
    private final SettingsService settings;

    public MagicLinkService(UserRepository users, MailService mail, SettingsService settings) {
        this.users = users;
        this.mail = mail;
        this.settings = settings;
    }

    public void requestLink(String email, String baseUrl) {
        String normalized = UserService.normalizeEmail(email);
        // Unlike /password/forgot (which always responds the same way), this newer
        // endpoint surfaces whether the address has an account so the sign-in page can
        // show a clear "no account with that email" message instead of a silent no-op.
        User user = users.findByEmail(normalized)
                .orElseThrow(() -> AppException.notFound("No account found for that email address."));
        String tok = newToken();
        links.put(tok, new Entry(user.getId(), Instant.now().plus(LINK_TTL_DAYS, ChronoUnit.DAYS)));
        mail.queue(user.getEmail(), "Your " + settings.orgName() + " sign-in link",
                "Hi " + user.getDisplayName() + ",\n\nUse the link below to sign in:\n\n"
                        + baseUrl + "/auth/magic-link/consume?tok=" + tok
                        + "\n\nIf you did not request this, you can ignore this email.\n\n"
                        + settings.orgName() + " team",
                MailService.TEMPLATE_WELCOME);
    }

    public Optional<Long> consume(String tok) {
        if (tok == null) {
            return Optional.empty();
        }
        Entry entry = links.remove(tok);
        if (entry == null || entry.expiresAt.isBefore(Instant.now())) {
            return Optional.empty();
        }
        return Optional.of(entry.userId);
    }

    private static String newToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static final class Entry {
        final Long userId;
        final Instant expiresAt;

        Entry(Long userId, Instant expiresAt) {
            this.userId = userId;
            this.expiresAt = expiresAt;
        }
    }
}
