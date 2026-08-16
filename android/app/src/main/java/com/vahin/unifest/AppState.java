package com.vahin.unifest;

/**
 * Tiny shared flags read by VahinMessagingService to decide whether an incoming
 * FCM call push needs the native full-screen popup + Telecom integration, or
 * whether the app is already open and connected — in which case the in-app
 * PeerJS ring (showIncoming() in index.html) will handle it on its own, and
 * firing the native popup too would just be a redundant second ring on top of
 * the in-app one.
 *
 * - foreground: set by MainActivity.onStart()/onStop(). True whenever any part
 *   of the Activity is visible.
 * - peerReady: set by VahinPermissionsPlugin.setPeerConnected(), called from
 *   index.html every time peerReady changes (see setPeerReady() in the JS).
 *   True only while the PeerJS WebSocket to the broker is actually open — i.e.
 *   the app can actually receive a peer.on('call') event right now, not just
 *   that the Activity happens to be on screen.
 *
 * Both must be true to safely skip the native popup — foreground alone isn't
 * enough (the broker socket can still be mid-reconnect while the app is open),
 * and peerReady alone isn't enough (a backgrounded/killed app won't run any JS
 * to show an in-app ring even if PeerJS technically thinks it's connected).
 */
public final class AppState {
    private AppState() {}

    private static volatile boolean foreground = false;
    private static volatile boolean peerReady = false;

    public static void setForeground(boolean v) { foreground = v; }
    public static boolean isForeground() { return foreground; }

    public static void setPeerReady(boolean v) { peerReady = v; }
    public static boolean isPeerReady() { return peerReady; }

    /** True only when it's safe to rely on the in-app ring alone. */
    public static boolean canSkipNativePopup() { return foreground && peerReady; }
}
