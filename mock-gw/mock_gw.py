#!/usr/bin/env python3
"""Mock SMS vendor gateway — simulates a real external channel provider.

Behaviors (mirrors what a real SMS gateway does):
  1. POST /sms/send  — receives send requests from the EA platform adapter,
     authenticates via X-Api-Key header, records the message, returns
     {"message_id": "mock-sms-...", "status": "ACCEPTED"}.
  2. ~2s later, asynchronously calls back the EA platform
     {CALLBACK_URL} with body {messageId,status,timestamp} and header
     X-Signature = HMAC-SHA256(CALLBACK_SECRET, "messageId|status|timestamp")
     — same canonicalization the platform's ChannelController verifies.
  3. POST /sms/receipt — active receipt query: returns latest status.
"""
import hashlib
import hmac
import json
import os
import threading
import time
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

CALLBACK_URL = os.environ.get("CALLBACK_URL", "http://ea-app:8081/api/channels/sms/callback")
CALLBACK_SECRET = os.environ.get("CALLBACK_SECRET", "sms-callback-secret-1")
API_KEY = os.environ.get("SMS_API_KEY", "test-api-key")

SENT = {}  # message_id -> status


def sign(message: str) -> str:
    return hmac.new(CALLBACK_SECRET.encode(), message.encode(), hashlib.sha256).hexdigest()


def fire_callback(msg_id: str, status: str) -> None:
    SENT[msg_id] = status  # synced so active receipt queries see the latest state
    time.sleep(2)  # simulate delivery latency
    ts = int(time.time() * 1000)
    canonical = f"{msg_id}|{status}|{ts}"
    body = json.dumps({"messageId": msg_id, "status": status, "timestamp": ts}).encode()
    req = urllib.request.Request(
        CALLBACK_URL, data=body, method="POST",
        headers={"Content-Type": "application/json", "X-Signature": sign(canonical)})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            print(f"[CALLBACK] {msg_id} -> {r.status} {r.read().decode()[:200]}", flush=True)
    except Exception as e:  # noqa: BLE001
        print(f"[CALLBACK] FAIL {msg_id}: {e}", flush=True)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):  # quiet
        pass

    def _send(self, obj, code=200):
        b = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def _form(self):
        n = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(n).decode()
        return dict(urllib.parse.parse_qsl(raw))

    def do_GET(self):
        if self.path == "/healthz":
            self._send({"ok": True})
        else:
            self._send({"error": "not found"}, 404)

    def do_POST(self):
        if self.path == "/sms/send":
            f = self._form()
            req_key = (self.headers.get("X-Api-Key") or "").lower()
            print(f"[SMS-SEND] phone={f.get('phone')} idem={f.get('idempotency_key')} "
                  f"signName={f.get('sign_name')} apiKey={req_key} content={str(f.get('content'))[:40]}",
                  flush=True)
            if req_key != API_KEY.lower():
                print(f"[SMS-SEND] REJECT bad api key", flush=True)
                self._send({"error": "invalid api key"}, 401)
                return
            msg_id = f"mock-sms-{int(time.time() * 1000)}"
            SENT[msg_id] = "ACCEPTED"
            threading.Thread(target=fire_callback, args=(msg_id, "DELIVERED"), daemon=True).start()
            self._send({"message_id": msg_id, "status": "ACCEPTED"})
        elif self.path == "/sms/receipt":
            f = self._form()
            mid = f.get("message_id")
            print(f"[SMS-RECEIPT] message_id={mid}", flush=True)
            self._send({"message_id": mid, "status": SENT.get(mid, "UNKNOWN")})
        else:
            self._send({"error": "not found"}, 404)


if __name__ == "__main__":
    print(f"[MOCK-GW] listening :8090 callback={CALLBACK_URL}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", 8090), Handler).serve_forever()