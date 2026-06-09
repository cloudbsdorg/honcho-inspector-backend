package com.honcho.dashboard.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption for Honcho API keys at rest.
 *
 * <p>Key source: {@code HONCHO_CRYPTO_KEY} env var or {@code honcho.crypto-key} property,
 * base64-encoded 32 bytes. Generate one with {@code openssl rand -base64 32}.
 *
 * <p>Wire format: base64( IV(12) || ciphertext || GCM_tag(16) ).
 */
@Component
public class CryptoService {

    private static final Logger log = LoggerFactory.getLogger(CryptoService.class);
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final String TRANSFORM = "AES/GCM/NoPadding";

    private final SecretKey key;
    private final SecureRandom rng = new SecureRandom();
    private final boolean ephemeral;

    public CryptoService(@Value("${honcho.crypto-key:}") String base64Key) {
        this.ephemeral = (base64Key == null || base64Key.isBlank());
        if (ephemeral) {
            var bytes = new byte[32];
            rng.nextBytes(bytes);
            this.key = new SecretKeySpec(bytes, "AES");
            log.warn("honcho.crypto-key not set — using an ephemeral random key. Encrypted values will not survive a restart. Set HONCHO_CRYPTO_KEY for persistent encryption.");
        } else {
            var bytes = Base64.getDecoder().decode(base64Key.trim());
            if (bytes.length != 32) {
                throw new IllegalStateException("honcho.crypto-key must decode to 32 bytes, got " + bytes.length);
            }
            this.key = new SecretKeySpec(bytes, "AES");
        }
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) return null;
        try {
            var iv = new byte[IV_LEN];
            rng.nextBytes(iv);
            var cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            var ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            var out = new byte[IV_LEN + ct.length];
            System.arraycopy(iv, 0, out, 0, IV_LEN);
            System.arraycopy(ct, 0, out, IV_LEN, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new CryptoException("encrypt failed", e);
        }
    }

    public String decrypt(String base64Ciphertext) {
        if (base64Ciphertext == null || base64Ciphertext.isBlank()) return null;
        if (ephemeral) {
            throw new CryptoException("decrypt unavailable: honcho.crypto-key not configured (ephemeral mode)");
        }
        try {
            var all = Base64.getDecoder().decode(base64Ciphertext.trim());
            if (all.length < IV_LEN + 16) {
                throw new CryptoException("ciphertext too short");
            }
            var iv = new byte[IV_LEN];
            System.arraycopy(all, 0, iv, 0, IV_LEN);
            var ct = new byte[all.length - IV_LEN];
            System.arraycopy(all, IV_LEN, ct, 0, ct.length);
            var cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            var pt = cipher.doFinal(ct);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (CryptoException e) {
            throw e;
        } catch (Exception e) {
            throw new CryptoException("decrypt failed", e);
        }
    }

    public boolean isEphemeral() {
        return ephemeral;
    }

    public static class CryptoException extends RuntimeException {
        public CryptoException(String message) { super(message); }
        public CryptoException(String message, Throwable cause) { super(message, cause); }
    }
}
