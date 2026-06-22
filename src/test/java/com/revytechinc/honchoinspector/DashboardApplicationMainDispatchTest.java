package com.revytechinc.honchoinspector;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DashboardApplicationMainDispatchTest {

    @Test
    void shouldRunCli_listUsers_returnsTrue() {
        assertTrue(DashboardApplication.shouldRunCli(new String[]{"list-users"}));
    }

    @Test
    void shouldRunCli_help_returnsTrue() {
        assertTrue(DashboardApplication.shouldRunCli(new String[]{"help"}));
    }

    @Test
    void shouldRunCli_resetAdminPassword_returnsTrue() {
        assertTrue(DashboardApplication.shouldRunCli(
            new String[]{"reset-admin-password", "--username", "alice"}));
    }

    @Test
    void shouldRunCli_promoteToAdmin_returnsTrue() {
        assertTrue(DashboardApplication.shouldRunCli(
            new String[]{"promote-to-admin", "--username", "alice"}));
    }

    @Test
    void shouldRunCli_revokeAllSessions_returnsTrue() {
        assertTrue(DashboardApplication.shouldRunCli(new String[]{"revoke-all-sessions"}));
    }

    @Test
    void shouldRunCli_emptyArgs_returnsFalse() {
        assertFalse(DashboardApplication.shouldRunCli(new String[]{}));
    }

    @Test
    void shouldRunCli_unknownCommand_returnsFalse() {
        assertFalse(DashboardApplication.shouldRunCli(new String[]{"bogus"}));
        assertFalse(DashboardApplication.shouldRunCli(new String[]{"--help"}));
        assertFalse(DashboardApplication.shouldRunCli(new String[]{"--server.port=9090"}));
    }
}
