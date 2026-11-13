"""Achica la carpeta que genera PyInstaller en Linux quitando partes de Qt que el programa no usa.

PyInstaller copia muchas librerías de Qt (Quick3D, Multimedia, estilos QML…) y las traducciones de
Chromium a ~50 idiomas. La ventana solo usa Qt WebEngine Widgets. Este script:
  1. Calcula, con ldd, qué librerías de Qt6/lib necesitan los módulos de PyQt6 incluidos, los plugins y
     QtWebEngineProcess, y borra el resto de libQt6*.
  2. Borra los QML y las traducciones que no sean español o inglés.

Uso (después de pyinstaller):  python podar_qt.py dist/CargaCTN
"""
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

CONSERVAR_IDIOMAS = re.compile(r"^(es|en)([-_].*)?\.(pak|qm)$|^qt(base|webengine)?_(es|en)\.qm$")


def dependencias(archivo: Path, carpeta_lib: Path) -> set[str]:
    env = dict(os.environ, LD_LIBRARY_PATH=str(carpeta_lib))
    salida = subprocess.run(["ldd", str(archivo)], capture_output=True, text=True, env=env).stdout
    return {m.group(1) for m in re.finditer(r"^\s*(\S+)\s+=>", salida, re.M)}


def main(raiz: Path):
    qt = next(raiz.rglob("PyQt6/Qt6"))
    lib = qt / "lib"
    antes = sum(f.stat().st_size for f in raiz.rglob("*") if f.is_file())

    # Raíces: módulos PyQt6 incluidos, plugins y el proceso de WebEngine.
    pendientes = [p for p in qt.parent.glob("*.so") if p.is_file()]
    pendientes += [p for p in (qt / "plugins").rglob("*.so") if p.is_file()]
    pendientes += [p for p in (qt / "libexec").glob("*") if p.is_file()]
    necesarias, vistos = set(), set()
    while pendientes:
        actual = pendientes.pop()
        if actual in vistos:
            continue
        vistos.add(actual)
        for nombre in dependencias(actual, lib):
            if (lib / nombre).exists() and nombre not in necesarias:
                necesarias.add(nombre)
                pendientes.append(lib / nombre)

    borradas = 0
    for f in lib.glob("libQt6*"):
        if f.name not in necesarias and not any(n.startswith(f.name.split(".so")[0] + ".so") for n in necesarias):
            f.unlink()
            borradas += 1
    shutil.rmtree(qt / "qml", ignore_errors=True)
    for f in (qt / "translations").rglob("*"):
        if f.is_file() and not CONSERVAR_IDIOMAS.match(f.name):
            f.unlink()

    despues = sum(f.stat().st_size for f in raiz.rglob("*") if f.is_file())
    print(f"podar_qt: {borradas} librerías Qt quitadas, {antes / 2**20:.0f} MB → {despues / 2**20:.0f} MB")


if __name__ == "__main__":
    main(Path(sys.argv[1]))
