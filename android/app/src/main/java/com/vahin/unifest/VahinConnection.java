package com.vahin.unifest;

import android.content.Context;
import android.telecom.Connection;
import android.telecom.DisconnectCause;

/**
 * One ringing/active call inside Telecom. onAnswer/onReject fire from the OS (Bluetooth
 * headset button, Android Auto, wearable) — IncomingCallActivity's own buttons also call
 * answerFromAppUi()/rejectFromAppUi() via getCurrent() so both input paths keep Telecom's
 * call state in sync instead of only closing our activity.
 */
public class VahinConnection extends Connection {

    private static VahinConnection current;

    private final Context appContext;
    private final String from;
    private final boolean isConf;

    public VahinConnection(Context ctx, String from, boolean isConf) {
        this.appContext = ctx.getApplicationContext();
        this.from = from;
        this.isConf = isConf;
        setConnectionProperties(PROPERTY_SELF_MANAGED);
        setAudioModeIsVoip(true);
    }

    public static synchronized void setCurrent(VahinConnection c) { current = c; }
    public static synchronized VahinConnection getCurrent() { return current; }

    @Override
    public void onShowIncomingCallUi() {
        // Telecom's cue to draw our own UI now — this is the hook that used to be
        // "FCM arrived -> CallNotifier.showIncomingCall()" directly.
        CallNotifier.showIncomingCall(appContext, isConf ? "conf" : "call", from);
    }

    @Override
    public void onAnswer() {
        setActive();
        notifyWebApp("accept");
    }

    @Override
    public void onReject() {
        setDisconnected(new DisconnectCause(DisconnectCause.REJECTED));
        destroy();
        clearIfCurrent();
    }

    @Override
    public void onDisconnect() {
        setDisconnected(new DisconnectCause(DisconnectCause.LOCAL));
        destroy();
        clearIfCurrent();
    }

    @Override
    public void onAbort() {
        setDisconnected(new DisconnectCause(DisconnectCause.CANCELED));
        destroy();
        clearIfCurrent();
    }

    public void answerFromAppUi() { onAnswer(); }
    public void rejectFromAppUi() { onReject(); }

    private void clearIfCurrent() {
        synchronized (VahinConnection.class) {
            if (current == this) current = null;
        }
    }

    private void notifyWebApp(String action) {
        String safeFrom = from == null ? "" : from.replace("'", "\\'");
        String js = "window.handleNativeCallAction && window.handleNativeCallAction('"
            + action + "','" + safeFrom + "');";
        MainActivity.runJsIfAvailable(js);
    }
}
