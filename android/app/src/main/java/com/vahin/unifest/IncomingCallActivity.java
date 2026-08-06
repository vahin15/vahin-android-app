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
import java.io.File;

/**
 * Full-screen incoming call UI shown over the lock screen.
 *
 * Ring behaviour:
 *   - If the user has set a custom ringtone (saved as a file by VahinPermissionsPlugin
 *     .saveCustomRingtone), that file is played with STREAM_RING audio attributes.
 *   - Otherwise the device's default ringtone is used.
 *   Both paths use USAGE_VOICE_COMMUNICATION_SIGNALLING + STREAM_RING so the ringer
 *   volume slider (not notification volume) controls the call ring level, matching
 *   exactly what the built-in phone dialer does.
 *
 * Caller-cancel:
 *   VahinConnection.onAbort() broadcasts ACTION_CALL_CANCELLED when the remote side
 *   hangs up before the callee answers. cancelReceiver catches it and calls finish()
 *   so the screen goes away immediately instead of ringing until timeout.
 *
 * Notification:
 *   The call notification is cancelled as soon as this activity is visible (so the
 *   shade doesn't show a duplicate) and again when the user accepts or rejects.
 */
public class IncomingCallActivity extends AppCompatActivity {

    /** Broadcast sent by VahinConnection.onAbort() when the caller hangs up mid-ring. */
    public static final String ACTION_CALL_CANCELLED = "com.vahin.unifest.CALL_CANCELLED";

    private MediaPlayer ringtonePlayer;
    private Vibrator vibrator;
    private String from;

    private final BroadcastReceiver cancelReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Caller hung up — stop ringing, cancel notification, close.
            stopRingAndVibrate();
            cancelCallNotification();
            finish();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Show over lock screen, keep screen on.
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

        // Cancel the heads-up notification immediately — the full-screen UI IS the UI now.
        cancelCallNotification();

        // Register for caller-cancelled broadcast (fired from VahinConnection.onAbort).
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
            // 1. Try the user's custom ringtone saved by VahinPermissionsPlugin.saveCustomRingtone
            File customFile = VahinPermissionsPlugin.getCustomRingtoneFile(this);
            Uri ringtoneUri;
            if (customFile.exists() && customFile.length() > 0) {
                ringtoneUri = Uri.fromFile(customFile);
            } else {
                // 2. Fall back to the device's default phone ringtone
                ringtoneUri = RingtoneManager.getActualDefaultRingtoneUri(
                    this, RingtoneManager.TYPE_RINGTONE);
                if (ringtoneUri == null) {
                    // 3. Last resort: default notification sound
                    ringtoneUri = RingtoneManager.getActualDefaultRingtoneUri(
                        this, RingtoneManager.TYPE_NOTIFICATION);
                }
            }

            ringtonePlayer = new MediaPlayer();
            ringtonePlayer.setDataSource(this, ringtoneUri);

            // USAGE_VOICE_COMMUNICATION_SIGNALLING + STREAM_RING = ringer volume slider
            // controls call ring level, not the notification volume. This is the same
            // pair used by AOSP's own Dialer app for incoming calls.
            ringtonePlayer.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setLegacyStreamType(AudioManager.STREAM_RING)
                .build());
            ringtonePlayer.setLooping(true);
            ringtonePlayer.prepare();
            ringtonePlayer.start();
        } catch (Exception ignored) {
            // If MediaPlayer fails, vibration alone still alerts the user.
        }

        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vibrator != null) {
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

        // Keep Telecom in sync — covers Bluetooth headset / Android Auto answer buttons.
        VahinConnection conn = VahinConnection.getCurrent();
        if (conn != null) {
            if ("accept".equals(action)) conn.answerFromAppUi();
            else conn.rejectFromAppUi();
        }

        // Bring MainActivity to front with the action so the web app can react.
        Intent openMain = new Intent(this, MainActivity.class);
        openMain.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        openMain.putExtra("vahinAction", action);
        openMain.putExtra("vahinFrom", from);
        startActivity(openMain);
        finish();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
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