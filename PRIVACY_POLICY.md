# Unifest Privacy Policy

*Last updated: August 2026*

Welcome to **Unifest**. Unifest is built from the ground up as a decentralized, peer-to-peer (P2P) voice, video, and messaging application.

## 1. Summary of Principles
- **No Central Message Storage**: Text messages, voice notes, and media attachments sent between users are transmitted directly peer-to-peer via WebRTC data channels.
- **End-to-End Encryption**: Calls and data channels utilize standard WebRTC DTLS-SRTP encryption.
- **Minimal Central Data**: The backend only stores routing metadata (phone number / user ID mapping and FCM push notification token).

## 2. Information Collected
1. **Phone Number & User ID**: Used strictly for account identification and discovery between peers.
2. **Push Notification Token (FCM)**: Used by Firebase Cloud Messaging to wake the device when an incoming call or message arrives while the app is backgrounded.
3. **Presence**: Ephemeral online/offline status for peer routing.
4. **Google Advertising ID (AD_ID)**: Collected by Google Mobile Ads (AdMob) SDK to serve advertisements.
5. **Crash Diagnostics**: Collected anonymously by Firebase Crashlytics to diagnose app stability and resolve bugs.

## 3. WebRTC Relay (TURN/STUN)
In cases where network NAT or firewalls prevent a direct peer-to-peer connection, encrypted media packets pass through standard TURN relay servers (Metered.ca). Relayed traffic is encrypted end-to-end and cannot be decrypted, recorded, or stored by the relay.

## 4. Third-Party Services
- **Google Play Services & Firebase**: Push notifications and crash reporting.
- **Google AdMob**: Banner ads.
- **Metered.ca**: STUN/TURN traversal.

## 5. Account & Data Deletion
Users can delete their account and all local data at any time:
- **In-App**: `Settings > Account > Delete Account & Data`
- **Web**: Via public data deletion form at `www/data-deletion.html` or contacting developer support.

## 6. Contact
For any privacy questions:
- Email: `unifest.messenger@gmail.com`
