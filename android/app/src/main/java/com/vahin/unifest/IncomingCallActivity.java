package com.vahin.unifest;

import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.WindowManager;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Full-screen "someone is calling" UI, launched via a full-screen intent.
 * Shows over the lock screen like a real phone call.
 *
 * Fixes applied:
 *  1. Ringtone now plays on STREAM_RING with USAGE_VOICE_COMMUNICATION_SIGNALLING
 *     so it respects the ringer volume slider and rings audibly like a real call.
 *  2. Listens for ACTION_CALL_CANCELLED broadcast so this activity auto-dismisses
 *     when the caller hangs up before the callee answers.
 *  3. Cancels the call notification on entry so shade + full-screen don't fight.
 */
public class IncomingCallActivity extends AppCompatActivity {

    /** Broadcast action fired by VahinConnection.onAbort() when caller cancels. */
    public static final String ACTION_CALL_CANCELLED = "com.vahin.unifest.CALL_CANCELLED";

    private MediaPlayer ringtonePlayer;
    private Vibrator vibrator;
    private String from;

    // Dismissed when the caller hangs up mid-ring.
    private final BroadcastReceiver cancelReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Caller hung up — stop ringing and close cleanly (no action sent to web app).
            stopRingAndVibrate();
            finish();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Show over the lock screen, keep screen on, dismiss keyguard if possible.
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        );
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            if (km != null) km.requestDismissKeyguard(this, null);
        }

        setContentView(R.layout.activity_incoming_call);

        from = getIntent().getStringExtra("from");
        boolean isConf = getIntent().getBooleanExtra("isConf", false);

        TextView nameView = findViewById(R.id.incoming_call_name);
        TextView subView  = findViewById(R.id.incoming_call_sub);
        nameView.setText(from == null ? "Unknown" : from);
        subView.setText(isConf ? "Conference invite \u00b7 Unifest" : "Incoming call \u00b7 Unifest");

        findViewById(R.id.btn_incoming_accept).setOnClickListener(v -> finishWithAction("accept"));
        findViewById(R.id.btn_incoming_decline).setOnClickListener(v -> finishWithAction("decline"));

        // Cancel the notification so the shade doesn't show a duplicate card while
        // this full-screen activity is already visible.
        cancelCallNotification();

        // Listen for caller-cancelled broadcast.
        IntentFilter filter = new IntentFilter(ACTION_CALL_CANCELLED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(cancelReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(cancelReceiver, filter);
        }

        startRingAndVibrate();
    }

    private void startRingAndVibrate() {
        try {
            // Use the default ringtone URI; fall back to the default notification sound
            // if, for some reason, no ringtone is set on this device.
            Uri ringtoneUri = RingtoneManager.getActualDefaultRingtoneUri(
                this, RingtoneManager.TYPE_RINGTONE);
            if (ringtoneUri == null) {
                ringtoneUri = RingtoneManager.getActualDefaultRingtoneUri(
                    this, RingtoneManager.TYPE_NOTIFICATION);
            }

            ringtonePlayer = new MediaPlayer();
            ringtonePlayer.setDataSource(this, ringtoneUri);

            // STREAM_RING + USAGE_VOICE_COMMUNICATION_SIGNALLING is the correct pair
            // for incoming-call audio. This makes the volume slider that controls phone
            // calls also control our ring volume — exactly what a real dialer does.
            ringtonePlayer.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setLegacyStreamType(AudioManager.STREAM_RING)
                .build());
            ringtonePlayer.setLooping(true);
            ringtonePlayer.prepare();
            ringtonePlayer.start();
        } catch (Exception ignored) {
            // If MediaPlayer fails for any reason, vibration alone still alerts the user.
        }

        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vibrator != null) {
            // Pattern: 0ms delay, 800ms on, 400ms off — feels like a real phone vibration.
            long[] pattern = {0, 800, 400};
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
            } else {
                //noinspection deprecation
                vibrator.vibrate(pattern, 0);
            }
        }
    }

    private void stopRingAndVibrate() {
        if (ringtonePlayer != null) {
            try {
                if (ringtonePlayer.isPlaying()) ringtonePlayer.stop();
                ringtonePlayer.release();
            } catch (Exception ignored) {}
            ringtonePlayer = null;
        }
        if (vibrator != null) {
            vibrator.cancel();
            vibrator = null;
        }
    }

    private void cancelCallNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && from != null) {
            nm.cancel(("call-" + from).hashCode());
        }
    }

    private void finishWithAction(String action) {
        stopRingAndVibrate();
        cancelCallNotification();

        // Keep Telecom's call state in sync (answers/rejects via OS Bluetooth, Auto, etc.).
        VahinConnection conn = VahinConnection.getCurrent();
        if (conn != null) {
            if ("accept".equals(action)) conn.answerFromAppUi();
            else conn.rejectFromAppUi();
        }

        // Tell the web app what happened.
        Intent openMain = new Intent(this, MainActivity.class);
        openMain.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        openMain.putExtra("vahinAction", action);
        openMain.putExtra("vahinFrom", from);
        startActivity(openMain);
        finish();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // If a second call arrives while this screen is showing, update the display.
        String newFrom = intent.getStringExtra("from");
        if (newFrom != null) {
            from = newFrom;
            TextView nameView = findViewById(R.id.incoming_call_name);
            if (nameView != null) nameView.setText(from);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopRingAndVibrate();
        try { unregisterReceiver(cancelReceiver); } catch (Exception ignored) {}
    }
}