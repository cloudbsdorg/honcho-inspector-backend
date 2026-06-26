package com.revytechinc.honchoinspector.cli;

import com.revytechinc.honchoinspector.IntegrationTestBase;
import com.revytechinc.honchoinspector.auth.PasswordHasher;
import com.revytechinc.honchoinspector.auth.repo.AuthSessionRepository;
import com.revytechinc.honchoinspector.auth.repo.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliRunnerTest extends IntegrationTestBase {

    @Autowired private UserRepository userRepo;
    @Autowired private AuthSessionRepository sessionRepo;

    private CliRunner cli;
    private ByteArrayOutputStream stdout;
    private ByteArrayOutputStream stderr;

    @BeforeEach
    void setUpCli() {
        PasswordHasher hasher = new PasswordHasher();
        cli = new CliRunner(userRepo, sessionRepo, hasher);
        stdout = new ByteArrayOutputStream();
        stderr = new ByteArrayOutputStream();
        System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("list-users on empty DB prints '(no users)' and returns 0")
    void listUsers_empty() {
        int code = cli.handle(CliRunner.CMD_LIST_USERS, new String[0]);
        assertEquals(CliRunner.EXIT_OK, code);
        assertTrue(stdout.toString().contains("(no users)"), "stdout was: " + stdout);
    }

    @Test
    @DisplayName("list-users prints each user with id, username, admin flag, created_at")
    void listUsers_withRows() {
        createUserDirect("alice", "alicepw1234", true);
        createUserDirect("bob", "bobpw1234567", false);
        int code = cli.handle(CliRunner.CMD_LIST_USERS, new String[0]);
        assertEquals(CliRunner.EXIT_OK, code);
        String out = stdout.toString();
        assertTrue(out.contains("alice"), out);
        assertTrue(out.contains("bob"), out);
        assertTrue(out.contains("yes"), out);
        assertTrue(out.contains("no"), out);
    }

    @Test
    @DisplayName("reset-admin-password: --username missing returns 2")
    void resetPassword_missingUsername() {
        int code = cli.handle(CliRunner.CMD_RESET_PASSWORD, new String[]{"--password", "abcdefgh"});
        assertEquals(CliRunner.EXIT_INVALID_ARGS, code);
        assertTrue(stderr.toString().contains("--username is required"), stderr.toString());
    }

    @Test
    @DisplayName("reset-admin-password: neither --password nor --generate returns 2")
    void resetPassword_missingBoth() {
        createUserDirect("alice", "alicepw1234", true);
        int code = cli.handle(CliRunner.CMD_RESET_PASSWORD, new String[]{"--username", "alice"});
        assertEquals(CliRunner.EXIT_INVALID_ARGS, code);
        assertTrue(stderr.toString().contains("--password or --generate"), stderr.toString());
    }

    @Test
    @DisplayName("reset-admin-password: unknown user returns 3")
    void resetPassword_unknownUser() {
        int code = cli.handle(CliRunner.CMD_RESET_PASSWORD, new String[]{"--username", "ghost", "--password", "abcdefgh"});
        assertEquals(CliRunner.EXIT_USER_NOT_FOUND, code);
        assertTrue(stderr.toString().contains("user not found"), stderr.toString());
    }

    @Test
    @DisplayName("reset-admin-password: short password returns 4")
    void resetPassword_shortPassword() {
        createUserDirect("alice", "alicepw1234", true);
        int code = cli.handle(CliRunner.CMD_RESET_PASSWORD, new String[]{"--username", "alice", "--password", "short"});
        assertEquals(CliRunner.EXIT_VALIDATION, code);
        assertTrue(stderr.toString().contains("at least 8 characters"), stderr.toString());
    }

    @Test
    @DisplayName("reset-admin-password: with --password, the new password is accepted and old is rejected")
    void resetPassword_explicit() {
        createUserDirect("alice", "oldpassword12", true);
        int code = cli.handle(CliRunner.CMD_RESET_PASSWORD,
            new String[]{"--username", "alice", "--password", "newpassword12"});
        assertEquals(CliRunner.EXIT_OK, code);
        Optional<com.revytechinc.honchoinspector.auth.entity.UserEntity> reloaded = userRepo.findByUsername("alice");
        assertTrue(reloaded.isPresent());
        PasswordHasher hasher = new PasswordHasher();
        assertTrue(hasher.verify("newpassword12", reloaded.get().getPasswordHash()));
        assertFalse(hasher.verify("oldpassword12", reloaded.get().getPasswordHash()));
        assertTrue(stdout.toString().contains("password reset for user 'alice'"), stdout.toString());
    }

    @Test
    @DisplayName("reset-admin-password: --generate prints a fresh password once and updates the hash")
    void resetPassword_generate() {
        createUserDirect("alice", "oldpassword12", true);
        int code = cli.handle(CliRunner.CMD_RESET_PASSWORD,
            new String[]{"--username", "alice", "--generate"});
        assertEquals(CliRunner.EXIT_OK, code);
        String out = stdout.toString();
        assertTrue(out.contains("new password: "), out);
        assertTrue(out.contains("shown only once"), out);
        Optional<com.revytechinc.honchoinspector.auth.entity.UserEntity> reloaded = userRepo.findByUsername("alice");
        assertTrue(reloaded.isPresent());
        PasswordHasher hasher = new PasswordHasher();
        assertFalse(hasher.verify("oldpassword12", reloaded.get().getPasswordHash()));
    }

    @Test
    @DisplayName("promote-to-admin: --username missing returns 2")
    void promoteAdmin_missingUsername() {
        int code = cli.handle(CliRunner.CMD_PROMOTE_ADMIN, new String[0]);
        assertEquals(CliRunner.EXIT_INVALID_ARGS, code);
        assertTrue(stderr.toString().contains("--username is required"), stderr.toString());
    }

    @Test
    @DisplayName("promote-to-admin: unknown user returns 3")
    void promoteAdmin_unknownUser() {
        int code = cli.handle(CliRunner.CMD_PROMOTE_ADMIN, new String[]{"--username", "ghost"});
        assertEquals(CliRunner.EXIT_USER_NOT_FOUND, code);
    }

    @Test
    @DisplayName("promote-to-admin: promotes a non-admin to admin")
    void promoteAdmin_success() {
        createUserDirect("bob", "bobpw1234567", false);
        int code = cli.handle(CliRunner.CMD_PROMOTE_ADMIN, new String[]{"--username", "bob"});
        assertEquals(CliRunner.EXIT_OK, code);
        com.revytechinc.honchoinspector.auth.entity.UserEntity reloaded = userRepo.findByUsername("bob").orElseThrow();
        assertTrue(reloaded.getIsAdmin());
        assertTrue(stdout.toString().contains("promoted to admin"), stdout.toString());
    }

    @Test
    @DisplayName("promote-to-admin: already-admin is a no-op success")
    void promoteAdmin_alreadyAdmin() {
        createUserDirect("alice", "alicepw1234", true);
        int code = cli.handle(CliRunner.CMD_PROMOTE_ADMIN, new String[]{"--username", "alice"});
        assertEquals(CliRunner.EXIT_OK, code);
        assertTrue(stdout.toString().contains("already an admin"), stdout.toString());
    }

    @Test
    @DisplayName("revoke-all-sessions: wipes all sessions across all users")
    void revokeAllSessions_wipesEverything() {
        createUserDirect("alice", "alicepw1234", true);
        createUserDirect("bob", "bobpw1234567", false);
        AuthSessionRepository s = sessionRepo;
        s.save(new com.revytechinc.honchoinspector.auth.entity.AuthSessionEntity(
            UUID.randomUUID().toString().replace("-", ""),
            userRepo.findByUsername("alice").orElseThrow().getId(),
            Instant.now(), Instant.now(), null));
        s.save(new com.revytechinc.honchoinspector.auth.entity.AuthSessionEntity(
            UUID.randomUUID().toString().replace("-", ""),
            userRepo.findByUsername("bob").orElseThrow().getId(),
            Instant.now(), Instant.now(), null));
        assertEquals(2L, s.count());
        int code = cli.handle(CliRunner.CMD_REVOKE_SESSIONS, new String[0]);
        assertEquals(CliRunner.EXIT_OK, code);
        assertEquals(0L, s.count());
        assertTrue(stdout.toString().contains("revoked 2"), stdout.toString());
    }

    @Test
    @DisplayName("help: returns 0 and prints the usage block")
    void help_printsUsage() {
        int code = cli.handle(CliRunner.CMD_HELP, new String[0]);
        assertEquals(CliRunner.EXIT_OK, code);
        String out = stdout.toString();
        assertTrue(out.contains("emergency-action CLI"));
        assertTrue(out.contains("list-users"));
        assertTrue(out.contains("reset-admin-password"));
        assertTrue(out.contains("promote-to-admin"));
        assertTrue(out.contains("revoke-all-sessions"));
    }

    @Test
    @DisplayName("unknown subcommand falls through to help and returns 2")
    void unknownCommand_returnsInvalidArgs() {
        int code = cli.handle("not-a-real-cmd", new String[0]);
        assertEquals(CliRunner.EXIT_INVALID_ARGS, code);
        assertTrue(stdout.toString().contains("emergency-action CLI"));
    }

    @Test
    @DisplayName("isKnownCommand recognises every public subcommand constant")
    void isKnownCommand_knownCommands() {
        assertTrue(CliRunner.isKnownCommand("list-users"));
        assertTrue(CliRunner.isKnownCommand("reset-admin-password"));
        assertTrue(CliRunner.isKnownCommand("promote-to-admin"));
        assertTrue(CliRunner.isKnownCommand("revoke-all-sessions"));
        assertTrue(CliRunner.isKnownCommand("help"));
    }

    @Test
    @DisplayName("isKnownCommand rejects unknown strings and null")
    void isKnownCommand_unknown() {
        assertFalse(CliRunner.isKnownCommand("bogus"));
        assertFalse(CliRunner.isKnownCommand(""));
        assertFalse(CliRunner.isKnownCommand(null));
    }

    @Test
    @DisplayName("parseFlags: --name value + --flag + --empty=ignored")
    void parseFlags_basic() {
        var out = CliRunner.parseFlags(new String[]{"--username", "alice", "--generate", "--unknown", "x"});
        assertEquals("alice", out.get("username"));
        assertEquals("true", out.get("generate"));
    }

    @Test
    @DisplayName("parseFlags: --key at end without value gets 'true'")
    void parseFlags_trailingFlag() {
        var out = CliRunner.parseFlags(new String[]{"--yes"});
        assertEquals("true", out.get("yes"));
    }

    @Test
    @DisplayName("parseFlags: empty input returns empty map")
    void parseFlags_empty() {
        var out = CliRunner.parseFlags(new String[0]);
        assertNotNull(out);
        assertTrue(out.isEmpty());
    }

    @Test
    @DisplayName("parseFlags: lower-cases keys")
    void parseFlags_lowercaseKeys() {
        var out = CliRunner.parseFlags(new String[]{"--UserName", "alice"});
        assertTrue(out.containsKey("username"), "expected lowercased key, got: " + out.keySet());
    }

    @Test
    @DisplayName("randomPassword: returns base64-url chars >= bytes; two calls differ")
    void randomPassword_uniquenessAndShape() {
        String a = CliRunner.randomPassword(20);
        String b = CliRunner.randomPassword(20);
        assertTrue(a.length() >= 20, "expected at least 20 chars, got " + a.length());
        assertTrue(b.length() >= 20, "expected at least 20 chars, got " + b.length());
        assertNotEquals(a, b);
        assertTrue(a.matches("[A-Za-z0-9_-]+"), "non-base64url chars in: " + a);
    }

    @Test
    @DisplayName("end-to-end: a fresh admin can be promoted, password reset, and sessions revoked without a web server")
    void endToEnd_adminLifecycle() {
        createUserDirect("alice", "oldpassword12", false);
        assertFalse(userRepo.findByUsername("alice").orElseThrow().getIsAdmin());
        assertEquals(CliRunner.EXIT_OK, cli.handle(CliRunner.CMD_PROMOTE_ADMIN,
            new String[]{"--username", "alice"}));
        assertTrue(userRepo.findByUsername("alice").orElseThrow().getIsAdmin());
        assertEquals(CliRunner.EXIT_OK, cli.handle(CliRunner.CMD_RESET_PASSWORD,
            new String[]{"--username", "alice", "--password", "brand-new-12345"}));
        var s1Entity = new com.revytechinc.honchoinspector.auth.entity.AuthSessionEntity(
            UUID.randomUUID().toString().replace("-", ""),
            userRepo.findByUsername("alice").orElseThrow().getId(),
            Instant.now(), Instant.now(), null);
        sessionRepo.save(s1Entity);
        assertEquals(1L, sessionRepo.count());
        assertEquals(CliRunner.EXIT_OK, cli.handle(CliRunner.CMD_REVOKE_SESSIONS, new String[0]));
        assertEquals(0L, sessionRepo.count());
    }
}
