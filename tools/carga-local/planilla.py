"""Lectura de las planillas de Secretaría General ("LISTAS <n> CURSO <año> SG.xls").

Formato: una hoja por especialidad y, dentro de cada hoja, un bloque por sección:

    Especialidad: Informática          Curso: 1er  Curso
    Turno: Mañana-Tarde          Sección: A
    Nº | Apellidos y Nombres            | C.I. Nº
    1  | ALVAREZ BENITEZ, EDGAR ARTURO  | 7090557
    ...
    Año 2026        Secretaria General CTN

El .xls (o .xlsx/.ods) se convierte a CSV con LibreOffice, que tiene que estar instalado.
"""
import csv
import re
import shutil
import subprocess
import tempfile
from pathlib import Path

RE_ESPECIALIDAD = re.compile(r"Especialidad:\s*(.+?)\s+Curso:\s*(\d+)", re.I)
RE_SECCION = re.compile(r"Secci[oó]n:\s*([A-Za-z])\b", re.I)
RE_ANIO = re.compile(r"\bA[ñn]o\s+(\d{4})\b", re.I)
RE_ANIO_ARCHIVO = re.compile(r"\b(20\d{2})\b")
EXTENSIONES = {".xls", ".xlsx", ".ods"}
# CSV UTF-8, separado por comas, todas las hojas (-1): un archivo <nombre>-<hoja>.csv por hoja.
FILTRO_CSV = "csv:Text - txt - csv (StarCalc):44,34,76,1,,0,false,true,false,false,false,-1"


class ErrorPlanilla(Exception):
    pass


def _soffice() -> str:
    for nombre in ("soffice", "libreoffice"):
        if ruta := shutil.which(nombre):
            return ruta
    for ruta in (r"C:\Program Files\LibreOffice\program\soffice.exe",
                 "/Applications/LibreOffice.app/Contents/MacOS/soffice"):
        if Path(ruta).is_file():
            return ruta
    raise ErrorPlanilla("No se encontró LibreOffice (soffice); hace falta para leer la planilla.")


def _a_csv(contenido: bytes, nombre: str, tmp: Path) -> list[Path]:
    ext = Path(nombre).suffix.lower()
    if ext not in EXTENSIONES:
        raise ErrorPlanilla(f"Formato no soportado: {ext or '(sin extensión)'}. Usar .xls, .xlsx u .ods.")
    origen = tmp / f"planilla{ext}"
    origen.write_bytes(contenido)
    salida = tmp / "csv"
    try:
        subprocess.run(
            [_soffice(), "--headless", "--norestore", f"-env:UserInstallation={(tmp / 'perfil').as_uri()}",
             "--convert-to", FILTRO_CSV, "--outdir", str(salida), str(origen)],
            check=True, capture_output=True, timeout=120)
    except subprocess.TimeoutExpired:
        raise ErrorPlanilla("LibreOffice tardó demasiado en convertir la planilla.")
    except subprocess.CalledProcessError as e:
        raise ErrorPlanilla(f"LibreOffice no pudo convertir la planilla: {e.stderr.decode(errors='replace')[:300]}")
    hojas = sorted(salida.glob("*.csv"))
    if not hojas:
        raise ErrorPlanilla("La planilla no tiene hojas legibles.")
    return hojas


def _ci(valor: str) -> str:
    valor = valor.strip()
    if re.fullmatch(r"\d+\.0+", valor):  # número leído como decimal
        valor = valor.split(".")[0]
    return re.sub(r"\D", "", valor)


def _hoja(ruta: Path) -> list[dict]:
    nombre_hoja = ruta.stem.split("-", 1)[1].strip() if "-" in ruta.stem else ruta.stem
    bloques, actual, anio = [], None, None
    with ruta.open(encoding="utf-8", newline="") as f:
        for nro_fila, fila in enumerate(csv.reader(f), start=1):
            celdas = [c.strip() for c in fila]
            texto = "  ".join(c for c in celdas if c)
            if not texto:
                continue
            if m := RE_ANIO.search(texto):
                anio = int(m.group(1))
            if m := RE_ESPECIALIDAD.search(texto):
                actual = {"hoja": nombre_hoja, "especialidad": m.group(1).strip(), "nivel": int(m.group(2)),
                          "seccion": None, "anio": None, "alumnos": [], "avisos": []}
                bloques.append(actual)
                continue
            if actual is None:
                continue
            if m := RE_SECCION.search(texto):
                actual["seccion"] = m.group(1).upper()
                continue
            if len(celdas) >= 2 and re.fullmatch(r"\d+(\.0+)?", celdas[0]) and celdas[1]:
                completo = re.sub(r"\s+", " ", celdas[1]).strip()
                if "," in completo:
                    apellido, nombre = (p.strip() for p in completo.split(",", 1))
                else:
                    apellido, nombre = completo, ""
                    actual["avisos"].append(f"Fila {nro_fila}: «{completo}» no tiene coma entre apellidos y nombres.")
                actual["alumnos"].append({"fila": nro_fila, "apellido": apellido.upper(), "nombre": nombre.upper(),
                                          "ci": _ci(celdas[2]) if len(celdas) > 2 else ""})
    # El "Año NNNN" está al pie de cada bloque: vale para todos los bloques de la hoja.
    for b in bloques:
        b["anio"] = anio
        if not b["seccion"]:
            b["avisos"].append("No se encontró la sección del bloque.")
    return bloques


def leer(contenido: bytes, nombre: str) -> list[dict]:
    with tempfile.TemporaryDirectory(prefix="carga-ctnapi-") as d:
        bloques = [b for hoja in _a_csv(contenido, nombre, Path(d)) for b in _hoja(hoja)]
    if not bloques:
        raise ErrorPlanilla("No se encontró ningún bloque «Especialidad: … Curso: …» en la planilla.")
    anio_archivo = RE_ANIO_ARCHIVO.search(nombre)
    for b in bloques:
        if b["anio"] is None and anio_archivo:
            b["anio"] = int(anio_archivo.group(1))
    return bloques


if __name__ == "__main__":  # prueba: python3 planilla.py "LISTAS 1er CURSO 2026 SG.xls"
    import json
    import sys
    ruta = Path(sys.argv[1])
    for b in leer(ruta.read_bytes(), ruta.name):
        print(json.dumps({k: v for k, v in b.items() if k != "alumnos"} | {"alumnos": len(b["alumnos"])},
                         ensure_ascii=False))
