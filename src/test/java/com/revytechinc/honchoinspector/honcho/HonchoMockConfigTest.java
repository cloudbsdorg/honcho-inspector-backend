package com.revytechinc.honchoinspector.honcho;

import tools.jackson.databind.ObjectMapper;
import com.revytechinc.honchoinspector.IntegrationTestBase;
import com.revytechinc.honchoinspector.model.HonchoContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test for the T23 {@link HonchoMockConfig} scaffold.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>The {@code @TestConfiguration} wires up correctly under a real
 *       {@code @SpringBootTest} context.</li>
 *   <li>The fixture-backed mock {@link HonchoClient} returns the same
 *       payload for the typed method and the generic {@code call(...)}
 *       dispatch entry point.</li>
 *   <li>The {@code @Primary} factory bean is the one autowired by the
 *       application (not the {@code @MockitoBean}-replaced production
 *       factory).</li>
 * </ul>
 *
 * <p>Extends {@link IntegrationTestBase} so the full Spring context
 * (in-memory SQLite, MockMvc, the mock factory) is wired around the
 * assertions below.
 */
class HonchoMockConfigTest extends IntegrationTestBase {

    @Autowired ApplicationContext ctx;
    @Autowired ObjectMapper json;

    private HonchoContext dummyCtx() {
        return new HonchoContext("hnc_test_key", "https://api.honcho.dev",
                                 "ws-1", "revytech", HonchoApiVersion.V3);
    }

    @Test
    void mockClientListPeersReturnsFixture() {
        HonchoClient client = honchoFactory.clientFor(HonchoApiVersion.V3);
        Object response = client.listPeers(dummyCtx(), Map.of());

        assertThat(response).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response;
        assertThat(data).containsKey("items");
        assertThat(data).containsKey("total");
        assertThat(data.get("total")).isEqualTo(11);
        @SuppressWarnings("unchecked")
        var items = (java.util.List<Map<String, Object>>) data.get("items");
        assertThat(items).hasSize(11);
        assertThat(items.get(0)).containsEntry("id", "mlapointe");
    }

    @Test
    void mockClientGetWorkspaceInfoReturnsFixture() {
        HonchoClient client = honchoFactory.clientFor(HonchoApiVersion.V3);
        Object response = client.getWorkspaceInfo(dummyCtx());

        assertThat(response).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response;
        assertThat(data).containsEntry("id", "fixture-ws");
    }

    @Test
    void mockClientCallDispatchesToFixture() {
        HonchoClient client = honchoFactory.clientFor(HonchoApiVersion.V3);
        Object viaCall = client.call(
            HonchoOperation.LIST_PEERS, dummyCtx(), null, null, null);
        Object viaTyped = client.listPeers(dummyCtx(), Map.of());

        assertThat(viaCall)
            .as("call(LIST_PEERS, ...) and listPeers(...) must return identical payloads")
            .isEqualTo(viaTyped);
    }

    @Test
    void primaryFactoryBeanIsAutowired() {
        assertThat(honchoFactory.clientFor(HonchoApiVersion.V3))
            .as("the autowired factory must serve the V3 dispatch slot")
            .isNotNull();
        assertThat(ctx.getBean(HonchoClientFactory.class))
            .as("the @Primary factory from HonchoMockConfig must be the one Spring autowires")
            .isSameAs(honchoFactory);
    }

    @Test
    void mockClientReturnsEmptyForOpsWithoutFixture() {
        HonchoClient client = honchoFactory.clientFor(HonchoApiVersion.V3);
        Object response = client.deleteSession(dummyCtx(), "s-1");
        assertThat(response).isInstanceOf(Map.class);
        assertThat((Map<?, ?>) response).isEmpty();
    }
}
