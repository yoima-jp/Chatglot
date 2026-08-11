package io.github.chatglot.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.chatglot.ChatglotRuntime;
import io.github.chatglot.config.ChatglotConfig;
import io.github.chatglot.fabric.command.ChatglotClientCommands;
import io.github.chatglot.ChatglotConstants;
import io.github.chatglot.moddeck.ChatglotModDeckConfig;
import io.github.chatglot.moddeck.ChatglotModDeckScreenOpener;
import io.github.chatglot.translation.LanguageUtil;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public final class ChatglotFabricClient implements ClientModInitializer {
    private static KeyMapping openConfigKey;
    private static final KeyMapping.Category CHATGLOT_CATEGORY = KeyMapping.Category.register(
        Identifier.fromNamespaceAndPath("chatglot", "main")
    );

    @Override
    public void onInitializeClient() {
        FabricLoader loader = FabricLoader.getInstance();
        ChatglotRuntime.initialize(loader.getConfigDir(), loader.getGameDir());
        applyMinecraftLanguageDefaultIfNeeded();
        applyLocalBackendPolicyOnStartup();

        ChatglotModDeckScreenOpener.setOpener((modId, parent) -> {
            Minecraft client = Minecraft.getInstance();
            client.execute(() -> {
                ChatglotModDeckConfig.refreshRegistration();
                client.setScreenAndShow(com.yoima.moddeck.api.ModDeckApi.createConfigScreen(modId, parent));
            });
        });
        ChatglotClientCommands.register();
        registerKeyBinding();
        registerLifecycleHooks();

        ChatglotFabric.LOGGER.info("Chatglot initialized.");
    }

    private static void registerKeyBinding() {
        openConfigKey = KeyMappingHelper.registerKeyMapping(
            new KeyMapping(
                "key.chatglot.open_config",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F8,
                CHATGLOT_CATEGORY
            )
        );

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openConfigKey.consumeClick()) {
                client.execute(() -> {
                    ChatglotModDeckConfig.refreshRegistration();
                    client.setScreenAndShow(com.yoima.moddeck.api.ModDeckApi.createConfigScreen(
                        ChatglotConstants.MOD_ID, client.gui.screen()));
                });
            }
        });
    }

    private static void applyMinecraftLanguageDefaultIfNeeded() {
        ChatglotRuntime runtime = ChatglotRuntime.get();
        if (!runtime.configManager().createdNewConfigFile()) {
            return;
        }

        ChatglotConfig config = runtime.configManager().get();
        config.targetLanguage = LanguageUtil.MINECRAFT_DEFAULT_TARGET;
        runtime.configManager().save();
    }

    private static void applyLocalBackendPolicyOnStartup() {
        ChatglotRuntime runtime = ChatglotRuntime.get();
        runtime.localBackendManager().applyConfiguredBackendPolicyAsync(runtime.configManager().get());
    }

    private static void registerLifecycleHooks() {
        // Minecraft only exposes its complete language catalog after client resources finish
        // loading. Registering earlier would leave the target-language selector with en_us only.
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> ChatglotModDeckConfig.registerIfAbsent());

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            if (!ChatglotRuntime.isInitialized()) {
                return;
            }
            ChatglotRuntime.get().shutdown();
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (!ChatglotRuntime.isInitialized()) {
                return;
            }
            ChatglotRuntime.get().shutdown();
        }, "chatglot-shutdown"));
    }
}
