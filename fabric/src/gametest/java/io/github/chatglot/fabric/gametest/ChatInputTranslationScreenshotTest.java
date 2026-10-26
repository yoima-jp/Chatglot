package io.github.chatglot.fabric.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.chatglot.ChatglotRuntime;
import io.github.chatglot.config.ChatglotConfig;
import io.github.chatglot.fabric.mixin.ChatScreenAccessor;
import io.github.chatglot.translation.TranslationException;
import io.github.chatglot.translation.TranslationProvider;
import io.github.chatglot.translation.TranslationRequest;
import io.github.chatglot.translation.TranslationResult;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.contents.TranslatableContents;

@SuppressWarnings("UnstableApiUsage")
public final class ChatInputTranslationScreenshotTest implements FabricClientGameTest {
    private static final String VILLAGE_INPUT = "村はどこ？";

    @Override
    public void runTest(ClientGameTestContext context) {
        FakeProvider.reset();
        configureClient(context);
        configureFakeProvider();

        try {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            prepareWorld(context, singleplayer);
            ChatScreen chat = openChat(context);
            type(context, VILLAGE_INPUT);
            context.takeScreenshot("01-idle");

            clickButton(context, chat, "chatglot.input.translate");
            context.waitFor(client -> FakeProvider.firstRequestStarted());
            try {
                context.takeScreenshot("02-loading");
            } finally {
                FakeProvider.releaseFirstRequest();
            }
            waitForTranslation(context, VILLAGE_INPUT);
            context.takeScreenshot("03-preview");

            context.getInput().pressKey(options -> options.keyPlayerList);
            context.waitTicks(2);
            ChatScreen translatedChat = chat;
            boolean applied = context.computeOnClient(client ->
                "Where is the village?".equals(((ChatScreenAccessor) translatedChat).chatglot$getInput().getValue()));
            context.takeScreenshot(applied ? "04-applied" : "04-tab-not-applied");

            clickButton(context, chat, "chatglot.input.language_button");
            context.waitTicks(2);
            context.getInput().setCursorPos(0, 0);
            context.takeScreenshot("05-language-popup");
        }

        FakeProvider.resetResult();
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            prepareWorld(context, singleplayer);
            ChatScreen chat = openChat(context);
            type(context, FakeProvider.ERROR_INPUT);
            clickButton(context, chat, "chatglot.input.translate");
            waitForTranslation(context, FakeProvider.ERROR_INPUT);
            context.takeScreenshot("06-error");
        }

        FakeProvider.resetResult();
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            prepareWorld(context, singleplayer);
            ChatScreen chat = openChat(context);
            type(context, FakeProvider.LONG_INPUT);
            clickButton(context, chat, "chatglot.input.translate");
            waitForTranslation(context, FakeProvider.LONG_INPUT);
            context.takeScreenshot("07-too-long");
        }

        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            prepareWorld(context, singleplayer);
            ChatScreen chat = openChat(context);
            type(context, "/tp @s 0 64 0");
            context.waitTicks(2);
            Button translateButton = findButton(context, chat, "chatglot.input.translate");
            boolean active = context.computeOnClient(client -> translateButton.active);
            if (active) {
                throw new IllegalStateException("The translate button should be disabled for commands.");
            }
            moveToButton(context, translateButton);
            context.waitTicks(20);
            context.takeScreenshot("08-command");
        }
        } finally {
            FakeProvider.releaseFirstRequest();
        }
    }

    private static void prepareWorld(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        singleplayer.getConnection().waitForChunksRender();
        context.getInput().lookAt(0, -80);
        context.waitTicks(2);
    }

    static void configureClient(ClientGameTestContext context) {
        context.getInput().resizeWindow(854, 480);
        context.waitTicks(2);
        var languageReload = context.computeOnClient(client -> {
            client.options.guiScale().set(2);
            client.options.languageCode = "ja_jp";
            client.getLanguageManager().setSelected("ja_jp");
            client.options.save();
            return client.reloadResourcePacks();
        });
        context.waitFor(client -> languageReload.isDone(), 1200);
        languageReload.join();
        context.waitTicks(2);
    }

    static void configureFakeProvider() {
        ChatglotRuntime runtime = ChatglotRuntime.get();
        // Replacing gas in the gametest runtime gives fixed results without sending any network requests.
        runtime.providerRegistry().register(new FakeProvider());
        ChatglotConfig config = runtime.configManager().get();
        config.enabled = true;
        config.provider = FakeProvider.ID;
        config.outgoingTargetLanguage = "en_us";
        config.recentOutgoingLanguages = new ArrayList<>(List.of("en_us", "fr_fr", "de_de"));
        runtime.configManager().save();
    }

    static ChatScreen openChat(ClientGameTestContext context) {
        context.getInput().pressKey(options -> options.keyChat);
        context.waitForScreen(ChatScreen.class);
        context.waitTicks(2);
        return context.computeOnClient(client -> (ChatScreen) client.gui.screen());
    }

    static void type(ClientGameTestContext context, String text) {
        context.getInput().typeChars(text);
        context.waitTicks(2);
    }

    static void waitForTranslation(ClientGameTestContext context, String input) {
        context.waitFor(client -> FakeProvider.completedInput().equals(input));
        context.waitTicks(3);
    }

    static void clickButton(ClientGameTestContext context, ChatScreen screen, String translationKey) {
        Button button = findButton(context, screen, translationKey);
        boolean active = context.computeOnClient(client -> button.active && button.visible);
        if (!active) {
            throw new IllegalStateException("Chat control " + translationKey + " is not active.");
        }
        moveToButton(context, button);
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTicks(2);
        boolean actionCompleted = translationKey.equals("chatglot.input.translate")
            ? FakeProvider.startedInput().equals(context.computeOnClient(client ->
                ((ChatScreenAccessor) screen).chatglot$getInput().getValue()))
            : languagePopupOpen(context, screen);
        if (!actionCompleted) {
            // The API helper dispatches the same widget activation when the virtual SDL click is dropped.
            String label = context.computeOnClient(client -> button.getMessage().getString());
            context.clickScreenButton(translationKey.equals("chatglot.input.translate") ? translationKey : label);
        }
        context.getInput().setCursorPos(0, 0);
    }

    private static boolean languagePopupOpen(ClientGameTestContext context, ChatScreen screen) {
        return context.computeOnClient(client -> Screens.getWidgets(screen).stream()
            .anyMatch(widget -> widget.getMessage().getContents() instanceof TranslatableContents contents
                && contents.getKey().equals("chatglot.input.recent")));
    }

    static Button findButton(ClientGameTestContext context, ChatScreen screen, String translationKey) {
        return context.computeOnClient(client -> Screens.getWidgets(screen).stream()
            .filter(Button.class::isInstance)
            .map(Button.class::cast)
            .filter(button -> button.getMessage().getContents() instanceof TranslatableContents contents
                && contents.getKey().equals(translationKey))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Could not find chat control " + translationKey + ".")));
    }

    static void moveToButton(ClientGameTestContext context, Button button) {
        double[] position = context.computeOnClient(client -> {
            double scaleX = client.getWindow().getScreenWidth()
                / (double) client.getWindow().getGuiScaledWidth();
            double scaleY = client.getWindow().getScreenHeight()
                / (double) client.getWindow().getGuiScaledHeight();
            return new double[] {
                (button.getX() + button.getWidth() / 2.0) * scaleX,
                (button.getY() + button.getHeight() / 2.0) * scaleY
            };
        });
        context.getInput().setCursorPos(position[0], position[1]);
        double[] cursor = context.computeOnClient(client -> new double[] {
            MouseHandler.getScaledXPos(client.getWindow(), client.mouseHandler.xpos()),
            MouseHandler.getScaledYPos(client.getWindow(), client.mouseHandler.ypos())
        });
        boolean overButton = context.computeOnClient(client -> button.isMouseOver(cursor[0], cursor[1]));
        if (!overButton) {
            throw new IllegalStateException("Mouse missed chat control at " + cursor[0] + "," + cursor[1]
                + " (button at " + button.getX() + "," + button.getY() + ").");
        }
    }

    private static final class FakeProvider implements TranslationProvider {
        private static final String ID = "gas";
        private static final String ERROR_INPUT = "失敗ケース";
        private static final String LONG_INPUT = "長文の例";
        private static final String LONG_TRANSLATION = "This is a long translation. ".repeat(11);
        private static final CountDownLatch FIRST_REQUEST_STARTED = new CountDownLatch(1);
        private static final CountDownLatch RELEASE_FIRST_REQUEST = new CountDownLatch(1);
        private static final AtomicReference<String> STARTED_INPUT = new AtomicReference<>("");
        private static final AtomicReference<String> COMPLETED_INPUT = new AtomicReference<>("");

        private static void reset() {
            STARTED_INPUT.set("");
            COMPLETED_INPUT.set("");
        }

        private static boolean firstRequestStarted() {
            return FIRST_REQUEST_STARTED.getCount() == 0;
        }

        private static void releaseFirstRequest() {
            RELEASE_FIRST_REQUEST.countDown();
        }

        private static String completedInput() {
            return COMPLETED_INPUT.get();
        }

        private static void resetResult() {
            STARTED_INPUT.set("");
            COMPLETED_INPUT.set("");
        }

        private static String startedInput() {
            return STARTED_INPUT.get();
        }

        @Override
        public String id() {
            return ID;
        }

        @Override
        public TranslationResult translate(
            TranslationRequest request,
            ChatglotConfig config,
            java.nio.file.Path configDir,
            java.nio.file.Path gameDir
        ) throws TranslationException {
            STARTED_INPUT.set(request.text());
            try {
                if (VILLAGE_INPUT.equals(request.text())) {
                    FIRST_REQUEST_STARTED.countDown();
                    // Keep the real asynchronous path pending until the loading frame is captured.
                    RELEASE_FIRST_REQUEST.await();
                    return new TranslationResult("Where is the village?", "ja", ID);
                }
                if (ERROR_INPUT.equals(request.text())) {
                    throw TranslationException.classified(
                        TranslationException.Kind.NETWORK, "Screenshot test network failure.", ID
                    );
                }
                if (LONG_INPUT.equals(request.text())) {
                    return new TranslationResult(LONG_TRANSLATION, "ja", ID);
                }
                return new TranslationResult(request.text(), "ja", ID);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw TranslationException.caused(
                    TranslationException.Kind.UNKNOWN, "Screenshot test was interrupted.", ID, exception
                );
            } finally {
                COMPLETED_INPUT.set(request.text());
            }
        }
    }
}
