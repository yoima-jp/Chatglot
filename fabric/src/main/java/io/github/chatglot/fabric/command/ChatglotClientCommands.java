package io.github.chatglot.fabric.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import io.github.chatglot.ChatglotRuntime;
import io.github.chatglot.client.ChatOutput;
import io.github.chatglot.client.ChatTranslationActions;
import io.github.chatglot.fabric.config.ChatglotConfigScreenFactory;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

public final class ChatglotClientCommands {
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
        client.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.5F, 1.0F);
    }

    private static int openConfig(FabricClientCommandSource source) {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> client.gui.setScreen(ChatglotConfigScreenFactory.create(client.gui.screen())));
        source.sendFeedback(Component.translatable("chatglot.command.config.opened").withStyle(ChatFormatting.GRAY));
        return 1;
    }

    private static int saveConfig(FabricClientCommandSource source) {
        ChatglotRuntime.get().configManager().save();
        source.sendFeedback(Component.translatable("chatglot.command.config.saved").withStyle(ChatFormatting.GRAY));
        ChatOutput.postInfo("Config saved.");
        return 1;
    }
}
