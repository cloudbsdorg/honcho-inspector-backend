package com.revytechinc.honchoinspector.auth;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

/**
 * Fire-and-forget audit log writer. Every admin write path and every
 * user-management mutation calls {@link #record(String, String, String, String, String, String, Map)}
 * which inserts a row into {@code audit_log}. Errors are logged at WARN
 * and swallowed so a broken audit table never breaks the calling write path.
 */
@Component
public class AdminAudit {

    private static final Logger log = LoggerFactory.getLogger(AdminAudit.class);
    private static final SecureRandom RNG = new SecureRandom();
    private final AuditLogDao dao;
    private final ObjectMapper json;

    public AdminAudit(AuditLogDao dao, ObjectMapper json) {
        this.dao = dao;
        this.json = json;
    }

    public void record(
        String actorUserId,
        String action,
        String targetUserId,
        String targetResource,
        String ip,
        String sessionId,
        Map<String, ?> metadata
    ) {
        try {
            dao.insert(new AuditLogDao.Entry(
                newId(),
                actorUserId,
                action,
                targetUserId,
                targetResource,
                ip,
                sessionId,
                metadata == null || metadata.isEmpty() ? null : json.writeValueAsString(metadata),
                Instant.now()
            ));
        } catch (JacksonException e) {
            log.warn("audit: failed to serialize metadata for action={} actor={}", action, actorUserId, e);
        } catch (RuntimeException e) {
            log.warn("audit: failed to write entry action={} actor={}", action, actorUserId, e);
        }
    }

    private static String newId() {
        var bytes = new byte[16];
        RNG.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
