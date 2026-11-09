// Overlook: frontend sem build. Só fala com a API local em /api.
import uPlot from './vendor/uplot/uPlot.esm.js';

const CSRF = document.querySelector('meta[name="overlook-csrf"]').content;
const DEMO = document.querySelector('meta[name="overlook-demo"]').content === 'true';

// Ordem validada para daltonismo (dataviz validate_palette): violeta, rosa, amarelo, aqua,
// laranja, azul, vermelho, verde. Cada modo tem seus próprios tons; nunca gerar uma 9ª cor.
const PALETTE_LIGHT = ['#7048e8', '#d6398a', '#eda100', '#1baf7a', '#eb6834', '#2a78d6', '#e34948', '#008300'];
const PALETTE_DARK = ['#8a6cf0', '#e0559b', '#c98500', '#199e70', '#d95926', '#3987e5', '#e66767', '#008300'];
const darkQuery = window.matchMedia('(prefers-color-scheme: dark)');
const palette = () => (darkQuery.matches ? PALETTE_DARK : PALETTE_LIGHT);
const OTHERS_COLOR = '#9a92a8';
const DIMENSION_TITLES = {
  pages: ['Páginas mais acessadas', 'Página'],
  referrers: ['Referrers', 'Origem'],
  countries: ['Países', 'País'],
  devices: ['Dispositivos', 'Dispositivo'],
  events: ['Eventos', 'Evento'],
};
const ANALYTICS_DOCS = 'https://vercel.com/docs/analytics/quickstart';

const nf = new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 0 });
const nfCompact = new Intl.NumberFormat('pt-BR', { notation: 'compact', maximumFractionDigits: 1 });
const pf = new Intl.NumberFormat('pt-BR', { style: 'percent', maximumFractionDigits: 1, signDisplay: 'exceptZero' });
const dayFmt = new Intl.DateTimeFormat('pt-BR', { day: '2-digit', month: 'short', timeZone: 'UTC' });
const hourFmt = new Intl.DateTimeFormat('pt-BR', { hour: '2-digit', minute: '2-digit', timeZone: 'UTC' });
const stampFmt = new Intl.DateTimeFormat('pt-BR', { dateStyle: 'short', timeStyle: 'medium' });

const $ = (sel) => document.querySelector(sel);

// ------------------------------------------------------------------ estado na URL

const state = {
  scope: null,
  range: null,
  since: null,
  until: null,
  env: null,
  projects: new Set(),
  project: null,
};
let settings = null;
let capabilities = null;
let projectList = [];
let charts = [];
let retryTimer = null;
let refreshTimer = null;
let loadToken = 0;

function readUrl() {
  const p = new URLSearchParams(location.search);
  state.scope = p.get('scope');
  state.range = p.get('range');
  state.since = p.get('since');
  state.until = p.get('until');
  state.env = p.get('env');
  state.projects = new Set((p.get('projects') || '').split(',').filter(Boolean));
  state.project = p.get('project');
}

function writeUrl(push = true) {
  const p = new URLSearchParams();
  if (state.scope) p.set('scope', state.scope);
  if (state.range === 'custom') {
    p.set('range', 'custom');
    if (state.since) p.set('since', state.since);
    if (state.until) p.set('until', state.until);
  } else if (state.range) {
    p.set('range', state.range);
  }
  if (state.env) p.set('env', state.env);
  if (state.projects.size) p.set('projects', [...state.projects].join(','));
  if (state.project) p.set('project', state.project);
  const url = `${location.pathname}?${p}`;
  if (push) history.pushState(null, '', url); else history.replaceState(null, '', url);
}

function queryParams(extra = {}) {
  const p = new URLSearchParams();
  if (state.scope) p.set('scope', state.scope);
  if (state.range === 'custom') {
    if (state.since) p.set('since', state.since);
    if (state.until) p.set('until', state.until);
  } else if (state.range) {
    p.set('range', state.range);
  }
  if (state.env) p.set('env', state.env);
  for (const [k, v] of Object.entries(extra)) if (v != null) p.set(k, v);
  return p.toString();
}

// ------------------------------------------------------------------ API

class ApiError extends Error {
  constructor(status, body) {
    super(body?.error?.message || `Erro ${status}`);
    this.status = status;
    this.code = body?.error?.code || `HTTP_${status}`;
    this.action = body?.error?.action || null;
    this.retryAfterSeconds = body?.error?.retryAfterSeconds || null;
  }
}

async function api(path, { method = 'GET', body } = {}) {
  const headers = { Accept: 'application/json' };
  if (method !== 'GET') {
    headers['X-Overlook-Csrf'] = CSRF;
    if (body !== undefined) headers['Content-Type'] = 'application/json';
  }
  let res;
  try {
    res = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  } catch {
    throw new ApiError(0, { error: { code: 'OFFLINE', message: 'O Overlook parou de responder.', action: 'Confira se o processo ainda está rodando.' } });
  }
  if (res.status === 204) return null;
  const data = await res.json().catch(() => null);
  if (!res.ok) throw new ApiError(res.status, data);
  return data;
}

// ------------------------------------------------------------------ helpers de DOM

function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v == null || v === false) continue;
    if (k === 'class') node.className = v;
    else if (k === 'text') node.textContent = v;
    else if (k.startsWith('on')) node.addEventListener(k.slice(2), v);
    else node.setAttribute(k, v === true ? '' : v);
  }
  for (const c of children.flat()) {
    if (c == null || c === false) continue;
    node.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
  return node;
}

function errorBlock(err, onRetry) {
  const box = el('div', { class: 'block-error', role: 'alert' },
    el('strong', { text: err.message }),
    err.action ? el('span', { class: 'action', text: err.action }) : null);
  if (err.retryAfterSeconds && onRetry) {
    const counter = el('span', { class: 'action' });
    box.append(counter);
    let left = err.retryAfterSeconds;
    const tick = () => {
      counter.textContent = `Nova tentativa em ${left}s…`;
      if (left-- <= 0) { clearInterval(retryTimer); onRetry(); }
    };
    clearInterval(retryTimer);
    retryTimer = setInterval(tick, 1000);
    tick();
  } else if (onRetry) {
    box.append(el('div', {}, el('button', { class: 'link', type: 'button', onclick: onRetry, text: 'Tentar de novo' })));
  }
  return box;
}

function delta(change) {
  if (change == null || !isFinite(change)) return null;
  const cls = Math.abs(change) < 0.005 ? 'flat' : change > 0 ? 'up' : 'down';
  const arrow = cls === 'up' ? '▲' : cls === 'down' ? '▼' : '';
  return el('span', { class: `delta ${cls}`, title: 'Contra o período anterior, só com buckets fechados' },
    `${arrow} ${pf.format(change)}`.trim());
}

function fmt(n) {
  return n == null ? '–' : nf.format(n);
}

function rangeLabel() {
  if (state.range === 'custom') return 'período personalizado';
  return { '24h': 'últimas 24h', '7d': 'últimos 7 dias', '30d': 'últimos 30 dias', '90d': 'últimos 90 dias' }[state.range] || '';
}

// ------------------------------------------------------------------ cabeçalho

function syncControls() {
  for (const b of document.querySelectorAll('#range-group button')) {
    b.setAttribute('aria-checked', String(b.dataset.range === state.range));
  }
  for (const b of document.querySelectorAll('#env-group button')) {
    b.setAttribute('aria-checked', String(b.dataset.env === state.env));
  }
  $('#custom-range').hidden = state.range !== 'custom';
  $('#since-input').value = state.since || '';
  $('#until-input').value = state.until || '';
  $('#scope-select').value = state.scope || '';
  const n = state.projects.size;
  $('#project-picker-label').textContent = n === 0 ? 'Todos os projetos' : n === 1 ? '1 projeto' : `${n} projetos`;
  for (const input of document.querySelectorAll('#picker-list input')) {
    input.checked = state.projects.has(input.value);
  }
  $('#project-picker').hidden = !!state.project;
}

function buildPicker() {
  const list = $('#picker-list');
  list.replaceChildren(...projectList.map((p) =>
    el('label', {},
      el('input', { type: 'checkbox', value: p.id, onchange: (e) => {
        if (e.target.checked) state.projects.add(p.id); else state.projects.delete(p.id);
        writeUrl();
        syncControls();
        render();
      } }),
      el('span', { text: p.name }))));
}

function wireControls() {
  for (const b of document.querySelectorAll('#range-group button')) {
    b.addEventListener('click', () => {
      state.range = b.dataset.range;
      if (state.range === 'custom') {
        if (!state.since) {
          const d = new Date(Date.now() - 13 * 86400e3);
          state.since = d.toISOString().slice(0, 10);
          state.until = new Date().toISOString().slice(0, 10);
        }
        syncControls();
        return;
      }
      state.since = state.until = null;
      writeUrl();
      syncControls();
      render();
    });
  }
  $('#apply-custom').addEventListener('click', () => {
    state.since = $('#since-input').value || null;
    state.until = $('#until-input').value || null;
    writeUrl();
    render();
  });
  for (const b of document.querySelectorAll('#env-group button')) {
    b.addEventListener('click', () => {
      state.env = b.dataset.env;
      writeUrl();
      syncControls();
      render();
    });
  }
  $('#scope-select').addEventListener('change', async (e) => {
    state.scope = e.target.value;
    state.projects.clear();
    state.project = null;
    writeUrl();
    await loadScopeData();
    render();
  });
  $('#picker-all').addEventListener('click', () => {
    state.projects.clear();
    writeUrl();
    syncControls();
    render();
  });
  $('#picker-none').addEventListener('click', () => {
    state.projects.clear();
    writeUrl();
    syncControls();
    render();
  });
  document.addEventListener('click', (e) => {
    const picker = $('#project-picker');
    if (picker.open && !picker.contains(e.target)) picker.open = false;
  });
  $('#refresh-btn').addEventListener('click', async () => {
    const btn = $('#refresh-btn');
    btn.classList.add('spinning');
    try {
      await api('/api/refresh', { method: 'POST' });
      await boot(false);
    } finally {
      btn.classList.remove('spinning');
    }
  });
  $('#back-link').addEventListener('click', (e) => {
    e.preventDefault();
    state.project = null;
    writeUrl();
    syncControls();
    render();
  });
  $('#brand-link').addEventListener('click', (e) => {
    e.preventDefault();
    state.project = null;
    state.projects.clear();
    writeUrl();
    syncControls();
    render();
  });
  window.addEventListener('popstate', () => {
    readUrl();
    syncControls();
    render();
  });
  wireSettings();
}

// ------------------------------------------------------------------ saúde da CLI

function showHealthBanner(err, cli) {
  const banner = $('#health-banner');
  const actions = el('div', {},
    el('button', { class: 'btn small', type: 'button', onclick: () => boot(false), text: 'Tentar de novo' }));
  if (err.code === 'CLI_NOT_FOUND') {
    actions.append(' ', el('button', { class: 'btn small', type: 'button', onclick: openSettings, text: 'Informar caminho da CLI' }));
  }
  const detail = [];
  if (err.action) detail.push(err.action);
  if (cli?.version && cli?.minimumVersion && err.code === 'CLI_OUTDATED') {
    detail.push(`Versão atual: ${cli.version}. Mínima: ${cli.minimumVersion}.`);
  }
  banner.replaceChildren(
    el('div', {}, el('strong', { text: err.message }), detail.length ? el('span', { text: detail.join(' ') }) : null),
    actions);
  banner.hidden = false;
}

function hideHealthBanner() {
  $('#health-banner').hidden = true;
}

function showWarnings(list) {
  const box = $('#warnings');
  if (!list || !list.length) {
    box.hidden = true;
    return;
  }
  box.replaceChildren(...list.map((w) => el('p', { text: w })));
  box.hidden = false;
}

// ------------------------------------------------------------------ boot

async function boot(first = true) {
  hideHealthBanner();
  try {
    settings = await api('/api/settings');
    if (!state.range) state.range = settings.config.defaultRange;
    if (!state.env) state.env = settings.config.productionOnly ? 'production' : 'all';
    setupAutoRefresh();

    const health = await api('/api/health');
    $('#demo-badge').hidden = !health.app.demo;
    $('#app-version').textContent = health.app.version;
    const cli = health.cli;
    if (cli.errorCode) {
      showHealthBanner({ code: cli.errorCode, message: cli.errorMessage, action: cli.errorAction }, cli);
      hideViews();
      return;
    }
    const scopes = await api('/api/scopes');
    const select = $('#scope-select');
    select.replaceChildren(...scopes.scopes.map((s) =>
      el('option', { value: s.slug, text: s.name && s.name !== s.slug ? `${s.name} (${s.slug})` : s.slug })));
    if (!state.scope || !scopes.scopes.some((s) => s.slug === state.scope)) state.scope = scopes.defaultScope;
    writeUrl(false);
    await loadScopeData();
    syncControls();
    render();
  } catch (err) {
    showHealthBanner(err);
    hideViews();
  }
  if (first) wireControls();
}

async function loadScopeData() {
  const [projects, caps] = await Promise.all([
    api(`/api/projects?scope=${encodeURIComponent(state.scope)}`),
    api(`/api/capabilities?scope=${encodeURIComponent(state.scope)}`),
  ]);
  projectList = projects.projects.slice().sort((a, b) => a.name.localeCompare(b.name));
  capabilities = caps;
  buildPicker();
  syncControls();
}

function hideViews() {
  $('#view-overview').hidden = true;
  $('#view-project').hidden = true;
}

function setupAutoRefresh() {
  clearInterval(refreshTimer);
  const ar = settings?.config?.autoRefresh;
  if (ar?.enabled) {
    refreshTimer = setInterval(() => render(), Math.max(15, ar.intervalMinutes) * 60e3);
  }
}

function render() {
  clearInterval(retryTimer);
  destroyCharts();
  if (state.project) renderProject(); else renderOverview();
}

// ------------------------------------------------------------------ tela consolidada

function cardSkeleton() {
  return el('div', { class: 'card' }, el('div', { class: 'skeleton', text: '' }), el('div', { class: 'skeleton value' }));
}

function card({ label, value, previous, change, note, error, available = true }, onRetry) {
  const node = el('div', { class: 'card' }, el('span', { class: 'card-label', text: label }));
  if (error) {
    node.append(errorBlock(error, onRetry));
    return node;
  }
  if (!available) {
    node.append(el('span', { class: 'card-value muted', text: '–' }), note ? el('span', { class: 'card-note', text: note }) : null);
    return node;
  }
  node.append(el('span', { class: 'card-value', text: fmt(value) }));
  const meta = el('div', { class: 'card-meta' });
  const d = delta(change);
  if (d) meta.append(d);
  if (previous != null) meta.append(el('span', { text: `anterior: ${fmt(previous)}` }));
  node.append(meta);
  if (note) node.append(el('span', { class: 'card-note', text: note }));
  return node;
}

async function renderOverview() {
  const token = ++loadToken;
  $('#view-project').hidden = true;
  $('#view-overview').hidden = false;
  $('#cards').replaceChildren(cardSkeleton(), cardSkeleton(), cardSkeleton());
  $('#overview-chart').replaceChildren(el('div', { class: 'skeleton chart' }));
  $('#overview-legend').replaceChildren();
  $('#ranking').replaceChildren(el('div', { class: 'skeleton' }), el('div', { class: 'skeleton' }));
  $('#chart-note').textContent = rangeLabel();
  showWarnings([]);

  let data;
  try {
    data = await api(`/api/overview?${queryParams()}`);
  } catch (err) {
    if (token !== loadToken) return;
    const retry = () => renderOverview();
    $('#cards').replaceChildren(card({ label: 'Page views', error: err }, retry));
    $('#overview-chart').replaceChildren(errorBlock(err));
    $('#ranking').replaceChildren(errorBlock(err));
    return;
  }
  if (token !== loadToken) return;
  $('#fetched-at').textContent = `Dados de ${stampFmt.format(new Date(data.fetchedAt))}${data.stale ? ' (cache)' : ''}`;
  showWarnings(data.warnings);

  const selected = state.projects;
  const rows = selected.size ? data.projects.filter((r) => selected.has(r.id)) : data.projects;
  const { pageViews, events, visitors } = data.cards;
  const retry = () => renderOverview();
  if (selected.size) {
    const pv = rows.reduce((s, r) => s + r.pageViews, 0);
    const vis = rows.reduce((s, r) => s + (r.visitors || 0), 0);
    $('#cards').replaceChildren(
      card({ label: 'Page views', value: pv, note: 'Projetos selecionados. A variação aparece por projeto no ranking.' }),
      card({ ...events, note: 'Todos os projetos do escopo: a CLI não separa eventos por projeto nesta consulta.' }, retry),
      card({ ...visitors, value: visitors.available && !visitors.error ? vis : visitors.value, previous: null, change: null,
        note: visitors.available ? `Soma dos visitantes dos projetos selecionados. ${visitors.note || ''}` : visitors.note }, retry));
  } else {
    $('#cards').replaceChildren(card(pageViews, retry), card(events, retry), card(visitors, retry));
  }

  renderOverviewChart(data, selected);
  renderRanking(rows, data);
}

function renderOverviewChart(data, selected) {
  const container = $('#overview-chart');
  const legend = $('#overview-legend');
  let lines = data.chart.lines;
  if (selected.size) lines = lines.filter((l) => selected.has(l.id));
  if (!lines.length) {
    container.replaceChildren(el('div', { class: 'block-empty', text: 'Nenhum page view no período.' }));
    legend.replaceChildren();
    return;
  }
  const colors = lines.map((l, i) => (l.id === '_others' ? OTHERS_COLOR : palette()[i]));
  container.replaceChildren();
  drawChart(container, data.chart.buckets, lines.map((l) => ({ name: l.name, values: l.values })), colors,
    data.chart.openBucketIndex, data.range.granularity);
  legend.replaceChildren(...lines.map((l, i) =>
    el('span', { class: 'legend-item' }, swatch(colors[i]), l.name)));
  if (data.chart.openBucketIndex != null) {
    legend.append(el('span', { class: 'legend-item' }, swatch('var(--open-band)'), 'em andamento'));
  }
  const hiddenSelected = selected.size ? [...selected].filter((id) => !data.chart.lines.some((l) => l.id === id)) : [];
  if (hiddenSelected.length) {
    legend.append(el('span', { class: 'legend-item', text: `${hiddenSelected.length} selecionado(s) fora do top 8 do gráfico` }));
  }
  $('#chart-note').textContent = `${rangeLabel()} · buckets em UTC`;
}

function swatch(color) {
  const s = el('span', { class: 'legend-swatch' });
  s.style.background = color;
  return s;
}

function renderRanking(rows, data) {
  const target = $('#ranking');
  if (!rows.length) {
    target.replaceChildren(el('div', { class: 'block-empty', text: 'Nenhum projeto neste escopo.' }));
    return;
  }
  const max = Math.max(1, ...rows.map((r) => r.pageViews));
  const hasVisitors = data.cards.visitors.available && !data.cards.visitors.error;
  const head = el('tr', {},
    el('th', { text: 'Projeto' }),
    el('th', { class: 'bar-cell', text: '' }),
    el('th', { class: 'num', text: 'Page views' }),
    el('th', { class: 'num', text: 'Variação' }),
    hasVisitors ? el('th', { class: 'num', text: 'Visitantes' }) : null);
  const body = rows.map((r) => {
    const bar = el('div', { class: 'bar' });
    bar.style.width = `${(r.pageViews / max) * 100}%`;
    const tr = el('tr', { class: `clickable${r.hasData ? '' : ' no-data'}`, tabindex: '0',
      onclick: () => openProject(r.id),
      onkeydown: (e) => { if (e.key === 'Enter') openProject(r.id); } },
    el('td', {},
      el('span', { class: 'proj-name', text: r.name }),
      r.hasData ? null : el('span', { class: 'proj-sub' },
        'Sem dados no período. ',
        el('a', { href: ANALYTICS_DOCS, target: '_blank', rel: 'noopener noreferrer', onclick: (e) => e.stopPropagation(),
          text: 'Web Analytics está ativado?' }))),
    el('td', { class: 'bar-cell' }, el('div', { class: 'bar-track' }, bar)),
    el('td', { class: 'num', text: fmt(r.pageViews) }),
    el('td', { class: 'num' }, delta(r.change) || el('span', { class: 'muted', text: '–' })),
    hasVisitors ? el('td', { class: 'num', text: fmt(r.visitors) }) : null);
    return tr;
  });
  target.replaceChildren(el('table', {}, el('thead', {}, head), el('tbody', {}, body)));
}

function openProject(id) {
  state.project = id;
  writeUrl();
  syncControls();
  render();
}

// ------------------------------------------------------------------ tela de projeto

async function renderProject() {
  const token = ++loadToken;
  $('#view-overview').hidden = true;
  $('#view-project').hidden = false;
  showWarnings([]);
  const project = projectList.find((p) => p.id === state.project) || { id: state.project, name: state.project };
  $('#project-title').textContent = project.name;
  const url = $('#project-url');
  if (project.productionUrl) {
    url.href = project.productionUrl;
    url.textContent = project.productionUrl.replace(/^https?:\/\//, '');
    url.hidden = false;
  } else {
    url.hidden = true;
  }

  const metrics = [['pageViews', 'Page views']];
  if (capabilities?.eventMetric) metrics.push(['events', 'Eventos']);
  if (capabilities?.visitors) metrics.push(['visitors', 'Visitantes únicos']);
  $('#project-cards').replaceChildren(...metrics.map(cardSkeleton));
  $('#project-chart').replaceChildren(el('div', { class: 'skeleton chart' }));
  $('#project-chart-note').textContent = rangeLabel();

  const dims = Object.keys(capabilities?.dimensions || {});
  const panels = new Map();
  $('#breakdowns').replaceChildren(...dims.map((key) => {
    const [title] = DIMENSION_TITLES[key] || [key];
    const body = el('div', {}, el('div', { class: 'skeleton' }), el('div', { class: 'skeleton' }));
    panels.set(key, body);
    return el('section', { class: 'panel' }, el('div', { class: 'panel-head' }, el('h2', { text: title })), body);
  }));

  const base = `/api/projects/${encodeURIComponent(state.project)}`;
  const series = metrics.map(([key]) => api(`${base}/timeseries?${queryParams({ metric: key })}`));
  const results = await Promise.allSettled(series);
  if (token !== loadToken) return;

  $('#project-cards').replaceChildren(...results.map((r, i) => {
    const [key, label] = metrics[i];
    if (r.status === 'rejected') return card({ label, error: r.reason }, () => renderProject());
    const ts = r.value;
    return card({ label, value: ts.total, note: key === 'visitors' ? ts.totalNote
      : !ts.hasData && key === 'pageViews' ? 'Sem page views no período. Confira se o Web Analytics está ativado.' : null });
  }));

  const pv = results[0];
  if (pv.status === 'fulfilled') {
    const ts = pv.value;
    $('#fetched-at').textContent = `Dados de ${stampFmt.format(new Date(ts.fetchedAt))}${ts.stale ? ' (cache)' : ''}`;
    const openIndex = ts.buckets.findIndex((b) => b.inProgress);
    const chart = $('#project-chart');
    chart.replaceChildren();
    if (!ts.hasData) {
      chart.replaceChildren(el('div', { class: 'block-empty' }, 'Sem dados de Web Analytics no período. ',
        el('a', { href: ANALYTICS_DOCS, target: '_blank', rel: 'noopener noreferrer', text: 'Como ativar' })));
    } else {
      drawChart(chart, ts.buckets.map((b) => b.t), [{ name: 'Page views', values: ts.buckets.map((b) => b.value) }],
        [palette()[0]], openIndex >= 0 ? openIndex : null, ts.range.granularity, true);
      $('#project-chart-note').textContent = `${rangeLabel()} · buckets em UTC${openIndex >= 0 ? ' · último bucket em andamento' : ''}`;
    }
  } else {
    $('#project-chart').replaceChildren(errorBlock(pv.reason, () => renderProject()));
  }

  await Promise.all(dims.map(async (key) => {
    const body = panels.get(key);
    try {
      const data = await api(`${base}/breakdown?${queryParams({ dim: key })}`);
      if (token !== loadToken) return;
      body.replaceChildren(breakdownTable(key, data));
    } catch (err) {
      if (token !== loadToken) return;
      body.replaceChildren(errorBlock(err, () => renderProject()));
    }
  }));
}

function breakdownTable(key, data) {
  if (!data.hasData) {
    return el('div', { class: 'block-empty', text: data.note });
  }
  const [, column] = DIMENSION_TITLES[key] || [key, key];
  const total = data.rows.reduce((s, r) => s + r.count, 0);
  const max = Math.max(1, ...data.rows.map((r) => r.count));
  const rows = data.rows.map((r) => {
    const bar = el('div', { class: 'bar' });
    bar.style.width = `${(r.count / max) * 100}%`;
    return el('tr', {},
      el('td', { class: 'cell-truncate', title: r.value || '(direto / desconhecido)', text: r.value || '(direto / desconhecido)' }),
      el('td', { class: 'bar-cell' }, el('div', { class: 'bar-track' }, bar)),
      el('td', { class: 'num', text: fmt(r.count) }),
      el('td', { class: 'num muted', text: total ? pf.format(r.count / total).replace('+', '') : '' }));
  });
  return el('div', {},
    el('table', {},
      el('thead', {}, el('tr', {}, el('th', { text: column }), el('th', { class: 'bar-cell' }),
        el('th', { class: 'num', text: key === 'events' ? 'Eventos' : 'Page views' }), el('th', { class: 'num', text: '% do top' }))),
      el('tbody', {}, rows)),
    el('p', { class: 'muted small', text: data.note }));
}

// ------------------------------------------------------------------ gráficos

function cssVar(name) {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}

function destroyCharts() {
  for (const c of charts) c.destroy();
  charts = [];
}

function drawChart(container, isoBuckets, series, colors, openIndex, granularity, fill = false) {
  const xs = isoBuckets.map((t) => Date.parse(t) / 1000);
  const step = granularity === '1h' ? 3600 : 86400;
  const text = cssVar('--muted');
  const grid = cssVar('--border');
  const band = cssVar('--open-band');
  const fmtX = (ts) => (granularity === '1h' ? hourFmt : dayFmt).format(new Date(ts * 1000));

  const opts = {
    width: Math.max(280, container.clientWidth),
    height: 300,
    tzDate: (ts) => uPlot.tzDate(new Date(ts * 1e3), 'Etc/UTC'),
    legend: { show: false },
    cursor: { points: { size: 6 } },
    scales: { x: { time: true, range: (u, min, max) => [min, max + (xs.length === 1 ? step : 0)] } },
    axes: [
      { stroke: text, grid: { stroke: grid, width: 1 }, ticks: { stroke: grid }, space: 60,
        // só múltiplos do bucket, para não repetir rótulos de dia
        incrs: [1, 2, 3, 6, 7, 12, 14, 30, 60].map((n) => n * step),
        values: (u, splits) => splits.map(fmtX) },
      { stroke: text, grid: { stroke: grid, width: 1 }, ticks: { show: false }, size: 56,
        values: (u, splits) => splits.map((v) => nfCompact.format(v)) },
    ],
    series: [{ value: (u, v) => (v == null ? '' : fmtX(v)) }, ...series.map((s, i) => ({
      label: s.name,
      stroke: colors[i],
      width: 2,
      fill: fill ? `${colors[i]}22` : undefined,
      points: { show: xs.length <= 31 },
      value: (u, v) => (v == null ? '–' : nf.format(v)),
    }))],
    hooks: {
      drawClear: [(u) => {
        if (openIndex == null || openIndex >= xs.length) return;
        const x0 = u.valToPos(xs[openIndex] - step / 2, 'x', true);
        const x1 = u.bbox.left + u.bbox.width;
        const { ctx } = u;
        ctx.save();
        ctx.fillStyle = band;
        ctx.fillRect(Math.max(u.bbox.left, x0), u.bbox.top, x1 - Math.max(u.bbox.left, x0), u.bbox.height);
        ctx.restore();
      }],
    },
    plugins: [tooltipPlugin(series, colors, fmtX, openIndex, xs)],
  };
  const chart = new uPlot(opts, [xs, ...series.map((s) => s.values)], container);
  charts.push(chart);
  const ro = new ResizeObserver(() => chart.setSize({ width: Math.max(280, container.clientWidth), height: 300 }));
  ro.observe(container);
  const destroy = chart.destroy.bind(chart);
  chart.destroy = () => { ro.disconnect(); destroy(); };
}

function tooltipPlugin(series, colors, fmtX, openIndex, xs) {
  let tip;
  return {
    hooks: {
      init: (u) => {
        tip = el('div', { class: 'chart-tip' });
        tip.hidden = true;
        u.over.append(tip);
      },
      setCursor: (u) => {
        const i = u.cursor.idx;
        if (i == null) { tip.hidden = true; return; }
        const rows = series.map((s, k) => ({ name: s.name, value: s.values[i], color: colors[k] }))
          .sort((a, b) => (b.value || 0) - (a.value || 0));
        tip.replaceChildren(
          el('div', { class: 'chart-tip-title', text: `${fmtX(xs[i])}${i === openIndex ? ' · em andamento' : ''}` }),
          ...rows.map((r) => el('div', { class: 'chart-tip-row' }, swatch(r.color), el('span', { text: r.name }),
            el('strong', { text: fmt(r.value) }))));
        tip.hidden = false;
        const left = u.cursor.left;
        const flip = left > u.over.clientWidth / 2;
        tip.style.left = flip ? '' : `${left + 12}px`;
        tip.style.right = flip ? `${u.over.clientWidth - left + 12}px` : '';
        tip.style.top = '8px';
      },
    },
  };
}

// ------------------------------------------------------------------ configurações

function openSettings() {
  const form = $('#settings-form');
  const c = settings?.config;
  if (!c) return;
  form.defaultScope.value = c.defaultScope;
  form.defaultRange.value = c.defaultRange;
  form.productionOnly.checked = c.productionOnly;
  form.vercelCliPath.value = c.vercelCliPath || '';
  form.autoRefreshEnabled.checked = c.autoRefresh.enabled;
  form.autoRefreshInterval.value = c.autoRefresh.intervalMinutes;
  form.port.value = c.port;
  form.openBrowserOnStart.checked = c.openBrowserOnStart;
  $('#settings-path').textContent = `Arquivo: ${settings.configPath}${settings.demo ? ' · modo demo' : ''}`;
  $('#settings-error').textContent = '';
  $('#settings-dialog').showModal();
}

function wireSettings() {
  $('#settings-btn').addEventListener('click', openSettings);
  $('#shutdown-btn').addEventListener('click', async () => {
    try {
      await api('/api/shutdown', { method: 'POST' });
    } catch {
      // o processo pode fechar antes de responder
    }
    $('#settings-dialog').close();
    hideViews();
    showWarnings(['O Overlook foi encerrado. Pode fechar esta aba.']);
  });
  $('#settings-cancel').addEventListener('click', () => $('#settings-dialog').close());
  $('#settings-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const form = e.target;
    const c = settings.config;
    const body = {
      ...c,
      port: Number(form.port.value),
      defaultScope: form.defaultScope.value.trim(),
      defaultRange: form.defaultRange.value,
      productionOnly: form.productionOnly.checked,
      vercelCliPath: form.vercelCliPath.value.trim() || null,
      autoRefresh: { enabled: form.autoRefreshEnabled.checked, intervalMinutes: Number(form.autoRefreshInterval.value) },
      openBrowserOnStart: form.openBrowserOnStart.checked,
    };
    try {
      settings = await api('/api/settings', { method: 'PUT', body });
      setupAutoRefresh();
      $('#settings-dialog').close();
      if (settings.requiresRestart) {
        showWarnings([`A nova porta (${settings.config.port}) vale depois de reiniciar o Overlook.`]);
      }
      if (body.vercelCliPath !== c.vercelCliPath) boot(false);
    } catch (err) {
      $('#settings-error').textContent = err.message;
    }
  });
}

// ------------------------------------------------------------------ início

darkQuery.addEventListener('change', () => render());

readUrl();
if (DEMO) $('#demo-badge').hidden = false;
boot();
