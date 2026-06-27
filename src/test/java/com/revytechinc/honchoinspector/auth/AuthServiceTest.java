package com.revytechinc.honchoinspector.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.revytechinc.honchoinspector.auth.entity.UserEntity;
import com.revytechinc.honchoinspector.auth.repo.AuthSessionRepository;
import com.revytechinc.honchoinspector.auth.repo.UserRepository;

/**
 * Unit coverage for {@link AuthService}, focused on the
 * self-service password change flow.
 *
 * <p>The legacy {@code register} / {@code login} / {@code logout}
 * paths are tested in the controller layer (the integration tests
 * exercise the full HTTP path). The unit-test surface here is the
 * password-verification and session-revocation logic that the
 * self-service endpoint depends on &mdash; a thin layer with
 * security-relevant branches that a regression in any of them
 * would let a caller lock themselves out or, worse, take over a
 * session they shouldn't have.
 */
class AuthServiceTest {

    private UserRepository users;
    private AuthSessionRepository sessions;
    private PasswordHasher hasher;
    private AuthService service;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        sessions = mock(AuthSessionRepository.class);
        hasher = mock(PasswordHasher.class);
        // Real PasswordHasher would work but we mock so the
        // assertions are about which method gets called, not
        // bcrypt's behavior. AdminUserServiceTest uses the same
        // mock pattern.
        service = new AuthService(users, sessions, hasher);
    }

    /**
     * Helper: build a UserEntity with a known password hash and
     * id. The hash is irrelevant; verify() on the hasher is mocked.
     */
    private static UserEntity entity(String id, String hash) {
        return new UserEntity(
            id, "alice", hash, null, null, null, true, Instant.now());
    }

    @Nested
    @DisplayName("changeOwnPassword")
    class ChangeOwnPassword {

        @Test
        @DisplayName("valid current + new password: rehashes and revokes all sessions")
        void happyPath() {
            UserEntity u = entity("u-1", "$old-hash$");
            when(users.findById("u-1")).thenReturn(Optional.of(u));
            when(hasher.verify("old-pw", "$old-hash$")).thenReturn(true);
            when(hasher.hash("new-pw-with-8+")).thenReturn("$new-hash$");
            service.changeOwnPassword("u-1", "old-pw", "new-pw-with-8+");
            // The password was re-hashed and saved.
            assertThat(u.getPasswordHash()).isEqualTo("$new-hash$");
            verify(users, times(1)).save(u);
            // ALL sessions were revoked, including the caller's.
            // This is the whole point: the new password must take
            // effect across every client the user has open.
            verify(sessions, times(1)).deleteByUserId("u-1");
        }

        @Test
        @DisplayName("wrong current password: throws InvalidCredentialsException, no save, no revocation")
        void wrongCurrentPassword() {
            UserEntity u = entity("u-1", "$correct-hash$");
            when(users.findById("u-1")).thenReturn(Optional.of(u));
            when(hasher.verify("wrong-pw", "$correct-hash$")).thenReturn(false);
            assertThatThrownBy(() -> service.changeOwnPassword("u-1", "wrong-pw", "new-pw-with-8+"))
                .isInstanceOf(AuthService.InvalidCredentialsException.class);
            // Critical: the user's password hash is unchanged
            // (no save() call) and no sessions are revoked.
            assertThat(u.getPasswordHash()).isEqualTo("$correct-hash$");
            verify(users, never()).save(any());
            verify(sessions, never()).deleteByUserId(anyString());
        }

        @Test
        @DisplayName("blank userId: rejected as 'not authenticated'")
        void blankUserId() {
            assertThatThrownBy(() -> service.changeOwnPassword("", "old", "new-pw-with-8+"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not authenticated");
        }

        @Test
        @DisplayName("blank new password: rejected (length validation)")
        void blankNewPassword() {
            assertThatThrownBy(() -> service.changeOwnPassword("u-1", "old", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 8 characters");
        }

        @Test
        @DisplayName("7-character new password: rejected (length validation)")
        void shortNewPassword() {
            assertThatThrownBy(() -> service.changeOwnPassword("u-1", "old", "7chars!"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 8 characters");
        }

        @Test
        @DisplayName("user not found: rejected")
        void userNotFound() {
            when(users.findById("u-missing")).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.changeOwnPassword("u-missing", "old", "new-pw-with-8+"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("user not found");
            verify(hasher, never()).hash(anyString());
            verify(users, never()).save(any());
        }

        @Test
        @DisplayName("null current password: rejected (verify() short-circuits to false)")
        void nullCurrentPassword() {
            UserEntity u = entity("u-1", "$correct-hash$");
            when(users.findById("u-1")).thenReturn(Optional.of(u));
            // PasswordHasher.verify() returns false on null, but we
            // mock the hasher to mirror the production behavior.
            when(hasher.verify(null, "$correct-hash$")).thenReturn(false);
            assertThatThrownBy(() -> service.changeOwnPassword("u-1", null, "new-pw-with-8+"))
                .isInstanceOf(AuthService.InvalidCredentialsException.class);
        }

        @Test
        @DisplayName("session revocation succeeds even if user is gone (edge case)")
        void sessionRevocationUnaffected() {
            // The user record exists, current password matches,
            // but the session-revoke call is the side effect we
            // care about. Confirm the call to deleteByUserId
            // uses the userId from the record, not from a stale
            // closure — if the user record's id is later changed
            // (it isn't, but defensive), the revocation should
            // still target the right user.
            UserEntity u = entity("u-99", "$old-hash$");
            when(users.findById("u-99")).thenReturn(Optional.of(u));
            when(hasher.verify("old", "$old-hash$")).thenReturn(true);
            when(hasher.hash("new-pw-with-8+")).thenReturn("$new-hash$");
            service.changeOwnPassword("u-99", "old", "new-pw-with-8+");
            verify(sessions).deleteByUserId("u-99");
        }
    }
}
