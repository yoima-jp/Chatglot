package io.github.chatglot.fabric;

import io.github.chatglot.ChatglotRuntime;
import io.github.chatglot.config.ChatglotConfig;
import io.github.chatglot.fabric.command.ChatglotClientCommands;
import io.github.chatglot.fabric.config.ChatglotConfigScreenFactory;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

public final class ChatglotFabricClient implements ClientModInitializer {
    private static KeyBinding openConfigKey;
    private static final KeyBinding.Category CHATGLOT_CATEGORY = KeyBinding.Category.create(
        Identifier.of("chatglot", "main")
    );

    @Override
    public void onInitializeClient() {
        FabricLoader loader = FabricLoader.getInstance();
        ChatglotRuntime.initialize(loader.getConfigDir(), loader.getGameDir());
        applyMinecraftLanguageDefaultIfNeeded();

        ChatglotClientCommands.register();
        registerKeyBinding();

        ChatglotFabric.LOGGER.info("Chatglot initialized.");
    }

    private static void registerKeyBinding() {
        openConfigKey = KeyBindingHelper.registerKeyBinding(
            new KeyBinding(
                "key.chatglot.open_config",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F8,
                CHATGLOT_CATEGORY
            )
        );

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openConfigKey.wasPressed()) {
                client.setScreen(ChatglotConfigScreenFactory.create(client.currentScreen));
            }
        });
    }

    private static void applyMinecraftLanguageDefaultIfNeeded() {
        ChatglotRuntime runtime = ChatglotRuntime.get();
        if (!runtime.configManager().createdNewConfigFile()) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getLanguageManager() == null) {
            return;
        }

        String languageCode = client.getLanguageManager().getLanguage();
        String resolvedTarget = normalizeLanguageCode(languageCode);
        if (resolvedTarget.isBlank()) {
            return;
        }

        ChatglotConfig config = runtime.configManager().get();
        config.targetLanguage = resolvedTarget;
        runtime.configManager().save();
    }

    private static String normalizeLanguageCode(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim().replace('-', '_').toLowerCase(Locale.ROOT);
    }
}
