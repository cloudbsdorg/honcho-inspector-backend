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
 * Structural unit tests for {@link MessagesProviderV3}. Three per-op
 * tests, one for each session-message operation the provider owns.
 *
 * <p>Each test verifies the four metadata points the
 * {@code HonchoProviderRegistry} relies on:
 * <ol>
 *   <li>{@code operations()} advertises the operation.</li>
 *   <li>{@code supportedVersions()} returns exactly {@code {V3}}.</li>
 *   <li>{@code pathTemplate(op)} returns the expected workspace-relative
 *       v3 path template (the dispatcher prepends the v3 prefix and
 *       workspace segment at call time).</li>
 *   <li>{@code httpMethod(op)} returns the expected HTTP verb.</li>
 * </ol>
 *
 * <p>Note that {@code LIST_SESSION_MESSAGES} stayed a {@code GET} in v3
 * (unlike {@code LIST_PEERS} / {@code LIST_SESSIONS}, which flipped to
 * {@code POST .../list}). The v3 contract preserved the legacy GET
 * semantics for the messages endpoint on purpose.
 *
 * <p>T27 is structural-only by design: it does NOT call {@code execute()}
 * or exercise HTTP. Behaviour integration tests live in T12's
 * {@code MessagesProviderV3Test}; end-to-end workflow tests live in
 * T26's {@code HonchoWorkflowIntegrationTest}.
 */
class MessagesProviderV3UnitTest {

    private static final String MESSAGES_PATH = "workspaces/{ws}/sessions/{sessionId}/messages";
    private static final String SEARCH_PATH   = "workspaces/{ws}/sessions/{sessionId}/search";

    private static MessagesProviderV3 newProvider() {
        return new MessagesProviderV3(mock(RestClient.class));
    }

    @Test
    void listSessionMessages_advertisesV3GetOnMessagesPath() {
        MessagesProviderV3 provider = newProvider();

        assertThat(provider.operations()).contains(HonchoOperation.LIST_SESSION_MESSAGES);
        assertThat(provider.supportedVersions())
            .as("MessagesProviderV3 is v3-only")
            .isEqualTo(Set.of(HonchoApiVersion.V3));
        assertThat(provider.pathTemplate(HonchoOperation.LIST_SESSION_MESSAGES))
            .isEqualTo(MESSAGES_PATH);
        assertThat(provider.httpMethod(HonchoOperation.LIST_SESSION_MESSAGES))
            .as("LIST_SESSION_MESSAGES stayed GET in v3 (unlike other list endpoints)")
            .isEqualTo(HttpMethod.GET);
    }

    @Test
    void addMessage_advertisesV3PostOnMessagesPath() {
        MessagesProviderV3 provider = newProvider();

        assertThat(provider.operations()).contains(HonchoOperation.ADD_MESSAGE);
        assertThat(provider.supportedVersions())
            .isEqualTo(Set.of(HonchoApiVersion.V3));
        assertThat(provider.pathTemplate(HonchoOperation.ADD_MESSAGE))
            .isEqualTo(MESSAGES_PATH);
        assertThat(provider.httpMethod(HonchoOperation.ADD_MESSAGE))
            .isEqualTo(HttpMethod.POST);
    }

    @Test
    void searchSessionMessages_advertisesV3PostOnSearchPath() {
        MessagesProviderV3 provider = newProvider();

        assertThat(provider.operations()).contains(HonchoOperation.SEARCH_SESSION_MESSAGES);
        assertThat(provider.supportedVersions())
            .isEqualTo(Set.of(HonchoApiVersion.V3));
        assertThat(provider.pathTemplate(HonchoOperation.SEARCH_SESSION_MESSAGES))
            .isEqualTo(SEARCH_PATH);
        assertThat(provider.httpMethod(HonchoOperation.SEARCH_SESSION_MESSAGES))
            .isEqualTo(HttpMethod.POST);
    }
}
