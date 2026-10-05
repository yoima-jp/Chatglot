package io.github.chatglot.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.chatglot.ChatglotRuntime;
import io.github.chatglot.client.SignTranslationState;
import io.github.chatglot.client.SignTranslationState.Key;
import io.github.chatglot.client.SignTranslationState.Target;
import io.github.chatglot.config.ChatglotConfig;
import io.github.chatglot.translation.LanguageUtil;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

final class SignTranslationController {
    private final KeyMapping translateKey;
    private SignTranslationState state = new SignTranslationState();
    private ClientLevel level;
    private ChatglotConfig settings;
    private Component narrated;
    private boolean loadingActionBarVisible;

    private SignTranslationController(KeyMapping.Category category) {
        translateKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.chatglot.translate_sign", InputConstants.Type.KEYBOARD, InputConstants.KEY_G, category
        ));
    }

    static KeyMapping register(KeyMapping.Category category) {
        SignTranslationController controller = new SignTranslationController(category);
        ClientTickEvents.END_CLIENT_TICK.register(controller::tick);
        // Chat messages occupy the same lower-screen area. Render after the vanilla chat
        // layer so active chat lines cannot cover the translation panel.
        HudElementRegistry.attachElementAfter(
            VanillaHudElements.CHAT, Identifier.fromNamespaceAndPath("chatglot", "sign_translation"),
            (graphics, deltaTracker) -> controller.render(graphics)
        );
        return controller.translateKey;
    }

    private void tick(Minecraft client) {
        ChatglotConfig config = ChatglotRuntime.get().configManager().get();
        // Saving or reloading replaces the config instance. Start a new state so model,
        // endpoint and other setting changes invalidate both cached and pending results.
        if (client.level != level || config != settings) {
            level = client.level;
            settings = config;
            state = new SignTranslationState();
            narrated = null;
        }
        if (client.level == null || client.player == null || client.gui.screen() != null || !config.enabled) {
            clearLoadingActionBar(client);
            state.hide();
            narrated = null;
            while (translateKey.consumeClick()) { }
            return;
        }
        long now = System.nanoTime() / 1_000_000;
        Target target = findTarget(client, config);
        state.observe(target, now);
        boolean pressed = false;
        while (translateKey.consumeClick()) {
            pressed = true;
        }
        if (pressed && state.request(target, now)) {
            translate(client, target.key());
        }
        updateLoadingActionBar(client);
        Component message = message();
        if (message != null && !message.equals(narrated)) {
            client.getNarrator().saySystemNow(message);
        }
        narrated = message;
    }

    private void updateLoadingActionBar(Minecraft client) {
        if (state.status() == SignTranslationState.Status.LOADING) {
            // Refresh every tick so long-running providers keep the standard action-bar
            // feedback visible until their request actually finishes.
            client.gui.hud.setOverlayMessage(Component.translatable("chatglot.sign.loading"), false);
            loadingActionBarVisible = true;
        } else {
            clearLoadingActionBar(client);
        }
    }

    private void clearLoadingActionBar(Minecraft client) {
        if (loadingActionBarVisible && client.player != null) {
            client.gui.hud.setOverlayMessage(Component.empty(), false);
        }
        loadingActionBarVisible = false;
    }

    private Target findTarget(Minecraft client, ChatglotConfig config) {
        if (!(client.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK
            || !(client.level.getBlockEntity(hit.getBlockPos()) instanceof SignBlockEntity sign)) {
            return null;
        }
        SignTextSlot slot = sign.getSlotPlayerIsFacing(client.player);
        boolean front = slot == SignTextSlot.FRONT;
        // Use the same filtered face as vanilla, without forwarding click/hover events.
        String text = sign.getText(slot).getMessages(client.isTextFilteringEnabled()).stream()
            .map(Component::getString).collect(Collectors.joining("\n")).strip();
        if (text.isBlank() || text.length() > 4096) {
            return null;
        }
        Key key = new Key(text, LanguageUtil.resolveConfiguredTargetLanguage(config.targetLanguage), config.provider);
        return new Target(hit.getBlockPos().getX(), hit.getBlockPos().getY(), hit.getBlockPos().getZ(), front, key);
    }

    private void translate(Minecraft client, Key key) {
        SignTranslationState requestedState = state;
        ClientLevel requestedLevel = client.level;
        ChatglotConfig requestedSettings = settings;
        ChatglotRuntime runtime = ChatglotRuntime.get();
        try {
            CompletableFuture<String> sourceLanguage = "translategemma_local".equalsIgnoreCase(key.provider())
                ? runtime.languageDetectorService().detectLanguageAsync(key.text()).thenApply(value -> value.orElse(""))
                : CompletableFuture.completedFuture("");
            sourceLanguage.thenComposeAsync(source -> {
                ChatglotConfig currentConfig = runtime.configManager().get();
                // Language detection can outlive the world or a provider selection.
                // Recheck before allowing its delayed result to send any sign text.
                if (state != requestedState || client.level != requestedLevel
                    || currentConfig != requestedSettings || !currentConfig.enabled
                    || !key.provider().equals(currentConfig.provider)
                    || !key.language().equals(LanguageUtil.resolveConfiguredTargetLanguage(currentConfig.targetLanguage))) {
                    throw new CancellationException("Sign translation context changed");
                }
                return runtime.translationService().translate(key.text(), key.language(), source, false);
            }, client::execute)
                .whenComplete((result, error) -> client.execute(() -> {
                    if (error != null && !(error instanceof CancellationException)
                        && !(error.getCause() instanceof CancellationException)) {
                        ChatglotFabric.LOGGER.warn("Sign translation failed", error);
                    }
                    if (state == requestedState && client.level == requestedLevel
                        && runtime.configManager().get() == requestedSettings) {
                        requestedState.complete(key, error == null && result != null ? result.translatedText() : null);
                    }
                }));
        } catch (RuntimeException error) {
            ChatglotFabric.LOGGER.warn("Could not start sign translation", error);
            requestedState.complete(key, null);
        }
    }

    private Component message() {
        return switch (state.status()) {
            case HIDDEN -> null;
            case LOADING -> Component.translatable("chatglot.sign.loading");
            case READY -> Component.literal(state.translation());
            case FAILED -> Component.translatable("chatglot.sign.failed", translateKey.getTranslatedKeyMessage());
            case BUSY -> Component.translatable("chatglot.sign.busy");
        };
    }

    private void render(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        if (state.status() == SignTranslationState.Status.LOADING) {
            return;
        }
        Component message = message();
        if (message == null || client.gui.screen() != null || client.level != level
            || !ChatglotRuntime.get().configManager().get().enabled) {
            return;
        }
        int maxTextWidth = Math.max(1, Math.min(360, graphics.guiWidth() - 32));
        List<FormattedCharSequence> lines = client.font.split(message, maxTextWidth);
        // Match vanilla sign alignment and size the panel from the longest wrapped line,
        // so short translations do not leave a large empty area on either side.
        int textWidth = 1;
        for (FormattedCharSequence line : lines) {
            textWidth = Math.max(textWidth, client.font.width(line));
        }
        int panelWidth = textWidth + 16;
        int height = lines.size() * client.font.lineHeight + 8;
        // Keep the complete translation on screen, including at large GUI scales.
        float scale = Math.min(1.0f, Math.max(1, graphics.guiHeight() - 100) / (float) height);
        float left = (graphics.guiWidth() - panelWidth * scale) / 2;
        // Leave enough room for the hotbar while keeping the result close to the
        // lower-screen area where the player was already watching progress.
        float top = Math.max(4, graphics.guiHeight() - 50 - height * scale);
        graphics.pose().pushMatrix();
        graphics.pose().translate(left, top);
        graphics.pose().scale(scale, scale);
        graphics.fill(0, 0, panelWidth, height, 0xE0101010);
        int textY = 4;
        for (FormattedCharSequence line : lines) {
            int textX = (panelWidth - client.font.width(line)) / 2;
            graphics.text(client.font, line, textX, textY, 0xFFFFFFFF, true);
            textY += client.font.lineHeight;
        }
        graphics.pose().popMatrix();
    }
}
