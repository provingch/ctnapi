'use strict';
// Página de carga de ctnapi. Habla solo con el proxy local (server.py), que agrega la clave.

const NIVELES = { 1: 'Profesor', 2: 'Evaluador', 3: 'Administrador', 4: 'Padre / Encargado', 5: 'Coordinación Pedagógica' };
const ANIO = new Date().getFullYear();

const $ = (sel, el = document) => el.querySelector(sel);
const h = (tag, attrs = {}, ...hijos) => {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
    else if (v === true) el.setAttribute(k, '');
    else if (v !== false && v != null) el.setAttribute(k, v);
  }
  for (const c of hijos.flat()) if (c != null) el.append(c instanceof Node ? c : String(c));
  return el;
};

async function api(metodo, ruta, cuerpo) {
  const r = await fetch('/api/core/v1' + ruta, {
    method: metodo,
    headers: { 'X-Carga-Local': '1', ...(cuerpo ? { 'Content-Type': 'application/json' } : {}) },
    body: cuerpo ? JSON.stringify(cuerpo) : undefined,
  });
  let data = {};
  try { data = await r.json(); } catch { /* respuesta vacía */ }
  if (!r.ok || data.ok === false) {
    if (data.sinClave) pedirClave(data.error);
    const e = new Error(data.error || data.message || `Error ${r.status}`);
    e.status = r.status;
    throw e;
  }
  return data.datos;
}

async function sesion(metodo = 'GET', cuerpo) {
  const r = await fetch('/_sesion', {
    method: metodo,
    headers: { 'X-Carga-Local': '1', ...(cuerpo ? { 'Content-Type': 'application/json' } : {}) },
    body: cuerpo ? JSON.stringify(cuerpo) : undefined,
  });
  const d = await r.json().catch(() => ({}));
  if (!r.ok || !d.ok) throw new Error(d.error || `Error ${r.status}`);
  return d.datos;
}

// Pantalla de clave: se muestra la primera vez o si la clave guardada deja de valer.
function pedirClave(aviso) {
  $('#tabs').hidden = true;
  $('#salir').hidden = true;
  const input = h('input', { type: 'password', autocomplete: 'off', placeholder: 'Clave de carga', size: 40, required: true });
  const error = h('p', { class: 'err' }, aviso && !/falta/i.test(aviso) ? aviso : '');
  const f = h('form', { class: 'clave' },
    h('h2', {}, 'Ingresá la clave de carga'),
    h('p', { class: 'mute' }, 'Se pide una sola vez: queda guardada en esta computadora. Si no la tenés, pedila a Informática.'),
    h('div', { class: 'barra' }, input, h('button', { class: 'b', type: 'submit' }, 'Entrar')), error);
  f.addEventListener('submit', async (ev) => {
    ev.preventDefault();
    const btn = f.querySelector('button');
    btn.disabled = true;
    error.textContent = 'Verificando…';
    try {
      await sesion('POST', { clave: input.value });
      input.value = '';
      await entrar();
    } catch (e) {
      error.textContent = e.message;
      btn.disabled = false;
      input.select();
    }
  });
  $('#vista').replaceChildren(f);
  msg('');
  input.focus();
}

function msg(texto, tipo = 'ok') {
  const el = $('#msg');
  el.textContent = texto;
  el.className = tipo;
}

// ---------- catálogos compartidos ----------
const cat = { especialidades: [], cursos: [], cursosBase: [], materias: [], perfiles: [] };

async function cargarCatalogos() {
  [cat.especialidades, cat.cursos, cat.cursosBase, cat.materias, cat.perfiles] = await Promise.all([
    api('GET', '/especialidades'), api('GET', '/cursos'), api('GET', '/cursos-base'),
    api('GET', '/materias'), api('GET', '/perfiles'),
  ]);
}

const nombreEsp = (id) => cat.especialidades.find((e) => e.id === id)?.nombre ?? `#${id}`;
// Mismo cálculo que SCA: nivel = año - promoción + 3.
const nivelCurso = (c) => ANIO - c.promocion + 3;
const etiquetaCurso = (c) => {
  const n = nivelCurso(c);
  const nivel = n >= 1 && n <= 3 ? `${n}° ` : '';
  return `${nivel}${c.seccion} · ${c.especialidad ?? nombreEsp(c.especialidad_id)} (prom. ${c.promocion})`;
};
const etiquetaCursoBase = (c) => `${c.nivel}° ${c.seccion} · ${c.especialidad ?? nombreEsp(c.especialidad_id)}`;
const nombrePerfil = (p) => `${p.apellido ?? ''}, ${p.nombre ?? ''}`.replace(/^, |, $/, '') || `#${p.id}`;

// ---------- formularios genéricos ----------
// campo: { k, label, tipo: 'text'|'number'|'email'|'select'|'multi', req, opciones: () => [[valor, texto]], max }
function formulario(campos, valores, onGuardar, onCancelar) {
  const f = h('form', { class: 'f' });
  for (const c of campos) {
    const v = valores[c.k];
    let input;
    if (c.tipo === 'select') {
      input = h('select', { name: c.k, required: c.req },
        h('option', { value: '' }, c.req ? '— elegir —' : '(ninguno)'),
        c.opciones().map(([val, txt]) => h('option', { value: val, selected: String(val) === String(v ?? '') }, txt)));
    } else if (c.tipo === 'multi') {
      const sel = new Set((v ?? []).map(String));
      input = h('div', { class: 'multi', 'data-multi': c.k },
        c.opciones().map(([val, txt]) => h('label', {},
          h('input', { type: 'checkbox', value: val, checked: sel.has(String(val)) }), ' ', txt)));
    } else {
      input = h('input', { name: c.k, type: c.tipo || 'text', required: c.req, maxlength: c.max, value: v ?? '' });
    }
    f.append(h('label', {}, c.label + (c.req ? ' *' : ''), input));
  }
  f.append(h('div', { class: 'acc' }, h('button', { class: 'b', type: 'submit' }, 'Guardar'),
    h('button', { class: 's', type: 'button', onclick: onCancelar }, 'Cancelar')));
  f.addEventListener('submit', async (ev) => {
    ev.preventDefault();
    const datos = {};
    for (const c of campos) {
      if (c.tipo === 'multi') {
        datos[c.k] = [...f.querySelectorAll(`[data-multi="${c.k}"] input:checked`)].map((i) => Number(i.value));
        continue;
      }
      const raw = f.elements[c.k].value.trim();
      datos[c.k] = raw === '' ? null : (c.tipo === 'number' || c.num) ? Number(raw) : raw;
    }
    const btn = f.querySelector('button[type=submit]');
    btn.disabled = true;
    try { await onGuardar(datos); } catch (e) { msg(errorTexto(e), 'err'); } finally { btn.disabled = false; }
  });
  return f;
}

function errorTexto(e) {
  return e.message;
}

function tabla(columnas, filas, acciones) {
  if (!filas.length) return h('p', { class: 'mute' }, 'Sin resultados.');
  return h('div', { class: 'tabla' }, h('table', {},
    h('thead', {}, h('tr', {}, columnas.map(([t]) => h('th', {}, t)), acciones ? h('th') : null)),
    h('tbody', {}, filas.map((f) => h('tr', {},
      columnas.map(([, fn]) => h('td', {}, fn(f) ?? '')),
      acciones ? h('td', {}, acciones(f)) : null)))));
}

// ---------- vistas ----------
// Cada vista: { titulo, listar(filtros) -> filas, filtros?, columnas, campos, nuevo, aBody(datos), crear, editar, borrar }
const vistas = {
  alumnos: {
    titulo: 'Alumnos',
    filtros: () => [
      { k: 'cursoId', label: 'Curso', opciones: () => cat.cursos.map((c) => [c.id, etiquetaCurso(c)]) },
      { k: 'texto', label: 'Buscar (nombre o CI)', local: true },
    ],
    listar: async (f) => {
      const filas = await api('GET', '/alumnos' + (f.cursoId ? `?cursoId=${f.cursoId}` : ''));
      const t = (f.texto || '').toLowerCase();
      return t ? filas.filter((a) => `${a.ci} ${a.nombre} ${a.apellido}`.toLowerCase().includes(t)) : filas;
    },
    columnas: [['ID', (a) => a.id], ['CI', (a) => a.ci], ['Apellido', (a) => a.apellido], ['Nombre', (a) => a.nombre],
      ['Curso', (a) => { const c = cat.cursos.find((x) => x.id === a.curso_id); return c ? etiquetaCurso(c) : `#${a.curso_id}`; }],
      ['Email', (a) => a.email], ['Teléfono', (a) => a.telefono]],
    campos: () => [
      { k: 'ci', label: 'CI', max: 20 }, { k: 'nombre', label: 'Nombre', req: true, max: 45 },
      { k: 'apellido', label: 'Apellido', req: true, max: 45 },
      { k: 'curso_id', label: 'Curso', tipo: 'select', req: true, num: true, opciones: () => cat.cursos.map((c) => [c.id, etiquetaCurso(c)]) },
      { k: 'email', label: 'Email', tipo: 'email', max: 150 }, { k: 'telefono', label: 'Teléfono', max: 30 },
    ],
    aBody: (d) => ({ ci: d.ci, nombre: d.nombre, apellido: d.apellido, cursoId: d.curso_id, email: d.email, telefono: d.telefono }),
    ruta: '/alumnos',
    describir: (a) => `${a.apellido}, ${a.nombre} (CI ${a.ci ?? '—'})`,
  },

  perfiles: {
    titulo: 'Usuarios',
    filtros: () => [
      { k: 'nivel', label: 'Rol', opciones: () => Object.entries(NIVELES) },
      { k: 'texto', label: 'Buscar (nombre o CI)', local: true },
    ],
    listar: async (f) => {
      const filas = await api('GET', '/perfiles' + (f.nivel ? `?nivel=${f.nivel}` : ''));
      const t = (f.texto || '').toLowerCase();
      return t ? filas.filter((p) => `${p.ci} ${p.nombre} ${p.apellido}`.toLowerCase().includes(t)) : filas;
    },
    columnas: [['ID', (p) => p.id], ['CI', (p) => p.ci], ['Apellido', (p) => p.apellido], ['Nombre', (p) => p.nombre],
      ['Rol', (p) => NIVELES[p.nivel] ?? p.nivel], ['Especialidad', (p) => p.especialidad_id ? nombreEsp(p.especialidad_id) : ''],
      ['Correo', (p) => p.correo], ['Celular', (p) => p.celular], ['Teléfono', (p) => p.telefono]],
    campos: () => [
      { k: 'ci', label: 'CI', tipo: 'number' }, { k: 'nombre', label: 'Nombre', max: 45 }, { k: 'apellido', label: 'Apellido', max: 45 },
      { k: 'nivel', label: 'Rol', tipo: 'select', req: true, num: true, opciones: () => Object.entries(NIVELES) },
      { k: 'especialidad_id', label: 'Especialidad', tipo: 'select', num: true, opciones: () => cat.especialidades.map((e) => [e.id, e.nombre]) },
      { k: 'correo', label: 'Correo', tipo: 'email', max: 255 }, { k: 'celular', label: 'Celular', max: 45 },
      { k: 'telefono', label: 'Teléfono', max: 45 },
    ],
    aBody: (d) => ({ nombre: d.nombre, apellido: d.apellido, ci: d.ci, telefono: d.telefono, celular: d.celular,
      correo: d.correo, nivel: d.nivel, especialidadId: d.especialidad_id }),
    ruta: '/perfiles',
    describir: (p) => `${nombrePerfil(p)} (${NIVELES[p.nivel] ?? p.nivel})`,
    despues: async () => { cat.perfiles = await api('GET', '/perfiles'); },
  },

  cursos: {
    titulo: 'Cursos',
    soloAlta: true,
    nota: 'Curso = promoción (año de egreso) + sección, para los alumnos. Curso base = nivel + sección, para las asignaciones. '
      + 'Dar de alta uno que ya existe no lo duplica: devuelve el mismo ID.',
    listar: async () => {
      const [c, cb] = await Promise.all([api('GET', '/cursos'), api('GET', '/cursos-base')]);
      cat.cursos = c; cat.cursosBase = cb;
      return [...c.map((x) => ({ ...x, tipo: 'Curso', txt: etiquetaCurso(x) })),
        ...cb.map((x) => ({ ...x, tipo: 'Curso base', txt: etiquetaCursoBase(x) }))];
    },
    columnas: [['Tipo', (c) => c.tipo], ['ID', (c) => c.id], ['Curso', (c) => c.txt]],
    altas: [
      { boton: 'Nuevo curso', ruta: '/cursos', campos: () => [
        { k: 'especialidadId', label: 'Especialidad', tipo: 'select', req: true, num: true, opciones: () => cat.especialidades.map((e) => [e.id, e.nombre]) },
        { k: 'promocion', label: 'Promoción (año de egreso)', tipo: 'number', req: true },
        { k: 'seccion', label: 'Sección', tipo: 'select', req: true, opciones: () => [['A', 'A'], ['B', 'B'], ['C', 'C']] }] },
      { boton: 'Nuevo curso base', ruta: '/cursos-base', campos: () => [
        { k: 'especialidadId', label: 'Especialidad', tipo: 'select', req: true, num: true, opciones: () => cat.especialidades.map((e) => [e.id, e.nombre]) },
        { k: 'nivel', label: 'Nivel', tipo: 'select', req: true, num: true, opciones: () => [1, 2, 3, 4, 5, 6].map((n) => [n, `${n}°`]) },
        { k: 'seccion', label: 'Sección', tipo: 'select', req: true, opciones: () => [['A', 'A'], ['B', 'B'], ['C', 'C']] }] },
    ],
  },

  materias: {
    titulo: 'Materias',
    filtros: () => [{ k: 'texto', label: 'Buscar', local: true }],
    listar: async (f) => {
      cat.materias = await api('GET', '/materias');
      const t = (f.texto || '').toLowerCase();
      return t ? cat.materias.filter((m) => m.nombre.toLowerCase().includes(t)) : cat.materias;
    },
    columnas: [['ID', (m) => m.id], ['Nombre', (m) => m.nombre], ['Categoría', (m) => m.categoria],
      ['Especialidades', (m) => (m.especialidad_ids || []).map(nombreEsp).join(', ')]],
    campos: () => [
      { k: 'nombre', label: 'Nombre', req: true, max: 45 },
      { k: 'categoria', label: 'Categoría', tipo: 'select', req: true, opciones: () => [['comun', 'Común'], ['especifico', 'Específico']] },
      { k: 'especialidad_ids', label: 'Especialidades', tipo: 'multi', opciones: () => cat.especialidades.map((e) => [e.id, e.nombre]) },
    ],
    aBody: (d) => ({ nombre: d.nombre, categoria: d.categoria, especialidadIds: d.especialidad_ids }),
    ruta: '/materias',
    describir: (m) => m.nombre,
  },

  asignaciones: {
    titulo: 'Asignaciones',
    filtros: () => [
      { k: 'profesorId', label: 'Profesor', opciones: () => profesores().map((p) => [p.id, nombrePerfil(p)]) },
      { k: 'especialidadId', label: 'Especialidad', opciones: () => cat.especialidades.map((e) => [e.id, e.nombre]) },
    ],
    listar: (f) => {
      const q = new URLSearchParams(Object.entries(f).filter(([k, v]) => v && k !== 'texto'));
      return api('GET', '/asignaciones' + (q.size ? `?${q}` : ''));
    },
    columnas: [['ID', (a) => a.id], ['Profesor', (a) => `${a.profesor_apellido}, ${a.profesor_nombre}`],
      ['Materia', (a) => a.materia_nombre], ['Curso', (a) => `${a.nivel}° ${a.seccion} · ${a.especialidad}`]],
    campos: () => [
      { k: 'profesor_id', label: 'Profesor', tipo: 'select', req: true, num: true, opciones: () => profesores().map((p) => [p.id, nombrePerfil(p)]) },
      { k: 'materia_id', label: 'Materia', tipo: 'select', req: true, num: true, opciones: () => cat.materias.map((m) => [m.id, m.nombre]) },
      { k: 'curso_base_id', label: 'Curso', tipo: 'select', req: true, num: true, opciones: () => cat.cursosBase.map((c) => [c.id, etiquetaCursoBase(c)]) },
    ],
    aBody: (d) => ({ profesorId: d.profesor_id, materiaId: d.materia_id, cursoBaseId: d.curso_base_id }),
    ruta: '/asignaciones',
    describir: (a) => `${a.profesor_apellido} · ${a.materia_nombre} · ${a.nivel}° ${a.seccion}`,
  },

  importar: { titulo: 'Importar', render: (cont) => renderImportar(cont) },
};

// ---------- importación de planillas de Secretaría ("LISTAS <n> CURSO <año> SG.xls") ----------
const sinTildes = (s) => (s ?? '').normalize('NFD').replace(/[̀-ͯ]/g, '').toUpperCase().replace(/\s+/g, ' ').trim();
const soloDigitos = (s) => String(s ?? '').replace(/\D/g, '');

/** Compara la planilla con ctnapi. No escribe nada: devuelve el plan de cambios. */
function planificar(bloques, alumnos) {
  const porCi = new Map(), porNombre = new Map();
  for (const a of alumnos) {
    const ci = soloDigitos(a.ci);
    if (ci) porCi.set(ci, a);
    const k = sinTildes(`${a.apellido}, ${a.nombre}`);
    porNombre.set(k, [...(porNombre.get(k) ?? []), a]);
  }
  const ciEnPlanilla = new Map();
  for (const b of bloques) for (const f of b.alumnos) if (f.ci) ciEnPlanilla.set(f.ci, (ciEnPlanilla.get(f.ci) ?? 0) + 1);

  const filas = [], avisos = [], cursosDestino = new Set();
  for (const b of bloques) {
    const esp = cat.especialidades.find((e) => sinTildes(e.nombre) === sinTildes(b.especialidad));
    const destino = `${b.hoja} · ${b.nivel}° ${b.seccion ?? '?'} · ${b.especialidad}`;
    for (const a of b.avisos) avisos.push(`${destino}: ${a}`);
    let curso = null, errorBloque = null;
    if (!esp) errorBloque = `Especialidad «${b.especialidad}» no existe en ctnapi`;
    else if (!b.seccion) errorBloque = 'Bloque sin sección';
    else if (!b.anio) errorBloque = 'No se pudo determinar el año';
    else {
      // Mismo cálculo que SCA: nivel = año - promoción + 3  →  promoción = año - nivel + 3.
      const promocion = b.anio - b.nivel + 3;
      const existente = cat.cursos.find((c) => c.especialidad_id === esp.id && c.promocion === promocion && c.seccion === b.seccion);
      curso = existente ?? { id: null, especialidad_id: esp.id, especialidad: esp.nombre, promocion, seccion: b.seccion };
      if (existente) cursosDestino.add(existente.id);
    }
    for (const f of b.alumnos) {
      const fila = { ...f, destino, curso, accion: 'error', detalle: '' };
      filas.push(fila);
      if (errorBloque) { fila.detalle = errorBloque; continue; }
      if (!f.ci) { fila.detalle = 'Sin CI en la planilla'; continue; }
      if (ciEnPlanilla.get(f.ci) > 1) { fila.detalle = `CI ${f.ci} repetida en la planilla: corregir y volver a importar`; continue; }
      const actual = porCi.get(f.ci);
      if (!actual) {
        const homonimos = porNombre.get(sinTildes(`${f.apellido}, ${f.nombre}`)) ?? [];
        if (homonimos.length) {
          fila.accion = 'revisar';
          fila.detalle = `No hay alumno con CI ${f.ci}, pero existe con el mismo nombre: `
            + homonimos.map((h2) => `#${h2.id} (CI ${h2.ci ?? 'sin cargar'})`).join(', ') + '. No se crea para no duplicarlo.';
          continue;
        }
        fila.accion = 'crear';
        fila.detalle = curso.id ? '' : 'curso nuevo';
        continue;
      }
      fila.actual = actual;
      const nombreDistinto = sinTildes(`${actual.apellido}, ${actual.nombre}`) !== sinTildes(`${f.apellido}, ${f.nombre}`);
      const notaNombre = nombreDistinto ? `En ctnapi figura como «${actual.apellido}, ${actual.nombre}» (se conserva)` : '';
      if (curso.id && actual.curso_id === curso.id) {
        fila.accion = 'igual';
        fila.detalle = notaNombre;
      } else {
        const ant = cat.cursos.find((c) => c.id === actual.curso_id);
        fila.accion = 'mover';
        fila.detalle = [`desde ${ant ? etiquetaCurso(ant) : `#${actual.curso_id}`}`, notaNombre].filter(Boolean).join(' · ');
      }
    }
  }
  const enPlanilla = new Set(filas.map((f) => f.ci).filter(Boolean));
  const ausentes = alumnos.filter((a) => cursosDestino.has(a.curso_id) && !enPlanilla.has(soloDigitos(a.ci)));
  return { filas, avisos, ausentes };
}

const ACCIONES = {
  crear: 'Alta', mover: 'Cambia de curso', igual: 'Sin cambios', revisar: 'Revisar', error: 'Error',
};

function renderImportar(cont) {
  const zona = h('div');
  const input = h('input', { type: 'file', accept: '.xls,.xlsx,.ods' });
  cont.append(
    h('p', { class: 'mute' }, 'Planillas de Secretaría General («LISTAS 1er CURSO 2026 SG.xls»). '
      + 'Se identifica a cada alumno por la CI: los que no existen se dan de alta y los que cambiaron de curso se mueven. '
      + 'No se borra a nadie ni se pisan nombres, correos ni teléfonos ya cargados. Primero se muestra la vista previa.'),
    h('div', { class: 'barra' }, input), zona);

  input.addEventListener('change', async () => {
    const archivo = input.files[0];
    if (!archivo) return;
    zona.replaceChildren(h('p', { class: 'mute' }, 'Leyendo planilla…'));
    msg('');
    try {
      const r = await fetch('/_importar/leer', {
        method: 'POST', body: archivo,
        headers: { 'X-Carga-Local': '1', 'X-Nombre-Archivo': encodeURIComponent(archivo.name) },
      });
      const d = await r.json();
      if (!r.ok || !d.ok) throw new Error(d.error || `Error ${r.status}`);
      [cat.cursos, cat.especialidades] = await Promise.all([api('GET', '/cursos'), api('GET', '/especialidades')]);
      const alumnos = await api('GET', '/alumnos');
      mostrarPlan(zona, d.datos, planificar(d.datos.bloques, alumnos));
    } catch (e) {
      zona.replaceChildren(h('p', { class: 'err' }, errorTexto(e)));
    }
  });
}

function mostrarPlan(zona, leido, plan) {
  const cuenta = (acc) => plan.filas.filter((f) => f.accion === acc).length;
  const aplicables = plan.filas.filter((f) => f.accion === 'crear' || f.accion === 'mover');
  const filtro = h('select', { 'aria-label': 'Mostrar' },
    h('option', { value: 'cambios' }, 'Mostrar: cambios, errores y revisiones'),
    h('option', { value: 'todo' }, 'Mostrar: todo'));
  const tablaZona = h('div');
  const pintar = () => {
    const filas = filtro.value === 'todo' ? plan.filas : plan.filas.filter((f) => f.accion !== 'igual' || f.detalle);
    tablaZona.replaceChildren(tabla([
      ['Acción', (f) => h('span', { class: f.accion === 'error' ? 'err' : f.accion === 'revisar' ? 'aviso' : '' }, ACCIONES[f.accion])],
      ['CI', (f) => f.ci], ['Apellidos', (f) => f.apellido], ['Nombres', (f) => f.nombre],
      ['Curso destino', (f) => f.curso ? etiquetaCurso(f.curso) : f.destino], ['Detalle', (f) => f.detalle],
    ], filas));
  };
  filtro.addEventListener('change', pintar);

  const boton = h('button', { class: 'b', type: 'button', disabled: !aplicables.length }, `Aplicar ${aplicables.length} cambio(s)`);
  boton.addEventListener('click', async () => {
    if (!confirm(`Se van a dar de alta ${cuenta('crear')} alumno(s) y mover ${cuenta('mover')}. ¿Continuar?`)) return;
    boton.disabled = true;
    await aplicar(aplicables, (txt) => { boton.textContent = txt; });
  });

  zona.replaceChildren(
    h('p', {}, h('strong', {}, leido.archivo), ` · ${leido.bloques.length} sección(es) · ${plan.filas.length} fila(s): `,
      `${cuenta('crear')} alta(s), ${cuenta('mover')} cambio(s) de curso, ${cuenta('igual')} sin cambios, `,
      h('span', { class: 'aviso' }, `${cuenta('revisar')} a revisar`), ', ', h('span', { class: 'err' }, `${cuenta('error')} con error`), '.'),
    plan.avisos.length ? h('ul', { class: 'aviso' }, plan.avisos.map((a) => h('li', {}, a))) : '',
    plan.ausentes.length ? h('details', {}, h('summary', { class: 'aviso' },
      `${plan.ausentes.length} alumno(s) están en estos cursos en ctnapi pero no figuran en la planilla (no se tocan)`),
    tabla([['ID', (a) => a.id], ['CI', (a) => a.ci], ['Apellido', (a) => a.apellido], ['Nombre', (a) => a.nombre],
      ['Curso', (a) => { const c = cat.cursos.find((x) => x.id === a.curso_id); return c ? etiquetaCurso(c) : `#${a.curso_id}`; }]], plan.ausentes)) : '',
    h('div', { class: 'barra' }, boton, filtro), tablaZona);
  pintar();
}

async function aplicar(filas, progreso) {
  const cursoIds = new Map(); // "esp-prom-sec" → id
  let hechos = 0;
  const fallos = [];
  for (const f of filas) {
    progreso(`Aplicando… ${hechos + fallos.length + 1}/${filas.length}`);
    try {
      const c = f.curso;
      const clave = `${c.especialidad_id}-${c.promocion}-${c.seccion}`;
      if (!cursoIds.has(clave)) {
        // Idempotente: si el curso ya existe devuelve su ID.
        cursoIds.set(clave, c.id ?? (await api('POST', '/cursos', { especialidadId: c.especialidad_id, promocion: c.promocion, seccion: c.seccion })).id);
      }
      const cursoId = cursoIds.get(clave);
      if (f.accion === 'crear') {
        await api('POST', '/alumnos', { ci: f.ci, nombre: f.nombre, apellido: f.apellido, cursoId, email: null, telefono: null });
      } else {
        const a = f.actual;
        await api('PUT', `/alumnos/${a.id}`, { ci: a.ci, nombre: a.nombre, apellido: a.apellido, cursoId, email: a.email, telefono: a.telefono });
      }
      hechos++;
    } catch (e) {
      fallos.push(`${f.apellido}, ${f.nombre} (CI ${f.ci}): ${errorTexto(e)}`);
      if (e.status === 503 || e.status === 401) break; // escritura deshabilitada o clave mala: no tiene sentido seguir
    }
  }
  progreso(`Listo: ${hechos} aplicado(s)`);
  cat.cursos = await api('GET', '/cursos').catch(() => cat.cursos);
  if (fallos.length) msg(`${hechos} aplicado(s), ${fallos.length} con error: ${fallos.slice(0, 3).join(' | ')}${fallos.length > 3 ? ' …' : ''}`, 'err');
  else msg(`Importación terminada: ${hechos} cambio(s) aplicados.`);
}

const profesores = () => cat.perfiles.filter((p) => p.nivel === 1 || p.nivel === 5)
  .sort((a, b) => nombrePerfil(a).localeCompare(nombrePerfil(b)));

let actual = 'alumnos';
const filtrosActivos = {};

async function mostrar(nombre) {
  actual = nombre;
  for (const b of document.querySelectorAll('#tabs button')) b.classList.toggle('on', b.dataset.v === nombre);
  const v = vistas[nombre];
  const cont = $('#vista');
  cont.replaceChildren();
  if (v.render) return v.render(cont);

  const f = (filtrosActivos[nombre] ??= {});
  const zonaForm = h('div');
  const zonaTabla = h('div', {}, h('p', { class: 'mute' }, 'Cargando…'));
  const barra = h('div', { class: 'barra' });

  const abrirForm = (campos, valores, guardar) => {
    zonaForm.replaceChildren(formulario(campos, valores, async (d) => {
      await guardar(d);
      zonaForm.replaceChildren();
      await refrescar();
    }, () => zonaForm.replaceChildren()));
    zonaForm.scrollIntoView({ block: 'nearest' });
  };

  if (v.altas) {
    for (const a of v.altas) barra.append(h('button', { class: 'b', type: 'button', onclick: () => abrirForm(a.campos(), {}, async (d) => {
      const r = await api('POST', a.ruta, d);
      msg(`Guardado (ID ${r.id}).`);
    }) }, a.boton));
  } else {
    barra.append(h('button', { class: 'b', type: 'button', onclick: () => abrirForm(v.campos(), {}, async (d) => {
      const r = await api('POST', v.ruta, v.aBody(d));
      msg(`Creado: ID ${r.id}.`);
      await v.despues?.();
    }) }, 'Nuevo'));
  }

  for (const fl of v.filtros?.() ?? []) {
    const ctl = fl.local
      ? h('input', { type: 'search', placeholder: fl.label, value: f[fl.k] ?? '', oninput: (e) => { f[fl.k] = e.target.value; refrescar(true); } })
      : h('select', { 'aria-label': fl.label, onchange: (e) => { f[fl.k] = e.target.value; refrescar(); } },
        h('option', { value: '' }, `${fl.label}: todos`),
        fl.opciones().map(([val, txt]) => h('option', { value: val, selected: String(val) === String(f[fl.k] ?? '') }, txt)));
    barra.append(ctl);
  }
  const contador = h('span', { class: 'mute' });
  barra.append(contador);
  cont.append(barra);
  if (v.nota) cont.append(h('p', { class: 'mute' }, v.nota));
  cont.append(zonaForm, zonaTabla);

  let pedido = 0;
  async function refrescar(debounce) {
    const n = ++pedido;
    if (debounce) await new Promise((r) => setTimeout(r, 200));
    if (n !== pedido) return;
    try {
      const filas = await v.listar(f);
      if (n !== pedido) return;
      contador.textContent = `${filas.length} registro(s)`;
      zonaTabla.replaceChildren(tabla(v.columnas, filas, v.soloAlta ? null : (fila) => [
        h('button', { class: 's', type: 'button', onclick: () => abrirForm(v.campos(), fila, async (d) => {
          await api('PUT', `${v.ruta}/${fila.id}`, v.aBody(d));
          msg(`Actualizado: ${v.describir(fila)}.`);
          await v.despues?.();
        }) }, 'Editar'), ' ',
        h('button', { class: 's del', type: 'button', onclick: async () => {
          if (!confirm(`¿Eliminar ${v.describir(fila)}? No se puede deshacer.`)) return;
          try {
            await api('DELETE', `${v.ruta}/${fila.id}`);
            msg(`Eliminado: ${v.describir(fila)}.`);
            await v.despues?.();
            await refrescar();
          } catch (e) { msg(errorTexto(e), 'err'); }
        } }, 'Eliminar'),
      ]));
    } catch (e) {
      zonaTabla.replaceChildren(h('p', { class: 'err' }, errorTexto(e)));
    }
  }
  await refrescar();
}

async function entrar() {
  $('#tabs').hidden = false;
  $('#salir').hidden = false;
  try {
    await cargarCatalogos();
    $('#estado').textContent = `Conectado · ${cat.cursos.length} cursos, ${cat.perfiles.length} usuarios`;
  } catch (e) {
    $('#estado').textContent = '';
    if (!$('#tabs').hidden) msg(`No se pudo leer ctnapi: ${errorTexto(e)}`, 'err');
    return;
  }
  await mostrar(actual);
}

async function iniciar() {
  const tabs = $('#tabs');
  for (const [k, v] of Object.entries(vistas)) tabs.append(h('button', { type: 'button', 'data-v': k, onclick: () => { msg(''); mostrar(k); } }, v.titulo));
  $('#salir').addEventListener('click', async () => {
    if (!confirm('¿Olvidar la clave guardada en esta computadora? Se va a pedir de nuevo.')) return;
    await sesion('DELETE');
    $('#estado').textContent = '';
    pedirClave();
  });
  let s;
  try {
    s = await sesion();
  } catch (e) {
    msg(`No responde la herramienta local: ${e.message}`, 'err');
    return;
  }
  const el = $('#destino');
  el.textContent = s.nombre;
  el.title = s.url;
  if (s.conClave) await entrar(); else pedirClave();
}

iniciar();
