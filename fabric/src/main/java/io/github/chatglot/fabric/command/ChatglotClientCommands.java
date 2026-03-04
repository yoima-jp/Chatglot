package io.github.chatglot.fabric.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import io.github.chatglot.ChatglotRuntime;
import io.github.chatglot.client.ChatOutput;
import io.github.chatglot.client.ChatTranslationActions;
import io.github.chatglot.fabric.config.ChatglotConfigScreenFactory;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public final class ChatglotClientCommands {
    private ChatglotClientCommands() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(
                ClientCommandManager.literal("chatglot")
                    .then(
                        ClientCommandManager.literal("translate")
                            .then(
                                ClientCommandManager.argument("id", IntegerArgumentType.integer(1))
                                    .executes(context -> translateById(context.getSource(), IntegerArgumentType.getInteger(context, "id")))
                            )
                    )
                    .then(ClientCommandManager.literal("config").executes(context -> openConfig(context.getSource())))
                    .then(ClientCommandManager.literal("save").executes(context -> saveConfig(context.getSource())))
            )
        );
    }

    private static int translateById(FabricClientCommandSource source, int id) {
        ChatglotRuntime runtime = ChatglotRuntime.get();
        return runtime.requestStore().find(id)
            .map(found -> {
                ChatTranslationActions.translateAndPublish(found.originalText(), "", false, found.signature());
                return 1;
            })
            .orElseGet(() -> {
                source.sendFeedback(Text.translatable("chatglot.command.translate.not_found").formatted(Formatting.RED));
                return 0;
            });
    }

    private static int openConfig(FabricClientCommandSource source) {
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> client.setScreen(ChatglotConfigScreenFactory.create(client.currentScreen)));
        source.sendFeedback(Text.translatable("chatglot.command.config.opened").formatted(Formatting.GRAY));
        return 1;
    }

    private static int saveConfig(FabricClientCommandSource source) {
        ChatglotRuntime.get().configManager().save();
        source.sendFeedback(Text.translatable("chatglot.command.config.saved").formatted(Formatting.GRAY));
        ChatOutput.postInfo("Config saved.");
        return 1;
    }
}
