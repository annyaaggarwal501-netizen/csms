// Shared helpers for every screen: API calls, login check, navigation bar, formatting, toasts, modals.
const API = '/api/v1';

/** Call the REST API. Throws an Error with .code / .field (SRS error body) when the call fails. */
async function api(path, options = {}) {
  const opts = { credentials: 'same-origin', headers: {}, ...options };
  if (opts.body && typeof opts.body !== 'string' && !(opts.body instanceof FormData)) {
    opts.body = JSON.stringify(opts.body);
    opts.headers['Content-Type'] = 'application/json';
  }
  let res;
  try {
    res = await fetch(API + path, opts);
  } catch (e) {
    const err = new Error('Cannot reach the server. Check that the app is running.');
    err.code = 'ERR-NET-001';
    throw err;
  }
  if (res.status === 401 && !path.startsWith('/auth/login') && !path.startsWith('/public/')) {
    location.href = 'login.html?next=' + encodeURIComponent(location.pathname.replace(/^\//, '') + location.search);
    return new Promise(() => {});   // page is leaving for the login screen
  }
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (!res.ok) {
    const err = new Error((data && data.message) || ('Request failed (' + res.status + ')'));
    err.code = data && data.code;
    err.field = data && data.field;
    err.status = res.status;
    throw err;
  }
  return data;
}

const inr = new Intl.NumberFormat('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const inr0 = new Intl.NumberFormat('en-IN', { maximumFractionDigits: 0 });
function rs(n) { return '₹' + inr.format(Number(n || 0)); }
function rs0(n) { return '₹' + inr0.format(Number(n || 0)); }
function pct(n) { return Number(n).toString().replace(/\.0+$/, '') + '%'; }

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function fmtDateTime(s) {
  if (!s) return '';
  const d = new Date(s);
  return d.toLocaleString('en-IN', { day: '2-digit', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' });
}
function fmtDate(s) {
  if (!s) return '';
  return new Date(s).toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' });
}
function isoDate(d) {
  const z = new Date(d.getTime() - d.getTimezoneOffset() * 60000);
  return z.toISOString().slice(0, 10);
}
function param(name) { return new URLSearchParams(location.search).get(name); }
function debounce(fn, ms = 250) {
  let t; return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); };
}

// ---------- messages ----------
// The server sends a code with every error (e.g. ERR-BIL-003). People see only the kind of message
// ("Error:", "Warning:", "Note:") and the text; the code stays in err.code for the program and the logs.
const MSG_CODE = /^\s*(ERR|WARN|INFO)-[A-Z]+-\d+\s*[:\-–]?\s*/;
/** Removes a leading message code: "WARN-CUS-001: Over limit" -> "Over limit". */
function cleanMsg(s) { return String(s ?? '').replace(MSG_CODE, ''); }
/** [css class, label] for a code or a message that starts with one. */
function msgKind(codeOrText) {
  const k = String(codeOrText || '').trim().slice(0, 4);
  if (k.startsWith('WARN')) return ['warn', 'Warning'];
  if (k.startsWith('INFO')) return ['info', 'Note'];
  return ['err', 'Error'];
}
/** An error as an inline message box: "Error: Payments add up to …". */
function errorHtml(err) {
  const [cls, label] = msgKind(err.code || err.message);
  return `<div class="msg ${cls}"><b>${label}:</b> ${esc(cleanMsg(err.message))}</div>`;
}
/** A server warning/info line as a message box (codes removed). */
function noticeHtml(text) {
  const [cls, label] = MSG_CODE.test(text) ? msgKind(text) : ['warn', 'Warning'];
  return `<div class="msg ${cls}"><b>${label}:</b> ${esc(cleanMsg(text))}</div>`;
}
function showError(el, err) { el.innerHTML = errorHtml(err); el.classList.remove('hidden'); }
function clearMsg(el) { el.innerHTML = ''; }

function toast(message, type = '') {
  message = cleanMsg(message);
  if (type === 'err' && !/^error/i.test(message)) message = 'Error: ' + message;
  if (type === 'warn' && !/^warning/i.test(message)) message = 'Warning: ' + message;
  let box = document.getElementById('toasts');
  if (!box) { box = document.createElement('div'); box.id = 'toasts'; document.body.appendChild(box); }
  const t = document.createElement('div');
  t.className = 'toast ' + type;
  t.textContent = message;
  box.appendChild(t);
  setTimeout(() => t.remove(), type === 'err' ? 7000 : 4500);
}

/** Simple modal. Returns {el, close}. */
function openModal(html, { wide = false, onClose } = {}) {
  const back = document.createElement('div');
  back.className = 'modal-back';
  back.innerHTML = `<div class="modal ${wide ? 'wide' : ''}">${html}</div>`;
  document.body.appendChild(back);
  const close = () => { back.remove(); document.removeEventListener('keydown', onKey); onClose && onClose(); };
  const onKey = e => { if (e.key === 'Escape') close(); };
  document.addEventListener('keydown', onKey);
  back.addEventListener('mousedown', e => { if (e.target === back) close(); });
  back.querySelectorAll('[data-close]').forEach(b => b.addEventListener('click', close));
  return { el: back.querySelector('.modal'), close };
}

function confirmBox(message, okLabel = 'Yes') {
  return new Promise(resolve => {
    const m = openModal(`<h2>Please confirm</h2><p>${esc(message)}</p>
      <div class="actions"><button data-close>Cancel</button><button class="primary" id="cfOk">${esc(okLabel)}</button></div>`,
      { onClose: () => resolve(false) });
    m.el.querySelector('#cfOk').onclick = () => { resolve(true); m.close(); };
  });
}

/** Ask for the Owner PIN (discount override, late return). Resolves to the PIN or null. */
function askOwnerPin(reason) {
  return new Promise(resolve => {
    let done = false;
    const m = openModal(`<h2>Owner PIN needed</h2><p class="muted">${esc(reason)}</p>
      <div class="field"><label>Owner PIN</label><input type="password" id="pinIn" inputmode="numeric" maxlength="6" autocomplete="off"></div>
      <div class="actions"><button data-close>Cancel</button><button class="primary" id="pinOk">Approve</button></div>`,
      { onClose: () => { if (!done) resolve(null); } });
    const inp = m.el.querySelector('#pinIn');
    inp.focus();
    const ok = () => { done = true; resolve(inp.value.trim()); m.close(); };
    m.el.querySelector('#pinOk').onclick = ok;
    inp.addEventListener('keydown', e => { if (e.key === 'Enter') ok(); });
  });
}

// ---------- remembered on this device ----------
// Small things the app remembers between visits (open bill, filters, chosen tabs), per user, in this browser.
// Never prices or stock: those always come fresh from the server.
const store = {
  key(k) { return 'csms:' + ((window.ME && window.ME.username) || '_') + ':' + k; },
  get(k, fallback = null) {
    try { const v = localStorage.getItem(this.key(k)); return v == null ? fallback : JSON.parse(v); }
    catch (e) { return fallback; }
  },
  set(k, v) { try { localStorage.setItem(this.key(k), JSON.stringify(v)); } catch (e) { /* private mode / full */ } },
  del(k) { try { localStorage.removeItem(this.key(k)); } catch (e) { /* ignore */ } },
};

// ---------- login check + navigation ----------

/** Line icons (Lucide, ISC licence) used in the menu and on buttons. */
const ICONS = {
  dashboard: '<rect x="3" y="3" width="7" height="9" rx="1"/><rect x="14" y="3" width="7" height="5" rx="1"/><rect x="14" y="12" width="7" height="9" rx="1"/><rect x="3" y="16" width="7" height="5" rx="1"/>',
  cart: '<circle cx="8" cy="21" r="1"/><circle cx="19" cy="21" r="1"/><path d="M2.05 2.05h2l2.66 12.42a2 2 0 0 0 2 1.58h9.78a2 2 0 0 0 1.95-1.57l1.65-7.43H5.12"/>',
  returns: '<path d="M9 14 4 9l5-5"/><path d="M4 9h10.5a5.5 5.5 0 0 1 5.5 5.5a5.5 5.5 0 0 1-5.5 5.5H11"/>',
  users: '<path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M22 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/>',
  wallet: '<path d="M19 7V4a1 1 0 0 0-1-1H5a2 2 0 0 0 0 4h15a1 1 0 0 1 1 1v4h-3a2 2 0 0 0 0 4h3a1 1 0 0 0 1-1v-2a1 1 0 0 0-1-1"/><path d="M3 5v14a2 2 0 0 0 2 2h15a1 1 0 0 0 1-1v-4"/>',
  shirt: '<path d="M20.38 3.46 16 2a4 4 0 0 1-8 0L3.62 3.46a2 2 0 0 0-1.34 2.23l.58 3.47a1 1 0 0 0 .99.84H6v10c0 1.1.9 2 2 2h8a2 2 0 0 0 2-2V10h2.15a1 1 0 0 0 .99-.84l.58-3.47a2 2 0 0 0-1.34-2.23z"/>',
  box: '<path d="m7.5 4.27 9 5.15"/><path d="M21 8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16Z"/><path d="m3.3 7 8.7 5 8.7-5"/><path d="M12 22V12"/>',
  truck: '<path d="M14 18V6a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2v11a1 1 0 0 0 1 1h2"/><path d="M15 18H9"/><path d="M19 18h2a1 1 0 0 0 1-1v-3.65a1 1 0 0 0-.22-.624l-3.48-4.35A1 1 0 0 0 17.52 8H14"/><circle cx="17" cy="18" r="2"/><circle cx="7" cy="18" r="2"/>',
  barcode: '<path d="M3 5v14"/><path d="M8 5v14"/><path d="M12 5v14"/><path d="M17 5v14"/><path d="M21 5v14"/>',
  percent: '<line x1="19" x2="5" y1="5" y2="19"/><circle cx="6.5" cy="6.5" r="2.5"/><circle cx="17.5" cy="17.5" r="2.5"/>',
  folder: '<path d="M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z"/>',
  building: '<rect x="4" y="2" width="16" height="20" rx="2"/><path d="M9 22v-4h6v4"/><path d="M8 6h.01M16 6h.01M12 6h.01M12 10h.01M12 14h.01M16 10h.01M16 14h.01M8 10h.01M8 14h.01"/>',
  chart: '<path d="M3 3v18h18"/><path d="M18 17V9"/><path d="M13 17V5"/><path d="M8 17v-3"/>',
  history: '<path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"/><path d="M3 3v5h5"/><path d="M12 7v5l4 2"/>',
  usercog: '<circle cx="18" cy="15" r="3"/><circle cx="9" cy="7" r="4"/><path d="M10 15H6a4 4 0 0 0-4 4v2"/><path d="m21.7 16.4-.9-.3"/><path d="m15.2 13.9-.9-.3"/><path d="m16.6 18.7.3-.9"/><path d="m19.1 12.2.3-.9"/>',
  sliders: '<line x1="4" x2="4" y1="21" y2="14"/><line x1="4" x2="4" y1="10" y2="3"/><line x1="12" x2="12" y1="21" y2="12"/><line x1="12" x2="12" y1="8" y2="3"/><line x1="20" x2="20" y1="21" y2="16"/><line x1="20" x2="20" y1="12" y2="3"/><line x1="2" x2="6" y1="14" y2="14"/><line x1="10" x2="14" y1="8" y2="8"/><line x1="18" x2="22" y1="16" y2="16"/>',
  key: '<circle cx="7.5" cy="15.5" r="5.5"/><path d="m21 2-9.6 9.6"/><path d="m15.5 7.5 3 3L22 7l-3-3"/>',
  logout: '<path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" x2="9" y1="12" y2="12"/>',
  menu: '<line x1="4" x2="20" y1="12" y2="12"/><line x1="4" x2="20" y1="6" y2="6"/><line x1="4" x2="20" y1="18" y2="18"/>',
  search: '<circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/>',
  plus: '<path d="M5 12h14"/><path d="M12 5v14"/>',
  download: '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" x2="12" y1="15" y2="3"/>',
  printer: '<polyline points="6 9 6 2 18 2 18 9"/><path d="M6 18H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-2"/><rect x="6" y="14" width="12" height="8"/>',
  inbox: '<polyline points="22 12 16 12 14 15 10 15 8 12 2 12"/><path d="M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z"/>',
};
function icon(name, cls = 'i') { return `<svg class="${cls}" viewBox="0 0 24 24" aria-hidden="true">${ICONS[name] || ''}</svg>`; }

const O = ['OWNER'], ALL = ['OWNER', 'SALES_STAFF'];
const NAV = [
  { group: 'Sell' },
  { href: 'dashboard.html', label: 'Dashboard', icon: 'dashboard', roles: O },
  { href: 'pos.html', label: 'New bill', icon: 'cart', roles: ALL },
  { href: 'returns.html', label: 'Returns & exchange', icon: 'returns', roles: ALL },
  { href: 'customers.html', label: 'Customers', icon: 'users', roles: ALL },
  { href: 'credit.html', label: 'Credit (udhaar)', icon: 'wallet', roles: ALL },
  { group: 'Stock' },
  { href: 'products.html', label: 'Products', icon: 'shirt', roles: ALL, also: ['product-edit.html'] },
  { href: 'stock.html', label: 'Stock', icon: 'box', roles: ALL },
  { href: 'stock-in.html', label: 'Stock-in (purchase)', icon: 'truck', roles: O },
  { href: 'labels.html', label: 'Barcode labels', icon: 'barcode', roles: ALL },
  { group: 'Shop' },
  { href: 'offers.html', label: 'Offers', icon: 'percent', roles: ALL },
  { href: 'categories.html', label: 'Categories', icon: 'folder', roles: O },
  { href: 'suppliers.html', label: 'Suppliers', icon: 'building', roles: O },
  { group: 'Owner' },
  { href: 'reports.html', label: 'Reports', icon: 'chart', roles: O },
  { href: 'activity.html', label: 'Activity log', icon: 'history', roles: O },
  { href: 'users.html', label: 'Staff & logins', icon: 'usercog', roles: O },
  { href: 'settings.html', label: 'Settings', icon: 'sliders', roles: O },
];

function homeFor(me) { return me.role === 'OWNER' ? 'dashboard.html' : 'pos.html'; }

/** Every protected page calls this first. Redirects to login, or home if the role may not see the page. */
async function requireLogin(allowedRoles) {
  const me = await api('/auth/me');
  const page = location.pathname.split('/').pop() || 'index.html';
  if (me.mustChangePassword && page !== 'account.html') {
    location.href = 'account.html?first=1';
    return new Promise(() => {});
  }
  if (allowedRoles && !allowedRoles.includes(me.role)) {
    location.href = homeFor(me);
    return new Promise(() => {});   // page is leaving; never run the rest of its setup
  }
  window.ME = me;
  await renderShell(me).catch(() => renderShell(me, true));
  return me;
}

async function renderShell(me, offline) {
  const shop = offline ? {} : await shopSettings().catch(() => ({}));
  const shopName = shop.shopName || 'My Shop';
  buildShell(me, shopName);
  // Remember the menu for this browser tab, so the next page shows it at once (no jump while it loads).
  try {
    if (!me.mustChangePassword) sessionStorage.setItem('csms:shell', JSON.stringify({ me, shopName }));
    else sessionStorage.removeItem('csms:shell');
  } catch (e) { /* private mode */ }
  if (shop.shopName) document.title = document.title.replace(/–.*$/, '– ' + shop.shopName);
}

/** Draws (or redraws) the side menu and the phone top bar. Safe to call twice: it replaces the old one. */
function buildShell(me, shopName) {
  const page = location.pathname.split('/').pop() || 'index.html';
  let html = '', pendingGroup = null, current = null;
  for (const n of me.mustChangePassword ? [] : NAV) {   // no menu until the temporary password is changed
    if (n.group) { pendingGroup = n.group; continue; }
    if (!n.roles.includes(me.role)) continue;
    if (pendingGroup) { html += `<div class="group">${pendingGroup}</div>`; pendingGroup = null; }
    const active = page === n.href || (n.also || []).includes(page);
    if (active) current = n;
    html += `<a href="${n.href}" class="${active ? 'active' : ''}">${icon(n.icon)}<span>${n.label}</span></a>`;
  }
  const initials = (me.fullName || me.username).split(/\s+/).map(w => w[0]).slice(0, 2).join('').toUpperCase();
  const side = document.createElement('aside');
  side.className = 'sidebar';
  side.innerHTML = `<a class="brand" href="${homeFor(me)}"><span class="mark">${esc(shopName[0] || 'S')}</span>
      <span><b>${esc(shopName)}</b><small>Shop manager</small></span></a>
    <nav>${html}</nav>
    <div class="me"><span class="avatar">${esc(initials)}</span>
      <a class="who" href="account.html" title="My account"><b>${esc(me.fullName)}</b><span>${me.role === 'OWNER' ? 'Owner' : 'Sales staff'} · My account</span></a>
      <button id="logoutBtn" title="Log out" aria-label="Log out">${icon('logout')}</button></div>`;
  const bar = document.createElement('header');
  bar.className = 'mobilebar';
  bar.innerHTML = `<button id="navToggle" aria-label="Menu">${icon('menu')}</button><span class="title">${esc(current ? current.label : shopName)}</span>`;
  const back = document.createElement('div');
  back.className = 'shell-back';
  const old = { side: document.querySelector('body > .sidebar'), bar: document.querySelector('body > .mobilebar'),
                back: document.querySelector('body > .shell-back') };
  const keepScroll = old.side ? old.side.querySelector('nav').scrollTop : 0;
  if (old.side) { old.side.replaceWith(side); old.bar.replaceWith(bar); old.back.replaceWith(back); }
  else { document.body.prepend(back); document.body.prepend(bar); document.body.prepend(side); }
  side.querySelector('nav').scrollTop = keepScroll;
  document.body.classList.add('app');
  bar.querySelector('#navToggle').onclick = () => document.body.classList.toggle('nav-open');
  back.onclick = () => document.body.classList.remove('nav-open');
  side.querySelector('#logoutBtn').onclick = async () => {
    try { sessionStorage.removeItem('csms:shell'); } catch (e) { /* ignore */ }
    await api('/auth/logout', { method: 'POST' }).catch(() => {});
    location.href = 'login.html';
  };
}

/** Download rows as a CSV file that opens in Excel (dates and money as plain text). */
function downloadCsv(filename, columns, rows) {
  const cell = v => { const s = String(v ?? ''); return /[",\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s; };
  const lines = [columns.map(c => cell(c[1])).join(',')]
    .concat(rows.map(r => columns.map(c => cell(typeof c[0] === 'function' ? c[0](r) : r[c[0]])).join(',')));
  const blob = new Blob(['﻿' + lines.join('\r\n')], { type: 'text/csv;charset=utf-8' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = filename;
  a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 2000);
}

function emptyState(text, iconName = 'inbox') {
  return `<div class="empty-state">${icon(iconName)}<div>${esc(text)}</div></div>`;
}

/** Puts the server's error message under the matching form field (err.field) or in the box. */
function formError(form, box, err) {
  form.querySelectorAll('.invalid').forEach(x => x.classList.remove('invalid'));
  const f = err.field && form.querySelector(`[name="${err.field}"]`);
  if (f) { f.classList.add('invalid'); f.focus(); }
  showError(box, err);
}

/** Reads a form's named inputs into an object ('' becomes null, number inputs become numbers). */
function formData(form) {
  const o = {};
  form.querySelectorAll('[name]').forEach(el => {
    if (el.type === 'checkbox') o[el.name] = el.checked;
    else if (el.type === 'radio') { if (el.checked) o[el.name] = el.value; }
    else if (el.type === 'number') o[el.name] = el.value === '' ? null : Number(el.value);
    else o[el.name] = el.value.trim() === '' ? null : el.value.trim();
  });
  return o;
}

function stockBadge(v) {
  const q = v.stockQty ?? v.stock_qty, r = v.reorderLevel ?? v.reorder_level;
  if (q <= 0) return '<span class="badge err">Out of stock</span>';
  if (q <= r) return `<span class="badge warn">Low: ${q}</span>`;
  return `<span class="badge ok">${q} in stock</span>`;
}

/** <img> for a product photo (small copy); shows "No photo yet" when the product has none. */
function productImg(productId, cls = 'thumb', bust = '') {
  return `<img class="${cls}" loading="lazy" alt="" data-pid="${productId}"
    src="${API}/products/${productId}/image?size=thumb${bust ? '&t=' + bust : ''}" onerror="this.classList.add('missing')">`;
}

/**
 * Makes any phone photo upload-ready: turns it into a JPEG with the longest side at most 1200 px.
 * A 6 MB phone photo becomes ~300-700 KB, well under the 2 MB limit (ERR-PRD-006).
 */
async function preparePhoto(file) {
  if (!file) return null;
  if (file.type === 'image/jpeg' && file.size <= 700 * 1024) return file;   // already small enough
  let bitmap;
  try { bitmap = await createImageBitmap(file); }
  catch (e) { return file; }   // browser can't read it: send as it is, the server checks the type
  const scale = Math.min(1, 1200 / Math.max(bitmap.width, bitmap.height));
  const canvas = document.createElement('canvas');
  canvas.width = Math.round(bitmap.width * scale);
  canvas.height = Math.round(bitmap.height * scale);
  const c = canvas.getContext('2d');
  c.fillStyle = '#fff';
  c.fillRect(0, 0, canvas.width, canvas.height);
  c.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
  const blob = await new Promise(r => canvas.toBlob(r, 'image/jpeg', 0.85));
  return new File([blob], (file.name || 'photo').replace(/\.[^.]+$/, '') + '.jpg', { type: 'image/jpeg' });
}

/** Uploads (or replaces) a product's photo. */
async function uploadProductPhoto(productId, file) {
  const fd = new FormData();
  fd.append('file', await preparePhoto(file));
  return api(`/products/${productId}/image`, { method: 'PUT', body: fd });
}

let _settings = null;
/** Shop settings (cached per page), e.g. { gstRegistered: false }. */
async function shopSettings() {
  if (!_settings) _settings = await api('/settings');
  return _settings;
}

// Installable app (Add to Home screen / Install app) – see sw.js.
if ('serviceWorker' in navigator && location.protocol !== 'file:') {
  window.addEventListener('load', () => navigator.serviceWorker.register('sw.js').catch(() => {}));
}

// ---------- smooth page changes ----------

// 1) Draw the remembered menu straight away, so the page does not jump when the menu arrives.
// 2) Let the page content ease in (body.entering, see app.css), only while the page opens.
(function smoothStart() {
  const page = location.pathname.split('/').pop() || 'index.html';
  if (['login.html', 'receipt.html', 'ebill.html', 'index.html'].includes(page)) return;
  try {
    const cached = JSON.parse(sessionStorage.getItem('csms:shell') || 'null');
    if (cached && cached.me) { window.ME = window.ME || cached.me; buildShell(cached.me, cached.shopName || 'My Shop'); }
  } catch (e) { /* no remembered menu: it is drawn after the login check */ }
  document.body.classList.add('entering');
  setTimeout(() => document.body.classList.remove('entering'), 1200);
})();
