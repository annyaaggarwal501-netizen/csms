// Rs 5,000 bill paid five ways, a customer with a Rs 10,000 credit limit, and "only cash can be more than the bill".
const { suite, open, pos } = require('./lib');
const SAREE = '2000000000435';   // Cotton Saree Free/Green, Rs 1,250 -> 4 pieces = Rs 5,000

(async () => {
  const t = suite('Payments: five ways, credit limit, only cash extra');
  const { browser, page: p, errors } = await open();
  const h = pos(p);
  await h.clear();
  await h.topUp(SAREE, 16);

  // a credit note worth Rs 1,050 and a new customer with a Rs 10,000 limit
  const setup = await p.evaluate(async () => {
    const s = await api('/sales', { method: 'POST', body: { lines: [{ variantId: 1, quantity: 1 }], payments: [{ mode: 'CASH', amount: 1050 }] } });
    const bill = await api('/sales/' + s.saleId);
    const r = await api('/returns', { method: 'POST', body: { invoiceNo: s.invoiceNo, reason: 'Size issue',
      lines: [{ lineId: bill.lines[0].lineId, quantity: 1, defective: false }] } });
    const mobile = '97' + String(Date.now()).slice(-8);
    const c = await api('/customers', { method: 'POST', body: { name: 'Lakshmi Devi', mobile, area: 'Behror', creditLimit: 10000 } });
    return { cn: r.cnNo, cnAmount: Number(r.amount), mobile, customerId: c.id };
  });
  const newBill = async () => { await h.clear(); await h.scan(SAREE, 4); await h.customer(setup.mobile); return h.total(); };

  // ---------- A: five ways, Rs 500 each ----------
  const T = await newBill();
  t.check('A1 bill is Rs 5,000', T === 5000, 'bill ' + T);
  t.check('A2 customer shows the Rs 10,000 limit', /of ₹10,000 limit · ₹10,000 available/.test(await p.textContent('#custBox')));
  await h.type('[data-amt="0"]', 500);
  await h.addRow('UPI', 500);
  await h.addRow('CREDIT_NOTE', 500, setup.cn);
  await h.addRow('CREDIT', 500);
  await h.addRow('CARD', 500);
  t.check('A3 all five amounts kept as typed', JSON.stringify(await h.amounts()) === '[500,500,500,500,500]', JSON.stringify(await h.amounts()));
  t.check('A4 clear error: Rs 2,500 left to collect', /₹2,500 left to collect/.test(await h.msg()), await h.msg());
  t.check('A5 locked', await h.locked());
  t.check('A6 change shows - while amounts do not add up', (await h.change()) === '—');
  await h.shot('five_left');
  await h.type('[data-amt="4"]', 3000);
  t.check('A7 card 3,000: adds up, Cash received highlighted', (await h.msg()) === '' && await h.locked()
    && await p.$eval('#cashGiven', x => x.classList.contains('need')), await h.msg());
  await h.type('#cashGiven', 300);
  t.check('A8 cash received 300: Short Rs 200, locked', (await h.change()) === 'Short ₹200' && await h.locked());
  await h.type('#cashGiven', 500);
  t.check('A9 cash received 500: unlocked', !(await h.locked()), await h.msg());
  await p.click('#completeBtn'); await h.wait(1800);
  const saved = await h.lastSale();
  const pays = saved.payments.map(x => x.mode + ' ' + Number(x.amount)).sort().join(', ');
  t.check('A10 saved with all five payments', pays === 'CARD 3000, CASH 500, CREDIT 500, CREDIT_NOTE 500, UPI 500', pays);
  const after = await p.evaluate(async s => ({ cn: await api('/credit-notes/' + s.cn), c: await api('/customers/' + s.customerId) }), setup);
  t.check('A11 credit note balance went down by 500', Number(after.cn.balance) === setup.cnAmount - 500, 'balance ' + after.cn.balance);
  t.check('A12 customer now owes 500', Number(after.c.creditDue) === 500, 'due ' + after.c.creditDue);
  await p.keyboard.press('Escape'); await h.wait(400);

  // ---------- B: Rs 10,000 credit limit ----------
  await newBill();
  t.check('B1 due 500 of 10,000, 9,500 available', /due ₹500 of ₹10,000 limit · ₹9,500 available/.test(await p.textContent('#custBox')));
  await p.selectOption('[data-mode="0"]', 'CREDIT'); await h.wait(300);
  t.check('B2 whole 5,000 on credit (inside 9,500): unlocked', !(await h.locked()) && /Credit available .*₹9,500/.test(await h.msg()), await h.msg());
  await p.click('#completeBtn'); await h.wait(1800);
  t.check('B3 saved', /Sale complete/.test(await p.textContent('.modal h2').catch(() => '')));
  await p.keyboard.press('Escape'); await h.wait(400);
  await newBill();
  t.check('B4 now due 5,500, 4,500 available', /due ₹5,500 of ₹10,000 limit · ₹4,500 available/.test(await p.textContent('#custBox')));
  await p.selectOption('[data-mode="0"]', 'CREDIT'); await h.wait(300);
  t.check('B5 another 5,000 on credit: over the limit by 500', /Over the credit limit by ₹500/.test(await h.msg()), await h.msg());
  await h.shot('credit_over');
  await p.click('#completeBtn'); await h.wait(500);
  t.check('B6 Owner must confirm before going over', /Save it over the limit/.test(await p.textContent('.modal').catch(() => '')));
  await p.click('.modal [data-close]'); await h.wait(300);
  await h.type('[data-amt="0"]', 4500);
  await h.addRow('CASH');
  t.check('B7 credit 4,500: cash fills 500 by itself', JSON.stringify(await h.amounts()) === '[4500,500]', JSON.stringify(await h.amounts()));
  await p.click('#cashQuick button >> nth=0'); await h.wait(250);
  t.check('B8 inside the limit: unlocked', !(await h.locked()) && !/Over the credit limit/.test(await h.msg()), await h.msg());
  await h.type('[data-amt="0"]', 10000);
  t.check('B9 credit 10,000 on a 5,000 bill: credit named, cash 0',
    /Credit \(udhaar\) ₹10,000 is more than what is left to pay \(₹5,000\)/.test(await h.msg()) && (await h.amounts())[1] === 0 && await h.locked(), await h.msg());

  // ---------- C: only cash can be more than the bill ----------
  await newBill();
  await h.type('[data-amt="0"]', 4000);
  await h.addRow('CREDIT', 2000);
  t.check('C1 cash 4,000 + credit 2,000: credit named, 1,000 left',
    /Credit \(udhaar\) ₹2,000 is more than what is left to pay \(₹1,000\)/.test(await h.msg()) && await h.locked(), await h.msg());
  await h.shot('credit_too_much');
  const before = await h.sales();
  await p.keyboard.press('F9'); await h.wait(900);
  t.check('C2 F9 with the error saves nothing, nothing to print', (await h.sales()) === before && !(await p.$('.modal')));
  await h.type('[data-amt="1"]', 1000);
  await h.type('#cashGiven', 4500);
  t.check('C3 credit 1,000 + cash received 4,500: change 500, unlocked', (await h.change()) === '₹500' && !(await h.locked()), await h.msg());
  await newBill();
  await h.type('[data-amt="0"]', 6000);
  t.check('C4 cash 6,000 on 5,000: enter only the cash part', /Cash amount is ₹1,000 more than the bill.*\(₹5,000\)/.test(await h.msg()) && await h.locked(), await h.msg());
  await p.selectOption('[data-mode="0"]', 'CARD'); await h.wait(250);
  t.check('C5 card 6,000: card named, 5,000 left', /Card ₹6,000 is more than what is left to pay \(₹5,000\)/.test(await h.msg()) && await h.locked(), await h.msg());
  await h.type('[data-amt="0"]', 5000);
  t.check('C6 card exactly 5,000: unlocked, no cash box', !(await h.locked()) && await p.$eval('#cashBox', x => x.classList.contains('hidden')));
  await h.clear();
  const res = await p.evaluate(async () => { try { await api('/sales', { method: 'POST', body: { lines: [{ variantId: 1, quantity: 1 }],
    payments: [{ mode: 'UPI', amount: 1100 }] } }); return 'saved'; } catch (e) { return e.message; } });
  t.check('C7 server also refuses UPI more than the bill', /cannot be more than the bill/.test(res), res);

  t.check('C8 no page errors', errors.length === 0, errors.join(' | '));
  t.done();
  await browser.close();
})().catch(e => { console.error(e); process.exitCode = 1; });
