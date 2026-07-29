package io.github.chatglot.moddeck;

import net.minecraft.client.gui.screens.Screen;

/**
 * Indirection for opening a ModDeck config screen.
 *
 * <p>ModDeck's {@code com.yoima.moddeck.api.ModDeckApi} lives in its client source
 * set, which is only visible to Chatglot's fabric module. The common module keeps the
 * screen-opening logic behind this interface; the fabric module supplies an
 * implementation via {@link #setOpener} during client initialization.</p>
 */
public final class ChatglotModDeckScreenOpener {
    private ChatglotModDeckScreenOpener() {
    }

    @FunctionalInterface
    public interface Opener {
        void open(String modId, Screen parent);
    }

    private static volatile Opener opener;

    /**
     * Installs the platform-specific screen opener. Called once by the fabric client
     * entry point. Replacing the opener after registration is allowed for tests but
     * should not happen during normal gameplay.
     */
    public static void setOpener(Opener opener) {
        ChatglotModDeckScreenOpener.opener = opener;
    }

    public static void open(String modId, Screen parent) {
        Opener current = opener;
        if (current == null) {
            throw new IllegalStateException("No ChatglotModDeckScreenOpener installed; "
                + "ChatglotFabricClient must initialize the opener on the client side.");
        }
        current.open(modId, parent);
    }
}
