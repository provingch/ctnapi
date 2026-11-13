# Herramienta de carga de datos (ctnapi)

Para los responsables de carga: alta y edición de alumnos, usuarios, cursos, materias y asignaciones, e
importación de las planillas de Secretaría. Se usa desde la PC de cada responsable y se conecta a
`https://ctapin.duckdns.org/api/carga/v1`.

## Instalar en una PC

1. Instalar **Python 3.10+** (python.org; en Windows marcar «Add python.exe to PATH») y, para importar
   planillas, **LibreOffice**.
2. Copiar esta carpeta (`tools/carga-local`) a la PC.
3. Abrir: Windows → doble clic en `iniciar.bat`; Linux/Mac → `python3 server.py`. Se abre el navegador solo.
4. La primera vez pide la **clave de carga** (`CARGA_API_KEY` del servidor). Se valida contra ctnapi y queda
   guardada en la PC (`%APPDATA%\ctnapi-carga\clave` o `~/.config/ctnapi-carga/clave`, solo legible por el usuario).
   «Cambiar clave» la olvida.

## Seguridad

- La clave de carga es **distinta** de la clave de servicio (`CORE_API_KEY`): para revocarla se cambia
  `CARGA_API_KEY` en `/etc/ctnapi/ctnapi.env` y se reinicia ctnapi; las PCs vuelven a pedirla.
- ctnapi bloquea por IP tras 5 claves incorrectas (30 s, luego 2, 10 y 30 min).
- La página solo habla con el proxy local (`server.py`), que escucha en 127.0.0.1, agrega la clave y rechaza
  pedidos de otros sitios (valida `Host`/`Origin`, header propio, sin CORS).
- `config.ini` es opcional y solo sirve para apuntar a un ctnapi de prueba.

## Importar planillas de Secretaría

Pestaña **Importar**: acepta las «LISTAS <n> CURSO <año> SG.xls» (también .xlsx/.ods). Necesita
**LibreOffice** instalado (`soffice`), que convierte la planilla. La lectura está en `planilla.py`
(`python3 planilla.py archivo.xls` muestra los bloques leídos).

Formato: una hoja por especialidad y un bloque por sección («Especialidad: … Curso: 1er Curso»,
«Sección: A», tabla Nº / «APELLIDOS, NOMBRES» / C.I.). El año sale del pie «Año 2026» o, si falta, del
nombre del archivo. Promoción = año − curso + 3, igual que en SCA.

Reglas:
- Se identifica a cada alumno por la CI. Si no existe, se da de alta. Si está en otro curso, se lo mueve
  (queda en `alumno_curso_historial`).
- **Nunca se borra a nadie** ni se pisan nombres, correos o teléfonos ya cargados. Las diferencias de nombre
  se muestran como aviso. Los alumnos que están en esos cursos pero no en la planilla se listan aparte.
- Error, no se aplica: CI vacía, CI repetida en la planilla, especialidad desconocida.
- Revisar, no se crea: la CI no existe pero hay un alumno con el mismo nombre (probablemente sin CI cargada).
- Si falta el curso (por ejemplo, la promoción nueva), se crea al aplicar.

Siempre muestra una vista previa y pide confirmación antes de escribir.
