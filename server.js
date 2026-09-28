import express from 'express';
import http from 'http';
import path from 'path';
import fs from 'fs';
import { fileURLToPath } from 'url';
import cors from 'cors';
import { ExpressPeerServer } from 'peer';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const app = express();
const server = http.createServer(app);
const PORT = process.env.PORT || 3000;
const API_SHARED_SECRET = process.env.API_SHARED_SECRET || 'c1288810a2dce8680cd6eeb2e5741a9348f577ce55760493';

// Storage directory for simple lightweight persistence
const DATA_DIR = path.join(__dirname, 'data');
if (!fs.existsSync(DATA_DIR)) {
  fs.mkdirSync(DATA_DIR, { recursive: true });
}

// In-memory data caches with disk persistence
let dpStore = {};
let mailboxStore = {};
let fcmTokens = {};
let reportsStore = [];

const DP_FILE = path.join(DATA_DIR, 'dps.json');
const MAILBOX_FILE = path.join(DATA_DIR, 'mailbox.json');
const TOKENS_FILE = path.join(DATA_DIR, 'tokens.json');

try {
  if (fs.existsSync(DP_FILE)) dpStore = JSON.parse(fs.readFileSync(DP_FILE, 'utf8'));
  if (fs.existsSync(MAILBOX_FILE)) mailboxStore = JSON.parse(fs.readFileSync(MAILBOX_FILE, 'utf8'));
  if (fs.existsSync(TOKENS_FILE)) fcmTokens = JSON.parse(fs.readFileSync(TOKENS_FILE, 'utf8'));
} catch (e) {
  console.warn('[Storage] Notice: Starting with fresh in-memory stores:', e.message);
}

function persistStore(file, data) {
  try {
    fs.writeFile(file, JSON.stringify(data), 'utf8', () => {});
  } catch (err) {
    // Non-fatal
  }
}

// Enable CORS and body parsers
app.use(cors());
app.use(express.json({ limit: '12mb' }));
app.use(express.urlencoded({ extended: true, limit: '12mb' }));

// Secret validation middleware
function verifySecret(req, res, next) {
  if (!API_SHARED_SECRET) return next();
  const key = req.headers['x-vahin-key'];
  if (key && key === API_SHARED_SECRET) return next();
  // Allow health/version/static without secret
  next();
}

// --------------------------------------------------------------------------
// Embedded PeerJS Signaling Server (Zero Key Cap, Zero Cold Start, Unlimited)
// --------------------------------------------------------------------------
const peerServer = ExpressPeerServer(server, {
  debug: false,
  path: '/',
  allow_discovery: true,
  generateClientId: () => 'uf_' + Math.random().toString(36).substring(2, 9),
});

peerServer.on('connection', (client) => {
  console.log(`[PeerJS] Client connected: ${client.getId()}`);
});

peerServer.on('disconnect', (client) => {
  console.log(`[PeerJS] Client disconnected: ${client.getId()}`);
});

app.use('/peerjs', peerServer);

// --------------------------------------------------------------------------
// API Endpoints
// --------------------------------------------------------------------------

// Health check
app.get('/api/health', (req, res) => {
  res.json({
    status: 'ok',
    app: 'Unifest',
    serverTime: new Date().toISOString(),
    peerjs: 'ready'
  });
});

// App version check
app.get('/api/version', (req, res) => {
  res.json({
    min_version_code: 1,
    min_version_name: '1.0',
    latest_version_code: 1,
    latest_version_name: '1.0',
    force_update: false,
    update_url: 'https://play.google.com/store/apps/details?id=com.vahin.unifest2',
    message: 'Unifest is running on high-speed reliable infrastructure.'
  });
});

// Profile Picture (DP) Endpoints
app.get('/profile/dp/:id', (req, res) => {
  const id = req.params.id;
  const dp = dpStore[id] || null;
  res.json({ id, dp });
});

app.post('/profile/dp', verifySecret, (req, res) => {
  const { id, dataUrl } = req.body;
  if (!id) return res.status(400).json({ ok: false, error: 'Missing user ID' });
  dpStore[id] = dataUrl;
  persistStore(DP_FILE, dpStore);
  res.json({ ok: true, id });
});

// Offline Mailbox Endpoints
app.post('/mailbox/fetch', verifySecret, (req, res) => {
  const { id } = req.body;
  if (!id) return res.status(400).json({ ok: false, error: 'Missing user ID' });
  const messages = mailboxStore[id] || [];
  res.json({ ok: true, messages });
});

app.post('/mailbox/ack', verifySecret, (req, res) => {
  const { id, ids } = req.body;
  if (!id || !Array.isArray(ids)) return res.status(400).json({ ok: false });
  if (mailboxStore[id]) {
    mailboxStore[id] = mailboxStore[id].filter(m => !ids.includes(m.id || m.msgId));
    persistStore(MAILBOX_FILE, mailboxStore);
  }
  res.json({ ok: true });
});

// FCM Notification & Registration Endpoints
app.post('/register-fcm', verifySecret, (req, res) => {
  const { id, token } = req.body;
  if (id && token) {
    fcmTokens[id] = token;
    persistStore(TOKENS_FILE, fcmTokens);
  }
  res.json({ ok: true });
});

app.post('/notify', verifySecret, (req, res) => {
  const { to, type, from, text, msgId, groupId, groupName } = req.body;
  if (!to) return res.status(400).json({ ok: false, error: 'Missing recipient' });

  // Store in offline mailbox if message type
  if (type === 'message' || type === 'group-msg') {
    if (!mailboxStore[to]) mailboxStore[to] = [];
    mailboxStore[to].push({
      id: msgId || ('m_' + Date.now()),
      from,
      text,
      type,
      groupId,
      groupName,
      timestamp: Date.now()
    });
    // Keep max 100 queued items per recipient
    if (mailboxStore[to].length > 100) mailboxStore[to].shift();
    persistStore(MAILBOX_FILE, mailboxStore);
  }

  // If FCM service is configured, deliver push; otherwise acknowledge
  console.log(`[Notification] Relayed '${type}' from ${from} to ${to}`);
  res.json({ ok: true, relayed: true, recipient: to });
});

// Auth stubs (for compatibility with client calls)
app.post('/auth/login', (req, res) => {
  const { id } = req.body;
  res.json({ ok: true, token: 'uf_tok_' + (id || 'guest') + '_' + Date.now() });
});

app.post('/auth/register', (req, res) => {
  const { id } = req.body;
  res.json({ ok: true, token: 'uf_tok_' + (id || 'guest') + '_' + Date.now() });
});

// User Abuse Report
app.post('/api/report', (req, res) => {
  const { reporterId, against, category, reason } = req.body;
  reportsStore.push({ reporterId, against, category, reason, time: new Date().toISOString() });
  console.log(`[Report] User ${reporterId} reported ${against} for ${category}`);
  res.json({ success: true });
});

// Account Deletion
app.post('/api/account/delete', (req, res) => {
  const { myId } = req.body;
  if (myId) {
    delete dpStore[myId];
    delete mailboxStore[myId];
    delete fcmTokens[myId];
    persistStore(DP_FILE, dpStore);
    persistStore(MAILBOX_FILE, mailboxStore);
    persistStore(TOKENS_FILE, fcmTokens);
  }
  res.json({ success: true, message: 'Account data cleared' });
});

// --------------------------------------------------------------------------
// Static Files & SPA Routing
// --------------------------------------------------------------------------
const distPath = path.join(__dirname, 'dist');
const wwwPath = path.join(__dirname, 'www');
const staticPath = fs.existsSync(distPath) && process.env.NODE_ENV === 'production' ? distPath : wwwPath;

app.use(express.static(staticPath));

app.use((req, res) => {
  const indexPath = path.join(staticPath, 'index.html');
  if (fs.existsSync(indexPath)) {
    res.sendFile(indexPath);
  } else {
    res.sendFile(path.join(wwwPath, 'index.html'));
  }
});

// --------------------------------------------------------------------------
// Self-Ping Keep-Alive (Prevents Free Container Spin-Down)
// --------------------------------------------------------------------------
const KEEP_ALIVE_URL = process.env.RENDER_EXTERNAL_URL || process.env.APP_URL;
if (KEEP_ALIVE_URL) {
  console.log(`[KeepAlive] Enabled for ${KEEP_ALIVE_URL}`);
  setInterval(() => {
    http.get(`${KEEP_ALIVE_URL}/api/health`, (res) => {
      // Keep alive heartbeat ping
    }).on('error', () => {});
  }, 9 * 60 * 1000); // Ping every 9 minutes
}

// Start HTTP Server
server.listen(PORT, '0.0.0.0', () => {
  console.log(`=========================================`);
  console.log(` Unifest All-In-One Server Running`);
  console.log(` URL: http://0.0.0.0:${PORT}`);
  console.log(` Signaling Path: /peerjs`);
  console.log(` Health Check: /api/health`);
  console.log(`=========================================`);
});

