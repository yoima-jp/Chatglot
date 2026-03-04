## codex-python-integration

### 1. 目的

本仕様は、`script/codex_auth_call.py` における「Codex連携 -> モデル呼び出し」までの一連の処理を定義する。
対象は、ChatGPT Plus/Pro ベースの OpenAI OAuth 認証を使い、`chatgpt.com` の Codex endpoint を呼び出すフローである。

### 2. スコープ

本仕様に含む範囲:

- OAuth (PKCE) 認可開始
- ローカルコールバックでの認可コード受領
- トークン交換 / リフレッシュ
- トークン永続化
- Codex Responses API 呼び出し
- ストリーミング応答パース
- CLI パラメータ (モデル/プロンプト/reasoning 深度)

本仕様に含まない範囲:

- GUI 実装
- 複数モデルの同時実行
- 運用監視基盤

### 3. 用語

- `access_token`: Codex API 呼び出し時の Bearer トークン
- `refresh_token`: `access_token` 更新に使うトークン
- `account_id`: JWT claims から抽出する ChatGPT アカウント識別子 (`ChatGPT-Account-Id` ヘッダ用)
- `instructions`: Codex 向けシステム指示文。既定では `packages/opencode/src/session/prompt/codex_header.txt` を読み込む

### 4. 保存先

- デフォルト保存先: `~/.chatglot/codex_tokens.json`
- 保存データ:
  - `access_token`
  - `refresh_token`
  - `id_token` (存在する場合)
  - `expires_in` (存在する場合)
  - `expires_at` (ローカル計算値)
  - `account_id` (抽出成功時)

### 5. 認証フロー

1. PKCE を生成する
   - `code_verifier` (43文字)
   - `code_challenge` (`S256`)
2. `http://localhost:1455/auth/callback` を redirect URI として OAuth URL を生成する
3. ブラウザを開き、ユーザーがログイン/認可する
4. ローカル HTTP サーバで `code` と `state` を受け取る
5. `state` 検証に成功した場合のみトークン交換に進む
6. `authorization_code` を `access_token` / `refresh_token` に交換する
7. `id_token` / `access_token` claims から `account_id` を抽出する
8. トークンをファイルへ保存する

### 6. トークン更新フロー

1. 保存済み `expires_at` が有効期限内なら再利用する
2. 期限切れで `refresh_token` があれば `grant_type=refresh_token` で更新する
3. 更新結果を再保存する
4. 更新できない場合は OAuth 認証フローを再実行する

### 7. モデル呼び出しフロー

エンドポイント:

- `POST https://chatgpt.com/backend-api/codex/responses`

リクエストヘッダ:

- `Authorization: Bearer <access_token>`
- `Content-Type: application/json`
- `Accept: text/event-stream`
- `originator: opencode`
- `session_id: session-<unix-ts>`
- `ChatGPT-Account-Id: <account_id>` (存在時)

リクエストボディ (概略):

```json
{
  "model": "gpt-5.3-codex",
  "input": [
    {
      "role": "user",
      "content": [
        { "type": "input_text", "text": "..." }
      ]
    }
  ],
  "instructions": "...",
  "store": false,
  "stream": true,
  "truncation": "auto",
  "reasoning": {
    "effort": "medium",
    "summary": "auto"
  }
}
```

Codex endpoint の要件として、以下を満たす必要がある:

- `store` は `false`
- `stream` は `true`
- `input` は配列
- `instructions` は必須

### 8. Reasoning 深度仕様

CLI 引数:

- `--effort`: `none | minimal | low | medium | high | xhigh`
- `--summary`: 既定 `auto`、空文字で未指定

ボディ反映:

- `reasoning.effort = --effort`
- `reasoning.summary = --summary` (未指定時は送信しない)

既定値:

- `effort = medium`
- `summary = auto`

### 9. レスポンス処理仕様

受信形式:

- JSON 単体レスポンス
- SSE (`text/event-stream`)
- NDJSON 互換形式

パース規則:

1. 本文全体が JSON として読める場合はそのまま返す
2. SSE の `data: ...` 行を JSON としてパースする
3. SSE が空なら NDJSON として行単位 JSON をパースする
4. テキスト抽出は以下を優先する
   - `response.output_text.delta` の連結
   - delta が無い場合 `response.output_item.done.item.content[].text`

### 10. エラーハンドリング仕様

対象例外:

- `HTTPError`
- `URLError`
- OAuth タイムアウト
- `state` 不一致
- 予期しないレスポンス形式

失敗時の要件:

- `status` / `payload` / `account_id ヘッダ有無` / `body` を含む詳細メッセージを返す
- 複数 payload を試行した場合は全失敗ログを連結して返す

### 11. CLI 仕様

主要引数:

- `--model` (既定: `gpt-5.3-codex`)
- `--prompt` (既定: `Say hello from Python.`)
- `--effort` (既定: `medium`)
- `--summary` (既定: `auto`)
- `--token-file` (既定: `~/.chatglot/codex_tokens.json`)
- `--port` (既定: `1455`)

実行例:

```bash
python script/codex_auth_call.py --prompt "レビューして" --effort high --summary auto
```

### 12. OpenCode 本体との整合

本仕様は以下の OpenCode 実装方針に整合させる:

- Codex endpoint 利用 (`/backend-api/codex/responses`)
- OAuth access token + account id ヘッダで認証
- Codex 系は `instructions` を別途送信
- OpenAI 系は `store=false` を既定とする
- reasoning effort を `low/medium/high/xhigh` 系で調整可能とする

