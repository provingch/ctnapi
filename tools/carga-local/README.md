# Carga local de datos (ctnapi)

Página para dar de alta y editar los datos del núcleo de ctnapi (alumnos, usuarios, cursos, materias y
asignaciones) **desde tu PC**. No se publica en ningún servidor.

`server.py` sirve la página en `http://127.0.0.1:8765` y reenvía `/api/core/v1/*` a ctnapi agregando el
`X-Service-Key`. La clave queda en `config.ini`, en tu PC, y nunca llega al navegador. El proxy solo
escucha en 127.0.0.1 y rechaza pedidos de otros sitios: valida `Host` y `Origin`, exige un header propio
y no aprueba preflights CORS.

## Uso

```bash
cd tools/carga-local
cp config.example.ini config.ini   # completar url y clave (CORE_API_KEY)
python3 server.py                  # Python 3.9+, sin dependencias
```

El nombre que pongas en `nombre` aparece arriba en la página, para no confundir prueba con producción.
Si contiene "prod", se resalta.

Para conectarte desde fuera de la red del colegio, usá un túnel SSH y `url = http://127.0.0.1:18080`:
`ssh -L 18080:192.168.100.102:8080 <host con acceso a la red interna>`.

## Antes del corte, solo lectura

Mientras SCA sea la fuente de verdad, ctnapi responde 503 a toda escritura (`CORE_WRITE_ENABLED=false`).
Además, la sync de cada 5 minutos **borra** de ctnapi lo que no exista en SCA. La herramienta sirve para
consultar, y para cargar solo contra un entorno de prueba.

Corte (fin de etapa escolar): última sync y después, en `/etc/ctnapi/ctnapi.env`,
`CORE_SYNC_ENABLED=false` y `CORE_WRITE_ENABLED=true`, y reiniciar `ctnapi`.

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
