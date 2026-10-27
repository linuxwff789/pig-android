#!/usr/bin/env python3
"""Check that a relay-side failure reaches the app *while it happens*.

Started with a pig binary that does not exist, the relay cannot answer
`get_commands`; it logs "cannot start pig ...". That line must arrive as a
`relay_log` frame immediately (before any reconnect), which is what this checks.
"""

import argparse
import json
import socket
import sys
import time


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=18794)
    parser.add_argument("--token", default="sim-token")
    parser.add_argument("--timeout", type=float, default=30)
    args = parser.parse_args()

    # The relay is started in the background just before this; wait for it.
    deadline = time.time() + args.timeout
    while True:
        try:
            sock = socket.create_connection(("127.0.0.1", args.port), timeout=5)
            break
        except OSError:
            if time.time() >= deadline:
                raise
            time.sleep(1)
    buffer = b""

    def send(frame):
        sock.sendall((json.dumps(frame) + "\n").encode())

    def recv(timeout):
        nonlocal buffer
        deadline = time.time() + timeout
        while True:
            if b"\n" in buffer:
                line, buffer = buffer.split(b"\n", 1)
                return json.loads(line)
            remaining = deadline - time.time()
            if remaining <= 0:
                return None
            sock.settimeout(remaining)
            try:
                chunk = sock.recv(65536)
            except socket.timeout:
                return None
            if not chunk:
                raise EOFError("relay closed the connection")
            buffer += chunk

    failures = []

    def check(ok, message, detail=""):
        print(("[ok] " if ok else "[!!] ") + message + (f"\n      {detail}" if not ok and detail else ""))
        if not ok:
            failures.append(message)

    send({"type": "relay_auth", "token": args.token})
    hello = None
    deadline = time.time() + args.timeout
    while time.time() < deadline and hello is None:
        frame = recv(5)
        if frame and frame.get("type") == "relay_hello":
            hello = frame
    check(hello is not None, "handshake")
    if hello is None:
        return 1

    # A failing command: the relay has to say why on the wire.
    send({"id": "log-commands", "type": "get_commands"})
    error_log = None
    response = None
    deadline = time.time() + args.timeout
    while time.time() < deadline and (error_log is None or response is None):
        frame = recv(5)
        if not frame:
            continue
        if frame.get("type") == "relay_log" and frame.get("level") == "error":
            error_log = frame
        elif frame.get("type") == "response" and frame.get("id") == "log-commands":
            response = frame

    check(
        error_log is not None and "cannot start pig" in str(error_log.get("message", "")),
        "relay error reaches the client in real time",
        str(error_log),
    )
    check(
        response is not None and response.get("success") is False,
        "the failed request answers with success: false",
        str(response),
    )
    return 1 if failures else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (TimeoutError, EOFError, ConnectionError) as error:
        print(f"[!!] {error}")
        sys.exit(1)
