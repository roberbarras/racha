// Racha web panel (H14): the user's statistics, devices and account; the server's state for administrators.
// No framework and no third-party code: DOM built with h() (text is never parsed as HTML), SVG charts in charts.js.
import { T, lang } from "./i18n.js";
import { bars, heatmap, heatLegend, isoDay } from "./charts.js";

/** The server's base path (RACHA_BASE_PATH): everything before /web/. */
const BASE = location.pathname.replace(/\/web(\/.*)?$/, "");
const ZONE = Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC";
const app = document.getElementById("app");

/** Access token in memory only; the refresh token is an HttpOnly cookie the page cannot read. */
let session = null;

// ---------- DOM ----------

function h(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs ?? {})) {
    if (v === undefined || v === null || v === false) continue;
    if (k.startsWith("on")) node.addEventListener(k.slice(2), v);
    else if (k === "class") node.className = v;
    else node.setAttribute(k, v === true ? "" : v);
  }
  append(node, children);
  return node;
}

function append(node, children) {
  for (const c of children.flat()) {
    if (c === undefined || c === null || c === false) continue;
    node.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
}

const card = (title, ...content) => h("section", { class: "card" }, title ? h("h3", {}, title) : null, ...content);
const kpi = (label, value, note) => h("div", { class: "card kpi" }, h("span", { class: "label" }, label), h("span", { class: "value" }, value),
  note ? h("span", { class: "caption subtle" }, note) : null);
const pageHead = (title, intro, ...actions) => h("header", { class: "page-head" },
  h("div", {}, h("h1", {}, title), intro ? h("p", { class: "muted" }, intro) : null), actions.length ? h("div", { class: "row" }, ...actions) : null);
const empty = text => h("p", { class: "empty" }, text);

// ---------- Formatting ----------

const number = new Intl.NumberFormat(lang);
const dateFormat = new Intl.DateTimeFormat(lang, { day: "numeric", month: "short", year: "numeric" });
const dateTimeFormat = new Intl.DateTimeFormat(lang, { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" });
const monthNames = Array.from({ length: 12 }, (_, m) => new Intl.DateTimeFormat(lang, { month: "short" }).format(new Date(2026, m, 1)));

function duration(seconds) {
  const s = Math.round(seconds ?? 0);
  if (s < 60) return `${s} s`;
  const m = Math.round(s / 60);
  if (m < 60) return `${m} min`;
  const hrs = Math.floor(m / 60), rest = m % 60;
  return rest && hrs < 100 ? `${number.format(hrs)} h ${rest} min` : `${number.format(hrs)} h`;
}

const hoursShort = seconds => `${number.format(Math.round((seconds ?? 0) / 360) / 10)} ${T.hours}`;

function bytes(n) {
  if (n == null) return "—";
  const units = ["B", "KB", "MB", "GB", "TB"];
  let i = 0, v = n;
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
  return `${number.format(Math.round(v * 10) / 10)} ${units[i]}`;
}

const date = iso => iso ? dateFormat.format(new Date(iso)) : "—";
const dateTime = iso => iso ? dateTimeFormat.format(new Date(iso)) : "—";

function ago(iso) {
  if (!iso) return "—";
  const minutes = Math.round((Date.now() - new Date(iso).getTime()) / 60000);
  if (minutes < 1) return T.now;
  if (minutes < 60) return T.ago(minutes, "m");
  if (minutes < 60 * 24) return T.ago(Math.round(minutes / 60), "h");
  if (minutes < 60 * 24 * 30) return T.ago(Math.round(minutes / 1440), "d");
  return date(iso);
}

const categoryName = (id, name) => name || T.categories[id] || id || T.unknown;
const platformName = p => ({ android: "Android", linux: "Linux", web: "Web" })[p] ?? p ?? "—";
const percent = v => `${Math.round((v ?? 0) * 100)} %`;

// ---------- API ----------

class ApiError extends Error {
  constructor(status, code, detail) {
    super(detail || code || String(status));
    this.status = status;
    this.code = code;
  }
}

async function request(path, { method = "GET", body, auth = true, web = false } = {}) {
  const headers = { "Accept-Language": lang };
  if (body !== undefined) headers["Content-Type"] = "application/json";
  if (auth && session) headers.Authorization = `Bearer ${session.accessToken}`;
  if (web) headers["X-Racha-Web"] = "1";
  let response;
  try {
    response = await fetch(BASE + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), credentials: "same-origin" });
  } catch {
    throw new ApiError(0, "network", T.errNetwork);
  }
  if (!response.ok) {
    let problem = {};
    try { problem = await response.json(); } catch { /* not JSON */ }
    throw new ApiError(response.status, problem.code, problem.detail);
  }
  return response.status === 204 || response.status === 202 ? null : response.json();
}

/** Calls the API; on 401 renews the access token once from the cookie, and signs out if that fails too. */
async function api(path, options = {}) {
  try {
    return await request(path, options);
  } catch (e) {
    if (e.status !== 401 || !session) throw e;
    if (!(await renew())) {
      session = null;
      render();
      throw e;
    }
    return request(path, options);
  }
}

async function renew() {
  try {
    session = await request("/auth/web/refresh", { method: "POST", auth: false, web: true });
    return true;
  } catch {
    return false;
  }
}

function errorText(e) {
  if (e.code === "network") return T.errNetwork;
  if (e.code === "invalid_credentials") return T.errCredentials;
  if (e.code === "too_many_attempts" || e.status === 429) return T.errTooMany;
  if (e.code === "wrong_password") return T.errWrongPassword;
  if (e.code === "last_admin") return T.errLastAdmin;
  if (e.status === 403) return T.errForbidden;
  if (e.code === "invalid_input" && /password/.test(e.message)) return T.errWeak;
  return e.message || T.errGeneric;
}

// ---------- Routing ----------

const routes = {
  "": summary, activity, devices, account,
  admin: adminOverview, "admin/users": adminUsers, "admin/logs": adminLogs,
};

/** "#/admin/logs?level=WARN" → "admin/logs": the query part is read by the view itself. */
const route = () => location.hash.replace(/^#\/?/, "").split("?")[0];

async function render() {
  if (!session) {
    app.replaceChildren(h("p", { class: "loading" }, T.loading));
    if (!(await renew())) {
      app.replaceChildren(signIn());
      return;
    }
  }
  const name = route() in routes ? route() : "";
  if (name.startsWith("admin") && session.role !== "admin") {
    location.hash = "#/";
    return;
  }
  const content = h("div", { class: "page" }, h("p", { class: "loading" }, T.loading));
  app.replaceChildren(shell(name, content));
  try {
    content.replaceChildren(...[await routes[name]()].flat().filter(Boolean));
  } catch (e) {
    if (!session) return;
    content.replaceChildren(card(null, h("p", { class: "danger" }, errorText(e)), h("button", { class: "btn secondary", onclick: render }, T.retry)));
  }
  document.title = `Racha · ${content.querySelector("h1")?.textContent ?? ""}`;
}

window.addEventListener("hashchange", render);

function shell(current, content) {
  const link = (to, text) => h("a", { href: `#/${to}`, "aria-current": current === to ? "page" : null }, text);
  const admin = session.role === "admin";
  return h("div", { class: "shell" },
    h("aside", { class: "rail" },
      h("div", { class: "brand" }, h("img", { src: "racha.svg", alt: "" }), "Racha"),
      h("nav", { class: "nav", "aria-label": "Racha" },
        link("", T.navSummary), link("activity", T.navActivity), link("devices", T.navDevices), link("account", T.navAccount),
        admin ? h("span", { class: "group" }, T.navAdmin) : null,
        admin ? link("admin", T.navAdminOverview) : null,
        admin ? link("admin/users", T.navAdminUsers) : null,
        admin ? link("admin/logs", T.navAdminLogs) : null),
      h("div", { class: "who" }, h("span", { class: "email" }, session.email),
        h("button", { class: "btn ghost small", onclick: signOut }, T.signOut))),
    h("main", { class: "main" }, content));
}

async function signOut() {
  try { await request("/auth/web/logout", { method: "POST", auth: false, web: true }); } catch { /* signed out anyway */ }
  session = null;
  location.hash = "#/";
  render();
}

// ---------- Sign in ----------

function signIn() {
  const message = h("p", { class: "message danger", role: "alert" });
  const email = h("input", { type: "email", autocomplete: "username", required: true, autofocus: true });
  const password = h("input", { type: "password", autocomplete: "current-password", required: true });
  const button = h("button", { class: "btn", type: "submit" }, T.signIn);
  const form = h("form", {
    class: "card stack",
    onsubmit: async e => {
      e.preventDefault();
      button.disabled = true;
      message.textContent = "";
      try {
        session = await request("/auth/web/login", { method: "POST", auth: false, body: { email: email.value, password: password.value } });
        render();
      } catch (err) {
        message.textContent = errorText(err);
        button.disabled = false;
      }
    },
  },
  h("label", {}, T.email, email), h("label", {}, T.password, password), message, button,
  h("a", { href: `${BASE}/reset-password`, class: "caption muted" }, T.forgot));
  return h("div", { class: "center" }, h("div", { class: "signin" },
    h("div", { class: "brand" }, h("img", { src: "racha.svg", alt: "" }), "Racha"),
    h("p", { class: "muted", style: "text-align:center" }, T.signInTitle), form));
}

// ---------- Summary ----------

async function summary() {
  const [stats, details] = await Promise.all([api(`/stats/me?zone=${encodeURIComponent(ZONE)}`), api(`/stats/me/details?zone=${encodeURIComponent(ZONE)}`)]);
  const byDay = new Map(stats.watchByDay.map(d => [d.day, d.seconds]));
  const today = new Date();
  const last30 = Array.from({ length: 30 }, (_, i) => {
    const d = new Date(today); d.setDate(today.getDate() - 29 + i);
    const key = isoDay(d);
    return { label: String(d.getDate()), value: (byDay.get(key) ?? 0) / 60, tip: `${date(key)}: ${duration(byDay.get(key) ?? 0)}` };
  });
  const totalCategories = details.byCategory.reduce((s, c) => s + c.seconds, 0);
  const nothing = stats.watchSeconds === 0 && stats.historyEntries === 0;
  return [
    pageHead(T.summaryTitle, T.summaryIntro),
    nothing ? card(null, empty(T.noData)) : null,
    h("div", { class: "grid kpis" },
      kpi(T.kTotal, duration(stats.watchSeconds)),
      kpi(T.kStreak, T.days(stats.streakDays)),
      kpi(T.kBest, T.days(details.bestStreak.days), details.bestStreak.days ? `${date(details.bestStreak.from)} – ${date(details.bestStreak.to)}` : null),
      kpi(T.kActive, number.format(stats.activeDays)),
      kpi(T.kDone, number.format(stats.completedElements)),
      kpi(T.kInProgress, number.format(stats.elementsInProgress)),
      kpi(T.kLast, ago(stats.lastWatchedAt))),
    card(T.last30, bars(last30, { format: v => `${Math.round(v)} min`, labelEvery: window.innerWidth < 700 ? 5 : 3, label: T.last30 })),
    card(T.year, heatmap(byDay, { end: today, tip: (d, v) => `${date(d)}: ${duration(v)}`, label: T.year, monthNames }), heatLegend(T.less, T.more)),
    card(T.byCategory, totalCategories ? hbars(details.byCategory.map(c => ({ name: categoryName(c.categoryId, c.name), value: c.seconds })), totalCategories)
      : empty(T.noData)),
  ];
}

function hbars(items, total) {
  const max = Math.max(...items.map(i => i.value), 1);
  return h("div", { class: "hbars" }, items.map(i => h("div", { class: "hbar" },
    h("span", { class: "name", title: i.name }, i.name),
    h("div", { class: "bar", role: "img", "aria-label": `${i.name}: ${duration(i.value)}` }, h("span", { style: `width:${(i.value / max) * 100}%` })),
    h("span", { class: "num muted" }, `${duration(i.value)}${total ? ` · ${Math.round((i.value / total) * 100)} %` : ""}`))));
}

// ---------- Activity ----------

async function activity() {
  const d = await api(`/stats/me/details?zone=${encodeURIComponent(ZONE)}`);
  const hours = Array.from({ length: 24 }, (_, hr) => ({ label: String(hr), value: (d.byHour.find(x => x.hour === hr)?.seconds ?? 0) / 60 }));
  const weekdays = T.weekdays.map((label, i) => ({ label, value: (d.byWeekday.find(x => x.day === i + 1)?.seconds ?? 0) / 60 }));
  const minutes = v => `${Math.round(v)} min`;
  const totalDevices = d.byDevice.reduce((s, x) => s + x.seconds, 0);
  return [
    pageHead(T.activityTitle, T.activityIntro),
    h("div", { class: "grid kpis" },
      kpi(T.sessionsCount, number.format(d.sessions.count)),
      kpi(T.avgSession, duration(d.sessions.averageSeconds)),
      kpi(T.avgSpeed, `${number.format(d.sessions.averageSpeed)}×`),
      kpi(T.withSubs, percent(d.sessions.withSubtitles))),
    h("div", { class: "grid two" },
      card(T.byHour, bars(hours, { width: 400, format: minutes, labelEvery: window.innerWidth < 700 ? 6 : 3, label: T.byHour })),
      card(T.byWeekday, bars(weekdays, { width: 400, format: minutes, label: T.byWeekday }))),
    card(T.byDevice, totalDevices
      ? hbars(d.byDevice.map(x => ({ name: `${x.name ?? "—"} · ${platformName(x.platform)}`, value: x.seconds })), totalDevices) : empty(T.noData)),
    card(T.titles, d.titles.length ? h("div", { class: "table-wrap" }, h("table", { class: "responsive" },
      h("thead", {}, h("tr", {}, h("th", {}, T.colTitle), h("th", {}, T.colProgress), h("th", {}, T.colTime), h("th", {}, T.colLast))),
      h("tbody", {}, d.titles.map(t => {
        const share = t.elements ? t.completed / t.elements : 0;
        return h("tr", {},
          h("td", { "data-label": T.colTitle }, h("strong", {}, t.title ?? "—"), h("div", { class: "caption subtle" }, categoryName(t.categoryId))),
          h("td", { "data-label": T.colProgress }, h("div", { class: "row" },
            h("div", { class: "bar", style: "flex:1" }, h("span", { style: `width:${share * 100}%` })),
            h("span", { class: "num caption" }, `${t.completed}/${t.elements}`))),
          h("td", { "data-label": T.colTime, class: "num" }, duration(t.seconds)),
          h("td", { "data-label": T.colLast }, ago(t.lastWatchedAt)));
      })))) : empty(T.noData)),
    card(T.recent, d.recent.length ? h("div", { class: "table-wrap" }, h("table", { class: "responsive" },
      h("tbody", {}, d.recent.map(r => h("tr", {},
        h("td", { "data-label": T.colTitle }, h("strong", {}, r.element ?? "—"), h("div", { class: "caption subtle" }, r.title ?? "")),
        h("td", { "data-label": T.colStatus }, r.completed ? h("span", { class: "chip on" }, T.completed) : null),
        h("td", { "data-label": T.colWhen, class: "num" }, dateTime(r.at))))))) : empty(T.noData)),
  ];
}

// ---------- Devices ----------

async function devices() {
  const list = await api("/devices");
  const act = (fn, confirmText) => async () => {
    if (!confirm(confirmText)) return;
    try { await fn(); render(); } catch (e) { alert(errorText(e)); }
  };
  return [
    pageHead(T.devicesTitle, T.devicesIntro),
    card(null, h("div", { class: "table-wrap" }, h("table", { class: "responsive" },
      h("thead", {}, h("tr", {}, h("th", {}, T.colDevice), h("th", {}, T.colPlatform), h("th", {}, T.colVersion), h("th", {}, T.colSeen),
        h("th", {}, T.colSessions), h("th", {}))),
      h("tbody", {}, list.sort((a, b) => (b.lastSeenAt ?? "").localeCompare(a.lastSeenAt ?? "")).map(d => h("tr", {},
        h("td", { "data-label": T.colDevice }, h("strong", {}, d.name), d.current ? h("span", { class: "chip accent", style: "margin-left:8px" }, T.thisDevice) : null),
        h("td", { "data-label": T.colPlatform }, platformName(d.platform)),
        h("td", { "data-label": T.colVersion, class: "num" }, d.appVersion ?? "—"),
        h("td", { "data-label": T.colSeen }, ago(d.lastSeenAt)),
        h("td", { "data-label": T.colSessions }, h("span", { class: `chip ${d.activeSessions ? "on" : ""}` }, d.activeSessions ? T.signedIn : T.signedOutState)),
        h("td", { class: "actions" }, d.current ? null : h("div", { class: "row", style: "justify-content:flex-end" },
          d.activeSessions ? h("button", { class: "btn secondary small", onclick: act(() => api(`/devices/${d.id}/sign-out`, { method: "POST" }), T.confirmSignOut(d.name)) }, T.signOutDevice) : null,
          h("button", { class: "btn danger small", onclick: act(() => api(`/devices/${d.id}`, { method: "DELETE" }), T.confirmForget(d.name)) }, T.forget))))))))),
  ];
}

// ---------- Account ----------

async function account() {
  const me = await api("/account/me");
  const message = h("p", { class: "message", role: "status" });
  const current = h("input", { type: "password", autocomplete: "current-password", required: true });
  const fresh = h("input", { type: "password", autocomplete: "new-password", minlength: 10, required: true });
  const repeat = h("input", { type: "password", autocomplete: "new-password", minlength: 10, required: true });
  const button = h("button", { class: "btn", type: "submit" }, T.save);
  const form = h("form", {
    class: "stack",
    onsubmit: async e => {
      e.preventDefault();
      message.className = "message danger";
      if (fresh.value !== repeat.value) { message.textContent = T.mismatch; return; }
      button.disabled = true;
      try {
        await api("/account/password", { method: "POST", body: { currentPassword: current.value, newPassword: fresh.value } });
        form.reset();
        message.className = "message ok";
        message.textContent = T.passwordChanged;
      } catch (err) {
        message.textContent = errorText(err);
      } finally {
        button.disabled = false;
      }
    },
  }, h("label", {}, T.currentPassword, current), h("label", {}, T.newPassword, fresh), h("label", {}, T.repeatPassword, repeat), message,
  h("div", {}, button));
  return [
    pageHead(T.accountTitle, T.accountIntro),
    h("div", { class: "grid two" },
      card(null, h("div", { class: "stack" },
        h("div", {}, h("span", { class: "caption muted" }, T.email), h("p", {}, h("strong", {}, me.email))),
        h("div", {}, h("span", { class: "caption muted" }, T.role), h("p", {}, me.role === "admin" ? T.roleAdmin : T.roleUser)),
        h("div", {}, h("span", { class: "caption muted" }, T.since), h("p", {}, date(me.createdAt))))),
      card(T.changePassword, form)),
  ];
}

// ---------- Admin ----------

async function adminOverview() {
  const [o, a] = await Promise.all([api("/admin/overview"), api("/admin/activity")]);
  const m = o.metrics ?? {}, c = o.counts, s = o.server;
  const flag = (label, on, text) => h("span", { class: `chip ${on ? "on" : "off"}` }, `${label}: ${text ?? (on ? T.yes : T.no)}`);
  const mailMessage = h("p", { class: "message caption", role: "status" });
  const testMail = h("button", {
    class: "btn secondary small",
    onclick: async () => {
      testMail.disabled = true;
      mailMessage.className = "message caption";
      try {
        const r = await api("/admin/mail/test", { method: "POST" });
        mailMessage.classList.add("ok");
        mailMessage.textContent = T.mailSent(r.sentTo);
      } catch (e) {
        mailMessage.classList.add("danger");
        mailMessage.textContent = errorText(e);
      } finally {
        testMail.disabled = false;
      }
    },
  }, T.testMail);
  const daily = a.activeUsersByDay.map(d => ({ label: String(new Date(d.day).getDate()), value: d.count, tip: `${date(d.day)}: ${d.count}` }));
  return [
    pageHead(T.adminTitle, T.adminIntro),
    h("div", { class: "grid kpis" },
      kpi(T.version, s.version), kpi(T.uptime, duration(o.uptime ? isoDuration(o.uptime) : 0)),
      kpi(T.users, number.format(c.users), `${c.admins} admin · ${c.disabledUsers} ${T.disabled.toLowerCase()}`),
      kpi(T.activeUsers, number.format(c.activeUsers)),
      kpi(T.devices, number.format(c.devices), `${c.activeDevices} ${T.activeUsers.toLowerCase()}`),
      kpi(T.activeSessions, number.format(c.activeSessions)),
      kpi(T.dbSize, bytes(c.databaseBytes)),
      kpi(T.memory, bytes(m.heapUsedBytes), m.heapMaxBytes ? `/ ${bytes(m.heapMaxBytes)}` : null),
      kpi(T.cpu, m.processCpu != null ? percent(m.processCpu) : "—"),
      kpi(T.requests, number.format(m.httpRequests ?? 0), `${number.format(m.httpServerErrors ?? 0)} ${T.serverErrors}`),
      kpi(T.meanTime, m.httpMeanSeconds != null ? `${Math.round(m.httpMeanSeconds * 1000)} ms` : "—")),
    card(T.config, h("div", { class: "stack" },
      h("div", { class: "chips" },
        flag(T.mail, s.mailConfigured), flag(T.publicUrl, s.publicUrlSet), flag(T.metricsToken, s.metricsTokenSet),
        h("span", { class: "chip" }, `${T.registration}: ${s.registrationOpen ? T.open : T.closed}`),
        h("span", { class: "chip" }, `${T.proxies}: ${s.trustedProxies}`)),
      h("div", { class: "row" }, testMail), mailMessage)),
    card(T.backups, o.backupsLocation ? h("p", { class: "caption muted" }, T.backupsWhere(o.backupsLocation)) : null,
      o.recentBackups.length ? h("table", { class: "responsive" }, h("tbody", {}, o.recentBackups.map(b => h("tr", {},
        h("td", { "data-label": "" }, b.name), h("td", { "data-label": T.colWhen }, dateTime(b.createdAt)), h("td", { class: "num", "data-label": "" }, bytes(b.bytes))))))
        : empty(T.noBackups)),
    card(T.activeUsersDaily, daily.length ? bars(daily, { format: v => String(Math.round(v)), labelEvery: 3, label: T.activeUsersDaily, integer: true }) : empty(T.noData)),
    h("div", { class: "grid two" },
      card(T.watchByUser, a.watchHoursByUser.length ? simpleTable(a.watchHoursByUser, [
        [T.colEmail, u => u.email], [T.colTime, u => `${number.format(u.hours)} ${T.hours}`], [T.sessions, u => number.format(u.sessions)]]) : empty(T.noData)),
      card(T.features, a.features.length ? simpleTable(a.features, [
        ["", f => f.feature], [T.uses, f => number.format(f.uses)], [T.users, f => number.format(f.users)], [T.colLast, f => ago(f.lastUsedAt)]]) : empty(T.noData))),
    card(T.errorsByVersion, a.errorsByVersion.length ? simpleTable(a.errorsByVersion, [
      [T.colPlatform, e => platformName(e.platform)], [T.colVersion, e => e.appVersion ?? "—"], ["#", e => number.format(e.errors)],
      [T.users, e => number.format(e.users)], [T.colLast, e => ago(e.lastAt)]]) : empty(T.noData)),
    card(T.recentErrors, a.recentErrors.length ? simpleTable(a.recentErrors, [
      [T.colWhen, e => dateTime(e.at)], [T.colEmail, e => e.email], [T.colVersion, e => `${platformName(e.platform)} ${e.appVersion ?? ""}`],
      [T.colMessage, e => e.message]]) : empty(T.noData)),
    card(T.syncFailures, a.recentSyncFailures.length ? simpleTable(a.recentSyncFailures, [
      [T.colWhen, f => dateTime(f.at)], [T.colEmail, f => f.email], ["", f => f.kind], [T.colMessage, f => f.detail ?? ""]]) : empty(T.noData)),
  ];
}

/** "PT26H3M4.5S" (Jackson's Duration) → seconds. */
function isoDuration(text) {
  if (typeof text === "number") return text;
  const m = /PT(?:(\d+)H)?(?:(\d+)M)?(?:([\d.]+)S)?/.exec(text ?? "");
  return m ? (+m[1] || 0) * 3600 + (+m[2] || 0) * 60 + (+m[3] || 0) : 0;
}

function simpleTable(rows, columns) {
  return h("div", { class: "table-wrap" }, h("table", { class: "responsive" },
    h("thead", {}, h("tr", {}, columns.map(([label]) => h("th", {}, label)))),
    h("tbody", {}, rows.map(r => h("tr", {}, columns.map(([label, value]) => h("td", { "data-label": label }, value(r))))))));
}

async function adminUsers() {
  const users = await api("/admin/users");
  const act = (fn, confirmText) => async () => {
    if (confirmText && !confirm(confirmText)) return;
    try { await fn(); render(); } catch (e) { alert(errorText(e)); }
  };
  return [
    pageHead(T.usersTitle, T.usersIntro),
    card(null, h("div", { class: "table-wrap" }, h("table", { class: "responsive" },
      h("thead", {}, h("tr", {}, [T.colEmail, T.colRole, T.colStatus, T.devices, T.colSeen, T.colCreated, ""].map(t => h("th", {}, t)))),
      h("tbody", {}, users.map(u => h("tr", {},
        h("td", { "data-label": T.colEmail }, h("strong", {}, u.email), u.displayName ? h("div", { class: "caption subtle" }, u.displayName) : null),
        h("td", { "data-label": T.colRole }, u.role === "admin" ? h("span", { class: "chip accent" }, T.roleAdmin) : T.roleUser),
        h("td", { "data-label": T.colStatus }, h("span", { class: `chip ${u.disabled ? "off" : "on"}` }, u.disabled ? T.disabled : T.enabled)),
        h("td", { "data-label": T.devices, class: "num" }, `${u.devices} · ${u.activeSessions} ${T.signedIn.toLowerCase()}`),
        h("td", { "data-label": T.colSeen }, ago(u.lastSeenAt)),
        h("td", { "data-label": T.colCreated }, date(u.createdAt)),
        h("td", { class: "actions" }, u.id === session.userId ? null : h("div", { class: "row", style: "justify-content:flex-end" },
          h("button", { class: "btn secondary small", onclick: act(() => api(`/admin/users/${u.id}/role`, { method: "POST", body: { role: u.role === "admin" ? "user" : "admin" } })) },
            u.role === "admin" ? T.makeUser : T.makeAdmin),
          h("button", { class: `btn ${u.disabled ? "secondary" : "danger"} small`,
            onclick: act(() => api(`/admin/users/${u.id}/disabled`, { method: "POST", body: { disabled: !u.disabled } }), u.disabled ? null : T.confirmDisable(u.email)) },
          u.disabled ? T.enable : T.disable))))))))),
  ];
}

async function adminLogs() {
  const params = new URLSearchParams(location.hash.split("?")[1] ?? "");
  const level = params.get("level") ?? "INFO", lines = params.get("lines") ?? "200";
  const entries = await api(`/admin/logs?level=${encodeURIComponent(level)}&lines=${encodeURIComponent(lines)}`);
  const levelSelect = h("select", { "aria-label": T.level }, ["ERROR", "WARN", "INFO", "DEBUG"].map(l => h("option", { value: l, selected: l === level }, l)));
  const linesSelect = h("select", { "aria-label": T.lines }, ["100", "200", "500", "1000"].map(n => h("option", { value: n, selected: n === lines }, n)));
  const go = () => { location.hash = `#/admin/logs?level=${levelSelect.value}&lines=${linesSelect.value}`; };
  levelSelect.addEventListener("change", go);
  linesSelect.addEventListener("change", go);
  return [
    pageHead(T.logsTitle, T.logsIntro, h("label", {}, T.level, levelSelect), h("label", {}, T.lines, linesSelect),
      h("button", { class: "btn secondary", onclick: render, style: "align-self:flex-end" }, T.refresh)),
    card(null, entries.length ? h("div", { class: "table-wrap" }, h("table", { class: "logs" },
      h("thead", {}, h("tr", {}, [T.colWhen, T.colLevel, T.colSource, T.colMessage].map(t => h("th", {}, t)))),
      h("tbody", {}, entries.map(e => h("tr", {},
        h("td", { class: "num", style: "white-space:nowrap" }, e.at ? dateTime(e.at) : ""),
        h("td", {}, h("span", { class: `level ${e.level}` }, e.level)),
        h("td", { class: "subtle", title: e.logger ?? "" }, (e.logger ?? "").split(".").pop()),
        h("td", {}, e.message ?? "", e.error ? h("div", { class: "danger" }, e.error) : null)))))) : empty(T.noLogs)),
  ];
}

render();
