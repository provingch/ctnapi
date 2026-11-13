# Herramienta de carga de datos (ctnapi)

Para los responsables de carga: alta y edición de alumnos, usuarios, cursos, materias y asignaciones, e
importación de las planillas de Secretaría. Se usa desde la PC de cada responsable y se conecta a
`https://ctapin.duckdns.org/api/carga/v1`.

## Instalar en una PC

Bajar el instalador de la última versión en GitHub (Releases, tag `carga-vX.Y.Z`):

- **Windows**: `CargaCTN-Setup-X.Y.Z.exe`. Se instala sin pedir permisos de administrador y crea un acceso
  directo en el escritorio. Se abre en su propia ventana, con el motor Edge WebView2 que ya trae Windows 10/11.
  Si no está disponible, se abre en el navegador. Como el instalador no está firmado, Windows SmartScreen
  puede avisar: «Más información» → «Ejecutar de todas formas».
- **Linux**: `CargaCTN-X.Y.Z-x86_64.AppImage`. Se marca como ejecutable (`chmod +x`) y se abre. Usa el
  navegador y una ventanita para cerrar el programa.

No hace falta Python ni LibreOffice: todo va incluido.

La primera vez pide la **clave de carga** (`CARGA_API_KEY` del servidor). Se valida contra ctnapi y queda
guardada en la PC (`%APPDATA%\ctnapi-carga\clave` o `~/.config/ctnapi-carga/clave`), solo legible por el
usuario. «Cambiar clave» la olvida.

## Publicar una versión nueva

1. Subir `VERSION` en `app.py`.
2. Mergear a `main` y crear el tag: `git tag carga-v1.0.1 && git push origin carga-v1.0.1`.
3. `.github/workflows/carga-instalador.yml` compila el `.exe` (Windows) y el `.AppImage` (Linux) y los
   publica en un Release. En los PRs quedan como artefactos del run, para probarlos antes.

## Desarrollo

Para correrlo sin compilar: `pip install -r requirements.txt` y `python3 server.py` (navegador) o
`python3 app.py` (ventana). Con un `config.ini` (ver `config.example.ini`) apunta a un ctnapi de prueba.

## Seguridad

- La clave de carga es **distinta** de la clave de servicio (`CORE_API_KEY`): para revocarla se cambia
  `CARGA_API_KEY` en `/etc/ctnapi/ctnapi.env` y se reinicia ctnapi; las PCs vuelven a pedirla.
- ctnapi bloquea por IP tras 5 claves incorrectas (30 s, luego 2, 10 y 30 min).
- La página solo habla con el proxy local (`server.py`), que escucha en 127.0.0.1, agrega la clave y rechaza
  pedidos de otros sitios (valida `Host`/`Origin`, header propio, sin CORS).

## Importar planillas de Secretaría

Pestaña **Importar**: acepta las «LISTAS <n> CURSO <año> SG.xls» (también .xlsx). La lectura está en `planilla.py`
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
