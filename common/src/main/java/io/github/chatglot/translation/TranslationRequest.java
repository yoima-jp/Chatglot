package io.github.chatglot.translation;

public record TranslationRequest(String text, String targetLanguage, String sourceLanguageHint, boolean automatic) {
}
