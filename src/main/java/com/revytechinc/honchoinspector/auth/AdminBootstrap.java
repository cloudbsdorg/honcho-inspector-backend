package com.revytechinc.honchoinspector.auth;

import com.revytechinc.honchoinspector.auth.repo.UserRepository;
import com.revytechinc.honchoinspector.config.HonchoConfigDirResolver;
import com.revytechinc.honchoinspector.config.HonchoProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.EnumSet;
import java.util.Set;

/**
 * First-startup bootstrap. If the database has no users AND the bootstrap
 * block is set, creates a single admin user with {@code isAdmin=true}.
 *
 * <p>The bootstrap block may be (a) fully populated by the operator in
 * {@code /etc/honcho-inspector/application.yml}, or (b) left blank — in
 * which case this listener synthesises sensible defaults:
 * <ul>
 *   <li>{@code adminUsername} defaults to {@code admin}.</li>
 *   <li>{@code adminPassword} defaults to a random 24-character
 *       alphanumeric password, printed once at startup and persisted to
 *       {@code <config-dir>/honcho.bootstrap.admin} (mode 0640 root:www-data).</li>
 * </ul>
 * Either way, on first boot with an empty DB the ephemeral crypto key is
 * persisted to {@code <config-dir>/honcho.crypto-key} (mode 0640 root:www-data)
 * so encrypted profile data survives a restart.
 *
 * <p>If users already exist, the bootstrap is a no-op (idempotent across
 * restarts). If only one of username/password is set, the bootstrap logs
 * a warning and does nothing.
 */
@Component
public class AdminBootstrap {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);
    private static final String ADMIN_FILE_NAME = "honcho.bootstrap.admin";
    private static final String PASSWORD_ALPHABET =
        "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";

    private final HonchoProperties properties;
    private final UserRepository users;
    private final AuthService auth;
    private final AdminAudit audit;
    private final CryptoService crypto;
    private final HonchoConfigDirResolver configDir;
    private final SecureRandom rng = new SecureRandom();

    public AdminBootstrap(
        HonchoProperties properties,
        UserRepository users,
        AuthService auth,
        AdminAudit audit,
        CryptoService crypto,
        HonchoConfigDirResolver configDir
    ) {
        this.properties = properties;
        this.users = users;
        this.auth = auth;
        this.audit = audit;
        this.crypto = crypto;
        this.configDir = configDir;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            bootstrap();
        } catch (RuntimeException e) {
            log.error("admin bootstrap failed", e);
        }
    }

    void bootstrap() {
        if (users.count() > 0) {
            log.debug("admin bootstrap skipped: users already exist");
            return;
        }
        var b = properties.bootstrap();

        String username;
        String password;
        String firstname;
        String lastname;
        String email;
        boolean generatedPassword;

        if (b == null) {
            username = null;
            password = null;
            firstname = null;
            lastname = null;
            email = null;
            generatedPassword = false;
        } else {
            username = isBlank(b.adminUsername()) ? "admin" : b.adminUsername();
            firstname = b.adminFirstname();
            lastname = b.adminLastname();
            email = b.adminEmail();
            if (isBlank(b.adminPassword())) {
                password = randomPassword(24);
                generatedPassword = true;
            } else {
                password = b.adminPassword();
                generatedPassword = false;
            }
        }

        if (password == null) {
            log.warn(
                "admin bootstrap skipped: database is empty but no bootstrap "
              + "configuration is available. Set honcho.bootstrap.admin-username "
              + "AND honcho.bootstrap.admin-password in "
              + "/etc/honcho-inspector/application.yml, or call "
              + "POST /api/setup/first-admin with a fresh browser, or use the "
              + "emergency CLI: honcho-inspector promote-to-admin --username NAME.");
            return;
        }

        try {
            if (crypto.isEphemeral()) {
                try {
                    crypto.persistKeyToFile(configDir);
                    crypto.reloadKeyFromFile(configDir);
                } catch (RuntimeException persistErr) {
                    log.warn(
                        "honcho.crypto-key: could not persist ephemeral key to "
                      + "<config-dir>/honcho.crypto-key: {}. Encrypted profile "
                      + "data WILL be lost on restart unless HONCHO_CRYPTO_KEY is "
                      + "set in the environment.",
                        persistErr.getMessage()
                    );
                }
            }
            if (generatedPassword) {
                var target = configDir.resolve().resolve(ADMIN_FILE_NAME);
                try {
                    persistGeneratedAdmin(target, username, password);
                    log.warn(
                        "================================================================\n"
                      + "  ADMIN BOOTSTRAP: generated admin user '{}' with a RANDOM password.\n"
                      + "  Username : {}\n"
                      + "  Password : {}\n"
                      + "  Saved to : {} (mode 0640)\n"
                      + "  Read it once, log in, then DELETE the file.\n"
                      + "================================================================",
                        username, username, password, target
                    );
                } catch (RuntimeException persistErr) {
                    log.warn(
                        "================================================================\n"
                      + "  ADMIN BOOTSTRAP: generated admin user '{}' with a RANDOM password.\n"
                      + "  Username : {}\n"
                      + "  Password : {}\n"
                      + "  SAVE THIS PASSWORD NOW — failed to write to {}: {}\n"
                      + "================================================================",
                        username, username, password, target, persistErr.getMessage()
                    );
                }
            }

            var user = auth.adminCreate(
                username, password,
                firstname, lastname, email,
                true
            );
            log.info(
                "admin bootstrap: created first admin user '{}' (id={}).",
                user.username(), user.id()
            );
            audit.record(null, "user.bootstrap", user.id(), null, null, null,
                java.util.Map.of(
                    "username", user.username(),
                    "generatedPassword", generatedPassword
                ));
        } catch (AuthService.UserExistsException e) {
            log.info("admin bootstrap: user already exists, skipping");
        }
    }

    private String randomPassword(int length) {
        var out = new char[length];
        for (int i = 0; i < length; i++) {
            out[i] = PASSWORD_ALPHABET.charAt(rng.nextInt(PASSWORD_ALPHABET.length()));
        }
        return new String(out);
    }

    private void persistGeneratedAdmin(Path target, String username, String password) {
        var body = "# honcho-inspector bootstrap admin credentials\n"
                 + "# Generated on first boot because honcho.bootstrap.* was unset.\n"
                 + "# DELETE this file after recording the password.\n"
                 + "username=" + username + "\n"
                 + "password=" + password + "\n";
        try {
            Files.writeString(target, body);
        } catch (java.io.IOException e) {
            throw new RuntimeException("failed to write " + target + ": " + e.getMessage(), e);
        }
        Set<PosixFilePermission> perms = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ
        );
        try {
            Files.setPosixFilePermissions(target, perms);
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX fs (Windows): best-effort.
        } catch (java.io.IOException e) {
            throw new RuntimeException("failed to chmod " + target + ": " + e.getMessage(), e);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
