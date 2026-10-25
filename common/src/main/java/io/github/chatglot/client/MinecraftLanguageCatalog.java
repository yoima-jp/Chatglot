package io.github.chatglot.client;

import com.google.gson.stream.JsonReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.LanguageInfo;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Reads the language choices shared by settings and the chat input language picker. */
public final class MinecraftLanguageCatalog {
    private static final Logger LOGGER = LoggerFactory.getLogger("Chatglot/LanguageCatalog");

    private MinecraftLanguageCatalog() { }

    public record Option(String code, String label) { }

    public static List<Option> collect(Minecraft client) {
        if (client == null || client.getLanguageManager() == null) {
            return List.of(new Option("en_us", "English (US)"));
        }
        List<Option> result = new ArrayList<>();
        for (Map.Entry<String, LanguageInfo> entry : client.getLanguageManager().getLanguages().entrySet()) {
            result.add(new Option(entry.getKey(), entry.getValue().toComponent().getString()));
        }
        // Minecraft may expose only en_us through LanguageManager while the downloaded assets
        // already contain the complete language catalog.
        if (result.size() <= 1) {
            Map<Identifier, Resource> resources = client.getResourceManager().listResources(
                "lang", id -> id.getNamespace().equals("minecraft") && id.getPath().endsWith(".json")
            );
            for (Map.Entry<Identifier, Resource> entry : resources.entrySet()) {
                String path = entry.getKey().getPath();
                String code = path.substring("lang/".length(), path.length() - ".json".length());
                readLanguageLabel(entry.getValue()).ifPresent(label -> {
                    if (find(result, code) == null) {
                        result.add(new Option(code, label));
                    }
                });
            }
            LOGGER.info("Discovered {} Minecraft language resources as a fallback.", result.size());
        }
        result.sort((left, right) -> String.CASE_INSENSITIVE_ORDER.compare(left.label(), right.label()));
        if (result.isEmpty()) {
            result.add(new Option("en_us", "English (US)"));
        }
        return List.copyOf(result);
    }

    private static Option find(List<Option> options, String code) {
        String normalized = normalize(code);
        return options.stream().filter(option -> normalize(option.code()).equals(normalized)).findFirst().orElse(null);
    }

    private static String normalize(String code) {
        return code.toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private static Optional<String> readLanguageLabel(Resource resource) {
        String name = null;
        String region = null;
        try (JsonReader reader = new JsonReader(resource.openAsReader())) {
            reader.beginObject();
            while (reader.hasNext() && (name == null || region == null)) {
                String key = reader.nextName();
                if ("language.name".equals(key)) {
                    name = reader.nextString();
                } else if ("language.region".equals(key)) {
                    region = reader.nextString();
                } else {
                    reader.skipValue();
                }
            }
        } catch (IOException | IllegalStateException exception) {
            LOGGER.warn("Could not read Minecraft language metadata from {}.", resource.sourcePackId(), exception);
            return Optional.empty();
        }
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(region == null || region.isBlank() ? name : name + " (" + region + ")");
    }
}
