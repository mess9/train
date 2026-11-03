// Оболочка приложения из кэша: офлайн-запуск. Медиа и тестовые эндпоинты не кэшируем.
const CACHE = 'spike-v3';

self.addEventListener('install', e => {
  self.skipWaiting();
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(['./', 'index.html', 'manifest.webmanifest', 'icon-192.png', 'icon-512.png'])));
});

self.addEventListener('activate', e => e.waitUntil(
  caches.keys()
    .then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k))))
    .then(() => self.clients.claim())
));

// Страница присылает список уже загруженных файлов (wasm, js, шрифты) — первый запуск шёл без SW.
self.addEventListener('message', e => {
  if (e.data && e.data.cache) {
    e.waitUntil(caches.open(CACHE).then(c => Promise.all(e.data.cache.map(u => c.add(u).catch(() => {})))));
  }
});

self.addEventListener('fetch', e => {
  const url = new URL(e.request.url);
  if (e.request.method !== 'GET' || url.origin !== location.origin) return;
  if (url.pathname.startsWith('/media/') || url.pathname === '/redirect') return;
  // Сначала сеть, кэш — когда сети нет: иначе после пересборки виден старый билд.
  // cache: 'no-cache' — Cloudflare переписывает no-cache сервера в max-age=14400,
  // и без этого браузер 4 часа отдаёт старый spike.js из своего HTTP-кэша.
  e.respondWith(caches.open(CACHE).then(async c => {
    try {
      const r = await fetch(e.request, { cache: 'no-cache' });
      if (r.ok) c.put(e.request, r.clone());
      return r;
    } catch (err) {
      const hit = await c.match(e.request, { ignoreSearch: true });
      if (hit) return hit;
      throw err;
    }
  }));
});
