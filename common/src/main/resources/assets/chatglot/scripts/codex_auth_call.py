#!/usr/bin/env python3
"""
Minimal OAuth + model-call example based on OpenCode's Codex integration.

Reference implementation:
- packages/opencode/src/plugin/codex.ts
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import secrets
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import webbrowser
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
ISSUER = "https://auth.openai.com"
CODEX_API_ENDPOINT = "https://chatgpt.com/backend-api/codex/responses"
DEFAULT_MODEL = "gpt-5.3-codex"
DEFAULT_PORT = 1455
DEFAULT_SCOPE = "openid profile email offline_access"


def codex_instructions() -> str:
    path = Path(__file__).resolve().parents[1] / "packages" / "opencode" / "src" / "session" / "prompt" / "codex_header.txt"
    if path.exists():
        text = path.read_text(encoding="utf-8").strip()
        if text:
            return text
    return "You are OpenCode, a coding assistant."


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("utf-8")


def generate_pkce() -> tuple[str, str]:
    alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    verifier = "".join(secrets.choice(alphabet) for _ in range(43))
    challenge = b64url(hashlib.sha256(verifier.encode("utf-8")).digest())
    return verifier, challenge


def parse_jwt_claims(token: str) -> dict | None:
    parts = token.split(".")
    if len(parts) != 3:
        return None
    segment = parts[1]
    segment += "=" * ((4 - len(segment) % 4) % 4)
    try:
        return json.loads(base64.urlsafe_b64decode(segment.encode("utf-8")).decode("utf-8"))
    except Exception:
        return None


def extract_account_id(tokens: dict) -> str | None:
    def pick(claims: dict | None) -> str | None:
        if not claims:
            return None
        return (
            claims.get("chatgpt_account_id")
            or claims.get("https://api.openai.com/auth", {}).get("chatgpt_account_id")
            or ((claims.get("organizations") or [{}])[0].get("id"))
        )

    return pick(parse_jwt_claims(tokens.get("id_token", ""))) or pick(parse_jwt_claims(tokens.get("access_token", "")))


def post_form(url: str, form: dict) -> dict:
    body = urllib.parse.urlencode(form).encode("utf-8")
    req = urllib.request.Request(
        url=url,
        data=body,
        method="POST",
        headers={"Content-Type": "application/x-www-form-urlencoded"},
    )
    with urllib.request.urlopen(req, timeout=30) as res:
        return json.loads(res.read().decode("utf-8"))


def exchange_code_for_tokens(code: str, redirect_uri: str, verifier: str) -> dict:
    return post_form(
        f"{ISSUER}/oauth/token",
        {
            "grant_type": "authorization_code",
            "code": code,
            "redirect_uri": redirect_uri,
            "client_id": CLIENT_ID,
            "code_verifier": verifier,
        },
    )


def refresh_access_token(refresh_token: str) -> dict:
    return post_form(
        f"{ISSUER}/oauth/token",
        {
            "grant_type": "refresh_token",
            "refresh_token": refresh_token,
            "client_id": CLIENT_ID,
        },
    )


def build_authorize_url(redirect_uri: str, challenge: str, state: str) -> str:
    q = urllib.parse.urlencode(
        {
            "response_type": "code",
            "client_id": CLIENT_ID,
            "redirect_uri": redirect_uri,
            "scope": DEFAULT_SCOPE,
            "code_challenge": challenge,
            "code_challenge_method": "S256",
            "id_token_add_organizations": "true",
            "codex_cli_simplified_flow": "true",
            "state": state,
            "originator": "opencode",
        }
    )
    return f"{ISSUER}/oauth/authorize?{q}"


def run_browser_oauth(port: int = DEFAULT_PORT, timeout_sec: int = 300) -> dict:
    verifier, challenge = generate_pkce()
    state = b64url(secrets.token_bytes(32))
    redirect_uri = f"http://localhost:{port}/auth/callback"

    event = threading.Event()
    payload: dict[str, str | None] = {"code": None, "error": None}

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):  # noqa: N802
            parsed = urllib.parse.urlparse(self.path)
            if parsed.path != "/auth/callback":
                self.send_response(404)
                self.end_headers()
                self.wfile.write(b"Not found")
                return

            params = urllib.parse.parse_qs(parsed.query)
            code = params.get("code", [None])[0]
            callback_state = params.get("state", [None])[0]
            err = params.get("error_description", [None])[0] or params.get("error", [None])[0]

            if err:
                payload["error"] = str(err)
            elif not code:
                payload["error"] = "Missing authorization code"
            elif callback_state != state:
                payload["error"] = "Invalid state - potential CSRF attack"
            else:
                payload["code"] = str(code)

            html = (
                "<html><body><h1>Authorization successful</h1><p>You can close this tab.</p></body></html>"
                if payload["error"] is None
                else f"<html><body><h1>Authorization failed</h1><p>{payload['error']}</p></body></html>"
            )
            self.send_response(200 if payload["error"] is None else 400)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            self.wfile.write(html.encode("utf-8"))
            event.set()

        def log_message(self, fmt: str, *args):  # noqa: A003
            return

    server = HTTPServer(("localhost", port), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()

    try:
        url = build_authorize_url(redirect_uri, challenge, state)
        print(f"Open this URL in your browser:\n{url}\n")
        webbrowser.open(url)

        if not event.wait(timeout=timeout_sec):
            raise TimeoutError("OAuth callback timeout")
        if payload["error"]:
            raise RuntimeError(f"OAuth failed: {payload['error']}")

        tokens = exchange_code_for_tokens(str(payload["code"]), redirect_uri, verifier)
        tokens["account_id"] = extract_account_id(tokens)
        tokens["expires_at"] = int(time.time()) + int(tokens.get("expires_in", 3600))
        return tokens
    finally:
        server.shutdown()
        server.server_close()


def parse_http_response(res) -> dict:
    body = res.read().decode("utf-8", errors="replace")
    content_type = (res.headers.get("Content-Type") or "").lower()
    text = body.strip()
    if text:
        try:
            return json.loads(text)
        except Exception:
            pass

    events: list[dict] = []
    chunks: list[str] = []
    saw_delta = False

    def push_event(event: dict) -> None:
        nonlocal saw_delta
        events.append(event)
        if event.get("type") == "response.output_text.delta" and isinstance(event.get("delta"), str):
            saw_delta = True
            chunks.append(event["delta"])
            return
        if event.get("type") == "response.output_item.done":
            if saw_delta:
                return
            item = event.get("item")
            if not isinstance(item, dict):
                return
            for part in item.get("content", []):
                if not isinstance(part, dict):
                    continue
                if part.get("type") in {"output_text", "text"} and isinstance(part.get("text"), str):
                    chunks.append(part["text"])

    # 1) Standard SSE: lines with "data: <json>"
    for line in body.splitlines():
        line = line.strip()
        if not line.startswith("data:"):
            continue
        data = line[5:].strip()
        if not data or data == "[DONE]":
            continue
        try:
            push_event(json.loads(data))
        except Exception:
            continue

    # 2) NDJSON fallback: one JSON object per line
    if not events:
        for line in body.splitlines():
            line = line.strip()
            if not line.startswith("{"):
                continue
            try:
                push_event(json.loads(line))
            except Exception:
                continue

    if events:
        return {"output_text": "".join(chunks), "events": events}

    raise RuntimeError(
        "Unexpected response format from Codex endpoint. "
        f"content_type={content_type!r} body_preview={body[:500]!r}"
    )


def call_codex(
    access_token: str,
    account_id: str | None,
    model: str,
    prompt: str,
    effort: str | None = "medium",
    summary: str | None = "auto",
) -> dict:
    instructions = codex_instructions()
    reasoning = {
        **({"effort": effort} if effort else {}),
        **({"summary": summary} if summary else {}),
    }
    headers_base = {
        "Content-Type": "application/json",
        "Accept": "text/event-stream",
        "Authorization": f"Bearer {access_token}",
        "originator": "opencode",
        "User-Agent": "codex-auth-call-python/0.1",
        "session_id": f"session-{int(time.time())}",
    }
    candidate_headers = [headers_base]
    if account_id:
        with_account = dict(headers_base)
        with_account["ChatGPT-Account-Id"] = account_id
        candidate_headers.insert(0, with_account)

    payloads = [
        {
            "label": "responses-rich",
            "body": {
                "model": model,
                "input": [
                    {
                        "role": "user",
                        "content": [{"type": "input_text", "text": prompt}],
                    }
                ],
                "instructions": instructions,
                "truncation": "auto",
                "store": False,
                "stream": True,
                **({"reasoning": reasoning} if reasoning else {}),
            },
        },
        {
            "label": "responses-minimal",
            "body": {
                "model": model,
                "input": [
                    {
                        "role": "user",
                        "content": [{"type": "input_text", "text": prompt}],
                    }
                ],
                "instructions": instructions,
                "store": False,
                "stream": True,
                **({"reasoning": reasoning} if reasoning else {}),
            },
        },
    ]

    errors: list[str] = []

    for hdr in candidate_headers:
        has_account = "ChatGPT-Account-Id" in hdr
        for payload in payloads:
            req = urllib.request.Request(
                url=CODEX_API_ENDPOINT,
                data=json.dumps(payload["body"]).encode("utf-8"),
                method="POST",
                headers=hdr,
            )
            try:
                with urllib.request.urlopen(req, timeout=120) as res:
                    return parse_http_response(res)
            except urllib.error.HTTPError as e:
                body = ""
                try:
                    body = e.read().decode("utf-8", errors="replace")
                except Exception:
                    body = "<unable to read error body>"
                errors.append(
                    f"status={e.code} header.account_id={has_account} payload={payload['label']} body={body[:800]}"
                )
                if e.code not in {400, 404, 422}:
                    break
            except urllib.error.URLError as e:
                errors.append(
                    f"network_error header.account_id={has_account} payload={payload['label']} reason={e.reason}"
                )
                break

    detail = "\n\n".join(errors) if errors else "no details"
    raise RuntimeError(f"Codex request failed after fallback attempts.\n\n{detail}")


def read_tokens(path: Path) -> dict | None:
    if not path.exists():
        return None
    return json.loads(path.read_text(encoding="utf-8"))


def write_tokens(path: Path, tokens: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(tokens, indent=2), encoding="utf-8")
    try:
        path.chmod(0o600)
    except Exception:
        pass


def ensure_tokens(path: Path, port: int) -> dict:
    saved = read_tokens(path)
    now = int(time.time())
    if saved and int(saved.get("expires_at", 0)) > now + 30:
        return saved

    if saved and saved.get("refresh_token"):
        refreshed = refresh_access_token(str(saved["refresh_token"]))
        refreshed["account_id"] = extract_account_id(refreshed) or saved.get("account_id")
        refreshed["expires_at"] = int(time.time()) + int(refreshed.get("expires_in", 3600))
        write_tokens(path, refreshed)
        return refreshed

    fresh = run_browser_oauth(port=port)
    write_tokens(path, fresh)
    return fresh


def extract_text(response: dict) -> str:
    if isinstance(response.get("output_text"), str):
        return response["output_text"]

    lines: list[str] = []
    for item in response.get("output", []):
        if not isinstance(item, dict):
            continue
        for part in item.get("content", []):
            if not isinstance(part, dict):
                continue
            if part.get("type") in {"output_text", "text"} and isinstance(part.get("text"), str):
                lines.append(part["text"])
    return "\n".join(lines).strip()


def emit(text: str) -> None:
    try:
        print(text)
    except UnicodeEncodeError:
        sys.stdout.buffer.write((text + "\n").encode("utf-8", errors="replace"))
        sys.stdout.buffer.flush()


def main() -> int:
    parser = argparse.ArgumentParser(description="OAuth and call ChatGPT Codex endpoint.")
    parser.add_argument("--model", default=DEFAULT_MODEL, help="Model ID, e.g. gpt-5.3-codex")
    parser.add_argument("--prompt", default="Say hello from Python.", help="Prompt text")
    parser.add_argument(
        "--effort",
        choices=["none", "minimal", "low", "medium", "high", "xhigh"],
        default="medium",
        help="Reasoning effort (opencode equivalent: low/medium/high/xhigh)",
    )
    parser.add_argument(
        "--summary",
        default="auto",
        help="Reasoning summary mode (default: auto, set empty to omit)",
    )
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help="Local OAuth callback port")
    parser.add_argument(
        "--token-file",
        default=str(Path.home() / ".chatglot" / "codex_tokens.json"),
        help="Path to cached OAuth tokens",
    )
    args = parser.parse_args()

    token_path = Path(args.token_file)
    tokens = ensure_tokens(token_path, args.port)

    response = call_codex(
        access_token=str(tokens["access_token"]),
        account_id=tokens.get("account_id"),
        model=args.model,
        prompt=args.prompt,
        effort=args.effort,
        summary=(args.summary if args.summary else None),
    )

    text = extract_text(response)
    if text:
        emit(text)
        return 0

    emit(json.dumps(response, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
