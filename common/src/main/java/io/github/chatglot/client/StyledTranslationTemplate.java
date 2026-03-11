package io.github.chatglot.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

final class StyledTranslationTemplate {
    private static final String TOKEN_PREFIX = "[[CGT_";
    private static final String TOKEN_SUFFIX = "]]";
    private static final String TOKEN_CLOSE_PREFIX = "[[/CGT_";
    private static final Style DEFAULT_TEXT_STYLE = Style.EMPTY.withColor(Formatting.WHITE);
    private static final Pattern LEADING_SPEAKER_PATTERN = Pattern.compile("^(<[^<>\\r\\n]+>\\s*)");

    private final String markedText;
    private final List<Segment> segments;
    private final Style fallbackStyle;
    private final String preservedPrefix;

    private StyledTranslationTemplate(String markedText, List<Segment> segments, Style fallbackStyle, String preservedPrefix) {
        this.markedText = markedText;
        this.segments = segments;
        this.fallbackStyle = mergeWithDefaultStyle(fallbackStyle);
        this.preservedPrefix = preservedPrefix == null ? "" : preservedPrefix;
    }

    public static StyledTranslationTemplate create(Text originalMessage, String fallbackPlainText, boolean preserveLeadingSpeakerPrefix) {
        String plainText = fallbackPlainText;
        if ((plainText == null || plainText.isEmpty()) && originalMessage != null) {
            plainText = originalMessage.getString();
        }
        PrefixMatch prefixMatch = preserveLeadingSpeakerPrefix ? extractLeadingSpeakerPrefix(plainText) : new PrefixMatch("", 0);

        if (originalMessage == null) {
            String body = plainText == null ? "" : plainText.substring(prefixMatch.prefixLength());
            return new StyledTranslationTemplate(body, List.of(), Style.EMPTY, prefixMatch.prefix());
        }

        List<Segment> segments = new ArrayList<>();
        StringBuilder marked = new StringBuilder();
        int[] remainingPrefixLength = {prefixMatch.prefixLength()};
        originalMessage.visit((style, string) -> {
            if (string == null || string.isEmpty()) {
                return Optional.empty();
            }

            String remainder = string;
            if (remainingPrefixLength[0] > 0) {
                int consumed = Math.min(remainingPrefixLength[0], remainder.length());
                remainder = remainder.substring(consumed);
                remainingPrefixLength[0] -= consumed;
            }
            if (remainder.isEmpty()) {
                return Optional.empty();
            }

            int index = segments.size();
            Segment segment = new Segment(
                TOKEN_PREFIX + index + TOKEN_SUFFIX,
                TOKEN_CLOSE_PREFIX + index + TOKEN_SUFFIX,
                mergeWithDefaultStyle(style)
            );
            segments.add(segment);
            marked.append(segment.openToken()).append(remainder).append(segment.closeToken());
            return Optional.empty();
        }, Style.EMPTY);

        if (segments.isEmpty()) {
            String body = plainText == null ? originalMessage.getString() : plainText.substring(prefixMatch.prefixLength());
            return new StyledTranslationTemplate(body, List.of(), originalMessage.getStyle(), prefixMatch.prefix());
        }

        return new StyledTranslationTemplate(marked.toString(), List.copyOf(segments), originalMessage.getStyle(), prefixMatch.prefix());
    }

    public String markedText() {
        return markedText;
    }

    public Text apply(String translatedText) {
        String value = translatedText == null ? "" : translatedText;
        if (segments.isEmpty()) {
            return Text.literal(preservedPrefix + value).setStyle(fallbackStyle);
        }

        MutableText rebuilt = Text.empty();
        appendLiteral(rebuilt, preservedPrefix, fallbackStyle);
        int cursor = 0;
        boolean appliedMarker = false;
        while (cursor < value.length()) {
            SegmentMatch nextSegment = findNextSegment(value, cursor);
            if (nextSegment == null) {
                appendLiteral(rebuilt, value.substring(cursor), fallbackStyle);
                return appliedMarker ? rebuilt : Text.literal(preservedPrefix + value).setStyle(fallbackStyle);
            }

            if (nextSegment.startIndex() > cursor) {
                appendLiteral(rebuilt, value.substring(cursor, nextSegment.startIndex()), fallbackStyle);
            }

            int contentStart = nextSegment.startIndex() + nextSegment.segment().openToken().length();
            int contentEnd = value.indexOf(nextSegment.segment().closeToken(), contentStart);
            if (contentEnd < 0) {
                return Text.literal(preservedPrefix + value).setStyle(fallbackStyle);
            }

            appendLiteral(rebuilt, value.substring(contentStart, contentEnd), nextSegment.segment().style());
            cursor = contentEnd + nextSegment.segment().closeToken().length();
            appliedMarker = true;
        }

        return appliedMarker ? rebuilt : Text.literal(preservedPrefix + value).setStyle(fallbackStyle);
    }

    private static PrefixMatch extractLeadingSpeakerPrefix(String plainText) {
        if (plainText == null || plainText.isEmpty()) {
            return new PrefixMatch("", 0);
        }

        Matcher matcher = LEADING_SPEAKER_PATTERN.matcher(plainText);
        if (!matcher.find()) {
            return new PrefixMatch("", 0);
        }
        String prefix = matcher.group(1);
        return new PrefixMatch(prefix, prefix.length());
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
        target.append(Text.literal(text).setStyle(mergeWithDefaultStyle(style)));
    }

    private static Style mergeWithDefaultStyle(Style style) {
        if (style == null) {
            return DEFAULT_TEXT_STYLE;
        }
        return style.withParent(DEFAULT_TEXT_STYLE);
    }

    private record Segment(String openToken, String closeToken, Style style) {
        private Segment {
            Objects.requireNonNull(openToken, "openToken");
            Objects.requireNonNull(closeToken, "closeToken");
            style = mergeWithDefaultStyle(style);
        }
    }

    private record SegmentMatch(Segment segment, int startIndex) {
    }

    private record PrefixMatch(String prefix, int prefixLength) {
    }
}
