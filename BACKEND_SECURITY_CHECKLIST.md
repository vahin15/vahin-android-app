# Backend Security & API Hardening Checklist (Render Backend)

This checklist applies to the deployed backend service (`vahin-backend.onrender.com`). Verify and implement these safeguards on the server codebase:

---

## 1. Endpoints & Features Implemented in Client

### A. Account Deletion (`POST /api/account/delete` or `POST /auth/delete-account`)
- **Purpose:** Handles in-app and public web deletion requests.
- **Action:** Purge user record, phone number mapping, associated FCM tokens, and any queued mailbox items from the database.
- **Example Express Route:**
```javascript
app.post('/api/account/delete', async (req, res) => {
  const { myId, fcmToken } = req.body;
  if (!myId) return res.status(400).json({ error: 'User ID is required' });
  // 1. Remove user from database / key-value store
  await db.users.delete(myId);
  // 2. Clear registered push tokens
  if (fcmToken) await db.tokens.delete(fcmToken);
  // 3. Clear pending mailboxes
  await db.mailboxes.delete(myId);
  return res.json({ success: true, message: 'Account and associated data deleted' });
});
```

### B. In-App Version Check (`GET /api/version` or `GET /app/version`)
- **Purpose:** Informs client apps of minimum supported and latest available versions.
- **Example Express Route:**
```javascript
app.get('/api/version', (req, res) => {
  res.json({
    min_version_code: 1,
    min_version_name: "1.0",
    latest_version_code: 1,
    latest_version_name: "1.0",
    force_update: false,
    update_url: "https://play.google.com/store/apps/details?id=com.vahin.unifest",
    message: "A new version of Unifest is available with performance and security improvements."
  });
});
```

### C. User Reporting Endpoint (`POST /api/report` or `POST /report`)
- **Purpose:** Logs user abuse reports for administrator review.
- **Example Express Route:**
```javascript
app.post('/api/report', async (req, res) => {
  const { reporterId, against, category, reason, time } = req.body;
  if (!against) return res.status(400).json({ error: 'Target ID required' });
  await db.reports.insert({ reporterId, against, category, reason, time: time || new Date() });
  console.log(`[REPORT] User ${reporterId} reported ${against} for ${category}: ${reason}`);
  return res.json({ success: true });
});
```

---

## 2. Security Safeguards

### A. Rate Limiting on Push Notification Relay (`/notify`)
- Apply `express-rate-limit` to `/notify` (e.g. max 30 notifications per minute per IP / token) to prevent malicious users from spamming push notifications at targets.

### B. Input Validation & Sanitization
- Strictly validate `myId`, `toId`, `peerId`, and `groupId` against alphanumeric/hyphen/underscore patterns (`^[a-zA-Z0-9_\-\+]+$`). Reject malformed strings or payloads exceeding size limits.

### C. CORS Configuration
- Lock down CORS from `*` to allowed origins (e.g. `capacitor://localhost`, `https://localhost`, and your web portal domain).

### D. PeerJS Server Hardening
- Ensure `peerjs` broker is configured with authentication keys or origin validation to prevent unauthorized signaling access.
