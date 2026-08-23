# Google Play Console — Data Safety Form Guide (Unifest)

Use this document to complete the **Data Safety** section in Google Play Console.

---

## 1. Overview Questions

- **Does your app collect or share any of the required user data types?**  
  👉 **Yes**
- **Is all of the user data collected by your app encrypted in transit?**  
  👉 **Yes** (All HTTPS/WSS and WebRTC DTLS-SRTP encryption)
- **Do you provide a way for users to request that their data be deleted?**  
  👉 **Yes** (In-app deletion in Settings and via public URL `https://<your-domain>/data-deletion.html`)

---

## 2. Data Types & Declarations

### A. Personal Info
#### **Phone Number**
- **Collected?** Yes
- **Shared?** No
- **Ephemeral?** No (Stored on backend until user deletes account)
- **Required or Optional?** Required for account functionality
- **Purposes:**
  - `App functionality` (User identification & call routing)
  - `Account management`

---

### B. Device or Other IDs
#### **Device or other IDs (FCM Token, Advertising ID)**
- **Collected?** Yes
- **Shared?** No (FCM token used only by your backend via Firebase; Ad ID used by Google Mobile Ads)
- **Ephemeral?** No
- **Required or Optional?** Required
- **Purposes:**
  - `App functionality` (Push notifications for incoming calls and messages)
  - `Advertising or marketing` (Google AdMob banner ads)
  - `Analytics` (Firebase)

---

### C. App Info and Performance
#### **Crash Logs & Diagnostics**
- **Collected?** Yes
- **Shared?** No
- **Ephemeral?** No
- **Required or Optional?** Required
- **Purposes:**
  - `Analytics`
  - `App functionality` (Crashlytics bug diagnosis & stability monitoring)

---

### D. Messages / Photos / Audio / Video
- **Messages (SMS/Text)?**  
  👉 **No** (Messages are transmitted purely peer-to-peer over encrypted WebRTC data channels and stored only in local device storage; not collected on central servers).
- **Photos and Videos?**  
  👉 **No** (Direct P2P file transfer; not collected on central servers).
- **Voice or Sound Recordings?**  
  👉 **No** (Direct P2P WebRTC audio streams; not collected or recorded).

---

## 3. URLs for Store Listing
- **Privacy Policy URL:** `https://<your-username>.github.io/vahin-android-app/privacy-policy.html` (or your hosted domain)
- **Data Deletion Request URL:** `https://<your-username>.github.io/vahin-android-app/data-deletion.html`
- **Developer Support Email:** `unifest.messenger@gmail.com`
