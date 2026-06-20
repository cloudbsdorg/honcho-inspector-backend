package com.revytechinc.honchoinspector.honcho.v3;

import com.revytechinc.honchoinspector.honcho.HonchoApiVersion;
import com.revytechinc.honchoinspector.honcho.HonchoOperation;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Contract test for {@link MessagesProviderV3}.
 *
 * <p>Verifies the four things the registry will rely on for each of the
 * three message operations:
 * <ol>
 *   <li>The provider's {@code operations()} set contains the op.</li>
 *   <li>{@code supportedVersions()} advertises {@link HonchoApiVersion#V3}.</li>
 *   <li>{@code pathTemplate(op)} returns the expected workspace-relative
 *       Honcho v3 path.</li>
 *   <li>{@code httpMethod(op)} returns the expected HTTP verb.</li>
 * </ol>
 *
 * <p>The {@code RestClient.Builder} is mocked so the provider can be
 * instantiated without touching a network or a Spring context; the
 * actual HTTP behavior is verified by integration tests in a later task.
 * One test per operation, per the T12 spec.
 */
class MessagesProviderV3Test {

    private static final String MESSAGES_PATH = "workspaces/{ws}/sessions/{sessionId}/messages";
    private static final String SEARCH_PATH   = "workspaces/{ws}/sessions/{sessionId}/search";

    private static MessagesProviderV3 newProvider() {
        return new MessagesProviderV3(mock(RestClient.class));
    }

    @Test
    void listSessionMessagesIsDeclaredGetOnMessagesPath() {
        MessagesProviderV3 provider = newProvider();

        assertThat(provider.operations())
            .as("LIST_SESSION_MESSAGES must be in the provider's operations set")
            .contains(HonchoOperation.LIST_SESSION_MESSAGES);

        assertThat(provider.supportedVersions())
            .as("MessagesProviderV3 is a v3-only provider")
            .containsExactly(HonchoApiVersion.V3);

        assertThat(provider.pathTemplate(HonchoOperation.LIST_SESSION_MESSAGES))
            .as("LIST_SESSION_MESSAGES path is the v3 session-messages collection")
            .isEqualTo(MESSAGES_PATH);

        assertThat(provider.httpMethod(HonchoOperation.LIST_SESSION_MESSAGES))
            .as("LIST_SESSION_MESSAGES stayed a GET in v3 (unlike other list endpoints)")
            .isEqualTo(HttpMethod.GET);
    }

    @Test
    void addMessageIsDeclaredPostOnMessagesPath() {
        MessagesProviderV3 provider = newProvider();

        assertThat(provider.operations())
            .as("ADD_MESSAGE must be in the provider's operations set")
            .contains(HonchoOperation.ADD_MESSAGE);

        assertThat(provider.supportedVersions())
            .as("MessagesProviderV3 is a v3-only provider")
            .containsExactly(HonchoApiVersion.V3);

        assertThat(provider.pathTemplate(HonchoOperation.ADD_MESSAGE))
            .as("ADD_MESSAGE path matches the LIST path (same collection, write verb)")
            .isEqualTo(MESSAGES_PATH);

        assertThat(provider.httpMethod(HonchoOperation.ADD_MESSAGE))
            .as("ADD_MESSAGE is a POST to append messages")
            .isEqualTo(HttpMethod.POST);
    }

    @Test
    void searchSessionMessagesIsDeclaredPostOnSearchPath() {
        MessagesProviderV3 provider = newProvider();

        assertThat(provider.operations())
            .as("SEARCH_SESSION_MESSAGES must be in the provider's operations set")
            .contains(HonchoOperation.SEARCH_SESSION_MESSAGES);

        assertThat(provider.supportedVersions())
            .as("MessagesProviderV3 is a v3-only provider")
            .containsExactly(HonchoApiVersion.V3);

        assertThat(provider.pathTemplate(HonchoOperation.SEARCH_SESSION_MESSAGES))
            .as("SEARCH_SESSION_MESSAGES path is the session-scoped search endpoint")
            .isEqualTo(SEARCH_PATH);

        assertThat(provider.httpMethod(HonchoOperation.SEARCH_SESSION_MESSAGES))
            .as("SEARCH_SESSION_MESSAGES is a POST carrying a search request body")
            .isEqualTo(HttpMethod.POST);
    }
}
