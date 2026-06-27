package com.revytechinc.honchoinspector.auth;

import com.revytechinc.honchoinspector.auth.entity.AuthSessionEntity;
import com.revytechinc.honchoinspector.auth.entity.UserEntity;
import com.revytechinc.honchoinspector.auth.repo.AuthSessionRepository;
import com.revytechinc.honchoinspector.auth.repo.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class AuthService {

    private final UserRepository users;
    private final AuthSessionRepository sessions;
    private final PasswordHasher hasher;
    private final SecureRandom rng = new SecureRandom();

    public AuthService(
        UserRepository users,
        AuthSessionRepository sessions,
        PasswordHasher hasher
    ) {
        this.users = users;
        this.sessions = sessions;
        this.hasher = hasher;
    }

    public boolean isFirstUser() {
        return users.count() == 0;
    }

    public User register(String username, String password) {
        return adminCreate(username, password, null, null, null, isFirstUser());
    }

    public User adminCreate(
        String username,
        String password,
        String firstname,
        String lastname,
        String email,
        boolean isAdmin
    ) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username is required");
        }
        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("password must be at least 8 characters");
        }
        if (users.findByUsername(username).isPresent()) {
            throw new UserExistsException(username);
        }
        var entity = new UserEntity(
            newId(),
            username.trim(),
            hasher.hash(password),
            blankToNull(firstname),
            blankToNull(lastname),
            blankToNull(email),
            isAdmin,
            Instant.now()
        );
        try {
            users.save(entity);
        } catch (DataIntegrityViolationException e) {
            throw new UserExistsException(username);
        }
        return toRecord(entity);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public LoginResult login(String username, String password) {
        var entity = users.findByUsername(username == null ? "" : username.trim())
            .orElseThrow(InvalidCredentialsException::new);
        if (!hasher.verify(password, entity.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        var session = new AuthSessionEntity(
            newId(),
            entity.getId(),
            Instant.now(),
            Instant.now(),
            null
        );
        sessions.save(session);
        return new LoginResult(toRecord(session), toRecord(entity));
    }

    /**
     * Self-service password change for the currently authenticated
     * user. Requires the current password (so a stolen session cookie
     * alone can't lock the real user out), re-hashes the new password,
     * and revokes ALL of the user's existing sessions — including
     * the one making the change. The caller has to log in again
     * with the new password. Audit event {@code user.password.change}
     * is recorded by the controller.
     *
     * <p>This is distinct from the admin-only
     * {@code AdminUserService.resetPassword(...)} path, which is
     * reachable at {@code POST /api/admin/users/{id}/password}
     * (the route the "Reset pwd" button in the admin panel uses).
     * That one does not require the current password because the
     * caller is already authenticated as an admin. Self-service
     * here does, because the only authentication factor we have
     * is the password itself.
     */
    @Transactional
    /*
     * Boundary transaction for the password-mutation + session-
     * revocation pair. The two operations MUST be in the same
     * transaction: if the password change commits but the session
     * revoke fails, the caller keeps a valid session under the old
     * password (broken auth state). The other call sites
     * (login, register, logout) are single-statement and don't
     * need an explicit @Transactional.
     */
    public void changeOwnPassword(String userId, String currentPassword, String newPassword) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("not authenticated");
        }
        if (newPassword == null || newPassword.length() < 8) {
            throw new IllegalArgumentException("newPassword must be at least 8 characters");
        }
        var entity = users.findById(userId).orElseThrow(() -> new IllegalArgumentException("user not found"));
        if (!hasher.verify(currentPassword, entity.getPasswordHash())) {
            // Use the same generic message as login so we don't leak
            // whether the username exists vs whether the password
            // is wrong. The dedicated InvalidCredentialsException is
            // the right type here even though the user is logged in
            // (the caller's current password is still a credential).
            throw new InvalidCredentialsException();
        }
        entity.setPasswordHash(hasher.hash(newPassword));
        users.save(entity);
        // Revoke all of the user's sessions. The caller's session
        // is in this set, so the response goes out, the cookie
        // is invalidated client-side on next API call, and the
        // operator has to log in again. This is the only way to
        // guarantee the new password takes effect across all clients.
        sessions.deleteByUserId(userId);
    }

    public void logout(String sessionId) {
        if (sessionId != null) sessions.deleteById(sessionId);
    }

    public Optional<CurrentUser> resolveSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return Optional.empty();
        var session = sessions.findById(sessionId).orElse(null);
        if (session == null) return Optional.empty();
        if (session.getExpiresAtAsInstant() != null
            && session.getExpiresAtAsInstant().isBefore(Instant.now())) {
            sessions.deleteById(sessionId);
            return Optional.empty();
        }
        sessions.touchLastSeen(sessionId, Instant.now());
        var user = users.findById(session.getUserId()).orElse(null);
        if (user == null) return Optional.empty();
        return Optional.of(new CurrentUser(toRecord(user), toRecord(session)));
    }

    private String newId() {
        var bytes = new byte[24];
        rng.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static User toRecord(UserEntity e) {
        return new User(
            e.getId(),
            e.getUsername(),
            e.getPasswordHash(),
            e.getFirstname(),
            e.getLastname(),
            e.getEmail(),
            e.getIsAdmin(),
            e.getCreatedAtAsInstant()
        );
    }

    private static AuthSession toRecord(AuthSessionEntity e) {
        return new AuthSession(
            e.getId(),
            e.getUserId(),
            e.getCreatedAtAsInstant(),
            e.getLastSeenAtAsInstant(),
            e.getExpiresAtAsInstant() == null
                ? Optional.empty()
                : Optional.of(e.getExpiresAtAsInstant())
        );
    }

    public record CurrentUser(User user, AuthSession session) {}
    public record LoginResult(AuthSession session, User user) {}

    public static class UserExistsException extends RuntimeException {
        public UserExistsException(String username) { super("user already exists: " + username); }
    }

    public static class InvalidCredentialsException extends RuntimeException {
        public InvalidCredentialsException() { super("invalid username or password"); }
    }
}
