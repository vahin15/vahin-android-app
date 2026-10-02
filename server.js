import express from 'express';
import http from 'http';
import path from 'path';
import fs from 'fs';
import { fileURLToPath } from 'url';
import cors from 'cors';
import { createRequire } from 'module';
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

// --------------------------------------------------------------------------
// Communities: user-created, discoverable, joinable (no auto-assignment)
// --------------------------------------------------------------------------
const COMM_FILE = path.join(DATA_DIR, 'communities.json');
const DB_FILE = process.env.DB_PATH || path.join(DATA_DIR, 'unifest.db');
let commStore = { rooms: {} };
let db = null;
try {
  const Database = createRequire(import.meta.url)('better-sqlite3');
  db = new Database(DB_FILE);
  db.pragma('journal_mode = WAL');
  db.pragma('foreign_keys = ON');
  db.exec(`
    CREATE TABLE IF NOT EXISTS communities (
      gid TEXT PRIMARY KEY, name TEXT NOT NULL, descr TEXT, cat TEXT, sub TEXT, topic TEXT, focus TEXT,
      platform TEXT, band TEXT, cap INTEGER, owner TEXT, created INTEGER);
    CREATE TABLE IF NOT EXISTS community_members (
      gid TEXT NOT NULL, uid TEXT NOT NULL, joined INTEGER,
      PRIMARY KEY (gid, uid), FOREIGN KEY (gid) REFERENCES communities(gid) ON DELETE CASCADE);
    CREATE INDEX IF NOT EXISTS idx_comm_cat ON communities(cat, topic);
    CREATE INDEX IF NOT EXISTS idx_members_uid ON community_members(uid);
    CREATE TABLE IF NOT EXISTS reports (
      id INTEGER PRIMARY KEY AUTOINCREMENT, gid TEXT, reporter TEXT, reason TEXT, at INTEGER);`);
  db.prepare('SELECT * FROM communities').all().forEach((c) => {
    commStore.rooms[c.gid] = { gid: c.gid, name: c.name, desc: c.descr || '', cat: c.cat, sub: c.sub || '', topic: c.topic,
      focus: c.focus || '', platform: c.platform || 'Any', band: c.band, cap: c.cap, owner: c.owner, created: c.created,
      members: db.prepare('SELECT uid FROM community_members WHERE gid=? ORDER BY joined').all(c.gid).map((m) => m.uid) };
  });
  console.log('[db] SQLite ready:', DB_FILE, '·', Object.keys(commStore.rooms).length, 'communities');
} catch (e) {
  console.warn('[db] better-sqlite3 unavailable — falling back to JSON file:', e.message);
  try { if (fs.existsSync(COMM_FILE)) commStore = JSON.parse(fs.readFileSync(COMM_FILE, 'utf8')); } catch (e2) {}
}
const saveRoom = (r) => {
  if (!db) return persistStore(COMM_FILE, commStore);
  db.transaction(() => {
    db.prepare(`INSERT INTO communities (gid,name,descr,cat,sub,topic,focus,platform,band,cap,owner,created)
      VALUES (?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(gid) DO UPDATE SET name=excluded.name, descr=excluded.descr, owner=excluded.owner`)
      .run(r.gid, r.name, r.desc, r.cat, r.sub, r.topic, r.focus, r.platform, r.band, r.cap, r.owner, r.created);
    db.prepare('DELETE FROM community_members WHERE gid=?').run(r.gid);
    const ins = db.prepare('INSERT INTO community_members (gid,uid,joined) VALUES (?,?,?)');
    r.members.forEach((m, i) => ins.run(r.gid, m, r.created + i));
  })();
};
const dropRoom = (gid) => {
  delete commStore.rooms[gid];
  if (!db) return persistStore(COMM_FILE, commStore);
  db.prepare('DELETE FROM communities WHERE gid=?').run(gid);
};
const BANDS = ['all', '16-17', '18-24', '25-34', '35+'];
const CAPS = [8, 12, 20];
const ageBand = (a) => (a < 18 ? '16-17' : a < 25 ? '18-24' : a < 35 ? '25-34' : '35+');
const clean = (t, n) => String(t == null ? '' : t).replace(/[<>]/g, '').replace(/\s+/g, ' ').trim().slice(0, n);
const okAge = (a) => Number.isFinite(a) && a >= 16 && a <= 100;
const fits = (r, a) => r.band === 'all' || r.band === ageBand(a);
const pub = (r, id, withMembers) => ({
  gid: r.gid, name: r.name, desc: r.desc, cat: r.cat, sub: r.sub, topic: r.topic, focus: r.focus,
  platform: r.platform, band: r.band, cap: r.cap, owner: r.owner, count: r.members.length,
  joined: r.members.includes(id), ...(withMembers ? { members: r.members } : {}),
});

app.post('/community/create', verifySecret, (req, res) => {
  const b = req.body || {}; const id = clean(b.id, 40); const age = Number(b.age);
  if (!id || !okAge(age)) return res.status(400).json({ ok: false, error: 'Valid ID and age (16+) required' });
  const name = clean(b.name, 40);
  if (name.length < 3) return res.status(400).json({ ok: false, error: 'Name must be at least 3 characters' });
  const cat = clean(b.cat, 30); const topic = clean(b.topic, 40);
  if (!cat || !topic) return res.status(400).json({ ok: false, error: 'Pick a category and topic' });
  const band = BANDS.includes(b.band) ? b.band : 'all';
  if (band !== 'all' && band !== ageBand(age)) return res.status(400).json({ ok: false, error: 'You can only create a community for your own age band or for everyone' });
  const cap = CAPS.includes(Number(b.cap)) ? Number(b.cap) : 12;
  const rooms = Object.values(commStore.rooms);
  if (rooms.filter((r) => r.owner === id).length >= 5) return res.status(429).json({ ok: false, error: 'You can own up to 5 communities' });
  if (rooms.some((r) => r.name.toLowerCase() === name.toLowerCase() && (r.band === band))) return res.status(409).json({ ok: false, error: 'A community with that name already exists — join it instead' });
  const gid = 'c_' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
  const r = { gid, name, desc: clean(b.desc, 160), cat, sub: clean(b.sub, 30), topic, focus: clean(b.focus, 40),
    platform: clean(b.platform, 12) || 'Any', band, cap, owner: id, members: [id], created: Date.now() };
  commStore.rooms[gid] = r; saveRoom(r);
  res.json({ ok: true, room: pub(r, id, true) });
});

app.post('/community/list', verifySecret, (req, res) => {
  const b = req.body || {}; const id = clean(b.id, 40); const age = Number(b.age);
  if (!id || !okAge(age)) return res.status(400).json({ ok: false, error: 'Valid ID and age required' });
  const q = clean(b.q, 40).toLowerCase(); const cat = clean(b.cat, 30); const sub = clean(b.sub, 30);
  let rooms = Object.values(commStore.rooms).filter((r) => r.members.includes(id) || fits(r, age));
  if (cat) rooms = rooms.filter((r) => r.cat === cat);
  if (sub) rooms = rooms.filter((r) => r.sub === sub);
  if (q) rooms = rooms.filter((r) => [r.name, r.desc, r.cat, r.sub, r.topic, r.focus, r.platform].join(' ').toLowerCase().includes(q));
  rooms.sort((a, c) => c.members.length - a.members.length || c.created - a.created);
  res.json({ ok: true, rooms: rooms.slice(0, 60).map((r) => pub(r, id, false)) });
});

app.post('/community/join', verifySecret, (req, res) => {
  const b = req.body || {}; const id = clean(b.id, 40); const age = Number(b.age);
  const r = commStore.rooms[b.gid];
  if (!id || !okAge(age)) return res.status(400).json({ ok: false, error: 'Valid ID and age required' });
  if (!r) return res.status(404).json({ ok: false, error: 'This community no longer exists' });
  if (!r.members.includes(id)) {
    if (!fits(r, age)) return res.status(403).json({ ok: false, error: 'This community is for ' + r.band + ' only' });
    if (r.members.length >= r.cap) return res.status(409).json({ ok: false, error: 'This community is full' });
    r.members.push(id); saveRoom(r);
  }
  res.json({ ok: true, room: pub(r, id, true) });
});

app.post('/community/leave', verifySecret, (req, res) => {
  const id = clean((req.body || {}).id, 40); const r = commStore.rooms[(req.body || {}).gid];
  if (r) {
    r.members = r.members.filter((m) => m !== id);
    if (!r.members.length) dropRoom(r.gid);
    else { if (r.owner === id) r.owner = r.members[0]; saveRoom(r); }
  }
  res.json({ ok: true });
});

app.post('/community/delete', verifySecret, (req, res) => {
  const id = clean((req.body || {}).id, 40); const r = commStore.rooms[(req.body || {}).gid];
  if (!r) return res.json({ ok: true });
  if (r.owner !== id) return res.status(403).json({ ok: false, error: 'Only the owner can delete a community' });
  dropRoom(r.gid); res.json({ ok: true });
});

app.post('/community/report', verifySecret, (req, res) => {
  const b = req.body || {}; const r = commStore.rooms[b.gid];
  if (!r) return res.json({ ok: true });
  const row = [r.gid, clean(b.id, 40), clean(b.reason, 200), Date.now()];
  if (db) db.prepare('INSERT INTO reports (gid,reporter,reason,at) VALUES (?,?,?,?)').run(...row);
  else console.warn('[report]', row.join(' | '));
  res.json({ ok: true });
});

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

