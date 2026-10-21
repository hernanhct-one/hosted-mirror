package cl.hosted.mirror.engine;

/** Evento del motor AirPlay. Los tipos están sincronizados con hm_bridge.cpp. */
public final class NativeEvent {
    public static final int CONN_INIT = 1;
    public static final int CONN_DESTROY = 2;
    public static final int CONN_RESET = 3;
    public static final int CLIENT = 4;
    public static final int VIDEO_START = 5;
    public static final int VIDEO_SIZE = 6;
    public static final int VIDEO_STOP = 7;
    public static final int VIDEO_PAUSE = 8;
    public static final int VIDEO_RESUME = 9;
    public static final int AUDIO_FORMAT = 10;
    public static final int AUDIO_FLUSH = 11;
    public static final int VOLUME = 12;

    public final int type;
    public final int a;
    public final int b;
    public final float f;
    public final String text;

    public NativeEvent(int type, int a, int b, float f, String text) {
        this.type = type;
        this.a = a;
        this.b = b;
        this.f = f;
        this.text = text;
    }

    @Override
    public String toString() {
        return "NativeEvent{" + type + ", a=" + a + ", b=" + b + ", f=" + f + ", text=" + text + "}";
    }
}
