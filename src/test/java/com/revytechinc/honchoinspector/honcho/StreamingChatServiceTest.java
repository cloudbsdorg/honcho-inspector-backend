package com.revytechinc.honchoinspector.honcho;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StreamingChatServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("textOnly emits every chunk verbatim and closes with done:true")
    void textOnlyStreamsEverything() throws Exception {
        var upstream = """
            data: {"delta":{"content":"Hello"},"done":false}

            data: {"delta":{"content":" world"},"done":false}

            data: {"delta":{"content":"!"},"done":false}

            data: {"done":true}

            """;
        List<Envelope> envelopes = run(upstream);
        assertThat(envelopes).hasSize(4);
        assertThat(envelopes.get(0).text()).isEqualTo("Hello");
        assertThat(envelopes.get(0).done()).isFalse();
        assertThat(envelopes.get(1).text()).isEqualTo(" world");
        assertThat(envelopes.get(2).text()).isEqualTo("!");
        assertThat(envelopes.get(3).text()).isEmpty();
        assertThat(envelopes.get(3).done()).isTrue();
        assertThat(envelopes.get(3).truncatedChars()).isNull();
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
        List<Envelope> envelopes = run(upstream);
        assertThat(envelopes).hasSize(4);
        assertThat(envelopes.get(0).text()).isEqualTo("ok");
        assertThat(envelopes.get(1).text())
            .as("chunk after opener — visible part is empty, the <think>secret text is dropped")
            .isEmpty();
        assertThat(envelopes.get(2).text())
            .as("chunk that contains the </think> opener — visible starts after the close")
            .isEqualTo("42");
        assertThat(envelopes.get(3).done()).isTrue();
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
        List<Envelope> envelopes = run(upstream);
        assertThat(envelopes).hasSize(4);
        assertThat(envelopes.get(0).text()).isEqualTo("pre");
        assertThat(envelopes.get(1).text())
            .as("chunk after opener — visible part is empty, the CoT body is dropped")
            .isEmpty();
        assertThat(envelopes.get(2).text())
            .as("chunk that contains the closer — visible starts after the close")
            .isEqualTo("final");
        assertThat(envelopes.get(3).done()).isTrue();
    }

    @Test
    @DisplayName("open CoT block at end-of-stream emits done:true + truncatedChars")
    void openCotAtEofEmitsTruncatedChars() throws Exception {
        var opener = "" + "<think" + ">";
        var upstream = ""
            + "data: {\"delta\":{\"content\":\"ok\"},\"done\":false}\n"
            + "data: {\"delta\":{\"content\":\"" + opener + "hanging\"},\"done\":false}\n"
            + "\n";
        List<Envelope> envelopes = run(upstream);
        assertThat(envelopes).hasSize(3);
        assertThat(envelopes.get(0).text()).isEqualTo("ok");
        assertThat(envelopes.get(1).text()).isEmpty();
        assertThat(envelopes.get(2).done()).isTrue();
        assertThat(envelopes.get(2).truncatedChars())
            .as("7 chars of unterminated CoT (the 'hanging' text after the opener)")
            .isEqualTo(7);
    }

    @Test
    @DisplayName("done:true midstream stops the loop without further envelopes")
    void doneMidstreamEmitsTerminalOnly() throws Exception {
        var upstream = ""
            + "data: {\"delta\":{\"content\":\"a\"},\"done\":false}\n"
            + "data: {\"done\":true}\n"
            + "data: {\"delta\":{\"content\":\"ignore me\"},\"done\":false}\n";
        List<Envelope> envelopes = run(upstream);
        assertThat(envelopes).hasSize(2);
        assertThat(envelopes.get(0).text()).isEqualTo("a");
        assertThat(envelopes.get(1).text()).isEmpty();
        assertThat(envelopes.get(1).done()).isTrue();
    }

    @Test
    @DisplayName("empty input stream emits a single terminal envelope")
    void emptyInputEmitsOneTerminal() throws Exception {
        List<Envelope> envelopes = run("");
        assertThat(envelopes).hasSize(1);
        assertThat(envelopes.get(0).done()).isTrue();
        assertThat(envelopes.get(0).text()).isEmpty();
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
        List<Envelope> envelopes = run(upstream);
        assertThat(envelopes).hasSize(2);
        assertThat(envelopes.get(0).text()).isEqualTo("visible");
        assertThat(envelopes.get(1).done()).isTrue();
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
        List<Envelope> envelopes = run(upstream);
        assertThat(envelopes).hasSize(2);
        assertThat(envelopes.get(0).text()).isEqualTo("ok");
        assertThat(envelopes.get(1).done()).isTrue();
    }

    @Test
    @DisplayName("meta.peerId is set on every emitted envelope")
    void metaPeerIdIsAlwaysSet() throws Exception {
        var upstream = ""
            + "data: {\"delta\":{\"content\":\"a\"},\"done\":false}\n"
            + "data: {\"done\":true}\n";
        List<Envelope> envelopes = run(upstream);
        assertThat(envelopes).hasSize(2);
        for (Envelope e : envelopes) {
            assertThat(e.peerId())
                .as("every envelope should carry the peerId we passed in")
                .isEqualTo("alice");
        }
    }

    @Test
    @DisplayName("null ObjectMapper is rejected (defensive — controller wiring bug catch)")
    void nullMapperRejected() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThatThrownBy(() ->
            StreamingChatService.stream(out, new ByteArrayInputStream(new byte[0]), "alice", null)
        ).isInstanceOf(IllegalArgumentException.class);
    }

    private List<Envelope> run(String sse) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long chunks = StreamingChatService.stream(
            out,
            new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)),
            "alice",
            mapper
        );
        String raw = out.toString(StandardCharsets.UTF_8);
        List<Envelope> result = new ArrayList<>();
        for (String line : raw.split("\n\n")) {
            if (line.isBlank()) continue;
            String stripped = line.startsWith("data:") ? line.substring(5).trim() : line;
            JsonNode envelope = mapper.readTree(stripped);
            result.add(new Envelope(
                envelope.path("data").path("text").asText(""),
                envelope.path("meta").path("done").asBoolean(false),
                envelope.path("meta").path("truncatedChars").isMissingNode() || envelope.path("meta").path("truncatedChars").isNull()
                    ? null
                    : envelope.path("meta").path("truncatedChars").asInt(),
                envelope.path("meta").path("peerId").asText(null)
            ));
        }
        assertThat(chunks)
            .as("chunk count returned by the service must equal the number of non-terminal envelopes")
            .isEqualTo(result.stream().filter(e -> !e.done()).count());
        return result;
    }

    private record Envelope(String text, boolean done, Integer truncatedChars, String peerId) {}
}
