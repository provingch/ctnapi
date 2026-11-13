#!/usr/bin/env python3
"""Herramienta de carga de datos de ctnapi para los responsables de carga.

Sirve la página en http://127.0.0.1:<puerto> (abre el navegador sola) y reenvía los pedidos a la API
pública de carga de ctnapi (/api/carga/v1) agregando la clave. La clave se pide la primera vez en la
página, se valida contra ctnapi y se guarda en esta PC; después no se vuelve a pedir.
Solo biblioteca estándar: `python3 server.py` (Windows: doble clic en iniciar.bat).
"""
import argparse
import configparser
import json
import os
import ssl
import sys
import threading
import webbrowser
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import planilla

AQUI = Path(__file__).resolve().parent
PREFIJO = "/api/core/v1/"          # rutas que usa la página
PREFIJO_REMOTO = "/api/carga/v1/"  # API pública de carga en ctnapi
URL_DEFECTO = "https://ctapin.duckdns.org"
MAX_CUERPO = 1_000_000  # igual que el límite del Core en ctnapi
MAX_PLANILLA = 20_000_000


def ruta_clave() -> Path:
    base = Path(os.environ.get("APPDATA") or Path.home() / ".config")
    return base / "ctnapi-carga" / "clave"


def leer_clave() -> str:
    try:
        return ruta_clave().read_text(encoding="utf-8").strip()
    except OSError:
        return ""


def guardar_clave(clave: str):
    ruta = ruta_clave()
    ruta.parent.mkdir(parents=True, exist_ok=True)
    tmp = ruta.with_suffix(".tmp")
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        f.write(clave)
    os.replace(tmp, ruta)


def borrar_clave():
    try:
        ruta_clave().unlink()
    except FileNotFoundError:
        pass


def cargar_config(ruta: Path) -> dict:
    """config.ini es opcional (para pruebas): sin él se usa la URL pública y el puerto 8765."""
    s = {}
    if ruta.is_file():
        cp = configparser.ConfigParser()
        cp.read(ruta, encoding="utf-8")
        s = cp["ctnapi"] if cp.has_section("ctnapi") else {}
    url = (s.get("url") or URL_DEFECTO).rstrip("/")
    return {
        "url": url,
        "nombre": s.get("nombre") or "CTN",
        "puerto": int(s.get("puerto") or 8765),
        "verificar_tls": str(s.get("verificar_tls", "true")).lower() != "false",
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
        clave = leer_clave()
        if not clave:
            return self._json(401, {"ok": False, "error": "Falta la clave de carga", "sinClave": True})
        status, data = self._remoto(self.command, PREFIJO_REMOTO + self.path[len(PREFIJO):], clave, cuerpo)
        if status == 401:  # la clave guardada dejó de valer (se cambió en el servidor)
            data = json.dumps({"ok": False, "error": "La clave guardada ya no es válida. Ingresala de nuevo.",
                               "sinClave": True}, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def _remoto(self, metodo: str, ruta: str, clave: str, cuerpo: bytes | None = None) -> tuple[int, bytes]:
        req = urllib.request.Request(self.cfg["url"] + ruta, data=cuerpo, method=metodo)
        req.add_header("X-Service-Key", clave)
        req.add_header("Accept", "application/json")
        if cuerpo is not None:
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=30, context=self.ssl_ctx) as r:
                return r.status, r.read()
        except urllib.error.HTTPError as e:
            return e.code, e.read()
        except (urllib.error.URLError, TimeoutError) as e:
            return 502, json.dumps({"ok": False, "error": f"No se pudo conectar con ctnapi ({self.cfg['url']}): "
                                    f"{getattr(e, 'reason', e)}"}, ensure_ascii=False).encode()

    def _sesion(self):
        """GET: ¿hay clave guardada?  POST {clave}: la valida contra ctnapi y la guarda.  DELETE: la olvida."""
        if not self._origen_valido():
            return self._json(403, {"ok": False, "error": "Pedido rechazado"})
        if self.command == "GET":
            return self._json(200, {"ok": True, "datos": {"conClave": bool(leer_clave()),
                                                          "nombre": self.cfg["nombre"], "url": self.cfg["url"]}})
        if self.command == "DELETE":
            borrar_clave()
            return self._json(200, {"ok": True, "datos": {"conClave": False}})
        largo = int(self.headers.get("Content-Length") or 0)
        try:
            clave = str(json.loads(self.rfile.read(min(largo, 10_000)) or b"{}").get("clave", "")).strip()
        except (ValueError, AttributeError):
            clave = ""
        if not clave:
            return self._json(400, {"ok": False, "error": "Ingresá la clave"})
        status, data = self._remoto("GET", PREFIJO_REMOTO + "especialidades", clave)
        if status == 200:
            guardar_clave(clave)
            return self._json(200, {"ok": True, "datos": {"conClave": True}})
        try:
            error = json.loads(data).get("error")
        except (ValueError, AttributeError):
            error = None
        mensajes = {401: "Clave incorrecta", 503: "La API de carga no está habilitada en el servidor"}
        return self._json(status if status in (401, 429, 502, 503) else 502,
                          {"ok": False, "error": mensajes.get(status) or error or f"Error {status} de ctnapi"})

    def _leer_planilla(self):
        """Recibe la planilla (cuerpo crudo) y devuelve los bloques leídos. No escribe nada en ctnapi."""
        if not self._origen_valido():
            return self._json(403, {"ok": False, "error": "Pedido rechazado"})
        largo = int(self.headers.get("Content-Length") or 0)
        if not 0 < largo <= MAX_PLANILLA:
            return self._json(413, {"ok": False, "error": "Archivo vacío o mayor a 20 MB"})
        nombre = Path(urllib.parse.unquote(self.headers.get("X-Nombre-Archivo", "planilla.xls"))).name
        try:
            bloques = planilla.leer(self.rfile.read(largo), nombre)
        except planilla.ErrorPlanilla as e:
            return self._json(422, {"ok": False, "error": str(e)})
        return self._json(200, {"ok": True, "datos": {"archivo": nombre, "bloques": bloques}})

    def _ruta(self):
        ruta = self.path.split("?", 1)[0]
        if self.command == "GET" and ruta in ("/", "/index.html"):
            return self._archivo("index.html", "text/html; charset=utf-8")
        if self.command == "GET" and ruta == "/app.js":
            return self._archivo("app.js", "text/javascript; charset=utf-8")
        if ruta == "/_sesion" and self.command in ("GET", "POST", "DELETE"):
            return self._sesion()
        if self.command == "POST" and ruta == "/_importar/leer":
            return self._leer_planilla()
        if ruta.startswith(PREFIJO) and ".." not in ruta:
            return self._proxy()
        return self._json(404, {"ok": False, "error": "No encontrado"})

    do_GET = do_POST = do_PUT = do_DELETE = _ruta

    def do_OPTIONS(self):  # nunca se aprueba un preflight: ningún otro sitio puede usar el proxy
        self._json(403, {"ok": False, "error": "CORS no permitido"})


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--config", default=str(AQUI / "config.ini"), help="opcional: url/puerto distintos (pruebas)")
    ap.add_argument("--no-abrir", action="store_true", help="no abrir el navegador")
    a = ap.parse_args()
    cfg = cargar_config(Path(a.config))
    Handler.cfg = cfg
    Handler.ssl_ctx = ssl.create_default_context() if cfg["verificar_tls"] else ssl._create_unverified_context()
    try:
        srv = ThreadingHTTPServer(("127.0.0.1", cfg["puerto"]), Handler)
    except OSError:
        sys.exit(f"El puerto {cfg['puerto']} está ocupado: ¿ya está abierta la herramienta? Probá en http://127.0.0.1:{cfg['puerto']}")
    direccion = f"http://127.0.0.1:{cfg['puerto']}"
    print(f"Carga de datos → {cfg['url']}")
    print(f"Abierto en {direccion}  (cerrá esta ventana o Ctrl+C para salir)")
    if not a.no_abrir:
        threading.Timer(0.5, webbrowser.open, args=(direccion,)).start()
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
