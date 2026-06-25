package com.revytechinc.honchoinspector.test;

import com.revytechinc.honchoinspector.auth.AdminAudit;
import com.revytechinc.honchoinspector.auth.RequireAdmin;
import com.revytechinc.honchoinspector.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Admin-only test-fixture endpoints. Seeds and tears down a
 * deterministic set of Honcho entities (peers, sessions, messages,
 * peer cards) under the {@link HonchoTestFixtureService#PREFIX}
 * namespace so the Playwright regression suite has stable data to
 * render against, regardless of what else happens to be in the
 * workspace.
 *
 * <p>Honcho credentials come from the
 * {@code HONCHO_API_KEY} / {@code HONCHO_BASE_URL} /
 * {@code HONCHO_WORKSPACE_ID} / {@code HONCHO_USER_NAME}
 * environment variables — the same set the backend itself reads
 * when no profile-specific override is set. This keeps the test
 * fixtures independent of any operator-managed profile.
 *
 * <p>Both endpoints record an {@code audit_log} entry with action
 * {@code test.fixture.seed} or {@code test.fixture.cleanup} so the
 * audit dashboard can show what the operator did.
 */
@RestController
@RequestMapping("/api/admin/test")
@RequireAdmin
@Tag(name = OpenApiConfig.TAG_ADMIN,
    description = "Admin-only test-fixture management: deterministic seed/cleanup for the regression suite.")
public class AdminTestFixtureController {

    private final HonchoTestFixtureService fixtures;
    private final AdminAudit adminAudit;

    public AdminTestFixtureController(HonchoTestFixtureService fixtures, AdminAudit adminAudit) {
        this.fixtures = fixtures;
        this.adminAudit = adminAudit;
    }

    @PostMapping("/seed")
    @Operation(summary = "Seed the regression test fixture (peers, sessions, messages, peer cards)")
    public ResponseEntity<Map<String, Object>> seed() {
        try {
            var report = fixtures.seed();
            adminAudit.record(null, "test.fixture.seed", null, null, null, null, report);
            return ResponseEntity.ok(envelope("seeded", report));
        } catch (RuntimeException e) {
            return ResponseEntity.status(500).body(envelope("seed failed: " + e.getMessage(), Map.of()));
        }
    }

    @DeleteMapping("/seed")
    @Operation(summary = "Tear down the regression test fixture (sessions only — peers are not deletable via Honcho v3)")
    public ResponseEntity<Map<String, Object>> cleanup() {
        try {
            var report = fixtures.cleanup();
            adminAudit.record(null, "test.fixture.cleanup", null, null, null, null, report);
            return ResponseEntity.ok(envelope("cleaned", report));
        } catch (RuntimeException e) {
            return ResponseEntity.status(500).body(envelope("cleanup failed: " + e.getMessage(), Map.of()));
        }
    }

    private static Map<String, Object> envelope(String status, Map<String, Object> report) {
        var out = new LinkedHashMap<String, Object>();
        out.put("status", status);
        out.put("report", report);
        return out;
    }
}
