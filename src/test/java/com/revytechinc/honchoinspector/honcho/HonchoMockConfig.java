package com.revytechinc.honchoinspector.honcho;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.revytechinc.honchoinspector.model.HonchoContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.revytechinc.honchoinspector.honcho.HonchoOperation.ADD_MESSAGE;
import static com.revytechinc.honchoinspector.honcho.HonchoOperation.CREATE_PEER;
import static com.revytechinc.honchoinspector.honcho.HonchoOperation.CREATE_SESSION;
import static com.revytechinc.honchoinspector.honcho.HonchoOperation.DELETE_SESSION;
import static com.revytechinc.honchoinspector.honcho.HonchoOperation.GET_PEER_CARD;
import static com.revytechinc.honchoinspector.honcho.HonchoOperation.GET_QUEUE_STATUS;
import static com.revytechinc.honchoinspector.honcho.HonchoOperation.GET_REPRESENTATION;
import static com.revytechinc.honchoinspector.honcho.HonchoOperation.GET_SESSION;
import static com.revytechinc.honchoinspector.honcho.HonchoOperation.GET_SESSION_CONTEXT;
import static com.revytechinc.honchoinspector.honcho.HonchoOperation.GET_SESSION_PEERS;
import static com.revytechinc.honchoinspector.honcho.HonchoOperation.GET_SESSION_SUMMARIES;
import static com.revytechinc.honchoinspector.honcho.HonchoOperation.GET_WORKSPACE_INFO;
import static com.revytechinc.honchoinspector.honcho.HonchoOperation.LIST_PEER_CONCLUSIONS;
import static com.revytechinc.honchoinspector.honcho.HonchoOperation.LIST_PEER_SESSIONS;
import static com.revytechinc.honchoinspector.honcho.HonchoOperation.LIST_PEERS;
import static com.revytechinc.honchoinspector.honcho.HonchoOperation.LIST_SESSION_MESSAGES;
import static com.revytechinc.honchoinspector.honcho.HonchoOperation.LIST_SESSIONS;
import static com.revytechinc.honchoinspector.honcho.HonchoOperation.PEER_CHAT;
import static com.revytechinc.honchoinspector.honcho.HonchoOperation.QUERY_PEER_CONCLUSIONS;
import static com.revytechinc.honchoinspector.honcho.HonchoOperation.SCHEDULE_DREAM;
import static com.revytechinc.honchoinspector.honcho.HonchoOperation.SEARCH_MESSAGES;
import static com.revytechinc.honchoinspector.honcho.HonchoOperation.SEARCH_PEERS;
import static com.revytechinc.honchoinspector.honcho.HonchoOperation.SEARCH_SESSION_MESSAGES;
import static com.revytechinc.honchoinspector.honcho.HonchoOperation.UPDATE_PEER_CARD;

/**
 * Test fixture-backed mock {@link HonchoClient} + factory.
 *
 * <p>Wired into integration tests via {@code @Import(HonchoMockConfig.class)}.
 * All 24 typed methods + the generic {@link HonchoClient#call} dispatch entry
 * point return the contents of a JSON fixture loaded from
 * {@code src/test/resources/fixtures/honcho/v3/}. Each fixture has an
 * {@code _meta} envelope; this mock strips {@code _meta} and returns just the
 * {@code data} payload, exactly as a real Honcho response body would arrive
 * at the controller layer (the {@code _meta} block is a capture-time
 * artifact, not part of the wire contract).
 *
 * <h2>Why not WireMock (per the T23 plan)</h2>
 * WireMock adds a network boundary (separate process, request/response
 * matching DSL) when the actual problem is "what should this Honcho client
 * return when called?". With 29 hand-curated JSON files already on disk
 * from T22, the cheapest answer is: load the file, return its {@code data}.
 * No request matcher to maintain, no JSONPath DSL to learn, no process
 * boundary to debug when a test fails.
 *
 * <h2>Boot-time wiring</h2>
 * The {@link HonchoClientFactory} bean has a fail-fast constructor that
 * throws if two clients claim the same {@link HonchoApiVersion}. Since the
 * production {@code HonchoV3Client} is on the classpath and also claims V3,
 * our mock factory here would collide with the production factory's
 * constructor. To prevent the throw, integration tests using this
 * configuration <strong>must also</strong> add
 * {@code @MockitoBean HonchoClientFactory honchoClientFactory} (or use a
 * filter that excludes {@code HonchoV3Client}); the Mockito mock replaces
 * the production factory bean before its constructor runs.
 *
 * <p>The {@link #honchoMockClientFactory(HonchoClient) @Primary factory bean}
 * here wraps <em>only</em> the mock client in a real {@link HonchoClientFactory},
 * so autowiring the factory in any service picks this one and the dispatch
 * table is {@code {V3 → honchoMockClient}}.
 */
@TestConfiguration
public class HonchoMockConfig {

    private static final Logger log = LoggerFactory.getLogger(HonchoMockConfig.class);

    /**
     * Fixture-backed mock {@link HonchoClient}. Implemented as a static
     * inner class so the wiring is co-located with the bean definition —
     * no separate file to maintain, but still package-internal so it does
     * not leak into production code.
     */
    @Bean
    public HonchoClient honchoMockClient() {
        return new HonchoFixtureClient();
    }

    /**
     * Real {@link HonchoClientFactory} wrapping only the mock client.
     * Marked {@link Primary @Primary} so autowiring prefers it over the
     * production factory bean (which is replaced by a {@code @MockitoBean}
     * in the integration test base — see class-level Javadoc).
     *
     * <p>Built with the real factory constructor (rather than a hand-rolled
     * subclass) so the same fail-fast validation logic applies. With only
     * the mock client in the list, the constructor's "first wins" semantics
     * produce a clean {@code {V3 → mock}} dispatch table.
     */
    @Bean
    @Primary
    public HonchoClientFactory honchoMockClientFactory(HonchoClient honchoMockClient) {
        return new HonchoClientFactory(List.of(honchoMockClient));
    }

    /**
     * Hand-written {@link HonchoClient} backed by JSON fixtures on the
     * test classpath. Loads fixtures lazily on first call, caches the
     * parsed {@code data} payload, and strips the {@code _meta} envelope
     * before returning. Missing fixtures resolve to an empty Map (not an
     * exception) so unimplemented ops do not break tests that don't care
     * about them.
     */
    static final class HonchoFixtureClient implements HonchoClient {

        static final String FIXTURE_DIR = "fixtures/honcho/v3/";

        /**
         * Each {@link HonchoOperation} constant maps to the fixture file
         * (in {@code src/test/resources/fixtures/honcho/v3/}) that contains
         * a representative response for that operation. Operations not
         * present in the map (currently {@link HonchoOperation#DELETE_SESSION})
         * resolve to an empty result via {@link #load(HonchoOperation)}.
         *
         * <p>The mapping is hand-maintained; a future task could derive it
         * from {@code _meta.endpoint} in each fixture, but that introduces
         * a fragile parsing dependency that this explicit map avoids.
         */
        private static final Map<HonchoOperation, String> FIXTURE_FOR_OP;
        static {
            Map<HonchoOperation, String> m = new EnumMap<>(HonchoOperation.class);
            m.put(HonchoOperation.LIST_PEERS,               "list-peers.json");
            m.put(HonchoOperation.CREATE_PEER,              "create-peer.json");
            m.put(HonchoOperation.GET_PEER_CARD,            "get-peer-card.json");
            m.put(HonchoOperation.UPDATE_PEER_CARD,         "update-peer-card.json");
            m.put(HonchoOperation.GET_REPRESENTATION,       "get-peer-representation.json");
            m.put(HonchoOperation.PEER_CHAT,                "peer-chat.json");
            m.put(HonchoOperation.SEARCH_PEERS,             "peer-search.json");
            m.put(HonchoOperation.LIST_PEER_CONCLUSIONS,    "list-peer-conclusions.json");
            m.put(HonchoOperation.LIST_PEER_SESSIONS,       "list-peer-sessions.json");
            m.put(HonchoOperation.QUERY_PEER_CONCLUSIONS,   "query-peer-conclusions.json");
            m.put(HonchoOperation.LIST_SESSIONS,            "list-sessions.json");
            m.put(HonchoOperation.CREATE_SESSION,           "create-session.json");
            m.put(HonchoOperation.GET_SESSION,              "get-session.json");
            m.put(HonchoOperation.LIST_SESSION_MESSAGES,    "list-session-messages.json");
            m.put(HonchoOperation.ADD_MESSAGE,              "add-message.json");
            m.put(HonchoOperation.GET_SESSION_CONTEXT,      "get-session-context.json");
            m.put(HonchoOperation.GET_SESSION_SUMMARIES,    "get-session-summaries.json");
            m.put(HonchoOperation.GET_SESSION_PEERS,        "get-session-peers.json");
            m.put(HonchoOperation.SEARCH_SESSION_MESSAGES,  "search-session-messages.json");
            m.put(HonchoOperation.GET_QUEUE_STATUS,         "queue-status.json");
            m.put(HonchoOperation.SEARCH_MESSAGES,          "search-messages.json");
            m.put(HonchoOperation.SCHEDULE_DREAM,           "schedule-dream.json");
            m.put(HonchoOperation.GET_WORKSPACE_INFO,       "workspace-info.json");
            FIXTURE_FOR_OP = Map.copyOf(m);
        }

        private final ObjectMapper json = new ObjectMapper();
        private final Map<String, Object> cache = new ConcurrentHashMap<>();

        @Override
        public Set<HonchoApiVersion> supportedVersions() {
            return Set.of(HonchoApiVersion.V3);
        }

        @Override public Object listPeers(HonchoContext ctx, Map<String, ?> filters)                          { return load(LIST_PEERS); }
        @Override public Object createPeer(HonchoContext ctx, Object createPeerRequest)                       { return load(CREATE_PEER); }
        @Override public Object getPeerCard(HonchoContext ctx, String peerId)                                 { return load(GET_PEER_CARD); }
        @Override public Object updatePeerCard(HonchoContext ctx, String peerId, Object cardData)             { return load(UPDATE_PEER_CARD); }
        @Override public Object getPeerRepresentation(HonchoContext ctx, String peerId)                       { return load(GET_REPRESENTATION); }
        @Override public Object peerChat(HonchoContext ctx, String peerId, Object chatRequest)                { return load(PEER_CHAT); }
        @Override public Object searchPeers(HonchoContext ctx, String peerId, Object searchRequest)            { return load(SEARCH_PEERS); }
        @Override public Object listPeerConclusions(HonchoContext ctx, String peerId, Map<String, ?> filters) { return load(LIST_PEER_CONCLUSIONS); }
        @Override public Object listPeerSessions(HonchoContext ctx, String peerId, Map<String, ?> filters)    { return load(LIST_PEER_SESSIONS); }
        @Override public Object queryPeerConclusions(HonchoContext ctx, String peerId, Object queryRequest)   { return load(QUERY_PEER_CONCLUSIONS); }
        @Override public Object listSessions(HonchoContext ctx, Map<String, ?> filters)                       { return load(LIST_SESSIONS); }
        @Override public Object createSession(HonchoContext ctx, Object createSessionRequest)                 { return load(CREATE_SESSION); }
        @Override public Object getSession(HonchoContext ctx, String sessionId)                               { return load(GET_SESSION); }
        @Override public Object deleteSession(HonchoContext ctx, String sessionId)                            { return load(DELETE_SESSION); }
        @Override public Object listSessionMessages(HonchoContext ctx, String sessionId, Map<String, ?> f)    { return load(LIST_SESSION_MESSAGES); }
        @Override public Object addMessage(HonchoContext ctx, String sessionId, Object messageRequest)        { return load(ADD_MESSAGE); }
        @Override public Object getSessionContext(HonchoContext ctx, String sessionId, Integer t, Boolean s)   { return load(GET_SESSION_CONTEXT); }
        @Override public Object getSessionSummaries(HonchoContext ctx, String sessionId)                      { return load(GET_SESSION_SUMMARIES); }
        @Override public Object getSessionPeers(HonchoContext ctx, String sessionId)                          { return load(GET_SESSION_PEERS); }
        @Override public Object searchSessionMessages(HonchoContext ctx, String sessionId, Object s)          { return load(SEARCH_SESSION_MESSAGES); }
        @Override public Object getQueueStatus(HonchoContext ctx)                                             { return load(GET_QUEUE_STATUS); }
        @Override public Object searchMessages(HonchoContext ctx, Object searchRequest)                       { return load(SEARCH_MESSAGES); }
        @Override public Object scheduleDream(HonchoContext ctx, String peerId, Object dreamRequest)          { return load(SCHEDULE_DREAM); }
        @Override public Object getWorkspaceInfo(HonchoContext ctx)                                           { return load(GET_WORKSPACE_INFO); }

        @Override
        public Object call(HonchoOperation op, HonchoContext ctx, Object requestBody,
                           Map<String, String> pathVars, Map<String, ?> queryParams) {
            return load(op);
        }

        /**
         * Load the fixture mapped to {@code op}, strip the {@code _meta}
         * envelope, and return the {@code data} payload as a plain
         * {@code Object} (Map for objects, List for arrays).
         *
         * <p>If the operation has no fixture mapped, returns an empty Map
         * — this keeps tests focused on what they exercise without forcing
         * every test to capture every operation.
         *
         * <p>If the fixture file is missing on the classpath, logs a
         * debug-level message and returns an empty Map. The mock is
         * deliberately non-fatal: a missing fixture means "no data for this
         * op" rather than "the test is broken".
         */
        private Object load(HonchoOperation op) {
            String fixtureName = FIXTURE_FOR_OP.get(op);
            if (fixtureName == null) {
                log.debug("no fixture mapped for op {}; returning empty result", op);
                return Map.of();
            }
            return cache.computeIfAbsent(fixtureName, this::loadFixture);
        }

        private Object loadFixture(String fixtureName) {
            String resourcePath = FIXTURE_DIR + fixtureName;
            try (InputStream is = new ClassPathResource(resourcePath).getInputStream()) {
                JsonNode root = json.readTree(is);
                JsonNode data = root.get("data");
                if (data == null) {
                    log.debug("fixture {} has no 'data' field; returning empty result", resourcePath);
                    return Map.of();
                }
                return json.treeToValue(data, Object.class);
            } catch (IOException e) {
                throw new IllegalStateException(
                    "HonchoMockConfig: failed to load fixture " + resourcePath, e);
            }
        }
    }
}
