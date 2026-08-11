# Chatglot

[![Modrinth Downloads](https://img.shields.io/modrinth/dt/chatglot?logo=modrinth&label=Modrinth%20downloads)](https://modrinth.com/mod/chatglot)
[![Modrinth Version](https://img.shields.io/modrinth/v/chatglot?logo=modrinth&label=Latest%20Modrinth%20release)](https://modrinth.com/mod/chatglot/versions)
[![License: MIT](https://img.shields.io/github/license/yoima-jp/Chatglot)](LICENSE)
[![Minecraft](https://img.shields.io/badge/Minecraft-26.1.x%20%7C%201.21.x-2ea043)](https://modrinth.com/mod/chatglot/versions)

![Chat translation examples](https://cdn.modrinth.com/data/cached_images/7225369d145f4b9bd970aa38485f37cbfe4782c3.png)
**Chatglot** is a Fabric client-side mod that translates Minecraft chat messages on the fly.  
A translate button (`✍` by default) is added to each chat message. Click it, or let auto-translate handle it, and the translated result is shown in chat either as a new line or by replacing the original message.

Client-side only. No server installation is required.

## Distribution

- [Download on Modrinth](https://modrinth.com/mod/chatglot)
- [Report issues on GitHub](https://github.com/yoima-jp/Chatglot/issues/new/choose)
- [View source on GitHub](https://github.com/yoima-jp/Chatglot)
- [Project roadmap](ROADMAP.md)

---

## Why Chatglot Matters

Minecraft servers often bring together players who do not share a common language. Chatglot helps those communities communicate by making chat translation available as a client-side Fabric mod, so servers do not need to install or trust a server-side plugin.

The project focuses on practical choice and transparency:

- Multiple translation providers so users can choose by cost, privacy, quality, and availability
- A local TranslateGemma backend option for users who prefer local inference
- Clear privacy notes for every provider that can receive chat text
- Compatibility maintenance for current Minecraft and Fabric versions
- Public distribution through Modrinth with source, issue reporting, and MIT licensing

## Features

- Translate button appended to every chat message (configurable label)
- Click-to-translate or automatic translation
- Result shown as a new line **or** replaces the original chat message
- Open the settings screen with `F8` or `/chatglot config`
- Translate a specific message with `/chatglot translate <id>`
![Config GUI](https://cdn.modrinth.com/data/cached_images/166cb33a9ad36001e34402412d0574044f5cdefd.jpeg)
---

## Translation Providers

Chatglot supports multiple translation backends. Switch between them in the settings screen.

| Provider | Requires |
|---|---|
| `default` | Nothing. Uses an author-operated free GAS endpoint. Rate-limited, and auto-translate is unavailable. |
| `gas` | Your own Google Apps Script web app URL |
| `deepl` | DeepL API key |
| `google` | Google Cloud Translation API key |
| `openai` | OpenAI API key |
| `custom_llm` | Custom OpenResponses or Chat Completions API |
| `gemini` | Gemini API key |
| `anthropic` | Anthropic API key |
| `azure` | Azure Translator API key + region |
| `codex` | Browser OAuth (one-time, token stored locally) |
| `translategemma_local` | Windows only. Downloads `llama.cpp` + a TranslateGemma GGUF model, then runs a local backend over `localhost` |

---

## Recommended Providers

- **`GAS`**: Good if you want a free setup with your own Google account. Google Apps Script documents up to `5,000` Translate calls per day for consumer accounts, though quotas can change. See [Apps Script quotas](https://developers.google.com/apps-script/guides/services/quotas).
- **`TranslateGemma`**: A strong option if your PC has enough headroom for local inference. After setup, it runs locally over `localhost`, can be fast, and does not require a paid API for each request. It does require downloading the `llama.cpp` runtime and a TranslateGemma GGUF model.
- **`Codex`**: Recommended if you already subscribe to ChatGPT. OpenAI states that Codex is included with ChatGPT Plus, Pro, Business, Edu, and Enterprise plans, with plan-based limits. See [Using Codex with your ChatGPT plan](https://help.openai.com/en/articles/11369540-using-codex-with-your-chatgpt-plan).
- **`Azure`**: A good option if you want an official API with a free tier. Azure Translator `F0` includes `2 million` characters free per month according to Microsoft's pricing page. See [Azure Translator pricing](https://azure.microsoft.com/pricing/details/cognitive-services/translator/).

---

## Requirements

- Minecraft `26.1.2` (`26.1.x` compatible)
- [Fabric Loader](https://fabricmc.net/)
- [Fabric API](https://modrinth.com/mod/fabric-api)
- [ModDeck](https://modrinth.com/mod/mod-deck) *(required for in-game settings)*
- [ModMenu](https://modrinth.com/mod/modmenu) *(optional, recommended)*

---

## Configuration

The config file is located at `config/chatglot/chatglot.json`.

Key settings:

- `enabled` — Enable or disable the mod entirely
- `autoTranslateEnabled` — Toggle automatic translation
- `targetLanguage` — e.g. `JA`, `EN`, `EN-US`, `ZH-HANS` (defaults to your Minecraft language)
- `provider` — Choose your translation backend
- `overwriteOriginalWithTranslation` — Replace original message instead of adding a new line
- `appendTranslateButton` — Show or hide the `✍` button
- `translateButtonLabel` — Customize the button label
- `showTranslationPrefix` — Show or hide the translated-message prefix
- `requestTimeoutSeconds` — Network timeout (`5`-`240` seconds)
- `maxConcurrentTranslations` — Max parallel translation requests (`1`-`16`)
  For `translategemma_local`, this also sets the managed `llama-server` `--parallel` value.
- `useSharedAppDataSettings` — Share API keys, model settings, and local backend settings through `%LOCALAPPDATA%`

---

## TranslateGemma Local Backend

`translategemma_local` is a Windows-only local translation option. It does not run inference inside the Minecraft JVM. Instead, it uses a separate local backend process over `127.0.0.1` / `localhost`.

When you use the TranslateGemma setup buttons in the config screen, Chatglot:

- Downloads a prebuilt `llama.cpp` Windows runtime
- Downloads a TranslateGemma GGUF model file
- Starts `llama-server.exe` locally and sends translation requests to that local server

The default model is `mradermacher/translategemma-4b-it-GGUF` using `translategemma-4b-it.Q4_K_M.gguf`.

By default, Chatglot stores the local backend runtime, model, logs, and state inside:

- `config/chatglot/local-backend/` when shared AppData settings are disabled
- `%LOCALAPPDATA%/ChatglotLocal/` when shared AppData settings are enabled

You can also override the runtime path, model path, download URL, or shared directory from the config screen.

---

## Shared Settings Storage

If `useSharedAppDataSettings` is enabled, Chatglot stores shared settings in `%LOCALAPPDATA%` so multiple launch profiles can reuse them.

This may include:

- API keys for supported translation providers
- Codex token file location and related model settings
- TranslateGemma local backend settings such as runtime path, model path, port, and shared directory

Shared settings are stored locally on your PC. They are not uploaded anywhere just because this option is enabled.

---

## Privacy Notice — External Data Transmission

**This mod sends chat text to external translation services.** The destination depends on the provider you select.

- **`default` provider**: Chat text is sent to an author-operated Google Apps Script endpoint. However, requests pass through Google's infrastructure. See [Google Apps Script Additional Terms](https://developers.google.com/apps-script/terms) and [Google API Services User Data Policy](https://developers.google.com/terms/api-services-user-data-policy).
- **`gas` provider**: Chat text is sent to the Google Apps Script web app URL that you configure. Requests are processed through Google's infrastructure. See [Google Apps Script Additional Terms](https://developers.google.com/apps-script/terms) and [Google API Services User Data Policy](https://developers.google.com/terms/api-services-user-data-policy).
- **`openai` provider**: Chat text is sent to the OpenAI API using your API key. See [OpenAI data controls](https://platform.openai.com/docs/guides/your-data/).
- **`custom_llm` provider**: Chat text is sent to the custom Base URL that you configure. Select either OpenResponses (`/responses`) or Chat Completions (`/chat/completions`) format. The API key is optional for local servers.
- **`codex` provider**: Uses browser-based OAuth, stores the token locally, and then uses that token to send chat text to OpenAI services. The mod also fetches the available model list from [`https://modelapi.yoima.com/api/codex-models/list`](https://modelapi.yoima.com/api/codex-models/list). No chat content is sent to that model-list endpoint.
- **`gemini` provider**: Chat text is sent to the Gemini API. For Google AI Studio / Gemini API usage, review the provider terms here: [Gemini API Terms](https://ai.google.dev/gemini-api/terms).
- **`deepl` provider**: Chat text is sent to the DeepL API. See [DeepL Privacy Policy](https://www.deepl.com/en/privacy.html).
- **`anthropic` provider**: Chat text is sent to Anthropic services using your API key. See [Anthropic Privacy Policy](https://www.anthropic.com/legal/privacy).
- **`azure` provider**: Chat text is sent to Azure Translator using your API key. See [Data, privacy, and security for Azure Translator](https://learn.microsoft.com/en-us/legal/cognitive-services/translator/data-privacy-security).
- **`google` provider**: Chat text is sent to Google Cloud Translation using your API key. See [Google Cloud Privacy Notice](https://cloud.google.com/terms/cloud-privacy-notice).
- **`translategemma_local` provider**: Chat text is sent only to a local backend running on `127.0.0.1` / `localhost`. During setup, this mod downloads the `llama.cpp` runtime and the TranslateGemma GGUF model from a third-party hosting source configured by the mod.

---


## Source & License

Source code: [GitHub — yoima-jp/Chatglot](https://github.com/yoima-jp/Chatglot)  
License: MIT
