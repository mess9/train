"""Статический сервер для проверки: отдаёт сборку, /media/* — только с X-Api-Key,
/redirect — 302 на чужой домен, как Cloudflare Access при истёкшей сессии.

python serve.py [порт]
"""
import http.server
import pathlib
import sys

ROOT = pathlib.Path(__file__).parent
DIST = ROOT / "build" / "dist" / "wasmJs" / "productionExecutable"
MEDIA = ROOT / "media"
KEY = "spike"


class Handler(http.server.SimpleHTTPRequestHandler):
    extensions_map = {
        **http.server.SimpleHTTPRequestHandler.extensions_map,
        ".wasm": "application/wasm",
        ".mjs": "text/javascript",
        ".js": "text/javascript",
        ".webmanifest": "application/manifest+json",
    }

    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(DIST), **kwargs)

    def end_headers(self):
        self.send_header("Cache-Control", "no-cache")
        super().end_headers()

    def do_GET(self):
        path = self.path.split("?")[0]
        if path == "/redirect":
            self.send_response(302)
            self.send_header("Location", "https://example.cloudflareaccess.com/cdn-cgi/access/login")
            self.end_headers()
            return
        if path.startswith("/media/"):
            if self.headers.get("X-Api-Key") != KEY:
                self.send_error(401, "no key")
                return
            file = MEDIA / pathlib.Path(path).name
            if not file.is_file():
                self.send_error(404)
                return
            data = file.read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "image/gif")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        super().do_GET()


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8080
    print(f"serving {DIST} on 0.0.0.0:{port}", flush=True)
    http.server.ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
