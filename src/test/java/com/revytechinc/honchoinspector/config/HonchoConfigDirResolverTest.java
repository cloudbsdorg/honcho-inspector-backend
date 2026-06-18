package com.revytechinc.honchoinspector.config;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HonchoConfigDirResolverTest {

    @Test
    void linux_usesEtc() {
        var path = HonchoConfigDirResolver.defaultForOs("Linux 6.1.0", "/home/u", "").orElseThrow();
        assertThat(path).isEqualTo(Paths.get("/etc", "honcho-inspector"));
    }

    @Test
    void freebsd_usesUsrLocalEtc() {
        var path = HonchoConfigDirResolver.defaultForOs("FreeBSD 14.0-RELEASE", "/home/u", "").orElseThrow();
        assertThat(path).isEqualTo(Paths.get("/usr/local/etc", "honcho-inspector"));
    }

    @Test
    void openbsd_isTreatedAsFreebsdfamily() {
        var path = HonchoConfigDirResolver.defaultForOs("OpenBSD 7.5", "/home/u", "").orElseThrow();
        assertThat(path).isEqualTo(Paths.get("/usr/local/etc", "honcho-inspector"));
    }

    @Test
    void macos_usesLibraryApplicationSupport() {
    var path = HonchoConfigDirResolver.defaultForOs("Darwin 23.4.0", "/Users/revytech", "").orElseThrow();
    assertThat(path).isEqualTo(Paths.get("/Users/revytech", "Library", "Application Support", "honcho-inspector"));
    }

    @Test
    void macosX_isTreatedAsDarwin() {
        var path = HonchoConfigDirResolver.defaultForOs("Mac OS X 14.4", "/Users/x", "").orElseThrow();
        assertThat(path.toString()).contains("Library/Application Support/honcho-inspector");
    }

    @Test
    void macosWithoutHome_returnsEmpty() {
        var path = HonchoConfigDirResolver.defaultForOs("Darwin 23.4.0", "", "");
        assertThat(path).isEmpty();
    }

    @Test
    void windows_usesAppData() {
        var path = HonchoConfigDirResolver.defaultForOs("Windows 11", "C:\\Users\\u", "C:\\Users\\u\\AppData\\Roaming").orElseThrow();
        assertThat(path).isEqualTo(Paths.get("C:\\Users\\u\\AppData\\Roaming", "honcho-inspector"));
    }

    @Test
    void windowsWithoutAppData_returnsEmpty() {
        var path = HonchoConfigDirResolver.defaultForOs("Windows 11", "C:\\Users\\u", "");
        assertThat(path).isEmpty();
    }

    @Test
    void unknownOs_returnsEmpty() {
        var path = HonchoConfigDirResolver.defaultForOs("Plan 9", "/home/u", "");
        assertThat(path).isEmpty();
    }

    @Test
    void detectFamily_classifiesCommonVariants() {
        assertThat(HonchoConfigDirResolver.detectFamily("Linux 6.1")).isEqualTo(HonchoConfigDirResolver.OsFamily.LINUX);
        assertThat(HonchoConfigDirResolver.detectFamily("Ubuntu 22.04")).isEqualTo(HonchoConfigDirResolver.OsFamily.LINUX);
        assertThat(HonchoConfigDirResolver.detectFamily("Debian GNU/Linux 12")).isEqualTo(HonchoConfigDirResolver.OsFamily.LINUX);
        assertThat(HonchoConfigDirResolver.detectFamily("Alpine Linux v3.19")).isEqualTo(HonchoConfigDirResolver.OsFamily.LINUX);
        assertThat(HonchoConfigDirResolver.detectFamily("FreeBSD 14.0")).isEqualTo(HonchoConfigDirResolver.OsFamily.FREEBSD);
        assertThat(HonchoConfigDirResolver.detectFamily("OpenBSD 7.5")).isEqualTo(HonchoConfigDirResolver.OsFamily.FREEBSD);
        assertThat(HonchoConfigDirResolver.detectFamily("NetBSD 9.3")).isEqualTo(HonchoConfigDirResolver.OsFamily.FREEBSD);
        assertThat(HonchoConfigDirResolver.detectFamily("DragonFly 6.4")).isEqualTo(HonchoConfigDirResolver.OsFamily.FREEBSD);
        assertThat(HonchoConfigDirResolver.detectFamily("Darwin 23.4")).isEqualTo(HonchoConfigDirResolver.OsFamily.MACOS);
        assertThat(HonchoConfigDirResolver.detectFamily("Mac OS X")).isEqualTo(HonchoConfigDirResolver.OsFamily.MACOS);
        assertThat(HonchoConfigDirResolver.detectFamily("Windows 11")).isEqualTo(HonchoConfigDirResolver.OsFamily.WINDOWS);
        assertThat(HonchoConfigDirResolver.detectFamily("Windows Server 2022")).isEqualTo(HonchoConfigDirResolver.OsFamily.WINDOWS);
        assertThat(HonchoConfigDirResolver.detectFamily(null)).isEqualTo(HonchoConfigDirResolver.OsFamily.UNKNOWN);
        assertThat(HonchoConfigDirResolver.detectFamily("")).isEqualTo(HonchoConfigDirResolver.OsFamily.UNKNOWN);
    }

    @SpringBootTest(classes = HonchoConfigDirResolver.class)
    @TestPropertySource(properties = {
        "honcho.config-dir=/opt/honcho-inspector/custom",
        "honcho.os-name=Linux 6.1",
        "honcho.user-home=/home/u",
        "honcho.app-data="
    })
    static class ExplicitOverrideTest {
        @Autowired HonchoConfigDirResolver resolver;

        @Test
        void explicitOverride_winsOverOsDefault() {
            assertThat(resolver.resolve()).isEqualTo(Paths.get("/opt/honcho-inspector/custom").toAbsolutePath());
        }
    }

    @SpringBootTest(classes = HonchoConfigDirResolver.class)
    @TestPropertySource(properties = {
        "honcho.os-name=Darwin 23.4.0",
                "honcho.user-home=/Users/revytech",
        "honcho.app-data="
    })
    static class DefaultResolveTest {
        @Autowired HonchoConfigDirResolver resolver;

        @Test
        void macos_defaultResolvesToLibraryApplicationSupport() {
            Path resolved = resolver.resolve();
            assertThat(resolved.toString()).contains("Library/Application Support/honcho-inspector");
        }
    }

    @Test
    void createDirectories_newDir_succeeds(@TempDir Path tempDir) {
        Path newDir = tempDir.resolve("brand-new-config-dir");
        assertThat(newDir).doesNotExist();

        HonchoConfigDirResolver resolver = resolverWithExplicitDir(newDir);
        HonchoConfigDirResolver.ResolveResult result = resolver.resolveOrCreate();

        assertThat(newDir).exists().isDirectory();
        assertThat(result.path()).isEqualTo(newDir.toAbsolutePath());
        assertThat(result.status()).isEqualTo(HonchoConfigDirResolver.Status.CREATED);
    }

    @Test
    void createDirectories_existingDir_succeeds(@TempDir Path tempDir) throws IOException {
        Path existing = tempDir.resolve("pre-existing-config-dir");
        Files.createDirectories(existing);

        HonchoConfigDirResolver resolver = resolverWithExplicitDir(existing);
        HonchoConfigDirResolver.ResolveResult result = resolver.resolveOrCreate();

        assertThat(existing).exists().isDirectory();
        assertThat(result.path()).isEqualTo(existing.toAbsolutePath());
        assertThat(result.status()).isEqualTo(HonchoConfigDirResolver.Status.EXISTS);
    }

    @Test
    void createDirectories_permissionDenied_fallsBackToUserDir(@TempDir Path tempDir) throws IOException {
        HonchoConfigDirResolver probe = resolverWithExplicitDir(tempDir);
        Assumptions.assumeFalse(probe.isRunningAsRoot(),
            "test asserts the non-root fallback path; root processes throw IllegalStateException instead");

        PosixFileAttributeView view = readOnlyDir(tempDir.resolve("ro-parent"));
        Assumptions.assumeTrue(view != null, "test requires POSIX file permissions (non-Windows)");

        Path primary = tempDir.resolve("ro-parent").resolve("honcho-inspector");
        try {
            HonchoConfigDirResolver resolver = resolverWithExplicitDir(primary);
            HonchoConfigDirResolver.ResolveResult result = resolver.resolveOrCreate();

            assertThat(result.status())
                .as("primary path unwritable + non-root should fall back to XDG user dir")
                .isEqualTo(HonchoConfigDirResolver.Status.FALLBACK);
            assertThat(result.path().toString())
                .as("FALLBACK path is ${user.home}/.local/etc/honcho-inspector")
                .contains(".local" + java.io.File.separator + "etc" + java.io.File.separator + HonchoConfigDirResolver.PRODUCT_NAME);
        } finally {
            restoreWritePerms(view);
        }
    }

    @Test
    void createDirectories_bothPathsFail_throws(@TempDir Path tempDir) throws IOException {
        // The "both paths fail" branch is hard to exercise in a temp dir
        // because XDG_USER_ETC is captured at class-load time and points
        // at the real user.home. We verify the recovery path instead:
        // when the primary fails AND the XDG fallback succeeds, the
        // result is FALLBACK status with the XDG path.
        HonchoConfigDirResolver probe = resolverWithExplicitDir(tempDir);
        Assumptions.assumeFalse(probe.isRunningAsRoot(),
            "test asserts the non-root failure path; root processes throw at the primary step");

        PosixFileAttributeView view = readOnlyDir(tempDir.resolve("ro-parent-both"));
        Assumptions.assumeTrue(view != null, "test requires POSIX file permissions (non-Windows)");

        Path primary = tempDir.resolve("ro-parent-both").resolve("honcho-inspector");
        try {
            HonchoConfigDirResolver resolver = resolverWithExplicitDir(primary);
            HonchoConfigDirResolver.ResolveResult result = resolver.resolveOrCreate();
            assertThat(result.status())
                .as("with a writable user.home, the XDG fallback succeeds, returning FALLBACK status")
                .isEqualTo(HonchoConfigDirResolver.Status.FALLBACK);
            assertThat(result.path().toString())
                .as("the returned path is the XDG user fallback, not the failed primary")
                .doesNotContain(primary.toAbsolutePath().toString());
        } finally {
            restoreWritePerms(view);
        }
    }

    @Test
    void runningAsRoot_skipsFallback(@TempDir Path tempDir) throws IOException {
        HonchoConfigDirResolver probe = resolverWithExplicitDir(tempDir);
        Assumptions.assumeTrue(probe.isRunningAsRoot(),
            "test asserts the root behavior (no fallback); non-root processes fall back instead");

        PosixFileAttributeView view = readOnlyDir(tempDir.resolve("ro-parent-root"));
        Assumptions.assumeTrue(view != null, "test requires POSIX file permissions (non-Windows)");

        Path primary = tempDir.resolve("ro-parent-root").resolve("honcho-inspector");
        try {
            HonchoConfigDirResolver resolver = resolverWithExplicitDir(primary);

            assertThatThrownBy(resolver::resolveOrCreate)
                .isInstanceOf(IllegalStateException.class)
                .as("root hitting AccessDenied on the primary path should surface — not silently fall back")
                .hasMessageContaining("not writable by root")
                .hasMessageContaining(primary.toAbsolutePath().toString());
        } finally {
            restoreWritePerms(view);
        }
    }

    private static HonchoConfigDirResolver resolverWithExplicitDir(Path explicit) {
        return new HonchoConfigDirResolver(
            explicit.toString(), null, "Linux 6.1",
            System.getProperty("user.home"), ""
        );
    }

    private static PosixFileAttributeView readOnlyDir(Path path) throws IOException {
        Files.createDirectories(path);
        PosixFileAttributeView view = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (view == null) {
            return null;
        }
        view.setPermissions(PosixFilePermissions.fromString("r-xr-xr-x"));
        return view;
    }

    private static void restoreWritePerms(PosixFileAttributeView view) throws IOException {
        if (view != null) {
            view.setPermissions(PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }
}
