package io.github.chatglot.translation;

import com.github.pemistahl.lingua.api.IsoCode639_1;
import com.github.pemistahl.lingua.api.Language;
import com.github.pemistahl.lingua.api.LanguageDetector;
import com.github.pemistahl.lingua.api.LanguageDetectorBuilder;
import java.util.Optional;

public final class LanguageDetectorService {
    private final LanguageDetector detector;

    public LanguageDetectorService() {
        this.detector = LanguageDetectorBuilder.fromAllSpokenLanguages().build();
    }

    public Optional<String> detectLanguage(String text) {
        if (text == null) {
            return Optional.empty();
        }

        String cleaned = text.replaceAll("§.", "").trim();
        if (cleaned.length() < 3) {
            return Optional.empty();
        }

        Language language = detector.detectLanguageOf(cleaned);
        if (language == null || language == Language.UNKNOWN) {
            return Optional.empty();
        }

        IsoCode639_1 iso = language.getIsoCode639_1();
        if (iso == IsoCode639_1.NONE) {
            return Optional.empty();
        }

        return Optional.of(iso.toString());
    }
}
