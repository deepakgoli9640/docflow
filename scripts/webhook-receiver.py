#!/usr/bin/env python3
"""A stand-in for a hospital's webhook endpoint.

Receives HPG webhooks, verifies the signature the way a hospital's own code would, and appends each
request to a file as JSON so a script (or you) can inspect it.

    HPG_WEBHOOK_SECRET=whsec_... ./scripts/webhook-receiver.py [port] [output-file]

Verification is exactly what docs/08-api-contracts.md asks hospitals to do:
  expected = hex(HMAC_SHA256("<X-HPG-Timestamp>.<raw body>", webhook_secret))
  compare with X-HPG-Signature, which is "v1=<expected>"
and reject anything older than five minutes so a captured request cannot be replayed later.
"""
import hashlib
import hmac
import json
import os
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 9099
OUTPUT = sys.argv[2] if len(sys.argv) > 2 else "/tmp/hpg-webhooks.jsonl"
SECRET = os.environ.get("HPG_WEBHOOK_SECRET", "")
MAX_AGE_SECONDS = 300


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_POST(self):  # noqa: N802  (http.server's required name)
        body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        timestamp = self.headers.get("X-HPG-Timestamp", "")
        provided = self.headers.get("X-HPG-Signature", "")
        expected = "v1=" + hmac.new(SECRET.encode(), f"{timestamp}.".encode() + body,
                                    hashlib.sha256).hexdigest()
        # compare_digest, not ==, so response time does not reveal how much of the signature matched
        signature_valid = hmac.compare_digest(expected, provided)
        fresh = timestamp.isdigit() and abs(time.time() - int(timestamp)) < MAX_AGE_SECONDS

        record = {
            "eventId": self.headers.get("X-HPG-Event-Id"),
            "eventType": self.headers.get("X-HPG-Event-Type"),
            "sequence": self.headers.get("X-HPG-Sequence"),
            "signatureValid": signature_valid,
            "timestampFresh": fresh,
            "body": json.loads(body) if body else None,
        }
        with open(OUTPUT, "a", encoding="utf-8") as output:
            output.write(json.dumps(record) + "\n")
        print(f"received {record['eventType']} signatureValid={signature_valid} fresh={fresh}", flush=True)

        # A hospital should reject an unverifiable request rather than act on it.
        self.send_response(200 if (signature_valid and fresh) else 401)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def log_message(self, *args):
        pass  # the prints above are the log


if __name__ == "__main__":
    if not SECRET:
        print("HPG_WEBHOOK_SECRET is not set; every request will fail verification", file=sys.stderr)
    print(f"Listening on 0.0.0.0:{PORT}, writing to {OUTPUT}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
