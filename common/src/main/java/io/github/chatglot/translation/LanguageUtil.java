package io.github.chatglot.translation;

import java.util.Locale;

public final class LanguageUtil {
    private LanguageUtil() {
    }

    public static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim().replace('_', '-').toUpperCase(Locale.ROOT);
    }

    public static boolean isSameLanguage(String left, String right) {
        String leftNorm = normalize(left);
        String rightNorm = normalize(right);
        if (leftNorm.isEmpty() || rightNorm.isEmpty()) {
            return false;
        }

        String leftBase = leftNorm.split("-", 2)[0];
        String rightBase = rightNorm.split("-", 2)[0];
        return leftBase.equals(rightBase);
    }
}
