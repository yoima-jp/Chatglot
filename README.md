# Chatglot

Chatglot は Minecraft チャットを翻訳する Fabric クライアント MOD です。  
`common + fabric` のマルチモジュール構成で、複数の翻訳プロバイダを切り替えて利用できます。

## 主な機能

- チャット末尾に翻訳ボタンを付与（既定ラベル: `✍`）
- ボタンクリックまたはコマンドで個別翻訳
- 自動翻訳（Lingua による言語判定）
- 翻訳結果を新規行表示、または原文チャットを置換
- プロバイダ切り替え  
  `default` / `gas` / `deepl` / `google` / `codex` / `translategemma_local` / `openai` / `gemini` / `anthropic` / `azure`
- Cloth Config + ModMenu でゲーム内設定
- `F8` キーで設定画面を直接オープン

## 対応バージョン

- Minecraft: `1.21.x`（現在のプロファイル既定は `1.21.11`）
- Java: `21`

`version-profiles/` で以下の切り替えを用意しています。

- `fabric-1.21.11.properties`

```powershell
./scripts/use-version-profile.ps1 -ProfileName fabric-1.21.11
```

## 開発コマンド

```powershell
./gradlew.bat :fabric:build
./gradlew.bat :fabric:runClient
```

macOS / Linux の場合:

```bash
./gradlew :fabric:build
./gradlew :fabric:runClient
```

## 操作方法

- チャットメッセージ末尾の `✍`（既定）をクリックして翻訳
- `/chatglot translate <id>` で指定IDのメッセージを翻訳
- `/chatglot config` で設定画面を開く
- `/chatglot save` で設定を保存
- `F8` で設定画面を開く（既定キー）

翻訳メッセージは `【翻訳】➡` プレフィックスで表示されます。

## 設定ファイル

- パス: `config/chatglot/chatglot.json`

主要設定:

- `enabled`: MOD全体の有効/無効
- `appendTranslateButton`: 翻訳ボタンをチャットへ追加
- `translateButtonLabel`: ボタンラベル（既定 `✍`）
- `autoTranslateEnabled`: 自動翻訳の有効/無効
- `overwriteOriginalWithTranslation`: 原文置換モード
- `showTranslationPrefix`: `【翻訳】➡` プレフィックスの表示/非表示
- `targetLanguage`: 例 `JA`, `EN`, `EN-US`, `ZH-HANS`  
  初回生成時は `MINECRAFT_DEFAULT`（Minecraft 言語に追従）
- `provider`: `default|gas|deepl|google|codex|openai|gemini|anthropic|azure|translategemma_local`
- `requestTimeoutSeconds`: 通信タイムアウト秒（5〜240）
- `maxConcurrentTranslations`: 同時に進める翻訳数の上限（1〜16）

プロバイダ固有設定:

- `deeplApiKey`, `deeplUseFreeApi`
- `googleTranslateApiKey`
- `gasWebAppUrl`
- `codexTokenFile`, `codexModel`, `codexReasoningEffort`, `codexReasoningSummary`
- `openaiApiKey`, `openaiModel`
- `geminiApiKey`, `geminiModel`
- `anthropicApiKey`, `anthropicModel`
- `azureTranslatorApiKey`, `azureTranslatorRegion`, `azureTranslatorEndpoint`
- `localBackendBaseUrl`, `localBackendPort`, `localBackendSharedDirectory`, `localBackendCommand`
- `localModelPath`, `localModelAlias`, `localModelFileName`, `localModelDownloadUrl`

## プロバイダ補足

- `default` は内蔵の無料 GAS エンドポイントを使用します（レート制限あり）。
- `default` 選択時は自動翻訳は利用できません。
- `gas` は自分でデプロイした GAS Web アプリ URL（`.../exec`）を使用します。
- `codex` は初回利用時にブラウザ OAuth を行い、`http://localhost:1455/auth/callback` で認証を受け取ります。
- モデル一覧は設定画面から更新可能です（Codex / OpenAI / Gemini / Anthropic）。
- `default` が混雑しているときは `gas` を自分で設定するか、PC スペックに余裕があれば `translategemma_local` を使うと高速・無料・実質無制限で使えます。
- `translategemma_local` は **外部ローカルバックエンドプロセス** を localhost HTTP で利用するローカルモデル機能です（Minecraft JVM 内では推論しません）。
- 設定画面では先に `Setup and start TranslateGemma`、その後に `Download / repair model` を実行してください。セットアップは `winget` で `llama.cpp` を導入し、既定の GGUF モデルをダウンロードして `llama-server` を起動します。
- 既定モデルは `mradermacher/translategemma-4b-it-GGUF` の `translategemma-4b-it.Q4_K_M.gguf` を利用します。必要に応じて設定から URL やファイル名を上書きできます。
- 共有ディレクトリは既定で `%LOCALAPPDATA%\ChatglotLocal\` です。`runtime/`, `models/`, `data/`, `logs/`, `state.json` を作成し、ログは `logs/backend.log` に保存します。
- `localModelPath` と `localBackendCommand` を設定すると、既存のローカルモデルや独自ランタイムを優先して利用できます。

## GAS 連携手順

1. 設定画面の `Google Apps Script (GAS)` で `GASコードをコピー`
2. `Apps Scriptを開く` で Google Apps Script を開く
3. 新規プロジェクトへコードを貼り付けて保存
4. `デプロイ` -> `新しいデプロイ` -> `ウェブアプリ`
5. 実行ユーザーを `自分`、アクセスを `全員`（または用途に応じて）でデプロイ
6. 発行URL（`.../exec`）を `GAS WebアプリURL` に設定
7. `provider` を `gas` に切り替えて利用

## キャッシュ/トークンファイル

`config/chatglot/` 配下に以下が作成されます（利用状況に応じて）。

- `chatglot.json`（設定）
- `codex_tokens.json`（Codex OAuth トークン、`codexTokenFile` 未指定時）
- `codex_models.json`
- `openai_models.json`
- `gemini_models.json`
- `anthropic_models.json`

## プロジェクト構成

```text
common/  : ローダー共通の翻訳ロジック・設定・Mixin
fabric/  : Fabricエントリポイント、クライアントコマンド、設定UI
```

## ライセンス

`MIT`

## 製作者

`yoima`
