package cl.hosted.mirror;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;
import java.util.Locale;

/** Configuración persistente: nombre visible del receptor e identidad de red. */
public final class Prefs {
    public static final String DEFAULT_NAME = "Hosted Mirror";
    private static final String FILE = "hosted_mirror";
    private static final String KEY_NAME = "name";
    private static final String KEY_HW = "hw_addr";

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static String name(Context c) {
        String n = sp(c).getString(KEY_NAME, DEFAULT_NAME);
        return n == null || n.trim().isEmpty() ? DEFAULT_NAME : n.trim();
    }

    public static void setName(Context c, String name) {
        sp(c).edit().putString(KEY_NAME, name == null ? "" : name.trim()).apply();
    }

    /**
     * Android no expone la MAC real, así que el receptor usa una propia, fija por
     * instalación (bit "local" encendido, unicast). El iPhone la usa como ID del equipo.
     */
    public static byte[] hwAddr(Context c) {
        String hex = sp(c).getString(KEY_HW, null);
        if (hex == null || hex.length() != 12) {
            byte[] b = new byte[6];
            new SecureRandom().nextBytes(b);
            b[0] = (byte) ((b[0] & 0xFC) | 0x02);
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format(Locale.US, "%02x", x));
            hex = sb.toString();
            sp(c).edit().putString(KEY_HW, hex).apply();
        }
        byte[] out = new byte[6];
        for (int i = 0; i < 6; i++) out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    public static String deviceId(Context c) {
        byte[] b = hwAddr(c);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i++) {
            if (i > 0) sb.append(':');
            sb.append(String.format(Locale.US, "%02X", b[i]));
        }
        return sb.toString();
    }

    /** Nombre de host mDNS único (Android responde "localhost" a gethostname). */
    public static String mdnsHost(Context c) {
        byte[] b = hwAddr(c);
        return String.format(Locale.US, "Hosted-Mirror-%02X%02X", b[4], b[5]);
    }
}
