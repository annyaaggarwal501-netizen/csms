// Loaded first on every page (in <head>): a page fade that is cut short (clicking on quickly, or the page moving on
// by itself) is simply skipped. Chrome reports that as an error; there is nothing wrong, so it is ignored here.
['pageswap', 'pagereveal'].forEach(type => window.addEventListener(type, e => {
  const vt = e.viewTransition;
  if (vt) { vt.ready.catch(() => {}); vt.finished.catch(() => {}); if (vt.updateCallbackDone) vt.updateCallbackDone.catch(() => {}); }
}));
window.addEventListener('unhandledrejection', e => {
  if (e.reason && /Transition was (aborted|skipped)/.test(String(e.reason.message || e.reason))) e.preventDefault();
});
