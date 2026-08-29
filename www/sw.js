// ═══════════════════════════════════════════════════════════════
//  UNIFEST — Service Worker v4.1 (cold-start PeerJS fix)
//  Offline caching · Push notifications · Background sync
// ═══════════════════════════════════════════════════════════════
const SW_VERSION = 'unifest-sw-v4.2';
const CACHE_NAME = 'unifest-cache-v4.2';

const PRECACHE = [
  '/',
  '/index.html',
  '/manifest.json',
  '/vendor/peerjs.min.js',
];

self.addEventListener('install', (event) => {
  self.skipWaiting();
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) =>
      cache.addAll(PRECACHE.map(u => new Request(u, { cache: 'no-cache' }))).catch(() => {})
    )
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE_NAME).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);
  if (url.hostname !== self.location.hostname) return;
  if (event.request.method !== 'GET') return;

  /* ── /vendor/peerjs.min.js — NETWORK-FIRST (with cache fallback) ───────────
     Cold-start root cause: a cache-first strategy here means a stale or
     truncated SW-cache entry (e.g. from an interrupted prior install) is
     served silently — the <script> onload fires but window.Peer is undefined,
     PeerJS never initialises, and the "Connecting…" banner never appears.
     Network-first ensures the APK-bundled asset is always used when reachable
     (which it always is in Capacitor — requests to https://localhost/* are
     served from the app's own asset bundle, not the real internet), falling
     back to cache only when genuinely offline. The ?v= cache-buster added by
     loadPeerJS() is intentionally preserved here: it causes a cache miss on
     the keyed URL, so the fresh network fetch is always preferred over any
     old cached entry regardless of the matching logic below. */
  if (url.pathname === '/vendor/peerjs.min.js') {
    event.respondWith(
      fetch(event.request).then((res) => {
        if (res && res.status === 200)
          caches.open(CACHE_NAME).then((c) => c.put(event.request, res.clone()));
        return res;
      }).catch(() => caches.match('/vendor/peerjs.min.js'))
    );
    return;
  }

  if (url.pathname.match(/\.(js|css|png|webp|woff2?|svg|ico)$/)) {
    event.respondWith(
      caches.match(event.request).then((cached) =>
        cached || fetch(event.request).then((res) => {
          if (res && res.status === 200) {
            caches.open(CACHE_NAME).then((c) => c.put(event.request, res.clone()));
          }
          return res;
        })
      )
    );
    return;
  }

  if (url.pathname === '/' || url.pathname.endsWith('.html')) {
    event.respondWith(
      fetch(event.request)
        .then((res) => {
          if (res && res.status === 200)
            caches.open(CACHE_NAME).then((c) => c.put(event.request, res.clone()));
          return res;
        })
        .catch(() => caches.match('/index.html'))
    );
  }
});

self.addEventListener('push', (event) => {
  let data = {};
  try { data = event.data ? event.data.json() : {}; } catch (e) {
    data = { type: 'message', from: 'Unifest', text: event.data ? event.data.text() : '' };
  }
  const { type, from, text } = data;
  const icon = '/icons/icon-192.png';
  let title, body, tag, requireInteraction, actions, vibrate;

  if (type === 'call') {
    title = `📞 Incoming call`;
    body = `${from} is calling you`;
    tag = `unifest-call-${from}`;
    requireInteraction = true;
    vibrate = [500, 200, 500, 200, 500, 200, 500];
    actions = [{ action: 'accept', title: '✅ Accept' }, { action: 'decline', title: '❌ Decline' }];
  } else if (type === 'conf') {
    title = `🎥 Conference invite`;
    body = `${from} invites you to a group call`;
    tag = `unifest-conf-${from}`;
    requireInteraction = true;
    vibrate = [500, 200, 500, 200, 500];
    actions = [{ action: 'accept', title: '✅ Join' }, { action: 'decline', title: '❌ Dismiss' }];
  } else {
    title = `💬 ${from}`;
    body = (text || 'New message').substring(0, 80);
    tag = `unifest-dm-${from}`;
    requireInteraction = false;
    vibrate = [200, 100, 200];
    actions = [{ action: 'open', title: '💬 Reply' }];
  }

  event.waitUntil(
    Promise.all([
      self.registration.getNotifications({ tag }).then((existing) => {
        existing.forEach((n) => n.close());
        return self.registration.showNotification(title, {
          body, icon, badge: icon, tag, renotify: true,
          requireInteraction, actions, vibrate, silent: false,
          data: { type, from, text, ts: Date.now() },
        });
      }),
      (type === 'call' || type === 'conf')
        ? self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((clients) =>
            clients.forEach((c) => c.postMessage({ kind: 'reconnect-now', notifType: type, from }))
          )
        : Promise.resolve(),
    ])
  );
});

self.addEventListener('notificationclick', (event) => {
  const { type, from, text, ts } = event.notification.data || {};
  event.notification.close();
  let action = 'open';
  if (event.action === 'accept') action = 'accept';
  else if (event.action === 'decline') action = 'decline';

  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((clients) => {
      const target = clients.find((c) => c.visibilityState === 'visible') || clients[0];
      if (target) {
        target.focus();
        target.postMessage({ kind: 'notification-action', action, notifType: type, from, text, ts });
        return;
      }
      const cleanType = type || 'message';
      const cleanFrom = from || '';
      const cleanText = text || '';
      const url = '/?notif=' + encodeURIComponent(cleanType) + '&from=' + encodeURIComponent(cleanFrom) + '&msg=' + encodeURIComponent(cleanText) + '&action=' + encodeURIComponent(action);
      return self.clients.openWindow(url);
    })
  );
});

self.addEventListener('notificationclose', (event) => {
  const { type, from } = event.notification.data || {};
  if (type === 'call') {
    self.clients.matchAll({ type: 'window' }).then((clients) =>
      clients.forEach((c) => c.postMessage({ kind: 'call-dismissed', from }))
    );
  }
});

self.addEventListener('pushsubscriptionchange', (event) => {
  event.waitUntil(
    self.registration.pushManager
      .subscribe(event.oldSubscription ? event.oldSubscription.options : { userVisibleOnly: true })
      .then((sub) => self.clients.matchAll().then((clients) =>
        clients.forEach((c) => c.postMessage({ kind: 'resubscribe', subscription: sub }))
      ))
      .catch(() => {})
  );
});

self.addEventListener('message', (event) => {
  if (event.data && event.data.type === 'SKIP_WAITING') self.skipWaiting();
  if (event.data && event.data.type === 'CLEAR_NOTIFICATIONS')
    self.registration.getNotifications().then((n) => n.forEach((x) => x.close()));
});