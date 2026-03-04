package io.github.chatglot.translation;

import java.util.Set;
import java.util.Locale;
import net.minecraft.client.MinecraftClient;

public final class LanguageUtil {
    public static final String MINECRAFT_DEFAULT_TARGET = "MINECRAFT_DEFAULT";

    private static final Set<String> SUPPORTED_REGIONAL_CODES = Set.of(
        "EN-US",
        "EN-GB",
        "PT-BR",
        "PT-PT",
        "ZH-HANS",
        "ZH-HANT"
    );

    private LanguageUtil() {
    }

    public static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim().replace('_', '-').toUpperCase(Locale.ROOT);
    }

    public static String normalizeTargetLanguage(String value) {
        String normalized = normalize(value);
        if (normalized.isEmpty()) {
            return "";
        }

        if (normalized.startsWith("ZH-")) {
            return switch (normalized) {
                case "ZH-CN", "ZH-SG", "ZH-HANS" -> "ZH-HANS";
                case "ZH-TW", "ZH-HK", "ZH-MO", "ZH-HANT" -> "ZH-HANT";
                default -> "ZH";
            };
        }

        if (SUPPORTED_REGIONAL_CODES.contains(normalized)) {
            return normalized;
        }

        int delimiterIndex = normalized.indexOf('-');
        if (delimiterIndex > 0) {
            return normalized.substring(0, delimiterIndex);
        }

        return normalized;
    }

    public static boolean isMinecraftDefaultTarget(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.trim().replace('-', '_').toUpperCase(Locale.ROOT);
        return normalized.equals(MINECRAFT_DEFAULT_TARGET) || normalized.equals("DEFAULT");
    }

    public static String resolveConfiguredTargetLanguage(String configuredTargetLanguage) {
        if (isMinecraftDefaultTarget(configuredTargetLanguage)) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && client.getLanguageManager() != null) {
                String currentLanguageCode = client.getLanguageManager().getLanguage();
                String resolvedCurrent = normalizeTargetLanguage(currentLanguageCode);
                if (!resolvedCurrent.isBlank()) {
                    return resolvedCurrent;
                }
            }
            return "EN";
        }

        String normalized = normalizeTargetLanguage(configuredTargetLanguage);
        if (!normalized.isBlank()) {
            return normalized;
        }
        return "EN";
    }

    public static boolean isSameLanguage(String left, String right) {
        String leftNorm = normalizeTargetLanguage(left);
        String rightNorm = normalizeTargetLanguage(right);
        if (leftNorm.isEmpty() || rightNorm.isEmpty()) {
            return false;
        }

        String leftBase = leftNorm.split("-", 2)[0];
        String rightBase = rightNorm.split("-", 2)[0];
        return leftBase.equals(rightBase);
    }
}
