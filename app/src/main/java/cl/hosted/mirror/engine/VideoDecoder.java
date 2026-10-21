package cl.hosted.mirror.engine;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;

/**
 * Decodifica el video del espejo con el decodificador por hardware y lo dibuja
 * directo en la Surface, sin esperar el PTS (latencia mínima).
 */
public final class VideoDecoder {
    private static final String TAG = "HM.Video";

    public interface Listener {
        /** Tamaño real del cuadro decodificado (para ajustar la relación de aspecto). */
        void onVideoSize(int width, int height);
    }

    private final Surface surface;
    private final Listener listener;
    private Thread thread;
    private volatile boolean running;

    private MediaCodec codec;
    private boolean codecH265;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

    public VideoDecoder(Surface surface, Listener listener) {
        this.surface = surface;
        this.listener = listener;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        NativeBridge.nativeSetVideoAttached(true);
        thread = new Thread(this::loop, "hm-video");
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
    }

    public void stop() {
        Thread t;
        synchronized (this) {
            running = false;
            t = thread;
            thread = null;
        }
        NativeBridge.nativeSetVideoAttached(false);
        if (t != null) {
            try {
                t.join(1500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void loop() {
        try {
            while (running) {
                MediaPacket p = NativeBridge.nativeTakeVideo(30);
                if (p != null) feed(p);
                drain();
            }
        } finally {
            releaseCodec();
        }
    }

    private void feed(MediaPacket p) {
        try {
            if (codec == null || codecH265 != p.isH265()) {
                if (!p.isKeyframe()) return; /* sin SPS/PPS no se puede empezar */
                createCodec(p.isH265());
            }
            /* H.264 no tolera cuadros perdidos (cada cuadro depende del anterior):
               se espera un buffer libre mientras se van dibujando los ya decodificados */
            int in = -1;
            for (int tries = 0; in < 0 && running && codec != null && tries < 100; tries++) {
                in = codec.dequeueInputBuffer(10_000);
                if (in < 0) drain();
            }
            if (codec == null || in < 0) {
                Log.w(TAG, "decodificador bloqueado, se reinicia en el próximo keyframe");
                releaseCodec();
                return;
            }
            ByteBuffer buf = codec.getInputBuffer(in);
            buf.clear();
            if (p.data.length > buf.capacity()) {
                Log.w(TAG, "cuadro de " + p.data.length + " bytes excede el buffer " + buf.capacity());
                codec.queueInputBuffer(in, 0, 0, p.ptsUs, 0);
                return;
            }
            buf.put(p.data);
            codec.queueInputBuffer(in, 0, p.data.length, p.ptsUs,
                    p.isKeyframe() ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
        } catch (Exception e) {
            Log.e(TAG, "error al decodificar; se espera el próximo keyframe", e);
            releaseCodec();
        }
    }

    private void drain() {
        if (codec == null) return;
        try {
            while (true) {
                int out = codec.dequeueOutputBuffer(info, 0);
                if (out >= 0) {
                    codec.releaseOutputBuffer(out, true);
                } else if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    reportSize(codec.getOutputFormat());
                } else {
                    break;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "error al dibujar", e);
            releaseCodec();
        }
    }

    private void createCodec(boolean h265) throws Exception {
        releaseCodec();
        String mime = h265 ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC;
        int w = h265 ? 3840 : 1920;
        int h = h265 ? 2160 : 1080;
        MediaFormat f = MediaFormat.createVideoFormat(mime, w, h);
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 * 1024 * 1024);
        f.setInteger(MediaFormat.KEY_MAX_WIDTH, w);
        f.setInteger(MediaFormat.KEY_MAX_HEIGHT, h);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
        }
        f.setInteger(MediaFormat.KEY_PRIORITY, 0);
        codec = MediaCodec.createDecoderByType(mime);
        codec.configure(f, surface, null, 0);
        codec.start();
        codecH265 = h265;
        Log.i(TAG, "decodificador " + codec.getName() + " listo para " + mime);
    }

    private void reportSize(MediaFormat f) {
        int w = f.getInteger(MediaFormat.KEY_WIDTH);
        int h = f.getInteger(MediaFormat.KEY_HEIGHT);
        if (f.containsKey("crop-right") && f.containsKey("crop-left")) {
            w = f.getInteger("crop-right") - f.getInteger("crop-left") + 1;
        }
        if (f.containsKey("crop-bottom") && f.containsKey("crop-top")) {
            h = f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1;
        }
        Log.i(TAG, "cuadro de " + w + "x" + h);
        if (listener != null && w > 0 && h > 0) listener.onVideoSize(w, h);
    }

    private void releaseCodec() {
        if (codec != null) {
            try {
                codec.stop();
            } catch (Exception ignored) {
            }
            codec.release();
            codec = null;
        }
    }
}
