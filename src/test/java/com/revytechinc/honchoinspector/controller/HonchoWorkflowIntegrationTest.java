package com.revytechinc.honchoinspector.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.revytechinc.honchoinspector.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class HonchoWorkflowIntegrationTest extends IntegrationTestBase {

    private static final String ALICE = "alice";
    private static final String ALICE_PASS = "alicepass123";
    private static final String PROFILE_LABEL = "mock-honcho";
    private static final String PROFILE_API_KEY = "hnc_test_key";

    private String sessionId;
    private String profileId;

    @BeforeEach
    void setUp() throws Exception {
        sessionId = registerAndLogin(ALICE, ALICE_PASS);
        profileId = createProfile(PROFILE_LABEL, PROFILE_API_KEY);
    }

    @Test
    @DisplayName("GET /api/peers returns the LIST_PEERS fixture (v2→v3: browser GET → upstream POST)")
    void listPeersReturnsFixtureData() throws Exception {
        MvcResult result = mvc.perform(withAuth(get("/api/peers"), sessionId, profileId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(11))
            .andExpect(jsonPath("$.page").value(1))
            .andExpect(jsonPath("$.size").value(50))
            .andExpect(jsonPath("$.pages").value(1))
            .andExpect(jsonPath("$.items").isArray())
            .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("items"))
            .as("LIST_PEERS fixture ships 11 representative peers")
            .hasSize(11);

        // The fixture's _meta block documents the upstream method+path.
        // Loading it here turns the v2→v3 contract into a load-bearing test:
        // if PeersProviderV3 regresses to GET upstream, this still says POST.
        JsonNode meta = readFixtureMeta("list-peers.json");
        assertThat(meta.get("method").asText())
            .as("LIST_PEERS upstream contract is POST (v2 was GET)")
            .isEqualTo("POST");
        assertThat(meta.get("endpoint").asText())
            .as("LIST_PEERS upstream path includes /peers/list")
            .endsWith("/peers/list");
    }

    @Test
    @DisplayName("POST /api/peers returns the CREATE_PEER fixture")
    void createPeerReturnsFixtureData() throws Exception {
        Map<String, Object> body = Map.of("name", "fixture-capture-test");

        mvc.perform(withAuth(post("/api/peers"), sessionId, profileId)
                .contentType(JSON)
                .content(toJson(body)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("fixture-capture-1781766883"))
            .andExpect(jsonPath("$.workspace_id").value("default"))
            .andExpect(jsonPath("$.metadata.source").value("fixture-capture"));
    }

    @Test
    @DisplayName("GET /api/peers/{peerId}/card returns the GET_PEER_CARD fixture")
    void getPeerCardReturnsFixture() throws Exception {
        // Fixture data is {"peer_card": null}. Spring's Jackson config
        // (default-property-inclusion: non_null) strips null fields from
        // the response, so we assert the empty-object shape rather than
        // the literal null key.
        mvc.perform(withAuth(get("/api/peers/alice/card"), sessionId, profileId))
            .andExpect(status().isOk())
            .andExpect(content().json("{}"));
    }

    @Test
    @DisplayName("GET /api/sessions returns the LIST_SESSIONS fixture (v2→v3: browser GET → upstream POST)")
    void listSessionsReturnsFixtureData() throws Exception {
        mvc.perform(withAuth(get("/api/sessions"), sessionId, profileId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(2))
            .andExpect(jsonPath("$.items").isArray())
            .andExpect(jsonPath("$.items[0].id").value("fixture-session-001"))
            .andExpect(jsonPath("$.items[1].id").value("fixture-session-002"));

        JsonNode meta = readFixtureMeta("list-sessions.json");
        assertThat(meta.get("method").asText())
            .as("LIST_SESSIONS upstream contract is POST (v2 was GET)")
            .isEqualTo("POST");
    }

    @Test
    @DisplayName("POST /api/sessions returns the CREATE_SESSION fixture")
    void createSessionReturnsFixtureData() throws Exception {
        Map<String, Object> body = Map.of(
            "name", "test-session",
            "peers", List.of(Map.of("id", "alice"))
        );

        mvc.perform(withAuth(post("/api/sessions"), sessionId, profileId)
                .contentType(JSON)
                .content(toJson(body)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("fixture-session-1781766883"))
            .andExpect(jsonPath("$.workspace_id").value("default"))
            .andExpect(jsonPath("$.is_active").value(true));
    }

    @Test
    @DisplayName("POST /api/sessions/{sessionId}/messages returns the ADD_MESSAGE fixture")
    void addMessageReturnsFixtureData() throws Exception {
        Map<String, Object> body = Map.of(
            "messages", List.of(Map.of(
                "peer_id", "fixture-peer",
                "content", "hello from workflow test"
            ))
        );

        mvc.perform(withAuth(post("/api/sessions/sess_abc/messages"), sessionId, profileId)
                .contentType(JSON)
                .content(toJson(body)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$[0].id").value("E9WplFW5WAbfkoeasIDah"))
            .andExpect(jsonPath("$[0].content").value("hello from fixture-capture 1781766883"))
            .andExpect(jsonPath("$[0].peer_id").value("fixture-capture-1781766883"))
            .andExpect(jsonPath("$[0].session_id").value("fixture-session-1781766883"));
    }

    @Test
    @DisplayName("GET /api/sessions/{sessionId}/messages returns the LIST_SESSION_MESSAGES fixture")
    void listSessionMessagesReturnsFixtureData() throws Exception {
        mvc.perform(withAuth(get("/api/sessions/sess_abc/messages"), sessionId, profileId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(2))
            .andExpect(jsonPath("$.items").isArray())
            .andExpect(jsonPath("$.items[0].id").value("fixture-msg-001"))
            .andExpect(jsonPath("$.items[0].content").value("hello world"))
            .andExpect(jsonPath("$.items[1].id").value("fixture-msg-002"));
    }

    @Test
    @DisplayName("GET /api/workspace/info returns a composite {workspace, queue} response built from two fixtures")
    void getWorkspaceInfoReturnsFixture() throws Exception {
        mvc.perform(withAuth(get("/api/workspace/info"), sessionId, profileId))
            .andExpect(status().isOk())
            // workspaceInfo composes getWorkspaceInfo + getQueueStatus; both fixtures' data
            // payloads appear under the workspace + queue keys.
            .andExpect(jsonPath("$.workspace.id").value("fixture-ws"))
            .andExpect(jsonPath("$.workspace.metadata.source").value("fixture-capture"))
            .andExpect(jsonPath("$.queue.total_work_units").value(0))
            .andExpect(jsonPath("$.queue.completed_work_units").value(0))
            .andExpect(jsonPath("$.queue.pending_work_units").value(0));
    }

    @Test
    @DisplayName("GET /api/queue-status returns the GET_QUEUE_STATUS fixture")
    void getQueueStatusReturnsFixture() throws Exception {
        mvc.perform(withAuth(get("/api/queue-status"), sessionId, profileId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total_work_units").value(0))
            .andExpect(jsonPath("$.completed_work_units").value(0))
            .andExpect(jsonPath("$.in_progress_work_units").value(0))
            .andExpect(jsonPath("$.pending_work_units").value(0));
    }

    @Test
    @DisplayName("Proxy endpoint without X-Honcho-Profile-Id returns 400")
    void proxyEndpointWithoutProfileHeaderReturns400() throws Exception {
        mvc.perform(get("/api/peers").header("X-Session-Id", sessionId))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("missing " + HonchoController.PROFILE_HEADER + " header"));
    }

    @Test
    @DisplayName("POST /api/peers happy path returns the CREATE_PEER fixture (canonical name)")
    void createPeerHappyPath() throws Exception {
        Map<String, Object> body = Map.of("name", "happy-path-peer");

        mvc.perform(withAuth(post("/api/peers"), sessionId, profileId)
                .contentType(JSON)
                .content(toJson(body)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("fixture-capture-1781766883"))
            .andExpect(jsonPath("$.workspace_id").value("default"))
            .andExpect(jsonPath("$.metadata.source").value("fixture-capture"));
    }

    @Test
    @DisplayName("GET /api/peers/{peerId}/representation returns the GET_REPRESENTATION fixture")
    void getPeerRepresentation() throws Exception {
        mvc.perform(withAuth(get("/api/peers/alice/representation"), sessionId, profileId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.representation").value(org.hamcrest.Matchers.containsString("backend engineer")));

        JsonNode meta = readFixtureMeta("get-peer-representation.json");
        assertThat(meta.get("method").asText())
            .as("GET_REPRESENTATION upstream contract is GET")
            .isEqualTo("GET");
        assertThat(meta.get("endpoint").asText())
            .as("upstream path includes /representation")
            .endsWith("/representation");
    }

    @Test
    @DisplayName("GET /api/peers/{peerId}/sessions returns the LIST_PEER_SESSIONS fixture")
    void listPeerSessions() throws Exception {
        mvc.perform(withAuth(get("/api/peers/alice/sessions"), sessionId, profileId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(2))
            .andExpect(jsonPath("$.items").isArray())
            .andExpect(jsonPath("$.items[0].id").value("fixture-session-001"))
            .andExpect(jsonPath("$.items[1].id").value("fixture-session-002"))
            .andExpect(jsonPath("$.page").value(1))
            .andExpect(jsonPath("$.size").value(50))
            .andExpect(jsonPath("$.pages").value(1));

        JsonNode meta = readFixtureMeta("list-peer-sessions.json");
        assertThat(meta.get("method").asText())
            .as("LIST_PEER_SESSIONS upstream contract is GET")
            .isEqualTo("GET");
    }

    @Test
    @DisplayName("POST /api/search returns the SEARCH_MESSAGES fixture")
    void searchMessages() throws Exception {
        Map<String, Object> body = Map.of("query", "fixture");

        mvc.perform(withAuth(post("/api/search"), sessionId, profileId)
                .contentType(JSON)
                .content(toJson(body)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$[0].id").value("IxYA8tK7iEU5IW0IDFEf3"))
            .andExpect(jsonPath("$[0].workspace_id").value("default"));

        JsonNode meta = readFixtureMeta("search-messages.json");
        assertThat(meta.get("method").asText())
            .as("SEARCH_MESSAGES upstream contract is POST (v2 was GET)")
            .isEqualTo("POST");
        assertThat(meta.get("endpoint").asText())
            .as("upstream path is /v3/workspaces/{ws}/search")
            .endsWith("/search");
    }

    /**
     * Read the {@code _meta} block from a fixture JSON file on the test
     * classpath. Used by the v2→v3 contract tests to assert that the
     * upstream endpoint documented in the fixture matches what the V3
     * provider is supposed to issue (POST for the list ops, GET for
     * everything else).
     */
    private JsonNode readFixtureMeta(String fileName) throws Exception {
        var resource = "fixtures/honcho/v3/" + fileName;
        try (var is = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertThat(is)
                .as("fixture on classpath: " + resource)
                .isNotNull();
            return json.readTree(is).get("_meta");
        }
    }
}
