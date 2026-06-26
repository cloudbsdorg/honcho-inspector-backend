package com.revytechinc.honchoinspector.auth;

import com.revytechinc.honchoinspector.auth.entity.AuthSessionEntity;
import com.revytechinc.honchoinspector.auth.entity.UserEntity;
import com.revytechinc.honchoinspector.auth.repo.AuthSessionRepository;
import com.revytechinc.honchoinspector.auth.repo.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

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
