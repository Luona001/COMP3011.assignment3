package comp3011;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SubtitleFormatterTest {

    @Test
    void shortTextStaysOnOneLine() {
        assertEquals(List.of("Hello there!"),
                SubtitleFormatter.wrap("  Hello   there!  "));
    }

    @Test
    void longTextIsCappedAtTwoLinesOf42Chars() {
        String text = "This is a fairly long sentence that will definitely need "
                + "to be wrapped across more than one line of subtitle text.";
        List<String> lines = SubtitleFormatter.wrap(text);

        assertTrue(lines.size() <= 2, "at most two lines");
        for (String line : lines) {
            assertTrue(line.length() <= SubtitleFormatter.MAX_CHARS,
                    "line too long: '" + line + "'");
        }
    }

    @Test
    void prefersBreakingAfterPunctuation() {
        String text = "Wait, before you go, there is one more thing I need to say to you.";
        List<String> lines = SubtitleFormatter.wrap(text);

        assertEquals(2, lines.size());
        assertTrue(lines.get(0).endsWith(",") || lines.get(0).endsWith("."),
                "line 1 should end at punctuation: '" + lines.get(0) + "'");
    }

    @Test
    void extremelyLongTextIsTruncatedWithEllipsis() {
        String text = "word ".repeat(100);
        List<String> lines = SubtitleFormatter.wrap(text);

        assertTrue(lines.size() <= 2);
        assertTrue(lines.get(lines.size() - 1).endsWith("\u2026"));
    }
}