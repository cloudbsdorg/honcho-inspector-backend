package com.revytechinc.honchoinspector.honcho;

import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
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
        // Spring calls supports() per (returnType, converterType)
        // pair. We return true for every non-String pair. For
        // String bodies (raw String or ResponseEntity<String>), we
        // let StringHttpMessageConverter handle them; the
        // converter will write the raw String. Our beforeBodyWrite
        // is only invoked for converterTypes that DO support the
        // body, so a Map body never gets routed here for String
        // converterTypes. We just return true unconditionally.
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
        // If the body is already an envelope, pass it through.
        if (body instanceof java.util.Map<?, ?> m
            && m.containsKey("data")
            && m.containsKey("error")
            && m.containsKey("meta")
        ) {
            return body;
        }
        if (body == null) {
            java.util.Map<String, Object> nullEnvelope = new java.util.LinkedHashMap<>();
            nullEnvelope.put("data", null);
            nullEnvelope.put("error", null);
            nullEnvelope.put("meta", null);
            return nullEnvelope;
        }
        // Serialize the envelope to a JSON String ourselves and
        // return that. This is the cleanest fix for the converter
        // dispatch problem: by returning a String, we let
        // StringHttpMessageConverter handle the write step (it
        // writes raw String bodies without any conversion), and the
        // Content-Type we set on the response drives the charset
        // (UTF-8). Returning a Map<String,Object> instead would let
        // Spring pick a converter based on the body type, but the
        // chain's StringHttpMessageConverter claims Object first
        // and ClassCasts to String at writeInternal.
        java.util.Map<String, Object> envelope = new java.util.LinkedHashMap<>();
        envelope.put("data", body);
        envelope.put("error", null);
        envelope.put("meta", null);
        try {
            return mapper.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new RuntimeException("envelope serialization failed", e);
        }
    }
}
