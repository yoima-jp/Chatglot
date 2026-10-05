package io.github.chatglot.fabric;

import io.github.chatglot.ChatglotRuntime;
import io.github.chatglot.client.BookTranslationState;
import io.github.chatglot.client.BookTranslationState.Key;
import io.github.chatglot.client.BookTranslationState.Request;
import io.github.chatglot.client.BookTranslationState.Status;
import io.github.chatglot.client.TranslationErrorMessages;
import io.github.chatglot.config.ChatglotConfig;
import io.github.chatglot.fabric.mixin.BookEditScreenAccessor;
import io.github.chatglot.fabric.mixin.BookViewScreenAccessor;
import io.github.chatglot.translation.LanguageUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

public final class BookTranslationController {
    private static final int PAGE_TEXT_WIDTH = 114;
    private static final int PAGE_TEXT_HEIGHT = 128;
    private static final float MIN_TEXT_SCALE = 0.65f;
    private static final int MENU_ROW_FALLBACK_WIDTH = 200;
    private static final int MENU_ROW_GAP = 4;
    private static BookTranslationController instance;

    private final KeyMapping translateKey;
    private final Map<Screen, ScreenState> screens = new WeakHashMap<>();

    private BookTranslationController(KeyMapping translateKey) {
        this.translateKey = translateKey;
    }

    static void register(KeyMapping translateKey) {
        BookTranslationController controller = new BookTranslationController(translateKey);
        instance = controller;
        ScreenEvents.AFTER_INIT.register(controller::afterInit);
    }

    private void afterInit(Minecraft client, Screen screen, int scaledWidth, int scaledHeight) {
        if (!supportsTranslation(screen)) {
            return;
        }

        ChatglotConfig config = ChatglotRuntime.get().configManager().get();
        ScreenState state = screens.get(screen);
        if (state == null) {
            state = new ScreenState(client.level, config);
            screens.put(screen, state);
            registerScreenEvents(screen, state);
        } else {
            refreshContext(client, screen, state);
        }

        // Screen init rebuilds the vanilla widgets, so recreate our button only when enabled.
        state.button = null;
        if (config.showBookTranslateButton) {
            addTranslateButton(screen, state);
        }
        updateButton(client, screen, state);
        syncEditablePage(screen, state);
    }

    private static boolean supportsTranslation(Screen screen) {
        return screen instanceof BookViewScreen || screen instanceof BookEditScreen;
    }

    private void registerScreenEvents(Screen screen, ScreenState state) {
        ScreenEvents.afterTick(screen).register(ignored -> tick(screen, state));
        ScreenEvents.afterExtract(screen).register((ignored, graphics, mouseX, mouseY, delta) ->
            renderPage(screen, state, graphics));
        if (screen instanceof BookViewScreen) {
            ScreenKeyboardEvents.beforeKeyPress(screen).register((ignored, event) -> {
                if (translateKey.matches(event)) {
                    activate(Minecraft.getInstance(), screen, state);
                }
            });
        }
        // BookEditScreen uses G as ordinary page input; translation there is button-only.
        ScreenEvents.remove(screen).register(ignored -> remove(screen, state));
    }

    private void addTranslateButton(Screen screen, ScreenState state) {
        ButtonRow row = findMenuButtonRow(screen);
        // Match the displayed vanilla row, including the lectern's optional Take Book button,
        // then keep the usual 20-pixel button height and 4-pixel vertical gap.
        int y = Math.min(row.y() + Button.DEFAULT_HEIGHT + MENU_ROW_GAP,
            Math.max(0, screen.height - Button.DEFAULT_HEIGHT));
        Button button = Button.builder(Component.translatable("chatglot.book.translate"),
                ignored -> activate(Minecraft.getInstance(), screen, state))
            .pos(row.x(), y)
            .width(row.width())
            .build();
        Screens.getWidgets(screen).add(button);
        state.button = button;
    }

    private static ButtonRow findMenuButtonRow(Screen screen) {
        int rowY = menuControlsTop(screen);
        int left = Integer.MAX_VALUE;
        int right = Integer.MIN_VALUE;
        for (AbstractWidget widget : Screens.getWidgets(screen)) {
            if (widget instanceof Button button && button.visible && button.getY() == rowY) {
                left = Math.min(left, button.getX());
                right = Math.max(right, button.getX() + button.getWidth());
            }
        }

        if (right <= left) {
            // Vanilla's Done row and BookEditScreen's two-button row are both 200 pixels wide.
            left = (screen.width - MENU_ROW_FALLBACK_WIDTH) / 2;
            right = left + MENU_ROW_FALLBACK_WIDTH;
        }
        return new ButtonRow(left, right - left, rowY);
    }

    private static int menuControlsTop(Screen screen) {
        if (screen instanceof BookViewScreen) {
            return ((BookViewScreenAccessor) screen).chatglot$menuControlsTop();
        }
        return ((BookEditScreenAccessor) screen).chatglot$menuControlsTop();
    }

    private void tick(Screen screen, ScreenState state) {
        Minecraft client = Minecraft.getInstance();
        refreshContext(client, screen, state);
        state.translation.observe(currentKey(screen, state.settings));
        syncEditablePage(screen, state);
        updateButton(client, screen, state);
        updateNarration(client, screen, state);
    }

    private void refreshContext(Minecraft client, Screen screen, ScreenState state) {
        ChatglotConfig config = ChatglotRuntime.get().configManager().get();
        if (client.level != state.level || config != state.settings) {
            state.translation.invalidate();
            state.translation = new BookTranslationState();
            state.level = client.level;
            state.settings = config;
            state.narratedKey = null;
            state.narratedText = null;
        }
    }

    private void updateButton(Minecraft client, Screen screen, ScreenState state) {
        if (state.button == null) {
            return;
        }
        Key key = currentKey(screen, state.settings);
        Status status = state.translation.status(key);
        Component label = status == Status.LOADING
            ? Component.translatable("chatglot.book.loading")
            : state.translation.isTranslationVisible(key)
                ? Component.translatable("chatglot.book.original")
                : Component.translatable("chatglot.book.translate");
        state.button.setMessage(label);
        state.button.active = isScreenReady(client, screen, state) && key != null && status != Status.LOADING;
    }

    private void activate(Minecraft client, Screen screen, ScreenState state) {
        refreshContext(client, screen, state);
        if (!isScreenReady(client, screen, state)) {
            return;
        }
        Key key = currentKey(screen, state.settings);
        state.translation.observe(key);
        if (key == null) {
            updateButton(client, screen, state);
            return;
        }

        Request request = state.translation.activate(key);
        syncEditablePage(screen, state);
        updateButton(client, screen, state);
        updateNarration(client, screen, state);
        if (request != null) {
            translate(client, screen, state, request);
        }
    }

    private void translate(Minecraft client, Screen screen, ScreenState screenState, Request request) {
        BookTranslationState requestedState = screenState.translation;
        ClientLevel requestedLevel = client.level;
        ChatglotConfig requestedSettings = screenState.settings;
        Key key = request.key();
        ChatglotRuntime runtime = ChatglotRuntime.get();
        try {
            CompletableFuture<String> sourceLanguage = "translategemma_local".equalsIgnoreCase(key.provider())
                ? runtime.languageDetectorService().detectLanguageAsync(key.text()).thenApply(value -> value.orElse(""))
                : CompletableFuture.completedFuture("");
            sourceLanguage.thenComposeAsync(source -> {
                if (!isCurrentRequest(client, screen, screenState, requestedState, requestedLevel, requestedSettings, key)) {
                    throw new CancellationException("Book translation context changed");
                }
                return runtime.translationService().translate(key.text(), key.language(), source, false);
            }, client::execute).whenComplete((result, error) -> client.execute(() -> {
                Throwable failure = unwrap(error);
                if (failure != null && !(failure instanceof CancellationException)) {
                    ChatglotFabric.LOGGER.warn("Book page translation failed", failure);
                }
                if (!isCurrentRequest(client, screen, screenState, requestedState, requestedLevel,
                    requestedSettings, key)) {
                    return;
                }

                requestedState.complete(request, result != null ? result.translatedText() : null, failure);
                Throwable storedFailure = requestedState.failure(key);
                if (storedFailure != null && storedFailure != failure) {
                    ChatglotFabric.LOGGER.warn("Book page translation returned an invalid result", storedFailure);
                }
                syncEditablePage(screen, screenState);
                updateButton(client, screen, screenState);
                updateNarration(client, screen, screenState);
            }));
        } catch (RuntimeException error) {
            ChatglotFabric.LOGGER.warn("Could not start book page translation", error);
            requestedState.complete(request, null, error);
            syncEditablePage(screen, screenState);
            updateButton(client, screen, screenState);
        }
    }

    private boolean isCurrentRequest(Minecraft client, Screen screen, ScreenState screenState,
                                     BookTranslationState requestedState, ClientLevel requestedLevel,
                                     ChatglotConfig requestedSettings, Key key) {
        ChatglotRuntime runtime = ChatglotRuntime.get();
        ChatglotConfig currentConfig = runtime.configManager().get();
        return screens.get(screen) == screenState && screenState.translation == requestedState
            && client.level == requestedLevel && client.gui.screen() == screen
            && currentConfig == requestedSettings && currentConfig.enabled
            && key.provider().equals(currentConfig.provider)
            && key.language().equals(LanguageUtil.resolveConfiguredTargetLanguage(currentConfig.targetLanguage));
    }

    private boolean isScreenReady(Minecraft client, Screen screen, ScreenState state) {
        return screens.get(screen) == state && client.level != null && client.gui.screen() == screen
            && ChatglotRuntime.get().configManager().get() == state.settings && state.settings.enabled;
    }

    private void renderPage(Screen screen, ScreenState state, GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        if (!isScreenReady(client, screen, state)) {
            return;
        }

        Key key = currentKey(screen, state.settings);
        state.translation.observe(key);
        syncEditablePage(screen, state);
        int left = backgroundLeft(screen);
        int top = backgroundTop(screen);
        String translated = state.translation.displayedTranslation(key);
        if (translated != null) {
            drawTranslation(client.font, graphics, left + 36, top + 30, translated, 0xFF000000);
            return;
        }

        Component message = switch (state.translation.status(key)) {
            case FAILED -> Component.translatable(
                "chatglot.book.failed",
                TranslationErrorMessages.shortReason(state.translation.failure(key), key.provider())
            );
            case BUSY -> Component.translatable("chatglot.book.busy");
            default -> null;
        };
        if (message != null) {
            drawTranslation(client.font, graphics, left + 36, top + 16, message.getString(), 0xFF8B1A1A);
        }
    }

    private static int backgroundLeft(Screen screen) {
        if (screen instanceof BookViewScreen) {
            return ((BookViewScreenAccessor) screen).chatglot$backgroundLeft();
        }
        return ((BookEditScreenAccessor) screen).chatglot$backgroundLeft();
    }

    private static int backgroundTop(Screen screen) {
        if (screen instanceof BookViewScreen) {
            return ((BookViewScreenAccessor) screen).chatglot$backgroundTop();
        }
        return ((BookEditScreenAccessor) screen).chatglot$backgroundTop();
    }

    private static void drawTranslation(
        Font font,
        GuiGraphicsExtractor graphics,
        int x,
        int y,
        String text,
        int color
    ) {
        List<String> lines = font.split(Component.literal(text), PAGE_TEXT_WIDTH).stream()
            .map(BookTranslationController::plainText)
            .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
        if (lines.isEmpty()) {
            return;
        }

        // Scale the full wrapped page toward the text area's bounds, then reserve the
        // remaining visible lines for a trailing ellipsis if the minimum scale is reached.
        float scale = Math.max(MIN_TEXT_SCALE,
            Math.min(1.0f, PAGE_TEXT_HEIGHT / (float) (lines.size() * font.lineHeight)));
        int visibleLineLimit = Math.max(1, (int) (PAGE_TEXT_HEIGHT / (font.lineHeight * scale)));
        if (lines.size() > visibleLineLimit) {
            lines = new ArrayList<>(lines.subList(0, visibleLineLimit));
            String lastLine = lines.get(visibleLineLimit - 1);
            String ellipsis = "…";
            int lastLineWidth = Math.max(0, PAGE_TEXT_WIDTH - font.width(ellipsis));
            lines.set(visibleLineLimit - 1, font.plainSubstrByWidth(lastLine, lastLineWidth) + ellipsis);
        }

        graphics.pose().pushMatrix();
        graphics.pose().translate(x * (1.0f - scale), y * (1.0f - scale));
        graphics.pose().scale(scale, scale);
        for (int index = 0; index < lines.size(); index++) {
            graphics.text(font, lines.get(index), x, y + index * font.lineHeight, color, false);
        }
        graphics.pose().popMatrix();
    }

    private static String plainText(FormattedCharSequence line) {
        StringBuilder text = new StringBuilder();
        line.accept((index, style, codePoint) -> {
            text.appendCodePoint(codePoint);
            return true;
        });
        return text.toString();
    }

    private void syncEditablePage(Screen screen, ScreenState state) {
        if (!(screen instanceof BookEditScreen)) {
            return;
        }

        MultiLineEditBox page = ((BookEditScreenAccessor) screen).chatglot$getPage();
        Key key = currentKey(screen, state.settings);
        boolean translated = state.translation.isTranslationVisible(key)
            && isScreenReady(Minecraft.getInstance(), screen, state);
        if (translated) {
            // The vanilla value listener writes every edit into pages, which saveChanges sends
            // to both the local ItemStack and the server. Hiding and disabling the widget keeps
            // translated text strictly presentational and prevents typing from reaching it.
            page.visible = false;
            page.active = false;
            if (screen.getFocused() == page) {
                screen.clearFocus();
            }
            state.editablePageSuppressed = true;
        } else if (state.editablePageSuppressed) {
            page.visible = true;
            page.active = true;
            screen.setFocused(page);
            state.editablePageSuppressed = false;
        } else {
            page.visible = true;
            page.active = true;
        }
    }

    private void updateNarration(Minecraft client, Screen screen, ScreenState state) {
        if (!isScreenReady(client, screen, state)) {
            return;
        }
        Key key = currentKey(screen, state.settings);
        String translated = state.translation.displayedTranslation(key);
        if (translated == null) {
            state.narratedKey = null;
            state.narratedText = null;
            return;
        }
        if (!key.equals(state.narratedKey) || !translated.equals(state.narratedText)) {
            client.getNarrator().saySystemNow(Component.literal(translated));
            state.narratedKey = key;
            state.narratedText = translated;
        }
    }

    private Key currentKey(Screen screen, ChatglotConfig config) {
        if (!config.enabled) {
            return null;
        }

        String text;
        if (screen instanceof BookViewScreen bookScreen) {
            BookViewScreen.BookAccess book = ((BookViewScreenAccessor) bookScreen).chatglot$getBookAccess();
            int pageIndex = ((BookViewScreenAccessor) bookScreen).chatglot$getCurrentPage();
            if (pageIndex < 0 || pageIndex >= book.getPageCount()) {
                return null;
            }
            text = book.getPage(pageIndex).getString();
        } else if (screen instanceof BookEditScreen editScreen) {
            BookEditScreenAccessor access = (BookEditScreenAccessor) editScreen;
            List<String> pages = access.chatglot$getPages();
            int pageIndex = access.chatglot$getCurrentPage();
            if (pageIndex < 0 || pageIndex >= pages.size()) {
                return null;
            }
            // BookEditScreen's value listener keeps this list current before any save action.
            text = pages.get(pageIndex);
        } else {
            return null;
        }

        text = text.strip();
        if (text.isBlank() || text.length() > 4096) {
            return null;
        }
        return new Key(text, LanguageUtil.resolveConfiguredTargetLanguage(config.targetLanguage), config.provider);
    }

    private void remove(Screen screen, ScreenState state) {
        if (screens.remove(screen, state)) {
            state.translation.invalidate();
            state.narratedKey = null;
            state.narratedText = null;
            state.button = null;
            state.editablePageSuppressed = false;
        }
    }

    private static Throwable unwrap(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) {
            error = error.getCause();
        }
        return error;
    }

    public static boolean isTranslationVisible(BookViewScreen screen) {
        BookTranslationController controller = instance;
        if (controller == null) {
            return false;
        }
        ScreenState state = controller.screens.get(screen);
        if (state == null) {
            return false;
        }
        Key key = controller.currentKey(screen, state.settings);
        state.translation.observe(key);
        return state.translation.isTranslationVisible(key)
            && controller.isScreenReady(Minecraft.getInstance(), screen, state);
    }

    private record ButtonRow(int x, int width, int y) { }

    private static final class ScreenState {
        private BookTranslationState translation = new BookTranslationState();
        private ClientLevel level;
        private ChatglotConfig settings;
        private Button button;
        private Key narratedKey;
        private String narratedText;
        private boolean editablePageSuppressed;

        private ScreenState(ClientLevel level, ChatglotConfig settings) {
            this.level = level;
            this.settings = settings;
        }
    }
}
