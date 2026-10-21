package com.brightpath.channel.service;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Encodes and decodes the short-lived "share this report" codes used by the
 * export screens. The ciphertext carries the course (and optional cohort) so
 * the recipient does not have to be told the ids separately.
 */
@Singleton
public class ReportCodeService {

    private static final int IV_LEN = 16;

    private final byte[] key;
    private final SecureRandom random = new SecureRandom();

    public ReportCodeService(@Value("${brightpath.report-code.phrase}") String phrase) {
        try {
            this.key = Arrays.copyOf(MessageDigest.getInstance("SHA-256")
                    .digest(phrase.getBytes(StandardCharsets.UTF_8)), 16);
        } catch (Exception e) {
            throw new IllegalStateException("report code key unavailable", e);
        }
    }

    public String seal(String value) {
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            byte[] body = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] all = new byte[iv.length + body.length];
            System.arraycopy(iv, 0, all, 0, iv.length);
            System.arraycopy(body, 0, all, iv.length, body.length);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(all);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("report code could not be created", e);
        }
    }

    public String reveal(String code) throws GeneralSecurityException {
        byte[] all = Base64.getUrlDecoder().decode(code);
        byte[] iv = Arrays.copyOfRange(all, 0, IV_LEN);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        byte[] body = cipher.doFinal(Arrays.copyOfRange(all, IV_LEN, all.length));
        return new String(body, StandardCharsets.UTF_8);
    }
}
