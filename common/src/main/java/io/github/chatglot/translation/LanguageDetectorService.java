package io.github.chatglot.translation;

import com.github.pemistahl.lingua.api.IsoCode639_1;
import com.github.pemistahl.lingua.api.Language;
import com.github.pemistahl.lingua.api.LanguageDetector;
import com.github.pemistahl.lingua.api.LanguageDetectorBuilder;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LanguageDetectorService {
    private static final Logger LOGGER = LoggerFactory.getLogger("Chatglot/LanguageDetector");

    private final ExecutorService executor;
    private final CompletableFuture<LanguageDetector> detectorFuture;
    private final AtomicBoolean initializationErrorLogged = new AtomicBoolean(false);

    public LanguageDetectorService() {
        this.executor = Executors.newSingleThreadExecutor(new ChatglotLanguageThreadFactory());
        this.detectorFuture = CompletableFuture.supplyAsync(
            () -> LanguageDetectorBuilder.fromAllSpokenLanguages().build(),
            executor
        );
    }

    public CompletableFuture<Optional<String>> detectLanguageAsync(String text) {
        String cleaned = sanitize(text);
        if (cleaned.isBlank()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        return detectorFuture
            .thenApplyAsync(detector -> detect(detector, cleaned), executor)
            .exceptionally(error -> {
                logInitializationFailure(error);
                return Optional.empty();
            });
    }

    public Optional<String> detectLanguage(String text) {
        String cleaned = sanitize(text);
        if (cleaned.isBlank() || !detectorFuture.isDone()) {
            return Optional.empty();
        }

        LanguageDetector detector;
        try {
            detector = detectorFuture.getNow(null);
        } catch (Exception error) {
            logInitializationFailure(error);
            return Optional.empty();
        }
        if (detector == null) {
            return Optional.empty();
        }

        return detect(detector, cleaned);
    }

    private static Optional<String> detect(LanguageDetector detector, String cleaned) {
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

    private static String sanitize(String text) {
        if (text == null) {
            return "";
        }

        String cleaned = text.replaceAll("§.", "").trim();
        if (cleaned.length() < 3) {
            return "";
        }
        return cleaned;
    }

    private void logInitializationFailure(Throwable error) {
        if (initializationErrorLogged.compareAndSet(false, true)) {
            LOGGER.warn("Language detector initialization failed; automatic language hint will be disabled.", error);
        }
    }

    private static final class ChatglotLanguageThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "chatglot-language-detector");
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((t, e) -> LOGGER.error("Uncaught language detector error", e));
            return thread;
        }
    }
}
