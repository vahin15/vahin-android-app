package com.vahin.unifest;

import android.net.Uri;
import android.os.Bundle;
import android.telecom.Connection;
import android.telecom.ConnectionRequest;
import android.telecom.ConnectionService;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.util.Log;

public class VahinConnectionService extends ConnectionService {

    private static final String TAG = "VahinConnectionService";

    @Override
    public Connection onCreateIncomingConnection(
            PhoneAccountHandle connectionManagerPhoneAccount, ConnectionRequest request) {
        // FIX D: wrap the entire body so that if anything here throws (bad extras,
        // null request, OEM firmware quirk) we degrade gracefully: return a dummy
        // DISCONNECTED connection to Telecom (which is better than a crash that would
        // surface as the "back error" / ANR dialog the user reported) and fire
        // CallNotifier directly so the user at least gets the full-screen notification.
        try {
            Bundle extras = request != null ? request.getExtras() : null;
            String from  = extras != null ? extras.getString("from") : "Unknown";
            boolean isConf = extras != null && extras.getBoolean("isConf", false);

            Log.d(TAG, "onCreateIncomingConnection: from=" + from + " isConf=" + isConf);

            VahinConnection connection = new VahinConnection(this, from, isConf);
            connection.setAddress(
                Uri.fromParts("tel", sanitize(from), null), TelecomManager.PRESENTATION_ALLOWED);
            connection.setCallerDisplayName(from, TelecomManager.PRESENTATION_ALLOWED);
            VahinConnection.setCurrent(connection); // must be before setRinging() so onAbort/onReject can find it
            connection.setRinging();

            Log.d(TAG, "onCreateIncomingConnection: VahinConnection created and set to RINGING");
            return connection;

        } catch (Exception e) {
            // FIX D: log the full exception so this failure is diagnosable
            Log.e(TAG, "onCreateIncomingConnection: unexpected exception — "
                + e.getClass().getSimpleName() + ": " + e.getMessage()
                + ". Returning disconnected connection and falling back to CallNotifier.", e);

            // Graceful degradation: return a disconnected connection so Telecom doesn't
            // crash, then fire CallNotifier directly so the user still gets a ring.
            try {
                Bundle extras = request != null ? request.getExtras() : null;
                String from  = extras != null ? extras.getString("from") : "Unknown";
                boolean isConf = extras != null && extras.getBoolean("isConf", false);
                CallNotifier.showIncomingCall(this, isConf ? "conf" : "call", from);
            } catch (Exception inner) {
                Log.e(TAG, "onCreateIncomingConnection: fallback CallNotifier also threw: "
                    + inner.getMessage(), inner);
            }

            // Return a dead connection — Telecom requires a non-null return value.
            Connection dead = new Connection() {};
            dead.setDisconnected(new DisconnectCause(DisconnectCause.ERROR));
            dead.destroy();
            return dead;
        }
    }

    // Telecom wants a "tel:"-shaped URI; Unifest uses peer IDs, not phone numbers, so
    // strip anything that would make Uri.fromParts choke.
    private static String sanitize(String s) {
        return s == null ? "unknown" : s.replaceAll("[^a-zA-Z0-9]", "");
    }
}