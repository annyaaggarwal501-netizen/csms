// POS billing screen (UC-08). The server prices the cart (/sales/quote) on every change,
// so offers, GST and totals on screen are exactly what will be saved.

const PAY_MODES = [
  ['CASH', 'Cash'], ['UPI', 'UPI'], ['CARD', 'Card'], ['CREDIT', 'Credit (udhaar)'], ['CREDIT_NOTE', 'Credit note'],
];

const state = {
  cart: [],            // [{variantId, quantity, manualDiscount}]
  quote: null,         // last /sales/quote response
  customer: null,      // {id, name, mobile, waOptIn, loyaltyPoints, ...}
  payments: [{ mode: 'CASH', amount: 0, reference: '' }],
  autoPay: true,       // keep the single payment row equal to the total until the user edits it
  ownerPin: null,      // remembered for this bill after the Owner approves
  creditNote: null,    // {cnNo, balance} when coming from an exchange
};

const $ = id => document.getElementById(id);
let searchResults = [];
let selIndex = -1;

requireLogin().then(init);

async function init() {
  window.GST_REGISTERED = (await shopSettings()).gstRegistered;
  $('scan').addEventListener('keydown', onScanKey);
  $('scan').addEventListener('input', debounce(onScanInput, 200));
  $('clearCart').onclick = clearCart;
  $('custFindBtn').onclick = findCustomer;
  $('custMobile').addEventListener('keydown', e => { if (e.key === 'Enter') findCustomer(); });
  $('addPay').onclick = () => {
    state.autoPay = false;
    const paid = state.payments.reduce((s, p) => s + Number(p.amount || 0), 0);
    const left = Math.max(0, grand() - paid);
    state.payments.push({ mode: 'UPI', amount: left, reference: '' });
    renderPayments();
  };
  $('cashGiven').addEventListener('input', renderChange);
  $('completeBtn').onclick = complete;
  $('holdBtn').onclick = holdBill;
  $('heldBtn').onclick = showHeld;
  refreshHeldCount();
  document.addEventListener('keydown', e => {
    if (e.key === 'F2') { e.preventDefault(); $('scan').focus(); }
    if (e.key === 'F4') { e.preventDefault(); $('custMobile').focus(); }
    if (e.key === 'F8') { e.preventDefault(); holdBill(); }
    if (e.key === 'F9') { e.preventDefault(); if (!$('completeBtn').disabled) complete(); }
  });
  document.addEventListener('click', e => { if (!e.target.closest('.scan-box')) hideResults(); });

  initBrowse();
  const cn = param('cn');
  if (cn) loadCreditNote(cn);
  else await restoreDraft();
  render();
}

// ---------- unfinished bill is remembered on this device ----------
// If the page is refreshed, the browser closes or someone opens another screen, the open bill comes back.
// Prices, offers and stock are always checked again by the server when it comes back.

function saveDraft() {
  if (state.saved || !state.cart.length) { store.del('posDraft'); return; }
  store.set('posDraft', { at: Date.now(), cart: state.cart, customerId: state.customer && state.customer.id,
    payments: state.payments, autoPay: state.autoPay, creditNote: state.creditNote, cashGiven: $('cashGiven').value });
}

async function restoreDraft() {
  const d = store.get('posDraft');
  if (!d || !Array.isArray(d.cart) || !d.cart.length || Date.now() - d.at > 24 * 3600e3) { store.del('posDraft'); return; }
  state.cart = d.cart;
  state.payments = d.payments && d.payments.length ? d.payments : state.payments;
  state.autoPay = d.autoPay !== false;
  state.creditNote = d.creditNote || null;
  $('cashGiven').value = d.cashGiven || '';
  if (d.customerId) {
    try { state.customer = await api('/customers/' + d.customerId); } catch (e) { state.customer = null; }
  }
  if (state.customer) setCustomer(state.customer); else await requote();
  toast(`Your unfinished bill is back (${state.cart.reduce((s, l) => s + l.quantity, 0)} pcs).`, 'ok');
}

function grand() { return state.quote ? Number(state.quote.grandTotal) : 0; }

// ---------- scanning & search ----------

function beep() {
  try {
    const ctx = new (window.AudioContext || window.webkitAudioContext)();
    const o = ctx.createOscillator(); o.frequency.value = 330; o.connect(ctx.destination);
    o.start(); setTimeout(() => { o.stop(); ctx.close(); }, 180);
  } catch (e) { /* no audio */ }
}

async function onScanKey(e) {
  if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
    if (!searchResults.length) return;
    e.preventDefault();
    selIndex = (selIndex + (e.key === 'ArrowDown' ? 1 : -1) + searchResults.length) % searchResults.length;
    drawResults();
    return;
  }
  if (e.key === 'Escape') { hideResults(); return; }
  if (e.key !== 'Enter') return;
  e.preventDefault();
  const code = $('scan').value.trim();
  if (!code) return;
  if (selIndex >= 0 && searchResults[selIndex]) {
    addVariant(searchResults[selIndex]);
    return;
  }
  // A barcode scanner types digits then Enter. SKUs look like KUR-0001-M-BLU.
  if (/^\d{6,}$/.test(code) || /^[A-Z]{3}-\d{4}-/i.test(code)) {
    try {
      const isBarcode = /^\d+$/.test(code);
      const v = await api('/variants?' + (isBarcode ? 'barcode=' : 'sku=') + encodeURIComponent(code.toUpperCase()));
      addVariant(v);
    } catch (err) {
      beep();
      $('scanMsg').innerHTML = errorHtml(err);
      $('scan').select();
    }
    return;
  }
  await onScanInput();
  if (searchResults.length === 1) addVariant(searchResults[0]);
}

async function onScanInput() {
  const q = $('scan').value.trim();
  $('scanMsg').innerHTML = '';
  if (q.length < 2 || /^\d+$/.test(q)) { hideResults(); return; }
  try {
    searchResults = await api('/variants/search?q=' + encodeURIComponent(q));
    selIndex = searchResults.length ? 0 : -1;
    drawResults();
  } catch (err) { $('scanMsg').innerHTML = errorHtml(err); }
}

function drawResults() {
  const box = $('results');
  if (!searchResults.length) {
    box.innerHTML = '<div class="muted">No matching items</div>';
  } else {
    box.innerHTML = searchResults.map((v, i) => `<div data-i="${i}" class="${i === selIndex ? 'sel' : ''}">
      <span class="with-thumb">${productImg(v.productId, 'thumb sm')}<span><b>${esc(v.productName)}</b> ${esc(v.size)} / ${esc(v.colour)} <span class="muted small">${esc(v.sku)}</span></span></span>
      <span>${rs0(v.sellingPrice)} ${stockBadge(v)}</span></div>`).join('');
    box.querySelectorAll('[data-i]').forEach(d => d.onclick = () => addVariant(searchResults[d.dataset.i]));
  }
  box.classList.remove('hidden');
}
function hideResults() { $('results').classList.add('hidden'); searchResults = []; selIndex = -1; }

function addVariant(v) {
  hideResults();
  $('scan').value = '';
  $('scanMsg').innerHTML = '';
  const line = state.cart.find(l => l.variantId === v.id);
  const inCart = line ? line.quantity : 0;
  if (v.stockQty <= inCart) {
    beep();
    $('scanMsg').innerHTML = errorHtml({ code: 'ERR-INV-001', message: `${v.productName} ${v.size}/${v.colour}: only ${v.stockQty} in stock.` });
    return;
  }
  if (line) line.quantity++;
  else state.cart.push({ variantId: v.id, quantity: 1, manualDiscount: 0 });
  $('scanMsg').innerHTML = `<span class="badge ok">Added</span> ${esc(v.productName)} ${esc(v.size)}/${esc(v.colour)} – ${rs0(v.sellingPrice)}`;
  requote();
  $('scan').focus();
}

// ---------- cart ----------

async function requote() {
  if (!state.cart.length) { state.quote = null; render(); return; }
  try {
    state.quote = await api('/sales/quote', { method: 'POST', body: {
      customerId: state.customer && state.customer.id, lines: state.cart } });
  } catch (err) {
    toast(err.message, 'err');
  }
  render();
}

function changeQty(i, delta) {
  const l = state.cart[i];
  l.quantity += delta;
  if (l.quantity <= 0) state.cart.splice(i, 1);
  requote();
}

async function editDiscount(i) {
  const q = state.quote.lines[i];
  const m = openModal(`<h2>Discount on ${esc(q.productName)} ${esc(q.size)}/${esc(q.colour)}</h2>
    <p class="muted small">Price ${rs(q.unitPrice)} per piece. ${q.offerName ? 'Offer running: <b>' + esc(q.offerName) + '</b> (' + rs(q.unitDiscount) + '). Offers and manual discounts do not stack; the larger one is used.' : ''}
    Staff can give up to 10% without the Owner PIN. GST is worked out on the price after discount.</p>
    <div class="row">
      <div class="field grow"><label>Discount per piece (₹)</label><input type="number" id="dAmt" min="0" step="1" value="${Number(q.manualDiscount) || ''}"></div>
      <div class="field grow"><label>or percent (%)</label><input type="number" id="dPct" min="0" max="90" step="1"></div>
    </div>
    <div class="actions"><button id="dClear">Remove discount</button><button data-close>Cancel</button><button class="primary" id="dOk">Apply</button></div>`);
  const amt = m.el.querySelector('#dAmt'), pctIn = m.el.querySelector('#dPct');
  amt.focus();
  pctIn.addEventListener('input', () => { amt.value = (Number(q.unitPrice) * Number(pctIn.value || 0) / 100).toFixed(2); });
  const apply = v => { state.cart[i].manualDiscount = Number(v) || 0; m.close(); requote(); };
  m.el.querySelector('#dOk').onclick = () => apply(amt.value);
  m.el.querySelector('#dClear').onclick = () => apply(0);
  amt.addEventListener('keydown', e => { if (e.key === 'Enter') apply(amt.value); });
}

async function clearCart() {
  if (!state.cart.length) return;
  if (!(await confirmBox('Remove all items from this bill?', 'Clear cart'))) return;
  resetSale();
}

function resetSale() {
  state.saved = false;
  store.del('posDraft');
  state.cart = [];
  state.quote = null;
  state.payments = [{ mode: 'CASH', amount: 0, reference: '' }];
  state.autoPay = true;
  state.ownerPin = null;
  state.creditNote = null;
  setCustomer(null);
  $('cashGiven').value = '';
  $('payMsg').innerHTML = '';
  $('custMsg').innerHTML = '';
  $('custMobile').value = '';
  history.replaceState(null, '', 'pos.html');
  render();
  $('scan').focus();
}

function render() {
  $('saleErr').innerHTML = '';
  const q = state.quote;
  const body = $('cartBody');
  if (!q || !q.lines.length) {
    body.innerHTML = `<tr><td colspan="7" class="cart-empty">Scan a barcode to start the bill</td></tr>`;
  } else {
    body.innerHTML = q.lines.map((l, i) => `<tr>
      <td><div class="with-thumb">${productImg(l.productId)}<div><div class="item-name">${esc(l.productName)}</div>
          <div class="small muted">${esc(l.size)} / ${esc(l.colour)} · ${esc(l.sku)}${q.gstRegistered && l.hsnCode ? ' · HSN ' + esc(l.hsnCode) : ''}
          ${q.gstRegistered && l.taxClass === 'FABRIC' ? '<span class="badge info">Fabric</span>' : ''}
          ${!l.enoughStock ? '<span class="badge err">Only ' + l.stockQty + ' in stock</span>' : ''}</div></div></div></td>
      <td class="num">${rs(l.unitPrice)}${Number(l.mrp) > Number(l.unitPrice) ? '<div class="small muted"><s>' + rs0(l.mrp) + '</s></div>' : ''}</td>
      <td class="num">${Number(l.unitDiscount) > 0 ? '−' + rs(l.unitDiscount) : '<span class="muted">—</span>'}
          ${l.offerName ? '<div class="offer-tag">' + esc(l.offerName) + '</div>' : ''}
          <div><button class="small" data-disc="${i}">Edit</button></div></td>
      <td><span class="qty"><button data-minus="${i}">−</button><span>${l.quantity}</span><button data-plus="${i}">+</button></span></td>
      <td class="num gst-col">${pct(l.gstRate)}${l.slabWarning ? ' <span class="badge warn" title="Price is close to the 5%/18% GST boundary">!</span>' : ''}</td>
      <td class="num"><b>${rs(l.lineTotal)}</b></td>
      <td><button class="icon-btn danger" data-del="${i}" title="Remove">×</button></td></tr>`).join('');
    body.querySelectorAll('[data-minus]').forEach(b => b.onclick = () => changeQty(+b.dataset.minus, -1));
    body.querySelectorAll('[data-plus]').forEach(b => b.onclick = () => changeQty(+b.dataset.plus, +1));
    body.querySelectorAll('[data-del]').forEach(b => b.onclick = () => { state.cart.splice(+b.dataset.del, 1); requote(); });
    body.querySelectorAll('[data-disc]').forEach(b => b.onclick = () => editDiscount(+b.dataset.disc));
  }
  $('cartCount').textContent = q ? `(${q.totalQty} pcs)` : '';

  $('warnings').innerHTML = (q ? q.warnings : []).map(noticeHtml).join('');

  $('tQty').textContent = q ? q.totalQty : 0;
  $('tSub').textContent = rs(q ? q.subtotal : 0);
  $('tDisc').textContent = q && Number(q.discountTotal) > 0 ? '−' + rs(q.discountTotal) : rs(0);
  $('tTaxable').textContent = rs(q ? q.taxableTotal : 0);
  $('tGst').innerHTML = (q ? q.taxSummary : []).map(r => `
    <tr><td>CGST ${pct(r.rate / 2)} <span class="muted small">(on ${rs(r.taxable)})</span></td><td class="num">${rs(r.cgst)}</td></tr>
    <tr><td>SGST ${pct(r.rate / 2)}</td><td class="num">${rs(r.sgst)}</td></tr>`).join('');
  $('tRound').textContent = rs(q ? q.roundOff : 0);
  // Shop without GST (Bill of Supply): no GST column, no taxable-value row.
  const gst = q ? q.gstRegistered : (window.GST_REGISTERED ?? true);
  document.querySelectorAll('.gst-col').forEach(e => e.classList.toggle('hidden', !gst));
  $('tGrand').textContent = rs0(q ? q.grandTotal : 0);

  if (state.autoPay) syncPayments();
  renderPayments();
}

// ---------- payments ----------

function syncPayments() {
  const total = grand();
  if (state.creditNote) {
    const cnAmt = Math.min(Number(state.creditNote.balance), total);
    state.payments = [{ mode: 'CREDIT_NOTE', amount: cnAmt, reference: state.creditNote.cnNo }];
    if (total - cnAmt > 0) state.payments.push({ mode: 'CASH', amount: total - cnAmt, reference: '' });
  } else if (state.payments.length === 1) {
    state.payments[0].amount = total;
  }
}

function renderPayments() {
  const box = $('payRows');
  box.innerHTML = state.payments.map((p, i) => `<div class="pay-row">
      <select data-mode="${i}">${PAY_MODES.map(([v, t]) => `<option value="${v}" ${p.mode === v ? 'selected' : ''}>${t}</option>`).join('')}</select>
      <input type="number" data-amt="${i}" value="${p.amount}" min="0" step="1">
      <input type="text" data-ref="${i}" value="${esc(p.reference)}" placeholder="${refHint(p.mode)}">
      <button class="icon-btn" data-rm="${i}" ${state.payments.length === 1 ? 'disabled' : ''} title="Remove">×</button></div>`).join('');
  box.querySelectorAll('[data-mode]').forEach(s => s.onchange = () => {
    state.payments[+s.dataset.mode].mode = s.value; renderPayments();
  });
  box.querySelectorAll('[data-amt]').forEach(s => s.oninput = () => {
    state.autoPay = false; state.payments[+s.dataset.amt].amount = s.value; renderChange(); updateComplete();
  });
  box.querySelectorAll('[data-ref]').forEach(s => s.oninput = () => { state.payments[+s.dataset.ref].reference = s.value; });
  box.querySelectorAll('[data-rm]').forEach(b => b.onclick = () => {
    state.payments.splice(+b.dataset.rm, 1);
    if (state.payments.length === 1) state.autoPay = true;
    render();
  });
  $('cashBox').classList.toggle('hidden', !state.payments.some(p => p.mode === 'CASH'));
  renderChange();
  updateComplete();
}

function refHint(mode) {
  return { UPI: 'UPI ref (optional)', CARD: 'Last 4 digits', CREDIT_NOTE: 'CN-2627-00001', CREDIT: 'Note', CASH: '' }[mode] || '';
}

function renderChange() {
  const cash = state.payments.filter(p => p.mode === 'CASH').reduce((s, p) => s + Number(p.amount || 0), 0);
  const given = Number($('cashGiven').value || 0);
  $('change').textContent = given > 0 ? rs0(Math.max(0, given - cash)) : '₹0';
  $('change').style.color = given > 0 && given < cash ? 'var(--err)' : '';
}

function updateComplete() {
  const paid = state.payments.reduce((s, p) => s + Number(p.amount || 0), 0);
  const ok = state.quote && state.quote.lines.length && state.quote.lines.every(l => l.enoughStock);
  $('completeBtn').disabled = !ok;
  const diff = Math.round((grand() - paid) * 100) / 100;
  const c = creditCheck();
  $('payMsg').innerHTML = (state.quote && diff !== 0
    ? `<div class="msg warn">${diff > 0 ? 'Still to collect: ' + rs(diff) : 'Payments are ' + rs(-diff) + ' more than the total'}</div>` : '')
    + (c.amount > 0 && !state.customer ? '<div class="msg warn" style="margin-top:.4rem"><b>Warning:</b> Choose the customer first – credit (udhaar) needs a customer.</div>' : '')
    + (c.amount > 0 && state.customer && !c.over ? `<div class="msg info" style="margin-top:.4rem">Credit available for ${esc(state.customer.name)}: <b>${rs0(c.available)}</b> (limit ${rs0(c.limit)}, already due ${rs0(c.due)}).</div>` : '')
    + (c.over ? `<div class="msg err" style="margin-top:.4rem"><b>Over the credit limit by ${rs0(c.amount - c.available)}.</b> ${esc(state.customer.name)} can take only ${rs0(c.available)} more on credit (limit ${rs0(c.limit)}, already due ${rs0(c.due)}). ${ME.role === 'OWNER' ? 'As the Owner you can still save it.' : 'Take the rest in cash/UPI, or the Owner approves with the PIN.'}</div>` : '');
  saveDraft();
}

/** Credit (udhaar) on this bill compared with the customer's credit limit. */
function creditCheck() {
  const amount = state.payments.filter(p => p.mode === 'CREDIT').reduce((s, p) => s + Number(p.amount || 0), 0);
  const c = state.customer;
  if (!c || amount <= 0) return { amount, over: false };
  const limit = Number(c.creditLimit || 0), due = Number(c.creditDue || 0), available = Math.max(0, limit - due);
  return { amount, limit, due, available, over: amount > available + 0.001 };
}

async function loadCreditNote(cnNo) {
  try {
    const cn = await api('/credit-notes/' + encodeURIComponent(cnNo));
    if (Number(cn.balance) <= 0) { toast('Credit note ' + cn.cnNo + ' has no balance left', 'warn'); return; }
    state.creditNote = { cnNo: cn.cnNo, balance: cn.balance };
    toast(`Exchange: credit note ${cn.cnNo} (${rs(cn.balance)}) will be used as payment`, 'ok');
    render();
  } catch (err) { toast(err.message, 'err'); }
}

// ---------- customer ----------

async function findCustomer() {
  const mobile = $('custMobile').value.trim();
  $('custMsg').innerHTML = '';
  if (!mobile) return;
  try {
    setCustomer(await api('/customers/lookup?mobile=' + encodeURIComponent(mobile)));
  } catch (err) {
    if (err.code === 'INFO-CUS-001') showRegister(mobile);
    else $('custMsg').innerHTML = errorHtml(err);
  }
}

function setCustomer(c) {
  state.customer = c;
  const box = $('custBox');
  $('custFind').classList.toggle('hidden', !!c);
  if (!c) { box.classList.add('hidden'); box.innerHTML = ''; requote(); return; }
  box.classList.remove('hidden');
  box.innerHTML = `<div class="row between"><div><b>${esc(c.name)}</b><div class="small muted">${esc(c.mobile)} ${c.area ? '· ' + esc(c.area) : ''}</div></div>
      <button class="small" id="custClear">Change</button></div>
    <div class="row small" style="margin-top:.4rem">
      ${c.waOptIn ? '<span class="badge ok">WhatsApp opted in</span>' : '<span class="badge grey">No WhatsApp consent</span>'}
      <span class="badge brand">${c.loyaltyPoints} points</span>
    </div>
    <div class="small" style="margin-top:.45rem">${Number(c.creditLimit) > 0
      ? `Credit (udhaar): due <b>${rs0(c.creditDue)}</b> of <b>${rs0(c.creditLimit)}</b> limit · <span style="color:${Number(c.creditLimit) - Number(c.creditDue) > 0 ? 'var(--ok)' : 'var(--err)'}">${rs0(Math.max(0, Number(c.creditLimit) - Number(c.creditDue)))} available</span>`
      : Number(c.creditDue) > 0 ? `<span style="color:var(--err)">Credit due ${rs0(c.creditDue)} · no credit limit set</span>`
      : '<span class="muted">No credit limit – credit (udhaar) needs Owner approval.</span>'}</div>`;
  $('custClear').onclick = () => { setCustomer(null); $('custMobile').focus(); };
  requote();
}

function showRegister(mobile) {
  $('custMsg').innerHTML = `<div class="msg info">New customer – register now (mobile ${esc(mobile)})</div>
    <div class="field" style="margin-top:.5rem"><label>Name</label><input type="text" id="rName"></div>
    <div class="field"><label>Area / village</label><input type="text" id="rArea"></div>
    <label class="checkbox"><input type="checkbox" id="rOpt"> Customer agrees to receive bills & offers on WhatsApp</label>
    <div class="field hidden" id="rMethodBox" style="margin-top:.4rem"><label>How was consent given?</label>
      <select id="rMethod"><option value="IN_STORE_VERBAL">Said yes at the counter</option><option value="SIGNED_FORM">Signed form</option><option value="QR_SCAN">Scanned shop QR</option></select></div>
    <div id="rErr"></div>
    <div class="row" style="margin-top:.5rem"><button class="primary" id="rSave">Register & attach</button><button id="rCancel">Cancel</button></div>`;
  $('rName').focus();
  $('rOpt').onchange = () => $('rMethodBox').classList.toggle('hidden', !$('rOpt').checked);
  $('rCancel').onclick = () => { $('custMsg').innerHTML = ''; };
  $('rSave').onclick = async () => {
    try {
      const c = await api('/customers', { method: 'POST', body: {
        name: $('rName').value, mobile, area: $('rArea').value,
        waOptIn: $('rOpt').checked, consentMethod: $('rOpt').checked ? $('rMethod').value : null } });
      $('custMsg').innerHTML = '';
      toast('Customer registered', 'ok');
      setCustomer(c);
    } catch (err) { showError($('rErr'), err); }
  };
}

// ---------- complete sale ----------

async function complete() {
  const q = state.quote;
  if (!q || !q.lines.length) return;
  if (q.needsOwnerPin && ME.role !== 'OWNER' && !state.ownerPin) {
    const pin = await askOwnerPin('A manual discount is above the staff limit of 10%.');
    if (!pin) return;
    state.ownerPin = pin;
  }
  const credit = creditCheck();
  if (credit.amount > 0 && !state.customer) {
    $('saleErr').innerHTML = errorHtml({ message: 'A credit (udhaar) sale needs a customer. Find or register the customer first.' });
    return;
  }
  if (credit.over) {
    const msg = `${state.customer.name} can take only ${rs0(credit.available)} more on credit (limit ${rs0(credit.limit)}, already due ${rs0(credit.due)}). This bill puts ${rs0(credit.amount)} on credit.`;
    if (ME.role === 'OWNER') {
      if (!(await confirmBox(msg + ' Save it over the limit?', 'Yes, save'))) return;
    } else if (!state.ownerPin) {
      const pin = await askOwnerPin(msg + ' The Owner can approve it with the PIN.');
      if (!pin) return;
      state.ownerPin = pin;
    }
  }
  $('saleErr').innerHTML = '';
  const btn = $('completeBtn');
  btn.disabled = true;
  btn.textContent = 'Saving…';
  try {
    const res = await api('/sales', { method: 'POST', body: {
      customerId: state.customer && state.customer.id,
      lines: state.cart,
      payments: state.payments.filter(p => Number(p.amount) > 0)
        .map(p => ({ mode: p.mode, amount: Number(p.amount), reference: p.reference || null })),
      ownerPin: state.ownerPin } });
    state.saved = true;
    store.del('posDraft');
    showDone(res);
  } catch (err) {
    if (err.code === 'ERR-BIL-005' || err.code === 'ERR-CUS-002') state.ownerPin = null;
    $('saleErr').innerHTML = errorHtml(err);
    if (err.code === 'ERR-INV-001') requote();
  } finally {
    btn.textContent = 'Complete Sale (F9)';
    updateComplete();
  }
}

function showDone(res) {
  const cash = state.payments.filter(p => p.mode === 'CASH').reduce((s, p) => s + Number(p.amount || 0), 0);
  const given = Number($('cashGiven').value || 0);
  const change = given > cash ? given - cash : 0;
  const m = openModal(`<h2>Sale complete ✓</h2>
    <div class="grid cols-2">
      <div class="kpi"><div class="label">Invoice</div><div class="value" style="font-size:1.2rem">${esc(res.invoiceNo)}</div></div>
      <div class="kpi"><div class="label">Total</div><div class="value">${rs0(res.grandTotal)}</div></div>
    </div>
    ${change > 0 ? `<div class="msg ok" style="margin-top:.8rem;font-size:1.1rem">Return change: <b>${rs0(change)}</b></div>` : ''}
    ${res.pointsEarned ? `<p class="small">Customer earned <b>${res.pointsEarned}</b> loyalty points.</p>` : ''}
    ${res.warnings.map(w => `<div style="margin-top:.5rem">${noticeHtml(w)}</div>`).join('')}
    ${res.stockAlerts.map(a => `<div class="msg ${a.startsWith('OUT') ? 'err' : 'warn'}" style="margin-top:.5rem">⚠ ${esc(a)}</div>`).join('')}
    <div class="actions">
      <button id="dPrint">Print receipt</button>
      ${res.customerOptedIn ? '<button class="whatsapp" id="dWa">Send e-bill on WhatsApp</button>' : ''}
      <button class="primary" id="dNew">New sale</button>
    </div>
    ${!res.customerOptedIn && state.customer ? '<p class="small muted">E-bill not available: this customer has not opted in to WhatsApp.</p>' : ''}`,
    { onClose: resetSale });
  m.el.querySelector('#dPrint').onclick = () => window.open('receipt.html?id=' + res.saleId + '&print=1', '_blank');
  m.el.querySelector('#dNew').onclick = m.close;
  m.el.querySelector('#dNew').focus();
  const wa = m.el.querySelector('#dWa');
  if (wa) wa.onclick = async () => {
    try {
      const link = await api('/whatsapp/ebill/' + res.saleId, { method: 'POST' });
      window.open(link.waUrl, '_blank');
      toast('WhatsApp opened for ' + link.recipient + ' – press Send there', 'ok');
    } catch (err) { toast(err.message, 'err'); }
  };
}

// ---------- hold bill (F8) ----------

async function holdBill() {
  if (!state.cart.length) { toast('The cart is empty – nothing to hold', 'warn'); return false; }
  try {
    const r = await api('/sales/hold', { method: 'POST', body: {
      customerId: state.customer && state.customer.id, lines: state.cart } });
    toast(`Bill held (${r.items} pcs, ${rs0(r.total)}). Resume it from "Held bills".`, 'ok');
    resetSale();
    refreshHeldCount();
    return true;
  } catch (err) { toast(err.message, 'err'); return false; }
}

async function refreshHeldCount() {
  try {
    const list = await api('/sales/held');
    $('heldCount').textContent = list.length;
    $('heldCount').className = 'badge ' + (list.length ? 'warn' : 'grey');
  } catch (e) { /* ignore */ }
}

async function showHeld() {
  const list = await api('/sales/held');
  const m = openModal(`<h2>Held bills</h2>
    ${list.length ? `<table><thead><tr><th>Held at</th><th>Customer</th><th>Items</th><th class="num">Total</th><th></th></tr></thead><tbody>
      ${list.map(h => `<tr><td>${fmtDateTime(h.created_at)}<div class="small muted">${esc(h.cashier)}</div></td>
        <td>${esc(h.customer_name || 'Walk-in')}</td>
        <td><div class="small">${esc(h.item_names)}</div><span class="muted small">${h.items} pcs</span></td>
        <td class="num">${rs0(h.grand_total)}</td>
        <td class="num"><button class="primary small" data-resume="${h.held_id}">Resume</button>
          <button class="small danger" data-discard="${h.held_id}">Discard</button></td></tr>`).join('')}
      </tbody></table>` : '<p class="muted">No held bills. Press F8 on the POS to park a bill while you serve the next customer.</p>'}
    <p class="small muted">Held bills do not reserve stock and are discarded after 24 hours.</p>
    <div class="actions"><button data-close>Close</button></div>`, { wide: true });
  m.el.querySelectorAll('[data-resume]').forEach(b => b.onclick = async () => {
    if (state.cart.length && !(await holdBill())) return;   // park the current bill first
    try {
      const cart = await api('/sales/held/' + b.dataset.resume + '/resume', { method: 'POST' });
      m.close();
      state.cart = cart.lines.map(l => ({ variantId: l.variantId, quantity: l.quantity, manualDiscount: Number(l.manualDiscount) || 0 }));
      if (cart.customerId) setCustomer(await api('/customers/' + cart.customerId));
      else requote();
      refreshHeldCount();
      toast('Held bill resumed', 'ok');
    } catch (err) { toast(err.message, 'err'); }
  });
  m.el.querySelectorAll('[data-discard]').forEach(b => b.onclick = async () => {
    if (!(await confirmBox('Discard this held bill?', 'Discard'))) return;
    await api('/sales/held/' + b.dataset.discard, { method: 'DELETE' });
    m.close();
    refreshHeldCount();
    showHeld();
  });
}

// ---------- tap-to-add photo grid (for items without a barcode tag, or on a phone/tablet) ----------

let browseCat = '';
async function initBrowse() {
  let hidden = false;
  try { hidden = localStorage.getItem('pos.hidePhotos') === '1'; } catch (e) { /* private mode */ }
  const apply = () => {
    $('browseBody').classList.toggle('hidden', hidden);
    $('browseToggle').textContent = hidden ? 'Show photos' : 'Hide photos';
  };
  $('browseToggle').onclick = () => {
    hidden = !hidden; apply();
    try { localStorage.setItem('pos.hidePhotos', hidden ? '1' : '0'); } catch (e) { /* ignore */ }
    if (!hidden && !$('tiles').innerHTML) loadTiles();
  };
  apply();
  const cats = await api('/categories').catch(() => []);
  const tops = cats.filter(c => !c.parent_id);
  $('cats').innerHTML = `<button class="on" data-c="">All</button>` + tops.map(c => `<button data-c="${c.category_id}">${esc(c.name)}</button>`).join('');
  $('cats').querySelectorAll('button').forEach(b => b.onclick = () => {
    $('cats').querySelectorAll('button').forEach(x => x.classList.toggle('on', x === b));
    browseCat = b.dataset.c; loadTiles();
  });
  if (!hidden) loadTiles();
}

async function loadTiles() {
  const list = await api('/products' + (browseCat ? '?categoryId=' + browseCat : '')).catch(() => []);
  $('tiles').innerHTML = list.map(p => `<button class="tile ${Number(p.total_stock) > 0 ? '' : 'out'}" data-id="${p.product_id}" title="${esc(p.name)}">
      <img loading="lazy" alt="" src="${API}/products/${p.product_id}/image?size=thumb${p.image_updated_at ? '&t=' + new Date(p.image_updated_at).getTime() : ''}">
      <div class="t"><b>${esc(p.name)}</b>${p.min_price == null ? '' : p.min_price === p.max_price ? rs0(p.min_price) : rs0(p.min_price) + '+'}
      <span class="muted"> · ${Number(p.total_stock) > 0 ? p.total_stock + ' pcs' : 'Out'}</span></div></button>`).join('')
    || `<div class="muted small" style="grid-column:1/-1">No products in this category.</div>`;
  $('tiles').querySelectorAll('.tile').forEach(t => t.onclick = () => pickVariant(Number(t.dataset.id)));
}

/** Size and colour picker for one product; adds the chosen variant to the cart. */
async function pickVariant(productId) {
  const p = await api('/products/' + productId);
  const vs = p.variants;
  const sizes = [...new Set(vs.map(v => v.size))], colours = [...new Set(vs.map(v => v.colour))];
  let size = null, colour = colours.length === 1 ? colours[0] : null;
  const m = openModal(`<div class="row" style="align-items:flex-start;gap:1rem">
      <img src="${API}/products/${productId}/image?size=thumb" alt="" style="width:110px;height:110px;object-fit:cover;border-radius:10px;background:#f1eeec">
      <div class="grow"><h2 style="margin:0">${esc(p.name)}</h2><div class="muted small">${esc(p.categoryName)}${p.brand ? ' · ' + esc(p.brand) : ''}</div>
        <div id="pv" style="font-size:1.4rem;font-weight:800;margin-top:.4rem"></div></div></div>
    <div ${colours.length === 1 ? 'class="hidden"' : ''}><h3 style="margin-top:1rem">Colour</h3><div class="picker" id="pc"></div></div>
    <h3 style="margin-top:1rem">Size</h3><div class="picker" id="ps"></div>
    <div class="actions"><button data-close>Cancel</button><button class="primary" id="padd" disabled>Add to bill</button></div>`);
  const find = () => vs.find(v => v.size === size && v.colour === colour);
  const draw = () => {
    m.el.querySelector('#pc').innerHTML = colours.map(c => {
      const left = vs.filter(v => v.colour === c).reduce((s, v) => s + v.stockQty, 0);
      return `<button data-c="${esc(c)}" class="${c === colour ? 'on' : ''}" ${left ? '' : 'disabled'}>${esc(c)}<small>${left} pcs</small></button>`;
    }).join('');
    m.el.querySelector('#ps').innerHTML = sizes.map(sz => {
      const v = vs.find(x => x.size === sz && x.colour === colour);
      const q = colour ? (v ? v.stockQty : 0) : vs.filter(x => x.size === sz).reduce((s, x) => s + x.stockQty, 0);
      return `<button data-s="${esc(sz)}" class="${sz === size ? 'on' : ''}" ${q ? '' : 'disabled'}>${esc(sz)}<small>${colour ? q + ' left' : ''}</small></button>`;
    }).join('');
    const v = colour && size ? find() : null;
    m.el.querySelector('#pv').innerHTML = v ? rs0(v.sellingPrice) + (Number(v.mrp) > Number(v.sellingPrice) ? ` <s class="muted small" style="font-weight:400">${rs0(v.mrp)}</s>` : '')
      : `<span class="muted small" style="font-weight:400">Choose ${colour ? 'a size' : 'a colour and size'}</span>`;
    m.el.querySelector('#padd').disabled = !(v && v.stockQty > 0);
    m.el.querySelectorAll('#pc button').forEach(b => b.onclick = () => { colour = b.dataset.c; if (size && !(find()?.stockQty)) size = null; draw(); });
    m.el.querySelectorAll('#ps button').forEach(b => b.onclick = () => {
      size = b.dataset.s;
      if (!colour) { const any = vs.find(x => x.size === size && x.stockQty > 0); if (any) colour = any.colour; }
      draw();
    });
  };
  draw();
  m.el.querySelector('#padd').onclick = () => {
    const v = find();
    if (!v) return;
    addVariant({ ...v, productName: p.name });
    m.close();
  };
}
