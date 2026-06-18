package com.revytechinc.honchoinspector.honcho;

import com.revytechinc.honchoinspector.model.HonchoContext;

import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HonchoClientFactoryTest {

    /**
     * Base stub: returns null from every HonchoClient method. Subclasses only
     * need to override {@link #supportedVersions()}. The factory tests never
     * invoke any of the 24 operation methods, so null is a fine placeholder.
     *
     * <p>Not annotated {@code @Component}: it is abstract, so Spring would
     * reject it as a bean anyway. The concrete fakes below carry the
     * annotation, matching the convention used by the real
     * {@code HonchoV3Client} once T14 lands.
     */
    abstract static class NoOpHonchoClient implements HonchoClient {
        @Override public Object listPeers(HonchoContext ctx, Map<String, ?> filters) { return null; }
        @Override public Object createPeer(HonchoContext ctx, Object createPeerRequest) { return null; }
        @Override public Object getPeerCard(HonchoContext ctx, String peerId) { return null; }
        @Override public Object updatePeerCard(HonchoContext ctx, String peerId, Object cardData) { return null; }
        @Override public Object getPeerRepresentation(HonchoContext ctx, String peerId) { return null; }
        @Override public Object peerChat(HonchoContext ctx, String peerId, Object chatRequest) { return null; }
        @Override public Object searchPeers(HonchoContext ctx, String peerId, Object searchRequest) { return null; }
        @Override public Object listPeerConclusions(HonchoContext ctx, String peerId, Map<String, ?> filters) { return null; }
        @Override public Object listPeerSessions(HonchoContext ctx, String peerId, Map<String, ?> filters) { return null; }
        @Override public Object queryPeerConclusions(HonchoContext ctx, String peerId, Object queryRequest) { return null; }
        @Override public Object listSessions(HonchoContext ctx, Map<String, ?> filters) { return null; }
        @Override public Object createSession(HonchoContext ctx, Object createSessionRequest) { return null; }
        @Override public Object getSession(HonchoContext ctx, String sessionId) { return null; }
        @Override public Object deleteSession(HonchoContext ctx, String sessionId) { return null; }
        @Override public Object listSessionMessages(HonchoContext ctx, String sessionId, Map<String, ?> filters) { return null; }
        @Override public Object addMessage(HonchoContext ctx, String sessionId, Object messageRequest) { return null; }
        @Override public Object getSessionContext(HonchoContext ctx, String sessionId, Integer tokens, Boolean summary) { return null; }
        @Override public Object getSessionSummaries(HonchoContext ctx, String sessionId) { return null; }
        @Override public Object getSessionPeers(HonchoContext ctx, String sessionId) { return null; }
        @Override public Object searchSessionMessages(HonchoContext ctx, String sessionId, Object searchRequest) { return null; }
        @Override public Object getQueueStatus(HonchoContext ctx) { return null; }
        @Override public Object searchMessages(HonchoContext ctx, Object searchRequest) { return null; }
        @Override public Object scheduleDream(HonchoContext ctx, String peerId, Object dreamRequest) { return null; }
        @Override public Object getWorkspaceInfo(HonchoContext ctx) { return null; }
        @Override public Object call(com.revytechinc.honchoinspector.honcho.HonchoOperation op, HonchoContext ctx, Object requestBody, Map<String, String> pathVars, Map<String, ?> queryParams) { return null; }
    }

    @Component
    static class FakeV3Client extends NoOpHonchoClient {
        @Override public Set<HonchoApiVersion> supportedVersions() {
            return Set.of(HonchoApiVersion.V3);
        }
    }

    @Component
    static class FakeV4Client extends NoOpHonchoClient {
        @Override public Set<HonchoApiVersion> supportedVersions() {
            return Set.of(HonchoApiVersion.V4);
        }
    }

    @Test
    void emptyFactoryThrowsForAllVersions() {
        HonchoClientFactory factory = new HonchoClientFactory(List.of());

        assertThatThrownBy(() -> factory.clientFor(HonchoApiVersion.V3))
            .isInstanceOf(UnsupportedHonchoVersionException.class)
            .hasMessageContaining("V3")
            .hasMessageContaining("Supported versions: []")
            .hasMessageContaining("docs/honcho-providers.md");
    }

    @Test
    void v3OnlyFactoryReturnsV3Client() {
        FakeV3Client v3 = new FakeV3Client();
        HonchoClientFactory factory = new HonchoClientFactory(List.of(v3));

        assertThat(factory.clientFor(HonchoApiVersion.V3)).isSameAs(v3);

        assertThatThrownBy(() -> factory.clientFor(HonchoApiVersion.V2))
            .isInstanceOf(UnsupportedHonchoVersionException.class)
            .hasMessageContaining("V2")
            .hasMessageContaining("docs/honcho-providers.md");

        assertThatThrownBy(() -> factory.clientFor(HonchoApiVersion.V4))
            .isInstanceOf(UnsupportedHonchoVersionException.class)
            .hasMessageContaining("V4")
            .hasMessageContaining("docs/honcho-providers.md");
    }

    @Test
    void multipleClientsDispatchByVersion() {
        FakeV3Client v3 = new FakeV3Client();
        FakeV4Client v4 = new FakeV4Client();
        HonchoClientFactory factory = new HonchoClientFactory(List.of(v3, v4));

        assertThat(factory.clientFor(HonchoApiVersion.V3)).isSameAs(v3);
        assertThat(factory.clientFor(HonchoApiVersion.V4)).isSameAs(v4);

        assertThatThrownBy(() -> factory.clientFor(HonchoApiVersion.V2))
            .isInstanceOf(UnsupportedHonchoVersionException.class);
    }

    @Test
    void resolveVersionPrefersOverride() {
        assertThat(HonchoClientFactory.resolveVersion("v4", HonchoApiVersion.V3))
            .isEqualTo(HonchoApiVersion.V4);
    }

    @Test
    void resolveVersionFallsBackToDefault() {
        assertThat(HonchoClientFactory.resolveVersion(null, HonchoApiVersion.V3))
            .isEqualTo(HonchoApiVersion.V3);
        assertThat(HonchoClientFactory.resolveVersion("", HonchoApiVersion.V3))
            .isEqualTo(HonchoApiVersion.V3);
        assertThat(HonchoClientFactory.resolveVersion("  ", HonchoApiVersion.V3))
            .isEqualTo(HonchoApiVersion.V3);
    }

    @Test
    void resolveVersionParsesCaseInsensitive() {
        assertThat(HonchoClientFactory.resolveVersion("V3", HonchoApiVersion.V2))
            .isEqualTo(HonchoApiVersion.V3);
    }

    @Test
    void resolveVersionThrowsOnInvalid() {
        assertThatThrownBy(() -> HonchoClientFactory.resolveVersion("v99", HonchoApiVersion.V3))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("v99")
            .hasMessageContaining("Supported versions")
            .hasMessageContaining("V2")
            .hasMessageContaining("V3")
            .hasMessageContaining("V4");
    }
}
