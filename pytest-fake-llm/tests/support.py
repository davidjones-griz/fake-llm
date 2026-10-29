"""Helpers for the plugin's tests."""

import http.server
import json
import os
import threading
import urllib.request
from pathlib import Path

# The docker CLI's configuration and current context, read before any pytester run replaces HOME.
DOCKER_CONFIG = os.environ.get("DOCKER_CONFIG") or str(Path.home() / ".docker")


def post(url, body):
    """POSTs JSON as an app's SDK would, and returns the decoded JSON reply."""
    request = urllib.request.Request(url, data=json.dumps(body).encode(), headers={"content-type": "application/json"})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(request, timeout=10) as response:
        return json.loads(response.read())


ANTHROPIC_REQUEST = {"model": "m", "max_tokens": 64, "messages": [{"role": "user", "content": "Hi"}]}
OPENAI_REQUEST = {"model": "m", "messages": [{"role": "user", "content": "Hi"}]}
GEMINI_REQUEST = {"contents": [{"parts": [{"text": "Hi"}]}]}
HELLO = "Hello. I can help with the weather, a simple recipe, or a short how-to."


class Stub:
    """A local HTTP server that answers every request with one canned response, and keeps the requests' headers."""

    def __init__(self, status, body, content_type="application/json"):
        self.status, self.body, self.content_type = status, body, content_type
        self.headers = []
        stub = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def _answer(self):
                stub.headers.append(dict(self.headers))
                self.rfile.read(int(self.headers.get("content-length") or 0))
                payload = stub.body.encode()
                self.send_response(stub.status)
                self.send_header("content-type", stub.content_type)
                self.send_header("content-length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            do_GET = do_POST = _answer

            def log_message(self, *args):
                pass

        self._server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.url = f"http://127.0.0.1:{self._server.server_address[1]}"
        threading.Thread(target=self._server.serve_forever, daemon=True).start()

    def close(self):
        self._server.shutdown()
        self._server.server_close()
