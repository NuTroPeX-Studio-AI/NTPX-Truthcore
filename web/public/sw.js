const CACHE = "ntpx-truthcore-web-v1.1.0";
const SHELL = ["/", "/app", "/styles.css", "/app.js", "/store.js", "/manifest.webmanifest", "/icon.svg"];

self.addEventListener("install", (event) => {
  event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(SHELL)));
  self.skipWaiting();
});

self.addEventListener("activate", (event) => {
  event.waitUntil(caches.keys().then((keys) => Promise.all(keys.filter((key) => key !== CACHE).map((key) => caches.delete(key)))));
  self.clients.claim();
});

// API traffic (which may carry BYOK credentials) is never intercepted or cached.
// The app shell is network-first so deployments roll out immediately, with the
// cache as an offline fallback. Only successful same-origin responses are stored.
self.addEventListener("fetch", (event) => {
  const request = event.request;
  const url = new URL(request.url);
  if (request.method !== "GET" || url.origin !== self.location.origin || url.pathname.startsWith("/api/")) return;

  event.respondWith((async () => {
    try {
      const response = await fetch(request);
      if (response.ok && response.type === "basic") {
        const copy = response.clone();
        caches.open(CACHE).then((cache) => cache.put(request, copy)).catch(() => {});
      }
      return response;
    } catch (error) {
      const cached = await caches.match(request);
      if (cached) return cached;
      if (request.mode === "navigate") {
        const shell = await caches.match(url.pathname.startsWith("/app") ? "/app" : "/");
        if (shell) return shell;
      }
      throw error;
    }
  })());
});
