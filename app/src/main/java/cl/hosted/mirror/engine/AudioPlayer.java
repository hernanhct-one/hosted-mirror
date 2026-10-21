package cl.hosted.mirror.engine;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;

import java.nio.ByteBuffer;

/**
 * Reproduce el audio AirPlay: PCM (ALAC ya decodificado en nativo) directo a
 * AudioTrack, y AAC-LC / AAC-ELD pasando antes por MediaCodec.
 */
public final class AudioPlayer {
    private static final String TAG = "HM.Audio";
    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNELS = 2;
    /* AudioSpecificConfig que usa AirPlay (44.1 kHz estéreo) */
    private static final byte[] CSD_AAC_ELD = {(byte) 0xF8, (byte) 0xE8, 0x50, 0x00};
    private static final byte[] CSD_AAC_LC = {0x12, 0x10};

    private final Object lock = new Object();
    private Thread thread;
    private volatile boolean running;
    private volatile int format;
    private volatile boolean flushRequested;
    private volatile float gain = 1f;

    private AudioTrack track;
    private MediaCodec codec;
    private int codecFormat;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

    public void start(int ct) {
        synchronized (lock) {
            format = ct;
            flushRequested = true;
            if (running) return;
            running = true;
            thread = new Thread(this::loop, "hm-audio");
            thread.setPriority(Thread.MAX_PRIORITY);
            thread.start();
        }
    }

    public void stop() {
        Thread t;
        synchronized (lock) {
            running = false;
            t = thread;
            thread = null;
        }
        if (t != null) {
            try {
                t.join(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void flush() {
        flushRequested = true;
    }

    /** Volumen AirPlay en dB: -144 es silencio, el rango normal va de -30 a 0. */
    public void setVolumeDb(float db) {
        float g;
        if (db <= -144f) g = 0f;
        else if (db >= 0f) g = 1f;
        else g = (float) Math.pow(10.0, Math.max(db, -30f) / 20.0);
        gain = g;
        AudioTrack t = track;
        if (t != null) t.setVolume(g);
    }

    private void loop() {
        try {
            openTrack();
            while (running) {
                if (flushRequested) {
                    flushRequested = false;
                    if (track != null) {
                        track.pause();
                        track.flush();
                        track.play();
                    }
                    if (codec != null) {
                        try {
                            codec.flush();
                        } catch (IllegalStateException e) {
                            releaseCodec();
                        }
                    }
                }
                MediaPacket p = NativeBridge.nativeTakeAudio(100);
                if (p == null) continue;
                if (p.flags == MediaPacket.CT_PCM) {
                    releaseCodec();
                    track.write(p.data, 0, p.data.length);
                } else if (p.flags == MediaPacket.CT_AAC_ELD || p.flags == MediaPacket.CT_AAC_LC) {
                    decodeAac(p);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "error de audio", e);
        } finally {
            releaseCodec();
            if (track != null) {
                track.release();
                track = null;
            }
        }
    }

    private void openTrack() {
        int min = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build())
                .setBufferSizeInBytes(Math.max(min * 2, 16384))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        track.setVolume(gain);
        track.play();
    }

    private void decodeAac(MediaPacket p) throws Exception {
        if (codec == null || codecFormat != p.flags) {
            releaseCodec();
            MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS);
            byte[] csd = p.flags == MediaPacket.CT_AAC_ELD ? CSD_AAC_ELD : CSD_AAC_LC;
            f.setByteBuffer("csd-0", ByteBuffer.wrap(csd));
            f.setInteger(MediaFormat.KEY_IS_ADTS, 0);
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            codec.configure(f, null, null, 0);
            codec.start();
            codecFormat = p.flags;
            Log.i(TAG, "decodificador AAC listo (ct=" + p.flags + ") " + codec.getName());
        }
        int in = codec.dequeueInputBuffer(10_000);
        if (in >= 0) {
            ByteBuffer buf = codec.getInputBuffer(in);
            buf.clear();
            if (p.data.length <= buf.capacity()) {
                buf.put(p.data);
                codec.queueInputBuffer(in, 0, p.data.length, p.ptsUs, 0);
            } else {
                codec.queueInputBuffer(in, 0, 0, p.ptsUs, 0);
            }
        }
        int out;
        while ((out = codec.dequeueOutputBuffer(info, 0)) >= 0) {
            if (info.size > 0) {
                ByteBuffer o = codec.getOutputBuffer(out);
                o.position(info.offset);
                o.limit(info.offset + info.size);
                track.write(o, info.size, AudioTrack.WRITE_BLOCKING);
            }
            codec.releaseOutputBuffer(out, false);
        }
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
