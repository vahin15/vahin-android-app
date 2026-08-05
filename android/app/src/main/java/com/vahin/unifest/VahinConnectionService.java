package com.vahin.unifest;

import android.net.Uri;
import android.os.Bundle;
import android.telecom.Connection;
import android.telecom.ConnectionRequest;
import android.telecom.ConnectionService;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;

public class VahinConnectionService extends ConnectionService {

    @Override
    public Connection onCreateIncomingConnection(
            PhoneAccountHandle connectionManagerPhoneAccount, ConnectionRequest request) {
        Bundle extras = request.getExtras();
        String from = extras != null ? extras.getString("from") : "Unknown";
        boolean isConf = extras != null && extras.getBoolean("isConf", false);

        VahinConnection connection = new VahinConnection(this, from, isConf);
        connection.setAddress(
            Uri.fromParts("tel", sanitize(from), null), TelecomManager.PRESENTATION_ALLOWED);
        connection.setCallerDisplayName(from, TelecomManager.PRESENTATION_ALLOWED);
        connection.setRinging();
        VahinConnection.setCurrent(connection);
        return connection;
    }

    // Telecom wants a "tel:"-shaped URI; Unifest uses peer IDs, not phone numbers, so
    // strip anything that would make Uri.fromParts choke.
    private static String sanitize(String s) {
        return s == null ? "unknown" : s.replaceAll("[^a-zA-Z0-9]", "");
    }
}
