// Edge cases on the New bill screen: wrong inputs a busy counter can type.
const { suite, open, pos, STAFF } = require('./lib');
const KURTI = '2000000000015';   // Floral Cotton Kurti S/Blue, Rs 1,050

(async () => {
  const t = suite('Payments: edge cases');
  const { browser, page: p, errors } = await open();
  const h = pos(p);
  await h.clear();
  await h.topUp(KURTI, 10);
  const fresh = async () => { await h.clear(); await h.scan(KURTI); return h.total(); };

  // negative amount
  let T = await fresh();
  await p.click('#addPay'); await h.wait(300);
  await h.type('[data-amt="1"]', -500);
  t.check('E1 UPI -500: clear error, locked', /UPI amount cannot be less than 0/.test(await h.msg()) && await h.locked(), await h.msg());
  t.check('E2 the grey cash row is not pushed above the bill', (await h.amounts())[0] === T, JSON.stringify(await h.amounts()));

  // credit note without a number
  T = await fresh();
  await p.selectOption('[data-mode="0"]', 'CREDIT_NOTE'); await h.wait(300);
  t.check('E3 credit note without number: error, locked', /Type the credit note number/.test(await h.msg()) && await h.locked(), await h.msg());
  await h.type('[data-ref="0"]', 'CN-2627-99999');
  t.check('E4 number typed: that message goes away', !/Type the credit note number/.test(await h.msg()), await h.msg());
  await p.click('#completeBtn'); await h.wait(1200);
  t.check('E5 unknown credit note: server error shown, nothing saved',
    /not found/i.test(await p.textContent('#saleErr')) && !(await p.$('.modal')), (await p.textContent('#saleErr')).trim());

  // two cash rows
  T = await fresh();
  await h.type('[data-amt="0"]', 300);
  await h.addRow('CASH');
  t.check('E6 cash 300 typed + second cash row fills the rest', JSON.stringify(await h.amounts()) === JSON.stringify([300, T - 300]), JSON.stringify(await h.amounts()));
  t.check('E7 Exact = whole cash part (both rows)', (await p.textContent('#cashQuick button >> nth=0')).includes(T.toLocaleString('en-IN')));

  // grey row changed to card
  T = await fresh();
  await h.addRow('UPI', 300);
  await p.selectOption('[data-mode="0"]', 'CARD'); await h.wait(300);
  t.check('E8 grey row switched to card still fills the rest; no cash box',
    JSON.stringify(await h.amounts()) === JSON.stringify([T - 300, 300]) && await p.$eval('#cashBox', x => x.classList.contains('hidden')) && !(await h.locked()), JSON.stringify(await h.amounts()));

  // more pieces than in stock (but within the 999 limit)
  T = await fresh();
  await p.evaluate(async () => { state.cart[0].quantity = 999; await requote(); });
  await h.wait(900);
  t.check('E9 more pieces than in stock: locked', await h.locked());
  await h.clear();
  // above the 999-per-item limit: clear message; a broken remembered bill is not brought back
  await h.scan(KURTI);
  await p.evaluate(async () => { state.cart[0].quantity = 5000; await requote(); });
  await h.wait(900);
  t.check('E9b quantity above 999: clear message', (await p.textContent('#toasts')).includes('At most 999 pieces of one item'),
    (await p.textContent('#toasts')).trim().slice(-120));
  await p.reload(); await h.wait(2000);
  t.check('E9c broken bill is not restored after a refresh', (await p.evaluate(() => state.cart.length)) === 0);

  // empty cart
  await h.clear();
  t.check('E10 empty cart: locked, no payment message', await h.locked() && (await h.msg()) === '');

  // held bill comes back with one payment row = bill
  T = await fresh();
  await h.addRow('UPI', 400);
  await p.click('#holdBtn');
  await p.waitForSelector('.toast:has-text("Bill held")', { timeout: 8000 });
  await h.wait(800);
  await p.click('#heldBtn');
  await p.waitForSelector('.modal [data-resume]', { timeout: 8000 });
  await p.click('.modal [data-resume] >> nth=0'); await h.wait(1500);
  t.check('E11 resumed held bill: one payment row = bill', JSON.stringify(await h.amounts()) === JSON.stringify([T]), JSON.stringify(await h.amounts()));
  await h.clear();

  // cash received with paise / huge number
  T = await fresh();
  await h.type('#cashGiven', 100000);
  t.check('E12 very large cash received: unlocked, big change shown', !(await h.locked()) && (await h.change()) === '₹' + (100000 - T).toLocaleString('en-IN'));
  await h.clear();
  await browser.close();

  // staff: credit for a customer without a limit needs the Owner PIN; cancelling saves nothing
  const s = await open(STAFF);
  const g = pos(s.page);
  await g.clear(); await g.scan(KURTI);
  const mobile = '96' + String(Date.now()).slice(-8);
  await s.page.evaluate(async m => api('/customers', { method: 'POST', body: { name: 'Nisha Rani', mobile: m } }), mobile);
  await g.customer(mobile);
  t.check('E13 staff sees "no credit limit" for the new customer', /No credit limit/.test(await s.page.textContent('#custBox')));
  await s.page.selectOption('[data-mode="0"]', 'CREDIT'); await g.wait(300);
  const before = await g.sales();
  await s.page.click('#completeBtn'); await g.wait(500);
  t.check('E14 staff over limit: Owner PIN asked', !!(await s.page.$('#pinIn')));
  await s.page.click('.modal [data-close]'); await g.wait(500);
  t.check('E15 cancelled: nothing saved', (await g.sales()) === before);
  await s.page.click('#completeBtn'); await g.wait(400);
  await s.page.fill('#pinIn', '0000'); await s.page.click('#pinOk'); await g.wait(1500);
  t.check('E16 wrong PIN: clear message, nothing saved', /Owner PIN is not correct/.test(await s.page.textContent('#saleErr')) && (await g.sales()) === before,
    (await s.page.textContent('#saleErr')).trim());
  await g.clear();
  await s.browser.close();

  t.check('E17 no page errors', errors.length === 0 && s.errors.length === 0, [...errors, ...s.errors].join(' | '));
  t.done();
})().catch(e => { console.error(e); process.exitCode = 1; });
