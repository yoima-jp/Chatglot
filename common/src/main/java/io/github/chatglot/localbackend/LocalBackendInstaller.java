package io.github.chatglot.localbackend;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chatglot.config.ChatglotConfig;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import net.minecraft.text.Text;

public final class LocalBackendInstaller {
    private static final String LLAMA_CPP_RELEASE_API_URL = "https://api.github.com/repos/ggml-org/llama.cpp/releases/latest";
    private static final String WINDOWS_CPU_ASSET_MARKER = "-bin-win-cpu-x64.zip";

    private final HttpClient httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    public void ensureLayout(Path sharedRoot) throws IOException {
        Files.createDirectories(LocalBackendPaths.runtimeDir(sharedRoot));
        Files.createDirectories(LocalBackendPaths.modelsDir(sharedRoot));
        Files.createDirectories(LocalBackendPaths.dataDir(sharedRoot));
        Files.createDirectories(LocalBackendPaths.logsDir(sharedRoot));
    }

    public void ensureRuntimePlaceholder(Path sharedRoot) throws IOException {
        Path runtimeDir = LocalBackendPaths.runtimeDir(sharedRoot);
        Path readme = runtimeDir.resolve("README.txt");
        if (Files.exists(readme)) {
            return;
        }

        Files.writeString(
            readme,
            "Chatglot installs TranslateGemma support via llama.cpp.\n"
                + "Setup from the config screen downloads llama.cpp into this runtime directory and downloads the default GGUF model.\n"
                + "If you want to override the runtime, set 'Backend command path override' in Chatglot settings.\n"
        );
    }

    public Path ensureRuntime(ChatglotConfig config) throws IOException {
        throw new UnsupportedOperationException("Use ensureRuntime(config, sharedRoot, progressListener)");
    }

    public boolean isRuntimeReady(ChatglotConfig config, Path sharedRoot) {
        return resolveInstalledRuntime(config, sharedRoot) != null;
    }

    public Path ensureRuntime(ChatglotConfig config, Path sharedRoot, Consumer<Text> progressListener) throws IOException {
        if (config.localBackendCommand != null && !config.localBackendCommand.isBlank()) {
            Path override = Path.of(config.localBackendCommand.trim());
            if (!Files.exists(override)) {
                throw new IOException("Configured backend command was not found: " + override);
            }
            progressListener.accept(LocalBackendTexts.usingRuntimeOverride(override.toString()));
            return override;
        }

        Path stagedRuntime = findManagedRuntime(sharedRoot);
        if (stagedRuntime != null) {
            progressListener.accept(LocalBackendTexts.usingManagedRuntime(stagedRuntime.toString()));
            return stagedRuntime;
        }

        progressListener.accept(LocalBackendTexts.downloadingManagedRuntime());
        String assetUrl = resolveLatestWindowsCpuAssetUrl();
        Path runtimeDir = LocalBackendPaths.runtimeDir(sharedRoot);
        Path dataDir = LocalBackendPaths.dataDir(sharedRoot);
        Files.createDirectories(runtimeDir);
        Files.createDirectories(dataDir);
        Path archivePath = dataDir.resolve("llama.cpp-windows-x64.zip.part");
        Path finalArchivePath = dataDir.resolve("llama.cpp-windows-x64.zip");
        Files.deleteIfExists(archivePath);
        downloadToFile(assetUrl, archivePath, Duration.ofMinutes(10), "Runtime download", progressListener);

        clearRuntimeDirectory(runtimeDir);
        extractZip(archivePath, runtimeDir);
        Files.move(archivePath, finalArchivePath, StandardCopyOption.REPLACE_EXISTING);

        stagedRuntime = findManagedRuntime(sharedRoot);
        if (stagedRuntime != null) {
            progressListener.accept(LocalBackendTexts.downloadedManagedRuntime(stagedRuntime.toString()));
            return stagedRuntime;
        }
        throw new IOException("Downloaded llama.cpp runtime archive, but llama-server.exe was not found in " + runtimeDir);
    }

    public Path ensureModelDownloaded(ChatglotConfig config, Path sharedRoot) throws IOException {
        return ensureModelDownloaded(config, sharedRoot, message -> {
        });
    }

    public Path ensureModelDownloaded(ChatglotConfig config, Path sharedRoot, Consumer<Text> progressListener) throws IOException {
        Path modelPath = LocalBackendPaths.resolveModelPath(config, sharedRoot);
        if (Files.exists(modelPath) && Files.size(modelPath) > 0) {
            progressListener.accept(LocalBackendTexts.modelAlreadyPresent(modelPath.toString()));
            return modelPath;
        }
        if (config.localModelDownloadUrl == null || config.localModelDownloadUrl.isBlank()) {
            throw new IOException("Model download URL is empty.");
        }

        Files.createDirectories(modelPath.getParent());
        Path tempPath = modelPath.resolveSibling(modelPath.getFileName() + ".part");
        Files.deleteIfExists(tempPath);
        progressListener.accept(LocalBackendTexts.downloadingModel(config.localModelDownloadUrl.trim()));

        downloadToFile(config.localModelDownloadUrl.trim(), tempPath, Duration.ofMinutes(30), "Model download", progressListener);

        try {
            Files.move(tempPath, modelPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) {
            try {
                Files.move(tempPath, modelPath, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveError) {
                if (Files.exists(modelPath) && Files.size(modelPath) > 0) {
                    Files.deleteIfExists(tempPath);
                    progressListener.accept(LocalBackendTexts.modelDownloadFinished(modelPath.toString()));
                    return modelPath;
                }
                throw new IOException("Failed to finalize downloaded model file: " + moveError.getMessage(), moveError);
            }
        }
        progressListener.accept(LocalBackendTexts.modelDownloadFinished(modelPath.toString()));
        return modelPath;
    }

    private String resolveLatestWindowsCpuAssetUrl() throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(LLAMA_CPP_RELEASE_API_URL))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Chatglot")
            .GET()
            .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("llama.cpp release lookup was interrupted.", e);
        }

        if (response.statusCode() >= 400) {
            throw new IOException("Failed to resolve llama.cpp release asset: HTTP " + response.statusCode());
        }

        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonArray assets = root.getAsJsonArray("assets");
        if (assets == null) {
            throw new IOException("llama.cpp release response did not include assets.");
        }

        for (JsonElement assetElement : assets) {
            if (!assetElement.isJsonObject()) {
                continue;
            }
            JsonObject asset = assetElement.getAsJsonObject();
            String name = asset.has("name") ? asset.get("name").getAsString() : "";
            if (!name.endsWith(WINDOWS_CPU_ASSET_MARKER)) {
                continue;
            }
            if (!asset.has("browser_download_url")) {
                continue;
            }
            return asset.get("browser_download_url").getAsString();
        }

        throw new IOException("Could not find a Windows x64 CPU llama.cpp runtime asset in the latest release.");
    }

    private void downloadToFile(
        String url,
        Path destination,
        Duration timeout,
        String progressLabel,
        Consumer<Text> progressListener
    ) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(timeout)
            .header("User-Agent", "Chatglot")
            .GET()
            .build();

        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 400) {
                Files.deleteIfExists(destination);
                throw new IOException(progressLabel + " failed with HTTP " + response.statusCode());
            }
            long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            try (InputStream input = response.body()) {
                writeStreamToFile(input, destination, contentLength, progressLabel, progressListener);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Files.deleteIfExists(destination);
            throw new IOException(progressLabel + " was interrupted.", e);
        }
    }

    private static void writeStreamToFile(
        InputStream input,
        Path destination,
        long contentLength,
        String progressLabel,
        Consumer<Text> progressListener
    ) throws IOException {
        Files.createDirectories(destination.getParent());
        byte[] buffer = new byte[8192];
        long downloaded = 0L;
        int lastPercent = -1;
        Instant lastUpdate = Instant.EPOCH;

        try (var output = Files.newOutputStream(destination)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                output.write(buffer, 0, read);
                downloaded += read;
                if (contentLength > 0) {
                    int percent = (int) Math.min(100, (downloaded * 100) / contentLength);
                    Instant now = Instant.now();
                    if (percent != lastPercent && (percent == 100 || percent / 5 != lastPercent / 5 || Duration.between(lastUpdate, now).toMillis() >= 1000)) {
                        progressListener.accept(
                            LocalBackendTexts.downloadProgress(
                                progressLabel,
                                percent + "%",
                                humanReadableBytes(downloaded),
                                humanReadableBytes(contentLength)
                            )
                        );
                        lastPercent = percent;
                        lastUpdate = now;
                    }
                } else {
                    Instant now = Instant.now();
                    if (Duration.between(lastUpdate, now).toMillis() >= 1000) {
                        progressListener.accept(LocalBackendTexts.downloadProgressUnknown(progressLabel, humanReadableBytes(downloaded)));
                        lastUpdate = now;
                    }
                }
            }
        } catch (IOException e) {
            Files.deleteIfExists(destination);
            throw e;
        }
    }

    private static void clearRuntimeDirectory(Path runtimeDir) throws IOException {
        if (!Files.isDirectory(runtimeDir)) {
            return;
        }

        try (Stream<Path> stream = Files.list(runtimeDir)) {
            for (Path child : stream.toList()) {
                deleteRecursively(child);
            }
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        if (Files.isDirectory(path)) {
            try (Stream<Path> stream = Files.list(path)) {
                for (Path child : stream.toList()) {
                    deleteRecursively(child);
                }
            }
        }
        Files.deleteIfExists(path);
    }

    private static void extractZip(Path archivePath, Path destinationDir) throws IOException {
        try (InputStream inputStream = Files.newInputStream(archivePath); ZipInputStream zip = new ZipInputStream(inputStream)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path output = destinationDir.resolve(entry.getName()).normalize();
                if (!output.startsWith(destinationDir.normalize())) {
                    throw new IOException("Unexpected archive entry outside destination: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                    continue;
                }

                Files.createDirectories(output.getParent());
                Files.copy(zip, output, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    public Path findManagedRuntime(Path sharedRoot) {
        Path runtimeDir = LocalBackendPaths.runtimeDir(sharedRoot);
        if (!Files.isDirectory(runtimeDir)) {
            return null;
        }

        try (Stream<Path> stream = Files.walk(runtimeDir)) {
            return stream
                .filter(Files::isRegularFile)
                .filter(path -> "llama-server.exe".equalsIgnoreCase(path.getFileName().toString()))
                .findFirst()
                .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    public Path resolveInstalledRuntime(ChatglotConfig config, Path sharedRoot) {
        if (config.localBackendCommand != null && !config.localBackendCommand.isBlank()) {
            Path override = Path.of(config.localBackendCommand.trim());
            return Files.exists(override) ? override : null;
        }
        return findManagedRuntime(sharedRoot);
    }

    private static String humanReadableBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = { "KB", "MB", "GB", "TB" };
        int unitIndex = -1;
        while (value >= 1024 && unitIndex + 1 < units.length) {
            value /= 1024.0;
            unitIndex++;
        }
        return String.format(java.util.Locale.ROOT, "%.1f %s", value, units[Math.max(unitIndex, 0)]);
    }
}
