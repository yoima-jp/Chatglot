package io.github.chatglot.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

final class StyledTranslationTemplate {
    private static final String TOKEN_PREFIX = "[[CGT_";
    private static final String TOKEN_SUFFIX = "]]";
    private static final String TOKEN_CLOSE_PREFIX = "[[/CGT_";

    private final String markedText;
    private final List<Segment> segments;
    private final Style fallbackStyle;

    private StyledTranslationTemplate(String markedText, List<Segment> segments, Style fallbackStyle) {
        this.markedText = markedText;
        this.segments = segments;
        this.fallbackStyle = fallbackStyle == null ? Style.EMPTY : fallbackStyle;
    }

    public static StyledTranslationTemplate create(Text originalMessage, String fallbackPlainText) {
        if (originalMessage == null) {
            return new StyledTranslationTemplate(fallbackPlainText == null ? "" : fallbackPlainText, List.of(), Style.EMPTY);
        }

        List<Segment> segments = new ArrayList<>();
        StringBuilder marked = new StringBuilder();
        originalMessage.visit((style, string) -> {
            if (string == null || string.isEmpty()) {
                return Optional.empty();
            }

            int index = segments.size();
            Segment segment = new Segment(
                TOKEN_PREFIX + index + TOKEN_SUFFIX,
                TOKEN_CLOSE_PREFIX + index + TOKEN_SUFFIX,
                style == null ? Style.EMPTY : style
            );
            segments.add(segment);
            marked.append(segment.openToken()).append(string).append(segment.closeToken());
            return Optional.empty();
        }, Style.EMPTY);

        if (segments.isEmpty()) {
            String plainText = fallbackPlainText == null ? originalMessage.getString() : fallbackPlainText;
            return new StyledTranslationTemplate(plainText, List.of(), originalMessage.getStyle());
        }

        return new StyledTranslationTemplate(marked.toString(), List.copyOf(segments), originalMessage.getStyle());
    }

    public String markedText() {
        return markedText;
    }

    public Text apply(String translatedText) {
        String value = translatedText == null ? "" : translatedText;
        if (segments.isEmpty()) {
            return Text.literal(value).setStyle(fallbackStyle);
        }

        MutableText rebuilt = Text.empty();
        int cursor = 0;
        boolean appliedMarker = false;
        while (cursor < value.length()) {
            SegmentMatch nextSegment = findNextSegment(value, cursor);
            if (nextSegment == null) {
                appendLiteral(rebuilt, value.substring(cursor), fallbackStyle);
                return appliedMarker ? rebuilt : Text.literal(value).setStyle(fallbackStyle);
            }

            if (nextSegment.startIndex() > cursor) {
                appendLiteral(rebuilt, value.substring(cursor, nextSegment.startIndex()), fallbackStyle);
            }

            int contentStart = nextSegment.startIndex() + nextSegment.segment().openToken().length();
            int contentEnd = value.indexOf(nextSegment.segment().closeToken(), contentStart);
            if (contentEnd < 0) {
                return Text.literal(value).setStyle(fallbackStyle);
            }

            appendLiteral(rebuilt, value.substring(contentStart, contentEnd), nextSegment.segment().style());
            cursor = contentEnd + nextSegment.segment().closeToken().length();
            appliedMarker = true;
        }

        return appliedMarker ? rebuilt : Text.literal(value).setStyle(fallbackStyle);
    }

    private SegmentMatch findNextSegment(String value, int cursor) {
        SegmentMatch earliest = null;
        for (Segment segment : segments) {
            int startIndex = value.indexOf(segment.openToken(), cursor);
            if (startIndex < 0) {
                continue;
            }

            if (earliest == null || startIndex < earliest.startIndex()) {
                earliest = new SegmentMatch(segment, startIndex);
            }
        }
        return earliest;
    }

    private static void appendLiteral(MutableText target, String text, Style style) {
        if (text == null || text.isEmpty()) {
            return;
        }
        target.append(Text.literal(text).setStyle(style == null ? Style.EMPTY : style));
    }

    private record Segment(String openToken, String closeToken, Style style) {
        private Segment {
            Objects.requireNonNull(openToken, "openToken");
            Objects.requireNonNull(closeToken, "closeToken");
            style = style == null ? Style.EMPTY : style;
        }
    }

    private record SegmentMatch(Segment segment, int startIndex) {
    }
}
