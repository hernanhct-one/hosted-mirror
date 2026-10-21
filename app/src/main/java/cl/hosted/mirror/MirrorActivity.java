package cl.hosted.mirror;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import cl.hosted.mirror.engine.NativeBridge;
import cl.hosted.mirror.engine.VideoDecoder;

/** Pantalla completa donde se dibuja el espejo del iPhone, iPad o Mac. */
public final class MirrorActivity extends Activity implements SurfaceHolder.Callback, MirrorState.Listener {
    private SurfaceView surfaceView;
    private View waiting;
    private TextView waitingText;
    private VideoDecoder decoder;
    private int videoWidth;
    private int videoHeight;
    private boolean sawMirroring;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_mirror);
        surfaceView = findViewById(R.id.surface);
        waiting = findViewById(R.id.waiting);
        waitingText = findViewById(R.id.waiting_text);
        surfaceView.getHolder().addCallback(this);
        findViewById(R.id.root).addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> fitVideo());
    }

    @Override
    protected void onStart() {
        super.onStart();
        MirrorState.get().addListener(this);
    }

    @Override
    protected void onStop() {
        MirrorState.get().removeListener(this);
        super.onStop();
        if (!isFinishing()) {
            /* el usuario salió con el botón Inicio: se corta el espejo como en un Apple TV */
            NativeBridge.nativeDisconnect();
            finish();
        }
    }

    @Override
    public void onMirrorStateChanged(MirrorState s) {
        if (s.mirroring) sawMirroring = true;
        if (sawMirroring && !s.mirroring) {
            finish();
            return;
        }
        String who = s.clientName == null || s.clientName.isEmpty() ? getString(R.string.your_device) : s.clientName;
        waitingText.setText(getString(R.string.connecting_with, who));
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            NativeBridge.nativeDisconnect();
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        decoder = new VideoDecoder(holder.getSurface(), (w, h) -> runOnUiThread(() -> {
            videoWidth = w;
            videoHeight = h;
            waiting.animate().alpha(0f).setDuration(250).withEndAction(() -> waiting.setVisibility(View.GONE));
            fitVideo();
        }));
        decoder.start();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (decoder != null) {
            decoder.stop();
            decoder = null;
        }
    }

    /** Ajusta la superficie a la proporción del video (iPhone vertical u horizontal). */
    private void fitVideo() {
        View root = findViewById(R.id.root);
        int pw = root.getWidth();
        int ph = root.getHeight();
        if (pw == 0 || ph == 0) return;
        int w = pw;
        int h = ph;
        if (videoWidth > 0 && videoHeight > 0) {
            float scale = Math.min((float) pw / videoWidth, (float) ph / videoHeight);
            w = Math.round(videoWidth * scale);
            h = Math.round(videoHeight * scale);
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) surfaceView.getLayoutParams();
        if (lp.width != w || lp.height != h) {
            lp.width = w;
            lp.height = h;
            surfaceView.setLayoutParams(lp);
        }
    }
}
