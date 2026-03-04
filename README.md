# Chatglot

Chatglot は Minecraft チャットを翻訳する Fabric クライアントMODです。

- Fabric 1.21.x を想定（デフォルト: `1.21.11`）
- チャット末尾に `[T]` ボタンを表示し、クリックで翻訳
- 自動翻訳モード（Lingua による言語判定）
- 翻訳プロバイダ切り替え（`deepl` / `codex`）
- 設定はゲーム内（Cloth Config）で変更可能

## ライセンス

`ARR (All Rights Reserved)`

## 構成

```text
common/  : ローダー共通の翻訳ロジック・設定・Mixin
fabric/  : Fabricエントリポイント、コマンド、設定UI
```

将来的に NeoForge 等へ展開しやすいように `common + loader` 分離構成にしています。

## バージョン切り替え

`version-profiles/` にプロファイルを用意しています。

- `fabric-1.21.11.properties`
- `fabric-1.21.0.properties`

適用:

```powershell
./scripts/use-version-profile.ps1 -ProfileName fabric-1.21.0
```

## 起動

```powershell
./gradlew :fabric:runClient
```

## コマンド

- `/chatglot translate <id>`: `[T]` ボタンIDのメッセージを翻訳
- `/chatglot config`: 設定画面を開く
- `/chatglot save`: 設定を保存

## 設定ファイル

`config/chatglot/chatglot.json`

主な設定:

- `provider`: `deepl` または `codex`
- `targetLanguage`: 例 `JA`, `EN`, `EN-US`
- `autoTranslateEnabled`: 自動翻訳ON/OFF
- `deeplApiKey`: DeepL APIキー
- `codexPythonCommand`: Python実行コマンド（例: `python` / `py -3`）
- `codexScriptPath`: 空欄なら自動探索 (`<gameDir>/codex_auth_call.py` -> 同梱スクリプト抽出)

## Codex連携について

- 既存の `codex_auth_call.py` を優先利用
- 見つからない場合は同梱スクリプトを `config/chatglot/codex_auth_call.py` に展開
- OAuthトークンは `config/chatglot/codex_tokens.json`（既定）に保存

## 開発メモ

- Java 21
- Fabric Loader `0.18.4`
- Fabric API `0.141.3+1.21.11`
- Loom `dev.architectury.loom 1.13-SNAPSHOT`
