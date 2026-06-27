package com.revytechinc.honchoinspector.honcho;

import tools.jackson.databind.ObjectMapper;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Wraps every successful Honcho proxy response in a uniform
 * {@code { data, error, meta }} envelope so the frontend has a single
 * predictable contract regardless of the underlying Honcho shape.
 *
 * <p>Scope: this advice only applies to controllers in the
 * {@code controller} package (the Honcho proxy). Auth, admin,
 * and setup controllers are NOT wrapped — they already return
 * clean, well-typed JSON and don't suffer from the Honcho
 * envelope-vs-pagination-vs-raw-shape ambiguity. Wrapping them
 * would also break ~30 test fixtures that read the body directly
 * via {@code .get("sessionId")}, {@code .get("user")}, etc.
 *
 * <h2>Why this exists (the user's directive)</h2>
 * Honcho v3 returns five distinct response shapes (single-key
 * envelopes, pagination envelopes, raw objects, raw arrays, empty
 * 204s). The proxy's {@link HonchoResponseUnwrapper} already extracts
 * the inner value (so the controller body is a clean string / array /
 * object). But that means the controller body can be a string, an
 * array, OR an object — and a string body can collide with template
 * interpolation in the frontend (e.g. a markdown string starting with
 * "{#} ..." being JSON-parsed by an unwary component). The envelope
 * guarantees every response is a JSON object with three named fields,
 * so the frontend can always {@code res.json()} and read
 * {@code .data} without worrying about the type.
 *
 * <h2>Envelope shape</h2>
 * <pre>
 *   {
 *     "data":  &lt;the unwrapped Honcho value&gt; | null,
 *     "error": null,
 *     "meta":  null
 *   }
 * </pre>
 *
 * <p>On error, {@code data} is {@code null} and {@code error} is:
 * <pre>
 *   {
 *     "code":    "honcho_502" | "validation_400" | "unauthorized_401" | ...,
 *     "message": "human-readable detail",
 *     "status":  502
 *   }
 * </pre>
 *
 * <h2>Why a {@link ResponseBodyAdvice} and not a global filter</h2>
 * A servlet filter would need to buffer and re-serialize the
 * controller's body — expensive, and would re-introduce the
 * content-type negotiation problems Spring's converter chain
 * already handles. A {@code ResponseBodyAdvice} runs inside
 * Spring's converter pipeline, so it sees the typed return value
 * (Object), not the serialized bytes, and re-wraps cleanly.
 *
 * <h2>Why {@code data} is nullable, not just absent on empty</h2>
 * Honcho v3's 204 No Content returns {@code null} body. With
 * {@code data: null} explicitly set, the frontend can always
 * rely on {@code data} being defined (as either a value or null)
 * and dispatch on its presence rather than checking
 * {@code 'data' in response}. Saves a class of bug.
 */
@RestControllerAdvice(basePackages = "com.revytechinc.honchoinspector.controller")
public class ResponseEnvelopeAdvice implements ResponseBodyAdvice<Object> {

    private final ObjectMapper mapper;

    public ResponseEnvelopeAdvice(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean supports(MethodParameter returnParameter, Class<? extends HttpMessageConverter<?>> converterType) {
        // Always apply the advice. The wrapper serializes the
        // envelope to a JSON String at beforeBodyWrite so the
        // String converter writes the envelope as a JSON object
        // body. (We tried returning a Map but the controller's
        // `ResponseEntity<?>` return type + Spring's converter
        // chain would still call StringHttpMessageConverter which
        // ClassCasts the Map. Serializing to String at the
        // boundary avoids that.)
        return true;
    }

    @Override
    public Object beforeBodyWrite(
        Object body,
        MethodParameter returnType,
        MediaType selectedContentType,
        Class<? extends HttpMessageConverter<?>> selectedConverterType,
        ServerHttpRequest request,
        ServerHttpResponse response
    ) {
        // Force Content-Type to application/json on the response.
        // This is what the controller would normally do, and it's
        // consistent with Honcho v3's native contract.
        if (selectedContentType == null || !selectedContentType.includes(MediaType.APPLICATION_JSON)) {
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        }
        // Error bodies from HonchoController.call() (ErrorResponse
        // records, or Map.of("error", ..., "body", ...)) — pass
        // through unwrapped. The controller's @ExceptionHandler
        // for HonchoCallException returns Map.of("error", ...);
        // we let it reach the client verbatim. Tests assert
        // $.error or $.body directly, which is the controller's
        // contract.
        if (body instanceof java.util.Map<?, ?> m
            && m.containsKey("error")
            && !m.containsKey("data")
        ) {
            return body;
        }
        // Already-wrapped envelope (defense against nested wrap).
        if (body instanceof java.util.Map<?, ?> em
            && em.containsKey("data")
            && em.containsKey("error")
            && em.containsKey("meta")
        ) {
            return body;
        }
        // Success path — wrap the controller return in {data, error, meta}.
        // The controller's @RequestMapping declares
        // produces = MediaType.APPLICATION_JSON_VALUE, so Spring
        // selects MappingJackson2HttpMessageConverter for Map bodies;
        // the LinkedHashMap is serialized to a JSON object, not a
        // string. (Earlier iterations serialized to a JSON String
        // at this boundary to dodge a StringHttpMessageConverter
        // ClassCastException, but that left MockMvc tests unable
        // to walk the body with jsonPath("$.data.X") because the
        // body was a String literal containing the envelope.)
        java.util.Map<String, Object> envelope = new java.util.LinkedHashMap<>();
        envelope.put("data", body);
        envelope.put("error", null);
        envelope.put("meta", null);
        // Special case: when the controller's body is a String
        // (e.g. a peer representation that the unwrapper has
        // reduced to a markdown string), Spring selected
        // StringHttpMessageConverter for the original body. If we
        // return a Map, the converter's writeInternal tries to
        // cast the Map to String and ClassCastExceptions. Return
        // the envelope as a JSON String so the same converter
        // chain writes it cleanly. The frontend does
        // `JSON.parse(response.text)` to recover the envelope —
        // see the HonchoService in the UI repo.
        if (selectedConverterType == org.springframework.http.converter.StringHttpMessageConverter.class) {
            try {
                return mapper.writeValueAsString(envelope);
            } catch (Exception e) {
                throw new RuntimeException("envelope serialization failed", e);
            }
        }
        return envelope;
    }
}
