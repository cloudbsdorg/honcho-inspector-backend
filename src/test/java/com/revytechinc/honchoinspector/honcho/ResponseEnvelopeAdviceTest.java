package com.revytechinc.honchoinspector.honcho;

import static org.assertj.core.api.Assertions.assertThat;

import com.revytechinc.honchoinspector.model.ErrorResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit coverage for {@link ResponseEnvelopeAdvice}.
 *
 * <p>The advice has a single responsibility: wrap every successful
 * Honcho proxy controller return in a uniform
 * {@code {data, error, meta}} envelope so the frontend has a
 * single predictable contract. Error bodies
 * ({@code ErrorResponse} records and {@code Map.of("error", ...)}
 * from the {@code HonchoCallException} handler) are passed through
 * unwrapped so the existing 4xx/5xx test contract
 * ({@code jsonPath("$.error")} or {@code jsonPath("$.data.error")}
 * for record-wrapped errors) is preserved.
 *
 * <p>The most important test is {@link #wrapsNullBodyAsEnvelope()}
 * — without it, a Spring Boot 4 + Jackson 3 config change
 * (specifically {@code default-property-inclusion: non_null} on
 * the auto-configured ObjectMapper) would silently drop the
 * envelope's null fields and emit {@code {}} on the wire for the
 * null-body case, leaving the frontend unable to disambiguate
 * "no card yet" from "Honcho returned an empty object" from
 * "Honcho timed out".
 */
class ResponseEnvelopeAdviceTest {

    private ResponseEnvelopeAdvice advice;
    private ObjectMapper mapper;
    private ServerHttpRequest request;
    private ServerHttpResponse response;
    private MethodParameter returnParam;
    private final Class<? extends HttpMessageConverter<?>> jackson = JacksonJsonHttpMessageConverter.class;
    @SuppressWarnings("unchecked")
    private final Class<? extends HttpMessageConverter<?>> stringConverter =
        (Class<? extends HttpMessageConverter<?>>) (Class<?>) StringHttpMessageConverter.class;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        // The advice uses an injected ObjectMapper. We construct one
        // with default settings (no default-property-inclusion
        // override) to mirror what Spring Boot 4 wires in when the
        // application.yml leaves the global default at `always`.
        mapper = JsonMapper.builder().build();
        advice = new ResponseEnvelopeAdvice(mapper);
        request = new ServletServerHttpRequest(new MockHttpServletRequest());
        MockHttpServletResponse mockResp = new MockHttpServletResponse();
        response = new ServletServerHttpResponse(mockResp);
        // MethodParameter is awkward to construct without a Method;
        // any return type will do for these tests.
        returnParam = new MethodParameter(
            String.class.getMethod("toString"), -1);
    }

    @Test
    @DisplayName("supports() returns true so the advice runs on every controller")
    void supportsReturnsTrue() {
        assertThat(advice.supports(returnParam, jackson)).isTrue();
        assertThat(advice.supports(returnParam, stringConverter)).isTrue();
    }

    @Nested
    @DisplayName("success-path wrapping")
    class SuccessWrapping {

        @Test
        @DisplayName("null body is wrapped as {data:null, error:null, meta:null}")
        void wrapsNullBodyAsEnvelope() throws Exception {
            Object result = advice.beforeBodyWrite(
                null, returnParam,
                MediaType.APPLICATION_JSON, jackson, request, response);
            // Spring Boot 4's Jackson 3 ObjectMapper will serialize
            // null map values when default-property-inclusion is
            // `always` (the application.yml default for the proxy
            // controllers). The wire body MUST be
            // {"data":null,"error":null,"meta":null}, not {} —
            // otherwise the frontend can't disambiguate "Honcho
            // returned an empty object" from "no payload".
            assertThat(result).isInstanceOf(Map.class);
            Map<?, ?> envelope = (Map<?, ?>) result;
            assertThat(envelope).hasSize(3);
            assertThat(envelope.keySet().toArray()).containsExactly((Object) "data", (Object) "error", (Object) "meta");
            assertThat(envelope.get("data")).isNull();
            assertThat(envelope.get("error")).isNull();
            assertThat(envelope.get("meta")).isNull();
            // The contract test: the advice's own ObjectMapper
            // serializes the envelope with all three keys, even
            // when values are null. If a future change to
            // application.yml flips default-property-inclusion to
            // non_null, this test fails immediately.
            String json = mapper.writeValueAsString(envelope);
            assertThat(json)
                .as("envelope must serialize with all three keys present, even when null")
                .isEqualTo("{\"data\":null,\"error\":null,\"meta\":null}");
        }

        @Test
        @DisplayName("Map body is wrapped as {data: <map>, error:null, meta:null}")
        void wrapsMapBody() throws Exception {
            Map<String, Object> body = Map.of("hello", "world");
            Object result = advice.beforeBodyWrite(
                body, returnParam, MediaType.APPLICATION_JSON, jackson, request, response);
            assertThat(result).isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> envelope = (Map<String, Object>) result;
            assertThat(envelope.get("data")).isEqualTo(Map.of("hello", "world"));
            assertThat(envelope.get("error")).isNull();
            assertThat(envelope.get("meta")).isNull();
        }

        @Test
        @DisplayName("String body via Jackson converter is wrapped as {data: <string>, ...}")
        void wrapsStringBodyViaJackson() throws Exception {
            // Most String bodies (e.g. peer representation) are
            // written by StringHttpMessageConverter, but if the
            // content negotiation picks Jackson instead, the
            // advice still produces a Map envelope.
            Object result = advice.beforeBodyWrite(
                "## Honcho knows X.", returnParam,
                MediaType.APPLICATION_JSON, jackson, request, response);
            assertThat(result).isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> envelope = (Map<String, Object>) result;
            assertThat(envelope.get("data")).isEqualTo("## Honcho knows X.");
        }

        @Test
        @DisplayName("List body is wrapped as {data: <list>, ...}")
        void wrapsListBody() throws Exception {
            Object result = advice.beforeBodyWrite(
                List.of("a", "b", "c"), returnParam,
                MediaType.APPLICATION_JSON, jackson, request, response);
            assertThat(result).isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> envelope = (Map<String, Object>) result;
            assertThat(envelope.get("data")).isEqualTo(List.of("a", "b", "c"));
        }

        @Test
        @DisplayName("String body via StringHttpMessageConverter is serialized as a JSON string")
        void wrapsStringBodyViaStringConverter() throws Exception {
            // Honcho v3 representation returns a markdown string.
            // Spring's StringHttpMessageConverter is selected for
            // String bodies. If the advice returned a Map, the
            // converter's writeInternal would ClassCastException.
            // The advice serializes the envelope to a JSON String
            // so the same converter writes it cleanly.
            Object result = advice.beforeBodyWrite(
                "## Honcho knows X.", returnParam,
                MediaType.APPLICATION_JSON, stringConverter, request, response);
            assertThat(result).isInstanceOf(String.class);
            String json = (String) result;
            assertThat(json).isEqualTo("{\"data\":\"## Honcho knows X.\",\"error\":null,\"meta\":null}");
        }
    }

    @Nested
    @DisplayName("error-body pass-through")
    class ErrorPassThrough {

        @Test
        @DisplayName("ErrorResponse record is wrapped as {data: <record>, error:null, meta:null}")
        void errorResponseRecordIsWrapped() {
            // HonchoController.call() returns new ErrorResponse("...")
            // for 400/401/404 paths. The record is not a Map so the
            // pass-through checks don't fire; the advice wraps it as
            // {data: ErrorResponse, error:null, meta:null}. The
            // existing tests assert jsonPath("$.data.error") (Jackson
            // serializes the ErrorResponse as a Map with one key),
            // which is the wrapped form.
            ErrorResponse body = new ErrorResponse("profile not found");
            Object result = advice.beforeBodyWrite(
                body, returnParam, MediaType.APPLICATION_JSON, jackson, request, response);
            assertThat(result).isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> envelope = (Map<String, Object>) result;
            assertThat(envelope.get("data")).isSameAs(body);
            assertThat(envelope.get("error")).isNull();
            assertThat(envelope.get("meta")).isNull();
        }

        @Test
        @DisplayName("Map with `error` key passes through unwrapped (HonchoCallException 5xx)")
        void mapWithErrorKeyPassesThrough() {
            // The HonchoCallException catch in call() returns
            // Map.of("error", e.getMessage(), "body", e.body()).
            // Pass it through so tests asserting jsonPath("$.error")
            // keep working.
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "upstream down");
            body.put("body", "");
            Object result = advice.beforeBodyWrite(
                body, returnParam, MediaType.APPLICATION_JSON, jackson, request, response);
            assertThat(result).isSameAs(body);
        }

        @Test
        @DisplayName("Map with both `error` and `data` keys is wrapped (defense-in-depth)")
        void mapWithBothErrorAndDataIsWrapped() {
            // A future controller that returns {error: ..., data: ...}
            // for partial-success semantics would be wrapped by the
            // envelope (the {data, error, meta} shape) so the
            // frontend sees a uniform contract. The single-key
            // pass-through only fires when the body has `error`
            // and NOT `data`, which is the HonchoCallException
            // catch's exact shape.
            Map<String, Object> body = Map.of("error", "x", "data", "y");
            Object result = advice.beforeBodyWrite(
                body, returnParam, MediaType.APPLICATION_JSON, jackson, request, response);
            assertThat(result).isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> envelope = (Map<String, Object>) result;
            // Wrapped: data is the whole original map
            assertThat(envelope.get("data")).isEqualTo(Map.of("error", "x", "data", "y"));
        }

        @Test
        @DisplayName("Map that is already an envelope passes through (no double-wrap)")
        void alreadyEnvelopePassesThrough() {
            // Defends against nested wrapping. If a controller
            // returns a map that already has the {data, error,
            // meta} shape (e.g. from a downstream service), the
            // advice passes it through unchanged.
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("data", "x");
            body.put("error", null);
            body.put("meta", null);
            Object result = advice.beforeBodyWrite(
                body, returnParam, MediaType.APPLICATION_JSON, jackson, request, response);
            assertThat(result).isSameAs(body);
        }
    }
}
