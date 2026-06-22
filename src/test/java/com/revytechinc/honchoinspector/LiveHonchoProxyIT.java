package com.revytechinc.honchoinspector;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live, environment-gated Honcho proxy integration test.
 *
 * <p><b>Gating:</b> This test is auto-skipped unless both
 * {@code HONCHO_LIVE_TEST=1} <em>and</em> {@code HONCHO_LIVE_WORKSPACE_ID=inspector-tests}
 * are set in the environment (see the two {@link EnabledIfEnvironmentVariable}
 * annotations on the class). Additionally, three connection details must be
 * present at setup time:
 * <ul>
 *   <li>{@code HONCHO_LIVE_URL} &mdash; base URL of the live Honcho
 *       (e.g. {@code https://honcho.cloudbsd.org}).</li>
 *   <li>{@code HONCHO_LIVE_API_KEY} &mdash; API key for the live Honcho.</li>
 *   <li>{@code HONCHO_LIVE_WORKSPACE_ID} &mdash; workspace id to target.</li>
 * </ul>
 *
 * <p><b>Default {@code mvn test} skips this.</b> The {@code @Tag("live")} tag
 * makes it selectable via {@code -Dgroups=live} on Surefire. The class name
 * uses the {@code IT} suffix so it sits outside Surefire's default include
 * pattern ({@code **\/*Test.java}) &mdash; which means the default run
 * silently excludes it entirely, and a manual run needs either
 * {@code -Dtest=LiveHonchoProxyIT} (overrides the include pattern) or the
 * Failsafe plugin ({@code mvn verify -Dgroups=live}).
 *
 * <p><b>Manual invocation:</b>
 * <pre>
 *   HONCHO_LIVE_TEST=1 HONCHO_LIVE_WORKSPACE_ID=inspector-tests mvn -Dgroups=live -Dtest=LiveHonchoProxyIT test
 * </pre>
 *
 * <p><b>What it touches:</b> A unique peer id {@code live-it-&lt;uuid&gt;} is
 * created on the live workspace during {@link #createPeer_persistsAndAppearsInList}.
 * It is <em>not</em> deleted (Honcho has no DELETE-peer endpoint in v3);
 * the tearDown log records the id so an operator can scrub it manually if
 * needed. Use a dedicated, disposable workspace (e.g. {@code inspector-tests})
 * to avoid polluting shared state.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "HONCHO_LIVE_TEST", matches = "1")
@EnabledIfEnvironmentVariable(named = "HONCHO_LIVE_WORKSPACE_ID", matches = "inspector-tests")
class LiveHonchoProxyIT {

    private static final String ENV_URL = "HONCHO_LIVE_URL";
    private static final String ENV_KEY = "HONCHO_LIVE_API_KEY";
    private static final String ENV_WS  = "HONCHO_LIVE_WORKSPACE_ID";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static String baseUrl;
    private static String apiKey;
    private static String workspaceId;
    private static HttpClient http;

    /** Unique id for the peer created by {@link #createPeer_persistsAndAppearsInList}. */
    private static String uniquePeerId;

    @BeforeAll
    static void setUp() {
        // Fail fast on missing connection details — better to throw at setup than
        // mid-test with an opaque NPE from java.net.http.
        baseUrl     = requireEnv(ENV_URL,     "live Honcho base URL");
        apiKey      = requireEnv(ENV_KEY,     "live Honcho API key");
        workspaceId = requireEnv(ENV_WS,      "live Honcho workspace id");
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

        uniquePeerId = "live-it-" + UUID.randomUUID();

        // Live tests touch real Honcho — clean up after yourself.
        System.err.println("[LiveHonchoProxyIT] LIVE RUN against " + baseUrl
            + " workspace=" + workspaceId
            + " uniquePeerId=" + uniquePeerId
            + " — remember to scrub " + uniquePeerId + " from the workspace afterwards");
    }

    @AfterAll
    static void tearDown() {
        System.err.println("[LiveHonchoProxyIT] Done. Peer " + uniquePeerId
            + " was created on workspace " + workspaceId + " at " + baseUrl
            + " — clean it up if it persists.");
    }

    // ------------------------------------------------------------------
    // The three operations per plan §26 (lines 2666–2675)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET /v3/workspaces/{ws}/queue/status → 200 and body is a queue snapshot")
    void getWorkspace_returns200WithQueueSnapshot() throws Exception {
        // Honcho v3 does not expose a GET /v3/workspaces/{id} endpoint (the path
        // only accepts PUT and DELETE). The WorkspaceProviderV3 connectivity
        // probe therefore hits the queue-status endpoint, which is GET,
        // requires no body, and returns real workspace-scoped data.
        HttpResponse<String> resp = send("GET", "/v3/workspaces/" + workspaceId + "/queue/status", null);

        assertThat(resp.statusCode())
            .as("GET /v3/workspaces/{ws}/queue/status must return 200 (Honcho reachable + key valid)")
            .isEqualTo(200);

        JsonNode body = JSON.readTree(resp.body());
        assertThat(body.has("total_work_units"))
            .as("queue snapshot body must include a total_work_units field")
            .isTrue();
    }

    @Test
    @DisplayName("List-peers endpoint is reachable (GET /peers or POST /peers/list fallback)")
    void listPeers_endpointIsReachable() throws Exception {
        // Plan §26 mentions GET /v3/workspaces/{ws}/peers. Honcho v3 actually
        // exposes list-peers as POST /v3/workspaces/{ws}/peers/list (v2 was GET).
        // Try GET first per the plan; if Honcho returns 404/405 (the typical v3
        // response), fall back to POST /peers/list. Either way we accept 200
        // with a JSON array.
        HttpResponse<String> resp = send("GET", "/v3/workspaces/" + workspaceId + "/peers", null);

        if (resp.statusCode() == 200) {
            JsonNode body = JSON.readTree(resp.body());
            assertThat(body.isArray())
                .as("GET /v3/workspaces/{ws}/peers 200 body must be a JSON array")
                .isTrue();
            System.err.println("[LiveHonchoProxyIT] GET /peers returned " + body.size() + " peer(s)");
            return;
        }

        if (resp.statusCode() == 404 || resp.statusCode() == 405) {
            System.err.println("[LiveHonchoProxyIT] GET /peers returned " + resp.statusCode()
                + " — Honcho v3 uses POST /peers/list, falling back");
            HttpResponse<String> postResp = send(
                "POST",
                "/v3/workspaces/" + workspaceId + "/peers/list",
                "{}"
            );
            assertThat(postResp.statusCode())
                .as("POST /v3/workspaces/{ws}/peers/list (v3 list-peers) must return 200")
                .isEqualTo(200);
            JsonNode body = JSON.readTree(postResp.body());
            JsonNode items = body.isArray() ? body : body.path("items");
            assertThat(items.isArray())
                .as("POST list-peers body must be (or contain) a JSON array of peers")
                .isTrue();
            System.err.println("[LiveHonchoProxyIT] POST /peers/list returned " + items.size() + " peer(s)");
            return;
        }

        throw new AssertionError(
            "Unexpected status " + resp.statusCode() + " from GET /peers: " + resp.body()
        );
    }

    @Test
    @DisplayName("POST /v3/workspaces/{ws}/peers → unique peer appears in subsequent list")
    void createPeer_persistsAndAppearsInList() throws Exception {
        // Step 1: create the peer with a UUID-suffixed id so reruns don't collide.
        String createBody = "{\"id\":\"" + uniquePeerId
            + "\",\"name\":\"LiveHonchoProxyIT probe\"}";
        HttpResponse<String> createResp = send(
            "POST",
            "/v3/workspaces/" + workspaceId + "/peers",
            createBody
        );

        assertThat(createResp.statusCode())
            .as("POST /v3/workspaces/{ws}/peers must return 2xx for unique peer creation")
            .isBetween(200, 299);

        // Step 2: re-list peers (GET first, then POST fallback per the list test)
        // and verify the new peer id appears in the response.
        HttpResponse<String> listResp = send(
            "GET",
            "/v3/workspaces/" + workspaceId + "/peers",
            null
        );
        boolean found;
        if (listResp.statusCode() == 200) {
            found = peerIdPresentIn(JSON.readTree(listResp.body()), uniquePeerId);
        } else {
            // Fall back to the v3 list endpoint and search there.
            HttpResponse<String> postList = send(
                "POST",
                "/v3/workspaces/" + workspaceId + "/peers/list",
                "{}"
            );
            assertThat(postList.statusCode())
                .as("POST /v3/workspaces/{ws}/peers/list fallback must return 200")
                .isEqualTo(200);
            found = peerIdPresentIn(JSON.readTree(postList.body()), uniquePeerId);
        }

        assertThat(found)
            .as("newly created peer '" + uniquePeerId + "' must appear in subsequent list-peers response")
            .isTrue();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * Walk a list-peers response (either a bare JSON array or an object with
     * an {@code items} array) and return {@code true} iff any element's
     * {@code id} / {@code peer_id} / {@code name} matches {@code peerId}.
     */
    private static boolean peerIdPresentIn(JsonNode listBody, String peerId) {
        JsonNode items = listBody.isArray() ? listBody : listBody.path("items");
        if (!items.isArray()) {
            return false;
        }
        for (JsonNode peer : items) {
            String id   = peer.path("id").asText(null);
            String pid  = peer.path("peer_id").asText(null);
            String name = peer.path("name").asText(null);
            if (peerId.equals(id) || peerId.equals(pid) || peerId.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static String requireEnv(String name, String humanDescription) {
        String value = System.getenv(name);
        assertThat(value)
            .as("live test requires env var " + name + " (" + humanDescription + ")")
            .isNotNull();
        assertThat(value)
            .as("live test env var " + name + " must not be blank")
            .isNotBlank();
        return value;
    }

    private static HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer " + apiKey)
            // Honcho expects X-Honcho-User-Name on every call (see
            // V3ProviderSupport.applyAuth in main code) — supply a probe value.
            .header("X-Honcho-User-Name", "live-it-probe")
            .header("Accept", "application/json");

        if (body != null) {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }

        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}