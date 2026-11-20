"""
A stand-in for unfret.net for the Play store screenshots (store/play/README.md).

The emulator resolves unfret.net to this server (its hosts file) and trusts its certificate (a throwaway CA put into
the emulator's system store), so the app, unchanged, talks to it as to unfret.net. It answers the API calls the app
makes with canned answers (fixtures/), and passes everything else (the result's photos) through to the real
https://unfret.net, read only.

The page being checked is the Unfret test shop (unfret.net/test-shop: Unfret's own made-up shop, every trick on it
fake on purpose). The app won't check a page on unfret.net itself, so the emulator also resolves the made-up
SHOP_HOST to this server, which shows the test shop there (SHOP_PATH is the test shop's product page).

The streamed check holds before its result while the file named by HOLD exists, so the screenshot of a check
running is taken at a known moment.

Usage: python3 mock_server.py CERT KEY FIXTURES HOLD (listens on port 443)
"""

import http.server
import json
import os
import ssl
import sys
import time
import urllib.error
import urllib.request

CERT, KEY, FIXTURES, HOLD = sys.argv[1:5]
UPSTREAM = "https://unfret.net"
SHOP_HOST = "nebulora-home.example"
SHOP_PATH = "/products/galaxy-star-projector"
STEPS = ["page", "listing", "html_source_signals", "reviews", "domain", "images", "price_history", "content", "verdict"]

# The made-up pass the app gets on its first start: 5 free deep checks, one used by the deep check.
state = {"checks": 5}


def fixture(name: str) -> dict:
    with open(os.path.join(FIXTURES, name), encoding="utf-8") as f:
        return json.load(f)


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, format: str, *args: object) -> None:
        sys.stderr.write("mock: " + (format % args) + "\n")

    def body(self) -> bytes:
        length = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(length) if length else b""

    def json(self, data: dict, status: int = 200) -> None:
        raw = json.dumps(data).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self) -> None:
        path = self.path.split("?")[0]
        if self.headers.get("Host", "").split(":")[0] == SHOP_HOST:
            self.proxy("GET", None, "/test-shop" if path == SHOP_PATH else self.path)
        elif path == "/v1/keys/challenge":
            self.json({"challenge": "store-screenshots", "bits": 4})
        elif path == "/v1/credits":
            self.json(
                {
                    "address": "key:k7m2qbxw4t",
                    "balance_lamports": 0,
                    "checks_remaining": state["checks"],
                    "free_checks_remaining": state["checks"],
                    "invite_eligible": False,
                }
            )
        else:
            self.proxy("GET", None)

    def do_POST(self) -> None:
        path = self.path.split("?")[0]
        request = self.body()
        if path == "/v1/keys":
            self.json(
                {
                    "key": "ufk_k7m2qbxw4t_" + "examplekeyforstorescreenshots" + "a" * 23,
                    "key_id": "k7m2qbxw4t",
                    "recovery_code": "example-recovery-code-for-store-screenshots",
                    "free_checks": 5,
                }
            )
        elif path == "/v1/analyze/stream":
            quick = json.loads(request or b"{}").get("tier") == "quick"
            self.stream(fixture("quick.json" if quick else "deep.json"), steps=4 if quick else len(STEPS))
            if not quick:
                state["checks"] -= 1
        else:
            self.proxy("POST", request)

    def stream(self, result: dict, steps: int) -> None:
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()
        for skill in STEPS[: steps - 1]:
            self.wfile.write(f"event: step\ndata: {json.dumps({'skill': skill})}\n\n".encode())
            self.wfile.flush()
            time.sleep(0.4)
        while os.path.exists(HOLD):
            time.sleep(0.2)
        self.wfile.write(f"event: step\ndata: {json.dumps({'skill': STEPS[-1]})}\n\n".encode())
        self.wfile.write(f"event: result\ndata: {json.dumps(result)}\n\n".encode())
        self.wfile.flush()
        self.close_connection = True

    def proxy(self, method: str, body: bytes | None, path: str | None = None) -> None:
        headers = {k: v for k, v in self.headers.items() if k.lower() in ("accept", "accept-language", "content-type")}
        headers["User-Agent"] = "unfret-store-screenshots"
        req = urllib.request.Request(UPSTREAM + (path or self.path), data=body, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                status, raw, kind = resp.status, resp.read(), resp.headers.get("Content-Type", "")
        except urllib.error.HTTPError as e:
            status, raw, kind = e.code, e.read(), e.headers.get("Content-Type", "")
        if path == "/test-shop" and status == 200:
            # No prices in the store screenshots: the shop's banner names one ("free shipping over $79").
            raw = raw.replace(b"</head>", b"<style>.announcement{display:none}</style></head>", 1)
        self.send_response(status)
        if kind:
            self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)


server = http.server.ThreadingHTTPServer(("127.0.0.1", 443), Handler)
context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
context.load_cert_chain(CERT, KEY)
server.socket = context.wrap_socket(server.socket, server_side=True)
print("mock unfret.net on 127.0.0.1:443", flush=True)
server.serve_forever()
