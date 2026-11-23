// Everyday payments on the New bill screen: single cash, cash received/change, split cash + UPI/card.
const { suite, open, pos } = require('./lib');
const ANARKALI = 4;              // product 4: Anarkali Kurta, Rs 2,625

(async () => {
  const t = suite('Payments: everyday cases');
  const { browser, page: p, errors } = await open();
  const h = pos(p);
  const rs = n => '₹' + Number(n).toLocaleString('en-IN');
  const fresh = async () => { await h.clear(); await h.addProduct(ANARKALI); return h.total(); };

  // ---------- single cash ----------
  let T = await fresh();
  t.check('1 single cash amount = bill', (await h.amounts())[0] === T, 'bill ' + T);
  t.check('2 nothing received: locked, no message, Cash received box highlighted',
    await h.locked() && (await h.msg()) === '' && await p.$eval('#cashGiven', x => x.classList.contains('need')), await h.msg());
  await h.type('#cashGiven', 500);
  t.check('3 received 500: locked, change shows Short', await h.locked() && (await h.change()) === 'Short ' + rs(T - 500), await h.change());
  t.check('4 one clear message about the cash', /Cash received is .* less/.test(await h.msg()) && !/too much|left to collect/.test(await h.msg()), await h.msg());
  await h.type('#cashGiven', 0);
  t.check('5 received 0: locked', await h.locked());
  await p.click('#cashQuick button >> nth=0'); await h.wait(250);
  t.check('6 Exact: unlocked, change 0', !(await h.locked()) && (await h.change()) === '₹0', await h.change());
  await h.type('#cashGiven', T + 375);
  t.check('7 received more: unlocked, change shown', !(await h.locked()) && (await h.change()) === '₹375', await h.change());

  // ---------- split cash + UPI ----------
  T = await fresh();
  await p.click('#addPay'); await h.wait(300);
  t.check('8 split adds UPI row, cash still covers the bill (grey)',
    JSON.stringify(await h.modes()) === '["CASH","UPI"]' && JSON.stringify(await h.amounts()) === JSON.stringify([T, 0]), JSON.stringify(await h.amounts()));
  await h.type('[data-amt="1"]', 1000);
  t.check('9 UPI 1,000: cash fills the rest by itself', JSON.stringify(await h.amounts()) === JSON.stringify([T - 1000, 1000]), JSON.stringify(await h.amounts()));
  t.check('10 no "too much" message', !/too much/.test(await h.msg()), await h.msg());
  await h.type('#cashGiven', 500);
  t.check('11 received 500: locked, Short = cash part - 500', await h.locked() && (await h.change()) === 'Short ' + rs(T - 1500), await h.change());
  t.check('12 quick Exact = cash part', (await p.textContent('#cashQuick button >> nth=0')).includes(rs(T - 1000).slice(1)));
  await h.shot('split_short');
  await p.click('#cashQuick button >> nth=0'); await h.wait(250);
  t.check('13 Exact: unlocked', !(await h.locked()), await h.msg());
  await p.click('#completeBtn'); await h.wait(1600);
  const sale = await h.lastSale();
  const pays = sale.payments.map(x => x.mode + ' ' + Number(x.amount)).sort().join(', ');
  t.check('14 saved as cash rest + UPI 1,000', pays === ['CASH ' + (T - 1000), 'UPI 1000'].sort().join(', '), pays);
  await p.keyboard.press('Escape'); await h.wait(400);

  // ---------- more split cases ----------
  T = await fresh();
  await p.click('#addPay'); await h.wait(300);
  await h.type('[data-amt="1"]', T + 375);
  t.check('15 UPI more than the bill: cash 0, locked', (await h.amounts())[0] === 0 && await h.locked(), JSON.stringify(await h.amounts()));
  t.check('16 error names UPI and what is left', (await h.msg()).includes(`UPI ${rs(T + 375)} is more than what is left to pay (${rs(T)})`), await h.msg());
  await h.type('[data-amt="1"]', 1000);
  await h.type('[data-amt="0"]', 2000);
  t.check('17 both typed by hand: nothing changed, UPI named',
    JSON.stringify(await h.amounts()) === '[2000,1000]' && (await h.msg()).includes(`UPI ₹1,000 is more than what is left to pay (${rs(T - 2000)})`), await h.msg());
  await p.click('[data-rm="1"]'); await h.wait(400);
  t.check('18 remove UPI row: one cash row = bill again', JSON.stringify(await h.amounts()) === JSON.stringify([T]), JSON.stringify(await h.amounts()));

  T = await fresh();
  await p.selectOption('[data-mode="0"]', 'UPI'); await h.wait(300);
  t.check('19 UPI only: no cash box, unlocked', !(await h.locked()) && await p.$eval('#cashBox', x => x.classList.contains('hidden')), await h.msg());

  T = await fresh();
  await p.click('#addPay'); await h.wait(300);
  await p.selectOption('[data-mode="1"]', 'CARD'); await h.wait(300);
  await h.type('[data-amt="1"]', 600);
  const T2 = Number(await p.evaluate(async () => { state.cart[0].quantity++; await requote(); return grand(); }));
  await h.wait(800);
  t.check('20 cart changes during a split: cash refills the new bill', JSON.stringify(await h.amounts()) === JSON.stringify([T2 - 600, 600]), JSON.stringify(await h.amounts()));

  T = await fresh();
  await p.selectOption('[data-mode="0"]', 'CREDIT'); await h.wait(300);
  t.check('21 credit without customer: locked + clear message', await h.locked() && /Choose the customer first/.test(await h.msg()), await h.msg());

  T = await fresh();
  await h.type('[data-amt="0"]', 1000);
  t.check('22 single amount 1,000: locked, shows what is left', await h.locked() && (await h.msg()).includes(rs(T - 1000) + ' left to collect'), await h.msg());
  await h.type('[data-amt="0"]', 0);
  t.check('23 single amount 0: locked', await h.locked());
  const before = await h.sales();
  await p.keyboard.press('F9'); await h.wait(1000);
  t.check('24 F9 while locked saves nothing', (await h.sales()) === before);

  T = await fresh();
  await p.click('#addPay'); await h.wait(300);
  await h.type('[data-amt="1"]', 700);
  await p.reload(); await h.wait(2500);
  t.check('25 refresh keeps the split', JSON.stringify(await h.amounts()) === JSON.stringify([T - 700, 700]), JSON.stringify(await h.amounts()));
  await h.clear();

  t.check('26 no page errors', errors.length === 0, errors.join(' | '));
  t.done();
  await browser.close();
})().catch(e => { console.error(e); process.exitCode = 1; });
