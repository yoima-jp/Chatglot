package io.github.chatglot.client;

import io.github.chatglot.ChatglotConstants;
import io.github.chatglot.config.ChatglotConfig;
import io.github.chatglot.translation.TranslationException;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/** Builds safe, localized player-facing summaries while keeping provider detail in a hover. */
public final class TranslationErrorMessages {
    private static final int MAX_DETAIL_LENGTH = 200;
    private static final Set<String> PROVIDER_IDS = Set.of(
        "default", "deepl", "google", "gas", "codex", "openai", "custom_llm", "gemini",
        "anthropic", "azure", "translategemma_local"
    );
    private static final Set<TranslationException.Kind> SETTINGS_KINDS = Set.of(
        TranslationException.Kind.AUTH,
        TranslationException.Kind.CONFIG_MISSING,
        TranslationException.Kind.QUOTA,
        TranslationException.Kind.RATE_LIMITED
    );

    private TranslationErrorMessages() {
    }

    public static Component chat(Throwable error) {
        return chat(error, null);
    }

    public static Component chat(Throwable error, String configuredProviderId) {
        TranslationException failure = findTranslationException(error);
        boolean showPauseNotice = failure != null && failure.claimBreakerNotice();
        return chat(error, configuredProviderId, showPauseNotice);
    }

    public static Component chat(Throwable error, String configuredProviderId, boolean showPauseNotice) {
        TranslationException failure = findTranslationException(error);
        TranslationException.Kind kind = failure == null ? TranslationException.Kind.UNKNOWN : failure.kind();
        String providerId = resolveProviderId(failure, configuredProviderId);
        MutableComponent message = Component.literal(ChatglotConstants.INTERNAL_PREFIX + " ")
            .append(reason(kind, providerId));
        String hintKey = hintKey(kind, providerId, configuredProviderId);
        if (hintKey != null) {
            message.append(Component.literal(" ")).append(Component.translatable(hintKey));
        }
        if (SETTINGS_KINDS.contains(kind)) {
            message.append(Component.literal(" ")).append(
                Component.translatable("chatglot.error.open_settings")
                    .withStyle(style -> style
                        .withColor(ChatFormatting.AQUA)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent.RunCommand("/chatglot config"))
                    )
            );
        }
        if (showPauseNotice) {
            message.append(Component.literal(" ")).append(Component.translatable("chatglot.error.auto_paused"));
        }

        // Provider messages and raw response bodies can contain chat text or credentials;
        // keep them out of the message and expose only the provider's masked detail here.
        Component hover = Component.translatable(
            "chatglot.error.hover",
            providerName(providerId),
            failure != null && failure.httpStatus().isPresent()
                ? Integer.toString(failure.httpStatus().getAsInt())
                : "-",
            Component.translatable("chatglot.error.kind." + kind.name().toLowerCase(Locale.ROOT)),
            limitedDetail(failure == null ? null : failure.detail())
        );
        return message.withStyle(Style.EMPTY.withColor(ChatFormatting.RED).withHoverEvent(new HoverEvent.ShowText(hover)));
    }

    public static Component shortReason(Throwable error) {
        return shortReason(error, null);
    }

    public static Component shortReason(Throwable error, String configuredProviderId) {
        TranslationException failure = findTranslationException(error);
        TranslationException.Kind kind = failure == null ? TranslationException.Kind.UNKNOWN : failure.kind();
        String key = "chatglot.error." + kind.name().toLowerCase(Locale.ROOT) + ".short";
        MutableComponent message = kind == TranslationException.Kind.DISABLED
            ? Component.translatable(key)
            : Component.translatable(key, providerName(resolveProviderId(failure, configuredProviderId)));
        if (failure != null && failure.breakerTriggered()) {
            message.append(Component.literal(" ")).append(Component.translatable("chatglot.error.auto_paused"));
        }
        return message;
    }

    public static Component diagnosticDetails(Throwable error) {
        TranslationException failure = findTranslationException(error);
        TranslationException.Kind kind = failure == null ? TranslationException.Kind.UNKNOWN : failure.kind();
        String status = failure != null && failure.httpStatus().isPresent()
            ? Integer.toString(failure.httpStatus().getAsInt())
            : "-";
        return Component.translatable(
            "chatglot.command.diagnose.failure.details",
            Component.translatable("chatglot.error.kind." + kind.name().toLowerCase(Locale.ROOT)),
            status
        );
    }

    public static Component providerName(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return Component.translatable("chatglot.error.unknown_provider");
        }
        String normalized = providerId.trim().toLowerCase(Locale.ROOT);
        if (!PROVIDER_IDS.contains(normalized)) {
            return Component.literal(providerId);
        }
        return Component.translatable("chatglot.config.provider." + normalized);
    }

    public static TranslationException translationException(Throwable error) {
        return findTranslationException(error);
    }

    private static Component reason(TranslationException.Kind kind, String providerId) {
        String key = "chatglot.error." + kind.name().toLowerCase(Locale.ROOT);
        return kind == TranslationException.Kind.DISABLED
            ? Component.translatable(key)
            : Component.translatable(key, providerName(providerId));
    }

    private static String hintKey(
        TranslationException.Kind kind,
        String providerId,
        String configuredProviderId
    ) {
        return switch (kind) {
            case AUTH -> "chatglot.error.auth.hint";
            case CONFIG_MISSING -> "chatglot.error.config_missing.hint";
            case RATE_LIMITED -> isDefaultGas(providerId, configuredProviderId)
                ? "chatglot.error.rate_limited.hint.default"
                : "chatglot.error.rate_limited.hint";
            case QUOTA -> "chatglot.error.quota.hint";
            case TIMEOUT -> "chatglot.error.timeout.hint";
            case NETWORK -> "translategemma_local".equalsIgnoreCase(providerId)
                ? "chatglot.error.network.hint.translategemma_local"
                : "chatglot.error.network.hint";
            case SERVER -> "chatglot.error.server.hint";
            case BAD_RESPONSE -> "chatglot.error.bad_response.hint";
            case EMPTY_OUTPUT -> "chatglot.error.empty_output.hint";
            case DISABLED -> "chatglot.error.disabled.hint";
            case UNKNOWN -> null;
        };
    }

    private static boolean isDefaultGas(String providerId, String configuredProviderId) {
        return ChatglotConfig.DEFAULT_PROVIDER.equalsIgnoreCase(configuredProviderId)
            || ("gas".equalsIgnoreCase(providerId)
                && (configuredProviderId == null || configuredProviderId.isBlank()));
    }

    private static String resolveProviderId(TranslationException failure, String configuredProviderId) {
        if (ChatglotConfig.DEFAULT_PROVIDER.equalsIgnoreCase(configuredProviderId)) {
            return ChatglotConfig.DEFAULT_PROVIDER;
        }
        if (failure != null && failure.providerId() != null && !failure.providerId().isBlank()) {
            return failure.providerId();
        }
        return configuredProviderId;
    }

    private static String limitedDetail(String detail) {
        if (detail == null || detail.isBlank()) {
            return "-";
        }
        int count = detail.codePointCount(0, detail.length());
        if (count <= MAX_DETAIL_LENGTH) {
            return detail;
        }
        int end = detail.offsetByCodePoints(0, MAX_DETAIL_LENGTH);
        return detail.substring(0, end) + "…";
    }

    private static TranslationException findTranslationException(Throwable error) {
        Map<Throwable, Boolean> visited = new IdentityHashMap<>();
        for (Throwable current = error; current != null && visited.put(current, Boolean.TRUE) == null;
             current = current.getCause()) {
            if (current instanceof TranslationException failure) {
                return failure;
            }
        }
        return null;
    }
}
