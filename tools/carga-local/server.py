#!/usr/bin/env python3
"""Herramienta local de carga de datos para ctnapi.

Sirve la página en http://127.0.0.1:<puerto> y reenvía /api/core/v1/* a ctnapi agregando el
X-Service-Key. La clave queda en config.ini (en esta PC) y nunca llega al navegador.
Solo biblioteca estándar: `python3 server.py` (o `python3 server.py --config otra.ini`).
"""
import argparse
import configparser
import json
import ssl
import sys
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

AQUI = Path(__file__).resolve().parent
PREFIJO = "/api/core/v1/"
MAX_CUERPO = 1_000_000  # igual que el límite del Core en ctnapi


def cargar_config(ruta: Path) -> dict:
    if not ruta.is_file():
        sys.exit(f"No existe {ruta}. Copiá config.example.ini a config.ini y completalo.")
    cp = configparser.ConfigParser()
    cp.read(ruta, encoding="utf-8")
    s = cp["ctnapi"]
    url = s.get("url", "").rstrip("/")
    clave = s.get("clave", "").strip()
    if not url or not clave or clave.startswith("CAMBIAR"):
        sys.exit("Completá url y clave en la sección [ctnapi] de config.ini.")
    return {
        "url": url,
        "clave": clave,
        "nombre": s.get("nombre", url),
        "puerto": s.getint("puerto", 8765),
        "verificar_tls": s.getboolean("verificar_tls", True),
    }


class Handler(BaseHTTPRequestHandler):
    cfg: dict = {}
    ssl_ctx = None

    def log_message(self, fmt, *args):
        sys.stderr.write("[carga-local] " + (fmt % args) + "\n")

    # --- protección: el proxy tiene la clave, así que solo atiende a su propia página ---
    def _origen_valido(self) -> bool:
        puerto = self.cfg["puerto"]
        permitidos = {f"127.0.0.1:{puerto}", f"localhost:{puerto}"}
        if self.headers.get("Host") not in permitidos:  # evita DNS rebinding
            return False
        origen = self.headers.get("Origin")
        if origen is not None and origen.removeprefix("http://") not in permitidos:
            return False
        # Header propio: un sitio externo no puede mandarlo sin un preflight CORS que acá nunca se aprueba.
        return self.headers.get("X-Carga-Local") == "1"

    def _json(self, status: int, data: dict):
        body = json.dumps(data, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _archivo(self, nombre: str, tipo: str):
        body = (AQUI / nombre).read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", tipo)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Security-Policy",
                         "default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self'")
        self.end_headers()
        self.wfile.write(body)

    def _proxy(self):
        if not self._origen_valido():
            return self._json(403, {"ok": False, "error": "Pedido rechazado: solo se acepta desde la página local"})
        largo = int(self.headers.get("Content-Length") or 0)
        if largo > MAX_CUERPO:
            return self._json(413, {"ok": False, "error": "Cuerpo demasiado grande"})
        cuerpo = self.rfile.read(largo) if largo else None
        req = urllib.request.Request(self.cfg["url"] + self.path, data=cuerpo, method=self.command)
        req.add_header("X-Service-Key", self.cfg["clave"])
        req.add_header("Accept", "application/json")
        if cuerpo is not None:
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=30, context=self.ssl_ctx) as r:
                status, data = r.status, r.read()
        except urllib.error.HTTPError as e:
            status, data = e.code, e.read()
        except (urllib.error.URLError, TimeoutError) as e:
            return self._json(502, {"ok": False, "error": f"No se pudo conectar con ctnapi: {getattr(e, 'reason', e)}"})
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def _ruta(self):
        ruta = self.path.split("?", 1)[0]
        if self.command == "GET" and ruta in ("/", "/index.html"):
            return self._archivo("index.html", "text/html; charset=utf-8")
        if self.command == "GET" and ruta == "/app.js":
            return self._archivo("app.js", "text/javascript; charset=utf-8")
        if self.command == "GET" and ruta == "/_destino":
            if not self._origen_valido():
                return self._json(403, {"ok": False, "error": "Pedido rechazado"})
            return self._json(200, {"ok": True, "datos": {"nombre": self.cfg["nombre"], "url": self.cfg["url"]}})
        if ruta.startswith(PREFIJO) and ".." not in ruta:
            return self._proxy()
        return self._json(404, {"ok": False, "error": "No encontrado"})

    do_GET = do_POST = do_PUT = do_DELETE = _ruta

    def do_OPTIONS(self):  # nunca se aprueba un preflight: ningún otro sitio puede usar el proxy
        self._json(403, {"ok": False, "error": "CORS no permitido"})


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--config", default=str(AQUI / "config.ini"))
    cfg = cargar_config(Path(ap.parse_args().config))
    Handler.cfg = cfg
    Handler.ssl_ctx = ssl.create_default_context() if cfg["verificar_tls"] else ssl._create_unverified_context()
    srv = ThreadingHTTPServer(("127.0.0.1", cfg["puerto"]), Handler)
    print(f"Carga local → {cfg['nombre']} ({cfg['url']})")
    print(f"Abrí http://127.0.0.1:{cfg['puerto']}  (Ctrl+C para salir)")
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
