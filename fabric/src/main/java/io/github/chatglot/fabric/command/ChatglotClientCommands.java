package io.github.chatglot.fabric.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import io.github.chatglot.ChatglotRuntime;
import io.github.chatglot.ChatglotConstants;
import io.github.chatglot.client.ChatOutput;
import io.github.chatglot.client.ChatTranslationActions;
import io.github.chatglot.client.TranslationErrorMessages;
import io.github.chatglot.config.ChatglotConfig;
import io.github.chatglot.moddeck.ChatglotModDeckConfig;
import io.github.chatglot.translation.LanguageUtil;
import io.github.chatglot.translation.TranslationException;
import java.util.concurrent.CompletionException;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ChatglotClientCommands {
    private static final Logger LOGGER = LoggerFactory.getLogger("Chatglot/Commands");
    private static final String DIAGNOSTIC_TEXT = "Hello";

    private ChatglotClientCommands() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(
                ClientCommands.literal("chatglot")
                    .then(
                        ClientCommands.literal("translate")
                            .then(
                                ClientCommands.argument("id", IntegerArgumentType.integer(1))
                                    .executes(context -> translateById(context.getSource(), IntegerArgumentType.getInteger(context, "id")))
                            )
                    )
                    .then(ClientCommands.literal("config").executes(context -> openConfig(context.getSource())))
                    .then(ClientCommands.literal("diagnose").executes(context -> diagnose(context.getSource())))
                    .then(ClientCommands.literal("cache")
                        .then(ClientCommands.literal("clear").executes(context -> clearCache(context.getSource()))))
                    .then(ClientCommands.literal("save").executes(context -> saveConfig(context.getSource())))
            )
        );
    }

    private static int translateById(FabricClientCommandSource source, int id) {
        ChatglotRuntime runtime = ChatglotRuntime.get();
        return runtime.requestStore().find(id)
            .map(found -> {
                playTranslateClickSound();
                ChatTranslationActions.translateAndPublish(
                    found.originalText(),
                    found.originalMessage(),
                    "",
                    false,
                    found.signature()
                );
                return 1;
            })
            .orElseGet(() -> {
                source.sendFeedback(Component.translatable("chatglot.command.translate.not_found").withStyle(ChatFormatting.RED));
                return 0;
            });
    }

    private static void playTranslateClickSound() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        // LocalPlayer#playSound accepts a SoundEvent directly; avoid the inherited Entity overload
        // that the compiler cannot resolve due to a missing transitive Fluid API interface.
        client.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.5F, 1.0F);
    }

    private static int openConfig(FabricClientCommandSource source) {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            ChatglotModDeckConfig.refreshRegistration();
            client.gui.setScreen(
                com.yoima.moddeck.api.ModDeckApi.createConfigScreen(ChatglotConstants.MOD_ID, client.gui.screen()));
        });
        source.sendFeedback(Component.translatable("chatglot.command.config.opened").withStyle(ChatFormatting.GRAY));
        return 1;
    }

    private static int diagnose(FabricClientCommandSource source) {
        ChatglotRuntime runtime = ChatglotRuntime.get();
        ChatglotConfig config = runtime.configManager().get();
        config.sanitize();
        Minecraft client = Minecraft.getInstance();
        if (!config.enabled) {
            ChatOutput.post(TranslationErrorMessages.chat(
                TranslationException.classified(
                    TranslationException.Kind.DISABLED,
                    "Chatglot is disabled in config.",
                    null
                ),
                config.provider
            ));
            return 1;
        }
        ChatOutput.post(Component.translatable(
            "chatglot.command.diagnose.sending",
            DIAGNOSTIC_TEXT,
            TranslationErrorMessages.providerName(config.provider)
        ).withStyle(ChatFormatting.GRAY));

        if ("translategemma_local".equalsIgnoreCase(config.provider)) {
            runtime.localBackendManager().checkStatusAsync(config.copy()).whenComplete((status, error) ->
                client.execute(() -> {
                    if (error != null) {
                        LOGGER.warn("Could not check local backend before translation diagnostics", unwrap(error));
                        ChatOutput.post(Component.translatable("chatglot.command.diagnose.backend_unknown")
                            .withStyle(ChatFormatting.GRAY));
                    } else {
                        String key = status.healthy()
                            ? "chatglot.command.diagnose.backend_healthy"
                            : "chatglot.command.diagnose.backend_unavailable";
                        ChatOutput.post(Component.translatable(key).withStyle(
                            status.healthy() ? ChatFormatting.GREEN : ChatFormatting.YELLOW
                        ));
                    }
                    client.execute(() -> startDiagnosis(runtime, client, config));
                })
            );
        } else {
            client.execute(() -> startDiagnosis(runtime, client, config));
        }
        return 1;
    }

    private static void startDiagnosis(ChatglotRuntime runtime, Minecraft client, ChatglotConfig config) {
        // Do not send to a provider that changed while the local health check was running.
        if (runtime.configManager().get() != config) {
            ChatOutput.post(Component.translatable("chatglot.command.diagnose.settings_changed")
                .withStyle(ChatFormatting.YELLOW));
            return;
        }

        String targetLanguage = LanguageUtil.resolveConfiguredTargetLanguage(config.targetLanguage);
        long startedAt = System.nanoTime();
        try {
            runtime.translationService().translateWithoutCache(DIAGNOSTIC_TEXT, targetLanguage, "", false)
                .whenComplete((result, error) -> {
                    long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;
                    client.execute(() -> {
                        if (error != null || result == null) {
                            Throwable failure = error == null
                                ? new IllegalStateException("Diagnostic translation returned no result")
                                : unwrap(error);
                            reportDiagnosticFailure(config, failure);
                            return;
                        }
                        ChatOutput.post(Component.translatable(
                            "chatglot.command.diagnose.success",
                            TranslationErrorMessages.providerName(config.provider),
                            elapsedMillis,
                            result.translatedText()
                        ).withStyle(ChatFormatting.GREEN));
                    });
                });
        } catch (RuntimeException error) {
            reportDiagnosticFailure(config, error);
        }
    }

    private static void reportDiagnosticFailure(ChatglotConfig config, Throwable error) {
        Throwable failure = unwrap(error);
        LOGGER.warn("Translation diagnostic failed", failure);
        ChatOutput.post(TranslationErrorMessages.chat(failure, config.provider));
        ChatOutput.post(Component.empty().append(TranslationErrorMessages.diagnosticDetails(failure))
            .withStyle(ChatFormatting.GRAY));
    }

    private static Throwable unwrap(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) {
            error = error.getCause();
        }
        return error;
    }

    private static int saveConfig(FabricClientCommandSource source) {
        ChatglotRuntime runtime = ChatglotRuntime.get();
        runtime.configManager().save();
        runtime.translationCache().setMaxEntries(runtime.configManager().get().translationCacheMaxEntries);
        source.sendFeedback(Component.translatable("chatglot.command.config.saved").withStyle(ChatFormatting.GRAY));
        ChatOutput.postInfo("Config saved.");
        return 1;
    }

    private static int clearCache(FabricClientCommandSource source) {
        ChatglotRuntime.get().translationCache().clear();
        source.sendFeedback(Component.translatable("chatglot.command.cache.cleared").withStyle(ChatFormatting.GRAY));
        return 1;
    }
}
