package com.revytechinc.honchoinspector.auth;

import com.revytechinc.honchoinspector.config.HonchoProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * First-startup bootstrap. If the database has no users AND both
 * {@code honcho.bootstrap.admin-username} and {@code honcho.bootstrap.admin-password}
 * are set in the resolved configuration (typically {@code /etc/honcho-inspector/application.yml}),
 * creates a single admin user with {@code isAdmin=true}. The bundled
 * {@code application.yml} ships these blank; production deployments opt in
 * by populating them in the drop-in config file.
 *
 * <p>If users already exist, the bootstrap is a no-op (idempotent across
 * restarts). If only one of username/password is set, the bootstrap logs
 * a warning and does nothing (caller must fix the config).
 */
@Component
public class AdminBootstrap {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final HonchoProperties properties;
    private final UserDao users;
    private final AuthService auth;
    private final AdminAudit audit;

    public AdminBootstrap(
        HonchoProperties properties,
        UserDao users,
        AuthService auth,
        AdminAudit audit
    ) {
        this.properties = properties;
        this.users = users;
        this.auth = auth;
        this.audit = audit;
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
        if (b == null) {
            log.debug("admin bootstrap skipped: no bootstrap config");
            return;
        }
        if (isBlank(b.adminUsername()) || isBlank(b.adminPassword())) {
            log.warn(
                "admin bootstrap skipped: database is empty but honcho.bootstrap.* "
              + "is not fully configured. Set honcho.bootstrap.admin-username AND "
              + "honcho.bootstrap.admin-password in /etc/honcho-inspector/application.yml "
              + "to create the first admin, or call POST /api/admin/users with an "
              + "authenticated admin session.");
            return;
        }
        try {
            var user = auth.adminCreate(
                b.adminUsername(), b.adminPassword(),
                b.adminFirstname(), b.adminLastname(), b.adminEmail(),
                true);
            log.info(
                "admin bootstrap: created first admin user '{}' (id={}). "
              + "For security, REMOVE the bootstrap credentials from "
              + "/etc/honcho-inspector/application.yml now that the admin exists.",
                user.username(), user.id());
            audit.record(null, "user.bootstrap", user.id(), null, null, null,
                java.util.Map.of("username", user.username()));
        } catch (AuthService.UserExistsException e) {
            log.info("admin bootstrap: user already exists, skipping");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
