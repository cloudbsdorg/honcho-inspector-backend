package com.revytechinc.honchoinspector.honcho;

import com.revytechinc.honchoinspector.model.HonchoContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.TestPropertySource;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPI proof for {@link HonchoProvider}: a third party can drop in a custom
 * {@code HonchoProvider} bean and have it transparently replace a default
 * provider at dispatch time — without forking the production code.
 *
 * <p>The test wires a hand-written {@link CustomTestProviderV3} via a
 * nested {@link TestConfiguration} so the production
 * {@link HonchoV3Client} picks it up as just another
 * {@code HonchoProvider} bean at construction time. The custom provider
 * claims {@link HonchoOperation#LIST_PEERS} (the same op the default
 * {@code PeersProviderV3} claims) and returns a unique marker object from
 * its {@code execute(...)} method instead of performing real HTTP.
 *
 * <h2>How the override works</h2>
 * {@link HonchoProviderRegistry} sorts providers alphabetically by fully
 * qualified class name and uses {@code putIfAbsent} for each claimed
 * operation. {@code CustomTestProviderV3}'s package
 * ({@code com.revytechinc.honchoinspector.honcho}) sorts BEFORE
 * {@code PeersProviderV3}'s ({@code .honcho.v3}), so the custom provider
 * wins the collision for {@code LIST_PEERS} deterministically — Spring's
 * bean ordering is irrelevant.
 *
 * <h2>Why {@code spring.main.allow-bean-definition-overriding=true}</h2>
 * Scoped to this test only via {@link SpringBootTest#properties()}, NOT
 * enabled globally. The property is a safety net in case the bean
 * registration order shifts in a future Spring upgrade: a collision on
 * the same bean name would otherwise fail the test at context load with
 * {@code BeanDefinitionOverrideException}, and the override is the
 * whole point of this test.
 *
 * <h2>Why no Mock Honcho</h2>
 * The custom provider's {@code execute(...)} returns a marker
 * {@link Map} directly — it never makes an HTTP call. Bypassing the
 * {@code HonchoMockConfig} fixture client keeps the assertion focused on
 * "the registry dispatched to MY provider" rather than "the mock
 * returned its canned payload".
 */
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@TestPropertySource(properties = {
    "HONCHO_DB_PATH=jdbc:sqlite::memory:",
    "honcho.crypto-key=dGVzdC1rZXktMzItYnl0ZXMtZm9yLWVuY3J5cHRpb24="
})
class CustomProviderSpiTest {

    private static final Map<String, Object> CUSTOM_MARKER = Map.of(
        "fromCustomProvider", true,
        "sessionId", "marker"
    );

    @Autowired
    HonchoClientFactory factory;

    @Autowired
    CustomTestProviderV3 customTestProvider;

    @Autowired
    ApplicationContext applicationContext;

    /**
     * SPI proof (primary test): calling {@code factory.clientFor(V3).listPeers(...)}
     * must return {@link #CUSTOM_MARKER}, not the result of
     * {@code PeersProviderV3}'s real HTTP path. If the default provider
     * were winning, the test would throw an
     * {@code HonchoCallException} (no real Honcho server running) or
     * return a different object.
     */
    @Test
    void customProviderWinsForListPeers() {
        HonchoContext ctx = new HonchoContext(
            "hnc_test_key",
            "https://api.honcho.dev",
            "ws-1",
            "revytech",
            HonchoApiVersion.V3
        );

        Object result = factory.clientFor(HonchoApiVersion.V3).listPeers(ctx, Map.of());

        assertThat(result)
            .as("CustomTestProviderV3 must override PeersProviderV3 for LIST_PEERS, "
                + "proving the user-extensibility SPI works end-to-end")
            .isEqualTo(CUSTOM_MARKER);
    }

    /**
     * The custom provider's metadata is visible to the dispatch layer.
     * Asserting operations() / supportedVersions() / pathTemplate() /
     * httpMethod() makes the test's contract explicit: anyone reading
     * the failure log sees exactly which properties the registry saw
     * when it built the dispatch table.
     */
    @Test
    void customProviderAdvertisesTheExpectedMetadata() {
        assertThat(customTestProvider.operations())
            .as("custom provider must claim exactly LIST_PEERS to trigger the collision")
            .containsExactly(HonchoOperation.LIST_PEERS);
        assertThat(customTestProvider.supportedVersions())
            .as("custom provider must claim V3 so the V3 registry picks it up")
            .containsExactly(HonchoApiVersion.V3);
        assertThat(customTestProvider.pathTemplate(HonchoOperation.LIST_PEERS))
            .as("path template must be intentionally different from PeersProviderV3's "
                + "'v3/workspaces/{ws}/peers/list' \u2014 the path difference is what "
                + "makes the override observable in a real HTTP trace")
            .isEqualTo("custom/special/path");
        assertThat(customTestProvider.httpMethod(HonchoOperation.LIST_PEERS))
            .as("HTTP method must be intentionally different from PeersProviderV3's POST "
                + "\u2014 the verb difference is the second observable proof of the override")
            .isEqualTo(HttpMethod.GET);
    }

    /**
     * Both the custom provider and the default {@code PeersProviderV3} are
     * present in the context. The registry must have picked the custom
     * one for {@code LIST_PEERS} and left the default in place for the
     * other 4 ops {@code PeersProviderV3} claims. Asserting the default
     * provider is still registered (not silently dropped) confirms the
     * override is per-operation, not all-or-nothing.
     */
    @Test
    void defaultPeersProviderStillExistsAlongsideCustomProvider() {
        Map<String, HonchoProvider> providers = applicationContext.getBeansOfType(HonchoProvider.class);
        boolean customPresent = providers.values().stream()
            .anyMatch(p -> p instanceof CustomTestProviderV3);
        boolean defaultPresent = providers.values().stream()
            .anyMatch(p -> p.getClass().getName().endsWith(".v3.PeersProviderV3"));

        assertThat(customPresent)
            .as("CustomTestProviderV3 must be registered as a HonchoProvider bean")
            .isTrue();
        assertThat(defaultPresent)
            .as("default PeersProviderV3 must still be registered alongside the custom one "
                + "\u2014 the override is per-operation, not whole-bean replacement")
            .isTrue();
    }

    /**
     * Test-only {@link TestConfiguration} that provides a
     * {@link CustomTestProviderV3} bean. Marked {@link Primary @Primary}
     * as a safety net: if a future change ever introduces a second
     * custom provider of the same logical type, the {@code @Primary}
     * resolution path still picks THIS instance for any field autowire
     * by name. The dispatch path doesn't care about {@code @Primary}
     * (the registry sorts alphabetically regardless), so this is purely
     * defensive.
     */
    @TestConfiguration
    static class CustomProviderConfig {

        @Bean
        @Primary
        public CustomTestProviderV3 customTestProviderV3() {
            return new CustomTestProviderV3();
        }
    }

    /**
     * Hand-written {@link HonchoProvider} that overrides the default
     * {@code PeersProviderV3} for {@link HonchoOperation#LIST_PEERS}.
     *
     * <p>The class is package-private and lives inside the test class
     * (as a nested class) so it cannot leak into production code. Its
     * {@code execute(...)} method returns a hard-coded marker
     * {@link Map} instead of performing real HTTP, so the SPI proof is
     * observable without a live Honcho server.
     *
     * <p><strong>Why the path and verb differ from the default:</strong>
     * the default {@code PeersProviderV3.LIST_PEERS} uses {@code POST
     * /v3/workspaces/{ws}/peers/list}. Picking a visibly different
     * {@code pathTemplate} ({@code "custom/special/path"}) and verb
     * ({@code GET}) means anyone debugging this test can tell at a
     * glance which provider actually handled a request — the response
     * marker alone proves the dispatch, but the path/verb combo is what
     * would surface in a real upstream HTTP log.
     */
    static class CustomTestProviderV3 implements HonchoProvider {

        private static final Set<HonchoOperation> OPS = EnumSet.of(HonchoOperation.LIST_PEERS);

        @Override
        public Set<HonchoOperation> operations() {
            return OPS;
        }

        @Override
        public Set<HonchoApiVersion> supportedVersions() {
            return EnumSet.of(HonchoApiVersion.V3);
        }

        @Override
        public String pathTemplate(HonchoOperation op) {
            return switch (op) {
                case LIST_PEERS -> "custom/special/path";
                default -> throw new UnsupportedOperationException(
                    "CustomTestProviderV3 has no path template for " + op);
            };
        }

        @Override
        public HttpMethod httpMethod(HonchoOperation op) {
            return switch (op) {
                case LIST_PEERS -> HttpMethod.GET;
                default -> throw new UnsupportedOperationException(
                    "CustomTestProviderV3 has no HTTP method for " + op);
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
            return CUSTOM_MARKER;
        }
    }
}
