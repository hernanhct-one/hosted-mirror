package cl.hosted.mirror;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;

/** Estado del receptor compartido entre el servicio y las pantallas (siempre en el hilo principal). */
public final class MirrorState {
    public enum Server { STARTING, READY, ERROR }

    public interface Listener {
        void onMirrorStateChanged(MirrorState s);
    }

    private static final MirrorState INSTANCE = new MirrorState();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    public Server server = Server.STARTING;
    public int errorCode;
    public String name = Prefs.DEFAULT_NAME;
    public int connections;
    public String clientName = "";
    public String clientModel = "";
    public boolean mirroring;
    public boolean audioOnly;

    public static MirrorState get() {
        return INSTANCE;
    }

    public void addListener(Listener l) {
        listeners.addIfAbsent(l);
        l.onMirrorStateChanged(this);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** Aplica un cambio en el hilo principal y avisa a las pantallas. */
    public void update(Runnable change) {
        main.post(() -> {
            change.run();
            for (Listener l : listeners) l.onMirrorStateChanged(this);
        });
    }
}
