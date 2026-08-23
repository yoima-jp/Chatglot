package io.github.chatglot.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class StyledTranslationTemplate {
    private static final Logger LOGGER = LoggerFactory.getLogger("Chatglot/StyledTranslation");
    private static final String TOKEN_PREFIX = "[[CGT_";
    private static final String TOKEN_SUFFIX = "]]";
    private static final String TOKEN_CLOSE_PREFIX = "[[/CGT_";
    private static final Style DEFAULT_TEXT_STYLE = Style.EMPTY.withColor(ChatFormatting.WHITE);
    private static final Pattern LEADING_SPEAKER_PATTERN = Pattern.compile("^(<[^<>\\r\\n]+>\\s*)");
    private static final Pattern RELAXED_TOKEN_PATTERN = Pattern.compile(
        "\\[\\[\\s*(/?)\\s*C\\s*G\\s*T\\s*_\\s*(\\d+)\\s*\\]\\]",
        Pattern.CASE_INSENSITIVE
    );

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

    public static StyledTranslationTemplate create(Component originalMessage, String fallbackPlainText, boolean preserveLeadingSpeakerPrefix) {
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
                mergeWithDefaultStyle(style),
                remainder
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

    public Component apply(String translatedText) {
        String value = normalizeMarkerTokens(translatedText == null ? "" : translatedText);
        if (segments.isEmpty()) {
            return Component.literal(preservedPrefix + value).setStyle(fallbackStyle);
        }

        MutableComponent rebuilt = Component.empty();
        appendLiteral(rebuilt, preservedPrefix, fallbackStyle);
        int cursor = 0;
        boolean appliedMarker = false;
        Segment previousSegment = null;
        while (cursor < value.length()) {
            SegmentMatch nextSegment = findNextSegment(value, cursor);
            if (nextSegment == null) {
                appendLiteral(rebuilt, value.substring(cursor), fallbackStyle);
                if (!appliedMarker) {
                    LOGGER.warn(
                        "Could not restore translation markers: segments={}, firstMarkerIndex={}, translatedPrefixCodePoints={}",
                        segments.size(),
                        value.indexOf(segments.getFirst().openToken()),
                        describeCodePoints(value, 20)
                    );
                }
                return appliedMarker ? rebuilt : Component.literal(preservedPrefix + value).setStyle(fallbackStyle);
            }

            if (nextSegment.startIndex() > cursor) {
                String gap = value.substring(cursor, nextSegment.startIndex());
                if (!isInsertedWhitespaceBetweenSymbols(gap, previousSegment, nextSegment.segment())) {
                    appendLiteral(rebuilt, gap, fallbackStyle);
                }
            }

            int contentStart = nextSegment.startIndex() + nextSegment.segment().openToken().length();
            int contentEnd = value.indexOf(nextSegment.segment().closeToken(), contentStart);
            if (contentEnd < 0) {
                LOGGER.warn(
                    "Could not restore closing translation marker: marker={}, translatedPrefixCodePoints={}",
                    nextSegment.segment().closeToken(),
                    describeCodePoints(value, 20)
                );
                return Component.literal(preservedPrefix + value).setStyle(fallbackStyle);
            }

            String translatedSegment = value.substring(contentStart, contentEnd);
            translatedSegment = removeInsertedBoundaryWhitespace(translatedSegment, nextSegment.segment());
            appendLiteral(rebuilt, translatedSegment, nextSegment.segment().style());
            cursor = contentEnd + nextSegment.segment().closeToken().length();
            appliedMarker = true;
            previousSegment = nextSegment.segment();
        }

        return appliedMarker ? rebuilt : Component.literal(preservedPrefix + value).setStyle(fallbackStyle);
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

    private static void appendLiteral(MutableComponent target, String text, Style style) {
        if (text == null || text.isEmpty()) {
            return;
        }
        target.append(Component.literal(text).setStyle(mergeWithDefaultStyle(style)));
    }

    private static String normalizeMarkerTokens(String value) {
        // Some translation backends insert whitespace inside marker names (for example,
        // "[[/CG T_22]]"). Canonicalize only complete Chatglot-shaped tokens so ordinary
        // translated whitespace and message text remain untouched.
        return RELAXED_TOKEN_PATTERN.matcher(value).replaceAll(result ->
            "[[" + (result.group(1).isEmpty() ? "" : "/") + "CGT_" + result.group(2) + "]]"
        );
    }

    private static boolean isInsertedWhitespaceBetweenSymbols(String gap, Segment previous, Segment next) {
        if (previous == null || next == null || gap == null || !gap.isBlank()) {
            return false;
        }
        // Translators sometimes add HTML element separators around decorative runs. Remove
        // those only when both source segments are entirely non-linguistic; spaces inserted
        // between translated words must remain available to the target language.
        return isNonLinguistic(previous.originalText()) && isNonLinguistic(next.originalText());
    }

    private static String removeInsertedBoundaryWhitespace(String translatedText, Segment segment) {
        String originalText = segment.originalText();
        if (!isNonLinguistic(originalText) || translatedText == null || translatedText.isEmpty()) {
            return translatedText;
        }

        // GAS may add spaces inside protected spans while translating decorative symbols.
        // Remove only boundary whitespace that did not exist in the source segment, so
        // meaningful whitespace in ordinary text and deliberately spaced symbols survives.
        String normalized = translatedText;
        if (!Character.isWhitespace(originalText.codePointAt(0))) {
            normalized = normalized.stripLeading();
        }
        if (!Character.isWhitespace(originalText.codePointBefore(originalText.length()))) {
            normalized = normalized.stripTrailing();
        }
        return normalized;
    }

    private static boolean isNonLinguistic(String value) {
        return value != null && !value.isEmpty() && value.codePoints().noneMatch(Character::isLetterOrDigit);
    }

    private static String describeCodePoints(String value, int limit) {
        if (value == null || value.isEmpty()) {
            return "<empty>";
        }
        return value.codePoints()
            .limit(limit)
            .mapToObj(codePoint -> String.format("U+%04X", codePoint))
            .collect(java.util.stream.Collectors.joining(" "));
    }

    private static Style mergeWithDefaultStyle(Style style) {
        if (style == null) {
            return DEFAULT_TEXT_STYLE;
        }
        return style.applyTo(DEFAULT_TEXT_STYLE);
    }

    private record Segment(String openToken, String closeToken, Style style, String originalText) {
        private Segment {
            Objects.requireNonNull(openToken, "openToken");
            Objects.requireNonNull(closeToken, "closeToken");
            Objects.requireNonNull(originalText, "originalText");
            style = mergeWithDefaultStyle(style);
        }
    }

    private record SegmentMatch(Segment segment, int startIndex) {
    }

    private record PrefixMatch(String prefix, int prefixLength) {
    }
}
