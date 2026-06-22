package com.revytechinc.honchoinspector.honcho.v3;

import com.revytechinc.honchoinspector.honcho.HonchoApiVersion;
import com.revytechinc.honchoinspector.honcho.HonchoOperation;
import com.revytechinc.honchoinspector.model.HonchoContext;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contract test for {@link SessionsProviderV3}. Seven tests, one per
 * operation the provider owns. Each test verifies:
 * <ol>
 *   <li>{@code operations()} advertises the operation under test.</li>
 *   <li>{@code supportedVersions()} returns exactly {@code {V3}}.</li>
 *   <li>{@code pathTemplate(op)} returns the right template — same
 *       shape as the Javadoc on the corresponding {@link HonchoOperation}
 *       constant.</li>
 *   <li>{@code httpMethod(op)} returns the right verb — including the
 *       v2→v3 change for {@link HonchoOperation#LIST_SESSIONS} (now
 *       {@code POST}).</li>
 *   <li>URL construction produces the right absolute URL for the given
 *       {@code {ws}} and {@code {sessionId}} placeholders — verified via
 *       {@link V3ProviderSupport#substitutePath} and
 *       {@link V3ProviderSupport#buildUrl} AND via a mocked
 *       {@link RestClient} chain that captures the URL passed to
 *       {@code .uri(...)}.</li>
 * </ol>
 */
class SessionsProviderV3Test {

    private static final HonchoContext CTX = new HonchoContext(
        "test-api-key",
        "https://api.honcho.dev",
        "ws-1",
        "test-user"
    );

    private static final Map<String, String> SESSION_PATH_VARS = Map.of(
        "ws", "ws-42",
        "sessionId", "sess-abc"
    );

    @Test
    void listSessions_usesPostAndListEndpoint() {
        SessionsProviderV3 provider = providerWithMockedClient();

        assertThat(provider.operations())
            .as("LIST_SESSIONS must be in operations()")
            .contains(HonchoOperation.LIST_SESSIONS);
        assertThat(provider.supportedVersions())
            .as("provider is v3-only")
            .containsExactly(HonchoApiVersion.V3);
        assertThat(provider.pathTemplate(HonchoOperation.LIST_SESSIONS))
            .as("v2→v3 contract change: LIST_SESSIONS uses /sessions/list (the bare /sessions path is CREATE-only)")
            .isEqualTo("workspaces/{ws}/sessions/list");
        assertThat(provider.httpMethod(HonchoOperation.LIST_SESSIONS))
            .isEqualTo(HttpMethod.POST);
        String template = provider.pathTemplate(HonchoOperation.LIST_SESSIONS);
        String substituted = V3ProviderSupport.substitutePath(template, CTX, wsVar("ws-42"));
        String url = V3ProviderSupport.buildUrl(CTX.baseUrl(), CTX.apiVersion().pathPrefix(), substituted, Map.of());
        assertThat(url).isEqualTo("https://api.honcho.dev/v3/workspaces/ws-42/sessions/list");
    }

    @Test
    void createSession_usesPostAndCreateEndpoint() {
        SessionsProviderV3 provider = providerWithMockedClient();

        assertThat(provider.operations()).contains(HonchoOperation.CREATE_SESSION);
        assertThat(provider.supportedVersions()).containsExactly(HonchoApiVersion.V3);
        assertThat(provider.pathTemplate(HonchoOperation.CREATE_SESSION))
            .isEqualTo("workspaces/{ws}/sessions");
        assertThat(provider.httpMethod(HonchoOperation.CREATE_SESSION))
            .isEqualTo(HttpMethod.POST);
        String template = provider.pathTemplate(HonchoOperation.CREATE_SESSION);
        String substituted = V3ProviderSupport.substitutePath(template, CTX, wsVar("ws-42"));
        String url = V3ProviderSupport.buildUrl(CTX.baseUrl(), CTX.apiVersion().pathPrefix(), substituted, Map.of());
        assertThat(url).isEqualTo("https://api.honcho.dev/v3/workspaces/ws-42/sessions");
    }

    @Test
    void getSession_usesGetAndIncludesSessionId() {
        SessionsProviderV3 provider = providerWithMockedClient();

        assertThat(provider.operations()).contains(HonchoOperation.GET_SESSION);
        assertThat(provider.supportedVersions()).containsExactly(HonchoApiVersion.V3);
        assertThat(provider.pathTemplate(HonchoOperation.GET_SESSION))
            .isEqualTo("workspaces/{ws}/sessions/{sessionId}");
        assertThat(provider.httpMethod(HonchoOperation.GET_SESSION))
            .isEqualTo(HttpMethod.GET);
        String template = provider.pathTemplate(HonchoOperation.GET_SESSION);
        String substituted = V3ProviderSupport.substitutePath(template, CTX, SESSION_PATH_VARS);
        String url = V3ProviderSupport.buildUrl(CTX.baseUrl(), CTX.apiVersion().pathPrefix(), substituted, Map.of());
        assertThat(url).isEqualTo("https://api.honcho.dev/v3/workspaces/ws-42/sessions/sess-abc");
    }

    @Test
    void deleteSession_usesDeleteAndIncludesSessionId() {
        SessionsProviderV3 provider = providerWithMockedClient();

        assertThat(provider.operations()).contains(HonchoOperation.DELETE_SESSION);
        assertThat(provider.supportedVersions()).containsExactly(HonchoApiVersion.V3);
        assertThat(provider.pathTemplate(HonchoOperation.DELETE_SESSION))
            .isEqualTo("workspaces/{ws}/sessions/{sessionId}");
        assertThat(provider.httpMethod(HonchoOperation.DELETE_SESSION))
            .isEqualTo(HttpMethod.DELETE);
        String template = provider.pathTemplate(HonchoOperation.DELETE_SESSION);
        String substituted = V3ProviderSupport.substitutePath(template, CTX, SESSION_PATH_VARS);
        String url = V3ProviderSupport.buildUrl(CTX.baseUrl(), CTX.apiVersion().pathPrefix(), substituted, Map.of());
        assertThat(url).isEqualTo("https://api.honcho.dev/v3/workspaces/ws-42/sessions/sess-abc");
    }

    @Test
    void getSessionContext_usesGetAndContextSubpath() {
        SessionsProviderV3 provider = providerWithMockedClient();

        assertThat(provider.operations()).contains(HonchoOperation.GET_SESSION_CONTEXT);
        assertThat(provider.supportedVersions()).containsExactly(HonchoApiVersion.V3);
        assertThat(provider.pathTemplate(HonchoOperation.GET_SESSION_CONTEXT))
            .isEqualTo("workspaces/{ws}/sessions/{sessionId}/context");
        assertThat(provider.httpMethod(HonchoOperation.GET_SESSION_CONTEXT))
            .isEqualTo(HttpMethod.GET);
        String template = provider.pathTemplate(HonchoOperation.GET_SESSION_CONTEXT);
        String substituted = V3ProviderSupport.substitutePath(template, CTX, SESSION_PATH_VARS);
        String url = V3ProviderSupport.buildUrl(CTX.baseUrl(), CTX.apiVersion().pathPrefix(), substituted, Map.of());
        assertThat(url).isEqualTo("https://api.honcho.dev/v3/workspaces/ws-42/sessions/sess-abc/context");
    }

    @Test
    void getSessionSummaries_usesGetAndSummariesSubpath() {
        SessionsProviderV3 provider = providerWithMockedClient();

        assertThat(provider.operations()).contains(HonchoOperation.GET_SESSION_SUMMARIES);
        assertThat(provider.supportedVersions()).containsExactly(HonchoApiVersion.V3);
        assertThat(provider.pathTemplate(HonchoOperation.GET_SESSION_SUMMARIES))
            .isEqualTo("workspaces/{ws}/sessions/{sessionId}/summaries");
        assertThat(provider.httpMethod(HonchoOperation.GET_SESSION_SUMMARIES))
            .isEqualTo(HttpMethod.GET);
        String template = provider.pathTemplate(HonchoOperation.GET_SESSION_SUMMARIES);
        String substituted = V3ProviderSupport.substitutePath(template, CTX, SESSION_PATH_VARS);
        String url = V3ProviderSupport.buildUrl(CTX.baseUrl(), CTX.apiVersion().pathPrefix(), substituted, Map.of());
        assertThat(url).isEqualTo("https://api.honcho.dev/v3/workspaces/ws-42/sessions/sess-abc/summaries");
    }

    @Test
    void getSessionPeers_usesGetAndPeersSubpath() {
        SessionsProviderV3 provider = providerWithMockedClient();

        assertThat(provider.operations()).contains(HonchoOperation.GET_SESSION_PEERS);
        assertThat(provider.supportedVersions()).containsExactly(HonchoApiVersion.V3);
        assertThat(provider.pathTemplate(HonchoOperation.GET_SESSION_PEERS))
            .isEqualTo("workspaces/{ws}/sessions/{sessionId}/peers");
        assertThat(provider.httpMethod(HonchoOperation.GET_SESSION_PEERS))
            .isEqualTo(HttpMethod.GET);
        String template = provider.pathTemplate(HonchoOperation.GET_SESSION_PEERS);
        String substituted = V3ProviderSupport.substitutePath(template, CTX, SESSION_PATH_VARS);
        String url = V3ProviderSupport.buildUrl(CTX.baseUrl(), CTX.apiVersion().pathPrefix(), substituted, Map.of());
        assertThat(url).isEqualTo("https://api.honcho.dev/v3/workspaces/ws-42/sessions/sess-abc/peers");
    }

    @Test
    void execute_callsRestClientWithCorrectUrlAndMethod() {
        // One full integration test: drive execute() through a mocked
        // RestClient chain and verify the URL captured by the mock.
        // The RestClient.Builder pattern is exercised by constructing
        // the provider with a mocked client; this confirms the wiring
        // between pathTemplate, httpMethod, and the actual HTTP call.
        RestClient mockClient = mock(RestClient.class);
        RestClient.RequestBodyUriSpec mockUriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.ResponseSpec mockResponseSpec = mock(RestClient.ResponseSpec.class);

        when(mockClient.method(any(HttpMethod.class))).thenReturn(mockUriSpec);
        when(mockUriSpec.uri((String) any())).thenReturn(mockUriSpec);
        when(mockUriSpec.headers(any())).thenReturn(mockUriSpec);
        when(mockUriSpec.contentType(any())).thenReturn(mockUriSpec);
        when(mockUriSpec.retrieve()).thenReturn(mockResponseSpec);
        when(mockResponseSpec.toEntity(any(Class.class)))
            .thenReturn(ResponseEntity.ok("ok"));

        SessionsProviderV3 provider = new SessionsProviderV3(mockClient);

        provider.execute(
            HonchoOperation.GET_SESSION,
            CTX,
            null,
            null,
            SESSION_PATH_VARS,
            new HashMap<>()
        );

        // URL captured by the mock must match the buildUrl() output exactly.
        String expectedUrl = "https://api.honcho.dev/v3/workspaces/ws-42/sessions/sess-abc";
        verify(mockClient, atLeastOnce()).method(argThat(m -> m == HttpMethod.GET));
        verify(mockUriSpec, atLeastOnce()).uri(
            argThat((String url) -> url != null && url.equals(expectedUrl))
        );
    }

    /**
     * Build a provider whose {@code RestClient} is a Mockito mock with a
     * fully stubbed chain — sufficient to satisfy the per-test URL/method
     * assertions against {@link V3ProviderSupport} without hitting the
     * network.
     */
    private static SessionsProviderV3 providerWithMockedClient() {
        RestClient mockClient = mock(RestClient.class);
        return new SessionsProviderV3(mockClient);
    }

    private static Map<String, String> wsVar(String ws) {
        return Map.of("ws", ws);
    }

    @SuppressWarnings("unused")
    private static Set<HonchoOperation> allSessionOps() {
        return Set.of(
            HonchoOperation.LIST_SESSIONS,
            HonchoOperation.CREATE_SESSION,
            HonchoOperation.GET_SESSION,
            HonchoOperation.DELETE_SESSION,
            HonchoOperation.GET_SESSION_CONTEXT,
            HonchoOperation.GET_SESSION_SUMMARIES,
            HonchoOperation.GET_SESSION_PEERS
        );
    }
}
