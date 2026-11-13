"""Carga CTN: programa de escritorio de los responsables de carga.

Levanta el proxy local (server.py) y muestra la página en una ventana propia (pywebview, motor Edge
WebView2 de Windows). Al cerrar la ventana termina el programa. Si la ventana no se puede crear, abre el
navegador y deja una ventanita para salir.
"""
import os
import sys
import webbrowser
from pathlib import Path

import server

VERSION = "1.0.2"
TITULO = f"Carga de datos CTN {VERSION}"


def ventana_de_respaldo(direccion: str):
    """Sin WebView2: navegador + ventanita para cerrar el programa."""
    webbrowser.open(direccion)
    try:
        import tkinter as tk
    except ImportError:  # sin Tk: sigue funcionando; se cierra desde la terminal o el administrador de tareas
        print(f"Carga de datos abierta en {direccion}. Ctrl+C para salir.")
        import threading
        threading.Event().wait()
        return
    raiz = tk.Tk()
    raiz.title(TITULO)
    raiz.resizable(False, False)
    tk.Label(raiz, text="La carga de datos está abierta en el navegador.", padx=20, pady=12).pack()
    tk.Button(raiz, text="Abrir de nuevo", command=lambda: webbrowser.open(direccion)).pack(pady=4)
    tk.Button(raiz, text="Cerrar el programa", command=raiz.destroy).pack(pady=(4, 14))
    raiz.mainloop()


def limpiar_entorno_para_hijos():
    """PyInstaller en Linux pone LD_LIBRARY_PATH apuntando a sus librerías empaquetadas; el navegador que
    abrimos lo heredaría y arrancaría con esas librerías viejas (falla o no abre). Este proceso ya cargó lo
    suyo al iniciar, así que se puede restaurar el valor original para los procesos hijos."""
    if getattr(sys, "frozen", False) and sys.platform.startswith("linux"):
        original = os.environ.pop("LD_LIBRARY_PATH_ORIG", None)
        if original is not None:
            os.environ["LD_LIBRARY_PATH"] = original
        else:
            os.environ.pop("LD_LIBRARY_PATH", None)


def main():
    limpiar_entorno_para_hijos()
    cfg = server.cargar_config(Path(server.AQUI) / "config.ini")
    srv = server.iniciar_servidor(cfg)
    direccion = f"http://127.0.0.1:{cfg['puerto']}"
    try:
        try:
            import webview
            webview.create_window(TITULO, direccion, width=1280, height=820, min_size=(900, 600))
            webview.start()
        except Exception as e:  # sin WebView2 u otro problema de la ventana
            sys.stderr.write(f"No se pudo abrir la ventana ({e}); se usa el navegador.\n")
            ventana_de_respaldo(direccion)
    finally:
        srv.shutdown()


if __name__ == "__main__":
    main()
