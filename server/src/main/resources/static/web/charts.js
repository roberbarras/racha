// Small SVG charts drawn by hand: no third-party code, styled by app.css, accessible titles on every mark.
const NS = "http://www.w3.org/2000/svg";

function svg(tag, attrs = {}, ...children) {
  const node = document.createElementNS(NS, tag);
  for (const [k, v] of Object.entries(attrs)) if (v !== undefined && v !== null) node.setAttribute(k, v);
  for (const c of children) if (c != null) node.append(typeof c === "string" ? document.createTextNode(c) : c);
  return node;
}

const title = text => svg("title", {}, text);

/** Nice upper bound for an axis: 1, 2 or 5 × 10ⁿ. */
function niceMax(value) {
  if (value <= 0) return 1;
  const p = 10 ** Math.floor(Math.log10(value));
  return [1, 2, 5, 10].map(m => m * p).find(m => m >= value);
}

/**
 * Vertical bars. [items]: [{label, value, tip}]; [format] turns a value into axis text.
 * [labelEvery]: show one x label every n bars (dense charts).
 */
/** Phones draw on a narrower canvas, so the same text is not shrunk to nothing. */
const narrow = () => window.innerWidth < 700;

export function bars(items, { height = 200, width = narrow() ? 380 : 720, format = String, labelEvery = 1, label = "", integer = false } = {}) {
  // A fixed drawing width per layout (720 full width, 400 half): text comes out the same size in every chart.
  const top = 10, bottom = 24, left = 56;
  const max = niceMax(Math.max(0, ...items.map(i => i.value)));
  const plotH = height - top - bottom, plotW = width - left;
  const step = plotW / Math.max(items.length, 1), barW = Math.max(2, Math.min(44, step * 0.68));
  const root = svg("svg", { class: "chart", viewBox: `0 0 ${width} ${height}`, role: "img", "aria-label": label });
  // Counts get no half-way line when it would not be a whole number.
  for (const f of integer && max < 2 ? [0, 1] : [0, 0.5, 1]) {
    const y = top + plotH * (1 - f);
    root.append(svg("line", { class: "grid-line", x1: left, x2: width, y1: y, y2: y }),
      svg("text", { x: left - 8, y: y + 4, "text-anchor": "end" }, format(max * f)));
  }
  items.forEach((item, i) => {
    const h = max ? (item.value / max) * plotH : 0, x = left + i * step + (step - barW) / 2;
    root.append(svg("rect", { class: "bar-fill", x, y: top + plotH - h, width: barW, height: Math.max(h, item.value > 0 ? 1.5 : 0), rx: 3 },
      title(item.tip ?? `${item.label}: ${format(item.value)}`)));
    if (i % labelEvery === 0) root.append(svg("text", { x: x + barW / 2, y: height - 6, "text-anchor": "middle" }, item.label));
  });
  return root;
}

/**
 * A year of days as a GitHub-style grid: weeks are columns, Monday on top. [days]: Map "YYYY-MM-DD" → seconds.
 * [end]: last day (today); [weeks]: how many columns.
 */
export function heatmap(days, { end = new Date(), weeks = narrow() ? 26 : 53, tip = (d, v) => `${d}: ${v}`, label = "", monthNames = [] } = {}) {
  const cell = 12, gap = 3, top = 16, left = 0;
  const last = new Date(end.getFullYear(), end.getMonth(), end.getDate());
  const lastWeekday = (last.getDay() + 6) % 7; // Monday = 0
  const first = new Date(last); first.setDate(last.getDate() - lastWeekday - (weeks - 1) * 7);
  const values = [...days.values()].filter(v => v > 0).sort((a, b) => a - b);
  const q = f => values.length ? values[Math.min(values.length - 1, Math.floor(values.length * f))] : Infinity;
  const steps = [q(0.25), q(0.5), q(0.75)];
  const level = v => v <= 0 ? 0 : v <= steps[0] ? 1 : v <= steps[1] ? 2 : v <= steps[2] ? 3 : 4;
  const width = left + weeks * (cell + gap), height = top + 7 * (cell + gap);
  const root = svg("svg", { class: "heat", viewBox: `0 0 ${width} ${height}`, role: "img", "aria-label": label });
  let month = -1;
  for (let w = 0; w < weeks; w++) {
    for (let d = 0; d < 7; d++) {
      const day = new Date(first); day.setDate(first.getDate() + w * 7 + d);
      if (day > last) continue;
      const key = isoDay(day), v = days.get(key) ?? 0;
      if (d === 0 && day.getMonth() !== month) {
        month = day.getMonth();
        if (w < weeks - 2) root.append(svg("text", { x: left + w * (cell + gap), y: 10, class: "subtle", style: "font-size:10px" }, monthNames[month] ?? ""));
      }
      root.append(svg("rect", { x: left + w * (cell + gap), y: top + d * (cell + gap), width: cell, height: cell, rx: 2,
        style: `fill: var(--heat-${level(v)})` }, title(tip(key, v))));
    }
  }
  return root;
}

export function isoDay(date) {
  const p = n => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${p(date.getMonth() + 1)}-${p(date.getDate())}`;
}

/** Legend under the heat map. */
export function heatLegend(less, more) {
  const wrap = document.createElement("div");
  wrap.className = "legend";
  wrap.append(less);
  for (let i = 0; i <= 4; i++) {
    const swatch = document.createElement("i");
    swatch.style.background = `var(--heat-${i})`;
    wrap.append(swatch);
  }
  wrap.append(more);
  return wrap;
}
