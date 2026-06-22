package com.revytechinc.honchoinspector.cli;

import com.revytechinc.honchoinspector.auth.AuthSessionDao;
import com.revytechinc.honchoinspector.auth.PasswordHasher;
import com.revytechinc.honchoinspector.auth.UserDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Emergency-action CLI for the Honcho Inspector backend.
 *
 * Runs as a Spring {@link CommandLineRunner}. When the first non-flag
 * argument matches a known subcommand, the action is executed and the
 * JVM exits with a conventional code. When the first argument is not
 * a known subcommand (or is absent), the runner returns without
 * exiting, letting Spring continue starting the web server.
 *
 * Subcommands (in stable order):
 *   list-users                                       List every user.
 *   reset-admin-password --username N --password P   Reset a user's password.
 *   reset-admin-password --username N --generate     Reset to a fresh random password (printed once).
 *   promote-to-admin --username N                    Set is_admin=1 for the user.
 *   revoke-all-sessions                              Wipe auth_sessions (force-logout everyone).
 *   help                                             Print usage and exit.
 *
 * Exit codes:
 *   0  success
 *   2  invalid arguments
 *   3  user not found
 *   4  validation error
 *   5  database error
 *
 * The CLI is intended to be invoked when the web server is stopped
 * (or under operator supervision). SQLite enforces a single-writer
 * lock, so concurrent CLI + web server writes will block.
 */
@Component
public class CliRunner implements CommandLineRunner {

    public static final int EXIT_OK = 0;
    public static final int EXIT_INVALID_ARGS = 2;
    public static final int EXIT_USER_NOT_FOUND = 3;
    public static final int EXIT_VALIDATION = 4;
    public static final int EXIT_DB_ERROR = 5;

    private static final Logger log = LoggerFactory.getLogger(CliRunner.class);

    public static final String CMD_LIST_USERS = "list-users";
    public static final String CMD_RESET_PASSWORD = "reset-admin-password";
    public static final String CMD_PROMOTE_ADMIN = "promote-to-admin";
    public static final String CMD_REVOKE_SESSIONS = "revoke-all-sessions";
    public static final String CMD_HELP = "help";

    private static final Set<String> KNOWN_COMMANDS = Set.of(
        CMD_LIST_USERS,
        CMD_RESET_PASSWORD,
        CMD_PROMOTE_ADMIN,
        CMD_REVOKE_SESSIONS,
        CMD_HELP
    );

    private final UserDao users;
    private final AuthSessionDao sessions;
    private final PasswordHasher hasher;

    public CliRunner(UserDao users, AuthSessionDao sessions, PasswordHasher hasher) {
        this.users = users;
        this.sessions = sessions;
        this.hasher = hasher;
    }

    @Override
    public void run(String... args) throws Exception {
        if (args.length == 0) {
            return;
        }
        String cmd = args[0];
        if (!KNOWN_COMMANDS.contains(cmd)) {
            return;
        }
        int exit = handle(cmd, Arrays.copyOfRange(args, 1, args.length));
        System.exit(exit);
    }

    /**
     * Dispatch one subcommand and return the exit code. Package-private
     * so unit tests can drive the handler directly without going
     * through {@link System#exit(int)}.
     */
    public int handle(String cmd, String[] args) {
        try {
            return switch (cmd) {
                case CMD_LIST_USERS -> doListUsers();
                case CMD_RESET_PASSWORD -> doResetPassword(parseFlags(args));
                case CMD_PROMOTE_ADMIN -> doPromoteAdmin(parseFlags(args));
                case CMD_REVOKE_SESSIONS -> doRevokeAllSessions();
                case CMD_HELP -> { printHelp(); yield EXIT_OK; }
                default -> {
                    printHelp();
                    yield EXIT_INVALID_ARGS;
                }
            };
        } catch (RuntimeException e) {
            log.error("CLI action failed: {}", e.getMessage(), e);
            return EXIT_DB_ERROR;
        }
    }

    private int doListUsers() {
        List<com.revytechinc.honchoinspector.auth.User> all = users.findAll();
        if (all.isEmpty()) {
            System.out.println("(no users)");
            return EXIT_OK;
        }
        var fmt = DateTimeFormatter.ISO_INSTANT;
        System.out.printf("%-34s %-24s %-7s %-24s%n", "ID", "USERNAME", "ADMIN", "CREATED_AT");
        for (var u : all) {
            System.out.printf("%-34s %-24s %-7s %-24s%n",
                u.id(),
                u.username(),
                u.isAdmin() ? "yes" : "no",
                u.createdAt() == null ? "" : fmt.format(u.createdAt()));
        }
        return EXIT_OK;
    }

    private int doResetPassword(Map<String, String> flags) {
        String username = flags.get("username");
        if (username == null || username.isBlank()) {
            System.err.println("--username is required");
            return EXIT_INVALID_ARGS;
        }
        Optional<com.revytechinc.honchoinspector.auth.User> userOpt = users.findByUsername(username);
        if (userOpt.isEmpty()) {
            System.err.println("user not found: " + username);
            return EXIT_USER_NOT_FOUND;
        }
        String password = flags.get("password");
        boolean generate = flags.containsKey("generate");
        if ((password == null || password.isEmpty()) && !generate) {
            System.err.println("either --password or --generate is required");
            return EXIT_INVALID_ARGS;
        }
        if (password != null && password.length() < 8) {
            System.err.println("password must be at least 8 characters");
            return EXIT_VALIDATION;
        }
        if (generate) {
            password = randomPassword(20);
        }
        var user = userOpt.get();
        users.updatePasswordHash(user.id(), hasher.hash(password));
        System.out.println("password reset for user '" + username + "' (id=" + user.id() + ")");
        if (generate) {
            System.out.println("  new password: " + password);
            System.out.println("  IMPORTANT: this password is shown only once. Store it now.");
        }
        return EXIT_OK;
    }

    private int doPromoteAdmin(Map<String, String> flags) {
        String username = flags.get("username");
        if (username == null || username.isBlank()) {
            System.err.println("--username is required");
            return EXIT_INVALID_ARGS;
        }
        Optional<com.revytechinc.honchoinspector.auth.User> userOpt = users.findByUsername(username);
        if (userOpt.isEmpty()) {
            System.err.println("user not found: " + username);
            return EXIT_USER_NOT_FOUND;
        }
        var user = userOpt.get();
        if (user.isAdmin()) {
            System.out.println("user '" + username + "' is already an admin");
            return EXIT_OK;
        }
        users.updateAdmin(user.id(), true);
        System.out.println("user '" + username + "' (id=" + user.id() + ") promoted to admin");
        return EXIT_OK;
    }

    private int doRevokeAllSessions() {
        long before = sessions.count();
        users.findAll().forEach(u -> sessions.deleteByUserId(u.id()));
        long after = sessions.count();
        System.out.println("revoked " + before + " sessions; " + after + " remain");
        return EXIT_OK;
    }

    static Map<String, String> parseFlags(String[] args) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a == null || !a.startsWith("--") || a.length() < 3) {
                continue;
            }
            String key = a.substring(2).toLowerCase(Locale.ROOT);
            String value;
            if (i + 1 < args.length && args[i + 1] != null && !args[i + 1].startsWith("--")) {
                value = args[++i];
            } else {
                value = "true";
            }
            out.put(key, value);
        }
        return out;
    }

    static String randomPassword(int bytes) {
        byte[] buf = new byte[bytes];
        new SecureRandom().nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    public static void printHelp() {
        System.out.println("""
            honcho-inspector emergency-action CLI

            Usage:
              honcho-inspector list-users
              honcho-inspector reset-admin-password --username NAME [--password PASS | --generate]
              honcho-inspector promote-to-admin --username NAME
              honcho-inspector revoke-all-sessions
              honcho-inspector help

            Exit codes:
              0  success
              2  invalid arguments
              3  user not found
              4  validation error
              5  database error

            Notes:
              - These actions are intended to run when the web server is stopped.
                SQLite enforces a single-writer lock; running the CLI while the
                service is up will block on writes.
              - The CLI does not authenticate the caller. File-system
                permissions on the SQLite DB and the operator's shell
                account are the access control.
              - For Honcho crypto key rotation, set HONCHO_CRYPTO_KEY_NEW in
                the env file, then call the corresponding /api/admin/maintenance
                endpoint with admin auth. (Planned CLI subcommand: rotate-crypto-key.)
            """);
    }

    public static boolean isKnownCommand(String s) {
        return s != null && KNOWN_COMMANDS.contains(s);
    }
}
