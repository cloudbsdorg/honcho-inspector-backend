package com.revytechinc.honchoinspector.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.revytechinc.honchoinspector.filter.SessionAuthFilter;
import com.revytechinc.honchoinspector.model.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.DelegatingServletOutputStream;
import org.springframework.web.method.HandlerMethod;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminAuthInterceptorTest {

    private AdminAuthInterceptor interceptor;
    private ByteArrayOutputStream responseBody;

    @BeforeEach
    void setUp() {
        interceptor = new AdminAuthInterceptor(new ObjectMapper());
        responseBody = new ByteArrayOutputStream();
    }

    @Test
    void nonHandlerMethod_passesThrough() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = new FakeHttpResponse(responseBody);

        boolean result = interceptor.preHandle(req, resp, new Object());

        assertThat(result).isTrue();
        assertThat(responseBody.size()).isZero();
    }

    @Test
    void unannotatedHandler_passesThrough() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = new FakeHttpResponse(responseBody);
        HandlerMethod handler = handlerMethod(PlainController.class, "anyMethod");

        boolean result = interceptor.preHandle(req, resp, handler);

        assertThat(result).isTrue();
        assertThat(responseBody.size()).isZero();
    }

    @Test
    void classAnnotated_nonAdmin_returns403() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = new FakeHttpResponse(responseBody);
        HandlerMethod handler = handlerMethod(AdminController.class, "listUsers");
        var nonAdmin = newUser("u1", false);
        when(req.getAttribute(SessionAuthFilter.CURRENT_USER_ATTR))
            .thenReturn(new AuthService.CurrentUser(nonAdmin, fakeSession(nonAdmin.id())));

        boolean result = interceptor.preHandle(req, resp, handler);

        assertThat(result).isFalse();
        assertThat(resp.getStatus()).isEqualTo(403);
        assertThat(resp.getContentType()).isEqualTo("application/json");
        var error = new ObjectMapper().readValue(responseBody.toByteArray(), ErrorResponse.class);
        assertThat(error.error()).isEqualTo("admin only");
    }

    @Test
    void classAnnotated_admin_passesThrough() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = new FakeHttpResponse(responseBody);
        HandlerMethod handler = handlerMethod(AdminController.class, "listUsers");
        var admin = newUser("u1", true);
        when(req.getAttribute(SessionAuthFilter.CURRENT_USER_ATTR))
            .thenReturn(new AuthService.CurrentUser(admin, fakeSession(admin.id())));

        boolean result = interceptor.preHandle(req, resp, handler);

        assertThat(result).isTrue();
        assertThat(responseBody.size()).isZero();
    }

    @Test
    void methodAnnotated_nonAdmin_returns403() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = new FakeHttpResponse(responseBody);
        HandlerMethod handler = handlerMethod(MixedController.class, "adminOnly");
        var nonAdmin = newUser("u1", false);
        when(req.getAttribute(SessionAuthFilter.CURRENT_USER_ATTR))
            .thenReturn(new AuthService.CurrentUser(nonAdmin, fakeSession(nonAdmin.id())));

        boolean result = interceptor.preHandle(req, resp, handler);

        assertThat(result).isFalse();
        assertThat(resp.getStatus()).isEqualTo(403);
    }

    @Test
    void methodAnnotated_admin_passesThrough() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = new FakeHttpResponse(responseBody);
        HandlerMethod handler = handlerMethod(MixedController.class, "adminOnly");
        var admin = newUser("u1", true);
        when(req.getAttribute(SessionAuthFilter.CURRENT_USER_ATTR))
            .thenReturn(new AuthService.CurrentUser(admin, fakeSession(admin.id())));

        boolean result = interceptor.preHandle(req, resp, handler);

        assertThat(result).isTrue();
    }

    @Test
    void unannotatedMethodInAnnotatedClass_passesThrough() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = new FakeHttpResponse(responseBody);
        HandlerMethod handler = handlerMethod(MixedController.class, "openToAll");
        var nonAdmin = newUser("u1", false);
        when(req.getAttribute(SessionAuthFilter.CURRENT_USER_ATTR))
            .thenReturn(new AuthService.CurrentUser(nonAdmin, fakeSession(nonAdmin.id())));

        boolean result = interceptor.preHandle(req, resp, handler);

        assertThat(result).isTrue();
        assertThat(responseBody.size()).isZero();
    }

    @Test
    void classAnnotated_missingCurrentUser_returns403() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = new FakeHttpResponse(responseBody);
        HandlerMethod handler = handlerMethod(AdminController.class, "listUsers");
        when(req.getAttribute(SessionAuthFilter.CURRENT_USER_ATTR)).thenReturn(null);

        boolean result = interceptor.preHandle(req, resp, handler);

        assertThat(result).isFalse();
        assertThat(resp.getStatus()).isEqualTo(403);
    }

    private static HandlerMethod handlerMethod(Class<?> klass, String methodName) throws Exception {
        Method m = klass.getDeclaredMethod(methodName);
        return new HandlerMethod(klass.getDeclaredConstructor().newInstance(), m);
    }

    private static User newUser(String id, boolean isAdmin) {
        return new User(id, "user-" + id, "hash", null, null, null, isAdmin, Instant.now());
    }

    private static AuthSession fakeSession(String userId) {
        return new AuthSession("sess-" + userId, userId, Instant.now(), Instant.now(), Optional.empty());
    }

    static class PlainController {
        public void anyMethod() {}
    }

    @RequireAdmin
    static class AdminController {
        public void listUsers() {}
    }

    static class MixedController {
        @RequireAdmin
        public void adminOnly() {}
        public void openToAll() {}
    }

    static class FakeHttpResponse extends org.springframework.mock.web.MockHttpServletResponse {
        private final ByteArrayOutputStream body;

        FakeHttpResponse(ByteArrayOutputStream body) {
            this.body = body;
        }

        @Override
        public jakarta.servlet.ServletOutputStream getOutputStream() {
            return new DelegatingServletOutputStream(body);
        }
    }
}
