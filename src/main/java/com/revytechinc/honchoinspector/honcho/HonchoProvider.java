package com.revytechinc.honchoinspector.honcho;

import com.revytechinc.honchoinspector.model.HonchoContext;
import org.springframework.http.HttpMethod;

import java.util.Map;
import java.util.Set;

/**
 * One logical unit of Honcho behaviour: a small, related set of operations on
 * a single resource, e.g. "all session message operations" or "all peer
 * representation operations".
 *
 * <p>Each Spring-managed {@code @Component} that implements this interface
 * declares which {@link HonchoOperation}s it serves via
 * {@link #operations()} and which Honcho API versions it understands via
 * {@link #supportedVersions()}. The provider registry (built in T15)
 * dispatches incoming calls by consulting those two sets.
 *
 * <h2>Why multi-operation and not one-per-op</h2>
 * Honcho v3 has ~24 endpoints but groups them naturally into ~7–9 resource
 * clusters (peers, peer cards, sessions, session messages, session context,
 * search, workspace, dreams). A multi-operation provider keeps related code
 * (path templates, response shaping, error mapping) in a single class so
 * that, for example, all session-message operations evolve together as the
 * Honcho contract changes. Splitting one-op-per-file would scatter that
 * cohesion across 24 files with no real reuse.
 *
 * <h2>What an implementer must do</h2>
 * <ul>
 *   <li>Return the exact set of operations from {@link #operations()}. The
 *       registry uses this set to decide who handles a given call; an
 *       empty or mismatched set would be a configuration bug.</li>
 *   <li>Implement {@link #execute} to perform the actual upstream HTTP call.
 *       Providers should dispatch on the {@code op} argument using a
 *       {@code switch} expression — the default branch should throw
 *       {@link HonchoCallException} with status 501 so registry bugs are
 *       visible, not silently dropped.</li>
 *   <li>Override {@link #pathTemplate} and {@link #httpMethod} when the
 *       provider needs per-operation values; the defaults throw
 *       {@link UnsupportedOperationException} so missing overrides fail loud
 *       at the first test that exercises them.</li>
 * </ul>
 *
 * <h2>Stable contract</h2>
 * The interface is plain Java; it carries no Spring annotations (despite
 * being {@code @Component}-friendly by intent) and no version-specific
 * logic. Honcho v3 providers live in a sub-package and depend on this
 * interface, not the other way around.
 */
public interface HonchoProvider {

    /**
     * The set of {@link HonchoOperation}s this provider knows how to execute.
     * Typically a small, related cluster (3–6 operations on a single
     * resource). Must be non-empty and stable for the provider's lifetime.
     */
    Set<HonchoOperation> operations();

    /**
     * The set of Honcho API versions this provider can service. Most
     * implementations will return {@code Set.of(HonchoApiVersion.V3)}; the
     * set is exposed so the registry can refuse to dispatch a v4 call to
     * a v3-only provider with a clear error.
     */
    Set<HonchoApiVersion> supportedVersions();

    /**
     * Execute the given operation against the upstream Honcho service.
     *
     * <p>Implementations are expected to:
     * <ol>
     *   <li>Resolve the path via {@link #pathTemplate} and the HTTP method
     *       via {@link #httpMethod} (or an internal switch for performance).</li>
     *   <li>Apply any required path-variable substitution from
     *       {@code pathVars}.</li>
     *   <li>Serialize {@code requestBody} (if non-null) as JSON.</li>
     *   <li>Apply the per-profile auth headers from {@code ctx} (API key,
     *       {@code X-Honcho-User-Name}).</li>
     *   <li>Translate any non-2xx response or transport failure into a
     *       {@link HonchoCallException} — never let raw Spring or HTTP
     *       exceptions leak to the controller.</li>
     * </ol>
     *
     * <p>The {@code client} parameter is the typed Honcho client (T7) that
     * exposes a method per {@link HonchoOperation}; the provider may delegate
     * to it or bypass it in favour of a pre-built {@code RestClient}. Both
     * are accepted so individual providers can optimise the hot path
     * without breaking the registry contract.
     *
     * @param op          the operation being invoked. Guaranteed to be in
     *                    {@link #operations()} by the registry.
     * @param ctx         the per-request Honcho context (apiKey, baseUrl,
     *                    workspaceId, userName).
     * @param client      the typed Honcho client to delegate to.
     * @param requestBody the deserialized request body, or {@code null} for
     *                    GET / DELETE.
     * @param pathVars    path variables keyed by template placeholder name,
     *                    e.g. {@code Map.of("peerId", "abc")}.
     * @param queryParams query string parameters, or {@code null} / empty.
     * @return the deserialized response body, or {@code null} for 204
     *         responses.
     * @throws HonchoCallException for every failure mode the caller should
     *         be able to render.
     */
    Object execute(
        HonchoOperation op,
        HonchoContext ctx,
        HonchoClient client,
        Object requestBody,
        Map<String, String> pathVars,
        Map<String, ?> queryParams
    ) throws HonchoCallException;

    /**
     * The URL path template (relative to the workspace base, e.g.
     * {@code "peers/{peerId}/card"}) for the given operation.
     *
     * <p>The default implementation throws {@link UnsupportedOperationException}
     * so providers that need per-operation paths are forced to override it
     * — a missing override is a test-time failure, not a runtime NPE.
     */
    default String pathTemplate(HonchoOperation op) {
        throw new UnsupportedOperationException(
            "HonchoProvider " + getClass().getSimpleName()
            + " does not declare a path template for operation " + op
        );
    }

    /**
     * The HTTP method to use when calling the upstream Honcho service for
     * the given operation. The default throws
     * {@link UnsupportedOperationException} — same rationale as
     * {@link #pathTemplate}.
     */
    default HttpMethod httpMethod(HonchoOperation op) {
        throw new UnsupportedOperationException(
            "HonchoProvider " + getClass().getSimpleName()
            + " does not declare an HTTP method for operation " + op
        );
    }
}
