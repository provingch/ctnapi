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

## Pendiente

- Importación de planillas: falta definir el formato estándar.
