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

/**
 * Reads an SSE byte stream from Honcho's
 * {@code POST /v3/workspaces/{ws}/peers/{peerId}/chat} (sent with
 * {@code Accept: text/event-stream}) and writes each visible chunk
 * straight back to the operator's response stream in Honcho's
 * native wire format:
 *
 * <pre>
 *   data: &lt;chunk&gt;\n\n
 *   ...
 *   data: [DONE]\n\n
 * </pre>
 *
 * <h2>Why raw text instead of a JSON envelope</h2>
 * <p>Honcho's chat endpoint already streams
 * {@code data: &lt;text&gt;\n\n} chunks of the form
 * {@code {"delta":{"content":"..."},"done":false}}. Wrapping that
 * text in a project-local envelope ({@code {"data":{"text":"..."},"meta":{...}}})
 * forces the browser to JSON-parse every chunk before showing
 * anything to the user, which both wastes CPU and (more visibly)
 * collapses Honcho's bursty chunking into a single update at
 * {@code done:true} time — the operator sees nothing stream.
 * Forwarding the visible text verbatim and using the well-known
 * {@code data: [DONE]\n\n} sentinel for the terminal event
 * eliminates the reparse, lets the chat popout's typing
 * animation paint word-by-word, and stays compatible with every
 * SSE client that knows the {@code data: [DONE]} idiom.
 *
 * <h2>Chain-of-thought stripping</h2>
 * <p>Honcho v3 emits Honcho-side reasoning inside
 * {@code <think>...</think>} blocks. The chat popout only wants
 * the visible answer, so this service drops everything between
 * matched {@code <think>} and {@code </think>} pairs in real time
 * as bytes arrive from Honcho. State is a single flag that flips
 * {@code true} on the first opener, back to {@code false} on the
 * next closer, and never resets across chunks (so a CoT block
 * that spans multiple Honcho SSE events is skipped end-to-end).
 *
 * <h2>Honcho wire format</h2>
 * <ul>
 *   <li>{@code data: {"delta":{"content":"..."},"done":false}} —
 *       normal partial text. The {@code content} is CoT-stripped
 *       and emitted as {@code data: <visible>\n\n}. Empty visible
 *       chunks (the entire chunk is inside a {@code <think>}
 *       block, or there is no {@code content} field at all) are
 *       dropped — no {@code data:} line is written, so the
 *       operator doesn't see blank ticks.</li>
 *   <li>{@code data: {"delta":{},"done":true}} — terminal marker.
 *       The service emits {@code data: [DONE]\n\n} and stops.</li>
 *   <li>Anything else (heartbeat comments, {@code id:}, {@code event:},
 *       {@code retry:}) is silently ignored.</li>
 * </ul>
 *
 * <h2>Design notes</h2>
 * <ul>
 *   <li>Each Honcho data line is parsed, CoT-stripped, and written
 *       to the output stream within the same loop iteration.
 *       {@link OutputStream#flush()} is called after every visible
 *       chunk so the operator's browser sees incremental text as
 *       it leaves Honcho.</li>
 *   <li>The output stream is wrapped in
 *       {@code OutputStreamWriter(UTF-8)}; line boundaries are
 *       written as {@code "\n\n"} to separate SSE events.</li>
 *   <li>{@link IOException} propagates so the surrounding
 *       {@code StreamingResponseBody} (or unit-test harness) can
 *       see a broken upstream pipe and let the controller emit an
 *       audit row.</li>
 *   <li>The signature takes an {@link OutputStream} because that's
 *       what Spring's {@code StreamingResponseBody} callback
 *       provides. Wrapping in a {@link Writer} happens here so the
 *       unit test (which passes a
 *       {@link java.io.ByteArrayOutputStream}) and the controller
 *       share the same code path.</li>
 *   <li>The terminal sentinel is the literal string {@code [DONE]},
 *       not a JSON object. This is the same convention OpenAI's
 *       v1 chat completions stream used and is unambiguous in
 *       SSE.</li>
 * </ul>
 *
 * <h2>Termination semantics</h2>
 * <ul>
 *   <li>If the upstream hits {@code done:true} mid-stream, the
 *       final outbound event is {@code data: [DONE]\n\n}.</li>
 *   <li>If we reach end-of-stream with the CoT block still open,
 *       the final outbound event is also {@code data: [DONE]\n\n}.
 *       No {@code truncatedChars} hint is emitted on the wire;
 *       the popout just closes.</li>
 * </ul>
 */
public final class StreamingChatService {

    static final String THINK_OPEN = "<" + "think" + ">";
    static final String THINK_CLOSE = "<" + "/think" + ">";
    /** Outbound SSE terminal sentinel (Honcho v3 native idiom). */
    static final String DONE_SENTINEL = "[DONE]";

    private StreamingChatService() {}

    /**
     * Stream Honcho's SSE response to {@code out}, emitting each
     * visible chunk as a raw-text {@code data:} line and
     * {@code data: [DONE]\n\n} as the terminal event.
     *
     * @param out          the operator's response stream; written to but NOT closed (caller owns)
     * @param honchoStream Honcho's SSE byte stream; read to EOF or until {@link IOException}
     * @param peerId       the Honcho peer id; reserved for future debug metadata (currently unused on the wire)
     * @param mapper       the JSON encoder; must be non-null
     * @return the number of visible chunks emitted (terminal event not counted)
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
                if (done) {
                    writeDone(writer);
                    writer.flush();
                    return chunks;
                }
                JsonNode delta = root.path("delta");
                String content = delta.isMissingNode() || delta.isNull()
                    ? null
                    : delta.path("content").asText((String) null);
                if (content == null) {
                    continue;
                }
                String visible = stripThink(content, state);
                if (visible.isEmpty()) {
                    // Entire chunk was inside a <think>...</think>
                    // block (or was just whitespace). Skip the
                    // outbound data: line so the operator doesn't
                    // see a blank tick.
                    continue;
                }
                writeChunk(writer, visible);
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
        // Upstream closed without an explicit done:true; still
        // send the terminal sentinel so the operator's reader
        // exits cleanly.
        writeDone(writer);
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

    private static void writeChunk(Writer writer, String text) throws IOException {
        // SSE: a single data: line per event, then a blank line
        // to separate events. Honcho's native format is the same;
        // we just forward the visible text after CoT stripping.
        writer.write("data: ");
        writer.write(text);
        writer.write("\n\n");
    }

    private static void writeDone(Writer writer) throws IOException {
        writer.write("data: ");
        writer.write(DONE_SENTINEL);
        writer.write("\n\n");
    }

    static final class CotState {
        boolean open;
        int truncated;
    }
}
