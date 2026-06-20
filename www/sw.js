// ═══════════════════════════════════════════════════════════════
//  VAHIN CONNECT — Service Worker
//  Handles: push notifications, notification clicks/actions,
//  and (where supported) background sync for presence.
// ═══════════════════════════════════════════════════════════════
const SW_VERSION = 'vahin-sw-v1';

self.addEventListener('install', (event) => {
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(self.clients.claim());
});

// ── PUSH EVENT ──
// Fired by the browser/OS push service even if no tab is open
// (as long as the PWA/site is installed or was visited and granted
// notification permission — exact rules vary slightly by browser).
self.addEventListener('push', (event) => {
  let data = {};
  try { data = event.data ? event.data.json() : {}; } catch (e) {
    data = { type: 'message', from: 'Vahin Connect', text: event.data ? event.data.text() : '' };
  }

  const { type, from, text } = data;

  let title, body, icon, tag, requireInteraction, actions;
  icon = '/icons/icon-192.png';

  if (type === 'call') {
    title = `📞 Incoming call — ${from}`;
    body = 'Tap to answer in Vahin Connect';
    tag = 'vahin-call-' + from;
    requireInteraction = true; // keep it on screen like a real ringing call
    actions = [
      { action: 'accept', title: '✅ Accept' },
      { action: 'decline', title: '❌ Decline' },
    ];
  } else if (type === 'conf') {
    title = `🎥 Conference invite — ${from}`;
    body = 'Tap to join the group call';
    tag = 'vahin-conf-' + from;
    requireInteraction = true;
    actions = [
      { action: 'accept', title: '✅ Join' },
      { action: 'decline', title: '❌ Dismiss' },
    ];
  } else if (type === 'group') {
    title = `💬 ${from} (Group)`;
    body = text || 'New group message';
    tag = 'vahin-group';
    requireInteraction = false;
    actions = [{ action: 'open', title: 'Open' }];
  } else {
    title = `💬 ${from}`;
    body = text || 'New message';
    tag = 'vahin-dm-' + from;
    requireInteraction = false;
    actions = [{ action: 'open', title: 'Reply' }];
  }

  const options = {
    body,
    icon,
    badge: '/icons/icon-192.png',
    tag,
    renotify: true,
    requireInteraction,
    actions,
    vibrate: type === 'call' || type === 'conf' ? [500, 300, 500, 300, 500, 300, 500] : [200, 100, 200],
    data: { type, from, text, ts: Date.now() },
  };

  event.waitUntil(self.registration.showNotification(title, options));
});

// ── NOTIFICATION CLICK ──
self.addEventListener('notificationclick', (event) => {
  const { type, from } = event.notification.data || {};
  event.notification.close();

  let urlAction = 'open';
  if (event.action === 'decline') urlAction = 'decline';
  else if (event.action === 'accept') urlAction = 'accept';
  else if (event.action === 'open') urlAction = 'open';

  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((clientList) => {
      // If a window is already open, focus it and tell it what happened.
      for (const client of clientList) {
        if ('focus' in client) {
          client.focus();
          client.postMessage({
            kind: 'notification-action',
            action: urlAction,
            notifType: type,
            from,
          });
          return;
        }
      }
      // Otherwise open a new window (deep link could be added via query string).
      if (self.clients.openWindow) {
        return self.clients.openWindow('/?from=' + encodeURIComponent(from || '') + '&action=' + urlAction);
      }
    })
  );
});

// Optional: react to a subscription being silently rotated by the browser.
self.addEventListener('pushsubscriptionchange', (event) => {
  event.waitUntil(
    self.registration.pushManager.subscribe(event.oldSubscription ? event.oldSubscription.options : undefined)
      .then((sub) => {
        return self.clients.matchAll().then((clientList) => {
          clientList.forEach((c) => c.postMessage({ kind: 'resubscribe', subscription: sub }));
        });
      })
      .catch(() => {})
  );
});
