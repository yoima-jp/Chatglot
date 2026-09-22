package io.github.chatglot.util;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Opens external resources from the Minecraft client process. */
public final class ExternalLinkOpener {
    private ExternalLinkOpener() {
    }

    /**
     * Minecraft 26.3 removed the old {@code Util.getPlatform().openUri} helper.
     * Keep browser and file launching in one small boundary so UI callbacks do not
     * each need to know about the host desktop capabilities.
     */
    public static void openUrl(String url) throws IOException {
        final URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid external URL", error);
        }

        openUri(uri, Desktop.Action.BROWSE);
    }

    public static void openFile(Path file) throws IOException {
        openUri(file.toUri(), Desktop.Action.OPEN);
    }

    private static void openUri(URI uri, Desktop.Action action) throws IOException {
        IOException desktopFailure = null;
        try {
            Desktop desktop = getDesktop();
            if (!desktop.isSupported(action)) {
                throw new IOException("The desktop does not support " + action);
            }
            if (action == Desktop.Action.BROWSE) {
                desktop.browse(uri);
            } else {
                desktop.open(Path.of(uri).toFile());
            }
            return;
        } catch (IOException error) {
            desktopFailure = error;
        }

        try {
            openWithPlatformCommand(uri);
        } catch (IOException error) {
            if (desktopFailure != null) {
                error.addSuppressed(desktopFailure);
            }
            throw error;
        }
    }

    private static Desktop getDesktop() throws IOException {
        if (!Desktop.isDesktopSupported()) {
            throw new IOException("Desktop integration is unavailable");
        }
        try {
            return Desktop.getDesktop();
        } catch (SecurityException | UnsupportedOperationException error) {
            throw new IOException("Desktop integration is unavailable", error);
        }
    }

    /**
     * Minecraft can run with AWT desktop integration disabled even on Windows.
     * Use the operating system's registered URI handler in that environment.
     */
    private static void openWithPlatformCommand(URI uri) throws IOException {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<String> command;
        if (osName.contains("win")) {
            command = List.of("rundll32.exe", "url.dll,FileProtocolHandler", uri.toString());
        } else if (osName.contains("mac")) {
            command = List.of("open", uri.toString());
        } else {
            command = List.of("xdg-open", uri.toString());
        }
        new ProcessBuilder(command).start();
    }
}
