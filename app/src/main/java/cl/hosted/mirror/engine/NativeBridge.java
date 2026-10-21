package cl.hosted.mirror.engine;

/** Acceso al motor AirPlay nativo (UxPlay + puente hm_bridge.cpp). */
public final class NativeBridge {
    static {
        System.loadLibrary("hostedmirror");
    }

    private NativeBridge() {
    }

    /**
     * Publica el receptor en la red y abre el servidor AirPlay.
     *
     * @return 0 si arrancó; otro valor indica en qué paso falló.
     */
    public static native int nativeStart(String name, byte[] hwAddr, String deviceId, String keyFile,
                                         String mdnsHost, int width, int height, int maxFps);

    public static native void nativeStop();

    public static native int nativeGetPort();

    /** Corta la sesión actual (por ejemplo, al apretar Atrás durante el espejo). */
    public static native void nativeDisconnect();

    /** Indica si hay un decodificador consumiendo video (cambia cómo se guardan los frames). */
    public static native void nativeSetVideoAttached(boolean attached);

    public static native MediaPacket nativeTakeVideo(int timeoutMs);

    public static native MediaPacket nativeTakeAudio(int timeoutMs);

    public static native NativeEvent nativeTakeEvent(int timeoutMs);
}
