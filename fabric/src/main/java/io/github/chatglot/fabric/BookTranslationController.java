package io.github.chatglot.fabric;

import io.github.chatglot.ChatglotRuntime;
import io.github.chatglot.client.BookTranslationState;
import io.github.chatglot.client.BookTranslationState.Key;
import io.github.chatglot.client.BookTranslationState.Request;
import io.github.chatglot.client.BookTranslationState.Status;
import io.github.chatglot.config.ChatglotConfig;
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
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

public final class BookTranslationController {
    private static final int PAGE_TEXT_WIDTH = 114;
    private static final int PAGE_TEXT_HEIGHT = 128;
    private static final float MIN_TEXT_SCALE = 0.65f;
    private static final int BUTTON_WIDTH = 96;
    private static BookTranslationController instance;

    private final KeyMapping translateKey;
    private final Map<BookViewScreen, ScreenState> screens = new WeakHashMap<>();

    private BookTranslationController(KeyMapping translateKey) {
        this.translateKey = translateKey;
    }

    static void register(KeyMapping translateKey) {
        BookTranslationController controller = new BookTranslationController(translateKey);
        instance = controller;
        ScreenEvents.AFTER_INIT.register(controller::afterInit);
    }

    private void afterInit(Minecraft client, Screen screen, int scaledWidth, int scaledHeight) {
        if (!(screen instanceof BookViewScreen book)) {
            return;
        }

        ChatglotConfig config = ChatglotRuntime.get().configManager().get();
        ScreenState state = screens.get(book);
        if (state == null) {
            state = new ScreenState(client.level, config);
            screens.put(book, state);
            registerScreenEvents(book, state);
        } else {
            refreshContext(client, book, state);
        }
        addTranslateButton(book, state);
        updateButton(client, book, state);
    }

    private void registerScreenEvents(BookViewScreen screen, ScreenState state) {
        ScreenEvents.afterTick(screen).register(ignored -> tick(screen, state));
        ScreenEvents.afterExtract(screen).register((ignored, graphics, mouseX, mouseY, delta) ->
            renderPage(screen, state, graphics));
        ScreenKeyboardEvents.beforeKeyPress(screen).register((ignored, event) -> {
            if (translateKey.matches(event)) {
                activate(Minecraft.getInstance(), screen, state);
            }
        });
        ScreenEvents.remove(screen).register(ignored -> remove(screen, state));
    }

    private void addTranslateButton(BookViewScreen screen, ScreenState state) {
        BookViewScreenAccessor access = (BookViewScreenAccessor) screen;
        int x = (screen.width - BUTTON_WIDTH) / 2;
        // The final 20 pixels of the book image are below the page text and page-turn
        // controls, leaving the existing Done and Take Book controls in their vanilla row.
        int y = access.chatglot$menuControlsTop() - 24;
        Button button = Button.builder(Component.translatable("chatglot.book.translate"),
                ignored -> activate(Minecraft.getInstance(), screen, state))
            .pos(x, y)
            .width(BUTTON_WIDTH)
            .build();
        Screens.getWidgets(screen).add(button);
        state.button = button;
    }

    private void tick(BookViewScreen screen, ScreenState state) {
        Minecraft client = Minecraft.getInstance();
        refreshContext(client, screen, state);
        state.translation.observe(currentKey(screen, state.settings));
        updateButton(client, screen, state);
        updateNarration(client, screen, state);
    }

    private void refreshContext(Minecraft client, BookViewScreen screen, ScreenState state) {
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

    private void updateButton(Minecraft client, BookViewScreen screen, ScreenState state) {
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

    private void activate(Minecraft client, BookViewScreen screen, ScreenState state) {
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
        updateButton(client, screen, state);
        updateNarration(client, screen, state);
        if (request != null) {
            translate(client, screen, state, request);
        }
    }

    private void translate(Minecraft client, BookViewScreen screen, ScreenState screenState, Request request) {
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
                updateButton(client, screen, screenState);
                updateNarration(client, screen, screenState);
            }));
        } catch (RuntimeException error) {
            ChatglotFabric.LOGGER.warn("Could not start book page translation", error);
            requestedState.complete(request, null, error);
            updateButton(client, screen, screenState);
        }
    }

    private boolean isCurrentRequest(Minecraft client, BookViewScreen screen, ScreenState screenState,
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

    private boolean isScreenReady(Minecraft client, BookViewScreen screen, ScreenState state) {
        return screens.get(screen) == state && client.level != null && client.gui.screen() == screen
            && ChatglotRuntime.get().configManager().get() == state.settings && state.settings.enabled;
    }

    private void renderPage(BookViewScreen screen, ScreenState state, GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        if (!isScreenReady(client, screen, state)) {
            return;
        }

        Key key = currentKey(screen, state.settings);
        state.translation.observe(key);
        BookViewScreenAccessor access = (BookViewScreenAccessor) screen;
        int left = access.chatglot$backgroundLeft();
        int top = access.chatglot$backgroundTop();
        String translated = state.translation.displayedTranslation(key);
        if (translated != null) {
            drawTranslation(client.font, graphics, left + 36, top + 30, translated);
            return;
        }

        Component message = switch (state.translation.status(key)) {
            case FAILED -> Component.translatable("chatglot.book.failed");
            case BUSY -> Component.translatable("chatglot.book.busy");
            default -> null;
        };
        if (message != null) {
            // Keep errors short and on the book page; provider details belong in logs and
            // the next diagnostics UI, so the book itself remains uncluttered.
            graphics.text(client.font, message, left + 36, top + 16, 0xFF8B1A1A, false);
        }
    }

    private static void drawTranslation(Font font, GuiGraphicsExtractor graphics, int x, int y, String text) {
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
            graphics.text(font, lines.get(index), x, y + index * font.lineHeight, 0xFF000000, false);
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

    private void updateNarration(Minecraft client, BookViewScreen screen, ScreenState state) {
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

    private Key currentKey(BookViewScreen screen, ChatglotConfig config) {
        if (!config.enabled) {
            return null;
        }
        BookViewScreen.BookAccess book = ((BookViewScreenAccessor) screen).chatglot$getBookAccess();
        int pageIndex = ((BookViewScreenAccessor) screen).chatglot$getCurrentPage();
        if (pageIndex < 0 || pageIndex >= book.getPageCount()) {
            return null;
        }
        String text = book.getPage(pageIndex).getString().strip();
        if (text.isBlank() || text.length() > 4096) {
            return null;
        }
        return new Key(text, LanguageUtil.resolveConfiguredTargetLanguage(config.targetLanguage), config.provider);
    }

    private void remove(BookViewScreen screen, ScreenState state) {
        if (screens.remove(screen, state)) {
            state.translation.invalidate();
            state.narratedKey = null;
            state.narratedText = null;
            state.button = null;
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

    private static final class ScreenState {
        private BookTranslationState translation = new BookTranslationState();
        private ClientLevel level;
        private ChatglotConfig settings;
        private Button button;
        private Key narratedKey;
        private String narratedText;

        private ScreenState(ClientLevel level, ChatglotConfig settings) {
            this.level = level;
            this.settings = settings;
        }
    }
}
