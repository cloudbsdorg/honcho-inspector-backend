package com.revytechinc.honchoinspector.honcho;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

class StreamingChatServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("textOnly emits every chunk verbatim and closes with data: [DONE]")
    void textOnlyStreamsEverything() throws Exception {
        var upstream = """
            data: {"delta":{"content":"Hello"},"done":false}

            data: {"delta":{"content":"world"},"done":false}

            data: {"delta":{"content":"!"},"done":false}

            data: {"done":true}

            """;
        List<Outbound> events = run(upstream);
        assertThat(events).hasSize(4);
        assertThat(events.get(0).text()).isEqualTo("Hello");
        assertThat(events.get(0).done()).isFalse();
        assertThat(events.get(1).text()).isEqualTo("world");
        assertThat(events.get(2).text()).isEqualTo("!");
        assertThat(events.get(3).text()).isEqualTo(StreamingChatService.DONE_SENTINEL);
        assertThat(events.get(3).done()).isTrue();
    }

    @Test
    @DisplayName("single CoT block stripping — visible text starts after the close tag")
    void stripsSingleThinkBlock() throws Exception {
        var upstream = ""
            + "data: {\"delta\":{\"content\":\"ok\"},\"done\":false}\n"
            + "data: {\"delta\":{\"content\":\"<think>secret\"},\"done\":false}\n"
            + "data: {\"delta\":{\"content\":\"</think>42\"},\"done\":false}\n"
            + "data: {\"done\":true}\n"
            + "\n";
        List<Outbound> events = run(upstream);
        // 2 visible ("ok" + "42") + 1 done = 3 outbound events.
        // The chunk that is entirely inside the <think> block
        // yields no data: line; the chunk that contains the
        // closer yields only the visible suffix.
        assertThat(events).hasSize(3);
        assertThat(events.get(0).text()).isEqualTo("ok");
        assertThat(events.get(1).text()).isEqualTo("42");
        assertThat(events.get(2).text()).isEqualTo(StreamingChatService.DONE_SENTINEL);
        assertThat(events.get(2).done()).isTrue();
    }

    @Test
    @DisplayName("multi-chunk CoT block spanning SSE events is dropped end-to-end")
    void multiChunkCoTBlockDroppedEndToEnd() throws Exception {
        var opener = "" + "<think" + ">";
        var closer = "" + "</think" + ">";
        String upstream = ""
            + "data: {\"delta\":{\"content\":\"pre\"},\"done\":false}\n"
            + "data: {\"delta\":{\"content\":\"" + opener + "CoT body\"},\"done\":false}\n"
            + "data: {\"delta\":{\"content\":\"" + closer + "final\"},\"done\":false}\n"
            + "data: {\"done\":true}\n"
            + "\n";
        List<Outbound> events = run(upstream);
        assertThat(events).hasSize(3);
        assertThat(events.get(0).text()).isEqualTo("pre");
        assertThat(events.get(1).text()).isEqualTo("final");
        assertThat(events.get(2).text()).isEqualTo(StreamingChatService.DONE_SENTINEL);
        assertThat(events.get(2).done()).isTrue();
    }

    @Test
    @DisplayName("open CoT block at end-of-stream still emits a data: [DONE] terminal")
    void openCotAtEofEmitsDone() throws Exception {
        var opener = "" + "<think" + ">";
        var upstream = ""
            + "data: {\"delta\":{\"content\":\"ok\"},\"done\":false}\n"
            + "data: {\"delta\":{\"content\":\"" + opener + "hanging\"},\"done\":false}\n"
            + "\n";
        List<Outbound> events = run(upstream);
        // 1 visible ("ok") + 1 done. The opener+body chunk is
        // entirely inside the <think> block, so no data: line.
        assertThat(events).hasSize(2);
        assertThat(events.get(0).text()).isEqualTo("ok");
        assertThat(events.get(1).text()).isEqualTo(StreamingChatService.DONE_SENTINEL);
        assertThat(events.get(1).done()).isTrue();
    }

    @Test
    @DisplayName("done:true midstream stops the loop without further events")
    void doneMidstreamEmitsTerminalOnly() throws Exception {
        var upstream = ""
            + "data: {\"delta\":{\"content\":\"a\"},\"done\":false}\n"
            + "data: {\"done\":true}\n"
            + "data: {\"delta\":{\"content\":\"ignore me\"},\"done\":false}\n";
        List<Outbound> events = run(upstream);
        assertThat(events).hasSize(2);
        assertThat(events.get(0).text()).isEqualTo("a");
        assertThat(events.get(1).text()).isEqualTo(StreamingChatService.DONE_SENTINEL);
        assertThat(events.get(1).done()).isTrue();
    }

    @Test
    @DisplayName("empty input stream emits a single data: [DONE] terminal")
    void emptyInputEmitsOneTerminal() throws Exception {
        List<Outbound> events = run("");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).text()).isEqualTo(StreamingChatService.DONE_SENTINEL);
        assertThat(events.get(0).done()).isTrue();
    }

    @Test
    @DisplayName("heartbeat lines (:ping) and non-data lines are ignored")
    void heartbeatLinesIgnored() throws Exception {
        var upstream = ""
            + ":keep-alive\n"
            + "\n"
            + ":ping\n"
            + "id: 42\n"
            + "event: message\n"
            + "retry: 1000\n"
            + "data: {\"delta\":{\"content\":\"visible\"},\"done\":false}\n"
            + "\n"
            + "data: {\"done\":true}\n";
        List<Outbound> events = run(upstream);
        assertThat(events).hasSize(2);
        assertThat(events.get(0).text()).isEqualTo("visible");
        assertThat(events.get(1).text()).isEqualTo(StreamingChatService.DONE_SENTINEL);
    }

    @Test
    @DisplayName("malformed JSON on a single data: line is skipped, not fatal")
    void malformedJsonSkipped() throws Exception {
        var upstream = ""
            + "data: not-json\n"
            + "\n"
            + "data: {\"delta\":{\"content\":\"ok\"},\"done\":false}\n"
            + "data: {broken\n"
            + "data: {\"done\":true}\n";
        List<Outbound> events = run(upstream);
        assertThat(events).hasSize(2);
        assertThat(events.get(0).text()).isEqualTo("ok");
        assertThat(events.get(1).text()).isEqualTo(StreamingChatService.DONE_SENTINEL);
    }

    @Test
    @DisplayName("null ObjectMapper is rejected (defensive — controller wiring bug catch)")
    void nullMapperRejected() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThatThrownBy(() ->
            StreamingChatService.stream(out, new ByteArrayInputStream(new byte[0]), "alice", null)
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("outbound wire format is exactly data: <text>\\n\\n (Honcho native SSE)")
    void outboundIsRawTextDataLine() throws Exception {
        var upstream = ""
            + "data: {\"delta\":{\"content\":\"Hello, world!\"},\"done\":false}\n"
            + "data: {\"done\":true}\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long chunks = StreamingChatService.stream(
            out,
            new ByteArrayInputStream(upstream.getBytes(StandardCharsets.UTF_8)),
            "alice",
            mapper
        );
        String raw = out.toString(StandardCharsets.UTF_8);
        // No JSON braces anywhere on the wire. SSE event
        // terminator is exactly "\n\n", and each event is a
        // single "data: <text>" line followed by a blank line.
        assertThat(raw).contains("data: Hello, world!\n\n");
        assertThat(raw).contains("data: [DONE]\n\n");
        assertThat(raw).doesNotContain("{");
        assertThat(raw).doesNotContain("}");
        assertThat(chunks).isEqualTo(1L);
    }

    private List<Outbound> run(String sse) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long chunks = StreamingChatService.stream(
            out,
            new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)),
            "alice",
            mapper
        );
        String raw = out.toString(StandardCharsets.UTF_8);
        List<Outbound> result = new ArrayList<>();
        for (String event : raw.split("\n\n")) {
            if (event.isBlank()) continue;
            // Wire format: "data: <payload>" (single line per
            // outbound event). Multi-line data: would be unusual
            // here since we always emit a single line.
            String stripped = event.startsWith("data:") ? event.substring(5).trim() : event;
            boolean done = stripped.equals(StreamingChatService.DONE_SENTINEL);
            result.add(new Outbound(stripped, done));
        }
        long nonTerminal = result.stream().filter(o -> !o.done()).count();
        assertThat(chunks)
            .as("chunk count returned by the service must equal the number of non-terminal outbound events")
            .isEqualTo(nonTerminal);
        return result;
    }

    private record Outbound(String text, boolean done) {}
}
