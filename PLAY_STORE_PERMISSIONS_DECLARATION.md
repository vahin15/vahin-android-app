# Google Play Console — Permissions Declaration Guide (Unifest)

When submitting Unifest to Google Play Console, you will be prompted to justify certain sensitive and high-risk permissions. Use the declarations below.

---

## 1. Foreground Service Permission (`FOREGROUND_SERVICE` & `FOREGROUND_SERVICE_DATA_SYNC`)

### **Type Selected:** `DATA_SYNC`
### **User-Facing Description (Video/Prompt):**
> "Unifest is a real-time peer-to-peer VoIP calling and messaging app. The `DATA_SYNC` foreground service (`SignalService`) maintains an active, low-overhead WebSocket signaling connection with the backend while the app is active in the background. This ensures the app can reliably receive real-time call invitations and peer signaling packets from callers when Google Play Services FCM delivery is delayed or constrained by OEM battery management."

### **Why WorkManager / JobScheduler is not sufficient:**
> "VoIP calling requires instant sub-second signaling latency to ring the callee in real time before the caller's connection times out (typically within 15-20 seconds). Periodic or delayed JobScheduler tasks cannot support real-time call initiation."

---

## 2. Full-Screen Intent Permission (`USE_FULL_SCREEN_INTENT`)

### **Declared Use Case:** `Incoming VoIP Call Alerts`
### **Justification:**
> "Unifest provides high-priority VoIP audio and video calling. The `USE_FULL_SCREEN_INTENT` permission is strictly used by `IncomingCallActivity` to present an incoming full-screen ringing UI over the device lock screen when the user receives an incoming call while the phone is locked or the screen is off, identical to the standard Android phone dialer experience."

---

## 3. Manage Own Calls Permission (`MANAGE_OWN_CALLS`) & Telecom Integration

### **Declared Use Case:** `VoIP Calling Integration (ConnectionService)`
### **Justification:**
> "Unifest integrates with Android's self-managed `TelecomManager` and `ConnectionService` to ensure VoIP calls receive proper audio focus, can be answered via Bluetooth headsets / Android Auto, and seamlessly coordinate with cellular call interruptions."

---

## 4. Camera & Microphone Permissions (`CAMERA`, `RECORD_AUDIO`)

### **Justification:**
> "Required strictly for user-initiated peer-to-peer WebRTC voice and video calls, voice note recording in chat, and capturing profile photos. Audio and video streams are transmitted directly peer-to-peer and are never recorded on backend servers."

---

## 5. Advertising ID Permission (`com.google.android.gms.permission.AD_ID`)

### **Justification:**
> "Used by the Google Mobile Ads SDK (AdMob) to serve banner advertisements in the app."
