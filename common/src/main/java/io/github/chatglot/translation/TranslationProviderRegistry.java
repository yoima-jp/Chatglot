package io.github.chatglot.translation;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class TranslationProviderRegistry {
    private final Map<String, TranslationProvider> providers = new ConcurrentHashMap<>();

    public void register(TranslationProvider provider) {
        providers.put(provider.id().toLowerCase(Locale.ROOT), provider);
    }

    public Optional<TranslationProvider> get(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(providers.get(id.toLowerCase(Locale.ROOT)));
    }
}
