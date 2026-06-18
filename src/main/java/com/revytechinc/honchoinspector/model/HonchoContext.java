package com.revytechinc.honchoinspector.model;

import com.revytechinc.honchoinspector.honcho.HonchoApiVersion;

/**
 * Internal value object for an authenticated Honcho call. Built at the
 * controller boundary from a {@link com.revytechinc.honchoinspector.auth.Profile}
 * + its decrypted API key. Never sent to the browser.
 *
 * <p>Carries the resolved {@link HonchoApiVersion} so downstream code
 * (e.g. {@code HonchoProxyService}, providers) can pick the correct
 * client/URL without re-resolving the version per call. T15 and T16
 * will populate {@code apiVersion} from the resolved version; until
 * then the no-version constructor defaults to {@link HonchoApiVersion#V3}
 * which matches the {@code honcho.api-version} default.
 *
 * <p>Carries the originating {@code profileId} so MDC fields can be
 * tagged per upstream call (T4b).
 */
public record HonchoContext(
    String apiKey,
    String baseUrl,
    String workspaceId,
    String userName,
    HonchoApiVersion apiVersion,
    String profileId
) {
    public HonchoContext {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("apiKey required");
        if (baseUrl == null || baseUrl.isBlank()) throw new IllegalArgumentException("baseUrl required");
        if (workspaceId == null || workspaceId.isBlank()) throw new IllegalArgumentException("workspaceId required");
        if (userName == null || userName.isBlank()) throw new IllegalArgumentException("userName required");
        if (apiVersion == null) throw new IllegalArgumentException("apiVersion required");
    }

    /**
     * Backward-compatible 4-arg form. Delegates to the canonical 6-arg
     * constructor with {@link HonchoApiVersion#V3} (the product default)
     * and {@code profileId=null}.
     */
    public HonchoContext(String apiKey, String baseUrl, String workspaceId, String userName) {
        this(apiKey, baseUrl, workspaceId, userName, HonchoApiVersion.V3, null);
    }

    /**
     * Backward-compatible 5-arg form. Delegates to the canonical 6-arg
     * constructor with {@code profileId=null}.
     */
    public HonchoContext(
        String apiKey,
        String baseUrl,
        String workspaceId,
        String userName,
        HonchoApiVersion apiVersion
    ) {
        this(apiKey, baseUrl, workspaceId, userName, apiVersion, null);
    }
}
