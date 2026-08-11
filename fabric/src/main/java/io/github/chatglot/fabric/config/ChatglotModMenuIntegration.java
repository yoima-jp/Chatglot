package io.github.chatglot.fabric.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import io.github.chatglot.ChatglotConstants;
import io.github.chatglot.ChatglotRuntime;
import io.github.chatglot.moddeck.ChatglotModDeckConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.Screen;

public final class ChatglotModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return this::createScreen;
    }

    private Screen createScreen(Screen parent) {
        // Ensure the ModDeck definition is registered before opening. ModMenu
        // may call this before our client initializer has run.
        if (!ChatglotRuntime.isInitialized()) {
            FabricLoader loader = FabricLoader.getInstance();
            ChatglotRuntime.initialize(loader.getConfigDir(), loader.getGameDir());
        }
        ChatglotModDeckConfig.refreshRegistration();
        return com.yoima.moddeck.api.ModDeckApi.createConfigScreen(ChatglotConstants.MOD_ID, parent);
    }
}
