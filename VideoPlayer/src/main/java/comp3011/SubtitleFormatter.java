package comp3011;

import java.util.List;

/**
 * Wraps subtitle text for display: at most 2 lines, at most 42 characters
 * per line, preferring a break after punctuation (Netflix/DCMP style rules,
 * simplified). Longer text is truncated with an ellipsis rather than
 * spilling onto a third line.
 */
final class SubtitleFormatter {

    static final int MAX_CHARS = 42;
    private static final String PUNCTUATION = ".,!?;:)]";

    private SubtitleFormatter() {
    }

    static List<String> wrap(String text) {
        String t = text.trim().replaceAll("\\s+", " ");

        if (t.length() > MAX_CHARS * 2) {
            t = t.substring(0, MAX_CHARS * 2 - 1) + "\u2026";
        }
        if (t.length() <= MAX_CHARS) {
            return List.of(t);
        }

        int lo = t.length() - MAX_CHARS;
        int hi = MAX_CHARS;

        int split = -1;
        for (int i = hi; i >= lo; i--) {
            if (i >= t.length() || i < 1) {
                continue;
            }
            if (PUNCTUATION.indexOf(t.charAt(i - 1)) >= 0 && t.charAt(i) == ' ') {
                split = i + 1;
                break;
            }
        }
        if (split < 0) {
            for (int i = hi; i >= lo; i--) {
                if (i < t.length() && t.charAt(i) == ' ') {
                    split = i + 1;
                    break;
                }
            }
        }
        if (split < 0) {
            split = hi;
        }
        split = Math.max(1, Math.min(split, MAX_CHARS));

        String first = t.substring(0, split).trim();
        String second = t.substring(split).trim();
        return second.isEmpty() ? List.of(first) : List.of(first, second);
    }
}