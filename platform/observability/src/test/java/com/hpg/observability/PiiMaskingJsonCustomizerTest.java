package com.hpg.observability;

import com.hpg.observability.logging.PiiMaskingJsonCustomizer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.json.JsonWriter;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PiiMaskingJsonCustomizerTest {

    record Line(String message, Map<String, String> mdc, int pid) {
    }

    @Test
    void masksEveryStringMemberOfAJsonLogLine() {
        JsonWriter<Line> writer = JsonWriter.of(members -> {
            members.add("message", Line::message);
            members.add("mdc", Line::mdc);
            members.add("pid", Line::pid);
            new PiiMaskingJsonCustomizer().customize((JsonWriter.Members) members);
        });
        String json = writer.writeToString(new Line("Email to asha.rao@example.com failed", Map.of("note",
                "Authorization: Bearer abcdefghijklmnop"), 42));
        assertThat(json).doesNotContain("asha.rao@").doesNotContain("abcdefghijklmnop")
                .contains("a******o@example.com").contains("Bearer ****").contains("\"pid\":42");
    }
}
