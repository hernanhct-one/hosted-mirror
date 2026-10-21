package cl.hosted.mirror.engine;

/** Paquete de video (H.264/H.265 Annex-B) o de audio entregado por el motor nativo. */
public final class MediaPacket {
    /** Video: el paquete es H.265. */
    public static final int FLAG_H265 = 1;
    /** Video: el paquete trae un keyframe o parámetros SPS/VPS. */
    public static final int FLAG_KEYFRAME = 2;

    /** Audio: tipo de compresión AirPlay ("ct"). */
    public static final int CT_PCM = 1;
    public static final int CT_AAC_LC = 4;
    public static final int CT_AAC_ELD = 8;

    public final byte[] data;
    public final long ptsUs;
    /** En video, combinación de FLAG_*; en audio, uno de CT_*. */
    public final int flags;

    public MediaPacket(byte[] data, long ptsUs, int flags) {
        this.data = data;
        this.ptsUs = ptsUs;
        this.flags = flags;
    }

    public boolean isH265() {
        return (flags & FLAG_H265) != 0;
    }

    public boolean isKeyframe() {
        return (flags & FLAG_KEYFRAME) != 0;
    }
}
