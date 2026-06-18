package com.revytechinc.honchoinspector.honcho;

/**
 * Honcho API surface version.
 *
 * <p>The path prefix is used to construct Honcho upstream URLs such as
 * {@code /<pathPrefix>/workspaces/{ws}/...}. The default in this product is
 * {@link #V3}; older deployments may pin to {@link #V2} and a future
 * release will introduce {@link #V4}.
 *
 * <p>This enum is plain Java — no Spring annotations. It exists so callers can
 * carry an API version across the proxy/provider boundary without depending on
 * a stringly-typed configuration value.
 */
public enum HonchoApiVersion {
    V2("v2"),
    V3("v3"),
    V4("v4");

    private final String pathPrefix;

    HonchoApiVersion(String pathPrefix) {
        this.pathPrefix = pathPrefix;
    }

    /**
     * Path segment that precedes {@code /workspaces/...} when addressing Honcho.
     * Always lowercase (e.g. {@code "v2"}, {@code "v3"}, {@code "v4"}).
     */
    public String pathPrefix() {
        return pathPrefix;
    }

    /**
     * Parse a user-supplied version identifier (case-insensitive) into an enum
     * constant. Both the lowercase {@code "v3"} and the canonical enum name
     * {@code "V3"} are accepted.
     *
     * @param value input string from config, request header, etc. May not be {@code null}.
     * @return the matching {@link HonchoApiVersion} constant.
     * @throws IllegalArgumentException if the value is not one of the supported versions.
     *         The message lists every supported version to aid operator diagnostics.
     */
    public static HonchoApiVersion fromString(String value) {
        if (value == null) {
            throw new IllegalArgumentException(
                "Unknown Honcho API version: null. Supported versions: " + supportedVersions());
        }
        for (HonchoApiVersion v : values()) {
            if (v.name().equalsIgnoreCase(value) || v.pathPrefix.equalsIgnoreCase(value)) {
                return v;
            }
        }
        throw new IllegalArgumentException(
            "Unknown Honcho API version: '" + value + "'. Supported versions: " + supportedVersions());
    }

    /**
     * Comma-separated listing of every supported version, in declaration order.
     * Used to construct helpful error messages.
     */
    public static String supportedVersions() {
        StringBuilder sb = new StringBuilder();
        HonchoApiVersion[] all = values();
        for (int i = 0; i < all.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(all[i].name()).append(" (").append(all[i].pathPrefix).append(")");
        }
        return sb.toString();
    }
}
