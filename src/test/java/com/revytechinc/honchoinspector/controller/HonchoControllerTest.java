package com.revytechinc.honchoinspector.controller;

import tools.jackson.databind.ObjectMapper;
import com.revytechinc.honchoinspector.auth.AuthController;
import com.revytechinc.honchoinspector.auth.PasswordHasher;
import com.revytechinc.honchoinspector.auth.Profile;
import com.revytechinc.honchoinspector.auth.ProfileService;
import com.revytechinc.honchoinspector.honcho.HonchoApiVersion;
import com.revytechinc.honchoinspector.honcho.HonchoCallException;
import com.revytechinc.honchoinspector.model.HonchoContext;
import com.revytechinc.honchoinspector.service.HonchoProxyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for the T16-refactored {@link HonchoController}.
 *
 * <p>The controller is now a thin delegating layer: it pulls the
 * authenticated profile out of the request headers, builds a
 * {@link HonchoContext} (resolving the per-profile {@code apiVersion}),
 * and calls exactly one of the 24 typed convenience methods on
 * {@link HonchoProxyService}. The 24 endpoint tests below confirm the
 * routing — for each endpoint, the mock proxy is configured to return a
 * known marker, the request is issued, and {@code verify(...)} confirms
 * the typed method was invoked with the right {@link HonchoContext} and
 * argument shape.
 *
 * <p>Additional tests cover:
 * <ul>
 *   <li>The 401/400/404 envelope (no session, no profile header,
 *       unknown profile id) — shared with the broader
 *       auth-filter contract.</li>
 *   <li>The composite {@code /workspace/info} endpoint — verifies both
 *       {@code getWorkspaceInfo} and {@code getQueueStatus} are called
 *       and the response is shaped as {@code {workspace, queue}}.</li>
 *   <li>The {@code /dream} endpoint — verifies the {@code peerId} is
 *       extracted from the body and passed to
 *       {@code scheduleDream}, and that a missing {@code peerId}
 *       yields 400.</li>
 *   <li>The {@code /sessions/{id}/context} endpoint — verifies the
 *       {@code tokens} and {@code summary} query params are parsed
 *       and forwarded to {@code getSessionContext}.</li>
 *   <li>The per-profile {@code apiVersion} resolution — a profile with
 *       {@code apiVersion = "v2"} should produce a {@link HonchoContext}
 *       whose {@code apiVersion()} is {@link HonchoApiVersion#V2}.</li>
 *   <li>{@link HonchoCallException} is mapped to a non-2xx response
 *       with the upstream status code (502 for upstream 5xx, otherwise
 *       Honcho's status code).</li>
 * </ul>
 *
 * <p>Test isolation: {@link BeforeEach} wipes all three tables so each
 * test starts with a known user / profile. The {@link HonchoProxyService}
 * is a {@link MockitoBean}, so the test never reaches Honcho or the
 * downstream providers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "HONCHO_DB_PATH=jdbc:sqlite::memory:",
    "honcho.crypto-key=dGVzdC1rZXktMzItYnl0ZXMtZm9yLWVuY3J5cHRpb24="
})
class HonchoControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProfileService profiles;
    @Autowired PasswordHasher hasher;
    @MockitoBean HonchoProxyService honchoProxy;

    private String sessionId;
    private String profileId;

    @BeforeEach
    void cleanDb() throws Exception {
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("DELETE FROM honcho_profiles");
        jdbc.update("DELETE FROM users");
        sessionId = registerAndLogin("alice", "passw0rd1");
        profileId = createProfile("production", "hnc_test_key");
    }

    private String registerAndLogin(String username, String password) throws Exception {
        createUserDirect(username, password, false);
        return loginAs(username, password);
    }

    private String loginAs(String username, String password) throws Exception {
        MvcResult res = mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(new AuthController.CredentialsDto(username, password))))
            .andExpect(status().isOk())
            .andReturn();
        return json.readTree(res.getResponse().getContentAsString()).get("sessionId").asText();
    }

    private String createUserDirect(String username, String password, boolean isAdmin) {
        var id = randomId();
        jdbc.update(
            "INSERT INTO users (id, username, password_hash, is_admin, created_at) VALUES (?, ?, ?, ?, ?)",
            id, username, hasher.hash(password), isAdmin ? 1 : 0, java.time.Instant.now().toString());
        return id;
    }

    private static String randomId() {
        var b = new byte[24];
        new java.security.SecureRandom().nextBytes(b);
        return java.util.HexFormat.of().formatHex(b);
    }

    private String createProfile(String label, String apiKey) {
        Profile p = profiles.create(
            profileOwnerId(),
            label, apiKey, "https://api.honcho.dev", "ws-1", "revytech"
        );
        return p.id();
    }

    private String profileOwnerId() {
        return jdbc.queryForObject(
            "SELECT id FROM users WHERE username = 'alice'", String.class
        );
    }

    private MockHttpServletRequestBuilder withHeaders(MockHttpServletRequestBuilder b) {
        return b.header("X-Session-Id", sessionId).header(HonchoController.PROFILE_HEADER, profileId);
    }

    private void stubReturn(Object marker) {
        // Default: every method returns the marker. Specific tests override
        // the methods they care about with a when(...).thenReturn(...) to
        // capture a return value distinct from the marker.
        when(honchoProxy.listPeers(any(), any())).thenReturn(marker);
        when(honchoProxy.createPeer(any(), any())).thenReturn(marker);
        when(honchoProxy.getPeerCard(any(), any())).thenReturn(marker);
        when(honchoProxy.updatePeerCard(any(), any(), any())).thenReturn(marker);
        when(honchoProxy.getPeerRepresentation(any(), any(), any())).thenReturn(marker);
        when(honchoProxy.peerChat(any(), any(), any())).thenReturn(marker);
        when(honchoProxy.searchPeers(any(), any(), any())).thenReturn(marker);
        when(honchoProxy.listPeerConclusions(any(), any(), any())).thenReturn(marker);
        when(honchoProxy.listPeerSessions(any(), any(), any())).thenReturn(marker);
        when(honchoProxy.queryPeerConclusions(any(), any(), any())).thenReturn(marker);
        when(honchoProxy.listSessions(any(), any())).thenReturn(marker);
        when(honchoProxy.createSession(any(), any())).thenReturn(marker);
        when(honchoProxy.getSession(any(), any())).thenReturn(marker);
        when(honchoProxy.deleteSession(any(), any())).thenReturn(marker);
        when(honchoProxy.listSessionMessages(any(), any(), any())).thenReturn(marker);
        when(honchoProxy.addMessage(any(), any(), any())).thenReturn(marker);
        when(honchoProxy.getSessionContext(any(), any(), any(), any())).thenReturn(marker);
        when(honchoProxy.getSessionSummaries(any(), any())).thenReturn(marker);
        when(honchoProxy.getSessionPeers(any(), any())).thenReturn(marker);
        when(honchoProxy.searchSessionMessages(any(), any(), any())).thenReturn(marker);
        when(honchoProxy.getQueueStatus(any())).thenReturn(marker);
        when(honchoProxy.searchMessages(any(), any())).thenReturn(marker);
        when(honchoProxy.scheduleDream(any(), any(), any())).thenReturn(marker);
        when(honchoProxy.getWorkspaceInfo(any())).thenReturn(marker);
    }

    // ------------------------------------------------------------------
    // 401/400/404 envelope
    // ------------------------------------------------------------------

    @Test
    void returns401WhenNotAuthenticated() throws Exception {
        // SessionAuthFilter rejects with 401 + "missing or invalid session"
        // BEFORE the controller's call() helper ever runs.
        mvc.perform(get("/api/peers").header(HonchoController.PROFILE_HEADER, profileId))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("missing or invalid session"));
    }

    @Test
    void returns400WhenProfileHeaderMissing() throws Exception {
        mvc.perform(get("/api/peers").header("X-Session-Id", sessionId))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("missing " + HonchoController.PROFILE_HEADER + " header"));
    }

    @Test
    void returns404WhenProfileNotFound() throws Exception {
        mvc.perform(get("/api/peers")
                .header("X-Session-Id", sessionId)
                .header(HonchoController.PROFILE_HEADER, "deadbeef"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("profile not found"));
    }

    // ------------------------------------------------------------------
    // 24 endpoint tests — one per HonchoController method
    // ------------------------------------------------------------------

    @Test
    void listPeers_delegatesToHonchoListPeers() throws Exception {
        Map<String, String> marker = Map.of("items", "peer-list");
        when(honchoProxy.listPeers(any(), eq(Map.of("limit", "5")))).thenReturn(marker);

        mvc.perform(withHeaders(get("/api/peers").param("limit", "5")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").value("peer-list"));

        verify(honchoProxy).listPeers(any(), eq(Map.of("limit", "5")));
    }

    @Test
    void createPeer_delegatesToHonchoCreatePeer() throws Exception {
        Object body = Map.of("id", "p-1", "name", "alice");
        when(honchoProxy.createPeer(any(), eq(body))).thenReturn(body);

        mvc.perform(withHeaders(post("/api/peers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body))))
            .andExpect(status().isOk());

        verify(honchoProxy).createPeer(any(), eq(body));
    }

    @Test
    void peerCard_delegatesToHonchoGetPeerCard() throws Exception {
        when(honchoProxy.getPeerCard(any(), eq("p-1"))).thenReturn(List.of("fact"));

        mvc.perform(withHeaders(get("/api/peers/p-1/card")))
            .andExpect(status().isOk());

        verify(honchoProxy).getPeerCard(any(), eq("p-1"));
    }

    @Test
    void updatePeerCard_delegatesToHonchoUpdatePeerCard() throws Exception {
        Object body = List.of("fact a", "fact b");
        when(honchoProxy.updatePeerCard(any(), eq("p-1"), eq(body))).thenReturn(body);

        mvc.perform(withHeaders(post("/api/peers/p-1/card")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body))))
            .andExpect(status().isOk());

        verify(honchoProxy).updatePeerCard(any(), eq("p-1"), eq(body));
    }

    @Test
    void peerRepresentation_delegatesToHonchoGetPeerRepresentation() throws Exception {
        when(honchoProxy.getPeerRepresentation(any(), eq("p-1"), any())).thenReturn("rep text");

        mvc.perform(withHeaders(post("/api/peers/p-1/representation")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")))
            .andExpect(status().isOk());

        verify(honchoProxy).getPeerRepresentation(any(), eq("p-1"), any());
    }

    @Test
    void peerChat_delegatesToHonchoPeerChat() throws Exception {
        Object body = Map.of("query", "hi");
        when(honchoProxy.peerChat(any(), eq("p-1"), eq(body))).thenReturn(Map.of("response", "hello"));

        mvc.perform(withHeaders(post("/api/peers/p-1/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.response").value("hello"));

        verify(honchoProxy).peerChat(any(), eq("p-1"), eq(body));
    }

    @Test
    void peerSearch_delegatesToHonchoSearchPeers() throws Exception {
        Object body = Map.of("q", "needle");
        when(honchoProxy.searchPeers(any(), eq("p-1"), eq(body))).thenReturn(Map.of("results", "x"));

        mvc.perform(withHeaders(post("/api/peers/p-1/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body))))
            .andExpect(status().isOk());

        verify(honchoProxy).searchPeers(any(), eq("p-1"), eq(body));
    }

    @Test
    void peerConclusions_delegatesToHonchoListPeerConclusions() throws Exception {
        Object body = Map.of("filters", Map.of("size", 10));
        when(honchoProxy.listPeerConclusions(any(), eq("p-1"), eq(body)))
            .thenReturn(Map.of("items", "c"));

        mvc.perform(withHeaders(post("/api/peers/p-1/conclusions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body))))
            .andExpect(status().isOk());

        verify(honchoProxy).listPeerConclusions(any(), eq("p-1"), eq(body));
    }

    @Test
    void peerSessions_delegatesToHonchoListPeerSessions() throws Exception {
        when(honchoProxy.listPeerSessions(any(), eq("p-1"), any()))
            .thenReturn(Map.of("items", "s"));

        mvc.perform(withHeaders(get("/api/peers/p-1/sessions")))
            .andExpect(status().isOk());

        verify(honchoProxy).listPeerSessions(any(), eq("p-1"), any());
    }

    @Test
    void peerConclusionsQuery_delegatesToHonchoQueryPeerConclusions() throws Exception {
        Object body = Map.of("q", "summary");
        when(honchoProxy.queryPeerConclusions(any(), eq("p-1"), eq(body)))
            .thenReturn(Map.of("matches", "y"));

        mvc.perform(withHeaders(post("/api/peers/p-1/conclusions/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body))))
            .andExpect(status().isOk());

        verify(honchoProxy).queryPeerConclusions(any(), eq("p-1"), eq(body));
    }

    @Test
    void listSessions_delegatesToHonchoListSessions() throws Exception {
        when(honchoProxy.listSessions(any(), eq(Map.of("limit", "20"))))
            .thenReturn(Map.of("items", "sess-list"));

        mvc.perform(withHeaders(get("/api/sessions").param("limit", "20")))
            .andExpect(status().isOk());

        verify(honchoProxy).listSessions(any(), eq(Map.of("limit", "20")));
    }

    @Test
    void createSession_delegatesToHonchoCreateSession() throws Exception {
        Object body = Map.of("name", "my session");
        when(honchoProxy.createSession(any(), eq(body))).thenReturn(Map.of("id", "s-1"));

        mvc.perform(withHeaders(post("/api/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body))))
            .andExpect(status().isOk());

        verify(honchoProxy).createSession(any(), eq(body));
    }

    @Test
    void getSession_delegatesToHonchoGetSession() throws Exception {
        when(honchoProxy.getSession(any(), eq("s-7"))).thenReturn(Map.of("id", "s-7"));

        mvc.perform(withHeaders(get("/api/sessions/s-7")))
            .andExpect(status().isOk());

        verify(honchoProxy).getSession(any(), eq("s-7"));
    }

    @Test
    void deleteSession_delegatesToHonchoDeleteSession() throws Exception {
        when(honchoProxy.deleteSession(any(), eq("s-7"))).thenReturn(Map.of("ok", "true"));

        mvc.perform(withHeaders(delete("/api/sessions/s-7")))
            .andExpect(status().isOk());

        verify(honchoProxy).deleteSession(any(), eq("s-7"));
    }

    @Test
    void listMessages_delegatesToHonchoListSessionMessages() throws Exception {
        when(honchoProxy.listSessionMessages(any(), eq("s-7"), any()))
            .thenReturn(Map.of("items", "msgs"));

        mvc.perform(withHeaders(get("/api/sessions/s-7/messages")))
            .andExpect(status().isOk());

        verify(honchoProxy).listSessionMessages(any(), eq("s-7"), any());
    }

    @Test
    void addMessages_delegatesToHonchoAddMessage() throws Exception {
        Object body = Map.of("messages", List.of(Map.of("content", "hi")));
        when(honchoProxy.addMessage(any(), eq("s-7"), eq(body))).thenReturn(Map.of("ok", "true"));

        mvc.perform(withHeaders(post("/api/sessions/s-7/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body))))
            .andExpect(status().isOk());

        verify(honchoProxy).addMessage(any(), eq("s-7"), eq(body));
    }

    @Test
    void sessionContext_delegatesToHonchoGetSessionContextWithParsedQueryParams() throws Exception {
        when(honchoProxy.getSessionContext(any(), eq("s-7"), eq(4096), eq(true)))
            .thenReturn(Map.of("messages", "ctx"));

        mvc.perform(withHeaders(get("/api/sessions/s-7/context")
                .param("tokens", "4096")
                .param("summary", "true")))
            .andExpect(status().isOk());

        verify(honchoProxy).getSessionContext(any(), eq("s-7"), eq(4096), eq(true));
    }

    @Test
    void sessionContext_omitsTokensAndSummaryWhenAbsent() throws Exception {
        when(honchoProxy.getSessionContext(any(), eq("s-7"), isNull(), isNull()))
            .thenReturn(Map.of("messages", "ctx"));

        mvc.perform(withHeaders(get("/api/sessions/s-7/context")))
            .andExpect(status().isOk());

        verify(honchoProxy).getSessionContext(any(), eq("s-7"), isNull(), isNull());
    }

    @Test
    void sessionContext_toleratesMalformedTokensParam() throws Exception {
        // A non-numeric `tokens` value is best-effort: parsed as null so the
        // proxy doesn't 400 the request. Honcho's own validation will catch
        // a bad value server-side if it matters.
        when(honchoProxy.getSessionContext(any(), eq("s-7"), isNull(), eq(false)))
            .thenReturn(Map.of("messages", "ctx"));

        mvc.perform(withHeaders(get("/api/sessions/s-7/context")
                .param("tokens", "not-a-number")
                .param("summary", "false")))
            .andExpect(status().isOk());

        verify(honchoProxy).getSessionContext(any(), eq("s-7"), isNull(), eq(false));
    }

    @Test
    void sessionSummaries_delegatesToHonchoGetSessionSummaries() throws Exception {
        when(honchoProxy.getSessionSummaries(any(), eq("s-7"))).thenReturn(List.of("sum1"));

        mvc.perform(withHeaders(get("/api/sessions/s-7/summaries")))
            .andExpect(status().isOk());

        verify(honchoProxy).getSessionSummaries(any(), eq("s-7"));
    }

    @Test
    void sessionPeers_delegatesToHonchoGetSessionPeers() throws Exception {
        when(honchoProxy.getSessionPeers(any(), eq("s-7"))).thenReturn(List.of("alice", "bob"));

        mvc.perform(withHeaders(get("/api/sessions/s-7/peers")))
            .andExpect(status().isOk());

        verify(honchoProxy).getSessionPeers(any(), eq("s-7"));
    }

    @Test
    void sessionSearch_delegatesToHonchoSearchSessionMessages() throws Exception {
        Object body = Map.of("q", "needle");
        when(honchoProxy.searchSessionMessages(any(), eq("s-7"), eq(body)))
            .thenReturn(Map.of("results", "x"));

        mvc.perform(withHeaders(post("/api/sessions/s-7/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body))))
            .andExpect(status().isOk());

        verify(honchoProxy).searchSessionMessages(any(), eq("s-7"), eq(body));
    }

    @Test
    void queueStatus_delegatesToHonchoGetQueueStatus() throws Exception {
        when(honchoProxy.getQueueStatus(any())).thenReturn(Map.of("pending", 0));

        mvc.perform(withHeaders(get("/api/queue-status")))
            .andExpect(status().isOk());

        verify(honchoProxy).getQueueStatus(any());
    }

    @Test
    void workspaceSearch_delegatesToHonchoSearchMessages() throws Exception {
        Object body = Map.of("q", "global");
        when(honchoProxy.searchMessages(any(), eq(body))).thenReturn(Map.of("results", "all"));

        mvc.perform(withHeaders(post("/api/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body))))
            .andExpect(status().isOk());

        verify(honchoProxy).searchMessages(any(), eq(body));
    }

    @Test
    void scheduleDream_extractsPeerIdFromBodyAndDelegates() throws Exception {
        Object body = Map.of("peerId", "p-1", "lookback", "7d");
        when(honchoProxy.scheduleDream(any(), eq("p-1"), eq(body))).thenReturn(Map.of("scheduled", true));

        mvc.perform(withHeaders(post("/api/dream")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.scheduled").value(true));

        // The full body (including peerId) is forwarded as the third arg;
        // peerId is also passed separately as the second arg. Both must agree.
        verify(honchoProxy).scheduleDream(any(), eq("p-1"), eq(body));
    }

    @Test
    void scheduleDream_returns400WhenPeerIdMissing() throws Exception {
        Object body = Map.of("lookback", "7d");

        mvc.perform(withHeaders(post("/api/dream")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("peerId")));
    }

    @Test
    void scheduleDream_returns400WhenPeerIdBlank() throws Exception {
        Object body = Map.of("peerId", "   ");

        mvc.perform(withHeaders(post("/api/dream")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void workspaceInfo_synthesizesWorkspaceFromContextAndReturnsQueueSnapshot() throws Exception {
        // Honcho v3 has no GET /v3/workspaces/{id} endpoint, so the controller
        // synthesizes the workspace field from the profile's workspaceId and
        // delegates the queue snapshot to the queue-status endpoint.
        Object queueMarker = Map.of("pending", 7);
        when(honchoProxy.getQueueStatus(any())).thenReturn(queueMarker);

        mvc.perform(withHeaders(get("/api/workspace/info")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.workspace.id").value("ws-1"))
            .andExpect(jsonPath("$.queue.pending").value(7));

        verify(honchoProxy, never()).getWorkspaceInfo(any());
        verify(honchoProxy).getQueueStatus(any());
    }

    // ------------------------------------------------------------------
    // HonchoContext construction — verifies the controller builds the
    // right 5-arg context (apiKey, baseUrl, workspaceId, userName, apiVersion)
    // from the profile row, with apiVersion defaulted to HonchoApiVersion.V3
    // when the profile doesn't override it.
    // ------------------------------------------------------------------

    @Test
    void callHelper_buildsHonchoContextFromProfileWithDefaultApiVersion() throws Exception {
        // Profile created in @BeforeEach has no apiVersion → controller
        // should fall back to HonchoApiVersion.V3 (server default).
        when(honchoProxy.listPeers(any(), any())).thenReturn(Map.of("ok", true));

        mvc.perform(withHeaders(get("/api/peers")))
            .andExpect(status().isOk());

        ArgumentCaptor<HonchoContext> ctxCap = ArgumentCaptor.forClass(HonchoContext.class);
        verify(honchoProxy).listPeers(ctxCap.capture(), any());
        HonchoContext ctx = ctxCap.getValue();
        assertThat(ctx.apiKey()).isEqualTo("hnc_test_key");
        assertThat(ctx.baseUrl()).isEqualTo("https://api.honcho.dev");
        assertThat(ctx.workspaceId()).isEqualTo("ws-1");
        assertThat(ctx.userName()).isEqualTo("revytech");
        assertThat(ctx.apiVersion())
            .as("profile has no apiVersion; should fall back to HonchoApiVersion.V3")
            .isEqualTo(HonchoApiVersion.V3);
    }

    @Test
    void callHelper_usesProfileApiVersionOverride() throws Exception {
        Profile p2 = profiles.create(
            profileOwnerId(), "staging", "hnc_test_key_2",
            "https://api.honcho.dev", "ws-1", "revytech", "v2"
        );
        when(honchoProxy.listPeers(any(), any())).thenReturn(Map.of("ok", true));

        mvc.perform(get("/api/peers")
                .header("X-Session-Id", sessionId)
                .header(HonchoController.PROFILE_HEADER, p2.id()))
            .andExpect(status().isOk());

        ArgumentCaptor<HonchoContext> ctxCap = ArgumentCaptor.forClass(HonchoContext.class);
        verify(honchoProxy).listPeers(ctxCap.capture(), any());
        assertThat(ctxCap.getValue().apiVersion())
            .as("profile.apiVersion = v2 should override the server default")
            .isEqualTo(HonchoApiVersion.V2);
    }

    // ------------------------------------------------------------------
    // HonchoCallException mapping — upstream 4xx/5xx should be surfaced
    // via the controller's catch-all.
    // ------------------------------------------------------------------

    @Test
    void honchoCallException_4xxIsReturnedAsIs() throws Exception {
        when(honchoProxy.listPeers(any(), any()))
            .thenThrow(new HonchoCallException("not found", 404, "{\"error\":\"no peer\"}"));

        mvc.perform(withHeaders(get("/api/peers")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("not found"));
    }

    @Test
    void honchoCallException_5xxIsMappedTo502() throws Exception {
        when(honchoProxy.listPeers(any(), any()))
            .thenThrow(new HonchoCallException("upstream down", 503, ""));

        mvc.perform(withHeaders(get("/api/peers")))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.error").value("upstream down"));
    }

    // ------------------------------------------------------------------
    // Marker roundtrip — confirms the controller returns the upstream
    // payload unchanged. Uses the default marker for brevity.
    // ------------------------------------------------------------------

    @Test
    void responseBody_isTheUpstreamPayloadUnchanged() throws Exception {
        Map<String, String> marker = Map.of("hello", "world");
        stubReturn(marker);

        mvc.perform(withHeaders(get("/api/peers")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hello").value("world"));
    }
}
