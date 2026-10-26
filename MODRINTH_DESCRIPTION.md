# Chatglot

**Chatglot** is a Fabric client-side mod that translates Minecraft chat messages on the fly.  
A translate button (`✍` by default) is added to each chat message. Click it, or let auto-translate handle it, and the translated result is shown in chat either as a new line or by replacing the original message.

Client-side only. No server installation is required.

---

## Features

- Translate button appended to every chat message (configurable label)
- Click-to-translate or automatic translation
- Free Google Translate web translation by default; no API key or setup required
- Persistent local cache to reuse successful translations
- Result shown as a new line **or** replaces the original chat message
- Open the settings screen with `F8` or `/chatglot config`
- Translate a specific message with `/chatglot translate <id>`
- Run `/chatglot diagnose` to send `Hello` to the selected provider and show the translation time and result. TranslateGemma's backend status is shown before the test.
- Translation errors show a short reason. Hover for provider and diagnostic details, open settings from supported errors, and see when repeated failures temporarily pause automatic translation.
- Aim at a sign or hanging sign and press `G` (rebindable) to translate its visible face in a lower-screen panel. Looking away dismisses it after 0.8 seconds; the sign itself is unchanged.
- Sign translation is manual and uses the selected provider. External providers receive the visible face's text; repeated translations reuse a cache for the current world session.
- In a written book or lectern, press `G` or `Translate` to translate the current page only. The button sits below the vanilla menu row; changing pages never sends a request automatically. The button can be hidden in settings while `G` remains available.
- In a writable book, use `Translate` to translate the current page. `G` remains available for typing, and the editor is disabled while translated text is shown. Translation never changes the book's page contents; hiding the shared button option disables translation for writable books.

---

## Translation Providers

Chatglot supports multiple translation backends. Switch between them in the settings screen.

| Provider | Requires |
|---|---|
| `default` | Nothing. Google's unofficial free web API, not paid Cloud Translation; no key or setup required, but it may be limited or changed without notice. |
| `gas` | Your own Google Apps Script web app URL |
| `deepl` | DeepL API key |
| `google` | Google Cloud Translation API key |
| `openai` | OpenAI API key |
| `gemini` | Gemini API key |
| `anthropic` | Anthropic API key |
| `azure` | Azure Translator API key + region |
| `codex` | Browser OAuth (one-time, token stored locally) |

---

## Recommended Providers

- **`gas`**: Good if you want a free setup with your own Google account. Google Apps Script documents up to `5,000` Translate calls per day for consumer accounts, though quotas can change. See [Apps Script quotas](https://developers.google.com/apps-script/guides/services/quotas).
- **`codex`**: Recommended if you already subscribe to ChatGPT. OpenAI states that Codex is included with ChatGPT Plus, Pro, Business, Edu, and Enterprise plans, with plan-based limits. See [Using Codex with your ChatGPT plan](https://help.openai.com/en/articles/11369540-using-codex-with-your-chatgpt-plan).
- **`azure`**: A good option if you want an official API with a free tier. Azure Translator `F0` includes `2 million` characters free per month according to Microsoft's pricing page. See [Azure Translator pricing](https://azure.microsoft.com/pricing/details/cognitive-services/translator/).

---

## Requirements

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
- `translationCacheEnabled` — Enable the local persistent translation cache (default: `true`)
- `translationCacheMaxEntries` — Maximum LRU entries (default: `5000`)

Successful results stay cached until LRU eviction or deletion. The cache is stored in `config/chatglot/translation-cache.json`; delete it with `/chatglot cache clear` or the settings button. It stores original text, including chat messages, in plain text. Cache keys omit API keys and other credentials.

---

## Privacy Notice — External Data Transmission

**This mod sends chat text to external translation services.** The destination depends on the provider you select.

Running `/chatglot diagnose` sends the fixed test text `Hello` to the selected provider.

Manually translating a sign sends its visible text to the selected provider. Manually translating a page in a written, lectern, or writable book sends only that page's text to the selected provider. Translating a writable book never changes its page contents. Looking at a sign or opening a book alone does not send a request. The local TranslateGemma option keeps sign and book text local.

- **`default` provider**: Chat text is sent to Google's unofficial web translation API. It is free and needs no user setup, but access may be limited or changed without notice; switch providers if it becomes unavailable.
- **`gas` provider**: Chat text is sent to the Google Apps Script web app URL that you configure. Requests are processed through Google's infrastructure. See [Google Apps Script Additional Terms](https://developers.google.com/apps-script/terms) and [Google API Services User Data Policy](https://developers.google.com/terms/api-services-user-data-policy).
- **`openai` provider**: Chat text is sent to the OpenAI API using your API key. See [OpenAI data controls](https://platform.openai.com/docs/guides/your-data/).
- **`codex` provider**: Uses browser-based OAuth, stores the token locally, and then uses that token to send chat text to OpenAI services. The mod also fetches the available model list from [`https://modelapi.yoima.com/api/codex-models/list`](https://modelapi.yoima.com/api/codex-models/list). No chat content is sent to that model-list endpoint.
- **`gemini` provider**: Chat text is sent to the Gemini API. For Google AI Studio / Gemini API usage, review the provider terms here: [Gemini API Terms](https://ai.google.dev/gemini-api/terms).
- **`deepl` provider**: Chat text is sent to the DeepL API. See [DeepL Privacy Policy](https://www.deepl.com/en/privacy.html).
- **`anthropic` provider**: Chat text is sent to Anthropic services using your API key. See [Anthropic Privacy Policy](https://www.anthropic.com/legal/privacy).
- **`azure` provider**: Chat text is sent to Azure Translator using your API key. See [Data, privacy, and security for Azure Translator](https://learn.microsoft.com/en-us/legal/cognitive-services/translator/data-privacy-security).
- **`google` provider**: Chat text is sent to Google Cloud Translation using your API key. See [Google Cloud Privacy Notice](https://cloud.google.com/terms/cloud-privacy-notice).

For providers that require your own API key or account, data handling is governed by your agreement with that provider.

---


## Source & License

Source code: [GitHub — yoima-jp/Chatglot](https://github.com/yoima-jp/Chatglot)  
License: MIT
