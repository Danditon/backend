#!/usr/bin/env python3
"""Disposable loopback server for the Nginx request-size boundary test."""

import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


class Handler(BaseHTTPRequestHandler):
    def do_POST(self) -> None:  # noqa: N802 - stdlib callback name
        remaining = int(self.headers.get("Content-Length", "0"))
        while remaining:
            chunk = self.rfile.read(min(remaining, 1024 * 1024))
            if not chunk:
                self.send_error(400)
                return
            remaining -= len(chunk)
        self.send_response(204)
        self.end_headers()

    def log_message(self, _format: str, *_args: object) -> None:
        pass


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: http-body-server.py PORT_FILE")
    with ThreadingHTTPServer(("127.0.0.1", 0), Handler) as server:
        Path(sys.argv[1]).write_text(str(server.server_port), encoding="ascii")
        server.serve_forever()


if __name__ == "__main__":
    main()
