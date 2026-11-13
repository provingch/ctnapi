"""Lectura de las planillas de Secretaría General ("LISTAS <n> CURSO <año> SG.xls").

Formato: una hoja por especialidad y, dentro de cada hoja, un bloque por sección:

    Especialidad: Informática          Curso: 1er  Curso
    Turno: Mañana-Tarde          Sección: A
    Nº | Apellidos y Nombres            | C.I. Nº
    1  | ALVAREZ BENITEZ, EDGAR ARTURO  | 7090557
    ...
    Año 2026        Secretaria General CTN

Lee .xls (xlrd) y .xlsx (openpyxl) directamente; no hace falta LibreOffice.
"""
import io
import re
from pathlib import Path

RE_ESPECIALIDAD = re.compile(r"Especialidad:\s*(.+?)\s+Curso:\s*(\d+)", re.I)
RE_SECCION = re.compile(r"Secci[oó]n:\s*([A-Za-z])\b", re.I)
RE_ANIO = re.compile(r"\bA[ñn]o\s+(\d{4})\b", re.I)
RE_ANIO_ARCHIVO = re.compile(r"\b(20\d{2})\b")


class ErrorPlanilla(Exception):
    pass


def _texto(valor) -> str:
    if valor is None:
        return ""
    if isinstance(valor, float) and valor.is_integer():  # 7090557.0 → "7090557"
        return str(int(valor))
    return str(valor).strip()


def _hojas(contenido: bytes, nombre: str):
    """Devuelve [(nombre_hoja, filas)] con cada fila como lista de textos."""
    ext = Path(nombre).suffix.lower()
    try:
        if ext == ".xls":
            import xlrd
            libro = xlrd.open_workbook(file_contents=contenido)
            return [(h.name, [[_texto(c) for c in h.row_values(i)] for i in range(h.nrows)]) for h in libro.sheets()]
        if ext == ".xlsx":
            import openpyxl
            libro = openpyxl.load_workbook(io.BytesIO(contenido), read_only=True, data_only=True)
            try:
                return [(h.title, [[_texto(c) for c in fila] for fila in h.iter_rows(values_only=True)])
                        for h in libro.worksheets]
            finally:
                libro.close()
    except ImportError as e:
        raise ErrorPlanilla(f"Falta la librería para leer {ext}: {e.name}")
    except Exception as e:  # archivo dañado o con otro formato real
        raise ErrorPlanilla(f"No se pudo leer la planilla: {e}")
    raise ErrorPlanilla(f"Formato no soportado: {ext or '(sin extensión)'}. Usar .xls o .xlsx.")


def _ci(valor: str) -> str:
    valor = valor.strip()
    if re.fullmatch(r"\d+\.0+", valor):  # número leído como decimal
        valor = valor.split(".")[0]
    return re.sub(r"\D", "", valor)


def _hoja(nombre_hoja: str, filas: list[list[str]]) -> list[dict]:
    bloques, actual, anio = [], None, None
    for nro_fila, celdas in enumerate(filas, start=1):
        celdas = [c.strip() for c in celdas]
        texto = "  ".join(c for c in celdas if c)
        if not texto:
            continue
        if m := RE_ANIO.search(texto):
            anio = int(m.group(1))
        if m := RE_ESPECIALIDAD.search(texto):
            actual = {"hoja": nombre_hoja.strip(), "especialidad": m.group(1).strip(), "nivel": int(m.group(2)),
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
    bloques = [b for hoja, filas in _hojas(contenido, nombre) for b in _hoja(hoja, filas)]
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
