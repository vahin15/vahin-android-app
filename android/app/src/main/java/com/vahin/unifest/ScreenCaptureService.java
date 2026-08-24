package com.vahin.unifest;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * Owns the actual screen-capture pipeline: MediaProjection -> VirtualDisplay ->
 * ImageReader -> JPEG-encoded frames pushed to VahinScreenSharePlugin.
 *
 * WHY THIS EXISTS: Android's WebView (unlike Chrome) has never implemented
 * navigator.mediaDevices.getDisplayMedia() — that's the actual reason "Share
 * Screen" silently did nothing before. There is no way to make that browser API
 * work inside a WebView; the only real fix is capturing the screen natively via
 * MediaProjection and feeding the frames into the WebView as a real
 * MediaStreamTrack (see index.html's toggleScreenShare(), which draws each
 * incoming frame onto an offscreen <canvas> and uses canvas.captureStream() to
 * produce a track that WebRTC's replaceTrack() can use exactly like a camera
 * track). This is the standard workaround for this specific, long-standing
 * WebView limitation.
 *
 * Runs as a genuine foreground service (required by Android 10+ to use
 * MediaProjection at all, and required to declare
 * foregroundServiceType="mediaProjection" on Android 14+/API 34+, enforced with
 * a SecurityException otherwise) so capture only ever runs while the user can
 * see the persistent "Screen is being shared" notification, and stops instantly
 * if they swipe it away or tap Stop in-call.
 */
public class ScreenCaptureService extends Service {
    private static final String TAG = "ScreenCaptureService";
    private static final String CHANNEL_ID = "vahin_screenshare";
    private static final int NOTIF_ID = 4271;

    /** Target capture cadence. Kept modest — this rides the same JS<->native
        bridge as everything else (base64 JSON messages), so higher isn't free.
        8fps is plenty to read text/share a slide deck/demo an app; it is not
        meant to carry fast motion/video smoothly. */
    private static final int TARGET_FPS = 8;
    private static final long FRAME_INTERVAL_MS = 1000L / TARGET_FPS;
    /** Downscale capture to this max longest-side to keep each JPEG small
        enough to push over the bridge at TARGET_FPS without falling behind. */
    private static final int MAX_DIMENSION = 1280;

    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";

    /** In-process callback the plugin registers so the service can hand frames
        straight back without a second IPC hop — this service only ever runs
        inside the app's own process, so a static reference is safe and simple. */
    public interface FrameListener {
        void onFrame(String base64Jpeg, int width, int height);
        void onCaptureEnded(String reason);
    }
    private static volatile FrameListener listener;
    public static void setFrameListener(FrameListener l) { listener = l; }

    private MediaProjectionManager projectionManager;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread captureThread;
    private Handler captureHandler;
    private long lastFrameAt = 0;
    private int width, height, densityDpi;

    @Override
    public void onCreate() {
        super.onCreate();
        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelfSafely("missing intent"); return START_NOT_STICKY; }
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1);
        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        if (resultData == null) { stopSelfSafely("missing permission data"); return START_NOT_STICKY; }

        startForeground(NOTIF_ID, buildNotification());

        try {
            projection = projectionManager.getMediaProjection(resultCode, resultData);
        } catch (Exception e) {
            DebugLog.log(TAG, "getMediaProjection failed: " + e.getMessage());
            stopSelfSafely("could not start projection");
            return START_NOT_STICKY;
        }
        if (projection == null) { stopSelfSafely("projection was null"); return START_NOT_STICKY; }

        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() {
                DebugLog.log(TAG, "MediaProjection.onStop — system or user revoked capture");
                stopSelfSafely("system stopped capture");
            }
        }, null);

        setupCapture();
        return START_NOT_STICKY;
    }

    private void setupCapture() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int rawW = dm.widthPixels, rawH = dm.heightPixels;
        densityDpi = dm.densityDpi;
        float scale = Math.min(1f, ((float) MAX_DIMENSION) / Math.max(rawW, rawH));
        width = Math.max(2, Math.round(rawW * scale) / 2 * 2);   // keep even dimensions
        height = Math.max(2, Math.round(rawH * scale) / 2 * 2);

        captureThread = new HandlerThread("VahinScreenCapture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());

        imageReader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(this::onImageAvailable, captureHandler);

        try {
            virtualDisplay = projection.createVirtualDisplay(
                "VahinScreenShare",
                width, height, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, captureHandler);
        } catch (Exception e) {
            DebugLog.log(TAG, "createVirtualDisplay failed: " + e.getMessage());
            stopSelfSafely("could not create virtual display");
        }
    }

    private void onImageAvailable(ImageReader reader) {
        long now = System.currentTimeMillis();
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) return;
            // Throttle to TARGET_FPS regardless of how fast frames actually arrive —
            // ImageReader will happily hand us far more than we want to encode/send.
            if (now - lastFrameAt < FRAME_INTERVAL_MS) return;
            lastFrameAt = now;

            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int rowPadding = rowStride - pixelStride * width;

            Bitmap bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(buffer);
            if (rowPadding != 0) bitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 62, baos);
            String b64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP);
            bitmap.recycle();

            FrameListener l = listener;
            if (l != null) l.onFrame(b64, width, height);
        } catch (Exception e) {
            Log.w(TAG, "onImageAvailable: " + e.getMessage());
        } finally {
            if (image != null) image.close();
        }
    }

    private android.app.Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Screen sharing", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("Shown while your screen is visible to the other person on a call");
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("Unifest")
            .setContentText("Your screen is being shared on the call")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pi)
            .build();
    }

    private void stopSelfSafely(String reason) {
        FrameListener l = listener;
        if (l != null) l.onCaptureEnded(reason);
        teardown();
        stopForeground(true);
        stopSelf();
    }

    private void teardown() {
        try { if (virtualDisplay != null) virtualDisplay.release(); } catch (Exception ignored) {}
        virtualDisplay = null;
        try { if (imageReader != null) imageReader.close(); } catch (Exception ignored) {}
        imageReader = null;
        try { if (projection != null) projection.stop(); } catch (Exception ignored) {}
        projection = null;
        try { if (captureThread != null) captureThread.quitSafely(); } catch (Exception ignored) {}
        captureThread = null;
    }

    @Override
    public void onDestroy() {
        teardown();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}
