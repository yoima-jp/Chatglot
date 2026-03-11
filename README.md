# Chatglot

Chatglot は Minecraft チャットを翻訳する Fabric クライアント MOD です。  
`common + fabric` のマルチモジュール構成で、複数の翻訳プロバイダを切り替えて利用できます。

## 主な機能

- チャット末尾に翻訳ボタンを付与（既定ラベル: `✍`）
- ボタンクリックまたはコマンドで個別翻訳
- 自動翻訳（Lingua による言語判定）
- 翻訳結果を新規行表示、または原文チャットを置換
- プロバイダ切り替え  
  `default` / `gas` / `deepl` / `google` / `codex` / `openai` / `gemini` / `anthropic` / `azure` / `translategemma_local`
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
- `translategemmaLocalBackendUrl`, `translategemmaLocalBackendPort`
- `translategemmaLocalModelPath`, `translategemmaLocalModelAlias`
- `translategemmaLocalInstallDir`, `translategemmaLocalBackendCommand`

## プロバイダ補足

- `default` は内蔵の無料 GAS エンドポイントを使用します（レート制限あり）。
- `default` 選択時は自動翻訳は利用できません。
- `gas` は自分でデプロイした GAS Web アプリ URL（`.../exec`）を使用します。
- `codex` は初回利用時にブラウザ OAuth を行い、`http://localhost:1455/auth/callback` で認証を受け取ります。
- モデル一覧は設定画面から更新可能です（Codex / OpenAI / Gemini / Anthropic）。
- `translategemma_local` は外部ローカルバックエンド（localhost HTTP）へ接続します。モデル推論は Minecraft JVM 内では実行しません。
- 初期版は **Windows のみ** を対象とし、共有ディレクトリ `%LOCALAPPDATA%\ChatglotLocal\` に runtime/models/data/logs/state.json を配置します。
- 初期版ではモデル取得は手動です（モデルファイルパスを設定）。トークン埋め込みや gated モデルの自動取得は行いません。
- 複数の Minecraft インスタンスは同一の共有バックエンド state.json を参照し、ヘルスチェックで再利用を試みます。

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

ローカルバックエンドの共有状態（Windows / `translategemma_local` 使用時）は既定で以下に保存されます。

- `%LOCALAPPDATA%\ChatglotLocal\state.json`

## プロジェクト構成

```text
common/  : ローダー共通の翻訳ロジック・設定・Mixin
fabric/  : Fabricエントリポイント、クライアントコマンド、設定UI
```

## ライセンス

`MIT`

## 製作者

`yoima`
