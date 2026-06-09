package com.honcho.dashboard.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Optional;

@Component
public class HonchoConfigDirResolver {

    private static final Logger log = LoggerFactory.getLogger(HonchoConfigDirResolver.class);

    public static final String PRODUCT_NAME = "honcho-inspector";

    private final String osName;
    private final String userHome;
    private final String appData;
    private final String explicit;

    public HonchoConfigDirResolver(
        @Value("${honcho.config-dir:#{null}}") String explicit,
        @Value("${HONCHO_CONFIG_DIR:#{null}}") String envOverride,
        @Value("${honcho.os-name:#{T(System).getProperty('os.name')}}") String osName,
        @Value("${honcho.user-home:#{T(System).getProperty('user.home')}}") String userHome,
        @Value("${honcho.app-data:#{T(System).getenv('APPDATA') ?: ''}}") String appData
    ) {
        this.osName = osName == null ? "" : osName;
        this.userHome = userHome == null ? "" : userHome;
        this.appData = appData == null ? "" : appData;
        this.explicit = firstNonBlank(explicit, envOverride);
    }

    public Path resolve() {
        if (explicit != null) {
            var p = Paths.get(explicit.trim());
            log.info("{} config dir (explicit): {}", PRODUCT_NAME, p.toAbsolutePath());
            return p.toAbsolutePath();
        }
        var resolved = defaultForOs(osName, userHome, appData)
            .orElseGet(() -> Paths.get("."));
        log.info("{} config dir ({}): {}", PRODUCT_NAME, detectFamily(osName), resolved.toAbsolutePath());
        return resolved.toAbsolutePath();
    }

    public static Optional<Path> defaultForOs(String osName, String userHome, String appData) {
        var family = detectFamily(osName);
        return switch (family) {
            case LINUX -> Optional.of(Paths.get("/etc", PRODUCT_NAME));
            case FREEBSD -> Optional.of(Paths.get("/usr/local/etc", PRODUCT_NAME));
            case MACOS -> userHome == null || userHome.isBlank()
                ? Optional.empty()
                : Optional.of(Paths.get(userHome, "Library", "Application Support", PRODUCT_NAME));
            case WINDOWS -> (appData == null || appData.isBlank())
                ? Optional.empty()
                : Optional.of(Paths.get(appData, PRODUCT_NAME));
            case UNKNOWN -> Optional.empty();
        };
    }

    public static OsFamily detectFamily(String osName) {
        if (osName == null) return OsFamily.UNKNOWN;
        var lower = osName.toLowerCase(Locale.ROOT);
        if (lower.contains("freebsd") || lower.contains("openbsd") || lower.contains("netbsd") || lower.contains("dragonfly")) {
            return OsFamily.FREEBSD;
        }
        if (lower.contains("mac") || lower.contains("darwin") || lower.contains("osx")) {
            return OsFamily.MACOS;
        }
        if (lower.contains("linux") || lower.contains("ubuntu") || lower.contains("debian")
            || lower.contains("fedora") || lower.contains("rhel") || lower.contains("centos")
            || lower.contains("arch") || lower.contains("alpine") || lower.contains("amazon")) {
            return OsFamily.LINUX;
        }
        if (lower.contains("windows") || lower.contains("win")) {
            return OsFamily.WINDOWS;
        }
        return OsFamily.UNKNOWN;
    }

    public enum OsFamily { LINUX, FREEBSD, MACOS, WINDOWS, UNKNOWN }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }
}
