package com.revytechinc.honchoinspector.honcho.v3;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClient;

import com.revytechinc.honchoinspector.honcho.HonchoApiVersion;
import com.revytechinc.honchoinspector.honcho.HonchoOperation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Structural unit tests for {@link SessionsProviderV3}. Seven per-op
 * tests, one for each session-resource operation the provider owns.
 *
 * <p>Each test verifies the four metadata points the
 * {@code HonchoProviderRegistry} relies on:
 * <ol>
 *   <li>{@code operations()} advertises the operation.</li>
 *   <li>{@code supportedVersions()} returns exactly {@code {V3}}.</li>
 *   <li>{@code pathTemplate(op)} returns the expected v3 path template.</li>
 *   <li>{@code httpMethod(op)} returns the expected HTTP verb.</li>
 * </ol>
 *
 * <p>T27 is structural-only by design: it does NOT call {@code execute()}
 * or exercise HTTP. Behaviour integration tests live in T11's
 * {@code SessionsProviderV3Test}; end-to-end workflow tests live in
 * T26's {@code HonchoWorkflowIntegrationTest}.
 */
class SessionsProviderV3UnitTest {

    private static SessionsProviderV3 newProvider() {
        return new SessionsProviderV3(mock(RestClient.class));
    }

    @Test
    void listSessions_advertisesV3PostOnSessionsEndpoint() {
        SessionsProviderV3 provider = newProvider();

        assertThat(provider.operations()).contains(HonchoOperation.LIST_SESSIONS);
        assertThat(provider.supportedVersions())
            .as("SessionsProviderV3 is v3-only")
            .isEqualTo(Set.of(HonchoApiVersion.V3));
        assertThat(provider.pathTemplate(HonchoOperation.LIST_SESSIONS))
            .isEqualTo("v3/workspaces/{ws}/sessions");
        assertThat(provider.httpMethod(HonchoOperation.LIST_SESSIONS))
            .as("v2→v3 contract change: LIST_SESSIONS is now POST")
            .isEqualTo(HttpMethod.POST);
    }

    @Test
    void createSession_advertisesV3PostOnSessionsEndpoint() {
        SessionsProviderV3 provider = newProvider();

        assertThat(provider.operations()).contains(HonchoOperation.CREATE_SESSION);
        assertThat(provider.supportedVersions())
            .isEqualTo(Set.of(HonchoApiVersion.V3));
        assertThat(provider.pathTemplate(HonchoOperation.CREATE_SESSION))
            .isEqualTo("v3/workspaces/{ws}/sessions");
        assertThat(provider.httpMethod(HonchoOperation.CREATE_SESSION))
            .isEqualTo(HttpMethod.POST);
    }

    @Test
    void getSession_advertisesV3GetOnSessionIdEndpoint() {
        SessionsProviderV3 provider = newProvider();

        assertThat(provider.operations()).contains(HonchoOperation.GET_SESSION);
        assertThat(provider.supportedVersions())
            .isEqualTo(Set.of(HonchoApiVersion.V3));
        assertThat(provider.pathTemplate(HonchoOperation.GET_SESSION))
            .isEqualTo("v3/workspaces/{ws}/sessions/{sessionId}");
        assertThat(provider.httpMethod(HonchoOperation.GET_SESSION))
            .isEqualTo(HttpMethod.GET);
    }

    @Test
    void deleteSession_advertisesV3DeleteOnSessionIdEndpoint() {
        SessionsProviderV3 provider = newProvider();

        assertThat(provider.operations()).contains(HonchoOperation.DELETE_SESSION);
        assertThat(provider.supportedVersions())
            .isEqualTo(Set.of(HonchoApiVersion.V3));
        assertThat(provider.pathTemplate(HonchoOperation.DELETE_SESSION))
            .isEqualTo("v3/workspaces/{ws}/sessions/{sessionId}");
        assertThat(provider.httpMethod(HonchoOperation.DELETE_SESSION))
            .isEqualTo(HttpMethod.DELETE);
    }

    @Test
    void getSessionContext_advertisesV3GetOnContextSubpath() {
        SessionsProviderV3 provider = newProvider();

        assertThat(provider.operations()).contains(HonchoOperation.GET_SESSION_CONTEXT);
        assertThat(provider.supportedVersions())
            .isEqualTo(Set.of(HonchoApiVersion.V3));
        assertThat(provider.pathTemplate(HonchoOperation.GET_SESSION_CONTEXT))
            .isEqualTo("v3/workspaces/{ws}/sessions/{sessionId}/context");
        assertThat(provider.httpMethod(HonchoOperation.GET_SESSION_CONTEXT))
            .isEqualTo(HttpMethod.GET);
    }

    @Test
    void getSessionSummaries_advertisesV3GetOnSummariesSubpath() {
        SessionsProviderV3 provider = newProvider();

        assertThat(provider.operations()).contains(HonchoOperation.GET_SESSION_SUMMARIES);
        assertThat(provider.supportedVersions())
            .isEqualTo(Set.of(HonchoApiVersion.V3));
        assertThat(provider.pathTemplate(HonchoOperation.GET_SESSION_SUMMARIES))
            .isEqualTo("v3/workspaces/{ws}/sessions/{sessionId}/summaries");
        assertThat(provider.httpMethod(HonchoOperation.GET_SESSION_SUMMARIES))
            .isEqualTo(HttpMethod.GET);
    }

    @Test
    void getSessionPeers_advertisesV3GetOnPeersSubpath() {
        SessionsProviderV3 provider = newProvider();

        assertThat(provider.operations()).contains(HonchoOperation.GET_SESSION_PEERS);
        assertThat(provider.supportedVersions())
            .isEqualTo(Set.of(HonchoApiVersion.V3));
        assertThat(provider.pathTemplate(HonchoOperation.GET_SESSION_PEERS))
            .isEqualTo("v3/workspaces/{ws}/sessions/{sessionId}/peers");
        assertThat(provider.httpMethod(HonchoOperation.GET_SESSION_PEERS))
            .isEqualTo(HttpMethod.GET);
    }
}
