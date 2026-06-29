// ═══════════════════════════════════════════════════════════════
//  UNIFEST — Service Worker v4.0
//  Offline caching · Push notifications · Background sync
// ═══════════════════════════════════════════════════════════════
const SW_VERSION = 'unifest-sw-v4';
const CACHE_NAME = 'unifest-cache-v4';

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
    self.registration.getNotifications({ tag }).then((existing) => {
      existing.forEach((n) => n.close());
      return self.registration.showNotification(title, {
        body, icon, badge: icon, tag, renotify: true,
        requireInteraction, actions, vibrate, silent: false,
        data: { type, from, text, ts: Date.now() },
      });
    })
  );
});

self.addEventListener('notificationclick', (event) => {
  const { type, from } = event.notification.data || {};
  event.notification.close();
  let action = 'open';
  if (event.action === 'accept') action = 'accept';
  else if (event.action === 'decline') action = 'decline';

  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((clients) => {
      const target = clients.find((c) => c.visibilityState === 'visible') || clients[0];
      if (target) {
        target.focus();
        target.postMessage({ kind: 'notification-action', action, notifType: type, from });
        return;
      }
      const url = '/?notif=' + encodeURIComponent(type) + '&from=' + encodeURIComponent(from || '') + '&action=' + action;
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
