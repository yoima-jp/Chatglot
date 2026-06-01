# Roadmap

Chatglot's main goal is to keep practical Minecraft chat translation available for Fabric users with minimal setup friction. The roadmap is intentionally focused on maintenance work that helps real players stay compatible with current Minecraft versions and communicate across languages.

## Current Priority

- Publish Fabric builds for the latest stable Minecraft Java version as quickly as possible after the Fabric toolchain and required dependencies are ready.
- Keep the current Minecraft 26.1.x support line working while tracking new stable Minecraft releases.
- Maintain clear Modrinth releases so users can find the correct build for their Minecraft version.
- Improve provider reliability, error messages, and diagnostics for translation failures.

## Near Term

- Refine compatibility updates for Minecraft, Fabric Loader, Fabric API, Cloth Config, and ModMenu.
- Improve setup and recovery guidance for providers that require API keys or external services.
- Continue improving the local TranslateGemma backend setup, health checks, logs, and failure messages.
- Keep privacy and data-transmission notes accurate for every provider that can receive chat text.

## Future Translation Targets

Chatglot currently focuses on chat messages. Future versions may expand translation support to other player-facing text surfaces, including:

- Signs and hanging signs
- Written books and book pages
- Other in-game text surfaces where translation can be added without server-side installation

These features need careful UX design because they may involve longer text, formatting, interaction timing, and different Minecraft UI screens than chat messages.

## Longer Term

- Add focused regression coverage for translation request handling and provider fallback behavior.
- Improve mixed-language UX for multiplayer servers.
- Explore safer defaults for external translation providers.
- Document provider setup examples for common player use cases.

## Maintenance Policy

Chatglot prioritizes current Minecraft/Fabric compatibility, low-friction client-side installation, provider choice, privacy transparency, and practical multilingual play.
