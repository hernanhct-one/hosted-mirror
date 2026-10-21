package cl.hosted.mirror;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.File;

import cl.hosted.mirror.engine.AudioPlayer;
import cl.hosted.mirror.engine.NativeBridge;
import cl.hosted.mirror.engine.NativeEvent;

/**
 * Servicio en primer plano que mantiene el receptor AirPlay publicado en la red,
 * atiende los eventos del motor y abre la pantalla de espejo cuando llega video.
 */
public final class MirrorService extends Service {
    private static final String TAG = "HM.Service";
    private static final String CHANNEL = "hosted_mirror";
    private static final String ACTION_RESTART = "cl.hosted.mirror.RESTART";
    private static final int NOTIFICATION_ID = 1;

    private WifiManager.MulticastLock multicastLock;
    private WifiManager.WifiLock wifiLock;
    private final AudioPlayer audio = new AudioPlayer();
    private Thread engineThread;
    private volatile boolean running;
    private volatile boolean restartRequested;

    public static void start(Context c) {
        Intent i = new Intent(c, MirrorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i);
        else c.startService(i);
    }

    /** Vuelve a publicar el receptor (por ejemplo, tras cambiarle el nombre). */
    public static void restart(Context c) {
        Intent i = new Intent(c, MirrorService.class).setAction(ACTION_RESTART);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i);
        else c.startService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        goForeground();
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            multicastLock = wifi.createMulticastLock("hosted-mirror-mdns");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "hosted-mirror");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        }
        running = true;
        engineThread = new Thread(this::engineLoop, "hm-engine");
        engineThread.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground();
        if (intent != null && ACTION_RESTART.equals(intent.getAction())) {
            restartRequested = true;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (engineThread != null) {
            try {
                engineThread.join(3000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        audio.stop();
        NativeBridge.nativeStop();
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void engineLoop() {
        startServer();
        while (running) {
            if (restartRequested) {
                restartRequested = false;
                audio.stop();
                NativeBridge.nativeStop();
                startServer();
            }
            NativeEvent ev = NativeBridge.nativeTakeEvent(500);
            if (ev != null) handle(ev);
        }
    }

    private void startServer() {
        String name = Prefs.name(this);
        MirrorState.get().update(() -> {
            MirrorState s = MirrorState.get();
            s.server = MirrorState.Server.STARTING;
            s.name = name;
            s.connections = 0;
            s.mirroring = false;
        });
        String keyFile = new File(getFilesDir(), "airplay_key.pem").getAbsolutePath();
        int r = NativeBridge.nativeStart(name, Prefs.hwAddr(this), Prefs.deviceId(this), keyFile,
                Prefs.mdnsHost(this), 1920, 1080, 60);
        Log.i(TAG, "servidor AirPlay: " + (r == 0 ? "listo en el puerto " + NativeBridge.nativeGetPort() : "error " + r));
        MirrorState.get().update(() -> {
            MirrorState s = MirrorState.get();
            s.server = r == 0 ? MirrorState.Server.READY : MirrorState.Server.ERROR;
            s.errorCode = r;
        });
    }

    private void handle(NativeEvent ev) {
        Log.d(TAG, ev.toString());
        MirrorState state = MirrorState.get();
        switch (ev.type) {
            case NativeEvent.CONN_INIT:
                state.update(() -> state.connections = ev.a);
                break;
            case NativeEvent.CONN_DESTROY:
                if (ev.a == 0) {
                    audio.stop();
                    state.update(() -> {
                        state.connections = 0;
                        state.mirroring = false;
                        state.audioOnly = false;
                        state.clientName = "";
                        state.clientModel = "";
                    });
                } else {
                    state.update(() -> state.connections = ev.a);
                }
                break;
            case NativeEvent.CLIENT: {
                String[] parts = (ev.text == null ? "" : ev.text).split("\n", 2);
                state.update(() -> {
                    state.clientName = parts[0];
                    state.clientModel = parts.length > 1 ? parts[1] : "";
                });
                break;
            }
            case NativeEvent.VIDEO_START:
                state.update(() -> {
                    state.mirroring = true;
                    state.audioOnly = false;
                });
                openMirrorScreen();
                break;
            case NativeEvent.VIDEO_STOP:
                state.update(() -> state.mirroring = false);
                break;
            case NativeEvent.AUDIO_FORMAT:
                audio.start(ev.a);
                state.update(() -> state.audioOnly = ev.f == 0f && !state.mirroring);
                break;
            case NativeEvent.AUDIO_FLUSH:
                audio.flush();
                break;
            case NativeEvent.VOLUME:
                audio.setVolumeDb(ev.f);
                break;
            case NativeEvent.CONN_RESET:
                audio.stop();
                state.update(() -> state.mirroring = false);
                break;
            default:
                break;
        }
    }

    private void openMirrorScreen() {
        Intent i = new Intent(this, MirrorActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            startActivity(i);
        } catch (Exception e) {
            Log.e(TAG, "no se pudo abrir la pantalla de espejo", e);
        }
    }

    private void goForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, getString(R.string.app_name),
                        NotificationManager.IMPORTANCE_LOW);
                nm.createNotificationChannel(ch);
            }
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        Notification n = b.setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notification_text))
                .setSmallIcon(R.drawable.ic_mark)
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }
}
