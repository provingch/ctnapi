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
    const e = new Error(data.error || data.message || `Error ${r.status}`);
    e.status = r.status;
    throw e;
  }
  return data.datos;
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
  if (e.status === 503) return `${e.message}. (La escritura se habilita en el corte: CORE_WRITE_ENABLED=true.)`;
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

  importar: {
    titulo: 'Importar',
    render: (cont) => cont.append(h('p', { class: 'mute' },
      'Importación de planillas: pendiente hasta definir el formato estándar.')),
  },
};

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

async function iniciar() {
  const tabs = $('#tabs');
  for (const [k, v] of Object.entries(vistas)) tabs.append(h('button', { type: 'button', 'data-v': k, onclick: () => { msg(''); mostrar(k); } }, v.titulo));
  try {
    const r = await fetch('/_destino', { headers: { 'X-Carga-Local': '1' } });
    const d = (await r.json()).datos;
    const el = $('#destino');
    el.textContent = d.nombre;
    el.title = d.url;
    el.className = /prod/i.test(d.nombre) ? 'aviso' : '';
  } catch { /* sin destino */ }
  try {
    await cargarCatalogos();
    $('#estado').textContent = `Conectado · ${cat.cursos.length} cursos, ${cat.perfiles.length} usuarios`;
  } catch (e) {
    $('#estado').textContent = '';
    msg(`No se pudo leer ctnapi: ${errorTexto(e)}`, 'err');
  }
  await mostrar(actual);
}

iniciar();
