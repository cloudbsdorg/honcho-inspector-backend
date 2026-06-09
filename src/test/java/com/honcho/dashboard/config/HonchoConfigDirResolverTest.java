package com.honcho.dashboard.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

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
}
