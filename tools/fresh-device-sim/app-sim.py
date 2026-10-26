#!/usr/bin/env python3
"""Replay what the Pig app sends to pig-relay and check what comes back.

The APK cannot run without an Android emulator, but the app is only a protocol
client: handshake, a UI pack push, get_commands, and `prompt` frames for the
buttons. This drives the same frames and asserts the results a user would see
on a fresh phone:

  * the relay's first UI pack is parseable (manifest + the files it names),
  * the pigkit commands exist (provider window, endpoints, plan),
  * `/provider-ui` writes the window and its nav entry,
  * saving a reachable provider reports the models and registers it,
  * saving an unreachable one fails loudly instead of claiming success.
"""

import argparse
import http.server
import json
import os
import re
import socket
import sys
import threading
import time

OK = "[ok]"
BAD = "[!!]"


class Client:
    """Line-delimited JSON over the relay socket.

    `socket.makefile().readline()` becomes unusable after a timeout, and this
    script times out on purpose (waiting for a first pig start that compiles
    eight extensions), so the buffer is kept here.
    """

    def __init__(self, port, token, connect_timeout=120):
        # A freshly installed relay needs a moment to listen; and probing the
        # port without authenticating makes it give up ("no client ever
        # authenticated"), so the first connection is the real one.
        deadline = time.time() + connect_timeout
        while True:
            try:
                self.sock = socket.create_connection(("127.0.0.1", port), timeout=5)
                break
            except OSError:
                if time.time() >= deadline:
                    raise
                time.sleep(1)
        self.token = token
        self.buffer = b""
        self.seen = []

    def send(self, frame):
        self.sock.sendall((json.dumps(frame, ensure_ascii=False) + "\n").encode())

    def recv(self, timeout=60):
        deadline = time.time() + timeout
        while True:
            if b"\n" in self.buffer:
                line, self.buffer = self.buffer.split(b"\n", 1)
                frame = json.loads(line)
                self.seen.append(frame)
                return frame
            remaining = deadline - time.time()
            if remaining <= 0:
                return None
            self.sock.settimeout(remaining)
            try:
                chunk = self.sock.recv(65536)
            except socket.timeout:
                return None
            if not chunk:
                raise EOFError("relay closed the connection")
            self.buffer += chunk

    def wait(self, predicate, timeout=60, what=""):
        deadline = time.time() + timeout
        while time.time() < deadline:
            frame = self.recv(min(5, max(0.2, deadline - time.time())))
            if frame is not None and predicate(frame):
                return frame
        raise TimeoutError("timed out waiting for " + (what or "a frame"))


class FakeModels(http.server.BaseHTTPRequestHandler):
    models = ["sim-model-a", "sim-model-b"]

    def do_GET(self):
        if self.path.endswith("/models"):
            body = json.dumps({
                "object": "list",
                "data": [{"id": m, "context_length": 32000} for m in self.models],
            }).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        self.send_response(404)
        self.end_headers()

    def log_message(self, *args):
        pass


def start_fake_models():
    server = http.server.HTTPServer(("127.0.0.1", 0), FakeModels)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server, server.server_address[1]


class Checks:
    def __init__(self):
        self.passed = 0
        self.failed = 0

    def check(self, ok, message, detail=""):
        if ok:
            self.passed += 1
            print(f"{OK} {message}")
        else:
            self.failed += 1
            print(f"{BAD} {message}" + (f"\n      {detail}" if detail else ""))
        return ok

    def done(self):
        print(f"\n--- {self.passed} passed, {self.failed} failed")
        return 0 if self.failed == 0 else 1


def walk_texts(node, out=None):
    """Every `text` in a window body, at any depth."""
    if out is None:
        out = []
    if isinstance(node, dict):
        if node.get("type") == "text" and isinstance(node.get("text"), str):
            out.append(node["text"])
        for value in node.values():
            walk_texts(value, out)
    elif isinstance(node, list):
        for item in node:
            walk_texts(item, out)
    return out


def read_json(path):
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=18793)
    parser.add_argument("--token", default="sim-token")
    parser.add_argument("--device-root", required=True)
    parser.add_argument("--guest-prefix", required=True)
    parser.add_argument("--guest-home", required=True)
    parser.add_argument("--timeout", type=int, default=900)
    args = parser.parse_args()

    home = args.device_root + args.guest_home
    agent = os.path.join(home, ".pig", "agent")
    ui = os.path.join(home, ".pig", "android-ui")
    checks = Checks()
    had_window = os.path.exists(os.path.join(ui, "windows", "providers.json"))
    had_providers = os.path.exists(os.path.join(agent, "custom-endpoints.json"))

    client = Client(args.port, args.token)
    client.send({"type": "relay_auth", "token": args.token})
    hello = client.wait(lambda f: f.get("type") == "relay_hello", 30, "relay_hello")
    checks.check(hello.get("protocol") == "relay/1", f"handshake ({hello.get('protocol')})",
                 str(hello))

    # --- the UI pack the app parses before anything else -------------------
    pack = client.wait(lambda f: f.get("type") == "relay_ui_pack", 30, "relay_ui_pack")
    files = pack.get("files") or {}
    manifest_text = files.get("manifest.json")
    if checks.check(bool(manifest_text), f"UI pack has manifest.json ({len(files)} files)",
                    "an app build before the fix answered 'manifest.json 缺失' here"):
        manifest = json.loads(manifest_text)
        theme = files.get(manifest.get("theme", "theme/colors.json"))
        layout = files.get(manifest.get("layout", "layout/chat.json"))
        checks.check(bool(theme), f"theme {manifest.get('theme')} present")
        checks.check(bool(layout), f"layout {manifest.get('layout')} present")
        if not had_window:
            checks.check("windows/providers.json" not in files,
                         "a fresh device pushes no provider window",
                         "it is rendered by provider-ui, not copied")

    # --- extensions ---------------------------------------------------------
    client.send({"id": "sim-commands", "type": "get_commands"})
    reply = client.wait(
        lambda f: f.get("type") == "response" and f.get("id") == "sim-commands",
        args.timeout, "get_commands")
    commands = [c.get("name") for c in (reply.get("data") or {}).get("commands", [])]
    checks.check(reply.get("success") is not False and len(commands) > 10,
                 f"get_commands: {len(commands)} commands", str(reply.get("error")))
    for name in ("provider-ui", "providers", "providerui.save", "endpoint-add", "plan"):
        checks.check(name in commands, f"command /{name} registered")

    # --- the provider window -------------------------------------------------
    client.send({"id": "sim-open", "type": "prompt", "message": "/provider-ui"})
    window_path = os.path.join(ui, "windows", "providers.json")
    nav_path = os.path.join(ui, "nav.json")
    deadline = time.time() + 60
    while time.time() < deadline and not os.path.exists(window_path):
        time.sleep(0.5)
    if checks.check(os.path.exists(window_path), "window providers.json written"):
        window = read_json(window_path)
        checks.check(window.get("id") == "providers", "window id is providers")
        texts = walk_texts(window.get("body", {}))
        if not had_providers:
            checks.check(any("还没有自定义供应商" in t for t in texts),
                         "fresh device: the window starts empty")
    if checks.check(os.path.exists(nav_path), "nav.json written"):
        items = read_json(nav_path).get("items", [])
        checks.check(any(i.get("command") == "provider-ui" for i in items),
                     "nav has the 模型供应商 entry")

    # --- saving a provider ---------------------------------------------------
    server, models_port = start_fake_models()
    base = f"http://127.0.0.1:{models_port}/v1"

    def save(provider_id, base_url):
        payload = {
            "id": provider_id, "name": "", "baseUrl": base_url, "apiKey": "",
            "api": "openai-completions", "auto": "true", "models": "", "modelsUrl": "",
        }
        client.send({"id": "sim-save-" + provider_id, "type": "prompt",
                     "message": "/providerui.save " + json.dumps(payload)})

        def is_save_notice(frame):
            # `/provider-ui` also notifies with 模型供应商 in the text; a save
            # names the provider in quotes.
            return (frame.get("type") == "extension_ui_request"
                    and frame.get("method") == "notify"
                    and re.search(r"供应商 [\"“]", str(frame.get("message"))))
        return client.wait(is_save_notice, 120, "save notice for " + provider_id)

    notice = save("sim-good", base)
    message = str(notice.get("message"))
    checks.check("已获取 2 个模型" in message, "reachable provider: fetched 2 models", message)
    checks.check("当前会话已生效" in message, "reachable provider: registered in the session",
                 message)
    saved = read_json(os.path.join(agent, "custom-endpoints.json"))
    providers = saved.get("providers", saved)
    checks.check(providers.get("sim-good", {}).get("baseUrl") == base,
                 "custom-endpoints.json holds the provider")
    cached = read_json(os.path.join(agent, "custom-endpoints.cache.json")).get("sim-good")
    checks.check(bool(cached and len(cached.get("models", [])) == 2),
                 "model cache written for the next start")

    notice = save("sim-bad", "http://127.0.0.1:1/v1")
    message = str(notice.get("message"))
    checks.check(notice.get("notifyType") in ("error", "warning"),
                 "unreachable provider: reported as a failure", message)
    checks.check("拿不到模型列表" in message and "还没注册" in message,
                 "unreachable provider: says it is not in the session", message)

    saved = read_json(os.path.join(agent, "custom-endpoints.json"))
    providers = saved.get("providers", saved)
    checks.check("sim-bad" in providers,
                 "unreachable provider: still written to the config file")

    server.shutdown()
    return checks.done()


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (TimeoutError, EOFError) as error:
        print(f"{BAD} {error}")
        sys.exit(1)
