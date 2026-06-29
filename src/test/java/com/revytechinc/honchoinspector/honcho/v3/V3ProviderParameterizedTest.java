package com.revytechinc.honchoinspector.honcho.v3;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.client.RestClient;

import com.revytechinc.honchoinspector.honcho.HonchoApiVersion;
import com.revytechinc.honchoinspector.honcho.HonchoOperation;
import com.revytechinc.honchoinspector.honcho.HonchoProvider;
import com.revytechinc.honchoinspector.honcho.HonchoProviderRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Aggregator test for all 9 V3 {@link HonchoProvider} beans. Iterates
 * the providers through a {@code @ParameterizedTest} and asserts the
 * cross-cutting invariants the registry depends on, then asserts the
 * registry itself:
 * <ul>
 *   <li>{@code providerCount() == 9} — exactly the nine V3 provider
 *       beans contribute to the V3 registry.</li>
 *   <li>Sum of {@code operations().size()} across all 9 providers is
 *       {@code 29} — every {@link HonchoOperation} is covered.</li>
 *   <li>{@code coveredOperations()} returns all 29 enum constants.</li>
 * </ul>
 *
 * <p>Complements the per-op structural tests in
 * {@code PeersProviderV3UnitTest}, {@code SessionsProviderV3UnitTest},
 * {@code MessagesProviderV3UnitTest}, and {@code MiscProvidersV3UnitTest}.
 * Where those tests pin the per-operation metadata, this test pins the
 * aggregate wiring: if any provider is dropped, renamed, or stops
 * advertising its operations, this test catches it.
 */
class V3ProviderParameterizedTest {

    private static List<HonchoProvider> allV3Providers() {
        RestClient.Builder builder = mock(RestClient.Builder.class);
        when(builder.build()).thenReturn(mock(RestClient.class));
        RestClient client = mock(RestClient.class);

        return List.of(
            new PeersProviderV3(client),
            new PeerQueryProviderV3(client),
            new ConclusionsProviderV3(client),
            new SessionsProviderV3(client),
            new MessagesProviderV3(client),
            new WorkspaceProviderV3(builder),
            new QueueStatusProviderV3(builder),
            new SearchProviderV3(builder),
            new DreamsProviderV3(builder)
        );
    }

    private static Stream<HonchoProvider> v3ProvidersStream() {
        return allV3Providers().stream();
    }

    @ParameterizedTest
    @MethodSource("v3ProvidersStream")
    void everyV3Provider_advertisesV3AndAtLeastOneOperation(HonchoProvider provider) {
        assertThat(provider.supportedVersions())
            .as("V3 providers must claim HonchoApiVersion.V3")
            .contains(HonchoApiVersion.V3);
        assertThat(provider.operations())
            .as("V3 providers must advertise at least one operation")
            .isNotEmpty();
    }

    @ParameterizedTest
    @MethodSource("v3ProvidersStream")
    void everyV3Provider_isV3OnlyAndDoesNotClaimOtherVersions(HonchoProvider provider) {
        assertThat(provider.supportedVersions())
            .as("V3 providers must not claim V2 or V4 — they're v3-only")
            .doesNotContain(HonchoApiVersion.V2, HonchoApiVersion.V4);
    }

    @Test
    void registry_withAllNineProvidersHasProviderCountNine() {
        HonchoProviderRegistry registry =
            new HonchoProviderRegistry(HonchoApiVersion.V3, allV3Providers());

        assertThat(registry.providerCount())
            .as("Registry must register all 9 distinct V3 provider instances")
            .isEqualTo(9);
        assertThat(registry.version())
            .as("Registry is built for V3")
            .isEqualTo(HonchoApiVersion.V3);
    }

    @Test
    void registry_totalCoveredOperationsAcrossAllProvidersIs29() {
        List<HonchoProvider> providers = allV3Providers();
        int totalOps = providers.stream()
            .mapToInt(p -> p.operations().size())
            .sum();

        assertThat(totalOps)
            .as("9 providers must collectively cover all 29 HonchoOperation constants")
            .isEqualTo(29);
        assertThat(HonchoOperation.values())
            .as("HonchoOperation enum sanity check — 29 constants")
            .hasSize(29);
    }

    @Test
    void registry_coveredOperationsContainsAll29EnumConstants() {
        HonchoProviderRegistry registry =
            new HonchoProviderRegistry(HonchoApiVersion.V3, allV3Providers());

        assertThat(registry.coveredOperations())
            .as("Registry must dispatch every HonchoOperation")
            .containsExactlyInAnyOrder(HonchoOperation.values());
    }
}
