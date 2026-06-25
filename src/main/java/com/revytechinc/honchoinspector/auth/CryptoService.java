package com.revytechinc.honchoinspector.auth;

import com.revytechinc.honchoinspector.config.HonchoConfigDirResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;

/**
 * AES-256-GCM encryption for Honcho API keys at rest.
 *
 * <p>Key source, in priority order:
 * <ol>
 *   <li>{@code HONCHO_CRYPTO_KEY} env var or {@code honcho.crypto-key} property,
 *       base64-encoded 32 bytes.</li>
 *   <li>{@code <config-dir>/honcho.crypto-key} file (auto-created on first
 *       boot if absent and the database is empty).</li>
 *   <li>Ephemeral random key (logs a loud warning; encrypted values are
 *       unreadable across restarts).</li>
 * </ol>
 *
 * <p>Generate one explicitly with {@code openssl rand -base64 32}.
 *
 * <p>Wire format: base64( IV(12) || ciphertext || GCM_tag(16) ).
 */
@Component
public class CryptoService {

    private static final Logger log = LoggerFactory.getLogger(CryptoService.class);
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final String KEY_FILE_NAME = "honcho.crypto-key";

    private volatile SecretKey key;
    private final SecureRandom rng = new SecureRandom();
    private volatile boolean ephemeral;

    public CryptoService(
        @Value("${honcho.crypto-key:}") String base64Key,
        HonchoConfigDirResolver configDir
    ) {
        if (base64Key != null && !base64Key.isBlank()) {
            this.key = new SecretKeySpec(decodeKey(base64Key), "AES");
            this.ephemeral = false;
            log.info("honcho.crypto-key: loaded from env/property");
            return;
        }
        var keyFile = configDir.resolve().resolve(KEY_FILE_NAME);
        if (Files.exists(keyFile)) {
            try {
                var stored = Files.readString(keyFile).trim();
                this.key = new SecretKeySpec(decodeKey(stored), "AES");
                this.ephemeral = false;
                log.info("honcho.crypto-key: loaded from {}", keyFile);
                return;
            } catch (Exception e) {
                throw new IllegalStateException(
                    "honcho.crypto-key file at " + keyFile + " is unreadable or invalid: " + e.getMessage(), e);
            }
        }
        var bytes = new byte[32];
        rng.nextBytes(bytes);
        this.key = new SecretKeySpec(bytes, "AES");
        this.ephemeral = true;
        log.warn(
            "honcho.crypto-key not configured — using an ephemeral random key. "
          + "Encrypted values will NOT survive a restart. "
          + "Set HONCHO_CRYPTO_KEY in the environment, or write the auto-generated "
          + "key file at {} to a secret manager to make encryption persistent.",
            keyFile
        );
    }

    private static byte[] decodeKey(String base64Key) {
        var bytes = Base64.getDecoder().decode(base64Key.trim());
        if (bytes.length != 32) {
            throw new IllegalStateException("honcho.crypto-key must decode to 32 bytes, got " + bytes.length);
        }
        return bytes;
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

    /**
     * Replace the in-memory key with one loaded from the key file at
     * {@code <config-dir>/honcho.crypto-key}. Throws if the file does
     * not exist, cannot be read, or does not decode to 32 bytes. Used
     * by {@link AdminBootstrap} after writing the file on first boot so
     * subsequent encrypt/decrypt calls in the same JVM use the
     * persisted key (matching what future restarts will load).
     */
    public void reloadKeyFromFile(HonchoConfigDirResolver configDir) {
        var keyFile = configDir.resolve().resolve(KEY_FILE_NAME);
        if (!Files.exists(keyFile)) {
            throw new IllegalStateException("honcho.crypto-key file missing at " + keyFile);
        }
        try {
            var stored = Files.readString(keyFile).trim();
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(stored);
            } catch (IllegalArgumentException badBase64) {
                throw new IllegalStateException("honcho.crypto-key file at " + keyFile + " is not valid base64: " + badBase64.getMessage(), badBase64);
            }
            if (bytes.length != 32) {
                throw new IllegalStateException("honcho.crypto-key file at " + keyFile + " must decode to 32 bytes, got " + bytes.length);
            }
            this.key = new SecretKeySpec(bytes, "AES");
            this.ephemeral = false;
            log.info("honcho.crypto-key: reloaded in-memory key from {}", keyFile);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("honcho.crypto-key: failed to read " + keyFile + ": " + e.getMessage(), e);
        }
    }

    /**
     * Persist the current key to {@code <config-dir>/honcho.crypto-key} so
     * encrypted values survive a restart. No-op if the key was loaded from
     * env or property (env always wins), or if a key file already exists.
     * Throws if the file cannot be written.
     *
     * <p>Intended to be called from {@link AdminBootstrap} on first boot
     * (when the database is empty and the operator hasn't configured a key).
     */
    public void persistKeyToFile(HonchoConfigDirResolver configDir) {
        var encoded = Base64.getEncoder().encodeToString(key.getEncoded());
        var target = configDir.resolve().resolve(KEY_FILE_NAME);
        try {
            Files.writeString(target, encoded);
            Set<PosixFilePermission> perms = EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ
            );
            try {
                Files.setPosixFilePermissions(target, perms);
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX fs (Windows): best-effort.
            }
            log.info("honcho.crypto-key: persisted ephemeral key to {}", target);
        } catch (Exception e) {
            throw new IllegalStateException(
                "honcho.crypto-key: failed to persist ephemeral key to " + target + ": " + e.getMessage(), e);
        }
    }

    public static class CryptoException extends RuntimeException {
        public CryptoException(String message) { super(message); }
        public CryptoException(String message, Throwable cause) { super(message, cause); }
    }
}
