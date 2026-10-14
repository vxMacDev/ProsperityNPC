#!/usr/bin/env python3
"""Look at staged resident skins in a browser. Loopback only, read only, no uploads.

    python3 tools/skins/preview_server.py <staging-dir> [port]

Serves one page listing every *.png in the staging folder, drawn large with sharp pixels.
Nothing leaves this machine. Stop it with Ctrl+C.
"""
import html
import http.server
import pathlib
import sys
import urllib.parse


def main():
    if len(sys.argv) not in (2, 3):
        print(__doc__)
        sys.exit(2)
    root = pathlib.Path(sys.argv[1]).resolve()
    port = int(sys.argv[2]) if len(sys.argv) == 3 else 8765
    if not root.is_dir():
        print("Not a folder:", root)
        sys.exit(1)

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            path = urllib.parse.unquote(urllib.parse.urlparse(self.path).path)
            if path == "/":
                names = sorted(p.name for p in root.glob("*.png") if p.is_file() and not p.is_symlink())
                cards = "".join(
                    '<figure><img src="/{0}" width="256" height="256"><figcaption>{1}</figcaption></figure>'.format(
                        urllib.parse.quote(n), html.escape(n)) for n in names)
                body = ("<!doctype html><meta charset=utf-8><title>Resident skins</title>"
                        "<style>body{font-family:sans-serif;background:#ddd;display:flex;flex-wrap:wrap;gap:16px;padding:16px}"
                        "img{image-rendering:pixelated;background:#fff}figure{margin:0}</style>" + cards).encode("utf-8")
                self.send(200, "text/html; charset=utf-8", body)
                return
            name = path.lstrip("/")
            target = root / name
            if ("/" in name or "\\" in name or not name.endswith(".png") or not target.is_file()
                    or target.is_symlink() or target.resolve().parent != root):
                self.send(404, "text/plain", b"Not found")
                return
            self.send(200, "image/png", target.read_bytes())

        def send(self, code, kind, body):
            self.send_response(code)
            self.send_header("Content-Type", kind)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *args):
            pass

    server = http.server.ThreadingHTTPServer(("127.0.0.1", port), Handler)
    print("Preview at http://127.0.0.1:%d/ (loopback only). Ctrl+C to stop." % port)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
