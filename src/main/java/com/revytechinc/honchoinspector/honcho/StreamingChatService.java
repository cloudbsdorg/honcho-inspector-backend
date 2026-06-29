package com.revytechinc.honchoinspector.honcho;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads an SSE byte stream from Honcho's
 * {@code POST /v3/workspaces/{ws}/peers/{peerId}/chat} (sent with
 * {@code Accept: text/event-stream}) and writes a per-chunk envelope back
 * to the operator's response stream:
 *
 * <pre>
 *   data: {"data":{"text":"&lt;chunk&gt;"},"meta":{"done":false}}\n\n
 *   ...
 *   data: {"data":{"text":""},"meta":{"done":true}}\n\n
 * </pre>
 *
 * <h2>Chain-of-thought stripping</h2>
 * <p>Honcho v3 emits Honcho-side reasoning inside {@code ...} blocks
 * (think tags). The chat popout UI only wants the visible answer, so
 * this service drops everything between matched {@code } and {@code }
 * pairs in real time as bytes arrive from Honcho. State is a single
 * flag that flips {@code true} on the first {@code }, back to
 * {@code false} on the next {@code }, and never resets across chunks
 * (so a CoT block that spans multiple Honcho SSE events is skipped
 * end-to-end).
 *
 * <h2>Per-chunk data line shape</h2>
 * <ul>
 *   <li>{@code {"delta":{"content":"..."},"done":false}} — normal partial text.
 *       The {@code text} becomes {@code data.text} in the outbound envelope;
 *       CoT inside the content is stripped per the rule above.</li>
 *   <li>{@code {"delta":{},"done":true}} — terminal marker. The service
 *       emits the final envelope ({@code "done":true}) and stops.</li>
 *   <li>Anything else (heartbeat comments, {@code id:}, {@code event:},
 *       {@code retry:}) is silently ignored.</li>
 * </ul>
 *
 * <h2>Design notes</h2>
 * <ul>
 *   <li>Each Honcho data line is parsed, CoT-stripped, and written to the
 *       output stream within the same loop iteration.
 *       {@link OutputStream#flush()} is called after every envelope so the
 *       operator's browser sees incremental chunks as they leave Honcho.</li>
 *   <li>The output stream is wrapped in {@code OutputStreamWriter(UTF-8)};
 *       line boundaries are written as {@code "\n\n"} to separate SSE
 *       events.</li>
 *   <li>{@link IOException} propagates so the surrounding
 *       {@code StreamingResponseBody} (or unit-test harness) can see a
 *       broken upstream pipe and let the controller emit an audit row.</li>
 *   <li>The signature takes an {@link OutputStream} because that's what
 *       Spring's {@code StreamingResponseBody} callback provides. Wrapping
 *       in a {@link Writer} happens here so the unit test (which passes
 *       a {@link java.io.ByteArrayOutputStream}) and the controller share
 *       the same code path.</li>
 * </ul>
 *
 * <h2>Termination semantics</h2>
 * <ul>
 *   <li>If the upstream hits {@code done:true} mid-stream, the final
 *       envelope is {@code {"data":{"text":""},"meta":{"done":true}}}.</li>
 *   <li>If we reach end-of-stream with the CoT block still open, the final
 *       envelope also reports {@code done:true} and includes a
 *       {@code truncatedChars} count in {@code meta} so the operator knows
 *       the CoT was abruptly cut off.</li>
 * </ul>
 */
public final class StreamingChatService {

    static final String THINK_OPEN = "<" + "think" + ">";
    static final String THINK_CLOSE = "<" + "/think" + ">";

    private StreamingChatService() {}

    /**
     * Stream Honcho's SSE response to {@code out}, emitting per-chunk
     * {@code {data, error, meta}} envelopes as SSE events.
     *
     * @param out          the operator's response stream; written to but NOT closed (caller owns)
     * @param honchoStream Honcho's SSE byte stream; read to EOF or until {@link IOException}
     * @param peerId       the Honcho peer id; included as {@code meta.peerId} for debug
     * @param mapper       the JSON encoder; must be non-null
     * @return the number of non-terminal envelopes emitted (terminal event not counted)
     * @throws IOException if the upstream stream or the downstream write errors
     */
    public static long stream(
        OutputStream out,
        InputStream honchoStream,
        String peerId,
        ObjectMapper mapper
    ) throws IOException {
        if (mapper == null) {
            throw new IllegalArgumentException("ObjectMapper is required");
        }

        Writer writer = new OutputStreamWriter(new BufferedOutputStream(out), StandardCharsets.UTF_8);
        long chunks = 0;
        CotState state = new CotState();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(honchoStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith(":")) {
                    continue;
                }
                if (!line.startsWith("data:")) {
                    continue;
                }
                String payload = line.length() <= 5 ? "" : line.substring(5).trim();
                if (payload.isEmpty()) {
                    continue;
                }
                JsonNode root;
                try {
                    root = mapper.readTree(payload);
                } catch (Exception parseFailure) {
                    continue;
                }
                if (root == null || root.isMissingNode() || root.isNull()) {
                    continue;
                }
                boolean done = root.path("done").asBoolean(false);
                JsonNode delta = root.path("delta");
                String content = delta.isMissingNode() || delta.isNull()
                    ? null
                    : delta.path("content").asText((String) null);

                if (done) {
                    Map<String, Object> meta = new LinkedHashMap<>();
                    meta.put("done", true);
                    meta.put("peerId", peerId);
                    if (state.open) {
                        meta.put("truncatedChars", state.truncated);
                    }
                    writeEnvelope(writer, "", meta, mapper);
                    writer.flush();
                    return chunks;
                }
                if (content == null) {
                    continue;
                }
                String visible = stripThink(content, state);

                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("done", false);
                meta.put("peerId", peerId);
                writeEnvelope(writer, visible, meta, mapper);
                writer.flush();
                chunks++;
            }
        } finally {
            try {
                writer.flush();
            } catch (IOException ignored) {
                // Stream may already be closed; nothing to do.
            }
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("done", true);
        meta.put("peerId", peerId);
        if (state.open) {
            meta.put("truncatedChars", state.truncated);
        }
        writeEnvelope(writer, "", meta, mapper);
        writer.flush();
        return chunks;
    }

    static String stripThink(String content, CotState state) {
        StringBuilder out = new StringBuilder(content.length());
        int i = 0;
        while (i < content.length()) {
            if (!state.open) {
                int openIdx = content.indexOf(THINK_OPEN, i);
                if (openIdx < 0) {
                    out.append(content, i, content.length());
                    return out.toString();
                }
                out.append(content, i, openIdx);
                state.open = true;
                i = openIdx + THINK_OPEN.length();
            } else {
                int closeIdx = content.indexOf(THINK_CLOSE, i);
                if (closeIdx < 0) {
                    state.truncated += content.length() - i;
                    return out.toString();
                }
                state.open = false;
                i = closeIdx + THINK_CLOSE.length();
            }
        }
        return out.toString();
    }

    private static void writeEnvelope(
        Writer writer,
        String text,
        Map<String, Object> meta,
        ObjectMapper mapper
    ) throws IOException {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("data", Map.of("text", text));
        envelope.put("error", null);
        envelope.put("meta", meta);
        writer.write("data: ");
        writer.write(mapper.writeValueAsString(envelope));
        writer.write("\n\n");
    }

    static final class CotState {
        boolean open;
        int truncated;
    }
}
