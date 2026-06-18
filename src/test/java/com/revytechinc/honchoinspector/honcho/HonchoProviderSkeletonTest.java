package com.revytechinc.honchoinspector.honcho;

import com.revytechinc.honchoinspector.model.HonchoContext;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract test for the {@link HonchoProvider} interface.
 *
 * <p>Drives a small in-test implementation that handles TWO operations on the
 * same resource ({@code peers/<id>/card} — the GET and POST variants). The
 * test asserts the four things the registry will rely on:
 *
 * <ol>
 *   <li>{@link HonchoProvider#operations()} returns the exact set the
 *       provider claims to serve — not a superset, not a subset.</li>
 *   <li>{@link HonchoProvider#supportedVersions()} is non-empty and
 *       contains the version the provider is written against.</li>
 *   <li>{@link HonchoProvider#pathTemplate(HonchoOperation)} returns
 *       per-operation paths that the registry can hand to a path
 *       variable substitutor.</li>
 *   <li>{@link HonchoProvider#httpMethod(HonchoOperation)} returns the
 *       correct verb for each operation.</li>
 *   <li>{@link HonchoProvider#execute} dispatches on the operation
 *       argument and returns the result that the per-op branch produced.</li>
 * </ol>
 *
 * <p>The provider under test is an inner class rather than a separate file
 * because the design is verified here, not exercised: real v3 providers
 * arrive in T10–T13. Keeping the test implementation local makes it
 * obvious that the test is documenting the contract, not shipping code.
 */
class HonchoProviderSkeletonTest {

    private static final HonchoContext CTX = new HonchoContext(
        "skeleton-api-key",
        "https://api.honcho.dev",
        "ws-1",
        "skeleton-user"
    );

    @Test
    void providerDeclaresExactlyTheOperationsItHandles() {
        PeerCardProvider provider = new PeerCardProvider();
        assertThat(provider.operations())
            .as("a multi-op provider must declare every op it serves, no more, no less")
            .containsExactlyInAnyOrder(HonchoOperation.GET_PEER_CARD, HonchoOperation.UPDATE_PEER_CARD);
    }

    @Test
    void providerAdvertisesAtLeastOneSupportedVersion() {
        PeerCardProvider provider = new PeerCardProvider();
        Set<HonchoApiVersion> versions = provider.supportedVersions();
        assertThat(versions)
            .as("registry needs at least one supported version to dispatch to this provider")
            .isNotEmpty()
            .contains(HonchoApiVersion.V3);
    }

    @Test
    void providerReturnsPerOperationPathTemplate() {
        PeerCardProvider provider = new PeerCardProvider();
        assertThat(provider.pathTemplate(HonchoOperation.GET_PEER_CARD))
            .isEqualTo("peers/{peerId}/card");
        assertThat(provider.pathTemplate(HonchoOperation.UPDATE_PEER_CARD))
            .isEqualTo("peers/{peerId}/card");
    }

    @Test
    void providerReturnsPerOperationHttpMethod() {
        PeerCardProvider provider = new PeerCardProvider();
        assertThat(provider.httpMethod(HonchoOperation.GET_PEER_CARD))
            .isEqualTo(HttpMethod.GET);
        assertThat(provider.httpMethod(HonchoOperation.UPDATE_PEER_CARD))
            .isEqualTo(HttpMethod.POST);
    }

    @Test
    void executeDispatchesOnOperationArgument() {
        PeerCardProvider provider = new PeerCardProvider();
        Map<String, String> pathVars = Map.of("peerId", "p-42");
        Map<String, String> query = new HashMap<>();

        Object getResult = provider.execute(
            HonchoOperation.GET_PEER_CARD, CTX, null, null, pathVars, query);
        Object updateResult = provider.execute(
            HonchoOperation.UPDATE_PEER_CARD, CTX, null, "calm", pathVars, query);

        assertThat(getResult)
            .as("GET_PEER_CARD branch should be exercised for the GET op")
            .isEqualTo("GET_PEER_CARD:p-42");
        assertThat(updateResult)
            .as("UPDATE_PEER_CARD branch should be exercised for the POST op")
            .isEqualTo("UPDATE_PEER_CARD:p-42:calm");
    }

    @Test
    void executeRejectsUnownedOperationWithHonchoCallException() {
        // A provider that does NOT own LIST_PEERS must fail loud if the
        // registry ever tries to route to it for that op. The skeleton
        // uses 501 because the failure is a configuration / dispatch
        // error, not an upstream Honcho error.
        PeerCardProvider provider = new PeerCardProvider();
        assertThatThrownBy(() -> provider.execute(
            HonchoOperation.LIST_PEERS, CTX, null, null, Map.of(), Map.of()))
            .isInstanceOf(HonchoCallException.class)
            .hasMessageContaining("does not handle")
            .hasMessageContaining("LIST_PEERS")
            .extracting("status").isEqualTo(501);
    }

    @Test
    void pathTemplateDefaultsFailLoudForUnknownOperation() {
        // A provider that does NOT override pathTemplate must throw
        // UnsupportedOperationException, never silently return null —
        // the registry relies on a non-null path to build URIs.
        HonchoProvider bareProvider = new HonchoProvider() {
            @Override public Set<HonchoOperation> operations() {
                return EnumSet.of(HonchoOperation.GET_PEER_CARD);
            }
            @Override public Set<HonchoApiVersion> supportedVersions() {
                return EnumSet.of(HonchoApiVersion.V3);
            }
            @Override public Object execute(HonchoOperation op, HonchoContext ctx, HonchoClient client,
                                            Object requestBody, Map<String, String> pathVars,
                                            Map<String, ?> queryParams) {
                return "noop";
            }
        };
        assertThatThrownBy(() -> bareProvider.pathTemplate(HonchoOperation.GET_PEER_CARD))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("path template")
            .hasMessageContaining("GET_PEER_CARD");
        assertThatThrownBy(() -> bareProvider.httpMethod(HonchoOperation.GET_PEER_CARD))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("HTTP method")
            .hasMessageContaining("GET_PEER_CARD");
    }

    /**
     * Tiny test-double provider that owns the two {@code peers/<id>/card}
     * operations. Demonstrates the multi-operation design — one class,
     * one resource, two related ops, with a switch-style dispatch in
     * {@code execute()}.
     */
    @Component
    static class PeerCardProvider implements HonchoProvider {

        @Override
        public Set<HonchoOperation> operations() {
            return EnumSet.of(HonchoOperation.GET_PEER_CARD, HonchoOperation.UPDATE_PEER_CARD);
        }

        @Override
        public Set<HonchoApiVersion> supportedVersions() {
            return EnumSet.of(HonchoApiVersion.V3);
        }

        @Override
        public String pathTemplate(HonchoOperation op) {
            return switch (op) {
                case GET_PEER_CARD, UPDATE_PEER_CARD -> "peers/{peerId}/card";
                default -> throw new UnsupportedOperationException(
                    "PeerCardProvider has no path template for " + op);
            };
        }

        @Override
        public HttpMethod httpMethod(HonchoOperation op) {
            return switch (op) {
                case GET_PEER_CARD -> HttpMethod.GET;
                case UPDATE_PEER_CARD -> HttpMethod.POST;
                default -> throw new UnsupportedOperationException(
                    "PeerCardProvider has no HTTP method for " + op);
            };
        }

        @Override
        public Object execute(
            HonchoOperation op,
            HonchoContext ctx,
            HonchoClient client,
            Object requestBody,
            Map<String, String> pathVars,
            Map<String, ?> queryParams
        ) {
            String peerId = pathVars == null ? "?" : pathVars.getOrDefault("peerId", "?");
            return switch (op) {
                case GET_PEER_CARD -> "GET_PEER_CARD:" + peerId;
                case UPDATE_PEER_CARD -> "UPDATE_PEER_CARD:" + peerId + ":" + requestBody;
                default -> throw new HonchoCallException(
                    "PeerCardProvider does not handle " + op, 501, null);
            };
        }
    }
}
