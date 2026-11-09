// ------------------------------------------------------------------ download

// Detecta o SO do visitante para destacar o download certo.
function detectOS() {
  const ua = navigator.userAgent;
  const platform = (navigator.userAgentData && navigator.userAgentData.platform) || navigator.platform || "";
  if (/win/i.test(platform) || /Windows/.test(ua)) return "windows";
  if (/mac/i.test(platform) || /Mac OS X/.test(ua)) return "mac";
  if (/linux/i.test(platform) && !/Android/.test(ua)) return "linux";
  return null;
}

const os = detectOS();
const hero = document.getElementById("heroDownload");
const heroLabel = document.getElementById("heroDownloadLabel");

if (os === "mac") {
  // O navegador não informa a arquitetura de forma confiável (Intel x Apple Silicon),
  // então destacamos as duas opções e deixamos o usuário escolher.
  document.querySelectorAll('.dl a[data-os^="mac"]').forEach((a) => a.classList.add("current"));
  hero.href = "#download";
  heroLabel.textContent = "Baixar para macOS";
} else if (os) {
  const link = document.querySelector(`.dl a[data-os="${os}"]`);
  link.classList.add("current");
  hero.href = link.href;
  heroLabel.textContent = os === "windows" ? "Baixar para Windows" : "Baixar para Linux";
}

// Modal de apoio: aparece no primeiro download e nunca mais.
// Sem localStorage (aba anônima, bloqueio) o site segue funcionando: mostra no máximo uma vez por visita.
const STORE_SUPPORT = "overlook:support-shown";
const support = document.getElementById("support");
let supportShown = false;

function wasShown() {
  try { return localStorage.getItem(STORE_SUPPORT) === "1"; } catch { return supportShown; }
}
function markShown() {
  supportShown = true;
  try { localStorage.setItem(STORE_SUPPORT, "1"); } catch { /* armazenamento indisponível */ }
}

document.addEventListener("click", (e) => {
  const link = e.target.closest('a[href*="/releases/latest/download/"]');
  if (!link || supportShown || wasShown()) return;
  markShown();
  // Não bloqueia o download: o navegador segue o link e o modal abre logo depois.
  setTimeout(() => support.showModal(), 400);
});

document.getElementById("supportClose").addEventListener("click", () => support.close());
support.addEventListener("click", (e) => {
  if (e.target === support) support.close(); // clique no fundo escuro
});

document.querySelectorAll(".copy").forEach((btn) => {
  btn.addEventListener("click", async () => {
    const text = document.getElementById(btn.dataset.copy).innerText.replace(/^\$ /gm, "");
    try {
      await navigator.clipboard.writeText(text);
      btn.textContent = "copiado!";
    } catch {
      btn.textContent = "erro";
    }
    setTimeout(() => (btn.textContent = "copiar"), 1500);
  });
});

// ------------------------------------------------------------------ gráficos de exemplo (dados fictícios)

// Mesma paleta categórica do app (tema escuro), na mesma ordem validada para daltonismo.
const PALETTE = ["#ff4f9b", "#5aa9f0", "#c98500", "#199e70", "#d95926"];
const SVG = "http://www.w3.org/2000/svg";
const nf = new Intl.NumberFormat("pt-BR");
const nfCompact = new Intl.NumberFormat("pt-BR", { notation: "compact", maximumFractionDigits: 1 });
const dayFmt = new Intl.DateTimeFormat("pt-BR", { day: "2-digit", month: "short", timeZone: "UTC" });

function svg(tag, attrs = {}) {
  const node = document.createElementNS(SVG, tag);
  for (const [k, v] of Object.entries(attrs)) node.setAttribute(k, v);
  return node;
}

// Gerador determinístico, para o exemplo ser sempre igual.
function seeded(seed) {
  let s = seed;
  return () => {
    s = (s * 1664525 + 1013904223) % 4294967296;
    return s / 4294967296;
  };
}

const DAYS = 14;
const today = new Date(Date.UTC(2026, 9, 8));
const days = Array.from({ length: DAYS }, (_, i) => new Date(today.getTime() - (DAYS - 1 - i) * 86400e3));

function series(base, seed, weekendDrop = 0.6) {
  const rnd = seeded(seed);
  return days.map((d, i) => {
    const dow = d.getUTCDay();
    const weekly = dow === 0 || dow === 6 ? weekendDrop : 1;
    const trend = 1 + i * 0.012;
    let v = base * weekly * trend * (0.85 + rnd() * 0.3);
    if (i === DAYS - 1) v *= 0.55; // hoje, ainda em andamento
    return Math.round(v);
  });
}

const projects = [
  { name: "loja", values: series(5200, 7) },
  { name: "blog", values: series(3900, 11, 0.8) },
  { name: "docs", values: series(2800, 23, 0.45) },
  { name: "landing", values: series(1700, 31) },
  { name: "status", values: series(600, 43, 0.9) },
];

// Passo "redondo" (1, 2, 2,5 ou 5 × 10^n) para os ticks do eixo.
function niceStep(raw) {
  const pow = 10 ** Math.floor(Math.log10(raw));
  const f = raw / pow;
  return (f <= 1 ? 1 : f <= 2 ? 2 : f <= 2.5 ? 2.5 : f <= 5 ? 5 : 10) * pow;
}

function lineChart(container, list, { area = false } = {}) {
  const box = container.getBoundingClientRect();
  const W = Math.max(280, box.width);
  const H = box.height;
  const m = { top: 12, right: 12, bottom: 26, left: 44 };
  const iw = W - m.left - m.right;
  const ih = H - m.top - m.bottom;
  const tick = niceStep(Math.max(...list.flatMap((s) => s.values)) / 4);
  const max = tick * 4;
  const x = (i) => m.left + (i / (DAYS - 1)) * iw;
  const y = (v) => m.top + ih - (v / max) * ih;

  const root = svg("svg", { viewBox: `0 0 ${W} ${H}`, preserveAspectRatio: "none" });

  // faixa do dia em andamento
  const openX = x(DAYS - 1.5);
  root.append(svg("rect", { class: "open-band", x: openX, y: m.top, width: m.left + iw - openX, height: ih }));
  const openLabel = svg("text", { class: "open-label", x: m.left + iw - 4, y: m.top + 12, "text-anchor": "end" });
  openLabel.textContent = "em andamento";
  root.append(openLabel);

  for (let t = 0; t <= 4; t++) {
    const v = (max / 4) * t;
    root.append(svg("line", { class: "grid-line", x1: m.left, x2: m.left + iw, y1: y(v), y2: y(v) }));
    const label = svg("text", { class: "axis-label", x: m.left - 8, y: y(v) + 4, "text-anchor": "end" });
    label.textContent = nfCompact.format(v);
    root.append(label);
  }
  const step = W < 520 ? 4 : 2;
  days.forEach((d, i) => {
    const last = i === DAYS - 1;
    if (!last && (i % step !== 0 || DAYS - 1 - i < step)) return;
    const label = svg("text", { class: "axis-label", x: last ? m.left + iw : x(i), y: H - 6, "text-anchor": last ? "end" : "middle" });
    label.textContent = dayFmt.format(d);
    root.append(label);
  });

  if (area) {
    const defs = svg("defs");
    const grad = svg("linearGradient", { id: "areaFill", x1: 0, y1: 0, x2: 0, y2: 1 });
    grad.append(svg("stop", { offset: "0", "stop-color": PALETTE[0], "stop-opacity": ".35" }));
    grad.append(svg("stop", { offset: "1", "stop-color": PALETTE[0], "stop-opacity": "0" }));
    defs.append(grad);
    root.append(defs);
    const s = list[0].values;
    const d = `M${x(0)},${y(0)} ` + s.map((v, i) => `L${x(i)},${y(v)}`).join(" ") + ` L${x(DAYS - 1)},${y(0)} Z`;
    root.append(svg("path", { d, fill: "url(#areaFill)" }));
  }

  list.forEach((s, k) => {
    const d = s.values.map((v, i) => `${i ? "L" : "M"}${x(i)},${y(v)}`).join(" ");
    root.append(svg("path", { d, fill: "none", stroke: PALETTE[k], "stroke-width": 2, "stroke-linejoin": "round", "stroke-linecap": "round" }));
  });

  const cross = svg("line", { class: "crosshair", y1: m.top, y2: m.top + ih, visibility: "hidden" });
  root.append(cross);
  const dots = list.map((s, k) => {
    const c = svg("circle", { r: 4.5, fill: PALETTE[k], stroke: "#1a1116", "stroke-width": 2, visibility: "hidden" });
    root.append(c);
    return c;
  });

  container.replaceChildren(root);
  const tip = document.createElement("div");
  tip.className = "tip";
  tip.hidden = true;
  container.append(tip);

  root.addEventListener("pointermove", (e) => {
    const r = root.getBoundingClientRect();
    const px = ((e.clientX - r.left) / r.width) * W;
    const i = Math.max(0, Math.min(DAYS - 1, Math.round(((px - m.left) / iw) * (DAYS - 1))));
    cross.setAttribute("x1", x(i));
    cross.setAttribute("x2", x(i));
    cross.setAttribute("visibility", "visible");
    dots.forEach((c, k) => {
      c.setAttribute("cx", x(i));
      c.setAttribute("cy", y(list[k].values[i]));
      c.setAttribute("visibility", "visible");
    });
    const rows = list.map((s, k) => ({ name: s.name, v: s.values[i], color: PALETTE[k] })).sort((a, b) => b.v - a.v);
    tip.innerHTML = "";
    const title = document.createElement("div");
    title.className = "t";
    title.textContent = dayFmt.format(days[i]) + (i === DAYS - 1 ? " · em andamento" : "");
    tip.append(title);
    for (const row of rows) {
      const line = document.createElement("div");
      line.className = "r";
      const sw = document.createElement("span");
      sw.className = "sw";
      sw.style.background = row.color;
      const name = document.createElement("span");
      name.textContent = row.name;
      const val = document.createElement("strong");
      val.textContent = nf.format(row.v);
      line.append(sw, name, val);
      tip.append(line);
    }
    tip.hidden = false;
    const left = (x(i) / W) * r.width;
    const flip = left > r.width / 2;
    tip.style.left = flip ? "" : `${left + 14}px`;
    tip.style.right = flip ? `${r.width - left + 14}px` : "";
    tip.style.top = "8px";
  });
  root.addEventListener("pointerleave", () => {
    tip.hidden = true;
    cross.setAttribute("visibility", "hidden");
    dots.forEach((c) => c.setAttribute("visibility", "hidden"));
  });
}

function legend(el, list) {
  el.replaceChildren(...list.map((s, k) => {
    const item = document.createElement("span");
    const sw = document.createElement("i");
    sw.className = "sw";
    sw.style.background = PALETTE[k];
    item.append(sw, s.name);
    return item;
  }));
}

function ranking(el) {
  const rows = projects.map((p) => {
    const closed = p.values.slice(0, DAYS - 1);
    const half = Math.floor(closed.length / 2);
    const prev = closed.slice(0, half).reduce((a, b) => a + b, 0);
    const cur = closed.slice(closed.length - half).reduce((a, b) => a + b, 0);
    return { name: p.name, total: p.values.reduce((a, b) => a + b, 0), change: (cur - prev) / prev };
  }).sort((a, b) => b.total - a.total);
  const max = rows[0].total;
  el.replaceChildren(...rows.map((r) => {
    const row = document.createElement("div");
    row.className = "rk";
    const name = document.createElement("span");
    name.className = "name";
    name.textContent = r.name;
    const track = document.createElement("div");
    track.className = "track";
    const bar = document.createElement("div");
    bar.className = "bar";
    bar.style.width = `${(r.total / max) * 100}%`;
    track.append(bar);
    const total = document.createElement("span");
    total.className = "num";
    total.textContent = nf.format(r.total);
    const delta = document.createElement("span");
    const up = r.change >= 0;
    delta.className = `num ${up ? "up" : "down"}`;
    delta.textContent = `${up ? "▲" : "▼"} ${Math.abs(r.change * 100).toFixed(1).replace(".", ",")}%`;
    row.append(name, track, total, delta);
    return row;
  }));
}

function countries(el) {
  const data = [["BR", 6326], ["US", 4898], ["PT", 1429], ["DE", 1227], ["AR", 1020], ["MX", 817]];
  const total = data.reduce((s, [, v]) => s + v, 0);
  const max = data[0][1];
  el.replaceChildren(...data.map(([code, v]) => {
    const row = document.createElement("div");
    row.className = "bk";
    const c = document.createElement("span");
    c.className = "cc";
    c.textContent = code;
    const track = document.createElement("div");
    track.className = "track";
    const bar = document.createElement("div");
    bar.className = "bar";
    bar.style.width = `${(v / max) * 100}%`;
    track.append(bar);
    const n = document.createElement("span");
    n.className = "num";
    n.style.textAlign = "right";
    n.textContent = nf.format(v);
    const pct = document.createElement("span");
    pct.style.textAlign = "right";
    pct.style.color = "var(--ink-dim)";
    pct.textContent = `${Math.round((v / total) * 100)}%`;
    row.append(c, track, n, pct);
    return row;
  }));
}

const linesEl = document.getElementById("exLines");
const areaEl = document.getElementById("exArea");
function drawCharts() {
  lineChart(linesEl, projects);
  lineChart(areaEl, [projects[2]], { area: true });
}
drawCharts();
legend(document.getElementById("exLinesLegend"), projects);
ranking(document.getElementById("exRanking"));
countries(document.getElementById("exCountries"));

let resizeTimer;
window.addEventListener("resize", () => {
  clearTimeout(resizeTimer);
  resizeTimer = setTimeout(drawCharts, 150);
});

// Barras crescem quando aparecem na tela.
const observer = new IntersectionObserver((entries) => {
  for (const entry of entries) {
    if (entry.isIntersecting) {
      entry.target.classList.add("in-view");
      observer.unobserve(entry.target);
    }
  }
}, { threshold: 0.3 });
document.querySelectorAll(".ex-ranking, .ex-bars").forEach((el) => observer.observe(el));
