package io.github.chatglot.fabric;

import io.github.chatglot.ChatglotRuntime;
import io.github.chatglot.client.ChatInputTranslationState;
import io.github.chatglot.client.ChatInputTranslationState.Request;
import io.github.chatglot.client.ChatInputTranslationState.Status;
import io.github.chatglot.client.MinecraftLanguageCatalog;
import io.github.chatglot.client.TranslationErrorMessages;
import io.github.chatglot.config.ChatglotConfig;
import io.github.chatglot.fabric.mixin.ChatScreenAccessor;
import io.github.chatglot.translation.LanguageUtil;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Adds translation controls to the vanilla chat input without replacing its value responder. */
public final class ChatInputTranslationController {
    private static final int MIN_LANGUAGE_BUTTON_WIDTH = 52;
    private static final int MAX_LANGUAGE_BUTTON_WIDTH = 86;
    private static final int TRANSLATE_BUTTON_WIDTH = 20;
    private static final int BUTTON_GAP = 2;
    private static ChatInputTranslationController instance;

    private final Map<Screen, ScreenState> screens = new WeakHashMap<>();

    private ChatInputTranslationController() { }

    static void register() {
        ChatInputTranslationController controller = new ChatInputTranslationController();
        instance = controller;
        ScreenEvents.AFTER_INIT.register(controller::afterInit);
    }

    public static void inputEdited(ChatScreen screen, String value) {
        if (instance == null) {
            return;
        }
        ScreenState state = instance.screens.get(screen);
        if (state == null) {
            return;
        }
        state.translation.observe(value, targetLanguage(ChatglotRuntime.get().configManager().get()));
        instance.refresh(Minecraft.getInstance(), screen, state);
        instance.updatePreview(screen, state);
    }

    private void afterInit(Minecraft client, Screen screen, int scaledWidth, int scaledHeight) {
        if (!(screen instanceof ChatScreen chatScreen)) {
            return;
        }
        ChatglotConfig config = ChatglotRuntime.get().configManager().get();
        ScreenState state = screens.get(screen);
        if (state == null) {
            state = new ScreenState();
            screens.put(screen, state);
        } else {
            // A resize/re-init rebuilds vanilla widgets and Fabric screen events. Cancel callbacks
            // from the old input and register fresh handlers against the newly created widgets.
            state.translation.invalidate();
            state.closePopup(screen);
            state.removePreview(screen);
        }
        ScreenState screenState = state;
        ChatScreenAccessor access = (ChatScreenAccessor) chatScreen;
        screenState.input = access.chatglot$getInput();
        screenState.suggestions = access.chatglot$getCommandSuggestions();
        screenState.provider = config.provider;
        screenState.inputWidth = screenState.input.getWidth();
        screenState.languageButtonWidth = languageButtonWidth(client, config);
        layoutInput(screenState);
        int controlsX = screenState.input.getX() + screenState.input.getWidth() + BUTTON_GAP;
        screenState.languageButton = Button.builder(Component.empty(), ignored -> openLanguagePopup(client, screen, screenState))
            .bounds(controlsX, screenState.input.getY(), screenState.languageButtonWidth, screenState.input.getHeight())
            .build();
        screenState.translateButton = Button.builder(Component.translatable("chatglot.input.translate"), ignored -> translate(client, screen, screenState))
            .bounds(controlsX + screenState.languageButtonWidth + BUTTON_GAP, screenState.input.getY(),
                TRANSLATE_BUTTON_WIDTH, screenState.input.getHeight())
            .build();
        addMouseOnlyWidget(screen, screenState.languageButton);
        addMouseOnlyWidget(screen, screenState.translateButton);

        registerScreenEvents(screen, screenState);
        refresh(client, screen, screenState);
    }

    private void registerScreenEvents(Screen screen, ScreenState state) {
        ScreenEvents.afterTick(screen).register(ignored -> tick(screen, state));
        ScreenKeyboardEvents.allowKeyPress(screen).register((ignored, event) -> allowKeyPress(screen, state, event));
        ScreenMouseEvents.allowMouseClick(screen).register((ignored, event) -> allowMouseClick(screen, state, event));
        ScreenMouseEvents.allowMouseRelease(screen).register((ignored, event) ->
            allowMouseRelease(state, event));
        ScreenMouseEvents.allowMouseDrag(screen).register((ignored, event, deltaX, deltaY) ->
            allowMouseDrag(state, event, deltaX, deltaY));
        ScreenMouseEvents.allowMouseScroll(screen).register((ignored, mouseX, mouseY, horizontal, vertical) ->
            allowMouseScroll(screen, state, mouseX, mouseY, horizontal, vertical));
        ScreenEvents.remove(screen).register(ignored -> remove(screen, state));
    }

    private static void addMouseOnlyWidget(Screen screen, AbstractWidget widget) {
        Screens.getWidgets(screen).add(widget);
        // ChatScreen calls Screen.keyPressed before applying ↑/↓ history, so Screen's focus
        // traversal can otherwise consume those keys. Keep these controls rendered but out of
        // the focusable children; allowMouseClick dispatches their clicks directly.
        screen.children().remove(widget);
    }

    private void tick(Screen screen, ScreenState state) {
        Minecraft client = Minecraft.getInstance();
        refresh(client, screen, state);
        if (isPreviewApplicable(client, screen, state)) {
            // ChatScreen checks CommandSuggestions before its widgets. Hiding ordinary-message
            // completions while a preview can be accepted makes unmodified Tab unambiguous.
            state.suggestions.hide();
        }
        updatePreview(screen, state);
    }

    private void refresh(Minecraft client, Screen screen, ScreenState state) {
        if (!(screen instanceof ChatScreen chatScreen) || state.input == null) {
            return;
        }
        ChatglotConfig config = ChatglotRuntime.get().configManager().get();
        if (!state.provider.equals(config.provider)) {
            state.translation.invalidate();
            state.provider = config.provider;
        }
        String language = targetLanguage(config);
        state.translation.observe(state.input.getValue(), language);
        updateButtons(client, chatScreen, state, config);
    }

    private void updateButtons(Minecraft client, ChatScreen screen, ScreenState state, ChatglotConfig config) {
        if (state.languageButton == null || state.translateButton == null) {
            return;
        }
        state.languageButtonWidth = languageButtonWidth(client, config);
        layoutInput(state);
        state.languageButton.setMessage(Component.translatable(
            "chatglot.input.language_button", languageCode(config.outgoingTargetLanguage)));
        boolean command = isCommand(screen, state.input.getValue());
        boolean blank = state.input.getValue() == null || state.input.getValue().isBlank();
        boolean ready = isScreenReady(client, screen, state) && !blank && !command
            && state.translation.status() != Status.LOADING;
        state.translateButton.active = ready;
        if (!config.enabled) {
            state.translateButton.setTooltip(Tooltip.create(Component.translatable("chatglot.input.disabled")));
        } else if (blank) {
            state.translateButton.setTooltip(Tooltip.create(Component.translatable("chatglot.input.empty")));
        } else if (command) {
            state.translateButton.setTooltip(Tooltip.create(Component.translatable("chatglot.input.command")));
        } else if (state.translation.status() == Status.LOADING) {
            state.translateButton.setTooltip(Tooltip.create(Component.translatable("chatglot.input.loading")));
        } else {
            state.translateButton.setTooltip(null);
        }
        state.languageButton.active = isScreenReady(client, screen, state);
    }

    private static int languageButtonWidth(Minecraft client, ChatglotConfig config) {
        int labelWidth = client.font.width(Component.translatable(
            "chatglot.input.language_button", languageCode(config.outgoingTargetLanguage)));
        return Math.max(MIN_LANGUAGE_BUTTON_WIDTH, Math.min(MAX_LANGUAGE_BUTTON_WIDTH, labelWidth + 12));
    }

    private static void layoutInput(ScreenState state) {
        int controlsWidth = state.languageButtonWidth + TRANSLATE_BUTTON_WIDTH + BUTTON_GAP * 2;
        state.input.setWidth(Math.max(8, state.inputWidth - controlsWidth));
        if (state.languageButton == null || state.translateButton == null) {
            return;
        }
        int controlsX = state.input.getX() + state.input.getWidth() + BUTTON_GAP;
        state.languageButton.setRectangle(controlsX, state.input.getY(),
            state.languageButtonWidth, state.input.getHeight());
        state.translateButton.setRectangle(controlsX + state.languageButtonWidth + BUTTON_GAP,
            state.input.getY(), TRANSLATE_BUTTON_WIDTH, state.input.getHeight());
    }

    private void translate(Minecraft client, Screen screen, ScreenState state) {
        refresh(client, screen, state);
        if (!(screen instanceof ChatScreen chatScreen) || !isScreenReady(client, screen, state)) {
            return;
        }
        ChatglotConfig config = ChatglotRuntime.get().configManager().get();
        String text = state.input.getValue();
        if (!config.enabled || !ChatInputTranslationState.canTranslate(text) || isCommand(chatScreen, text)) {
            updateButtons(client, chatScreen, state, config);
            return;
        }

        String language = targetLanguage(config);
        Request request = state.translation.start(text, language, config.provider);
        if (request == null) {
            return;
        }
        updatePreview(screen, state);
        ChatglotRuntime runtime = ChatglotRuntime.get();
        try {
            CompletableFuture<String> sourceLanguage = "translategemma_local".equalsIgnoreCase(request.provider())
                ? runtime.languageDetectorService().detectLanguageAsync(request.text()).thenApply(value -> value.orElse(""))
                : CompletableFuture.completedFuture("");
            sourceLanguage.thenComposeAsync(source -> {
                if (!isCurrentRequest(client, screen, state, request)) {
                    throw new CancellationException("Chat input translation context changed");
                }
                // ChatTranslationService has no "auto" source mode; an empty hint lets regular
                // providers decide, while TranslateGemma receives the asynchronous detection.
                return runtime.translationService().translate(request.text(), request.language(), source, false);
            }, client::execute).whenComplete((result, error) -> client.execute(() -> {
                Throwable failure = unwrap(error);
                if (failure != null && !(failure instanceof CancellationException)) {
                    ChatglotFabric.LOGGER.warn("Chat input translation failed", failure);
                }
                if (!isCurrentRequest(client, screen, state, request)) {
                    return;
                }
                state.translation.complete(request, result == null ? null : result.translatedText(), failure);
                refresh(client, screen, state);
                updatePreview(screen, state);
            }));
        } catch (RuntimeException error) {
            ChatglotFabric.LOGGER.warn("Could not start chat input translation", error);
            state.translation.complete(request, null, error);
            updatePreview(screen, state);
        }
    }

    private boolean isCurrentRequest(Minecraft client, Screen screen, ScreenState state, Request request) {
        if (!(screen instanceof ChatScreen chatScreen) || !isScreenReady(client, screen, state)
            || !state.translation.isLatest(request)) {
            return false;
        }
        ChatglotConfig config = ChatglotRuntime.get().configManager().get();
        return config.enabled && request.provider().equals(config.provider)
            && request.language().equals(targetLanguage(config))
            && request.text().equals(state.input.getValue())
            && !isCommand(chatScreen, state.input.getValue());
    }

    private boolean isScreenReady(Minecraft client, Screen screen, ScreenState state) {
        return screens.get(screen) == state && client.gui.screen() == screen && state.input != null
            && ChatglotRuntime.get().configManager().get().enabled;
    }

    private boolean isPreviewApplicable(Minecraft client, Screen screen, ScreenState state) {
        if (!(screen instanceof ChatScreen chatScreen)) {
            return false;
        }
        refresh(client, screen, state);
        ChatglotConfig config = ChatglotRuntime.get().configManager().get();
        return isScreenReady(client, screen, state)
            && state.translation.canApply(state.input.getValue(), targetLanguage(config), config.provider)
            && !isCommand(chatScreen, state.input.getValue());
    }

    private void updatePreview(Screen screen, ScreenState state) {
        Status status = state.translation.status();
        if (status == Status.HIDDEN) {
            state.removePreview(screen);
            return;
        }
        if (!(screen instanceof ChatScreen)) {
            return;
        }
        ChatglotConfig config = ChatglotRuntime.get().configManager().get();
        String code = languageCode(config.outgoingTargetLanguage);
        boolean canApply = isScreenReady(Minecraft.getInstance(), screen, state)
            && state.translation.canApply(state.input.getValue(), targetLanguage(config), config.provider);
        if (state.preview == null) {
            state.preview = new PreviewWidget(0, 0, 1, 1, state, code, this, screen);
            state.closePreviewButton = Button.builder(Component.translatable("chatglot.input.close"), ignored -> {
                    state.translation.discard();
                    state.restoreInputFocus(screen);
                })
                .tooltip(Tooltip.create(Component.translatable("chatglot.input.discard")))
                .build();
            addMouseOnlyWidget(screen, state.preview);
            addMouseOnlyWidget(screen, state.closePreviewButton);
        }
        int previewWidth = Math.max(20, state.inputWidth - 26);
        int previewX = state.input.getX();
        int previewHeight = state.preview.measure(previewWidth, code, state.translation.translation(), status,
            state.translation.failure(), state.translation.tooLong(), canApply,
            config.provider, Math.max(34, state.input.getY() - 8));
        int previewY = Math.max(4, state.input.getY() - previewHeight - 4);
        state.preview.update(previewX, previewY, previewWidth, previewHeight, code,
            state.translation.translation(), status, state.translation.failure(),
            state.translation.tooLong(), canApply, config.provider);
        state.closePreviewButton.setRectangle(previewX + previewWidth + 4, previewY, 22, 20);
        state.closePreviewButton.active = true;
        if (status == Status.FAILED) {
            state.preview.setTooltip(Tooltip.create(TranslationErrorMessages.chat(
                state.translation.failure(), config.provider)));
        } else if (state.translation.tooLong()) {
            state.preview.setTooltip(Tooltip.create(Component.translatable("chatglot.input.too_long")));
        } else if (state.translation.translation() != null) {
            state.preview.setTooltip(Tooltip.create(Component.literal(state.translation.translation())));
        } else {
            state.preview.setTooltip(null);
        }
    }

    private void applyPreview(Minecraft client, Screen screen, ScreenState state) {
        if (!(screen instanceof ChatScreen chatScreen)) {
            return;
        }
        refresh(client, screen, state);
        ChatglotConfig config = ChatglotRuntime.get().configManager().get();
        if (!isPreviewApplicable(client, screen, state)) {
            return;
        }
        String input = state.input.getValue();
        String normalized = ((ChatScreenAccessor) chatScreen).chatglot$normalizeChatMessage(input);
        if (normalized.startsWith("/") || state.translation.tooLong()) {
            return;
        }
        String translation = state.translation.translation();
        // EditBox#setValue silently cuts UTF-16 text at its character limit. Keep an over-limit
        // preview visible and disabled so accepting it cannot silently lose translated content.
        if (translation == null || translation.length() > ChatInputTranslationState.CHAT_LIMIT) {
            return;
        }
        state.input.setValue(translation);
        state.input.moveCursorToEnd(false);
        state.translation.discard();
        state.input.setFocused(true);
        screen.setFocused(state.input);
        updatePreview(screen, state);
    }

    private boolean allowKeyPress(Screen screen, ScreenState state, KeyEvent event) {
        if (state.popup != null) {
            if (event.key() == InputConstants.KEY_ESCAPE) {
                state.closePopup(screen);
                state.restoreInputFocus(screen);
                return false;
            }
            if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) {
                LanguageEntry selected = state.popup.list.getSelected();
                if (selected != null) {
                    selectLanguage(screen, state, selected.option);
                }
                return false;
            }
            switch (event.key()) {
                case InputConstants.KEY_UP -> state.popup.list.moveSelection(-1);
                case InputConstants.KEY_DOWN -> state.popup.list.moveSelection(1);
                case InputConstants.KEY_PAGEUP -> state.popup.list.moveSelection(-8);
                case InputConstants.KEY_PAGEDOWN -> state.popup.list.moveSelection(8);
                case InputConstants.KEY_HOME -> state.popup.list.moveSelectionTo(0);
                case InputConstants.KEY_END -> state.popup.list.moveSelectionTo(
                    state.popup.list.children().size() - 1);
                default -> state.popup.list.keyPressed(event);
            }
            return false;
        }
        if (event.key() == InputConstants.KEY_TAB && event.modifiers() == 0
            && isPreviewApplicable(Minecraft.getInstance(), screen, state)) {
            applyPreview(Minecraft.getInstance(), screen, state);
            return false;
        }
        return true;
    }

    private boolean allowMouseClick(Screen screen, ScreenState state, MouseButtonEvent event) {
        double x = event.x();
        double y = event.y();
        if (state.popup != null) {
            state.popupMouseGesture = true;
            if (state.popup.contains(x, y)) {
                for (Button recent : state.popup.recentButtons) {
                    if (recent.isMouseOver(x, y)) {
                        recent.mouseClicked(event, false);
                        return false;
                    }
                }
                state.popup.list.mouseClicked(event, false);
                return false;
            }
            // The same press would otherwise continue through ChatScreen and activate controls
            // or chat links behind the picker after it closes.
            state.closePopup(screen);
            state.restoreInputFocus(screen);
            return false;
        }
        state.popupMouseGesture = false;
        if (state.languageButton != null && state.languageButton.isMouseOver(x, y)) {
            state.languageButton.mouseClicked(event, false);
            state.restoreInputFocus(screen);
            return false;
        }
        if (state.translateButton != null && state.translateButton.isMouseOver(x, y)) {
            state.translateButton.mouseClicked(event, false);
            state.restoreInputFocus(screen);
            return false;
        }
        if (state.preview != null && state.preview.isMouseOver(x, y)) {
            state.preview.mouseClicked(event, false);
            state.restoreInputFocus(screen);
            return false;
        }
        if (state.closePreviewButton != null && state.closePreviewButton.isMouseOver(x, y)) {
            state.closePreviewButton.mouseClicked(event, false);
            state.restoreInputFocus(screen);
            return false;
        }
        return true;
    }

    private boolean allowMouseRelease(ScreenState state, MouseButtonEvent event) {
        if (state.popup == null && !state.popupMouseGesture) {
            return true;
        }
        if (state.popup != null) {
            state.popup.list.mouseReleased(event);
        }
        state.popupMouseGesture = false;
        return false;
    }

    private boolean allowMouseDrag(ScreenState state, MouseButtonEvent event, double deltaX, double deltaY) {
        if (state.popup == null) {
            return !state.popupMouseGesture;
        }
        state.popup.list.mouseDragged(event, deltaX, deltaY);
        return false;
    }

    private boolean allowMouseScroll(Screen screen, ScreenState state, double mouseX, double mouseY,
                                     double horizontalAmount, double verticalAmount) {
        if (state.popup == null) {
            return true;
        }
        if (state.popup.list.isMouseOver(mouseX, mouseY)) {
            state.popup.list.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }
        // ChatScreen forwards scrolling to CommandSuggestions before its widgets, so consume
        // scrolls while the picker is open even when the pointer is outside the list.
        return false;
    }

    private void openLanguagePopup(Minecraft client, Screen screen, ScreenState state) {
        if (!(screen instanceof ChatScreen) || !isScreenReady(client, screen, state)) {
            return;
        }
        state.closePopup(screen);
        int width = Math.min(252, Math.max(180, screen.width - 8));
        int x = Math.max(4, Math.min(state.input.getX(), screen.width - width - 4));
        int top = Math.max(4, state.input.getY() - 162);
        int listTop = top + 34;
        int listHeight = Math.max(60, state.input.getY() - 22 - listTop);
        List<MinecraftLanguageCatalog.Option> options = MinecraftLanguageCatalog.collect(client);
        LanguagePopup popup = new LanguagePopup(x, top, width, listTop + listHeight, new LanguageList(
            client, x, listTop, width, listHeight, state, options));
        state.popup = popup;
        int count = Math.min(ChatInputTranslationState.RECENT_LANGUAGE_LIMIT, ChatglotRuntime.get()
            .configManager().get().recentOutgoingLanguages.size());
        int recentWidth = count == 0 ? 0 : Math.max(24, (width - 66) / count);
        for (int index = 0; index < count; index++) {
            String recentCode = ChatglotRuntime.get().configManager().get().recentOutgoingLanguages.get(index);
            MinecraftLanguageCatalog.Option option = findLanguage(options, recentCode);
            if (option == null) {
                continue;
            }
            int buttonX = x + 58 + index * (recentWidth + 2);
            Button recentButton = Button.builder(Component.literal(languageCode(recentCode)), ignored ->
                    selectLanguage(screen, state, option))
                .bounds(buttonX, top + 4, recentWidth, 20)
                .tooltip(Tooltip.create(Component.literal(option.label())))
                .build();
            popup.recentButtons.add(recentButton);
            addMouseOnlyWidget(screen, recentButton);
        }
        popup.title = new PopupTitleWidget(x + 6, top + 6, 50, 16,
            Component.translatable("chatglot.input.recent"));
        addMouseOnlyWidget(screen, popup.title);
        Screens.getWidgets(screen).add(popup.list);
        state.input.setFocused(false);
        popup.list.setFocused(popup.list.getSelected());
        screen.setFocused(popup.list);
    }

    private void selectLanguage(Screen screen, ScreenState state, MinecraftLanguageCatalog.Option option) {
        ChatglotRuntime runtime = ChatglotRuntime.get();
        ChatglotConfig config = runtime.configManager().get();
        config.outgoingTargetLanguage = option.code();
        config.recentOutgoingLanguages = ChatInputTranslationState.updateRecentLanguages(
            config.recentOutgoingLanguages, option.code());
        runtime.configManager().save();
        config = runtime.configManager().get();
        state.provider = config.provider;
        state.closePopup(screen);
        state.input.setFocused(true);
        screen.setFocused(state.input);
        refresh(Minecraft.getInstance(), screen, state);
        updatePreview(screen, state);
    }

    private static MinecraftLanguageCatalog.Option findLanguage(
        List<MinecraftLanguageCatalog.Option> options, String code
    ) {
        String normalized = code == null ? "" : code.replace('-', '_');
        return options.stream().filter(option -> option.code().equalsIgnoreCase(normalized)).findFirst().orElse(null);
    }

    private static String targetLanguage(ChatglotConfig config) {
        return LanguageUtil.normalizeTargetLanguage(config.outgoingTargetLanguage);
    }

    private static String languageCode(String value) {
        if (value == null || value.isBlank()) {
            return "EN";
        }
        String normalized = value.trim().replace('_', '-').toUpperCase(java.util.Locale.ROOT);
        return normalized;
    }

    private static boolean isCommand(ChatScreen screen, String value) {
        if (value == null) {
            return false;
        }
        String normalized = ((ChatScreenAccessor) screen).chatglot$normalizeChatMessage(value);
        return normalized != null && normalized.startsWith("/");
    }

    private void remove(Screen screen, ScreenState state) {
        state.translation.invalidate();
        state.closePopup(screen);
        state.removePreview(screen);
        screens.remove(screen);
    }

    private static Throwable unwrap(Throwable throwable) {
        while ((throwable instanceof CompletionException || throwable instanceof ExecutionException)
            && throwable.getCause() != null) {
            throwable = throwable.getCause();
        }
        return throwable;
    }

    private static final class ScreenState {
        private final ChatInputTranslationState translation = new ChatInputTranslationState();
        private net.minecraft.client.gui.components.EditBox input;
        private net.minecraft.client.gui.components.CommandSuggestions suggestions;
        private int inputWidth;
        private int languageButtonWidth;
        private String provider = "";
        private Button languageButton;
        private Button translateButton;
        private PreviewWidget preview;
        private Button closePreviewButton;
        private LanguagePopup popup;
        private boolean popupMouseGesture;

        private void removePreview(Screen screen) {
            boolean hadFocus = (preview != null && screen.getFocused() == preview)
                || (closePreviewButton != null && screen.getFocused() == closePreviewButton);
            if (preview != null) {
                Screens.getWidgets(screen).remove(preview);
                preview = null;
            }
            if (closePreviewButton != null) {
                Screens.getWidgets(screen).remove(closePreviewButton);
                closePreviewButton = null;
            }
            if (hadFocus) {
                restoreInputFocus(screen);
            }
        }

        private void closePopup(Screen screen) {
            if (popup == null) {
                return;
            }
            boolean hadFocus = screen.getFocused() == popup.list || screen.getFocused() == popup.title
                || popup.recentButtons.contains(screen.getFocused());
            for (Button button : popup.recentButtons) {
                Screens.getWidgets(screen).remove(button);
            }
            Screens.getWidgets(screen).remove(popup.title);
            Screens.getWidgets(screen).remove(popup.list);
            popup = null;
            if (hadFocus) {
                restoreInputFocus(screen);
            }
        }

        private void restoreInputFocus(Screen screen) {
            if (input != null) {
                input.setFocused(true);
                screen.setFocused(input);
            }
        }
    }

    private static final class PreviewWidget extends AbstractWidget {
        private final ScreenState state;
        private final ChatInputTranslationController controller;
        private final Screen screen;
        private String language;
        private String provider;
        private Status status;
        private Throwable failure;
        private boolean tooLong;

        private PreviewWidget(int x, int y, int width, int height, ScreenState state,
                              String language, ChatInputTranslationController controller, Screen screen) {
            super(x, y, width, height, Component.empty());
            this.state = state;
            this.language = language;
            this.controller = controller;
            this.screen = screen;
        }

        private int measure(int width, String language, String translation, Status status, Throwable failure,
                            boolean tooLong, boolean canApply, String provider, int maxHeight) {
            Component message = previewMessage(translation, status, failure, tooLong, language, provider);
            Font font = Minecraft.getInstance().font;
            int textHeight = font.split(message, Math.max(1, width - 12)).size() * font.lineHeight;
            int footerHeight = canApply || tooLong ? font.lineHeight : 0;
            return Math.min(maxHeight, Math.max(34, textHeight + footerHeight + 8));
        }

        private void update(int x, int y, int width, int height, String language, String translation,
                            Status status, Throwable failure, boolean tooLong, boolean canApply, String provider) {
            this.language = language;
            this.status = status;
            this.failure = failure;
            this.tooLong = tooLong;
            this.provider = provider;
            setRectangle(x, y, width, height);
            this.active = canApply;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            graphics.fill(getX(), getY(), getRight(), getBottom(), 0xE6101010);
            graphics.fill(getX(), getY(), getRight(), getY() + 1, 0xFF777777);
            Font font = Minecraft.getInstance().font;
            Component message = previewMessage(state.translation.translation(), status, failure,
                tooLong, language, provider);
            List<FormattedCharSequence> lines = font.split(message, Math.max(1, getWidth() - 12));
            int y = getY() + 4;
            int footerHeight = active || tooLong ? font.lineHeight : 0;
            int visibleLines = Math.max(0, (getHeight() - footerHeight - 8) / font.lineHeight);
            for (int index = 0; index < Math.min(lines.size(), visibleLines); index++) {
                graphics.text(font, lines.get(index), getX() + 6, y + index * font.lineHeight,
                    status == Status.FAILED ? 0xFFFF6B6B : 0xFFFFFFFF, false);
            }
            Component footer = tooLong
                ? Component.translatable("chatglot.input.too_long")
                : active ? Component.translatable("chatglot.input.tab_hint") : Component.empty();
            if (!footer.getString().isEmpty()) {
                graphics.text(font, footer, getRight() - font.width(footer) - 6, getBottom() - font.lineHeight - 3,
                    tooLong ? 0xFFFF6B6B : 0xFFAAAAAA, false);
            }
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            controller.applyPreview(Minecraft.getInstance(), screen, state);
        }

        @Override
        protected void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }

        private static Component previewMessage(String translation, Status status, Throwable failure,
                                                boolean tooLong, String language, String provider) {
            return switch (status) {
                case LOADING -> Component.translatable("chatglot.input.loading_preview", language);
                case FAILED -> TranslationErrorMessages.shortReason(failure, provider);
                case READY -> Component.translatable("chatglot.input.preview", language,
                    translation == null ? "" : translation);
                case HIDDEN -> Component.empty();
            };
        }
    }

    private static final class PopupTitleWidget extends AbstractWidget {
        private PopupTitleWidget(int x, int y, int width, int height, Component label) {
            super(x, y, width, height, label);
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            graphics.text(Minecraft.getInstance().font, getMessage(), getX(), getY(), 0xFFAAAAAA);
        }

        @Override
        protected void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    private final class LanguageList extends ObjectSelectionList<LanguageEntry> {
        private LanguageList(Minecraft client, int x, int y, int width, int height, ScreenState state,
                             List<MinecraftLanguageCatalog.Option> options) {
            super(client, width, height, y, 20);
            setX(x);
            String selectedCode = ChatglotRuntime.get().configManager().get().outgoingTargetLanguage;
            LanguageEntry exactMatch = null;
            LanguageEntry fallbackMatch = null;
            for (MinecraftLanguageCatalog.Option option : options) {
                LanguageEntry entry = new LanguageEntry(this, option, state);
                addEntry(entry);
                if (option.code().replace('-', '_').equalsIgnoreCase(selectedCode.replace('-', '_'))) {
                    exactMatch = entry;
                } else if (fallbackMatch == null
                    && LanguageUtil.normalizeTargetLanguage(option.code())
                        .equals(LanguageUtil.normalizeTargetLanguage(selectedCode))) {
                    fallbackMatch = entry;
                }
            }
            setSelected(exactMatch != null ? exactMatch : fallbackMatch);
        }

        private void moveSelection(int amount) {
            List<LanguageEntry> options = children();
            if (options.isEmpty()) {
                return;
            }
            int current = options.indexOf(getSelected());
            int next = Math.max(0, Math.min(options.size() - 1,
                (current < 0 ? 0 : current) + amount));
            moveSelectionTo(next);
        }

        private void moveSelectionTo(int index) {
            List<LanguageEntry> options = children();
            if (options.isEmpty()) {
                return;
            }
            LanguageEntry selected = options.get(Math.max(0, Math.min(options.size() - 1, index)));
            setSelected(selected);
            scrollToEntry(selected);
        }
    }

    private final class LanguageEntry extends ObjectSelectionList.Entry<LanguageEntry> {
        private final LanguageList list;
        private final MinecraftLanguageCatalog.Option option;
        private final ScreenState state;

        private LanguageEntry(LanguageList list, MinecraftLanguageCatalog.Option option, ScreenState state) {
            this.list = list;
            this.option = option;
            this.state = state;
        }

        @Override
        public Component getNarration() {
            return Component.literal(option.label());
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                   boolean hovered, float delta) {
            graphics.text(Minecraft.getInstance().font, getNarration(), getContentX(), getContentY(),
                hovered ? 0xFFFFFF55 : 0xFFFFFFFF, true);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if (event.button() == 0) {
                list.setSelected(this);
                selectLanguage(Minecraft.getInstance().gui.screen(), state, option);
                return true;
            }
            return false;
        }
    }

    private static final class LanguagePopup {
        private final int x;
        private final int top;
        private final int width;
        private final int bottom;
        private final LanguageList list;
        private final List<Button> recentButtons = new ArrayList<>();
        private PopupTitleWidget title;

        private LanguagePopup(int x, int top, int width, int bottom, LanguageList list) {
            this.x = x;
            this.top = top;
            this.width = width;
            this.bottom = bottom;
            this.list = list;
        }

        private boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + width && mouseY >= top && mouseY < bottom;
        }
    }
}
