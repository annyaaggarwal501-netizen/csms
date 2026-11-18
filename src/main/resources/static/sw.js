// Service worker: lets the app install on a phone/PC like an app and opens screens fast.
// Pages, CSS and JS: network first (always the newest version), cached copy only when offline.
// The API (/api/...) is never cached: bills, stock and prices must always be live.
const CACHE = 'shop-v1';
self.addEventListener('install', e => self.skipWaiting());
self.addEventListener('activate', e => e.waitUntil(
  caches.keys().then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k)))).then(() => self.clients.claim())));
self.addEventListener('fetch', e => {
  const url = new URL(e.request.url);
  if (e.request.method !== 'GET' || url.origin !== location.origin || url.pathname.startsWith('/api/')) return;
  e.respondWith(fetch(e.request).then(res => {
    if (res.ok) { const copy = res.clone(); caches.open(CACHE).then(c => c.put(e.request, copy)); }
    return res;
  }).catch(() => caches.match(e.request).then(r => r || new Response(
    '<h2 style="font-family:sans-serif;padding:2rem">No internet. The shop app needs a connection to bill and save stock.</h2>',
    { headers: { 'Content-Type': 'text/html' } }))));
});
