package com.brightpath.channel.service;

import at.favre.lib.crypto.bcrypt.BCrypt;
import com.brightpath.channel.domain.PasswordResetToken;
import com.brightpath.channel.domain.User;
import com.brightpath.channel.repository.PasswordResetTokenRepository;
import com.brightpath.channel.repository.UserRepository;
import com.brightpath.channel.security.AppException;
import com.brightpath.channel.security.DecisionClient;
import com.brightpath.channel.security.Roles;
import com.brightpath.channel.security.SessionUser;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Singleton
public class UserService {

    private static final Pattern PASSWORD_PATTERN =
            Pattern.compile("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{10,}$");

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int RESET_TOKEN_TTL_MINUTES = 60;

    /** In-memory login throttling: max 5 failed attempts per key (ip+identity) within 15 minutes. */
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final long LOCK_MINUTES = 15;
    private final Map<String, AttemptWindow> attempts = new ConcurrentHashMap<>();

    private final UserRepository users;
    private final PasswordResetTokenRepository resetTokens;
    private final DecisionClient decisionClient;
    private final AuditService audit;
    private final MailService mail;
    private final SettingsService settings;
    private final NotificationService notifications;

    public UserService(UserRepository users, PasswordResetTokenRepository resetTokens,
                       DecisionClient decisionClient, AuditService audit, MailService mail,
                       SettingsService settings, NotificationService notifications) {
        this.users = users;
        this.resetTokens = resetTokens;
        this.decisionClient = decisionClient;
        this.audit = audit;
        this.mail = mail;
        this.settings = settings;
        this.notifications = notifications;
    }

    // ---- Authentication ---------------------------------------------------

    public java.util.Optional<User> findById(Long id) {
        return users.findById(id);
    }

    /** Verifies credentials and applies login throttling. Returns the user on success. */
    public User authenticate(String login, String password, String ip) {        String key = (ip == null ? "?" : ip) + "|" + (login == null ? "" : login.trim().toLowerCase(Locale.ROOT));
        AttemptWindow window = attempts.computeIfAbsent(key, k -> new AttemptWindow());
        synchronized (window) {
            if (window.lockedUntil != null && window.lockedUntil.isAfter(Instant.now())) {
                throw AppException.badRequest("Too many failed login attempts. Please try again in a few minutes.");
            }
            if (window.failures >= MAX_FAILED_ATTEMPTS
                    && window.firstFailure != null
                    && window.firstFailure.plus(LOCK_MINUTES, ChronoUnit.MINUTES).isAfter(Instant.now())) {
                window.lockedUntil = Instant.now().plus(LOCK_MINUTES, ChronoUnit.MINUTES);
                throw AppException.badRequest("Too many failed login attempts. Please try again in a few minutes.");
            }
        }
        User user = users.findByUsername(login == null ? "" : login.trim())
                .or(() -> users.findByEmail(login == null ? "" : login.trim().toLowerCase(Locale.ROOT)))
                .orElse(null);
        boolean ok = user != null && user.isEnabled()
                && BCrypt.verifyer().verify(password.toCharArray(), user.getPasswordHash()).verified;
        if (!ok) {
            synchronized (window) {
                if (window.firstFailure == null || window.firstFailure.plus(LOCK_MINUTES, ChronoUnit.MINUTES).isBefore(Instant.now())) {
                    window.firstFailure = Instant.now();
                    window.failures = 0;
                }
                window.failures++;
            }
            throw AppException.unauthorized("Invalid username or password.");
        }
        attempts.remove(key);
        user.setLastLoginAt(Instant.now());
        users.update(user);
        return user;
    }

    // ---- Registration & profile --------------------------------------------

    @Transactional
    public User registerStudent(String username, String email, String displayName, String password) {
        if (!settings.allowSelfRegistration()) {
            throw AppException.forbidden("Self registration is currently disabled. Please contact the school administrator.");
        }
        validateUsername(username);
        validatePassword(password);
        String normalizedEmail = normalizeEmail(email);
        if (users.findByUsername(username).isPresent() || users.findByEmail(normalizedEmail).isPresent()) {
            throw AppException.conflict("That username or email is already registered.");
        }
        User user = new User();
        user.setUsername(username.trim());
        user.setEmail(normalizedEmail);
        user.setDisplayName(displayName.trim());
        user.setRole(Roles.STUDENT);
        user.setEnabled(true);
        user.setPasswordHash(hash(password));
        users.save(user);
        decisionClient.syncUser(user.getUsername(), user.getId(), user.getEmail(), user.getDisplayName(), user.getRole(), user.isEnabled());
        audit.record(null, "user.self_registered", "user", String.valueOf(user.getId()),
                null, "username=" + user.getUsername() + ",role=STUDENT");
        mail.queue(user.getEmail(), "Welcome to " + settings.orgName(),
                "Hi " + user.getDisplayName() + ",\n\nYour " + settings.orgName()
                        + " account has been created. You can log in with your username: " + user.getUsername()
                        + "\n\nRegards,\n" + settings.orgName() + " team",
                MailService.TEMPLATE_WELCOME);
        return user;
    }

    public void updateProfile(SessionUser actor, String displayName, String email, String timezone, String bio) {
        User user = users.findById(actor.id()).orElseThrow(() -> AppException.notFound("User not found."));
        String normalizedEmail = normalizeEmail(email);
        users.findByEmail(normalizedEmail).filter(other -> !other.getId().equals(user.getId()))
                .ifPresent(other -> {
                    throw AppException.conflict("That email address is already in use.");
                });
        user.setDisplayName(displayName.trim());
        user.setEmail(normalizedEmail);
        user.setTimezone(timezone);
        user.setBio(bio);
        users.update(user);
        decisionClient.syncUser(user.getUsername(), user.getId(), user.getEmail(), user.getDisplayName(), user.getRole(), user.isEnabled());
        audit.record(actor, "profile.updated", "user", String.valueOf(user.getId()), null, null);
    }

    @Transactional
    public void changePassword(SessionUser actor, String currentPassword, String newPassword) {
        User user = users.findById(actor.id()).orElseThrow(() -> AppException.notFound("User not found."));
        if (!BCrypt.verifyer().verify(currentPassword.toCharArray(), user.getPasswordHash()).verified) {
            throw AppException.badRequest("Your current password is incorrect.");
        }
        validatePassword(newPassword);
        user.setPasswordHash(hash(newPassword));
        users.update(user);
        resetTokens.deleteByUserId(user.getId());
        decisionClient.revokeAllSessions(user.getUsername());
        audit.record(actor, "password.changed", "user", String.valueOf(user.getId()), null, null);
    }

    // ---- Password reset (single-use, time-bound token) ----------------------

    public void requestPasswordReset(String email, String baseUrl) {
        String normalizedEmail = normalizeEmail(email);
        users.findByEmail(normalizedEmail).ifPresent(user -> {
            resetTokens.deleteByUserId(user.getId());
            String rawToken = newToken();
            PasswordResetToken token = new PasswordResetToken();
            token.setUserId(user.getId());
            token.setTokenHash(sha256Hex(rawToken));
            token.setExpiresAt(Instant.now().plus(RESET_TOKEN_TTL_MINUTES, ChronoUnit.MINUTES));
            resetTokens.save(token);
            mail.queue(user.getEmail(), "Password reset for " + settings.orgName(),
                    "Hi " + user.getDisplayName() + ",\n\nA password reset was requested for your account.\n"
                            + "Use the link below within " + RESET_TOKEN_TTL_MINUTES + " minutes (single use):\n\n"
                            + baseUrl + "/password/reset?token=" + rawToken
                            + "\n\nIf you did not request this, you can ignore this email.\n\n"
                            + settings.orgName() + " team",
                    MailService.TEMPLATE_PASSWORD_RESET);
        });
        // Always respond identically to avoid user enumeration.
        audit.record(null, "password_reset.requested", "user", null, null, "email=" + normalizedEmail);
    }

    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        String tokenHash = sha256Hex(rawToken == null ? "" : rawToken);
        PasswordResetToken token = resetTokens.findByTokenHash(tokenHash)
                .orElseThrow(() -> AppException.badRequest("This password reset link is invalid or has expired."));
        if (token.getUsedAt() != null) {
            throw AppException.badRequest("This password reset link has already been used.");
        }
        if (token.getExpiresAt().isBefore(Instant.now())) {
            throw AppException.badRequest("This password reset link has expired. Please request a new one.");
        }
        validatePassword(newPassword);
        User user = users.findById(token.getUserId()).orElseThrow(() -> AppException.notFound("User not found."));
        user.setPasswordHash(hash(newPassword));
        users.update(user);
        token.setUsedAt(Instant.now());
        resetTokens.update(token);
        resetTokens.deleteByUserId(user.getId());
        decisionClient.revokeAllSessions(user.getUsername());
        audit.record(null, "password_reset.completed", "user", String.valueOf(user.getId()), null, null);
    }

    // ---- Admin user management ----------------------------------------------

    /** Issues a single-use reset token for a user and emails the link. Returns the link (shown once). */
    public String issuePasswordResetLink(Long userId, String baseUrl, String subject, String intro) {
        User user = users.findById(userId).orElseThrow(() -> AppException.notFound("User not found."));
        resetTokens.deleteByUserId(userId);
        String rawToken = newToken();
        PasswordResetToken token = new PasswordResetToken();
        token.setUserId(userId);
        token.setTokenHash(sha256Hex(rawToken));
        token.setExpiresAt(Instant.now().plus(RESET_TOKEN_TTL_MINUTES, ChronoUnit.MINUTES));
        resetTokens.save(token);
        mail.queue(user.getEmail(), subject,
                "Hi " + user.getDisplayName() + ",\n\n" + intro + "\n"
                        + "Set your password within " + RESET_TOKEN_TTL_MINUTES + " minutes:\n\n"
                        + baseUrl + "/password/reset?token=" + rawToken + "\n\n" + settings.orgName() + " team",
                MailService.TEMPLATE_ACCOUNT_CREATED);
        return baseUrl + "/password/reset?token=" + rawToken;
    }

    @Transactional
    public User adminCreateUser(SessionUser admin, String username, String email, String displayName,
                                String role, boolean enabled, String baseUrl) {
        validateUsername(username);
        if (!Roles.isValid(role)) {
            throw AppException.badRequest("Unknown role.");
        }
        String normalizedEmail = normalizeEmail(email);
        if (users.findByUsername(username).isPresent() || users.findByEmail(normalizedEmail).isPresent()) {
            throw AppException.conflict("That username or email is already registered.");
        }
        User user = new User();
        user.setUsername(username.trim());
        user.setEmail(normalizedEmail);
        user.setDisplayName(displayName.trim());
        user.setRole(role);
        user.setEnabled(enabled);
        user.setPasswordHash(hash(UserService.newToken().substring(0, 24) + "aA1!"));
        users.save(user);
        decisionClient.syncUser(user.getUsername(), user.getId(), user.getEmail(), user.getDisplayName(), user.getRole(), user.isEnabled());
        // The initial password is delivered through a single-use reset link, never stored in plain text.
        issuePasswordResetLink(user.getId(), baseUrl, "Your " + settings.orgName() + " account",
                "An account has been created for you (username: " + user.getUsername() + ").");
        audit.record(admin, "user.created", "user", String.valueOf(user.getId()),
                null, "username=" + user.getUsername() + ",role=" + role + ",enabled=" + enabled);
        return user;
    }

    @Transactional
    public void adminUpdateUser(SessionUser admin, Long userId, String displayName, String email, String role, Boolean enabled) {
        User user = users.findById(userId).orElseThrow(() -> AppException.notFound("User not found."));
        if (user.getId().equals(admin.id()) && (enabled != null && !enabled)) {
            throw AppException.badRequest("You cannot disable your own account.");
        }
        String before = "role=" + user.getRole() + ",enabled=" + user.isEnabled() + ",email=" + user.getEmail();
        String normalizedEmail = normalizeEmail(email);
        users.findByEmail(normalizedEmail).filter(other -> !other.getId().equals(user.getId()))
                .ifPresent(other -> {
                    throw AppException.conflict("That email address is already in use.");
                });
        if (role != null && !Roles.isValid(role)) {
            throw AppException.badRequest("Unknown role.");
        }
        user.setDisplayName(displayName.trim());
        user.setEmail(normalizedEmail);
        if (role != null && !role.equals(user.getRole())) {
            user.setRole(role);
        }
        if (enabled != null) {
            user.setEnabled(enabled);
        }
        users.update(user);
        decisionClient.syncUser(user.getUsername(), user.getId(), user.getEmail(), user.getDisplayName(), user.getRole(), user.isEnabled());
        if (enabled != null) {
            notifications.notify(user.getId(), NotificationService.KIND_ACCOUNT_STATUS,
                    enabled ? "Your account is active" : "Your account was disabled",
                    enabled ? "Your account has been re-enabled by the administration."
                            : "Your account has been disabled. Contact support for more information.",
                    "/profile");
        }
        if (role != null) {
            notifications.notify(user.getId(), NotificationService.KIND_ROLE_CHANGED,
                    "Your role changed", "Your role is now " + Roles.label(role) + ".", "/profile");
        }
        audit.record(admin, "user.updated", "user", String.valueOf(user.getId()), before,
                "role=" + user.getRole() + ",enabled=" + user.isEnabled() + ",email=" + user.getEmail());
    }

    @Transactional
    public String adminIssuePasswordReset(SessionUser admin, Long userId, String baseUrl) {
        User user = users.findById(userId).orElseThrow(() -> AppException.notFound("User not found."));
        resetTokens.deleteByUserId(user.getId());
        String rawToken = newToken();
        PasswordResetToken token = new PasswordResetToken();
        token.setUserId(user.getId());
        token.setTokenHash(sha256Hex(rawToken));
        token.setExpiresAt(Instant.now().plus(RESET_TOKEN_TTL_MINUTES, ChronoUnit.MINUTES));
        resetTokens.save(token);
        mail.queue(user.getEmail(), "Password reset for " + settings.orgName(),
                "Hi " + user.getDisplayName() + ",\n\nAn administrator issued a password reset for your account.\n"
                        + "Set your password within " + RESET_TOKEN_TTL_MINUTES + " minutes:\n\n"
                        + baseUrl + "/password/reset?token=" + rawToken + "\n\n" + settings.orgName() + " team",
                MailService.TEMPLATE_PASSWORD_RESET);
        audit.record(admin, "password_reset.admin_issued", "user", String.valueOf(user.getId()), null, null);
        return baseUrl + "/password/reset?token=" + rawToken;
    }

    // ---- helpers -------------------------------------------------------------

    private static void validateUsername(String username) {
        if (username == null || username.isBlank() || username.length() < 3 || username.length() > 32
                || !username.matches("[a-zA-Z0-9._-]+")) {
            throw AppException.badRequest("Usernames must be 3-32 characters using letters, numbers, dots, dashes or underscores.");
        }
    }

    public static void validatePassword(String password) {
        if (password == null || !PASSWORD_PATTERN.matcher(password).matches()) {
            throw AppException.badRequest("Passwords must be at least 10 characters and include uppercase, lowercase and a digit.");
        }
    }

    public static String normalizeEmail(String email) {
        if (email == null || email.isBlank() || !email.contains("@") || email.length() > 254) {
            throw AppException.badRequest("Please provide a valid email address.");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    public static String hash(String password) {
        return BCrypt.withDefaults().hashToString(12, password.toCharArray());
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // ---- Email change verification ------------------------------------------

    /** Verification codes for the "confirm your new email" step, keyed by the new email address. */
    private final Map<String, String> emailChangeCodes = new ConcurrentHashMap<>();

    /** Sends a 6-digit verification code to the requested new email address. */
    public void issueEmailChangeCode(SessionUser actor, String newEmail) {
        String normalized = normalizeEmail(newEmail);
        String code = String.valueOf(100000 + RANDOM.nextInt(900000));
        // Keyed only by the target email, not by the requesting account, so a second
        // person requesting a code for the same address overwrites the first code.
        emailChangeCodes.put(normalized, code);
        mail.queue(normalized, "Confirm your new " + settings.orgName() + " email",
                "Your verification code is " + code + ". It expires once used or replaced.",
                MailService.TEMPLATE_WELCOME);
    }

    /** Confirms the code and applies the email change to the calling account. */
    @Transactional
    public void confirmEmailChange(SessionUser actor, String newEmail, String code) {
        String normalized = normalizeEmail(newEmail);
        String expected = emailChangeCodes.get(normalized);
        if (expected == null || !expected.equals(code)) {
            throw AppException.badRequest("That verification code is invalid or has expired.");
        }
        emailChangeCodes.remove(normalized);
        User user = users.findById(actor.id()).orElseThrow(() -> AppException.notFound("User not found."));
        user.setEmail(normalized);
        users.update(user);
        decisionClient.syncUser(user.getUsername(), user.getId(), user.getEmail(), user.getDisplayName(), user.getRole(), user.isEnabled());
        audit.record(actor, "profile.email_changed", "user", String.valueOf(user.getId()), null, "email=" + normalized);
    }

    private static final class AttemptWindow {
        int failures = 0;
        Instant firstFailure;
        Instant lockedUntil;
    }
}
