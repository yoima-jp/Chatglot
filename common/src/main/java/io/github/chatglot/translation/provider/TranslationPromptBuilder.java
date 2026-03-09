package io.github.chatglot.translation.provider;

import io.github.chatglot.translation.TranslationRequest;

final class TranslationPromptBuilder {
    private TranslationPromptBuilder() {
    }

    static String buildStandardPrompt(TranslationRequest request) {
        StringBuilder builder = new StringBuilder();
        builder.append("Translate the following Minecraft chat message into ")
            .append(request.targetLanguage())
            .append(". ")
            .append("Preserve player names, commands, URLs, placeholders, and formatting markers when possible. ")
            .append("Return only translated text without explanations.");

        if (request.sourceLanguageHint() != null && !request.sourceLanguageHint().isBlank()) {
            builder.append(" Source language hint: ").append(request.sourceLanguageHint()).append('.');
        }

        builder.append("\n\nMessage:\n").append(request.text());
        return builder.toString();
    }

    static String abbreviate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }
}
