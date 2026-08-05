package com.vahin.unifest;

import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;

/**
 * Registers Unifest as a self-managed Telecom calling account and hands incoming
 * calls to the OS via TelecomManager, instead of jumping straight to our own
 * notification. Self-managed accounts (API 26+) don't need the user to enable them
 * in Settings the way SIM-style ConnectionServices do — registerPhoneAccount() is
 * enough, and MANAGE_OWN_CALLS is a normal (not runtime-prompted) permission.
 */
public final class VahinTelecom {

    private static final String ACCOUNT_ID = "vahin_unifest_account";

    private VahinTelecom() {}

    public static PhoneAccountHandle handle(Context ctx) {
        return new PhoneAccountHandle(
            new ComponentName(ctx, VahinConnectionService.class), ACCOUNT_ID);
    }

    // Call once from MainActivity.onCreate. Cheap and idempotent — safe every launch.
    public static void register(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        TelecomManager tm = (TelecomManager) ctx.getSystemService(Context.TELECOM_SERVICE);
        if (tm == null) return;
        PhoneAccount account = PhoneAccount.builder(handle(ctx), "Unifest")
            .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
            .setShortDescription("Unifest calls")
            .build();
        tm.registerPhoneAccount(account);
    }

    // Called from VahinMessagingService when an FCM "call"/"conf" push lands. This
    // replaces the old direct-to-CallNotifier line. Telecom calls back into
    // VahinConnectionService.onCreateIncomingConnection(), which in turn triggers
    // VahinConnection.onShowIncomingCallUi() -> our existing IncomingCallActivity.
    // Returns false if Telecom refuses it (rare on self-managed, but some OEMs
    // block third-party calling accounts) so the caller can fall back.
    public static boolean addIncomingCall(Context ctx, String from, boolean isConf) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false;
        TelecomManager tm = (TelecomManager) ctx.getSystemService(Context.TELECOM_SERVICE);
        if (tm == null) return false;
        try {
            Bundle extras = new Bundle();
            extras.putString("from", from);
            extras.putBoolean("isConf", isConf);
            tm.addNewIncomingCall(handle(ctx), extras);
            return true;
        } catch (SecurityException | IllegalStateException e) {
            return false;
        }
    }
}
