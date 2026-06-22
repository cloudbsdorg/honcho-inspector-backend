package com.revytechinc.honchoinspector.auth;

import java.util.Locale;

/**
 * Page size selector for admin list endpoints. Accepts one of the literal
 * string values {@code 10}, {@code 20}, {@code 30}, or {@code ALL}. Any
 * other value falls back to the default {@code 20}.
 */
public enum PageSize {
    S10(10),
    S20(20),
    S30(30),
    ALL(Integer.MAX_VALUE);

    public final int rows;

    PageSize(int rows) {
        this.rows = rows;
    }

    public static PageSize parse(String raw) {
        if (raw == null) return S20;
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "10"  -> S10;
            case "20"  -> S20;
            case "30"  -> S30;
            case "ALL" -> ALL;
            default    -> S20;
        };
    }
}
