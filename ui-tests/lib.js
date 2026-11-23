// Shared helpers for the browser tests of the New bill (POS) screen.
// These tests create real bills, returns and customers, so they run ONLY against a local app with the
// local test data (http://localhost:8080 and db/ data), never against the live shop.
const { chromium } = require('playwright');

const BASE = (process.env.BASE_URL || 'http://localhost:8080').replace(/\/$/, '') + '/';
const OWNER = { user: process.env.OWNER_USER || 'owner', pass: process.env.OWNER_PASS || 'Owner@123' };
const STAFF = { user: process.env.STAFF_USER || 'staff', pass: process.env.STAFF_PASS || 'Staff@123' };
const SHOTS = process.env.SHOTS_DIR || '';          // set to a folder to save screenshots

if (!/^http:\/\/(localhost|127\.0\.0\.1)(:\d+)?\/$/.test(BASE) && process.env.ALLOW_REMOTE !== '1') {
  console.error(`Refusing to run against ${BASE}: these tests create bills. Use the local app (http://localhost:8080).`);
  process.exit(2);
}

function suite(name) {
  let pass = 0, fail = 0;
  const failed = [];
  return {
    check(label, ok, info = '') {
      ok ? pass++ : (fail++, failed.push(label));
      console.log(`${ok ? 'PASS' : 'FAIL'} ${label}${info ? '  [' + info + ']' : ''}`);
    },
    done() {
      console.log(`\n${name}: ${pass} passed, ${fail} failed`);
      if (fail) { console.log('Failed: ' + failed.join(' | ')); process.exitCode = 1; }
      return { pass, fail };
    },
  };
}

let checkedDb = false;
async function open(who = OWNER, viewport = { width: 1366, height: 1100 }) {
  const browser = await chromium.launch();
  const page = await (await browser.newContext({ viewport })).newPage();
  if (!checkedDb && process.env.ALLOW_REMOTE !== '1') {
    // The address can be localhost while the app still uses the shop's cloud database (.env): check that too.
    const info = await (await page.request.get(BASE + 'api/v1/public/login-info')).json();
    if (info.localDatabase !== true) {
      await browser.close();
      console.error('Refusing to run: the app at ' + BASE + ' is connected to a cloud (shop) database, not a local one.\n'
        + 'These tests create bills. Start the app WITHOUT the .env that points at Neon (local PostgreSQL), then run again.');
      process.exit(2);
    }
    checkedDb = true;
  }
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  await page.goto(BASE + 'login.html');
  await page.fill('#username', who.user);
  await page.fill('#password', who.pass);
  await Promise.all([page.waitForNavigation(), page.click('#loginBtn')]);
  if (page.url().includes('account.html')) throw new Error(`${who.user} must change the password first (log in once by hand).`);
  return { browser, page, errors };
}

/** Helpers bound to one page. */
function pos(p) {
  const wait = ms => p.waitForTimeout(ms);
  const h = {
    wait,
    locked: () => p.$eval('#completeBtn', x => x.disabled),
    msg: async () => (await p.textContent('#payMsg')).replace(/\s+/g, ' ').trim(),
    amounts: () => p.$$eval('[data-amt]', xs => xs.map(x => Number(x.value))),
    modes: () => p.$$eval('[data-mode]', xs => xs.map(x => x.value)),
    change: async () => (await p.textContent('#change')).trim(),
    total: async () => Number((await p.textContent('#tGrand')).replace(/[^\d.]/g, '')),
    type: async (sel, v) => { await p.fill(sel, String(v)); await wait(250); },
    sales: () => p.evaluate(() => api('/sales').then(r => r.length)),
    lastSale: () => p.evaluate(() => api('/sales').then(r => r[0] ? api('/sales/' + r[0].sale_id) : null)),
    async shot(name) {
      if (!SHOTS) return;
      const card = (await (await p.$('#payRows')).evaluateHandle(e => e.closest('.card'))).asElement();
      await card.screenshot({ path: `${SHOTS}/${name}.png` });
    },
    /** Opens a fresh bill. */
    async clear() {
      await p.goto(BASE + 'pos.html'); await wait(1200);
      if (await p.evaluate(() => state.cart.length)) { await p.click('#clearCart'); await p.click('#cfOk'); await wait(500); }
    },
    /** Adds a product by tapping its photo and choosing the first colour and size in stock. */
    async addProduct(productId) {
      await p.click(`.tile[data-id="${productId}"]`); await wait(700);
      for (const sel of ['#pc button:not([disabled])', '#ps button:not([disabled])']) {
        const btns = await p.$$(sel); if (btns.length) { await btns[0].click(); await wait(200); }
      }
      await p.click('#padd'); await wait(1200);
    },
    async scan(barcode, times = 1) {
      for (let k = 0; k < times; k++) { await p.fill('#scan', barcode); await p.press('#scan', 'Enter'); await wait(700); }
    },
    async addRow(mode, amount, ref) {
      await p.click('#addPay'); await wait(250);
      const n = (await h.amounts()).length - 1;
      await p.selectOption(`[data-mode="${n}"]`, mode); await wait(250);
      if (amount != null) await h.type(`[data-amt="${n}"]`, amount);
      if (ref) await h.type(`[data-ref="${n}"]`, ref);
      return n;
    },
    async customer(mobile) { await p.fill('#custMobile', mobile); await p.click('#custFindBtn'); await wait(1200); },
    /** Makes sure a variant has enough pieces (owner only), through the normal stock adjustment. */
    async topUp(barcode, min = 12) {
      await p.evaluate(async ([b, m]) => {
        const v = await api('/variants?barcode=' + b);
        if (v.stockQty < m) await api('/stock/adjustments', { method: 'POST', body: { variantId: v.id, qtyChange: 30, reason: 'Counting correction' } });
      }, [barcode, min]);
    },
  };
  return h;
}

module.exports = { BASE, OWNER, STAFF, suite, open, pos };
