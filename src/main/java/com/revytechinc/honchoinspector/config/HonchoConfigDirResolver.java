package com.revytechinc.honchoinspector.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Optional;

@Component
public class HonchoConfigDirResolver {

    private static final Logger log = LoggerFactory.getLogger(HonchoConfigDirResolver.class);

    public static final String PRODUCT_NAME = "honcho-inspector";

    /** XDG user-level fallback: {@code ${user.home}/.local/etc/honcho-inspector}. */
    private static final Path XDG_USER_ETC = buildXdgUserEtc();

    private static Path buildXdgUserEtc() {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            return Paths.get(".local", "etc", PRODUCT_NAME);
        }
        return Paths.get(home, ".local", "etc", PRODUCT_NAME);
    }

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

    /**
     * Resolves the config dir and ensures it exists on disk. On Linux/FreeBSD
     * as a non-root user, an unwritable system path falls back to
     * {@code ~/.local/etc/honcho-inspector}. The {@link ResolveResult}
     * carries a status tag the caller can use for logging
     * ({@code [created]}, {@code [exists]}, {@code [fallback]}).
     *
     * <p>Throws {@link IllegalStateException} if both attempts fail, so the
     * app fails fast at startup rather than crashing mid-request.
     */
    public ResolveResult resolveOrCreate() {
        Path primary = resolve();
        boolean primaryExisted = Files.exists(primary);
        try {
            Files.createDirectories(primary);
            Status status = primaryExisted ? Status.EXISTS : Status.CREATED;
            return new ResolveResult(primary, status);
        } catch (AccessDeniedException denied) {
            if (isRunningAsRoot()) {
                // Root hitting AccessDenied is a real config error — surface it
                // rather than silently relocating state to a hidden home dir.
                throw new IllegalStateException(
                    "config dir primary path is not writable by root: " + primary
                        + " (exception: " + denied.getClass().getSimpleName() + ")",
                    denied
                );
            }
            log.warn(
                "config dir primary path not writable ({}), falling back to {}",
                primary, XDG_USER_ETC
            );
            try {
                Files.createDirectories(XDG_USER_ETC);
                return new ResolveResult(XDG_USER_ETC, Status.FALLBACK);
            } catch (IOException fallbackDenied) {
                throw new IllegalStateException(
                    "config dir primary path (" + primary
                        + ") is not writable and the XDG user fallback ("
                        + XDG_USER_ETC + ") also failed: "
                        + fallbackDenied.getClass().getSimpleName() + ": " + fallbackDenied.getMessage(),
                    fallbackDenied
                );
            }
        } catch (IOException other) {
            // Non-permission I/O on the primary path: do not mask a more
            // serious problem (corrupted fs, etc.) with the user fallback.
            throw new IllegalStateException(
                "config dir primary path (" + primary + ") could not be created: "
                    + other.getClass().getSimpleName() + ": " + other.getMessage(),
                other
            );
        }
    }

    /**
     * Best-effort root detection. Returns {@code false} on Windows / macOS;
     * on Linux checks {@code /proc/self/status} for {@code Uid: 0} when
     * available, falling back to {@code user.name == "root"}.
     *
     * <p>Conservative on purpose: a user literally named "root" but
     * actually unprivileged will skip the user-dir fallback, so a service
     * that cannot write {@code /etc} fails loud at startup instead of
     * silently relocating state to a hidden home dir.
     */
    boolean isRunningAsRoot() {
        var family = detectFamily(osName);
        if (family == OsFamily.WINDOWS || family == OsFamily.MACOS) {
            return false;
        }
        // /proc/self/status is Linux-specific; on FreeBSD it's not present
        // at this path, so we fall through to the user.name check.
        try {
            Path procSelfStatus = Paths.get("/proc/self/status");
            if (Files.exists(procSelfStatus)) {
                for (String line : Files.readAllLines(procSelfStatus)) {
                    if (line.startsWith("Uid:")) {
                        String[] parts = line.split("\\s+");
                        if (parts.length >= 2 && "0".equals(parts[1])) {
                            return true;
                        }
                        return false;
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // /proc not readable, parse error — fall through to the username check.
        }
        return "root".equals(System.getProperty("user.name"));
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

    /** {@code FALLBACK} = OS-default path unwritable, used XDG user dir. */
    public enum Status { CREATED, EXISTS, FALLBACK }

    /** Path guaranteed to exist on disk + status tag. */
    public record ResolveResult(Path path, Status status) {}

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }
}
