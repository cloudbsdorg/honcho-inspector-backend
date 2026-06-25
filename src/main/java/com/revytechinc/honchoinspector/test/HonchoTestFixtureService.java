package com.revytechinc.honchoinspector.test;

import com.revytechinc.honchoinspector.config.HonchoConfigDirResolver;
import com.revytechinc.honchoinspector.honcho.HonchoCallException;
import com.revytechinc.honchoinspector.honcho.HonchoClientFactory;
import com.revytechinc.honchoinspector.honcho.HonchoOperation;
import com.revytechinc.honchoinspector.model.HonchoContext;
import com.revytechinc.honchoinspector.service.HonchoProxyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic Honcho fixture seed/teardown used by the Playwright
 * regression suite and by the admin "Seed / Clean" buttons in the
 * UI. Every created entity is tagged with the {@link #PREFIX} so
 * teardown can find them later and so a single workspace can
 * safely mix fixture entities with production data.
 *
 * <h2>What gets seeded</h2>
 * <ul>
 *   <li>{@code fixture-} prefixed peers (5): alice, bob, carol, dave, eve.</li>
 *   <li>{@code fixture-} prefixed sessions (3): one per pair of peers
 *       so the sessions tab has realistic peer groupings.</li>
 *   <li>One peer card per seeded peer (3 short fact strings each).</li>
 *   <li>One message per session (3 messages total) so the message
 *       view has data on first load.</li>
 *   <li>Conclusions are <em>derived</em> by the upstream Honcho from
 *       the messages, so we don't write them directly. The listPeers
 *       fan-out we already use will see them on the next probe.</li>
 * </ul>
 *
 * <h2>Cleanup</h2>
 * <p>Removes everything we created:
 * <ul>
 *   <li>Each fixture session via DELETE (real Honcho supports this).</li>
 *   <li>Fixture peers are <em>not</em> removed — Honcho v3 has no
 *       DELETE peer endpoint (returns 405). The next seed run
 *       re-uses the same peer IDs, so the workspace accumulates at
 *       most one canonical set of fixture peers indefinitely.</li>
 * </ul>
 */
@Service
public class HonchoTestFixtureService {

    public static final String PREFIX = "fixture-";
    private static final Logger log = LoggerFactory.getLogger(HonchoTestFixtureService.class);

    public static final String[] PEER_IDS = {
        PREFIX + "alice",
        PREFIX + "bob",
        PREFIX + "carol",
        PREFIX + "dave",
        PREFIX + "eve",
    };

    public static final String[] SESSION_IDS = {
        PREFIX + "alpha",
        PREFIX + "beta",
        PREFIX + "gamma",
    };

    public static final String[][] SESSION_PEERS = {
        { PEER_IDS[0], PEER_IDS[1] }, // alice ↔ bob
        { PEER_IDS[2], PEER_IDS[3] }, // carol ↔ dave
        { PEER_IDS[0], PEER_IDS[2], PEER_IDS[4] }, // alice ↔ carol ↔ eve
    };

    private final HonchoProxyService proxy;
    private final HonchoClientFactory clientFactory;
    private final HonchoConfigDirResolver configDir;

    public HonchoTestFixtureService(
        HonchoProxyService proxy,
        HonchoClientFactory clientFactory,
        HonchoConfigDirResolver configDir
    ) {
        this.proxy = proxy;
        this.clientFactory = clientFactory;
        this.configDir = configDir;
    }

    /**
     * Resolve a {@link HonchoContext} from the same env-block that
     * the rest of the app uses (config-dir + HONCHO_API_KEY /
     * HONCHO_BASE_URL / HONCHO_USER_NAME). Tests can construct a
     * fresh fixture without going through a stored profile.
     */
    public HonchoContext resolveContext() {
        var env = System.getenv();
        var baseUrl = firstNonBlank(env.get("HONCHO_BASE_URL"), "https://api.honcho.dev");
        var apiKey = firstNonBlank(env.get("HONCHO_API_KEY"), "");
        var workspaceId = firstNonBlank(env.get("HONCHO_WORKSPACE_ID"), "default");
        var userName = firstNonBlank(env.get("HONCHO_USER_NAME"), "admin");
        if (apiKey.isBlank()) {
            throw new IllegalStateException(
                "HONCHO_API_KEY must be set for test-fixture seeding (e.g. the JWT from ~/.config/opencode/opencode.json)."
            );
        }
        var apiVersion = clientFactory.resolveVersion(null,
            com.revytechinc.honchoinspector.honcho.HonchoApiVersion.fromString("v3"));
        return new HonchoContext(
            apiKey, baseUrl, workspaceId, userName, apiVersion, "test-fixture"
        );
    }

    /**
     * Seed the fixture set against the upstream Honcho defined by
     * {@link #resolveContext()}. Idempotent: re-creates sessions,
     * messages, and peer cards even if they already exist (Honcho
     * upserts on most write paths).
     */
    public Map<String, Object> seed() {
        var ctx = resolveContext();
        var report = new LinkedHashMap<String, Object>();

        // Peers (idempotent at Honcho: createPeer is upsert-ish; we
        // tolerate 409/422 to mean "already exists").
        for (var peerId : PEER_IDS) {
            report.put("peer:" + peerId, invoke(
                "createPeer",
                () -> proxy.call(HonchoOperation.CREATE_PEER, ctx,
                    Map.of("id", peerId, "metadata", Map.of("fixture", true)), null, null)
            ));
        }

        // Peer cards (3 short fact strings each). Real Honcho wraps the
        // facts in {"peer_card": [...]} so the provider parses them
        // into PeerCardDocument rather than treating them as raw facts.
        for (var peerId : PEER_IDS) {
            final var pid = peerId;
            report.put("card:" + pid, invoke(
                "updatePeerCard",
                () -> proxy.call(HonchoOperation.UPDATE_PEER_CARD, ctx,
                    Map.of("peer_card", List.of(
                        pid + " is a test peer.",
                        pid + " was created by HonchoTestFixtureService.",
                        "fixture metadata: source=honcho-inspector-regression")),
                    Map.of("peerId", pid), null)
            ));
        }

        // Sessions.
        for (int i = 0; i < SESSION_IDS.length; i++) {
            var sid = SESSION_IDS[i];
            var peerIds = SESSION_PEERS[i];
            report.put("session:" + sid, invoke(
                "createSession",
                () -> proxy.call(HonchoOperation.CREATE_SESSION, ctx,
                    Map.of("id", sid, "metadata", Map.of("fixture", true, "peerIds", List.of(peerIds))),
                    null, null)
            ));
        }

        // One message per session. Real Honcho expects the body to be
        // {"messages": [{peer_id, content}, ...]} on
        // POST /v3/workspaces/{ws}/sessions/{sid}/messages.
        var messages = new String[]{
            "Hello from the regression suite.",
            "Adding a second message so the message pane has scrolling room.",
            "Final message in this session.",
        };
        for (int i = 0; i < SESSION_IDS.length; i++) {
            final var sid = SESSION_IDS[i];
            final var peerIds = SESSION_PEERS[i];
            // First peer authors; everyone else gets a copy.
            final var author = peerIds[0];
            final var msg = messages[i];
            report.put("message:" + sid + ":author", invoke(
                "addMessage",
                () -> proxy.call(HonchoOperation.ADD_MESSAGE, ctx,
                    Map.of("messages", List.of(Map.of(
                        "peer_id", author,
                        "content", msg))),
                    Map.of("sessionId", sid), null)
            ));
        }
        log.info("test-fixture seed complete: {}", report.keySet());
        return report;
    }

    /**
     * Best-effort cleanup. Deletes the fixture sessions (Honcho
     * supports DELETE). Fixture peers are left in place — see the
     * class Javadoc for why.
     */
    public Map<String, Object> cleanup() {
        var ctx = resolveContext();
        var report = new LinkedHashMap<String, Object>();
        for (var sid : SESSION_IDS) {
            report.put("session:" + sid, invoke(
                "deleteSession",
                () -> proxy.call(HonchoOperation.DELETE_SESSION, ctx,
                    null, Map.of("sessionId", sid), null)
            ));
        }
        log.info("test-fixture cleanup complete: {}", report.keySet());
        return report;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    /**
     * Run a single Honcho call and record a per-op status. Tolerates
     * 4xx as "already exists" so the seed is idempotent against a
     * workspace that already has the fixture entities.
     */
    private Object invoke(String label, java.util.function.Supplier<Object> op) {
        try {
            var result = op.get();
            return Map.of("ok", true, "result", String.valueOf(result));
        } catch (HonchoCallException e) {
            // 4xx means "already there" for upsert-shaped endpoints
            // (createPeer, createSession, addMessage). Surface as ok
            // but note the existing status. 5xx is a real failure.
            if (e.status() >= 400 && e.status() < 500) {
                return Map.of("ok", true, "existed", true, "status", e.status(), "body", e.body());
            }
            log.error("fixture {} failed: {}", label, e.getMessage());
            return Map.of("ok", false, "status", e.status(), "body", e.body());
        } catch (RuntimeException e) {
            log.error("fixture {} transport failure: {}", label, e.getMessage());
            return Map.of("ok", false, "error", e.getMessage());
        }
    }
}
