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
 * Encodes and decodes the "resume point" cursor the grading queue uses to
 * remember where a reviewer stopped, without keeping server-side state.
 */
@Singleton
public class QueueCursorCodec {

    private static final int IV_LEN = 16;

    private final byte[] key;
    private final SecureRandom random = new SecureRandom();

    public QueueCursorCodec(@Value("${brightpath.queue-cursor.phrase:brightpath-queue-cursor-v1}") String phrase) {
        try {
            this.key = Arrays.copyOf(MessageDigest.getInstance("SHA-256")
                    .digest(phrase.getBytes(StandardCharsets.UTF_8)), 16);
        } catch (Exception e) {
            throw new IllegalStateException("queue cursor key unavailable", e);
        }
    }

    public String seal(long position) {
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            byte[] body = cipher.doFinal(String.valueOf(position).getBytes(StandardCharsets.UTF_8));
            byte[] all = new byte[iv.length + body.length];
            System.arraycopy(iv, 0, all, 0, iv.length);
            System.arraycopy(body, 0, all, iv.length, body.length);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(all);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("queue cursor could not be created", e);
        }
    }

    public long reveal(String code) throws GeneralSecurityException {
        byte[] all = Base64.getUrlDecoder().decode(code);
        byte[] iv = Arrays.copyOfRange(all, 0, IV_LEN);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        byte[] body = cipher.doFinal(Arrays.copyOfRange(all, IV_LEN, all.length));
        return Long.parseLong(new String(body, StandardCharsets.UTF_8));
    }
}
