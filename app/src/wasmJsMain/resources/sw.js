// Оболочка приложения из кэша: запуск без сети. Сборка подставляет BUILD и FILES (server: appDist).
// Cloudflare переписывает Cache-Control в max-age=14400, поэтому у всех файлов, кроме index.html
// и sw.js, в имени хеш: их можно брать из кэша сразу, а index.html — сначала из сети.
const BUILD = '__BUILD__';
const FILES = __FILES__;
const SHELL = 'shell-' + BUILD;
const MEDIA = 'media';

const fresh = u => new Request(u, { cache: 'no-cache' });

self.addEventListener('install', e => {
  self.skipWaiting();
  e.waitUntil(caches.open(SHELL).then(c => c.addAll(['./', ...FILES].map(fresh))));
});

self.addEventListener('activate', e => e.waitUntil(
  caches.keys()
    .then(keys => Promise.all(keys.filter(k => k !== SHELL && k !== MEDIA).map(k => caches.delete(k))))
    .then(() => self.clients.claim())
));

self.addEventListener('fetch', e => {
  const url = new URL(e.request.url);
  if (e.request.method !== 'GET' || url.origin !== location.origin) return;
  if (url.pathname.startsWith('/api/') || url.pathname.startsWith('/q/')) return;
  // «Войти в Cloudflare» из приложения: страница из сети, редирект на вход — браузеру
  if (url.searchParams.has('login')) return;

  if (url.pathname.startsWith('/media/')) {
    e.respondWith(caches.open(MEDIA).then(async c => {
      const hit = await c.match(e.request);
      if (hit) return hit;
      const r = await fetch(e.request);
      if (r.ok) c.put(e.request, r.clone());
      return r;
    }));
    return;
  }

  if (e.request.mode === 'navigate' || url.pathname === '/' || url.pathname === '/index.html') {
    e.respondWith(page(e.request));
    return;
  }

  e.respondWith(caches.open(SHELL).then(async c => (await c.match(e.request, { ignoreSearch: true })) || fetch(e.request)));
});

// index.html: из сети, если она отвечает быстро; иначе, при редиректе на вход или ошибке — из кэша.
// Приложение само покажет, что нужен вход в Cloudflare
async function page(req) {
  const c = await caches.open(SHELL);
  const cached = await c.match('./');
  const net = fetch(req.url, { cache: 'no-cache', redirect: 'manual' }).then(r => {
    if (r.ok && r.type === 'basic') { c.put('./', r.clone()); return r; }
    return cached || r;
  });
  if (!cached) return net.catch(() => fetch(req));
  const timeout = new Promise(resolve => setTimeout(() => resolve(cached), 3000));
  return Promise.race([net.catch(() => cached), timeout]);
}
