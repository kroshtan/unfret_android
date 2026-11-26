"""
A stand-in for unfret.net for the store screenshots (store/play/README.md).

The emulator resolves unfret.net to this server (its hosts file) and trusts its certificate (a throwaway CA put into
the emulator's system store), so the app, unchanged, talks to it as to unfret.net. It answers the API calls the app
makes for a free check (contract: "Free checks for shoppers"; no pass, no account) with canned answers (fixtures/):
the price list's and the challenge's pause, an easy proof of work, the findings catalogue, and each shop's result. It
passes everything else (the result's photos) through to the dev deployment, read only.

The shops are made up, on .example addresses the emulator also resolves to this server:
  - SHOP_HOST shows the Unfret test shop (dev.unfret.net/test-shop: Unfret's own made-up shop, every trick on it
    fake on purpose); its result (fixtures/test-shop.json) is a real free deep check of it, with the prices taken
    out of its words;
  - the hosts in PAGES show a plain made-up product page; their results (fixtures/level-*.json) are made up by hand.

Free deep checks are paused while the file named by PAUSED exists (the challenge and the price list say so). Every
API request's `X-Unfret-Donate` header is written to API_LOG, so the run can check that the Play build always sends
`off`.

Usage: python3 mock_server.py CERT KEY FIXTURES PAUSED API_LOG (listens on port 443)
"""

import html
import http.server
import json
import os
import ssl
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

CERT, KEY, FIXTURES, PAUSED, API_LOG = sys.argv[1:6]
UPSTREAM = "https://dev.unfret.net"
SHOP_HOST = "nebulora-home.example"
SHOP_PATH = "/products/galaxy-star-projector"
STEPS = ["page", "listing", "html_source_signals", "reviews", "domain", "images", "price_history", "content", "verdict"]

# The made-up shops' product pages: host → (fixture, shop, product, brand, text).
PAGES = {
    "lumen-and-oak.example": (
        "level-0",
        "Lumen & Oak",
        "Walnut desk lamp",
        "Lumen & Oak",
        "Solid walnut base, linen shade, warm LED bulb included. Made in our workshop in Portland, Oregon.",
    ),
    "restwell-home.example": (
        "level-2",
        "Restwell Home",
        "Ergonomic office chair with lumbar support",
        "Restwell",
        "Breathable mesh back, adjustable armrests and lumbar support. Ships in 12 to 18 days.",
    ),
    "zentrova-audio.example": (
        "level-3",
        "Zentrova Audio",
        "X3 wireless earbuds",
        "Zentrova",
        "Waterproof for swimming! 120 hours of music on one charge. Specs: IPX4, 6 hours per charge, Bluetooth 5.3.",
    ),
}
FIXTURE_BY_HOST = {SHOP_HOST: "test-shop", **{host: page[0] for host, page in PAGES.items()}}


def fixture(name: str) -> dict:
    with open(os.path.join(FIXTURES, f"{name}.json"), encoding="utf-8") as f:
        return json.load(f)


def pause(donate_off: bool) -> dict:
    """The pause as the server sends it; without the donate link for a client that may not link to donations."""
    if donate_off:
        return {"reason": "spend_ceiling", "scope": "day", "retry_after_s": 20000, "quick_checks": True,
                "message_key": "free_checks_paused"}
    return {"reason": "spend_ceiling", "scope": "day", "retry_after_s": 20000, "quick_checks": True,
            "message_key": "free_checks_run_on_donations", "donate_url": "https://unfret.net/donate"}


def product_page(host: str) -> bytes:
    _, shop, product, brand, text = PAGES[host]
    data = {"@context": "https://schema.org", "@type": "Product", "name": product, "brand": {"@type": "Brand", "name": brand},
            "description": text}
    e = html.escape
    return f"""<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>{e(product)} | {e(shop)}</title>
<script type="application/ld+json">{json.dumps(data)}</script>
<style>body{{font-family:sans-serif;margin:0;color:#222}}header{{padding:14px 18px;font-weight:700;border-bottom:1px solid #ddd}}
main{{padding:18px}}.photo{{height:280px;border-radius:12px;background:linear-gradient(135deg,#e7e2d8,#c9bfae)}}</style>
</head><body><header>{e(shop)}</header><main><div class="photo"></div><h1>{e(product)}</h1>
<p>by {e(brand)}</p><p>{e(text)}</p><button>Add to cart</button></main></body></html>""".encode()


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, format: str, *args: object) -> None:
        sys.stderr.write("mock: " + (format % args) + "\n")

    def body(self) -> bytes:
        length = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(length) if length else b""

    def host(self) -> str:
        return self.headers.get("Host", "").split(":")[0]

    def donate_off(self, path: str) -> bool:
        off = (self.headers.get("X-Unfret-Donate") or "").lower() == "off"
        if path.startswith("/v1/") and not path.startswith("/v1/images/"):
            with open(API_LOG, "a", encoding="utf-8") as log:
                log.write(f"{self.command} {path} donate={'off' if off else 'missing'}\n")
        return off

    def send(self, status: int, raw: bytes, kind: str) -> None:
        self.send_response(status)
        if kind:
            self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def json(self, data: dict, status: int = 200) -> None:
        self.send(status, json.dumps(data).encode(), "application/json")

    def do_GET(self) -> None:
        path = self.path.split("?")[0]
        if self.host() == SHOP_HOST:
            self.proxy("GET", None, "/test-shop" if path == SHOP_PATH else self.path)
            return
        if self.host() in PAGES:
            self.send(200, product_page(self.host()), "text/html; charset=utf-8")
            return
        off = self.donate_off(path)
        paused = os.path.exists(PAUSED)
        if path == "/v1/analyze/challenge":
            answer = {"challenge": f"store-screenshots-{time.time_ns()}", "bits": 4, "expires_at": time.time() + 300,
                      "paused": paused}
            self.json({**answer, "pause": pause(off)} if paused else answer)
        elif path == "/v1/pricing":
            free = {"quick_checks": True, "deep_checks": True, "paused": paused, "back_at": "00:00 UTC",
                    "proof_of_work": {"challenge_url": "/v1/analyze/challenge", "bits": 4}}
            if paused:
                free["pause"] = pause(off)
            if not off:
                free["donate_url"] = "https://unfret.net/donate"
            self.json({"free": free})
        elif path == "/v1/catalogue/findings":
            self.json(fixture("catalogue"))
        else:
            self.proxy("GET", None)

    def do_POST(self) -> None:
        path = self.path.split("?")[0]
        request = self.body()
        self.donate_off(path)
        if path in ("/v1/analyze", "/v1/analyze/stream"):
            body = json.loads(request or b"{}")
            host = urllib.parse.urlsplit(body.get("url") or "").hostname or ""
            name = FIXTURE_BY_HOST.get(host)
            if name is None:
                self.json({"detail": f"no fixture for {host!r}", "error_code": "BAD_REQUEST"}, 400)
            elif body.get("tier") != "quick" and "proof_of_work" not in body:
                self.json({"detail": "Solve the challenge first.", "error_code": "PROOF_OF_WORK_REQUIRED"}, 428)
            else:
                self.stream(fixture(name))
        elif path == "/v1/labels":
            self.json({"status": "queued", "updated": False, "anonymous": True}, 201)
        else:
            self.json({"detail": "not in the store screenshots' stand-in", "error_code": "NOT_FOUND"}, 404)

    def stream(self, result: dict) -> None:
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()
        for skill in STEPS:
            self.wfile.write(f"event: step\ndata: {json.dumps({'skill': skill, 'check': True})}\n\n".encode())
            self.wfile.flush()
            time.sleep(0.2)
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
        self.send(status, raw, kind)


server = http.server.ThreadingHTTPServer(("127.0.0.1", 443), Handler)
context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
context.load_cert_chain(CERT, KEY)
server.socket = context.wrap_socket(server.socket, server_side=True)
print("mock unfret.net on 127.0.0.1:443", flush=True)
server.serve_forever()
